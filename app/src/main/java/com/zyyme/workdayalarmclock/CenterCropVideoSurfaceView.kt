package com.zyyme.workdayalarmclock

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceView
import kotlin.math.ceil

/** 在居中的 FrameLayout 中等比铺满视频，超出屏幕的部分由容器裁剪。 */
class CenterCropVideoSurfaceView @JvmOverloads constructor(
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
        val scale = maxOf(width.toDouble() / videoWidth, height.toDouble() / videoHeight)
        setMeasuredDimension(ceil(videoWidth * scale).toInt(), ceil(videoHeight * scale).toInt())
    }
}
