package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * MUSIC-17 封面调色板 → [MediaColors] 的纯函数派生（W5-T2 定稿 §3）。
 *
 * 调用方（音乐模块）只负责把封面位图交给 `androidx.palette` 提取、按 itemId 缓存；本文件承担全部判定：
 *
 * - 取色优先级 `vibrant ?: muted ?: dominant`；
 * - 纯色封面（HSL 饱和度 < [CoverMediaColors.PlainSaturation]）与提取失败一律回落域色（返回 null，调用方用 [mediaColorsFor]
 *   的域色）；
 * - 可读性护栏：[readableCoverBase] 对 `surface` 对比度 < 3:1 的 base 按 +8% 明度提亮，最多 3 步；
 * - 派生：[MediaColors.bright] / [dim] 由 base 明度 ±10% 得到，`container` / `containerPressed` / `outline`
 *   走既有 [MediaColors.derivedOver] 在 `surfaceContainer` 上合成，与域色同一套口径。
 *
 * 全部函数无 Android 运行时依赖（HSL 与对比度自带实现），可在 JVM 单测里钉死。
 */
object CoverMediaColors {
    /** 纯色封面阈值：提取色 HSL 饱和度低于此值视为纯色封面。 */
    const val PlainSaturation = 0.08f

    /** 可读性护栏：base 对 surface 的对比度下限（WCAG AA 大字 / 图形元素 3:1）。 */
    const val MinBaseContrast = 3f

    /** 护栏提亮步进（HSL 明度）。 */
    const val BaseLightnessStep = 0.08f

    /** 护栏提亮上限步数。 */
    const val BaseMaxSteps = 3

    /** bright / dim 相对 base 的明度偏移。 */
    const val ToneShift = 0.10f
}

/**
 * 封面提取色 → 覆盖用的 [MediaColors]；提取失败、无封面或纯色封面返回 null（调用方回落域色）。
 *
 * @param vibrant `Palette` 的 vibrant 取样色（优先级最高）
 * @param muted `Palette` 的 muted 取样色
 * @param dominant `Palette` 的 dominant 取样色
 */
fun mediaColorsFromCover(
    vibrant: Color?,
    muted: Color?,
    dominant: Color?,
    colors: CinefinColors,
): MediaColors? {
    val picked = vibrant ?: muted ?: dominant ?: return null
    if (picked.toHsl().saturation < CoverMediaColors.PlainSaturation) return null
    return mediaColorsForCoverColor(picked, colors)
}

/** 单个提取色直接派生（跳过优先级选择，仍做纯色回落判定）。 */
internal fun mediaColorsForCoverColor(picked: Color, colors: CinefinColors): MediaColors {
    val base = readableCoverBase(picked, colors.surface)
    val hsl = base.toHsl()
    val bright = Hsl(hsl.hue, hsl.saturation, hsl.lightness + CoverMediaColors.ToneShift).toColor()
    val dim = Hsl(hsl.hue, hsl.saturation, hsl.lightness - CoverMediaColors.ToneShift).toColor()
    // 文字永远走 onSurface 系；onBase 只服务「base 实底 + 图标」的少数场景，按对比度取黑白。
    val onBase =
        if (contrastRatio(Color.White, base) >= contrastRatio(Color.Black, base)) {
            Color.White
        } else {
            Color.Black
        }
    return MediaColors(
            base = base,
            bright = bright,
            dim = dim,
            onBase = onBase,
            // container / containerPressed / outline 三个槽位交给 derivedOver 按 surfaceContainer 合成
            container = Color.Transparent,
            containerPressed = Color.Transparent,
            outline = Color.Transparent,
        )
        .derivedOver(colors.surfaceContainer)
}

/**
 * 可读性护栏：base 与 [surface] 对比度 < [CoverMediaColors.MinBaseContrast] 时按 +8% 明度提亮，最多 3 步。
 *
 * 始终返回「提亮后仍达不到 3:1 的最佳尝试」（例如极暗色在纯黑背景上），不返回 null——调用方不需要再分支。
 */
internal fun readableCoverBase(base: Color, surface: Color): Color {
    var current = base
    repeat(CoverMediaColors.BaseMaxSteps) {
        if (contrastRatio(current, surface) >= CoverMediaColors.MinBaseContrast) return current
        val hsl = current.toHsl()
        current =
            Hsl(
                    hsl.hue,
                    hsl.saturation,
                    (hsl.lightness + CoverMediaColors.BaseLightnessStep).coerceAtMost(1f),
                )
                .toColor()
    }
    return current
}

/** HSL 三元组（hue 0–360，saturation / lightness 0–1）。 */
internal data class Hsl(val hue: Float, val saturation: Float, val lightness: Float) {
    internal fun toColor(): Color =
        Color.hsl(
            hue = hue,
            saturation = saturation.coerceIn(0f, 1f),
            lightness = lightness.coerceIn(0f, 1f),
        )
}

internal fun Color.toHsl(): Hsl {
    val maxChannel = max(red, max(green, blue))
    val minChannel = min(red, min(green, blue))
    val delta = maxChannel - minChannel
    val lightness = (maxChannel + minChannel) / 2f
    if (delta <= 0f) return Hsl(0f, 0f, lightness)
    val saturation =
        if (lightness > 0.5f) {
            delta / (2f - maxChannel - minChannel)
        } else {
            delta / (maxChannel + minChannel)
        }
    val hue =
        when (maxChannel) {
            red -> ((green - blue) / delta + if (green < blue) 6f else 0f) / 6f
            green -> ((blue - red) / delta + 2f) / 6f
            else -> ((red - green) / delta + 4f) / 6f
        }
    return Hsl(hue * 360f, saturation, lightness)
}

/** WCAG 相对亮度（sRGB 线性化）。 */
internal fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) +
        0.7152 * channel(color.green) +
        0.0722 * channel(color.blue)
}

/** WCAG 对比度（1–21）。 */
internal fun contrastRatio(foreground: Color, background: Color): Double {
    val a = relativeLuminance(foreground)
    val b = relativeLuminance(background)
    val (lighter, darker) = if (a >= b) a to b else b to a
    return (lighter + 0.05) / (darker + 0.05)
}
