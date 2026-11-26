package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.presentation.utils.cinefinSafeTop
import kotlinx.coroutines.delay

/** 控制层开启动画时长（定稿一 §3 / 定稿八）。 */
internal const val READER_CONTROLS_ENTER_MS = 200

/** 控制层关闭动画时长（定稿一 §3 / 定稿八）。 */
internal const val READER_CONTROLS_EXIT_MS = 160

/** 「减少动态效果」降级后的纯淡入淡出时长（定稿一 §3）。 */
internal const val READER_CONTROLS_REDUCED_MS = 120

/** 开启时上下两片的位移距离。 */
private const val READER_CONTROLS_SLIDE_DP = 24

/** 顶片内容高度（不含状态栏 inset）。 */
private val READER_CONTROLS_TOPBAR_HEIGHT = 56.dp

/** 图标按钮触控区（定稿一 §2：48dp）。 */
private val READER_CONTROLS_ICON_BUTTON = 48.dp

/** 顶片形状：只下圆角（定稿一 §2）。 */
private val ReaderTopSheetShape =
    RoundedCornerShape(
        topStart = CornerSize(0.dp),
        topEnd = CornerSize(0.dp),
        bottomEnd = CinefinShapes.Card.bottomEnd,
        bottomStart = CinefinShapes.Card.bottomStart,
    )

/** 底片形状：只上圆角（定稿一 §2）。 */
private val ReaderBottomSheetShape =
    RoundedCornerShape(
        topStart = CinefinShapes.Card.topStart,
        topEnd = CinefinShapes.Card.topEnd,
        bottomEnd = CornerSize(0.dp),
        bottomStart = CornerSize(0.dp),
    )

/** 底片四入口：图标 + 12sp 标签。 */
private enum class ReaderControlEntry(
    val label: String,
    val iconRes: Int,
) {
    Contents("目录", CoreR.drawable.ic_view_list),
    Bookmarks("书签", CoreR.drawable.ic_bookmark),
    Progress("进度", CoreR.drawable.ic_gauge),
    Settings("设置", CoreR.drawable.ic_settings),
}

/**
 * 沉浸控制层（W6 T1 定稿一）：唤出时上下两片覆盖在正文之上，正文不位移、不缩放。
 *
 * 顶片 = 返回 / 书名 / 更多；底片 = 进度条占位（T2） + 目录 / 书签 / 进度 / 设置四入口。深色 / OLED 家族用玻璃底，纸色 / 护眼用 96%
 * 不透明的页色，圆角只朝正文内侧。开合动画 200/160ms（无回弹），系统「减少动态效果」降级为 120ms 纯淡入淡出。
 */
