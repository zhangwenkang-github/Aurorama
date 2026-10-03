package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.player.core.domain.models.SleepTimerSpec
import kotlin.math.roundToInt

/**
 * 睡眠定时共享选择组件（W55）：音乐面板 / 视频页顶栏对话框共用同一套 UI 与状态语义。
 *
 * - 档位 = [SleepTimerSpec.PRESET_MINUTES]（10 / 20 / 30 / 60，保留既有验收档位）；
 * - 自定义 = 滑块 1–240 分钟（整分钟），点「开始计时」生效；
 * - 进行中再打开会显示剩余时间，选中档位高亮，可一键取消。
 *
 * 只消费纯数据（[activeMinutes] / [remainingMs]）与 [onSelect] 回调，不依赖任何播放层类型—— 宿主可以是
 * `ModalBottomSheet`（音乐）、`BaseDialog`（视频页）或播放器右侧面板的内嵌内容。
 */
@Composable
fun CinefinSleepTimerOptions(
    activeMinutes: Int?,
    remainingMs: Long,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    var customExpanded by rememberSaveable { mutableStateOf(false) }
    var customMinutes by rememberSaveable { mutableIntStateOf(customMinutesDefault(activeMinutes)) }
    val isCustomActive = activeMinutes != null && activeMinutes !in SleepTimerSpec.PRESET_MINUTES

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text =
                if (activeMinutes != null) {
                    stringResource(
                        R.string.sleep_timer_subtitle_active,
                        SleepTimerSpec.formatRemaining(remainingMs),
                    )
                } else {
                    stringResource(R.string.sleep_timer_subtitle)
                },
            style = CinefinType.BodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(CinefinSpacing.Space2))
        CinefinListRow(
            title =
                stringResource(
                    if (activeMinutes != null) R.string.sleep_timer_cancel
                    else R.string.sleep_timer_off
                ),
            isCurrent = activeMinutes == null,
            onClick = { onSelect(null) },
        )
        SleepTimerSpec.PRESET_MINUTES.forEach { minutes ->
            CinefinListRow(
                title = stringResource(R.string.sleep_timer_minutes, minutes),
                isCurrent = activeMinutes == minutes,
                onClick = { onSelect(minutes) },
            )
        }
        CinefinListRow(
            title = stringResource(R.string.sleep_timer_custom),
            secondary =
                when {
                    customExpanded -> stringResource(R.string.sleep_timer_custom_hint)
                    isCustomActive ->
                        stringResource(R.string.sleep_timer_custom_current, activeMinutes)
                    else -> null
                },
            isCurrent = isCustomActive,
            showDivider = customExpanded,
            onClick = {
                if (!customExpanded && isCustomActive) customMinutes = activeMinutes
                customExpanded = !customExpanded
            },
        )
        if (customExpanded) {
            SleepTimerCustomEditor(
                minutes = customMinutes,
                onMinutesChange = { customMinutes = it },
                onConfirm = { onSelect(customMinutes) },
            )
        }
    }
}

/** 自定义分钟编辑器：滑块（整分钟）+ 当前值 + 「开始计时」。 */
@Composable
private fun SleepTimerCustomEditor(
    minutes: Int,
    onMinutesChange: (Int) -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(
                    start = CinefinSpacing.Space4,
                    top = CinefinSpacing.Space2,
                    end = CinefinSpacing.Space4,
                    bottom = CinefinSpacing.Space3,
                )
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.sleep_timer_custom_hint),
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.sleep_timer_minutes, minutes),
                style = CinefinType.TitleMedium,
                color = colors.onSurface,
            )
        }
        CinefinSlider(
            value = minutes.toFloat(),
            onValueChange = { value ->
                onMinutesChange(
                    value
                        .roundToInt()
                        .coerceIn(SleepTimerSpec.MIN_MINUTES, SleepTimerSpec.MAX_MINUTES)
                )
            },
            valueRange = SleepTimerSpec.MIN_MINUTES.toFloat()..SleepTimerSpec.MAX_MINUTES.toFloat(),
            // M3 steps = 中间刻度数 → 240 个整分钟值（1..240）
            steps = SleepTimerSpec.MAX_MINUTES - SleepTimerSpec.MIN_MINUTES - 1,
        )
        Spacer(Modifier.height(CinefinSpacing.Space2))
        CinefinButton(
            text = stringResource(R.string.sleep_timer_custom_start, minutes),
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            size = CinefinButtonSize.Medium,
        )
    }
}

/** 展开自定义时的初始分钟：进行中的自定义值优先，否则给 30 分钟。 */
private fun customMinutesDefault(activeMinutes: Int?): Int =
    activeMinutes?.takeIf { it !in SleepTimerSpec.PRESET_MINUTES } ?: DEFAULT_CUSTOM_MINUTES

private const val DEFAULT_CUSTOM_MINUTES = 30
