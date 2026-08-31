package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

internal data class AacStreamConfig(
    val sampleRate: Int,
    val channelCount: Int,
    val bitRate: Int,
    val samplesPerFrame: Int,
    val audioSpecificConfig: ByteArray,
    val codecString: String = "mp4a.40.2"
)

@SuppressLint("MissingPermission")
internal class AacAudioPipeline(
    private val log: (String) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 44_100
        private const val CHANNEL_COUNT = 1
        private const val BIT_RATE = 64_000
        private const val AAC_SAMPLES_PER_FRAME = 1_024
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_INPUT_SIZE = 16_384

        fun isPlatformSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN

        private fun audioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
            val frequencyIndex = when (sampleRate) {
                96_000 -> 0
                88_200 -> 1
                64_000 -> 2
                48_000 -> 3
                44_100 -> 4
                32_000 -> 5
                24_000 -> 6
                22_050 -> 7
                16_000 -> 8
                12_000 -> 9
                11_025 -> 10
                8_000 -> 11
                7_350 -> 12
                else -> throw IllegalArgumentException("不支持的AAC采样率：$sampleRate")
            }
            val audioObjectType = 2 // AAC-LC
            return byteArrayOf(
                ((audioObjectType shl 3) or (frequencyIndex shr 1)).toByte(),
                (((frequencyIndex and 1) shl 7) or (channelCount shl 3)).toByte()
            )
        }
    }

    val config = AacStreamConfig(
        SAMPLE_RATE,
        CHANNEL_COUNT,
        BIT_RATE,
        AAC_SAMPLES_PER_FRAME,
        audioSpecificConfig(SAMPLE_RATE, CHANNEL_COUNT)
    )

    private val running = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val frameHub = CameraFrameHub(maxPackets = 64, keepLatestOnly = false)
    private var audioRecord: AudioRecord? = null
    private var codec: MediaCodec? = null
    private var captureThread: Thread? = null

    fun start(): Boolean {
        if (!isPlatformSupported() || released.get() || !running.compareAndSet(false, true)) {
            return false
        }

        var newRecord: AudioRecord? = null
        var newCodec: MediaCodec? = null
        return try {
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val minimumBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, encoding)
            if (minimumBufferSize <= 0) throw IllegalStateException("设备不支持麦克风采样")
            val recordBufferSize = max(minimumBufferSize * 2, 4_096)

            newRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                channelConfig,
                encoding,
                recordBufferSize
            )
            if (newRecord.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("麦克风初始化失败")
            }

            val format = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                SAMPLE_RATE,
                CHANNEL_COUNT
            ).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)
            }
            newCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            newCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            newCodec.start()
            newRecord.startRecording()
            if (newRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("麦克风录音启动失败")
            }

            audioRecord = newRecord
            codec = newCodec
            captureThread = Thread(
                { captureLoop(newRecord, newCodec, recordBufferSize) },
                "camera-aac-capture"
            ).apply { start() }
            true
        } catch (e: Exception) {
            running.set(false)
            try {
                newRecord?.stop()
            } catch (_: Exception) {
            }
            try {
                newRecord?.release()
            } catch (_: Exception) {
            }
            try {
                newCodec?.stop()
            } catch (_: Exception) {
            }
            try {
                newCodec?.release()
            } catch (_: Exception) {
            }
            log("麦克风AAC启动失败：${e.message}")
            false
        }
    }

    fun awaitPacket(afterSequence: Long): CameraStreamPacket? = frameHub.awaitNext(afterSequence)

    fun stop() {
        if (!released.compareAndSet(false, true)) return
        running.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        captureThread?.interrupt()
        try {
            captureThread?.join(1_500L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        captureThread = null
        frameHub.close()
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
    }

    @Suppress("DEPRECATION")
    private fun captureLoop(record: AudioRecord, encoder: MediaCodec, recordBufferSize: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val frameBytes = CHANNEL_COUNT * BYTES_PER_SAMPLE
        val pcm = ByteArray(min(MAX_INPUT_SIZE, max(2_048, recordBufferSize)))
        val outputInfo = MediaCodec.BufferInfo()
        var capturedFrames = 0L
        var legacyInputBuffers = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            encoder.inputBuffers
        } else {
            null
        }
        var legacyOutputBuffers = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            encoder.outputBuffers
        } else {
            null
        }

        try {
            while (running.get()) {
                val inputIndex = encoder.dequeueInputBuffer(10_000L)
                if (inputIndex >= 0) {
                    val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        encoder.getInputBuffer(inputIndex)
                    } else {
                        legacyInputBuffers?.get(inputIndex)
                    }
                    if (inputBuffer == null) {
                        encoder.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                    } else {
                        inputBuffer.clear()
                        val readSize = min(pcm.size, inputBuffer.remaining()) / frameBytes * frameBytes
                        val bytesRead = record.read(pcm, 0, readSize)
                        if (bytesRead > 0) {
                            inputBuffer.put(pcm, 0, bytesRead)
                            val presentationTimeUs = capturedFrames * 1_000_000L / SAMPLE_RATE
                            encoder.queueInputBuffer(inputIndex, 0, bytesRead, presentationTimeUs, 0)
                            capturedFrames += bytesRead / frameBytes
                        } else {
                            encoder.queueInputBuffer(inputIndex, 0, 0, 0L, 0)
                            if (bytesRead == AudioRecord.ERROR_INVALID_OPERATION ||
                                bytesRead == AudioRecord.ERROR_BAD_VALUE
                            ) {
                                throw IllegalStateException("麦克风读取失败：$bytesRead")
                            }
                        }
                    }
                }

                while (running.get()) {
                    when (val outputIndex = encoder.dequeueOutputBuffer(outputInfo, 0L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> break
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                        MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                                legacyOutputBuffers = encoder.outputBuffers
                            }
                        }
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                encoder.getOutputBuffer(outputIndex)
                            } else {
                                legacyOutputBuffers?.get(outputIndex)
                            }
                            val isCodecConfig = outputInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!isCodecConfig && outputInfo.size > 0 && outputBuffer != null) {
                                frameHub.publish(
                                    copyOutput(outputBuffer, outputInfo),
                                    keyFrame = true,
                                    presentationTimeUs = outputInfo.presentationTimeUs
                                )
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running.get()) log("麦克风AAC编码停止：${e.message}")
        } finally {
            running.set(false)
            frameHub.close()
        }
    }

    private fun copyOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo): ByteArray {
        val copy = buffer.duplicate()
        copy.position(info.offset)
        copy.limit(info.offset + info.size)
        return ByteArray(info.size).also { copy.get(it) }
    }
}
