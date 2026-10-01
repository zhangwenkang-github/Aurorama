package com.zhangwenkang.cinefin.presentation.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.player.local.domain.PlayerVideoTransform
import com.zhangwenkang.cinefin.player.local.domain.VideoMirrorMode
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.Constants
import com.zhangwenkang.cinefin.settings.domain.models.Preference

/**
 * 播放页设置面板的状态快照（§1.9）。
 *
 * 只包含「页内可改、改完即生效」的设置项；全部来源于既有偏好键（新增的画面调整键见 [PlayerExtraPreferences]，那是本波为避免触碰阅读器会话持有的
 * `AppPreferences.kt` 单独声明的）。
 */
data class PlayerSettingsSnapshot(
    val backgroundAudio: Boolean,
    val segmentsSkipButton: Boolean,
    val segmentsAutoSkip: Boolean,
    val chapterMarkers: Boolean,
    val pauseAfterCurrentItem: Boolean,
    val backend: String,
    val mpvHwdec: String,
    val subtitleMode: String,
    val rememberTrackSelection: Boolean,
    val audioLanguagePreset: String,
    val gesturesEnabled: Boolean,
    val gesturesBrightnessVolume: Boolean,
    val gesturesZoom: Boolean,
    val gesturesSeek: Boolean,
    val gesturesSeekTrickplay: Boolean,
    val gesturesChapterSkip: Boolean,
    val gesturesBrightnessRemember: Boolean,
    val gesturesStartMaximized: Boolean,
    val gesturesSpeedMultiplier: String,
    val seekSensitivity: String,
    val verticalSensitivity: String,
)

/** 语言优先级预设：写进既有 `pref_audio_languages`（逗号分隔的全量列表） */
private val AudioLanguagePresets =
    listOf(
        "zh-Hans,zh-Hant,zh,en" to PlayerR.string.player_settings_language_zh,
        "ja,zh-Hans,zh-Hant,zh,en" to PlayerR.string.player_settings_language_ja,
        "en,zh-Hans,zh-Hant,zh" to PlayerR.string.player_settings_language_en,
    )

/**
 * 设置面板的读写器：值从 `SharedPreferences` 现读现写，面板 UI 只持有一份快照。
 *
 * 「改完立即回读」保证 UI 与偏好不会脱节（例如同一项在全局设置页也被改过）。
 */
class PlayerSettingsController(private val appPreferences: AppPreferences) {
    var state by mutableStateOf(read())
        private set

    fun refresh() {
        state = read()
    }

    fun setBackgroundAudio(value: Boolean) = write(appPreferences.playerBackgroundAudio, value)

    fun setSegmentsSkipButton(value: Boolean) =
        write(appPreferences.playerMediaSegmentsSkipButton, value)

    fun setSegmentsAutoSkip(value: Boolean) =
        write(appPreferences.playerMediaSegmentsAutoSkip, value)

    fun setChapterMarkers(value: Boolean) = write(appPreferences.playerChapterMarkers, value)

    fun setPauseAfterCurrentItem(value: Boolean) =
        write(PlayerExtraPreferences.pauseAfterCurrentItem, value)

    fun setBackend(value: String) = write(appPreferences.playerBackend, value)

    fun setMpvHwdec(value: String) = write(appPreferences.playerMpvHwdec, value)

    fun setSubtitleMode(value: String) = write(appPreferences.subtitleMode, value)

    fun setRememberTrackSelection(value: Boolean) =
        write(appPreferences.rememberTrackSelection, value)

    fun setAudioLanguage(priorityList: String) =
        write(appPreferences.preferredAudioLanguages, priorityList)

    fun setGesturesEnabled(value: Boolean) = write(appPreferences.playerGestures, value)

    fun setGesturesBrightnessVolume(value: Boolean) = write(appPreferences.playerGesturesVB, value)

    fun setGesturesZoom(value: Boolean) = write(appPreferences.playerGesturesZoom, value)

