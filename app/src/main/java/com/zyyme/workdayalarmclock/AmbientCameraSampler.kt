package com.zyyme.workdayalarmclock

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
                val size = parameters.supportedPreviewSizes.orEmpty()
                    .minByOrNull { it.width.toLong() * it.height }
                    ?: throw IllegalStateException("摄像头没有预览规格")
                width = size.width
                height = size.height
                parameters.previewFormat = ImageFormat.NV21
                parameters.setPreviewSize(width, height)
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
                success.set(true)
                if (isFrist) {
                    // 输出刷屏了
                    log("环境亮度采样已启动：${width}x$height")
                    isFrist = false
                }
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
