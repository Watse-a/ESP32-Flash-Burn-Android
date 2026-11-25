package com.espflash.burn

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator

/** 等待进度：一段实心胶囊来回走动，像参考 App 那种满填充效果的 indeterminate */
class MarqueeBar @JvmOverloads constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF8FB8FF.toInt() }
    private val rect = RectF()
    private var frac = 0f
    private var animator: ValueAnimator? = null

    init {
        paint.style = Paint.Style.FILL
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                frac = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        animator?.cancel()
        animator = null
    }

    fun setTint(color: Int) {
        paint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val seg = w * 0.45f
        val left = frac * (w - seg)
        val r = h / 2f
        rect.set(left, 0f, left + seg, h)
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
