package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.MetadataNamespace
import com.zhangwenkang.cinefin.repository.MetadataSnapshotReadResult
import com.zhangwenkang.cinefin.repository.MetadataSnapshotStore
import com.zhangwenkang.cinefin.repository.UserDataWriteFailure
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
import com.zhangwenkang.cinefin.repository.toUserDataWriteFailure
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CreatePlaylistDto
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.ItemFilter
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetInstantMixFromSongRequest

/** W37 曲目来源（在线融合：全部 / 服务器 / 本地）。 */
enum class MusicItemSource(val label: String) {
    SERVER("服务器"),
    LOCAL("本地"),
}

/** 音乐曲库来源筛选档位。 */
enum class MusicItemSourceFilter(val label: String) {
    ALL("全部"),
    SERVER("服务器"),
    LOCAL("本地");

    companion object {
        fun fromName(raw: String?): MusicItemSourceFilter =
            entries.firstOrNull { it.name == raw } ?: ALL
    }
}

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
    /** 服务端收藏状态（W21-R2；写入走 `/UserFavoriteItems/{id}`，读回走 `filters=IsFavorite`）。 */
    val isFavorite: Boolean = false,
    /** W37：本地媒体库曲目的 SAF 文档 URI（null = 服务器曲目）。 */
    val localUri: String? = null,
    /** W37：来源（在线融合的来源徽标 / 筛选）。 */
    val source: MusicItemSource = MusicItemSource.SERVER,
    /** 本地媒体库 id；仅本地来源有值，进入本地歌单稳定身份时必须存在。 */
    val localLibraryId: Long? = null,
    /** 本地歌单投影附带的持久条目 id；不在歌单中的曲目为 null。 */
    val playlistEntryId: String? = null,
    /** 本地歌单持久身份；标题 / 路径 / URI 不参与该身份。 */
    val trackRef: TrackRef? = null,
    /** 当前曲库无法解析该持久身份时为 true，仍保留数据库行与快照。 */
    val isPlaylistPlaceholder: Boolean = false,
    /** 服务器曲目快照的持久来源；本地曲目为 null。 */
    val serverId: String? = null,
    val userId: String? = null,
    // MUSIC-02/35：解析链与 TrackSource 对齐的音频参数（取不到为 null，不伪造 0）
    /** 专辑艺人（服务器 `AlbumArtists[0]` / 本地内嵌标签）。 */
    val albumArtist: String? = null,
    /** 碟号（服务器 `ParentIndexNumber` / 本地内嵌标签）。 */
    val discNumber: Int? = null,
    /** 流派（首个）。 */
    val genre: String? = null,
    /** 发行年份。 */
    val year: Int? = null,
    /** 源编码（服务器 `MediaStream.codec` / 本地扩展名）。 */
    val codec: String? = null,
    /** 源码率（bit/s）。 */
    val bitrate: Int? = null,
    /** 采样率（Hz）。 */
    val sampleRate: Int? = null,
    /** 位深（bit）。 */
    val bitDepth: Int? = null,
    /** 源容器（服务器 `Container` / 本地扩展名）：协商直连容器表用。 */
    val container: String? = null,
    /** MUSIC-36：本地格式引擎解不了（APE / DSD）→ 曲目行弱提示「不支持」，不静默失败。 */
    val formatUnsupported: Boolean = false,
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
    val kind: MusicPlaylistKind = MusicPlaylistKind.SERVER,
)

enum class MusicPlaylistKind {
    SERVER,
    LOCAL,
}

/** 曲库快照：一次请求拿到的全部曲目 + 客户端聚合出的专辑 / 艺术家。 */
data class MusicLibrary(
    val songs: List<MusicSong>,
    val albums: List<MusicAlbum>,
    val artists: List<MusicArtist>,
)

/**
 * W8-T4：新建服务器歌单的结果。
 *
 * 成功只认服务器返回的歌单 id（禁止本地乐观插入）；失败沿用 W8-T1 的分类，由 UI 区分权限拒绝与可重试失败。
 */
sealed interface ServerPlaylistCreateOutcome {
    data class Created(val playlistId: UUID) : ServerPlaylistCreateOutcome

    data class Failed(val failure: UserDataWriteFailure) : ServerPlaylistCreateOutcome
}

