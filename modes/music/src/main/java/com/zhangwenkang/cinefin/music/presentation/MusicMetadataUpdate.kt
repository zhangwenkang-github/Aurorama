package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.music.data.MusicSong

/**
 * W3-T20：把刷新后的曲目对象替换进详情层。
 *
 * 专辑 / 艺术家详情的列表挂在各自的聚合对象上，这里同步替换；专辑原本没有封面时用刷新后的曲目封面兜底。
 */
internal fun MusicDetail.withUpdatedSong(updated: MusicSong): MusicDetail =
    when (this) {
        is MusicDetail.Album ->
            copy(
                album =
                    album.copy(
                        songs = album.songs.replaceSong(updated),
                        imageUri = album.imageUri ?: updated.imageUri,
                    )
            )
        is MusicDetail.Artist ->
            copy(artist = artist.copy(songs = artist.songs.replaceSong(updated)))
        is MusicDetail.Playlist -> copy(songs = songs.replaceSong(updated))
        is MusicDetail.Favorites -> copy(songs = songs.replaceSong(updated))
        is MusicDetail.Recent -> copy(songs = songs.replaceSong(updated))
    }

private fun List<MusicSong>.replaceSong(updated: MusicSong): List<MusicSong> = map { song ->
    if (song.itemId == updated.itemId) updated else song
}
