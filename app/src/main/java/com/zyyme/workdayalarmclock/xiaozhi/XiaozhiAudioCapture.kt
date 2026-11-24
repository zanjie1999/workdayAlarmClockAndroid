package com.zyyme.workdayalarmclock.xiaozhi

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 麦克风采集：16kHz / 单声道 / PCM16 -> 60ms 定长帧 -> Opus。
 *
 * 采集音源由 [denoise] 决定：开 = `VOICE_COMMUNICATION`（配合宿主侧 `MODE_IN_COMMUNICATION`
 * 才生效，回声消除/降噪/AGC 全部交给 HAL），关 = `MIC`（裸麦）。
 * 这里**不额外挂 AudioEffect**——叠在 HAL 的处理上只会把声音削过头。
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
    /** 降噪开关：开走语音通话链路（HAL 出 AEC/NS/AGC），关走裸麦 `MIC` */
    private val denoise: Boolean = false,
    onPacket: (ByteArray) -> Unit,
    private val onLevel: (Double) -> Unit = {},
    private val onError: (String) -> Unit = {},
    private val log: (String) -> Unit = {},
    /**
     * 唤醒使用的 PCM 扇出。**跑在采集线程上**，实现方只能做拷贝+入队，
     * 任何耗时操作都会让 AudioRecord 溢出丢帧。
     */
    private val onPcm: ((ShortArray, Int) -> Unit)? = null
) {

    /**
     * 上行帧的出口。做成可变是为了「说话打断」：THINKING/SPEAKING 期间把编码管线摘掉，
     * 播报中要打断时再挂回来，全程不碰 AudioRecord（不然预滚缓冲就没了）。
     */
    @Volatile
    private var packetSink: (ByteArray) -> Unit = onPacket

    private val running = AtomicBoolean(false)

    /**
     * 采集音源。试过的顺序：
     * - `MIC`：裸麦，HAL 不做前处理，AEC/NS亲测啥效果没有，还不如关了
     * - `CAMCORDER`：录像链路，部分设备上根本出不了声音，弃用
     * - `VOICE_RECOGNITION`：声音不知道为什么小到嗨那样
     * - `VOICE_COMMUNICATION`：效果好巨好，音质差
     */
    private val audioSource = if (denoise) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION
    } else {
        MediaRecorder.AudioSource.MIC
    }

    private val audioSourceName = if (denoise) "VOICE_COMMUNICATION" else "MIC"

    private var captureThread: Thread? = null
    private var encodeThread: Thread? = null
    private var record: AudioRecord? = null
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

    /**
     * @param encode false 表示只录音不编码（待机监听唤醒词用），
     *               这样不会白白跑 Opus 和上行队列。
     */
    fun start(encode: Boolean = true): Boolean {
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
                audioSource,
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
        prerollWrite = 0
        prerollFilled = 0
        segments = newSegmentQueue()
        // 不挂 AudioEffect：VOICE_COMMUNICATION 音频源 + 通话模式已经带 HAL 的 AEC/NS/AGC，
        // 再叠一层 NoiseSuppressor 只会把声音削得更狠
        log("录音参数 音源=${audioSourceName} 音效=${if (denoise) "HAL 内置" else "无"}")
        try {
            recorder.startRecording()
        } catch (e: Exception) {
            releaseAll()
            running.set(false)
            onError("启动录音失败：${e.message}")
            return false
        }
        recording = true
        if (encode) {
            // 编码器整个实例生命周期只建一个：摘下/挂回只动编码线程，不碰它，
            // 免得「线程还卡在 ws.send 上、编码器已经被 release」这种竞态
            encoder = ConcentusOpusCodec.createEncoder(complexity = ENCODE_COMPLEXITY)
            startEncoderChain()
        }
        captureThread = Thread({ captureLoop(recorder) }, "xiaozhi-capture").apply { start() }
        return true
    }

    // ---------------------------------------------------------------- 编码管线挂/摘

    /**
     * 挂上编码管线（录音已经开着时用）。`onPacket` 会替换掉构造时传的那个。
     * 队列先清空：摘下期间积的旧段已经没意义了。
     */
    fun attachEncoder(onPacket: (ByteArray) -> Unit) {
        if (!running.get()) return
        packetSink = onPacket
        if (encodeThread != null) {
            // 已经挂着，只换出口
            return
        }
        clearSegments()
        if (encoder == null) {
            // 待机监听那个实例没建编码器，会话要复用它时现补一个
            encoder = ConcentusOpusCodec.createEncoder(complexity = ENCODE_COMPLEXITY)
        }
        startEncoderChain()
    }

    /**
     * 摘掉编码管线但不关录音：AudioRecord 继续 read、预滚缓冲继续滚、RMS 继续回调。
     * 供「说话打断」用——THINKING/SPEAKING 期间只有这样才能听到播报中的人声。
     */
    fun detachEncoder() {
        if (encodeThread == null) return
        stopEncoderChain()
        clearSegments()
    }

    /**
     * 补一段已经录好的 PCM 进上行。调用前必须 [attachEncoder]，
     * 否则没有编码线程，塞进去也没人处理。
     */
    fun feedPreroll(samples: ShortArray) {
        if (samples.isEmpty()) return
        if (!segments.offer(samples)) {
            segments.poll()
            segments.offer(samples)
        }
    }

    private fun startEncoderChain() {
        chunker.reset()
        resetStats()
        encodeThread = Thread({ encodeLoop() }, "xiaozhi-encode").apply { start() }
    }

    /** 只停线程，不释放编码器（编码器归实例生命周期管，见 releaseAll） */
    private fun stopEncoderChain() {
        val worker = encodeThread
        encodeThread = null
        worker?.interrupt()
        if (worker != null && worker !== Thread.currentThread()) {
            try {
                worker.join(ENCODE_JOIN_MS)
            } catch (ignored: InterruptedException) {
            }
        }
    }

    private fun clearSegments() {
        while (segments.poll() != null) {
            // 丢弃摘下期间和切换瞬间的旧数据
        }
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
     * 只取预滚缓冲里**最近 framesBack 个 20ms 帧**的样本，缓冲本身不清空。
     *
     * 给「说话打断」用：环形缓冲里前面绝大部分是 TTS 从喇叭漏进来的回声，
     * 从音量突增那一帧开始取，才只有真正的人声。
     *
     * 只允许在采集线程调用（preroll 的游标只在那个线程里动）。
     */
    fun drainPrerollFrom(framesBack: Int): ShortArray {
        if (prerollFilled == 0 || framesBack <= 0) return ShortArray(0)
        val want = framesBack.toLong() * READ_SAMPLES
        val length = if (want > prerollFilled) prerollFilled else want.toInt()
        if (length <= 0) return ShortArray(0)
        val out = ShortArray(length)
        val size = preroll.size
        val start = (prerollWrite - length + size) % size
        val first = minOf(length, size - start)
        System.arraycopy(preroll, start, out, 0, first)
        if (first < length) {
            System.arraycopy(preroll, 0, out, first, length - first)
        }
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
                // 唤醒监听：只转发引用，拷贝在接收方自己做
                onPcm?.invoke(readBuffer, read)
                // 这里只做搬运，编码和发网络都在另一个线程，慢死也卡不住录音。
                // 待机监听那个实例没建编码器，就别白拷贝了。
                if (encoder != null) enqueueSegment(readBuffer, read)
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
            packetSink(packet)
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
