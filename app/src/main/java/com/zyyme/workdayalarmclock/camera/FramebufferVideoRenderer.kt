package com.zyyme.workdayalarmclock.camera

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaCodecList
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.zyyme.workdayalarmclock.ClockActivity
import com.zyyme.workdayalarmclock.DeskActivity
import com.zyyme.workdayalarmclock.MeService
import java.io.ByteArrayOutputStream
import kotlin.math.min

/**
 * /fb264 和 /fb265 的显示端：把解码器的输出 Surface 挂到当前时钟界面的 TextureView 上。
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
    private var surface: Surface? = null
    private var texture: SurfaceTexture? = null
    private var view: TextureView? = null
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
        val existing = synchronized(lock) {
            val currentView = view
            val currentTexture = texture
            if (currentView != null && currentTexture != null) {
                Pair(currentView, currentTexture)
            } else {
                null
            }
        }
        stopInternal(false)
        synchronized(lock) {
            active = true
            failed = false
            this.codec = codec
            videoWidth = 0
            videoHeight = 0
            pending = ByteArrayOutputStream()
        }
        if (existing != null) {
            // 切换 264/265 时复用已有 TextureView，只重建 Surface 和解码器。
            onSurfaceAvailable(existing.second, existing.first)
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

    fun onSurfaceAvailable(surfaceTexture: SurfaceTexture, textureView: TextureView) {
        val newSurface = Surface(surfaceTexture)
        val currentCodec = synchronized(lock) {
            if (!active || failed) {
                null
            } else if (decoder != null && texture === surfaceTexture) {
                // 同一个 Surface 已经挂过解码器，避免 show() 和回调重复创建。
                null
            } else {
                val selected = codec
                if (selected == null) {
                    null
                } else {
                    texture = surfaceTexture
                    view = textureView
                    surface = newSurface
                    selected
                }
            }
        }
        if (currentCodec == null) {
            newSurface.release()
            return
        }

        val created = try {
            FramebufferVideoDecoder(
                currentCodec,
                newSurface,
                { message -> MeService.me?.print2LogView("fbVideo" + message) },
                { width, height -> onVideoSize(width, height) },
                { message -> fail(message) }
            )
        } catch (e: Exception) {
            newSurface.release()
            synchronized(lock) {
                surface = null
                texture = null
                view = null
            }
            fail("创建 ${codecName(currentCodec)} 解码器失败：${e.message}")
            return
        }

        synchronized(lock) {
            if (!active) {
                created.stop()
                newSurface.release()
                surface = null
                texture = null
                view = null
                return
            }
            decoder = created
            if (pending.size() > 0) {
                val buffered = pending.toByteArray()
                pending = ByteArrayOutputStream()
                created.feed(buffered, buffered.size)
            }
        }
    }

    fun onSurfaceDestroyed(surfaceTexture: SurfaceTexture) {
        var toStop: FramebufferVideoDecoder? = null
        var toRelease: Surface? = null
        synchronized(lock) {
            // 只处理当前 Surface 的销毁，忽略切换过程中迟到的旧回调。
            if (texture !== surfaceTexture) return
            texture = null
            view = null
            toStop = decoder
            decoder = null
            toRelease = surface
            surface = null
        }
        toStop?.stop()
        toRelease?.release()
    }

    fun onViewSizeChanged(textureView: TextureView) {
        var width = 0
        var height = 0
        synchronized(lock) {
            if (!active || view !== textureView) return
            width = videoWidth
            height = videoHeight
        }
        if (width > 0 && height > 0) applyFitCenter(textureView, width, height)
    }

    fun stop() {
        stopInternal(true)
    }

    private fun stopInternal(notifyUi: Boolean) {
        var toStop: FramebufferVideoDecoder? = null
        var toRelease: Surface? = null
        synchronized(lock) {
            if (!active && decoder == null) return
            active = false
            codec = null
            failed = false
            texture = null
            view = null
            toStop = decoder
            decoder = null
            toRelease = surface
            surface = null
            pending = ByteArrayOutputStream()
        }
        toStop?.stop()
        toRelease?.release()
        if (notifyUi) {
            mainHandler.post {
                ClockActivity.me?.hideFramebufferVideoView()
                DeskActivity.me?.hideFramebufferVideoView()
            }
        }
    }

    private fun onVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        var target: TextureView? = null
        synchronized(lock) {
            videoWidth = width
            videoHeight = height
            target = view
        }
        val textureView = target ?: return
        mainHandler.post { applyFitCenter(textureView, width, height) }
    }

    private fun applyFitCenter(textureView: TextureView, width: Int, height: Int) {
        val viewWidth = textureView.width
        val viewHeight = textureView.height
        if (viewWidth <= 0 || viewHeight <= 0) {
            textureView.post {
                if (textureView.width > 0 && textureView.height > 0) {
                    applyFitCenter(textureView, width, height)
                }
            }
            return
        }
        val fit = min(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
        val matrix = Matrix()
        matrix.setScale(
            fit * width / viewWidth,
            fit * height / viewHeight,
            viewWidth / 2f,
            viewHeight / 2f
        )
        textureView.setTransform(matrix)
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
