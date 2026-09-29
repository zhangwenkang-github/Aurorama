package com.zhangwenkang.cinefin.models

enum class CollectionType(val type: String) {
    Movies("movies"),
    TvShows("tvshows"),
    HomeVideos("homevideos"),
    Music("music"),
    Playlists("playlists"),
    Books("books"),
    LiveTv("livetv"),
    BoxSets("boxsets"),
    Mixed("null"),
    Folders("folders"),
    Unknown("unknown");

    companion object {
        val defaultValue = Unknown

        /**
         * 可以在 App 里浏览的库类型。
         *
         * 这里刻意把所有「用户可见的媒体库」都列进来：除了原本的影视库， 音乐库、图书（小说/漫画）库、家庭视频库与播放列表库同样要能打开，
         * 否则服务器上明明建好的库会在「媒体库」里凭空消失。 LiveTv 不属于媒体库（是直播源视图），因此不在此列。
         */
        val supported =
            listOf(Movies, TvShows, BoxSets, Mixed, Folders, HomeVideos, Music, Books, Playlists)

        fun fromString(string: String?): CollectionType {
            if (
                string == null
            ) { // TODO jellyfin returns null as the collectiontype for mixed libraries. This is
                //  obviously wrong, but probably an upstream issue. Should be fixed whenever
                //  upstream fixes this
                return Mixed
            }

            return try {
                entries.first { it.type == string }
            } catch (e: NoSuchElementException) {
                defaultValue
            }
        }
    }
}
