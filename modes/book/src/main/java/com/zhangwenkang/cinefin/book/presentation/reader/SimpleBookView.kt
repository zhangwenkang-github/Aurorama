package com.zhangwenkang.cinefin.book.presentation.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import timber.log.Timber

/** 双指缩放调试日志的最小步进：真机验收用 logcat 文本判断缩放生效，又不会每帧刷屏。 */
private const val ZOOM_LOG_STEP = 0.1f

/**
 * PDF / CBZ 阅读视图（EB-3 / EB-4）：滚动 / 横向分页 / 平板双栏三档模式。
 *
 * 内存策略（ARCHITECTURE §3.4）：位图按需渲染，[PageImageCache] 窗口固定 3 张，位图长边不超过
 * [PAGE_BITMAP_MAX_SIDE_PX]，与文档页数无关；先 `min(屏幕宽, 2048)` 再交给数据源降采样。
 */
@Composable
internal fun SimpleBookView(
    document: ReaderDocument.Simple,
    settings: ReaderSettings,
    systemDark: Boolean,
    contentColor: Color,
    chromeColor: Color,
    onPageChanged: (index: Int, pageCount: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pageCount = document.pageSource.pageCount
    val screenWidthPx = LocalContext.current.resources.displayMetrics.widthPixels
    val cache =
        remember(document) {
            PageImageCache(
                source = document.pageSource,
                maxSidePx = min(screenWidthPx, PAGE_BITMAP_MAX_SIDE_PX).coerceAtLeast(1),
            )
        }
    var currentPage by
        remember(document) {
            mutableStateOf(document.initialPage.coerceIn(0, max(0, pageCount - 1)))
        }

    fun report(page: Int) {
        currentPage = page
        onPageChanged(page, pageCount)
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (settings.mode) {
            ReaderMode.Scroll ->
                ScrollPages(
                    cache = cache,
                    pageCount = pageCount,
                    initialPage = currentPage,
                    contentColor = contentColor,
                    onPageChanged = ::report,
                )

            ReaderMode.Paged ->
                PagedPages(
                    cache = cache,
                    pageCount = pageCount,
                    initialPage = currentPage,
                    pagesPerSpread = 1,
                    rtl = settings.rtl,
                    contentColor = contentColor,
                    onPageChanged = ::report,
                )

            ReaderMode.TwoColumn ->
                PagedPages(
                    cache = cache,
                    pageCount = pageCount,
                    initialPage = currentPage,
                    pagesPerSpread = 2,
                    rtl = settings.rtl,
                    contentColor = contentColor,
                    onPageChanged = ::report,
                )
        }

        PageIndicator(
            text = pageIndicatorText(settings.mode, currentPage, pageCount, settings.rtl),
            chromeColor = chromeColor,
            contentColor = contentColor,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 滚动模式：纵向连续，当前页 = 首个可见页。
 *
 * 每页仍挂双指缩放手势（EB-3 扩展到滚动模式）：竖排顺序与 RTL 无关，右起开关在滚动模式不改变 页序；单指纵向拖动不被消费，继续交给 `LazyColumn` 滚动。
 */
@Composable
private fun ScrollPages(
    cache: PageImageCache,
    pageCount: Int,
    initialPage: Int,
    contentColor: Color,
    onPageChanged: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialPage)
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collect(onPageChanged)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = CinefinSpacing.Space2),
        verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
    ) {
        items(count = pageCount, key = { it }) { index ->
            var aspect by remember(index) { mutableStateOf(0.707f) }
            LaunchedEffect(index) { cache.aspect(index)?.let { aspect = it } }
            Box(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CinefinSpacing.Space2),
                contentAlignment = Alignment.Center,
            ) {
                ZoomablePage(
                    cache = cache,
                    index = index,
                    contentColor = contentColor,
                    modifier = Modifier.fillMaxWidth().aspectRatio(aspect),
                )
            }
        }
    }
}

/**
 * 横向分页 / 双栏：`pagesPerSpread` = 1（分页）或 2（双栏，Pad 5 横屏左右各一页）。
 *
 * RTL（漫画右起）时整条页链镜像（`reverseLayout`，向右滑动前进），spread 内左右页也镜像 （[spreadPageSlots]：右 = 2k+1、左 =
 * 2k+2）；逻辑页号与进度不变。切开关时用 `key(rtl)` 重建 Pager，让 `initialPage` 按当前逻辑页重新落位，避免镜像瞬间跳到别的页。
 */
