package com.zhangwenkang.cinefin.presentation.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinListRow
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSegmentedControl
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.local.LocalFolderBrowseMode
import com.zhangwenkang.cinefin.local.LocalLibraryBrowse
import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalLibraryType
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.presentation.film.components.BaseBadge
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
import com.zhangwenkang.cinefin.presentation.film.components.SectionHeader
import com.zhangwenkang.cinefin.presentation.film.components.lumenEntrance
import com.zhangwenkang.cinefin.presentation.film.components.rememberLandscapeCardWidth
import com.zhangwenkang.cinefin.presentation.utils.rememberGridGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.io.File
import java.util.UUID

/**
 * 首页「本地媒体」区块：由 `pref_local_library_visible` 控制（默认关）。
 *
 * W45：与首页其它走廊同语言——**封面（库内首项缩略图）+ 库名 +「N 项 · 类型」+ 类型角标**， 卡片宽高 / 圆角 / 间距与「继续观看」横排一致；无封面回退类型图标。
 * 与库级「在媒体库显示」互不影响（后者只管媒体库总览）。
 *
 * 封面按需生成：库卡进入组合（可见）时才请求，见 [LocalLibraryViewModel.loadCover]。
 */
@Composable
fun HomeLocalMediaSection(
    onOpenLibrary: (Long) -> Unit,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // 开关关闭时不刷新 / 不生成封面（首页隐藏时不该有额外开销）。
    if (state.homeVisible) {
        LaunchedEffect(Unit) { viewModel.refresh() }
    }
    if (!state.homeVisible || state.cards.isEmpty()) return
    val gutter = rememberGridGutter()
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "本地媒体",
            modifier = Modifier.padding(bottom = CinefinSpacing.Space4),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(gutter)) {
            itemsIndexed(
                items = state.cards,
                key = { _, card -> "local-home-${card.id}" },
            ) { index, card ->
                HomeLocalLibraryCard(
                    card = card,
                    cover = state.covers[card.id],
                    index = index,
                    onClick = { onOpenLibrary(card.id) },
                    onCoverVisible = { viewModel.loadCover(card.id) },
                )
            }
        }
    }
}

/**
 * 首页本地库卡（W45）：与「继续观看」横排同宽同高（[rememberLandscapeCardWidth] + 16:9）、 同 `CinefinShapes.Md` 圆角；封面 +
 * 底部渐隐 + 库名 +「N 项 · 类型」+ 右上类型角标。
 */
