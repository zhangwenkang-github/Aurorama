package com.zhangwenkang.cinefin.presentation.film

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.R
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinBackToDefaultChip
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorChip
import com.zhangwenkang.cinefin.core.presentation.components.LibrarySelectorOption
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.components.cinefinSelectable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.local.localItemIdFor
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.SearchTopBarAction
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
import com.zhangwenkang.cinefin.presentation.film.components.RetainedAsyncImage
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID
import timber.log.Timber

/**
 * 顶部「书架」Tab 的落点页。
 *
 * 与媒体库总览同构的独立目的地：解析命中书籍库后直接复用 [LibraryScreen] 渲染书目； 没有书籍库（或全部为空）时给出空态，**不再回退媒体库总览**（2026-10-01
 * 验收缺陷修复）。
 */
@Composable
fun BookshelfScreen(
    /** 抽屉入口；null = 当前形态没有抽屉（手机 Compact，W6-R6N）。 */
    onOpenDrawer: (() -> Unit)?,
    onItemClick: (FindroidItem) -> Unit,
    navigateBack: () -> Unit,
    /** 临时库视图（W53 追加）：非空 = 侧栏点进来的书籍库 id，只加载该库。 */
    temporaryLibraryId: String? = null,
    /** 临时库视图的「返回默认 ×」/ 系统返回键动作（回默认书架）。 */
    onExitTemporaryLibrary: (() -> Unit)? = null,
    /** W60b：下载反馈 Snackbar「查看」→ 下载页。 */
    onOpenDownloads: () -> Unit = {},
    /** W75 #13：顶栏搜索入口（复用媒体库页现有搜索流程，导航由调用方负责）。 */
    onSearchClick: () -> Unit = {},
    /**
     * W6-T7：导入书籍 → 本地阅读器。参数 = (SAF 文档 URI, 显示名)。
     *
     * 只做持久授权 + 打开，**不拷贝源文件**；是否支持 TXT 由阅读器嗅探（BookFormat）决定。
     */
    onOpenLocalBook: (String, String) -> Unit = { _, _ -> },
    viewModel: BookshelfViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val librarySelection by viewModel.librarySelection.collectAsStateWithLifecycle()
    // W6-T10：最近阅读 / 本地书架 / 移出标记 / 条目附加信息。
    val extras by viewModel.extras.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var importError by remember { mutableStateOf<Throwable?>(null) }
    var shelfActionTarget by remember { mutableStateOf<BookshelfShelfTarget?>(null) }

    // 「导入书籍」= 系统文件选择器（EPUB / TXT）→ 持久授权 → 阅读器。不拷贝源文件（W6-T7 定稿七 §2）。
    val pickBook =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            val displayName = context.bookDisplayName(uri) ?: "book"
            val extension = displayName.substringAfterLast('.', "").lowercase()
            if (extension !in SUPPORTED_IMPORT_EXTENSIONS) {
                importError =
                    IllegalArgumentException(
                        context.getString(R.string.bookshelf_import_unsupported, displayName)
                    )
                return@rememberLauncherForActivityResult
            }
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
                .onFailure { Timber.w(it, "导入书籍：持久授权失败（%s）", uri) }
            // W6-T10：登记进本地书架（只存 URI / 显示名，不拷贝源文件）。
            viewModel.addLocalBook(uri.toString(), displayName.substringBeforeLast('.'))
            onOpenLocalBook(uri.toString(), displayName.substringBeforeLast('.'))
        }

    LaunchedEffect(temporaryLibraryId) { viewModel.load(libraryId = temporaryLibraryId) }

    // W6-T10：书架增强头部（最近阅读 / 本地书籍）。Ready 与占位态共用同一份；
    // 2.0.2 D3：Ready 态里它是内容流首项（随滚动离开），左右页边距由承载方传入（网格已带页边距 → 0dp）。
    val shelfHeader: (@Composable (PaddingValues) -> Unit)? =
        if (!bookshelfShelfHeaderVisible(extras.recent, extras.localBooks)) {
            null
        } else {
            { horizontalPadding ->
                BookshelfShelfHeader(
                    recent = extras.recent,
                    localBooks = extras.localBooks,
                    progressPercentByItemId = extras.progressPercentByItemId,
                    horizontalPadding = horizontalPadding,
                    onOpenRecent = { entry ->
                        val uri = entry.localUri
                        if (uri != null) {
                            onOpenLocalBook(uri, entry.title)
                        } else {
                            extras.itemsById[entry.itemId]?.let(onItemClick)
                        }
                    },
                    onOpenLocalBook = { book -> onOpenLocalBook(book.uri, book.name) },
                    onRecentLongPress = { entry ->
                        val uri = entry.localUri
                        if (uri != null) {
                            shelfActionTarget =
                                BookshelfShelfTarget.Local(
                                    BookshelfLocalBook(uri = uri, name = entry.title)
                                )
                        } else {
                            extras.itemsById[entry.itemId]?.let { item ->
                                shelfActionTarget = BookshelfShelfTarget.Server(item)
                            }
                        }
                    },
                    onLocalLongPress = { book ->
                        shelfActionTarget = BookshelfShelfTarget.Local(book)
                    },
                )
            }
        }

    // 临时库视图：返回键先退出临时库（回默认书架），再按一次才离开（用户 2026-10-03 口径）。
    BackHandler(
        enabled = temporaryLibraryId != null && onExitTemporaryLibrary != null,
        onBack = { onExitTemporaryLibrary?.invoke() },
    )

    Box(modifier = Modifier.fillMaxSize()) {
        when (val current = state) {
            is BookshelfState.Ready ->
                LibraryScreen(
                    libraryId = current.library.id,
                    libraryName = current.library.name,
                    libraryType = current.library.type,
                    onItemClick = onItemClick,
                    navigateBack = navigateBack,
                    // 顶层模式（W8-R3）：书架 Tab 进入时是"书架"自己的页面——侧栏入口 +「书架」标题，
                    // 不再复用二级库内容页的返回箭头与库名「书籍」。
                    topLevel = true,
                    onOpenDrawer = onOpenDrawer,
                    onBackToDefault = onExitTemporaryLibrary,
                    onOpenDownloads = onOpenDownloads,
                    // W6-T10：书架增强（作者 / 进度 / 最近阅读 / 移出标记）。
                    shelfMetaLine = { item -> extras.metaLineById[item.id] },
                    hiddenItemIds = extras.hiddenIds.toItemIdSet(),
                    headerContent = shelfHeader,
                    onItemLongPress = { item ->
                        shelfActionTarget = BookshelfShelfTarget.Server(item)
                    },
                    topBarActions = {
                        BookshelfTopBarActions(
                            selection = librarySelection,
                            showLibrarySelector = temporaryLibraryId == null,
                            onSelectLibrary = viewModel::selectLibrary,
                        )
                        SearchTopBarAction(onClick = onSearchClick)
                    },
                )
            else ->
                BookshelfPlaceholder(
                    state = current,
                    onOpenDrawer = onOpenDrawer,
                    onRetry = { viewModel.load(force = true, libraryId = temporaryLibraryId) },
                    onExitTemporaryLibrary = onExitTemporaryLibrary,
                    headerContent = shelfHeader,
                    topBarActions = {
                        BookshelfTopBarActions(
                            selection = librarySelection,
                            showLibrarySelector = temporaryLibraryId == null,
                            onSelectLibrary = viewModel::selectLibrary,
                        )
                        SearchTopBarAction(onClick = onSearchClick)
                    },
                )
        }

        BookshelfImportButton(
            onClick = { pickBook.launch(arrayOf("application/epub+zip", "text/plain", "text/*")) },
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }

    importError?.let { error ->
        ErrorDialog(exception = error, onDismissRequest = { importError = null })
    }

    // W6-T10：长按条目 = 移出书架（本地书）/ 详情；服务器书的「移出」只写本地隐藏标记。
    shelfActionTarget?.let { target ->
        BookshelfShelfActionSheet(
            target = target,
            onDismiss = { shelfActionTarget = null },
            onRemoveLocal = { book ->
                viewModel.removeLocalBook(book.uri)
                shelfActionTarget = null
            },
            onRemoveServer = { item ->
                viewModel.hideFromShelf(item.id)
                shelfActionTarget = null
            },
            onOpen = { target ->
                shelfActionTarget = null
                when (target) {
                    is BookshelfShelfTarget.Server -> onItemClick(target.item)
                    is BookshelfShelfTarget.Local ->
                        onOpenLocalBook(target.book.uri, target.book.name)
                }
            },
        )
    }
}

