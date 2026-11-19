package com.zhangwenkang.cinefin.presentation.player

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayControllerState
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroup as CoreSyncPlayGroup
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.syncplay.SyncPlaySessionCoordinator
import com.zhangwenkang.cinefin.syncplay.SyncPlayCapability
import com.zhangwenkang.cinefin.syncplay.SyncPlayConnectionState
import com.zhangwenkang.cinefin.syncplay.SyncPlayError
import java.util.UUID

/*
 * W4-T8e（VIDEO-32 最小态 / VIDEO-46）：SyncPlay 入口面板、状态胶囊与提示条。
 *
 * 规格唯一来源：docs/V2_W4_TASKS.md「T8e 设计定稿」。脉动与「已对齐」淡出 DEFERRED，本文件只有已同步 / 重连中 / 时钟同步中三态。
 */

/** 状态胶囊的三种可见态（外加「不在组内 = 不出现」）。 */
internal enum class SyncPlayCapsuleVisual {
    /** 未入组：胶囊整颗不出现（VIDEO-32：只在入组期间出现）。 */
    Hidden,

    /** 已同步：绿点 aurora1，只在 Playing / Paused / Idle 且连接正常时。 */
    Synced,

    /** 重连中…：WS 连接态不是 CONNECTED（含还没收到第一帧）。 */
    Reconnecting,

    /** 时钟同步中…：状态机在 ClockWarming（等待首个时钟样本）。 */
    ClockSyncing,
}

/**
 * 七态 + 连接态 → 胶囊三态。
 *
 * 判定顺序（定稿只写了三种可见态，这里把顺序钉死，避免状态叠加时两边各判一套）：
 * 1. 未入组 → 不出现；
 * 2. 连接态 ≠ CONNECTED → 重连中（连接断了时时钟也不可能收敛，比「时钟同步中」更贴近事实）；
 * 3. ClockWarming → 时钟同步中；Joining 同样按「同步尚未就绪」显示时钟同步中（定稿未单列 Joining）；
 * 4. 其余（Armed / Idle / Playing / Paused）→ 已同步。
 *
 * Armed 是「已排定未来组命令」的瞬时态，仍在组内且连接正常，按已同步显示——否则每条组命令都会让胶囊闪一下消失。
 */
internal fun resolveSyncPlayCapsule(
    state: SyncPlayControllerState,
    connection: SyncPlayConnectionState?,
): SyncPlayCapsuleVisual =
    when {
        state == SyncPlayControllerState.NotInGroup -> SyncPlayCapsuleVisual.Hidden
        connection != SyncPlayConnectionState.CONNECTED -> SyncPlayCapsuleVisual.Reconnecting
        state == SyncPlayControllerState.ClockWarming || state == SyncPlayControllerState.Joining ->
            SyncPlayCapsuleVisual.ClockSyncing
        else -> SyncPlayCapsuleVisual.Synced
    }

/** 胶囊文案；Hidden 不画胶囊，没有文案。 */
@StringRes
internal fun SyncPlayCapsuleVisual.textRes(): Int? =
    when (this) {
        SyncPlayCapsuleVisual.Hidden -> null
        SyncPlayCapsuleVisual.Synced -> PlayerR.string.player_sync_capsule_synced
        SyncPlayCapsuleVisual.Reconnecting -> PlayerR.string.player_sync_capsule_reconnecting
        SyncPlayCapsuleVisual.ClockSyncing -> PlayerR.string.player_sync_capsule_clock_syncing
    }

/** 组内换集的动作判定：闸门开启时上/下一集与队列点选一律拦下（提示条由宿主弹）。 */
internal enum class SyncPlayEpisodeAction {
    /** 不在组内 / 闸门关闭：按本地原路径换集。 */
    Allow,

    /** 入组期间：换集由组控制，只提示不执行。 */
    Block,
}

