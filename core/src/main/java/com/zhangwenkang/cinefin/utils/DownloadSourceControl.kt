package com.zhangwenkang.cinefin.utils

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 同一 source 的 claim 副作用与生命周期命令共享围栏。
 *
 * 生命周期命令按 source 串行；worker 回调在 effects 锁内同时校验 claim 代次与 fence，避免“先检查、后落副作用”的竞态。
 */
internal class DownloadSourceControl {
    private val claims = ConcurrentHashMap<String, Long>()
    private val fences = ConcurrentHashMap<String, Long>()
    private val effectLocks = Array(LOCK_STRIPES) { Mutex() }
    private val commandLocks = Array(LOCK_STRIPES) { Mutex() }

    fun activateClaim(sourceId: String, generation: Long) {
        claims[sourceId] = generation
    }

    fun forget(sourceId: String) {
        claims.remove(sourceId)
        fences.remove(sourceId)
    }

    suspend fun advanceLifecycle(sourceId: String, generation: Long) {
        effectLock(sourceId).withLock { claims[sourceId] = generation }
    }

    suspend fun <T> withCurrentClaim(
        sourceId: String,
        generation: Long,
        block: suspend () -> T,
    ): T? =
        effectLock(sourceId).withLock {
            if (claims[sourceId] != generation || fences[sourceId] == generation) {
                null
            } else {
                block()
            }
        }

    suspend fun beginTransition(sourceId: String, expectedGeneration: Long): Boolean =
        effectLock(sourceId).withLock {
            val current = claims[sourceId]
            if (current != null && current != expectedGeneration) {
                false
            } else {
                fences[sourceId] = expectedGeneration
                true
            }
        }

    fun fence(sourceId: String, generation: Long) {
        fences[sourceId] = generation
    }

    fun clearFence(sourceId: String, generation: Long) {
        fences.remove(sourceId, generation)
    }

    fun isFenced(sourceId: String, generation: Long): Boolean = fences[sourceId] == generation

    suspend fun <T> withCommand(sourceId: String, block: suspend () -> T): T =
        commandLock(sourceId).withLock { block() }

    private fun effectLock(sourceId: String): Mutex = effectLocks[lockIndex(sourceId)]

    private fun commandLock(sourceId: String): Mutex = commandLocks[lockIndex(sourceId)]

    private fun lockIndex(sourceId: String): Int =
        (sourceId.hashCode() and Int.MAX_VALUE) % LOCK_STRIPES

    private companion object {
        const val LOCK_STRIPES = 64
    }
}
