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
        assertFalse(consoleEntrySelected(currentPath = "/metadata", entryPath = "/dashboard"))
    }

    @Test
    fun selectsNothingWhenNotOnConsoleRoute() {
        // W7-R3 用户反馈 5：退出控制台后 currentPath = null（当前目的地不是 ConsoleRoute），
        // 两条入口都必须取消选中；旧实现把 null 回退成 /dashboard，导致「服务器控制台」一直亮着。
        assertFalse(consoleEntrySelected(currentPath = null, entryPath = ConsolePathDashboard))
        assertFalse(consoleEntrySelected(currentPath = null, entryPath = ConsolePathMetadata))
        assertFalse(consoleEntrySelected(currentPath = null, entryPath = "/dashboard"))
        assertFalse(consoleEntrySelected(currentPath = null, entryPath = "/metadata"))
    }
}
