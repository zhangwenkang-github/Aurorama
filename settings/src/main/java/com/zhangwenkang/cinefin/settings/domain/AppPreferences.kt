package com.zhangwenkang.cinefin.settings.domain

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.settings.domain.models.Preference
import com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode
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
    /** 动态取色默认关闭：极光幕有自己的墨底 + 朱砂配色， 跟随系统壁纸会把品牌色冲掉；想要 Material You 的用户可在设置里打开。 */
    val dynamicColors = Preference("pref_dynamic_colors", false)
    val homeSuggestions = Preference<Boolean>("home_suggestions", true)
    val homeContinueWatching = Preference<Boolean>("home_continue_watching", true)
    /** W54-D（用户 2026-10-03 确认）：继续阅读 / 继续收听两条走廊的独立开关。 */
    val homeContinueReading = Preference<Boolean>("home_continue_reading", true)
    val homeContinueListening = Preference<Boolean>("home_continue_listening", true)
    val homeNextUp = Preference<Boolean>("home_next_up", true)
    /**
     * W54-D：「最近添加」按媒体类型拆成三条，各自开关。
     *
     * 逐库的「在首页显示」开关另见 [uiHomeLibrariesHidden]；旧的全局 `home_latest` 键已随本波退役。
     */
    val homeRecentlyAddedVideos = Preference<Boolean>("home_recently_added_videos", true)
    val homeRecentlyAddedBooks = Preference<Boolean>("home_recently_added_books", true)
    val homeRecentlyAddedMusic = Preference<Boolean>("home_recently_added_music", true)
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
    /**
     * W67 补充（负责人特批新增键）：播放器时钟格式 = `system`（跟随系统 `Settings.System.TIME_12_24`）/ `24` / `12`。
     * 供「预计结束时刻」等时钟显示共用；解析与格式化见 `PlayerClock.kt`。
     */
    val playerClockFormat = Preference("pref_player_clock_format", "system")

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

    /**
     * 后台继续播放：离开播放页（锁屏 / 切到别的应用）时不暂停。
     *
     * 对应官方 Android 客户端「视频播放器 → 允许后台播放音频」。W68 起**默认开启**（用户 2026-10-04 全检要求）；
     * 播放页设置面板与设置页仍可关闭，关闭后行为与旧版一致（离开播放页即暂停）。
     */
    val playerBackgroundAudio = Preference("pref_player_background_audio", true)

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

    // Player - 解码回退会话 / 循环保护 / 开关（W19：只追加，不重排既有键）
    /**
     * 回退档位所属的播放会话 id（空 = 无）。
     *
     * 回退重启（Activity recreate）会带着同一个会话 id，档位在会话内保持；新开播放页 / 通知另起播放会用新的会话 id，判定为「换会话」并把档位清零。旧实现按
     * Intent 条目 id 比对，季 / 剧集入口与队列换集时 Intent 条目 ≠ 实际播放条目，每次回退重启都会清零档位 → 死循环（W19 真机实测 7 次重启 / 分钟）。
     */
    val playerDecodeFallbackSession = Preference("pref_player_decode_fallback_session", "")

    /**
     * 回退重启守卫（循环保护）：`mediaId|targetStage|attempts`，同一媒体 + 同一目标档位的重启次数。 只在「新播放会话」或用户显式改配置时清零，见
     * [PlayerDecodeFallback]。
     */
    val playerDecodeFallbackGuard = Preference("pref_player_decode_fallback_guard", "")

    /** 播放失败时是否自动回退：开 = 硬解 → 服务器转码 → mpv 软解；关 = 强制所选内核，失败只提示错误（默认开） */
    val playerAutoFallback = Preference("pref_player_auto_fallback", true)

    // Downloads
    val downloadOverMobileData = Preference("pref_downloads_mobile_data", false)
    val downloadWhenRoaming = Preference("pref_downloads_roaming", false)
    /** W50 自研下载引擎：同时下载数（1–3，默认 2）。设置 UI 由 W51 补。 */
    val downloadConcurrency = Preference("pref_download_concurrency", 2)

    /** W57：下载限速（MB/s，0 = 不限速；每次任务启动时读取）。 */
    val downloadSpeedLimitMbps = Preference("pref_download_speed_limit_mbps", 0)
    /** W50 自研下载引擎：下载完成通知开关（默认开）；关闭后仍保留进行中前台服务通知。 */
    val downloadCompleteNotification = Preference("pref_download_complete_notification", true)

    // Network
    val requestTimeout =
        Preference("pref_network_request_timeout", Constants.NETWORK_DEFAULT_REQUEST_TIMEOUT)
    val connectTimeout =
        Preference("pref_network_connect_timeout", Constants.NETWORK_DEFAULT_CONNECT_TIMEOUT)
    val socketTimeout =
        Preference("pref_network_socket_timeout", Constants.NETWORK_DEFAULT_SOCKET_TIMEOUT)

    // Cache
    val imageCache = Preference("pref_image_cache", true)
    val imageCacheSize = Preference("pref_image_cache_size", 50)

    // Sorting
    val sortBy = Preference("pref_sort_by", "SortName")
    val sortOrder = Preference("pref_sort_order", "Ascending")

    // Offline mode
    val offlineMode = Preference("pref_offline_mode", false)
    /**
     * W36：被关闭「允许离线模式观看」的书籍 id（书籍离线文件不经过 `sources` 表， 开关状态只能落在偏好里；视频 / 音乐用 `sources.allowOffline`）。
     */
    val offlineBlockedBooks = Preference("pref_offline_blocked_books", emptySet<String>())
    /**
     * W36 预留（W37 实现内容）：离线媒体库里是否显示「本地媒体库」入口。
     *
     * W36 只落地开关位与持久化；「建立本地媒体库」（SAF 添加文件夹）属于 W37。
     */
    val localLibraryVisible = Preference("pref_local_library_visible", false)

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
    /** 音乐模式「使用哪个音乐库」：null = 自动（服务器上全部音乐库）。 */
    val uiMusicLibraryId = Preference<String?>("pref_ui_music_library_id", null)

    /** 书架「使用哪个书籍库」：null = 自动（第一个非空书籍库）。 */
    val uiBookshelfLibraryId = Preference<String?>("pref_ui_bookshelf_library_id", null)

    /**
     * 视频模式页「显示哪个库」（W54-C，用户 2026-10-03 确认）：null = 全部视频库（`movies` + `tvshows`）。
     *
     * 视频页顶栏「库选择」写入这里：库卡模式 = 过滤显示哪些库卡；聚合模式 = 只聚合该库条目。 服务器上该库被删除 / 不再属于 movies·tvshows
     * 时回落「全部库」（不偷偷改选别家）。
     */
    val uiVideoLibraryId = Preference<String?>("pref_ui_video_library_id", null)

    /** 侧栏（平板侧轨 / 抽屉）条目可见性。客户端设置始终可见，保证入口不会把自己关掉。 */
    val uiSidebarShowHome = Preference("pref_ui_sidebar_show_home", true)

    /**
     * W53（用户 2026-10-03 确认）：侧栏「视频」入口开关，与首页 / 音乐 / 书架 / 媒体库 / 下载并列。
     *
     * 只作用于侧栏（平板侧轨 / 手机抽屉）；手机底栏的四个 Tab 不受这些开关影响（既有语义）。
     */
    val uiSidebarShowVideo = Preference("pref_ui_sidebar_show_video", true)
    val uiSidebarShowMedia = Preference("pref_ui_sidebar_show_media", true)
    val uiSidebarShowMusic = Preference("pref_ui_sidebar_show_music", true)
    val uiSidebarShowBookshelf = Preference("pref_ui_sidebar_show_bookshelf", true)
    val uiSidebarShowDownloads = Preference("pref_ui_sidebar_show_downloads", true)
    val uiSidebarShowConsole = Preference("pref_ui_sidebar_show_console", true)
    val uiSidebarShowMetadata = Preference("pref_ui_sidebar_show_metadata", true)

    /**
     * 「隐藏底栏」（W42 新增，用户 2026-10-03 确认）：紧凑形态隐藏底部导航栏；重启保留。
     *
     * 平板形态没有底栏（侧轨常驻），设置页会把该开关置灰并给出一行说明。默认关。
     */
    val hideBottomBar = Preference("pref_hide_bottom_bar", false)

    /**
     * 服务器媒体库目录缓存（[com.zhangwenkang.cinefin.settings.domain.models.LibraryCatalog] 编码）。
     *
     * 设置模块不能依赖 data 层（data/settings 依赖方向相反），所以由 `DrawerViewModel` 在加载抽屉数据时写入， 设置页再读出来渲染「首页 / 音乐 /
     * 书架使用哪个媒体库」的动态选项。
     */
    val uiLibraryCatalog = Preference("pref_ui_library_catalog", "")

    /**
     * W54-D 首页模块的库级偏好（用户 2026-10-03 确认「全部按推荐」）。旧的 「首页媒体库」单选 `pref_ui_home_library_id`
     * 已删除——它只让首页显示一个库的最新内容， 正是「首页只有最新电影」的根因；迁移说明见 `docs/UI_PLAN.md` D60。
     *
     * - [uiHomeLibrariesHidden]：关掉「在首页显示」的库 id 集合，默认空 = 全部开；
     * - [uiHomeLibraryPages]：库 id → 默认分页 key 映射（先落盘，库页 tabs 消费）；
     * - [uiHomeLibraryOrder]：用户调整过的媒体库顺序（未列出的库按服务器顺序追加）。
     */
    val uiHomeLibrariesHidden = Preference("pref_ui_home_libraries_hidden", emptySet<String>())

    val uiHomeLibraryPages = Preference("pref_ui_home_library_pages", "")

    val uiHomeLibraryOrder = Preference("pref_ui_home_library_order", "")

    /**
     * 视频模式页显示方式（W53，用户 2026-10-03 确认）：`cards` = 库卡列表（默认）/ `aggregated` = 聚合列表。
     *
     * 取值见 [com.zhangwenkang.cinefin.settings.domain.models.VideoDisplayMode]；设置页「媒体库」子页可选。
     */
    val uiVideoDisplayMode =
        Preference(
            "pref_ui_video_display_mode",
            VideoDisplayMode.defaultValue.value,
        )

    // Music（W21-R2；只追加 pref_music_* 前缀，不重排既有键）
    /** 应用重启后恢复上次音乐队列与播放位置（MU-3 队列保存）。默认开。 */
    val musicResumeQueue = Preference("pref_music_resume_queue", true)

    // Music（W23-MUSIC 桌面歌词；继续只追加 pref_music_* 前缀，不重排既有键）
    /** 桌面歌词悬浮窗开关（需要「显示在其他应用上层」权限，无权限时悬浮窗不显示）。 */
    val musicLyricsOverlay = Preference("pref_music_lyrics_overlay", false)

    /** 桌面歌词文字颜色档位（[com.zhangwenkang.cinefin.music.data.LyricsOverlayTint] 的 key）。 */
    val musicLyricsOverlayTint = Preference("pref_music_lyrics_overlay_tint", "moon_white")

    /** 桌面歌词字号档位（[com.zhangwenkang.cinefin.music.data.LyricsOverlaySize] 的 key）。 */
    val musicLyricsOverlaySize = Preference("pref_music_lyrics_overlay_size", "medium")

    /** 桌面歌词显示语言（`LyricsDisplayLanguage.name`；文档里没有该语言时回落默认）。 */
    val musicLyricsOverlayLanguage =
        Preference("pref_music_lyrics_overlay_language", "SIMPLIFIED_CHINESE")

    /** 桌面歌词锁定：锁定后不可拖动（仍可单击打开设置面板）。 */
    val musicLyricsOverlayLocked = Preference("pref_music_lyrics_overlay_locked", false)

    // Music（W24-MUSIC 桌面歌词位置持久化；继续只追加 pref_music_* 前缀，不重排既有键）
    /** 桌面歌词悬浮窗左上角 X（像素；-1 = 尚未记录，使用默认位）。 */
    val musicLyricsOverlayX = Preference("pref_music_lyrics_overlay_x", -1)

    /** 桌面歌词悬浮窗左上角 Y（像素；-1 = 尚未记录，使用默认位）。 */
    val musicLyricsOverlayY = Preference("pref_music_lyrics_overlay_y", -1)

    // Music（W25-MUSIC 悬浮窗保持显示时长档位；继续只追加 pref_music_* 前缀，不重排既有键）
    /** 桌面歌词无操作自动隐藏的保持时长档位（`LyricsOverlayIdle` 的 key；`3s` 为默认，`always` = 常显）。 */
    val musicLyricsOverlayIdle = Preference("pref_music_lyrics_overlay_idle", "3s")

    // Music（W30-MUSIC-FX 音乐音效 EQ / ReplayGain / 交叉淡化；继续只追加 pref_music_* 前缀，不重排既有键）
    /** 均衡器开关（W30-MUSIC-FX）。默认关，保持既有听感不变。 */
    val musicEqualizerEnabled = Preference("pref_music_eq_enabled", false)

    /** 均衡器预设 key（`MusicEqualizerPreset.key`；`custom` = 使用 [musicEqualizerCustomBands]）。 */
    val musicEqualizerPreset = Preference("pref_music_eq_preset", "flat")

    /** 自定义频段增益（逗号分隔 dB，五段：60 / 230 / 910 / 3600 / 14000 Hz）。 */
    val musicEqualizerCustomBands = Preference("pref_music_eq_custom_bands", "0.0,0.0,0.0,0.0,0.0")

    /** ReplayGain 模式 key（`off` / `track` / `album`；对齐 mpv 的 `replaygain` 三态）。默认关。 */
    val musicReplayGainMode = Preference("pref_music_replaygain_mode", "off")

    /** 交叉淡化档位（秒；0 = 关闭，可选 2 / 4 / 6）。默认关。 */
    val musicCrossfadeSeconds = Preference("pref_music_crossfade_seconds", 0)

    // Local library（W37 本地媒体库；只追加 pref_local_* / pref_music_* 前缀，不重排既有键）
    /** 音乐曲库来源筛选：`ALL` / `SERVER` / `LOCAL`。 */
    val localLibraryMusicSource = Preference("pref_music_source_filter", "ALL")

    /** 音乐列表是否显示「本地 / 服务器」来源徽标。 */
    val localLibrarySourceBadge = Preference("pref_music_source_badge", true)

    /** 本地库条目在「继续观看 / 最近播放」是否显示视频首帧封面（本地生成，默认开）。 */
    val localLibraryVideoCover = Preference("pref_local_library_video_cover", true)

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