internal fun resolveSyncPlayEpisodeAction(ownsTransport: Boolean): SyncPlayEpisodeAction =
    if (ownsTransport) SyncPlayEpisodeAction.Block else SyncPlayEpisodeAction.Allow

/**
 * SyncPlay 错误 → 原因文案（拼在「加入失败：/创建失败：」后面）。
 *
 * 403 在 T8a 一律映射 `LibraryAccessDenied`；组上下文缺失的 404 是 `EndpointUnavailable`（版本过低）。
 */
@StringRes
internal fun syncPlayErrorReasonRes(error: SyncPlayError): Int =
    when (error) {
        is SyncPlayError.GroupDoesNotExist -> PlayerR.string.player_sync_error_group_missing
        is SyncPlayError.LibraryAccessDenied -> PlayerR.string.player_sync_error_access_denied
        is SyncPlayError.EndpointUnavailable -> PlayerR.string.player_sync_error_unsupported
        is SyncPlayError.Unauthorized -> PlayerR.string.player_sync_error_unauthorized
        is SyncPlayError.Timeout,
        is SyncPlayError.Network -> PlayerR.string.player_sync_error_network
        is SyncPlayError.HttpError,
        is SyncPlayError.ServerError,
        is SyncPlayError.EmptyResponse,
        is SyncPlayError.Unknown -> PlayerR.string.player_sync_error_generic
    }

/**
 * 状态胶囊（VIDEO-32 最小态）：玻璃底 + glassBorder + Full 胶囊、高 28dp、横内边距 10dp，8dp 圆点 + 13sp 文字。
 *
 * 点击 = 打开 SyncPlay 面板；随控制层出入（由顶栏承载，不独立常驻）。
 */
@Composable
internal fun SyncPlayCapsule(
    visual: SyncPlayCapsuleVisual,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val textRes = visual.textRes() ?: return
    val colors = LocalCinefinColors.current
    val dotColor =
        if (visual == SyncPlayCapsuleVisual.Synced) colors.aurora1 else colors.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .height(SYNC_PLAY_CAPSULE_HEIGHT)
                .clip(CinefinShapes.Full)
                .background(colors.glass)
                .border(1.dp, colors.glassBorder, CinefinShapes.Full)
                .clickable(onClick = onClick)
                .padding(horizontal = SYNC_PLAY_CAPSULE_HORIZONTAL_PADDING),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dotColor))
        Spacer(Modifier.width(CinefinSpacing.Space1))
        Text(
            text = stringResource(textRes),
            color = colors.onSurface,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 13.sp,
            maxLines = 1,
        )
    }
}

/**
 * 面板：未入组 = 创建 CTA + 加入现有组；已入组 = 组名 + 成员列表 + 底部离开组。
 *
 * 组能力与组列表都来自协调器的 `probeCapability()`（入组前探针，UI 不碰 gateway）； 组身份变化（创建 / 加入 / 离开）时重新探一次，保证列表不过期。
 */
@Composable
internal fun SyncPlayPanel(
    syncPlay: SyncPlaySessionCoordinator,
    activeGroup: CoreSyncPlayGroup?,
    onCreateGroup: () -> Unit,
    onJoinGroup: (UUID) -> Unit,
    onLeaveGroup: () -> Unit,
) {
    var capability by remember { mutableStateOf<SyncPlayCapability?>(null) }
    LaunchedEffect(activeGroup?.id) { capability = syncPlay.probeCapability() }
    if (activeGroup != null) {
        /*
         * 成员列表优先用探针刚拉到的组快照：协调器只在 `GroupJoined` 时刷新组对象，
         * 别人加入 / 离开（`UserJoined` / `UserLeft`）不会改它，直接用 activeGroup 会看到过期人数。
         */
        val freshGroup =
            (capability as? SyncPlayCapability.Available)?.groups?.firstOrNull {
                it.id == activeGroup.id
            }
        SyncPlayGroupSection(
            group = activeGroup,
            members = freshGroup?.participants ?: activeGroup.participants,
            onLeaveGroup = onLeaveGroup,
        )
    } else {
        SyncPlayJoinSection(
            capability = capability,
            onCreateGroup = onCreateGroup,
            onJoinGroup = onJoinGroup,
        )
    }
}

