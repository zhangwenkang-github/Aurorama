package com.zhangwenkang.cinefin.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinFilterChip
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import java.util.UUID

/** 库选择候选项（W54-C）：[id] = null 表示「全部库」/「自动」这条不限定项。 */
data class LibrarySelectorOption(
    val id: UUID?,
    val label: String,
    /** 菜单第二行（项目数 / 类型），服务器没给就不占位。 */
    val detail: String? = null,
)

/**
 * 顶栏「库选择」（W54-C，用户 2026-10-03 确认）：紧凑 chip 常显当前选择 + 下拉菜单列出「全部库 / 自动」与各库。
 *
 * 选中态走设计系统 §8.3（`CinefinFilterChip`）：未选择 = 中性色，选定某个库 = 当前域媒体色融入控件本体；菜单项行尾用 `ic_check`
 * 标出当前项。两处复用：视频页（全部库 / 各视频库）与书架页（自动 / 各书籍库）。
 */
@Composable
fun LibrarySelectorChip(
    label: String,
    options: List<LibrarySelectorOption>,
    selectedId: UUID?,
    onSelect: (UUID?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    var expanded by rememberSaveable { mutableStateOf(false) }

    Box(modifier = modifier) {
        CinefinFilterChip(
            text = label,
            selected = selectedId != null,
            compact = true,
            onClick = { expanded = true },
            modifier = Modifier.widthIn(max = 168.dp),
            icon = { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_library),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
            },
        )

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = CinefinShapes.Sm,
            containerColor = colors.surfaceContainerHighest,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(
                                text = option.label,
                                style = CinefinType.BodyMedium,
                                color = colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            option.detail?.let { detail ->
                                Text(
                                    text = detail,
                                    style = CinefinType.BodySmall,
                                    color = colors.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    },
                    trailingIcon = {
                        if (option.id == selectedId) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_check),
                                contentDescription = null,
                                tint = media.bright,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(option.id)
                    },
                )
            }
        }
    }
}
