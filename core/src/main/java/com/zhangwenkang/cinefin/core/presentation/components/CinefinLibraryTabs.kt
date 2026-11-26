package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.auroraGradient

/** 标签行行高（2.0.2 定稿 D2）。 */
private val LibraryTabRowHeight = 40.dp

/** 选中指示条：2dp 极光渐变条，宽度取底栏选中指示的 20dp（同一套指示语言）。 */
private val LibraryTabIndicatorWidth = 20.dp
private val LibraryTabIndicatorHeight = 2.dp

/**
 * 库页轻量标签行（2.0.2 定稿 D2）：单层文本标签，无容器底、无描边。
 *
 * 选中 = `auroraInk` 文字 + 2dp 极光渐变指示条；未选中 = `onSurfaceVariant`。行高 40dp、标签间距 [CinefinSpacing.Space4]、
 * 左右对齐 [cinefinLibraryGutter]（与 `CinefinPageTopBar` 顶栏页边距同级），窄屏靠横滑兜底——音乐 5 个 Tab 全部保留。
 *
 * 音乐页（替代原 `CinefinSegmentedControl` 重容器分段控件）与书籍/视频库页共用。
 */
@Composable
fun <T> CinefinLibraryTabs(
    items: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = cinefinLibraryGutter()),
) {
    val listState = rememberLazyListState()
    val selectedIndex = items.indexOf(selected)
    // 选中项滚进可见区：窄屏 5 个标签横滑时，程序化切 Tab（侧栏 / 返回详情）也能看到当前选中。
    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        modifier = modifier.height(LibraryTabRowHeight),
        state = listState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(items) { index, item ->
            LibraryTab(
                text = label(item),
                selected = index == selectedIndex,
                onClick = { onSelect(item) },
            )
        }
    }
}

@Composable
private fun LibraryTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val contentColor by
        animateColorAsState(
            targetValue = if (selected) colors.auroraInk else colors.onSurfaceVariant,
            animationSpec =
                tween(durationMillis = CinefinMotion.Fast, easing = CinefinMotion.Standard),
            label = "libraryTabContent",
        )

    Box(
        modifier =
            Modifier.height(LibraryTabRowHeight)
                .selectable(
                    selected = selected,
                    onClick = onClick,
                    role = Role.Tab,
                    interactionSource = interactionSource,
                    indication = null,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = CinefinType.LabelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
        )
        // 指示条槽位常驻（未选中不绘制），选中切换时文字不跳。
        Box(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .width(LibraryTabIndicatorWidth)
                    .height(LibraryTabIndicatorHeight)
                    .auroraGradient(
                        colors = colors,
                        cornerRadius = LibraryTabIndicatorHeight / 2,
                        visible = selected,
                    )
        )
    }
}

/**
 * 标签行页边距（dp 口径）：Compact 20 / Medium 24 / Expanded 32 / Large 48dp。
 *
 * 与 `CinefinPageTopBar` 的顶栏页边距同一分级——顶栏与标签行上下相邻，边距不一致会错位。
 */
internal fun cinefinLibraryGutterDp(screenWidthDp: Int): Dp =
    when {
        screenWidthDp >= 1200 -> 48.dp
        screenWidthDp >= 840 -> 32.dp
        screenWidthDp >= 600 -> 24.dp
        else -> 20.dp
    }

/** 当前窗口下的标签行左右页边距（见 [cinefinLibraryGutterDp]）。 */
@Composable
fun cinefinLibraryGutter(): Dp = cinefinLibraryGutterDp(LocalConfiguration.current.screenWidthDp)
