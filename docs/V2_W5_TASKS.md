# 2.0 W5 · 音乐域（任务卡）

> 状态：`PLANNED`（2026-11-20 **W5 会话1 收官**：T0 现状核对完成（§7 全 50 条逐条，见下）；设计规格四节 Kimi 定稿（换肤布局/迷你条与调色板/动效/歌词）；任务卡 T1–T11 定稿待派；W4 残留 L3、L7、coroutines-test 已收 T10。**实现入会话2**，开工提示词见下）
> 依据：`docs/V2_REQUIREMENTS.md` §7（V2-MUSIC）与 §15 波次（W5 ← W3）；设计唯一权威 `docs/V2_DESIGN.md`；W4 已落地 token（玻璃/弹簧）见 `core/presentation/theme/`。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W5 开工基线 = W4 收官 HEAD（T10 `47095ed7` 之后，以 git log 为准；会话2 实际基线 `4c69bf31`）。
- **会话2 首批三卡已并入（2026-11-20）**：T1 `bdbaee9c`（merge `f4640eb1`）、T10 `dbd264f6`（merge `1c8c1c19`）、T4 `c98d0f7c`→rebase 后 `41b404cb`（merge `e50c293f`），全部入 `codex/v2-integration`；三卡子代理门禁各自绿，T1 合并后集成门禁绿，T1+T4+T10 全量集成门禁与 K60 统一验证（T4 转码中 + T1 四主题走查）在途。
- **设计追认（Kimi，2026-11-20）**：定稿一「横屏手机（高 <411dp）保留紧凑」对 K60 横屏（恰 411dp）是边界空洞，T1 实现为**矮屏（高 <620dp）优先紧凑两栏**并钉单测——追认为定稿口径，定稿一 §1 已同步修订。
- **会话2 返修×2（2026-11-20 晚，Kimi 验收发现）**：① T1r 音乐域主题跟随——`MusicModeScreen.kt:401` `CinefinTheme(domain=Music)` 不传 themeId 把音乐域钉死 aurora（amoled 下实测仍 #080D17；dynamic 呈 aurora 属 W1 架构预期），T1 子代理返修中（分支 `codex/v2-w5-t1r`）；② T4r J12 流鉴权——T4 `MusicStreamUrl` 用 `?api_key=` 拼鉴权，违仓库既有 `JellyfinAuth` 定论（2026-10-17 探针：J12 仅收 `Authorization: MediaBrowser Token="…"` 头，api_key/X-Emby/Bearer 一律 401），实测矩阵：universal 无鉴权 401、有效 api_key 也 401、头 200（真实转码 MP3 流）；视频流端点匿名可达（故视频链路一直"看起来正常"）。T4 子代理返修中（分支 `codex/v2-w5-t4r`），修法=删 api_key + 音乐流按主机注头 + 契约测试改钉无 api_key + 审计音乐域其它 api_key 残留。
- **服务端排查结论（用户线索命中）**：jellyfins 12.4.0 auth 健康——错密码 400 是 J12 对不完整 Authorization 头的端点前校验；新鲜 token + 新式头 `/Users/Me`=200、登录=200、转码流=200。设备登录态已由 Kimi 重新登录恢复。
- **遗留观察项**：① SyncPlay unpause 回声时序（T10 发现：媒体键/UI 键 unpause 后本地一度仍暂停，疑组 pending 回声，非 L8 引入）——W6 或播放器线另立小卡评估；② core `CinefinIconButton` 可点区实为 44dp（T1 在音乐模块内补 48dp 触控壳），后续 core 侧把 clickable 提 48dp 后删壳；③ L7 mpv content:// 字幕修复未做 SAF 本地视频端到端（设备无素材），W4-T7 复验时补。
- **T0 ✅（2026-11-20，Kimi 亲自）**：§7 全 50 条现状核对完成，结论见下文「T0 现状核对」。要点：播放层（单队列+随机/循环+冷启动恢复+MediaSession）、歌词全链路、ReplayGain、EQ 5 段、交叉淡化包络、睡眠定时已具备；缺口集中在 TrackSource 模型、/Audio/universal 协商、双队列、EQ 10 段、倍速、音频信息面板、波浪进度条、封面调色板、容器变换动效、失败恢复链。
- **W4 残留并入 W5**：L3（播放页 themeId 跟随 aurora↔amoled，方向已拍板）、L7（mpv 内核 content:// 外挂字幕）、L8（媒体键暂停绕开 SyncPlay 组闸门，2026-11-20 W4 收官复验新立）、`app/phone` coroutines-test 硬编码收 catalog——四项合为 T10 收尾小项包（沿 W4 T9 先例，一卡一 commit）。W4 已正式收官（D12/B5 双账号复验双过）。
- **工具**：`tools/backup-docs.ps1` 已同步入 worktree（2026-11-20，参数化 `-DocsPath` 默认取脚本所在仓 docs/，主仓与 worktree 各自备份自己；keep 池共用 10 份）。
- **本次质量门**：相关模块单测 + `ktfmtCheck` + `:app:phone:assembleDebug --max-workers=2` 绿；核心链路（起播/切歌/队列编辑/歌词跟随/失败恢复）K60（`8e875894`）真机取证；jellyfin-test 与生产 jellyfins 同实例（12.4.0），生产永久只读（App 播放链路自身请求除外）。
- **环境备注**：`export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'`；K60 density 420 = 2.625 px/dp；CI 仅 master push/PR 触发。**设备用后必停播放**：取证完确认 media_session state=NONE，`am force-stop io.github.zhangwenkang.aurorama.debug` 兜底。
- **设计分权**：一切视觉/动效/布局决策以本文档「设计定稿」四节为准（Kimi 亲自定稿），deepseek 零设计权。

