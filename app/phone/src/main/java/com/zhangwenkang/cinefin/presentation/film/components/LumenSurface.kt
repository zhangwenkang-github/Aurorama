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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors

/**
 * Lumen（S1 方向 A · 流光）手法原语。
 *
 * 只做三件事，不引入新 token：
 * 1. **双层嵌套卡片**——外壳 1dp 描边（白 10% → 白 2% 的渐变感）+ 内核顶部 1px 内高光（§5.2）；
 * 2. **底部渐隐**——内容图向下溶进页面底色，文字永远压在可读的暗场上；
 * 3. **入场**——opacity 0→1 + translateY 8dp→0，560ms Emphasized、40ms 错峰（§6.3）。
 *
 * 刻意不做的两件（守住 B 纪律，见 `UI_PLAN.md` 决策 D17）：焦点放大 1.04（触摸端无 hover 语义，且 §6.3 明确禁止位移 / 缩放），
 * 胶片颗粒噪点（需要新增位图资源，收益低于成本）。动画只改透明度与位移，走 `graphicsLayer`，不触发逐帧重组。
 */

/** 卡片外壳：1dp 描边 + 顶部 1px 内高光，统一画在内容之上（压在图上也可见）。 */
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
    val borderColor = if (emphasized) media.outline else colors.outline
    Box(
        modifier = modifier.clip(shape).background(container),
        content = {
            content()
            Box(
                modifier =
                    Modifier.matchParentSize()
                        .border(width = 1.dp, color = borderColor, shape = shape)
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
fun lumenBottomScrim(bottomColor: Color, heightFraction: Float = 0.96f): Brush =
    Brush.verticalGradient(
        0f to Color.Transparent,
        1f - heightFraction to Color.Transparent,
        0.72f to bottomColor.copy(alpha = 0.55f),
        1f to bottomColor,
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

private fun Modifier.lumenTopHighlight(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(color = color, size = Size(size.width, 1.dp.toPx()))
}
