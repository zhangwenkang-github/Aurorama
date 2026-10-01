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
    // 背景档位顺序：无 / 轻纱 / 半透明 / 实底 —— 默认「无」（用户 2026-10-01 反馈）
    val playerSubtitleStyleBackground = Preference("pref_player_subtitle_style_background", 0)
    val playerSubtitleStyleEdge = Preference("pref_player_subtitle_style_edge", 1)
    val playerSubtitleStylePosition = Preference("pref_player_subtitle_style_position", 1)

    // Player - 音轨（延迟；§1.2）
    /** 音轨延迟（毫秒）：正 = 声音延后，负 = 声音提前；面板按 0.05s 步长调节，范围 ±5s */
    val playerAudioDelayMs = Preference("pref_player_audio_delay_ms", 0L)

    // Player - 码率 / 解码（W12：服务器转码档位 + 硬解/软解策略；只追加，不重排既有键）
    /**
     * 播放码率档位：0 = 自动（服务器自行判断直连 / 转码）、-1 = 原始画质（只直连，不转码）、 其余 = 具体 Mbps（按该码率请求服务器转码）。映射见
     * [PlayerStreamingQuality]。
     */
    val playerStreamingBitrate = Preference("pref_player_streaming_bitrate", 0L)

    /** 解码策略：hardware = 硬解优先（失败自动回退）、software = 仅软解（FFmpeg / mpv） */
    val playerDecodeMode = Preference("pref_player_decode_mode", "hardware")

    // Player - 解码回退链（W16：本地硬解 → 服务器解码/转码 → 本地软解；只追加，不重排既有键）
    /**
     * 解码回退档位：0 = 未降级；1 = 已降到服务器解码/转码；2 = 已降到本地软解。
     *
     * 由 `PlayerViewModel` 在 ExoPlayer 硬解报错时推进，落盘是为了「重启播放页续播」不丢档位； 换条目 / 用户显式改码率、内核、解码策略时清回 0。映射见
     * [PlayerDecodeFallback]。
     */
    val playerDecodeFallbackStage = Preference("pref_player_decode_fallback_stage", 0)

    /** 触发解码回退的媒体 id（空 = 无）；条目变化时自动清空回退档位，避免影响下一部片 */
    val playerDecodeFallbackMediaId = Preference("pref_player_decode_fallback_media_id", "")

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

    // Reader
    /** 阅读模式：scroll / paged / two_column。 */
    val readerMode = Preference("pref_reader_mode", "scroll")

    /** 正文字号倍数（Readium fontSize，1.0 = 100%）。 */
    val readerFontSize = Preference("pref_reader_font_size", 1.0f)

    /** 行距倍数。 */
    val readerLineHeight = Preference("pref_reader_line_height", 1.2f)

    /** 页面边距倍数。 */
    val readerPageMargins = Preference("pref_reader_page_margins", 1.0f)

    /** 内置字体：publisher / serif / sans / monospace。 */
    val readerFontFamily = Preference("pref_reader_font_family", "publisher")

    /** 阅读主题：paper / eyecare / dark / oled / system。 */
    val readerTheme = Preference("pref_reader_theme", "dark")

    /** 正文对齐：justify / start / center。 */
    val readerTextAlign = Preference("pref_reader_text_align", "justify")

    /** 漫画右起翻页（RTL）：只影响 PDF / CBZ 的分页 / 双栏方向；滚动模式保持纵向顺序。 */
    val readerRtl = Preference("pref_reader_rtl", false)

    // Migrations
    val mpvMigrated = Preference("mpv_migrated", false)

    // Interface - 导航信息架构（W6-R6N，2026-10-01；只追加 pref_ui_* 前缀，不重排既有键）
    /** 首页「使用哪个媒体库」：null = 自动（服务器上全部影视库）。 */
    val uiHomeLibraryId = Preference<String?>("pref_ui_home_library_id", null)

    /** 音乐模式「使用哪个音乐库」：null = 自动（服务器上全部音乐库）。 */
    val uiMusicLibraryId = Preference<String?>("pref_ui_music_library_id", null)

    /** 书架「使用哪个书籍库」：null = 自动（第一个非空书籍库）。 */
    val uiBookshelfLibraryId = Preference<String?>("pref_ui_bookshelf_library_id", null)

    /** 侧栏（平板侧轨 / 抽屉）条目可见性。客户端设置始终可见，保证入口不会把自己关掉。 */
    val uiSidebarShowHome = Preference("pref_ui_sidebar_show_home", true)
    val uiSidebarShowMedia = Preference("pref_ui_sidebar_show_media", true)
    val uiSidebarShowMusic = Preference("pref_ui_sidebar_show_music", true)
    val uiSidebarShowBookshelf = Preference("pref_ui_sidebar_show_bookshelf", true)
    val uiSidebarShowDownloads = Preference("pref_ui_sidebar_show_downloads", true)
    val uiSidebarShowConsole = Preference("pref_ui_sidebar_show_console", true)
    val uiSidebarShowMetadata = Preference("pref_ui_sidebar_show_metadata", true)

    /**
     * 服务器媒体库目录缓存（[com.zhangwenkang.cinefin.settings.domain.models.LibraryCatalog] 编码）。
     *
     * 设置模块不能依赖 data 层（data/settings 依赖方向相反），所以由 `DrawerViewModel` 在加载抽屉数据时写入， 设置页再读出来渲染「首页 / 音乐 /
     * 书架使用哪个媒体库」的动态选项。
     */
    val uiLibraryCatalog = Preference("pref_ui_library_catalog", "")

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
