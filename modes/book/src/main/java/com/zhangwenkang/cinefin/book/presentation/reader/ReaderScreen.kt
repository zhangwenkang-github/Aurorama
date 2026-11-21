package com.zhangwenkang.cinefin.book.presentation.reader

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.book.presentation.reader.tts.AndroidTtsEngine
import com.zhangwenkang.cinefin.book.presentation.reader.tts.READER_TTS_BAR_ENTER_MS
import com.zhangwenkang.cinefin.book.presentation.reader.tts.READER_TTS_BAR_EXIT_MS
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsBar
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsController
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsDecoration
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsHighlight
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsNotifier
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsSession
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsSheets
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsStartPoint
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsTextSource
import com.zhangwenkang.cinefin.book.presentation.reader.tts.ReaderTtsUiState
import com.zhangwenkang.cinefin.book.presentation.reader.tts.TtsPlayback
import com.zhangwenkang.cinefin.book.presentation.reader.tts.TtsTimerOption
import com.zhangwenkang.cinefin.book.presentation.reader.tts.TtsVoiceOption
import com.zhangwenkang.cinefin.book.presentation.reader.tts.locateReadingPoint
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSlider
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSliderColors
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.repository.ReaderBookmark
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Layout
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.services.search.SearchService
import org.readium.r2.shared.publication.services.search.search
import timber.log.Timber

/** W6 T9：句高亮的 200ms 淡入量化档数（每档 50ms 重下发一次 Decoration，定稿八）。 */
private const val TTS_HIGHLIGHT_FADE_STEPS = 4

private const val TTS_HIGHLIGHT_FADE_STEP_MS = 50L

