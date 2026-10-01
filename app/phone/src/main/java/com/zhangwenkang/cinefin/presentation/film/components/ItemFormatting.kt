package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidShow

/**
 * 影视条目的文字格式化：首页主视觉、走廊卡片、详情页共用同一套口径。
 *
 * 全部走"短标签 + `·` 分隔"，不再拼长句；时间码统一 `MonoData`（§3.3 排版纪律）。
 */
internal fun FindroidItem.productionYearOrNull(): Int? =
    when (this) {
        is FindroidMovie -> productionYear
        is FindroidShow -> productionYear
        else -> null
    }

internal fun FindroidItem.officialRatingOrNull(): String? =
    when (this) {
        is FindroidMovie -> officialRating
        is FindroidShow -> officialRating
        else -> null
    }

internal fun FindroidItem.genreList(): List<String> =
    when (this) {
        is FindroidMovie -> genres
        is FindroidShow -> genres
        else -> emptyList()
    }

internal fun FindroidItem.runtimeMinutes(): Int? =
    if (runtimeTicks > 0) (runtimeTicks / 600_000_000L).toInt().takeIf { it > 0 } else null

/** 片长：`24:02` / `1:23:00`（等宽字体，避免跳动）。 */
internal fun FindroidItem.runtimeLabel(): String? {
    if (runtimeTicks <= 0) return null
    val totalSeconds = runtimeTicks / 10_000_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** 剩余分钟：没有播放进度时为 null。 */
internal fun FindroidItem.remainingMinutes(): Int? {
    if (runtimeTicks <= 0 || playbackPositionTicks <= 0) return null
    val remaining = ((runtimeTicks - playbackPositionTicks).coerceAtLeast(0) / 600_000_000L).toInt()
    return remaining.takeIf { it > 0 }
}

/** 集号：`S1 E1`（只有剧集才有）。 */
internal fun FindroidItem.episodeCode(): String? =
    if (this is FindroidEpisode) "S$this.parentIndexNumber E$this.indexNumber" else null

/** 元信息行：`2026 · TV-14 · 24 分钟`。 */
@Composable
internal fun FindroidItem.metaLine(): String? {
    val parts = buildList {
        productionYearOrNull()?.let { add(it.toString()) }
        officialRatingOrNull()?.let { add(it) }
        runtimeMinutes()?.let { add(stringResource(CoreR.string.runtime_minutes, it)) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

/** 详情页眉标：`动漫 · 剧场版 · 2026` 这类"我在看什么"的一行说明。 */
@Composable
internal fun FindroidItem.detailEyebrow(extra: String? = null): String? {
    val parts = buildList {
        extra?.takeIf { it.isNotBlank() }?.let { add(it) }
        genreList().firstOrNull()?.let { add(it) }
        productionYearOrNull()?.let { add(it.toString()) }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}
