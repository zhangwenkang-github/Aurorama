package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * 区块标题：衬线标题 + 一条延伸到右边界的发丝线（+ 可选的文字操作）。
 *
 * 这条线不是装饰：它把标题和它下面的内容明确地绑在一起， 让页面在滚动时仍然一眼分得清"哪一行是标题、哪一片是内容"。
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
    ) {
        Text(text = title, style = CinefinType.SectionTitle, color = colors.onSurface)
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            color = colors.outlineVariant,
        )
        if (actionText != null && onActionClick != null) {
            Text(
                text = actionText,
                style = CinefinType.LabelMedium,
                color = media.bright,
                modifier =
                    Modifier.clickable { onActionClick() }
                        .padding(vertical = CinefinSpacing.Space1),
            )
        }
    }
}
