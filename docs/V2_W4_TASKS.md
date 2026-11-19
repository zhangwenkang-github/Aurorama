# 2.0 W4 · 视频域（任务卡）

> 状态：`ACTIVE`（2026-11-19：**T1 ✅ `d2795184`、T8a ✅ `1ef74954`、T2 ✅ `c8a05dce`、T8b ✅ `ad8b2709`**（四卡两波并行交付，合态终验 ktfmt+四模块单测+assembleDebug 全绿；T2 K60 取证通过已停播放）；下一步派 T3a（引擎切换接口）与 T8c（SyncPlay 状态机）；遗留 L3 已立：播放页 themeId 跟随 aurora↔amoled 深色主题）
> 依据：`docs/V2_REQUIREMENTS.md` §9（V2-VIDEO）与 §15 波次（W4 ← W2）；架构 `docs/V2_ARCHITECTURE.md` §3；设计唯一权威 `docs/V2_DESIGN.md`。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W4 开工基线 `651cd427`（W3 全部合入）。
- **T0 ✅（2026-11-19，Kimi 亲自）**：§9 全 50 条现状核对完成，结论见下文「T0 现状核对」。
- **T6 ✅ → 用户拍板 go（2026-11-19，go-minimal 范围）**：SyncPlay 评估完成（SDK 1.8.12 的 `SyncPlayApi` 22 REST + `SocketApi.subscribeSyncPlayCommands` WS 扩展 + `TimeSyncApi.getUtcTime` 全部现成；时钟同步走 `GetUtcTime` 而非 `SyncPlay/Ping`——需求文档的"Ping"系误命名；8 窗最小 RTT、3 次 greedy→60s 与 jellyfin-web 逐字对应）。落点：状态机入 `player/core`（纯 JVM 可测）、SDK 适配入 `data`、装配挂 `PlayerHolder`；硬缺口：`PlaybackEngine` 无读侧（位置/时长/播放态），`PlayerHolder.currentEngine` 零调用方。**已立 T8a–T8e 实现卡**（gateway/时钟/状态机/播放口/入口面板）；组队列编辑、胶囊完整动效、深链、PiP 细分 DEFERRED。**⚠️ 验收环境红线**：jellyfin-test 与生产 jellyfins 同实例（12.4.0），创建 SyncPlay 组是服务器侧会话写——实组验收前需用户确认可用生产做临时组测试（组离开即销毁）或提供独立实例。
- **遗留 L1 ✅（2026-11-19，Kimi 亲自，commit `ccdafefa`）**：`seekSink.retrySeek()` 跨条目意图重建 bug 已修——失败请求是 MediaItem/AdjacentMediaItem 时沿用原目标条目与落点，不再从回退后的引擎条目重建；新增回归测试（RED→GREEN 验证），门禁 player/local+phone 单测 + ktfmt + 构建全绿。
- **遗留 L2 ✅（2026-11-19 复验通过）**：生产 `jellyfins` mpv TLS `-0x7280` 已随服务器侧修复消失。K60（新构建含 L1）mpv 内核直连生产 HTTPS 播放《告白实行委员会》（mkv/HEVC/FLAC 2.1GB）：无 mbedtls 错误，位置 6s→48s 实时推进，UI「正在播放」。排障插曲：Room 缓存的 accessToken 与 movie itemId 均已失效（401/源解析失败），App 线上会话不受影响；真机取证一律走 UI 驱动，勿用缓存 itemId 冷启 PlayerActivity。
- **W3 遗留并入 W4**：9 处非 token 硬编码圆角（`PlayerContentTabItem` 10、`PlayerSubtitleOverlay` 6、`ErrorDialog` 28、`LyricsSheet` 8、`BaseBadge` 8、歌词覆盖层 22 等）随 T1 播放器 UI 换肤一并清理。
- **本次质量门**：相关模块单测 + `ktfmtCheck` + `:app:phone:assembleDebug --max-workers=2` 绿；核心链路 K60（`8e875894`）真机验证，播放链路验证用 jellyfin-test（生产 jellyfins 永久只读，App 播放链路自身请求除外）。
- **环境备注**：`export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'`；K60 density 420 = 2.625 px/dp、Pad `43af8627` 360dpi = 2.25 px/dp；CI 仅 master push/PR 触发。**设备用后必停播放**：取证完确认 media_session state=NONE，`am force-stop io.github.zhangwenkang.aurorama.debug` 兜底（用户 2026-11-19 指令）。

