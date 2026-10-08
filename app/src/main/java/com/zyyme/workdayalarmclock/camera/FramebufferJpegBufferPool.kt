package com.zyyme.workdayalarmclock.camera

import java.util.ArrayDeque

internal object FramebufferJpegBufferPool {
    private val buffers = ArrayDeque<ByteArray>()
    private var retainedBytes = 0

    @Synchronized
    fun acquire(size: Int): ByteArray {
        var best: ByteArray? = null
        val iterator = buffers.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.size >= size && (best == null || candidate.size < best.size)) {
                best = candidate
            }
        }
        if (best != null) {
            buffers.remove(best)
            retainedBytes -= best.size
            return best
        }
        return ByteArray(size)
    }

    @Synchronized
    fun release(buffer: ByteArray) {
        if (buffer.size > MAX_RETAINED_BYTES ||
            buffers.size >= MAX_BUFFER_COUNT ||
            retainedBytes + buffer.size > MAX_RETAINED_BYTES
        ) {
            return
        }
        buffers.addLast(buffer)
        retainedBytes += buffer.size
    }

    private const val MAX_BUFFER_COUNT = 2
    private const val MAX_RETAINED_BYTES = 16 * 1024 * 1024
}
