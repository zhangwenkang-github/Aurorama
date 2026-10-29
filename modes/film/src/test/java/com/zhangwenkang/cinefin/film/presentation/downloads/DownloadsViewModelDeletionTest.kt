package com.zhangwenkang.cinefin.film.presentation.downloads

import android.content.ContextWrapper
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.utils.BookCoverProvider
import com.zhangwenkang.cinefin.utils.DownloadDeleteBatchResult
import com.zhangwenkang.cinefin.utils.DownloadDeleteOutcome
import com.zhangwenkang.cinefin.utils.DownloadDeleteReceipt
import com.zhangwenkang.cinefin.utils.DownloadDeleteRequest
import com.zhangwenkang.cinefin.utils.DownloadDeletionIdentity
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
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloadsViewModelDeletionTest {

    @Test
    fun `batch result removes only succeeded entries`() {
        val succeeded = task("succeeded", "source-success")
        val failed = task("failed", "source-failed")
        val superseded = task("superseded", "source-superseded")
        val fixture =
            Fixture(
                tasks = listOf(succeeded, failed, superseded),
                outcomes =
                    mapOf(
                        "source-success" to DownloadDeleteOutcome.SUCCEEDED,
                        "source-failed" to DownloadDeleteOutcome.FAILED,
                        "source-superseded" to DownloadDeleteOutcome.SUPERSEDED,
                    ),
            )
        try {
            fixture.start()
            fixture.viewModel.onAction(
                DownloadAction.SelectKeys(
                    listOf(taskKey(succeeded), taskKey(failed), taskKey(superseded))
                )
            )
            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("delete batch did not finish") {
                fixture.deleteRequests.isNotEmpty() &&
                    fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertEquals(
                listOf(taskKey(failed), taskKey(superseded)),
                state.selection,
            )
            assertEquals(DownloadActionFailure.DELETE, state.actionFailure)
            assertEquals(
                setOf(taskKey(failed), taskKey(superseded)),
                state.deletionRecoveryEntries.mapTo(mutableSetOf()) { it.entryKey },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `superseded child keeps parent expanded`() {
        val succeeded =
            task("succeeded", "source-success")
                .copy(mediaKind = DownloadMediaKind.MUSIC, albumName = "Album")
        val superseded =
            task("superseded", "source-superseded")
                .copy(mediaKind = DownloadMediaKind.MUSIC, albumName = "Album")
        val fixture =
            Fixture(
                tasks = listOf(succeeded, superseded),
                outcomes =
                    mapOf(
                        "source-success" to DownloadDeleteOutcome.SUCCEEDED,
                        "source-superseded" to DownloadDeleteOutcome.SUPERSEDED,
                    ),
            )
        try {
            fixture.start()
            val container =
                fixture.viewModel.state.value.activeContainers.first { container ->
                    val keys = container.allEntries().mapTo(mutableSetOf()) { it.key }
                    taskKey(succeeded) in keys && taskKey(superseded) in keys
                }
            fixture.viewModel.onAction(DownloadAction.ToggleContainer(container.key))
            fixture.viewModel.onAction(DownloadAction.DeleteContainer(container.key))
            fixture.waitUntil("container delete did not finish") {
                fixture.deleteRequests.isNotEmpty() &&
                    fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertTrue(container.key in state.expandedKeys)
            assertEquals(DownloadActionFailure.DELETE, state.actionFailure)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `superseded result keeps selection and exposes precise failure reason`() {
        val task = task("superseded", "source-superseded")
        val fixture =
            Fixture(
                tasks = listOf(task),
                outcomes = mapOf("source-superseded" to DownloadDeleteOutcome.SUPERSEDED),
            )
        try {
            fixture.start()
            val key = taskKey(task)
            fixture.viewModel.onAction(DownloadAction.SelectKeys(listOf(key)))
            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("superseded delete did not finish") {
                fixture.deleteRequests.isNotEmpty() &&
                    fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertEquals(listOf(key), state.selection)
            assertEquals(DownloadActionFailure.DELETE, state.actionFailure)
            assertEquals(DownloadDeletionFailureReason.SUPERSEDED, state.deletionFailureReason)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `ambiguous intent migration preserves selection and fails deletion`() {
        val itemId = UUID.randomUUID()
        val intent = task("intent", "intent:$itemId", itemId = itemId)
        val first = task("first", "source-1", itemId = itemId)
        val second = task("second", "source-2", itemId = itemId)
        val fixture =
            Fixture(
                tasks = listOf(intent),
                outcomes = mapOf("intent:$itemId" to DownloadDeleteOutcome.FAILED),
            )
        try {
            fixture.start()
            val intentKey = taskKey(intent)
            fixture.viewModel.onAction(DownloadAction.SelectKeys(listOf(intentKey)))
            fixture.deleteGate = CompletableDeferred()
            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("delete request was not started") {
                fixture.deleteRequests.isNotEmpty()
            }

            fixture.tasks.value = listOf(first, second)
            assertTrue(fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("ambiguous migration was not surfaced") {
                fixture.viewModel.state.value.actionFailure == DownloadActionFailure.DELETE
            }

            val state = fixture.viewModel.state.value
            assertEquals(listOf(intentKey), state.selection)
            assertEquals(
                listOf(intentKey),
                state.deletionRecoveryEntries.map { it.entryKey },
            )
        } finally {
            fixture.deleteGate?.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `hidden failed selection retries from frozen recovery snapshot`() {
        val selected = task("selected", "source-selected")
        val fixture =
            Fixture(
                tasks = listOf(selected),
                outcomes = mapOf("source-selected" to DownloadDeleteOutcome.FAILED),
            )
        try {
            fixture.start()
            val key = taskKey(selected)
            fixture.viewModel.onAction(DownloadAction.SelectKeys(listOf(key)))
            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("first delete did not finish") {
                fixture.deleteRequests.size == 1 &&
                    fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            fixture.tasks.value = emptyList()
            assertTrue(fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("hidden failed selection was not retained") {
                fixture.viewModel.state.value.activeContainers.isEmpty() &&
                    fixture.viewModel.state.value.selection == listOf(key)
            }
            assertNull(fixture.viewModel.state.value.selectedEntries.singleOrNull())

            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("hidden selection did not retry from recovery snapshot") {
                fixture.deleteRequests.size == 2
            }

            assertEquals(
                "source-selected",
                fixture.deleteRequests.last().single().sourceId,
            )
        } finally {
            fixture.close()
        }
    }

    private fun task(
        name: String,
        sourceId: String,
        itemId: UUID = UUID.randomUUID(),
        seriesId: UUID? = null,
    ) =
        DownloadTask(
            itemId = itemId,
            sourceId = sourceId,
            name = name,
            path = "/tmp/$sourceId.download",
            downloadId = null,
            status = DownloadTaskStatus.RUNNING,
            failureReason = null,
            downloadedBytes = 0L,
            totalBytes = 100L,
            updatedAt = 0L,
            mediaKind = DownloadMediaKind.VIDEO,
            seriesId = seriesId,
            seasonId = seriesId?.let { UUID.randomUUID() },
            seriesName = seriesId?.let { "Series" },
            seasonName = seriesId?.let { "Season" },
            episodeIndex = if (seriesId == null) 0 else name.hashCode(),
            serverId = "server-a",
            userId = "user-a",
        )

    private class Fixture(
        tasks: List<DownloadTask>,
        private val outcomes: Map<String, DownloadDeleteOutcome>,
    ) {
        val tasks = MutableStateFlow(tasks)
        val queueChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val deleteRequests = mutableListOf<List<DownloadDeleteRequest>>()
        var deleteGate: CompletableDeferred<Unit>? = null
        private val context = TestContext(createTempDirectory().toFile())
        private val downloader =
            proxy<Downloader> { method, args ->
                when (method.name) {
                    "getQueueChanges" -> queueChanges
                    "refreshDownloadTasks" -> this.tasks.value
                    "downloadedMediaRecords" -> emptyList<Any>()
                    "getStorageUsage" -> DownloadStorageUsage(0L, 0L, 0L)
                    "deleteDownloads" -> {
                        @Suppress("UNCHECKED_CAST")
                        val requests = args.first() as List<DownloadDeleteRequest>
                        deleteRequests += requests
                        val gate = deleteGate
                        if (gate == null) {
                            result(requests)
                        } else {
                            @Suppress("UNCHECKED_CAST")
                            val continuation =
                                args.last() as Continuation<DownloadDeleteBatchResult>
                            gate.invokeOnCompletion { continuation.resume(result(requests)) }
                            COROUTINE_SUSPENDED
                        }
                    }
                    else -> defaultValue(method.returnType)
                }
            }
        val viewModel =
            DownloadsViewModel(
                    context = context,
                    downloader = downloader,
                    repository = repositoryProxy(),
                    readerRepository = readerRepositoryProxy(),
                    offlineMediaRepository = offlineRepositoryProxy(),
                    bookCoverProvider = bookCoverProvider(),
                    imageCacheStore = imageCacheStore(context),
                )
                .apply {
                    actionScopeOverride = CoroutineScope(Dispatchers.Unconfined)
                    pollIntervalMsOverrideForTest = 60_000L
                }

        fun start() {
            viewModel.start()
            waitUntil("initial tasks were not published") { viewModel.state.value.isLoading.not() }
        }

        fun close() {
            viewModel.stop()
        }

        fun waitUntil(
            message: String,
            timeoutMs: Long = 2_000L,
            condition: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (!condition()) {
                if (System.nanoTime() >= deadline) fail(message)
                Thread.sleep(10L)
            }
        }

        private fun result(requests: List<DownloadDeleteRequest>): DownloadDeleteBatchResult =
            DownloadDeleteBatchResult(
                batchId = "batch-under-test",
                receipts =
                    requests.mapIndexed { ordinal, request ->
                        val outcome = outcomes[request.sourceId] ?: DownloadDeleteOutcome.FAILED
                        DownloadDeleteReceipt(
                            batchId = "batch-under-test",
                            canonicalKey = DownloadDeletionIdentity.canonicalKey(request),
                            itemId = request.itemId,
                            sourceId = request.sourceId,
                            mediaKind = request.mediaKind,
                            serverId = request.serverId,
                            userId = request.userId,
                            ordinal = ordinal,
                            outcome = outcome,
                            detail =
                                if (outcome == DownloadDeleteOutcome.SUPERSEDED) {
                                    "superseded"
                                } else {
                                    null
                                },
                            updatedAtMs = 0L,
                            expectedGeneration = request.expectedGeneration,
                        )
                    },
            )
    }

    private companion object {
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
            val instanceField =
                unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = instanceField.get(null)
            return unsafeClass
                .getMethod("allocateInstance", Class::class.java)
                .invoke(unsafe, BookCoverProvider::class.java) as BookCoverProvider
        }

        private inline fun <reified T> proxy(
            noinline result: (java.lang.reflect.Method, Array<out Any?>) -> Any?
        ): T = proxy(T::class.java, result)

        private fun <T> proxy(
            type: Class<T>,
            result: (java.lang.reflect.Method, Array<out Any?>) -> Any?,
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
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
