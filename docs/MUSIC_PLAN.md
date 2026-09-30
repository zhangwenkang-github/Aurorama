# Cinefin 音乐任务线（MUSIC_PLAN）

> 本文件是音乐线的**唯一权威文档**：需求、决策、进度、验收记录、踩坑库都在这里。
> 关联文档：`PROJECT_PLAN.md`（项目总览）、`REQUIREMENTS.md` §5/§11、`ARCHITECTURE.md` §4/§5.2、`PARALLEL_PLAN.md`（波次）、`ROLE_SKILLS.md` §5.2。
> 最后更新：2026-09-30（W1-R2 会话）　分支：`feature/r2-music-skeleton`

## 1. 需求基线（MU-1…MU-9，来源 REQUIREMENTS §5）

| 编号 | 需求 | W1 状态 |
|------|------|---------|
| MU-1 | 独立"音乐"模式 + 抽屉入口，不复用视频播放页 | 🟡 入口 Composable + 路由契约已就绪；抽屉注册由 R3 统一提交 |
| MU-2 | 曲库：专辑 / 艺术家 / 歌曲 / 歌单 / 收藏 / 最近播放 | 🟡 专辑 / 歌曲两级已通（客户端聚合）；其余待 W2/W3 |
| MU-3 | 单队列 + 手动排序 + "下一首播放" + 队列保存 | 🟡 单队列（`MusicQueue` 冻结模型）已接入；保存/恢复待 W2 |
| MU-4 | 直连优先 + 可配置转码档位；gapless 必做 | 🟡 音频强制 ExoPlayer（gapless 由内核承担）；`music_*` 偏好待 W2 |
| MU-5 | 歌词：服务端 / 内嵌 / 外挂 LRC + 双语识别 | ⛔ 学习笔记与算法方案已定（§4.2），实现待 W2 |
| MU-6 | 离线：下载 + 容量管理 + 离线播放 | ⛔ 方案已定（§4.3），实现待 W2/W3 |
| MU-7 | 通知 / 锁屏 / 耳机按键 / 后台播放 + 睡眠定时 | 🟡 通知 / 锁屏 / 媒体键 / 后台长驻已真机验证；睡眠定时待 W2 |
| MU-8 | 全 App 单 MediaSession，音视频互斥 | 🟡 音乐起播停视频已通（含上报）；视频侧接入待 W4（播放器线） |
| MU-9 | 播放进度写回 Jellyfin | 🟡 起播 / 停止上报已接；10s 周期上报与续播待 W2 |

服务端约束（REQUIREMENTS §11）：**只读 + 用户数据白名单**（进度 / 收藏 / 播放列表可写；禁止媒体库管理/删除）。

## 2. 架构与决策

### 2.1 W0 冻结件（不修改，只消费）

- `player:core`：`MusicQueue`（含 `move` / `insertNext` 纯函数）、`RepeatMode`、`QueueSource`；
- `player:local`：`MusicPlaybackController` / `PlaybackCoordinator` 接口、`PlayerHolder.audioSession()`；
- 路由：R1/R2 只提供路由常量，入口注册由 R3 统一提交（`PARALLEL_PLAN` §1.3）。

### 2.2 W1 本会话决策

