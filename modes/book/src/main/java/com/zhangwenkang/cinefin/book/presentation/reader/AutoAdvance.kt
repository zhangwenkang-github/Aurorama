package com.zhangwenkang.cinefin.book.presentation.reader

/**
 * 自动阅读节拍器（W6 T6 / READER-12）：分页档按间隔翻页、滚动档按速度逐帧给像素。
 *
 * 纯 Kotlin、无 Compose / Android 依赖：时钟由调用方注入（生产 = `SystemClock.elapsedRealtime`，单测 = 虚拟时钟），
 * 执行层（Readium 导航器 / Pager / LazyColumn / WebView）只消费 [AutoAdvanceTick]。
 */

/** 分页档自动翻页间隔（秒）：5–120，默认 30。 */
internal const val AutoAdvanceMinIntervalSeconds = 5
internal const val AutoAdvanceMaxIntervalSeconds = 120
internal const val AutoAdvanceDefaultIntervalSeconds = 30

/** 滚动档速度（px/s）：10–300，默认 60（像素级平滑滚动，非固定跳页）。 */
internal const val AutoAdvanceMinSpeedPxPerSecond = 10f
internal const val AutoAdvanceMaxSpeedPxPerSecond = 300f
internal const val AutoAdvanceDefaultSpeedPxPerSecond = 60f

/** 执行层帧节拍：滚动档逐帧（约 60fps），分页档 / 空闲态轮询更慢即可。 */
internal const val AutoAdvanceScrollFrameMs = 16L
internal const val AutoAdvancePagedTickMs = 250L

/** EPUB 滚动档的 JS 派发节拍（约 30fps）：`evaluateJavascript` 桥接成本与平滑度的折中（W6 T6r）。 */
internal const val AutoAdvanceEpubScrollFrameMs = 33L
internal const val AutoAdvanceIdlePollMs = 250L

/** 自动阅读运行态：关闭 / 运行 / 暂停（暂停可恢复，关闭不可）。 */
enum class AutoAdvanceState {
    Off,
    Running,
    Paused,
}

/** 自动阅读参数；越界 / 非有限值在 [sanitized] 收进可用区间。 */
data class AutoAdvanceConfig(
    val pageIntervalSeconds: Int = AutoAdvanceDefaultIntervalSeconds,
    val scrollSpeedPxPerSecond: Float = AutoAdvanceDefaultSpeedPxPerSecond,
) {
    val pageIntervalMs: Long
        get() = pageIntervalSeconds * 1_000L

    fun sanitized(): AutoAdvanceConfig =
        copy(
            pageIntervalSeconds =
                pageIntervalSeconds.coerceIn(
                    AutoAdvanceMinIntervalSeconds,
                    AutoAdvanceMaxIntervalSeconds,
                ),
            scrollSpeedPxPerSecond =
                if (scrollSpeedPxPerSecond.isFinite()) {
                    scrollSpeedPxPerSecond.coerceIn(
                        AutoAdvanceMinSpeedPxPerSecond,
                        AutoAdvanceMaxSpeedPxPerSecond,
                    )
                } else {
                    AutoAdvanceDefaultSpeedPxPerSecond
                },
        )
}

/** 一次节拍的结果：空闲 / 前进一页 / 滚动若干整像素（正 = 向下）。 */
sealed interface AutoAdvanceTick {
    data object Idle : AutoAdvanceTick

    data object PageForward : AutoAdvanceTick

    data class Scroll(val deltaPx: Int) : AutoAdvanceTick
}

/**
 * 节拍器状态机。
 *
 * - 分页档 [tick]（`paged = true`）按 [AutoAdvanceConfig.pageIntervalMs] 累计时间，到点返回一次
 *   [AutoAdvanceTick.PageForward]；
 * - 滚动档 [tick]（`paged = false`）按 `speed × Δt` 累计像素，保留小数余量，返回整像素 [AutoAdvanceTick.Scroll]；
 * - [updateConfig] 实时生效且不重置累计进度（改速度 / 间隔不打断当前节拍）；
 * - 暂停 / 恢复都会丢弃暂停期间的时间与像素余量，恢复后从 0 重新累计；
 * - 到书末由执行层调用 [stop]（两档同语义：回到 [AutoAdvanceState.Off]）。
 */
