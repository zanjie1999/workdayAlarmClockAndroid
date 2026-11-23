package com.zyyme.workdayalarmclock.xiaozhi

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 待机语音唤醒监听器：在会话 IDLE 期间用低占用的裸麦 `AudioSource.MIC` 监听唤醒词，
 * 不切 `MODE_IN_COMMUNICATION`，保证后台音乐正常外放。
 *
 * 一旦命中唤醒词：先快照最近 1.5 秒预滚 PCM 并释放自己的 AudioRecord，
 * 等麦克风硬件彻底让出后才触发 [onDetected]，避免和对话期的 AudioRecord 抢麦。
 */
class XiaozhiWakeWordMonitor(
    private val engine: XiaozhiWakeWordEngine,
    private val onDetected: (wakeWord: String, prerollPcm: ShortArray) -> Unit,
    private val onError: (String) -> Unit = {},
    private val log: (String) -> Unit = {}
) {

    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var record: AudioRecord? = null

    private val preroll = ShortArray(PREROLL_SAMPLES)
    private var prerollWrite = 0
    private var prerollFilled = 0

    val isRunning: Boolean get() = running.get()

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running.set(false)
            onError("待机唤醒：麦克风参数不被支持")
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE * 2)
        val recorder = try {
            @Suppress("DEPRECATION")
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: Exception) {
            running.set(false)
            onError("待机唤醒创建 AudioRecord 失败：${e.message}")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            onError("待机唤醒：麦克风被占用")
            return false
        }
        record = recorder
        prerollWrite = 0
        prerollFilled = 0
        engine.resetWakeWord()
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            releaseRecorder(recorder)
            record = null
            running.set(false)
            onError("待机唤醒启动录音失败：${e.message}")
            return false
        }

        val worker = Thread({ monitorLoop(recorder) }, "xiaozhi-wake").apply { start() }
        thread = worker
        log("待机语音唤醒监听已启动")
        return true
    }

    /** 同步停止并等待硬件释放完毕，防止紧接着创建会话 AudioRecord 时撞车 */
    fun stopAndAwait(timeoutMs: Long = 600L) {
        val wasRunning = running.getAndSet(false)
        val worker = thread
        thread = null
        val rec = record
        if (wasRunning || rec != null) {
            try {
                rec?.stop()
            } catch (_: Exception) {
            }
        }
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(timeoutMs)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        if (record != null) {
            val stale = record
            record = null
            releaseRecorder(stale)
        }
    }

    private fun monitorLoop(recorder: AudioRecord) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
        val readBuffer = ShortArray(READ_SAMPLES)
        var detectedWord: String? = null
        var detectedPreroll: ShortArray? = null

        try {
            while (running.get()) {
                val read = try {
                    recorder.read(readBuffer, 0, readBuffer.size)
                } catch (e: Exception) {
                    if (running.get()) onError("待机唤醒读取麦克风失败：${e.message}")
                    break
                }
                if (read <= 0) {
                    if (read < 0) {
                        if (running.get()) onError("待机唤醒读取错误 code=$read")
                        break
                    }
                    continue
                }
                appendPreroll(readBuffer, read)
                val hit = engine.feedWakeWordPcm(readBuffer, read)
                if (hit != null && running.get()) {
                    detectedWord = hit
                    detectedPreroll = snapshotPreroll()
                    running.set(false)
                    break
                }
            }
        } finally {
            running.set(false)
            if (record === recorder) record = null
            releaseRecorder(recorder)
        }

        val word = detectedWord
        val pcm = detectedPreroll
        if (word != null && pcm != null) {
            onDetected(word, pcm)
        }
    }

    private fun releaseRecorder(recorder: AudioRecord?) {
        if (recorder == null) return
        try {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                recorder.stop()
            }
        } catch (_: Exception) {
        }
        try {
            recorder.release()
        } catch (_: Exception) {
        }
    }

    private fun appendPreroll(samples: ShortArray, length: Int) {
        val size = preroll.size
        for (i in 0 until length) {
            preroll[prerollWrite] = samples[i]
            prerollWrite = (prerollWrite + 1) % size
            if (prerollFilled < size) prerollFilled++
        }
    }

    private fun snapshotPreroll(): ShortArray {
        if (prerollFilled == 0) return ShortArray(0)
        val out = ShortArray(prerollFilled)
        val start = (prerollWrite - prerollFilled + preroll.size) % preroll.size
        val first = minOf(prerollFilled, preroll.size - start)
        System.arraycopy(preroll, start, out, 0, first)
        if (first < prerollFilled) {
            System.arraycopy(preroll, 0, out, first, prerollFilled - first)
        }
        prerollFilled = 0
        return out
    }

    companion object {
        private const val SAMPLE_RATE = XiaozhiProtocol.UPLOAD_SAMPLE_RATE
        private const val READ_SAMPLES = SAMPLE_RATE / 50 // 20ms = 320 samples
        private const val PREROLL_SAMPLES = SAMPLE_RATE * 3 / 2 // 1.5s = 24000 samples
    }
}
