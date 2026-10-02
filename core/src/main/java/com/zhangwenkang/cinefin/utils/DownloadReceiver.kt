package com.zhangwenkang.cinefin.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import timber.log.Timber

@AndroidEntryPoint
class DownloadReceiver : BroadcastReceiver() {

    @Inject lateinit var database: ServerDatabaseDao

    @Inject lateinit var downloader: Downloader

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onReceive(context: Context, intent: Intent) =
        launchAsync(ioScope) {
            if (intent.action == "android.intent.action.DOWNLOAD_COMPLETE") {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                if (id != -1L) {
                    val source = database.getSourceByDownloadId(id)
                    if (source != null) {
                        // W32：完成 / 失败都保留任务；完成补重命名，失败归档可读原因（不再删记录）。
                        val snapshot =
                            context.getSystemService(DownloadManager::class.java).querySnapshot(id)
                        when (snapshot?.status) {
                            DownloadManager.STATUS_SUCCESSFUL -> {
                                if (!finishDownloadedSource(database, source)) {
                                    Timber.w("下载完成但重命名失败：${source.path}")
                                }
                            }
                            DownloadManager.STATUS_FAILED,
                            DownloadManager.STATUS_PAUSED -> {
                                val reason =
                                    DownloadTaskRules.resolveFailureReason(
                                        persistedReason = null,
                                        managerStatus = snapshot.status,
                                        managerReason = snapshot.reason,
                                    )
                                database.setSourceTaskStatus(
                                    source.id,
                                    if (snapshot.status == DownloadManager.STATUS_PAUSED) {
                                        DownloadTaskStatus.PAUSED.name
                                    } else {
                                        DownloadTaskStatus.FAILED.name
                                    },
                                    reason?.name,
                                    System.currentTimeMillis(),
                                )
                                if (snapshot.status == DownloadManager.STATUS_FAILED) {
                                    // 网络类失败：入队带 CONNECTED 约束的重试任务，网络恢复后自动重试一次。
                                    runCatching { downloader.refreshDownloadTasks() }
                                }
                            }
                            else -> Unit
                        }
                    } else {
                        val mediaStream = database.getMediaStreamByDownloadId(id)
                        if (mediaStream != null) {
                            val path = mediaStream.path.replace(".download", "")
                            val successfulRename = File(mediaStream.path).renameTo(File(path))
                            if (successfulRename) {
                                database.setMediaStreamPath(mediaStream.id, path)
                            } else {
                                database.deleteMediaStream(mediaStream.id)
                            }
                        }
                    }
                }
            }
        }
}
