package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * 歌词清洗（MU-5，纯函数）。
 *
 * 顺序：去空白 → 去空行 → 去元数据行（LRC 标签 + "作词 / 作曲 / 编曲"等署名行） → 去重复行 → 按时间戳升序稳定排序。 时间戳对齐（"不足毫秒补齐"）已在
 * [LrcParser] / 服务端换算阶段完成，这里只保证非负。
 */
object LyricsNormalizer {

    /** 署名 / 制作信息行（服务端实测：aLIEz 前 3 行是作词 / 作曲 / 编曲）。 */
    private val CREDIT_PATTERN =
        Regex(
            """^\s*(作词|作詞|作曲|编曲|編曲|作词/作曲|词|詞|曲|制作人|製作人|混音|母带|母帶|录音|錄音|演唱|歌手|出品|发行|發行|""" +
                """lyrics?|music|composer|arranger|producer|artist|album|title|by)\s*[:：]""",
            RegexOption.IGNORE_CASE,
        )

    fun normalize(lines: List<LyricLine>): List<LyricLine> =
        lines
            .asSequence()
            .map { it.copy(text = it.text.trim()) }
            .filter { it.text.isNotEmpty() }
            .filterNot { it.isMetadata || isCreditText(it.text) }
            .map { if (it.startMs != null) it.copy(startMs = it.startMs.coerceAtLeast(0L)) else it }
            .distinctBy { it.startMs to it.text }
            .sortedBy { it.startMs ?: Long.MAX_VALUE }
            .toList()

    /** 判断一行文本是否是署名 / 制作信息。 */
    fun isCreditText(text: String) = CREDIT_PATTERN.containsMatchIn(text)
}
