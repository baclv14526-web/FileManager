package com.filemanager.utils

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filemanager.data.model.FileItem
import kotlin.math.roundToInt

/**
 * FastScroller — thanh cuộn nhanh bên phải màn hình.
 *
 * Tính năng:
 *  - Thanh track mỏng + thumb kéo được
 *  - Bubble hiện chữ cái / ký tự đầu tiên của item tại vị trí đang kéo
 *  - Tự ẩn sau 1.5s khi không dùng (fade-out)
 *  - Tự hiện khi list scroll (fade-in)
 *  - Chỉ hiện khi có ≥ MIN_ITEMS items
 */
class FastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MIN_ITEMS       = 30     // hiện khi ≥ 30 items
        private const val HIDE_DELAY_MS   = 1500L
        private const val TRACK_WIDTH_DP  = 4f
        private const val THUMB_WIDTH_DP  = 4f
        private const val THUMB_MIN_H_DP  = 36f
        private const val BUBBLE_SIZE_DP  = 48f
        private const val TRACK_PADDING_DP= 16f   // padding top/bottom
    }

    // ── Paints ──────────────────────────────────────────────────

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        style = Paint.Style.FILL
    }
    private val thumbActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D47A1.toInt()
        style = Paint.Style.FILL
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt()
        style = Paint.Style.FILL
    }
    private val bubbleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = sp(18f)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // ── Dimensions ──────────────────────────────────────────────

    private val trackWidth    = dp(TRACK_WIDTH_DP)
    private val thumbWidth    = dp(THUMB_WIDTH_DP)
    private val thumbMinH     = dp(THUMB_MIN_H_DP)
    private val bubbleSize    = dp(BUBBLE_SIZE_DP)
    private val trackPadding  = dp(TRACK_PADDING_DP)

    // ── State ───────────────────────────────────────────────────

    private var recyclerView: RecyclerView? = null
    private var items: List<FileItem> = emptyList()
    private var isDragging = false
    private var thumbTop = 0f
    private var thumbHeight = thumbMinH

    private val hideRunnable = Runnable { animateAlpha(0f) }

    // ── Scroll listener ─────────────────────────────────────────

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (!isDragging) updateThumbFromScroll()
            if (items.size >= MIN_ITEMS) {
                animateAlpha(1f)
                scheduleHide()
            }
        }
    }

    // ── Public API ──────────────────────────────────────────────

    fun attachToRecyclerView(rv: RecyclerView) {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = rv
        rv.addOnScrollListener(scrollListener)
        alpha = 0f
    }

    fun setItems(newItems: List<FileItem>) {
        items = newItems
        visibility = if (newItems.size >= MIN_ITEMS) VISIBLE else INVISIBLE
        updateThumbFromScroll()
        invalidate()
    }

    // ── Touch ───────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val trackTop    = trackPadding
        val trackBottom = height - trackPadding

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Hit-test: только если tap попал на thumb
                if (event.x >= width - dp(24f)) {
                    isDragging = true
                    removeCallbacks(hideRunnable)
                    animateAlpha(1f)
                    moveTo(event.y, trackTop, trackBottom)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    moveTo(event.y, trackTop, trackBottom)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                scheduleHide()
                invalidate()
            }
        }
        return isDragging
    }

    private fun moveTo(y: Float, trackTop: Float, trackBottom: Float) {
        val trackH = trackBottom - trackTop - thumbHeight
        val clamped = (y - trackTop - thumbHeight / 2f).coerceIn(0f, trackH)
        thumbTop = trackTop + clamped
        val fraction = clamped / trackH.coerceAtLeast(1f)
        scrollRecyclerViewTo(fraction)
        invalidate()
    }

    private fun scrollRecyclerViewTo(fraction: Float) {
        val rv = recyclerView ?: return
        val total = rv.computeVerticalScrollRange()
        val offset = (fraction * total).roundToInt()
        rv.scrollBy(0, offset - rv.computeVerticalScrollOffset())
    }

    // ── Draw ────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (items.size < MIN_ITEMS) return

        val trackTop    = trackPadding
        val trackBottom = height - trackPadding
        val cx          = width - trackWidth / 2f

        // Track
        canvas.drawRoundRect(
            cx - trackWidth / 2f, trackTop,
            cx + trackWidth / 2f, trackBottom,
            trackWidth / 2f, trackWidth / 2f,
            trackPaint
        )

        // Thumb
        val paint = if (isDragging) thumbActivePaint else thumbPaint
        val tw    = if (isDragging) thumbWidth * 2 else thumbWidth
        canvas.drawRoundRect(
            cx - tw / 2f, thumbTop,
            cx + tw / 2f, thumbTop + thumbHeight,
            tw / 2f, tw / 2f,
            paint
        )

        // Bubble (chỉ khi drag)
        if (isDragging) {
            val bubbleLabel = getLabelAt(thumbTop, trackTop, trackBottom)
            val bx    = width - dp(24f) - bubbleSize
            val byCtr = (thumbTop + thumbHeight / 2f)
                .coerceIn(trackTop + bubbleSize / 2f, trackBottom - bubbleSize / 2f)

            // Vẽ hình tròn bubble
            canvas.drawCircle(bx + bubbleSize / 2f, byCtr, bubbleSize / 2f, bubblePaint)

            // Vẽ chữ
            val textY = byCtr - (bubbleTextPaint.ascent() + bubbleTextPaint.descent()) / 2f
            canvas.drawText(bubbleLabel, bx + bubbleSize / 2f, textY, bubbleTextPaint)
        }
    }

    // ── Label logic ─────────────────────────────────────────────

    private fun getLabelAt(tTop: Float, trackTop: Float, trackBottom: Float): String {
        val trackH   = (trackBottom - trackTop - thumbHeight).coerceAtLeast(1f)
        val fraction = ((tTop - trackTop) / trackH).coerceIn(0f, 1f)

        // Mode Timeline: dùng customLabels
        if (customLabels.isNotEmpty() && totalItemCount > 0) {
            val pos = (fraction * (totalItemCount - 1)).roundToInt()
            // Tìm header gần nhất phía trên vị trí hiện tại
            val label = customLabels.lastOrNull { it.first <= pos }
                ?: customLabels.firstOrNull()
            return label?.second ?: "#"
        }

        // Mode FileList: dùng items
        if (items.isEmpty()) return "#"
        val index = (fraction * (items.size - 1)).roundToInt()
            .coerceIn(0, items.lastIndex)
        val item = items[index]
        return when {
            item.isDirectory -> "📁"
            item.name.firstOrNull()?.isDigit() == true -> "#"
            else -> item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        }
    }

    // ── Thumb position sync ─────────────────────────────────────

    private fun updateThumbFromScroll() {
        val rv = recyclerView ?: return
        if (height == 0) return

        val trackTop    = trackPadding
        val trackBottom = height - trackPadding
        val trackH      = (trackBottom - trackTop).coerceAtLeast(1f)

        // Tính chiều cao thumb tỉ lệ với visible / total
        val range    = rv.computeVerticalScrollRange().coerceAtLeast(1)
        val extent   = rv.computeVerticalScrollExtent().coerceAtLeast(0)
        val ratio    = extent.toFloat() / range
        thumbHeight  = (ratio * trackH).coerceAtLeast(thumbMinH)

        // Vị trí thumb
        val offset   = rv.computeVerticalScrollOffset()
        val fraction = offset.toFloat() / (range - extent).coerceAtLeast(1)
        thumbTop     = trackTop + fraction * (trackH - thumbHeight)

        invalidate()
    }

    // ── Alpha animation ─────────────────────────────────────────

    private var alphaAnimator: ObjectAnimator? = null

    private fun animateAlpha(to: Float) {
        if (alpha == to) return
        alphaAnimator?.cancel()
        alphaAnimator = ObjectAnimator.ofFloat(this, "alpha", alpha, to).apply {
            duration    = 200
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun scheduleHide() {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun dp(value: Float) = value * resources.displayMetrics.density
    private fun sp(value: Float) = value * resources.displayMetrics.scaledDensity
}

// Extension: hỗ trợ Timeline dùng label tùy ý thay vì chữ cái từ FileItem
private var customLabels: List<Pair<Int, String>> = emptyList() // (position, label)
private var totalItemCount: Int = 0

fun setTimelineLabels(labels: List<Pair<Int, String>>, total: Int) {
    customLabels = labels
    totalItemCount = total
    visibility = if (total >= MIN_ITEMS) VISIBLE else INVISIBLE
    invalidate()
}
