package com.zhangwenkang.cinefin.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 导航 IA 门控（W6-R6N）：顶层顺序、库类型门控、管理员门控与侧栏可见性过滤。 */
class NavigationIaTest {
    @Test
    fun railGroupsSplitContentAndManageWithoutTouchingSettings() {
        val keys =
            navEntryKeys(
                isAdministrator = true,
                librariesLoaded = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        // 内容区（首页…下载）→ 管理区（控制台 / 元数据）；客户端设置固定在底部，不产生分组分隔。
        assertEquals(setOf(4), railGroupBreaks(keys))
        assertEquals(RailGroup.Content, railGroupOf(NavEntryKey.Home))
        assertEquals(RailGroup.Content, railGroupOf(NavEntryKey.Downloads))
        assertEquals(RailGroup.Manage, railGroupOf(NavEntryKey.Console))
        assertEquals(RailGroup.Manage, railGroupOf(NavEntryKey.Metadata))
        assertEquals(RailGroup.Pinned, railGroupOf(NavEntryKey.Settings))
    }

    @Test
    fun railGroupsWithoutManageEntriesHaveNoBreaks() {
        val keys = listOf(NavEntryKey.Home, NavEntryKey.Media, NavEntryKey.Settings)

        assertTrue(railGroupBreaks(keys).isEmpty())
    }

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
                NavEntryKey.Music,
                NavEntryKey.Bookshelf,
                NavEntryKey.Media,
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
                NavEntryKey.Music,
                NavEntryKey.Bookshelf,
                NavEntryKey.Media,
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

    @Test
    fun musicAndBookshelfSitBeforeMediaGroup() {
        // W8-R3 用户反馈 4（覆盖 W7-R3）：媒体库（含二级库列表）移到音乐 / 书架之后。
        val keys =
            navEntryKeys(
                isAdministrator = true,
                librariesLoaded = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        assertTrue(keys.indexOf(NavEntryKey.Music) < keys.indexOf(NavEntryKey.Media))
        assertTrue(keys.indexOf(NavEntryKey.Bookshelf) < keys.indexOf(NavEntryKey.Media))
    }

    @Test
    fun mediaGroupDefaultsCollapsed() {
        // W8-R3 用户反馈 4：抽屉 / 侧轨的二级库列表默认收起，展开后才显示（覆盖 W7-R3 的默认展开）。
        assertFalse(MEDIA_GROUP_DEFAULT_EXPANDED)
    }
}
