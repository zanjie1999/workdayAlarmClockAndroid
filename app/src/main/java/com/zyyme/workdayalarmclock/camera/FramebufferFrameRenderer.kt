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
    private data class DecodedFrame(val generation: Long, val bitmap: Bitmap)
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
        val previous = when (target) {
            is ClockActivity -> target.showFramebufferFrame(frame.bitmap)
            is DeskActivity -> target.showFramebufferFrame(frame.bitmap)
            else -> null
        }

        if (target == null || target.isFinishing) {
            FramebufferBitmapReaper.recycle(frame.bitmap)
        } else {
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
            if (!streaming || activeDisplayTarget() == null) {
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

            val bitmap = decodeForDisplay(request.frame.bytes, request.frame.length, reusable)
            releaseFrame(request.frame)

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
                    pendingDisplay = DecodedFrame(request.generation, bitmap)
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
