package com.zyyme.workdayalarmclock.xiaozhi

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SherpaOnnxNative
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 离线语音唤醒（KWS）与本地人声检测（Silero VAD）封装。
 *
 * 线程安全：所有 JNI 句柄都在 [lock] 下初始化、喂帧、重置和释放，
 * 这样待机监听器（XiaozhiWakeWordMonitor）和通话采集器（XiaozhiAudioCapture）
 * 可以安全共享同一个引擎实例而无需重复加载 5MB 模型。
 */
class XiaozhiWakeWordEngine {

    private val lock = Any()

    private var kws: KeywordSpotter? = null
    private var kwsStream: OnlineStream? = null
    private var vad: Vad? = null

    private var tokenSet: Set<String>? = null
    private var loadedKeywordsText: String = ""
    private var loadedThresholdPercent: Int = -1
    private var lastHitAtMs: Long = Long.MIN_VALUE / 4

    private val vadWindow = FloatArray(VAD_WINDOW_SAMPLES)
    private var vadBuffered: Int = 0
    private var lastVadProb: Float? = null

    @Volatile
    var available: Boolean = false
        private set

    @Volatile
    var lastInitError: String = ""
        private set

    fun ensureReady(
        context: Context,
        rawWakeWords: String,
        thresholdPercent: Int,
        needKws: Boolean,
        needVad: Boolean,
        log: (String) -> Unit
    ): Boolean {
        synchronized(lock) {
            if (!needKws && !needVad) {
                return true
            }
            if (Build.VERSION.SDK_INT < 21) {
                lastInitError = "系统版本低于 Android 5.0，跳过离线唤醒/VAD"
                available = false
                return false
            }
            if (!SherpaOnnxNative.loadLibrary()) {
                lastInitError = "离线语音库加载失败：${SherpaOnnxNative.lastError}"
                log(lastInitError)
                available = false
                return false
            }

            if (needKws) {
                val ok = ensureKwsLocked(context, rawWakeWords, thresholdPercent, log)
                if (!ok) {
                    available = false
                    return false
                }
            }

            if (needVad && vad == null) {
                try {
                    val vadConfig = VadModelConfig(
                        sileroVadModelConfig = SileroVadModelConfig(
                            model = "$ASSET_DIR/$VAD_FILE",
                            threshold = 0.5f,
                            minSilenceDuration = 0.25f,
                            minSpeechDuration = 0.10f,
                            windowSize = VAD_WINDOW_SAMPLES,
                            maxSpeechDuration = 5.0f
                        ),
                        sampleRate = SAMPLE_RATE,
                        numThreads = 1,
                        provider = "cpu",
                        debug = false
                    )
                    vad = Vad(context.assets, vadConfig)
                    vadBuffered = 0
                    lastVadProb = null
                    log("本地 Silero VAD 已就绪")
                } catch (t: Throwable) {
                    vad = null
                    log("Silero VAD 初始化失败（将退化为能量检测）：${t.message}")
                }
            }

            available = !needKws || (kws != null && kwsStream != null)
            return available
        }
    }

