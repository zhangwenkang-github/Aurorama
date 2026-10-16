package com.zhangwenkang.cinefin.book.presentation.reader

import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl

private const val NAVIGATOR_TAG = "CinefinEpubNavigator"

/**
 * 用 Compose `AndroidView` 承载 Readium 的 `EpubNavigatorFragment`。
 *
 * Readium 3.4.0 的 Visual Navigator 仍是 Fragment 体系：这里创建 `FragmentContainerView`，由
 * `EpubNavigatorFactory` 生成 FragmentFactory 后手动添加。
 */
@OptIn(ExperimentalReadiumApi::class)
@Composable
internal fun ReadiumEpubView(
    publication: Publication,
    initialLocator: Locator?,
    settings: ReaderSettings,
    systemDark: Boolean,
    jumpTarget: Locator?,
    onLocationChanged: (Locator) -> Unit,
    onJumpHandled: () -> Unit,
    onNavigatorReady: (Locator) -> Unit,
    /** W80（#32）：当前屏幕亮度（0–1），上下滑手势从这里起算。 */
    brightnessProvider: () -> Float = { 1f },
    onBrightness: (Float) -> Unit = {},
    /** W80（#32）：双击中间 = 单 / 双栏切换。 */
    onToggleTwoColumn: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    // W80（#24）：平板竖屏时 Readium 的 `--USER__colCount` 被 CSS 媒体查询丢掉（只认 ≥60em 或横屏），
    // 双栏档会静默变单栏；这里在宽度够用的形态下直接压内部变量兜底（滚动档会被 `columns:auto auto` 覆盖，不受影响）。
    val forceTwoColumns =
        settings.mode == ReaderMode.TwoColumn && shouldForceEpubTwoColumns(screenWidthDp)
    val currentSettings = rememberUpdatedState(settings)
    val currentSystemDark = rememberUpdatedState(systemDark)
    val currentOnLocationChanged = rememberUpdatedState(onLocationChanged)
    val currentOnJumpHandled = rememberUpdatedState(onJumpHandled)
    val currentOnNavigatorReady = rememberUpdatedState(onNavigatorReady)
    val currentBrightnessProvider = rememberUpdatedState(brightnessProvider)
    val currentOnBrightness = rememberUpdatedState(onBrightness)
    val currentOnToggleTwoColumn = rememberUpdatedState(onToggleTwoColumn)

    // 最近一次上报的位置：切换「双栏兜底」需要重建导航器，用它把阅读位置接回去。
    var liveLocator by remember(publication) { mutableStateOf<Locator?>(null) }
    // W77：`key(publication)` 内部持有导航器状态与效果——远程流式 → 本地热切换会换 publication
    // （同一个 `AndroidView` 节点被重建），状态必须跟着 publication 重建，否则新导航器不会挂载。
    // W80（#24）：`ReadiumCssRsProperties` 只在导航器构造时注入，因此「平板竖屏强制两栏」这条兜底
    // 需要连同 `forceTwoColumns` 一起重建导航器（用 [liveLocator] 续上当前页，位置不回退）。
    key(publication, forceTwoColumns) {
        var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
        val listener = remember {
            object : EpubNavigatorFragment.Listener, EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(
                    pageIndex: Int,
                    totalPages: Int,
                    locator: Locator,
                ) {
                    liveLocator = locator
                    currentOnLocationChanged.value(locator)
                }

                override fun onExternalLinkActivated(url: AbsoluteUrl) {
                    // W1 PoC 不处理外链；W2 接入系统浏览器 / 站内路由。
                }
            }
        }

        LaunchedEffect(navigator, settings, systemDark) {
            navigator?.submitPreferences(settings.toEpubPreferences(systemDark))
        }

        // 书签跳转：等导航器就绪后执行一次，避免 Fragment 还没 add 就 go 丢目标。
        LaunchedEffect(navigator, jumpTarget) {
            val target = jumpTarget ?: return@LaunchedEffect
            val currentNavigator = navigator ?: return@LaunchedEffect
            currentNavigator.go(target, animated = true)
            currentOnJumpHandled.value()
        }

        // 导航器就绪时上报当前位置：用户不翻页也能直接加书签（不触发进度写入）。
        LaunchedEffect(navigator) {
            val currentNavigator = navigator ?: return@LaunchedEffect
            currentOnNavigatorReady.value(currentNavigator.currentLocator.value)
        }

        ReaderGestureArea(
            enabled = true,
            scrollMode = currentSettings.value.mode == ReaderMode.Scroll,
            brightnessProvider = { currentBrightnessProvider.value() },
            onBrightness = { value -> currentOnBrightness.value(value) },
            onDoubleTap = { x, width ->
                val currentNavigator = navigator
                if (currentNavigator != null) {
                    when (readerTapTarget(x, width)) {
                        ReaderTapTarget.Previous -> currentNavigator.goBackward(animated = true)
                        ReaderTapTarget.Next -> currentNavigator.goForward(animated = true)
                        ReaderTapTarget.ToggleFit -> currentOnToggleTwoColumn.value()
                    }
                }
            },
            modifier = modifier,
        ) {
            AndroidView(
                factory = { viewContext ->
                    val container = FragmentContainerView(viewContext)
                    container.id = View.generateViewId()
                    container.post {
                        if (!container.isAttachedToWindow || navigator != null) {
                            return@post
                        }
                        val current = currentSettings.value
                        val factory =
                            EpubNavigatorFactory(
                                    publication = publication,
                                    configuration =
                                        EpubNavigatorFactory.Configuration(
                                            defaults =
                                                EpubDefaults(
                                                    scroll = current.mode == ReaderMode.Scroll
                                                )
                                        ),
                                )
                                .createFragmentFactory(
                                    // 双栏兜底重建导航器时用「最近位置」而不是文档初始位置，避免跳回开头。
                                    liveLocator ?: initialLocator,
                                    null,
                                    current.toEpubPreferences(currentSystemDark.value),
                                    listener,
                                    listener,
                                    EpubNavigatorFragment.Configuration(
                                        readiumCssRsProperties =
                                            if (forceTwoColumns) {
                                                RsProperties(
                                                    overrides =
                                                        mapOf(
                                                            "--RS__colCount" to "2",
                                                            "--RS__colWidth" to "auto",
                                                        )
                                                )
                                            } else {
                                                RsProperties()
                                            }
                                    ),
                                )
                        val fragment =
                            factory.instantiate(
                                requireNotNull(publication.javaClass.classLoader),
                                EpubNavigatorFragment::class.java.name,
                            ) as EpubNavigatorFragment
                        val tag = "$NAVIGATOR_TAG-${System.identityHashCode(publication)}"
                        activity.supportFragmentManager
                            .beginTransaction()
                            .replace(container.id, fragment, tag)
                            .commitNow()
                        navigator = fragment
                    }
                    container
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = {
                    val fragment = navigator?.takeIf { it.isAdded } ?: return@AndroidView
                    activity.supportFragmentManager
                        .beginTransaction()
                        .remove(fragment)
                        .commitNowAllowingStateLoss()
                    navigator = null
                },
            )
        }
    }
}
