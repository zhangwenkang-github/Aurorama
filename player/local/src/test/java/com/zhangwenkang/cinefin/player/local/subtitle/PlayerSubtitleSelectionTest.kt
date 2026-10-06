package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.settings.domain.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 字幕链路的选择与记忆（W74 #14）： ①简 / 繁识别（语言码泛化时以标题里的简繁线索为准）； ②手动选择活过「切码率 = 重启播放页」； ③次字幕语言是用户级记忆，换视频后沿用。 */
class PlayerSubtitleSelectionTest {

    private val priority = LanguageMatcher.DEFAULT_SUBTITLE_PRIORITY

    private fun source(
        index: Int,
        title: String,
        language: String,
        isDefault: Boolean = false,
        isForced: Boolean = false,
    ) =
        PlayerSubtitleSource(
            index = index,
            title = title,
            language = language,
            uri = "https://server/Subtitles/$index/Stream.ass",
            codec = "ass",
            isDefault = isDefault,
            isForced = isForced,
        )

    /** `夏日幽灵` 的三条轨：日语 / 简日双语 / 繁日雙語，语言码都是泛化值 */
    private fun summerGhostSources() =
        listOf(
            source(index = 3, title = "日本語", language = detect("jpn", "日本語")!!),
            source(index = 4, title = "简日双语", language = detect("zho", "简日双语")!!),
            source(index = 5, title = "繁日雙語", language = detect("zho", "繁日雙語")!!),
        )

    private fun detect(vararg hints: String?): String? = LanguageMatcher.detect(*hints)

    @Test
    fun `语言码泛化时标题里的简繁线索生效`() {
        assertEquals(LanguageMatcher.CHINESE_SIMPLIFIED, detect("zho", "简日双语"))
        assertEquals(LanguageMatcher.CHINESE_TRADITIONAL, detect("zho", "繁日雙語"))
        assertEquals(LanguageMatcher.CHINESE_SIMPLIFIED, detect("zho", "Chinese(Simplified)"))
        assertEquals(LanguageMatcher.CHINESE_TRADITIONAL, detect("zho", "Chinese(Traditional)"))
        assertEquals(LanguageMatcher.CHINESE_SIMPLIFIED, detect("chi", "简体中文"))
        assertEquals(LanguageMatcher.CHINESE_SIMPLIFIED, detect("", "简日[CHS-JPN]"))
    }

    @Test
    fun `语言码已是具体变体时以它为准，不同语言族不被标题改写`() {
        assertEquals(LanguageMatcher.CHINESE_TRADITIONAL, detect("zh-Hant", "简体"))
        assertEquals(LanguageMatcher.JAPANESE, detect("jpn", "简日双语"))
        assertEquals(LanguageMatcher.ENGLISH, detect("eng", "Chinese(Traditional)"))
    }

    @Test
    fun `简繁同优先级时按设置优先级命中简中`() {
        val picked =
            PlayerSubtitleSelection.pickPrimary(
                mode = Constants.SubtitleMode.AUTO,
                priority = priority,
                sources = summerGhostSources(),
            )

        assertEquals(4, picked?.index)
        assertEquals(LanguageMatcher.CHINESE_SIMPLIFIED, picked?.language)
    }

    @Test
    fun `繁体序号更靠前或默认轨也抢不过简中`() {
        val sources =
            listOf(
                source(
                    index = 2,
                    title = "cht-01",
                    language = detect("", "cht-01")!!,
                    isDefault = true,
                ),
                source(index = 3, title = "chs-01", language = detect("", "chs-01")!!),
            )

        val picked =
            PlayerSubtitleSelection.pickPrimary(
                mode = Constants.SubtitleMode.AUTO,
                priority = priority,
                sources = sources,
            )

        assertEquals(3, picked?.index)
    }

    @Test
    fun `字幕模式关不自动选主字幕`() {
        assertNull(
            PlayerSubtitleSelection.pickPrimary(
                mode = Constants.SubtitleMode.OFF,
                priority = priority,
                sources = summerGhostSources(),
            )
        )
    }

    @Test
    fun `次字幕按用户级语言记忆跨视频继承且不与主字幕同源`() {
        // 用户在上一部片手动选过繁体作次字幕 → `pref_secondary_subtitle_languages` = zh-Hant
        val picked =
            PlayerSubtitleSelection.pickSecondary(
                priority = listOf(LanguageMatcher.CHINESE_TRADITIONAL),
                sources = summerGhostSources(),
                excludeIndex = 4,
            )

        assertEquals(5, picked?.index)
    }

    @Test
    fun `次字幕没设过语言时不自动选`() {
        assertNull(
            PlayerSubtitleSelection.pickSecondary(
                priority = emptyList(),
                sources = summerGhostSources(),
                excludeIndex = 4,
            )
        )
    }

    @Test
    fun `手动选择记忆只在同一媒体上恢复`() {
        val sources = summerGhostSources()
        val memory = SubtitleManualSelection("media-a", primaryIndex = 5, secondaryIndex = 3)

        val restored = PlayerSubtitleSelection.restore(memory, "media-a", sources)

        assertEquals(5, restored?.primaryIndex)
        assertEquals(3, restored?.secondaryIndex)
        assertTrue(restored?.disabledByUser == false)
        assertNull(PlayerSubtitleSelection.restore(memory, "media-b", sources))
    }

    @Test
    fun `记忆里的序号在当前清单不存在时回落到自动选择`() {
        val memory = SubtitleManualSelection("media-a", primaryIndex = 99, secondaryIndex = -1)

        assertNull(PlayerSubtitleSelection.restore(memory, "media-a", summerGhostSources()))
    }

    @Test
    fun `手动关字幕也会被记住并恢复为关闭态`() {
        val memory =
            SubtitleManualSelection(
                "media-a",
                primaryIndex = SubtitleManualSelection.NO_INDEX,
                secondaryIndex = SubtitleManualSelection.NO_INDEX,
            )

        val restored = PlayerSubtitleSelection.restore(memory, "media-a", summerGhostSources())

        assertNull(restored?.primaryIndex)
        assertTrue(restored?.disabledByUser == true)
    }

    @Test
    fun `记忆编解码可往返`() {
        val selection = SubtitleManualSelection("0b834979-9e2e-8c13-9014-783cfd83e7ed", 4, -1)

        assertEquals(selection, SubtitleManualSelection.decode(selection.encode()))
        assertNull(SubtitleManualSelection.decode(""))
        assertNull(SubtitleManualSelection.decode("only-media-id"))
        assertNull(SubtitleManualSelection.decode("|4|5"))
    }
}
