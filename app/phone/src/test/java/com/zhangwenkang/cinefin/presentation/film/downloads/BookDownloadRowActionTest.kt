package com.zhangwenkang.cinefin.presentation.film.downloads

import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadAction
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W7-T3：书籍行暂停 / 继续 / 重试的尾键与动作映射（书籍没有 DownloadTask，按条目 key 派发）。 */
class BookDownloadRowActionTest {

    @Test
    fun `书籍行进中与待启动显示暂停暂停态显示继续失败显示重试`() {
        assertEquals(
            listOf(
                DownloadRowActionSpec(
                    DownloadRowTaskAction.PAUSE,
                    CoreR.string.download_action_pause,
                ),
                DownloadRowActionSpec(
                    DownloadRowTaskAction.DELETE,
                    CoreR.string.download_action_delete,
                ),
            ),
            downloadRowActionSpecs(
                status = DownloadTaskStatus.RUNNING,
                canDelete = true,
                pending = false,
            ),
        )
        assertEquals(
            DownloadRowTaskAction.PAUSE,
            downloadRowActionSpecs(
                    status = DownloadTaskStatus.PENDING,
                    canDelete = false,
                    pending = false,
                )
                .single()
                .action,
        )
        assertEquals(
            DownloadRowTaskAction.RESUME,
            downloadRowActionSpecs(
                    status = DownloadTaskStatus.PAUSED,
                    canDelete = false,
                    pending = false,
                )
                .single()
                .action,
        )
        assertEquals(
            DownloadRowTaskAction.RETRY,
            downloadRowActionSpecs(
                    status = DownloadTaskStatus.FAILED,
                    canDelete = false,
                    pending = false,
                )
                .single()
                .action,
        )
    }

    @Test
    fun `书籍行动作按条目 key 派发且删除走行级确认`() {
        val key = "item:book:key"
        assertEquals(
            DownloadAction.PauseBook(key),
            bookDownloadActionForRowAction(key, DownloadRowTaskAction.PAUSE),
        )
        assertEquals(
            DownloadAction.ResumeBook(key),
            bookDownloadActionForRowAction(key, DownloadRowTaskAction.RESUME),
        )
        assertEquals(
            DownloadAction.RetryBook(key),
            bookDownloadActionForRowAction(key, DownloadRowTaskAction.RETRY),
        )
        assertNull(bookDownloadActionForRowAction(key, DownloadRowTaskAction.DELETE))
    }

    @Test
    fun `有声书时长按影视片长同一口径格式化`() {
        assertEquals("24:02", formatRuntimeTicks(24L * 60L * 10_000_000L + 2L * 10_000_000L))
        assertEquals(
            "1:23:00",
            formatRuntimeTicks(3600L * 10_000_000L + 23L * 60L * 10_000_000L),
        )
        assertEquals("0:00", formatRuntimeTicks(0L))
    }
}
