package com.zhangwenkang.cinefin.presentation.film

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.components.ErrorDialog
import com.zhangwenkang.cinefin.presentation.components.SearchTopBarAction
import com.zhangwenkang.cinefin.presentation.film.components.ErrorCard
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
    val context = LocalContext.current
    var importError by remember { mutableStateOf<Throwable?>(null) }

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
            onOpenLocalBook(uri.toString(), displayName.substringBeforeLast('.'))
        }

    LaunchedEffect(temporaryLibraryId) { viewModel.load(libraryId = temporaryLibraryId) }

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
                    // 顶栏「库选择」+「收藏」（W54-C）：与视频页同一套动作，落点由库内容页头部注入。
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
}

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

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = paddingStart),
            contentAlignment = Alignment.Center,
        ) {
            when (state) {
                BookshelfState.Loading -> CircularProgressIndicator(color = colors.onSurfaceVariant)
                BookshelfState.Empty ->
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
