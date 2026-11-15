package com.zyyme.workdayalarmclock.xiaozhi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmFrameChunkerTest {

    @Test
    fun splitsExactFrames() {
        val chunker = PcmFrameChunker(4)
        val frames = chunker.append(shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        assertEquals(2, frames.size)
        assertEquals(listOf<Short>(1, 2, 3, 4), frames[0].toList())
        assertEquals(listOf<Short>(5, 6, 7, 8), frames[1].toList())
        assertEquals(0, chunker.bufferedSamples)
    }

    @Test
    fun keepsRemainder() {
        val chunker = PcmFrameChunker(4)
        val frames = chunker.append(shortArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(1, frames.size)
        assertEquals(2, chunker.bufferedSamples)

        val more = chunker.append(shortArrayOf(7, 8))
        assertEquals(1, more.size)
        assertEquals(listOf<Short>(5, 6, 7, 8), more[0].toList())
        assertEquals(0, chunker.bufferedSamples)
    }

    @Test
    fun handlesOffset() {
        val chunker = PcmFrameChunker(2)
        val frames = chunker.append(shortArrayOf(9, 1, 2, 9), offset = 1, length = 2)
        assertEquals(1, frames.size)
        assertEquals(listOf<Short>(1, 2), frames[0].toList())
    }

    @Test
    fun emptyInputDoesNothing() {
        val chunker = PcmFrameChunker(4)
        assertTrue(chunker.append(ShortArray(0)).isEmpty())
        assertTrue(chunker.append(shortArrayOf(1, 2), 0, 0).isEmpty())
        assertEquals(0, chunker.bufferedSamples)
    }

    @Test
    fun resetDropsRemainder() {
        val chunker = PcmFrameChunker(4)
        chunker.append(shortArrayOf(1, 2))
        chunker.reset()
        assertEquals(0, chunker.bufferedSamples)
    }

    @Test
    fun defaultFrameIs60msAt16k() {
        assertEquals(960, XiaozhiProtocol.UPLOAD_FRAME_SAMPLES)
        val chunker = PcmFrameChunker()
        val pcm = ShortArray(16000 / 10) // 100ms
        val frames = chunker.append(pcm)
        assertEquals(1, frames.size)
        assertEquals(960, frames[0].size)
        assertEquals(640, chunker.bufferedSamples)
    }
}
