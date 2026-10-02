package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.player.core.domain.models.RepeatMode

/**
 * 音乐播放模式（W23-MUSIC · C 组，用户确认的四种）。
 *
 * 与 `player:core` 冻结的 [RepeatMode] + `shuffleEnabled` 是**多对一**关系：四种模式映射到
 * 内核的三个开关组合上，队列状态（[com.zhangwenkang.cinefin.player.core.domain.models.MusicQueue]） 继续由既有字段承载，排队持久化
 * / gapless / 上报链路都不用改。
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

    /** 随机播放：下一首按随机顺序（未播优先），上一首回播放历史。 */
    SHUFFLE("随机播放"),
}

/** 模式 → 内核重复模式（随机播放要能一轮接一轮地继续，所以用 ALL）。 */
fun MusicPlayMode.toRepeatMode(): RepeatMode =
    when (this) {
        MusicPlayMode.SEQUENTIAL -> RepeatMode.OFF
        MusicPlayMode.LIST_LOOP -> RepeatMode.ALL
        MusicPlayMode.SINGLE_LOOP -> RepeatMode.ONE
        MusicPlayMode.SHUFFLE -> RepeatMode.ALL
    }

/** 模式 → 内核 shuffle 开关。 */
fun MusicPlayMode.toShuffleEnabled(): Boolean = this == MusicPlayMode.SHUFFLE

/** 内核状态 → 四种模式（队列 / 恢复快照里读回，UI 与单测共用同一口径）。 */
fun musicPlayModeOf(repeatMode: RepeatMode, shuffleEnabled: Boolean): MusicPlayMode =
    when {
        shuffleEnabled -> MusicPlayMode.SHUFFLE
        repeatMode == RepeatMode.ONE -> MusicPlayMode.SINGLE_LOOP
        repeatMode == RepeatMode.ALL -> MusicPlayMode.LIST_LOOP
        else -> MusicPlayMode.SEQUENTIAL
    }

/** 图标循环切换顺序：顺序 → 列表循环 → 单曲循环 → 随机 → 顺序。 */
fun MusicPlayMode.next(): MusicPlayMode =
    when (this) {
        MusicPlayMode.SEQUENTIAL -> MusicPlayMode.LIST_LOOP
        MusicPlayMode.LIST_LOOP -> MusicPlayMode.SINGLE_LOOP
        MusicPlayMode.SINGLE_LOOP -> MusicPlayMode.SHUFFLE
        MusicPlayMode.SHUFFLE -> MusicPlayMode.SEQUENTIAL
    }
