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

    override suspend fun getPublicSystemInfo(): PublicSystemInfo =
        withContext(Dispatchers.IO) { jellyfinApi.systemApi.getPublicSystemInfo().content }

    override suspend fun getUserViews(): List<BaseItemDto> =
        withContext(Dispatchers.IO) {
            jellyfinApi.viewsApi.getUserViews(jellyfinApi.userId!!).content.items
        }

    override suspend fun getEpisode(itemId: UUID): FindroidEpisode =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toFindroidEpisode(this@JellyfinRepositoryImpl, database)!!
        }

    override suspend fun getMovie(itemId: UUID): FindroidMovie =
        withContext(Dispatchers.IO) {
            localLibrary.syntheticMovie(itemId)?.let {
                return@withContext it
            }
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toFindroidMovie(this@JellyfinRepositoryImpl, database)
        }

    override suspend fun getShow(itemId: UUID): FindroidShow =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toFindroidShow(this@JellyfinRepositoryImpl)
        }

    override suspend fun getSeason(itemId: UUID): FindroidSeason =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getItem(itemId, jellyfinApi.userId!!)
                .content
                .toFindroidSeason(this@JellyfinRepositoryImpl)
        }

    override suspend fun getLibraries(): List<FindroidCollection> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    // W8-R3：媒体库卡片要显示"共 N 个项目"——库列表默认不返回 ChildCount，显式要一次
                    // （只影响这一个请求的体积，不额外发请求）。
                    fields = listOf(ItemFields.CHILD_COUNT),
                )
                .content
                .items
                .mapNotNull { it.toFindroidCollection(this@JellyfinRepositoryImpl) }
        }

    override suspend fun getItem(itemId: UUID): FindroidItem? =
        withContext(Dispatchers.IO) {
            localLibrary.syntheticMovie(itemId)?.let {
                return@withContext it
            }
            jellyfinApi.userLibraryApi
                .getItem(itemId = itemId, userId = jellyfinApi.userId!!)
                .content
                .toFindroidItem(this@JellyfinRepositoryImpl, database)
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
                config = PagingConfig(pageSize = 10, enablePlaceholders = false),
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

    override suspend fun getLibrarySuggestions(
        parentId: UUID,
        includeTypes: List<BaseItemKind>?,
        limit: Int,
    ): List<FindroidItem> =
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

    override suspend fun getUpcomingEpisodes(parentId: UUID, limit: Int): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.showsApi
                .getUpcomingEpisodes(jellyfinApi.userId!!, parentId = parentId, limit = limit)
                .content
                .items
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getGenres(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidTag> =
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

    override suspend fun getStudios(
        parentId: UUID,
        includeItemTypes: List<BaseItemKind>?,
    ): List<FindroidTag> =
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

    override suspend fun getFavoriteItems(): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.itemsApi
                .getItems(
                    jellyfinApi.userId!!,
                    filters = listOf(ItemFilter.IS_FAVORITE),
                    includeItemTypes =
                        listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES, BaseItemKind.EPISODE),
                    recursive = true,
                )
                .content
                .items
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSearchItems(query: String): List<FindroidItem> =
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

    override suspend fun getSuggestions(): List<FindroidItem> =
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

    override suspend fun getResumeItems(includeItemTypes: List<BaseItemKind>): List<FindroidItem> =
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

    override suspend fun getLatestMedia(parentId: UUID): List<FindroidItem> =
        withContext(Dispatchers.IO) {
            jellyfinApi.userLibraryApi
                .getLatestMedia(jellyfinApi.userId!!, parentId = parentId, limit = 16)
                .content
                .mapNotNull { it.toFindroidItem(this@JellyfinRepositoryImpl, database) }
        }

    override suspend fun getSeasons(seriesId: UUID, offline: Boolean): List<FindroidSeason> =
        withContext(Dispatchers.IO) {
            if (!offline) {
                jellyfinApi.showsApi.getSeasons(seriesId, jellyfinApi.userId!!).content.items.map {
                    it.toFindroidSeason(this@JellyfinRepositoryImpl)
                }
            } else {
                database.getSeasonsByShowId(seriesId).map {
                    it.toFindroidSeason(database, jellyfinApi.userId!!)
                }
            }
        }

    override suspend fun getNextUp(seriesId: UUID?): List<FindroidEpisode> =
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

    override suspend fun getEpisodes(
        seriesId: UUID,
        seasonId: UUID,
        fields: List<ItemFields>?,
        startItemId: UUID?,
        limit: Int?,
        offline: Boolean,
    ): List<FindroidEpisode> =
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

    override suspend fun getMediaSources(itemId: UUID, includePath: Boolean): List<FindroidSource> =
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
                "getMediaSources bitrate=%d maxStreamingBitrate=%d transcoding=%b forceTranscode=%b profiles=%d",
                streamingBitrate,
                maxStreamingBitrate,
                transcodingEnabled,
                forceTranscode,
                transcodingProfiles.size,
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
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, true)
            try {
                jellyfinApi.userLibraryApi.markFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun unmarkAsFavorite(itemId: UUID) {
        withContext(Dispatchers.IO) {
            database.setFavorite(jellyfinApi.userId!!, itemId, false)
            try {
                jellyfinApi.userLibraryApi.unmarkFavoriteItem(itemId)
            } catch (_: Exception) {
                database.setUserDataToBeSynced(jellyfinApi.userId!!, itemId, true)
            }
        }
    }

    override suspend fun markAsPlayed(itemId: UUID) {
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
