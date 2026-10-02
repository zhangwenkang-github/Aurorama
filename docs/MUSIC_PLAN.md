# Cinefin 音乐任务线（MUSIC_PLAN）

> 本文件是音乐线的**唯一权威文档**：需求、决策、进度、验收记录、踩坑库都在这里。
> 关联文档：`PROJECT_PLAN.md`（项目总览）、`REQUIREMENTS.md` §5/§11、`ARCHITECTURE.md` §4/§5.2、`PARALLEL_PLAN.md`（波次）、`ROLE_SKILLS.md` §5.2。
> 最后更新：2026-10-02（W23-MUSIC 会话）　分支：`feature/w23-music-player-ui`

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

### 2.6 W3-R3b 本会话决策（用户复测 3 项交互缺陷）

| # | 决策 | 理由 |
|---|------|------|
| D21 | 系统返回键用 `BackHandler(enabled = detail != null)` 在音乐模式内收口（不引入嵌套导航） | 只需"详情 → 音乐主界面"一层；与 `MusicHeader` 左上角返回共用 `closeDetail()`，两个入口行为永远一致；不改 `NavigationRoot.kt`（咽喉文件） |
| D22 | 点歌改为「**先解析被点曲目** → `setQueue` 起播 → 后台按 `musicQueueFillOrder` 补队列」，取代"整份列表串行解析后再起播" | 歌曲页 100 首 = 100 次 `PlaybackInfo` 串行请求，起播被拖到十几秒（真机表现为"点了没反应"），且任一首无媒体源会让整次点击失败（§6-17）；补队列只用冻结接口 `insertNext` + `move`（尾部追加 / 队首前移，见 §6-20），不碰 `player:core`；补入次序抽成纯函数 `musicQueueFillOrder` 并单测（`MusicQueueFillTest` 2 例） |
| D23 | 曲库加载失败**自动重试一次**（1.2 s 后）再进可重试错误态；错误面板标题按来源区分 | 服务器偶发超时（`PROJECT_PLAN` §6 已知风险）时用户不必自己点重试；"曲库加载失败"标题曾被播放 / 歌单失败复用，误导读数（§6-18） |

### 2.7 W21 本会话决策（睡眠定时 / 队列持久化 / 收藏与最近播放）

| # | 决策 | 理由 |
|---|------|------|
| D24 | 睡眠定时放 `modes:music` 的**进程级单例** `MusicSleepTimer`（不落 `player:local`） | 音乐支持后台播放：用户离开音乐页 / 熄屏后定时必须继续生效，播放页内的局部状态做不到；到点只调 `MusicPlaybackController.pause()`，该方法自带"当前是音乐会话且是音乐条目"校验，不会误伤视频；视频侧既有定时仍留在播放页内，两者互不影响。档位与视频侧一致（10 / 20 / 30 / 60 分钟 + 关闭） |
| D25 | 队列持久化用 data 层**独立 `MusicDatabase`**（库名 `music`，version 1：`music_queue_items` / `music_queue_state` / `music_recent` 三张表），不改 `ServerDatabase.kt` | W20 正在改 `servers` 库的 schema / 迁移；独立库让两波改动在文件级别隔离（本波 data 层全部新增文件）。存档由进程级 `MusicQueuePersister` 驱动：队列结构变化立即存、位置每 5 s 节流存；恢复只回填 UI 与续播位置、**不自动出声**，点播放 / 切歌走既有 `setQueue`（MU-9 续播路径），与 gapless 行为不冲突 |
| D26 | 收藏走**服务端白名单**：写 = `userLibraryApi.markFavoriteItem` / `unmarkFavoriteItem`（失败抛异常由 UI 提示），读 = `filters=IsFavorite` + `includeItemTypes=Audio` | 需求「收藏」是服务器用户数据（REQUIREMENTS §11 白名单）；不复用视频侧 `markAsFavorite`（那个会吞异常、只标记待同步，验收"读回"不可靠）。入口 = 音乐顶栏 ♥ + 歌曲行 ⋮ 菜单「收藏 / 取消收藏」，不改导航 IA |
| D27 | 最近播放 = **本地 Room**（`music_recent`，itemId 主键 upsert + `playedAt` 倒序，上限 100）；记录时机 = 播放器队列当前曲目变化（恢复态不算"播放过"） | 最近播放是本地行为数据，不需要写服务器；重复播放只刷新时间并排到最前；曲库快照能查到完整元数据时补专辑 / 艺人 / 时长，查不到（如恢复队列）用播放条目兜底 |
| D28 | 恢复态（重启后未点播放）下队列面板仍可拖拽 / 移除：编辑直接落在恢复快照并立即落盘；存档位置取值 = 活动会话读播放器实时位置、恢复态读快照里写回的续播位置 | 恢复态没有播放器会话，若照读 `positionMs`（=0）会把保存的位置清零（真机回归拦下，见 §6-21）；`musicQueueRemoveAt` 纯函数保证"删当前项索引顺延 / 删前面左移"语义并有单测 |

