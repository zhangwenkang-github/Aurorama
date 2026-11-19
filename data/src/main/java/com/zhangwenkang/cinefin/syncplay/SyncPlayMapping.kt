package com.zhangwenkang.cinefin.syncplay

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.sockets.SocketApiState
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.GroupQueueMode
import org.jellyfin.sdk.model.api.GroupRepeatMode
import org.jellyfin.sdk.model.api.GroupShuffleMode
import org.jellyfin.sdk.model.api.GroupStateType
import org.jellyfin.sdk.model.api.GroupStateUpdate
import org.jellyfin.sdk.model.api.GroupUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdate
import org.jellyfin.sdk.model.api.PlayQueueUpdateReason
import org.jellyfin.sdk.model.api.PlaybackRequestType
import org.jellyfin.sdk.model.api.SendCommand
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayGroupDoesNotExistUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate
import org.jellyfin.sdk.model.api.SyncPlayLibraryAccessDeniedUpdate
import org.jellyfin.sdk.model.api.SyncPlayNotInGroupUpdate
import org.jellyfin.sdk.model.api.SyncPlayPlayQueueUpdate
import org.jellyfin.sdk.model.api.SyncPlayStateUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate
import org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate
import org.jellyfin.sdk.model.api.UtcTimeResponse

/**
 * W4-T8a：SDK 类型 → 域类型 的映射。
 *
 * 全部映射函数都是 `internal`：SDK 类型只出现在网关实现与契约测试里，不越过 [SyncPlayGateway] 接口。
 */
internal fun GroupInfoDto.toDomain(): SyncPlayGroup =
    SyncPlayGroup(
        id = groupId,
        name = groupName,
        state = state.toDomain(),
        participants = participants,
        lastUpdatedAt = lastUpdatedAt.toInstantInSystemZone(),
    )

internal fun GroupStateType.toDomain(): SyncPlayGroupState =
    when (this) {
        GroupStateType.IDLE -> SyncPlayGroupState.IDLE
        GroupStateType.WAITING -> SyncPlayGroupState.WAITING
        GroupStateType.PAUSED -> SyncPlayGroupState.PAUSED
        GroupStateType.PLAYING -> SyncPlayGroupState.PLAYING
    }

internal fun GroupUpdate.toDomain(): SyncPlayGroupUpdate =
    when (this) {
        is SyncPlayGroupJoinedUpdate ->
            SyncPlayGroupUpdate.GroupJoined(groupId = groupId, group = data.toDomain())
        is SyncPlayGroupLeftUpdate -> SyncPlayGroupUpdate.GroupLeft(groupId)
        is SyncPlayUserJoinedUpdate -> SyncPlayGroupUpdate.UserJoined(groupId, data)
        is SyncPlayUserLeftUpdate -> SyncPlayGroupUpdate.UserLeft(groupId, data)
        is SyncPlayStateUpdate -> SyncPlayGroupUpdate.StateChanged(groupId, data.toDomain())
        is SyncPlayPlayQueueUpdate -> SyncPlayGroupUpdate.QueueChanged(groupId, data.toDomain())
        is SyncPlayNotInGroupUpdate -> SyncPlayGroupUpdate.NotInGroup(groupId)
        is SyncPlayGroupDoesNotExistUpdate -> SyncPlayGroupUpdate.GroupDoesNotExist(groupId)
        is SyncPlayLibraryAccessDeniedUpdate -> SyncPlayGroupUpdate.LibraryAccessDenied(groupId)
    }

internal fun GroupStateUpdate.toDomain(): SyncPlayGroupStateUpdate =
    SyncPlayGroupStateUpdate(state = state.toDomain(), reason = reason.toDomain())

internal fun PlayQueueUpdate.toDomain(): SyncPlayQueue =
    SyncPlayQueue(
        reason = reason.toDomain(),
        playlist =
            playlist.map {
                SyncPlayQueueEntry(itemId = it.itemId, playlistItemId = it.playlistItemId)
            },
        playingItemIndex = playingItemIndex,
        startPositionTicks = startPositionTicks,
        isPlaying = isPlaying,
        shuffleMode = shuffleMode.toDomain(),
        repeatMode = repeatMode.toDomain(),
        lastUpdatedAt = lastUpdate.toInstantInSystemZone(),
    )