/**
 * 阅读页（§9 Compose 落地）：外壳（顶栏 / 加载 / 错误态 / 设置面板）全部走 core 组件。
 *
 * 阅读主题（纸色 / 护眼 / 深色 / OLED / 跟随）驱动 `CinefinTheme` 的亮暗与底色； 纸色 / 护眼主题内媒体色整体替换为纸页棕（§8.14），所有控件通过
 * `LocalMediaColors` 自动取色。
 *
 * W3-R1 在阅读外壳上并入离线能力：顶栏下载状态 / 书签入口、离线暂存横幅、书签面板与跳转。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    settings: ReaderSettings,
    title: String,
    bookKey: String,
    systemDark: Boolean,
    downloadState: BookDownloadState,
    streamState: ReaderStreamState,
    bookmarks: List<ReaderBookmark>,
    pendingSyncCount: Int,
    jumpTarget: Locator?,
    searchState: PdfSearchUiState,
    annotations: List<ReaderAnnotation>,
    pageJumpTarget: Int?,
    onSettingsChange: (ReaderSettings) -> Unit,
    onLocationChanged: (Locator) -> Unit,
    onSimplePageChanged: (index: Int, pageCount: Int) -> Unit,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onJumpToBookmark: (ReaderBookmark) -> Unit,
    onJumpHandled: () -> Unit,
    onNavigatorReady: (Locator) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCancelSearch: () -> Unit,
    onJumpToHit: (PdfSearchHit) -> Unit,
    onJumpToAnnotation: (ReaderAnnotation) -> Unit,
    onPageJumpHandled: () -> Unit,
    onAddAnnotation: (pageIndex: Int, rect: PageRect, note: String) -> Unit,
    onUpdateAnnotationNote: (annotationId: String, note: String) -> Unit,
    onRemoveAnnotation: (annotationId: String) -> Unit,
    onBack: () -> Unit,
    /** W6 T1 起点 / T2 接线：双击正文 = 书签动作。具体「添加 / 取消」由阅读页内部完成，本钩子保留给外部（默认空）。 */
    onDoubleTapBookmark: () -> Unit = {},
    /** W6 T4：音量键 / 键盘 Enter 的「控制层开合」信号（每次自增 = 一次开合）。 */
    controlsToggleRequest: Int = 0,
    /** W6 T4（T2 交接）：目录错误态重试 → 重新解析目录与 positions。 */
    onReloadContents: () -> Unit = {},
) {
    var showSettings by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showAnnotations by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    // W6 T2：目录抽屉 / 「进度」入口聚焦 / 双击书签的 900ms 提示胶囊。
    var showContents by remember { mutableStateOf(false) }
    var progressFocusToken by remember { mutableIntStateOf(0) }
    var bookmarkNotice by remember { mutableStateOf<String?>(null) }
    var bookmarkNoticeToken by remember { mutableIntStateOf(0) }
    // 当前位置（0–1）：进度条与双击书签都要用，来源与 VM 的持久化同口径（totalProgression / 页换算）。
    // null = 还没收到第一次位置回调，回落到文档初始进度。
    var liveProgression by remember(bookKey) { mutableStateOf<Double?>(null) }
    LaunchedEffect(bookmarkNoticeToken) {
        if (bookmarkNoticeToken == 0) return@LaunchedEffect
        delay(BOOKMARK_NOTICE_VISIBLE_MS)
        bookmarkNotice = null
    }
    // W6 T1：沉浸态默认关闭控制层；书未就绪（加载 / 错误）时先显示，保证有返回入口。
    var controlsVisible by remember(bookKey) { mutableStateOf(false) }
    val bookReady = state is ReaderUiState.Ready
    LaunchedEffect(bookReady) { controlsVisible = !bookReady }
    // W6 T4：音量键 / 键盘 Enter 唤出或关闭控制层（外部信号自增触发一次）。
    LaunchedEffect(controlsToggleRequest) {
        if (controlsToggleRequest > 0) controlsVisible = !controlsVisible
    }
    BackHandler(enabled = controlsVisible) { controlsVisible = false }
    var annotating by remember { mutableStateOf(false) }
    var pendingAnnotation by remember { mutableStateOf<Pair<Int, PageRect>?>(null) }
    var editingAnnotation by remember { mutableStateOf<ReaderAnnotation?>(null) }
    // W80（#32）：亮度手势 —— 只作用于阅读页窗口（离开阅读页即恢复系统亮度），不落偏好。
    val view = LocalView.current
    val readerActivity = remember(view) { view.context.readerActivity() }
    var brightness by
        remember(readerActivity) { mutableStateOf(readerWindowBrightness(readerActivity)) }
    var brightnessHudToken by remember { mutableIntStateOf(0) }
    var brightnessHudVisible by remember { mutableStateOf(false) }
    // READER_PLAN §9：PDF / CBZ 全本共享倍率，同一进程内按书保留；退出阅读页不丢，切书 / 重启回 1×。
    val bookZoom = remember(bookKey) { BookZoomState(bookKey) }
    var zoomBarVisible by remember(bookKey) { mutableStateOf(false) }
    var zoomBarToken by remember(bookKey) { mutableIntStateOf(0) }
    val zoomBarAvailable = (state as? ReaderUiState.Ready)?.document is ReaderDocument.Simple
    // 文档就绪后才开始第一次「显示 3 秒再淡出」，避免慢加载时缩放条从未出现。
    LaunchedEffect(zoomBarAvailable) { if (zoomBarAvailable) zoomBarToken++ }
    LaunchedEffect(zoomBarToken) {
        if (zoomBarToken == 0) return@LaunchedEffect
        zoomBarVisible = true
        delay(ZOOM_BAR_VISIBLE_MS)
        zoomBarVisible = false
    }
    LaunchedEffect(brightness, readerActivity) {
        val window = readerActivity?.window ?: return@LaunchedEffect
        val attributes = window.attributes
        attributes.screenBrightness = brightness
        window.attributes = attributes
    }
    LaunchedEffect(brightnessHudToken) {
        if (brightnessHudToken == 0) return@LaunchedEffect
        brightnessHudVisible = true
        delay(BRIGHTNESS_HUD_VISIBLE_MS)
        brightnessHudVisible = false
    }
    val onBrightnessChange: (Float) -> Unit = { value ->
        brightness = value
        brightnessHudToken++
    }
    // W6 T5：排版扩展与主题自定义色由自持偏好提供（T6 并行期间不改 ReaderViewModel），
    // 组合期合并进设置模型后，凡「读设置」的位置统一用 effectiveSettings。
    val typography = rememberReaderTypographyState()
    val effectiveSettings = settings.withTypography(typography)
    val ready = state as? ReaderUiState.Ready
    // W6 T2：书签对 EPUB / PDF / CBZ 全格式开放（PDF / CBZ 用页锚点）。
    val bookmarksAvailable = ready != null
    /** 右起翻页（RTL）只对页序列文档（PDF / CBZ）开放；EPUB 的阅读方向由 Readium 出版物元数据决定。 */
    val pagingDirectionAvailable = ready?.document is ReaderDocument.Simple
    /** PDF 搜索 / 批注：只对 PDF 开放（CBZ 走同一页模型，但本波未列入验收范围）。 */
    val pdfToolsAvailable =
        (ready?.document as? ReaderDocument.Simple)?.format == SimpleBookFormat.Pdf
    // W6 T2：底片进度条 / 目录抽屉的数据源（导航元数据随 Ready 下发）。
    val navigation = ready?.navigation ?: ReaderNavigation()
    val tocEntries = (navigation.toc as? ReaderTocUiState.Ready)?.entries.orEmpty()
    val progressPageCount =
        when (val document = ready?.document) {
            is ReaderDocument.Rich -> navigation.positionPageCount
            is ReaderDocument.Simple -> document.pageSource.pageCount
            null -> null
        }
    val progressPercentVisible = ready?.document is ReaderDocument.Rich
    val currentProgression =
        (liveProgression ?: ready?.initialProgression ?: 0.0).coerceIn(0.0, 1.0)
    // 书签 marker / 双击切换共用同一投影：列表实例只在书签集合变化时更新（手势 key 不抖动）。
    val bookmarkProgressions = remember(bookmarks) { bookmarks.map { it.progression.toFloat() } }
    /** 进度条 / 目录跳转：复用书签跳转通道（locatorJson 为空 → VM 按 progression 定位）。 */
    val jumpToPosition: (Double, String?) -> Unit = { target, locatorJson ->
        val itemId = ready?.itemId
        if (itemId != null) {
            onJumpToBookmark(
                readerPositionBookmark(
                    itemId = itemId,
                    progression = target,
                    locatorJson = locatorJson,
                )
            )
        }
    }
    /**
     * W6 T2：双击正文 = 添加 / 取消书签（当前位置已有书签则取消），并弹 900ms 提示胶囊。
     *
     * 匹配按 progression（与持久化同口径）；[onDoubleTapBookmark] 是 T1 留的外部钩子（默认空）。
     */
    val onDoubleTapToggleBookmark: () -> Unit = {
        onDoubleTapBookmark()
        val bookmarkIndex =
            readerBookmarkIndexAt(bookmarks.map { it.progression }, currentProgression)
        if (bookmarkIndex != null) {
            onRemoveBookmark(bookmarks[bookmarkIndex].id)
            bookmarkNotice = "已取消"
        } else {
            onAddBookmark()
            bookmarkNotice = "已加书签"
        }
        bookmarkNoticeToken++
    }

    // ---- W6 T8：EPUB 划词高亮与笔记（READER-23–29）----
    // 状态与存储留在阅读页内：EPUB 标注只服务当前书，`ReaderActivity` 的接线（VM 调用点）因此不动。
    val context = LocalContext.current
    val epubScope = rememberCoroutineScope()
    val epubStore =
        remember(context) { EpubAnnotationStore(epubAnnotationStorageDir(context.filesDir)) }
    val richDocument = ready?.document as? ReaderDocument.Rich
    val epubBookId = richDocument?.let { ready.itemId.toString() }
    /** FXL（固定版式）不支持 Decoration / 文本选择（spike：`supportsDecorationStyle` 判定）。 */
    val epubAnnotationsAvailable =
        richDocument != null && richDocument.publication.metadata.layout != Layout.FIXED
    var epubAnnotations by remember(bookKey) { mutableStateOf<List<BookAnnotation>>(emptyList()) }
    var epubSelection by remember(bookKey) { mutableStateOf<EpubSelectionSnapshot?>(null) }
    var epubSelectionClearToken by remember(bookKey) { mutableIntStateOf(0) }
    var epubActiveAnnotation by remember(bookKey) { mutableStateOf<BookAnnotation?>(null) }
    var epubNoteTarget by remember(bookKey) { mutableStateOf<EpubNoteTarget?>(null) }
    var showEpubAnnotations by remember(bookKey) { mutableStateOf(false) }
    var epubNotice by remember(bookKey) { mutableStateOf<String?>(null) }
    var epubNoticeToken by remember(bookKey) { mutableIntStateOf(0) }
    LaunchedEffect(epubBookId) {
        epubAnnotations =
            epubBookId
                ?.let { runCatching { epubStore.list(it) }.getOrDefault(emptyList()) }
                .orEmpty()
    }
    LaunchedEffect(epubNoticeToken) {
        if (epubNoticeToken == 0) return@LaunchedEffect
        delay(EPUB_NOTICE_VISIBLE_MS)
        epubNotice = null
    }
    /** 章节标题：TOC 里同 href 的条目；找不到就留空（导出时按 spine 序号兜底）。 */
    val epubChapterTitleFor: (String) -> String = { href ->
        val target = href.substringBefore('#')
        tocEntries.firstOrNull { it.href?.substringBefore('#') == target }?.title.orEmpty()
    }
    val addEpubAnnotation: (EpubSelectionSnapshot, HighlightColor, String) -> Unit =
        { selection, color, note ->
            val bookId = epubBookId
            if (bookId != null) {
                val annotation =
                    newBookAnnotation(
                        bookId = bookId,
                        chapterHref = selection.chapterHref,
                        locatorJson = selection.locatorJson,
                        highlightColor = color,
                        noteText = note,
                        selectedText = selection.text,
                        chapterTitle =
                            selection.chapterTitle.ifBlank {
                                epubChapterTitleFor(selection.chapterHref)
                            },
                        spineIndex = selection.spineIndex,
                    )
                epubScope.launch {
                    epubAnnotations =
                        runCatching { epubStore.save(annotation) }.getOrElse { epubAnnotations }
                }
            }
            epubSelection = null
            epubSelectionClearToken++
        }
    val recolorEpubAnnotation: (BookAnnotation, HighlightColor) -> Unit = { annotation, color ->
        val bookId = epubBookId
        if (bookId != null) {
            epubScope.launch {
                runCatching { epubStore.recolor(bookId, annotation.id, color) }
                    .onSuccess { epubAnnotations = it }
            }
        }
    }
    val updateEpubAnnotationNote: (BookAnnotation, String) -> Unit = { annotation, note ->
        val bookId = epubBookId
        if (bookId != null) {
            epubScope.launch {
                runCatching { epubStore.updateNote(bookId, annotation.id, note) }
                    .onSuccess { epubAnnotations = it }
            }
        }
    }
    val removeEpubAnnotation: (BookAnnotation) -> Unit = { annotation ->
        val bookId = epubBookId
        if (bookId != null) {
            epubScope.launch {
                runCatching { epubStore.delete(bookId, annotation.id) }
                    .onSuccess { epubAnnotations = it }
            }
        }
        if (epubActiveAnnotation?.id == annotation.id) epubActiveAnnotation = null
    }
    /** 标注跳转：复用书签通道（locatorJson 非空 = 按整条 locator 定位）。 */
    val jumpToEpubAnnotation: (BookAnnotation) -> Boolean = { annotation ->
        val itemId = ready?.itemId
        if (itemId == null) {
            false
        } else {
            val progression =
                epubAnnotationProgression(annotation.locatorJson) ?: currentProgression
            onJumpToBookmark(
                readerPositionBookmark(
                    itemId = itemId,
                    progression = progression,
                    locatorJson = annotation.locatorJson,
                )
            )
            true
        }
    }

    // ---- W6 T10：EPUB 书内搜索（READER-31）----
    // 执行层 = Readium 自带 `publication.search()`（按资源顺序流式返回 LocatorCollection）；
    // 状态与生命周期留在阅读页内（与 T8 标注同一策略，不动 ReaderViewModel）。
    val epubSearchChapters =
        remember(tocEntries) { tocEntries.map { EpubSearchChapter(it.title, it.href) } }
    val epubSpineHrefs =
        remember(richDocument) {
            epubSpineHrefs(
                richDocument?.publication?.readingOrder?.map { it.href.toString() }.orEmpty()
            )
        }
    var epubSearchState by remember(bookKey) { mutableStateOf(EpubSearchUiState()) }
    var showEpubSearch by remember(bookKey) { mutableStateOf(false) }
    var epubSearchJob by remember(bookKey) { mutableStateOf<Job?>(null) }
    // 命中临时高亮：一次只保留一条，3s 后撤除（定稿八「搜索命中高亮」）。
    var epubSearchHighlight by remember(bookKey) { mutableStateOf<BookAnnotation?>(null) }
    var epubSearchHighlightToken by remember(bookKey) { mutableIntStateOf(0) }
    DisposableEffect(bookKey) { onDispose { epubSearchJob?.cancel() } }
    LaunchedEffect(epubSearchHighlightToken) {
        if (epubSearchHighlightToken == 0) return@LaunchedEffect
        delay(EPUB_SEARCH_HIGHLIGHT_VISIBLE_MS)
        epubSearchHighlight = null
    }
    val runEpubSearch: (String) -> Unit = { raw ->
        val query = normalizeSearchQuery(raw)
        epubSearchJob?.cancel()
        epubSearchJob = null
        val publication = richDocument?.publication
        if (query.isEmpty() || publication == null) {
            epubSearchState = EpubSearchUiState(query = query)
        } else {
            epubSearchState = EpubSearchUiState(query = query, running = true)
            epubSearchJob = epubScope.launch {
                val iterator = runCatching {
                    publication.search(query, SearchService.Options())
                }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        epubSearchState =
                            EpubSearchUiState(
                                query = query,
                                error = error.message ?: "搜索不可用",
                            )
                        return@launch
                    }
                if (iterator == null) {
                    epubSearchState = EpubSearchUiState(query = query, error = "这本书暂不支持搜索")
                    return@launch
                }
                var hits = emptyList<EpubSearchHit>()
                try {
                    while (currentCoroutineContext().isActive) {
                        val batch = iterator.next().getOrNull()
                        if (batch == null) {
                            epubSearchState =
                                epubSearchState.copy(running = false, error = "搜索中断，请重试")
                            return@launch
                        }
                        val locators = batch.locators.mapNotNull { it.toEpubSearchLocator() }
                        // 空集合 = 扫描结束（Readium 迭代器约定）。
                        if (locators.isEmpty()) break
                        val remaining = EPUB_SEARCH_MAX_HITS - hits.size
                        if (remaining <= 0) break
                        hits =
                            hits +
                                epubSearchHits(
                                    locators = locators,
                                    chapters = epubSearchChapters,
                                    spineHrefs = epubSpineHrefs,
                                    maxHits = remaining,
                                )
                        val truncated = hits.size >= EPUB_SEARCH_MAX_HITS
                        epubSearchState =
                            epubSearchState.copy(
                                hits = hits,
                                truncated = truncated,
                                running = !truncated,
                            )
                        if (truncated) break
                    }
                    epubSearchState = epubSearchState.copy(running = false, finished = true)
                } finally {
                    runCatching { iterator.close() }
                }
            }
        }
    }
    /** 命中跳转 = 书签通道 + 一次性临时高亮（Decoration 与 T8 同一组，3s 后自动撤除）。 */
    val jumpToEpubSearchHit: (EpubSearchHit) -> Unit = { hit ->
        jumpToPosition(
            epubSearchJumpProgression(hit, currentProgression),
            hit.locatorJson,
        )
        epubSearchHighlight =
            BookAnnotation(
                id = EPUB_SEARCH_HIGHLIGHT_ID,
                bookId = epubBookId.orEmpty(),
                chapterHref = hit.chapterHref,
                locatorJson = hit.locatorJson,
                highlightColor = HighlightColor.Yellow,
                noteText = "",
                selectedText = hit.snippet,
                createdAtMs = 0L,
                updatedAtMs = 0L,
                chapterTitle = hit.chapterTitle,
                spineIndex = hit.spineIndex,
            )
        epubSearchHighlightToken++
    }
    /** 从选区工具栏「搜索」进入：关键词 = 选中文本（≤60 字），直接开跑。 */
    val searchEpubSelection: (String) -> Unit = { text ->
        val keyword = text.trim().take(EPUB_SEARCH_MAX_QUERY_CHARS)
        epubSelection = null
        epubSelectionClearToken++
        showEpubSearch = true
        runEpubSearch(keyword)
    }
    /** Markdown 导出（READER-30）：整书标注 → `.md` 文本 + 分享 Intent（不碰服务器）。 */
    val exportEpubAnnotations: () -> Unit = {
        val publication = richDocument?.publication
        if (publication != null && epubAnnotations.isNotEmpty()) {
            val document =
                AnnotationExportDocument(
                    bookTitle = title,
                    bookAuthor =
                        publication.metadata.authors.mapNotNull { it.name }.joinToString("、"),
                    progressPercent = annotationExportProgressPercent(currentProgression),
                    exportedAtText = EPUB_EXPORT_TIME_FORMAT.format(LocalDateTime.now()),
                    annotations = epubAnnotations,
                )
            val markdown = buildAnnotationMarkdown(document)
            val fileName = annotationExportFileName(title)
            epubScope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        writeAnnotationExport(context, fileName, markdown)
                    }
                }
                    .onSuccess { file ->
                        shareAnnotationExport(context, document, markdown, file)
                        epubNotice = "已导出 ${file.name}"
                    }
                    .onFailure { error ->
                        Timber.w(error, "导出标注失败")
                        epubNotice = "导出失败：${error.message ?: "写入文件出错"}"
                    }
                epubNoticeToken++
            }
        }
    }

    // ---- W6 T6：自动阅读（READER-12）----
    // 节拍器是纯逻辑（AutoAdvanceEngine）；执行层（翻页 / 滚动帧循环）在 ReadiumEpubView / SimpleBookView 内，
    // 这里只负责参数、暂停规则与「更多」面板入口。
    var autoState by remember(bookKey) { mutableStateOf(AutoAdvanceState.Off) }
    val autoAdvance =
        remember(bookKey) {
            AutoAdvanceEngine(
                clock = { SystemClock.elapsedRealtime() },
                onStateChanged = { next -> autoState = next },
            )
        }
    val autoPreferences: AppPreferences? =
        remember(context) {
            runCatching {
                EntryPointAccessors.fromApplication(
                        context.applicationContext,
                        ReaderPreferencesEntryPoint::class.java,
                    )
                    .appPreferences()
            }
                .onFailure { Timber.w(it, "自动阅读偏好不可用，沿用默认参数") }
                .getOrNull()
        }
    var autoIntervalSeconds by
        remember(bookKey) {
            mutableIntStateOf(
                autoPreferences?.getValue(ReaderAutoPreferences.pageIntervalSeconds)
                    ?: AutoAdvanceDefaultIntervalSeconds
            )
        }
    var autoSpeedPxPerSecond by
        remember(bookKey) {
            mutableFloatStateOf(
                autoPreferences?.getValue(ReaderAutoPreferences.scrollSpeedPxPerSecond)
                    ?: AutoAdvanceDefaultSpeedPxPerSecond
            )
        }
    LaunchedEffect(autoIntervalSeconds, autoSpeedPxPerSecond) {
        autoAdvance.updateConfig(
            AutoAdvanceConfig(
                pageIntervalSeconds = autoIntervalSeconds,
                scrollSpeedPxPerSecond = autoSpeedPxPerSecond,
            )
        )
    }
    // 控制层唤出即暂停（关闭后由用户在「更多 → 自动阅读」里手动继续）。
    LaunchedEffect(controlsVisible) { if (controlsVisible) autoAdvance.pause() }
    // 离开阅读页即停止并释放帧循环。
    DisposableEffect(bookKey) { onDispose { autoAdvance.stop() } }
    val autoReadingAvailable = ready != null
    // 手动判定读「当前」设置：视图侧的长生命周期收集器可能持有旧 lambda，这里统一取最新快照。
    val currentAutoSettings = rememberUpdatedState(effectiveSettings)
    /**
     * 手动翻页判定：自动翻页后的宽限期内（[AUTO_MANUAL_GRACE_MS]）的位置变化归自动翻页， 其余算手动干预； 滚动档的自动滚动不翻页，手动拖动由滚动容器的
     * `isScrollInProgress` 单独判定。
     */
    val manualPageTurnDetected: () -> Boolean = {
        val now = SystemClock.elapsedRealtime()
        val last = autoAdvance.lastPageForwardAtMs
        autoAdvance.state == AutoAdvanceState.Running &&
            currentAutoSettings.value.mode != ReaderMode.Scroll &&
            (last == null || now - last > AUTO_MANUAL_GRACE_MS)
    }
    val onAutoAdvanceEnd: () -> Unit = {
        bookmarkNotice = "已到书末，自动阅读停止"
        bookmarkNoticeToken++
    }

    // ---- W6 T9：系统 TTS 听书（READER-32–36，定稿六）----
    // 引擎 / 状态机 / 章节文本提取都在 reader/tts/；这里只接线：起点二选一、迷你控制条、句高亮参数、
    // 跨章跟随、定时心跳与「点按正文跳转朗读位置」。
    var ttsState by remember(bookKey) { mutableStateOf(ReaderTtsUiState()) }
    var ttsShowStart by remember(bookKey) { mutableStateOf(false) }
    var ttsShowSettings by remember(bookKey) { mutableStateOf(false) }
    var ttsShowTimer by remember(bookKey) { mutableStateOf(false) }
    var ttsVoices by remember(bookKey) { mutableStateOf<List<TtsVoiceOption>>(emptyList()) }
    var ttsSelectedVoice by
        remember(bookKey) {
            mutableStateOf(
                autoPreferences?.getValue(ReaderTtsPreferences.voice)?.takeIf { it.isNotBlank() }
            )
        }
    var ttsTapFollowsReading by
        remember(bookKey) {
            mutableStateOf(
                autoPreferences?.getValue(ReaderTtsPreferences.tapFollowsReading) ?: true
            )
        }
    var ttsPauseOnTap by
        remember(bookKey) {
            mutableStateOf(autoPreferences?.getValue(ReaderTtsPreferences.pauseOnTap) ?: false)
        }
    // 最近一次上报位置：TTS 起点与「点按正文跳转朗读位置」都以可见位置为准。
    var liveLocator by remember(bookKey) { mutableStateOf<Locator?>(null) }
    var ttsReanchorPending by remember(bookKey) { mutableStateOf(false) }
    var ttsFadeStep by remember(bookKey) { mutableIntStateOf(4) }
    var ttsServiceRunning by remember(bookKey) { mutableStateOf(false) }
    val ttsScope = rememberCoroutineScope()
    val ttsEngine = remember(bookKey) { AndroidTtsEngine(context) }
    val ttsTitleState = rememberUpdatedState(title)
    // 章节标题解析随目录加载更新：用 rememberUpdatedState 让文本源始终读最新目录。
    val ttsTocEntries = rememberUpdatedState(tocEntries)
    val ttsSource =
        remember(richDocument) {
            richDocument?.let { document ->
                ReaderTtsTextSource(document.publication) { href ->
                    val target = href.substringBefore('#')
                    ttsTocEntries.value
                        .firstOrNull { it.href?.substringBefore('#') == target }
                        ?.title
                        .orEmpty()
                }
            }
        }
    val ttsChapterRequests = remember(bookKey) { MutableSharedFlow<Int>(extraBufferCapacity = 8) }
    val ttsController =
        remember(bookKey) {
            ReaderTtsController(
                engine = ttsEngine,
                clock = { SystemClock.elapsedRealtime() },
                onStateChanged = { next ->
                    ttsState = next
                    ReaderTtsSession.publish(next)
                },
                onRequestChapter = { index -> ttsChapterRequests.tryEmit(index) },
            )
        }
    val stopTts: () -> Unit = { ttsController.stop() }
    // 章节按需解析（跨章续播 / 指定章首）：IO 线程提取，结果回喂状态机。
    LaunchedEffect(ttsController, ttsSource) {
        ttsChapterRequests.collect { index ->
            val source = ttsSource
            val chapter = source?.let {
                runCatching { withContext(Dispatchers.IO) { it.loadChapter(index) } }.getOrNull()
            }
            if (chapter == null) ttsController.onChapterUnavailable(index)
            else ttsController.addChapter(chapter)
        }
    }
    LaunchedEffect(ttsController) {
        autoPreferences?.getValue(ReaderTtsPreferences.rate)?.let(ttsController::setRate)
    }
    // 引擎回调 → 状态机（onStart / onRangeStart 决定句高亮边界，READER-33/36）。
    DisposableEffect(bookKey, ttsController) {
        ttsEngine.callbacks =
            object : AndroidTtsEngine.Callbacks {
                override fun onStarted(utteranceId: String) {
                    ttsController.onUtteranceStarted(utteranceId)
                }

                override fun onRangeStart(utteranceId: String, start: Int, end: Int) {
                    ttsController.onRangeStart(utteranceId)
                }

                override fun onFinished(utteranceId: String) {
                    ttsController.onUtteranceFinished(utteranceId)
                }

                override fun onFailed(utteranceId: String) {
                    ttsController.onUtteranceFailed(utteranceId)
                }
            }
        ReaderTtsSession.attach(
            controls =
                object : ReaderTtsSession.Controls {
                    override fun play() = ttsController.resume()

                    override fun pause() = ttsController.pause()

                    override fun toggle() = ttsController.toggle()

                    override fun next() = ttsController.next()

                    override fun previous() = ttsController.previous()

                    override fun stop() = stopTts()
                },
            title = ttsTitleState.value,
            intent = null,
        )
        onDispose {
            ttsEngine.callbacks = null
            stopTts()
            ReaderTtsSession.detach()
            ReaderTtsNotifier.stop(context)
            ttsEngine.shutdown()
        }
    }
    // 播放态 ↔ 前台服务（通知 / 锁屏 / 耳机键）。
    LaunchedEffect(ttsState.playback) {
        when {
            ttsState.playback == TtsPlayback.Idle && ttsServiceRunning -> {
                ReaderTtsNotifier.stop(context)
                ttsServiceRunning = false
            }
            ttsState.playback != TtsPlayback.Idle && !ttsServiceRunning -> {
                ReaderTtsNotifier.start(context)
                ttsServiceRunning = true
            }
        }
    }
    // 定时心跳：到点由状态机直接 stop（系统 TTS 无音量包络，定稿六 §4 记观察项）。
    LaunchedEffect(ttsController, ttsState.timer) {
        if (ttsState.timer == TtsTimerOption.Off) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            ttsController.onTick(SystemClock.elapsedRealtime())
        }
    }
    // 停止原因复用 900ms 轻提示胶囊（与书签提示同一形态）。
    LaunchedEffect(ttsState.message) {
        val text = ttsState.message ?: return@LaunchedEffect
        bookmarkNotice = text
        bookmarkNoticeToken++
    }
    val jumpToTtsReading: () -> Unit = {
        ttsState.active?.let { sentence ->
            jumpToPosition(sentence.progression ?: currentProgression, sentence.locatorJson)
        }
    }
    /** 点按正文 → 把朗读位置落到可见处继续读（设置「点按正文跳转朗读位置」默认开）。 */
    val reanchorTtsToViewport: () -> Unit = {
        val locator = liveLocator
        if (locator != null && ttsState.playback != TtsPlayback.Idle) {
            ttsController.relocate(
                href = locator.href.toString(),
                progression = locator.locations.totalProgression ?: locator.locations.progression,
                pause = ttsPauseOnTap,
            )
        }
    }
    LaunchedEffect(liveLocator) {
        if (!ttsReanchorPending) return@LaunchedEffect
        ttsReanchorPending = false
        reanchorTtsToViewport()
    }
    // 跨章自动衔接：当前朗读句换了资源就把视图跟过去（同章内不打扰用户翻页）。
    LaunchedEffect(ttsState.active?.chapterIndex) {
        val sentence = ttsState.active ?: return@LaunchedEffect
        val currentHref =
            liveLocator?.href?.toString()?.substringBefore('#') ?: return@LaunchedEffect
        if (currentHref == sentence.href.substringBefore('#')) return@LaunchedEffect
        jumpToPosition(sentence.progression ?: 0.0, sentence.locatorJson)
    }
    // 句高亮：真正开口后才显示；200ms 底色淡入按 4 档量化重下发（WebView 内无补间，定稿八）。
    val ttsHighlightLocator =
        remember(ttsState.active?.locatorJson, ttsState.highlightVisible) {
            if (!ttsState.highlightVisible) null
            else
                ttsState.active?.locatorJson?.let { json ->
                    runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
                }
        }
    LaunchedEffect(ttsHighlightLocator) {
        ttsFadeStep = 0
        if (ttsHighlightLocator == null) return@LaunchedEffect
        repeat(TTS_HIGHLIGHT_FADE_STEPS) { step ->
            delay(TTS_HIGHLIGHT_FADE_STEP_MS)
            ttsFadeStep = step + 1
        }
    }
    val ttsDecoration =
        remember(ttsHighlightLocator, ttsFadeStep, effectiveSettings, systemDark) {
            ttsHighlightLocator?.let { locator ->
                ReaderTtsDecoration(
                    locator = locator,
                    tintArgb =
                        ReaderTtsHighlight.fadeTint(
                            effectiveSettings.accentColor(systemDark).toArgb(),
                            effectiveSettings.surfaceColor(systemDark).toArgb(),
                            ttsFadeStep / TTS_HIGHLIGHT_FADE_STEPS.toFloat(),
                        ),
                )
            }
        }
    /** 听书起点二选一（定稿六 §2）：当前位置 → 同资源最近句；章首 → 该章第一句。 */
    val startTts: (ReaderTtsStartPoint) -> Unit = { point ->
        ttsShowStart = false
        val source = ttsSource
        if (source == null) {
            bookmarkNotice = "这本书暂不支持听书"
            bookmarkNoticeToken++
        } else {
            controlsVisible = false
            ttsScope.launch {
                ttsEngine.initialize(ttsSelectedVoice)
                if (!ttsEngine.awaitReady()) {
                    bookmarkNotice = ttsEngine.initError ?: "系统 TTS 不可用"
                    bookmarkNoticeToken++
                    return@launch
                }
                ttsVoices = ttsEngine.voices()
                val anchor = liveLocator ?: richDocument?.initialLocator
                val href = anchor?.href?.toString()
                val anchorChapter = href?.let { source.chapterIndexOfHref(it) } ?: 0
                val chapter = runCatching {
                    withContext(Dispatchers.IO) { source.loadChapter(anchorChapter) }
                }
                    .getOrNull()
                if (chapter == null || chapter.sentences.isEmpty()) {
                    bookmarkNotice = "这一章没有可朗读的文字"
                    bookmarkNoticeToken++
                    return@launch
                }
                val startSentence =
                    if (point == ReaderTtsStartPoint.ChapterStart) {
                        0
                    } else {
                        val progression =
                            anchor?.locations?.totalProgression ?: anchor?.locations?.progression
                        locateReadingPoint(listOf(chapter), chapter.href, progression)?.second ?: 0
                    }
                ttsController.addChapter(chapter)
                ttsController.startAt(chapter.index, startSentence)
            }
        }
    }

    CinefinTheme(
        domain = ContentDomain.Book,
        darkTheme = effectiveSettings.isDark(systemDark),
        surfaceBackground = false,
    ) {
        val contentColor = effectiveSettings.contentColor(systemDark)
        val chromeColor = effectiveSettings.chromeColor(systemDark)
        val accent = effectiveSettings.accentColor(systemDark)

        CompositionLocalProvider(
            LocalMediaColors provides effectiveSettings.mediaColors(systemDark)
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = effectiveSettings.surfaceColor(systemDark),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    when (state) {
                        ReaderUiState.Loading ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(color = accent)
                            }

                        is ReaderUiState.Error ->
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                CinefinEmptyState(
                                    title = "打不开这本书",
                                    message = state.message,
                                    action = {
                                        CinefinButton(
                                            text = "重试",
                                            onClick = onRetry,
                                            size = CinefinButtonSize.Medium,
                                        )
                                    },
                                )
                            }

                        is ReaderUiState.Ready ->
                            when (val document = state.document) {
                                is ReaderDocument.Rich ->
                                    // W6 T8：EPUB 正文 + 选区浮动工具栏（工具栏按选区矩形锚定，不参与正文布局）。
                                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                                        val density = LocalDensity.current
                                        val containerWidthDp = maxWidth.value
                                        val containerHeightDp = maxHeight.value
                                        ReadiumEpubView(
                                            publication = document.publication,
                                            initialLocator = document.initialLocator,
                                            settings = effectiveSettings,
                                            systemDark = systemDark,
                                            jumpTarget = jumpTarget,
                                            onLocationChanged = { locator ->
                                                locator.locations.totalProgression?.let {
                                                    liveProgression = it
                                                }
                                                // W6 T9：听书起点 / 点按重锚都以最近可见位置为准。
                                                liveLocator = locator
                                                if (manualPageTurnDetected()) {
                                                    autoAdvance.pauseForManualIntervention()
                                                }
                                                onLocationChanged(locator)
                                            },
                                            onJumpHandled = onJumpHandled,
                                            onNavigatorReady = onNavigatorReady,
                                            brightnessProvider = { brightness },
                                            onBrightness = onBrightnessChange,
                                            tapPolicy =
                                                effectiveSettings.tapPolicy(controlsVisible),
                                            onToggleControls = {
                                                controlsVisible = !controlsVisible
                                            },
                                            onDoubleTapBookmark = onDoubleTapToggleBookmark,
                                            // W6 T10：临时命中高亮复用标注 Decoration 组（3s 后随状态撤除）。
                                            epubAnnotations =
                                                epubSearchHighlight?.let { epubAnnotations + it }
                                                    ?: epubAnnotations,
                                            onSelectionChanged = { selection ->
                                                epubSelection = selection
                                            },
                                            onAnnotationActivated = { id, _, _, _, _ ->
                                                epubSelection = null
                                                epubActiveAnnotation = epubAnnotations.firstOrNull {
                                                    it.id == id
                                                }
                                            },
                                            clearSelectionToken = epubSelectionClearToken,
                                            autoAdvance = autoAdvance,
                                            onManualIntervention = {
                                                autoAdvance.pauseForManualIntervention()
                                            },
                                            onAutoAdvanceEnd = onAutoAdvanceEnd,
                                            // W6 T9：朗读句高亮（独立 Decoration group）+ 点按正文重锚。
                                            ttsHighlight = ttsDecoration,
                                            onBodyTap = {
                                                if (
                                                    ttsTapFollowsReading &&
                                                        ttsState.playback != TtsPlayback.Idle
                                                ) {
                                                    ttsReanchorPending = true
                                                }
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                        epubSelection
                                            ?.takeUnless { controlsVisible }
                                            ?.let { selection ->
                                                val placement =
                                                    remember(
                                                        selection,
                                                        containerWidthDp,
                                                        containerHeightDp,
                                                    ) {
                                                        with(density) {
                                                            epubToolbarPlacement(
                                                                selectionLeftDp =
                                                                    selection.leftPx.toDp().value,
                                                                selectionTopDp =
                                                                    selection.topPx.toDp().value,
                                                                selectionRightDp =
                                                                    selection.rightPx.toDp().value,
                                                                selectionBottomDp =
                                                                    selection.bottomPx.toDp().value,
                                                                containerWidthDp = containerWidthDp,
                                                                containerHeightDp =
                                                                    containerHeightDp,
                                                            )
                                                        }
                                                    }
                                                EpubSelectionToolbar(
                                                    inBookSearchAvailable = richDocument != null,
                                                    containerColor = chromeColor,
                                                    contentColor = contentColor,
                                                    onAction = { action ->
                                                        when (action) {
                                                            EpubSelectionAction.Idea ->
                                                                epubNoteTarget =
                                                                    EpubNoteTarget.NewSelection(
                                                                        selection = selection,
                                                                        color =
                                                                            HighlightColor.Yellow,
                                                                    )
                                                            EpubSelectionAction.Copy -> {
                                                                copyEpubSelection(
                                                                    context,
                                                                    selection.text,
                                                                )
                                                                epubSelection = null
                                                                epubSelectionClearToken++
                                                                epubNotice = "已复制"
                                                                epubNoticeToken++
                                                            }
                                                            EpubSelectionAction.Dictionary -> {
                                                                val launched =
                                                                    launchEpubDictionary(
                                                                        context,
                                                                        selection.text,
                                                                    )
                                                                if (!launched) {
                                                                    copyEpubSelection(
                                                                        context,
                                                                        selection.text,
                                                                    )
                                                                    epubNotice = "已复制（未找到词典）"
                                                                    epubNoticeToken++
                                                                }
                                                                epubSelection = null
                                                                epubSelectionClearToken++
                                                            }
                                                            EpubSelectionAction.Highlight -> Unit
                                                            EpubSelectionAction.Search ->
                                                                searchEpubSelection(selection.text)
                                                        }
                                                    },
                                                    onPickColor = { color ->
                                                        addEpubAnnotation(selection, color, "")
                                                    },
                                                    modifier =
                                                        Modifier.offset(
                                                            x = placement.xDp.dp,
                                                            y = placement.yDp.dp,
                                                        ),
                                                )
                                            }
                                    }

                                is ReaderDocument.Simple ->
                                    SimpleBookView(
                                        document = document,
                                        settings = effectiveSettings,
                                        systemDark = systemDark,
                                        contentColor = contentColor,
                                        chromeColor = chromeColor,
                                        bookZoom = bookZoom,
                                        searchHits = searchState.hits,
                                        annotations = annotations,
                                        annotating = annotating,
                                        jumpTargetPage = pageJumpTarget,
                                        onPageChanged = { index, pageCount ->
                                            liveProgression = progressionForPage(index, pageCount)
                                            onSimplePageChanged(index, pageCount)
                                            if (manualPageTurnDetected()) {
                                                autoAdvance.pauseForManualIntervention()
                                            }
                                        },
                                        onJumpHandled = onPageJumpHandled,
                                        brightnessProvider = { brightness },
                                        onBrightness = onBrightnessChange,
                                        onAnnotationRequested = { index, rect ->
                                            pendingAnnotation = index to rect
                                        },
                                        onAnnotationTap = { annotation ->
                                            editingAnnotation = annotation
                                        },
                                        onZoomInteraction = { zoomBarToken++ },
                                        onBlankTap = { zoomBarToken++ },
                                        tapPolicy = effectiveSettings.tapPolicy(controlsVisible),
                                        onToggleControls = { controlsVisible = !controlsVisible },
                                        onDoubleTapBookmark = onDoubleTapToggleBookmark,
                                        autoAdvance = autoAdvance,
                                        onManualIntervention = {
                                            autoAdvance.pauseForManualIntervention()
                                        },
                                        onAutoAdvanceEnd = onAutoAdvanceEnd,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                            }
                    }

                    if (pendingSyncCount > 0) {
                        Box(modifier = Modifier.align(Alignment.TopCenter)) {
                            PendingSyncBanner(
                                pendingSyncCount = pendingSyncCount,
                                containerColor = chromeColor,
                                contentColor = contentColor,
                            )
                        }
                    }

                    if (annotating) {
                        Box(modifier = Modifier.align(Alignment.TopCenter)) {
                            Surface(color = chromeColor, contentColor = contentColor) {
                                AnnotateHintBar(
                                    contentColor = contentColor,
                                    accent = accent,
                                    onStop = { annotating = false },
                                )
                            }
                        }
                    }

                    if (zoomBarAvailable) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = zoomBarVisible,
                            enter = fadeIn(tween(160)),
                            exit = fadeOut(tween(ZOOM_BAR_FADE_MS)),
                            modifier =
                                Modifier.align(Alignment.BottomEnd)
                                    .padding(
                                        end = CinefinSpacing.Space4,
                                        bottom = ZOOM_BAR_BOTTOM_PADDING,
                                    ),
                        ) {
                            ReaderZoomBar(
                                percent = bookZoom.percent,
                                canZoomOut = bookZoom.canZoomOut,
                                canZoomIn = bookZoom.canZoomIn,
                                containerColor = chromeColor,
                                contentColor = contentColor,
                                onZoomOut = {
                                    bookZoom.zoomOut()
                                    zoomBarToken++
                                },
                                onZoomIn = {
                                    bookZoom.zoomIn()
                                    zoomBarToken++
                                },
                            )
                        }
                    }

                    if (brightnessHudVisible) {
                        BrightnessHud(
                            percent = (brightness * 100f).roundToInt(),
                            containerColor = chromeColor,
                            contentColor = contentColor,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }

                    // W6 T2：双击书签的轻提示（900ms 淡入淡出，定稿八）。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = bookmarkNotice != null,
                        enter = fadeIn(tween(BOOKMARK_NOTICE_FADE_MS)),
                        exit = fadeOut(tween(BOOKMARK_NOTICE_FADE_MS)),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Surface(
                            shape = CinefinShapes.Button,
                            color = chromeColor.copy(alpha = 0.92f),
                            contentColor = contentColor,
                        ) {
                            Text(
                                text = bookmarkNotice.orEmpty(),
                                style = CinefinType.TitleMedium,
                                modifier =
                                    Modifier.padding(
                                        horizontal = CinefinSpacing.Space5,
                                        vertical = CinefinSpacing.Space3,
                                    ),
                            )
                        }
                    }

                    // W6 T8：复制 / 词典降级的轻提示（与书签提示同一形态）。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = epubNotice != null,
                        enter = fadeIn(tween(BOOKMARK_NOTICE_FADE_MS)),
                        exit = fadeOut(tween(BOOKMARK_NOTICE_FADE_MS)),
                        modifier = Modifier.align(Alignment.Center),
                    ) {
                        Surface(
                            shape = CinefinShapes.Button,
                            color = chromeColor.copy(alpha = 0.92f),
                            contentColor = contentColor,
                        ) {
                            Text(
                                text = epubNotice.orEmpty(),
                                style = CinefinType.TitleMedium,
                                modifier =
                                    Modifier.padding(
                                        horizontal = CinefinSpacing.Space5,
                                        vertical = CinefinSpacing.Space3,
                                    ),
                            )
                        }
                    }

                    // 控制层可见时正文区域整体只做「关闭」：透明 scrim 拦截正文点击（含链接 / 批注命中）。
                    if (controlsVisible) {
                        Box(
                            modifier =
                                Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectTapGestures { controlsVisible = false }
                                }
                        )
                    }

                    ReaderControlLayer(
                        visible = controlsVisible,
                        title = title,
                        darkChrome = effectiveSettings.isDark(systemDark),
                        chromeColor = chromeColor,
                        contentColor = contentColor,
                        progression = currentProgression.toFloat(),
                        chapterTicks = navigation.chapterTicks,
                        chapters = tocEntries,
                        bookmarkProgressions = bookmarkProgressions,
                        progressPageCount = progressPageCount,
                        progressPercentVisible = progressPercentVisible,
                        progressFocusToken = progressFocusToken,
                        onBack = onBack,
                        onMore = { showMore = true },
                        // T2 接线：目录抽屉 / 书签列表 / 进度条拖动。
                        onOpenContents = { showContents = true },
                        onOpenBookmarks = { showBookmarks = true },
                        onOpenProgress = { progressFocusToken++ },
                        onOpenSettings = { showSettings = true },
                        onDismissByDrag = { controlsVisible = false },
                        onScrub = { target -> jumpToPosition(target.toDouble(), null) },
                        onBookmarkTap = { index ->
                            bookmarks.getOrNull(index)?.let(onJumpToBookmark)
                        },
                    )

                    // W6 T9（READER-32）：听书迷你控制条（高 56dp，容器同控制层底片）。
                    // 控制层可见时让位给底片，两层不叠在同一底边。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = ttsState.playback != TtsPlayback.Idle && !controlsVisible,
                        enter = fadeIn(tween(READER_TTS_BAR_ENTER_MS)),
                        exit = fadeOut(tween(READER_TTS_BAR_EXIT_MS)),
                        modifier = Modifier.align(Alignment.BottomCenter),
                    ) {
                        ReaderTtsBar(
                            state = ttsState,
                            darkChrome = effectiveSettings.isDark(systemDark),
                            chromeColor = chromeColor,
                            contentColor = contentColor,
                            onToggle = ttsController::toggle,
                            onPrevious = ttsController::previous,
                            onNext = ttsController::next,
                            onOpenSettings = { ttsShowSettings = true },
                            onOpenTimer = { ttsShowTimer = true },
                            onJumpToReading = jumpToTtsReading,
                        )
                    }
                }
            }

            if (showSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showSettings = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = CinefinSpacing.Space6)
                                .padding(bottom = CinefinSpacing.Space6),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        ReaderSettingsPanel(
                            settings = effectiveSettings,
                            systemDark = systemDark,
                            showRtl = pagingDirectionAvailable,
                            tools =
                                ReaderSettingsTools(
                                    downloadState = downloadState,
                                    streamState = streamState,
                                    onDownload = onDownload,
                                    bookmarksEnabled = bookmarksAvailable,
                                    onOpenBookmarks = {
                                        showSettings = false
                                        showBookmarks = true
                                    },
                                    pdfToolsEnabled = pdfToolsAvailable,
                                    onOpenSearch = {
                                        showSettings = false
                                        showSearch = true
                                    },
                                    onOpenAnnotations = {
                                        showSettings = false
                                        showAnnotations = true
                                    },
                                ),
                            onSettingsChange = onSettingsChange,
                            brightness = brightness,
                            // 面板滑块与左缘手势同源，但不弹手势 HUD（面板内已有百分比）。
                            onBrightnessChange = { brightness = it },
                        )
                    }
                }
            }

            if (showBookmarks) {
                ModalBottomSheet(
                    onDismissRequest = { showBookmarks = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    BookmarkPanel(
                        bookmarks = bookmarks,
                        enabled = state is ReaderUiState.Ready,
                        contentColor = contentColor,
                        onAddBookmark = {
                            onAddBookmark()
                            showBookmarks = false
                        },
                        onRemoveBookmark = onRemoveBookmark,
                        onJumpToBookmark = { bookmark ->
                            onJumpToBookmark(bookmark)
                            showBookmarks = false
                        },
                    )
                }
            }

            // W6 T2：目录抽屉（底部半屏，可拖成全屏；关闭后再回到控制层）。
            if (showContents) {
                ModalBottomSheet(
                    onDismissRequest = { showContents = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    ReaderTocSheet(
                        state = navigation.toc,
                        currentProgression = currentProgression,
                        showReadMarks = effectiveSettings.tocShowRead,
                        accent = accent,
                        contentColor = contentColor,
                        onSelect = { entry ->
                            jumpToPosition(
                                entry.progression ?: currentProgression,
                                entry.locatorJson,
                            )
                            showContents = false
                        },
                        onRetry = onReloadContents,
                    )
                }
            }

            if (showSearch) {
                ModalBottomSheet(
                    onDismissRequest = {
                        showSearch = false
                        onCancelSearch()
                    },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    ReaderSearchSheet(
                        state = searchState,
                        accent = accent,
                        contentColor = contentColor,
                        onQueryChange = onSearchQueryChange,
                        onJumpToHit = { hit ->
                            onJumpToHit(hit)
                            showSearch = false
                            onCancelSearch()
                        },
                    )
                }
            }

            // W6 T10：EPUB 书内搜索（READER-31）。关面板不取消扫描，重开仍能看到结果。
            if (showEpubSearch) {
                ModalBottomSheet(
                    onDismissRequest = { showEpubSearch = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    EpubSearchSheet(
                        state = epubSearchState,
                        accent = accent,
                        contentColor = contentColor,
                        onQueryChange = runEpubSearch,
                        onJumpToHit = { hit ->
                            jumpToEpubSearchHit(hit)
                            showEpubSearch = false
                        },
                    )
                }
            }

            if (showAnnotations) {
                ModalBottomSheet(
                    onDismissRequest = { showAnnotations = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    ReaderAnnotationSheet(
                        annotations = annotations,
                        annotating = annotating,
                        contentColor = contentColor,
                        onToggleAnnotating = {
                            if (annotating) {
                                annotating = false
                            } else {
                                annotating = true
                                // 关掉面板才能看到页面去框选。
                                showAnnotations = false
                            }
                        },
                        onJumpToAnnotation = { annotation ->
                            onJumpToAnnotation(annotation)
                            showAnnotations = false
                        },
                        onRemoveAnnotation = { annotation -> onRemoveAnnotation(annotation.id) },
                    )
                }
            }

            if (showMore) {
                ModalBottomSheet(
                    onDismissRequest = { showMore = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    Column(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = CinefinSpacing.Space6)
                                .padding(bottom = CinefinSpacing.Space6),
                        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
                    ) {
                        Text(text = "更多", style = CinefinType.TitleMedium, color = contentColor)
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = CinefinSpacing.Space2)
                        )
                        MoreActionRow(
                            label = "目录",
                            contentColor = contentColor,
                            onClick = {
                                showMore = false
                                showContents = true
                            },
                        )
                        MoreActionRow(
                            label = "书签列表",
                            contentColor = contentColor,
                            onClick = {
                                showMore = false
                                showBookmarks = true
                            },
                        )
                        if (pdfToolsAvailable || richDocument != null) {
                            MoreActionRow(
                                label = "书内搜索",
                                contentColor = contentColor,
                                onClick = {
                                    showMore = false
                                    if (pdfToolsAvailable) showSearch = true
                                    else showEpubSearch = true
                                },
                            )
                        }
                        // W6 T8：EPUB 标注列表并入「更多」（PDF 走矩形批注列表，EPUB 走划词高亮列表）；
                        // FXL（固定版式）不支持 Decoration，入口保留但灰置。
                        if (pdfToolsAvailable || richDocument != null) {
                            MoreActionRow(
                                label =
                                    if (richDocument != null && !epubAnnotationsAvailable) {
                                        "标注列表（固定版式不支持）"
                                    } else {
                                        "标注列表"
                                    },
                                contentColor = contentColor,
                                enabled = pdfToolsAvailable || epubAnnotationsAvailable,
                                onClick = {
                                    showMore = false
                                    if (pdfToolsAvailable) {
                                        showAnnotations = true
                                    } else {
                                        showEpubAnnotations = true
                                    }
                                },
                            )
                        }
                        // W6 T10（READER-30）：整书标注导出 Markdown（入口按定稿七 §5 放「更多」）。
                        if (richDocument != null) {
                            MoreActionRow(
                                label =
                                    if (epubAnnotations.isEmpty()) {
                                        "导出标注（Markdown）· 暂无标注"
                                    } else {
                                        "导出标注（Markdown）· ${epubAnnotations.size} 条"
                                    },
                                contentColor = contentColor,
                                enabled = epubAnnotations.isNotEmpty(),
                                onClick = {
                                    showMore = false
                                    exportEpubAnnotations()
                                },
                            )
                        }
                        // W6 T6（READER-12）：自动阅读分组。开关默认关；参数落 `pref_reader_auto_*`。
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = CinefinSpacing.Space2)
                        )
                        Text(
                            text = "自动阅读",
                            style = CinefinType.LabelMedium,
                            color = contentColor.copy(alpha = 0.72f),
                        )
                        AutoAdvanceGroup(
                            state = autoState,
                            scrollMode = effectiveSettings.mode == ReaderMode.Scroll,
                            intervalSeconds = autoIntervalSeconds,
                            speedPxPerSecond = autoSpeedPxPerSecond,
                            enabled = autoReadingAvailable,
                            contentColor = contentColor,
                            accent = accent,
                            onAccent = effectiveSettings.onAccentColor(systemDark),
                            onToggle = { running ->
                                if (running) autoAdvance.start() else autoAdvance.stop()
                            },
                            onResume = { autoAdvance.resume() },
                            onIntervalChange = { seconds ->
                                autoIntervalSeconds = seconds
                                autoPreferences?.setValue(
                                    ReaderAutoPreferences.pageIntervalSeconds,
                                    seconds,
                                )
                            },
                            onSpeedChange = { speed ->
                                autoSpeedPxPerSecond = speed
                                autoPreferences?.setValue(
                                    ReaderAutoPreferences.scrollSpeedPxPerSecond,
                                    speed,
                                )
                            },
                        )
                        // 下载 / 流式状态（与设置面板工具区同一份状态文案）。
                        Row(
                            modifier =
                                Modifier.fillMaxWidth().padding(vertical = CinefinSpacing.Space2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "下载状态",
                                style = CinefinType.BodyMedium,
                                color = contentColor,
                                modifier = Modifier.weight(1f),
                            )
                            DownloadAction(
                                streamState = streamState,
                                downloadState = downloadState,
                                contentColor = contentColor,
                                onDownload = onDownload,
                            )
                        }
                        // W6 T9（READER-32）：听书入口（定稿一 §2「更多」末位）。EPUB 走 Readium 文本提取，
                        // PDF / CBZ 本波不提供（无 Readium 文本层），入口灰置。
                        MoreActionRow(
                            label = if (richDocument != null) "听书" else "听书（仅 EPUB）",
                            contentColor = contentColor,
                            enabled = richDocument != null,
                            onClick = {
                                showMore = false
                                ttsShowStart = true
                            },
                        )
                    }
                }
            }

            pendingAnnotation?.let { (pageIndex, rect) ->
                AnnotationNoteDialog(
                    title = "添加批注（第 ${pageIndex + 1} 页）",
                    initialNote = "",
                    canDelete = false,
                    onDismiss = { pendingAnnotation = null },
                    onConfirm = { note ->
                        onAddAnnotation(pageIndex, rect, note)
                        pendingAnnotation = null
                    },
                    onDelete = {},
                )
            }

            editingAnnotation?.let { annotation ->
                AnnotationNoteDialog(
                    title = "批注 · 第 ${annotation.pageIndex + 1} 页",
                    initialNote = annotation.note,
                    canDelete = true,
                    onDismiss = { editingAnnotation = null },
                    onConfirm = { note ->
                        onUpdateAnnotationNote(annotation.id, note)
                        editingAnnotation = null
                    },
                    onDelete = {
                        onRemoveAnnotation(annotation.id)
                        editingAnnotation = null
                    },
                )
            }

            // W6 T8：EPUB 标注列表（并入「更多」）。
            if (showEpubAnnotations) {
                ModalBottomSheet(
                    onDismissRequest = { showEpubAnnotations = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    EpubAnnotationListSheet(
                        annotations = epubAnnotations,
                        contentColor = contentColor,
                        onJump = { annotation ->
                            if (jumpToEpubAnnotation(annotation)) showEpubAnnotations = false
                        },
                        onRemove = { annotation -> removeEpubAnnotation(annotation) },
                    )
                }
            }

            // W6 T8：点已有高亮 → 底部小面板（改色 / 编辑笔记 / 删除 / 跳转）。
            epubActiveAnnotation?.let { annotation ->
                ModalBottomSheet(
                    onDismissRequest = { epubActiveAnnotation = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    EpubAnnotationActionsSheet(
                        annotation = annotation,
                        contentColor = contentColor,
                        onPickColor = { color -> recolorEpubAnnotation(annotation, color) },
                        onEditNote = {
                            epubNoteTarget = EpubNoteTarget.Existing(annotation)
                            epubActiveAnnotation = null
                        },
                        onJump = {
                            if (jumpToEpubAnnotation(annotation)) epubActiveAnnotation = null
                        },
                        onDelete = {
                            removeEpubAnnotation(annotation)
                            epubActiveAnnotation = null
                        },
                    )
                }
            }

            // W6 T8：新建 / 编辑 EPUB 笔记（想法）。新建时沿用工具栏选中的颜色。
            epubNoteTarget?.let { target ->
                when (target) {
                    is EpubNoteTarget.NewSelection ->
                        AnnotationNoteDialog(
                            title = "想法",
                            initialNote = "",
                            canDelete = false,
                            onDismiss = { epubNoteTarget = null },
                            onConfirm = { note ->
                                addEpubAnnotation(target.selection, target.color, note)
                                epubNoteTarget = null
                            },
                            onDelete = {},
                        )

                    is EpubNoteTarget.Existing ->
                        AnnotationNoteDialog(
                            title = "编辑笔记",
                            initialNote = target.annotation.noteText,
                            canDelete = false,
                            onDismiss = { epubNoteTarget = null },
                            onConfirm = { note ->
                                updateEpubAnnotationNote(target.annotation, note)
                                epubNoteTarget = null
                            },
                            onDelete = {},
                        )
                }
            }

            // W6 T9：听书弹层（起点二选一 / 语速与音色 / 定时关闭）收在 reader/tts 内，这里一行调用。
            ReaderTtsSheets(
                showStart = ttsShowStart,
                showSettings = ttsShowSettings,
                showTimer = ttsShowTimer,
                state = ttsState,
                voices = ttsVoices,
                selectedVoice = ttsSelectedVoice,
                tapFollowsReading = ttsTapFollowsReading,
                pauseOnTap = ttsPauseOnTap,
                chromeColor = chromeColor,
                contentColor = contentColor,
                accent = accent,
                onDismissStart = { ttsShowStart = false },
                onStart = startTts,
                onDismissSettings = { ttsShowSettings = false },
                onRateChange = { value ->
                    ttsController.setRate(value)
                    autoPreferences?.setValue(ReaderTtsPreferences.rate, value)
                },
                onVoiceSelected = { name ->
                    ttsSelectedVoice = name
                    ttsEngine.setVoice(name)
                    autoPreferences?.setValue(ReaderTtsPreferences.voice, name)
                },
                onTapFollowChange = { value ->
                    ttsTapFollowsReading = value
                    autoPreferences?.setValue(ReaderTtsPreferences.tapFollowsReading, value)
                },
                onPauseOnTapChange = { value ->
                    ttsPauseOnTap = value
                    autoPreferences?.setValue(ReaderTtsPreferences.pauseOnTap, value)
                },
                onDismissTimer = { ttsShowTimer = false },
                onTimerSelected = { option ->
                    ttsController.setTimer(option)
                    ttsShowTimer = false
                },
            )
        }
    }
}

