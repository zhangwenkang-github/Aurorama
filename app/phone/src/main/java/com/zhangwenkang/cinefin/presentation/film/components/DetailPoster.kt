package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem

/** 详情页海报（Lumen 双层嵌套）：宽屏摆在标题左侧，与头图同心圆角。 */
@Composable
fun DetailPoster(
    item: FindroidItem,
    modifier: Modifier = Modifier,
    width: Dp = 216.dp,
) {
    val colors = LocalCinefinColors.current
    LumenCardFrame(
        modifier = modifier.width(width).aspectRatio(2f / 3f),
        shape = CinefinShapes.Md,
        container = colors.surfaceContainerHigh,
    ) {
        AsyncImage(
            model = if (item is FindroidEpisode) item.images.showPrimary else item.images.primary,
            placeholder = ColorPainter(colors.surfaceContainerHigh),
            error = ColorPainter(colors.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
    }
}
