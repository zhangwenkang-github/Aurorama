package com.zhangwenkang.cinefin.book.presentation.reader

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W77-3：阅读页顶栏三态（①流式 / ②下载中 / ③离线可读）的分支决策与下载百分比（纯函数）。
 *
 * 口径（2026-10-07 用户拍板，D-F14 配套）：远端打开**不再自动整本下载**，因此流式态与下载态必须互斥显示 —— 顶栏不再出现「流式首开却显示下载中 xx%」的错显（W77-1
 * 未覆盖②）。
 */
class ReaderStatusBadgeTest {

    @Test
    fun `远端流式载入中时显示流式载入中而非下载中`() {
        assertEquals(
            ReaderStatusBadge.StreamingLoading,
            readerStatusBadge(ReaderStreamState.StreamConnecting, BookDownloadState.NotDownloaded),
        )
    }

    @Test
    fun `远端流式阅读中且未下载时显示流式阅读中`() {
        assertEquals(
            ReaderStatusBadge.StreamingReading,
            readerStatusBadge(ReaderStreamState.Streaming, BookDownloadState.NotDownloaded),
        )
    }

    @Test
    fun `流式阅读中的手动整本下载优先显示下载进度`() {
        assertEquals(
            ReaderStatusBadge.Downloading,
            readerStatusBadge(ReaderStreamState.Streaming, BookDownloadState.Downloading(0.42f)),
        )
    }

    @Test
    fun `回退整本下载路径显示下载进度`() {
        assertEquals(
            ReaderStatusBadge.Downloading,
            readerStatusBadge(ReaderStreamState.Local, BookDownloadState.Downloading(0f)),
        )
    }

    @Test
    fun `本地已有整本文件时显示离线可读`() {
        assertEquals(
            ReaderStatusBadge.OfflineReadable,
            readerStatusBadge(ReaderStreamState.Local, BookDownloadState.Downloaded(229_242_149L)),
        )
        // 手动下载已完成但热切换尚未落地的瞬间：文件已在本地，同样显示离线可读。
        assertEquals(
            ReaderStatusBadge.OfflineReadable,
            readerStatusBadge(ReaderStreamState.Streaming, BookDownloadState.Downloaded(1024L)),
        )
    }

    @Test
    fun `未下载且非流式时可点下载`() {
        assertEquals(
            ReaderStatusBadge.DownloadAction,
            readerStatusBadge(ReaderStreamState.Local, BookDownloadState.NotDownloaded),
        )
    }

    @Test
    fun `下载失败时提供重试下载`() {
        assertEquals(
            ReaderStatusBadge.RetryDownload,
            readerStatusBadge(ReaderStreamState.Local, BookDownloadState.Failed("网络错误")),
        )
        assertEquals(
            ReaderStatusBadge.RetryDownload,
            readerStatusBadge(ReaderStreamState.Streaming, BookDownloadState.Failed("网络错误")),
        )
    }

    @Test
    fun `下载百分比向下取整且越界归一`() {
        assertEquals(0, downloadPercent(0f))
        assertEquals(0, downloadPercent(-0.5f))
        assertEquals(42, downloadPercent(0.42f))
        // 99.6% 不能提前显示成 100%（100% 只属于下载真正完成）。
        assertEquals(99, downloadPercent(0.996f))
        assertEquals(99, downloadPercent(0.999f))
        assertEquals(100, downloadPercent(1f))
        assertEquals(100, downloadPercent(1.7f))
    }
}
