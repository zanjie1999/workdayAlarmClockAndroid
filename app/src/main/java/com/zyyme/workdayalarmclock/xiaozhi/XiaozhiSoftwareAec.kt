package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 纯 Kotlin 实现的软件回声消除（PBFDAF）+ 残余回声抑制（RES）+ 平稳降噪（NS）。
 *
 * 面向无硬件 AEC 或硬件回声消除效果较差的设备：
 * 1. 下行 TTS 解码后通过 [feedFarEnd] 喂入 16kHz 参考环形缓冲，使用平滑播放游标避免 60ms Opus
 *    包突发引起的时延抖动；
 * 2. 上行通过 4kHz 降采样归一化互相关估计扬声器到麦克风的声学+缓冲总延迟（0~400ms）；
 * 3. 采用 4 分区 512 点约束频域自适应滤波（PBFDAF）抵消线性回声，带双讲（Double-Talk）
 *    步长保护与发散回滚；
 * 4. 后端 512 点 50% 重叠 `sqrt(Hann)` 维纳谱增益同时压制非线性残余回声与稳态底噪，
 *    保持任意输入块长度 [processNearEnd] 等长输出（固定 16ms 算法延迟）。
 */
class XiaozhiSoftwareAec {

    private val farLock = Any()

    // ---- 下行参考缓冲（写入侧） ----
    private val farStaging = FloatArray(FAR_STAGING_CAP)
    private var farStagingRead = 0L
    private var farStagingWrite = 0L
    private var farHpfPrevX = 0f
    private var farHpfPrevY = 0f
    private var farResamplePos = 0f
    private var farResampleLastSample = 0f
    private var farResampleHasLast = false

    // ---- 对齐后的 16kHz 参考历史与 4kHz 时延搜索历史 ----
    private val farAlignedRing = FloatArray(FAR_ALIGNED_SIZE)
    private var farAlignedWrite = 0L
    private val farDecimRing = FloatArray(DECIM_FAR_SIZE)
    private var farDecimWrite = 0L

    private var farActiveHoldHops = 0

    // ---- 上行 16kHz 输入与输出 FIFO ----
    private val nearInBuf = FloatArray(HOP_SIZE)
    private var nearInCount = 0
    private var nearHpfPrevX = 0f
    private var nearHpfPrevY = 0f

    private val nearWorkRing = FloatArray(FAR_ALIGNED_SIZE)
    private var nearWorkWrite = 0L
    private val nearDecimRing = FloatArray(DECIM_NEAR_SIZE)
    private var nearDecimWrite = 0L

    private val outFifo = FloatArray(OUT_FIFO_CAP)
    private var outFifoRead = 0
    private var outFifoWrite = 0
    private var outFifoCount = 0

    // ---- 时延估计 ----
    @Volatile
    private var estimatedDelaySamples = DEFAULT_DELAY_SAMPLES
    private var delayLocked = false
    private var hopCounter = 0L

    // ---- PBFDAF 状态（P = 4 分区, FFT_SIZE = 512） ----
    private val farFftRingRe = Array(DELAY_BLOCKS) { FloatArray(FFT_SIZE) }
    private val farFftRingIm = Array(DELAY_BLOCKS) { FloatArray(FFT_SIZE) }
    private val wRe = Array(PARTITIONS) { FloatArray(FFT_SIZE) }
    private val wIm = Array(PARTITIONS) { FloatArray(FFT_SIZE) }

    // ---- RES + NS 状态 ----
    private val prevLinearResidual = FloatArray(HOP_SIZE)
    private val prevYHat = FloatArray(HOP_SIZE)
    private val prevOlaTail = FloatArray(HOP_SIZE)
    private val noisePsd = FloatArray(HALF_BINS) { INIT_NOISE_PSD }
    private val smoothedPsd = FloatArray(HALF_BINS) { INIT_NOISE_PSD }
    private val noiseBlockMin = FloatArray(HALF_BINS) { Float.MAX_VALUE }
    private var noiseSeeded = false
    private var noiseBlockHops = 0
    private val echoLeakFactor = FloatArray(HALF_BINS) { 0.45f }
    private val smoothGain = FloatArray(HALF_BINS) { 1.0f }

