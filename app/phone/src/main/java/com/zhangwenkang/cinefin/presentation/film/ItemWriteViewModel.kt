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

/** 2.0.3-F3：⋮ 菜单的账号能力快照；[loaded] = 权限已拉取（未拉取时入口按「可能可用」显示，点开才拉取）。 */
data class ItemWriteCapabilities(
    val loaded: Boolean = false,
    val canEditMetadata: Boolean = false,
    val canDeleteContent: Boolean = false,
)

/**
 * 2.0.3-C：影视域通用的「删除服务器条目 / 编辑元数据」写入口（海报墙与剧集 / 季详情页共用）。
 *
 * 与 MovieViewModel / EpisodeViewModel 里的同款动作是同一套仓库调用、同一套权限判定与结果事件： 删除走二次确认后 [deleteItem]，编辑先取服务器快照
 * [loadMetadataSnapshot] 再提交 [saveMetadata]。权限来自 [UserPolicyStore]（未拉取 / 换号后一律 fail-closed）。
 *
 * 2.0.3-F3：构造时不再拉取权限——页面可能只是浏览，[start] 由 ⋮ 菜单第一次被点开时调用，避免每个列表页常驻一次权限请求。
 */
@HiltViewModel
class ItemWriteViewModel
@Inject
constructor(
    private val repository: JellyfinRepository,
    private val userPolicyStore: UserPolicyStore,
) : ViewModel() {
    private val _capabilities = MutableStateFlow(ItemWriteCapabilities())
    val capabilities = _capabilities.asStateFlow()

    /** 编辑对话框预填快照；null = 服务器当前值没取到（字段禁用）。 */
    private val _metadataSnapshot = MutableStateFlow<ItemMetadataSnapshot?>(null)
    val metadataSnapshot = _metadataSnapshot.asStateFlow()

    /** 快照所属条目：与请求条目一致才允许打开对话框，避免首帧拿到上一次 / 空快照。 */
    private val _metadataSnapshotItemId = MutableStateFlow<UUID?>(null)
    val metadataSnapshotItemId = _metadataSnapshotItemId.asStateFlow()

    /** 快照结果属于最近一次请求（false = 还在读取 / 结果已过期）。 */
    private val _metadataSnapshotReady = MutableStateFlow(false)
    val metadataSnapshotReady = _metadataSnapshotReady.asStateFlow()

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

    private var started = false
    private var metadataRequestSerial = 0L

    /** 首次打开 ⋮ 菜单时调用：拉取权限并保持跟随（同一身份内不再重复请求）。 */
    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            runCatching { userPolicyStore.ensureLoaded() }
            userPolicyStore.access.collect { access ->
                _capabilities.value =
                    ItemWriteCapabilities(
                        loaded = access.isLoaded,
                        canEditMetadata = canEditMetadata(access.policy),
                        canDeleteContent = canDeleteContent(access.policy, libraryId = null),
                    )
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

    /** 打开编辑对话框前先取服务器当前值；结果与请求条目绑定（[metadataSnapshotItemId]），取不到时对话框字段禁用。 */
    fun loadMetadataSnapshot(itemId: UUID) {
        val serial = ++metadataRequestSerial
        _metadataSnapshotLoading.value = true
        _metadataSnapshotReady.value = false
        _metadataSnapshot.value = null
        _metadataSnapshotItemId.value = null
        viewModelScope.launch {
            val snapshot = runCatching { repository.fetchItemMetadataSnapshot(itemId) }.getOrNull()
            // 期间又发起了新请求（换了条目 / 重复打开）：旧结果直接丢弃。
            if (serial != metadataRequestSerial) return@launch
            _metadataSnapshot.value = snapshot
            _metadataSnapshotItemId.value = itemId
            _metadataSnapshotReady.value = true
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
