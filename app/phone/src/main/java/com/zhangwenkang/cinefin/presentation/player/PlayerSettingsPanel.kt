package com.zhangwenkang.cinefin.presentation.player

import androidx.annotation.StringRes
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.zhangwenkang.cinefin.core.presentation.components.CinefinSwitch
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinShapes
import com.zhangwenkang.cinefin.core.presentation.theme.CinefinSpacing
import com.zhangwenkang.cinefin.core.presentation.theme.LocalCinefinColors
import com.zhangwenkang.cinefin.core.presentation.theme.LocalMediaColors
import com.zhangwenkang.cinefin.player.local.R as PlayerR
import com.zhangwenkang.cinefin.player.local.domain.PlayerDecodeMode
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.player.local.domain.PlayerVideoTransform
import com.zhangwenkang.cinefin.player.local.domain.VideoMirrorMode
import com.zhangwenkang.cinefin.player.local.presentation.PlayerViewModel
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.PlayerStreamingQuality
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
    /** 跳过提示条显示时长（秒，W20：以前只存在于全局设置页，播放页面板也补上阈值） */
    val segmentsSkipButtonDuration: Long,
    val segmentsAutoSkip: Boolean,
    val chapterMarkers: Boolean,
    /** W27 播放结束行为：自动下一集 */
    val autoNextEpisode: Boolean,
    /** W27 播放结束行为：队列播完停在结束帧 */
    val stayAtEndOfFrame: Boolean,
    val backend: String,
    val mpvHwdec: String,
    val subtitleMode: String,
    val rememberTrackSelection: Boolean,
    val audioLanguagePreset: String,
    /** W27：字幕语言优先预设（与音轨同一套交互，写 `pref_subtitle_languages`） */
    val subtitleLanguagePreset: String,
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
    /** 码率档位（W12 反馈 B）：0 / -1 / 具体 Mbps，见 [PlayerStreamingQuality] */
    val streamingBitrate: Long,
    /** 解码策略：hardware / software（W12 反馈 B） */
    val decodeMode: String,

    /** 解码回退档位（W16）：0 = 本地硬解；1 = 服务器转码；2 = 本地软解 */
    val decodeFallbackStage: Int,

    /** 失败自动回退开关（W19）：关 = 强制所选内核，失败只提示错误 */
    val autoFallback: Boolean,
)

/** 语言优先级预设（W12：并入「音轨」面板）：写进既有 `pref_audio_languages`（逗号分隔的全量列表） */
internal val AudioLanguagePresets =
    listOf(
        "zh-Hans,zh-Hant,zh,en" to PlayerR.string.player_settings_language_zh,
        "ja,zh-Hans,zh-Hant,zh,en" to PlayerR.string.player_settings_language_ja,
        "en,zh-Hans,zh-Hant,zh" to PlayerR.string.player_settings_language_en,
    )

/**
 * W20（§6.1）：跳过提示条显示时长的可选项（秒）。
 *
 * 与全局设置页的数值输入共用同一个偏好键；这里给的是最常用的四档， 需要任意秒数仍可去「设置 → 播放」输入。
 */
internal val SegmentSkipDurations = listOf(3L, 5L, 8L, 10L)

/**
 * 设置面板的读写器：值从 `SharedPreferences` 现读现写，面板 UI 只持有一份快照。
 *
 * 「改完立即回读」保证 UI 与偏好不会脱节（例如同一项在全局设置页也被改过）。
 */
