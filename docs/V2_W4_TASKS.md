# 2.0 W4 · 视频域（任务卡）

> 状态：`CLOSED`（2026-11-20 **W4 正式收官**：全 14 卡交付 + T7/T10 + **D12 ✅（admin 双账号对照：web seek→App 落点误差 140ms、双向播停均通、组销毁确认）+ B5 ✅（mpv `pgssub` 解码器加载、同帧开/关差分证 PGS 上屏）**；新发现 L8（媒体键暂停绕开组闸门）列入 W5 小卡；下一波 W5 音乐域会话2 实现）
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

## T8e 交付遗留（2026-11-19 晚收口记录）

- **「控制层冻结」调查结论**：非 UI 缺陷——`dumpsys` 证明 overlay VISIBLE、点击后帧计数与像素均变化；真相 = MIUI `uiautomator dump` 树陈旧（返回已不存在的旧坐标）+ 建组自动暂停后的静止帧。**取证教训（后续代理必读）：uiautomator dump 陈旧时，改用 dumpsys activity/gfxinfo + 截图像素差验证 UI 活性，勿信「点了没反应」。**
- **seek 风暴根因与修复（`7d601455`）**：真因 = 校正 seek 无 in-flight 抑制 + 锚点重设到落点把 1–2s seek 延迟吸收成永久偏差（实测 2.4s 一轮再校正）。修复：`seekInFlight` 抑制 + `seekConfirmation`(requestId) 落定 + `MIN_CORRECTION_SEEK_INTERVAL` 护栏；**Kimi 裁决：批准「锚点始终对齐组时间线、不在落定时重设」对原规格（落点重锚）的修正**——落点重锚会把 seek 延迟烙成永久偏差，代理的根因论证成立。
- **T8e 裁决（Kimi）**：`Armed` 按「已同步」显示、`Joining` 按「时钟同步中…」（避免胶囊闪没）——批准；图标用 `ic_repeat` 占位（全仓无 sync 类图标），待图标批次补 K1 重绘；成员列表取数用面板打开时的探针快照（协调器只在 GroupJoined 刷新）——接受；浏览器页面快照 3 张属 web 对照取证，不占设备截图预算，下不为例需在任务卡写明。
- **代理操作事故记录**：agent-15 误删 Temp 目录 4 个非本次会话的 k60_* 临时文件（越界操作）；已在后续任务卡加「只删自己创建的文件」条款。
- **VIDEO-32 完整态接入点**（脉动/已对齐淡出，DEFERRED 项将来做）：`SyncPlayEntry.kt` 三态枚举 + `textRes()` 是唯一接缝，方案已记录在 T8e 交接 §6。
- **实组验收欠账**（seek 风暴修复后未复验）：组内切内核位置误差、断网 12s/30s 恢复、稳态同步（修复后定位频率应 ≈0/分钟）——下一轮 K60 复验补齐；Pad 与 K60 不同账号（SyncPlay 组按账号隔离），双端对照只能用 web。

## T7 验收收口（2026-11-20，K60 全清单）

8 ✅ / 4 ⚠️ / 1 ❌。✅：SAF 同路径播放、双内核切换状态全量保留（倍速/字幕延迟/循环/比例/轨道五项实测）、字幕不漂移（seek×3+双档倍速+切换）、引擎切换提示、版本过低降级（契约测试）、trickplay 复验（14/17 部电影已产出，`trickplayIntervalMs=10000`，缩略图+时间戳气泡+章节名同框 `第 12 章 · Chapter 12（1:01:58）` 吸附误差 0）、控制层让位、图形字幕 Exo 提示。⚠️→已由 T10 修复：SAF 失效文案、解散组提示分支、L5 口径（裁决：自动回退仅 codec 能力错误，坏容器走错误卡——单测已钉）、本地进度回写 404。⚠️→残留（见下）。❌：D12 web 主动 seek→App 跟随（同账号双端不可靠）。

### W4 收官验收项（2026-11-20 全部 ✅，D12/B5 已关闭）

- **D12 ✅**：admin 账号 web 入组（面板成员同时见两账号）→ web 键盘 seek（OSD 10:38）→ App `type=SEEK` + `syncplay-command` 落点 **638.221s vs 目标 638.081s，误差 140ms**；web→App 暂停/播放、App→web 反向（web 画面冻结佐证）均通；双方离组后两侧组列表均空（销毁确认；注意组按成员存在而非按创建者——创建者先离组组仍在）。
- **B5 ✅**：现成片源（《告白实行委员会》PGSSUB/eng 内封）；mpv 日志 `Using subtitle decoder pgssub`；同帧开/关差分证 PGS 上屏（差分只落字幕带）；Exo 提示条复验一致。
- **L8（新立，W5 小卡）**：**媒体键（KEYCODE_MEDIA_PAUSE/耳机/通知栏）暂停不走 SyncPlay 组闸门**——UI 键正常广播组命令，媒体键零广播、组状态失联。修法已记：MediaSession onPlay/onPause 回调接 `SyncPlayTransportGate` + 单测。
- **取证坑入库**：①App 起播会恢复上次字幕选择，若上次是「关」则日志 `sid=no`，扫多久都看不到 PGS——必须把主字幕设为 PGS；②PGS 字幕稀疏，静止帧盲抓命中率低，**同帧开/关差分**是可靠判定法；③web SyncPlay 弹层有旧组本地缓存，入组前先整页刷新。

