package com.zhangwenkang.cinefin.presentation.downloads

import com.zhangwenkang.cinefin.DownloadCompletionLaunch
import com.zhangwenkang.cinefin.DownloadsLaunchRequestState
import com.zhangwenkang.cinefin.DownloadsRoute
import com.zhangwenkang.cinefin.downloadsTopLevelEntryIsHome
import com.zhangwenkang.cinefin.parseDownloadsLaunchTab
import com.zhangwenkang.cinefin.presentation.film.DownloadTab
import com.zhangwenkang.cinefin.presentation.navigation.TopLevelTapAction
import com.zhangwenkang.cinefin.presentation.navigation.topLevelTapAction
import com.zhangwenkang.cinefin.resolveDownloadCompletionLaunch
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.OfflineMediaEntry
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind
import java.util.UUID
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
    fun `ordinary entry navigates away from failed notification parameters`() {
        val current = DownloadsRoute(initialTab = DownloadTab.FAILED, requestId = 41L)
        val target = DownloadsRoute(initialTab = DownloadTab.ACTIVE, requestId = 0L)

        assertEquals(
            "当前 FAILED/41 到普通 ACTIVE/0 入口必须导航：current=$current target=$target",
            TopLevelTapAction.Navigate,
            topLevelTapAction(
                isOnEntryHome = downloadsTopLevelEntryIsHome(current, target),
                hasInPageOverlay = false,
            ),
        )
    }

    @Test
    fun `ordinary entry navigates away from consumed active request id`() {
        val current = DownloadsRoute(initialTab = DownloadTab.ACTIVE, requestId = 17L)
        val target = DownloadsRoute(initialTab = DownloadTab.ACTIVE, requestId = 0L)

        assertEquals(
            "当前 ACTIVE/17 到普通 ACTIVE/0 入口必须导航：current=$current target=$target",
            TopLevelTapAction.Navigate,
            topLevelTapAction(
                isOnEntryHome = downloadsTopLevelEntryIsHome(current, target),
                hasInPageOverlay = false,
            ),
        )
    }

    @Test
    fun `ordinary entry stays on identical ordinary parameters`() {
        val current = DownloadsRoute(initialTab = DownloadTab.ACTIVE, requestId = 0L)
        val target = DownloadsRoute(initialTab = DownloadTab.ACTIVE, requestId = 0L)

        assertEquals(
            TopLevelTapAction.Stay,
            topLevelTapAction(
                isOnEntryHome = downloadsTopLevelEntryIsHome(current, target),
                hasInPageOverlay = false,
            ),
        )
    }

    @Test
    fun `repeated notification command keeps its new request id`() {
        val current = DownloadsRoute(initialTab = DownloadTab.FAILED, requestId = 41L)
        val target = DownloadsRoute(initialTab = DownloadTab.FAILED, requestId = 42L)

        assertEquals(42L, target.requestId)
        assertEquals(
            TopLevelTapAction.Navigate,
            topLevelTapAction(
                isOnEntryHome = downloadsTopLevelEntryIsHome(current, target),
                hasInPageOverlay = false,
            ),
        )
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
        val active = parseDownloadsLaunchTab(openDownloads = true, requestedTab = "PODCASTS")
        val missingOpen = parseDownloadsLaunchTab(openDownloads = false, requestedTab = "FAILED")
        val failed = parseDownloadsLaunchTab(openDownloads = true, requestedTab = "FAILED")
        val completed = parseDownloadsLaunchTab(openDownloads = true, requestedTab = "COMPLETED")

        assertEquals(DownloadTab.ACTIVE, active)
        assertNull(missingOpen)
        assertEquals(DownloadTab.FAILED, failed)
        assertEquals("W7-T5：不可达回退落已完成分组", DownloadTab.COMPLETED, completed)
    }

    @Test
    fun `completed notification routes each media kind to its local destination`() {
        val musicId = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7")
        val videoId = UUID.fromString("16fd2706-8baf-433b-82eb-8c7fada847da")
        val bookId = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301")

        assertEquals(
            DownloadCompletionLaunch.Music(musicId.toString()),
            resolveDownloadCompletionLaunch(
                DownloadMediaKind.MUSIC,
                OfflineMediaEntry(
                    itemId = musicId,
                    name = "海边的歌",
                    kind = OfflineMediaEntryKind.MUSIC,
                ),
            ),
        )
        assertEquals(
            DownloadCompletionLaunch.Video(videoId.toString(), isEpisode = true),
            resolveDownloadCompletionLaunch(
                DownloadMediaKind.VIDEO,
                OfflineMediaEntry(
                    itemId = videoId,
                    name = "第 2 集",
                    kind = OfflineMediaEntryKind.VIDEO,
                    isEpisode = true,
                ),
            ),
        )
        assertEquals(
            DownloadCompletionLaunch.Book(bookId.toString(), "海边的书"),
            resolveDownloadCompletionLaunch(
                DownloadMediaKind.BOOK,
                OfflineMediaEntry(
                    itemId = bookId,
                    name = "海边的书",
                    kind = OfflineMediaEntryKind.BOOK,
                ),
            ),
        )
    }

    @Test
    fun `missing local entry falls back to completed downloads group`() {
        val launch =
            resolveDownloadCompletionLaunch(
                mediaKind = DownloadMediaKind.VIDEO,
                entry = null,
            )

        assertEquals(DownloadCompletionLaunch.CompletedDownloads, launch)
    }

    @Test
    fun `kind mismatch or unknown kind falls back to completed downloads group`() {
        val musicEntry =
            OfflineMediaEntry(
                itemId = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"),
                name = "海边的歌",
                kind = OfflineMediaEntryKind.MUSIC,
            )

        assertEquals(
            "媒体类型与本地条目不一致不得直达：$musicEntry",
            DownloadCompletionLaunch.CompletedDownloads,
            resolveDownloadCompletionLaunch(DownloadMediaKind.VIDEO, musicEntry),
        )
        assertEquals(
            DownloadCompletionLaunch.CompletedDownloads,
            resolveDownloadCompletionLaunch(null, musicEntry),
        )
    }
}
