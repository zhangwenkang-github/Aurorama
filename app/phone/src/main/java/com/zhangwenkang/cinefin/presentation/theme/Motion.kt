package com.zhangwenkang.cinefin.presentation.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing

/**
 * 动效规范：统一全应用的动画时长与缓动曲线，避免各处自定义导致节奏不一致。
 *
 * 设计取向为“电影感”：进入柔和、退出干脆，避免弹跳类动画带来的廉价感。
 */
object Motion {
    /** 微交互：按下反馈、图标切换 */
    const val durationInstant = 100

    /** 轻量过渡：淡入淡出、颜色变化 */
    const val durationFast = 180

    /** 标准过渡：卡片展开、内容切换 */
    const val durationMedium = 280

    /** 页面级过渡：整屏进出、大图展开 */
    const val durationSlow = 420

    /** 海报墙入场：逐个错开的延迟步长与上限 */
    const val staggerStep = 40
    const val staggerMax = 240

    /** 标准缓动：起步快、收尾缓，适合大多数 UI 位移 */
    val standard: Easing = FastOutSlowInEasing

    /** 入场缓动：柔和的减速曲线，用于大图与整屏进场 */
    val emphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** 退场缓动：快速离场，避免拖沓 */
    val emphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** 轻柔淡入 */
    val fadeIn: Easing = LinearOutSlowInEasing
}
