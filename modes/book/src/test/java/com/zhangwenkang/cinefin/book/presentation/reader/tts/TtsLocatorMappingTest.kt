package com.zhangwenkang.cinefin.book.presentation.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TtsLocatorMappingTest {

    @Test
    fun `句首 offset 落在所属 segment`() {
        // 「他说：」(3) + 「走吧。」(3) + 「然后走了。」(5)
        val lengths = listOf(3, 3, 5)
        assertEquals(0, segmentIndexForOffset(0, lengths))
        assertEquals(0, segmentIndexForOffset(2, lengths))
        assertEquals(1, segmentIndexForOffset(3, lengths))
        assertEquals(2, segmentIndexForOffset(6, lengths))
        assertEquals(2, segmentIndexForOffset(10, lengths))
    }

    @Test
    fun `offset 越界退到最后一个 segment`() {
        assertEquals(1, segmentIndexForOffset(99, listOf(3, 3)))
    }

    @Test
    fun `无 segment 时映射失败`() {
        assertNull(segmentIndexForOffset(0, emptyList()))
    }

    @Test
    fun `切分后的句子起点能映射回 segment`() {
        val block = "他说：“走吧。”然后走了。"
        val slices = TtsSentenceSplitter.split(block)
        // segment 边界与句边界对齐时（spike 口径：映射退化为「句 → 所属 segment」）：
        // 「他说：」/「“走吧。”」/「然后走了。」三段，第二句起点 8 落在第三段。
        val lengths = listOf(3, 5, 5)
        assertEquals(0, segmentIndexForOffset(slices[0].start, lengths))
        assertEquals(2, segmentIndexForOffset(slices[1].start, lengths))
    }
}
