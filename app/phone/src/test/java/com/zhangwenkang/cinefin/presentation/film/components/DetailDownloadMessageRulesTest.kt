package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W60b 下载反馈「查看」动作映射（纯函数）单测。 */
class DetailDownloadMessageRulesTest {

    @Test
    fun `queue states offer the view action`() {
        assertTrue(DetailDownloadEvent.AddedToQueue.showsViewAction())
        assertTrue(DetailDownloadEvent.AlreadyQueued.showsViewAction())
        assertTrue(DetailDownloadEvent.AlreadyDownloaded.showsViewAction())
        assertTrue(DetailDownloadEvent.BatchAdded(added = 3).showsViewAction())
        assertTrue(DetailDownloadEvent.BatchSkipped(downloaded = 2, queued = 1).showsViewAction())
    }

    @Test
    fun `failure state has no view action`() {
        assertFalse(DetailDownloadEvent.Failed(reason = null).showsViewAction())
    }
}
