package com.zyyme.workdayalarmclock

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.Charset
import kotlin.math.max

internal class FragmentedAacMp4Muxer(
    private val config: AacStreamConfig
) {
    companion object {
        private const val TRACK_ID = 1
        private val ASCII: Charset = Charset.forName("US-ASCII")
    }

    private var fragmentSequence = 1
    private var firstPresentationTimeUs: Long? = null
    private var nextDecodeTime = 0L

    fun initializationSegment(): ByteArray = concat(fileTypeBox(), movieBox())

    fun mediaFragment(packet: CameraStreamPacket): ByteArray {
        val firstPts = firstPresentationTimeUs ?: packet.presentationTimeUs.also {
            firstPresentationTimeUs = it
        }
        val timestampDecodeTime = max(
            0L,
            (packet.presentationTimeUs - firstPts) * config.sampleRate / 1_000_000L
        )
        val decodeTime = max(nextDecodeTime, timestampDecodeTime)
        nextDecodeTime = decodeTime + config.samplesPerFrame

        val moofWithoutOffset = movieFragmentBox(
            fragmentSequence,
            decodeTime,
            packet.data.size,
            0
        )
        val moof = movieFragmentBox(
            fragmentSequence,
            decodeTime,
            packet.data.size,
            moofWithoutOffset.size + 8
        )
        fragmentSequence++
        return concat(moof, box("mdat", packet.data))
    }

    private fun fileTypeBox(): ByteArray = box("ftyp", bytes {
        writeType("isom")
        writeInt(0x00000200)
        writeType("isom")
        writeType("iso6")
        writeType("mp41")
    })

    private fun movieBox(): ByteArray = box(
        "moov",
        movieHeaderBox(),
        trackBox(),
        box("mvex", trackExtendsBox())
    )

    private fun movieHeaderBox(): ByteArray = fullBox("mvhd", 0, 0, bytes {
        writeInt(0)
        writeInt(0)
        writeInt(config.sampleRate)
        writeInt(0)
        writeInt(0x00010000)
        writeShort(0x0100)
        writeShort(0)
        writeInt(0)
        writeInt(0)
        writeIdentityMatrix()
        repeat(6) { writeInt(0) }
        writeInt(2)
    })

    private fun trackBox(): ByteArray = box(
        "trak",
        trackHeaderBox(),
        box(
            "mdia",
            mediaHeaderBox(),
            handlerBox(),
            mediaInformationBox()
        )
    )

    private fun trackHeaderBox(): ByteArray = fullBox("tkhd", 0, 0x000007, bytes {
        writeInt(0)
        writeInt(0)
        writeInt(TRACK_ID)
        writeInt(0)
        writeInt(0)
        writeInt(0)
        writeInt(0)
        writeShort(0)
        writeShort(0)
        writeShort(0x0100)
        writeShort(0)
        writeIdentityMatrix()
        writeInt(0)
        writeInt(0)
    })

    private fun mediaHeaderBox(): ByteArray = fullBox("mdhd", 0, 0, bytes {
        writeInt(0)
        writeInt(0)
        writeInt(config.sampleRate)
        writeInt(0)
        writeShort(0x55c4)
        writeShort(0)
    })

    private fun handlerBox(): ByteArray = fullBox("hdlr", 0, 0, bytes {
        writeInt(0)
        writeType("soun")
        writeInt(0)
        writeInt(0)
        writeInt(0)
        write("SoundHandler\u0000".toByteArray(ASCII))
    })

    private fun mediaInformationBox(): ByteArray {
        val soundMediaHeader = fullBox("smhd", 0, 0, bytes {
            writeShort(0)
            writeShort(0)
        })
        val dataReference = fullBox("dref", 0, 0, bytes {
            writeInt(1)
            write(fullBox("url ", 0, 1, ByteArray(0)))
        })
        return box(
            "minf",
            soundMediaHeader,
            box("dinf", dataReference),
            sampleTableBox()
        )
    }

    private fun sampleTableBox(): ByteArray = box(
        "stbl",
        fullBox("stsd", 0, 0, bytes {
            writeInt(1)
            write(aacSampleEntry())
        }),
        fullBox("stts", 0, 0, bytes { writeInt(0) }),
        fullBox("stsc", 0, 0, bytes { writeInt(0) }),
        fullBox("stsz", 0, 0, bytes {
            writeInt(0)
            writeInt(0)
        }),
        fullBox("stco", 0, 0, bytes { writeInt(0) })
    )

    private fun aacSampleEntry(): ByteArray = box("mp4a", bytes {
        write(ByteArray(6))
        writeShort(1)
        writeInt(0)
        writeInt(0)
        writeShort(config.channelCount)
        writeShort(16)
        writeShort(0)
        writeShort(0)
        writeInt(config.sampleRate shl 16)
    }, elementaryStreamDescriptorBox())

    private fun elementaryStreamDescriptorBox(): ByteArray {
        val decoderSpecificInfo = descriptor(0x05, config.audioSpecificConfig)
        val decoderConfig = descriptor(0x04, bytes {
            writeByte(0x40) // MPEG-4 Audio
            writeByte(0x15) // Audio stream
            writeByte(0)
            writeByte(0)
            writeByte(0)
            writeInt(config.bitRate)
            writeInt(config.bitRate)
            write(decoderSpecificInfo)
        })
        val slConfig = descriptor(0x06, byteArrayOf(0x02))
        val esDescriptor = descriptor(0x03, bytes {
            writeShort(TRACK_ID)
            writeByte(0)
            write(decoderConfig)
            write(slConfig)
        })
        return fullBox("esds", 0, 0, esDescriptor)
    }

    private fun trackExtendsBox(): ByteArray = fullBox("trex", 0, 0, bytes {
        writeInt(TRACK_ID)
        writeInt(1)
        writeInt(config.samplesPerFrame)
        writeInt(0)
        writeInt(0)
    })

    private fun movieFragmentBox(
        sequence: Int,
        decodeTime: Long,
        sampleSize: Int,
        dataOffset: Int
    ): ByteArray {
        val movieFragmentHeader = fullBox("mfhd", 0, 0, bytes { writeInt(sequence) })
        val trackFragmentHeader = fullBox("tfhd", 0, 0x020000, bytes { writeInt(TRACK_ID) })
        val decodeTimeBox = fullBox("tfdt", 1, 0, bytes { writeLong(decodeTime) })
        val trackRun = fullBox("trun", 0, 0x000301, bytes {
            writeInt(1)
            writeInt(dataOffset)
            writeInt(config.samplesPerFrame)
            writeInt(sampleSize)
        })
        return box(
            "moof",
            movieFragmentHeader,
            box("traf", trackFragmentHeader, decodeTimeBox, trackRun)
        )
    }

    private fun descriptor(tag: Int, payload: ByteArray): ByteArray = bytes {
        writeByte(tag)
        writeDescriptorLength(payload.size)
        write(payload)
    }

    private fun DataOutputStream.writeDescriptorLength(length: Int) {
        var byteCount = 1
        var value = length
        while (value > 0x7f) {
            byteCount++
            value = value ushr 7
        }
        for (index in byteCount - 1 downTo 0) {
            val next = (length ushr (index * 7)) and 0x7f
            writeByte(if (index > 0) next or 0x80 else next)
        }
    }

    private fun fullBox(
        type: String,
        version: Int,
        flags: Int,
        payload: ByteArray
    ): ByteArray = box(type, bytes {
        writeByte(version)
        writeByte((flags ushr 16) and 0xff)
        writeByte((flags ushr 8) and 0xff)
        writeByte(flags and 0xff)
        write(payload)
    })

    private fun box(type: String, vararg payloads: ByteArray): ByteArray {
        val payloadSize = payloads.sumOf { it.size }
        return bytes {
            writeInt(payloadSize + 8)
            writeType(type)
            payloads.forEach { write(it) }
        }
    }

    private fun bytes(block: DataOutputStream.() -> Unit): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { data -> data.block() }
        return output.toByteArray()
    }

    private fun concat(vararg values: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(values.sumOf { it.size })
        values.forEach { output.write(it) }
        return output.toByteArray()
    }

    private fun DataOutputStream.writeType(type: String) {
        require(type.length == 4)
        write(type.toByteArray(ASCII))
    }

    private fun DataOutputStream.writeIdentityMatrix() {
        writeInt(0x00010000)
        writeInt(0)
        writeInt(0)
        writeInt(0)
        writeInt(0x00010000)
        writeInt(0)
        writeInt(0)
        writeInt(0)
        writeInt(0x40000000)
    }
}
