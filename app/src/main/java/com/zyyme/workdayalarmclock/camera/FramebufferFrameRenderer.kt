package com.zyyme.workdayalarmclock.camera

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zyyme.workdayalarmclock.ClockActivity
import com.zyyme.workdayalarmclock.DeskActivity
import java.util.ArrayDeque
import java.util.concurrent.Executors

internal object FramebufferFrameRenderer {
    private class JpegFrame(
        val sequence: Long,
        val bytes: ByteArray,
        val length: Int,
        var references: Int = 1
    )

    private data class DecodeRequest(val frame: JpegFrame, val generation: Long)
    private data class DecodedFrame(val generation: Long, val bitmap: Bitmap, val decodeStartNanos: Long)
    private data class DecodeConfig(
        val sourceWidth: Int,
        val sourceHeight: Int,
        val sampleSize: Int,
        val decodedWidth: Int,
        val decodedHeight: Int
    )

    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "framebuffer-jpeg-decoder").apply { isDaemon = true }
    }

    private var streaming = false
    private var generation = 0L
    private var nextSequence = 0L
    private var lastDecodedSequence = 0L
    private var latestFrame: JpegFrame? = null
    private var decodeScheduled = false
    private var pendingDisplay: DecodedFrame? = null
    private var displayScheduled = false
    private val reusableBitmaps = ArrayDeque<Bitmap>()
    private var decodeConfig: DecodeConfig? = null
    private var fpsWindowStartNanos = System.nanoTime()
    private var fpsSubmitted = 0L
    private var fpsDecoded = 0L
    private var fpsDisplayed = 0L
    private var displayCallCount = 0L
    private var displayCallNanos = 0L
    private var displayMaxNanos = 0L
    private var decodeCallCount = 0L
    private var decodeCallNanos = 0L
    private var decodeMaxNanos = 0L
    private var decodeToDisplayCount = 0L
    private var decodeToDisplayNanos = 0L
    private var decodeToDisplayMaxNanos = 0L
    @Volatile private var fpsLabel = ""

    private val displayLatestFrame = Runnable {
        val frame = synchronized(lock) {
            pendingDisplay.also {
                pendingDisplay = null
                displayScheduled = false
            }
        } ?: return@Runnable

        val stillCurrent = synchronized(lock) {
            streaming && generation == frame.generation
        }
        if (!stillCurrent) {
            FramebufferBitmapReaper.recycle(frame.bitmap)
            return@Runnable
        }

        val target = activeDisplayTarget()
        val displayStartNanos = System.nanoTime()
        val previous = when (target) {
            is ClockActivity -> target.showFramebufferFrame(frame.bitmap)
            is DeskActivity -> target.showFramebufferFrame(frame.bitmap)
            else -> null
        }
        val displayElapsedNanos = System.nanoTime() - displayStartNanos

        if (target == null || target.isFinishing) {
            FramebufferBitmapReaper.recycle(frame.bitmap)
        } else {
            synchronized(lock) {
                fpsDisplayed++
                val decodeToDisplayElapsedNanos = System.nanoTime() - frame.decodeStartNanos
                decodeToDisplayCount++
                decodeToDisplayNanos += decodeToDisplayElapsedNanos
                if (decodeToDisplayElapsedNanos > decodeToDisplayMaxNanos) decodeToDisplayMaxNanos = decodeToDisplayElapsedNanos
                displayCallCount++
                displayCallNanos += displayElapsedNanos
                if (displayElapsedNanos > displayMaxNanos) displayMaxNanos = displayElapsedNanos
                logFpsIfDueLocked()
            }
            FramebufferBitmapReaper.retire(previous) { retired ->
                recycleOrStoreReusable(retired, frame.generation)
            }
        }

        synchronized(lock) {
            streaming && generation == frame.generation
        }.takeIf { it }?.let {
            ensureDecodeScheduled()
        }
    }

    fun isStreaming(): Boolean = synchronized(lock) { streaming }

    fun fpsText(): String = fpsLabel

    fun setStreaming(active: Boolean) {
        var discardedFrame: JpegFrame? = null
        var discardedPendingBitmap: Bitmap? = null
        val discardedReusableBitmaps = ArrayList<Bitmap>()
        val currentGeneration = synchronized(lock) {
            generation++
            streaming = active
            discardedFrame = latestFrame
            latestFrame = null
            discardedPendingBitmap = pendingDisplay?.bitmap
            pendingDisplay = null
            while (reusableBitmaps.isNotEmpty()) {
                discardedReusableBitmaps.add(reusableBitmaps.removeFirst())
            }
            nextSequence = 0L
            lastDecodedSequence = 0L
            decodeConfig = null
            fpsWindowStartNanos = System.nanoTime()
            fpsSubmitted = 0L
            fpsDecoded = 0L
            fpsDisplayed = 0L
            displayCallCount = 0L
            displayCallNanos = 0L
            displayMaxNanos = 0L
            decodeCallCount = 0L
            decodeCallNanos = 0L
            decodeMaxNanos = 0L
            decodeToDisplayCount = 0L
            decodeToDisplayNanos = 0L
            decodeToDisplayMaxNanos = 0L
            generation
        }

        releaseFrame(discardedFrame)
        FramebufferBitmapReaper.recycle(discardedPendingBitmap)
        discardedReusableBitmaps.forEach(FramebufferBitmapReaper::recycle)

        mainHandler.post {
            DeskActivity.me?.setFramebufferStreaming(active)
            if (!active) {
                ClockActivity.me?.hideFramebufferFrame()
                DeskActivity.me?.hideFramebufferFrame()
            }
        }
        Log.d(TAG, "framebuffer stream active=$active generation=$currentGeneration")
    }

    fun submit(jpeg: ByteArray, length: Int) {
        require(length in 1..jpeg.size) { "invalid JPEG length: $length" }
        var displacedFrame: JpegFrame? = null
        val accepted = synchronized(lock) {
            if (!streaming) {
                false
            } else {
                nextSequence++
                fpsSubmitted++
                logFpsIfDueLocked()
                displacedFrame = latestFrame
                latestFrame = JpegFrame(nextSequence, jpeg, length)
                true
            }
        }

        releaseFrame(displacedFrame)
        if (accepted) {
            ensureDecodeScheduled()
        } else {
            FramebufferJpegBufferPool.release(jpeg)
        }
    }

    fun requestLatestFrame() {
        synchronized(lock) {
            val latest = latestFrame ?: return
            if (!streaming) return
            if (lastDecodedSequence >= latest.sequence) {
                lastDecodedSequence = latest.sequence - 1L
            }
        }
        ensureDecodeScheduled()
    }

    private fun ensureDecodeScheduled() {
        val schedule = synchronized(lock) {
            if (!streaming || activeDisplayTarget() == null || pendingDisplay != null) {
                false
            } else if (decodeScheduled) {
                false
            } else {
                decodeScheduled = true
                true
            }
        }
        if (schedule) {
            decoder.execute(::decodeLatestFrames)
        }
    }

    private fun decodeLatestFrames() {
        while (true) {
            val request = synchronized(lock) {
                if (!streaming || activeDisplayTarget() == null) {
                    decodeScheduled = false
                    null
                } else if (pendingDisplay != null) {
                    // Keep at most one decoded frame waiting for the UI. New
                    // JPEGs replace the source frame and are decoded after
                    // the pending bitmap has been displayed.
                    decodeScheduled = false
                    null
                } else {
                    val frame = latestFrame
                    if (frame == null || frame.sequence <= lastDecodedSequence) {
                        decodeScheduled = false
                        null
                    } else {
                        frame.references++
                        lastDecodedSequence = frame.sequence
                        DecodeRequest(frame, generation)
                    }
                }
            } ?: return

            val reusable = synchronized(lock) {
                if (reusableBitmaps.isEmpty()) null else reusableBitmaps.removeFirst()
            }

            val decodeStartNanos = System.nanoTime()
            val bitmap = decodeForDisplay(request.frame.bytes, request.frame.length, reusable)
            val decodeElapsedNanos = System.nanoTime() - decodeStartNanos
            releaseFrame(request.frame)

            synchronized(lock) {
                if (bitmap != null) fpsDecoded++
                decodeCallCount++
                decodeCallNanos += decodeElapsedNanos
                if (decodeElapsedNanos > decodeMaxNanos) decodeMaxNanos = decodeElapsedNanos
                logFpsIfDueLocked()
            }

            if (reusable !== bitmap && reusable != null) {
                FramebufferBitmapReaper.recycle(reusable)
            }

            if (bitmap == null) continue

            val current = synchronized(lock) {
                streaming && generation == request.generation
            }
            if (!current) {
                FramebufferBitmapReaper.recycle(bitmap)
                continue
            }

            var displacedPending: Bitmap? = null
            val scheduleDisplay = synchronized(lock) {
                if (!streaming || generation != request.generation) {
                    false
                } else {
                    displacedPending = pendingDisplay?.bitmap
                    pendingDisplay = DecodedFrame(request.generation, bitmap, decodeStartNanos)
                    if (displayScheduled) {
                        false
                    } else {
                        displayScheduled = true
                        true
                    }
                }
            }

            displacedPending?.let { storeUndisplayedBitmap(it, request.generation) }

            if (!scheduleDisplay) {
                val isPending = synchronized(lock) {
                    pendingDisplay?.bitmap === bitmap
                }
                if (!isPending) {
                    storeUndisplayedBitmap(bitmap, request.generation)
                }
            } else {
                mainHandler.post(displayLatestFrame)
            }
        }
    }

    private fun decodeForDisplay(jpeg: ByteArray, length: Int, reusable: Bitmap?): Bitmap? {
        return try {
            val config = synchronized(lock) { decodeConfig }
                ?: initializeDecodeConfig(jpeg, length)
                ?: return null

            val candidateMatches = reusable != null &&
                !reusable.isRecycled &&
                reusable.isMutable &&
                reusable.config == Bitmap.Config.ARGB_8888 &&
                reusable.width == config.decodedWidth &&
                reusable.height == config.decodedHeight

            val options = BitmapFactory.Options().apply {
                inSampleSize = config.sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                if (candidateMatches) {
                    inBitmap = reusable
                }
            }

            try {
                BitmapFactory.decodeByteArray(jpeg, 0, length, options)
            } catch (e: IllegalArgumentException) {
                if (!candidateMatches) throw e
                Log.d(TAG, "Bitmap reuse rejected; decoding into a fresh bitmap")
                reusable?.recycle()
                options.inBitmap = null
                BitmapFactory.decodeByteArray(jpeg, 0, length, options)
            }
        } catch (e: RuntimeException) {
            Log.e(TAG, "Framebuffer JPEG decode failed", e)
            null
        }
    }

    private fun initializeDecodeConfig(jpeg: ByteArray, length: Int): DecodeConfig? {
        val target = activeDisplayTarget() ?: return null

        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(jpeg, 0, length, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "Ignoring invalid framebuffer JPEG")
            return null
        }

        val metrics = target.resources.displayMetrics
        val sampleSize = calculateSampleSize(
            bounds.outWidth,
            bounds.outHeight,
            metrics.widthPixels.coerceAtLeast(1),
            metrics.heightPixels.coerceAtLeast(1)
        )

        val sampledBounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        BitmapFactory.decodeByteArray(jpeg, 0, length, sampledBounds)
        if (sampledBounds.outWidth <= 0 || sampledBounds.outHeight <= 0) {
            Log.w(TAG, "Unable to determine sampled framebuffer JPEG size")
            return null
        }

        val config = DecodeConfig(
            sourceWidth = bounds.outWidth,
            sourceHeight = bounds.outHeight,
            sampleSize = sampleSize,
            decodedWidth = sampledBounds.outWidth,
            decodedHeight = sampledBounds.outHeight
        )

        synchronized(lock) {
            if (streaming && decodeConfig == null) {
                decodeConfig = config
            }
            return decodeConfig ?: config
        }
    }

    private fun storeUndisplayedBitmap(bitmap: Bitmap, bitmapGeneration: Long) {
        var recycle: Bitmap? = null
        synchronized(lock) {
            if (streaming && generation == bitmapGeneration &&
                bitmap.isMutable && !bitmap.isRecycled &&
                bitmap.config == Bitmap.Config.ARGB_8888
            ) {
                if (reusableBitmaps.size < MAX_REUSABLE_BITMAPS) {
                    reusableBitmaps.addLast(bitmap)
                    return
                }
            }
            recycle = bitmap
        }
        FramebufferBitmapReaper.recycle(recycle)
    }

    private fun recycleOrStoreReusable(bitmap: Bitmap?, bitmapGeneration: Long) {
        if (bitmap == null) return
        storeUndisplayedBitmap(bitmap, bitmapGeneration)
    }

    private fun releaseFrame(frame: JpegFrame?) {
        if (frame == null) return
        val buffer = synchronized(lock) {
            frame.references--
            if (frame.references == 0) frame.bytes else null
        }
        if (buffer != null) {
            FramebufferJpegBufferPool.release(buffer)
        }
    }

    private fun logFpsIfDueLocked() {
        val elapsedNanos = System.nanoTime() - fpsWindowStartNanos
        if (elapsedNanos < 10_000_000_000L) return
        val elapsedSeconds = elapsedNanos / 1_000_000_000.0
        val submitted = fpsSubmitted / elapsedSeconds
        val decoded = fpsDecoded / elapsedSeconds
        val displayed = fpsDisplayed / elapsedSeconds
        val avgDisplayMs = if (displayCallCount == 0L) 0.0 else displayCallNanos / displayCallCount / 1_000_000.0
        val maxDisplayMs = displayMaxNanos / 1_000_000.0
        val avgDecodeMs = if (decodeCallCount == 0L) 0.0 else decodeCallNanos / decodeCallCount / 1_000_000.0
        val maxDecodeMs = decodeMaxNanos / 1_000_000.0
        val avgDecodeToDisplayMs = if (decodeToDisplayCount == 0L) 0.0 else decodeToDisplayNanos / decodeToDisplayCount / 1_000_000.0
        val maxDecodeToDisplayMs = decodeToDisplayMaxNanos / 1_000_000.0
        fpsLabel = "  FPS: ${"%.1f".format(java.util.Locale.US, displayed)}"
        Log.d(TAG, "framebuffer fps: submitted=%.1f decoded=%.1f displayed=%.1f decode=%.2fms max=%.2fms decodeToDisplay=%.2fms max=%.2fms showImageView=%.2fms max=%.2fms".format(java.util.Locale.US, submitted, decoded, displayed, avgDecodeMs, maxDecodeMs, avgDecodeToDisplayMs, maxDecodeToDisplayMs, avgDisplayMs, maxDisplayMs))
        fpsWindowStartNanos = System.nanoTime()
        fpsSubmitted = 0L
        fpsDecoded = 0L
        fpsDisplayed = 0L
        displayCallCount = 0L
        displayCallNanos = 0L
        displayMaxNanos = 0L
        decodeCallCount = 0L
        decodeCallNanos = 0L
        decodeMaxNanos = 0L
        decodeToDisplayCount = 0L
        decodeToDisplayNanos = 0L
        decodeToDisplayMaxNanos = 0L
    }

    private fun activeDisplayTarget(): Activity? =
        ClockActivity.me?.takeIf { it.isActivityStarted }
            ?: DeskActivity.me?.takeIf { it.isActivityStarted }

    private fun calculateSampleSize(
        sourceWidth: Int,
        sourceHeight: Int,
        width: Int,
        height: Int
    ): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= width &&
            sourceHeight / (sample * 2) >= height
        ) {
            sample *= 2
        }
        return sample
    }

    private const val MAX_REUSABLE_BITMAPS = 3
    private const val TAG = "FramebufferRenderer"
}
