package com.zhangwenkang.cinefin.presentation.film.components

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason

enum class Direction {
    HORIZONTAL,
    VERTICAL,
    /** 方形封面：音乐专辑、播客封面这类 1:1 的图 */
    SQUARE,
}

/**
 * W73（#10）：季的**海报位**取图口径（纯函数，便于单测）——季自身海报 → 剧集海报。
 *
 * 服务器的「未知季」（无季号的虚拟季分组）不生成任何图片，官方客户端同样回落显示剧集海报； 不回落就是一块深灰占位（用户 2026-10-06 截图 1 的「未知季」空卡、截图 2
 * 的无图详情海报）。
 */
internal fun <T> seasonPosterImage(primary: T?, showPrimary: T?): T? = primary ?: showPrimary

/**
 * W73（#10）：季的**头图位**（hero / backdrop）取图口径——季 backdrop → 剧集 backdrop → 季海报 → 剧集海报。
 *
 * 与海报位同源：未知季自己没有 backdrop，回落剧集 backdrop（剧集也没有 backdrop 时再退到海报）， 保证季详情页头图不空白（用户 2026-10-06 截图 2）。
 */
internal fun <T> seasonBackdropImage(
    backdrop: T?,
    showBackdrop: T?,
    primary: T?,
    showPrimary: T?,
): T? = backdrop ?: showBackdrop ?: primary ?: showPrimary

@Composable
fun ItemPoster(
    item: FindroidItem,
    direction: Direction,
    modifier: Modifier = Modifier,
    /** W59：书籍封面自动生成结果（本地绝对路径）；非空时直接覆盖服务器图。 */
    imageOverride: String? = null,
    /** W59：无图时的类型占位图标（书籍 = `ic_book`）；null = 保持原有空白底。 */
    @DrawableRes placeholderIconRes: Int? = null,
    /** W69b：**服务器图加载失败**（404 / 取图失败）时回调——书籍库 / 书架据此请求「本地生成」回落； null = 不需要回落（视频 / 音乐卡等）。 */
    onServerImageFailed: (() -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var serverImageFailed by remember(item.id) { mutableStateOf(false) }

    val serverUri: Uri? =
        when (direction) {
            // W54-B：家庭视频（`BaseItemKind.VIDEO`）在映射层同样落到 `FindroidMovie`，但视频没有元数据
            // 来源、也就没有 Backdrop——原逻辑一律取 Backdrop 会让整个家庭视频库缩略图空白；
            // 只有真的存在 Backdrop 时才用它，否则回退到 Primary（缩略图本来就是 Primary）。
            Direction.HORIZONTAL ->
                if (item is FindroidMovie) item.images.backdrop ?: item.images.primary
                else item.images.primary
            Direction.VERTICAL ->
                when (item) {
                    is FindroidEpisode -> item.images.showPrimary
                    // W73（#10）：季自身没有海报时回落剧集图——服务器的「未知季」分组不建图，
                    // 官方客户端同样显示剧集海报；剧集（Show）卡仍只用自身海报，不跨级回落。
                    is FindroidSeason ->
                        seasonPosterImage(item.images.primary, item.images.showPrimary)
                    else -> item.images.primary
                }
            Direction.SQUARE -> item.images.primary
        }
    val imageUri: Any? =
        when {
            // 生成封面是应用私有目录的绝对路径，直接交给 Coil（不能再拼 filesDir）。
            imageOverride != null -> imageOverride
            serverUri != null && !serverImageFailed ->
                // Ugly workaround to append the files directory when loading local images
                if (serverUri.scheme == null) {
                    Uri.Builder()
                        .appendEncodedPath("${context.filesDir}")
                        .appendEncodedPath(serverUri.path)
                        .build()
                } else {
                    serverUri
                }
            else -> null
        }

    val aspectRatio =
        when (direction) {
            Direction.HORIZONTAL -> 1.77f
            Direction.VERTICAL -> 0.66f
            Direction.SQUARE -> 1f
        }

    // W69c：类型占位常驻底层（书籍 = 书图标 / 音乐 = 音符 + 媒体色底）——无图 / 加载中 / 失败都不出黑块；
    // 生成封面或服务器图就绪后覆盖在上层。
    if (placeholderIconRes != null) {
        BookCoverPlaceholder(
            iconRes = placeholderIconRes,
            modifier = modifier.aspectRatio(aspectRatio),
            iconSize = 28.dp,
        )
    }

    // W69：图片模型变化（刷新 / 回落本地封面）时保留上一张图，避免海报先变黑底。
    RetainedAsyncImage(
        model = imageUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        retainKey = item.id,
        placeholderPainter =
            if (placeholderIconRes != null) null else ColorPainter(colors.surfaceContainerHigh),
        errorPainter =
            if (placeholderIconRes != null) null else ColorPainter(colors.surfaceContainerHigh),
        onError = {
            // W69b：服务器图取不到（404 等）→ 标记失败（书籍卡回退占位 / 触发本地生成回落）。
            if (imageOverride == null && serverUri != null && !serverImageFailed) {
                serverImageFailed = true
                onServerImageFailed?.invoke()
            }
        },
        modifier =
            modifier
                .aspectRatio(aspectRatio)
                .background(
                    if (placeholderIconRes != null) Color.Transparent
                    else colors.surfaceContainerHigh
                ),
    )
}
