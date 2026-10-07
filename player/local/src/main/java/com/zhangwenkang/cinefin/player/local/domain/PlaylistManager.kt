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
import kotlinx.coroutines.CancellationException
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemFields
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber

class PlaylistManager @Inject internal constructor(private val repository: JellyfinRepository) {
    private var startItem: FindroidItem? = null
    private var items: List<FindroidItem> = emptyList()
    private val playerItems: MutableList<PlayerItem> = mutableListOf()

    /**
     * W76-B11b：整剧队列的「后台补全」请求。
     *
     * 系列 / 季 / 集级入口起播时只带**一条**起播集，整剧枚举交给播放页在起播之后调 [expandPendingSeriesQueue]
     * 补上——旧实现把枚举放在起播之前，是「点了播放先空白 7.8–31.7 s」的主因。
     */
    internal var pendingSeriesQueueExpansion: SeriesQueueExpansion? = null
        private set

    /** 队列枚举用的字段：章节 + Trickplay 与「后台补全」共用一份缓存键，避免重复请求。 */
    private val seriesQueueFields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY)

    /** 构建播放信息失败过的条目：补队列时跳过，避免对同一集反复请求 */
    private val failedItemIds: MutableSet<UUID> = mutableSetOf()

    /**
     * W76-Q6：播放信息解析结果的「请求去重 / 复用」缓存。
     *
     * 同一条目 + 同一起播位置只请求一次 `PlaybackInfo`：既覆盖「起播集（快路径）与后台补队列撞车」，也覆盖 「重复入队 / 并发补片」——旧实现只靠
     * `playerItems` 清单去重，两者都挡不住。
     */
    private val playbackRequestCache = PlaybackRequestCache()

    var currentItemIndex: Int = 0

    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        startFromBeginning: Boolean = false,
        startPositionMs: Long? = null,
    ): PlayerItem? {
        Timber.d("Retrieving initial player item")

        // W76-B11b：每次解析新条目都先清掉上一次的「骨架队列」标记（电影 / 显式队列播放用不到它）
        pendingSeriesQueueExpansion = null

        val initialItem =
            when (itemKind) {
                BaseItemKind.MOVIE -> {
                    val movie = repository.getMovie(itemId)

                    items = listOf(movie)
                    movie
                }
                BaseItemKind.SERIES -> {
                    /*
                     * W76-B11：系列级「播放」= **续播**（服务器 NextUp）→ 否则**第一季第一集**；解析链上每一步失败
                     * 都只降级、不取空（NextUp 取不到 → 第一季；归属季取不到 → 季号最小的季），确实没有可播的集时抛
                     * [PlaybackStartException]，由播放页把消息提示给用户。
                     *
                     * W76-B11b：拿到「续播那一集」就**先起播**，整剧队列交给播放页在起播之后调
                     * [expandPendingSeriesQueue] 补。旧实现把整剧枚举排在起播之前（getNextUp → 逐季 getEpisodes，
                     * 5 次串行 HTTP），真机实测这段时间播放页一直停在 `00:00/00:00` + 队列为空 + 无提示的空白态。
                     */
                    val nextUpEpisode = runCatchingCancellable {
                        repository.getNextUp(itemId).firstOrNull()
                    }
                        .onFailure { Timber.w(it, "系列级播放：拉取 NextUp 失败，回退第一季第一集") }
                        .getOrNull()

                    if (nextUpEpisode != null) {
                        // 快路径：只解析这一集（补齐章节 / Trickplay；同一份季集响应会被后台整剧补全命中缓存）
                        val startEpisode = enrichEpisodeWithFields(itemId, nextUpEpisode)
                        pendingSeriesQueueExpansion =
                            SeriesQueueExpansion(
                                seriesId = itemId,
                                anchorItemId = startEpisode.id,
                                fallbackSeasonId = startEpisode.seasonId,
                            )
                        items = listOf(startEpisode)
                        startEpisode
                    } else {
                        // 兜底：服务器没给续播（整部看完 / 无 NextUp）时仍然从第一季第一集起播（需枚举整剧）
                        val episodes =
                            loadSeriesEpisodes(
                                seriesId = itemId,
                                fields = seriesQueueFields,
                                fallbackSeasonId = resolveSeriesFallbackSeasonId(itemId, null),
                            )
                        val plan =
                            planSeriesPlayback(null, episodes)
                                ?: throw PlaybackStartException("这部剧暂时没有可播放的剧集，请稍后重试")

                        items = plan.episodes
                        plan.episodes[plan.startIndex]
                    }
                }
                BaseItemKind.SEASON -> {
                    val season = repository.getSeason(itemId)

                    // W76-B11b：先只取「这一季」的集列表拿到起播集 → 立即起播；整剧队列后台补（同 SERIES 分支）
                    val seasonEpisodes = runCatchingCancellable {
                        repository
                            .getEpisodes(
                                seriesId = season.seriesId,
                                seasonId = season.id,
                                fields = seriesQueueFields,
                            )
                            .filter { !it.missing }
                            .sortedBy { it.indexNumber ?: Int.MAX_VALUE }
                    }
                        .onFailure { Timber.w(it, "季级播放：拉取本季集列表失败，回退整剧枚举") }
                        .getOrNull()
                        .orEmpty()

                    val startEpisode = seasonEpisodes.firstOrNull()
                    if (startEpisode != null) {
                        pendingSeriesQueueExpansion =
                            SeriesQueueExpansion(
                                seriesId = season.seriesId,
                                anchorItemId = startEpisode.id,
                                fallbackSeasonId = season.id,
                            )
                        items = listOf(startEpisode)
                        startEpisode
                    } else {
                        val episodes =
                            loadSeriesEpisodes(
                                seriesId = season.seriesId,
                                fields = seriesQueueFields,
                                fallbackSeasonId = season.id,
                            )

                        if (episodes.isEmpty()) {
                            throw PlaybackStartException("这一季暂时没有可播放的剧集，请稍后重试")
                        }

                        // 从这一季的第一集开始播
                        val episode =
                            pickSeasonStartEpisode(episodes, season.id) ?: episodes.first()

                        items = episodes
                        episode
                    }
                }
                BaseItemKind.EPISODE -> {
                    // W76-B11b：单集入口同样先起播（只 1 次 getItem），整剧队列交给后台补全
                    val episode = repository.getEpisode(itemId)

                    pendingSeriesQueueExpansion =
                        SeriesQueueExpansion(
                            seriesId = episode.seriesId,
                            anchorItemId = episode.id,
                            fallbackSeasonId = episode.seasonId,
                        )
                    items = listOf(episode)
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
        // W76-B11b：显式队列播放不走「骨架队列 + 后台补全」
        pendingSeriesQueueExpansion = null
        if (entries.isEmpty()) return null
        val resolvedItems = entries.mapNotNull { entry ->
            runCatchingCancellable {
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
        // W76-Q6：重开会话必须拿新地址，强制绕过缓存并覆盖旧结果。
        return runCatching {
            item.toPlayerItem(null, positionMs.coerceAtLeast(0L), forceRefresh = true)
        }
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

    /** W76-Q6：本播放页累计的播放信息「实际请求 / 复用缓存」次数（真机取证：对照整剧逐集请求的规模）。 */
    internal fun playbackRequestStats(): PlaybackRequestStats = playbackRequestCache.stats()

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
    ): List<FindroidEpisode> = runCatchingCancellable {
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
            runCatchingCancellable { repository.getSeason(episode.seasonId) }
                .onFailure { Timber.w(it, "系列级播放：拉取 NextUp 归属季失败，回退第一季") }
                .getOrNull()
        }
        if (nextUpSeason != null) return nextUpSeason.id

        return runCatchingCancellable { repository.getSeasons(seriesId) }
            .onFailure { Timber.w(it, "系列级播放：拉取季列表失败（由整剧拉取兜底）") }
            .getOrNull()
            ?.let(::pickFallbackSeason)
            ?.id
    }

    /**
     * W76-B11b：给「用轻量接口拿到的起播集」补齐章节 / Trickplay。
     *
     * `getNextUp` / `getItem` 都不带 `ItemFields`，直接拿它们起播会让这一集的章节刻度缺失。这里只多拉「它所属那一季」的
     * 集列表——后台补全整剧时同一个缓存键（[seriesQueueFields] + 同 seasonId）会直接命中，不产生第二次请求。
     * 拉不到就用原始条目起播（宁可少章节标记，也不能耽误起播）。
     */
    private suspend fun enrichEpisodeWithFields(
        seriesId: UUID,
        episode: FindroidEpisode,
    ): FindroidEpisode =
        runCatchingCancellable {
            repository
                .getEpisodes(
                    seriesId = seriesId,
                    seasonId = episode.seasonId,
                    fields = seriesQueueFields,
                )
                .firstOrNull { it.id == episode.id }
        }
            .onFailure { Timber.w(it, "系列级播放：补齐起播集章节信息失败，用原始条目起播") }
            .getOrNull() ?: episode

    /**
     * W76-B11b：把「骨架队列」（只有起播那一集）补成整剧队列。
     *
     * 由播放页在**起播之后**调用（见 `PlayerViewModel.fillQueueInBackground` 的调用点）。锚点集不在整剧列表里 （被 `missing`
     * 过滤等）时不改 [items]，返回 false——宁可队列只有一条，也不能让 [currentItemIndex] 与播放器时间线错位。
     */
    suspend fun expandPendingSeriesQueue(): Boolean {
        val request = pendingSeriesQueueExpansion ?: return false
        pendingSeriesQueueExpansion = null
        if (items.size > 1) return true

        val episodes =
            loadSeriesEpisodes(
                seriesId = request.seriesId,
                fields = seriesQueueFields,
                fallbackSeasonId = request.fallbackSeasonId,
            )
        if (episodes.isEmpty()) return false

        val anchorIndex = seriesQueueAnchorIndex(episodes, request.anchorItemId)
        if (anchorIndex < 0) {
            Timber.w("整剧队列补全：起播集 %s 不在整剧列表里，保持单条队列", request.anchorItemId)
            return false
        }

        items = episodes
        currentItemIndex = anchorIndex
        return episodes.size > 1
    }

    private suspend fun FindroidItem.toPlayerItem(
        mediaSourceIndex: Int?,
        playbackPosition: Long,
        forceRefresh: Boolean = false,
    ): PlayerItem =
        playbackRequestCache.resolve(
            key = playbackRequestKey(id, mediaSourceIndex, playbackPosition * 10000L),
            forceRefresh = forceRefresh,
        ) {
            buildPlayerItem(mediaSourceIndex, playbackPosition)
        }

    /** W76-Q6：缓存层之下的真正解析（请求 `PlaybackInfo` 并组装条目），只由 [toPlayerItem] 调用。 */
    private suspend fun FindroidItem.buildPlayerItem(
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
 * W76-B11b：整剧队列的「后台补全」请求（[PlaylistManager.pendingSeriesQueueExpansion]）。
 *
 * [anchorItemId] = 已经起播的那一集的 id，补全后要保证它在 [PlaylistManager.currentItemIndex] 上原地不动；
 * [fallbackSeasonId] 用于整剧拉取失败时至少还能补上这一季。
 */
internal data class SeriesQueueExpansion(
    val seriesId: UUID,
    val anchorItemId: UUID,
    val fallbackSeasonId: UUID?,
)

/**
 * W76-B11b：骨架队列补全后，**起播集**在整剧列表里的下标；不在列表里（被 `missing` 过滤等）返回 -1。
 *
 * 调用方据此决定「改不改 `currentItemIndex`」——返回 -1 时保持单条队列，宁可队列只有一条，也不能让队列下标 与播放器时间线错位（错位会导致「下一集」跳到别的集 /
 * 自动连播串集）。纯函数，便于单测。
 */
internal fun seriesQueueAnchorIndex(episodes: List<FindroidEpisode>, anchorItemId: UUID): Int =
    episodes.indexOfFirst {
        it.id == anchorItemId
    }

/** W76-B11b：季级入口的起播集 = 这一季的第一集（[episodes] 已按「季号 → 集号」排好序）； 列表里没有这一季的集时退回列表第一集（沿用旧行为）。纯函数，便于单测。 */
internal fun pickSeasonStartEpisode(
    episodes: List<FindroidEpisode>,
    seasonId: UUID,
): FindroidEpisode? = episodes.firstOrNull { it.seasonId == seasonId } ?: episodes.firstOrNull()

/**
 * W76-B11b：**可取消**的 `runCatching`。
 *
 * 起播解析链上每一步失败都只降级（NextUp 取不到 → 第一季……），所以到处是 `runCatching`；但 `runCatching` 连 [CancellationException]
 * 一起吞：在起播窗口内退出播放页时，被取消的请求会「伪装成空列表」，最后走到「这部剧暂时没有可播放的剧集」
 * 的兜底提示——用户明明只是退出了播放页，却收到一句报错。这里把取消原样抛出，只让**真正的失败**参与降级。 纯函数，便于单测。
 */
internal inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

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
