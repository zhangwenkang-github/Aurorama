package com.zhangwenkang.cinefin.core.presentation.downloader

import android.app.DownloadManager
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.utils.Downloader
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderViewModelTest {
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `downloadItem throws - view model reaches visible failure and never queues`() =
        runTest(mainDispatcher.scheduler) {
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { error("sidecar write failed") },
                        onProgress = { 0 to 0 },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }

            viewModel.onAction(DownloaderAction.Download(movie(), storageIndex = 0))
            val state = awaitStatus(viewModel, DownloadManager.STATUS_FAILED)

            assertNotNull(state.errorText)
            assertFalse(state.isDownloading)
            assertEquals(emptyList<DownloaderEvent>(), events)
            eventJob.cancel()
        }

    @Test
    fun `downloadItem returns -1 - failure unlocks legacy action and retry reaches downloader`() =
        runTest(mainDispatcher.scheduler) {
            var attempts = 0
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = {
                            attempts++
                            -1L to UiText.DynamicString("queue rejected")
                        },
                        onProgress = { 0 to 0 },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }

            viewModel.onAction(DownloaderAction.Download(movie(), storageIndex = 0))
            val firstFailure = awaitStatus(viewModel, DownloadManager.STATUS_FAILED)
            assertFalse(firstFailure.isDownloading)

            viewModel.onAction(DownloaderAction.Download(movie(), storageIndex = 0))
            val retryFailure = awaitStatus(viewModel, DownloadManager.STATUS_FAILED)

            assertEquals(2, attempts)
            assertFalse(retryFailure.isDownloading)
            assertEquals(emptyList<DownloaderEvent>(), events)
            eventJob.cancel()
        }

    @Test
    fun `empty source - downloader is not called and view model exposes retryable failure`() =
        runTest(mainDispatcher.scheduler) {
            var calls = 0
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = {
                            calls++
                            42L to null
                        },
                        onProgress = { 30 to 50 },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }

            viewModel.onAction(
                DownloaderAction.Download(movie(sources = emptyList()), storageIndex = 0)
            )
            val state = awaitStatus(viewModel, DownloadManager.STATUS_FAILED)

            assertEquals(0, calls)
            assertNotNull(state.errorText)
            assertFalse(state.isDownloading)
            assertEquals(emptyList<DownloaderEvent>(), events)
            eventJob.cancel()
        }

    @Test
    fun `page rebuild - persisted failure remains observable and legacy action stays enabled`() =
        runTest(mainDispatcher.scheduler) {
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { -1L to null },
                        onProgress = { 0 to 0 },
                    )
                )

            viewModel.onAction(DownloaderAction.Download(movie(), storageIndex = 0))
            awaitStatus(viewModel, DownloadManager.STATUS_FAILED)

            val rebuiltPageReads = List(2) { viewModel.state.value }

            rebuiltPageReads.forEach { state ->
                assertEquals(DownloadManager.STATUS_FAILED, state.status)
                assertNotNull(state.errorText)
                assertFalse(state.isDownloading)
            }
        }

    @Test
    fun `stale update after cancellation - non-active partial never restores poll`() =
        runTest(mainDispatcher.scheduler) {
            listOf("FAILED", "CANCELLED", "SUCCESSFUL", "COMPLETED").forEach { taskStatus ->
                var progressCalls = 0
                val viewModel =
                    DownloaderViewModel(
                        downloader(
                            onDownload = { 42L to null },
                            onProgress = {
                                progressCalls++
                                DownloadManager.STATUS_RUNNING to 50
                            },
                        )
                    )

                viewModel.update(
                    movie(
                        sources =
                            listOf(
                                localSource(
                                    path = "/downloads/movie.download",
                                    downloadId = 42L,
                                    taskStatus = taskStatus,
                                )
                            )
                    )
                )
                mainDispatcher.scheduler.runCurrent()

                val state = viewModel.state.value
                val downloadId = viewModel.downloadId
                viewModel.viewModelScope.cancel()

                assertEquals(DownloaderState(), state)
                assertNull(downloadId)
                assertEquals(0, progressCalls)
            }
        }

    @Test
    fun `active partial update - pending and running restore handle and poll`() =
        runTest(mainDispatcher.scheduler) {
            listOf("PENDING", "RUNNING").forEach { taskStatus ->
                var progressCalls = 0
                val viewModel =
                    DownloaderViewModel(
                        downloader(
                            onDownload = { 42L to null },
                            onProgress = {
                                progressCalls++
                                DownloadManager.STATUS_RUNNING to 37
                            },
                        )
                    )

                viewModel.update(
                    movie(
                        sources =
                            listOf(
                                localSource(
                                    path = "/downloads/movie.download",
                                    downloadId = 42L,
                                    taskStatus = taskStatus,
                                )
                            )
                    )
                )
                mainDispatcher.scheduler.runCurrent()

                val downloadId = viewModel.downloadId
                val state = viewModel.state.value
                viewModel.viewModelScope.cancel()

                assertEquals(42L, downloadId)
                assertEquals(DownloadManager.STATUS_RUNNING, state.status)
                assertEquals(0.37f, state.progress)
                assertEquals(1, progressCalls)
            }
        }

    @Test
    fun `stale update cannot overwrite an accepted new download`() =
        runTest(mainDispatcher.scheduler) {
            var progressCalls = 0
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { 42L to null },
                        onProgress = {
                            progressCalls++
                            DownloadManager.STATUS_RUNNING to 50
                        },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }

            viewModel.onAction(DownloaderAction.Download(movie()))
            awaitStatus(viewModel, DownloadManager.STATUS_RUNNING)
            val progressBeforeUpdate = progressCalls

            viewModel.update(
                movie(
                    sources =
                        listOf(
                            localSource(
                                path = "/downloads/old.download",
                                downloadId = 77L,
                                taskStatus = "FAILED",
                            )
                        )
                )
            )
            mainDispatcher.scheduler.runCurrent()

            val downloadId = viewModel.downloadId
            val state = viewModel.state.value
            val recordedEvents = events.toList()
            eventJob.cancel()
            viewModel.viewModelScope.cancel()

            assertEquals(42L, downloadId)
            assertEquals(DownloadManager.STATUS_RUNNING, state.status)
            assertEquals(progressBeforeUpdate, progressCalls)
            assertEquals(listOf(DownloaderEvent.Queued), recordedEvents)
        }

    @Test
    fun `stale running update after cancellation - old handle stays fenced`() =
        runTest(mainDispatcher.scheduler) {
            var progressCalls = 0
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { 42L to null },
                        onProgress = {
                            progressCalls++
                            DownloadManager.STATUS_RUNNING to 50
                        },
                    )
                )
            val item = movie()

            viewModel.onAction(DownloaderAction.Download(item))
            awaitStatus(viewModel, DownloadManager.STATUS_RUNNING)
            viewModel.onAction(DownloaderAction.CancelDownload(item))
            mainDispatcher.scheduler.runCurrent()
            assertEquals(DownloaderState(), viewModel.state.value)
            val progressCallsBeforeUpdate = progressCalls

            viewModel.update(
                movie(
                    sources =
                        listOf(
                            localSource(
                                path = "/downloads/old.download",
                                downloadId = 42L,
                                taskStatus = "RUNNING",
                            )
                        )
                )
            )
            mainDispatcher.scheduler.runCurrent()

            val state = viewModel.state.value
            val downloadId = viewModel.downloadId
            viewModel.viewModelScope.cancel()

            assertEquals(DownloaderState(), state)
            assertNull(downloadId)
            assertEquals(progressCallsBeforeUpdate, progressCalls)
        }

    @Test
    fun `cancel during pending prepare - late success cannot publish handle queued or poll`() =
        runTest(mainDispatcher.scheduler) {
            val pendingDownloads = mutableListOf<Continuation<Pair<Long, UiText?>>>()
            var progressCalls = 0
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { continuation ->
                            pendingDownloads += continuation
                            COROUTINE_SUSPENDED
                        },
                        onProgress = {
                            progressCalls++
                            DownloadManager.STATUS_RUNNING to 50
                        },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }
            val item = movie()

            viewModel.onAction(DownloaderAction.Download(item))
            awaitStatus(viewModel, DownloadManager.STATUS_PENDING)
            viewModel.onAction(DownloaderAction.CancelDownload(item))
            assertEquals(DownloaderState(), viewModel.state.value)

            pendingDownloads.single().resume(42L to null)
            mainDispatcher.scheduler.runCurrent()

            assertEquals(DownloaderState(), viewModel.state.value)
            assertNull(viewModel.downloadId)
            assertEquals(emptyList<DownloaderEvent>(), events)
            assertEquals(0, progressCalls)
            eventJob.cancel()
        }

    @Test
    fun `cancel during pending prepare - late exception cannot replace idle with failed`() =
        runTest(mainDispatcher.scheduler) {
            val pendingDownloads = mutableListOf<Continuation<Pair<Long, UiText?>>>()
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { continuation ->
                            pendingDownloads += continuation
                            COROUTINE_SUSPENDED
                        },
                        onProgress = { DownloadManager.STATUS_RUNNING to 50 },
                    )
                )
            val item = movie()

            viewModel.onAction(DownloaderAction.Download(item))
            awaitStatus(viewModel, DownloadManager.STATUS_PENDING)
            viewModel.onAction(DownloaderAction.CancelDownload(item))
            assertEquals(DownloaderState(), viewModel.state.value)

            pendingDownloads.single().resumeWithException(IllegalStateException("late failure"))
            mainDispatcher.scheduler.runCurrent()

            assertEquals(DownloaderState(), viewModel.state.value)
            assertNull(viewModel.downloadId)
        }

    @Test
    fun `view model scope cancellation - cancellation is not published as product failure`() =
        runTest(mainDispatcher.scheduler) {
            val pendingDownloads = mutableListOf<Continuation<Pair<Long, UiText?>>>()
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { continuation ->
                            pendingDownloads += continuation
                            COROUTINE_SUSPENDED
                        },
                        onProgress = { DownloadManager.STATUS_RUNNING to 50 },
                    )
                )

            viewModel.onAction(DownloaderAction.Download(movie()))
            awaitStatus(viewModel, DownloadManager.STATUS_PENDING)

            viewModel.viewModelScope.cancel()
            pendingDownloads.single().resumeWithException(CancellationException("scope cancelled"))
            mainDispatcher.scheduler.runCurrent()

            assertEquals(DownloaderState(), viewModel.state.value)
            assertNull(viewModel.downloadId)
            assertNull(viewModel.state.value.errorText)
        }

    @Test
    fun `cancel then retry - old late success cannot overwrite new generation`() =
        runTest(mainDispatcher.scheduler) {
            val pendingDownloads = mutableListOf<Continuation<Pair<Long, UiText?>>>()
            var progressCalls = 0
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { continuation ->
                            pendingDownloads += continuation
                            COROUTINE_SUSPENDED
                        },
                        onProgress = {
                            progressCalls++
                            DownloadManager.STATUS_RUNNING to 50
                        },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }
            val item = movie()

            viewModel.onAction(DownloaderAction.Download(item))
            awaitStatus(viewModel, DownloadManager.STATUS_PENDING)
            viewModel.onAction(DownloaderAction.CancelDownload(item))
            viewModel.onAction(DownloaderAction.Download(item))
            assertEquals(2, pendingDownloads.size)
            assertEquals(DownloadManager.STATUS_PENDING, viewModel.state.value.status)

            pendingDownloads[0].resume(41L to null)
            mainDispatcher.scheduler.runCurrent()
            assertEquals(DownloadManager.STATUS_PENDING, viewModel.state.value.status)
            assertNull(viewModel.downloadId)
            assertEquals(emptyList<DownloaderEvent>(), events)
            assertEquals(0, progressCalls)

            pendingDownloads[1].resume(42L to null)
            awaitStatus(viewModel, DownloadManager.STATUS_RUNNING)

            assertEquals(42L, viewModel.downloadId)
            assertEquals(listOf(DownloaderEvent.Queued), events)
            assertEquals(1, progressCalls)
            eventJob.cancel()
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `accepted download control - handle queued and poll are observable`() =
        runTest(mainDispatcher.scheduler) {
            var progressCalls = 0
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { 42L to null },
                        onProgress = {
                            progressCalls++
                            DownloadManager.STATUS_RUNNING to 50
                        },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }

            viewModel.onAction(DownloaderAction.Download(movie()))
            val state = awaitStatus(viewModel, DownloadManager.STATUS_RUNNING)

            assertEquals(42L, viewModel.downloadId)
            assertEquals(0.5f, state.progress)
            assertEquals(listOf(DownloaderEvent.Queued), events)
            assertEquals(1, progressCalls)
            eventJob.cancel()
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `accepted download successful - completion is emitted once and polling stops`() =
        runTest(mainDispatcher.scheduler) {
            var progressCalls = 0
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { 42L to null },
                        onProgress = {
                            progressCalls++
                            DownloadManager.STATUS_SUCCESSFUL to 100
                        },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }

            viewModel.onAction(DownloaderAction.Download(movie()))
            val state = awaitStatus(viewModel, DownloadManager.STATUS_SUCCESSFUL)
            mainDispatcher.scheduler.advanceTimeBy(5_000L)
            mainDispatcher.scheduler.runCurrent()

            val recordedEvents = events.toList()
            eventJob.cancel()
            viewModel.viewModelScope.cancel()

            assertEquals(42L, viewModel.downloadId)
            assertEquals(DownloadManager.STATUS_SUCCESSFUL, state.status)
            assertEquals(1.0f, state.progress)
            assertEquals(1, progressCalls)
            assertEquals(
                listOf(DownloaderEvent.Queued, DownloaderEvent.Successful),
                recordedEvents,
            )
        }

    @Test
    fun `cancel accepted download - real handle is canceled and polling stops`() =
        runTest(mainDispatcher.scheduler) {
            var progressCalls = 0
            val canceledHandles = mutableListOf<Long>()
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = { 42L to null },
                        onProgress = {
                            progressCalls++
                            DownloadManager.STATUS_RUNNING to 50
                        },
                        onCancel = { canceledHandles += it },
                    )
                )
            val item = movie()

            viewModel.onAction(DownloaderAction.Download(item))
            awaitStatus(viewModel, DownloadManager.STATUS_RUNNING)
            viewModel.onAction(DownloaderAction.CancelDownload(item))
            mainDispatcher.scheduler.advanceTimeBy(2_000L)
            mainDispatcher.scheduler.runCurrent()

            assertEquals(DownloaderState(), viewModel.state.value)
            assertNull(viewModel.downloadId)
            assertEquals(listOf(42L), canceledHandles)
            assertEquals(1, progressCalls)
            viewModel.viewModelScope.cancel()
        }

    @Test
    fun `cancel then retry after accepted handle - retry waits for old cancellation`() =
        runTest(mainDispatcher.scheduler) {
            val pendingCancellations = mutableListOf<Continuation<Unit>>()
            var activeGeneration: Int? = null
            var nextGeneration = 0
            var cancellationCompleted = false
            var retryStartedBeforeCancellationCompleted = false
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = {
                            if (nextGeneration > 0 && !cancellationCompleted) {
                                retryStartedBeforeCancellationCompleted = true
                            }
                            nextGeneration++
                            activeGeneration = nextGeneration
                            42L to null
                        },
                        onProgress = {
                            if (activeGeneration == null) {
                                DownloadManager.STATUS_FAILED to 75
                            } else {
                                DownloadManager.STATUS_RUNNING to 50
                            }
                        },
                        onCancelSuspending = { _, continuation ->
                            val wrapped =
                                object : Continuation<Unit> {
                                    override val context = continuation.context

                                    override fun resumeWith(result: Result<Unit>) {
                                        cancellationCompleted = true
                                        activeGeneration = null
                                        continuation.resumeWith(result)
                                    }
                                }
                            pendingCancellations += wrapped
                            COROUTINE_SUSPENDED
                        },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }
            val item = movie()

            viewModel.onAction(DownloaderAction.Download(item))
            awaitStatus(viewModel, DownloadManager.STATUS_RUNNING)
            viewModel.onAction(DownloaderAction.CancelDownload(item))
            viewModel.onAction(DownloaderAction.Download(item))
            mainDispatcher.scheduler.runCurrent()

            assertEquals(1, pendingCancellations.size)
            pendingCancellations.single().resume(Unit)
            mainDispatcher.scheduler.advanceTimeBy(1_000L)
            mainDispatcher.scheduler.runCurrent()

            val downloadId = viewModel.downloadId
            val state = viewModel.state.value
            val recordedEvents = events.toList()
            eventJob.cancel()
            viewModel.viewModelScope.cancel()

            assertFalse(retryStartedBeforeCancellationCompleted)
            assertEquals(42L, downloadId)
            assertEquals(DownloadManager.STATUS_RUNNING, state.status)
            assertNotNull(activeGeneration)
            assertEquals(
                listOf(DownloaderEvent.Queued, DownloaderEvent.Queued),
                recordedEvents,
            )
        }

    @Test
    fun `cancel failure - retry waits for owned cancellation and publishes no failure`() =
        runTest(mainDispatcher.scheduler) {
            val pendingCancellations = mutableListOf<Continuation<Unit>>()
            var activeGeneration: Int? = null
            var nextGeneration = 0
            var cancellationCompleted = false
            var retryStartedBeforeCancellationCompleted = false
            val viewModel =
                DownloaderViewModel(
                    downloader(
                        onDownload = {
                            if (nextGeneration > 0 && !cancellationCompleted) {
                                retryStartedBeforeCancellationCompleted = true
                            }
                            nextGeneration++
                            activeGeneration = nextGeneration
                            42L to null
                        },
                        onProgress = {
                            if (activeGeneration == null) {
                                DownloadManager.STATUS_FAILED to 75
                            } else {
                                DownloadManager.STATUS_RUNNING to 50
                            }
                        },
                        onCancelSuspending = { _, continuation ->
                            val wrapped =
                                object : Continuation<Unit> {
                                    override val context = continuation.context

                                    override fun resumeWith(result: Result<Unit>) {
                                        cancellationCompleted = true
                                        activeGeneration = null
                                        continuation.resumeWithException(
                                            IllegalStateException("late cancel failure")
                                        )
                                    }
                                }
                            pendingCancellations += wrapped
                            COROUTINE_SUSPENDED
                        },
                    )
                )
            val events = mutableListOf<DownloaderEvent>()
            val eventJob = launch(mainDispatcher) { viewModel.events.collect { events += it } }
            val item = movie()

            viewModel.onAction(DownloaderAction.Download(item))
            awaitStatus(viewModel, DownloadManager.STATUS_RUNNING)
            viewModel.onAction(DownloaderAction.CancelDownload(item))
            viewModel.onAction(DownloaderAction.Download(item))
            mainDispatcher.scheduler.runCurrent()

            assertEquals(1, pendingCancellations.size)
            pendingCancellations.single().resume(Unit)
            mainDispatcher.scheduler.advanceTimeBy(1_000L)
            mainDispatcher.scheduler.runCurrent()

            val downloadId = viewModel.downloadId
            val state = viewModel.state.value
            val recordedEvents = events.toList()
            eventJob.cancel()
            viewModel.viewModelScope.cancel()

            assertFalse(retryStartedBeforeCancellationCompleted)
            assertEquals(42L, downloadId)
            assertEquals(DownloadManager.STATUS_RUNNING, state.status)
            assertNull(state.errorText)
            assertNotNull(activeGeneration)
            assertEquals(
                listOf(DownloaderEvent.Queued, DownloaderEvent.Queued),
                recordedEvents,
            )
        }

    @Test
    fun `accepted download control - pending and running remain busy`() {
        assertEquals(
            true,
            DownloaderState(status = DownloadManager.STATUS_PENDING, progress = 0f).isDownloading,
        )
        assertEquals(
            true,
            DownloaderState(status = DownloadManager.STATUS_RUNNING, progress = 0.25f)
                .isDownloading,
        )
    }

    private suspend fun awaitStatus(
        viewModel: DownloaderViewModel,
        status: Int,
    ): DownloaderState = withTimeout(1_000) { viewModel.state.first { it.status == status } }

    private fun movie(sources: List<FindroidSource> = listOf(source())): FindroidMovie =
        FindroidMovie(
            id = UUID.randomUUID(),
            name = "下载动作回归电影",
            originalTitle = null,
            overview = "",
            sources = sources,
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = true,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
        )

    private fun source(): FindroidSource =
        FindroidSource(
            id = "source-a",
            name = "原始视频",
            type = FindroidSourceType.REMOTE,
            path = "https://example.test/Items/movie/Download",
            size = 1L,
            mediaStreams = emptyList(),
        )

    private fun localSource(
        path: String,
        downloadId: Long,
        taskStatus: String,
    ): FindroidSource =
        FindroidSource(
            id = "source-local",
            name = "本地下载",
            type = FindroidSourceType.LOCAL,
            path = path,
            size = 1L,
            mediaStreams = emptyList(),
            downloadId = downloadId,
            downloadTaskStatus = taskStatus,
        )

    @Suppress("UNCHECKED_CAST")
    private fun downloader(
        onDownload: (Continuation<Pair<Long, UiText?>>) -> Any?,
        onProgress: () -> Pair<Int, Int>,
        onCancel: (Long) -> Unit = {},
        onCancelSuspending: ((Long, Continuation<Unit>) -> Any?)? = null,
    ): Downloader =
        proxy(Downloader::class.java) { method, args ->
            when (method.name) {
                "downloadItem" ->
                    if ((args.getOrNull(1) as? String).isNullOrEmpty()) {
                        42L to null
                    } else {
                        onDownload(args.last() as Continuation<Pair<Long, UiText?>>)
                    }
                "getProgress" -> onProgress()
                "cancelDownload" -> {
                    val handle = (args.getOrNull(1) as? Number)?.toLong()
                    if (handle != null && onCancelSuspending != null) {
                        onCancelSuspending.invoke(
                            handle,
                            args.last() as Continuation<Unit>,
                        )
                    } else {
                        handle?.let(onCancel)
                        Unit
                    }
                }
                else -> defaultValue(method.returnType)
            }
        }

    private inline fun <reified T> proxy(
        type: Class<T>,
        crossinline result: (Method, Array<out Any?>) -> Any?,
    ): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { instance, method, args ->
            when (method.name) {
                "equals" -> instance === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(instance)
                "toString" -> "Test${type.simpleName}"
                else -> result(method, args.orEmpty())
            }
        } as T

    private fun defaultValue(type: Class<*>): Any? =
        when (type) {
            Boolean::class.javaPrimitiveType -> false
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
}
