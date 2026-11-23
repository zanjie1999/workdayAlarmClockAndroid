package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 语音实时打断（Barge-in）抗回声判定器（纯 Kotlin，不依赖 Android）。
 *
 * 思路：
 * 1. TTS 刚开播的 [graceWindowMs]（默认 600ms）内不触发打断，留给 HAL AEC 收敛，
 *    并顺带观测实际扬声器回声泄露比例 alpha = micRms / playbackRmsPeak。
 * 2. 如果 alpha > [maxAcceptableEchoRatio]（说明当前机型在扬声器外放时硬件 AEC 基本没压住回声），
 *    本轮自动关闭本地能量/VAD 快打断，避免 AI 把自己的声音当成用户打断（仍保留唤醒词与按键打断）。
 * 3. 在 HAL AEC 有效的情况下，必须同时满足 Silero VAD 人声概率 >= [minVadProb]
 *    且麦克风 RMS 超过随播报响度自适应的阈值，并持续 [sustainMs] 以上才触发打断。
 */
class XiaozhiBargeInDetector(
    private val graceWindowMs: Long = 600L,
    private val sustainMs: Long = 260L,
    private val cooldownMs: Long = 1200L,
    private val minSpeechRms: Double = 1200.0,
    private val minVadProb: Float = 0.65f,
    private val maxAcceptableEchoRatio: Double = 0.55
) {

    enum class Result {
        NONE,
        TRIGGERED,
        ECHO_SUPPRESSED_ONCE
    }

    private var speakingStartMs: Long = -1L
    private var calibratedAlpha: Double = 0.0
    private var speechMs: Long = 0L
    private var lastTriggeredAt: Long = Long.MIN_VALUE / 4
    private var echoSuppressedNotified: Boolean = false

    val echoLeakRatio: Double get() = calibratedAlpha

    fun reset() {
        speakingStartMs = -1L
        calibratedAlpha = 0.0
        speechMs = 0L
        echoSuppressedNotified = false
    }

    fun onSpeakingStarted(nowMs: Long) {
        speakingStartMs = nowMs
        calibratedAlpha = 0.0
        speechMs = 0L
        echoSuppressedNotified = false
    }

    fun onFrame(
        micRms: Double,
        playbackRmsPeak: Double,
        vadProb: Float?,
        frameMs: Long,
        nowMs: Long
    ): Result {
        if (speakingStartMs < 0L) speakingStartMs = nowMs

        val elapsed = nowMs - speakingStartMs
        if (elapsed < graceWindowMs) {
            if (playbackRmsPeak >= 400.0) {
                val ratio = micRms / playbackRmsPeak
                if (ratio > calibratedAlpha) calibratedAlpha = ratio
            }
            return Result.NONE
        }

        if (calibratedAlpha > maxAcceptableEchoRatio) {
            speechMs = 0L
            return if (!echoSuppressedNotified) {
                echoSuppressedNotified = true
                Result.ECHO_SUPPRESSED_ONCE
            } else {
                Result.NONE
            }
        }

        val dynamicThreshold = maxOf(
            minSpeechRms,
            playbackRmsPeak * maxOf(0.30, calibratedAlpha * 2.4)
        )
        val vadOk = vadProb == null || vadProb >= minVadProb
        val energyOk = micRms >= dynamicThreshold

        if (vadOk && energyOk) {
            speechMs += frameMs
            if (speechMs >= sustainMs && nowMs - lastTriggeredAt >= cooldownMs) {
                lastTriggeredAt = nowMs
                speechMs = 0L
                return Result.TRIGGERED
            }
        } else {
            speechMs = maxOf(0L, speechMs - frameMs * 2L)
        }
        return Result.NONE
    }
}