## W5 会话2 开工提示词（可直接粘贴）

```
继续 Aurorama 2.0.0 W5 音乐域实现会话。你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W5_TASKS.md 状态块与任务卡
→ docs/V2_REQUIREMENTS.md §7 + docs/V2_DESIGN.md → 记忆库 MEMORY.md。
W5 状态：T0 现状核对完成、设计规格四节已定稿（换肤布局/迷你条与调色板/动效/歌词）、
任务卡 T1–T11 已定稿待派，依赖顺序见任务卡表。
本会话任务：按依赖顺序派卡。首批可并行：T1（modes/music 换肤布局）与 T10（W4 残留小项包，
app/phone+gradle+player/local 小改，含 L8 媒体键接 SyncPlayTransportGate）不同模块；
T4（TrackSource 模型，player/core+modes/music data）先行解锁 T5/T6。
设计决策一律以 V2_W5_TASKS.md「设计定稿」四节为准，deepseek 零设计权。
纪律：每任务一个子代理一个 commit；门禁=相关模块单测+ktfmtCheck+构建（--max-workers=2）；
核心链路 K60 真机验证；设备用后必须停播放（media_session state=NONE + force-stop 兜底）。
红线：生产 jellyfins 只读；只删自己创建的文件。
```

## T0 现状核对（2026-11-20，对照 §7 全 50 条）

图例：✅ 已具备 / ⚠️ 部分具备（差距见注）/ ❌ 缺失 / 🔍 已实现待真机回归确认

### §7.1 引擎与数据层（MUSIC-01~08）

- 01 ⚠️ 混合队列已可播（服务器曲 + 本地 SAF `content://` 走同一 `MusicPlaybackController`，`MusicTrackResolver` 双路径）；但无需求定义的 `TrackSource` 抽象——现状是 `PlayerItem` + `MusicSong.source` 枚举近似，来源语义不进播放层模型。
- 02 ❌ 字段不齐：`MusicSong` 有 itemId/name/album/artist/indexNumber/runtimeTicks/imageUri/source/localUri；缺 sourceType/streamUrl/bitrate/codec/sampleRate/bitDepth/replayGainTrack/replayGainAlbum/coverArtUrl/lyricsRef（ReplayGain 由 `ReplayGainTagReader` 单独 Range 解析，不进模型）。
- 03 ❌ 未走 `/Audio/{itemId}/universal` 能力协商：现状 `getMediaSources(includePath=true)` → `transcodingPath ?: path` 二选一（`MusicTrackResolver.resolve`）；无解码能力/音质偏好决策、无 audioCodec/audioBitRate/enableRedirection 传参、无「转码中」非侵入标注。
- 04 ✅ 本地元数据首次入库缓存（`LocalLibraryEntry`：artist/album/trackIndex/duration/coverUri 落 Room，W37）。
- 05 ✅ ExoPlayer `setMediaItems` 播放列表天然 gapless（内核预加载下一首、无间隙切换）；`pauseAtEndOfMediaItems` 音乐期间关闭已保链路。注意：交叉淡化是单实例音量包络，与 gapless 不冲突。
- 06 ⚠️ 交叉淡化已有（`MusicCrossfadeMath` 等功率曲线 + `Player.volume` 包络，100ms 采样）；差距：档位仅 0/2/4/6s（需求 0–12s 可调）；包络按位置全程生效（手动切歌/seek/暂停也触发淡入——需求限定「仅队列自然推进触发」）；短曲跳过规则 `max(20s, 3×fade)` 未实现（现状两侧窗口重叠时取 min 近似）。
- 07 ✅ ReplayGain 三态（off/track/album，默认 track 待核）+ 内嵌标签 Range 解析（FLAC VorbisComment/ID3v2 TXXX，前 128KB）+ 本机覆盖文件 ±12dB；专辑档缺 album 标签回落曲目档（MUSIC_PLAN D50）。
- 08 ✅ 单 MediaSession 完备（`CinefinPlaybackService`：通知/锁屏/线控/蓝牙，音乐条目路由回音乐播放界面，元数据含 artist 供系统媒体胶囊）。

### §7.2 队列（MUSIC-09~14）

