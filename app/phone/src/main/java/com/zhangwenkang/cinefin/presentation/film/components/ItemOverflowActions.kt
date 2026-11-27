package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.BuildConfig
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinMoreVertIcon
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import timber.log.Timber

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

/**
 * 2.0.3-F3：⋮ 键是否显示（纯函数，便于单测）。
 *
 * 权限未拉取时先按「可能可用」显示（点开菜单才拉取，见 [ItemOverflowActionsHandler.onRequestCapabilities]）； 拉取后没有任何可用项就彻底隐藏。
 */
internal fun itemOverflowMenuVisible(
    supported: Boolean,
    capabilitiesKnown: Boolean,
    actions: List<ItemOverflowAction>,
): Boolean = supported && (!capabilitiesKnown || actions.isNotEmpty())

/** 2.0.3-F3：编辑对话框的打开条件——快照所属条目与请求条目一致，且该快照是最近一次请求的结果（首帧即正确快照， 不会被上一次 / 空快照重置输入）。 */
internal fun shouldOpenMetadataEditor(
    requestedItemId: UUID?,
    snapshotItemId: UUID?,
    snapshotReady: Boolean,
): Boolean = requestedItemId != null && snapshotReady && requestedItemId == snapshotItemId

/** ⋮ 菜单的当前账号能力与动作分发；null = 当前界面没有提供写能力（卡片不渲染 ⋮）。 */
class ItemOverflowActionsHandler(
    val canEditMetadata: Boolean,
    val canDeleteContent: Boolean,
    /** false = 权限尚未拉取：⋮ 先显示，点开时才请求（懒加载）。 */
    val capabilitiesKnown: Boolean,
    val offlineMode: Boolean,
    val onRequestCapabilities: () -> Unit,
    val onAction: (FindroidItem, ItemOverflowAction) -> Unit,
)

val LocalItemOverflowActions = staticCompositionLocalOf<ItemOverflowActionsHandler?> { null }

/** 给子树提供 ⋮ 菜单能力（详情页 / 海报墙共用）。未提供的界面保持原样，卡片不出现 ⋮。 */
@Composable
fun ProvideItemOverflowActions(
    canEditMetadata: Boolean,
    canDeleteContent: Boolean,
    capabilitiesKnown: Boolean,
    offlineMode: Boolean,
    onRequestCapabilities: () -> Unit,
    onAction: (FindroidItem, ItemOverflowAction) -> Unit,
    content: @Composable () -> Unit,
) {
    val latestOnAction by rememberUpdatedState(onAction)
    val latestOnRequest by rememberUpdatedState(onRequestCapabilities)
    val handler =
        remember(canEditMetadata, canDeleteContent, capabilitiesKnown, offlineMode) {
            ItemOverflowActionsHandler(
                canEditMetadata = canEditMetadata,
                canDeleteContent = canDeleteContent,
                capabilitiesKnown = capabilitiesKnown,
                offlineMode = offlineMode,
                onRequestCapabilities = { latestOnRequest() },
                onAction = { item, action -> latestOnAction(item, action) },
            )
        }
    CompositionLocalProvider(LocalItemOverflowActions provides handler, content = content)
}

/**
 * 2.0.3-F6：接线防线——影视海报渲染在**没有** [ProvideItemOverflowActions] / [ItemOverflowActionsHost] 的子树里时，
 * debug 构建只在每个条目类型上告警一次（release 零开销），避免以后新增网格忘了接 ⋮ 而静默丢失入口。
 */
internal fun shouldWarnMissingOverflowHost(supported: Boolean, hasHandler: Boolean): Boolean =
    supported && !hasHandler

private val warnedMissingOverflowHost = ConcurrentHashMap.newKeySet<String>()

@Composable
internal fun WarnMissingOverflowHost(item: FindroidItem) {
    val hasHandler = LocalItemOverflowActions.current != null
    if (!shouldWarnMissingOverflowHost(supportsItemOverflowActions(item), hasHandler)) return
    LaunchedEffect(item.id) {
        if (BuildConfig.DEBUG) {
            val kind = item::class.simpleName ?: "item"
            if (warnedMissingOverflowHost.add(kind)) {
                Timber.w("影视海报缺少 ItemOverflowActionsHost，⋮（编辑元数据 / 删除）不可用：%s", kind)
            }
        }
    }
}

