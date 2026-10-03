package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.film.R as FilmR
import com.zhangwenkang.cinefin.presentation.components.BaseDialog
import com.zhangwenkang.cinefin.presentation.components.TopBarAction
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.HomeLibrarySettings
import com.zhangwenkang.cinefin.settings.presentation.models.HomeLibraryOrderEntry
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceHomeLibrary
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceHomeLibraryOrder

/**
 * 首页逐库设置行（W54-D）：库名 +「在首页显示」开关 +「默认分页」。
 *
 * 两行同属一张行卡：上行整行点按切换显示开关（与其它设置开关行为一致），下行点开默认分页对话框。 默认分页选项与 W54-B 库页 tabs
 * 同源（`HomeLibrarySettings.pageKeys`），本波只落盘。
 */
@Composable
fun SettingsHomeLibraryCard(
    preference: PreferenceHomeLibrary,
    onUpdate: (PreferenceHomeLibrary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    var showPageDialog by remember { mutableStateOf(false) }
    val pageOptions =
        HomeLibrarySettings.pageKeys(preference.libraryType).map { key ->
            key to homeLibraryPageLabel(key)
        }

    Column(modifier = modifier) {
        SettingsBaseCard(
            preference = preference,
            onClick = { onUpdate(preference.copy(visible = !preference.visible)) },
        ) {
            SettingsRow(
                title = preference.libraryName,
                description = stringResource(preference.nameStringResource),
                trailing = {
                    Box(
                        modifier = Modifier.width(SettingsTrailingWidth),
                        contentAlignment = Alignment.Center,
                    ) {
                        CinefinSwitch(
                            checked = preference.enabled && preference.visible,
                            onCheckedChange = {
                                onUpdate(preference.copy(visible = !preference.visible))
                            },
                            enabled = preference.enabled,
                            modifier = Modifier.scale(SettingsSwitchVisualScale),
                        )
                    }
                },
            )
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = SettingsRowHorizontalPadding),
            color = colors.outlineVariant,
        )
        SettingsBaseCard(
            preference = preference,
            onClick = { showPageDialog = true },
        ) {
            SettingsRow(
                title = stringResource(SettingsR.string.settings_home_library_default_page),
                value = homeLibraryPageLabel(preference.pageKey),
                showChevron = true,
            )
        }
    }

    if (showPageDialog) {
        SettingsOptionsDialog(
            titleRes = SettingsR.string.settings_home_library_default_page,
            options = pageOptions,
            selectedValue = preference.pageKey,
            onUpdate = { value ->
                showPageDialog = false
                onUpdate(preference.copy(pageKey = value ?: HomeLibrarySettings.DEFAULT_PAGE_KEY))
            },
            onDismissRequest = { showPageDialog = false },
        )
    }
}

/** 默认分页 key → 文案（与 W54-B 库页 tabs 同一套中文；「库内容」= 库名 tab）。 */
@Composable
private fun homeLibraryPageLabel(key: String): String =
    stringResource(
        when (key) {
            HomeLibrarySettings.PAGE_SUGGESTIONS -> FilmR.string.library_tab_suggestions
            HomeLibrarySettings.PAGE_UPCOMING -> FilmR.string.library_tab_upcoming
            HomeLibrarySettings.PAGE_GENRES -> FilmR.string.library_tab_genres
            HomeLibrarySettings.PAGE_STUDIOS -> FilmR.string.library_tab_studios
            HomeLibrarySettings.PAGE_EPISODES -> FilmR.string.library_tab_episodes
            else -> SettingsR.string.settings_home_page_library
        }
    )

/**
 * 媒体库顺序行（W54-D）：点开对话框，用上下箭头调整。
 *
 * 顺序作用于首页「最新 · <库名>」走廊与「最近添加」分区；未在顺序表里的服务器新库自动排到末尾。
 */
@Composable
fun SettingsHomeLibraryOrderCard(
    preference: PreferenceHomeLibraryOrder,
    onUpdate: (PreferenceHomeLibraryOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showDialog by remember { mutableStateOf(false) }
    val movable = preference.libraries.size > 1

    SettingsBaseCard(
        preference = preference,
        onClick = { if (movable) showDialog = true },
        modifier = modifier,
    ) {
        SettingsRow(
            title = stringResource(preference.nameStringResource),
            description = preference.descriptionStringRes?.let { stringResource(it) },
            showChevron = movable,
        )
    }

    if (showDialog) {
        BaseDialog(
            title = stringResource(preference.nameStringResource),
            onDismiss = { showDialog = false },
        ) { contentPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                contentPadding = PaddingValues(bottom = CinefinSpacing.Space4),
            ) {
                itemsIndexed(
                    items = preference.libraries,
                    key = { _, entry -> entry.id },
                ) { index, entry ->
                    HomeLibraryOrderRow(
                        entry = entry,
                        canMoveUp = index > 0,
                        canMoveDown = index < preference.libraries.lastIndex,
                        onMove = { delta ->
                            onUpdate(
                                preference.copy(
                                    libraries = preference.libraries.move(entry.id, delta)
                                )
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeLibraryOrderRow(
    entry: HomeLibraryOrderEntry,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (delta: Int) -> Unit,
) {
    val colors = LocalCinefinColors.current
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(horizontal = CinefinSpacing.Space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.name,
            style = CinefinType.TitleSmall,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (canMoveUp) {
            TopBarAction(
                icon = CoreR.drawable.ic_chevron_up,
                onClick = { onMove(-1) },
                contentDescription = stringResource(SettingsR.string.settings_home_library_move_up),
            )
        } else {
            Spacer(modifier = Modifier.size(44.dp))
        }
        if (canMoveDown) {
            TopBarAction(
                icon = CoreR.drawable.ic_chevron_down,
                onClick = { onMove(1) },
                contentDescription =
                    stringResource(SettingsR.string.settings_home_library_move_down),
            )
        } else {
            Spacer(modifier = Modifier.size(44.dp))
        }
    }
}

private fun List<HomeLibraryOrderEntry>.move(
    libraryId: String,
    delta: Int,
): List<HomeLibraryOrderEntry> {
    val byId = associateBy { it.id }
    return HomeLibrarySettings.moveLibrary(map { it.id }, libraryId, delta).mapNotNull { byId[it] }
}
