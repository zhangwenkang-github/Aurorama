package com.zhangwenkang.cinefin.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageDownloadWorkRulesTest {

    @Test
    fun `仅当前未删除代次允许图片任务写入`() {
        assertTrue(
            ImageDownloadWorkRules.isCurrent(
                expectedSourceId = "source-a",
                expectedGeneration = 8L,
                persisted = ImageDownloadOwner("source-a", "COMPLETED", 8L),
            )
        )
        assertTrue(
            ImageDownloadWorkRules.isCurrent(
                expectedSourceId = "source-a",
                expectedGeneration = 8L,
                persisted = ImageDownloadOwner("source-a", "PENDING", 8L),
            )
        )
        assertFalse(
            ImageDownloadWorkRules.isCurrent(
                expectedSourceId = "source-a",
                expectedGeneration = 8L,
                persisted = ImageDownloadOwner("source-a", "DELETING", 9L),
            )
        )
        assertFalse(
            ImageDownloadWorkRules.isCurrent(
                expectedSourceId = "source-a",
                expectedGeneration = 8L,
                persisted = null,
            )
        )
    }

    @Test
    fun `图片任务按 source 使用独立唯一工作名`() {
        assertTrue(
            ImageDownloadWorkRules.uniqueWorkName("source-a") !=
                ImageDownloadWorkRules.uniqueWorkName("source-b")
        )
    }
}