/**
 * 阅读页顶栏状态区（W77-3 三态）。
 *
 * ①远端流式：「流式载入中…」/「流式阅读中」（右侧保留手动「下载」入口）； ②整本下载中：「下载中 xx%」（手动触发；未下载 PDF 等回退下载也走这条）； ③本地已有整本：「离线可读 ·
 * 大小」。分支决策在纯函数 [readerStatusBadge] 里，单测覆盖。
 */
@Composable
internal fun DownloadAction(
    streamState: ReaderStreamState,
    downloadState: BookDownloadState,
    contentColor: Color,
    onDownload: () -> Unit,
) {
    val badge = readerStatusBadge(streamState, downloadState)
    when (badge) {
        ReaderStatusBadge.StreamingLoading ->
            Text(
                text = "流式载入中…",
                style = CinefinType.LabelLarge,
                color = contentColor,
                modifier = Modifier.padding(horizontal = CinefinSpacing.Space2),
            )

        ReaderStatusBadge.StreamingReading ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "流式阅读中",
                    style = CinefinType.LabelLarge,
                    color = contentColor,
                    modifier = Modifier.padding(horizontal = CinefinSpacing.Space2),
                )
                CinefinButton(
                    text = "下载",
                    onClick = onDownload,
                    variant = CinefinButtonVariant.Text,
                    size = CinefinButtonSize.Small,
                )
            }

        // 其余分支由 downloadState 决定（与纯函数一一对应）：下载中 / 离线可读 / 重试下载 / 可下载。
        ReaderStatusBadge.Downloading,
        ReaderStatusBadge.OfflineReadable,
        ReaderStatusBadge.RetryDownload,
        ReaderStatusBadge.DownloadAction ->
            when (downloadState) {
                is BookDownloadState.Downloading ->
                    Text(
                        text = "下载中 ${downloadPercent(downloadState.progress)}%",
                        style = CinefinType.LabelLarge,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = CinefinSpacing.Space2),
                    )

                is BookDownloadState.Downloaded ->
                    Text(
                        text = "离线可读 · ${formatBookSize(downloadState.sizeBytes)}",
                        style = CinefinType.LabelLarge,
                        color = contentColor,
                        modifier = Modifier.padding(horizontal = CinefinSpacing.Space2),
                    )

                is BookDownloadState.Failed ->
                    CinefinButton(
                        text = "重试下载",
                        onClick = onDownload,
                        variant = CinefinButtonVariant.Text,
                        size = CinefinButtonSize.Small,
                    )

                BookDownloadState.NotDownloaded ->
                    CinefinButton(
                        text = "下载",
                        onClick = onDownload,
                        variant = CinefinButtonVariant.Text,
                        size = CinefinButtonSize.Small,
                    )
            }
    }
}

