package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadCanonicalIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadTask
import com.zhangwenkang.cinefin.utils.DownloadTaskGroup
import com.zhangwenkang.cinefin.utils.DownloadTaskRules
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus
import com.zhangwenkang.cinefin.utils.downloadIdentity
import java.util.UUID

sealed interface DownloadPlaybackPlan {
    data class Music(val itemIds: List<UUID>) : DownloadPlaybackPlan

    data class Video(val itemIds: List<UUID>) : DownloadPlaybackPlan
}

data class DownloadDeletionResult(
    val succeededKeys: Set<String> = emptySet(),
    val failedKeys: Set<String> = emptySet(),
    val supersededKeys: Set<String> = emptySet(),
) {
    val retainedKeys: Set<String>
        get() = failedKeys + supersededKeys
}

data class DownloadSelectionRefreshResult(
    val selection: List<String>,
    val migratedEntries: Map<String, DownloadHierarchyEntry> = emptyMap(),
    val ambiguousIntentKeys: Set<String> = emptySet(),
)

/** W84：下载管理列表的全选口径。 */
object DownloadSelectionRules {

    /** 多选播放只接受全部已完成、且属于同一播放域的音乐或视频。 */
    fun playbackPlan(state: DownloadManagerState): DownloadPlaybackPlan? {
        val entries = state.selectedEntries
        if (entries.isEmpty() || entries.any { it.status != DownloadTaskStatus.COMPLETED }) {
            return null
        }
        val mediaKind = entries.map { it.mediaKind }.distinct().singleOrNull() ?: return null
        val itemIds = entries.map { it.itemId }.distinct()
        return when (mediaKind) {
            DownloadMediaKind.MUSIC -> DownloadPlaybackPlan.Music(itemIds)
            DownloadMediaKind.VIDEO -> DownloadPlaybackPlan.Video(itemIds)
            DownloadMediaKind.BOOK -> null
        }
    }

    /** 任一批次动作仍在途时，选择栏与 ViewModel 都 fail-closed。 */
    fun canStartSelectionAction(state: DownloadManagerState): Boolean =
        state.pendingActions.isEmpty()

    /** 批次开始时冻结候选；后续 refresh 清空容器也不能抹掉失败项的恢复身份。 */
    fun beginDeletion(
        state: DownloadManagerState,
        entries: List<DownloadHierarchyEntry>,
        batchId: Long? = null,
    ): DownloadManagerState {
        val keys = entries.mapTo(linkedSetOf()) { it.key }
        val begun =
            if (batchId == null) {
                state.beginPending(DownloadPendingAction.DELETE, keys)
            } else {
                state.beginDeletionBatch(batchId, keys)
            }
        return begun.copy(
            deletionRecoveryEntries =
                entries.distinctBy(DownloadHierarchyEntry::key).map { entry ->
                    DownloadDeletionRecoveryEntry(
                        entryKey = entry.key,
                        canonicalIdentity = entry.canonicalIdentity,
                    )
                }
        )
    }

    /** 只保留 receipt 明确失败的候选；成功项必须同时退出 selection 与 recovery snapshot。 */
    fun completeDeletion(
        state: DownloadManagerState,
        failedKeys: Set<String>,
    ): DownloadManagerState =
        completeDeletion(
            state = state,
            result =
                DownloadDeletionResult(
                    succeededKeys = state.selection.toSet() - failedKeys,
                    failedKeys = failedKeys,
                ),
        )

    fun completeDeletion(
        state: DownloadManagerState,
        result: DownloadDeletionResult,
    ): DownloadManagerState {
        val resolved = state.resolveDeletionResult(result)
        val retainedSelection = state.selection.filterNot { key -> key in resolved.succeededKeys }
        return state.copy(
            selection = retainedSelection,
            selectionMode = retainedSelection.isNotEmpty(),
            actionFailure =
                if (retainedSelection.isNotEmpty()) {
                    DownloadActionFailure.DELETE
                } else {
                    null
                },
            deletionFailureReason =
                if (resolved.supersededKeys.isNotEmpty()) {
                    DownloadDeletionFailureReason.SUPERSEDED
                } else {
                    null
                },
            deletionRecoveryEntries =
                state.deletionRecoveryEntries.filterNot { it.entryKey in resolved.succeededKeys },
        )
    }

    /** 本地数据先上屏时保留已完成键；完整服务器阶段仍由后续 validKeys 再裁剪。 */
    fun selectionAfterLocalRefresh(
        state: DownloadManagerState,
        localKeys: Set<String>,
    ): List<String> = selectionAfterRefresh(state, localKeys, state.allEntries())

    /**
     * refresh 选择对账：
     * 1. exact key 永久优先；
     * 2. 只有本删除批次冻结的 intent 才能在完整 namespace 内迁移；
     * 3. 零 / 多 source 候选时保留原 key，交由删除结果层显式报错。
     */
    fun selectionAfterRefresh(
        state: DownloadManagerState,
        validKeys: Set<String>,
        currentEntries: List<DownloadHierarchyEntry>,
    ): List<String> =
        reconcileAfterRefresh(
                state = state,
                validKeys = validKeys,
                currentEntries = currentEntries,
            )
            .selection

