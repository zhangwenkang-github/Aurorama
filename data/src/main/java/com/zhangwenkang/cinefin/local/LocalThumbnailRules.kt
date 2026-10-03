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
 * - 首页库卡封面 = 「第一个已有封面 / 可生成封面」的条目，候选顺序 **视频 → 书籍 → 音乐**；
 * - W49：视频按「1s → 10% → 30% → 0s」候选取帧并跳过近黑帧；CBZ 取**自然序**第一张页图（与阅读器同一比较器）。
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

    /** 候选帧的默认起点：第 1 秒（微秒）。 */
    const val VIDEO_FIRST_FRAME_US = 1_000_000L

    /** 近黑帧判定的平均相对亮度上限（0–1）：低于 / 等于该值的候选帧换下一个时间点。 */
    const val VIDEO_BLACK_LUMA_MAX = 0.10

    /** 近黑帧判定的采样位图边长（像素）：32 × 32 足够反映整体亮度，`getPixels` 开销可忽略。 */
    const val VIDEO_LUMA_SAMPLE_SIDE_PX = 32

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
     * 删除一组条目的缩略图缓存与失败标记（W47：库 / 文件夹移除后清理，避免 `files/local_thumbs` 残留）。
     *
     * 只删 `<itemId>.jpg` / `<itemId>.fail` 两类文件，返回实际删除的文件数。
     */
    fun purgeThumbnails(filesDir: File, itemIds: Collection<UUID>): Int = itemIds.sumOf { itemId ->
        (if (cacheFile(filesDir, itemId).delete()) 1 else 0) +
            (if (failureMarker(filesDir, itemId).delete()) 1 else 0)
    }

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

    /**
     * 视频候选取帧时间点（微秒，按优先级去重）：**第 1 秒 → 时长 10% → 时长 30% → 第 0 秒**。
     *
     * 黑场片源在第 1 秒可能取到黑帧（W45 遗留），由调用方按 [isNearlyBlack] 逐个跳过； 时长未知（null / ≤0）时只保留第 1 秒与第 0 秒（短于 1
     * 秒的视频由第 0 秒兜底）。
     */
    fun videoFrameTimesUs(durationMs: Long?): List<Long> {
        val times = mutableListOf(VIDEO_FIRST_FRAME_US)
        if (durationMs != null && durationMs > 0) {
            times += durationMs * 100L
            times += durationMs * 300L
        }
        times += 0L
        return times.distinct()
    }

    /** 采样像素的平均相对亮度（sRGB 0–255 → 0–1；空数组按 0 处理）。 */
    fun meanLuma(pixels: IntArray): Double {
        if (pixels.isEmpty()) return 0.0
        var sum = 0.0
        for (pixel in pixels) {
            val red = (pixel shr 16) and 0xFF
            val green = (pixel shr 8) and 0xFF
            val blue = pixel and 0xFF
            sum += (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
        }
        return sum / pixels.size
    }

    /** 是否近黑帧（平均相对亮度 ≤ [maxLuma]）。 */
    fun isNearlyBlack(pixels: IntArray, maxLuma: Double = VIDEO_BLACK_LUMA_MAX): Boolean =
        meanLuma(pixels) <= maxLuma

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
     * CBZ 页名自然序比较（W49，从阅读器 `ComicPageOrder` 下沉复用）：连续数字按数值比较 （`p2.jpg` 排在 `p10.jpg`
     * 之前），大小写不敏感，包含关系用剩余长度兜底（`a.jpg` < `a1.jpg`）。
     */
    fun compareComicPageNames(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var iEnd = i
                while (iEnd < a.length && a[iEnd].isDigit()) iEnd++
                var jEnd = j
                while (jEnd < b.length && b[jEnd].isDigit()) jEnd++
                val numberA = a.substring(i, iEnd).trimStart('0')
                val numberB = b.substring(j, jEnd).trimStart('0')
                val byLength = numberA.length.compareTo(numberB.length)
                if (byLength != 0) return byLength
                val byDigits = numberA.compareTo(numberB)
                if (byDigits != 0) return byDigits
                i = iEnd
                j = jEnd
            } else {
                val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byChar != 0) return byChar
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }

    /** 归档顺序的页名 → 自然序（只排序、不过滤；调用方先按 [isComicPageImage] 筛过）。 */
    fun sortedComicPageNames(names: List<String>): List<String> =
        names.sortedWith(::compareComicPageNames)

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