@Composable
private fun PagedPages(
    cache: PageImageCache,
    pageCount: Int,
    initialPage: Int,
    pagesPerSpread: Int,
    rtl: Boolean,
    contentColor: Color,
    onPageChanged: (Int) -> Unit,
) {
    key(rtl) {
        val spreadTotal = spreadCount(pageCount, pagesPerSpread)
        val pagerState =
            rememberPagerState(initialPage = initialPage / pagesPerSpread) { spreadTotal }
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collect { spread -> onPageChanged(spread * pagesPerSpread) }
        }
        // 停稳后再预取邻页，避免抢可见页的解码带宽（窗口仍是 3 张）。
        LaunchedEffect(pagerState) {
            snapshotFlow { pagerState.settledPage }
                .distinctUntilChanged()
                .collect { spread ->
                    delay(150)
                    val base = spread * pagesPerSpread
                    listOf(base - 1, base + pagesPerSpread).forEach { cache.prefetch(it) }
                }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { it },
            reverseLayout = rtl,
        ) { spread ->
            val slots = spreadPageSlots(spread, pageCount, pagesPerSpread, rtl)
            Row(modifier = Modifier.fillMaxSize()) {
                slots.forEach { index ->
                    if (index != null) {
                        ZoomablePage(
                            cache = cache,
                            index = index,
                            contentColor = contentColor,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    } else {
                        Box(modifier = Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

/**
 * 单页 + 双指缩放（EB-3「缩放基础」）；缩放到 1× 以下自动回到居中。
 *
 * 手势只在**两根手指**同时按下时消费事件：单指拖动必须留给 `HorizontalPager` 翻页，否则 分页 / 双栏模式会翻不动页（真机实测踩坑，见 READER_PLAN §8 踩坑
 * 18）。放大后可用双指拖动平移。
 */
@Composable
private fun ZoomablePage(
    cache: PageImageCache,
    index: Int,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    var pageZoom by remember(index) { mutableStateOf(PageZoom()) }
    var boxSize by remember(index) { mutableStateOf(IntSize.Zero) }
    var lastLoggedScale by remember(index) { mutableStateOf(1f) }

    Box(
        modifier =
            modifier
                .clipToBounds()
                .onSizeChanged { boxSize = it }
                .pointerInput(index) {
                    detectMultiTouchZoom { pan, zoomFactor ->
                        val next =
                            pageZoom.transform(zoomFactor, pan, boxSize.width, boxSize.height)
                        pageZoom = next
                        // 数帧一次的调试轨迹：真机验收用 logcat 文本判断缩放手势确实生效（不贴截图）。
                        if (abs(next.scale - lastLoggedScale) >= ZOOM_LOG_STEP) {
                            lastLoggedScale = next.scale
                            Timber.d(
                                "reader zoom index=%d scale=%.2f offset=(%.0f,%.0f)",
                                index,
                                next.scale,
                                next.offset.x,
                                next.offset.y,
                            )
                        }
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    scaleX = pageZoom.scale
                    scaleY = pageZoom.scale
                    translationX = pageZoom.offset.x
                    translationY = pageZoom.offset.y
                }
        ) {
            PageContent(
                cache = cache,
                index = index,
                contentColor = contentColor,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** 单页内容：位图 / 加载中 / 渲染失败（占位 + 重试）。 */
@Composable
private fun PageContent(
    cache: PageImageCache,
    index: Int,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(index) { mutableStateOf(false) }
    var attempt by remember(index) { mutableStateOf(0) }
    LaunchedEffect(index, attempt) {
        failed = false
        bitmap = cache.image(index)
        failed = bitmap == null
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val image = bitmap
        when {
            image != null ->
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

            failed ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = "本页渲染失败", style = CinefinType.LabelLarge, color = contentColor)
                    CinefinButton(
                        text = "重试",
                        onClick = { attempt++ },
                        size = CinefinButtonSize.Small,
                    )
                }

            else ->
                CircularProgressIndicator(
                    color = contentColor,
                    modifier = Modifier.size(28.dp),
                )
        }
    }
}

/** 页指示（也是模式差异的可见证据：滚动 / 分页 / 双栏三档文字不同）。 */
@Composable
private fun PageIndicator(
    text: String,
    chromeColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = CinefinType.LabelLarge,
        color = contentColor,
        modifier =
            modifier
                .padding(CinefinSpacing.Space3)
                .background(chromeColor.copy(alpha = 0.88f), CinefinShapes.Xs)
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space1),
    )
}

/**
 * 只在 ≥2 根手指时消费事件的双指缩放手势。
 *
 * `detectTransformGestures` 会把单指拖动也当成 pan 消费掉，导致 Pager 收不到翻页手势。
 */
private suspend fun PointerInputScope.detectMultiTouchZoom(
    onGesture: (pan: Offset, zoom: Float) -> Unit
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        do {
            val event = awaitPointerEvent()
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (zoom != 1f || pan != Offset.Zero) {
                    onGesture(pan, zoom)
                    event.changes.forEach { change ->
                        if (change.positionChanged()) change.consume()
                    }
                }
            }
        } while (event.changes.any { it.pressed })
    }
}
