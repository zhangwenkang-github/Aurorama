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
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * ReplayGain 标签读取（W30-MUSIC-FX）。
 *
 * 数据源优先级：
 * 1. 本机覆盖文件 `<filesDir>/replaygain/<itemId>.txt`（`track=-6.5` / `album=-8.0`）——给没有标签的文件手动指定增益，
 *    也是真机验收"三态有值可测"的合法数据源；
 * 2. 音频文件内嵌标签——对 `/Audio/{id}/stream?static=true` 发 Range 请求只读拉前 [READ_BYTES] 字节后解析 （FLAC
 *    VorbisComment / ID3v2 TXXX；服务器只读，不写任何数据）。
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

    private class CacheEntry(val value: TrackReplayGain?)

    /** 读取一首曲目的 ReplayGain；失败与无标签都返回 null（静默降级，不影响播放）。 */
    suspend fun read(item: PlayerItem): TrackReplayGain? =
        withContext(Dispatchers.IO) {
            synchronized(cacheLock) { cache[item.itemId] }
                ?.let {
                    return@withContext it.value
                }
            val result = readOverride(item.itemId) ?: readEmbedded(item)
            synchronized(cacheLock) { cache[item.itemId] = CacheEntry(result) }
            Timber.d(
                "ReplayGain 读取：item=%s 来源=%s track=%s album=%s",
                item.itemId,
                result?.source?.name ?: "NONE",
                result?.trackGainDb,
                result?.albumGainDb,
            )
            result
        }

    private fun readOverride(itemId: UUID): TrackReplayGain? {
        val file = File(overrideDir, "$itemId.txt")
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { parseReplayGainOverride(file.readText()) }
            .onFailure { Timber.w(it, "ReplayGain 覆盖文件解析失败") }
            .getOrNull()
    }

    private fun readEmbedded(item: PlayerItem): TrackReplayGain? {
        val baseUrl = jellyfinApi.api.baseUrl?.trimEnd('/') ?: return null
        val token = jellyfinApi.api.accessToken ?: return null
        val mediaSourceId = URLEncoder.encode(item.mediaSourceId, Charsets.UTF_8.name())
        val url =
            URL("$baseUrl/Audio/${item.itemId}/stream?static=true&mediaSourceId=$mediaSourceId")
        val connection = url.openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("X-Emby-Token", token)
            connection.setRequestProperty("Range", "bytes=0-${READ_BYTES - 1}")
            connection.connectTimeout = READ_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            val code = connection.responseCode
            if (code !in 200..299) {
                Timber.d("ReplayGain 标签拉取失败：HTTP %d", code)
                return null
            }
            val bytes = connection.inputStream.use { stream -> readUpTo(stream, READ_BYTES) }
            parseReplayGainBytes(bytes)
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

        const val READ_TIMEOUT_MS = 8_000
    }
}
