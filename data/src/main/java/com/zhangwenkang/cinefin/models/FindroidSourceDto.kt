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
    /**
     * W50 自研下载引擎：残片已写入字节数。
     *
     * 进程重启后无需扫盘即可恢复进度显示与续传偏移；旧数据为 0，由引擎在首次运行时按残片大小补齐。
     */
    @ColumnInfo(defaultValue = "0") val downloadedBytes: Long = 0,
    /** W50：连续失败次数（指数退避用）。成功后清零。 */
    @ColumnInfo(defaultValue = "0") val retryCount: Int = 0,
    /**
     * W50：断点续传校验器（ETag 或 Last-Modified）。
     *
     * 续传请求带 `If-Range`，服务器内容已变化时回 200，引擎截断残片安全重下。
     */
    val resumeValidator: String? = null,
    /** W50：下一次可自动重试的时间（epoch ms；0 = 立即可运行）。 */
    @ColumnInfo(defaultValue = "0") val nextRetryAt: Long = 0,
    /** W50：目标文件总大小（已知时；进程重启后用于进度百分比 / ETA）。 */
    @ColumnInfo(defaultValue = "0") val totalBytes: Long = 0,
    /** W50：写入该行的引擎版本（0 = 旧 DownloadManager 时代，1 = 自研引擎）。 */
    @ColumnInfo(defaultValue = "0") val engineVersion: Int = 0,
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
