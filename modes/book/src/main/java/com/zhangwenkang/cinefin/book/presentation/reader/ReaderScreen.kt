package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButton
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonSize
import com.zhangwenkang.cinefin.core.presentation.components.CinefinButtonVariant
import com.zhangwenkang.cinefin.core.presentation.components.CinefinEmptyState
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

/**
 * 阅读页（§9 Compose 落地）：外壳（顶栏 / 加载 / 错误态 / 设置面板）全部走 Prism 组件。
 *
 * 阅读主题（纸色 / 护眼 / 深色 / OLED / 跟随）驱动 `CinefinTheme` 的亮暗与底色； 纸色 / 护眼主题内媒体色整体替换为纸页棕（§8.14），所有控件通过
 * `LocalMediaColors` 自动取色。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    settings: ReaderSettings,
    title: String,
    systemDark: Boolean,
    onSettingsChange: (ReaderSettings) -> Unit,
    onLocationChanged: (Locator) -> Unit,
    onRetry: () -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }

    CinefinTheme(
        domain = ContentDomain.Book,
        darkTheme = settings.isDark(systemDark),
        surfaceBackground = false,
    ) {
        val contentColor = settings.contentColor(systemDark)
        val chromeColor = settings.chromeColor(systemDark)
        val accent = settings.accentColor(systemDark)

        CompositionLocalProvider(LocalMediaColors provides settings.mediaColors(systemDark)) {
            Surface(modifier = Modifier.fillMaxSize(), color = settings.surfaceColor(systemDark)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    ReaderTopBar(
                        title = title,
                        settings = settings,
                        contentColor = contentColor,
                        chromeColor = chromeColor,
                        onCycleMode = {
                            onSettingsChange(settings.copy(mode = settings.mode.next()))
                        },
                        onOpenSettings = { showSettings = true },
                    )

                    Box(modifier = Modifier.fillMaxSize()) {
                        when (state) {
                            ReaderUiState.Loading ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(color = accent)
                                }

                            is ReaderUiState.Error ->
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CinefinEmptyState(
                                        title = "打不开这本书",
                                        message = state.message,
                                        action = {
                                            CinefinButton(
                                                text = "重试",
                                                onClick = onRetry,
                                                size = CinefinButtonSize.Medium,
                                            )
                                        },
                                    )
                                }

                            is ReaderUiState.Ready ->
                                ReadiumEpubView(
                                    publication = state.publication,
                                    initialLocator = state.initialLocator,
                                    settings = settings,
                                    systemDark = systemDark,
                                    onLocationChanged = onLocationChanged,
                                    modifier = Modifier.fillMaxSize(),
                                )
                        }
                    }
                }
            }

            if (showSettings) {
                ModalBottomSheet(
                    onDismissRequest = { showSettings = false },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = chromeColor,
                    contentColor = contentColor,
                    scrimColor = CinefinTokens.Scrim.copy(alpha = CinefinTokens.ScrimAlpha),
                    dragHandle = {
                        Box(
                            modifier =
                                Modifier.padding(top = CinefinSpacing.Space3)
                                    .size(width = 32.dp, height = 4.dp)
                                    .clip(CinefinShapes.TwoXs)
                                    .background(contentColor.copy(alpha = 0.24f))
                        )
                    },
                ) {
                    Box(
                        modifier =
                            Modifier.fillMaxWidth()
                                .padding(horizontal = CinefinSpacing.Space6)
                                .padding(bottom = CinefinSpacing.Space6),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        ReaderSettingsPanel(
                            settings = settings,
                            systemDark = systemDark,
                            onSettingsChange = onSettingsChange,
                        )
                    }
                }
            }
        }
    }
}

/** 阅读页顶栏：底 = 阅读器外壳色，标题 + 模式切换 + 「Aa」排版面板入口。 */
@Composable
private fun ReaderTopBar(
    title: String,
    settings: ReaderSettings,
    contentColor: Color,
    chromeColor: Color,
    onCycleMode: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().background(chromeColor)) {
        Row(
            modifier =
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = CinefinSpacing.Space4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                modifier = Modifier.weight(1f),
                style = CinefinType.TitleMedium,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
            CinefinButton(
                text = settings.mode.label,
                onClick = onCycleMode,
                variant = CinefinButtonVariant.Outlined,
                size = CinefinButtonSize.Small,
            )
            Spacer(modifier = Modifier.width(CinefinSpacing.Space2))
            CinefinButton(
                text = "Aa",
                onClick = onOpenSettings,
                variant = CinefinButtonVariant.Text,
                size = CinefinButtonSize.Small,
            )
        }
        Box(
            modifier =
                Modifier.fillMaxWidth().height(1.dp).background(contentColor.copy(alpha = 0.12f))
        )
    }
}
