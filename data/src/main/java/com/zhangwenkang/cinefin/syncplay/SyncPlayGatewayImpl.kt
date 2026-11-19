package com.zhangwenkang.cinefin.syncplay

import com.zhangwenkang.cinefin.api.JellyfinApi
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.SyncPlayApi
import org.jellyfin.sdk.api.sockets.SocketApi
import org.jellyfin.sdk.model.api.BufferRequestDto
import org.jellyfin.sdk.model.api.IgnoreWaitRequestDto
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.MovePlaylistItemRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.NextItemRequestDto
import org.jellyfin.sdk.model.api.PingRequestDto
import org.jellyfin.sdk.model.api.PlayRequestDto
import org.jellyfin.sdk.model.api.PreviousItemRequestDto
import org.jellyfin.sdk.model.api.QueueRequestDto
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.RemoveFromPlaylistRequestDto
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SetPlaylistItemRequestDto
import org.jellyfin.sdk.model.api.SetRepeatModeRequestDto
import org.jellyfin.sdk.model.api.SetShuffleModeRequestDto
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage

/**
 * 网关实现：把 jellyfin-sdk 的 `SyncPlayApi` / `SocketApi` / `TimeSyncApi` 接到 [SyncPlayGateway]。
 *
 * 所有调用都走 [callContent] / [callAction]，异常在此统一翻译为 [SyncPlayError]； SDK 类型的映射集中在 `SyncPlayMapping.kt`。
 */
class SyncPlayGatewayImpl(private val api: JellyfinApi) : SyncPlayGateway {
    private val syncPlayApi: SyncPlayApi
        get() = api.syncPlayApi

    private val socketApi: SocketApi
        get() = api.webSocket

    // 取值为 getter：每次收集都重新向 SocketApi 订阅，收集结束即退订（T8d 依赖这个生命周期语义）。
    override val connectionState: Flow<SyncPlayConnectionState>
        get() = socketApi.state.map { it.toDomain() }

    override val groupUpdates: Flow<SyncPlayGroupUpdate>
        get() = socketApi.subscribe(SyncPlayGroupUpdateMessage::class).map { it.data.toDomain() }

    override val commands: Flow<SyncPlayCommand>
        get() =
            socketApi.subscribe(SyncPlayCommandMessage::class).mapNotNull { it.data?.toDomain() }

    override suspend fun probeCapability(): SyncPlayCapability {
        val groups =
            when (val result = getGroups()) {
                is SyncPlayResult.Success -> result.value
                is SyncPlayResult.Failure ->
                    return SyncPlayCapability.Unavailable(
                        SyncPlayUnavailableReason.GROUP_LIST_FAILED,
                        result.error,
                    )
            }
        val serverTime =
            when (val result = getServerTime()) {
                is SyncPlayResult.Success -> result.value
                is SyncPlayResult.Failure ->
                    return SyncPlayCapability.Unavailable(
                        SyncPlayUnavailableReason.SERVER_TIME_FAILED,
                        result.error,
                    )
            }
        return SyncPlayCapability.Available(groups = groups, serverTime = serverTime)
    }

    override suspend fun getServerTime(): SyncPlayResult<SyncPlayServerTime> = callContent {
        api.timeSyncApi.getUtcTime()
    }
        .mapSuccess { it.toDomain() }

    override suspend fun getGroups(): SyncPlayResult<List<SyncPlayGroup>> = callContent {
        syncPlayApi.syncPlayGetGroups()
    }
        .mapSuccess { groups -> groups.map { it.toDomain() } }

    override suspend fun getGroup(groupId: UUID): SyncPlayResult<SyncPlayGroup> =
        callContent(groupId) { syncPlayApi.syncPlayGetGroup(groupId) }.mapSuccess { it.toDomain() }

