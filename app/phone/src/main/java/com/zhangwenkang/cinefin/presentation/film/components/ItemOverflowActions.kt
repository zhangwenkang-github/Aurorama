package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDeleteRules
import com.zhangwenkang.cinefin.film.presentation.detail.DetailEditRules
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow

/** 2.0.3-C：海报 / 详情页 ⋮ 菜单项（与 Jellyfin 官方客户端同序：先编辑元数据，后删除）。 */
enum class ItemOverflowAction {
    EditMetadata,
    Delete,
}

/** 只有影视四类条目有服务器文件与可编辑元数据——书籍 / 音乐 / 合集不出现 ⋮ 菜单。 */
internal fun supportsItemOverflowActions(item: FindroidItem): Boolean =
    item is FindroidMovie ||
        item is FindroidShow ||
        item is FindroidSeason ||
        item is FindroidEpisode

/**
 * 2.0.3-C：⋮ 菜单的可见项（纯函数，便于单测）。
 *
 * - 编辑元数据：管理员专属（[DetailEditRules] 同口径，复用既有权限判定）；
 * - 删除：电影 / 单集按服务端条目级 `canDelete`（Jellyfin 官方同一口径）；剧集 / 季的本地模型没有该字段， 退到账号级「可删除内容」权限；
 * - 离线模式两者都不渲染（没有服务器可写）。
 */
internal fun itemOverflowActions(
    item: FindroidItem,
    canEditMetadata: Boolean,
    canDeleteContent: Boolean,
    offlineMode: Boolean,
): List<ItemOverflowAction> {
    if (!supportsItemOverflowActions(item)) return emptyList()
    return buildList {
        if (DetailEditRules.shouldShowEditAction(canEditMetadata, offlineMode)) {
            add(ItemOverflowAction.EditMetadata)
        }
        val canDelete =
            when (item) {
                is FindroidMovie -> item.canDelete
                is FindroidEpisode -> item.canDelete
                else -> canDeleteContent
            }
        if (DetailDeleteRules.shouldShowDeleteAction(canDelete, offlineMode)) {
            add(ItemOverflowAction.Delete)
        }
    }
}

/** ⋮ 菜单的当前账号能力与动作分发；null = 当前界面没有提供写能力（卡片不渲染 ⋮）。 */
class ItemOverflowActionsHandler(
    val canEditMetadata: Boolean,
    val canDeleteContent: Boolean,
    val offlineMode: Boolean,
    val onAction: (FindroidItem, ItemOverflowAction) -> Unit,
)

val LocalItemOverflowActions = staticCompositionLocalOf<ItemOverflowActionsHandler?> { null }

/** 给子树提供 ⋮ 菜单能力（详情页 / 海报墙共用）。未提供的界面保持原样，卡片不出现 ⋮。 */
@Composable
fun ProvideItemOverflowActions(
    canEditMetadata: Boolean,
    canDeleteContent: Boolean,
    offlineMode: Boolean,
    onAction: (FindroidItem, ItemOverflowAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val latestOnAction by rememberUpdatedState(onAction)
    val handler =
        remember(canEditMetadata, canDeleteContent, offlineMode) {
            ItemOverflowActionsHandler(
                canEditMetadata = canEditMetadata,
                canDeleteContent = canDeleteContent,
                offlineMode = offlineMode,
                onAction = { item, action -> latestOnAction(item, action) },
            )
        }
    CompositionLocalProvider(LocalItemOverflowActions provides handler, content = content)
}

/**
 * 海报卡 / 详情动作条共用的 ⋮ 键：24dp 点阵图标、圆形触控目标（海报上 40dp、动作条 44dp）。
 *
 * [onImage] = 压在海报 / 剧照上时给一层半透明黑底，保证任意亮度底图上都看得清； [outlined] = 动作条的 1dp 细描边圆形键。
 */
@Composable
fun ItemOverflowMenu(
    item: FindroidItem,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    onImage: Boolean = false,
    outlined: Boolean = false,
) {
    val handler = LocalItemOverflowActions.current ?: return
    val actions =
        itemOverflowActions(
            item = item,
            canEditMetadata = handler.canEditMetadata,
            canDeleteContent = handler.canDeleteContent,
            offlineMode = handler.offlineMode,
        )
    if (actions.isEmpty()) return

    val colors = LocalCinefinColors.current
    var expanded by remember(item.id) { mutableStateOf(false) }
    val contentColor = if (onImage) Color.White else colors.onSurfaceVariant
    val description = stringResource(CoreR.string.title_more)

    Box(modifier = modifier) {
        Box(
            modifier =
                Modifier.size(size)
                    .clip(CircleShape)
                    .then(
                        if (onImage) Modifier.background(Color.Black.copy(alpha = 0.38f))
                        else Modifier
                    )
                    .then(
                        if (outlined) Modifier.border(1.dp, colors.outline, CircleShape)
                        else Modifier
                    )
                    .semantics {
                        contentDescription = description
                        role = Role.Button
                    }
                    .cinefinClickable { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            OverflowDots(color = contentColor)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(action.labelRes),
                            style = CinefinType.BodyMedium,
                            color =
                                if (action == ItemOverflowAction.Delete) colors.error
                                else colors.onSurface,
                        )
                    },
                    onClick = {
                        expanded = false
                        handler.onAction(item, action)
                    },
                )
            }
        }
    }
}

private val ItemOverflowAction.labelRes: Int
    get() =
        when (this) {
            ItemOverflowAction.EditMetadata -> FilmR.string.detail_edit_metadata_action
            ItemOverflowAction.Delete -> FilmR.string.detail_delete_server_confirm
        }

/** 24dp 点阵 ⋮（3 × 4dp 圆点 + 6dp 间距）；不依赖图标资源，任意主题色下都清晰。 */
@Composable
private fun OverflowDots(color: Color) {
    Column(
        modifier = Modifier.size(width = 24.dp, height = 24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) { Box(modifier = Modifier.size(4.dp).clip(CircleShape).background(color)) }
    }
}