@Composable
private fun HomeLocalLibraryCard(
    card: LocalLibraryViewModel.LibraryCard,
    cover: String?,
    index: Int,
    onClick: () -> Unit,
    onCoverVisible: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val width = rememberLandscapeCardWidth()
    // 条目数变化（新建库 → 扫描完成）后重新请求封面。
    LaunchedEffect(card.id, card.itemCount) { onCoverVisible() }
    LumenCardFrame(
        modifier =
            Modifier.width(width).aspectRatio(16f / 9f).lumenEntrance(index).cinefinClickable {
                onClick()
            },
        container = colors.surfaceContainerHigh,
    ) {
        if (cover != null) {
            AsyncImage(
                model = localCoverModel(cover),
                contentDescription = card.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier =
                    Modifier.fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.36f to Color.Transparent,
                                0.72f to Color.Black.copy(alpha = 0.42f),
                                1f to Color.Black.copy(alpha = 0.88f),
                            )
                        )
            )
        } else {
            // 无封面：回退类型图标（不引入额外色块）。
            Box(
                modifier = Modifier.fillMaxSize().background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(card.type.iconRes()),
                    contentDescription = card.type.label,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        TypeBadge(type = card.type, modifier = Modifier.align(Alignment.TopEnd))
        Column(
            modifier =
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(
                        start = CinefinSpacing.Space4,
                        end = CinefinSpacing.Space4,
                        bottom = CinefinSpacing.Space3,
                    ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = card.name,
                style = CinefinType.WideCardTitle,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${card.itemCount} 项 · ${card.type.label}",
                style = CinefinType.BodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 库卡右上角类型角标（§8.9 中性徽标：黑 62% 底 + 白 12% 描边）。 */
@Composable
private fun TypeBadge(type: LocalLibraryType, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    BaseBadge(modifier = modifier.padding(CinefinSpacing.Space3)) {
        Row(
            modifier =
                Modifier.padding(
                    horizontal = CinefinSpacing.Space2,
                    vertical = CinefinSpacing.Space1,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
        ) {
            Icon(
                painter = painterResource(type.iconRes()),
                contentDescription = null,
                tint = colors.onSurface,
                modifier = Modifier.size(14.dp),
            )
            Text(text = type.label, style = CinefinType.LabelSmall, color = colors.onSurface)
        }
    }
}

/** W45：本地条目 / 库卡缩略图（默认 40dp 正方形，`corner-xs`）；无图回退类型图标。 */
@Composable
internal fun LocalThumbnailTile(
    cover: String?,
    iconRes: Int,
    iconDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    shape: Shape = CinefinShapes.Xs,
    /** W53B：宽高分开（本地库卡 16:9）；默认都等于 [size]，既有调用点不变。 */
    width: Dp = size,
    height: Dp = size,
) {
    val colors = LocalCinefinColors.current
    Box(
        modifier =
            modifier
                .size(width = width, height = height)
                .clip(shape)
                .background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            AsyncImage(
                model = localCoverModel(cover),
                contentDescription = iconDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = iconDescription,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/**
 * W45：缩略图 → Coil 模型。缩略图是**文件绝对路径**（`files/local_thumbs/<itemId>.jpg` / 音乐内嵌封面）， 交给 Coil 3 时转成
 * [File] 走 `FileMapper`（`file://`）；`content://`（同目录封面）原样传递。
 */
private fun localCoverModel(cover: String?): Any? =
    when {
        cover.isNullOrBlank() -> null
        cover.startsWith("/") -> File(cover)
        else -> cover
    }

/**
 * W39 本地媒体库总览（嵌在媒体库页 / 离线媒体库页里，**在线离线都常显**）：
 * - 标题行右侧「＋ 新建」紧凑入口：名称 + 类型 + SAF 文件夹（持久化权限）；
 * - 库卡列表 + 管理视图（显示「在媒体库显示」关掉的库）；
 * - 无库时只留一行空态；「只建立索引、不复制、不移动源文件」的说明移入新建对话框。
 *
 * 左右页边距由调用方承担（媒体库页由栅格 `contentPadding` 负责），组件自身不加内边距， 保证在线 / 离线两处卡片边缘一致。
 */
@Composable
fun LocalLibrarySection(
    onOpenLibrary: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** W70：本地库集合 / 可见性变化后通知侧栏只读刷新（新建库后 SAF 返回不触发导航变化，侧轨不刷新）。 */
    onLibrariesChanged: () -> Unit = {},
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    // W70：库集合（新建 / 删除）或可见性变化 → 侧栏「本地媒体库」子分组跟着刷新（真机拦下：新建库后
    // SAF 返回时导航不变，只靠 navBackStackEntry 触发会漏掉这一次）。
    LaunchedEffect(state.cards, state.hasHidden) { onLibrariesChanged() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingLibraryId by rememberSaveable { mutableStateOf<Long?>(null) }
    // W70：SAF 回调可能早于 RESUMED 分发——直接 navigate 会被 Navigation 忽略（真机拦下：建库后不跳详情页）。
    // 改为状态驱动：先记下待打开的库，等回到组合（已 RESUMED）后再导航。
    var openAfterFolderPick by rememberSaveable { mutableStateOf<Long?>(null) }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val libraryId = pendingLibraryId
            pendingLibraryId = null
            if (uri != null && libraryId != null) {
                viewModel.addFolder(libraryId, uri)
                openAfterFolderPick = libraryId
            }
        }
    LaunchedEffect(pendingLibraryId) { if (pendingLibraryId != null) folderPicker.launch(null) }
    LaunchedEffect(openAfterFolderPick) {
        val libraryId = openAfterFolderPick ?: return@LaunchedEffect
        openAfterFolderPick = null
        onOpenLibrary(libraryId)
    }

    val colors = LocalCinefinColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        ) {
            Text(
                text = "本地媒体库",
                style = CinefinType.SectionTitle,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (state.hasHidden) {
                CinefinIconButton(onClick = { viewModel.setShowHidden(!state.showHidden) }) { tint
                    ->
                    Icon(
                        painter =
                            painterResource(
                                if (state.showHidden) CoreR.drawable.ic_eye
                                else CoreR.drawable.ic_eye_off
                            ),
                        contentDescription = if (state.showHidden) "隐藏已关闭的本地库" else "显示已关闭的本地库",
                        tint = tint,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            CinefinButton(
                text = "＋ 新建",
                onClick = { showCreateDialog = true },
                variant = CinefinButtonVariant.Outlined,
                size = CinefinButtonSize.Small,
            )
        }
        Spacer(Modifier.height(CinefinSpacing.Space3))
        when {
            // 无库：只留一行空态（W39 收掉整块说明与整行大卡）
            state.cards.isEmpty() ->
                Text(
                    text = "还没有本地媒体库 · 点「＋ 新建」",
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                )
            // 有库但全被「在媒体库显示」关掉：提示进管理视图（点标题行右侧「眼睛」）
            state.visibleCards.isEmpty() ->
                Text(
                    text = "本地媒体库都已隐藏 · 点右上角「眼睛」查看",
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                )
            else ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
                ) {
                    state.visibleCards.forEach { card ->
                        LocalLibraryCard(
                            card = card,
                            cover = state.covers[card.id],
                            onCoverVisible = { viewModel.loadCover(card.id) },
                            onClick = { onOpenLibrary(card.id) },
                        )
                    }
                }
        }
    }

    if (showCreateDialog) {
        CreateLibraryDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, type ->
                showCreateDialog = false
                viewModel.createLibrary(name, type) { id -> pendingLibraryId = id }
            },
        )
    }
}

/** W53B：本地库卡缩略图 16:9（宽 100dp，落在「约 96–104dp」档内）。 */
private val LocalLibraryCardThumbnailWidth = 100.dp
private val LocalLibraryCardThumbnailHeight = LocalLibraryCardThumbnailWidth * 9f / 16f

@Composable
private fun LocalLibraryCard(
    card: LocalLibraryViewModel.LibraryCard,
    cover: String?,
    onCoverVisible: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    // W45：库卡可见时才生成封面（懒生成 + 失败回退类型图标）。
    LaunchedEffect(card.id, card.itemCount) { onCoverVisible() }
    CinefinCard(onClick = onClick, contentPadding = PaddingValues(CinefinSpacing.Space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // W53B：本地库卡保持紧凑行卡，缩略图放大到 16:9（宽 100dp ≈ 96–104dp 档）；服务器库卡不动。
            LocalThumbnailTile(
                cover = cover,
                iconRes = card.type.iconRes(),
                iconDescription = card.type.label,
                iconSize = 24.dp,
                width = LocalLibraryCardThumbnailWidth,
                height = LocalLibraryCardThumbnailHeight,
            )
            Spacer(Modifier.width(CinefinSpacing.Space3))
            Column(Modifier.weight(1f)) {
                Text(
                    text = card.name,
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = card.detail,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (card.itemCount > 0) {
                Spacer(Modifier.width(CinefinSpacing.Space2))
                Text(
                    text = "${card.itemCount} 项",
                    style = CinefinType.MonoDataSmall,
                    color = colors.onSurfaceFaint,
                )
            }
        }
    }
}

/** 本地库详情：库级设置 + 文件夹（层级 / 平铺）+ 条目浏览 + 打开链路。 */
@Composable
fun LocalLibraryDetailScreen(
    libraryId: Long,
    onBack: () -> Unit,
    onPlayVideo: (UUID) -> Unit,
    onOpenBook: (itemId: UUID, title: String, documentUri: String) -> Unit,
    onMusicStarted: () -> Unit,
    /** W53B：库级设置（「在媒体库显示」开关 / 重命名 / 条目数）变化后通知侧栏只读刷新。 */
    onLocalLibrariesChanged: () -> Unit = {},
    viewModel: LocalLibraryDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(libraryId) { viewModel.setup(libraryId) }
    // W53B：本地库行的名称 / 项目数 /「在媒体库显示」都在这页改——变化后通知侧栏（平板侧轨常显）。
    LaunchedEffect(state.visibleInLibrary, state.name, state.itemCount) {
        onLocalLibrariesChanged()
    }

    val colors = LocalCinefinColors.current
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val pageGutter = rememberPageGutter()
    val horizontalPadding = safePadding.start + pageGutter

    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }
    var folderToRemove by remember {
        mutableStateOf<LocalLibraryDetailViewModel.FolderContent?>(null)
    }
    var pendingAddFolder by remember { mutableStateOf(false) }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            pendingAddFolder = false
            if (uri != null) viewModel.addFolder(uri)
        }
    LaunchedEffect(pendingAddFolder) { if (pendingAddFolder) folderPicker.launch(null) }

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        CinefinPageTopBar(
            title = state.name.ifBlank { "本地媒体库" },
            subtitle = "共 ${state.itemCount} 项 · ${state.folderCount} 个文件夹",
            onBack = onBack,
            modifier = Modifier.padding(start = safePadding.start),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = horizontalPadding),
            contentPadding = PaddingValues(bottom = CinefinSpacing.Space8),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        ) {
            // W45：详情头部封面（库内首项缩略图；无图回退类型图标）。
            item(key = "cover") {
                LocalLibraryHeaderCover(
                    cover = state.headerCover,
                    type = state.type,
                    itemCount = state.itemCount,
                    onCoverVisible = viewModel::loadHeaderCover,
                )
            }
            item(key = "settings") {
                LibrarySettingsCard(
                    name = state.name,
                    type = state.type,
                    visibleInLibrary = state.visibleInLibrary,
                    onRename = { renameOpen = true },
                    onSelectType = viewModel::setType,
                    onVisibleChange = viewModel::setVisible,
                    onRescan = viewModel::rescan,
                    onDelete = { deleteOpen = true },
                )
            }
            items(items = state.folders, key = { folder -> "folder-${folder.folderId}" }) { folder
                ->
                FolderCard(
                    folder = folder,
                    onBrowseModeChange = { mode -> viewModel.setBrowseMode(folder.folderId, mode) },
                    onRemove = { folderToRemove = folder },
                )
            }
            item(key = "add-folder") {
                CinefinButton(
                    text = "＋ 添加文件夹",
                    onClick = { pendingAddFolder = true },
                    variant = CinefinButtonVariant.Outlined,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.folders.isEmpty()) {
                item(key = "empty") {
                    CinefinEmptyState(
                        title = "还没有添加文件夹",
                        message = "点「＋ 添加文件夹」选择设备上的目录；App 只读取索引，不会改动源文件。",
                        modifier = Modifier.fillMaxWidth(),
                        icon = { tint ->
                            Icon(
                                painter = painterResource(CoreR.drawable.ic_library),
                                contentDescription = null,
                                tint = tint,
                                modifier = Modifier.size(44.dp),
                            )
                        },
                    )
                }
            }
            state.folders.forEach { folder ->
                item(key = "content-header-${folder.folderId}") { ContentSectionHeader(folder) }
                folder.groups.forEachIndexed { groupIndex, group ->
                    if (group.title != null) {
                        item(key = "group-$groupIndex-${folder.folderId}") {
                            Text(
                                text = group.title,
                                style = CinefinType.LabelMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = CinefinSpacing.Space2),
                            )
                        }
                    }
                    itemsIndexed(
                        items = group.rows,
                        key = { index, _ -> "row-${folder.folderId}-$groupIndex-$index" },
                    ) { _, row ->
                        val entry = (row as? LocalLibraryBrowse.Row.Entry)?.entry
                        BrowseRow(
                            row = row,
                            thumbnail = entry?.let { state.thumbs[it.itemId] },
                            onThumbnailVisible = { entry?.let(viewModel::loadThumbnail) },
                            onOpenEntry = { entry ->
                                when (entry.kind) {
                                    LocalMediaKind.VIDEO -> onPlayVideo(entry.itemId)
                                    LocalMediaKind.BOOK ->
                                        onOpenBook(
                                            entry.itemId,
                                            entry.displayName,
                                            entry.documentUri,
                                        )
                                    LocalMediaKind.MUSIC ->
                                        viewModel.playMusic(
                                            folderId = folder.folderId,
                                            itemId = entry.itemId,
                                            onStarted = onMusicStarted,
                                        )
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (renameOpen) {
        RenameLibraryDialog(
            initialName = state.name,
            onDismiss = { renameOpen = false },
            onConfirm = { name ->
                renameOpen = false
                viewModel.rename(name)
            },
        )
    }
    if (deleteOpen) {
        UnlinkConfirmDialog(
            title = "删除媒体库「${state.name}」？",
            message = "只会删除 App 里的媒体库与文件夹关联，**设备上的源文件不会被删除**。之后可以重新建立。",
            confirmText = "删除媒体库",
            onConfirm = {
                deleteOpen = false
                viewModel.deleteLibrary(onDeleted = onBack)
            },
            onDismiss = { deleteOpen = false },
        )
    }
    folderToRemove?.let { folder ->
        UnlinkConfirmDialog(
            title = "移除文件夹「${folder.name}」？",
            message = "只会解除关联并从索引里移除，**文件夹与源文件不会被删除**。",
            confirmText = "移除文件夹",
            onConfirm = {
                viewModel.removeFolder(folder.folderId)
                folderToRemove = null
            },
            onDismiss = { folderToRemove = null },
        )
    }
}

@Composable
private fun LibrarySettingsCard(
    name: String,
    type: LocalLibraryType,
    visibleInLibrary: Boolean,
    onRename: () -> Unit,
    onSelectType: (LocalLibraryType) -> Unit,
    onVisibleChange: (Boolean) -> Unit,
    onRescan: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(contentPadding = PaddingValues(CinefinSpacing.Space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = CinefinType.TitleMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TextButton(onClick = onRename) { Text("重命名") }
            CinefinIconButton(onClick = onRescan) { tint ->
                Icon(
                    painter = painterResource(CoreR.drawable.ic_rotate_ccw),
                    contentDescription = "重新扫描",
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(CinefinSpacing.Space2))
        Text(text = "类型", style = CinefinType.LabelMedium, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(CinefinSpacing.Space1))
        CinefinSegmentedControl(
            items = LocalLibraryType.entries,
            selected = type,
            onSelect = onSelectType,
            label = { it.label },
        )
        Spacer(Modifier.height(CinefinSpacing.Space3))
        SwitchSettingRow(
            title = "在媒体库显示",
            detail = "关闭后不在媒体库总览出现（数据保留）",
            checked = visibleInLibrary,
            onCheckedChange = onVisibleChange,
        )
        Spacer(Modifier.height(CinefinSpacing.Space2))
        TextButton(onClick = onDelete) { Text(text = "删除媒体库", color = colors.error) }
    }
}

@Composable
private fun FolderCard(
    folder: LocalLibraryDetailViewModel.FolderContent,
    onBrowseModeChange: (LocalFolderBrowseMode) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    CinefinCard(contentPadding = PaddingValues(CinefinSpacing.Space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${folder.itemCount} 个文件",
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemove) { Text(text = "移除", color = colors.error) }
        }
        Spacer(Modifier.height(CinefinSpacing.Space2))
        CinefinSegmentedControl(
            items = LocalFolderBrowseMode.entries,
            selected = folder.browseMode,
            onSelect = onBrowseModeChange,
            label = { it.shortLabel },
        )
        Spacer(Modifier.height(CinefinSpacing.Space1))
        Text(
            text = folder.browseMode.label,
            style = CinefinType.BodySmall,
            color = colors.onSurfaceFaint,
        )
    }
}

@Composable
private fun ContentSectionHeader(folder: LocalLibraryDetailViewModel.FolderContent) {
    val colors = LocalCinefinColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = CinefinSpacing.Space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = folder.name,
            style = CinefinType.TitleMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = folder.browseMode.shortLabel,
            style = CinefinType.LabelSmall,
            color = colors.onSurfaceFaint,
        )
    }
}

@Composable
private fun BrowseRow(
    row: LocalLibraryBrowse.Row,
    thumbnail: String?,
    onThumbnailVisible: () -> Unit,
    onOpenEntry: (LocalLibraryEntry) -> Unit,
) {
    val colors = LocalCinefinColors.current
    when (row) {
        is LocalLibraryBrowse.Row.Folder ->
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = CinefinSpacing.Space4 * row.depth,
                            top = CinefinSpacing.Space2,
                        ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "📁 ${row.title}",
                    style = CinefinType.BodyMedium,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${row.fileCount} 个文件",
                    style = CinefinType.MonoDataSmall,
                    color = colors.onSurfaceFaint,
                )
            }
        is LocalLibraryBrowse.Row.Entry -> {
            // W45：行可见时按需生成缩略图（视频首帧 / 书籍首页 / 音乐沿用既有封面）。
            LaunchedEffect(row.entry.itemId) { onThumbnailVisible() }
            CinefinListRow(
                title = row.entry.displayName,
                secondary = entryDetail(row.entry),
                onClick = { onOpenEntry(row.entry) },
                modifier = Modifier.padding(start = CinefinSpacing.Space4 * row.depth),
                leading = {
                    LocalThumbnailTile(
                        cover = thumbnail,
                        iconRes = row.entry.kind.iconRes(),
                        iconDescription = row.entry.kind.label,
                    )
                },
            )
        }
    }
}

/** W45 本地库详情头部封面：16:9 封面通栏裁切（高度 140dp），无图回退类型图标。 */
@Composable
private fun LocalLibraryHeaderCover(
    cover: String?,
    type: LocalLibraryType,
    itemCount: Int,
    onCoverVisible: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    // 条目数变化（新增文件夹 → 扫描完成）后重新请求。
    LaunchedEffect(type, itemCount) { onCoverVisible() }
    Box(
        modifier =
            Modifier.fillMaxWidth()
                .height(140.dp)
                .clip(CinefinShapes.Md)
                .background(colors.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            AsyncImage(
                model = localCoverModel(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                painter = painterResource(type.iconRes()),
                contentDescription = type.label,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

@Composable
private fun SwitchSettingRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = CinefinType.BodyMedium, color = colors.onSurface)
            Text(text = detail, style = CinefinType.BodySmall, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(CinefinSpacing.Space3))
        CinefinSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CreateLibraryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, LocalLibraryType) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf(LocalLibraryType.MIXED) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("建立本地媒体库") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(CinefinSpacing.Space3))
                Text(
                    text = "类型（混合库按文件类型自动分组）",
                    style = CinefinType.BodySmall,
                    color = LocalCinefinColors.current.onSurfaceVariant,
                )
                Spacer(Modifier.height(CinefinSpacing.Space2))
                CinefinSegmentedControl(
                    items = LocalLibraryType.entries,
                    selected = type,
                    onSelect = { type = it },
                    label = { it.label },
                )
                Spacer(Modifier.height(CinefinSpacing.Space3))
                Text(
                    text = "只建立索引，不复制、不移动源文件。",
                    style = CinefinType.BodySmall,
                    color = LocalCinefinColors.current.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "本地媒体库" }, type) }) { Text("创建并选择文件夹") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameLibraryDialog(
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名媒体库") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { initialName }) }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun UnlinkConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalCinefinColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = confirmText, color = colors.error) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/** 本地库类型 → 图标（侧栏「本地媒体库」子分组行与库卡共用同一份映射）。 */
internal fun LocalLibraryType.iconRes(): Int =
    when (this) {
        LocalLibraryType.VIDEO -> CoreR.drawable.ic_video
        LocalLibraryType.MUSIC -> CoreR.drawable.ic_music
        LocalLibraryType.BOOK -> CoreR.drawable.ic_book
        LocalLibraryType.MIXED -> CoreR.drawable.ic_library
    }

internal fun LocalMediaKind.iconRes(): Int =
    when (this) {
        LocalMediaKind.VIDEO -> CoreR.drawable.ic_video
        LocalMediaKind.MUSIC -> CoreR.drawable.ic_music
        LocalMediaKind.BOOK -> CoreR.drawable.ic_book
    }

private fun entryDetail(entry: LocalLibraryEntry): String =
    listOfNotNull(
            if (entry.kind == LocalMediaKind.MUSIC) entry.artist else null,
            sizeText(entry.sizeBytes),
            if (entry.kind == LocalMediaKind.MUSIC) formatDuration(entry.durationMs) else null,
        )
        .filter { it.isNotBlank() }
        .joinToString(" · ")

internal fun sizeText(sizeBytes: Long): String {
    if (sizeBytes <= 0L) return "未知大小"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = sizeBytes.toDouble()
    var index = 0
    while (value >= 1024.0 && index < units.lastIndex) {
        value /= 1024.0
        index++
    }
    return if (index == 0) "${sizeBytes} ${units[index]}"
    else String.format("%.1f %s", value, units[index])
}

internal fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "—"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
