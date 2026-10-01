package com.zhangwenkang.cinefin.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalLumenColors

/**
 * Lumen 加载过渡组件（W6-VIS 决策 D24）。
 *
 * 目标：加载阶段不再"黑板直出"——页面先用骨架屏占位，数据到达后骨架淡出、内容淡入。遵守 §6.4 性能规则： 全部动效只走**不透明度与位移**（`graphicsLayer` +
 * 渐变平移），不改变尺寸、不触发逐帧重组（读数只发生在绘制阶段）。
 *
 * 色值不新增：骨架 = 石墨底 + 雾灰扫光（Lumen 区域），Prism 区域自动回落到 `SurfaceContainer` / `SurfaceContainerHigh`。
 */

/** 骨架底色的语义取值（Lumen 用石墨 / 雾灰，Prism 用表面容器色）。 */
@Composable
private fun skeletonColors(): Pair<Color, Color> {
    val lumen = LocalLumenColors.current
    val colors = LocalCinefinColors.current
    return if (lumen != null) {
        lumen.panel to lumen.panelElevated
    } else {
        colors.surfaceContainer to colors.surfaceContainerHigh
    }
}

/**
 * 骨架扫光：底色 + 一条横向移动的高光带。
 *
 * 动画值在 `drawBehind` 里读取，只会触发**重绘**；高光带用渐变起止点平移实现，不做模糊、不改变布局。
 */
@Composable
fun Modifier.lumenShimmer(): Modifier {
    val (base, highlight) = skeletonColors()
    val transition = rememberInfiniteTransition(label = "lumen-shimmer")
    val progress by
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec =
                infiniteRepeatable(
                    animation = tween(durationMillis = 1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
            label = "lumen-shimmer-progress",
        )
    return this.drawBehind {
        drawRect(color = base)
        val band = size.width.coerceAtLeast(size.height) * 0.75f
        if (band <= 0f) return@drawBehind
        val start = -band + (size.width + band * 2f) * progress
        drawRect(
            brush =
                Brush.linearGradient(
                    colors = listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(start, 0f),
                    end = Offset(start + band, size.height),
                ),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
        )
    }
}

/** 骨架占位块：圆角由调用方决定，默认 12dp（与卡片 / 磁贴同一套半径语言）。 */
@Composable
fun LumenSkeletonBlock(modifier: Modifier = Modifier, shape: Shape = CinefinShapes.Sm) {
    Box(modifier = modifier.clip(shape).lumenShimmer())
}

/** 骨架文本行：宽度按比例，默认 14dp 高（接近正文行高）。 */
@Composable
fun LumenSkeletonLine(
    modifier: Modifier = Modifier,
    widthFraction: Float = 1f,
    height: Dp = 14.dp,
    shape: Shape = RoundedCornerShape(percent = 50),
) {
    LumenSkeletonBlock(
        modifier = modifier.fillMaxWidth(widthFraction).height(height),
        shape = shape,
    )
}

/**
 * 加载过渡外壳：`isLoading` 时盖上骨架屏，数据就绪后骨架 220ms 淡出、内容 220ms 淡入。
 *
 * 内容始终参与组合（只是透明度为 0），因此页面内部的滚动位置 / 分页状态不会在过渡时被重建。
 */
@Composable
fun LumenLoadingTransition(
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    skeleton: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val contentAlpha by
        animateFloatAsState(
            targetValue = if (isLoading) 0f else 1f,
            animationSpec = tween(durationMillis = CinefinMotion.Reader),
            label = "lumen-content-alpha",
        )
    Box(modifier = modifier) {
        Box(modifier = Modifier.graphicsLayer { alpha = contentAlpha }, content = content)
        AnimatedVisibility(
            visible = isLoading,
            enter = fadeIn(tween(CinefinMotion.Reader)),
            exit = fadeOut(tween(CinefinMotion.Reader)),
        ) {
            Box(content = skeleton)
        }
    }
}

/**
 * 骨架屏淡入 / 淡出外壳。
 *
 * 单独包一层是为了避开作用域重载：在 `Column` / `Row` 里直接写 `AnimatedVisibility` 会解析到
 * `ColumnScope.AnimatedVisibility`，在 `Box` 内容里会因隐式接收者冲突而编译失败。
 */
@Composable
fun LumenSkeletonOverlay(
    visible: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(CinefinMotion.Reader)),
        exit = fadeOut(tween(CinefinMotion.Reader)),
        label = "lumen-skeleton-overlay",
    ) {
        content()
    }
}

/**
 * 冷启动过渡页（W6-VIS D24）：`MainViewModel` 解析登录态期间显示的品牌页。
 *
 * 背景铺曜石黑（A 稿 `--bg`），中心是品牌印记 + 应用名 + 一条呼吸感进度线；登录态就绪后主界面以 220ms 淡入接上，第一帧到主界面之间不再有"纯黑板"的空白期。
 */