## 新会话开工提示词（可直接粘贴）

```
继续 Aurorama 2.0.0 开发。你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W4_TASKS.md 状态块
→ docs/V2_REQUIREMENTS.md §9 + docs/V2_ARCHITECTURE.md §3 + docs/V2_DESIGN.md → 记忆库 MEMORY.md。
W4 状态：T0 现状核对、T6 SyncPlay 评估（已拍板 go-minimal）、L1/L2 两遗留均已完成；
任务卡 T1–T5、T7、T8a–T8e 已定稿待派。
本波任务：先由 Kimi 定稿 T1（播放页 K1 换肤与动效）设计规格（玻璃控制层参数/弹簧曲线/菜单动效），
定稿后派 deepseek 执行 T1；T8a（SyncPlay gateway，data 模块）与 T1 不同模块可并行派出。
设计决策由 Kimi 亲自定稿，deepseek 零设计权。
纪律：每任务一个子代理一个 commit；门禁=相关模块单测+ktfmtCheck+构建（--max-workers=2）；
核心链路 K60 真机验证；设备用后必须停播放（media_session state=NONE + force-stop 兜底）。
红线：生产 jellyfins 只读；SyncPlay 实组验收是服务器会话写，需用户确认后才可在生产做。
```

## T0 现状核对（2026-11-19，对照 §9 全 50 条）

图例：✅ 已具备 / ⚠️ 部分具备（差距见注）/ ❌ 缺失 / 🔍 已实现待真机回归确认

### §9.1 双内核与统一管线（VIDEO-01~09）

- 01 ✅ 双内核在（Exo 默认、mpv 可选，`pref_player_backend`）。
- 02 ⚠️ **接口窄**：`PlaybackEngine` 仅 prepare/play/pause/seekTo×2/setRate/release + 能力位；轨道枚举与选择、字幕延迟、音频延迟、字幕样式动词未进接口（`player/core/playback/PlaybackEngine.kt`）。
- 03 ⚠️ 接口已建、上层未迁移：`PlayerViewModel`（4450 行）仍面向 media3 `Player` 编程，适配器保留 `media3Player` 出口。
- 04 ⚠️ 有错误回退自动切 mpv（`PlayerActivity.switchBackendForFallback`）；无图形字幕探测切换、无 prepare 限时失败探测、无非打断提示。
- 05 ✅ 手动切换入口在（`PlayerViewModel.switchBackend` / 设置面板）。
- 06 ❌ 现状切换 = 重启播放页（`switchBackendAndRestart`），无状态保留、无 Exo↔mpv 轨道映射。
- 07 ❌ 无淡入遮罩。
- 08 ❌ 无能力探测：`HdrCapabilities`/MediaCodec 探测零引用。
- 09 ❌ `MPVPlayer` 无 tone-mapping 配置（仅 hwdec）。

### §9.2 功能矩阵（VIDEO-10~18）

- 10 ✅ 基础控制大体齐（可拖进度条含缓冲/章节标记、倍速 0.25–4.0、长按临时倍速、循环三模式、方向锁定、比例切换、PiP）。
- 11 ⚠️ 字幕面板有源列表与内外挂信息；按语言分组 + 「内封/外挂/图形」标注 + 图形字幕自动提示切 mpv 缺。
- 12 ❌ 偏移步长 100ms（需求 50ms，`PlayerSubtitleController.DELAY_STEP_MS=100`，上限 ±10s 在）；seek/引擎切换/倍速后保持生效列入回归。
- 13 🔍 自研渲染按位置取 cue，理论无残留；列 T2 真机回归项。
- 14 ⚠️ `SubtitleStyle` + `SubtitleAppearancePreferences` 在；mpv 路径实时预览待核。
- 15 ❓ 在线字幕搜索未见实现线索，T2 开工先查（无则 SHOULD 可缓）。
- 16 ⚠️ 音轨切换（`TrackSelectionEngine`）与音频延迟（`AudioDelayProcessor`、mpv `audio-delay`）在；对白增强/夜间音量均衡快捷开关缺。
- 17 ❌ HDR：Exo 直通靠默认行为未验证；mpv tone-mapping 与亮度/对比度补偿无。
- 18 ✅ trickplay 按需加载 + 手势 HUD（W20）；拖拽进度条的缩略图+时间戳在，章节名叠加待核。

