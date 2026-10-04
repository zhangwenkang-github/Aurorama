package com.zhangwenkang.cinefin.presentation.offline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyContainer
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyFlattener
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyLeaf
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchyRow
import com.zhangwenkang.cinefin.film.presentation.downloads.DownloadHierarchySubContainer
import com.zhangwenkang.cinefin.presentation.local.LocalLibrarySection
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.ImageCacheRules
import com.zhangwenkang.cinefin.utils.OfflineMediaEntryKind
import java.util.UUID

/** W36：离线模式的常驻提示条（说明当前只显示已下载内容）。 */
@Composable
private fun OfflineNotice(message: String = "离线模式 · 仅显示本机已下载内容；退出离线模式后恢复服务器内容") {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .background(colors.surfaceContainer)
                .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(CoreR.drawable.ic_server_off),
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(CinefinSpacing.Space2))
        Text(text = message, style = CinefinType.BodySmall, color = colors.onSurfaceVariant)
    }
}

/**
 * 离线首页：已下载内容入口（视频 / 音乐 / 书籍）+ 空态 + 网络恢复提示。
 *
 * W37「本地文件库」入口在这里预留占位（见 DOWNLOAD_PLAN §2.2 第 8/9 条）。
 */
@Composable
fun OfflineHomeScreen(
    onOpenDrawer: (() -> Unit)?,
    onOpenVideos: () -> Unit,
    onOpenMusic: () -> Unit,
    onOpenBooks: () -> Unit,
    /** 有可用登录会话时的「退出离线模式」；无账号离线时传 null。 */
    onExitOffline: (() -> Unit)? = null,
    /** 设备上已配置服务器时的「登录到服务器」入口（登录成功会自动退出离线模式）；无服务器传 null。 */
    onOpenLogin: (() -> Unit)? = null,
    viewModel: OfflineMediaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 每次进入页面都重读本机下载（下载完成后立即反映；VM 单例只用于共享开关状态）。
    LaunchedEffect(Unit) { viewModel.load(force = true) }

    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val horizontalPadding = safePadding.start + pageGutter

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        CinefinPageTopBar(
            title = "离线模式",
            subtitle = "视频 ${state.videoCount} · 音乐 ${state.musicCount} · 书籍 ${state.bookCount}",
            onOpenDrawer = onOpenDrawer,
            modifier = Modifier.padding(start = safePadding.start),
        )
        OfflineNotice()
        Spacer(Modifier.height(CinefinSpacing.Space4))
        if (!state.loading && !state.hasAnyVisible) {
            CinefinEmptyState(
                title = "还没有可离线使用的内容",
                message = "联网后打开媒体详情页，点「下载」把内容存到本机；之后断网 / 退出登录也能在这里播放与阅读。",
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                icon = { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_download),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(44.dp),
                    )
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding =
                    PaddingValues(horizontal = horizontalPadding, vertical = CinefinSpacing.Space2),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
            ) {
                item {
                    OfflineEntryCard(
                        title = "视频",
                        detail = "已下载 ${state.videoCount} 个条目",
                        iconRes = CoreR.drawable.ic_film,
                        onClick = onOpenVideos,
                    )
                }
                item {
                    OfflineEntryCard(
                        title = "音乐",
                        detail = "已下载 ${state.musicCount} 首",
                        iconRes = CoreR.drawable.ic_music,
                        onClick = onOpenMusic,
                    )
                }
                item {
                    OfflineEntryCard(
                        title = "书籍",
                        detail = "已下载 ${state.bookCount} 本",
                        iconRes = CoreR.drawable.ic_book,
                        onClick = onOpenBooks,
                    )
                }
                if (onExitOffline != null) {
                    item {
                        CinefinButton(
                            text = "退出离线模式",
                            onClick = onExitOffline,
                            modifier = Modifier.fillMaxWidth(),
                            variant = CinefinButtonVariant.Outlined,
                            size = CinefinButtonSize.Medium,
                        )
                    }
                }
                if (onOpenLogin != null) {
                    item {
                        CinefinButton(
                            text = "登录到服务器",
                            onClick = onOpenLogin,
                            modifier = Modifier.fillMaxWidth(),
                            variant = CinefinButtonVariant.Text,
                            size = CinefinButtonSize.Medium,
                        )
                    }
                }
                // W37 预留：本地文件库（SAF 添加文件夹 / 平铺或层级浏览）。
                item {
                    CinefinCard(contentPadding = PaddingValues(CinefinSpacing.Space4)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_library),
                                contentDescription = null,
                                tint = colors.onSurfaceFaint,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(Modifier.width(CinefinSpacing.Space3))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "本地文件库",
                                    style = CinefinType.BodyLarge,
                                    color = colors.onSurfaceFaint,
                                )
                                Text(
                                    text = "即将推出：添加本机文件夹（视频 / 音乐 / 书籍）",
                                    style = CinefinType.BodySmall,
                                    color = colors.onSurfaceFaint,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineEntryCard(
    title: String,
    detail: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(onClick = onClick, contentPadding = PaddingValues(CinefinSpacing.Space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(CinefinSpacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = CinefinType.BodyLarge, color = colors.onSurface)
                Text(text = detail, style = CinefinType.BodySmall, color = colors.onSurfaceVariant)
            }
        }
    }
}

/**
 * 离线媒体库（离线模式的「媒体库」Tab）：已下载媒体的层级列表。
 *
 * - 「允许离线模式观看」开关：条目行右侧；关闭后条目从默认视图消失，顶栏「眼睛」切到管理视图可重新打开；
 * - 点视频条目直接起播本地文件（PlayerActivity 队列链路会自动优先 LOCAL 来源）；
 * - 点音乐曲目把所在专辑入队起播（本地文件）；点书籍直接打开本机阅读器。
 */
@Composable
fun OfflineLibraryScreen(
    onOpenDrawer: (() -> Unit)?,
    onPlayVideo: (itemId: UUID, isEpisode: Boolean) -> Unit,
    onOpenBook: (itemId: UUID, title: String) -> Unit,
    /** W37：本地媒体库详情（离线同样常显入口）。 */
    onOpenLocalLibrary: (Long) -> Unit = {},
    /** W70：本地库集合 / 可见性变化 → 侧栏「本地媒体库」子分组只读刷新。 */
    onLocalLibrariesChanged: () -> Unit = {},
    viewModel: OfflineMediaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load(force = true) }

    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val horizontalPadding = safePadding.start + pageGutter
    var expandedKeys by remember { mutableStateOf(emptySet<String>()) }
    val containers =
        remember(state.entries, state.showHidden) {
            // W36 补充要求：离线媒体库只展示「已下载且允许离线的节目（视频）」+ 本地媒体库（W37 占位）。
            OfflineMediaVisibility.buildHierarchy(
                OfflineMediaVisibility.videoOnly(state.entries),
                state.showHidden,
            )
        }
    val rows =
        remember(containers, expandedKeys) {
            DownloadHierarchyFlattener.flatten(containers, expandedKeys)
        }

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        CinefinPageTopBar(
            title = "已下载媒体",
            subtitle = "视频 ${state.videoCount} · 音乐 ${state.musicCount} · 书籍 ${state.bookCount}",
            onOpenDrawer = onOpenDrawer,
            modifier = Modifier.padding(start = safePadding.start),
            actions = {
                CinefinIconButton(onClick = { viewModel.setShowHidden(!state.showHidden) }) { tint
                    ->
                    Icon(
                        painter =
                            painterResource(
                                if (state.showHidden) CoreR.drawable.ic_eye
                                else CoreR.drawable.ic_eye_off
                            ),
                        contentDescription = if (state.showHidden) "隐藏已关闭条目" else "显示已关闭条目",
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            },
        )
        OfflineNotice(
            message =
                if (state.showHidden) "离线模式 · 管理视图（含已关闭离线访问的条目）" else "离线模式 · 仅显示已允许离线观看的已下载内容"
        )
        Spacer(Modifier.height(CinefinSpacing.Space2))
        when {
            state.loading ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.onSurfaceVariant)
                }
            rows.isEmpty() ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = CinefinSpacing.Space8),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                ) {
                    item(key = "offline-empty") {
                        CinefinEmptyState(
                            title = "没有可离线观看的视频",
                            message =
                                if (
                                    !state.showHidden &&
                                        state.entries.any { it.kind == OfflineMediaEntryKind.VIDEO }
                                ) {
                                    "已下载节目都被关闭了离线观看；点右上角「眼睛」进入管理视图重新打开。"
                                } else {
                                    "联网后打开节目详情页，点「下载」把剧集存到本机；音乐与书籍在各自入口查看。"
                                },
                            modifier =
                                Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                            icon = { tint ->
                                Icon(
                                    painter = painterResource(CoreR.drawable.ic_download),
                                    contentDescription = null,
                                    tint = tint,
                                    modifier = Modifier.size(44.dp),
                                )
                            },
                        )
                    }
                    // W37：离线模式同样常显「＋ 建立本地媒体库」（不依赖服务器 / 账号）。
                    item(key = "local_library_section") {
                        LocalLibrarySection(
                            onOpenLibrary = onOpenLocalLibrary,
                            onLibrariesChanged = onLocalLibrariesChanged,
                            modifier = Modifier.padding(horizontal = horizontalPadding),
                        )
                    }
                }
            else ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
                    contentPadding = PaddingValues(bottom = CinefinSpacing.Space8),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                ) {
                    items(rows, key = { row -> row.key }) { row ->
                        when (row) {
                            is DownloadHierarchyRow.ContainerRow ->
                                OfflineContainerCard(
                                    container = row.container,
                                    depth = row.depth,
                                    collapsed = row.collapsed,
                                    showHidden = state.showHidden,
                                    onClick = {
                                        expandedKeys =
                                            if (row.container.key in expandedKeys) {
                                                expandedKeys - row.container.key
                                            } else {
                                                expandedKeys + row.container.key
                                            }
                                    },
                                    onToggleAllow = { allow ->
                                        viewModel.setAllowOffline(
                                            itemIds = row.container.descendantItemIds(),
                                            allow = allow,
                                        )
                                    },
                                    onPlayVideo = onPlayVideo,
                                    onOpenBook = onOpenBook,
                                    onPlayMusic = viewModel::playMusic,
                                )
                            is DownloadHierarchyRow.ChildContainerRow ->
                                OfflineSubContainerCard(
                                    title = row.container.title,
                                    detail = row.container.detail.orEmpty(),
                                    collapsed = row.collapsed,
                                    imageUri = row.container.imageUri,
                                    onClick = {
                                        expandedKeys =
                                            if (row.container.key in expandedKeys) {
                                                expandedKeys - row.container.key
                                            } else {
                                                expandedKeys + row.container.key
                                            }
                                    },
                                )
                            is DownloadHierarchyRow.ItemRow ->
                                OfflineLeafCard(
                                    title = row.entry.name,
                                    detail =
                                        offlineLeafDetail(
                                            row.entry.mediaKind,
                                            row.entry.sizeBytes,
                                            row.entry.runtimeTicks,
                                        ),
                                    allowOffline = row.entry.allowOffline,
                                    depth = row.depth,
                                    iconRes = row.entry.mediaKind.iconRes(),
                                    imageUri = row.entry.imageUri,
                                    onClick = {
                                        when (row.entry.mediaKind) {
                                            DownloadMediaKind.VIDEO ->
                                                onPlayVideo(
                                                    row.entry.itemId,
                                                    row.entry.seriesId != null,
                                                )
                                            DownloadMediaKind.MUSIC ->
                                                viewModel.playMusic(row.entry.itemId)
                                            DownloadMediaKind.BOOK ->
                                                onOpenBook(row.entry.itemId, row.entry.name)
                                        }
                                    },
                                    onToggleAllow = { allow ->
                                        viewModel.setAllowOffline(row.entry.itemId, allow)
                                    },
                                )
                        }
                    }
                    // W37：本地媒体库（离线常显入口；索引只读，不依赖服务器）。
                    item(key = "local_library_section") {
                        LocalLibrarySection(
                            onOpenLibrary = onOpenLocalLibrary,
                            onLibrariesChanged = onLocalLibrariesChanged,
                        )
                    }
                }
        }
    }
}