    fun setGesturesSeek(value: Boolean) = write(appPreferences.playerGesturesSeek, value)

    fun setGesturesSeekTrickplay(value: Boolean) =
        write(appPreferences.playerGesturesSeekTrickplay, value)

    fun setGesturesChapterSkip(value: Boolean) =
        write(appPreferences.playerGesturesChapterSkip, value)

    fun setGesturesBrightnessRemember(value: Boolean) =
        write(appPreferences.playerGesturesBrightnessRemember, value)

    fun setGesturesStartMaximized(value: Boolean) =
        write(appPreferences.playerGesturesStartMaximized, value)

    fun setGesturesSpeedMultiplier(value: String) =
        write(appPreferences.playerGesturesSpeedMultiplier, value)

    fun setSeekSensitivity(value: String) =
        write(appPreferences.playerGesturesSeekSensitivity, value)

    fun setVerticalSensitivity(value: String) =
        write(appPreferences.playerGesturesVerticalSensitivity, value)

    /** 画面调整：旋转 / 镜像 / 裁剪 / 去黑边，写偏好后由播放页即时应用 */
    fun setVideoTransform(transform: PlayerVideoTransform) {
        appPreferences.setValue(PlayerExtraPreferences.videoRotation, transform.rotationDegrees)
        appPreferences.setValue(PlayerExtraPreferences.videoMirror, transform.mirror)
        appPreferences.setValue(PlayerExtraPreferences.videoCropPercent, transform.cropPercent)
        appPreferences.setValue(
            PlayerExtraPreferences.videoLetterboxCrop,
            transform.letterboxCrop,
        )
        refresh()
    }

    fun readVideoTransform(): PlayerVideoTransform =
        PlayerVideoTransform(
            rotationDegrees = appPreferences.getValue(PlayerExtraPreferences.videoRotation),
            mirror = appPreferences.getValue(PlayerExtraPreferences.videoMirror),
            cropPercent = appPreferences.getValue(PlayerExtraPreferences.videoCropPercent),
            letterboxCrop = appPreferences.getValue(PlayerExtraPreferences.videoLetterboxCrop),
        )

    private inline fun <reified T> write(preference: Preference<T>, value: T) {
        appPreferences.setValue(preference, value)
        refresh()
    }

    private fun read(): PlayerSettingsSnapshot =
        PlayerSettingsSnapshot(
            backgroundAudio = appPreferences.getValue(appPreferences.playerBackgroundAudio),
            segmentsSkipButton =
                appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButton),
            segmentsAutoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip),
            chapterMarkers = appPreferences.getValue(appPreferences.playerChapterMarkers),
            pauseAfterCurrentItem =
                appPreferences.getValue(PlayerExtraPreferences.pauseAfterCurrentItem),
            backend = appPreferences.getValue(appPreferences.playerBackend),
            mpvHwdec = appPreferences.getValue(appPreferences.playerMpvHwdec),
            subtitleMode = appPreferences.getValue(appPreferences.subtitleMode),
            rememberTrackSelection = appPreferences.getValue(appPreferences.rememberTrackSelection),
            audioLanguagePreset = appPreferences.getValue(appPreferences.preferredAudioLanguages),
            gesturesEnabled = appPreferences.getValue(appPreferences.playerGestures),
            gesturesBrightnessVolume = appPreferences.getValue(appPreferences.playerGesturesVB),
            gesturesZoom = appPreferences.getValue(appPreferences.playerGesturesZoom),
            gesturesSeek = appPreferences.getValue(appPreferences.playerGesturesSeek),
            gesturesSeekTrickplay =
                appPreferences.getValue(appPreferences.playerGesturesSeekTrickplay),
            gesturesChapterSkip = appPreferences.getValue(appPreferences.playerGesturesChapterSkip),
            gesturesBrightnessRemember =
                appPreferences.getValue(appPreferences.playerGesturesBrightnessRemember),
            gesturesStartMaximized =
                appPreferences.getValue(appPreferences.playerGesturesStartMaximized),
            gesturesSpeedMultiplier =
                appPreferences.getValue(appPreferences.playerGesturesSpeedMultiplier),
            seekSensitivity = appPreferences.getValue(appPreferences.playerGesturesSeekSensitivity),
            verticalSensitivity =
                appPreferences.getValue(appPreferences.playerGesturesVerticalSensitivity),
        )
}

