package com.zyyme.workdayalarmclock

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

@Suppress("DEPRECATION")
internal class MjpegCameraPipeline(
    override val key: CameraStreamKey,
    private val log: (String) -> Unit,
    private val onLuma: ((Int) -> Unit)? = null
) : CameraStreamPipeline {
    companion object {
        private const val TARGET_FPS = 30_000
        private const val MAX_PREFERRED_WIDTH = 2048
        private const val JPEG_QUALITY = 80
        private const val MIN_JPEG_QUALITY = 50
    }

    private val frameHub = CameraFrameHub(maxPackets = 1, keepLatestOnly = true)
    private val frameQueue = ArrayBlockingQueue<ByteArray>(2)
    private val running = AtomicBoolean(false)
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var encoderThread: Thread? = null
    private var camera: Camera? = null
    private var previewTexture: SurfaceTexture? = null
    private var frameWidth = 0
    private var frameHeight = 0
    private var selectedFps = 0
    private var lastLumaAt = 0L

    override val description: String
        get() = "MJPEG ${frameWidth}x${frameHeight} ${selectedFps}fps"

    override fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true

        val started = CountDownLatch(1)
        val success = AtomicBoolean(false)
        cameraThread = HandlerThread("camera-mjpeg-${key.cameraIndex}").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)

        cameraHandler?.post {
            try {
                val openedCamera = Camera.open(key.cameraIndex)
                camera = openedCamera
                val parameters = openedCamera.parameters
                val previewFormats = parameters.supportedPreviewFormats.orEmpty()
                if (!previewFormats.contains(ImageFormat.NV21)) {
                    throw IllegalStateException("摄像头不支持NV21预览")
                }

                val selection = selectConfiguration(parameters)
                    ?: throw IllegalStateException("摄像头没有可用预览规格")
                frameWidth = selection.size.width
                frameHeight = selection.size.height
                selectedFps = selection.outputFps
                startEncoderThread()

                parameters.previewFormat = ImageFormat.NV21
                parameters.setPreviewSize(frameWidth, frameHeight)
                selection.fpsRange?.let {
                    parameters.setPreviewFpsRange(it[0], it[1])
                }
                openedCamera.parameters = parameters

                val texture = SurfaceTexture(10)
                previewTexture = texture
                openedCamera.setPreviewTexture(texture)
                openedCamera.setPreviewCallbackWithBuffer { data, sourceCamera ->
                    val now = SystemClock.elapsedRealtime()
                    if (onLuma != null && now - lastLumaAt >= 500L) {
                        lastLumaAt = now
                        onLuma.invoke(highlightLuma(data))
                    }
                    if (!running.get() || !frameQueue.offer(data)) {
                        if (running.get()) sourceCamera.addCallbackBuffer(data)
                    }
                }

                val bufferSize = frameWidth * frameHeight *
                    ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
                repeat(3) {
                    openedCamera.addCallbackBuffer(ByteArray(bufferSize))
                }
                openedCamera.startPreview()
                success.set(true)
                log("摄像头流已准备：$description")
            } catch (e: Exception) {
                log("MJPEG摄像头启动失败：${e.message}")
                releaseCamera()
            } finally {
                started.countDown()
            }
        }

        val completed = try {
            started.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            false
        }
        if (!completed || !success.get()) {
            stop()
            return false
        }
        return true
    }

    private fun startEncoderThread() {
        encoderThread = Thread({
            val output = ByteArrayOutputStream(frameWidth * frameHeight / 4)
            var jpegQuality = JPEG_QUALITY
            var slowFrames = 0
            var fastFrames = 0
            while (running.get()) {
                val data = try {
                    frameQueue.poll(500, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue

                try {
                    output.reset()
                    val encodeStartedAt = SystemClock.elapsedRealtime()
                    val image = YuvImage(
                        data,
                        ImageFormat.NV21,
                        frameWidth,
                        frameHeight,
                        null
                    )
                    if (image.compressToJpeg(
                            Rect(0, 0, frameWidth, frameHeight),
                            jpegQuality,
                            output
                        )
                    ) {
                        frameHub.publish(output.toByteArray())
                    }
                    val encodeMillis = SystemClock.elapsedRealtime() - encodeStartedAt
                    if (encodeMillis > 100L) {
                        slowFrames++
                        fastFrames = 0
                        if (slowFrames >= 3 && jpegQuality > MIN_JPEG_QUALITY) {
                            jpegQuality = (jpegQuality - 5).coerceAtLeast(MIN_JPEG_QUALITY)
                            slowFrames = 0
                            log("MJPEG编码较慢，JPEG质量降为$jpegQuality")
                        }
                    } else if (encodeMillis < 60L) {
                        fastFrames++
                        slowFrames = 0
                        if (fastFrames >= 30 && jpegQuality < JPEG_QUALITY) {
                            jpegQuality++
                            fastFrames = 0
                        }
                    } else {
                        slowFrames = 0
                        fastFrames = 0
                    }
                } catch (e: Exception) {
                    if (running.get()) log("MJPEG帧编码失败：${e.message}")
                } finally {
                    cameraHandler?.post {
                        if (running.get()) camera?.addCallbackBuffer(data)
                    }
                }
            }
        }, "camera-mjpeg-encoder-${key.cameraIndex}").apply { start() }
    }

    private data class Selection(
        val size: Camera.Size,
        val fpsRange: IntArray?,
        val outputFps: Int
    )

    private fun selectConfiguration(parameters: Camera.Parameters): Selection? {
        val sizes = parameters.supportedPreviewSizes.orEmpty()
        if (sizes.isEmpty()) return null

        val fpsRanges = parameters.supportedPreviewFpsRange.orEmpty()
        val targetRange = fpsRanges
            .filter { it.size >= 2 && it[0] <= TARGET_FPS && it[1] >= TARGET_FPS }
            .sortedWith(compareByDescending<IntArray> { it[0] }.thenBy { it[1] - it[0] })
            .firstOrNull()

        val resolutionIndex = key.resolutionIndex
        val chosenSize = if (resolutionIndex != null) {
            sortedSizes(sizes).getOrNull(resolutionIndex) ?: return null
        } else {
            val preferredSizes = if (targetRange != null) {
                sizes.filter { it.width <= MAX_PREFERRED_WIDTH }
            } else {
                emptyList()
            }
            largestSize(if (preferredSizes.isNotEmpty()) preferredSizes else sizes)
        }

        val fallbackRange = fpsRanges
            .filter { it.size >= 2 }
            .maxWithOrNull(compareBy<IntArray> { it[1] }.thenBy { it[0] })
        val chosenRange = targetRange ?: fallbackRange
        val outputFps = if (targetRange != null) {
            TARGET_FPS / 1000
        } else {
            min(TARGET_FPS, chosenRange?.get(1) ?: TARGET_FPS) / 1000
        }
        return Selection(chosenSize, chosenRange, outputFps.coerceAtLeast(1))
    }

    private fun largestSize(sizes: List<Camera.Size>): Camera.Size {
        return sortedSizes(sizes).first()
    }

    private fun sortedSizes(sizes: List<Camera.Size>): List<Camera.Size> {
        return sizes.sortedWith(
            compareByDescending<Camera.Size> { it.width.toLong() * it.height }
                .thenByDescending { it.width }
        )
    }

    private fun highlightLuma(data: ByteArray): Int {
        val pixelCount = frameWidth * frameHeight
        if (pixelCount <= 0 || data.isEmpty()) return 0
        val histogram = IntArray(256)
        var count = 0
        var index = 0
        while (index < pixelCount && index < data.size) {
            histogram[data[index].toInt() and 0xff]++
            count++
            index += 32
        }
        if (count == 0) return 0
        val target = ((count * 98) / 100).coerceAtLeast(1)
        var accumulated = 0
        for (value in histogram.indices) {
            accumulated += histogram[value]
            if (accumulated >= target) return value
        }
        return 255
    }

    override fun awaitPacket(afterSequence: Long): CameraStreamPacket? {
        return frameHub.awaitNext(afterSequence)
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        frameHub.close()
        frameQueue.clear()
        encoderThread?.interrupt()

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
        encoderThread = null
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
            previewTexture?.release()
        } catch (_: Exception) {
        }
        previewTexture = null
    }
}
