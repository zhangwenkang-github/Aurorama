package com.zhangwenkang.cinefin.music.data.lyrics

/**
 * 歌词显示偏好（W79 · #35 / #37，纯数据 + 纯函数，可 JVM 单测）。
 *
 * 三组值都落 `AppPreferences` 的 `pref_music_lyrics_*` 键（键值 = 这里的 [LyricsFontScale.key] /
 * [LyricsLineSpacing.key] / [LyricsBilingualPreference]）；歌词页与底栏歌词面板共用同一套读数， 桌面歌词悬浮窗保留自己的字号档位（W23
 * 既有，本波不合并）。
 */

/**
 * 歌词字号档位（W79 · #37）。
 *
 * [multiplier] 乘在**各表面既有的基准字号**上，[DEFAULT] = `1f` 表示不改动既有观感； 行高按同一倍数跟随（见
 * [LyricsTextSettings.lineSp]），避免"特大"档行间重叠。
 */
enum class LyricsFontScale(val key: String, val label: String, val multiplier: Float) {
    SMALL("small", "小", 0.85f),
    STANDARD("standard", "标准", 1f),
    LARGE("large", "大", 1.15f),
    EXTRA_LARGE("xlarge", "特大", 1.3f);

    fun next(): LyricsFontScale = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = STANDARD

        fun fromKey(key: String?): LyricsFontScale =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 歌词行距档位（W79 · #37）：只缩放行高与行块间距，字号不动；[DEFAULT] = 与既有观感同档。
 *
 * [multiplier] 作用在"换行行距"（同一句歌词折行时两行之间的距离，见 [LyricsTextSettings.lineSp]）； [gapDeltaDp]
 * 作用在**行块间距**（每句歌词之间、主行与译文行之间）。两个都要：单行文本框的高度会被 渲染层按文字本身裁掉 leading，只给 lineHeight
 * 的话整句歌词的疏密完全没有变化（W79a 真机实测）， 行块间距必须显式加。
 */
enum class LyricsLineSpacing(
    val key: String,
    val label: String,
    val multiplier: Float,
    val gapDeltaDp: Float,
) {
    TIGHT("tight", "紧凑", 0.85f, -4f),
    STANDARD("standard", "标准", 1f, 0f),
    LOOSE("loose", "宽松", 1.25f, 8f);

    fun next(): LyricsLineSpacing = entries[(ordinal + 1) % entries.size]

    companion object {
        val DEFAULT = STANDARD

        fun fromKey(key: String?): LyricsLineSpacing =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/** 歌词页 / 面板的显示档位组合（W79 · #37；默认 = 既有观感的"标准 / 标准"）。 */
data class LyricsTextSettings(
    val fontScale: LyricsFontScale = LyricsFontScale.DEFAULT,
    val lineSpacing: LyricsLineSpacing = LyricsLineSpacing.DEFAULT,
) {
    fun withFontScale(scale: LyricsFontScale): LyricsTextSettings = copy(fontScale = scale)

    fun withLineSpacing(spacing: LyricsLineSpacing): LyricsTextSettings =
        copy(lineSpacing = spacing)

    /** 基准字号 → 实际字号（sp）。 */
    fun fontSp(baseFontSp: Float): Float = baseFontSp * fontScale.multiplier

    /**
     * 实际行高（sp）= 实际字号 × 字体自然行高比例 × 行距倍数。
     *
     * 为什么不缩放基准样式的 `lineHeight`：既有歌词样式的 lineHeight 普遍低于字体自然行高（如 32sp 字号配 28sp 行高、 17sp 配
     * 24sp），渲染层会把过小的 lineHeight 当无效值忽略 —— 缩放它等于"行距档位完全失效" （W79a 真机实测：标准 / 宽松两档的首行像素完全一致）。改按字体自然行高
     * （[NATURAL_LINE_RATIO] ≈ 1.33 em，真机量得 1.31–1.33）计算：`标准` 档与既有观感同档， `宽松` / `紧凑` 档才真正改变行盒。
     */
    fun lineSp(baseFontSp: Float): Float =
        fontSp(baseFontSp) * NATURAL_LINE_RATIO * lineSpacing.multiplier

    /** 行块间距（dp）= 基准间距 + 档位增量，下限 0（单行文本框不吃 lineHeight，靠它出效果）。 */
    fun gapDp(baseDp: Float): Float = (baseDp + lineSpacing.gapDeltaDp).coerceAtLeast(0f)

    companion object {
        /** 字体自然行高比例（em）：字号 × 本值 ≈ 未设 lineHeight 时渲染层的行盒高度。 */
        const val NATURAL_LINE_RATIO = 1.33f

        /** 从偏好键值还原（未知 / 空值回落默认）。 */
        fun fromKeys(fontKey: String?, spacingKey: String?): LyricsTextSettings =
            LyricsTextSettings(
                fontScale = LyricsFontScale.fromKey(fontKey),
                lineSpacing = LyricsLineSpacing.fromKey(spacingKey),
            )
    }
}

/**
 * 双语对照偏好（W79 · #35，**三态**字符串）。
 *
 * [ON] / [OFF] = 用户显式选择；[UNSET]（或未知值）= 从未显式设置 → 生效值取 [DEFAULT_ENABLED]。 用户手动关过就一直落 `off`，不会被默认值覆盖。
 */
object LyricsBilingualPreference {
    const val ON = "on"
    const val OFF = "off"
    const val UNSET = ""

    /** 未显式设置（新装 / 从未动过开关）时的生效值：默认开。 */
    const val DEFAULT_ENABLED = true

    /** 已落盘的显式选择；未设置 / 未知值 → null。 */
    fun stored(raw: String?): Boolean? =
        when (raw) {
            ON -> true
            OFF -> false
            else -> null
        }

    /** 实际生效值：显式偏好优先，否则默认开。 */
    fun resolve(raw: String?): Boolean = stored(raw) ?: DEFAULT_ENABLED

    /** 用户操作后的落盘值（显式写 on / off，不会再回到未设置态）。 */
    fun encode(enabled: Boolean): String = if (enabled) ON else OFF
}
