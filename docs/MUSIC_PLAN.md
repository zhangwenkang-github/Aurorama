# Cinefin 音乐任务线（MUSIC_PLAN）

> 本文件是音乐线的**唯一权威文档**：需求、决策、进度、验收记录、踩坑库都在这里。
> 关联文档：`PROJECT_PLAN.md`（项目总览）、`REQUIREMENTS.md` §5/§11、`ARCHITECTURE.md` §4/§5.2、`PARALLEL_PLAN.md`（波次）、`ROLE_SKILLS.md` §5.2。
> 最后更新：2026-10-02（W35-MUSIC-EXTRAS 会话）　分支：`feature/w35-crossfade-rg`

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

### 2.9 W24 本会话决策（音乐 UI 细化）

| # | 决策 | 理由 |
|---|------|------|
| D34 | 悬浮窗自动隐藏 = `MusicLyricsOverlayContent` 内部 `interacting`（3 秒无操作落回 false）+ 纯函数 `overlayChromeVisible(locked, interacting)`；**背景 / 边框恒受锁定抑制**，设置工具条自带一层落底半透明底板 | 「无操作 3 秒隐藏、触摸恢复、锁定保持隐藏」三个状态只用一个布尔表达；工具条底板与歌词背景分离，锁定后单击仍能看清 5 个图标（真机日志见 §5.7-1） |
| D35 | 悬浮窗位置持久化 = `AppPreferences` 追加 `pref_music_lyrics_overlay_x/y`（-1 = 未记录）；拖动结束 / 布局完成后 `commitPosition()` 夹进屏幕再落盘 | 位置只在“拖动结束”与“屏幕旋转后越界”两个时机写入，避免拖动过程中每个 move 事件都写 SharedPreferences；`AppPreferences.kt` 只追加，不重排既有键（本波红线允许） |
| D36 | 无歌词回落 = 控制器状态补 `title/artist`，显示侧 `overlayDisplayLines` 优先歌词、否则「歌名 / 歌手（缺失给占位）」；歌手来源 = `MusicModeViewModel` 把曲库快照里当前曲目的 artist 喂入控制器（内存 map），控制器再保留一次按需曲库加载兜底 | 真机首版只靠控制器懒加载 500 首快照，歌手要等网络请求回来（快速切歌时一直显示"暂无歌词"占位，见 §5.7-3）；ViewModel 已经持有曲库快照，直接喂入即时且离线可用 |
| D37 | 全屏播放页按可用高度分两套布局：`maxHeight ≥ 620dp` 单列（W23 原样），否则两栏紧凑（左封面 / 右控制） | K60 横屏可用高度 411dp，W23 单列大封面会把进度条、歌词、功能键全部挤出屏幕（真机实测，见 §6-27）；两栏后 2–3 行歌词、细轨道进度条、播放列表入口全部可见，Pad 5 / 竖屏手机仍走原单列 |
| D38 | 进度条改自绘 `MusicProgressBar`：4dp 圆头轨道 + 18dp 白色圆形拖点 + 同色 radial glow；整条 36dp 触控带按下 / 拖动都 seek 并消费手势 | Material3 `Slider` 的浮标是竖条（用户明确要求去掉）；自绘同时满足"细轨道 + 圆形发光拖点（Prism / 音乐皮肤）"，消费手势后不会误触全屏左滑呼出队列 |
| D39 | 歌词页居中 = 上下 `contentPadding = 视口一半` + `centerItem()`（先 `animateScrollToItem`，再按目标行实际高度 `animateScrollBy` 微调）；去掉当前行前的「杠」，改用 `media.bright` 颜色 + 32sp / 21sp 字号区分 | 旧实现首帧 `viewportSize.height == 0`，滚动偏移退化成"当前行贴顶"（用户复现）；两段式滚动对首行 / 末行 / 双语多行都成立（真机实测居中后 `offset=332 ≈ (840-177)/2`，见 §5.7-6） |

### 2.10 W25 本会话决策（内嵌歌词探测 / 本机歌词编辑 / 悬浮窗时长档位 / 队列恢复开关）

| # | 决策 | 理由 |
|---|------|------|
| D40 | **内嵌歌词：先只读探测再决定，结论=服务器已覆盖 → 不做客户端标签解析**（证据见 §4.2；brief 的条件是"若服务器未覆盖"才补） | Jellyfin 10.11.8 扫描时由 `AudioFileProber.FetchDataFromTags(tryExtractEmbeddedLyrics)` 把内嵌歌词（ATL `track.Lyrics`）落为 metadata `.lrc`，`/Audio/{id}/Lyrics` 直接返回；客户端服务端来源链已能拿到。客户端解析需要离线文件字节（在线流式播放拿不到），另行评审 |
| D41 | 本机覆盖落 `<filesDir>/lyrics/override/<itemId>.lrc`（独立子目录、LRC 文本、UTF-8），来源链变「**本机覆盖 > 外挂 LRC > 服务端 > 缓存**」 | 与缓存同目录树但生命周期独立（清理缓存不误删用户编辑）；LRC 文本可读、与导入导出同构；沿用 D15 文件存储不用 Room |
| D42 | 编辑态模型 `LyricEditLine(id, timeText, text)`：时间戳编辑期保留文本，保存时统一校验（`mm:ss.x/.xx/.xxx`，留空 = 未同步行） | 边输边改不打断输入；解析 / 格式化 / 文档预填全部抽纯函数（`LyricsEditorModel`）可 JVM 单测 |
| D43 | 导入 `.lrc` 在 `modes:music` 内用 `rememberLauncherForActivityResult(OpenDocument)`（任意 MIME 通配）实现，不新增 app/phone 接线 | `androidx.activity.compose` 已是音乐模块依赖（W3-R3b 引入）；`.lrc` 的 MIME 因文件管理器而异，通配避免被过滤；读入后按 UTF-8 → GBK 回退解码（与仓库/缓存同口径 `LyricTextCodec`） |
| D44 | 悬浮窗「保持显示时长」= `LyricsOverlayIdle` 五档（2 / 3 / 5 / 10 秒 + 常显），工具条新增第 6 个图标按钮循环切换；新增偏好键 `pref_music_lyrics_overlay_idle`（默认 `3s`，保持 W24 行为） | 常显 = `durationMs = null`，`LaunchedEffect` 直接保持背景 / 边框；只追加键不重排；原五键的顺序与语义不动（新增键加在原键组尾部） |
| D45 | 队列恢复开关直接落客户端设置的「音乐」行组（与桌面歌词同一组），只加一个 `PreferenceSwitch` + 两条字符串 | W21 已把开关语义做全（`MusicQueuePersister.load()/persist()`：关 = 不读不写），设置页只缺入口；沿用现有组件 / 文案风格（红线"settings 只加设置行"） |

### 2.11 W28 本会话决策（逐字歌词 / 提示行清理 / 编辑时间轴）

| # | 决策 | 理由 |
|---|------|------|
| D46 | 逐字模型 = `LyricLine.words: List<LyricWord>`（可空默认空）+ `LyricsPresenter.wordHighlights`（纯函数，词按"自身起点 → 下一词起点"折算 0..1 进度，末词用下一行起点或 500 ms 兜底）；**无逐字数据（空列表）时显示侧完全走原整行高亮路径** | 整行高亮 / 滚动同步是 W3 已验收行为，逐字必须是"附加层"：`LyricsRow.words` 为空时新旧渲染完全同构（代码层 `wordHighlightedText` 返回 null 即回落），不引入新的滚动逻辑 |
| D47 | 逐字来源两路：**增强 LRC `<mm:ss.xx>` 标签**（`LrcParser.parseWords`，外挂 / 本机覆盖 / 导入共用）+ **服务端 `Cues`**（`mapServerLyrics` 按 `position`/`endPosition` 截子串、ticks/10000 换算，越界跳过）；标签文本必须从行文本剥离 | 服务端实测全库 100 首 `Cues` 全空（§4.4），本库唯一可得样本 = 增强 LRC；`Cues` 映射为数据链就绪 + 单测覆盖（未来服务器 / 其他库可用）。剥离标签是硬要求：不剥离会把 `<00:05.00>` 原样显示给用户 |
| D48 | 编辑增强 = **整段偏移**（`shiftLyricEditLines`）与**单行微调**（`nudgeLyricEditLine`），±100 ms 档 + 自定义毫秒输入（`parseLyricOffsetMs`）；逐字数据随行一起平移（`shiftLyricWords`），保存时校验"首词起点 == 行时间"否则丢弃逐字（`lyricWordsConsistentWithLine`） | 时间轴编辑必须与逐字高亮同源：行时间动了而词时间不动，高亮会错位到行外；校验失败宁可回落整行（D46）。保存仍写本机覆盖（`encodeLrc` 对有效逐字写回 `<mm:ss.xxx>` 增强 LRC），不写服务器 |

### 2.12 W30 本会话决策（音乐音效 EQ / ReplayGain / 交叉淡化）

| # | 决策 | 理由 |
|---|------|------|
| D49 | 三项音效全部落在 **Exo 音频链**：新增 `MusicAudioEffectsProcessor`（5 段 peaking EQ + ReplayGain 逐样本增益）挂进 `CinefinRenderersFactory`（与 `AudioDelayProcessor` 同链，仅音乐会话处理）；**不改**"音乐固定 ExoPlayer"（W1 D3）。mpv 的 `af=equalizer` / `replaygain=no\|track\|album` 只在文档记录为"能力存在但音乐不可达" | mpv 原生能力对音乐路径无效（`PlayerHolder.audioSession()` 强制 Exo，后台视频页会把实例打回 mpv）；改 D3 会连带通知 / MediaSession / 队列 / gapless，超出本波红线。Exo 侧有 `AudioDelayProcessor` 先例（含"必须覆写 `isActive`、关 offload"两条踩坑） |
| D50 | ReplayGain 数据源 = **本机覆盖文件 > 音频内嵌标签**：覆盖文件 `<filesDir>/replaygain/<itemId>.txt`（`track=-6.0` / `album=-12.0`）；内嵌标签对 `/Audio/{id}/stream?static=true` 发只读 Range（前 128 KB）解析 FLAC VorbisComment / ID3v2 TXXX；三态 `off/track/album`，**专辑档缺 album 标签回落曲目档**，无标签不改变音量；结果按 itemId 缓存（含负缓存） | 全库 100 首 FLAC 只读实测 **0 个 `REPLAYGAIN_*` / `R128_*` 标签**（服务器 JSON 同样没有），mpv 自动 replaygain 对音乐不可达 → 客户端自解析是唯一路径；覆盖文件给无标签文件一个合法的手动增益入口（也是真机"三态有值可测"的数据源，先例见 W25 歌词本机覆盖 D41） |
| D51 | 交叉淡化交付**单实例近似**：曲尾淡出 + 曲首淡入（`Player.volume` 等功率包络，100 ms 采样、只在变化 > 0.002 时写、关闭 / 会话结束立即复位），档位 关 / 2 / 4 / 6 秒；**不做双实例真交叉（重叠）**，文档写明差异并列为未排期 | 双实例交叉会破坏单 Exo 实例 + 单 MediaSession + gapless + 上报链路；近似方案与 gapless 不冲突、默认关闭、可完整真机验收（曲线数值可证）。"真交叉"复杂度高，按 brief 分阶段 |

### 2.13 W35 本会话决策（真交叉淡化调研结论 + ReplayGain 收尾）

| # | 决策 | 理由 |
|---|------|------|
| D52 | **双实例真交叉淡化不在本波实施**：交付只读调研 + 三阶段实施方案（§4.6），正式交付仍为 W30 D51 单实例近似（默认关、可回退）。若未来开波，按"阶段 1 双 deck 基础（2–3 人日）→ 阶段 2 交叉引擎（3–4 人日）→ 阶段 3 会话切换与双机回归（3–5 人日）"推进，合计 8–12 人日 | 代码证据：`PlayerHolder` 全进程单实例；`CinefinPlaybackService` 以 `playerHolder.existingPlayer` 建 `MediaSession` 且实例变化即重建会话；`MusicPlaybackControllerImpl` 的队列 / 索引 / 上报 / 通知全读同一实例；deck B 还要解决音频焦点、MediaSession 主体切换、MU-9 上报时机与 UI 跟随。任一项漏做都会静默出错（双播 / 音量残留 / 进度写错），收益仅"重叠"听感，风险与工作量不成比例 |
| D53 | ReplayGain 读取扩展 **M4A/MP4 iTunes free-form 原子**：解析 `moov/udta/meta/ilst/----`（子项 `mean`/`name`/`data`，UTF-8 与 UTF-16 探测）；头部 128 KB 窗口不含完整 `moov` 时，按顶层 box 链的声明长度算出 moov 绝对偏移，再发一次只读 Range（128 KB）；二次窗口仍找不到 moov 则记日志静默放弃 | 覆盖 iTunes / foobar2000 / rsgain 的 MP4 打标格式，与 FLAC VorbisComment / ID3v2 TXXX 并列；非 faststart（moov 在尾）在真实素材常见，box 链扫描成本极低、不需要整文件下载；服务器只读红线不变（全 GET + Range） |
| D54 | 用户可见的**本机增益覆盖**入口：音效面板 ReplayGain 组内「曲目增益 / 专辑增益」两支滑杆（-12..+12 dB、0.5 dB 步进，拖动实时生效、松手落盘）+「清除本机覆盖」；写 `<filesDir>/replaygain/<itemId>.txt`（`track=` / `album=`，删空即删文件），写/删后失效 itemId 读缓存并触发播放链重读；覆盖 > 内嵌优先级不变 | 替代"只能外部写文件"；不写服务器；覆盖是逐曲目本机状态（先例 W25 歌词覆盖 D41）；滑杆沿用 EQ 的"拖动不落盘、onValueChangeFinished 才写"模式（W30 踩坑 38），避免拖动期间高频写文件 |

