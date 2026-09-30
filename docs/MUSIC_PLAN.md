# Cinefin 音乐任务线（MUSIC_PLAN）

> 本文件是音乐线的**唯一权威文档**：需求、决策、进度、验收记录、踩坑库都在这里。
> 关联文档：`PROJECT_PLAN.md`（项目总览）、`REQUIREMENTS.md` §5/§11、`ARCHITECTURE.md` §4/§5.2、`PARALLEL_PLAN.md`（波次）、`ROLE_SKILLS.md` §5.2。
> 最后更新：2026-09-30（W3-R2 会话）　分支：`feature/r2-music-lyrics`

## 1. 需求基线（MU-1…MU-9，来源 REQUIREMENTS §5）

| 编号 | 需求 | W1 状态 |
|------|------|---------|
| MU-1 | 独立"音乐"模式 + 抽屉入口，不复用视频播放页 | 🟡 入口 Composable + 路由契约已就绪；抽屉注册由 R3 统一提交 |
| MU-2 | 曲库：专辑 / 艺术家 / 歌曲 / 歌单 / 收藏 / 最近播放 | 🟡 W2 四维浏览（专辑 / 艺术家 / 歌曲 / 歌单）已实现；收藏 / 最近播放待 W3 |
| MU-3 | 单队列 + 手动排序 + "下一首播放" + 队列保存 | 🟡 单队列 + 拖拽排序 + 下一首播放 + 队列面板（跳转 / 移除）已实现；队列保存（Room）待 W3 |
| MU-4 | 直连优先 + 可配置转码档位；gapless 必做 | 🟡 W2 修复"一首播完即停"（`pauseAtEndOfMediaItems`）→ 专辑连播 + gapless 生效；`music_*` 码率偏好待 W3 |
| MU-5 | 歌词：服务端 / 内嵌 / 外挂 LRC + 双语识别 | 🟢 W3 完成：外挂 LRC / 服务端 / 文件缓存三级来源 + 双语配对 + 逐行语言识别 + 语言切换（默认简体中文）+ 滚动同步（§4.2 / §5.3）；内嵌歌词（ID3 USLT）后置 |
| MU-6 | 离线：下载 + 容量管理 + 离线播放 | ⛔ 方案已定（§4.3），实现待 W2/W3 |
| MU-7 | 通知 / 锁屏 / 耳机按键 / 后台播放 + 睡眠定时 | 🟡 通知 / 锁屏 / 媒体键 / 后台长驻已真机验证；睡眠定时待 W2 |
| MU-8 | 全 App 单 MediaSession，音视频互斥 | 🟡 音乐起播停视频已通（含上报）；视频侧接入待 W4（播放器线） |
| MU-9 | 播放进度写回 Jellyfin | 🟢 W2 完成：起播 Start / 10s 周期 Progress / 暂停即报 / 切歌 Stop→Start / 停止 Stop + 续播位置 |

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

### 2.4 W2 本会话决策

| # | 决策 | 理由 |
|---|------|------|
| D8 | 曲库一次请求（`includeItemTypes=Audio`）客户端聚合出专辑 / 艺术家 / 歌曲三个维度，歌单单独请求 | 服务器无 MusicAlbum 实体（§6-1）；减少请求数，三个维度口径一致 |
| D9 | 艺术家用**曲目聚合**（`AlbumArtist` → 首个艺人），不用 `MusicArtist` 实体列表 | 保证"艺术家里的曲目"与歌曲列表口径完全一致；`MusicArtist` 实体（86 个）留作 W3 头像 / 简介数据源 |
| D10 | 歌单走 SDK `PlaylistsApi.getPlaylistItems`（在 `JellyfinApi` 追加 `playlistsApi` 访问器） | 服务器实测歌单端点只有**用户 access token** 可用（API Key 调 `/Playlists/{id}/Items` 报错，§6-9）；App 内天然是用户令牌 |
| D11 | 音乐会话期间关掉 ExoPlayer 的 `pauseAtEndOfMediaItems`，停止音乐 / 视频起播时恢复 | 视频分集依赖"播完一件先停"，音乐必须连播；开着它 gapless 与专辑连播全部失效（§6-10） |
| D12 | 队列编辑（点队列跳转 / 移除）放进附加接口 `MusicQueueEditor`，不动 W0 冻结的 `MusicPlaybackController` | 与 `MusicPlaybackStateSource` 同样的"冻结接口 + 附加接口"处理方式 |
| D13 | 上报状态机：`activeItemId` + ticker 采样位置/时长；切歌在同一条协程里 Stop→Start 保证顺序 | 周期上报与切歌上报需要"上一首的位置/时长"，`onMediaItemTransition` 回调里拿不到 |

