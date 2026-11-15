package com.zyyme.workdayalarmclock.xiaozhi

/**
 * 把任意长度的 PCM16 采样切成定长帧（默认 60ms @16kHz = 960 样本）。
 * 不足一帧的余量留到下次 append，不补零。
 */
class PcmFrameChunker(private val frameSamples: Int = XiaozhiProtocol.UPLOAD_FRAME_SAMPLES) {

    init {
        require(frameSamples > 0) { "frameSamples must be positive" }
    }

    private var pending = ShortArray(frameSamples * 2)
    private var pendingCount = 0

    val bufferedSamples: Int get() = pendingCount

    /** 追加一段采样，返回本次凑满的完整帧（可能 0 个或多个）。 */
    fun append(samples: ShortArray, offset: Int = 0, length: Int = samples.size): List<ShortArray> {
        if (length <= 0) return emptyList()
        val frames = ArrayList<ShortArray>(2)
        var read = offset
        var remaining = length
        while (remaining > 0) {
            val space = frameSamples - pendingCount
            val take = if (remaining < space) remaining else space
            System.arraycopy(samples, read, pending, pendingCount, take)
            pendingCount += take
            read += take
            remaining -= take
            if (pendingCount == frameSamples) {
                frames.add(pending.copyOf(frameSamples))
                pendingCount = 0
            }
        }
        return frames
    }

    fun reset() {
        pendingCount = 0
    }
}