| D55 | **音乐来源空态文案 + 真实下拉刷新（W39，用户 2026-10-03 确认）** | ①**空态按来源分支**（纯函数 `musicEmptyCopy`，`modes/music`）：本地「本地还没有音乐」/「在『媒体库 → 本地媒体库』添加包含音乐的文件夹后回来」；服务器「服务器音乐库里还没有专辑（按 tab 换 艺术家 / 歌曲）」/「下拉可刷新」；全部「服务器和本地都还没有音乐」/「在服务器或本地媒体库添加音乐后，下拉刷新」；离线「离线模式还没有可播放的音乐」/「联网后在曲目菜单点「下载」，或下拉刷新本地音乐索引」；歌单「服务器上没有歌单」/「下拉可刷新」。**不再出现「在服务器添加音乐后点「刷新」」这类错源 / 不存在控件文案**。②**真实下拉刷新**：内容区接 Compose M3 `PullToRefreshBox`（四 tab 共用），下拉触发 `MusicModeViewModel.refresh()`；`UiState` 新增 `refreshing`（首次加载仍整页 loading，刷新保留列表只转顶部指示）；空态容器改 `verticalScroll` 以接收下拉手势；**离线模式 refresh() 只重读本机索引，不发服务器请求**（保持离线语义）。③**计数副题细化**（纯函数 `musicLibrarySubtitle`）：筛选「本地」=「共 N 张本地专辑」/ 服务器 =「共 N 张服务器专辑」/ 全部 =「共 N 张专辑」（艺术家 / 歌曲同理；离线无前缀；歌单仍「共 N 个歌单」）。④**取证日志**：`曲库刷新：requested / 重新请求服务器曲库 / 重读本地媒体库索引 / 完成`（logcat 关键词「曲库刷新」，供验收复核「真实重取」）；不改 `pref_music_source_*` 键、不改 `player:core` / `player:local`。 |

### 2.14 W44 本会话决策（音乐三件套：开关对比度 / 统一滑杆 / 手势）

| # | 决策 | 理由 |
|---|------|------|
| D56 | **全屏播放页与歌词页手势改向（用户 2026-10-03 确认）**：全屏播放页 **左滑 → 歌词页**、下滑关闭、**右滑留空**（不再绑定歌词）、**取消左滑打开队列**；歌词页 **右滑 → 返回全屏播放页**、下滑返回、左上返回箭头保留；队列仍只由「队列」按钮 / 底部队列图标打开。判定逻辑抽成纯函数 `swipeGestureDirection(totalX, totalY, 横阈值, 纵阈值)`（`MusicNowPlayingScreen.kt`），未达阈值返回 `null`，5 项单测 | 覆盖 W23 D29「右滑进歌词 / 左滑呼出队列」的方向决策（用户 2026-10-03 改口）；把"横向位移占优才判横滑、纵向下滑单独判"的矩阵从 `pointerInput` 闭包里提出来，两页共用同一函数、方向可单测，阈值不再散落 |
| D57 | **音效面板的开关与滑杆改走 core 共享组件**（`CinefinSwitch` / `CinefinSlider`；全 App 范围与组件规格见 `UI_PLAN` D46）：开关关闭态拇指 `onSurfaceVariant` + 轨道 `surfaceContainerHigh` / `onSurfaceFaint` 描边；滑杆 = 4dp 胶囊轨道 + 18dp 圆点拇指 + 极轻同色柔光（与播放页 D38 自绘 `MusicProgressBar` 同一视觉语言）。**W30 交互语义不变**：`onValueChange` 实时预览、`onValueChangeFinished` 才落盘（W30 踩坑 38），EQ 关闭时五段滑杆 `enabled=false`，ReplayGain 覆盖的 0.5 dB 步进改由 `steps` + 吸附纯函数实现 | 用户实测：①M3 默认 `Switch` 关闭态拇指取 `outline`，在暗底上几乎看不见（「开启均衡器」小圆点不明显）；②M3 默认 `Slider` 的竖条拇指（浮标）与播放页圆点进度条不是一套语言。共享组件 + 纯函数吸附让"拖动实时生效、松手落盘"可被单测与真机像素双重取证，也避免两处滑杆各写一份样式 |

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

### W24 音乐 UI 细化（本会话 `feature/w24-music-polish`，已交付）

- [x] A1 悬浮窗自动隐藏：无操作 3 秒隐藏背景 / 边框（只留双行文字），触摸 / 拖动恢复；锁定后保持隐藏，单击只唤出设置工具条
- [x] A2 悬浮窗按钮 / 设置工具条图标化：5 个 40dp 纯图标按钮（颜色 / 字号 / 语言 / 锁定 / 关闭），无文字、无边框、无底色；颜色图标用当前歌词色着色
- [x] A3 无歌词回落：显示「歌名 / 歌手」，歌手由 ViewModel 曲库快照 + 控制器按需加载双来源提供，不出现空白双行
- [x] A4 悬浮窗位置持久化：`pref_music_lyrics_overlay_x/y`（只追加），拖动结束 / 布局完成夹进屏幕后落盘，杀进程重启复位
- [x] A5 全屏「桌面歌词」按钮状态色：未开启 = 白色图标，开启 = 音乐域强调色（`MediaMusic.base`），其余控件不动
- [x] B6 歌词页当前行居中：上下半视口 padding + `centerItem()` 两段式滚动；修掉首帧 `viewportSize=0` 导致当前行贴顶
- [x] B7 去掉当前行前的「杠」，改用强调色 + 32sp / 21sp 字号区分
- [x] C8 全屏播放页显示 2–3 行歌词（当前行 ±1），点任意一行进歌词页
- [x] C9 进度条改自绘：4dp 细轨道 + 18dp 白色圆形拖点 + 同色 radial glow，去掉 Material Slider 竖线浮标
- [x] C10 播放列表半屏面板：全屏「播放队列」图标或左滑呼出，复用既有队列编辑（拖动 / 跳转 / 移除）
- [x] 真机拦下并修复：K60 横屏（411dp 高）W23 单列全屏布局被裁切 → 按高度分「单列 / 两栏紧凑」两套布局
- [x] 单测：`MusicLyricsOverlaySettingsTest` 新增回落 / 锁定 / 位置 3 例 + `LyricsPresenterTest` 新增歌词窗口 1 例，`:modes:music` 71 → **75 项**
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（51 项）+ `:modes:music:testDebugUnitTest`（75 项）全绿
- [x] 真机验证（K60 `8e875894` 全项 10 组，见 §5.7）
- [x] `AppPreferences.kt` 只追加 `pref_music_lyrics_overlay_x/y` 2 个键（位置持久化）

**遗留**：内嵌歌词 / 歌词编辑（W3 未决项不变）；悬浮窗设置工具条暂无“保持显示时长”档位（固定 3 秒）；真机残留新功能偏好 = 桌面歌词关 + 位置 (1017,759) + 颜色松石（W23 遗留色）。

### W25 音乐扩展（本会话 `feature/w25-music-lyrics-edit`，进行中）

- [x] 内嵌歌词探测（只读）：全库 100 首按「Lyric 流路径 / 内嵌 Vorbis `LYRICS` / API 返回」三类对照 + 3 个「服务器无外挂 LRC」样本逐行比对 + 4 个无标签负样本；**结论 = 服务器已覆盖内嵌歌词**（§4.2），按 brief 条件不做客户端标签解析
- [x] 本机歌词编辑：`LyricsEditorDialog`（时间戳 + 文本逐行增 / 删 / 改 / 保存）→ `LyricsOverrideStore`（`<filesDir>/lyrics/override/<itemId>.lrc`）；`LyricsSource.LOCAL_OVERRIDE`「本机覆盖」参与来源链且优先级最高
- [x] 导入 `.lrc`（系统文件选择器；UTF-8 / GBK 自动识别，原样保存并回填编辑器）+ 清除覆盖（回落外挂 LRC / 服务端 / 缓存）；**不写服务器**（文档边界：编辑仅本机生效）
- [x] 悬浮窗工具条「保持显示时长」档位：2 / 3 / 5 / 10 秒 + 常显（第 6 个图标按钮循环切换；偏好键 `pref_music_lyrics_overlay_idle`，默认 `3s`）
- [x] 客户端设置接入 `pref_music_resume_queue` 开关行（音乐行组，与桌面歌词同组；影响队列持久化恢复）
- [x] 单测：`LyricsOverrideStoreTest`（4）/ `LyricsEditorModelTest`（4）/ `LyricsRepositoryTest`（+2）/ `MusicLyricsOverlaySettingsTest`（+1）= 新增 11 项，`:modes:music` 75 → **86 项** 全绿
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（51）+ `:modes:music:testDebugUnitTest`（86）全绿
- [x] 真机验证（K60 `8e875894`，9 组，见 §5.8）：内嵌歌词显示 / 编辑保存重进生效 / UTF-8 + GBK 导入 / 清除覆盖回落 / 悬浮窗五档逐档计时 / 队列开关开与关 / 回归 + 0 FATAL/ANR
- [x] 真机拦下并修复：K60 竖屏「词」面板半展开锚点把歌词行挤出可视区 → `LyricsSheet` 打开即全展开（`skipPartiallyExpanded`），下滑仍可关闭
- [x] `AppPreferences.kt` 只追加 `pref_music_lyrics_overlay_idle`（不重排既有键）

**红线说明**：`app/phone` 仅一处宿主接线——`MusicLyricsOverlayService` 给工具条新按钮传 `controller::cycleIdle`（悬浮窗内容在 `modes:music`，回调只能由宿主注入）；歌词导入的文件选择器在 `modes:music` 内完成，未改 app/phone。

**遗留**：服务器覆盖内嵌歌词的结论仅实测 FLAC/Vorbis（ID3v2 `USLT` / MP4 `©lyr` 由同一 ATL 路径理论覆盖，本库无样本）；离线本地文件（无网络、无缓存）仍取不到内嵌歌词，如需客户端解析另开任务。

### W28 逐字歌词 + 提示行清理 + 编辑时间轴（本会话 `feature/w28-word-lyrics`，进行中）

- [x] 数据链调研（只读）：全库 100 首 `GET /Audio/{id}/Lyrics` → **`Cues` 全为空（0/100）**、无增强 LRC 标签；纯音乐占位行 27 条；服务端 DTO 的 `LyricLineCue` 契约（`position` / `endPosition` / `start` / `end`）核实（§4.4）
- [x] 逐字数据模型与解析：`LyricWord` + `LyricLine.words`；`LrcParser.parseWords` 解析 `<mm:ss.xx>`（offset 同步、空词段跳过、标签前残留文本兜底）并从显示文本剥离；`mapServerLyrics` 映射服务端 `Cues`
- [x] 逐字高亮：`LyricsPresenter.wordHighlights` / `activeWordIndex`（纯函数）+ `LyricsWordHighlight.wordHighlightedText`（AnnotatedString 颜色渐变）；接入歌词面板、全屏歌词页与桌面歌词悬浮窗；**无逐字数据整行降级**（原路径不变）
- [x] 纯音乐提示行时间戳清理（W21 遗留）：`LyricsNormalizer.cleanPlaceholderTimestamp` 清理占位行文本里混入的 `[00:00:00]`（只命中纯音乐 / 无人声 / 请欣赏类，非占位行不动）
- [x] 编辑增强：整段偏移 / 单行 ±100ms + 自定义毫秒（`shiftLyricEditLines` / `nudgeLyricEditLine` / `parseLyricOffsetMs`）；逐字随行平移、保存写增强 LRC 本机覆盖（`LyricsOverrideStore.encodeLrc`）
- [x] 单测：`LrcParserTest`(+3) / `LyricsPresenterTest`(+3) / `LyricsNormalizerTest`(+2) / `LyricsEditorModelTest`(+3) / `LyricsOverrideStoreTest`(+1) / `LyricsRepositoryTest`(+1，服务端 `Cues` 映射)= 新增 13 项，`:modes:music` 86 → **99 项** 全绿
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（51）+ `:modes:music:testDebugUnitTest`（99）全绿
- [x] 真机验证（K60 `8e875894`，见 §5.9）：提示行清理前后对比 / 逐字推进（日志 + 像素）/ 整行降级（无逐字日志 + 整行强调色）/ 偏移与单行微调保存重进生效 + 悬浮窗一致 / 回归 + 0 FATAL/ANR

**遗留**：本库服务端无 `Cues` 样本，服务端映射仅单测覆盖（真机样本 = 增强 LRC 本机覆盖）；歌词缓存（`LyricsCache`）仍只存"行文本 + 时间"，离线回落缓存时逐字数据丢失（优雅降级为整行，如需离线逐字再扩展缓存格式）。

### W30 音乐音效 EQ / ReplayGain / 交叉淡化（本会话 `feature/w30-audio-fx`，已交付）

