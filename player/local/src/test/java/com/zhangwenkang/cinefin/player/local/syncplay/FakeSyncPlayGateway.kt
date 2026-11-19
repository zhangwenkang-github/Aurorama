package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.syncplay.SyncPlayCapability
import com.zhangwenkang.cinefin.syncplay.SyncPlayCommand
import com.zhangwenkang.cinefin.syncplay.SyncPlayConnectionState
import com.zhangwenkang.cinefin.syncplay.SyncPlayError
import com.zhangwenkang.cinefin.syncplay.SyncPlayGateway
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroup
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroupState
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroupUpdate
import com.zhangwenkang.cinefin.syncplay.SyncPlayQueueMode
import com.zhangwenkang.cinefin.syncplay.SyncPlayRepeatMode
import com.zhangwenkang.cinefin.syncplay.SyncPlayResult
import com.zhangwenkang.cinefin.syncplay.SyncPlayServerTime
import com.zhangwenkang.cinefin.syncplay.SyncPlayShuffleMode
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * T8d 单测用网关（不触网）：WS 三路流用可变流模拟，REST 动词按预设结果返回并记录调用序列。
 *
 * 与 T8a 的契约测试互补：这边验的是装配层「怎么收、收了做什么」，协议映射已由 T8a 单测钉死。
 */
class FakeSyncPlayGateway : SyncPlayGateway {
    val commandCollections = AtomicInteger()
    val groupUpdateCollections = AtomicInteger()
    val connectionCollections = AtomicInteger()

    private val commandFlow = MutableSharedFlow<SyncPlayCommand>(extraBufferCapacity = 32)
    private val groupUpdateFlow = MutableSharedFlow<SyncPlayGroupUpdate>(extraBufferCapacity = 32)
    private val connectionFlow = MutableStateFlow(SyncPlayConnectionState.CONNECTED)

    /** 全部 REST 调用的名字序列（含参数摘要）。 */
    val calls = mutableListOf<String>()

    var serverTime: SyncPlayResult<SyncPlayServerTime> =
        SyncPlayResult.Success(
            SyncPlayServerTime(
                requestReceptionTime = Instant.parse("2026-11-19T10:00:00Z"),
                responseTransmissionTime = Instant.parse("2026-11-19T10:00:00.010Z"),
            )
        )