/** 离线书架（离线模式的「书架」Tab）：本机已下载书籍，点击直接进阅读器。 */
@Composable
fun OfflineShelfScreen(
    onOpenDrawer: (() -> Unit)?,
    onOpenBook: (itemId: UUID, title: String) -> Unit,
    viewModel: OfflineMediaViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load(force = true) }

    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val horizontalPadding = safePadding.start + pageGutter
    // W62：书籍不过滤「允许离线」（无管理视图入口），与下载页「已完成 · 书籍」清单一致。
    val books = state.downloadedBooks

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        CinefinPageTopBar(
            title = "书架",
            subtitle = "离线模式 · 已下载 ${books.size} 本",
            onOpenDrawer = onOpenDrawer,
            modifier = Modifier.padding(start = safePadding.start),
        )
        OfflineNotice(message = "离线模式 · 仅显示已下载书籍，点击直接阅读")
        Spacer(Modifier.height(CinefinSpacing.Space2))
        if (!state.loading && books.isEmpty()) {
            CinefinEmptyState(
                title = "还没有下载的书籍",
                message = "联网后在书籍详情页下载，之后断网也能打开阅读。",
                modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
                icon = { tint ->
                    Icon(
                        painter = painterResource(CoreR.drawable.ic_book),
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(44.dp),
                    )
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
                contentPadding = PaddingValues(bottom = CinefinSpacing.Space8),
                verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
            ) {
                items(books, key = { book -> book.itemId.toString() }) { book ->
                    OfflineLeafCard(
                        title = book.name,
                        detail =
                            offlineLeafDetail(
                                DownloadMediaKind.BOOK,
                                book.sizeBytes,
                                book.runtimeTicks,
                            ),
                        allowOffline = book.allowOffline,
                        depth = 0,
                        iconRes = CoreR.drawable.ic_book,
                        imageUri = book.imageUri,
                        onClick = { onOpenBook(book.itemId, book.name) },
                        onToggleAllow = { allow -> viewModel.setAllowOffline(book.itemId, allow) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OfflineContainerCard(
    container: DownloadHierarchyContainer,
    depth: Int,
    collapsed: Boolean,
    showHidden: Boolean,
    onClick: () -> Unit,
    onToggleAllow: (Boolean) -> Unit,
    onPlayVideo: (UUID, Boolean) -> Unit,
    onOpenBook: (UUID, String) -> Unit,
    onPlayMusic: (UUID) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val entries = container.descendantEntries()
    val allAllowed = entries.isNotEmpty() && entries.all { it.allowOffline }
    val singleLeaf = container.children.singleOrNull() as? DownloadHierarchyLeaf
    // 电影 / 书籍容器只有一条内容：点卡片直接播放 / 阅读，不做展开。
    val directLeaf = singleLeaf?.takeIf {
        container.mediaKind == DownloadMediaKind.BOOK ||
            (container.mediaKind == DownloadMediaKind.VIDEO && singleLeaf.entry.seriesId == null)
    }
    CinefinCard(
        onClick = {
            when {
                directLeaf == null -> onClick()
                directLeaf.entry.mediaKind == DownloadMediaKind.BOOK ->
                    onOpenBook(directLeaf.entry.itemId, directLeaf.entry.name)
                else -> onPlayVideo(directLeaf.entry.itemId, false)
            }
        },
        contentPadding = PaddingValues(CinefinSpacing.Space4),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width((depth * 8).dp))
            OfflineArtwork(
                imageUri = container.imageUri,
                iconRes = container.mediaKind.iconRes(),
                size = 44.dp,
            )
            Spacer(Modifier.width(CinefinSpacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = container.title,
                    style = CinefinType.BodyLarge,
                    color =
                        if (!allAllowed && !showHidden) colors.onSurfaceFaint else colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = containerDetailText(container),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(CinefinSpacing.Space2))
            CinefinSwitch(checked = allAllowed, onCheckedChange = onToggleAllow)
            if (directLeaf == null) {
                Icon(
                    painter =
                        painterResource(
                            if (collapsed) CoreR.drawable.ic_chevron_down
                            else CoreR.drawable.ic_chevron_up
                        ),
                    contentDescription = if (collapsed) "展开" else "折叠",
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun OfflineSubContainerCard(
    title: String,
    detail: String,
    collapsed: Boolean,
    imageUri: String? = null,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(onClick = onClick, contentPadding = PaddingValues(CinefinSpacing.Space3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OfflineArtwork(imageUri = imageUri, iconRes = CoreR.drawable.ic_film, size = 36.dp)
            Spacer(Modifier.width(CinefinSpacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = CinefinType.BodyLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Icon(
                painter =
                    painterResource(
                        if (collapsed) CoreR.drawable.ic_chevron_down
                        else CoreR.drawable.ic_chevron_up
                    ),
                contentDescription = if (collapsed) "展开" else "折叠",
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun OfflineLeafCard(
    title: String,
    detail: String,
    allowOffline: Boolean,
    depth: Int,
    iconRes: Int,
    imageUri: String? = null,
    onClick: () -> Unit,
    onToggleAllow: (Boolean) -> Unit,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(onClick = onClick, contentPadding = PaddingValues(CinefinSpacing.Space3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width((depth * 8).dp))
            OfflineArtwork(
                imageUri = imageUri,
                iconRes = iconRes,
                size = 40.dp,
                tint = if (allowOffline) colors.onSurfaceVariant else colors.onSurfaceFaint,
            )
            Spacer(Modifier.width(CinefinSpacing.Space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = CinefinType.BodyLarge,
                    color = if (allowOffline) colors.onSurface else colors.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(text = detail, style = CinefinType.BodySmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.width(CinefinSpacing.Space2))
            CinefinSwitch(checked = allowOffline, onCheckedChange = onToggleAllow)
        }
    }
}

private fun DownloadHierarchyContainer.descendantEntries() = children.flatMap { child ->
    when (child) {
        is DownloadHierarchyLeaf -> listOf(child.entry)
        is DownloadHierarchySubContainer -> child.children.map { it.entry }
    }
}

private fun DownloadHierarchyContainer.descendantItemIds(): List<UUID> =
    descendantEntries().map { it.itemId }

private fun containerDetailText(container: DownloadHierarchyContainer): String =
    listOfNotNull(
            container.detail,
            sizeText(container.sizeBytes).takeIf { container.sizeBytes > 0 },
        )
        .joinToString(" · ")

private fun offlineLeafDetail(
    kind: DownloadMediaKind,
    sizeBytes: Long,
    runtimeTicks: Long = 0L,
): String =
    listOfNotNull(
            when (kind) {
                DownloadMediaKind.VIDEO -> "本地文件"
                DownloadMediaKind.MUSIC -> "本地音频"
                DownloadMediaKind.BOOK -> "本地书籍"
            },
            runtimeText(runtimeTicks),
            sizeText(sizeBytes).takeIf { sizeBytes > 0 },
        )
        .joinToString(" · ")

/** Jellyfin ticks（1 tick = 100 ns）→ 可读时长；无数据返回 null。 */
private fun runtimeText(ticks: Long): String? {
    if (ticks <= 0L) return null
    val totalSeconds = ticks / 10_000_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    return if (hours > 0) "$hours 小时 $minutes 分" else "$minutes 分钟"
}

/** W36：离线封面 / 缩略图（本地缓存图）；无图时按类型图标占位。 */
@Composable
private fun OfflineArtwork(
    imageUri: String?,
    iconRes: Int,
    size: androidx.compose.ui.unit.Dp,
    tint: Color = LocalCinefinColors.current.onSurfaceVariant,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    Box(
        modifier =
            Modifier.size(size).clip(CinefinShapes.Xs).background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (imageUri != null) {
            // W66：本地缓存路径的内存键带 mtime——过期替换（原子改名）后 key 变化，立即显示新图。
            val request =
                remember(imageUri) {
                    ImageCacheRules.artworkMemoryCacheKey(imageUri)?.let { key ->
                        ImageRequest.Builder(context).data(imageUri).memoryCacheKey(key).build()
                    } ?: imageUri
                }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(size / 2),
            )
        }
    }
}

private fun DownloadMediaKind.iconRes(): Int =
    when (this) {
        DownloadMediaKind.VIDEO -> CoreR.drawable.ic_film
        DownloadMediaKind.MUSIC -> CoreR.drawable.ic_music
        DownloadMediaKind.BOOK -> CoreR.drawable.ic_book
    }

private fun sizeText(bytes: Long): String =
    when {
        bytes >= 1_073_741_824 -> "%.2f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
