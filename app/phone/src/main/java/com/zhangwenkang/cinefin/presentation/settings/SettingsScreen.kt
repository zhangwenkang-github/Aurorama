package com.zhangwenkang.cinefin.presentation.settings

import android.app.Activity
import android.app.UiModeManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.recalculateWindowInsets
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.presentation.settings.components.SettingsGroupCard
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.presentation.utils.plus
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceCategory
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceGroup
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsAction
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsEvent
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsState
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsViewModel
import com.zhangwenkang.cinefin.utils.ObserveAsEvents
import com.zhangwenkang.cinefin.utils.restart
import timber.log.Timber

@Composable
fun SettingsScreen(
    indexes: IntArray = intArrayOf(),
    navigateToSettings: (indexes: IntArray) -> Unit,
    navigateToSettingsFileEdit: (filePath: String) -> Unit,
    navigateToServers: () -> Unit,
    navigateToUsers: () -> Unit,
    navigateToAbout: () -> Unit,
    navigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(true) { viewModel.loadPreferences(indexes, DeviceType.PHONE) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is SettingsEvent.NavigateToSettings -> navigateToSettings(event.indexes)
            is SettingsEvent.NavigateToSettingsFileEdit ->
                navigateToSettingsFileEdit(event.filePath)
            is SettingsEvent.NavigateToUsers -> navigateToUsers()
            is SettingsEvent.NavigateToServers -> navigateToServers()
            is SettingsEvent.NavigateToAbout -> navigateToAbout()
            is SettingsEvent.UpdateTheme -> {
                val uiModeManager = context.getSystemService(UiModeManager::class.java)
                val nightMode =
                    when (event.theme) {
                        "system" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_AUTO
                            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                        "light" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_NO
                            else AppCompatDelegate.MODE_NIGHT_NO
                        "dark" ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_YES
                            else AppCompatDelegate.MODE_NIGHT_YES
                        else ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                                UiModeManager.MODE_NIGHT_AUTO
                            else AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    uiModeManager.setApplicationNightMode(nightMode)
                } else {
                    AppCompatDelegate.setDefaultNightMode(nightMode)
                }
            }
            is SettingsEvent.LaunchIntent -> {
                try {
                    context.startActivity(event.intent)
                } catch (e: Exception) {
                    Timber.e(e)
                }
            }
            is SettingsEvent.RestartActivity -> {
                try {
                    (context as Activity).restart()
                } catch (_: Exception) {}
            }
        }
    }

    SettingsScreenLayout(
        title = indexes.last(),
        state = state,
        onAction = { action ->
            when (action) {
                is SettingsAction.OnBackClick -> navigateBack()
                is SettingsAction.OnUpdate -> {
                    viewModel.onAction(action)
                    viewModel.loadPreferences(indexes, DeviceType.PHONE)
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreenLayout(
    @StringRes title: Int,
    state: SettingsState,
    onAction: (SettingsAction) -> Unit,
) {
    val contentPadding = PaddingValues(all = MaterialTheme.spacings.default)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        modifier =
            Modifier.fillMaxSize()
                .recalculateWindowInsets()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = { onAction(SettingsAction.OnBackClick) }) {
                        Icon(
                            painter = painterResource(CoreR.drawable.ic_arrow_left),
                            contentDescription = null,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = contentPadding + innerPadding,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacings.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            items(state.preferenceGroups) { group ->
                SettingsGroupCard(
                    group = group,
                    onAction = onAction,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
        }
    }
}

@PreviewScreenSizes
@Composable
private fun SettingsScreenLayoutPreview() {
    CinefinTheme {
        SettingsScreenLayout(
            title = CoreR.string.title_settings,
            state =
                SettingsState(
                    preferenceGroups =
                        listOf(
                            PreferenceGroup(
                                nameStringResource = null,
                                preferences =
                                    listOf(
                                        PreferenceCategory(
                                            nameStringResource =
                                                SettingsR.string.settings_category_language,
                                            iconDrawableId = SettingsR.drawable.ic_languages,
                                        )
                                    ),
                            ),
                            PreferenceGroup(
                                nameStringResource = null,
                                preferences =
                                    listOf(
                                        PreferenceCategory(
                                            nameStringResource =
                                                SettingsR.string.settings_category_interface,
                                            iconDrawableId = SettingsR.drawable.ic_palette,
                                        )
                                    ),
                            ),
                        )
                ),
            onAction = {},
        )
    }
}
