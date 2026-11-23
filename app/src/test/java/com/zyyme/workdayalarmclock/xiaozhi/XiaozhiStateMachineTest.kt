package com.zyyme.workdayalarmclock.xiaozhi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaozhiStateMachineTest {

    private fun handshakeToListening(sm: XiaozhiStateMachine) {
        assertEquals(XiaozhiStateMachine.State.CONNECTING, sm.accept(XiaozhiStateMachine.Event.Start)?.to)
        assertEquals(XiaozhiStateMachine.State.HANDSHAKING, sm.accept(XiaozhiStateMachine.Event.TransportOpen)?.to)
        assertEquals(XiaozhiStateMachine.State.LISTENING, sm.accept(XiaozhiStateMachine.Event.HelloReceived(24000))?.to)
    }

    @Test
    fun halfDuplexStopsCaptureOnSttWhenWakeWordDisabled() {
        val sm = XiaozhiStateMachine(
            autoContinue = true,
            silenceTimeoutMs = 8000L,
            realtimeMode = false,
            keepCaptureWhileSpeaking = false
        )
        handshakeToListening(sm)

        val stt = sm.accept(XiaozhiStateMachine.Event.SttReceived("你好"))!!
        assertEquals(XiaozhiStateMachine.State.THINKING, stt.to)
        assertTrue(stt.effects.contains(XiaozhiStateMachine.SideEffect.StopCapture))

        val ttsStart = sm.accept(XiaozhiStateMachine.Event.TtsStart)!!
        assertEquals(XiaozhiStateMachine.State.SPEAKING, ttsStart.to)

        val drained = sm.accept(XiaozhiStateMachine.Event.PlaybackDrained)!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, drained.to)
        assertTrue(drained.effects.contains(XiaozhiStateMachine.SideEffect.SendListenStart))
        assertTrue(drained.effects.contains(XiaozhiStateMachine.SideEffect.StartCapture))
    }

    @Test
    fun halfDuplexWithWakeWordKeepsCaptureAndPausesUplinkUntilWakeInterrupt() {
        val sm = XiaozhiStateMachine(
            autoContinue = true,
            silenceTimeoutMs = 8000L,
            realtimeMode = false,
            keepCaptureWhileSpeaking = true
        )
        handshakeToListening(sm)

        val stt = sm.accept(XiaozhiStateMachine.Event.SttReceived("讲个故事"))!!
        assertEquals(XiaozhiStateMachine.State.THINKING, stt.to)
        assertFalse(stt.effects.contains(XiaozhiStateMachine.SideEffect.StopCapture))
        assertTrue(stt.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(false)))

        sm.accept(XiaozhiStateMachine.Event.TtsStart)
        val wakeInterrupt = sm.accept(XiaozhiStateMachine.Event.WakeWordInterrupt("你好小智"))!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, wakeInterrupt.to)
        assertTrue(wakeInterrupt.effects.contains(XiaozhiStateMachine.SideEffect.FlushPlayback))
        assertTrue(wakeInterrupt.effects.any { it is XiaozhiStateMachine.SideEffect.SendAbort })
        assertTrue(wakeInterrupt.effects.contains(XiaozhiStateMachine.SideEffect.FlushUplinkQueue))
        assertTrue(wakeInterrupt.effects.contains(XiaozhiStateMachine.SideEffect.SendListenStart))
        assertTrue(wakeInterrupt.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(true)))
    }

    @Test
    fun realtimeModeKeepsFullDuplexAndSupportsLocalBargeInAndServerSttBargeIn() {
        val sm = XiaozhiStateMachine(
            autoContinue = true,
            silenceTimeoutMs = 8000L,
            realtimeMode = true,
            keepCaptureWhileSpeaking = true
        )
        handshakeToListening(sm)

        val stt = sm.accept(XiaozhiStateMachine.Event.SttReceived("今天天气"))!!
        assertEquals(XiaozhiStateMachine.State.THINKING, stt.to)
        assertFalse(stt.effects.contains(XiaozhiStateMachine.SideEffect.StopCapture))
        assertFalse(stt.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(false)))

        val ttsStart = sm.accept(XiaozhiStateMachine.Event.TtsStart)!!
        assertEquals(XiaozhiStateMachine.State.SPEAKING, ttsStart.to)
        assertFalse(ttsStart.effects.contains(XiaozhiStateMachine.SideEffect.StopCapture))

        val bargeIn = sm.accept(XiaozhiStateMachine.Event.VoiceBargeIn)!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, bargeIn.to)
        assertTrue(bargeIn.effects.contains(XiaozhiStateMachine.SideEffect.FlushPlayback))
        assertTrue(bargeIn.effects.contains(XiaozhiStateMachine.SideEffect.FlushUplinkQueue))
        assertTrue(bargeIn.effects.contains(XiaozhiStateMachine.SideEffect.SendListenStart))
        assertTrue(bargeIn.effects.contains(XiaozhiStateMachine.SideEffect.SendPrerollAudio))

        // 再次进入 SPEAKING 后测试服务端直接下发 Stt 触发打断，以及正常排空不重复发 listen start
        sm.accept(XiaozhiStateMachine.Event.TtsStart)
        val serverBargeIn = sm.accept(XiaozhiStateMachine.Event.SttReceived("别说了"))!!
        assertEquals(XiaozhiStateMachine.State.THINKING, serverBargeIn.to)
        assertTrue(serverBargeIn.effects.contains(XiaozhiStateMachine.SideEffect.FlushPlayback))

        sm.accept(XiaozhiStateMachine.Event.TtsStart)
        val drained = sm.accept(XiaozhiStateMachine.Event.PlaybackDrained)!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, drained.to)
        assertFalse(drained.effects.contains(XiaozhiStateMachine.SideEffect.SendListenStart))
    }

    @Test
    fun wakeGreetingEntersListeningEvenWhenAutoContinueDisabledAndHandlesTtsStopWithoutAudio() {
        val sm = XiaozhiStateMachine(
            autoContinue = false,
            silenceTimeoutMs = 8000L,
            realtimeMode = false,
            keepCaptureWhileSpeaking = true
        )
        handshakeToListening(sm)
        sm.expectListeningAfterTurn = true

        sm.accept(XiaozhiStateMachine.Event.TtsStart)
        val afterGreeting = sm.accept(XiaozhiStateMachine.Event.PlaybackDrained)!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, afterGreeting.to)
        assertFalse(sm.expectListeningAfterTurn)

        // 第二轮正常问完后（expectListeningAfterTurn 为 false 且 autoContinue=false）应当回到 IDLE
        sm.accept(XiaozhiStateMachine.Event.SttReceived("几点了"))
        sm.accept(XiaozhiStateMachine.Event.TtsStart)
        val afterAnswer = sm.accept(XiaozhiStateMachine.Event.PlaybackDrained)!!
        assertEquals(XiaozhiStateMachine.State.IDLE, afterAnswer.to)

        // enable_greeting=false 时服务端在 THINKING/LISTENING 直接下发 tts stop
        val sm2 = XiaozhiStateMachine(autoContinue = false, realtimeMode = true, keepCaptureWhileSpeaking = true)
        handshakeToListening(sm2)
        sm2.expectListeningAfterTurn = true
        sm2.accept(XiaozhiStateMachine.Event.SttReceived("你好小智"))
        val tStop = sm2.accept(XiaozhiStateMachine.Event.TtsStop)!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, tStop.to)
        assertTrue(tStop.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(true)))
    }

    @Test
    fun wakeWordInterruptWithoutRealtimePausesUplinkAndStillAllowsWakeInterrupt() {
        val sm = XiaozhiStateMachine(
            autoContinue = true,
            silenceTimeoutMs = 8000L,
            realtimeMode = false,
            keepCaptureWhileSpeaking = true
        )
        handshakeToListening(sm)

        val stt = sm.accept(XiaozhiStateMachine.Event.SttReceived("讲个笑话"))!!
        assertEquals(XiaozhiStateMachine.State.THINKING, stt.to)
        assertTrue(stt.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(false)))
        assertFalse(stt.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(true)))

        val tts = sm.accept(XiaozhiStateMachine.Event.TtsStart)!!
        assertEquals(XiaozhiStateMachine.State.SPEAKING, tts.to)
        assertTrue(tts.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(false)))
        assertFalse(tts.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(true)))

        val wake = sm.accept(XiaozhiStateMachine.Event.WakeWordInterrupt("你好小智"))!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, wake.to)
        assertTrue(wake.effects.contains(XiaozhiStateMachine.SideEffect.FlushPlayback))
        assertTrue(wake.effects.contains(XiaozhiStateMachine.SideEffect.SetUplinkEnabled(true)))
    }
}
