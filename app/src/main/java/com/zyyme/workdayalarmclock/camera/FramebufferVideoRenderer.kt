package com.zyyme.workdayalarmclock.camera

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import com.zyyme.workdayalarmclock.ClockActivity
import com.zyyme.workdayalarmclock.DeskActivity
import com.zyyme.workdayalarmclock.FramebufferVideoSurfaceView
import com.zyyme.workdayalarmclock.MeService
import java.io.ByteArrayOutputStream

/**
 * /fb264 和 /fb265 的显示端：把解码器的输出 Surface 挂到当前时钟界面的 SurfaceView 上。
 * 264/265 共用同一套逻辑，只有 [FramebufferVideoCodec] 不同。
 */
internal object FramebufferVideoRenderer {
    private const val TAG = "FramebufferVideo"
    private const val MAX_PENDING_BYTES = 8 * 1024 * 1024

    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()

    private var context: Context? = null
    private var active = false
    private var failed = false
    private var codec: FramebufferVideoCodec? = null
    private var decoder: FramebufferVideoDecoder? = null
    private var view: FramebufferVideoSurfaceView? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var pending = ByteArrayOutputStream()

    fun initialize(appContext: Context) {
        context = appContext.applicationContext
    }

    fun isActive(): Boolean = synchronized(lock) { active }

    fun isFailed(): Boolean = synchronized(lock) { failed }

    fun canDecode(codec: FramebufferVideoCodec): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any {
                    it.equals(codec.mime, ignoreCase = true)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    fun notifyUnsupported(codec: FramebufferVideoCodec) {
        val name = if (codec == FramebufferVideoCodec.HEVC) "H.265" else "H.264"
        showToast("设备没有可用的 $name 解码器")
    }

    fun start(codec: FramebufferVideoCodec): Boolean {
        if (!canDecode(codec)) return false
        val existingView = synchronized(lock) { view }
        stopInternal(false)
        synchronized(lock) {
            active = true
            failed = false
            this.codec = codec
            videoWidth = 0
            videoHeight = 0
            pending = ByteArrayOutputStream()
        }
        if (existingView != null && existingView.holder.surface.isValid) {
            // 切换 264/265 时复用已有的 SurfaceView，只重建解码器。
            onSurfaceCreated(existingView.holder, existingView)
        } else {
            mainHandler.post {
                ClockActivity.me?.showFramebufferVideoView()
                DeskActivity.me?.showFramebufferVideoView()
            }
        }
        return true
    }

    fun feed(data: ByteArray, length: Int) {
        if (length <= 0) return
        var target: FramebufferVideoDecoder? = null
        synchronized(lock) {
            if (!active || failed) return
            target = decoder
            if (target == null) {
                if (pending.size() + length > MAX_PENDING_BYTES) {
                    failed = true
                    showToast("视频解码器没有准备就绪")
                } else {
                    pending.write(data, 0, length)
                }
                return
            }
        }
        try {
            target?.feed(data, length)
        } catch (e: Exception) {
            fail("视频码流送入解码器失败：${e.message}")
        }
    }

    /** SurfaceView 的 Surface 就绪：用它创建解码器，解码结果直接合成到这块 Surface。 */
    fun onSurfaceCreated(holder: SurfaceHolder, surfaceView: FramebufferVideoSurfaceView) {
        val targetSurface = holder.surface
        if (!targetSurface.isValid) return
        val selected = synchronized(lock) {
            if (!active || failed) {
                null
            } else if (view === surfaceView && decoder != null) {
                // 同一个 Surface 已经挂过解码器，避免界面显示和回调重复创建。
                null
            } else {
                view = surfaceView
                codec
            }
        } ?: return
        applyVideoSize(surfaceView)
        createDecoder(selected, targetSurface)
    }

    /** Surface 即将销毁：先停掉解码器，避免它继续往失效的 Surface 写数据。 */
    fun onSurfaceDestroyed(holder: SurfaceHolder, surfaceView: FramebufferVideoSurfaceView) {
        var toStop: FramebufferVideoDecoder? = null
        synchronized(lock) {
            // 只处理当前 Surface 的销毁，忽略切换过程中迟到的旧回调。
            if (view !== surfaceView) return
            view = null
            toStop = decoder
            decoder = null
        }
        toStop?.stop()
    }

    fun stop() {
        stopInternal(true)
    }

    private fun stopInternal(notifyUi: Boolean) {
        var toStop: FramebufferVideoDecoder? = null
        synchronized(lock) {
            if (!active && decoder == null) return
            active = false
            codec = null
            failed = false
            view = null
            toStop = decoder
            decoder = null
            pending = ByteArrayOutputStream()
        }
        toStop?.stop()
        if (notifyUi) {
            mainHandler.post {
                ClockActivity.me?.hideFramebufferVideoView()
                DeskActivity.me?.hideFramebufferVideoView()
            }
        }
    }

    private fun createDecoder(codec: FramebufferVideoCodec, targetSurface: Surface) {
        val created = try {
            FramebufferVideoDecoder(
                codec,
                targetSurface,
                { message -> MeService.me?.print2LogView("fbVideo" + message) },
                { width, height -> onVideoSize(width, height) },
                { message -> fail(message) }
            )
        } catch (e: Exception) {
            fail("创建 ${codecName(codec)} 解码器失败：${e.message}")
            return
        }

        var stale = false
        synchronized(lock) {
            if (!active) {
                stale = true
            } else {
                decoder = created
                if (pending.size() > 0) {
                    val buffered = pending.toByteArray()
                    pending = ByteArrayOutputStream()
                    created.feed(buffered, buffered.size)
                }
            }
        }
        if (stale) created.stop()
    }

    private fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val target = synchronized(lock) {
            videoWidth = width
            videoHeight = height
            view
        } ?: return
        mainHandler.post { target.setVideoSize(width, height) }
    }

    private fun applyVideoSize(surfaceView: FramebufferVideoSurfaceView) {
        val width: Int
        val height: Int
        synchronized(lock) {
            width = videoWidth
            height = videoHeight
        }
        if (width > 0 && height > 0) {
            mainHandler.post { surfaceView.setVideoSize(width, height) }
        }
    }

    private fun fail(message: String) {
        val first = synchronized(lock) {
            if (failed) {
                false
            } else {
                failed = true
                true
            }
        }
        if (!first) return
        Log.w(TAG, message)
        showToast(message)
    }

    private fun showToast(message: String) {
        val appContext = context ?: return
        mainHandler.post {
            Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun codecName(codec: FramebufferVideoCodec): String =
        if (codec == FramebufferVideoCodec.HEVC) "H.265" else "H.264"
}
