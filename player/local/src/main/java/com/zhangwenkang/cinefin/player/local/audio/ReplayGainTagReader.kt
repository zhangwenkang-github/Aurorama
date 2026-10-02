package com.zhangwenkang.cinefin.player.local.audio

import android.content.Context
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ReplayGain 标签读取（W30-MUSIC-FX，W35 扩展 M4A/MP4 与本机覆盖写入）。
 *
 * 数据源优先级：
 * 1. 本机覆盖文件 `<filesDir>/replaygain/<itemId>.txt`（`track=-6.5` / `album=-8.0`）——给没有标签的文件手动指定增益，
 *    也是真机验收"三态有值可测"的合法数据源；W35 起音效面板可直接写入 / 清除；
 * 2. 音频文件内嵌标签——对 `/Audio/{id}/stream?static=true` 发 Range 请求只读拉前 [READ_BYTES] 字节后解析 （FLAC
 *    VorbisComment / ID3v2 TXXX / M4A·MP4 的 iTunes free-form `----` 原子；服务器只读，不写任何数据）。 M4A/MP4 且
 *    moov 不在头部窗口（非 faststart）时，按顶层 box 链算出的偏移再拉一段（最多一次额外请求）。
 *
 * 结果按 itemId 缓存（含"无标签"的负缓存），切歌不会重复拉流。
 */
