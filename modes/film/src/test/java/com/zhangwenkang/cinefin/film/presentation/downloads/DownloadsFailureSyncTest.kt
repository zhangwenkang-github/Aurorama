package com.zhangwenkang.cinefin.film.presentation.downloads

import android.content.Context
import android.content.ContextWrapper
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.LocalBookFile
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.ImageCacheStore
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import java.io.File
import java.lang.reflect.Proxy
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

    private class Fixture : AutoCloseable {
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
                        "getStorageUsage" -> DownloadStorageUsage(0L, 0L, 0L)
                        else -> defaultValue(method.returnType)
                    }
                }
            val repository =
                proxy(JellyfinRepository::class.java) { method, _ ->
                    when (method.name) {
                        "getDownloads" -> awaitRemoteAndReturnDownloads()
                        "getEpisodeHierarchyWithSources" -> emptyList<Any>()
                        "getMusicTrackMetadata" -> emptyMap<UUID, Any>()
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
                        "listEntries" -> emptyList<Any>()
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
                    imageCacheStore = imageCacheStore(context),
                )
            viewModel.actionScopeOverride = scope
            viewModel.externalRefreshIngressObserverForTest = {
                externalRefreshIngress.incrementAndGet()
            }
        }

        fun start() {
            viewModel.start()
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

        fun activeSessionChildCount(): Int {
            val field = DownloadsViewModel::class.java.getDeclaredField("sessionJob")
            field.isAccessible = true
            val session = field.get(viewModel) as? Job ?: return 0
            return session.children.count { it.isActive }
        }

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

        fun imageCacheStore(context: TestContext): ImageCacheStore {
            val constructor =
                ImageCacheStore::class.java.declaredConstructors.first { it.parameterCount == 2 }
            constructor.isAccessible = true
            return constructor.newInstance(context, { null }) as ImageCacheStore
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
}
