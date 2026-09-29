package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.presentation.theme.spacings
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceAppLanguage
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceCategory
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceFileEdit
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceGroup
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceIntInput
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceLongInput
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceMultiSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSwitch
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsAction

/**
 * 一组设置项：扁平清单 + 行间发丝线。
 *
 * 整页不再用卡片包裹——设置项是一张清单，不是一堆卡片。 分组标题右侧拖一条发丝线，分隔线缩进到文字起始处， 于是"标题 / 组 / 项"三级关系只靠线与留白就说得清。
 */
@Composable
fun SettingsGroupCard(
    group: PreferenceGroup,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        group.nameStringResource?.let {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = SettingsRowHorizontalPadding,
                            end = SettingsRowHorizontalPadding,
                            bottom = MaterialTheme.spacings.small,
                        ),
            ) {
                Text(
                    text = stringResource(it),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacings.medium))
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            group.preferences.fastForEachIndexed { index, preference ->
                when (preference) {
                    is PreferenceCategory ->
                        SettingsCategoryCard(
                            preference = preference,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    is PreferenceSwitch ->
                        SettingsSwitchCard(
                            preference = preference,
                            onClick = {
                                onAction(
                                    SettingsAction.OnUpdate(
                                        preference.copy(value = !preference.value)
                                    )
                                )
                                preference.onClick(preference)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    is PreferenceSelect ->
                        SettingsSelectCard(
                            preference = preference,
                            onUpdate = { value ->
                                onAction(SettingsAction.OnUpdate(preference.copy(value = value)))
                                preference.onUpdate(value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    is PreferenceMultiSelect ->
                        SettingsMultiSelectCard(
                            preference = preference,
                            onUpdate = { value ->
                                onAction(SettingsAction.OnUpdate(preference.copy(value = value)))
                                preference.onUpdate(value)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    is PreferenceIntInput ->
                        SettingsIntInputCard(
                            preference = preference,
                            onUpdate = { value ->
                                onAction(SettingsAction.OnUpdate(preference.copy(value = value)))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    is PreferenceLongInput ->
                        SettingsLongInputCard(
                            preference = preference,
                            onUpdate = { value ->
                                onAction(SettingsAction.OnUpdate(preference.copy(value = value)))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    is PreferenceAppLanguage ->
                        SettingsAppLanguageCard(
                            preference = preference,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    is PreferenceFileEdit ->
                        SettingsFileEditCard(
                            preference = preference,
                            modifier = Modifier.fillMaxWidth(),
                        )
                }
                if (index < group.preferences.lastIndex) {
                    HorizontalDivider(
                        modifier =
                            Modifier.padding(
                                start =
                                    if (preference.iconDrawableId != null) {
                                        SettingsRowHorizontalPadding +
                                            SettingsIconTileSize +
                                            MaterialTheme.spacings.medium
                                    } else {
                                        SettingsRowHorizontalPadding
                                    }
                            ),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun SettingsGroupCardPreview() {
    CinefinTheme {
        Column(modifier = Modifier.padding(20.dp)) {
            SettingsGroupCard(
                group =
                    PreferenceGroup(
                        nameStringResource = SettingsR.string.settings_category_player,
                        preferences =
                            listOf(
                                PreferenceSwitch(
                                    nameStringResource = SettingsR.string.mpv_player,
                                    descriptionStringRes = SettingsR.string.mpv_player_summary,
                                    backendPreference = Preference("", false),
                                ),
                                PreferenceSelect(
                                    nameStringResource = SettingsR.string.pref_player_mpv_hwdec,
                                    dependencies = listOf(Preference("", false)),
                                    backendPreference = Preference("", ""),
                                    options = SettingsR.array.mpv_hwdec,
                                    optionValues = SettingsR.array.mpv_hwdec,
                                ),
                            ),
                    ),
                onAction = {},
            )
        }
    }
}
