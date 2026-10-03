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
                hasVideoLibrary = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        // 内容区（首页…下载）→ 管理区（控制台 / 元数据）；客户端设置固定在底部，不产生分组分隔。
        assertEquals(setOf(6), railGroupBreaks(keys))
        assertEquals(RailGroup.Content, railGroupOf(NavEntryKey.Home))
        assertEquals(RailGroup.Content, railGroupOf(NavEntryKey.Video))
        assertEquals(RailGroup.Content, railGroupOf(NavEntryKey.Favorites))
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
                hasVideoLibrary = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        assertEquals(
            listOf(
                NavEntryKey.Home,
                NavEntryKey.Video,
                NavEntryKey.Music,
                NavEntryKey.Bookshelf,
                NavEntryKey.Favorites,
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
                hasVideoLibrary = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        assertEquals(
            listOf(
                NavEntryKey.Home,
                NavEntryKey.Video,
                NavEntryKey.Music,
                NavEntryKey.Bookshelf,
                NavEntryKey.Favorites,
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
                hasVideoLibrary = false,
                hasMusicLibrary = false,
                hasBooksLibrary = false,
            )

        assertEquals(
            listOf(
                NavEntryKey.Home,
                NavEntryKey.Favorites,
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
                hasVideoLibrary = false,
                hasMusicLibrary = false,
                hasBooksLibrary = false,
            )

        assertTrue(NavEntryKey.Video in keys)
        assertTrue(NavEntryKey.Music in keys)
        assertTrue(NavEntryKey.Bookshelf in keys)
    }

    @Test
    fun hidesVideoWhenServerHasNoMovieOrShowLibrary() {
        val keys =
            navEntryKeys(
                isAdministrator = false,
                librariesLoaded = true,
                hasVideoLibrary = false,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        assertFalse(NavEntryKey.Video in keys)
        assertEquals(NavEntryKey.Home, keys.first())
        assertEquals(NavEntryKey.Music, keys[1])
    }

    @Test
    fun keepsVideoWhileLibraryListIsUnknown() {
        // 冷启动 / 拉取失败：库类型未知时不能把入口藏掉，交给视频页空态。
        val keys =
            navEntryKeys(
                isAdministrator = false,
                librariesLoaded = false,
                hasVideoLibrary = false,
                hasMusicLibrary = false,
                hasBooksLibrary = false,
            )

        assertTrue(NavEntryKey.Video in keys)
    }

    @Test
    fun sidebarVisibilityFilterKeepsClientSettings() {
        val keys =
            navEntryKeys(
                isAdministrator = false,
                librariesLoaded = true,
                hasVideoLibrary = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )
        val visibility =
            SidebarVisibility(
                home = false,
                video = false,
                media = false,
                music = false,
                bookshelf = false,
                downloads = false,
            )

        val visible = visibleRailKeys(keys, visibility)

        // 只关掉开关里的项；客户端设置与「我的收藏」（无开关）永远保留。
        assertEquals(listOf(NavEntryKey.Favorites, NavEntryKey.Settings), visible)
    }

    @Test
    fun sidebarVisibilityCanHideVideoAlone() {
        val keys =
            navEntryKeys(
                isAdministrator = false,
                librariesLoaded = true,
                hasVideoLibrary = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        val visible = visibleRailKeys(keys, SidebarVisibility(video = false))

        assertFalse(NavEntryKey.Video in visible)
        assertTrue(NavEntryKey.Media in visible)
    }

    @Test
    fun phoneBottomTabsAreHomeVideoMusicBookshelf() {
        // W53：媒体库移出底栏（仍保留在侧栏 / 抽屉）。
        assertEquals(
            listOf(
                NavEntryKey.Home,
                NavEntryKey.Video,
                NavEntryKey.Music,
                NavEntryKey.Bookshelf,
            ),
            bottomNavKeys,
        )
        assertFalse(NavEntryKey.Media in bottomNavKeys)
    }

    @Test
    fun musicAndBookshelfSitBeforeMediaGroup() {
        // W8-R3 用户反馈 4（W53 更新）：媒体库（含二级库列表）移到视频 / 音乐 / 书架之后。
        val keys =
            navEntryKeys(
                isAdministrator = true,
                librariesLoaded = true,
                hasVideoLibrary = true,
                hasMusicLibrary = true,
                hasBooksLibrary = true,
            )

        assertTrue(keys.indexOf(NavEntryKey.Video) < keys.indexOf(NavEntryKey.Music))
        assertTrue(keys.indexOf(NavEntryKey.Music) < keys.indexOf(NavEntryKey.Media))
        assertTrue(keys.indexOf(NavEntryKey.Bookshelf) < keys.indexOf(NavEntryKey.Media))
        assertTrue(keys.indexOf(NavEntryKey.Favorites) < keys.indexOf(NavEntryKey.Media))
    }

    @Test
    fun mediaGroupDefaultsCollapsed() {
        // W8-R3 用户反馈 4：抽屉 / 侧轨的二级库列表默认收起，展开后才显示（覆盖 W7-R3 的默认展开）。
        assertFalse(MEDIA_GROUP_DEFAULT_EXPANDED)
    }
}
