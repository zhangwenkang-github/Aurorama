package com.zhangwenkang.cinefin.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.api.MusicDownloadUrl
import com.zhangwenkang.cinefin.api.MusicStreamRequest
import com.zhangwenkang.cinefin.api.MusicStreamUrl
import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.local.LocalLibraryRepository
import com.zhangwenkang.cinefin.models.FindroidCollection
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidEpisodeDto
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.FindroidPerson
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidTag
import com.zhangwenkang.cinefin.models.SortBy
import com.zhangwenkang.cinefin.models.SortOrder
import com.zhangwenkang.cinefin.models.isAvailableForSourceSelection
import com.zhangwenkang.cinefin.models.toFindroidCollection
import com.zhangwenkang.cinefin.models.toFindroidEpisode
import com.zhangwenkang.cinefin.models.toFindroidItem
import com.zhangwenkang.cinefin.models.toFindroidMovie
import com.zhangwenkang.cinefin.models.toFindroidPerson
import com.zhangwenkang.cinefin.models.toFindroidSeason
import com.zhangwenkang.cinefin.models.toFindroidSegment
import com.zhangwenkang.cinefin.models.toFindroidShow
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.models.toOfficialDownloadSource
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
import com.zhangwenkang.cinefin.repository.catalog.CatalogView
import com.zhangwenkang.cinefin.repository.catalog.CatalogViewItems
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.jellyfin.sdk.api.client.HttpMethod
import org.jellyfin.sdk.api.client.RawResponse
import org.jellyfin.sdk.model.FileInfo
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.DeviceOptionsDto
import org.jellyfin.sdk.model.api.GeneralCommandType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.PlayAccess
import org.jellyfin.sdk.model.api.PlayMethod
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.api.SortOrder as ItemSortOrder
import org.jellyfin.sdk.model.api.UserConfiguration
import org.jellyfin.sdk.model.api.UserItemDataDto
import timber.log.Timber

