# 2.0 W4 · 视频域（任务卡）

> 状态：`ACTIVE`（2026-11-19：T0 调查完成、任务卡定稿；遗留 L1 已修，L2 复验进行中）
> 依据：`docs/V2_REQUIREMENTS.md` §9（V2-VIDEO）与 §15 波次（W4 ← W2）；架构 `docs/V2_ARCHITECTURE.md` §3；设计唯一权威 `docs/V2_DESIGN.md`。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W4 开工基线 `651cd427`（W3 全部合入）。
- **T0 ✅（2026-11-19，Kimi 亲自）**：§9 全 50 条现状核对完成，结论见下文「T0 现状核对」；SyncPlay 评估输入已齐（SDK 1.8.12 全套 API + 生产服 12.4.0），go/no-go 见 T6 卡。
- **遗留 L1 ✅（2026-11-19，Kimi 亲自，commit `ccdafefa`）**：`seekSink.retrySeek()` 跨条目意图重建 bug 已修——失败请求是 MediaItem/AdjacentMediaItem 时沿用原目标条目与落点，不再从回退后的引擎条目重建；新增回归测试（RED→GREEN 验证），门禁 player/local+phone 单测 + ktfmt + 构建全绿。
- **遗留 L2 ✅（2026-11-19 复验通过）**：生产 `jellyfins` mpv TLS `-0x7280` 已随服务器侧修复消失。K60（新构建含 L1）mpv 内核直连生产 HTTPS 播放《告白实行委员会》（mkv/HEVC/FLAC 2.1GB）：无 mbedtls 错误，位置 6s→48s 实时推进，UI「正在播放」。排障插曲：Room 缓存的 accessToken 与 movie itemId 均已失效（401/源解析失败），App 线上会话不受影响；真机取证一律走 UI 驱动，勿用缓存 itemId 冷启 PlayerActivity。
- **W3 遗留并入 W4**：9 处非 token 硬编码圆角（`PlayerContentTabItem` 10、`PlayerSubtitleOverlay` 6、`ErrorDialog` 28、`LyricsSheet` 8、`BaseBadge` 8、歌词覆盖层 22 等）随 T1 播放器 UI 换肤一并清理。
- **本次质量门**：相关模块单测 + `ktfmtCheck` + `:app:phone:assembleDebug --max-workers=2` 绿；核心链路 K60（`8e875894`）真机验证，播放链路验证用 jellyfin-test（生产 jellyfins 永久只读，App 播放链路自身请求除外）。
- **环境备注**：`export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'`；K60 density 420 = 2.625 px/dp、Pad `43af8627` 360dpi = 2.25 px/dp；CI 仅 master push/PR 触发。

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
| T6 | SyncPlay go/no-go 评估（Kimi 亲自或 deepseek 纯调研卡）：基于 T0 输入产出技术方案与工作量估算；go 则另立实现卡（状态机 + 时钟同步 + 漂移校正 + 组队列） | VIDEO-36~46 | 评估报告含 SDK 适配面、WS 通道方案、风险与裁剪建议 |
| T7 | 本地媒体核查 + 域验收：SAF 外挂字幕同级推断核查；VIDEO-49/50 全量验收清单（双内核状态保留、图形字幕、字幕不漂移、SAF 失效/组不存在/版本过低非阻断提示） | VIDEO-47/48/49/50 | 验收清单逐项 K60 取证 |

## 日志（只留最近 ~5 条）

- 2026-11-19：W4 开工。T0 调查完成（结论如上）；L1 retrySeek 修复 + 回归测试（门禁绿，待提交）；L2 TLS 复验进行中；任务卡定稿 T1–T7。
- 2026-11-19：W3 收口后建卡（`69b9f87f`），含开工提示词与任务草案。