/** 设置面板的六个分组（§1.9） */
private enum class PlayerSettingsGroup(val labelRes: Int) {
    Playback(PlayerR.string.player_settings_group_playback),
    Decode(PlayerR.string.player_settings_group_decode),
    Subtitle(PlayerR.string.player_settings_group_subtitle),
    Audio(PlayerR.string.player_settings_group_audio),
    Picture(PlayerR.string.player_settings_group_picture),
    Gesture(PlayerR.string.player_settings_group_gesture),
}

/**
 * 播放页设置面板（§1.9）：播放 / 解码 / 字幕 / 音频 / 画面 / 手势 六组，页内直接改、即时生效。
 *
 * 需要跨组件动作的项（切内核、换画面比例、字幕模式重选、mpv 换硬解）通过回调交给宿主； 其余项写完偏好即生效（手势层与播放页都是「用时现读」）。
 */
@Composable
internal fun PlayerSettingsPanel(
    controller: PlayerSettingsController,
    videoTransform: PlayerVideoTransform,
    onOpenPanel: (PlayerPanel) -> Unit,
    onSelectBackend: (String) -> Unit,
    onSelectMpvHwdec: (String) -> Unit,
    onSubtitleModeChanged: (String) -> Unit,
    onVideoTransformChanged: (PlayerVideoTransform) -> Unit,
) {
    var group by remember { mutableStateOf(PlayerSettingsGroup.Playback) }
    val settings = controller.state
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsGroupRow(selected = group, onSelect = { group = it })
        PanelList {
            when (group) {
                PlayerSettingsGroup.Playback -> {
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_background_audio),
                        caption =
                            stringResource(PlayerR.string.player_settings_background_audio_caption),
                        checked = settings.backgroundAudio,
                        onCheckedChange = { controller.setBackgroundAudio(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_segments_button),
                        checked = settings.segmentsSkipButton,
                        onCheckedChange = { controller.setSegmentsSkipButton(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_segments_auto),
                        checked = settings.segmentsAutoSkip,
                        onCheckedChange = { controller.setSegmentsAutoSkip(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_chapter_markers),
                        checked = settings.chapterMarkers,
                        onCheckedChange = { controller.setChapterMarkers(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_pause_after_item),
                        caption =
                            stringResource(PlayerR.string.player_settings_pause_after_item_caption),
                        checked = settings.pauseAfterCurrentItem,
                        onCheckedChange = { controller.setPauseAfterCurrentItem(it) },
                    )
                    // 循环模式（顺序 / 列表 / 单集 / 随机）在「更多」去重后唯一的入口（W10 反馈⑤）
                    PanelRow(
                        label = stringResource(PlayerR.string.player_settings_repeat_entry),
                        caption =
                            stringResource(PlayerR.string.player_settings_repeat_entry_caption),
                        selected = false,
                        onClick = { onOpenPanel(PlayerPanel.Repeat) },
                    )
                }

                PlayerSettingsGroup.Decode -> {
                    PanelTitle(stringResource(PlayerR.string.player_settings_backend))
                    PanelRow(
                        label = stringResource(PlayerR.string.player_settings_backend_exoplayer),
                        caption = stringResource(PlayerR.string.player_settings_backend_caption),
                        selected = settings.backend == PlayerViewModel.PLAYER_BACKEND_EXOPLAYER,
                        onClick = {
                            controller.setBackend(PlayerViewModel.PLAYER_BACKEND_EXOPLAYER)
                            onSelectBackend(PlayerViewModel.PLAYER_BACKEND_EXOPLAYER)
                        },
                    )
                    PanelRow(
                        label = stringResource(PlayerR.string.player_settings_backend_mpv),
                        caption = stringResource(PlayerR.string.player_settings_backend_caption),
                        selected = settings.backend == PlayerViewModel.PLAYER_BACKEND_MPV,
                        onClick = {
                            controller.setBackend(PlayerViewModel.PLAYER_BACKEND_MPV)
                            onSelectBackend(PlayerViewModel.PLAYER_BACKEND_MPV)
                        },
                    )
                    PanelTitle(stringResource(PlayerR.string.player_settings_mpv_hwdec))
                    PanelRow(
                        label = stringResource(PlayerR.string.player_settings_mpv_hwdec_on),
                        caption = stringResource(PlayerR.string.player_settings_mpv_hwdec_caption),
                        selected = settings.mpvHwdec == "mediacodec",
                        enabled = settings.backend == PlayerViewModel.PLAYER_BACKEND_MPV,
                        onClick = {
                            controller.setMpvHwdec("mediacodec")
                            onSelectMpvHwdec("mediacodec")
                        },
                    )
                    PanelRow(
                        label = stringResource(PlayerR.string.player_settings_mpv_hwdec_off),
                        caption = stringResource(PlayerR.string.player_settings_mpv_hwdec_caption),
                        selected = settings.mpvHwdec == "no",
                        enabled = settings.backend == PlayerViewModel.PLAYER_BACKEND_MPV,
                        onClick = {
                            controller.setMpvHwdec("no")
                            onSelectMpvHwdec("no")
                        },
                    )
                }

                PlayerSettingsGroup.Subtitle -> {
                    PanelTitle(stringResource(PlayerR.string.player_settings_subtitle_mode))
                    val modes =
                        listOf(
                            Constants.SubtitleMode.AUTO to
                                PlayerR.string.player_settings_subtitle_auto,
                            Constants.SubtitleMode.ALWAYS to
                                PlayerR.string.player_settings_subtitle_always,
                            Constants.SubtitleMode.OFF to
                                PlayerR.string.player_settings_subtitle_off,
                        )
                    PanelChipRow(
                        options =
                            modes.map { (value, labelRes) ->
                                stringResource(labelRes) to (settings.subtitleMode == value)
                            },
                        onSelect = { index ->
                            val value = modes[index].first
                            controller.setSubtitleMode(value)
                            onSubtitleModeChanged(value)
                        },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_remember_track),
                        checked = settings.rememberTrackSelection,
                        onCheckedChange = { controller.setRememberTrackSelection(it) },
                    )
                }

                PlayerSettingsGroup.Audio -> {
                    PanelTitle(stringResource(PlayerR.string.player_settings_audio_language))
                    PanelChipRow(
                        options =
                            AudioLanguagePresets.map { (value, labelRes) ->
                                stringResource(labelRes) to (settings.audioLanguagePreset == value)
                            },
                        onSelect = { index ->
                            controller.setAudioLanguage(AudioLanguagePresets[index].first)
                        },
                    )
                    Text(
                        text =
                            stringResource(PlayerR.string.player_settings_audio_language_caption),
                        style = MaterialTheme.typography.bodySmall,
                        color = LocalCinefinColors.current.onSurfaceVariant,
                        modifier =
                            Modifier.padding(
                                horizontal = CinefinSpacing.Space5,
                                vertical = CinefinSpacing.Space1,
                            ),
                    )
                }

                PlayerSettingsGroup.Picture -> {
                    VideoTransformControls(
                        transform = videoTransform,
                        onTransformChange = { transform ->
                            controller.setVideoTransform(transform)
                            onVideoTransformChanged(transform)
                        },
                    )
                }

                PlayerSettingsGroup.Gesture -> {
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_gesture_master),
                        checked = settings.gesturesEnabled,
                        onCheckedChange = { controller.setGesturesEnabled(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_gesture_vb),
                        checked = settings.gesturesBrightnessVolume,
                        onCheckedChange = { controller.setGesturesBrightnessVolume(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_gesture_zoom),
                        checked = settings.gesturesZoom,
                        onCheckedChange = { controller.setGesturesZoom(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_gesture_seek),
                        checked = settings.gesturesSeek,
                        onCheckedChange = { controller.setGesturesSeek(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_gesture_trickplay),
                        checked = settings.gesturesSeekTrickplay,
                        onCheckedChange = { controller.setGesturesSeekTrickplay(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_gesture_chapter_skip),
                        checked = settings.gesturesChapterSkip,
                        onCheckedChange = { controller.setGesturesChapterSkip(it) },
                    )
                    PanelSwitchRow(
                        label =
                            stringResource(
                                PlayerR.string.player_settings_gesture_brightness_remember
                            ),
                        checked = settings.gesturesBrightnessRemember,
                        onCheckedChange = { controller.setGesturesBrightnessRemember(it) },
                    )
                    PanelSwitchRow(
                        label =
                            stringResource(PlayerR.string.player_settings_gesture_start_maximized),
                        checked = settings.gesturesStartMaximized,
                        onCheckedChange = { controller.setGesturesStartMaximized(it) },
                    )
                    PanelTitle(stringResource(PlayerR.string.player_settings_speed_multiplier))
                    PanelChipRow(
                        options =
                            listOf("1.5", "2.0", "3.0").map { value ->
                                "$value×" to (settings.gesturesSpeedMultiplier == value)
                            },
                        onSelect = { index ->
                            controller.setGesturesSpeedMultiplier(
                                listOf("1.5", "2.0", "3.0")[index]
                            )
                        },
                    )
                    PanelTitle(stringResource(PlayerR.string.player_settings_seek_sensitivity))
                    SensitivityChips(
                        current = settings.seekSensitivity,
                        onSelect = { controller.setSeekSensitivity(it) },
                    )
                    PanelTitle(stringResource(PlayerR.string.player_settings_vertical_sensitivity))
                    SensitivityChips(
                        current = settings.verticalSensitivity,
                        onSelect = { controller.setVerticalSensitivity(it) },
                    )
                }
            }
        }
    }
}

/** 六组的分组选择行（横向可滚，样式与队列的季节页签一致） */
@Composable
private fun SettingsGroupRow(
    selected: PlayerSettingsGroup,
    onSelect: (PlayerSettingsGroup) -> Unit,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Row(
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space1),
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2),
    ) {
        PlayerSettingsGroup.entries.forEach { group ->
            val isSelected = group == selected
            Text(
                text = stringResource(group.labelRes),
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) media.bright else colors.onSurfaceVariant,
                modifier =
                    Modifier.clip(CinefinShapes.Sm)
                        .background(if (isSelected) media.container else colors.surfaceContainer)
                        .border(
                            1.dp,
                            if (isSelected) media.outline else colors.outline,
                            CinefinShapes.Sm,
                        )
                        .clickable { onSelect(group) }
                        .semantics { this.selected = isSelected }
                        .padding(
                            horizontal = CinefinSpacing.Space4,
                            vertical = CinefinSpacing.Space2,
                        ),
            )
        }
    }
}

