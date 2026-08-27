package com.filemanager.utils

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.filemanager.R

/**
 * LoadingHelper — quản lý tất cả trạng thái loading trong app:
 *
 *  1. showShimmer(recyclerView)   → thay adapter bằng shimmer placeholder
 *  2. hideShimmer(recyclerView)   → khôi phục adapter thật + fade-in
 *  3. showOverlay(activity, msg)  → overlay tối + card spinner (cho tác vụ nặng)
 *  4. hideOverlay(activity)       → ẩn overlay + fade-out
 */
object LoadingHelper {

    // ── Shimmer ─────────────────────────────────────────────────

    private val savedAdapters = mutableMapOf<RecyclerView, RecyclerView.Adapter<*>>()
    private val savedManagers = mutableMapOf<RecyclerView, RecyclerView.LayoutManager?>()

    /**
     * Hiện shimmer skeleton cho RecyclerView.
     * Lưu adapter thật lại để restore sau.
     *
     * ✅ FIX: hoán đổi adapter/layoutManager qua rv.post{} — không bao giờ
     * thực hiện giữa lúc RecyclerView đang computing layout/scroll (nguồn gốc
     * lỗi "androidx.recyclerview..." thoáng qua khi search debounce dồn dập).
     */
    fun showShimmer(
        rv: RecyclerView,
        type: ShimmerType = ShimmerType.LIST,
        itemCount: Int = 8
    ) {
        if (rv.adapter is ShimmerAdapter) return   // đang shimmer rồi

        val realAdapter = rv.adapter ?: return
        val realManager = rv.layoutManager

        val doSwap = {
            // Kiểm tra lại lần nữa phòng trường hợp state đã đổi trong lúc post{} đợi
            if (rv.adapter !is ShimmerAdapter) {
                savedAdapters[rv] = realAdapter
                savedManagers[rv] = realManager

                val ctx = rv.context
                rv.layoutManager = when (type) {
                    ShimmerType.GRID  -> GridLayoutManager(ctx, 3)
                    ShimmerType.MEDIA -> GridLayoutManager(ctx, 3)
                    else              -> LinearLayoutManager(ctx)
                }
                rv.adapter = ShimmerAdapter(itemCount, type)
            }
        }

        if (rv.isComputingLayout) rv.post(doSwap) else doSwap()
    }

    /**
     * Ẩn shimmer, khôi phục adapter thật với fade-in.
     */
    fun hideShimmer(rv: RecyclerView) {
        val real = savedAdapters[rv] ?: return

        val doSwap = {
            savedAdapters.remove(rv)
            rv.layoutManager = savedManagers.remove(rv) ?: LinearLayoutManager(rv.context)
            rv.adapter = real
            rv.startAnimation(AnimationUtils.loadAnimation(rv.context, R.anim.fade_in))
        }

        if (rv.isComputingLayout) rv.post(doSwap) else doSwap()
    }

    // ── Overlay ─────────────────────────────────────────────────

    private const val TAG_OVERLAY = "loading_overlay_tag"

    /**
     * Hiện overlay tối + card spinner.
     * @param message  Dòng chính, VD: "Đang quét..."
     * @param subMsg   Dòng phụ optional, VD: "Vui lòng chờ"
     */
    fun showOverlay(
        activity: Activity,
        message: String = "Đang tải...",
        subMsg: String? = null
    ) {
        val root = activity.window.decorView as? ViewGroup ?: return
        if (root.findViewWithTag<View>(TAG_OVERLAY) != null) {
            // Đã có overlay → update message và return
            updateOverlayMessage(activity, message, subMsg)
            return
        }

        val overlay = activity.layoutInflater
            .inflate(R.layout.layout_loading_overlay, root, false)
        overlay.tag = TAG_OVERLAY

        overlay.findViewById<TextView>(R.id.loadingMessage)?.text = message
        val sub = overlay.findViewById<TextView>(R.id.loadingSubMessage)
        if (subMsg != null) {
            sub?.text = subMsg
            sub?.visibility = View.VISIBLE
        } else {
            sub?.visibility = View.GONE
        }

        root.addView(overlay)
        overlay.visibility = View.VISIBLE
        overlay.startAnimation(AnimationUtils.loadAnimation(activity, R.anim.fade_in))
    }

    /**
     * Ẩn overlay với fade-out.
     */
    fun hideOverlay(activity: Activity) {
        val root = activity.window.decorView as? ViewGroup ?: return
        val overlay = root.findViewWithTag<View>(TAG_OVERLAY) ?: return

        val anim = AnimationUtils.loadAnimation(activity, R.anim.fade_out)
        anim.setAnimationListener(object : android.view.animation.Animation.AnimationListener {
            override fun onAnimationEnd(a: android.view.animation.Animation?) {
                root.removeView(overlay)
            }
            override fun onAnimationStart(a: android.view.animation.Animation?) {}
            override fun onAnimationRepeat(a: android.view.animation.Animation?) {}
        })
        overlay.startAnimation(anim)
    }

    /**
     * Update message của overlay đang hiện (nếu có).
     */
    fun updateOverlayMessage(activity: Activity, message: String, subMsg: String? = null) {
        val root = activity.window.decorView as? ViewGroup ?: return
        val overlay = root.findViewWithTag<View>(TAG_OVERLAY) ?: return
        overlay.findViewById<TextView>(R.id.loadingMessage)?.text = message
        val sub = overlay.findViewById<TextView>(R.id.loadingSubMessage)
        if (subMsg != null) {
            sub?.text = subMsg
            sub?.visibility = View.VISIBLE
        } else {
            sub?.visibility = View.GONE
        }
    }
}
