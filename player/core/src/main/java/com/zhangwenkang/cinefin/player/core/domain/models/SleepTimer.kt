package com.zhangwenkang.cinefin.player.core.domain.models

import kotlin.math.PI
import kotlin.math.sin

/**
 * 睡眠定时规格（W55；W5-T6 改档位并补「播完当前曲目停止」+ 结束前淡出）：音乐 / 视频共享同一口径。
 *
 * 用户 2026-10-04 拍板：音乐 / 视频用同一实现 + 自定义 1–240 分钟。W5-T6 起预设档位按 2.0 需求账本改为 15 / 30 / 45 / 60 /
 * 90（**取代** W55 的 10 / 20 / 30 / 60），自定义仍兜住 1–240 的任意整分钟； 到点前 [FADE_OUT_MS] 用 `Player.volume`
 * 包络淡出（与音乐交叉淡化同一等功率曲线手法）。
 *
 * 本对象只放纯逻辑，方便单测（`SleepTimerTest`）。
 */
object SleepTimerSpec {
    /** 自定义下限（分钟）。 */
    const val MIN_MINUTES = 1

    /** 自定义上限（分钟）。 */
    const val MAX_MINUTES = 240

    /** 预设档位（分钟）：W5-T6 以需求账本为准（取代 W55 的 10/20/30/60）。 */
    val PRESET_MINUTES = listOf(15, 30, 45, 60, 90)

    /** 到点前淡出窗口（毫秒）。 */
    const val FADE_OUT_MS = 5_000L

    /** 淡出窗口内的音量采样间隔（毫秒）：100ms 够平滑，也不至于常驻高频唤醒。 */
    const val FADE_TICK_MS = 100L

    /** 淡出窗口外的计时轮询间隔（毫秒）。 */
    const val IDLE_TICK_MS = 1_000L

    /** 把分钟收敛到 1–240；仅用于 [select][SleepTimerStateMachine.select] 的正数分支。 */
    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** 分钟 → 毫秒（先收敛再换算，>240 按 240 处理）。 */
    fun totalMs(minutes: Int): Long = clampMinutes(minutes) * 60_000L

    /** 到点前剩余毫秒（过期按 0，不返回负数）。 */
    fun remainingMs(deadlineMs: Long, nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L)

    /** 到点判定：`now >= deadline` 即到点。 */
    fun isExpired(deadlineMs: Long, nowMs: Long): Boolean = nowMs >= deadlineMs

    /** 是否处于结束前淡出窗口（剩余 0–5000ms，含两端）。 */
    fun fadeActive(remainingMs: Long): Boolean = remainingMs in 0..FADE_OUT_MS

    /**
     * 淡出增益：剩余 ≥ [FADE_OUT_MS] → 1，剩余 0 → 0；等功率（sin）曲线，避免线性曲线中途响度塌陷。
     *
     * 与音乐交叉淡化的 `MusicCrossfadeMath.fadeOutGain` 同曲线（跨模块不能互相依赖，这里按同一公式实现）。
     */
    fun fadeOutGain(remainingMs: Long): Float {
        if (remainingMs >= FADE_OUT_MS) return 1f
        if (remainingMs <= 0L) return 0f
        return sin(PI / 2.0 * remainingMs.toDouble() / FADE_OUT_MS).toFloat()
    }

    /** 计时轮询间隔：进入淡出窗口后降到 100ms，保证到点暂停与音量曲线贴合。 */
    fun tickIntervalMs(remainingMs: Long): Long =
        if (fadeActive(remainingMs)) FADE_TICK_MS else IDLE_TICK_MS

    /**
     * 剩余时间文案（mm:ss；向上取整到秒，避免最后一秒显示 00:00 仍在播）。
     *
     * 上限 240 分钟时输出 `240:00`（分钟位不截断）。
     */
    fun formatRemaining(remainingMs: Long): String {
        val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
        return "%02d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
    }
}

/**
 * 睡眠定时纯状态机（W55；W5-T6 补「播完当前曲目停止」）：把「选择 / 取消 / 每秒推进」做成无副作用逻辑，计时器与单测共用。
 *
 * 语义：
 * - `select(null)` / `select(0)` / `select(负数)` = 取消定时（与 W21 音乐侧现状一致）；
 * - `select(正数)` = 重置倒计时，分钟收敛到 1–240，并清掉「播完当前曲目停止」；
 * - `selectStopAfterCurrentTrack()` = 取消分钟计时，挂起「播完当前曲目停止」；
 * - `consumeStopAfterCurrentTrack()` = 播放链在 `onMediaItemTransition(AUTO)` 时消费一次（只触发一次）；
 * - `tick(now)` 到点时把状态清理为空，并只报告一次 `expired = true`（避免重复触发暂停）。
 */
class SleepTimerStateMachine {
    data class Snapshot(
        val minutes: Int? = null,
        val deadlineMs: Long = 0L,
        val stopAfterCurrentTrack: Boolean = false,
    ) {
        val active: Boolean
            get() = minutes != null
    }

    data class Tick(val snapshot: Snapshot, val expired: Boolean)

    private var snapshot = Snapshot()

    val current: Snapshot
        get() = snapshot

    fun select(minutes: Int?, nowMs: Long): Snapshot {
        snapshot =
            if (minutes == null || minutes <= 0) {
                Snapshot()
            } else {
                Snapshot(
                    minutes = SleepTimerSpec.clampMinutes(minutes),
                    deadlineMs = nowMs + SleepTimerSpec.totalMs(minutes),
                )
            }
        return snapshot
    }

    /** 挂起「播完当前曲目停止」（暂停挂起，等 AUTO 切歌后由播放链暂停）。 */
    fun selectStopAfterCurrentTrack(): Snapshot {
        snapshot = Snapshot(stopAfterCurrentTrack = true)
        return snapshot
    }

    /** 消费「播完当前曲目停止」：只返回一次 true，并清空状态。 */
    fun consumeStopAfterCurrentTrack(): Boolean {
        if (!snapshot.stopAfterCurrentTrack) return false
        snapshot = Snapshot()
        return true
    }

    /** 用户主动停止音乐 / 关闭音乐会话：只清「播完当前曲目停止」，分钟计时保持既有语义。 */
    fun clearStopAfterCurrentTrack(): Snapshot {
        if (snapshot.stopAfterCurrentTrack) snapshot = snapshot.copy(stopAfterCurrentTrack = false)
        return snapshot
    }

    fun cancel(): Snapshot {
        snapshot = Snapshot()
        return snapshot
    }

    fun tick(nowMs: Long): Tick {
        val current = snapshot
        if (!current.active) return Tick(current, expired = false)
        if (SleepTimerSpec.isExpired(current.deadlineMs, nowMs)) {
            snapshot = Snapshot()
            return Tick(snapshot, expired = true)
        }
        return Tick(current, expired = false)
    }
}
