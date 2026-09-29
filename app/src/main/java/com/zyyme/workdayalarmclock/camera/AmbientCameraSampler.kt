package com.zyyme.workdayalarmclock.camera

import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Suppress("DEPRECATION")
internal class AmbientCameraSampler(
    private val onLuma: (Int) -> Unit,
    private val onFace: (Boolean) -> Unit,
    private val faceDetectionEnabled: () -> Boolean,
    private val continuousFaceCycleEnabled: () -> Boolean,
    private val log: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var camera: Camera? = null
    private var texture: SurfaceTexture? = null
    private var width = 0
    private var height = 0
    private var warmupUntil = 0L
    private var lastSampleAt = 0L
    private var faceDetectionStarted = false
    private var faceDetectionRestartPending = false
    @Volatile private var faceDetectionStartLogged = false
    private var loggedWidth = 0
    private var loggedHeight = 0

    private var isFrist = true

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        val started = CountDownLatch(1)
        val success = AtomicBoolean(false)
        cameraThread = HandlerThread("camera-ambient").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
        cameraHandler?.post {
            try {
                val index = findFrontCamera() ?: throw IllegalStateException("没有前置摄像头")
                val opened = Camera.open(index)
                camera = opened
                val parameters = opened.parameters
                if (!parameters.supportedPreviewFormats.orEmpty().contains(ImageFormat.NV21)) {
                    throw IllegalStateException("摄像头不支持NV21预览")
                }
                val supportedPreviewSizes = parameters.supportedPreviewSizes.orEmpty()
                val size = supportedPreviewSizes
                    .filter { it.height >= 480 }
                    .minWithOrNull(
                        compareBy<Camera.Size> { it.width.toLong() * it.height }
                            .thenBy { it.width }
                    )
                    ?: supportedPreviewSizes.minByOrNull { it.width.toLong() * it.height }
                    ?: throw IllegalStateException("摄像头没有预览规格")
                width = size.width
                height = size.height
                parameters.previewFormat = ImageFormat.NV21
                parameters.setPreviewSize(width, height)
                // 采样摄像头没有可见预览，限制到 15 FPS 以降低底层人脸检测和 ISP 的 CPU 开销。
                val fpsRanges = parameters.supportedPreviewFpsRange.orEmpty()
                val targetFps = 15 * 1000
                val fpsRange = fpsRanges
                    .filter { it[0] <= targetFps && it[1] >= targetFps }
                    .minByOrNull { it[1] - it[0] }
                    ?: fpsRanges.minByOrNull { kotlin.math.abs(it[1] - targetFps) }
                if (fpsRange != null) parameters.setPreviewFpsRange(fpsRange[0], fpsRange[1])
                opened.parameters = parameters

                val surfaceTexture = SurfaceTexture(11)
                texture = surfaceTexture
                opened.setPreviewTexture(surfaceTexture)
                opened.setPreviewCallbackWithBuffer { data, sourceCamera ->
                    if (!running.get()) return@setPreviewCallbackWithBuffer
                    val now = SystemClock.elapsedRealtime()
                    if (now >= warmupUntil && now - lastSampleAt >= 500L) {
                        lastSampleAt = now
                        onLuma(balancedLuma(data))
                    }
                    sourceCamera.addCallbackBuffer(data)
                }

                val bufferSize = width * height * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
                repeat(2) { opened.addCallbackBuffer(ByteArray(bufferSize)) }
                warmupUntil = SystemClock.elapsedRealtime() + 1_200L
                opened.startPreview()

                if (width != loggedWidth || height != loggedHeight) {
                    log("环境亮度采样已启动：${width}x$height")
                    loggedWidth = width
                    loggedHeight = height
                }

                updateFaceDetection(opened, faceDetectionEnabled())

                success.set(true)
            } catch (e: Exception) {
                log("环境亮度采样启动失败：${e.message}")
                releaseCamera()
            } finally {
                started.countDown()
            }
        }
        val completed = try {
            started.await(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!completed || !success.get()) {
            stop()
            return false
        }
        return true
    }

    fun setFaceDetectionEnabled(enabled: Boolean) {
        cameraHandler?.post {
            val opened = camera ?: return@post
            updateFaceDetection(opened, enabled)
        }
    }

    fun resetFaceDetectionStartLog() {
        faceDetectionStartLogged = false
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val released = CountDownLatch(1)
        val handler = cameraHandler
        if (handler != null) {
            handler.post {
                releaseCamera()
                released.countDown()
            }
            try {
                released.await(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
            }
        } else {
            releaseCamera()
        }
        cameraThread?.quit()
        cameraThread = null
        cameraHandler = null
    }

    private fun findFrontCamera(): Int? {
        val info = Camera.CameraInfo()
        for (index in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(index, info)
            if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) return index
        }
        return null
    }

    private fun aspectRatio(size: Camera.Size): Double {
        val shortSide = minOf(size.width, size.height).toDouble()
        val longSide = maxOf(size.width, size.height).toDouble()
        return if (shortSide == 0.0) 0.0 else longSide / shortSide
    }

    private fun updateFaceDetection(opened: Camera, enabled: Boolean) {
        cameraHandler?.removeCallbacks(faceDetectionRestartRunnable)
        cameraHandler?.removeCallbacks(faceCycleStopRunnable)
        faceDetectionRestartPending = false
        val maxFaces = try { opened.parameters.maxNumDetectedFaces } catch (_: Exception) { 0 }
        if (!enabled) {
            if (faceDetectionStarted) {
                try { opened.stopFaceDetection() } catch (_: Exception) { }
                try { opened.setFaceDetectionListener(null) } catch (_: Exception) { }
                faceDetectionStarted = false
            }
            return
        }
        if (faceDetectionStarted) return
        if (maxFaces <= 0) {
            log("当前摄像头不支持人脸检测")
            return
        }
        try {
            opened.setFaceDetectionListener { faces, _ ->
                if (!running.get()) return@setFaceDetectionListener
                onFace(faces != null && faces.isNotEmpty())
            }
            opened.startFaceDetection()
            faceDetectionStarted = true
            if (!faceDetectionStartLogged) {
                faceDetectionStartLogged = true
                log("人脸检测已启动，最大人脸数：$maxFaces 分辨率：${width}x$height")
            }
            if (continuousFaceCycleEnabled()) {
                cameraHandler?.removeCallbacks(faceCycleStopRunnable)
                cameraHandler?.postDelayed(faceCycleStopRunnable, 3_000L)
            }
        } catch (e: Exception) {
            log("人脸检测启动失败：${e.message}")
        }
    }

    private val faceCycleStopRunnable = Runnable {
        val handler = cameraHandler ?: return@Runnable
        if (!faceDetectionStarted || !continuousFaceCycleEnabled()) return@Runnable
        faceDetectionRestartPending = true
        try { camera?.setFaceDetectionListener(null) } catch (_: Exception) { }
        try { camera?.stopFaceDetection() } catch (_: Exception) { }
        faceDetectionStarted = false
        handler.postDelayed(faceDetectionRestartRunnable, 2_000L)
    }

    private val faceDetectionRestartRunnable = Runnable {
        faceDetectionRestartPending = false
        if (running.get() && faceDetectionEnabled()) {
            camera?.let { updateFaceDetection(it, true) }
        }
    }

    private fun balancedLuma(data: ByteArray): Int {
        val pixelCount = width * height
        if (pixelCount <= 0 || data.isEmpty()) return 0
        val histogram = IntArray(256)
        var sum = 0L
        var count = 0
        var index = 0
        while (index < pixelCount && index < data.size) {
            val value = data[index].toInt() and 0xff
            histogram[value]++
            sum += value
            count++
            index += 32
        }
        if (count == 0) return 0
        var target = (count * 98) / 100
        if (target < 1) target = 1
        var accumulated = 0
        for (value in histogram.indices) {
            accumulated += histogram[value]
            if (accumulated >= target) {
                if (value >= 225) return value
                val average = (sum / count).toInt()
                return (average * 3 + value * 2) / 5
            }
        }
        return 255
    }

    private fun releaseCamera() {
        cameraHandler?.removeCallbacks(faceDetectionRestartRunnable)
        cameraHandler?.removeCallbacks(faceCycleStopRunnable)
        faceDetectionRestartPending = false
        try {
            if (faceDetectionStarted) camera?.stopFaceDetection()
            camera?.setFaceDetectionListener(null)
        } catch (_: Exception) {
        }
        faceDetectionStarted = false
        try {
            camera?.setPreviewCallbackWithBuffer(null)
            camera?.stopPreview()
        } catch (_: Exception) {
        }
        try {
            camera?.release()
        } catch (_: Exception) {
        }
        camera = null
        try {
            texture?.release()
        } catch (_: Exception) {
        }
        texture = null
    }
}