### W4 残留项（不影响 W4 收官判定）

| 项 | 归属 |
|---|---|
| L3 播放页 themeId 跟随 aurora↔amoled（方向已拍板） | W5+ 小卡 |
| L7 mpv 内核 content:// 外挂字幕（T10 只覆盖 Exo；mpv `sub-add` 大概率打不开 content://） | W5 小卡 |
| T1 trickplay 入场动画帧级取证（adb 粒度不够） | 用户人工走查（同动效模式） |
| app/phone `kotlinx-coroutines-test:1.11.0` 硬编码 | 下波顺手收 catalog |
| `core` 的 `Constants.ZOOM_SCALE_BASE/THRESHOLD` 无调用方 | W 后清理 |

### T10 交付事实（`47095ed7`，22 文件）

SAF 同级字幕推断（`LocalSubtitleRules`：同主名或主名+语言标签后缀，`.srt/.ass/.ssa/.vtt`，同目录，大小写不敏感扩展名）+ DB **v18** 自动迁移（`local_media_items.subtitles` 列，K60 实测迁移成功——**裁决：批准 schema 变更**，扫描期推断需要持久化）；字幕 content:// 读取通路（`PlayerSubtitleController`）；SAF 失效错误卡新文案+「重新扫描媒体库」主按钮（`rescanAll`）；Joining 态收到 GroupDoesNotExist →「加入失败：组不存在或已解散」；`PlaybackErrorClassification` 纯函数化 + 回退链口径单测；`PlaybackPositionWriter` 跳过本地条目。K60 取证：demo 双外挂字幕上屏渲染、失效错误卡与重扫动作可用。

## T9 交付遗留（2026-11-20 收口记录）

- **T9 裁决（Kimi）**：①mpv `profile=fast` 在 init 时覆写 `hdr-compute-peak=no`，代理保留构造期 `setOptionString` 并在 init 后补 `setPropertyString`（日志已证生效）——批准，这是最小正确做法，不改 profile；②气泡时间戳用既有 `formatTime`（≥1h 展开 h:mm:ss）——批准，长片 65:30 式纯 mm:ss 反而难读。
- **T9 事实**：气泡文案 `第 N 章 · 名称（mm:ss）` 三要素同框（`PlayerChapterSnap.kt` label 加 `startMs`）；「解码与能力」入口在播放设置面板 Playback 页签末尾；mpv 构造日志含 `hdr-compute-peak=yes target-peak=auto`；coroutines catalog 已建（`kotlinx-coroutines = "1.11.0"`），player/core 已收编。
- **新小遗留**：`app/phone/build.gradle.kts:219` 的 `kotlinx-coroutines-test:1.11.0` 硬编码未收（T9 边界外），下波顺手收 catalog。
- **备查**：T9 取证期出现一次 mpv `tls: TLS handshake was aborted by peer`（服务器转码 URL，重开正常），与 T9 无关——若再出现按 L2 旧案（TLS 服务器侧）排查。

## T3b/T4 交付遗留（2026-11-19 晚收口记录）

