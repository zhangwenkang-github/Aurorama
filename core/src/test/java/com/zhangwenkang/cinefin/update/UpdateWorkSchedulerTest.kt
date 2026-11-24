package com.zhangwenkang.cinefin.update

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateWorkSchedulerTest {
    @Test
    fun automaticCheckPassesCoordinatorGateDelayToUniqueWork() {
        val gateway = FakeGateway()
        val scheduler = scheduler(gateway = gateway, automaticDelayMs = 15_000L)

        scheduler.scheduleAutomaticCheck()

        assertEquals(
            listOf(
                Enqueued(
                    UpdateWorkCommand.AutomaticCheck(initialDelayMs = 15_000L),
                    ExistingWorkPolicy.KEEP,
                )
            ),
            gateway.enqueued,
        )
    }

    @Test
    fun automaticCheckAtBoundaryPassesZeroDelay() {
        val gateway = FakeGateway()
        val scheduler = scheduler(gateway = gateway, automaticDelayMs = 0L)

        scheduler.scheduleAutomaticCheck()

        assertEquals(
            listOf(
                Enqueued(
                    UpdateWorkCommand.AutomaticCheck(initialDelayMs = 0L),
                    ExistingWorkPolicy.KEEP,
                )
            ),
            gateway.enqueued,
        )
    }

    @Test
    fun manualCheckAndDownloadReplaceQueuedWorkSoUserRequestsAreNeverSwallowed() {
        val gateway = FakeGateway()
        val scheduler = scheduler(gateway = gateway, automaticDelayMs = 0L)
        val updateId = "a".repeat(64)

        scheduler.scheduleManualCheck()
        scheduler.scheduleDownload(updateId)

        assertEquals(
            listOf(
                Enqueued(UpdateWorkCommand.ManualCheck, ExistingWorkPolicy.REPLACE),
                Enqueued(UpdateWorkCommand.Download(updateId), ExistingWorkPolicy.REPLACE),
            ),
            gateway.enqueued,
        )
    }

    @Test
    fun cancelDownloadCancelsTheUniqueDownloadWork() {
        val gateway = FakeGateway()
        val scheduler = scheduler(gateway = gateway, automaticDelayMs = 0L)

        scheduler.cancelDownload()

        assertEquals(1, gateway.cancelDownloadCalls)
        assertEquals(emptyList<Enqueued>(), gateway.enqueued)
    }

    @Test(expected = IllegalArgumentException::class)
    fun downloadRejectsNonCanonicalUpdateIdBeforeWorkEnqueue() {
        val scheduler = scheduler(FakeGateway(), automaticDelayMs = 0L)

        scheduler.scheduleDownload("not-an-update-id")
    }

    private fun scheduler(
        gateway: UpdateWorkGateway,
        automaticDelayMs: Long,
    ) =
        UpdateWorkScheduler(
            gateway = gateway,
            automaticCheckDelayMs = { automaticDelayMs },
        )
}

private data class Enqueued(
    val command: UpdateWorkCommand,
    val policy: ExistingWorkPolicy,
)

private class FakeGateway : UpdateWorkGateway {
    val enqueued = mutableListOf<Enqueued>()
    var cancelDownloadCalls = 0

    override fun enqueue(
        command: UpdateWorkCommand,
        policy: ExistingWorkPolicy,
    ) {
        enqueued += Enqueued(command, policy)
    }

    override fun cancelDownload() {
        cancelDownloadCalls++
    }
}
