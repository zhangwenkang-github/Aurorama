package com.zhangwenkang.cinefin.settings.presentation.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.settings.R
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.models.LibraryCatalog
import com.zhangwenkang.cinefin.settings.presentation.enums.DeviceType
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceAppLanguage
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceCategory
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceDynamicOption
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceDynamicSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceFileEdit
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceGroup
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceIntInput
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceIntSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceLongInput
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceMultiSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSelect
import com.zhangwenkang.cinefin.settings.presentation.models.PreferenceSwitch
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel
@Inject
constructor(
    private val appPreferences: AppPreferences,
    @param:ApplicationContext private val context: Context,
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state = _state.asStateFlow()

    private val eventsChannel = Channel<SettingsEvent>()
    val events = eventsChannel.receiveAsFlow()

    /** 服务器媒体库目录（由 App 层写入偏好缓存）：用于「首页 / 音乐 / 书架使用哪个媒体库」的选项。 */
    private val libraryCatalog =
        LibraryCatalog.decode(appPreferences.getValue(appPreferences.uiLibraryCatalog))

    private val automaticLibraryOption =
        PreferenceDynamicOption(
            value = null,
            labelStringResource = R.string.settings_library_auto,
        )

    private fun libraryOptions(filter: (String) -> Boolean): List<PreferenceDynamicOption> =
        buildList {
            add(automaticLibraryOption)
            libraryCatalog
                .filter { filter(it.type) }
                .forEach { library ->
                    add(PreferenceDynamicOption(value = library.id, label = library.name))
                }
        }

    private val homeLibraryOptions = libraryOptions { it != "music" && it != "books" }

    private val musicLibraryOptions = libraryOptions { it == "music" }

    private val bookshelfLibraryOptions = libraryOptions { it == "books" }

    /**
     * 顶层条目的原始定义（W42 起顺序不再直接决定展示顺序）。
     *
     * 设置页收敛为 5 组（用户 2026-10-03 确认）后，由 [buildTopLevelPreferenceGroups] 按 [SETTINGS_GROUP_LAYOUT]
     * 分桶拼装——这样每个条目（分类）的内部子页结构保持原样，不必搬动大段定义， 也就不会把子页的索引路径搬丢。
     */
    private val rawPreferenceGroups =
        listOf(
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_language,
                            descriptionStringRes = R.string.settings_language_summary,
                            iconDrawableId = R.drawable.ic_languages,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceAppLanguage(
                                                    nameStringResource = R.string.app_language,
                                                    iconDrawableId = R.drawable.ic_languages,
                                                    enabled =
                                                        Build.VERSION.SDK_INT >=
                                                            Build.VERSION_CODES.TIRAMISU,
                                                )
                                            )
                                    ),
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string.settings_preferred_audio_language,
                                                    iconDrawableId = R.drawable.ic_speaker,
                                                    backendPreference =
                                                        appPreferences.preferredAudioLanguage,
                                                    options = R.array.languages,
                                                    optionValues = R.array.languages_values,
                                                    optionsIncludeNull = true,
                                                    onUpdate = { value ->
                                                        // 首选语言置顶，其余沿用默认优先级
                                                        appPreferences.setValue(
                                                            appPreferences.preferredAudioLanguages,
                                                            LanguageMatcher.priorityToString(
                                                                LanguageMatcher.buildPriority(
                                                                    value,
                                                                    LanguageMatcher
                                                                        .DEFAULT_AUDIO_PRIORITY,
                                                                )
                                                            ),
                                                        )
                                                    },
                                                ),
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string
                                                            .settings_preferred_subtitle_language,
                                                    iconDrawableId = R.drawable.ic_closed_caption,
                                                    backendPreference =
                                                        appPreferences.preferredSubtitleLanguage,
                                                    options = R.array.languages,
                                                    optionValues = R.array.languages_values,
                                                    optionsIncludeNull = true,
                                                    onUpdate = { value ->
                                                        appPreferences.setValue(
                                                            appPreferences
                                                                .preferredSubtitleLanguages,
                                                            LanguageMatcher.priorityToString(
                                                                LanguageMatcher.buildPriority(
                                                                    value,
                                                                    LanguageMatcher
                                                                        .DEFAULT_SUBTITLE_PRIORITY,
                                                                )
                                                            ),
                                                        )
                                                    },
                                                ),
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string.settings_subtitle_mode,
                                                    iconDrawableId = R.drawable.ic_closed_caption,
                                                    backendPreference = appPreferences.subtitleMode,
                                                    options = R.array.subtitle_mode,
                                                    optionValues = R.array.subtitle_mode_values,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_remember_track_selection,
                                                    descriptionStringRes =
                                                        R.string
                                                            .settings_remember_track_selection_summary,
                                                    iconDrawableId = R.drawable.ic_closed_caption,
                                                    backendPreference =
                                                        appPreferences.rememberTrackSelection,
                                                ),
                                            )
                                    ),
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_libraries,
                            descriptionStringRes = R.string.settings_libraries_summary,
                            iconDrawableId = R.drawable.ic_media_library,
                            // 库选择是手机 / 平板客户端设置；TV 端设置页不渲染这两种新模型（保持冻结）。
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceDynamicSelect(
                                                    nameStringResource =
                                                        R.string.settings_home_library,
                                                    descriptionStringRes =
                                                        R.string.settings_home_library_summary,
                                                    backendPreference =
                                                        appPreferences.uiHomeLibraryId,
                                                    options = homeLibraryOptions,
                                                ),
                                                PreferenceDynamicSelect(
                                                    nameStringResource =
                                                        R.string.settings_bookshelf_library,
                                                    descriptionStringRes =
                                                        R.string.settings_bookshelf_library_summary,
                                                    backendPreference =
                                                        appPreferences.uiBookshelfLibraryId,
                                                    options = bookshelfLibraryOptions,
                                                ),
                                                // W46：音乐库选择从「播放与音乐 → 音乐」子页移到这里，与首页 /
                                                // 书架并列（「音乐」子页整体下线）。
                                                PreferenceDynamicSelect(
                                                    nameStringResource =
                                                        R.string.settings_music_library,
                                                    descriptionStringRes =
                                                        R.string.settings_music_library_summary,
                                                    backendPreference =
                                                        appPreferences.uiMusicLibraryId,
                                                    options = musicLibraryOptions,
                                                ),
                                                // W53（用户 2026-10-03 确认）：视频模式页显示方式——库卡列表（默认）/
                                                // 聚合列表；静态两选项，与「首页媒体库」等并列在「媒体库」子页。
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string.settings_video_display_mode,
                                                    descriptionStringRes =
                                                        R.string
                                                            .settings_video_display_mode_summary,
                                                    backendPreference =
                                                        appPreferences.uiVideoDisplayMode,
                                                    options = R.array.video_display_mode,
                                                    optionValues =
                                                        R.array.video_display_mode_values,
                                                ),
                                            )
                                    )
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_sidebar,
                            descriptionStringRes = R.string.settings_sidebar_summary,
                            iconDrawableId = R.drawable.ic_sidebar,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_sidebar_show_home,
                                                    backendPreference =
                                                        appPreferences.uiSidebarShowHome,
                                                ),
                                                // W53：侧栏「视频」入口（与首页 / 音乐 / 书架 / 媒体库 / 下载并列）。
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_sidebar_show_video,
                                                    backendPreference =
                                                        appPreferences.uiSidebarShowVideo,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_sidebar_show_media,
                                                    backendPreference =
                                                        appPreferences.uiSidebarShowMedia,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_sidebar_show_music,
                                                    backendPreference =
                                                        appPreferences.uiSidebarShowMusic,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_sidebar_show_bookshelf,
                                                    backendPreference =
                                                        appPreferences.uiSidebarShowBookshelf,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_sidebar_show_downloads,
                                                    backendPreference =
                                                        appPreferences.uiSidebarShowDownloads,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_sidebar_show_console,
                                                    backendPreference =
                                                        appPreferences.uiSidebarShowConsole,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_sidebar_show_metadata,
                                                    backendPreference =
                                                        appPreferences.uiSidebarShowMetadata,
                                                ),
                                            )
                                    )
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        // W42：桌面歌词收进子页——顶层只留一行「悬浮显示当前句与下一句」，
                        // 权限提示与开关本体放进子页（用户 2026-10-03 确认）。
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_desktop_lyrics,
                            descriptionStringRes = R.string.settings_music_lyrics_overlay_summary,
                            iconDrawableId = R.drawable.ic_lyrics,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                // W23-MUSIC 桌面歌词（与全屏播放界面的开关共用同一偏好键）：
                                                // 打开时若还没有「显示在其他应用上层」权限，带包名引导到该权限页。
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_music_lyrics_overlay,
                                                    descriptionStringRes =
                                                        R.string
                                                            .settings_music_lyrics_overlay_permission,
                                                    iconDrawableId = R.drawable.ic_lyrics,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.musicLyricsOverlay,
                                                    onClick = { preference ->
                                                        if (
                                                            preference.value &&
                                                                !Settings.canDrawOverlays(context)
                                                        ) {
                                                            viewModelScope.launch {
                                                                eventsChannel.send(
                                                                    SettingsEvent.LaunchIntent(
                                                                        overlayPermissionIntent()
                                                                    )
                                                                )
                                                            }
                                                        }
                                                    },
                                                )
                                            )
                                    )
                                ),
                        ),
                        // W25-MUSIC 队列恢复开关（W21 遗留）：关闭后不落盘恢复快照、重启不恢复上次队列。
                        PreferenceSwitch(
                            nameStringResource = R.string.settings_music_resume_queue,
                            descriptionStringRes = R.string.settings_music_resume_queue_summary,
                            iconDrawableId = R.drawable.ic_play,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            backendPreference = appPreferences.musicResumeQueue,
                        ),
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_interface,
                            descriptionStringRes = R.string.settings_interface_summary,
                            iconDrawableId = R.drawable.ic_layout_dashboard,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        nameStringResource = R.string.home,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.home_suggestions,
                                                    backendPreference =
                                                        appPreferences.homeSuggestions,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.home_continue_watching,
                                                    backendPreference =
                                                        appPreferences.homeContinueWatching,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.home_next_up,
                                                    backendPreference = appPreferences.homeNextUp,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.home_latest,
                                                    backendPreference = appPreferences.homeLatest,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.extra_info,
                                                    descriptionStringRes =
                                                        R.string.extra_info_summary,
                                                    backendPreference =
                                                        appPreferences.displayExtraInfo,
                                                )
                                            )
                                    ),
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_player,
                            descriptionStringRes = R.string.settings_player_summary,
                            iconDrawableId = R.drawable.ic_play,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceCategory(
                                                    nameStringResource = R.string.subtitles,
                                                    descriptionStringRes =
                                                        R.string.subtitles_summary,
                                                    iconDrawableId = R.drawable.ic_closed_caption,
                                                    onClick = {
                                                        viewModelScope.launch {
                                                            eventsChannel.send(
                                                                SettingsEvent.LaunchIntent(
                                                                    Intent(
                                                                        Settings
                                                                            .ACTION_CAPTIONING_SETTINGS
                                                                    )
                                                                )
                                                            )
                                                        }
                                                    },
                                                )
                                            )
                                    ),
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string.pref_player_backend,
                                                    backendPreference =
                                                        appPreferences.playerBackend,
                                                    options = R.array.player_backends,
                                                    optionValues = R.array.player_backends,
                                                ),
                                                PreferenceCategory(
                                                    nameStringResource = R.string.mpv_options,
                                                    onClick = {
                                                        viewModelScope.launch {
                                                            eventsChannel.send(
                                                                SettingsEvent.NavigateToSettings(
                                                                    intArrayOf(
                                                                        R.string
                                                                            .settings_category_player,
                                                                        it.nameStringResource,
                                                                    )
                                                                )
                                                            )
                                                        }
                                                    },
                                                    nestedPreferenceGroups =
                                                        listOf(
                                                            PreferenceGroup(
                                                                preferences =
                                                                    listOf(
                                                                        PreferenceSelect(
                                                                            nameStringResource =
                                                                                R.string
                                                                                    .pref_player_mpv_hwdec,
                                                                            backendPreference =
                                                                                appPreferences
                                                                                    .playerMpvHwdec,
                                                                            options =
                                                                                R.array.mpv_hwdec,
                                                                            optionValues =
                                                                                R.array.mpv_hwdec,
                                                                        ),
                                                                        PreferenceSelect(
                                                                            nameStringResource =
                                                                                R.string
                                                                                    .pref_player_mpv_vo,
                                                                            backendPreference =
                                                                                appPreferences
                                                                                    .playerMpvVo,
                                                                            options =
                                                                                R.array.mpv_vos,
                                                                            optionValues =
                                                                                R.array.mpv_vos,
                                                                        ),
                                                                        PreferenceSelect(
                                                                            nameStringResource =
                                                                                R.string
                                                                                    .pref_player_mpv_ao,
                                                                            backendPreference =
                                                                                appPreferences
                                                                                    .playerMpvAo,
                                                                            options =
                                                                                R.array.mpv_aos,
                                                                            optionValues =
                                                                                R.array.mpv_aos,
                                                                        ),
                                                                    )
                                                            ),
                                                            PreferenceGroup(
                                                                nameStringResource =
                                                                    R.string.advanced,
                                                                preferences =
                                                                    listOf(
                                                                        PreferenceFileEdit(
                                                                            nameStringResource =
                                                                                R.string
                                                                                    .edit_file_title,
                                                                            filePath =
                                                                                "mpv/mpv.conf",
                                                                            onClick = {
                                                                                viewModelScope
                                                                                    .launch {
                                                                                        eventsChannel
                                                                                            .send(
                                                                                                SettingsEvent
                                                                                                    .NavigateToSettingsFileEdit(
                                                                                                        it
                                                                                                            .filePath
                                                                                                    )
                                                                                            )
                                                                                    }
                                                                            },
                                                                        ),
                                                                        PreferenceFileEdit(
                                                                            nameStringResource =
                                                                                R.string
                                                                                    .edit_file_title,
                                                                            filePath =
                                                                                "mpv/input.conf",
                                                                            onClick = {
                                                                                viewModelScope
                                                                                    .launch {
                                                                                        eventsChannel
                                                                                            .send(
                                                                                                SettingsEvent
                                                                                                    .NavigateToSettingsFileEdit(
                                                                                                        it
                                                                                                            .filePath
                                                                                                    )
                                                                                            )
                                                                                    }
                                                                            },
                                                                        ),
                                                                    ),
                                                            ),
                                                        ),
                                                ),
                                            )
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.gestures,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.player_gestures,
                                                    backendPreference =
                                                        appPreferences.playerGestures,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.player_gestures_vb,
                                                    descriptionStringRes =
                                                        R.string.player_gestures_vb_summary,
                                                    dependencies =
                                                        listOf(appPreferences.playerGestures),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.playerGesturesVB,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.player_gestures_seek,
                                                    descriptionStringRes =
                                                        R.string.player_gestures_seek_summary,
                                                    dependencies =
                                                        listOf(appPreferences.playerGestures),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.playerGesturesSeek,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.player_gestures_zoom,
                                                    descriptionStringRes =
                                                        R.string.player_gestures_zoom_summary,
                                                    dependencies =
                                                        listOf(appPreferences.playerGestures),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.playerGesturesZoom,
                                                ),
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string.player_gestures_speed,
                                                    dependencies =
                                                        listOf(appPreferences.playerGestures),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerGesturesSpeedMultiplier,
                                                    options = R.array.gesture_speed_multiplier,
                                                    optionValues =
                                                        R.array.gesture_speed_multiplier_values,
                                                ),
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string.player_gestures_seek_sensitivity,
                                                    dependencies =
                                                        listOf(appPreferences.playerGestures),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerGesturesSeekSensitivity,
                                                    options = R.array.gesture_sensitivity,
                                                    optionValues =
                                                        R.array.gesture_sensitivity_values,
                                                ),
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string
                                                            .player_gestures_vertical_sensitivity,
                                                    dependencies =
                                                        listOf(appPreferences.playerGestures),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerGesturesVerticalSensitivity,
                                                    options = R.array.gesture_sensitivity,
                                                    optionValues =
                                                        R.array.gesture_sensitivity_values,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.player_gestures_chapter_skip,
                                                    descriptionStringRes =
                                                        R.string
                                                            .player_gestures_chapter_skip_summary,
                                                    dependencies =
                                                        listOf(appPreferences.playerGestures),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.playerGesturesChapterSkip,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.player_brightness_remember,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences.playerGestures,
                                                            appPreferences.playerGesturesVB,
                                                        ),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerGesturesBrightnessRemember,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.player_start_maximized,
                                                    descriptionStringRes =
                                                        R.string.player_start_maximized_summary,
                                                    dependencies =
                                                        listOf(appPreferences.playerGestures),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.playerGesturesStartMaximized,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.seeking,
                                        preferences =
                                            listOf(
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.seek_back_increment,
                                                    backendPreference =
                                                        appPreferences.playerSeekBackInc,
                                                    suffixRes = R.string.ms,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.seek_forward_increment,
                                                    backendPreference =
                                                        appPreferences.playerSeekForwardInc,
                                                    suffixRes = R.string.ms,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.pref_player_chapter_markers,
                                                    descriptionStringRes =
                                                        R.string
                                                            .pref_player_chapter_markers_summary,
                                                    backendPreference =
                                                        appPreferences.playerChapterMarkers,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.media_segments,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_skip_button,
                                                    descriptionStringRes =
                                                        R.string
                                                            .pref_player_media_segments_skip_button_summary,
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsSkipButton,
                                                ),
                                                PreferenceMultiSelect(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_skip_button_type,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences
                                                                .playerMediaSegmentsSkipButton
                                                        ),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsSkipButtonType,
                                                    options = R.array.media_segments_type,
                                                    optionValues =
                                                        R.array.media_segments_type_values,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_skip_button_duration,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences
                                                                .playerMediaSegmentsSkipButton
                                                        ),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsSkipButtonDuration,
                                                    suffixRes = R.string.seconds,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_auto_skip,
                                                    descriptionStringRes =
                                                        R.string
                                                            .pref_player_media_segments_auto_skip_summary,
                                                    backendPreference =
                                                        appPreferences.playerMediaSegmentsAutoSkip,
                                                ),
                                                PreferenceSelect(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_auto_skip_mode,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences
                                                                .playerMediaSegmentsAutoSkip
                                                        ),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsAutoSkipMode,
                                                    options = R.array.media_segments_auto_skip,
                                                    optionValues =
                                                        R.array.media_segments_auto_skip_values,
                                                ),
                                                PreferenceMultiSelect(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_auto_skip_type,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences
                                                                .playerMediaSegmentsAutoSkip
                                                        ),
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsAutoSkipType,
                                                    options = R.array.media_segments_type,
                                                    optionValues =
                                                        R.array.media_segments_type_values,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_media_segments_next_episode_threshold,
                                                    backendPreference =
                                                        appPreferences
                                                            .playerMediaSegmentsNextEpisodeThreshold,
                                                    suffixRes = R.string.ms,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.trickplay,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.pref_player_trickplay,
                                                    descriptionStringRes =
                                                        R.string.pref_player_trickplay_summary,
                                                    backendPreference =
                                                        appPreferences.playerTrickplay,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string
                                                            .pref_player_gestures_seek_trickplay,
                                                    descriptionStringRes =
                                                        R.string
                                                            .pref_player_gestures_seek_trickplay_summary,
                                                    dependencies =
                                                        listOf(appPreferences.playerTrickplay),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.playerGesturesSeekTrickplay,
                                                ),
                                            ),
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.picture_in_picture,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.picture_in_picture_gesture,
                                                    descriptionStringRes =
                                                        R.string.picture_in_picture_gesture_summary,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.playerPipGesture,
                                                )
                                            ),
                                    ),
                                    PreferenceGroup(
                                        nameStringResource = R.string.background_playback,
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.pref_player_background_audio,
                                                    descriptionStringRes =
                                                        R.string
                                                            .pref_player_background_audio_summary,
                                                    backendPreference =
                                                        appPreferences.playerBackgroundAudio,
                                                )
                                            ),
                                    ),
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_servers,
                            descriptionStringRes = R.string.settings_servers_summary,
                            iconDrawableId = R.drawable.ic_server,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(SettingsEvent.NavigateToServers)
                                }
                            },
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        // W42：下载 + 缓存合并成一个子页（5 组 IA：媒体库 → 下载与缓存）。
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_downloads_cache,
                            descriptionStringRes = R.string.settings_downloads_cache_summary,
                            iconDrawableId = R.drawable.ic_download,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                // W51：用户口径 =「仅 Wi-Fi 下载（默认开）」；对既有键取反绑定，不新增重复键。
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_download_wifi_only,
                                                    descriptionStringRes =
                                                        R.string
                                                            .settings_download_wifi_only_summary,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    negateValue = true,
                                                    backendPreference =
                                                        appPreferences.downloadOverMobileData,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.download_roaming,
                                                    dependencies =
                                                        listOf(
                                                            appPreferences.downloadOverMobileData
                                                        ),
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.downloadWhenRoaming,
                                                ),
                                                PreferenceIntSelect(
                                                    nameStringResource =
                                                        R.string.settings_download_concurrency,
                                                    descriptionStringRes =
                                                        R.string
                                                            .settings_download_concurrency_summary,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.downloadConcurrency,
                                                    optionValues = listOf(1, 2, 3),
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string
                                                            .settings_download_complete_notification,
                                                    descriptionStringRes =
                                                        R.string
                                                            .settings_download_complete_notification_summary,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.downloadCompleteNotification,
                                                ),
                                            )
                                    ),
                                    // W42：缓存设置并入本子页（原来是独立的「缓存」分类）。
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSwitch(
                                                    nameStringResource =
                                                        R.string.settings_use_cache_title,
                                                    descriptionStringRes =
                                                        R.string.settings_use_cache_summary,
                                                    iconDrawableId = R.drawable.ic_hard_drive,
                                                    backendPreference = appPreferences.imageCache,
                                                ),
                                                PreferenceIntInput(
                                                    nameStringResource =
                                                        R.string.settings_cache_size,
                                                    descriptionStringRes =
                                                        R.string.settings_cache_size_message,
                                                    dependencies =
                                                        listOf(appPreferences.imageCache),
                                                    backendPreference =
                                                        appPreferences.imageCacheSize,
                                                    suffixRes = R.string.mb,
                                                ),
                                            )
                                    ),
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_network,
                            descriptionStringRes = R.string.settings_network_summary,
                            iconDrawableId = R.drawable.ic_network,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.settings_request_timeout,
                                                    backendPreference =
                                                        appPreferences.requestTimeout,
                                                    suffixRes = R.string.ms,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.settings_connect_timeout,
                                                    backendPreference =
                                                        appPreferences.connectTimeout,
                                                    suffixRes = R.string.ms,
                                                ),
                                                PreferenceLongInput(
                                                    nameStringResource =
                                                        R.string.settings_socket_timeout,
                                                    backendPreference =
                                                        appPreferences.socketTimeout,
                                                    suffixRes = R.string.ms,
                                                ),
                                            )
                                    )
                                ),
                        )
                    )
            ),
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceCategory(
                            nameStringResource = R.string.about,
                            descriptionStringRes = R.string.settings_about_summary,
                            iconDrawableId = R.drawable.ic_info,
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(SettingsEvent.NavigateToAbout)
                                }
                            },
                        )
                    )
            ),
            // 离线模式固定在设置最后一项：打开后停留本页（不重启 / 不跳转），联网能力在下次进入页面时自行降级。
            PreferenceGroup(
                preferences =
                    listOf(
                        PreferenceSwitch(
                            nameStringResource = R.string.offline_mode,
                            descriptionStringRes = R.string.offline_mode_summary,
                            iconDrawableId = R.drawable.ic_server_off,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            backendPreference = appPreferences.offlineMode,
                        )
                    )
            ),
            // W42：从既有分类中拆出 / 新增的顶层条目（展示位置由 SETTINGS_GROUP_LAYOUT 决定）。
            PreferenceGroup(
                preferences =
                    listOf(
                        // 「首页显示本地媒体」从「媒体库」子页提升为顶层开关（W42 5 组 IA：媒体库 → 本地媒体）。
                        PreferenceSwitch(
                            nameStringResource = R.string.settings_local_library_visible,
                            descriptionStringRes = R.string.settings_local_library_visible_summary,
                            iconDrawableId = R.drawable.ic_folder,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            backendPreference = appPreferences.localLibraryVisible,
                        ),
                        // TV 端保留显式的「用户」入口（手机侧由账号卡承接，TV 设置页没改版，行为保持不变）。
                        PreferenceCategory(
                            nameStringResource = R.string.users,
                            iconDrawableId = R.drawable.ic_user,
                            supportedDeviceTypes = listOf(DeviceType.TV),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(SettingsEvent.NavigateToUsers)
                                }
                            },
                        ),
                        // 「外观」：原本在「界面」子页里的主题 / 动态取色，提升为与「界面」并列的顶层分类。
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_appearance,
                            descriptionStringRes = R.string.settings_appearance_summary,
                            iconDrawableId = R.drawable.ic_palette,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.NavigateToSettings(
                                            intArrayOf(it.nameStringResource)
                                        )
                                    )
                                }
                            },
                            nestedPreferenceGroups =
                                listOf(
                                    PreferenceGroup(
                                        preferences =
                                            listOf(
                                                PreferenceSelect(
                                                    nameStringResource = R.string.theme,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference = appPreferences.theme,
                                                    onUpdate = { value ->
                                                        viewModelScope.launch {
                                                            eventsChannel.send(
                                                                SettingsEvent.UpdateTheme(
                                                                    value ?: "system"
                                                                )
                                                            )
                                                        }
                                                    },
                                                    options = R.array.theme,
                                                    optionValues = R.array.theme_values,
                                                ),
                                                PreferenceSwitch(
                                                    nameStringResource = R.string.dynamic_colors,
                                                    descriptionStringRes =
                                                        R.string.dynamic_colors_summary,
                                                    enabled =
                                                        Build.VERSION.SDK_INT >=
                                                            Build.VERSION_CODES.S,
                                                    supportedDeviceTypes = listOf(DeviceType.PHONE),
                                                    backendPreference =
                                                        appPreferences.dynamicColors,
                                                ),
                                            )
                                    )
                                ),
                        ),
                        // 「设备」：本设备上的系统级应用设置（权限 / 通知 / 存储）。上游 Findroid 的「设备」分类
                        // 在本仓库从未接线（只有遗留字符串 device_name 与未调用的 updateDeviceName），
                        // W42 按「只重排合并、不扩大范围」的原则接系统应用信息页，不新建偏好键。
                        PreferenceCategory(
                            nameStringResource = R.string.settings_category_device,
                            descriptionStringRes = R.string.settings_device_summary,
                            iconDrawableId = R.drawable.ic_device,
                            supportedDeviceTypes = listOf(DeviceType.PHONE),
                            onClick = {
                                viewModelScope.launch {
                                    eventsChannel.send(
                                        SettingsEvent.LaunchIntent(
                                            Intent(
                                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                                Uri.parse("package:${context.packageName}"),
                                            )
                                        )
                                    )
                                }
                            },
                        ),
                    )
            ),
        )

    /** @param compactForm 紧凑形态（手机底部 Tab）。W42「隐藏底栏」只在紧凑形态可用：平板 / 展开形态 本来就没有底栏（侧轨常驻），开关置灰并给出说明。 */
    fun loadPreferences(
        indexes: IntArray = intArrayOf(),
        deviceType: DeviceType,
        compactForm: Boolean = true,
    ) {
        viewModelScope.launch {
            var preferences = topLevelPreferenceGroups(hideBottomBarSwitch(compactForm))

            // Show preferences based on the name of the parent
            for (index in indexes) {
                // If index is root (Settings) don't search for category
                if (index == R.string.title_settings) {
                    break
                }
                val preference =
                    preferences
                        .flatMap { it.preferences }
                        .filterIsInstance<PreferenceCategory>()
                        .find { it.nameStringResource == index }
                if (preference != null) {
                    preferences = preference.nestedPreferenceGroups
                }
            }

            // Update all (visible) preferences with there current values
            preferences =
                preferences
                    .map { preferenceGroup ->
                        preferenceGroup.copy(
                            preferences =
                                preferenceGroup.preferences
                                    .filter { it.supportedDeviceTypes.contains(deviceType) }
                                    .map { preference ->
                                        when (preference) {
                                            is PreferenceSwitch -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences
                                                            .getValue(preference.backendPreference)
                                                            .let { value ->
                                                                if (preference.negateValue) !value
                                                                else value
                                                            },
                                                )
                                            }
                                            is PreferenceIntSelect -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceSelect -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceDynamicSelect -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceMultiSelect -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceIntInput -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            is PreferenceLongInput -> {
                                                preference.copy(
                                                    enabled =
                                                        preference.enabled &&
                                                            preference.dependencies.all {
                                                                appPreferences.getValue(it)
                                                            },
                                                    value =
                                                        appPreferences.getValue(
                                                            preference.backendPreference
                                                        ),
                                                )
                                            }
                                            else -> preference
                                        }
                                    }
                        )
                    }
                    .filter { it.preferences.isNotEmpty() }

            _state.emit(_state.value.copy(preferenceGroups = preferences))
        }
    }

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.OnUpdate -> {
                when (action.preference) {
                    is PreferenceSwitch ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            if (action.preference.negateValue) !action.preference.value
                            else action.preference.value,
                        )
                    is PreferenceIntSelect ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                    is PreferenceSelect ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                    is PreferenceDynamicSelect ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                    is PreferenceMultiSelect ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                    is PreferenceIntInput ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                    is PreferenceLongInput ->
                        appPreferences.setValue(
                            action.preference.backendPreference,
                            action.preference.value,
                        )
                }
            }
            else -> Unit
        }
    }

    /** 「显示在其他应用上层」权限页（桌面歌词需要）：带包名，落点就是本应用。 */
    private fun overlayPermissionIntent(): Intent =
        Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        )

    /**
     * W42「隐藏底栏」：紧凑形态可开关（隐藏手机底部导航栏、重启保留）。
     *
     * 平板形态没有底栏（侧轨常驻），开关置灰 + 一行说明——用户 2026-10-03 确认的推荐做法。
     */
    private fun hideBottomBarSwitch(compactForm: Boolean) =
        PreferenceSwitch(
            nameStringResource = R.string.settings_hide_bottom_bar,
            descriptionStringRes =
                if (compactForm) R.string.settings_hide_bottom_bar_summary
                else R.string.settings_hide_bottom_bar_tablet_note,
            iconDrawableId = R.drawable.ic_sidebar,
            enabled = compactForm,
            supportedDeviceTypes = listOf(DeviceType.PHONE),
            backendPreference = appPreferences.hideBottomBar,
        )

    /** 顶层 5 组（W42）：原始分类块 + 每轮动态生成的「隐藏底栏」开关。 */
    private fun topLevelPreferenceGroups(hideBottomBar: PreferenceSwitch): List<PreferenceGroup> =
        buildTopLevelPreferenceGroups(
            rawPreferenceGroups.flatMap { it.preferences } + hideBottomBar
        )
}