- 09 ❌ 无双队列模型：无独立「接下来播放」队列；`insertNext` 只是往当前曲目后插一首（播完残留于队列中）。
- 10 ⚠️ 队列 sheet（`QueueSheet`）支持长按拖拽排序、点击跳转、✕ 移除；无左滑移除；无「接下来播放」轻视觉分区。
- 11 ⚠️ 操作大部齐：设队列+起始索引（setQueue）、追加（appendToQueue）、前插（prependToQueue）、移动（move）、移除（removeAt）、清空全部（stop）、跳到索引（jumpTo）、上/下一首、随机、三种重复模式；缺「清空接下来播放」（无双队列）。
- 12 ❌ 随机只有单开关（ExoPlayer shuffleMode）；无「随机整个队列」vs「随机并保持当前曲目居首」双子模式。
- 13 ✅ 冷启动恢复（`MusicQueuePersister` + Room 快照：曲目列表/当前索引/位置（5s 节流）/repeat/shuffle 含实际序与 seed；偏好开关 `musicResumeQueue`）。
- 14 ❌ Auto DJ 零实现（SHOULD；Jellyfin InstantMix API 现成，列 T9 评估）。

### §7.3 播放器 UI（MUSIC-15~23）

- 15 ✅ 迷你条常驻 + 点击进全屏；全屏是音乐页内覆盖层（`MusicNowPlayingScreen` 非路由，`BackHandler` 管理），关闭后底层列表状态保持。
- 16 ✅ 平板双栏（`LibraryPane`/`DetailPane`）+ 底部播放条；手机底部导航 + 迷你条。
- 17 ❌ 无封面提取 tonal palette：`MediaColors` 是按内容域静态派生（`mediaColorsFor(domain, colors)`），不随封面变化；迷你条是纯色 `surfaceContainerHigh`，未玻璃化。
- 18 ⚠️ 封面加载走 `ArtworkThumb`（Coil）；无模糊→清晰渐进加载、无切歌交叉淡化（硬切）。
- 19 ✅ 全屏结构大体符合（收起钮/封面/曲名艺人/歌词预览/进度条/传输三键/五键功能行）；缺工具栏上下文菜单（需求「收起、上下文菜单」）。
- 20 ⚠️ 封面取 `min(宽×0.58, 320dp)`（需求 78–82% 手机宽）；宽屏无「左封面控制/右歌词队列」分栏（仅横屏手机紧凑两栏）；点击封面不切换歌词视图（现左滑进歌词）。
- 21 ❌ 进度条是直线细轨 + 发光圆形 thumb（`MusicProgressBar`），非波浪形；无「正在跳转到 mm:ss」拖拽提示；松手直接 seek 无 spring 回弹。
- 22 🔍 传输键触控目标（`CinefinIconButton` 48dp 族）列 T1 量测；播放/暂停视觉权重已有（`PrimaryPlayButton`）。
- 23 ⚠️ 迷你条有封面缩略/标题/时间/播放暂停/下一首/关闭；标题是 Ellipsis 非 marquee；无极细进度线；无左右滑切歌；与全屏共享状态（同一 VM）✅；不遮挡底部导航 ✅。

### §7.4 歌词（MUSIC-24~29）

- 24 ⚠️ 三来源在（本机覆盖 > 外挂 .lrc > 服务端 `/Audio/{id}/Lyrics`（服务端自动提取内嵌）> 本地缓存）；缺 `.elrc` 支持（仅 .lrc）；外部兜底缓存 ✅。
- 25 ⚠️ 逐行时间戳 ✅；词级粒度：服务端 Cues 映射数据链就绪 + `LyricsWordHighlight` 组件在，但本库实测 100 首 Cues 全空（MUSIC_PLAN §4.4）——词级高亮无真实数据可验；.elrc（增强 LRC 词级）未解析。
- 26 ⚠️ 左滑/点击歌词预览进歌词页（状态切换），无 280–350ms 封面淡出+歌词淡入转场。
- 27 ✅ 当前行高亮放大 + 上下文降透明度 + lerp 跟随（`LyricsCenterScroll`）。
- 28 ✅ 手动滚动暂停跟随（`followState.manualPause`）+ 点「回到当前行」恢复。
- 29 ✅ 点击歌词行 seek（`onSeekLyricLine`）。

### §7.5 音频设置与增强（MUSIC-30~34）

- 30 ⚠️ EQ 已有但 5 段（`MUSIC_EQUALIZER_BAND_COUNT=5`，Biquad 链挂 ExoPlayer 音频链，后台保持生效 ✅）；预设 6 个（原声/低音增强/人声/摇滚/流行/古典+自定义保存 ✅）；需求 ≥10 段 + 8 预设（缺 Jazz、Treble Boost）。
- 31 ❌ 无独占/bit-perfect 旁路提示（全链路无 bit-perfect 概念）。
- 32 ❌ 音乐倍速零实现（`MusicPlaybackController` 无 setSpeed；ExoPlayer 本身 Sonic 保调现成）。
- 33 ⚠️ 睡眠定时在（`SleepTimerController` 进程级，档位 10/20/30/60 + 自定义 1–240 分钟，W55 统一口径）；差距：需求档 15/30/45/60/90；无「播完当前曲目停止」；无结束前 ~5s 淡出。
- 34 ❌ 无音频输出信息面板（源格式/采样率/位深/输出格式/转码/码率/bit-perfect 均无展示；`PlayerKernelMediaInfo` 仅视频）。

