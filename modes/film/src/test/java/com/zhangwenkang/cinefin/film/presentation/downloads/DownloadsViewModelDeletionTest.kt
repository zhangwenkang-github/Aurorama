package com.zhangwenkang.cinefin.film.presentation.downloads

import android.content.ContextWrapper
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.BookDownloadTask
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MusicTrackMetadata
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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DownloadsViewModelDeletionTest {

    @Test
    fun `single completed leaf exception becomes visible and retains entry`() {
        val completed = completedMusic()
        val fixture =
            Fixture(
                tasks = emptyList(),
                outcomes = emptyMap(),
                downloads = listOf(completed),
                deleteItemException = IllegalStateException("completed music guard failed"),
            )
        try {
            fixture.start()
            fixture.waitUntil("completed music was not published") {
                fixture.viewModel.state.value.completed.any { it.item.id == completed.id }
            }
            val key = completedLeafKeys(fixture.viewModel.state.value).single()

            fixture.viewModel.onAction(DownloadAction.DeleteEntry(key))
            fixture.waitUntil("single leaf delete did not finish") {
                fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertEquals(DownloadActionFailure.DELETE, state.actionFailure)
            assertTrue(
                "single leaf delete removed the completed entry",
                completedLeafKeys(state).contains(key),
            )
            assertTrue(
                "single leaf delete leaked an unhandled exception: ${fixture.unhandled}",
                fixture.unhandled.isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `completed action exception becomes visible and retains entry`() {
        val completed = completedMusic()
        val fixture =
            Fixture(
                tasks = emptyList(),
                outcomes = emptyMap(),
                downloads = listOf(completed),
                deleteItemException = IllegalStateException("completed action guard failed"),
            )
        try {
            fixture.start()
            fixture.waitUntil("completed music was not published") {
                fixture.viewModel.state.value.completed.any { it.item.id == completed.id }
            }
            val download =
                fixture.viewModel.state.value.completed.single { it.item.id == completed.id }
            val key = completedLeafKeys(fixture.viewModel.state.value).single()

            fixture.viewModel.onAction(DownloadAction.DeleteCompleted(download))
            fixture.waitUntil("completed action delete did not finish") {
                fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertEquals(DownloadActionFailure.DELETE, state.actionFailure)
            assertTrue(
                "completed action removed the completed entry",
                completedLeafKeys(state).contains(key),
            )
            assertTrue(
                "completed action leaked an unhandled exception: ${fixture.unhandled}",
                fixture.unhandled.isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `selected batch exception becomes visible and retains selection`() {
        val task = task("music", "source-music")
        val fixture =
            Fixture(
                tasks = listOf(task),
                outcomes = emptyMap(),
                deleteDownloadsException = IllegalStateException("batch guard failed"),
            )
        try {
            fixture.start()
            val key = taskKey(task)
            fixture.viewModel.onAction(DownloadAction.SelectKeys(listOf(key)))

            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("selected delete did not finish") {
                fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertEquals(listOf(key), state.selection)
            assertEquals(DownloadActionFailure.DELETE, state.actionFailure)
            assertTrue(
                "selected delete leaked an unhandled exception: ${fixture.unhandled}",
                fixture.unhandled.isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `single task delete exception becomes visible and retains task`() {
        val task = task("music", "source-music")
        val fixture =
            Fixture(
                tasks = listOf(task),
                outcomes = emptyMap(),
                deleteTaskException = IllegalStateException("task guard failed"),
            )
        try {
            fixture.start()
            val key = taskKey(task)

            fixture.viewModel.onAction(DownloadAction.DeleteTask(task))
            fixture.waitUntil("single task delete did not finish") {
                fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertEquals(DownloadActionFailure.DELETE, state.actionFailure)
            assertTrue(
                "single task delete removed the retained task",
                state.activeContainers.flatMap(::hierarchyLeafKeys).contains(key),
            )
            assertTrue(
                "single task delete leaked an unhandled exception: ${fixture.unhandled}",
                fixture.unhandled.isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `container delete exception becomes visible and retains entries`() {
        val task = task("music", "source-music")
        val fixture =
            Fixture(
                tasks = listOf(task),
                outcomes = emptyMap(),
                deleteDownloadsException = IllegalStateException("container guard failed"),
            )
        try {
            fixture.start()
            val container = fixture.viewModel.state.value.activeContainers.single()
            val keys = hierarchyLeafKeys(container)

            fixture.viewModel.onAction(DownloadAction.DeleteContainer(container.key))
            fixture.waitUntil("container delete did not finish") {
                fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertEquals(DownloadActionFailure.DELETE, state.actionFailure)
            assertTrue(
                "container delete removed retained entries",
                state.activeContainers.flatMap(::hierarchyLeafKeys).containsAll(keys),
            )
            assertTrue(
                "container delete leaked an unhandled exception: ${fixture.unhandled}",
                fixture.unhandled.isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

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
    fun `successful original intent result clears migrated source alias`() {
        val itemId = UUID.randomUUID()
        val intent = task("intent", "intent:$itemId", itemId = itemId)
        val source = task("source", "source-real", itemId = itemId)
        val fixture =
            Fixture(
                tasks = listOf(intent),
                outcomes = mapOf("intent:$itemId" to DownloadDeleteOutcome.SUCCEEDED),
            )
        try {
            fixture.start()
            val intentKey = taskKey(intent)
            val sourceKey = taskKey(source)
            fixture.viewModel.onAction(DownloadAction.SelectKeys(listOf(intentKey)))
            fixture.deleteGate = CompletableDeferred()
            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("delete request was not started") {
                fixture.deleteRequests.isNotEmpty()
            }

            fixture.tasks.value = listOf(source)
            assertTrue(fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("unique intent to source migration did not finish") {
                val state = fixture.viewModel.state.value
                state.selection == listOf(sourceKey) &&
                    state.pendingActions == mapOf(sourceKey to DownloadPendingAction.DELETE) &&
                    state.deletionRecoveryEntries.map { it.entryKey } == listOf(sourceKey)
            }

            fixture.deleteGate?.complete(Unit)
            fixture.waitUntil("migrated alias delete did not finish") {
                fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            val mismatch = buildString {
                if (state.selection.isNotEmpty()) append("selection=${state.selection}; ")
                if (state.deletionRecoveryEntries.isNotEmpty()) {
                    append("recovery=${state.deletionRecoveryEntries.map { it.entryKey }}; ")
                }
                val deletePending =
                    state.pendingActions.filterValues { it == DownloadPendingAction.DELETE }
                if (deletePending.isNotEmpty()) append("deletePending=$deletePending; ")
                if (state.actionFailure == DownloadActionFailure.DELETE) {
                    append("actionFailure=${state.actionFailure}; ")
                }
            }
            assertTrue("migrated source alias was not fully cleared: $mismatch", mismatch.isEmpty())
        } finally {
            fixture.deleteGate?.complete(Unit)
            fixture.close()
        }
    }

    @Test
    fun `container batch completion preserves other pending and failure state`() {
        val firstSeriesId = UUID.randomUUID()
        val secondSeriesId = UUID.randomUUID()
        val failed = task("failed", "source-failed", seriesId = firstSeriesId)
        val succeeded = task("succeeded", "source-succeeded", seriesId = secondSeriesId)
        val fixture =
            Fixture(
                tasks = listOf(failed, succeeded),
                outcomes =
                    mapOf(
                        "source-failed" to DownloadDeleteOutcome.FAILED,
                        "source-succeeded" to DownloadDeleteOutcome.SUCCEEDED,
                    ),
            )
        try {
            fixture.start()
            val failedKey = taskKey(failed)
            val succeededKey = taskKey(succeeded)
            val failedContainer =
                fixture.viewModel.state.value.activeContainers.first { container ->
                    container.allEntries().any { it.key == failedKey }
                }
            val succeededContainer =
                fixture.viewModel.state.value.activeContainers.first { container ->
                    container.allEntries().any { it.key == succeededKey }
                }
            assertTrue(
                "fixture requires independent containers",
                failedContainer.key != succeededContainer.key,
            )
            fixture.deleteGates["source-failed"] = CompletableDeferred()
            fixture.deleteGates["source-succeeded"] = CompletableDeferred()

            fixture.viewModel.onAction(DownloadAction.DeleteContainer(failedContainer.key))
            fixture.waitUntil("first container delete was not started") {
                fixture.deleteRequests.any { requests ->
                    requests.any { it.sourceId == "source-failed" }
                }
            }
            fixture.viewModel.onAction(DownloadAction.DeleteContainer(failedContainer.key))
            assertEquals(
                "overlapping container deletion must fail closed",
                1,
                fixture.deleteRequests.count { requests ->
                    requests.any { it.sourceId == "source-failed" }
                },
            )
            fixture.viewModel.onAction(DownloadAction.DeleteContainer(succeededContainer.key))
            fixture.waitUntil("second container delete was not started") {
                fixture.deleteRequests.any { requests ->
                    requests.any { it.sourceId == "source-succeeded" }
                }
            }

            fixture.deleteGates.getValue("source-failed").complete(Unit)
            fixture.waitUntil("first container delete did not publish failure") {
                fixture.viewModel.state.value.actionFailure == DownloadActionFailure.DELETE
            }
            val afterFirst = fixture.viewModel.state.value
            assertEquals(
                mapOf(succeededKey to DownloadPendingAction.DELETE),
                afterFirst.pendingActions,
            )
            assertFalse(DownloadSelectionRules.canStartSelectionAction(afterFirst))
            assertEquals(DownloadActionFailure.DELETE, afterFirst.actionFailure)

            fixture.deleteGates.getValue("source-succeeded").complete(Unit)
            fixture.waitUntil("second container delete did not finish") {
                fixture.viewModel.state.value.pendingActions.isEmpty()
            }
            val afterSecond = fixture.viewModel.state.value
            assertEquals(
                "a successful peer batch must not clear another batch failure",
                DownloadActionFailure.DELETE,
                afterSecond.actionFailure,
            )
        } finally {
            fixture.deleteGates.values.forEach { it.complete(Unit) }
            fixture.close()
        }
    }

    @Test
    fun `container intent refresh moves pending ownership and rejects overlap`() {
        val itemId = UUID.randomUUID()
        val intent = task("intent", "intent:$itemId", itemId = itemId)
        val source = task("source", "source-real", itemId = itemId)
        val fixture =
            Fixture(
                tasks = listOf(intent),
                outcomes = mapOf("intent:$itemId" to DownloadDeleteOutcome.SUCCEEDED),
            )
        try {
            fixture.start()
            val intentContainer = fixture.viewModel.state.value.activeContainers.single()
            fixture.deleteGates["intent:$itemId"] = CompletableDeferred()
            fixture.viewModel.onAction(DownloadAction.DeleteContainer(intentContainer.key))
            fixture.waitUntil("container delete was not started") {
                fixture.deleteRequests.size == 1
            }

            fixture.tasks.value = listOf(source)
            assertTrue(fixture.queueChanges.tryEmit(Unit))
            val sourceKey = taskKey(source)
            fixture.waitUntil("container refresh did not publish the source") {
                fixture.viewModel.state.value.allContainers
                    .flatMap { it.allEntries() }
                    .any { it.key == sourceKey }
            }

            val state = fixture.viewModel.state.value
            val migratedBatchIds =
                requireNotNull(state.deletionPendingBatches[sourceKey]) {
                    "container pending batch owner did not follow the visible source: " +
                        "owners=${state.deletionPendingBatches}"
                }
            assertTrue(
                "container pending did not follow the visible source: " +
                    "pending=${state.pendingActions}",
                state.pendingActions == mapOf(sourceKey to DownloadPendingAction.DELETE),
            )

            val sourceContainer =
                state.activeContainers.first { container ->
                    container.allEntries().any { it.key == sourceKey }
                }
            fixture.viewModel.onAction(DownloadAction.DeleteContainer(sourceContainer.key))
            assertEquals(
                "source container must still overlap its migrated intent owner",
                1,
                fixture.deleteRequests.size,
            )
            assertEquals(
                mapOf(sourceKey to migratedBatchIds),
                fixture.viewModel.state.value.deletionPendingBatches,
            )
        } finally {
            fixture.deleteGates.values.forEach { it.complete(Unit) }
            fixture.close()
        }
    }

    @Test
    fun `converging container batches preserve earlier owner after peer finishes`() {
        val itemId = UUID.randomUUID()
        val intent = task("intent", "intent:$itemId", itemId = itemId)
        val source = task("source", "source-real", itemId = itemId)
        val intentKey = taskKey(intent)
        val sourceKey = taskKey(source)
        val fixture =
            Fixture(
                tasks = listOf(intent, source),
                outcomes =
                    mapOf(
                        "intent:$itemId" to DownloadDeleteOutcome.SUCCEEDED,
                        "source-real" to DownloadDeleteOutcome.FAILED,
                    ),
            )
        try {
            fixture.start()
            val intentContainer =
                fixture.viewModel.state.value.activeContainers.single { container ->
                    container.allEntries().single().key == intentKey
                }
            val sourceContainer =
                fixture.viewModel.state.value.activeContainers.single { container ->
                    container.allEntries().single().key == sourceKey
                }

            val intentGate = CompletableDeferred<Unit>()
            fixture.deleteGates[intent.sourceId] = intentGate
            fixture.viewModel.onAction(DownloadAction.DeleteContainer(intentContainer.key))
            fixture.waitUntil("intent batch did not start") { fixture.deleteRequests.size == 1 }
            val intentBatchId =
                fixture.viewModel.state.value.deletionPendingBatches.getValue(intentKey).single()

            fixture.deleteGates[source.sourceId] = CompletableDeferred()
            fixture.viewModel.onAction(DownloadAction.DeleteContainer(sourceContainer.key))
            fixture.waitUntil("source batch did not start") { fixture.deleteRequests.size == 2 }
            val sourceBatchId =
                fixture.viewModel.state.value.deletionPendingBatches.getValue(sourceKey).single()

            fixture.tasks.value = listOf(source)
            assertTrue(fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("converging refresh did not migrate the intent alias") {
                fixture.viewModel.state.value.deletionAliases[intentKey] == sourceKey
            }
            assertEquals(
                setOf(intentBatchId, sourceBatchId),
                fixture.viewModel.state.value.deletionPendingBatches[sourceKey],
            )

            fixture.deleteGates.getValue(source.sourceId).complete(Unit)
            fixture.waitUntil("source peer batch did not report failure") {
                fixture.viewModel.state.value.actionFailure == DownloadActionFailure.DELETE
            }
            Thread.sleep(100L)
            assertEquals(
                setOf(intentBatchId),
                fixture.viewModel.state.value.deletionPendingBatches[sourceKey],
            )
            assertEquals(
                setOf(intentBatchId),
                fixture.viewModel.state.value.deletionAliasBatches[intentKey],
            )

            fixture.deleteGates[source.sourceId] = CompletableDeferred()
            val refreshedSourceContainer =
                fixture.viewModel.state.value.activeContainers.single { container ->
                    container.allEntries().single().key == sourceKey
                }
            fixture.viewModel.onAction(DownloadAction.DeleteContainer(refreshedSourceContainer.key))
            Thread.sleep(100L)

            val afterPeerFinished = fixture.viewModel.state.value
            val requestCountAfterRetry = fixture.deleteRequests.size
            val owners = afterPeerFinished.deletionPendingBatches.values.flatten().toSet()
            assertTrue(
                "the earlier owner lost protection after its peer finished; " +
                    "pending=${afterPeerFinished.pendingActions} " +
                    "requests=$requestCountAfterRetry owners=$owners",
                afterPeerFinished.pendingActions[sourceKey] == DownloadPendingAction.DELETE &&
                    requestCountAfterRetry == 2 &&
                    owners == setOf(intentBatchId),
            )

            intentGate.complete(Unit)
            fixture.waitUntil("all owners did not clear source pending and alias") {
                val state = fixture.viewModel.state.value
                sourceKey !in state.pendingActions &&
                    state.deletionPendingBatches[sourceKey] == null &&
                    intentKey !in state.deletionAliases &&
                    intentKey !in state.deletionAliasBatches
            }
        } finally {
            fixture.deleteGates.values.forEach { it.complete(Unit) }
            fixture.close()
        }
    }

    @Test
    fun `finished failed batch leaves no consumable alias for a fresh intent success`() {
        val itemId = UUID.randomUUID()
        val intent = task("intent", "intent:$itemId", itemId = itemId)
        val source = task("old-source", "old-source", itemId = itemId)
        val fixture =
            Fixture(
                tasks = listOf(intent),
                outcomes = mapOf("intent:$itemId" to DownloadDeleteOutcome.FAILED),
            )
        try {
            fixture.start()
            val intentKey = taskKey(intent)
            fixture.viewModel.onAction(DownloadAction.SelectKeys(listOf(intentKey)))
            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("failed delete did not finish") {
                fixture.deleteRequests.size == 1 &&
                    fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            fixture.tasks.value = listOf(source)
            assertTrue(fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("source refresh did not finish") {
                fixture.viewModel.state.value.allContainers
                    .flatMap { it.allEntries() }
                    .any { it.key == taskKey(source) }
            }
            assertTrue(
                "a finished batch must not leave an alias: " +
                    "aliases=${fixture.viewModel.state.value.deletionAliases}",
                fixture.viewModel.state.value.deletionAliases.isEmpty(),
            )

            fixture.tasks.value = listOf(intent)
            assertTrue(fixture.queueChanges.tryEmit(Unit))
            fixture.waitUntil("fresh intent was not published") {
                fixture.viewModel.state.value.allContainers
                    .flatMap { it.allEntries() }
                    .any { it.key == intentKey }
            }
            fixture.outcomes = mapOf("intent:$itemId" to DownloadDeleteOutcome.SUCCEEDED)
            fixture.viewModel.onAction(DownloadAction.SelectKeys(listOf(intentKey)))
            fixture.viewModel.onAction(DownloadAction.DeleteSelected)
            fixture.waitUntil("fresh intent delete did not finish") {
                fixture.deleteRequests.size == 2 &&
                    fixture.viewModel.state.value.pendingActions.isEmpty()
            }

            val state = fixture.viewModel.state.value
            assertTrue(
                "fresh intent success retained selection: ${state.selection}",
                state.selection.isEmpty(),
            )
            assertTrue(
                "fresh intent success retained recovery: " +
                    "${state.deletionRecoveryEntries.map { it.entryKey }}",
                state.deletionRecoveryEntries.isEmpty(),
            )
            assertTrue(
                "fresh intent success retained pending: ${state.pendingActions}",
                state.pendingActions.isEmpty(),
            )
            assertNull("fresh intent success retained action failure", state.actionFailure)
        } finally {
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
        var outcomes: Map<String, DownloadDeleteOutcome>,
        private val downloads: List<FindroidItem> = emptyList(),
        private val deleteItemException: Exception? = null,
        private val deleteTaskException: Exception? = null,
        private val deleteDownloadsException: Exception? = null,
    ) {
        val tasks = MutableStateFlow(tasks)
        val queueChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val deleteRequests = mutableListOf<List<DownloadDeleteRequest>>()
        val unhandled = mutableListOf<Throwable>()
        var deleteGate: CompletableDeferred<Unit>? = null
        val deleteGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val context = TestContext(createTempDirectory().toFile())
        private val downloader =
            proxy<Downloader> { method, args ->
                when (method.name) {
                    "getQueueChanges" -> queueChanges
                    "refreshDownloadTasks" -> this.tasks.value
                    "downloadedMediaRecords" -> emptyList<Any>()
                    "getStorageUsage" -> DownloadStorageUsage(0L, 0L, 0L)
                    "deleteItem" ->
                        throw requireNotNull(deleteItemException) { "deleteItem was not expected" }
                    "deleteTask" ->
                        throw requireNotNull(deleteTaskException) { "deleteTask was not expected" }
                    "deleteDownloads" -> {
                        deleteDownloadsException?.let { throw it }
                        @Suppress("UNCHECKED_CAST")
                        val requests = args.first() as List<DownloadDeleteRequest>
                        deleteRequests += requests
                        val gate =
                            requests.firstNotNullOfOrNull { request ->
                                deleteGates[request.sourceId]
                            } ?: deleteGate
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
                    repository = repositoryProxy(downloads),
                    readerRepository = readerRepositoryProxy(),
                    offlineMediaRepository = offlineRepositoryProxy(),
                    bookCoverProvider = bookCoverProvider(),
                    imageCacheStore = imageCacheStore(context),
                )
                .apply {
                    actionScopeOverride =
                        CoroutineScope(
                            Dispatchers.Unconfined +
                                CoroutineExceptionHandler { _, error -> unhandled += error }
                        )
                    pollIntervalMsOverrideForTest = 60_000L
                }

        init {
            installTestMainDispatcher()
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
        private fun repositoryProxy(downloads: List<FindroidItem>): JellyfinRepository =
            proxy(JellyfinRepository::class.java) { method, _ ->
                when (method.name) {
                    "getDownloads" -> downloads
                    "getEpisodeHierarchyWithSources" -> emptyList<Any>()
                    "getMusicTrackMetadata" ->
                        downloads.filterIsInstance<FindroidMovie>().associate { item ->
                            item.id to
                                MusicTrackMetadata(
                                    itemId = item.id,
                                    name = item.name,
                                    albumName = "Album",
                                    artist = "Artist",
                                    indexNumber = 1,
                                    imageUri = null,
                                )
                        }
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

        private fun completedMusic(): FindroidMovie =
            FindroidMovie(
                id = UUID.randomUUID(),
                name = "Completed Music",
                originalTitle = null,
                overview = "",
                sources =
                    listOf(
                        FindroidSource(
                            id = "completed-music-source",
                            name = "Completed Music",
                            type = FindroidSourceType.LOCAL,
                            path = "/missing/completed.m4a",
                            size = 1L,
                            mediaStreams = emptyList(),
                            container = "m4a",
                        )
                    ),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = false,
                runtimeTicks = 0L,
                playbackPositionTicks = 0L,
                premiereDate = null,
                people = emptyList(),
                genres = emptyList(),
                communityRating = null,
                officialRating = null,
                status = "Released",
                productionYear = null,
                endDate = null,
                trailer = null,
                images = FindroidImages(),
                chapters = emptyList(),
                trickplayInfo = emptyMap(),
                kind = null,
            )

        private fun completedLeafKeys(state: DownloadManagerState): List<String> =
            state.completedContainers.flatMap(::hierarchyLeafKeys)

        private fun hierarchyLeafKeys(container: DownloadHierarchyContainer): List<String> =
            container.children.flatMap { child ->
                when (child) {
                    is DownloadHierarchyLeaf -> listOf(child.entry.key)
                    is DownloadHierarchySubContainer ->
                        child.children.map { leaf -> leaf.entry.key }
                }
            }

        private fun installTestMainDispatcher() {
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val instanceField =
                unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
            val unsafe = instanceField.get(null)
            val dispatcherField =
                Class.forName("kotlinx.coroutines.internal.MainDispatcherLoader")
                    .getField("dispatcher")
            val base = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            val offset =
                unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            val putObject =
                unsafeClass.getMethod(
                    "putObject",
                    Any::class.java,
                    Long::class.javaPrimitiveType,
                    Any::class.java,
                )
            putObject.invoke(
                unsafe,
                base.invoke(unsafe, dispatcherField),
                offset.invoke(unsafe, dispatcherField),
                TestMainDispatcher,
            )
        }
    }

    private object TestMainDispatcher : MainCoroutineDispatcher() {
        override val immediate: MainCoroutineDispatcher
            get() = this

        override fun dispatch(
            context: kotlin.coroutines.CoroutineContext,
            block: Runnable,
        ) {
            block.run()
        }
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    }
}
