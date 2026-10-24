package com.zyyme.workdayalarmclock.camera

import java.io.ByteArrayOutputStream

/**
 * 从 SPS 里解析出编码分辨率。
 *
 * 裸 Annex-B 流没有容器，MediaCodec 在配置阶段又要求 KEY_WIDTH/KEY_HEIGHT
 * 因为解码器要先上报分辨率，麻烦死了
 */
internal object SpsDimensions {
    fun parseAvc(spsWithStartCode: ByteArray): Pair<Int, Int>? {
        return try {
            val nal = stripStartCode(spsWithStartCode)
            if (nal.size < 4) return null
            val rbsp = toRbsp(nal)
            val reader = BitReader(rbsp)

            reader.readBits(8) // nal_unit_header
            val profileIdc = reader.readBits(8)
            reader.readBits(8) // constraint flags + reserved
            reader.readBits(8) // level_idc
            reader.readUe() // seq_parameter_set_id

            var chromaFormatIdc = 1
            var separateColourPlane = false
            val highProfile = profileIdc in intArrayOf(
                100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135
            )
            if (highProfile) {
                chromaFormatIdc = reader.readUe()
                if (chromaFormatIdc == 3) separateColourPlane = reader.readBit() == 1
                reader.readUe() // bit_depth_luma_minus8
                reader.readUe() // bit_depth_chroma_minus8
                reader.readBit() // qpprime_y_zero_transform_bypass_flag
                if (reader.readBit() == 1) {
                    val count = if (chromaFormatIdc == 3) 12 else 8
                    for (index in 0 until count) {
                        if (reader.readBit() == 1) {
                            skipScalingList(reader, if (index < 6) 16 else 64)
                        }
                    }
                }
            }

            reader.readUe() // log2_max_frame_num_minus4
            when (reader.readUe()) { // pic_order_cnt_type
                0 -> reader.readUe() // log2_max_pic_order_cnt_lsb_minus4
                1 -> {
                    reader.readBit() // delta_pic_order_always_zero_flag
                    reader.readSe() // offset_for_non_ref_pic
                    reader.readSe() // offset_for_top_to_bottom_field
                    val cycle = reader.readUe()
                    repeat(cycle) { reader.readSe() }
                }
            }
            reader.readUe() // max_num_ref_frames
            reader.readBit() // gaps_in_frame_num_value_allowed_flag

            val widthInMbs = reader.readUe() + 1
            val heightInMapUnits = reader.readUe() + 1
            val frameMbsOnly = reader.readBit() == 1
            if (!frameMbsOnly) reader.readBit() // mb_adaptive_frame_field_flag
            reader.readBit() // direct_8x8_inference_flag

            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.readBit() == 1) {
                cropLeft = reader.readUe()
                cropRight = reader.readUe()
                cropTop = reader.readUe()
                cropBottom = reader.readUe()
            }

            val subWidthC = when {
                separateColourPlane || chromaFormatIdc == 0 -> 1
                chromaFormatIdc == 3 -> 1
                else -> 2
            }
            val subHeightC = when {
                separateColourPlane || chromaFormatIdc == 0 -> 1
                chromaFormatIdc == 1 -> 2
                else -> 1
            }
            val frameFactor = if (frameMbsOnly) 1 else 2
            val width = widthInMbs * 16 - (cropLeft + cropRight) * subWidthC
            val height = heightInMapUnits * 16 * frameFactor -
                (cropTop + cropBottom) * subHeightC * frameFactor
            if (width <= 0 || height <= 0) null else Pair(width, height)
        } catch (_: Exception) {
            null
        }
    }

    fun parseHevc(spsWithStartCode: ByteArray): Pair<Int, Int>? {
        return try {
            val nal = stripStartCode(spsWithStartCode)
            if (nal.size < 6) return null
            val rbsp = toRbsp(nal)
            val reader = BitReader(rbsp)

            reader.readBits(16) // nal_unit_header
            reader.readBits(4) // sps_video_parameter_set_id
            val maxSubLayersMinus1 = reader.readBits(3)
            reader.readBit() // sps_temporal_id_nesting_flag

            // profile_tier_level(1, maxSubLayersMinus1), general part.
            reader.readBits(2) // general_profile_space
            reader.readBit() // general_tier_flag
            reader.readBits(5) // general_profile_idc
            reader.skipBits(32) // general_profile_compatibility_flag
            reader.readBits(4) // progressive/interlaced/non_packed/frame_only
            reader.skipBits(44) // reserved zero bits
            reader.readBits(8) // general_level_idc

            val profilePresent = BooleanArray(maxSubLayersMinus1)
            val levelPresent = BooleanArray(maxSubLayersMinus1)
            for (index in 0 until maxSubLayersMinus1) {
                profilePresent[index] = reader.readBit() == 1
                levelPresent[index] = reader.readBit() == 1
            }
            if (maxSubLayersMinus1 > 0) {
                for (index in maxSubLayersMinus1 until 8) reader.skipBits(2)
            }
            for (index in 0 until maxSubLayersMinus1) {
                if (profilePresent[index]) reader.skipBits(88)
                if (levelPresent[index]) reader.readBits(8)
            }

            reader.readUe() // sps_seq_parameter_set_id
            val chromaFormatIdc = reader.readUe()
            var separateColourPlane = false
            if (chromaFormatIdc == 3) separateColourPlane = reader.readBit() == 1

            val codedWidth = reader.readUe()
            val codedHeight = reader.readUe()
            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (reader.readBit() == 1) {
                cropLeft = reader.readUe()
                cropRight = reader.readUe()
                cropTop = reader.readUe()
                cropBottom = reader.readUe()
            }

            val subWidthC = when {
                separateColourPlane || chromaFormatIdc == 0 -> 1
                chromaFormatIdc == 3 -> 1
                else -> 2
            }
            val subHeightC = when {
                separateColourPlane || chromaFormatIdc == 0 -> 1
                chromaFormatIdc == 1 -> 2
                else -> 1
            }
            val width = codedWidth - (cropLeft + cropRight) * subWidthC
            val height = codedHeight - (cropTop + cropBottom) * subHeightC
            if (width <= 0 || height <= 0) null else Pair(width, height)
        } catch (_: Exception) {
            null
        }
    }

    private fun skipScalingList(reader: BitReader, size: Int) {
        var lastScale = 8
        var nextScale = 8
        repeat(size) {
            if (nextScale != 0) {
                val deltaScale = reader.readSe()
                nextScale = (lastScale + deltaScale + 256) % 256
            }
            if (nextScale != 0) lastScale = nextScale
        }
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        var index = 0
        while (index + 2 < data.size) {
            if (data[index] == 0.toByte() && data[index + 1] == 0.toByte()) {
                if (data[index + 2] == 1.toByte()) {
                    return data.copyOfRange(index + 3, data.size)
                }
                if (index + 3 < data.size && data[index + 2] == 0.toByte() &&
                    data[index + 3] == 1.toByte()
                ) {
                    return data.copyOfRange(index + 4, data.size)
                }
            }
            index++
        }
        return data
    }

    private fun toRbsp(nal: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (byte in nal) {
            val value = byte.toInt() and 0xff
            if (zeros >= 2 && value == 0x03) {
                zeros = 0
                continue
            }
            output.write(value)
            zeros = if (value == 0) zeros + 1 else 0
        }
        return output.toByteArray()
    }

    private class BitReader(private val data: ByteArray) {
        private var bitPosition = 0

        fun readBit(): Int {
            if (bitPosition >= data.size * 8) throw IndexOutOfBoundsException()
            val value = (data[bitPosition / 8].toInt() ushr (7 - bitPosition % 8)) and 1
            bitPosition++
            return value
        }

        fun readBits(count: Int): Int {
            var value = 0
            repeat(count) { value = (value shl 1) or readBit() }
            return value
        }

        fun skipBits(count: Int) {
            bitPosition += count
            if (bitPosition > data.size * 8) throw IndexOutOfBoundsException()
        }

        fun readUe(): Int {
            var leadingZeros = 0
            while (readBit() == 0) {
                leadingZeros++
                if (leadingZeros > 31) throw IllegalArgumentException()
            }
            var value = 0
            repeat(leadingZeros) { value = (value shl 1) or readBit() }
            return (1 shl leadingZeros) - 1 + value
        }

        fun readSe(): Int {
            val value = readUe()
            return if (value and 1 == 1) (value + 1) / 2 else -(value / 2)
        }
    }
}
