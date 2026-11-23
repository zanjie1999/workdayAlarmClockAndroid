package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager
import android.os.Build

/**
 * Sherpa-ONNX v2.0.3 离线唤醒（KWS）与语音活动检测（VAD）最小 JNI 绑定。
 *
 * 这里不直接引用 AAR，是为了保住 minSdk 14 和 Kotlin 1.6.21 编译兼容；
 * 运行时由 [SherpaOnnxNative.loadLibrary] 在 API 21+ 安全加载 native 库。
 */
object SherpaOnnxNative {

    @Volatile
    private var attempted = false

    @Volatile
    private var available = false

    @Volatile
    var lastError: String = ""
        private set

    @Synchronized
    fun loadLibrary(): Boolean {
        if (available) return true
        if (attempted) return false
        attempted = true
        if (Build.VERSION.SDK_INT < 21) {
            lastError = "Android 版本低于 5.0（API ${Build.VERSION.SDK_INT}），跳过加载 Sherpa-ONNX"
            return false
        }
        return try {
            // Android 5.0/5.1 的 linker 不会自动在应用 lib 目录递归找 DT_NEEDED，先显式 load 依赖库
            try {
                System.loadLibrary("onnxruntime")
            } catch (_: Throwable) {
            }
            System.loadLibrary("sherpa-onnx-jni")
            available = true
            lastError = ""
            true
        } catch (t: Throwable) {
            available = false
            lastError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    fun isAvailable(): Boolean = available
}

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

data class QnnConfig(
    var backendLib: String = "",
    var contextBinary: String = "",
    var systemLib: String = "",
    var htpPerfMode: Boolean = false
)

data class OnlineTransducerModelConfig(
    var encoder: String = "",
    var decoder: String = "",
    var joiner: String = "",
    var qnnConfig: QnnConfig = QnnConfig()
)

data class OnlineParaformerModelConfig(
    var encoder: String = "",
    var decoder: String = ""
)

data class OnlineZipformer2CtcModelConfig(
    var model: String = ""
)

data class OnlineNeMoCtcModelConfig(
    var model: String = ""
)

data class OnlineToneCtcModelConfig(
    var model: String = ""
)

data class OnlineModelConfig(
    var transducer: OnlineTransducerModelConfig = OnlineTransducerModelConfig(),
    var paraformer: OnlineParaformerModelConfig = OnlineParaformerModelConfig(),
    var zipformer2Ctc: OnlineZipformer2CtcModelConfig = OnlineZipformer2CtcModelConfig(),
    var neMoCtc: OnlineNeMoCtcModelConfig = OnlineNeMoCtcModelConfig(),
    var toneCtc: OnlineToneCtcModelConfig = OnlineToneCtcModelConfig(),
    var tokens: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
    var modelType: String = "",
    var modelingUnit: String = "",
    var bpeVocab: String = ""
)

class OnlineStream(var ptr: Long = 0) {
    init {
        require(ptr != 0L) { "Failed to create native OnlineStream" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        if (ptr != 0L) acceptWaveform(ptr, samples, sampleRate)
    }

    fun inputFinished() {
        if (ptr != 0L) inputFinished(ptr)
    }

    fun setOption(key: String, value: String) {
        if (ptr != 0L) setOption(ptr, key, value)
    }

    fun getOption(key: String): String = if (ptr != 0L) getOption(ptr, key) else ""

    fun hasOption(key: String): Boolean = if (ptr != 0L) hasOption(ptr, key) else false

    protected fun finalize() {
        release()
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun setOption(ptr: Long, key: String, value: String)
    private external fun getOption(ptr: Long, key: String): String
    private external fun hasOption(ptr: Long, key: String): Boolean
    private external fun delete(ptr: Long)
}

data class KeywordSpotterConfig(
    var featConfig: FeatureConfig = FeatureConfig(),
    var modelConfig: OnlineModelConfig = OnlineModelConfig(),
    var maxActivePaths: Int = 4,
    var keywordsFile: String = "",
    var keywordsScore: Float = 1.5f,
    var keywordsThreshold: Float = 0.25f,
    var numTrailingBlanks: Int = 2
)

data class KeywordSpotterResult(
    val keyword: String,
    val tokens: Array<String>,
    val timestamps: FloatArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is KeywordSpotterResult) return false
        return keyword == other.keyword &&
            tokens.contentEquals(other.tokens) &&
            timestamps.contentEquals(other.timestamps)
    }

    override fun hashCode(): Int {
        var result = keyword.hashCode()
        result = 31 * result + tokens.contentHashCode()
        result = 31 * result + timestamps.contentHashCode()
        return result
    }
}

class KeywordSpotter(
    assetManager: AssetManager? = null,
    val config: KeywordSpotterConfig
) {
    private var ptr: Long = 0

    init {
        check(SherpaOnnxNative.loadLibrary()) {
            "Sherpa-ONNX native library unavailable: ${SherpaOnnxNative.lastError}"
        }
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) {
            "Invalid KeywordSpotterConfig: failed to create native KeywordSpotter"
        }
    }

    protected fun finalize() {
        release()
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun createStream(keywords: String = ""): OnlineStream {
        val p = createStream(ptr, keywords)
        return OnlineStream(p)
    }

    fun isReady(stream: OnlineStream): Boolean = isReady(ptr, stream.ptr)

    fun decode(stream: OnlineStream) = decode(ptr, stream.ptr)

    fun reset(stream: OnlineStream) = reset(ptr, stream.ptr)

    fun getResult(stream: OnlineStream): KeywordSpotterResult = getResult(ptr, stream.ptr)

    private external fun delete(ptr: Long)
    private external fun newFromAsset(assetManager: AssetManager, config: KeywordSpotterConfig): Long
    private external fun newFromFile(config: KeywordSpotterConfig): Long
    private external fun createStream(ptr: Long, keywords: String): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun decode(ptr: Long, streamPtr: Long)
    private external fun reset(ptr: Long, streamPtr: Long)
    private external fun getResult(ptr: Long, streamPtr: Long): KeywordSpotterResult
}

data class SileroVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5f,
    var minSilenceDuration: Float = 0.25f,
    var minSpeechDuration: Float = 0.25f,
    var windowSize: Int = 512,
    var maxSpeechDuration: Float = 5.0f
)

data class TenVadModelConfig(
    var model: String = "",
    var threshold: Float = 0.5f,
    var minSilenceDuration: Float = 0.25f,
    var minSpeechDuration: Float = 0.25f,
    var windowSize: Int = 256,
    var maxSpeechDuration: Float = 5.0f
)

data class VadModelConfig(
    var sileroVadModelConfig: SileroVadModelConfig = SileroVadModelConfig(),
    var tenVadModelConfig: TenVadModelConfig = TenVadModelConfig(),
    var sampleRate: Int = 16000,
    var numThreads: Int = 1,
    var provider: String = "cpu",
    var debug: Boolean = false
)

class SpeechSegment(val start: Long, val samples: FloatArray)

class Vad(
    assetManager: AssetManager? = null,
    var config: VadModelConfig
) {
    private var ptr: Long = 0

    init {
        check(SherpaOnnxNative.loadLibrary()) {
            "Sherpa-ONNX native library unavailable: ${SherpaOnnxNative.lastError}"
        }
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
        require(ptr != 0L) {
            "Invalid VadModelConfig: failed to create native Vad"
        }
    }

    protected fun finalize() {
        release()
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0L
        }
    }

    fun compute(samples: FloatArray): Float = compute(ptr, samples)
    fun acceptWaveform(samples: FloatArray) = acceptWaveform(ptr, samples)
    fun empty(): Boolean = empty(ptr)
    fun pop() = pop(ptr)
    fun front(): SpeechSegment = front(ptr)
    fun clear() = clear(ptr)
    fun isSpeechDetected(): Boolean = isSpeechDetected(ptr)
    fun reset() = reset(ptr)
    fun flush() = flush(ptr)

    private external fun delete(ptr: Long)
    private external fun newFromAsset(assetManager: AssetManager, config: VadModelConfig): Long
    private external fun newFromFile(config: VadModelConfig): Long
    private external fun acceptWaveform(ptr: Long, samples: FloatArray)
    private external fun compute(ptr: Long, samples: FloatArray): Float
    private external fun empty(ptr: Long): Boolean
    private external fun pop(ptr: Long)
    private external fun clear(ptr: Long)
    private external fun front(ptr: Long): SpeechSegment
    private external fun isSpeechDetected(ptr: Long): Boolean
    private external fun reset(ptr: Long)
    private external fun flush(ptr: Long)
}
