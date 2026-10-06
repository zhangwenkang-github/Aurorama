package com.zhangwenkang.cinefin.player.local.domain

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.models.FindroidChapter
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidSources
import com.zhangwenkang.cinefin.player.core.domain.models.ExternalSubtitle
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerMediaInfo
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.player.core.domain.models.TrickplayInfo
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import java.util.UUID
import javax.inject.Inject
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

class PlaylistManager @Inject internal constructor(private val repository: JellyfinRepository) {
    private var startItem: FindroidItem? = null
    private var items: List<FindroidItem> = emptyList()
    private val playerItems: MutableList<PlayerItem> = mutableListOf()

    /** 构建播放信息失败过的条目：补队列时跳过，避免对同一集反复请求 */
    private val failedItemIds: MutableSet<UUID> = mutableSetOf()

    var currentItemIndex: Int = 0

    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        startFromBeginning: Boolean = false,
        startPositionMs: Long? = null,
    ): PlayerItem? {
        Timber.d("Retrieving initial player item")

        val initialItem =
            when (itemKind) {
                BaseItemKind.MOVIE -> {
                    val movie = repository.getMovie(itemId)

                    items = listOf(movie)
                    movie
                }
                BaseItemKind.SERIES -> {
                    /*
                     * W76-B11：系列级「播放」= **续播**（服务器 NextUp）→ 否则**第一季第一集**。
                     *
                     * 旧实现把「NextUp / 季 / 集任一取空」收成 `return null`：播放页拿到 null 就停在空载态
                     * （`00:00/00:00`、队列为空）且**全程无提示**。现在解析链上的每一步失败都只降级、不取空
                     * （NextUp 取不到 → 第一季；归属季取不到 → 第一季），最终确实没有可播的集时抛
                     * [PlaybackStartException]，由播放页把消息提示给用户。
                     */
                    val nextUpEpisode = runCatching {
                        repository.getNextUp(itemId).firstOrNull()
                    }
                        .onFailure { Timber.w(it, "系列级播放：拉取 NextUp 失败，回退第一季第一集") }
                        .getOrNull()

                    val episodes =
                        loadSeriesEpisodes(
                            seriesId = itemId,
                            fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            fallbackSeasonId = resolveSeriesFallbackSeasonId(itemId, nextUpEpisode),
                        )

                    val plan =
                        planSeriesPlayback(nextUpEpisode, episodes)
                            ?: throw PlaybackStartException("这部剧暂时没有可播放的剧集，请稍后重试")

                    items = plan.episodes
                    plan.episodes[plan.startIndex]
                }
                BaseItemKind.SEASON -> {
                    val season = repository.getSeason(itemId)
                    val episodes =
                        loadSeriesEpisodes(
                            seriesId = season.seriesId,
                            fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            fallbackSeasonId = season.id,
                        )

                    if (episodes.isEmpty()) {
                        throw PlaybackStartException("这一季暂时没有可播放的剧集，请稍后重试")
                    }

                    // 从这一季的第一集开始播
                    val episode =
                        episodes.firstOrNull { it.seasonId == season.id } ?: episodes.first()

                    items = episodes
                    episode
                }
                BaseItemKind.EPISODE -> {
                    val episode = repository.getEpisode(itemId)

                    val episodes =
                        loadSeriesEpisodes(
                            seriesId = episode.seriesId,
                            fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            fallbackSeasonId = episode.seasonId,
                        )

                    items = episodes
                    episode
                }
                // 未知类型不应该静默给一个空载播放页（W76-B11 的同类缺陷），直接给用户一句可见提示。
                else -> throw PlaybackStartException("暂不支持播放该类型的条目")
            }

        startItem = initialItem

        currentItemIndex = items.indexOfFirst { it.id == initialItem.id }

        val playbackPosition =
            when {
                startPositionMs != null && startPositionMs > 0L -> startPositionMs
                !startFromBeginning -> initialItem.playbackPositionTicks.div(10000)
                else -> 0L
            }
        val playerItem = initialItem.toPlayerItem(mediaSourceIndex, playbackPosition)
        playerItems.add(playerItem)

        return playerItem
    }

    /**
     * W58b：**显式播放队列**（视频多选批量播放）。
     *
     * 与 [getInitialItem] 的「按条目类型展开」不同：队列条目由调用方给出（已解析成电影 / 单集）， 本函数只按需取回条目并起播 [preferredItemId]
     * 那一项（找不到时从队首开始）， 其余条目由播放页后台补进队列（[buildPlayerItemAt]）。
     */
    suspend fun getInitialItemForQueue(
        entries: List<PlaybackQueueEntry>,
        preferredItemId: UUID? = null,
        startFromBeginning: Boolean = false,
        startPositionMs: Long? = null,
    ): PlayerItem? {
        if (entries.isEmpty()) return null
        val resolvedItems = entries.mapNotNull { entry ->
            runCatching {
                when (entry.kind) {
                    BaseItemKind.MOVIE -> repository.getMovie(entry.itemId)
                    BaseItemKind.EPISODE -> repository.getEpisode(entry.itemId)
                    else -> null
                }
            }
                .getOrNull()
        }
        if (resolvedItems.isEmpty()) return null
        val startIndex =
            resolvedItems.indexOfFirst { it.id == preferredItemId }.takeIf { it >= 0 } ?: 0
        val initialItem = resolvedItems[startIndex]
        items = resolvedItems
        startItem = initialItem
        currentItemIndex = startIndex
        val playbackPosition =
            when {
                startPositionMs != null && startPositionMs > 0L -> startPositionMs
                startFromBeginning -> 0L
                else -> initialItem.playbackPositionTicks.div(10000)
            }
        val playerItem = initialItem.toPlayerItem(null, playbackPosition)
        playerItems.add(playerItem)
        return playerItem
    }

    suspend fun getPreviousPlayerItem(): PlayerItem? {
        Timber.d("Retrieving previous player item")

        val itemIndex = currentItemIndex - 1
        val playerItem =
            when (startItem) {
                is FindroidMovie -> null
                is FindroidEpisode -> {
                    if (currentItemIndex == 0) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve previous player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    suspend fun getNextPlayerItem(): PlayerItem? {
        Timber.d("Retrieving next player item")

        val itemIndex = currentItemIndex + 1
        val playerItem =
            when (startItem) {
                is FindroidMovie -> null
                is FindroidEpisode -> {
                    if (currentItemIndex == items.lastIndex) {
                        null
                    } else {
                        val item = items[itemIndex]
                        if (playerItems.firstOrNull { it.itemId == item.id } == null) {
                            try {
                                item.toPlayerItem(null, 0L)
                            } catch (e: Exception) {
                                Timber.e("Failed to retrieve next player item: $e")
                                null
                            }
                        } else {
                            null
                        }
                    }
                }
                else -> null
            }

        if (playerItem != null) {
            playerItems.add(playerItem)
        }

        return playerItem
    }

    fun setCurrentMediaItemIndex(itemId: UUID) {
        currentItemIndex = items.indexOfFirst { it.id == itemId }
    }

    /**
     * 取已构建过的播放信息。
     *
     * 用于「从通知回到播放页」这类没有重新走 [getInitialItem] 的场景：字幕源清单 随 [PlayerItem] 一起缓存，拿回它就还能继续做字幕面板与自研渲染。
     */
    fun getPlayerItem(itemId: UUID): PlayerItem? = playerItems.firstOrNull { it.itemId == itemId }

    /**
     * W73（#7）：按目标位置重建条目播放信息（转码 seek 超出可用窗口时重开转码会话）。
     *
     * 重新请求 PlaybackInfo 并携 `startTimeTicks = positionMs × 10000`，服务器从目标位置开始生成分片；新条目写回 [playerItems]
     * 缓存（同条目旧 URL 作废），播放侧用 `replaceMediaItem` 原位替换，不重启 Activity。
     */
    suspend fun rebuildPlayerItemForPosition(itemId: UUID, positionMs: Long): PlayerItem? {
        val item =
            items.firstOrNull { it.id == itemId }
                ?: startItem?.takeIf { it.id == itemId }
                ?: return null
        return runCatching { item.toPlayerItem(null, positionMs.coerceAtLeast(0L)) }
            .onSuccess { rebuilt ->
                playerItems.removeAll { it.itemId == itemId }
                playerItems.add(rebuilt)
                failedItemIds.remove(itemId)
            }
            .onFailure {
                Timber.w(
                    it,
                    "重建播放条目失败（转码 seek 重开会话）：item=%s position=%d",
                    itemId,
                    positionMs,
                )
            }
            .getOrNull()
    }

    /**
     * W19：按条目 id 找队列里的原始条目（回退 / 手动切内核重启时用它把播放页恢复到正在播的那一条）。
     *
     * 季 / 剧集入口与队列换集时，Intent 里的原始条目和实际播放条目不是同一条。
     */
    fun findItem(itemId: UUID): FindroidItem? =
        items.firstOrNull { it.id == itemId } ?: startItem?.takeIf { it.id == itemId }

    /**
     * 播放队列在「整剧 / 整季 / 单片」层面的条目数。
     *
     * 注意读的是**清单** [items]（元数据已经全部拿到），不是已构建播放信息的条目数： 队列面板要显示完整剧集，而每集的播放信息（流地址 / 外挂字幕）是按需逐集构建的。
     */
    val queueSize: Int
        get() = items.size

    /** 当前播放条目在队列里的位置（与 [queueSize] 同一坐标系） */
    val queueIndex: Int
        get() = currentItemIndex

    /**
     * 按队列位置构建播放条目：已经构建过的直接复用，构建失败记下来并返回 null（调用方跳过）。
     *
     * 一次整剧可能有几十集，每集都要打一次播放信息接口，所以不做"开播前全量构建"， 而是由播放页在起播之后按顺序逐集调用（见
     * `PlayerViewModel.fillQueueInBackground`）。
     */
    suspend fun buildPlayerItemAt(index: Int): PlayerItem? {
        val item = items.getOrNull(index) ?: return null
        if (item.id in failedItemIds) return null
        playerItems
            .firstOrNull { it.itemId == item.id }
            ?.let {
                return it
            }
        return runCatching { item.toPlayerItem(null, 0L) }
            .onSuccess { playerItems.add(it) }
            .onFailure {
                // 记下失败的条目，避免补队列时对同一集反复重试
                failedItemIds.add(item.id)
                Timber.w(it, "构建队列第 %d 项失败，跳过这一集", index)
            }
            .getOrNull()
    }

    /**
     * 极光幕：整部剧的播放队列 = **所有季的所有集**，按「季号 → 集号」排序。
     *
     * 之前只取当前这一季，导致播放队列面板显示不全（用户反馈：应能看到该季所有集， 以及其他季的所有集，并按季分组）。排序后队列面板可以直接按季号分组显示。
     */
    private suspend fun loadSeriesEpisodes(
        seriesId: UUID,
        fields: List<ItemFields>,
        fallbackSeasonId: UUID? = null,
    ): List<FindroidEpisode> = runCatching {
        repository
            .getSeasons(seriesId)
            .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
            .flatMap { season ->
                repository
                    .getEpisodes(seriesId = seriesId, seasonId = season.id, fields = fields)
                    .filter { !it.missing }
                    .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
            }
    }
        .getOrElse { error ->
            // 拉整剧失败也绝不能让播放起不来：退回到「只加载当前这一季」
            Timber.w(error, "拉取整剧集数失败，回退到当前季")
            fallbackSeasonId
                ?.let { seasonId ->
                    repository
                        .getEpisodes(seriesId = seriesId, seasonId = seasonId, fields = fields)
                        .filter { !it.missing }
                }
                .orEmpty()
        }

    /**
     * 系列级播放的「兜底季」：优先 NextUp 归属的那一季（整剧拉取失败时至少要能播这一季）， 否则按队列排序取季号最小的一季（= 第一季）。
     *
     * 两步都可能失败（网络 / 条目异常），失败只降级到下一步，不再返回 null 让播放页空载。
     */
    private suspend fun resolveSeriesFallbackSeasonId(
        seriesId: UUID,
        nextUpEpisode: FindroidEpisode?,
    ): UUID? {
        val nextUpSeason = nextUpEpisode?.let { episode ->
            runCatching { repository.getSeason(episode.seasonId) }
                .onFailure { Timber.w(it, "系列级播放：拉取 NextUp 归属季失败，回退第一季") }
                .getOrNull()
        }
        if (nextUpSeason != null) return nextUpSeason.id

        return runCatching { repository.getSeasons(seriesId) }
            .onFailure { Timber.w(it, "系列级播放：拉取季列表失败（由整剧拉取兜底）") }
            .getOrNull()
            ?.let(::pickFallbackSeason)
            ?.id
    }

    private suspend fun FindroidItem.toPlayerItem(
        mediaSourceIndex: Int?,
        playbackPosition: Long,
    ): PlayerItem {
        Timber.d("Converting FindroidItem ${this.id} to PlayerItem")

        /*
         * W73（#7）：带起播位置请求播放信息——服务器转码会话从 [playbackPosition] 开始生成分片，
         * 而不是恒从 0 开始追赶（窗口外 seek 长卡 / 回片头的根因）。
         */
        val mediaSources =
            repository.getMediaSources(id, true, startPositionTicks = playbackPosition * 10000L)
        val mediaSource =
            if (mediaSourceIndex == null) {
                mediaSources.firstOrNull { it.type == FindroidSourceType.LOCAL } ?: mediaSources[0]
            } else {
                mediaSources[mediaSourceIndex]
            }
        val externalSubtitles =
            mediaSource.mediaStreams
                .filter { mediaStream ->
                    mediaStream.isExternal &&
                        mediaStream.type == MediaStreamType.SUBTITLE &&
                        !mediaStream.path.isNullOrBlank()
                }
                .map { mediaStream ->
                    ExternalSubtitle(
                        title = mediaStream.title,
                        // 归一化为 BCP-47 标签，便于按语言优先级自动选中正确字幕
                        language =
                            LanguageMatcher.detect(
                                mediaStream.language,
                                mediaStream.title,
                                mediaStream.path,
                            ) ?: mediaStream.language,
                        uri = mediaStream.path!!.toUri(),
                        mimeType =
                            when (mediaStream.codec) {
                                "subrip" -> MimeTypes.APPLICATION_SUBRIP
                                "webvtt" -> MimeTypes.APPLICATION_SUBRIP
                                "ass" -> MimeTypes.TEXT_SSA
                                else -> MimeTypes.TEXT_UNKNOWN
                            },
                    )
                }
        /*
         * 字幕面板与自研字幕渲染读的清单：包含内嵌字幕在内**全部**字幕流。
         *
         * 内嵌字幕同样能从 Jellyfin 拿到独立字幕文件（服务端的 SubtitleProfile 声明了
         * srt / ass 以 External 方式交付），所以「内嵌字幕」也能调延迟、做双语；
         * 拿不到地址或解析不了的（图形字幕）会在面板里标注并退回播放内核渲染。
         */
        val subtitleSources =
            mediaSource.mediaStreams
                .filter { mediaStream -> mediaStream.type == MediaStreamType.SUBTITLE }
                .map { mediaStream ->
                    val language =
                        LanguageMatcher.detect(
                            mediaStream.language,
                            mediaStream.title,
                            mediaStream.path,
                        ) ?: mediaStream.language
                    PlayerSubtitleSource(
                        index = mediaStream.index,
                        title =
                            mediaStream.title.ifBlank {
                                mediaStream.displayTitle ?: language.ifBlank { "字幕" }
                            },
                        language = language,
                        uri = mediaStream.path.orEmpty(),
                        codec = mediaStream.codec,
                        isGraphic = PlayerSubtitleSource.isGraphicCodec(mediaStream.codec),
                        isExternal = mediaStream.isExternal,
                        isDefault = mediaStream.isDefault,
                        isForced = mediaStream.isForced,
                    )
                }
                .sortedBy { it.index }
        val trickplayInfo =
            when (this) {
                is FindroidSources -> {
                    this.trickplayInfo?.get(mediaSource.id)?.let {
                        TrickplayInfo(
                            width = it.width,
                            height = it.height,
                            tileWidth = it.tileWidth,
                            tileHeight = it.tileHeight,
                            thumbnailCount = it.thumbnailCount,
                            interval = it.interval,
                            bandwidth = it.bandwidth,
                        )
                    }
                }
                else -> null
            }
        return PlayerItem(
            name = name,
            itemId = id,
            mediaSourceId = mediaSource.id,
            // 服务器要转码时用转码地址（否则 Hi10P 之类会「有声音、进度在走、画面全黑」）
            mediaSourceUri = mediaSource.transcodingPath ?: mediaSource.path,
            playbackPosition = playbackPosition,
            parentIndexNumber = if (this is FindroidEpisode) parentIndexNumber else null,
            indexNumber = if (this is FindroidEpisode) indexNumber else null,
            indexNumberEnd = if (this is FindroidEpisode) indexNumberEnd else null,
            // 剧集用缩略图（16:9），其它条目退回海报：队列列表与通知封面共用这一个地址
            thumbnailUri = (images.primary ?: images.backdrop)?.toString(),
            externalSubtitles = externalSubtitles,
            subtitleSources = subtitleSources,
            chapters = chapters.toPlayerChapters(),
            trickplayInfo = trickplayInfo,
            mediaInfo =
                buildSourceMediaInfo(
                    source = mediaSource,
                    playbackUri = mediaSource.transcodingPath ?: mediaSource.path,
                ),
        )
    }

    /**
     * 媒体源元数据 → 播放信息快照（§1.8 的第一层：Jellyfin 侧）。
     *
     * 这里只填 API 明确给出的字段；分辨率 / 帧率 / 码率 / HDR 的实测值由播放内核在面板打开时补， 两边都取不到的字段留 null，UI 统一显示「—」。
     */
    private fun buildSourceMediaInfo(source: FindroidSource, playbackUri: String): PlayerMediaInfo {
        val video = source.mediaStreams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val audio = source.mediaStreams.firstOrNull { it.type == MediaStreamType.AUDIO }
        return PlayerMediaInfo(
            // 播放地址是 Jellyfin 的 /Videos/<id>/stream（没有后缀），容器名优先从源文件名猜
            container = inferContainerFromUri(playbackUri) ?: inferContainerFromUri(source.name),
            videoCodec = video?.codec?.takeIf { it.isNotBlank() },
            width = video?.width?.takeIf { it > 0 },
            height = video?.height?.takeIf { it > 0 },
            hdr = hdrFromJellyfin(video?.videoRangeType?.name, video?.videoDoViTitle),
            audioCodec = audio?.codec?.takeIf { it.isNotBlank() },
            audioChannels = channelsFromLayout(audio?.channelLayout),
            fileSizeBytes = source.size.takeIf { it > 0 },
            path = playbackUri.takeIf { it.isNotBlank() },
        )
    }

    private fun List<FindroidChapter>.toPlayerChapters(): List<PlayerChapter> {
        return this.map { chapter ->
            PlayerChapter(startPosition = chapter.startPosition, name = chapter.name)
        }
    }
}

/**
 * W76-B11：系列级播放的规划结果。
 *
 * [startIndex] 是 [episodes] 里的起播下标（续播 / 第一集），[episodes] 即整剧播放队列。
 */
internal data class SeriesPlaybackPlan(
    val episodes: List<FindroidEpisode>,
    val startIndex: Int,
)

/**
 * 系列级播放的「兜底季」= 季号最小的一季（= 第一季）。
 *
 * 排序口径与 [PlaylistManager] 的队列排序一致（`indexNumber` 升序，同号取先出现的）；拿不到季列表时返回 null， 由调用方决定降级。 纯函数，便于单测。
 */
internal fun pickFallbackSeason(seasons: List<FindroidSeason>): FindroidSeason? =
    seasons.minByOrNull {
        it.indexNumber
    }

/**
 * 规划系列级播放：**优先 NextUp 续播**，NextUp 缺失 / 不在这一轮队列里时回退到队首（= 第一季第一集）。
 *
 * [episodes] 为空 = 这部剧没有可播的集 → 返回 null，调用方据此抛 [PlaybackStartException]（用户可见提示）， 不再静默返回 null
 * 让播放页空载。纯函数，便于单测。
 */
internal fun planSeriesPlayback(
    nextUpEpisode: FindroidEpisode?,
    episodes: List<FindroidEpisode>,
): SeriesPlaybackPlan? {
    if (episodes.isEmpty()) return null
    val nextUpIndex =
        nextUpEpisode?.let { candidate -> episodes.indexOfFirst { it.id == candidate.id } } ?: -1
    return SeriesPlaybackPlan(
        episodes = episodes,
        startIndex = if (nextUpIndex >= 0) nextUpIndex else 0,
    )
}
