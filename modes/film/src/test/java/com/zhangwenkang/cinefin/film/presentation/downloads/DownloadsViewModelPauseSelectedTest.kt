package com.zhangwenkang.cinefin.film.presentation.downloads

import android.content.ContextWrapper
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import com.zhangwenkang.cinefin.utils.CanonicalActiveTaskRefresh
import com.zhangwenkang.cinefin.utils.CanonicalActiveTaskSnapshotSource
import com.zhangwenkang.cinefin.utils.CanonicalDownloadAction
import com.zhangwenkang.cinefin.utils.DownloadControlPlaneRules
import com.zhangwenkang.cinefin.utils.DownloadIntentPhase
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadStorageUsage
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.utils.ImageCacheStore
import com.zhangwenkang.cinefin.utils.OfflineMediaRepository
import com.zhangwenkang.cinefin.utils.downloadIdentity
import java.io.File
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadsViewModelPauseSelectedTest {

    @Test
    fun `page applies an externally published canonical refresh epoch`() = runBlocking {
        val initialTasks =
            (1L..12L).map { index ->
                task(
                    sourceId = "episode-$index",
                    intentPhase = null,
                    suffix = index,
                    status = DownloadTaskStatus.RUNNING,
                )
            }
        val publishedTasks =
            initialTasks.take(11).mapIndexed { index, task ->
                task.copy(
                    status =
                        if (index == 10) DownloadTaskStatus.PAUSED else DownloadTaskStatus.RUNNING,
                    downloadedBytes = (index + 1L) * 1_000L,
                    totalBytes = (index + 1L) * 10_000L,
                    updatedAt = 2_000L + index,
                )
            }
        val initialRefresh = canonicalRefresh(initialTasks, samplingEpochMs = 100L)
        val publishedRefresh = canonicalRefresh(publishedTasks, samplingEpochMs = 101L)
        val publications = MutableStateFlow(initialRefresh)
        var canonicalRefreshReads = 0
        var rawTaskReads = 0
        val context = TestContext(createTempDirectory().toFile())
        val viewModel =
            DownloadsViewModel(
                context = context,
                downloader =
                    canonicalDownloaderProxy(
                        refresh = initialRefresh,
                        canonicalRefreshEvents = publications,
                        onCanonicalRefresh = { canonicalRefreshReads += 1 },
                        onRawTaskRead = { rawTaskReads += 1 },
                        onCompletedRecordRead = {},
                    ),
                repository = repositoryProxy(),
                readerRepository = readerRepositoryProxy(),
                offlineMediaRepository = offlineRepositoryProxy(),
                bookCoverProvider = bookCoverProvider(),
                imageCacheStore = imageCacheStore(context),
            )

        viewModel.actionScopeOverride = CoroutineScope(Dispatchers.Unconfined)
        viewModel.pollIntervalMsOverrideForTest = 60_000L
        viewModel.start()

        publications.value = publishedRefresh
        withTimeout(5_000L) {
            while (
                viewModel.state.value.activeTasks.size != publishedRefresh.snapshot.activeCount
            ) {
                delay(10L)
            }
        }

        val container = viewModel.state.value.activeContainers.single()
        assertEquals(1, canonicalRefreshReads)
        assertEquals(0, rawTaskReads)
        assertEquals(
            publishedRefresh.snapshot.sortedIdentityKeys,
            viewModel.state.value.activeTasks.map { task -> task.downloadIdentity().canonicalKey },
        )
        assertEquals(publishedRefresh.snapshot.downloadedBytes, container.downloadedBytes)
        assertEquals(publishedRefresh.snapshot.totalBytes, container.totalBytes)
        assertEquals(
            publishedRefresh.snapshot.projection.actionEligibleIdentities.keys,
            canonicalPageActions(container),
        )
        viewModel.stop()
    }

    @Test
    fun `page consumes one canonical refresh payload without a second raw task read`() {
        val tasks =
            (1L..12L).map { index ->
                task(
                    sourceId = "episode-$index",
                    intentPhase = null,
                    suffix = index,
                    status =
                        if (index == 12L) DownloadTaskStatus.PAUSED else DownloadTaskStatus.RUNNING,
                )
            }
        val snapshot =
            DownloadControlPlaneRules.canonicalActiveTaskSnapshot(
                tasks = tasks,
                downloadedCanonicalKeys = emptySet(),
                samplingEpochMs = 77L,
            )
        val refresh =
            CanonicalActiveTaskRefresh(
                tasks = tasks,
                completedMediaRecords = emptyList(),
                snapshot = snapshot,
            )
        var canonicalRefreshReads = 0
        var rawTaskReads = 0
        var completedRecordReads = 0
        val context = TestContext(createTempDirectory().toFile())
        val viewModel =
            DownloadsViewModel(
                context = context,
                downloader =
                    canonicalDownloaderProxy(
                        refresh = refresh,
                        onCanonicalRefresh = { canonicalRefreshReads += 1 },
                        onRawTaskRead = { rawTaskReads += 1 },
                        onCompletedRecordRead = { completedRecordReads += 1 },
                    ),
                repository = repositoryProxy(),
                readerRepository = readerRepositoryProxy(),
                offlineMediaRepository = offlineRepositoryProxy(),
                bookCoverProvider = bookCoverProvider(),
                imageCacheStore = imageCacheStore(context),
            )

        viewModel.actionScopeOverride = CoroutineScope(Dispatchers.Unconfined)
        viewModel.pollIntervalMsOverrideForTest = 60_000L
        viewModel.start()

        val container = viewModel.state.value.activeContainers.single()
        assertEquals(1, canonicalRefreshReads)
        assertEquals(0, rawTaskReads)
        assertEquals(0, completedRecordReads)
        assertEquals(12, viewModel.state.value.activeTasks.size)
        assertEquals(snapshot.downloadedBytes, container.downloadedBytes)
        assertEquals(snapshot.totalBytes, container.totalBytes)
        assertEquals(
            snapshot.projection.actionEligibleIdentities.keys,
            canonicalPageActions(container),
        )
        viewModel.stop()
    }

    @Test
    fun `selected pause sends only real source tasks to pauseTask`() {
        val realSource =
            task(
                sourceId = "real-source",
                intentPhase = DownloadIntentPhase.SOURCE_PENDING,
                suffix = 1,
            )
        val intentTasks =
            DownloadIntentPhase.entries.mapIndexed { index, phase ->
                task(
                    sourceId = "intent:${UUID.nameUUIDFromBytes("intent-$index".toByteArray())}",
                    intentPhase = phase,
                    suffix = index + 2L,
                )
            }
        val paused = mutableListOf<DownloadTask>()
        val downloads = listOf(realSource) + intentTasks
        val context = TestContext(createTempDirectory().toFile())
        val repository = repositoryProxy()
        val viewModel =
            DownloadsViewModel(
                context = context,
                downloader = downloaderProxy(downloads, paused),
                repository = repository,
                readerRepository = readerRepositoryProxy(),
                offlineMediaRepository = offlineRepositoryProxy(),
                bookCoverProvider = bookCoverProvider(),
                imageCacheStore = imageCacheStore(context),
            )

        viewModel.actionScopeOverride = CoroutineScope(Dispatchers.Unconfined)
        viewModel.start()
        val selectedKeys = viewModel.state.value.activeTasks.map(::taskKey)

        assertEquals(4, selectedKeys.size)
        viewModel.onAction(DownloadAction.SelectKeys(selectedKeys))
        viewModel.onAction(DownloadAction.PauseSelected)

        assertEquals(listOf("real-source"), paused.map(DownloadTask::sourceId))
    }

    private fun task(
        sourceId: String,
        intentPhase: DownloadIntentPhase?,
        suffix: Long,
        status: DownloadTaskStatus = DownloadTaskStatus.PENDING,
    ) =
        DownloadTask(
            itemId = UUID(0L, suffix),
            sourceId = sourceId,
            name = "Task $suffix",
            path = "",
            downloadId = null,
            status = status,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 100L,
            updatedAt = suffix,
            mediaKind = DownloadMediaKind.MUSIC,
            intentPhase = intentPhase,
        )

    private fun downloaderProxy(
        tasks: List<DownloadTask>,
        paused: MutableList<DownloadTask>,
    ): Downloader =
        proxy(Downloader::class.java) { method, args ->
            when (method.name) {
                "getQueueChanges" -> MutableSharedFlow<Unit>()
                "refreshDownloadTasks" -> tasks
                "downloadedMediaRecords" -> emptyList<Any>()
                "getStorageUsage" -> DownloadStorageUsage(0L, 0L, 0L)
                "pauseTask" -> {
                    paused += args.first() as DownloadTask
                    true
                }
                else -> defaultValue(method.returnType)
            }
        }

    private fun canonicalDownloaderProxy(
        refresh: CanonicalActiveTaskRefresh,
        canonicalRefreshEvents: StateFlow<CanonicalActiveTaskRefresh>? = null,
        onCanonicalRefresh: () -> Unit,
        onRawTaskRead: () -> Unit,
        onCompletedRecordRead: () -> Unit,
    ): Downloader =
        Proxy.newProxyInstance(
            Downloader::class.java.classLoader,
            arrayOf(Downloader::class.java, CanonicalActiveTaskSnapshotSource::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "CanonicalTestDownloader"
                "getQueueChanges" -> MutableSharedFlow<Unit>()
                "refreshCanonicalActiveTaskRefresh" -> {
                    onCanonicalRefresh()
                    refresh
                }
                "latestCanonicalActiveTaskRefresh" -> refresh
                "latestCanonicalActiveTaskSnapshot" -> refresh.snapshot
                "getCanonicalRefreshEvents",
                "canonicalRefreshEvents" -> canonicalRefreshEvents
                "refreshDownloadTasks" -> {
                    onRawTaskRead()
                    refresh.tasks.dropLast(1)
                }
                "downloadedMediaRecords" -> {
                    onCompletedRecordRead()
                    emptyList<Any>()
                }
                "getStorageUsage" -> DownloadStorageUsage(0L, 0L, 0L)
                else -> defaultValue(method.returnType)
            }
        } as Downloader

    private fun canonicalRefresh(
        tasks: List<DownloadTask>,
        samplingEpochMs: Long,
    ): CanonicalActiveTaskRefresh =
        CanonicalActiveTaskRefresh(
            tasks = tasks,
            completedMediaRecords = emptyList(),
            snapshot =
                DownloadControlPlaneRules.canonicalActiveTaskSnapshot(
                    tasks = tasks,
                    downloadedCanonicalKeys = emptySet(),
                    samplingEpochMs = samplingEpochMs,
                ),
        )

    private fun canonicalPageActions(
        container: DownloadHierarchyContainer
    ): Set<CanonicalDownloadAction> =
        container
            .taskActionSpecs()
            .mapNotNull { spec ->
                when (spec.action) {
                    DownloadContainerTaskAction.PAUSE -> CanonicalDownloadAction.PAUSE
                    DownloadContainerTaskAction.RESUME -> CanonicalDownloadAction.RESUME
                    DownloadContainerTaskAction.RETRY -> null
                }
            }
            .toSet() +
            if (container.canDelete) setOf(CanonicalDownloadAction.DELETE) else emptySet()

    private fun repositoryProxy(): JellyfinRepository =
        proxy(JellyfinRepository::class.java) { method, _ ->
            when (method.name) {
                "getDownloads" -> emptyList<FindroidItem>()
                "getEpisodeHierarchyWithSources" -> emptyList<Any>()
                "getMusicTrackMetadata" -> emptyMap<UUID, Any>()
                else -> defaultValue(method.returnType)
            }
        }

    private fun readerRepositoryProxy(): ReaderRepository =
        proxy(ReaderRepository::class.java) { method, _ ->
            when (method.name) {
                "getBookDownloads" -> MutableStateFlow(emptyList<BookDownloadTask>())
                "listLocalFiles" -> emptyList<Any>()
                else -> defaultValue(method.returnType)
            }
        }

    private fun offlineRepositoryProxy(): OfflineMediaRepository =
        proxy(OfflineMediaRepository::class.java) { method, _ ->
            when (method.name) {
                "listEntries" -> emptyList<Any>()
                else -> defaultValue(method.returnType)
            }
        }

    private fun imageCacheStore(context: TestContext): ImageCacheStore {
        val constructor =
            ImageCacheStore::class.java.declaredConstructors.first { it.parameterCount == 2 }
        constructor.isAccessible = true
        return constructor.newInstance(context, { null }) as ImageCacheStore
    }

    private fun bookCoverProvider(): BookCoverProvider {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val instanceField = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        val unsafe = instanceField.get(null)
        return unsafeClass
            .getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, BookCoverProvider::class.java) as BookCoverProvider
    }

    private inline fun <reified T> proxy(
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
            Void.TYPE -> null
            Unit::class.java -> Unit
            else -> null
        }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