/** 书架增强的隐藏集合：偏好里存字符串，这里转回 UUID（脏值直接忽略）。 */
private fun Set<String>.toItemIdSet(): Set<UUID> =
    mapNotNullTo(mutableSetOf()) { raw -> runCatching { UUID.fromString(raw) }.getOrNull() }

/** 导入允许的扩展名（与任务卡 READER-37 一致：EPUB / TXT）。 */
private val SUPPORTED_IMPORT_EXTENSIONS = setOf("epub", "txt")

/**
 * 「导入书籍」FAB（W6-T7）：复用 [CinefinButton] 与媒体色 token，不新造视觉样式。
 *
 * 全系统无投影（CinefinShapes KDoc §5.2），靠填充媒体色在内容上分层；位置贴右下安全区。
 */
@Composable
private fun BookshelfImportButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val safePadding = rememberSafePadding()
    val pageGutter = rememberPageGutter()
    CinefinButton(
        text = stringResource(R.string.bookshelf_import_book),
        onClick = onClick,
        variant = CinefinButtonVariant.Filled,
        size = CinefinButtonSize.Medium,
        modifier =
            modifier.padding(
                end = safePadding.end + pageGutter,
                bottom = safePadding.bottom + pageGutter + CinefinSpacing.Space4,
            ),
        icon = { tint ->
            Icon(
                painter = painterResource(CoreR.drawable.ic_plus),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.padding(end = CinefinSpacing.Space2).size(18.dp),
            )
        },
    )
}