- **T3b 事实**：`DevicePlaybackCapabilities`（模型 player/core + 探测 player/local，进程级缓存）；K60 = 骁龙 8+（23013RK75C，**非天玑**），HDR 四项（DV/HDR10/HLG/HDR10+）全支持；tone-mapping 偏好 `pref_player_tone_mapping` 默认 bt.2390，mpv 运行时写属性即时生效（T3b K60 已证 PLAYING 不中断）。比对入口 `supportsAll/missingHdrTypes` 已备未接线。
- **T4 核查结论（零 commit）**：Exo HDR10 直通已证（`colorInfo`→MediaCodec BT.2020/PQ→dataspace 链，面板当前流读数一致；**App 侧零 HDR 显式代码，纯「服务器直放+平台默认」**）；mpv 三档在 HDR 片源上观感差异量化已证（亮度 56.4/47.8/53.6，同帧相关系数 >0.92）。全库 HDR 片源 = 25 集 HDR10（青之箱），无 HDR10+。
- **trickplay（2026-11-19 用户已开启提取，扫描进行中）**：用户已在服务器开启提取，全库扫描需时间（官方 App 端目前也看不到预览图，佐证扫描未完成）。**T4 trickplay 三项复验挂起**：待扫描产出后（`Trickplay` 字段非空）重跑——缩略图+时间戳气泡、章节名同框（T9①交付后一并验）、T1 入场动画；并入 T7 域验收检查单。App 侧代码（W20 + T1 动画）已就位，无需改动。
- **T9 收尾小项包（新立，Kimi 定稿）**：①**章节气泡补目标时间戳**（裁决：VIDEO-18「缩略图、时间戳与章节名」按三要素同框——气泡文案 `第 N 章 · 名称（mm:ss）`，mm:ss=吸附落点）；②**解码面板手机竖屏入口**（裁决：手机竖屏宽度预算省略码率/解码键是既有行为，不改预算——在「播放设置」面板加「解码与能力」入口行跳解码面板）；③**mpv HDR 补偿参数**（VIDEO-17 后半缺口：`hdr-compute-peak=yes` + `target-peak=auto` 构造期默认值，偏好不加新档，先观察观感）；④ L4 coroutines catalog 顺手做。一卡一 commit。
- **T4 其它缺口（记录不立项）**：设备档案无 HDR/位深门控（`PlaybackDeviceProfile` 只有容器+编解码条件）；mpv `target-contrast` 未设（随 T9③观察后再议）；全库无 HDR10+ 片源（HDR10+ 只能引 T3b 能力结论）。
- **设备档案注意**：T4 取证用 HEAD 构建（22:20）重装了设备；`pref_player_backend`/`pref_player_tone_mapping` 已还原默认值。

## T5 交付遗留与 SyncPlay 复验结论（2026-11-19 晚收口记录）

### SyncPlay 修复后复验（K60 + web，全过）

- **稳态**：3 分 10 秒窗口 `syncplay-command` 定位 **0 次**（修复前 ~310 次/3 分钟）、mpv 解码器重初始化 0 次；UNPAUSE 后初始对齐 3 秒内 3 次属正常，此后 4 分钟零漂移 seek。跟随延迟实测 368ms / 709ms（服务器 whenAt → App 收到）。
- **组内切内核**：mpv→Exo 误差 **0ms**（耗时 8.3s）、Exo→mpv 误差 **5ms**（2.9s）；组态保持 Playing、无重新入组；漂移档随内核重建。
- **断网 12s**：重连快照对齐日志在（`resyncAfterReconnect`），WS 全程零重订阅；观察项：网络恢复到对齐约 24s（SDK 判连断偏慢，记录不立项）。
- **追帧**：UI 实测「倍速 0.95×」上屏（领先减速追帧），证实漂移走变速带而非 seek——修复设计意图兑现。
- 小欠账：web 端主动 seek→App 跟随未取到直接数据（web OSD 交互限制），组命令带位置落点生效作间接证据；并入 T7 域验收收尾。

### T5 交付事实与裁决

- **Kimi 裁决**：①捏合缩放/双指双击还原真机未验（`input` 不支持多点、`sendevent` 被 SELinux 拒，非 root 无解）——纯函数单测 + 代码审查作证，**列入用户人工走查**（同 T1 动效模式）；②缩放用 PlayerView 外层 `player_zoom_container`（View scale/translation）实现 graphicsLayer 同语义——批准；③**字幕层不随缩放**（`subtitle_overlay_compose` 在容器外，保持原尺寸可读性优先）——批准为正式行为；④连击累计值不设上限（倍率 ×3 封顶）——符合定稿预期。
- 事实：连击基准档取设置值（非写死 10/20/30）；方向锁阈值 10vp（K60=26.3px），旧 `|dy|<2|dx|` 判定已删；「越久越快」偏好键 `pref_player_gestures_speed_ramp` 在 `PlayerExtraPreferences`（默认关，走查后已复原）；VIDEO-25 核查结论「播放页无非全屏态，不适用」已关闭；`core` 的 `Constants.ZOOM_SCALE_BASE/THRESHOLD` 已无调用方（core 禁改，留 W 后清理）。
- **L6（已关闭）**：~~mpv + 视图层缩放只有代码路径判断~~ **用户 2026-11-19 人工走查通过**（双指捏合缩放/双指双击还原），关闭。

## T8e 设计定稿（Kimi，2026-11-19，deepseek 零裁量）

依据 VIDEO-32（最小态）/VIDEO-46 + T8d 交付的协调器 API。脉动与「已对齐」淡出 DEFERRED（T6 拍板范围）。

