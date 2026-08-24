package com.filemanager.ui.viewer

import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * PbPlayController — Preview Browse Mode
 *
 * Tự động nhảy đến các vị trí đặc trưng trong video và phát 30 giây tốc độ 2x:
 *
 *  ≤ 60s      → 1 vị trí (giữa video)
 *  60s–3min   → 2 vị trí (chia đều)
 *  3min–10min → 5 vị trí (chia đều)
 *  > 10min    → 10 vị trí (chia đều)
 *
 * Mỗi vị trí là trung điểm của đoạn tương ứng → đại diện tốt nhất cho nội dung.
 * Phát 30 giây thực (real-time) ở tốc độ 2x = 60 giây nội dung video mỗi đoạn.
 */
class PbPlayController(
    private val player: ExoPlayer,
    private val onUpdate: (segment: Int, totalSegments: Int, positionMs: Long, segments: List<Long>) -> Unit,
    private val onFinished: () -> Unit
) {
    companion object {
        const val SEGMENT_REAL_MS = 30_000L   // 30 giây thực mỗi đoạn
        const val SPEED           = 2.0f       // tốc độ phát
        const val TICK_MS         = 200L       // tick kiểm tra mỗi 200ms
    }

    private var job: Job? = null
    var isActive = false
        private set

    // Danh sách vị trí (ms) sẽ được tính sau khi biết duration
    private var segments: List<Long> = emptyList()

    // ── Public API ──────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        if (isActive) return

        val duration = player.duration
        if (duration <= 0L) return

        segments = calculateSegments(duration)
        isActive = true

        job = scope.launch(Dispatchers.Main.immediate) {
            segments.forEachIndexed { index, posMs ->
                if (!isActive) return@launch

                // Seek đến vị trí + bật tốc độ 2x
                player.seekTo(posMs)
                player.playWhenReady = true
                player.playbackParameters = PlaybackParameters(SPEED)
                onUpdate(index + 1, segments.size, posMs, segments)

                // Phát 30 giây thực: đếm bằng tick thay vì delay() đơn lẻ
                // để có thể bị cancel sạch hơn
                var elapsed = 0L
                while (elapsed < SEGMENT_REAL_MS && isActive) {
                    delay(TICK_MS)
                    elapsed += TICK_MS
                    // Nếu video đã kết thúc sớm → thoát luôn
                    if (player.playbackState == Player.STATE_ENDED) {
                        stop()
                        onFinished()
                        return@launch
                    }
                }
            }
            // Xong tất cả đoạn
            if (isActive) {
                stop()
                onFinished()
            }
        }
    }

    fun stop() {
        isActive = false
        job?.cancel()
        job = null
        player.playbackParameters = PlaybackParameters(1.0f)
    }

    // ── Segment calculation ─────────────────────────────────────

    /**
     * Tính danh sách vị trí (ms) cần nhảy đến.
     * Mỗi vị trí là trung điểm của đoạn (i+0.5)/n × duration.
     *
     * n=1  (≤60s):      [D/2]
     * n=2  (60s–3min):  [D/4,  3D/4]
     * n=5  (3–10min):   [D/10, 3D/10, D/2, 7D/10, 9D/10]
     * n=10 (>10min):    [D/20, 3D/20, …, 19D/20]
     */
    fun calculateSegments(durationMs: Long): List<Long> {
        val durationSec = durationMs / 1000.0
        val n = when {
            durationSec <= 60.0  -> 1
            durationSec <= 180.0 -> 2
            durationSec <= 600.0 -> 5
            else                 -> 10
        }
        return (0 until n).map { i ->
            durationMs * (2L * i + 1L) / (2L * n)
        }
    }

    fun getSegments() = segments
}
