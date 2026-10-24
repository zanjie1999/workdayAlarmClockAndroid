package com.zyyme.workdayalarmclock.camera

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

internal enum class FramebufferVideoCodec(val mime: String) {
    AVC(MediaFormat.MIMETYPE_VIDEO_AVC),
    HEVC(MediaFormat.MIMETYPE_VIDEO_HEVC)
}

/**
 * 把 Annex-B 的 H.264/H.265 连续码流喂给系统解码器，解码结果直接渲染到 [surface]。
 * 至少需要 api16，但是没关系，android5才有这两个路由
 * 264 和 265 的差异只有三处：MIME、参数集 NAL 类型、csd 的组织方式，其它逻辑完全共用。
 */
internal class FramebufferVideoDecoder(
    private val kind: FramebufferVideoCodec,
    private val surface: Surface,
    private val log: (String) -> Unit,
    private val onVideoSize: (Int, Int) -> Unit,
    private val onFailure: (String) -> Unit
) {
    private companion object {
        const val MAX_BUFFER_BYTES = 8 * 1024 * 1024
        const val PARAMETER_SET_TIMEOUT_MS = 5_000L
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }

    private val lock = Object()
    @Volatile private var running = true
    private var decoder: MediaCodec? = null
    private var configured = false
    @Volatile private var streamWidth = 0
    @Volatile private var streamHeight = 0

    private var buffer = ByteArray(512 * 1024)
    private var readPos = 0
    private var writePos = 0

    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null

    private val accessUnit = ByteArrayOutputStream()
    private var accessUnitHasVcl = false
    private var accessUnitKeyFrame = false
    private val startedAtMs = SystemClock.elapsedRealtime()

    private val inputThread = Thread({ inputLoop() }, "fb-video-input")
    private val outputThread = Thread({ outputLoop() }, "fb-video-output")

    init {
        inputThread.start()
        outputThread.start()
    }

    fun feed(data: ByteArray, length: Int) {
        var overflow = false
        synchronized(lock) {
            if (!running || length <= 0) return
            if (writePos + length > buffer.size) {
                if (readPos > 0) {
                    System.arraycopy(buffer, readPos, buffer, 0, writePos - readPos)
                    writePos -= readPos
                    readPos = 0
                }
                if (writePos + length > buffer.size) {
                    if (writePos + length > MAX_BUFFER_BYTES) {
                        overflow = true
                    } else {
                        buffer = buffer.copyOf(maxOf(buffer.size * 2, writePos + length))
                    }
                }
            }
            if (!overflow) {
                System.arraycopy(data, 0, buffer, writePos, length)
                writePos += length
            }
        }
        if (overflow) fail("${codecName()} 解码缓冲区溢出")
    }

    fun stop() {
        synchronized(lock) {
            running = false
        }
        join(inputThread)
        join(outputThread)
        val codec = synchronized(lock) {
            val current = decoder
            decoder = null
            current
        }
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try {
            thread.join(500L)
        } catch (_: InterruptedException) {
        }
    }

    private fun inputLoop() {
        try {
            while (running) {
                if (!configured &&
                    SystemClock.elapsedRealtime() - startedAtMs > PARAMETER_SET_TIMEOUT_MS
                ) {
                    fail("没有收到 ${codecName()} 的 SPS/PPS，确认推的是 Annex-B 裸流")
                    return
                }
                val nal = nextNal()
                if (nal == null) {
                    Thread.sleep(5L)
                    continue
                }
                handleNal(nal)
            }
        } catch (e: Exception) {
            fail("${codecName()} 码流解析失败：${e.message}")
        }
    }

    private fun outputLoop() {
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val codec = synchronized(lock) { decoder }
                if (codec == null) {
                    Thread.sleep(20L)
                    continue
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000L)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        // getInteger(key, default) is API 29+, use containsKey on 21-28.
                        val width = if (format.containsKey(MediaFormat.KEY_WIDTH)) {
                            format.getInteger(MediaFormat.KEY_WIDTH)
                        } else {
                            0
                        }
                        val height = if (format.containsKey(MediaFormat.KEY_HEIGHT)) {
                            format.getInteger(MediaFormat.KEY_HEIGHT)
                        } else {
                            0
                        }
                        if (width > 0 && height > 0) onVideoSize(width, height)
                    }
                    else -> if (index >= 0) {
                        // 直接交给 Surface，不拷贝到 Bitmap。
                        codec.releaseOutputBuffer(index, true)
                    }
                }
            }
        } catch (e: Exception) {
            fail("${codecName()} 解码失败（${streamWidth}x${streamHeight}）：${describe(e)}")
        }
    }

    private fun nextNal(): ByteArray? {
        synchronized(lock) {
            while (running) {
                val first = findStartCode(readPos, writePos)
                if (first < 0) {
                    // 保留最后 3 个字节，起始码可能被拆到两次网络读取里。
                    if (writePos - readPos > 3) readPos = writePos - 3
                    return null
                }
                val payloadStart = first + startCodeLength(first)
                val second = findStartCode(payloadStart, writePos)
                if (second < 0) {
                    readPos = first
                    return null
                }
                readPos = second
                if (second > payloadStart) return buffer.copyOfRange(payloadStart, second)
            }
            return null
        }
    }

    private fun findStartCode(from: Int, to: Int): Int {
        var index = from
        while (index + 2 < to) {
            if (buffer[index] == 0.toByte() && buffer[index + 1] == 0.toByte()) {
                if (buffer[index + 2] == 1.toByte()) return index
                if (buffer[index + 2] == 0.toByte() &&
                    index + 3 < to && buffer[index + 3] == 1.toByte()
                ) {
                    return index
                }
            }
            index++
        }
        return -1
    }

    private fun startCodeLength(index: Int): Int =
        if (index + 2 < writePos && buffer[index + 2] == 1.toByte()) 3 else 4

    private fun handleNal(nal: ByteArray) {
        val type = nalType(nal)
        if (type < 0) return

        when (kind) {
            FramebufferVideoCodec.AVC -> when (type) {
                7 -> sps = withStartCode(nal)
                8 -> pps = withStartCode(nal)
            }
            FramebufferVideoCodec.HEVC -> when (type) {
                32 -> vps = withStartCode(nal)
                33 -> sps = withStartCode(nal)
                34 -> pps = withStartCode(nal)
            }
        }

        if (!configured) {
            if (hasAllParameterSets()) configureDecoder()
            if (!configured) return
        }

        val vcl = isVcl(type)
        if (isAud(type) && accessUnit.size() > 0) emitAccessUnit()
        if (vcl && isFirstSlice(nal) && accessUnitHasVcl) emitAccessUnit()

        appendToAccessUnit(nal)
        if (vcl) accessUnitHasVcl = true
        if (isIdr(type)) accessUnitKeyFrame = true
    }

    private fun hasAllParameterSets(): Boolean {
        return when (kind) {
            FramebufferVideoCodec.AVC -> sps != null && pps != null
            FramebufferVideoCodec.HEVC -> vps != null && sps != null && pps != null
        }
    }

    private fun configureDecoder() {
        val s = sps ?: return
        val p = pps ?: return
        val csd0: ByteArray
        val csd1: ByteArray?
        if (kind == FramebufferVideoCodec.AVC) {
            csd0 = s
            csd1 = p
        } else {
            val v = vps ?: return
            csd0 = v + s + p
            csd1 = null
        }

        val dimensions = when (kind) {
            FramebufferVideoCodec.AVC -> SpsDimensions.parseAvc(s)
            FramebufferVideoCodec.HEVC -> SpsDimensions.parseHevc(s)
        }
        if (dimensions == null) {
            fail("${codecName()} 无法从 SPS 解析分辨率")
            return
        }
        val width = dimensions.first
        val height = dimensions.second
        streamWidth = width
        streamHeight = height
        log("${codecName()} SPS 分辨率：${width}x${height}")

        val format = buildFormat(csd0, csd1, width, height)
        var codec: MediaCodec? = null
        try {
            val selectedName = try {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
            } catch (_: Exception) {
                null
            }
            val candidate = if (selectedName != null) {
                MediaCodec.createByCodecName(selectedName)
            } else {
                MediaCodec.createDecoderByType(kind.mime)
            }
            codec = candidate
            candidate.configure(format, surface, null, 0)
            log("${codecName()} 使用解码器 ${candidate.name}")
        } catch (e: Exception) {
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            fail("${codecName()} 解码器初始化失败：${describe(e)}")
            return
        }

        val ready = codec ?: return
        try {
            ready.start()
        } catch (e: Exception) {
            try {
                ready.release()
            } catch (_: Exception) {
            }
            fail("${codecName()} 解码器启动失败：${describe(e)}")
            return
        }

        synchronized(lock) {
            decoder = ready
            configured = true
        }
        log("${codecName()} 解码器已启动")
    }

    private fun buildFormat(
        csd0: ByteArray,
        csd1: ByteArray?,
        width: Int,
        height: Int
    ): MediaFormat {
        val format = MediaFormat()
        format.setString(MediaFormat.KEY_MIME, kind.mime)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
        format.setInteger(MediaFormat.KEY_WIDTH, width)
        format.setInteger(MediaFormat.KEY_HEIGHT, height)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(csd0))
        if (csd1 != null) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
        return format
    }

    private fun appendToAccessUnit(nal: ByteArray) {
        accessUnit.write(START_CODE)
        accessUnit.write(nal)
    }

    private fun emitAccessUnit() {
        if (!accessUnitHasVcl || accessUnit.size() == 0) return
        val data = accessUnit.toByteArray()
        val keyFrame = accessUnitKeyFrame
        accessUnit.reset()
        accessUnitHasVcl = false
        accessUnitKeyFrame = false
        if (!configured) return
        queueAccessUnit(data, keyFrame)
    }

    private fun queueAccessUnit(data: ByteArray, keyFrame: Boolean) {
        val codec = synchronized(lock) { decoder } ?: return
        val deadline = SystemClock.elapsedRealtime() + 200L
        var index = -1
        while (running) {
            index = try {
                codec.dequeueInputBuffer(10_000L)
            } catch (e: Exception) {
                fail("${codecName()} 输入缓冲区获取失败：${e.message}")
                return
            }
            if (index >= 0) break
            if (SystemClock.elapsedRealtime() > deadline) return
        }
        if (index < 0) return

        val input = try {
            codec.getInputBuffer(index)
        } catch (e: Exception) {
            null
        }
        if (input == null) {
            try {
                codec.queueInputBuffer(index, 0, 0, 0L, 0)
            } catch (_: Exception) {
            }
            return
        }
        input.clear()
        if (input.capacity() < data.size) {
            try {
                codec.queueInputBuffer(index, 0, 0, 0L, 0)
            } catch (_: Exception) {
            }
            fail("${codecName()} 输入缓冲区过小")
            return
        }
        input.put(data)
        // 用系统单调时钟做 PTS，避免从 0 开始的时间戳被 SurfaceFlinger 当成过期帧。
        val presentationTimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L
        try {
            codec.queueInputBuffer(
                index,
                0,
                data.size,
                presentationTimeUs,
                if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            )
        } catch (e: Exception) {
            fail("${codecName()} 输入数据提交失败：${e.message}")
        }
    }

    private fun nalType(nal: ByteArray): Int {
        if (nal.isEmpty()) return -1
        return if (kind == FramebufferVideoCodec.AVC) {
            nal[0].toInt() and 0x1f
        } else {
            if (nal.size < 2) return -1
            (nal[0].toInt() and 0x7e) shr 1
        }
    }

    private fun isVcl(type: Int): Boolean {
        return if (kind == FramebufferVideoCodec.AVC) {
            type in 1..5
        } else {
            type in 0..31
        }
    }

    private fun isIdr(type: Int): Boolean {
        return if (kind == FramebufferVideoCodec.AVC) {
            type == 5
        } else {
            type in 19..21
        }
    }

    private fun isAud(type: Int): Boolean {
        return if (kind == FramebufferVideoCodec.AVC) type == 9 else type == 35
    }

    private fun isFirstSlice(nal: ByteArray): Boolean {
        return try {
            val rbsp = rbspPrefix(nal, 64)
            if (kind == FramebufferVideoCodec.AVC) {
                if (rbsp.size < 2) return true
                readUnsignedExpGolomb(rbsp, 8) == 0
            } else {
                if (rbsp.size < 3) return true
                readBit(rbsp, 16) == 1
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun rbspPrefix(data: ByteArray, maxBytes: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var zeros = 0
        val limit = minOf(data.size, maxBytes)
        for (index in 0 until limit) {
            val value = data[index].toInt() and 0xff
            if (zeros >= 2 && value == 0x03) {
                zeros = 0
                continue
            }
            out.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun readBit(data: ByteArray, bitIndex: Int): Int {
        val byte = data[bitIndex / 8].toInt() and 0xff
        return (byte shr (7 - bitIndex % 8)) and 1
    }

    private fun readUnsignedExpGolomb(data: ByteArray, startBit: Int): Int {
        var bitIndex = startBit
        var leadingZeros = 0
        val totalBits = data.size * 8
        while (bitIndex < totalBits && readBit(data, bitIndex) == 0) {
            leadingZeros++
            bitIndex++
        }
        if (bitIndex >= totalBits) return -1
        bitIndex++
        var value = 0
        repeat(leadingZeros) {
            value = (value shl 1) or readBit(data, bitIndex)
            bitIndex++
        }
        return (1 shl leadingZeros) - 1 + value
    }

    private fun withStartCode(nal: ByteArray): ByteArray = START_CODE + nal

    private fun codecName(): String =
        if (kind == FramebufferVideoCodec.AVC) "H.264" else "H.265"

    private fun describe(e: Exception): String {
        if (e is MediaCodec.CodecException) {
            return "codec error ${e.errorCode}: ${e.diagnosticInfo ?: "无诊断信息"}"
        }
        return "${e.javaClass.simpleName}: ${e.message ?: "无错误信息"}"
    }

    private fun fail(message: String) {
        synchronized(lock) {
            if (!running) return
            running = false
        }
        log(message)
        onFailure(message)
    }
}