### 2.8 W23 本会话决策（播放 UI/交互重构 + 桌面歌词悬浮窗）

| # | 决策 | 理由 |
|---|------|------|
| D29 | 全屏播放界面与歌词页做成**音乐模式内的覆盖层**（`nowPlayingOpen` + `lyricsPage` 两个布尔），不新增路由、不改 `NavigationRoot.kt` | 本波红线：导航 IA 与版本目录不动；覆盖层同样满足"底栏进全屏 / 右滑歌词 / 下滑退出"，且状态与队列同源 |
| D30 | 时长来源 = `MusicPlaybackStateSource` 追加 `durationMs`（附加只读观察接口，`MusicPlaybackController` 冻结接口不动）；恢复态用曲库元数据（`runtimeTicks`）兜底 | 底栏与全屏都要"当前时间 / 总时长"；控制器 ticker 已经采样 `lastKnownDurationMs`，加一条 StateFlow 即可，不碰 `player:core` |
| D31 | 播放模式 = 四种模式 ↔ 内核 `repeatMode` + `shuffleEnabled` 的**纯映射**（`MusicPlayMode`），模式随队列持久化（无新增偏好键）；图标循环切换 | 复用既有 `MusicQueue` 字段，gapless / 上报 / 队列恢复全部不用改；顺序播放=OFF（播完停）、列表循环=ALL、单曲循环=ONE、随机=ALL+shuffle |
| D32 | 随机规则：**下一曲**交给内核 `DefaultShuffleOrder`（一轮内每首恰好一次 = 未播优先）；**上一曲**由 `MusicPlaybackHistory`（纯函数 + 进程级 tracker）给出"实际播放过的上一首"的队列索引，找不到才回落内核 | 用户明确要求"上一曲不是随机跳"；内核 shuffle 的 `seekToPrevious` 在手动跳播 / 随机起点下不等于真实历史（真机日志见 §5.6-3） |
| D33 | 桌面歌词 = `modes:music` 的 `MusicLyricsOverlayController`（状态与歌词）+ `app:phone` 的 `MusicLyricsOverlayService`（`WindowManager` 覆盖层 + `ComposeView` 最小生命周期宿主），宿主接口沿用 `PlaybackServiceStarter` 的"模块定义接口、宿主实现"约定；Service 是**普通 Service**（不占前台通知，与播放前台服务共存） | 红线约束：主要逻辑在音乐线，App 层只加 Manifest 权限 / Service / Hilt 绑定；悬浮窗跟随播放与开关状态，退出播放 / 关闭开关即销毁 |

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

### W3-R3b 音乐交互修复（`feature/r3-music-ux-fix`，用户复测反馈 3 项）

- [x] 缺陷 1 · 系统返回键层级：详情（专辑 / 艺术家 / 歌单）内按返回键先回音乐主界面（`BackHandler(enabled = detail != null)`，与左上角返回同一出口）
- [x] 缺陷 2 · 歌曲页点歌不播放：`playSong` 改为「先解析被点曲目 → `setQueue` 立即起播 → 后台按 `musicQueueFillOrder` 补队列」（原实现先串行解析整份列表，歌曲页 100 首 = 100 次 PlaybackInfo，起播被拖住且任一首失败即整次点击失败）
- [x] 缺陷 3 · 曲库加载失败：失败自动重试一次（1.2 s 后）→ 仍失败进可重试错误态；错误面板按来源显示标题（曲库加载 / 播放 / 歌单加载 / 添加到队列）
- [x] 单测：`MusicQueueFillTest`（2 例：补入次序 + 补完后的队列顺序 / 当前曲目），模块合计 **47 项**；首版补队列用"连续 insertNext"会把尾部顺序倒置，被该单测拦下后改为 `insertNext` + `move`（见 §6-20）
- [x] 门禁：`:app:phone:assembleDebug`、`:modes:music:testDebugUnitTest`（47 项）、`ktfmtCheck` 通过
- [x] 真机验证（Pad 5，2026-10-01 00:58–01:05）：返回键 / 左上角返回 / 点歌 3.1 s 起播 + 补队列顺序 / 断网可重试错误态 + 自动重试 / 歌词不回归（见 §5.4）

### W21 音乐扩展（本会话 `feature/w21-music-extras`，已交付）

