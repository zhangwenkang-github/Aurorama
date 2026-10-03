package com.zhangwenkang.cinefin.local

import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * W45 本地缩略图 / 封面规则（纯函数，便于 JVM 单测）。
 *
 * 口径（用户 2026-10-03 确认）：
 * - **懒生成**：列表 / 卡片可见时按需生成，磁盘缓存 `files/local_thumbs/<itemId>.jpg`；
 * - 统一 **JPEG ~80、最长边 ≤512px**；
 * - **失败标记** `files/local_thumbs/<itemId>.fail`：失败条目不再反复重试（回退类型图标）；
 * - 音乐沿用 W37 既有链路（内嵌标签 → 同目录封面），不落 `local_thumbs`；
 * - 首页库卡封面 = 「第一个已有封面 / 可生成封面」的条目，候选顺序 **视频 → 书籍 → 音乐**。
 */
object LocalThumbnailRules {

    /** 缩略图缓存目录（`context.filesDir` 下）。 */
    const val CACHE_DIR = "local_thumbs"

    /** 生成位图最长边。 */
    const val MAX_SIDE_PX = 512

    /** JPEG 压缩质量。 */
    const val JPEG_QUALITY = 80

    /** 同时生成的缩略图数量上限（不阻塞主线程）。 */
    const val MAX_CONCURRENT = 2

    /** 单个库卡封面最多现场生成的候选数（避免为一个库刷出上百张缩略图）。 */
    const val MAX_COVER_ATTEMPTS = 3

    /** 视频首帧取值顺序：第 1 秒 → 回退第 0 秒（微秒）。 */
    val VIDEO_FRAME_TIMES_US = listOf(1_000_000L, 0L)

    /** 书籍类型：只有这三类需要现场生成首页封面。 */
    private val BOOK_EXTENSIONS = setOf("pdf", "cbz", "epub")

    /** CBZ 页图扩展名（顺序流取第一张图）。 */
    private val COMIC_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

    /** 首页库卡封面候选顺序（视频 → 书籍 → 音乐）。 */
    private val COVER_KIND_ORDER =
        listOf(LocalMediaKind.VIDEO, LocalMediaKind.BOOK, LocalMediaKind.MUSIC)

    private val ENTRY_ORDER =
        compareBy<LocalLibraryEntry>({ it.relativePath.lowercase() }, { it.name.lowercase() })

    fun cacheDir(filesDir: File): File = File(filesDir, CACHE_DIR)

    /** 缩略图缓存路径：`files/local_thumbs/<itemId>.jpg`。 */
    fun cacheFile(filesDir: File, itemId: UUID): File = File(cacheDir(filesDir), "$itemId.jpg")

    /** 失败标记路径：`files/local_thumbs/<itemId>.fail`（存在即不再重试）。 */
    fun failureMarker(filesDir: File, itemId: UUID): File = File(cacheDir(filesDir), "$itemId.fail")

    /**
     * 缩略图目标尺寸（长边 ≤ [maxSide]，保持比例，至少 1px）。
     *
     * 非正尺寸 / 非正上限返回 null（调用方回退类型图标）。
     */
    fun thumbSize(width: Int, height: Int, maxSide: Int = MAX_SIDE_PX): Pair<Int, Int>? {
        if (width <= 0 || height <= 0 || maxSide <= 0) return null
        val scale = min(1.0, maxSide.toDouble() / max(width, height).toDouble())
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** 视频帧目标尺寸：按旋转角折算显示宽高后再取长边 ≤ [maxSide]。 */
    fun videoTargetSize(
        videoWidth: Int,
        videoHeight: Int,
        rotationDegrees: Int,
        maxSide: Int = MAX_SIDE_PX,
    ): Pair<Int, Int>? {
        val swapped = rotationDegrees % 360 == 90 || rotationDegrees % 360 == 270
        return if (swapped) thumbSize(videoHeight, videoWidth, maxSide)
        else thumbSize(videoWidth, videoHeight, maxSide)
    }

    /** 该条目是否已具备可用封面（不触发任何生成）。 */
    fun hasCover(filesDir: File, entry: LocalLibraryEntry): Boolean =
        when (entry.kind) {
            // 音乐：内嵌封面（`files/local_covers/<id>.jpg`）或同目录封面（content://）都已存在。
            LocalMediaKind.MUSIC -> !entry.coverUri.isNullOrBlank()
            LocalMediaKind.VIDEO,
            LocalMediaKind.BOOK ->
                cacheFile(filesDir, entry.itemId).let { it.isFile && it.length() > 0 }
        }

    /** 书籍是否需要首页封面生成（PDF / CBZ / EPUB）。 */
    fun needsBookCover(entry: LocalLibraryEntry): Boolean =
        entry.kind == LocalMediaKind.BOOK &&
            LocalMediaExtensions.extensionOf(entry.name) in BOOK_EXTENSIONS

    /** CBZ 顺序流里的候选页图（跳过目录项、隐藏文件与 `__MACOSX` 资源叉）。 */
    fun isComicPageImage(entryName: String, isDirectory: Boolean): Boolean {
        if (isDirectory) return false
        val normalized = entryName.replace('\\', '/')
        if (normalized.contains("__MACOSX")) return false
        val baseName = normalized.substringAfterLast('/')
        if (baseName.isEmpty() || baseName.startsWith('.')) return false
        return LocalMediaExtensions.extensionOf(baseName) in COMIC_IMAGE_EXTENSIONS
    }

    /**
     * 首页库卡封面候选（稳定排序）：视频 → 书籍 → 音乐，同类型按相对路径 / 文件名升序。
     *
     * 顺序即「优先有缩略图的第一项」的比较顺序。
     */
    fun coverCandidates(entries: List<LocalLibraryEntry>): List<LocalLibraryEntry> =
        COVER_KIND_ORDER.flatMap { kind ->
            entries.filter { it.kind == kind }.sortedWith(ENTRY_ORDER)
        }

    /** 库卡封面计划：已有封面直接命中；否则按候选顺序最多现场生成 [maxAttempts] 张。 */
    data class CoverPlan(val ready: LocalLibraryEntry?, val attempts: List<LocalLibraryEntry>)

    fun planCover(
        candidates: List<LocalLibraryEntry>,
        readyIds: Set<UUID>,
        maxAttempts: Int = MAX_COVER_ATTEMPTS,
    ): CoverPlan =
        CoverPlan(
            ready = candidates.firstOrNull { it.itemId in readyIds },
            attempts =
                if (candidates.any { it.itemId in readyIds }) emptyList()
                else candidates.take(max(0, maxAttempts)),
        )
}
