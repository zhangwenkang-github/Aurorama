package com.zhangwenkang.cinefin.presentation.film.components

import com.zhangwenkang.cinefin.core.R
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.MediaBook
import com.zhangwenkang.cinefin.core.presentation.theme.MediaFilm
import com.zhangwenkang.cinefin.core.presentation.theme.MediaMusic
import com.zhangwenkang.cinefin.models.CollectionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W69c：首页「最新 · <库名>」走廊的无图占位图标映射（书籍 / 音乐 / 其余）。 */
class LibraryPlaceholderIconsTest {

    @Test
    fun `书籍库用书图标 音乐库用音符 其余库不加占位`() {
        assertEquals(R.drawable.ic_book, libraryPlaceholderIconRes(CollectionType.Books))
        assertEquals(R.drawable.ic_music, libraryPlaceholderIconRes(CollectionType.Music))
        assertNull(libraryPlaceholderIconRes(CollectionType.Movies))
        assertNull(libraryPlaceholderIconRes(CollectionType.TvShows))
        assertNull(libraryPlaceholderIconRes(CollectionType.HomeVideos))
    }

    /** W74（#3）：媒体库总览大卡的占位色域——音乐 / 书籍按域，其余（含混合 / 播放列表）走影视域。 */
    @Test
    fun `媒体库占位色域按库类型映射`() {
        assertEquals(MediaMusic, libraryPlaceholderMedia(CollectionType.Music))
        assertEquals(MediaBook, libraryPlaceholderMedia(CollectionType.Books))
        assertEquals(MediaFilm, libraryPlaceholderMedia(CollectionType.Movies))
        assertEquals(MediaFilm, libraryPlaceholderMedia(CollectionType.TvShows))
        assertEquals(MediaFilm, libraryPlaceholderMedia(CollectionType.HomeVideos))
        assertEquals(MediaFilm, libraryPlaceholderMedia(CollectionType.Mixed))
        assertEquals(MediaFilm, libraryPlaceholderMedia(CollectionType.Playlists))
    }

    /** W74（#17）：影视域条目卡占位图标——剧集 = 电视、合集 = 星标、其余影视条目 = 胶片。 */
    @Test
    fun `影视域条目占位图标按条目类型映射`() {
        assertEquals(R.drawable.ic_film, videoItemPlaceholderIconRes(dummyMovie))
        assertEquals(R.drawable.ic_film, videoItemPlaceholderIconRes(dummyEpisode))
    }
}
