package com.zhangwenkang.cinefin.film.presentation.downloads

import android.content.Context
import android.content.ContextWrapper
import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.LocalBookFile
import com.zhangwenkang.cinefin.repository.MetadataNamespace
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.ImageCacheStore
import com.zhangwenkang.cinefin.utils.OfflineMediaEntry
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import java.io.DataOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsFailureSyncTest {

    @Test
    fun `queue event publishes failed immediately and stale remote cannot restore running`() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.awaitRemoteStarted()
            fixture.waitUntil("initial running task was not published") {
                fixture.viewModel.state.value.activeTasks.singleOrNull()?.status ==
                    DownloadTaskStatus.RUNNING
            }

            fixture.tasks.value = listOf(failedVideoTask(generation = 2, updatedAt = 200))
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))

            fixture.waitUntil(
                message = "queue event did not publish failed before the 1.5s polling fallback",
                timeoutMs = 750,
            ) {
                val state = fixture.viewModel.state.value
                state.activeTasks.isEmpty() &&
                    state.failedTasks.singleOrNull()?.status == DownloadTaskStatus.FAILED
            }

            fixture.releaseRemote()
            fixture.awaitRemoteIdle()

            val state = fixture.viewModel.state.value
            assertTrue("stale remote restored the running projection", state.activeTasks.isEmpty())
            assertEquals(
                listOf(DownloadTaskStatus.FAILED),
                state.failedTasks.map(DownloadTask::status),
            )
        }
    }

    @Test
    fun `burst book and queue events use one bounded refresh and one trailing refresh`() {
        Fixture().use { fixture ->
            fixture.disablePolling()
            fixture.allowRemoteImmediately()
            fixture.start()
            fixture.waitUntil("initial running task was not published") {
                fixture.viewModel.state.value.activeTasks.singleOrNull()?.status ==
                    DownloadTaskStatus.RUNNING
            }
            fixture.awaitRemoteIdle()

            val baselineRefreshCount = fixture.refreshCount.get()
            val baselineActiveChildren = fixture.activeSessionChildCount()
            fixture.resetExternalRefreshIngress()
            fixture.blockNextLocalRefresh()

            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))
            fixture.awaitLocalRefreshBlocked()

            repeat(12) { progress ->
                fixture.books.value =
                    listOf(
                        bookTask(
                            itemId = BOOK_ID,
                            status = BookDownloadStatus.RUNNING,
                            generation = 1,
                            updatedAt = 100,
                            downloadedBytes = (progress + 1) * 10L,
                        )
                    )
                assertTrue(
                    "queue event burst was not accepted",
                    fixture.queueChanges.tryEmit(Unit),
                )
            }
            fixture.waitUntil("external refresh ingress did not drain") {
                fixture.externalRefreshIngressCount() >= 13
            }

            fixture.releaseLocalRefresh()
            fixture.waitUntil("trailing refresh did not run") {
                fixture.refreshCount.get() - baselineRefreshCount >= 2
            }
            fixture.waitUntil("refresh work did not become quiescent") {
                fixture.activeSessionChildCount() == baselineActiveChildren
            }

            assertEquals(
                "a burst must run once immediately and only one trailing refresh",
                2,
                fixture.refreshCount.get() - baselineRefreshCount,
            )
        }
    }

    @Test
    fun `changed completion and progress projection cannot be overwritten by blocked remote`() {
        Fixture().use { fixture ->
            fixture.books.value =
                listOf(
                    bookTask(
                        itemId = BOOK_ID,
                        status = BookDownloadStatus.RUNNING,
                        generation = 1,
                        updatedAt = 100,
                        downloadedBytes = 25,
                    )
                )
            fixture.start()
            fixture.awaitRemoteStarted()
            fixture.waitUntil("initial book progress was not published") {
                fixture.viewModel.state.value.activeContainers
                    .flatMap { it.allEntries() }
                    .any { it.itemId == BOOK_ID && it.downloadedBytes == 25L }
            }

            fixture.tasks.value =
                listOf(
                    videoTask(
                        status = DownloadTaskStatus.RUNNING,
                        generation = 1,
                        updatedAt = 100,
                        downloadedBytes = 50,
                    )
                )
            fixture.books.value =
                listOf(
                    bookTask(
                        itemId = BOOK_ID,
                        status = BookDownloadStatus.RUNNING,
                        generation = 1,
                        updatedAt = 100,
                        downloadedBytes = 50,
                    )
                )
            fixture.bookFiles.set(
                listOf(LocalBookFile(itemId = BOOK_ID, sizeBytes = 42, title = "Fresh book"))
            )
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))

            fixture.waitUntil(
                "new completion and progress projection was not published before remote finished",
                failureDetail = {
                    Triple(
                        fixture.viewModel.state.value,
                        fixture.externalRefreshIngressCount(),
                        fixture.bookFiles.get(),
                    )
                },
            ) {
                val state = fixture.viewModel.state.value
                state.activeTasks.singleOrNull()?.downloadedBytes == 50L &&
                    state.activeContainers
                        .flatMap { it.allEntries() }
                        .any { it.itemId == BOOK_ID && it.downloadedBytes == 50L } &&
                    state.bookStorageBytes == 42L
            }

            fixture.releaseRemote()
            fixture.awaitRemoteIdle()

            val state = fixture.viewModel.state.value
            assertEquals(
                "stale remote overwrote newer task progress",
                50L,
                state.activeTasks.singleOrNull()?.downloadedBytes,
            )
            assertTrue(
                "stale remote overwrote newer book progress",
                state.activeContainers
                    .flatMap { it.allEntries() }
                    .any { it.itemId == BOOK_ID && it.downloadedBytes == 50L },
            )
            assertTrue(
                "stale remote restored an old completed-book projection",
                state.completedContainers.flatMap { it.allEntries() }.any { it.itemId == BOOK_ID },
            )
        }
    }

    @Test
    fun `offline allow change cannot be overwritten by blocked remote`() {
        Fixture().use { fixture ->
            fixture.offlineEntries.set(
                listOf(
                    OfflineMediaEntry(
                        itemId = VIDEO_ID,
                        name = "Video",
                        kind = OfflineMediaEntryKind.VIDEO,
                        allowOffline = true,
                    )
                )
            )
            fixture.start()
            fixture.awaitRemoteStarted()
            fixture.waitUntil("initial offline allowance was not published") {
                fixture.viewModel.state.value.activeContainers
                    .flatMap { it.allEntries() }
                    .singleOrNull { it.itemId == VIDEO_ID }
                    ?.allowOffline == true
            }
            val initialToken = fixture.snapshotToken()

            fixture.offlineEntries.set(
                listOf(
                    OfflineMediaEntry(
                        itemId = VIDEO_ID,
                        name = "Video",
                        kind = OfflineMediaEntryKind.VIDEO,
                        allowOffline = false,
                    )
                )
            )
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("new offline allowance was not published") {
                fixture.viewModel.state.value.activeContainers
                    .flatMap { it.allEntries() }
                    .singleOrNull { it.itemId == VIDEO_ID }
                    ?.allowOffline == false
            }

            fixture.releaseRemote()
            fixture.awaitRemoteIdle()

            val state = fixture.viewModel.state.value
            val latestAllowOffline =
                state.activeContainers
                    .flatMap { it.allEntries() }
                    .singleOrNull { it.itemId == VIDEO_ID }
                    ?.allowOffline
            val latestToken = fixture.snapshotToken()
            assertTrue(
                "offline-only change was stale-published or omitted from the snapshot token: " +
                    "allowOffline=$latestAllowOffline initialToken=$initialToken latestToken=$latestToken",
                latestAllowOffline == false && latestToken != initialToken,
            )
        }
    }

    @Test
    fun `episode hierarchy change cannot be overwritten by blocked remote`() {
        Fixture().use { fixture ->
            fixture.episodeHierarchy.set(
                listOf(episodeHierarchy(seriesName = "Old series", seasonName = "Old season"))
            )
            fixture.start()
            fixture.awaitRemoteStarted()
            fixture.waitUntil("initial episode hierarchy was not published") {
                fixture.viewModel.state.value.activeContainers
                    .flatMap { it.allEntries() }
                    .singleOrNull { it.itemId == VIDEO_ID }
                    ?.seriesName == "Old series"
            }
            val initialToken = fixture.snapshotToken()

            fixture.episodeHierarchy.set(
                listOf(
                    episodeHierarchy(
                        seriesName = "Fresh series",
                        seasonName = "Fresh season",
                        episodeIndex = 7,
                    )
                )
            )
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("new episode hierarchy was not published") {
                fixture.viewModel.state.value.activeContainers
                    .flatMap { it.allEntries() }
                    .singleOrNull { it.itemId == VIDEO_ID }
                    ?.seriesName == "Fresh series"
            }

            fixture.releaseRemote()
            fixture.awaitRemoteIdle()

            val latest =
                fixture.viewModel.state.value.activeContainers
                    .flatMap { it.allEntries() }
                    .singleOrNull { it.itemId == VIDEO_ID }
            val latestToken = fixture.snapshotToken()
            assertTrue(
                "hierarchy-only change was stale-published or omitted from the snapshot token: " +
                    "latest=$latest initialToken=$initialToken latestToken=$latestToken",
                latest?.seriesName == "Fresh series" &&
                    latest.seasonName == "Fresh season" &&
                    latest.episodeIndex == 7 &&
                    latestToken != initialToken,
            )
        }
    }

    @Test
    fun `storage change cannot be overwritten by blocked remote`() {
        Fixture().use { fixture ->
            fixture.storageUsage.set(DownloadStorageUsage(1L, 2L, 3L))
            fixture.start()
            fixture.awaitRemoteStarted()
            fixture.waitUntil("initial storage usage was not published") {
                fixture.viewModel.state.value.storage == DownloadStorageUsage(1L, 2L, 3L)
            }
            val initialToken = fixture.snapshotToken()

            fixture.storageUsage.set(DownloadStorageUsage(4L, 5L, 6L))
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("new storage usage was not published") {
                fixture.viewModel.state.value.storage == DownloadStorageUsage(4L, 5L, 6L)
            }

            fixture.releaseRemote()
            fixture.awaitRemoteIdle()

            val latestStorage = fixture.viewModel.state.value.storage
            val latestToken = fixture.snapshotToken()
            assertTrue(
                "storage-only change was stale-published or omitted from the snapshot token: " +
                    "storage=$latestStorage initialToken=$initialToken latestToken=$latestToken",
                latestStorage == DownloadStorageUsage(4L, 5L, 6L) && latestToken != initialToken,
            )
        }
    }

    @Test
    fun `completed book projection alone survives blocked remote`() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.awaitRemoteStarted()
            fixture.waitUntil("initial task projection was not published") {
                fixture.viewModel.state.value.activeTasks.singleOrNull()?.downloadedBytes == 25L
            }

            fixture.bookFiles.set(
                listOf(LocalBookFile(itemId = BOOK_ID, sizeBytes = 42, title = "Fresh book"))
            )
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("completed book inputs were not published locally") {
                fixture.viewModel.state.value.bookStorageBytes == 42L
            }

            fixture.releaseRemote()
            fixture.awaitRemoteIdle()

            val state = fixture.viewModel.state.value
            val completedBook =
                state.completedContainers
                    .flatMap { it.allEntries() }
                    .singleOrNull { it.itemId == BOOK_ID }
            assertTrue(
                "stale remote overwrote the completed-book-only projection: " +
                    "completedBook=$completedBook bookStorageBytes=${state.bookStorageBytes}",
                completedBook?.status == DownloadTaskStatus.COMPLETED &&
                    completedBook.sizeBytes == 42L &&
                    state.bookStorageBytes == 42L,
            )
        }
    }

    @Test
    fun `stop prevents blocked artwork backfill from refreshing`() {
        BlockingImageServer().use { server ->
            Fixture(
                    remoteImageUrl = server.url,
                    imageNamespace = MetadataNamespace(serverId = "server", userId = "user"),
                )
                .use { fixture ->
                    fixture.disablePolling()
                    fixture.allowRemoteImmediately()
                    fixture.start()
                    fixture.waitUntil("initial running task was not published") {
                        fixture.viewModel.state.value.activeTasks.singleOrNull()?.status ==
                            DownloadTaskStatus.RUNNING
                    }
                    fixture.awaitRemoteIdle()
                    assertTrue(
                        "artwork backfill did not begin its blocked fetch",
                        server.awaitStarted(),
                    )

                    val refreshCountBeforeStop = fixture.refreshCount.get()
                    val stateBeforeStop = fixture.viewModel.state.value
                    fixture.viewModel.stop()
                    server.release()
                    assertTrue("blocked artwork fetch did not finish", server.awaitFinished())
                    fixture.waitUntil("artwork job did not quiesce after stop") {
                        fixture.activeActionChildCount() == 0
                    }

                    assertEquals(
                        "artwork completion refreshed after stop",
                        refreshCountBeforeStop,
                        fixture.refreshCount.get(),
                    )
                    assertEquals(
                        "artwork completion republished state after stop",
                        stateBeforeStop,
                        fixture.viewModel.state.value,
                    )
                }
        }
    }

    @Test
    fun `book flow publishes failed immediately and hides superseded records`() {
        val itemId = UUID.nameUUIDFromBytes("book-sync".toByteArray())
        Fixture().use { fixture ->
            fixture.tasks.value = emptyList()
            fixture.books.value =
                listOf(
                    bookTask(itemId, BookDownloadStatus.RUNNING, generation = 1, updatedAt = 100),
                    bookTask(
                        itemId,
                        BookDownloadStatus.RUNNING,
                        generation = 1,
                        updatedAt = 100,
                        canonicalKey = "delete-receipt",
                        failureReason = "DELETE_SUPERSEDED",
                    ),
                )
            fixture.start()
            fixture.waitUntil(
                message = "initial running book was not published",
                failureDetail = { fixture.viewModel.state.value },
            ) {
                fixture.viewModel.state.value.activeTasks.isEmpty() &&
                    fixture.viewModel.state.value.activeContainers
                        .flatMap { it.allEntries() }
                        .any { it.status == DownloadTaskStatus.RUNNING }
            }

            fixture.books.value =
                listOf(
                    bookTask(
                        itemId,
                        BookDownloadStatus.FAILED,
                        generation = 2,
                        updatedAt = 200,
                        error = "offline read failed",
                    ),
                    bookTask(
                        itemId,
                        BookDownloadStatus.FAILED,
                        generation = 2,
                        updatedAt = 200,
                        canonicalKey = "delete-receipt",
                        failureReason = "DELETE_SUPERSEDED",
                    ),
                )

            fixture.waitUntil(
                message = "book flow did not publish failed before the polling fallback",
                timeoutMs = 750,
            ) {
                val state = fixture.viewModel.state.value
                state.activeContainers.isEmpty() &&
                    state.failedContainers.flatMap { it.allEntries() }.map { it.status } ==
                        listOf(DownloadTaskStatus.FAILED)
            }
        }
    }

    @Test
    fun `polling fallback still converges when an event is lost`() {
        Fixture().use { fixture ->
            fixture.allowRemoteImmediately()
            fixture.start()
            fixture.waitUntil("initial running task was not published") {
                fixture.viewModel.state.value.activeTasks.singleOrNull()?.status ==
                    DownloadTaskStatus.RUNNING
            }
            fixture.awaitRemoteIdle()

            fixture.tasks.value = listOf(failedVideoTask(generation = 2, updatedAt = 200))

            fixture.waitUntil(
                message = "polling fallback did not converge to failed",
                timeoutMs = 4_000,
            ) {
                val state = fixture.viewModel.state.value
                state.activeTasks.isEmpty() &&
                    state.failedTasks.singleOrNull()?.status == DownloadTaskStatus.FAILED
            }
        }
    }

    @Test
    fun `stop cancels polling and event collection`() {
        Fixture().use { fixture ->
            fixture.start()
            fixture.start()
            fixture.awaitRemoteStarted()
            fixture.waitUntil("queue collector was not started") {
                fixture.queueChanges.subscriptionCount.value == 1
            }
            fixture.waitUntil("initial running task was not published") {
                fixture.viewModel.state.value.activeTasks.singleOrNull()?.status ==
                    DownloadTaskStatus.RUNNING
            }

            fixture.resetExternalRefreshIngress()
            fixture.blockNextLocalRefresh()
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))
            fixture.awaitLocalRefreshBlocked()
            fixture.waitUntil("queue ingress did not reach the ViewModel") {
                fixture.externalRefreshIngressCount() == 1
            }

            fixture.viewModel.stop()
            fixture.viewModel.stop()
            fixture.waitUntil("queue collector remained active after stop") {
                fixture.queueChanges.subscriptionCount.value == 0
            }
            val refreshCountAfterStop = fixture.refreshCount.get()
            val ingressAfterStop = fixture.externalRefreshIngressCount()

            fixture.tasks.value = listOf(failedVideoTask(generation = 2, updatedAt = 200))
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))
            fixture.releaseLocalRefresh()
            fixture.releaseRemote()
            fixture.waitUntil("session jobs remained active after stop") {
                fixture.activeSessionChildCount() == 0
            }

            assertEquals(refreshCountAfterStop, fixture.refreshCount.get())
            assertEquals(ingressAfterStop, fixture.externalRefreshIngressCount())
            assertEquals(
                DownloadTaskStatus.RUNNING,
                fixture.viewModel.state.value.activeTasks.single().status,
            )
        }
    }

    private fun failedVideoTask(generation: Long, updatedAt: Long) =
        videoTask(
            status = DownloadTaskStatus.FAILED,
            generation = generation,
            updatedAt = updatedAt,
            failureReason = DownloadFailureReason.NETWORK_UNAVAILABLE,
        )

    private fun videoTask(
        status: DownloadTaskStatus,
        generation: Long,
        updatedAt: Long,
        failureReason: DownloadFailureReason? = null,
        downloadedBytes: Long = 25,
    ) =
        DownloadTask(
            itemId = VIDEO_ID,
            sourceId = "video-source",
            name = "Video",
            path = "",
            downloadId = null,
            status = status,
            failureReason = failureReason,
            downloadedBytes = downloadedBytes,
            totalBytes = 100,
            updatedAt = updatedAt,
            mediaKind = DownloadMediaKind.VIDEO,
            taskGeneration = generation,
            serverId = "server",
            userId = "user",
        )

    private fun bookTask(
        itemId: UUID,
        status: BookDownloadStatus,
        generation: Long,
        updatedAt: Long,
        canonicalKey: String = itemId.toString(),
        failureReason: String? = null,
        error: String? = null,
        downloadedBytes: Long = 25,
    ) =
        BookDownloadTask(
            itemId = itemId,
            title = "Book",
            downloadedBytes = downloadedBytes,
            totalBytes = 100,
            status = status,
            error = error,
            failureReason = failureReason,
            taskGeneration = generation,
            canonicalKey = canonicalKey,
            serverId = "server",
            userId = USER_ID,
            updatedAtEpochMillis = updatedAt,
        )

    private fun episodeHierarchy(
        seriesName: String,
        seasonName: String,
        episodeIndex: Int = 1,
    ) =
        DownloadedEpisodeHierarchy(
            episodeId = VIDEO_ID,
            seasonId = UUID.nameUUIDFromBytes("$seasonName-season".toByteArray()),
            seriesId = UUID.nameUUIDFromBytes("$seriesName-series".toByteArray()),
            episodeName = "Video",
            episodeIndex = episodeIndex,
            runtimeTicks = 1_000,
            seriesName = seriesName,
            seasonName = seasonName,
            seasonIndex = 1,
        )

    private class Fixture(
        private val remoteImageUrl: String? = null,
        private val imageNamespace: MetadataNamespace? = null,
    ) : AutoCloseable {
        private val executor = Executors.newFixedThreadPool(4)
        private val dispatcher = executor.asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val context = TestContext(createTempDirectory().toFile())
        private val remoteStarted = CountDownLatch(1)
        private val releaseRemote = CountDownLatch(1)
        private val remoteAllowed = AtomicInteger(0)
        private val localRefreshEntered = CountDownLatch(1)
        private val releaseLocalRefresh = CountDownLatch(1)
        private val blockNextLocalRefresh = AtomicBoolean(false)
        private val externalRefreshIngress = AtomicInteger(0)

        val tasks = MutableStateFlow(listOf(runningVideoTask()))
        val books = MutableStateFlow(emptyList<BookDownloadTask>())
        val queueChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
        val bookFiles = AtomicReference<List<LocalBookFile>>(emptyList())
        val offlineEntries = AtomicReference<List<OfflineMediaEntry>>(emptyList())
        val episodeHierarchy = AtomicReference<List<DownloadedEpisodeHierarchy>>(emptyList())
        val storageUsage =
            AtomicReference(
                DownloadStorageUsage(usedBytes = 0L, availableBytes = 0L, totalBytes = 0L)
            )
        val refreshCount = AtomicInteger(0)

        val viewModel: DownloadsViewModel

        init {
            val downloader =
                proxy(Downloader::class.java) { method, _ ->
                    when (method.name) {
                        "getQueueChanges" -> queueChanges
                        "refreshDownloadTasks" -> {
                            refreshCount.incrementAndGet()
                            if (blockNextLocalRefresh.compareAndSet(true, false)) {
                                localRefreshEntered.countDown()
                                check(releaseLocalRefresh.await(5, TimeUnit.SECONDS)) {
                                    "local refresh gate was not released"
                                }
                            }
                            tasks.value
                        }
                        "downloadedMediaRecords" -> emptyList<Any>()
                        "getStorageUsage" -> storageUsage.get()
                        else -> defaultValue(method.returnType)
                    }
                }
            val repository =
                proxy(JellyfinRepository::class.java) { method, _ ->
                    when (method.name) {
                        "getDownloads" -> awaitRemoteAndReturnDownloads()
                        "getEpisodeHierarchyWithSources" -> episodeHierarchy.get()
                        "getMusicTrackMetadata" -> emptyMap<UUID, Any>()
                        "getPrimaryImageUrl" -> remoteImageUrl
                        else -> defaultValue(method.returnType)
                    }
                }
            val readerRepository =
                proxy(ReaderRepository::class.java) { method, _ ->
                    when (method.name) {
                        "getBookDownloads" -> books
                        "listLocalFiles" -> bookFiles.get()
                        else -> defaultValue(method.returnType)
                    }
                }
            val offlineRepository =
                proxy(OfflineMediaRepository::class.java) { method, _ ->
                    when (method.name) {
                        "listEntries" -> offlineEntries.get()
                        else -> defaultValue(method.returnType)
                    }
                }
            viewModel =
                DownloadsViewModel(
                    context = context,
                    downloader = downloader,
                    repository = repository,
                    readerRepository = readerRepository,
                    offlineMediaRepository = offlineRepository,
                    bookCoverProvider = bookCoverProvider(context),
                    imageCacheStore = imageCacheStore(context, imageNamespace),
                )
            viewModel.actionScopeOverride = scope
            viewModel.externalRefreshIngressObserverForTest = {
                externalRefreshIngress.incrementAndGet()
            }
        }

        fun start() {
            viewModel.start()
        }

        fun disablePolling() {
            viewModel.pollIntervalMsOverrideForTest = Long.MAX_VALUE
        }

        fun awaitRemoteStarted() {
            assertTrue("remote metadata did not start", remoteStarted.await(5, TimeUnit.SECONDS))
        }

        fun awaitRemoteIdle() {
            waitUntil("remote metadata did not finish") { viewModel.isRemoteRefreshIdleForTest() }
        }

        fun allowRemoteImmediately() {
            remoteAllowed.incrementAndGet()
            releaseRemote()
        }

        fun releaseRemote() {
            releaseRemote.countDown()
        }

        fun blockNextLocalRefresh() {
            check(blockNextLocalRefresh.compareAndSet(false, true)) {
                "local refresh was already configured to block"
            }
        }

        fun awaitLocalRefreshBlocked() {
            assertTrue(
                "local refresh did not reach the blocked read",
                localRefreshEntered.await(5, TimeUnit.SECONDS),
            )
        }

        fun releaseLocalRefresh() {
            releaseLocalRefresh.countDown()
        }

        fun resetExternalRefreshIngress() {
            externalRefreshIngress.set(0)
        }

        fun externalRefreshIngressCount(): Int = externalRefreshIngress.get()

        fun snapshotToken(): String? {
            val field = DownloadsViewModel::class.java.getDeclaredField("localSnapshotToken")
            field.isAccessible = true
            return field.get(viewModel) as? String
        }

        fun activeSessionChildCount(): Int {
            val field = DownloadsViewModel::class.java.getDeclaredField("sessionJob")
            field.isAccessible = true
            val session = field.get(viewModel) as? Job ?: return 0
            return session.children.count { it.isActive }
        }

        fun activeActionChildCount(): Int =
            scope.coroutineContext[Job]?.children?.count { it.isActive } ?: 0

        fun waitUntil(
            message: String,
            timeoutMs: Long = 3_000,
            failureDetail: (() -> Any?)? = null,
            condition: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (System.nanoTime() < deadline) {
                if (condition()) return
                Thread.sleep(10)
            }
            assertTrue("$message: ${failureDetail?.invoke()}", condition())
        }

        private fun awaitRemoteAndReturnDownloads(): List<FindroidItem> {
            if (remoteAllowed.get() == 0) {
                remoteStarted.countDown()
                check(releaseRemote.await(5, TimeUnit.SECONDS)) {
                    "remote metadata gate was not released"
                }
            } else {
                remoteStarted.countDown()
            }
            return emptyList()
        }

        override fun close() {
            releaseRemote()
            releaseLocalRefresh()
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private companion object {
        val VIDEO_ID: UUID = UUID.nameUUIDFromBytes("failure-sync-video".toByteArray())
        val BOOK_ID: UUID = UUID.nameUUIDFromBytes("failure-sync-book".toByteArray())
        val USER_ID: UUID = UUID.nameUUIDFromBytes("failure-sync-user".toByteArray())

        fun runningVideoTask() =
            DownloadTask(
                itemId = VIDEO_ID,
                sourceId = "video-source",
                name = "Video",
                path = "",
                downloadId = null,
                status = DownloadTaskStatus.RUNNING,
                failureReason = null,
                downloadedBytes = 25,
                totalBytes = 100,
                updatedAt = 100,
                mediaKind = DownloadMediaKind.VIDEO,
                taskGeneration = 1,
                serverId = "server",
                userId = "user",
            )

        fun imageCacheStore(
            context: TestContext,
            namespace: MetadataNamespace?,
        ): ImageCacheStore {
            val constructor =
                ImageCacheStore::class.java.declaredConstructors.first { it.parameterCount == 2 }
            constructor.isAccessible = true
            return constructor.newInstance(context, { namespace }) as ImageCacheStore
        }

        fun bookCoverProvider(context: Context): BookCoverProvider {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val instanceField =
                unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = instanceField.get(null)
            val provider =
                unsafeClass
                    .getMethod("allocateInstance", Class::class.java)
                    .invoke(unsafe, BookCoverProvider::class.java) as BookCoverProvider
            BookCoverProvider::class
                .java
                .declaredFields
                .first { it.type == Context::class.java }
                .apply { isAccessible = true }
                .set(provider, context)
            return provider
        }

        inline fun <reified T> proxy(
            type: Class<T>,
            crossinline result: (java.lang.reflect.Method, Array<out Any?>) -> Any?,
        ): T =
            Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "Test${type.simpleName}"
                    else -> result(method, args.orEmpty())
                }
            } as T

        fun defaultValue(type: Class<*>): Any? =
            when (type) {
                Boolean::class.javaPrimitiveType -> false
                Byte::class.javaPrimitiveType -> 0.toByte()
                Short::class.javaPrimitiveType -> 0.toShort()
                Int::class.javaPrimitiveType -> 0
                Long::class.javaPrimitiveType -> 0L
                Float::class.javaPrimitiveType -> 0f
                Double::class.javaPrimitiveType -> 0.0
                Char::class.javaPrimitiveType -> '\u0000'
                Void.TYPE -> null
                Unit::class.java -> Unit
                else -> null
            }
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }

    private class BlockingImageServer : AutoCloseable {
        private val serverSocket = ServerSocket(0)
        private val started = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val finished = CountDownLatch(1)
        private val thread =
            Thread(
                    {
                        try {
                            serverSocket.accept().use { client ->
                                val input = client.getInputStream()
                                var headerTerminatorMatch = 0
                                while (true) {
                                    val next = input.read()
                                    if (next < 0) break
                                    headerTerminatorMatch =
                                        when {
                                            next == '\r'.code && headerTerminatorMatch == 0 -> 1
                                            next == '\n'.code && headerTerminatorMatch == 1 -> 2
                                            next == '\r'.code && headerTerminatorMatch == 2 -> 3
                                            next == '\n'.code && headerTerminatorMatch == 3 -> 4
                                            next == '\r'.code -> 1
                                            else -> 0
                                        }
                                    if (headerTerminatorMatch == 4) break
                                }
                                started.countDown()
                                check(release.await(5, TimeUnit.SECONDS)) {
                                    "blocked artwork response was not released"
                                }
                                val body = byteArrayOf(0x01, 0x02, 0x03)
                                val output = DataOutputStream(client.getOutputStream())
                                output.writeBytes(
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Content-Type: image/jpeg\r\n" +
                                        "Content-Length: ${body.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                )
                                output.write(body)
                                output.flush()
                            }
                        } finally {
                            finished.countDown()
                        }
                    },
                    "blocked-artwork-server",
                )
                .apply {
                    isDaemon = true
                    start()
                }

        val url: String = "http://127.0.0.1:${serverSocket.localPort}/artwork"

        fun awaitStarted(): Boolean = started.await(5, TimeUnit.SECONDS)

        fun release() {
            release.countDown()
        }

        fun awaitFinished(): Boolean = finished.await(5, TimeUnit.SECONDS)

        override fun close() {
            release()
            serverSocket.close()
            thread.join(1_000)
        }
    }
}
