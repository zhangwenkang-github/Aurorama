package com.zhangwenkang.cinefin.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadIntentBatchProgressTest {
    @Test
    fun `stalled second resolver does not block peers or the seventh item`() = runBlocking {
        val coordinator =
            DownloadIntentResolutionCoordinator(
                maxConcurrency = 6,
                deadlineScheduler = ImmediateDownloadIntentDeadlineScheduler,
            )
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val started = ConcurrentHashMap.newKeySet<Int>()
        val completed = ConcurrentHashMap.newKeySet<Int>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val outcomes = async {
            coordinator.run(
                items = (1..7).toList(),
                keyOf = { item ->
                    DownloadIntentResolutionKey(
                        logicalKey = "music:item-$item",
                        generation = 4L,
                    )
                },
                begin = { true },
                resolve = { item ->
                    started += item
                    val currentActive = active.incrementAndGet()
                    maxActive.getAndUpdate { previous -> maxOf(previous, currentActive) }
                    try {
                        if (item == 2) {
                            secondStarted.complete(Unit)
                            releaseSecond.await()
                        } else {
                            delay(10)
                        }
                        item
                    } finally {
                        active.decrementAndGet()
                    }
                },
                onResult = { item, outcome ->
                    if (outcome is DownloadIntentResolutionOutcome.Resolved) {
                        completed += item
                    }
                },
            )
        }

        withTimeout(2_000) { secondStarted.await() }
        withTimeout(2_000) { while (!started.contains(7) || completed.size < 5) yield() }

        assertTrue("第 1 项必须越过挂起的第 2 项完成", completed.contains(1))
        assertTrue("第 3 项必须越过挂起的第 2 项完成", completed.contains(3))
        assertTrue("首个空槽必须立即推进第 7 项", started.contains(7))
        assertEquals("任何时刻只能有 6 个 resolver 在飞", 6, maxActive.get())

        releaseSecond.complete(Unit)
        assertEquals(7, outcomes.await().count { it is DownloadIntentResolutionOutcome.Resolved })
    }

    @Test
    fun `same logical key and generation has only one active attempt`() = runBlocking {
        val coordinator =
            DownloadIntentResolutionCoordinator(
                maxConcurrency = 6,
                deadlineScheduler = ImmediateDownloadIntentDeadlineScheduler,
            )
        val key = DownloadIntentResolutionKey("music:same-song", generation = 9L)
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var resolverCalls = 0
        var beginCalls = 0

        val outcomes = async {
            coordinator.run(
                items = listOf("first"),
                keyOf = { key },
                begin = {
                    beginCalls += 1
                    true
                },
                resolve = {
                    resolverCalls += 1
                    firstStarted.complete(Unit)
                    releaseFirst.await()
                    it
                },
                onResult = { _, _ -> },
            )
        }

        withTimeout(2_000) { firstStarted.await() }
        val duplicateOutcomes =
            coordinator.run(
                items = listOf("duplicate"),
                keyOf = { key },
                begin = {
                    beginCalls += 1
                    true
                },
                resolve = {
                    resolverCalls += 1
                    it
                },
                onResult = { _, _ -> },
            )
        releaseFirst.complete(Unit)

        assertEquals(1, resolverCalls)
        assertEquals(1, beginCalls)
        assertEquals(
            1,
            duplicateOutcomes.count { it is DownloadIntentResolutionOutcome.Duplicate },
        )
        assertEquals(1, outcomes.await().count { it is DownloadIntentResolutionOutcome.Resolved })
    }

    @Test
    fun `processor pass rethrows cancellation instead of treating it as retryable failure`() =
        runBlocking {
            val captured = runCatching {
                runIntentProcessorPass(block = { throw CancellationException("shutdown") })
            }
                .exceptionOrNull()

            assertTrue(
                "CancellationException 必须穿透 processor pass，实际=$captured",
                captured is CancellationException,
            )
        }
}

internal object ImmediateDownloadIntentDeadlineScheduler : DownloadIntentDeadlineScheduler {
    override suspend fun <T> withDeadline(
        key: DownloadIntentResolutionKey,
        timeoutMs: Long,
        block: suspend () -> T,
    ): T = block()
}
