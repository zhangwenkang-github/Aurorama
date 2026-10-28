package com.zhangwenkang.cinefin.presentation.downloads

import com.zhangwenkang.cinefin.DownloadsLaunchRequestState
import com.zhangwenkang.cinefin.DownloadsRoute
import com.zhangwenkang.cinefin.parseDownloadsLaunchTab
import com.zhangwenkang.cinefin.presentation.film.DownloadTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Task 9 Wave 2：通知启动参数的纯解析 / 请求序号 / 目的地契约。
 *
 * Activity `onCreate` / `onNewIntent` 共用 [DownloadsLaunchRequestState.consume]；系统 Intent 与
 * PendingIntent 的真实投递不在 JVM 测试里伪造，留给独立设备 QA。
 */
class DownloadNotificationRouteTest {

    @Test
    fun `ordinary downloads entry defaults to active and does not inherit failed`() {
        val route = DownloadsRoute()

        assertEquals(DownloadTab.ACTIVE, route.initialTab)
        assertEquals(0L, route.requestId)
    }

    @Test
    fun `failed launch route carries tab and unique request id`() {
        val state = DownloadsLaunchRequestState(40L)
        val first = state.consume(openDownloads = true, requestedTab = "FAILED")
        val second = state.consume(openDownloads = true, requestedTab = "FAILED")
        val firstRoute = requireNotNull(first)
        val secondRoute = requireNotNull(second)

        assertEquals(DownloadTab.FAILED, firstRoute.initialTab)
        assertEquals(41L, firstRoute.requestId)
        assertEquals(DownloadTab.FAILED, secondRoute.initialTab)
        assertEquals(42L, secondRoute.requestId)
        assertNotEquals(firstRoute.requestId, secondRoute.requestId)
    }

    @Test
    fun `same failed intent is consumed once`() {
        val state = DownloadsLaunchRequestState(0L)

        val initial = state.consume(openDownloads = true, requestedTab = "FAILED")
        val alreadyConsumed = state.consume(openDownloads = false, requestedTab = null)
        val hot = state.consume(openDownloads = true, requestedTab = "FAILED")

        assertEquals(1L, requireNotNull(initial).requestId)
        assertNull(alreadyConsumed)
        assertEquals(2L, requireNotNull(hot).requestId)
    }

    @Test
    fun `parser treats unknown tab as active and requires open downloads`() {
        val active = parseDownloadsLaunchTab(openDownloads = true, requestedTab = "COMPLETED")
        val missingOpen = parseDownloadsLaunchTab(openDownloads = false, requestedTab = "FAILED")
        val failed = parseDownloadsLaunchTab(openDownloads = true, requestedTab = "FAILED")

        assertEquals(DownloadTab.ACTIVE, active)
        assertNull(missingOpen)
        assertEquals(DownloadTab.FAILED, failed)
    }
}
