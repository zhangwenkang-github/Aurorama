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
 * 封面状态徽标（下载徽标之外的那一个）——口径与官方 Jellyfin 一致：
 *
 * - **容器类（Series / Season / 文件夹）**：显示未看条目数，`>0` 才显示；完全看完（计数为 0 或缺省）不显示任何徽标；
 * - **单片类（Movie / Episode）**：服务器只给 `UserData.Played`——已看打勾、未看不加角标；
 * - 其他（合集 / 相册等本波未纳入的类型）：不显示。
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
            unplayedItemCount?.takeIf { it > 0 }?.let { PosterStatusBadge.UnplayedCount(it) }
                ?: PosterStatusBadge.None
        is FindroidMovie,
        is FindroidEpisode -> if (played) PosterStatusBadge.Played else PosterStatusBadge.None
        else -> PosterStatusBadge.None
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
