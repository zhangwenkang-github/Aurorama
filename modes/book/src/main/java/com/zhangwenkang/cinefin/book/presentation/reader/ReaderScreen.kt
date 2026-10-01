package com.zhangwenkang.cinefin.book.presentation.reader

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    systemDark: Boolean,
    downloadState: BookDownloadState,
    bookmarks: List<ReaderBookmark>,
    pendingSyncCount: Int,
    jumpTarget: Locator?,
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
) {
    var showSettings by remember { mutableStateOf(false) }
    var showBookmarks by remember { mutableStateOf(false) }
    val bookmarksAvailable = (state as? ReaderUiState.Ready)?.document is ReaderDocument.Rich
    /** 右起翻页（RTL）只对页序列文档（PDF / CBZ）开放；EPUB 的阅读方向由 Readium 出版物元数据决定。 */
    val pagingDirectionAvailable =
        (state as? ReaderUiState.Ready)?.document is ReaderDocument.Simple

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
                        settings = settings,
                        contentColor = contentColor,
                        chromeColor = chromeColor,
                        downloadState = downloadState,
                        onDownload = onDownload,
                        bookmarksEnabled = bookmarksAvailable,
                        onOpenBookmarks = { showBookmarks = true },
                        onCycleMode = {
                            onSettingsChange(settings.copy(mode = settings.mode.next()))
                        },
                        onOpenSettings = { showSettings = true },
                    )

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
                                            modifier = Modifier.fillMaxSize(),
                                        )

                                    is ReaderDocument.Simple ->
                                        SimpleBookView(
                                            document = document,
                                            settings = settings,
                                            systemDark = systemDark,
                                            contentColor = contentColor,
                                            chromeColor = chromeColor,
                                            onPageChanged = onSimplePageChanged,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                }
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
        }
    }
}

/**
 * 阅读页顶栏：底 = 阅读器外壳色，标题 + 下载状态 + 「书签」+ 模式切换 + 「Aa」排版面板入口。
 *
 * 下载状态（EB-11）与书签（EB-8）是 W3-R1 并入 Prism 外壳的离线能力。
 */
@Composable
private fun ReaderTopBar(
    title: String,
    settings: ReaderSettings,
    contentColor: Color,
    chromeColor: Color,
    downloadState: BookDownloadState,
    onDownload: () -> Unit,
    bookmarksEnabled: Boolean,
    onOpenBookmarks: () -> Unit,
    onCycleMode: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(chromeColor)) {
        Row(
            modifier =
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = CinefinSpacing.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = CinefinType.TitleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
            DownloadAction(
                downloadState = downloadState,
                contentColor = contentColor,
                onDownload = onDownload,
            )
            if (bookmarksEnabled) {
                Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
                CinefinButton(
                    text = "书签",
                    onClick = onOpenBookmarks,
                    variant = CinefinButtonVariant.Text,
                    size = CinefinButtonSize.Small,
                )
            }
            Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
            CinefinButton(
                text = settings.mode.label,
                onClick = onCycleMode,
                variant = CinefinButtonVariant.Outlined,
                size = CinefinButtonSize.Small,
            )
            Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
            CinefinButton(
                text = "Aa",
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

/** 离线阅读（EB-11）状态：未下载可点下载，下载中显示进度，已下载显示"离线可读"。 */
@Composable
private fun DownloadAction(
    downloadState: BookDownloadState,
    contentColor: Color,
    onDownload: () -> Unit,
) {
    when (downloadState) {
        BookDownloadState.NotDownloaded ->
            CinefinButton(
                text = "下载",
                onClick = onDownload,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )

        is BookDownloadState.Downloading ->
            Text(
                text = "下载中 ${(downloadState.progress * 100).toInt()}%",
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
