package com.zhangwenkang.cinefin.utils

import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentTimeoutTest {
    @Test
    fun `one item deadline fails only that item and later work continues`() = runBlocking {
        val scheduler = ManualDownloadIntentDeadlineScheduler()
        val coordinator =
            DownloadIntentResolutionCoordinator(
                maxConcurrency = 1,
                timeoutMs = 15_000,
                deadlineScheduler = scheduler,
            )
        val blocked = DownloadIntentResolutionKey("music:slow", generation = 3L)
        val releaseNever = CompletableDeferred<Unit>()
        val completed = ConcurrentLinkedQueue<Int>()

        val outcomes = async {
            coordinator.run(
                items = listOf(1, 2),
                keyOf = { item ->
                    if (item == 1) blocked
                    else DownloadIntentResolutionKey("music:fast", generation = 3L)
                },
                begin = { true },
                resolve = { item ->
                    if (item == 1) releaseNever.await()
                    item
                },
                onResult = { item, outcome ->
                    completed += item
                    if (outcome is DownloadIntentResolutionOutcome.TimedOut) {
                        assertEquals(1, item)
                    }
                },
            )
        }

        withTimeout(2_000) { scheduler.awaitArmed(blocked) }
        scheduler.advanceTimeBy(14_999)
        assertTrue("14.999s 不得提前判超时", completed.isEmpty())
        scheduler.advanceTimeBy(1)

        val results = outcomes.await()
        assertEquals(
            listOf(
                DownloadIntentResolutionOutcome.TimedOut,
                DownloadIntentResolutionOutcome.Resolved(2),
            ),
            results,
        )
        assertEquals(listOf(1, 2), completed.toList())
    }
}

internal class ManualDownloadIntentDeadlineScheduler : DownloadIntentDeadlineScheduler {
    private data class Deadline(
        val expiresAt: Long,
        val expired: CompletableDeferred<Unit>,
    )

    private val deadlines = ConcurrentLinkedQueue<Pair<DownloadIntentResolutionKey, Deadline>>()
    private val armed = ConcurrentLinkedQueue<DownloadIntentResolutionKey>()
    private var now = 0L

    override suspend fun <T> withDeadline(
        key: DownloadIntentResolutionKey,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T = coroutineScope {
        val expired = CompletableDeferred<Unit>()
        deadlines += key to Deadline(now + timeoutMs, expired)
        armed += key
        val result = async { block() }
        try {
            select {
                result.onAwait { it }
                expired.onAwait { throw DownloadIntentDeadlineExceededException(key) }
            }
        } finally {
            result.cancel()
            deadlines.removeIf { it.first == key && it.second.expired === expired }
        }
    }

    fun advanceTimeBy(deltaMs: Long) {
        now += deltaMs
        deadlines.filter { it.second.expiresAt <= now }.forEach { it.second.expired.complete(Unit) }
    }

    fun timeOut(key: DownloadIntentResolutionKey) {
        deadlines.filter { it.first == key }.forEach { it.second.expired.complete(Unit) }
    }

    suspend fun awaitArmed(key: DownloadIntentResolutionKey) {
        while (key !in armed) yield()
    }
}