- **入口**：右侧工具面板列表新增「SyncPlay」一项（位置在「画面」之后、「设置」之前；图标暂用既有 `ic_sync`/最相近同步类图标，无则用 `ic_group`，标注待图标批次补 K1 重绘）；面板打开前先 `probeCapability()`，`Unavailable` 时**隐藏**该入口（不做置灰）。
- **面板（未入组）**：标题「同步观看」；主按钮「创建同步组」（`CinefinShapes.Button`，主 CTA 用 aurora 渐变——K1 规则允许）；其下「加入现有组」分组列出 `getGroups()` 返回的组（PanelRow：组名 + 成员数），点击即 `joinGroup`。创建走字符串资源默认名「<用户名> 的组」不弹输入框（最小方案）。**创建前先本地暂停**（定稿卡原文）。
- **面板（已入组）**：组名（PanelTitle 样式）、成员列表（用户名字符串逐行 PanelRow）、底部「离开组」（错误色文字，非破坏性强调）；离开时**不弹确认框**（go-minimal，组离开即销毁语义服务器兜底）。
- **状态胶囊（VIDEO-32 最小态）**：仅在入组期间出现，位置 = 顶栏标题右侧（随控制层出入，不独立常驻）；玻璃底 `colors.glass` + `glassBorder` + `CinefinShapes.Full` 胶囊、高 28dp、内边距横 10dp；内容 = 8dp 圆点 + 13sp 文字：`已同步`（圆点 aurora1 `#3DE8C2`，仅 `state==Playing/Paused/Idle` 且连接正常）/ `重连中…`（圆点 `colors.onSurfaceVariant`，`connectionState != CONNECTED`）/ `时钟同步中…`（圆点同上，`ClockWarming`）。点击胶囊 = 打开 SyncPlay 面板。
- **组内行为约束（裁决已记录）**：上/下一集与队列点选入口在闸门开启时拦截，提示条「同步观看中，换集由组控制」（复用 PlaybackRecoveryBar 样式，2s 自动消失）；长按倍速已被闸门禁用（T8d），面板不显露。
- **异常态**：join 失败/创建失败提示条 3s（「加入失败：组不存在或已解散」等按 `SyncPlayError` 映射文案，中英文入 strings.xml）；`GroupDoesNotExist/LibraryAccessDenied` 踢出事件 → 胶囊消失 + 提示条「已被移出同步组」3s。

## T5 设计定稿（Kimi，2026-11-19，deepseek 零裁量）

四项评估拍板：

1. **双击连击递增：实现**（VIDEO-20 MUST）。单击 ±10s（取设置步长为基准步长）；800ms 连击窗口内第 2 次 ±20s、第 3 次起 ±30s（3 档封顶）；HUD 显示**累计值**（如「快退 −30s」），窗口结束或松手超时后清零。方向性波纹保留现状样式。
2. **方向锁：改 10vp 显著位移仲裁**（VIDEO-26 MUST）。首次位移达 10vp 前不锁定（也不触发任何手势反馈）；锁定后本次手势不再换向；斜滑（位移夹角 30°–60°）达锁后按主轴执行，不跳变。替换现状 `|dy| < 2|dx|` 比率判定 + 50px 阈值。
3. **双指连续缩放 + 平移：升级**（VIDEO-24 MUST，取代纯档位）。双指捏合 1.0–3.0x 连续缩放，锚点跟随双指中心；缩放 >1.05x 时单指拖动 = 平移（此期间单指 seek/亮度/音量手势禁用），≤1.05x 恢复手势语义；双指双击 = 还原 1.0x。既有「铺满/还原」档位键保留不变。画面变换走 PlayerView 外层 `graphicsLayer`（scale+translation），不动渲染管线。
4. **「越久越快」长按档：实现为可选开关**（VIDEO-23 MUST 的可选半句）。设置面板加开关「长按倍速逐级加快」（默认关）；开启后长按每 2s 升一档：基础档→+1x→+2x，封顶 4x（基础档取设置值）；松手恢复。开关关闭 = 现状行为。
5. **非全屏下滑退出（VIDEO-25）**：先核查——播放页现形态以全屏为主，`PlayerFormFactor` 边到边布局下无「非全屏播放」态；若核查确认无非全屏态，记录「不适用」关闭此条，不实现。

## T3a-2/T8d 交付遗留（2026-11-19 收口记录）

