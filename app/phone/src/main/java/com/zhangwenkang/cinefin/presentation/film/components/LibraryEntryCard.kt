package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.navigation.libraryIconRes
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/**
 * 媒体库入口卡（Lumen 改版，W8-R3）：整列宽度 16:9 大图 + 底部渐隐 + 一层「类型图标 + 库名 + 项目数」信息条。
 *
 * 与首页同一视觉语言：`LumenCardFrame` 的 1dp 发丝线 + 顶部内高光、`lumenEntrance` 错峰淡入；类型图标放进"雾灰 + 发丝线"
 * 磁贴并用当前强调色点缀（Lumen = 极光青，Prism = 当前域媒体色），右侧一个三级灰箭头表示"进入"； 项目数来自库的
 * `ChildCount`（`FindroidCollection.itemCount`），服务器没给就只显示库名，不占位。
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
    val media = LocalMediaColors.current
    val lumen = LocalLumenColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val scrim = lumen?.scrim ?: Color.Black
    val tileShape = CinefinShapes.Sm
    val collection = item as? FindroidCollection
    val itemCount = collection?.itemCount

    LumenCardFrame(
        modifier =
            modifier.fillMaxWidth().aspectRatio(16f / 9f).lumenEntrance(index).cinefinClickable(
                interactionSource = interactionSource
            ) {
                onClick()
            },
        emphasized = hovered || pressed,
        container = lumen?.panel ?: colors.surfaceContainerHigh,
    ) {
        AsyncImage(
            model = item.images.backdrop ?: item.images.primary,
            placeholder = ColorPainter(lumen?.panelElevated ?: colors.surfaceContainerHigh),
            error = ColorPainter(lumen?.panelElevated ?: colors.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier =
                Modifier.fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0.38f to Color.Transparent,
                            0.74f to scrim.copy(alpha = 0.5f),
                            1f to scrim.copy(alpha = 0.92f),
                        )
                    )
        )

        Row(
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = CinefinSpacing.Space5,
                        end = CinefinSpacing.Space5,
                        bottom = CinefinSpacing.Space4,
                    ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 类型图标磁贴（雾灰 + 发丝线 + 强调色图标）：媒体库总览会列出音乐 / 书籍库，
            // 但卡片本体不引用各域的媒体色——强调色统一取当前皮肤（Lumen = 极光青）。
            Box(
                modifier =
                    Modifier.size(44.dp)
                        .clip(tileShape)
                        .background(lumen?.panelElevated ?: colors.surfaceContainerHigh)
                        .border(1.dp, lumen?.line ?: colors.outline, tileShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter =
                        painterResource(libraryIconRes(collection?.type ?: CollectionType.Mixed)),
                    contentDescription = null,
                    tint = media.base,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(CinefinSpacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = CinefinType.WideCardTitle,
                    color = lumen?.text ?: colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (itemCount != null && itemCount > 0) {
                    Spacer(Modifier.height(CinefinSpacing.Space1))
                    Text(
                        text = stringResource(FilmR.string.library_item_count, itemCount),
                        style = CinefinType.BodySmall,
                        color = lumen?.textSecondary ?: colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(CinefinSpacing.Space2))
            Icon(
                painter = painterResource(CoreR.drawable.ic_arrow_right),
                contentDescription = null,
                tint = lumen?.textFaint ?: colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 720)
@Composable
private fun LibraryEntryCardPreview() {
    CinefinTheme {
        LibraryEntryCard(item = dummyMovie, onClick = {}, modifier = Modifier.padding(24.dp))
    }
}
