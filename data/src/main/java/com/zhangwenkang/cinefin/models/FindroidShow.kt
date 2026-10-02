package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.PlayAccess

data class FindroidShow(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    override val sources: List<FindroidSource>,
    val seasons: List<FindroidSeason>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val playbackPositionTicks: Long = 0L,
    override val unplayedItemCount: Int?,
    val genres: List<String>,
    val people: List<FindroidItemPerson>,
    override val runtimeTicks: Long,
    val communityRating: Float?,
    val officialRating: String?,
    val status: String,
    val productionYear: Int?,
    val endDate: DateTime?,
    val trailer: String?,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter> = emptyList(),
) : FindroidItem

fun BaseItemDto.toFindroidShow(jellyfinRepository: JellyfinRepository): FindroidShow {
    return FindroidShow(
        id = id,
        name = name.orEmpty(),
        originalTitle = originalTitle,
        overview = overview.orEmpty(),
        played = userData?.played == true,
        favorite = userData?.isFavorite == true,
        canPlay = playAccess != PlayAccess.NONE,
        canDownload = canDownload == true,
        unplayedItemCount = userData?.unplayedItemCount,
        sources = emptyList(),
        seasons = emptyList(),
        genres = genres ?: emptyList(),
        people = people?.map { it.toFindroidPerson(jellyfinRepository) } ?: emptyList(),
        runtimeTicks = runTimeTicks ?: 0,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status ?: "Ended",
        productionYear = productionYear,
        endDate = endDate,
        trailer = remoteTrailers?.getOrNull(0)?.url,
        images = toFindroidImages(jellyfinRepository),
    )
}

suspend fun FindroidShowDto.toFindroidShow(
    database: ServerDatabaseDao,
    userId: UUID?,
): FindroidShow {
    // W36：无账号离线模式下没有 userId，播放状态按未观看处理，不再要求登录会话。
    val userData = userId?.let { database.getUserDataOrCreateNew(id, it) }
    return FindroidShow(
        id = id,
        name = name,
        originalTitle = originalTitle,
        overview = overview,
        played = userData?.played ?: false,
        favorite = userData?.favorite ?: false,
        canPlay = true,
        canDownload = false,
        unplayedItemCount = null,
        sources = emptyList(),
        seasons = emptyList(),
        genres = emptyList(),
        people = emptyList(),
        runtimeTicks = runtimeTicks,
        communityRating = communityRating,
        officialRating = officialRating,
        status = status,
        productionYear = productionYear,
        endDate = endDate,
        trailer = null,
        images = toLocalFindroidImages(itemId = id),
    )
}
