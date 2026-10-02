package com.zhangwenkang.cinefin.models

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.PrimaryKey
import java.util.UUID

@Entity(tableName = "sources")
data class FindroidSourceDto(
    @PrimaryKey val id: String,
    val itemId: UUID,
    val name: String,
    val type: FindroidSourceType,
    val path: String,
    val downloadId: Long? = null,
    /** W32 下载任务状态（DownloadTaskStatus.name）。null = 旧数据，按 DownloadManager / 路径推断。 */
    val taskStatus: String? = null,
    /** W32 下载失败原因（DownloadFailureReason.name）。null = 没有失败。 */
    val failureReason: String? = null,
    /** W32 最近一次状态变更时间（epoch ms），用于任务排序与对账。 */
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,
    /**
     * W36「允许离线模式观看」：默认允许。
     *
     * 关闭后该条目在离线媒体库 / 离线音乐 / 离线书架中隐藏，在线访问不受影响； 只对 LOCAL（已下载）来源有意义。
     */
    @ColumnInfo(defaultValue = "1") val allowOffline: Boolean = true,
)

fun FindroidSource.toFindroidSourceDto(itemId: UUID, path: String): FindroidSourceDto {
    return FindroidSourceDto(
        id = id,
        itemId = itemId,
        name = name,
        type = FindroidSourceType.LOCAL,
        path = path,
    )
}
