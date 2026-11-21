package com.zhangwenkang.cinefin.settings.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderThemeCustomColorsTest {

    @Test
    fun `自定义色编码后原样解码`() {
        val colors =
            ReaderThemeCustomColors(background = 0xFFFBF6EC.toInt(), content = 0xFF1B212B.toInt())
        assertEquals(colors, ReaderThemeCustomJson.decode(ReaderThemeCustomJson.encode(colors)))
    }

    @Test
    fun `两项默认时 isDefault 为真且编码后解码仍为默认`() {
        val colors = ReaderThemeCustomColors()
        assertTrue(colors.isDefault)
        assertTrue(ReaderThemeCustomJson.decode(ReaderThemeCustomJson.encode(colors)).isDefault)
    }

    @Test
    fun `只自定义背景时正文色保持默认`() {
        val colors = ReaderThemeCustomColors(background = 0xFF101010.toInt())
        assertFalse(colors.isDefault)
        val decoded = ReaderThemeCustomJson.decode(ReaderThemeCustomJson.encode(colors))
        assertEquals(0xFF101010.toInt(), decoded.background)
        assertEquals(null, decoded.content)
    }

    @Test
    fun `非法 JSON 与坏字段回落默认`() {
        assertTrue(ReaderThemeCustomJson.decode(null).isDefault)
        assertTrue(ReaderThemeCustomJson.decode("").isDefault)
        assertTrue(ReaderThemeCustomJson.decode("not-json").isDefault)
        assertTrue(ReaderThemeCustomJson.decode("[1,2]").isDefault)
        assertTrue(
            ReaderThemeCustomJson.decode("""{"background":"oops","content":null}""").isDefault
        )
    }

    @Test
    fun `每套主题的偏好键互不相同且带 pref_reader_theme_custom_ 前缀`() {
        val keys = listOf("paper", "eyecare", "dark", "oled").map(ReaderThemeCustomJson::prefName)
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { key -> assertTrue(key.startsWith("pref_reader_theme_custom_")) }
        assertEquals("pref_reader_theme_custom_eyecare", ReaderThemeCustomJson.prefName("eyecare"))
    }

    @Test
    fun `按主题隔离——写一套主题不串到另一套`() {
        // 模拟 AppPreferences 的按主题独立存储：键 = pref_reader_theme_custom_<theme>。
        val stored = mutableMapOf<String, String>()
        val paper = ReaderThemeCustomColors(background = 0xFFFBF6EC.toInt(), content = null)
        val dark = ReaderThemeCustomColors(background = null, content = 0xFFF4F1EA.toInt())
        stored[ReaderThemeCustomJson.prefName("paper")] = ReaderThemeCustomJson.encode(paper)
        stored[ReaderThemeCustomJson.prefName("dark")] = ReaderThemeCustomJson.encode(dark)

        assertEquals(
            paper,
            ReaderThemeCustomJson.decode(stored[ReaderThemeCustomJson.prefName("paper")]),
        )
        assertEquals(
            dark,
            ReaderThemeCustomJson.decode(stored[ReaderThemeCustomJson.prefName("dark")]),
        )
        assertTrue(
            ReaderThemeCustomJson.decode(stored[ReaderThemeCustomJson.prefName("oled")]).isDefault
        )
    }
}
