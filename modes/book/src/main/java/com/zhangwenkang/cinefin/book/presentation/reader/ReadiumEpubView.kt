package com.zhangwenkang.cinefin.book.presentation.reader

import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.zhangwenkang.cinefin.core.presentation.theme.rememberReduceMotionEnabled
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import org.readium.r2.navigator.DecorableNavigator
import org.readium.r2.navigator.Decoration
import org.readium.r2.navigator.Selection
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.extensions.normalizeLocator
import org.readium.r2.navigator.html.HtmlDecorationTemplates
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import timber.log.Timber

private const val NAVIGATOR_TAG = "CinefinEpubNavigator"

/** 选区稳定去抖（spike：selectionchange 约 120ms 去抖）。 */
private const val EPUB_SELECTION_DEBOUNCE_MS = 120L

/** 注入 JS 的等待上限：页面未就绪时不阻塞阅读页。 */
private const val EPUB_SCRIPT_TIMEOUT_MS = 3_000L

/**
 * 用 Compose `AndroidView` 承载 Readium 的 `EpubNavigatorFragment`。
 *
 * Readium 3.4.0 的 Visual Navigator 仍是 Fragment 体系：这里创建 `FragmentContainerView`，由
 * `EpubNavigatorFactory` 生成 FragmentFactory 后手动添加。
 *
 * W6-T8 在此接入划词高亮（READER-23–29）：
 * - `selectionActionModeCallback` 顶掉系统选择菜单，动作交给 Compose 浮动工具栏；
 * - `Decoration`（Highlight，alpha 0.28 / 圆角 2dp）渲染已存高亮，点击走 `addDecorationListener`；
 * - 选区精度：`currentSelection()` 的 locator（含 text、页级 locations）+ 注入 JS 补算 `cssSelector`（放
 *   otherLocations）。
 */