- **T3a-2 结构**：快照采集=`PlayerViewModel.captureEngineSwitchSnapshot`；编排=`PlayerHolder.switchBackendInPlace`（`backendSwitchInProgress` 守卫 + rollback）；上层重绑=`PlayerViewModel.rebindAfterBackendSwitch`；宿主=`PlayerActivity.requestBackendSwitch`（四条手动/自动入口统一）。轨道映射=`EngineTrackMatcher.match()` 三级命中。MediaSession 走服务既有 `rebuildSessionIfPlayerChanged()`（换实例必重挂，未重启服务/Activity，KDoc 已写清）。
- **T3a-2 真机教训（后续代理必读）**：mpv 的 vo 必须在 `prepare()` 前绑定 surface；`setMediaItems(..., startPositionMs)` 后**不要**再补 seek（mpv 会卡在 seek 中间态）；播放中 `uiautomator dump` 常返回旧内容，位置读数必须先暂停再 dump。
- **L5（新立遗留）**：自动回退链（起播错误→自动切 mpv）已统一走原地切换管线但**无坏流片源无法真机复现**，四条入口代码路径已统一、档位语义保留；带坏流 fixture 的复验并入 T7 域验收。
- **T3a-2 存疑（Kimi 已裁决）**：Exo 引擎层字幕映射会先按 isDefault 兜底选一条轨、随后 VM 路由恢复正确轨——最终状态正确，接受现状；Exo 字幕映射完全交路由层的优化不立项。
- **T8d 结构**：`SyncPlaySessionCoordinator`（@Singleton 进程级）注入 `PlayerActivity`（非 ViewModel——直接 new VM 的测试会崩 + 避免循环依赖）；host=`PlayerHolderSyncPlayHost`（scope=`PlayerHolder.processScope`）；三路 WS 流各只收集一次；`notifyBackendRebound()` 监听位解耦 holder↔coordinator。映射文件落 `player/local/syncplay/`（data 看不到 player:core，data 零改动）。
- **T8d 裁决（Kimi）**：断线重连「对齐」= 刷组快照+解决 Joining+组不存在收尾（GET 不返回位置 ticks，协议限制，位置由后续组命令纠正）——接受；**长按倍速在组内禁用**（与漂移追帧抢倍速）——批准，T8e 面板不显露。
- **T8e 必读**：协调器 API（`createAndJoin/joinGroup/leaveGroup/state/activeGroup/connectionState/gate`）与实组验收五步骤在 T8d 交接里（含「创建组先 pause 再 createAndJoin」「协调器未包 playQueue，T8e 直接用 gateway 或补 `startGroupQueue()`」）；**组内换集（上/下一集、队列点选）未接闸门**——T8e 规格：入组期间这些入口拦截并提示「同步观看中，换集由组控制」（最小方案，不做 setPlaylistItem 接管）。

## T3a-1/T8c 交付遗留（2026-11-19 收口记录）

- **T3a-1 裁决（Kimi）**：能力位只加 2 个（`supportsTrackEnumeration`/`supportsSubtitleDelay`）——`supportsAudioDelay`/`supportsSubtitleStyling` W2 已存在，批准不加同义字段。
- **T3a-1 事实（T3a-2 必读）**：`EngineTrack` 8 字段逐字按定稿；Exo 轨 id=`"group:track"`（轨道重建会漂移，**切换快照必须在同一会话内取**）；Exo `setSubtitleDelay/Style` 经 `ExoSubtitleHooks` 转发 `PlayerSubtitleController`（同一语义源，非平行套）；mpv `selectTrack(SUBTITLE,null)`=「sid=no」，恢复时勿传空串；Exo 直调 `selectTrack(SUBTITLE,id)` 会与 `applySubtitleRoutingParameters` 竞争——原地切换应走快照恢复后首次参数下发；`installExoSubtitleHooks(null)` 在 `onCleared` 无条件清空（单播放页无影响）。Exo 轨道枚举/选择与双内核延迟生效**未真机验**，并入 T3a-2 K60 验收。
- **T8c 裁决（Kimi）**：PAUSE 晚到**不做** elapsed 补偿（组已冻结，期望位置=positionTicks）——公式语义正确，批准；`QueueChanged` 不进状态机（组队列编辑本波 DEFERRED），data 侧该事件 T8d 丢弃。
- **T8c 事实（T8d 必读）**：必须双循环 `clock.run()` + `controller.run()`；T8a 的 `commands/groupUpdates` Flow 每次收集重订阅 WS，**只收集一次**；`SyncPlayDriftProfile.forBackend(engine.backendName)` 取档（切内核后需重建或改可变注入）；core 与 data 同名模型映射文件需 `import as`；`STOP`/踢出/`UserJoined` 等边界已处理；`onJoinRequested/onJoinFailed` 供装配层驱动 Joining 态。
- **graphify**：T3a-1 代理未跑（边界），负责人已统一跑。

## T8c 设计定稿（Kimi，2026-11-19，deepseek 零裁量）

