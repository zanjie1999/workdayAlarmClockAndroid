package com.zyyme.workdayalarmclock.camera

import java.util.ArrayDeque

internal enum class CameraStreamFormat {
    MJPEG,
    AVC
}

internal data class CameraStreamKey(
    val format: CameraStreamFormat,
    val cameraIndex: Int,
    val resolutionIndex: Int? = null,
    val bitrateBps: Int? = null
)

internal data class CameraStreamPacket(
    val sequence: Long,
    val data: ByteArray,
    val keyFrame: Boolean = true,
    val presentationTimeUs: Long = 0L
)

/** 分辨率档位的唯一来源：MJPEG、AVC、caminfo 三处都走这里，保证档位号含义一致。 */
internal object CameraSizes {
    private val mjpegCache = java.util.concurrent.ConcurrentHashMap<Int, List<Pair<Int, Int>>>()

    /** 面积降序 → 宽降序 → 高降序。保留重复项，不要在这里去重，否则旧档位号会整体漂移。 */
    val ORDER: Comparator<Pair<Int, Int>> =
        compareByDescending<Pair<Int, Int>> { it.first.toLong() * it.second }
            .thenByDescending { it.first }
            .thenByDescending { it.second }

    fun sort(sizes: List<Pair<Int, Int>>): List<Pair<Int, Int>> = sizes.sortedWith(ORDER)

    fun cacheMjpeg(cameraIndex: Int, sizes: List<Pair<Int, Int>>) {
        if (sizes.isNotEmpty()) mjpegCache[cameraIndex] = sort(sizes)
    }

    fun cachedMjpeg(cameraIndex: Int): List<Pair<Int, Int>>? = mjpegCache[cameraIndex]
}

internal data class AvcStreamConfig(
    val sps: ByteArray,
    val pps: ByteArray,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val codecString: String
)

internal interface CameraStreamPipeline {
    val key: CameraStreamKey
    val description: String

    fun start(): Boolean
    fun stop()
    fun awaitPacket(afterSequence: Long): CameraStreamPacket?
    fun awaitAvcConfig(timeoutMillis: Long): AvcStreamConfig? = null
    fun requestKeyFrame() = Unit
}

internal class CameraFrameHub(
    private val maxPackets: Int,
    private val keepLatestOnly: Boolean
) {
    private val monitor = Object()
    private val packets = ArrayDeque<CameraStreamPacket>()
    private var sequence = 0L
    private var closed = false

    fun publish(
        data: ByteArray,
        keyFrame: Boolean = true,
        presentationTimeUs: Long = 0L
    ) {
        synchronized(monitor) {
            if (closed) return
            sequence++
            if (keepLatestOnly) packets.clear()
            packets.addLast(
                CameraStreamPacket(sequence, data, keyFrame, presentationTimeUs)
            )
            while (packets.size > maxPackets) packets.removeFirst()
            monitor.notifyAll()
        }
    }

    fun awaitNext(afterSequence: Long): CameraStreamPacket? {
        synchronized(monitor) {
            while (!closed) {
                val iterator = packets.iterator()
                while (iterator.hasNext()) {
                    val packet = iterator.next()
                    if (packet.sequence > afterSequence) return packet
                }
                try {
                    monitor.wait(3_000L)
                } catch (_: InterruptedException) {
                    return null
                }
            }
            return null
        }
    }

    fun close() {
        synchronized(monitor) {
            closed = true
            packets.clear()
            monitor.notifyAll()
        }
    }
}