/** SAF 显示名（`OpenableColumns.DISPLAY_NAME`）；取不到回落 URI 末段。 */
private fun Context.bookDisplayName(uri: Uri): String? =
    runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            cursor ->
            if (cursor.moveToFirst()) cursor.getString(0)?.takeIf { it.isNotBlank() } else null
        }
    }
        .getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }

/**
 * 书架顶栏动作：库选择（服务器上有 2 个以上书籍库时才给入口）。
 *
 * 两处顶栏（库内容页头部 = Ready 态，页面占位头 = Loading / Empty / Failed 态）共用；临时库视图只显示路由指定的库，因此不出现选择器。
 */
@Composable
private fun RowScope.BookshelfTopBarActions(
    selection: BookshelfLibrarySelection,
    showLibrarySelector: Boolean,
    onSelectLibrary: (UUID?) -> Unit,
) {
    if (showLibrarySelector && selection.libraries.size >= 2) {
        val autoLabel = stringResource(FilmR.string.bookshelf_library_auto)
        val selectedLibrary = selection.libraries.firstOrNull { it.id == selection.selectedId }
        LibrarySelectorChip(
            label = selectedLibrary?.name ?: autoLabel,
            options =
                listOf(
                    LibrarySelectorOption(
                        id = null,
                        label = autoLabel,
                        detail = null,
                    )
                ) +
                    selection.libraries.map { library ->
                        LibrarySelectorOption(
                            id = library.id,
                            label = library.name,
                            detail =
                                library.itemCount?.let {
                                    stringResource(FilmR.string.library_item_count, it)
                                },
                        )
                    },
            selectedId = selection.selectedId,
            onSelect = onSelectLibrary,
        )
    }
}

