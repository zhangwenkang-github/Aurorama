package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class MusicDefaultTabTest {
    @Test
    fun musicLibraryStartsOnSuggestionsTab() {
        // 定稿 §4.1：Tab 序列「建议 / 歌曲 / 专辑 / 艺术家 / 歌单」建议居首，冷启动默认落在建议
        assertEquals(MusicTab.SUGGESTIONS, MusicModeViewModel.UiState().tab)
    }
}
