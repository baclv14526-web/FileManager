package com.filemanager.ui.viewer

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportActionBar?.hide()

        val path = intent.getStringExtra(EXTRA_PATH) ?: run { finish(); return }
        binding.tvTitle.text = File(path).name
        binding.btnBack.setOnClickListener { finish() }

        initPlayer(path)
    }

    private fun initPlayer(path: String) {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(path)))
            exo.setMediaItem(mediaItem)
            exo.playWhenReady = playWhenReady
            exo.seekTo(currentPosition)
            exo.prepare()
        }
    }

    override fun onStart() { super.onStart(); if (player == null) {
        intent.getStringExtra(EXTRA_PATH)?.let { initPlayer(it) }
    }}

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    override fun onPause() {
        super.onPause()
        player?.let {
            playWhenReady = it.playWhenReady
            currentPosition = it.currentPosition
            it.pause()
        }
    }

    override fun onStop() { super.onStop(); releasePlayer() }

    private fun releasePlayer() {
        player?.let {
            playWhenReady = it.playWhenReady
            currentPosition = it.currentPosition
            it.release()
        }
        player = null
    }
}
