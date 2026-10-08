package com.zyyme.workdayalarmclock.camera

import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.Executors

internal object FramebufferBitmapReaper {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recycler = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "framebuffer-bitmap-recycler").apply { isDaemon = true }
    }

    fun retire(bitmap: Bitmap?, onRetired: (Bitmap?) -> Unit = { retired ->
        recycle(retired)
    }) {
        afterFrames(2) { onRetired(bitmap) }
    }

    fun recycle(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        recycler.execute {
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    fun afterFrames(frameCount: Int, action: () -> Unit) {
        mainHandler.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
                val choreographer = Choreographer.getInstance()
                var framesRemaining = frameCount.coerceAtLeast(1)
                val callback = object : Choreographer.FrameCallback {
                    override fun doFrame(frameTimeNanos: Long) {
                        framesRemaining--
                        if (framesRemaining == 0) {
                            action()
                        } else {
                            choreographer.postFrameCallback(this)
                        }
                    }
                }
                choreographer.postFrameCallback(callback)
            } else {
                mainHandler.postDelayed(action, FALLBACK_DELAY_MS * frameCount.coerceAtLeast(1))
            }
        }
    }

    private const val FALLBACK_DELAY_MS = 100L
}
