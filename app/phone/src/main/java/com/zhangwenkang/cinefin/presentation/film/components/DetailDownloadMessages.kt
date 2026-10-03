package com.zhangwenkang.cinefin.presentation.film.components

import android.content.Context
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadEvent

/** W51：详情页下载事件 → Snackbar 文案（三态「已加入下载队列 / 已在队列 / 已下载」+ 批量 / 失败）。 */
internal fun downloadEventMessage(context: Context, event: DetailDownloadEvent): String =
    when (event) {
        DetailDownloadEvent.AddedToQueue -> context.getString(CoreR.string.detail_download_added)
        DetailDownloadEvent.AlreadyQueued ->
            context.getString(CoreR.string.detail_download_already_queued)
        DetailDownloadEvent.AlreadyDownloaded ->
            context.getString(CoreR.string.detail_download_already_downloaded)
        is DetailDownloadEvent.BatchAdded ->
            context.getString(CoreR.string.detail_download_batch_added, event.added)
        is DetailDownloadEvent.BatchSkipped ->
            when {
                event.downloaded > 0 && event.queued > 0 ->
                    context.getString(CoreR.string.detail_download_batch_none_mixed)
                event.downloaded > 0 ->
                    context.getString(CoreR.string.detail_download_batch_none_downloaded)
                event.queued > 0 ->
                    context.getString(CoreR.string.detail_download_batch_none_queued)
                else -> context.getString(CoreR.string.detail_download_batch_empty)
            }
        is DetailDownloadEvent.Failed ->
            event.reason?.asString(context.resources)
                ?: context.getString(CoreR.string.detail_download_failed)
    }
