# Aurorama

A Jellyfin Android client; this glossary names product concepts shared across the player, reader, and music surfaces.

## Language

**Aurorama 2.0**:
The next formal major product release, combining the V2 architecture and delivery-process generation; upgrading from 1.4.0 must preserve existing user data.
_Avoid_: 换个版本号, 纯 UI 改版

**V2 前置基线**:
The approved requirements, target architecture, data migration, risk-spike results, and UX direction that must exist before implementation begins.
_Avoid_: 需求清单, 开发计划

**应用主题 (App theme)**:
A named visual identity that covers the whole app and may change neutral colors, accents, typography expression, surface materials, shape language, and motion intensity while preserving information architecture, navigation, component behavior, and interaction semantics.
_Avoid_: 页面皮肤, 内容域主题, 深色模式

**主题家族 (Theme family)**:
The complete supported set of app themes presented to the user as alternatives.
_Avoid_: 页面模板, 配色开关

**品牌主题 (Brand theme)**:
A hand-designed app theme with a curated visual identity and light / dark appearances, distinct from a system-derived dynamic theme.
_Avoid_: 系统动态色主题, 动态色开关

**Prism**:
The structured, fine-line brand theme that uses stable content-domain roles and the most conventional Aurorama layout geometry.
_Avoid_: 默认主题, 系统主题

**Lumen**:
The cinematic, immersive brand theme intended to recede behind media; it is the new-install default app theme.
_Avoid_: 仅深色主题, 播放器主题

**Nocturne**:
The quiet, editorial brand theme oriented toward focused music and reading contexts.
_Avoid_: 阅读显示主题, 夜间模式

**Monolith**:
The high-contrast geometric brand theme characterized by mostly opaque surfaces, strong outlines, and an 8dp base corner radius.
_Avoid_: 纯黑白模式, 系统高对比模式

**主题画廊 (Theme gallery)**:
The settings surface that presents the finite app-theme family through large live previews and a separate appearance-mode control.
_Avoid_: 配色圆点, 自定义主题编辑器

**外观模式 (Appearance mode)**:
The independent light, dark, or follow-system setting applied within an app theme.
_Avoid_: 应用主题, 主题包

**语义状态色 (Semantic status color)**:
A success, warning, error, or info role that communicates operational meaning and must always have a non-color cue.
_Avoid_: 主题强调色, 装饰色

**内容域 (Content domain)**:
The media context of film, music, or books that selects semantic domain roles without becoming a separate app theme.
_Avoid_: 媒体主题, 页面皮肤

**阅读显示主题 (Reader display theme)**:
A reader-local preference for the reading surface and body presentation; it may override the reading canvas but does not replace the app theme for navigation, controls, or overlays.
_Avoid_: 阅读 App 主题, 全 App 主题

**系统动态色主题 (System dynamic theme)**:
An independent app theme derived from the device’s system wallpaper colors and appearance, never layered over a brand theme.
_Avoid_: 动态色开关, 品牌主题叠加色

**需求域**:
A coherent capability area that can be clarified, decided, and accepted independently within one relay session.
_Avoid_: 文档页码, 菜单页面

**集成完成**:
Work that has passed independent QA and merge preparation, has entered the V2 integration branch, and has passed integration-state gates; it is not thereby released.
_Avoid_: 分支自测完成, 已发布

**待集成**:
A functional branch or candidate that has passed its own scoped checks but has not entered the V2 integration branch; it is not `Done`.
_Avoid_: 已完成, 已发布

**批准集成目标**:
The exact V2 integration branch and commit against which a task is based, reviewed, prepared for merge, and judged as integrated.
_Avoid_: 最新 master, 任意集成分支

**发布就绪**:
A candidate that has passed the approved release gates but has not necessarily received authorization for push, tag, Release, or asset publication.
_Avoid_: 已发布, 可自动发布

**生产服务器**:
The user's normal Jellyfin server whose content and user state are read-only during development and QA.
_Avoid_: 测试服务器, 通用服务器

**测试写入服务器**:
The separate, mutable Jellyfin server reserved for explicitly controlled V2 server-write tests.
_Avoid_: 生产只读服务器, 临时数据库

**主字幕**:
The user-selected primary subtitle track. In dual-subtitle playback it is positioned above the secondary track.
_Avoid_: 大字幕, 第一字幕

**次字幕**:
The optional secondary subtitle track. It is positioned below the primary track and must never overlap it.
_Avoid_: 副字幕, 第二字幕

**全本共享缩放**:
One zoom factor shared by every page of a PDF/CBZ reader session; it does not cross books or app restarts.
_Avoid_: 单页缩放, 全局缩放

**歌词跟随**:
Automatically scrolling the lyrics list so the active line stays centered while playback progresses.
_Avoid_: 自动滚动

**歌曲栏**:
The song list in the Music page's “歌曲” tab.
_Avoid_: 播放队列, 歌词栏

**搜索作用域**:
The default media domain selected by the search entry; the search page may still switch to all domains.
_Avoid_: 搜索类型, 过滤器

**字幕默认设置**:
The device-wide subtitle appearance, delay, and language preferences that apply to every video without a per-media override.
_Avoid_: 单片字幕, 字幕轨道

**单片字幕覆盖**:
An explicit subtitle-track choice that applies only to its media item and survives quality or player-backend changes for that item.
_Avoid_: 全局字幕, 轨道序号继承

**下载意图**:
A stable request to download one media item at one quality; retries update the same intent rather than create a second active task.
_Avoid_: 文件, 容器

**接收批次**:
A group of download intents durably accepted by one user action before metadata resolution or transfer starts.
_Avoid_: 页面协程, 临时选择

**未终态任务**:
A unique download intent that has not reached completed, failed, or canceled; it is the sole source for active counts across every surface.
_Avoid_: 容器数, 进行中按钮数

**本地歌单**:
A playlist owned by the app and stored locally, independent of any server playlist.
_Avoid_: 服务器歌单副本, M3U 文件

**离线模式**:
An access mode that uses local data without an authenticated server session; it is independent from the last remembered Jellyfin user.
_Avoid_: 当前用户, 上次用户

**服务器写能力**:
An explicitly enabled capability to mutate a test Jellyfin server; it is disabled and absent from normal UI by default.
_Avoid_: 同步开关, 自动写回