- [x] 睡眠定时（MU-7）：顶栏月亮入口 + 面板档位 10 / 20 / 30 / 60 分钟 + 关闭；底栏显示「睡眠 mm:ss」倒计时；到点自动 `pause()`（与视频播放页的定时互不影响，见 D24）
- [x] 队列持久化（MU-3 队列保存）：独立 `MusicDatabase`（3 表）+ `MusicQueueStore` / `MusicQueuePersister`（D25）；杀进程 / 重启后恢复队列、当前曲目与播放位置，点播放从保存位置续播；恢复态下队列面板可拖拽 / 移除（D28）
- [x] 收藏（MU-2）：歌曲行 ⋮ 菜单「收藏 / 取消收藏」写服务端白名单；顶栏 ♥ 打开服务端收藏列表（`filters=IsFavorite` 读回）；收藏页取消收藏即时移出
- [x] 最近播放（MU-2）：本地 Room `music_recent`；播放 / 切歌自动记录（重复播放置顶）；顶栏时钟入口按播放时间倒序展示
- [x] 单测：`MusicQueueStoreTest`（4）/ `MusicQueueEditsTest`（5）/ `MusicRecentStoreTest`（2）/ `MusicSleepTimerTest`（1）= 新增 12 项，`:modes:music` 合计 **59 项** 全绿
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（51 项）+ `:modes:music:testDebugUnitTest`（59 项）全绿
- [x] 真机验证（K60，2026-10-02）：睡眠到点暂停 / 杀进程重启续播 / 收藏读写回 / 最近播放顺序 / 歌词 / 队列 / 自动衔接 gapless 不回归（见 §5.5）
- [x] `AppPreferences.kt` 只追加 `pref_music_resume_queue`（默认开，恢复开关；不动既有键）

**遗留**：暂无（队列持久化的偏好开关暂未接设置页 UI，留作后续设置线接入；内置歌词、歌词编辑 / 上传仍按 §3 W3 未决项）。

### W23 音乐播放 UI/交互重构 + 桌面歌词（本会话 `feature/w23-music-player-ui`，已交付）

- [x] A 迷你播放栏：封面 + 「当前时间 / 总时长」+ 播放状态（**移除**「队列 N/N」）；按钮固定为 播放队列 / 词 / 上一曲 / 播放暂停 / 下一曲；点封面与标题区进全屏，各按钮互不触发全屏
- [x] B 全屏播放界面：大封面、歌名 / 歌手 / 专辑、可拖动进度条 + 时间、上一曲 / 播放暂停 / 下一曲、收藏、歌词入口；左上箭头 / **下滑**退出，**右滑**进歌词页；歌词页左滑 / 返回箭头回全屏，**点当前歌词文本**也可进歌词页
- [x] C 播放模式：顺序播放（播完停）/ 列表循环 / 单曲循环 / 随机播放，全屏图标循环切换；随机模式「下一曲随机（未播优先）+ 上一曲回播放历史」
- [x] D 桌面歌词：`SYSTEM_ALERT_WINDOW` 权限引导（全屏播放界面弹引导框 → 系统权限页 → 返回自动开启）+ 客户端设置新增「桌面歌词」开关行；悬浮窗双行（当前句 + 下一句）、可拖动、单击弹设置面板（颜色 / 字号 / 语言 / 锁定 / 关闭）；跟随播放进度更新，退出播放 / 关闭开关即销毁
- [x] 单测：`MusicPlayModeTest`（4）/ `MusicPlaybackHistoryTest`（4）/ `MusicLyricsOverlaySettingsTest`（4）新增 12 项，`:modes:music` 59 → **71 项**；`MusicPlaybackMathTest` 补 1 例回归（起播索引越界）
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（51 项）+ `:modes:music:testDebugUnitTest`（71 项）全绿
- [x] 真机验证（Pad 5 `43af8627` 全项 + K60 `8e875894` 手机形态冒烟，见 §5.6）
- [x] 缺陷修复：换队列过渡窗口里 ticker 按旧播放器索引回写新队列 → `setMediaItems` 抛 `IllegalSeekPositionException`（起播失败），见 §6-24
- [x] `AppPreferences.kt` 只追加 `pref_music_lyrics_overlay*` 5 个键（开关 / 颜色 / 字号 / 语言 / 锁定）

**遗留**：内嵌歌词 / 歌词编辑（W3 未决项）；`player:local` 的 `MusicPlaybackStateSource` 追加了 `durationMs`，`MusicPlaybackControllerImpl` 修了起播索引竞态（两处均在本波红线外，已记录理由）；桌面歌词悬浮窗位置不跨进程持久化（每次开启回到默认位）。

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

### 5.4 W3-R3b 真机验证记录（2026-10-01 00:58–01:05，Xiaomi Pad 5 / Android 13，`43af8627`）

设备由负责人统一调度（`device-lock.md`，v2：禁止超时自动接管）；全部 `adb` 命令带 `-s 43af8627`。

