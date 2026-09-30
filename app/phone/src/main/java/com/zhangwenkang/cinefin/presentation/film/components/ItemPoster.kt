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
            if (item is FindroidMovie) imageUri = item.images.backdrop
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