@OptIn(ExperimentalReadiumApi::class)
@Composable
internal fun ReadiumEpubView(
    publication: Publication,
    initialLocator: Locator?,
    settings: ReaderSettings,
    systemDark: Boolean,
    jumpTarget: Locator?,
    onLocationChanged: (Locator) -> Unit,
    onJumpHandled: () -> Unit,
    onNavigatorReady: (Locator) -> Unit,
    /** W80（#32）：当前屏幕亮度（0–1），上下滑手势从这里起算。 */
    brightnessProvider: () -> Float = { 1f },
    onBrightness: (Float) -> Unit = {},
    /** W6 T1：三分区单击策略（控制层可见性 / 反转左右 / 点按翻页开关）。 */
    tapPolicy: ReaderTapPolicy = ReaderTapPolicy(),
    onToggleControls: () -> Unit = {},
    /** W6 T1：双击正文 = 书签动作（T2 接线，本卡只留回调）。 */
    onDoubleTapBookmark: () -> Unit = {},
    /** W6 T8：当前书的 EPUB 标注（整组替换下发 Decoration）。 */
    epubAnnotations: List<BookAnnotation> = emptyList(),
    /** W6 T8：选区快照（null = 选区被取消）。 */
    onSelectionChanged: (EpubSelectionSnapshot?) -> Unit = {},
    /** W6 T8：点击已有高亮（annotationId + 高亮矩形，导航器视图坐标）。 */
    onAnnotationActivated:
        (
            annotationId: String,
            leftPx: Float,
            topPx: Float,
            rightPx: Float,
            bottomPx: Float,
        ) -> Unit =
        { _, _, _, _, _ ->
        },
    /** W6 T8：自增即清掉原生选区（创建 / 取消后收起选择手柄）。 */
    clearSelectionToken: Int = 0,
    /** W6 T6：自动阅读节拍器（分页档 `goForward`、滚动档 WebView `scrollBy` 由本视图驱动）。 */
    autoAdvance: AutoAdvanceEngine? = null,
    /** W6 T6：手动干预（单击正文 / 用户拖动滚动）——调用方据此暂停自动阅读。 */
    onManualIntervention: () -> Unit = {},
    /** W6 T6：自动阅读已到书末（执行层已停止节拍器）。 */
    onAutoAdvanceEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // W80（#24）：平板竖屏时 Readium 的 `--USER__colCount` 被 CSS 媒体查询丢掉（只认 ≥60em 或横屏），
    // 双栏档会静默变单栏；这里在宽度够用的形态下直接压内部变量兜底（滚动档会被 `columns:auto auto` 覆盖，不受影响）。
    val forceTwoColumns =
        settings.mode == ReaderMode.TwoColumn && shouldForceEpubTwoColumns(screenWidthDp)
    // W6 T3（READER-08）：EPUB 只有「滑动 / 无动画」两档，映射为 Readium 的 `animated` 参数；
    // 系统「减少动态效果」时降级为无动画（Readium 内核没有淡入淡出档）。
    val pageAnimation = rememberReaderPageAnimation()
    val reduceMotion = rememberReduceMotionEnabled()
    val animatedTurns = epubPageTurnAnimated(pageAnimation, reduceMotion)
    // FXL（固定版式）不支持 Decoration / 文本选择（spike 已核实 `supportsDecorationStyle` 判定）。
    val selectionSupported = remember(publication) { publication.metadata.layout != Layout.FIXED }
    /**
     * 导航器实例级的权威判定：`supportsDecorationStyle(Highlight)`。
     *
     * 元数据先行（FXL 直接关掉），导航器就绪后再核一次；为 false 时选区回调与高亮下发全部跳过。
     */
    var decorationsSupported by remember(publication) { mutableStateOf(selectionSupported) }
    val currentSettings = rememberUpdatedState(settings)
    val currentSystemDark = rememberUpdatedState(systemDark)
    val currentOnLocationChanged = rememberUpdatedState(onLocationChanged)
    val currentOnJumpHandled = rememberUpdatedState(onJumpHandled)
    val currentOnNavigatorReady = rememberUpdatedState(onNavigatorReady)
    val currentBrightnessProvider = rememberUpdatedState(brightnessProvider)
    val currentOnBrightness = rememberUpdatedState(onBrightness)
    val currentTapPolicy = rememberUpdatedState(tapPolicy)
    val currentOnToggleControls = rememberUpdatedState(onToggleControls)
    val currentOnDoubleTapBookmark = rememberUpdatedState(onDoubleTapBookmark)
    val currentOnSelectionChanged = rememberUpdatedState(onSelectionChanged)
    val currentOnAnnotationActivated = rememberUpdatedState(onAnnotationActivated)
    val currentSelectionSupported = rememberUpdatedState(selectionSupported)
    val currentOnManualIntervention = rememberUpdatedState(onManualIntervention)
    val currentOnAutoAdvanceEnd = rememberUpdatedState(onAutoAdvanceEnd)
    val currentAnimatedTurns = rememberUpdatedState(animatedTurns)
    // 高亮模板：alpha / 圆角 / 线宽按定稿五 §3（tint 由每条 Decoration 自带）。
    val decorationTemplates = remember {
        HtmlDecorationTemplates.defaultTemplates(
            defaultTint = HighlightColor.Yellow.argb.toInt(),
            lineWeight = EPUB_HIGHLIGHT_LINE_WEIGHT,
            cornerRadius = EPUB_HIGHLIGHT_CORNER_RADIUS,
            alpha = EPUB_HIGHLIGHT_ALPHA,
        )
    }

    // 最近一次上报的位置：切换「双栏兜底」需要重建导航器，用它把阅读位置接回去。
    var liveLocator by remember(publication) { mutableStateOf<Locator?>(null) }
    // W77：`key(publication)` 内部持有导航器状态与效果——远程流式 → 本地热切换会换 publication
    // （同一个 `AndroidView` 节点被重建），状态必须跟着 publication 重建，否则新导航器不会挂载。
    // W80（#24）：`ReadiumCssRsProperties` 只在导航器构造时注入，因此「平板竖屏强制两栏」这条兜底
    // 需要连同 `forceTwoColumns` 一起重建导航器（用 [liveLocator] 续上当前页，位置不回退）。
    key(publication, forceTwoColumns) {
        var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
        val scope = rememberCoroutineScope()
        var selectionCaptureJob by remember { mutableStateOf<Job?>(null) }
        // 当前是否存在选区：让「有选区时的第一次单击」只用来取消选区，不误翻页。
        var selectionActive by remember { mutableStateOf(false) }

        /**
         * 选区快照：ActionMode（选择手柄出现 / 菜单刷新）与注入 JS 的 selectionchange 都走这里。
         *
         * 去抖 [EPUB_SELECTION_DEBOUNCE_MS] 后读 `currentSelection()`——点击回调里系统会先 clearFocus，
         * 所以**不能**等到工具栏按钮点击时再取选区。
         */
        fun scheduleSelectionCapture() {
            if (!currentSelectionSupported.value || !decorationsSupported) return
            selectionCaptureJob?.cancel()
            val current = navigator ?: return
            selectionCaptureJob = scope.launch {
                delay(EPUB_SELECTION_DEBOUNCE_MS)
                val snapshot = readEpubSelection(current, publication)
                if (snapshot == null && !selectionActive) return@launch
                selectionActive = snapshot != null
                currentOnSelectionChanged.value(snapshot)
            }
        }
        val currentCaptureTrigger = rememberUpdatedState<() -> Unit>({ scheduleSelectionCapture() })
        // ActionMode 回调：保留选区手柄、清空系统菜单（动作走自绘工具栏）。
        val selectionCallback = remember {
            object : ActionMode.Callback {
                override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                    currentCaptureTrigger.value()
                    return true
                }

                override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                    menu.clear()
                    currentCaptureTrigger.value()
                    return true
                }

                override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

                override fun onDestroyActionMode(mode: ActionMode) {
                    // 不在这里直接发 null：点自绘工具栏时系统可能先销毁 ActionMode，选区其实还在。
                    // 交给去抖后的重新捕获决定（选区真没了自然发 null）。
                    currentCaptureTrigger.value()
                }
            }
        }
        val decorationListener = remember {
            object : DecorableNavigator.Listener {
                override fun onDecorationActivated(
                    event: DecorableNavigator.OnActivatedEvent
                ): Boolean {
                    val id = event.decoration.extras["annotationId"] as? String ?: return false
                    val rect = event.rect
                    currentOnAnnotationActivated.value(
                        id,
                        rect?.left ?: 0f,
                        rect?.top ?: 0f,
                        rect?.right ?: 0f,
                        rect?.bottom ?: 0f,
                    )
                    return true
                }
            }
        }
        val listener = remember {
            object : EpubNavigatorFragment.Listener, EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(
                    pageIndex: Int,
                    totalPages: Int,
                    locator: Locator,
                ) {
                    liveLocator = locator
                    currentOnLocationChanged.value(locator)
                    if (decorationsSupported) {
                        // selectionchange 监听是逐文档注册的：换页后补注一次（失败不影响阅读）。
                        scope.launch {
                            withTimeoutOrNull(EPUB_SCRIPT_TIMEOUT_MS) {
                                runCatching {
                                    navigator?.evaluateJavascript(EPUB_SELECTION_TRACKER_JS)
                                }
                            }
                        }
                    }
                }

                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    // W1 PoC 不处理外链；W2 接入系统浏览器 / 站内路由。
                }
            }
        }

        LaunchedEffect(navigator, settings, systemDark) {
            navigator?.submitPreferences(settings.toEpubPreferences(systemDark))
        }

        // 书签跳转：等导航器就绪后执行一次，避免 Fragment 还没 add 就 go 丢目标。
        LaunchedEffect(navigator, jumpTarget) {
            val target = jumpTarget ?: return@LaunchedEffect
            val currentNavigator = navigator ?: return@LaunchedEffect
            currentNavigator.go(target, animated = true)
            currentOnJumpHandled.value()
        }

        // 导航器就绪时上报当前位置：用户不翻页也能直接加书签（不触发进度写入）。
        LaunchedEffect(navigator) {
            val currentNavigator = navigator ?: return@LaunchedEffect
            currentOnNavigatorReady.value(currentNavigator.currentLocator.value)
            // 权威判定：导航器是否支持高亮 Decoration（FXL / 非 reflowable 会返回 false）。
            decorationsSupported =
                runCatching {
                        currentNavigator.supportsDecorationStyle(Decoration.Style.Highlight::class)
                    }
                    .getOrDefault(selectionSupported)
            Timber.i("EPUB 高亮支持: $decorationsSupported（metadata selection=$selectionSupported）")
            if (decorationsSupported) {
                // 注入 selectionchange 去抖回调（window.cinefinSelection 由 Configuration 注册）。
                withTimeoutOrNull(EPUB_SCRIPT_TIMEOUT_MS) {
                    runCatching { currentNavigator.evaluateJavascript(EPUB_SELECTION_TRACKER_JS) }
                }
            }
        }

        // 标注整组替换：改色 / 删除都是重算整表再下发（spike 结论 ③）。
        LaunchedEffect(navigator, epubAnnotations, decorationsSupported) {
            val currentNavigator = navigator ?: return@LaunchedEffect
            if (!decorationsSupported) return@LaunchedEffect
            val decorations = epubAnnotations.mapNotNull { it.toEpubDecoration(publication) }
            runCatching {
                currentNavigator.applyDecorations(
                    decorations,
                    EPUB_ANNOTATION_DECORATION_GROUP,
                )
            }
                .onFailure { Timber.w(it, "应用 EPUB 高亮失败") }
        }

        // 高亮点击：Decoration 监听（rect 为导航器视图坐标，供列表 / 面板定位）。
        DisposableEffect(navigator) {
            val currentNavigator = navigator
            currentNavigator?.addDecorationListener(
                EPUB_ANNOTATION_DECORATION_GROUP,
                decorationListener,
            )
            onDispose { currentNavigator?.removeDecorationListener(decorationListener) }
        }

        // 创建 / 取消后收起原生选择手柄（工具栏自己的状态由调用方清）。
        LaunchedEffect(navigator, clearSelectionToken) {
            val currentNavigator = navigator ?: return@LaunchedEffect
            if (clearSelectionToken > 0) currentNavigator.clearSelection()
        }

        // W6 T6：自动阅读执行层——分页档按节拍器到点 `goForward`，滚动档逐帧 WebView `scrollBy`。
        val autoScrollGuard = remember { AutoScrollGuard() }
        var lastIssuedLocatorKey by remember { mutableStateOf<String?>(null) }
        var pendingPageTurnVerify by remember { mutableStateOf(false) }
        LaunchedEffect(navigator, autoAdvance, settings.mode) {
            val engine = autoAdvance ?: return@LaunchedEffect
            val currentNavigator = navigator ?: return@LaunchedEffect
            var lastScrollY = -1
            var stalledTicks = 0
            var listenerWebView: WebView? = null
            try {
                while (true) {
                    if (engine.state != AutoAdvanceState.Running) {
                        delay(AutoAdvanceIdlePollMs)
                        continue
                    }
                    if (settings.mode == ReaderMode.Scroll) {
                        val tick = engine.tick(paged = false)
                        val webView = findAutoScrollWebView(currentNavigator.publicationView)
                        // 用户拖动滚动 = 手动干预：程序化 scrollBy 会同步触发 onScrollChanged，
                        // 用标志位排除；换章会换 WebView，所以每次循环核对一次监听对象。
                        if (webView != null && webView !== listenerWebView) {
                            webView.setOnScrollChangeListener { _, _, _, _, _ ->
                                if (
                                    !autoScrollGuard.programmatic &&
                                        engine.state == AutoAdvanceState.Running
                                ) {
                                    currentOnManualIntervention.value()
                                }
                            }
                            listenerWebView = webView
                        }
                        if (webView != null && tick is AutoAdvanceTick.Scroll) {
                            autoScrollGuard.programmatic = true
                            webView.scrollBy(0, tick.deltaPx)
                            autoScrollGuard.programmatic = false
                            stalledTicks =
                                if (webView.scrollY == lastScrollY) stalledTicks + 1 else 0
                            lastScrollY = webView.scrollY
                            if (
                                autoScrollReachedEnd(webView, liveLocator) ||
                                    stalledTicks >= AUTO_SCROLL_STALL_TICKS
                            ) {
                                engine.stop()
                                currentOnAutoAdvanceEnd.value()
                            }
                        }
                        delay(AutoAdvanceScrollFrameMs)
                    } else {
                        if (engine.tick(paged = true) == AutoAdvanceTick.PageForward) {
                            val currentKey = liveLocator?.let(::autoLocatorKey)
                            if (
                                pendingPageTurnVerify &&
                                    currentKey != null &&
                                    currentKey == lastIssuedLocatorKey
                            ) {
                                // 上一次翻页后位置没变：已经到书末，停止并退出自动阅读。
                                engine.stop()
                                currentOnAutoAdvanceEnd.value()
                            } else {
                                lastIssuedLocatorKey = currentKey
                                pendingPageTurnVerify = true
                                currentNavigator.goForward(animated = currentAnimatedTurns.value)
                            }
                        }
                        delay(AutoAdvancePagedTickMs)
                    }
                }
            } finally {
                listenerWebView?.setOnScrollChangeListener(null)
            }
        }

        ReaderGestureArea(
            enabled = true,
            scrollMode = currentSettings.value.mode == ReaderMode.Scroll,
            brightnessProvider = { currentBrightnessProvider.value() },
            onBrightness = { value -> currentOnBrightness.value(value) },
            onSingleTap = { x, _, width ->
                currentOnManualIntervention.value()
                if (selectionActive) {
                    // 有选区时第一次单击 = 取消选区（不翻页、不唤控制层）。
                    selectionActive = false
                    navigator?.clearSelection()
                    currentOnSelectionChanged.value(null)
                } else {
                    when (readerTapAction(x, width, currentTapPolicy.value)) {
                        ReaderTapAction.PreviousPage ->
                            navigator?.goBackward(animated = animatedTurns)

                        ReaderTapAction.NextPage -> navigator?.goForward(animated = animatedTurns)

                        ReaderTapAction.ToggleControls -> currentOnToggleControls.value()
                    }
                }
            },
            onDoubleTap = { _, _, _ -> currentOnDoubleTapBookmark.value() },
            modifier = modifier,
        ) {
            AndroidView(
                factory = { viewContext ->
                    val container = FragmentContainerView(viewContext)
                    container.id = View.generateViewId()
                    container.post {
                        if (!container.isAttachedToWindow || navigator != null) {
                            return@post
                        }
                        val current = currentSettings.value
                        val factory =
                            EpubNavigatorFactory(
                                    publication = publication,
                                    configuration =
                                        EpubNavigatorFactory.Configuration(
                                            defaults =
                                                EpubDefaults(
                                                    scroll = current.mode == ReaderMode.Scroll
                                                )
                                        ),
                                )
                                .createFragmentFactory(
                                    // 双栏兜底重建导航器时用「最近位置」而不是文档初始位置，避免跳回开头。
                                    liveLocator ?: initialLocator,
                                    null,
                                    current.toEpubPreferences(currentSystemDark.value),
                                    listener,
                                    listener,
                                    EpubNavigatorFragment.Configuration(
                                            readiumCssRsProperties =
                                                if (forceTwoColumns) {
                                                    RsProperties(
                                                        overrides =
                                                            mapOf(
                                                                "--RS__colCount" to "2",
                                                                "--RS__colWidth" to "auto",
                                                            )
                                                    )
                                                } else {
                                                    RsProperties()
                                                },
                                            decorationTemplates = decorationTemplates,
                                            selectionActionModeCallback =
                                                if (selectionSupported) selectionCallback else null,
                                        )
                                        .apply {
                                            if (selectionSupported) {
                                                // selectionchange 去抖后回调原生，触发一次选区快照。
                                                registerJavascriptInterface("cinefinSelection") {
                                                    EpubSelectionJsBridge {
                                                        Handler(Looper.getMainLooper()).post {
                                                            currentCaptureTrigger.value()
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                )
                        val fragment =
                            factory.instantiate(
                                requireNotNull(publication.javaClass.classLoader),
                                EpubNavigatorFragment::class.java.name,
                            ) as EpubNavigatorFragment
                        val tag = "$NAVIGATOR_TAG-${System.identityHashCode(publication)}"
                        activity.supportFragmentManager
                            .beginTransaction()
                            .replace(container.id, fragment, tag)
                            .commitNow()
                        navigator = fragment
                    }
                    container
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    val fragment = navigator?.takeIf { it.isAdded } ?: return@AndroidView
                    activity.supportFragmentManager
                        .beginTransaction()
                        .remove(fragment)
                        .commitNowAllowingStateLoss()
                    navigator = null
                },
            )
        }
    }
}

/**
 * 选区快照：`currentSelection()` 给 text + 页级 locations，注入 JS 补 `cssSelector`（选区起点元素路径）。
 *
 * 失败 / 空选区返回空：调用方按「无选区」处理（工具栏不出现）。
 */
@OptIn(ExperimentalReadiumApi::class)
private suspend fun readEpubSelection(
    navigator: EpubNavigatorFragment,
    publication: Publication,
): EpubSelectionSnapshot? {
    val selection: Selection =
        try {
            navigator.currentSelection()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "读取 EPUB 选区失败")
            null
        } ?: return null
    val probe =
        try {
            withTimeoutOrNull(EPUB_SCRIPT_TIMEOUT_MS) {
                navigator.evaluateJavascript(EPUB_SELECTION_PROBE_JS)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "补算选区 cssSelector 失败（退化到纯文本定位）")
            null
        }
    val probeObject = parseEpubJsObject(probe)
    val cssSelector = probeObject?.optString("selector")?.takeIf { it.isNotBlank() }
    val selected = probeObject?.optString("text").orEmpty()
    val text = epubSelectionText(selection.locator.text.highlight.orEmpty(), selected)
    if (text.isEmpty()) return null
    val base = selection.locator
    val href = base.href.toString()
    val locator = runCatching {
        publication.normalizeLocator(
            base.copy(
                locations =
                    base.locations.copy(
                        otherLocations =
                            epubLocatorOtherLocations(
                                base.locations.otherLocations,
                                cssSelector,
                            )
                    )
            )
        )
    }
        .getOrDefault(base)
    val rect: RectF? = selection.rect
    return EpubSelectionSnapshot(
        text = text,
        locatorJson = locator.toJSON().toString(),
        chapterHref = href,
        spineIndex = readingOrderIndex(publication, href),
        progression = locator.locations.totalProgression ?: locator.locations.progression,
        cssSelector = cssSelector,
        leftPx = rect?.left ?: 0f,
        topPx = rect?.top ?: 0f,
        rightPx = rect?.right ?: 0f,
        bottomPx = rect?.bottom ?: 0f,
    )
}

/** 标注 → Readium Decoration（Highlight，具名颜色 → tint，extras 带 annotationId 供点击回调）。 */
@OptIn(ExperimentalReadiumApi::class)
private fun BookAnnotation.toEpubDecoration(publication: Publication): Decoration? {
    val raw = runCatching { Locator.fromJSON(JSONObject(locatorJson)) }.getOrNull() ?: return null
    val locator = runCatching { publication.normalizeLocator(raw) }.getOrDefault(raw)
    return Decoration(
        id,
        locator,
        Decoration.Style.Highlight(highlightColor.argb.toInt(), true),
        mapOf("annotationId" to id),
    )
}

private fun readingOrderIndex(publication: Publication, href: String): Int {
    val target = href.substringBefore('#')
    return publication.readingOrder.indexOfFirst { link ->
        link.href.toString().substringBefore('#') == target
    }
}

/** JS 结果解析：优先直接当 JSON 对象，兼容被再编码成字符串的一层。 */
private fun parseEpubJsObject(raw: String?): JSONObject? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty() || text == "null") return null
    runCatching { JSONObject(text) }
        .getOrNull()
        ?.let {
            return it
        }
    val unwrapped =
        runCatching { JSONTokener(text).nextValue() as? String }.getOrNull() ?: return null
    return runCatching { JSONObject(unwrapped) }.getOrNull()
}