### §9.3 手势（VIDEO-19~28）

- 19 ⚠️ `PlayerGestureHelper` 仍是 View 时代（直写 window/audioManager/View 动画）；seek 已走 `submitSeek` 意图，反馈层与手势仍耦合 View 动画。
- 20 ⚠️ 双击快退/快进 + 方向性波纹在；步长取固定 pref，连续递增 10/20/30 未见。
- 21 ✅ 横滑实时 seek + HUD（目标时间/总时长）+ 渐进加速 + haptic。
- 22 ✅ 竖滑左亮度/右音量 + 竖向 HUD。
- 23 ✅ 长按临时倍速（档位用时现读）；「越久越快」未见。
- 24 ⚠️ 双指 = 铺满/还原档位切换，非连续缩放+平移。
- 25 ❓ 非全屏顶部下滑退出未见；返回仲裁有 `resolvePlayerBack`。
- 26 ⚠️ 现状 `|dy| < 2|dx|` 比率判定 + 50px 横滑阈值，非 8–10vp 显著位移锁。
- 27 ✅ 控制层命中带让位（`PlayerOverlayContainer` 实测高回传）。
- 28 ⚠️ 自动隐藏 3.5s（需求 4s），抑制条件齐（面板/错误/seek 中不隐藏）。

### §9.4 UI 与动效（VIDEO-29~35）

- 29–31 ❌ 播放页未做 K1 玻璃换肤与弹簧动效：`PlayerControlOverlay` 5170 行待换肤；W3 遗留 9 处硬编码圆角并入。
- 32 依赖 SyncPlay go/no-go（T6）。
- 33 SHOULD，可裁剪，默认不做。
- 34 同 07，并入 T3。
- 35 ⚠️ 播放页主题跟随待换肤后验证（平板/折叠边到边布局已有 `PlayerFormFactor`）。

### §9.5 SyncPlay（VIDEO-36~46，SHOULD）

评估输入（T6 用）：App 零代码；jellyfin-sdk 1.8.12 自带完整 `SyncPlayApi`（REST New/Join/Leave/Play/Pause/Seek/Queue/Buffering/Ping 等）+ `SyncPlayCommandMessage`/`SyncPlayGroupUpdateMessage` WS 模型类；生产服 jellyfins = Jellyfin **12.4.0**（原生 SyncPlay 齐备）；WS 通道需走 SDK `api.webSocket`，App data 层暂无现成 WS 客户端封装。`PlaybackEngine.setRate` 已在接口面，漂移校正的变速追帧可直接落；缺：时钟采样、deadline 调度、组队列 UI、前后台/PiP 语义。

### §9.6 本地媒体与验收（VIDEO-47~50）

- 47 ✅ SAF 本地库在（`LocalLibraryRepositoryImpl`：`OpenDocumentTree` + `takePersistableUriPermission` + content://，视频走 synthetic `FindroidMovie`/`FindroidSource` 进同一播放器）。
- 48 🔍 本地播放复用同一 PlayerActivity/手势/字幕路径；标题/时长从扫描元数据来，同级目录外挂字幕推断待核。
- 49/50 域验收口径，并入 T7。

## W4 任务卡（定稿）

每任务一个子代理一个 commit；门禁 = 相关模块单测 + `ktfmtCheck` + `assembleDebug --max-workers=2`；涉及播放行为的核心链路必须 K60 真机取证（jellyfin-test）。设计决策一律以 `V2_DESIGN.md` 与本卡「设计定稿」行为准，deepseek 零设计权。

