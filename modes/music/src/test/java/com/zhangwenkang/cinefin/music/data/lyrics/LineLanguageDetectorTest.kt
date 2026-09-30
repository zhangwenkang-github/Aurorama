package com.zhangwenkang.cinefin.music.data.lyrics

import org.junit.Assert.assertEquals
import org.junit.Test

class LineLanguageDetectorTest {

    @Test
    fun `detects japanese line by kana`() {
        assertEquals(LyricLanguage.JAPANESE, LineLanguageDetector.detect("夜に駆ける"))
        assertEquals(LyricLanguage.JAPANESE, LineLanguageDetector.detect("ｱﾘｴｽﾞ"))
    }

    @Test
    fun `detects simplified and traditional chinese`() {
        assertEquals(LyricLanguage.SIMPLIFIED_CHINESE, LineLanguageDetector.detect("爱的回归线"))
        assertEquals(LyricLanguage.SIMPLIFIED_CHINESE, LineLanguageDetector.detect("我们从这里出发"))
        assertEquals(LyricLanguage.TRADITIONAL_CHINESE, LineLanguageDetector.detect("愛的回歸線"))
        assertEquals(LyricLanguage.TRADITIONAL_CHINESE, LineLanguageDetector.detect("我們這樣說話"))
    }

    @Test
    fun `detects latin and mixed lines`() {
        assertEquals(LyricLanguage.ENGLISH, LineLanguageDetector.detect("Brave Shine"))
        assertEquals(LyricLanguage.MIXED, LineLanguageDetector.detect("我的 brave shine"))
    }

    @Test
    fun `returns other for punctuation only`() {
        assertEquals(LyricLanguage.OTHER, LineLanguageDetector.detect("♪"))
        assertEquals(LyricLanguage.OTHER, LineLanguageDetector.detect("——"))
    }

    @Test
    fun `glyph table stays aligned`() {
        LineLanguageDetector.GLYPH_PAIRS.forEach { (simplified, traditional) ->
            assertEquals(
                "简繁字形表必须等长：$simplified / $traditional",
                simplified.length,
                traditional.length,
            )
        }
        val simplified = LineLanguageDetector.GLYPH_PAIRS.flatMap { it.first.toList() }.toSet()
        val traditional = LineLanguageDetector.GLYPH_PAIRS.flatMap { it.second.toList() }.toSet()
        assertEquals(emptySet<Char>(), simplified intersect traditional)
    }
}
