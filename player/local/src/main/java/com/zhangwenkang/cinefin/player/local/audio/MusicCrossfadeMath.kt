package com.zhangwenkang.cinefin.player.local.audio

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 交叉淡化近似方案（W30-MUSIC-FX）的增益曲线（纯函数，可 JVM 单测）。
 *
 * 实现形态 = **曲尾淡出 + 曲首淡入**（单实例、无重叠的"淡接"）：
 * - 真实双实例交叉淡化（旧曲渐弱的同时新曲渐强、两音轨重叠）会破坏单 ExoPlayer 实例 + 单 MediaSession + gapless 的既有链路，
 *   本波不采用（文档已写明分阶段结论）；
 * - 本曲线与 gapless 不冲突：内核仍无缝衔接下一首，音量包络由 `Player.volume` 驱动；
 * - 等功率曲线（sin）避免线性曲线在淡出中途的心理响度塌陷。
 *
 * W5-T7 · MUSIC-06：时长 0–12s 连续可调（1s 步进）；只有队列自然推进进入的曲目才淡化（[eligible]）； 过短的曲目整曲不淡化。
 */
object MusicCrossfadeMath {

    /** 时长上限（秒）：0 = 关闭，1–[CROSSFADE_MAX_SECONDS] 连续可调。 */
    const val CROSSFADE_MAX_SECONDS = 12

    /** 短曲阈值下限：曲长短于 max(此值, 3 × 淡化时长) 时整曲不淡化。 */
    const val CROSSFADE_MIN_TRACK_MS = 20_000L

    /** 旧版档位偏好（0 / 2 / 4 / 6）天然落在区间内，无需迁移；越界输入夹到 [0, 12]。 */
    fun sanitizeCrossfadeSeconds(seconds: Int): Int = seconds.coerceIn(0, CROSSFADE_MAX_SECONDS)

    /** 淡入增益：position=0 → 0，position≥fadeMs → 1；等功率曲线。 */
    fun fadeInGain(positionMs: Long, fadeMs: Long): Float {
        if (fadeMs <= 0L) return 1f
        if (positionMs <= 0L) return 0f
        if (positionMs >= fadeMs) return 1f
        return sin(PI / 2.0 * positionMs.toDouble() / fadeMs).toFloat()
    }

    /** 淡出增益：remaining≥fadeMs → 1，remaining=0 → 0；与淡入同曲线（对称）。 */
    fun fadeOutGain(remainingMs: Long, fadeMs: Long): Float = fadeInGain(remainingMs, fadeMs)

    /** 曲目时长是否允许淡化：短曲两头窗口挤在一起听感像"整曲被压扁"，直接跳过。 */
    fun isFadeableTrack(durationMs: Long, fadeMs: Long): Boolean =
        fadeMs > 0L && durationMs >= max(CROSSFADE_MIN_TRACK_MS, 3 * fadeMs)

    /**
     * 当前位置的目标音量：取"曲首淡入"与"曲尾淡出"的较小值。
     *
     * 未开启（fadeMs≤0）、时长未知（≤0）、本曲无淡化资格（[eligible] = false）或短曲时恒为 1。
     */
    fun crossfadeVolume(
        positionMs: Long,
        durationMs: Long,
        fadeMs: Long,
        eligible: Boolean,
    ): Float {
        if (!eligible || fadeMs <= 0L || durationMs <= 0L) return 1f
        if (!isFadeableTrack(durationMs, fadeMs)) return 1f
        val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
        return min(fadeInGain(positionMs, fadeMs), fadeOutGain(remainingMs, fadeMs))
    }
}
