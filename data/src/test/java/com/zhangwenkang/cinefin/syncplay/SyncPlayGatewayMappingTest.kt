package com.zhangwenkang.cinefin.syncplay

import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupRepeatMode
import org.jellyfin.sdk.model.api.GroupShuffleMode
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupStateUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.PlaybackRequestType
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayQueueItem
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.UtcTimeResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** W4-T8a：SDK → 域 的映射契约（纯函数，不含网络）。 */
class SyncPlayGatewayMappingTest {
    private val groupId = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val itemId = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val playlistItemId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    private fun sdkGroup(): GroupInfoDto =
        GroupInfoDto(
            groupId = groupId,
            groupName = "客厅同步",
            state = GroupStateType.PLAYING,
            participants = listOf("alice", "bob"),
            lastUpdatedAt = LocalDateTime.of(2026, 11, 19, 20, 30, 0),
        )

    @Test
    fun `组加入更新映射为域组快照`() {
        val update = SyncPlayGroupJoinedUpdate(groupId = groupId, data = sdkGroup()).toDomain()

        val expected =
            SyncPlayGroupUpdate.GroupJoined(
                groupId = groupId,
                group =
                    SyncPlayGroup(
                        id = groupId,
                        name = "客厅同步",
                        state = SyncPlayGroupState.PLAYING,
                        participants = listOf("alice", "bob"),
                        lastUpdatedAt =
                            LocalDateTime.of(2026, 11, 19, 20, 30, 0)
                                .atZone(ZoneId.systemDefault())
                                .toInstant(),
                    ),
            )
        assertEquals(expected, update)
    }

    @Test
    fun `组状态更新映射为域状态事件`() {
        val update =
            SyncPlayStateUpdate(
                    groupId = groupId,
                    data =
                        GroupStateUpdate(
                            state = GroupStateType.PAUSED,
                            reason = PlaybackRequestType.PAUSE,
                        ),
                )
                .toDomain()

        assertEquals(
            SyncPlayGroupUpdate.StateChanged(
                groupId = groupId,
                update =
                    SyncPlayGroupStateUpdate(
                        state = SyncPlayGroupState.PAUSED,
                        reason = SyncPlayPlaybackRequest.PAUSE,
                    ),
            ),
            update,
        )
    }

    @Test
    fun `组不存在与媒体库无权限更新映射为踢出事件`() {
        assertEquals(
            SyncPlayGroupUpdate.GroupDoesNotExist(groupId),
            SyncPlayGroupDoesNotExistUpdate(groupId = groupId, data = "gone").toDomain(),
        )
        assertEquals(
            SyncPlayGroupUpdate.LibraryAccessDenied(groupId),
            SyncPlayLibraryAccessDeniedUpdate(groupId = groupId, data = "denied").toDomain(),
        )
    }

    @Test
    fun `队列更新映射为域队列`() {
        val update =
            SyncPlayPlayQueueUpdate(
                    groupId = groupId,
                    data =
                        PlayQueueUpdate(
                            reason = PlayQueueUpdateReason.NEW_PLAYLIST,
                            lastUpdate = LocalDateTime.of(2026, 11, 19, 20, 31, 0),
                            playlist =
                                listOf(
                                    SyncPlayQueueItem(
                                        itemId = itemId,
                                        playlistItemId = playlistItemId,
                                    )
                                ),
                            playingItemIndex = 0,
                            startPositionTicks = 5_000_000L,
                            isPlaying = true,
                            shuffleMode = GroupShuffleMode.SORTED,
                            repeatMode = GroupRepeatMode.REPEAT_NONE,
                        ),
                )
                .toDomain()

        val queueChanged = update as SyncPlayGroupUpdate.QueueChanged
        assertEquals(groupId, queueChanged.groupId)
        assertEquals(SyncPlayQueueUpdateReason.NEW_PLAYLIST, queueChanged.queue.reason)
        assertEquals(
            listOf(SyncPlayQueueEntry(itemId = itemId, playlistItemId = playlistItemId)),
            queueChanged.queue.playlist,
        )
        assertEquals(0, queueChanged.queue.playingItemIndex)
        assertEquals(5_000_000L, queueChanged.queue.startPositionTicks)
        assertTrue(queueChanged.queue.isPlaying)
        assertEquals(SyncPlayShuffleMode.SORTED, queueChanged.queue.shuffleMode)
        assertEquals(SyncPlayRepeatMode.REPEAT_NONE, queueChanged.queue.repeatMode)
    }

    @Test
    fun `播放命令映射为域命令并保留位置与时刻`() {
        val whenAt = LocalDateTime.of(2026, 11, 19, 20, 32, 0)
        val emittedAt = LocalDateTime.of(2026, 11, 19, 20, 31, 59)

        val command =
            SendCommand(
                    groupId = groupId,
                    playlistItemId = playlistItemId,
                    `when` = whenAt,
                    positionTicks = 42_000_000L,
                    command = SendCommandType.SEEK,
                    emittedAt = emittedAt,
                )
                .toDomain()

        assertEquals(
            SyncPlayCommand(
                groupId = groupId,
                playlistItemId = playlistItemId,
                type = SyncPlayCommandType.SEEK,
                whenAt = whenAt.atZone(ZoneId.systemDefault()).toInstant(),
                positionTicks = 42_000_000L,
                emittedAt = emittedAt.atZone(ZoneId.systemDefault()).toInstant(),
            ),
            command,
        )
    }

    @Test
    fun `UTC 时间响应按系统时区换算为 Instant`() {
        val reception = LocalDateTime.of(2026, 11, 19, 12, 0, 0)
        val transmission = LocalDateTime.of(2026, 11, 19, 12, 0, 1)

        val serverTime =
            UtcTimeResponse(
                    requestReceptionTime = reception,
                    responseTransmissionTime = transmission,
                )
                .toDomain()

        assertEquals(
            SyncPlayServerTime(
                requestReceptionTime = reception.atZone(ZoneId.systemDefault()).toInstant(),
                responseTransmissionTime = transmission.atZone(ZoneId.systemDefault()).toInstant(),
            ),
            serverTime,
        )
    }

    @Test
    fun `Instant 与服务器 LocalDateTime 往返不丢精度`() {
        val instant = java.time.Instant.ofEpochMilli(1_763_539_200_123L)

        assertEquals(instant, instant.toServerLocalDateTime().toInstantInSystemZone())
    }
}