/** 音乐曲库仓库（W1 R2 建立，W2 R2 扩展浏览维度）。 */
interface MusicRepository {
    /**
     * 全部音乐曲目与客户端聚合结果（专辑 / 艺术家 / 歌曲三个维度共用一次请求）。
     *
     * [libraryId] 非空时只取该音乐库的曲目（客户端设置「音乐库」；null = 自动，服务器上全部音乐库）。
     */
    suspend fun getLibrary(libraryId: UUID? = null): MusicLibrary

    /** 服务器歌单列表；用户主动刷新时 [forceRefresh] 绕过 fresh 摘要。 */
    suspend fun getPlaylists(forceRefresh: Boolean = false): List<MusicPlaylist>

    /** 歌单内曲目（按服务器返回顺序）。 */
    suspend fun getPlaylistSongs(playlistId: UUID): List<MusicSong>

    /** 收藏 / 取消收藏（服务端 UserData 白名单）。W8-T1：结果区分权限拒绝与可重试失败。 */
    suspend fun setFavorite(itemId: UUID, favorite: Boolean): UserDataWriteOutcome

    /** 服务端收藏的曲目（`filters=IsFavorite` + `includeItemTypes=Audio`）。 */
    suspend fun getFavoriteSongs(): List<MusicSong>

    /** W58：把曲目从服务器歌单移除（编辑歌单，非删除媒体；纯服务器写白名单）。W8-T1：结果区分权限拒绝与可重试失败。 */
    suspend fun removeFromPlaylist(playlistId: UUID, itemId: UUID): UserDataWriteOutcome

    /**
     * W8-T4：在服务器新建音频歌单（`POST /Playlists`），[itemIds] 非空时同一次请求内一并加入。
     *
     * 结果以服务器返回的歌单 id 为准；成功后清歌单卡片缓存，由调用方按服务端回读刷新列表。
     */
    suspend fun createServerPlaylist(
        name: String,
        itemIds: List<UUID> = emptyList(),
    ): ServerPlaylistCreateOutcome

    /** W8-T4：把曲目加入服务器歌单（`POST /Playlists/{id}/Items`）。W8-T1：结果区分权限拒绝与可重试失败。 */
    suspend fun addToPlaylist(playlistId: UUID, itemIds: List<UUID>): UserDataWriteOutcome

    /**
     * W5-T12（MUSIC-14 Auto DJ）：以 [seedItemId] 为种子的即时混排曲目（`GET /Audio/{itemId}/instant-mix`，只读）。
     *
     * [limit] 为期望条数；服务器可能返回更少。网络 / API 失败直接抛异常，由调用方决定静默跳过。
     */
    suspend fun getInstantMix(seedItemId: UUID, limit: Int): List<MusicSong>
}

/**
 * InstantMix 请求形状（W5-T12）。
 *
 * 单测直接校验 itemId / userId / limit 的透传与映射所需字段（音频流 + 图片 + UserData）， 保证仓库层没有把种子或批次大小截断 / 改写。
 */
internal fun instantMixRequest(
    itemId: UUID,
    userId: UUID,
    limit: Int,
): GetInstantMixFromSongRequest =
    GetInstantMixFromSongRequest(
        itemId = itemId,
        userId = userId,
        limit = limit,
        fields = listOf(ItemFields.MEDIA_STREAMS),
        enableImages = true,
        enableUserData = true,
    )

/**
 * W8-T4：解析服务端返回的歌单 id。
 *
 * Jellyfin 实测（10.11）返回 32 位无连字符十六进制（如 `8f3d487b0e86f69c0a841aa9aaa157ac`），标准 UUID 文本（8-4-4-4-12）
 * 也要兼容；两种都解析不了时抛 [IllegalArgumentException]，由统一写边界归为 `Unknown` 失败。
 */
private fun parseServerPlaylistId(raw: String): UUID {
    val trimmed = raw.trim()
    val dashed =
        if (trimmed.length == SERVER_ID_HEX_LENGTH && '-' !in trimmed) {
            "${trimmed.substring(0, 8)}-${trimmed.substring(8, 12)}-${trimmed.substring(12, 16)}-" +
                "${trimmed.substring(16, 20)}-${trimmed.substring(20, 32)}"
        } else {
            trimmed
        }
    return UUID.fromString(dashed)
}

private const val SERVER_ID_HEX_LENGTH = 32