    // ---- 热循环复用数组（零 GC 分配） ----
    private val fftRe = FloatArray(FFT_SIZE)
    private val fftIm = FloatArray(FFT_SIZE)
    private val yHatSpecRe = FloatArray(FFT_SIZE)
    private val yHatSpecIm = FloatArray(FFT_SIZE)
    private val gradRe = FloatArray(FFT_SIZE)
    private val gradIm = FloatArray(FFT_SIZE)
    private val errSpecRe = FloatArray(FFT_SIZE)
    private val errSpecIm = FloatArray(FFT_SIZE)
    private val farPowSum = FloatArray(FFT_SIZE)
    private val eTime = FloatArray(HOP_SIZE)
    private val yHatTime = FloatArray(HOP_SIZE)
    private val yHatResSpecRe = FloatArray(FFT_SIZE)
    private val yHatResSpecIm = FloatArray(FFT_SIZE)
    private val farDelayedPsd = FloatArray(HALF_BINS)
    private val rawGain = FloatArray(HALF_BINS)

    private val fft = Fft512()

    val estimatedDelayMs: Int
        get() = estimatedDelaySamples * 1000 / SAMPLE_RATE

    val isFarEndActive: Boolean
        get() = farActiveHoldHops > 0

    init {
        reset()
    }

    fun reset() {
        synchronized(farLock) {
            farStaging.fill(0f)
            farStagingRead = 0L
            farStagingWrite = 0L
            farHpfPrevX = 0f
            farHpfPrevY = 0f
            farResamplePos = 0f
            farResampleLastSample = 0f
            farResampleHasLast = false
        }
        farAlignedRing.fill(0f)
        farAlignedWrite = 0L
        farDecimRing.fill(0f)
        farDecimWrite = 0L
        farActiveHoldHops = 0

        nearInBuf.fill(0f)
        nearInCount = 0
        nearHpfPrevX = 0f
        nearHpfPrevY = 0f
        nearWorkRing.fill(0f)
        nearWorkWrite = 0L
        nearDecimRing.fill(0f)
        nearDecimWrite = 0L

        outFifo.fill(0f)
        outFifoRead = 0
        outFifoWrite = 0
        outFifoCount = 0
        // 预填 1 hop (16ms) 零样本，确保 processNearEnd 对任意调用长度都能立即等长吐出
        repeat(HOP_SIZE) { pushOutSample(0f) }

        estimatedDelaySamples = DEFAULT_DELAY_SAMPLES
        delayLocked = false
        hopCounter = 0L

        for (i in 0 until DELAY_BLOCKS) {
            farFftRingRe[i].fill(0f)
            farFftRingIm[i].fill(0f)
        }
        resetFilterWeights()
        prevLinearResidual.fill(0f)
        prevYHat.fill(0f)
        prevOlaTail.fill(0f)
        noisePsd.fill(INIT_NOISE_PSD)
        smoothedPsd.fill(INIT_NOISE_PSD)
        noiseBlockMin.fill(Float.MAX_VALUE)
        noiseSeeded = false
        noiseBlockHops = 0
        smoothGain.fill(1.0f)
    }

    /** 播报被打断并清空 AudioTrack 时调用，立刻停止将其视为活跃回声参考 */
    fun clearFarEnd() {
        synchronized(farLock) {
            farStagingRead = farStagingWrite
            farResamplePos = 0f
            farResampleHasLast = false
        }
        farAlignedRing.fill(0f)
        farDecimRing.fill(0f)
        farActiveHoldHops = 0
        prevYHat.fill(0f)
    }

    /** 喂入下行 TTS 解码出的 PCM16，自动重采样到 16kHz 并存入参考排队区 */
    fun feedFarEnd(samples: ShortArray, length: Int, sampleRate: Int) {
        if (length <= 0 || sampleRate <= 0) return
        synchronized(farLock) {
            if (sampleRate == SAMPLE_RATE) {
                for (i in 0 until length) {
                    pushFarStaging(samples[i] / 32768.0f)
                }
            } else {
                val step = sampleRate.toFloat() / SAMPLE_RATE.toFloat()
                if (!farResampleHasLast) {
                    farResampleLastSample = samples[0] / 32768.0f
                    farResampleHasLast = true
                    farResamplePos = 0f
                }
                while (farResamplePos < length - 1) {
                    val idx = farResamplePos.toInt()
                    val frac = farResamplePos - idx
                    val s0 = if (idx < 0) farResampleLastSample else samples[idx] / 32768.0f
                    val s1 = samples[idx + 1] / 32768.0f
                    pushFarStaging(s0 + (s1 - s0) * frac)
                    farResamplePos += step
                }
                // 把本包尾样保留，供跨包插值平滑衔接
                farResamplePos -= length
                farResampleLastSample = samples[length - 1] / 32768.0f
            }
        }
    }

