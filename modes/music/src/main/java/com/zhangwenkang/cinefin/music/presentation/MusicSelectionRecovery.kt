package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.core.selection.MultiSelectState

internal data class MusicBatchSelectionState(
    val revision: Long = 0L,
    val selection: MultiSelectState = MultiSelectState(),
    val recoveryClaim: MusicSelectionRecoveryClaim? = null,
)

internal data class MusicSelectionSnapshot(
    val revision: Long,
    val selectedIds: Set<String>,
)

internal data class MusicSelectionRecoveryClaim(
    val requestId: Long,
    val selectedIds: Set<String>,
    val revisionAfterClear: Long,
)

internal fun MusicBatchSelectionState.snapshotForPlayback(): MusicSelectionSnapshot =
    MusicSelectionSnapshot(revision = revision, selectedIds = selection.selectedIds)

internal fun MusicBatchSelectionState.userChanged(
    selection: MultiSelectState
): MusicBatchSelectionState =
    copy(revision = revision + 1, selection = selection, recoveryClaim = null)

internal fun MusicBatchSelectionState.acceptedForPlayback(
    requestId: Long,
    snapshot: MusicSelectionSnapshot,
): MusicBatchSelectionState {
    if (revision != snapshot.revision || selection.selectedIds != snapshot.selectedIds) {
        return copy(recoveryClaim = null)
    }
    val clearedRevision = revision + 1
    return copy(
        revision = clearedRevision,
        selection = MultiSelectState(),
        recoveryClaim =
            MusicSelectionRecoveryClaim(
                requestId = requestId,
                selectedIds = snapshot.selectedIds,
                revisionAfterClear = clearedRevision,
            ),
    )
}

internal fun MusicBatchSelectionState.restoreUnaccepted(
    requestId: Long,
    unacceptedIds: Set<String>,
    visibleIds: Set<String>,
): MusicBatchSelectionState {
    val claim = recoveryClaim ?: return this
    if (
        claim.requestId != requestId ||
            revision != claim.revisionAfterClear ||
            selection != MultiSelectState()
    ) {
        return this
    }
    val retryIds = claim.selectedIds.intersect(unacceptedIds).intersect(visibleIds)
    if (retryIds.isEmpty()) return copy(recoveryClaim = null)
    return copy(
        revision = revision + 1,
        selection = MultiSelectState(selectionMode = true, selectedIds = retryIds),
        recoveryClaim = null,
    )
}
