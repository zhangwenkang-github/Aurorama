package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.0.2 D2：头部两层拆分后的可见性规则；2.0.3 D Bug 7：两个 ⋮ 合并为顶栏单入口，规则仍决定菜单里出现哪些组。
 *
 * 标签行常显（5 tab 全保留），「播放全部 + 随机」降为内容区上方紧凑工具行，只在歌曲 / 专辑 tab 显示。
 */
class MusicToolbarVisibilityTest {

    private fun visibility(
        tab: MusicTab = MusicTab.SONGS,
        offline: Boolean = false,
        songCount: Int = 1,
        detailPresent: Boolean = false,
        batchMode: Boolean = false,
    ): MusicPrimaryToolbarVisibility =
        MusicPrimaryToolbarVisibility.forState(
            tab = tab,
            offline = offline,
            songCount = songCount,
            detailPresent = detailPresent,
            batchMode = batchMode,
        )

    @Test
    fun `offline empty music hides the compact row`() {
        val result = visibility(offline = true, songCount = 0)

        assertFalse(result.hasToolbar)
        assertFalse(result.showsSource)
        assertFalse(result.showsPlayAll)
    }

    @Test
    fun `offline with songs shows play all only`() {
        val result = visibility(offline = true, songCount = 1)

        assertTrue(result.hasToolbar)
        assertFalse(result.showsSource)
        assertTrue(result.showsPlayAll)
    }

    @Test
    fun `online empty keeps source options without play all`() {
        val result = visibility(offline = false, songCount = 0)

        assertFalse(result.hasToolbar)
        assertTrue(result.showsSource)
        assertFalse(result.showsPlayAll)
    }

    @Test
    fun `batch mode hides both the menu options and the compact row`() {
        val result = visibility(offline = false, songCount = 1, batchMode = true)

        assertFalse(result.hasToolbar)
        assertFalse(result.showsSource)
        assertFalse(result.showsPlayAll)
    }

    @Test
    fun `detail page shows no compact row`() {
        val result = visibility(detailPresent = true, songCount = 10)

        assertFalse(result.hasToolbar)
        assertFalse(result.showsPlayAll)
    }

    @Test
    fun `play all is limited to songs and albums tabs`() {
        assertTrue(visibility(tab = MusicTab.SONGS).showsPlayAll)
        assertTrue(visibility(tab = MusicTab.ALBUMS).showsPlayAll)
        listOf(MusicTab.SUGGESTIONS, MusicTab.ARTISTS, MusicTab.PLAYLISTS).forEach { tab ->
            assertFalse("$tab 不应显示播放全部", visibility(tab = tab).showsPlayAll)
        }
    }

    @Test
    fun `source options stay independent of the current tab`() {
        MusicTab.entries.forEach { tab ->
            assertTrue("$tab 在线时应给出来源筛选项", visibility(tab = tab).showsSource)
        }
    }

    @Test
    fun `song options only on the songs tab`() {
        assertTrue(visibility(tab = MusicTab.SONGS).showsSongOptions)
        listOf(MusicTab.SUGGESTIONS, MusicTab.ALBUMS, MusicTab.ARTISTS, MusicTab.PLAYLISTS)
            .forEach { tab ->
                assertFalse("$tab 不应显示排序 / 筛选 / 每页数量", visibility(tab = tab).showsSongOptions)
            }
    }

    @Test
    fun `merged menu keeps song options offline`() {
        val result = visibility(offline = true, songCount = 1)

        // 离线时来源组消失，但标签行 ⋮ 原有的排序 / 筛选 / 每页数量不能被合并吞掉。
        assertFalse(result.showsSource)
        assertTrue(result.showsSongOptions)
    }

    @Test
    fun `merged menu keeps every list option on songs tab online`() {
        val result = visibility(tab = MusicTab.SONGS, offline = false, songCount = 3)

        assertTrue(result.showsSource)
        assertTrue(result.showsSongOptions)
    }

    @Test
    fun `detail page and batch mode keep only the navigation group`() {
        listOf(visibility(detailPresent = true), visibility(batchMode = true)).forEach { result ->
            assertFalse(result.showsSource)
            assertFalse(result.showsSongOptions)
        }
    }
}
