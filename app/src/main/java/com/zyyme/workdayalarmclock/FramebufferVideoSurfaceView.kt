package com.zyyme.workdayalarmclock

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.ceil

/**
 * /fb264 与 /fb265 的解码输出层。
 * 这里按 fitCenter 等比完整显示，多余的黑边由父容器背景补上。
 */
class FramebufferVideoSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceView(context, attrs) {
    private var videoWidth = 0
    private var videoHeight = 0

    fun setVideoSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (videoWidth == width && videoHeight == height)) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val scale = minOf(width.toDouble() / videoWidth, height.toDouble() / videoHeight)
        setMeasuredDimension(ceil(videoWidth * scale).toInt(), ceil(videoHeight * scale).toInt())
    }
}