### §7.6 本地媒体、动效、手势与容错（MUSIC-35~50）

- 35 ⚠️ 本地元数据有 title/artist/album/trackNumber/duration/内嵌封面；缺 albumArtist/discNumber/genre/year/bitrate/sampleRate/bitDepth/内嵌歌词入库/ReplayGain 入库（RG 走播放时 Range 解析不进库）。
- 36 ⚠️ 本地格式识别 mp3/flac/m4a/aac/opus/ogg/wav（`LocalMediaExtensions.MUSIC`）；ALAC 由 m4a 容器覆盖（未显式标注）；缺 APE/DSD 识别与「引擎不支持」明确提示（不识别=不出现在库，无提示层）。
- 37 ✅ 混排融合（来源筛选 ALL/SERVER/LOCAL + 来源徽标；仅信息展示区分）。
- 38–44 ❌ 动效体系未做：无容器变换（迷你→全屏是状态切换）、无播放暂停脉冲、无切歌交叉淡化+位移+文字 stagger、无队列拖拽抬升/让位弹簧、无减少动态降级（`animatorDurationScale` 全仓未消费）。歌词跟随 lerp（43）已有 ✅。
- 45 ⚠️ 手势：全屏左滑进歌词/下滑收起/歌词页右滑返回在（`swipeGestures`）；无系统化优先级仲裁（系统返回 > 下拉收起 > 切歌 > 进度）；迷你条无切歌手势。
- 46 ⚠️ 下滑收起有（`onSwipeDown`），无 18% 屏高阈值/速度阈值、无拖动时封面缩放降圆角、无松手回弹。
- 47 ⚠️ 长按曲目 = 进多选批量模式（播放/下载/收藏/删除/从歌单移除），非需求的「上下文菜单 bottom sheet（播放下一首/加队尾/加播放列表/音频信息/下载）」；语义近似但形态不同——T1 定稿为 bottom sheet 上下文菜单（多选模式保留为另一入口）。
- 48 ❌ 音乐播放错误链零处理：`MusicPlaybackControllerImpl` 无 `onPlayerError` 监听；无自动重试 1 次、无 snackbar、无自动跳下一首。
- 49 ⚠️ 本地缺失/被移动：歌单投影层有「不可用」占位（`isPlaylistPlaceholder`）✅；播放队列中缺失文件无标记、无跳过语义（Exo 会抛错停播）；缓冲中指示依赖 Exo 默认（无轻量指示 UI）。
- 50 ✅ 已修并钉死（`MusicSelectedPlaybackTest`：点歌只载该曲、随后点歌追加队尾不覆盖，含 pending/乱序/失败 12 例）。

## W5 任务卡（定稿）

每任务一个子代理一个 commit；门禁 = 相关模块单测 + `ktfmtCheck` + `assembleDebug --max-workers=2`；播放行为核心链路必须 K60 真机取证（jellyfin-test）。设计决策一律以本卡「设计定稿」四节为准，deepseek 零设计权。依赖：T4 解锁 T5/T6；T2/T3 依赖 T1 落成的布局骨架；T9 是纯调研卡随时可插。