    private fun pushFarStaging(raw: Float) {
        val y = HPF_ALPHA * (farHpfPrevY + raw - farHpfPrevX)
        farHpfPrevX = raw
        farHpfPrevY = y
        val pending = farStagingWrite - farStagingRead
        if (pending >= FAR_STAGING_CAP - 1) {
            farStagingRead = farStagingWrite - (FAR_STAGING_CAP / 2)
        }
        farStaging[(farStagingWrite and FAR_STAGING_MASK).toInt()] = y
        farStagingWrite++
    }

    /**
     * 处理一段 16kHz 近端 PCM16，返回等长度的去回声降噪 PCM16。
     */
    fun processNearEnd(samples: ShortArray, length: Int): ShortArray {
        if (length <= 0) return ShortArray(0)
        for (i in 0 until length) {
            val raw = samples[i] / 32768.0f
            val y = HPF_ALPHA * (nearHpfPrevY + raw - nearHpfPrevX)
            nearHpfPrevX = raw
            nearHpfPrevY = y
            nearInBuf[nearInCount++] = y
            if (nearInCount == HOP_SIZE) {
                nearInCount = 0
                processHop()
            }
        }
        val out = ShortArray(length)
        for (i in 0 until length) {
            val v = (popOutSample() * 32768.0f).toInt()
            out[i] = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }

    private fun processHop() {
        // 1. 拉取一个 256 样本对齐远端 hop（不足补 0，保持时域连续推进）
        var farEnergy = 0f
        synchronized(farLock) {
            for (i in 0 until HOP_SIZE) {
                val s = if (farStagingRead < farStagingWrite) {
                    val v = farStaging[(farStagingRead and FAR_STAGING_MASK).toInt()]
                    farStagingRead++
                    v
                } else {
                    0f
                }
                farAlignedRing[(farAlignedWrite and FAR_ALIGNED_MASK).toInt()] = s
                farAlignedWrite++
                farEnergy += s * s
            }
        }
        val farRms = Math.sqrt((farEnergy / HOP_SIZE).toDouble()).toFloat()
        if (farRms >= FAR_ACTIVE_RMS) {
            farActiveHoldHops = FAR_HOLD_HOPS
        } else if (farActiveHoldHops > 0) {
            farActiveHoldHops--
        }
        val farActive = farActiveHoldHops > 0

        // 2. 保存近端 hop 并更新 4kHz 降采样历史（4:1 抗混叠均值）
        var nearEnergy = 0f
        for (i in 0 until HOP_SIZE) {
            val s = nearInBuf[i]
            nearWorkRing[(nearWorkWrite and FAR_ALIGNED_MASK).toInt()] = s
            nearWorkWrite++
            nearEnergy += s * s
        }
        val nearRms = Math.sqrt((nearEnergy / HOP_SIZE).toDouble()).toFloat()

        for (d in 0 until DECIM_HOP) {
            val baseNear = nearWorkWrite - HOP_SIZE + d * 4L
            val baseFar = farAlignedWrite - HOP_SIZE + d * 4L
            var sumN = 0f
            var sumF = 0f
            for (j in 0 until 4) {
                sumN += nearWorkRing[((baseNear + j) and FAR_ALIGNED_MASK).toInt()]
                sumF += farAlignedRing[((baseFar + j) and FAR_ALIGNED_MASK).toInt()]
            }
            nearDecimRing[(nearDecimWrite and DECIM_NEAR_MASK).toInt()] = sumN * 0.25f
            nearDecimWrite++
            farDecimRing[(farDecimWrite and DECIM_FAR_MASK).toInt()] = sumF * 0.25f
            farDecimWrite++
        }

        // 3. 缓存本 hop 的 512 点远端 FFT（overlap-save）
        val slot = (hopCounter % DELAY_BLOCKS).toInt()
        val fRe = farFftRingRe[slot]
        val fIm = farFftRingIm[slot]
        val farStart = farAlignedWrite - FFT_SIZE
        for (i in 0 until FFT_SIZE) {
            fRe[i] = farAlignedRing[((farStart + i) and FAR_ALIGNED_MASK).toInt()]
            fIm[i] = 0f
        }
        fft.forward(fRe, fIm)

        // 4. 定期做粗延迟估计
        if (farActive && nearRms >= NEAR_ACTIVE_RMS && (hopCounter % DELAY_UPDATE_INTERVAL_HOPS == 0L)) {
            updateDelayEstimate()
        }

        // 5. PBFDAF 线性回声估计
        val baseBlock = ((estimatedDelaySamples - HOP_SIZE / 2).coerceAtLeast(0)) / HOP_SIZE
        val maxValidBlock = (DELAY_BLOCKS - PARTITIONS).coerceAtLeast(0)
        val clampedBase = baseBlock.coerceIn(0, maxValidBlock)

        // 计算对应估计延迟处的远端实际 RMS，用于准确判断双讲（避免停播瞬间误判）
        var delayedFarEnergy = 0f
        val dStart = farAlignedWrite - HOP_SIZE - estimatedDelaySamples.toLong()
        if (dStart >= 0L) {
            for (i in 0 until HOP_SIZE) {
                val s = farAlignedRing[((dStart + i) and FAR_ALIGNED_MASK).toInt()]
                delayedFarEnergy += s * s
            }
        }
        val delayedFarRms = Math.sqrt((delayedFarEnergy / HOP_SIZE).toDouble()).toFloat()

        yHatSpecRe.fill(0f)
        yHatSpecIm.fill(0f)
        farPowSum.fill(0f)

        for (p in 0 until PARTITIONS) {
            val blockAge = clampedBase + p
            if (blockAge.toLong() > hopCounter) continue
            val pSlot = (((hopCounter - blockAge) % DELAY_BLOCKS + DELAY_BLOCKS) % DELAY_BLOCKS).toInt()
            val xRe = farFftRingRe[pSlot]
            val xIm = farFftRingIm[pSlot]
            val wpRe = wRe[p]
            val wpIm = wIm[p]
            for (k in 0 until FFT_SIZE) {
                val xr = xRe[k]
                val xi = xIm[k]
                val wr = wpRe[k]
                val wi = wpIm[k]
                yHatSpecRe[k] += wr * xr - wi * xi
                yHatSpecIm[k] += wr * xi + wi * xr
                farPowSum[k] += xr * xr + xi * xi
            }
        }

        System.arraycopy(yHatSpecRe, 0, fftRe, 0, FFT_SIZE)
        System.arraycopy(yHatSpecIm, 0, fftIm, 0, FFT_SIZE)
        fft.inverse(fftRe, fftIm)

        var errEnergy = 0f
        var yHatEnergy = 0f
        for (i in 0 until HOP_SIZE) {
            val yh = fftRe[HOP_SIZE + i]
            val er = nearInBuf[i] - yh
            yHatTime[i] = yh
            eTime[i] = er
            yHatEnergy += yh * yh
            errEnergy += er * er
        }
        val errRms = Math.sqrt((errEnergy / HOP_SIZE).toDouble()).toFloat()
        val yHatRms = Math.sqrt((yHatEnergy / HOP_SIZE).toDouble()).toFloat()

        // 发散回滚保护：只取线性残差不超过原近端能量过多时才输出线性残差
        val useFiltered = farActive && (errEnergy <= nearEnergy * 1.25f || nearEnergy < 1e-6f)
        if (!useFiltered && farActive && errEnergy > nearEnergy * 1.8f) {
            // 权重可能由于突发双讲或延迟剧变偏离，回退输出原始近端并温和衰减权重
            shrinkWeights(0.75f)
            System.arraycopy(nearInBuf, 0, eTime, 0, HOP_SIZE)
            yHatTime.fill(0f)
        }

        // 双讲判断：近端明显高于回声估计与当前延迟对应的远端参考能量
        val doubleTalk = farActive && (
            (nearRms > yHatRms * 1.85f && errRms > nearRms * 0.55f && errRms > 0.025f) ||
                (nearRms > maxOf(delayedFarRms, yHatRms) * 1.8f && delayedFarRms > 0.005f)
            )

        // 6. 约束频域梯度更新 PBFDAF 权重
        if (farActive && farEnergy > 1e-6f) {
            for (i in 0 until HOP_SIZE) {
                errSpecRe[i] = 0f
                errSpecIm[i] = 0f
                errSpecRe[HOP_SIZE + i] = eTime[i]
                errSpecIm[HOP_SIZE + i] = 0f
            }
            fft.forward(errSpecRe, errSpecIm)

            val mu = if (doubleTalk) MU_DOUBLE_TALK else MU_SINGLE_TALK
            for (p in 0 until PARTITIONS) {
                val blockAge = clampedBase + p
                if (blockAge.toLong() > hopCounter) continue
                val pSlot = (((hopCounter - blockAge) % DELAY_BLOCKS + DELAY_BLOCKS) % DELAY_BLOCKS).toInt()
                val xRe = farFftRingRe[pSlot]
                val xIm = farFftRingIm[pSlot]
                for (k in 0 until FFT_SIZE) {
                    val norm = farPowSum[k] + NLMS_REG
                    val inv = mu / norm
                    val er = errSpecRe[k]
                    val ei = errSpecIm[k]
                    val xr = xRe[k]
                    val xi = xIm[k]
                    // E * conj(X) / norm
                    gradRe[k] = (er * xr + ei * xi) * inv
                    gradIm[k] = (ei * xr - er * xi) * inv
                }
                fft.inverse(gradRe, gradIm)
                for (i in HOP_SIZE until FFT_SIZE) {
                    gradRe[i] = 0f
                    gradIm[i] = 0f
                }
                for (i in 0 until HOP_SIZE) {
                    gradIm[i] = 0f
                }
                fft.forward(gradRe, gradIm)
                val wpRe = wRe[p]
                val wpIm = wIm[p]
                for (k in 0 until FFT_SIZE) {
                    wpRe[k] = wpRe[k] * WEIGHT_LEAK + gradRe[k]
                    wpIm[k] = wpIm[k] * WEIGHT_LEAK + gradIm[k]
                }
            }
        }

        // 7. 后端 512 点 50% 重叠 sqrt(Hann) 残余回声抑制 + 平稳降噪
        applySpectralResAndNs(farActive, doubleTalk, clampedBase)

        hopCounter++
    }

    private fun updateDelayEstimate() {
        val windowLen = 128 // 32ms @ 4kHz
        if (nearDecimWrite < windowLen || farDecimWrite < windowLen) return
        val maxLag = minOf(MAX_DELAY_4K, (farDecimWrite - windowLen).toInt())
        if (maxLag < 0) return

        var nearMeanSq = 0f
        val nStart = nearDecimWrite - windowLen
        for (i in 0 until windowLen) {
            val v = nearDecimRing[((nStart + i) and DECIM_NEAR_MASK).toInt()]
            nearMeanSq += v * v
        }
        if (nearMeanSq < 1e-4f) return

        var bestLag4k = -1
        var bestNcc = 0f
        var coarseBestLag = 0
        var coarseBestNcc = 0f

        var lag = 0
        while (lag <= maxLag) {
            var dot = 0f
            var farSq = 0f
            val fStart = farDecimWrite - windowLen - lag
            for (i in 0 until windowLen) {
                val nVal = nearDecimRing[((nStart + i) and DECIM_NEAR_MASK).toInt()]
                val fVal = farDecimRing[((fStart + i) and DECIM_FAR_MASK).toInt()]
                dot += nVal * fVal
                farSq += fVal * fVal
            }
            if (farSq > 1e-4f) {
                val ncc = dot / Math.sqrt((nearMeanSq * farSq).toDouble()).toFloat()
                if (ncc > coarseBestNcc) {
                    coarseBestNcc = ncc
                    coarseBestLag = lag
                }
            }
            lag += 2
        }

        val refineMin = (coarseBestLag - 3).coerceAtLeast(0)
        val refineMax = (coarseBestLag + 3).coerceAtMost(maxLag)
        for (rLag in refineMin..refineMax) {
            var dot = 0f
            var farSq = 0f
            val fStart = farDecimWrite - windowLen - rLag
            for (i in 0 until windowLen) {
                val nVal = nearDecimRing[((nStart + i) and DECIM_NEAR_MASK).toInt()]
                val fVal = farDecimRing[((fStart + i) and DECIM_FAR_MASK).toInt()]
                dot += nVal * fVal
                farSq += fVal * fVal
            }
            if (farSq > 1e-4f) {
                val ncc = dot / Math.sqrt((nearMeanSq * farSq).toDouble()).toFloat()
                if (ncc > bestNcc) {
                    bestNcc = ncc
                    bestLag4k = rLag
                }
            }
        }

        val minScore = if (delayLocked) 0.45f else 0.30f
        if (bestLag4k >= 0 && bestNcc >= minScore) {
            val candidate16k = bestLag4k * 4
            if (!delayLocked || Math.abs(candidate16k - estimatedDelaySamples) > 64) {
                if (delayLocked) {
                    resetFilterWeights()
                }
                estimatedDelaySamples = candidate16k
                delayLocked = true
            }
        }
    }

    private fun applySpectralResAndNs(farActive: Boolean, doubleTalk: Boolean, clampedBase: Int) {
        // 拼接 [prevLinearResidual(256), eTime(256)] 并乘 sqrt(Hann)
        for (i in 0 until HOP_SIZE) {
            fftRe[i] = prevLinearResidual[i] * SQRT_HANN[i]
            fftIm[i] = 0f
            fftRe[HOP_SIZE + i] = eTime[i] * SQRT_HANN[HOP_SIZE + i]
            fftIm[HOP_SIZE + i] = 0f
        }
        fft.forward(fftRe, fftIm)

        // 对线性回声估计 yHat 也做同样窗口 FFT，便于精准估计每频点残余耦合
        if (farActive) {
            for (i in 0 until HOP_SIZE) {
                yHatResSpecRe[i] = prevYHat[i] * SQRT_HANN[i]
                yHatResSpecIm[i] = 0f
                yHatResSpecRe[HOP_SIZE + i] = yHatTime[i] * SQRT_HANN[HOP_SIZE + i]
                yHatResSpecIm[HOP_SIZE + i] = 0f
            }
            fft.forward(yHatResSpecRe, yHatResSpecIm)

            // 取主对齐块与相邻块远端能量最大值做非线性漏声辅助参考
            val b0 = clampedBase.coerceIn(0, DELAY_BLOCKS - 1)
            val b1 = (clampedBase + 1).coerceIn(0, DELAY_BLOCKS - 1)
            val s0 = (((hopCounter - b0) % DELAY_BLOCKS + DELAY_BLOCKS) % DELAY_BLOCKS).toInt()
            val s1 = (((hopCounter - b1) % DELAY_BLOCKS + DELAY_BLOCKS) % DELAY_BLOCKS).toInt()
            val r0Re = farFftRingRe[s0]
            val r0Im = farFftRingIm[s0]
            val r1Re = farFftRingRe[s1]
            val r1Im = farFftRingIm[s1]
            for (k in 0 until HALF_BINS) {
                val p0 = (r0Re[k] * r0Re[k] + r0Im[k] * r0Im[k]) * 0.5f
                val p1 = (r1Re[k] * r1Re[k] + r1Im[k] * r1Im[k]) * 0.5f
                farDelayedPsd[k] = if (p0 > p1) p0 else p1
            }
        } else {
            farDelayedPsd.fill(0f)
        }

        val gainFloor = if (farActive) RES_GAIN_FLOOR else NS_GAIN_FLOOR
        val overSub = if (farActive) RES_OVERSUB else NS_OVERSUB
        val updateNoiseBlock = !farActive && (noiseBlockHops + 1 >= NOISE_BLOCK_HOPS)

        for (k in 0 until HALF_BINS) {
            val er = fftRe[k]
            val ei = fftIm[k]
            val ePsd = er * er + ei * ei

            if (!farActive) {
                if (hopCounter == 0L) {
                    smoothedPsd[k] = ePsd * 2.0f
                    noisePsd[k] = smoothedPsd[k]
                } else {
                    smoothedPsd[k] = 0.75f * smoothedPsd[k] + 0.25f * ePsd
                    val sPsd = smoothedPsd[k]
                    if (!noiseSeeded) {
                        noisePsd[k] = sPsd
                    } else {
                        if (sPsd < noiseBlockMin[k]) noiseBlockMin[k] = sPsd
                        val nPrev = noisePsd[k]
                        noisePsd[k] = when {
                            sPsd < nPrev -> 0.80f * nPrev + 0.20f * sPsd
                            sPsd < nPrev * 2.2f -> 0.90f * nPrev + 0.10f * sPsd
                            else -> nPrev
                        }
                        if (updateNoiseBlock && noiseBlockMin[k] < Float.MAX_VALUE) {
                            val compensatedMin = noiseBlockMin[k] * 1.5f
                            noisePsd[k] = 0.45f * noisePsd[k] + 0.55f * compensatedMin
                            noiseBlockMin[k] = Float.MAX_VALUE
                        }
                    }
                }
            }

            var resEchoPsd = 0f
            if (farActive) {
                val yr = yHatResSpecRe[k]
                val yi = yHatResSpecIm[k]
                val yPsd = yr * yr + yi * yi
                val refPsd = yPsd + 0.15f * farDelayedPsd[k]
                if (!doubleTalk && refPsd > 1e-4f) {
                    val instantFactor = (ePsd / (refPsd + 1e-6f)).coerceIn(0.05f, 3.0f)
                    echoLeakFactor[k] = 0.90f * echoLeakFactor[k] + 0.10f * instantFactor
                }
                resEchoPsd = echoLeakFactor[k] * refPsd
            }

            val unwanted = noisePsd[k] + resEchoPsd
            val g = if (ePsd > 1e-10f) {
                ((ePsd - overSub * unwanted) / (ePsd + 1e-10f)).coerceIn(gainFloor, 1.0f)
            } else {
                gainFloor
            }
            rawGain[k] = g
        }
        if (!farActive) {
            if (hopCounter >= 1L) noiseSeeded = true
            noiseBlockHops = if (updateNoiseBlock) 0 else noiseBlockHops + 1
        }

        // 频域 3 点平滑 + 时域平滑，消除音乐噪声
        for (k in 0 until HALF_BINS) {
            val left = rawGain[if (k > 0) k - 1 else 0]
            val center = rawGain[k]
            val right = rawGain[if (k + 1 < HALF_BINS) k + 1 else HALF_BINS - 1]
            val freqSmoothed = 0.2f * left + 0.6f * center + 0.2f * right
            smoothGain[k] = 0.55f * smoothGain[k] + 0.45f * freqSmoothed
        }

        // 应用增益到复数谱（保持共轭对称）
        fftRe[0] *= smoothGain[0]
        fftIm[0] *= smoothGain[0]
        fftRe[HOP_SIZE] *= smoothGain[HOP_SIZE]
        fftIm[HOP_SIZE] *= smoothGain[HOP_SIZE]
        for (k in 1 until HOP_SIZE) {
            val g = smoothGain[k]
            fftRe[k] *= g
            fftIm[k] *= g
            fftRe[FFT_SIZE - k] *= g
            fftIm[FFT_SIZE - k] *= g
        }

        fft.inverse(fftRe, fftIm)

        for (i in 0 until HOP_SIZE) {
            val firstHalf = fftRe[i] * SQRT_HANN[i]
            val outSample = prevOlaTail[i] + firstHalf
            pushOutSample(outSample)
            prevOlaTail[i] = fftRe[HOP_SIZE + i] * SQRT_HANN[HOP_SIZE + i]
            prevLinearResidual[i] = eTime[i]
            prevYHat[i] = yHatTime[i]
        }
    }

    private fun resetFilterWeights() {
        for (p in 0 until PARTITIONS) {
            wRe[p].fill(0f)
            wIm[p].fill(0f)
        }
        echoLeakFactor.fill(0.45f)
    }

    private fun shrinkWeights(factor: Float) {
        for (p in 0 until PARTITIONS) {
            val r = wRe[p]
            val im = wIm[p]
            for (k in 0 until FFT_SIZE) {
                r[k] *= factor
                im[k] *= factor
            }
        }
    }

    private fun pushOutSample(sample: Float) {
        if (outFifoCount >= OUT_FIFO_CAP) {
            outFifoRead = (outFifoRead + 1) % OUT_FIFO_CAP
            outFifoCount--
        }
        outFifo[outFifoWrite] = sample
        outFifoWrite = (outFifoWrite + 1) % OUT_FIFO_CAP
        outFifoCount++
    }

    private fun popOutSample(): Float {
        if (outFifoCount == 0) return 0f
        val v = outFifo[outFifoRead]
        outFifoRead = (outFifoRead + 1) % OUT_FIFO_CAP
        outFifoCount--
        return v
    }

    /** 原地 512 点基-2 Cooley-Tukey FFT，预计算旋转因子与位反序表 */
    internal class Fft512 {
        private val bitRev = IntArray(FFT_SIZE)
        private val cosTable = FloatArray(FFT_SIZE / 2)
        private val sinTable = FloatArray(FFT_SIZE / 2)

        init {
            val bits = 9 // log2(512)
            for (i in 0 until FFT_SIZE) {
                var x = i
                var rev = 0
                for (b in 0 until bits) {
                    rev = (rev shl 1) or (x and 1)
                    x = x shr 1
                }
                bitRev[i] = rev
            }
            for (k in 0 until FFT_SIZE / 2) {
                val angle = -2.0 * Math.PI * k / FFT_SIZE
                cosTable[k] = Math.cos(angle).toFloat()
                sinTable[k] = Math.sin(angle).toFloat()
            }
        }

        fun forward(re: FloatArray, im: FloatArray) {
            transform(re, im, inverse = false)
        }

        fun inverse(re: FloatArray, im: FloatArray) {
            transform(re, im, inverse = true)
            val invN = 1.0f / FFT_SIZE
            for (i in 0 until FFT_SIZE) {
                re[i] *= invN
                im[i] *= invN
            }
        }

        private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
            for (i in 0 until FFT_SIZE) {
                val j = bitRev[i]
                if (i < j) {
                    val tr = re[i]
                    re[i] = re[j]
                    re[j] = tr
                    val ti = im[i]
                    im[i] = im[j]
                    im[j] = ti
                }
            }
            val sign = if (inverse) -1f else 1f
            var len = 2
            while (len <= FFT_SIZE) {
                val half = len shr 1
                val step = FFT_SIZE / len
                var i = 0
                while (i < FFT_SIZE) {
                    var k = 0
                    var twIdx = 0
                    while (k < half) {
                        val wr = cosTable[twIdx]
                        val wi = sinTable[twIdx] * sign
                        val uRe = re[i + k]
                        val uIm = im[i + k]
                        val vRe = re[i + k + half]
                        val vIm = im[i + k + half]
                        val tRe = wr * vRe - wi * vIm
                        val tIm = wr * vIm + wi * vRe
                        re[i + k] = uRe + tRe
                        im[i + k] = uIm + tIm
                        re[i + k + half] = uRe - tRe
                        im[i + k + half] = uIm - tIm
                        k++
                        twIdx += step
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = XiaozhiProtocol.UPLOAD_SAMPLE_RATE // 16000
        private const val HOP_SIZE = 256 // 16ms
        private const val FFT_SIZE = 512
        private const val HALF_BINS = FFT_SIZE / 2 + 1 // 257

        private const val PARTITIONS = 4
        private const val DELAY_BLOCKS = 40 // 40 * 256 = 10240 samples = 640ms

        private const val FAR_STAGING_CAP = 32768
        private const val FAR_STAGING_MASK = (FAR_STAGING_CAP - 1).toLong()

        private const val FAR_ALIGNED_SIZE = 16384 // 1024ms @ 16kHz
        private const val FAR_ALIGNED_MASK = (FAR_ALIGNED_SIZE - 1).toLong()

        private const val DECIM_HOP = HOP_SIZE / 4 // 64
        private const val DECIM_FAR_SIZE = 4096 // 1024ms @ 4kHz
        private const val DECIM_FAR_MASK = (DECIM_FAR_SIZE - 1).toLong()
        private const val DECIM_NEAR_SIZE = 256
        private const val DECIM_NEAR_MASK = (DECIM_NEAR_SIZE - 1).toLong()
        private const val MAX_DELAY_4K = 1920 // 480ms @ 4kHz
        private const val DEFAULT_DELAY_SAMPLES = 960 // 60ms 初始默认估计
        private const val DELAY_UPDATE_INTERVAL_HOPS = 4L

        private const val OUT_FIFO_CAP = 8192

        private const val HPF_ALPHA = 0.96f
        private const val FAR_ACTIVE_RMS = 0.004f
        private const val NEAR_ACTIVE_RMS = 0.006f
        private const val FAR_HOLD_HOPS = 25 // 400ms
        private const val NOISE_BLOCK_HOPS = 32 // ~512ms

        private const val MU_SINGLE_TALK = 0.35f
        private const val MU_DOUBLE_TALK = 0.015f
        private const val NLMS_REG = 1.0e-3f
        private const val WEIGHT_LEAK = 0.9995f

        private const val INIT_NOISE_PSD = 1.0e-4f
        private const val RES_GAIN_FLOOR = 0.06f
        private const val NS_GAIN_FLOOR = 0.20f
        private const val RES_OVERSUB = 1.8f
        private const val NS_OVERSUB = 1.6f

        private val SQRT_HANN = FloatArray(FFT_SIZE) { i ->
            Math.sqrt(0.5 * (1.0 - Math.cos(2.0 * Math.PI * (i + 0.5) / FFT_SIZE))).toFloat()
        }
    }
}
