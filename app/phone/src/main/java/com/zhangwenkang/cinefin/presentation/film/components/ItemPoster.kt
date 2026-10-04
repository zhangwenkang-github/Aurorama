package com.zhangwenkang.cinefin.presentation.film.components

import android.net.Uri
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
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
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var imageUri: Any? = item.images.primary

    if (imageOverride != null) {
        // 生成封面是应用私有目录的绝对路径，直接交给 Coil（不能再拼 filesDir）。
        imageUri = imageOverride
    } else {
        when (direction) {
            Direction.HORIZONTAL -> {
                // W54-B：家庭视频（`BaseItemKind.VIDEO`）在映射层同样落到 `FindroidMovie`，但视频没有元数据
                // 来源、也就没有 Backdrop——原逻辑一律取 Backdrop 会让整个家庭视频库缩略图空白；
                // 只有真的存在 Backdrop 时才用它，否则回退到 Primary（缩略图本来就是 Primary）。
                if (item is FindroidMovie) imageUri = item.images.backdrop ?: item.images.primary
            }
            Direction.VERTICAL -> {
                when (item) {
                    is FindroidEpisode -> imageUri = item.images.showPrimary
                }
            }
            Direction.SQUARE -> Unit
        }

        // Ugly workaround to append the files directory when loading local images
        if ((imageUri as? Uri)?.scheme == null) {
            imageUri =
                Uri.Builder()
                    .appendEncodedPath("${context.filesDir}")
                    .appendEncodedPath((imageUri as? Uri)?.path)
                    .build()
        }
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
        modifier = modifier.aspectRatio(aspectRatio).background(colors.surfaceContainerHigh),
    )
}