class PlayerSettingsController(
    private val appPreferences: AppPreferences,
    /**
     * W19：解码面板「播放内核」选中态的数据源——优先实际生效的内核（PlayerHolder 实例）， 而不是偏好快照；实例与偏好短暂不一致时（如回退链刚切内核）面板也不会显示错内核。
     */
    private val effectiveBackend: () -> String = {
        appPreferences.getValue(appPreferences.playerBackend)
    },
) {
    var state by mutableStateOf(read())
        private set

    fun refresh() {
        state = read()
    }

    fun setBackgroundAudio(value: Boolean) = write(appPreferences.playerBackgroundAudio, value)

    fun setSegmentsSkipButton(value: Boolean) =
        write(appPreferences.playerMediaSegmentsSkipButton, value)

    fun setSegmentsSkipButtonDuration(value: Long) =
        write(appPreferences.playerMediaSegmentsSkipButtonDuration, value)

    fun setSegmentsAutoSkip(value: Boolean) =
        write(appPreferences.playerMediaSegmentsAutoSkip, value)

    fun setChapterMarkers(value: Boolean) = write(appPreferences.playerChapterMarkers, value)

    /** W27：自动下一集（旧「播完暂停」的反向语义，写新键） */
    fun setAutoNextEpisode(value: Boolean) = write(PlayerExtraPreferences.autoNextEpisode, value)

    /** W27：队列播完停在结束帧（不退出播放页） */
    fun setStayAtEndOfFrame(value: Boolean) = write(PlayerExtraPreferences.stayAtEndOfFrame, value)

    fun setMpvHwdec(value: String) = write(appPreferences.playerMpvHwdec, value)

    fun setSubtitleMode(value: String) = write(appPreferences.subtitleMode, value)

    fun setRememberTrackSelection(value: Boolean) =
        write(appPreferences.rememberTrackSelection, value)

    fun setAudioLanguage(priorityList: String) =
        write(appPreferences.preferredAudioLanguages, priorityList)

    /** W27：字幕语言优先预设，与音轨共用同一组预设（写 `pref_subtitle_languages`） */
    fun setSubtitleLanguage(priorityList: String) =
        write(appPreferences.preferredSubtitleLanguages, priorityList)

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

    /** 码率档位：写偏好后由 Activity 重启播放页重新拉取播放信息（服务器转码生效） */
    fun setStreamingBitrate(value: Long) = write(appPreferences.playerStreamingBitrate, value)

    /** 解码策略：hardware / software（mpv 的硬解开关同步由面板写 playerMpvHwdec） */
    fun setDecodeMode(value: String) = write(appPreferences.playerDecodeMode, value)

    /** 失败自动回退开关（W19）：关 = 强制所选内核，失败只提示错误 */
    fun setAutoFallback(value: Boolean) = write(appPreferences.playerAutoFallback, value)

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
            segmentsSkipButtonDuration =
                appPreferences.getValue(appPreferences.playerMediaSegmentsSkipButtonDuration),
            segmentsAutoSkip = appPreferences.getValue(appPreferences.playerMediaSegmentsAutoSkip),
            chapterMarkers = appPreferences.getValue(appPreferences.playerChapterMarkers),
            autoNextEpisode = appPreferences.getValue(PlayerExtraPreferences.autoNextEpisode),
            stayAtEndOfFrame = appPreferences.getValue(PlayerExtraPreferences.stayAtEndOfFrame),
            backend = effectiveBackend(),
            mpvHwdec = appPreferences.getValue(appPreferences.playerMpvHwdec),
            subtitleMode = appPreferences.getValue(appPreferences.subtitleMode),
            rememberTrackSelection = appPreferences.getValue(appPreferences.rememberTrackSelection),
            audioLanguagePreset = appPreferences.getValue(appPreferences.preferredAudioLanguages),
            subtitleLanguagePreset =
                appPreferences.getValue(appPreferences.preferredSubtitleLanguages),
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
            streamingBitrate = appPreferences.getValue(appPreferences.playerStreamingBitrate),
            decodeMode = appPreferences.getValue(appPreferences.playerDecodeMode),
            decodeFallbackStage = appPreferences.getValue(appPreferences.playerDecodeFallbackStage),
            autoFallback = appPreferences.getValue(appPreferences.playerAutoFallback),
        )
}

/**
 * 设置面板的两个分类（W12 反馈 B）：**播放 / 手势**。
 *
 * 解码、字幕、音频、画面全部移出设置面板：解码有独立入口（进度条下 6 键之一）、字幕与音频并入各自面板、 画面只保留右上角入口——设置面板里不再出现重复入口，也不再出现「播放」大按钮。
 */
private enum class PlayerSettingsTab(@StringRes val labelRes: Int) {
    Playback(PlayerR.string.player_settings_tab_playback),
    Gesture(PlayerR.string.player_settings_tab_gesture),
}

/**
 * 播放页设置面板（W12 反馈 B）：播放 / 手势两个分类，Tab 切换，页内直接改、即时生效。
 *
 * 「播放」分类直接列播放相关设置项；「手势」分类放全部手势设置。 需要跨组件动作的项（切内核、换画面比例、字幕模式重选）在各自面板里，通过回调交给宿主。
 */
