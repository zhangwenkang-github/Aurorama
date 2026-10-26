package com.zhangwenkang.cinefin.presentation.film.downloads

import com.zhangwenkang.cinefin.utils.DownloadFailureReason
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W60b 下载页「重试中 · 第 N 次」文案触发条件（纯函数）单测。 */
class DownloadRetryLabelTest {

    @Test
    fun `waiting retry shows retry label`() {
        assertTrue(showsRetryLabel(task(DownloadTaskStatus.PENDING, retryCount = 2)))
    }

    @Test
    fun `running after retry shows retry label`() {
        assertTrue(showsRetryLabel(task(DownloadTaskStatus.RUNNING, retryCount = 1)))
    }

    @Test
    fun `first attempt without retries keeps normal label`() {
        assertFalse(showsRetryLabel(task(DownloadTaskStatus.RUNNING, retryCount = 0)))
        assertFalse(showsRetryLabel(task(DownloadTaskStatus.PENDING, retryCount = 0)))
    }

    @Test
    fun `paused and failed keep their own labels`() {
        assertFalse(showsRetryLabel(task(DownloadTaskStatus.PAUSED, retryCount = 3)))
        assertFalse(showsRetryLabel(task(DownloadTaskStatus.FAILED, retryCount = 3)))
        assertFalse(showsRetryLabel(null))
    }

    @Test
    fun `completed entry always uses success label before failure fallback`() {
        assertTrue(showsCompletedDownloadLabel(DownloadTaskStatus.COMPLETED))
        assertFalse(showsCompletedDownloadLabel(DownloadTaskStatus.FAILED))
        assertFalse(showsCompletedDownloadLabel(DownloadTaskStatus.RUNNING))
    }

    @Test
    fun `legacy provenance detail is visible for failed music task`() {
        assertEquals(
            LEGACY_PROVENANCE_UNAVAILABLE,
            visibleFailureDetail(
                task(
                    status = DownloadTaskStatus.FAILED,
                    retryCount = 0,
                    failureReason = DownloadFailureReason.CANCELLED,
                    failureDetail = LEGACY_PROVENANCE_UNAVAILABLE,
                ),
                bookDetail = null,
            ),
        )
    }

    @Test
    fun `cancelled task without detail keeps generic fallback`() {
        assertNull(
            visibleFailureDetail(
                task(
                    status = DownloadTaskStatus.FAILED,
                    retryCount = 0,
                    failureReason = DownloadFailureReason.CANCELLED,
                ),
                bookDetail = null,
            )
        )
    }

    private fun task(
        status: DownloadTaskStatus,
        retryCount: Int,
        failureReason: DownloadFailureReason? = null,
        failureDetail: String? = null,
    ) =
        DownloadTask(
            itemId = UUID.randomUUID(),
            sourceId = "source",
            name = "测试条目",
            path = "/tmp/test.download",
            downloadId = null,
            status = status,
            failureReason = failureReason,
            failureDetail = failureDetail,
            downloadedBytes = 0L,
            totalBytes = 0L,
            updatedAt = 0L,
            retryCount = retryCount,
        )

    private companion object {
        const val LEGACY_PROVENANCE_UNAVAILABLE = "下载来源不可证明，请重新下载"
    }
}