| # | 项目 | 操作 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 系统返回键层级 | 音乐库（`共 94 张专辑`）→ 点专辑 `A/Z\|aLIEz`（详情 `共 1 首曲目`）→ `input keyevent 4` → dump：回到 `音乐 / 共 94 张专辑 / 专辑 艺术家 歌曲 歌单`（未退影阁首页） | ✅ |
| 2 | 左上角返回按钮 | 同一路径改用详情页左上角「返回」→ dump：同样回 `音乐 / 共 94 张专辑` | ✅ |
| 3 | 歌曲页点歌即播 | 「歌曲」页签（`共 100 首歌曲`）点第一首 `Last Reunion (最后的重逢)` → **3.1 s 内** `dumpsys media_session`：`state=3, metadata=Last Reunion…Peter Roe`；position `5772 → 8626 ms` 递增 | ✅ |
| 4 | 后台补队列顺序 | 点歌后队列 `1/34` 持续增长到 45；队列面板顺序 `01 Last Reunion（正在播放）/ 02 心做し / 03 天下 / 04 梦的光点`，与歌曲页列表一致（旧的"整份解析后再起播"改法这里会等 ~100 次 PlaybackInfo） | ✅ |
| 5 | 断网错误态 + 自动重试 | 关 wifi（`wifi_on=0`）→ 重启 App → 抽屉进音乐：出现 `曲库加载失败 / HTTP host unreachable / 重试 / 关闭`（非静默空白）；logcat `MusicModeViewModel: 曲库加载失败，自动重试一次`（01:03:16） | ✅ |
| 6 | 点重试恢复 | 开 wifi（`wifi_on=1`，`ping jellyfins.zhangwenkang.com` 通，RTT ~150 ms）→ 点「重试」→ dump：`音乐 / 共 94 张专辑` 列表恢复 | ✅ |
| 7 | 歌词不回归 | 底栏「词」→ `aLIEz`：`来源：服务端　简体中文 / 日文 / 英文 / 混合行` + 语言 chip；默认显示中文译文；切「日文」后显示日文原文（`決めつけばかり 自惚れを着た…`） | ✅ |

> 设备副作用已还原：wifi 已开、App 已 `force-stop`（无播放会话）、`/sdcard/u.xml` 与本地临时截图已删除；
> `device-lock.md`「当前占用」已写释放时间（01:05）。
> 真机 RTT ~150 ms 也解释了旧实现的表现：歌曲页 100 首串行 `PlaybackInfo` ≈ 15 s+（§6-17）。

### 5.5 W21 音乐扩展真机验证记录（2026-10-02，Redmi K60 / Android，`8e875894`）

设备由负责人统一调度（`device-lock.md`，K60 归本会话；Pad 5 归 W20 未占用）；全部 `adb` 命令带 `-s 8e875894`。

