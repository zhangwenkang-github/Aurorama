package com.zhangwenkang.cinefin.music.data.lyrics

import com.zhangwenkang.cinefin.api.JellyfinApi
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.extensions.lyricsApi
import org.jellyfin.sdk.model.api.LyricDto

/**
 * 歌词仓库（MU-5）。
 *
 * 来源优先级（W25 起：**本机覆盖 > 外挂 LRC > 服务端 > 本地缓存**）：
 * 1. 本机覆盖——用户在 App 内编辑 / 导入的歌词（`<filesDir>/lyrics/override/<itemId>.lrc`，W25-MUSIC）；
 * 2. 外挂 `.lrc`——本地音频文件同目录同名文件（离线播放 / 下载后），或 `<filesDir>/lyrics/<itemId>.lrc`（手工投放）；
 * 3. 服务端 `GET /Audio/{itemId}/Lyrics`（Jellyfin SDK `LyricsApi.getLyrics`；实测服务器会把文件内嵌歌词提取为 metadata
 *    `.lrc` 后由此接口返回）；
 * 4. 本地缓存（上一次成功拉取的原文行）——断网时仍然可用。
 *
 * 客户端内嵌标签解析未做：W25 只读探测确认 Jellyfin 10.11.8 已覆盖（见 `MUSIC_PLAN` §4.2 探测结论）。
 */
interface LyricsRepository {
    /**
     * 读取并解析歌词；没有任何来源时返回 null。
     *
     * @param localMediaPath 本地音频文件路径（离线播放时传入），用于找同目录外挂 LRC；在线播放传 null。
     */
    suspend fun getLyrics(itemId: UUID, localMediaPath: String? = null): LyricsDocument?

    /** 保存本机覆盖（编辑保存）。返回是否落盘成功。 */
    suspend fun saveLocalOverride(itemId: UUID, lines: List<LyricLine>): Boolean

    /** 原样保存 LRC 文本为本机覆盖（导入 `.lrc`）。返回是否落盘成功。 */
    suspend fun saveLocalOverrideText(itemId: UUID, text: String): Boolean

    /** 删除本机覆盖（清除覆盖）；返回是否删除成功。 */
    suspend fun clearLocalOverride(itemId: UUID): Boolean

    /** 当前曲目是否存在本机覆盖。 */
    suspend fun hasLocalOverride(itemId: UUID): Boolean
}

/**
 * 服务端歌词来源（`GET /Audio/{itemId}/Lyrics`）。
 *
 * 单独抽一层是为了让"断网回落缓存"可 JVM 单测（见 `LyricsRepositoryTest`）：网络实现走 Jellyfin SDK， 测试用抛异常的假实现。
 */
interface LyricsRemoteSource {
    /** 取服务端歌词行；没有歌词 / 请求失败由实现方决定返回 null 或抛异常，仓库层统一容错。 */
    suspend fun fetch(itemId: UUID): List<LyricLine>?
}

@Singleton
class JellyfinLyricsRemoteSource @Inject constructor(private val jellyfinApi: JellyfinApi) :
    LyricsRemoteSource {

    /** 服务端 DTO → 行模型；`Start` 是 ticks（1 ms = 10000 ticks，实测 28440000 → 2.844 s）。 */
    override suspend fun fetch(itemId: UUID): List<LyricLine>? =
        jellyfinApi.api.lyricsApi.getLyrics(itemId).content.toLyricLines()

    private fun LyricDto.toLyricLines(): List<LyricLine>? = lyrics?.map { line ->
        LyricLine(
            startMs = line.start?.let { ticks -> ticks / TICKS_PER_MS },
            text = line.text.orEmpty(),
        )
    }

    private companion object {
        const val TICKS_PER_MS = 10_000L
    }
}

@Singleton
class LyricsRepositoryImpl
@Inject
constructor(
    private val remote: LyricsRemoteSource,
    private val cache: LyricsCache,
    private val overrideStore: LyricsOverrideStore,
) : LyricsRepository {

    /** 外挂 LRC 与缓存共用同一目录（`<filesDir>/lyrics`）。 */
    private val lyricsDir: File = cache.directory

    override suspend fun getLyrics(itemId: UUID, localMediaPath: String?): LyricsDocument? =
        withContext(Dispatchers.IO) {
            overrideStore.load(itemId)?.let { lines ->
                return@withContext LyricsDocumentBuilder.build(lines, LyricsSource.LOCAL_OVERRIDE)
                    .takeIf { !it.isEmpty }
            }

            externalLrc(itemId, localMediaPath)?.let { lines ->
                return@withContext LyricsDocumentBuilder.build(lines, LyricsSource.EXTERNAL_LRC)
                    .takeIf { !it.isEmpty }
            }

            val serverLines = runCatching { remote.fetch(itemId) }.getOrNull()
            if (!serverLines.isNullOrEmpty()) {
                cache.save(itemId, serverLines)
                return@withContext LyricsDocumentBuilder.build(serverLines, LyricsSource.SERVER)
                    .takeIf { !it.isEmpty }
            }

            cache.load(itemId)?.let { lines ->
                return@withContext LyricsDocumentBuilder.build(lines, LyricsSource.CACHE).takeIf {
                    !it.isEmpty
                }
            }
            null
        }

    override suspend fun saveLocalOverride(itemId: UUID, lines: List<LyricLine>): Boolean =
        withContext(Dispatchers.IO) { overrideStore.save(itemId, lines) }

    override suspend fun saveLocalOverrideText(itemId: UUID, text: String): Boolean =
        withContext(Dispatchers.IO) { overrideStore.saveText(itemId, text) }

    override suspend fun clearLocalOverride(itemId: UUID): Boolean =
        withContext(Dispatchers.IO) { overrideStore.clear(itemId) }

    override suspend fun hasLocalOverride(itemId: UUID): Boolean =
        withContext(Dispatchers.IO) { overrideStore.has(itemId) }

    /** 外挂 LRC：本地媒体同目录 `<文件名>.lrc`，或应用歌词目录 `<itemId>.lrc`。 */
    private fun externalLrc(itemId: UUID, localMediaPath: String?): List<LyricLine>? {
        val media = localMediaPath?.let(::File)
        val candidates = buildList {
            if (media != null) {
                val name = media.name.substringBeforeLast('.', media.name)
                add(File(media.parentFile, "$name.lrc"))
                add(File("${media.path}.lrc"))
            }
            add(File(lyricsDir, "$itemId.lrc"))
        }
        for (file in candidates) {
            if (!file.isFile) continue
            val text = readText(file) ?: continue
            val lines = LrcParser.parse(text)
            if (lines.isNotEmpty()) return lines
        }
        return null
    }

    /** 外挂 LRC 常见 GBK 编码：先按 UTF-8 读，出现替换字符再按 GBK 重读（与导入共用 [LyricTextCodec]）。 */
    private fun readText(file: File): String? {
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        return LyricTextCodec.decode(bytes)
    }
}