- [x] 调研（只读）：mpv / Exo 能力对照（§4.5）+ 服务器全库 100 首 ReplayGain 标签扫描（0/100，见 §4.5）
- [x] EQ：`MusicEqualizer`（5 段频率 / 预设 / 编解码 / RBJ peaking biquad）+ `MusicAudioEffectsProcessor`（音频链逐样本处理，16bit/float，仅音乐会话）；面板「预设 7 档 + 五段滑杆」；实时拖动不落盘、拖动结束 commit
- [x] ReplayGain：`ReplayGainTags`（FLAC VorbisComment / ID3v2 TXXX / 覆盖文本解析）+ `ReplayGainTagReader`（Range 拉取 + 覆盖文件 + 内存缓存）+ `ReplayGainMode` 三态与面板状态行
- [x] 交叉淡化：`MusicCrossfadeMath`（等功率曲线）+ `MusicPlaybackControllerImpl` 100ms 包络循环（关 / 2 / 4 / 6 秒，默认关）
- [x] UI：全屏播放页功能行第六键「音效」（`ic_music_equalizer`）+ `MusicEffectsSheet`（EQ / ReplayGain / 淡入淡出三组）
- [x] `AppPreferences.kt` 只追加 5 键：`pref_music_eq_enabled` / `pref_music_eq_preset` / `pref_music_eq_custom_bands` / `pref_music_replaygain_mode` / `pref_music_crossfade_seconds`（不重排既有键）
- [x] 单测 18 项（`MusicEqualizerTest` 5 / `ReplayGainTagParserTest` 6 / `MusicCrossfadeMathTest` 7），`:player:local` 80 → **98 项**
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（51）+ `:player:local:testDebugUnitTest`（98）+ `:modes:music:testDebugUnitTest`（99）全绿
- [x] 真机验证（K60 `8e875894`，见 §5.10）：EQ 可测差异（RMS 比值）/ RG 三态（覆盖文件 -6 dB / -12 dB）/ 交叉淡化淡出淡入全序列 + 关闭档 0 条曲线日志 / 回归与还原

**遗留（明示）**：① 本库 100 首 FLAC **全部没有 ReplayGain 标签** —— 功能链路就绪，实际生效需要用户给文件打 RG 标签或写本机覆盖文件（文档写清）；② 交叉淡化是单实例近似（曲尾淡出 + 曲首淡入，**无重叠**），双实例真交叉未排期；③ M4A/MP4 的 `----:com.apple.iTunes:replaygain_track_gain` 未实现（本库无样本，解析器留了扩展点）；④ EQ 的 peaking Q 固定 1.0，未做每段 Q 可调。

### W35 音乐扩展（真交叉调研 + ReplayGain 收尾；本会话 `feature/w35-crossfade-rg`，已推送未合并）

- [x] 真交叉淡化调研（只读）：单实例 / MediaSession / 上报链路约束拆解 + 三阶段方案与工作量（§4.6 / D52），结论 = 本波不实施
- [x] M4A/MP4 ReplayGain 读取：`parseMp4ReplayGain`（`----` free-form）+ `scanMp4TopLevelBoxes`（moov 在尾时二次只读 Range）；与 FLAC / ID3v2 并列
- [x] 本机增益覆盖 UI：面板两滑杆 + 清除；`ReplayGainTagReader.writeOverride`（写 / 删 + 缓存失效）+ `MusicAudioEffectsController` 覆盖状态与 `overrideRevision` 重读链
- [x] 单测：`ReplayGainTagParserTest` 6 → 12 项（MP4 双档 / 大小写与单位 / 非 RG 忽略 / moov 在尾定位 / 截断 / 覆盖编码往返），`:player:local` 98 → **104 项**
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（61）+ `:player:local:testDebugUnitTest`（104）+ `:modes:music:testDebugUnitTest`（99）全绿
- [x] 本地打标联调（负责人安排，无设备）：rsgain 3.8 基线只读确认原文件无 RG 标签；副本 `test_files/侧脸-RG.flac` 写入 track/album gain = **-8.47 dB**、peak = 1.000000（Loudness -9.53 LUFS，SHA-256 见 §7 日志）
- [x] 真机验证（K60 `8e875894`，见 §5.11）：标签识别（RG 副本 -8.47 dB → 0.38x、面板「文件标签」；原曲 `来源=NONE`、面板「未检测到」）/ 覆盖 UI（-6.0 → 0.50x、+6.0 → 2.00x、轨道+专辑双滑杆 -2.5 dB → 0.75x、清除回落 -8.47 dB）/ 重启持久化 / 歌词·桌面歌词·队列·自然衔接回归 / 0 FATAL·ANR
- [x] 真机拦下并修复：清除覆盖后回读内嵌标签偶发失败被当负缓存（面板「未检测到」直到重启）→ `ReplayGainReadResult`（失败不缓存）+ 换曲先清旧标签 + 3s 重试一次 + 覆盖编码取整（`-2.4999995` → `-2.5`）；重跑门禁全绿、重装复验
- [ ] M4A/MP4 设备端样本：本库 123 首全 FLAC（0 个 m4a/mp4）→ **无样本待补**（解析与 moov 在尾二次 Range 由 12 项单测覆盖，含真机同款两段式路径的纯函数部分）

### W39 音乐来源文案 + 真实下拉刷新（本会话 `feature/w39-media-library-polish`，已推送未合并）

- [x] **空态按来源分支**：纯函数 `musicEmptyCopy` + `EmptyHint` 加 message（专辑 / 艺术家 / 歌曲 / 歌单四 tab 共用）；本地 / 服务器 / 全部 / 离线 / 歌单五套文案全部就位（见 D55）
- [x] **真实下拉刷新**：`MusicModeScreen` 内容区 `PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = viewModel::refresh)`；`UiState.refreshing` 与首载 `loading` 分离；空态容器 `verticalScroll` 保证空库也能下拉
- [x] **离线语义**：离线模式 refresh() 走既有离线分支（重读已下载曲库 + 本地索引，不发网络请求），日志实测无「重新请求服务器曲库」行
- [x] **计数副题**：`musicLibrarySubtitle` 按筛选补「本地 / 服务器」前缀（全部不加），四个 tab 口径一致
- [x] **取证日志**：`曲库刷新：requested` / `重新请求服务器曲库` / `重读本地媒体库索引` / `完成`（Timber，debug 可读）
- [x] **单测 5 项**（`MusicLibraryCopyTest`）：副题前缀（本地 / 服务器 / 全部 / 离线）、三种来源空态、艺术家 / 歌曲标题、歌单与离线空态；`:modes:music` 99 → **104 项**
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；`--rerun` 后 app 74 / core 16 / data 27 / player:local 104 / film 6 / book 106 / music 104 = 437 项 / 0 失败
- [x] **真机验证（Pad 5 `43af8627` 主 + K60 抽验，2026-10-03 04:22–04:37）**：见 §5.12

**遗留（明示）**：①服务器 / 全部两种**空态**分支在测试服务器（105 张专辑 / 123 首）上不可复现，用 5 项单测覆盖，真机只验证了「本地」「歌单」「离线」三种空态 + 三种筛选的计数副题；②刷新不改数据源口径（服务器只读），「数据更新」以仓库重取日志 + 计数副题为准。

### W44 音乐三件套：开关对比度 / 统一滑杆 / 手势（本会话 `feature/w44-music-detail`，起点 master `77feba9`）

- [x] **A 全局开关配色**：core 新增 `CinefinSwitch` + `cinefinSwitchColors()`——关闭态拇指 `onSurfaceVariant`、轨道 `surfaceContainerHigh`、描边 `onSurfaceFaint`；开启态当前域媒体色；禁用态降透明但仍可辨；Prism / Lumen 由 `LocalCinefinColors` / `LocalMediaColors` 自动切换，**未新增任何色值**
- [x] **A 替换面**：`MusicEffectsSheet`、`SettingsSwitchCard`、`PlayerSettingsPanel`、`DownloadsScreen`×2、`LocalLibraryScreens`、`OfflineScreens`×2 全部改走共享组件；阅读器纸色面板保留按 `ReaderSettings` 派生的自定义配色（同一组件、显式传色）
- [x] **B 统一滑杆**：core 新增 `CinefinSlider` + `CinefinSliderColors` / `CinefinSliderDefaults`——4dp 胶囊轨 + 18dp 圆点拇指 + 极轻柔光；支持 `steps` 吸附、禁用态、RTL、36dp 触控带（手势自消费）；数值 / 触摸换算纯函数 `cinefinSliderFraction` / `cinefinSliderValueAt` + 7 项单测
- [x] **B 替换面**：EQ 五段 + ReplayGain 曲目 / 专辑覆盖（`MusicEffectsSheet`）、阅读器 `ReaderSettingsPanel` 数值滑杆；仓库内 `androidx.compose.material3.Slider` 使用点清零（`rg "Slider("` 只剩 core 组件自身）；播放页自绘 `MusicProgressBar` 不动，仅核对同一视觉语言
- [x] **C 手势**：全屏左滑进歌词 / 右滑留空 / 取消左滑队列 / 下滑关闭；歌词页右滑返回 / 下滑返回 / 左上箭头保留；队列按钮与底部图标行为不变
- [x] **单测**：core +7（`CinefinSliderMathTest`，含 0.5 dB 档位吸附）、music +5（`MusicSwipeGestureTest`）
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；`--rerun` 后 app 79 / core 25 / data 33 / player:local 104 / film 6 / book 106 / music 109 = **462 项 / 0 失败**
- [x] **真机验证（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-03 12:20–12:50）**：见 §5.13（含开关关闭态 thumb / 轨道像素采样与对比度）

**遗留（明示）**：①歌词页「下滑返回」在歌词列表处于可滚动位置时依赖列表滚动边界，长歌词列表从中间下滑仍是滚动列表（与 W23 同款行为，未改）；②`CinefinSlider` 为自绘实现，键盘 / 无障碍只提供 `progressBarRangeInfo` + `setProgress`（无 M3 的完整键盘步进）；③滑杆发光沿用播放页的 `GlowScale = 2.6`，若后续设计系统调整进度条光晕需同步。

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

**内嵌歌词探测结论（W25-MUSIC，2026-10-02，全程只读）**

- 目的：确认 `GET /Audio/{id}/Lyrics` 是否已覆盖"文件内嵌歌词"，据此决定要不要客户端补 ID3v2 `USLT` / Vorbis `LYRICS` / MP4 `©lyr` 解析（brief 条件：服务器未覆盖才实现）。
- 方法：① 全库 100 首音频取 `MediaSources[0].MediaStreams` 里 `Type=Lyric` 流的路径（`/medi/音乐/*.lrc` = 外挂 LRC；`/config/metadata/library/**/*.lrc` = 服务器扫描时提取保存；无 Lyric 流 = 无歌词）；② 用 `Range: bytes=0-262143` 只读拉取每个 FLAC 头部、解析 Vorbis comment 的 `LYRICS` 字段；③ 逐条调用 `GET /Audio/{id}/Lyrics` 与内嵌标签比对。
- 全库分布（100 首）：**95 首带内嵌 `LYRICS`**；`Lyric` 流 35 首指向媒体目录同名 `.lrc`（外挂），**61 首指向 `/config/metadata/library/...`（无外挂 LRC，歌词只可能来自内嵌标签/上传，前者已由样本证实）**；4 首无 Lyric 流且同时无内嵌标签（API 404）。
- 「服务器无外挂 LRC」三个样本（API 返回 = 文件内嵌标签）：
  - `b8d0326b`《梦之咏叹(晨)》：内嵌纯文本 9 行（作曲 / 指挥 / 乐队 / 录音棚 / 录音师 / 工程师 / 混音…）与 API 返回 **9/9 行逐行全等**（脚本比对 `equal=True`）；
  - `08751d9c`《梦的光点》：内嵌 LRC 75 行，API 74 行——差异仅为无时间戳首行 `作词 : 陈天佑` 被服务端 LRC 解析器丢弃；
  - `977d410b`《心做し》：内嵌 LRC 100 行，API 95 行（`[ti:]` / `[ar:]` 等 ID 标签被解析器丢弃）。
- 负样本（文件无 `LYRICS` 标签 → API 404）：`1a49753d`《Dream Aria》/ `85e600ea`《Golden Key》/ `41432098`《S.T.A.Y.》/ `8a6701ce`《Start Over》。
- 代码层佐证（upstream `jellyfin/jellyfin v10.11.8`）：`AudioFileProber.cs` 扫描时执行 `FetchDataFromTags(audio, …, tryExtractEmbeddedLyrics)`——注释 "Add external lyrics first to prevent the lrc file get overwritten on first scan"、"Save extracted lyrics if they exist, and if the audio doesn't yet have lyrics"；`track.Lyrics`（ATL/TagLib，覆盖 ID3v2 `USLT` / Vorbis `LYRICS` / MP4 `©lyr`）有同步 / 非同步候选时 `_lyricManager.SaveLyricAsync(audio, "lrc", lyrics)` 落为 metadata `.lrc`，随后 `LyricManager.GetLyricsAsync` 读取该 `.lrc` 经 API 返回。服务器未装任何歌词 provider 插件（`/Plugins` 8 个均非歌词；`RemoteSearch/Lyrics` 返回 0），排除"远端歌词库"来源。
- **结论：服务器（Jellyfin 10.11.8）已覆盖内嵌歌词**（FLAC/Vorbis 实测；ID3v2 / MP4 由同一 ATL 路径理论覆盖，本库无样本）。按 brief 条件**本波不实现**客户端标签解析；服务端来源链已能把"仅内嵌歌词"的歌显示出来（真机证据见 §5.8）。

### 4.3 离线方案（MU-6）

