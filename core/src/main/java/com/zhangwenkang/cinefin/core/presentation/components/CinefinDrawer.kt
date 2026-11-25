package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinNavigationMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.core.presentation.theme.rememberReduceMotionEnabled
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 抽屉状态：保留 M3 `DrawerState` 的公开语义（`isOpen` / `open` / `close`），但动画规格由 [CinefinNavigationMotion]
 * 唯一提供。
 *
 * W3-T1 定稿要求开 200ms / 关 160ms、减速曲线、无弹簧；M3 `ModalNavigationDrawer` 在每次组合时把内部规格重置为 motion
 * token，无法从外部定制，因此这里用同一份 `AnchoredDraggableState` 自绘容器（手势、遮罩与锚点行为与 M3 一致）。
 */
@Stable
class CinefinDrawerState(initialValue: DrawerValue = DrawerValue.Closed) {
    private var density: Density = Density(1f)

    internal var reduceMotion: Boolean = false

    internal val anchoredState: AnchoredDraggableState<DrawerValue> =
        AnchoredDraggableState(
            initialValue = initialValue,
            anchors = DraggableAnchors {},
            positionalThreshold = { distance -> distance * DrawerPositionalThreshold },
            velocityThreshold = { with(density) { DrawerVelocityThreshold.toPx() } },
            snapAnimationSpec = CinefinNavigationMotion.drawerSettleSpec(reduceMotion = false),
            decayAnimationSpec = exponentialDecay(),
            confirmValueChange = { true },
        )

    val currentValue: DrawerValue
        get() = anchoredState.settledValue

    val isOpen: Boolean
        get() = currentValue == DrawerValue.Open

    val isClosed: Boolean
        get() = currentValue == DrawerValue.Closed

    val targetValue: DrawerValue
        get() = anchoredState.targetValue

    internal val currentOffset: Float
        get() = anchoredState.offset

    internal fun updateDensity(density: Density) {
        this.density = density
    }

    internal fun updateAnchors(closedOffsetPx: Float, openOffsetPx: Float) {
        anchoredState.updateAnchors(
            DraggableAnchors {
                DrawerValue.Closed at closedOffsetPx
                DrawerValue.Open at openOffsetPx
            }
        )
    }

    /** 打开抽屉；Reduce Motion 时即时切换。 */
    suspend fun open() {
        if (!anchoredState.anchors.hasPositionFor(DrawerValue.Open)) return
        anchoredState.animateTo(
            targetValue = DrawerValue.Open,
            animationSpec = CinefinNavigationMotion.drawerOpenSpec(reduceMotion),
        )
    }

    /** 关闭抽屉；Reduce Motion 时即时切换。 */
    suspend fun close() {
        if (!anchoredState.anchors.hasPositionFor(DrawerValue.Closed)) return
        anchoredState.animateTo(
            targetValue = DrawerValue.Closed,
            animationSpec = CinefinNavigationMotion.drawerCloseSpec(reduceMotion),
        )
    }

    /** 拖拽中的原始位移（px）。写偏移不做动画，由 [settleAfterDrag] 收尾。 */
    internal fun dragBy(deltaPx: Float) {
        anchoredState.dispatchRawDelta(deltaPx)
    }

    /** 取消上一次拖拽的收尾动画：新手势起手时让位给用户。 */
    internal fun cancelDragSettle() {
        dragSettleJob?.cancel()
        dragSettleJob = null
    }

    /**
     * 松手收尾：速度 / 位置阈值决定开合（与 `AnchoredDraggableState` 同口径），动画走定稿落位规格 （开 200ms / 关 160ms，尊重 Reduce
     * Motion）。
     *
     * 手势协程受 `AwaitPointerEventScope` 限制不能直接调用挂起函数，因此收尾动画放到组合作用域里跑。
     */
    internal fun settleAfterDrag(animationScope: CoroutineScope, velocity: Float) {
        cancelDragSettle()
        val closedOffset = anchoredState.anchors.positionOf(DrawerValue.Closed)
        val openOffset = anchoredState.anchors.positionOf(DrawerValue.Open)
        val target =
            drawerTargetAfterDrag(
                offset = anchoredState.offset,
                closedOffset = closedOffset,
                openOffset = openOffset,
                velocity = velocity,
                velocityThresholdPx = with(density) { DrawerVelocityThreshold.toPx() },
            )
        val spec = CinefinNavigationMotion.drawerSettleSpec(reduceMotion)
        dragSettleJob = animationScope.launch { anchoredState.animateTo(target, spec) }
    }

