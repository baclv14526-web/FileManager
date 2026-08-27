package com.filemanager.utils

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.RecyclerView
import com.filemanager.data.model.FileItem
import kotlin.math.roundToInt

class FastScroller @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val MIN_ITEMS      = 30
        private const val HIDE_DELAY_MS  = 1500L
        private const val TRACK_W_DP    = 4f
        private const val THUMB_W_DP    = 4f
        private const val THUMB_MIN_DP  = 36f
        private const val BUBBLE_DP     = 48f
        private const val PAD_DP        = 16f
    }

    // ── Paints ──────────────────────────────────────────────────

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000; style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.FILL
    }
    private val thumbActivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D47A1.toInt(); style = Paint.Style.FILL
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1565C0.toInt(); style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    // ── Dimensions ──────────────────────────────────────────────

    private val trackW   get() = dp(TRACK_W_DP)
    private val thumbW   get() = dp(THUMB_W_DP)
    private val thumbMin get() = dp(THUMB_MIN_DP)
    private val bubbleSz get() = dp(BUBBLE_DP)
    private val pad      get() = dp(PAD_DP)

    // ── State ───────────────────────────────────────────────────

    private var rv: RecyclerView? = null

    // File list mode
    private var items: List<FileItem> = emptyList()

    // Timeline mode
    private var customLabels: List<Pair<Int, String>> = emptyList()
    private var totalItemCount: Int = 0

    private var isDragging = false
    private var thumbTop   = 0f
    private var thumbH     = 0f

    private val hideRunnable = Runnable { animateAlpha(0f) }

    // ── Scroll listener ─────────────────────────────────────────

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (!isDragging) syncThumb()
            val count = if (customLabels.isNotEmpty()) totalItemCount else items.size
            if (count >= MIN_ITEMS) { animateAlpha(1f); scheduleHide() }
        }
    }

    // ── Public API ──────────────────────────────────────────────

    fun attachToRecyclerView(recyclerView: RecyclerView) {
        rv?.removeOnScrollListener(scrollListener)
        rv = recyclerView
        recyclerView.addOnScrollListener(scrollListener)
        alpha = 0f
    }

    /** File list mode */
    fun setItems(newItems: List<FileItem>) {
        items        = newItems
        customLabels = emptyList()
        totalItemCount = 0
        visibility   = if (newItems.size >= MIN_ITEMS) VISIBLE else INVISIBLE
        syncThumb()
        invalidate()
    }

    /** Timeline mode — labels: list of (position, displayLabel) */
    fun setTimelineLabels(labels: List<Pair<Int, String>>, total: Int) {
        customLabels   = labels
        totalItemCount = total
        items          = emptyList()
        visibility     = if (total >= MIN_ITEMS) VISIBLE else INVISIBLE
        syncThumb()
        invalidate()
    }

    // ── Touch ───────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val trackTop    = pad
        val trackBottom = height - pad

        return when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (event.x >= width - dp(28f)) {
                    isDragging = true
                    removeCallbacks(hideRunnable)
                    animateAlpha(1f)
                    dragTo(event.y, trackTop, trackBottom)
                    true
                } else false
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) { dragTo(event.y, trackTop, trackBottom); true }
                else false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                scheduleHide()
                invalidate()
                true
            }
            else -> false
        }
    }

    private fun dragTo(y: Float, trackTop: Float, trackBottom: Float) {
        val range   = (trackBottom - trackTop - thumbH).coerceAtLeast(1f)
        val clamped = (y - trackTop - thumbH / 2f).coerceIn(0f, range)
        thumbTop    = trackTop + clamped
        scrollToFraction(clamped / range)
        invalidate()
    }

    private fun scrollToFraction(fraction: Float) {
        val r = rv ?: return
        try {
            if (r.layoutManager == null) return
            val total  = r.computeVerticalScrollRange()
            val target = (fraction * total).roundToInt()
            r.scrollBy(0, target - r.computeVerticalScrollOffset())
        } catch (e: Exception) {
            // RecyclerView có thể đang giữa layout pass (VD: LoadingHelper
            // hoán đổi adapter/layoutManager) — bỏ qua an toàn, không crash/toast.
        }
    }

    // ── Draw ────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val count = if (customLabels.isNotEmpty()) totalItemCount else items.size
        if (count < MIN_ITEMS) return

        textPaint.textSize = sp(18f)

        val trackTop    = pad
        val trackBottom = height - pad
        val cx          = width - trackW / 2f

        // Track
        canvas.drawRoundRect(
            cx - trackW / 2f, trackTop, cx + trackW / 2f, trackBottom,
            trackW / 2f, trackW / 2f, trackPaint
        )

        // Thumb (thicker when dragging)
        val tw    = if (isDragging) thumbW * 2.5f else thumbW
        val paint = if (isDragging) thumbActivePaint else thumbPaint
        canvas.drawRoundRect(
            cx - tw / 2f, thumbTop, cx + tw / 2f, thumbTop + thumbH,
            tw / 2f, tw / 2f, paint
        )

        // Bubble label when dragging
        if (isDragging) {
            val label = getLabelAt(thumbTop, trackTop, trackBottom)
            val bx    = width - dp(28f) - bubbleSz
            val byCtr = (thumbTop + thumbH / 2f)
                .coerceIn(trackTop + bubbleSz / 2f, trackBottom - bubbleSz / 2f)

            canvas.drawCircle(bx + bubbleSz / 2f, byCtr, bubbleSz / 2f, bubblePaint)
            val textY = byCtr - (textPaint.ascent() + textPaint.descent()) / 2f
            canvas.drawText(label, bx + bubbleSz / 2f, textY, textPaint)
        }
    }

    // ── Label ───────────────────────────────────────────────────

    private fun getLabelAt(tTop: Float, trackTop: Float, trackBottom: Float): String {
        val range    = (trackBottom - trackTop - thumbH).coerceAtLeast(1f)
        val fraction = ((tTop - trackTop) / range).coerceIn(0f, 1f)

        // Timeline mode
        if (customLabels.isNotEmpty() && totalItemCount > 0) {
            val pos = (fraction * (totalItemCount - 1)).roundToInt()
            return (customLabels.lastOrNull { it.first <= pos }
                ?: customLabels.firstOrNull())?.second ?: "#"
        }

        // File list mode
        if (items.isEmpty()) return "#"
        val idx  = (fraction * (items.size - 1)).roundToInt().coerceIn(0, items.lastIndex)
        val item = items[idx]
        return when {
            item.isDirectory                      -> "📁"
            item.name.firstOrNull()?.isDigit() == true -> "#"
            else -> item.name.firstOrNull()?.uppercaseChar()?.toString() ?: "#"
        }
    }

    // ── Sync thumb from scroll position ─────────────────────────

    private fun syncThumb() {
        val r = rv ?: return
        if (height == 0) return
        if (r.layoutManager == null) return   // giữa lúc swap layoutManager → bỏ qua

        try {
            val trackTop    = pad
            val trackBottom = height - pad
            val trackH      = (trackBottom - trackTop).coerceAtLeast(1f)

            val range  = r.computeVerticalScrollRange().coerceAtLeast(1)
            val extent = r.computeVerticalScrollExtent().coerceAtLeast(0)
            thumbH     = (extent.toFloat() / range * trackH).coerceAtLeast(thumbMin)

            val offset   = r.computeVerticalScrollOffset()
            val fraction = offset.toFloat() / (range - extent).coerceAtLeast(1)
            thumbTop     = trackTop + fraction * (trackH - thumbH)

            invalidate()
        } catch (e: Exception) {
            // RecyclerView đang computing layout/scroll — bỏ qua an toàn.
            // Đây là nguyên nhân của lỗi "androidx.recyclerview..." thoáng qua
            // khi LoadingHelper hoán đổi adapter/layoutManager lúc search debounce.
        }
    }

    // ── Alpha animation ─────────────────────────────────────────

    private var alphaAnim: ObjectAnimator? = null

    private fun animateAlpha(to: Float) {
        if (alpha == to) return
        alphaAnim?.cancel()
        alphaAnim = ObjectAnimator.ofFloat(this, "alpha", alpha, to).apply {
            duration     = 200
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun scheduleHide() {
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, HIDE_DELAY_MS)
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun sp(v: Float) = v * resources.displayMetrics.scaledDensity
}
