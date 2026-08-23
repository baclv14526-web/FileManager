package com.filemanager.ui.viewer

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.filemanager.R
import com.filemanager.databinding.ActivityVideoPlayerBinding
import java.io.File
import java.util.Locale

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "extra_path"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var currentPosition = 0L
    private var path = ""

    // Zoom state — 5 levels tuần tự
    enum class ZoomMode(val label: String, val scale: Float) {
        FIT   ("FIT",  1.0f),   // vừa màn hình, có letterbox
        FULL  ("100%", 1.0f),   // 100% không scale thêm (dùng RESIZE_MODE_ZOOM)
        Z150  ("150%", 1.5f),   // zoom 150%
        Z200  ("200%", 2.0f),   // zoom 200%
        CROP  ("CROP", 1.0f);   // fill toàn màn hình, crop 2 cạnh

        fun next() = entries[(ordinal + 1) % entries.size]
    }
    private var zoomMode = ZoomMode.FIT

    // QX Controller
    private var qxController: QxSpeedController? = null
    private var isQxActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportActionBar?.hide()

        path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        binding.tvTitle.text = File(path).name

        setupButtons()
        initPlayer()
    }

    // ── Setup ───────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }

        // Xoay màn hình thủ công
        binding.btnRotate.setOnClickListener {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        // Zoom: tuần tự FIT → 100% → 150% → 200% → CROP → FIT → ...
        binding.btnZoom.setOnClickListener {
            zoomMode = zoomMode.next()
            applyZoom()
        }

        // QX Speed toggle
        binding.btnQx.setOnClickListener { toggleQx() }
    }

    private fun applyZoom() {
        val pv = binding.playerView
        when (zoomMode) {
            ZoomMode.FIT -> {
                // Vừa màn hình, letterbox, không scale
                pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                pv.scaleX = 1f; pv.scaleY = 1f
            }
            ZoomMode.FULL -> {
                // Fill màn hình crop 2 cạnh, scale = 1 (ZOOM mode tự fill)
                pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                pv.scaleX = 1f; pv.scaleY = 1f
            }
            ZoomMode.Z150 -> {
                // Crop fill + scale thêm 1.5x
                pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                pv.scaleX = 1.5f; pv.scaleY = 1.5f
            }
            ZoomMode.Z200 -> {
                // Crop fill + scale thêm 2.0x
                pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                pv.scaleX = 2.0f; pv.scaleY = 2.0f
            }
            ZoomMode.CROP -> {
                // Giống FULL nhưng dùng RESIZE_MODE_FIXED_WIDTH để fill chiều ngang
                pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
                pv.scaleX = 1f; pv.scaleY = 1f
            }
        }
        // Cập nhật icon và label
        binding.tvZoomLabel.text = zoomMode.label
        binding.btnZoom.setImageResource(
            if (zoomMode == ZoomMode.FIT) R.drawable.ic_zoom_in else R.drawable.ic_zoom_out
        )
    }

    // ── QX ──────────────────────────────────────────────────────

    private fun toggleQx() {
        isQxActive = !isQxActive
        val p = player ?: return

        if (isQxActive) {
            binding.btnQx.setImageResource(R.drawable.ic_qx_on)
            binding.qxPanel.visibility = View.VISIBLE

            qxController = QxSpeedController(
                player            = p,
                cycleDurationMs   = 30_000L,   // 1 chu kỳ = 30 giây thực
                tickIntervalMs    = 80L,        // cập nhật 12.5 lần/giây → mượt
                onSpeedChanged    = { speed, phase ->
                    runOnUiThread { updateQxUI(speed, phase) }
                }
            )
            qxController?.start(lifecycleScope)
        } else {
            stopQx()
        }
    }

    private fun stopQx() {
        isQxActive = false
        qxController?.stop()
        qxController = null
        binding.btnQx.setImageResource(R.drawable.ic_qx_off)
        binding.qxPanel.visibility = View.GONE
        binding.tvQxSpeed.text = "1.0x"
    }

    private fun updateQxUI(speed: Float, phase: Float) {
        binding.tvQxSpeed.text = String.format(Locale.US, "%.2fx", speed)

        // Progress bar thể hiện vị trí trong chu kỳ (0..1)
        binding.qxProgress.progress = (phase * 1000).toInt()

        // Màu chỉ thị tốc độ:
        //   2x   → đỏ cam  (#FF5722)
        //   1x   → trắng
        //   0.5x → xanh lam (#29B6F6)
        val color = when {
            speed > 1.3f -> interpolateColor(0xFFFFFFFF.toInt(), 0xFFFF5722.toInt(), (speed - 1f) / 1f)
            speed < 0.8f -> interpolateColor(0xFFFFFFFF.toInt(), 0xFF29B6F6.toInt(), (1f - speed) / 0.5f)
            else         -> 0xFFFFFFFF.toInt()
        }
        binding.tvQxSpeed.setTextColor(color)
    }

    private fun interpolateColor(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val aF = (from shr 24) and 0xFF; val aT = (to shr 24) and 0xFF
        val rF = (from shr 16) and 0xFF; val rT = (to shr 16) and 0xFF
        val gF = (from shr 8)  and 0xFF; val gT = (to shr 8)  and 0xFF
        val bF =  from         and 0xFF; val bT =  to         and 0xFF
        return ((aF + (aT - aF) * r).toInt() shl 24) or
               ((rF + (rT - rF) * r).toInt() shl 16) or
               ((gF + (gT - gF) * r).toInt() shl 8)  or
                (bF + (bT - bF) * r).toInt()
    }

    // ── Player ──────────────────────────────────────────────────

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            applyZoom()  // áp dụng zoom mode hiện tại

            exo.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0)
                        autoRotateByVideoSize(size.width, size.height)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    // Dừng QX khi video kết thúc
                    if (state == Player.STATE_ENDED && isQxActive) stopQx()
                }
            })

            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            exo.playWhenReady = playWhenReady
            exo.seekTo(currentPosition)
            exo.prepare()
        }

        // Khởi động lại QX nếu đang bật
        if (isQxActive) {
            qxController = QxSpeedController(
                player = player!!,
                onSpeedChanged = { speed, phase ->
                    runOnUiThread { updateQxUI(speed, phase) }
                }
            )
            qxController?.start(lifecycleScope)
        }
    }

    private fun autoRotateByVideoSize(width: Int, height: Int) {
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
        requestedOrientation = if (width > height)
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // ── Lifecycle ───────────────────────────────────────────────

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (player == null) initPlayer()
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            playWhenReady   = it.playWhenReady
            currentPosition = it.currentPosition
            it.pause()
        }
        qxController?.stop()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun releasePlayer() {
        qxController?.stop()
        qxController = null
        isQxActive = false   // reset để tránh double-start khi resume
        player?.let {
            playWhenReady   = it.playWhenReady
            currentPosition = it.currentPosition
            it.release()
        }
        player = null
    }

    // ── Fullscreen ──────────────────────────────────────────────

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }
}