| 卡 | 内容 | 需求依据 | 验收要点 |
|---|---|---|---|
| T1 | 音乐界面 K1 换肤与布局校准：全屏播放页结构对齐（工具栏补上下文菜单、封面 0.80×宽上限 360dp、封面点击切封面/歌词视图）；宽屏（≥840dp 或横屏平板）左封面控制/右歌词·队列 Tab 分栏；音乐库页圆角清理（`MusicModeScreen.kt:1251` 等，按 W4 映射规则）；长按曲目改 bottom sheet 上下文菜单（播放下一首/加队尾/加播放列表/音频信息占位/下载——音频信息项 T6 前可置灰或隐藏，Kimi 定：隐藏）；来源徽标 K1 化；四主题切换验证 | MUSIC-15/16/19/20/22/37/47 | 四主题无残留色；封面宽度量测命中 78–82%；宽屏分栏两区独立滚动；上下文菜单五动作齐全 |
| T2 | 玻璃迷你播放器 + 封面调色板：迷你条悬浮玻璃化（参数见设计定稿§2）+ 极细进度线 + marquee 标题 + 左右滑切歌（阈值见定稿）；封面调色板（`androidx.palette` 新依赖入 catalog，按 itemId 缓存，提取失败回落域色）驱动迷你条与全屏播放页 `LocalMediaColors` 局部覆盖 | MUSIC-17/23 | K60：玻璃参数量测命中 token；滑切歌不抢边缘返回；调色板换曲换色、无封面回退域色 |
| T3 | 音乐动效包：容器变换（迷你↔全屏）、播放暂停脉冲、波浪进度条（含拖拽提示与回弹）、切歌交叉淡化+位移+文字 stagger、歌词转场、下拉收起（阈值+封面缩放回弹）、队列拖拽抬升/让位/落位弹簧、减少动态降级（`CinefinMotion.reduceMotion` helper 入 core） | MUSIC-21/26/38/39/40/41/42/44/45/46 | K60 动效走查单逐项；可中断性（连点/连滑无 ANR 无跳变）；减少动态开启后转场全降级 |
| T4 | TrackSource 模型与音质协商：`player/core` 定义 `TrackSource`（需求全字段）+ `MusicSong`/解析链对齐；`/Audio/{itemId}/universal` 协商（解码能力用 W4 T3b `DevicePlaybackCapabilities`；音质偏好入 AppPreferences：自动/原始禁转码/320/256/192/128 kbps——沿用 V2-D116 旧裁决口径）；转码时传 audioCodec/audioBitRate/enableRedirection + UI 非侵入「转码中」标注（播放页信息区小字）；本地元数据扩展入库（albumArtist/discNumber/genre/year/bitrate/sampleRate/bitDepth，含 Room 迁移）+ 格式识别补 APE/DSD 与不支持提示 | MUSIC-01/02/03/04/35/36 | data/player 契约测试钉请求形状（fake 不替生产映射作证）；K60 真实转码档播放可见「转码中」；Room 迁移实测 |
| T5 | 队列增强：双队列模型（「当前播放队列」+「接下来播放」手动插入优先、播完不残留）；队列 sheet 双分区视觉（接下来播放更轻权重）+ 左滑移除；随机双子模式（随机整个队列/随机并保持当前居首）；清空接下来播放 | MUSIC-09/10/11/12 | 单测覆盖双队列消费顺序与持久化（接下来播放是否入快照：Kimi 定——入，标注类型）；K60 队列编辑全流程 |
| T6 | 音频设置增强：EQ 升 10 段（31.25/62.5/125/250/500/1k/2k/4k/8k/16k Hz）+ 补 Jazz/Treble Boost 预设（旧自定义 5 段偏好迁移规则见设计定稿§3）；音乐倍速 0.5–2.0×（Sonic 保调，默认 1.0，入口放音效面板不显眼位）；睡眠定时档位改 15/30/45/60/90（保留自定义 1–240）+「播完当前曲目停止」+ 结束前 5s 淡出（复用交叉淡化包络通路）；音频输出信息面板（源格式/采样率/位深/输出格式/是否转码/码率；bit-perfect 行恒显「非 bit-perfect（Android 混音）」——满足 MUSIC-31 提示义务，不做独占输出） | MUSIC-30/31/32/33/34 | EQ 新段数单测+真机听感抽查；倍速保调 K60 取证；睡眠到点淡出 logcat 音量曲线；信息面板数据与 Exo Format 一致 |
| T7 | 交叉淡化口径对齐：档位改 0–12s 连续可调（1s 步进）；触发源限定队列自然推进（手动切歌/拖拽进度/暂停恢复不触发，需切歌原因可判定——`MEDIA_ITEM_TRANSITION_REASON_AUTO` vs `SEEK`）；短曲跳过 `max(20s, 3×fade)` | MUSIC-06（+05 回归） | 纯函数单测钉触发矩阵；K60 自然切歌可闻淡接、手动切歌无淡入 |
| T8 | 容错与恢复：`MusicPlaybackControllerImpl` 补 `onPlayerError`——自动重试 1 次（延迟 1s）→ 仍失败非阻塞 snackbar + 自动跳下一首（队列尾=停）；本地缺失文件标记「不可用」保留队列、播放跳过、允许手动移除；缓冲中轻量指示（迷你条与全屏进度区缓冲态）且可暂停 | MUSIC-48/49 | 单测 fake 错误序列；K60 断网/坏源实测重试与跳曲；snackbar 文案三语言 |
| T9 | Auto DJ 评估（纯调研，SHOULD）：Jellyfin InstantMix API（SDK `instantMixApi`）现成度、队列播完追加的接缝（`STATE_ENDED` 钩子）、偏好开关位置；产出 go/no-go 报告，go 则立实现卡 | MUSIC-14 | 报告并入状态块；不派实现 |
| T10 | W4 残留收尾小项包（一卡一 commit）：① L3 播放页 themeId 跟随（`PlayerActivity` 的 `CinefinTheme(themeId=DEFAULT)` 改读全局主题流，light/dynamic 映射 aurora dark，amoled 直通——W4 已拍板方向）；② `app/phone/build.gradle.kts:219` coroutines-test 收 catalog（`libs.kotlinx.coroutines.test`）；③ L7 mpv 内核 content:// 外挂字幕核查修复（`sub-add` 打不开 content:// 时经 fd/临时副本通路，先核后修）；④ L8 媒体键暂停绕开 SyncPlay 组闸门（`CinefinPlaybackService` MediaSession onPlay/onPause 回调接 `SyncPlayTransportGate`，与 UI 键同走「只发请求不写引擎」闸门，修法已记在 `V2_W4_TASKS.md` 收官节） | W4 残留表 L3/L7/L8/T9 遗留 | K60 播放页 aurora↔amoled 切换取证；构建绿；L7 有结论（修复或记录边界）；L8 单测 + K60 实组：耳机/通知栏暂停触发组广播 |
| T11 | 歌词域核查补缺 + 域验收：`.elrc` 解析（词级时间戳并入 `LyricWord`）；词级高亮用 .elrc fixture 真机/单测验证；全量验收清单（50 条逐条 K60/单测归证） | MUSIC-24/25 + 全域 | .elrc fixture 词级高亮上屏；验收单逐条 ✅/⚠️/❌ 有归属 |

