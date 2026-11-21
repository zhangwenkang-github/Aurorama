package com.zhangwenkang.cinefin.book.presentation.reader

import android.view.KeyEvent

/**
 * 阅读页键位动作（W6 T4 / READER-10/44，定稿三 §1）。
 *
 * 只描述「动作」，不依赖具体引擎：EPUB 交给 Readium 导航器，PDF / CBZ 交给页序列视图。
 */
internal enum class ReaderKeyAction {
    PreviousPage,
    NextPage,
    ScrollUp,
    ScrollDown,
    ToggleControls,
}

/** 滚动档一次按键的滚动距离（dp，约一行正文）。 */
internal const val READER_KEY_SCROLL_LINE_DP: Float = 24f

/** 当前正文的按键分发归属（W6 T4r）：决定阅读键是在 view 分发前还是分发后处理。 */
internal enum class ReaderKeyDocument {
    /** EPUB：正文由持焦点的 Readium `R2BasicWebView` 承载，必须在 `Activity.dispatchKeyEvent` 抢先消费。 */
    Epub,

    /** PDF / CBZ：Compose 页，保持「view 树（Compose 焦点）先收键 → `onKeyDown` 兜底」的原有顺序。 */
    Compose,
}

/**
 * 物理键 → 阅读动作（纯函数，null = 不消费、交给系统）。
 *
 * - 音量上下键：仅 [volumeKeysPaging] 打开时消费；关闭时返回 null，让系统继续调音量。
 * - 分页 / 双栏档：←/↑/PgUp = 上一页，→/↓/PgDn = 下一页。
 * - 滚动档：同一组键改为向上 / 向下滚动。
 * - Enter / 小键盘 Enter / 方向键中键 = 控制层开合。
 */
internal fun readerKeyAction(
    keyCode: Int,
    volumeKeysPaging: Boolean,
    scrollMode: Boolean,
): ReaderKeyAction? {
    val backward = if (scrollMode) ReaderKeyAction.ScrollUp else ReaderKeyAction.PreviousPage
    val forward = if (scrollMode) ReaderKeyAction.ScrollDown else ReaderKeyAction.NextPage
    return when (keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> if (volumeKeysPaging) backward else null
        KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeKeysPaging) forward else null
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_PAGE_UP -> backward
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_DOWN -> forward
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_DPAD_CENTER -> ReaderKeyAction.ToggleControls
        else -> null
    }
}

/**
 * view 分发前的抢占判定（W6 T4r，纯函数；null = 不拦截，交给 view 树 / `onKeyDown`）。
 *
 * 背景：`Activity.onKeyDown` 在 `View.dispatchKeyEvent` 之后才被调用。EPUB 正文由持焦点的 Readium `R2BasicWebView`
 * 承载，DPAD / Enter 在 view 分发阶段就被 WebView 消费，永远到不了 `onKeyDown` ——音量键不带焦点语义，所以 T4 真机只暴露了方向键 / Enter
 * 失效。故 EPUB 的阅读键必须在 `Activity.dispatchKeyEvent` 里先于 view 树消费。
 *
 * PDF / CBZ 是 Compose 页，返回 null：保持「Compose 焦点先收键 → 未消化才落到 `onKeyDown`」的原有顺序， 不抢面板 /
 * 滑块的焦点导航。未命中或音量键开关关闭同样返回 null（放行系统音量）。
 */
internal fun readerKeyActionBeforeDispatch(
    document: ReaderKeyDocument,
    keyCode: Int,
    volumeKeysPaging: Boolean,
    scrollMode: Boolean,
): ReaderKeyAction? {
    if (document != ReaderKeyDocument.Epub) return null
    return readerKeyAction(
        keyCode = keyCode,
        volumeKeysPaging = volumeKeysPaging,
        scrollMode = scrollMode,
    )
}