- **模块隔离**：`player/core` 不得依赖 `data`。T8c 在 `player/core/syncplay/` 自定义命令/组事件模型（字段与 T8a `SyncPlayCommand`/`SyncPlayGroupUpdate` 对齐：`whenAt`/`emittedAt`/`positionTicks`/命令类型），data→core 映射归 T8d 装配层。
- **播放口**：T8c 定义 `SyncPlayPlaybackPort` 接口（最小集：`play/pause/seekTo(positionMs)/setRate(rate)` + 读侧 `currentPositionMs/isPlaying`；缓冲态读不到就省，T8d 实现时再议），单测全 fake。
- **时钟**：消费 T8b `SyncPlayClock`（`nowServer()` 对比 `whenAt`；`state.isSynchronized` ⇔ `ClockWarming` 迁移）。
- **漂移分档参数（写死）**：漂移 = 本地位置 − 组期望位置（`positionTicks + (nowServer − whenAt) × rate`）。Exo：|漂移|<60ms 忽略；60–400ms 变速追帧（领先 → 0.95x，落后 → 1.05x，回到 <30ms 恢复 1.0，滞回防抖）；>400ms 直接 seek。mpv：阈值放宽 150/600ms（R2），追帧倍率同。
- **晚到命令**：到达时已过期（`nowServer > whenAt`）→ 按 `positionTicks + elapsed×rate` 直跳，不补播。
- 状态机七态（NotInGroup/Joining/Idle/Armed/Playing/Paused/ClockWarming）迁移表在代码 KDoc 写全，单测逐条覆盖。

## T3a 设计定稿（Kimi，2026-11-19，deepseek 零裁量）

T3a 拆两步：**T3a-1** = 接口扩展 + 双内核实现（本波，纯 player 层）；**T3a-2** = 上层迁移 + 原地切换 + 遮罩/提示（下波，依赖 T3a-1）。依据 VIDEO-02/03/04/05/06/07/34。

### 1. 接口扩展（T3a-1，`player/core/playback/`）

新增模型（纯 Kotlin，无 Android 依赖）：

```kotlin
enum class EngineTrackType { VIDEO, AUDIO, SUBTITLE }
data class EngineTrack(
    val id: String,            // 内核内稳定 id（exo 用 group/renderer 索引编码，mpv 用 track id；格式是实现细节，调用方只当不透明 token）
    val type: EngineTrackType,
    val language: String?,     // BCP-47，未知为 null
    val title: String?,
    val codec: String?,        // 编码名小写（ass/pgsub/aac/h264…），供图形字幕判定与映射
    val isExternal: Boolean,
    val isDefault: Boolean,
    val isForced: Boolean,
)
```

`PlaybackEngine` 新增动词（主线程约定与既有一致）：

```kotlin
fun availableTracks(type: EngineTrackType): List<EngineTrack>
fun selectedTrack(type: EngineTrackType): String?   // null = 该类型未启用（字幕常见）
fun selectTrack(type: EngineTrackType, trackId: String?)  // trackId=null 仅字幕允许（=关闭）
fun setSubtitleDelay(delayMs: Long)
fun getSubtitleDelay(): Long
fun setAudioDelay(delayMs: Long)
fun getAudioDelay(): Long
fun setSubtitleStyle(style: SubtitleStyle)           // SubtitleStyle 已在 player/core/domain/models，直接引用
```

约束：不改既有 7 个动词语义；Exo/mpv 双实现各自的怪癖收口在实现内部（mpv 的 `sub-delay`/`audio-delay` 属性、Exo 的 `TrackSelectionParameters`/`AudioDelayProcessor` 接线）；`EngineCapabilities` 增补能力位（轨道枚举/字幕延迟/音频延迟/样式 各一个布尔），不支持的内核返回空表/空操作而非抛异常。

### 2. 原地切换交互规格（T3a-2 用，先定稿）

- **共用管线**：手动切换（设置面板、T2 图形字幕提示条「切换内核」）与自动回退都走同一「原地切换」管线；`switchBackendAndRestart` 的 Activity recreate 路径降级为管线异常时的兜底（一次，记 Timber 警告）。
- **非阻断提示**（VIDEO-04/34）：全程**无对话框**。切换开始即出提示条（样式复用 `PlaybackRecoveryBar`：玻璃底 `colors.glass` + `glassBorder` + `CinefinShapes.Xs`）：「正在切换到 mpv 内核…」；成功换「已切换」1.5s 自动消失；失败换「切换失败，已保持原内核」3s。
- **淡入遮罩**（VIDEO-07/34）：切换开始画面叠 `scrim @ 90%`（`tween(Fast=200, Standard)` 淡入），遮罩上**无文案无 spinner**（状态全在提示条）；新内核 ready 后 `tween(Player=280, Standard)` 淡出。与控制层可见性解耦。
- **状态快照**（切换前采集，切换后恢复）：position（`confirmedPosition` 口径，误差验收 <1s）、isPlaying、rate、字幕轨/音轨选择、字幕延迟、音频延迟、字幕样式、画面比例、循环模式。
- **轨道映射表**（Exo↔mpv 重选轨，按序命中）：① 精确：language + codec + isExternal + title 全等；② 宽松：language + type + isExternal；③ 兜底：同类型 isDefault 轨；原类型未启用（字幕关闭）则保持关闭。映射不进持久化，仅切换瞬间使用。
- **K60 验收**：jellyfin-test 上 Exo→mpv 与 mpv→Exo 原地切换位置误差 <1s、已选字幕/音轨保持、无 Activity 重开感（窗口不重建）；回退链（错误回退自动切 mpv）不回归。

