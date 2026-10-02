package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

data class FindroidSeason(
    override val id: UUID,
    override val name: String,
    val seriesId: UUID,
    val seriesName: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<FindroidSource>,
    val indexNumber: Int,
    val episodes: Collection<FindroidEpisode>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long = 0L,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
) : FindroidItem

fun BaseItemDto.toFindroidSeason(jellyfinRepository: JellyfinRepository): FindroidSeason {
    return FindroidSeason(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        unplayedItemCount = userData?.unplayedItemCount,
        indexNumber = indexNumber ?: 0,
        sources = emptyList(),
        episodes = emptyList(),
        seriesId = seriesId!!,
        seriesName = seriesName.orEmpty(),
        images = toFindroidImages(jellyfinRepository),
    )
}

suspend fun FindroidSeasonDto.toFindroidSeason(
    database: ServerDatabaseDao,
    userId: UUID?,
): FindroidSeason {
    // W36：无账号离线模式下没有 userId，播放状态按未观看处理，不再要求登录会话。
    val userData = userId?.let { database.getUserDataOrCreateNew(id, it) }
    return FindroidSeason(
        id = id,
        name = name,
        originalTitle = null,
        overview = overview,
        played = userData?.played ?: false,
        favorite = userData?.favorite ?: false,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        indexNumber = indexNumber,
        sources = emptyList(),
        episodes = emptyList(),
        seriesId = seriesId,
        seriesName = seriesName,
        images = toLocalFindroidImages(itemId = id),
    )
}
