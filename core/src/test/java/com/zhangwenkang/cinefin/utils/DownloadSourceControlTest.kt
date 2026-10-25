package com.zhangwenkang.cinefin.utils

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceControlTest {

    @Test
    fun `旧 progress 在 resume 后不得更新 runtime`() = runBlocking {
        val control = DownloadSourceControl()
        assertTrue(control.claim("source", generation = 8L) { true })
        var runtime = 0

        assertTrue(control.withCurrentClaim("source", generation = 8L) { runtime++ } != null)
        control.withCommand("source", expectedGeneration = 8L) {
            DownloadSourceCommandResult(Unit, nextGeneration = 9L)
        }

        assertNull(control.withCurrentClaim("source", generation = 8L) { runtime++ })
        assertEquals(1, runtime)
    }

    @Test
    fun `旧 failure 在 resume 后不得删除共享残片`() = runBlocking {
        val control = DownloadSourceControl()
        assertTrue(control.claim("source", generation = 8L) { true })
        val directory = Files.createTempDirectory("download-stale-failure").toFile()
        val partial = directory.resolve("media.download").apply { writeText("payload") }

        control.withCommand("source", expectedGeneration = 8L) {
            DownloadSourceCommandResult(Unit, nextGeneration = 9L)
        }

        assertNull(control.withCurrentClaim("source", generation = 8L) { partial.delete() })
        assertTrue(partial.exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `旧 completion 在 resume 后不得执行任一完成副作用`() = runBlocking {
        val control = DownloadSourceControl()
        assertTrue(control.claim("source", generation = 8L) { true })
        val directory = Files.createTempDirectory("download-stale-effects").toFile()
        val partial = directory.resolve("media.download").apply { writeText("payload") }
        val finalFile = directory.resolve("media")
        val effects = List(6) { AtomicInteger() }

        control.withCommand("source", expectedGeneration = 8L) {
            DownloadSourceCommandResult(Unit, nextGeneration = 9L)
        }

        assertNull(
            control.withCurrentClaim("source", generation = 8L) {
                DownloadCompletionCommitRules.commit(
                    partial = partial,
                    finalFile = finalFile,
                    canStart = { true },
                    commitState = { 1 },
                    onCommitted = { effects.forEach(AtomicInteger::incrementAndGet) },
                )
            }
        )
        assertTrue(partial.exists())
        assertFalse(finalFile.exists())
        effects.forEach { assertEquals(0, it.get()) }
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `迟到的旧 claim 不得覆盖 PAUSED 或 DELETING 的新代次`() = runBlocking {
        listOf("PAUSED", "DELETING").forEach { state ->
            val control = DownloadSourceControl()
            assertTrue(control.claim("source", generation = 4L) { true })
            control.withCommand("source", expectedGeneration = 4L) {
                DownloadSourceCommandResult(Unit, nextGeneration = 6L)
            }
            val databaseClaimed = AtomicBoolean()

            assertFalse(
                "$state 新代次被旧 claim 覆盖",
                control.claim("source", generation = 4L) {
                    databaseClaimed.set(true)
                    true
                },
            )
            assertFalse("旧 claim 仍调用了 DB", databaseClaimed.get())
            assertTrue(control.claim("source", generation = 6L) { true })
        }
    }

    @Test
    fun `stale pause retry cancel delete 均在 DB 前被拒绝`() = runBlocking {
        listOf("pause", "retry", "cancel", "delete").forEach { command ->
            val control = DownloadSourceControl()
            assertTrue(control.claim("source", generation = 4L) { true })
            control.withCommand("source", expectedGeneration = 4L) {
                DownloadSourceCommandResult(Unit, nextGeneration = 6L)
            }
            val databaseTransitioned = AtomicBoolean()

            assertNull(
                control.withCommand("source", expectedGeneration = 4L) {
                    databaseTransitioned.set(true)
                    DownloadSourceCommandResult(Unit, nextGeneration = 7L)
                }
            )
            assertFalse("$command stale 命令仍执行了 DB CAS", databaseTransitioned.get())
        }
    }

    @Test
    fun `同 source 生命周期命令串行执行`() = runBlocking {
        val control = DownloadSourceControl()
        assertTrue(control.claim("source", generation = 8L) { true })
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        launch {
            control.withCommand("source", expectedGeneration = 8L) {
                firstEntered.complete(Unit)
                releaseFirst.await()
                DownloadSourceCommandResult(Unit)
            }
        }
        firstEntered.await()
        launch {
            control.withCommand("source", expectedGeneration = 8L) {
                secondEntered.complete(Unit)
                DownloadSourceCommandResult(Unit)
            }
        }

        val overlapped = withTimeoutOrNull(200L) { secondEntered.await() } != null
        releaseFirst.complete(Unit)
        withTimeoutOrNull(1_000L) { secondEntered.await() }

        assertFalse("同 source 生命周期命令发生交叠", overlapped)
    }

    @Test
    fun `completion 副作用与删除共享临界区`() = runBlocking {
        val control = DownloadSourceControl()
        assertTrue(control.claim("source", generation = 8L) { true })
        val completionEntered = CompletableDeferred<Unit>()
        val releaseCompletion = CompletableDeferred<Unit>()
        val deleteEntered = CompletableDeferred<Unit>()

        launch {
            control.withCurrentClaim("source", generation = 8L) {
                completionEntered.complete(Unit)
                releaseCompletion.await()
            }
        }
        completionEntered.await()
        launch {
            control.withCommand("source", expectedGeneration = 8L) {
                deleteEntered.complete(Unit)
                DownloadSourceCommandResult(Unit, nextGeneration = 9L)
            }
        }

        val interleaved = withTimeoutOrNull(200L) { deleteEntered.await() } != null
        releaseCompletion.complete(Unit)
        withTimeoutOrNull(1_000L) { deleteEntered.await() }

        assertFalse("删除穿入 completion finalizer", interleaved)
    }

    @Test
    fun `同 source 同代次不会重复 claim`() = runBlocking {
        val control = DownloadSourceControl()
        val secondDatabaseClaim = AtomicBoolean()

        assertTrue(control.claim("source", generation = 6L) { true })
        assertFalse(
            control.claim("source", generation = 6L) {
                secondDatabaseClaim.set(true)
                true
            }
        )

        assertFalse(secondDatabaseClaim.get())
    }

    @Test
    fun `Job 在 source 锁被占用时取消，finally 仍能释放 active claim`() = runBlocking {
        val control = DownloadSourceControl()
        val claimed = CompletableDeferred<Unit>()
        val holderEntered = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        assertTrue(control.claim("source", generation = 6L) { true })
        val holder = launch {
            control.withCurrentClaim("source", generation = 6L) {
                holderEntered.complete(Unit)
                releaseHolder.await()
            }
        }
        holderEntered.await()
        val job = launch {
            try {
                claimed.complete(Unit)
                awaitCancellation()
            } finally {
                control.releaseClaim("source", generation = 6L)
            }
        }
        claimed.await()
        job.cancel()
        releaseHolder.complete(Unit)
        holder.join()
        job.join()

        assertTrue("取消后的 releaseClaim 被跳过，source 永久占用", control.claim("source", 6L) { true })
    }
}
