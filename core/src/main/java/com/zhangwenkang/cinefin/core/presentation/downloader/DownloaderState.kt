package com.zhangwenkang.cinefin.core.presentation.downloader

import android.app.DownloadManager
import com.zhangwenkang.cinefin.models.UiText

data class DownloaderState(
    val status: Int = 0,
    val progress: Float = 0f,
    val errorText: UiText? = null,
) {
    val isDownloading: Boolean
        get() =
            status in
                arrayOf(
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_FAILED,
                )
}
