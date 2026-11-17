package com.zhangwenkang.cinefin.utils

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadActionReceiverContractTest {

    @Test
    fun `canonical actions map every source to the matching downloader operation`() = runBlocking {
        val calls = mutableListOf<String>()
        val expected =
            listOf(
                DownloadNotifications.ACTION_PAUSE to "pause:a",
                DownloadNotifications.ACTION_RESUME to "resume:a",
                DownloadNotifications.ACTION_DELETE to "delete:a",
                DownloadNotifications.ACTION_CANCEL to "cancel:a",
            )
        expected.forEach { (action, expectedCall) ->
            calls.clear()
            val outcomes =
                executeDownloadNotificationAction(
                    action = action,
                    sourceIds = listOf("a"),
                    pause = { sourceId ->
                        calls += "pause:$sourceId"
                        true
                    },
                    resume = { sourceId ->
                        calls += "resume:$sourceId"
                        true
                    },
                    delete = { sourceId ->
                        calls += "delete:$sourceId"
                        true
                    },
                    cancel = { sourceId ->
                        calls += "cancel:$sourceId"
                        true
                    },
                )

            assertEquals(listOf(expectedCall), calls)
            assertTrue(outcomes.single().getOrThrow())
        }
    }

    @Test
    fun `one failing source does not block later source operations`() = runBlocking {
        val calls = mutableListOf<String>()
        val injected = IllegalStateException("source-a failed")

        val outcomes =
            executeDownloadNotificationAction(
                action = DownloadNotifications.ACTION_DELETE,
                sourceIds = listOf("a", "b", "c"),
                pause = { true },
                resume = { true },
                delete = { sourceId ->
                    calls += sourceId
                    if (sourceId == "a") throw injected else true
                },
                cancel = { true },
            )

        assertEquals(listOf("a", "b", "c"), calls)
        assertSame(injected, outcomes[0].exceptionOrNull())
        assertTrue(outcomes[1].getOrThrow())
        assertTrue(outcomes[2].getOrThrow())
    }
}
