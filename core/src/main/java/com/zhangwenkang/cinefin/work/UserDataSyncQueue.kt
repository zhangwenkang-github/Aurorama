package com.zhangwenkang.cinefin.work

import com.zhangwenkang.cinefin.models.FindroidUserDataDto
import java.util.UUID
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto

/**
 * W9-T4：补同步扫描集 = 各表投影行 id ∪ 全部 `toBeSynced` 队列行 id（去重保序）。
 *
 * 只按表投影扫会漏掉没有投影的条目（音乐收藏只有 `userdata` 行）；只按队列行扫则要求调用方先能列出队列，二者合一才 能把 WRITE-08「季 / 剧 / 音乐排队写不补传」一次收口。
 */
internal fun userDataSyncItemIds(vararg sources: List<UUID>): List<UUID> =
    sources.flatMap { it }.distinct()

/**
 * W9-T4：推送载荷。
 *
 * [mirrored] = true（movies / episodes / shows / seasons 有投影）：本地行是完整用户数据镜像，推全量三字段。
 *
 * [mirrored] = false（音乐等无投影条目）：本地行只知道收藏意图，只推 `isFavorite`——其余字段留 null，由服务端保留 原值（Jellyfin 的
 * `UpdateUserItemDataDto` 忽略 null 字段），避免把 played / 播放进度覆盖成默认值。
 */
internal fun queuedUserDataUpdateDto(
    userData: FindroidUserDataDto,
    mirrored: Boolean,
): UpdateUserItemDataDto =
    if (mirrored) {
        UpdateUserItemDataDto(
            playbackPositionTicks = userData.playbackPositionTicks,
            isFavorite = userData.favorite,
            played = userData.played,
        )
    } else {
        UpdateUserItemDataDto(isFavorite = userData.favorite)
    }

/**
 * W9-T4：逐条补传。
 *
 * 只有服务端确认（[push] 正常返回）才清 `toBeSynced`；失败保留排队行等下一次调度（WRITE-08 既有语义），并且单条失败不 阻断后续条目。协程取消照常向上传播。
 */
internal suspend fun pushQueuedUserData(
    itemIds: List<UUID>,
    loadQueued: suspend (UUID) -> FindroidUserDataDto?,
    mirrored: suspend (UUID) -> Boolean,
    push: suspend (FindroidUserDataDto, UpdateUserItemDataDto) -> Unit,
    clear: suspend (UUID) -> Unit,
) {
    for (itemId in itemIds) {
        val userData = loadQueued(itemId) ?: continue
        try {
            push(userData, queuedUserDataUpdateDto(userData, mirrored(itemId)))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            continue
        }
        clear(itemId)
    }
}
