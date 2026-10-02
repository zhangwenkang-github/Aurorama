package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * 歌词清洗（MU-5，纯函数）。
 *
 * 顺序：去空白 → 去空行 → 去元数据行（LRC 标签 + "作词 / 作曲 / 编曲"等署名行） → 去重复行 → 按时间戳升序稳定排序。 时间戳对齐（"不足毫秒补齐"）已在
 * [LrcParser] / 服务端换算阶段完成，这里只保证非负。
 *
 * W28-MUSIC（W21 遗留）：服务端「纯音乐 / 无人声」占位提示行会把行首时间戳混在文本里原样返回（实测
 * `[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏`），显示前统一清理；只对占位提示行生效，普通歌词文本不动。
 */
object LyricsNormalizer {

    /** 署名 / 制作信息行（服务端实测：aLIEz 前 3 行是作词 / 作曲 / 编曲）。 */
    private val CREDIT_PATTERN =
        Regex(
            """^\s*(作词|作詞|作曲|编曲|編曲|作词/作曲|词|詞|曲|制作人|製作人|混音|母带|母帶|录音|錄音|演唱|歌手|出品|发行|發行|""" +
                """lyrics?|music|composer|arranger|producer|artist|album|title|by)\s*[:：]""",
            RegexOption.IGNORE_CASE,
        )

    /** 「纯音乐 / 无人声」类占位提示行（实测全库 27 条：`此歌曲为没有填词的纯音乐，请您欣赏` / `纯音乐，请欣赏`）。 */
    private val PLACEHOLDER_PATTERN = Regex("""(纯音乐|無人聲|无人声|沒有填詞|没有填词|請欣賞|请欣赏)""")

    /** 文本里混入的行首时间戳，例如 `[00:00:00]` / `[00:00.000]`。 */
    private val STRAY_TIMESTAMP_PREFIX = Regex("""^\s*\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?\]\s*""")

    fun normalize(lines: List<LyricLine>): List<LyricLine> =
        lines
            .asSequence()
            .map { it.copy(text = cleanPlaceholderTimestamp(it.text.trim())) }
            .filter { it.text.isNotEmpty() }
            .filterNot { it.isMetadata || isCreditText(it.text) }
            .map { if (it.startMs != null) it.copy(startMs = it.startMs.coerceAtLeast(0L)) else it }
            .distinctBy { it.startMs to it.text }
            .sortedBy { it.startMs ?: Long.MAX_VALUE }
            .toList()

    /**
     * 清理占位提示行里混入的行首时间戳（W28-MUSIC / W21 遗留）。
     *
     * 只命中「纯音乐 / 无人声 / 请欣赏」类提示行；非占位行原样返回，避免误伤正文里合法的方括号文本。
     */
    internal fun cleanPlaceholderTimestamp(text: String): String {
        if (!PLACEHOLDER_PATTERN.containsMatchIn(text)) return text
        return text.replaceFirst(STRAY_TIMESTAMP_PREFIX, "")
    }

    /** 判断一行文本是否是署名 / 制作信息。 */
    fun isCreditText(text: String) = CREDIT_PATTERN.containsMatchIn(text)
}
