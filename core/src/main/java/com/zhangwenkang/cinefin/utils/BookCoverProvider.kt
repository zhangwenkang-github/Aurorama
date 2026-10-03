package com.zhangwenkang.cinefin.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.ScratchFile
import com.tom_roush.pdfbox.pdfparser.PDFParser
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.local.LocalThumbnailRules
import com.zhangwenkang.cinefin.network.buildCertificateAwareOkHttpClient
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.xmlpull.v1.XmlPullParser
import timber.log.Timber

/**
 * W59 在线书籍封面自动生成（标准落点，`app:phone` / `modes:film` / `modes:book` 共用）。
 *
 * - **服务器图优先**由调用方判断（[cached] 只在没有服务器图时使用）；
 * - 没有服务器图时懒生成：PDF 首页 / CBZ 第一图 / EPUB 封面；
 * - 已下载书籍直接读 `files/books/<itemId>.book`；未下载书籍走 `Items/<id>/Download` 的 **HTTP Range**
 *   只读片段（[HttpByteSource]）；
 * - 缓存 `files/book_covers/<itemId>.jpg`，失败写 `.fail` 标记（不再重试，UI 回退类型占位图）。
 */
@Singleton
class BookCoverProvider
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: JellyfinRepository,
    private val jellyfinApi: JellyfinApi,
) {

    private val semaphore = Semaphore(BookCoverRules.MAX_CONCURRENT)
    private val failed = ConcurrentHashMap.newKeySet<UUID>()
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<UUID, CompletableDeferred<String?>>()

    private val httpClient: OkHttpClient by lazy {
        buildCertificateAwareOkHttpClient(
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build(),
            jellyfinApi.certificateTrustStore,
        )
    }

    /** 同步查已生成的封面（Compose 首帧 / 下载页行用）：返回可直接喂给 Coil 的绝对路径。 */
    fun cached(itemId: UUID): String? =
        BookCoverRules.cacheFile(context.filesDir, itemId)
            .takeIf { it.isFile && it.length() > 0L }
            ?.absolutePath

    /** 是否已标记失败（不再重试）。 */
    fun isMarkedFailed(itemId: UUID): Boolean {
        if (itemId in failed) return true
        val marked = BookCoverRules.failureMarker(context.filesDir, itemId).isFile
        if (marked) failed += itemId
        return marked
    }

    /**
     * 懒生成（条目可见时调用一次）。
     *
     * 计划（[BookCoverRules.planCover]）：服务器图优先 → 已生成缓存 → 生成 → 失败占位； 生成路径 = 本地已下载书籍优先，其次 HTTP
     * Range；同一条目的并发请求合并为一次生成。
     */
    suspend fun ensureCover(itemId: UUID, serverImageUrl: String? = null): String? =
        when (
            BookCoverRules.planCover(
                serverImageUrl = serverImageUrl,
                generatedPath = cached(itemId),
                generationFailed = cached(itemId) == null && isMarkedFailed(itemId),
            )
        ) {
            // 服务器图优先：有服务器图时不需要生成。
            BookCoverRules.CoverSource.SERVER_IMAGE -> null
            BookCoverRules.CoverSource.GENERATED_CACHE -> cached(itemId)
            BookCoverRules.CoverSource.PLACEHOLDER -> null
            BookCoverRules.CoverSource.GENERATE -> generateOnce(itemId)
        }

    private suspend fun generateOnce(itemId: UUID): String? {
        val (deferred, owner) =
            mutex.withLock {
                inFlight[itemId]?.let { it to false }
                    ?: CompletableDeferred<String?>().also { inFlight[itemId] = it } to true
            }
        if (!owner) return deferred.await()
        return try {
            val result = withContext(Dispatchers.IO) { semaphore.withPermit { generate(itemId) } }
            deferred.complete(result)
            result
        } catch (cancellation: CancellationException) {
            deferred.completeExceptionally(cancellation)
            throw cancellation
        } catch (error: Throwable) {
            deferred.completeExceptionally(error)
            throw error
        } finally {
            mutex.withLock { inFlight.remove(itemId) }
        }
    }

    // ------------------------------------------------------------ 生成

    private fun generate(itemId: UUID): String? {
        val bitmap =
            try {
                extract(itemId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                Timber.w(error, "W59 书籍封面生成失败：$itemId")
                null
            }
        if (bitmap == null) {
            markFailed(itemId)
            return null
        }
        val written = writeCover(itemId, bitmap)
        bitmap.recycle()
        if (written == null) markFailed(itemId)
        return written
    }

    private fun extract(itemId: UUID): Bitmap? {
        val local = File(context.filesDir, "books/$itemId.book")
        val source: ByteSource =
            if (local.isFile && local.length() > 0L) FileByteSource(local)
            else openRemoteSource(itemId)
        source.use { bytes ->
            return when (BookCoverRules.detectKind(bytes.readAt(0, PREFIX_BYTES))) {
                BookCoverRules.Kind.PDF -> pdfCover(bytes)
                BookCoverRules.Kind.ZIP -> zipCover(bytes)
                BookCoverRules.Kind.UNKNOWN -> null
            }
        }
    }

    private fun openRemoteSource(itemId: UUID): ByteSource {
        val baseUrl = repository.getBaseUrl().trimEnd('/')
        val token = repository.getAccessToken()
        val headers = if (token.isNullOrBlank()) emptyMap() else mapOf("X-Emby-Token" to token)
        return HttpByteSource(
            client = httpClient,
            url = "$baseUrl/Items/$itemId/Download",
            headers = headers,
        )
    }

    /** PDF 首页：PdfBox 通过 [PdfBoxRandomAccess] 按需读取，白底渲染后回收。 */
    private fun pdfCover(source: ByteSource): Bitmap? {
        ensurePdfBoxReady()
        val scratchDir = File(context.cacheDir, "book_cover_scratch").apply { mkdirs() }
        val scratch = ScratchFile(scratchDir)
        try {
            val parser = PDFParser(PdfBoxRandomAccess(source), scratch)
            parser.parse()
            val document = parser.getPDDocument() ?: return null
            document.use { doc ->
                if (doc.getNumberOfPages() <= 0) return null
                val page = doc.getPage(0)
                val box = page.getCropBox() ?: page.getMediaBox() ?: return null
                val width = box.getWidth()
                val height = box.getHeight()
                if (width <= 0f || height <= 0f) return null
                val scale = (BookCoverRules.MAX_SIDE_PX / maxOf(width, height)).coerceIn(0.1f, 2f)
                val rendered = PDFRenderer(doc).renderImage(0, scale) ?: return null
                return flattenOnWhite(rendered)
            }
        } finally {
            runCatching { scratch.close() }
        }
    }

    /** 透明底 PDF 渲染结果铺白，避免 JPEG 出黑底。 */
    private fun flattenOnWhite(rendered: Bitmap): Bitmap {
        val flattened =
            Bitmap.createBitmap(rendered.width, rendered.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(flattened)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(rendered, 0f, 0f, null)
        if (flattened !== rendered) rendered.recycle()
        return flattened
    }

    private fun ensurePdfBoxReady() {
        if (!PDFBoxResourceLoader.isReady()) {
            PDFBoxResourceLoader.init(context.applicationContext)
        }
    }

    /** EPUB / CBZ：只解出封面 / 第一张页图的条目。 */
    private fun zipCover(source: ByteSource): Bitmap? {
        val archive = ZipArchiveReader(source)
        return archive.use { zip ->
            when (BookCoverRules.zipKind(zip.find(EPUB_CONTAINER) != null)) {
                BookCoverRules.ZipKind.EPUB -> epubCover(zip)
                BookCoverRules.ZipKind.CBZ -> cbzCover(zip)
            }
        }
    }

    private fun epubCover(zip: ZipArchiveReader): Bitmap? {
        val opfPath = readEpubOpfPath(zip) ?: return null
        val opfEntry = zip.find(opfPath) ?: return null
        val opf = parseOpf(zip.readBytes(opfEntry, XML_MAX_BYTES))
        val href = EpubCoverRules.selectCoverHref(opf.items, opf.coverMetaId) ?: return null
        val coverPath = EpubCoverRules.resolveHref(opfPath, href)
        val coverEntry =
            zip.find(coverPath)
                ?: zip.entries.firstOrNull { it.name.equals(coverPath, ignoreCase = true) }
                ?: return null
        return decodeThumb(zip.readBytes(coverEntry))
    }

    private fun cbzCover(zip: ZipArchiveReader): Bitmap? {
        val names =
            zip.entries
                .filter { entry ->
                    LocalThumbnailRules.isComicPageImage(entry.name, false) &&
                        entry.uncompressedSize in 1..BookCoverRules.MAX_ENTRY_BYTES
                }
                .map { it.name }
        val first = LocalThumbnailRules.sortedComicPageNames(names).firstOrNull() ?: return null
        val entry = zip.find(first) ?: return null
        return decodeThumb(zip.readBytes(entry))
    }

    private fun readEpubOpfPath(zip: ZipArchiveReader): String? {
        val container = zip.find(EPUB_CONTAINER) ?: return null
        val xml = zip.readBytes(container, XML_MAX_BYTES)
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") {
                val path = parser.getAttributeValue(null, "full-path")
                if (!path.isNullOrBlank()) return path
            }
            event = parser.next()
        }
        return null
    }

    private data class OpfManifest(
        val items: List<EpubCoverRules.ManifestItem>,
        val coverMetaId: String?,
    )

    private fun parseOpf(xml: ByteArray): OpfManifest {
        val items = mutableListOf<EpubCoverRules.ManifestItem>()
        var coverMetaId: String? = null
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xml), null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            items +=
                                EpubCoverRules.ManifestItem(
                                    id = id,
                                    href = href,
                                    mediaType =
                                        parser.getAttributeValue(null, "media-type").orEmpty(),
                                    properties =
                                        parser.getAttributeValue(null, "properties").orEmpty(),
                                )
                        }
                    }
                    "meta" -> {
                        if (
                            parser
                                .getAttributeValue(null, "name")
                                .equals(
                                    "cover",
                                    ignoreCase = true,
                                )
                        ) {
                            coverMetaId =
                                parser.getAttributeValue(null, "content")?.takeIf {
                                    it.isNotBlank()
                                }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return OpfManifest(items, coverMetaId)
    }

    // ------------------------------------------------------------ 位图 → 磁盘

    /** 解码页图：先按边界采样降尺寸（长边 ≤ 1024），再统一缩到封面口径（长边 ≤ 512）。 */
    private fun decodeThumb(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= DECODE_MAX_SIDE_PX &&
                bounds.outHeight / (sample * 2) >= DECODE_MAX_SIDE_PX
        ) {
            sample *= 2
        }
        val decoded =
            BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
        return scaleToCover(decoded)
    }

    /** 最长边收到 [BookCoverRules.MAX_SIDE_PX]（已在范围内原样返回）。 */
    private fun scaleToCover(bitmap: Bitmap): Bitmap {
        val size =
            LocalThumbnailRules.thumbSize(
                bitmap.width,
                bitmap.height,
                BookCoverRules.MAX_SIDE_PX,
            ) ?: return bitmap
        if (size.first == bitmap.width && size.second == bitmap.height) return bitmap
        val scaled = runCatching {
            Bitmap.createScaledBitmap(bitmap, size.first, size.second, true)
        }
            .getOrDefault(bitmap)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    /** 写 `files/book_covers/<itemId>.jpg`（先写 `.tmp` 再改名，避免半截文件被当命中）。 */
    private fun writeCover(itemId: UUID, bitmap: Bitmap): String? = runCatching {
        val dir = BookCoverRules.cacheDir(context.filesDir).apply { mkdirs() }
        val target = BookCoverRules.cacheFile(context.filesDir, itemId)
        val temporary = File(dir, "${target.name}.tmp")
        val encoded =
            FileOutputStream(temporary).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, BookCoverRules.JPEG_QUALITY, output)
            }
        if (!encoded || temporary.length() <= 0L) {
            temporary.delete()
            return@runCatching null
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.delete()
            return@runCatching null
        }
        runCatching { BookCoverRules.failureMarker(context.filesDir, itemId).delete() }
        target.absolutePath
    }
        .getOrNull()

    private fun markFailed(itemId: UUID) {
        failed += itemId
        runCatching {
            val marker = BookCoverRules.failureMarker(context.filesDir, itemId)
            marker.parentFile?.mkdirs()
            marker.writeBytes(ByteArray(0))
        }
    }

    private companion object {
        const val PREFIX_BYTES = 16
        const val XML_MAX_BYTES = 2L * 1024 * 1024
        const val DECODE_MAX_SIDE_PX = 1024
        const val EPUB_CONTAINER = "META-INF/container.xml"
    }
}
