package com.zhangwenkang.cinefin.presentation.player

import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayControllerState
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.syncplay.SyncPlayConnectionState
import com.zhangwenkang.cinefin.syncplay.SyncPlayError
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W4-T8e：胶囊三态映射、换集拦截判定、错误文案映射。
 *
 * 定稿（VIDEO-32 最小态）只定义了三种可见态，这里把「七态 × 连接态」的完整矩阵逐格钉死， 后续改判定顺序会直接红。
 */
class SyncPlayEntryTest {

    private val groupId = UUID.fromString("11111111-2222-3333-4444-555555555555")

    // —— 胶囊三态 ——

    @Test
    fun capsule_hiddenWheneverNotInGroup() {
        SyncPlayConnectionState.entries.forEach { connection ->
            assertEquals(
                "未入组（连接态 $connection）不应出现胶囊",
                SyncPlayCapsuleVisual.Hidden,
                resolveSyncPlayCapsule(SyncPlayControllerState.NotInGroup, connection),
            )
        }
    }

    @Test
    fun capsule_syncedOnlyForPlayingPausedIdleWhenConnected() {
        listOf(
                SyncPlayControllerState.Idle,
                SyncPlayControllerState.Playing,
                SyncPlayControllerState.Paused,
            )
            .forEach { state ->
                assertEquals(
                    "$state + CONNECTED 应显示已同步",
                    SyncPlayCapsuleVisual.Synced,
                    resolveSyncPlayCapsule(state, SyncPlayConnectionState.CONNECTED),
                )
            }
    }

    @Test
    fun capsule_armedStaysSyncedToAvoidFlickerBetweenGroupCommands() {
        // Armed = 已排定一条未来的组命令，仍在组内且连接正常；显示已同步，否则每条组命令都会让胶囊闪一下消失。
        assertEquals(
            SyncPlayCapsuleVisual.Synced,
            resolveSyncPlayCapsule(
                SyncPlayControllerState.Armed,
                SyncPlayConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun capsule_clockWarmingShowsClockSyncing() {
        assertEquals(
            SyncPlayCapsuleVisual.ClockSyncing,
            resolveSyncPlayCapsule(
                SyncPlayControllerState.ClockWarming,
                SyncPlayConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun capsule_joiningCountsAsClockSyncing() {
        // 定稿没单列 Joining：它同样是「同步尚未就绪」，按时钟同步中显示。
        assertEquals(
            SyncPlayCapsuleVisual.ClockSyncing,
            resolveSyncPlayCapsule(
                SyncPlayControllerState.Joining,
                SyncPlayConnectionState.CONNECTED,
            ),
        )
    }

    @Test
    fun capsule_reconnectingWinsOverAllInGroupStates() {
        listOf(
                SyncPlayControllerState.Joining,
                SyncPlayControllerState.Idle,
                SyncPlayControllerState.Armed,
                SyncPlayControllerState.Playing,
                SyncPlayControllerState.Paused,
                SyncPlayControllerState.ClockWarming,
            )
            .forEach { state ->
                listOf(
                        SyncPlayConnectionState.CONNECTING,
                        SyncPlayConnectionState.DISCONNECTED,
                        // 还没收到第一帧（null）也算「不是 CONNECTED」
                        null,
                    )
                    .forEach { connection ->
                        assertEquals(
                            "$state + $connection 应显示重连中",
                            SyncPlayCapsuleVisual.Reconnecting,
                            resolveSyncPlayCapsule(state, connection),
                        )
                    }
            }
    }

    @Test
    fun capsuleTexts_matchThreeVisibleStates() {
        assertNull(SyncPlayCapsuleVisual.Hidden.textRes())
        assertEquals(
            PlayerR.string.player_sync_capsule_synced,
            SyncPlayCapsuleVisual.Synced.textRes(),
        )
        assertEquals(
            PlayerR.string.player_sync_capsule_reconnecting,
            SyncPlayCapsuleVisual.Reconnecting.textRes(),
        )
        assertEquals(
            PlayerR.string.player_sync_capsule_clock_syncing,
            SyncPlayCapsuleVisual.ClockSyncing.textRes(),
        )
    }

    // —— 组内换集拦截 ——

    @Test
    fun episodeChange_blockedOnlyWhileGateOwnsTransport() {
        assertEquals(
            "入组期间（闸门开启）换集由组控制",
            SyncPlayEpisodeAction.Block,
            resolveSyncPlayEpisodeAction(ownsTransport = true),
        )
        assertEquals(
            "未入组 / 闸门关闭时按本地原路径换集",
            SyncPlayEpisodeAction.Allow,
            resolveSyncPlayEpisodeAction(ownsTransport = false),
        )
    }

    // —— 错误文案 ——

    @Test
    fun errorReason_groupMissingAndAccessDeniedHaveDedicatedCopy() {
        assertEquals(
            PlayerR.string.player_sync_error_group_missing,
            syncPlayErrorReasonRes(SyncPlayError.GroupDoesNotExist(groupId)),
        )
        assertEquals(
            PlayerR.string.player_sync_error_access_denied,
            syncPlayErrorReasonRes(SyncPlayError.LibraryAccessDenied(groupId)),
        )
    }

    @Test
    fun errorReason_serverDowngradeShowsUnsupported() {
        assertEquals(
            PlayerR.string.player_sync_error_unsupported,
            syncPlayErrorReasonRes(SyncPlayError.EndpointUnavailable()),
        )
    }

    @Test
    fun errorReason_authAndTransportErrorsHaveDedicatedCopy() {
        assertEquals(
            PlayerR.string.player_sync_error_unauthorized,
            syncPlayErrorReasonRes(SyncPlayError.Unauthorized()),
        )
        assertEquals(
            PlayerR.string.player_sync_error_network,
            syncPlayErrorReasonRes(SyncPlayError.Timeout()),
        )
        assertEquals(
            PlayerR.string.player_sync_error_network,
            syncPlayErrorReasonRes(SyncPlayError.Network()),
        )
    }

    @Test
    fun errorReason_remainingErrorsFallBackToGeneric() {
        listOf(
                SyncPlayError.HttpError(400),
                SyncPlayError.ServerError(500),
                SyncPlayError.EmptyResponse(),
                SyncPlayError.Unknown(),
            )
            .forEach { error ->
                assertEquals(
                    "$error 应复用通用文案",
                    PlayerR.string.player_sync_error_generic,
                    syncPlayErrorReasonRes(error),
                )
            }
    }
}
