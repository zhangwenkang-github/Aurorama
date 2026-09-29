package com.zhangwenkang.cinefin.utils

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 播放页“氛围色”工具。
 *
 * 设计原则：只把影片海报的主色用于背景渐变（画面以外的区域与加载态）， 控件颜色保持应用统一的冰蓝，避免整屏变色导致视觉混乱。
 */
object AmbientColors {
    /** 取色时的采样尺寸（像素），越小越快，24x24 已足够稳定 */
    const val SAMPLE_SIZE = 24

    /** 从海报中挑选最具代表性的颜色：偏好有色彩倾向、亮度适中的像素， 避免挑到纯黑边框或高光白。 */
    fun extract(bitmap: Bitmap): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null

        val sample = Bitmap.createScaledBitmap(bitmap, SAMPLE_SIZE, SAMPLE_SIZE, true)
        var bestScore = Float.NEGATIVE_INFINITY
        var bestColor: Int? = null

        for (y in 0 until SAMPLE_SIZE) {
            for (x in 0 until SAMPLE_SIZE) {
                val color = sample.getPixel(x, y)
                val r = Color.red(color)
                val g = Color.green(color)
                val b = Color.blue(color)

                val maxChannel = max(r, max(g, b))
                if (maxChannel == 0) continue

                val minChannel = min(r, min(g, b))
                val saturation = (maxChannel - minChannel).toFloat() / maxChannel
                val luminance = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f

                // 有颜色倾向且亮度居中者得分最高
                val score = saturation * 1.8f - abs(luminance - 0.5f) * 1.1f
                if (score > bestScore) {
                    bestScore = score
                    bestColor = color
                }
            }
        }

        if (sample != bitmap) sample.recycle()
        return bestColor
    }

    /** 由氛围色生成深色渐变：顶部保留色调、向下逐渐收黑， 同时压低饱和度与亮度，保证不会喧宾夺主。 */
    fun gradientFor(color: Int): GradientDrawable {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        val hue = hsv[0]
        val saturation = min(hsv[1], 0.5f)
        val value = hsv[2].coerceIn(0.30f, 0.55f)

        val top = Color.HSVToColor(floatArrayOf(hue, saturation, value))
        val middle = Color.HSVToColor(floatArrayOf(hue, saturation * 0.85f, value * 0.42f))
        val bottom = Color.HSVToColor(floatArrayOf(hue, saturation * 0.7f, 0.06f))

        return GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, middle, bottom),
        )
    }
}
