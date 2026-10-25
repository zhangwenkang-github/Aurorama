package com.zhangwenkang.cinefin.utils

import java.util.concurrent.atomic.AtomicBoolean
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
}