@Singleton
class MusicRepositoryImpl
@Inject
constructor(
    private val jellyfinRepository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
    private val appPreferences: AppPreferences,
    private val metadataSnapshotStore: MetadataSnapshotStore,
) : MusicRepository {

    private val playlistCardCache =
        ServerPlaylistCardCache(
            readSnapshot = { namespace, key, maxAgeMs ->
                val hit =
                    metadataSnapshotStore.read(
                        namespace = namespace,
                        logicalKey = key,
                        maxAgeMs = maxAgeMs,
                    ) as? MetadataSnapshotReadResult.Hit
                val raw = hit?.value as? String
                raw?.let { encoded ->
                    ServerPlaylistCardCache.decodeCards(encoded)?.let { cards ->
                        CachedPlaylistCards(cards = cards, savedAtMs = hit.savedAtEpochMs)
                    }
                }
            },
            writeSnapshot = { namespace, key, cards, savedAtMs ->
                metadataSnapshotStore.write(
                    namespace = namespace,
                    logicalKey = key,
                    value = ServerPlaylistCardCache.encodeCards(cards),
                    savedAtEpochMs = savedAtMs,
                )
            },
            deleteSnapshot = { namespace, key ->
                metadataSnapshotStore.delete(namespace = namespace, logicalKey = key)
            },
        )

    override suspend fun getLibrary(libraryId: UUID?): MusicLibrary =
        withContext(Dispatchers.IO) {
            val songs = querySongs(libraryId)
            MusicLibrary(
                songs = songs,
                albums = groupAlbums(songs),
                artists = groupArtists(songs),
            )
        }

    override suspend fun getPlaylists(forceRefresh: Boolean): List<MusicPlaylist> =
        withContext(Dispatchers.IO) {
            val namespace = currentNamespace() ?: return@withContext emptyList()
            val cards =
                playlistCardCache.load(
                    namespace = namespace,
                    key = PLAYLIST_CARD_CACHE_KEY,
                    isCurrent = { currentNamespace() == namespace },
                    forceRefresh = forceRefresh,
                    fetch = {
                        jellyfinApi.itemsApi
                            .getItems(
                                UUID.fromString(namespace.userId),
                                includeItemTypes = listOf(BaseItemKind.PLAYLIST),
                                recursive = true,
                                sortBy = listOfNotNull(ItemSortBy.fromName("SortName")),
                                sortOrder = listOfNotNull(SortOrder.fromName("Ascending")),
                                limit = PLAYLIST_LIMIT,
                            )
                            .content
                            .items
                            .mapNotNull { item ->
                                val title =
                                    item.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                                CachedPlaylistCard(
                                    id = item.id,
                                    name = title,
                                    songCount = item.childCount,
                                    imageTag = item.imageTags?.get(ImageType.PRIMARY),
                                )
                            }
                    },
                )
            val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
            cards.map { card ->
                MusicPlaylist(
                    id = card.id,
                    name = card.name,
                    imageUri =
                        card.imageTag?.let { tag ->
                            ImageCacheRules.primaryImageUrl(baseUrl, card.id, tag)
                        },
                    songCount = card.songCount,
                )
            }
        }

    override suspend fun getPlaylistSongs(playlistId: UUID): List<MusicSong> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
            val namespace = currentNamespace()
            jellyfinApi.playlistsApi
                .getPlaylistItems(
                    playlistId = playlistId,
                    userId = userId,
                    limit = SONG_LIMIT,
                    fields = listOf(ItemFields.MEDIA_STREAMS),
                    enableImages = true,
                    enableUserData = true,
                )
                .content
                .items
                .mapNotNull { item -> item.toMusicSong(baseUrl, namespace) }
        }

    override suspend fun setFavorite(itemId: UUID, favorite: Boolean): UserDataWriteOutcome =
        withContext(Dispatchers.IO) {
            if (jellyfinApi.userId == null) {
                return@withContext UserDataWriteOutcome.Failed(
                    UserDataWriteFailure.Unknown(status = null, message = "尚未登录服务器")
                )
            }
            // W9-T4：与影视详情页共用同一写模型（服务器先行；网络 / 5xx 落本地行 + `toBeSynced` 排队，由 SyncWorker
            // 补传），不再单独维护一套「音乐收藏不排队」的失败语义。
            if (favorite) {
                jellyfinRepository.markAsFavorite(itemId)
            } else {
                jellyfinRepository.unmarkAsFavorite(itemId)
            }
        }

    override suspend fun removeFromPlaylist(
        playlistId: UUID,
        itemId: UUID,
    ): UserDataWriteOutcome {
        val namespace = currentNamespace()
        return withContext(Dispatchers.IO) {
            musicServerWrite(
                    remote = {
                        jellyfinApi.playlistsApi.removeItemFromPlaylist(
                            playlistId = playlistId.toString(),
                            entryIds = listOf(itemId.toString()),
                        )
                    },
                    onConfirmed = {
                        namespace?.let { playlistCardCache.invalidate(it, PLAYLIST_CARD_CACHE_KEY) }
                    },
                )
                .toUserDataWriteOutcome()
        }
    }

    override suspend fun createServerPlaylist(
        name: String,
        itemIds: List<UUID>,
    ): ServerPlaylistCreateOutcome {
        val namespace = currentNamespace()
        return withContext(Dispatchers.IO) {
            when (
                val write =
                    musicServerWrite(
                        remote = {
                            val userId = jellyfinApi.userId ?: error("尚未登录服务器")
                            val created =
                                jellyfinApi.playlistsApi
                                    .createPlaylist(
                                        CreatePlaylistDto(
                                            name = name,
                                            ids = itemIds,
                                            userId = userId,
                                            mediaType = MediaType.AUDIO,
                                            users = emptyList(),
                                            isPublic = false,
                                        )
                                    )
                                    .content
                            parseServerPlaylistId(created.id)
                        },
                        onConfirmed = {
                            namespace?.let {
                                playlistCardCache.invalidate(it, PLAYLIST_CARD_CACHE_KEY)
                            }
                        },
                    )
            ) {
                is MusicServerWrite.Written -> ServerPlaylistCreateOutcome.Created(write.value)
                is MusicServerWrite.Rejected -> ServerPlaylistCreateOutcome.Failed(write.failure)
            }
        }
    }

    override suspend fun addToPlaylist(
        playlistId: UUID,
        itemIds: List<UUID>,
    ): UserDataWriteOutcome {
        val namespace = currentNamespace()
        return withContext(Dispatchers.IO) {
            musicServerWrite(
                    remote = {
                        val userId = jellyfinApi.userId ?: error("尚未登录服务器")
                        jellyfinApi.playlistsApi.addItemToPlaylist(playlistId, itemIds, userId)
                    },
                    onConfirmed = {
                        namespace?.let { playlistCardCache.invalidate(it, PLAYLIST_CARD_CACHE_KEY) }
                    },
                )
                .toUserDataWriteOutcome()
        }
    }

    /** 写边界结果：成功值（如新建歌单 id）只在服务器确认后交给调用方映射。 */
    private sealed interface MusicServerWrite<out T> {
        data class Written<T>(val value: T) : MusicServerWrite<T>

        data class Rejected(val failure: UserDataWriteFailure) : MusicServerWrite<Nothing>
    }

    private fun MusicServerWrite<*>.toUserDataWriteOutcome(): UserDataWriteOutcome =
        when (this) {
            is MusicServerWrite.Written -> UserDataWriteOutcome.Confirmed
            is MusicServerWrite.Rejected -> UserDataWriteOutcome.Failed(failure)
        }

    /**
     * W8-T1：音乐侧服务器写统一错误边界（歌单条目 / 歌单新建：这两类没有可排队的本地镜像，失败如实上报，由 `UserDataWriteFailure.retryable` 让 UI
     * 区分权限拒绝与可重试失败）。
     *
     * W9-T4 起音乐收藏不再走这里——它复用影视详情路径的写模型，走 `JellyfinRepository.markAsFavorite` 的排队补偿。
     */
    private suspend fun <T> musicServerWrite(
        remote: suspend () -> T,
        onConfirmed: suspend (T) -> Unit,
    ): MusicServerWrite<T> =
        try {
            val value = remote()
            onConfirmed(value)
            MusicServerWrite.Written(value)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            MusicServerWrite.Rejected(error.toUserDataWriteFailure())
        }

    override suspend fun getFavoriteSongs(): List<MusicSong> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
            val namespace = currentNamespace()
            jellyfinApi.itemsApi
                .getItems(
                    userId,
                    filters = listOf(ItemFilter.IS_FAVORITE),
                    includeItemTypes = listOf(BaseItemKind.AUDIO),
                    recursive = true,
                    fields = listOf(ItemFields.MEDIA_STREAMS),
                    enableUserData = true,
                    limit = SONG_LIMIT,
                )
                .content
                .items
                .mapNotNull { item -> item.toMusicSong(baseUrl, namespace) }
        }

    override suspend fun getInstantMix(seedItemId: UUID, limit: Int): List<MusicSong> =
        withContext(Dispatchers.IO) {
            val userId = jellyfinApi.userId ?: return@withContext emptyList()
            val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
            val namespace = currentNamespace()
            jellyfinApi.instantMixApi
                .getInstantMixFromSong(instantMixRequest(seedItemId, userId, limit))
                .content
                .items
                .mapNotNull { item -> item.toMusicSong(baseUrl, namespace) }
        }

    private suspend fun querySongs(libraryId: UUID?): List<MusicSong> {
        val userId = jellyfinApi.userId ?: return emptyList()
        val baseUrl = jellyfinRepository.getBaseUrl().trimEnd('/')
        val namespace = currentNamespace()
        return jellyfinApi.itemsApi
            .getItems(
                userId,
                parentId = libraryId,
                includeItemTypes = listOf(BaseItemKind.AUDIO),
                recursive = true,
                sortBy = listOfNotNull(ItemSortBy.fromName("SortName")),
                sortOrder = listOfNotNull(SortOrder.fromName("Ascending")),
                fields = listOf(ItemFields.MEDIA_STREAMS),
                enableUserData = true,
                limit = SONG_LIMIT,
            )
            .content
            .items
            .mapNotNull { item -> item.toMusicSong(baseUrl, namespace) }
    }

    private fun BaseItemDto.toMusicSong(
        baseUrl: String,
        namespace: MetadataNamespace?,
    ): MusicSong? {
        val title = name?.takeIf { it.isNotBlank() } ?: return null
        val runtimeTicks = runTimeTicks ?: 0L
        // MUSIC-02：音频流参数决定直连 / 转码（列表接口带 MediaStreams 时才有值）
        val audioStream = mediaStreams?.firstOrNull { it.type == MediaStreamType.AUDIO }
        val streamBitrate: Int? = audioStream?.bitRate?.takeIf { it > 0 }
        val sourceBitrate: Int? = streamBitrate
        val streamCodec: String? = audioStream?.codec?.takeIf { it.isNotBlank() }
        val sourceCodec: String? = streamCodec
        val sourceContainer: String? = container?.takeIf { it.isNotBlank() } ?: streamCodec
        return MusicSong(
            itemId = id,
            name = title,
            albumName = album?.takeIf { it.isNotBlank() } ?: UNKNOWN_ALBUM,
            artist = albumArtists?.firstOrNull()?.name ?: artists?.firstOrNull(),
            indexNumber = indexNumber,
            runtimeTicks = runtimeTicks,
            imageUri = imageUri(baseUrl),
            resumePositionMs = resumePositionMs(userData?.playbackPositionTicks, runtimeTicks),
            isFavorite = userData?.isFavorite == true,
            serverId = namespace?.serverId,
            userId = namespace?.userId,
            albumArtist = albumArtists?.firstOrNull()?.name,
            discNumber = parentIndexNumber,
            genre = genres?.firstOrNull()?.takeIf { it.isNotBlank() },
            year = productionYear,
            codec = sourceCodec,
            bitrate = sourceBitrate,
            sampleRate = audioStream?.sampleRate?.takeIf { it > 0 },
            bitDepth = audioStream?.bitDepth?.takeIf { it > 0 },
            container = sourceContainer,
        )
    }

    private fun BaseItemDto.imageUri(baseUrl: String): String? =
        imageTags?.get(ImageType.PRIMARY)?.let { tag ->
            ImageCacheRules.primaryImageUrl(baseUrl, id, tag)
        }

    private fun currentNamespace(): MetadataNamespace? {
        val serverId = appPreferences.getValue(appPreferences.currentServer)
        val userId = jellyfinApi.userId?.toString()
        if (serverId.isNullOrBlank() || userId.isNullOrBlank()) return null
        return MetadataNamespace(serverId = serverId, userId = userId)
    }

    private companion object {
        /**
         * 单次曲库 / 歌单曲目拉取上限（W78：500 → 1000）。
         *
         * 音乐播放队列直接由曲库列表补齐，所以这个上限就是"队列能有多长"的实际上限； 用户 2026-10-17 要求「播放队列 > 500 首」（#38）。
         */
        const val SONG_LIMIT = 1000
        const val PLAYLIST_LIMIT = 200
        const val PLAYLIST_CARD_CACHE_KEY = "music-playlists:card:v1:200"
        const val UNKNOWN_ALBUM = "未分类"
    }
}
