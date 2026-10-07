package com.zhangwenkang.cinefin.repository

import java.io.File
import java.time.Instant
import java.util.UUID
import okhttp3.OkHttpClient

/**
 * 阅读器数据契约（ARCHITECTURE §5.1）。
 *
 * 服务端进度走 Jellyfin UserData 白名单接口；`locatorJson` 保存 Readium 的精确位置。 本地存储仍是应用私有 JSON 文件（W3-R1 决策
 * D11：Room 迁移待 R2-LYRICS 释放数据库版本后再做，见 READER_PLAN）。
 */
interface ReaderRepository {
    /** 打开书籍用：本地已有直接命中，否则整本下载到应用私有目录。 */
    suspend fun ensureLocalFile(itemId: UUID): File

    /**
     * W77 阅读流式：未下载书籍的远程读取入口（`GET /Items/{id}/Download` + 访问令牌）。
     *
     * 没有登录会话 / 服务器地址时返回 null，调用方按既有「整本下载」路径处理。
     */
    suspend fun remoteBookSource(itemId: UUID): RemoteBookSource?

    /**
     * W77 阅读流式：阅读页远程资产共用的 OkHttp 客户端（已应用应用级 TOFU 自签证书信任）。
     *
     * Readium 自带的 `DefaultHttpClient` 走 `HttpURLConnection` + 系统信任库，拿不到本应用的 TOFU 信任， 因此远程 EPUB
     * 必须复用这里的客户端。调用方应在其基础上派生独立的 `Dispatcher` / `ConnectionPool`（避免「离开阅读页取消在途请求」时误伤其它网络任务）。
     */
    fun readerHttpClient(): OkHttpClient

    /** 显式下载（离线阅读 EB-11）：`onProgress` 为 0.0–1.0。 */
    suspend fun downloadLocalFile(itemId: UUID, onProgress: (Float) -> Unit = {}): File

    /** 本地已下载书籍信息；未下载返回 null。 */
    suspend fun localFile(itemId: UUID): LocalBookFile?

    /** W34 下载列表：全部已离线书籍（下载页「书籍」层级用）。 */
    suspend fun listLocalFiles(): List<LocalBookFile>

    suspend fun deleteLocalFile(itemId: UUID)

    suspend fun getReadingProgress(itemId: UUID): ReadingProgress?

    suspend fun saveReadingProgress(itemId: UUID, progress: ReadingProgress)

    /** 联网回传待同步进度，返回成功条数（EB-9）。 */
    suspend fun flushPendingProgress(): Int

    /** 待同步进度条数，用于阅读页提示与定时重试。 */
    suspend fun pendingProgressCount(): Int

    suspend fun getBookmarks(itemId: UUID): List<ReaderBookmark>

    suspend fun saveBookmark(bookmark: ReaderBookmark)

    suspend fun deleteBookmark(itemId: UUID, bookmarkId: String)
}

/** W77 阅读流式：远程书籍读取入口。 */
data class RemoteBookSource(
    /** `/Items/{id}/Download`（支持 HTTP Range）。 */
    val url: String,
    /** `X-Emby-Token` 访问令牌。 */
    val token: String,
)

/** 已下载书籍的本地文件信息。W36：`title` 为下载时落盘的书名（缺省 = 旧数据没有标题侧车）。 */
data class LocalBookFile(val itemId: UUID, val sizeBytes: Long, val title: String? = null)

data class ReadingProgress(
    val itemId: UUID,
    val locatorJson: String = "",
    val progression: Double,
    val positionTicks: Long,
    val updatedAt: Instant,
    val pendingSync: Boolean = false,
    /**
     * 该书目的 Jellyfin `RunTimeTicks` 缓存。
     *
     * 书籍的 runtime 常是 1 秒占位值，离线时拿不到；没有缓存会把 ticks 按默认时间轴换算， 回传后服务端进度会明显偏大（见 READER_PLAN §8 踩坑 4 /
     * 12）。
     */
    val runtimeTicks: Long = 0L,
)
