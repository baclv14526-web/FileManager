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
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.filemanager.R
import com.filemanager.databinding.ActivityVideoPlayerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

class VideoPlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PATH = "extra_path"
    }

    private lateinit var binding: ActivityVideoPlayerBinding
    private var player: ExoPlayer? = null
    private var playWhenReady  = true
    private var currentPosition = 0L
    private var path = ""

    // Zoom
    private var isZoomed = false
    enum class ZoomMode(val label: String) {
        FIT("FIT"), FULL("100%"), Z150("150%"), Z200("200%"), CROP("CROP");
        fun next() = entries[(ordinal + 1) % entries.size]
    }
    private var zoomMode = ZoomMode.FIT

    // QX controller
    private var qxController: QxSpeedController? = null
    private var isQxActive = false

    // Pb controller
    private var pbController: PbPlayController? = null
    private var isPbActive = false
    private var pbCountdownJob: Job? = null

    // Speed selector — tự viết, không phụ thuộc PopupWindow ẩn của ExoPlayer
    // (nguồn gốc bug "chọn xong không mở lại được" trên một số thiết bị/Android 9)
    private val speedPresets = floatArrayOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private var currentSpeed = 1.0f

    // Audio track selector
    data class AudioTrackOption(val groupIndex: Int, val trackIndex: Int, val label: String)
    private var audioTracks: List<AudioTrackOption> = emptyList()
    private var selectedAudioTrack: AudioTrackOption? = null

    // ── Lifecycle ───────────────────────────────────────────────

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

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (player == null) initPlayer()
    }

    override fun onPause() {
        super.onPause()
        player?.let { playWhenReady = it.playWhenReady; currentPosition = it.currentPosition; it.pause() }
        qxController?.stop()
        pbController?.stop()
        pbCountdownJob?.cancel()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    private fun releasePlayer() {
        qxController?.stop(); qxController = null; isQxActive = false
        pbController?.stop();  pbController = null;  isPbActive = false
        pbCountdownJob?.cancel()
        player?.let { playWhenReady = it.playWhenReady; currentPosition = it.currentPosition; it.release() }
        player = null
    }

    // ── Player ──────────────────────────────────────────────────

    private fun initPlayer() {
        player = ExoPlayer.Builder(this).build().also { exo ->
            binding.playerView.player = exo
            applyZoom()

            exo.addListener(object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) autoRotate(size.width, size.height)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        if (isQxActive) stopQx()
                        if (isPbActive) stopPb()
                    }
                }
                // Được gọi khi track info sẵn sàng (thường ngay sau STATE_READY)
                // → cập nhật danh sách audio track + hiện/ẩn nút Audio đúng lúc
                override fun onTracksChanged(tracks: Tracks) {
                    refreshAudioTracks()
                }
            })

            exo.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
            exo.playWhenReady = playWhenReady
            exo.seekTo(currentPosition)
            exo.prepare()
        }
    }

    private fun autoRotate(w: Int, h: Int) {
        if (requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
        requestedOrientation = if (w > h) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    // ── Buttons ─────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnBack.setOnClickListener { finish() }

        binding.btnRotate.setOnClickListener {
            requestedOrientation =
                if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }

        binding.btnZoom.setOnClickListener {
            zoomMode = zoomMode.next()
            applyZoom()
            binding.tvZoomLabel.text = zoomMode.label
            binding.btnZoom.setImageResource(
                if (zoomMode == ZoomMode.FIT) R.drawable.ic_zoom_in else R.drawable.ic_zoom_out
            )
        }

        binding.btnQx.setOnClickListener { if (isPbActive) stopPb(); toggleQx() }
        binding.btnPb.setOnClickListener { if (isQxActive) stopQx(); togglePb() }

        // Nút tốc độ — luôn mở dialog mới mỗi lần tap, không giữ state ẩn
        // nên KHÔNG BAO GIỜ bị kẹt "không mở lại được" như menu settings mặc định
        binding.btnSpeed.setOnClickListener { showSpeedDialog() }
        binding.btnAudio.setOnClickListener { showAudioTrackDialog() }
    }

    // ── Speed selector ──────────────────────────────────────────

    private fun showSpeedDialog() {
        val p = player ?: return
        // Chọn speed thủ công thì tắt QX/Pb (2 mode auto-speed) để tránh xung đột
        if (isQxActive) stopQx()
        if (isPbActive) stopPb()

        val labels = speedPresets.map {
            if (it == 1.0f) "1.0x (Bình thường)" else "${it}x"
        }.toTypedArray()
        val checkedIdx = speedPresets.indexOfFirst { it == currentSpeed }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Tốc độ phát")
            .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                currentSpeed = speedPresets[which]
                p.playbackParameters = PlaybackParameters(currentSpeed)
                binding.tvSpeedLabel.text = "${currentSpeed}x".replace(".0x", "x")
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ── Audio track selector ────────────────────────────────────

    /** Quét danh sách audio track có sẵn từ Player.Tracks (Media3 API chuẩn) */
    private fun refreshAudioTracks() {
        val p = player ?: return
        val tracks = p.currentTracks
        val list = mutableListOf<AudioTrackOption>()

        tracks.groups.forEachIndexed { groupIdx, group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEachIndexed
            for (i in 0 until group.length) {
                val format = group.getTrackFormat(i)
                val lang  = format.language
                val label = when {
                    !lang.isNullOrBlank() -> "Track ${groupIdx + 1} · ${lang.uppercase()}"
                    else                  -> "Track ${groupIdx + 1}"
                }
                list.add(AudioTrackOption(groupIdx, i, label))
                if (group.isTrackSelected(i)) selectedAudioTrack = list.last()
            }
        }

        audioTracks = list
        // Chỉ hiện nút Audio nếu có từ 2 track trở lên (1 track thì không cần chọn)
        binding.btnAudio.visibility = if (list.size > 1) View.VISIBLE else View.GONE
    }

    private fun showAudioTrackDialog() {
        val p = player ?: return
        if (audioTracks.isEmpty()) {
            refreshAudioTracks()
            if (audioTracks.isEmpty()) return
        }

        val labels = audioTracks.map { it.label }.toTypedArray()
        val checkedIdx = audioTracks.indexOfFirst {
            it.groupIndex == selectedAudioTrack?.groupIndex &&
            it.trackIndex == selectedAudioTrack?.trackIndex
        }.coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle("Chọn âm thanh")
            .setSingleChoiceItems(labels, checkedIdx) { dialog, which ->
                val chosen = audioTracks[which]
                selectedAudioTrack = chosen

                val tracks = p.currentTracks
                val group  = tracks.groups.getOrNull(chosen.groupIndex)
                if (group != null) {
                    val override = TrackSelectionOverride(group.mediaTrackGroup, chosen.trackIndex)
                    p.trackSelectionParameters = p.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(override)
                        .build()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Hủy", null)
            .show()
    }

    // ── Zoom ────────────────────────────────────────────────────

    private fun applyZoom() {
        val pv = binding.playerView
        when (zoomMode) {
            ZoomMode.FIT  -> { pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT;          pv.scaleX = 1f; pv.scaleY = 1f }
            ZoomMode.FULL -> { pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM;         pv.scaleX = 1f; pv.scaleY = 1f }
            ZoomMode.Z150 -> { pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM;         pv.scaleX = 1.5f; pv.scaleY = 1.5f }
            ZoomMode.Z200 -> { pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM;         pv.scaleX = 2.0f; pv.scaleY = 2.0f }
            ZoomMode.CROP -> { pv.resizeMode = androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH;  pv.scaleX = 1f; pv.scaleY = 1f }
        }
    }

    // ── QX ──────────────────────────────────────────────────────

    private fun toggleQx() {
        isQxActive = !isQxActive
        val p = player ?: return
        if (isQxActive) {
            binding.btnQx.setImageResource(R.drawable.ic_qx_on)
            binding.qxPanel.visibility = View.VISIBLE
            qxController = QxSpeedController(p, 30_000L, 80L) { speed, phase ->
                runOnUiThread { updateQxUI(speed, phase) }
            }.also { it.start(lifecycleScope) }
        } else stopQx()
    }

    private fun stopQx() {
        isQxActive = false
        qxController?.stop(); qxController = null
        binding.btnQx.setImageResource(R.drawable.ic_qx_off)
        binding.qxPanel.visibility = View.GONE
        binding.tvQxSpeed.text = "1.0x"
        currentSpeed = 1.0f
        binding.tvSpeedLabel.text = "1.0x"
    }

    private fun updateQxUI(speed: Float, phase: Float) {
        binding.tvQxSpeed.text = String.format(Locale.US, "%.2fx", speed)
        binding.qxProgress.progress = (phase * 1000).toInt()
        val color = when {
            speed > 1.3f -> interpolateColor(0xFFFFFFFF.toInt(), 0xFFFF5722.toInt(), (speed - 1f) / 1f)
            speed < 0.8f -> interpolateColor(0xFFFFFFFF.toInt(), 0xFF29B6F6.toInt(), (1f - speed) / 0.5f)
            else -> 0xFFFFFFFF.toInt()
        }
        binding.tvQxSpeed.setTextColor(color)
    }

    // ── Pb ──────────────────────────────────────────────────────

    private fun togglePb() {
        isPbActive = !isPbActive
        val p = player ?: return
        if (isPbActive) {
            // Phải chờ player có duration (không IDLE)
            if (p.duration <= 0L) {
                isPbActive = false
                return
            }
            startPb(p)
        } else stopPb()
    }

    private fun startPb(p: ExoPlayer) {
        binding.btnPb.setImageResource(R.drawable.ic_pb_on)
        binding.pbPanel.visibility = View.VISIBLE

        pbController = PbPlayController(
            player   = p,
            onUpdate = { seg, total, posMs, allSegs ->
                runOnUiThread { updatePbUI(seg, total, posMs, allSegs) }
            },
            onFinished = {
                runOnUiThread { stopPb() }
            }
        ).also { it.start(lifecycleScope) }
    }

    private fun stopPb() {
        isPbActive = false
        pbController?.stop(); pbController = null
        pbCountdownJob?.cancel(); pbCountdownJob = null
        binding.btnPb.setImageResource(R.drawable.ic_pb_off)
        binding.pbPanel.visibility = View.GONE
        currentSpeed = 1.0f
        binding.tvSpeedLabel.text = "1.0x"
    }

    /**
     * Cập nhật toàn bộ Pb panel:
     * - "Đoạn 2/5 · 01:23"
     * - Dots: ○ ● ○ ○ ○  (vị trí hiện tại tô màu)
     * - Countdown bar 100→0 trong 30s
     * - Vị trí tính giây
     */
    private fun updatePbUI(
        seg: Int, total: Int,
        posMs: Long, allSegs: List<Long>
    ) {
        // Segment text
        val posSec = posMs / 1000
        val posStr = String.format(Locale.US, "%02d:%02d", posSec / 60, posSec % 60)
        binding.tvPbSegment.text = "Đoạn $seg/$total · $posStr"
        binding.tvPbPosition.text = "Đang phát từ $posStr · 2x speed"

        // Vẽ dots
        buildSegmentDots(seg - 1, total)

        // Countdown bar + text: đếm ngược 30s
        pbCountdownJob?.cancel()
        pbCountdownJob = lifecycleScope.launch(Dispatchers.Main.immediate) {
            val totalMs = PbPlayController.SEGMENT_REAL_MS
            val tick    = 100L
            var elapsed = 0L
            while (elapsed <= totalMs && isActive) {
                val remaining = ((totalMs - elapsed) / 1000.0).toInt()
                val progress  = ((1.0 - elapsed.toDouble() / totalMs) * 100).toInt()
                binding.tvPbCountdown.text = "${remaining}s"
                binding.pbCountdownBar.progress = progress
                delay(tick)
                elapsed += tick
            }
        }
    }

    /** Vẽ row các dot: filled = đã xem/đang xem, empty = chưa */
    private fun buildSegmentDots(currentIdx: Int, total: Int) {
        val container = binding.pbDotsContainer
        container.removeAllViews()

        val dpSize   = (resources.displayMetrics.density * 10).toInt()
        val dpMargin = (resources.displayMetrics.density * 4).toInt()
        val lineW    = (resources.displayMetrics.density * 16).toInt()
        val lineH    = (resources.displayMetrics.density * 2).toInt()

        for (i in 0 until total) {
            val dot = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpSize, dpSize).also {
                    it.gravity = android.view.Gravity.CENTER_VERTICAL
                }
                setImageResource(when {
                    i < currentIdx  -> R.drawable.dot_done
                    i == currentIdx -> R.drawable.dot_current
                    else            -> R.drawable.dot_pending
                })
            }
            container.addView(dot)

            if (i < total - 1) {
                val line = View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(lineW, lineH).also {
                        it.gravity     = android.view.Gravity.CENTER_VERTICAL
                        it.marginStart = dpMargin / 2
                        it.marginEnd   = dpMargin / 2
                    }
                    setBackgroundColor(if (i < currentIdx) 0xFF00BCD4.toInt() else 0x44FFFFFF.toInt())
                }
                container.addView(line)
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────

    private fun interpolateColor(from: Int, to: Int, ratio: Float): Int {
        val r = ratio.coerceIn(0f, 1f)
        val aF = (from shr 24) and 0xFF; val aT = (to shr 24) and 0xFF
        val rF = (from shr 16) and 0xFF; val rT = (to shr 16) and 0xFF
        val gF = (from shr 8)  and 0xFF; val gT = (to shr 8)  and 0xFF
        val bF =  from         and 0xFF; val bT =  to         and 0xFF
        return ((aF + (aT-aF)*r).toInt() shl 24) or ((rF + (rT-rF)*r).toInt() shl 16) or
               ((gF + (gT-gF)*r).toInt() shl 8)  or  (bF + (bT-bF)*r).toInt()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }
}