## T2/T8b 交付遗留（2026-11-19 收口记录）

- **T2 裁决（Kimi）**：「· 图形」标注仅用于真图形轨（PGS/VobSub 族），ASS/SSA 不标（文本格式，标「图形」误导）；但 mpv 提示条对 ASS/SSA/PGS 全触发。代理偏离任务卡括注，理由成立，批准。
- **T2 事实**：`DELAY_STEP_MS=50`（±10s 不变）；延迟显示改两位小数（`+0.05s`），chip 文案由常量推导；行副标题瘦身为「格式 · 落点」，语言进组头、来源进行尾；`PlayerSubtitleOverlay.visibleText` 升 internal 供回归测试。mpv 字幕样式**已实时**（`applySubtitleStyle` 写运行时可写属性），无补差；`sub-ass-override` 未显式设置（作者样式 ASS 可能部分不接受覆盖，边界已知）。**VIDEO-15 在线字幕搜索全仓零实现、零依赖，SHOULD 不实现，关闭。**
- **T8b 事实（T8c/T8d 消费口径）**：`SyncPlayClock`（`player/core/syncplay/`）：`state.isSynchronized` = ≥1 样本；`run()` 吞单次采样异常，T8c 直接 `launch { clock.run() }`；退组 `reset()`；命令 deadline 用 `clock.nowServer()` 对比 `whenAt`。与上游 TimeSync.js 唯一偏差：任务卡钉 3 次 greedy（上游实际 4×1s），Kimi 裁决维持任务卡口径。`SyncPlayTimeSource`↔`gateway.getServerTime()` 适配器留 T8d。
- **L4（小遗留）**：`player/core/build.gradle.kts` 硬编码 `kotlinx-coroutines-core:1.11.0`（catalog 无条目），下波顺手收进 version catalog。
- **graphify**：T2/T8b 代理未跑（边界限制），负责人已统一跑。

## T1/T8a 交付遗留（2026-11-19 收口记录）

- **L3（新立，小卡）**：`PlayerActivity` 目前 `CinefinTheme(themeId = DEFAULT)` 写死，播放页不随 App 主题换色板。Kimi 设计拍板：播放页跟随 **aurora↔amoled** 深色主题（light/dynamic 映射 aurora dark，保持影院深色基调——与「T1 设计定稿」§1 一致，VIDEO-35 的 OLED 维度由此闭合）。改动面：PlayerActivity 传 themeId 映射 + 播放页组件对照 aurora/amoled 玻璃参数抽查，随 T5 或独立小卡派。
- **trickplay 上浮淡入**：代码已实现但本次片源无 trickplay 未真机触发，复验并入 T4。
- **动效主观手感**（松手回弹/knob 微弹/stagger 节奏）：~~adb 采样粒度无法取证，建议用户人工触摸走查~~ **用户 2026-11-19 人工走查通过，关闭**。
- **范围外圆角**（不属 W4 播放域，留各域波次）：`modes/book/ReaderSettingsPanel.kt`(14)、`core/.../CinefinNavigation.kt:399`(9)、`modes/music/MusicModeScreen.kt:1251`(12)；`app/tv` 冻结不动。
- **T8a 接口事实**（T8b/c/d 消费注意）：403 一律映射 `LibraryAccessDenied`；时区换算 `toInstantInSystemZone()` 严格可逆，T8b 须契约测试复验；`commands`/`groupUpdates` Flow 每次收集重新订阅 WS（T8d 订阅计数语义依赖此）；`SyncPlayGatewayImpl` 未做 Hilt 绑定，装配留 T8d。

## 日志（只留最近 ~5 条）

