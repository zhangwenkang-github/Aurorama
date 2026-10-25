package com.zhangwenkang.cinefin.utils

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 单 worker 内的空槽 claim pump。
 *
 * 每批只为当前空槽领取任务，任务完成或 resume 会唤醒 pump 重新计算空槽；不会等待一整批任务全部结束。
 */
internal class DownloadQueuePump {
    private val claimMutex = Mutex()
    private val kickSignal = Channel<Unit>(capacity = Channel.CONFLATED)
    private val running = ConcurrentHashMap<String, RunningTask>()
    private val startSequence = AtomicLong()

    fun kick() {
        kickSignal.trySend(Unit)
    }

    suspend fun <T> run(
        maxConcurrentTasks: () -> Int,
        claim: suspend (Int) -> List<T>,
        key: (T) -> String,
        execute: suspend (T) -> Unit,
        pause: suspend (String) -> Boolean = { false },
    ): Int = supervisorScope {
        var ran = 0
        while (coroutineContext.isActive) {
            val limit = maxConcurrentTasks().coerceAtLeast(0)
            val excess = (running.size - limit).coerceAtLeast(0)
            if (excess > 0) {
                running.entries
                    .sortedByDescending { it.value.startSequence }
                    .take(excess)
                    .forEach { (taskKey, task) ->
                        val paused = runCatching {
                            pause(taskKey)
                        }
                            .onFailure { error -> if (error is CancellationException) throw error }
                            .getOrDefault(false)
                        if (!paused) {
                            task.job.cancel(CancellationException("下载并发上限收缩"))
                            task.job.join()
                        }
                    }
            }
            val claimed = claimMutex.withLock {
                val slots = (limit - running.size).coerceAtLeast(0)
                if (slots == 0) emptyList() else claim(slots)
            }
            claimed.forEach { task ->
                val taskKey = key(task)
                lateinit var job: Job
                lateinit var runningTask: RunningTask
                job =
                    launch(start = CoroutineStart.LAZY) {
                        try {
                            execute(task)
                        } finally {
                            running.remove(taskKey, runningTask)
                            kick()
                        }
                    }
                runningTask =
                    RunningTask(job = job, startSequence = startSequence.incrementAndGet())
                if (running.putIfAbsent(taskKey, runningTask) == null) {
                    job.start()
                    ran++
                } else {
                    job.cancel()
                }
            }
            if (running.isEmpty()) break
            kickSignal.receive()
        }
        ran
    }

    private data class RunningTask(
        val job: Job,
        val startSequence: Long,
    )
}
