package com.zhangwenkang.cinefin.presentation.film.components

import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 2.0.3-F3：⋮ 键可见性（权限未拉取时先显示、拉取后无可用项才隐藏）与编辑对话框打开条件。 */
class ItemOverflowMenuRulesTest {
    private val itemId: UUID = UUID.randomUUID()

    @Test
    fun `menu stays visible until capabilities are known`() {
        assertTrue(
            itemOverflowMenuVisible(
                supported = true,
                capabilitiesKnown = false,
                actions = emptyList(),
            )
        )
        assertTrue(
            itemOverflowMenuVisible(
                supported = true,
                capabilitiesKnown = true,
                actions = listOf(ItemOverflowAction.Delete),
            )
        )
    }

    @Test
    fun `menu hides once known with no available action`() {
        assertFalse(
            itemOverflowMenuVisible(
                supported = true,
                capabilitiesKnown = true,
                actions = emptyList(),
            )
        )
        assertFalse(
            itemOverflowMenuVisible(
                supported = false,
                capabilitiesKnown = false,
                actions = listOf(ItemOverflowAction.Delete),
            )
        )
    }

    /** 2.0.3-F6：只有「影视条目 + 子树没有 host」才告警；书籍/音乐/合集与已接线的网格不打扰日志。 */
    @Test
    fun `missing host warns only for video posters`() {
        assertTrue(shouldWarnMissingOverflowHost(supported = true, hasHandler = false))
        assertFalse(shouldWarnMissingOverflowHost(supported = true, hasHandler = true))
        assertFalse(shouldWarnMissingOverflowHost(supported = false, hasHandler = false))
    }

    @Test
    fun `metadata editor opens only for the matching ready snapshot`() {
        assertFalse(
            shouldOpenMetadataEditor(
                requestedItemId = null,
                snapshotItemId = null,
                snapshotReady = false,
            )
        )
        assertFalse(
            shouldOpenMetadataEditor(
                requestedItemId = itemId,
                snapshotItemId = null,
                snapshotReady = false,
            )
        )
        assertFalse(
            shouldOpenMetadataEditor(
                requestedItemId = itemId,
                snapshotItemId = UUID.randomUUID(),
                snapshotReady = true,
            )
        )
        // 上一次的旧快照（ready 已翻回 false）不能开对话框——同一张卡重复打开也安全。
        assertFalse(
            shouldOpenMetadataEditor(
                requestedItemId = itemId,
                snapshotItemId = itemId,
                snapshotReady = false,
            )
        )
        assertTrue(
            shouldOpenMetadataEditor(
                requestedItemId = itemId,
                snapshotItemId = itemId,
                snapshotReady = true,
            )
        )
    }
}
