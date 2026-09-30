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

/** 音乐曲库仓库（W1 R2）。 */
interface MusicRepository {
    /** 全部音乐专辑（按专辑名排序，专辑内按音轨序号排序）。 */
    suspend fun getAlbums(): List<MusicAlbum>
}

@Singleton
class MusicRepositoryImpl
@Inject
constructor(
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
) : MusicRepository {

    override suspend fun getAlbums(): List<MusicAlbum> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
            val songs =
                jellyfinApi.itemsApi
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
            songs
                .groupBy { song -> song.albumName }
                .map { (albumName, group) ->
                    val ordered = group.sortedWith(TRACK_ORDER)
                    MusicAlbum(
                        key = albumName,
                        name = albumName,
                        artist = ordered.firstOrNull()?.artist,
                        imageUri = ordered.firstNotNullOfOrNull { song -> song.imageUri },
                        songs = ordered,
                    )
                }
                .sortedBy { album -> album.name }
        }

    private fun BaseItemDto.toMusicSong(baseUrl: String): MusicSong? {
        val title = name?.takeIf { it.isNotBlank() } ?: return null
        val imageUri =
            imageTags?.get(ImageType.PRIMARY)?.let { tag ->
                "$baseUrl/Items/$id/Images/Primary?tag=$tag"
            }
        return MusicSong(
            itemId = id,
            name = title,
            albumName = album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM,
            artist = albumArtists?.firstOrNull()?.name ?: artists?.firstOrNull(),
            indexNumber = indexNumber,
            runtimeTicks = runTimeTicks ?: 0L,
            imageUri = imageUri,
        )
    }

    private companion object {
        const val SONG_LIMIT = 500
        const val UNKNOWN_ALBUM = "未分类"

        /** 组内排序：有音轨序号的在前，同序号按名称。 */
        val TRACK_ORDER =
            compareBy<MusicSong>({ song -> song.indexNumber ?: Int.MAX_VALUE }, { it.name })
    }
}