- 复用 `Downloader`（DownloadManager + WorkManager + Room）扩展 Audio 分支；下载完成后播 `file://` 本地源（`PlayerItem.mediaSourceUri`）；
- 容量管理：设置页显示占用 + 一键清理（复用下载页样式）；自动缓存策略后置；
- 与阅读器线共用 `DownloaderImpl` 时按 `ARCHITECTURE` §2.4 约定：一条线先做通用化，另一条只加自己的分支。

### 4.4 逐字歌词数据链（W28-MUSIC 调研，只读）

目标：回答"逐字（词）粒度从哪来、本库实际能拿到什么"，据此定实现与降级策略。

| 来源 | 契约 / 粒度 | 本库实测（2026-10-02，全库 100 首） | 客户端处理 |
|------|------------|--------------------------------|-----------|
| 服务端 `GET /Audio/{id}/Lyrics` → `Lyrics[].Cues` | `LyricLineCue{position,endPosition,start,end}`：**行内 UTF-16 下标区间** + ticks 时间（1 ms = 10000 ticks） | **100 首全部 `Cues` 为空数组**（3 个样本逐行比对 + 全库扫描）；服务端 10.11.8 的 LRC 解析器不产出 cues | `mapServerLyrics` 已按契约映射（越界 / 空段跳过）+ 单测；本库无真机样本，属"未来可用" |
| 增强 LRC `<mm:ss.xx>`（外挂 `.lrc` / 本机覆盖 / 导入） | 行内逐字标签：`[00:12.00]<00:12.00>词<00:12.80>句`；与行时间同 offset 语义 | 服务端返回的 100 首里 0 条含 `<…>` 标签；**本机覆盖 / 导入链路可携带**（W28 真机用增强 LRC 覆盖样本验证） | `LrcParser.parseWords`（本波新增）：标签剥离 + 转 `LyricWord`；`LyricsOverrideStore.encodeLrc` 保存时写回增强标签 |
| 本机覆盖文件 `<filesDir>/lyrics/override/<itemId>.lrc` | 与"外挂 LRC"同格式（可读 LRC 文本），来源链最高优先级 | W25 建立；W28 真机写增强 LRC 样本（9 词 + 1 行无逐字） | 无需新格式：解析统一走 `LrcParser` |

**粒度结论**：本库可得的最细粒度 = 增强 LRC 的"词/字段"（每段一个 `<mm:ss.xx>`）；服务端粒度 = 行（`Start`）+ 双语配对（同 Start），**没有逐字**。词段边界与实际唱词不一定逐字对齐（取决于 LRC 作者），客户端只做"按段推进"。

**降级规则（D46）**：`words` 为空（服务端行 / 无标签 LRC / 缓存回落）→ 颜色不渐变、整行高亮 + 跟随滚动完全走 W3 路径；逐字只在"有数据且文本与词段拼接一致"时生效。

### 4.5 W30 音效能力与数据源调研（只读，2026-10-02）

**三项能力对照（音乐实际路径 = ExoPlayer，W1 D3）**

| 能力 | mpv 原生 | Exo/Media3 原生 | 音乐可达性 | 本波落点 |
|------|----------|-----------------|------------|----------|
| EQ | ✅ `af=equalizer` / `anequalizer`（任意频段 / 增益 / Q） | ❌（需自研 AudioProcessor） | mpv 不可达（音乐强制 Exo） | 自研 `MusicAudioEffectsProcessor`（5 段 peaking biquad）挂 Exo 音频链 |
| ReplayGain | ✅ `replaygain=no\|track\|album` + `replaygain-fallback` / `replaygain-preamp` | ❌（1.11.1 有 `GainProcessor` / `DefaultGainProvider`，但**不解析标签**、样本位置语义面向单个流） | mpv 不可达 | 客户端解析标签（FLAC/ID3）+ 逐样本增益 |
| 交叉淡化 | ❌（gapless ≠ crossfade；`af` 只能处理单流） | ❌（无 dual-deck） | — | 单实例近似（`Player.volume` 包络），双实例真交叉未排期 |

**服务器 ReplayGain 标签全库扫描（只读）**：100 首音频全部为 FLAC，Range 只读拉头部（64 KB / 256 KB 两种窗口）解析 VorbisComment：
**100/100 可解析出普通标签**（`TITLE` 100 / `ALBUM` 100 / `LYRICS` 95 等，证明解析链路正确），但 **`REPLAYGAIN_*` 0/100、`R128_*` 0/100**；`/Items`（含 `MediaSources`）JSON 也不含 replaygain 字段。
→ 结论：RG 客户端链路可行但**本库暂无数据**；真机用本机覆盖文件（产品内的合法数据源）验证三态行为，文件标签路径由单测覆盖。

**Media3 `GainProcessor` 备注（留档）**：1.11.1 的 `androidx.media3.common.audio.GainProcessor(GainProvider)` + `DefaultGainProvider`（`FADE_IN/OUT_LINEAR/EQUAL_POWER`、`Builder.addFadeAt(samplePos…)`）可在**样本级**应用增益/淡化，但 `GainProvider` 的入参是"音频流累计样本位置"，跨媒体项（gapless 不 flush 链路）时媒体项边界不可观测；本波未采用（EQ 需自研 DSP，RG 只用一个标量增益，`Player.volume` 包络更简单可靠），记录以备后续。

### 4.6 W35 双实例真交叉淡化调研（只读，2026-10-02）

**目标**：旧曲渐弱与新曲渐强**重叠**（真 crossfade），替换 W30 D51 的"淡出 + 淡入（无重叠）"近似。

**现状约束（代码证据）**：
1. `PlayerHolder` 全进程只持有 1 个实例（`instance` / `instanceBackend`）；`player` getter 会按偏好重建，音乐会话期间 `audioSession()` 强制 Exo 且复用唯一实例，`release()` 随播放服务生命周期。
2. `CinefinPlaybackService` 用 `playerHolder.existingPlayer` 构建 `MediaSession`，实例变化（`rebuildSessionIfPlayerChanged()`）时**整体重建会话**；通知 / 锁屏 / 蓝牙 / 车机全部只认这个会话。
3. `MusicPlaybackControllerImpl` 的队列、索引回写、500 ms ticker、MU-9 上报（Start / Progress / Stop）、`PlaybackPositionWriter` 都读同一实例的 `currentMediaItemIndex` / `currentItem`；`Player.volume` 是唯一可编程增益面，退出音乐会话会被复位。
4. 音频焦点由 ExoPlayer `setAudioAttributes(attrs, true)` 处理；两条实例同时请求焦点会互相 duck / 暂停。

**为什么不能"顺手"上双 deck**：deck B 需要第二条 Exo 实例（内存 / 解码 ×2）；两个实例同时持有音频焦点会互相干扰；MediaSession 只能绑一个 player，交叉窗口横跨"通知当前曲目 / 队列索引 / 上报归属"的语义变化；交叉时"旧曲何时 Stop、新曲何时 Start"必须重定义（否则服务器进度写错）；歌词 / 进度条 / 封面在交叉窗口要跟随主 deck。任一项漏做都会静默出错（双播 / 音量残留 / 上报错位）。

**分阶段实施方案（未来开波照此做）**：
- **阶段 1 · 双 deck 基础（2–3 人日）**：抽 `MusicDeck` 接口；deck A = 现 `PlayerHolder`，deck B 惰性创建（同 `AudioAttributes`、`handleAudioFocus=false`、不挂 MediaSession、独立 `CinefinRenderersFactory`）；加调试开关做"双 deck 同时出声"冒烟（不出厂）。
- **阶段 2 · 交叉引擎（3–4 人日）**：`MusicCrossfadeMath` 扩双路等功率包络；在 `duration - fadeMs` 触发 deck B `setMediaItem / seekTo(0) / prepare / play`；100 ms ticker 同时驱动两 deck 音量；交叉完成停 A 并交换 A/B 角色；deck B 准备失败回落 D51 单实例近似。
- **阶段 3 · 会话与回归（3–5 人日）**：`MediaSession.setPlayer()`（或重建会话）把主体切到活跃 deck；音频焦点只交给活跃 deck；通知 / 锁屏 / 耳机 / 车机、上报（旧曲 Stop = 交叉开始位置、新曲 Start = 交叉开始）、睡眠定时、歌词 / 悬浮窗、蓝牙断开、来电 duck、视频互斥全量回归（Pad 5 + K60 双机矩阵）。
- **总计 8–12 人日 + 双机回归**。结论：**除非把"真交叉"列为头等功能，否则保持 D51 近似**——近似在 gapless 下已无静音缝，真交叉的增量主要是"重叠"听感。

**其他备注**：Media3 1.11.1 `GainProcessor` 的样本位置入参跨 item 边界不可观测（§4.5 已记），不能替代双 deck；若未来音频链改为 `DefaultAudioSink` 单一 sink 混音方案，仍绕不开会话 / 上报 / UI 的同一批改造。

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

### 5.7 W24 真机验证记录（2026-10-02，Redmi K60 `8e875894`）

设备由负责人统一调度（`device-lock.md`，K60 归 W24）；全部 `adb` 命令带 `-s 8e875894`；安装包 = 本会话 `assembleDebug` 产物；测试时设备为**横屏**（3200×1440 / 914×411dp，`fullSensor`）。
悬浮窗是 `FLAG_NOT_FOCUSABLE` 的 `APPLICATION_OVERLAY` 窗口，`uiautomator dump` 抓不到它的节点（W23 §6-25 同款结论）；因此悬浮窗证据 = `dumpsys window` frame + 截图像素采样 + logcat 时间戳，App 内页面用 `uiautomator dump`。

| # | 项目 | 证据 | 结果 |
|---|------|------|------|
| 1 | 悬浮窗 3s 自动隐藏 / 触摸恢复 / 锁定后单击唤出 | 日志 `背景：显示 17:01:50.038 → 隐藏 17:01:53.077`（3.039s）；点歌词区 `17:02:07.042 背景：显示`；锁定 `17:02:45.462 已锁定` → `17:02:45.476 背景：隐藏（锁定=true）`；锁定后单击 → 工具条 frame `[162,618][1168,1123]`（展开）且无「显示」日志 | ✅ |
| 2 | 工具条图标化、无文字 / 无边框 / 无底色 | 工具条 frame 展开 `[162,618][1168,1123]`（5×40dp 图标行）vs 收起 `[162,618][1002,934]`；截图采样：按钮单元格背景 13601/14400 px = 容器色 `(15,18,23)`（无浅色底板），图标白色 3097 px + 颜色键 `(92,225,210)` 649 px | ✅ |
| 3 | 无歌词时显示歌名 / 歌手 | 日志 `桌面歌词：无歌词，回落歌名 / 歌手（unravel / TK from 凛冽时雨）`（17:01:50.049 与重启后 17:03:46.685 各一次）；`unravel` 歌词加载前悬浮窗即显示两行，无空白 | ✅ |
| 4 | 位置持久化（杀进程重启） | 拖动后 `17:03:22.718 桌面歌词位置：x=1017, y=759`（frame `[1155,897]`）；force-stop → 重启 → 重新起播 `17:03:46.484 位置：x=1017, y=759`、frame `[1155,897][…]` 与杀进程前一致 | ✅ |
| 5 | 桌面歌词按钮两态色 | 全屏按钮区域像素采样：未开启 = 白色 1186 px / 强调色 0；开启 = `(63,201,160)` 249 px / 白色 0（音乐域 `MediaMusic.base`） | ✅ |
| 6 | 歌词页当前行居中；无「杠」 | 居中日志 `target=30 viewport=840 offset=0 size=177 delta=-331.5` → 居中后 `offset=332 ≈ (840-177)/2`；节点 dump 当前行 centerY=994 = 列表视口（574–1414）中心；5 行文本 left 全部 530（无杠）；当前行强调色 8916 px、其余行 0 | ✅ |
| 7 | 全屏 2–3 行歌词 + 点击进歌词页 | dump 三行 `あなたを傷つけたくはないよ / 覚えていて… / 無限に広がる孤独が絡まる`（当前行居中强调）；点当前行 → 歌词页（`返回全屏播放` + `来源：服务端　日文 / 英文` + 语言 chip） | ✅ |
| 8 | 进度条新样式（像素 / 描边采样） | 修回放大后采样：轨道 y=633–646 共 14 px（=4dp）连续强调色；拖点白色圆 y=622–657，逐行宽度 51→62→51 px（中心行 62 px ≈ 18dp 圆，非竖线） | ✅ |
| 9 | 播放列表半屏面板（图标 / 左滑、拖动 / 跳转） | 点全屏 `播放队列` 图标 → `播放队列（98）` 面板顶部 y=720（下半屏 720–1440）；左滑 `2500,500→1200,500` → 同一面板；`DOWN 900ms + MOVE` 长按拖动 → 01/02 由「梦的光点 / 123我爱你」互换；点 `123我爱你` → media_session `metadata=123我爱你`、`PLAYING` | ✅ |
| 10 | W23 功能回归 + 稳定性 | 迷你栏五键顺序（队列 / 词 / 上一曲 / 播放 / 下一首）dump 一致；模式 4 连点日志 `顺序 → 列表循环 → 单曲循环 → 随机`；底栏「词」→ 歌词面板（`来源：服务端　简体中文` + 原文 / 双语 / 跟随）；顶栏睡眠定时面板（关闭 / 10 / 20 / 30 / 60）；收藏 / 最近入口在；重启后队列 `unravel 0:02/3:25 · 上次播放` 恢复；随机模式 seek 到曲末 → 自动衔接下一首（`絆 → 奢香夫人`，全程 `PLAYING`）；logcat 0 条 `FATAL EXCEPTION` / `ANR` / `Input dispatching timed out` | ✅ |
| 11 | 真机拦下的缺陷 | K60 横屏 411dp 高时 W23 单列全屏被裁切（大封面 `[1263,348][2383,1384]` 后进度条 / 按钮全部出屏）→ 本波按高度分「单列 / 两栏紧凑」（§6-27），复测两栏后歌词 / 进度条 / 五键均可见 | ✅ |