/** 解析中 / 空态 / 失败态的页面骨架：顶栏保留抽屉入口，内容区居中反馈，不让用户困在空白页。 */
@Composable
private fun BookshelfPlaceholder(
    state: BookshelfState,
    onOpenDrawer: (() -> Unit)?,
    onRetry: () -> Unit,
    onExitTemporaryLibrary: (() -> Unit)? = null,
    /** W6-T10：无服务器书籍库时也要能看到最近阅读 / 本地书籍。 */
    headerContent: (@Composable (horizontalPadding: PaddingValues) -> Unit)? = null,
    topBarActions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val paddingStart = safePadding.start + pageGutter
    val paddingEnd = safePadding.end + pageGutter

    var showErrorDialog by rememberSaveable { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 与媒体库 / 音乐同一顶栏（W8-R3）：侧栏入口 +「书架」标题；解析中 / 空态 / 失败态共用。
        CinefinPageTopBar(
            title = stringResource(CoreR.string.title_book_shelf),
            onOpenDrawer = onOpenDrawer,
            modifier = Modifier.padding(start = safePadding.start),
            actions = {
                if (onExitTemporaryLibrary != null) {
                    CinefinBackToDefaultChip(onClick = onExitTemporaryLibrary)
                }
                topBarActions()
            },
        )
        Spacer(Modifier.height(CinefinSpacing.Space6))

        // W6-T10：占位态也显示书架增强头部（本地书 / 最近阅读不依赖服务器书籍库）。
        headerContent?.invoke(PaddingValues(start = paddingStart, end = paddingEnd))

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = paddingStart),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                BookshelfState.Loading -> CircularProgressIndicator(color = colors.onSurfaceVariant)
                BookshelfState.Empty ->
                    if (headerContent != null) {
                        // 本地书架已有内容：只提示服务器书籍库为空，不盖掉上半屏的本地书 / 最近阅读。
                        Text(
                            text = "服务器书籍库为空 · 可用右下角「导入书籍」添加本地书",
                            style = CinefinType.LabelMedium,
                            color = colors.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    } else {
                        CinefinEmptyState(
                            title = stringResource(FilmR.string.bookshelf_empty_title),
                            message = stringResource(FilmR.string.bookshelf_empty_message),
                            icon = { tint ->
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_book),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(44.dp),
                                )
                            },
                        )
                    }
                is BookshelfState.Failed -> {
                    ErrorCard(
                        onShowStacktrace = { showErrorDialog = true },
                        onRetryClick = onRetry,
                    )
                    if (showErrorDialog) {
                        ErrorDialog(
                            exception = state.error,
                            onDismissRequest = { showErrorDialog = false },
                        )
                    }
                }
                is BookshelfState.Ready -> Unit
            }
        }
    }
}

/** 书架长按目标：服务器书（详情 / 移出）或本地书（打开 / 移出登记）。 */
private sealed interface BookshelfShelfTarget {
    data class Server(val item: FindroidItem) : BookshelfShelfTarget

    data class Local(val book: BookshelfLocalBook) : BookshelfShelfTarget
}

/** 书架增强头部（W6-T10，定稿七 §3）：最近阅读行 + 本地书籍行，横滑卡片。 */
@Composable
private fun BookshelfShelfHeader(
    recent: List<BookshelfRecentEntry>,
    localBooks: List<BookshelfLocalBook>,
    progressPercentByItemId: Map<UUID, Int>,
    onOpenRecent: (BookshelfRecentEntry) -> Unit,
    onOpenLocalBook: (BookshelfLocalBook) -> Unit,
    onRecentLongPress: (BookshelfRecentEntry) -> Unit,
    onLocalLongPress: (BookshelfLocalBook) -> Unit,
    /** 本段自带的左右页边距：占位态传安全区 + pageGutter；作为网格 / 列表首项时传 0（承载方已经给过页边距）。 */
    horizontalPadding: PaddingValues,
) {
    val colors = LocalCinefinColors.current
    val layoutDirection = LocalLayoutDirection.current
    val startPadding = horizontalPadding.calculateStartPadding(layoutDirection)
    val endPadding = horizontalPadding.calculateEndPadding(layoutDirection)
    val sectionPadding = PaddingValues(start = startPadding, end = endPadding)

    Column(modifier = Modifier.fillMaxWidth()) {
        if (recent.isNotEmpty()) {
            Text(
                text = "最近阅读",
                style = CinefinType.LabelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = startPadding, end = endPadding),
            )
            Spacer(Modifier.height(CinefinSpacing.Space2))
            LazyRow(
                contentPadding = sectionPadding,
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
            ) {
                items(items = recent, key = { entry -> "recent-${entry.itemId}" }) { entry ->
                    BookshelfShelfCard(
                        title = entry.title,
                        coverUrl = entry.coverUrl,
                        progressPercent = entry.progressPercent,
                        onClick = { onOpenRecent(entry) },
                        onLongClick = { onRecentLongPress(entry) },
                    )
                }
            }
            // 段间距 = Space4；末段不留尾距（与后续内容的间距交给承载方的垂直 spacing）。
            if (localBooks.isNotEmpty()) Spacer(Modifier.height(CinefinSpacing.Space4))
        }
        if (localBooks.isNotEmpty()) {
            Text(
                text = "本地书籍",
                style = CinefinType.LabelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = startPadding, end = endPadding),
            )
            Spacer(Modifier.height(CinefinSpacing.Space2))
            LazyRow(
                contentPadding = sectionPadding,
                horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
            ) {
                items(items = localBooks, key = { book -> "local-${book.uri}" }) { book ->
                    BookshelfShelfCard(
                        title = book.name,
                        coverUrl = null,
                        progressPercent = progressPercentByItemId[localItemIdFor(book.uri)],
                        onClick = { onOpenLocalBook(book) },
                        onLongClick = { onLocalLongPress(book) },
                    )
                }
            }
        }
    }
}