/**
 * PDF / CBZ 右下角悬浮缩放条（READER_PLAN §9）：`− 100% +`。
 *
 * 独立于顶栏与正文点击层：按钮按 25% 档位步进，1× 禁用 `−`、4× 禁用 `+`。约 [ZOOM_BAR_VISIBLE_MS] 无操作后淡出，单击正文空白重新唤回。
 */
@Composable
private fun ReaderZoomBar(
    percent: Int,
    canZoomOut: Boolean,
    canZoomIn: Boolean,
    containerColor: Color,
    contentColor: Color,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
) {
    Surface(
        shape = CinefinShapes.Button,
        color = containerColor.copy(alpha = 0.92f),
        contentColor = contentColor,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = CinefinSpacing.Space1),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CinefinButton(
                text = "−",
                onClick = onZoomOut,
                enabled = canZoomOut,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
            Text(
                text = "$percent%",
                style = CinefinType.LabelLarge,
                color = contentColor,
                modifier = Modifier.padding(horizontal = CinefinSpacing.Space2),
            )
            CinefinButton(
                text = "+",
                onClick = onZoomIn,
                enabled = canZoomIn,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
        }
    }
}

/** 离线暂存提示（EB-9）：断网期间的进度会在联网后自动回传。 */
@Composable
private fun PendingSyncBanner(
    pendingSyncCount: Int,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(color = containerColor, contentColor = contentColor) {
        Text(
            text = "离线暂存 $pendingSyncCount 条进度，联网后自动回传",
            style = CinefinType.LabelLarge,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        horizontal = CinefinSpacing.Space4,
                        vertical = CinefinSpacing.Space1,
                    ),
        )
    }
}

