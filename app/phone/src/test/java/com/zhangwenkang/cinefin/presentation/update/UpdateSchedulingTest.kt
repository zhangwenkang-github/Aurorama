package com.zhangwenkang.cinefin.presentation.update

import com.zhangwenkang.cinefin.update.UpdateWorkScheduling
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private class FakeUpdateWorkScheduling : UpdateWorkScheduling {
        var automaticCount = 0
        var manualCount = 0
        val downloadIds = mutableListOf<String>()
        var cancelCount = 0

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
        }
    }
}
