package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicBatchActionAccessibilityTest {

    @Test
    fun `loading action keeps label and announces progress state`() {
        val semantics =
            musicBatchActionAccessibility(
                label = "播放",
                enabled = false,
                loading = true,
            )

        assertEquals("播放", semantics.contentDescription)
        assertEquals("正在处理", semantics.stateDescription)
    }

    @Test
    fun `idle enabled action keeps label without a false progress state`() {
        val semantics =
            musicBatchActionAccessibility(
                label = "下载",
                enabled = true,
                loading = false,
            )

        assertEquals("下载", semantics.contentDescription)
        assertNull(semantics.stateDescription)
    }
}
