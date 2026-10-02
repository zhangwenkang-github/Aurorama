package com.zhangwenkang.cinefin.presentation.local

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinCard
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.components.CinefinIconButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinListRow
import com.zhangwenkang.cinefin.core.presentation.components.CinefinPageTopBar
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSegmentedControl
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.local.LocalFolderBrowseMode
import com.zhangwenkang.cinefin.local.LocalLibraryBrowse
import com.zhangwenkang.cinefin.local.LocalLibraryEntry
import com.zhangwenkang.cinefin.local.LocalLibraryType
import com.zhangwenkang.cinefin.local.LocalMediaKind
import com.zhangwenkang.cinefin.presentation.film.components.SectionHeader
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
import java.util.UUID

/**
 * 首页「本地媒体」区块：由 `pref_local_library_visible` 控制（默认关）。
 *
 * 开关打开且存在本地库时渲染一排库卡（与库级「在媒体库显示」互不影响：后者只管媒体库总览）， 点击进本地库详情。
 */
@Composable
fun HomeLocalMediaSection(
    onOpenLibrary: (Long) -> Unit,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }
    if (!state.homeVisible || state.cards.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(
            title = "本地媒体",
            modifier = Modifier.padding(bottom = CinefinSpacing.Space3),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3)) {
            items(items = state.cards, key = { card -> "local-home-${card.id}" }) { card ->
                CinefinCard(
                    onClick = { onOpenLibrary(card.id) },
                    contentPadding = PaddingValues(CinefinSpacing.Space3),
                ) {
                    Column(Modifier.width(200.dp)) {
                        Text(
                            text = card.name,
                            style = CinefinType.TitleMedium,
                            color = LocalCinefinColors.current.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${card.type.label} · ${card.summary}",
                            style = CinefinType.BodySmall,
                            color = LocalCinefinColors.current.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * W37 本地媒体库总览（嵌在媒体库页 / 离线媒体库页里，**在线离线都常显**）：
 * - 「＋ 建立本地媒体库」入口：名称 + 类型 + SAF 文件夹（持久化权限）；
 * - 库卡列表 + 管理视图（显示「在媒体库显示」关掉的库）；
 * - 首页本地媒体开关（`pref_local_library_visible`，默认关）。
 */
@Composable
fun LocalLibrarySection(
    onOpenLibrary: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LocalLibraryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refresh() }

    var showCreateDialog by remember { mutableStateOf(false) }
    var pendingLibraryId by rememberSaveable { mutableStateOf<Long?>(null) }
    val folderPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            val libraryId = pendingLibraryId
            pendingLibraryId = null
            if (uri != null && libraryId != null) {
                viewModel.addFolder(libraryId, uri)
                onOpenLibrary(libraryId)
            }
        }
    LaunchedEffect(pendingLibraryId) { if (pendingLibraryId != null) folderPicker.launch(null) }

    val colors = LocalCinefinColors.current
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CinefinSpacing.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "本地媒体库",
                style = CinefinType.TitleLarge,
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
        }
        Text(
            text = "把设备上已有的文件夹加入 App：只建立索引，不复制、不移动源文件。",
            style = CinefinType.BodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = CinefinSpacing.Space4),
        )
        Spacer(Modifier.height(CinefinSpacing.Space3))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = CinefinSpacing.Space4),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space3),
        ) {
            LocalLibraryEntryCard(
                title = "＋ 建立本地媒体库",
                detail = "选择名称与类型，再选一个或多个文件夹",
                onClick = { showCreateDialog = true },
            )
            state.visibleCards.forEach { card ->
                LocalLibraryCard(card = card, onClick = { onOpenLibrary(card.id) })
            }
            SwitchSettingRow(
                title = "首页显示本地媒体",
                detail = "在首页展示本地媒体的入口（默认关闭）",
                checked = state.homeVisible,
                onCheckedChange = viewModel::setHomeVisible,
            )
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

@Composable
private fun LocalLibraryEntryCard(title: String, detail: String, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    CinefinCard(onClick = onClick, contentPadding = PaddingValues(CinefinSpacing.Space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(CinefinShapes.Sm)
                        .background(media.container)
                        .border(1.dp, media.outline, CinefinShapes.Sm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(CoreR.drawable.ic_plus),
                    contentDescription = null,
                    tint = media.bright,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(CinefinSpacing.Space3))
            Column(Modifier.weight(1f)) {
                Text(text = title, style = CinefinType.TitleMedium, color = colors.onSurface)
                Text(
                    text = detail,
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LocalLibraryCard(card: LocalLibraryViewModel.LibraryCard, onClick: () -> Unit) {
    val colors = LocalCinefinColors.current
    CinefinCard(onClick = onClick, contentPadding = PaddingValues(CinefinSpacing.Space4)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier =
                    Modifier.size(40.dp)
                        .clip(CinefinShapes.Sm)
                        .background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(card.type.iconRes()),
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
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
                    text =
                        "${card.type.label} · ${card.folderCount} 个文件夹 · ${card.summary}" +
                            if (!card.visibleInLibrary) " · 已隐藏" else "",
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(CinefinSpacing.Space2))
            Text(
                text = "${card.itemCount} 项",
                style = CinefinType.MonoDataSmall,
                color = colors.onSurfaceFaint,
            )
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
    viewModel: LocalLibraryDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(libraryId) { viewModel.setup(libraryId) }

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
                        BrowseRow(
                            row = row,
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
private fun BrowseRow(row: LocalLibraryBrowse.Row, onOpenEntry: (LocalLibraryEntry) -> Unit) {
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
        is LocalLibraryBrowse.Row.Entry ->
            CinefinListRow(
                title = row.entry.displayName,
                secondary = entryDetail(row.entry),
                onClick = { onOpenEntry(row.entry) },
                modifier = Modifier.padding(start = CinefinSpacing.Space4 * row.depth),
                leading = {
                    Icon(
                        painter = painterResource(row.entry.kind.iconRes()),
                        contentDescription = row.entry.kind.label,
                        tint = colors.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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

private fun LocalLibraryType.iconRes(): Int =
    when (this) {
        LocalLibraryType.VIDEO -> CoreR.drawable.ic_video
        LocalLibraryType.MUSIC -> CoreR.drawable.ic_music
        LocalLibraryType.BOOK -> CoreR.drawable.ic_book
        LocalLibraryType.MIXED -> CoreR.drawable.ic_library
    }

private fun LocalMediaKind.iconRes(): Int =
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

private fun sizeText(sizeBytes: Long): String {
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

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "—"
    val totalSeconds = durationMs / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
