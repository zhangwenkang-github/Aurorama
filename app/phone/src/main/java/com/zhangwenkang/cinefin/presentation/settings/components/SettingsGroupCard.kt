package com.zhangwenkang.cinefin.presentation.settings.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinType
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.presentation.film.components.LumenCardFrame
import com.zhangwenkang.cinefin.presentation.theme.CinefinTheme
import com.zhangwenkang.cinefin.settings.R as SettingsR
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceAppLanguage
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceCategory
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceDynamicSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceFileEdit
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceGroup
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceHomeLibrary
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceHomeLibraryOrder
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceIntInput
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceIntSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceLongInput
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceMultiSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSwitch
import com.zhangwenkang.cinefin.settings.presentation.settings.SettingsAction

/**
 * 一组设置项：扁平清单 + 行间发丝线。
 *
 * 整页不再用卡片包裹——设置项是一张清单，不是一堆卡片。 分组标题右侧拖一条发丝线，分隔线缩进到文字起始处， 于是"标题 / 组 / 项"三级关系只靠线与留白就说得清。
 *
 * W6-VIS 升级：分类整体收进一张**石墨 / 雾灰双层卡**（1dp 渐变描边 + 顶部 1px 内高光 + 极轻外发光， 复用 Lumen 卡片原语），组内行间仍是发丝线——一屏里"卡片
 * / 行 / 磁贴"三种容器各有一级亮度， 排版不再是一张平铺的长表。
 */
@Composable
fun SettingsGroupCard(
    group: PreferenceGroup,
    onAction: (SettingsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCinefinColors.current
    Column(modifier = modifier) {
        group.nameStringResource?.let {
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier =
                    Modifier.fillMaxWidth()
                        .padding(
                            start = SettingsRowHorizontalPadding,
                            end = SettingsRowHorizontalPadding,
                            bottom = CinefinSpacing.Space2,
                        ),
            ) {
                Text(
                    text = stringResource(it),
                    // W42：分组标题 = 小号次级灰（设计系统 §8.5 的分组头口径）。
                    style = CinefinType.LabelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(CinefinSpacing.Space4))
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = colors.outlineVariant,
                )
            }
        }
        LumenCardFrame(shape = CinefinShapes.Lg, modifier = Modifier.fillMaxWidth()) {
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
                                    val toggled = preference.copy(value = !preference.value)
                                    onAction(SettingsAction.OnUpdate(toggled))
                                    // 回调拿到**切换后**的值：桌面歌词据此判断「刚打开且没有悬浮窗权限」。
                                    preference.onClick(toggled)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        is PreferenceSelect ->
                            SettingsSelectCard(
                                preference = preference,
                                onUpdate = { value ->
                                    onAction(
                                        SettingsAction.OnUpdate(preference.copy(value = value))
                                    )
                                    preference.onUpdate(value)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        is PreferenceIntSelect ->
                            SettingsIntSelectCard(
                                preference = preference,
                                onUpdate = { value ->
                                    onAction(
                                        SettingsAction.OnUpdate(preference.copy(value = value))
                                    )
                                    preference.onUpdate(value)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        is PreferenceDynamicSelect ->
                            SettingsDynamicSelectCard(
                                preference = preference,
                                onUpdate = { value ->
                                    onAction(
                                        SettingsAction.OnUpdate(preference.copy(value = value))
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        is PreferenceHomeLibrary ->
                            SettingsHomeLibraryCard(
                                preference = preference,
                                onUpdate = { updated ->
                                    onAction(SettingsAction.OnUpdate(updated))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        is PreferenceHomeLibraryOrder ->
                            SettingsHomeLibraryOrderCard(
                                preference = preference,
                                onUpdate = { updated ->
                                    onAction(SettingsAction.OnUpdate(updated))
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        is PreferenceMultiSelect ->
                            SettingsMultiSelectCard(
                                preference = preference,
                                onUpdate = { value ->
                                    onAction(
                                        SettingsAction.OnUpdate(preference.copy(value = value))
                                    )
                                    preference.onUpdate(value)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        is PreferenceIntInput ->
                            SettingsIntInputCard(
                                preference = preference,
                                onUpdate = { value ->
                                    onAction(
                                        SettingsAction.OnUpdate(preference.copy(value = value))
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        is PreferenceLongInput ->
                            SettingsLongInputCard(
                                preference = preference,
                                onUpdate = { value ->
                                    onAction(
                                        SettingsAction.OnUpdate(preference.copy(value = value))
                                    )
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
                                                CinefinSpacing.Space4
                                        } else {
                                            SettingsRowHorizontalPadding
                                        }
                                ),
                            color = colors.outlineVariant,
                        )
                    }
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
