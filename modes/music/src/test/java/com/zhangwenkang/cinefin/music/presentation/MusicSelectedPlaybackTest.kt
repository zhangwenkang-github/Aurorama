package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicItemSource
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSelectedPlaybackTest {

    @Test
    fun `first selected song publishes pending before resolver completes`() {
        runBlocking {
            val songs = listOf(song("第一首"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val resolverStarted = CompletableDeferred<Unit>()
            val resolverGate = CompletableDeferred<Unit>()

            val job =
                launchSelectedPlayback(
                    scope = this,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 1,
                    resolve = { selected ->
                        resolverStarted.complete(Unit)
                        resolverGate.await()
                        playerItem(selected)
                    },
                    onFirstResolved = {},
                    onAccepted = {},
                    appendItems = {},
                    onFailure = { _, _ -> },
                )

            assertNotNull("selection playback must start", job)
            resolverStarted.await()
            assertTrue("resolver must still be unresolved", resolverGate.isCompleted.not())
            assertEquals(songs.single().itemId, pending.value?.item?.itemId)
            assertEquals("第一首", pending.value?.item?.name)

            resolverGate.complete(Unit)
            job?.join()
        }
    }

    @Test
    fun `repeat selected playback is rejected while pending`() {
        runBlocking {
            val songs = listOf(song("第一首"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val resolverStarted = CompletableDeferred<Unit>()
            val resolverGate = CompletableDeferred<Unit>()
            var resolverCalls = 0
            val resolve: suspend (MusicSong) -> PlayerItem = { selected ->
                resolverCalls++
                resolverStarted.complete(Unit)
                resolverGate.await()
                playerItem(selected)
            }
            val firstJob =
                launchSelectedPlayback(
                    scope = this,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 1,
                    resolve = resolve,
                    onFirstResolved = {},
                    onAccepted = {},
                    appendItems = {},
                    onFailure = { _, _ -> },
                )
            val repeatedJob =
                launchSelectedPlayback(
                    scope = this,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 2,
                    resolve = resolve,
                    onFirstResolved = {},
                    onAccepted = {},
                    appendItems = {},
                    onFailure = { _, _ -> },
                )

            assertNull("repeat click must be rejected", repeatedJob)
            assertEquals(1L, pending.value?.requestId)
            resolverStarted.await()
            assertEquals(1, resolverCalls)

            resolverGate.complete(Unit)
            firstJob?.join()
        }
    }

    @Test
    fun `success clears pending selection and appends songs in selection order`() {
        runBlocking {
            val songs = listOf(song("第一首"), song("第二首"), song("第三首"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val firstGate = CompletableDeferred<Unit>()
            val secondGate = CompletableDeferred<Unit>()
            val thirdGate = CompletableDeferred<Unit>()
            val secondStarted = CompletableDeferred<Unit>()
            val thirdStarted = CompletableDeferred<Unit>()
            val queued = mutableListOf<PlayerItem>()
            var selectionCleared = false

            val job =
                launchSelectedPlayback(
                    scope = this,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 7,
                    resolve = { selected ->
                        when (selected.name) {
                            "第一首" -> firstGate.await()
                            "第二首" -> {
                                secondStarted.complete(Unit)
                                secondGate.await()
                            }
                            "第三首" -> {
                                thirdStarted.complete(Unit)
                                thirdGate.await()
                            }
                        }
                        playerItem(selected)
                    },
                    onFirstResolved = { queued += it },
                    onAccepted = { selectionCleared = true },
                    appendItems = { queued += it },
                    onFailure = { _, _ -> },
                )

            firstGate.complete(Unit)
            withTimeout(2_000) {
                secondStarted.await()
                thirdStarted.await()
            }
            thirdGate.complete(Unit)
            secondGate.complete(Unit)
            job?.join()

            assertNull(pending.value)
            assertTrue(selectionCleared)
            assertEquals(
                listOf("第一首", "第二首", "第三首"),
                queued.map(PlayerItem::name),
            )
        }
    }

    @Test
    fun `first resolver failure clears pending keeps selection and reports error`() {
        runBlocking {
            val songs = listOf(song("坏文件"), song("第二首"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var selection = songs
            var visibleError: String? = null
            var queueStarted = false

            val job =
                launchSelectedPlayback(
                    scope = scope,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 9,
                    resolve = { error("曲目没有可用的媒体源") },
                    onFirstResolved = { queueStarted = true },
                    onAccepted = { selection = emptyList() },
                    appendItems = {},
                    onFailure = { failed, cause ->
                        visibleError = "播放失败：${failed.name}：${cause.message}"
                    },
                )
            job?.join()
            scope.cancel()

            assertNull(pending.value)
            assertEquals(songs, selection)
            assertEquals("播放失败：坏文件：曲目没有可用的媒体源", visibleError)
            assertTrue(queueStarted.not())
        }
    }

    @Test
    fun `later resolver failure is skipped without interrupting queue order`() {
        runBlocking {
            val songs = listOf(song("第一首"), song("坏文件"), song("第三首"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val queued = mutableListOf<PlayerItem>()

            val job =
                launchSelectedPlayback(
                    scope = scope,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 10,
                    resolve = { selected ->
                        if (selected.name == "坏文件") error("曲目没有可用的媒体源")
                        playerItem(selected)
                    },
                    onFirstResolved = { queued += it },
                    onAccepted = {},
                    appendItems = { queued += it },
                    onFailure = { _, _ -> },
                )
            job?.join()
            scope.cancel()

            assertNull(pending.value)
            assertEquals(listOf("第一首", "第三首"), queued.map(PlayerItem::name))
        }
    }

    private fun song(name: String): MusicSong =
        MusicSong(
            itemId = UUID.randomUUID(),
            name = name,
            albumName = "专辑",
            artist = "歌手",
            indexNumber = 1,
            runtimeTicks = 0L,
            imageUri = null,
            source = MusicItemSource.SERVER,
        )

    private fun playerItem(song: MusicSong): PlayerItem =
        PlayerItem(
            name = song.name,
            itemId = song.itemId,
            mediaSourceId = "source",
            playbackPosition = 0L,
        )
}
