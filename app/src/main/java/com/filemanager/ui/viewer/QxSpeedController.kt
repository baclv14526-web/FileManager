package com.filemanager.ui.viewer

import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * QX Speed Controller
 *
 * Tốc độ phát thay đổi tuần hoàn theo đường cong Quartic S-Curve:
 *   2x ──► 1x ──► 0.5x ──► 1x ──► 2x ──► ... (lặp lại)
 *
 * Mỗi đoạn chuyển tiếp giữa 2 keyframe dùng easeInOutQuartic:
 *   - Gần keyframe: thay đổi rất chậm (cảm giác "đứng yên" ở tốc độ đó)
 *   - Giữa đoạn:    thay đổi nhanh   (chuyển tiếp mượt mà rõ rệt)
 *
 * @param cycleDurationMs  Thời gian thực để hoàn thành 1 chu kỳ (ms). Mặc định 30s.
 * @param tickIntervalMs   Tần suất cập nhật tốc độ (ms). Mặc định 100ms.
 */
class QxSpeedController(
    private val player: ExoPlayer,
    private val cycleDurationMs: Long = 30_000L,
    private val tickIntervalMs: Long  = 100L,
    private val onSpeedChanged: (speed: Float, phase: Float) -> Unit = { _, _ -> }
) {
    // Keyframe speeds: 2x → 1x → 0.5x → 1x → 2x
    private val keyframes = floatArrayOf(2.0f, 1.0f, 0.5f, 1.0f, 2.0f)

    private var job: Job? = null
    private var elapsedMs = 0L
    var isActive = false
        private set

    // ── Public API ──────────────────────────────────────────────

    fun start(scope: CoroutineScope) {
        if (isActive) return
        isActive = true
        job = scope.launch {
            while (isActive && this.isActive) {
                tick()
                delay(tickIntervalMs)
            }
        }
    }

    fun stop() {
        isActive = false
        job?.cancel()
        job = null
        // Khôi phục tốc độ bình thường
        player.playbackParameters = PlaybackParameters(1.0f)
        onSpeedChanged(1.0f, 0f)
    }

    fun reset() {
        elapsedMs = 0L
    }

    // ── Core ────────────────────────────────────────────────────

    private fun tick() {
        elapsedMs += tickIntervalMs

        // phase: 0.0 → 1.0, tuần hoàn theo cycleDurationMs
        val phase = (elapsedMs % cycleDurationMs).toFloat() / cycleDurationMs

        val speed = speedAtPhase(phase)
        player.playbackParameters = PlaybackParameters(speed)
        onSpeedChanged(speed, phase)
    }

    /**
     * Tính tốc độ tại một vị trí pha [0, 1] trong chu kỳ.
     *
     * Dùng easeInOutQuartic để nội suy giữa các keyframe:
     *   seg 0: phase 0.00–0.25 → 2.0x → 1.0x
     *   seg 1: phase 0.25–0.50 → 1.0x → 0.5x
     *   seg 2: phase 0.50–0.75 → 0.5x → 1.0x
     *   seg 3: phase 0.75–1.00 → 1.0x → 2.0x
     */
    fun speedAtPhase(phase: Float): Float {
        val numSegments = keyframes.size - 1          // = 4
        val pos         = phase * numSegments         // 0..4
        val segment     = pos.toInt().coerceIn(0, numSegments - 1)
        val t           = pos - segment               // 0..1 trong đoạn

        val from = keyframes[segment]
        val to   = keyframes[segment + 1]
        return from + (to - from) * easeInOutQuartic(t)
    }

    // ── Quartic S-Curve ─────────────────────────────────────────

    /**
     * Hàm Quartic Ease-In-Out (bậc 4):
     *   f(t) = 8t⁴                   (t < 0.5)
     *   f(t) = 1 − 8(1−t)⁴          (t ≥ 0.5)
     *
     * Đặc tính:
     *   - Đạo hàm tại t=0 và t=1: 0  (dừng/chậm ở 2 đầu)
     *   - Đạo hàm tại t=0.5:     1  (nhanh nhất ở giữa)
     *   - Tạo hình chữ S rõ rệt hơn hàm bậc 2/3
     */
    private fun easeInOutQuartic(t: Float): Float {
        val tc = t.coerceIn(0f, 1f)
        return if (tc < 0.5f) {
            8f * tc * tc * tc * tc
        } else {
            val u = 1f - tc
            1f - 8f * u * u * u * u
        }
    }

    
}