| # | 决策 | 理由 |
|---|------|------|
| D1 | 音乐条目用本线模型（`MusicSong` / `MusicAlbum`），**不经过 `FindroidItem`** | 服务器实测：音乐库没有 MusicAlbum 实体、音频 `AlbumId` 为空，`FindroidItem` 转换会丢掉 `Album` 名与音轨号（§6-1） |
| D2 | 专辑列表 = 音频按 `Album` 名**客户端分组**；专辑内按 `IndexNumber` 排序 | 服务器 `/Items?includeItemTypes=MusicAlbum` 返回 0；音频查询支持 `sortBy=IndexNumber` |
| D3 | 音乐会话期间 `PlayerHolder.player` **固定返回 ExoPlayer**（`musicSessionActive` 标志） | 后台视频页每秒访问 `player` 会按偏好（mpv）重建实例，把正在播放的音乐打回 mpv（真机实测，§6-3） |
| D4 | `CinefinPlaybackService.onStartCommand` 检测实例切换并**重建 MediaSession** | 音乐强制 ExoPlayer 会重建实例，旧 session 指向已释放实例 → 通知/锁屏失联（真机实测，§6-2） |
| D5 | `PlaybackCoordinatorImpl.stopLocal` 对 `clearMediaItems` 做容错 | `MPVPlayer` 未实现 `removeMediaItems`（`NotImplementedError`），不能因此中断音乐起播（§6-4） |
| D6 | 单测落点：`player:local` 的纯计算函数（索引归一化 / 上报换算 / ticks） | 仓库无测试基础设施（§6-7）；纯 JVM 测试零 Android 依赖，先建立最小样例 |
| D7 | 播放上报：W1 只做起播 `Start` 与停止 `Stop`（视频停止 = 挂起等待；音乐停止 = 异步补发） | MU-9 的精细化（10s 周期、暂停即报）属 W2；先保证互斥协议正确 |

### 2.3 数据流（W1 已实现）

```text
MusicModeScreen(专辑列表) ─▶ MusicModeViewModel ─▶ MusicRepository.getAlbums()
   └─(点击专辑)─▶ MusicAlbum.songs（本地分组结果，无二次请求）
   └─(点击歌曲)─▶ MusicTrackResolver.toPlayerItems()（补媒体源地址）
                     └─▶ MusicPlaybackController.setQueue(MusicQueue, startIndex)
                            ├─ PlaybackCoordinator.onMusicStartRequested()（停视频 + 上报 Stopped）
                            ├─ PlayerHolder.audioSession()（音频强制 ExoPlayer）
                            ├─ PlaybackServiceStarter.ensureSessionService()（通知/锁屏）
                            └─ ExoPlayer.setMediaItems(...) + prepare() + play()
```

## 3. 任务清单

### W1（本会话，已交付）

- [x] `modes:music` 入口 Composable（`MusicModeScreen`）+ 路由契约（`MusicModeRoute`，`@Serializable`）
- [x] `MusicPlaybackControllerImpl`（队列状态、命令转发、播放/暂停/切歌/seek/repeat/shuffle/move/insertNext）
- [x] `PlaybackCoordinatorImpl`（音乐起播停视频 + `Sessions/Playing/Stopped` 上报；视频起播停音乐预留）
- [x] `PlaybackServiceStarter`（player:local 接口 + app:phone 实现 + Hilt 绑定）
- [x] `CinefinPlaybackService`：实例感知的会话重建（通知/锁屏在实例切换后仍可控）
- [x] `PlayerHolder`：`existingPlayer` / `musicSessionActive` / 音乐期间固定 ExoPlayer / 每实例独立 TrackSelector
- [x] 音乐列表：专辑（94 张，客户端分组）→ 曲目 → 播放（真机验证）
- [x] 单测：`MusicPlaybackMathTest`（3 用例，`:player:local:testDebugUnitTest` 通过）
- [x] 真机验证：播放 / 通知 / 锁屏 / 媒体键 / 后台播放 / 音视频互斥（§5）
- [x] 本文件（`docs/MUSIC_PLAN.md`）建立

### W2 待办（下一会话建议顺序）

1. **进度上报完善**：10s 周期 `postPlaybackProgress`、暂停即报、切歌 Stop→Start、续播（`playbackPositionTicks`）；
2. **队列增强**：队列保存/恢复（Room `music_queue` 表）、"下一首播放"入口、拖拽排序 UI；
3. **歌词子系统**：`LrcParser` / `LyricsPairer` / `LineLanguageDetector` / `LyricsNormalizer` + `LyricsApi` 接入（按 §4.2 方案）；
4. **浏览扩展**：艺术家 / 歌单 / 收藏 / 最近播放；
5. **音质偏好**：`music_*` 前缀（Wi-Fi/蜂窝码率）、直连优先策略；
6. **睡眠定时**（P1，与视频共用）。

### W3/W4 依赖

- 视频侧接入 `PlaybackCoordinator.onVideoStartRequested()`（播放器线，W4）——完成后"视频起播停音乐"闭环；
- 离线下载（复用 `DownloaderImpl`，与下载线对齐，单写者约定见 `ARCHITECTURE` §2.4）。

