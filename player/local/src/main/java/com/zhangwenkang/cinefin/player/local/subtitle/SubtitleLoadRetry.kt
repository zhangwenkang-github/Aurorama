package com.zhangwenkang.cinefin.player.local.subtitle

import kotlinx.coroutines.delay
import timber.log.Timber

/** W74 #21：字幕下载失败后的短退避间隔（毫秒） */
internal const val SUBTITLE_DOWNLOAD_RETRY_DELAY_MS = 500L

/**
 * 短退避重试一次（W74 #21）。
 *
 * 首次下载偶发失败（连接复用被服务端掐断 / 瞬时抖动 / 转码档刚起播）时，如果直接把异常兜成空字幕， 用户看到的是「这集没有字幕」（
 * `cues=0`）；重试一次能吃掉大部分瞬时抖动，两次都失败时先记下第一次的异常原因再抛出， 由调用方统一兜底并落日志。
 *
 * @param delayMs 重试前的等待时间；传 0 便于单测（生产用 [SUBTITLE_DOWNLOAD_RETRY_DELAY_MS]）。
 * @param label 日志里的动作名（不含地址——Jellyfin 的 DeliveryUrl 带 ApiKey）。
 */
internal suspend fun <T> retryOnce(
    delayMs: Long,
    label: String,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (first: Exception) {
        Timber.w(first, "%s 失败，%dms 后重试一次", label, delayMs)
        delay(delayMs)
        block()
    }
