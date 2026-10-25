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
                    onFailure = {},
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
                    onFailure = {},
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
                    onFailure = {},
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
                    onFailure = {},
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
            var failure: SelectedPlaybackFailure? = null
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
                    onFailure = { rejected ->
                        failure = rejected
                        visibleError = "播放失败：${rejected.triggerSong.name}：${rejected.cause.message}"
                    },
                )
            job?.join()
            scope.cancel()

            assertNull(pending.value)
            assertEquals(songs, selection)
            assertEquals("播放失败：坏文件：曲目没有可用的媒体源", visibleError)
            assertEquals(SelectedPlaybackFailureStage.FIRST_RESOLUTION, failure?.stage)
            assertEquals(songs, failure?.unacceptedSongs)
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
            var failure: SelectedPlaybackFailure? = null

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
                    onFailure = { failure = it },
                    onPartialFailure = { failure = it },
                )
            job?.join()
            scope.cancel()

            assertNull(pending.value)
            assertEquals(listOf("第一首", "第三首"), queued.map(PlayerItem::name))
            assertEquals(SelectedPlaybackFailureStage.SUBSEQUENT_ADDITION, failure?.stage)
            assertEquals(listOf("坏文件"), failure?.unacceptedSongs?.map(MusicSong::name))
        }
    }

    @Test
    fun `queue acceptance failure clears pending keeps selection and reports error`() {
        runBlocking {
            val songs = listOf(song("第一首"), song("第二首"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var selection = songs
            var visibleError: String? = null

            val job =
                launchSelectedPlayback(
                    scope = scope,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 11,
                    resolve = { playerItem(it) },
                    onFirstResolved = { error("播放器队列不可用") },
                    onAccepted = { selection = emptyList() },
                    appendItems = {},
                    onFailure = { failure ->
                        visibleError = "播放失败：${failure.triggerSong.name}：${failure.cause.message}"
                    },
                )
            job?.join()
            scope.cancel()

            assertNull(pending.value)
            assertEquals(songs, selection)
            assertEquals("播放失败：第一首：播放器队列不可用", visibleError)
        }
    }

    @Test
    fun `queue append failure reports visible error without leaving pending`() {
        runBlocking {
            val songs = listOf(song("第一首"), song("第二首"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var failure: SelectedPlaybackFailure? = null
            val queued = mutableListOf<PlayerItem>()

            val job =
                launchSelectedPlayback(
                    scope = scope,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 12,
                    resolve = { playerItem(it) },
                    onFirstResolved = { queued += it },
                    onAccepted = {},
                    appendItems = { error("队列追加失败") },
                    onFailure = { failure = it },
                    onPartialFailure = { failure = it },
                )
            job?.join()
            scope.cancel()

            assertNull(pending.value)
            assertEquals(listOf("第一首"), queued.map(PlayerItem::name))
            assertEquals(SelectedPlaybackFailureStage.SUBSEQUENT_ADDITION, failure?.stage)
            assertEquals("第二首", failure?.triggerSong?.name)
            assertEquals(
                songs.drop(1).map(MusicSong::name),
                failure?.unacceptedSongs?.map(MusicSong::name),
            )
            assertEquals("部分歌曲未能加入队列", selectedPlaybackFailureUi(requireNotNull(failure)).title)
        }
    }

    @Test
    fun `stale request failure cannot clear or report over newer pending`() {
        runBlocking {
            val songs = listOf(song("旧请求"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val resolverGate = CompletableDeferred<Unit>()
            val resolverStarted = CompletableDeferred<Unit>()
            var visibleError: String? = null

            val job =
                launchSelectedPlayback(
                    scope = this,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 20,
                    resolve = {
                        resolverStarted.complete(Unit)
                        resolverGate.await()
                        error("旧请求失败")
                    },
                    onFirstResolved = {},
                    onAccepted = {},
                    appendItems = {},
                    onFailure = { failure ->
                        visibleError = "${failure.triggerSong.name}：${failure.cause.message}"
                    },
                )
            resolverStarted.await()
            val newer = PendingPlaybackUi(requestId = 21, item = song("新请求").toPendingPlayerItem())
            pending.value = newer

            resolverGate.complete(Unit)
            job?.join()

            assertEquals(newer, pending.value)
            assertNull(visibleError)
        }
    }

    @Test
    fun `stale request success cannot start queue or clear newer pending`() {
        runBlocking {
            val songs = listOf(song("旧请求"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val resolverGate = CompletableDeferred<Unit>()
            val resolverStarted = CompletableDeferred<Unit>()
            var queueStarted = false
            var accepted = false

            val job =
                launchSelectedPlayback(
                    scope = this,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 30,
                    resolve = {
                        resolverStarted.complete(Unit)
                        resolverGate.await()
                        playerItem(it)
                    },
                    onFirstResolved = { queueStarted = true },
                    onAccepted = { accepted = true },
                    appendItems = {},
                    onFailure = {},
                )
            resolverStarted.await()
            val newer = PendingPlaybackUi(requestId = 31, item = song("新请求").toPendingPlayerItem())
            pending.value = newer

            resolverGate.complete(Unit)
            job?.join()

            assertEquals(newer, pending.value)
            assertTrue(queueStarted.not())
            assertTrue(accepted.not())
        }
    }

    @Test
    fun `subsequent append failure keeps accepted prefix and reports remaining songs`() {
        runBlocking {
            val songs =
                listOf(
                    song("第一首"),
                    song("第二首"),
                    song("第三首"),
                    song("第四首"),
                    song("第五首"),
                )
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var failure: SelectedPlaybackFailure? = null

            val job =
                launchSelectedPlayback(
                    scope = scope,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 41,
                    resolve = { playerItem(it) },
                    onFirstResolved = {},
                    onAccepted = {},
                    appendItems = { error("队列追加失败") },
                    onFailure = { failure = it },
                    onPartialFailure = { failure = it },
                )
            job?.join()
            scope.cancel()

            val partial = requireNotNull(failure)
            assertEquals(SelectedPlaybackFailureStage.SUBSEQUENT_ADDITION, partial.stage)
            assertEquals("第二首", partial.triggerSong.name)
            assertEquals(listOf("第一首"), partial.acceptedSongs.map(MusicSong::name))
            assertEquals(
                listOf("第二首", "第三首", "第四首", "第五首"),
                partial.unacceptedSongs.map(MusicSong::name),
            )
            assertEquals("部分歌曲未能加入队列", selectedPlaybackFailureUi(partial).title)
        }
    }

    @Test
    fun `queue acceptance failure is distinct from first and subsequent failures`() {
        runBlocking {
            val songs = listOf(song("第一首"), song("第二首"))
            val pending = MutableStateFlow<PendingPlaybackUi?>(null)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            var failure: SelectedPlaybackFailure? = null

            val job =
                launchSelectedPlayback(
                    scope = scope,
                    songs = songs,
                    pendingPlayback = pending,
                    requestId = 42,
                    resolve = { playerItem(it) },
                    onFirstResolved = { error("播放器队列不可用") },
                    onAccepted = {},
                    appendItems = {},
                    onFailure = { failure = it },
                    onPartialFailure = { failure = it },
                )
            job?.join()
            scope.cancel()

            val rejected = requireNotNull(failure)
            assertEquals(SelectedPlaybackFailureStage.QUEUE_ACCEPTANCE, rejected.stage)
            assertEquals("第一首", rejected.triggerSong.name)
            assertTrue(rejected.acceptedSongs.isEmpty())
            assertEquals(
                listOf("第一首", "第二首"),
                rejected.unacceptedSongs.map(MusicSong::name),
            )
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