| # | 项目 | 操作 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 睡眠定时到点暂停 | 顶栏月亮 → 选「10 分钟」→ 底栏 `队列 1/69 · 正在播放 · 睡眠 09:56`（逐秒递减）→ 14:11:22 logcat `MusicSleepTimer$select: 音乐睡眠定时到点，暂停播放` + `dumpsys media_session` `state=PAUSED(2)`、position=120338；底栏睡眠文案消失 | ✅ |
| 2 | 队列持久化（杀进程） | 播放中（position 28630 ms）→ `am force-stop` → 重启进音乐：`队列 1/69 · 上次播放到 0:35` → 点播放：`state=BUFFERING(6) position=35231` → 从 35 s 续播（非 0） | ✅ |
| 3 | 队列持久化（重装 + 暂停点） | 暂停于 2:07 → `install -r` 重装重启 → `队列 5/100 · 上次播放到 2:00` → 点播放：`position=120465`（2:00 续播）→ 点暂停：底栏 `队列 5/100 · 已暂停` | ✅ |
| 4 | 数据库快照 | `run-as` 拉取 `music` 库（WAL）→ `music_queue_items` 100 项（position 0..99）、`music_queue_state` `currentIndex=3 positionMs=102173 repeatMode=OFF shuffle=0 source=MANUAL`、`music_recent` 4 条按 `playedAt` 倒序 | ✅ |
| 5 | 收藏写入 + 读回 | 歌曲页 `Last Reunion` ⋮ → 「收藏」→ 顶栏 ♥ 收藏页：`共 1 首曲目 / Last Reunion (最后的重逢)`（服务端 `filters=IsFavorite` 读回）→ ⋮「取消收藏」→ 收藏页 `共 0 首曲目 / 还没有收藏的曲目`（测试数据已还原） | ✅ |
| 6 | 最近播放顺序 | 依次播放 `Last Reunion` → `心做し` → `天下` → 顶栏时钟：`最近播放 / 共 3 首曲目` 顺序 `天下 / 心做し / Last Reunion`（最后播的排最前）；重复播放只置顶不新增 | ✅ |
| 7 | 恢复态队列编辑 | force-stop 重启（恢复态 `队列 4/99 · 上次播放到 0:07`）→ 队列面板移除 1 项：`播放队列（98）`、当前索引 4→3；关闭面板底栏 `队列 3/98 · 上次播放到 0:07`（位置未清零）→ 再次重启仍 `队列 3/98 · 上次播放到 0:07`（编辑与位置都已落盘） | ✅ |
| 8 | 歌词不回归 | 底栏「词」：`来源：服务端　简体中文 / 混合行` + 语言 chip + 滚动行 + 点击行 seek（`position=207180`） | ✅ |
| 9 | 队列面板不回归 | `播放队列（69）`→（100）→（98）条目、当前曲目标「正在播放」、拖拽锚点 `≡` 与移除 `✕` 就位 | ✅ |
| 10 | gapless / 自动衔接不回归 | 歌词点最后一行把「天下」seek 到 3:35 → 播放完**无用户操作**自动切「梦的光点」（active item 2→3），采样第 4→5 次之间 `state` 全程 `PLAYING(3)`、新曲目 `position=15 buffered=44489`（无暂停 / 无中断） | ✅ |
| 11 | 稳定性 | 整轮多次 `logcat` 检查无 `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out` / `UnsatisfiedLinkError` | ✅ |
| 12 | 门禁 | `assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（51）+ `:modes:music:testDebugUnitTest`（59）全绿 | ✅ |

> 设备副作用已还原：App `force-stop`、`/sdcard/w21_ui.xml` 清理、收藏测试条目已取消（服务器收藏数回 0）；未改 prefs / `wm size` / 旋转 / Wi-Fi。
> 过程事故：恢复态编辑队列时曾把保存位置清零（`persist` 照读无会话的 `positionMs=0`）——真机回归拦下，修复为 `queuePersistPositionMs`（活动会话 = 实时位置 / 恢复态 = 快照位置）+ 单测，第 7 项为修复后复验。

### 5.6 W23 真机验证记录（2026-10-02，Pad 5 `43af8627` 全项 + K60 `8e875894` 冒烟）

设备由负责人统一调度（`device-lock.md`，W23 登记 Pad 5 + K60）；全部 `adb` 命令带 `-s`；安装包 = 本会话 `assembleDebug` 产物（`phone-libre-arm64-v8a-debug.apk`）。

| # | 项目 | 证据 | 结果 |
|---|------|------|------|
| 1 | 播放栏封面 / 时间 / 无队列号 / 五键顺序 | Pad 5 dump：封面 `content-desc=aLIEz [225,2425][333,2524]`、`aLIEz` + `0:04 / 4:07 · 正在播放`；五键 bounds 依次 `播放队列 [1114,2457] → 词 [1217] → 上一曲 [1312] → 暂停 [1411] → 下一首 [1510]`；无「队列 N/N」文案 | ✅ |
| 2 | 点播放栏进全屏 | 点封面中心 → dump：`退出全屏 [279,117]`、大封面 `[539,569][1259,1289]`、`SawanoHiroyuki[nZk], 瑞葵 · A/Z\|aLIEz`、当前歌词行、`0:15 / 4:07`、底部四键（顺序播放 / 收藏 / 歌词 / 桌面歌词） | ✅ |
| 3 | 歌词页进入 / 返回 | 右滑（`input swipe 700 1500 → 1500 1500`）→ 歌词页（`返回全屏播放` + `来源：服务端　简体中文 / 日文 / 英文 / 混合行` + 语言 chip）；歌词页左滑 → 全屏；点「歌词」键 → 歌词页；点当前歌词文本（`clickable=true`）→ 歌词页；返回箭头 → 全屏 | ✅ |
| 4 | 四种播放模式 | 连点模式键：`顺序播放 → 列表循环 → 单曲循环 → 随机播放 → 顺序播放`；日志 `播放模式切换：X（repeat=ALL/ONE/OFF shuffle=…）` 四条齐全 | ✅ |
| 5 | 顺序播放（播完停） | 顺序模式走到队列末曲（`只要平凡`）→ 再点下一曲不前进 → 进度条拖到末尾 → 12 s 后 `state=STOPPED(1) position=246037`（曲长 4:06）、`speed=0.0` | ✅ |
| 6 | 列表循环 | 列表模式在末曲 `aLIEz` 点下一曲 → 回到队首 `Last Reunion`（6 次 next 走完整队列后再次回卷） | ✅ |
| 7 | 单曲循环 | 单曲模式：`来自天堂的魔鬼` 拖到末尾 → 8 s 后同一首 `position=5831 state=3`（从头续播，未切歌） | ✅ |
| 8 | 随机：下一曲随机（未播优先） | 日志 `随机下一曲：当前 index=0 → 58 → 72 → 76 → 84`（非顺序跳）+ 曲名 `Last Reunion → 只要平凡 → 来自天堂的魔鬼 → 水龙吟 → 篇章` 互不重复 | ✅ |
| 9 | 随机：上一曲回历史 | 日志 `随机上一曲：回到历史曲目 index=76（当前 index=84）`、`index=72（当前 index=76）`；曲名 `篇章 → 水龙吟 → 来自天堂的魔鬼` 与播放历史一致 | ✅ |
| 10 | 桌面歌词权限引导 | 点「桌面歌词」→ 引导框（`开启桌面歌词` + 说明 + `取消` / `去授权`）→ 点去授权 → `topResumedActivity=com.android.settings/.Settings$OverlaySettingsActivity`；测试侧用 `appops set … SYSTEM_ALERT_WINDOW allow` 等效授权 → 返回 App 自动开启（`桌面歌词开关：开启` + `悬浮窗已显示`） | ✅ |
| 11 | 悬浮窗双行随播放更新 | 日志 `桌面歌词更新：当前句「一味的固执己见…」· 下一句「但有时也会躲藏在那被悲哀所羞辱的镜子里」`（aLIEz，后续多组随进度推进） | ✅ |
| 12 | 悬浮窗拖动 / 单击面板 | 窗口（`dumpsys window`：`appop=SYSTEM_ALERT_WINDOW ty=APPLICATION_OVERLAY fl=NOT_FOCUSABLE`）从 `(24,853)` 拖到 `(610,1510)`；单击后窗口高度 `204 → 451`（面板展开） | ✅ |
| 13 | 面板五项 | 日志：`颜色：松石` / `字号：大`（再点 `特大`）/ `语言：日文`（悬浮窗同步变日文原文）/ `锁定：已解锁 → 已锁定`（锁定时拖动窗口 frame 位置不变）/ `关闭`（`桌面歌词开关：关闭` + `悬浮窗已销毁` + `dumpsys activity services` 该 Service 记录 0） | ✅ |
| 14 | 不回归 | 歌词面板（`来源：服务端　简体中文` + chip）、队列面板（`播放队列（6）` + `正在播放` + `≡`/`✕`）、睡眠定时面板（10/20/30/60）、force-stop 重启后队列恢复（`4:06 / 4:06 · 上次播放` + 播放键）、最近播放 / 收藏入口、gapless 自动衔接均正常 | ✅ |
| 15 | 稳定性 | 整轮 logcat 无 `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out`；本波真机拦下的 `IllegalSeekPositionException`（§6-24）已修复并复验 | ✅ |
| 16 | K60 手机形态冒烟 | 底栏（手机 IA）→ 音乐 → 播放：封面 48dp + `Gate of Steiner -Piano- / 2:56 / 3:50 · 上次播放` + 五键顺序一致；点封面进全屏（收藏 / 歌词 / 桌面歌词）；授权后开启桌面歌词 → `dumpsys window` 出现本应用 `APPLICATION_OVERLAY` 窗口；再点关闭后窗口消失 | ✅ |

> 设备副作用：两机 App 已 force-stop、`/sdcard/w23_*.xml` 已清理；未改 `wm size` / `wm density` / 旋转 / Wi-Fi；Pad 5 在测试中经 appops 授予悬浮窗权限（等同系统页开关，保留）。
> 测试期残留的新功能偏好（Pad 5）：`桌面歌词=关`、`颜色=松石`、`字号=特大`、`语言=日文`、`锁定=开`（均为本波新增键，用户下次开启面板可直接改；未做 prefs 文件改写，避免 W18 的 run-as 截断事故）。

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
17. **"歌曲页点歌没反应"的根因是整份列表串行解析**（2026-10-01 W3-R3b 定位）：`MusicTrackResolver.toPlayerItems` 会为列表中**每一首**曲目各发一次
    `PlaybackInfo`（音频条目在列表接口里不带播放地址）；歌曲页 100 首即 100 次串行请求，期间没有任何 UI 反馈 = "点了没反应"，
    且任意一首拿不到媒体源时整次点击直接失败（错误面板还顶着"曲库加载失败"的标题）。
    → 起播路径改为"先解析被点曲目（1 次请求）→ 起播 → 后台按顺序补队列"，单曲失败只跳过该曲（视频线 `PlaylistManager` 的"按需逐集解析"是同一思路）。
    真机量化：本服务器 RTT ≈ 150 ms，100 首串行即 15 s 起步；改后点歌 **3.1 s 内**出声（含 adb 采样往返，§5.4）。
18. **详情页的系统返回键不会自动走页内返回**：NavHost 弹栈直接退到上一张路由（首页）。页内子状态（详情 / 面板）必须自己用
    `BackHandler(enabled = 子状态 != null)` 拦截；`modes:music` 因此新增 `libs.androidx.activity.compose` 依赖（版本目录已有别名，未改 `libs.versions.toml`）。
19. **ktfmt 也检查 `.kts`**：给 `modes/music/build.gradle.kts` 加依赖后，只跑 `:modes:music:ktfmtFormatMain` 不够（它只覆盖 Kotlin 源码），
    构建脚本的 CRLF/LF 差异仍会让 `ktfmtCheck` 报 `Invalid formatting` → 跑该模块的 `ktfmtFormat`（不带 `Main`）后再 `ktfmtCheck`。
20. **`MusicQueue.insertNext` 是"下一首播放"语义，不能当"追加"连用**（2026-10-01 W3-R3b 单测拦截）：它固定插到 `currentIndex + 1`，
    连续插 A→B 得到 `[当前, B, A, …]`——补队列时尾部曲目会被倒置。想按列表顺序补队列必须 `insertNext(item)` 后再 `move(insertedAt, 目标位置)`
    （追加到队尾 `items.size - 1` / 前移到队首 `0`）；单测 `MusicQueueFillTest` 用 `MusicQueue` 纯函数模拟整段补入过程做回归。
21. **恢复态编辑队列会把保存位置清零**（2026-10-02 W21 真机回归拦下）：`MusicQueuePersister.persist` 原来无条件读
    `playbackController.positionMs`，恢复态没有播放会话时该值为 0，于是"移除一项"就把 2:07 的存档位置覆盖成 0:00。
    → 抽成纯函数 `queuePersistPositionMs(liveQueuePresent, livePositionMs, restoredPositionMs)`（活动会话 = 实时位置 / 恢复态 = 快照里写回的续播位置）并加单测；
    **凡是"恢复态可编辑、后台又持续落盘"的路径都要明确位置来源**。
22. **Timber `DebugTree` 的 tag 是调用点标签而不是类名**（2026-10-02 W21）：`MusicSleepTimer.select` 里的 `Timber.i` 输出 tag =
    `MusicSleepTimer$select`，用 `adb logcat -s MusicSleepTimer:V` 过滤不到（一度以为日志没打）。→ 取证时先全量 `logcat -d | Select-String '关键字'`，
    或按 `--pid=<app pid>` 过滤。
23. **PowerShell 的 `>` 会破坏二进制**（2026-10-02 W21）：`adb exec-out run-as … cat databases/music > x.db` 拉 Room 库会写出损坏文件；
    且 `run-as` 往 `/sdcard/Android/data/...` 复制会 `Permission denied`。→ 用 Python `subprocess.run(…, capture_output=True)` 读 `exec-out` 字节流再 `open(..., 'wb')` 落盘；
    Room WAL 模式要连同 `-wal` 一起拉取才能读到最新快照。
24. **换队列过渡窗口里 ticker 会用旧索引回写新队列 → 起播抛 `IllegalSeekPositionException`**（2026-10-02 W23 真机拦下）：
    `MusicPlaybackControllerImpl.setQueue` 先把 `_queue` 换成新队列（例如 1~6 首），而 500 ms 位置轮询 / `onMediaItemTransition`
    仍在读**旧播放器**（100 首随机队列，`currentMediaItemIndex=84`）并回写 `currentIndex`；随后 `player.setMediaItems(items, latest.currentIndex, 0)`
    直接抛 `IllegalSeekPositionException` → 音乐起播失败（真机日志 `音乐起播失败` + 该异常）。
    → 起播索引统一走 `normalizeStartIndex(latest.currentIndex, latest.items.size)`，且 ticker / 媒体项切换只在
    `queue.items.size == player.mediaItemCount` 时回写索引；`MusicPlaybackMathTest` 增加 `normalizeStartIndex(84, 1) == 0` 回归用例。
    **凡"状态流会被另一个线程按旧快照回写"的路径，喂给内核前都要再归一化一次。**
25. **悬浮窗取证用 `dumpsys window` 而不是 uiautomator**（2026-10-02 W23）：覆盖层窗口 `FLAG_NOT_FOCUSABLE`，
    `uiautomator dump` 抓的是有焦点的窗口（App 主界面），看不到悬浮窗节点。
    → 用 `dumpsys window windows` 看窗口的 `ty=APPLICATION_OVERLAY` / `appop=SYSTEM_ALERT_WINDOW` / `Frames:`（拖动/面板展开的坐标证据），
    用 `dumpsys activity services <pkg>` 看 Service 存活，用 logcat 看两行歌词内容；`input tap/swipe` 可以直接命中覆盖层窗口（坐标取窗口 frame 内）。
26. **手机（K60 411dp）播放栏仍然放得下五键**（2026-10-02 W23）：封面 48dp + 标题/时间列 ≈107dp + 5×44dp 图标键，
    行内边距压到 `Space2`；窄屏下时间用 `MonoDataSmall` 单行省略，避免换行撑高底栏。

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
- **2026-10-01 W3-R3b**（本会话，`feature/r3-music-ux-fix`）：修用户复测的 3 项音乐交互缺陷——① 详情页系统返回键改走页内返回
  （`BackHandler`，与左上角返回同一个 `closeDetail()` 出口）；② 歌曲页点歌改为"先解析被点曲目立即 `setQueue` 起播 → 后台按
  `musicQueueFillOrder` 补队列"（原实现先串行解析整份列表，100 首 ≈ 15 s 且无任何反馈，任一首坏文件整次点击失败）；
  ③ 曲库加载失败自动重试一次（1.2 s）后进可重试错误态，错误面板标题按来源区分（曲库 / 播放 / 歌单 / 入队）。
  新增 `MusicQueueFillTest` 2 例（**首版"连续 insertNext"会把尾部顺序倒置，被该单测拦下**，改为 `insertNext` + `move`），
  模块 47 项全绿；真机 7 项验证见 §5.4（返回键 / 左上角返回 / 点歌 3.1 s 起播 + 队列顺序 / 断网错误态 + 重试恢复 / 歌词语言切换）。
  决策 §2.6（D21–D23），踩坑 §6-17～20。**未决**：歌词文本里带 `[00:00:00]` 前缀的纯音乐提示行（服务端原样返回，未做清理，非本次回归）。
- **2026-10-02 W21-MUSIC**（本会话，`feature/w21-music-extras`）：完成三项——①睡眠定时（进程级 `MusicSleepTimer`，10/20/30/60 分钟档，
  到点只暂停音乐、与视频侧定时互不影响，底栏倒计时）；②队列持久化（data 层独立 `MusicDatabase` 三表 + `MusicQueuePersister` 结构变化即存 / 位置 5 s 节流存，
  重启恢复当前曲目与位置、点播放续播；恢复态仍可拖拽 / 移除）；③收藏（服务端 `UserFavoriteItems` 写 + `filters=IsFavorite` 读回，顶栏 ♥ + 行内菜单）与
  本地最近播放（Room `music_recent`，倒序、重复置顶，顶栏时钟入口）。新增 12 条单测（模块 59 项），门禁 `assembleDebug`（含 TV）+ `ktfmtCheck` +
  app 51 项 + music 59 项全绿；K60 真机 12 项验证见 §5.5（含真机拦下的"恢复态编辑清零位置"修复，踩坑 §6-21）。
  决策 §2.7（D24–D28）。**未决**：`pref_music_resume_queue` 开关暂未接设置页 UI；内置歌词 / 歌词编辑仍按 §3 W3 未决项后置。
- **2026-10-02 W23-MUSIC**（本会话，`feature/w23-music-player-ui`，起点 master `7f49d88`，四组提交 A `74214e7` / B `0124828` / C `65c6095` / D `179df99`）：
  **A 迷你播放栏**封面 + 当前/总时长（去掉「队列 N/N」）+ 五键固定顺序（播放队列 / 词 / 上一曲 / 播放暂停 / 下一曲），点封面或标题区进全屏；
  **B 全屏播放界面**（大封面 / 歌名歌手 / 可拖动进度条 / 传输键 / 收藏 / 歌词入口）+ 歌词页（右滑进、左滑或箭头返回、点当前歌词文本也可进）；
  **C 四种播放模式**（顺序播完停 / 列表循环 / 单曲循环 / 随机）+ 随机下一曲交给内核 shuffle（未播优先）、上一曲按 `MusicPlaybackHistory` 回实际播放历史；
  **D 桌面歌词悬浮窗**（`SYSTEM_ALERT_WINDOW` 引导 + 客户端设置开关行 + 双行歌词 + 拖动 + 单击面板的颜色/字号/语言/锁定/关闭，退出播放或关闭开关即销毁）。
  新增 12 条单测（`MusicPlayModeTest` / `MusicPlaybackHistoryTest` / `MusicLyricsOverlaySettingsTest`），`:modes:music` 71 项；
  门禁 `assembleDebug`（含 TV）+ `ktfmtCheck` + app 51 项 + music 71 项全绿；真机 16 项见 §5.6（Pad 5 全项 + K60 手机形态冒烟）。
  决策 §2.8（D29–D33），踩坑 §6-24～26。**未决**：内嵌歌词 / 歌词编辑；悬浮窗位置不跨进程持久化；`player:local` 两处改动（`durationMs` 观察 + 起播索引竞态修复）已在红线外记录理由。