@Composable
internal fun ReaderControlLayer(
    visible: Boolean,
    title: String,
    darkChrome: Boolean,
    chromeColor: Color,
    contentColor: Color,
    /** 全书进度（0–1）：底片进度条的当前值。 */
    progression: Float,
    /** 章节分界刻度（0–1；PDF / CBZ 为空）。 */
    chapterTicks: List<Float>,
    /** 目录条目（拖动浮层按位置取章节名）。 */
    chapters: List<ReaderTocEntry>,
    /** 书签位置（0–1），点按书签点 = 跳转。 */
    bookmarkProgressions: List<Float>,
    /** 分页页数（EPUB = positions 页数，PDF / CBZ = 总页数）；null = 不显示页码行。 */
    progressPageCount: Int?,
    /** 浮层是否显示百分比（EPUB = true；PDF / CBZ 只显示页码）。 */
    progressPercentVisible: Boolean,
    /** 「进度」入口聚焦令牌：变化时把浮层亮一会儿。 */
    progressFocusToken: Int,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onOpenContents: () -> Unit,
    onOpenBookmarks: () -> Unit,
    onOpenProgress: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissByDrag: () -> Unit,
    /** 进度条松手（只在松手时回调一次）：目标位置 0–1。 */
    onScrub: (Float) -> Unit,
    /** 点按进度条上的书签点：书签下标。 */
    onBookmarkTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = CinefinMotion.reduceMotion()
    val enterMillis = if (reduceMotion) READER_CONTROLS_REDUCED_MS else READER_CONTROLS_ENTER_MS
    val exitMillis = if (reduceMotion) READER_CONTROLS_REDUCED_MS else READER_CONTROLS_EXIT_MS
    val colors = LocalCinefinColors.current
    val accent = LocalMediaColors.current.base
    // 深色 / OLED 家族 = 玻璃（rgba(10,16,28,.58) + glassBorder）；纸色 / 护眼 = 页色 96%。
    val containerColor = if (darkChrome) colors.glass else chromeColor.copy(alpha = 0.96f)
    val borderColor = if (darkChrome) colors.glassBorder else contentColor.copy(alpha = 0.08f)
    val slidePx = with(LocalDensity.current) { READER_CONTROLS_SLIDE_DP.dp.roundToPx() }
    // 拖动中的预览位置上抛到本层：浮层要画在底片圆角裁剪之外。
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var progressFocused by remember { mutableStateOf(false) }
    LaunchedEffect(progressFocusToken) {
        if (progressFocusToken <= 0) return@LaunchedEffect
        progressFocused = true
        delay(READER_PROGRESS_OVERLAY_FOCUS_MS)
        progressFocused = false
    }
    val overlayProgression = scrubFraction ?: progression.takeIf { progressFocused }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter =
                if (reduceMotion) fadeIn(tween(enterMillis))
                else
                    fadeIn(tween(enterMillis)) + slideInVertically(tween(enterMillis)) { -slidePx },
            exit =
                if (reduceMotion) fadeOut(tween(exitMillis))
                else
                    fadeOut(tween(exitMillis)) + slideOutVertically(tween(exitMillis)) { -slidePx },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Column(
                modifier =
                    Modifier.fillMaxWidth()
                        .clip(ReaderTopSheetShape)
                        .background(containerColor)
                        .border(1.dp, borderColor, ReaderTopSheetShape)
                        // 2.0.2 D1：进页即沉浸（systemBars 隐藏 → `statusBarsPadding()` 取 0），
                        // 改用 safeDrawing 顶部口径，挖孔比状态栏高时控制层不被压住。
                        .cinefinSafeTop()
                        .dragDownToDismiss(onDismiss = onDismissByDrag)
            ) {
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .height(READER_CONTROLS_TOPBAR_HEIGHT)
                            .padding(horizontal = CinefinSpacing.Space2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ControlIconButton(
                        iconRes = CoreR.drawable.ic_arrow_left,
                        contentDescription = "返回",
                        contentColor = contentColor,
                        onClick = onBack,
                    )
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f).padding(horizontal = CinefinSpacing.Space2),
                        style = CinefinType.TitleMedium,
                        color = contentColor,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ControlIconButton(
                        iconRes = CoreR.drawable.ic_menu,
                        contentDescription = "更多",
                        contentColor = contentColor,
                        onClick = onMore,
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = visible,
            enter =
                if (reduceMotion) fadeIn(tween(enterMillis))
                else fadeIn(tween(enterMillis)) + slideInVertically(tween(enterMillis)) { slidePx },
            exit =
                if (reduceMotion) fadeOut(tween(exitMillis))
                else fadeOut(tween(exitMillis)) + slideOutVertically(tween(exitMillis)) { slidePx },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 拖动 / 聚焦浮层：进度条上方 8dp，画在底片圆角裁剪之外（否则会被 clip 掉）。
                AnimatedVisibility(
                    visible = overlayProgression != null,
                    enter = fadeIn(tween(READER_PROGRESS_OVERLAY_FADE_MS)),
                    exit = fadeOut(tween(READER_PROGRESS_OVERLAY_FADE_MS)),
                ) {
                    ReaderProgressOverlay(
                        progression = overlayProgression ?: progression,
                        chapters = chapters,
                        pageCount = progressPageCount,
                        percentVisible = progressPercentVisible,
                        containerColor = containerColor,
                        contentColor = contentColor,
                        borderColor = borderColor,
                        modifier =
                            Modifier.padding(
                                start = CinefinSpacing.Space6,
                                end = CinefinSpacing.Space6,
                                bottom = READER_PROGRESS_OVERLAY_GAP,
                            ),
                    )
                }

                Column(
                    modifier =
                        Modifier.fillMaxWidth()
                            .clip(ReaderBottomSheetShape)
                            .background(containerColor)
                            .border(1.dp, borderColor, ReaderBottomSheetShape)
                            .navigationBarsPadding()
                ) {
                    // 全书进度条（定稿二 §1）：44dp 触控区，松手才跳转一次。
                    ReaderProgressBar(
                        progression = progression,
                        chapterTicks = chapterTicks,
                        bookmarkProgressions = bookmarkProgressions,
                        accent = accent,
                        contentColor = contentColor,
                        scrubFraction = scrubFraction,
                        onScrubPreview = { scrubFraction = it },
                        onScrubCommit = onScrub,
                        onBookmarkTap = onBookmarkTap,
                        modifier = Modifier.padding(horizontal = CinefinSpacing.Space3),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = CinefinSpacing.Space2)
                    ) {
                        ReaderControlEntry.entries.forEach { entry ->
                            ControlEntryButton(
                                entry = entry,
                                contentColor = contentColor,
                                onClick =
                                    when (entry) {
                                        ReaderControlEntry.Contents -> onOpenContents
                                        ReaderControlEntry.Bookmarks -> onOpenBookmarks
                                        ReaderControlEntry.Progress -> onOpenProgress
                                        ReaderControlEntry.Settings -> onOpenSettings
                                    },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 顶片 48dp 图标按钮。 */
@Composable
private fun ControlIconButton(
    iconRes: Int,
    contentDescription: String,
    contentColor: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier.size(READER_CONTROLS_ICON_BUTTON)
                .clip(CircleShape)
                .cinefinClickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            tint = contentColor,
        )
    }
}

/** 底片入口：图标 + 12sp 标签，触控区 ≥48dp，四等分。 */
@Composable
private fun ControlEntryButton(
    entry: ReaderControlEntry,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .height(READER_CONTROLS_ENTRY_HEIGHT)
                .cinefinClickable(onClickLabel = entry.label, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(id = entry.iconRes),
            contentDescription = null,
            tint = contentColor,
        )
        Text(
            text = entry.label,
            style = CinefinType.LabelSmall.copy(fontSize = 12.sp),
            color = contentColor,
            maxLines = 1,
        )
    }
}

/**
 * 顶片下滑关闭（定稿一 §3）：位移 > 48dp 或速度 > 800dp/s。
 *
 * 在 Initial 阶段拿事件：拖动成立就消费，按钮的单击不受影响（未超过 touchSlop 不消费）。
 */
private fun Modifier.dragDownToDismiss(onDismiss: () -> Unit): Modifier =
    pointerInput(onDismiss) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val startY = down.position.y
            val startTime = down.uptimeMillis
            var lastY = startY
            var lastTime = startTime
            var dragging = false
            val slop = viewConfiguration.touchSlop
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (change.pressed) {
                    if (!dragging && change.position.y - startY > slop) dragging = true
                    if (dragging) {
                        lastY = change.position.y
                        lastTime = change.uptimeMillis
                        change.consume()
                    }
                } else {
                    if (dragging) {
                        val distance = lastY - startY
                        val elapsed = (lastTime - startTime).coerceAtLeast(1L)
                        val velocity = distance / elapsed * 1_000f
                        if (readerControlsDismissedByDrag(distance, velocity, density)) {
                            onDismiss()
                        }
                    }
                    break
                }
            }
        }
    }

/** 底片入口行高：图标 + 标签 + 上下留白，保证触控区 ≥48dp。 */
private val READER_CONTROLS_ENTRY_HEIGHT = 56.dp