## 4. 学习笔记（ROLE_SKILLS §5.2 全表成果）

### 4.1 队列与会话

- Media3 以 `Player` 接口为统一抽象：ExoPlayer 与 MediaController 都是实现；UI 只依赖接口。
- 播放列表：`setMediaItems(items, startIndex, positionMs)` + `seekToNext()` / `seekToPrevious()`；随机 / 循环由 `shuffleModeEnabled` / `repeatMode` 承担，`MusicQueue` 自身保留"未随机顺序"。
- gapless：Media3 词汇表定义为"跳过曲目之间静音"；MP3 依赖 Xing/Info/VBRI 头与 LAME 编码器 delay/padding 元数据（RELEASENOTES 多处）；1.11.x 修复了硬件缓冲不足导致的 offload 预卷/gapless 卡顿。→ 验收时同一专辑切歌无感知爆音即可，跨编码/采样率的间隙留 W2 实测。
- 后台播放官方要求：`MediaSessionService` + manifest `foregroundServiceType="mediaPlayback"` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限；通知随 `MediaItem` 与 Player 状态自动更新；`onTaskRemoved` 可自定义"划掉任务"行为（项目已实现"播放中保留、否则收摊"）。
- 音视频互斥：单实例 + 单 session 下唯一可靠做法是**所有起播必经仲裁器**（W0 冻结的 `PlaybackCoordinator`），音乐侧已落地。

### 4.2 歌词方案（MU-5）

- 服务端实测：`GET /Audio/{itemId}/Lyrics` → `LyricDto{ Metadata, Lyrics[{Text, Start(ticks), Cues}] }`；原文/译文**共享同一 `Start`**（aLIEz 40 组）；`LyricMetadata` 含 `Offset` / `IsSynced`。
- 算法：`LrcParser`（兼容 `[mm:ss]`/`[mm:ss.xx]`/多时间戳/`[offset:]`/ID 标签）→ `LyricsPairer`（相同或 ≤250ms 成对）→ `LineLanguageDetector`（逐行：假名→日文；仅汉字→中文简/繁；仅拉丁→英文；混合→MIXED）→ `LyricsNormalizer`；
- 显示：默认简体中文（存在中文行），可切原文/双语对照；语言切换只影响显示侧。
- 官方基线：jellyfin-web 的 `lyricseditor` / `lyricsuploader`（编辑/上传）、`nowPlayingBar`（当前播放）、`playlisteditor`（歌单编辑）——W2 做"可编辑歌词"时对照。

### 4.3 离线方案（MU-6）

- 复用 `Downloader`（DownloadManager + WorkManager + Room）扩展 Audio 分支；下载完成后播 `file://` 本地源（`PlayerItem.mediaSourceUri`）；
- 容量管理：设置页显示占用 + 一键清理（复用下载页样式）；自动缓存策略后置；
- 与阅读器线共用 `DownloaderImpl` 时按 `ARCHITECTURE` §2.4 约定：一条线先做通用化，另一条只加自己的分支。

## 5. 真机验证记录（2026-09-30，Xiaomi Pad 5 / Android 13，`43af8627`）

| # | 项目 | 命令 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 曲库列表 | `uiautomator dump`：`音乐 / 共 94 张专辑`（含 `A/Z\|aLIEz`） | ✅ |
| 2 | 专辑曲目 | 点开 aLIEz：`共 1 首曲目 / 01 aLIEz 4:07` | ✅ |
| 3 | 播放 | `dumpsys media_session`：`state=3 (PLAYING)`；`dumpsys audio`：AudioTrack 44.1kHz | ✅ |
| 4 | 通知 / 锁屏 | `dumpsys notification`：`channel=cinefin_playback, actions=5, vis=PUBLIC, title=aLIEz` | ✅ |
| 5 | 媒体键 | `input keyevent 127/126`：state 2↔3 | ✅ |
| 6 | 后台播放 | HOME 后 state=3，position 持续增长 | ✅ |
| 7 | 音视频互斥 | 视频会话（mpv）→ 音乐起播：日志 `音乐起播：已停止视频会话` + `音乐会话要求 ExoPlayer，重建播放器实例` + `播放器实例已切换，重建播放会话`；session 变为 `state=3` + 正确 metadata | ✅ |
| 8 | 构建门禁 | `:app:phone:compileLibreDebugKotlin ktfmtCheck` | ✅ |
| 9 | 单测 | `:player:local:testDebugUnitTest`（3/3） | ✅ |