    private var dragSettleJob: Job? = null

    companion object {
        val Saver: Saver<CinefinDrawerState, DrawerValue> =
            Saver(save = { it.currentValue }, restore = { CinefinDrawerState(it) })
    }
}

@Composable
fun rememberCinefinDrawerState(initialValue: DrawerValue = DrawerValue.Closed): CinefinDrawerState =
    rememberSaveable(saver = CinefinDrawerState.Saver) { CinefinDrawerState(initialValue) }

private const val DrawerPositionalThreshold = 0.5f
private val DrawerVelocityThreshold = 400.dp

/** 边缘呼出手势热区宽度（W2-T11 / 需求 #12）：抽屉关闭时只有该宽度的左缘（RTL 为右缘）可起手拖出抽屉。 */
internal val DrawerEdgeSwipeWidth = 28.dp

/** 手势导航机型屏幕最外侧留给系统返回手势的保留区；热区从保留区之后开始，否则永远被系统抢走。 */
internal val DrawerSystemEdgeReserve = 24.dp

/**
 * 抽屉拖拽门控（W2-T11 / 需求 #12）：手势是否允许驱动抽屉。
 *
 * 抽屉已打开（或正在打开）时全屏可拖拽关闭，与改造前行为一致；抽屉关闭时只有落点在边缘热区 `[edgeStartPx, edgeStartPx + edgeWidthPx]`
 * 内的手势才放行，屏幕中部 / 右缘的水平、斜向滑动与页面上下滚动都不再误触。
 */
internal fun drawerDragAllowedAt(
    startX: Float,
    containerWidth: Float,
    edgeStartPx: Float,
    edgeWidthPx: Float,
    drawerVisible: Boolean,
    rtl: Boolean,
): Boolean {
    if (drawerVisible) return true
    if (edgeWidthPx <= 0f || containerWidth <= 0f) return false
    val fromStart = if (rtl) containerWidth - startX else startX
    return fromStart >= edgeStartPx && fromStart <= edgeStartPx + edgeWidthPx
}

/** 边缘热区内手势方向的仲裁结果。 */
internal enum class DrawerDragVerdict {
    /** 横向占优：抽屉接管本次手势。 */
    Claim,
    /** 纵向占优：让给页面滚动，抽屉不参与。 */
    Yield,
    /** 位移还不够：继续观察。 */
    Pending,
}

/** 边缘热区里的方向仲裁（W2-T11）：横向位移过 slop 且明显大于纵向才算抽屉； 纵向先过 slop 一律让给页面滚动——页面上下滑动因此不可能呼出抽屉。 */
internal fun drawerDragVerdict(dx: Float, dy: Float, touchSlopPx: Float): DrawerDragVerdict {
    val absDx = kotlin.math.abs(dx)
    val absDy = kotlin.math.abs(dy)
    return when {
        absDx > touchSlopPx && absDx > absDy -> DrawerDragVerdict.Claim
        absDy > touchSlopPx && absDy >= absDx -> DrawerDragVerdict.Yield
        else -> DrawerDragVerdict.Pending
    }
}

/**
 * 拖拽松手后的落点判定（W2-T11）：速度过阈按方向决定，否则看位移是否越过 `positionalThreshold` （`offset` 已由
 * [CinefinDrawerState.dragBy] 夹在开 / 关两个锚点之间）。
 */
internal fun drawerTargetAfterDrag(
    offset: Float,
    closedOffset: Float,
    openOffset: Float,
    velocity: Float,
    velocityThresholdPx: Float,
    positionalThreshold: Float = DrawerPositionalThreshold,
): DrawerValue {
    if (openOffset == closedOffset) return DrawerValue.Closed
    val towardOpen = if (openOffset > closedOffset) 1f else -1f
    val directedVelocity = velocity * towardOpen
    if (directedVelocity >= velocityThresholdPx) return DrawerValue.Open
    if (directedVelocity <= -velocityThresholdPx) return DrawerValue.Closed
    val moved = (offset - closedOffset) * towardOpen
    val distance = (openOffset - closedOffset) * towardOpen
    return if (moved >= distance * positionalThreshold) DrawerValue.Open else DrawerValue.Closed
}

