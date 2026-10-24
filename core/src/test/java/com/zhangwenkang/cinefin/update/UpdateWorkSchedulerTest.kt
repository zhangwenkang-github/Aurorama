package com.zhangwenkang.cinefin.update

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateWorkSchedulerTest {
    @Test
    fun automaticCheckPassesCoordinatorGateDelayToUniqueWork() {
        val gateway = FakeGateway()
        val scheduler = scheduler(gateway = gateway, automaticDelayMs = 15_000L)

        scheduler.scheduleAutomaticCheck()

        assertEquals(
            listOf(UpdateWorkCommand.AutomaticCheck(initialDelayMs = 15_000L)),
            gateway.commands,
        )
    }

    @Test
    fun automaticCheckAtBoundaryPassesZeroDelay() {
        val gateway = FakeGateway()
        val scheduler = scheduler(gateway = gateway, automaticDelayMs = 0L)

        scheduler.scheduleAutomaticCheck()

        assertEquals(
            listOf(UpdateWorkCommand.AutomaticCheck(initialDelayMs = 0L)),
            gateway.commands,
        )
    }

    @Test
    fun manualCheckAndDownloadUseSeparateStableCommands() {
        val gateway = FakeGateway()
        val scheduler = scheduler(gateway = gateway, automaticDelayMs = 0L)
        val updateId = "a".repeat(64)

        scheduler.scheduleManualCheck()
        scheduler.scheduleDownload(updateId)
        scheduler.cancelDownload()

        assertEquals(
            listOf(
                UpdateWorkCommand.ManualCheck,
                UpdateWorkCommand.Download(updateId),
                UpdateWorkCommand.CancelDownload,
            ),
            gateway.commands,
        )
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

private class FakeGateway : UpdateWorkGateway {
    val commands = mutableListOf<UpdateWorkCommand>()

    override fun enqueue(command: UpdateWorkCommand) {
        commands += command
    }
}
