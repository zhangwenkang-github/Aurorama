package com.zhangwenkang.cinefin.utils

import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
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
    private val running = ConcurrentHashMap<String, Job>()

    fun kick() {
        kickSignal.trySend(Unit)
    }

    suspend fun <T> run(
        maxConcurrentTasks: () -> Int,
        claim: suspend (Int) -> List<T>,
        key: (T) -> String,
        execute: suspend (T) -> Unit,
    ): Int = supervisorScope {
        var ran = 0
        while (coroutineContext.isActive) {
            val claimed = claimMutex.withLock {
                val slots = (maxConcurrentTasks() - running.size).coerceAtLeast(0)
                if (slots == 0) emptyList() else claim(slots)
            }
            claimed.forEach { task ->
                val taskKey = key(task)
                lateinit var job: Job
                job =
                    launch(start = CoroutineStart.LAZY) {
                        try {
                            execute(task)
                        } finally {
                            running.remove(taskKey, job)
                            kick()
                        }
                    }
                if (running.putIfAbsent(taskKey, job) == null) {
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
}
