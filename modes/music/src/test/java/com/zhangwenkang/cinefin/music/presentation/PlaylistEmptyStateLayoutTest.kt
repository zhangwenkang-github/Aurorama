package com.zhangwenkang.cinefin.music.presentation

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Compose UI test 设施不在本模块测试依赖中；用源码结构断言锁住确定性 infinity constraints 回归： `EmptyHint` 自带
 * verticalScroll，歌单空态必须在 LazyColumn 之前返回，不能作为 list item。
 */
class PlaylistEmptyStateLayoutTest {

    @Test
    fun playlistEmptyState_isRenderedBeforeLazyColumn() {
        val body = playlistListBody()
        val lazyColumnIndex = body.indexOf("LazyColumn")
        val emptyBranchIndex = body.indexOf("if (playlists.isEmpty())")

        assertTrue("PlaylistList must keep an explicit empty branch", emptyBranchIndex >= 0)
        assertTrue(
            "Playlist empty state must be laid out outside LazyColumn",
            emptyBranchIndex < lazyColumnIndex,
        )
        val emptyBranchEnd = body.indexOf("return", emptyBranchIndex)
        assertTrue("Playlist empty branch must return", emptyBranchEnd > emptyBranchIndex)
        val emptyBranch = body.substring(emptyBranchIndex, emptyBranchEnd)
        assertTrue("Playlist empty branch must render EmptyHint", emptyBranch.contains("EmptyHint"))
        assertFalse(
            "EmptyHint must not be wrapped in a LazyColumn item",
            emptyBranch.contains("item("),
        )
    }

    private fun playlistListBody(): String {
        val source = screenSource().readText().replace("\r\n", "\n")
        val start = source.indexOf("private fun PlaylistList(")
        val end = source.indexOf("\n/**\n * W59：46", start)
        assertTrue("PlaylistList source block not found", start >= 0 && end > start)
        return source.substring(start, end)
    }

    private fun screenSource(): File =
        listOf(
                File(
                    "src/main/java/com/zhangwenkang/cinefin/music/presentation/MusicModeScreen.kt"
                ),
                File(
                    "modes/music/src/main/java/com/zhangwenkang/cinefin/music/presentation/MusicModeScreen.kt"
                ),
            )
            .firstOrNull(File::isFile)
            ?: error("MusicModeScreen.kt not found from ${File(".").absolutePath}")
}