@Composable
private fun SyncPlayJoinSection(
    capability: SyncPlayCapability?,
    onCreateGroup: () -> Unit,
    onJoinGroup: (UUID) -> Unit,
) {
    val groups = (capability as? SyncPlayCapability.Available)?.groups.orEmpty()
    Column(modifier = Modifier.fillMaxSize()) {
        SyncPlayPrimaryButton(
            text = stringResource(PlayerR.string.player_sync_create_group),
            onClick = onCreateGroup,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = CinefinSpacing.Space5,
                        end = CinefinSpacing.Space5,
                        top = CinefinSpacing.Space2,
                        bottom = CinefinSpacing.Space4,
                    ),
        )
        PanelList {
            PanelTitle(stringResource(PlayerR.string.player_sync_join_existing))
            if (capability != null && groups.isEmpty()) {
                PanelTitle(stringResource(PlayerR.string.player_sync_no_groups))
            }
            groups.forEach { group ->
                PanelRow(
                    label = group.name,
                    caption =
                        stringResource(
                            PlayerR.string.player_sync_group_members,
                            group.participants.size,
                        ),
                    selected = false,
                    onClick = { onJoinGroup(group.id) },
                )
            }
        }
    }
}

@Composable
private fun SyncPlayGroupSection(
    group: CoreSyncPlayGroup,
    members: List<String>,
    onLeaveGroup: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            PanelTitle(group.name)
            members.forEach { name -> PanelRow(label = name, selected = false, onClick = {}) }
        }
        SyncPlayDangerTextButton(
            text = stringResource(PlayerR.string.player_sync_leave_group),
            onClick = onLeaveGroup,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = CinefinSpacing.Space5,
                        end = CinefinSpacing.Space5,
                        bottom = CinefinSpacing.Space5,
                    ),
        )
    }
}

/** 主 CTA：aurora 渐变底（K1 规则允许的唯一渐变按钮），文字用 onAurora。 */
@Composable
private fun SyncPlayPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Button
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            modifier
                .heightIn(min = 52.dp)
                .clip(shape)
                .background(Brush.horizontalGradient(listOf(colors.aurora1, colors.aurora2)))
                .clickable(onClick = onClick)
                .padding(horizontal = CinefinSpacing.Space4),
    ) {
        Text(
            text = text,
            color = colors.onAurora,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** 「离开组」：错误色文字按钮（非破坏性强调，不弹确认框——组离开即销毁由服务器兜底）。 */
@Composable
private fun SyncPlayDangerTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier =
            modifier
                .heightIn(min = 52.dp)
                .clip(CinefinShapes.Button)
                .clickable(onClick = onClick)
                .padding(horizontal = CinefinSpacing.Space3),
    ) {
        Text(
            text = text,
            color = colors.error,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/** 非阻断提示条：复用 `PlaybackRecoveryBar` 的玻璃底 + 1dp 描边 + Card 形状，单行居中。 */
@Composable
internal fun SyncPlayNoticeBar(text: String, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    val shape = CinefinShapes.Card
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .widthIn(max = 560.dp)
                .clip(shape)
                .background(colors.surfaceContainer.copy(alpha = 0.96f))
                .border(1.dp, colors.outline, shape)
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space2),
    ) {
        Text(
            text = text,
            color = colors.onSurface,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 胶囊高度（定稿：28dp）。 */
private val SYNC_PLAY_CAPSULE_HEIGHT = 28.dp

/** 胶囊横向内边距（定稿：10dp）。 */
private val SYNC_PLAY_CAPSULE_HORIZONTAL_PADDING = 10.dp
