package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.selection.MultiSelectState

/**
 * W58b：页面层多选状态（视频 / 书籍库内容页与视频聚合页共用）。
 *
 * 用 `rememberSaveable` 承载：旋屏 / 进程重建后选中不丢；列表刷新 / 分页仍由 [MultiSelectState.retain] 与已加载条目求交集。
 */
@Composable
fun rememberMultiSelectState(): MutableState<MultiSelectState> =
    rememberSaveable(saver = MultiSelectStateHolderSaver) { mutableStateOf(MultiSelectState()) }

private val MultiSelectStateHolderSaver: Saver<MutableState<MultiSelectState>, Any> =
    listSaver(
        save = { holder -> listOf(holder.value.selectionMode, holder.value.selectedIds.toList()) },
        restore = { saved ->
            mutableStateOf(
                MultiSelectState(
                    selectionMode = saved.getOrNull(0) as? Boolean ?: false,
                    selectedIds =
                        (saved.getOrNull(1) as? List<*>)
                            ?.filterIsInstance<String>()
                            ?.toSet()
                            .orEmpty(),
                )
            )
        },
    )

/**
 * W58 可复用「长按进入多选」手势（下载页多选沿用旧实现，新三模式统一走这里）。
 *
 * 普通态单击 = [onClick]；长按 = [onLongPress]（进入多选并把该条加入选中）； 多选态由调用方在 [selected] 为 true 时把单击接到
 * [onClick]（切换选中），本函数不感知模式。
 */
@Composable
fun Modifier.cinefinSelectable(
    enabled: Boolean = true,
    selected: Boolean = false,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    return combinedClickable(
        interactionSource = source,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClickLabel = onClickLabel,
        onLongClickLabel = onLongClickLabel,
        onClick = onClick,
        onLongClick = onLongPress,
    )
}

/**
 * §8.5 多选勾选指示（20dp）：选中 = `Media.Base` 填充 + `OnBase` 勾；未选中 = 透明底 + 1dp 描边。
 *
 * 卡片（§8.4「左上角已选圆点」）/ 列表行（§8.5 左侧勾选）共用；仅作指示，点击由整行 / 整卡承担。
 */
@Composable
fun CinefinSelectIndicator(
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            modifier
                .size(size)
                .clip(CircleShape)
                .background(if (selected) media.base else colors.scrim.copy(alpha = 0.55f))
                .then(
                    if (selected) Modifier
                    else Modifier.border(1.dp, colors.onSurface.copy(alpha = 0.65f), CircleShape)
                ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = media.onBase,
                modifier = Modifier.size(size * 0.6f),
            )
        }
    }
}

/**
 * W58 多选底部工具条（下载页 `SelectionBar` 同款口径）：左「已选 N 项」+ 右动作键。
 *
 * 动作可用性由调用方按纯函数判定后传入 [actions]；本组件不含业务语义。
 */
@Composable
fun CinefinBatchBar(
    selectedCount: Int,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .background(colors.surfaceContainer)
                .navigationBarsPadding()
                .padding(
                    horizontal = CinefinSpacing.Space4,
                    vertical = CinefinSpacing.Space2,
                ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.download_selected_count, selectedCount),
            style = CinefinType.BodyMedium,
            color = colors.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1)) { actions() }
    }
}
