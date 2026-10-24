package com.zhangwenkang.cinefin.utils

import java.util.UUID

/** 已真正完成的 LOCAL 下载来源；sourceId 必须精确匹配 sidecar。 */
internal data class DownloadedMediaSource(val itemId: UUID, val sourceId: String)

internal object DownloadedMediaRecordRules {
    /**
     * 视频 / 音乐按 `(itemId, sourceId)` 对账；书籍不经过 sources 表，以非空 `.book` 文件为完成证据。
     *
     * 失败、取消、暂停和 `.download` 半成品不会进入 `completedLocalSources`，因此不会冒充 DOWNLOADED 搜索结果。
     */
    fun completedRecords(
        records: List<DownloadMediaRecord>,
        completedLocalSources: Set<DownloadedMediaSource>,
        bookFileSize: (UUID) -> Long,
    ): List<DownloadMediaRecord> = records.filter { record ->
        val itemId = record.itemId.toUuidOrNull() ?: return@filter false
        when (record.kind) {
            DownloadMediaKind.BOOK -> bookFileSize(itemId) > 0L
            DownloadMediaKind.VIDEO,
            DownloadMediaKind.MUSIC -> {
                val sourceId = record.sourceId?.takeIf { it.isNotBlank() }
                if (sourceId != null) {
                    DownloadedMediaSource(itemId, sourceId) in completedLocalSources
                } else {
                    // v1 侧车没有 sourceId；只有 item 确有完整 LOCAL 文件时保留为来源未知。
                    completedLocalSources.any { it.itemId == itemId }
                }
            }
        }
    }
}
