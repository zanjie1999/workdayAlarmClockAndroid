package com.zyyme.workdayalarmclock.camera

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.min

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
internal class AvcCameraPipeline(
    private val context: Context,
    override val key: CameraStreamKey,
    private val log: (String) -> Unit
) : CameraStreamPipeline {
    companion object {
        private const val TARGET_FPS = 30
        private const val MAX_PREFERRED_WIDTH = 2048
        private const val START_TIMEOUT_SECONDS = 10L
        private const val DEFAULT_BITS_PER_PIXEL = 3L
        private const val MIN_BITRATE_BPS = 128_000
        private const val MAX_BITRATE_BPS = 20_000_000
        private const val BITRATE_LOG_INTERVAL_MS = 5_000L
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        private val AVC_LEVEL_NAMES = mapOf(
            MediaCodecInfo.CodecProfileLevel.AVCLevel1 to "1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel1b to "1b",
            MediaCodecInfo.CodecProfileLevel.AVCLevel11 to "1.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel12 to "1.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel13 to "1.3",
            MediaCodecInfo.CodecProfileLevel.AVCLevel2 to "2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel21 to "2.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel22 to "2.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel3 to "3",
            MediaCodecInfo.CodecProfileLevel.AVCLevel31 to "3.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel32 to "3.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel4 to "4",
            MediaCodecInfo.CodecProfileLevel.AVCLevel41 to "4.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel42 to "4.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel5 to "5",
            MediaCodecInfo.CodecProfileLevel.AVCLevel51 to "5.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel52 to "5.2",
            MediaCodecInfo.CodecProfileLevel.AVCLevel6 to "6",
            MediaCodecInfo.CodecProfileLevel.AVCLevel61 to "6.1",
            MediaCodecInfo.CodecProfileLevel.AVCLevel62 to "6.2"
        )

        private data class CameraTarget(
            val logicalCameraId: String,
            val physicalCameraId: String?
        )

        fun cameraCount(context: Context): Int {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                enumerateTargets(manager).size
            } catch (_: Exception) {
                0
            }
        }

        private fun enumerateTargets(manager: CameraManager): List<CameraTarget> {
            return manager.cameraIdList.flatMap { logicalId ->
                val targets = mutableListOf(CameraTarget(logicalId, null))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    try {
                        val characteristics = manager.getCameraCharacteristics(logicalId)
                        val physicalIds = characteristics
                            .getPhysicalCameraIds()
                            .toList()
                            .sorted()
                        physicalIds.forEach { physicalId ->
                            if (physicalId != logicalId) {
                                targets.add(CameraTarget(logicalId, physicalId))
                            }
                        }
                    } catch (_: Exception) {
                        // Keep the logical camera when physical metadata is unavailable.
                    }
                }
                targets
            }
        }
    }

    private data class Selection(
        val cameraId: String,
        val physicalCameraId: String?,
        val size: Size,
        val fps: Int,
        val aeRange: Range<Int>?
    )

    private val frameHub = CameraFrameHub(maxPackets = 120, keepLatestOnly = false)
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private val startResolved = AtomicBoolean(false)
    private val startSucceeded = AtomicBoolean(false)
    private val startLatch = CountDownLatch(1)
    private val codecConfigMonitor = Object()

    private var avcStreamConfig: AvcStreamConfig? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var codec: MediaCodec? = null
    private var codecSurface: Surface? = null
    private var drainThread: Thread? = null
    private var selectedWidth = 0
    private var selectedHeight = 0
    private var selectedFps = 0
    private var configuredBitrateBps = 0
    private var encoderParameterSummary = ""

    override val description: String
        get() = "H.264 ${selectedWidth}x${selectedHeight} ${selectedFps}fps"

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        try {
            cameraThread = HandlerThread("camera-avc-${key.cameraIndex}").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            val selection = selectConfiguration(cameraManager, encoder)
                ?: throw IllegalStateException("没有可用的H.264输出规格")
            selectedWidth = selection.size.width
            selectedHeight = selection.size.height
            selectedFps = selection.fps

            val bitrate = resolveBitrate(encoder)
            encoder = configureEncoder(encoder, bitrate)
            codec = encoder
            log("H.264编码参数：$encoderParameterSummary")
            codecSurface = encoder.createInputSurface()
            encoder.start()
            startDrainThread(encoder)

            cameraManager.openCamera(
                selection.cameraId,
                cameraStateCallback(selection),
                cameraHandler
            )
        } catch (e: Exception) {
            log("H.264摄像头启动失败：${e.message}")
            resolveStart(false)
        }

        val completed = try {
            startLatch.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!completed || !startSucceeded.get()) {
            stop()
            return false
        }
        return true
    }

    private fun selectConfiguration(
        cameraManager: CameraManager,
        encoder: MediaCodec
    ): Selection? {
        val target = enumerateTargets(cameraManager).getOrNull(key.cameraIndex) ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(
            target.physicalCameraId ?: target.logicalCameraId
        )
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return null
        // ImageFormat.PRIVATE is the camera output format used by an encoder input Surface.
        // The integer overload is available on API 21, unlike getOutputSizes(Class), which
        // was added later and would break Android 5.0/5.1 devices at runtime.
        val sizes = streamMap.getOutputSizes(ImageFormat.PRIVATE)?.toList().orEmpty()
        if (sizes.isEmpty()) return null

        val codecCapabilities = encoder.codecInfo.getCapabilitiesForType(
            MediaFormat.MIMETYPE_VIDEO_AVC
        )
        if (!codecCapabilities.colorFormats.contains(
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
        ) return null
        val videoCapabilities = codecCapabilities.videoCapabilities ?: return null
        val aeRanges = characteristics.get(
            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES
        ).orEmpty().toList()
        val targetAeRange = selectFpsRangeContaining(aeRanges, TARGET_FPS)

        val usableSizes = sizes.filter { size ->
            try {
                videoCapabilities.isSizeSupported(size.width, size.height)
            } catch (_: Exception) {
                false
            }
        }
        if (usableSizes.isEmpty()) return null

        val resolutionIndex = key.resolutionIndex
        val chosenSize = if (resolutionIndex != null) {
            sortedSizes(usableSizes).getOrNull(resolutionIndex) ?: return null
        } else {
            val preferredSizes = usableSizes.filter { size ->
                if (size.width > MAX_PREFERRED_WIDTH) return@filter false
                if (targetAeRange == null) return@filter false
                val cameraDuration = try {
                    streamMap.getOutputMinFrameDuration(ImageFormat.PRIVATE, size)
                } catch (_: Exception) {
                    0L
                }
                val cameraSupportsTarget = cameraDuration <= 0L ||
                    cameraDuration <= 1_000_000_000L / TARGET_FPS
                val encoderSupportsTarget = try {
                    videoCapabilities.areSizeAndRateSupported(
                        size.width,
                        size.height,
                        TARGET_FPS.toDouble()
                    )
                } catch (_: Exception) {
                    false
                }
                cameraSupportsTarget && encoderSupportsTarget
            }
            largestSize(if (preferredSizes.isNotEmpty()) preferredSizes else usableSizes)
        }

        val cameraDuration = try {
            streamMap.getOutputMinFrameDuration(ImageFormat.PRIVATE, chosenSize)
        } catch (_: Exception) {
            0L
        }
        val cameraMaxFps = if (cameraDuration > 0L) {
            floor(1_000_000_000.0 / cameraDuration).toInt().coerceAtLeast(1)
        } else {
            TARGET_FPS
        }
        val encoderMaxFps = try {
            floor(
                videoCapabilities.getSupportedFrameRatesFor(
                    chosenSize.width,
                    chosenSize.height
                ).upper
            ).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            TARGET_FPS
        }
        val outputFps = min(TARGET_FPS, min(cameraMaxFps, encoderMaxFps)).coerceAtLeast(1)
        val aeRange = selectFpsRange(aeRanges, outputFps)
        val actualFps = min(outputFps, aeRange?.upper ?: outputFps).coerceAtLeast(1)
        return Selection(
            target.logicalCameraId,
            target.physicalCameraId,
            chosenSize,
            actualFps,
            aeRange
        )
    }

    private fun resolveBitrate(encoder: MediaCodec): Int {
        val requested = key.bitrateBps ?: defaultBitrate(selectedWidth, selectedHeight)
        val range = try {
            encoder.codecInfo
                .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                .videoCapabilities?.bitrateRange
        } catch (_: Exception) {
            null
        }
        val resolved = if (range != null) {
            requested.coerceIn(range.lower, range.upper)
        } else {
            requested
        }
        if (resolved != requested) {
            log("H.264码率 ${requested / 1000} kbps 超出编码器范围，调整为 ${resolved / 1000} kbps")
        }
        configuredBitrateBps = resolved
        return resolved
    }

    private fun defaultBitrate(width: Int, height: Int): Int {
        val bps = width.toLong() * height * DEFAULT_BITS_PER_PIXEL
        return bps.coerceIn(MIN_BITRATE_BPS.toLong(), MAX_BITRATE_BPS.toLong()).toInt()
    }

    private fun configureEncoder(encoder: MediaCodec, bitrate: Int): MediaCodec {
        try {
            encoder.configure(
                createEncoderFormat(encoder, true, bitrate),
                null,
                null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            return encoder
        } catch (e: Exception) {
            log("H.264编码器不接受增强参数，回退默认配置：${e.message}")
            try {
                encoder.release()
            } catch (_: Exception) {
            }
        }
        val fallback = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        fallback.configure(
            createEncoderFormat(fallback, false, bitrate),
            null,
            null,
            MediaCodec.CONFIGURE_FLAG_ENCODE
        )
        return fallback
    }

    private fun createEncoderFormat(
        encoder: MediaCodec,
        tuned: Boolean,
        bitrate: Int
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            selectedWidth,
            selectedHeight
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val tuning = if (tuned) applyTuning(format, encoder, bitrate) else ""
        encoderParameterSummary =
            "H.264 ${selectedWidth}x${selectedHeight} ${selectedFps}fps " +
                "${bitrate / 1000}kbps$tuning"
        return format
    }

    private fun applyTuning(
        format: MediaFormat,
        encoder: MediaCodec,
        bitrate: Int
    ): String {
        val caps = try {
            encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        } catch (_: Exception) {
            return ""
        }
        val suffix = StringBuilder()

        val vbrSupported = try {
            caps.encoderCapabilities?.isBitrateModeSupported(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            ) == true
        } catch (_: Exception) {
            false
        }
        if (vbrSupported) {
            format.setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            suffix.append(" VBR")
        }

        val profileLevel = selectHighProfileLevel(caps, bitrate)
        if (profileLevel != null) {
            format.setInteger(MediaFormat.KEY_PROFILE, profileLevel.first)
            format.setInteger(MediaFormat.KEY_LEVEL, profileLevel.second)
            suffix.append(" High@").append(avcLevelName(profileLevel.second))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            format.setInteger(MediaFormat.KEY_PRIORITY, 0)
            suffix.append(" realtime")
        }
        return suffix.toString()
    }

    private fun selectHighProfileLevel(
        caps: MediaCodecInfo.CodecCapabilities,
        bitrate: Int
    ): Pair<Int, Int>? {
        val combos = try {
            caps.profileLevels
        } catch (_: Exception) {
            null
        } ?: return null
        val high = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        if (combos.none { it.profile == high }) return null
        val required = requiredAvcLevel(bitrate)
        val level = combos
            .filter { it.profile == high && it.level >= required }
            .map { it.level }
            .minOrNull() ?: return null
        return Pair(high, level)
    }

    private fun requiredAvcLevel(bitrate: Int): Int {
        val mbWidth = (selectedWidth + 15) / 16
        val mbHeight = (selectedHeight + 15) / 16
        val frameMacroblocks = mbWidth * mbHeight
        val macroblocksPerSecond = frameMacroblocks.toLong() * selectedFps
        val kbps = bitrate / 1000
        // MaxFS / MaxMBPS / MaxBR (High profile) per H.264 Annex A.
        val limits = arrayOf(
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel31, 3600, 108_000, 14_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel32, 5120, 216_000, 20_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel4, 8192, 245_760, 20_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel41, 8192, 245_760, 50_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel42, 8704, 522_240, 50_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel5, 22_080, 589_824, 135_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel51, 36_864, 983_040, 240_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel52, 36_864, 2_073_600, 240_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel6, 139_264, 4_177_920, 240_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel61, 139_264, 8_355_840, 480_000),
            intArrayOf(MediaCodecInfo.CodecProfileLevel.AVCLevel62, 139_264, 16_711_680, 800_000)
        )
        return limits.firstOrNull {
            frameMacroblocks <= it[1] &&
                macroblocksPerSecond <= it[2] &&
                kbps <= it[3]
        }?.get(0) ?: MediaCodecInfo.CodecProfileLevel.AVCLevel62
    }

    private fun avcLevelName(level: Int): String {
        return AVC_LEVEL_NAMES[level] ?: level.toString()
    }

    private fun largestSize(sizes: List<Size>): Size {
        return sortedSizes(sizes).first()
    }

    private fun sortedSizes(sizes: List<Size>): List<Size> {
        return sizes.sortedWith(
            compareByDescending<Size> { it.width.toLong() * it.height }
                .thenByDescending { it.width }
        )
    }

    private fun selectFpsRange(ranges: List<Range<Int>>, targetFps: Int): Range<Int>? {
        val containing = selectFpsRangeContaining(ranges, targetFps)
        if (containing != null) return containing
        return ranges.maxWithOrNull(compareBy<Range<Int>> { it.upper }.thenBy { it.lower })
    }

    private fun selectFpsRangeContaining(
        ranges: List<Range<Int>>,
        targetFps: Int
    ): Range<Int>? {
        return ranges
            .filter { it.lower <= targetFps && it.upper >= targetFps }
            .sortedWith(compareByDescending<Range<Int>> { it.lower }.thenBy { it.upper - it.lower })
            .firstOrNull()
    }

    private fun cameraStateCallback(selection: Selection): CameraDevice.StateCallback {
        return object : CameraDevice.StateCallback() {
            override fun onOpened(openedCamera: CameraDevice) {
                if (!running.get()) {
                    openedCamera.close()
                    resolveStart(false)
                    return
                }
                cameraDevice = openedCamera
                val surface = codecSurface
                if (surface == null) {
                    resolveStart(false)
                    return
                }
                try {
                    createCaptureSession(openedCamera, surface, selection)
                } catch (e: Exception) {
                    log("创建H.264采集会话失败：${e.message}")
                    resolveStart(false)
                }
            }

            override fun onDisconnected(disconnectedCamera: CameraDevice) {
                disconnectedCamera.close()
                if (cameraDevice === disconnectedCamera) cameraDevice = null
                resolveStart(false)
                closeStreamAfterRuntimeFailure()
            }

            override fun onError(errorCamera: CameraDevice, error: Int) {
                errorCamera.close()
                if (cameraDevice === errorCamera) cameraDevice = null
                log("H.264摄像头错误：$error")
                resolveStart(false)
                closeStreamAfterRuntimeFailure()
            }
        }
    }

    @Suppress("NewApi")
    private fun createCaptureSession(
        openedCamera: CameraDevice,
        surface: Surface,
        selection: Selection
    ) {
        val callback = captureSessionCallback(openedCamera, surface, selection)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            selection.physicalCameraId != null
        ) {
            val output = OutputConfiguration(surface).apply {
                setPhysicalCameraId(selection.physicalCameraId)
            }
            val executor = Executor { command ->
                val handler = cameraHandler
                if (handler != null) handler.post(command) else command.run()
            }
            openedCamera.createCaptureSession(
                SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(output),
                    executor,
                    callback
                )
            )
        } else {
            openedCamera.createCaptureSession(listOf(surface), callback, cameraHandler)
        }
    }

    private fun captureSessionCallback(
        openedCamera: CameraDevice,
        surface: Surface,
        selection: Selection
    ): CameraCaptureSession.StateCallback {
        return object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                if (!running.get()) {
                    session.close()
                    resolveStart(false)
                    return
                }
                try {
                    val request = openedCamera.createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD
                    ).apply {
                        addTarget(surface)
                        set(
                            CaptureRequest.CONTROL_MODE,
                            CaptureRequest.CONTROL_MODE_AUTO
                        )
                        selection.aeRange?.let {
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                it
                            )
                        }
                    }
                    captureSession = session
                    session.setRepeatingRequest(request.build(), null, cameraHandler)
                    log("摄像头流已准备：$description")
                    resolveStart(true)
                } catch (e: Exception) {
                    log("启动H.264采集失败：${e.message}")
                    resolveStart(false)
                }
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.close()
                log("H.264采集会话配置失败")
                resolveStart(false)
            }
        }
    }

    private fun resolveStart(success: Boolean) {
        if (startResolved.compareAndSet(false, true)) {
            startSucceeded.set(success)
            startLatch.countDown()
        }
    }

    private fun startDrainThread(encoder: MediaCodec) {
        drainThread = Thread({
            val bufferInfo = MediaCodec.BufferInfo()
            var windowStartMs = SystemClock.elapsedRealtime()
            var windowBytes = 0L
            var windowFrames = 0
            try {
                while (running.get()) {
                    when (val outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000L)) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            updateCodecConfig(encoder.outputFormat)
                        }
                        else -> if (outputIndex >= 0) {
                            val outputBuffer = encoder.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val bytes = ByteArray(bufferInfo.size)
                                outputBuffer.get(bytes)
                                val normalized = toAnnexB(bytes)
                                val isConfig = bufferInfo.flags and
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (isConfig) {
                                    updateCodecConfig(normalized)
                                } else {
                                    val keyFrame = bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
                                        containsIdr(normalized)
                                    frameHub.publish(
                                        normalized,
                                        keyFrame,
                                        bufferInfo.presentationTimeUs
                                    )
                                    windowBytes += bufferInfo.size
                                    windowFrames++
                                }
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
                            val now = SystemClock.elapsedRealtime()
                            val elapsed = now - windowStartMs
                            if (elapsed >= BITRATE_LOG_INTERVAL_MS) {
                                val kbps = windowBytes * 8_000L / elapsed
                                log(
                                    "H.264实际码率：$kbps kbps（目标 ${configuredBitrateBps / 1000} kbps，" +
                                        "$windowFrames 帧/$elapsed ms）"
                                )
                                windowStartMs = now
                                windowBytes = 0L
                                windowFrames = 0
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) log("H.264编码输出失败：${e.message}")
                closeStreamAfterRuntimeFailure()
            }
        }, "camera-avc-encoder-${key.cameraIndex}").apply { start() }
    }

    private fun updateCodecConfig(format: MediaFormat) {
        val output = ByteArrayOutputStream()
        listOf("csd-0", "csd-1").forEach { name ->
            val buffer = format.getByteBuffer(name) ?: return@forEach
            val duplicate = buffer.duplicate()
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            output.write(toAnnexB(bytes))
        }
        updateCodecConfig(output.toByteArray())
    }

    private fun updateCodecConfig(annexBConfig: ByteArray) {
        val parsed = FragmentedMp4Muxer.Companion.parseAvcConfig(
            annexBConfig,
            selectedWidth,
            selectedHeight,
            selectedFps
        ) ?: return
        synchronized(codecConfigMonitor) {
            avcStreamConfig = parsed
            codecConfigMonitor.notifyAll()
        }
    }

    private fun toAnnexB(data: ByteArray): ByteArray {
        if (data.isEmpty() || hasStartCode(data, 0)) return data

        val output = ByteArrayOutputStream(data.size + 16)
        var offset = 0
        var parsedAny = false
        while (offset + 4 <= data.size) {
            val length = ((data[offset].toInt() and 0xff) shl 24) or
                ((data[offset + 1].toInt() and 0xff) shl 16) or
                ((data[offset + 2].toInt() and 0xff) shl 8) or
                (data[offset + 3].toInt() and 0xff)
            offset += 4
            if (length <= 0 || offset + length > data.size) {
                parsedAny = false
                break
            }
            output.write(START_CODE)
            output.write(data, offset, length)
            offset += length
            parsedAny = true
        }
        if (parsedAny && offset == data.size) return output.toByteArray()

        return START_CODE + data
    }

    private fun hasStartCode(data: ByteArray, offset: Int): Boolean {
        if (offset + 3 >= data.size) return false
        return data[offset] == 0.toByte() && data[offset + 1] == 0.toByte() &&
            (data[offset + 2] == 1.toByte() ||
                (data[offset + 2] == 0.toByte() && data[offset + 3] == 1.toByte()))
    }

    private fun containsIdr(data: ByteArray): Boolean {
        var index = 0
        while (index + 4 < data.size) {
            val startCodeSize = when {
                index + 3 < data.size && data[index] == 0.toByte() &&
                    data[index + 1] == 0.toByte() && data[index + 2] == 1.toByte() -> 3
                index + 4 < data.size && data[index] == 0.toByte() &&
                    data[index + 1] == 0.toByte() && data[index + 2] == 0.toByte() &&
                    data[index + 3] == 1.toByte() -> 4
                else -> 0
            }
            if (startCodeSize > 0 && index + startCodeSize < data.size) {
                if (data[index + startCodeSize].toInt().and(0x1f) == 5) return true
                index += startCodeSize
            } else {
                index++
            }
        }
        return false
    }

    override fun awaitPacket(afterSequence: Long): CameraStreamPacket? {
        return frameHub.awaitNext(afterSequence)
    }

    override fun awaitAvcConfig(timeoutMillis: Long): AvcStreamConfig? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        synchronized(codecConfigMonitor) {
            while (running.get() && avcStreamConfig == null) {
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0L) break
                try {
                    codecConfigMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    break
                }
            }
            return avcStreamConfig
        }
    }

    override fun requestKeyFrame() {
        try {
            codec?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        } catch (_: Exception) {
        }
    }

    private fun closeStreamAfterRuntimeFailure() {
        running.set(false)
        frameHub.close()
        synchronized(codecConfigMonitor) {
            codecConfigMonitor.notifyAll()
        }
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        frameHub.close()
        resolveStart(false)
        synchronized(codecConfigMonitor) {
            codecConfigMonitor.notifyAll()
        }

        try {
            captureSession?.stopRepeating()
        } catch (_: Exception) {
        }
        try {
            captureSession?.close()
        } catch (_: Exception) {
        }
        captureSession = null
        try {
            cameraDevice?.close()
        } catch (_: Exception) {
        }
        cameraDevice = null

        drainThread?.interrupt()
        try {
            drainThread?.join(500L)
        } catch (_: InterruptedException) {
        }
        drainThread = null
        try {
            codec?.stop()
        } catch (_: Exception) {
        }
        try {
            codec?.release()
        } catch (_: Exception) {
        }
        codec = null
        try {
            codecSurface?.release()
        } catch (_: Exception) {
        }
        codecSurface = null

        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
    }
}
