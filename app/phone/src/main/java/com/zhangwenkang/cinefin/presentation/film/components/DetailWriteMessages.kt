package com.zhangwenkang.cinefin.presentation.film.components

import android.content.Context
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.film.presentation.detail.DetailWriteEvent

/**
 * W8-T1：收藏 / 已看写结果 → Snackbar 文案；null = 服务器已确认、无需提示。
 *
 * 权限拒绝不可重试（V2-WRITE-09），文案与「已保存待同步」严格区分。
 */
internal fun detailWriteEventMessage(context: Context, event: DetailWriteEvent): String? =
    when (event) {
        DetailWriteEvent.Confirmed -> null
        DetailWriteEvent.QueuedForSync -> context.getString(FilmR.string.write_saved_queued)
        DetailWriteEvent.PermissionDenied -> context.getString(FilmR.string.write_permission_denied)
        DetailWriteEvent.Failed -> context.getString(FilmR.string.write_failed)
    }
