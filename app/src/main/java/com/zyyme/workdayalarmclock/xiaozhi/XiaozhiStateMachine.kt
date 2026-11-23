package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 会话状态机：只根据事件产出「下一状态 + 该做的副作用」，不碰网络与音频，
 * 因此可以在 JVM 单测里完整跑一遍。
 *
 * 支持两种交互拓扑：
 * - 半双工（auto）：讲话阶段停上行（若开着语音唤醒则保留本地麦克风做 KWS）；
 * - 全双工实时打断（realtime）：整段会话期间麦克风与上行保持开启，
 *   配合服务端或本地抗回声 VAD 随时打断正在播报的 TTS。
 */
class XiaozhiStateMachine(
    var autoContinue: Boolean = true,
    var silenceTimeoutMs: Long = 8000L,
    var realtimeMode: Boolean = false,
    var keepCaptureWhileSpeaking: Boolean = false
) {

    /**
     * 通过“你好小智”唤醒时，服务端往往会先回一句“我在呢”，
     * 此时即使关闭了连续对话，播完唤醒应答后也应进入一次聆听等待用户下指令。
     */
    var expectListeningAfterTurn: Boolean = false

    enum class State {
        IDLE,
        CONNECTING,
        HANDSHAKING,
        LISTENING,
        THINKING,
        SPEAKING,
        ERROR
    }

    sealed class Event {
        object Start : Event()
        object TransportOpen : Event()
        data class HelloReceived(val sampleRate: Int) : Event()
        object HelloTimeout : Event()
        data class SttReceived(val text: String) : Event()
        object TtsStart : Event()
        data class TtsSentence(val text: String) : Event()
        object TtsStop : Event()
        object PlaybackDrained : Event()
        object SilenceTimeout : Event()
        object Interrupt : Event()
        data class WakeWordInterrupt(val wakeWord: String) : Event()
        object VoiceBargeIn : Event()
        data class Stop(val reason: String) : Event()
        data class TransportClosed(val reason: String) : Event()
        data class FatalError(val message: String) : Event()
    }

    sealed class SideEffect {
        /** 上行录音开始（若已在采集则保持不断流） */
        object StartCapture : SideEffect()

        /** 上行录音停止 */
        object StopCapture : SideEffect()

        /** 控制采集到的 PCM 是否送往 Opus 编码与 WebSocket 上行 */
        data class SetUplinkEnabled(val enabled: Boolean) : SideEffect()

        /** 清空编码队列里尚未发出的旧语音 */
        object FlushUplinkQueue : SideEffect()

        /** 补发打断前最后几百毫秒的预滚语音，避免吞掉开头音节 */
        object SendPrerollAudio : SideEffect()

        object SendListenStart : SideEffect()
        object SendListenStop : SideEffect()
        data class SendAbort(val reason: String = XiaozhiProtocol.ABORT_REASON_WAKE_WORD) : SideEffect()

        /** 丢掉播放器里还没播完的 TTS */
        object FlushPlayback : SideEffect()

        object CancelSilence : SideEffect()
        data class ScheduleSilence(val timeoutMs: Long) : SideEffect()
        data class Log(val message: String) : SideEffect()
    }

    data class Transition(
        val from: State,
        val to: State,
        val effects: List<SideEffect>
    )

    @Volatile
    var state: State = State.IDLE
        private set

    val isActive: Boolean
        get() = state != State.IDLE && state != State.ERROR

    fun reset() {
        state = State.IDLE
        expectListeningAfterTurn = false
    }

    /**
     * 处理一个事件。返回 null 表示该事件在当前状态下无意义（已被忽略，不改状态）。
     */
    fun accept(event: Event): Transition? {
        val from = state
        return when (event) {
            is Event.Start -> when (from) {
                State.IDLE, State.ERROR ->
                    go(from, State.CONNECTING, listOf(SideEffect.StopCapture, SideEffect.CancelSilence))
                else -> null
            }

            is Event.TransportOpen -> when (from) {
                State.CONNECTING -> go(from, State.HANDSHAKING, emptyList())
                else -> null
            }

            is Event.HelloReceived -> when (from) {
                State.HANDSHAKING -> go(
                    from, State.LISTENING,
                    listOf(
                        SideEffect.SendListenStart,
                        SideEffect.StartCapture,
                        SideEffect.SetUplinkEnabled(true),
                        SideEffect.ScheduleSilence(silenceTimeoutMs)
                    )
                )
                else -> null
            }

            is Event.HelloTimeout -> when (from) {
                State.HANDSHAKING -> {
                    expectListeningAfterTurn = false
                    go(
                        from, State.ERROR,
                        listOf(
                            SideEffect.StopCapture,
                            SideEffect.CancelSilence,
                            SideEffect.Log("握手超时：10 秒内没有收到 hello")
                        )
                    )
                }
                else -> null
            }

            is Event.SttReceived -> when (from) {
                State.LISTENING -> go(from, State.THINKING, captureEffectsForThinking(includeFlush = false))
                State.SPEAKING -> go(from, State.THINKING, captureEffectsForThinking(includeFlush = true))
                State.THINKING -> go(from, State.THINKING, emptyList())
                else -> null
            }

            is Event.TtsStart -> when (from) {
                State.LISTENING, State.THINKING, State.SPEAKING ->
                    go(from, State.SPEAKING, speakingEffects())
                else -> null
            }

            is Event.TtsSentence -> when (from) {
                State.LISTENING, State.THINKING ->
                    go(from, State.SPEAKING, speakingEffects())
                State.SPEAKING -> go(from, State.SPEAKING, emptyList())
                else -> null
            }

            is Event.TtsStop -> when (from) {
                State.SPEAKING -> go(from, State.SPEAKING, emptyList())
                // 服务端关闭唤醒问候语（enable_greeting=false）时会直接发 tts stop 而无音频
                State.LISTENING, State.THINKING -> {
                    expectListeningAfterTurn = false
                    go(
                        from, State.LISTENING,
                        listOf(
                            SideEffect.StartCapture,
                            SideEffect.SetUplinkEnabled(true),
                            SideEffect.ScheduleSilence(silenceTimeoutMs)
                        )
                    )
                }
                else -> null
            }

            is Event.PlaybackDrained -> when (from) {
                State.SPEAKING -> {
                    val shouldListen = autoContinue || expectListeningAfterTurn
                    expectListeningAfterTurn = false
                    if (shouldListen) {
                        val effects = if (realtimeMode) {
                            listOf(
                                SideEffect.StartCapture,
                                SideEffect.SetUplinkEnabled(true),
                                SideEffect.ScheduleSilence(silenceTimeoutMs)
                            )
                        } else {
                            listOf(
                                SideEffect.SendListenStart,
                                SideEffect.StartCapture,
                                SideEffect.SetUplinkEnabled(true),
                                SideEffect.ScheduleSilence(silenceTimeoutMs)
                            )
                        }
                        go(from, State.LISTENING, effects)
                    } else {
                        go(
                            from, State.IDLE,
                            listOf(
                                SideEffect.SendListenStop,
                                SideEffect.StopCapture,
                                SideEffect.CancelSilence
                            )
                        )
                    }
                }
                else -> null
            }

            is Event.SilenceTimeout -> when (from) {
                State.LISTENING -> {
                    expectListeningAfterTurn = false
                    go(
                        from, State.IDLE,
                        listOf(
                            SideEffect.SendListenStop,
                            SideEffect.StopCapture,
                            SideEffect.CancelSilence,
                            SideEffect.Log("静默超时，结束会话")
                        )
                    )
                }
                else -> null
            }

            is Event.Interrupt, is Event.WakeWordInterrupt, is Event.VoiceBargeIn -> when (from) {
                State.LISTENING, State.THINKING, State.SPEAKING -> {
                    expectListeningAfterTurn = false
                    val effects = mutableListOf<SideEffect>(
                        SideEffect.FlushPlayback,
                        SideEffect.SendAbort(XiaozhiProtocol.ABORT_REASON_WAKE_WORD),
                        SideEffect.FlushUplinkQueue,
                        SideEffect.SendListenStart,
                        SideEffect.StartCapture,
                        SideEffect.SetUplinkEnabled(true)
                    )
                    if (event is Event.VoiceBargeIn) {
                        effects.add(SideEffect.SendPrerollAudio)
                    }
                    effects.add(SideEffect.ScheduleSilence(silenceTimeoutMs))
                    go(from, State.LISTENING, effects)
                }
                else -> null
            }

            is Event.Stop -> when (from) {
                State.IDLE -> null
                else -> {
                    expectListeningAfterTurn = false
                    go(
                        from, State.IDLE,
                        listOf(
                            SideEffect.CancelSilence,
                            SideEffect.StopCapture,
                            SideEffect.SendListenStop
                        )
                    )
                }
            }

            is Event.TransportClosed -> when (from) {
                State.IDLE -> null
                else -> {
                    expectListeningAfterTurn = false
                    go(
                        from, State.ERROR,
                        listOf(
                            SideEffect.CancelSilence,
                            SideEffect.StopCapture,
                            SideEffect.Log("连接已断开：${event.reason}")
                        )
                    )
                }
            }

            is Event.FatalError -> when (from) {
                State.IDLE -> null
                else -> {
                    expectListeningAfterTurn = false
                    go(
                        from, State.ERROR,
                        listOf(
                            SideEffect.CancelSilence,
                            SideEffect.StopCapture,
                            SideEffect.Log("错误：${event.message}")
                        )
                    )
                }
            }
        }
    }

    private fun captureEffectsForThinking(includeFlush: Boolean): List<SideEffect> {
        val effects = ArrayList<SideEffect>(3)
        if (includeFlush) effects.add(SideEffect.FlushPlayback)
        if (!realtimeMode) {
            if (keepCaptureWhileSpeaking) {
                effects.add(SideEffect.SetUplinkEnabled(false))
            } else {
                effects.add(SideEffect.StopCapture)
            }
        }
        effects.add(SideEffect.CancelSilence)
        return effects
    }

    private fun speakingEffects(): List<SideEffect> {
        val effects = ArrayList<SideEffect>(3)
        effects.add(SideEffect.CancelSilence)
        effects.add(SideEffect.FlushPlayback)
        if (!realtimeMode) {
            if (keepCaptureWhileSpeaking) {
                effects.add(SideEffect.SetUplinkEnabled(false))
            } else {
                effects.add(SideEffect.StopCapture)
            }
        }
        return effects
    }

    private fun go(from: State, to: State, effects: List<SideEffect>): Transition {
        state = to
        return Transition(from, to, effects)
    }
}