/** 双击书签提示胶囊的停留时长（定稿八：900ms 淡入淡出）。 */
private const val BOOKMARK_NOTICE_VISIBLE_MS = 900L

/** 提示胶囊淡入淡出时长。 */
private const val BOOKMARK_NOTICE_FADE_MS = 120

/** W6 T8：复制 / 词典降级提示的停留时长（与书签提示同量级）。 */
private const val EPUB_NOTICE_VISIBLE_MS = 900L

/** W6 T10：搜索命中临时高亮的停留时长（定稿八：3s 后撤除）。 */
private const val EPUB_SEARCH_HIGHLIGHT_VISIBLE_MS = 3_000L

/** W6 T10：选区「搜索」预填关键词的最大长度。 */
private const val EPUB_SEARCH_MAX_QUERY_CHARS = 60

/** W6 T10：临时命中高亮的 Decoration id（不落盘、不进标注列表）。 */
private const val EPUB_SEARCH_HIGHLIGHT_ID = "reader-search-hit"

/** W6 T10：导出时间文案格式（本地时区）。 */
private val EPUB_EXPORT_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** Readium 搜索定位 → 纯数据（片段 / 章节归因 / 跳转 JSON）。 */
private fun Locator.toEpubSearchLocator(): EpubSearchLocator? {
    val target = href.toString().takeIf { it.isNotBlank() } ?: return null
    return EpubSearchLocator(
        href = target,
        locatorJson = runCatching { toJSON().toString() }.getOrNull() ?: return null,
        before = text?.before.orEmpty(),
        highlight = text?.highlight.orEmpty(),
        after = text?.after.orEmpty(),
        progression = locations.totalProgression,
    )
}

