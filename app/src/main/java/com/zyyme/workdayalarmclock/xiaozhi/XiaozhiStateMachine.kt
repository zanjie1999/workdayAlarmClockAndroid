package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 会话状态机：只根据事件产出「下一状态 + 该做的副作用」，不碰网络与音频，
 * 因此可以在 JVM 单测里完整跑一遍。
 *
 * 真正的副作用执行在 XiaozhiAssistant 里（控制线程），顺序即列表顺序。
 */
class XiaozhiStateMachine(
    var autoContinue: Boolean = true,
    var silenceTimeoutMs: Long = 8000L
) {

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
        data class Stop(val reason: String) : Event()
        data class TransportClosed(val reason: String) : Event()
        data class FatalError(val message: String) : Event()
    }

    sealed class SideEffect {
        /** 上行录音开始（同时触发宿主 ducking） */
        object StartCapture : SideEffect()

        /** 上行录音停止（同时解除 ducking） */
        object StopCapture : SideEffect()

        object SendListenStart : SideEffect()
        object SendListenStop : SideEffect()
        object SendAbort : SideEffect()

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

    var state: State = State.IDLE
        private set

    val isActive: Boolean
        get() = state != State.IDLE && state != State.ERROR

    fun reset() {
        state = State.IDLE
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
                        SideEffect.ScheduleSilence(silenceTimeoutMs)
                    )
                )
                else -> null
            }

            is Event.HelloTimeout -> when (from) {
                State.HANDSHAKING -> go(
                    from, State.ERROR,
                    listOf(
                        SideEffect.StopCapture,
                        SideEffect.CancelSilence,
                        SideEffect.Log("握手超时：10 秒内没有收到 hello")
                    )
                )
                else -> null
            }

            is Event.SttReceived -> when (from) {
                // 服务端已经识别出人声，录音可以关了（它在思考阶段本就会丢弃上行帧）
                State.LISTENING -> go(
                    from, State.THINKING,
                    listOf(SideEffect.StopCapture, SideEffect.CancelSilence)
                )
                else -> null
            }

            is Event.TtsStart -> when (from) {
                State.THINKING, State.SPEAKING -> go(from, State.SPEAKING, listOf(SideEffect.FlushPlayback))
                else -> null
            }

            is Event.TtsSentence -> when (from) {
                State.THINKING -> go(from, State.SPEAKING, listOf(SideEffect.FlushPlayback))
                State.SPEAKING -> go(from, State.SPEAKING, emptyList())
                else -> null
            }

            is Event.TtsStop -> when (from) {
                State.THINKING -> go(from, State.SPEAKING, listOf(SideEffect.FlushPlayback))
                State.SPEAKING -> go(from, State.SPEAKING, emptyList())
                else -> null
            }

            is Event.PlaybackDrained -> when (from) {
                State.SPEAKING ->
                    if (autoContinue) {
                        go(
                            from, State.LISTENING,
                            listOf(
                                SideEffect.SendListenStart,
                                SideEffect.StartCapture,
                                SideEffect.ScheduleSilence(silenceTimeoutMs)
                            )
                        )
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
                else -> null
            }

            is Event.SilenceTimeout -> when (from) {
                State.LISTENING -> go(
                    from, State.IDLE,
                    listOf(
                        SideEffect.SendListenStop,
                        SideEffect.StopCapture,
                        SideEffect.CancelSilence,
                        SideEffect.Log("静默超时，结束会话")
                    )
                )
                else -> null
            }

            // 双击打断：丢掉正在说的话，立刻重新听
            is Event.Interrupt -> when (from) {
                State.LISTENING, State.THINKING, State.SPEAKING -> go(
                    from, State.LISTENING,
                    listOf(
                        SideEffect.FlushPlayback,
                        SideEffect.SendAbort,
                        SideEffect.StopCapture,
                        SideEffect.SendListenStart,
                        SideEffect.StartCapture,
                        SideEffect.ScheduleSilence(silenceTimeoutMs)
                    )
                )
                else -> null
            }

            is Event.Stop -> when (from) {
                State.IDLE -> null
                else -> go(
                    from, State.IDLE,
                    listOf(
                        SideEffect.CancelSilence,
                        SideEffect.StopCapture,
                        SideEffect.SendListenStop
                    )
                )
            }

            is Event.TransportClosed -> when (from) {
                State.IDLE -> null
                else -> go(
                    from, State.ERROR,
                    listOf(
                        SideEffect.CancelSilence,
                        SideEffect.StopCapture,
                        SideEffect.Log("连接已断开：${event.reason}")
                    )
                )
            }

            is Event.FatalError -> when (from) {
                State.IDLE -> null
                else -> go(
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

    private fun go(from: State, to: State, effects: List<SideEffect>): Transition {
        state = to
        return Transition(from, to, effects)
    }
}
