package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceStreamingBitrate

/**
 * 串流码率单选项（W4-T23）：从播放器码率面板迁到设置页。
 *
 * 值域是长整型 bps 档位（自动 / 原始 / 具体档），选项由设置 ViewModel 按 `PlayerStreamingQuality` 的档位表现场生成；
 * 这里只负责显示与选择，写回统一走 `AppPreferences.setPlayerStreamingBitrate`。
 */
@Composable
fun SettingsStreamingBitrateCard(
    preference: PreferenceStreamingBitrate,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val optionLabels =
        preference.options.map { option ->
            option.labelStringResource?.let { stringResource(it) } ?: option.label.orEmpty()
        }
    // 通用选项对话框以字符串为 key（服务器媒体库等运行时选项同款），码率档位编码成 bps 字符串往返
    val dialogOptions =
        preference.options.zip(optionLabels) { option, label -> option.value.toString() to label }
    val currentIndex = preference.options.indexOfFirst { it.value == preference.value }
    val currentLabel =
        if (currentIndex >= 0) optionLabels[currentIndex] else stringResource(CoreR.string.not_set)

    var showDialog by remember { mutableStateOf(false) }

    SettingsBaseCard(
        preference = preference,
        onClick = { showDialog = true },
        modifier = modifier,
    ) {
        SettingsRow(
            title = stringResource(preference.nameStringResource),
            description = preference.descriptionStringRes?.let { stringResource(it) },
            iconRes = preference.iconDrawableId,
            value = currentLabel,
            showChevron = true,
        )
    }

    if (showDialog) {
        SettingsOptionsDialog(
            titleRes = preference.nameStringResource,
            options = dialogOptions,
            selectedValue = preference.value.toString(),
            onUpdate = { value ->
                showDialog = false
                value?.toLongOrNull()?.let(onSelect)
            },
            onDismissRequest = { showDialog = false },
        )
    }
}
