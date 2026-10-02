package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors

/** 图标磁贴的尺寸；分隔线的缩进也以它为基准（W42：38 → 40dp，容纳 24dp 语义图标）。 */
internal val SettingsIconTileSize = 40.dp
internal val SettingsRowHorizontalPadding = 16.dp

/** 右侧控件位（W42）：开关 / 当前值 / 箭头共用同一个最小 44dp 的槽位，保证右缘对齐。 */
internal val SettingsTrailingWidth = 44.dp

/**
 * 设置项的统一行式样：图标磁贴 → 标题（+说明） → 尾部（当前值 / 开关 / 箭头）。
 *
 * 图标放进带发丝线的圆角磁贴里，是这一版设置页最直接的"换了样子"—— 整页不再用卡片把每一项包起来，而是排成一张清单， 靠分隔线与磁贴列建立秩序，读起来更接近控制台/系统设置。
 *
 * W6-VIS 升级：磁贴 = 雾灰底（`SurfaceContainerHigh`）+ 1dp 发丝线 + 顶部 1px 内高光；标题走月白、
 * 说明与当前值走次级灰、箭头走三级灰——层次全部由**明度**与 1px 细线建立，不引入新的强调色。
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    iconRes: Int? = null,
    value: String? = null,
    showChevron: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                // W42 行高 60dp（原 64dp）；描述收敛为一行，行距更紧、组内更整齐。
                .defaultMinSize(minHeight = 60.dp)
                .padding(
                    horizontal = SettingsRowHorizontalPadding,
                    // 垂直 8dp：40dp 图标磁贴 / 两行文字都能落到 60dp 行高内（W42）。
                    vertical = CinefinSpacing.Space2,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
    ) {
        if (iconRes != null) {
            Box(
                modifier =
                    Modifier.size(SettingsIconTileSize)
                        .clip(CinefinShapes.Sm)
                        .background(colors.surfaceContainerHigh)
                        .drawBehind {
                            drawRect(
                                color = colors.topHighlight,
                                size = Size(size.width, 1.dp.toPx()),
                            )
                        }
                        .border(width = 1.dp, color = colors.outline, shape = CinefinShapes.Sm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = CinefinType.BodyLarge,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            description
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = it,
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
        }

        // W42：右侧固定 44dp 控件位——开关 / 当前值 / 箭头三选一，统一右缘与垂直居中。
        Box(
            modifier =
                Modifier.defaultMinSize(
                    minWidth = SettingsTrailingWidth,
                    minHeight = SettingsTrailingWidth,
                ),
            contentAlignment = Alignment.CenterEnd,
        ) {
            when {
                trailing != null -> trailing.invoke()
                value != null && value.isNotBlank() ->
                    Text(
                        text = value,
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                showChevron ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_arrow_right),
                        contentDescription = null,
                        tint = colors.onSurfaceFaint,
                        modifier = Modifier.size(20.dp),
                    )
            }
        }
    }
}
