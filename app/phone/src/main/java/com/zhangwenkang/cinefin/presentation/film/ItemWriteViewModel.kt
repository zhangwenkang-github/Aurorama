package com.zhangwenkang.cinefin.presentation.film

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent
import com.zhangwenkang.cinefin.repository.ItemMetadataSnapshot
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.UserDataWriteFailure
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
import com.zhangwenkang.cinefin.repository.UserPolicyStore
import com.zhangwenkang.cinefin.repository.canDeleteContent
import com.zhangwenkang.cinefin.repository.canEditMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * 2.0.3-C：影视域通用的「删除服务器条目 / 编辑元数据」写入口（海报墙与剧集 / 季详情页共用）。
 *
 * 与 MovieViewModel / EpisodeViewModel 里的同款动作是同一套仓库调用、同一套权限判定与结果事件： 删除走二次确认后 [deleteItem]，编辑先取服务器快照
 * [loadMetadataSnapshot] 再提交 [saveMetadata]。权限来自 [UserPolicyStore]（未拉取 / 换号后一律 fail-closed）。
 */
@HiltViewModel
class ItemWriteViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    userPolicyStore: UserPolicyStore,
) : ViewModel() {
    /** 编辑元数据是管理员专属（`POST /Items/{id}` 整体 RequiresElevation）。 */
    private val _canEditMetadata = MutableStateFlow(false)
    val canEditMetadata = _canEditMetadata.asStateFlow()

    /**
     * 账号级删除许可（管理员 / `EnableContentDeletion`）。剧集 / 季的本地模型没有服务端条目级 `canDelete`， 入口门控退到这一口径；电影 /
     * 单集仍以条目自身 `canDelete` 为准。
     */
    private val _canDeleteContent = MutableStateFlow(false)
    val canDeleteContent = _canDeleteContent.asStateFlow()

    /** 编辑对话框预填快照；null = 服务器当前值没取到（字段禁用）。 */
    private val _metadataSnapshot = MutableStateFlow<ItemMetadataSnapshot?>(null)
    val metadataSnapshot = _metadataSnapshot.asStateFlow()

    private val _metadataSnapshotLoading = MutableStateFlow(false)
    val metadataSnapshotLoading = _metadataSnapshotLoading.asStateFlow()

    private val _metadataSaving = MutableStateFlow(false)
    val metadataSaving = _metadataSaving.asStateFlow()

    /** 删除结果；[DetailWriteEvent.Confirmed] = 屏幕刷新 / 关闭，其余弹 Snackbar。 */
    private val deleteEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val deleteEvents = deleteEventsChannel.receiveAsFlow()

    /** 编辑结果；[DetailWriteEvent.Confirmed] = 对话框关闭并刷新，其余弹 Snackbar。 */
    private val metadataEventsChannel = Channel<DetailWriteEvent>(Channel.BUFFERED)
    val metadataEvents = metadataEventsChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            runCatching { userPolicyStore.ensureLoaded() }
            userPolicyStore.access.collect { access ->
                _canEditMetadata.value = canEditMetadata(access.policy)
                _canDeleteContent.value = canDeleteContent(access.policy, libraryId = null)
            }
        }
    }

    /** 删除服务器条目（调用方负责确认弹窗）；失败时本地不改动，不重取详情。 */
    fun deleteItem(itemId: UUID) {
        viewModelScope.launch {
            val outcome = runCatching { repository.deleteItem(itemId) }.getOrNull()
            deleteEventsChannel.send(outcome.toWriteEvent())
        }
    }

    /** 打开编辑对话框前先取服务器当前值；取不到（null）时对话框字段禁用。 */
    fun loadMetadataSnapshot(itemId: UUID) {
        _metadataSnapshotLoading.value = true
        _metadataSnapshot.value = null
        viewModelScope.launch {
            _metadataSnapshot.value =
                runCatching { repository.fetchItemMetadataSnapshot(itemId) }.getOrNull()
            _metadataSnapshotLoading.value = false
        }
    }

    /** 提交元数据编辑；成功后由调用方刷新内容，失败走既有 Snackbar 文案。 */
    fun saveMetadata(itemId: UUID, name: String, overview: String?, sortName: String?) {
        if (_metadataSaving.value) return
        _metadataSaving.value = true
        viewModelScope.launch {
            val outcome = runCatching {
                repository.updateItemMetadata(
                    itemId = itemId,
                    name = name,
                    overview = overview,
                    sortName = sortName,
                )
            }
                .getOrNull()
            _metadataSaving.value = false
            metadataEventsChannel.send(outcome.toWriteEvent())
        }
    }
}

/** 仓库写结果 → 屏幕事件；null（写根本没完成）按普通失败呈现（与电影 / 单集详情同口径）。 */
private fun UserDataWriteOutcome?.toWriteEvent(): DetailWriteEvent =
    when (this) {
        UserDataWriteOutcome.Confirmed -> DetailWriteEvent.Confirmed
        UserDataWriteOutcome.QueuedForSync -> DetailWriteEvent.QueuedForSync
        is UserDataWriteOutcome.Failed ->
            when (failure) {
                is UserDataWriteFailure.PermissionDenied -> DetailWriteEvent.PermissionDenied
                else -> DetailWriteEvent.Failed
            }
        null -> DetailWriteEvent.Failed
    }