/** 导出的 `.md` 落在 `filesDir/reader/exports/`（App 私有；不写服务器、不碰源文件）。 */
private fun writeAnnotationExport(context: Context, fileName: String, markdown: String): File {
    val dir = File(File(context.filesDir, "reader"), "exports")
    if (!dir.isDirectory && !dir.mkdirs()) error("导出目录不可写")
    val file = File(dir, fileName)
    file.writeText(markdown)
    return file
}

/** 分享导出内容：接收方直接拿到完整 Markdown 文本（文件保留在 exports 目录供回看）。 */
private fun shareAnnotationExport(
    context: Context,
    document: AnnotationExportDocument,
    markdown: String,
    file: File,
) {
    val send =
        Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, annotationExportShareSubject(document.bookTitle))
            putExtra(Intent.EXTRA_TEXT, markdown)
            putExtra(Intent.EXTRA_TITLE, file.name)
        }
    val chooser = Intent.createChooser(send, "导出标注")
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(chooser)
}

/** W6 T8：复制选中文字到系统剪贴板（空文本不写）。 */
private fun copyEpubSelection(context: Context, text: String) {
    val value = epubCopyableText(text)
    if (value.isEmpty()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("选中的文字", value))
}

/** 位置跳转载体的 id（只走跳转通道，不进书签列表）。 */
private const val READER_POSITION_BOOKMARK_ID = "reader-position"

