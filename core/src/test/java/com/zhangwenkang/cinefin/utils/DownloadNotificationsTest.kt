package com.zhangwenkang.cinefin.utils

import android.content.Intent
import com.zhangwenkang.cinefin.core.R as CoreR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Task 9 Wave 2：失败通知正文与待处理目标契约。
 *
 * 本机 JVM 没有 Robolectric，不能用 android.jar 伪造系统通知；这里只验证生产代码中可执行的纯内容 / 目标 规则。真实 PendingIntent 投递仍由独立 QA
 * 在设备上覆盖。
 */
class DownloadNotificationsTest {

    @Test
    fun `failure detail is a safe bounded single line`() {
        val raw =
            "  DNS\r\nfailed\tat https://alice:password@example.com:8443/media/file?token=secret#fragment  "

        val actual = sanitizeDownloadFailureDetail(raw)

        assertEquals("DNS failed at https://example.com:8443/media/file", actual)
    }

    @Test
    fun `failure detail is truncated after url and control cleanup`() {
        val raw = buildString {
            append("head\n")
            repeat(300) { append("x") }
            append(" https://user:secret@example.com/path?query=secret#fragment")
        }

        val actual = sanitizeDownloadFailureDetail(raw)

        assertTrue(actual.length <= 180)
        assertFalse(actual.contains('\n'))
        assertFalse(actual.contains('\r'))
        assertFalse(actual.contains("user:secret"))
        assertFalse(actual.contains("query=secret"))
        assertFalse(actual.contains("fragment"))
    }

    @Test
    fun `failure content includes name localized reason and sanitized detail`() {
        val content =
            failureNotificationContent(
                taskName = "海边的电影",
                localizedReason = "网络错误",
                detail = "GET https://user:secret@example.com/a?token=secret failed\nretry later",
            )

        assertEquals("海边的电影 · 网络错误", content.text)
        assertEquals(
            "海边的电影\n网络错误\nGET https://example.com/a failed retry later",
            content.expandedText,
        )
    }

    @Test
    fun `failure content falls back when reason and detail are absent`() {
        val content =
            failureNotificationContent(
                taskName = "海边的电影",
                localizedReason = "未知错误",
                detail = null,
            )

        assertEquals("海边的电影 · 未知错误", content.text)
        assertEquals("海边的电影\n未知错误", content.expandedText)
    }

    @Test
    fun `missing reason maps to localized unknown label`() {
        assertEquals(
            CoreR.string.download_failure_unknown,
            downloadFailureReasonLabelRes(null),
        )
    }

    @Test
    fun `home active and failed targets have isolated pending identities`() {
        val targets =
            DownloadNotificationDestination.entries.associate { destination ->
                destination.name to downloadNotificationTarget(destination)
            }

        val home = targets.getValue("HOME")
        val active = targets.getValue("ACTIVE")
        val failed = targets.getValue("FAILED")

        assertNotEquals(home.requestCode, active.requestCode)
        assertNotEquals(active.requestCode, failed.requestCode)
        assertNotEquals(home.requestCode, failed.requestCode)

        assertFalse(home.openDownloads)
        assertEquals(null, home.tab)

        assertTrue(active.openDownloads)
        assertEquals("ACTIVE", active.tab)

        assertTrue(failed.openDownloads)
        assertEquals("FAILED", failed.tab)
    }

    @Test
    fun `launch flags keep single top clear top and new task`() {
        val flags = downloadNotificationLaunchFlags

        assertTrue(flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
        assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
    }
}
