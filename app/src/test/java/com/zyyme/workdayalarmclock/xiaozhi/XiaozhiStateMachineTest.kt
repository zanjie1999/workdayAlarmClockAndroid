package com.zyyme.workdayalarmclock.xiaozhi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaozhiStateMachineTest {

    private fun machine(autoContinue: Boolean = true) =
        XiaozhiStateMachine(autoContinue = autoContinue, silenceTimeoutMs = 8000L)

    private fun XiaozhiStateMachine.toListening() {
        accept(XiaozhiStateMachine.Event.Start)
        accept(XiaozhiStateMachine.Event.TransportOpen)
        accept(XiaozhiStateMachine.Event.HelloReceived(24000))
    }

    @Test
    fun happyPath() {
        val sm = machine()
        assertEquals(XiaozhiStateMachine.State.IDLE, sm.state)

        sm.accept(XiaozhiStateMachine.Event.Start)
        assertEquals(XiaozhiStateMachine.State.CONNECTING, sm.state)

        sm.accept(XiaozhiStateMachine.Event.TransportOpen)
        assertEquals(XiaozhiStateMachine.State.HANDSHAKING, sm.state)

        val hello = sm.accept(XiaozhiStateMachine.Event.HelloReceived(24000))!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, hello.to)
        assertTrue(hello.effects.contains(XiaozhiStateMachine.SideEffect.SendListenStart))
        assertTrue(hello.effects.contains(XiaozhiStateMachine.SideEffect.StartCapture))

        val stt = sm.accept(XiaozhiStateMachine.Event.SttReceived("你好"))!!
        assertEquals(XiaozhiStateMachine.State.THINKING, stt.to)
        assertTrue(stt.effects.contains(XiaozhiStateMachine.SideEffect.StopCapture))

        sm.accept(XiaozhiStateMachine.Event.TtsStart)
        assertEquals(XiaozhiStateMachine.State.SPEAKING, sm.state)

        sm.accept(XiaozhiStateMachine.Event.TtsStop)
        val drained = sm.accept(XiaozhiStateMachine.Event.PlaybackDrained)!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, drained.to)
        assertTrue(drained.effects.contains(XiaozhiStateMachine.SideEffect.StartCapture))
    }

    @Test
    fun silenceTimeoutEndsSession() {
        val sm = machine()
        sm.toListening()
        val transition = sm.accept(XiaozhiStateMachine.Event.SilenceTimeout)!!
        assertEquals(XiaozhiStateMachine.State.IDLE, sm.state)
        assertTrue(transition.effects.contains(XiaozhiStateMachine.SideEffect.SendListenStop))
        assertTrue(transition.effects.contains(XiaozhiStateMachine.SideEffect.StopCapture))
    }

    @Test
    fun interruptWhileSpeakingRestartsListeningWithoutDrain() {
        val sm = machine()
        sm.toListening()
        sm.accept(XiaozhiStateMachine.Event.SttReceived("讲个故事"))
        sm.accept(XiaozhiStateMachine.Event.TtsStart)
        sm.accept(XiaozhiStateMachine.Event.TtsSentence("从前"))

        val transition = sm.accept(XiaozhiStateMachine.Event.Interrupt)!!
        assertEquals(XiaozhiStateMachine.State.LISTENING, sm.state)
        val effects = transition.effects
        // 顺序：先丢音频，再 abort，再重新开始听
        assertTrue(effects.indexOf(XiaozhiStateMachine.SideEffect.FlushPlayback) <
                effects.indexOf(XiaozhiStateMachine.SideEffect.SendAbort))
        assertTrue(effects.indexOf(XiaozhiStateMachine.SideEffect.SendAbort) <
                effects.indexOf(XiaozhiStateMachine.SideEffect.SendListenStart))
        assertTrue(effects.contains(XiaozhiStateMachine.SideEffect.StartCapture))
    }

    @Test
    fun interruptWhileIdleIgnored() {
        val sm = machine()
        assertNull(sm.accept(XiaozhiStateMachine.Event.Interrupt))
        assertEquals(XiaozhiStateMachine.State.IDLE, sm.state)
    }

    @Test
    fun drainedWithoutAutoContinueStopsSession() {
        val sm = machine(autoContinue = false)
        sm.toListening()
        sm.accept(XiaozhiStateMachine.Event.SttReceived("hi"))
        sm.accept(XiaozhiStateMachine.Event.TtsStart)
        sm.accept(XiaozhiStateMachine.Event.TtsStop)
        sm.accept(XiaozhiStateMachine.Event.PlaybackDrained)
        assertEquals(XiaozhiStateMachine.State.IDLE, sm.state)
    }

    @Test
    fun stalledDrainDoesNothing() {
        val sm = machine()
        sm.toListening()
        assertNull(sm.accept(XiaozhiStateMachine.Event.PlaybackDrained))
        assertEquals(XiaozhiStateMachine.State.LISTENING, sm.state)
    }

    @Test
    fun transportClosedGoesToError() {
        val sm = machine()
        sm.toListening()
        sm.accept(XiaozhiStateMachine.Event.TransportClosed("io"))
        assertEquals(XiaozhiStateMachine.State.ERROR, sm.state)
        assertFalse(sm.isActive)
    }

    @Test
    fun canRestartFromError() {
        val sm = machine()
        sm.toListening()
        sm.accept(XiaozhiStateMachine.Event.FatalError("boom"))
        assertEquals(XiaozhiStateMachine.State.ERROR, sm.state)
        sm.accept(XiaozhiStateMachine.Event.Start)
        assertEquals(XiaozhiStateMachine.State.CONNECTING, sm.state)
    }

    @Test
    fun duplicateStartIgnored() {
        val sm = machine()
        sm.accept(XiaozhiStateMachine.Event.Start)
        assertNull(sm.accept(XiaozhiStateMachine.Event.Start))
        assertEquals(XiaozhiStateMachine.State.CONNECTING, sm.state)
    }

    @Test
    fun helloTimeoutIsFatal() {
        val sm = machine()
        sm.accept(XiaozhiStateMachine.Event.Start)
        sm.accept(XiaozhiStateMachine.Event.TransportOpen)
        sm.accept(XiaozhiStateMachine.Event.HelloTimeout)
        assertEquals(XiaozhiStateMachine.State.ERROR, sm.state)
    }

    @Test
    fun stopFromAnyStateReturnsToIdle() {
        val sm = machine()
        sm.toListening()
        sm.accept(XiaozhiStateMachine.Event.Stop("user"))
        assertEquals(XiaozhiStateMachine.State.IDLE, sm.state)
    }

    @Test
    fun ttsSentenceWhileThinkingEntersSpeaking() {
        val sm = machine()
        sm.toListening()
        sm.accept(XiaozhiStateMachine.Event.SttReceived("hi"))
        sm.accept(XiaozhiStateMachine.Event.TtsSentence("嗯"))
        assertEquals(XiaozhiStateMachine.State.SPEAKING, sm.state)
    }
}
