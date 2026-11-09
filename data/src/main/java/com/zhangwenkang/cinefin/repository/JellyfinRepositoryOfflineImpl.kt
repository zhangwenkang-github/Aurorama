package com.zhangwenkang.cinefin.repository

import android.content.Context
import androidx.paging.PagingData
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidEpisodeDto
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.FindroidPerson
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidShowDto
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidTag
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.models.toFindroidEpisode
import com.zhangwenkang.cinefin.models.toFindroidMovie
import com.zhangwenkang.cinefin.models.toFindroidSeason
import com.zhangwenkang.cinefin.models.toFindroidSegment
import com.zhangwenkang.cinefin.models.toFindroidShow
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness
import com.zhangwenkang.cinefin.repository.catalog.CatalogHomeData
import com.zhangwenkang.cinefin.repository.catalog.CatalogHomeQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogItemsPageQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogLibrariesQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogMovieQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome
import com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort
import com.zhangwenkang.cinefin.repository.catalog.CatalogScope
import com.zhangwenkang.cinefin.repository.catalog.CatalogScopeResolver
import com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery
import com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.UserConfiguration

class JellyfinRepositoryOfflineImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    /** W37：本地媒体库（离线模式同样可播本机文件夹里的媒体）。 */
    private val localLibrary: LocalLibraryRepository,
) : JellyfinRepository, CatalogReadPort {
    private var offlineMovieMapper: suspend (FindroidMovieDto, UUID) -> FindroidMovie =
        { dto, userId ->
            dto.toFindroidMovie(database, userId)
        }
    private var offlineShowMapper: suspend (FindroidShowDto, UUID) -> FindroidShow =
        { dto, userId ->
            dto.toFindroidShow(database, userId)
        }
    private var offlineEpisodeMapper: suspend (FindroidEpisodeDto, UUID) -> FindroidEpisode =
        { dto, userId ->
            dto.toFindroidEpisode(database, userId)
        }

    private val catalogScopeResolver =
        CatalogScopeResolver(
            appPreferences = appPreferences,
            database = database,
            principalContext = CatalogPrincipalContext.SAVED_OFFLINE_ACCOUNT,
        )

    internal constructor(
        context: Context,
        jellyfinApi: JellyfinApi,
        database: ServerDatabaseDao,
        appPreferences: AppPreferences,
        localLibrary: LocalLibraryRepository,
        offlineMovieMapper: suspend (FindroidMovieDto, UUID) -> FindroidMovie,
        offlineShowMapper: suspend (FindroidShowDto, UUID) -> FindroidShow,
        offlineEpisodeMapper: suspend (FindroidEpisodeDto, UUID) -> FindroidEpisode,
    ) : this(
        context = context,
        jellyfinApi = jellyfinApi,
        database = database,
        appPreferences = appPreferences,
        localLibrary = localLibrary,
    ) {
        this.offlineMovieMapper = offlineMovieMapper
        this.offlineShowMapper = offlineShowMapper
        this.offlineEpisodeMapper = offlineEpisodeMapper
    }

    override suspend fun getPublicSystemInfo(): PublicSystemInfo {
        throw Exception("System info not available in offline mode")
    }

    override suspend fun getMovie(itemId: UUID): FindroidMovie =
        withContext(Dispatchers.IO) {
            localLibrary.syntheticMovie(itemId)?.let {
                return@withContext it
            }
            database.getMovie(itemId).toFindroidMovie(database, jellyfinApi.userId)
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        withContext(Dispatchers.IO) {
            database.getShow(itemId).toFindroidShow(database, jellyfinApi.userId)
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        withContext(Dispatchers.IO) {
            database.getSeason(itemId).toFindroidSeason(database, jellyfinApi.userId)
        }

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        withContext(Dispatchers.IO) {
            database.getEpisode(itemId).toFindroidEpisode(database, jellyfinApi.userId)
        }

    override suspend fun getLibraries(): List<FindroidCollection> {
        return emptyList()
    }

    override suspend fun getItem(itemId: UUID): FindroidItem? {
        // W37：本地媒体库条目在离线模式下也要能取到（播放页信息回退）。
        return localLibrary.syntheticMovie(itemId)
    }

    override suspend fun getItems(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        startIndex: Int?,
        limit: Int?,
        filters: List<ItemFilter>?,
        genres: List<String>?,
        studios: List<String>?,
    ): List<FindroidItem> {
        return emptyList()
    }

    override suspend fun getItemsPaging(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        sortBy: SortBy,
        sortOrder: SortOrder,
        filters: List<ItemFilter>?,
        genres: List<String>?,
        studios: List<String>?,
    ): Flow<PagingData<FindroidItem>> {
        TODO("Not yet implemented")
    }

    /** 离线模式没有服务器库内容，计数固定 0（库内容页在离线时不显示范围计数）。 */
    override suspend fun getItemCount(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        filters: List<ItemFilter>?,
        genres: List<String>?,
        studios: List<String>?,
    ): Int = 0

    override suspend fun getLibrarySuggestions(
        parentId: UUID,
        includeTypes: List<BaseItemKind>?,
        limit: Int,
    ): List<FindroidItem> = emptyList()

    override suspend fun getUpcomingEpisodes(parentId: UUID, limit: Int): List<FindroidItem> =
        emptyList()

    override suspend fun getGenres(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidTag> = emptyList()

    override suspend fun getStudios(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidTag> = emptyList()

    override suspend fun getPerson(personId: UUID): FindroidPerson {
        TODO("Not yet implemented")
    }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<FindroidItem> {
        TODO("Not yet implemented")
    }

    /**
     * 离线模式的「我的收藏」没有跨库查询能力：本机索引只缓存已下载条目、也没有收藏查询入口， 返回空表由页面显示空态（收藏的写入 / 同步仍走 [markAsFavorite] /
     * [unmarkAsFavorite]）。
     */
    override suspend fun getFavoriteItems(
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): List<FindroidItem> = emptyList()

    override suspend fun currentScope(): CatalogScope? = catalogScopeResolver.resolve()

    override suspend fun search(query: CatalogSearchQuery): CatalogReadOutcome<List<FindroidItem>> {
        val scope = query.frozenScope
        if (currentScope() != scope) return CatalogReadOutcome.IdentityUnavailable
        if (query.scope == SearchScope.MUSIC || query.scope == SearchScope.BOOK) {
            return CatalogReadOutcome.Available(
                CatalogSnapshot(
                    scope = scope,
                    contentGeneration = 0L,
                    freshness = CatalogFreshness.LOCAL_QUERY,
                    observedAtEpochMs = System.currentTimeMillis(),
                    value = emptyList(),
                )
            )
        }
        return withContext(Dispatchers.IO) {
            if (currentScope() != scope) {
                return@withContext CatalogReadOutcome.IdentityUnavailable
            }
            val serverId = scope.origin.serverId
            val userId = scope.origin.userId
            val movies = database.searchMovies(serverId, query.term)
            val shows = database.searchShows(serverId, query.term)
            val episodes = database.searchEpisodes(serverId, query.term)
            if (currentScope() != scope) {
                return@withContext CatalogReadOutcome.IdentityUnavailable
            }
            val value =
                movies.map { offlineMovieMapper(it, userId) } +
                    shows.map { offlineShowMapper(it, userId) } +
                    episodes.map { offlineEpisodeMapper(it, userId) }
            if (currentScope() != scope) {
                return@withContext CatalogReadOutcome.IdentityUnavailable
            }
            CatalogReadOutcome.Available(
                CatalogSnapshot(
                    scope = scope,
                    contentGeneration = 0L,
                    freshness = CatalogFreshness.LOCAL_QUERY,
                    observedAtEpochMs = System.currentTimeMillis(),
                    value = value,
                )
            )
        }
    }

    override suspend fun home(query: CatalogHomeQuery): CatalogReadOutcome<CatalogHomeData> =
        withContext(Dispatchers.IO) {
            val scope = query.frozenScope
            if (currentScope() != scope) return@withContext CatalogReadOutcome.IdentityUnavailable
            val resumeWatching =
                if (query.includeContinueWatching) {
                    val movies =
                        database
                            .getMoviesByServerId(scope.origin.serverId)
                            .map { offlineMovieMapper(it, scope.origin.userId) }
                            .filter { it.playbackPositionTicks > 0L }
                    val episodes =
                        database
                            .getEpisodesByServerId(scope.origin.serverId)
                            .map { offlineEpisodeMapper(it, scope.origin.userId) }
                            .filter { it.playbackPositionTicks > 0L }
                    movies + episodes
                } else {
                    emptyList()
                }
            if (currentScope() != scope) return@withContext CatalogReadOutcome.IdentityUnavailable
            offlineAvailable(
                scope = scope,
                value = CatalogHomeData(resumeWatching = resumeWatching),
            )
        }

    override suspend fun libraries(
        query: CatalogLibrariesQuery
    ): CatalogReadOutcome<List<FindroidCollection>> {
        val scope = query.frozenScope
        if (currentScope() != scope) return CatalogReadOutcome.IdentityUnavailable
        return offlineAvailable(scope = scope, value = emptyList())
    }

    override suspend fun itemsPage(
        query: CatalogItemsPageQuery
    ): CatalogReadOutcome<List<FindroidItem>> {
        val scope = query.frozenScope
        if (currentScope() != scope) return CatalogReadOutcome.IdentityUnavailable
        return offlineAvailable(scope = scope, value = emptyList())
    }

    override suspend fun movie(query: CatalogMovieQuery): CatalogReadOutcome<FindroidMovie> =
        withContext(Dispatchers.IO) {
            val scope = query.frozenScope
            if (currentScope() != scope) return@withContext CatalogReadOutcome.IdentityUnavailable
            val movie =
                localLibrary.syntheticMovie(query.itemId)
                    ?: offlineMovieMapper(
                        database.getMovie(query.itemId),
                        scope.origin.userId,
                    )
            if (currentScope() != scope) return@withContext CatalogReadOutcome.IdentityUnavailable
            offlineAvailable(scope = scope, value = movie)
        }

    private fun <T> offlineAvailable(
        scope: CatalogScope,
        value: T,
    ): CatalogReadOutcome<T> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = scope,
                contentGeneration = 0L,
                freshness = CatalogFreshness.LOCAL_QUERY,
                observedAtEpochMs = System.currentTimeMillis(),
                value = value,
            )
        )

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        withContext(Dispatchers.IO) {
            database.getSeasonsByShowId(seriesId).map {
                it.toFindroidSeason(database, jellyfinApi.userId)
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> {
        return withContext(Dispatchers.IO) {
            val serverId =
                appPreferences.getValue(appPreferences.currentServer)
                    ?: return@withContext emptyList()
            val result = mutableListOf<FindroidEpisode>()
            val shows =
                database.getShowsByServerId(serverId).filter {
                    if (seriesId != null) it.id == seriesId else true
                }
            for (show in shows) {
                val episodes =
                    database.getEpisodesByShowId(show.id).map {
                        it.toFindroidEpisode(database, jellyfinApi.userId)
                    }
                val indexOfLastPlayed = episodes.indexOfLast { it.played }
                if (indexOfLastPlayed == -1) {
                    result.add(episodes.first())
                } else {
                    episodes.getOrNull(indexOfLastPlayed + 1)?.let { result.add(it) }
                }
            }
            result.filter { it.playbackPositionTicks == 0L }
        }
    }

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> =
        withContext(Dispatchers.IO) {
            val items =
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toFindroidEpisode(database, jellyfinApi.userId)
                }
            if (startItemId != null) return@withContext items.dropWhile { it.id != startItemId }
            items
        }

    /** W34：离线实现没有服务端图片可拉，直接返回 null（下载页用类型图标占位）。 */
    override suspend fun getPrimaryImageUrl(itemId: UUID): String? = null

    override suspend fun getDownloadedEpisodeHierarchy(): List<DownloadedEpisodeHierarchy> =
        withContext(Dispatchers.IO) {
            runCatching { database.getDownloadedEpisodeHierarchy() }.getOrElse { emptyList() }
        }

    override suspend fun getEpisodeHierarchyWithSources(): List<DownloadedEpisodeHierarchy> =
        withContext(Dispatchers.IO) {
            runCatching { database.getEpisodeHierarchyWithSources() }.getOrElse { emptyList() }
        }

    /** W34：离线实现拿不到主库音频元数据，返回空表（层级退化为单条）。 */
    override suspend fun getMusicTrackMetadata(): Map<UUID, MusicTrackMetadata> = emptyMap()

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
        startPositionTicks: Long,
    ): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            localLibrary.syntheticSources(itemId)?.let {
                return@withContext it
            }
            database.getSources(itemId).map { it.toFindroidSource(database) }
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String {
        TODO("Not yet implemented")
    }

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) { database.getSegments(itemId).map { it.toFindroidSegment() } }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                val sources =
                    File(context.filesDir, "trickplay/$itemId").listFiles()
                        ?: return@withContext null
                File(sources.first(), index.toString()).readBytes()
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun postCapabilities() {}

    override suspend fun postPlaybackStart(itemId: UUID) {}

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        // W37：本地媒体库条目没有服务器条目，跳过用户数据写入。
        if (localLibrary.isLocalItem(itemId)) return
        withContext(Dispatchers.IO) {
            // W36：无账号离线模式没有可写的用户，播放进度只保留在内存路径（不落库）。
            val userId = jellyfinApi.userId ?: return@withContext
            when {
                playedPercentage < 10 -> {
                    database.setPlaybackPositionTicks(itemId, userId, 0)
                    database.setPlayed(userId, itemId, false)
                }
                playedPercentage > 90 -> {
                    database.setPlaybackPositionTicks(itemId, userId, 0)
                    database.setPlayed(userId, itemId, true)
                }
                else -> {
                    database.setPlaybackPositionTicks(itemId, userId, positionTicks)
                    database.setPlayed(userId, itemId, false)
                }
            }
            database.setUserDataToBeSynced(userId, itemId, true)
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        // W37：本地媒体库条目没有服务器条目，跳过用户数据写入。
        if (localLibrary.isLocalItem(itemId)) return
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext
            database.setPlaybackPositionTicks(itemId, userId, positionTicks)
            database.setUserDataToBeSynced(userId, itemId, true)
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext
            database.setFavorite(userId, itemId, true)
            database.setUserDataToBeSynced(userId, itemId, true)
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext
            database.setFavorite(userId, itemId, false)
            database.setUserDataToBeSynced(userId, itemId, true)
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext
            database.setPlayed(userId, itemId, true)
            database.setPlaybackPositionTicks(itemId, userId, 0)
            database.setUserDataToBeSynced(userId, itemId, true)
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext
            database.setPlayed(userId, itemId, false)
            database.setUserDataToBeSynced(userId, itemId, true)
        }
    }

    /** W69：离线实现没有会话级元数据缓存，空操作（下拉刷新本就直读本地库）。 */
    override fun invalidateMetadataCache() = Unit

    override fun getBaseUrl(): String {
        return ""
    }

    override fun getAccessToken(): String? = null

    override suspend fun updateDeviceName(name: String) {
        TODO("Not yet implemented")
    }

    override suspend fun getUserConfiguration(): UserConfiguration? {
        return null
    }

    override suspend fun isCurrentUserAdministrator(): Boolean {
        // 离线模式不提供服务器控制台
        return false
    }

    override suspend fun getDownloads(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            val serverId =
                appPreferences.getValue(appPreferences.currentServer)
                    ?: return@withContext emptyList()
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database.getMoviesByServerId(serverId).map {
                    it.toFindroidMovie(database, jellyfinApi.userId)
                }
            )
            items.addAll(
                database.getShowsByServerId(serverId).map {
                    it.toFindroidShow(database, jellyfinApi.userId)
                }
            )
            // W36：与在线实现一致——补「已下载剧集」的节目 / 季归属，离线下载页也能组织 节目→季→剧集 层级。
            items.addAll(
                database.getCompletedEpisodeHierarchy().mapNotNull { hierarchy ->
                    runCatching {
                        database
                            .getEpisode(hierarchy.episodeId)
                            ?.toFindroidEpisode(database, jellyfinApi.userId)
                    }
                        .getOrNull()
                }
            )
            items
        }

    override fun getUserId(): UUID = jellyfinApi.userId ?: error("离线模式没有登录账号，无法执行需要账号的写操作")
}
