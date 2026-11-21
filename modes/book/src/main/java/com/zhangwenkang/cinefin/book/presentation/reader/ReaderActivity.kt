package com.zhangwenkang.cinefin.book.presentation.reader

import android.animation.ValueAnimator
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import javax.inject.Inject
import kotlin.math.roundToInt
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import timber.log.Timber

/**
 * 阅读器入口（EB-10）。
 *
 * W3 R3 起由 `NavigationRoot` 的书籍条目以显式 Intent 打开（`EXTRA_ITEM_ID` / `EXTRA_TITLE`）， 已改回
 * `exported=false`； 调试期如需 adb 直启，临时改 manifest 后务必还原。
 */
@AndroidEntryPoint
class ReaderActivity : AppCompatActivity() {
    private val viewModel: ReaderViewModel by viewModels()

    @Inject lateinit var appPreferences: AppPreferences

    /** W6 T4：Enter 键的控制层开合信号（每次 +1 触发一次；[ReaderScreen] 侧消费）。 */
    private var controlsToggleRequest by mutableIntStateOf(0)

    /** PDF / CBZ 当前位置（由页序列视图回报）：键盘翻页从这里步进。 */
    private var simplePage = -1

    /** 方向设置变化回调；持有实例以便 `onDestroy` 精确摘除。 */
    private val orientationListener: (ReaderOrientation) -> Unit = { applyReaderOrientation(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        // W6 T4：两个输入设置（音量键翻页 / 方向锁定）先于首帧恢复，避免方向闪一下再锁定。
        ReaderInputSetting.bind(appPreferences)
        ReaderPageAnimationSetting.bind(this)
        applyReaderOrientation(ReaderInputSetting.orientation)
        ReaderInputSetting.onOrientationChanged = orientationListener

        val itemId = intent.getStringExtra(EXTRA_ITEM_ID)?.toItemIdOrNull()
        // W37：本地媒体库书籍（SAF content://）；与 itemId 二选一，本地优先。
        val localUri = intent.getStringExtra(EXTRA_LOCAL_URI)?.let(android.net.Uri::parse)
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "阅读器 PoC" }
        // 页面倍率的进程内记忆键：同一本书（itemId / 本地 URI）重进保留，切书 / 重启回 1×（ADR-0002）。
        val bookKey = itemId?.toString() ?: localUri?.toString() ?: title

        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val downloadState by viewModel.downloadState.collectAsStateWithLifecycle()
            val streamState by viewModel.streamState.collectAsStateWithLifecycle()
            val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
            val pendingSyncCount by viewModel.pendingSyncCount.collectAsStateWithLifecycle()
            val jumpTarget by viewModel.jumpTarget.collectAsStateWithLifecycle()
            val searchState by viewModel.searchState.collectAsStateWithLifecycle()
            val annotations by viewModel.annotations.collectAsStateWithLifecycle()
            val pageJumpTarget by viewModel.pageJumpTarget.collectAsStateWithLifecycle()
            ReaderScreen(
                state = state,
                settings = settings,
                title = title,
                bookKey = bookKey,
                systemDark = isSystemInDarkTheme(),
                downloadState = downloadState,
                streamState = streamState,
                bookmarks = bookmarks,
                pendingSyncCount = pendingSyncCount,
                jumpTarget = jumpTarget,
                searchState = searchState,
                annotations = annotations,
                pageJumpTarget = pageJumpTarget,
                onSettingsChange = viewModel::updateSettings,
                onLocationChanged = viewModel::onLocationChanged,
                onSimplePageChanged = { index, pageCount ->
                    simplePage = index
                    viewModel.onSimplePageChanged(index, pageCount)
                },
                onRetry = viewModel::retry,
                onDownload = viewModel::downloadBook,
                onAddBookmark = viewModel::addBookmark,
                onRemoveBookmark = viewModel::removeBookmark,
                onJumpToBookmark = viewModel::jumpTo,
                onJumpHandled = viewModel::consumeJumpTarget,
                onNavigatorReady = viewModel::onNavigatorLocator,
                onSearchQueryChange = viewModel::updateSearchQuery,
                onCancelSearch = viewModel::cancelSearch,
                onJumpToHit = { hit -> viewModel.jumpToPage(hit.pageIndex) },
                onJumpToAnnotation = { annotation -> viewModel.jumpToPage(annotation.pageIndex) },
                onPageJumpHandled = viewModel::consumePageJumpTarget,
                onAddAnnotation = viewModel::addAnnotation,
                onUpdateAnnotationNote = viewModel::updateAnnotationNote,
                onRemoveAnnotation = viewModel::removeAnnotation,
                onBack = { finish() },
                controlsToggleRequest = controlsToggleRequest,
                onReloadContents = viewModel::reloadNavigation,
            )
        }

