package com.zhangwenkang.cinefin.music.data

/**
 * 曲库客户端聚合（MU-2，纯函数，可 JVM 单测）。
 *
 * 服务器没有 MusicAlbum 实体（见 [MusicAlbum] 注释），专辑 / 艺术家都只能由曲目列表聚合：
 * - 专辑：按 `Album` 名分组，组内按音轨序号排序；
 * - 艺术家：按 `AlbumArtist`（或首个艺人）分组，组内按"专辑名 + 音轨序号"排序。
 */
internal fun groupAlbums(songs: List<MusicSong>): List<MusicAlbum> =
    songs
        .groupBy { song -> song.albumName }
        .map { (albumName, group) ->
            val ordered = group.sortedWith(ALBUM_TRACK_ORDER)
            MusicAlbum(
                key = albumName,
                name = albumName,
                artist = ordered.firstNotNullOfOrNull { song -> song.artist },
                imageUri = ordered.firstNotNullOfOrNull { song -> song.imageUri },
                songs = ordered,
            )
        }
        .sortedBy { album -> album.name }

internal fun groupArtists(songs: List<MusicSong>): List<MusicArtist> =
    songs
        .groupBy { song -> song.artist ?: UNKNOWN_ARTIST }
        .map { (artistName, group) ->
            val ordered = group.sortedWith(ARTIST_TRACK_ORDER)
            MusicArtist(
                key = artistName,
                name = artistName,
                imageUri = ordered.firstNotNullOfOrNull { song -> song.imageUri },
                songs = ordered,
            )
        }
        .sortedBy { artist -> artist.name }

/**
 * 续播位置换算（MU-9）：服务器 ticks → 毫秒。
 *
 * 已听完（≥ 90% 时长）的曲目按服务器语义从头播放；位置非法（≤ 0 / 时长未知）时返回 0。
 */
internal fun resumePositionMs(positionTicks: Long?, runtimeTicks: Long): Long {
    if (positionTicks == null || positionTicks <= 0L) return 0L
    val positionMs = positionTicks / TICKS_PER_MS
    if (runtimeTicks <= 0L) return positionMs
    val durationMs = runtimeTicks / TICKS_PER_MS
    if (durationMs > 0L && positionMs * 100L >= durationMs * 90L) return 0L
    return positionMs
}

private const val TICKS_PER_MS = 10_000L
private const val UNKNOWN_ARTIST = "未知艺术家"

/** 组内排序：有音轨序号的在前，同序号按名称。 */
private val ALBUM_TRACK_ORDER =
    compareBy<MusicSong>({ song -> song.indexNumber ?: Int.MAX_VALUE }, { it.name })

/** 艺术家组内排序：先按专辑名，再按音轨序号。 */
private val ARTIST_TRACK_ORDER =
    compareBy<MusicSong>({ song -> song.albumName }, { song -> song.indexNumber ?: Int.MAX_VALUE })
