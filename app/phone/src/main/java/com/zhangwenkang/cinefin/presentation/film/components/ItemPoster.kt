package com.zhangwenkang.cinefin.presentation.film.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
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
fun ItemPoster(item: FindroidItem, direction: Direction, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var imageUri = item.images.primary

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
    if (imageUri?.scheme == null) {
        imageUri =
            Uri.Builder()
                .appendEncodedPath("${context.filesDir}")
                .appendEncodedPath(imageUri?.path)
                .build()
    }

    AsyncImage(
        model = imageUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier =
            modifier
                .aspectRatio(
                    when (direction) {
                        Direction.HORIZONTAL -> 1.77f
                        Direction.VERTICAL -> 0.66f
                        Direction.SQUARE -> 1f
                    }
                )
                .background(colors.surfaceContainerHigh),
    )
}
