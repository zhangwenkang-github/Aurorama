package com.zhangwenkang.cinefin.presentation.film.components

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.presentation.utils.parallaxLayoutModifier

@Composable
fun ItemHeader(
    item: FindroidItem,
    scrollState: ScrollState,
    showLogo: Boolean = false,
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var backdropUri =
        when (item) {
            is FindroidEpisode -> item.images.primary
            else -> item.images.backdrop
        }

    // Ugly workaround to append the files directory when loading local images
    if (backdropUri?.scheme == null) {
        backdropUri =
            Uri.Builder()
                .appendEncodedPath("${context.filesDir}")
                .appendEncodedPath(backdropUri?.path)
                .build()
    }

    ItemHeaderBase(
        item = item,
        showLogo = showLogo,
        backdropImage = {
            AsyncImage(
                model = backdropUri,
                contentDescription = null,
                modifier =
                    Modifier.fillMaxSize()
                        .parallaxLayoutModifier(scrollState = scrollState, rate = 2),
                placeholder = ColorPainter(colors.surfaceContainer),
                contentScale = ContentScale.Crop,
            )
        },
        content = content,
    )
}

@Composable
fun ItemHeader(
    item: FindroidItem,
    lazyListState: LazyListState,
    showLogo: Boolean = false,
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    val colors = LocalCinefinColors.current
    val context = LocalContext.current
    var backdropUri =
        when (item) {
            is FindroidEpisode -> item.images.primary
            is FindroidSeason -> item.images.showBackdrop
            else -> item.images.backdrop
        }

    // Ugly workaround to append the files directory when loading local images
    if (backdropUri?.scheme == null) {
        backdropUri =
            Uri.Builder()
                .appendEncodedPath("${context.filesDir}")
                .appendEncodedPath(backdropUri?.path)
                .build()
    }

    ItemHeaderBase(
        item = item,
        showLogo = showLogo,
        backdropImage = {
            AsyncImage(
                model = backdropUri,
                contentDescription = null,
                modifier =
                    Modifier.fillMaxSize()
                        .parallaxLayoutModifier(lazyListState = lazyListState, rate = 2),
                placeholder = ColorPainter(colors.surfaceContainer),
                contentScale = ContentScale.Crop,
            )
        },
        content = content,
    )
}

@Composable
private fun ItemHeaderBase(
    item: FindroidItem,
    showLogo: Boolean = false,
    backdropImage: @Composable (() -> Unit),
    content: @Composable (BoxScope.() -> Unit) = {},
) {
    val colors = LocalCinefinColors.current
    val backgroundColor = colors.surface

    val logoUri =
        when (item) {
            is FindroidEpisode -> item.images.showLogo
            else -> item.images.logo
        }

    Box(modifier = Modifier.height(288.dp).clipToBounds()) {
        backdropImage()
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = 0.1f))
            drawRect(
                brush =
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, backgroundColor),
                        startY = 0f,
                    )
            )
        }
        content()
        if (showLogo) {
            AsyncImage(
                model = logoUri,
                contentDescription = null,
                modifier =
                    Modifier.align(Alignment.BottomCenter)
                        .padding(CinefinSpacing.Space5)
                        .height(100.dp)
                        .fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
