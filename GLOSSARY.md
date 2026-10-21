# Aurorama

A Jellyfin Android client; this glossary names product concepts shared across the player, reader, and music surfaces.

## Language

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
