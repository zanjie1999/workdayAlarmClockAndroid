package com.zyyme.workdayalarmclock.xiaozhi

import org.junit.Assert.assertEquals
import org.junit.Test

class XiaozhiBargeInDetectorTest {

    @Test
    fun triggersAfterGraceAndSustainedSpeechWhenAecEffective() {
        val detector = XiaozhiBargeInDetector()
        detector.onSpeakingStarted(1000L)

        // Grace window (0..599ms): mild residual echo alpha = 300 / 3000 = 0.10
        for (t in 1000L until 1600L step 20L) {
            val res = detector.onFrame(
                micRms = 300.0,
                playbackRmsPeak = 3000.0,
                vadProb = 0.2f,
                frameMs = 20L,
                nowMs = t
            )
            assertEquals(XiaozhiBargeInDetector.Result.NONE, res)
        }
        assertEquals(0.10, detector.echoLeakRatio, 0.001)

        // User starts speaking over TTS for 240ms -> not yet triggered (< 260ms)
        var triggered = false
        for (i in 0 until 12) {
            val res = detector.onFrame(
                micRms = 2200.0,
                playbackRmsPeak = 3000.0,
                vadProb = 0.85f,
                frameMs = 20L,
                nowMs = 1600L + i * 20L
            )
            if (res == XiaozhiBargeInDetector.Result.TRIGGERED) triggered = true
        }
        assertEquals(false, triggered)

        // Frame 13 (260ms) crosses sustain threshold
        val hit = detector.onFrame(
            micRms = 2200.0,
            playbackRmsPeak = 3000.0,
            vadProb = 0.85f,
            frameMs = 20L,
            nowMs = 1840L
        )
        assertEquals(XiaozhiBargeInDetector.Result.TRIGGERED, hit)

        // Immediate cooldown prevents duplicate triggers
        val duringCooldown = detector.onFrame(
            micRms = 2500.0,
            playbackRmsPeak = 3000.0,
            vadProb = 0.90f,
            frameMs = 20L,
            nowMs = 2200L
        )
        assertEquals(XiaozhiBargeInDetector.Result.NONE, duringCooldown)
    }

    @Test
    fun suppressesLocalAutoBargeInWhenStrongEchoObserved() {
        val detector = XiaozhiBargeInDetector()
        detector.onSpeakingStarted(0L)

        // Device lacks HAL AEC: speaker playback leaks at 80% into mic
        for (t in 0L until 600L step 20L) {
            assertEquals(
                XiaozhiBargeInDetector.Result.NONE,
                detector.onFrame(2400.0, 3000.0, 0.9f, 20L, t)
            )
        }

        // First post-grace frame notifies once; subsequent frames stay suppressed
        assertEquals(
            XiaozhiBargeInDetector.Result.ECHO_SUPPRESSED_ONCE,
            detector.onFrame(3500.0, 3000.0, 0.95f, 20L, 600L)
        )
        for (i in 1..20) {
            assertEquals(
                XiaozhiBargeInDetector.Result.NONE,
                detector.onFrame(3500.0, 3000.0, 0.95f, 20L, 600L + i * 20L)
            )
        }
    }
}
