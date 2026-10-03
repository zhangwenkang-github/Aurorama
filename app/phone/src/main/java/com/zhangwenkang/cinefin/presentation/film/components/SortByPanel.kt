package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSegmentedControl
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme

/**
 * 排序面板（W54-B）：把原 `SortByDialog` 气泡对话框升级为「工具行图标 + 底部面板」。
 *
 * 排序项与排序方向仍是同一份口径（`SortBy.entries` × `CoreR.array.sort_by_options` /
 * `CoreR.array.sort_order_options`），只换承载形态：面板从底部升起、可滚动、行高 48dp（§8.5 列表 + §8.11 面板）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortByPanel(
    currentSortBy: SortBy,
    currentSortOrder: SortOrder,
    onUpdate: (sortBy: SortBy, sortOrder: SortOrder) -> Unit,
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val colors = LocalCinefinColors.current
    val optionValues = SortBy.entries
    val optionNames = stringArrayResource(CoreR.array.sort_by_options)
    val options = optionValues.zip(optionNames)
    val orderValues = SortOrder.entries.toList()
    val orderNames = stringArrayResource(CoreR.array.sort_order_options)

    var selectedOption by remember { mutableStateOf(currentSortBy) }
    var selectedOrder by remember { mutableStateOf(currentSortOrder) }

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = colors.surfaceContainerHigh,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = CinefinSpacing.Space6)
                    .padding(bottom = CinefinSpacing.Space8)
        ) {
            Text(
                text = stringResource(CoreR.string.sort_by),
                style = CinefinType.HeadlineSmall,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(CinefinSpacing.Space4))
            CinefinSegmentedControl(
                items = orderValues,
                selected = selectedOrder,
                onSelect = { order ->
                    selectedOrder = order
                    onUpdate(selectedOption, selectedOrder)
                },
                label = { order ->
                    orderNames.getOrElse(orderValues.indexOf(order)) { order.name }
                },
            )
            Spacer(Modifier.height(CinefinSpacing.Space4))
            options.forEach { option ->
                SortByPanelItem(
                    option = option,
                    isSelected = option.first == selectedOption,
                    onSelect = {
                        selectedOption = option.first
                        onUpdate(selectedOption, selectedOrder)
                    },
                )
            }
        }
    }
}

@Composable
private fun SortByPanelItem(
    option: Pair<SortBy, String>,
    isSelected: Boolean,
    onSelect: (SortBy) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth().heightIn(min = 48.dp).cinefinClickable {
                onSelect(option.first)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = option.second,
            style = CinefinType.BodyLarge,
            color = if (isSelected) media.bright else colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isSelected) {
            Icon(
                painter = painterResource(CoreR.drawable.ic_check),
                contentDescription = null,
                tint = media.bright,
            )
        }
    }
}

@Preview
@Composable
private fun SortByPanelItemPreview() {
    CinefinTheme {
        SortByPanelItem(
            option = Pair(SortBy.NAME, "Title"),
            isSelected = true,
            onSelect = {},
        )
    }
}
