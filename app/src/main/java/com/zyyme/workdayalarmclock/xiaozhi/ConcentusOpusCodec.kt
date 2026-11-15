package com.zyyme.workdayalarmclock.xiaozhi

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder

/**
 * Concentus（纯 Java Opus）实现。
 * 编码器只在采集线程里用，解码器只在播放线程里用，互不加锁。
 */
object ConcentusOpusCodec {

    /** opus 单包上限 1275*3，留点余量 */
    private const val MAX_PACKET_BYTES = 1500

    fun createEncoder(
        sampleRate: Int = XiaozhiProtocol.UPLOAD_SAMPLE_RATE,
        channels: Int = XiaozhiProtocol.UPLOAD_CHANNELS,
        bitrate: Int = 16000,
        complexity: Int = 5
    ): OpusEncoderApi = ConcentusEncoder(sampleRate, channels, bitrate, complexity)

    fun createDecoder(
        sampleRate: Int = XiaozhiProtocol.DEFAULT_DOWNLOAD_SAMPLE_RATE,
        channels: Int = 1
    ): OpusDecoderApi = ConcentusDecoder(sampleRate, channels)

    private class ConcentusEncoder(
        sampleRate: Int,
        channels: Int,
        bitrate: Int,
        complexity: Int
    ) : OpusEncoderApi {

        private val encoder = OpusEncoder(sampleRate, channels, OpusApplication.OPUS_APPLICATION_VOIP)
        private val out = ByteArray(MAX_PACKET_BYTES)

        init {
            try {
                encoder.setBitrate(bitrate)
                encoder.setComplexity(complexity)
                encoder.setUseVBR(true)
            } catch (ignored: Throwable) {
            }
        }

        override fun encode(pcm: ShortArray, pcmOffset: Int, frameSamples: Int): ByteArray? {
            return try {
                val length = encoder.encode(pcm, pcmOffset, frameSamples, out, 0, out.size)
                if (length <= 0) null else out.copyOf(length)
            } catch (e: Throwable) {
                null
            }
        }

        override fun release() {
            // Concentus 没有显式的 release
        }
    }

    private class ConcentusDecoder(
        sampleRate: Int,
        channels: Int
    ) : OpusDecoderApi {

        private val decoder = OpusDecoder(sampleRate, channels)
        /** 一次 decode 最多 120ms，按最大可能分配，避免每包都新建数组 */
        private val pcm = ShortArray(sampleRate / 1000 * 120 * channels)

        override fun decode(packet: ByteArray, frameSamples: Int): ShortArray? {
            return try {
                val samples = decoder.decode(packet, 0, packet.size, pcm, 0, frameSamples, false)
                if (samples <= 0) null else pcm.copyOf(samples)
            } catch (e: Throwable) {
                null
            }
        }

        override fun release() {
            // Concentus 没有显式的 release
        }
    }
}