> 设备副作用：App force-stop、`/sdcard/w24_*.xml` 清理；未改 `wm size` / `wm density` / 旋转 / Wi-Fi（横屏为设备物理姿态，全程未主动改）；测试结束桌面歌词开关 = 关；新增位置键残留 `(1017,759)`；队列拖动顺序已还原（01 梦的光点 / 02 123我爱你），播放模式 4 连点回到原随机态。

### 5.8 W25 真机验证记录（2026-10-02，Redmi K60 `8e875894`）

设备由负责人统一调度（`device-lock.md`，K60 归 W25）；全部 `adb` 命令带 `-s 8e875894`；安装包 = 本会话 `assembleDebug` 产物（19:00 前先装 `9bae78c` 版，拦下「词」面板问题后重装修复版复测）。
悬浮窗为 `FLAG_NOT_FOCUSABLE` 的 `APPLICATION_OVERLAY` 窗口，`uiautomator` 抓不到其节点 → 悬浮窗证据 = `dumpsys window` frame + logcat 时间戳；App 内页面用 `uiautomator dump` 文本。

| # | 项目 | 证据 | 结果 |
|---|------|------|------|
| 1 | 内嵌歌词显示（仅内嵌标签的歌） | 当前曲 `无人之地 (no man's land)`：服务器 Lyric 流在 `/config/metadata/library/...`（无外挂 `.lrc`），API 5 行与文件内嵌 `LYRICS` 一致（作曲/编曲/制作/纯音乐）；面板 `来源：服务端` + 3 行（`无人之地 (no man's land) - UM` / `制作：UM` / `纯音乐，请欣赏`，署名行按既有规则清洗） | ✅ |
| 2 | 歌词编辑保存 → 重进生效 | 词面板「编辑」→ 行列表（时间戳 + 文本）→「添加行」输入 `00:02.00` + `W25EDITLI` →「保存」；覆盖文件 `files/lyrics/override/8be8416d-….lrc` 含 `[00:02.000]W25EDITLI`；面板即时 `来源：本机覆盖` + 新行；**force-stop 重启后**仍 `来源：本机覆盖` 且新行在列（优先级最高） | ✅ |
| 3 | 导入 `.lrc`（UTF-8 / GBK） | 系统文件选择器（下载内容）选 `w25_import.lrc` → 编辑器 `已导入 4 行（本机覆盖）`、面板显示 3 行 `W25 IMPORT LINE …`；GBK 文件（本机生成 GBK 字节 → `base64 -d` 落盘）→ `已导入 3 行（本机覆盖）` 且 `W25 GBK 中文行一/二` 正确解码 | ✅ |
| 4 | 清除覆盖回落原来源 | 「清除覆盖」→ `已清除本机覆盖`、`files/lyrics/override` 目录清空；面板回落 `来源：服务端` + 原 3 行 | ✅ |
| 5 | 悬浮窗「保持显示时长」逐档 | 工具条第 4 个图标循环切换；logcat「保持显示 → 背景：隐藏」配对：**2 秒 2.042s**（18:54:02.290→04.332）、**3 秒默认 3.009s**（18:51:01.560→04.569）、**3 秒显式 3.024s**（18:54:11.775→14.799）、**5 秒 5.036s**（18:51:53.690→58.726）、**10 秒 10.042s**（18:52:11.066→21.108）、**常显**（清日志后 12s 内 0 条隐藏、窗口保持展开）；结束后档位回 `3s`（默认） | ✅ |
| 6 | 队列恢复开关开 / 关 | 设置页新行「恢复播放队列」：ON（默认）→ force-stop 重启 → 底栏 `unravel 1:54 / 3:25 · 上次播放`；OFF（`pref_music_resume_queue=false`）→ 重启后音乐页无底栏 / 无「上次播放」；再 ON → 重启恢复 | ✅ |
| 7 | 既有功能回归 | 歌词面板（来源 / 语言 chip / 滚动行；`unravel`：`来源：服务端　日文 / 英文` + 日文行）；全屏播放页（退出全屏 / 播放队列 / 随机播放 / 收藏 / 歌词 / 桌面歌词）；播放队列面板（条目 + `≡` 拖拽柄）；睡眠定时面板（关闭 / 10 / 20 / 30 / 60）；播放自动衔接（无人之地 → 心做し → unravel 全程 PLAYING）；起播 `state=PLAYING(3) position=36316` 从续播位开始 | ✅ |
| 8 | 稳定性 | 全程 logcat `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out` / `UnsatisfiedLinkError` **0 条**（含两次 force-stop 重启与 6 档位切换） | ✅ |
| 9 | 真机拦下并修复 | K60 竖屏「词」面板半展开锚点把歌词行挤出可视区（上滑展开才见）→ `LyricsSheet` 改 `rememberModalBottomSheetState(skipPartiallyExpanded = true)`（82% 屏高面板打开即全展开）；复测行列表直接可见、下滑仍可关闭 | ✅ |

> 设备副作用：App force-stop（0 会话）；`/sdcard/w25_*.xml` 与 `/sdcard/Download/w25_import*.lrc` 已删除；偏好复核 = `pref_music_lyrics_overlay=false` / `..._idle=3s`（默认）/ `pref_music_resume_queue=true`（默认）/ 桌面歌词位置 `(276,126)` / 颜色松石 / 字号中 / 语言简体 / 未锁定（与开场基线一致）；`accelerometer_rotation=1` / `user_rotation=0` 未改；队列快照因测试播放前进（无人之地 → unravel，位置 1:54），未做回滚。

### 5.9 W28 真机验证记录（2026-10-02，Redmi K60 `8e875894`）

设备由负责人统一调度（`device-lock.md`，K60 归 W28；Pad 5 归 W27，全程未占用）；全部 `adb` 命令带 `-s 8e875894`；安装包 = 本会话 `assembleDebug` 产物（`classes12.dex` 含 `逐字歌词：pos=` 特征串，安装后核对）。

| # | 项目 | 操作 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 提示行清理**前后对比** | 装前（W25 整合版 1.1.0）：`Last Reunion` 词面板 = `[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏`（与服务器原始返回一致）；装 W28 版后同曲 = `此歌曲为没有填词的纯音乐，请您欣赏`（`来源：服务端`，前缀消失） | ✅ |
| 2 | 逐字推进（日志采样） | 增强 LRC 本机覆盖（`心做し`，9 词 + 1 行无逐字；经 `run-as` 落 `files/lyrics/override/<id>.lrc`）→ 面板 `来源：本机覆盖`、文本已剥离 `<…>` 标签；logcat：`pos=5103ms 第 1 行 第 1/4 词「逐字」`→`8054ms 2/4`→`11130ms 3/4`→`14044ms 4/4`，`20259ms 第 2 行 1/3`→`23060ms 2/3`→`26157ms 3/3`（每词按时间戳推进） | ✅ |
| 3 | 逐字推进（像素） | 同一行文本带（y 2159–2214）强调色像素：`x=115–226`（词 1 进度 0.41 @pos 6244，mean RGB 177,224,201）→ `x=115–466`（词 1–3 完成 @pos 15549，mean RGB 60,189,152）；亮色像素 2463 → 9619（同亮度阈值） | ✅ |
| 4 | 桌面歌词逐字 + 偏移一致 | 悬浮窗 `APPLICATION_OVERLAY` frame `[276,264][1116,580]`；同帧区域亮色像素 3087 → 8486 → 13828、暗色 15403 → 10300 → 5314（pos 5.0 / 11.3 / 15.1 s）→ 逐字递进；点歌词行 `第二行也逐字` → `media_session position=20100` + 悬浮窗 `当前句「第二行也逐字」`；点 `逐字高亮推进样本` → `position=5200` + 悬浮窗当前句同步 | ✅ |
| 5 | 偏移 / 单行微调保存重进 | 编辑器初值 `00:05.000 / 00:20.000 / 00:32.000 / 00:38.000` →「整体 +100ms」×2 + 选中第 2 行「−100ms」= `00:05.200 / 00:20.100 / 00:32.200 / 00:38.200`；保存后**重开编辑器仍为偏移值**；覆盖文件 `[00:05.200]<00:05.200>逐字<00:08.200>高亮…`（逐字随行平移，未丢段） | ✅ |
| 6 | 偏移后播放时间轴 | 重播采样：第 1 行 `pos=5258 / 8301 / 11413 / 14383`（原 5103 / 8054 / 11130 / 14044，+200 ms 基线）；第 2 行 `pos=20342 / 23307 / 26468`（+100 ms 基线） | ✅ |
| 7 | 无逐字数据整行降级 | 删除覆盖 → 面板 `来源：服务端　简体中文 / 日文 / 混合行`；播放期间 `逐字歌词` 日志 **0 条**；当前行 `不再哭泣` 整行强调色（像素 x=114–704 全行同色、y 3044–3099）+ 跟随滚动居中（同一帧悬浮窗同句） | ✅ |
| 8 | 既有功能回归 | 歌词面板（来源 / 语言 chip / 双语对照 / 跟随滚动 / 点行 seek）/ 歌词编辑（预填 / 保存 / 清除覆盖回落）/ 桌面歌词（开 → 关 + Service 销毁）/ 播放队列（`播放队列（100）` + `正在播放`）/ 睡眠定时（关闭 + 10 / 20 / 30 / 60）/ 后台自动衔接 = 全过，无回归 | ✅ |
| 9 | 门禁 + 稳定性 | `assembleDebug`（含 TV）+ `ktfmtCheck` + app 51 + music 99 全绿；真机全程 `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out` / crash buffer **0 条**（含 3 次 force-stop 重启与 1 次安装重启） | ✅ |

> 设备副作用已还原：App force-stop（0 会话）；`/sdcard/w28_*.png|xml` 与 `/data/local/tmp/w28_word.lrc` 已删除；测试覆盖 `files/lyrics/override/<心做し id>.lrc` 已删（override 目录空）；偏好复核 = 桌面歌词 `false` / idle `3s` / 语言简体 / 字号中 / 颜色松石 / 位置 `(276,126)` / `resume_queue=true` / 未锁定（与开场备份逐键一致）；未改 `wm size` / density / 旋转 / Wi-Fi；队列快照因测试播放前进（Last Reunion → 心做し），未回滚。

### 5.10 W30 音效真机验证记录（2026-10-02，Redmi K60 `8e875894`）

设备由负责人统一调度（`device-lock.md`，K60 归 W30；Pad 5 全程未占用）；全部 `adb` 命令带 `-s 8e875894`；安装包 = 本会话 `assembleDebug` 产物（含"交叉淡化：位置"特征串，补日志后二次构建核对）。

| # | 项目 | 操作 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 入口与面板 | 全屏播放页功能行六键：新增「音效」图标区 `[1159,2551][1327,2719]`；面板三组 = 均衡器（开关 + 7 预设 chips + 五段滑杆）/ ReplayGain（关闭 / 曲目 / 专辑 + 状态行）/ 淡入淡出（关闭 / 2 / 4 / 6 秒），K60 竖屏 411dp 六键不溢出 | ✅ |
| 2 | EQ 预设可测差异 | 开 EQ +「低音增强」：`音乐音效统计：eq=开 gains=[7.0,5.0,2.0,0.0,0.0] … 实际增益=1.26–1.48x`（内容相关，>1 = 抬升生效） | ✅ |
| 3 | EQ 全段衰减（可测差异） | 五段滑杆全部拖到最低：`gains=[-11.7,-11.7,-11.6,-11.7,-11.6] 输入RMS=0.2102 输出RMS=0.0653 实际增益=0.31x`（段间重叠使实际值略高于 -11.7 dB 的理论 0.26x） | ✅ |
| 4 | EQ 关闭 = 透传 | 关 EQ 后无「音乐音效统计」（`sessionActive && (eq ∥ rg)` 门槛），播放正常 | ✅ |
| 5 | RG 无标签链路 | 全库 100 首 0 标签（只读探测 §4.5）→ 选「曲目」档：`ReplayGain 读取：item=977d410b… 来源=NONE track=null album=null`（真实 Range 拉流 + 解析）、面板「未检测到 ReplayGain 标签」 | ✅ |
| 6 | RG 曲目档（覆盖 -6 dB） | `files/replaygain/<id>.txt`（`track=-6.0` / `album=-12.0`）→ `rg=-6.00dB 实际增益=0.50x`（-6 dB = 0.501，精确）；面板「曲目标签 -6.0 dB（本机设置）」 | ✅ |
| 7 | RG 专辑档（-12 dB） | 切「专辑」→ `rg=-12.00dB 实际增益=0.25x`（-12 dB = 0.251，精确；切换瞬间 1 条 0.42x 过渡 buffer） | ✅ |
| 8 | RG 关闭档 | 切「关闭」→ 无「音乐音效统计」（不改变音量），面板回说明文案 | ✅ |
| 9 | 交叉淡化淡出（6 秒档） | `1492: Conquest of Paradise` 4:38：剩余 `4661ms→0.94 / 4220→0.89 / 3715→0.83 / 3205→0.74 / 2696→0.65 / 2184→0.54 / 1674→0.42 / 1164→0.30 / 660→0.17 / 55→0.01`（等功率 sin 曲线数值命中） | ✅ |
| 10 | 交叉淡化淡入 + 无静音衔接 | 切歌 `Sending start 856719c4…`，media_session 全程 `PLAYING(3)`；新曲 `529ms→0.14 / 1034→0.27 / 1545→0.39 / 2054→0.51 / 2565→0.62 / 3077→0.72 / 4603→0.93 / 5107→0.97 / 5622→1.00`；0.01 → 0.14 连续，无静音段 | ✅ |
| 11 | 交叉淡化关闭档 | 关闭后 seek 到曲尾：`交叉淡化` 日志 **0 条**，自动切歌正常（active item 7→8，`PLAYING`） | ✅ |
| 12 | 回归 | 手动换歌（下一首 6→7→8）/ 播放队列面板（播放队列（100））/ 歌词面板（`来源：服务端　简体中文`）/ 桌面歌词开→关（`悬浮窗已显示` + `已销毁`）/ 自动衔接全程 PLAYING | ✅ |
| 13 | 稳定性 | 整轮 logcat `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out` / `UnsatisfiedLinkError` **0 条**（含 4 次安装 / force-stop 重启） | ✅ |

