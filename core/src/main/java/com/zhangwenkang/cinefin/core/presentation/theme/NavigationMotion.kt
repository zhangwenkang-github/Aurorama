package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * W3 导航动效 token（`docs/V2_W3_TASKS.md`「W3 导航决策定稿」）。
 *
 * 导航容器只做 `opacity + translate`：页面切换 120ms、位移 8dp；抽屉开 200ms / 关 160ms。 曲线统一用
 * EmphasizedDecelerate（减速、单调、无过冲），导航容器禁用弹簧。
 */
object CinefinNavigationMotion {
    /** 一级 / 二级页面切换时长。 */
    const val PageTransitionMillis = 120

    /** 页面切换的水平位移（只做视觉位移，不触发重排）。 */
    val PageTransitionOffset: Dp = 8.dp

    /** 抽屉打开时长。 */
    const val DrawerOpenMillis = 200

    /** 抽屉关闭时长。 */
    const val DrawerCloseMillis = 160

    /** Reduce Motion 打开时的兜底淡入时长（定稿上限 50ms）。 */
    const val ReducedMotionMillis = 50

    /** 抽屉位移的减速曲线：起步快、收尾缓，无弹跳 / 过冲。 */
    val EmphasizedDecelerate = Motion.emphasizedDecelerate

    fun drawerOpenSpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) snap() else tween(DrawerOpenMillis, easing = EmphasizedDecelerate)

    fun drawerCloseSpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) snap() else tween(DrawerCloseMillis, easing = EmphasizedDecelerate)

    /** 手势松手后的吸附时长；与打开同规格，仍为减速曲线而非弹簧。 */
    fun drawerSettleSpec(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) snap() else tween(DrawerOpenMillis, easing = EmphasizedDecelerate)
}
