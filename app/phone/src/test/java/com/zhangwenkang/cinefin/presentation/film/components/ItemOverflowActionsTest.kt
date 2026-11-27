package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.core.presentation.dummy.dummyCollections
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.dummy.dummySeason
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyShow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.0.3-C：海报 / 详情页 ⋮ 菜单的可见性口径。
 *
 * 电影 / 单集按服务端条目级 `canDelete`；剧集 / 季没有该字段，退到账号级删除许可；编辑元数据恒为管理员专属； 离线模式两者都不出现；非影视条目（合集 / 书籍 / 音乐）永远不出
 * ⋮。
 */
class ItemOverflowActionsTest {

    @Test
    fun `movie and episode gate delete on the server item flag`() {
        assertEquals(
            listOf(ItemOverflowAction.EditMetadata, ItemOverflowAction.Delete),
            itemOverflowActions(
                item = dummyMovie.copy(canDelete = true),
                canEditMetadata = true,
                canDeleteContent = false,
                offlineMode = false,
            ),
        )
        assertEquals(
            emptyList<ItemOverflowAction>(),
            itemOverflowActions(
                item = dummyMovie.copy(canDelete = false),
                canEditMetadata = false,
                canDeleteContent = true,
                offlineMode = false,
            ),
        )
        assertEquals(
            listOf(ItemOverflowAction.Delete),
            itemOverflowActions(
                item = dummyEpisode.copy(canDelete = true),
                canEditMetadata = false,
                canDeleteContent = false,
                offlineMode = false,
            ),
        )
    }

    @Test
    fun `show and season fall back to the account delete permission`() {
        assertEquals(
            listOf(ItemOverflowAction.EditMetadata, ItemOverflowAction.Delete),
            itemOverflowActions(
                item = dummyShow,
                canEditMetadata = true,
                canDeleteContent = true,
                offlineMode = false,
            ),
        )
        assertEquals(
            listOf(ItemOverflowAction.EditMetadata),
            itemOverflowActions(
                item = dummyShow,
                canEditMetadata = true,
                canDeleteContent = false,
                offlineMode = false,
            ),
        )
        assertEquals(
            listOf(ItemOverflowAction.Delete),
            itemOverflowActions(
                item = dummySeason,
                canEditMetadata = false,
                canDeleteContent = true,
                offlineMode = false,
            ),
        )
    }

    @Test
    fun `offline mode hides every action`() {
        assertTrue(
            itemOverflowActions(
                    item = dummyMovie.copy(canDelete = true),
                    canEditMetadata = true,
                    canDeleteContent = true,
                    offlineMode = true,
                )
                .isEmpty()
        )
        assertTrue(
            itemOverflowActions(
                    item = dummySeason,
                    canEditMetadata = true,
                    canDeleteContent = true,
                    offlineMode = true,
                )
                .isEmpty()
        )
    }

    @Test
    fun `non video items never show the menu`() {
        val collection = dummyCollections.first()
        assertFalse(supportsItemOverflowActions(collection))
        assertTrue(
            itemOverflowActions(
                    item = collection,
                    canEditMetadata = true,
                    canDeleteContent = true,
                    offlineMode = false,
                )
                .isEmpty()
        )
    }

    @Test
    fun `every video type supports the menu`() {
        assertTrue(supportsItemOverflowActions(dummyMovie))
        assertTrue(supportsItemOverflowActions(dummyShow))
        assertTrue(supportsItemOverflowActions(dummySeason))
        assertTrue(supportsItemOverflowActions(dummyEpisode))
    }

    @Test
    fun `edit metadata is listed before delete`() {
        val actions =
            itemOverflowActions(
                item = dummyEpisode.copy(canDelete = true),
                canEditMetadata = true,
                canDeleteContent = false,
                offlineMode = false,
            )
        assertEquals(ItemOverflowAction.EditMetadata, actions.first())
        assertEquals(ItemOverflowAction.Delete, actions.last())
    }
}
