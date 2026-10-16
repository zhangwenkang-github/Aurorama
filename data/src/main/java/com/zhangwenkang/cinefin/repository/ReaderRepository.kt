package com.zhangwenkang.cinefin.repository

import java.io.File
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient

/**
 * 阅读器数据契约（ARCHITECTURE §5.1）。
 *
 * 服务端进度走 Jellyfin UserData 白名单接口；`locatorJson` 保存 Readium 的精确位置。 本地存储仍是应用私有 JSON 文件（W3-R1 决策
 * D11：Room 迁移待 R2-LYRICS 释放数据库版本后再做，见 READER_PLAN）。
 */
interface ReaderRepository {
    /**
     * 打开书籍用：本地已有直接命中，否则整本下载到应用私有目录。
     *
     * W77-3：`onProgress`（0.0–1.0）只在**确实发生整本下载**时上报，且下载开始前先给一次 `0f`（长度未知的响应也能进「下载中」 状态）；本地已有文件时只给一次
     * `1f` 后立即返回 —— 调用方据此在 Loading 态显示「下载中 xx%」（PDF / 未知格式 / 离线回退 都走这条路径），不会为本地命中的书闪一次「下载中 100%」。
     */
    suspend fun ensureLocalFile(itemId: UUID, onProgress: (Float) -> Unit = {}): File

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

    /**
     * W80（#23）：书籍整本下载任务表（进程内，`RUNNING` / `FAILED`）。
     *
     * 下载页据此把进行中的书籍下载显示成「下载中 xx%」条目（完成后由 [listLocalFiles] 的已完成列表接手）。
     */
    val bookDownloads: StateFlow<List<BookDownloadTask>>

    /**
     * W80（#23）：把整本下载登记为**后台任务**（App 作用域，离开阅读页 / 切换页面都不会取消）。
     *
     * 与 [downloadLocalFile] 的区别：后者绑定调用方协程（W64 语义：离开阅读页取消在途 HTTP），
     * 用于"打开书时按需落盘"；用户**主动点下载**走本入口，任务继续跑并在下载页可见。
     */
    suspend fun enqueueBookDownload(
        itemId: UUID,
        title: String? = null,
    ): BookDownloadEnqueueResult

    /** W80（#23）：等待（必要时先入队）某本书的整本下载，`onProgress` 为 0.0–1.0。 */
    suspend fun awaitBookDownload(
        itemId: UUID,
        title: String? = null,
        onProgress: (Float) -> Unit = {},
    ): File

    /** W80（#23）：取消进行中的后台下载任务（没有任务时无操作）。 */
    fun cancelBookDownload(itemId: UUID)

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
