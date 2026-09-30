package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinTokens
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.ContentDomain
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

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

        Surface(modifier = Modifier.fillMaxSize(), color = settings.surfaceColor(systemDark)) {
            Scaffold(
                containerColor = Color.Transparent,
                topBar = {
                    TopAppBar(
                        title = {
                            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        colors =
                            TopAppBarDefaults.topAppBarColors(
                                containerColor = chromeColor,
                                titleContentColor = contentColor,
                                actionIconContentColor = contentColor,
                            ),
                        actions = {
                            TextButton(
                                onClick = {
                                    onSettingsChange(settings.copy(mode = settings.mode.next()))
                                }
                            ) {
                                Text(
                                    text = settings.mode.label,
                                    style = CinefinType.LabelLarge,
                                    color = contentColor,
                                )
                            }
                            TextButton(onClick = { showSettings = true }) {
                                Text(
                                    text = "Aa",
                                    style = CinefinType.LabelLarge,
                                    color = contentColor,
                                )
                            }
                        },
                    )
                },
            ) { padding ->
                when (state) {
                    ReaderUiState.Loading ->
                        Box(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator()
                        }

                    is ReaderUiState.Error ->
                        Box(
                            modifier = Modifier.fillMaxSize().padding(padding),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Text(state.message, color = contentColor)
                                Button(onClick = onRetry) { Text("重试") }
                            }
                        }

                    is ReaderUiState.Ready ->
                        ReadiumEpubView(
                            publication = state.publication,
                            initialLocator = state.initialLocator,
                            settings = settings,
                            systemDark = systemDark,
                            onLocationChanged = onLocationChanged,
                            modifier = Modifier.fillMaxSize().padding(padding),
                        )
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
                            Modifier.padding(top = 12.dp)
                                .size(width = 32.dp, height = 4.dp)
                                .clip(CinefinShapes.TwoXs)
                                .background(contentColor.copy(alpha = 0.24f))
                    )
                },
            ) {
                Box(
                    modifier =
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
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
