package com.zhangwenkang.cinefin.presentation.settings

import android.app.Activity
import android.app.UiModeManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.navigation.DrawerState
import com.zhangwenkang.cinefin.presentation.navigation.DrawerViewModel
import com.zhangwenkang.cinefin.presentation.settings.components.SettingsGroupCard
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.utils.rememberPageGutter
import com.zhangwenkang.cinefin.presentation.utils.rememberSafePadding
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
        onReloadPreferences = { viewModel.loadPreferences(indexes, DeviceType.PHONE) },
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

/**
 * 设置页：一条固定的返回行 + 可滚动的分组清单。
 *
 * 标题用衬线，与片名保持同一套"印刷品"语气；分组之间靠 32dp 留白分隔， 组内是发丝线，因此页面既不空旷也不拥挤——这也是整份重做的基线节奏。
 */
@Composable
private fun SettingsScreenLayout(
    @StringRes title: Int,
    state: SettingsState,
    onReloadPreferences: () -> Unit,
    onAction: (SettingsAction) -> Unit,
) {
    val safePadding = rememberSafePadding(handleStartInsets = false)
    val gutter = rememberPageGutter()
    val colors = LocalCinefinColors.current
    val accountViewModel: DrawerViewModel = hiltViewModel()
    val accountState by accountViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { accountViewModel.load() }

    // 媒体库目录（偏好缓存）由 DrawerViewModel 在加载抽屉数据时写入；服务器库列表到达后
    // 重新加载设置项，让「首页 / 音乐 / 书架使用哪个媒体库」的选项立刻可用（含新建的库）。
    LaunchedEffect(accountState.libraries) {
        if (accountState.libraries.isNotEmpty()) {
            onReloadPreferences()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(colors.surface)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier =
                Modifier.fillMaxWidth()
                    .padding(
                        start = safePadding.start + CinefinSpacing.Space3,
                        top = safePadding.top + CinefinSpacing.Space3,
                        end = safePadding.end + CinefinSpacing.Space3,
                    )
                    .height(48.dp),
        ) {
            TopBarAction(
                icon = CoreR.drawable.ic_arrow_left,
                onClick = { onAction(SettingsAction.OnBackClick) },
            )
            Text(
                text = stringResource(title),
                style = CinefinType.HeadlineSmall,
                color = colors.onSurface,
                modifier = Modifier.padding(start = CinefinSpacing.Space2),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding =
                PaddingValues(
                    start = safePadding.start + gutter,
                    end = safePadding.end + gutter,
                    top = CinefinSpacing.Space4,
                    bottom = safePadding.bottom + CinefinSpacing.Space8,
                ),
            verticalArrangement = Arrangement.spacedBy(CinefinSpacing.Space8),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item(key = "account") {
                SettingsAccountHeader(
                    state = accountState,
                    modifier = Modifier.widthIn(max = 640.dp),
                )
            }
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

/**
 * 设置页顶部的账号条：头像首字 + 账号 + 服务器，右侧标出账号身份。
 *
 * 管理员身份直接写在界面上——用户由此明白抽屉里为什么会多出「服务器控制台」， 也解释了权限从哪里来，比藏在设置深处的开关更容易理解。
 */
@Composable
private fun SettingsAccountHeader(state: DrawerState, modifier: Modifier = Modifier) {
    val colors = LocalCinefinColors.current
    val isAdministrator = state.isAdministrator
    val badgeColor = if (isAdministrator) colors.onSurface else colors.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = CinefinSpacing.Space4, vertical = CinefinSpacing.Space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            Box(
                modifier =
                    Modifier.size(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = state.userName?.trim()?.take(1)?.uppercase().orEmpty().ifEmpty { "?" },
                    style = CinefinType.TitleLarge,
                    color = colors.onSurface,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.userName ?: stringResource(CoreR.string.not_set),
                    style = CinefinType.TitleMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle =
                    listOfNotNull(state.serverName, state.serverAddress).joinToString(" · ")
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
                        style = CinefinType.BodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Text(
                text =
                    stringResource(
                        if (isAdministrator) CoreR.string.role_administrator
                        else CoreR.string.role_regular_user
                    ),
                style = CinefinType.LabelMedium,
                color = badgeColor,
                modifier =
                    Modifier.clip(CinefinShapes.Xs)
                        .border(
                            width = 1.dp,
                            color = badgeColor.copy(alpha = 0.5f),
                            shape = CinefinShapes.Xs,
                        )
                        .padding(
                            horizontal = CinefinSpacing.Space2,
                            vertical = CinefinSpacing.Space1,
                        ),
            )
        }
        HorizontalDivider(color = colors.outlineVariant)
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
            onReloadPreferences = {},
            onAction = {},
        )
    }
}