        if (localUri != null) {
            viewModel.openLocal(localUri, title)
        } else if (itemId != null) {
            viewModel.open(itemId)
        }
    }

    /** 退到后台立即落盘 + 尝试回传（ARCHITECTURE §3.6 上报时机）。 */
    override fun onStop() {
        viewModel.onStopReading()
        super.onStop()
    }

    override fun onDestroy() {
        if (ReaderInputSetting.onOrientationChanged === orientationListener) {
            ReaderInputSetting.onOrientationChanged = null
        }
        super.onDestroy()
    }

    /** 窗口重新获得焦点（从系统栏手势 / 弹层返回）时回到沉浸态。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    /**
     * W6 T4（定稿三 §1）：音量键 / 键盘翻页。
     *
     * 映射见 [readerKeyAction]（纯函数）；未命中或音量键开关关闭时返回 false 交给系统（音量键继续调音量）。
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val action =
            readerKeyAction(
                keyCode = keyCode,
                volumeKeysPaging = ReaderInputSetting.volumeKeysPaging,
                scrollMode = isScrollMode(),
            ) ?: return super.onKeyDown(keyCode, event)
        handleReaderKey(action)
        return true
    }

    /** 按下时已消费的键，抬起同样吞掉，避免系统音量面板 / 默认点击被补触发。 */
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (readerKeyAction(keyCode, ReaderInputSetting.volumeKeysPaging, isScrollMode()) != null) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun isScrollMode(): Boolean = viewModel.settings.value.mode == ReaderMode.Scroll

    private fun handleReaderKey(action: ReaderKeyAction) {
        when (action) {
            ReaderKeyAction.ToggleControls -> controlsToggleRequest++
            ReaderKeyAction.PreviousPage -> turnPage(forward = false)
            ReaderKeyAction.NextPage -> turnPage(forward = true)
            ReaderKeyAction.ScrollUp -> scrollByLines(lines = -1)
            ReaderKeyAction.ScrollDown -> scrollByLines(lines = 1)
        }
    }

    private fun turnPage(forward: Boolean) {
        when (val document = (viewModel.state.value as? ReaderUiState.Ready)?.document) {
            is ReaderDocument.Rich -> turnEpubPage(forward)
            is ReaderDocument.Simple -> turnSimplePage(document, forward)
            null -> Unit
        }
    }

    /**
     * EPUB：直接驱动 Readium 导航器（`goForward` / `goBackward`），与单击三分区同一入口。
     *
     * 导航器由 [ReadiumEpubView] 挂在 Activity 的 `supportFragmentManager` 上（Fragment 内核），这里按类型取回。
     */
    private fun turnEpubPage(forward: Boolean) {
        val navigator = findEpubNavigator() ?: return
        val animated = currentEpubTurnAnimated()
        runCatching {
            if (forward) navigator.goForward(animated = animated)
            else navigator.goBackward(animated = animated)
        }
            .onFailure { Timber.w(it, "键盘翻页失败") }
    }

    private fun turnSimplePage(document: ReaderDocument.Simple, forward: Boolean) {
        val pageCount = document.pageSource.pageCount
        if (pageCount <= 0) return
        val current = currentSimplePage(document)
        // 双栏一屏两页：按「槽」步进，和单击三分区同一语义（不能只 +1，否则半步仍在同屏）。
        val step = if (viewModel.settings.value.mode == ReaderMode.TwoColumn) 2 else 1
        val target = (current + if (forward) step else -step).coerceIn(0, pageCount - 1)
        if (target != current) {
            // 乐观推进：视图的页码回报要等 Pager / LazyColumn 落位，连按音量键时不能等它。
            simplePage = target
            viewModel.jumpToPage(target)
        }
    }

    private fun currentSimplePage(document: ReaderDocument.Simple): Int {
        val pageCount = document.pageSource.pageCount
        if (pageCount <= 0) return 0
        return if (simplePage in 0 until pageCount) simplePage
        else document.initialPage.coerceIn(0, pageCount - 1)
    }

    /**
     * 滚动档：EPUB 按行滚动当前 Readium 页面（WebView `scrollBy`）；PDF / CBZ 的滚动容器按页定位， 退回整页步进（页序列视图未暴露行级滚动入口）。
     */
    private fun scrollByLines(lines: Int) {
        when (val document = (viewModel.state.value as? ReaderUiState.Ready)?.document) {
            is ReaderDocument.Rich -> scrollEpubLines(lines)
            is ReaderDocument.Simple -> turnSimplePage(document, forward = lines > 0)
            null -> Unit
        }
    }

    private fun scrollEpubLines(lines: Int) {
        val navigator = findEpubNavigator() ?: return
        val webView = runCatching {
            findVisibleWebView(navigator.publicationView)
        }
            .onFailure { Timber.w(it, "读取 Readium 页面失败") }
            .getOrNull()
        if (webView == null) {
            // 导航器尚未挂载时退回视口滚动，保证按键不丢。
            runCatching {
                if (lines > 0) navigator.goForward(animated = false)
                else navigator.goBackward(animated = false)
            }
            return
        }
        val delta =
            (READER_KEY_SCROLL_LINE_DP * resources.displayMetrics.density * lines).roundToInt()
        webView.scrollBy(0, delta)
    }

    private fun findEpubNavigator(): EpubNavigatorFragment? =
        supportFragmentManager.fragments.filterIsInstance<EpubNavigatorFragment>().firstOrNull {
            it.isAdded
        }

    private fun findVisibleWebView(view: View?): WebView? {
        when {
            view == null -> return null
            view is WebView -> return view.takeIf { it.isShown }
            view is ViewGroup ->
                for (index in 0 until view.childCount) {
                    findVisibleWebView(view.getChildAt(index))?.let {
                        return it
                    }
                }
        }
        return null
    }

    private fun currentEpubTurnAnimated(): Boolean =
        epubPageTurnAnimated(
            animation = ReaderPageAnimationSetting.value,
            reduceMotion = !ValueAnimator.areAnimatorsEnabled(),
        )

    /** W6 T4（定稿三 §3）：方向三档 → `requestedOrientation`；跟随系统 = 交给系统旋转 / 用户旋转锁。 */
    private fun applyReaderOrientation(orientation: ReaderOrientation) {
        val requested =
            when (orientation) {
                ReaderOrientation.FollowSystem -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ReaderOrientation.Portrait -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ReaderOrientation.Landscape -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        if (requestedOrientation != requested) requestedOrientation = requested
    }

    /**
     * W6 T1 定稿一 §1：进入阅读页即沉浸 —— 隐藏状态栏与导航栏，正文占满全屏。
     *
     * 系统栏经手势滑动可短暂唤出（`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`），松手后自动收回。
     */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    companion object {
        const val EXTRA_ITEM_ID = "itemId"
        const val EXTRA_TITLE = "title"
        /** W37：本地媒体库书籍的 SAF 文档 URI。 */
        const val EXTRA_LOCAL_URI = "localUri"
    }
}

/** Jellyfin REST 的 UUID 有两种写法：36 位标准格式与 32 位无连字符格式。 */
private fun String.toItemIdOrNull(): UUID? {
    if (matches(Regex("[0-9a-fA-F]{32}"))) {
        val dashed =
            "${substring(0, 8)}-${substring(8, 12)}-${substring(12, 16)}-" +
                "${substring(16, 20)}-${substring(20, 32)}"
        return runCatching { UUID.fromString(dashed) }.getOrNull()
    }
    return runCatching { UUID.fromString(this) }.getOrNull()
}