/** 一行开关（设置面板通用）：标签 + 说明 + Material3 开关（媒体色选中态） */
@Composable
internal fun PanelSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    caption: String? = null,
    enabled: Boolean = true,
) {
    val colors = LocalCinefinColors.current
    val media = LocalMediaColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .padding(horizontal = CinefinSpacing.Space3, vertical = 2.dp)
                .heightIn(min = 52.dp)
                .clip(CinefinShapes.Sm)
                .background(colors.surfaceContainerLow)
                // 整行可点：开关行是「一行的表单」，不该只有右侧小圆钮响应（48dp 命中区纪律）
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space2),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) colors.onSurface else colors.onSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(CinefinSpacing.Space3))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = media.onBase,
                    checkedTrackColor = media.base,
                    checkedBorderColor = media.base,
                    uncheckedThumbColor = colors.onSurfaceVariant,
                    uncheckedTrackColor = colors.surfaceContainerHighest,
                    uncheckedBorderColor = colors.outline,
                ),
        )
    }
}

/** 横向单选的 chip 行：与字幕面板的档位选择同款视觉 */
@Composable
internal fun PanelChipRow(
    options: List<Pair<String, Boolean>>,
    onSelect: (Int) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(CinefinSpacing.Space2),
        modifier =
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = CinefinSpacing.Space3, vertical = CinefinSpacing.Space1),
    ) {
        options.forEachIndexed { index, (label, isSelected) ->
            PanelChip(
                label = label,
                selected = isSelected,
                onClick = { onSelect(index) },
            )
        }
    }
}

