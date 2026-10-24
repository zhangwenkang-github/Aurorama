package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicLibrary
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

internal data class MusicLibrarySources(
    val library: MusicLibrary?,
    val playlists: List<MusicPlaylist>?,
    val libraryError: Throwable?,
    val playlistsError: Throwable?,
)

/**
 * 曲库与服务器歌单摘要各自独立加载。任一来源先完成就先回传，避免曲库失败阻止磁盘歌单摘要上屏。
 *
 * 离线模式只调用本地曲库读取；不接触任何服务器来源。
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
        MusicLibrarySources(
            library = libraryResult.getOrNull(),
            playlists = playlistResult.getOrNull(),
            libraryError = libraryResult.exceptionOrNull(),
            playlistsError = playlistResult.exceptionOrNull(),
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
