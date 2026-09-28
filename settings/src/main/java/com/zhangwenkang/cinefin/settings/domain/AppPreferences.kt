package com.zhangwenkang.cinefin.settings.domain

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import javax.inject.Inject
import timber.log.Timber

class AppPreferences @Inject constructor(val sharedPreferences: SharedPreferences) {
    // Server
    val currentServer = Preference<String?>("pref_current_server", null)

    // Language
    val preferredAudioLanguage = Preference<String?>("pref_audio_language", null)
    val preferredSubtitleLanguage = Preference<String?>("pref_subtitle_language", null)

    // Language - 字幕/音轨语言优先级（逗号分隔，越靠前优先级越高）
    val preferredSubtitleLanguages = Preference("pref_subtitle_languages", "zh-Hans,zh-Hant,zh,en")
    val preferredAudioLanguages = Preference("pref_audio_languages", "zh-Hans,zh-Hant,zh,ja,en")

    /** 字幕显示模式：auto / always / off */
    val subtitleMode = Preference("pref_subtitle_mode", Constants.SubtitleMode.AUTO)

    /** 手动切换字幕/音轨后，是否把该语言记为首选（跨视频记忆） */
    val rememberTrackSelection = Preference("pref_remember_track_selection", true)

    // Interface
    val theme = Preference("pref_theme", "system")
    /** 动态取色默认关闭：影阁有自己的墨底 + 朱砂配色， 跟随系统壁纸会把品牌色冲掉；想要 Material You 的用户可在设置里打开。 */
    val dynamicColors = Preference("pref_dynamic_colors", false)
    val homeSuggestions = Preference<Boolean>("home_suggestions", true)
    val homeContinueWatching = Preference<Boolean>("home_continue_watching", true)
    val homeNextUp = Preference<Boolean>("home_next_up", true)
    val homeLatest = Preference<Boolean>("home_latest", true)
    val displayExtraInfo = Preference("pref_display_extra_info", false)

    // Player
    val playerBackend = Preference("pref_player_backend", "exoplayer")
    val playerBrightness = Preference("pref_player_brightness", -1.0f)
    /** 画面比例档位。数值直接用 Media3 `PlayerView.RESIZE_MODE_*`： 0 = 适应屏幕（默认）、3 = 拉伸填满、4 = 裁剪填满。 */
    val playerResizeMode = Preference("pref_player_resize_mode", 0)

    // Player - mpv
    val playerMpv = Preference("pref_player_mpv", false)
    val playerMpvHwdec = Preference("pref_player_mpv_hwdec", "mediacodec")
    val playerMpvVo = Preference("pref_player_mpv_vo", "gpu-next")
    val playerMpvAo = Preference("pref_player_mpv_ao", "aaudio")

    // Player - gestures
    val playerGestures = Preference("pref_player_gestures", true)
    val playerGesturesVB = Preference("pref_player_gestures_vb", true)
    val playerGesturesZoom = Preference("pref_player_gestures_zoom", true)
    val playerGesturesSeek = Preference("pref_player_gestures_seek", true)
    val playerGesturesSeekTrickplay = Preference("pref_player_gestures_seek_trickplay", true)
    /** 长按左/右侧跳过章节。默认关闭，让长按保持「倍速播放」这一更常用的行为， 需要跳章节的用户可按需开启。 */
    val playerGesturesChapterSkip = Preference("pref_player_gestures_chapter_skip", false)
    val playerGesturesBrightnessRemember = Preference("pref_player_brightness_remember", false)
    val playerGesturesStartMaximized = Preference("pref_player_start_maximized", false)
    /** 当前账号是否为管理员的缓存（含账号 id）。 控制台入口依赖它——服务器短暂不可达时不该把管理员的入口也一起藏起来。 */
    val currentUserIsAdministrator = Preference("pref_current_user_is_admin", false)
    val currentUserIsAdministratorUserId = Preference("pref_current_user_is_admin_id", "")
    /** 长按倍速的档位：1.5 / 2.0 / 3.0（字符串便于配合设置项的选择控件） */
    val playerGesturesSpeedMultiplier = Preference("pref_player_gestures_speed", "2.0")
    /** 横向滑动灵敏度：low / standard / high */
    val playerGesturesSeekSensitivity =
        Preference("pref_player_gestures_seek_sensitivity", "standard")
    /** 纵向（亮度/音量）滑动灵敏度：low / standard / high */
    val playerGesturesVerticalSensitivity =
        Preference("pref_player_gestures_vertical_sensitivity", "standard")

    // Player - seeking
    val playerSeekBackInc = Preference("pref_player_seek_back_inc", 5_000L)
    val playerSeekForwardInc = Preference("pref_player_seek_forward_inc", 15_000L)
    val playerChapterMarkers = Preference("pref_player_chapter_markers", true)

    // Player - Media Segments
    val playerMediaSegmentsSkipButton
        get() = Preference("pref_player_media_segments_skip_button", true)

    val playerMediaSegmentsSkipButtonType
        get() = Preference("pref_player_media_segments_skip_button_type", setOf("INTRO", "OUTRO"))

