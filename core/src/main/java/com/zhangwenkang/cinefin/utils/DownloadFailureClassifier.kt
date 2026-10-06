package com.zhangwenkang.cinefin.utils

import java.io.FileNotFoundException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException

/**
 * W76-Q2：把下载链路上**可判定**的异常映射到具体的 [DownloadFailureReason]。
 *
 * 背景：`DownloaderImpl.executeTask` 的通用 `catch (Exception)`（B9 之前连 `StatFs` 也走这里）把一切非
 * [DownloadHttpException] 的异常收成 `UNKNOWN`，UI 只显示「未知错误」，用户与日志都无法定位。此处只做 「异常 → 原因」的纯映射：可判定 →
 * 具体原因，真未知才 [DownloadFailureReason.UNKNOWN]。
 *
 * 边界：本类**不改动**任何重试 / 恢复策略本身——策略仍由 [DownloadTaskRules] 按原因决定；本类只负责
 * 让原因更准确（更准确的原因会自然选到那一类既有策略，例如磁盘满不自动重试、鉴权失败不自动重试）。
 */
internal object DownloadFailureClassifier {

    /** cause 链最大回溯深度（防御自引用 / 超长链）。 */
    private const val MAX_CAUSE_DEPTH = 8

    /**
     * 传输层 [IOException] 的分类。
     *
     * [DownloadHttpEngine] 的网络读失败默认归为网络错误（保持既有语义与自动重试口径）；仅当异常明确指向 本地文件问题时给出更精确的原因——磁盘满 → 空间不足，目标文件
     * / 父目录不可写 → 文件错误。
     */
    fun classifyIo(io: IOException): DownloadFailureReason =
        when {
            isStorageExhausted(io) -> DownloadFailureReason.STORAGE_INSUFFICIENT
            io is FileNotFoundException -> DownloadFailureReason.FILE_ERROR
            else -> DownloadFailureReason.NETWORK_UNAVAILABLE
        }

    /** 通用异常映射：可判定 → 具体原因，真未知 → [DownloadFailureReason.UNKNOWN]。 */
    fun classify(error: Throwable): DownloadFailureReason =
        when (error) {
            is DownloadHttpException -> error.reason
            is CancellationException -> DownloadFailureReason.CANCELLED
            is UnknownHostException,
            is ConnectException,
            is NoRouteToHostException,
            is SocketTimeoutException,
            is SocketException,
            is InterruptedIOException -> DownloadFailureReason.NETWORK_UNAVAILABLE
            is FileNotFoundException -> DownloadFailureReason.FILE_ERROR
            is SecurityException -> DownloadFailureReason.FILE_ERROR
            else ->
                when {
                    isStorageExhausted(error) -> DownloadFailureReason.STORAGE_INSUFFICIENT
                    // 传输层已把所有网络 IOException 包成 DownloadHttpException（含 NETWORK_UNAVAILABLE），
                    // 这里剩下的裸 IOException 来自本地文件读写 → 文件错误。
                    error is IOException -> DownloadFailureReason.FILE_ERROR
                    else -> DownloadFailureReason.UNKNOWN
                }
        }

    /** 「磁盘满」判据：`ENOSPC` / `No space left on device`（沿 cause 链查找）。 */
    private fun isStorageExhausted(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            val message = current.message
            if (
                message != null &&
                    (message.contains("ENOSPC", ignoreCase = true) ||
                        message.contains("No space left on device", ignoreCase = true))
            ) {
                return true
            }
            current = current.cause?.takeIf { it !== current }
            depth += 1
        }
        return false
    }
}