| 卡 | 内容 | 需求依据 | 验收要点 |
|---|---|---|---|
| T1 | 播放页 K1 换肤与动效：玻璃控制层（玻璃 token + 描边）、弹簧化控制层出入、播放暂停 scale+fade、进度条拖拽吸附回弹、菜单 stagger；自动隐藏 3.5s→4s 校准；清 9 处硬编码圆角；主题跟随四变体验证 | VIDEO-28/29/30/31/35 + W3 遗留 | 四主题切换无残留色；控制层出入弹簧可中断；K60 圆角像素量测命中 token |
| T2 | 字幕域：偏移步长 100→50ms；字幕列表按语言分组并标注内封/外挂/图形；ASS/SSA/PGS 在 Exo 路径提示切 mpv（非阻断）；seek 后字幕重算不残留回归；mpv 样式实时预览核对补差；（15 在线搜索先查后定） | VIDEO-11/12/13/14/(15) | K60 实测偏移 50ms 步进生效且 seek 后保持；图形字幕提示非阻断 |
| T3 | 引擎切换体验（拆 T3a/T3b）。T3a：`PlaybackEngine` 接口补轨道枚举/选择、字幕延迟、音频延迟、字幕样式动词 + 上层调用点迁移 + 原地切换（保留 position/播放态/倍速/轨道/延迟/样式 + 轨道映射表）+ 淡入遮罩 + 非阻断提示。T3b：能力探测（MediaCodec/HDR10/HDR10+/DV/mpv hwdec）+ mpv tone-mapping 选项（bt.2390 默认 / mobius / hable） | VIDEO-02/03/04/05/06/07/08/09/34 | jellyfin-test 上 Exo→mpv 原地切换位置误差 <1s、轨道保持；K60 取证无「重开播放器」感；T3b 探测结果落面板 |
| T4 | trickplay 与 HDR 核查补缺：拖拽缩略图+时间戳+章节名核对；Exo HDR10/10+ 直通真机验证；mpv 补偿依赖 T3b | VIDEO-17/18 | K60 HDR 片源双内核取证（无 HDR 片源则用 jellyfin-test 转码流说明边界） |
| T5 | 手势复核补缺：双击连击递增（10/20/30s）；方向锁改 8–10vp 显著位移仲裁；双指连续缩放+平移评估（保持档位切换还是升级，Kimi 定）；「越久越快」长按档评估；非全屏下滑退出核查 | VIDEO-20/23/24/25/26 + 19/21/22/27 回归 | 七类手势无冲突真机走查单；方向锁斜滑不跳变 |
| T6 ✅ | SyncPlay go/no-go 评估（deepseek 纯调研，2026-11-19 完成；**用户同日拍板 go**，按 go-minimal 范围立 T8 系列实现卡） | VIDEO-36~46 | 报告结论并入状态块 |
| T8a | SyncPlay gateway 骨架：`JellyfinApi` 暴露 `syncPlayApi`/`timeSyncApi`/`webSocket`（各一行扩展属性）；`data/syncplay/` 网关把 SDK 类型/异常翻成域事件（GroupUpdate/Command 流 + 22 个 REST 动词），含 204 空体与 GroupDoesNotExist/LibraryAccessDenied 映射；入组前探针（`syncPlayGetGroups`+`getUtcTime`），不支持则降级隐藏入口 | VIDEO-36/43/50 | data 模块单测；探针降级路径有契约测试 |
| T8b | SyncClock：`GetUtcTime` 四时间戳采样（T1–T4、offset/delay 公式与 jellyfin-web TimeSync.js 一致）、8 窗最小 RTT、前 3 次 1s greedy 后 60s 低功耗、SDK DateTimeSerializer 的 systemDefault 时区换算用契约测试钉死 | VIDEO-38 | player/core 纯 JVM 单测（注入时钟），时区换算用例覆盖 |
| T8c | SyncPlayController 状态机（`player/core`，纯 Kotlin）：NotInGroup/Joining/Idle/Armed/Playing/Paused/ClockWarming；When→本地时钟 deadline 调度；晚到命令按 `positionTicks + elapsed×rate` 直跳不补播；漂移校正分档（<60ms 忽略 / 60–400ms `setRate` 追帧 / >400ms 跳），mpv 内核阈值放宽到 150/600ms（R2） | VIDEO-37/39/40/41/43 | 纯 JVM 单测覆盖各迁移与三档漂移 |
| T8d | 播放口适配与装配（`player/local`）：`SyncPlayPlaybackPort` 实现（读位置/时长/播放/缓冲态——`PlaybackEngine` 无读侧，port 直接读 media3Player）、seek 一律经 `seekSink.submit(..., "syncplay-command")` 复用确认逻辑、入组期间 `syncPlayOwnsTransport` 闸门（本地手势只发请求不写引擎）、挂 `PlayerHolder` 进程级 scope（WS 订阅计数随收集生命周期，断线先 `GetGroup` 拉快照再恢复） | VIDEO-37/42(最小)/44 | player/local 单测 + 契约测试；K60 双端（App + jellyfin-web 对照）走查 |
| T8e | 入口与状态（`app/phone`）：播放页菜单加 SyncPlay 面板——未入组：创建/加入（组列表）；入组：组名、成员数、离开；VIDEO-32 最小态胶囊（已同步绿点/重连中提示；脉动与"已对齐"淡出 DEFERRED）；创建时自动暂停本地播放 | VIDEO-46/32(最小) | K60 真机：创建组→web 端加入→双端播/停/seek 同步取证 |
| T7 | 本地媒体核查 + 域验收：SAF 外挂字幕同级推断核查；VIDEO-49/50 全量验收清单（双内核状态保留、图形字幕、字幕不漂移、SAF 失效/组不存在/版本过低非阻断提示） | VIDEO-47/48/49/50 | 验收清单逐项 K60 取证 |