/** 书架横滑卡片：封面（无封面用书籍占位图标）+ 书名 + 进度百分比。 */
@Composable
private fun BookshelfShelfCard(
    title: String,
    coverUrl: String?,
    progressPercent: Int?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(
        modifier =
            Modifier.width(BOOKSHELF_SHELF_CARD_WIDTH)
                .cinefinSelectable(
                    selected = false,
                    onClick = onClick,
                    onLongPress = onLongClick,
                )
    ) {
        LumenCardFrame(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            shape = CinefinShapes.Poster,
            container = colors.surfaceContainerHigh,
        ) {
            if (coverUrl.isNullOrBlank()) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_book),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant.copy(alpha = 0.64f),
                    modifier = Modifier.align(Alignment.Center).size(32.dp),
                )
            } else {
                RetainedAsyncImage(
                    model = coverUrl,
                    retainKey = coverUrl,
                    placeholderPainter = ColorPainter(colors.surfaceContainerHigh),
                    errorPainter = ColorPainter(colors.surfaceContainerHigh),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Spacer(Modifier.height(CinefinSpacing.Space2))
        Text(
            text = title,
            style = CinefinType.BodySmall,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = bookshelfProgressText(progressPercent),
            style = CinefinType.LabelMedium,
            color = colors.onSurfaceVariant,
        )
    }
}

/** 长按面板：服务器书 = 打开 / 移出书架（本地隐藏）；本地书 = 打开 / 移出登记（不删文件）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookshelfShelfActionSheet(
    target: BookshelfShelfTarget,
    onDismiss: () -> Unit,
    onRemoveLocal: (BookshelfLocalBook) -> Unit,
    onRemoveServer: (FindroidItem) -> Unit,
    onOpen: (BookshelfShelfTarget) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val title =
        when (target) {
            is BookshelfShelfTarget.Server -> target.item.name
            is BookshelfShelfTarget.Local -> target.book.name
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceContainerHigh,
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = CinefinSpacing.Space6)
                    .padding(bottom = CinefinSpacing.Space6),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
        ) {
            Text(text = title, style = CinefinType.TitleMedium, color = colors.onSurface)
            HorizontalDivider(modifier = Modifier.padding(vertical = CinefinSpacing.Space2))
            BookshelfActionRow(label = "打开", onClick = { onOpen(target) })
            when (target) {
                is BookshelfShelfTarget.Server ->
                    BookshelfActionRow(
                        label = "移出书架（只在本机隐藏，不修改服务器）",
                        onClick = { onRemoveServer(target.item) },
                    )
                is BookshelfShelfTarget.Local ->
                    BookshelfActionRow(
                        label = "移出书架（不删除本地文件）",
                        onClick = { onRemoveLocal(target.book) },
                    )
            }
        }
    }
}

@Composable
private fun BookshelfActionRow(label: String, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .heightIn(min = 48.dp)
                .cinefinClickable(onClickLabel = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = CinefinType.BodyMedium, color = colors.onSurface)
    }
}

/** 书架横滑卡片宽度（与「最近阅读」行一屏约 3 张的节奏）。 */
private val BOOKSHELF_SHELF_CARD_WIDTH = 104.dp
