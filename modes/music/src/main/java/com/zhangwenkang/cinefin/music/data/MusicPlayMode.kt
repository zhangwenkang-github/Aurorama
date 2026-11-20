package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode
import com.zhangwenkang.cinefin.player.core.domain.models.ShuffleMode

/**
 * 音乐播放模式（W23-MUSIC · C 组；W5 T5 扩随机双子模式）。
 *
 * 与 `player:core` 冻结的 [RepeatMode] + `shuffleEnabled` / `shuffleKeepCurrentFirst`
 * 是**多对一**关系：五种模式映射到
 * 内核的三个开关组合上，队列状态（[com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue]） 继续由既有字段承载，排队持久化
 * / gapless / 上报链路都不用改。
 *
 * 随机的两种模式（MUSIC-12）：
 * - [SHUFFLE] 随机整个队列；
 * - [SHUFFLE_CURRENT_FIRST] 随机并保持当前曲目居首（当前曲固定后洗牌其余）。
 *
 * 随机播放走内核的 shuffle（`DefaultShuffleOrder`：一轮内每首恰好播放一次 = "未播优先"）， "上一曲回历史"由 [MusicPlaybackHistory]
 * 在显示层给出目标索引。
 */
enum class MusicPlayMode(val label: String) {
    /** 顺序播放：播完最后一首停止。 */
    SEQUENTIAL("顺序播放"),

    /** 列表循环：播完最后一首回到第一首。 */
    LIST_LOOP("列表循环"),

    /** 单曲循环：同一首反复播放。 */
    SINGLE_LOOP("单曲循环"),

    /** 随机播放：随机整个队列。 */
    SHUFFLE("随机播放"),

    /** 随机播放：当前曲目固定在随机序首位，其余洗牌。 */
    SHUFFLE_CURRENT_FIRST("随机（当前居首）"),
}

/** 是否处于随机（两种随机模式都算）。 */
val MusicPlayMode.isShuffle: Boolean
    get() = this == MusicPlayMode.SHUFFLE || this == MusicPlayMode.SHUFFLE_CURRENT_FIRST

/** 模式 → 内核重复模式（随机播放要能一轮接一轮地继续，所以用 ALL）。 */
fun MusicPlayMode.toRepeatMode(): RepeatMode =
    when (this) {
        MusicPlayMode.SEQUENTIAL -> RepeatMode.OFF
        MusicPlayMode.LIST_LOOP -> RepeatMode.ALL
        MusicPlayMode.SINGLE_LOOP -> RepeatMode.ONE
        MusicPlayMode.SHUFFLE -> RepeatMode.ALL
        MusicPlayMode.SHUFFLE_CURRENT_FIRST -> RepeatMode.ALL
    }

/** 模式 → 内核 shuffle 开关。 */
fun MusicPlayMode.toShuffleEnabled(): Boolean = isShuffle

/** 模式 → 随机子模式（关闭随机时为 [ShuffleMode.OFF]）。 */
fun MusicPlayMode.toShuffleMode(): ShuffleMode =
    when (this) {
        MusicPlayMode.SEQUENTIAL,
        MusicPlayMode.LIST_LOOP,
        MusicPlayMode.SINGLE_LOOP -> ShuffleMode.OFF
        MusicPlayMode.SHUFFLE -> ShuffleMode.ALL
        MusicPlayMode.SHUFFLE_CURRENT_FIRST -> ShuffleMode.KEEP_CURRENT_FIRST
    }

/** 内核状态 → 五种模式（队列 / 恢复快照里读回，UI 与单测共用同一口径）。 */
fun musicPlayModeOf(
    repeatMode: RepeatMode,
    shuffleEnabled: Boolean,
    shuffleKeepCurrentFirst: Boolean = false,
): MusicPlayMode =
    when {
        shuffleEnabled && shuffleKeepCurrentFirst -> MusicPlayMode.SHUFFLE_CURRENT_FIRST
        shuffleEnabled -> MusicPlayMode.SHUFFLE
        repeatMode == RepeatMode.ONE -> MusicPlayMode.SINGLE_LOOP
        repeatMode == RepeatMode.ALL -> MusicPlayMode.LIST_LOOP
        else -> MusicPlayMode.SEQUENTIAL
    }

/** 图标循环切换顺序：顺序 → 列表循环 → 单曲循环 → 随机 → 随机（当前居首）→ 顺序。 */
fun MusicPlayMode.next(): MusicPlayMode =
    when (this) {
        MusicPlayMode.SEQUENTIAL -> MusicPlayMode.LIST_LOOP
        MusicPlayMode.LIST_LOOP -> MusicPlayMode.SINGLE_LOOP
        MusicPlayMode.SINGLE_LOOP -> MusicPlayMode.SHUFFLE
        MusicPlayMode.SHUFFLE -> MusicPlayMode.SHUFFLE_CURRENT_FIRST
        MusicPlayMode.SHUFFLE_CURRENT_FIRST -> MusicPlayMode.SEQUENTIAL
    }
