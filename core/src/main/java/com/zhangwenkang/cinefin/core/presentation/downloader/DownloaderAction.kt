package com.zhangwenkang.cinefin.core.presentation.downloader

import com.zhangwenkang.cinefin.models.FindroidItem

sealed interface DownloaderAction {
    data class Download(val item: FindroidItem, val storageIndex: Int = 0) : DownloaderAction

    data class DeleteDownload(val item: FindroidItem) : DownloaderAction

    data class CancelDownload(val item: FindroidItem) : DownloaderAction
}
