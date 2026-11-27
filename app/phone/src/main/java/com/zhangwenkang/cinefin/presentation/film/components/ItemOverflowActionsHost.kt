package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSnackbarHost
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.film.ItemWriteViewModel

/**
 * 2.0.3-C：⋮ 菜单的写动作宿主（海报墙 / 剧集 / 季详情页）。
 *
 * 负责三件事，页面只传回调：
 * 1. 把 [ItemWriteViewModel] 的账号能力 + 动作分发通过 [ProvideItemOverflowActions] 提供给子树（卡片 ⋮ 与详情动作条 ⋮ 都读它）；
 * 2. 删除走既有 [DeleteServerFileDialog] 二次确认，编辑走既有 [EditMetadataDialog]（快照没取到时字段禁用）；
 * 3. 结果按既有 `detailWriteEventMessage` 弹 Snackbar；成功由页面回调刷新 / 关闭。
 *
 * [snackbarHostState] 传页面上已有的实例可共用同一个提示通道；不传则本宿主自绘一个（页面没有 Snackbar 时用）。
 */
@Composable
fun ItemOverflowActionsHost(
    offlineMode: Boolean,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    onItemDeleted: (FindroidItem) -> Unit = {},
    onMetadataSaved: (FindroidItem) -> Unit = {},
    write: ItemWriteViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val canEditMetadata by write.canEditMetadata.collectAsStateWithLifecycle()
    val canDeleteContent by write.canDeleteContent.collectAsStateWithLifecycle()
    val snapshot by write.metadataSnapshot.collectAsStateWithLifecycle()
    val snapshotLoading by write.metadataSnapshotLoading.collectAsStateWithLifecycle()
    val saving by write.metadataSaving.collectAsStateWithLifecycle()

    val ownedSnackbar = remember { SnackbarHostState() }
    val snackbar = snackbarHostState ?: ownedSnackbar

    var deleteTarget by remember { mutableStateOf<FindroidItem?>(null) }
    var deletingItem by remember { mutableStateOf<FindroidItem?>(null) }
    var editRequested by remember { mutableStateOf<FindroidItem?>(null) }
    var editTarget by remember { mutableStateOf<FindroidItem?>(null) }

    LaunchedEffect(Unit) {
        write.deleteEvents.collect { event ->
            val deleted = deletingItem
            deletingItem = null
            if (event == DetailWriteEvent.Confirmed) {
                deleted?.let(onItemDeleted)
            } else {
                detailWriteEventMessage(context, event)?.let { snackbar.showSnackbar(it) }
            }
        }
    }

    LaunchedEffect(Unit) {
        write.metadataEvents.collect { event ->
            if (event == DetailWriteEvent.Confirmed) {
                editTarget?.let(onMetadataSaved)
                editTarget = null
            }
            detailWriteEventMessage(context, event)?.let { snackbar.showSnackbar(it) }
        }
    }

    // 快照读取完成后才展示对话框（取不到也展示，字段禁用）——与电影 / 单集详情同一时序。
    LaunchedEffect(editRequested, snapshotLoading) {
        val requested = editRequested ?: return@LaunchedEffect
        if (!snapshotLoading) {
            editTarget = requested
            editRequested = null
        }
    }

    Box(modifier = modifier) {
        ProvideItemOverflowActions(
            canEditMetadata = canEditMetadata,
            canDeleteContent = canDeleteContent,
            offlineMode = offlineMode,
            onAction = { item, action ->
                when (action) {
                    ItemOverflowAction.EditMetadata -> {
                        editRequested = item
                        write.loadMetadataSnapshot(item.id)
                    }
                    ItemOverflowAction.Delete -> deleteTarget = item
                }
            },
        ) {
            content()
        }
        if (snackbarHostState == null) {
            CinefinSnackbarHost(
                hostState = ownedSnackbar,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(CinefinSpacing.Space4),
            )
        }
    }

    deleteTarget?.let { target ->
        DeleteServerFileDialog(
            itemName = target.name,
            onDelete = {
                deleteTarget = null
                deletingItem = target
                write.deleteItem(target.id)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    editTarget?.let { target ->
        EditMetadataDialog(
            snapshot = snapshot,
            saving = saving,
            onSave = { name, overview, sortName ->
                write.saveMetadata(target.id, name, overview, sortName)
            },
            onDismiss = { editTarget = null },
        )
    }
}