### 2.5 W3 本会话决策（歌词，MU-5）

| # | 决策 | 理由 |
|---|------|------|
| D14 | 歌词代码全部落 `modes:music`（`data/lyrics` 纯函数 + `presentation` 面板）；不碰 `player:core`、`NavigationRoot.kt`、`settings.gradle.kts`、`libs.versions.toml` | W3 波次咽喉文件约定（PARALLEL_PLAN §1.3）；歌词只依赖 W2 已有的 `queue` / `positionMs` 两个 StateFlow |
| D15 | 歌词缓存用**文件**（`<filesDir>/lyrics/<itemId>.json` 行文本），不用 Room | W3 波次 R1-OFFLINE 同时在改 Room schema / 版本号，同一时间只允许一条线动数据库；文件缓存已满足"断网时已缓存歌词可用" |
| D16 | 来源优先级按 W3 brief：**外挂 LRC → 服务端 `/Audio/{id}/Lyrics` → 本地缓存**（`ARCHITECTURE` §4.3 写的"服务端优先"以本会话 brief 为准） | 外挂 LRC 是用户手工挑选的版本，理应压过服务端抓取；内嵌歌词（ID3 USLT / Vorbis LYRICS）本会话未做，列入未决项 |
| D17 | 不引入 Lingua / 任何新依赖：逐行语言识别用"假名 → 汉字字形表 → 拉丁 → 混合"规则法 | 三样例实测规则法全部命中；仓库 public + APK 体积红线，Lingua（~1 MB 模型）留到规则法不达标时再评审 |
| D18 | 真歌歌词**不入库**：单测用"行数 / 时间戳结构 / 语言构成"同构的合成样例；真实三样例只做本地临时夹具 + 真机抽验 | 仓库是 public，整篇商业歌词有版权风险；结构与算法覆盖等价（§5.3 记录了真实数据结论） |
| D19 | 配对判据 = **同一 Start** 必配；**≤250 ms 且两侧字形家族不同**（中文 ⇄ 非中文 / 繁 ⇄ 简）才配 | 服务端双语共享精确 Start；加字形家族约束可避免"纯中文歌相邻两句间隔很近"被误配成原文 + 译文（`LyricsPairerTest` 有回归用例） |
| D20 | 显示侧取词优先译文侧：选中的语言命中 `secondary` 就取 `secondary`，否则取 `primary` | 日文原文若全为汉字会被"仅汉字 → 中文"规则判成中文，按"第一行命中"取值会显示成原文而不是中文译文（`LyricsPresenterTest` 有回归用例） |

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

### W2（本会话，已交付）

