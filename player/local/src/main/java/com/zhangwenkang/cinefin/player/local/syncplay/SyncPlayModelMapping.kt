package com.zhangwenkang.cinefin.player.local.syncplay

import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayCommand as CoreSyncPlayCommand
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayCommandType as CoreSyncPlayCommandType
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroup as CoreSyncPlayGroup
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupState as CoreSyncPlayGroupState
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupStateUpdate as CoreSyncPlayGroupStateUpdate
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayGroupUpdate as CoreSyncPlayGroupUpdate
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayPlaybackRequest as CoreSyncPlayPlaybackRequest
import com.zhangwenkang.cinefin.player.core.syncplay.SyncPlayServerTimeSample
import com.zhangwenkang.cinefin.syncplay.SyncPlayCommand as DataSyncPlayCommand
import com.zhangwenkang.cinefin.syncplay.SyncPlayCommandType as DataSyncPlayCommandType
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroup as DataSyncPlayGroup
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroupState as DataSyncPlayGroupState
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroupStateUpdate as DataSyncPlayGroupStateUpdate
import com.zhangwenkang.cinefin.syncplay.SyncPlayGroupUpdate as DataSyncPlayGroupUpdate
import com.zhangwenkang.cinefin.syncplay.SyncPlayPlaybackRequest as DataSyncPlayPlaybackRequest
import com.zhangwenkang.cinefin.syncplay.SyncPlayServerTime

/**
 * data → player/core 的模型搬运（W4-T8d 装配层）。
 *
 * 两侧同名类型字段逐一对齐（见 T8c 设计定稿），`player:core` 不依赖 `data`，所以这份映射只能落在同时看得见两者的
 * `player:local`。映射是纯函数，单测逐值覆盖。
 *
 * 队列事件（data 侧 `SyncPlayGroupUpdate.QueueChanged`）不进状态机：W4 组队列编辑 DEFERRED，[toCore] 直接返回 null 由调用方丢弃。
 */
internal fun DataSyncPlayCommand.toCore(): CoreSyncPlayCommand =
    CoreSyncPlayCommand(
        groupId = groupId,
        playlistItemId = playlistItemId,
        type = type.toCore(),
        whenAt = whenAt,
        positionTicks = positionTicks,
        emittedAt = emittedAt,
    )

internal fun DataSyncPlayCommandType.toCore(): CoreSyncPlayCommandType =
    when (this) {
        DataSyncPlayCommandType.UNPAUSE -> CoreSyncPlayCommandType.UNPAUSE
        DataSyncPlayCommandType.PAUSE -> CoreSyncPlayCommandType.PAUSE
        DataSyncPlayCommandType.STOP -> CoreSyncPlayCommandType.STOP
        DataSyncPlayCommandType.SEEK -> CoreSyncPlayCommandType.SEEK
        DataSyncPlayCommandType.UNKNOWN -> CoreSyncPlayCommandType.UNKNOWN
    }

internal fun DataSyncPlayGroup.toCore(): CoreSyncPlayGroup =
    CoreSyncPlayGroup(
        id = id,
        name = name,
        state = state.toCore(),
        participants = participants,
        lastUpdatedAt = lastUpdatedAt,
    )

internal fun DataSyncPlayGroupState.toCore(): CoreSyncPlayGroupState =
    when (this) {
        DataSyncPlayGroupState.IDLE -> CoreSyncPlayGroupState.IDLE
        DataSyncPlayGroupState.WAITING -> CoreSyncPlayGroupState.WAITING
        DataSyncPlayGroupState.PAUSED -> CoreSyncPlayGroupState.PAUSED
        DataSyncPlayGroupState.PLAYING -> CoreSyncPlayGroupState.PLAYING
        DataSyncPlayGroupState.UNKNOWN -> CoreSyncPlayGroupState.UNKNOWN
    }

