package com.zhangwenkang.cinefin.player.local.subtitle

import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import java.util.Locale
import kotlin.math.roundToInt

/**
 * ASS 脚本工具（W16 · §1.18）。
 *
 * 两个职责：
 * 1. **SRT / WebVTT 覆盖**：把已解析的 cue 生成一份 ASS 脚本，交给 libass 渲染。这样 「libass 路径」对文本字幕是全覆盖的（SRT 也能享受
 *    libass 的排版 / 换行 / 描边 / 定位）， 同时外观档位（大小 / 颜色 / 背景 / 描边 / 位置）按 [SubtitleStyle] 写进生成的样式里，做到不回归。
 * 2. **ASS / SSA 透传**：原始脚本原样进 libass（定位 / 字体 / 特效交给脚本）。
 *
 * 纯函数（无 Android 依赖，只有 dp→px 需要的 density 作为参数），单测钉死： 时间格式、颜色打包（`&HAABBGGRR`，ASS 的 alpha 与 Android
 * 相反）、字号 / 边距换算、转义。
 */
object AssSubtitleScript {

    /** 生成脚本用的默认字族：Android 系统无衬线（中英文都能落到系统字体） */
    const val DEFAULT_FONT = "sans-serif"

    /** 基础字号 = 画面高度的 4.2%（与 Compose 覆盖层的基准一致，见 PlayerSubtitleOverlay） */
    const val BASE_FONT_FRACTION = 0.042f

    /**
     * cue 列表 → ASS 脚本。
     *
     * @param playResX/playResY 脚本坐标系（一般用视频像素尺寸；libass 会缩放到渲染区）
     * @param frameHeightPx 渲染区高度（像素）：描边宽度按它把 dp 换算成脚本坐标
     * @param density 屏幕密度（dp → px）
     */
    fun forCues(
        cues: List<SubtitleCue>,
        style: SubtitleStyle,
        playResX: Int,
        playResY: Int,
        frameHeightPx: Int,
        density: Float,
        fontName: String = DEFAULT_FONT,
    ): String {
        val width = playResX.coerceAtLeast(1)
        val height = playResY.coerceAtLeast(1)
        val frameHeight = frameHeightPx.coerceAtLeast(1)

        // 字号：屏幕像素 = 0.042 × 画面高 × 倍率；换算到脚本坐标即与画面高抵消（与渲染区尺寸无关）
        val fontSize = (BASE_FONT_FRACTION * height * style.textScale).roundToInt().coerceAtLeast(8)
        val outlineWidthPx = style.edgeWidthDp * density.coerceAtLeast(0.5f)
        val outlineScript = (outlineWidthPx * height / frameHeight).roundToInt().coerceAtLeast(0)
        val marginV = (style.bottomFraction * height).roundToInt().coerceIn(0, height / 2)
        val hasBackground = (style.backgroundColor ushr 24) != 0

        /*
         * 背景与描边在 ASS 样式里互斥（BorderStyle=3 的 Outline 值表示背景框内边距，
         * 不能再画文字描边）；两者都选时以「背景框」优先——与 Compose 覆盖层里「背景 + 描边」
         * 的观感最接近的近似。
         */
        val borderStyle = if (hasBackground) 3 else 1
        val boxPaddingPx = (style.edgeWidthDp + 4f) * density.coerceAtLeast(0.5f)
        val outline =
            if (hasBackground) {
                (boxPaddingPx * height / frameHeight).roundToInt().coerceAtLeast(1)
            } else {
                outlineScript
            }
        val outlineColor = if (hasBackground) style.backgroundColor else OPAQUE_BLACK

        val builder = StringBuilder(1024 + cues.size * 96)
        builder
            .appendLine("[Script Info]")
            .appendLine("; Cinefin · SRT/VTT → ASS（libass 路径，W16）")
            .appendLine("ScriptType: v4.00+")
            .appendLine("PlayResX: $width")
            .appendLine("PlayResY: $height")
            .appendLine("WrapStyle: 0")
            .appendLine("ScaledBorderAndShadow: yes")
            .appendLine("YCbCr Matrix: TV.709")
            .appendLine()
            .appendLine("[V4+ Styles]")
            .appendLine(ASS_STYLE_FORMAT)
            .append("Style: Default,")
            .append(fontName)
            .append(',')
            .append(fontSize)
            .append(',')
            .append(assColor(style.textColor))
            .append(',')
            .append(assColor(style.textColor))
            .append(',')
            .append(assColor(outlineColor))
            .append(',')
            .append(assColor(OPAQUE_BLACK))
            .append(
                ",-1,0,0,0,100,100,0,0,"
            ) // Bold/Italic/Underline/StrikeOut/ScaleX/ScaleY/Spacing/Angle
            .append(borderStyle)
            .append(',')
            .append(outline)
            .append(",0,2,") // Outline / Shadow / Alignment=2（底部居中）
            .append(MARGIN_L)
            .append(',')
            .append(MARGIN_L)
            .append(',')
            .append(marginV)
            .append(",1") // Encoding
            .appendLine()
            .appendLine()
            .appendLine("[Events]")
            .appendLine(ASS_EVENT_FORMAT)

        cues.forEach { cue ->
            val text = escapeText(cue.text)
            if (text.isEmpty()) return@forEach
            builder
                .append("Dialogue: 0,")
                .append(formatTime(cue.startMs))
                .append(',')
                .append(formatTime(cue.endMs))
                .append(",Default,,0,0,0,,")
                .append(text)
                .appendLine()
        }
        return builder.toString()
    }

    /** ASS / SSA 原始脚本：只去掉 BOM、补齐结尾换行（内容一律不改，特效交给脚本） */
    fun normalize(script: String): String {
        val trimmed = script.removePrefix("\uFEFF")
        return if (trimmed.endsWith("\n")) trimmed else "$trimmed\n"
    }

    /** ASS 时间戳：`H:MM:SS.cc`（厘秒） */
    fun formatTime(millis: Long): String {
        val total = millis.coerceAtLeast(0L)
        val hours = total / 3_600_000L
        val minutes = (total / 60_000L) % 60L
        val seconds = (total / 1_000L) % 60L
        val centis = (total % 1_000L) / 10L
        return String.format(Locale.US, "%d:%02d:%02d.%02d", hours, minutes, seconds, centis)
    }

    /**
     * Android ARGB → ASS `&HAABBGGRR`。
     *
     * ASS 的 alpha 与 Android 相反：`00` = 不透明、`FF` = 全透明，所以要取反。
     */
    fun assColor(argb: Int): String {
        val alpha = 0xFF - (argb ushr 24 and 0xFF)
        val red = argb ushr 16 and 0xFF
        val green = argb ushr 8 and 0xFF
        val blue = argb and 0xFF
        return String.format(
            Locale.US,
            "&H%02X%02X%02X%02X",
            alpha,
            blue,
            green,
            red,
        )
    }

    /** 文本转义：换行 → `\N`；花括号会被 libass 当 override 标签，直接去掉 */
    fun escapeText(raw: String): String =
        raw.replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("{", "(")
            .replace("}", ")")
            .replace("\n", "\\N")
            .trim()

    private const val OPAQUE_BLACK = 0xFF000000.toInt()

    /** 左右安全边距（脚本坐标 5%）：与 Compose 覆盖层的 24dp 观感接近 */
    private const val MARGIN_L = 96

    private const val ASS_STYLE_FORMAT =
        "Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, " +
            "Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, " +
            "Shadow, Alignment, MarginL, MarginR, MarginV, Encoding"

    private const val ASS_EVENT_FORMAT =
        "Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text"
}
