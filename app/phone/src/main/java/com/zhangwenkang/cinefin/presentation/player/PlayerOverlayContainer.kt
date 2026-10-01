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
 * 交互区域按**画面区**（而不是整个控件）划带：顶部 76dp / 中央 520×180dp / 底部 210dp； 竖屏（SplitPortrait）里画面更矮、底栏更高（图标 +
 * 文字的工具行），单独用 72 / 150dp 两条带宽。 画面区之外的常驻内容区（平板右侧栏、手机竖屏下方选集、折叠半开下屏）整块接管， 这样 Compose
 * 内容不会被手势层穿透，画面区里的手势却一点不受影响。
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

    /** 画面区尺寸（px）。竖屏 16:9、侧栏模式让出右侧栏；0 表示退化为整块控件 */
    var videoWidthPx: Float = 0f
    var videoHeightPx: Float = 0f

    /** 小窗单行控制条高度（px） */
    var compactBarHeightPx: Float = 0f

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

        /*
         * 画面区之外的常驻内容区（平板侧栏 / 竖屏下方选集 / 折叠半开下屏）永远接管触摸：
         * 它不随控制层淡出，所以判断要放在 controlsVisible 之前。
         */
        val hasContentRegion =
            chrome == PlayerChromeLayout.SplitSide ||
                chrome == PlayerChromeLayout.SplitPortrait ||
                chrome == PlayerChromeLayout.FoldHalfOpen
        if (hasContentRegion && (x > videoWidth || y > videoHeight)) return true

        if (!controlsVisible) return false

        if (chrome == PlayerChromeLayout.Compact) {
            val barHeight = compactBarHeightPx.takeIf { it > 0f } ?: 56f * density
            return y >= height - barHeight
        }

        if (x > videoWidth || y > videoHeight) return false

        // 竖屏画面区更矮，命中带跟着收窄，否则整块画面区都被控件吃掉、手势无处可落
        val portrait = chrome == PlayerChromeLayout.SplitPortrait
        // 竖屏顶带 = 顶栏实际高度（8 + 48 + 8dp），给「点画面显隐控制层」留出可落的手指区
        val topBand = RectF(0f, 0f, videoWidth, (if (portrait) 64f else 76f) * density)
        val centerHalfWidth = if (errorVisible) 300f else if (portrait) 200f else 260f
        // 竖屏中央带只包住传输簇（主键 70dp），比横屏更紧，避免吃掉剩下的手势区
        val centerHalfHeight = if (errorVisible) 200f else if (portrait) 56f else 90f
        val centerBand =
            RectF(
                videoWidth / 2f - centerHalfWidth * density,
                videoHeight / 2f - centerHalfHeight * density,
                videoWidth / 2f + centerHalfWidth * density,
                videoHeight / 2f + centerHalfHeight * density,
            )
        // 底栏 = 进度条 + 时间行 + 图标文案工具行（≥52dp），竖屏命中带同步加高
        val bottomBandHeight = (if (portrait) 150f else 210f) * density
        val bottomBand = RectF(0f, videoHeight - bottomBandHeight, videoWidth, videoHeight)
        return topBand.contains(x, y) || centerBand.contains(x, y) || bottomBand.contains(x, y)
    }
}
