package com.zhangwenkang.cinefin.utils

import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadLifecycleTest {

    @Test
    fun `删除栅栏保留在活动组但封锁暂停继续重试与自动领取`() {
        assertTrue(DownloadTaskRules.isActiveQueueStatus(DownloadTaskStatus.DELETING))
        assertEquals(
            DownloadTaskGroup.ACTIVE,
            DownloadTaskRules.groupKey(DownloadTaskStatus.DELETING),
        )
        assertFalse(DownloadTaskRules.canPause(DownloadTaskStatus.DELETING))
        assertFalse(DownloadTaskRules.canResume(DownloadTaskStatus.DELETING))
        assertFalse(DownloadTaskRules.canRetry(DownloadTaskStatus.DELETING))
        assertFalse(
            DownloadTaskRules.requiresRedownloadAfterEngineUpgrade(
                engineVersion = 0,
                downloadId = 1L,
                status = DownloadTaskStatus.DELETING,
                pathIsPartial = true,
            )
        )
        assertFalse(
            DownloadTaskRules.isAutoRetryEligible(
                DownloadTaskStatus.DELETING,
                DownloadFailureReason.NETWORK_UNAVAILABLE,
            )
        )
    }

    @Test
    fun `claim 后 activeJobs 登记前删除时迟到完成被持久代次拒绝`() {
        val claim = DownloadLifecycleRules.claim(sourceId = "source-a", runningGeneration = 12L)
        val deleting =
            DownloadLifecycleRules.beginDeletion(
                DownloadLifecycleState(
                    status = DownloadTaskStatus.RUNNING,
                    generation = claim.generation,
                )
            )
        val current =
            DownloadLifecycleState(
                status = deleting.status,
                generation = deleting.generation,
            )

        assertFalse(
            DownloadLifecycleRules.accepts(
                claim = claim,
                current = current,
                transition = DownloadLifecycleTransition.COMPLETION,
            )
        )
        assertFalse(
            DownloadLifecycleRules.accepts(
                claim = claim,
                current = current,
                transition = DownloadLifecycleTransition.PROGRESS,
            )
        )
        assertEquals(13L, deleting.generation)
    }

    @Test
    fun `同代 RUNNING 的正常完成仍被接受`() {
        val claim = DownloadLifecycleRules.claim("source-ok", runningGeneration = 5L)
        val current =
            DownloadLifecycleState(
                status = DownloadTaskStatus.RUNNING,
                generation = 5L,
            )

        assertTrue(
            DownloadLifecycleRules.accepts(
                claim,
                current,
                DownloadLifecycleTransition.COMPLETION,
            )
        )
    }

    @Test
    fun `取消后旧 worker 的进度失败与完成均不能越过新代`() {
        val oldClaim = DownloadLifecycleRules.claim("source-b", runningGeneration = 3L)
        val cancelled =
            DownloadLifecycleRules.beginCancellation(
                DownloadLifecycleState(
                    status = DownloadTaskStatus.RUNNING,
                    generation = oldClaim.generation,
                )
            )
        val current =
            DownloadLifecycleState(
                status = cancelled.status,
                generation = cancelled.generation,
            )

        DownloadLifecycleTransition.entries.forEach { transition ->
            assertFalse(DownloadLifecycleRules.accepts(oldClaim, current, transition))
        }
        assertEquals(DownloadTaskStatus.FAILED, cancelled.status)
        assertEquals(4L, cancelled.generation)
    }

    @Test
    fun `取消后的重试回到等待调度并推进 generation`() {
        val cancelled =
            DownloadLifecycleState(
                status = DownloadTaskStatus.FAILED,
                generation = 4L,
            )

        val retried = DownloadLifecycleRules.beginRetry(cancelled)

        assertEquals(DownloadTaskStatus.PENDING, retried.status)
        assertEquals(5L, retried.generation)
    }

    @Test
    fun `resume 后旧 PAUSED claim 的进度失败完成均被拒绝`() {
        val oldClaim = DownloadLifecycleRules.claim("source-resume", runningGeneration = 8L)
        val paused =
            DownloadLifecycleState(
                status = DownloadTaskStatus.PAUSED,
                generation = oldClaim.generation,
            )

        val resumed = requireNotNull(DownloadLifecycleRules.beginResume(paused))

        assertEquals(DownloadTaskStatus.PENDING, resumed.status)
        assertEquals(9L, resumed.generation)
        listOf(
                DownloadLifecycleTransition.PROGRESS,
                DownloadLifecycleTransition.FAILURE,
                DownloadLifecycleTransition.COMPLETION,
            )
            .forEach { transition ->
                assertFalse(
                    "恢复推进代次后旧 PAUSED claim 仍可写 $transition",
                    DownloadLifecycleRules.accepts(oldClaim, resumed, transition),
                )
            }
    }

    @Test
    fun `重复 resume 在 PENDING 时复用 handle 且其他状态拒绝`() {
        val paused =
            DownloadLifecycleState(
                status = DownloadTaskStatus.PAUSED,
                generation = 8L,
            )
        val resumed = requireNotNull(DownloadLifecycleRules.beginResume(paused))

        assertEquals(
            DownloadResumeDecision.REUSE,
            DownloadLifecycleRules.resumeDecision(resumed.status),
        )
        assertEquals(
            DownloadResumeDecision.REJECT,
            DownloadLifecycleRules.resumeDecision(DownloadTaskStatus.FAILED),
        )
        assertEquals(
            DownloadResumeDecision.RESUME,
            DownloadLifecycleRules.resumeDecision(DownloadTaskStatus.PAUSED),
        )
    }

    @Test
    fun `rename 后删除抢先落栅栏时最终文件被撤回`() = runBlocking {
        val directory = Files.createTempDirectory("download-complete-fence").toFile()
        val partial = directory.resolve("media.download").apply { writeText("payload") }
        val finalFile = directory.resolve("media")

        val result =
            DownloadCompletionCommitRules.commit(
                partial = partial,
                finalFile = finalFile,
                canStart = { true },
                commitState = { 0 },
            )

        assertEquals(DownloadCompletionCommit.FENCED, result)
        assertFalse("迟到完成不能留下最终文件", finalFile.exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `旧 completion 在开始校验失败时不得删除共享残片`() = runBlocking {
        val directory = Files.createTempDirectory("download-stale-complete").toFile()
        val partial = directory.resolve("media.download").apply { writeText("payload") }
        val finalFile = directory.resolve("media")

        val result =
            DownloadCompletionCommitRules.commit(
                partial = partial,
                finalFile = finalFile,
                canStart = { false },
                commitState = { error("旧完成不得提交状态") },
            )

        assertEquals(DownloadCompletionCommit.FENCED, result)
        assertTrue("旧完成删除了仍由新代次使用的残片", partial.exists())
        assertFalse(finalFile.exists())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `completion 条件提交失败时 rename 与后续副作用均不发生`() = runBlocking {
        val directory = Files.createTempDirectory("download-completion-cas-lost").toFile()
        val partial = directory.resolve("media.download").apply { writeText("payload") }
        val finalFile = directory.resolve("media")
        val notifications = AtomicInteger()
        val snapshots = AtomicInteger()
        val extras = AtomicInteger()
        val images = AtomicInteger()

        val result =
            DownloadCompletionCommitRules.commit(
                partial = partial,
                finalFile = finalFile,
                canStart = { true },
                commitState = { 0 },
                onCommitted = {
                    notifications.incrementAndGet()
                    snapshots.incrementAndGet()
                    extras.incrementAndGet()
                    images.incrementAndGet()
                },
            )

        assertEquals(DownloadCompletionCommit.FENCED, result)
        assertTrue("删除赢得 CAS 后不得 rename，残片必须原位保留", partial.exists())
        assertFalse(finalFile.exists())
        assertEquals(0, notifications.get())
        assertEquals(0, snapshots.get())
        assertEquals(0, extras.get())
        assertEquals(0, images.get())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `完成状态只能在最终文件已经存在后提交`() = runBlocking {
        val directory = Files.createTempDirectory("download-completion-order").toFile()
        val partial = directory.resolve("media.download").apply { writeText("payload") }
        val finalFile = directory.resolve("media")
        var finalExistedAtCommit = false

        val result =
            DownloadCompletionCommitRules.commit(
                partial = partial,
                finalFile = finalFile,
                canStart = { true },
                commitState = {
                    finalExistedAtCommit = finalFile.isFile
                    1
                },
            )

        assertEquals(DownloadCompletionCommit.COMMITTED, result)
        assertTrue("DB 先于 rename 写成完成，进程中断会留下虚假完成", finalExistedAtCommit)
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `rename 失败不得删除既有同名文件`() = runBlocking {
        val directory = Files.createTempDirectory("download-completion-conflict").toFile()
        val partial = directory.resolve("media.download").apply { writeText("new payload") }
        val finalFile = directory.resolve("media").apply { writeText("existing payload") }

        val result =
            DownloadCompletionCommitRules.commit(
                partial = partial,
                finalFile = finalFile,
                canStart = { true },
                commitState = { 1 },
            )

        assertEquals(DownloadCompletionCommit.RENAME_FAILED, result)
        assertEquals("existing payload", finalFile.readText())
        assertEquals("new payload", partial.readText())
        directory.deleteRecursively()
        Unit
    }

    @Test
    fun `DB 提交中断后可从 witness 与最终文件恢复`() = runBlocking {
        val directory = Files.createTempDirectory("download-completion-recovery").toFile()
        val partial = directory.resolve("media.download").apply { writeText("payload") }
        val finalFile = directory.resolve("media")

        runCatching {
            DownloadCompletionCommitRules.commit(
                partial = partial,
                finalFile = finalFile,
                canStart = { true },
                commitState = { error("模拟进程在 rename 后中断") },
                sourceId = "source-recovery",
                generation = 9L,
            )
        }

        assertTrue(
            DownloadCompletionCommitRules.hasInterruptedFinalization(
                partial = partial,
                finalFile = finalFile,
                sourceId = "source-recovery",
                generation = 9L,
            )
        )
        var committed = 0
        val recovered =
            DownloadCompletionCommitRules.commit(
                partial = partial,
                finalFile = finalFile,
                canStart = { true },
                commitState = {
                    assertEquals("payload", finalFile.readText())
                    committed++
                    1
                },
                sourceId = "source-recovery",
                generation = 9L,
            )

        assertEquals(DownloadCompletionCommit.COMMITTED, recovered)
        assertEquals(1, committed)
        assertFalse(DownloadCompletionCommitRules.witnessFile(partial, finalFile).exists())
        directory.deleteRecursively()
        Unit
    }
}