internal fun PlayQueueUpdateReason.toDomain(): SyncPlayQueueUpdateReason =
    when (this) {
        PlayQueueUpdateReason.NEW_PLAYLIST -> SyncPlayQueueUpdateReason.NEW_PLAYLIST
        PlayQueueUpdateReason.SET_CURRENT_ITEM -> SyncPlayQueueUpdateReason.SET_CURRENT_ITEM
        PlayQueueUpdateReason.REMOVE_ITEMS -> SyncPlayQueueUpdateReason.REMOVE_ITEMS
        PlayQueueUpdateReason.MOVE_ITEM -> SyncPlayQueueUpdateReason.MOVE_ITEM
        PlayQueueUpdateReason.QUEUE -> SyncPlayQueueUpdateReason.QUEUE
        PlayQueueUpdateReason.QUEUE_NEXT -> SyncPlayQueueUpdateReason.QUEUE_NEXT
        PlayQueueUpdateReason.NEXT_ITEM -> SyncPlayQueueUpdateReason.NEXT_ITEM
        PlayQueueUpdateReason.PREVIOUS_ITEM -> SyncPlayQueueUpdateReason.PREVIOUS_ITEM
        PlayQueueUpdateReason.REPEAT_MODE -> SyncPlayQueueUpdateReason.REPEAT_MODE
        PlayQueueUpdateReason.SHUFFLE_MODE -> SyncPlayQueueUpdateReason.SHUFFLE_MODE
    }

internal fun GroupShuffleMode.toDomain(): SyncPlayShuffleMode =
    when (this) {
        GroupShuffleMode.SORTED -> SyncPlayShuffleMode.SORTED
        GroupShuffleMode.SHUFFLE -> SyncPlayShuffleMode.SHUFFLE
    }

internal fun GroupRepeatMode.toDomain(): SyncPlayRepeatMode =
    when (this) {
        GroupRepeatMode.REPEAT_NONE -> SyncPlayRepeatMode.REPEAT_NONE
        GroupRepeatMode.REPEAT_ONE -> SyncPlayRepeatMode.REPEAT_ONE
        GroupRepeatMode.REPEAT_ALL -> SyncPlayRepeatMode.REPEAT_ALL
    }

internal fun PlaybackRequestType.toDomain(): SyncPlayPlaybackRequest =
    when (this) {
        PlaybackRequestType.PLAY -> SyncPlayPlaybackRequest.PLAY
        PlaybackRequestType.SET_PLAYLIST_ITEM -> SyncPlayPlaybackRequest.SET_PLAYLIST_ITEM
        PlaybackRequestType.REMOVE_FROM_PLAYLIST -> SyncPlayPlaybackRequest.REMOVE_FROM_PLAYLIST
        PlaybackRequestType.MOVE_PLAYLIST_ITEM -> SyncPlayPlaybackRequest.MOVE_PLAYLIST_ITEM
        PlaybackRequestType.QUEUE -> SyncPlayPlaybackRequest.QUEUE
        PlaybackRequestType.UNPAUSE -> SyncPlayPlaybackRequest.UNPAUSE
        PlaybackRequestType.PAUSE -> SyncPlayPlaybackRequest.PAUSE
        PlaybackRequestType.STOP -> SyncPlayPlaybackRequest.STOP
        PlaybackRequestType.SEEK -> SyncPlayPlaybackRequest.SEEK
        PlaybackRequestType.BUFFER -> SyncPlayPlaybackRequest.BUFFER
        PlaybackRequestType.READY -> SyncPlayPlaybackRequest.READY
        PlaybackRequestType.NEXT_ITEM -> SyncPlayPlaybackRequest.NEXT_ITEM
        PlaybackRequestType.PREVIOUS_ITEM -> SyncPlayPlaybackRequest.PREVIOUS_ITEM
        PlaybackRequestType.SET_REPEAT_MODE -> SyncPlayPlaybackRequest.SET_REPEAT_MODE
        PlaybackRequestType.SET_SHUFFLE_MODE -> SyncPlayPlaybackRequest.SET_SHUFFLE_MODE
        PlaybackRequestType.PING -> SyncPlayPlaybackRequest.PING
        PlaybackRequestType.IGNORE_WAIT -> SyncPlayPlaybackRequest.IGNORE_WAIT
    }

internal fun SendCommand.toDomain(): SyncPlayCommand =
    SyncPlayCommand(
        groupId = groupId,
        playlistItemId = playlistItemId,
        type = command.toDomain(),
        whenAt = `when`.toInstantInSystemZone(),
        positionTicks = positionTicks,
        emittedAt = emittedAt.toInstantInSystemZone(),
    )