> 设备副作用已还原：App force-stop（0 会话）；prefs 逐键复核 = `crossfade=0` / `eq_enabled=false` / `eq_preset=flat` / `eq_custom_bands=0.0,0.0,0.0,0.0,0.0`（force-stop → `exec-out` 读出替换 → 非空校验写回的流程修正，3467 B）/ `replaygain_mode=off`；RG 覆盖文件 `files/replaygain/` 已删、`/data/local/tmp/w30_*` 与 `/sdcard/w30_*.xml` 已清理；未改 `wm size` / density / 旋转 / Wi-Fi；队列快照因测试播放前进（梦的光点 → Bloody Mary → 下一首），未回滚（与 W21/W28 先例一致）。
>
> 取证注意：补日志后必须**重新 `assembleDebug` 再装机**（首轮 APK 只有功能没有淡化日志，造成"无日志"误判）；K60 主缓冲小，跨 10 秒以上的序列日志要当轮 `logcat -d` 抓取。

### 5.11 W35 真机验证记录（2026-10-02 23:10–23:35，Redmi K60 `8e875894`）

设备由负责人指派（`device-lock.md`；Pad 5 归 W34，全程未触碰）；安装包 = 本 worktree `:app:phone:assembleDebug` 产物（先校验 dex 内含「本机增益覆盖」特征串，排除 master 旧镜像误装）；服务器只读确认两条音频：`侧脸.flac` id=`190863be…`、`侧脸-RG.flac` id=`98043669…`。

| # | 项目 | 操作 / 证据 | 结果 |
|---|------|------------|------|
| 1 | RG 副本识别（标签） | 播放 `侧脸-RG`（曲目档）：`ReplayGain 读取：item=98043669… 来源=EMBEDDED track=-8.47 album=-8.47`；面板「曲目标签 -8.5 dB（文件标签）」；`音乐音效统计 … rg=-8.47dB 输入RMS=0.36 输出RMS=0.14 实际增益=0.38x`（-8.47 dB 理论 0.377×） | ✅ |
| 2 | 原曲对照（无标签） | 播放 `侧脸`（原曲）：`来源=NONE track=null album=null`；面板「未检测到 ReplayGain 标签」；无 RG 统计日志（不改变音量） | ✅ |
| 3 | 覆盖 -6 dB | 拖「曲目增益」滑杆 → 文件 `track=-6.0`；面板「曲目标签 -6.0 dB（本机设置）」；实测 `rg=-6.00dB … 实际增益=0.49–0.50x` | ✅ |
| 4 | 重启持久化 | force-stop → 重开 → 恢复队列播放：`来源=LOCAL_OVERRIDE track=-6.0`；面板仍为「-6.0 dB（本机设置）」（覆盖文件跨进程生效） | ✅ |
| 5 | 覆盖 +6 dB | 拖到 +6 → 文件 `track=6.0`；实测 `rg=6.00dB … 实际增益=2.00x`（三连采样一致） | ✅ |
| 6 | 清除回落 | 点「清除本机覆盖」→ 文件删除（`files/replaygain/` 空）；`来源=EMBEDDED track=-8.47`；实测回 0.38x；面板回「曲目标签 -8.5 dB（文件标签）」（修复后同进程复验） | ✅ |
| 7 | 双滑杆（修复后构建） | 曲目 + 专辑各拖一档：文件 `track=-2.5`（取整后文本，修复前为 `-2.4999995`）/ `album=-2.5`；实测 `rg=-2.50dB … 实际增益=0.75x`；清除后回落 0.38x | ✅ |
| 8 | 歌词 / 桌面歌词 | 倒数（服务端 40 行）：全屏歌词行「时针一直倒数着…」正常渲染；桌面歌词开 → `MusicLyricsOverlayController 更新：当前句/下一句` + 窗口 `APPLICATION_OVERLAY (276,126)`；再点关 → `桌面歌词悬浮窗已销毁` | ✅ |
| 9 | 队列 / 自然衔接 | 队列面板「播放队列（123）」+ 拖拽提示；`侧脸-RG` 播完自然切到 倒数（active item 60→61）全程 `PLAYING`，无静音段、交叉淡化关闭档 0 条日志 | ✅ |
| 10 | 稳定性 | 整轮 `FATAL EXCEPTION` / `ANR in com.zhangwenkang` / `Input dispatching timed out` / `UnsatisfiedLinkError` **0 条**（含 3 次重装 / force-stop / 重启） | ✅ |
| 11 | M4A/MP4 | 只读枚举库内 123 首音频：`m4a/mp4/aac` = **0**；本机无 ffmpeg 造样本 → **无样本待补**（解析路径由 12 项单测覆盖） | ⏳ 待样本 |

> 真机拦下并修复的缺陷（本波修复，提交见 git log）：清除覆盖后回读内嵌标签**偶发一次拉取失败被当成"无标签"写进负缓存**（面板「未检测到」且不改变音量，直到进程重启才恢复）。修复 = ①`ReplayGainReadResult`（Value / Failed）：失败不缓存、后续可重试；②换曲 / 重读前先清上一首标签（避免旧增益瞬时套到新曲）；③失败后 3 秒重试一次；④覆盖文本编码取整（`-2.4999995` → `-2.5`）。修复后重跑门禁 + 重装复验第 6/7 项。
>
> 还原：App force-stop；prefs 逐键复核 = `replaygain_mode=off` / `crossfade=0` / `eq_enabled=false` / `eq_preset=flat`；RG 覆盖目录空；`/sdcard/w35_ui*.xml` 全部删除；未改 `wm size` / density / 旋转 / Wi-Fi / 媒体音量；队列快照因测试播放前进未回滚（与 W21/W30 先例一致）。**Pad 5（43af8627）全程未占用**。

### 5.12 W39 真机验证记录（2026-10-03 04:22–04:37，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

窗口登记 / 释放见 `device-lock.md`；服务器只读；安装包 = 本 worktree `:app:phone:assembleDebug`（arm64-v8a）。Pad 5 进入音乐页时来源筛选为 **本地**（W37 遗留值），先验证本地 / 全部 / 服务器三种口径，再做下拉刷新取证，最后在离线模式下复验语义；结束时把 `pref_music_source_filter` 复原为 `LOCAL`、`pref_offline_mode=false`。

| # | 项目 | 操作 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 计数副题（三种筛选） | 本地：`共 0 张本地专辑`；全部：`共 105 张专辑`；服务器：`共 105 张服务器专辑`（切 chip 即变） | ✅ |
| 2 | 四 tab 与空态（本地） | 专辑 `共 0 张本地专辑` / 艺术家 `共 0 位本地艺术家` / 歌曲 `共 0 首本地歌曲` → 均为「本地还没有音乐 / 在『媒体库 → 本地媒体库』添加包含音乐的文件夹后回来」；歌单 `共 0 个歌单` →「服务器上没有歌单 / 下拉可刷新」 | ✅ |
| 3 | 在线下拉刷新（真实重取） | 04:31:14 在专辑列表下拉（`input swipe 900 900 900 1700 500`）→ logcat：`曲库刷新：requested offline=false` → `曲库刷新：重新请求服务器曲库 library=null` → `曲库刷新：重读本地媒体库索引 songs=0` → `曲库刷新：完成 offline=false albums=105 songs=123`；同帧截图可见顶部指示环（列表中仍显示专辑） | ✅ |
| 4 | 离线语义 | 离线模式（`pref_offline_mode=true`）→ 音乐页无来源筛选行、副题 `共 0 张专辑`、空态「离线模式还没有可播放的音乐 / 联网后在曲目菜单点「下载」，或下拉刷新本地音乐索引」；04:34:37 下拉 → logcat 只有 `requested offline=true` + `离线模式 → 只重读本机索引（不发服务器请求）` + `重读本地媒体库索引`，**无**「重新请求服务器曲库」行 | ✅ |
| 5 | K60 抽验 | 音乐页四 tab + 来源筛选 + 本地空态文案一致（`共 0 张本地专辑` +「本地还没有音乐」）；媒体库页两段式与顶栏动作同款 | ✅ |
| 6 | 稳定性 | 双机整轮 `FATAL EXCEPTION` / `ANR in com.zhangwenkang` 0 条 | ✅ |

> 未覆盖（明示）：服务器 / 全部两种空态分支在测试服务器（105 专辑 / 123 曲）不可复现，由 `MusicLibraryCopyTest` 5 项单测覆盖；下拉刷新只做只读重取，不存在写服务器动作。

### 5.13 W44 真机验证记录（2026-10-03 12:20–12:50，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

窗口登记 / 释放见 `device-lock.md`；服务器只读；安装包 = 本 worktree `:app:phone:assembleDebug`（arm64-v8a，`install -r`）。采样像素均取 `screencap` 原图（Pad 5 1600×2560 / K60 1440×3200，360dpi），截图看完即删、不入库。

| # | 项目 | 操作 / 证据 | 结果 |
|---|------|------------|------|
| 1 | A 开关关闭态（Prism / 音乐域） | 音效面板「开启均衡器」关：`checkable checked=false`；像素采样 thumb `(167,176,189)` = `onSurfaceVariant #A7B0BD`（952 px）、轨道填充 `(34,42,54)` = `surfaceContainerHigh #222A36`（4642 px）、描边 `(110,120,135)` = `onSurfaceFaint #6E7887`（1294 px）、面板底 `(23,29,37)` | ✅ |
| 2 | A 对比度 | thumb / 轨道 **6.6:1**、thumb / 面板底 **7.74:1**；同位置 M3 默认（thumb = `outline #2C3542`）对面板底仅 **1.37:1** → 提亮后约 5.6 倍 | ✅ |
| 3 | A 两套皮肤 + 开启态 | 客户端设置（Lumen）关闭态 thumb `(152,162,179)` = `textSecondary #98A2B3`、轨道 `(23,26,33)` = `panelElevated #171A21`、描边 `(107,116,131)` = `textFaint #6B7483`，thumb/底 **7.21:1**；开启态 Prism（Pad 5）轨道 `(63,201,160)` = 松石 `#3FC9A0`、拇指 `(6,18,13)` = `onBase`，Lumen（设置页）轨道 `(92,225,210)` = 极光青 | ✅ |
| 3b | A 禁用态 | Pad 5 设置页「隐藏底栏」行（平板形态 `enabled=false`）：拇指 `(107,116,131)` = `onSurfaceFaint`、轨道 `(23,26,33)` = Lumen `panelElevated`、描边 `(69,76,87)`（= `onSurfaceFaint @55%` 合成）；拇指对轨道 **3.69:1**（Prism 同式 3.24:1），禁用仍可辨 | ✅ |
| 4 | B 滑杆样式 | 音效面板 EQ 五段 + ReplayGain 两条 + 阅读器数值滑杆全部为圆点拇指：启用态拇指 `(244,241,234)` = `onSurface`（18dp 圆点，含同色柔光）、已填充轨 `(63,203,161)`、未填充轨 `(51,56,64)`（`progressTrack` 白 12% 合成）；与播放页进度条同语言 | ✅ |
| 5 | B 拖动实时生效 | EQ 60 Hz 段拖动 → 面板标签 `+0.0 dB → +3.7 dB` 即时刷新；ReplayGain「曲目增益」拖动 → `未设置 → +7.0 dB`（0.5 dB 档位吸附） | ✅ |
| 6 | B 松手落盘 | `run-as` 读 `shared_prefs/…_preferences.xml`：`pref_music_eq_custom_bands=3.7,0.0,0.0,0.0,0.0`；ReplayGain 覆盖写 `files/replaygain/<itemId>.txt`（20 B）→ 点「清除本机覆盖」后文件消失、标签回「未设置」；验证后 EQ 复原 `0.0×5` + `pref_music_eq_enabled=false` | ✅ |
| 7 | B 禁用态 | EQ 关闭时五段滑杆 `enabled=false`：thumb 40% 白（含 glow，实测中心 `(128,169,157)`）、已填充 `(45,104,92)`、未填充 `(34,40,47)`，三态仍可辨 | ✅ |
| 8 | C 全屏页手势 | 全屏播放页左滑（`input swipe 1400 1200 200 1200 150`）→ 歌词页出现 `content-desc="返回全屏播放"`；**左滑不再打开播放队列**（代码层已无该绑定，队列面板未出现） | ✅ |
| 9 | C 歌词页手势 / 下滑 | 歌词页右滑 → 回全屏（`退出全屏` 出现、`返回全屏播放` 消失）；歌词页下滑 → 回全屏；全屏下滑 → 关闭（回曲库 + 迷你条 `正在播放`） | ✅ |
| 10 | C 队列入口不回归 | 全屏页点「播放队列」图标 → 队列面板打开（`播放队列（89）` + 拖拽提示 + 条目）；面板可用系统返回关闭 | ✅ |
| 11 | K60 抽验 | 冷启动 915 ms；左滑进歌词 / 右滑回全屏；音效面板开关与滑杆同款（开启态轨道 `(63,201,160)` / 拇指 `(6,18,13)`，滑杆行 teal 轨 + 圆点） | ✅ |
| 12 | 稳定性 / 还原 | 双机整轮 `FATAL EXCEPTION` / `ANR in io.github.zhangwenkang.aurorama` **0 条**；双机 `am force-stop`、`/sdcard/w44*.png|xml` 清理、EQ 与 ReplayGain 覆盖复原、`pref_music_resume_queue` / `pref_local_library_visible` 保持 true | ✅ |

