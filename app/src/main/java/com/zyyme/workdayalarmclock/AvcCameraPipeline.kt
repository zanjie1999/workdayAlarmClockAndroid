package com.zyyme.workdayalarmclock

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.util.Size
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.floor
import kotlin.math.max
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
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        fun cameraCount(context: Context): Int {
            return try {
                val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
                manager.cameraIdList.size
            } catch (_: Exception) {
                0
            }
        }
    }

    private data class Selection(
        val cameraId: String,
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
    private val codecConfigLock = Any()

    private var codecConfig = ByteArray(0)
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

    override val description: String
        get() = "H.264 ${selectedWidth}x${selectedHeight} ${selectedFps}fps"

    @SuppressLint("MissingPermission")
    override fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        try {
            cameraThread = HandlerThread("camera-avc-${key.cameraIndex}").apply { start() }
            cameraHandler = Handler(cameraThread!!.looper)

            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec = encoder
            val selection = selectConfiguration(cameraManager, encoder)
                ?: throw IllegalStateException("没有可用的H.264输出规格")
            selectedWidth = selection.size.width
            selectedHeight = selection.size.height
            selectedFps = selection.fps

            val mediaFormat = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                selectedWidth,
                selectedHeight
            ).apply {
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
                )
                setInteger(MediaFormat.KEY_BIT_RATE, max(128_000, selectedWidth * selectedHeight * 2))
                setInteger(MediaFormat.KEY_FRAME_RATE, selectedFps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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
        val cameraIds = cameraManager.cameraIdList
        val cameraId = cameraIds.getOrNull(key.cameraIndex) ?: return null
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return null
        val sizes = streamMap.getOutputSizes(MediaCodec::class.java)?.toList().orEmpty()
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

        val preferredSizes = usableSizes.filter { size ->
            if (size.width > MAX_PREFERRED_WIDTH) return@filter false
            if (targetAeRange == null) return@filter false
            val cameraDuration = try {
                streamMap.getOutputMinFrameDuration(MediaCodec::class.java, size)
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
        val chosenSize = largestSize(
            if (preferredSizes.isNotEmpty()) preferredSizes else usableSizes
        )

        val cameraDuration = try {
            streamMap.getOutputMinFrameDuration(MediaCodec::class.java, chosenSize)
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
        return Selection(cameraId, chosenSize, actualFps, aeRange)
    }

    private fun largestSize(sizes: List<Size>): Size {
        return sizes.maxWithOrNull(
            compareBy<Size> { it.width.toLong() * it.height }
                .thenBy { it.width }
        )!!
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
                    openedCamera.createCaptureSession(
                        listOf(surface),
                        captureSessionCallback(openedCamera, surface, selection),
                        cameraHandler
                    )
                } catch (e: CameraAccessException) {
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
                            android.hardware.camera2.CaptureRequest.CONTROL_MODE,
                            android.hardware.camera2.CaptureRequest.CONTROL_MODE_AUTO
                        )
                        selection.aeRange?.let {
                            set(
                                android.hardware.camera2.CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
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
                                    synchronized(codecConfigLock) {
                                        codecConfig = normalized
                                    }
                                } else {
                                    val keyFrame = bufferInfo.flags and
                                        MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 ||
                                        containsIdr(normalized)
                                    frameHub.publish(normalized, keyFrame)
                                }
                            }
                            encoder.releaseOutputBuffer(outputIndex, false)
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
        synchronized(codecConfigLock) {
            codecConfig = output.toByteArray()
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

    override fun codecConfig(): ByteArray {
        return synchronized(codecConfigLock) { codecConfig.copyOf() }
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
    }

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        frameHub.close()
        resolveStart(false)

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
