package com.zhangwenkang.cinefin.book.presentation.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.repository.ReaderBookmark
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * 阅读页（§9 Compose 落地）：外壳（顶栏 / 加载 / 错误态 / 设置面板）全部走 Prism 组件。
 *
 * 阅读主题（纸色 / 护眼 / 深色 / OLED / 跟随）驱动 `CinefinTheme` 的亮暗与底色； 纸色 / 护眼主题内媒体色整体替换为纸页棕（§8.14），所有控件通过
 * `LocalMediaColors` 自动取色。
 *
 * W3-R1 在 Prism 外壳上并入离线能力：顶栏下载状态 / 书签入口、离线暂存横幅、书签面板与跳转。
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
) {
    var showSettings by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showAnnotations by remember { mutableStateOf(false) }
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
    val bookmarksAvailable = (state as? ReaderUiState.Ready)?.document is ReaderDocument.Rich
    /** 右起翻页（RTL）只对页序列文档（PDF / CBZ）开放；EPUB 的阅读方向由 Readium 出版物元数据决定。 */
    val pagingDirectionAvailable =
        (state as? ReaderUiState.Ready)?.document is ReaderDocument.Simple
    /** PDF 搜索 / 批注：只对 PDF 开放（CBZ 走同一页模型，但本波未列入验收范围）。 */
    val pdfToolsAvailable =
        ((state as? ReaderUiState.Ready)?.document as? ReaderDocument.Simple)?.format ==
            SimpleBookFormat.Pdf

    CinefinTheme(
        domain = ContentDomain.Book,
        darkTheme = settings.isDark(systemDark),
        surfaceBackground = false,
    ) {
        val contentColor = settings.contentColor(systemDark)
        val chromeColor = settings.chromeColor(systemDark)
        val accent = settings.accentColor(systemDark)

        CompositionLocalProvider(LocalMediaColors provides settings.mediaColors(systemDark)) {
            Surface(modifier = Modifier.fillMaxSize(), color = settings.surfaceColor(systemDark)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ReaderTopBar(
                        title = title,
                        contentColor = contentColor,
                        chromeColor = chromeColor,
                        onBack = onBack,
                        onOpenSettings = { showSettings = true },
                    )

                    if (annotating) {
                        Surface(color = chromeColor, contentColor = contentColor) {
                            AnnotateHintBar(
                                contentColor = contentColor,
                                accent = accent,
                                onStop = { annotating = false },
                            )
                        }
                    }

                    if (pendingSyncCount > 0) {
                        PendingSyncBanner(
                            pendingSyncCount = pendingSyncCount,
                            containerColor = chromeColor,
                            contentColor = contentColor,
                        )
                    }

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
                                        ReadiumEpubView(
                                            publication = document.publication,
                                            initialLocator = document.initialLocator,
                                            settings = settings,
                                            systemDark = systemDark,
                                            jumpTarget = jumpTarget,
                                            onLocationChanged = onLocationChanged,
                                            onJumpHandled = onJumpHandled,
                                            onNavigatorReady = onNavigatorReady,
                                            brightnessProvider = { brightness },
                                            onBrightness = onBrightnessChange,
                                            onToggleTwoColumn = {
                                                onSettingsChange(
                                                    settings.copy(
                                                        mode =
                                                            if (
                                                                settings.mode ==
                                                                    ReaderMode.TwoColumn
                                                            )
                                                                ReaderMode.Paged
                                                            else ReaderMode.TwoColumn
                                                    )
                                                )
                                            },
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                    is ReaderDocument.Simple ->
                                        SimpleBookView(
                                            document = document,
                                            settings = settings,
                                            systemDark = systemDark,
                                            contentColor = contentColor,
                                            chromeColor = chromeColor,
                                            bookZoom = bookZoom,
                                            searchHits = searchState.hits,
                                            annotations = annotations,
                                            annotating = annotating,
                                            jumpTargetPage = pageJumpTarget,
                                            onPageChanged = onSimplePageChanged,
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
                                            modifier = Modifier.fillMaxSize(),
                                        )
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
                            settings = settings,
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
        }
    }
}

/**
 * 阅读页顶栏：只保留 **返回 + 书名 + 设置**（READER_PLAN §9 2026-10-18）。
 *
 * 下载、搜索、批注、书签、模式、翻页方向与显示设置全部收进「阅读设置」面板，顶栏不再承载工具入口。
 */
@Composable
private fun ReaderTopBar(
    title: String,
    contentColor: Color,
    chromeColor: Color,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(chromeColor)) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .padding(horizontal = CinefinSpacing.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CinefinButton(
                text = "返回",
                onClick = onBack,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
            Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = CinefinType.TitleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
            CinefinButton(
                text = "设置",
                onClick = onOpenSettings,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
        }
        Box(
            modifier =
                Modifier.fillMaxWidth().height(1.dp).background(contentColor.copy(alpha = 0.12f))
        )
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
        shape = CinefinShapes.Sm,
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
        shape = CinefinShapes.Sm,
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