> 未覆盖（明示）：①歌词页下滑返回未在"歌词长列表非顶部"场景单独取证（与 W23 同款行为）；②K60 只做手势 + 面板抽验，未复跑 EQ / RG 落盘（同 APK、同共享组件，落盘链路由 Pad 5 取证 + 单测覆盖）；③滑杆的键盘 / TalkBack 步进未做人工走查（语义只提供 `setProgress`）。

### 5.14 W47 回归抽验（2026-10-03，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，分支 `feature/w47-full-regression`）

- 音乐页（Pad 5）105 专辑加载正常；播放 `Town of Windmill（风车小镇）` → `state=PLAYING(3)` + 迷你条 `0:02 / 2:19 · 正在播放`；K60 同款（4:07 上次播放续播位起播，`state=PLAYING(3)`，metadata = 曲名 / 专辑 / 艺人）。
- 长列表滚动（105 专辑，4 次上滑）：`gfxinfo` 610 帧 / janky 17（2.79%）/ p50 7 ms / p90 12 ms（对照 W45 本地 125 项缓存命中 0.53%，本轮为服务器海报行 + 网络加载，未定义阈值）。
- 离线模式（真断网：`svc wifi disable` + `cmd connectivity airplane-mode enable`）音乐页空态「离线模式还没有可播放的音乐 / 联网后在曲目菜单点『下载』…」正常；恢复网络与离线开关后回在线曲库。
- 0 App FATAL / ANR；设备状态已还原（见 `TEST_PLAN` §7.4）。

**M4A ReplayGain 真机补验（2026-10-03 W47-B，Pad 5）**：样本 `m4a_60s_sample_file_574KB`（服务器，574 KB / 60 s）→ 面板「未检测到 ReplayGain 标签」（log `来源=NONE`；独立复核 = 拉到全量 587,509 B，`REPLAYGAIN` 0 次 / freeform atom 0 个 → 真·无标签，非读失败）；本机覆盖 -6.0 dB → `files/replaygain/09805eb4….txt` = `track=-6.0` + 面板「曲目标签 -6.0 dB（本机设置）」；清除后回读（无负缓存）+ 覆盖 +4.0 dB 重启持久化（`force-stop` → 音乐页「上次播放」→ 恢复播放 → 面板仍 +4.0 dB）全部通过；增益应用链 = `10^(dB/20)`（未做声学测量）；覆盖已清除、`pref_music_replaygain_mode` 回 `off`，0 FATAL / ANR。详见 `TEST_PLAN` §7.5。

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
27. **横屏手机的"高"只有 411dp，单列全屏布局必被裁切**（2026-10-02 W24 真机拦下）：W23 全屏页按 `min(0.58×宽, 320dp)` 定封面尺寸，K60 横屏（914×411dp）封面 320dp + 歌名 + 歌词 + 进度条 + 传输键 + 功能键 ≈ 700dp，直接出屏（首屏只剩大封面）。→ 按 `maxHeight` 分两套布局：≥620dp 单列、矮屏两栏紧凑（左封面 / 右控制）；**全新全屏类页面落地时先按最矮目标机（横屏手机）估算固定高度**。
28. **悬浮窗节点抓不到，不能拿 uiautomator 当唯一证据**（W24 实测）：`FLAG_NOT_FOCUSABLE` 的 `APPLICATION_OVERLAY` 不在 `uiautomator dump` 里（App 内页面正常）；`dumpsys window` 的 frame + 截图像素采样 + logcat 时间戳才能覆盖"背景隐藏 / 工具条展开 / 按钮颜色"。→ 悬浮窗验收脚本统一用这三件套，不要等 uiautomator。
29. **悬浮窗像素取证前必须先关悬浮窗**（W24 实测）：悬浮窗是顶层窗口，开着它去采样歌词页 / 全屏页会串色（歌词页"当前行强调色"一度采到悬浮窗的松石色 `#5CE1D2`，误判 30 分钟）。→ 采样 App 内页面时先 `dumpsys window` 确认没有 `APPLICATION_OVERLAY` 叠加，或在采样前关掉开关。
30. **队列面板里的滑动手势会穿透到全屏页**（W24 实测）：在 `ModalBottomSheet` 里做下滑手势（想滚列表）一旦超过阈值，sheet 关闭后同一段手势会继续触发全屏页的"下滑退出"。→ sheet 内滚动用"列表区内、较小位移"的滑动，或先确认 sheet 的滚动区域坐标；需要大幅滚动时用 `uiautomator` 定位行后分批滑。
31. **歌词居中的旧实现是"首帧视口 0"的隐性 bug**（W24 实测）：`LaunchedEffect` 里读 `listState.layoutInfo.viewportSize.height` 首帧为 0，`scrollOffset` 退化为 `+40px` → 当前行停在最上方；改成"上下 contentPadding = 视口一半 + `animateScrollToItem` 后按实际行高 `animateScrollBy` 微调"，真机 offset 收敛到 `(viewport-size)/2`。→ 凡"首帧滚动定位"都要把 `viewportSize==0` 当成显式分支或等首帧布局。
32. **服务端 `Cues` 在本库全空 + 增强 LRC 标签必须剥离**（2026-10-02 W28）：全库 100 首 `GET /Audio/{id}/Lyrics` 的 `Cues` **0/100 非空**（服务端 LRC 解析器不产出 cues）→ 本库逐字样本只能来自外挂 / 导入 / 本机覆盖的增强 LRC；`<mm:ss.xx>` 若不从行文本剥离会原样显示在歌词里（用户直接看到标签）。另外 `LyricsCache` 仍只存 `startMs + text`：服务端将来有 cues、断网回落缓存时会丢逐字 → 当前刻意降级为整行（要离线逐字需扩展缓存格式）。
33. **uiautomator dump 会同时包含全屏覆盖层与底层页面的节点**（W28 实测）：全屏播放页是 `MusicModeScreen` 内的 Box 覆盖层，底层歌曲列表 / mini bar 的同名按钮节点仍在 hierarchy 里，两组 bounds 交错（底层 mini bar 五键 y≈2710–2878、全屏功能行 y≈2551–2719）。→ 点全屏按钮要按"图标行 + label 的 y 分层"核对（桌面歌词点击区 = 图标行 `[1115,2551][1283,2719]`，不是底部同名的 label 行），否则会点到底层 mini bar 的下一曲 / 播放暂停；用 `content-desc` 或 `dumpsys window` 复核更稳。
34. **MIUI 的 logcat 主缓冲很小，音频 MediaCodec debug 会吃掉周期日志**（W28 实测）：整轮调试后 `logcat -d` 只剩 ~955 行（`---------- beginning of main` 起），播放早期的逐字日志已被刷掉，导致误判"日志没打"。→ 周期性取证日志（逐字 / 上报 / 睡眠）必须 `logcat -c` 后**边播边过滤**或当场 `-d` 取，不能事后捞。
35. **音乐固定 ExoPlayer 意味着 mpv 的 EQ / ReplayGain 永远不作用于音乐**（W30 调研定论）：`PlayerHolder.audioSession()` 强制 Exo（W1 D3），`applyMusicPlaybackTuning` 对 mpv 实例静默忽略。以后若有人提"用 mpv `replaygain` / `af=equalizer` 做音乐音效"，先看 `MUSIC_PLAN` §4.5 —— 能力存在但**路径不可达**，除非重开 D3 决策（通知 / MediaSession / gapless 全要重验）。
36. **FLAC 的 metadata block 长度是 24-bit 大端，VorbisComment 内部字段才是小端**（W30 解析踩到）：探测脚本第一版用 little-endian 读 block length（`00 00 22` 读成 2228224）→ 100/100 全部"解析失败"，差点把"全库无标签"当结论。写 FLAC 解析记住：block header = 1B(last/type) + **3B 大端**长度；VORBIS_COMMENT 内的 vendor / 条目长度 = 小端。
37. **`Player.volume` 做淡入淡出的三条纪律**（W30）：① 只在音乐会话 + 档位 > 0 时驱动，暂停时不改（保持当前包络）；② 关闭档 / 会话结束 / 停止必须复位 `1f` —— 残留 0 会静音下一首甚至视频；③ 100ms 采样 + 变化阈值（>0.002）才写，避免每 tick 写音量；曲线可在日志里用 `sin(π/2·t)` 数值核对（等功率）。
38. **M3 `Slider` 的 `input tap` 不设值**（W30 真机操作踩到）：点滑杆任意位置不会改变值（只聚焦），必须 `input swipe` 拖动；实现上滑杆拖动期间只更新处理器 / 内存（实时听感），`onValueChangeFinished` 才写 SharedPreferences，避免拖动期间高频落盘（对齐 W24 悬浮窗位置的做法）。
39. **MP4/M4A 的 `ilst` 与 `data` 不是 FullBox，`meta` 才是**（W35 解析踩到）：`moov/udta/meta/ilst` 里 `meta` 带 4 字节 version/flags（QuickTime 老变体没有，需探测），`ilst` 是纯容器，`data` 的负载 = 4 字节 type indicator + 4 字节 locale + 文本（type=1 UTF-8 / type=2 UTF-16BE）。第一版单测构造器给 `ilst`/`data` 多加了一层 version/flags，导致"解析器读不到标签"的假故障——写 MP4 box 测试务必先对齐规范。另：moov 在文件尾（非 faststart）时，头部窗口读不到它，但顶层 box 头里的声明长度足以算出 moov 绝对偏移，二次 Range 即可，不需要整文件下载。
40. **rsgain 的命令形态**（W35 打标）：`easy` 子命令只接受**目录**（自动按专辑分组），单文件打标用 `custom`——`rsgain custom -s s -a <file>` 只扫不写（`-s s` 是默认档），`-s i` 才是写 ReplayGain 2.0 标签；`loudgain` 的 `-a` 是 loudgain 自己的参数，rsgain 没有 `easy -a` 这种写法。实测《侧脸》：-9.53 LUFS / peak 1.000000 → gain -8.47 dB，写入 4 个标签（TRACK/ALBUM_GAIN + TRACK/ALBUM_PEAK）；副本用 TagLib padding，文件体积不变。
41. **"读取失败"不能当"确认无标签"进负缓存**（W35 真机拦下）：清除本机覆盖后回读内嵌标签偶发一次拉取失败（无错误日志），旧实现把 `null` 写进 itemId 缓存 → 面板「未检测到 ReplayGain 标签」且不改变音量，直到进程重启才恢复。修复 = `ReplayGainReadResult`（Value / Failed）：Failed 不缓存、调用方 3 秒重试一次；同时换曲 / 重读前先清上一首标签，避免旧增益瞬间套到新曲。教训：凡"负结果可缓存"的链路都要区分**确认没有**与**读取失败**。
42. **Media3 `seekToPrevious()` 的 3 秒语义 + 播放中 uiautomator 不 idle**（W35 真机操作踩到）：位置 > 3 秒时点「上一曲」只回到本曲开头，必须快速点两次才切上一首（音乐页上一曲走 `seekToPrevious`）——验证切歌时别误判按钮失灵。另外 K60 播放中 `uiautomator dump` 经常拿不到 idle 并**留下旧文件**（看起来"界面没变"），先 `input keyevent 85` 暂停（或发键后立刻 dump）再操作；日志取证要当轮 `logcat -d`，MIUI 会把 123 首队列信息刷满主缓冲。
43. **空态不可滚动 → `PullToRefreshBox` 收不到下拉手势**（W39）：PTR 依赖子树的嵌套滚动事件，空态如果只是 `Box(fillMaxSize)` 居中放空状态，手指下拉没有任何可滚动节点消费，刷新永远不触发。修法：把空态包成 `Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Center)`——内容比视口小时仍居中、同时把手势转给 PTR；`CinefinEmptyState` 自身是 `fillMaxWidth + padding`，不会在无限高约束下崩。

## 7. 会话日志

