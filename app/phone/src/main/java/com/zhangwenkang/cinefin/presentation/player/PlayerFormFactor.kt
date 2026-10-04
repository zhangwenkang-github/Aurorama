/*
 * 播放页形态判定（D9）。
 *
 * 一个播放页要同时服务手机 / 平板 / 折叠 / TV / 车机 / 小窗 / 画中画，最容易失控的做法是
 * 「每个形态写一套界面」。这里把判定收成单点：窗口宽度档位 + 折叠姿势 + 系统 UI 模式 +
 * 多窗口状态 → [PlayerLayoutContext]，再由它决定用哪种骨架 [PlayerChromeLayout]。
 * 组件只读 context，不自己去看屏幕尺寸，避免出现两套互相矛盾的判断。
 */
package com.zhangwenkang.cinefin.presentation.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowMetricsCalculator
import kotlinx.coroutines.flow.collect
import timber.log.Timber

/** 设备形态：只影响取舍（字体尺度、命中区、是否沉浸），界面结构一律交给 [PlayerChromeLayout] */
enum class PlayerFormFactor {
    Phone,
    Tablet,
    Foldable,
    Tv,
    Car,
    Freeform,
}

/** 播放页骨架。六种骨架共用同一批原子组件（顶栏 / 中央簇 / 进度条 / 工具行 / 内容栏）。 */
enum class PlayerChromeLayout {
    /** 画面铺满，控制层叠在画面上：手机横屏、平板竖屏、TV、车机 */
    Fullscreen,

    /** 左画面 + 右侧常驻内容栏：平板横屏、折叠展开（>840dp） */
    SplitSide,

    /** 上画面（16:9 定高）+ 下方内容区：手机竖屏 */
    SplitPortrait,

    /** 折痕上半屏画面、下半屏控制与选集：折叠半开（水平折痕） */
    FoldHalfOpen,

    /** 单行合并控制条：自由窗口 / 分屏窄宽 */
    Compact,

    /** 画中画：控制层不渲染，交给系统窗口与三键 */
    Pip,
}

/** 折叠姿势。名字描述折痕方向：水平折痕 = 上下两半（帐篷/桌面模式）。 */
enum class PlayerFoldPosture {
    None,
    Flat,
    HalfOpenedHorizontal,
    HalfOpenedVertical,
}

/**
 * 该骨架是否有常驻内容（侧栏 / 竖屏下方内容区 / 小窗控制条）。
 *
 * 有常驻内容的骨架即使用户把控制层淡出，整层 Compose 也必须保持可见， 否则内容栏会一起消失，同时失去点击能力。
 */
fun PlayerChromeLayout.keepsComposition(): Boolean =
    this == PlayerChromeLayout.SplitSide ||
        this == PlayerChromeLayout.SplitPortrait ||
        this == PlayerChromeLayout.FoldHalfOpen ||
        this == PlayerChromeLayout.Compact

/**
 * 播放页布局上下文。
 *
 * [windowWidthDp] / [windowHeightDp] 是**当前窗口**尺寸（分屏会变小），[foldTopDp] 是折痕上边缘， 三者都用 dp，Activity 与
 * Compose 共用同一份数值换算像素，避免两边各自取整造成错位。
 */
