package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow

/**
 * W8-T3'：卡片评分徽标文案（纯函数，便于单测）。
 *
 * 口径唯一：复用 [communityRatingText]（「★ x.x」一位小数，null 不渲染）。类型范围按设计定稿： Movie / Episode / Show /
 * Season；其余类型（合集 / 文件夹 / 书籍 / 音乐）一律不显示。
 */
fun communityRatingBadgeText(item: FindroidItem): String? =
    when (item) {
        is FindroidMovie -> communityRatingText(item.communityRating)
        is FindroidEpisode -> communityRatingText(item.communityRating)
        is FindroidShow -> communityRatingText(item.communityRating)
        is FindroidSeason -> communityRatingText(item.communityRating)
        else -> null
    }

/**
 * 2.0.3-C：评分徽标的可见文案——[showRating] = false 时一律不渲染（首页视频卡专用）。
 *
 * 首页要「图说一切」，评分压在每张海报上会让整面墙变吵；详情页 / 库页保持显示。
 */
fun communityRatingBadgeText(item: FindroidItem, showRating: Boolean): String? =
    if (showRating) communityRatingBadgeText(item) else null

/**
 * 评分徽标——设计定稿位置：海报**左下角**（进度条上方，与右上角标区不冲突）。
 *
 * 形态复用 [BaseBadge]（§8.9 的形状、留白与描边）；配色按 2.0.1 定稿：极光淡色 pill = `aurora1` 12% 底（批①，AUDIT-17）+
 * `auroraInk` 文字（批③ 专项裁定：浅色档 #0A6B5A 达 AA，深色档即 `aurora1`）。黄铜（TV 专用的 `ColorDark.tertiaryDark`）退出
 * phone。
 */
@Composable
fun BoxScope.CommunityRatingBadge(
    item: FindroidItem,
    modifier: Modifier = Modifier,
    cornerPadding: Dp = CinefinSpacing.Space3,
    /** 底部额外抬升（横版卡标题块 / 卡片进度条占位时用），默认贴着海报左下角。 */
    bottomInset: Dp = 0.dp,
    /** 2.0.3-C：首页视频卡传 false 隐藏评分徽标；默认 true = 既有行为。 */
    showRating: Boolean = true,
) {
    val text = communityRatingBadgeText(item, showRating) ?: return
    val colors = LocalCinefinColors.current
    BaseBadge(
        modifier =
            modifier
                .align(Alignment.BottomStart)
                .padding(start = cornerPadding, bottom = cornerPadding + bottomInset),
        container = colors.aurora1.copy(alpha = CinefinTokens.RatingBadgeAlpha),
    ) {
        Text(
            text = text,
            style = CinefinType.LabelSmall,
            color = colors.auroraInk,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}
