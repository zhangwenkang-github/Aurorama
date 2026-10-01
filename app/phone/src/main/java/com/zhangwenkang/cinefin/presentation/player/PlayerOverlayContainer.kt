package com.zhangwenkang.cinefin.presentation.player

import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * 播放控制层的触摸容器。
 *
 * 关键点：**只有落在控件区域内的触摸才交给内部的 ComposeView，其余一律返回 false 放行给下层的 PlayerView**。
 * 这样单击显隐、双击快进/快退、左右滑动进度、上下滑动亮度音量、双指缩放这些既有手势 依然完全由
 * [com.zhangwenkang.cinefin.utils.PlayerGestureHelper] 处理， 不会因为控制层换成 Compose 就把手势全吞掉。
 *
 * 交互区域按**画面区**（而不是整个控件）划带：顶带 = 顶栏（返回 / 标题 / 右上角工具簇 / 锁定）实高、 底带 = 左下角传输行 + 通栏进度条实高——两处都由控制层
 * `onSizeChanged` 回传，不再写死 dp； 中央只有错误卡片才接管触摸（传输键已挪到左下角，中央整块留给手势）。
 * 画面区之外的常驻内容区（平板右侧栏、手机竖屏下方选集、折叠半开下屏）整块接管，这样 Compose 内容不会被手势层穿透，画面区里的手势却一点不受影响。
 *
 * 错误卡片比中央控件高一截，出现时中央命中区放大到 600×400dp，否则重试按钮点不到。
 */