## T1 设计定稿（Kimi，2026-11-19，deepseek 零裁量）

依据 VIDEO-28/29/30/31/35 + `V2_DESIGN.md` §3（K1 token）。物理约束先说明：视频走 SurfaceView，**真实 backdrop blur 不可行**（RenderEffect 糊不到独立 surface）——磨砂感 = 半透明底 + 1dp 描边 + 顶部内高光，VIDEO-29 的「轻微模糊」与 VIDEO-30 的「blur 渐强」以 scrim 透明度渐强近似，此处正式记录偏离。

### 1. 玻璃控制层参数（VIDEO-29）

- **播放页恒为影院深色基调**：浅色/动态色主题下控制层遮罩、玻璃底仍用深色 scrim 系（行业惯例，画面可读性优先）；主题跟随（VIDEO-35）体现在强调色/媒体色、圆角、字体、描边 token 全部走 `LocalCinefinColors`/`LocalMediaColors`，不得出现 `Color.White` 等字面量。
- 控件玻璃 chip（`PlayerIconButton`/`PlayerPlayKey`/传输键）：底 `scrim @ 28%`、按下 `@ 44%`（W12 用户验收值，保留），描边统一改为 `colors.glassBorder`（原私有 0.16 常量废除）；私有常量 `PLAYER_GLASS_ALPHA/PRESSED/BORDER` 迁入 `CinefinTokens` 命名为 `PlayerGlassAlpha/PlayerGlassPressedAlpha`，描边不再单设常量。
- 玻璃容器（侧面板、trickplay 缩略图卡、章节气泡、手势 HUD 底）：底 `colors.glass` + 1dp `colors.glassBorder` + `CinefinShapes.Glass`（面板）/`Xs`（小件）。
- 顶/底 scrim 渐隐遮罩保留（`playerTopScrim/playerBottomScrim`），不出现写死色值，继续走 `colors.scrim`。

### 2. 弹簧与动效 token（新增进 `CinefinMotion`，VIDEO-29/30/31）

```kotlin
/** 控制层出入位移、面板缩放：可中断、无可见回弹。 */
val SpringControlIn = spring<Float>(dampingRatio = 0.86f, stiffness = 400f)
/** 播放/暂停图标切换过冲（1→约1.18→1）。 */
val SpringOvershoot = spring<Float>(dampingRatio = 0.62f, stiffness = 520f)
/** 进度条释放回弹与章节吸附落位。 */
val SpringSnap = spring<Float>(dampingRatio = 0.75f, stiffness = 600f)
```

- **控制层出入**（VIDEO-28/30）：enter = `fadeIn(tween(Fast, Standard))` + `slideInVertically(spring(0.86, 400))`，位移 12dp 保留；exit = `fadeOut(tween(150, Accelerate))` + `slideOutVertically(spring(dampingRatio=1f, stiffness=400f))`；scrim 遮罩 alpha 与控制层同步 `tween(Fast, Standard)`（blur 渐强的替代）。**必须可中断**：全部走 `AnimatedVisibility`/`animate*AsState`，禁止手写不可中断动画。
- **播放/暂停图标**：切换 = `Crossfade(tween(150))` + 图标 scale 过冲（`SpringOvershoot`，仅图标，容器不缩放）。
- **暂停降亮加暗角**（VIDEO-30 后半）：暂停时画面叠 `scrim @ 22%`（`tween(Player=280, Standard)` 淡入淡出）+ 四边径向暗角（边缘黑 35%，同一透明度动画）；恢复播放即撤；与控制层可见性解耦（控制层隐藏后降亮仍在）。
- **进度条**（VIDEO-31）：非拖拽时 played/buffered 比例变化走 `animateFloatAsState(tween(Fast, Standard))`；拖拽中动画关闭；松手后从 scrub 值回实际位置走 `SpringSnap`（knob 可微过冲）；章节吸附落位瞬间 knob scale 0.9→1 `SpringSnap`；trickplay 缩略图 enter = `fadeIn(150)` + `slideInVertically(8dp, SpringControlIn)`。章节吸附逻辑（`resolveChapterSnap`）与滞回参数**不动**，只加表现层动画。
- **菜单/面板**（VIDEO-31）：「圆形揭示」采用 M3 Expressive 等价做法——面板 `scaleIn(0.92→1, SpringControlIn, transformOrigin=锚点角)` + `fadeIn(Fast)`（真 shape 圆角动画成本高且收益低，记录偏离）；菜单项 stagger = 每项 `fadeIn(150)` + `slideInVertically(8dp)`、`delay = index × CinefinMotion.Stagger(40ms)`，stagger 上限 8 项。
- **自动隐藏**（VIDEO-28）：`3500L` → 常量 `PLAYER_CONTROLS_AUTO_HIDE_MS = 4000L`；菜单随控制层关闭、关闭后重新计时（现状已有，回归确认不破坏）。