/**
 * 抽屉分组：`title = null` 表示不画分组标题。
 *
 * [dividerAboveTitle]（W53B）：分组标题上方是否加一条细分隔线——用于「媒体库 → 本地媒体库」这类**组内子分组**，
 * 让子分组与上面的服务器库列表分开（复用侧轨同一条发丝线，不新增配色 / 字体 / 位图）。
 */
class CinefinDrawerGroup(
    val title: String?,
    val items: List<CinefinNavItem>,
    val dividerAboveTitle: Boolean = false,
)

/**
 * Cinefin 抽屉（§8.6 + W3-T2 K1 换肤）：默认宽 320dp（W65 起由调用方按窗口宽度传入 [drawerWidth]，手机 ≈ 55% 屏宽、夹取
 * 208–280dp），底 = 面板容器色，header 96dp，条目 56dp / 圆角 16dp， 分组标题 `LabelSmall +
 * OnSurfaceFaint`；无投影（`tonalElevation = 0`），遮罩用 `Scrim`。
 *
 * `selectedIndex` 按分组顺序拍平后计算；选中态与常显侧轨同一套 K1 离散语言（极光淡色 pill + `glassBorder` 描边 + `aurora1` 图文，见
 * [navItemContainerColor]）。
 *
 * 容器外壳（W3-T1）：M3 `ModalNavigationDrawer` 的动画规格不可外部定制，这里用 `AnchoredDraggableState` 自绘—— 与 M3 相同的遮罩 /
 * 锚点模型，位移只走 `Modifier.offset { IntOffset(...) }`，不触发布局重排。
 *
 * 呼出手势（W2-T11 / 需求 #12）：关闭态只有左缘 [DrawerEdgeSwipeWidth] 热区内起手才能拖出抽屉（RTL 为右缘），热区整体 内移到系统返回手势保留区
 * [DrawerSystemEdgeReserve] 之后，屏幕中部的横向 / 斜向滑动与页面上下滚动都不再误触； 抽屉打开后全屏可拖拽关闭。
 */
