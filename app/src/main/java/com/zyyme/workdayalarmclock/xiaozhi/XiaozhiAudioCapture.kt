package com.zyyme.workdayalarmclock.xiaozhi

import android.annotation.TargetApi
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风采集：16kHz / 单声道 / PCM16 -> 60ms 定长帧 -> Opus。
 *
 * 每次会话新建一个实例，[stop] 之后不可复用（AudioRecord 与编码器一起释放，避免状态残留）。
 * 500ms 预滚缓冲是为了将来做语音唤醒时能把唤醒前的话带上，现在只保留不发送。
 */
class XiaozhiAudioCapture(
    private val onPacket: (ByteArray) -> Unit,
    private val onLevel: (Double) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val log: (String) -> Unit = {}
) {

    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var encoder: OpusEncoderApi? = null

    private val chunker = PcmFrameChunker(XiaozhiProtocol.UPLOAD_FRAME_SAMPLES)
    private val preroll = ShortArray(SAMPLE_RATE / 2)
    private var prerollWrite = 0
    private var prerollFilled = 0

    @Volatile
    private var recording = false

    val isRecording: Boolean get() = recording

    fun start(): Boolean {
        if (running.getAndSet(true)) return true
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            running.set(false)
            onError("麦克风不可用（缓冲区参数不被支持）")
            return false
        }
        val bufferSize = maxOf(minBuffer * 2, SAMPLE_RATE) // 至少 0.5 秒
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
            onError("创建 AudioRecord 失败：${e.message}")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            running.set(false)
            onError("麦克风被占用（AudioRecord 初始化失败）")
            return false
        }
        record = recorder
        encoder = ConcentusOpusCodec.createEncoder()
        chunker.reset()
        prerollWrite = 0
        prerollFilled = 0
        enableEchoCanceler(recorder.audioSessionId)
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            releaseAll()
            running.set(false)
            onError("启动录音失败：${e.message}")
            return false
        }
        recording = true
        thread = Thread({ loop(recorder) }, "xiaozhi-capture").apply { start() }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        recording = false
        try {
            record?.stop()
        } catch (ignored: Exception) {
        }
        val worker = thread
        thread = null
        // 不 join：采集线程自己会在 read 返回后退出并释放资源，界面线程不该被它拖住
        if (worker == null) releaseAll()
    }

    /**
     * 取出并清空预滚缓冲（唤醒词预留，当前唤醒是按键触发，用不到）。
     */
    fun drainPreroll(): ShortArray {
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

    private fun loop(recorder: AudioRecord) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val readBuffer = ShortArray(READ_SAMPLES)
        while (running.get()) {
            val read = try {
                recorder.read(readBuffer, 0, readBuffer.size)
            } catch (e: Exception) {
                if (running.get()) onError("读取麦克风失败：${e.message}")
                break
            }
            if (read <= 0) {
                if (read < 0) {
                    if (running.get()) onError("麦克风读取错误 code=$read")
                    break
                }
                continue
            }
            appendPreroll(readBuffer, read)
            onLevel(computeRms(readBuffer, read))
            val enc = encoder
            if (enc != null) {
                for (frame in chunker.append(readBuffer, 0, read)) {
                    val packet = enc.encode(frame, 0, frame.size)
                    if (packet != null && packet.isNotEmpty()) onPacket(packet)
                }
            }
        }
        releaseAll()
    }

    private fun releaseAll() {
        recording = false
        try {
            record?.release()
        } catch (ignored: Exception) {
        }
        record = null
        try {
            echoCanceler?.enabled = false
            echoCanceler?.release()
        } catch (ignored: Exception) {
        }
        echoCanceler = null
        encoder?.release()
        encoder = null
    }

    private fun appendPreroll(samples: ShortArray, length: Int) {
        val size = preroll.size
        for (i in 0 until length) {
            preroll[prerollWrite] = samples[i]
            prerollWrite = (prerollWrite + 1) % size
            if (prerollFilled < size) prerollFilled++
        }
    }

    private fun computeRms(samples: ShortArray, length: Int): Double {
        if (length <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until length) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        return Math.sqrt(sum / length)
    }

    @TargetApi(16)
    private fun enableEchoCanceler(sessionId: Int) {
        if (Build.VERSION.SDK_INT < 16) {
            // 小于 4.1 没有 AEC，外放时可能把自己的 TTS 拾回来，靠协议侧 VAD 兜底
            log("无回声消除（API ${Build.VERSION.SDK_INT}），建议戴耳机或调低音量")
            return
        }
        try {
            if (!AcousticEchoCanceler.isAvailable()) {
                log("本机无回声消除，建议戴耳机或调低音量")
                return
            }
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            log("已启用回声消除")
        } catch (e: Exception) {
            log("启用回声消除失败：${e.message}")
        }
    }

    companion object {
        const val SAMPLE_RATE = XiaozhiProtocol.UPLOAD_SAMPLE_RATE

        /** 每次 read 取 20ms，帧切分交给 chunker */
        private const val READ_SAMPLES = SAMPLE_RATE / 50
    }
}