- **2026-10-03 W44 音乐三件套（本会话，`feature/w44-music-detail`，起点 master `77feba9`）**：读 `PROJECT_PLAN` §1–§5、`MUSIC_PLAN`（D29 / D38 / D49–D55 + 踩坑库）、`UI_PLAN`（D41–D45）、`UI_DESIGN_SYSTEM` §2/§4/§5 后开工（developer.android.com 两条官方页 20 s 超时不可达，按任务书回退项目设计系统 + 既有自绘进度条口径执行）。①**A 开关**：core 新增 `CinefinSwitch`（关闭态拇指 `onSurfaceVariant` / 轨道 `surfaceContainerHigh` / 描边 `onSurfaceFaint`；开启态域媒体色；禁用态可辨），替换音效面板 / 设置卡片 / 播放器面板 / 下载 / 本地库 / 离线共 8 处裸 `Switch`；②**B 滑杆**：core 新增 `CinefinSlider`（4dp 胶囊轨 + 18dp 圆点拇指 + 极轻柔光，`steps` 吸附 / 禁用 / RTL / 36dp 触控带），替换 EQ 五段 + RG 覆盖 + 阅读器滑杆，仓库 M3 默认 `Slider` 清零；③**C 手势**：全屏左滑进歌词 / 右滑留空 / 左滑队列取消 / 下滑关闭，歌词页右滑返回，判定抽纯函数 `swipeGestureDirection`。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 `--rerun` **462 项 / 0 失败**（core 18→25、music 104→109）。真机 Pad 5 主 + K60 抽验（§5.13）：开关关闭态像素 `#A7B0BD` / 轨道 `#222A36` / 描边 `#6E7887`，对比度 6.6:1（旧默认 1.37:1）；Lumen 关闭态 `#98A2B3` / `#171A21` / `#6B7483`；EQ +3.7 dB 与 RG 覆盖 +7.0 dB 实时生效且落盘 / 清除可回读；左滑进词 / 右滑返回 / 下滑关闭 / 队列按钮全部命中；双机 0 FATAL / ANR，副作用已还原。分支已推送未合并。

- **2026-10-03 W39 音乐来源文案 + 真实下拉刷新**（本会话，`feature/w39-media-library-polish`）：①新增 `MusicLibraryCopy.kt`（纯函数 `musicLibrarySubtitle` / `musicEmptyCopy` + `MusicEmptyCopy`）并把空态接进 `AlbumList` / `ArtistList` / `SongList` / `PlaylistList`（`EmptyHint` 支持 message 且改可滚动）；②`MusicModeScreen` 内容区接 `PullToRefreshBox`（四 tab 共用，`state.refreshing` 与 `loading` 分离），`MusicModeViewModel.refresh()` 按首载 / 刷新分流并在离线分支只重读本机索引 + 记录「曲库刷新」取证日志；③计数副题按来源筛选细化。单测 `MusicLibraryCopyTest` 5 项（music 99 → 104）；门禁根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，整仓 437 项 / 0 失败。真机 Pad 5 主 + K60 抽验（§5.12）：三种筛选口径、本地 / 歌单 / 离线空态、在线下拉「重新请求服务器曲库」+ 完成计数、离线下拉「只重读本机索引（无服务器请求）」、双机 0 FATAL / ANR；新增踩坑 43。分支已推送未合并。
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
- **2026-10-02 W24-MUSIC**（本会话，`feature/w24-music-polish`，起点 master `7e092bc`）：完成用户逐条确认的 UI 细化——
  **A 悬浮窗**：3 秒无操作隐藏背景 / 边框、触摸恢复、锁定保持隐藏且单击唤出设置；工具条改 5 个纯图标按钮（无文字 / 无边框 / 无底色）；无歌词回落「歌名 / 歌手」（ViewModel 曲库快照 + 控制器按需加载双来源）；位置跨进程持久化（`pref_music_lyrics_overlay_x/y`）；全屏按钮两态色（白 / 强调色）；
  **B 歌词页**：当前行居中（首帧视口 0 修复）、去掉当前行前的「杠」改颜色 + 字号；
  **C 全屏页**：2–3 行歌词（点任意行进歌词页）、自绘细轨道 + 圆形发光拖点进度条、播放列表半屏面板（图标 / 左滑呼出，拖动 / 跳转复用）；
  **真机拦下并修复**：K60 横屏 411dp 高时 W23 单列全屏被裁切 → 按高度分「单列 / 两栏紧凑」。
  新增 4 条单测（回落 / 锁定背景 / 位置夹取 / 歌词窗口），`:modes:music` 71 → **75 项**；门禁 `assembleDebug`（含 TV）+ `ktfmtCheck` + app 51 项 + music 75 项全绿；K60 真机 11 组证据见 §5.7。
  决策 §2.9（D34–D39），踩坑 §6-27～31。**未决**：内嵌歌词 / 歌词编辑；工具条"保持显示时长"暂无档位（固定 3 秒）。
- **2026-10-02 W25-MUSIC**（本会话，`feature/w25-music-lyrics-edit`，起点 master `85690d4`，提交 `9bae78c` + 本文档提交）：完成四项——
  ①**内嵌歌词探测（只读）**：全库 100 首按「Lyric 流路径 / 内嵌 `LYRICS` / API 返回」三类对照 + 3 个「服务器无外挂 LRC」样本逐行比对 + 4 个无标签负样本 + upstream `v10.11.8` 源码佐证 → **服务器已覆盖内嵌歌词**（§4.2），按 brief 条件不做客户端标签解析；
  ②**本机歌词编辑 / 导入 LRC / 清除覆盖**：`LyricsOverrideStore`（`files/lyrics/override/<itemId>.lrc`，LRC 文本）、`LyricsEditorModel` 纯函数、编辑器对话框；来源链「本机覆盖 > 外挂 LRC > 服务端 > 缓存」；导入走系统文件选择器（UTF-8 / GBK 自动识别），**不写服务器**；
  ③悬浮窗「保持显示时长」五档（2 / 3 / 5 / 10 秒 + 常显；`pref_music_lyrics_overlay_idle` 默认 `3s`，工具条第 6 个图标循环切换）；
  ④客户端设置接入 `pref_music_resume_queue` 开关行（音乐行组，与桌面歌词同组）。
  新增 11 条单测（`:modes:music` 75 → **86 项**），门禁 `assembleDebug` + `ktfmtCheck` + app 51 + music 86 全绿；K60 真机 9 组见 §5.8（真机拦下并修复「词」面板半展开裁切；0 FATAL/ANR）。
 决策 §2.10（D40–D45）。**未决**：ID3v2 `USLT` / MP4 `©lyr` 的内嵌覆盖仅源码层推断（本库无该类样本）；离线本地文件（无网络、无缓存）取内嵌歌词的场景未做（如需另开任务）。
- **2026-10-02 W28-MUSIC**（本会话，`feature/w28-word-lyrics`，起点 master `d972315`，提交见 git log）：完成三项——
  ①**逐字歌词**：只读调研结论 = 服务端 `Cues` 全库 0/100（§4.4），可行粒度 = 增强 LRC `<mm:ss.xx>`（外挂 / 导入 / 本机覆盖）与服务端 `Cues`（映射就绪）；
  数据层新增 `LyricWord` + `LyricLine.words`、`LrcParser.parseWords`（标签剥离 + offset 同步）、`mapServerLyrics`（`position`/`endPosition` 截子串）；
  `LyricsPresenter.wordHighlights` / `activeWordIndex` 纯函数 + `wordHighlightedText`（AnnotatedString 颜色渐变）接入歌词面板 / 全屏歌词页 / 桌面歌词悬浮窗，**无逐字数据整行降级（原滚动同步路径不变）**；
  ②**纯音乐提示行时间戳清理**（W21 遗留）：`LyricsNormalizer.cleanPlaceholderTimestamp` 清理占位行文本里的 `[00:00:00]`（真机前后对比：`[00:00:00]此歌曲为没有填词的纯音乐，请您欣赏` → `此歌曲为没有填词的纯音乐，请您欣赏`）；
  ③**编辑增强**：整段偏移 + 单行微调（±100ms 档 / 自定义毫秒），逐字随行平移（首词与行时间绑定校验，失败回落整行），保存写增强 LRC 本机覆盖（`encodeLrc` 写回 `<mm:ss.xxx>`），**不写服务器**。
  新增 13 条单测（`:modes:music` 86 → **99 项**），门禁 `assembleDebug`（含 TV）+ `ktfmtCheck` + app 51 + music 99 全绿；K60 真机 9 组见 §5.9（逐字日志 + 像素推进 / 悬浮窗逐字与偏移一致 / 整行降级像素 + 0 逐字日志 / 偏移重进生效 / 0 FATAL/ANR）。
  决策 §2.11（D46–D48），踩坑 §6-32～34。
- **2026-10-02 W30-MUSIC-FX**（本会话，`feature/w30-audio-fx`，起点 master `e465a83`）：完成三项音效——
  ①**均衡器 EQ**：调研结论 = mpv `af=equalizer` 能力存在但**音乐不可达**（W1 D3 固定 Exo）→ 落 Exo 音频链：`MusicEqualizer`（5 段 60/230/910/3600/14000 Hz，RBJ peaking biquad，±12 dB，7 档预设 + 自定义）+ `MusicAudioEffectsProcessor`（16bit/float，逐样本，仅音乐会话，每约 2.5s 输出输入/输出 RMS 统计）；
  ②**ReplayGain**：全库 100 首 FLAC 只读扫描 **0 个 RG/R128 标签**（服务器 JSON 同）→ 客户端链路 = 本机覆盖文件 > 内嵌标签（FLAC VorbisComment / ID3v2 TXXX，Range 128KB 只读）→ off/track/album 三态（专辑档缺 album 回落曲目，无标签不改音量）；
  ③**交叉淡化**：单实例近似（曲尾淡出 + 曲首淡入，`Player.volume` 等功率包络，100ms 采样，关/2/4/6 秒，默认关；双实例真交叉未排期）。
  UI = 全屏播放页第六键「音效」+ `MusicEffectsSheet`；`AppPreferences` 只追加 5 键；新增 18 条单测（`:player:local` 80 → **98 项**）；
  门禁 `assembleDebug`（含 TV）+ `ktfmtCheck` + app 51 + player:local 98 + music 99 全绿；K60 真机 13 组见 §5.10（EQ 预设 1.3x / 全段 -11.7dB → 0.31x；RG -6dB → 0.50x、-12dB → 0.25x；淡化淡出 0.94→0.01 + 淡入 0.14→1.00 全程 PLAYING；关闭档 0 条曲线日志；0 FATAL/ANR）。
  决策 §2.12（D49–D51），调研 §4.5，踩坑 §6-35～38。**未决**：本库无 RG 标签（需用户打标签 / 写覆盖文件）；真交叉未排期；M4A RG 标签未实现。
- **2026-10-02 W35-MUSIC-EXTRAS**（本会话，`feature/w35-crossfade-rg`，起点 master `4c80276`）：真交叉调研 + ReplayGain 收尾——
  ①**真交叉**：只读拆解单实例约束（单 `PlayerHolder` / 单 `MediaSession` / MU-9 上报 / UI 状态源），结论 = 本波不实施，给出三阶段方案与 8–12 人日工作量（D52 / §4.6）；
  ②**M4A/MP4 RG**：`----` free-form 原子解析（mean / name / data，UTF-8 与 UTF-16）+ moov 在尾时按顶层 box 链二次只读 Range（D53）；
  ③**本机增益覆盖 UI**：音效面板 ReplayGain 组两滑杆（-12..+12 dB / 0.5 dB 步进，拖动实时、松手落盘）+ 清除；写 `<files/replaygain/<id>.txt`、缓存失效 + 播放链重读（D54）；
  ④**本地打标联调（负责人安排的方法 2，无设备）**：经代理下载 rsgain 3.8（Win64，工具在 `%USERPROFILE%\.codex\tools\rsgain`，不入库）；基线只读确认原文件 3 条普通 VorbisComment、0 条 RG；副本 `test_files/侧脸-RG.flac` 用 `rsgain custom -s i -a` 写入 TRACK/ALBUM gain = -8.47 dB、peak = 1.000000（Loudness -9.53 LUFS），SHA-256 `D85C800B822FC68B655E5EAA84BF83C656B9B087DAC941EC462C144A6BC0F394`、23,189,955 B；原文件 SHA-256 前后一致（`ABE5AE…0166`，未改动）；上传目标 = Jellyfin「音乐」库同目录（服务器 `/medi/音乐/侧脸-RG.flac`），待负责人触发扫描后做 App 端识别验证；
  ⑤新增 6 条单测（`:player:local` 98 → **104 项**），门禁 `assembleDebug`（含 TV）+ `ktfmtCheck` + app 61 + player:local 104 + music 99 全绿；
  ⑥**K60 真机验收完成**（2026-10-02 23:10–23:35，见 §5.11）：RG 副本 -8.47 dB → 0.38x（面板「文件标签」）、原曲对照 NONE（「未检测到」）、覆盖 -6.0 → 0.50x / +6.0 → 2.00x / 双滑杆 -2.5 → 0.75x / 清除回落 -8.47、重启持久化；歌词 / 桌面歌词 / 队列 / 自然衔接回归；0 FATAL·ANR；
  ⑦**真机拦下的缺陷已修复并复验**：封面清除后回读偶发失败被负缓存（面板"未检测到"直到重启）→ `ReplayGainReadResult` 失败不缓存 + 换曲清旧标签 + 3s 重试 + 覆盖编码取整；重跑五项门禁全绿、重装复验；
  ⑧还原：prefs 逐键（RG=off / crossfade=0 / eq=false / preset=flat）、覆盖目录空、force-stop、`/sdcard/w35_ui*.xml` 清理；Pad 5 未占用；队列快照前进未回滚（先例一致）。**未决**：真交叉排期（§4.6 方案就绪）；M4A 设备端样本（库内 0 个）。
