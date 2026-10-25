package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `父级图片仅由当前可见 namespace 与持久父快照共同放行`() {
        val owner =
            ImageDownloadParentOwner(
                kind = ImageDownloadParentKind.SHOW,
                itemId = UUID(0L, 21L),
                serverId = "server-a",
                userId = "user-a",
            )

        assertTrue(
            ImageDownloadWorkRules.isParentCurrent(
                owner = owner,
                visibleServerId = "server-a",
                visibleUserId = "user-a",
                parentExists = true,
            )
        )
        assertFalse(
            ImageDownloadWorkRules.isParentCurrent(
                owner = owner,
                visibleServerId = "server-b",
                visibleUserId = "user-a",
                parentExists = true,
            )
        )
        assertFalse(
            ImageDownloadWorkRules.isParentCurrent(
                owner = owner,
                visibleServerId = "server-a",
                visibleUserId = "user-b",
                parentExists = true,
            )
        )
        assertFalse(
            ImageDownloadWorkRules.isParentCurrent(
                owner = owner,
                visibleServerId = "server-a",
                visibleUserId = "user-a",
                parentExists = false,
            )
        )
    }

    @Test
    fun `父级 owner key 不包含任何 child source`() {
        val itemId = UUID(0L, 22L)
        val season =
            ImageDownloadParentOwner(
                kind = ImageDownloadParentKind.SEASON,
                itemId = itemId,
                serverId = "server-a",
                userId = "user-a",
            )
        val otherUser = season.copy(userId = "user-b")

        assertEquals(
            ImageDownloadWorkRules.parentWorkName(season),
            ImageDownloadWorkRules.parentWorkName(season.copy()),
        )
        assertNotEquals(
            ImageDownloadWorkRules.parentWorkName(season),
            ImageDownloadWorkRules.parentWorkName(otherUser),
        )
        assertTrue(season.canonicalKey.contains(itemId.toString()))
        assertFalse(season.canonicalKey.contains("source-"))
    }
}
