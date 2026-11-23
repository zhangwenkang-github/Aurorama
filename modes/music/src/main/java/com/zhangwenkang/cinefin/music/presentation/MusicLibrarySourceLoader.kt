package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicLibrary
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import timber.log.Timber

internal data class MusicLibrarySources(
    val library: MusicLibrary?,
    val playlists: List<MusicPlaylist>?,
    val libraryError: Throwable?,
    val playlistsError: Throwable?,
    /**
     * W9-T5（F4）：[library] 来自「已下载曲库」的降级读取（服务器曲库加载失败时），不是服务器曲库。
     *
     * 降级内容只用于填补空白（上次没有曲库时），不覆盖已上屏的服务器曲库，也不触发音乐库列表请求。
     */
    val libraryFallback: Boolean = false,
)

/**
 * 曲库与服务器歌单摘要各自独立加载。任一来源先完成就先回传，避免曲库失败阻止磁盘歌单摘要上屏。
 *
 * 离线模式只调用本地曲库读取；不接触任何服务器来源。
 *
 * W9-T5（F4）：在线模式但服务器曲库不可达（飞行模式 / 断网）时，降级读本机已下载曲库 —— 冷启动也能看到已下载音乐； 已下载曲库为空时保持原错误态（[libraryError]
 * 原样上报，是否阻塞由页面按内容判定）。
 */
internal suspend fun loadMusicLibrarySources(
    offline: Boolean,
    forceServerPlaylistRefresh: Boolean,
    loadLibrary: suspend () -> MusicLibrary,
    loadOfflineLibrary: suspend () -> MusicLibrary,
    loadPlaylists: suspend (Boolean) -> List<MusicPlaylist>,
    onLibraryLoaded: (MusicLibrary) -> Unit,
    onPlaylistsLoaded: (List<MusicPlaylist>) -> Unit,
): MusicLibrarySources {
    if (offline) {
        val library = captureSource(loadOfflineLibrary)
        library.getOrNull()?.let(onLibraryLoaded)
        return MusicLibrarySources(
            library = library.getOrNull(),
            playlists = emptyList(),
            libraryError = library.exceptionOrNull(),
            playlistsError = null,
        )
    }

    return supervisorScope {
        val library = async {
            captureSource(loadLibrary).also { result -> result.getOrNull()?.let(onLibraryLoaded) }
        }
        val playlists = async {
            captureSource { loadPlaylists(forceServerPlaylistRefresh) }
                .also { result -> result.getOrNull()?.let(onPlaylistsLoaded) }
        }
        val libraryResult = library.await()
        val playlistResult = playlists.await()
        val fallback =
            if (libraryResult.isFailure) {
                captureSource(loadOfflineLibrary)
                    .getOrNull()
                    ?.takeIf { offlineLibrary -> offlineLibrary.songs.isNotEmpty() }
                    ?.also { offlineLibrary ->
                        Timber.d(
                            "曲库加载失败，降级到已下载离线曲库 songs=%d",
                            offlineLibrary.songs.size,
                        )
                    }
            } else {
                null
            }
        MusicLibrarySources(
            library = libraryResult.getOrNull() ?: fallback,
            playlists = playlistResult.getOrNull(),
            libraryError = libraryResult.exceptionOrNull(),
            playlistsError = playlistResult.exceptionOrNull(),
            libraryFallback = fallback != null,
        )
    }
}

private suspend fun <T> captureSource(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        Result.failure(error)
    }

internal fun shouldShowBlockingMusicError(
    errorMessage: String?,
    hasRenderableContent: Boolean,
): Boolean = errorMessage != null && !hasRenderableContent

internal fun MusicModeViewModel.UiState.hasRenderableMusicContent(): Boolean =
    detail != null ||
        albums.isNotEmpty() ||
        artists.isNotEmpty() ||
        songs.isNotEmpty() ||
        playlists.isNotEmpty()

internal fun musicTabAfterInitialSourceLoad(
    currentTab: MusicTab,
    firstLoad: Boolean,
    libraryFailed: Boolean,
    albumCount: Int,
    playlistCount: Int,
): MusicTab =
    if (
        firstLoad &&
            libraryFailed &&
            albumCount == 0 &&
            playlistCount > 0 &&
            currentTab == MusicTab.ALBUMS
    ) {
        MusicTab.PLAYLISTS
    } else {
        currentTab
    }
