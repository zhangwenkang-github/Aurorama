package com.zhangwenkang.cinefin.presentation.film.components

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie

enum class Direction {
    HORIZONTAL,
    VERTICAL,
    /** 方形封面：音乐专辑、播客封面这类 1:1 的图 */
    SQUARE,
}

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
                if (item is FindroidEpisode) item.images.showPrimary else item.images.primary
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

    if (imageUri == null && placeholderIconRes != null) {
        // W59：生成失败 / 不可生成时回退类型占位图（不再是黑块空白）。
        Box(
            modifier = modifier.aspectRatio(aspectRatio).background(colors.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(placeholderIconRes),
                contentDescription = null,
                tint = colors.onSurfaceFaint,
                modifier = Modifier.size(28.dp),
            )
        }
        return
    }

    // W69：图片模型变化（刷新 / 回落本地封面）时保留上一张图，避免海报先变黑底。
    RetainedAsyncImage(
        model = imageUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        retainKey = item.id,
        placeholderPainter = ColorPainter(colors.surfaceContainerHigh),
        onError = {
            // W69b：服务器图取不到（404 等）→ 标记失败（书籍卡回退占位 / 触发本地生成回落）。
            if (imageOverride == null && serverUri != null && !serverImageFailed) {
                serverImageFailed = true
                onServerImageFailed?.invoke()
            }
        },
        modifier = modifier.aspectRatio(aspectRatio).background(colors.surfaceContainerHigh),
    )
}
