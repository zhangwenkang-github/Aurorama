package com.zhangwenkang.cinefin.repository

import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * 阅读器数据契约（ARCHITECTURE §5.1）。
 *
 * 服务端进度走 Jellyfin UserData 白名单接口；`locatorJson` 保存 Readium 的精确位置， W1 先落在应用私有 JSON 文件，W3 再迁入 Room（见
 * READER_PLAN 决策记录）。
 */
interface ReaderRepository {
    suspend fun ensureLocalFile(itemId: UUID): File

    suspend fun deleteLocalFile(itemId: UUID)

    suspend fun getReadingProgress(itemId: UUID): ReadingProgress?

    suspend fun saveReadingProgress(itemId: UUID, progress: ReadingProgress)

    suspend fun flushPendingProgress()
}

data class ReadingProgress(
    val itemId: UUID,
    val locatorJson: String = "",
    val progression: Double,
    val positionTicks: Long,
    val updatedAt: Instant,
    val pendingSync: Boolean = false,
)
