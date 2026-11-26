package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2.0.2 D2 标签行页边距：必须与顶栏 `CinefinPageTopBar` 同一分级，否则顶栏与标签行上下错位。 */
class CinefinLibraryTabsTest {

    @Test
    fun `gutter follows the top bar breakpoints`() {
        assertEquals(20.dp, cinefinLibraryGutterDp(screenWidthDp = 360))
        assertEquals(20.dp, cinefinLibraryGutterDp(screenWidthDp = 599))
        assertEquals(24.dp, cinefinLibraryGutterDp(screenWidthDp = 600))
        assertEquals(24.dp, cinefinLibraryGutterDp(screenWidthDp = 839))
        assertEquals(32.dp, cinefinLibraryGutterDp(screenWidthDp = 840))
        assertEquals(32.dp, cinefinLibraryGutterDp(screenWidthDp = 1199))
        assertEquals(48.dp, cinefinLibraryGutterDp(screenWidthDp = 1200))
        assertEquals(48.dp, cinefinLibraryGutterDp(screenWidthDp = 2560))
    }
}
