package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicAlbum
import com.zhangwenkang.cinefin.music.data.MusicArtist
import com.zhangwenkang.cinefin.music.data.MusicPlaylist
import com.zhangwenkang.cinefin.music.data.MusicSong
import com.zhangwenkang.cinefin.player.core.domain.models.QueueSource

/** 曲库浏览的四个维度（MU-2）。 */
enum class MusicTab {
    ALBUMS,
    ARTISTS,
    SONGS,
    PLAYLISTS,
}

/**
 * 曲目列表详情（专辑 / 艺术家 / 歌单三种来源）。
 *
 * [songs] 是"以哪一份列表为播放上下文"的唯一依据：点歌起播时整份列表入队， [source] / [sourceId] 记录队列来源（MU-3 的"继续播放"语义）。
 */
sealed interface MusicDetail {
    val title: String
    val songs: List<MusicSong>
    val source: QueueSource
    val sourceId: String?

    data class Album(val album: MusicAlbum) : MusicDetail {
        override val title: String
            get() = album.name

        override val songs: List<MusicSong>
            get() = album.songs

        override val source: QueueSource
            get() = QueueSource.ALBUM

        override val sourceId: String
            get() = album.key
    }

    data class Artist(val artist: MusicArtist) : MusicDetail {
        override val title: String
            get() = artist.name

        override val songs: List<MusicSong>
            get() = artist.songs

        override val source: QueueSource
            get() = QueueSource.ARTIST

        override val sourceId: String
            get() = artist.key
    }

    data class Playlist(
        val playlist: MusicPlaylist,
        override val songs: List<MusicSong>,
        val loading: Boolean = false,
    ) : MusicDetail {
        override val title: String
            get() = playlist.name

        override val source: QueueSource
            get() = QueueSource.PLAYLIST

        override val sourceId: String
            get() = playlist.id.toString()
    }

    /** 服务端收藏（W21-R2；顶栏「收藏」入口，写入走 UserData 白名单）。 */
    data class Favorites(
        override val songs: List<MusicSong>,
        val loading: Boolean = false,
    ) : MusicDetail {
        override val title: String
            get() = "收藏"

        override val source: QueueSource
            get() = QueueSource.FAVORITES

        override val sourceId: String?
            get() = null
    }

    /** 本地最近播放（W21-R2；按播放时间倒序）。 */
    data class Recent(override val songs: List<MusicSong>) : MusicDetail {
        override val title: String
            get() = "最近播放"

        override val source: QueueSource
            get() = QueueSource.MANUAL

        override val sourceId: String?
            get() = null
    }
}
