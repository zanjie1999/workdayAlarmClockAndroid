package com.zyyme.workdayalarmclock.xiaozhi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class XiaozhiSoftwareAecTest {

    private fun rms(samples: ShortArray, start: Int = 0, end: Int = samples.size): Double {
        if (end <= start) return 0.0
        var sum = 0.0
        for (i in start until end) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return Math.sqrt(sum / (end - start))
    }

    private fun generateSpeechLike16k(totalSamples: Int, seed: Long): ShortArray {
        val rnd = Random(seed)
        val out = ShortArray(totalSamples)
        for (n in 0 until totalSamples) {
            val t = n / 16000.0
            val env = 0.55 + 0.45 * Math.sin(2.0 * Math.PI * 3.1 * t)
            val tone = 0.42 * Math.sin(2.0 * Math.PI * 195.0 * t) +
                0.28 * Math.sin(2.0 * Math.PI * 430.0 * t + 0.4) +
                0.18 * Math.sin(2.0 * Math.PI * 890.0 * t + 1.1) +
                0.10 * Math.sin(2.0 * Math.PI * 1570.0 * t) +
                0.12 * (rnd.nextDouble() * 2.0 - 1.0)
            val v = (env * tone * 0.45 * Short.MAX_VALUE).toInt()
            out[n] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun upsample16kTo24k(src16k: ShortArray): ShortArray {
        val outLen = src16k.size * 3 / 2
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val pos = i * 2.0 / 3.0
            val idx = pos.toInt().coerceAtMost(src16k.size - 2)
            val frac = pos - idx
            val v = src16k[idx] * (1.0 - frac) + src16k[idx + 1] * frac
            out[i] = v.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    @Test
    fun fftRoundTripMatchesInput() {
        val fft = XiaozhiSoftwareAec.Fft512()
        val re = FloatArray(512)
        val im = FloatArray(512)
        val expected = FloatArray(512)
        val rnd = Random(123L)
        for (i in 0 until 512) {
            val v = (rnd.nextFloat() * 2f - 1f) * 0.8f
            re[i] = v
            expected[i] = v
        }
        fft.forward(re, im)
        fft.inverse(re, im)
        for (i in 0 until 512) {
            assertEquals("real mismatch at $i", expected[i], re[i], 1e-4f)
            assertEquals("imag mismatch at $i", 0f, im[i], 1e-4f)
        }
    }

    @Test
    fun cancelsSimulatedDelayedEchoAt24kFarEnd() {
        val aec = XiaozhiSoftwareAec()
        val totalMs = 2200
        val total16k = totalMs * 16
        val far16 = generateSpeechLike16k(total16k, seed = 42L)
        val far24 = upsample16kTo24k(far16)

        val delaySamples = 1920 // 120ms @ 16kHz
        val near = ShortArray(total16k)
        val rnd = Random(77L)
        for (n in 0 until total16k) {
            val e0 = if (n >= delaySamples) far16[n - delaySamples] * 0.65 else 0.0
            val e1 = if (n >= delaySamples + 17) far16[n - delaySamples - 17] * 0.12 else 0.0
            val hiss = (rnd.nextDouble() * 2.0 - 1.0) * 60.0
            near[n] = (e0 + e1 + hiss).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val out = ShortArray(total16k)
        var pos16 = 0
        var pos24 = 0
        // 模拟真实播放器 60ms (1440 @ 24k) 与采集器 20ms (320 @ 16k) 交替运行
        while (pos16 + 320 <= total16k) {
            if (pos16 % 960 == 0 && pos24 + 1440 <= far24.size) {
                val pkt24 = far24.copyOfRange(pos24, pos24 + 1440)
                aec.feedFarEnd(pkt24, pkt24.size, 24000)
                pos24 += 1440
            }
            val micChunk = near.copyOfRange(pos16, pos16 + 320)
            val clean = aec.processNearEnd(micChunk, micChunk.size)
            assertEquals(320, clean.size)
            System.arraycopy(clean, 0, out, pos16, 320)
            pos16 += 320
        }

        // 收敛后窗口：1400ms ~ 2100ms
        val evalStart = 1400 * 16
        val evalEnd = 2100 * 16
        val inRms = rms(near, evalStart, evalEnd)
        val outRms = rms(out, evalStart, evalEnd)
        val ratio = outRms / inRms
        val reductionDb = 20.0 * Math.log10(1.0 / ratio.coerceAtLeast(1e-6))

        assertTrue("expected far-end active", aec.isFarEndActive)
        assertTrue(
            "expected delay near 120ms, got ${aec.estimatedDelayMs}ms",
            aec.estimatedDelayMs in 100..140
        )
        assertTrue(
            "expected >= 12 dB echo reduction (ratio <= 0.25), got ${String.format("%.1f", reductionDb)} dB (ratio=$ratio)",
            ratio <= 0.25
        )
    }

    @Test
    fun preservesNearEndSpeechDuringDoubleTalk() {
        val aec = XiaozhiSoftwareAec()
        val total16k = 2400 * 16
        val far16 = generateSpeechLike16k(total16k, seed = 101L)
        val far24 = upsample16kTo24k(far16)
        val delaySamples = 1600 // 100ms

        // 1500ms ~ 2200ms 注入近端人声（与远端频率不同）
        val pureSpeech = ShortArray(total16k)
        val dtStart = 1500 * 16
        val dtEnd = 2200 * 16
        for (n in dtStart until dtEnd) {
            val t = n / 16000.0
            val s = (0.50 * Math.sin(2.0 * Math.PI * 260.0 * t) +
                0.35 * Math.sin(2.0 * Math.PI * 520.0 * t) +
                0.20 * Math.sin(2.0 * Math.PI * 980.0 * t)) * 0.42 * Short.MAX_VALUE
            pureSpeech[n] = s.toInt().toShort()
        }

        val near = ShortArray(total16k)
        for (n in 0 until total16k) {
            val echo = if (n >= delaySamples) far16[n - delaySamples] * 0.50 else 0.0
            near[n] = (echo + pureSpeech[n]).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        val out = ShortArray(total16k)
        var pos16 = 0
        var pos24 = 0
        while (pos16 + 320 <= total16k) {
            if (pos16 % 960 == 0 && pos24 + 1440 <= far24.size) {
                val pkt24 = far24.copyOfRange(pos24, pos24 + 1440)
                aec.feedFarEnd(pkt24, pkt24.size, 24000)
                pos24 += 1440
            }
            val micChunk = near.copyOfRange(pos16, pos16 + 320)
            val clean = aec.processNearEnd(micChunk, micChunk.size)
            System.arraycopy(clean, 0, out, pos16, 320)
            pos16 += 320
        }

        val speechInRms = rms(pureSpeech, dtStart + 1600, dtEnd)
        val speechOutRms = rms(out, dtStart + 1600, dtEnd)
        assertTrue(
            "double-talk speech over-attenuated: out=$speechOutRms vs pure=$speechInRms",
            speechOutRms >= speechInRms * 0.30
        )
    }

    @Test
    fun reducesStationaryNoiseWhenFarEndSilent() {
        val aec = XiaozhiSoftwareAec()
        val total16k = 1600 * 16
        val rnd = Random(555L)
        val noise = ShortArray(total16k) {
            ((rnd.nextDouble() * 2.0 - 1.0) * 1400.0).toInt().toShort()
        }
        val out = ShortArray(total16k)
        var pos = 0
        while (pos + 320 <= total16k) {
            val chunk = noise.copyOfRange(pos, pos + 320)
            val clean = aec.processNearEnd(chunk, chunk.size)
            System.arraycopy(clean, 0, out, pos, 320)
            pos += 320
        }
        val inRms = rms(noise, 900 * 16, 1500 * 16)
        val outRms = rms(out, 900 * 16, 1500 * 16)
        assertTrue(
            "expected stationary noise reduction, ratio=${outRms / inRms}",
            outRms / inRms <= 0.45
        )
    }

    @Test
    fun clearFarEndAndResetRestoreCleanState() {
        val aec = XiaozhiSoftwareAec()
        val far = generateSpeechLike16k(1920, seed = 9L)
        aec.feedFarEnd(far, far.size, 16000)
        aec.processNearEnd(ShortArray(320), 320)
        assertTrue(aec.isFarEndActive)

        aec.clearFarEnd()
        assertFalse(aec.isFarEndActive)

        aec.reset()
        assertFalse(aec.isFarEndActive)
        assertEquals(60, aec.estimatedDelayMs)
    }
}
