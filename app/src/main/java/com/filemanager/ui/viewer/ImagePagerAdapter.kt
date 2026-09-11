package com.filemanager.ui.viewer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.filemanager.R
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ImagePagerAdapter(
    private val paths: List<String>,
    private val onTap: () -> Unit
) : RecyclerView.Adapter<ImagePagerAdapter.VH>() {

    inner class VH(val imageView: ZoomableImageView) : RecyclerView.ViewHolder(imageView)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val iv = ZoomableImageView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(0xFF000000.toInt())
            setOnSingleTapListener(onTap)
        }
        return VH(iv)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        Glide.with(holder.imageView.context)
            .load(File(paths[position]))
            .transition(DrawableTransitionOptions.withCrossFade())
            .placeholder(R.drawable.ic_image)
            .into(holder.imageView)
        holder.imageView.resetZoom()
    }

    override fun getItemCount() = paths.size
}

enum class ImageZoomMode(val label: String) {
    FIT("FIT"),
    Z50("50%"),
    FULL("100%"),
    Z150("150%"),
    Z200("200%"),
    CROP("CROP");

    fun next() = entries[(ordinal + 1) % entries.size]
}

@SuppressLint("ClickableViewAccessibility")
class ZoomableImageView(context: android.content.Context) : androidx.appcompat.widget.AppCompatImageView(context) {

    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var onSingleTap: (() -> Unit)? = null
    var currentZoomMode: ImageZoomMode = ImageZoomMode.FIT
        private set

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactor * detector.scaleFactor).coerceIn(0.5f, 8f)
                scaleFactor = newScale
                applyTransform()
                return true
            }
        })

    fun setOnSingleTapListener(listener: () -> Unit) {
        onSingleTap = listener
    }

    fun resetZoom() {
        currentZoomMode = ImageZoomMode.FIT
        scaleType = ScaleType.FIT_CENTER
        scaleFactor = 1f
        translateX = 0f
        translateY = 0f
        applyTransform()
    }

    fun setZoomMode(mode: ImageZoomMode) {
        currentZoomMode = mode
        translateX = 0f
        translateY = 0f
        when (mode) {
            ImageZoomMode.FIT -> {
                scaleType = ScaleType.FIT_CENTER
                scaleFactor = 1f
            }
            ImageZoomMode.CROP -> {
                scaleType = ScaleType.CENTER_CROP
                scaleFactor = 1f
            }
            ImageZoomMode.Z50 -> {
                scaleType = ScaleType.FIT_CENTER
                scaleFactor = 0.5f
            }
            ImageZoomMode.FULL -> {
                scaleType = ScaleType.FIT_CENTER
                scaleFactor = 1f
            }
            ImageZoomMode.Z150 -> {
                scaleType = ScaleType.FIT_CENTER
                scaleFactor = 1.5f
            }
            ImageZoomMode.Z200 -> {
                scaleType = ScaleType.FIT_CENTER
                scaleFactor = 2f
            }
        }
        applyTransform()
    }

    private fun applyTransform() {
        scaleX = scaleFactor
        scaleY = scaleFactor
        translationX = translateX
        translationY = translateY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                isDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress && (scaleFactor > 1f || scaleFactor < 1f || currentZoomMode == ImageZoomMode.CROP)) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (abs(dx) > 10 || abs(dy) > 10) isDragging = true
                    translateX += dx
                    translateY += dy
                    applyTransform()
                    lastX = event.x
                    lastY = event.y
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isDragging) onSingleTap?.invoke()
                if (scaleFactor == 1f && currentZoomMode == ImageZoomMode.FIT) {
                    translateX = 0f
                    translateY = 0f
                    applyTransform()
                }
                parent?.requestDisallowInterceptTouchEvent(scaleFactor > 1f || currentZoomMode == ImageZoomMode.CROP)
            }
        }
        return true
    }
}
