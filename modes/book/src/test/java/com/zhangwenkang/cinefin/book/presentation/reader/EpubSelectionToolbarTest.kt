package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpubSelectionToolbarTest {

    @Test
    fun `工具栏默认落在选区上方居中`() {
        val placement =
            epubToolbarPlacement(
                selectionLeftDp = 100f,
                selectionTopDp = 400f,
                selectionRightDp = 200f,
                selectionBottomDp = 420f,
                containerWidthDp = 400f,
                containerHeightDp = 800f,
            )
        assertTrue(placement.above)
        // 居中：150 - 288/2 = 6。
        assertEquals(6f, placement.xDp, 0.001f)
        assertEquals(400f - 8f - 92f, placement.yDp, 0.001f)
    }

    @Test
    fun `顶部空间不够时翻到选区下方`() {
        val placement =
            epubToolbarPlacement(
                selectionLeftDp = 40f,
                selectionTopDp = 20f,
                selectionRightDp = 120f,
                selectionBottomDp = 60f,
                containerWidthDp = 360f,
                containerHeightDp = 640f,
            )
        assertFalse(placement.above)
        assertEquals(60f + 8f, placement.yDp, 0.001f)
    }

    @Test
    fun `左右越界被夹回容器内`() {
        val right =
            epubToolbarPlacement(
                selectionLeftDp = 600f,
                selectionTopDp = 300f,
                selectionRightDp = 700f,
                selectionBottomDp = 320f,
                containerWidthDp = 720f,
                containerHeightDp = 900f,
            )
        assertEquals(720f - EPUB_TOOLBAR_WIDTH_DP, right.xDp, 0.001f)

        val left =
            epubToolbarPlacement(
                selectionLeftDp = 0f,
                selectionTopDp = 300f,
                selectionRightDp = 10f,
                selectionBottomDp = 320f,
                containerWidthDp = 720f,
                containerHeightDp = 900f,
            )
        assertEquals(0f, left.xDp, 0.001f)
    }

    @Test
    fun `容器比工具栏还窄时不出现负偏移`() {
        val placement =
            epubToolbarPlacement(
                selectionLeftDp = 10f,
                selectionTopDp = 10f,
                selectionRightDp = 20f,
                selectionBottomDp = 30f,
                containerWidthDp = 200f,
                containerHeightDp = 60f,
            )
        assertEquals(0f, placement.xDp, 0.001f)
        assertTrue(placement.yDp >= 0f)
        assertTrue(placement.yDp <= 0f)
    }

    @Test
    fun `工具栏含五个动作且书内搜索未就绪时灰置`() {
        val actions = epubSelectionActions(inBookSearchAvailable = false)
        assertEquals(
            listOf("高亮", "想法", "复制", "搜索", "词典"),
            actions.map { it.action.label },
        )
        assertFalse(actions.first { it.action == EpubSelectionAction.Search }.enabled)
        assertTrue(actions.filter { it.action != EpubSelectionAction.Search }.all { it.enabled })

        val enabled = epubSelectionActions(inBookSearchAvailable = true)
        assertTrue(enabled.all { it.enabled })
    }

    @Test
    fun `cssSelector 补丁不覆盖原有 otherLocations`() {
        val base = mapOf<String, Any>("progression" to 0.2, "other" to "x")
        val patched = epubLocatorOtherLocations(base, " body > p:nth-child(2) ")
        assertEquals("body > p:nth-child(2)", patched["cssSelector"])
        assertEquals(0.2, patched["progression"])
        assertEquals("x", patched["other"])
        assertEquals(base, epubLocatorOtherLocations(base, "  "))
        assertEquals(base, epubLocatorOtherLocations(base, null))
    }

    @Test
    fun `选区文本优先 locator text 再退 JS 原文`() {
        assertEquals("重点句", epubSelectionText(" 重点句 ", "兜底"))
        assertEquals("兜底", epubSelectionText("   ", " 兜底 "))
        assertEquals("", epubSelectionText("", ""))
        assertEquals("重点句", epubCopyableText("  重点句 \n"))
    }

    @Test
    fun `快照摘要折叠空白截断`() {
        assertEquals("一二 三四", epubAnnotationSummary(" 一二\n三四 "))
        assertEquals("一二三四五六…", epubAnnotationSummary("一二三四五六七八九十", maxChars = 6))
        assertEquals("一二三四五六", epubAnnotationSummary("一二三四五六", maxChars = 6))
    }
}
