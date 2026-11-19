package com.zhangwenkang.cinefin.syncplay

import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.Flow

/**
 * W4-T8a：SyncPlay 网关（SDK 适配层）。
 *
 * 职责边界：
 * - REST 动词 → [SyncPlayResult]（成功值或 [SyncPlayError]），不向调用方抛业务异常；
 * - WS 消息 → [SyncPlayGroupUpdate] / [SyncPlayCommand] 冷流；收集即订阅，收集结束即退订；
 * - 入组前能力探针 [probeCapability]，任一调用失败都返回 [SyncPlayCapability.Unavailable] 供上层隐藏入口。
 *
 * 实现与 SDK 的接线见 [SyncPlayGatewayImpl]；域类型定义见 `SyncPlayModels.kt`。
 */
interface SyncPlayGateway {
    /** WS 连接态；断线重连由 SDK 负责，T8d 用它决定"先 GetGroup 拉快照再恢复"。 */
    val connectionState: Flow<SyncPlayConnectionState>

    /** 组更新事件流（组内进退人、状态变化、队列变化、被踢出等）。 */
    val groupUpdates: Flow<SyncPlayGroupUpdate>

    /** 播放命令流（PAUSE/UNPAUSE/STOP/SEEK）。 */
    val commands: Flow<SyncPlayCommand>

    /**
     * 入组前能力探针：先 `GET /SyncPlay/List` 再 `GET /GetUtcTime`。
     *
     * 任一失败（含服务器无端点 = 404）都返回 [SyncPlayCapability.Unavailable]，**不抛异常**； 仅协程取消会原样抛出。
     */
    suspend fun probeCapability(): SyncPlayCapability

    /** `GET /GetUtcTime`：T8b 时钟同步的唯一时间源。 */
    suspend fun getServerTime(): SyncPlayResult<SyncPlayServerTime>

    // —— 组生命周期 ——

    suspend fun getGroups(): SyncPlayResult<List<SyncPlayGroup>>

    suspend fun getGroup(groupId: UUID): SyncPlayResult<SyncPlayGroup>

    suspend fun createGroup(name: String): SyncPlayResult<SyncPlayGroup>

    suspend fun joinGroup(groupId: UUID): SyncPlayResult<Unit>

    suspend fun leaveGroup(): SyncPlayResult<Unit>

    // —— 播放与队列（条目 id 均为 Jellyfin itemId） ——

    /** `POST /SyncPlay/SetNewQueue`：以给定队列开播（T8e 创建组后本地播放时使用）。 */
    suspend fun playQueue(
        playlistItemIds: List<UUID>,
        playingItemIndex: Int,
        startPositionTicks: Long,
    ): SyncPlayResult<Unit>

    /** `POST /SyncPlay/SetPlaylistItem`：切到队列中的某一项。 */
    suspend fun setPlaylistItem(playlistItemId: UUID): SyncPlayResult<Unit>

    suspend fun nextItem(playlistItemId: UUID): SyncPlayResult<Unit>

    suspend fun previousItem(playlistItemId: UUID): SyncPlayResult<Unit>

    suspend fun pause(): SyncPlayResult<Unit>

    suspend fun unpause(): SyncPlayResult<Unit>

    suspend fun stop(): SyncPlayResult<Unit>

    suspend fun seek(positionTicks: Long): SyncPlayResult<Unit>

    /** `POST /SyncPlay/Queue`：追加或插队。 */
    suspend fun queue(
        playlistItemIds: List<UUID>,
        mode: SyncPlayQueueMode = SyncPlayQueueMode.QUEUE,
    ): SyncPlayResult<Unit>

    suspend fun movePlaylistItem(
        playlistItemId: UUID,
        newIndex: Int,
    ): SyncPlayResult<Unit>

    suspend fun removeFromPlaylist(
        playlistItemIds: List<UUID>,
        clearPlaylist: Boolean = false,
        clearPlayingItem: Boolean = false,
    ): SyncPlayResult<Unit>

    suspend fun setRepeatMode(mode: SyncPlayRepeatMode): SyncPlayResult<Unit>

    suspend fun setShuffleMode(mode: SyncPlayShuffleMode): SyncPlayResult<Unit>

    /** `POST /SyncPlay/SetIgnoreWait`：跳过"等待全员就绪"。 */
    suspend fun setIgnoreWait(ignoreWait: Boolean): SyncPlayResult<Unit>

    /**
     * `POST /SyncPlay/Ready`：本机已就绪。
     *
     * [whenAt] 是命令要执行的服务器时刻（T8b 给出的服务器时钟换算结果），不可为空。
     */
    suspend fun reportReady(
        whenAt: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ): SyncPlayResult<Unit>

    /** `POST /SyncPlay/Buffering`：本机开始/结束缓冲。 */
    suspend fun reportBuffering(
        whenAt: Instant,
        positionTicks: Long,
        isPlaying: Boolean,
        playlistItemId: UUID,
    ): SyncPlayResult<Unit>

    /** `POST /SyncPlay/Ping`：组内保活。 */
    suspend fun ping(pingMillis: Long): SyncPlayResult<Unit>
}