### 3. 硬编码圆角清理（W3 遗留 9 处，映射规则写死）

业务代码全量 grep `RoundedCornerShape(<数字>.dp)`（theme 定义文件除外），按档位映射，不得自选对齐：1–4→`TwoXs`；5–12→`Xs`；13–18→`Button`；19–24→`Card`；25–32→`Xl`（悬浮玻璃容器才用 `Glass`）。已知点：`PlayerContentPanel.kt:138`(10→Xs…按规则 10∈5–12→`Xs`)、`PlayerControlOverlay.kt:4254`(4→`TwoXs`)、`PlayerSubtitleOverlay.kt:528`(6→`Xs`)、`ErrorDialog.kt:40`(28→`Xl`)、`LyricsSheet.kt:202`(8→`Xs`)、`BaseBadge.kt:18`(8→`Xs`)、`MusicLyricsOverlayContent.kt:80`(22→`Card`)，以及 grep 新发现的其余处。

### 4. 文件边界与验收

- 允许动：`core` theme 三文件（Motion/Tokens/必要时 Colors）、`app/phone/presentation/player/**`、`app/phone/presentation/components/ErrorDialog.kt`、`app/phone/presentation/film/components/BaseBadge.kt`、`modes/music` 歌词两文件。**禁止**动 `player/core`、`player/local`、`data` 与任何手势/seek 行为逻辑。
- 门禁：`core`+`app:phone`+`modes:music` 单测（断言旧值的布局测试同步更新）+ `ktfmtCheck` + `assembleDebug --max-workers=2`。
- K60 取证：起播→控制层弹簧出入（连点验证可中断）→暂停降亮暗角→拖拽吸附回弹→trickplay 上浮→面板 stagger→自动隐藏 4s（logcat 计时）→四主题切换无残留色；圆角量测允许 ≤2 张压缩截图（看完即删）。**取证完必须 media_session state=NONE + force-stop 兜底**。

## T2/T8b 交付遗留（2026-11-19 收口记录）

- **T2 裁决（Kimi）**：「· 图形」标注仅用于真图形轨（PGS/VobSub 族），ASS/SSA 不标（文本格式，标「图形」误导）；但 mpv 提示条对 ASS/SSA/PGS 全触发。代理偏离任务卡括注，理由成立，批准。
- **T2 事实**：`DELAY_STEP_MS=50`（±10s 不变）；延迟显示改两位小数（`+0.05s`），chip 文案由常量推导；行副标题瘦身为「格式 · 落点」，语言进组头、来源进行尾；`PlayerSubtitleOverlay.visibleText` 升 internal 供回归测试。mpv 字幕样式**已实时**（`applySubtitleStyle` 写运行时可写属性），无补差；`sub-ass-override` 未显式设置（作者样式 ASS 可能部分不接受覆盖，边界已知）。**VIDEO-15 在线字幕搜索全仓零实现、零依赖，SHOULD 不实现，关闭。**
- **T8b 事实（T8c/T8d 消费口径）**：`SyncPlayClock`（`player/core/syncplay/`）：`state.isSynchronized` = ≥1 样本；`run()` 吞单次采样异常，T8c 直接 `launch { clock.run() }`；退组 `reset()`；命令 deadline 用 `clock.nowServer()` 对比 `whenAt`。与上游 TimeSync.js 唯一偏差：任务卡钉 3 次 greedy（上游实际 4×1s），Kimi 裁决维持任务卡口径。`SyncPlayTimeSource`↔`gateway.getServerTime()` 适配器留 T8d。
- **L4（小遗留）**：`player/core/build.gradle.kts` 硬编码 `kotlinx-coroutines-core:1.11.0`（catalog 无条目），下波顺手收进 version catalog。
- **graphify**：T2/T8b 代理未跑（边界限制），负责人已统一跑。