    var groupSnapshot: SyncPlayResult<SyncPlayGroup>? = null
    var createGroupResult: SyncPlayResult<SyncPlayGroup>? = null
    var joinGroupResult: SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)
    var leaveGroupResult: SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)
    var pauseResult: SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)
    var unpauseResult: SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)
    var seekResult: SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)

    /** W4-T8e：`startGroupQueue()` 薄封装的入参记录（队列体 / 下标 / ticks）。 */
    val playQueueCalls = mutableListOf<Triple<List<UUID>, Int, Long>>()
    var playQueueResult: SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)

    override val connectionState: Flow<SyncPlayConnectionState>
        get() = flow {
            connectionCollections.incrementAndGet()
            emitAll(connectionFlow)
        }

    override val groupUpdates: Flow<SyncPlayGroupUpdate>
        get() = flow {
            groupUpdateCollections.incrementAndGet()
            emitAll(groupUpdateFlow)
        }

    override val commands: Flow<SyncPlayCommand>
        get() = flow {
            commandCollections.incrementAndGet()
            emitAll(commandFlow)
        }

    override suspend fun probeCapability(): SyncPlayCapability =
        SyncPlayCapability.Unavailable(
            com.zhangwenkang.cinefin.syncplay.SyncPlayUnavailableReason.GROUP_LIST_FAILED
        )

    override suspend fun getServerTime(): SyncPlayResult<SyncPlayServerTime> = serverTime

    override suspend fun getGroups(): SyncPlayResult<List<SyncPlayGroup>> =
        SyncPlayResult.Success(emptyList())

    override suspend fun getGroup(groupId: UUID): SyncPlayResult<SyncPlayGroup> {
        calls += "getGroup:$groupId"
        return groupSnapshot ?: SyncPlayResult.Failure(SyncPlayError.GroupDoesNotExist(groupId))
    }

    override suspend fun createGroup(name: String): SyncPlayResult<SyncPlayGroup> {
        calls += "createGroup:$name"
        return createGroupResult ?: SyncPlayResult.Failure(SyncPlayError.Network("no preset"))
    }

    override suspend fun joinGroup(groupId: UUID): SyncPlayResult<Unit> {
        calls += "joinGroup:$groupId"
        return joinGroupResult
    }

    override suspend fun leaveGroup(): SyncPlayResult<Unit> {
        calls += "leaveGroup"
        return leaveGroupResult
    }

    override suspend fun playQueue(
        playlistItemIds: List<UUID>,
        playingItemIndex: Int,
        startPositionTicks: Long,
    ): SyncPlayResult<Unit> {
        playQueueCalls += Triple(playlistItemIds, playingItemIndex, startPositionTicks)
        return playQueueResult
    }

    override suspend fun setPlaylistItem(playlistItemId: UUID): SyncPlayResult<Unit> =
        SyncPlayResult.Success(Unit)

    override suspend fun nextItem(playlistItemId: UUID): SyncPlayResult<Unit> =
        SyncPlayResult.Success(Unit)

    override suspend fun previousItem(playlistItemId: UUID): SyncPlayResult<Unit> =
        SyncPlayResult.Success(Unit)

    override suspend fun pause(): SyncPlayResult<Unit> {
        calls += "pause"
        return pauseResult
    }

    override suspend fun unpause(): SyncPlayResult<Unit> {
        calls += "unpause"
        return unpauseResult
    }

    override suspend fun stop(): SyncPlayResult<Unit> {
        calls += "stop"
        return SyncPlayResult.Success(Unit)
    }

    override suspend fun seek(positionTicks: Long): SyncPlayResult<Unit> {
        calls += "seek:$positionTicks"
        return seekResult
    }

    override suspend fun queue(
        playlistItemIds: List<UUID>,
        mode: SyncPlayQueueMode,
    ): SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)

    override suspend fun movePlaylistItem(
        playlistItemId: UUID,
        newIndex: Int,
    ): SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)

    override suspend fun removeFromPlaylist(
        playlistItemIds: List<UUID>,
        clearPlaylist: Boolean,
        clearPlayingItem: Boolean,
    ): SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)

    override suspend fun setRepeatMode(mode: SyncPlayRepeatMode): SyncPlayResult<Unit> =
        SyncPlayResult.Success(Unit)

    override suspend fun setShuffleMode(mode: SyncPlayShuffleMode): SyncPlayResult<Unit> =
        SyncPlayResult.Success(Unit)

    override suspend fun setIgnoreWait(ignoreWait: Boolean): SyncPlayResult<Unit> =
        SyncPlayResult.Success(Unit)

    override suspend fun reportReady(
        whenAt: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ): SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)

    override suspend fun reportBuffering(
        whenAt: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ): SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)

    override suspend fun ping(pingMillis: Long): SyncPlayResult<Unit> = SyncPlayResult.Success(Unit)

    // —— 测试驱动 ——

    fun emitCommand(command: SyncPlayCommand): Boolean = commandFlow.tryEmit(command)

    fun emitGroupUpdate(update: SyncPlayGroupUpdate): Boolean = groupUpdateFlow.tryEmit(update)

    fun setConnectionState(state: SyncPlayConnectionState) {
        connectionFlow.value = state
    }

    val currentConnectionState: StateFlow<SyncPlayConnectionState> = connectionFlow.asStateFlow()

    companion object {
        fun group(
            id: UUID,
            name: String = "客厅",
            state: SyncPlayGroupState = SyncPlayGroupState.PLAYING,
        ): SyncPlayGroup =
            SyncPlayGroup(
                id = id,
                name = name,
                state = state,
                participants = listOf("kimi", "web"),
                lastUpdatedAt = Instant.parse("2026-11-19T10:00:00Z"),
            )
    }
}
