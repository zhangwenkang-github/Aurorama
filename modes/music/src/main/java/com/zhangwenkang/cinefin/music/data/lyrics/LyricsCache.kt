package com.zhangwenkang.cinefin.music.data.lyrics

import java.io.File
import java.util.UUID

/**
 * 歌词文件缓存（MU-5 / MU-6，断网可用）。
 *
 * 落点 `<filesDir>/lyrics/<itemId>.json`——**故意不用 Room**：W3 波次 R1-OFFLINE 同时在改 Room schema / 版本号，
 * 歌词缓存用文件即可满足"断网时已缓存歌词可用"，避免两条线抢同一个数据库版本（`PARALLEL_PLAN` §1.3）。
 *
 * 文本格式（逐行、可读、无第三方依赖）：
 *
 * ```
 * # cinefin-lyrics 1
 * <startMs>\t<text>
 * \t<text>              # 未同步行（startMs 为空）
 * ```
 *
 * 转义：`\` → `\\`、Tab → `\t`、换行 → `\n`。
 */
class LyricsCache(val directory: File) {

    fun save(itemId: UUID, lines: List<LyricLine>) {
        if (lines.isEmpty()) return
        runCatching {
            directory.mkdirs()
            file(itemId).writeText(encode(lines), Charsets.UTF_8)
        }
    }

    fun load(itemId: UUID): List<LyricLine>? {
        val file = file(itemId)
        if (!file.isFile) return null
        return runCatching { decode(file.readText(Charsets.UTF_8)) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    fun clear(itemId: UUID) {
        runCatching { file(itemId).delete() }
    }

    private fun file(itemId: UUID) = File(directory, "$itemId.json")

    companion object {
        private const val HEADER = "# cinefin-lyrics 1"

        internal fun encode(lines: List<LyricLine>): String =
            (listOf(HEADER) + lines.map { line -> "${line.startMs ?: ""}\t${escape(line.text)}" })
                .joinToString("\n", postfix = "\n")

        internal fun decode(text: String): List<LyricLine> =
            text
                .lineSequence()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { row ->
                    val separator = row.indexOf('\t')
                    if (separator < 0) {
                        LyricLine(startMs = null, text = unescape(row))
                    } else {
                        LyricLine(
                            startMs =
                                row.substring(0, separator).takeIf { it.isNotBlank() }?.toLong(),
                            text = unescape(row.substring(separator + 1)),
                        )
                    }
                }
                .toList()

        private fun escape(text: String): String =
            text.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unescape(text: String): String {
            val builder = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                val char = text[index]
                if (char == '\\' && index + 1 < text.length) {
                    when (text[index + 1]) {
                        '\\' -> builder.append('\\')
                        't' -> builder.append('\t')
                        'n' -> builder.append('\n')
                        else -> builder.append(text[index + 1])
                    }
                    index += 2
                } else {
                    builder.append(char)
                    index++
                }
            }
            return builder.toString()
        }
    }
}
