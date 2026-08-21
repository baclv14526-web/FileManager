package com.filemanager.utils

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Custom shimmer view - không cần dependency ngoài.
 * Vẽ gradient sáng chạy từ trái sang phải lặp vô hạn.
 */
class ShimmerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shimmerX = -1f
    private var animator: ValueAnimator? = null

    // Màu shimmer: xám nhạt → trắng sáng → xám nhạt
    private val baseColor    = 0xFFE8E8E8.toInt()
    private val highlightColor = 0xFFFAFAFA.toInt()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startShimmer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopShimmer()
    }

    private fun startShimmer() {
        animator = ValueAnimator.ofFloat(-1f, 2f).apply {
            duration        = 1400
            repeatMode      = ValueAnimator.RESTART
            repeatCount     = ValueAnimator.INFINITE
            interpolator    = LinearInterpolator()
            addUpdateListener {
                shimmerX = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopShimmer() {
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width == 0) return

        // Base fill
        paint.color = baseColor
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Shimmer gradient overlay
        val x = shimmerX * width
        val shimmerWidth = width * 0.4f
        val shader = LinearGradient(
            x - shimmerWidth, 0f, x + shimmerWidth, 0f,
            intArrayOf(baseColor, highlightColor, baseColor),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        paint.shader = shader
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }
}