internal fun DataSyncPlayPlaybackRequest.toCore(): CoreSyncPlayPlaybackRequest =
    when (this) {
        DataSyncPlayPlaybackRequest.PLAY -> CoreSyncPlayPlaybackRequest.PLAY
        DataSyncPlayPlaybackRequest.SET_PLAYLIST_ITEM ->
            CoreSyncPlayPlaybackRequest.SET_PLAYLIST_ITEM
        DataSyncPlayPlaybackRequest.REMOVE_FROM_PLAYLIST ->
            CoreSyncPlayPlaybackRequest.REMOVE_FROM_PLAYLIST
        DataSyncPlayPlaybackRequest.MOVE_PLAYLIST_ITEM ->
            CoreSyncPlayPlaybackRequest.MOVE_PLAYLIST_ITEM
        DataSyncPlayPlaybackRequest.QUEUE -> CoreSyncPlayPlaybackRequest.QUEUE
        DataSyncPlayPlaybackRequest.UNPAUSE -> CoreSyncPlayPlaybackRequest.UNPAUSE
        DataSyncPlayPlaybackRequest.PAUSE -> CoreSyncPlayPlaybackRequest.PAUSE
        DataSyncPlayPlaybackRequest.STOP -> CoreSyncPlayPlaybackRequest.STOP
        DataSyncPlayPlaybackRequest.SEEK -> CoreSyncPlayPlaybackRequest.SEEK
        DataSyncPlayPlaybackRequest.BUFFER -> CoreSyncPlayPlaybackRequest.BUFFER
        DataSyncPlayPlaybackRequest.READY -> CoreSyncPlayPlaybackRequest.READY
        DataSyncPlayPlaybackRequest.NEXT_ITEM -> CoreSyncPlayPlaybackRequest.NEXT_ITEM
        DataSyncPlayPlaybackRequest.PREVIOUS_ITEM -> CoreSyncPlayPlaybackRequest.PREVIOUS_ITEM
        DataSyncPlayPlaybackRequest.SET_REPEAT_MODE -> CoreSyncPlayPlaybackRequest.SET_REPEAT_MODE
        DataSyncPlayPlaybackRequest.SET_SHUFFLE_MODE -> CoreSyncPlayPlaybackRequest.SET_SHUFFLE_MODE
        DataSyncPlayPlaybackRequest.PING -> CoreSyncPlayPlaybackRequest.PING
        DataSyncPlayPlaybackRequest.IGNORE_WAIT -> CoreSyncPlayPlaybackRequest.IGNORE_WAIT
        DataSyncPlayPlaybackRequest.UNKNOWN -> CoreSyncPlayPlaybackRequest.UNKNOWN
    }

internal fun DataSyncPlayGroupStateUpdate.toCore(): CoreSyncPlayGroupStateUpdate =
    CoreSyncPlayGroupStateUpdate(state = state.toCore(), reason = reason.toCore())

/** @return null = 该事件不进状态机（当前只有 `QueueChanged`）。 */
internal fun DataSyncPlayGroupUpdate.toCore(): CoreSyncPlayGroupUpdate? =
    when (this) {
        is DataSyncPlayGroupUpdate.GroupJoined ->
            CoreSyncPlayGroupUpdate.GroupJoined(groupId = groupId, group = group.toCore())
        is DataSyncPlayGroupUpdate.GroupLeft -> CoreSyncPlayGroupUpdate.GroupLeft(groupId = groupId)
        is DataSyncPlayGroupUpdate.UserJoined ->
            CoreSyncPlayGroupUpdate.UserJoined(groupId = groupId, userName = userName)
        is DataSyncPlayGroupUpdate.UserLeft ->
            CoreSyncPlayGroupUpdate.UserLeft(groupId = groupId, userName = userName)
        is DataSyncPlayGroupUpdate.StateChanged ->
            CoreSyncPlayGroupUpdate.StateChanged(groupId = groupId, update = update.toCore())
        is DataSyncPlayGroupUpdate.QueueChanged -> null
        is DataSyncPlayGroupUpdate.NotInGroup ->
            CoreSyncPlayGroupUpdate.NotInGroup(groupId = groupId)
        is DataSyncPlayGroupUpdate.GroupDoesNotExist ->
            CoreSyncPlayGroupUpdate.GroupDoesNotExist(groupId = groupId)
        is DataSyncPlayGroupUpdate.LibraryAccessDenied ->
            CoreSyncPlayGroupUpdate.LibraryAccessDenied(groupId = groupId)
        is DataSyncPlayGroupUpdate.Unknown -> CoreSyncPlayGroupUpdate.Unknown(groupId = groupId)
    }

/** `GetUtcTime` 的两个时间戳搬运（T8b 的 [SyncPlayServerTimeSample] 只吃这两个字段）。 */
internal fun SyncPlayServerTime.toCoreSample(): SyncPlayServerTimeSample =
    SyncPlayServerTimeSample(
        requestReceptionTime = requestReceptionTime,
        responseTransmissionTime = responseTransmissionTime,
    )