class PlayerOverlayContainer
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 控制层是否可见；不可见时整层完全不接管触摸 */
    var controlsVisible: Boolean = true

    /** 面板是否打开；打开时全屏接管，避免手势穿透到播放器 */
    var panelOpen: Boolean = false

    /** 锁屏态：只保留解锁按钮那一小块可点 */
    var locked: Boolean = false

    /** 错误卡片是否可见：决定中央命中区用普控件尺寸还是放大尺寸 */
    var errorVisible: Boolean = false

    /** 当前骨架：决定命中区形状。由 Activity 与控制层同源写入 */
    var chrome: PlayerChromeLayout = PlayerChromeLayout.Fullscreen

    /** 画面区尺寸（px）。竖屏 16:9；侧栏模式也是整窗宽（W12 起选集栏是覆盖层，不再挤压画面） */
    var videoWidthPx: Float = 0f
    var videoHeightPx: Float = 0f

    /**
     * 覆盖层选集栏（W12 反馈 C）：打开时右缘 [sidePanelWidthPx] 宽的一条由控制层接管触摸。
     *
     * 它不改变画面区布局（播放器仍是整窗宽），所以命中区必须单独补这一条；关闭时完全放行。
     */
    var sidePanelOpen: Boolean = false
    var sidePanelWidthPx: Float = 0f

    /** 小窗单行控制条高度（px） */
    var compactBarHeightPx: Float = 0f

    /**
     * 顶栏 / 底栏实测高度（px）：由控制层测量后回传（`onSizeChanged`）。
     *
     * 顶栏要装下「返回 + 标题 + 右上角工具簇 + 锁定」，底栏是「左下角传输行 + 通栏进度条」， 两处高度都会随形态与窗口宽度变化——命中带必须跟实高走，否则会出现「进度条点不到」
     * 或「点画面显隐控制层的可落区被吃掉」（§9 踩坑）。
     */
    var topBarHeightPx: Float = 0f
    var bottomBarHeightPx: Float = 0f

    /** 中央播放簇的实测尺寸（px）：控制层回传，命中块跟它同源（窄窗收尺寸时不会多吃掉画面手势）。 */
    var centerClusterWidthPx: Float = 0f
    var centerClusterHeightPx: Float = 0f

    private var handlingSequence = false
    private val density = resources.displayMetrics.density

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            handlingSequence = shouldHandle(ev.x, ev.y)
        }
        val handled = handlingSequence
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            handlingSequence = false
        }
        return handled && super.dispatchTouchEvent(ev)
    }

    private fun shouldHandle(x: Float, y: Float): Boolean {
        if (panelOpen) return true
        if (chrome == PlayerChromeLayout.Pip) return false

        if (locked) {
            return RectF(
                    width - 140f * density,
                    height / 2f - 80f * density,
                    width.toFloat(),
                    height / 2f + 80f * density,
                )
                .contains(x, y)
        }

        val videoWidth = videoWidthPx.takeIf { it > 0f } ?: width.toFloat()
        val videoHeight = videoHeightPx.takeIf { it > 0f } ?: height.toFloat()

        // 覆盖层选集栏：只在它自己的那一条里接管触摸，画面区其余部分照旧留给手势
        if (sidePanelOpen && sidePanelWidthPx > 0f) {
            if (x >= videoWidth - sidePanelWidthPx) return true
        }

        /*
         * 画面区之外的常驻内容区（平板侧栏 / 竖屏下方选集 / 折叠半开下屏）永远接管触摸：
         * 它不随控制层淡出，所以判断要放在 controlsVisible 之前。
         */
        val hasContentRegion =
            chrome == PlayerChromeLayout.SplitPortrait ||
                chrome == PlayerChromeLayout.FoldHalfOpen
        if (hasContentRegion && (x > videoWidth || y > videoHeight)) return true

        if (!controlsVisible) return false

        if (chrome == PlayerChromeLayout.Compact) {
            val barHeight = compactBarHeightPx.takeIf { it > 0f } ?: 56f * density
            return y >= height - barHeight
        }

        if (x > videoWidth || y > videoHeight) return false

        // 竖屏画面区更矮，命中带跟着收窄，否则整块画面区都被控件吃掉、手势无处可落；
        // 有实测高度时用实测（顶栏 / 底栏随形态变高变矮），没有则按骨架取保守值
        val portrait = chrome == PlayerChromeLayout.SplitPortrait
        val topHeight =
            topBarHeightPx.takeIf { it > 0f } ?: ((if (portrait) 64f else 76f) * density)
        val topBand = RectF(0f, 0f, videoWidth, topHeight)
        /*
         * 中央 = 播放簇（W11 反馈③：上一个 · 快退 · 播放 · 快进 · 下一个 五键居中）。命中块跟控件实测尺寸同源
         * （窄窗会收尺寸，见 playerCenterSpec），画面其余部分照旧交给手势；
         * 错误卡片出现时放大到 600×400dp，否则「重试」点不到。
         */
        val centerHalfWidth =
            if (errorVisible) {
                300f
            } else {
                // 宽度按实测的一半再放 8dp 余量（手指落点不需要像素级精确）
                ((centerClusterWidthPx / 2f) / density + 8f).coerceAtLeast(60f)
            }
        val centerHalfHeight = if (errorVisible) 200f else 40f
        val centerBand =
            RectF(
                videoWidth / 2f - centerHalfWidth * density,
                videoHeight / 2f - centerHalfHeight * density,
                videoWidth / 2f + centerHalfWidth * density,
                videoHeight / 2f + centerHalfHeight * density,
            )
        // 锁定键（W11 反馈②）：右缘垂直居中的一个小方块，键之外的右缘仍然放行给手势
        val lockBand =
            RectF(
                videoWidth - 76f * density,
                videoHeight / 2f - 36f * density,
                videoWidth,
                videoHeight / 2f + 36f * density,
            )
        // 底栏 = 左下角传输行 + 通栏进度条；有实测高度就按实测
        val bottomBandHeight =
            bottomBarHeightPx.takeIf { it > 0f } ?: ((if (portrait) 130f else 150f) * density)
        val bottomBand = RectF(0f, videoHeight - bottomBandHeight, videoWidth, videoHeight)
        return topBand.contains(x, y) ||
            centerBand.contains(x, y) ||
            lockBand.contains(x, y) ||
            bottomBand.contains(x, y)
    }
}
