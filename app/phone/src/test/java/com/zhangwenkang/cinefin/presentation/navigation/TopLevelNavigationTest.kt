package com.zhangwenkang.cinefin.presentation.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/** W56：顶层图标「回对应主页」落点判定（已在主页不重复导航 / 覆盖层收起 / 二级页回根）。 */
class TopLevelNavigationTest {

    @Test
    fun alreadyOnHomeWithoutOverlayStaysPut() {
        assertEquals(
            TopLevelTapAction.Stay,
            topLevelTapAction(isOnEntryHome = true, hasInPageOverlay = false),
        )
    }

    @Test
    fun alreadyOnHomeWithOverlayCollapsesOverlayOnly() {
        assertEquals(
            TopLevelTapAction.CollapseOverlay,
            topLevelTapAction(isOnEntryHome = true, hasInPageOverlay = true),
        )
    }

    @Test
    fun secondLevelPageNavigatesBackToEntryHome() {
        assertEquals(
            TopLevelTapAction.Navigate,
            topLevelTapAction(isOnEntryHome = false, hasInPageOverlay = false),
        )
    }

    @Test
    fun otherEntryWithStaleOverlayStillNavigates() {
        assertEquals(
            TopLevelTapAction.Navigate,
            topLevelTapAction(isOnEntryHome = false, hasInPageOverlay = true),
        )
    }
}
