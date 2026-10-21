package com.zhangwenkang.cinefin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

enum class DownloadIntentStatus {
    RECEIVED,
    FAILED,
}

/**
 * W84：用户点击「下载」后先原子落库的接收意图。
 *
 * 播放信息与媒体源由后台继续解析；意图行在成功转成 sources 任务后删除。
 */
@Entity(tableName = "downloadIntents")
data class DownloadIntentDto(
    @PrimaryKey val itemId: UUID,
    val name: String,
    val albumName: String?,
    val artist: String?,
    val trackIndex: Int,
    val status: String,
    val failureReason: String? = null,
    val failureDetail: String? = null,
    @ColumnInfo(defaultValue = "0") val createdAt: Long,
    @ColumnInfo(defaultValue = "0") val updatedAt: Long,
)
