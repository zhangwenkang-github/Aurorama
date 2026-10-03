package com.zhangwenkang.cinefin.presentation.film.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.film.presentation.detail.DetailDownloadState
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.isDownloaded
import com.zhangwenkang.cinefin.utils.DownloadTaskStatus

/**
 * 卡片 / 详情海报上的下载状态（W60b 用户口径）：
 *
 * - [IN_PROGRESS]：下载中 / 排队 = 进度环（总大小未知时转圈的不确定态）；
 * - [DOWNLOADED]：已完成 = 完成角标（沿用 `ic_download` 的既有语义）；
 * - [PAUSED]：暂停 = 双竖线；
 * - [FAILED]：失败 = 红色叹号。
 */
enum class DownloadBadgeState {
    NONE,
    IN_PROGRESS,
    PAUSED,
    FAILED,
    DOWNLOADED,
}

/** 单个条目的下载角标信息（进度 0..1；[indeterminate] = 总大小未知，画转圈）。 */
data class DownloadBadgeInfo(
    val state: DownloadBadgeState = DownloadBadgeState.NONE,
    val progress: Float = 0f,
    val indeterminate: Boolean = false,
)

/**
 * 纯函数：引擎快照 → 角标状态。优先级 = 已下载 > 下载中 / 排队 > 暂停 > 失败 > 无。
 *
 * 「已下载」优先：任务失败后用户可能已经手动下载好（或又是旧任务残留），完整文件在盘上就展示完成角标。
 */
fun downloadBadgeInfo(
    downloaded: Boolean,
    taskStatus: DownloadTaskStatus?,
    progress: Float = 0f,
    totalKnown: Boolean = true,
): DownloadBadgeInfo =
    when {
        downloaded -> DownloadBadgeInfo(DownloadBadgeState.DOWNLOADED)
        taskStatus == DownloadTaskStatus.RUNNING || taskStatus == DownloadTaskStatus.PENDING ->
            DownloadBadgeInfo(
                state = DownloadBadgeState.IN_PROGRESS,
                progress = progress.coerceIn(0f, 1f),
                indeterminate = !totalKnown,
            )
        taskStatus == DownloadTaskStatus.PAUSED -> DownloadBadgeInfo(DownloadBadgeState.PAUSED)
        taskStatus == DownloadTaskStatus.FAILED -> DownloadBadgeInfo(DownloadBadgeState.FAILED)
        else -> DownloadBadgeInfo()
    }

/** 角标落点（§8.9 / W60b 错位规则）。 */
enum class BadgeCorner {
    TOP_END,
    BOTTOM_END,
}

/** 纯函数：下载徽标落点——右上角被收藏书签 / 未看数 / 已看打勾占用时错位到右下角，否则占右上角。 */
fun downloadBadgeCorner(hasTopEndBadge: Boolean): BadgeCorner =
    if (hasTopEndBadge) BadgeCorner.BOTTOM_END else BadgeCorner.TOP_END

/**
 * 纯函数：详情页下载三态 → 海报角标（W51 语义）。
 *
 * 容器（剧集 / 季）没有单条任务进度，队列中画不确定态进度环；电影 / 单集的实时进度由 [DownloadBadgeInfo] 快照覆盖。
 */
fun downloadBadgeInfo(detailState: DetailDownloadState): DownloadBadgeInfo =
    when (detailState) {
        DetailDownloadState.NOT_DOWNLOADED -> DownloadBadgeInfo()
        DetailDownloadState.IN_QUEUE ->
            DownloadBadgeInfo(DownloadBadgeState.IN_PROGRESS, indeterminate = true)
        DetailDownloadState.DOWNLOADED -> DownloadBadgeInfo(DownloadBadgeState.DOWNLOADED)
    }

internal fun BadgeCorner.toAlignment(): Alignment =
    when (this) {
        BadgeCorner.TOP_END -> Alignment.TopEnd
        BadgeCorner.BOTTOM_END -> Alignment.BottomEnd
    }

/** 引擎快照里同一 itemId 取优先级最高的状态（下载中 > 暂停 > 失败 > 无）。 */
internal fun downloadBadgePriority(state: DownloadBadgeState): Int =
    when (state) {
        DownloadBadgeState.DOWNLOADED -> 4
        DownloadBadgeState.IN_PROGRESS -> 3
        DownloadBadgeState.PAUSED -> 2
        DownloadBadgeState.FAILED -> 1
        DownloadBadgeState.NONE -> 0
    }

