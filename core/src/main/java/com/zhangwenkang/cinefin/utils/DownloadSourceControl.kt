package com.zhangwenkang.cinefin.utils

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class DownloadSourceCommandResult<T>(
    val value: T,
    val nextGeneration: Long? = null,
)

/**
 * 同一 source 的 claim、生命周期命令与 completion 副作用共享一把锁。
 *
 * DB 条件更新也必须在锁内执行。这样“DB 已 claim / transition，但内存尚未登记”的窗口不存在；迟到的旧 generation 只能看到新状态并被拒绝。命令释放 source
 * 锁后才能取消并等待旧 worker，避免取消回调重入同一锁造成死锁。
 */
internal class DownloadSourceControl {
    private val lifecycleLocks = Array(LOCK_STRIPES) { Mutex() }
    private val operationLocks = Array(LOCK_STRIPES) { Mutex() }
    private val generations = ConcurrentHashMap<String, Long>()
    private val activeClaims = ConcurrentHashMap<String, Long>()
    private val fences = ConcurrentHashMap<String, Long>()

    /**
     * 同一 source 的入队与完整删除流程共享操作边界。
     *
     * 与细粒度 lifecycle 锁分离，避免删除在命令锁释放后取消旧 worker 的窗口被 enqueue 穿入；调用方可取消，不设置不可中断等待。
     */
    suspend fun <T> withSourceOperation(
        sourceId: String,
        block: suspend () -> T,
    ): T = operationLock(sourceId).withLock { block() }

    /**
     * 在 source 临界区内执行 DB 条件 claim；只有 DB 成功后才登记当前 worker claim。
     *
     * 已有活动 claim 或已知代次不匹配时不再调用 DB，因而同 source 不会被重复领取。
     */
    suspend fun claim(
        sourceId: String,
        generation: Long,
        claim: suspend () -> Boolean,
    ): Boolean =
        lifecycleLock(sourceId).withLock {
            if (activeClaims.containsKey(sourceId)) return@withLock false
            val knownGeneration = generations[sourceId]
            if (knownGeneration != null && knownGeneration != generation) {
                return@withLock false
            }
            if (!claim()) return@withLock false
            generations[sourceId] = generation
            activeClaims[sourceId] = generation
            true
        }

    /**
     * 执行用户 / 调度生命周期命令。
     *
     * [expectedGeneration] 来自打开操作时的任务快照，而不是刚读到的当前行；DB CAS 与内存代次推进都在锁内完成。
     */
    suspend fun <T> withCommand(
        sourceId: String,
        expectedGeneration: Long,
        block: suspend () -> DownloadSourceCommandResult<T>,
    ): T? =
        lifecycleLock(sourceId).withLock command@{
            val knownGeneration = generations[sourceId]
            val activeClaim = activeClaims[sourceId]
            if (
                (knownGeneration != null && knownGeneration != expectedGeneration) ||
                    (activeClaim != null && activeClaim != expectedGeneration)
            ) {
                return@command null
            }
            fences[sourceId] = expectedGeneration
            try {
                val result = block()
                result.nextGeneration?.let { nextGeneration ->
                    generations[sourceId] = nextGeneration
                    activeClaims.remove(sourceId, expectedGeneration)
                }
                result.value
            } finally {
                fences.remove(sourceId, expectedGeneration)
            }
        }

    /** worker 副作用只可在当前 active claim 且未 fence 时执行；调用期间持有 source 锁，禁止命令插入。 */
    suspend fun <T> withCurrentClaim(
        sourceId: String,
        generation: Long,
        block: suspend () -> T,
    ): T? =
        lifecycleLock(sourceId).withLock {
            if (
                activeClaims[sourceId] != generation ||
                    generations[sourceId] != generation ||
                    fences[sourceId] == generation
            ) {
                null
            } else {
                block()
            }
        }

    /** 命令落库后的收尾副作用；若后续命令已推进代次则跳过。 */
    suspend fun <T> withCurrentGeneration(
        sourceId: String,
        generation: Long,
        block: suspend () -> T,
    ): T? =
        lifecycleLock(sourceId).withLock {
            if (generations[sourceId] == generation) block() else null
        }

    suspend fun releaseClaim(sourceId: String, generation: Long) {
        withContext(NonCancellable) {
            lifecycleLock(sourceId).withLock { activeClaims.remove(sourceId, generation) }
        }
    }

    suspend fun forget(sourceId: String) {
        lifecycleLock(sourceId).withLock {
            generations.remove(sourceId)
            activeClaims.remove(sourceId)
            fences.remove(sourceId)
        }
    }

    private fun lifecycleLock(sourceId: String): Mutex = lifecycleLocks[lockIndex(sourceId)]

    private fun operationLock(sourceId: String): Mutex = operationLocks[lockIndex(sourceId)]

    private fun lockIndex(sourceId: String): Int =
        (sourceId.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES

    private companion object {
        const val LOCK_STRIPES = 64
    }
}