@Composable
internal fun PlayerSettingsPanel(
    controller: PlayerSettingsController,
    onOpenPanel: (PlayerPanel) -> Unit,
) {
    var tab by remember { mutableStateOf(PlayerSettingsTab.Playback) }
    val settings = controller.state
    Column(modifier = Modifier.fillMaxSize()) {
        SettingsGroupRow(
            labels = PlayerSettingsTab.entries.map { it.labelRes },
            selectedIndex = tab.ordinal,
            onSelect = { index -> tab = PlayerSettingsTab.entries[index] },
        )
        PanelList {
            when (tab) {
                PlayerSettingsTab.Playback -> {
                    /*
                     * W17（用户确认）：W13 加的「码率 / 解码」两行兜底入口**完全移除**，不留兜底入口。
                     * 两个功能的唯一入口回到进度条下的工具键（窄屏只留图标，仍然可点）。
                     */
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
                    /*
                     * W20（§6.1 阈值补齐）：提示条显示时长的页内入口。
                     * 与全局设置页共用同一个偏好键（秒）；改完下一秒的片段轮询即生效。
                     */
                    if (settings.segmentsSkipButton) {
                        PanelTitle(
                            stringResource(PlayerR.string.player_settings_segments_button_duration)
                        )
                        PanelChipRow(
                            options =
                                SegmentSkipDurations.map { seconds ->
                                    stringResource(
                                        PlayerR.string.player_settings_segments_seconds,
                                        seconds,
                                    ) to (settings.segmentsSkipButtonDuration == seconds)
                                },
                            onSelect = { index ->
                                controller.setSegmentsSkipButtonDuration(
                                    SegmentSkipDurations[index]
                                )
                            },
                        )
                    }
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_chapter_markers),
                        checked = settings.chapterMarkers,
                        onCheckedChange = { controller.setChapterMarkers(it) },
                    )
                    /*
                     * W27 播放结束行为：旧的「播完暂停」拆成两个更直白的开关
                     * （自动下一集 / 停在结束帧）；旧键在 ViewModel 初始化时迁到新键。
                     */
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_auto_next),
                        caption = stringResource(PlayerR.string.player_settings_auto_next_caption),
                        checked = settings.autoNextEpisode,
                        onCheckedChange = { controller.setAutoNextEpisode(it) },
                    )
                    PanelSwitchRow(
                        label = stringResource(PlayerR.string.player_settings_stay_at_end),
                        caption =
                            stringResource(PlayerR.string.player_settings_stay_at_end_caption),
                        checked = settings.stayAtEndOfFrame,
                        onCheckedChange = { controller.setStayAtEndOfFrame(it) },
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

                PlayerSettingsTab.Gesture -> {
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

/** 分类选择行（W12：播放 / 手势两个 Tab，横向可滚，样式与队列的季节页签一致） */
@Composable
private fun SettingsGroupRow(
    labels: List<Int>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
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
        labels.forEachIndexed { index, labelRes ->
            val isSelected = index == selectedIndex
            Text(
                text = stringResource(labelRes),
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
                        .clickable { onSelect(index) }
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
        // W44：统一走 core `CinefinSwitch`（关闭态拇指提亮 + 轨道描边），皮肤随 Prism / Lumen 自动切换。
        CinefinSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
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

/**
 * 解码面板（W12 反馈 B）：**内核切换（ExoPlayer / mpv）+ 硬解 / 软解策略**。
 *
 * W17（用户反馈②）：删除「优先级提示」文字；内核名只留 `ExoPlayer` / `mpv`（不带括号说明）。 面板保留「当前档位」一行，作为回退链实际落点的可视化证据。
 * 切内核与切策略都由宿主走「从当前位置重启播放」的既有路径，保证两个内核都用新参数重新创建实例。
 */
@Composable
internal fun PlayerDecodePanel(
    controller: PlayerSettingsController,
    onSelectBackend: (String) -> Unit,
    onSelectDecodeMode: (String) -> Unit,
    onAutoFallbackChange: (Boolean) -> Unit,
) {
    // 回退档位可能在播放过程中被 ViewModel 推进（不是通过本控制器写的）：每次打开面板回读一次
    LaunchedEffect(Unit) { controller.refresh() }
    val settings = controller.state
    val colors = LocalCinefinColors.current
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            PanelTitle(stringResource(PlayerR.string.player_controls_decode_kernel))
            PanelRow(
                label = stringResource(PlayerR.string.player_settings_backend_exoplayer),
                caption = stringResource(PlayerR.string.player_controls_decode_kernel_caption),
                selected = settings.backend == PlayerViewModel.PLAYER_BACKEND_EXOPLAYER,
                // W19：偏好由 Activity 的 switchBackendAndRestart 写（必须先读续播位置、再写偏好，
                // 否则 PlayerHolder 会立刻按新偏好重建空实例，位置读成 0、当前条目也取不到）
                onClick = { onSelectBackend(PlayerViewModel.PLAYER_BACKEND_EXOPLAYER) },
            )
            PanelRow(
                label = stringResource(PlayerR.string.player_settings_backend_mpv),
                caption = stringResource(PlayerR.string.player_settings_backend_caption),
                selected = settings.backend == PlayerViewModel.PLAYER_BACKEND_MPV,
                onClick = { onSelectBackend(PlayerViewModel.PLAYER_BACKEND_MPV) },
            )
            PanelTitle(stringResource(PlayerR.string.player_controls_decode_strategy))
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_decode_hardware),
                caption = stringResource(PlayerR.string.player_controls_decode_hardware_caption),
                selected = settings.decodeMode == PlayerViewModel.DECODE_MODE_HARDWARE,
                onClick = {
                    controller.setDecodeMode(PlayerViewModel.DECODE_MODE_HARDWARE)
                    onSelectDecodeMode(PlayerViewModel.DECODE_MODE_HARDWARE)
                },
            )
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_decode_software),
                caption = stringResource(PlayerR.string.player_controls_decode_software_caption),
                selected = settings.decodeMode == PlayerViewModel.DECODE_MODE_SOFTWARE,
                onClick = {
                    controller.setDecodeMode(PlayerViewModel.DECODE_MODE_SOFTWARE)
                    onSelectDecodeMode(PlayerViewModel.DECODE_MODE_SOFTWARE)
                },
            )
            PanelTitle(stringResource(PlayerR.string.player_controls_decode_fallback))
            PanelSwitchRow(
                label = stringResource(PlayerR.string.player_controls_decode_auto_fallback),
                caption =
                    stringResource(PlayerR.string.player_controls_decode_auto_fallback_caption),
                checked = settings.autoFallback,
                onCheckedChange = { enabled ->
                    controller.setAutoFallback(enabled)
                    onAutoFallbackChange(enabled)
                },
            )
            Text(
                text =
                    stringResource(
                        PlayerR.string.player_controls_decode_stage_active,
                        stringResource(
                            // W18：档位文案带内核（ExoPlayer 硬解 / mpv 硬解 / 服务器转码 / mpv 软解），
                            // 避免「本地硬解」被误读成 mpv 硬解
                            when (
                                PlayerDecodeMode.decodeStage(
                                    backend = settings.backend,
                                    mode = settings.decodeMode,
                                    fallbackStage = settings.decodeFallbackStage,
                                )
                            ) {
                                PlayerDecodeMode.DecodeStage.EXO_HARDWARE ->
                                    PlayerR.string.player_controls_decode_stage_exo_hardware
                                PlayerDecodeMode.DecodeStage.EXO_SOFTWARE ->
                                    PlayerR.string.player_controls_decode_stage_exo_software
                                PlayerDecodeMode.DecodeStage.MPV_HARDWARE ->
                                    PlayerR.string.player_controls_decode_stage_mpv_hardware
                                PlayerDecodeMode.DecodeStage.MPV_SOFTWARE ->
                                    PlayerR.string.player_controls_decode_stage_mpv_software
                                PlayerDecodeMode.DecodeStage.SERVER_TRANSCODE ->
                                    PlayerR.string.player_controls_decode_stage_server
                            }
                        ),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = CinefinSpacing.Space5,
                        vertical = CinefinSpacing.Space1,
                    ),
            )
        }
    }
}

