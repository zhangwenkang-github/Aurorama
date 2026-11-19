package com.zhangwenkang.cinefin.player.core.syncplay

import java.time.Instant
import java.util.UUID

/** Jellyfin 的 ticks 精度：1 tick = 100ns，即 1ms = 10_000 ticks。 */
internal const val TICKS_PER_MILLISECOND: Long = 10_000L

/** ticks → 毫秒（向下取整）。组命令的 `positionTicks` 只有这一种消费方式。 */
internal fun ticksToMillis(ticks: Long): Long = ticks / TICKS_PER_MILLISECOND

/**
 * 组命令类型（W4-T8c）。
 *
 * 取值与 data 层 `SyncPlayCommandType` 一一对应：`UNPAUSE` 即任务卡里的「Play」。 `player:core` 不依赖 `data`，T8d
 * 装配时逐值搬运。队列类变化走组事件而不是命令（data 侧命令类型里没有 QUEUE），状态机不消费。
 */
enum class SyncPlayCommandType {
    UNPAUSE,
    PAUSE,
    STOP,
    SEEK,
    UNKNOWN,
}

/**
 * 播放命令（WS `SyncPlayCommand` 的 player/core 形态）。
 *
 * 字段与 data 层 `SyncPlayCommand` 一一对应：[whenAt] 是"何时执行"的服务器时刻，[emittedAt] 是服务器发出时刻，[positionTicks] 只对
 * SEEK/PAUSE/UNPAUSE 有意义（可缺省）。时刻比对一律走 [SyncPlayClock.nowServer]，不做本地时区换算。
 */
data class SyncPlayCommand(
    val groupId: UUID,
    val playlistItemId: UUID,
    val type: SyncPlayCommandType,
    val whenAt: Instant,
    val positionTicks: Long?,
    val emittedAt: Instant,
)

/** 组状态：与 data 层 `SyncPlayGroupState` 一一对应。 */
enum class SyncPlayGroupState {
    IDLE,
    WAITING,
    PAUSED,
    PLAYING,
    UNKNOWN,
}

/** 组快照：字段与 data 层 `SyncPlayGroup` 一一对应（[SyncPlayGroupUpdate.GroupJoined] 携带）。 */
data class SyncPlayGroup(
    val id: UUID,
    val name: String,
    val state: SyncPlayGroupState,
    val participants: List<String>,
    val lastUpdatedAt: Instant,
)

/** 组状态变化原因：与 data 层 `SyncPlayPlaybackRequest` 一一对应。 */
enum class SyncPlayPlaybackRequest {
    PLAY,
    SET_PLAYLIST_ITEM,
    REMOVE_FROM_PLAYLIST,
    MOVE_PLAYLIST_ITEM,
    QUEUE,
    UNPAUSE,
    PAUSE,
    STOP,
    SEEK,
    BUFFER,
    READY,
    NEXT_ITEM,
    PREVIOUS_ITEM,
    SET_REPEAT_MODE,
    SET_SHUFFLE_MODE,
    PING,
    IGNORE_WAIT,
    UNKNOWN,
}

/** 组状态变化：与 data 层 `SyncPlayGroupStateUpdate` 一一对应。 */
data class SyncPlayGroupStateUpdate(
    val state: SyncPlayGroupState,
    val reason: SyncPlayPlaybackRequest,
)

/**
 * 组事件（WS `SyncPlayGroupUpdate` 的 player/core 形态），字段与 data 层同名类型一一对应。
 *
 * [NotInGroup] / [GroupDoesNotExist] / [LibraryAccessDenied] 是服务器主动踢出语义，状态机一律按退组处理。
 *
 * 队列事件（data 侧 `QueueChanged`）不进状态机：组队列编辑在 W4 里 DEFERRED，T8d 映射时直接丢弃即可。
 */
sealed interface SyncPlayGroupUpdate {
    val groupId: UUID

    data class GroupJoined(
        override val groupId: UUID,
        val group: SyncPlayGroup,
    ) : SyncPlayGroupUpdate

    data class GroupLeft(override val groupId: UUID) : SyncPlayGroupUpdate

    data class UserJoined(
        override val groupId: UUID,
        val userName: String?,
    ) : SyncPlayGroupUpdate

    data class UserLeft(
        override val groupId: UUID,
        val userName: String?,
    ) : SyncPlayGroupUpdate

    data class StateChanged(
        override val groupId: UUID,
        val update: SyncPlayGroupStateUpdate,
    ) : SyncPlayGroupUpdate

    data class NotInGroup(override val groupId: UUID) : SyncPlayGroupUpdate

    data class GroupDoesNotExist(override val groupId: UUID) : SyncPlayGroupUpdate

    data class LibraryAccessDenied(override val groupId: UUID) : SyncPlayGroupUpdate

    data class Unknown(override val groupId: UUID) : SyncPlayGroupUpdate
}