## 设计定稿一：K1 换肤与布局（Kimi，2026-11-20，deepseek 零裁量）

依据 MUSIC-15/16/19/20/22/37/47 + `V2_DESIGN.md` §3（K1 token）。音乐页是普通内容页，**直接跟随 App 全局主题四变体**（不像视频播放页强制深色基调——视频是画面可读性约束，音乐无此约束；封面调色板（T2）只做 `LocalMediaColors` 局部覆盖，不动全局色板）。

### 1. 全屏播放页结构（MUSIC-19，写死顺序）

安全区 → 工具栏（左「收起」、右「上下文菜单 ⋮」：队列/音效/睡眠定时/分享占位不做——菜单项 = 队列、音效、睡眠定时三项）→ 封面 → 曲目信息（曲名 18sp 500、艺术家 15sp 400 `onSurfaceVariant` **可点击跳艺术家页**——艺术家页已有 `ArtistList` 详情）→ 波浪进度条（T3）→ 主传输三键 → 次级操作行（播放模式/收藏/队列/歌词/音效 五键 44dp）→ 安全区。横屏手机（高 <411dp）保留现有两栏紧凑布局。

- **封面**：手机竖屏 = `min(宽×0.80, 360dp)`（需求 78–82%），圆角 `CinefinShapes.Card`（20），1dp `glassBorder` 描边；**点击封面 = 切换封面/歌词视图**（MUSIC-20；与左滑进歌词并存），转场见定稿三。
- **宽屏**（`maxWidth ≥ 840dp` 且非矮屏）：左右分栏——左栏（封面+信息+进度+传输，居中，最大 480dp 列宽）/右栏 Tab「歌词 | 队列」（默认歌词），两栏独立滚动；分栏间无分隔线，间距 `CinefinSpacing.Space6`。**矮屏优先**（2026-11-20 追认）：`maxHeight < 620dp`（含全部手机横屏，K60 横屏高恰 411dp 属此类）保留现有两栏紧凑布局，不走宽屏分栏。
- **上下文菜单（MUSIC-47）**：长按曲目 = `ModalBottomSheet`（复用 QueueSheet 的容器参数：`colors.surfaceContainer` + 拖拽柄）五动作：播放下一首 / 加到队尾 / 加入播放列表 / 音频信息（T6 未交付前**隐藏**该项）/ 下载·离线；现有多选批量模式保留（工具栏「多选」入口），长按不再进多选。
- **圆角清理**：`MusicModeScreen.kt:1251`（12→按 W4 规则 5–12 档→`CinefinShapes.Xs`）及 grep 新发现的 `RoundedCornerShape(<数字>.dp)`（theme 定义文件除外），映射规则同 W4 T1 §3。

### 2. 玻璃迷你播放器（MUSIC-17/23，T2 执行）

- **形态**：悬浮条——水平外边距 `Space3`(12)、底部悬浮于导航栏上方 `Space2`(8)，高 64dp（现 72dp 收窄），圆角 `CinefinShapes.Glass`（取 32 档），底 `colors.glass` + 1dp `colors.glassBorder` + 顶部内高光（0.5dp 白 6%，与 W4 玻璃容器同法）；平板形态 = 底部通栏播放条（同一组件，外边距 0、圆角 0——宽度 ≥840dp 时退化）。
- **组成**：封面缩略 40dp（`TwoXs`）→ 标题（marquee：超出宽度滚动，首尾停顿 1.2s，速度 30dp/s）→ 艺术家次行 12sp `onSurfaceVariant` → 播放/暂停 44dp → 下一首 44dp → 关闭 ×（现状语义保留：停播清内存队列、存档保留）。**极细进度线** 2dp 贴条上缘，`media.base`（调色板覆盖后随封面色），暂停时 alpha 40% 静态（MUSIC-40 后半）。
- **左右滑切歌**（MUSIC-23）：水平位移 ≥56dp 且水平速度 ≥800dp/s 触发；迷你条左右各留 16dp 不响应（系统边缘返回安全区，MUSIC-45）；滑动中条目随指位移 + 邻曲封面从反侧探入（位移量 = 手指位移，松手未达阈值 `SpringSnap` 回位）。
- **展开/收起**：点击或上滑（位移 ≥48dp 或向上速度 ≥600dp/s）展开全屏；转场见定稿三 §1。

### 3. 封面调色板（MUSIC-17，T2 执行）