## T1/T8a 交付遗留（2026-11-19 收口记录）

- **L3（新立，小卡）**：`PlayerActivity` 目前 `CinefinTheme(themeId = DEFAULT)` 写死，播放页不随 App 主题换色板。Kimi 设计拍板：播放页跟随 **aurora↔amoled** 深色主题（light/dynamic 映射 aurora dark，保持影院深色基调——与「T1 设计定稿」§1 一致，VIDEO-35 的 OLED 维度由此闭合）。改动面：PlayerActivity 传 themeId 映射 + 播放页组件对照 aurora/amoled 玻璃参数抽查，随 T5 或独立小卡派。
- **trickplay 上浮淡入**：代码已实现但本次片源无 trickplay 未真机触发，复验并入 T4。
- **动效主观手感**（松手回弹/knob 微弹/stagger 节奏）：adb 采样粒度无法取证，建议用户人工触摸走查一次，不满意再回调弹簧参数（参数集中在 `CinefinMotion`，一处可调）。
- **范围外圆角**（不属 W4 播放域，留各域波次）：`modes/book/ReaderSettingsPanel.kt`(14)、`core/.../CinefinNavigation.kt:399`(9)、`modes/music/MusicModeScreen.kt:1251`(12)；`app/tv` 冻结不动。
- **T8a 接口事实**（T8b/c/d 消费注意）：403 一律映射 `LibraryAccessDenied`；时区换算 `toInstantInSystemZone()` 严格可逆，T8b 须契约测试复验；`commands`/`groupUpdates` Flow 每次收集重新订阅 WS（T8d 订阅计数语义依赖此）；`SyncPlayGatewayImpl` 未做 Hilt 绑定，装配留 T8d。

## 日志（只留最近 ~5 条）

- 2026-11-19（五）：**T2 ✅ `c8a05dce`**（50ms 步进/语言分组标注/图形字幕非阻断提示，K60 取证：步进、seek 与引擎切换后偏移保持、分组与提示条上屏，已停播放）与 **T8b ✅ `ad8b2709`**（SyncClock，公式与 jellyfin-web 逐字对应，9+3 例测试全绿）并行交付；合态终验全绿。VIDEO-15 查无实现关闭；L4 立（coroutines 进 catalog）。
- 2026-11-19（四）：**T1 ✅ `d2795184`**（deepseek，K1 玻璃换肤 + 弹簧动效 + 圆角清理 9 处 + 自动隐藏 4s；K60 取证：隐藏 4.29–4.32s、暂停降亮 ≈0.80 含边缘压暗、连点可中断无 ANR，已停播放）与 **T8a ✅ `1ef74954`**（deepseek，SyncPlay gateway 22 动词 + 探针三路径契约测试 20 例）并行交付，门禁全绿；T1 设计规格 Kimi 定稿入「T1 设计定稿」节；新立遗留 L3（播放页 themeId 跟随 aurora↔amoled），trickplay 复验并入 T4。
- 2026-11-19（三）：用户拍板 SyncPlay **go**（go-minimal 范围），立 T8a–T8e 实现卡；新增纪律「设备用后必停播放」（media_session state=NONE + force-stop 兜底，已入记忆库）；补回新会话开工提示词。
- 2026-11-19（二）：L1 retrySeek 修复提交 `ccdafefa`；L2 TLS 复验通过（K60 mpv 生产播放健康）；T6 SyncPlay 调研完成；顺手修正 `DEV_ENVIRONMENT.md` 服务器版本 10.11.8→12.4.0 并记录 jellyfin-test=生产同实例。
- 2026-11-19：W4 开工。T0 调查完成；L1/L2 认领；任务卡定稿 T1–T7。
- 2026-11-19：W3 收口后建卡（`69b9f87f`），含开工提示词与任务草案。