/** selectionchange 去抖桥：JS 只负责「通知选区变了」，快照仍由原生读取。 */
private class EpubSelectionJsBridge(private val onSelectionSignal: () -> Unit) {
    @JavascriptInterface
    fun onSelectionChanged() {
        onSelectionSignal()
    }
}

/** 逐文档注入：去抖 [EPUB_SELECTION_DEBOUNCE_MS] 后回调原生（幂等，换页补注）。 */
private const val EPUB_SELECTION_TRACKER_JS =
    """(function(){
  try {
    if (window.__cinefinSelectionTracked) { return true; }
    window.__cinefinSelectionTracked = true;
    var timer = null;
    document.addEventListener('selectionchange', function(){
      if (timer) { clearTimeout(timer); }
      timer = setTimeout(function(){
        timer = null;
        try {
          if (window.cinefinSelection && window.cinefinSelection.onSelectionChanged) {
            window.cinefinSelection.onSelectionChanged();
          }
        } catch (e) {}
      }, 120);
    });
    return true;
  } catch (e) {
    return false;
  }
})()"""

/**
 * 补算选区精度：选区起点文本节点父元素的 `body > ... > tag:nth-child(n)` 路径 + 选中原文。
 *
 * Readium 渲染 Decoration 时用 `text.highlight` 做文本查找、`locations.cssSelector` 限定起点元素，
 * 于是重复短句（「是的。」）只在同一段内匹配，字号 / 分页变化后仍能还原。
 */
