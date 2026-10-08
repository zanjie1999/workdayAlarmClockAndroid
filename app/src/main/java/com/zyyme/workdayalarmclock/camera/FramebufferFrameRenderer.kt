package com.zyyme.workdayalarmclock.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zyyme.workdayalarmclock.ClockActivity
import com.zyyme.workdayalarmclock.DeskActivity
import java.util.concurrent.Executors

internal object FramebufferFrameRenderer {
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "framebuffer-jpeg-decoder").apply { isDaemon = true }
    }
    private data class DecodedFrame(val bitmap: Bitmap, val generation: Long)

    private var streaming = false
    private var generation = 0L
    private var pendingFrame: ByteArray? = null
    private var pendingBitmap: DecodedFrame? = null
    private var decodeScheduled = false
    private var displayScheduled = false
    private val displayLatestFrame = Runnable {
        val frame = synchronized(lock) {
            pendingBitmap.also {
                pendingBitmap = null
                displayScheduled = false
            }
        } ?: return@Runnable
        val stillCurrent = synchronized(lock) {
            streaming && generation == frame.generation
        }
        if (!stillCurrent) {
            if (!frame.bitmap.isRecycled) frame.bitmap.recycle()
            return@Runnable
        }
        val target = ClockActivity.me?.takeIf { it.isActivityStarted }
            ?: DeskActivity.me?.takeIf { it.isActivityStarted }
        if (target is ClockActivity) {
            target.showFramebufferFrame(frame.bitmap)
        } else if (target is DeskActivity) {
            target.showFramebufferFrame(frame.bitmap)
        } else if (!frame.bitmap.isRecycled) {
            frame.bitmap.recycle()
        }
    }

    fun isStreaming(): Boolean = synchronized(lock) { streaming }

    fun setStreaming(active: Boolean) {
        val (currentGeneration, oldBitmap) = synchronized(lock) {
            generation++
            streaming = active
            pendingFrame = null
            val old = pendingBitmap?.bitmap
            pendingBitmap = null
            generation to old
        }
        oldBitmap?.let { if (!it.isRecycled) it.recycle() }
        mainHandler.post {
            DeskActivity.me?.setFramebufferStreaming(active)
            if (!active) {
                ClockActivity.me?.hideFramebufferFrame()
                DeskActivity.me?.hideFramebufferFrame()
            }
        }
        Log.d(TAG, "framebuffer stream active=$active generation=$currentGeneration")
    }

    fun submit(jpeg: ByteArray) {
        val schedule = synchronized(lock) {
            if (!streaming) return
            pendingFrame = jpeg
            if (decodeScheduled) {
                false
            } else {
                decodeScheduled = true
                true
            }
        }
        if (schedule) decoder.execute(::decodeLatestFrames)
    }

    private fun decodeLatestFrames() {
        while (true) {
            val work = synchronized(lock) {
                if (!streaming) {
                    pendingFrame = null
                    decodeScheduled = false
                    null
                } else {
                    val frame = pendingFrame
                    if (frame == null) {
                        decodeScheduled = false
                        null
                    } else {
                        pendingFrame = null
                        frame to generation
                    }
                }
            } ?: return
            val (frame, frameGeneration) = work

            val bitmap = decodeForDisplay(frame)
            if (bitmap == null) continue
            val shouldDisplay = synchronized(lock) {
                streaming && generation == frameGeneration
            }
            if (!shouldDisplay) {
                bitmap.recycle()
                continue
            }
            var previousBitmap: Bitmap? = null
            val scheduleDisplay = synchronized(lock) {
                if (!streaming || generation != frameGeneration) {
                    false
                } else {
                    previousBitmap = pendingBitmap?.bitmap
                    pendingBitmap = DecodedFrame(bitmap, frameGeneration)
                    if (displayScheduled) {
                        false
                    } else {
                        displayScheduled = true
                        true
                    }
                }
            }
            previousBitmap?.let { if (!it.isRecycled) it.recycle() }
            if (!scheduleDisplay) {
                val isPending = synchronized(lock) { pendingBitmap?.bitmap === bitmap }
                if (!isPending && !bitmap.isRecycled) bitmap.recycle()
            } else {
                mainHandler.post(displayLatestFrame)
            }
        }
    }

    private fun decodeForDisplay(jpeg: ByteArray): Bitmap? {
        return try {
            val target = ClockActivity.me?.takeIf { it.isActivityStarted }
                ?: DeskActivity.me?.takeIf { it.isActivityStarted }
                ?: return null
            val metrics = target.resources.displayMetrics
            val targetWidth = metrics.widthPixels.coerceAtLeast(1)
            val targetHeight = metrics.heightPixels.coerceAtLeast(1)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                Log.w(TAG, "Ignoring invalid framebuffer JPEG")
                return null
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(
                    bounds.outWidth,
                    bounds.outHeight,
                    targetWidth,
                    targetHeight
                )
            }
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
        } catch (e: RuntimeException) {
            Log.e(TAG, "Framebuffer JPEG decode failed", e)
            null
        }
    }

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