@Composable
fun ColdStartSplash(modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    val lumen = LocalLumenColors.current
    Box(
        modifier = modifier.fillMaxSize().background(lumen?.background ?: colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            Box(
                modifier =
                    Modifier.size(64.dp)
                        .clip(CinefinShapes.Lg)
                        .background(lumen?.panelElevated ?: colors.surfaceContainerHigh)
                        .then(
                            if (lumen != null) {
                                Modifier.border(1.dp, lumen.line, CinefinShapes.Lg)
                            } else {
                                Modifier
                            }
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_logo),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(40.dp),
                )
            }
            Text(
                text = stringResource(CoreR.string.app_name),
                style = CinefinType.TitleMedium,
                color = lumen?.text ?: colors.onSurface,
            )
            LumenSkeletonBlock(modifier = Modifier.width(96.dp).height(4.dp))
        }
    }
}

// ---- 页面级骨架（首页 / 媒体库 / 库内容 / 详情 / 设置）------------------------------------------

/** 首页骨架：主视觉大卡 + 一条走廊 + 一面海报墙。 */
@Composable
fun HomeSkeleton(columns: Int, gutterStart: Dp, gutterEnd: Dp, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space8),
    ) {
        LumenSkeletonBlock(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(start = gutterStart, end = gutterEnd)
                    .height(260.dp),
            shape = CinefinShapes.Lg,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = gutterStart, end = gutterEnd),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            LumenSkeletonLine(widthFraction = 0.22f, height = 20.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4)) {
                repeat(3) {
                    LumenSkeletonBlock(
                        modifier = Modifier.width(220.dp).height(124.dp),
                        shape = CinefinShapes.Md,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = gutterStart, end = gutterEnd),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            LumenSkeletonLine(widthFraction = 0.18f, height = 20.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4)) {
                repeat(columns.coerceIn(2, 6)) {
                    LumenSkeletonBlock(
                        modifier = Modifier.weight(1f).height(210.dp),
                        shape = CinefinShapes.Md,
                    )
                }
            }
        }
    }
}

/** 媒体库总览骨架：大标题 + 收藏行 + 库卡（16:9 大卡）。 */
@Composable
fun MediaLibrarySkeleton(
    gutterStart: Dp,
    gutterEnd: Dp,
    columns: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(start = gutterStart, end = gutterEnd),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
    ) {
        LumenSkeletonLine(widthFraction = 0.3f, height = 34.dp)
        LumenSkeletonLine(widthFraction = 0.16f, height = 16.dp)
        LumenSkeletonBlock(
            modifier = Modifier.fillMaxWidth().height(112.dp),
            shape = CinefinShapes.Md,
        )
        repeat(if (columns >= 2) 2 else 1) {
            LumenSkeletonBlock(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                shape = CinefinShapes.Md,
            )
        }
    }
}

/** 库内容页骨架：标题 + 海报栅格。 */
@Composable
fun LibraryGridSkeleton(
    columns: Int,
    tileHeight: Dp,
    gutterStart: Dp,
    gutterEnd: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space6),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = gutterStart, end = gutterEnd),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        ) {
            LumenSkeletonLine(widthFraction = 0.26f, height = 30.dp)
            LumenSkeletonLine(widthFraction = 0.14f, height = 14.dp)
        }
        repeat(2) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = gutterStart, end = gutterEnd),
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
            ) {
                repeat(columns.coerceIn(2, 6)) {
                    LumenSkeletonBlock(
                        modifier = Modifier.weight(1f).height(tileHeight),
                        shape = CinefinShapes.Md,
                    )
                }
            }
        }
    }
}

/** 详情页骨架：沉浸头图 + 标题 / 元信息 / 行动区 + 演职人员行。 */
@Composable
fun DetailSkeleton(gutterStart: Dp, gutterEnd: Dp, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        LumenSkeletonBlock(
            modifier = Modifier.fillMaxWidth().height(300.dp),
            shape = CinefinShapes.Lg,
        )
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = gutterStart + CinefinSpacing.Space6,
                        end = gutterEnd + CinefinSpacing.Space6,
                        top = CinefinSpacing.Space6,
                    ),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            LumenSkeletonLine(widthFraction = 0.18f, height = 14.dp)
            LumenSkeletonLine(widthFraction = 0.46f, height = 32.dp)
            LumenSkeletonLine(widthFraction = 0.3f, height = 16.dp)
            LumenSkeletonLine(widthFraction = 0.62f, height = 14.dp)
            LumenSkeletonLine(widthFraction = 0.5f, height = 14.dp)
            Spacer(Modifier.height(CinefinSpacing.Space2))
            Row(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4)) {
                LumenSkeletonBlock(modifier = Modifier.width(150.dp).height(46.dp))
                LumenSkeletonBlock(modifier = Modifier.size(46.dp))
                LumenSkeletonBlock(modifier = Modifier.size(46.dp))
            }
        }
    }
}

/** 设置页骨架：三张分组卡（图标磁贴 + 标题 + 尾部控件）。 */
@Composable
fun SettingsSkeleton(
    gutterStart: Dp,
    gutterEnd: Dp,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(start = gutterStart, end = gutterEnd),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space8),
    ) {
        LumenSkeletonBlock(
            modifier = Modifier.fillMaxWidth().widthIn(max = maxWidth).height(72.dp),
            shape = CinefinShapes.Md,
        )
        repeat(3) {
            LumenSkeletonBlock(
                modifier =
                    Modifier.fillMaxWidth()
                        .widthIn(max = maxWidth)
                        .height(if (it == 0) 168.dp else 216.dp),
                shape = CinefinShapes.Lg,
            )
        }
    }
}