- **提取**：新增唯一依赖 `androidx.palette:palette-ktx`（入 version catalog，`palette = "1.0.0"`）；Coil 成功回调取 Bitmap（缩至 ≤96px 边）→ `Palette.from(bmp).generate()`；取色优先级 `vibrant ?: muted ?: dominant`；按 itemId 缓存（进程内 `LruCache(32)`，不落盘）。
- **派生与作用域**：提取色 → `MediaColors` 派生集（`base`=提取色、`bright`=提亮档、`container`/`over` 用既有 `derivedOver(surface)` 同法合成），**仅** `CompositionLocal` 覆盖迷你条与全屏播放页子树；提取失败/无封面/纯色封面（饱和度 <0.08）回落域色 `mediaColorsFor(Music, colors)`——兜底逻辑写纯函数可单测。
- **可读性护栏**：`base` 用于进度线/当前行歌词/高亮时，对 `surface` 对比度 <3:1 自动提亮（步进 +8% 明度，上限 3 步，纯函数单测钉死）；文字永远走 `onSurface` 系，不直接用提取色。

## 设计定稿二：动效规格（Kimi，2026-11-20，deepseek 零裁量）

依据 MUSIC-21/26/38~46。复用 W4 已落地 token：`CinefinMotion.SpringControlIn(0.86,400)`/`SpringOvershoot(0.62,520)`/`SpringSnap(0.75,600)`/`Fast=200`/`Player=280`/`Stagger=40ms`。全部走 `animate*AsState`/`AnimatedVisibility`，**必须可中断**，禁止手写不可中断动画。

### 1. 新增 motion token（进 `core` `CinefinMotion`）

```kotlin
/** 迷你↔全屏容器变换展开（350–400ms 档）。 */
val SpringExpand = spring<Float>(dampingRatio = 0.86f, stiffness = 300f)
/** 容器变换收起（约 300ms 更快回）。 */
val SpringCollapse = spring<Float>(dampingRatio = 1f, stiffness = 380f)
/** 减少动态：animatorDurationScale==0f 时为 true——转场降级 Crossfade(150)、弹簧降级 tween(150)。 */
@Composable fun reduceMotion(): Boolean  // 读 LocalContext animatorDurationScale，缓存
```

### 2. 容器变换（MUSIC-39）

迷你条 ↔ 全屏页共享元素：封面（40dp↔大封面）、标题、播放键。展开 = 全屏页 `scaleIn(0.94→1, SpringExpand)` + `fadeIn(tween(280))` + 封面从迷你条位置/尺寸插值到大封面位（`Modifier.animateBounds` 不可用则共享元素简化：封面 `SharedTransitionLayout` 不做——**Kimi 定：不做真共享元素**，用「全屏页整体弹簧展开 + 迷你条同时 `fadeOut(150)`」近似，记录偏离：Compose 1.12 的 shared element 样板成本高、收益边际）；收起反向 `SpringCollapse` + `fadeOut(200)`。迷你条本体重现 = `scaleIn(0.96, SpringCollapse)` + `fadeIn(200)`。

### 3. 播放/暂停脉冲（MUSIC-40）

按下播放键：容器 scale 1→0.92→1，`tween(180, Standard)`（分两段 90+90 或 `SpringOvershoot` 一次——Kimi 定：`SpringOvershoot`，目标 0.92 后回弹至 1，一次动画语义更简）；全屏大播放键与迷你条键同一规格。暂停时波浪进度压平（见 §4）+ 迷你条进度线转静态（alpha 40%，`tween(Fast)`）。

### 4. 波浪进度条（MUSIC-21，替换现有 `MusicProgressBar`）

- **形态**：Canvas 正弦波——播放中：振幅 3dp、波长 28dp、相位速度 1.5 周/s（流向右侧）；暂停：`tween(Fast)` 振幅压平为直线（现样式）；拖拽中：thumb 弹性放大 scale 1→1.35（`SpringOvershoot`），波形局部隆起跟随手指（隆起半径 48dp，高斯权重）。
- **拖拽**：进入拖拽显示「正在跳转到 mm:ss」气泡（拇指上方 12dp，玻璃 chip：`colors.glass` + `glassBorder` + `Xs`）；松手 seek 生效，thumb 从拖拽位回实际播放位走 `SpringSnap`；非拖拽时位置更新走 `animateFloatAsState(tween(Fast, Standard))`。
- **减少动态**：振幅恒 0（直线），thumb 无弹性放大，保留拖拽气泡。

### 5. 切歌（MUSIC-41）

封面 `Crossfade(tween(250))` + 新封面从切歌反侧位移 14dp 滑入（`slideInHorizontally`，`SpringControlIn`；「反侧」= 下一首从右、上一首从左）；曲名/艺术家 `fadeIn(150)` + `slideInVertically(8dp)`，艺术家延迟 `Stagger`(40ms)；进度 thumb 重置 0 后 `SpringSnap` 微过冲。

### 6. 歌词转场（MUSIC-26）

