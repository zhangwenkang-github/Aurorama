package com.zhangwenkang.cinefin.presentation.navigation

import com.zhangwenkang.cinefin.local.LocalLibrary
import com.zhangwenkang.cinefin.local.LocalLibraryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W53B：侧栏「本地媒体库」子分组的行构造（库级「在媒体库显示」过滤 / 0 库隐藏）与 168dp 轨宽下的 「完整名称优先」排版规则（名称放得下才显示项目数）。 */
class SidebarLocalLibraryTest {

    private fun library(
        id: Long,
        name: String,
        visible: Boolean,
        itemCount: Int = 0,
    ) =
        LocalLibrary(
            id = id,
            name = name,
            type = LocalLibraryType.MIXED,
            visibleInLibrary = visible,
            folders = emptyList(),
            itemCount = itemCount,
            countsByKind = emptyMap(),
        )

    @Test
    fun hiddenLibrariesAreNotListed() {
        val rows =
            sidebarLocalLibraries(
                listOf(
                    library(1, "音乐收藏", visible = true, itemCount = 12),
                    library(2, "已关闭的库", visible = false, itemCount = 3),
                    library(3, "电影收藏", visible = true, itemCount = 0),
                )
            )

        // 关掉的库不出现，顺序保持仓库返回顺序。
        assertEquals(listOf("音乐收藏", "电影收藏"), rows.map { it.name })
        assertEquals(listOf(1L, 3L), rows.map { it.id })
        assertEquals(listOf(12, 0), rows.map { it.itemCount })
    }

    @Test
    fun noVisibleLibraryMeansHiddenGroup() {
        assertTrue(sidebarLocalLibraries(emptyList()).isEmpty())
        assertTrue(sidebarLocalLibraries(listOf(library(1, "关掉的库", visible = false))).isEmpty())
    }

    @Test
    fun countShownWhenNameAndCountFit() {
        // 名称 40dp + 间距 8dp + 项目数 20dp = 68dp ≤ 68dp。
        assertTrue(
            libraryChildCountVisible(
                labelWidthDp = 68f,
                nameWidthDp = 40f,
                countWidthDp = 20f,
                gapDp = 8f,
            )
        )
    }

    @Test
    fun countHiddenWhenBothDoNotFitTogether() {
        // 名称放得下（56 ≤ 68），但名称 + 项目数放不下 → 省略项目数，保完整名称（「音乐测试」不再截断）。
        assertFalse(
            libraryChildCountVisible(
                labelWidthDp = 68f,
                nameWidthDp = 56f,
                countWidthDp = 30f,
                gapDp = 8f,
            )
        )
        // 恰好差 1dp 也省略。
        assertFalse(
            libraryChildCountVisible(
                labelWidthDp = 68f,
                nameWidthDp = 40f,
                countWidthDp = 20.5f,
                gapDp = 8f,
            )
        )
    }

    @Test
    fun countHiddenWhenNameItselfDoesNotFit() {
        assertFalse(
            libraryChildCountVisible(
                labelWidthDp = 68f,
                nameWidthDp = 80f,
                countWidthDp = 10f,
                gapDp = 8f,
            )
        )
    }

    @Test
    fun railLabelWidthMatchesRailGeometry() {
        // 168 − 2×10（导航列内边距）− 16（子项缩进）− 2×14（条目内边距）− 24（图标）− 12（图标与文字间距）。
        assertEquals(68f, railLibraryLabelWidthDp(), 0.001f)
    }
}
