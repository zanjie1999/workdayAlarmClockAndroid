package com.zyyme.workdayalarmclock.xiaozhi

import android.annotation.TargetApi
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风采集：16kHz / 单声道 / PCM16 -> 60ms 定长帧 -> Opus。
 *
 * 线程模型（老机器上编码和发网络都可能卡几十上百毫秒，必须跟采集分开）：
 * - "xiaozhi-capture"：只做 AudioRecord.read(20ms) + RMS，读到的样本丢进 [segments]；
 * - "xiaozhi-encode"：从 [segments] 取样本 -> 分帧 -> Opus 编码 -> [onPacket]（宿主里直接发 WS）。
 *
 * 队列满时丢**最旧**的一段：语音识别要的是"现在的声音"，丢新数据只会让延迟一路累积。
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
    private var captureThread: Thread? = null
    private var encodeThread: Thread? = null
    private var record: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var encoder: OpusEncoderApi? = null

    private val chunker = PcmFrameChunker(XiaozhiProtocol.UPLOAD_FRAME_SAMPLES)
    private val preroll = ShortArray(SAMPLE_RATE / 2)
    private var prerollWrite = 0
    private var prerollFilled = 0

    /** 采集线程 -> 编码线程的转运队列，一段 = 一次 read（20ms） */
    private var segments = newSegmentQueue()

    @Volatile
    private var recording = false

    // ---- 诊断统计：采集线程写这两个，编码线程只读（volatile 保证不被撕裂） ----
    @Volatile
    private var samplesRead = 0L

    @Volatile
    private var droppedSegments = 0

    // ---- 诊断统计：只在编码线程里读写 ----
    private var lastReportAt = 0L
    private var lastSamples = 0L
    private var lastDropped = 0
    private var encodeCount = 0
    private var encodeTotalUs = 0L
    private var encodeMaxUs = 0L
    private var sendCount = 0
    private var sendTotalUs = 0L
    private var sendMaxUs = 0L

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
        // 至少给 1 秒：系统调度抖动时先攒着，真正抗抖动的是下面的 segments 队列
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
        // 纯 Java 的 Opus，复杂度 2 和 5 在 16kbps 语音上几乎听不出差别，老机器上省一半以上 CPU
        encoder = ConcentusOpusCodec.createEncoder(complexity = ENCODE_COMPLEXITY)
        chunker.reset()
        prerollWrite = 0
        prerollFilled = 0
        segments = newSegmentQueue()
        resetStats()
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
        encodeThread = Thread({ encodeLoop() }, "xiaozhi-encode").apply { start() }
        captureThread = Thread({ captureLoop(recorder) }, "xiaozhi-capture").apply { start() }
        return true
    }

    fun stop() {
        if (!running.getAndSet(false)) return
        recording = false
        try {
            record?.stop()
        } catch (ignored: Exception) {
        }
        // 叫醒编码线程：它可能正卡在 ws.send 上，等不到队列里新数据
        encodeThread?.interrupt()
        val worker = captureThread
        captureThread = null
        encodeThread = null
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

    // ---------------------------------------------------------------- 采集线程

    private fun captureLoop(recorder: AudioRecord) {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val readBuffer = ShortArray(READ_SAMPLES)
        try {
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
                samplesRead += read
                appendPreroll(readBuffer, read)
                onLevel(computeRms(readBuffer, read))
                // 这里只做搬运，编码和发网络都在另一个线程，慢死也卡不住录音
                enqueueSegment(readBuffer, read)
            }
        } finally {
            running.set(false)
            encodeThread?.interrupt()
            try {
                encodeThread?.join(ENCODE_JOIN_MS)
            } catch (ignored: InterruptedException) {
            }
            releaseAll()
        }
    }

    private fun enqueueSegment(samples: ShortArray, length: Int) {
        val segment = ShortArray(length)
        System.arraycopy(samples, 0, segment, 0, length)
        val queue = segments
        if (!queue.offer(segment)) {
            // 编码/发网络跟不上：丢最旧的一段，保证送出去的一直是"最近的语音"
            queue.poll()
            droppedSegments++
            queue.offer(segment)
        }
    }

    // ---------------------------------------------------------------- 编码线程

    private fun encodeLoop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        while (running.get()) {
            val segment = try {
                segments.poll(WORKER_POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            }
            if (segment != null && running.get()) encodeAndSend(segment)
            reportStatsIfDue()
        }
        // 收尾补一条，别把最后这几秒的数据丢掉
        reportStats(force = true)
    }

    private fun encodeAndSend(segment: ShortArray) {
        val enc = encoder ?: return
        for (frame in chunker.append(segment, 0, segment.size)) {
            if (!running.get()) return
            val encodeStart = System.nanoTime()
            val packet = enc.encode(frame, 0, frame.size)
            val encodeUs = (System.nanoTime() - encodeStart) / 1000L
            encodeCount++
            encodeTotalUs += encodeUs
            if (encodeUs > encodeMaxUs) encodeMaxUs = encodeUs
            if (packet == null || packet.isEmpty()) continue
            // 这一步会同步写 socket（Java-WebSocket 1.4.1 的 send 在调用线程上直接写），
            // 所以它只配待在这个线程里，卡住的是它自己
            val sendStart = System.nanoTime()
            onPacket(packet)
            val sendUs = (System.nanoTime() - sendStart) / 1000L
            sendCount++
            sendTotalUs += sendUs
            if (sendUs > sendMaxUs) sendMaxUs = sendUs
        }
    }

    // ---------------------------------------------------------------- 诊断日志

    private fun resetStats() {
        samplesRead = 0L
        droppedSegments = 0
        lastSamples = 0L
        lastDropped = 0
        encodeCount = 0
        encodeTotalUs = 0L
        encodeMaxUs = 0L
        sendCount = 0
        sendTotalUs = 0L
        sendMaxUs = 0L
        lastReportAt = SystemClock.elapsedRealtime()
    }

    private fun reportStatsIfDue() {
        val now = SystemClock.elapsedRealtime()
        // 5 秒一条：日志页只留最后 10000 字符，别把对话顶出去
        if (now - lastReportAt < STATS_INTERVAL_MS) return
        reportStats(force = false, now = now)
    }

    private fun reportStats(force: Boolean, now: Long = SystemClock.elapsedRealtime()) {
        val windowMs = now - lastReportAt
        // 不足 1 秒的尾巴没统计意义（刚汇报完就结束会话时会出现）
        if (windowMs < REPORT_MIN_WINDOW_MS) return
        if (!force && windowMs < STATS_INTERVAL_MS) return
        val read = samplesRead
        val windowSamples = read - lastSamples
        val dropped = droppedSegments - lastDropped
        if (windowSamples <= 0) return
        val expected = windowMs * SAMPLE_RATE / 1000L
        val receivePercent = if (expected > 0) windowSamples * 100 / expected else 100
        val encodeAvgMs = if (encodeCount > 0) encodeTotalUs / encodeCount / 1000L else 0L
        val sendAvgMs = if (sendCount > 0) sendTotalUs / sendCount / 1000L else 0L
        log(
            "采集 ${windowMs / 1000}s 实收率${receivePercent}%" +
                    " | 编码 avg${encodeAvgMs}ms max${encodeMaxUs / 1000L}ms" +
                    " | 发送 avg${sendAvgMs}ms max${sendMaxUs / 1000L}ms" +
                    " | 丢${dropped}段"
        )
        lastReportAt = now
        lastSamples = read
        lastDropped = droppedSegments
        encodeCount = 0
        encodeTotalUs = 0L
        encodeMaxUs = 0L
        sendCount = 0
        sendTotalUs = 0L
        sendMaxUs = 0L
    }

    // ---------------------------------------------------------------- 资源

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

        /** 转运队列容量：64 段 * 20ms ≈ 1.3 秒的蓄水池 */
        private const val SEGMENT_QUEUE_CAPACITY = 64

        /** 编码线程等新数据的超时，用来定期检查 running */
        private const val WORKER_POLL_MS = 100L

        /** 停录时最多等编码线程这么久（它可能正卡在 ws.send 上） */
        private const val ENCODE_JOIN_MS = 500L

        /** 诊断日志间隔 */
        private const val STATS_INTERVAL_MS = 5000L

        /** 少于这个时长的统计窗口直接不打印 */
        private const val REPORT_MIN_WINDOW_MS = 1000L

        /** Opus 编码复杂度：纯 Java 实现，老机器上 2 比 5 省一半以上 CPU */
        private const val ENCODE_COMPLEXITY = 2

        private fun newSegmentQueue() = ArrayBlockingQueue<ShortArray>(SEGMENT_QUEUE_CAPACITY)
    }
}