@Immutable
data class PlayerLayoutContext(
    val formFactor: PlayerFormFactor = PlayerFormFactor.Phone,
    val chrome: PlayerChromeLayout = PlayerChromeLayout.Fullscreen,
    val isLandscape: Boolean = true,
    val windowWidthDp: Int = 0,
    val windowHeightDp: Int = 0,
    val sidePanelWidthDp: Int = SIDE_PANEL_WIDTH_DP,
    /** 折叠半开：画面区高度 = 折痕上边缘 */
    val foldTopDp: Int? = null,
    /**
     * W67b：竖屏「画面下方的常驻内容区」（选集 / 队列）是否展开。
     *
     * 收起（内容区右上角 ×）后画面区铺满整窗、内容区不占位；顶栏「选集」键可再次展开。平板 / 折叠等其他骨架恒为 true（无此状态）。
     */
    val bottomContentExpanded: Boolean = true,
) {
    /** 画面区宽度：有侧栏时让出侧栏 */
    val videoWidthDp: Int
        get() =
            if (chrome == PlayerChromeLayout.SplitSide) {
                (windowWidthDp - sidePanelWidthDp).coerceAtLeast(0)
            } else {
                windowWidthDp
            }

    /** 画面区高度：竖屏 16:9、半开取折痕上边缘，其余铺满 */
    val videoHeightDp: Int
        get() =
            when (chrome) {
                // 竖屏画面区：至少 42% 窗口高，保证「顶栏 + 中央播放键 + 进度条」三件套放得下；
                // 视频本体仍是 16:9，多出来的高度用黑边/氛围底色承接。
                // W67b：内容区被 × 收起后画面区铺满整窗（视频区恢复）。
                PlayerChromeLayout.SplitPortrait ->
                    if (bottomContentExpanded) {
                        maxOf(windowWidthDp * 9 / 16, windowHeightDp * 42 / 100)
                    } else {
                        windowHeightDp
                    }
                PlayerChromeLayout.FoldHalfOpen -> foldTopDp ?: (windowHeightDp * 56 / 100)
                else -> windowHeightDp
            }

    val hasSideContent: Boolean
        get() = chrome == PlayerChromeLayout.SplitSide

    val hasBottomContent: Boolean
        get() = chrome == PlayerChromeLayout.SplitPortrait && bottomContentExpanded

    val isCompact: Boolean
        get() = chrome == PlayerChromeLayout.Compact

    companion object {
        /** 侧栏宽度：320dp 是「选集卡片 + 集号 + 时长」不换行的下限 */
        const val SIDE_PANEL_WIDTH_DP = 320
    }
}

/** 纯函数判定，方便单测与日志排查。顺序即优先级： PiP > TV > 车机 > 小窗 > 折叠半开 > 折叠展开 / 平板 > 手机。 */
fun detectPlayerLayout(
    context: Context,
    configuration: Configuration,
    foldPosture: PlayerFoldPosture,
    isInMultiWindowMode: Boolean,
    isInPip: Boolean,
    windowWidthDp: Int,
    windowHeightDp: Int,
    fullWidthDp: Int,
): PlayerLayoutContext {
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val base =
        PlayerLayoutContext(
            isLandscape = isLandscape,
            windowWidthDp = windowWidthDp,
            windowHeightDp = windowHeightDp,
        )

    if (isInPip) {
        return base.copy(formFactor = PlayerFormFactor.Phone, chrome = PlayerChromeLayout.Pip)
    }
    if (isTelevision(context)) {
        return base.copy(formFactor = PlayerFormFactor.Tv, chrome = PlayerChromeLayout.Fullscreen)
    }
    if (isAutomotive(context)) {
        return base.copy(formFactor = PlayerFormFactor.Car, chrome = PlayerChromeLayout.Fullscreen)
    }
    if (isInMultiWindowMode && fullWidthDp > 0 && windowWidthDp < fullWidthDp * 3 / 4) {
        return base.copy(
            formFactor = PlayerFormFactor.Freeform,
            chrome = PlayerChromeLayout.Compact,
        )
    }

    val isFolding = foldPosture != PlayerFoldPosture.None
    when (foldPosture) {
        // 水平折痕（桌面模式）：上屏画面、下屏控制
        PlayerFoldPosture.HalfOpenedHorizontal ->
            return base.copy(
                formFactor = PlayerFormFactor.Foldable,
                chrome = PlayerChromeLayout.FoldHalfOpen,
            )
        // 垂直折痕（书本模式）：左右并排，宽度够就复用侧栏骨架
        PlayerFoldPosture.HalfOpenedVertical ->
            return base.copy(
                formFactor = PlayerFormFactor.Foldable,
                chrome =
                    if (windowWidthDp >= 600) PlayerChromeLayout.SplitSide
                    else PlayerChromeLayout.FoldHalfOpen,
            )
        else -> Unit
    }

    val wideEnoughForSidePanel = windowWidthDp >= 840 || (isFolding && windowWidthDp >= 600)
    if (isLandscape && wideEnoughForSidePanel) {
        return base.copy(
            formFactor = if (isFolding) PlayerFormFactor.Foldable else PlayerFormFactor.Tablet,
            chrome = PlayerChromeLayout.SplitSide,
        )
    }
    if (!isLandscape && windowWidthDp < 600) {
        return base.copy(
            formFactor = PlayerFormFactor.Phone,
            chrome = PlayerChromeLayout.SplitPortrait,
        )
    }
    return base.copy(
        formFactor =
            when {
                isFolding -> PlayerFormFactor.Foldable
                windowWidthDp >= 600 -> PlayerFormFactor.Tablet
                else -> PlayerFormFactor.Phone
            },
        chrome = PlayerChromeLayout.Fullscreen,
    )
}