@Composable
fun CinefinModalDrawer(
    drawerState: CinefinDrawerState,
    header: @Composable () -> Unit,
    groups: List<CinefinDrawerGroup>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    gesturesEnabled: Boolean = true,
    /** 抽屉内容宽（W65 自适应接入点；默认 320dp 保持既有预览 / 调用方行为）。 */
    drawerWidth: Dp = 320.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalCinefinColors.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val reduceMotion = rememberReduceMotionEnabled()
    val scope = rememberCoroutineScope()
    val scrimInteractionSource = remember { MutableInteractionSource() }
    var anchorsInitialized by remember { mutableStateOf(false) }
    // 关闭锚点 = -抽屉宽度；由抽屉内容实测宽写回（旋转 / 分屏 / 窄屏都自洽）。
    var closedAnchor by remember(density) { mutableFloatStateOf(0f) }
    val openAnchor = 0f
    SideEffect {
        drawerState.updateDensity(density)
        drawerState.reduceMotion = reduceMotion
    }

    val reverseDirection = layoutDirection == LayoutDirection.Rtl
    val edgeSwipeWidthPx = with(density) { DrawerEdgeSwipeWidth.toPx() }
    // 手势导航机型最外侧 ~24dp 归系统返回手势；热区整体内移到保留区之后，否则边缘滑动永远被系统吃掉。
    val edgeSwipeStartPx =
        maxOf(
            WindowInsets.systemGestures.getLeft(density, layoutDirection),
            with(density) { DrawerSystemEdgeReserve.toPx() }.roundToInt(),
        )
    val edgeSwipeStartDp = with(density) { edgeSwipeStartPx.toDp() }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                // W2-T11（需求 #12）：手势不再挂在整屏。关闭态只有左缘热区（系统返回手势保留区之后）内起手才进入
                // 横向拖拽判定，页面上下滚动（乃至中部 / 右缘的横向滑动）不再误触抽屉；抽屉打开后仍是全屏拖拽关闭。
                .pointerInput(
                    drawerState,
                    gesturesEnabled,
                    reverseDirection,
                    edgeSwipeStartPx,
                    edgeSwipeWidthPx,
                ) {
                    if (!gesturesEnabled) return@pointerInput
                    val direction = if (reverseDirection) -1f else 1f
                    val anchoredState = drawerState.anchoredState
                    val touchSlopPx = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down =
                            awaitFirstDown(
                                requireUnconsumed = false,
                                pass = PointerEventPass.Initial,
                            )
                        val drawerVisible =
                            anchoredState.settledValue == DrawerValue.Open ||
                                anchoredState.targetValue == DrawerValue.Open
                        val allowed =
                            drawerDragAllowedAt(
                                startX = down.position.x,
                                containerWidth = size.width.toFloat(),
                                edgeStartPx = edgeSwipeStartPx.toFloat(),
                                edgeWidthPx = edgeSwipeWidthPx,
                                drawerVisible = drawerVisible,
                                rtl = reverseDirection,
                            )
                        if (!allowed) {
                            return@awaitEachGesture
                        }

                        // Initial pass 抢先仲裁：热区里的横向拖拽优先于页面里的横向轮播，纵向滑动一律让给页面滚动。
                        var dx = 0f
                        var dy = 0f
                        var last = down.position
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change =
                                event.changes.firstOrNull { it.id == down.id }
                                    ?: return@awaitEachGesture
                            if (!change.pressed) return@awaitEachGesture
                            dx += change.position.x - last.x
                            dy += change.position.y - last.y
                            last = change.position
                            when (drawerDragVerdict(dx, dy, touchSlopPx)) {
                                DrawerDragVerdict.Claim -> {
                                    change.consume()
                                    break
                                }
                                DrawerDragVerdict.Yield -> return@awaitEachGesture
                                DrawerDragVerdict.Pending -> Unit
                            }
                        }
                        drawerState.cancelDragSettle()
                        drawerState.dragBy(dx * direction)
                        val velocityTracker = VelocityTracker()
                        velocityTracker.addPointerInputChange(down)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            velocityTracker.addPointerInputChange(change)
                            if (!change.pressed) break
                            change.consume()
                            drawerState.dragBy((change.position.x - last.x) * direction)
                            last = change.position
                        }
                        val velocity = velocityTracker.calculateVelocity().x * direction
                        // 松手收尾：速度 / 位置阈值定开合，动画走定稿落位规格（开 200ms / 关 160ms）。
                        drawerState.settleAfterDrag(
                            animationScope = scope,
                            velocity = velocity,
                        )
                    }
                }
    ) {
        Box(modifier = Modifier.fillMaxSize()) { content() }

        // 热区向系统申请排除返回手势（部分 ROM 生效；MIUI 仍保留最外侧 ~24dp，故热区已内移到保留区之后）。
        // 该节点只有布局节点、没有 pointerInput，不遮挡热区内内容的点击 / 滚动。
        if (gesturesEnabled) {
            Box(
                modifier = Modifier.fillMaxSize().padding(start = edgeSwipeStartDp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxHeight()
                            .width(DrawerEdgeSwipeWidth)
                            .systemGestureExclusion()
                )
            }
        }

        // 遮罩：alpha 跟随抽屉位移在绘制阶段读取（不触发逐帧重组），点击关闭走 160ms 定稿规格。
        val scrimModifier =
            Modifier.fillMaxSize()
                .graphicsLayer {
                    alpha = drawerFraction(closedAnchor, openAnchor, drawerState.currentOffset)
                }
                .background(colors.scrim)
        Box(
            modifier =
                if (drawerState.isOpen) {
                    scrimModifier.clickable(
                        interactionSource = scrimInteractionSource,
                        indication = null,
                    ) {
                        scope.launch { drawerState.close() }
                    }
                } else {
                    scrimModifier
                }
        )

        Layout(
            content = {
                ModalDrawerSheet(
                    modifier = Modifier.width(drawerWidth).background(colors.surfaceContainer),
                    drawerShape = RectangleShape,
                    // 底色由上一条 modifier 的 background 提供（容器色必须是透明，否则会盖住该层）。
                    drawerContainerColor = Color.Transparent,
                    drawerContentColor = colors.onSurface,
                    drawerTonalElevation = 0.dp,
                ) {
                    CinefinDrawerContent(
                        header = header,
                        groups = groups,
                        selectedIndex = selectedIndex,
                        onSelect = onSelect,
                    )
                }
            },
            modifier =
                Modifier.offset {
                    val offset = drawerState.currentOffset
                    val fallbackClosed = -with(density) { drawerWidth.toPx() }
                    IntOffset(
                        x =
                            if (offset.isNaN()) fallbackClosed.roundToInt()
                            else offset.roundToInt(),
                        y = 0,
                    )
                },
            measurePolicy = { measurables, constraints ->
                val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
                val placeables = measurables.map { it.measure(looseConstraints) }
                val width = placeables.maxOfOrNull { it.width } ?: 0
                val height = placeables.maxOfOrNull { it.height } ?: 0
                layout(width, height) {
                    val measuredClosedAnchor = -width.toFloat()
                    if (!anchorsInitialized || closedAnchor != measuredClosedAnchor) {
                        anchorsInitialized = true
                        closedAnchor = measuredClosedAnchor
                        drawerState.updateAnchors(
                            closedOffsetPx = measuredClosedAnchor,
                            openOffsetPx = openAnchor,
                        )
                    }
                    if (drawerFraction(closedAnchor, openAnchor, drawerState.currentOffset) != 0f) {
                        // 关闭时不放置抽屉内容，键盘焦点 / 无障碍遍历不会落到屏外条目。
                        placeables.forEach { it.placeRelative(0, 0) }
                    }
                }
            },
        )
    }
}

