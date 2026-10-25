package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 点歌后的乐观展示状态（W84）。
 *
 * [item] 只用于歌曲高亮与底部正在播放条；真正播放仍由旧队列继续，直到目标曲解析完成并调用 `setQueue`。 因此这里不修改
 * `playbackController.queue`，解析失败时清掉本状态即可让 UI 回到仍在播放的旧曲。
 */
internal data class PendingPlaybackUi(
    val requestId: Long,
    val item: PlayerItem,
)

internal fun MusicSong.toPendingPlayerItem(): PlayerItem =
    PlayerItem(
        name = name,
        itemId = itemId,
        mediaSourceId = "",
        playbackPosition = resumePositionMs,
        parentIndexNumber = null,
        indexNumber = indexNumber,
        artist = artist,
        thumbnailUri = imageUri,
    )

/** 底部条 / 当前曲高亮优先显示乐观目标；没有待解析目标时回到播放器真实当前曲。 */
internal fun displayPlaybackItem(
    pending: PendingPlaybackUi?,
    liveItem: PlayerItem?,
): PlayerItem? = pending?.item ?: liveItem

/** 只有失败请求仍是当前请求时才回滚，避免较慢的旧请求覆盖用户刚点的新目标。 */
internal fun pendingPlaybackAfterFailure(
    pending: PendingPlaybackUi?,
    failedRequestId: Long,
): PendingPlaybackUi? = pending?.takeUnless { it.requestId == failedRequestId }

internal fun launchSelectedPlayback(
    scope: CoroutineScope,
    songs: List<MusicSong>,
    pendingPlayback: MutableStateFlow<PendingPlaybackUi?>,
    requestId: Long,
    resolve: suspend (MusicSong) -> PlayerItem,
    onFirstResolved: (PlayerItem) -> Unit,
    onAccepted: () -> Unit,
    appendItems: (List<PlayerItem>) -> Unit,
    shouldContinue: () -> Boolean = { true },
    onFailure: (MusicSong, Throwable) -> Unit,
    isCurrentRequest: (Long) -> Boolean = {
        pendingPlayback.value?.let { pending -> pending.requestId == it } ?: true
    },
): Job? {
    if (songs.isEmpty() || pendingPlayback.value != null) return null
    val firstSong = songs.first()
    val pending = PendingPlaybackUi(requestId = requestId, item = firstSong.toPendingPlayerItem())
    pendingPlayback.value = pending
    return scope.launch {
        fun failCurrent(error: Exception) {
            if (!isCurrentRequest(requestId)) return
            if (pendingPlayback.value?.requestId == requestId) {
                pendingPlayback.value = pendingPlaybackAfterFailure(pending, requestId)
            }
            onFailure(firstSong, error)
        }

        val first =
            try {
                resolve(firstSong)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failCurrent(error)
                return@launch
            }
        if (!isCurrentRequest(requestId) || pendingPlayback.value?.requestId != requestId) {
            return@launch
        }
        try {
            onFirstResolved(first)
            if (!isCurrentRequest(requestId)) return@launch
            pendingPlayback.value = null
            onAccepted()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            failCurrent(error)
            return@launch
        }
        for (chunk in songs.drop(1).chunked(SELECTED_PLAYBACK_PREFETCH_CONCURRENCY)) {
            if (!isCurrentRequest(requestId) || !shouldContinue()) break
            val resolved = coroutineScope {
                chunk
                    .map { song ->
                        async {
                            try {
                                resolve(song)
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (_: Exception) {
                                null
                            }
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }
            if (!isCurrentRequest(requestId)) break
            if (resolved.isNotEmpty()) {
                try {
                    appendItems(resolved)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    failCurrent(error)
                    return@launch
                }
            }
        }
    }
}

private const val SELECTED_PLAYBACK_PREFETCH_CONCURRENCY = 4
