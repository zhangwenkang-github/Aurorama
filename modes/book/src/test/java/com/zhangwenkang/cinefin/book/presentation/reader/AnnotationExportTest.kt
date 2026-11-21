package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationExportTest {

    private fun annotation(
        id: String,
        title: String,
        spine: Int,
        selected: String,
        note: String = "",
        color: HighlightColor = HighlightColor.Yellow,
        createdAtMs: Long = 1_000L,
    ) =
        newBookAnnotation(
            bookId = "book-1",
            chapterHref = "OEBPS/ch${spine + 1}.xhtml",
            locatorJson = """{"href":"OEBPS/ch${spine + 1}.xhtml"}""",
            highlightColor = color,
            noteText = note,
            selectedText = selected,
            chapterTitle = title,
            spineIndex = spine,
            nowMs = createdAtMs,
            id = id,
        )

    @Test
    fun `整书导出结构 快照比对`() {
        val doc =
            AnnotationExportDocument(
                bookTitle = "虚构推理",
                bookAuthor = "城平京",
                progressPercent = annotationExportProgressPercent(0.425),
                exportedAtText = "2026-11-21 14:03",
                annotations =
                    listOf(
                        annotation(
                            id = "a2",
                            title = "第二章 转折",
                            spine = 1,
                            selected = "真相往往藏在细节里。",
                            note = "注意这里的伏笔",
                            color = HighlightColor.Blue,
                            createdAtMs = 2_000L,
                        ),
                        annotation(
                            id = "a1",
                            title = "第一章 起点",
                            spine = 0,
                            selected = "故事从一场雨开始。",
                            createdAtMs = 1_000L,
                        ),
                        annotation(
                            id = "a3",
                            title = "第一章 起点",
                            spine = 0,
                            selected = "她抬头看向窗外。",
                            color = HighlightColor.Pink,
                            createdAtMs = 3_000L,
                        ),
                    ),
            )
        val expected =
            """
            # 虚构推理

            - 作者：城平京
            - 阅读进度：42.5%
            - 导出时间：2026-11-21 14:03
            - 标注数：3

            ## 第一章 起点

            > 故事从一场雨开始。

            - 高亮：yellow

            > 她抬头看向窗外。

            - 高亮：pink

            ## 第二章 转折

            > 真相往往藏在细节里。

            - 笔记：注意这里的伏笔
            - 高亮：blue
            """
                .trimIndent()
        assertEquals(expected, buildAnnotationMarkdown(doc).trimEnd())
    }

    @Test
    fun `没有标注时输出头部与占位`() {
        val markdown =
            buildAnnotationMarkdown(
                AnnotationExportDocument(
                    bookTitle = "空书",
                    bookAuthor = "",
                    progressPercent = null,
                    exportedAtText = "",
                )
            )
        assertEquals("# 空书\n\n- 标注数：0\n\n（暂无标注）\n", markdown)
    }

    @Test
    fun `作者与进度缺失时省略对应行`() {
        val markdown =
            buildAnnotationMarkdown(
                AnnotationExportDocument(
                    bookTitle = "只有书名",
                    exportedAtText = "2026-11-21",
                    annotations = listOf(annotation("a1", "", -1, "孤句")),
                )
            )
        assertTrue(!markdown.contains("- 作者："))
        assertTrue(!markdown.contains("- 阅读进度："))
        assertTrue(markdown.contains("## 未标注章节"))
        assertTrue(markdown.contains("> 孤句"))
    }

    @Test
    fun `多行笔记与多行引文保持引用块结构`() {
        val markdown =
            buildAnnotationMarkdown(
                AnnotationExportDocument(
                    bookTitle = "多行",
                    annotations =
                        listOf(
                            annotation(
                                id = "a1",
                                title = "第一章",
                                spine = 0,
                                selected = "第一行\n第二行",
                                note = "笔记一\n笔记二",
                            )
                        ),
                )
            )
        assertTrue(markdown.contains("> 第一行 第二行"))
        assertTrue(markdown.contains("- 笔记：笔记一\n  笔记二"))
    }

    @Test
    fun `仅有笔记没有引文时用笔记当引文且不重复笔记行`() {
        val markdown =
            buildAnnotationMarkdown(
                AnnotationExportDocument(
                    bookTitle = "无引文",
                    annotations =
                        listOf(
                            annotation(
                                id = "a1",
                                title = "第一章",
                                spine = 0,
                                selected = "",
                                note = "只有想法",
                            )
                        ),
                )
            )
        assertTrue(markdown.contains("> 只有想法"))
        assertTrue(!markdown.contains("- 笔记："))
    }

    @Test
    fun `进度百分比换算与格式化`() {
        assertEquals(42.5, annotationExportProgressPercent(0.425)!!, 1e-9)
        assertEquals(100.0, annotationExportProgressPercent(1.0)!!, 1e-9)
        assertEquals(0.0, annotationExportProgressPercent(-0.2)!!, 1e-9)
        assertEquals(100.0, annotationExportProgressPercent(1.8)!!, 1e-9)
        assertNull(annotationExportProgressPercent(null))
        assertNull(annotationExportProgressPercent(Double.NaN))
        assertEquals("42.5%", annotationExportPercentText(42.5))
        assertEquals("100%", annotationExportPercentText(100.0))
        assertEquals("", annotationExportPercentText(null))
    }

    @Test
    fun `文件名清洗非法字符并补后缀`() {
        assertEquals("虚构推理.md", annotationExportFileName("虚构推理"))
        assertEquals("a_b_c__.md", annotationExportFileName("a/b:c*?"))
        assertEquals("annotations.md", annotationExportFileName("   "))
        assertEquals("分享标题 · 标注", annotationExportShareSubject("分享标题"))
    }
}
