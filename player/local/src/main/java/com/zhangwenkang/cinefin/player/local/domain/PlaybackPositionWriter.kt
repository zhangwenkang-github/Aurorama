package com.zhangwenkang.cinefin.player.local.domain

import com.zhangwenkang.cinefin.api.JellyfinApi
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.UpdateUserItemDataDto
import timber.log.Timber

/**
 * W20（§1.11 进度记忆）：把播放位置**直接写进 Jellyfin UserData**。
 *
 * 背景（Pad 5 真机实测）：`Sessions/Playing/Progress` 只更新服务端会话里的 PlayState， `GET
 * /Users/{userId}/Items/{itemId}` 读回的 `UserData.PlaybackPositionTicks` 在停止上报之前一直是 0—— 进程被系统杀掉 /
 * 崩溃时进度全丢。所以播放中定期（以及暂停 / 切集 / 退出时）用 `POST /Users/{userId}/Items/{itemId}/UserData` 把位置落库。
 *
 * 只带 `PlaybackPositionTicks` 一个字段：不动收藏 / 已看 / 评分等其它用户数据； 写用户数据属 `docs/REQUIREMENTS.md` §11
 * 白名单（禁止写媒体库 / 服务器设置）。 失败只记 debug 日志，不影响播放。
 *
 * 说明：`data` 模块本波只允许**新增文件**、且该模块未引 `javax.inject`， 因此这条写入器放在 `player:local`（用 `data` 暴露的
 * [JellyfinApi]）， 不触碰 `data/.../JellyfinRepository*.kt` 这些与 W21 共享的公共文件。
 */
@Singleton
class PlaybackPositionWriter @Inject constructor(private val jellyfinApi: JellyfinApi) {

    /** [positionTicks] 为 Jellyfin 的 100ns tick（毫秒 × 10000）。 */
    suspend fun writePosition(
        itemId: UUID,
        positionTicks: Long,
        /** 本地判断：`local-` 合成源 id（[isLocalPlaybackSource]）。 */
        mediaSourceId: String? = null,
        /** 本地判断：SAF `content://` 播放 URI。 */
        sourceUri: String? = null,
    ) {
        if (positionTicks < 0L) return
        // 本地媒体库条目在服务器上没有 UserData：写回必然 404（VIDEO-48 验收发现的噪音）。
        if (isLocalPlaybackSource(mediaSourceId, sourceUri)) {
            Timber.d("本地媒体库条目跳过服务器进度回写：%s", itemId)
            return
        }
        val userId = jellyfinApi.userId ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                /*
                 * SDK 1.8.12 的 itemsApi.updateItemUserData 两个 UUID 参数顺序是
                 * （path 的 itemId，query 的 userId）——真机实测按「(userId, itemId)」传会把
                 * 两者拼错：`POST /UserItems/{userId}/UserData?userId={itemId}` → 400。
                 */
                jellyfinApi.itemsApi.updateItemUserData(
                    itemId,
                    userId,
                    UpdateUserItemDataDto(playbackPositionTicks = positionTicks),
                )
            }
                .onFailure { Timber.d(it, "进度写入 UserData 失败（网络 / 权限），不影响播放") }
        }
    }
}

/** 本地媒体库合成源 id 的前缀（`asFindroidSource` 侧 `local-<itemId>`）。 */
internal const val LOCAL_MEDIA_SOURCE_ID_PREFIX = "local-"

/**
 * 本地媒体库条目的统一判据（进度回写 / 错误卡文案共用）： 合成源 id 带 `local-` 前缀，播放 URI 是 SAF `content://`。
 *
 * 服务器上没有这些条目：任何 Online 侧的 UserData / 会话上报都只会 404。
 */
internal fun isLocalPlaybackSource(
    mediaSourceId: String?,
    sourceUri: String?,
): Boolean =
    mediaSourceId?.startsWith(LOCAL_MEDIA_SOURCE_ID_PREFIX, ignoreCase = true) == true ||
        sourceUri?.startsWith("content://", ignoreCase = true) == true
