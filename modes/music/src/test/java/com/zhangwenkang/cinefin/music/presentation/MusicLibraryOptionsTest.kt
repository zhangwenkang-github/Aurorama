package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W66：音乐库选择器候选（「全部音乐库」+ 各库）与选中解析纯函数。 */
class MusicLibraryOptionsTest {

    private val musicA = collection("音乐", CollectionType.Music, itemCount = 124)
    private val musicB = collection("音乐测试", CollectionType.Music, itemCount = null)
    private val movies = collection("电影", CollectionType.Movies, itemCount = 17)

    @Test
    fun pickMusicLibrariesKeepsOnlyMusicType() {
        assertEquals(listOf(musicA, musicB), pickMusicLibraries(listOf(musicA, movies, musicB)))
    }

    @Test
    fun optionsStartWithAllMusicLibrariesAndKeepServerOrder() {
        val options = musicLibraryOptions(listOf(musicA, musicB))
        assertEquals(3, options.size)
        assertNull(options[0].id)
        assertEquals("全部音乐库", options[0].label)
        assertEquals(musicA.id, options[1].id)
        assertEquals("音乐", options[1].label)
        assertEquals(musicB.id, options[2].id)
        assertEquals("音乐测试", options[2].label)
    }

    @Test
    fun optionDetailShowsItemCountOnlyWhenPresent() {
        val options = musicLibraryOptions(listOf(musicA, musicB))
        assertEquals("共 124 项", options[1].detail)
        assertNull(options[2].detail)
        assertNull(options[0].detail)
    }

    @Test
    fun resolveSelectedLibraryFallsBackToAllWhenMissingOrInvalid() {
        assertEquals(
            musicB,
            resolveSelectedMusicLibrary(listOf(musicA, musicB), musicB.id.toString()),
        )
        assertNull(resolveSelectedMusicLibrary(listOf(musicA), musicB.id.toString()))
        assertNull(resolveSelectedMusicLibrary(listOf(musicA), "not-a-uuid"))
        assertNull(resolveSelectedMusicLibrary(listOf(musicA), "  "))
        assertNull(resolveSelectedMusicLibrary(listOf(musicA), null))
    }

    private fun collection(
        name: String,
        type: CollectionType,
        itemCount: Int?,
    ): FindroidCollection =
        FindroidCollection(
            id = UUID.randomUUID(),
            name = name,
            type = type,
            images = FindroidImages(),
            itemCount = itemCount,
        )
}
