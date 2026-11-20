package com.zhangwenkang.cinefin.core.presentation.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.IntOffset

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

    /** 控制层出入位移、面板缩放：可中断、无可见回弹。 */
    val SpringControlIn = spring<Float>(dampingRatio = 0.86f, stiffness = 400f)

    /**
     * [SpringControlIn] 的位移版：`slideInVertically` / `slideOutVertically` 要 [IntOffset] 规格。
     *
     * 参数与 [SpringControlIn] 同源（不另写一套数值），阈值取像素级 1px，弹簧在亚像素阶段停住。
     */
    val SpringControlInOffset: SpringSpec<IntOffset> =
        spring(
            dampingRatio = SpringControlIn.dampingRatio,
            stiffness = SpringControlIn.stiffness,
            visibilityThreshold = IntOffset.VisibilityThreshold,
        )

    /** 控制层退场位移：临界阻尼，让位时一次到位、不甩尾。 */
    val SpringControlOutOffset: SpringSpec<IntOffset> =
        spring(
            dampingRatio = 1f,
            stiffness = 400f,
            visibilityThreshold = IntOffset.VisibilityThreshold,
        )

    /** 播放 / 暂停图标切换过冲（1→约 1.18→1）。 */
    val SpringOvershoot = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)

    /** 进度条释放回弹与章节吸附落位。 */
    val SpringSnap = spring<Float>(dampingRatio = 0.75f, stiffness = 600f)

    /**
     * W5-T3（MUSIC-39）：迷你播放条 ↔ 全屏播放页的容器变换展开（约 350–400ms 档）。
     *
     * 与 [SpringCollapse] 成对存在：展开比收起松、收起更快回。
     */
    val SpringExpand = spring<Float>(dampingRatio = 0.86f, stiffness = 300f)

    /** W5-T3（MUSIC-39）：容器变换收起（约 300ms，更快回）。 */
    val SpringCollapse = spring<Float>(dampingRatio = 1f, stiffness = 380f)

    /**
     * W5-T3（MUSIC-44）：减少动态时的降级时长。
     *
     * 「减少动画」开启后转场一律降级 `Crossfade(tween(150))`、弹簧降级 `tween(150)`；音乐动效包全部经 [reduceMotion] 走这一档。
     */
    const val ReducedMotionMillis = 150

    /**
     * W5-T3（MUSIC-44）：系统「减少动画」是否开启（`ANIMATOR_DURATION_SCALE == 0`）。
     *
     * 读取 `LocalContext` 的系统设置并在 composition 内缓存；设置变化由 [rememberReduceMotionEnabled] 的
     * ContentObserver 触发重组，无需重启。
     */
    @Composable fun reduceMotion(): Boolean = rememberReduceMotionEnabled()

    /** 减少动态时把任意动画规格换成 [ReducedMotionMillis] 的短 tween（弹簧 / 位移统一下降级）。 */
    fun <T> reducedMotionSpec(reduceMotion: Boolean, spec: AnimationSpec<T>): AnimationSpec<T> =
        if (reduceMotion) tween(durationMillis = ReducedMotionMillis) else spec
}
