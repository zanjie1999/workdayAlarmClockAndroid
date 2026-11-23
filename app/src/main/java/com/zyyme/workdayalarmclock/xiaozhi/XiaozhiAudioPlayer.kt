package com.zyyme.workdayalarmclock.xiaozhi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 下行 TTS 播放器：opus 包 -> PCM -> AudioTrack 流式播放。
 *
 * 不用 ExoPlayer，也不用 AudioFocus（MeService 已常驻持有 GAIN，同 UID 再申请在廉价
 * OEM 音频栈上不可靠）。打断用 [flushAndPause]，一轮说完用 [markEndOfTurn]，
 * 排空后回调 [onDrained] 给上层决定自动续听还是结束会话。
 *
 * 当 [useVoiceStream] 为 true 时走 `USAGE_VOICE_COMMUNICATION` / `STREAM_VOICE_CALL`，
 * 配合 `MODE_IN_COMMUNICATION` 让 Android HAL 把 TTS 下行送入硬件 AEC 参考信号。
 */
class XiaozhiAudioPlayer(
    private val sampleRate: Int,
    private val useVoiceStream: Boolean = false,
    private val log: (String) -> Unit = {}
) {

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var decoder: OpusDecoderApi? = null

    /** 一轮话说完（收到 tts stop）之后等排空 */
    @Volatile
    private var endOfTurn = false

    @Volatile
    private var drainedNotified = true

    /** 已写入的总帧数，与 playbackHeadPosition 同一基准（flush 后重新对齐） */
    @Volatile
    private var writtenFrames = 0

    private var frameSamples = sampleRate * XiaozhiProtocol.FRAME_DURATION_MS / 1000

    @Volatile
    var onDrained: (() -> Unit)? = null

    @Volatile
    var onDecodedPcm: ((ShortArray, Int, Int) -> Unit)? = null

    private val rmsLock = Any()
    private val rmsValues = DoubleArray(RMS_WINDOW_SLOTS)
    private val rmsTimes = LongArray(RMS_WINDOW_SLOTS)
    private var rmsCursor = 0

    fun currentPlaybackRmsPeak(nowMs: Long = SystemClock.elapsedRealtime()): Double {
        synchronized(rmsLock) {
            var peak = 0.0
            for (i in 0 until RMS_WINDOW_SLOTS) {
                val t = rmsTimes[i]
                if (t > 0L && nowMs - t <= RMS_WINDOW_MS && rmsValues[i] > peak) {
                    peak = rmsValues[i]
                }
            }
            return peak
        }
    }

    private fun recordPlaybackRms(rms: Double) {
        val now = SystemClock.elapsedRealtime()
        synchronized(rmsLock) {
            rmsValues[rmsCursor] = rms
            rmsTimes[rmsCursor] = now
            rmsCursor = (rmsCursor + 1) % RMS_WINDOW_SLOTS
        }
    }

    private fun clearPlaybackRms() {
        synchronized(rmsLock) {
            rmsValues.fill(0.0)
            rmsTimes.fill(0L)
            rmsCursor = 0
        }
    }

    fun start() {
        if (running.getAndSet(true)) return
        decoder = ConcentusOpusCodec.createDecoder(sampleRate)
        frameSamples = sampleRate * XiaozhiProtocol.FRAME_DURATION_MS / 1000
        val created = createTrack()
        if (created == null) {
            running.set(false)
            return
        }
        track = created
        thread = Thread({ loop() }, "xiaozhi-play").apply {
            priority = Thread.NORM_PRIORITY + 2
            start()
        }
    }

    private fun createTrack(): AudioTrack? {
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val frameBytes = frameSamples * 2
            val framesInBuffer = if (useVoiceStream) 4 else 6
            val bufferSize = maxOf(minBuffer, frameBytes * framesInBuffer)
            @Suppress("DEPRECATION")
            val created = if (useVoiceStream && Build.VERSION.SDK_INT >= 21) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val format = AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
                AudioTrack(
                    attrs,
                    format,
                    bufferSize,
                    AudioTrack.MODE_STREAM,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
                )
            } else {
                val streamType = if (useVoiceStream) {
                    AudioManager.STREAM_VOICE_CALL
                } else {
                    AudioManager.STREAM_MUSIC
                }
                AudioTrack(
                    streamType,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM
                )
            }
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                log("AudioTrack 初始化失败，TTS 无法播放")
                created.release()
                null
            } else {
                created
            }
        } catch (e: Exception) {
            log("创建 AudioTrack 失败：${e.message}")
            null
        }
    }

    fun enqueue(packet: ByteArray) {
        if (!running.get()) return
        if (!queue.offer(packet)) {
            // 队列满（异常情况）丢最旧的，宁可掉一句也不要卡住
            queue.poll()
            queue.offer(packet)
        }
    }

    /** 打断：丢掉还没播的，暂停播放头 */
    fun flushAndPause() {
        queue.clear()
        clearPlaybackRms()
        endOfTurn = false
        drainedNotified = true
        val target = track ?: return
        try {
            target.pause()
            target.flush()
            writtenFrames = target.playbackHeadPosition
        } catch (e: Exception) {
            log("flush 播放器失败：${e.message}")
        }
    }

    /** 一轮回复结束，等播放排空后回调 onDrained */
    fun markEndOfTurn() {
        endOfTurn = true
        drainedNotified = false
    }

    fun stop() {
        running.set(false)
        queue.clear()
        clearPlaybackRms()
        thread?.interrupt()
        thread = null
        val target = track
        track = null
        if (target != null) {
            try {
                target.pause()
                target.flush()
                target.stop()
            } catch (ignored: Exception) {
            }
            try {
                target.release()
            } catch (ignored: Exception) {
            }
        }
        decoder?.release()
        decoder = null
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        while (running.get()) {
            try {
                val packet = queue.poll(40, TimeUnit.MILLISECONDS)
                if (packet != null) {
                    playPacket(packet)
                } else if (endOfTurn && !drainedNotified) {
                    checkDrained()
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Exception) {
                log("播放线程异常：${e.message}")
            }
        }
    }

    private fun playPacket(packet: ByteArray) {
        val dec = decoder ?: return
        val target = track ?: return
        val pcm = dec.decode(packet, frameSamples) ?: return
        if (pcm.isEmpty()) return
        onDecodedPcm?.invoke(pcm, pcm.size, sampleRate)
        recordPlaybackRms(computeRms(pcm))
        try {
            if (target.playState != AudioTrack.PLAYSTATE_PLAYING) target.play()
            var offset = 0
            while (offset < pcm.size && running.get()) {
                val written = target.write(pcm, offset, pcm.size - offset)
                if (written <= 0) break
                offset += written
            }
            writtenFrames += offset
        } catch (e: Exception) {
            log("写 AudioTrack 失败：${e.message}")
        }
    }

    private fun computeRms(pcm: ShortArray): Double {
        var sum = 0.0
        for (s in pcm) {
            val v = s.toDouble()
            sum += v * v
        }
        return Math.sqrt(sum / pcm.size)
    }

    private fun checkDrained() {
        val target = track ?: return
        val head = target.playbackHeadPosition
        if (writtenFrames - head <= 0) {
            clearPlaybackRms()
            drainedNotified = true
            endOfTurn = false
            onDrained?.invoke()
        }
    }

    companion object {
        private const val RMS_WINDOW_SLOTS = 8
        private const val RMS_WINDOW_MS = 350L
    }
}