@Singleton
class ReplayGainTagReader
@Inject
constructor(
    @ApplicationContext context: Context,
    private val jellyfinApi: JellyfinApi,
) {

    private val overrideDir = File(context.filesDir, "replaygain")
    private val cache = HashMap<UUID, CacheEntry>()
    private val cacheLock = Any()
    private val overrideMutex = Mutex()

    private class CacheEntry(val value: TrackReplayGain?)

    /**
     * 读取一首曲目的 ReplayGain；无标签返回 [ReplayGainReadResult.Value]（null），失败返回
     * [ReplayGainReadResult.Failed]。
     */
    suspend fun read(item: PlayerItem): ReplayGainReadResult =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) { cache[item.itemId] }
                ?.let {
                    return@withContext ReplayGainReadResult.Value(it.value)
                }
            val override = readOverride(item.itemId)
            val result =
                if (override != null) ReplayGainReadResult.Value(override) else readEmbedded(item)
            if (result is ReplayGainReadResult.Value) {
                synchronized(cacheLock) { cache[item.itemId] = CacheEntry(result.tags) }
                Timber.d(
                    "ReplayGain 读取：item=%s 来源=%s track=%s album=%s",
                    item.itemId,
                    result.tags?.source?.name ?: "NONE",
                    result.tags?.trackGainDb,
                    result.tags?.albumGainDb,
                )
            } else {
                // 失败不缓存：切档位 / 切回曲目 / 调用方重试时会重新拉取
                Timber.d("ReplayGain 读取失败（不缓存，可重试）：item=%s", item.itemId)
            }
            result
        }

    /** 只读本机覆盖文件（不触发内嵌标签拉取）；供音效面板显示当前曲目的覆盖值。 */
    suspend fun readOverrideOnly(itemId: UUID): TrackReplayGain? =
        withContext(Dispatchers.IO) { readOverride(itemId) }

    /**
     * 写入 / 更新本机覆盖（track 与 album 都为 null 时删除文件），返回写入后的覆盖值。
     *
     * 写完同步失效 itemId 的整体缓存：下次 [read]（切档位 / 切回曲目）会重新走 "覆盖 > 内嵌" 优先级， 而不是命中旧结果。
     */
    suspend fun writeOverride(
        itemId: UUID,
        trackGainDb: Float?,
        albumGainDb: Float?,
    ): TrackReplayGain? =
        withContext(Dispatchers.IO) {
            overrideMutex.withLock {
                val text = encodeReplayGainOverride(trackGainDb, albumGainDb)
                val file = File(overrideDir, "$itemId.txt")
                runCatching {
                    if (text == null) {
                        if (file.exists()) file.delete()
                    } else {
                        overrideDir.mkdirs()
                        file.writeText(text)
                    }
                }
                    .onFailure { Timber.w(it, "ReplayGain 覆盖写入失败") }
                synchronized(cacheLock) { cache.remove(itemId) }
                text?.let(::parseReplayGainOverride)
            }
        }

    private fun readOverride(itemId: UUID): TrackReplayGain? {
        val file = File(overrideDir, "$itemId.txt")
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { parseReplayGainOverride(file.readText()) }
            .onFailure { Timber.w(it, "ReplayGain 覆盖文件解析失败") }
            .getOrNull()
    }

    private fun readEmbedded(item: PlayerItem): ReplayGainReadResult {
        val baseUrl = jellyfinApi.api.baseUrl?.trimEnd('/') ?: return ReplayGainReadResult.Failed
        val token = jellyfinApi.api.accessToken ?: return ReplayGainReadResult.Failed
        val mediaSourceId = URLEncoder.encode(item.mediaSourceId, Charsets.UTF_8.name())
        val url = "$baseUrl/Audio/${item.itemId}/stream?static=true&mediaSourceId=$mediaSourceId"
        val prefix =
            fetchRange(url, token, "bytes=0-${READ_BYTES - 1}")
                ?: return ReplayGainReadResult.Failed
        parseReplayGainBytes(prefix)?.let {
            return ReplayGainReadResult.Value(it)
        }
        if (!isMp4Header(prefix)) return ReplayGainReadResult.Value(null)
        return readMp4Embedded(url, token, prefix)
    }

    /**
     * M4A/MP4 的二次读取：头部窗口没有完整 `moov`（非 faststart，moov 在文件尾）时，按 box 链算出的 偏移再拉一小段只读 Range，解析
     * `moov/udta/meta/ilst/----`。
     */
    private fun readMp4Embedded(
        url: String,
        token: String,
        prefix: ByteArray,
    ): ReplayGainReadResult {
        val scan = scanMp4TopLevelBoxes(prefix)
        if (scan.moovStart >= 0 && scan.moovCompleteInWindow) {
            // moov 完整落在头部窗口：通用解析不认 MP4，这里补一次。
            return ReplayGainReadResult.Value(parseMp4ReplayGain(prefix).toTrackReplayGain())
        }
        val offset =
            scan.nextBoxOffset
                ?: scan.moovStart.takeIf { it >= 0 }?.toLong()
                ?: return ReplayGainReadResult.Value(null)
        val window =
            fetchRange(url, token, "bytes=$offset-${offset + MP4_MOOV_WINDOW_BYTES - 1}")
                ?: return ReplayGainReadResult.Failed
        val result = parseMp4ReplayGain(window)
        if (!result.moovFound) {
            Timber.d("ReplayGain MP4：二次窗口未找到 moov（offset=%d）", offset)
        }
        return ReplayGainReadResult.Value(result.toTrackReplayGain())
    }

    /** 只读 Range 拉取（服务器只读调用，不写任何数据）；失败静默返回 null。 */
    private fun fetchRange(url: String, token: String, range: String): ByteArray? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-Emby-Token", token)
            connection.setRequestProperty("Range", range)
            connection.connectTimeout = READ_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val code = connection.responseCode
            if (code !in 200..299) {
                Timber.d("ReplayGain 标签拉取失败：HTTP %d", code)
                return null
            }
            connection.inputStream.use { stream -> readUpTo(stream, READ_BYTES) }
        } catch (e: Exception) {
            Timber.d(e, "ReplayGain 标签拉取失败（静默降级）")
            null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun readUpTo(stream: InputStream, limit: Int): ByteArray {
        val buffer = ByteArray(limit)
        var filled = 0
        while (filled < limit) {
            val read = stream.read(buffer, filled, limit - filled)
            if (read <= 0) break
            filled += read
        }
        return if (filled == limit) buffer else buffer.copyOf(filled)
    }

    private companion object {
        /**
         * 头部读取范围：FLAC 的 VORBIS_COMMENT 一般紧跟 STREAMINFO（实测全库 100 首 64KB 内命中）， 128KB 覆盖 PADDING /
         * 封面在前的大多数情况；ID3v2 同理。
         */
        const val READ_BYTES = 128 * 1024

        /** M4A/MP4 二次窗口：moov 里可能含 trak / 封面，给足余量。 */
        const val MP4_MOOV_WINDOW_BYTES = READ_BYTES

        const val READ_TIMEOUT_MS = 8_000
    }
}
