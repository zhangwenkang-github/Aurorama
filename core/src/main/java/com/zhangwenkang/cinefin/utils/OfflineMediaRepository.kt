package com.zhangwenkang.cinefin.utils

import android.content.Context
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * W36 离线媒体目录：本机已下载的媒体（视频 / 音乐 / 书籍），**不依赖当前服务器会话与登录账号**。
 *
 * 数据来源：
 * - 视频 / 音乐：Room `sources` 表里已完成的 LOCAL 来源（+ `download_media.tsv` 侧车区分音乐、 `episodes` / `shows` /
 *   `seasons` 表补节目与季归属）；
 * - 书籍：`ReaderRepository.listLocalFiles()`（`files/books/`）。
 *
 * 「允许离线模式观看」开关：视频 / 音乐写 `sources.allowOffline`，书籍写 `AppPreferences.offlineBlockedBooks`。
 */
enum class OfflineMediaEntryKind {
    VIDEO,
    MUSIC,
    BOOK,
}

/** 离线媒体库的单条目（扁平结构；层级由 UI 侧按系列 / 专辑分组）。 */
data class OfflineMediaEntry(
    val itemId: UUID,
    val name: String,
    val kind: OfflineMediaEntryKind,
    val sizeBytes: Long = 0L,
    val sourceId: String? = null,
    val allowOffline: Boolean = true,
    val isEpisode: Boolean = false,
    /** W36：离线可显示的本地图片（绝对路径）；按「条目图 → 季海报 → 节目海报」回退后的结果。 */
    val imageUri: String? = null,
    /** W36：节目海报（容器行用）。 */
    val showImageUri: String? = null,
    /** W36：季海报（季容器行用）。 */
    val seasonImageUri: String? = null,
    /** W36：时长（Jellyfin ticks）；无数据为 0。 */
    val runtimeTicks: Long = 0L,
    val seriesId: UUID? = null,
    val seasonId: UUID? = null,
    val seriesName: String? = null,
    val seasonName: String? = null,
    val episodeIndex: Int = 0,
    val seasonIndex: Int = 0,
    val albumName: String? = null,
    val artist: String? = null,
    val trackIndex: Int = 0,
)

interface OfflineMediaRepository {
    /** 全部已下载条目（含被关闭离线访问的条目；调用方按 [OfflineMediaEntry.allowOffline] 过滤）。 */
    suspend fun listEntries(): List<OfflineMediaEntry>

    /** 切换「允许离线模式观看」；书籍等没有 `sources` 行的条目写本地偏好。 */
    suspend fun setAllowOffline(itemId: UUID, allow: Boolean)
}

class OfflineMediaRepositoryImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val sidecar: DownloadMediaSidecar,
    private val readerRepository: ReaderRepository,
    private val appPreferences: AppPreferences,
) : OfflineMediaRepository {

    override suspend fun listEntries(): List<OfflineMediaEntry> =
        withContext(Dispatchers.IO) {
            val sources = runCatching {
                database.getAllSources()
            }
                .onFailure { Timber.w(it, "读取下载来源失败，离线媒体库按空处理") }
                .getOrElse { emptyList() }
                .filter { it.type == FindroidSourceType.LOCAL && !it.path.endsWith(".download") }
            val sidecarRecords = sidecar.records()
            val episodeHierarchy = runCatching {
                database.getDownloadedEpisodeHierarchy()
            }
                .getOrElse { emptyList() }
                .associateBy { it.episodeId }

            val entries = mutableListOf<OfflineMediaEntry>()
            for ((itemId, itemSources) in sources.groupBy { it.itemId }) {
                val source = itemSources.first()
                val sizeBytes = runCatching { File(source.path).length() }.getOrDefault(0L)
                val allowOffline = itemSources.all { it.allowOffline }
                val record = sidecarRecords[itemId.toString()]
                val episode = episodeHierarchy[itemId]
                entries +=
                    when {
                        episode != null ->
                            OfflineMediaEntry(
                                itemId = itemId,
                                name = episode.episodeName,
                                kind = OfflineMediaEntryKind.VIDEO,
                                sizeBytes = sizeBytes,
                                sourceId = source.id,
                                allowOffline = allowOffline,
                                isEpisode = true,
                                imageUri =
                                    localImage(itemId)
                                        ?: localImage(episode.seasonId)
                                        ?: localImage(episode.seriesId),
                                showImageUri =
                                    localImage(episode.seriesId) ?: localImage(episode.seasonId),
                                seasonImageUri =
                                    localImage(episode.seasonId) ?: localImage(episode.seriesId),
                                runtimeTicks = episode.runtimeTicks,
                                seriesId = episode.seriesId,
                                seasonId = episode.seasonId,
                                seriesName = episode.seriesName,
                                seasonName = episode.seasonName,
                                episodeIndex = episode.episodeIndex,
                                seasonIndex = episode.seasonIndex,
                            )
                        record?.kind == DownloadMediaKind.MUSIC -> {
                            val title =
                                runCatching { database.getMovie(itemId)?.name }.getOrNull()
                                    ?: "曲目 ${itemId.toString().take(8)}"
                            OfflineMediaEntry(
                                itemId = itemId,
                                name = title,
                                kind = OfflineMediaEntryKind.MUSIC,
                                sizeBytes = sizeBytes,
                                sourceId = source.id,
                                allowOffline = allowOffline,
                                imageUri = localImage(itemId),
                                runtimeTicks =
                                    runCatching { database.getMovie(itemId)?.runtimeTicks }
                                        .getOrNull() ?: 0L,
                                albumName = record.albumName,
                                artist = record.artist,
                                trackIndex = record.trackIndex,
                            )
                        }
                        else -> {
                            val title =
                                runCatching { database.getMovie(itemId)?.name }.getOrNull()
                                    ?: "视频 ${itemId.toString().take(8)}"
                            OfflineMediaEntry(
                                itemId = itemId,
                                name = title,
                                kind = OfflineMediaEntryKind.VIDEO,
                                sizeBytes = sizeBytes,
                                sourceId = source.id,
                                allowOffline = allowOffline,
                                imageUri = localImage(itemId),
                                runtimeTicks =
                                    runCatching { database.getMovie(itemId)?.runtimeTicks }
                                        .getOrNull() ?: 0L,
                            )
                        }
                    }
            }

            val blockedBooks = appPreferences.getValue(appPreferences.offlineBlockedBooks)
            runCatching { readerRepository.listLocalFiles() }
                .onFailure { Timber.w(it, "读取离线书籍失败") }
                .getOrElse { emptyList() }
                .forEach { book ->
                    entries +=
                        OfflineMediaEntry(
                            itemId = book.itemId,
                            // W62：与下载页「已完成 · 书籍」统一显示名口径（侧车优先）。
                            name =
                                offlineBookDisplayName(
                                    serverName = null,
                                    sidecarTitle = book.title,
                                    itemId = book.itemId,
                                ),
                            kind = OfflineMediaEntryKind.BOOK,
                            sizeBytes = book.sizeBytes,
                            sourceId = null,
                            allowOffline = book.itemId.toString() !in blockedBooks,
                            imageUri = localImage(book.itemId),
                        )
                }

            entries
        }

    /** W36：本地图片缓存（下载时由 ImagesDownloaderWorker 落盘）；不存在返回 null（UI 回退占位）。 */
    private fun localImage(itemId: UUID?): String? {
        if (itemId == null) return null
        val file = File(context.filesDir, "images/$itemId/primary")
        return if (file.isFile && file.length() > 0L) file.absolutePath else null
    }

    override suspend fun setAllowOffline(itemId: UUID, allow: Boolean) =
        withContext(Dispatchers.IO) {
            val sources = runCatching { database.getSources(itemId) }.getOrElse { emptyList() }
            if (sources.isNotEmpty()) {
                sources.forEach { database.setSourceAllowOffline(it.id, allow) }
            } else {
                val blocked =
                    appPreferences.getValue(appPreferences.offlineBlockedBooks).toMutableSet()
                if (allow) blocked.remove(itemId.toString()) else blocked.add(itemId.toString())
                appPreferences.setValue(appPreferences.offlineBlockedBooks, blocked)
            }
        }
}
