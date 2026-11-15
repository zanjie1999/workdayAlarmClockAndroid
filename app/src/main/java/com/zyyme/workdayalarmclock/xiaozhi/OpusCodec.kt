package com.zyyme.workdayalarmclock.xiaozhi

/**
 * Opus 编解码抽象。
 * 现在由纯 Java 的 Concentus 实现；如果低端机 CPU 顶不住，可以换 libopus 的 .so 而不动上层。
 */
interface OpusEncoderApi {
    /** @return 编码后的 opus 包；失败返回 null */
    fun encode(pcm: ShortArray, pcmOffset: Int, frameSamples: Int): ByteArray?

    fun release()
}

interface OpusDecoderApi {
    /** @return 解码出的 PCM 采样；失败返回 null */
    fun decode(packet: ByteArray, frameSamples: Int): ShortArray?

    fun release()
}