    fun reconcileAfterRefresh(
        state: DownloadManagerState,
        validKeys: Set<String>,
        currentEntries: List<DownloadHierarchyEntry>,
    ): DownloadSelectionRefreshResult {
        val completedKeys =
            state.completedContainers
                .flatMap { container -> container.allEntries() }
                .mapTo(mutableSetOf()) { it.key }
        val recovery = state.deletionRecoveryEntries.associateBy { it.entryKey }
        val selection = mutableListOf<String>()
        val migrated = linkedMapOf<String, DownloadHierarchyEntry>()
        val ambiguous = linkedSetOf<String>()
        state.selection.forEach { key ->
            when {
                key in validKeys || key in completedKeys -> selection += key
                else ->
                    recovery[key]?.let { entry ->
                        val identity = entry.canonicalIdentity
                        if (!identity.isIntentSource()) {
                            selection += key
                            return@forEach
                        }
                        val sourceCandidates =
                            currentEntries
                                .filter { candidate ->
                                    candidate.canonicalIdentity.sameNamespace(identity) &&
                                        !candidate.canonicalIdentity.isIntentSource()
                                }
                                .distinctBy { candidate -> candidate.canonicalIdentity }
                        val target = sourceCandidates.singleOrNull()
                        if (target == null) {
                            selection += key
                            ambiguous += key
                        } else {
                            selection += target.key
                            migrated[key] = target
                        }
                    }
            }
        }
        return DownloadSelectionRefreshResult(
            selection = selection,
            migratedEntries = migrated,
            ambiguousIntentKeys = ambiguous,
        )
    }

    /** 当前 selection 顺序对应的删除目标；visible 缺失时使用批次冻结 identity 重建。 */
    fun deletionEntries(state: DownloadManagerState): List<DownloadHierarchyEntry> {
        val visible = state.allEntries().associateBy(DownloadHierarchyEntry::key)
        val recovery = state.deletionRecoveryEntries.associateBy { it.entryKey }
        return state.selection
            .mapNotNull { key -> visible[key] ?: recovery[key]?.toDeletionEntry() }
            .distinctBy(DownloadHierarchyEntry::key)
    }

    /** 容器 / 季选择展开为条目并追加到尾部；再次点击同一目标时整体移除。 */
    fun toggleSelection(state: DownloadManagerState, key: String): List<String> {
        val targets = state.selectionTargets(key)
        if (targets.isEmpty()) return state.selection
        return if (targets.all { it in state.selection }) {
            state.selection.filterNot { it in targets }
        } else {
            (state.selection + targets.filterNot { it in state.selection }).distinct()
        }
    }

    /**
     * 当前分组 + 媒体筛选下的全集。
     *
     * 失败分组只返回可重试失败任务，因此「全选失败」数量与「重试全部失败」的实际新增数一致。
     */
    fun keysFor(
        state: DownloadManagerState,
        group: DownloadTaskGroup,
    ): List<String> =
        state
            .containersFor(group)
            .flatMap { it.allEntries() }
            .asSequence()
            .filter { entry ->
                group != DownloadTaskGroup.FAILED || entry.status == DownloadTaskStatus.FAILED
            }
            .map { it.key }
            .distinct()
            .toList()

    fun retryableTasks(state: DownloadManagerState): List<DownloadTask> =
        state.selectedTasks
            .asSequence()
            .filter { DownloadTaskRules.canRetry(it.status) }
            .distinctBy { it.downloadIdentity() }
            .toList()

    /** 书籍下载没有 [DownloadTask]；失败重试直接回到 ReaderRepository 的整本下载队列。 */
    fun retryableBookEntries(state: DownloadManagerState): List<DownloadHierarchyEntry> =
        state.selectedEntries
            .asSequence()
            .filter { entry ->
                entry.mediaKind == DownloadMediaKind.BOOK &&
                    entry.status == DownloadTaskStatus.FAILED
            }
            .distinctBy { it.key }
            .toList()

    private fun DownloadManagerState.selectionTargets(key: String): List<String> {
        allContainers
            .firstOrNull { it.key == key }
            ?.let { container ->
                return container.allEntries().map { it.key }
            }
        allContainers.forEach { container ->
            container.children.filterIsInstance<DownloadHierarchySubContainer>().forEach { season ->
                if (season.key == key) return season.children.map { it.key }
            }
        }
        val knownEntry =
            allContainers.flatMap { container -> container.allEntries() }.any { it.key == key }
        return if (knownEntry) {
            listOf(key)
        } else {
            emptyList()
        }
    }

    fun DownloadManagerState.allEntries(): List<DownloadHierarchyEntry> =
        allContainers.flatMap { container ->
            container.children.flatMap { child ->
                when (child) {
                    is DownloadHierarchyLeaf -> listOf(child.entry)
                    is DownloadHierarchySubContainer -> child.children.map { it.entry }
                }
            }
        }

    private fun DownloadCanonicalIdentity.isIntentSource(): Boolean =
        sourceId == "$INTENT_SOURCE_PREFIX$itemId"

    private const val INTENT_SOURCE_PREFIX = "intent:"
}