    val playerMediaSegmentsSkipButtonDuration
        get() = Preference("pref_player_media_segments_skip_button_duration", 5L)

    val playerMediaSegmentsAutoSkip
        get() = Preference("pref_player_media_segments_auto_skip", false)

    val playerMediaSegmentsAutoSkipMode
        get() =
            Preference(
                "pref_player_media_segments_auto_skip_mode",
                Constants.PlayerMediaSegmentsAutoSkip.ALWAYS,
            )

    val playerMediaSegmentsAutoSkipType
        get() = Preference("pref_player_media_segments_auto_skip_type", setOf("INTRO", "OUTRO"))

    val playerMediaSegmentsNextEpisodeThreshold
        get() = Preference("pref_player_media_segments_next_episode_threshold", 5_000L)

    // Player - trickplay
    val playerTrickplay = Preference("pref_player_trickplay", true)

    // Player - PiP
    val playerPipGesture = Preference("pref_player_picture_in_picture_gesture", false)

    /** 后台继续播放：离开播放页（锁屏 / 切到别的应用）时不暂停。 对应官方 Android 客户端「视频播放器 → 允许后台播放音频」。 */
    val playerBackgroundAudio = Preference("pref_player_background_audio", false)

    // Player - 字幕（延迟 / 双语 / 外观；§1.1）
    /** 字幕延迟（毫秒）：正 = 字幕延后出现，负 = 字幕提前出现；面板按 0.1s 步长调节，范围 ±10s */
    val playerSubtitleDelayMs = Preference("pref_player_subtitle_delay_ms", 0L)

    /** 次字幕语言优先级（逗号分隔）；空串 = 关闭次字幕 */
    val secondarySubtitleLanguages = Preference("pref_secondary_subtitle_languages", "")

    /** 字幕外观档位，取值见 player/core 的 SubtitleStyle（这里只存索引，两处默认值保持一致） */
    val playerSubtitleStyleSize = Preference("pref_player_subtitle_style_size", 1)

    val playerSubtitleStyleColor = Preference("pref_player_subtitle_style_color", 0)
    val playerSubtitleStyleBackground = Preference("pref_player_subtitle_style_background", 2)
    val playerSubtitleStyleEdge = Preference("pref_player_subtitle_style_edge", 1)
    val playerSubtitleStylePosition = Preference("pref_player_subtitle_style_position", 1)

    // Downloads
    val downloadOverMobileData = Preference("pref_downloads_mobile_data", false)
    val downloadWhenRoaming = Preference("pref_downloads_roaming", false)

    // Network
    val requestTimeout =
        Preference("pref_network_request_timeout", Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT)
    val connectTimeout =
        Preference("pref_network_connect_timeout", Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT)
    val socketTimeout =
        Preference("pref_network_socket_timeout", Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT)

    // Cache
    val imageCache = Preference("pref_image_cache", true)
    val imageCacheSize = Preference("pref_image_cache_size", 20)

    // Sorting
    val sortBy = Preference("pref_sort_by", "SortName")
    val sortOrder = Preference("pref_sort_order", "Ascending")

    // Offline mode
    val offlineMode = Preference("pref_offline_mode", false)

    // Migrations
    val mpvMigrated = Preference("mpv_migrated", false)

    inline fun <reified T> getValue(preference: Preference<T>): T {
        return try {
            @Suppress("UNCHECKED_CAST")
            when (preference.defaultValue) {
                is Boolean ->
                    sharedPreferences.getBoolean(preference.backendName, preference.defaultValue)
                        as T
                is Int ->
                    sharedPreferences.getInt(preference.backendName, preference.defaultValue) as T
                is Long ->
                    sharedPreferences.getLong(preference.backendName, preference.defaultValue) as T
                is Float ->
                    sharedPreferences.getFloat(preference.backendName, preference.defaultValue) as T
                is String? ->
                    sharedPreferences.getString(preference.backendName, preference.defaultValue)
                        as T
                is Set<*> ->
                    sharedPreferences.getStringSet(
                        preference.backendName,
                        preference.defaultValue as Set<String>,
                    ) as T
                else -> preference.defaultValue
            }
        } catch (_: Exception) {
            Timber.w(
                "Failed to load ${preference.backendName} preference. Resetting to default value..."
            )
            setValue(preference, preference.defaultValue)
            preference.defaultValue
        }
    }

    inline fun <reified T> setValue(preference: Preference<T>, value: T) {
        val editor = sharedPreferences.edit()
        @Suppress("UNCHECKED_CAST")
        when (preference.defaultValue) {
            is Boolean -> editor.putBoolean(preference.backendName, value as Boolean)
            is Int -> editor.putInt(preference.backendName, value as Int)
            is Long -> editor.putLong(preference.backendName, value as Long)
            is Float -> editor.putFloat(preference.backendName, value as Float)
            is String? -> editor.putString(preference.backendName, value as String?)
            is Set<*> -> editor.putStringSet(preference.backendName, value as Set<String>)
            else -> throw Exception()
        }
        editor.apply()
    }
}