/** 当前条目的 ⋮ 可见项（未提供写能力时为空）。 */
@Composable
fun rememberItemOverflowActions(item: FindroidItem): List<ItemOverflowAction> {
    val handler = LocalItemOverflowActions.current ?: return emptyList()
    return itemOverflowActions(
        item = item,
        canEditMetadata = handler.canEditMetadata,
        canDeleteContent = handler.canDeleteContent,
        offlineMode = handler.offlineMode,
    )
}

/** 当前条目是否渲染 ⋮（详情动作条据此参与键宽计算）。 */
@Composable
fun rememberItemOverflowMenuVisible(item: FindroidItem): Boolean {
    val handler = LocalItemOverflowActions.current ?: return false
    return itemOverflowMenuVisible(
        supported = supportsItemOverflowActions(item),
        capabilitiesKnown = handler.capabilitiesKnown,
        actions = rememberItemOverflowActions(item),
    )
}

/**
 * 海报卡 / 详情动作条共用的 ⋮ 键。
 *
 * [size] = 视觉圆直径（海报 32dp、动作条跟随键宽），[touchSize] = 触控面边长（海报 44dp，动作条与视觉圆一致）。 [onImage] = 压在海报 /
 * 剧照上：垫层按 2.0.3 定稿走 **scrim 28%（`PlayerGlassAlpha`）+ `glassBorder`**； [outlined] = 动作条的 1dp 细描边圆形键。
 */
@Composable
fun ItemOverflowMenu(
    item: FindroidItem,
    modifier: Modifier = Modifier,
    size: Dp = 32.dp,
    touchSize: Dp = 44.dp,
    onImage: Boolean = false,
    outlined: Boolean = false,
) {
    val handler = LocalItemOverflowActions.current ?: return
    val actions = rememberItemOverflowActions(item)
    val visible =
        itemOverflowMenuVisible(
            supported = supportsItemOverflowActions(item),
            capabilitiesKnown = handler.capabilitiesKnown,
            actions = actions,
        )
    if (!visible) return

    val colors = LocalCinefinColors.current
    var expanded by remember(item.id) { mutableStateOf(false) }
    // 权限未拉取时先记下「想打开」，拉取完成后按真实可见项再打开（不阻塞点击）。
    var openWhenReady by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(handler.capabilitiesKnown) {
        if (openWhenReady && handler.capabilitiesKnown) {
            expanded = actions.isNotEmpty()
            openWhenReady = false
        }
    }
    val contentColor = if (onImage) Color.White else colors.onSurfaceVariant
    val description = stringResource(CoreR.string.title_more)

    Box(modifier = modifier.size(touchSize), contentAlignment = Alignment.Center) {
        Box(
            modifier =
                Modifier.size(size)
                    .clip(CircleShape)
                    .then(
                        if (onImage) {
                            // 纯图标控件的图上垫层：scrim 28% + glassBorder（2.0.3-M1 定稿，与 ItemTopBar 同口径）。
                            Modifier.background(
                                    colors.scrim.copy(alpha = CinefinTokens.PlayerGlassAlpha)
                                )
                                .border(1.dp, colors.glassBorder, CircleShape)
                        } else {
                            Modifier
                        }
                    )
                    .then(
                        if (outlined) Modifier.border(1.dp, colors.outline, CircleShape)
                        else Modifier
                    )
                    .semantics {
                        contentDescription = description
                        role = Role.Button
                    }
                    .cinefinClickable {
                        if (handler.capabilitiesKnown) {
                            expanded = true
                        } else {
                            openWhenReady = true
                            handler.onRequestCapabilities()
                        }
                    },
            contentAlignment = Alignment.Center,
        ) {
            // 语义由外层 Box 的 semantics 提供，图标本身不再暴露节点
            Box(Modifier.clearAndSetSemantics {}) {
                CinefinMoreVertIcon(contentDescription = description, tint = contentColor)
            }
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
