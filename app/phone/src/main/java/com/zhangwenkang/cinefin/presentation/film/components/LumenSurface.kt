package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * Lumen（S1 方向 A · 流光）手法原语。
 *
 * 只做四件事，不引入新 token：
 * 1. **双层嵌套卡片**——外壳 1dp 渐变描边（白 16% → 5% → 10%）+ 内核顶部 1px 内高光 + 一圈极轻外发光（§5.2）；
 * 2. **底部渐隐 + 顶部光晕**——内容图向上"透光"、向下溶进页面底色，文字永远压在可读的暗场上；
 * 3. **入场**——opacity 0→1 + translateY 8dp→0，560ms Emphasized、40ms 错峰（§6.3）。
 *
 * 刻意不做的两件（守住 B 纪律，见 `UI_PLAN.md` 决策 D17）：焦点放大 1.04（触摸端无 hover 语义，且 §6.3 明确禁止位移 / 缩放），
 * 胶片颗粒噪点（需要新增位图资源，收益低于成本）。动画只改透明度与位移，走 `graphicsLayer`，不触发逐帧重组。
 *
 * 光的颜色纪律（§2.6 / §5.3）：光晕、外发光、内高光一律用**无彩色的白 / 黑**（和既有 `lumenVignette` 同口径），
 * 媒体色只出现在进度、焦点描边与主行动上——不新增媒体色发光。
 */

/** 卡片外壳：1dp 渐变描边 + 顶部 1px 内高光，统一画在内容之上（压在图上也可见）；外壳外一圈极轻的光晕。 */
@Composable
fun LumenCardFrame(
    modifier: Modifier = Modifier,
    shape: Shape = CinefinShapes.Md,
    emphasized: Boolean = false,
    container: Color = LocalCinefinColors.current.surfaceContainerLow,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val borderBrush =
        if (emphasized) {
            Brush.linearGradient(
                colorStops =
                    arrayOf(
                        0f to media.outline,
                        0.5f to media.base.copy(alpha = 0.55f),
                        1f to media.outline,
                    )
            )
        } else {
            // 铝框感：上缘受光更亮、中段回落、下缘再收一点光，避免"通体一根灰线"
            Brush.linearGradient(
                colorStops =
                    arrayOf(
                        0f to Color.White.copy(alpha = 0.16f),
                        0.38f to Color.White.copy(alpha = 0.05f),
                        1f to Color.White.copy(alpha = 0.10f),
                    )
            )
        }
    Box(
        modifier = modifier.lumenOuterGlow(shape).clip(shape).background(container),
        content = {
            content()
            Box(
                modifier =
                    Modifier.matchParentSize()
                        .border(width = 1.dp, brush = borderBrush, shape = shape)
                        .lumenTopHighlight(colors.topHighlight)
            )
        },
    )
}

/** Lumen 卡片入场：560ms Emphasized + 40ms 错峰（超过 8 项后不再累加，避免长列表尾部等待）。 */
@Composable
fun Modifier.lumenEntrance(index: Int = 0): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(
            targetValue = 1f,
            animationSpec =
                tween(
                    durationMillis = CinefinMotion.Enter,
                    delayMillis = index.coerceIn(0, 8) * CinefinMotion.Stagger,
                    easing = CinefinMotion.Emphasized,
                ),
        )
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}

/** 内容图底部 96dp 渐隐：文字托底，同时让整块图与页面底色自然衔接。 */
fun lumenBottomScrim(bottomColor: Color): Brush =
    Brush.verticalGradient(
        colorStops =
            arrayOf(
                0f to Color.Transparent,
                0.42f to Color.Transparent,
                0.68f to bottomColor.copy(alpha = 0.42f),
                0.88f to bottomColor.copy(alpha = 0.88f),
                1f to bottomColor,
            )
    )

/** 头图顶部光晕（S1 "内容即光源"）：光从画面上缘溢出、约 40% 处完全消散。 纯白低透明度，只加光不加色；压在图上时读作"画面在发光"，而不是盖了一层白纱。 */
val lumenTopGlow: Brush
    get() =
        Brush.verticalGradient(
            colorStops =
                arrayOf(
                    0f to Color.White.copy(alpha = 0.12f),
                    0.16f to Color.White.copy(alpha = 0.05f),
                    0.42f to Color.Transparent,
                )
        )

/** 内暗角：左右边缘各压暗一点，模拟"内容即光源、画面向内收"的光学感。 */
val lumenVignette: Brush
    get() =
        Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = 0.32f),
            0.16f to Color.Transparent,
            0.84f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.32f),
        )

/** 极轻外发光：沿卡片轮廓晕出 2–4dp 的白光（内半圈被卡片本体盖住），守住"无投影"（§5.3）。 */
private fun Modifier.lumenOuterGlow(shape: Shape): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(
        outline = outline,
        color = Color.White.copy(alpha = 0.035f),
        style = Stroke(width = 8.dp.toPx()),
    )
    drawOutline(
        outline = outline,
        color = Color.White.copy(alpha = 0.07f),
        style = Stroke(width = 3.dp.toPx()),
    )
}

/** 顶部 1px 内高光：中段最亮、两端收光，读作"边缘被光扫过"。 */
private fun Modifier.lumenTopHighlight(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(
        brush =
            Brush.horizontalGradient(
                colorStops =
                    arrayOf(
                        0f to color,
                        0.5f to color.copy(alpha = color.alpha * 2.6f),
                        1f to color.copy(alpha = color.alpha * 0.6f),
                    )
            ),
        size = Size(size.width, 1.dp.toPx()),
    )
}
