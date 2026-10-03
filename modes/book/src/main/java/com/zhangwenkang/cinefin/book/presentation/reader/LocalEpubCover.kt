package com.zhangwenkang.cinefin.book.presentation.reader

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.cover
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

/**
 * W45：本地书籍封面（EPUB）——用 Readium 的 `Publication.cover()` 读 metadata 声明的封面位图。
 *
 * 只服务「本地媒体库缩略图」这一用途：不改阅读链路（[ReaderViewModel] 打开书籍时的资产 / 进度语义不变）。 解析失败 / 无封面（metadata 未声明且启发式也找不到）返回
 * null，调用方回退类型图标。
 *
 * 放在 `modes:book` 的原因：Readium 是 `implementation` 依赖，只有本模块能直接用； 对外只暴露 [Bitmap]（Android 类型），不把
 * Readium 类型带进 `app:phone` 的编译面。
 */
object LocalEpubCover {

    suspend fun load(context: Context, documentUri: String): Bitmap? =
        withContext(Dispatchers.IO) {
            try {
                loadCover(context, documentUri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                null
            }
        }

    private suspend fun loadCover(context: Context, documentUri: String): Bitmap? {
        val httpClient = DefaultHttpClient()
        val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
        val url = Url(documentUri) as? AbsoluteUrl ?: return null
        val asset = assetRetriever.retrieve(url).getOrNull() ?: return null
        var publication: Publication? = null
        return try {
            val parser =
                DefaultPublicationParser(
                    context = context,
                    httpClient = httpClient,
                    assetRetriever = assetRetriever,
                    pdfFactory = null,
                )
            publication =
                PublicationOpener(parser).open(asset, allowUserInteraction = false).getOrNull()
            publication?.cover()
        } finally {
            runCatching { publication?.close() }
            runCatching { asset.close() }
        }
    }
}
