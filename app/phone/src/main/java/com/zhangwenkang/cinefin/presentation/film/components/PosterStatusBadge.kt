package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow

/**
 * 封面状态徽标（下载徽标之外的那一个）：
 *
 * - **容器类（Series / Season / 文件夹）**：未看条目数 `>0` 显示数字；**全部看完显示打勾**；
 * - **单片类（Movie / Episode）**：服务器只给 `UserData.Played`——已看打勾、未看不加角标；
 * - 其他（合集 / 相册等本波未纳入的类型）：不显示。
 *
 * W73（#4）口径变更：容器类「全部看完」由 [PosterStatusBadge.None] 改为打勾——用户 2026-10-06 「季里每集看完，季没有勾」。W56 的「0
 * 就不显示」作废（当时按官方容器只显示未看数理解）。
 *
 * 依据：官方 OpenAPI `UserItemDataDto.UnplayedItemCount` 仅由容器类条目的 `UserData` 返回（2026-10-03 对 服务器 10.11.8
 * 的只读探针实测：Series / Season 有值，Movie / Episode / 库视图恒为空）。
 */
sealed interface PosterStatusBadge {
    /** 容器类：未看条目数（`>0`）。 */
    data class UnplayedCount(val count: Int) : PosterStatusBadge

    /** 单片类：已看完。 */
    data object Played : PosterStatusBadge

    /** 不显示任何状态徽标。 */
    data object None : PosterStatusBadge
}

/** 纯函数：条目 → 状态徽标（类型 + 用户数据口径的唯一真相）。 */
internal fun FindroidItem.posterStatusBadge(): PosterStatusBadge =
    when (this) {
        is FindroidShow,
        is FindroidSeason,
        is FindroidFolder ->
            containerStatusBadge(played = played, unplayedItemCount = unplayedItemCount)
        is FindroidMovie,
        is FindroidEpisode -> if (played) PosterStatusBadge.Played else PosterStatusBadge.None
        else -> PosterStatusBadge.None
    }

/**
 * 容器类（剧集 / 季 / 文件夹）的徽标口径（抽成纯函数便于单测）：
 *
 * 1. 还有未看条目 → 未看数（服务器的 `UnplayedItemCount` 比 `Played` 更新）；
 * 2. 计数为 0（服务器已统计过、没有未看子项）或服务器直接给了已看标记 → 打勾；
 * 3. 计数缺省且未标记已看（如离线数据）→ 不显示。
 */
internal fun containerStatusBadge(played: Boolean, unplayedItemCount: Int?): PosterStatusBadge {
    val unplayed = unplayedItemCount ?: 0
    return when {
        unplayed > 0 -> PosterStatusBadge.UnplayedCount(unplayed)
        played || unplayedItemCount == 0 -> PosterStatusBadge.Played
        else -> PosterStatusBadge.None
    }
}

/** 未看数量文案：>99 收敛为 `99+`，避免长数字挤爆封面小胶囊。 */
internal fun unplayedItemCountText(count: Int): String = if (count > 99) "99+" else count.toString()

/** 按口径渲染封面状态徽标；[PosterStatusBadge.None] 时什么都不画。 */
@Composable
fun ItemStatusBadge(item: FindroidItem, modifier: Modifier = Modifier) {
    when (val badge = item.posterStatusBadge()) {
        is PosterStatusBadge.UnplayedCount -> ItemCountBadge(badge.count, modifier)
        PosterStatusBadge.Played -> PlayedBadge(modifier)
        PosterStatusBadge.None -> Unit
    }
}
