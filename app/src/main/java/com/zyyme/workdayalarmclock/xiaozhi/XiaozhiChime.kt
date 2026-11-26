package com.zyyme.workdayalarmclock.xiaozhi

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 唤醒提示音：一段 160ms 的双音 PCM 在代码里现算，丢给一个临时 AudioTrack，播完立刻 release
 */
object XiaozhiChime {

    private const val RATE = 16000

    // 880Hz 55ms -> 20ms 静音 -> 1320Hz 85ms 「咚叮」
    private const val FIRST_HZ = 880.0
    private const val FIRST_MS = 55
    private const val GAP_MS = 20
    private const val SECOND_HZ = 1320.0
    private const val SECOND_MS = 85

    /** 每个音的淡入淡出，防爆音 */
    private const val FADE_MS = 6

    /** 相对满量程的幅度 */
    private const val AMPLITUDE = 0.35

    /** 播完到 release 之间多留的余量，留出 HAL 的排空时间 */
    private const val RELEASE_MARGIN_MS = 150L

    private val handler = Handler(Looper.getMainLooper())

    private var current: AudioTrack? = null
    private var releaseTask: Runnable? = null

    /** 同步创建 AudioTrack：只阻塞调用线程 20~40ms，比再起一个线程便宜 */
    fun play(context: Context?, log: (String) -> Unit = {}) {
        val pcm = buildPcm()
        val durationMs = pcm.size * 1000L / RATE
        releaseCurrent()
        val minBuffer = try {
            AudioTrack.getMinBufferSize(
                RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (e: Exception) {
            -1
        }
        if (minBuffer <= 0) {
            log("提示音：不支持 ${RATE}Hz 单声道（minBuffer=$minBuffer）")
            return
        }
        // 缓冲不能小于 minBuffer，老机器上给少了构造函数直接抛 IllegalArgumentException
        val bufferBytes = maxOf(minBuffer, pcm.size * 2)
        val created = try {
            @Suppress("DEPRECATION")
            AudioTrack(
                AudioManager.STREAM_MUSIC,
                RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
                AudioTrack.MODE_STREAM
            )
        } catch (e: Exception) {
            log("提示音：创建 AudioTrack 失败 ${e.message}")
            return
        }
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            log("提示音：AudioTrack 未初始化 state=${created.state}")
            created.release()
            return
        }
        try {
            @Suppress("DEPRECATION")
            created.setStereoVolume(1f, 1f)
            created.play()
            var offset = 0
            while (offset < pcm.size) {
                val written = created.write(pcm, offset, pcm.size - offset)
                if (written <= 0) {
                    log("提示音：写入失败 code=$written")
                    break
                }
                offset += written
            }
        } catch (e: Exception) {
            log("提示音：播放失败 ${e.message}")
            created.release()
            return
        }
        log("提示音：${durationMs}ms 缓冲=${bufferBytes} 媒体音量=${mediaVolume(context)}")
        current = created
        val task = Runnable {
            if (current === created) {
                current = null
                releaseTask = null
            }
            try {
                created.stop()
            } catch (ignored: Exception) {
            }
            created.release()
        }
        releaseTask = task
        handler.postDelayed(task, durationMs + RELEASE_MARGIN_MS)
    }

    /** 媒体音量，0 的时候就不是硬件问题，是静音放出来的 */
    private fun mediaVolume(context: Context?): String {
        val manager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return "?"
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val now = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) "$now/$max" else "?"
    }

    /** 上一声还没释放就再来一声（连击唤醒）时，先把旧的收掉 */
    private fun releaseCurrent() {
        releaseTask?.let { handler.removeCallbacks(it) }
        releaseTask = null
        val old = current
        current = null
        if (old != null) {
            try {
                old.stop()
            } catch (ignored: Exception) {
            }
            old.release()
        }
    }

    private fun buildPcm(): ShortArray {
        val first = FIRST_MS * RATE / 1000
        val gap = GAP_MS * RATE / 1000
        val second = SECOND_MS * RATE / 1000
        val out = ShortArray(first + gap + second)
        fillTone(out, 0, first, FIRST_HZ)
        fillTone(out, first + gap, second, SECOND_HZ)
        return out
    }

    private fun fillTone(out: ShortArray, offset: Int, count: Int, hz: Double) {
        val fade = FADE_MS * RATE / 1000
        val step = 2.0 * PI * hz / RATE
        for (i in 0 until count) {
            val gain = when {
                i < fade -> i.toDouble() / fade
                i >= count - fade -> (count - i).toDouble() / fade
                else -> 1.0
            }
            out[offset + i] = (sin(step * i) * gain * AMPLITUDE * Short.MAX_VALUE).roundToInt().toShort()
        }
    }
}
