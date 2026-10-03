package com.zhangwenkang.cinefin.presentation.selection

import com.zhangwenkang.cinefin.models.CollectionType
import com.zhangwenkang.cinefin.models.FindroidFolder
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W58b 视频 / 书籍批量规则（纯函数）单测。 */
class MediaBatchRulesTest {

    @Test
    fun `batch mode maps library types`() {
        assertEquals(MediaBatchMode.VIDEO, mediaBatchMode(CollectionType.Movies))
        assertEquals(MediaBatchMode.VIDEO, mediaBatchMode(CollectionType.TvShows))
        assertEquals(MediaBatchMode.VIDEO, mediaBatchMode(CollectionType.HomeVideos))
        assertEquals(MediaBatchMode.BOOK, mediaBatchMode(CollectionType.Books))
        assertEquals(MediaBatchMode.NONE, mediaBatchMode(CollectionType.Playlists))
        assertEquals(MediaBatchMode.NONE, mediaBatchMode(CollectionType.Mixed))
    }

    @Test
    fun `action sets match the mode`() {
        assertEquals(
            listOf(
                MediaBatchAction.PLAY,
                MediaBatchAction.DOWNLOAD,
                MediaBatchAction.MARK_PLAYED,
                MediaBatchAction.FAVORITE,
                MediaBatchAction.DELETE,
            ),
            mediaBatchActions(MediaBatchMode.VIDEO),
        )
        assertEquals(
            listOf(
                MediaBatchAction.DOWNLOAD,
                MediaBatchAction.MARK_PLAYED,
                MediaBatchAction.FAVORITE,
            ),
            mediaBatchActions(MediaBatchMode.BOOK),
        )
        assertEquals(
            listOf(
                MediaBatchAction.FAVORITE,
                MediaBatchAction.DOWNLOAD,
                MediaBatchAction.MARK_PLAYED,
            ),
            mediaBatchActions(MediaBatchMode.FAVORITES),
        )
        assertTrue(mediaBatchActions(MediaBatchMode.NONE).isEmpty())
    }

    @Test
    fun `download is blocked when downloaded or already queued`() {
        assertTrue(MediaBatchCaps().canDownload)
        assertFalse(MediaBatchCaps(downloaded = true).canDownload)
        assertFalse(MediaBatchCaps(activeDownload = true).canDownload)
    }

    @Test
    fun `delete only applies to downloaded server items`() {
        assertTrue(MediaBatchCaps(downloaded = true).canDelete)
        assertFalse(MediaBatchCaps(downloaded = false).canDelete)
        assertFalse(MediaBatchCaps(downloaded = true, fromLocalLibrary = true).canDelete)
    }

    @Test
    fun `caps are derived from the download snapshot keyed by item id`() {
        val item = movie()
        val caps =
            mediaBatchCaps(
                item = item,
                downloadedItemIds = setOf(item.id),
                activeItemIds = emptySet(),
            )
        assertTrue(caps.downloaded)
        assertTrue(caps.canDelete)
        assertTrue(caps.playable)
    }

    @Test
    fun `playable requires a media type and play access`() {
        assertTrue(mediaItemPlayable(movie()))
        assertFalse(mediaItemPlayable(movie(canPlay = false)))
        assertFalse(mediaItemPlayable(book()))
    }

    @Test
    fun `action enabled follows any-of-selected items`() {
        val downloaded = MediaBatchCaps(downloaded = true, playable = true)
        val queued = MediaBatchCaps(activeDownload = true, playable = false)
        val plain = MediaBatchCaps(playable = false, downloaded = false)
        assertTrue(mediaBatchActionEnabled(MediaBatchAction.PLAY, listOf(queued, downloaded)))
        assertFalse(mediaBatchActionEnabled(MediaBatchAction.PLAY, listOf(queued, plain)))
        assertTrue(mediaBatchActionEnabled(MediaBatchAction.DOWNLOAD, listOf(downloaded, plain)))
        assertFalse(mediaBatchActionEnabled(MediaBatchAction.DOWNLOAD, listOf(downloaded, queued)))
        assertTrue(mediaBatchActionEnabled(MediaBatchAction.DELETE, listOf(plain, downloaded)))
        assertFalse(mediaBatchActionEnabled(MediaBatchAction.DELETE, listOf(plain)))
        assertTrue(mediaBatchActionEnabled(MediaBatchAction.MARK_PLAYED, listOf(plain)))
        assertTrue(mediaBatchActionEnabled(MediaBatchAction.FAVORITE, listOf(plain)))
        assertFalse(mediaBatchActionEnabled(MediaBatchAction.MARK_PLAYED, emptyList()))
        assertFalse(mediaBatchActionEnabled(MediaBatchAction.FAVORITE, emptyList()))
    }

    @Test
    fun `download targets skip downloaded and queued items`() {
        val a = movie()
        val b = movie()
        val c = movie()
        val caps =
            mapOf(
                a.id to MediaBatchCaps(downloaded = true),
                b.id to MediaBatchCaps(activeDownload = true),
                c.id to MediaBatchCaps(),
            )
        assertEquals(listOf(c), batchDownloadTargets(listOf(a, b, c), caps))
    }

    @Test
    fun `delete targets keep only downloaded server items`() {
        val a = movie()
        val b = movie()
        val c = movie()
        val caps =
            mapOf(
                a.id to MediaBatchCaps(downloaded = true),
                b.id to MediaBatchCaps(downloaded = true, fromLocalLibrary = true),
                c.id to MediaBatchCaps(),
            )
        assertEquals(listOf(a), batchDeleteTargets(listOf(a, b, c), caps))
    }

    @Test
    fun `play order keeps list order and drops unplayable items`() {
        val first = movie()
        val second = movie()
        val bookItem = book()
        assertEquals(listOf(first, second), batchPlayOrder(listOf(bookItem, first, second)))
        assertEquals(emptyList<FindroidItem>(), batchPlayOrder(listOf(bookItem)))
    }

    @Test
    fun `played target is bidirectional and null when empty`() {
        assertNull(batchPlayedTarget(emptyList()))
        assertEquals(true, batchPlayedTarget(listOf(movie(played = false), movie(played = true))))
        assertEquals(false, batchPlayedTarget(listOf(movie(played = true), movie(played = true))))
    }

    @Test
    fun `favorite target is bidirectional and null when empty`() {
        assertNull(batchFavoriteTarget(emptyList()))
        assertEquals(
            true,
            batchFavoriteTarget(listOf(movie(favorite = true), movie(favorite = false))),
        )
        assertEquals(false, batchFavoriteTarget(listOf(movie(favorite = true))))
    }

    private fun movie(
        played: Boolean = false,
        favorite: Boolean = false,
        canPlay: Boolean = true,
    ) =
        FindroidMovie(
            id = UUID.randomUUID(),
            name = "测试电影",
            originalTitle = null,
            overview = "",
            sources = emptyList(),
            played = played,
            favorite = favorite,
            canPlay = canPlay,
            canDownload = true,
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
        )

    private fun book() =
        FindroidFolder(
            id = UUID.randomUUID(),
            name = "测试书籍",
            played = false,
            favorite = false,
            unplayedItemCount = null,
            images = FindroidImages(),
            kind = "BOOK",
        )
}
