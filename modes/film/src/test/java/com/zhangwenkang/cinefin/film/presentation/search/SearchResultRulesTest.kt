package com.zhangwenkang.cinefin.film.presentation.search

import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.local.LocalSearchHit
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W82 #25：分类 / 去重纯函数单测（影视 / 音乐 / 书籍混合结果）。 */
class SearchResultRulesTest {

    @Test
    fun `服务器 Audio 归入音乐而普通影片归入影视`() {
        assertEquals(SearchCategory.MUSIC, SearchResultRules.categoryOf(movie(kind = "AUDIO")))
        assertEquals(SearchCategory.VIDEO, SearchResultRules.categoryOf(movie(kind = "MOVIE")))
        assertEquals(SearchCategory.VIDEO, SearchResultRules.categoryOf(movie(kind = "VIDEO")))
    }

    @Test
    fun `书籍与音乐容器按服务器 kind 分类`() {
        assertEquals(SearchCategory.BOOK, SearchResultRules.categoryOf(folder(kind = "BOOK")))
        assertEquals(
            SearchCategory.MUSIC,
            SearchResultRules.categoryOf(folder(kind = "MUSIC_ALBUM")),
        )
    }

    @Test
    fun `本地视频音乐书籍分别归入三类`() {
        assertEquals(
            SearchCategory.VIDEO,
            SearchResultRules.categoryOf(hit("a.mp4", LocalMediaKind.VIDEO)),
        )
        assertEquals(
            SearchCategory.MUSIC,
            SearchResultRules.categoryOf(hit("a.flac", LocalMediaKind.MUSIC)),
        )
        assertEquals(
            SearchCategory.BOOK,
            SearchResultRules.categoryOf(hit("a.epub", LocalMediaKind.BOOK)),
        )
    }

    @Test
    fun `同一 id 的服务器条目只保留一次且本地按 itemId 去重`() {
        val id = UUID.randomUUID()
        val first = movie(id = id, kind = "AUDIO")
        val duplicate = movie(id = id, kind = "MOVIE")
        assertEquals(
            listOf<FindroidItem>(first),
            SearchResultRules.dedupeServer(listOf(first, duplicate)),
        )

        val entry = hit("tone.flac", LocalMediaKind.MUSIC).entry
        val localHits = listOf(LocalSearchHit(entry, "库"), LocalSearchHit(entry, "库 · 子目录"))
        assertEquals(1, SearchResultRules.dedupeLocal(localHits).size)
    }

    private fun movie(
        id: UUID = UUID.randomUUID(),
        kind: String?,
    ): FindroidMovie =
        FindroidMovie(
            id = id,
            name = "条目",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = false,
            favorite = false,
            canPlay = true,
            canDownload = false,
            runtimeTicks = 0L,
            playbackPositionTicks = 0L,
            premiereDate = null,
            people = emptyList(),
            genres = emptyList(),
            communityRating = null,
            officialRating = null,
            status = "Ended",
            productionYear = null,
            endDate = null,
            trailer = null,
            images = FindroidImages(),
            chapters = emptyList(),
            trickplayInfo = null,
            kind = kind,
        )

    private fun folder(kind: String): FindroidFolder =
        FindroidFolder(
            id = UUID.randomUUID(),
            name = "容器",
            played = false,
            favorite = false,
            unplayedItemCount = null,
            images = FindroidImages(),
            kind = kind,
        )

    private fun hit(name: String, kind: LocalMediaKind): LocalSearchHit =
        LocalSearchHit(
            entry =
                LocalLibraryEntry(
                    itemId = UUID.nameUUIDFromBytes(name.toByteArray()),
                    folderId = 1L,
                    name = name,
                    kind = kind,
                    documentUri = "content://test/$name",
                    relativePath = name,
                    sizeBytes = 1L,
                    lastModified = 0L,
                ),
            folderLabel = "库",
        )
}
