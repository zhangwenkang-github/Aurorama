package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.R as AppR
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.detail.RatingStarFill
import com.zhangwenkang.cinefin.film.presentation.detail.ratingLabel
import com.zhangwenkang.cinefin.film.presentation.detail.ratingStarFill
import com.zhangwenkang.cinefin.film.presentation.detail.ratingValueForHalfStar

/**
 * W8-T3 评分对话框（设计定稿，逐字执行）：标题「评分」；内容 = 5 星条（半星粒度，共 10 档，对应 API 1–10 分）+ 当前分回显； 左下「清除」（置
 * null），右下「取消」。点星即写入，无确认键。
 *
 * 复用既有 Material3 `AlertDialog` 与主题色（accent = `LocalMediaColors.bright`），不新增 token / 样式。
 */
@Composable
internal fun RatingDialog(
    currentRating: Double?,
    onRatingSelected: (Double) -> Unit,
    onClear: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(FilmR.string.rating_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2)) {
                ratingLabel(currentRating)?.let { label ->
                    Text(
                        text = label,
                        style = CinefinType.TitleMedium,
                        color = LocalMediaColors.current.bright,
                    )
                }
                RatingStarRow(
                    currentRating = currentRating,
                    onRatingSelected = onRatingSelected,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onClear) { Text(text = stringResource(FilmR.string.rating_clear)) }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(CoreR.string.cancel))
            }
        },
    )
}

/** 5 星条：每颗星左 / 右半各对应 1 分（半星），点击立即回写。 */
@Composable
private fun RatingStarRow(
    currentRating: Double?,
    onRatingSelected: (Double) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val accent = LocalMediaColors.current.bright
    Row {
        repeat(5) { starIndex ->
            Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_star),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
                when (ratingStarFill(currentRating, starIndex)) {
                    RatingStarFill.FULL ->
                        Icon(
                            painter = painterResource(AppR.drawable.ic_star_filled),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(28.dp),
                        )
                    RatingStarFill.HALF ->
                        Icon(
                            painter = painterResource(AppR.drawable.ic_star_half),
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(28.dp),
                        )
                    RatingStarFill.EMPTY -> Unit
                }
                Row(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier =
                            Modifier.weight(1f).fillMaxHeight().clickable {
                                onRatingSelected(
                                    ratingValueForHalfStar(starIndex, rightHalf = false)
                                )
                            }
                    )
                    Box(
                        modifier =
                            Modifier.weight(1f).fillMaxHeight().clickable {
                                onRatingSelected(
                                    ratingValueForHalfStar(starIndex, rightHalf = true)
                                )
                            }
                    )
                }
            }
        }
    }
}
