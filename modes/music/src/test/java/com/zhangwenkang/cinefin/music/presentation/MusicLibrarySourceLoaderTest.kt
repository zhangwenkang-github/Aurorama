package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicLibrary
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLibrarySourceLoaderTest {

    private val playlist =
        MusicPlaylist(
            id = UUID.fromString("11111111-2222-3333-4444-555555555555"),
            name = "测试播放列表",
            imageUri = null,
            songCount = 7,
        )

    private fun emptyLibrary() = MusicLibrary(emptyList(), emptyList(), emptyList())

    @Test
    fun `library failure still publishes an existing playlist summary`() = runBlocking {
        val publishedPlaylists = mutableListOf<List<MusicPlaylist>>()
        val offlineError = IOException("HTTP host unreachable")

        val result =
            loadMusicLibrarySources(
                offline = false,
                forceServerPlaylistRefresh = false,
                loadLibrary = { throw offlineError },
                loadOfflineLibrary = { error("offline library must not be used") },
                loadPlaylists = { listOf(playlist) },
                onLibraryLoaded = {},
                onPlaylistsLoaded = { publishedPlaylists += it },
            )

        assertEquals(listOf(listOf(playlist)), publishedPlaylists)
        assertEquals(listOf(playlist), result.playlists)
        assertEquals(offlineError, result.libraryError)
        assertNull(result.playlistsError)
        assertFalse(
            shouldShowBlockingMusicError(
                errorMessage = offlineError.message,
                hasRenderableContent = listOf(playlist).isNotEmpty(),
            )
        )
    }

    @Test
    fun `playlist summary publishes before a slow library request finishes`() = runBlocking {
        val releaseLibrary = CompletableDeferred<Unit>()
        val playlistPublished = CompletableDeferred<Unit>()

        val load =
            async(start = CoroutineStart.UNDISPATCHED) {
                loadMusicLibrarySources(
                    offline = false,
                    forceServerPlaylistRefresh = false,
                    loadLibrary = {
                        releaseLibrary.await()
                        emptyLibrary()
                    },
                    loadOfflineLibrary = { error("offline library must not be used") },
                    loadPlaylists = { listOf(playlist) },
                    onLibraryLoaded = {},
                    onPlaylistsLoaded = { playlistPublished.complete(Unit) },
                )
            }

        withTimeout(1_000) { playlistPublished.await() }
        assertFalse(load.isCompleted)

        releaseLibrary.complete(Unit)
        assertEquals(listOf(playlist), load.await().playlists)
    }

    @Test
    fun `both sources publish normally when both requests succeed`() = runBlocking {
        val library = emptyLibrary()
        val publishedLibraries = mutableListOf<MusicLibrary>()
        val publishedPlaylists = mutableListOf<List<MusicPlaylist>>()

        val result =
            loadMusicLibrarySources(
                offline = false,
                forceServerPlaylistRefresh = false,
                loadLibrary = { library },
                loadOfflineLibrary = { error("offline library must not be used") },
                loadPlaylists = { listOf(playlist) },
                onLibraryLoaded = { publishedLibraries += it },
                onPlaylistsLoaded = { publishedPlaylists += it },
            )

        assertEquals(listOf(library), publishedLibraries)
        assertEquals(listOf(listOf(playlist)), publishedPlaylists)
        assertEquals(library, result.library)
        assertEquals(listOf(playlist), result.playlists)
        assertNull(result.libraryError)
        assertNull(result.playlistsError)
    }

    @Test
    fun `playlist failure does not discard a successful library`() = runBlocking {
        val library = emptyLibrary()
        val publishedLibraries = mutableListOf<MusicLibrary>()
        val playlistError = IOException("playlist request failed")

        val result =
            loadMusicLibrarySources(
                offline = false,
                forceServerPlaylistRefresh = true,
                loadLibrary = { library },
                loadOfflineLibrary = { error("offline library must not be used") },
                loadPlaylists = { throw playlistError },
                onLibraryLoaded = { publishedLibraries += it },
                onPlaylistsLoaded = { error("failed playlists must not be published") },
            )

        assertEquals(listOf(library), publishedLibraries)
        assertEquals(library, result.library)
        assertNull(result.playlists)
        assertNull(result.libraryError)
        assertEquals(playlistError, result.playlistsError)
    }

    @Test
    fun `initial load is cache first and explicit refresh forces playlists`() = runBlocking {
        val requestedRefreshFlags = mutableListOf<Boolean>()

        loadMusicLibrarySources(
            offline = false,
            forceServerPlaylistRefresh = false,
            loadLibrary = { emptyLibrary() },
            loadOfflineLibrary = { error("offline library must not be used") },
            loadPlaylists = { forceRefresh ->
                requestedRefreshFlags += forceRefresh
                listOf(playlist)
            },
            onLibraryLoaded = {},
            onPlaylistsLoaded = {},
        )
        loadMusicLibrarySources(
            offline = false,
            forceServerPlaylistRefresh = true,
            loadLibrary = { emptyLibrary() },
            loadOfflineLibrary = { error("offline library must not be used") },
            loadPlaylists = { forceRefresh ->
                requestedRefreshFlags += forceRefresh
                listOf(playlist)
            },
            onLibraryLoaded = {},
            onPlaylistsLoaded = {},
        )

        assertEquals(listOf(false, true), requestedRefreshFlags)
    }

    @Test
    fun `offline mode only reads the local library`() = runBlocking {
        val localLibrary = emptyLibrary()
        var serverLibraryCalls = 0
        var serverPlaylistCalls = 0

        val result =
            loadMusicLibrarySources(
                offline = true,
                forceServerPlaylistRefresh = true,
                loadLibrary = {
                    serverLibraryCalls += 1
                    error("server library must not be requested")
                },
                loadOfflineLibrary = { localLibrary },
                loadPlaylists = {
                    serverPlaylistCalls += 1
                    error("server playlists must not be requested")
                },
                onLibraryLoaded = {},
                onPlaylistsLoaded = {},
            )

        assertEquals(0, serverLibraryCalls)
        assertEquals(0, serverPlaylistCalls)
        assertEquals(localLibrary, result.library)
        assertEquals(emptyList<MusicPlaylist>(), result.playlists)
    }

    @Test
    fun `load errors block the page only when no content is available`() {
        assertTrue(shouldShowBlockingMusicError("HTTP host unreachable", false))
        assertFalse(shouldShowBlockingMusicError("HTTP host unreachable", true))
        assertFalse(shouldShowBlockingMusicError(null, false))
    }

    @Test
    fun `initial library failure opens available playlist summaries`() {
        assertEquals(
            MusicTab.PLAYLISTS,
            musicTabAfterInitialSourceLoad(
                currentTab = MusicTab.ALBUMS,
                firstLoad = true,
                libraryFailed = true,
                albumCount = 0,
                playlistCount = 1,
            ),
        )
        assertEquals(
            MusicTab.ALBUMS,
            musicTabAfterInitialSourceLoad(
                currentTab = MusicTab.ALBUMS,
                firstLoad = false,
                libraryFailed = true,
                albumCount = 0,
                playlistCount = 1,
            ),
        )
    }
}
