package com.zhangwenkang.cinefin.music.data

import com.zhangwenkang.cinefin.music.data.lyrics.LyricsRow

/**
 * 桌面歌词悬浮窗的文字颜色（W23-MUSIC · D 组，纯数据）。
 *
 * 颜色取自 Prism 设计系统的中性色 / 媒体色，[argb] 是 0xAARRGGBB；悬浮窗按"当前句实色、 下一句 60% 同色"渲染，保证两行层次分明。
 */
enum class LyricsOverlayTint(val key: String, val label: String, val argb: Long) {
    MOON_WHITE("moon_white", "月白", 0xFFF2F5F9L),
    TURQUOISE("turquoise", "松石", 0xFF5CE1D2L),
    AMBER("amber", "琥珀", 0xFFE8A15CL),
    AZURE("azure", "天青", 0xFF7CC4FFL),
    ROSE("rose", "胭脂", 0xFFFF8EA0L);

    fun next(): LyricsOverlayTint = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): LyricsOverlayTint =
            entries.firstOrNull { it.key == key } ?: MOON_WHITE
    }
}

/** 桌面歌词字号档位（当前句 / 下一句 sp，纯数据）。 */
enum class LyricsOverlaySize(
    val key: String,
    val label: String,
    val currentSp: Float,
    val nextSp: Float,
) {
    SMALL("small", "小", 14f, 12f),
    MEDIUM("medium", "中", 18f, 14f),
    LARGE("large", "大", 22f, 16f),
    EXTRA_LARGE("xlarge", "特大", 26f, 18f);

    fun next(): LyricsOverlaySize = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromKey(key: String?): LyricsOverlaySize =
            entries.firstOrNull { it.key == key } ?: MEDIUM
    }
}

/** 悬浮窗两行内容：当前句 + 下一句。 */
data class LyricsOverlayLines(val current: String?, val next: String?) {
    val hasContent: Boolean
        get() = !current.isNullOrBlank()
}

/**
 * 取"当前句 + 下一句"（纯函数，可 JVM 单测）。
 *
 * [activeIndex] 取 [com.zhangwenkang.cinefin.music.data.lyrics.LyricsPresenter.activeIndex] 的结果； 越界
 * / 空歌词安全降级（空歌词返回两行 null，由 UI 显示"暂无歌词"）。
 */
fun overlayLyricsLines(rows: List<LyricsRow>, activeIndex: Int): LyricsOverlayLines {
    if (rows.isEmpty()) return LyricsOverlayLines(current = null, next = null)
    val index = activeIndex.coerceIn(0, rows.lastIndex)
    return LyricsOverlayLines(
        current = rows[index].mainText,
        next = rows.getOrNull(index + 1)?.mainText,
    )
}