/**
 * 位置跳转载体（W6 T2）：目录 / 进度条跳转复用书签跳转通道（阅读页不额外接线）。
 *
 * `locatorJson` 为空 = 按 progression 定位：PDF / CBZ 换成页索引；EPUB 用 publication 定位最近的 locator。
 */
private fun readerPositionBookmark(
    itemId: UUID,
    progression: Double,
    locatorJson: String?,
): ReaderBookmark =
    ReaderBookmark(
        id = READER_POSITION_BOOKMARK_ID,
        itemId = itemId,
        locatorJson = locatorJson.orEmpty(),
        progression = progression.coerceIn(0.0, 1.0),
        label = "",
        createdAt = Instant.EPOCH,
    )

/** 「更多」抽屉的一行动作：文字 + ≥48dp 触控区；`enabled = false` 时灰置（FXL 等不支持场景）。 */
@Composable
private fun MoreActionRow(
    label: String,
    contentColor: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .cinefinClickable(onClickLabel = label, enabled = enabled, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = CinefinType.BodyMedium,
            color = if (enabled) contentColor else contentColor.copy(alpha = 0.38f),
        )
    }
}

/** 书签面板（EB-8 基础版）：添加当前位置、跳转、删除。 */
@Composable
private fun BookmarkPanel(
    bookmarks: List<ReaderBookmark>,
    enabled: Boolean,
    contentColor: Color,
    onAddBookmark: () -> Unit,
    onRemoveBookmark: (String) -> Unit,
    onJumpToBookmark: (ReaderBookmark) -> Unit,
) {
    Column(
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space6)
                .padding(bottom = CinefinSpacing.Space6),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
    ) {
        Text(text = "书签", style = CinefinType.TitleMedium)
        Button(onClick = onAddBookmark, enabled = enabled) { Text("添加当前页书签") }
        HorizontalDivider(modifier = Modifier.padding(vertical = CinefinSpacing.Space2))
        if (bookmarks.isEmpty()) {
            Text(
                text = "还没有书签",
                style = CinefinType.BodyMedium,
                color = contentColor.copy(alpha = 0.7f),
                modifier = Modifier.padding(vertical = CinefinSpacing.Space3),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(items = bookmarks, key = { it.id }) { bookmark ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { onJumpToBookmark(bookmark) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = bookmark.label,
                                style = CinefinType.BodyMedium,
                                color = contentColor,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        TextButton(onClick = { onRemoveBookmark(bookmark.id) }) {
                            Text(text = "删除", color = contentColor)
                        }
                    }
                }
            }
        }
    }
}

