package com.zhangwenkang.cinefin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(tableName = "bookDownloadTasks")
data class BookDownloadTaskDto(
    @PrimaryKey val canonicalKey: String,
    val itemId: UUID,
    val serverId: String,
    val userId: String,
    val title: String,
    val targetPath: String,
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
    @ColumnInfo(defaultValue = "0") val totalBytes: Long = 0,
    val status: String,
    val failureReason: String? = null,
    val resumeValidator: String? = null,
    @ColumnInfo(defaultValue = "0") val taskGeneration: Long = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = 0,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
)
