package com.zhangwenkang.cinefin.models

import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import org.jellyfin.sdk.model.DateTime
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.LocationType
import org.jellyfin.sdk.model.api.PlayAccess

data class FindroidEpisode(
    override val id: UUID,
    override val name: String,
    override val originalTitle: String?,
    override val overview: String,
    val indexNumber: Int,
    val indexNumberEnd: Int?,
    val parentIndexNumber: Int,
    override val sources: List<FindroidSource>,
    override val played: Boolean,
    override val favorite: Boolean,
    override val canPlay: Boolean,
    override val canDownload: Boolean,
    override val runtimeTicks: Long,
    override val playbackPositionTicks: Long,
    val premiereDate: DateTime?,
    val seriesId: UUID,
    val seriesName: String,
    val seasonId: UUID,
    val seasonName: String?,
    val communityRating: Float?,
    val people: List<FindroidItemPerson>,
    override val unplayedItemCount: Int? = null,
    val missing: Boolean = false,
    override val images: FindroidImages,
    override val chapters: List<FindroidChapter>,
    override val trickplayInfo: Map<String, FindroidTrickplayInfo>?,
) : FindroidItem, FindroidSources

suspend fun BaseItemDto.toFindroidEpisode(
    jellyfinRepository: JellyfinRepository,
    database: ServerDatabaseDao? = null,
    /**
     * W73（#10）：服务端「未知季」（无季号）分组里的集**不返回 `SeasonId`**——按季取集时由调用方把请求用的 seasonId 作为回落值传进来；否则
     * `seasonId!!` 抛 NPE、被下面的 catch 吞成 null、整条数据被 `mapNotNull` 丢掉 （真机表现：季详情没有集、播放键置灰）。
     */
    fallbackSeasonId: UUID? = null,
): FindroidEpisode? {
    val resolvedSeasonId = resolveEpisodeSeasonId(seasonId, fallbackSeasonId) ?: return null
    val sources = mutableListOf<FindroidSource>()
    sources.addAll(mediaSources?.map { it.toFindroidSource(jellyfinRepository, id) } ?: emptyList())
    if (database != null) {
        sources.addAll(database.getSources(id).map { it.toFindroidSource(database) })
    }
    return try {
        FindroidEpisode(
            id = id,
            name = name.orEmpty(),
            originalTitle = originalTitle,
            overview = overview.orEmpty(),
            indexNumber = indexNumber ?: 0,
            indexNumberEnd = indexNumberEnd,
            parentIndexNumber = parentIndexNumber ?: 0,
            sources = sources,
            played = userData?.played == true,
            favorite = userData?.isFavorite == true,
            canPlay = playAccess != PlayAccess.NONE,
            canDownload = canDownload == true,
            runtimeTicks = runTimeTicks ?: 0,
            playbackPositionTicks = userData?.playbackPositionTicks ?: 0L,
            premiereDate = premiereDate,
            seriesId = seriesId!!,
            seriesName = seriesName.orEmpty(),
            seasonId = resolvedSeasonId,
            seasonName = seasonName,
            communityRating = communityRating,
            people = people?.map { it.toFindroidPerson(jellyfinRepository) } ?: emptyList(),
            missing = locationType == LocationType.VIRTUAL,
            images = toFindroidImages(jellyfinRepository),
            chapters = toFindroidChapters(),
            trickplayInfo =
                trickplay?.mapValues { it.value[it.value.keys.max()]!!.toFindroidTrickplayInfo() },
        )
    } catch (_: NullPointerException) {
        null
    }
}

/** 集的归属季 ID 取值口径（纯函数，便于单测）：服务器给的值优先，缺省时用调用方请求的季 ID 回落。 两者都没有（既不是按季取、服务端也没给）时返回 null，调用方据此丢弃该条。 */
internal fun resolveEpisodeSeasonId(serverSeasonId: UUID?, requestedSeasonId: UUID?): UUID? =
    serverSeasonId ?: requestedSeasonId

suspend fun FindroidEpisodeDto.toFindroidEpisode(
    database: ServerDatabaseDao,
    userId: UUID?,
): FindroidEpisode {
    // W36：无账号离线模式下没有 userId，播放状态按未观看处理，不再要求登录会话。
    val userData = userId?.let { database.getUserDataOrCreateNew(id, it) }
    val sources = database.getSources(id).map { it.toFindroidSource(database) }
    val trickplayInfos = mutableMapOf<String, FindroidTrickplayInfo>()
    for (source in sources) {
        database.getTrickplayInfo(source.id)?.toFindroidTrickplayInfo()?.let {
            trickplayInfos[source.id] = it
        }
    }
    return FindroidEpisode(
        id = id,
        name = name,
        originalTitle = "",
        overview = overview,
        indexNumber = indexNumber,
        indexNumberEnd = indexNumberEnd,
        parentIndexNumber = parentIndexNumber,
        sources = sources,
        played = userData?.played ?: false,
        favorite = userData?.favorite ?: false,
        canPlay = true,
        canDownload = false,
        runtimeTicks = runtimeTicks,
        playbackPositionTicks = userData?.playbackPositionTicks ?: 0,
        premiereDate = premiereDate,
        seriesId = seriesId,
        seriesName = seriesName,
        seasonId = seasonId,
        seasonName = null,
        communityRating = communityRating,
        people = emptyList(),
        images = toLocalFindroidImages(itemId = id),
        chapters = chapters ?: emptyList(),
        trickplayInfo = trickplayInfos,
    )
}
