package com.zhangwenkang.cinefin.film.presentation.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W7-T5（需求 09）：下载页通知权限引导的纯判定与「每会话一次」开关。 */
class DownloadsNotificationNudgeTest {

    @Test
    fun `nudge shows for active tasks when notifications are unavailable`() {
        assertTrue(
            shouldShowNotificationPermissionNudge(
                activeCount = 2,
                notificationsEnabled = false,
                dismissedThisSession = false,
            )
        )
    }

    @Test
    fun `nudge stays hidden without active tasks`() {
        assertFalse(
            shouldShowNotificationPermissionNudge(
                activeCount = 0,
                notificationsEnabled = false,
                dismissedThisSession = false,
            )
        )
    }

    @Test
    fun `nudge stays hidden when notifications are enabled`() {
        assertFalse(
            shouldShowNotificationPermissionNudge(
                activeCount = 3,
                notificationsEnabled = true,
                dismissedThisSession = false,
            )
        )
    }

    @Test
    fun `nudge does not repeat within one session`() {
        DownloadNotificationNudgeSession.resetForTest()
        try {
            assertFalse(DownloadNotificationNudgeSession.isDismissed())

            DownloadNotificationNudgeSession.dismiss()

            assertTrue(DownloadNotificationNudgeSession.isDismissed())
            assertFalse(
                shouldShowNotificationPermissionNudge(
                    activeCount = 3,
                    notificationsEnabled = false,
                    dismissedThisSession = DownloadNotificationNudgeSession.isDismissed(),
                )
            )
        } finally {
            DownloadNotificationNudgeSession.resetForTest()
        }
    }
}
