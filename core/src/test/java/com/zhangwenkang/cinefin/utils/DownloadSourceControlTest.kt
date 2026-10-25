package com.zhangwenkang.cinefin.utils

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadSourceControlTest {

    @Test
    fun `旧 progress 在 resume 后不得更新 runtime`() = runBlocking {
        val control = DownloadSourceControl()
        control.activateClaim("source", generation = 8L)
        var runtime = 0

        assertTrue(control.withCurrentClaim("source", generation = 8L) { runtime++ } != null)
        control.advanceLifecycle("source", generation = 9L)

        assertEquals(null, control.withCurrentClaim("source", generation = 8L) { runtime++ })
        assertEquals(1, runtime)
    }

    @Test
    fun `旧 failure 在 resume 后不得删除共享残片`() = runBlocking {
        val control = DownloadSourceControl()
        control.activateClaim("source", generation = 8L)
        val directory = Files.createTempDirectory("download-stale-failure").toFile()
        val partial = directory.resolve("media.download").apply { writeText("payload") }

        control.advanceLifecycle("source", generation = 9L)

        assertEquals(
            null,
            control.withCurrentClaim("source", generation = 8L) { partial.delete() },
        )
        assertTrue(partial.exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `旧 completion 在 resume 后不得提交或通知`() = runBlocking {
        val control = DownloadSourceControl()
        control.activateClaim("source", generation = 8L)
        val commits = AtomicInteger()
        val notifications = AtomicInteger()

        control.advanceLifecycle("source", generation = 9L)

        assertEquals(
            null,
            control.withCurrentClaim("source", generation = 8L) {
                commits.incrementAndGet()
                notifications.incrementAndGet()
            },
        )
        assertEquals(0, commits.get())
        assertEquals(0, notifications.get())
    }

    @Test
    fun `fence 只能由匹配代次清除`() {
        val control = DownloadSourceControl()

        control.fence("source", generation = 8L)
        control.clearFence("source", generation = 9L)
        assertTrue(control.isFenced("source", generation = 8L))

        control.clearFence("source", generation = 8L)
        assertFalse(control.isFenced("source", generation = 8L))
    }

    @Test
    fun `同 source 生命周期命令串行执行`() = runBlocking {
        val control = DownloadSourceControl()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()

        launch {
            control.withCommand("source") {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        launch { control.withCommand("source") { secondEntered.complete(Unit) } }

        val overlapped = withTimeoutOrNull(200L) { secondEntered.await() } != null
        releaseFirst.complete(Unit)
        withTimeoutOrNull(1_000L) { secondEntered.await() }

        assertFalse("同 source 生命周期命令发生交叠", overlapped)
    }
}
