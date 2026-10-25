package com.zhangwenkang.cinefin.utils

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePumpTest {

    @Test
    fun `resume 在旧批次仍运行时立即领取空槽`() = runBlocking {
        val pump = DownloadQueuePump()
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val resumed = AtomicBoolean(false)
        var aClaimed = false
        var bClaimed = false

        val run = launch {
            pump.run(
                maxConcurrentTasks = { 2 },
                claim = { slots ->
                    buildList {
                        repeat(slots) {
                            when {
                                !aClaimed -> {
                                    aClaimed = true
                                    add("A")
                                }
                                resumed.get() && !bClaimed -> {
                                    bClaimed = true
                                    add("B")
                                }
                            }
                        }
                    }
                },
                key = { it },
                execute = { task ->
                    when (task) {
                        "A" -> {
                            aStarted.complete(Unit)
                            releaseA.await()
                        }
                        "B" -> bStarted.complete(Unit)
                    }
                },
            )
        }

        val aDidStart = withTimeoutOrNull(1_000L) { aStarted.await() } != null
        val bWasClaimed =
            if (aDidStart) {
                resumed.set(true)
                pump.kick()
                withTimeoutOrNull(1_000L) { bStarted.await() } != null
            } else {
                false
            }
        releaseA.complete(Unit)
        run.join()

        assertTrue(
            "旧任务 A 未启动，或 PAUSED 恢复后未使用空槽立即领取 B",
            aDidStart && bWasClaimed,
        )
    }

    @Test
    fun `同 source 重复 claim 只执行一次`() = runBlocking {
        val pump = DownloadQueuePump()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val executions = AtomicInteger()
        var firstClaim = true

        val run = launch {
            pump.run(
                maxConcurrentTasks = { 2 },
                claim = {
                    if (firstClaim) {
                        firstClaim = false
                        listOf("A", "A")
                    } else {
                        emptyList()
                    }
                },
                key = { it },
                execute = {
                    executions.incrementAndGet()
                    started.complete(Unit)
                    release.await()
                },
            )
        }

        assertTrue(withTimeoutOrNull(1_000L) { started.await() } != null)
        release.complete(Unit)
        run.join()

        assertTrue("重复 claim 被执行了多次", executions.get() == 1)
    }
}
