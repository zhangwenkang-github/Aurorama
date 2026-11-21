package com.zhangwenkang.cinefin.book.presentation.reader.tts

import kotlin.math.roundToInt

/** Readium 高亮模板铺底的固定 alpha（T8 定稿五 §3：模板级 0.28）。 */
internal const val ReaderTtsTemplateAlpha = 0.28f

/** 朗读句底色 = 阅读域 accent 18%（定稿六 §3 / 定稿八「TTS 句高亮」）。 */
internal const val ReaderTtsHighlightAlpha = 0.18f

/** 200ms 底色淡入的量化档数与步长（定稿八）：4 档 × 50ms。 */
internal const val ReaderTtsHighlightFadeSteps = 4

internal const val ReaderTtsHighlightFadeStepMs = 50L

/**
 * 朗读句高亮的 tint 反解（定稿六 §3：accent 18% 底色）。
 *
 * Readium 的 `HtmlDecorationTemplates` 用**模板级固定 alpha** 铺 `rgba(tint, 0.28)`，`tint` 自带的 alpha
 * 会被丢弃（模板与用户标注共用同一份，不能为了 TTS 改全局值）。因此要把「accent 18% 叠在页面上」 反解成模板下的 `tint`：
 *
 * `0.28·tint + 0.72·surface = 0.18·accent + 0.82·surface` ⇒ `tint = (0.18·accent + 0.10·surface) /
 * 0.28`。
 *
 * 反解后 18% 观感与直接铺 18% 一致；`alpha = 0` 时 tint 恰好等于页面底色（不可见），正好用于 200ms 淡入的起始帧。
 */
internal object ReaderTtsHighlight {
    fun tint(
        accentArgb: Int,
        surfaceArgb: Int,
        alpha: Float = ReaderTtsHighlightAlpha,
    ): Int {
        val effective = alpha.coerceIn(0f, ReaderTtsTemplateAlpha)
        val template = ReaderTtsTemplateAlpha
        fun channel(shift: Int): Int {
            val accent = (accentArgb shr shift) and 0xFF
            val surface = (surfaceArgb shr shift) and 0xFF
            val value = (effective * accent + (template - effective) * surface) / template
            return value.roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    /** 淡入进度（0–1）→ tint：0 = 页面底色（不可见），1 = 18% accent。 */
    fun fadeTint(accentArgb: Int, surfaceArgb: Int, progress: Float): Int =
        tint(accentArgb, surfaceArgb, ReaderTtsHighlightAlpha * progress.coerceIn(0f, 1f))

    /**
     * 两个 tint 之间按通道线性插值（W6 T9r2 的 200ms 淡入由导航器侧分档下发用）。
     *
     * 纯整数运算：`fraction = 0` 取 [from]，`fraction = 1` 取 [to]，越界自动收进区间。
     */
    fun lerpArgb(from: Int, to: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        fun channel(shift: Int): Int {
            val start = (from shr shift) and 0xFF
            val end = (to shr shift) and 0xFF
            return (start + (end - start) * f).roundToInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }
}
