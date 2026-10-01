package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * 区块标题（Lumen）：大标题 23sp/600 + 右侧文字操作，**不画发丝线**。
 *
 * 旧稿的横贯分隔线在流光大版面上会切断"内容即光源"的整体感；这里改用间距与字阶建立层级， 右侧操作保留一个 16dp 右箭头，指向性比纯文字更强。
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
    val lumen = LocalLumenColors.current
    // A 稿 `.shelf-head .more` 是三级文字灰；Prism 区域保持媒体色文字操作
    val actionColor = lumen?.textFaint ?: media.bright
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
    ) {
        Text(text = title, style = CinefinType.SectionTitle, color = colors.onSurface)
        Spacer(modifier = Modifier.weight(1f))
        if (actionText != null && onActionClick != null) {
            Row(
                modifier =
                    Modifier.clip(CinefinShapes.Sm)
                        .cinefinClickable { onActionClick() }
                        .padding(
                            horizontal = CinefinSpacing.Space2,
                            vertical = CinefinSpacing.Space2,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = actionText, style = CinefinType.LabelMedium, color = actionColor)
                Spacer(modifier = Modifier.width(CinefinSpacing.Space1))
                Icon(
                    painter = painterResource(CoreR.drawable.ic_arrow_right),
                    contentDescription = null,
                    tint = actionColor,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
