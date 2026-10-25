package com.zhangwenkang.cinefin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(tableName = "downloadDeleteBatches")
data class DownloadDeleteBatchDto(
    @PrimaryKey val id: String,
    @ColumnInfo(defaultValue = "0") val requestedCount: Int,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
    val completedAt: Long? = null,
)

@Entity(
    tableName = "downloadDeleteReceipts",
    primaryKeys = ["batchId", "canonicalKey"],
)
data class DownloadDeleteReceiptDto(
    val batchId: String,
    val canonicalKey: String,
    val itemId: UUID,
    val sourceId: String?,
    val mediaKind: String,
    val outcome: String,
    val detail: String?,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
)
