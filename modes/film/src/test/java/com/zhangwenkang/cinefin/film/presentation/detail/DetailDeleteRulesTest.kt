package com.zhangwenkang.cinefin.film.presentation.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W8-T5：删除服务器文件入口只看条目级 canDelete，离线模式一律隐藏。 */
class DetailDeleteRulesTest {
    @Test
    fun `canDelete 为 true 且在线时显示删除入口`() {
        assertTrue(DetailDeleteRules.shouldShowDeleteAction(canDelete = true, offlineMode = false))
    }

    @Test
    fun `canDelete 为 false 时隐藏删除入口`() {
        assertFalse(
            DetailDeleteRules.shouldShowDeleteAction(canDelete = false, offlineMode = false)
        )
    }

    @Test
    fun `离线模式即使 canDelete 为 true 也隐藏删除入口`() {
        assertFalse(DetailDeleteRules.shouldShowDeleteAction(canDelete = true, offlineMode = true))
    }
}
