package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize

/**
 * 同一本书在同一 App 进程内的页面倍率（ADR-0002）。
 *
 * 只活在内存里：退出阅读页再打开同一本书仍生效；切书 / 冷启动回到 1×，不写任何偏好键。 map 规模 = 本次进程打开过的书数，阅读器一次只开一本书，无需淘汰。
 */
internal object BookZoomSession {
    private val scales = mutableMapOf<String, Float>()

    fun scaleFor(bookKey: String): Float =
        scales[bookKey]?.takeIf { it.isFinite() }?.coerceIn(1f, PAGE_MAX_ZOOM) ?: 1f

    fun remember(
        bookKey: String,
        scale: Float,
    ) {
        if (!scale.isFinite() || scale <= 1f) {
            scales.remove(bookKey)
        } else {
            scales[bookKey] = scale.coerceIn(1f, PAGE_MAX_ZOOM)
        }
    }
}

/**
 * 文档级缩放状态：倍率跨页 / 跨 spread / 跨三种模式共享，平移仍按页槽（[slotKey]）各自保留。
 *
 * 分页、双栏、滚动三档都读同一份 [scale]；翻到没访问过的页时该槽平移为 0（重新居中）， 回访已平移过的页时保留自己的平移。倍率变化按当前槽的视口焦点重算平移；`snapTo`
 * 只清当前槽，其余槽按各自中心重算。
 */
@Stable
internal class BookZoomState(private val bookKey: String) {
    var scale by mutableFloatStateOf(BookZoomSession.scaleFor(bookKey))
        private set

    private val offsets = mutableStateMapOf<Any, Offset>()
    private val sizes = mutableStateMapOf<Any, IntSize>()
    private var activeSlotKey: Any? = null
    private var activeViewportFocus = Offset.Zero

    val percent: Int
        get() = zoomPercent(scale)

    val canZoomIn: Boolean
        get() = scale < PAGE_MAX_ZOOM - ZOOM_EPSILON

    val canZoomOut: Boolean
        get() = scale > 1f + ZOOM_EPSILON

    fun offsetFor(slotKey: Any): Offset =
        if (scale <= 1f) Offset.Zero else offsets[slotKey] ?: Offset.Zero

    /**
     * 当前页槽相对视口中心的焦点（页槽局部坐标）。
     *
     * 按钮缩放只用这份焦点；双栏当前页位于左 / 右半屏、滚动页只部分可见时，焦点都不是 `Offset.Zero`。
     */
    fun updateViewportFocus(
        slotKey: Any,
        focus: Offset,
    ) {
        activeSlotKey = slotKey
        activeViewportFocus = focus.takeIf { it.isFinite() } ?: Offset.Zero
    }

    /** 页槽布局尺寸（手势平移夹取用）；尺寸变化时顺手把已有平移夹回新范围。 */
    fun registerSize(
        slotKey: Any,
        size: IntSize,
    ) {
        if (size.width <= 0 || size.height <= 0) return
        if (sizes[slotKey] == size) return
        sizes[slotKey] = size
        val offset = offsets[slotKey] ?: return
        val clamped = clampPageOffset(offset, scale, size.width, size.height)
        if (clamped != offset) offsets[slotKey] = clamped
    }

    /** 按钮 +：命中下一个 25% 档位，以当前页槽上报的视口中心为锚点。 */
    fun zoomIn() {
        stepTo(nextPageZoomStep(scale))
    }

    /** 按钮 −：命中上一个 25% 档位，以当前页槽上报的视口中心为锚点。 */
    fun zoomOut() {
        stepTo(previousPageZoomStep(scale))
    }

    /** 双击「适宽」直接落到目标倍率，并只清当前页槽的平移（不经过档位）。 */
    fun snapTo(
        value: Float,
        slotKey: Any,
    ) {
        if (!value.isFinite()) return
        val next = value.coerceIn(1f, PAGE_MAX_ZOOM)
        offsets.remove(slotKey)
        commitScale(next)
    }

    /**
     * 双指缩放：围绕手指焦点（相对视口中心）缩放并叠加双指平移。
     *
     * [zoomFactor] 与 [pan] 来自同一帧事件；倍率夹取到 1×–4×，平移按放大后的边缘夹取。
     */
    fun transform(
        slotKey: Any,
        zoomFactor: Float,
        pan: Offset,
        focus: Offset,
    ) {
        if (!zoomFactor.isFinite() || zoomFactor <= 0f) return
        val next = (scale * zoomFactor).coerceIn(1f, PAGE_MAX_ZOOM)
        if (next <= 1f) {
            offsets.clear()
            commitScale(1f)
            return
        }
        val safePan = if (pan.x.isFinite() && pan.y.isFinite()) pan else Offset.Zero
        val safeFocus = if (focus.isFinite()) focus else Offset.Zero
        val anchored = anchorPageOffset(offsetFor(slotKey), scale, next, safeFocus) + safePan
        val size = sizes[slotKey]
        offsets[slotKey] =
            if (size != null) clampPageOffset(anchored, next, size.width, size.height) else anchored
        commitScale(next)
    }

    /** 滚动模式单指横向平移（纵向留给 `LazyColumn`）；缩放回 1× 时自动归零。 */
    fun panBy(
        slotKey: Any,
        delta: Offset,
    ) {
        if (scale <= 1f) return
        if (!delta.x.isFinite() || !delta.y.isFinite()) return
        val size = sizes[slotKey] ?: return
        offsets[slotKey] =
            clampPageOffset(
                offsetFor(slotKey) + delta,
                scale,
                size.width,
                size.height,
            )
    }

    private fun stepTo(target: Float) {
        if (!target.isFinite()) return
        val next = target.coerceIn(1f, PAGE_MAX_ZOOM)
        if (next <= 1f) {
            offsets.clear()
            commitScale(1f)
            return
        }
        if (next == scale) return
        val currentSlot = activeSlotKey
        val currentFocus = activeViewportFocus.takeIf { it.isFinite() } ?: Offset.Zero
        val keys = offsets.keys.toMutableSet().apply { currentSlot?.let(::add) }
        keys.forEach { key ->
            val focus = if (key == currentSlot) currentFocus else Offset.Zero
            val anchored = anchorPageOffset(offsetFor(key), scale, next, focus)
            val size = sizes[key]
            offsets[key] =
                if (size != null) {
                    clampPageOffset(anchored, next, size.width, size.height)
                } else {
                    anchored
                }
        }
        commitScale(next)
    }

    private fun commitScale(value: Float) {
        if (!value.isFinite()) return
        scale = value
        BookZoomSession.remember(bookKey, value)
    }

    private companion object {
        const val ZOOM_EPSILON = 1e-4f
    }
}

private fun Offset.isFinite(): Boolean = x.isFinite() && y.isFinite()