- [x] 曲库四维浏览：专辑 / 艺术家 / 歌曲 / 歌单（标签页切换，歌单空数据有兜底文案）
- [x] 客户端聚合纯函数 `groupAlbums` / `groupArtists` + 续播换算 `resumePositionMs`（`MusicLibraryGrouping.kt`，4 用例）
- [x] 队列面板：点标题跳转（`MusicQueueEditor.jumpTo`）、长按「≡」拖拽排序（`MusicQueue.move`）、「✕」移除（`removeAt`）
- [x] 歌曲行「下一首播放」菜单（`insertNext`，含媒体源解析）
- [x] 点歌按当前列表整份入队（专辑 / 艺术家 / 歌单 / 全部歌曲四种 `QueueSource`）
- [x] gapless：音乐期间关闭 `pauseAtEndOfMediaItems`，停止后恢复（`PlayerHolder.applyMusicPlaybackTuning`）
- [x] MU-9 上报：Start / 10s Progress / 暂停即报 / 切歌 Stop→Start / 停止 Stop（百分比修正）+ 续播 seek
- [x] 单测：`MusicQueueTest`（4 用例）+ `MusicLibraryGroupingTest`（4 用例）+ `MusicPlaybackMathTest` 扩充（共 13 用例）
- [x] 真机验证（§5.2）
- [x] 文档收口（本节 + §5 / §6 / §7）

### W3 歌词（本会话 `feature/r2-music-lyrics`，已交付）

- [x] 纯函数层：`LrcParser`（`[mm:ss]` / `.x` / `.xx` / `.xxx`、一行多时间戳、`[offset:±ms]`、ID 标签、未同步行）
- [x] 纯函数层：`LyricsNormalizer`（去空白 / 空行 / 署名行 / 重复行 + 时间戳升序）
- [x] 纯函数层：`LyricsPairer`（同 Start 或 ≤250 ms 且字形家族不同；原文在前，中文→译文侧）
- [x] 纯函数层：`LineLanguageDetector`（逐行：假名→日文；汉字→简 / 繁（字形表）；拉丁→英文；汉字+拉丁→混合行）
- [x] 纯函数层：`LyricsDocumentBuilder` + `LyricsPresenter`（语言列表聚合 / 默认简体中文 / 双语对照行 / 当前行索引）
- [x] 数据层：`LyricsRepositoryImpl`（外挂 LRC → 服务端 `LyricsApi.getLyrics` → 文件缓存）+ `MusicModeViewModel` 注入
- [x] 数据层：`LyricsCache`（`<filesDir>/lyrics/<itemId>.json`，转义行文本，断网可用）
- [x] UI：`LyricsSheet`（语言 chip 切换 / 双语对照 / 跟随滚动开关 / 当前行高亮 + 居中滚动 / 点击行 seek）+ 底栏「词」入口
- [x] 单测：41 条（`LrcParserTest` 6 / `LyricsNormalizerTest` 3 / `LyricsPairerTest` 6 / `LineLanguageDetectorTest` 5 / `LyricsPresenterTest` 8 / `LyricsCacheTest` 4 / `LyricsRepositoryTest` 5 / `LyricsSamplesTest` 4；模块合计 45 条全绿）
- [x] 真实三样例抽验（本地临时夹具，不入库）：aLIEz 83 → 40 双语块；Brave Shine 27 → 12；爱的回归线 57 → 57 单语块；默认均为简体中文
- [x] 真机验证（§5.3）
- [x] 文档收口（本节 + §5.3 / §6 / §7）

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

**实现结果（W3-R2，2026-09-30）**

- 组件落地：`modes/music/data/lyrics/` 下 9 个文件（`LyricsModels` / `LrcParser` / `LyricsNormalizer` / `LyricsPairer` / `LineLanguageDetector` / `LyricsDocumentBuilder` / `LyricsPresenter` / `LyricsCache` / `LyricsRepository`），全部纯 Kotlin，可 JVM 单测。
- 服务端 DTO 换算：`LyricLine.start`（ticks）÷ 10000 = ms（实测 aLIEz 首行 28440000 → 2844 ms，Brave Shine 166590000 → 16659 ms）。
- 服务端实测补充：`Metadata` 为 `{}`（无 `Offset` / `IsSynced`），aLIEz / Brave Shine 各有 1 行**没有 Start**（结尾行），`Cues` 恒为空数组。
- 语言列表 = 逐行识别聚合（按 简中 > 繁中 > 日文 > 英文 > 混合 > 其他 排序）+ 末位固定"原文"；切换只改显示侧（`LyricsPresenter.rows`）。
- 滚动同步：`LyricsPresenter.activeIndex(rows, positionMs)`（纯函数）→ 面板 `animateScrollToItem` 居中；位置流 500 ms 采样，索引不变时不触发重组。
- 点击行跳转 = `MusicPlaybackController.seekTo(startMs)`，不改变播放 / 暂停状态。

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