    private fun ensureKwsLocked(
        context: Context,
        rawWakeWords: String,
        thresholdPercent: Int,
        log: (String) -> Unit
    ): Boolean {
        val clampedThreshold = XiaozhiWakeWordConfig.clampThresholdPercent(thresholdPercent)
        val tokens = loadTokenSetLocked(context)
        val built = XiaozhiWakeWordConfig.buildKeywordsText(rawWakeWords, tokens)
        for (warning in built.warnings) {
            log(warning)
        }

        if (kws != null && kwsStream != null &&
            loadedKeywordsText == built.keywordsText &&
            loadedThresholdPercent == clampedThreshold
        ) {
            return true
        }

        releaseKwsLocked()

        return try {
            val kwsDir = prepareKwsFilesLocked(context)
            val keywordsFile = File(kwsDir, KEYWORDS_FILE)
            keywordsFile.writeText(built.keywordsText, Charsets.UTF_8)

            val modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = File(kwsDir, ENCODER_FILE).absolutePath,
                    decoder = File(kwsDir, DECODER_FILE).absolutePath,
                    joiner = File(kwsDir, JOINER_FILE).absolutePath
                ),
                tokens = File(kwsDir, TOKENS_FILE).absolutePath,
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer2",
                debug = false
            )
            val config = KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80, dither = 0.0f),
                modelConfig = modelConfig,
                maxActivePaths = 4,
                keywordsFile = keywordsFile.absolutePath,
                keywordsScore = 1.5f,
                keywordsThreshold = XiaozhiWakeWordConfig.thresholdFloat(clampedThreshold),
                numTrailingBlanks = 2
            )

            val spotter = KeywordSpotter(assetManager = null, config = config)
            val stream = spotter.createStream("")
            kws = spotter
            kwsStream = stream
            loadedKeywordsText = built.keywordsText
            loadedThresholdPercent = clampedThreshold
            lastInitError = ""
            log("离线唤醒词已加载：${built.activeLabels.joinToString("、")}（阈值 ${clampedThreshold}%）")
            true
        } catch (t: Throwable) {
            releaseKwsLocked()
            lastInitError = "唤醒引擎初始化失败：${t.message}"
            log(lastInitError)
            false
        }
    }

    private fun loadTokenSetLocked(context: Context): Set<String>? {
        tokenSet?.let { return it }
        return try {
            val text = context.assets.open("$ASSET_DIR/$TOKENS_FILE").bufferedReader().use { it.readText() }
            val parsed = XiaozhiWakeWordConfig.parseTokenSet(text)
            tokenSet = parsed
            parsed
        } catch (_: Throwable) {
            null
        }
    }

    private fun prepareKwsFilesLocked(context: Context): File {
        val dir = File(context.filesDir, ASSET_DIR)
        if (!dir.exists()) dir.mkdirs()
        val marker = File(dir, MARKER_FILE)
        val required = listOf(ENCODER_FILE, DECODER_FILE, JOINER_FILE, TOKENS_FILE)
        val alreadyCopied = marker.exists() &&
            marker.readText() == MODEL_MARKER_VERSION &&
            required.all { File(dir, it).let { f -> f.exists() && f.length() > 0L } }
        if (alreadyCopied) return dir

        val assets = context.assets
        for (name in required) {
            val tmp = File(dir, "$name.tmp")
            val target = File(dir, name)
            assets.open("$ASSET_DIR/$name").use { input ->
                tmp.outputStream().use { output ->
                    input.copyTo(output, bufferSize = 16 * 1024)
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
        marker.writeText(MODEL_MARKER_VERSION, Charsets.UTF_8)
        return dir
    }

    /**
     * 喂入 16kHz PCM16 样本并做一次唤醒词解码。命中则返回唤醒词文案，否则返回 null。
     */
    fun feedWakeWordPcm(
        samples: ShortArray,
        length: Int,
        nowMs: Long = SystemClock.elapsedRealtime()
    ): String? {
        if (length <= 0) return null
        val floats = pcm16ToFloat(samples, length)
        synchronized(lock) {
            val spotter = kws ?: return null
            val stream = kwsStream ?: return null
            return try {
                stream.acceptWaveform(floats, SAMPLE_RATE)
                while (spotter.isReady(stream)) {
                    spotter.decode(stream)
                    val result = spotter.getResult(stream)
                    val hit = result.keyword.trim()
                    if (hit.isNotEmpty()) {
                        spotter.reset(stream)
                        if (nowMs - lastHitAtMs >= HIT_COOLDOWN_MS) {
                            lastHitAtMs = nowMs
                            return hit
                        }
                    }
                }
                null
            } catch (_: Throwable) {
                null
            }
        }
    }

    fun resetWakeWord() {
        synchronized(lock) {
            val spotter = kws ?: return
            val stream = kwsStream ?: return
            try {
                spotter.reset(stream)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * 送入 16kHz PCM16 样本更新 Silero VAD 概率（每满 512 样本推理一次）。
     */
    fun computeVadProb(samples: ShortArray, length: Int): Float? {
        if (length <= 0) return null
        synchronized(lock) {
            val detector = vad ?: return null
            var offset = 0
            while (offset < length) {
                val space = VAD_WINDOW_SAMPLES - vadBuffered
                val take = minOf(space, length - offset)
                for (i in 0 until take) {
                    vadWindow[vadBuffered + i] = samples[offset + i] / 32768.0f
                }
                vadBuffered += take
                offset += take
                if (vadBuffered == VAD_WINDOW_SAMPLES) {
                    vadBuffered = 0
                    try {
                        val prob = detector.compute(vadWindow)
                        if (prob >= 0f) lastVadProb = prob
                    } catch (_: Throwable) {
                    }
                }
            }
            return lastVadProb
        }
    }

    fun resetVad() {
        synchronized(lock) {
            vadBuffered = 0
            lastVadProb = null
            try {
                vad?.reset()
            } catch (_: Throwable) {
            }
        }
    }

    fun release() {
        synchronized(lock) {
            releaseKwsLocked()
            releaseVadLocked()
            available = false
        }
    }

    private fun releaseKwsLocked() {
        try {
            kwsStream?.release()
        } catch (_: Throwable) {
        }
        kwsStream = null
        try {
            kws?.release()
        } catch (_: Throwable) {
        }
        kws = null
        loadedKeywordsText = ""
        loadedThresholdPercent = -1
    }

    private fun releaseVadLocked() {
        vadBuffered = 0
        lastVadProb = null
        try {
            vad?.release()
        } catch (_: Throwable) {
        }
        vad = null
    }

    private fun pcm16ToFloat(samples: ShortArray, length: Int): FloatArray {
        val out = FloatArray(length)
        for (i in 0 until length) {
            out[i] = samples[i] / 32768.0f
        }
        return out
    }

    companion object {
        private const val SAMPLE_RATE = XiaozhiProtocol.UPLOAD_SAMPLE_RATE
        private const val VAD_WINDOW_SAMPLES = 512
        private const val HIT_COOLDOWN_MS = 1200L

        private const val ASSET_DIR = "xiaozhi_kws"
        private const val ENCODER_FILE = "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val DECODER_FILE = "decoder-epoch-12-avg-2-chunk-16-left-64.onnx"
        private const val JOINER_FILE = "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val KEYWORDS_FILE = "keywords.txt"
        private const val VAD_FILE = "silero_vad.onnx"
        private const val MARKER_FILE = ".model_version"
        private const val MODEL_MARKER_VERSION = "wenetspeech-3.3M-2024-01-01-v1"
    }
}
