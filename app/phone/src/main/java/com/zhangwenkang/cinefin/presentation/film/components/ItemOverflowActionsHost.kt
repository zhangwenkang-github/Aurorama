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
import com.zhangwenkang.cinefin.presentation.film.ItemWriteCapabilities
import com.zhangwenkang.cinefin.presentation.film.ItemWriteViewModel
import com.zhangwenkang.cinefin.repository.ItemMetadataSnapshot
import java.util.UUID
import kotlinx.coroutines.launch

/**
 * 2.0.3-C：⋮ 菜单的写动作宿主（海报墙 / 剧集 / 季详情页）。
 *
 * 负责三件事，页面只传回调：
 * 1. 把账号能力 + 动作分发通过 [ProvideItemOverflowActions] 提供给子树（卡片 ⋮ 与详情动作条 ⋮ 都读它）；
 * 2. 删除走既有 [DeleteServerFileDialog] 二次确认，编辑走既有 [EditMetadataDialog]（快照没取到时字段禁用）；
 * 3. 结果按既有 `detailWriteEventMessage` 弹 Snackbar；成功由页面回调刷新 / 关闭。
 *
 * 2.0.3-F3：浏览不写入的页面不再常驻权限请求——[ItemWriteViewModel] 只在第一次点开 ⋮ 时创建（`activated`），
 * 由它按需拉取账号能力；编辑对话框以「快照所属条目 = 请求条目」为打开条件，首帧即拿到正确快照。
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
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var activated by remember { mutableStateOf(false) }
    // 懒加载：⋮ 第一次被点开才创建写 ViewModel（构造零 IO，start() 才拉权限）。
    val write = if (activated) hiltViewModel<ItemWriteViewModel>() else null
    val capabilities = rememberWriteCapabilities(write)
    val snapshot = rememberMetadataSnapshot(write)
    val snapshotItemId = rememberMetadataSnapshotItemId(write)
    val snapshotReady = rememberMetadataSnapshotReady(write)
    val saving = rememberMetadataSaving(write)

    val ownedSnackbar = remember { SnackbarHostState() }
    val snackbar = snackbarHostState ?: ownedSnackbar

    var deleteTarget by remember { mutableStateOf<FindroidItem?>(null) }
    var deletingItem by remember { mutableStateOf<FindroidItem?>(null) }
    var editRequested by remember { mutableStateOf<FindroidItem?>(null) }
    var editTarget by remember { mutableStateOf<FindroidItem?>(null) }

    LaunchedEffect(write) {
        val viewModel = write ?: return@LaunchedEffect
        viewModel.start()
        launch {
            viewModel.deleteEvents.collect { event ->
                val deleted = deletingItem
                deletingItem = null
                if (event == DetailWriteEvent.Confirmed) {
                    deleted?.let(onItemDeleted)
                } else {
                    detailWriteEventMessage(context, event)?.let { snackbar.showSnackbar(it) }
                }
            }
        }
        launch {
            viewModel.metadataEvents.collect { event ->
                if (event == DetailWriteEvent.Confirmed) {
                    editTarget?.let(onMetadataSaved)
                    editTarget = null
                }
                detailWriteEventMessage(context, event)?.let { snackbar.showSnackbar(it) }
            }
        }
    }

    // 请求一旦成立（⋮ 里点了「编辑元数据」），先同步清空上一次快照再取服务器值；失败也照旧开对话框（字段禁用）。
    LaunchedEffect(write, editRequested) {
        val viewModel = write ?: return@LaunchedEffect
        val requested = editRequested ?: return@LaunchedEffect
        viewModel.loadMetadataSnapshot(requested.id)
    }

    // 快照读取完成后才展示对话框；「快照条目 = 请求条目」保证首帧就是正确数据（取不到也展示，字段禁用）。
    LaunchedEffect(editRequested, snapshotItemId, snapshotReady) {
        if (shouldOpenMetadataEditor(editRequested?.id, snapshotItemId, snapshotReady)) {
            editTarget = editRequested
            editRequested = null
        }
    }

    Box(modifier = modifier) {
        ProvideItemOverflowActions(
            canEditMetadata = capabilities.canEditMetadata,
            canDeleteContent = capabilities.canDeleteContent,
            capabilitiesKnown = capabilities.loaded,
            offlineMode = offlineMode,
            onRequestCapabilities = { activated = true },
            onAction = { item, action ->
                when (action) {
                    ItemOverflowAction.EditMetadata -> {
                        activated = true
                        editRequested = item
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
                write?.deleteItem(target.id)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    editTarget?.let { target ->
        EditMetadataDialog(
            snapshot = snapshot,
            saving = saving,
            onSave = { name, overview, sortName ->
                write?.saveMetadata(target.id, name, overview, sortName)
            },
            onDismiss = { editTarget = null },
        )
    }
}

/** 未创建写 ViewModel 时返回「权限未拉取」的占位快照（⋮ 先显示，点开才拉取）。 */
@Composable
private fun rememberWriteCapabilities(write: ItemWriteViewModel?): ItemWriteCapabilities =
    if (write == null) {
        ItemWriteCapabilities()
    } else {
        write.capabilities.collectAsStateWithLifecycle().value
    }

@Composable
private fun rememberMetadataSnapshot(write: ItemWriteViewModel?): ItemMetadataSnapshot? =
    if (write == null) {
        null
    } else {
        write.metadataSnapshot.collectAsStateWithLifecycle().value
    }

@Composable
private fun rememberMetadataSnapshotItemId(write: ItemWriteViewModel?): UUID? =
    if (write == null) {
        null
    } else {
        write.metadataSnapshotItemId.collectAsStateWithLifecycle().value
    }

@Composable
private fun rememberMetadataSaving(write: ItemWriteViewModel?): Boolean =
    if (write == null) {
        false
    } else {
        write.metadataSaving.collectAsStateWithLifecycle().value
    }

@Composable
private fun rememberMetadataSnapshotReady(write: ItemWriteViewModel?): Boolean =
    if (write == null) {
        false
    } else {
        write.metadataSnapshotReady.collectAsStateWithLifecycle().value
    }
