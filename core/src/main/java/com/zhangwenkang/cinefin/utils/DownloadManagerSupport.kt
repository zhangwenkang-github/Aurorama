package com.zhangwenkang.cinefin.utils

import android.app.DownloadManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import timber.log.Timber

/** DownloadManager 单条任务的快照（查询失败 / 记录不存在时为 null）。 */
internal data class DownloadManagerSnapshot(
    val status: Int,
    val reason: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

internal fun DownloadManager.querySnapshot(downloadId: Long?): DownloadManagerSnapshot? {
    if (downloadId == null) return null
    val query = DownloadManager.Query().setFilterById(downloadId)
    return try {
        query(query).use { cursor ->
            if (!cursor.moveToFirst()) return null
            DownloadManagerSnapshot(
                status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
                downloadedBytes =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    ),
                totalBytes =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    ),
            )
        }
    } catch (e: Exception) {
        Timber.w(e, "查询 DownloadManager 任务失败 downloadId=$downloadId")
        null
    }
}

/**
 * 完成任务落盘：`.download` → 正式文件名，并写入 COMPLETED 状态。
 *
 * 失败时保留任务并标记 FAILED（不再像旧实现那样直接删掉整条下载记录）。
 */
internal suspend fun finishDownloadedSource(
    database: ServerDatabaseDao,
    source: FindroidSourceDto,
): Boolean {
    val targetPath = source.path.removeSuffix(".download")
    if (targetPath == source.path) {
        database.setSourceTaskStatus(
            source.id,
            DownloadTaskStatus.COMPLETED.name,
            null,
            System.currentTimeMillis(),
        )
        return true
    }

    val partial = File(source.path)
    if (!partial.exists()) {
        database.setSourceTaskStatus(
            source.id,
            DownloadTaskStatus.FAILED.name,
            DownloadFailureReason.FILE_ERROR.name,
            System.currentTimeMillis(),
        )
        return false
    }

    val target = File(targetPath)
    if (target.exists()) {
        target.delete()
    }
    val renamed = partial.renameTo(target)
    if (renamed) {
        database.setSourcePath(source.id, target.path)
        database.setSourceTaskStatus(
            source.id,
            DownloadTaskStatus.COMPLETED.name,
            null,
            System.currentTimeMillis(),
        )
    } else {
        database.setSourceTaskStatus(
            source.id,
            DownloadTaskStatus.FAILED.name,
            DownloadFailureReason.FILE_ERROR.name,
            System.currentTimeMillis(),
        )
    }
    return renamed
}

/** 把异常按「网络 / 未知」分类，用于失败任务的可读原因。 */
internal fun classifyDownloadException(e: Exception): DownloadFailureReason =
    when (e) {
        is UnknownHostException,
        is ConnectException,
        is SocketTimeoutException,
        is IOException -> DownloadFailureReason.NETWORK_UNAVAILABLE
        else -> DownloadFailureReason.UNKNOWN
    }

internal fun partialFileSize(path: String): Long = runCatching {
    File(path).length()
}
    .getOrDefault(0L)

internal fun deletePartialFile(path: String): Boolean = runCatching {
    !File(path).exists() || File(path).delete()
}
    .getOrDefault(false)

/**
 * DownloadManager 断点续传 sidecar（`<目标文件>.js`）。
 *
 * 真机（K60 / Android 15）实测：暂停（remove）会删除目标残片，但 sidecar 可能残留，导致下载页存储占用清不干净。 统一在暂停 / 重试 / 删除时主动清理。
 */
internal fun deletePartialSidecar(path: String): Boolean = runCatching {
    val target = File(path)
    val candidates = buildList {
        target.parentFile?.let { add(File(it, ".${target.name}.js")) }
        add(File("$path.js"))
    }
    candidates.all { !it.exists() || it.delete() }
}
    .getOrDefault(false)

internal fun deletePartialArtifacts(path: String) {
    deletePartialFile(path)
    deletePartialSidecar(path)
}