    override suspend fun createGroup(name: String): SyncPlayResult<SyncPlayGroup> = callContent {
        syncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(groupName = name))
    }
        .mapSuccess { it.toDomain() }

    override suspend fun joinGroup(groupId: UUID): SyncPlayResult<Unit> =
        callAction(groupId) {
            syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId = groupId))
        }

    override suspend fun leaveGroup(): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayLeaveGroup()
    }

    override suspend fun playQueue(
        playlistItemIds: List<UUID>,
        playingItemIndex: Int,
        startPositionTicks: Long,
    ): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlaySetNewQueue(
            PlayRequestDto(
                playingQueue = playlistItemIds,
                playingItemPosition = playingItemIndex,
                startPositionTicks = startPositionTicks,
            )
        )
    }

    override suspend fun setPlaylistItem(playlistItemId: UUID): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlaySetPlaylistItem(
            SetPlaylistItemRequestDto(playlistItemId = playlistItemId)
        )
    }

    override suspend fun nextItem(playlistItemId: UUID): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayNextItem(NextItemRequestDto(playlistItemId = playlistItemId))
    }

    override suspend fun previousItem(playlistItemId: UUID): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayPreviousItem(PreviousItemRequestDto(playlistItemId = playlistItemId))
    }

    override suspend fun pause(): SyncPlayResult<Unit> = callAction { syncPlayApi.syncPlayPause() }

    override suspend fun unpause(): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayUnpause()
    }

    override suspend fun stop(): SyncPlayResult<Unit> = callAction { syncPlayApi.syncPlayStop() }

    override suspend fun seek(positionTicks: Long): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlaySeek(SeekRequestDto(positionTicks = positionTicks))
    }

    override suspend fun queue(
        playlistItemIds: List<UUID>,
        mode: SyncPlayQueueMode,
    ): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayQueue(QueueRequestDto(itemIds = playlistItemIds, mode = mode.toSdk()))
    }

    override suspend fun movePlaylistItem(
        playlistItemId: UUID,
        newIndex: Int,
    ): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayMovePlaylistItem(
            MovePlaylistItemRequestDto(playlistItemId = playlistItemId, newIndex = newIndex)
        )
    }

    override suspend fun removeFromPlaylist(
        playlistItemIds: List<UUID>,
        clearPlaylist: Boolean,
        clearPlayingItem: Boolean,
    ): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayRemoveFromPlaylist(
            RemoveFromPlaylistRequestDto(
                playlistItemIds = playlistItemIds,
                clearPlaylist = clearPlaylist,
                clearPlayingItem = clearPlayingItem,
            )
        )
    }

    override suspend fun setRepeatMode(mode: SyncPlayRepeatMode): SyncPlayResult<Unit> =
        callAction {
            syncPlayApi.syncPlaySetRepeatMode(SetRepeatModeRequestDto(mode = mode.toSdk()))
        }

    override suspend fun setShuffleMode(mode: SyncPlayShuffleMode): SyncPlayResult<Unit> =
        callAction {
            syncPlayApi.syncPlaySetShuffleMode(SetShuffleModeRequestDto(mode = mode.toSdk()))
        }

    override suspend fun setIgnoreWait(ignoreWait: Boolean): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlaySetIgnoreWait(IgnoreWaitRequestDto(ignoreWait = ignoreWait))
    }

    override suspend fun reportReady(
        whenAt: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayReady(
            ReadyRequestDto(
                `when` = whenAt.toServerLocalDateTime(),
                positionTicks = positionTicks,
                isPlaying = isPlaying,
                playlistItemId = playlistItemId,
            )
        )
    }

    override suspend fun reportBuffering(
        whenAt: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayBuffering(
            BufferRequestDto(
                `when` = whenAt.toServerLocalDateTime(),
                positionTicks = positionTicks,
                isPlaying = isPlaying,
                playlistItemId = playlistItemId,
            )
        )
    }

    override suspend fun ping(pingMillis: Long): SyncPlayResult<Unit> = callAction {
        syncPlayApi.syncPlayPing(PingRequestDto(ping = pingMillis))
    }

    /** 需要内容的调用：204/空体视为 [SyncPlayError.EmptyResponse]（组快照/组列表不该为空）。 */
    private suspend fun <T> callContent(
        groupId: UUID? = null,
        block: suspend () -> Response<T>,
    ): SyncPlayResult<T> =
        try {
            val response = block()
            if (response.status == NO_CONTENT) {
                SyncPlayResult.Failure(
                    SyncPlayError.EmptyResponse("HTTP $NO_CONTENT for a content-required call")
                )
            } else {
                val value: T? = response.content
                if (value == null) {
                    SyncPlayResult.Failure(SyncPlayError.EmptyResponse("empty body"))
                } else {
                    SyncPlayResult.Success(value)
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            SyncPlayResult.Failure(throwable.toSyncPlayError(groupId))
        }

    /** 动作类调用：服务器返回 204 空体是正常成功（Jellyfin 的 SyncPlay 动词均如此）。 */
    private suspend fun callAction(
        groupId: UUID? = null,
        block: suspend () -> Response<Unit>,
    ): SyncPlayResult<Unit> =
        try {
            block()
            SyncPlayResult.Success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            SyncPlayResult.Failure(throwable.toSyncPlayError(groupId))
        }

    private companion object {
        const val NO_CONTENT = 204
    }
}

internal inline fun <T, R> SyncPlayResult<T>.mapSuccess(transform: (T) -> R): SyncPlayResult<R> =
    when (this) {
        is SyncPlayResult.Success -> SyncPlayResult.Success(transform(value))
        is SyncPlayResult.Failure -> this
    }
