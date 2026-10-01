package com.zhangwenkang.cinefin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 侧柜控制台入口的门控与路径（W5-R3I）：非管理员隐藏、管理员两条、选中态按 path 区分。 */
class ConsoleEntrySpecTest {
    @Test
    fun hidesConsoleEntriesForNonAdministrator() {
        assertTrue(consoleEntrySpecs(isAdministrator = false).isEmpty())
    }

    @Test
    fun showsConsoleAndMetadataManagerForAdministrator() {
        val entries = consoleEntrySpecs(isAdministrator = true)

        assertEquals(2, entries.size)
        // 复用历史路由：服务器控制台走默认 path，媒体资料管理器走 /metadata
        assertEquals(ConsoleRoute().path, entries[0].path)
        assertEquals("/metadata", entries[1].path)
        assertEquals(ConsolePathDashboard, entries[0].path)
        assertEquals(ConsolePathMetadata, entries[1].path)
        entries.forEach { entry ->
            assertTrue(entry.titleRes != 0)
            assertTrue(entry.iconRes != 0)
        }
    }

    @Test
    fun selectsConsoleEntryByPath() {
        assertTrue(consoleEntrySelected(currentPath = "/dashboard", entryPath = "/dashboard"))
        assertFalse(consoleEntrySelected(currentPath = "/dashboard", entryPath = "/metadata"))
        assertTrue(consoleEntrySelected(currentPath = "/metadata", entryPath = "/metadata"))
        // 默认值（参数缺失）等同控制台路径
        assertTrue(consoleEntrySelected(currentPath = null, entryPath = "/dashboard"))
        assertFalse(consoleEntrySelected(currentPath = null, entryPath = "/metadata"))
    }
}
