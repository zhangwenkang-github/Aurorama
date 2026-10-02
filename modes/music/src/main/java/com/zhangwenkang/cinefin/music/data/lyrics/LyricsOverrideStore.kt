package com.zhangwenkang.cinefin.music.data.lyrics

import java.io.File
import java.nio.charset.Charset
import java.util.UUID

/**
 * 本机歌词覆盖存储（W25-MUSIC）。
 *
 * 落点 `<filesDir>/lyrics/override/<itemId>.lrc`——用户编辑 / 导入的歌词作为"本机覆盖"参与来源链且优先级最高（外挂 LRC → 服务端 →
 * 缓存之上），**不写服务器、不用 Room**（沿用 D15「歌词缓存用文件不用 Room」）。
 *
 * 存储格式就是可读的 LRC 文本：编辑保存时按 `[mm:ss.SSS]文本` 写出（毫秒保真），导入时原样保存（保留 `[ti:]` 等标签，读取时再由 [LrcParser] 解析）。
 */
class LyricsOverrideStore(val directory: File) {

    /** 覆盖行 → LRC 文件；空内容返回 false（不落盘，避免产生"空覆盖"）。 */
    fun save(itemId: UUID, lines: List<LyricLine>): Boolean {
        val text = encodeLrc(lines)
        if (text.isBlank()) return false
        return saveText(itemId, text)
    }

    /** 原样保存 LRC 文本（导入用）。 */
    fun saveText(itemId: UUID, text: String): Boolean {
        if (text.isBlank()) return false
        return runCatching {
            directory.mkdirs()
            file(itemId).writeText(text, Charsets.UTF_8)
        }
            .isSuccess
    }

    /** 读取覆盖原文（无覆盖 / 读取失败返回 null）。 */
    fun loadText(itemId: UUID): String? {
        val file = file(itemId)
        if (!file.isFile) return null
        return runCatching { LyricTextCodec.decode(file.readBytes()) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    /** 读取并解析覆盖行；空 / 解析不出内容按"无覆盖"处理。 */
    fun load(itemId: UUID): List<LyricLine>? =
        loadText(itemId)?.let(LrcParser::parse)?.takeIf { it.isNotEmpty() }

    fun has(itemId: UUID): Boolean = file(itemId).isFile

    /** 删除覆盖；返回是否真的删掉了文件。 */
    fun clear(itemId: UUID): Boolean = runCatching { file(itemId).delete() }.getOrDefault(false)

    private fun file(itemId: UUID) = File(directory, "$itemId.lrc")

    companion object {
        /** 覆盖目录名（相对 `<filesDir>/lyrics`）。 */
        const val DIRECTORY_NAME = "override"

        /** 覆盖行 → LRC 文本（时间戳毫秒保真；留空时间戳按未同步行写出）。 */
        internal fun encodeLrc(lines: List<LyricLine>): String =
            lines
                .asSequence()
                .filter { it.text.isNotBlank() }
                .map { line ->
                    line.startMs?.let { startMs -> "[${formatLyricTime(startMs)}]${line.text}" }
                        ?: line.text
                }
                .joinToString("\n", postfix = "\n")
    }
}

/**
 * 歌词文本字节解码（W25-MUSIC，导入 / 外挂 LRC 共用）。
 *
 * 外挂 LRC 常见 GBK 编码：先按 UTF-8 读，出现替换字符再按 GBK 重读（与原 `LyricsRepository.readText` 同口径）。
 */
object LyricTextCodec {
    fun decode(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains(REPLACEMENT_CHAR)) return utf8
        val gbk = runCatching { String(bytes, Charset.forName("GBK")) }.getOrNull() ?: return utf8
        return gbk.takeIf { !it.contains(REPLACEMENT_CHAR) } ?: utf8
    }

    private const val REPLACEMENT_CHAR = '\uFFFD'
}