### 5.2 W2 真机验证记录（2026-09-30，Xiaomi Pad 5 / Android 13，`43af8627`）

| # | 项目 | 命令 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 专辑浏览 | `uiautomator dump`：`音乐 / 共 94 张专辑`（客户端聚合） | ✅ |
| 2 | 艺术家浏览 | 切「艺术家」页签：`共 87 位艺术家` | ✅ |
| 3 | 歌曲浏览 | 切「歌曲」页签：`共 100 首歌曲`，行内含专辑名与时长 | ✅ |
| 4 | 歌单浏览 | 写白名单内临时歌单（3 首）：列表 `共 1 个歌单 / 3 首`，详情 3 首顺序正确 | ✅ |
| 5 | 队列面板 | 底栏「播放队列」：`播放队列（3）` + `正在播放` 标记 | ✅ |
| 6 | 拖拽排序 | 长按 `≡` 拖 1 格：`心做し / Last Reunion（正在播放）/ 天下`，当前曲目不变 | ✅ |
| 7 | 下一首播放 | 行内 `⋮ → 下一首播放`：队列 3→4，新条插到当前曲目之后 | ✅ |
| 8 | 点队列跳转 | 点第 3 首：`Sending stop 8711707c…` → `Sending start d2ef9e2b…`，`active item id=2` | ✅ |
| 9 | 切歌 | 底栏「下一首」：`Sending stop d2ef9e2b…` → `Sending start d2ef9e2b…`，`active item id` 2→3 | ✅ |
| 10 | 队列移除 | 点 `✕`：`播放队列（3）→（2）`，当前曲目保持 | ✅ |
| 11 | 同专辑连播（gapless） | 原神 3 首 FLAC 44.1kHz 直连；2s 采样 62 次：第 1 首 86.8s → 第 2 首 ~0s **全程 `state=3`**（无 buffering），无用户操作；日志 `Sending stop 1a49753d…` → `Sending start b8d0326b…` | ✅ |
| 12 | MU-9 周期上报 | 连续 11 条 `Posting progress`，间隔 ~10.05s（位置 50.8s→153.5s 递增） | ✅ |
| 13 | 暂停 / 恢复即时上报 | 暂停后服务器 `Sessions: paused=True, posTicks=625300000`；恢复后 `paused=False` | ✅ |
| 14 | 服务器 Sessions 可见 | `GET /Sessions`：`client=Cinefin, device=21051182C, nowPlaying=Last Reunion…, paused=False`，`posTicks` 递增 | ✅ |
| 15 | 构建 / 格式门禁 | `:app:phone:assembleDebug ktfmtCheck` | ✅ |
| 16 | 单测 | `:player:local:testDebugUnitTest`（9）+ `:modes:music:testDebugUnitTest`（4）= 13/13 | ✅ |

> 验证方式：入口未注册（由 R3 统一提交），本次用**未提交的临时插桩**（`NavigationRoot` 起始页临时指向 `MusicModeRoute`）完成验证，验证后已还原（还原后重跑门禁通过）。
> 歌单验证用写白名单内的临时歌单（建 → 验证 → 删），服务器歌单数已回到 0。
> "听觉无缝隙"的客观证据：全程无 buffering、同专辑同格式直连、无用户操作自动衔接；**听感复核**留给 R4 / 用户验收波（开发会话无法替用户听音）。

### 5.3 W3 歌词真机验证记录（2026-09-30，Xiaomi Pad 5 / Android 13，`43af8627`）

曲目：`aLIEz`（`3b31291f-b0b4-1e86-aae6-94d9f1a60799`，服务器歌词 83 行）。真机占用登记见 `device-lock.md`（22:35–23:05，用完已释放）。

