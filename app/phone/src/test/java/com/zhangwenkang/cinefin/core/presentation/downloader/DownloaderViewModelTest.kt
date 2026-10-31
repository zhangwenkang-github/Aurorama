package com.zhangwenkang.cinefin.core.presentation.downloader

import android.app.DownloadManager
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.utils.Downloader
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    private fun downloader(
        onDownload: () -> Pair<Long, UiText?>,
        onProgress: () -> Pair<Int, Int>,
    ): Downloader =
        proxy(Downloader::class.java) { method, args ->
            when (method.name) {
                "downloadItem" ->
                    if ((args.getOrNull(1) as? String).isNullOrEmpty()) {
                        42L to null
                    } else {
                        onDownload()
                    }
                "getProgress" -> onProgress()
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
