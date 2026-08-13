package com.zyyme.workdayalarmclock

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.charset.Charset
import java.util.Locale
import kotlin.math.max

internal class FragmentedMp4Muxer(
    private val config: AvcStreamConfig
) {
    companion object {
        private const val TIMESCALE = 90_000
        private const val TRACK_ID = 1
        private val ASCII: Charset = Charset.forName("US-ASCII")

        fun parseAvcConfig(
            annexB: ByteArray,
            width: Int,
            height: Int,
            frameRate: Int
        ): AvcStreamConfig? {
            val units = splitAnnexB(annexB)
            val sps = units.firstOrNull { nalType(it) == 7 } ?: return null
            val pps = units.firstOrNull { nalType(it) == 8 } ?: return null
            if (sps.size < 4) return null
            val codecString = String.format(
                Locale.US,
                "avc1.%02X%02X%02X",
                sps[1].toInt() and 0xff,
                sps[2].toInt() and 0xff,
                sps[3].toInt() and 0xff
            )
            return AvcStreamConfig(
                sps.copyOf(),
                pps.copyOf(),
                width,
                height,
                frameRate.coerceAtLeast(1),
                codecString
            )
        }

        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val units = mutableListOf<ByteArray>()
            var start = findStartCode(data, 0) ?: return units
            while (true) {
                val nalStart = start.first + start.second
                val next = findStartCode(data, nalStart)
                val nalEnd = next?.first ?: data.size
                if (nalEnd > nalStart) {
                    units.add(data.copyOfRange(nalStart, nalEnd))
                }
                if (next == null) break
                start = next
            }
            return units
        }

        private fun findStartCode(data: ByteArray, fromIndex: Int): Pair<Int, Int>? {
            var index = fromIndex.coerceAtLeast(0)
            while (index + 2 < data.size) {
                if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                    if (data[index + 2] == 1.toByte()) return Pair(index, 3)
                    if (index + 3 < data.size && data[index + 2] == 0.toByte() &&
                        data[index + 3] == 1.toByte()
                    ) return Pair(index, 4)
                }
                index++
            }
            return null
        }

        private fun nalType(nal: ByteArray): Int {
            return if (nal.isEmpty()) -1 else nal[0].toInt() and 0x1f
        }
    }

    private val sampleDuration = max(1, TIMESCALE / config.frameRate)
    private var fragmentSequence = 1
    private var firstPresentationTimeUs: Long? = null
    private var nextDecodeTime = 0L

    fun initializationSegment(): ByteArray {
        return concat(fileTypeBox(), movieBox())
    }

    fun mediaFragment(packet: CameraStreamPacket): ByteArray? {
        val sample = toAvcSample(packet.data) ?: return null
        val firstPts = firstPresentationTimeUs ?: packet.presentationTimeUs.also {
            firstPresentationTimeUs = it
        }
        val timestampDecodeTime = max(
            0L,
            (packet.presentationTimeUs - firstPts) * TIMESCALE / 1_000_000L
        )
        val decodeTime = max(nextDecodeTime, timestampDecodeTime)
        nextDecodeTime = decodeTime + sampleDuration

        val moofWithoutOffset = movieFragmentBox(
            fragmentSequence,
            decodeTime,
            sample.size,
            packet.keyFrame,
            0
        )
        val dataOffset = moofWithoutOffset.size + 8
        val moof = movieFragmentBox(
            fragmentSequence,
            decodeTime,
            sample.size,
            packet.keyFrame,
            dataOffset
        )
        fragmentSequence++
        return concat(moof, box("mdat", sample))
    }

    private fun toAvcSample(annexB: ByteArray): ByteArray? {
        val units = splitAnnexB(annexB).filter {
            val type = nalType(it)
            type != 7 && type != 8
        }
        if (units.isEmpty()) return null
        return bytes {
            units.forEach { nal ->
                writeInt(nal.size)
                write(nal)
            }
        }
    }

    private fun fileTypeBox(): ByteArray {
        return box("ftyp", bytes {
            writeType("isom")
            writeInt(0x00000200)
            writeType("isom")
            writeType("iso6")
            writeType("avc1")
            writeType("mp41")
        })
    }

    private fun movieBox(): ByteArray {
        return box(
            "moov",
            movieHeaderBox(),
            trackBox(),
            box("mvex", trackExtendsBox())
        )
    }

    private fun movieHeaderBox(): ByteArray {
        return fullBox("mvhd", 0, 0, bytes {
            writeInt(0)
            writeInt(0)
            writeInt(TIMESCALE)
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
    }

    private fun trackBox(): ByteArray {
        return box(
            "trak",
            trackHeaderBox(),
            box(
                "mdia",
                mediaHeaderBox(),
                handlerBox(),
                mediaInformationBox()
            )
        )
    }

    private fun trackHeaderBox(): ByteArray {
        return fullBox("tkhd", 0, 0x000007, bytes {
            writeInt(0)
            writeInt(0)
            writeInt(TRACK_ID)
            writeInt(0)
            writeInt(0)
            writeInt(0)
            writeInt(0)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeIdentityMatrix()
            writeInt(config.width shl 16)
            writeInt(config.height shl 16)
        })
    }

    private fun mediaHeaderBox(): ByteArray {
        return fullBox("mdhd", 0, 0, bytes {
            writeInt(0)
            writeInt(0)
            writeInt(TIMESCALE)
            writeInt(0)
            writeShort(0x55c4) // ISO-639-2/T language code: und
            writeShort(0)
        })
    }

    private fun handlerBox(): ByteArray {
        return fullBox("hdlr", 0, 0, bytes {
            writeInt(0)
            writeType("vide")
            writeInt(0)
            writeInt(0)
            writeInt(0)
            write("VideoHandler\u0000".toByteArray(ASCII))
        })
    }

    private fun mediaInformationBox(): ByteArray {
        val videoMediaHeader = fullBox("vmhd", 0, 1, bytes {
            writeShort(0)
            writeShort(0)
            writeShort(0)
            writeShort(0)
        })
        val dataReference = fullBox("dref", 0, 0, bytes {
            writeInt(1)
            write(fullBox("url ", 0, 1, ByteArray(0)))
        })
        val dataInformation = box("dinf", dataReference)
        return box("minf", videoMediaHeader, dataInformation, sampleTableBox())
    }

    private fun sampleTableBox(): ByteArray {
        val sampleDescription = fullBox("stsd", 0, 0, bytes {
            writeInt(1)
            write(avcSampleEntry())
        })
        val timeToSample = fullBox("stts", 0, 0, bytes { writeInt(0) })
        val sampleToChunk = fullBox("stsc", 0, 0, bytes { writeInt(0) })
        val sampleSize = fullBox("stsz", 0, 0, bytes {
            writeInt(0)
            writeInt(0)
        })
        val chunkOffset = fullBox("stco", 0, 0, bytes { writeInt(0) })
        return box(
            "stbl",
            sampleDescription,
            timeToSample,
            sampleToChunk,
            sampleSize,
            chunkOffset
        )
    }

    private fun avcSampleEntry(): ByteArray {
        val visualSampleEntry = bytes {
            write(ByteArray(6))
            writeShort(1)
            writeShort(0)
            writeShort(0)
            writeInt(0)
            writeInt(0)
            writeInt(0)
            writeShort(config.width)
            writeShort(config.height)
            writeInt(0x00480000)
            writeInt(0x00480000)
            writeInt(0)
            writeShort(1)
            write(ByteArray(32))
            writeShort(0x0018)
            writeShort(0xffff)
        }
        return box("avc1", visualSampleEntry, avcConfigurationBox())
    }

    private fun avcConfigurationBox(): ByteArray {
        val sps = config.sps
        val pps = config.pps
        return box("avcC", bytes {
            writeByte(1)
            writeByte(sps[1].toInt() and 0xff)
            writeByte(sps[2].toInt() and 0xff)
            writeByte(sps[3].toInt() and 0xff)
            writeByte(0xff)
            writeByte(0xe1)
            writeShort(sps.size)
            write(sps)
            writeByte(1)
            writeShort(pps.size)
            write(pps)
        })
    }

    private fun trackExtendsBox(): ByteArray {
        return fullBox("trex", 0, 0, bytes {
            writeInt(TRACK_ID)
            writeInt(1)
            writeInt(sampleDuration)
            writeInt(0)
            writeInt(0x01010000)
        })
    }

    private fun movieFragmentBox(
        sequence: Int,
        decodeTime: Long,
        sampleSize: Int,
        keyFrame: Boolean,
        dataOffset: Int
    ): ByteArray {
        val movieFragmentHeader = fullBox("mfhd", 0, 0, bytes {
            writeInt(sequence)
        })
        val trackFragmentHeader = fullBox("tfhd", 0, 0x020000, bytes {
            writeInt(TRACK_ID)
        })
        val decodeTimeBox = fullBox("tfdt", 1, 0, bytes {
            writeLong(decodeTime)
        })
        val sampleFlags = if (keyFrame) 0x02000000 else 0x01010000
        val trackRun = fullBox("trun", 0, 0x000701, bytes {
            writeInt(1)
            writeInt(dataOffset)
            writeInt(sampleDuration)
            writeInt(sampleSize)
            writeInt(sampleFlags)
        })
        return box(
            "moof",
            movieFragmentHeader,
            box("traf", trackFragmentHeader, decodeTimeBox, trackRun)
        )
    }

    private fun fullBox(
        type: String,
        version: Int,
        flags: Int,
        payload: ByteArray
    ): ByteArray {
        return box(type, bytes {
            writeByte(version)
            writeByte((flags ushr 16) and 0xff)
            writeByte((flags ushr 8) and 0xff)
            writeByte(flags and 0xff)
            write(payload)
        })
    }

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
