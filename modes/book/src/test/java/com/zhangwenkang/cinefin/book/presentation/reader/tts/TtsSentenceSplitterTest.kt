package com.zhangwenkang.cinefin.book.presentation.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsSentenceSplitterTest {

    private fun texts(text: String): List<String> = TtsSentenceSplitter.split(text).map { it.text }

    @Test
    fun `中文句读切分`() {
        assertEquals(listOf("第一句。", "第二句！", "第三句？", "第四句；"), texts("第一句。第二句！第三句？第四句；"))
    }

    @Test
    fun `右引号跟着本句收尾`() {
        val result = texts("他说：“走吧。”然后走了。")
        assertEquals(listOf("他说：“走吧。”", "然后走了。"), result)
    }

    @Test
    fun `连续终止符与省略号不拆句`() {
        assertEquals(listOf("真的吗？！", "等等……", "后来呢？"), texts("真的吗？！等等……后来呢？"))
    }

    @Test
    fun `英文句点按空格与大小写判定`() {
        assertEquals(
            listOf("Hello world.", "How are you?", "Fine!"),
            texts("Hello world. How are you? Fine!"),
        )
    }

    @Test
    fun `小数点不断句`() {
        assertEquals(listOf("Pi is 3.14 and e is 2.71."), texts("Pi is 3.14 and e is 2.71."))
    }

    @Test
    fun `换行断句`() {
        assertEquals(listOf("第一行", "第二行"), texts("第一行\n第二行"))
    }

    @Test
    fun `空白与空串不产生句子`() {
        assertTrue(TtsSentenceSplitter.split("").isEmpty())
        assertTrue(TtsSentenceSplitter.split("   \n\t ").isEmpty())
    }

    @Test
    fun `区间与原文一致`() {
        val text = "  第一句。第二句！ "
        TtsSentenceSplitter.split(text).forEach { slice ->
            assertEquals(slice.text, text.substring(slice.start, slice.end))
            assertEquals(slice.text, slice.text.trim())
        }
    }

    @Test
    fun `超长句在标点处硬切且不丢字`() {
        val clause = "这是一段没有句号的超长句子，"
        val text = clause.repeat(40)
        val slices = TtsSentenceSplitter.split(text)
        assertTrue(slices.size > 1)
        assertTrue(slices.all { it.text.length <= TtsSentenceSplitter.MaxSentenceChars })
        assertEquals(text.length, slices.sumOf { it.text.length })
    }

    @Test
    fun `冒号与逗号不单独断句`() {
        assertEquals(listOf("注意：这不是句末，继续。"), texts("注意：这不是句末，继续。"))
    }
}