/**
 * 码率面板（W12 反馈 B）：**自动 / 原始画质 / 具体 Mbps**（对齐 Jellyfin 官方客户端的质量档位）。
 *
 * 选具体码率 = 请求服务器转码并播放返回的 `transcodingPath`；原始画质 = 只直连不转码。 选择后由宿主从当前位置重启播放（重新拉
 * PlaybackInfo），档位对下一次起播即时生效。
 */
@Composable
internal fun PlayerBitratePanel(
    controller: PlayerSettingsController,
    onSelectBitrate: (Long) -> Unit,
) {
    val current = controller.state.streamingBitrate
    Column(modifier = Modifier.fillMaxSize()) {
        PanelList {
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_bitrate_auto),
                caption = stringResource(PlayerR.string.player_controls_bitrate_auto_caption),
                selected = current == PlayerStreamingQuality.AUTO,
                onClick = {
                    controller.setStreamingBitrate(PlayerStreamingQuality.AUTO)
                    onSelectBitrate(PlayerStreamingQuality.AUTO)
                },
            )
            PanelRow(
                label = stringResource(PlayerR.string.player_controls_bitrate_original),
                caption = stringResource(PlayerR.string.player_controls_bitrate_original_caption),
                selected = current == PlayerStreamingQuality.ORIGINAL,
                onClick = {
                    controller.setStreamingBitrate(PlayerStreamingQuality.ORIGINAL)
                    onSelectBitrate(PlayerStreamingQuality.ORIGINAL)
                },
            )
            PanelTitle(stringResource(PlayerR.string.player_controls_bitrate_caption))
            PlayerStreamingQuality.PRESET_MBPS.forEach { mbps ->
                val value = mbps.toLong()
                PanelRow(
                    label = PlayerStreamingQuality.bitrateLabel(value),
                    selected = current == value,
                    onClick = {
                        controller.setStreamingBitrate(value)
                        onSelectBitrate(value)
                    },
                )
            }
        }
    }
}
