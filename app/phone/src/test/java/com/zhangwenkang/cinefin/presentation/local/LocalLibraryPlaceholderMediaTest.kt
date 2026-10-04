package com.zhangwenkang.cinefin.presentation.local

import com.zhangwenkang.cinefin.core.presentation.theme.MediaBook
import com.zhangwenkang.cinefin.core.presentation.theme.MediaFilm
import com.zhangwenkang.cinefin.core.presentation.theme.MediaMusic
import com.zhangwenkang.cinefin.local.LocalLibraryType
import com.zhangwenkang.cinefin.local.LocalMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** W70b：本地媒体库占位配色（类型 → 媒体色域）纯函数单测（用户 2026-10-05 截图反馈）。 */
class LocalLibraryPlaceholderMediaTest {
    @Test
    fun libraryTypeMapsToExpectedMediaDomain() {
        // 视频 / 混合 = 影视域（混合沿用既有 Neutral = 影视色口径）；音乐 / 书籍各自域。
        assertEquals(MediaFilm, localLibraryPlaceholderMedia(LocalLibraryType.VIDEO))
        assertEquals(MediaFilm, localLibraryPlaceholderMedia(LocalLibraryType.MIXED))
        assertEquals(MediaMusic, localLibraryPlaceholderMedia(LocalLibraryType.MUSIC))
        assertEquals(MediaBook, localLibraryPlaceholderMedia(LocalLibraryType.BOOK))
    }

    @Test
    fun entryKindMapsToExpectedMediaDomain() {
        assertEquals(MediaFilm, localEntryPlaceholderMedia(LocalMediaKind.VIDEO))
        assertEquals(MediaMusic, localEntryPlaceholderMedia(LocalMediaKind.MUSIC))
        assertEquals(MediaBook, localEntryPlaceholderMedia(LocalMediaKind.BOOK))
    }
}