/** 亮度提示条的停留时长（与播放器手势提示同量级，避免长期遮挡正文）。 */
private const val BRIGHTNESS_HUD_VISIBLE_MS = 900L

/** 悬浮缩放条无操作自动淡出时长（READER_PLAN §9：约 3 秒）。 */
private const val ZOOM_BAR_VISIBLE_MS = 3_000L

/** 缩放条淡出动画时长。 */
private const val ZOOM_BAR_FADE_MS = 280

/** 缩放条距底部的距离：让开底部的页指示。 */
private val ZOOM_BAR_BOTTOM_PADDING = 64.dp

/** 阅读页所属 Activity（Compose 视图上下文里逐层回找）。 */
private tailrec fun Context.readerActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.readerActivity()
        else -> null
    }

/** 当前窗口亮度（0–1）：本页覆盖过就用覆盖值，否则回落系统亮度（与播放器手势同口径）， 这样「上下滑调亮度」是从用户现在看到的亮度继续增减。 */
private fun readerWindowBrightness(activity: Activity?): Float {
    val window = activity?.window ?: return 1f
    val override = window.attributes.screenBrightness
    if (override in 0f..1f) return override
    val system = runCatching {
        Settings.System.getFloat(
            activity.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            255f,
        ) / 255f
    }
        .getOrDefault(1f)
    return system.coerceIn(ReaderBrightnessRange)
}

/** 亮度手势 HUD：居中胶囊 + 百分比（手势结束约 [BRIGHTNESS_HUD_VISIBLE_MS] 后自动隐藏）。 */
@Composable
private fun BrightnessHud(
    percent: Int,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = CinefinShapes.Button,
        color = containerColor.copy(alpha = 0.92f),
        contentColor = contentColor,
    ) {
        Text(
            text = "亮度 ${percent}%",
            style = CinefinType.TitleMedium,
            modifier =
                Modifier.padding(
                    horizontal = CinefinSpacing.Space5,
                    vertical = CinefinSpacing.Space3,
                ),
        )
    }
}

/** 自动阅读手动翻页宽限：自动翻页后的位置变化归自动翻页，超过该窗口的手动变化即暂停（W6 T6）。 */
private const val AUTO_MANUAL_GRACE_MS = 2_000L

/**
 * 「更多 → 自动阅读」分组（W6 T6 / READER-12）。
 *
 * 分页档 = 开关 + 间隔（5–120 秒）；滚动档 = 开关 + 速度（px/s，10–300）。暂停态给「继续」， 参数改动即时生效且不打断节拍。
 */
@Composable
private fun AutoAdvanceGroup(
    state: AutoAdvanceState,
    scrollMode: Boolean,
    intervalSeconds: Int,
    speedPxPerSecond: Float,
    enabled: Boolean,
    contentColor: Color,
    accent: Color,
    onAccent: Color,
    onToggle: (Boolean) -> Unit,
    onResume: () -> Unit,
    onIntervalChange: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit,
) {
    val active = state != AutoAdvanceState.Off
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (scrollMode) "自动滚动" else "自动翻页",
                style = CinefinType.BodyMedium,
                color = contentColor,
            )
            Text(
                text =
                    when (state) {
                        AutoAdvanceState.Off -> if (scrollMode) "按速度平滑向下滚动" else "按间隔自动翻到下一页"
                        AutoAdvanceState.Running -> "进行中 · 唤出控制层或手动翻页即暂停"
                        AutoAdvanceState.Paused -> "已暂停 · 手动干预后需点按继续"
                    },
                style = CinefinType.LabelSmall,
                color = contentColor.copy(alpha = 0.72f),
            )
        }
        CinefinSwitch(
            checked = active,
            onCheckedChange = onToggle,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedTrackColor = accent,
                    checkedThumbColor = onAccent,
                    checkedBorderColor = Color.Transparent,
                    uncheckedTrackColor = contentColor.copy(alpha = 0.16f),
                    uncheckedThumbColor = contentColor.copy(alpha = 0.72f),
                    uncheckedBorderColor = Color.Transparent,
                ),
        )
    }
    if (state == AutoAdvanceState.Paused) {
        MoreActionRow(label = "继续自动阅读", contentColor = accent, onClick = onResume)
    }
    if (scrollMode) {
        AutoAdvanceSliderRow(
            label = "速度",
            valueText = "${speedPxPerSecond.roundToInt()} px/s",
            value = speedPxPerSecond,
            range = AutoAdvanceMinSpeedPxPerSecond..AutoAdvanceMaxSpeedPxPerSecond,
            steps =
                ((AutoAdvanceMaxSpeedPxPerSecond - AutoAdvanceMinSpeedPxPerSecond) / 10f)
                    .roundToInt() - 1,
            enabled = enabled,
            contentColor = contentColor,
            accent = accent,
            onValueChange = { onSpeedChange((it / 10f).roundToInt() * 10f) },
        )
    } else {
        AutoAdvanceSliderRow(
            label = "间隔",
            valueText = "$intervalSeconds 秒",
            value = intervalSeconds.toFloat(),
            range =
                AutoAdvanceMinIntervalSeconds.toFloat()..AutoAdvanceMaxIntervalSeconds.toFloat(),
            steps = AutoAdvanceMaxIntervalSeconds - AutoAdvanceMinIntervalSeconds - 1,
            enabled = enabled,
            contentColor = contentColor,
            accent = accent,
            onValueChange = { onIntervalChange(it.roundToInt()) },
        )
    }
}

/** 自动阅读参数滑块行：与设置面板 `SliderRow` 同一视觉口径（标签 48dp、滑杆 ≤230dp、数值右对齐）。 */
@Composable
private fun AutoAdvanceSliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    contentColor: Color,
    accent: Color,
    onValueChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
    ) {
        Text(
            text = label,
            modifier = Modifier.width(48.dp),
            style = CinefinType.BodyMedium,
            color = contentColor,
        )
        CinefinSlider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f, fill = false).widthIn(max = 230.dp),
            colors =
                CinefinSliderColors(
                    activeTrackColor = accent,
                    inactiveTrackColor = contentColor.copy(alpha = 0.16f),
                    thumbColor = Color.White,
                    glowColor = accent.copy(alpha = 0.35f),
                ),
        )
        Text(
            text = valueText,
            style = CinefinType.LabelMedium,
            color = contentColor.copy(alpha = 0.72f),
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 56.dp),
        )
    }
}
