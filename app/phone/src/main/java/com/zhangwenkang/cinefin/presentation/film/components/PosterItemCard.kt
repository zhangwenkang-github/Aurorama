package com.zhangwenkang.cinefin.presentation.film.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyEpisode
import com.zhangwenkang.cinefin.core.presentation.dummy.dummyMovie
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.utils.BookCoverRules

/**
 * 海报墙竖版卡（§8.4 PosterCard · Lumen）：双层嵌套外壳 + 2:3 海报 + 标题（两行）+ 3dp 进度。
 *
 * 信息只留"片名 + 进行中"两件事：徽标压到最少（下载 / 未看集数），标题最多两行—— 海报墙靠"图说一切"，多一行元信息就会让整面墙变挤。
 */
@Composable
fun PosterItemCard(
    item: FindroidItem,
    onClick: (FindroidItem) -> Unit,
    modifier: Modifier = Modifier,
    index: Int = 0,
    /** W60b：下载状态角标（下载中 / 暂停 / 失败 / 已下载）；默认无角标，既有调用零改动。 */
    downloadBadge: DownloadBadgeInfo = DownloadBadgeInfo(),
    /** W64：本地生成封面（服务器图缺失时覆盖）；null = 走服务器图。 */
    imageOverride: String? = null,
    /** W64：无图时的类型占位图标（书籍 = `ic_book`）；null = 保持原有空色底。 */
    @DrawableRes placeholderIconRes: Int? = null,
    /**
     * W69b：**服务器图加载失败**（404 / 取图失败）时回调——书籍卡据此请求「本地生成」回落 （服务器给了 URL 但实际取不到时也要有封面）。null =
     * 不需要回落（视频卡等）。
     */
    onServerImageFailed: (() -> Unit)? = null,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val emphasized = hovered || pressed

    val resumeFraction = item.resumeFraction()

    Column(
        modifier =
            modifier.lumenEntrance(index).cinefinClickable(interactionSource = interactionSource) {
                onClick(item)
            }
    ) {
        LumenCardFrame(
            modifier = Modifier.fillMaxWidth().aspectRatio(2f / 3f),
            emphasized = emphasized,
            container = colors.surfaceContainerHigh,
        ) {
            // W64（用户第 12 条）：服务器图优先 → 本地封面 → 风格化类型占位；加载失败逐级回落。
            val serverImage =
                if (item is FindroidEpisode) item.images.showPrimary else item.images.primary
            val localCover = imageOverride?.takeIf { it.isNotBlank() }
            var serverImageFailed by remember(item.id) { mutableStateOf(false) }
            var localCoverFailed by remember(item.id) { mutableStateOf(false) }
            val coverSource =
                BookCoverRules.displaySource(
                    hasServerImage = serverImage != null,
                    hasLocalCover = localCover != null,
                    serverFailed = serverImageFailed,
                    localFailed = localCoverFailed,
                )
            val imageModel =
                when (coverSource) {
                    BookCoverRules.CoverSource.SERVER_IMAGE -> serverImage
                    BookCoverRules.CoverSource.GENERATED_CACHE -> localCover
                    else -> null
                }
            if (imageModel == null && placeholderIconRes != null) {
                BookCoverPlaceholder(
                    iconRes = placeholderIconRes,
                    modifier = Modifier.fillMaxSize(),
                    iconSize = 36.dp,
                )
            } else {
                // W69：封面模型变化（刷新 / 服务器图失败回落本地封面）时保留上一张图 + 交叉淡入。
                RetainedAsyncImage(
                    model = imageModel,
                    retainKey = item.id,
                    placeholderPainter = ColorPainter(colors.surfaceContainerHigh),
                    errorPainter = ColorPainter(colors.surfaceContainerHigh),
                    onError = {
                        // 服务器图加载失败（离线等）→ 回落本地封面；本地封面失败 → 占位。
                        if (coverSource == BookCoverRules.CoverSource.SERVER_IMAGE) {
                            serverImageFailed = true
                            onServerImageFailed?.invoke()
                        } else {
                            localCoverFailed = true
                        }
                    },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            CardBadgeOverlay(
                item = item,
                downloadBadge = downloadBadge,
                cornerPadding = CinefinSpacing.Space2,
            )

            if (resumeFraction > 0f) {
                Box(
                    modifier =
                        Modifier.align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(colors.progressTrackOnImage)
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth(resumeFraction)
                                .height(3.dp)
                                .background(media.base)
                    )
                }
            }
        }

        Text(
            text = if (item is FindroidEpisode) item.seriesName else item.name,
            style = CinefinType.TitleSmall,
            color = colors.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier.padding(
                    start = 2.dp,
                    end = 2.dp,
                    top = CinefinSpacing.Space2,
                ),
        )
    }
}

/** 观看进度（0..1），没有时长信息时返回 0，卡片便不画进度线。 */
internal fun FindroidItem.resumeFraction(): Float =
    if (runtimeTicks > 0) {
        (playbackPositionTicks.toFloat() / runtimeTicks.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

@Preview(showBackground = true)
@Composable
private fun PosterItemCardPreview() {
    CinefinTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PosterItemCard(item = dummyMovie, onClick = {}, modifier = Modifier.width(160.dp))
            PosterItemCard(item = dummyEpisode, onClick = {}, modifier = Modifier.width(160.dp))
        }
    }
}
