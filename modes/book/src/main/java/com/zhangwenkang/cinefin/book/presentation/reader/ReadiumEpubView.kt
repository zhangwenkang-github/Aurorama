package com.zhangwenkang.cinefin.book.presentation.reader

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import org.readium.r2.navigator.epub.EpubDefaults
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
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
    onLocationChanged: (Locator) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity ?: return
    val currentSettings = rememberUpdatedState(settings)
    val currentSystemDark = rememberUpdatedState(systemDark)
    val currentOnLocationChanged = rememberUpdatedState(onLocationChanged)
    var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

    val listener =
        remember(publication) {
            object : EpubNavigatorFragment.Listener, EpubNavigatorFragment.PaginationListener {
                override fun onPageChanged(
                    pageIndex: Int,
                    totalPages: Int,
                    locator: Locator,
                ) {
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

    key(publication) {
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
                                            EpubDefaults(scroll = current.mode == ReaderMode.Scroll)
                                    ),
                            )
                            .createFragmentFactory(
                                initialLocator,
                                null,
                                current.toEpubPreferences(currentSystemDark.value),
                                listener,
                                listener,
                                EpubNavigatorFragment.Configuration(),
                            )
                    val fragment =
                        factory.instantiate(
                            requireNotNull(publication.javaClass.classLoader),
                            EpubNavigatorFragment::class.java.name,
                        ) as EpubNavigatorFragment
                    activity.supportFragmentManager
                        .beginTransaction()
                        .replace(container.id, fragment, NAVIGATOR_TAG)
                        .commitNow()
                    navigator = fragment
                }
                container
            },
            modifier = modifier,
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
