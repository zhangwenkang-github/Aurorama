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
 * 这样单击显隐、双击快进/快退、左右滑动进度、上下滑动亮度音量、双指缩放这些既有手势
 * 依然完全由 [com.zhangwenkang.cinefin.utils.PlayerGestureHelper] 处理，
 * 不会因为控制层换成 Compose 就把手势全吞掉。
 *
 * 交互区域用固定带宽判断（顶部 76dp / 中央 520×180dp / 底部 210dp）：
 * 比让每个控件上报坐标更稳定，也不会因为动画中间帧漏判。
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

        if (locked) {
            return RectF(
                    width - 140f * density,
                    height / 2f - 80f * density,
                    width.toFloat(),
                    height / 2f + 80f * density,
                )
                .contains(x, y)
        }

        if (!controlsVisible) return false

        val topBand = RectF(0f, 0f, width.toFloat(), 76f * density)
        val centerBand =
            RectF(
                width / 2f - 260f * density,
                height / 2f - 90f * density,
                width / 2f + 260f * density,
                height / 2f + 90f * density,
            )
        val bottomBand = RectF(0f, height - 210f * density, width.toFloat(), height.toFloat())
        return topBand.contains(x, y) || centerBand.contains(x, y) || bottomBand.contains(x, y)
    }
}