internal fun SendCommandType.toDomain(): SyncPlayCommandType =
    when (this) {
        SendCommandType.UNPAUSE -> SyncPlayCommandType.UNPAUSE
        SendCommandType.PAUSE -> SyncPlayCommandType.PAUSE
        SendCommandType.STOP -> SyncPlayCommandType.STOP
        SendCommandType.SEEK -> SyncPlayCommandType.SEEK
    }

internal fun UtcTimeResponse.toDomain(): SyncPlayServerTime =
    SyncPlayServerTime(
        requestReceptionTime = requestReceptionTime.toInstantInSystemZone(),
        responseTransmissionTime = responseTransmissionTime.toInstantInSystemZone(),
    )

internal fun SocketApiState.toDomain(): SyncPlayConnectionState =
    when (this) {
        is SocketApiState.Connected -> SyncPlayConnectionState.CONNECTED
        is SocketApiState.Connecting -> SyncPlayConnectionState.CONNECTING
        is SocketApiState.Disconnected -> SyncPlayConnectionState.DISCONNECTED
    }

internal fun SyncPlayQueueMode.toSdk(): GroupQueueMode =
    when (this) {
        SyncPlayQueueMode.QUEUE -> GroupQueueMode.QUEUE
        SyncPlayQueueMode.QUEUE_NEXT -> GroupQueueMode.QUEUE_NEXT
    }

internal fun SyncPlayRepeatMode.toSdk(): GroupRepeatMode =
    when (this) {
        SyncPlayRepeatMode.REPEAT_NONE -> GroupRepeatMode.REPEAT_NONE
        SyncPlayRepeatMode.REPEAT_ONE -> GroupRepeatMode.REPEAT_ONE
        SyncPlayRepeatMode.REPEAT_ALL -> GroupRepeatMode.REPEAT_ALL
        // 服务端没有 UNKNOWN：未知值按"不重复"兜底，宁可少重复也不误开循环。
        SyncPlayRepeatMode.UNKNOWN -> GroupRepeatMode.REPEAT_NONE
    }

internal fun SyncPlayShuffleMode.toSdk(): GroupShuffleMode =
    when (this) {
        SyncPlayShuffleMode.SORTED -> GroupShuffleMode.SORTED
        SyncPlayShuffleMode.SHUFFLE -> GroupShuffleMode.SHUFFLE
        // 未知值按"顺序播放"兜底，与 UNKNOWN→REPEAT_NONE 同一原则。
        SyncPlayShuffleMode.UNKNOWN -> GroupShuffleMode.SORTED
    }

/**
 * SDK 的 `DateTimeSerializer` 把服务器时间按 `ZoneId.systemDefault()` 解析成 `LocalDateTime`，
 * 序列化方向同样用系统时区格式化；这里用同一时区换回 [Instant]，往返严格一致。 T8b 用契约测试钉死这条换算。
 */
internal fun LocalDateTime.toInstantInSystemZone(): Instant =
    atZone(ZoneId.systemDefault()).toInstant()

internal fun Instant.toServerLocalDateTime(): LocalDateTime =
    atZone(ZoneId.systemDefault()).toLocalDateTime()

/** 异常 → 域错误。沿 cause 链找 `InvalidStatusException`（SDK 常把它包在 `ApiClientException` 里）。 */
internal fun Throwable.toSyncPlayError(groupId: UUID? = null): SyncPlayError {
    var current: Throwable? = this
    while (current != null) {
        when (current) {
            is InvalidStatusException -> return current.toSyncPlayError(groupId)
            is java.net.SocketTimeoutException -> return SyncPlayError.Timeout(current.message)
            is java.io.InterruptedIOException -> return SyncPlayError.Timeout(current.message)
            is java.io.IOException -> return SyncPlayError.Network(current.message)
        }
        current = current.cause
    }
    return SyncPlayError.Unknown(message)
}

private fun InvalidStatusException.toSyncPlayError(groupId: UUID?): SyncPlayError =
    when (val code = status) {
        401 -> SyncPlayError.Unauthorized(message)
        403 -> SyncPlayError.LibraryAccessDenied(groupId, message)
        404 ->
            if (groupId != null) {
                SyncPlayError.GroupDoesNotExist(groupId, message)
            } else {
                SyncPlayError.EndpointUnavailable(message)
            }
        in 500..599 -> SyncPlayError.ServerError(code, message)
        else -> SyncPlayError.HttpError(code, message)
    }