private const val EPUB_SELECTION_PROBE_JS =
    """(function(){
  try {
    var sel = window.getSelection();
    if (!sel || sel.rangeCount === 0 || sel.isCollapsed) { return { selector: null, text: '' }; }
    var range = sel.getRangeAt(0);
    var node = range.startContainer;
    var el = (node && node.nodeType === 3) ? node.parentElement : node;
    if (!el || el.nodeType !== 1) { return { selector: null, text: '' }; }
    var parts = [];
    while (el && el.nodeType === 1 && el !== document.body && el !== document.documentElement) {
      var parent = el.parentElement;
      if (!parent) { break; }
      var index = 0;
      var children = parent.children;
      for (var i = 0; i < children.length; i++) {
        if (children[i] === el) { index = i + 1; break; }
      }
      parts.unshift(el.tagName.toLowerCase() + ':nth-child(' + index + ')');
      el = parent;
    }
    var selector = parts.length ? ('body > ' + parts.join(' > ')) : null;
    return { selector: selector, text: sel.toString() };
  } catch (e) {
    return { selector: null, text: '' };
  }
})()"""

/** 滚动档整书进度达到该值且已不能继续下滚 = 书末。 */
private const val AUTO_SCROLL_END_PROGRESSION = 0.995

/** 连续无位移的帧数上限（≈3 s）：内核未续接下一章时停止，避免静默空转。 */
private const val AUTO_SCROLL_STALL_TICKS = 180

