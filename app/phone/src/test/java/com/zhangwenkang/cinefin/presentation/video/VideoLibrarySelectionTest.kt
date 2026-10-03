package com.zhangwenkang.cinefin.presentation.video

import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.presentation.utils.parseStoredLibraryId
import com.zhangwenkang.cinefin.presentation.utils.storedLibraryIdValue
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W54-C 视频页「库选择」：偏好映射 + 过滤规则（库卡模式过滤 / 聚合模式只看所选库）。 */
class VideoLibrarySelectionTest {
    @Test
    fun withoutStoredIdShowsEveryVideoLibrary() {
        val movies = library("电影", CollectionType.Movies)
        val shows = library("动漫", CollectionType.TvShows)

        val selection = resolveVideoLibrarySelection(listOf(movies, shows), storedId = null)

        assertNull(selection.selectedId)
        assertEquals(listOf(movies, shows), selection.visible)
    }

    @Test
    fun storedIdFiltersToThatLibraryOnly() {
        val movies = library("电影", CollectionType.Movies)
        val shows = library("动漫", CollectionType.TvShows)

        val selection =
            resolveVideoLibrarySelection(listOf(movies, shows), storedId = shows.id.toString())

        assertEquals(shows.id, selection.selectedId)
        assertEquals(listOf(shows), selection.visible)
    }

    @Test
    fun staleStoredIdFallsBackToEveryLibrary() {
        val movies = library("电影", CollectionType.Movies)

        // 服务器把库删了 / 换成别的类型 → 回落「全部库」，而不是显示空白。
        val selection =
            resolveVideoLibrarySelection(
                listOf(movies),
                storedId = UUID.randomUUID().toString(),
            )

        assertNull(selection.selectedId)
        assertEquals(listOf(movies), selection.visible)
    }

    @Test
    fun garbageStoredValueIsTreatedAsUnset() {
        val movies = library("电影", CollectionType.Movies)

        val selection = resolveVideoLibrarySelection(listOf(movies), storedId = "not-a-uuid")

        assertNull(selection.selectedId)
        assertEquals(listOf(movies), selection.visible)
    }

    @Test
    fun storedIdMappingRoundTripsAndRejectsBlank() {
        val id = UUID.randomUUID()

        assertEquals(id, parseStoredLibraryId(storedLibraryIdValue(id)))
        assertNull(storedLibraryIdValue(null))
        assertNull(parseStoredLibraryId(null))
        assertNull(parseStoredLibraryId(""))
        assertNull(parseStoredLibraryId("   "))
        assertNull(parseStoredLibraryId("bogus"))
    }

    private fun library(name: String, type: CollectionType) =
        FindroidCollection(
            id = UUID.randomUUID(),
            name = name,
            type = type,
            images = FindroidImages(),
        )
}