进（封面→歌词）：封面 `fadeOut(tween(200))` + 歌词页 `fadeIn(tween(280))` + 歌词内容 `slideInVertically(16dp, SpringControlIn)`；出反向 `fadeOut(300)` 档（`tween(Player)`）。总时长窗口 280–350ms。

### 7. 下拉收起（MUSIC-45/46）

全屏页垂直拖：拖动量实时作用于整页 `translationY` + 封面 scale 1→0.96 与圆角 Card→32 线性插值（拖幅 0→18% 屏高映射）；松手：拖幅 ≥18% 屏高 或 向下速度 ≥1200dp/s → 关闭（沿速度方向飞出 `tween(200)` + 迷你条重现）；否则 `SpringSnap` 回位。手势优先级写死：系统返回（BackHandler）> 全屏下拉收起 > 左右切歌 > 进度拖拽；歌词页内下拉 = 返回封面视图（现状保留）。

### 8. 队列拖拽（MUSIC-42）

长按拖起：条目 scale 1.02（`SpringControlIn`）+ 阴影 `graphicsLayer`（elevation 6dp 等效阴影刷制）+ zIndex 1；其余条目让位 = `animateItemPlacement`（`SpringControlInOffset`）；落位 scale 回 1 `SpringSnap`。左滑移除（T5 交付）：滑出 `slideOutHorizontally` + 删除，撤销 snackbar 3s（撤销 = 原位插回）。

## 设计定稿三：EQ/睡眠/信息面板参数（Kimi，2026-11-20）

- **EQ 10 段**（MUSIC-30）：中心频率 `31.25/62.5/125/250/500/1k/2k/4k/8k/16k` Hz，Q 值沿用现有 Biquad 默认（实现时核）；增益范围与现有一致（±12dB，0.5 步进）。**预设补 2 个**：Jazz `listOf(4f,3f,1f,2f,-2f,-2f,0f,1f,3f,4f)`、Treble Boost `listOf(0f,0f,0f,0f,-1f,-1f,0f,2f,5f,7f)`；既有 6 预设从 5 段插值到 10 段（线性插值相邻频点，写死数值进代码而非运行时插值——预设表全量替换）。**旧自定义偏好迁移**：`musicEqualizerCustomBands` 5 段值 → 10 段线性插值一次性迁移（读旧写新，迁移标记不入库——重迁移幂等）。
- **倍速**（MUSIC-32）：档位 `0.5/0.75/1.0/1.25/1.5/2.0`，默认 1.0 不落偏好（进程级，与视频倍速互斥——音乐会话起播强制 1.0）；入口 = 音效面板末尾「播放速度」行，不显眼。
- **睡眠定时**（MUSIC-33）：档位改 `15/30/45/60/90` + 自定义 1–240（**取代 W55 的 10/20/30/60 口径**，记录决策：以需求账本为准）；「播完当前曲目停止」= 暂停挂起到 `onMediaItemTransition(AUTO)` 触发后 pause；结束前 5s 音量淡出复用 `Player.volume` 包络通路（`fadeOutGain(remaining, 5000)`），恢复时音量归 1。
- **音频输出信息面板**（MUSIC-34）：入口 = 长按上下文菜单「音频信息」+ 音效面板底部「输出信息」行；底部 sheet 列出：源格式（codec/container）·采样率·位深·声道 → 输出格式（Exo `Format` 运行时读数）· 是否转码（T4 标注同源）· 码率；末行恒显「非 bit-perfect 输出（Android 音频混音）」（MUSIC-31 的提示义务落地；不做独占输出，记录范围裁剪）。

## 设计定稿四：歌词域规格（Kimi，2026-11-20）

- **`.elrc` 支持**（MUSIC-24/25）：`LrcParser` 扩展解析增强 LRC 行内词级时间戳（`<mm:ss.xx>词` 序列），映射进既有 `LyricWord(startMs, text)`；`.elrc` 扩展名加入外挂候选清单（与 .lrc 同规则：同目录同名、大小写不敏感）。服务端 Cues 与 .elrc 词级数据统一走 `LyricsWordHighlight`，高亮色 = `media.base`（调色板覆盖后随封面色），已唱词 100% alpha / 未唱 55% alpha（现状参数保留，视觉抽查）。
- **歌词转场**与当前行样式按设计定稿二 §6 与现状（当前行更亮更大字号 ✅）执行；本域唯一新视觉 = 词级高亮进度（词内按时间插值亮度——卡拉 OK 扫词），动画用 `animateFloatAsState(tween(200))`（MUSIC-43 口径）。

## 日志（只留最近 ~5 条）

- 2026-11-20（二）：W4 正式收官（D12/B5 复验双过，`8016fa37`）；新立 L8 收进 T10（四小项）；`tools/backup-docs.ps1` 同步入 worktree（参数化 DocsPath）。
- 2026-11-20：**W5 会话1 收官**。T0 现状核对（§7 全 50 条）完成；设计规格四节 Kimi 定稿；任务卡 T1–T11 定稿；W4 残留 L3/L7/coroutines-test 收 T10。实现入会话2（提示词见上）。
