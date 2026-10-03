package com.zhangwenkang.cinefin.repository

import androidx.paging.PagingData
import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidPerson
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidTag
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserConfiguration

interface JellyfinRepository {
    suspend fun getPublicSystemInfo(): PublicSystemInfo

    suspend fun getUserViews(): List<BaseItemDto>

    suspend fun getEpisode(itemId: UUID): FindroidEpisode

    suspend fun getMovie(itemId: UUID): FindroidMovie

    suspend fun getShow(itemId: UUID): FindroidShow

    suspend fun getSeason(itemId: UUID): FindroidSeason

    suspend fun getLibraries(): List<FindroidCollection>

    suspend fun getItem(itemId: UUID): FindroidItem?

    suspend fun getItems(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        startIndex: Int? = null,
        limit: Int? = null,
        /** W54-B 筛选（funnel）：已看 / 未看 / 收藏，映射到 Jellyfin `filters`。 */
        filters: List<ItemFilter>? = null,
        /** W54-B 库内「类型」过滤（按名称）。 */
        genres: List<String>? = null,
        /** W54-B 库内「制片发行商」过滤（按名称）。 */
        studios: List<String>? = null,
    ): List<FindroidItem>

    suspend fun getItemsPaging(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        sortBy: SortBy = SortBy.defaultValue,
        sortOrder: SortOrder = SortOrder.ASCENDING,
        filters: List<ItemFilter>? = null,
        genres: List<String>? = null,
        studios: List<String>? = null,
    ): Flow<PagingData<FindroidItem>>

    /**
     * 库内容页条目计数（W54-B）：只取 `TotalRecordCount`（`limit = 1` + 由服务器返回总数）， 供工具行「1-94 /
     * 94」使用；与网格共用同一组过滤条件，保证计数与列表同源。
     */
    suspend fun getItemCount(
        parentId: UUID? = null,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = false,
        filters: List<ItemFilter>? = null,
        genres: List<String>? = null,
        studios: List<String>? = null,
    ): Int

    /**
     * 库内「建议」（W54-B）：`SortBy=Random` 的库内随机抽样。
     *
     * 说明：SDK 1.8.12 的 `/Items/Suggestions` 没有 `parentId` 参数（官方 OpenAPI stable 同样没有），
     * 无法做「库内建议」，因此用库内随机抽样落地同一 IA 位置。
     */
    suspend fun getLibrarySuggestions(
        parentId: UUID,
        includeTypes: List<BaseItemKind>?,
        limit: Int = 24,
    ): List<FindroidItem>

    /** 「即将播出」（W54-B，仅剧集库）：`/Shows/Upcoming?parentId=`，Jellyfin 官方同名 tab 的同一接口。 */
    suspend fun getUpcomingEpisodes(parentId: UUID, limit: Int = 24): List<FindroidItem>

    /** 库内「类型」（Genre）列表（W54-B）：`/Genres?parentId=`。 */
    suspend fun getGenres(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>? = null,
    ): List<FindroidTag>

    /** 库内「制片发行商」（Studio）列表（W54-B）：`/Studios?parentId=`。 */
    suspend fun getStudios(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>? = null,
    ): List<FindroidTag>

    suspend fun getPerson(personId: UUID): FindroidPerson

    suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>? = null,
        recursive: Boolean = true,
    ): List<FindroidItem>

    suspend fun getFavoriteItems(): List<FindroidItem>

    suspend fun getSearchItems(query: String): List<FindroidItem>

    suspend fun getSuggestions(): List<FindroidItem>

    suspend fun getResumeItems(): List<FindroidItem>

    suspend fun getLatestMedia(parentId: UUID): List<FindroidItem>

    suspend fun getSeasons(seriesId: UUID, offline: Boolean = false): List<FindroidSeason>

    suspend fun getNextUp(seriesId: UUID? = null): List<FindroidEpisode>

    suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>? = null,
        startItemId: UUID? = null,
        limit: Int? = null,
        offline: Boolean = false,
    ): List<FindroidEpisode>

    suspend fun getMediaSources(itemId: UUID, includePath: Boolean = false): List<FindroidSource>

    suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String

    suspend fun getSegments(itemId: UUID): List<FindroidSegment>

    suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray?

    suspend fun postCapabilities()

    suspend fun postPlaybackStart(itemId: UUID)

    suspend fun postPlaybackStop(itemId: UUID, positionTicks: Long, playedPercentage: Int)

    suspend fun postPlaybackProgress(itemId: UUID, positionTicks: Long, isPaused: Boolean)

    suspend fun markAsFavorite(itemId: UUID)

    suspend fun unmarkAsFavorite(itemId: UUID)

    suspend fun markAsPlayed(itemId: UUID)

    suspend fun markAsUnplayed(itemId: UUID)

    fun getBaseUrl(): String

    /** W50：当前会话访问令牌（自研下载引擎请求头用；离线 / 未登录为 null）。 */
    fun getAccessToken(): String?

    suspend fun updateDeviceName(name: String)

    suspend fun getUserConfiguration(): UserConfiguration?

    /**
     * 当前登录账号是否为服务器管理员。
     *
     * 界面用它来决定是否显示「服务器控制台」这类只有管理员能用的入口； 服务器端仍会二次校验权限，这里只是不把无权限的入口摆在用户面前。
     */
    suspend fun isCurrentUserAdministrator(): Boolean

    suspend fun getDownloads(): List<FindroidItem>

    /** W34 下载层级：单个条目的主图 URL（`/Items/{id}/Images/Primary?tag=…`）；无图返回 null。 */
    suspend fun getPrimaryImageUrl(itemId: UUID): String?

    /** W34 下载层级：本地已下载剧集的节目 / 季归属（离线读库，不联网）。 */
    suspend fun getDownloadedEpisodeHierarchy(): List<DownloadedEpisodeHierarchy>

    /**
     * W34 下载层级：主库音频快照（id → 专辑 / 艺人）。
     *
     * 音乐曲目在本地库里只按 movies 表存标题，专辑信息只能回主库取；失败返回空表（层级退化为单条）。
     */
    suspend fun getMusicTrackMetadata(): Map<UUID, MusicTrackMetadata>

    fun getUserId(): UUID
}
