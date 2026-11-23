package com.zyyme.workdayalarmclock.xiaozhi

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build

/**
 * 轻量提示音：语音唤醒或打断时播一段柔和的短提示音，不占资源文件、不依赖第三方音频库。
 */
object XiaozhiToneCue {

    private const val SAMPLE_RATE = 16000
    private const val TONE_MS = 90

    fun playWakeCue(useVoiceStream: Boolean) {
        playDualTone(880.0, 1174.66, useVoiceStream)
    }

    fun playInterruptCue(useVoiceStream: Boolean) {
        playDualTone(784.0, 987.77, useVoiceStream)
    }

    private fun playDualTone(freq1: Double, freq2: Double, useVoiceStream: Boolean) {
        Thread({
            var track: AudioTrack? = null
            try {
                val samples = synthesize(freq1, freq2)
                val bytes = samples.size * 2
                track = createStaticTrack(bytes, useVoiceStream) ?: return@Thread
                track.write(samples, 0, samples.size)
                track.play()
                Thread.sleep(TONE_MS.toLong() + 40L)
            } catch (_: Throwable) {
            } finally {
                try {
                    track?.stop()
                } catch (_: Throwable) {
                }
                try {
                    track?.release()
                } catch (_: Throwable) {
                }
            }
        }, "xiaozhi-cue").apply { isDaemon = true }.start()
    }

    @Suppress("DEPRECATION")
    private fun createStaticTrack(bufferBytes: Int, useVoiceStream: Boolean): AudioTrack? {
        val created = if (useVoiceStream && Build.VERSION.SDK_INT >= 21) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            AudioTrack(attrs, format, bufferBytes, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE)
        } else {
            val streamType = if (useVoiceStream) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
            AudioTrack(
                streamType,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STATIC
            )
        }
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            return null
        }
        return created
    }

    private fun synthesize(freq1: Double, freq2: Double): ShortArray {
        val total = SAMPLE_RATE * TONE_MS / 1000
        val half = total / 2
        val out = ShortArray(total)
        val fadeSamples = SAMPLE_RATE * 10 / 1000
        for (i in 0 until total) {
            val freq = if (i < half) freq1 else freq2
            val localIdx = if (i < half) i else i - half
            val segLen = if (i < half) half else total - half
            var env = 1.0
            if (localIdx < fadeSamples) {
                env = 0.5 * (1.0 - Math.cos(Math.PI * localIdx / fadeSamples))
            } else if (segLen - localIdx < fadeSamples) {
                env = 0.5 * (1.0 - Math.cos(Math.PI * (segLen - localIdx) / fadeSamples))
            }
            val sample = Math.sin(2.0 * Math.PI * freq * i / SAMPLE_RATE) * env * 0.18 * Short.MAX_VALUE
            out[i] = sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return out
    }
}