| # | 项目 | 命令 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 打开歌词（服务端来源） | 底栏「词」→ `uiautomator dump`：`来源：服务端　简体中文 / 日文 / 英文 / 混合行` | ✅ |
| 2 | 默认简体中文 | 首屏可见行是中文译文（`一味的固执己见，充满着傲慢…`），不是日文原文 | ✅ |
| 3 | 语言切换（日文） | 点「日文」→ 可见行变日文原文（`受け売り盾に 見下してても…`），假名行 3/4 | ✅ |
| 4 | 双语对照 | 点「双语对照」→ 同一块上下两行（`愛-same-CRIER 愛撫-save-LIAR` + `内心一直在哭泣着…`） | ✅ |
| 5 | 跟随滚动 | 播放 194.6 s 首可见行 `堂々さらした罪の群れと…`（该处起点 177.1 s）→ 播放 218.6 s 首可见行 `Signal,Siehst du das?`（该处起点 218.3 s）；窗口随播放前移 | ✅ |
| 6 | 点击行跳转 | 点 `这就是我的意志`（该块起点 218302 ms）→ 播放位置 227817 ms **精确跳到 218302 ms** | ✅ |
| 7 | 缓存文件落盘 | `run-as … ls -l files/lyrics` → `3b31291f-….json` 4360 B；`head -n 1` = `# cinefin-lyrics 1`；`wc -l` = 84（头 1 + 歌词 83） | ✅ |
| 8 | 断网重载回落缓存 | 关 wifi 后歌词仍显示（内存态）；**未能在真机复现"重新加载 → 本地缓存"**：切歌需要服务端解析媒体源，断网时无法切到别的曲目再切回 → 该路径由单测覆盖（`LyricsRepositoryTest`：服务端抛异常 → `LyricsSource.CACHE`），转 R4 回归 | ⚠️ |

> 验证方式：入口未注册（由 R3 统一提交），本次同样用**未提交的临时插桩**（`NavigationRoot` 起始页临时指向 `MusicModeRoute` + 注册该 composable）完成验证；验证后已还原并重跑 `:app:phone:assembleDebug ktfmtCheck :modes:music:testDebugUnitTest` 全绿。
> 真机副作用均已还原：wifi 关闭 → 已开启（`ping jellyfins.zhangwenkang.com` 通）、`accelerometer_rotation`/`user_rotation` 还原、设备重新安装正式 APK 并停在影阁首页。
> 真实三样例的**完整歌词不落库**（D18）：单测用同构样例；真实数据的行数 / 配对 / 语言结论来自本地临时夹具探针（跑完即删）。

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
9. **歌单端点的鉴权差异**（2026-09-30 W2 实测）：`GET /Playlists/{id}/Items` 用 **API Key**（`X-Emby-Token`）调用报错
   （`Error processing request`，无 body）；换成**用户 access token**（`POST /Users/AuthenticateByName` 换到）后正常返回曲目。
   → App 内登录态天然是用户令牌，SDK `playlistsApi.getPlaylistItems` 无需特殊处理；命令行脚本复现时别用 API Key。
10. **`pauseAtEndOfMediaItems` 是 ExoPlayer 专有 API（`Player` 接口没有）**：`PlayerHolder` 建实例时设 `true`（视频分集"播完先停"）。
    音乐如果不关掉它，一首播完 `playWhenReady` 就被置 false → 专辑连播停住、gapless 失效。W1 没暴露是因为只测了单曲专辑（aLIEz）。
    → `PlayerHolder.applyMusicPlaybackTuning(true/false)`：音乐期间关、停止音乐 / 视频起播时恢复。
11. **"服务器没有歌单"是数据现状，不是能力缺失**：`includeItemTypes=Playlist` 返回 0；歌单接口本身可用（见 §6-9）。
    真机验证用写白名单内的一张临时歌单（3 首，建 → 验证 → 删），不影响媒体库。
