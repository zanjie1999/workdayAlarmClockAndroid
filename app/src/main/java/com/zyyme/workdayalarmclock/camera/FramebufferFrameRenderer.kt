package com.zyyme.workdayalarmclock.camera

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zyyme.workdayalarmclock.ClockActivity
import com.zyyme.workdayalarmclock.DeskActivity
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
    private var displaySlotGeneration: Long? = null
    private var reusableBitmap: Bitmap? = null

    fun isStreaming(): Boolean = synchronized(lock) { streaming }

    fun setStreaming(active: Boolean) {
        var discardedFrame: JpegFrame? = null
        var discardedBitmap: Bitmap? = null
        val currentGeneration = synchronized(lock) {
            generation++
            streaming = active
            discardedFrame = latestFrame
            latestFrame = null
            discardedBitmap = reusableBitmap
            reusableBitmap = null
            nextSequence = 0L
            lastDecodedSequence = 0L
            displaySlotGeneration = null
            generation
        }
        releaseFrame(discardedFrame)
        FramebufferBitmapReaper.recycle(discardedBitmap)
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
        if (accepted) decodeLatestIfSlotAvailable()
        else FramebufferJpegBufferPool.release(jpeg)
    }

    fun requestLatestFrame() {
        synchronized(lock) {
            val latest = latestFrame ?: return
            if (!streaming) return
            if (lastDecodedSequence >= latest.sequence) {
                lastDecodedSequence = latest.sequence - 1L
            }
        }
        decodeLatestIfSlotAvailable()
    }

    private fun decodeLatestIfSlotAvailable() {
        val request = synchronized(lock) {
            if (!streaming || displaySlotGeneration != null || activeDisplayTarget() == null) {
                return
            }
            val frame = latestFrame ?: return
            if (frame.sequence <= lastDecodedSequence) return
            frame.references++
            lastDecodedSequence = frame.sequence
            displaySlotGeneration = generation
            DecodeRequest(frame, generation)
        }
        decoder.execute { decodeAndDisplay(request) }
    }

    private fun decodeAndDisplay(request: DecodeRequest) {
        val candidate = synchronized(lock) {
            reusableBitmap.also { reusableBitmap = null }
        }
        val bitmap = decodeForDisplay(request.frame.bytes, request.frame.length, candidate)
        releaseFrame(request.frame)
        if (candidate !== bitmap) FramebufferBitmapReaper.recycle(candidate)
        if (bitmap == null) {
            releaseDisplaySlot(request.generation)
            return
        }
        val current = synchronized(lock) {
            streaming && generation == request.generation
        }
        if (!current) {
            FramebufferBitmapReaper.recycle(bitmap)
            releaseDisplaySlot(request.generation)
            return
        }

        val decoded = DecodedFrame(request.generation, bitmap)
        mainHandler.post {
            val stillCurrent = synchronized(lock) {
                streaming && generation == decoded.generation
            }
            if (!stillCurrent) {
                FramebufferBitmapReaper.recycle(bitmap)
                releaseDisplaySlot(decoded.generation)
                return@post
            }

            val target = activeDisplayTarget()
            val previous = when (target) {
                is ClockActivity -> target.showFramebufferFrame(bitmap)
                is DeskActivity -> target.showFramebufferFrame(bitmap)
                else -> null
            }
            if (target == null || target.isFinishing) {
                FramebufferBitmapReaper.recycle(bitmap)
                releaseDisplaySlot(decoded.generation)
            } else {
                FramebufferBitmapReaper.retire(previous) { retired ->
                    recycleOrStoreReusable(retired, decoded.generation)
                    releaseDisplaySlot(decoded.generation)
                }
            }
        }
    }

    private fun recycleOrStoreReusable(bitmap: Bitmap?, bitmapGeneration: Long) {
        if (bitmap == null) return
        var recycle: Bitmap? = null
        synchronized(lock) {
            if (streaming && generation == bitmapGeneration &&
                bitmap.isMutable && !bitmap.isRecycled &&
                bitmap.config == Bitmap.Config.ARGB_8888
            ) {
                recycle = reusableBitmap
                reusableBitmap = bitmap
            } else {
                recycle = bitmap
            }
        }
        FramebufferBitmapReaper.recycle(recycle)
    }

    private fun releaseDisplaySlot(slotGeneration: Long) {
        val hasActiveStream = synchronized(lock) {
            if (displaySlotGeneration != slotGeneration) return
            displaySlotGeneration = null
            streaming
        }
        if (hasActiveStream) decodeLatestIfSlotAvailable()
    }

    private fun releaseFrame(frame: JpegFrame?) {
        if (frame == null) return
        val buffer = synchronized(lock) {
            frame.references--
            if (frame.references == 0) frame.bytes else null
        }
        if (buffer != null) FramebufferJpegBufferPool.release(buffer)
    }

    private fun decodeForDisplay(jpeg: ByteArray, length: Int, reusable: Bitmap?): Bitmap? {
        return try {
            val target = activeDisplayTarget() ?: return null
            val metrics = target.resources.displayMetrics
            val targetWidth = metrics.widthPixels.coerceAtLeast(1)
            val targetHeight = metrics.heightPixels.coerceAtLeast(1)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, length, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Ignoring invalid framebuffer JPEG")
                return null
            }

            val sampleSize = calculateSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                targetWidth,
                targetHeight
            )
            val sampledBounds = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeByteArray(jpeg, 0, length, sampledBounds)

            val candidateMatches = reusable != null &&
                !reusable.isRecycled &&
                reusable.isMutable &&
                reusable.config == Bitmap.Config.ARGB_8888 &&
                reusable.width == sampledBounds.outWidth &&
                reusable.height == sampledBounds.outHeight
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inMutable = true
                if (candidateMatches) inBitmap = reusable
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

    private fun activeDisplayTarget(): Activity? =
        ClockActivity.me?.takeIf { it.isActivityStarted }
            ?: DeskActivity.me?.takeIf { it.isActivityStarted }

    private fun calculateSampleSize(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Int {
        var sample = 1
        while (sourceWidth / (sample * 2) >= width &&
            sourceHeight / (sample * 2) >= height
        ) {
            sample *= 2
        }
        return sample
    }

    private const val TAG = "FramebufferRenderer"
}
