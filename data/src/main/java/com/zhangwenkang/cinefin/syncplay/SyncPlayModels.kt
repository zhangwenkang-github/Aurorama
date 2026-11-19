package com.zhangwenkang.cinefin.syncplay

import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID

/**
 * W4-T8a：SyncPlay 域模型。
 *
 * 网关（[SyncPlayGateway]）之外不出现任何 jellyfin-sdk 类型；SDK 的 `GroupInfoDto` / `SendCommand` /
 * `SyncPlay*Update` 一律在这里翻成域类型后再交给上层。
 */
enum class SyncPlayGroupState {
    IDLE,
    WAITING,
    PAUSED,
    PLAYING,
    UNKNOWN,
}

enum class SyncPlayConnectionState {
    CONNECTED,
    CONNECTING,
    DISCONNECTED,
}

/** 组快照（`GET /SyncPlay/List`、`GET /SyncPlay/{id}`、Join 后的推送都由它承载）。 */
data class SyncPlayGroup(
    val id: UUID,
    val name: String,
    val state: SyncPlayGroupState,
    val participants: List<String>,
    val lastUpdatedAt: Instant,
)

/**
 * `GET /GetUtcTime` 的两个服务器时间戳。
 *
 * SDK 以系统时区把它解析成 [LocalDateTime]，这里按同一时区换回 [Instant]（严格可逆）； T8b 用契约测试钉死这条换算。
 */
data class SyncPlayServerTime(
    val requestReceptionTime: Instant,
    val responseTransmissionTime: Instant,
)

data class SyncPlayQueueEntry(val itemId: UUID, val playlistItemId: UUID)

data class SyncPlayQueue(
    val reason: SyncPlayQueueUpdateReason,
    val playlist: List<SyncPlayQueueEntry>,
    val playingItemIndex: Int,
    val startPositionTicks: Long,
    val isPlaying: Boolean,
    val shuffleMode: SyncPlayShuffleMode,
    val repeatMode: SyncPlayRepeatMode,
    val lastUpdatedAt: Instant,
)

enum class SyncPlayQueueUpdateReason {
    NEW_PLAYLIST,
    SET_CURRENT_ITEM,
    REMOVE_ITEMS,
    MOVE_ITEM,
    QUEUE,
    QUEUE_NEXT,
    NEXT_ITEM,
    PREVIOUS_ITEM,
    REPEAT_MODE,
    SHUFFLE_MODE,
    UNKNOWN,
}

enum class SyncPlayShuffleMode {
    SORTED,
    SHUFFLE,
    UNKNOWN,
}

enum class SyncPlayRepeatMode {
    REPEAT_NONE,
    REPEAT_ONE,
    REPEAT_ALL,
    UNKNOWN,
}

/** `POST /SyncPlay/Queue` 的入队位置：队尾 [QUEUE] 或下一首 [QUEUE_NEXT]。 */
enum class SyncPlayQueueMode {
    QUEUE,
    QUEUE_NEXT,
}

/** 组状态变化的原因（服务端 `PlaybackRequestType`）。 */
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

data class SyncPlayGroupStateUpdate(
    val state: SyncPlayGroupState,
    val reason: SyncPlayPlaybackRequest,
)

/**
 * 组更新事件（WS `SyncPlayGroupUpdate` 的域形态）。
 *
 * [GroupDoesNotExist] / [LibraryAccessDenied] / [NotInGroup] 是服务器主动踢出语义， 上层据此退组并提示，不要当成普通错误。
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

    data class QueueChanged(
        override val groupId: UUID,
        val queue: SyncPlayQueue,
    ) : SyncPlayGroupUpdate

    data class NotInGroup(override val groupId: UUID) : SyncPlayGroupUpdate

    data class GroupDoesNotExist(override val groupId: UUID) : SyncPlayGroupUpdate

    data class LibraryAccessDenied(override val groupId: UUID) : SyncPlayGroupUpdate

    data class Unknown(override val groupId: UUID) : SyncPlayGroupUpdate
}

enum class SyncPlayCommandType {
    UNPAUSE,
    PAUSE,
    STOP,
    SEEK,
    UNKNOWN,
}

/**
 * 播放命令（WS `SyncPlayCommand` 的域形态）。
 *
 * [whenAt] 是"何时执行"的服务器时刻；[positionTicks] 只对 SEEK/PAUSE 等带位置的命令有意义（可缺省）。 时刻换算（服务器时区 → 本地时钟）由 T8b
 * SyncClock 负责，这里只做无损搬运。
 */
data class SyncPlayCommand(
    val groupId: UUID,
    val playlistItemId: UUID,
    val type: SyncPlayCommandType,
    val whenAt: Instant,
    val positionTicks: Long?,
    val emittedAt: Instant,
)

/**
 * 入组前能力探针结果。
 *
 * [Unavailable] 是降级信号（上层隐藏 SyncPlay 入口），不是异常路径。
 */
sealed interface SyncPlayCapability {
    data class Available(
        val groups: List<SyncPlayGroup>,
        val serverTime: SyncPlayServerTime,
    ) : SyncPlayCapability

    data class Unavailable(
        val reason: SyncPlayUnavailableReason,
        val error: SyncPlayError? = null,
    ) : SyncPlayCapability
}

enum class SyncPlayUnavailableReason {
    /** 组列表调用失败（含服务器无 SyncPlay 端点 = 404）。 */
    GROUP_LIST_FAILED,

    /** 时间同步调用失败（无 `GetUtcTime` 端点或网络问题）。 */
    SERVER_TIME_FAILED,
}
