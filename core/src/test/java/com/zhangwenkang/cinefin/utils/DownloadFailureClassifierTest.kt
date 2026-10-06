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
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W76-Q2 单测：可判定的下载异常 / 传输层 IO 失败必须给出具体 [DownloadFailureReason]，真未知才 UNKNOWN。
 *
 * 映射只决定「原因」；重试 / 恢复策略仍由 [DownloadTaskRules] 按原因决定（本测试断言的是原因本身）。
 */
class DownloadFailureClassifierTest {

    @Test
    fun `网络类异常映射为网络不可达`() {
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadFailureClassifier.classify(UnknownHostException("jellyfins.zhangwenkang.com")),
        )
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadFailureClassifier.classify(ConnectException("refused")),
        )
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadFailureClassifier.classify(NoRouteToHostException("no route")),
        )
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadFailureClassifier.classify(SocketTimeoutException("timeout")),
        )
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadFailureClassifier.classify(SocketException("reset")),
        )
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadFailureClassifier.classify(InterruptedIOException("interrupted")),
        )
    }

    @Test
    fun `磁盘满映射为空间不足`() {
        assertEquals(
            DownloadFailureReason.STORAGE_INSUFFICIENT,
            DownloadFailureClassifier.classify(
                IOException("write failed: ENOSPC (No space left on device)")
            ),
        )
        assertEquals(
            DownloadFailureReason.STORAGE_INSUFFICIENT,
            DownloadFailureClassifier.classify(IOException("No space left on device")),
        )
    }

    @Test
    fun `磁盘满可沿 cause 链判定`() {
        val wrapped = IOException("写盘失败", IOException("ENOSPC"))
        assertEquals(
            DownloadFailureReason.STORAGE_INSUFFICIENT,
            DownloadFailureClassifier.classify(wrapped),
        )
    }

    @Test
    fun `本地文件异常映射为文件错误`() {
        assertEquals(
            DownloadFailureReason.FILE_ERROR,
            DownloadFailureClassifier.classify(FileNotFoundException("open failed: EISDIR")),
        )
        assertEquals(
            DownloadFailureReason.FILE_ERROR,
            DownloadFailureClassifier.classify(SecurityException("permission denied")),
        )
        assertEquals(
            DownloadFailureReason.FILE_ERROR,
            DownloadFailureClassifier.classify(IOException("read-only filesystem")),
        )
    }

    @Test
    fun `传输层已分类异常原样透传`() {
        for (reason in DownloadFailureReason.entries) {
            assertEquals(
                reason,
                DownloadFailureClassifier.classify(DownloadHttpException(reason, "http")),
            )
        }
    }

    @Test
    fun `取消异常映射为已取消`() {
        assertEquals(
            DownloadFailureReason.CANCELLED,
            DownloadFailureClassifier.classify(CancellationException("cancelled")),
        )
    }

    @Test
    fun `无法判定的异常回落 UNKNOWN`() {
        assertEquals(
            DownloadFailureReason.UNKNOWN,
            DownloadFailureClassifier.classify(IllegalStateException("unexpected")),
        )
        assertEquals(
            DownloadFailureReason.UNKNOWN,
            DownloadFailureClassifier.classify(RuntimeException("boom")),
        )
    }

    @Test
    fun `传输层 IO 分类-网络默认-本地文件精确`() {
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadFailureClassifier.classifyIo(SocketTimeoutException("timeout")),
        )
        assertEquals(
            DownloadFailureReason.NETWORK_UNAVAILABLE,
            DownloadFailureClassifier.classifyIo(IOException("connection reset by peer")),
        )
        assertEquals(
            DownloadFailureReason.STORAGE_INSUFFICIENT,
            DownloadFailureClassifier.classifyIo(IOException("ENOSPC")),
        )
        assertEquals(
            DownloadFailureReason.FILE_ERROR,
            DownloadFailureClassifier.classifyIo(
                FileNotFoundException("open failed: ENOTDIR (Not a directory)")
            ),
        )
    }
}
