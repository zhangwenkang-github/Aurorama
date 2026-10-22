package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.settings.domain.Constants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `规范键严格编码服务用户与媒体三段`() {
        val scope =
            SubtitleSelectionScope.create(
                serverId = "server-a",
                userId = "user-a",
                itemId = "item-a",
            )!!

        assertEquals("server-a|user-a|item-a", scope.encode())
        assertEquals(scope, SubtitleSelectionScope.decode(scope.encode()))
        assertEquals(
            "-|user-a|item-a",
            SubtitleSelectionScope.create(
                    serverId = null,
                    userId = "user-a",
                    itemId = "item-a",
                )!!
                .encode(),
        )
        assertEquals(
            "server-a|-|item-a",
            SubtitleSelectionScope.create(
                    serverId = "server-a",
                    userId = "",
                    itemId = "item-a",
                )!!
                .encode(),
        )
    }

    @Test
    fun `规范键拒绝缺段空段额外段和非法媒体占位`() {
        assertNull(SubtitleSelectionScope.decode(null))
        assertNull(SubtitleSelectionScope.decode(""))
        assertNull(SubtitleSelectionScope.decode("server-a|user-a"))
        assertNull(SubtitleSelectionScope.decode("server-a|user-a|item-a|extra"))
        assertNull(SubtitleSelectionScope.decode("server-a||item-a"))
        assertNull(SubtitleSelectionScope.decode("|-|item-a"))
        assertNull(SubtitleSelectionScope.decode("server-a|user-a|-"))
        assertNull(SubtitleSelectionScope.decode(" server-a|user-a|item-a"))
        assertNull(SubtitleSelectionScope.decode("server-a|user-a|item-a "))
        assertNull(
            SubtitleSelectionScope.create(
                serverId = "bad|server",
                userId = "user-a",
                itemId = "item-a",
            )
        )
    }

    @Test
    fun `单片主次与关闭记录严格编解码`() {
        val scope =
            SubtitleSelectionScope.create(
                serverId = "server-a",
                userId = "user-a",
                itemId = "item-a",
            )!!
        val selection = ScopedSubtitleSelection(scope, primaryIndex = 4, secondaryIndex = -1)

        assertEquals("server-a|user-a|item-a|4|-1", selection.encode())
        assertEquals(selection, ScopedSubtitleSelection.decode(selection.encode()))
        assertNull(ScopedSubtitleSelection.decode("server-a|user-a|item-a|4"))
        assertNull(ScopedSubtitleSelection.decode("server-a|user-a|item-a|-2|-1"))
        assertNull(ScopedSubtitleSelection.decode("server-a||item-a|4|-1"))
    }

    @Test
    fun `多片记录逐行解析并逐条忽略坏记录`() {
        val first =
            ScopedSubtitleSelection(
                SubtitleSelectionScope.create("server-a", "user-a", "item-a")!!,
                primaryIndex = 4,
                secondaryIndex = 3,
            )
        val second =
            ScopedSubtitleSelection(
                SubtitleSelectionScope.create("server-b", "user-b", "item-b")!!,
                primaryIndex = -1,
                secondaryIndex = -1,
            )
        val raw =
            listOf(
                    first.encode(),
                    "broken",
                    "server-a|user-a|item-c|not-int|-1",
                    "server-a|user-a|item-c|-2|-1",
                    second.encode(),
                    "",
                )
                .joinToString("\n")

        assertEquals(listOf(first, second), ScopedSubtitleSelection.decodeAll(raw))
    }

    @Test
    fun `单片记录只在完整 scope 匹配时恢复`() {
        val sources = summerGhostSources()
        val scope = SubtitleSelectionScope.create("server-a", "user-a", "item-a")!!
        val selection = ScopedSubtitleSelection(scope, primaryIndex = 5, secondaryIndex = 3)

        val restored =
            PlayerSubtitleSelection.restoreScoped(selection, scope = scope, sources = sources)

        assertEquals(5, restored?.primaryIndex)
        assertEquals(3, restored?.secondaryIndex)
        assertNull(
            PlayerSubtitleSelection.restoreScoped(
                selection,
                scope = scope.copy(userId = "user-b"),
                sources = sources,
            )
        )
        assertNull(
            PlayerSubtitleSelection.restoreScoped(
                selection,
                scope = scope.copy(itemId = "item-b"),
                sources = sources,
            )
        )
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

    @Test
    fun `主路分配同一轨时清空原次路`() {
        val next =
            SubtitleTrackAssignment.assignPrimary(
                SubtitleTrackPair(primaryIndex = 3, secondaryIndex = 5),
                index = 5,
            )

        assertEquals(5, next.primaryIndex)
        assertNull(next.secondaryIndex)
    }

    @Test
    fun `次路分配同一轨时清空原主路`() {
        val next =
            SubtitleTrackAssignment.assignSecondary(
                SubtitleTrackPair(primaryIndex = 4, secondaryIndex = 5),
                index = 4,
            )

        assertNull(next.primaryIndex)
        assertEquals(4, next.secondaryIndex)
    }

    @Test
    fun `主次交换只在两条不同文本轨时成立`() {
        val swapped =
            SubtitleTrackAssignment.swap(SubtitleTrackPair(primaryIndex = 3, secondaryIndex = 5))

        assertEquals(5, swapped?.primaryIndex)
        assertEquals(3, swapped?.secondaryIndex)
        assertNull(SubtitleTrackAssignment.swap(SubtitleTrackPair(primaryIndex = 3)))
        assertNull(
            SubtitleTrackAssignment.swap(SubtitleTrackPair(primaryIndex = 3, secondaryIndex = 3))
        )
    }

    @Test
    fun `mpv 映射先标题语言、再唯一标题语言，最后同序号兜底`() {
        val sources = summerGhostSources()
        val exact =
            MpvSubtitleTrackRef(
                id = 90,
                label = "繁日雙語",
                language = sources[2].language,
                codec = "ass",
            )
        val byTitle = MpvSubtitleTrackRef(id = 91, label = "简日双语", language = "jpn", codec = "ass")
        val byLanguage =
            MpvSubtitleTrackRef(
                id = 92,
                label = null,
                language = sources[0].language,
                codec = "ass",
            )
        val bySameIndex =
            MpvSubtitleTrackRef(id = 4, label = "无匹配", language = "eng", codec = "ass")

        assertEquals(5, MpvSubtitleTrackMapping.sourceIndexForTrack(exact, sources))
        assertEquals(4, MpvSubtitleTrackMapping.sourceIndexForTrack(byTitle, sources))
        assertEquals(3, MpvSubtitleTrackMapping.sourceIndexForTrack(byLanguage, sources))
        assertEquals(4, MpvSubtitleTrackMapping.sourceIndexForTrack(bySameIndex, sources))
        assertEquals(
            90,
            MpvSubtitleTrackMapping.trackForSource(5, listOf(exact, byTitle), sources)?.id,
        )
        assertNull(MpvSubtitleTrackMapping.trackForSource(99, listOf(exact, byTitle), sources))
    }

    @Test
    fun `mpv sid 与源序号碰撞时优先按标题语言区分`() {
        val sources =
            listOf(
                source(index = 3, title = "English", language = "en").copy(codec = "subrip"),
                source(index = 4, title = "简体中文", language = "zh-Hans").copy(codec = "ass"),
            )
        val track =
            MpvSubtitleTrackRef(
                id = 3,
                label = "简体中文",
                language = "zh-Hans",
                codec = "ass",
            )

        // 旧实现会先命中 index=3 的英文源；新实现必须命中标题 / 语言对应的 4。
        assertEquals(4, MpvSubtitleTrackMapping.sourceIndexForTrack(track, sources))
    }

    @Test
    fun `mpv 标题重复且语言不再唯一时返回歧义`() {
        val sources =
            listOf(
                source(index = 1, title = "Commentary", language = "en").copy(codec = "ass"),
                source(index = 2, title = "Commentary", language = "fr").copy(codec = "ass"),
            )
        val track =
            MpvSubtitleTrackRef(
                id = 2,
                label = "Commentary",
                language = "de",
                codec = "ass",
            )

        assertNull(MpvSubtitleTrackMapping.sourceIndexForTrack(track, sources))
        assertNull(MpvSubtitleTrackMapping.trackForSource(1, listOf(track), sources))
    }

    @Test
    fun `mpv 图形轨不会映射到同序号的文本源`() {
        val sources =
            listOf(
                source(index = 5, title = "中文文本", language = "zho").copy(codec = "ass"),
                PlayerSubtitleSource(
                        index = 9,
                        title = "中文图形",
                        language = "zho",
                        uri = "https://server/Subtitles/9/Stream.sup",
                        codec = "pgs",
                        isGraphic = true,
                    )
                    .copy(),
            )
        val graphicTrack =
            MpvSubtitleTrackRef(id = 5, label = "中文图形", language = "zho", codec = "pgs")
        val unknownGraphic =
            MpvSubtitleTrackRef(id = 5, label = "无匹配", language = "und", codec = "pgs")

        assertEquals(9, MpvSubtitleTrackMapping.sourceIndexForTrack(graphicTrack, sources))
        assertNull(MpvSubtitleTrackMapping.sourceIndexForTrack(unknownGraphic, sources))
        assertNull(MpvSubtitleTrackMapping.secondarySourceIndexForTrack(graphicTrack, sources))
    }

    @Test
    fun `mpv 次字幕候选保留源序号并拒绝同源主次`() {
        val sources = summerGhostSources()
        val tracks =
            listOf(
                MpvSubtitleTrackRef(
                    id = 3,
                    label = "日本語",
                    language = sources[0].language,
                    codec = "ass",
                ),
                MpvSubtitleTrackRef(
                    id = 4,
                    label = "简日双语",
                    language = sources[1].language,
                    codec = "ass",
                ),
            )
        val primarySource = MpvSubtitleTrackMapping.sourceIndexForTrack(tracks[0], sources)
        val secondarySource = MpvSubtitleTrackMapping.sourceIndexForTrack(tracks[1], sources)

        assertEquals(3, primarySource)
        assertEquals(4, secondarySource)
        assertTrue(primarySource != secondarySource)
        assertNull(
            MpvSubtitleTrackMapping.trackForSource(
                primarySource!!,
                tracks.drop(1),
                sources,
            )
        )
    }

    @Test
    fun `多条 mpv 轨映射到同一源时反查返回歧义`() {
        val sources = summerGhostSources()
        val duplicateA =
            MpvSubtitleTrackRef(
                id = 10,
                label = "简日双语",
                language = sources[1].language,
                codec = "ass",
            )
        val duplicateB =
            MpvSubtitleTrackRef(
                id = 11,
                label = "简日双语",
                language = sources[1].language,
                codec = "ass",
            )

        assertNull(
            MpvSubtitleTrackMapping.trackForSource(4, listOf(duplicateA, duplicateB), sources)
        )
    }

    @Test
    fun `mpv 纯源文本列表只补未映射的文本轨`() {
        val sources =
            listOf(
                source(index = 1, title = "源 ASS", language = "zh-Hans").copy(codec = "ass"),
                PlayerSubtitleSource(
                        index = 2,
                        title = "PGS",
                        language = "zh-Hans",
                        uri = "https://server/Subtitles/2/Stream.sup",
                        codec = "pgs",
                        isGraphic = true,
                    )
                    .copy(),
                source(index = 3, title = "未注入 SRT", language = "en").copy(codec = "subrip"),
            )
        assertEquals(
            listOf(3),
            MpvSubtitleTrackMapping.unmappedTextSources(
                    representedSourceIndices = setOf(1, 2),
                    sources = sources,
                )
                .map { it.index },
        )
    }

    @Test
    fun `已表示源集合会排除侧载纯源行`() {
        val sideloaded =
            source(
                index = SideloadedSubtitleStore.INDEX_BASE,
                title = "侧载字幕",
                language = "zh-Hans",
            )

        assertTrue(
            MpvSubtitleTrackMapping.unmappedTextSources(
                    representedSourceIndices = setOf(SideloadedSubtitleStore.INDEX_BASE),
                    sources = listOf(sideloaded),
                )
                .isEmpty()
        )
        assertEquals(
            listOf(SideloadedSubtitleStore.INDEX_BASE),
            MpvSubtitleTrackMapping.unmappedTextSources(
                    representedSourceIndices = emptySet(),
                    sources = listOf(sideloaded),
                )
                .map { it.index },
        )
    }

    @Test
    fun `手动主字幕语言会提到优先级最前且去重`() {
        val updated =
            PlayerSubtitleSelection.promotedLanguagePriority(
                current = "en,zh-Hans",
                language = "zh-Hans",
                isSecondary = false,
            )
        val parsed = LanguageMatcher.parsePriority(updated!!, emptyList())

        assertEquals("zh-Hans", parsed.first())
        assertEquals(1, parsed.count { LanguageMatcher.baseOf(it) == "zh" })
        assertNull(
            PlayerSubtitleSelection.promotedLanguagePriority(
                current = "en",
                language = "",
                isSecondary = false,
            )
        )
    }

    @Test
    fun `图形字幕源不可作为次字幕文本`() {
        val graphic =
            PlayerSubtitleSource(
                index = 9,
                title = "PGS",
                language = "zho",
                uri = "https://server/Subtitles/9/Stream.sup",
                codec = "pgs",
                isGraphic = true,
            )

        assertFalse(graphic.isTextBased)
        assertNull(
            MpvSubtitleTrackMapping.secondarySourceIndexForTrack(
                MpvSubtitleTrackRef(id = 9, codec = "pgs"),
                listOf(graphic),
            )
        )
    }
}
