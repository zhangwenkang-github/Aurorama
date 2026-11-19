package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayCommandType
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupState
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayPlaybackRequest
import com.zhangwenkang.cinefin.syncplay.SyncPlayCommand
import com.zhangwenkang.cinefin.syncplay.SyncPlayCommandType as DataCommandType
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroup
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroupState as DataGroupState
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroupStateUpdate
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroupUpdate
import com.zhangwenkang.cinefin.syncplay.SyncPlayPlaybackRequest as DataPlaybackRequest
import com.zhangwenkang.cinefin.syncplay.SyncPlayQueue
import com.zhangwenkang.cinefin.syncplay.SyncPlayQueueUpdateReason
import com.zhangwenkang.cinefin.syncplay.SyncPlayRepeatMode
import com.zhangwenkang.cinefin.syncplay.SyncPlayServerTime
import com.zhangwenkang.cinefin.syncplay.SyncPlayShuffleMode
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8d：data → player/core 的模型搬运逐值覆盖（同名类型靠 `import as` 区分）。 */
class SyncPlayModelMappingTest {
    private val groupId = UUID.fromString("11111111-2222-3333-4444-555555555555")
    private val itemId = UUID.fromString("66666666-7777-8888-9999-aaaaaaaaaaaa")
    private val whenAt = Instant.parse("2026-11-19T10:00:01Z")
    private val emittedAt = Instant.parse("2026-11-19T10:00:00Z")

    @Test
    fun `命令逐字段搬运`() {
        val data =
            SyncPlayCommand(
                groupId = groupId,
                playlistItemId = itemId,
                type = DataCommandType.SEEK,
                whenAt = whenAt,
                positionTicks = 123_456_789L,
                emittedAt = emittedAt,
            )

        val core = data.toCore()

        assertEquals(groupId, core.groupId)
        assertEquals(itemId, core.playlistItemId)
        assertEquals(SyncPlayCommandType.SEEK, core.type)
        assertEquals(whenAt, core.whenAt)
        assertEquals(123_456_789L, core.positionTicks)
        assertEquals(emittedAt, core.emittedAt)
    }

    @Test
    fun `命令类型逐值一一对应`() {
        val dataTypes =
            listOf(
                DataCommandType.UNPAUSE to SyncPlayCommandType.UNPAUSE,
                DataCommandType.PAUSE to SyncPlayCommandType.PAUSE,
                DataCommandType.STOP to SyncPlayCommandType.STOP,
                DataCommandType.SEEK to SyncPlayCommandType.SEEK,
                DataCommandType.UNKNOWN to SyncPlayCommandType.UNKNOWN,
            )
        dataTypes.forEach { (data, expected) -> assertEquals(expected, data.toCore()) }
        // 两侧枚举值集合一致（漏一个就会在多收集路径上静默丢命令）
        assertEquals(DataCommandType.entries.size, SyncPlayCommandType.entries.size)
    }

    @Test
    fun `组快照与组态逐值搬运`() {
        val data =
            SyncPlayGroup(
                id = groupId,
                name = "客厅",
                state = DataGroupState.WAITING,
                participants = listOf("kimi", "web"),
                lastUpdatedAt = emittedAt,
            )

        val core = data.toCore()

        assertEquals(groupId, core.id)
        assertEquals("客厅", core.name)
        assertEquals(SyncPlayGroupState.WAITING, core.state)
        assertEquals(listOf("kimi", "web"), core.participants)
        assertEquals(emittedAt, core.lastUpdatedAt)
        assertEquals(DataGroupState.entries.size, SyncPlayGroupState.entries.size)
    }

    @Test
    fun `组事件逐类型搬运`() {
        val group = FakeSyncPlayGateway.group(groupId)
        assertEquals(
            com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.GroupJoined(
                groupId,
                group.toCore(),
            ),
            (SyncPlayGroupUpdate.GroupJoined(groupId, group).toCore()),
        )
        assertEquals(
            com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.GroupLeft(groupId),
            (SyncPlayGroupUpdate.GroupLeft(groupId).toCore()),
        )
        assertEquals(
            com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.UserJoined(
                groupId,
                "web",
            ),
            (SyncPlayGroupUpdate.UserJoined(groupId, "web").toCore()),
        )
        assertEquals(
            com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.UserLeft(
                groupId,
                null,
            ),
            (SyncPlayGroupUpdate.UserLeft(groupId, null).toCore()),
        )
        assertEquals(
            com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.NotInGroup(groupId),
            (SyncPlayGroupUpdate.NotInGroup(groupId).toCore()),
        )
        assertEquals(
            com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.GroupDoesNotExist(
                groupId
            ),
            (SyncPlayGroupUpdate.GroupDoesNotExist(groupId).toCore()),
        )
        assertEquals(
            com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.LibraryAccessDenied(
                groupId
            ),
            (SyncPlayGroupUpdate.LibraryAccessDenied(groupId).toCore()),
        )
        assertEquals(
            com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.Unknown(groupId),
            (SyncPlayGroupUpdate.Unknown(groupId).toCore()),
        )
    }

    @Test
    fun `状态变化事件带原因搬运`() {
        val data =
            SyncPlayGroupUpdate.StateChanged(
                groupId = groupId,
                update =
                    SyncPlayGroupStateUpdate(
                        state = DataGroupState.PAUSED,
                        reason = DataPlaybackRequest.PAUSE,
                    ),
            )

        val core = coreStateChanged(data)

        assertEquals(SyncPlayGroupState.PAUSED, core.update.state)
        assertEquals(SyncPlayPlaybackRequest.PAUSE, core.update.reason)
        assertEquals(DataPlaybackRequest.entries.size, SyncPlayPlaybackRequest.entries.size)
    }

    @Test
    fun `队列事件被丢弃`() {
        val queue =
            SyncPlayQueue(
                reason = SyncPlayQueueUpdateReason.QUEUE,
                playlist = emptyList(),
                playingItemIndex = 0,
                startPositionTicks = 0L,
                isPlaying = false,
                shuffleMode = SyncPlayShuffleMode.SORTED,
                repeatMode = SyncPlayRepeatMode.REPEAT_NONE,
                lastUpdatedAt = emittedAt,
            )
        assertNull(SyncPlayGroupUpdate.QueueChanged(groupId, queue).toCore())
    }

    @Test
    fun `服务器时间戳搬运不漏字段`() {
        val data =
            SyncPlayServerTime(
                requestReceptionTime = whenAt,
                responseTransmissionTime = emittedAt,
            )

        val core = data.toCoreSample()

        assertEquals(whenAt, core.requestReceptionTime)
        assertEquals(emittedAt, core.responseTransmissionTime)
    }

    @Test
    fun `映射不丢 groupId 且未知事件不会误判成踢出`() {
        val core = SyncPlayGroupUpdate.Unknown(groupId).toCore()
        assertTrue(
            core is com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.Unknown
        )
        assertEquals(groupId, core!!.groupId)
    }

    private fun coreStateChanged(
        data: SyncPlayGroupUpdate.StateChanged
    ): com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.StateChanged =
        data.toCore()
            as com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate.StateChanged
}
