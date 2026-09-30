package com.zhangwenkang.cinefin.book.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator

@OptIn(ExperimentalMaterial3Api::class, ExperimentalReadiumApi::class)
@Composable
fun ReaderScreen(
    state: ReaderUiState,
    title: String,
    onLocationChanged: (Locator) -> Unit,
    onRetry: () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var scroll by remember { mutableStateOf(false) }
        var navigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }

        LaunchedEffect(scroll, navigator) {
            navigator?.submitPreferences(EpubPreferences(scroll = scroll, theme = Theme.DARK))
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    actions = {
                        TextButton(onClick = { scroll = !scroll }) {
                            Text(if (scroll) "滚动模式" else "翻页模式")
                        }
                    },
                )
            }
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
                            Text(state.message)
                            Button(onClick = onRetry) { Text("重试") }
                        }
                    }

                is ReaderUiState.Ready ->
                    ReadiumEpubView(
                        publication = state.publication,
                        initialLocator = state.initialLocator,
                        scroll = scroll,
                        onLocationChanged = onLocationChanged,
                        onNavigatorReady = { navigator = it },
                        modifier = Modifier.fillMaxSize().padding(padding),
                    )
            }
        }
    }
}
