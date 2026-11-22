package com.zhangwenkang.cinefin.film.presentation.detail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W8-T6：编辑元数据入口只看管理员权限，离线模式一律隐藏。 */
class DetailEditRulesTest {
    @Test
    fun `管理员且在线时显示编辑入口`() {
        assertTrue(
            DetailEditRules.shouldShowEditAction(canEditMetadata = true, offlineMode = false)
        )
    }

    @Test
    fun `非管理员时隐藏编辑入口`() {
        assertFalse(
            DetailEditRules.shouldShowEditAction(canEditMetadata = false, offlineMode = false)
        )
    }

    @Test
    fun `离线模式即使有权限也隐藏编辑入口`() {
        assertFalse(
            DetailEditRules.shouldShowEditAction(canEditMetadata = true, offlineMode = true)
        )
    }
}
