package com.zhangwenkang.cinefin.film.presentation.downloads

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadBadgeRulesTest {

    private fun id(seed: Int): UUID = UUID.nameUUIDFromBytes("item-$seed".toByteArray())

    @Test
    fun `one active item counts once`() {
        assertEquals(
            1,
            DownloadBadgeRules.activeBadgeCount(
                activeItemIds = setOf(id(1)),
                downloadedItemIds = emptySet(),
            ),
        )
    }

    @Test
    fun `same item repeated in the active snapshot is deduplicated`() {
        val duplicated = setOf(id(1), id(1), id(1))
        assertEquals(
            1,
            DownloadBadgeRules.activeBadgeCount(
                activeItemIds = duplicated,
                downloadedItemIds = emptySet(),
            ),
        )
    }

    @Test
    fun `items with a completed local file are excluded`() {
        // 真机缺陷「只下 1 集却显示 2」：活动快照里残留了已落盘的完成条目 → 不得计数。
        assertEquals(
            1,
            DownloadBadgeRules.activeBadgeCount(
                activeItemIds = setOf(id(1), id(2)),
                downloadedItemIds = setOf(id(2)),
            ),
        )
        assertEquals(
            0,
            DownloadBadgeRules.activeBadgeCount(
                activeItemIds = setOf(id(2)),
                downloadedItemIds = setOf(id(2)),
            ),
        )
    }

    @Test
    fun `empty queue counts zero`() {
        assertEquals(
            0,
            DownloadBadgeRules.activeBadgeCount(
                activeItemIds = emptySet(),
                downloadedItemIds = emptySet(),
            ),
        )
    }

    @Test
    fun `mixed queue counts only active not-downloaded items`() {
        assertEquals(
            2,
            DownloadBadgeRules.activeBadgeCount(
                activeItemIds = setOf(id(1), id(2), id(3)),
                downloadedItemIds = setOf(id(3)),
            ),
        )
    }
}
