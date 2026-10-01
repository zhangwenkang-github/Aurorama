package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/**
 * 媒体库入口卡（Lumen）：整列宽度的 16:9 大图 + 底部渐隐 + 库名。
 *
 * 取代原先"固定 260dp 宽 + 自适应栅格"的写法——那种组合在手机上会挤出屏幕，在平板上又留下不规则空档； 现在卡片宽度由栅格列宽决定，一屏只放 1–3 张，信息密度直接降下来。
 */
@Composable
fun LibraryEntryCard(
    item: FindroidItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
) {
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()

    LumenCardFrame(
        modifier =
            modifier.fillMaxWidth().aspectRatio(16f / 9f).lumenEntrance(index).cinefinClickable(
                interactionSource = interactionSource
            ) {
                onClick()
            },
        emphasized = hovered || pressed,
        container = colors.surfaceContainerHigh,
    ) {
        AsyncImage(
            model = item.images.backdrop ?: item.images.primary,
            placeholder = ColorPainter(colors.surfaceContainerHigh),
            error = ColorPainter(colors.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.4f to Color.Transparent,
                            0.78f to Color.Black.copy(alpha = 0.45f),
                            1f to Color.Black.copy(alpha = 0.86f),
                        )
                    )
        )

        Row(
            modifier = Modifier.align(Alignment.TopEnd).padding(CinefinSpacing.Space3),
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            item.unplayedItemCount?.takeIf { it > 0 }?.let { ItemCountBadge(it) }
        }

        Text(
            text = item.name,
            style = CinefinType.WideCardTitle,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = CinefinSpacing.Space5,
                        end = CinefinSpacing.Space5,
                        bottom = CinefinSpacing.Space4,
                    ),
        )
    }
}

@Preview(showBackground = true, widthDp = 720)
@Composable
private fun LibraryEntryCardPreview() {
    CinefinTheme {
        LibraryEntryCard(item = dummyMovie, onClick = {}, modifier = Modifier.padding(24.dp))
    }
}
