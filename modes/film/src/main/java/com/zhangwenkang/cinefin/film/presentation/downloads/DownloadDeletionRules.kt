package com.zhangwenkang.cinefin.film.presentation.downloads

import com.zhangwenkang.cinefin.utils.DownloadDeleteRequest
import com.zhangwenkang.cinefin.utils.DownloadDeletionIdentity
import com.zhangwenkang.cinefin.utils.DownloadMediaKind
import com.zhangwenkang.cinefin.utils.DownloadMediaRecord

data class DownloadDeletionCandidate(
    val entryKey: String,
    val request: DownloadDeleteRequest,
) {
    val canonicalKey: String
        get() = DownloadDeletionIdentity.canonicalKey(request)
}

object DownloadDeletionRules {
    fun candidates(
        entries: List<DownloadHierarchyEntry>,
        mediaRecords: List<DownloadMediaRecord>,
    ): List<DownloadDeletionCandidate> =
        entries
            .map { entry ->
                val task = entry.task
                val record = mediaRecords.firstOrNull { record ->
                    record.itemId == entry.itemId.toString() &&
                        (entry.serverId == null || record.serverId == entry.serverId) &&
                        (entry.userId == null || record.userId == entry.userId) &&
                        (entry.sourceId == null ||
                            record.sourceId == entry.sourceId ||
                            (entry.mediaKind == DownloadMediaKind.BOOK && record.sourceId == null))
                }
                val sourceId =
                    task?.sourceId
                        ?: entry.sourceId
                        ?: if (entry.mediaKind == DownloadMediaKind.BOOK) {
                            "book:${entry.itemId}"
                        } else {
                            null
                        }
                DownloadDeletionCandidate(
                    entryKey = entry.key,
                    request =
                        DownloadDeleteRequest(
                            itemId = entry.itemId,
                            sourceId = sourceId,
                            mediaKind = entry.mediaKind,
                            serverId = entry.serverId ?: task?.serverId ?: record?.serverId,
                            userId = entry.userId ?: task?.userId ?: record?.userId,
                        ),
                )
            }
            .distinctBy(DownloadDeletionCandidate::canonicalKey)
}