@Composable
private fun SensitivityChips(current: String, onSelect: (String) -> Unit) {
    val values =
        listOf(
            "low" to PlayerR.string.player_settings_sensitivity_low,
            "standard" to PlayerR.string.player_settings_sensitivity_standard,
            "high" to PlayerR.string.player_settings_sensitivity_high,
        )
    PanelChipRow(
        options =
            values.map { (value, labelRes) -> stringResource(labelRes) to (current == value) },
        onSelect = { index -> onSelect(values[index].first) },
    )
}

/**
 * 画面调整控件（§1.6）：旋转 / 镜像 / 裁剪 / 去黑边。
 *
 * 设置面板的「画面」组与「画面」面板共用这一份控件，避免两处各写一套。
 */
@Composable
internal fun VideoTransformControls(
    transform: PlayerVideoTransform,
    onTransformChange: (PlayerVideoTransform) -> Unit,
) {
    PanelTitle(stringResource(PlayerR.string.player_settings_rotation))
    val rotations =
        listOf(
            0 to PlayerR.string.player_settings_rotation_0,
            90 to PlayerR.string.player_settings_rotation_90,
            180 to PlayerR.string.player_settings_rotation_180,
            270 to PlayerR.string.player_settings_rotation_270,
        )
    PanelChipRow(
        options =
            rotations.map { (value, labelRes) ->
                stringResource(labelRes) to (transform.rotationDegrees % 360 == value)
            },
        onSelect = { index ->
            onTransformChange(transform.copy(rotationDegrees = rotations[index].first))
        },
    )

    PanelTitle(stringResource(PlayerR.string.player_settings_mirror))
    val mirrors =
        listOf(
            VideoMirrorMode.OFF to PlayerR.string.player_settings_mirror_off,
            VideoMirrorMode.HORIZONTAL to PlayerR.string.player_settings_mirror_horizontal,
            VideoMirrorMode.VERTICAL to PlayerR.string.player_settings_mirror_vertical,
        )
    PanelChipRow(
        options =
            mirrors.map { (value, labelRes) ->
                stringResource(labelRes) to (transform.mirror == value)
            },
        onSelect = { index -> onTransformChange(transform.copy(mirror = mirrors[index].first)) },
    )

    PanelTitle(stringResource(PlayerR.string.player_settings_crop))
    val crops =
        listOf(
            0 to PlayerR.string.player_settings_crop_off,
            5 to PlayerR.string.player_settings_crop_5,
            10 to PlayerR.string.player_settings_crop_10,
            15 to PlayerR.string.player_settings_crop_15,
            20 to PlayerR.string.player_settings_crop_20,
        )
    PanelChipRow(
        options =
            crops.map { (value, labelRes) ->
                stringResource(labelRes) to (transform.cropPercent == value)
            },
        onSelect = { index -> onTransformChange(transform.copy(cropPercent = crops[index].first)) },
    )

    PanelSwitchRow(
        label = stringResource(PlayerR.string.player_settings_letterbox),
        caption = stringResource(PlayerR.string.player_settings_letterbox_caption),
        checked = transform.letterboxCrop,
        onCheckedChange = { onTransformChange(transform.copy(letterboxCrop = it)) },
    )
}
