package com.zyyme.workdayalarmclock

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat

class CircularMusicProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.clockProgressFill)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val arcBounds = RectF()
    private var progressFraction = 0f

    init {
        progressPaint.strokeWidth = 10f * resources.displayMetrics.density
        visibility = GONE
        isClickable = false
        isFocusable = false
    }

    fun setProgress(position: Int?, duration: Int) {
        val nextFraction = if (position == null || duration <= 0) {
            0f
        } else {
            position.coerceIn(0, duration) / duration.toFloat()
        }
        if (progressFraction != nextFraction) {
            progressFraction = nextFraction
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (progressFraction <= 0f) return

        val diameter = minOf(width, height).toFloat()
        val left = (width - diameter) / 2f
        val top = (height - diameter) / 2f
        arcBounds.set(left, top, left + diameter, top + diameter)
        arcBounds.inset(progressPaint.strokeWidth / 2f, progressPaint.strokeWidth / 2f)
        canvas.drawArc(arcBounds, -90f, progressFraction * 360f, false, progressPaint)
    }
}
