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

internal enum class SelectedPlaybackFailureStage {
    FIRST_RESOLUTION,
    QUEUE_ACCEPTANCE,
    SUBSEQUENT_ADDITION,
}

internal data class SelectedPlaybackFailure(
    val stage: SelectedPlaybackFailureStage,
    val triggerSong: MusicSong,
    val acceptedSongs: List<MusicSong>,
    val unacceptedSongs: List<MusicSong>,
    val cause: Throwable,
)

internal data class SelectedPlaybackFailureUi(
    val title: String,
    val message: String,
)

internal fun selectedPlaybackFailureUi(
    failure: SelectedPlaybackFailure
): SelectedPlaybackFailureUi =
    when (failure.stage) {
        SelectedPlaybackFailureStage.FIRST_RESOLUTION ->
            SelectedPlaybackFailureUi(
                title = "播放失败",
                message =
                    failure.cause.message?.takeIf { message -> message.isNotBlank() }
                        ?: "无法解析「${failure.triggerSong.name}」的播放地址",
            )
        SelectedPlaybackFailureStage.QUEUE_ACCEPTANCE ->
            SelectedPlaybackFailureUi(
                title = "播放失败",
                message =
                    "播放队列受理失败：" +
                        (failure.cause.message?.takeIf { message -> message.isNotBlank() }
                            ?: "请稍后重试"),
            )
        SelectedPlaybackFailureStage.SUBSEQUENT_ADDITION ->
            SelectedPlaybackFailureUi(
                title = "部分歌曲未能加入队列",
                message =
                    "已起播或已受理 ${failure.acceptedSongs.size} 首；" +
                        "${failure.unacceptedSongs.size} 首未能加入队列。",
            )
    }

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
    onFailure: (SelectedPlaybackFailure) -> Unit,
    onPartialFailure: (SelectedPlaybackFailure) -> Unit = {},
    isCurrentRequest: (Long) -> Boolean = {
        pendingPlayback.value?.let { pending -> pending.requestId == it } ?: true
    },
): Job? {
    if (songs.isEmpty() || pendingPlayback.value != null) return null
    val firstSong = songs.first()
    val pending = PendingPlaybackUi(requestId = requestId, item = firstSong.toPendingPlayerItem())
    pendingPlayback.value = pending
    return scope.launch {
        fun failCurrent(
            error: Exception,
            stage: SelectedPlaybackFailureStage,
        ) {
            if (!isCurrentRequest(requestId)) return
            if (pendingPlayback.value?.requestId == requestId) {
                pendingPlayback.value = pendingPlaybackAfterFailure(pending, requestId)
            }
            onFailure(
                SelectedPlaybackFailure(
                    stage = stage,
                    triggerSong = firstSong,
                    acceptedSongs = emptyList(),
                    unacceptedSongs = songs,
                    cause = error,
                )
            )
        }

        fun reportPartialFailure(
            triggerSong: MusicSong,
            acceptedSongs: List<MusicSong>,
            cause: Throwable,
        ) {
            if (!isCurrentRequest(requestId)) return
            val acceptedIds = acceptedSongs.mapTo(mutableSetOf()) { song -> song.itemId }
            onPartialFailure(
                SelectedPlaybackFailure(
                    stage = SelectedPlaybackFailureStage.SUBSEQUENT_ADDITION,
                    triggerSong = triggerSong,
                    acceptedSongs = acceptedSongs,
                    unacceptedSongs = songs.filter { song -> song.itemId !in acceptedIds },
                    cause = cause,
                )
            )
        }

        val first =
            try {
                resolve(firstSong)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                failCurrent(error, SelectedPlaybackFailureStage.FIRST_RESOLUTION)
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
            failCurrent(error, SelectedPlaybackFailureStage.QUEUE_ACCEPTANCE)
            return@launch
        }

        val acceptedSongs = mutableListOf(firstSong)
        val unresolved = mutableMapOf<java.util.UUID, SelectedPlaybackResolutionFailure>()
        for (chunk in songs.drop(1).chunked(SELECTED_PLAYBACK_PREFETCH_CONCURRENCY)) {
            if (!isCurrentRequest(requestId) || !shouldContinue()) break
            val resolutions = coroutineScope {
                chunk
                    .map { song ->
                        async {
                            try {
                                SelectedPlaybackResolution(
                                    song = song,
                                    item = resolve(song),
                                    failure = null,
                                )
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (error: Exception) {
                                SelectedPlaybackResolution(
                                    song = song,
                                    item = null,
                                    failure = error,
                                )
                            }
                        }
                    }
                    .awaitAll()
            }
            if (!isCurrentRequest(requestId)) break
            resolutions.forEach { resolution ->
                val failure = resolution.failure
                if (failure != null) {
                    unresolved[resolution.song.itemId] =
                        SelectedPlaybackResolutionFailure(
                            song = resolution.song,
                            cause = failure,
                        )
                }
            }
            val resolved = resolutions.mapNotNull(SelectedPlaybackResolution::item)
            if (resolved.isNotEmpty()) {
                val resolvedSongs =
                    resolutions
                        .filter { resolution -> resolution.item != null }
                        .map(SelectedPlaybackResolution::song)
                try {
                    appendItems(resolved)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    reportPartialFailure(
                        triggerSong = resolvedSongs.first(),
                        acceptedSongs = acceptedSongs,
                        cause = error,
                    )
                    return@launch
                }
                acceptedSongs += resolvedSongs
            }
        }
        if (!isCurrentRequest(requestId)) return@launch
        unresolved.values.firstOrNull()?.let { failure ->
            reportPartialFailure(
                triggerSong = failure.song,
                acceptedSongs = acceptedSongs,
                cause = failure.cause,
            )
        }
    }
}

private data class SelectedPlaybackResolution(
    val song: MusicSong,
    val item: PlayerItem?,
    val failure: Exception?,
)

private data class SelectedPlaybackResolutionFailure(
    val song: MusicSong,
    val cause: Exception,
)

private const val SELECTED_PLAYBACK_PREFETCH_CONCURRENCY = 4
