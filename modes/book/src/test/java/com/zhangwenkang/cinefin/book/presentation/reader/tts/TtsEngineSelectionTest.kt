package com.zhangwenkang.cinefin.book.presentation.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class TtsEngineSelectionTest {

    @Test
    fun `无默认引擎时按包名升序`() {
        assertEquals(
            listOf("com.a.tts", "com.b.tts", "com.c.tts"),
            ttsEngineBindOrder(listOf("com.c.tts", "com.a.tts", "com.b.tts"), defaultEngine = null),
        )
    }

    @Test
    fun `默认引擎置顶其余保持升序`() {
        assertEquals(
            listOf("com.mibrain.speech", "com.a.tts", "com.c.tts"),
            ttsEngineBindOrder(
                listOf("com.c.tts", "com.mibrain.speech", "com.a.tts"),
                defaultEngine = "com.mibrain.speech",
            ),
        )
    }

    @Test
    fun `默认引擎不在已装列表时忽略`() {
        assertEquals(
            listOf("com.a.tts", "com.b.tts"),
            ttsEngineBindOrder(listOf("com.a.tts", "com.b.tts"), defaultEngine = "com.gone.tts"),
        )
    }

    @Test
    fun `去重并过滤空白`() {
        assertEquals(
            listOf("com.a.tts", "com.b.tts"),
            ttsEngineBindOrder(
                listOf("com.a.tts", " com.a.tts ", "", "   ", "com.b.tts"),
                defaultEngine = " ",
            ),
        )
    }

    @Test
    fun `没有任何引擎时为空表`() {
        assertEquals(emptyList<String>(), ttsEngineBindOrder(emptyList(), defaultEngine = null))
        assertEquals(
            emptyList<String>(),
            ttsEngineBindOrder(emptyList(), defaultEngine = "com.default.tts"),
        )
    }
}
