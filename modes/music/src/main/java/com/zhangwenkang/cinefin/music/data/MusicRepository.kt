package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder

/** 一首可播放的音乐曲目（音乐线自己的模型，不经过 `FindroidItem`）。 */
data class MusicSong(
    val itemId: UUID,
    val name: String,
    val albumName: String,
    val artist: String?,
    val indexNumber: Int?,
    val runtimeTicks: Long,
    val imageUri: String?,
    /** 服务器记录的续播位置（毫秒）；0 = 从头播放（MU-9）。 */
    val resumePositionMs: Long = 0L,
)

/**
 * 音乐专辑（客户端聚合）。
 *
 * 服务器实测（2026-09-30，Jellyfin 10.11.8）：音乐库**没有 MusicAlbum 实体** （`includeItemTypes=MusicAlbum` 返回
 * 0），音频条的 `AlbumId` / `ParentId` 为空， 只有 `Album` 名称与 `MusicArtist` 实体。所以专辑列表 = 音频按 `Album` 名称分组。
 */
data class MusicAlbum(
    val key: String,
    val name: String,
    val artist: String?,
    val imageUri: String?,
    val songs: List<MusicSong>,
)

/** 音乐艺术家（客户端按艺人名聚合；详见 `groupArtists`）。 */
data class MusicArtist(
    val key: String,
    val name: String,
    val imageUri: String?,
    val songs: List<MusicSong>,
)

/** 服务器歌单（Jellyfin Playlist 实体；服务器实测为 0 个，浏览按空数据兜底）。 */
data class MusicPlaylist(
    val id: UUID,
    val name: String,
    val imageUri: String?,
    val songCount: Int?,
)

/** 曲库快照：一次请求拿到的全部曲目 + 客户端聚合出的专辑 / 艺术家。 */
data class MusicLibrary(
    val songs: List<MusicSong>,
    val albums: List<MusicAlbum>,
    val artists: List<MusicArtist>,
)

/** 音乐曲库仓库（W1 R2 建立，W2 R2 扩展浏览维度）。 */
interface MusicRepository {
    /** 全部音乐曲目与客户端聚合结果（专辑 / 艺术家 / 歌曲三个维度共用一次请求）。 */
    suspend fun getLibrary(): MusicLibrary

    /** 服务器歌单列表。 */
    suspend fun getPlaylists(): List<MusicPlaylist>

    /** 歌单内曲目（按服务器返回顺序）。 */
    suspend fun getPlaylistSongs(playlistId: UUID): List<MusicSong>
}

@Singleton
class MusicRepositoryImpl
@Inject
constructor(
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
) : MusicRepository {

    override suspend fun getLibrary(): MusicLibrary =
        withContext(Dispatchers.IO) {
            val songs = querySongs()
            MusicLibrary(
                songs = songs,
                albums = groupAlbums(songs),
                artists = groupArtists(songs),
            )
        }

    override suspend fun getPlaylists(): List<MusicPlaylist> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
            jellyfinApi.itemsApi
                .getItems(
                    userId,
                    includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                    recursive = true,
                    sortBy = listOfNotNull(ItemSortBy.fromName("SortName")),
                    sortOrder = listOfNotNull(SortOrder.fromName("Ascending")),
                    limit = PLAYLIST_LIMIT,
                )
                .content
                .items
                .mapNotNull { item ->
                    val title = item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MusicPlaylist(
                        id = item.id,
                        name = title,
                        imageUri = item.imageUri(baseUrl),
                        songCount = item.childCount,
                    )
                }
        }

    override suspend fun getPlaylistSongs(playlistId: UUID): List<MusicSong> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
            jellyfinApi.playlistsApi
                .getPlaylistItems(
                    playlistId = playlistId,
                    userId = userId,
                    limit = SONG_LIMIT,
                    enableImages = true,
                    enableUserData = true,
                )
                .content
                .items
                .mapNotNull { item -> item.toMusicSong(baseUrl) }
        }

    private suspend fun querySongs(): List<MusicSong> {
        val userId = jellyfinApi.userId ?: return emptyList()
        val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
        return jellyfinApi.itemsApi
            .getItems(
                userId,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                recursive = true,
                sortBy = listOfNotNull(ItemSortBy.fromName("SortName")),
                sortOrder = listOfNotNull(SortOrder.fromName("Ascending")),
                limit = SONG_LIMIT,
            )
            .content
            .items
            .mapNotNull { item -> item.toMusicSong(baseUrl) }
    }

    private fun BaseItemDto.toMusicSong(baseUrl: String): MusicSong? {
        val title = name?.takeIf { it.isNotBlank() } ?: return null
        val runtimeTicks = runTimeTicks ?: 0L
        return MusicSong(
            itemId = id,
            name = title,
            albumName = album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM,
            artist = albumArtists?.firstOrNull()?.name ?: artists?.firstOrNull(),
            indexNumber = indexNumber,
            runtimeTicks = runtimeTicks,
            imageUri = imageUri(baseUrl),
            resumePositionMs = resumePositionMs(userData?.playbackPositionTicks, runtimeTicks),
        )
    }

    private fun BaseItemDto.imageUri(baseUrl: String): String? =
        imageTags?.get(ImageType.PRIMARY)?.let { tag ->
            "$baseUrl/Items/$id/Images/Primary?tag=$tag"
        }

    private companion object {
        const val SONG_LIMIT = 500
        const val PLAYLIST_LIMIT = 200
        const val UNKNOWN_ALBUM = "未分类"
    }
}
