package com.zhangwenkang.cinefin.player.local.domain

import androidx.core.net.toUri
import androidx.media3.common.MimeTypes
import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.models.FindroidChapter
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidSources
import com.zhangwenkang.cinefin.player.core.domain.models.ExternalSubtitle
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerChapter
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
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
    var currentItemIndex: Int = 0

    suspend fun getInitialItem(
        itemId: UUID,
        itemKind: BaseItemKind,
        mediaSourceIndex: Int? = null,
        startFromBeginning: Boolean = false,
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
                    val nextUpEpisode = repository.getNextUp(itemId).firstOrNull()

                    val season =
                        if (nextUpEpisode != null) {
                            repository.getSeason(nextUpEpisode.seasonId)
                        } else {
                            val seasons = repository.getSeasons(itemId)
                            if (seasons.isEmpty()) {
                                return null
                            }
                            seasons.first()
                        }

                    val episodes =
                        loadSeriesEpisodes(
                            seriesId = itemId,
                            fields = listOf(ItemFields.CHAPTERS, ItemFields.TRICKPLAY),
                            fallbackSeasonId = season.id,
                        )

                    if (episodes.isEmpty()) {
                        return null
                    }

                    val episode = nextUpEpisode ?: episodes.first()

                    items = episodes
                    episode
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
                        return null
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
                else -> null
            }

        if (initialItem == null) {
            return null
        }

        startItem = initialItem

        currentItemIndex = items.indexOfFirst { it.id == initialItem.id }

        val playbackPosition =
            if (!startFromBeginning) initialItem.playbackPositionTicks.div(10000) else 0
        val playerItem = initialItem.toPlayerItem(mediaSourceIndex, playbackPosition)
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
     * 影阁：整部剧的播放队列 = **所有季的所有集**，按「季号 → 集号」排序。
     *
     * 之前只取当前这一季，导致播放队列面板显示不全（用户反馈：应能看到该季所有集，
     * 以及其他季的所有集，并按季分组）。排序后队列面板可以直接按季号分组显示。
     */
    private suspend fun loadSeriesEpisodes(
        seriesId: UUID,
        fields: List<ItemFields>,
        fallbackSeasonId: UUID? = null,
    ): List<FindroidEpisode> =
        runCatching {
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

    private suspend fun FindroidItem.toPlayerItem(
        mediaSourceIndex: Int?,
        playbackPosition: Long,
    ): PlayerItem {
        Timber.d("Converting FindroidItem ${this.id} to PlayerItem")

        val mediaSources = repository.getMediaSources(id, true)
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
            externalSubtitles = externalSubtitles,
            chapters = chapters.toPlayerChapters(),
            trickplayInfo = trickplayInfo,
        )
    }

    private fun List<FindroidChapter>.toPlayerChapters(): List<PlayerChapter> {
        return this.map { chapter ->
            PlayerChapter(startPosition = chapter.startPosition, name = chapter.name)
        }
    }
}
