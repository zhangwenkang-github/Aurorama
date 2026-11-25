package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 极光渐变角度：CSS `linear-gradient(100deg)`（0° 朝上、90° 朝右）。 */
private const val AuroraGradientDegrees = 100.0

/**
 * 极光渐变（`au1 → au2 → au3`，100°）的渐变线起止点。
 *
 * 角度按 CSS `linear-gradient(<deg>)` 的渐变线定义：方向向量 `(sin θ, -cos θ)`，渐变线长度取 `|w·sin θ| + |h·cos
 * θ|`，起止点以构件中心对称。 尺寸无关的纯函数，便于单测角度算法。
 */
fun auroraGradientEnds(size: Size): Pair<Offset, Offset> {
    val radians = Math.toRadians(AuroraGradientDegrees)
    val dirX = sin(radians).toFloat()
    val dirY = -cos(radians).toFloat()
    val length = abs(size.width * dirX) + abs(size.height * dirY)
    val center = Offset(size.width / 2f, size.height / 2f)
    val start = Offset(center.x - dirX * length / 2f, center.y - dirY * length / 2f)
    val end = Offset(center.x + dirX * length / 2f, center.y + dirY * length / 2f)
    return start to end
}

/** 极光三色（`aurora1 → aurora2 → aurora3`）的 100° 线性渐变刷，渐变线长度由 [size] 决定。 */
fun auroraGradientBrush(colors: CinefinColors, size: Size): Brush {
    val (start, end) = auroraGradientEnds(size)
    return Brush.linearGradient(
        colors = listOf(colors.aurora1, colors.aurora2, colors.aurora3),
        start = start,
        end = end,
    )
}

/**
 * 以 100° 极光渐变填充当前绘制区域（2.0.1 定稿 §2「域色 = 身份，极光 = 行动」）。
 *
 * 一级 CTA 实底、进度条已播段、导航选中指示统一走这里，保证全 App 只有一套极光渐变。
 *
 * @param cornerRadius 非空时按圆角矩形绘制；为空（默认）则绘制直角矩形，圆角由调用方的 `clip(shape)` 决定。
 * @param visible `false` 时不绘制——供尺寸常驻的槽位（如导航指示条）在未选中时留空，避免布局跳动。
 */
fun Modifier.auroraGradient(
    colors: CinefinColors,
    cornerRadius: Dp? = null,
    visible: Boolean = true,
): Modifier = drawBehind {
    if (!visible) return@drawBehind
    val brush = auroraGradientBrush(colors, size)
    if (cornerRadius == null) {
        drawRect(brush = brush)
    } else {
        drawRoundRect(brush = brush, cornerRadius = CornerRadius(cornerRadius.toPx()))
    }
}