/** 程序化滚动标志：`scrollBy` 与用户拖动共用 `onScrollChanged`，只有后者算手动干预。 */
private class AutoScrollGuard {
    var programmatic: Boolean = false
}

/** 位置键：href + position + 整书进度，用于判断一次自动翻页是否真的推进了。 */
private fun autoLocatorKey(locator: Locator): String {
    val progression = locator.locations.totalProgression ?: locator.locations.progression ?: 0.0
    return "${locator.href}#${locator.locations.position ?: -1}@$progression"
}

/** 滚动档已到书末：整书进度接近 1 且 WebView 不能再向下滚。 */
private fun autoScrollReachedEnd(webView: WebView, locator: Locator?): Boolean {
    val progression = locator?.locations?.totalProgression ?: return false
    return progression >= AUTO_SCROLL_END_PROGRESSION && !webView.canScrollVertically(1)
}

/** 取导航器里当前可见的正文 WebView（与 `ReaderActivity` 键盘滚动同一查找口径）。 */
private fun findAutoScrollWebView(view: View?): WebView? =
    when (view) {
        null -> null
        is WebView -> view.takeIf { it.isShown }
        is ViewGroup ->
            (0 until view.childCount).firstNotNullOfOrNull {
                findAutoScrollWebView(view.getChildAt(it))
            }

        else -> null
    }
