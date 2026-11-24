package com.zyyme.workdayalarmclock.voicewake

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.zyyme.workdayalarmclock.wake.WakeWordHost
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 语音唤醒引擎。由宿主用 DexClassLoader 加载并强转成 [WakeWordHost]。
 *
 * 线程模型跟宿主的采集保持一致：
 * - [acceptPcm] 跑在宿主的采集线程上，只做 short->float 转换 + 非阻塞入队，
 *   绝不能在这里跑推理（会把 AudioRecord 拖到溢出丢帧）；
 * - "voicewake-kws" 线程从队列取数据喂给 KeywordSpotter。
 */
class WakeWordEngine : WakeWordHost {

    private val queue = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)

    @Volatile
    private var running = false

    private var worker: Thread? = null
    private var spotter: KeywordSpotter? = null
    private var stream: OnlineStream? = null

    @Volatile
    private var listener: WakeWordHost.Listener? = null

    override fun init(
        assets: AssetManager,
        keywords: String,
        threshold: Float,
        listener: WakeWordHost.Listener?
    ) {
        this.listener = listener
        // 同一实例可能被重复 init：先把采集线程停掉再放旧的原生对象，
        // 否则 worker 手里还攥着已释放的指针，native 层直接崩
        stop()
        releaseModel()
        try {
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM, dither = 0f),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = "$ASSET_DIR/encoder.int8.onnx",
                        decoder = "$ASSET_DIR/decoder.int8.onnx",
                        joiner = "$ASSET_DIR/joiner.int8.onnx"
                    ),
                    tokens = "$ASSET_DIR/tokens.txt",
                    numThreads = 2,
                    modelType = "zipformer2"
                ),
                keywordsFile = "$ASSET_DIR/keywords.txt",
                keywordsScore = 1.0f,
                keywordsThreshold = threshold
            )
            val created = KeywordSpotter(assetManager = assets, config = config)
            spotter = created
            stream = created.createStream(keywords)
        } catch (e: Throwable) {
            this.listener?.onError("唤醒模型加载失败：${e.message}")
        }
    }

    override fun start() {
        if (running) return
        if (spotter == null || stream == null) return
        running = true
        queue.clear()
        worker = Thread({ loop() }, "voicewake-kws").apply { start() }
    }

    override fun stop() {
        running = false
        val current = worker
        worker = null
        current?.interrupt()
        if (current != null && current !== Thread.currentThread()) {
            try {
                current.join(300)
            } catch (ignored: InterruptedException) {
            }
        }
        queue.clear()
    }

    override fun release() {
        stop()
        releaseModel()
    }

    /** 只释放原生对象，不动线程（[init] 重建引擎和 [release] 都会用到） */
    private fun releaseModel() {
        try {
            stream?.release()
        } catch (ignored: Throwable) {
        }
        stream = null
        try {
            spotter?.release()
        } catch (ignored: Throwable) {
        }
        spotter = null
    }

    override fun acceptPcm(samples: ShortArray, length: Int) {
        if (!running || length <= 0) return
        // 宿主会复用这个数组，必须拷出来
        val floats = FloatArray(length)
        for (i in 0 until length) {
            floats[i] = samples[i] / 32768.0f
        }
        if (!queue.offer(floats)) {
            queue.poll()
            queue.offer(floats)
        }
    }

    private fun loop() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
        val spotter = this.spotter ?: return
        val stream = this.stream ?: return
        while (running) {
            val chunk = try {
                queue.poll(100L, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                break
            } ?: continue
            if (!running) break
            try {
                stream.acceptWaveform(chunk, SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val result = spotter.getResult(stream)
                    if (result.keyword.isNotEmpty()) {
                        listener?.onWake(result.keyword)
                        spotter.reset(stream)
                        break
                    }
                }
            } catch (e: Throwable) {
                listener?.onError("唤醒检测出错：${e.message}")
                break
            }
        }
    }

    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val ASSET_DIR = "kws"
        private const val QUEUE_CAPACITY = 64
    }
}
