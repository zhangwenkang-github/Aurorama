package com.zhangwenkang.cinefin.book.presentation.reader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID

/**
 * 阅读器入口（EB-10）。
 *
 * W3 R3 起由 `NavigationRoot` 的书籍条目以显式 Intent 打开（`EXTRA_ITEM_ID` / `EXTRA_TITLE`）， 已改回
 * `exported=false`； 调试期如需 adb 直启，临时改 manifest 后务必还原。
 */
@AndroidEntryPoint
class ReaderActivity : AppCompatActivity() {
    private val viewModel: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)?.toItemIdOrNull()
        // W37：本地媒体库书籍（SAF content://）；与 itemId 二选一，本地优先。
        val localUri = intent.getStringExtra(EXTRA_LOCAL_URI)?.let(android.net.Uri::parse)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "阅读器 PoC" }

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
            val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
            val pendingSyncCount by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
            val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()
            val searchState by viewModel.searchState.collectAsStateWithLifecycle()
            val annotations by viewModel.annotations.collectAsStateWithLifecycle()
            val pageJumpTarget by viewModel.pageJumpTarget.collectAsStateWithLifecycle()
            ReaderScreen(
                state = state,
                settings = settings,
                title = title,
                systemDark = isSystemInDarkTheme(),
                downloadState = downloadState,
                bookmarks = bookmarks,
                pendingSyncCount = pendingSyncCount,
                jumpTarget = jumpTarget,
                searchState = searchState,
                annotations = annotations,
                pageJumpTarget = pageJumpTarget,
                onSettingsChange = viewModel::updateSettings,
                onLocationChanged = viewModel::onLocationChanged,
                onSimplePageChanged = viewModel::onSimplePageChanged,
                onRetry = viewModel::retry,
                onDownload = viewModel::downloadBook,
                onAddBookmark = viewModel::addBookmark,
                onRemoveBookmark = viewModel::removeBookmark,
                onJumpToBookmark = viewModel::jumpTo,
                onJumpHandled = viewModel::consumeJumpTarget,
                onNavigatorReady = viewModel::onNavigatorLocator,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onCancelSearch = viewModel::cancelSearch,
                onJumpToHit = { hit -> viewModel.jumpToPage(hit.pageIndex) },
                onJumpToAnnotation = { annotation -> viewModel.jumpToPage(annotation.pageIndex) },
                onPageJumpHandled = viewModel::consumePageJumpTarget,
                onAddAnnotation = viewModel::addAnnotation,
                onUpdateAnnotationNote = viewModel::updateAnnotationNote,
                onRemoveAnnotation = viewModel::removeAnnotation,
            )
        }

        if (localUri != null) {
            viewModel.openLocal(localUri, title)
        } else if (itemId != null) {
            viewModel.open(itemId)
        }
    }

    /** 退到后台立即落盘 + 尝试回传（ARCHITECTURE §3.6 上报时机）。 */
    override fun onStop() {
        viewModel.onStopReading()
        super.onStop()
    }

    companion object {
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_TITLE = "title"
        /** W37：本地媒体库书籍的 SAF 文档 URI。 */
        const val EXTRA_LOCAL_URI = "localUri"
    }
}

/** Jellyfin REST 的 UUID 有两种写法：36 位标准格式与 32 位无连字符格式。 */
private fun String.toItemIdOrNull(): UUID? {
    if (matches(Regex("[0-9a-fA-F]{32}"))) {
        val dashed =
            "${substring(0, 8)}-${substring(8, 12)}-${substring(12, 16)}-" +
                "${substring(16, 20)}-${substring(20, 32)}"
        return runCatching { UUID.fromString(dashed) }.getOrNull()
    }
    return runCatching { UUID.fromString(this) }.getOrNull()
}
