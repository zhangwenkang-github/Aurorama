package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * 一行歌词（MU-5）。
 *
 * [startMs] 统一为毫秒；`null` 表示该行没有时间戳（未同步歌词）。 [isMetadata] 标记 LRC ID 标签（`[ti:]` / `[ar:]` /
 * `[offset:]` …）或"作词 / 作曲"一类的元数据行，显示前会被 [LyricsNormalizer] 丢弃。
 */
data class LyricLine(
    val startMs: Long?,
    val text: String,
    val isMetadata: Boolean = false,
)

/**
 * 配对后的歌词块。
 *
 * 原文与译文共享同一时间戳（或时间戳差 ≤ [LyricsPairer.DEFAULT_TOLERANCE_MS]）， [primary] 恒为原文， [secondary] 为译文；单语歌词只有
 * [primary]。
 */
data class LyricBlock(
    val startMs: Long?,
    val primary: LyricLine,
    val secondary: LyricLine? = null,
) {
    /** 块内所有行（1 = 单语，2 = 双语）。 */
    val lines: List<LyricLine>
        get() = listOfNotNull(primary, secondary)
}

/** 逐行语言识别结果（MU-5 / REQUIREMENTS §10）。 */
enum class LyricLanguage(val label: String) {
    SIMPLIFIED_CHINESE("简体中文"),
    TRADITIONAL_CHINESE("繁體中文"),
    JAPANESE("日文"),
    ENGLISH("英文"),
    /** 汉字 + 拉丁字母混排（"中文行夹英文"），保留原文展示。 */
    MIXED("混合行"),
    /** 无汉字 / 假名 / 拉丁字母（纯符号等）。 */
    OTHER("其他"),
}

/** 歌词来源；用于界面角标与真机验证（断网时应当回落 [CACHE]）。 */
enum class LyricsSource(val label: String) {
    EXTERNAL_LRC("外挂 LRC"),
    SERVER("服务端"),
    CACHE("本地缓存"),
}

/**
 * 一份可直接渲染的歌词文档（纯数据，无 Android 依赖）。
 *
 * [availableLanguages] 是逐行识别结果的聚合（按 [LyricsPresenter.displayLanguages] 的优先级排序），
 * 界面据此生成语言切换列表；语言切换只影响显示，不改变 [blocks] 的时间轴。
 */
data class LyricsDocument(
    val blocks: List<LyricBlock>,
    val availableLanguages: List<LyricLanguage>,
    val synced: Boolean,
    val source: LyricsSource,
) {
    val isEmpty: Boolean
        get() = blocks.isEmpty()
}
