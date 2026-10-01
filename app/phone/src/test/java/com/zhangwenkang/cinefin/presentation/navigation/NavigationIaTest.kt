package com.zhangwenkang.cinefin.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导航 IA 门控（W6-R6N）：顶层顺序、库类型门控、管理员门控与侧栏可见性过滤。 */
class NavigationIaTest {
    @Test
    fun topLevelOrderMatchesSpec() {
        val keys =
            navEntryKeys(
                isAdministrator = false,
                librariesLoaded = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        assertEquals(
            listOf(
                NavEntryKey.Home,
                NavEntryKey.Media,
                NavEntryKey.Music,
                NavEntryKey.Bookshelf,
                NavEntryKey.Downloads,
                NavEntryKey.Settings,
            ),
            keys,
        )
    }

    @Test
    fun administratorGetsConsoleAndMetadataBeforeSettings() {
        val keys =
            navEntryKeys(
                isAdministrator = true,
                librariesLoaded = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        assertEquals(
            listOf(
                NavEntryKey.Home,
                NavEntryKey.Media,
                NavEntryKey.Music,
                NavEntryKey.Bookshelf,
                NavEntryKey.Downloads,
                NavEntryKey.Console,
                NavEntryKey.Metadata,
                NavEntryKey.Settings,
            ),
            keys,
        )
    }

    @Test
    fun hidesMusicAndBookshelfWhenServerHasNoSuchLibrary() {
        val keys =
            navEntryKeys(
                isAdministrator = false,
                librariesLoaded = true,
                hasMusicLibrary = false,
                hasBooksLibrary = false,
            )

        assertEquals(
            listOf(
                NavEntryKey.Home,
                NavEntryKey.Media,
                NavEntryKey.Downloads,
                NavEntryKey.Settings,
            ),
            keys,
        )
    }

    @Test
    fun keepsMusicAndBookshelfWhileLibraryListIsUnknown() {
        // 冷启动 / 拉取失败：库类型未知时不能把入口藏掉，交给页面空态（避免把网络故障当成"没有这个库"）。
        val keys =
            navEntryKeys(
                isAdministrator = false,
                librariesLoaded = false,
                hasMusicLibrary = false,
                hasBooksLibrary = false,
            )

        assertTrue(NavEntryKey.Music in keys)
        assertTrue(NavEntryKey.Bookshelf in keys)
    }

    @Test
    fun sidebarVisibilityFilterKeepsClientSettings() {
        val keys =
            navEntryKeys(
                isAdministrator = false,
                librariesLoaded = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )
        val visibility =
            SidebarVisibility(
                home = false,
                media = false,
                music = false,
                bookshelf = false,
                downloads = false,
            )

        val visible = visibleRailKeys(keys, visibility)

        // 只关掉开关里的项；客户端设置永远保留（否则再也回不到设置页）。
        assertEquals(listOf(NavEntryKey.Settings), visible)
    }

    @Test
    fun phoneBottomTabsKeepHomeMusicBookshelfMediaOrder() {
        assertEquals(
            listOf(NavEntryKey.Home, NavEntryKey.Music, NavEntryKey.Bookshelf, NavEntryKey.Media),
            bottomNavKeys,
        )
    }
}
