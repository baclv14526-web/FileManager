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
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.filemanager.R
import com.filemanager.databinding.ActivityVideoPlayerBinding
import java.io.File

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "extra_path"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var playWhenReady = true
    private var currentPosition = 0L
    private var path = ""

    // Trạng thái zoom: FIT (vừa màn hình) ↔ ZOOM (crop full)
    private var isZoomed = false

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

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }

        // Nút xoay màn hình
        binding.btnRotate.setOnClickListener {
            requestedOrientation = if (resources.configuration.orientation
                == Configuration.ORIENTATION_LANDSCAPE
            ) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }

        // Nút zoom: FIT ↔ CROP (giữ tỉ lệ gốc, chỉ thay đổi cách fill)
        binding.btnZoom.setOnClickListener {
            isZoomed = !isZoomed
            applyResizeMode()
            binding.btnZoom.setImageResource(
                if (isZoomed) R.drawable.ic_zoom_out else R.drawable.ic_zoom_in
            )
        }
    }

    private fun applyResizeMode() {
        // RESIZE_MODE_FIT  = vừa vặn, có viền đen, giữ tỉ lệ gốc  ✅
        // RESIZE_MODE_ZOOM = fill màn hình, crop 2 cạnh, giữ tỉ lệ gốc ✅
        // RESIZE_MODE_FILL = kéo giãn, PHÁ tỉ lệ gốc ❌ — KHÔNG dùng
        binding.playerView.resizeMode = if (isZoomed)
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        else
            androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo

            // Mặc định FIT — giữ tỉ lệ gốc, không crop
            binding.playerView.resizeMode =
                androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT

            exo.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // Tự động xoay màn hình theo chiều video khi mới mở
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        autoRotateByVideoSize(videoSize.width, videoSize.height)
                    }
                }
            })

            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            exo.playWhenReady = playWhenReady
            exo.seekTo(currentPosition)
            exo.prepare()
        }
    }

    /**
     * Tự động xoay màn hình theo chiều video:
     * - Video ngang (width > height) → landscape
     * - Video dọc (height >= width)  → portrait
     * Chỉ xoay 1 lần khi video mới load (không override lựa chọn tay của người dùng)
     */
    private fun autoRotateByVideoSize(width: Int, height: Int) {
        // Nếu người dùng đã chủ động xoay thủ công → không ghi đè
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
            playWhenReady    = it.playWhenReady
            currentPosition  = it.currentPosition
            it.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun releasePlayer() {
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
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }
}