private fun isTelevision(context: Context): Boolean {
    val uiModeManager =
        context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) return true
    return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

private fun isAutomotive(context: Context): Boolean {
    val uiModeManager =
        context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
    if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_CAR) return true
    // FEATURE_AUTOMOTIVE 是 API 30 常量，这里用字符串判定，避免 minSdk 28 上做版本分支
    return context.packageManager.hasSystemFeature("android.hardware.type.automotive")
}

/** 从 Compose 的 LocalContext 里找到宿主 Activity；没有就返回 null（预览 / 非 Activity 宿主） */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * 在播放页 Compose 层读取当前形态。折叠状态来自 [WindowInfoTracker]，窗口尺寸用 [WindowMetricsCalculator] 取「当前窗口 /
 * 最大窗口」，两者相除即可识别自由窗口。
 */
@Composable
fun rememberPlayerLayoutContext(isPip: Boolean): PlayerLayoutContext {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val activity = context.findActivity()

    val foldSnapshot by
        produceState(FoldSnapshot(), activity) {
            val host = activity ?: return@produceState
            try {
                WindowInfoTracker.getOrCreate(host).windowLayoutInfo(host).collect { info ->
                    val feature =
                        info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                    value =
                        if (feature == null) {
                            FoldSnapshot()
                        } else {
                            FoldSnapshot(
                                posture = feature.toFoldPosture(),
                                topPx = feature.bounds.top,
                            )
                        }
                }
            } catch (e: Exception) {
                Timber.w(e, "读取折叠状态失败，按普通窗口处理")
            }
        }
    val foldPosture = foldSnapshot.posture

    val metrics =
        remember(activity, configuration) {
            if (activity == null) {
                null
            } else {
                runCatching {
                    val calculator = WindowMetricsCalculator.getOrCreate()
                    calculator.computeCurrentWindowMetrics(activity) to
                        calculator.computeMaximumWindowMetrics(activity)
                }
                    .getOrNull()
            }
        }

    val windowWidthDp =
        metrics?.first?.bounds?.width()?.toDp(density) ?: configuration.screenWidthDp
    val windowHeightDp =
        metrics?.first?.bounds?.height()?.toDp(density) ?: configuration.screenHeightDp
    val fullWidthDp = metrics?.second?.bounds?.width()?.toDp(density) ?: windowWidthDp
    val foldTopDp =
        if (foldPosture == PlayerFoldPosture.HalfOpenedHorizontal) {
            // 折痕上边缘就是画面区高度；拿不到 bounds 时退回窗口一半
            foldSnapshot.topPx.takeIf { it > 0 }?.toDp(density) ?: (windowHeightDp / 2)
        } else {
            null
        }

    return detectPlayerLayout(
            context = context,
            configuration = configuration,
            foldPosture = foldPosture,
            isInMultiWindowMode = activity?.isInMultiWindowMode == true,
            isInPip = isPip,
            windowWidthDp = windowWidthDp,
            windowHeightDp = windowHeightDp,
            fullWidthDp = fullWidthDp,
        )
        .copy(foldTopDp = foldTopDp)
}

private fun Int.toDp(density: androidx.compose.ui.unit.Density): Int =
    with(density) { this@toDp.toFloat().toDp().value.toInt() }

private data class FoldSnapshot(
    val posture: PlayerFoldPosture = PlayerFoldPosture.None,
    val topPx: Int = 0,
)

private fun FoldingFeature.toFoldPosture(): PlayerFoldPosture =
    when (state) {
        FoldingFeature.State.FLAT -> PlayerFoldPosture.Flat
        FoldingFeature.State.HALF_OPENED ->
            when (orientation) {
                FoldingFeature.Orientation.HORIZONTAL -> PlayerFoldPosture.HalfOpenedHorizontal
                FoldingFeature.Orientation.VERTICAL -> PlayerFoldPosture.HalfOpenedVertical
                else -> PlayerFoldPosture.Flat
            }
        else -> PlayerFoldPosture.None
    }