private fun drawerFraction(closed: Float, open: Float, offset: Float): Float =
    if (offset.isNaN() || closed == open) 0f
    else ((offset - closed) / (open - closed)).coerceIn(0f, 1f)

/** 抽屉内容（与 Modal 外壳解耦，供预览与自定义容器复用）。 */
@Composable
internal fun CinefinDrawerContent(
    header: @Composable () -> Unit,
    groups: List<CinefinDrawerGroup>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxHeight()) {
        Box(
            modifier =
                Modifier.fillMaxWidth().height(96.dp).padding(horizontal = CinefinSpacing.Space5),
            contentAlignment = Alignment.CenterStart,
        ) {
            header()
        }
        // 条目区可滚动：媒体库默认展开后抽屉条目变多（库列表跟在「媒体库」之后），
        // 矮屏（横屏手机）不能把底部条目裁掉。
        Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            var flatIndex = 0
            groups.forEach { group ->
                if (group.dividerAboveTitle) {
                    CinefinRailGroupDivider(expanded = true)
                }
                if (group.title != null) {
                    Text(
                        text = group.title,
                        style = CinefinType.LabelSmall,
                        color = colors.onSurfaceFaint,
                        modifier =
                            Modifier.padding(
                                start = CinefinSpacing.Space5,
                                end = CinefinSpacing.Space5,
                                top = CinefinSpacing.Space3,
                                bottom = CinefinSpacing.Space2,
                            ),
                    )
                }
                group.items.forEach { item ->
                    val index = flatIndex++
                    CinefinDrawerItem(
                        item = item,
                        selected = index == selectedIndex,
                        onClick = { onSelect(index) },
                    )
                }
            }
            Spacer(Modifier.height(CinefinSpacing.Space4))
        }
    }
}

/** 抽屉条目：56dp 高、圆角 16dp（K1）、左右 16dp 内边距；二级子项（[CinefinNavItem.nested]）48dp + 缩进。 */
@Composable
private fun CinefinDrawerItem(
    item: CinefinNavItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val media = LocalMediaColors.current
    val colors = LocalCinefinColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val container =
        navItemContainerColor(
            selected = selected,
            neutral = item.neutral,
            hovered = hovered,
            pressed = pressed,
            media = media,
            colors = colors,
        )
    val content =
        navItemContentColor(
            selected = selected,
            pressed = pressed,
            colors = colors,
        )
    val shape = CinefinShapes.Button

    CompositionLocalProvider(
        LocalNavItemInteraction provides
            NavItemInteractionState(selected = selected, hovered = hovered, pressed = pressed)
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start =
                            CinefinSpacing.Space3 +
                                if (item.nested) CinefinSpacing.Space4 else 0.dp,
                        end = CinefinSpacing.Space3,
                    )
                    .height(if (item.nested) 48.dp else 56.dp)
                    .clip(shape)
                    .background(container)
                    .then(
                        if (selected) {
                            Modifier.border(1.dp, colors.glassBorder, shape)
                        } else {
                            Modifier
                        }
                    )
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                    .padding(horizontal = CinefinSpacing.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                item.icon(selected)
                item.badge?.let { badge ->
                    Box(modifier = Modifier.padding(start = 4.dp)) { badge() }
                }
            }
            Spacer(Modifier.width(CinefinSpacing.Space4))
            Text(
                text = item.label,
                style = CinefinType.NavLabel,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (item.trailing != null) Modifier.weight(1f) else Modifier,
            )
            item.trailing?.invoke()
        }
    }
}
