package com.zhangwenkang.cinefin.film.presentation.downloads

import android.content.Context
import android.content.ContextWrapper
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.JellyfinRepository
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
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
            Thread.sleep(150)

            val state = fixture.viewModel.state.value
            assertTrue("stale remote restored the running projection", state.activeTasks.isEmpty())
            assertEquals(
                listOf(DownloadTaskStatus.FAILED),
                state.failedTasks.map(DownloadTask::status),
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
            fixture.awaitRemoteStarted()
            fixture.waitUntil("queue collector was not started") {
                fixture.queueChanges.subscriptionCount.value == 1
            }
            fixture.waitUntil("initial running task was not published") {
                fixture.viewModel.state.value.activeTasks.singleOrNull()?.status ==
                    DownloadTaskStatus.RUNNING
            }

            fixture.viewModel.stop()
            fixture.waitUntil("queue collector remained active after stop") {
                fixture.queueChanges.subscriptionCount.value == 0
            }
            val refreshCountAfterStop = fixture.refreshCount.get()

            fixture.tasks.value = listOf(failedVideoTask(generation = 2, updatedAt = 200))
            assertTrue("queue event was not accepted", fixture.queueChanges.tryEmit(Unit))
            Thread.sleep(200)

            assertEquals(refreshCountAfterStop, fixture.refreshCount.get())
            assertEquals(
                DownloadTaskStatus.RUNNING,
                fixture.viewModel.state.value.activeTasks.single().status,
            )

            Thread.sleep(1_700)
            assertEquals(refreshCountAfterStop, fixture.refreshCount.get())
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
    ) =
        DownloadTask(
            itemId = VIDEO_ID,
            sourceId = "video-source",
            name = "Video",
            path = "",
            downloadId = null,
            status = status,
            failureReason = failureReason,
            downloadedBytes = 25,
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
    ) =
        BookDownloadTask(
            itemId = itemId,
            title = "Book",
            downloadedBytes = 25,
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
        private val remoteFinished = CountDownLatch(1)
        private val remoteAllowed = AtomicInteger(0)

        val tasks = MutableStateFlow(listOf(runningVideoTask()))
        val books = MutableStateFlow(emptyList<BookDownloadTask>())
        val queueChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
        val refreshCount = AtomicInteger(0)

        val viewModel: DownloadsViewModel

        init {
            val downloader =
                proxy(Downloader::class.java) { method, _ ->
                    when (method.name) {
                        "getQueueChanges" -> queueChanges
                        "refreshDownloadTasks" -> {
                            refreshCount.incrementAndGet()
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
                        "listLocalFiles" -> emptyList<Any>()
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
        }

        fun start() {
            viewModel.start()
        }

        fun awaitRemoteStarted() {
            assertTrue("remote metadata did not start", remoteStarted.await(5, TimeUnit.SECONDS))
        }

        fun awaitRemoteIdle() {
            waitUntil("remote metadata did not finish") {
                remoteFinished.await(0, TimeUnit.MILLISECONDS)
            }
        }

        fun allowRemoteImmediately() {
            remoteAllowed.incrementAndGet()
            releaseRemote()
        }

        fun releaseRemote() {
            releaseRemote.countDown()
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
                try {
                    releaseRemote.await(5, TimeUnit.SECONDS)
                } finally {
                    remoteFinished.countDown()
                }
            } else {
                remoteStarted.countDown()
                remoteFinished.countDown()
            }
            return emptyList()
        }

        override fun close() {
            releaseRemote()
            scope.cancel()
            executor.shutdownNow()
        }
    }

    private companion object {
        val VIDEO_ID: UUID = UUID.nameUUIDFromBytes("failure-sync-video".toByteArray())
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
