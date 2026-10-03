package com.zhangwenkang.cinefin.player.core.domain.models

/**
 * 睡眠定时规格（W55）：音乐 / 视频共享同一口径。
 *
 * 用户 2026-10-04 拍板：音乐 / 视频用同一实现 + 自定义 1–240 分钟。 预设档位保留 W12 / W21 已验收的 10 / 20 / 30 / 60，自定义兜住 1–240
 * 的任意整分钟；本对象只放纯逻辑，方便单测（`SleepTimerTest`）。
 */
object SleepTimerSpec {
    /** 自定义下限（分钟）。 */
    const val MIN_MINUTES = 1

    /** 自定义上限（分钟）。 */
    const val MAX_MINUTES = 240

    /** 预设档位（分钟），与播放器面板 / 音乐面板既有档位一致。 */
    val PRESET_MINUTES = listOf(10, 20, 30, 60)

    /** 把分钟收敛到 1–240；仅用于 [select][SleepTimerStateMachine.select] 的正数分支。 */
    fun clampMinutes(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    /** 分钟 → 毫秒（先收敛再换算，>240 按 240 处理）。 */
    fun totalMs(minutes: Int): Long = clampMinutes(minutes) * 60_000L

    /** 到点前剩余毫秒（过期按 0，不返回负数）。 */
    fun remainingMs(deadlineMs: Long, nowMs: Long): Long = (deadlineMs - nowMs).coerceAtLeast(0L)

    /** 到点判定：`now >= deadline` 即到点。 */
    fun isExpired(deadlineMs: Long, nowMs: Long): Boolean = nowMs >= deadlineMs

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
 * 睡眠定时纯状态机（W55）：把「选择 / 取消 / 每秒推进」做成无副作用逻辑，计时器与单测共用。
 *
 * 语义：
 * - `select(null)` / `select(0)` / `select(负数)` = 取消定时（与 W21 音乐侧现状一致）；
 * - `select(正数)` = 重置倒计时，分钟收敛到 1–240；
 * - `tick(now)` 到点时把状态清理为空，并只报告一次 `expired = true`（避免重复触发暂停）。
 */
class SleepTimerStateMachine {
    data class Snapshot(val minutes: Int? = null, val deadlineMs: Long = 0L) {
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
