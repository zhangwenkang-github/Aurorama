package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SRT → ASS 生成器的回归测试（W16 · libass 路径覆盖 SRT）。
 *
 * 关键约束： ① 时间戳必须是 `H:MM:SS.cc`（厘秒，ASS 不认毫秒语法）； ② 颜色必须是 `&HAABBGGRR`，且 alpha 与 Android 相反（00 = 不透明）；
 * ③ 字号 / 边距按 PlayRes 换算（画面上看上去的字号不随渲染区尺寸变化）； ④ 花括号会被 libass 当 override 标签，必须转义掉。
 */
class AssSubtitleScriptTest {

    @Test
    fun formatsTimeInAssCentiseconds() {
        assertEquals("0:00:00.00", AssSubtitleScript.formatTime(0))
        assertEquals("0:00:01.05", AssSubtitleScript.formatTime(1050))
        assertEquals("1:02:03.45", AssSubtitleScript.formatTime(3_723_456))
    }

    @Test
    fun packsColorsAsAssAbgrWithInvertedAlpha() {
        // 不透明白 = &H00FFFFFF；半透明黑（0x66）→ AA = FF - 66 = 99
        assertEquals("&H00FFFFFF", AssSubtitleScript.assColor(0xFFFFFFFF.toInt()))
        assertEquals("&H99000000", AssSubtitleScript.assColor(0x66000000))
        // 暖黄 0xFFFFE082 → ASS 的 AABBGGRR = &H0082E0FF
        assertEquals("&H0082E0FF", AssSubtitleScript.assColor(0xFFFFE082.toInt()))
    }

    @Test
    fun buildsDialogueLinesWithFontSizeAndMarginFromStyle() {
        val style = SubtitleStyle() // 默认：缩放 1.0 / 白字 / 无背景 / 细描边 / 位置 0.08
        val script =
            AssSubtitleScript.forCues(
                cues = listOf(SubtitleCue(1_000, 3_000, "第一行\n第二行")),
                style = style,
                playResX = 1920,
                playResY = 1080,
                frameHeightPx = 1080,
                density = 2f,
            )

        // 字号 = 0.042 × 1080 × 1.0 ≈ 45
        assertTrue(script.contains("Style: Default,sans-serif,45,"))
        assertTrue(script.contains("PlayResX: 1920"))
        assertTrue(script.contains("PlayResY: 1080"))
        // 底部位置：0.08 × 1080 ≈ 86（MarginV）
        assertTrue(script.contains(",86,1"))
        // 换行 → \N；时间厘秒
        assertTrue(script.contains("Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,第一行\\N第二行"))
    }

    @Test
    fun backgroundStyleUsesOpaqueBoxAndTextKeepsStylesOut() {
        val style = SubtitleStyle(backgroundIndex = 2, edgeIndex = 0)
        val script =
            AssSubtitleScript.forCues(
                cues = listOf(SubtitleCue(0, 1_000, "x")),
                style = style,
                playResX = 1920,
                playResY = 1080,
                frameHeightPx = 1080,
                density = 2f,
            )
        // BorderStyle=3（不透明背景框）+ OutlineColour = 背景色（70% 黑 0xB3000000 → ASS &H4C000000）
        assertTrue(script.contains("&H4C000000"))
        assertTrue(script.contains(",3,"))
    }

    @Test
    fun escapesBracesAndNormalizesAssPassthrough() {
        assertEquals("(\\pos(0,0)) 你好", AssSubtitleScript.escapeText("{\\pos(0,0)} 你好"))
        assertEquals("x\n", AssSubtitleScript.normalize("\uFEFFx"))
    }
}