> 验证方式说明：因共享真机被并行会话占用，路由入口用**未提交的临时插桩**（NavigationRoot 注册 `MusicModeRoute` + 悬浮入口）完成验证，验证后已还原；正式入口由 R3 统一提交。

## 6. 踩坑库

1. **服务器没有 MusicAlbum 实体**（2026-09-30 实测，Jellyfin 10.11.8）：
   `/Items?includeItemTypes=MusicAlbum&recursive=true` 返回 **0**；`includeItemTypes=Audio` 返回 100+，但音频的 `AlbumId` / `ParentId` 为空，只有 `Album` 名（如 `A/Z|aLIEz`）与 `MusicArtist`（86 个）。
   → 专辑列表必须客户端按 `Album` 分组；不要依赖 `MusicAlbum` 查询或专辑子项接口。
2. **MediaSession 与播放器实例的绑定是快照**：`MediaSession.Builder(service, player)` 绑定的是创建时的实例；实例被 `PlayerHolder` 重建（音乐强制 ExoPlayer）后旧 session 指向已释放实例，通知/锁屏失联（state 卡在旧值）。
   → 已在 `CinefinPlaybackService.onStartCommand` 加"实例切换检测 + 重建会话"；**后续任何会重建实例的路径都要保证服务收到一次 start**。
3. **后台视频页会把音乐打回 mpv**：`PlayerActivity` 的每秒任务经 `viewModel.player` → `playerHolder.player` getter，按偏好 mpv 重建实例（真机日志 `播放内核切换为 mpv，重建播放器实例`）。
   → 已在 `PlayerHolder.player` 加"音乐会话期间固定 ExoPlayer"保护；`musicSessionActive` 置位提前到 `audioSession()` 之前。
4. **`MPVPlayer.removeMediaItems` 未实现**（`NotImplementedError`）：`clearMediaItems()` 在 mpv 上会抛错；停视频时不得让它中断音乐起播。
   → `stopLocal` 容错（stop 必执行，clear 失败仅 DEBUG 日志）；mpv 队列残留媒体项由后续 `setMediaItems` 覆盖。
5. **共享 TrackSelector 的线程错误**：`PlayerHolder` 原实现所有实例共享一个 `DefaultTrackSelector`，实例重建时 `release` 在错误线程执行（`DefaultTrackSelector is accessed on the wrong thread`）。
   → 改为每次 `create()` 新建 selector；实例重建场景（mpv ⇄ exoplayer）不再报错。
6. **adb 启动 PlayerActivity 的 UUID 必须带连字符**：32 位裸 hex（Jellyfin 原始 id）会被 `UUID.fromString` 拒掉 → Activity 立即退出。测试脚本需转成 `8-4-4-4-12`。
7. **仓库无测试基础设施**：`libs.versions.toml` 没有 junit / mockk，各模块无 `src/test`。本次在 `player:local` 用模块内 `testImplementation("junit:junit:4.13.2")` 字面量建立最小单测；**建议 W2 由负责人决定把测试依赖统一进版本目录**（本波该文件只有 R1 可写）。
8. **共享真机冲突**：多会话并行使用同一台小米平板时，会出现"APK 被其他会话覆盖安装、点击/前台被抢占、uiautomator 服务冲突（`UiAutomationService already registered`）"。
   → 真机验证要压缩成"重装后 40 秒内一步完成"的脚本；或与负责人约定串行验证窗口。

## 7. 会话日志

- **2026-09-30 W1-R2**（本会话）：完成 §3 W1 全部条目；替换/新增文件见 git 提交；结论：音乐骨架 + 最小闭环可用，视频互斥链路真机通过；遗留 W2 待办见 §3。
