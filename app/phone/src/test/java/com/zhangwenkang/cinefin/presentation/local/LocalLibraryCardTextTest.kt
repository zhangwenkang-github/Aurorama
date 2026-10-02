package com.zhangwenkang.cinefin.presentation.local

import com.zhangwenkang.cinefin.local.LocalMediaKind
import org.junit.Assert.assertEquals
import org.junit.Test

/** W39：本地库卡副标题去重口径（纯函数）。 */
class LocalLibraryCardTextTest {

    @Test
    fun singleKindDropsDuplicateCount() {
        val detail =
            localLibraryCardDetail(
                typeLabel = "书籍",
                folderCount = 1,
                itemCount = 10,
                countsByKind = mapOf(LocalMediaKind.BOOK to 10),
                visibleInLibrary = true,
            )
        // 旧文案「书籍 · 1 个文件夹 · 书籍 10」重复；总数改由行尾「10 项」承担。
        assertEquals("书籍 · 1 个文件夹", detail)
    }

    @Test
    fun mixedKindListsPerKindCounts() {
        val detail =
            localLibraryCardDetail(
                typeLabel = "混合",
                folderCount = 2,
                itemCount = 6,
                countsByKind =
                    mapOf(
                        LocalMediaKind.VIDEO to 1,
                        LocalMediaKind.MUSIC to 2,
                        LocalMediaKind.BOOK to 3,
                    ),
                visibleInLibrary = true,
            )
        assertEquals("混合 · 2 个文件夹 · 视频 1 · 音乐 2 · 书籍 3", detail)
    }

    @Test
    fun emptyLibraryShowsScanHint() {
        val detail =
            localLibraryCardDetail(
                typeLabel = "混合",
                folderCount = 1,
                itemCount = 0,
                countsByKind = emptyMap(),
                visibleInLibrary = true,
            )
        assertEquals("混合 · 1 个文件夹 · 尚未扫描到媒体", detail)
    }

    @Test
    fun hiddenLibraryAppendsHiddenSuffix() {
        val detail =
            localLibraryCardDetail(
                typeLabel = "书籍",
                folderCount = 1,
                itemCount = 10,
                countsByKind = mapOf(LocalMediaKind.BOOK to 10),
                visibleInLibrary = false,
            )
        assertEquals("书籍 · 1 个文件夹 · 已隐藏", detail)
    }
}
