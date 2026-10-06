package com.zhangwenkang.cinefin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
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
import com.zhangwenkang.cinefin.models.toFindroidCollection
import com.zhangwenkang.cinefin.models.toFindroidEpisode
import com.zhangwenkang.cinefin.models.toFindroidItem
import com.zhangwenkang.cinefin.models.toFindroidMovie
import com.zhangwenkang.cinefin.models.toFindroidPerson
import com.zhangwenkang.cinefin.models.toFindroidSeason
import com.zhangwenkang.cinefin.models.toFindroidSegment
import com.zhangwenkang.cinefin.models.toFindroidShow
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.PlayerDecodeFallback
import com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceOptionsDto
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder as ItemSortOrder
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile
import org.jellyfin.sdk.model.api.UserConfiguration
import timber.log.Timber

class JellyfinRepositoryImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    /** W37：本地媒体库（合成 LOCAL 源，播放链路零改动复用）。 */
    private val localLibrary: LocalLibraryRepository,
) : JellyfinRepository {
    /** W34：下载页轮询会重复取同一批封面 / 专辑元数据，用短 TTL 缓存避免每轮都打服务器。 */
    private val primaryImageUrls = java.util.concurrent.ConcurrentHashMap<UUID, String>()
    private val imageUrlCacheAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val musicTrackCache =
        java.util.concurrent.atomic.AtomicReference<Map<UUID, MusicTrackMetadata>>(emptyMap())
    private val musicTrackCacheAt = java.util.concurrent.atomic.AtomicLong(0L)

    /** W62：库直接子项稳定计数（该服务器 `ChildCount` 随机；见 [stableLibraryItemCount]）。 */
    private val libraryItemCountCache = LibraryItemCountCache(CACHE_TTL_MS)

    /** W69：会话级元数据缓存（按服务器地址 + 用户隔离；TTL 规则见 [MetadataCacheRules]）。 */
    private val metadataCache =
        MetadataCache(
            namespace = { "${jellyfinApi.api.baseUrl.orEmpty()}|${jellyfinApi.userId ?: "-"}" }
        )

    /** W69b：同 key 并发请求合并（去重）。 */
    private val inFlightRequests = InFlightRequests()

    /**
     * W69：元数据读取的统一入口（stale-while-revalidate 的读侧）。
     *
     * TTL 内命中缓存直接返回（不发请求，页面不进入加载态）；过期 / 缺失才执行 [fetch]，成功后回填。
     * 页面在刷新期间继续渲染自己保留的内容（不清列表、不置空图片），新数据回来后就地替换。
     */
    private suspend fun <T> cachedMetadata(
        key: String,
        ttlMs: Long = MetadataCacheRules.DEFAULT_TTL_MS,
        allowJoin: Boolean = true,
        fetch: suspend () -> T,
    ): T {
        val now = metadataCache.now()
        metadataCache.get<T>(key)?.let { entry ->
            if (MetadataCacheRules.isFresh(entry.fetchedAtMs, metadataCache.now(), ttlMs)) {
                Timber.d("metadata cache hit: %s", key)
                return entry.value
            }
            // W69b：过期但仍在冷却窗口内——先复用旧值，避免返回页面 / 重进页面反复触发刷新。
            if (!metadataCache.canStartFetch(key, now)) {
                Timber.d("metadata cache hit (cooldown): %s", key)
                return entry.value
            }
        }
        // W69b：同 key 并发去重——第一个调用方执行请求，其余等待同一结果。
        if (allowJoin) {
            val (deferred, owner) = inFlightRequests.join(key)
            if (!owner) {
                Timber.d("metadata cache join in-flight: %s", key)
                return try {
                    @Suppress("UNCHECKED_CAST") deferred.await() as T
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (_: Throwable) {
                    // 领头请求失败：等待方自己补一次（不受别人失败牵连）。
                    cachedMetadata(key, ttlMs, allowJoin = false, fetch = fetch)
                }
            }
            return try {
                Timber.d("metadata cache miss (refreshing): %s", key)
                metadataCache.markFetchStarted(key, now)
                val value = fetch()
                metadataCache.put(key, value)
                deferred.complete(value)
                value
            } catch (throwable: Throwable) {
                deferred.completeExceptionally(throwable)
                throw throwable
            } finally {
                inFlightRequests.finish(key, deferred)
            }
        }
        Timber.d("metadata cache miss (refreshing): %s", key)
        metadataCache.markFetchStarted(key, now)
        val value = fetch()
        metadataCache.put(key, value)
        return value
    }

    /** W69：下拉刷新 / 用户主动刷新 = 强制失效缓存，后续读取直打服务器。 */
    override fun invalidateMetadataCache() {
        metadataCache.invalidateAll()
        libraryItemCountCache.clear()
    }

    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        withContext(Dispatchers.IO) { jellyfinApi.systemApi.getPublicSystemInfo().content }

    override suspend fun getUserViews(): List<BaseItemDto> =
        cachedMetadata(MetadataCacheKeys.VIEWS) {
            withContext(Dispatchers.IO) {
                jellyfinApi.viewsApi.getUserViews(jellyfinApi.userId!!).content.items
            }
        }

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        cachedMetadata(MetadataCacheKeys.episode(itemId)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.userLibraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                    .toFindroidEpisode(this@JellyfinRepositoryImpl, database)!!
            }
        }

    override suspend fun getMovie(itemId: UUID): FindroidMovie =
        cachedMetadata(MetadataCacheKeys.movie(itemId)) {
            withContext(Dispatchers.IO) {
                localLibrary.syntheticMovie(itemId)?.let {
                    return@withContext it
                }
                jellyfinApi.userLibraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                    .toFindroidMovie(this@JellyfinRepositoryImpl, database)
            }
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        cachedMetadata(MetadataCacheKeys.show(itemId)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.userLibraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                    .toFindroidShow(this@JellyfinRepositoryImpl)
            }
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        cachedMetadata(MetadataCacheKeys.season(itemId)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.userLibraryApi
                    .getItem(itemId, jellyfinApi.userId!!)
                    .content
                    .toFindroidSeason(this@JellyfinRepositoryImpl)
            }
        }

    override suspend fun getLibraries(): List<FindroidCollection> =
        cachedMetadata(MetadataCacheKeys.LIBRARIES) {
            withContext(Dispatchers.IO) {
                val libraries =
                    jellyfinApi.itemsApi
                        .getItems(
                            jellyfinApi.userId!!,
                            // W8-R3：媒体库卡片要显示"共 N 个项目"——库列表默认不返回 ChildCount，显式要一次
                            // （W62 起只作为稳定计数缺失时的回退值，不再直接上屏）。
                            fields = listOf(ItemFields.CHILD_COUNT),
                        )
                        .content
                        .items
                        .mapNotNull { it.toFindroidCollection(this@JellyfinRepositoryImpl) }

                // W62：ChildCount 在该服务器（10.11.8）对 UserView 随机波动（W61 回归 + 本波只读探针复核），
                // 改用「按库直接子项查询」的稳定 TotalRecordCount，失败再回退 ChildCount。
                val stableCounts = libraryItemCounts(libraries.map { it.id })
                libraries.map { library ->
                    library.copy(
                        itemCount =
                            stableLibraryItemCount(stableCounts[library.id], library.itemCount)
                    )
                }
            }
        }

    /**
     * W62：并发取每个库的稳定条目数。
     *
     * 口径 = `parentId=<库>&recursive=false&limit=1` 的 `TotalRecordCount`（= 库内直接条目数，探针两次采样一致，
     * 且与进入库后内容页的总数一致）。命中 [libraryItemCountCache] 直接复用；单个库查询失败只影响它自己 （返回 null → 调用方回退 `ChildCount`）。
     */
    private suspend fun libraryItemCounts(libraryIds: List<UUID>): Map<UUID, Int> = coroutineScope {
        libraryIds
            .map { libraryId -> async { libraryItemCount(libraryId)?.let { libraryId to it } } }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    private suspend fun libraryItemCount(libraryId: UUID): Int? {
        libraryItemCountCache.get(libraryId)?.let {
            return it
        }
        val count =
            runCatching {
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        parentId = libraryId,
                        recursive = false,
                        limit = 1,
                    )
                    .content
                    .totalRecordCount
            }
                .onFailure { Timber.w(it, "读取库 %s 的稳定条目数失败，回退 ChildCount", libraryId) }
                .getOrNull() ?: return null
        libraryItemCountCache.put(libraryId, count)
        return count
    }

    override suspend fun getItem(itemId: UUID): FindroidItem? =
        cachedMetadata(MetadataCacheKeys.item(itemId)) {
            withContext(Dispatchers.IO) {
                localLibrary.syntheticMovie(itemId)?.let {
                    return@withContext it
                }
                jellyfinApi.userLibraryApi
                    .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                    .content
                    .toFindroidItem(this@JellyfinRepositoryImpl, database)
            }
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
    ): List<FindroidItem> =
        cachedMetadata(
            key =
                MetadataCacheKeys.items(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    sortBy = sortBy,
                    sortOrder = sortOrder,
                    startIndex = startIndex,
                    limit = limit,
                    filters = filters,
                    genres = genres,
                    studios = studios,
                ),
            // 分页页片比列表元数据更"活"：用更短的 TTL，下拉刷新与滚动重取都能及时看到新数据。
            ttlMs = MetadataCacheRules.PAGING_TTL_MS,
        ) {
            withContext(Dispatchers.IO) {
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = includeTypes,
                        recursive = recursive,
                        sortBy = listOf(ItemSortBy.fromName(sortBy.sortString)),
                        sortOrder = listOf(ItemSortOrder.fromName(sortOrder.sortString)),
                        startIndex = startIndex,
                        limit = limit,
                        filters = filters,
                        genres = genres,
                        studios = studios,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
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
        return Pager(
                config =
                    PagingConfig(
                        pageSize = ItemsPagingSource.PAGE_SIZE,
                        initialLoadSize = ItemsPagingSource.INITIAL_LOAD_SIZE,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    ItemsPagingSource(
                        this,
                        parentId,
                        includeTypes,
                        recursive,
                        sortBy,
                        sortOrder,
                        filters = filters,
                        genres = genres,
                        studios = studios,
                    )
                },
            )
            .flow
    }

    override suspend fun getItemCount(
        parentId: UUID?,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
        filters: List<ItemFilter>?,
        genres: List<String>?,
        studios: List<String>?,
    ): Int =
        cachedMetadata(
            MetadataCacheKeys.itemCount(
                parentId = parentId,
                includeTypes = includeTypes,
                recursive = recursive,
                filters = filters,
                genres = genres,
                studios = studios,
            )
        ) {
            withContext(Dispatchers.IO) {
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = includeTypes,
                        recursive = recursive,
                        filters = filters,
                        genres = genres,
                        studios = studios,
                        // 只要总数：拿 1 条即可让服务器照常计算 TotalRecordCount（limit = 0 的语义各版本不一致）。
                        limit = 1,
                        enableTotalRecordCount = true,
                    )
                    .content
                    .totalRecordCount
            }
        }

    override suspend fun getLibrarySuggestions(
        parentId: UUID,
        includeTypes: List<BaseItemKind>?,
        limit: Int,
    ): List<FindroidItem> =
        cachedMetadata(
            MetadataCacheKeys.librarySuggestions(
                parentId = parentId,
                includeTypes = includeTypes,
                limit = limit,
            )
        ) {
            withContext(Dispatchers.IO) {
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = includeTypes,
                        recursive = true,
                        sortBy = listOf(ItemSortBy.RANDOM),
                        limit = limit,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
        }

    override suspend fun getUpcomingEpisodes(parentId: UUID, limit: Int): List<FindroidItem> =
        cachedMetadata(MetadataCacheKeys.upcoming(parentId = parentId, limit = limit)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.showsApi
                    .getUpcomingEpisodes(jellyfinApi.userId!!, parentId = parentId, limit = limit)
                    .content
                    .items
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
        }

    override suspend fun getGenres(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidTag> =
        cachedMetadata(
            MetadataCacheKeys.genres(parentId = parentId, includeItemTypes = includeItemTypes)
        ) {
            withContext(Dispatchers.IO) {
                jellyfinApi.genresApi
                    .getGenres(
                        userId = jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = includeItemTypes,
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                    )
                    .content
                    .items
                    .mapNotNull { dto ->
                        val name = dto.name ?: return@mapNotNull null
                        FindroidTag(id = dto.id, name = name)
                    }
            }
        }

    override suspend fun getStudios(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidTag> =
        cachedMetadata(
            MetadataCacheKeys.studios(parentId = parentId, includeItemTypes = includeItemTypes)
        ) {
            withContext(Dispatchers.IO) {
                jellyfinApi.studiosApi
                    .getStudios(
                        userId = jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = includeItemTypes,
                    )
                    .content
                    .items
                    .mapNotNull { dto ->
                        val name = dto.name ?: return@mapNotNull null
                        FindroidTag(id = dto.id, name = name)
                    }
            }
        }

    override suspend fun getPerson(personId: UUID): FindroidPerson =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(personId, jellyfinApi.userId!!)
                .content
                .toFindroidPerson(this@JellyfinRepositoryImpl)
        }

    override suspend fun getPersonItems(
        personIds: List<UUID>,
        includeTypes: List<BaseItemKind>?,
        recursive: Boolean,
    ): List<FindroidItem> =
        cachedMetadata(
            MetadataCacheKeys.personItems(personIds = personIds, includeTypes = includeTypes)
        ) {
            withContext(Dispatchers.IO) {
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        personIds = personIds,
                        includeItemTypes = includeTypes,
                        recursive = recursive,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
        }

    override suspend fun getFavoriteItems(
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): List<FindroidItem> =
        cachedMetadata(MetadataCacheKeys.favorites(sortBy = sortBy, sortOrder = sortOrder)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        filters = listOf(ItemFilter.IS_FAVORITE),
                        includeItemTypes =
                            listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                        recursive = true,
                        sortBy = listOf(ItemSortBy.fromName(sortBy.sortString)),
                        sortOrder = listOf(ItemSortOrder.fromName(sortOrder.sortString)),
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
        }

    override suspend fun getSearchItems(query: String): List<FindroidItem> =
        cachedMetadata(MetadataCacheKeys.search(query)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        searchTerm = query,
                        includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                        recursive = true,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
        }

    override suspend fun getSuggestions(): List<FindroidItem> =
        cachedMetadata(MetadataCacheKeys.SUGGESTIONS) {
            withContext(Dispatchers.IO) {
                jellyfinApi.suggestionsApi
                    .getSuggestions(
                        jellyfinApi.userId!!,
                        limit = 6,
                        type = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
        }

    override suspend fun getResumeItems(includeItemTypes: List<BaseItemKind>): List<FindroidItem> =
        cachedMetadata(MetadataCacheKeys.resume(includeItemTypes)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.itemsApi
                    .getResumeItems(
                        jellyfinApi.userId!!,
                        limit = 12,
                        includeItemTypes = includeItemTypes,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
        }

    override suspend fun getLatestMedia(parentId: UUID): List<FindroidItem> =
        cachedMetadata(MetadataCacheKeys.latestMedia(parentId)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.userLibraryApi
                    .getLatestMedia(jellyfinApi.userId!!, parentId = parentId, limit = 16)
                    .content
                    .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
            }
        }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        cachedMetadata(MetadataCacheKeys.seasons(seriesId, offline)) {
            withContext(Dispatchers.IO) {
                if (!offline) {
                    jellyfinApi.showsApi
                        .getSeasons(seriesId, jellyfinApi.userId!!)
                        .content
                        .items
                        .map { it.toFindroidSeason(this@JellyfinRepositoryImpl) }
                } else {
                    database.getSeasonsByShowId(seriesId).map {
                        it.toFindroidSeason(database, jellyfinApi.userId!!)
                    }
                }
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =
        cachedMetadata(MetadataCacheKeys.nextUp(seriesId)) {
            withContext(Dispatchers.IO) {
                jellyfinApi.showsApi
                    .getNextUp(
                        jellyfinApi.userId!!,
                        limit = 24,
                        seriesId = seriesId,
                        enableResumable = false,
                    )
                    .content
                    .items
                    .mapNotNull { it.toFindroidEpisode(this@JellyfinRepositoryImpl) }
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
        cachedMetadata(
            MetadataCacheKeys.episodes(
                seriesId = seriesId,
                seasonId = seasonId,
                fields = fields,
                startItemId = startItemId,
                limit = limit,
                offline = offline,
            )
        ) {
            withContext(Dispatchers.IO) {
                if (!offline) {
                    jellyfinApi.showsApi
                        .getEpisodes(
                            seriesId,
                            jellyfinApi.userId!!,
                            seasonId = seasonId,
                            fields = fields,
                            startItemId = startItemId,
                            limit = limit,
                        )
                        .content
                        .items
                        .mapNotNull { it.toFindroidEpisode(this@JellyfinRepositoryImpl, database) }
                } else {
                    database.getEpisodesBySeasonId(seasonId).map {
                        it.toFindroidEpisode(database, jellyfinApi.userId!!)
                    }
                }
            }
        }

    override suspend fun getMediaSources(
        itemId: UUID,
        includePath: Boolean,
        startPositionTicks: Long,
    ): List<FindroidSource> =
        withContext(Dispatchers.IO) {
            // W37：本地媒体库条目 → 合成 LOCAL 源（content:// 文档 URI，不落 sources 表）。
            localLibrary.syntheticSources(itemId)?.let {
                return@withContext it
            }
            /*
             * 码率档位（W12 反馈 B）：0 = 自动（不设上限，服务器自行判断直连 / 转码）、
             * -1 = 原始画质（只直连，明确禁用转码）、>0 = 具体 Mbps（按该上限请求服务器转码）。
             * 选具体码率且片源超过上限时，服务器会在 mediaSources 里给出 transcodingUrl，
             * 播放侧（PlaylistManager）优先使用它——功能与 Jellyfin 官方客户端的「质量」档位一致。
             */
            val streamingBitrate = appPreferences.getValue(appPreferences.playerStreamingBitrate)
            val maxStreamingBitrate =
                PlayerStreamingQuality.maxStreamingBitrate(streamingBitrate).toInt()
            val transcodingEnabled = PlayerStreamingQuality.transcodingEnabled(streamingBitrate)
            /*
             * W16 解码回退链第 2 档：本地硬解报「解码能力不足」后，按回退档位强制服务器转码——
             * 明确不允许直连 / 直传（enableDirectPlay / enableDirectStream = false），否则服务器会
             * 认为参数没超限继续 DirectPlay，客户端还是解不了。用户显式选码率后档位会清零。
             */
            val forceTranscode =
                PlayerDecodeFallback.forcesServerTranscode(
                    appPreferences.getValue(appPreferences.playerDecodeFallbackStage)
                )
            /*
             * 选了具体码率就必须声明转码能力：否则服务器认为「这个客户端不会播转码流」，
             * 于是无视 maxStreamingBitrate 继续直连（Pad 5 真机实测：3 Mbps 档仍是 DirectPlay）。
             * 只声明 HLS + H.264/AAC（Jellyfin 官方客户端的流式档位），音频直通优先级由服务器决定。
             */
            val transcodingProfiles =
                if (
                    PlayerStreamingQuality.requestsTranscoding(streamingBitrate) || forceTranscode
                ) {
                    listOf(
                        TranscodingProfile(
                            container = "ts",
                            type = DlnaProfileType.VIDEO,
                            videoCodec = "h264",
                            audioCodec = "aac,mp3,ac3,opus",
                            protocol = MediaStreamProtocol.HLS,
                            context = EncodingContext.STREAMING,
                            enableSubtitlesInManifest = true,
                            conditions = emptyList(),
                        )
                    )
                } else {
                    emptyList()
                }
            Timber.d(
                "getMediaSources bitrate=%d maxStreamingBitrate=%d transcoding=%b forceTranscode=%b profiles=%d startTimeTicks=%d",
                streamingBitrate,
                maxStreamingBitrate,
                transcodingEnabled,
                forceTranscode,
                transcodingProfiles.size,
                startPositionTicks,
            )
            val sources = mutableListOf<FindroidSource>()
            sources.addAll(
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(
                        itemId,
                        PlaybackInfoDto(
                            userId = jellyfinApi.userId!!,
                            deviceProfile =
                                DeviceProfile(
                                    name = "Direct play all",
                                    maxStaticBitrate = maxStreamingBitrate,
                                    maxStreamingBitrate = maxStreamingBitrate,
                                    codecProfiles = emptyList(),
                                    containerProfiles = emptyList(),
                                    directPlayProfiles = emptyList(),
                                    transcodingProfiles = transcodingProfiles,
                                    subtitleProfiles =
                                        listOf(
                                            SubtitleProfile("srt", SubtitleDeliveryMethod.EXTERNAL),
                                            SubtitleProfile("ass", SubtitleDeliveryMethod.EXTERNAL),
                                        ),
                                ),
                            maxStreamingBitrate = maxStreamingBitrate,
                            /*
                             * W73（#7）：把起播 / seek 目标位置透传给服务器。转码会话据此从目标位置开始生成分片，
                             * 客户端不必等转码任务从 0 追赶（实测窗口外 seek 会 BUFFERING 15–30 s）。
                             */
                            startTimeTicks = startPositionTicks.takeIf { it > 0L },
                            // 原始画质 = 只直连；自动 / 具体码率都允许服务器转码
                            enableTranscoding = transcodingEnabled,
                            // W16：回退档位 = 服务器转码时禁止直连 / 直传，逼服务器真的转码
                            enableDirectPlay = if (forceTranscode) false else null,
                            enableDirectStream = if (forceTranscode) false else null,
                            /*
                             * 还禁止「流拷贝」：只禁直连 / 直传时，服务器会把 10-bit H.264 原样
                             * 塞进 HLS（video stream copy），客户端依旧解不了——实测就是这个坑
                             * （Hi10P 片源第二档仍报 ERROR_CODE_DECODING_FAILED）。禁掉拷贝后
                             * 服务器按转码档位（h264/aac）重新编码，客户端硬解才吃得下。
                             */
                            allowVideoStreamCopy = if (forceTranscode) false else null,
                            allowAudioStreamCopy = if (forceTranscode) false else null,
                        ),
                    )
                    .content
                    .mediaSources
                    .map { it.toFindroidSource(this@JellyfinRepositoryImpl, itemId, includePath) }
            )
            sources.addAll(database.getSources(itemId).map { it.toFindroidSource(database) })
            sources
        }

    override suspend fun getStreamUrl(itemId: UUID, mediaSourceId: String): String =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.videosApi.getVideoStreamUrl(
                    itemId,
                    static = true,
                    mediaSourceId = mediaSourceId,
                )
            } catch (e: Exception) {
                Timber.e(e)
                ""
            }
        }

    override suspend fun getSegments(itemId: UUID): List<FindroidSegment> =
        withContext(Dispatchers.IO) {
            val databaseSegments = database.getSegments(itemId).map { it.toFindroidSegment() }

            if (databaseSegments.isNotEmpty()) {
                return@withContext databaseSegments
            }

            try {
                val apiSegments =
                    jellyfinApi.mediaSegmentsApi.getItemSegments(itemId).content.items.map {
                        it.toFindroidSegment()
                    }

                return@withContext apiSegments
            } catch (e: Exception) {
                Timber.e(e)
                return@withContext emptyList()
            }
        }

    override suspend fun getTrickplayData(itemId: UUID, width: Int, index: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            try {
                try {
                    val sources = File(context.filesDir, "trickplay/$itemId").listFiles()
                    if (sources != null) {
                        return@withContext File(sources.first(), index.toString()).readBytes()
                    }
                } catch (_: Exception) {}

                return@withContext jellyfinApi.trickplayApi
                    .getTrickplayTileImage(itemId, width, index)
                    .content
            } catch (_: Exception) {
                return@withContext null
            }
        }

    override suspend fun postCapabilities() {
        Timber.d("Sending capabilities")
        withContext(Dispatchers.IO) {
            jellyfinApi.sessionApi.postCapabilities(
                playableMediaTypes = listOf(MediaType.VIDEO),
                supportedCommands =
                    listOf(
                        GeneralCommandType.VOLUME_UP,
                        GeneralCommandType.VOLUME_DOWN,
                        GeneralCommandType.TOGGLE_MUTE,
                        GeneralCommandType.SET_AUDIO_STREAM_INDEX,
                        GeneralCommandType.SET_SUBTITLE_STREAM_INDEX,
                        GeneralCommandType.MUTE,
                        GeneralCommandType.UNMUTE,
                        GeneralCommandType.SET_VOLUME,
                        GeneralCommandType.DISPLAY_MESSAGE,
                        GeneralCommandType.PLAY,
                        GeneralCommandType.PLAY_STATE,
                        GeneralCommandType.PLAY_NEXT,
                        GeneralCommandType.PLAY_MEDIA_SOURCE,
                    ),
                supportsMediaControl = true,
            )
        }
    }

    override suspend fun postPlaybackStart(itemId: UUID) {
        Timber.d("Sending start $itemId")
        // W37：本地媒体库条目没有服务器会话，跳过上报。
        if (localLibrary.isLocalItem(itemId)) return
        withContext(Dispatchers.IO) {
            jellyfinApi.playStateApi.reportPlaybackStart(
                PlaybackStartInfo(
                    itemId = itemId,
                    canSeek = true,
                    isPaused = false,
                    isMuted = false,
                    playMethod = PlayMethod.DIRECT_PLAY,
                    repeatMode = RepeatMode.REPEAT_NONE,
                    playbackOrder = PlaybackOrder.DEFAULT,
                )
            )
        }
    }

    override suspend fun postPlaybackStop(
        itemId: UUID,
        positionTicks: Long,
        playedPercentage: Int,
    ) {
        Timber.d("Sending stop $itemId")
        // W69：播放结束会改变「继续观看 / 下一次」的成员与已看状态，失效元数据缓存，
        // 下一次读各列表（首页走廊 / 库网格 / 详情）静默拿最新值。
        invalidateMetadataCache()
        // W37：本地媒体库条目不打服务器（进度由播放器内存态承载）。
        if (localLibrary.isLocalItem(itemId)) return
        withContext(Dispatchers.IO) {
            when {
                playedPercentage < 10 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                }
                playedPercentage > 90 -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, 0)
                    database.setPlayed(jellyfinApi.userId!!, itemId, true)
                }
                else -> {
                    database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
                    database.setPlayed(jellyfinApi.userId!!, itemId, false)
                }
            }
            try {
                jellyfinApi.playStateApi.reportPlaybackStopped(
                    PlaybackStopInfo(itemId = itemId, positionTicks = positionTicks, failed = false)
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun postPlaybackProgress(
        itemId: UUID,
        positionTicks: Long,
        isPaused: Boolean,
    ) {
        Timber.d("Posting progress of $itemId, position: $positionTicks")
        // W37：本地媒体库条目不打服务器。
        if (localLibrary.isLocalItem(itemId)) return
        withContext(Dispatchers.IO) {
            database.setPlaybackPositionTicks(itemId, jellyfinApi.userId!!, positionTicks)
            try {
                jellyfinApi.playStateApi.reportPlaybackProgress(
                    PlaybackProgressInfo(
                        itemId = itemId,
                        canSeek = true,
                        isPaused = isPaused,
                        isMuted = false,
                        playMethod = PlayMethod.DIRECT_PLAY,
                        repeatMode = RepeatMode.REPEAT_NONE,
                        playbackOrder = PlaybackOrder.DEFAULT,
                        positionTicks = positionTicks,
                    )
                )
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsFavorite(itemId: UUID) {
        // W69：收藏状态内嵌在各列表的条目里，本地落库后直接失效会话缓存（下一次读取静默取新值）。
        invalidateMetadataCache()
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.userLibraryApi.markFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
            // W60b：单一数据源广播（本地状态已落库，详情 / 列表 / 收藏页统一靠它刷新）。
            UserDataEvents.notifyFavoriteChanged()
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        invalidateMetadataCache()
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.userLibraryApi.unmarkFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
            // W60b：单一数据源广播（取消收藏同样通知所有展示方刷新）。
            UserDataEvents.notifyFavoriteChanged()
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
        invalidateMetadataCache()
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.playStateApi.markPlayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsUnplayed(itemId: UUID) {
        invalidateMetadataCache()
        withContext(Dispatchers.IO) {
            database.setPlayed(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.playStateApi.markUnplayedItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override fun getBaseUrl() = jellyfinApi.api.baseUrl.orEmpty()

    override fun getAccessToken(): String? = jellyfinApi.api.accessToken

    override suspend fun updateDeviceName(name: String) {
        withContext(Dispatchers.IO) {
            jellyfinApi.jellyfin.deviceInfo?.id?.let { id ->
                jellyfinApi.devicesApi.updateDeviceOptions(
                    id,
                    DeviceOptionsDto(0, customName = name),
                )
            }
        }
    }

    override suspend fun getUserConfiguration(): UserConfiguration =
        withContext(Dispatchers.IO) { jellyfinApi.userApi.getCurrentUser().content.configuration!! }

    /**
     * 当前账号是否为管理员。
     *
     * 需要联网查询，但控制台入口依赖它：服务器短暂不可达时不能把管理员的入口一起藏起来， 因此把结果按账号缓存——查询成功就刷新缓存，查询失败就用同一账号的上次结果。
     */
    override suspend fun isCurrentUserAdministrator(): Boolean =
        withContext(Dispatchers.IO) {
            val currentUserId = jellyfinApi.userId?.toString()
            val cachedUserId =
                appPreferences.getValue(appPreferences.currentUserIsAdministratorUserId)
            val cachedValue = appPreferences.getValue(appPreferences.currentUserIsAdministrator)

            runCatching {
                jellyfinApi.userApi.getCurrentUser().content.policy?.isAdministrator == true
            }
                .getOrNull()
                ?.let { isAdministrator ->
                    if (currentUserId != null && currentUserId != cachedUserId) {
                        appPreferences.setValue(
                            appPreferences.currentUserIsAdministratorUserId,
                            currentUserId,
                        )
                    }
                    if (cachedValue != isAdministrator) {
                        appPreferences.setValue(
                            appPreferences.currentUserIsAdministrator,
                            isAdministrator,
                        )
                    }
                    isAdministrator
                } ?: (currentUserId != null && currentUserId == cachedUserId && cachedValue)
        }

    override suspend fun getDownloads(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            val items = mutableListOf<FindroidItem>()
            items.addAll(
                database
                    .getMoviesByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toFindroidMovie(database, jellyfinApi.userId!!) }
            )
            items.addAll(
                database
                    .getShowsByServerId(appPreferences.getValue(appPreferences.currentServer)!!)
                    .map { it.toFindroidShow(database, jellyfinApi.userId!!) }
            )
            // W34：剧集不在这两个表里（存 episodes），按已下载的 LOCAL source 反查补进下载列表，
            // 层级化（节目 → 季 → 剧集）需要它们；只有进行中 / 失败 source 的剧集由 DownloadTask 表示。
            items.addAll(
                database.getCompletedEpisodeHierarchy().mapNotNull { hierarchy ->
                    database
                        .getEpisode(hierarchy.episodeId)
                        ?.toFindroidEpisode(
                            database,
                            jellyfinApi.userId!!,
                        )
                }
            )
            items
        }

    override suspend fun getPrimaryImageUrl(itemId: UUID): String? =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (now - imageUrlCacheAt.get() > CACHE_TTL_MS) {
                primaryImageUrls.clear()
                imageUrlCacheAt.set(now)
            }
            primaryImageUrls[itemId]?.let {
                return@withContext it
            }
            val userId = jellyfinApi.userId ?: return@withContext null
            val url = runCatching {
                jellyfinApi.userLibraryApi.getItem(itemId, userId).content
            }
                .getOrNull()
                ?.imageTags
                ?.get(ImageType.PRIMARY)
                ?.let { tag ->
                    getBaseUrl().trimEnd('/') + "/Items/$itemId/Images/Primary?tag=$tag"
                }
            url?.let { primaryImageUrls[itemId] = it }
            url
        }

    override suspend fun getDownloadedEpisodeHierarchy(): List<DownloadedEpisodeHierarchy> =
        withContext(Dispatchers.IO) {
            runCatching { database.getDownloadedEpisodeHierarchy() }.getOrElse { emptyList() }
        }

    override suspend fun getEpisodeHierarchyWithSources(): List<DownloadedEpisodeHierarchy> =
        withContext(Dispatchers.IO) {
            runCatching { database.getEpisodeHierarchyWithSources() }.getOrElse { emptyList() }
        }

    override suspend fun getMusicTrackMetadata(): Map<UUID, MusicTrackMetadata> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (now - musicTrackCacheAt.get() <= CACHE_TTL_MS) {
                return@withContext musicTrackCache.get()
            }
            val userId = jellyfinApi.userId ?: return@withContext emptyMap()
            val baseUrl = getBaseUrl().trimEnd('/')
            runCatching {
                jellyfinApi.itemsApi
                    .getItems(
                        userId,
                        includeItemTypes = listOf(BaseItemKind.AUDIO),
                        recursive = true,
                        limit = MUSIC_TRACK_LIMIT,
                    )
                    .content
                    .items
            }
                .getOrElse { emptyList() }
                .mapNotNull { dto ->
                    val imageTag = dto.imageTags?.get(ImageType.PRIMARY)
                    MusicTrackMetadata(
                        itemId = dto.id,
                        name = dto.name.orEmpty(),
                        albumName = dto.album?.takeIf { it.isNotBlank() },
                        artist =
                            dto.albumArtists?.firstOrNull()?.name ?: dto.artists?.firstOrNull(),
                        indexNumber = dto.indexNumber,
                        imageUri =
                            imageTag?.let { tag ->
                                "$baseUrl/Items/${dto.id}/Images/Primary?tag=$tag"
                            },
                    )
                }
                .associateBy { it.itemId }
                .also {
                    musicTrackCache.set(it)
                    musicTrackCacheAt.set(now)
                }
        }

    override fun getUserId(): UUID {
        return jellyfinApi.userId!!
    }

    private companion object {
        /** 音乐曲库上限，与音乐线曲库快照一致。 */
        const val MUSIC_TRACK_LIMIT = 500

        /** 下载页封面 / 曲库元数据缓存时长。 */
        const val CACHE_TTL_MS = 60_000L
    }
}
