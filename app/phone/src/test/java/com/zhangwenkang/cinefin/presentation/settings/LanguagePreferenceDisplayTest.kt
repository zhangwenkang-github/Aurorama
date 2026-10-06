package com.zhangwenkang.cinefin.presentation.settings

import com.zhangwenkang.cinefin.language.LanguageMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * W74 U7：设置页「首选语言」读数由优先级列表派生（不再直读遗留单值键）。
 *
 * 候选值域取自 `R.array.languages_values` 的同一批取值（ISO 639-2 与 BCP-47 混合）。
 */
class LanguagePreferenceDisplayTest {

    private val optionValues =
        listOf("chi", "zh-Hans", "zh-Hant", "eng", "jpn", "kor", "fre", "ger")

    @Test
    fun `归一化标签映射回下拉选项值`() {
        assertEquals("eng", LanguageMatcher.optionValueFor("en", optionValues))
        assertEquals("jpn", LanguageMatcher.optionValueFor("ja", optionValues))
        assertEquals("zh-Hans", LanguageMatcher.optionValueFor("zh-Hans", optionValues))
        assertEquals("zh-Hant", LanguageMatcher.optionValueFor("zh-Hant", optionValues))
        // 泛化中文码归一化后落到 ISO 639-2 的 chi
        assertEquals("chi", LanguageMatcher.optionValueFor("zh", optionValues))
        assertEquals("chi 与归一化 zh 等价", "chi", LanguageMatcher.optionValueFor("chi", optionValues))
    }

    @Test
    fun `无法识别的标签返回 null 让下拉显示未设置`() {
        assertNull(LanguageMatcher.optionValueFor(null, optionValues))
        assertNull(LanguageMatcher.optionValueFor("", optionValues))
        assertNull(LanguageMatcher.optionValueFor("und", optionValues))
        assertNull("候选值域里没有对应项时不猜", LanguageMatcher.optionValueFor("ko", emptyList()))
    }

    @Test
    fun `手动选轨写入优先级列表后读数与实际一致`() {
        // 手动选音轨（英语）→ rememberSelectedLanguage 把它提到 pref_audio_languages 最前面
        val manual = "en,zh-Hans,zh-Hant,zh,ja"
        val head =
            LanguageMatcher.parsePriority(manual, LanguageMatcher.DEFAULT_AUDIO_PRIORITY)
                .firstOrNull()
        assertEquals("en", head)
        assertEquals("设置页应显示 English", "eng", LanguageMatcher.optionValueFor(head, optionValues))

        // 手动选字幕轨（繁体中文）
        val subtitleManual = "zh-Hant,zh-Hans,zh,en"
        val subtitleHead =
            LanguageMatcher.parsePriority(subtitleManual, LanguageMatcher.DEFAULT_SUBTITLE_PRIORITY)
                .firstOrNull()
        assertEquals(
            "设置页应显示 Chinese (Traditional)",
            "zh-Hant",
            LanguageMatcher.optionValueFor(subtitleHead, optionValues),
        )
    }

    @Test
    fun `空列表回落到默认优先级首项`() {
        val head =
            LanguageMatcher.parsePriority(null, LanguageMatcher.DEFAULT_AUDIO_PRIORITY)
                .firstOrNull()
        assertEquals("zh-Hans", head)
        assertEquals("zh-Hans", LanguageMatcher.optionValueFor(head, optionValues))
    }
}
