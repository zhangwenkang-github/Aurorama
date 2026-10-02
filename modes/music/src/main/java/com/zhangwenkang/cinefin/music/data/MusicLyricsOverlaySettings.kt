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

/** 悬浮窗位置（像素，左上角相对屏幕）。 */
data class LyricsOverlayPosition(val x: Int, val y: Int)

/**
 * 「保持显示时长」档位（W25-MUSIC）：工具条里循环切换，控制"无操作后自动隐藏背景 / 边框"的时间。
 *
 * [durationMs] = null 表示**常显**（不自动隐藏）；[key] 落偏好键 `pref_music_lyrics_overlay_idle`。
 */
enum class LyricsOverlayIdle(val key: String, val label: String, val durationMs: Long?) {
    SECONDS_2("2s", "2 秒", 2_000L),
    SECONDS_3("3s", "3 秒", 3_000L),
    SECONDS_5("5s", "5 秒", 5_000L),
    SECONDS_10("10s", "10 秒", 10_000L),
    ALWAYS("always", "常显", null);

    fun next(): LyricsOverlayIdle = entries[(ordinal + 1) % entries.size]

    companion object {
        /** 默认保持 W24 行为（3 秒）。 */
        val DEFAULT = SECONDS_3

        fun fromKey(key: String?): LyricsOverlayIdle =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

/**
 * 背景 / 边框是否显示（纯函数）。
 *
 * [interacting] = 距最近一次触摸 / 拖动不足当前档位的保持时长（或档位为[LyricsOverlayIdle.ALWAYS]常显）；
 * 锁定后无论是否交互都保持隐藏（只留歌词文字），此时单击只负责唤出设置工具条。
 */
fun overlayChromeVisible(locked: Boolean, interacting: Boolean): Boolean = !locked && interacting

/**
 * 悬浮窗两行最终显示内容（纯函数）。
 *
 * 有歌词 = 当前句 + 下一句；**无歌词时回落歌名 / 歌手**，避免出现两行空白。
 */
data class LyricsOverlayDisplay(val first: String, val second: String?)

fun overlayDisplayLines(
    lines: LyricsOverlayLines,
    title: String?,
    artist: String?,
): LyricsOverlayDisplay {
    if (lines.hasContent) return LyricsOverlayDisplay(lines.current.orEmpty(), lines.next)
    val song = title?.takeIf { it.isNotBlank() } ?: return LyricsOverlayDisplay("暂无歌词", null)
    return LyricsOverlayDisplay(song, artist?.takeIf { it.isNotBlank() } ?: "暂无歌词")
}

/** 校验偏好里的位置（-1 = 未记录）；越界 / 未记录返回 null。 */
fun storedOverlayPosition(x: Int, y: Int): LyricsOverlayPosition? =
    if (x >= 0 && y >= 0) LyricsOverlayPosition(x, y) else null

/** 把悬浮窗位置夹进屏幕（拖动 / 恢复 / 旋转后都走它，纯函数）。 */
fun clampOverlayPosition(
    position: LyricsOverlayPosition,
    windowWidth: Int,
    windowHeight: Int,
    screenWidth: Int,
    screenHeight: Int,
): LyricsOverlayPosition {
    val maxX = (screenWidth - windowWidth).coerceAtLeast(0)
    val maxY = (screenHeight - windowHeight).coerceAtLeast(0)
    return LyricsOverlayPosition(
        position.x.coerceIn(0, maxX),
        position.y.coerceIn(0, maxY),
    )
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