12. **服务端 `LyricDto.Start` 是 ticks，不是毫秒**（2026-09-30 W3 实测）：aLIEz 首行 `28440000` = 2.844 s，Brave Shine `166590000` = 16.659 s；
    `milliseconds = ticks / 10000`。按毫秒理解会让整篇歌词的时间轴差 4 个数量级。
13. **"仅汉字 → 中文"会把日文原文判成中文**：日文歌词大量使用汉字（aLIEz 40 行原文里过半是纯汉字）。
    配对本身按"同 Start"不受影响，但**显示侧必须优先取译文侧**（D20），否则"默认简体中文"会渲染成日文原文。
14. **`ktfmtFormat` 之后 `ktfmtCheck` 仍可能报 `Invalid formatting`**（本会话实测）：Gradle 的 up-to-date 判定与格式化结果不同步时，
    逐 source set 再跑一次 `:modes:music:ktfmtFormatMain` / `:modes:music:ktfmtFormatTest`，随后 `ktfmtCheck` 即通过。
    另外：**用补丁工具改过的文件即使 `git diff` 为空，也可能因行尾差异被 ktfmt 判为未格式化**（本会话临时插桩 `NavigationRoot.kt` 还原后即如此）
    —— 跑一次对应 source set 的 `ktfmtFormatMain` 即可消除，不需要改内容。
15. **PowerShell 里 `Select-Object -First N` 会提前终止上游原生命令**：`gradlew ... | Select-Object -First 12` 会在第 12 行时掐断 Gradle 进程，
    构建静默失败。验证命令一律 `*> "$env:TEMP\xxx.log"` 落盘后再 `Select-String`。
16. **真机登记时间要用设备/本机当前时间**：本会话读 `device-lock.md` 时看到"开始时间 23:05"而本机时间是 22:07（登记笔误），
    会导致"是否超过 45 分钟"无法判断。占用时请照抄 `Get-Date` 结果，用完立刻清空登记区。

## 7. 会话日志

- **2026-09-30 W1-R2**（本会话）：完成 §3 W1 全部条目；替换/新增文件见 git 提交；结论：音乐骨架 + 最小闭环可用，视频互斥链路真机通过；遗留 W2 待办见 §3。
- **2026-09-30 W2-R2**（本会话，`feature/r2-music-core`）：完成四维曲库浏览（专辑 / 艺术家 / 歌曲 / 歌单）、
  队列面板（拖拽排序 / 跳转 / 移除 / 下一首播放）、gapless 修复（`pauseAtEndOfMediaItems`）、MU-9 上报补齐与续播；
  新增 13 条 JVM 单测（`MusicQueueTest` / `MusicLibraryGroupingTest` / `MusicPlaybackMathTest` 扩充）；
  真机验证与结论见 §5。遗留：队列持久化（Room）、收藏 / 最近播放、`music_*` 码率偏好 → W3。
- **2026-09-30 W3-R2**（本会话，`feature/r2-music-lyrics`）：完成 MU-5 歌词子系统——三级来源（外挂 LRC → 服务端
  `/Audio/{id}/Lyrics` → 文件缓存）、LRC 解析（多时间戳 / offset / ID 标签 / 未同步行）、清洗去署名行、双语配对
  （同 Start 必配 + ≤250 ms 且字形家族不同）、逐行语言识别（假名 / 汉字简繁 / 拉丁 / 混合）、显示侧默认简体中文 +
  原文 / 繁中 / 日文 / 英文切换 + 双语对照、当前行高亮 + 跟随滚动 + 点击行 seek；新增 41 条歌词单测（模块合计 45）；
  真实三样例（aLIEz 83 → 40 双语块 / Brave Shine 27 → 12 / 爱的回归线 57 → 57 单语）与真机 8 项验证结论见 §5.3。
  决策见 §2.5（D14–D20）。**未决**：内嵌歌词（ID3 USLT / Vorbis LYRICS）、"断网重载→缓存"真机复现（转 R4）、
  歌词编辑 / 上传（对照 jellyfin-web 的 `lyricseditor`，未排期）。
