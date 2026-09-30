package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/**
 * Cinefin 动效 token（UI_DESIGN_SYSTEM §6）。
 *
 * 只动画 `transform / opacity / color`；blur 只作用于固定层（顶栏 / 控制层 / 弹层）。 「减少动画」（系统动画缩放为 0）时全部时长置
 * 0，直接切换——由调用方读取系统设置处理。
 */
object CinefinMotion {
    /** 图标切换、勾选。 */
    const val Instant = 100

    /** hover / focus 描边与颜色过渡。 */
    const val Fast = 200

    /** 阅读翻页交叉淡入。 */
    const val Reader = 220

    /** 播放器控制层出现 / 消失。 */
    const val Player = 280

    /** 面板、侧滑层、导航展开。 */
    const val Page = 420

    /** 卡片 / 列表首次入场。 */
    const val Enter = 560

    /** 列表项错峰间隔。 */
    const val Stagger = 40

    /** 首页沉浸头图（A 手法，仅此一处慢动效）。 */
    const val Immersive = 700

    /** 入场、面板、导航（B 稿主曲线）：更快出、更缓收。 */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** 颜色 / 描边状态过渡。 */
    val Standard: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    /** 元素出现。 */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    /** 元素消失。 */
    val Accelerate: Easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)
}