/**
 * 卡片角上的徽标组（W60b）：右上 = 收藏书签 + 未看数 / 已看打勾；下载状态按错位规则放右上或右下。
 *
 * 三个卡片组件（[ItemCard] / [PosterItemCard] / [LandscapeItemCard]）共用，错位逻辑只有这一份。
 */
@Composable
fun BoxScope.CardBadgeOverlay(
    item: FindroidItem,
    downloadBadge: DownloadBadgeInfo = DownloadBadgeInfo(),
    cornerPadding: Dp = CinefinSpacing.Space3,
    bottomEndExtraBottom: Dp = 0.dp,
) {
    val statusBadge = item.posterStatusBadge()
    val hasStatusBadge = statusBadge != PosterStatusBadge.None
    Row(
        modifier = Modifier.align(Alignment.TopEnd).padding(cornerPadding),
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item.favorite) FavoriteBadge()
        if (hasStatusBadge) ItemStatusBadge(item)
    }
    // 兼容既有链路：没有实时快照（未接监控的页面 / 首帧）时，本机已有完整文件的条目仍显示完成角标。
    val effectiveBadge =
        if (downloadBadge.state == DownloadBadgeState.NONE && item.isDownloaded()) {
            DownloadBadgeInfo(DownloadBadgeState.DOWNLOADED)
        } else {
            downloadBadge
        }
    val corner = downloadBadgeCorner(hasTopEndBadge = item.favorite || hasStatusBadge)
    // 轻动画（W60b）：角标淡入 / 淡出；内容态之间的切换（进度环 → 完成）直接切形状。
    AnimatedVisibility(
        visible = effectiveBadge.state != DownloadBadgeState.NONE,
        enter = fadeIn(animationSpec = tween(durationMillis = CinefinMotion.Fast)),
        exit = fadeOut(animationSpec = tween(durationMillis = CinefinMotion.Fast)),
        modifier =
            Modifier.align(corner.toAlignment())
                .padding(
                    top = cornerPadding,
                    end = cornerPadding,
                    bottom = cornerPadding + bottomEndExtraBottom,
                ),
    ) {
        DownloadStatusBadge(badge = effectiveBadge)
    }
}

/** 下载状态徽标本体：进度环 / 完成角标 / 双竖线 / 红色叹号；淡入 + 环旋转（§6.3）。 */
@Composable
fun DownloadStatusBadge(badge: DownloadBadgeInfo, modifier: Modifier = Modifier) {
    when (badge.state) {
        DownloadBadgeState.NONE -> Unit
        DownloadBadgeState.DOWNLOADED -> DownloadedBadge(modifier)
        DownloadBadgeState.IN_PROGRESS ->
            DownloadProgressRing(
                progress = badge.progress,
                indeterminate = badge.indeterminate,
                modifier = modifier,
            )
        DownloadBadgeState.PAUSED ->
            DownloadGlyphBadge(
                iconRes = CoreR.drawable.ic_pause,
                contentDescription = stringResource(CoreR.string.download_paused),
                tint = Color.White,
                modifier = modifier,
            )
        DownloadBadgeState.FAILED ->
            DownloadGlyphBadge(
                iconRes = CoreR.drawable.ic_alert_circle,
                contentDescription = stringResource(CoreR.string.download_failed),
                tint = LocalCinefinColors.current.error,
                modifier = modifier,
            )
    }
}

/** 进度环：轨道白 16% + 当前域媒体色弧线；不确定态转圈（1100ms linear，§8.8）。 */
@Composable
private fun DownloadProgressRing(
    progress: Float,
    indeterminate: Boolean,
    modifier: Modifier = Modifier,
) {
    val media = LocalMediaColors.current
    val animatedProgress by
        animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            animationSpec = tween(durationMillis = 200, easing = LinearEasing),
            label = "downloadBadgeProgress",
        )
    val rotation by
        rememberInfiniteTransition(label = "downloadBadgeSpin")
            .animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = 1100, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "downloadBadgeRotation",
            )
    val trackColor = Color.White.copy(alpha = 0.16f)

    BaseBadge(modifier = modifier) {
        Canvas(modifier = Modifier.size(18.dp).align(Alignment.Center)) {
            val stroke = 2.25.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            if (indeterminate) {
                drawArc(
                    color = media.base,
                    startAngle = rotation - 90f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            } else {
                drawArc(
                    color = media.base,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** 中性徽标 + 单色图标（暂停 / 失败共用）。 */
@Composable
private fun DownloadGlyphBadge(
    iconRes: Int,
    contentDescription: String,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    BaseBadge(modifier = modifier) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp).align(Alignment.Center),
        )
    }
}
