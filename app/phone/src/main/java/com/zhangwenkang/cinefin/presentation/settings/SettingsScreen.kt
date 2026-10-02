package com.zhangwenkang.cinefin.presentation.settings

import android.app.Activity
import android.app.UiModeManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.cinefinClickable
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinMotion
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.components.LumenSkeletonOverlay
import com.zhangwenkang.cinefin.presentation.components.SettingsSkeleton
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
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
    /** W42：紧凑形态（手机底部 Tab）才允许「隐藏底栏」；平板形态开关置灰 + 说明。 */
    compactForm: Boolean = true,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(indexes.toList(), compactForm) {
        viewModel.loadPreferences(indexes, DeviceType.PHONE, compactForm)
    }

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
        onReloadPreferences = { viewModel.loadPreferences(indexes, DeviceType.PHONE, compactForm) },
        onOpenUsers = navigateToUsers,
        onAction = { action ->
            when (action) {
                is SettingsAction.OnBackClick -> navigateBack()
                is SettingsAction.OnUpdate -> {
                    viewModel.onAction(action)
                    viewModel.loadPreferences(indexes, DeviceType.PHONE, compactForm)
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
    onOpenUsers: () -> Unit,
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
        // 发丝线：把"标题栏"和"设置清单"分成两层，滚动时标题不再与内容粘在一起
        HorizontalDivider(color = colors.outlineVariant)

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val listAlpha by
                animateFloatAsState(
                    targetValue = if (state.isLoading) 0f else 1f,
                    animationSpec = tween(CinefinMotion.Reader),
                    label = "settings-content-alpha",
                )
            LazyColumn(
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = listAlpha },
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
                    LumenCardFrame(
                        shape = CinefinShapes.Lg,
                        modifier = Modifier.fillMaxWidth().widthIn(max = 640.dp),
                    ) {
                        SettingsAccountHeader(state = accountState, onClick = onOpenUsers)
                    }
                }
                items(state.preferenceGroups) { group ->
                    SettingsGroupCard(
                        group = group,
                        onAction = onAction,
                        modifier = Modifier.widthIn(max = 640.dp),
                    )
                }
            }

            // 设置加载过渡（W6-VIS D24）：首帧不再是一块纯黑板——先给分组卡骨架，数据到达后淡出
            LumenSkeletonOverlay(visible = state.isLoading && state.preferenceGroups.isEmpty()) {
                SettingsSkeleton(
                    gutterStart = safePadding.start + gutter,
                    gutterEnd = safePadding.end + gutter,
                    maxWidth = 640.dp,
                    modifier =
                        Modifier.fillMaxSize()
                            .padding(top = CinefinSpacing.Space4)
                            .background(colors.surface),
                )
            }
        }
    }
}

/**
 * 设置页顶部的账号条：头像首字 + 账号 + 服务器，右侧标出账号身份。
 *
 * 管理员身份直接写在界面上——用户由此明白抽屉里为什么会多出「服务器控制台」， 也解释了权限从哪里来，比藏在设置深处的开关更容易理解。
 *
 * W6-VIS：整条收进 Lumen 卡片（石墨底 + 1dp 发丝线 + 顶部内高光），头像用雾灰磁贴承托， 与下方分类卡共用同一套"卡片 / 磁贴"语言。
 */
@Composable
private fun SettingsAccountHeader(
    state: DrawerState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    val isAdministrator = state.isAdministrator
    val badgeColor = if (isAdministrator) colors.onSurface else colors.onSurfaceVariant

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .cinefinClickable(onClick = onClick)
                .padding(CinefinSpacing.Space4)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = CinefinSpacing.Space1),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space4),
        ) {
            Box(
                modifier =
                    Modifier.size(48.dp)
                        .clip(CinefinShapes.Md)
                        .background(colors.surfaceContainerHigh)
                        .border(1.dp, colors.outline, CinefinShapes.Md),
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
                // W42：账号卡承担「账号与服务器」组的入口语义——点卡片进入用户管理。
                Text(
                    text = stringResource(SettingsR.string.settings_account_summary),
                    style = CinefinType.BodySmall,
                    color = colors.onSurfaceFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                            color = colors.outline,
                            shape = CinefinShapes.Xs,
                        )
                        .padding(
                            horizontal = CinefinSpacing.Space2,
                            vertical = CinefinSpacing.Space1,
                        ),
            )
            Icon(
                painter = painterResource(CoreR.drawable.ic_arrow_right),
                contentDescription = null,
                tint = colors.onSurfaceFaint,
                modifier = Modifier.size(16.dp),
            )
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
            onReloadPreferences = {},
            onOpenUsers = {},
            onAction = {},
        )
    }
}