- 2026-11-20（三）：**W4 正式收官**。D12 ✅（admin 双账号，web seek→App 误差 140ms，双向通）+ B5 ✅（mpv pgssub 上屏，同帧差分取证）。新立 L8（媒体键绕开组闸门，入 W5）。用户两次校准口径：验收项未完成不得标 CLOSED（已入记忆库）。
- 2026-11-20（二）：**W4 收官**。T7 验收（零 commit，8✅/4⚠️/1❌）+ **T10 ✅ `47095ed7`**（SAF 同级字幕推断+DB v18 等五项缺口修复，K60 取证过）。残留项全部有归属（L3/L7/D12 等）。trickplay 服务器扫描产出中（14/17 部电影已有）。
- 2026-11-20（一）：**T9 ✅ `f96b1313`**（收尾四小项，K60 三项取证过；profile=fast 覆写 hdr-compute-peak 的 post-init 补写裁决批准；app/phone coroutines-test 硬编码留小尾巴）。trickplay 用户已开服务器提取（扫描中），复验并入 T7。W4 只剩 T7 域验收。
- 2026-11-19（十）：**T3b ✅ `96169328`**（能力探测+mpv tone-mapping 三档即时生效）+ **T4 核查（零 commit）**（Exo HDR10 直通已证、mpv 三档观感量化已证、trickplay 服务器未开提取 BLOCKED 待用户拍板）。新立 T9 收尾小项包（章节气泡时间戳/手机解码入口/mpv HDR 补偿/L4）。L6 关闭（双指缩放用户走查过）。
- 2026-11-19（九）：**SyncPlay 复验全过**（稳态 0 次校正 seek、切内核 0/5ms、断网恢复、0.95× 追帧上屏）+ **T5 ✅ `b19c8d52`**（连击递增/10vp 方向锁/双指缩放/逐级倍速开关，K60 走查除多点注入受限项外全取证；VIDEO-25 不适用关闭）。L6 立（mpv 缩放人工确认）。
- 2026-11-19（八）：**T8e ✅ `b32b81cc`**（面板+胶囊+拦截，K60 实组建组/web 入组/组销毁已证）+「控制层冻结」调查（`e59c01e3`，结论：uiautomator 树陈旧误判，非 UI 缺陷）+ **seek 风暴修复 `7d601455`**（in-flight 抑制 + 组时间线锚点；修复后实组复验欠账：切内核/断网/稳态）。T1 动效用户走查通过。代理误删 Temp 旧文件事故已记录并加任务卡条款。
- 2026-11-19（七）：**T3a-2 ✅ `f118f49c`**（原地切换四入口统一，K60：位置误差 0ms、轨道保持、窗口不重建；mpv vo 预绑定/setMediaItems 后禁补 seek 两条真机教训入库）与 **T8d ✅ `d4762f1f`**（协调器 + 闸门 + seekSink 委托 + 断线恢复，33 例单测）串行交付。L5 立（坏流回退复验入 T7）。
- 2026-11-19（六）：**T3a-1 ✅ `8f49e1ad`**（引擎 8 动词 + EngineTrack + 双内核实现，能力位 +2）与 **T8c ✅ `788d25bf`**（七态状态机 + deadline + 双内核三档漂移，29 例单测）并行交付；T3a/T8c 设计定稿入库；两项裁决（能力位 +2、PAUSE 晚到不补偿）批准。
- 2026-11-19（五）：**T2 ✅ `c8a05dce`**（50ms 步进/语言分组标注/图形字幕非阻断提示，K60 取证：步进、seek 与引擎切换后偏移保持、分组与提示条上屏，已停播放）与 **T8b ✅ `ad8b2709`**（SyncClock，公式与 jellyfin-web 逐字对应，9+3 例测试全绿）并行交付；合态终验全绿。VIDEO-15 查无实现关闭；L4 立（coroutines 进 catalog）。
- 2026-11-19（四）：**T1 ✅ `d2795184`**（deepseek，K1 玻璃换肤 + 弹簧动效 + 圆角清理 9 处 + 自动隐藏 4s；K60 取证：隐藏 4.29–4.32s、暂停降亮 ≈0.80 含边缘压暗、连点可中断无 ANR，已停播放）与 **T8a ✅ `1ef74954`**（deepseek，SyncPlay gateway 22 动词 + 探针三路径契约测试 20 例）并行交付，门禁全绿；T1 设计规格 Kimi 定稿入「T1 设计定稿」节；新立遗留 L3（播放页 themeId 跟随 aurora↔amoled），trickplay 复验并入 T4。
- 2026-11-19（三）：用户拍板 SyncPlay **go**（go-minimal 范围），立 T8a–T8e 实现卡；新增纪律「设备用后必停播放」（media_session state=NONE + force-stop 兜底，已入记忆库）；补回新会话开工提示词。
- 更早日志（W4 开工/T0/L1/L2/T6 与逐卡交付细节）见各「交付遗留」节与 git log（docs commit 逐波可查）。
- 2026-11-19（二）：L1 retrySeek 修复提交 `ccdafefa`；L2 TLS 复验通过（K60 mpv 生产播放健康）；T6 SyncPlay 调研完成；顺手修正 `DEV_ENVIRONMENT.md` 服务器版本 10.11.8→12.4.0 并记录 jellyfin-test=生产同实例。
- 2026-11-19：W4 开工。T0 调查完成；L1/L2 认领；任务卡定稿 T1–T7。
- 2026-11-19：W3 收口后建卡（`69b9f87f`），含开工提示词与任务草案。