class JellyfinRepositoryImpl(
    private val context: Context,
    private val jellyfinApi: JellyfinApi,
    private val database: ServerDatabaseDao,
    private val appPreferences: AppPreferences,
    /** W37：本地媒体库（合成 LOCAL 源，播放链路零改动复用）。 */
    private val localLibrary: LocalLibraryRepository,
    metadataSnapshotStore: MetadataSnapshotStore,
    /** W8-T1r：排队写的补同步调度（WorkManager 实现由 app 层注入；默认空实现供单测）。 */
    private val userDataSyncScheduler: UserDataSyncScheduler = UserDataSyncScheduler.NoOp,
) : JellyfinRepository, OriginScopedJellyfinRepository, CatalogReadPort {
    private var downloadedMovieMapper: suspend (FindroidMovieDto, UUID?) -> FindroidMovie =
        { dto, userId ->
            dto.toFindroidMovie(database, userId)
        }
    private var downloadedEpisodeMapper: suspend (FindroidEpisodeDto, UUID?) -> FindroidEpisode =
        { dto, userId ->
            dto.toFindroidEpisode(database, userId)
        }

    internal constructor(
        context: Context,
        jellyfinApi: JellyfinApi,
        database: ServerDatabaseDao,
        appPreferences: AppPreferences,
        localLibrary: LocalLibraryRepository,
        metadataSnapshotStore: MetadataSnapshotStore,
        downloadedMovieMapper: suspend (FindroidMovieDto, UUID?) -> FindroidMovie,
        downloadedEpisodeMapper: suspend (FindroidEpisodeDto, UUID?) -> FindroidEpisode,
    ) : this(
        context = context,
        jellyfinApi = jellyfinApi,
        database = database,
        appPreferences = appPreferences,
        localLibrary = localLibrary,
        metadataSnapshotStore = metadataSnapshotStore,
    ) {
        this.downloadedMovieMapper = downloadedMovieMapper
        this.downloadedEpisodeMapper = downloadedEpisodeMapper
    }

    private var searchItemsIoDispatcher: CoroutineDispatcher = Dispatchers.IO
    private var searchItemsFetch: suspend (JellyfinApi, String, SearchScope) -> List<BaseItemDto> =
        { api, query, scope ->
            api.itemsApi
                .getItems(
                    api.userId!!,
                    searchTerm = query,
                    includeItemTypes = SearchQueryRules.itemTypes(scope),
                    recursive = true,
                )
                .content
                .items
        }
    private var searchItemsMaterialize:
        suspend (
            List<BaseItemDto>,
            suspend (List<BaseItemDto>) -> List<FindroidItem>,
        ) -> List<FindroidItem> =
        { items, materialize ->
            materialize(items)
        }
    private var searchItemsCacheRead:
        suspend (suspend () -> CatalogReadOutcome<List<FindroidItem>>) -> CatalogReadOutcome<
                List<FindroidItem>
            > =
        { read ->
            read()
        }

    /**
     * Internal observation seam for the concrete search IO contract; defaults preserve production.
     */
    internal constructor(
        context: Context,
        jellyfinApi: JellyfinApi,
        database: ServerDatabaseDao,
        appPreferences: AppPreferences,
        localLibrary: LocalLibraryRepository,
        metadataSnapshotStore: MetadataSnapshotStore,
        searchItemsIoDispatcher: CoroutineDispatcher,
        searchItemsFetch: suspend (JellyfinApi, String, SearchScope) -> List<BaseItemDto>,
        searchItemsMaterialize:
            suspend (
                List<BaseItemDto>,
                suspend (List<BaseItemDto>) -> List<FindroidItem>,
            ) -> List<FindroidItem>,
        searchItemsCacheRead:
            suspend (suspend () -> CatalogReadOutcome<List<FindroidItem>>) -> CatalogReadOutcome<
                    List<FindroidItem>
                >,
        originApiFactory: ((JellyfinOrigin) -> JellyfinApi)? = null,
    ) : this(
        context = context,
        jellyfinApi = jellyfinApi,
        database = database,
        appPreferences = appPreferences,
        localLibrary = localLibrary,
        metadataSnapshotStore = metadataSnapshotStore,
    ) {
        this.searchItemsIoDispatcher = searchItemsIoDispatcher
        this.searchItemsFetch = searchItemsFetch
        this.searchItemsMaterialize = searchItemsMaterialize
        this.searchItemsCacheRead = searchItemsCacheRead
        this.originApiFactory = originApiFactory
    }

    /** W34：下载页轮询会重复取同一批封面 / 专辑元数据，用短 TTL 缓存避免每轮都打服务器。 */
    private val primaryImageUrls = java.util.concurrent.ConcurrentHashMap<UUID, String>()
    private val imageUrlCacheAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val musicTrackCache =
        java.util.concurrent.atomic.AtomicReference<Map<UUID, MusicTrackMetadata>>(emptyMap())
    private val musicTrackCacheAt = java.util.concurrent.atomic.AtomicLong(0L)
    private val originApis = java.util.concurrent.ConcurrentHashMap<JellyfinOrigin, JellyfinApi>()
    private var originApiFactory: ((JellyfinOrigin) -> JellyfinApi)? = null
    private val catalogScopeResolver =
        CatalogScopeResolver(
            appPreferences = appPreferences,
            database = database,
            principalContext = CatalogPrincipalContext.ONLINE_ACCOUNT,
        )

    internal constructor(
        context: Context,
        jellyfinApi: JellyfinApi,
        database: ServerDatabaseDao,
        appPreferences: AppPreferences,
        localLibrary: LocalLibraryRepository,
        metadataSnapshotStore: MetadataSnapshotStore,
        originApiFactory: (JellyfinOrigin) -> JellyfinApi,
    ) : this(
        context = context,
        jellyfinApi = jellyfinApi,
        database = database,
        appPreferences = appPreferences,
        localLibrary = localLibrary,
        metadataSnapshotStore = metadataSnapshotStore,
    ) {
        this.originApiFactory = originApiFactory
    }

    private data class BoundOriginClient(
        val api: JellyfinApi,
        val baseUrl: String,
        val accessToken: String?,
        val userId: UUID,
    )

    private class BoundCatalogRepository(
        private val delegate: JellyfinRepository,
        private val baseUrl: String,
        private val accessToken: String?,
        private val userId: UUID,
    ) : JellyfinRepository by delegate {
        override fun getBaseUrl(): String = baseUrl

        override fun getAccessToken(): String? = accessToken

        override fun getUserId(): UUID = userId
    }

    private suspend fun bindOrigin(origin: JellyfinOrigin): BoundOriginClient =
        withContext(Dispatchers.IO) {
            val user =
                database.getUser(origin.userId)?.takeIf { it.serverId == origin.serverId }
                    ?: error("origin user 不属于冻结 server：${origin.serverId}/${origin.userId}")
            val address =
                database.getServerCurrentAddress(origin.serverId)
                    ?: error("origin server 缺少可用地址：${origin.serverId}")
            val accessToken =
                user.accessToken?.takeIf { it.isNotBlank() }
                    ?: error("origin user 缺少 access token：${origin.userId}")
            val baseUrl =
                address.address.takeIf { it.isNotBlank() }
                    ?: error("origin server 地址为空：${origin.serverId}")
            val api =
                originApis[origin]
                    ?: originApiFactory?.invoke(origin)
                    ?: JellyfinApi(
                            androidContext = context,
                            requestTimeout = appPreferences.getValue(appPreferences.requestTimeout),
                            connectTimeout = appPreferences.getValue(appPreferences.connectTimeout),
                            socketTimeout = appPreferences.getValue(appPreferences.socketTimeout),
                        )
                        .also { originApis[origin] = it }
            api.api.update(
                baseUrl = baseUrl,
                accessToken = accessToken,
            )
            api.userId = user.id
            BoundOriginClient(
                api = api,
                baseUrl = baseUrl,
                accessToken = accessToken,
                userId = user.id,
            )
        }

    override suspend fun credentialsForOrigin(origin: JellyfinOrigin): JellyfinOriginCredentials {
        val bound = bindOrigin(origin)
        return JellyfinOriginCredentials(
            baseUrl = bound.baseUrl,
            accessToken = bound.accessToken,
        )
    }

    override suspend fun getItemForOrigin(
        itemId: UUID,
        origin: JellyfinOrigin,
    ): FindroidItem? {
        val bound = bindOrigin(origin)
        val dto =
            withContext(Dispatchers.IO) {
                bound.api.userLibraryApi.getItem(itemId, bound.userId).content
            }
        val mapped =
            withContext(Dispatchers.IO) {
                dto.toFindroidItem(this@JellyfinRepositoryImpl, database)
            } ?: return null
        val downloadUrl = bound.api.libraryApi.getDownloadUrl(itemId)
        val sources =
            dto.mediaSources.orEmpty().map { mediaSource ->
                mediaSource.toOfficialDownloadSource(
                    baseUrl = bound.baseUrl,
                    itemId = itemId,
                    downloadUrl =
                        downloadUrlForMediaSource(
                            item = dto,
                            mediaSource = mediaSource,
                            baseUrl = bound.baseUrl,
                            itemId = itemId,
                            officialDownloadUrl = downloadUrl,
                        ),
                )
            }
        return when (mapped) {
            is FindroidMovie ->
                mapped.copy(
                    sources = sources,
                    images = FindroidImages(),
                )
            else -> mapped
        }
    }

    override suspend fun getSegmentsForOrigin(
        itemId: UUID,
        origin: JellyfinOrigin,
    ): List<FindroidSegment> {
        val bound = bindOrigin(origin)
        return withContext(Dispatchers.IO) {
            bound.api.mediaSegmentsApi.getItemSegments(itemId).content.items.map {
                it.toFindroidSegment()
            }
        }
    }

    override suspend fun getTrickplayDataForOrigin(
        itemId: UUID,
        width: Int,
        index: Int,
        origin: JellyfinOrigin,
    ): ByteArray? {
        val bound = bindOrigin(origin)
        return withContext(Dispatchers.IO) {
            bound.api.trickplayApi.getTrickplayTileImage(itemId, width, index).content
        }
    }

    override suspend fun getShowForOrigin(
        itemId: UUID,
        origin: JellyfinOrigin,
    ): FindroidShow {
        val bound = bindOrigin(origin)
        val dto =
            withContext(Dispatchers.IO) {
                bound.api.userLibraryApi.getItem(itemId, bound.userId).content
            }
        return withContext(Dispatchers.IO) { dto.toOriginScopedShow() }
    }

    override suspend fun getSeasonForOrigin(
        itemId: UUID,
        origin: JellyfinOrigin,
    ): FindroidSeason {
        val bound = bindOrigin(origin)
        val dto =
            withContext(Dispatchers.IO) {
                bound.api.userLibraryApi.getItem(itemId, bound.userId).content
            }
        return withContext(Dispatchers.IO) { dto.toOriginScopedSeason() }
    }

    private fun BaseItemDto.toOriginScopedShow(): FindroidShow =
        FindroidShow(
            id = id,
            name = name.orEmpty(),
            originalTitle = originalTitle,
            overview = overview.orEmpty(),
            sources = emptyList(),
            seasons = emptyList(),
            played = userData?.played == true,
            favorite = userData?.isFavorite == true,
            canPlay = playAccess != PlayAccess.NONE,
            canDownload = canDownload == true,
            unplayedItemCount = userData?.unplayedItemCount,
            genres = genres ?: emptyList(),
            people = emptyList(),
            runtimeTicks = runTimeTicks ?: 0L,
            communityRating = communityRating,
            officialRating = officialRating,
            status = status ?: "Ended",
            productionYear = productionYear,
            endDate = endDate,
            trailer = remoteTrailers?.getOrNull(0)?.url,
            images = FindroidImages(),
        )

    private fun BaseItemDto.toOriginScopedSeason(): FindroidSeason =
        FindroidSeason(
            id = id,
            name = name.orEmpty(),
            seriesId = seriesId!!,
            seriesName = seriesName.orEmpty(),
            originalTitle = originalTitle,
            overview = overview.orEmpty(),
            sources = emptyList(),
            indexNumber = indexNumber ?: 0,
            episodes = emptyList(),
            played = userData?.played == true,
            favorite = userData?.isFavorite == true,
            canPlay = playAccess != PlayAccess.NONE,
            canDownload = canDownload == true,
            unplayedItemCount = userData?.unplayedItemCount,
            images = FindroidImages(),
        )

    /** W69 / W88-C：会话级元数据缓存（按 serverId + userId 隔离；TTL 规则见 [MetadataCacheRules]）。 */
    private val metadataCache =
        MetadataCache(
            namespace = {
                val serverId = appPreferences.getValue(appPreferences.currentServer)
                val userId = jellyfinApi.userId?.toString()
                if (serverId.isNullOrBlank() || userId.isNullOrBlank()) {
                    null
                } else {
                    MetadataNamespace(serverId = serverId, userId = userId)
                }
            }
        )

    /** W88-C：内存 / 磁盘 SWR；后台刷新与原前台请求共用 fence-aware 单飞表。 */
    private val metadataSnapshotSwr =
        MetadataSnapshotSwr(cache = metadataCache, store = metadataSnapshotStore)

    /**
     * DTO / primitive source is cached first; domain mapping runs after every memory or disk hit.
     *
     * This keeps Room sources, current image URLs, local overlays, and current playback progress as
     * read-time projections instead of persisted truth.
     */
    private suspend fun <P : Any, T> cachedSnapshotMetadata(
        key: String,
        expectedFence: MetadataFence? = null,
        decodeSnapshot: (Any) -> P?,
        fetch: suspend () -> P,
        materialize: suspend (P) -> T,
    ): T {
        val fence = expectedFence ?: metadataCache.currentFence()
        if (fence == null) return materialize(fetch())
        return metadataSnapshotSwr.read(
            fence = fence,
            key = key,
            policy = MetadataSnapshotTtlPolicy.forKey(key),
            decodeSnapshot = decodeSnapshot,
            fetch = fetch,
            materialize = materialize,
        )
    }

    private suspend fun <T> cachedDto(
        key: String,
        fetch: suspend () -> BaseItemDto,
        materialize: suspend (BaseItemDto) -> T,
    ): T =
        cachedSnapshotMetadata(
            key = key,
            decodeSnapshot = { it as? BaseItemDto },
            fetch = fetch,
            materialize = { dto -> materialize(dto.withCurrentRoomUserData()) },
        )

    private suspend fun <T> cachedDtoList(
        key: String,
        fetch: suspend () -> List<BaseItemDto>,
        materialize: suspend (List<BaseItemDto>) -> T,
    ): T =
        cachedSnapshotMetadata(
            key = key,
            decodeSnapshot = { value -> value.asBaseItemDtoList() },
            fetch = fetch,
            materialize = { dtos ->
                materialize(dtos.map { dto -> dto.withCurrentRoomUserData() })
            },
        )

    private suspend fun cachedInt(
        key: String,
        fetch: suspend () -> Int,
    ): Int =
        cachedSnapshotMetadata(
            key = key,
            decodeSnapshot = { it as? Int },
            fetch = fetch,
            materialize = { it },
        )

    private fun Any.asBaseItemDtoList(): List<BaseItemDto>? {
        val values = this as? List<*> ?: return null
        if (values.any { it !is BaseItemDto }) return null
        return values.map { it as BaseItemDto }
    }

    private suspend fun List<BaseItemDto>.toFindroidItems(
        repository: JellyfinRepository = this@JellyfinRepositoryImpl
    ): List<FindroidItem> =
        withContext(Dispatchers.IO) { mapNotNull { it.toFindroidItem(repository, database) } }

    private suspend fun List<BaseItemDto>.toFindroidTags(): List<FindroidTag> = mapNotNull { dto ->
        val name = dto.name ?: return@mapNotNull null
        FindroidTag(id = dto.id, name = name)
    }

    /**
     * UserData is deliberately absent from disk snapshots. Overlay current local mutations before
     * mapping so an old card cannot resurrect stale progress or favorite state as truth.
     */
    private suspend fun BaseItemDto.withCurrentRoomUserData(): BaseItemDto {
        val userId = jellyfinApi.userId ?: return this
        return withRoomUserData(userId)
    }

    private suspend fun BaseItemDto.withRoomUserData(userId: UUID): BaseItemDto {
        val local = database.getUserData(id, userId) ?: return this
        val current =
            userData?.copy(
                played = local.played,
                isFavorite = local.favorite,
                playbackPositionTicks = local.playbackPositionTicks,
            )
                ?: UserItemDataDto(
                    null,
                    null,
                    null,
                    local.playbackPositionTicks,
                    0,
                    local.favorite,
                    null,
                    null,
                    local.played,
                    "",
                    id,
                )
        return copy(userData = current)
    }

    /** W69：下拉刷新 / 用户主动刷新 = 强制失效缓存，后续读取直打服务器。 */
    override fun invalidateMetadataCache() {
        metadataSnapshotSwr.invalidateAll()
    }

    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        withContext(Dispatchers.IO) { jellyfinApi.systemApi.getPublicSystemInfo().content }

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode {
        if (completedLocalSource(itemId) != null) {
            return withContext(Dispatchers.IO) {
                downloadedEpisodeMapper(database.getEpisode(itemId), jellyfinApi.userId)
            }
        }
        return cachedDto(
            key = MetadataCacheKeys.episode(itemId),
            fetch = {
                withContext(Dispatchers.IO) {
                    jellyfinApi.userLibraryApi.getItem(itemId, jellyfinApi.userId!!).content
                }
            },
            materialize = { dto ->
                withContext(Dispatchers.IO) {
                    dto.toFindroidEpisode(this@JellyfinRepositoryImpl, database)
                        ?: throw IllegalStateException("该条目不是可播放的剧集")
                }
            },
        )
    }

    override suspend fun getMovie(itemId: UUID): FindroidMovie {
        withContext(Dispatchers.IO) { localLibrary.syntheticMovie(itemId) }
            ?.let {
                return it
            }
        if (completedLocalSource(itemId) != null) {
            return withContext(Dispatchers.IO) {
                downloadedMovieMapper(database.getMovie(itemId), jellyfinApi.userId)
            }
        }
        return cachedDto(
            key = MetadataCacheKeys.movie(itemId),
            fetch = {
                withContext(Dispatchers.IO) {
                    jellyfinApi.userLibraryApi.getItem(itemId, jellyfinApi.userId!!).content
                }
            },
            materialize = { dto ->
                withContext(Dispatchers.IO) {
                    dto.toFindroidMovie(this@JellyfinRepositoryImpl, database)
                }
            },
        )
    }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        cachedDto(
            key = MetadataCacheKeys.show(itemId),
            fetch = {
                withContext(Dispatchers.IO) {
                    jellyfinApi.userLibraryApi.getItem(itemId, jellyfinApi.userId!!).content
                }
            },
            materialize = { dto ->
                withContext(Dispatchers.IO) { dto.toFindroidShow(this@JellyfinRepositoryImpl) }
            },
        )

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        cachedDto(
            key = MetadataCacheKeys.season(itemId),
            fetch = {
                withContext(Dispatchers.IO) {
                    jellyfinApi.userLibraryApi.getItem(itemId, jellyfinApi.userId!!).content
                }
            },
            materialize = { dto ->
                withContext(Dispatchers.IO) { dto.toFindroidSeason(this@JellyfinRepositoryImpl) }
            },
        )

    override suspend fun getLibraries(): List<FindroidCollection> =
        cachedDtoList(
            key = MetadataCacheKeys.LIBRARIES,
            fetch = {
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
                    val stableCounts = libraryItemCounts(libraries.map { it.id })
                    libraries.map { library ->
                        library.copy(
                            childCount =
                                stableLibraryItemCount(
                                    stableCounts[library.id],
                                    library.childCount,
                                )
                        )
                    }
                }
            },
            materialize = { libraries ->
                withContext(Dispatchers.IO) {
                    libraries.mapNotNull { it.toFindroidCollection(this@JellyfinRepositoryImpl) }
                }
            },
        )

    /**
     * W62：并发取每个库的稳定条目数。
     *
     * 口径 = `parentId=<库>&recursive=false&limit=1` 的 `TotalRecordCount`（= 库内直接条目数，探针两次采样一致，
     * 且与进入库后内容页的总数一致）。计数统一走 [MetadataCache]，按 serverId + userId + generation 隔离； 单个库查询失败只影响它自己（返回
     * null → 调用方回退 `ChildCount`）。
     */
    private suspend fun libraryItemCounts(libraryIds: List<UUID>): Map<UUID, Int> = coroutineScope {
        libraryIds
            .map { libraryId -> async { libraryItemCount(libraryId)?.let { libraryId to it } } }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    private suspend fun libraryItemCount(libraryId: UUID): Int? =
        try {
            cachedInt(key = MetadataCacheKeys.libraryItemCount(libraryId)) {
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
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Timber.w(throwable, "读取库 %s 的稳定条目数失败，回退 ChildCount", libraryId)
            null
        }

    override suspend fun getItem(itemId: UUID): FindroidItem? {
        withContext(Dispatchers.IO) { localLibrary.syntheticMovie(itemId) }
            ?.let {
                return it
            }
        return cachedDto(
            key = MetadataCacheKeys.item(itemId),
            fetch = {
                withContext(Dispatchers.IO) {
                    jellyfinApi.userLibraryApi
                        .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                        .content
                }
            },
            materialize = { dto ->
                withContext(Dispatchers.IO) {
                    dto.toFindroidItem(this@JellyfinRepositoryImpl, database)
                }
            },
        )
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
        cachedDtoList(
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
            fetch = {
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
                            // W77-5：书架书籍卡零请求判断远端 PDF（`BookCoverRules.isPdfPath`）用；默认字段不含 Path。
                            // W6-T10：书籍查询补 `People`，书架条目显示作者（READER-39）；其他类型不请求，避免额外载荷。
                            fields =
                                buildList {
                                    add(ItemFields.PATH)
                                    if (includeTypes?.contains(BaseItemKind.BOOK) == true) {
                                        add(ItemFields.PEOPLE)
                                    }
                                },
                        )
                        .content
                        .items
                }
            },
            materialize = { it.toFindroidItems() },
        )

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
        cachedInt(
            key =
                MetadataCacheKeys.itemCount(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    recursive = recursive,
                    filters = filters,
                    genres = genres,
                    studios = studios,
                ),
            fetch = {
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
            },
        )

    override suspend fun getLibrarySuggestions(
        parentId: UUID,
        includeTypes: List<BaseItemKind>?,
        limit: Int,
    ): List<FindroidItem> =
        cachedDtoList(
            key =
                MetadataCacheKeys.librarySuggestions(
                    parentId = parentId,
                    includeTypes = includeTypes,
                    limit = limit,
                ),
            fetch = {
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
            },
            materialize = { it.toFindroidItems() },
        )

    override suspend fun getUpcomingEpisodes(parentId: UUID, limit: Int): List<FindroidItem> =
        cachedDtoList(
            key = MetadataCacheKeys.upcoming(parentId = parentId, limit = limit),
            fetch = {
                withContext(Dispatchers.IO) {
                    jellyfinApi.showsApi
                        .getUpcomingEpisodes(
                            jellyfinApi.userId!!,
                            parentId = parentId,
                            limit = limit,
                        )
                        .content
                        .items
                }
            },
            materialize = { it.toFindroidItems() },
        )

    override suspend fun getGenres(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidTag> =
        cachedDtoList(
            key =
                MetadataCacheKeys.genres(
                    parentId = parentId,
                    includeItemTypes = includeItemTypes,
                ),
            fetch = {
                jellyfinApi.genresApi
                    .getGenres(
                        userId = jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = includeItemTypes,
                        sortBy = listOf(ItemSortBy.SORT_NAME),
                    )
                    .content
                    .items
            },
            materialize = { it.toFindroidTags() },
        )

    override suspend fun getStudios(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidTag> =
        cachedDtoList(
            key =
                MetadataCacheKeys.studios(
                    parentId = parentId,
                    includeItemTypes = includeItemTypes,
                ),
            fetch = {
                jellyfinApi.studiosApi
                    .getStudios(
                        userId = jellyfinApi.userId!!,
                        parentId = parentId,
                        includeItemTypes = includeItemTypes,
                    )
                    .content
                    .items
            },
            materialize = { it.toFindroidTags() },
        )

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
        cachedDtoList(
            key =
                MetadataCacheKeys.personItems(
                    personIds = personIds,
                    includeTypes = includeTypes,
                ),
            fetch = {
                jellyfinApi.itemsApi
                    .getItems(
                        jellyfinApi.userId!!,
                        personIds = personIds,
                        includeItemTypes = includeTypes,
                        recursive = recursive,
                    )
                    .content
                    .items
            },
            materialize = { it.toFindroidItems() },
        )

    override suspend fun getFavoriteItems(
        sortBy: SortBy,
        sortOrder: SortOrder,
    ): List<FindroidItem> =
        cachedDtoList(
            key = MetadataCacheKeys.favorites(sortBy = sortBy, sortOrder = sortOrder),
            fetch = {
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
            },
            materialize = { it.toFindroidItems() },
        )

    override suspend fun currentScope(): CatalogScope? = catalogScopeResolver.resolve()

    override suspend fun search(query: CatalogSearchQuery): CatalogReadOutcome<List<FindroidItem>> =
        withContext(searchItemsIoDispatcher) {
            try {
                searchItemsCacheRead {
                    val scope = query.frozenScope
                    if (currentScope() != scope) {
                        return@searchItemsCacheRead CatalogReadOutcome.IdentityUnavailable
                    }
                    val expectedNamespace =
                        MetadataNamespace(
                            serverId = scope.origin.serverId,
                            userId = scope.origin.userId.toString(),
                        )
                    val fence =
                        metadataCache.currentFence()?.takeIf { it.namespace == expectedNamespace }
                            ?: return@searchItemsCacheRead CatalogReadOutcome.IdentityUnavailable
                    val bound =
                        try {
                            bindOrigin(scope.origin)
                        } catch (_: IllegalStateException) {
                            return@searchItemsCacheRead CatalogReadOutcome.IdentityUnavailable
                        }
                    if (bound.userId != scope.origin.userId) {
                        return@searchItemsCacheRead CatalogReadOutcome.IdentityUnavailable
                    }
                    if (currentScope() != scope) {
                        return@searchItemsCacheRead CatalogReadOutcome.IdentityUnavailable
                    }
                    val validateFence: suspend (MetadataFence) -> Boolean = { candidate ->
                        candidate == fence && currentScope() == scope
                    }
                    val cacheKey = MetadataCacheKeys.search(query.term, query.scope)
                    val read =
                        metadataSnapshotSwr.readWithMetadata(
                            fence = fence,
                            key = cacheKey,
                            policy = MetadataSnapshotTtlPolicy.forKey(cacheKey),
                            decodeSnapshot = { value -> value.asBaseItemDtoList() },
                            fetch = {
                                val items =
                                    searchItemsFetch(
                                        bound.api,
                                        query.term,
                                        query.scope,
                                    )
                                if (currentScope() != scope) {
                                    throw MetadataSnapshotFenceChangedException()
                                }
                                items
                            },
                            materialize = { items ->
                                val scopedRepository =
                                    BoundCatalogRepository(
                                        delegate = this@JellyfinRepositoryImpl,
                                        baseUrl = bound.baseUrl,
                                        accessToken = bound.accessToken,
                                        userId = bound.userId,
                                    )
                                searchItemsMaterialize(
                                    items.map { dto -> dto.withRoomUserData(scope.origin.userId) }
                                ) { dtos ->
                                    dtos.toFindroidItems(scopedRepository)
                                }
                            },
                            validateFence = validateFence,
                        )
                    if (currentScope() != scope || metadataCache.currentFence() != fence) {
                        return@searchItemsCacheRead CatalogReadOutcome.IdentityUnavailable
                    }
                    CatalogReadOutcome.Available(
                        CatalogSnapshot(
                            scope = scope,
                            contentGeneration = fence.contentGeneration,
                            freshness =
                                when (read.freshness) {
                                    MetadataSnapshotFreshness.FRESH -> CatalogFreshness.FRESH
                                    MetadataSnapshotFreshness.STALE -> CatalogFreshness.STALE
                                },
                            observedAtEpochMs = read.observedAtEpochMs,
                            value = read.value,
                        )
                    )
                }
            } catch (_: MetadataSnapshotFenceChangedException) {
                CatalogReadOutcome.IdentityUnavailable
            }
        }

    override suspend fun home(query: CatalogHomeQuery): CatalogReadOutcome<CatalogHomeData> =
        withContext(Dispatchers.IO) {
            val context =
                beginOnlineCatalogRead(query.frozenScope, query.forceRefresh)
                    ?: return@withContext CatalogReadOutcome.IdentityUnavailable
            try {
                val reads = coroutineScope {
                    val suggestions =
                        if (query.includeSuggestions) {
                            async {
                                readCatalogItemList(
                                    context = context,
                                    key = MetadataCacheKeys.SUGGESTIONS,
                                    fetch = { bound ->
                                        bound.api.suggestionsApi
                                            .getSuggestions(
                                                bound.userId,
                                                limit = 6,
                                                type =
                                                    listOf(
                                                        BaseItemKind.MOVIE,
                                                        BaseItemKind.SERIES,
                                                    ),
                                            )
                                            .content
                                            .items
                                    },
                                )
                            }
                        } else {
                            null
                        }
                    val continueWatching =
                        if (query.includeContinueWatching) {
                            async {
                                readResumeItems(
                                    context,
                                    listOf(BaseItemKind.MOVIE, BaseItemKind.EPISODE),
                                )
                            }
                        } else {
                            null
                        }
                    val continueReading =
                        if (query.includeContinueReading) {
                            async { readResumeItems(context, listOf(BaseItemKind.BOOK)) }
                        } else {
                            null
                        }
                    val continueListening =
                        if (query.includeContinueListening) {
                            async { readResumeItems(context, listOf(BaseItemKind.AUDIO)) }
                        } else {
                            null
                        }
                    val nextUp =
                        if (query.includeNextUp) {
                            async {
                                readCatalogDtoList(
                                    context = context,
                                    key = MetadataCacheKeys.nextUp(null),
                                    fetch = { bound ->
                                        bound.api.showsApi
                                            .getNextUp(
                                                bound.userId,
                                                limit = 24,
                                                seriesId = null,
                                                enableResumable = false,
                                            )
                                            .content
                                            .items
                                    },
                                    materialize = { dtos, repository ->
                                        val episodes: List<FindroidItem> = dtos.mapNotNull {
                                            it.toFindroidEpisode(repository)
                                        }
                                        episodes
                                    },
                                )
                            }
                        } else {
                            null
                        }
                    val views =
                        if (query.includeUserViews) {
                            async { readHomeViews(context) }
                        } else {
                            null
                        }
                    HomeReads(
                        suggestions = suggestions?.await(),
                        continueWatching = continueWatching?.await(),
                        continueReading = continueReading?.await(),
                        continueListening = continueListening?.await(),
                        nextUp = nextUp?.await(),
                        views = views?.await(),
                    )
                }
                val allReads =
                    listOfNotNull(
                        reads.suggestions,
                        reads.continueWatching,
                        reads.continueReading,
                        reads.continueListening,
                        reads.nextUp,
                        reads.views?.metadata,
                    ) + reads.views?.latest.orEmpty()
                val freshness =
                    if (allReads.any { it.freshness == MetadataSnapshotFreshness.STALE }) {
                        CatalogFreshness.STALE
                    } else {
                        CatalogFreshness.FRESH
                    }
                val observedAtEpochMs =
                    allReads.minOfOrNull(MetadataSnapshotRead<*>::observedAtEpochMs)
                        ?: metadataCache.now()
                CatalogReadOutcome.Available(
                    CatalogSnapshot(
                        scope = context.scope,
                        contentGeneration = context.fence.contentGeneration,
                        freshness = freshness,
                        observedAtEpochMs = observedAtEpochMs,
                        value =
                            CatalogHomeData(
                                suggestions = reads.suggestions?.value.orEmpty(),
                                resumeWatching = reads.continueWatching?.value.orEmpty(),
                                resumeReading = reads.continueReading?.value.orEmpty(),
                                resumeListening = reads.continueListening?.value.orEmpty(),
                                nextUp = reads.nextUp?.value.orEmpty(),
                                views = reads.views?.views.orEmpty(),
                            ),
                    )
                )
            } catch (_: MetadataSnapshotFenceChangedException) {
                CatalogReadOutcome.IdentityUnavailable
            }
        }

    override suspend fun libraries(
        query: CatalogLibrariesQuery
    ): CatalogReadOutcome<List<FindroidCollection>> =
        withContext(Dispatchers.IO) {
            val context =
                beginOnlineCatalogRead(query.frozenScope, query.forceRefresh)
                    ?: return@withContext CatalogReadOutcome.IdentityUnavailable
            try {
                catalogAvailable(context, readCatalogLibraries(context))
            } catch (_: MetadataSnapshotFenceChangedException) {
                CatalogReadOutcome.IdentityUnavailable
            }
        }

    override suspend fun itemsPage(
        query: CatalogItemsPageQuery
    ): CatalogReadOutcome<List<FindroidItem>> =
        withContext(Dispatchers.IO) {
            val context =
                beginOnlineCatalogRead(query.frozenScope, forceRefresh = false)
                    ?: return@withContext CatalogReadOutcome.IdentityUnavailable
            val cacheKey =
                MetadataCacheKeys.items(
                    parentId = query.parentId,
                    includeTypes = query.includeTypes,
                    recursive = query.recursive,
                    sortBy = query.sortBy,
                    sortOrder = query.sortOrder,
                    startIndex = query.startIndex,
                    limit = query.limit,
                    filters = query.filters,
                    genres = query.genres,
                    studios = query.studios,
                )
            try {
                val read =
                    readCatalogItemList(
                        context = context,
                        key = cacheKey,
                        fetch = { bound ->
                            bound.api.itemsApi
                                .getItems(
                                    bound.userId,
                                    parentId = query.parentId,
                                    includeItemTypes = query.includeTypes,
                                    recursive = query.recursive,
                                    sortBy = listOf(ItemSortBy.fromName(query.sortBy.sortString)),
                                    sortOrder =
                                        listOf(ItemSortOrder.fromName(query.sortOrder.sortString)),
                                    startIndex = query.startIndex,
                                    limit = query.limit,
                                    filters = query.filters,
                                    genres = query.genres,
                                    studios = query.studios,
                                    fields = listOf(ItemFields.PATH),
                                )
                                .content
                                .items
                        },
                    )
                catalogAvailable(context, read)
            } catch (_: MetadataSnapshotFenceChangedException) {
                CatalogReadOutcome.IdentityUnavailable
            }
        }

    override suspend fun movie(query: CatalogMovieQuery): CatalogReadOutcome<FindroidMovie> =
        withContext(Dispatchers.IO) {
            val context =
                beginOnlineCatalogRead(query.frozenScope, forceRefresh = false)
                    ?: return@withContext CatalogReadOutcome.IdentityUnavailable
            localLibrary.syntheticMovie(query.itemId)?.let { local ->
                if (!context.isCurrent()) return@withContext CatalogReadOutcome.IdentityUnavailable
                return@withContext CatalogReadOutcome.Available(
                    CatalogSnapshot(
                        scope = context.scope,
                        contentGeneration = context.fence.contentGeneration,
                        freshness = CatalogFreshness.FRESH,
                        observedAtEpochMs = metadataCache.now(),
                        value = local,
                    )
                )
            }
            try {
                val read =
                    readCatalogDto(
                        context = context,
                        key = MetadataCacheKeys.movie(query.itemId),
                        fetch = { bound ->
                            bound.api.userLibraryApi.getItem(query.itemId, bound.userId).content
                        },
                        materialize = { dto, repository ->
                            dto.toFindroidMovie(repository, database)
                        },
                    )
                catalogAvailable(context, read)
            } catch (_: MetadataSnapshotFenceChangedException) {
                CatalogReadOutcome.IdentityUnavailable
            }
        }

    private data class OnlineCatalogReadContext(
        val scope: CatalogScope,
        val fence: MetadataFence,
        val bound: BoundOriginClient,
    )

    private data class HomeViewsRead(
        val views: List<CatalogViewItems>,
        val metadata: MetadataSnapshotRead<List<BaseItemDto>>,
        val latest: List<MetadataSnapshotRead<List<FindroidItem>>>,
    )

    private data class HomeReads(
        val suggestions: MetadataSnapshotRead<List<FindroidItem>>?,
        val continueWatching: MetadataSnapshotRead<List<FindroidItem>>?,
        val continueReading: MetadataSnapshotRead<List<FindroidItem>>?,
        val continueListening: MetadataSnapshotRead<List<FindroidItem>>?,
        val nextUp: MetadataSnapshotRead<List<FindroidItem>>?,
        val views: HomeViewsRead?,
    )

    private suspend fun beginOnlineCatalogRead(
        expectedScope: CatalogScope,
        forceRefresh: Boolean,
    ): OnlineCatalogReadContext? {
        if (currentScope() != expectedScope) return null
        val expectedNamespace =
            MetadataNamespace(
                serverId = expectedScope.origin.serverId,
                userId = expectedScope.origin.userId.toString(),
            )
        val expectedFence =
            metadataCache.currentFence()?.takeIf { it.namespace == expectedNamespace }
                ?: return null
        val bound =
            try {
                bindOrigin(expectedScope.origin)
            } catch (_: IllegalStateException) {
                return null
            }
        if (bound.userId != expectedScope.origin.userId || currentScope() != expectedScope) {
            return null
        }
        if (forceRefresh) {
            if (!metadataSnapshotSwr.invalidateAll(expectedFence)) return null
        }
        val fence =
            metadataCache.currentFence()?.takeIf { it.namespace == expectedNamespace }
                ?: return null
        if (currentScope() != expectedScope) return null
        return OnlineCatalogReadContext(
            scope = expectedScope,
            fence = fence,
            bound = bound,
        )
    }

    private suspend fun OnlineCatalogReadContext.isCurrent(): Boolean =
        currentScope() == scope && metadataCache.currentFence() == fence

    private fun OnlineCatalogReadContext.scopedRepository(): JellyfinRepository =
        BoundCatalogRepository(
            delegate = this@JellyfinRepositoryImpl,
            baseUrl = bound.baseUrl,
            accessToken = bound.accessToken,
            userId = bound.userId,
        )

    private suspend fun readResumeItems(
        context: OnlineCatalogReadContext,
        includeItemTypes: List<BaseItemKind>,
    ): MetadataSnapshotRead<List<FindroidItem>> =
        readCatalogItemList(
            context = context,
            key = MetadataCacheKeys.resume(includeItemTypes),
            fetch = { bound ->
                bound.api.itemsApi
                    .getResumeItems(
                        bound.userId,
                        limit = 12,
                        includeItemTypes = includeItemTypes,
                        fields = listOf(ItemFields.PATH),
                    )
                    .content
                    .items
            },
        )

    private suspend fun readHomeViews(context: OnlineCatalogReadContext): HomeViewsRead {
        val metadata =
            readCatalogDtoList(
                context = context,
                key = MetadataCacheKeys.VIEWS,
                fetch = { bound -> bound.api.viewsApi.getUserViews(bound.userId).content.items },
                materialize = { dtos, _ -> dtos },
            )
        val repository = context.scopedRepository()
        val views = mutableListOf<CatalogViewItems>()
        val latestReads = mutableListOf<MetadataSnapshotRead<List<FindroidItem>>>()
        for (dto in metadata.value) {
            val collection =
                withContext(Dispatchers.IO) { dto.toFindroidCollection(repository) } ?: continue
            val view =
                CatalogView(
                    id = collection.id,
                    name = collection.name,
                    type = collection.type,
                )
            val latest =
                readCatalogItemList(
                    context = context,
                    key = MetadataCacheKeys.latestMedia(view.id),
                    fetch = { bound ->
                        bound.api.userLibraryApi
                            .getLatestMedia(
                                bound.userId,
                                parentId = view.id,
                                limit = 16,
                                fields = listOf(ItemFields.PATH),
                            )
                            .content
                    },
                )
            latestReads += latest
            if (latest.value.isNotEmpty()) {
                views += CatalogViewItems(view = view, latestMedia = latest.value)
            }
        }
        return HomeViewsRead(
            views = views,
            metadata = metadata,
            latest = latestReads,
        )
    }

    private suspend fun readCatalogLibraries(
        context: OnlineCatalogReadContext
    ): MetadataSnapshotRead<List<FindroidCollection>> =
        readCatalogDtoList(
            context = context,
            key = MetadataCacheKeys.LIBRARIES,
            fetch = { bound ->
                coroutineScope {
                    val libraries =
                        bound.api.itemsApi
                            .getItems(
                                bound.userId,
                                fields = listOf(ItemFields.CHILD_COUNT),
                            )
                            .content
                            .items
                    val counts =
                        libraries
                            .map { library ->
                                async {
                                    libraryItemCountForCatalog(context, library.id)?.let {
                                        library.id to it
                                    }
                                }
                            }
                            .awaitAll()
                            .filterNotNull()
                            .toMap()
                    libraries.map { library ->
                        library.copy(
                            childCount =
                                stableLibraryItemCount(
                                    counts[library.id],
                                    library.childCount,
                                )
                        )
                    }
                }
            },
            materialize = { dtos, repository ->
                dtos.mapNotNull { it.toFindroidCollection(repository) }
            },
        )

    private suspend fun libraryItemCountForCatalog(
        context: OnlineCatalogReadContext,
        libraryId: UUID,
    ): Int? =
        try {
            readCatalogInt(
                    context = context,
                    key = MetadataCacheKeys.libraryItemCount(libraryId),
                    fetch = { bound ->
                        bound.api.itemsApi
                            .getItems(
                                bound.userId,
                                parentId = libraryId,
                                recursive = false,
                                limit = 1,
                            )
                            .content
                            .totalRecordCount
                    },
                )
                .value
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Timber.w(throwable, "读取库 %s 的稳定条目数失败，回退 ChildCount", libraryId)
            null
        }

    private suspend fun readCatalogItemList(
        context: OnlineCatalogReadContext,
        key: String,
        fetch: suspend (BoundOriginClient) -> List<BaseItemDto>,
    ): MetadataSnapshotRead<List<FindroidItem>> =
        readCatalogDtoList(
            context = context,
            key = key,
            fetch = fetch,
            materialize = { dtos, repository -> dtos.toFindroidItems(repository) },
        )

    private suspend fun <T> readCatalogDtoList(
        context: OnlineCatalogReadContext,
        key: String,
        fetch: suspend (BoundOriginClient) -> List<BaseItemDto>,
        materialize: suspend (List<BaseItemDto>, JellyfinRepository) -> T,
    ): MetadataSnapshotRead<T> =
        metadataSnapshotSwr.readWithMetadata(
            fence = context.fence,
            key = key,
            policy = MetadataSnapshotTtlPolicy.forKey(key),
            decodeSnapshot = { value -> value.asBaseItemDtoList() },
            fetch = {
                val value = fetch(context.bound)
                if (!context.isCurrent()) {
                    throw MetadataSnapshotFenceChangedException()
                }
                value
            },
            materialize = { dtos ->
                val repository = context.scopedRepository()
                val current =
                    withContext(Dispatchers.IO) {
                        dtos.map { it.withRoomUserData(context.scope.origin.userId) }
                    }
                if (!context.isCurrent()) {
                    throw MetadataSnapshotFenceChangedException()
                }
                val result = materialize(current, repository)
                if (!context.isCurrent()) {
                    throw MetadataSnapshotFenceChangedException()
                }
                result
            },
            validateFence = { candidate -> candidate == context.fence && context.isCurrent() },
        )

    private suspend fun <T> readCatalogDto(
        context: OnlineCatalogReadContext,
        key: String,
        fetch: suspend (BoundOriginClient) -> BaseItemDto,
        materialize: suspend (BaseItemDto, JellyfinRepository) -> T,
    ): MetadataSnapshotRead<T> =
        metadataSnapshotSwr.readWithMetadata(
            fence = context.fence,
            key = key,
            policy = MetadataSnapshotTtlPolicy.forKey(key),
            decodeSnapshot = { value -> value as? BaseItemDto },
            fetch = {
                val value = fetch(context.bound)
                if (!context.isCurrent()) {
                    throw MetadataSnapshotFenceChangedException()
                }
                value
            },
            materialize = { dto ->
                val repository = context.scopedRepository()
                val current =
                    withContext(Dispatchers.IO) {
                        dto.withRoomUserData(context.scope.origin.userId)
                    }
                if (!context.isCurrent()) {
                    throw MetadataSnapshotFenceChangedException()
                }
                val result = materialize(current, repository)
                if (!context.isCurrent()) {
                    throw MetadataSnapshotFenceChangedException()
                }
                result
            },
            validateFence = { candidate -> candidate == context.fence && context.isCurrent() },
        )

    private suspend fun readCatalogInt(
        context: OnlineCatalogReadContext,
        key: String,
        fetch: suspend (BoundOriginClient) -> Int,
    ): MetadataSnapshotRead<Int> =
        metadataSnapshotSwr.readWithMetadata(
            fence = context.fence,
            key = key,
            policy = MetadataSnapshotTtlPolicy.forKey(key),
            decodeSnapshot = { value -> value as? Int },
            fetch = {
                val value = fetch(context.bound)
                if (!context.isCurrent()) {
                    throw MetadataSnapshotFenceChangedException()
                }
                value
            },
            materialize = { value ->
                if (!context.isCurrent()) {
                    throw MetadataSnapshotFenceChangedException()
                }
                value
            },
            validateFence = { candidate -> candidate == context.fence && context.isCurrent() },
        )

    private fun <T> catalogAvailable(
        context: OnlineCatalogReadContext,
        read: MetadataSnapshotRead<T>,
    ): CatalogReadOutcome<T> =
        CatalogReadOutcome.Available(
            CatalogSnapshot(
                scope = context.scope,
                contentGeneration = context.fence.contentGeneration,
                freshness =
                    when (read.freshness) {
                        MetadataSnapshotFreshness.FRESH -> CatalogFreshness.FRESH
                        MetadataSnapshotFreshness.STALE -> CatalogFreshness.STALE
                    },
                observedAtEpochMs = read.observedAtEpochMs,
                value = read.value,
            )
        )

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> {
        if (offline) {
            return withContext(Dispatchers.IO) {
                database.getSeasonsByShowId(seriesId).map {
                    it.toFindroidSeason(database, jellyfinApi.userId!!)
                }
            }
        }
        return cachedDtoList(
            key = MetadataCacheKeys.seasons(seriesId, offline = false),
            fetch = {
                withContext(Dispatchers.IO) {
                    jellyfinApi.showsApi.getSeasons(seriesId, jellyfinApi.userId!!).content.items
                }
            },
            materialize = { dtos ->
                withContext(Dispatchers.IO) {
                    dtos.map { it.toFindroidSeason(this@JellyfinRepositoryImpl) }
                }
            },
        )
    }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =
        cachedDtoList(
            key = MetadataCacheKeys.nextUp(seriesId),
            fetch = {
                jellyfinApi.showsApi
                    .getNextUp(
                        jellyfinApi.userId!!,
                        limit = 24,
                        seriesId = seriesId,
                        enableResumable = false,
                    )
                    .content
                    .items
            },
            materialize = { dtos ->
                withContext(Dispatchers.IO) {
                    dtos.mapNotNull { it.toFindroidEpisode(this@JellyfinRepositoryImpl) }
                }
            },
        )

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> {
        if (offline) {
            return withContext(Dispatchers.IO) {
                database.getEpisodesBySeasonId(seasonId).map {
                    it.toFindroidEpisode(database, jellyfinApi.userId!!)
                }
            }
        }

        val fetchOnlineEpisodes: suspend () -> List<BaseItemDto> = {
            withContext(Dispatchers.IO) {
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
            }
        }
        val materializeOnlineEpisodes: suspend (List<BaseItemDto>) -> List<FindroidEpisode> =
            { dtos ->
                withContext(Dispatchers.IO) {
                    dtos.mapNotNull {
                        // W73（#10）：未知季的集不带 SeasonId，用请求用的 seasonId 回落，别让整条被丢掉。
                        it.toFindroidEpisode(
                            this@JellyfinRepositoryImpl,
                            database,
                            fallbackSeasonId = seasonId,
                        )
                    }
                }
            }

        if (fields?.contains(ItemFields.MEDIA_SOURCES) == true) {
            return materializeOnlineEpisodes(
                fetchOnlineEpisodes().map { dto -> dto.withCurrentRoomUserData() }
            )
        }

        return cachedDtoList(
            key =
                MetadataCacheKeys.episodes(
                    seriesId = seriesId,
                    seasonId = seasonId,
                    fields = fields,
                    startItemId = startItemId,
                    limit = limit,
                    offline = false,
                ),
            fetch = fetchOnlineEpisodes,
            materialize = materializeOnlineEpisodes,
        )
    }

    override suspend fun getMediaSourcesForOrigin(
        itemId: UUID,
        origin: JellyfinOrigin,
    ): List<FindroidSource> {
        val bound = bindOrigin(origin)
        return withContext(Dispatchers.IO) {
            val item = bound.api.userLibraryApi.getItem(itemId, bound.userId).content
            val downloadUrl = bound.api.libraryApi.getDownloadUrl(itemId)
            item.mediaSources.orEmpty().map { mediaSource ->
                mediaSource.toOfficialDownloadSource(
                    baseUrl = bound.baseUrl,
                    itemId = itemId,
                    downloadUrl =
                        downloadUrlForMediaSource(
                            item = item,
                            mediaSource = mediaSource,
                            baseUrl = bound.baseUrl,
                            itemId = itemId,
                            officialDownloadUrl = downloadUrl,
                        ),
                )
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
            // W9-T5（F4）：已下载条目（按 itemId 直查下载记录，不依赖当前会话身份）优先直开本地文件。
            getDownloadedLocalSource(itemId)?.let {
                return@withContext listOf(it)
            }
            /*
             * 码率档位：0 = 自动（不设上限，服务器自行判断直连 / 转码）、
             * -1 = 原始画质（只直连，明确禁用转码）、>0 = 具体 bps（只映射 maxStreamingBitrate）。
             * 选具体码率且片源超过上限时，服务器会在 mediaSources 里给出 transcodingUrl，
             * 播放侧（PlaylistManager）优先使用它。分辨率标签参考 Android client v2.7.3 快照，
             * 是非协议硬限制标签，不会新增 maxWidth / maxHeight 请求。
             */
            val request =
                PlaybackInfoSourceRequestFactory.create(
                    appPreferences = appPreferences,
                    userId = jellyfinApi.userId!!,
                    startPositionTicks = startPositionTicks,
                )
            /*
             * W16 解码回退链第 2 档：本地硬解报「解码能力不足」后，按回退档位强制服务器转码——
             * 明确不允许直连 / 直传（enableDirectPlay / enableDirectStream = false），否则服务器会
             * 认为参数没超限继续 DirectPlay，客户端还是解不了。用户显式选码率后档位会清零。
             */
            val deviceProfile = request.deviceProfile
            Timber.d(
                "getMediaSources bitrate=%d maxStreamingBitrate=%d transcoding=%b forceTranscode=%b directPlay=%d transcodingProfiles=%d startTimeTicks=%d",
                appPreferences.getPlayerStreamingBitrate(),
                request.maxStreamingBitrate ?: 0,
                request.enableTranscoding == true,
                request.enableDirectPlay == false && request.enableDirectStream == false,
                deviceProfile?.directPlayProfiles?.size ?: 0,
                deviceProfile?.transcodingProfiles?.size ?: 0,
                request.startTimeTicks ?: 0L,
            )
            val sources = mutableListOf<FindroidSource>()
            sources.addAll(
                jellyfinApi.mediaInfoApi
                    .getPostedPlaybackInfo(itemId, request)
                    .content
                    .mediaSources
                    .map { it.toFindroidSource(this@JellyfinRepositoryImpl, itemId, includePath) }
            )
            sources.addAll(
                database
                    .getSources(itemId)
                    .map { it.toFindroidSource(database) }
                    .filter { it.isAvailableForSourceSelection() }
            )
            sources
        }

    /**
     * V2-DOWNLOAD 03：音乐下载走 `/Audio/{itemId}/stream.{container}`（容器取自媒体源元数据）， 视频 / 书籍仍走官方
     * `/Items/{id}/Download`。容器缺失或非法时回退官方端点并记录日志，不静默失败。
     */
    private fun downloadUrlForMediaSource(
        item: BaseItemDto,
        mediaSource: MediaSourceInfo,
        baseUrl: String,
        itemId: UUID,
        officialDownloadUrl: String,
    ): String {
        if (item.type != BaseItemKind.AUDIO) return officialDownloadUrl
        val streamUrl =
            MusicDownloadUrl.build(
                baseUrl = baseUrl,
                itemId = itemId,
                container = mediaSource.container,
            )
        if (streamUrl == null) {
            Timber.w(
                "音乐媒体源缺少可用容器，回退官方下载端点：item=%s source=%s container=%s",
                itemId,
                mediaSource.id,
                mediaSource.container,
            )
            return officialDownloadUrl
        }
        return streamUrl
    }

    private suspend fun completedLocalSource(itemId: UUID): FindroidSource? =
        withContext(Dispatchers.IO) {
            database.getCompletedSource(itemId)?.toFindroidSource(database)?.takeIf {
                it.isAvailableForSourceSelection()
            }
        }

    /**
     * W9-T5（F4）：离线 / 已下载条目的本地播放源。
     *
     * 口径 = 「下载行里这个 itemId 的 LOCAL 源 + 文件真的在」：不看当前会话的 serverId / userId（下载行自身带着身份），
     * 也不要求状态列的文本与当前枚举完全一致（历史 / 旧引擎写下的状态只用于诊断）；`.download` 残片与缺失文件不算。 只查本机数据库，不发任何网络请求。
     */
    override suspend fun getDownloadedLocalSource(itemId: UUID): FindroidSource? =
        withContext(Dispatchers.IO) {
            val candidates =
                database.getSources(itemId).map { dto -> dto.toFindroidSource(database) }
            val playable = candidates.firstOrNull { source -> source.isPlayableDownloadedFile() }
            if (playable == null && candidates.isNotEmpty()) {
                Timber.d(
                    "已下载本地源未命中：item=%s candidates=%s",
                    itemId,
                    candidates.joinToString { source ->
                        "${source.id}(${source.type},${source.downloadTaskStatus},${source.path})"
                    },
                )
            }
            playable
        }

    /** 本地文件存在且不是 `.download` 残片（`content://` 视为可读文档）。 */
    private fun FindroidSource.isPlayableDownloadedFile(): Boolean {
        if (type != FindroidSourceType.LOCAL) return false
        val rawPath = path.trim()
        if (rawPath.isEmpty() || rawPath.endsWith(".download", ignoreCase = true)) {
            return false
        }
        if (rawPath.startsWith("content://", ignoreCase = true)) return true
        return runCatching { File(rawPath).let { file -> file.isFile && file.length() > 0L } }
            .getOrDefault(false)
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

    override suspend fun markAsFavorite(itemId: UUID): UserDataWriteOutcome {
        // W69：收藏状态内嵌在各列表的条目里，本地落库后直接失效会话缓存（下一次读取静默取新值）。
        invalidateMetadataCache()
        val outcome =
            writeUserData(
                itemId = itemId,
                remote = { jellyfinApi.userLibraryApi.markFavoriteItem(itemId) },
                persistLocal = { userId -> persistFavoriteLocally(userId, itemId, true) },
            )
        // W60b：单一数据源广播（本地状态已落库，详情 / 列表 / 收藏页统一靠它刷新）。
        if (outcome.localStateChanged) UserDataEvents.notifyFavoriteChanged()
        return outcome
    }

    override suspend fun unmarkAsFavorite(itemId: UUID): UserDataWriteOutcome {
        invalidateMetadataCache()
        val outcome =
            writeUserData(
                itemId = itemId,
                remote = { jellyfinApi.userLibraryApi.unmarkFavoriteItem(itemId) },
                persistLocal = { userId -> persistFavoriteLocally(userId, itemId, false) },
            )
        // W60b：单一数据源广播（取消收藏同样通知所有展示方刷新）。
        if (outcome.localStateChanged) UserDataEvents.notifyFavoriteChanged()
        return outcome
    }

    /**
     * W9-T4：收藏落库前确保本地行存在。
     *
     * 影视条目读详情时会 `getUserDataOrCreateNew` 建行，音乐条目没有任何投影行——纯 UPDATE 会静默命中 0 行，排队 `toBeSynced`
     * 也落不了地（WRITE-05 音乐侧缺口）。收藏语义不关心 played / 进度，所以新行用默认值建、随后只写收藏位。
     */
    private suspend fun persistFavoriteLocally(userId: UUID, itemId: UUID, favorite: Boolean) {
        database.getUserDataOrCreateNew(itemId, userId)
        database.setFavorite(userId, itemId, favorite)
    }

    override suspend fun markAsPlayed(itemId: UUID): UserDataWriteOutcome {
        invalidateMetadataCache()
        return writeUserData(
            itemId = itemId,
            remote = { jellyfinApi.playStateApi.markPlayedItem(itemId) },
            persistLocal = { userId -> database.setPlayed(userId, itemId, true) },
        )
    }

    override suspend fun markAsUnplayed(itemId: UUID): UserDataWriteOutcome {
        invalidateMetadataCache()
        return writeUserData(
            itemId = itemId,
            remote = { jellyfinApi.playStateApi.markUnplayedItem(itemId) },
            persistLocal = { userId -> database.setPlayed(userId, itemId, false) },
        )
    }

    /**
     * W8-T5：删除服务器条目（`DELETE /Items/{itemId}`）。
     *
     * 与用户数据写不同，删除**不存在排队补偿**：网络 / 5xx / 403 都在本地保持原样；只有服务器确认后才清本地缓存与下载记录。
     */
    override suspend fun deleteItem(itemId: UUID): UserDataWriteOutcome =
        withContext(Dispatchers.IO) {
            try {
                jellyfinApi.libraryApi.deleteItem(itemId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return@withContext UserDataWriteOutcome.Failed(error.toUserDataWriteFailure())
            }
            // 服务器已删除：本地清理是尽力而为，失败也不能把已完成的删除报告成失败。
            try {
                clearDeletedItemLocalState(itemId)
            } catch (error: Exception) {
                Timber.w(error, "Local cleanup after deleting item %s failed", itemId)
            }
            // 列表 / 详情都可能缓存了该条目，统一失效（含磁盘快照），下次读取直接打服务器。
            invalidateMetadataCache()
            UserDataEvents.notifyContentChanged()
            UserDataWriteOutcome.Confirmed
        }

    /** 删除服务器条目后的本地清理：条目行、用户数据、来源（含媒体流级联）、下载意图。 */
    private suspend fun clearDeletedItemLocalState(itemId: UUID) {
        // 同一 itemId 只可能落在 movies 或 episodes 之一；两条都删无害，避免客户端再猜条目类型。
        database.deleteMovie(itemId)
        database.deleteEpisode(itemId)
        database.deleteUserData(itemId)
        database.getSources(itemId).forEach { source ->
            database.deleteMediaStreamsBySourceId(source.id)
            database.deleteSource(source.id)
        }
        database.deleteDownloadIntent(itemId)
    }

    /**
     * W8-T6：读取条目元数据快照（名称 / 简介 / 排序名称）。
     *
     * 走原始 JSON 通道（`GET /Items/{id}` 的字节原文）而不是 SDK 类型化 DTO：SDK 的 DateTime 序列化器会把服务端的
     * `0001-01-01T00:00:00.0000000Z`（DateTime.MinValue）重写成设备时区的历史 LMT 偏移（如 `+08:05:43`）， 服务端
     * System.Text.Json 无法解析，写路径会稳定 400（见 [updateItemMetadata]）。
     *
     * 排序名称取 `ForcedSortName ?: SortName`：`SortName` 是服务器派生的只读回显值，只有 `ForcedSortName`
     * 才是用户自定义项（Jellyfin 12.4.0 `ItemUpdateController` 只消费后者）。预填显示当前生效的排序名。
     *
     * 任何失败（HTTP 异常 / 非 200 / JSON 解析失败）都返回 null（fail-closed），入口据此禁用提交。
     */
    override suspend fun fetchItemMetadataSnapshot(itemId: UUID): ItemMetadataSnapshot? =
        withContext(Dispatchers.IO) {
            val raw = rawItemJson(itemId) ?: return@withContext null
            try {
                val json = Json.parseToJsonElement(raw).jsonObject
                ItemMetadataSnapshot(
                    itemId = itemId,
                    name = json.stringOrNull("Name").orEmpty(),
                    overview = json.stringOrNull("Overview"),
                    sortName = json.stringOrNull("ForcedSortName") ?: json.stringOrNull("SortName"),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "解析条目 %s 的元数据快照失败", itemId)
                null
            }
        }

    /**
     * W8-T6：编辑条目元数据（`POST /Items/{itemId}`）。
     *
     * 根因：SDK 的 `userLibraryApi.getItem` + `itemUpdateApi.updateItem` 组合会把 `BaseItemDto`
     * 重新序列化，DateTime 字段按设备时区重写（`0001-01-01T00:00:00Z` → `0001-01-01T08:05:43+08:05:43`），服务端无法解析 →
     * 稳定 400； 现代日期也会被改写成 `+08:00`，同样篡改了服务端原值。因此写路径不再经过类型化 DTO，而是取 GET 的 JSON 原文、只替换三个键 后原样
     * POST（其余键值保持服务端格式）。
     *
     * 三个键：`Name`、`Overview`、`ForcedSortName`（服务端只认它，`SortName` 是派生的只读回显值）；传 null 等于清除自定义
     * 排序名，服务端回落到派生值。
     *
     * 请求体用 `FileInfo(bytes, "application/json")` 而不是 String：SDK 的 String body 分支固定发
     * `text/plain`，ASP.NET Core 的 `[FromBody]` 只接受 `application/json`（415）；两条分支都不经
     * ApiSerializer，字节内容一致。
     *
     * 失败语义与 T1 一致：`ApiClient.request` 对非 2xx 抛 `InvalidStatusException`，403 → 权限拒绝、5xx → 服务端错误、 IO
     * / 超时 → 网络失败；不排队补偿，本地不做任何改动。
     */
    override suspend fun updateItemMetadata(
        itemId: UUID,
        name: String,
        overview: String?,
        sortName: String?,
    ): UserDataWriteOutcome =
        withContext(Dispatchers.IO) {
            try {
                val original =
                    Json.parseToJsonElement(rawItemResponse(itemId).body.decodeToString())
                        .jsonObject
                jellyfinApi.api.request(
                    method = HttpMethod.POST,
                    pathTemplate = "/Items/$itemId",
                    queryParameters = emptyMap(),
                    requestBody =
                        FileInfo(
                            metadataUpdateBody(original, name, overview, sortName)
                                .encodeToByteArray(),
                            "application/json",
                        ),
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                return@withContext UserDataWriteOutcome.Failed(error.toUserDataWriteFailure())
            }
            invalidateMetadataCache()
            UserDataEvents.notifyContentChanged()
            UserDataWriteOutcome.Confirmed
        }

    /** `GET /Items/{id}` 的原始响应（2xx 才返回；非 2xx 由 SDK 抛 `InvalidStatusException`，沿用 T1 异常映射）。 */
    private suspend fun rawItemResponse(itemId: UUID): RawResponse =
        jellyfinApi.api.request(
            method = HttpMethod.GET,
            pathTemplate = "/Items/$itemId",
            queryParameters = mapOf("userId" to jellyfinApi.userId),
        )

    /** 同 [rawItemResponse]，但任何失败都返回 null（读快照是 fail-closed）。 */
    private suspend fun rawItemJson(itemId: UUID): String? =
        try {
            val response = rawItemResponse(itemId)
            if (response.status == 200) response.body.decodeToString() else null
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Timber.w(error, "读取条目 %s 的原始 JSON 失败", itemId)
            null
        }

    /** 只替换三个可编辑键，其余键值按服务端原文保留（键顺序不变）。 */
    private fun metadataUpdateBody(
        original: JsonObject,
        name: String,
        overview: String?,
        sortName: String?,
    ): String {
        val fields = original.toMutableMap()
        fields["Name"] = JsonPrimitive(name)
        fields["Overview"] = overview?.let(::JsonPrimitive) ?: JsonNull
        fields["ForcedSortName"] = sortName?.let(::JsonPrimitive) ?: JsonNull
        return JsonObject(fields).toString()
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * W8-T1：用户数据写（收藏 / 已看）的统一落点。
     *
     * W8-T1r：排队时触发一次带网络约束的补同步；服务器确认时顺手清掉该行残留的 `toBeSynced` 标志。
     */
    private suspend fun writeUserData(
        itemId: UUID,
        remote: suspend () -> Unit,
        persistLocal: suspend (userId: UUID) -> Unit,
    ): UserDataWriteOutcome =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId!!
            runUserDataWrite(
                remote = remote,
                persistLocal = { persistLocal(userId) },
                markForSync = { database.setUserDataToBeSynced(userId, itemId, true) },
                clearForSync = { database.setUserDataToBeSynced(userId, itemId, false) },
                scheduleSync = { userDataSyncScheduler.schedule() },
            )
        }

    override fun getBaseUrl() = jellyfinApi.api.baseUrl.orEmpty()

    override fun getAccessToken(): String? = jellyfinApi.api.accessToken

    /** MUSIC-03：`/Audio/{itemId}/universal`（协议拼装集中在 [MusicStreamUrl]；鉴权由播放器注入请求头，不进 URL）。 */
    override fun getMusicStreamUrl(itemId: UUID, request: MusicStreamRequest): String? =
        MusicStreamUrl.build(
            baseUrl = getBaseUrl(),
            itemId = itemId,
            userId = jellyfinApi.userId?.toString(),
            deviceId = jellyfinApi.deviceId,
            request = request,
        )

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
                ?.let { tag -> ImageCacheRules.primaryImageUrl(getBaseUrl(), itemId, tag) }
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
                                ImageCacheRules.primaryImageUrl(baseUrl, dto.id, tag)
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
