package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.ColorDark
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
 * 评分徽标——设计定稿位置：海报**左下角**（进度条上方，与右上角标区不冲突）。
 *
 * 形态复用 [BaseBadge]（§8.9 中性黑底 + 白 12% 描边）；文字色为设计指定的评分/徽标黄铜 token （`Color.kt:68` 的
 * `ColorDark.tertiaryDark`）。phone 的 [CinefinColors] 未暴露该槽位（M3 `tertiary` 是内容域媒体色）， 因此按定稿直接引用该既有
 * token，不新增任何色值；徽标底恒为黑，暗色黄铜在明暗主题下都成立。
 */
@Composable
fun BoxScope.CommunityRatingBadge(
    item: FindroidItem,
    modifier: Modifier = Modifier,
    cornerPadding: Dp = CinefinSpacing.Space3,
    /** 底部额外抬升（横版卡标题块 / 卡片进度条占位时用），默认贴着海报左下角。 */
    bottomInset: Dp = 0.dp,
) {
    val text = communityRatingBadgeText(item) ?: return
    BaseBadge(
        modifier =
            modifier
                .align(Alignment.BottomStart)
                .padding(start = cornerPadding, bottom = cornerPadding + bottomInset)
    ) {
        Text(
            text = text,
            style = CinefinType.LabelSmall,
            color = RATING_BRASS,
            maxLines = 1,
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * 黄铜 token（`Color.kt:68`）：设计定稿指定「评分、徽标」用途。
 *
 * 注释依据：该 token 目前只被冻结的 TV 主题引用；phone 色板没有同名槽位。为保证四个卡面（海报墙 / 横版 / 网格 / 单集） 同一枚颜色、且不新增色值，这里直接引用该既有
 * token。
 */
private val RATING_BRASS: Color = ColorDark.tertiaryDark
