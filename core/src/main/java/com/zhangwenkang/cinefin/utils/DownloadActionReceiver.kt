package com.zhangwenkang.cinefin.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * W50 下载通知操作接收器：前台通知上的「暂停 / 取消」按钮。
 *
 * 下载引擎不再使用系统 DownloadManager，通知操作直接调用自研引擎的按 id 任务控制。
 */
@AndroidEntryPoint
class DownloadActionReceiver : BroadcastReceiver() {

    @Inject lateinit var downloader: Downloader

    override fun onReceive(context: Context, intent: Intent) {
        val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID) ?: return
        val action = intent.action ?: return
        if (
            action != DownloadNotifications.ACTION_PAUSE &&
                action != DownloadNotifications.ACTION_CANCEL
        ) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    DownloadNotifications.ACTION_PAUSE -> downloader.pauseTaskById(sourceId)
                    DownloadNotifications.ACTION_CANCEL -> downloader.deleteTaskById(sourceId)
                }
            } catch (e: Exception) {
                Timber.w(e, "下载通知操作失败 action=$action sourceId=$sourceId")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "com.zhangwenkang.cinefin.extra.DOWNLOAD_SOURCE_ID"
    }
}
