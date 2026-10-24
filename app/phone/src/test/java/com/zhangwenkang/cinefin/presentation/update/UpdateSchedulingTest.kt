package com.zhangwenkang.cinefin.presentation.update

import com.zhangwenkang.cinefin.update.UpdateCancelResult
import com.zhangwenkang.cinefin.update.UpdateCheckMode
import com.zhangwenkang.cinefin.update.UpdateCheckResult
import com.zhangwenkang.cinefin.update.UpdateDownloadResult
import com.zhangwenkang.cinefin.update.UpdateRepository
import com.zhangwenkang.cinefin.update.UpdateWorkScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateSchedulingTest {

    @Test
    fun launchAutomaticCheckReturnsBeforeTheSchedulerRuns() {
        val scheduler = FakeUpdateWorkScheduling()

        val job =
            launchAutomaticUpdateCheck(
                scope = CoroutineScope(Dispatchers.Unconfined),
                scheduler = scheduler,
            )

        assertFalse(job.isCompleted)
        assertEquals(0, scheduler.automaticCount)

        job.start()

        assertTrue(job.isCompleted)
        assertEquals(1, scheduler.automaticCount)
        assertEquals(0, scheduler.manualCount)
    }

    @Test
    fun manualUiActionNeverUsesTheAutomaticCheckEntryPoint() {
        val scheduler = FakeUpdateWorkScheduling()

        dispatchUpdateAction(UpdateAction.CHECK, "a".repeat(64), scheduler)

        assertEquals(1, scheduler.manualCount)
        assertEquals(0, scheduler.automaticCount)
    }

    @Test
    fun downloadActionsUseThePersistedUpdateIdentity() {
        val scheduler = FakeUpdateWorkScheduling()
        val updateId = "b".repeat(64)

        dispatchUpdateAction(UpdateAction.DOWNLOAD, updateId, scheduler)
        dispatchUpdateAction(UpdateAction.CANCEL, updateId, scheduler)

        assertEquals(listOf(updateId), scheduler.downloadIds)
        assertEquals(1, scheduler.cancelCount)
    }

    @Test
    fun uiCancelPersistsCancelledBeforeCancellingUniqueWork() = runBlocking {
        val events = mutableListOf<String>()
        val repository =
            FakeCancellationRepository(UpdateCancelResult.Success) { events += "persist" }
        val scheduler = FakeUpdateWorkScheduling()
        scheduler.onCancel = { events += "cancel-work" }

        val handled =
            cancelUpdateDownload(
                updateId = "b".repeat(64),
                repository = repository,
                scheduler = scheduler,
            )

        assertTrue(handled)
        assertEquals(listOf("persist", "cancel-work"), events)
    }

    @Test
    fun uiCancelDoesNotTouchWorkWhenPersistenceFailsClosed() = runBlocking {
        val repository =
            FakeCancellationRepository(UpdateCancelResult.Failure("identity mismatch")) {}
        val scheduler = FakeUpdateWorkScheduling()

        val handled =
            cancelUpdateDownload(
                updateId = "b".repeat(64),
                repository = repository,
                scheduler = scheduler,
            )
        val missingId =
            cancelUpdateDownload(updateId = null, repository = repository, scheduler = scheduler)

        assertFalse(handled)
        assertFalse(missingId)
        assertEquals(0, scheduler.cancelCount)
    }

    private class FakeUpdateWorkScheduling : UpdateWorkScheduling {
        var automaticCount = 0
        var manualCount = 0
        val downloadIds = mutableListOf<String>()
        var cancelCount = 0
        var onCancel: () -> Unit = {}

        override fun scheduleAutomaticCheck() {
            automaticCount++
        }

        override fun scheduleManualCheck() {
            manualCount++
        }

        override fun scheduleDownload(updateId: String) {
            downloadIds += updateId
        }

        override fun cancelDownload() {
            cancelCount++
            onCancel()
        }
    }

    private class FakeCancellationRepository(
        private val result: UpdateCancelResult,
        private val onCancel: () -> Unit,
    ) : UpdateRepository {
        override suspend fun check(mode: UpdateCheckMode): UpdateCheckResult =
            throw AssertionError("check must not run")

        override suspend fun download(
            updateId: String,
            onProgress: (Long) -> Unit,
        ): UpdateDownloadResult = throw AssertionError("download must not run")

        override suspend fun cancel(updateId: String): UpdateCancelResult {
            onCancel()
            return result
        }
    }
}
