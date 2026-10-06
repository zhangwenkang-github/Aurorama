package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidSeason

/** 详情页海报（Lumen 双层嵌套）：宽屏摆在标题左侧，与头图同心圆角。 */
@Composable
fun DetailPoster(
    item: FindroidItem,
    modifier: Modifier = Modifier,
    width: Dp = 216.dp,
    /** W60b：下载状态角标（详情海报）；默认无角标，既有调用零改动。 */
    downloadBadge: DownloadBadgeInfo = DownloadBadgeInfo(),
) {
    val colors = LocalCinefinColors.current
    LumenCardFrame(
        modifier = modifier.width(width).aspectRatio(2f / 3f),
        shape = CinefinShapes.Md,
        container = colors.surfaceContainerHigh,
    ) {
        // W69：详情海报刷新（含返回重进）时保留上一张图 + 交叉淡入。
        RetainedAsyncImage(
            model =
                when (item) {
                    is FindroidEpisode -> item.images.showPrimary
                    // W73（#10）：未知季自身没有海报，回落剧集海报，不再留深灰空框。
                    is FindroidSeason ->
                        seasonPosterImage(item.images.primary, item.images.showPrimary)
                    else -> item.images.primary
                },
            retainKey = item.id,
            placeholderPainter = ColorPainter(colors.surfaceContainerHigh),
            errorPainter = ColorPainter(colors.surfaceContainerHigh),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        if (downloadBadge.state != DownloadBadgeState.NONE) {
            DownloadStatusBadge(
                badge = downloadBadge,
                modifier = Modifier.align(Alignment.TopEnd).padding(CinefinSpacing.Space3),
            )
        }
    }
}