class AutoAdvanceEngine(
    private val clock: () -> Long,
    private val onStateChanged: (AutoAdvanceState) -> Unit = {},
) {
    var state: AutoAdvanceState = AutoAdvanceState.Off
        private set

    var config: AutoAdvanceConfig = AutoAdvanceConfig()
        private set

    /** 最近一次自动翻页的时刻：执行层用它区分「位置变化是自动翻页还是用户手动翻页」。 */
    var lastPageForwardAtMs: Long? = null
        private set

    private var lastTickMs: Long? = null
    private var pageElapsedMs = 0L
    private var scrollResidualPx = 0f

    /** 开启（关闭 / 暂停 → 运行）；已在运行时无副作用。 */
    fun start() {
        if (state == AutoAdvanceState.Running) return
        lastPageForwardAtMs = null
        transition(AutoAdvanceState.Running)
    }

    /** 暂停（仅运行态有效）：控制层唤出 / 手动干预调用。 */
    fun pause() {
        if (state == AutoAdvanceState.Running) transition(AutoAdvanceState.Paused)
    }

    /** 手动干预暂停：语义化别名，非运行态时保持原状（不会把「关闭」变成「暂停」）。 */
    fun pauseForManualIntervention() = pause()

    /** 从暂停继续（仅暂停态有效）；不会从关闭态直接开始。 */
    fun resume() {
        if (state == AutoAdvanceState.Paused) transition(AutoAdvanceState.Running)
    }

    /** 停止（任意态 → 关闭）。到书末 / 退出阅读页也走这里。 */
    fun stop() {
        if (state != AutoAdvanceState.Off) transition(AutoAdvanceState.Off)
    }

    /** 实时更新参数（间隔 / 速度），不打断正在累计的节拍。 */
    fun updateConfig(next: AutoAdvanceConfig) {
        config = next.sanitized()
    }

    /**
     * 推进一次节拍。
     *
     * 执行层每帧 / 每个轮询周期调用一次；本方法只按注入时钟计算，与调用频率无关（分页档至少间隔 [AutoAdvanceConfig.pageIntervalMs]
     * 才会返回一页，滚动档按真实经过时间折算像素）。
     */
    fun tick(paged: Boolean): AutoAdvanceTick {
        val now = clock()
        if (state != AutoAdvanceState.Running) {
            // 非运行态不累计：恢复后不从暂停期间补课。
            lastTickMs = null
            pageElapsedMs = 0L
            scrollResidualPx = 0f
            return AutoAdvanceTick.Idle
        }
        val previous = lastTickMs
        lastTickMs = now
        val elapsedMs = if (previous == null) 0L else (now - previous).coerceAtLeast(0L)
        return if (paged) {
            scrollResidualPx = 0f
            pageElapsedMs += elapsedMs
            val interval = config.pageIntervalMs
            if (interval > 0L && pageElapsedMs >= interval) {
                pageElapsedMs = 0L
                lastPageForwardAtMs = now
                AutoAdvanceTick.PageForward
            } else {
                AutoAdvanceTick.Idle
            }
        } else {
            pageElapsedMs = 0L
            scrollResidualPx += config.scrollSpeedPxPerSecond * elapsedMs / 1_000f
            val wholePx = scrollResidualPx.toInt()
            scrollResidualPx -= wholePx
            if (wholePx > 0) AutoAdvanceTick.Scroll(wholePx) else AutoAdvanceTick.Idle
        }
    }

    private fun transition(next: AutoAdvanceState) {
        if (state == next) return
        state = next
        lastTickMs = null
        pageElapsedMs = 0L
        scrollResidualPx = 0f
        onStateChanged(next)
    }
}

/** 滚动档一帧的判定结果（纯逻辑；视图只负责执行 goForward / 停止）。 */
enum class AutoScrollOutcome {
    Continue,
    ChapterEnd,
    BookEnd,
    Stalled,
}

/**
 * 滚动档进度追踪（W6 T6r）。
 *
 * Readium 的 EPUB 滚动档是「每章一个 WebView、章节内部滚动」，到章末**不会**自动续接下一资源 （`R2BasicWebView.onOverScrolled` →
 * `onPageEnded` 在 `EpubNavigatorFragment` 里是空实现），所以由本类判定、 调用方执行 `goForward`：
 * - offset 变化 = 有推进（重置无位移计时）；
 * - 资源内进度到顶 / 已不能继续前滚 → [AutoScrollOutcome.ChapterEnd]（末章则 [AutoScrollOutcome.BookEnd]）；
 * - 连续 [stallWindowMs] 无位移 → [AutoScrollOutcome.Stalled]（最后防线，正常路径不应触发）。
 */
class AutoScrollTracker(
    private val stallWindowMs: Long = 3_000L,
    private val chapterEndProgression: Double = 0.995,
    private val bookEndProgression: Double = 0.995,
) {
    private var lastOffset: Double? = null
    private var lastMovementAtMs: Long? = null

    /** 换章 / 重新开始后清空基线：新 WebView 的 offset 与旧值不可比。 */
    fun reset(nowMs: Long) {
        lastOffset = null
        lastMovementAtMs = nowMs
    }

    fun onFrame(
        nowMs: Long,
        offset: Double,
        resourceProgression: Double,
        bookProgression: Double?,
        canScrollForward: Boolean,
    ): AutoScrollOutcome {
        val previous = lastOffset
        lastOffset = offset
        if (previous == null || offset != previous) lastMovementAtMs = nowMs
        val atChapterEnd = resourceProgression >= chapterEndProgression || !canScrollForward
        if (atChapterEnd) {
            return if (bookProgression != null && bookProgression >= bookEndProgression) {
                AutoScrollOutcome.BookEnd
            } else {
                AutoScrollOutcome.ChapterEnd
            }
        }
        val lastMovement = lastMovementAtMs
        if (lastMovement != null && nowMs - lastMovement >= stallWindowMs) {
            return AutoScrollOutcome.Stalled
        }
        return AutoScrollOutcome.Continue
    }
}
