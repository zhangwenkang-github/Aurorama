# Cinefin 播放界面 · 任务与进度（唯一权威文件）

> **新对话从这里开始。** 开工前读本文件，收工前把进度写回本文件。
> 纪律：需求变更、决策、完成度、勾选项、更新日志，都在**同一次改动**里写回这里；不再新建零散 `.md`。
>
> 最后更新：2026-10-02　分支：`feature/w20-playback-enhance`（W20-PLAYER：进度写入 UserData + 片头尾阈值 / 提示条 + Trickplay 按需与降级；基线 `master 7ec5015`，落地记录见 §23）

---

## 快速上手（新会话必读）

### A. 项目是什么

Cinefin = 自用 Jellyfin 客户端（findroid 分支改造）。**本任务只做播放器**：`app/phone` 的播放页
（`PlayerActivity` + Compose 控制层），双内核 ExoPlayer（默认）/ mpv（软解兜底）。

| 模块 | 作用 |
|------|------|
| `app/phone` | 主应用（手机 + 平板）：`PlayerActivity`、`BasePlayerActivity`、`presentation/player/*`（控制层）、`playback/CinefinPlaybackService`（通知 / 后台 / 前台服务） |
| `app/tv` | TV 端独立 Compose UI（本轮少动，阶段 8.9 对齐） |
| `core` | 主题、工具、通用图标 / 字符串（`CoreR`） |
| `data` | Jellyfin 数据模型与仓库（`FindroidItem` / `FindroidEpisode` 等） |
| `player/core` | 播放模型（`PlayerItem` / `Track` / `Trickplay` / `PlayerChapter`） |
| `player/local` | 播放核心：`PlayerHolder`（实例持有）、`PlayerViewModel`、`PlaylistManager`（队列）、`TrackSelectionEngine`、`mpv/MPVPlayer` |
| `modes/film` | 电影 / 剧集业务页面 |
| `settings` / `setup` | 偏好设置 / 服务器配置向导 |
| `docs/PLAYER_PLAN.md` | **本文件：唯一权威文件** |

改播放器基本只碰这几处：
`PlayerControlOverlay.kt`（骨架 + 顶/中/底栏 + 面板）、`PlayerFormFactor.kt`（形态判定）、
`PlayerContentPanel.kt`（内容栏 / 选集列表）、`PlayerOverlayContainer.kt`（触摸命中区）、
`PlayerActivity.kt`（宿主）、`PlayerViewModel.kt` + `PlayerHolder.kt` + `PlaylistManager.kt`（播放逻辑）。

### B. 规范与命令（硬约束）

1. 全程简体中文；文档只维护本文件，不新建零散 `.md`。
2. 构建（JDK 必须用这个，工程按 Java 21 编译）：
   ```powershell
   $env:JAVA_HOME='D:\Android\Android Studio\jbr'
   cd E:\codex_work\Android_Studio_Work_Space\Cinefin
   .\gradlew.bat :app:phone:assembleDebug --console=plain
   ```
3. **真机调试**（模拟器太卡已弃用；真机 = 小米平板 5 `nabu` / Android 13 / 1600×2560）：
   ```powershell
   adb install -r app\phone\build\outputs\apk\libre\debug\phone-libre-arm64-v8a-debug.apk
   # debug 包播放页 exported=true，可直接带条目启动
   adb shell am start -n com.zhangwenkang.cinefin.debug/com.zhangwenkang.cinefin.PlayerActivity --es itemId "<UUID>" --es itemKind "Episode"
   adb shell dumpsys media_session | Select-String cinefin          # 会话 / 播放状态
   adb shell uiautomator dump /sdcard/u.xml; adb shell cat /sdcard/u.xml | Select-String 'text="'  # UI 文本
   adb shell run-as com.zhangwenkang.cinefin.debug cat shared_prefs/com.zhangwenkang.cinefin.debug_preferences.xml  # 读偏好（临时改内核）
   ```
4. 测试服务器 `jellyfins.zhangwenkang.com` **只读**（禁止任何写入 / 删除调用）。
5. 每完成一个阶段就 `git commit`（先编译通过；信息用 `feat(player): …` / `fix(player): …`）。
6. 工具输出必须裁剪（`-Last N` / `Select-String`）；截图只在必要时看、看完即删、不贴进回复。

### C. 现在做到哪了

播放页已可日常使用：手机 / 平板形态自适应、控制层完整（八态按钮 / 错误卡片 / 面板 / 缩略图选集）、
右侧选集栏（默认收起、可手动弹出、点画面收起）、通知栏与后台播放、PiP、双内核与静默降级。
完成度看板见 §3；**待办集中在 §1「下一步任务」**，踩坑经验在 §9。

---

## 0. 已确认决策（不要再问）

| 编号 | 决策 |
|------|------|
| D1 | 形态优先级：**平板 > 手机 > 折叠 > 小窗 > TV > 车机**；六类形态全部纳入设计与落地（原文「TV 本轮不做」作废，见 D9） |
| D2 | 本轮只做**视频播放**，音乐 / 纯音频形态忽略（歌词、均衡器、空间音频全部不做） |
| D3 | TV 端不再冻结：按 10-foot / D-pad 规范对齐（`app/tv` 已有独立实现，阶段 8 做对齐与验收） |
| D4 | 通知栏 / 锁屏控制要**全套**：标题、封面、上一集、播放/暂停、下一集、快退、快进、关闭、进度 |
| D5 | 外部播放器：**仅当进度能同步回 Jellyfin 才做**。结论：不可靠 → **不做**。理由：外部播放器播放期间 App 不在链路中，无法上报 `Sessions/Playing/Progress`，返回后回填属于伪同步。仅在 `PlayerIntent` 留接口位 |
| D6 | 范围裁剪以 §2 为准：投屏 / DRM / 广告 / 弹幕 / 直播 / 录制一律不做；**车机改为做**（大触控 + 硬件键/旋钮焦点，不接 Car App Library 模板） |
| D7 | 文档纪律：只维护本文件；新增说明写进本文件，不新建 `.md` |
| D8 | **上下文预算（硬约束）**：模型 API 单次请求纯文本 ≤ **880KB**、含内联图片 ≤ **48MiB**。工作时必须：① 不把截图/大图塞进对话；② 工具输出一律裁剪（只取 `-Last N` / `Select-String` 命中行）；③ 验证优先用文本手段（编译、`adb` 的 `dumpsys` / `uiautomator dump` / 过滤后的 `logcat`），截图只在必要时看、且看完即删、不贴进回复；④ 接近上限时主动提醒用户并建议开新对话（进度已在本文件，可无损接续） |
| D9 | **多形态统一由代码判定驱动**：新增 `PlayerFormFactor`（Phone / Tablet / Foldable / Tv / Car / Freeform）+ `PlayerChromeLayout`（Fullscreen / SplitSide / SplitPortrait / FoldHalfOpen / Compact / Pip）作为唯一布局开关；不做"每个形态一套界面"，只做"一套组件、六种骨架"。判定输入：`WindowSizeClass` + `FoldingFeature` + `UiModeManager` + `PackageManager` features + `isInMultiWindowMode` |
| D10 | 车机不做独立模块：复用 phone 模块，`Car` 形态关闭沉浸式全屏、放大命中区到 64dp、限制为横屏、保留硬件方向键/旋钮焦点 |

技术栈（不变）：Kotlin + Jetpack Compose + Media3/ExoPlayer（FFmpeg 软解兜底 + libmpv 二级兜底）。

目标架构（D9 落地方向，阶段 8 收口）：**播放页最终为全 Compose 单 Activity**，
仅用 `AndroidView` 托管两样不可 Compose 化的东西——Media3 `PlayerView`（视频输出 + 字幕）与 mpv `SurfaceView`；
控制层、内容栏、面板、手势 HUD 全部 Compose。当前 `activity_player.xml` 里的 XML HUD 逐步下线。

---

## 1. 下一步任务（挑一条开工：改代码 → 真机验证 → 勾掉 → 写 §10 日志 → commit）

> **用户已批准的执行顺序**：① 字幕面板 → ② 音轨面板 → ③ libass 字幕渲染（M4 缺口）→ ④ 下载离线增强 → ⑤ 投屏。
> 通知封面、阶段 4 收尾、手势打磨等作为穿插项，见下面 P0 / P2。

### P0 · 直接影响日常观影

- [x] **1.1 字幕面板补全**（原阶段 3.1）——延迟 ±0.1s、双语次字幕、外观（大小 / 颜色 / 背景 / 描边 / 位置）。
      落点：`PlayerControlOverlay.kt` 的 `PlayerPanel.Subtitle` 分支 + `PlayerViewModel` +
      新增自管字幕管线（`player/local/subtitle/`：`PlayerSubtitleController` + `SubtitleParser`）+
      `PlayerSubtitleOverlay.kt`（Compose 渲染层）。
      实现：ExoPlayer 下文本字幕改由自研渲染（下载 Jellyfin 交付的字幕文件 → 解析 SRT/VTT/ASS →
      Compose 分层绘制），因此延迟可正可负且即时生效、主/次两条字幕可同时显示；图形字幕与拿不到
      文件地址的字幕仍走内核原生渲染。mpv 内核把面板设置翻译成 `sub-delay` / `secondary-sid` /
      `sub-*` 属性，同一套 UI。延迟与外观都落偏好（退出重进仍生效），主/次字幕语言进优先级列表
      （跨集/跨片沿用）。
      验收：外挂 ASS 字幕调延迟立即生效、退出重进仍记住；双语双轨不重叠。
      真机实测（小米平板 5）：《夏日幽灵》简日双语 + 繁日双语双轨同显；暂停帧上把延迟从 0 → −0.1s，
      字幕在同一帧从无到有（cue 起点 121.1s，播放位置 121.057s）；mpv 下 `sub-delay` 立即生效、
      重启后延迟/外观/次字幕语言全部保留。
- [x] **1.2 音轨面板补全**（原阶段 3.2）——音轨延迟、轨道描述、默认轨记忆。
      落点：`PlayerPanel.Audio` 分支 + `TrackSelectionEngine` + 播放内核。
      实现：ExoPlayer 侧新增自研 `AudioDelayProcessor`（通过 `CinefinRenderersFactory` 挂进
      Media3 音频链：正延迟插静音、负延迟丢帧，播放中即时生效，seek 后自动重新应用；
      同时显式关掉音频 offload——offload 直通硬件会让延迟静默失效）。mpv 侧写原生
      `audio-delay` 属性。面板：延迟条放顶部（±0.05s / 范围 ±5s / 点当前值归零），
      音轨列表带描述（编码 · 声道 · 码率或采样率）；延迟落偏好（退出重进仍生效），
      选轨沿用语言记忆（跨集 / 跨片沿用）。
      验收：音轨延迟即时生效且方向正确（+ = 声音晚）；轨道描述可读；选轨记忆生效。
      真机实测（小米平板 5）：ExoPlayer 日志「目标 50 ms → 插入静音 2400 帧（48000 Hz）」
      与 50ms×48kHz 精确吻合；两个内核的延迟方向都已用户听感确认（+ = 声音晚、− = 声音早）；
      两个内核的面板都显示「AAC · 2 声道 · 48 kHz」这类轨道描述。
- [ ] **1.3 手势打磨与验收**（原阶段 2.x；**手势功能本身已完成**，见下）
      已实现：长按倍速 / 长按跳章节、双击、横向滑动 seek（按屏宽比例 + 渐进加速 + Trickplay 预览）、
      左缘亮度 / 右缘音量、双指缩放、锁屏屏蔽（`isControlsLocked` 全路径判断）、灵敏度与档位设置项
      —— 全在 `utils/PlayerGestureHelper.kt` 与 `settings/…/AppPreferences.kt`。
      剩余：双击分区**触觉反馈**、手势与 Compose 命中区的优先级走查。
      验收：连续 20 次手势无冲突、无误触；锁屏后任意触摸不改变播放状态。
- [x] **1.4 通知封面**（D4 缺口）——自研通知 provider 用 `MediaMetadata.artworkUri` 异步加载后 `setLargeIcon`，
      加载完成用 `onNotificationChangedCallback` 刷新（勿阻塞通知线程）。
      落点：`playback/CinefinMediaNotificationProvider.kt`。
      实现：Coil 在 IO 线程解码（512px、ARGB_8888 软件位图），主线程只做通知组装；
      只缓存「最近一张」封面（避免整剧播放攒位图）、同 URL 不重复请求、失败不反复重试。
      真机实测（小米平板 5）：日志「通知封面就绪：363×512，刷新通知」后紧跟一次带封面的通知重建；
      用户确认通知栏封面显示正常。
- [ ] **1.5 阶段 4 收尾验收**——锁屏 30 分钟音频不中断、真机耳机拔出 / 蓝牙切换 / 来电暂停；
      自动降级 mpv 的端到端触发（上次因服务器视频流超时未能验成）。
- [x] **1.19 播放稳定性专项（PLAYER-STAB，2026-10-01）**——mpv 队列补片 ANR 修复 + 卡顿量化。
      落点：`mpv/MPVPlayer.kt`（阻塞型 mpv 命令改走 `HandlerThread("mpv-command")`，保序、release 关闸）、
      `presentation/PlayerViewModel.kt`（补片协程真后台 + 每条让出 50ms + 单次上限 150 条 + mediaId 集合去重）。
      真机（Pad 5 / mpv / 灼眼的夏娜 73 集）：补片+点击场景主线程峰值 **2584ms → 508ms**；80 次点击 5.2s
      无 `Input dispatching timed out`；修复前 mpv 补片窗口帧间隔 p99 166.68ms（SurfaceFlinger 采样）→
      修复后稳态 24fps 满帧、无 >100ms 停顿。脚本见 `tools/player-stability/`；详见 §9 踩坑与 §10 日志。

### P1 · 体验提升

- [x] **1.6 画面调整**（原阶段 3.3）——旋转 / 镜像 / 裁剪 / 去黑边（比例已有，偏好复用 `pref_player_resize_mode` 体系）。
      落点：「画面」面板（比例 + 几何变换同一个面板，`PlayerControlOverlay.kt`）、`PlayerActivity.applyVideoTransform()`、
      `player/local/domain/PlayerVideoTransform.kt`（纯函数：旋转铺满缩放 / 裁剪缩放 / 去黑边填满倍数）、
      `MPVPlayer.applyVideoTransform()`（`video-rotate` / `video-scale-x|y` / `video-zoom`）。
      实现：ExoPlayer 走视图变换（旋转 = `rotation` + `max(w/h,h/w)` 铺满；镜像 = `scaleX|Y` 取负；裁剪 = 收窄
      `exo_content_frame` 宽高比 + ZOOM；去黑边 = FIT→ZOOM）；mpv 走原生属性（`video-zoom = log2(裁剪放大 × 填满倍数)`）。
      新增偏好键单独声明在 `PlayerExtraPreferences`（见 §12 决策，不触碰 `AppPreferences.kt`）。**改完即时生效、退出重进保留**。
- [x] **1.7 队列管理**（原阶段 3.4）——拖拽排序、删除、清空、跳转；循环模式补「播完暂停」。
      落点：`QueuePanel` + `QueueEditableRow`（长按拖动换位 / 行尾删除 / 清空 / 点行跳转）、
      `PlayerViewModel.moveQueueItem|removeQueueItem|clearQueue`、`MPVPlayer.moveMediaItems|removeMediaItems`（`playlist-move|remove`）、
      `PlayerViewModel.advanceAfterItemEnd()` 的「播完暂停」分支。
      语义：「清空」= 只保留正在播的那一条（直接 `clearMediaItems` 会把当前条目也删掉并停播）；
      「播完暂停」= 当前一集播完停在片尾，不自动跳下一集（与循环模式正交，默认关）。
- [x] **1.8 信息面板补全**（原阶段 3.5）——容器 / 编码 / 分辨率 / 码率 / 帧率 / HDR / 音频格式 / 文件大小 / 路径。
      落点：`InfoPanel`（双内核合并 + 全字段「—」降级）、`PlayerMediaInfo`（`player:core`，随 `PlayerItem` 与 MediaItem extras 走）、
      `PlayerMediaInfoFormat`（纯函数 + 单测）、`PlayerKernelMediaInfo.readKernelMediaInfo()`（ExoPlayer `Tracks` / mpv 原生属性）、
      `MPVPlayer.queryMediaInfo()`。
      实现：媒体源（Jellyfin：容器 / 文件大小 / 路径 / 编码 / 分辨率 / HDR / 声道）+ 内核实测（编码 / 帧率 / 码率 / 色彩）两层合并，
      **内核优先**；两层都没有的字段输出「—」，行数固定（不会这行有那行没有）。
- [x] **1.9 播放页设置面板**（原阶段 3.6）——播放 / 解码 / 字幕 / 音频 / 画面 / 手势 六组，页内直接改。
      落点：`PlayerSettingsPanel.kt`（`PlayerSettingsController` + 六组面板 + `PanelSwitchRow` / `PanelChipRow` / `VideoTransformControls`）、
      `MorePanel` 新增「播放设置」入口、`PlayerViewModel.setSubtitleMode|setBackend|refreshSegmentPreferences`、
      `MPVPlayer.applyHwDec`、`PlayerGestureHelper`（手势总开关改为每次触摸现读）。
      六组内容：播放（后台播放 / 跳过片头片尾按钮 / 自动跳过 / 章节刻度 / 播完暂停）、解码（内核切换 + mpv 硬解）、
      字幕（模式 / 记住选轨 / 延迟外观入口）、音频（音轨面板入口 / 语言优先预设）、画面（比例 + 旋转 / 镜像 / 裁剪 / 去黑边）、
      手势（总开关 + 5 个手势开关 + 亮度记忆 + 铺满 + 长按倍速 + 两档灵敏度）。**全部写偏好即生效，不重开播放页**。
- [ ] **1.10 控制层视觉收口**（原阶段 1.4 / 1.5）——底栏时间可点切换「总时长 / 剩余」、章节入口、
      15sp / 13sp 字阶、等宽数字、渐变遮罩统一、加载细线。
      🟡 2026-10-01 部分落地（§11.4）：等宽数字（`MonoData`）、渐变遮罩统一（`scrim` 令牌）、字阶收敛到 Prism 字阶；
      「时间可点切换 / 章节入口 / 加载细线」仍未做，留给后续会话。
- [ ] **1.11 播放增强**（原阶段 6）——🟡 W20 完成三项：✅ 进度记忆与服务端同步、✅ 片头片尾阈值与提示条、
      ✅ Trickplay 按需预加载与失败降级（见 §23）；剩余：外挂字幕导入、播放结束行为（自动下一集 / 停在结束帧）。
- [x] **1.18 libass 字幕渲染**（M4 缺口）——✅ W15-LIBASS（**mpv 原生路径**，见 §18）+ ✅ **W16-PLAYER（Exo 路径，见 §19）**，两个内核都走 libass。
      mpv 内核：容器有内嵌字幕（DirectPlay）→ mpv 内置 libass 直接渲染；容器无字幕（服务器转码 / HLS）→
      按 MediaItem extras 的 Jellyfin 字幕清单 `sub-add` 独立 ASS 文件；App 覆盖层在 mpv 下不接管、不叠加。
      延迟（`sub-delay`）/ 开关（`sid=no|auto`）/ 语言（`slang` + 轨 `lang`）联动；`sub-ass-override` 保持默认 `scale`，
      ASS 的定位 / 字体 / 动画交给脚本 + libass 还原（颜色 / 背景 / 描边在 ASS 上让位给脚本，属有意取舍）。
      **ExoPlayer 路径（W16 已落地）**：引入 `io.github.peerless2012:ass-kt`（libass ISC）走「App 驱动」渲染——
      ASS/SSA 原文透传、SRT 由 `AssSubtitleScript` 生成 ASS，两者都交给 libass 画；延迟 / 开关 / 语言 / 大小档位与 mpv 同语义
      （ASS 的颜色 / 位置 / 描边让位给脚本，与 mpv 的 `sub-ass-override=scale` 一致），libass 失败回退既有文本渲染。详见 §19。
      验收：DirectPlay / 转码双场景 + 像素对比 + 性能 / 稳定性 + Exo 回归，证据见 §18.4。

### P2 · 多形态与打磨

- [ ] **1.12 折叠半开实测**（阶段 8.7）——`FoldHalfOpen` 骨架已写，缺折叠设备，待真机。
- [ ] **1.13 小窗 / 分屏实测**（阶段 8.8）——`Compact` 骨架已写，需真机 freeform / 分屏。
- [ ] **1.14 TV / 车机走查**（阶段 8.9 / D10）——10-foot 焦点、车机 64dp 命中区、不沉浸。
- [ ] **1.15 PiP 三键与细进度条**（阶段 8.10）——MediaSession 已就绪，验证 PiP 窗口三键与比例。
- [ ] **1.16 验收与打磨**（阶段 7）——真机回归矩阵、性能（首帧 ≤1.5s、2h 内存增长 <80MB）、
      无障碍（TalkBack、大字体 2.0×、键盘焦点可见）。
- [ ] **1.17 排障工具与工程基线**（阶段 0.3 / 0.4）——`PlayerDebugOverlay`（长按标题显示内核 / 解码器 / 码率 / 缓冲 / 丢帧）、
      `ktfmtFormat` + `lintDebug` 无新增告警。

---

## 2. 范围裁剪

### 2.1 做（P0）

播放/暂停/进度/上下集/倍速（含音调补偿）｜手势全套｜字幕（语言 + 延迟 + 外观 + 双语 + 记忆）｜音轨（切换 + 延迟 + 记忆）｜画面（比例/缩放/裁剪/旋转/镜像/去黑边）｜队列（拖拽排序 + 循环模式）｜剧集按季分组｜信息面板｜睡眠定时｜**通知栏与锁屏控制**｜后台播放｜音频焦点 / 来电 / 拔耳机｜PiP｜屏幕常亮｜跳片头片尾｜Trickplay｜进度记忆｜错误自动降级。

### 2.2 后置（P1）

平板/折叠分栏与半开形态｜外挂字幕导入｜播放结束行为设置｜键盘快捷键（外接键盘可用即可，不做自定义页）。

### 2.3 不做

投屏全家桶（DLNA / Chromecast / AirPlay / Miracast / 投屏码）、DRM、广告与会员、直播 LIVE 与线路切换、弹幕、歌词、评论与相关推荐、儿童模式与家长锁、二维码扫码、录制 / GIF / 逐帧、AB 循环、小窗悬浮窗、车机与 Android Auto、主题皮肤商城、外部播放器、TV 端改造。

系统截图保留（系统能力，不额外做 UI）。

---

## 3. 完成度看板（估算，随进度更新）

| 模块 | 完成度 | 状态 | 落点 / 备注 |
|------|--------|------|------------|
| 播放内核（ExoPlayer + FFmpeg + mpv 双内核 + 静默降级） | 88% | 🟡 | `player/local`：`PlayerHolder` / `PlayerViewModel` / `mpv/MPVPlayer`；2026-10-01 补片 ANR 修复见 §1.19 |
| 队列与选集（整剧补全、按季分组、缩略图行、拖拽排序 / 删除 / 清空 / 播完暂停） | 92% | 🟡 | `PlaylistManager` + `PlayerContentPanel` + `PlayerViewModel` 队列编辑（§1.7） |
| 控制层（三栏 + 进度条 + 锁屏 + 错误卡片 + 八态按钮 + 清晰度徽标） | 88% | 🟡 | `presentation/player/PlayerControlOverlay.kt` |
| 面板系统（倍速 / 循环 / 比例 / 字幕 / 音轨 / 信息 / 队列 / 睡眠 / 更多 / 设置） | 88% | 🟡 | 字幕（§1.1）、音轨（§1.2）、画面（§1.6）、队列（§1.7）、信息（§1.8）、设置（§1.9）已补全，见 §12 |
| 系统层（通知栏 / 后台 / 焦点 / PiP） | 92% | 🟡 | 通知封面已补（§1.4）；剩余收尾见 §1.5（锁屏/耳机/降级端到端） |
| 多形态骨架（手机 / 平板 / 折叠 / 小窗 / TV / 车机） | 62% | 🟡 | `PlayerFormFactor` + 三种骨架已实测；折叠 / 小窗 / TV / 车机待实机（§1.12–1.14） |
| 手势层 | 88% | 🟢 | `utils/PlayerGestureHelper.kt`：长按倍速 / 跳章节、双击、滑动 seek、边缘亮度音量、双指缩放、锁屏屏蔽、灵敏度设置均已实现；打磨见 §1.3 |
| 无障碍 | 55% | 🟡 | 可点节点有标签与 role/selected、48dp 命中区；焦点顺序 / 大字体 / 高对比未验 |
| 测试与验收 | 10% | ⛔ | 无 UI 测试、无性能基线；见 §1.16 |

### 3.1 工作区状态（交接必读）

- 本任务改动均已提交（`git log --oneline` 里的 `feat(player):` / `fix(player):` 系列）；工作区另有更早会话留下的
  **其他领域**未提交改动（主题、首页、设置页等），**不属于本任务，别动**。
- **判断进度只看 §3 看板与 §1 任务清单**，不要用 `git status` 的改动量推断。
- 验证环境：真机小米平板 5（见 §7）；模拟器已弃用；测试服务器凭据不入库。
- 交接约定：完成一条任务 → 勾 §1 → §10 记一行 → `git commit`。

---

## 4. 已完成阶段摘要（待办已上移到 §1）

| 原阶段 | 状态 | 结果 |
|--------|------|------|
| 0 · 基线 | 🟡 | 0.1 固定 JDK 构建通过；0.2 / 0.3 / 0.4 见 §1.17 |
| 1 · 控制层 | 🟡 | 八态按钮、顶栏清晰度徽标、错误卡片（原因 / 重试 / 一键切内核）、无障碍标签完成；1.3b / 1.4 / 1.5 见 §1.10 |
| 2 · 手势锁屏 | 🟢 | 功能已完成（长按倍速 / 跳章节、双击、滑动 seek、边缘亮度音量、双指缩放、锁屏屏蔽、灵敏度设置）；剩余打磨与验收见 §1.3 |
| 3 · 面板 | 🟡 | 倍速 / 循环 / 比例 / 字幕轨 / 音轨 / 信息 / 队列 / 睡眠 / 更多 的框架已有；补全见 §1.1 / 1.2 / 1.6–1.9 |
| 4 · 系统层 | 🟡 88% | MediaSessionService + mediaPlayback 前台服务 + 自研通知（标题 / 季集 / 进度 / 5 按钮）+ PiP + 媒体按键；收尾见 §1.4 / 1.5 |
| 5 · 形态 | 🟡 | 布局条目并入阶段 8；实机验收见 §1.12–1.14 |
| 6 · 自用增强 | ⛔ | 未做，见 §1.11 |
| 7 · 验收打磨 | ⛔ | 未做，见 §1.16 |
| 8 · 多形态 | 🟡 55% | 8.1–8.6 完成（`PlayerFormFactor` 判定层 + `SplitSide` / `SplitPortrait` / `Compact` 骨架 + 命中区 + 车机不沉浸）；8.7–8.10 见 §1.12–1.15 |

改代码前值得先读的既有实现（避免重复造）：

- 控制层骨架：`PlayerControlOverlay.kt` 按 `PlayerChromeLayout` 摆顶栏 / 中央簇 / 底栏，面板统一 `ModalBottomSheet`。
- 选集 / 队列：`PlayerContentPanel.kt` 右侧内容栏（默认收起、点画面收起、底栏按钮开关），行样式 = 缩略图 + 集号 + 标题。
- 队列数据：`PlaylistManager` 出清单，`PlayerViewModel.fillQueueInBackground()` 起播后逐集补进播放器。
- 系统层：`playback/CinefinPlaybackService.kt`（MediaSessionService + 常驻 controller + 前台服务）+ `CinefinMediaNotificationProvider.kt`（自研通知）。
- 双内核：`PlayerHolder` 按偏好建实例；ExoPlayer 解码能力不足时静默降级 mpv（`PlayerEvents.FallbackToMpv`）。

### 阶段 5 · 平板 / 折叠 / 手机形态（1.5–2 天）

> 本阶段的布局类条目不单独勾选，判定层与骨架实现已由 **阶段 8** 承接（8.1–8.6 已完成），
> 剩余为实机验收（8.7–8.9）。

- [ ] 5.1 用 `WindowSizeClass` 分三档；展开态右侧固定 320dp 内容栏（选集/队列/简介，可折叠）
- [ ] 5.2 折叠 HALF_OPENED：上屏画面、下屏控制与选集（`WindowLayoutInfo`）
- [ ] 5.3 手机竖屏：底部控制 + 可上滑内容区（默认只露 56dp 手柄）
- [ ] 5.4 手机横屏：贴底进度条 + 侧边锁定
- [ ] 5.5 验收：旋转/折叠/分屏切换不重启播放、不丢进度

### 阶段 6 · 自用增强（1 天）

- [ ] 6.1 片头片尾阈值设置复核 + 提示条样式统一
- [ ] 6.2 进度记忆与服务端同步（退出、切集、被杀后恢复）
- [ ] 6.3 Trickplay 拖动预加载与失败降级
- [ ] 6.4 外挂字幕导入；字幕/音轨语言优先级设置页
- [ ] 6.5 播放结束行为（自动下一集 / 停在结束帧）

### 阶段 7 · 验收与打磨（0.5–1 天）

- [ ] 7.1 真机回归矩阵：手机竖/横、平板竖/横、折叠展开/半开、PiP、后台
- [ ] 7.2 性能：首帧 ≤1.5s、控制层显隐不掉帧、2 小时内存增长 <80MB、无 ANR
- [ ] 7.3 无障碍：TalkBack 全流程、大字体 1.3×/2.0× 不截断、高对比、键盘焦点可见
- [ ] 7.4 更新本文件的按键表与截图索引

### 阶段 8 · 多形态覆盖（D9，2 天）

> 本轮先落「判定层 + 骨架」，多形态的实机验收放到 8.7–8.10。

- [x] 8.1 `PlayerFormFactor` / `PlayerChromeLayout` 判定层（窗口宽度档位 + 折叠姿势 + `UiModeManager` + 多窗口）
- [x] 8.2 平板 / 折叠展开 `SplitSide`：右侧 320dp 内容栏（选集 / 队列），可收起；收起后画面区回到全宽（模拟器实测：TextureView `[0,0][2560,1600]`）
- [x] 8.3 手机竖屏 `SplitPortrait`：画面区 `max(16:9, 42% 窗口高)`，下方页签 + 横滑选集卡片（模拟器实测：TextureView `[0,0][1080,982]`，内容区从 y≈1030 起）
- [x] 8.4 小窗 `Compact`：单行控制条（播放暂停 / 标题 / 时间 / 更多）+ 细进度条；「更多」聚合面板
- [x] 8.5 车机不沉浸（系统栏保持可见）+ 车机 / TV 锁横屏；手机 / 平板 `fullSensor` 自由旋转
- [x] 8.6 触摸命中区随骨架变化：`PlayerOverlayContainer` 按画面区划带，画面区之外的常驻内容区整块接管
- [ ] 8.7 折叠半开 `FoldHalfOpen` 实测（水平折痕：上屏画面、下屏控制；当前无折叠设备，待真机）
- [ ] 8.8 小窗 / 分屏实测（模拟器需开启 freeform 支持）
- [ ] 8.9 TV / 车机实机验收（10-foot 焦点走查、车机 64dp 命中区）
- [ ] 8.10 PiP 系统三键与细进度条（依赖阶段 4 的 `MediaSessionService`）

---

## 5. 界面规格（实现基准 · 参考用）

> 设计基准，**核心部分已实现**。日常开发按 §1 任务清单走，改到具体部件时再按需查阅本节。

### 5.1 分区与按键

```
竖屏
┌───────────────────────────────────────────┐
│ ← 标题 · S01E03 · 1080P        ◫  🔒  ⋮   │  顶部（安全区内缩）
│              ⏮  ↺10  ▶/⏸  ↻10  ⏭          │  中央簇（3–5s 淡出）
│         [缓冲圈 / 错误重试 / 跳过片头]      │
│  ─────────●──────────────  12:31 / 24:05  │  进度条（视觉 4dp / 命中 32dp）
│  ⏮ ↺10 ▶ ⏭ ↻10  1.0× 💬 🎧 ▭ ☰ 🔒        │  底部控制栏
├───────────────────────────────────────────┤
│ ︿ 选集 / 简介 / 队列（上滑展开）            │
└───────────────────────────────────────────┘

横屏
← 标题 S01E03 1080P                                  🔒
                    ⏮ ↺10 ▶/⏸ ↻10 ⏭
─ ─ ─ ─ ─ ─ ─ ─ ─●─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─
⏮ ↺10 ▶ ⏭ ↻10  1.0× 💬 🎧 ▭ ☰

平板 / 折叠展开：视频画面 + 右侧 320dp 固定内容栏（选集/队列/简介，可折叠）
折叠半开：上屏画面、下屏控制与选集
PiP：画面 + 系统三键（播放暂停 / 上一集 / 下一集）+ 细进度条
```

- 顶部：返回、标题/副标题、集数、清晰度、画中画、锁屏、更多。
- 中央：上一集、快退 10s、播放/暂停、快进 10s、下一集、缓冲圈、加载细线、错误重试、跳过片头片尾、手势 HUD。
- 底部：进度条（缓冲段 + 章节刻度 + Trickplay 预览）、当前/总时长/剩余时间、播放暂停、上下集、快退快进、倍速、字幕、音轨、画面、队列、循环、锁屏。
- 侧边：左亮度、右音量、锁屏/解锁。

### 5.2 手势

| 手势 | 行为 | 备注 |
|------|------|------|
| 单击 | 显示/隐藏控制层 | 3–5s 无操作淡出；暂停时不隐藏 |
| 双击左 1/5 · 中 1/5 · 右 2/5 | 快退 · 播放暂停 · 快进 | 带分区反馈 + 触觉 |
| 水平滑动 | 进度预览 + 松手跳转 | Trickplay 缩略图，拖动 0ms 跟手 |
| 左/右缘竖滑 | 亮度 / 音量 | 仅边缘 25% 生效 |
| 长按 | 临时倍速（默认 2×） | 松手复位，可配置为跳章节 |
| 双指缩放 | 填满 / 还原 | 结果写回画面比例 |
| 下拉锁屏 / 上滑解锁 | 防误触 | 锁后只留解锁钮 |
| 边缘返回 | 收面板 → 收控制层 → 退出 | 逐级回退 |

自动隐藏：播放中 3s（可配 3/5/10/永不）；面板打开、拖动进度、长按期间不计时。
安全区：统一读 `displayCutout + systemBars`。
按钮八态：默认 / 按下 / 聚焦 / 选中 / 禁用 / 加载 / 激活 / 错误。

### 5.3 状态机

```
播放态
Idle ──► Loading ──► Buffering ──► Playing ⇄ Paused ──► Ended
           └─失败─► Error ──► Retrying（指数退避 3 次）
                        └─ 解码失败 ─► 软解 ─► 仍失败 ─► 提示切内核（mpv）

控制层态
Hidden ──单击/双击/遥控──► Visible ──3~5s 无操作──► Hidden
Visible ──锁按钮──► Locked ──双击/上滑──► Visible
Visible ──面板入口──► PanelOpen ──返回/下划──► Visible

面板态：PlayerPanel 单值枚举（None/Speed/Subtitle/Audio/Aspect/Info/Queue/Repeat/Sleep/Settings），至多一个非 None
```

并存附加态：Offline、Background、Pip、HeadsetUnplugged、CallInterrupted、SleepTimer、Locked。

### 5.4 组件树

```
PlayerActivity（单 Activity，edge-to-edge，PiP 宿主）
└─ CinefinTheme
   └─ PlayerScreen
      ├─ PlayerSurface                // AndroidView：ExoPlayer PlayerView / mpv SurfaceView
      │   └─ SubtitleOverlay          // Media3 / libass 字幕
      ├─ PlayerGestureLayer           // 手势挂载点（PlayerGestureHelper），不含 UI
      ├─ PlayerOverlayContainer       // 三块触摸区托管（顶 76dp / 中 520×180dp / 底 210dp）
      │   └─ PlayerControlOverlay     // 主改动点
      │      ├─ PlayerTopBar          // 返回/标题/清晰度/PiP/锁/更多
      │      ├─ PlayerCenterControls  // 上下集/±10s/播放暂停/缓冲/错误卡片
      │      ├─ PlayerSeekBar         // 缓冲段 + 章节刻度 + Trickplay + 拖动手柄
      │      ├─ PlayerBottomBar       // 时间/倍速/字幕/音轨/画面/队列/睡眠/锁
      │      ├─ SkipSegmentChip       // 跳过片头片尾
      │      ├─ GestureHud            // 亮度/音量/进度/倍速提示
      │      └─ LockedOverlay         // 锁屏层
      ├─ PlayerPanelHost              // ModalBottomSheet，单开
      └─ PlayerDebugOverlay           // 长按标题唤出（阶段 0 新增，自用排障）

系统层：CinefinPlaybackService : MediaSessionService、MediaNotification.Provider、
        AudioFocus / Headset / Call 接收器、PictureInPictureParams
数据层：PlayerViewModel、PlaylistManager、TrackSelectionEngine、媒体片段仓库
```

### 5.5 多形态规格（D9 · 阶段 8 落地）

原则：**不为每个形态做一套界面**，而是「一套原子组件 + 六种骨架」。
骨架由 `PlayerFormFactor` / `PlayerChromeLayout` 单点判定，组件不得自己读窗口尺寸。

#### 5.5.1 形态判定矩阵

| 形态 | 判定输入 | 典型窗口 | 骨架 |
|------|----------|----------|------|
| Phone | `widthSizeClass == Compact` 且无折叠/TV/车机特征 | < 600dp | 竖屏 `SplitPortrait` / 横屏 `Fullscreen` |
| Tablet | `widthSizeClass == Expanded` 且非折叠 | ≥ 840dp | `SplitSide` |
| Foldable(展开) | `FoldingFeature.state == FLAT` | 600–900dp | `SplitSide`（Media 姿势） |
| Foldable(半开) | `FoldingFeature.state == HALF_OPENED` | 任意 | 水平折痕（桌面姿势，上下两半）→ `FoldHalfOpen`；垂直折痕（书本姿势，左右两半）→ `SplitSide` |
| Tv | `FEATURE_LEANBACK` 或 `UiMode == TELEVISION` | 1080p 10-foot | `Fullscreen(Tv)` |
| Car | `FEATURE_AUTOMOTIVE` 或 `UiMode == CAR` | 横向大屏 | `Fullscreen(Car)`：不沉浸、常驻控件、64dp 命中区 |
| Freeform | `isInMultiWindowMode && windowWidth < 75% screenWidth` | 任意 | `Compact` |
| Pip | `isInPictureInPictureMode` | 系统窗口 | `Pip` |

优先级：`Pip > Freeform > FoldHalfOpen > Tv > Car > Foldable > Tablet > Phone`（前面的命中即返回）。
每项判定都要有降级路径：拿不到 `FoldingFeature` 就按宽度档位走，拿不到 `UiMode` 就按 feature 走。

#### 5.5.2 骨架线框（已实现，从略）

`SplitSide`：左画面 + 右侧 320dp 内容栏（默认收起）。
`SplitPortrait`：上画面（`max(16:9, 42% 窗口高)`）+ 下方页签与横滑选集卡片。
`FoldHalfOpen`：折痕上画面、下屏控制行 + 选集。
`Compact`：单行控制条（播放暂停 / 标题 / 时间 / 更多）+ 细进度条。
`Pip`：系统三键 + 细进度条。

#### 5.5.3 组件 × 骨架映射（谁出现在哪里）

| 组件 | Fullscreen | SplitSide | SplitPortrait | FoldHalfOpen | Compact | Pip |
|------|-----------|-----------|---------------|--------------|---------|-----|
| 顶栏 | 覆盖 | 视频区覆盖 | 视频区覆盖（标题单行） | 下屏顶部 | 并入单行 | — |
| 中央簇 | 覆盖 | 视频区覆盖 | 视频区覆盖 | 下屏 | 并入单行 ▶ | — |
| 进度条 | 底部 | 视频区底部 | 视频区底部 | 下屏首行 | 单行 | 细进度 |
| 工具行 | 底部 | 视频区底部 | 视频区底部 | 下屏第二行 | ⋮ 溢出 | — |
| 内容栏 | 面板 | 侧栏常驻 | 下方 Tab | 下方横滑 | 面板 | — |
| 手势 | 全套 | 全套 | 仅视频区内全套 | 上屏画面 | 仅单击 | 系统 |

#### 5.5.4 视觉与动效基准（沿用现有设计语言，不新增色彩）

- 颜色锁：唯一强调色朱砂 `#D2553C`；中性色只用墨系 `#0B0C0E / #0F1114 / #131518 / #17191D / #24282C`；文字纸白 `#F1EDE6`、雾灰 `#A8A49C`。
- 字阶：标题 15sp / 正文 13sp / 徽标 11sp；时间码用等宽数字（`FontFeatureSetting "tnum"`），避免跳动。
- 形状锁：按钮胶囊 999dp、缩略图 12dp、面板 16dp、进度条视觉 2–4dp、命中区 ≥48dp（车机 64dp）。
- 动效：显隐 `180ms cubic-bezier(0.32,0.72,0,1)`；侧栏/内容区展开 `220ms` 同曲线；面板 `240ms` spring；不做回弹过冲，不用 linear。
- 遮罩：`ScrimTop` 76dp / `ScrimBottom` 210dp 统一资源，任何形态不得就地写第二套渐变。
- 触觉：双击跳转、锁屏/解锁、跳过片头、折叠形态切换各一次 `HapticFeedback`，不做连续震动。
- 无障碍：每个图标按钮 `contentDescription + role=Button + selected`；大字体 2.0× 不截断（标题 2 行封顶，工具行可横滑）。

#### 5.5.5 系统集成矩阵

| 能力 | 手机/平板/折叠 | 小窗 | PiP | TV | 车机 |
|------|----------------|------|-----|----|------|
| 通知栏全套（D4） | ✓ | ✓ | ✓ | ✓ | ✓ 精简 |
| 后台音频 | 设置开关 | — | ✓ | ✓ | ✓ |
| 屏幕常亮 | 播放中 | 播放中 | — | 播放中 | 播放中 |
| 音频焦点/来电/拔耳机 | ✓ | ✓ | ✓ | ✓ | ✓ |
| 方向 | 竖横自适应 | 跟随宿主 | 跟随视频 | 横屏 | 锁定横屏 |
| 焦点导航 | 外接键盘 | — | — | D-pad | 旋钮/方向键 |

背景回放与画面策略：进后台 → 若「后台音频」开启则只保音频（画面 TextureView 释放由 Media3 处理）；
PiP → 画面继续、控制层隐藏；车机 → 不退后台，保持前台常驻。

---

## 6. 验收标准

**功能**：平板与手机竖横屏、PiP、后台下播放/暂停/±10s/上下集均可用；倍速三入口一致；字幕语言退出重进仍生效、延迟即时、双语不重叠；进度条拖动跟手（<16ms）、松手 300ms 内画面追上；手势互不冲突；锁屏后触摸不改变播放状态；断网 3s 内出重试、解码失败自动降级并显示内核；锁屏 30 分钟音频不断且通知栏可控。

**交互**：控制层 3s 淡出、触摸立即出现不闪烁、面板打开时不消失；遥控/键盘焦点始终可见；按钮 ≥48dp、TalkBack 可读、大字体 2.0× 不截断。

**性能**：首帧 ≤1.5s（局域网）、切分辨率 ≤2s；控制层显隐不掉帧、额外耗电 <3%；连续播放 2 小时内存增长 <80MB、无 ANR。

---

## 7. 环境与命令

| 项 | 值 |
|----|----|
| JDK | `D:\Android\Android Studio\jbr`（OpenJDK 25）**必须**，工程按 Java 21 编译 |
| Android SDK | `D:\Android\AndroidSDK`（platforms 36/37，build-tools 36/37） |
| Gradle | 工程自带 wrapper 9.7.1 |
| 版本矩阵 | AGP 9.4.1 / Kotlin 2.4.20 / KSP 2.3.12 / ktfmt 0.27 `kotlinLangStyle` |
| SDK 配置 | compileSdk 37 / targetSdk 36 / minSdk 28 / buildTools 37.0.0（`buildSrc/.../Versions.kt`） |
| 关键依赖 | Compose 1.12.1 + material3 1.4.0；Media3 1.11.1（+ jellyfin ffmpeg-decoder 1.9.0+1）；libmpv 1.0.0（compileOnly）；Jellyfin SDK 1.8.12 |
| **真机（首选）** | 小米平板 5 `nabu` / 型号 21051182C，1600×2560 / 360dpi / Android 13（API 33）。模拟器太卡，2026-09-28 起调试以真机为准 |
| 模拟器（备用） | AVD `CinefinTablet`（2560×1600 / 320dpi / API 36），无硬件加速；仅在真机不在时兜底 |
| 测试服务器 | `https://jellyfins.zhangwenkang.com`（Jellyfin 10.11.8，只读！禁止写/删） |

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
cd E:\codex_work\Android_Studio_Work_Space\Cinefin
.\gradlew.bat :app:phone:assembleDebug --console=plain
.\gradlew.bat :app:phone:installDebug
.\gradlew.bat ktfmtFormat ; .\gradlew.bat :app:phone:lintDebug
adb shell dumpsys media_session | Select-String cinefin   # 校验媒体会话
adb logcat -s CinefinPlayer:V ExoPlayerImpl:V             # 播放排障
```

构建产物：`app/phone/build/outputs/apk/libre/debug/`（4 个 ABI，单包 85–95 MB debug）。

真机调试（小米平板 5 / Android 13）约定：

```powershell
adb install -r app\phone\build\outputs\apk\libre\debug\phone-libre-arm64-v8a-debug.apk
# 播放页在 debug 包里 exported=true（src/debug/AndroidManifest.xml 覆盖），可以直接带条目启动：
adb shell am start -n com.zhangwenkang.cinefin.debug/com.zhangwenkang.cinefin.PlayerActivity `
  --es itemId "<UUID>" --es itemKind "Episode"
# 读真机偏好（SharedPreferences 是明文，可临时改内核等开关）
adb shell run-as com.zhangwenkang.cinefin.debug cat shared_prefs/com.zhangwenkang.cinefin.debug_preferences.xml
```

---

## 8. 风险

| 风险 | 影响 | 对策 |
|------|------|------|
| mpv 与 ExoPlayer 行为差异 | 字幕/比例/倍速表现不同 | 统一 `PlayerIntent` 抽象，差异在设置里显式标注 |
| 清晰度档位取决于服务器转码 | 菜单可能为空 | 只暴露 `PlaybackInfo` 实际返回的档位 |
| 后台播放缺前台服务被回收 | 长时后台中断 | 阶段 4 优先落地 `MediaSessionService` |
| 模拟器无硬件加速 | 无法模拟器走查 | 用真机；TV 相关的 D-pad 暂不投入 |
| 工作区有大量未提交改动 | 与既有改动冲突 | 每阶段前 `git status` 确认，按文件小步提交 |

---

## 9. 踩坑库与权威文件索引

**改代码前先扫一遍这张表**——都是已经踩过的坑，重复踩会浪费一整轮。

| 坑 | 结论 / 对策 |
|----|------------|
| 播放器实例放哪 | 必须是进程级单例 `PlayerHolder`（`player/local`），不能放 ViewModel：通知栏 / 后台播放要求 Activity 销毁后实例还在 |
| Media3 默认媒体通知不显示 | `MediaNotificationManager.shouldShowNotification` 要求会话**有 MediaController 连接**；播放页直接操作共享实例 → 由服务自连接常驻 controller + 自研 provider |
| Android 14 `startForeground` 崩溃 | 通知渠道必须自建，否则 `Bad notification for startForeground` 直接杀进程（见 `CinefinMediaNotificationProvider.ensureChannel()`） |
| `NotificationCompat.Builder` 没有 `setSubtitle` | 通知副标题用 `setContentText` |
| `CommandButton.Builder` | media3 1.11 可用构造是 `Builder(Int iconRes)`，`Builder(IconCompat)` 不存在 |
| 队列只显示当前一集 | `PlaylistManager` 已有整剧清单，但 `initializePlayer` 只把当前集交给播放器 → 现由 `fillQueueInBackground()` 起播后逐集补全 |
| `MPVPlayer.addMediaItems` 越界崩溃 | BasePlayer 封装可能传 `Int.MAX_VALUE`，下标必须收敛到 `[0, size]` |
| `MPVPlayer.getMediaMetadata()` 返回空 | 通知 / 锁屏拿不到标题 → 改为返回当前媒体项元数据 |
| mpv 释放后崩溃 | `release()` 后 TextureView detach 仍回调 `detachTextureSurface` 去碰已销毁的 `mpvLib`；现在先解绑 surface 再置 `released` 标志，所有 surface 回调短路 |
| 10-bit H.264 播不了 | ExoPlayer 报 `NO_EXCEEDS_CAPABILITIES` → 已静默降级 mpv（`PlayerEvents.FallbackToMpv`） |
| mpv 在 PiP 下 `position=-1` | 已知问题，待修（不影响播放） |
| 视频输出用 TextureView | `activity_player.xml` 里 `surface_type="texture_view"`：SurfaceView 会被 Compose 控制层盖黑 |
| 触摸与手势 | `PlayerOverlayContainer` 只把「画面区内顶 / 中 / 底三带」与「画面区之外的常驻内容区」交给 Compose，其余放行给 `PlayerGestureHelper`；改布局必须同步命中区 |
| 真机 adb 进播放页 | 主清单 `PlayerActivity exported=false`；`src/debug/AndroidManifest.xml` 覆盖为 true，仅 debug 包可 `am start` |
| 服务器偶发超时 | `jellyfins.zhangwenkang.com` 只读；视频流偶发 `SocketTimeoutException`（ping 正常），重试即可，不是客户端 bug |
| Android 正则不支持 `\{` 转义 | ICU 引擎对 `\{` 抛 `PatternSyntaxException`（字幕解析器静态初始化时崩过一次，整页闪退）；用字符类 `[{]` 代替。字幕下载 + 解析整体套 `runCatching`，解析失败只丢字幕不带崩播放页 |
| 自管字幕与内核字幕打架 | 自管接管时**必须**把 `setTrackTypeDisabled(TEXT, true)` 与语言引擎的参数合成一次算完；先选轨再禁用会让参数来回变化，`onTracksChanged` 死循环刷日志（`automatic 选轨` 每秒几十条） |
| 同一媒体不同集的字幕序号会重复 | 字幕解析缓存 key 必须带媒体 id（`mediaId:index`），只按 index 会串集 |
| mpv 侧字幕条目的 id | 是 mpv 的 track id（`Format.id`），不是列表下标；`secondary-sid` 也必须用它 |
| Media3 没有音频偏移 API | 音轨延迟要自己写 `AudioProcessor`：正延迟插静音帧、负延迟丢帧，插到 `DefaultAudioSink` 的处理器链上（覆写 `DefaultRenderersFactory.buildAudioSink`） |
| `BaseAudioProcessor` 的 `isActive` 陷阱 | 基类只在「有待消费输出」时算活跃，靠默认实现会让处理器被管线旁路、延迟只在第一个 buffer 生效；必须覆写成「配置完成后一直活跃」 |
| 音频 offload 会让处理链失效 | offload 把压缩音频直通硬件，PCM 处理器收不到数据（延迟静默失效）；`CinefinRenderersFactory` 里显式 `DEFAULT_UNSUPPORTED` 关掉 |
| mpv `audio-delay` 符号 | 正值 = 声音延后（与面板「+ = 声音晚」一致），真机听感确认过；不要凭「delay 是不是补提前量」的直觉想当然 |
| mpv 语言优先列表 | `alang` / `slang` 要传**整份**逗号分隔列表（`zh-Hans,zh-Hant,zh,en`）。旧代码 `firstOrNull().split("-").last()` 把 `zh-Hans` 截成 `Hans`，任何轨道都匹配不上（真机表现：打开新片自动选到日语字幕）；mpv 自己会做 ISO 639-1/639-2 与地区后缀归一化 |
| 自动选字幕兜底 | `PlayerSubtitleController.pickPrimary()` 在「语言优先级没命中」时按「默认轨 > 非强制字幕 > 轨道序号」兜底，别再直接返回 null（旧行为 = 打开新片没字幕）。注意 `TrackSelectionEngine.pickTextTrack()` 仍是「auto 不选」，两处语义待统一（见 §10 遗留） |
| 打开即播与 pause/resume | `initializePlayer` 发出后、`play()` 落地前是**起播窗口**：播放器 `playWhenReady` 还是默认 false，此时 pause/resume（权限弹窗 / 切后台 / 切内核）会把 false 回存再写回、覆盖自动起播 → 打开视频要手点一次。窗口内一律不回存 / 不恢复（`PlayerViewModel.startupInProgress`） |
| mpv 队列补片压主线程（ANR） | `fillQueueInBackground` 旧实现只在 IO 构建 PlayerItem，`player.addMediaItem` 仍在主线程；mpv 的 `addMediaItems` 同步执行 `mpvLib.command("loadfile")`，整季补片把主线程连续阻塞（dropbox：`Waited 5000ms for MotionEvent`，栈 `MPVPlayer.addMediaItems ← PlayerViewModel.addToQueueEnd ← fillQueueInBackground`）。修复：MPVPlayer 加 `HandlerThread("mpv-command")`，所有 command 按提交顺序异步执行；补片协程整体跑 IO、每条让出 50ms、单次上限 150 条、mediaId 集合 O(1) 去重。补片+点击场景主线程峰值 2584ms → 508ms |
| mpv 命令异步化后的释放协议 | `release()` 不能直接 `destroy`（会与命令线程正在执行的命令并发打崩 native）。做法：`commandsClosed` 关闸 → `removeCallbacksAndMessages(null)` 丢排队命令 → 在命令线程上 `destroy` + `quitSafely`；`event()` / `onAudioFocusChange` 增加 `released` 短路；surface attach/detach 加主线程断言（命令线程只跑 command） |
| 视频帧节奏怎么量 | TextureView 视频不走 `dumpsys gfxinfo`（实测 Total frames=0）；用 `SurfaceFlinger --latency <layer>` 采样，`INT64_MAX` 是 pending 必须剔除；同一帧会被重复 present，要同时看 `ready_*`（frameReady 去重）与 `present_*`。Pad 5 屏幕 120Hz、片源 24fps 时正常 p50≈41.7ms，p99>100ms 才是可感知停顿。补片窗口的帧数据会被服务器 PlaybackInfo 变慢（本轮 0.4→3.8s 漂移）污染，对比时优先用主线程指标（PerfMonitor latency） |
| mpv 参数与卡顿定位结论 | 默认 `hwdec=mediacodec` / `vo=gpu-next` / cache 64+32MiB 下稳态 24fps 满帧、无 >100ms 停顿；参数不是可复现卡顿源，卡顿集中在补片窗口的主线程阻塞与服务器 / 网络缓冲。低内存 swap thrashing（02:07 现场）未在本次测量复现，作为后续观察项 |
| 播放页面板抽屉化（§11 C） | 抽屉宿主必须挂在画面区根 `Box` 内（`Modifier.align` 需要 BoxScope；放在 Box 外要么编译不过、要么盖不住画面）；退场内容用 `lastPanel` 兜底，避免滑走的是一块空板；「点外部关闭」的捕获层垫在抽屉之下，抽屉本体用 `pointerInput { detectTapGestures {} }` 吞空白点击——用 `clickable {}` 会给整块面板叠一个按钮语义（TalkBack 噪音）；打开期间命中区继续由 `PlayerOverlayContainer.panelOpen` 全屏接管，不随抽屉宽度变化 |
| 覆盖层底栏加文字标签（§11 B） | 工具键从纯图标改为「图标 + 文字」后底栏变高：竖屏底带 100→150dp、小窗单行条 72→92dp；`PlayerOverlayContainer` 命中带必须同步，否则进度条上半截点不到、按钮触摸漏给手势层——以后改底栏高度都要对照命中带一起改。命中带也不能给过松：竖屏顶带按顶栏实高（8+48+8=64dp）取，超过会把「点画面显隐控制层」的可落区压没（Pad 5 走查实测 72dp 时只剩 ~30px） |
| 顶栏尾部控件被挤到中间（weight 陷阱） | `标题 weight(1f, fill=false)` + 尾部 `Spacer(weight(1f))` 的组合在 Pad 5 横屏下**不会**把末尾的锁推到右端（实测锁停在顶栏约 63% 处）；尾部要贴边的控件，用「标题 `weight(1f)` 吃满剩余空间」而不是再放一个 weighted Spacer。同类权重组合改完必须真机（或 uiautomator bounds）复核坐标，别只看截图缩略图 |
| 信息面板的媒体源元数据走哪条路 | `onMediaItemTransition` 命中 `items` 时会**直接**更新 `uiState`（不走 `refreshUiStateFromPlayer()`），只把元数据塞进 MediaItem extras 会出现「面板全空、只有内核字段」。结论：`PlayerItem.mediaInfo` 在 transition 分支里同步写进 `UiState.currentMediaInfo`，extras 只作为「从通知回到播放页」兜底路径 |
| Jellyfin 播放地址没有后缀 | `/Videos/<id>/stream?static=true…` 的「扩展名」是 `stream`，容器名猜不出来；要回退到媒体源文件名（`FindroidSource.name`），mpv 侧还有 `file-format` 可用。ExoPlayer 拿不到容器时显示「—」（契约允许） |
| mpv 的 `video-bitrate` 不保证随时可读 | 同一集不同时刻读出来可能是 null（面板显示「—」）也可能是 6.4 Mbps；这是 mpv 属性本身的可用性差异，不是解析 bug，别为它加轮询 |
| 队列排序后 UI 不刷新 | 控制层的 `PlayerRuntime.sync` 原先只在 `mediaItemCount` 变化时重建队列列表，拖拽排序（数量不变）不会刷新；改成比较 **mediaId 序列**（O(n)，n ≤ 150）再重建 |
| 返回键关不掉子面板（W11 真机踩到） | 子面板的「上一级」标记（`panelBackTarget`）在返回后没清掉：`BackHandler` 每次都判定成「回上一级」，于是第二次按返回又回到同一个子面板，一级面板永远关不上。修复：回上一级 / 关面板 / 点抽屉外空白三条路径都要把标记清空 |
| 窄窗里中央簇与锁定键叠在一起（W11 真机踩到） | 锁定键固定在画面区右缘垂直居中，中央簇居中排布，两者都吃中部空间；`wm 800x2400`（305dp）实测中央簇右端与锁定键重叠 26dp。修复：中央簇按画面区宽度收放（302 / 266 / 240 / 160dp 四档，`playerCenterSpec` + 单测保证「簇宽 ≤ 宽 − 2×(48+12)」），命中块改用实测尺寸（`onCenterClusterSize`） |
| 季卡「一大一小」的根因 | `ItemCard` 根节点只有 `fillMaxWidth()`，而 `LazyRow` 的宽度约束是无界的 → 每张卡按各自海报的固有尺寸排版；另外标题 1 行 / 2 行也会让卡高差一整行。修复：新增可选 `width`（季列表传 `rememberSeasonCardWidth()` 分档固定值）+ 标题块固定预留两行高度 |
| 「清空队列」不能直接 `clearMediaItems()` | 那会把正在播放的条目一起删掉并停播；正确做法是先删当前条目前的、再删其后的，只保留当前条目（真机实测：清空后仍在播、队列剩 1 项） |
| 手势总开关关掉后的死角 | 只关「手势」不能把「单击显隐控制层」也关掉，否则用户收起控制层后再也唤不出控件（真机走查踩到）。实现：`gesturesEnabled=false` 时仍跑一个只含 `onSingleTapConfirmed` 的轻量检测器 |
| 开关行只有小圆钮可点 | Material3 `Switch` 命中区只有 ~52×32dp；面板里的「开关行」必须整行可点（`Row.clickable(enabled)`），否则点标签没反应（真机走查踩到） |
| 播放页 UI 走查的点击时序 | 控制层 3.5 秒自动淡出 + `uiautomator dump` 要 3–4 秒：照 dump 出来的坐标点按经常点空。稳定做法是先发 `KEYCODE_MEDIA_PAUSE`（暂停后控制层不淡出）再点；脚本见 `tools/w9-player/Device-Ui.ps1` |
| 竖屏（SplitPortrait）下方内容区不响应「单击显隐控制层」 | `PlayerOverlayContainer` 把画面区之外的常驻内容区整块交给 Compose（点画面区才 toggle）。走查时「点击视频区中心」= 视频区高度以内（竖屏 ≈ `max(16:9, 42% 窗口高)` 的像素值），别点到下方选集区 |
| Media3 的 `RESIZE_MODE_ZOOM` 是空操作 | `PlayerView` 会把内容框（`exo_content_frame`）按视频比例收窄，ZOOM 在「内容框 = 视频比例」时没有任何视觉效果——W10 真机实测 Pad 5 上适应 / 裁剪 / 拉伸三档截图**逐像素相同**（用户反馈「比例无效」的真因之一）。对策：「裁剪填满」= 内容框保持视频比例（走 FIT）+ 等比视图缩放 `max(画面区比例/视频比例, 视频比例/画面区比例)`；裁剪也统一改成纯视图缩放，不再依赖容器宽高比 |
| mpv 不读 `PlayerView.resizeMode` | mpv 自己渲染到 TextureView（AspectRatioFrameLayout 拿不到视频尺寸时内容框就是整屏），比例必须写 mpv 属性：适应 = `keepaspect=yes` + `panscan=0`、裁剪填满 = `panscan=1`、拉伸填满 = `keepaspect=no`。`setPropertyString` **不返回错误码**（`setOptionString` 返回），所以两个都写、用 `getPropertyString` 回读值打日志做证据；`panscan` 的旧值残留会让画面一直放大铺满，切换档位 / 内核 / 退出重进都要重放一次 |
| `uiautomator` 读不出面板 chip 的选中态 | `PanelChip` / `PanelRow` 的选中只体现在底色与描边上，dump 里 `selected=false`（没有 `selectable` 语义）。验收需要「默认选中项」证据时，改用像素采样：选中 chip 的底 = 极光青容器合成色（Pad 5/K60 实测 ≈ `(54,122,118)`），未选中 = 面板底（`(17,19,25)`） |

| 常驻侧栏会「挤压 / 右移画面」（W12 真机定论） | 平板侧栏一旦缩窄 `PlayerView`（`applyVideoArea` 按 `sidePanelExpanded` 改宽度），用户的感受就是「打开选集画面被挤走」——本轮改成**覆盖层**：画面区恒为整窗宽，面板盖右缘 320dp + 左缘 1dp 结构线。命中区必须单独补一条（`PlayerOverlayContainer.sidePanelOpen` / `sidePanelWidthPx`），否则面板上的点按会穿透到手势层 |
| ass-kt 与 libmpv 的 `libc++_shared.so` 冲突（W16） | 两个 AAR 各带一份同名 `libc++_shared.so`，AGP 9 直接报 duplicate；`packaging.jniLibs.pickFirsts` 去重后**固定**取 ass-kt 的旧版（与声明顺序无关，实测 0.3.0–0.5.1 全是同一份旧 libc++），libmpv 缺 `__from_chars_floating_point` → 真机 `UnsatisfiedLinkError: dlopen failed`。修法：app 模块在 `merge*NativeLibs` 的 `doLast` 用 **libmpv AAR 里的新版覆盖**合并结果（逐个 ABI），校验 APK 内 `lib/arm64-v8a/libc++_shared.so` 的 sha256/大小（1374336 = libmpv 版）。改完 mpv 与 libass 两条路径都要真机回归 |
| libass 对「同一时间戳」有帧缓存（W16） | 暂停画面下改字号 / 转屏后只调 `ass_set_font_scale` / `ass_set_frame_size` 不会让旧帧失效：libass 命中缓存直接回旧图（`changed=0`），表现为「面板改了、画面不动」。修法：`LibassSubtitleRenderer.load()` 只要脚本 / 字号 / storage / frame 任一变化就**释放并重建轨道 + 渲染器**再重新 `readBuffer`（几十 KB 脚本，毫秒级） |
| libass 字体来源（W16） | 原生库用 fontconfig provider（`ass_set_fonts(..., "sans-serif", FONTCONFIG, ...)`），字体来自系统 `/system/fonts`：脚本里的 `方正准圆_GBK` 真机回退到 MiSans（日志 `fontselect: ... -> /system/fonts/MiSansVF.ttf`）。MKV 内嵌字体（attachment）在服务器转码场景 Jellyfin 不交付，同样走系统字体回退——与 mpv 路径一致，属接受差异 |
| 回退链「只改标记、没换内核」（W17） | `PlayerHolder.player` getter 会按 `appPreferences.playerBackend` **原地重建实例**：第 3 档若先写 backend=mpv 偏好再触发 `switchBackend()` toggle，此时 `PlayerHolder.backend` 已被重建改成 mpv，toggle 判定「当前是 mpv → 切 ExoPlayer」，把内核又写回 ExoPlayer（真机表现 = Exo 硬解失败后没有重新切换 mpv 软解）。修法：回退动作**显式传目标内核**（`setBackend(mpv)` + recreate），先读续播位置再写偏好；防死循环交给 `pref_player_decode_fallback_stage` 状态机，不要再加「同一媒体只降级一次」的拦截（它会把第 2 档失败卡死在 Exo） |
| 选集覆盖层遮挡底栏右端控件（W17） | 平板 / 折叠展开的选集栏是覆盖在画面右缘的 320dp 面板，绘制在控制层之上；底栏原先铺满整窗 → 右下角全屏键落在面板之下，点击被面板吃掉（`player fullscreen=` 无日志）。修法：侧栏展开时底栏 `Modifier.padding(end = sidePanelWidthDp)` 整体让位，全屏键落到面板左侧；任何「右下角新增控件」都要先确认侧栏展开态是否被盖住 |
| 控制层淡出后的第一击只唤出（W17 走查） | 控制层 3.5 秒自动淡出后，点按钮位置的第一下由手势层接管、只负责唤出控制层（视觉上按钮刚淡出又出现，像「点了没反应」）。真机验收要点：先 `KEYCODE_MEDIA_PAUSE`（暂停不淡出）或先点画面唤出，再点按钮；否则会把「首击唤出」误判成「按钮命中失效」 |
| M3 `clickable` 的最小触控 ≠ 视觉框 | Compose Material3 会把可点节点扩到最小 48dp：`uiautomator` 读到的 bounds 是**触摸框**（48dp），不是画出来的键框（本轮宽屏 44dp / 窄屏 38dp）。验收「控件缩小」要用截图量描边位置（Pad 5 实测视觉框 ≈ 40–44dp、触摸框 108px = 48dp） |
| 只发 `maxStreamingBitrate` 不会转码（W12 真机踩到） | `DeviceProfile.transcodingProfiles = emptyList()` 时服务器认为「这个客户端不会播转码流」，于是无视码率上限继续 DirectPlay（Pad 5 实测 3 Mbps 档 `PlayMethod=DirectPlay`、`TranscodingInfo=null`）。补上 `TranscodingProfile(ts + HLS + h264 + aac/mp3/ac3/opus)` 后会话才出现 `TranscodingInfo{IsVideoDirect=False, Bitrate=2808000, TranscodeReasons=ContainerBitrateExceedsLimit}`，App 播放 `master.m3u8` |
| Jellyfin 会话 `PlayMethod` 可能滞后 | 同一时刻 `/Sessions` 可能给出 `PlayState.PlayMethod=DirectPlay` 而 `TranscodingInfo` 明确是转码（本轮实测）。判断「服务器是否转码」只看 `TranscodingInfo`（`IsVideoDirect` / `Bitrate` / `TranscodeReasons`） |
| 「手动切内核 = 关掉回退链」（W18 用户实测） | 手动切内核的**每一条入口**都必须 `clearDecodeFallback()`：解码面板（`restartWithBackend`）清了，但错误卡片「改用 X 内核」走 `switchBackendAndRestart` **没清**——残留档位会让下一次失败直接命中末档（不降级、只弹错误卡片）或跳过服务器转码。同时手动路径必须**先读续播位置、再写 `playerBackend` 偏好**：`PlayerHolder.player` 的 getter 会按新偏好就地重建实例，写后再读位置必是 0。现在两条路径明确分开：手动 = `switchBackendAndRestart`（清档位），回退链 = `switchBackendForFallback`（保留档位、显式目标内核） |
| 无缓冲 Channel + `trySend` 会静默丢事件（W18） | `Channel<PlayerEvents>()`（rendezvous）上 `trySend` 只在「接收方此刻正挂起」时成功。回退事件是在 `onPlayerError`（主线程）里发的，主线程一忙（重组 / 正在处理上一条事件）事件就被丢弃，而 `handleCodecFallback` 已经返回「已接管」→ **既不回退、也不弹错误卡片**（用户看到的就是「解码失败后没有自动切回 mpv」）。事件通道一律带缓冲（`Channel(Channel.BUFFERED)`） |
| mpv 的失败语义（W18） | ①`MPV_EVENT_END_FILE` 只给事件 id、拿不到 reason → 用 `eof-reached==false` 判「非正常结束」，再用「自己发起的 END_FILE 预算 + 3s 窗口」抵消 `stop` / 换片 / 切集 / 清队列产生的旧文件 END_FILE（预算只在已加载文件时记；`FILE_LOADED` 清预算），否则会把正常换集报成失败；②一次打开失败会连发 2–3 条 END_FILE，ViewModel 侧必须按「同内核 + 同档位 + 3s」去重，否则一次失败连跳两档、把服务器转码整档跳过；③mpv 硬解不被支持时会**自己静默软回退**（Hi10P 实测照播），断网时只会 stall（`paused-for-cache`），两者都不产生失败事件——可观测的 mpv 失败主要是打开失败 / 文件类错误 |
| 用 `run-as` 改 prefs 会截断文件（W18 踩到并已恢复） | `Get-Content -Raw <不存在/读空> | adb shell run-as <pkg> tee shared_prefs/xxx.xml` 会用**空输入**把 prefs 截成 0 字节（本波把 Pad 5 + K60 的 prefs 全清了，靠会话中导出的备份恢复）。安全做法：写前确认本地文件非空 → `adb push` 到 `/data/local/tmp` → `adb shell "run-as <pkg> sh -c 'cat /data/local/tmp/x.xml > shared_prefs/xxx.xml'"` → `ls -l` 核对字节数；不要用 `tee` 接收可能为空的管道 |
| 回退档位按「Intent 条目 id」判定 = 容器入口死循环（W19 复现并修复） | 季 / 剧集入口与队列换集时 **Intent 条目 ≠ 实际播放条目**（如 Intent=季 id、实际播放=集 id）；旧 `resetDecodeFallbackForItem(intentId)` 每次回退重启都判定「换条目」→ 把刚推进的档位清零 → 链路永远停在「硬解失败 → 请求转码 → 重启 → 清零」。Pad 5 实测 60s 内 `Restart player (fallback=server-transcode)` 7 次、`清空解码回退档位` 8 次、从不出现 mpv（用户症状：不切 mpv + 频繁重载 + 面板一直 ExoPlayer）。修法：①档位改按**播放会话 id**（`pref_player_decode_fallback_session`）判定——回退 / 手动重启复用同一 id，新开播放页换新 id；②重启前把**实际播放条目**写回 Intent（`PlayerActivity.currentRestartTarget()`，先取目标再写偏好） |
| 面板先写偏好 = 手动切内核丢续播位置（W19 修复） | 解码面板的「切内核」行原本先 `controller.setBackend(...)` 再回调 Activity，而 `PlayerHolder.player` 的 getter 会**立刻按新偏好重建空实例** → Activity 随后读到的位置是 0、`currentMediaItem` 为 null（回退重启落回 Intent 原条目）。修法：面板只发回调，由 `switchBackendAndRestart` / `switchBackendForFallback` 统一「先读位置与当前条目、再写偏好、再重启」；`PlayerSettingsController.setBackend` 删除避免复用 |
| 循环保护 = 回退重启守卫（W19 新增） | 正常链路每个目标档位最多重启一次（目标=服务器转码 / 本地软解）；`pref_player_decode_fallback_guard` 记 `mediaId\|targetStage\|attempts`，同一媒体 + 同一目标档位超过 2 次即判定循环，直接交错误卡片（不再无限重启）。用户显式改内核 / 解码策略 / 码率 / 关开关或新播放会话时守卫清零；纯函数 `PlayerDecodeFallback.recordRestart / RestartGuard.exceeded / parseGuard / fallbackDecision` 有单测 |
| K60 logcat 环形缓冲小（W19 走查注意） | K60 上 MIUI 系统日志量大，`logcat -c` 后约 15s 就能把 app 的早期日志挤出缓冲（本波手动切内核的日志就是这样丢的）。要留证据就把 `logcat -c` 放在操作**前一刻**、操作后立刻 `logcat -d` 落盘；稳态证据用「偏好键 + uiautomator + 计数窗口」补 |
| `Sessions/Playing/Progress` **不落** UserData（W20 真机实测） | 播放中每 5 秒发进度上报，服务端 `Now Playing` 会话位置会变，但 `GET /Users/{userId}/Items/{itemId}` 的 `UserData.PlaybackPositionTicks` **恒为 0**——只有 `Playing/Stopped` 才落库，所以「进程被杀」= 进度全丢。要恢复进度必须直写 `POST /UserItems/{itemId}/UserData`（写用户数据白名单），只带 `PlaybackPositionTicks` 一个字段 |
| SDK `updateItemUserData` 的两个 UUID 顺序（W20 踩到） | jellyfin-sdk 1.8.12 的 `itemsApi.updateItemUserData(a, b, dto)` 会把 `a` 拼进 path、`b` 拼成 `?userId=`：正确顺序是 **(itemId, userId)**；按直觉传 `(userId, itemId)` 会得到 `POST /UserItems/{userId}/UserData?userId={itemId}` → 400（OkHttp 日志里一眼可见） |
| 覆盖层提示条被控制层淡出带走（W20 修复） | 播放页整层控制层是**一个** ComposeView：控制层 3.5 秒自动淡出时 `PlayerActivity` 把它置成 `INVISIBLE`，片头尾提示条、缓冲圈这类「独立于控制层」的元素会一起消失（真机表现：「跳过片头」永远看不见）。修法：`onRegionsChanged` 增加 `skipChipVisible` 维度，提示条可见期间保持整层合成 |
| 离线 Trickplay 缓存目录结构（W20 踩到） | `getTrickplayData` 的本地缓存是 `files/trickplay/<itemId>/<sourceId>/<index>`（Downloader 写入同款）；若把文件直接放成 `files/trickplay/<itemId>/<index>`，`listFiles().first()` 取到的是**文件本身**，`File(它, "0")` 读不到 → 悄悄回落到网络请求（真机表现为「投喂了图却仍在拉服务器」）。手工投喂验证时必须建 `<sourceId>` 这一层子目录 |
| 权威内容 | 位置 |
|----------|------|
| 播放界面任务 / 需求 / 决策 / 进度 | **本文件** |
| 服务器控制台皮肤（旁支功能） | `docs/web-console-skin.css`（改后同步 `app/phone/src/main/res/raw/web_console_skin.css` 与服务器自定义 CSS） |
| 旧文档（PLAN / PLAYER_SPEC / DEV_ENVIRONMENT / WEB_CONSOLE_SKIN） | 已合并进本文件，历史见 git |

---

## 10. 更新日志（近 8 条；更早见 `git log -p docs/PLAYER_PLAN.md`）

| 日期 | 变更 |
|------|------|
| 2026-10-02 | **W20-PLAYER 播放增强三项（`feature/w20-playback-enhance`）**：①**进度同步**——真机实测 `Sessions/Playing/Progress` 不落 `UserData.PlaybackPositionTicks`（服务器读回恒 0），新增 `PlaybackPositionWriter` 直写 `POST /UserItems/{itemId}/UserData`（写用户数据白名单）+ 上报循环迁到 ViewModel 常驻（切后台不停）+ 暂停 / 切集 / 退出即时落盘 + 换集给上一集补 `Playing/Stopped`；退出 / 杀进程 / 切集 / 双内核逐条取证（189.659 s 续播、194.849 s 续播、上一集 142.0 s）。②**片头尾**——提示条超时改读设置里的「显示时长」（原先写死 8 s），修「控制层淡出导致整层 ComposeView INVISIBLE、提示条永远看不见」的真机缺陷（`onRegionsChanged` 加 `skipChipVisible`），描边统一 `outlineVariant`，播放面板补时长档位；Exo / mpv 提示条 bounds 一致、自动跳过开（60→122 s）关（停在段内）两态。③**Trickplay**——整片预拉改为「只拉当前精灵图」+ 2 张 LRU + 请求去重 / 失败不重试（`TrickplayTiles` / `TrickplaySheetCache` / `TrickplayRequestState` 纯函数 + 6 单测），失败 404 → 优雅降级、命中本地精灵图 → 预览色块 21 万像素（双内核）。门禁 `assembleDebug（含 TV）+ ktfmtCheck + app 51 项 / player:local 62 → 68 项` 全绿；证据与还原见 §23。 |
| 2026-10-02 | **W19-PLAYER 手动选内核后的自动回退 + UI 同步 + 频繁重载修复 + 「失败自动回退」开关（`feature/w19-fallback-toggle`）**：①**根因**——回退档位按「Intent 条目 id」判定，季 / 剧集入口与队列换集时 Intent 条目 ≠ 实际播放条目，回退重启每次清零档位 → 「硬解失败 → 请求转码 → 重启 → 清零」死循环（Pad 5 实测 60s 重启 7 次、从不出现 mpv、面板一直 ExoPlayer）；②档位改按**播放会话 id** 判定（`pref_player_decode_fallback_session`，回退 / 手动重启复用、新开播放页换新）+ 重启前把**实际播放条目**写回 Intent（修掉 §21.5「回退重启落回原条目」）；③面板「切内核」不再先写偏好（`PlayerHolder` 会立即重建空实例 → 位置读成 0），统一由 Activity「先读位置与条目 → 再写偏好 → 再重启」；解码面板「播放内核」选中态改读**实际生效内核**（`PlayerHolder.backend`）；④新增 `pref_player_auto_fallback`（默认开）：关 = 强制所选内核，失败只提示错误、不切换不重启；⑤循环保护 `pref_player_decode_fallback_guard`（同媒体 + 同目标档位 >2 次即报错，纯函数 + 单测）。门禁 `assembleDebug（含 TV）+ ktfmtCheck + app 51 项 / player:local 59 → 62 项（+3）` 全绿；双机真机证据见 §22。 |
| 2026-10-02 | **W18-PLAYER 手动切内核不关闭回退链 + 档位文案带内核（`feature/w18-manual-fallback-label`）**：①回退判定抽到 `PlayerDecodeFallback.stageAfterFailure`——手动 / 自动同一条链路（本地硬解 → 服务器转码 → 本地软解），第 2 档任意错误继续降、第 3 档失败才报错；②内核切换拆成「手动」（清回退档位 + 先读位置再写偏好）与「回退链」（保留档位、显式 `setBackend(mpv)`）两条路径，修掉错误卡片切内核残留档位 / 续播位置读成 0；③`MPVPlayer` 在 `END_FILE` 且 `eof-reached=false` 时上报 `PlaybackException`（预算 + 窗口抵消自己发起的 END_FILE，`FILE_LOADED` 清预算），ViewModel 侧同内核 / 同档位 3 s 去重——「手动 mpv 失败也降级、第 3 档失败才弹卡片」成立；④事件通道改带缓冲（原无缓冲 `trySend` 会静默丢回退事件）；⑤解码面板「当前档位」改为 `ExoPlayer 硬解 / ExoPlayer 软解 / mpv 硬解 / mpv 软解 / 服务器转码`（`PlayerDecodeMode.decodeStage` 纯函数）。门禁 `assembleDebug（含 TV）+ ktfmtCheck + app 51 项 / player:local 59 项（+7）` 全绿；Pad 5 + K60 三段链路日志、四态文案与全败错误卡片证据见 §21。 |
| 2026-10-02 | **W17-PLAYER 播放页第六轮反馈（`feature/w17-player-labels-fallback`）**：①回退链真实生效修复——第 3 档不再提前写 `playerBackend` 偏好（`PlayerHolder.player` 的 getter 会按新偏好原地重建实例，随后的 toggle 会把内核切回 ExoPlayer），改由 Activity 显式 `setBackend(mpv)`；删掉「同一媒体只降级一次」拦截，改由档位状态机防死循环；第 2 档（服务器转码）失败时任何错误都静默落第 3 档，只有第 3 档也失败才显示错误卡片。②解码面板删「优先级」提示、内核名只留 `ExoPlayer` / `mpv`。③「设置 → 播放」删除 W13 的码率 / 解码兜底两行。④中央播放键从月白填充改为与其它覆盖键同一套玻璃底 / 描边（像素采样：播放键内部 (119,156,168)、传输键 (162,177,181)、锁定键 (158,181,186)，同为压暗玻璃）。⑤右上 5 键 + 左下 6 键图标下加 10sp 小字（键框 56×58dp、图标 ×0.82），窄屏 / Compact 只留图标，右下全屏键与中央五键 / 锁定键保持纯图标；左下 6 键恒定齐全（码率 / 解码 不再隐藏）。⑥横屏退出全屏修复——全屏 + 选集覆盖层展开时底栏整体让出 320dp（退出全屏键从被面板盖住的 x≈2410 移到侧栏左侧 x≈1690），连点 20 次全部生效。门禁 `assembleDebug（含 TV）+ ktfmtCheck + app 51 项 / player:local 52 项` 全绿；双机真机证据见 §20。 |
| 2026-10-02 | **W16-PLAYER：Exo 路径 libass + SRT 覆盖 + 解码优先级反转（`feature/w16-exo-libass-decode`）**：①引入 `io.github.peerless2012:ass-kt:0.5.1`（libass ISC，App 驱动渲染，不依赖 Media3）；②Exo 主字幕改由 libass 渲染——ASS/SSA 原文透传（定位 / 字体 / 特效还原），SRT 由 `AssSubtitleScript` 生成 ASS；延迟 / 开关 / 语言 / 大小档位与 mpv 同语义，次字幕仍纯文本，libass 失败回退既有文本渲染；③解码优先级改为「本地硬解 → 服务器解码 / 转码 → 本地软解」（`PlayerDecodeFallback` 纯函数），强制转码时禁直连 / 直传 / 流拷贝；④原生库冲突修复（libc++_shared 用 libmpv 版覆盖）。门禁 `assembleDebug + ktfmtCheck + app 49 项 / player:local 52 项（+13）` 全绿；Pad 5 + K60 逐条真机证据与性能采样见 §19。 |
| 2026-10-02 | **W15-LIBASS 特效字幕（`feature/w15-libass`）**：mpv 内核走内置 libass——DirectPlay 交给容器内嵌 ASS；服务器转码（容器无字幕）时按 MediaItem extras 里的 Jellyfin 字幕清单把 `Stream.ass` `sub-add` 给 mpv，不再出现「当前媒体没有可调节的字幕」。字幕模式联动（`off→sid=no` / `auto·always→sid=auto`）、延迟 / 语言沿用既有 `sub-delay` / `slang`；mpv 下自研覆盖层不接管（纯函数 + 单测钉死）。Exo 保持现状（Media3 只出文本；引入 libass 需新依赖，待批准）。门禁 `assembleDebug + ktfmtCheck + app 49 项 / player:local 39 项（+8）单测` 全绿；K60 双场景真机证据、像素对比与性能采样见 §18。 | 
| 2026-10-02 | **W14-PLAYER 播放页微调（`feature/w14-speed-badge`）**：①「1×」从可点文本项改为与顶栏清晰度徽标**同款的纯展示徽标**——抽共用组件 `PlayerOverlayBadge`（labelSmall + `CinefinShapes.Xs` 8dp 圆角 + 1dp `outlineVariant` 描边 + 水平 `Space2` / 垂直 2dp 内边距，无独立底色，顶 / 底由渐隐遮罩托底），顶栏清晰度徽标改为调用同一组件；②移除点击（点 1× 不再打开倍速面板），倍速入口只剩左下倍率图标键一个；③位置不变（「详细信息」右侧）、随倍率更新（1× / 1.5× …）；④底栏宽度预算改按徽标自适应口径（7 键 + 徽标最宽估值 + 8 间距）。门禁 `assembleDebug + ktfmtCheck + app 49 项单测` 全绿；Pad 5 + K60 逐条文本 / 像素证据见 §17。 |
| 2026-10-02 | **W13-PLAYER 播放页第五轮反馈（`feature/w13-player-ui5`）**：①左下工具行按「全屏 / 宽度」分级显示——全屏或宽度充足（≥600dp / 平板 · 折叠）显示 6 键，非全屏窄窗只留 音轨 · 字幕 · 倍率 · 详细信息 + 1×（判据抽纯函数 + 单测）；②倍率键只显示图标（与其它图标键同宽，选中态=非 1×）；③当前倍率作为独立文本项固定在「详细信息」右侧，点图标 / 点数字同开「选择播放速度」；④被隐藏的 码率 / 解码 在「设置 → 播放」各加一行兜底入口（不新增图标）。门禁 `assembleDebug + ktfmtCheck + app 49 项 / player:local 31 项单测` 全绿；Pad 5 + K60 逐条文本证据见 §16。 |
| 2026-10-01 | **W11-PLAYER 播放页第三轮反馈（`feature/w11-player-ui3`）**：①取消「更多」并把入口按性质分流到右上工具簇 / 左下工具行（新增 7 枚 `ic_player_*` 矢量图标）②锁定键移到画面区右缘垂直居中③中央恢复五键传输簇（上一个 · 快退 · 播放 · 快进 · 下一个）④进度条已播段改流光渐变（极光青 → 末端 <10% 辅光蓝）⑤两个加载图标去重（Media3 内置缓冲圈关闭，控件可见时落在主播放键里）⑥右下角全屏 / 退出全屏键（收起常驻内容栏 + 强制横屏）⑦返回键先关面板（子面板先回上一级）⑧时间码两端对齐 + 中央簇四档收放，任何窗口宽度都不重叠 / 不越界⑨季卡统一尺寸只横向滑动。真机 Pad 5 + K60 逐条文本证据；门禁 `assembleDebug + ktfmtCheck + app 34 项 / player:local 28 项单测` 全绿。见 §14 |
| 2026-10-01 | W9-PLAYER 播放器体验补全波（`feature/w9-player-experience`）：§1.8 信息面板（双内核 + 全字段「—」降级 + 纯函数单测）、§1.9 设置面板六组（页内即时生效）、§1.7 队列管理（拖拽排序 / 删除 / 清空 / 跳转 + 播完暂停）、§1.6 画面调整（旋转 / 镜像 / 裁剪 / 去黑边，双内核）。门禁 `assembleDebug + ktfmtCheck + app 23 项 / player:local 23 项单测` 通过；真机 Pad 5 逐项走查（信息面板双内核、设置六组、队列四操作 + 播完暂停、画面四变换 + 还原），`logcat` 无 FATAL/ANR。见 §12 |
| 2026-10-01 | §11 A–E 真机走查（PLAYER-UI / Pad 5 `43af8627`，10:27–11:05 指派窗口）：抽屉右侧化不压缩画面、底栏 5 键「图标+文字」、锁定单入口、更多收低频、手势（单击/双击 +10s/横向 seek/左亮度/右音量/长按 2×/面板打开拦截）与 PiP 全部通过；D 组回归（打开即播 + 自动字幕）通过；`logcat` 无 FATAL / ANR。走查中发现并修复①顶栏锁被 weight 布局挤到中部 ②竖屏命中带过紧（顶 72→64dp、中央半高 64→56dp）。设备侧已还原；结论见 §11.4、踩坑见 §9 |
| 2026-10-01 | §11 A–C/E 播放页控制层改造（PLAYER-UI / 分支 `feature/player-ui-refactor`）：Prism + 流光视觉收口；锁 / PiP 双入口收敛（PiP→更多）；底栏 9→5 个「图标 + 文字」高频键；面板改右侧抽屉且不压缩画面；命中带同步（竖屏 72 / 150dp、小窗 92dp）。门禁 `assembleDebug + player:local:testDebugUnitTest + ktfmtCheck` 通过；真机走查待设备（清单见 §11.4） |
| 2026-10-01 | §1.19 播放稳定性专项（PLAYER-STAB / 分支 `feature/player-stability`）：mpv 队列补片 ANR 修复（MPVPlayer 命令线程 + 补片真后台 / 节流 / 上限 150）+ 卡顿量化。真机 Pad 5（mpv / 灼眼的夏娜 73 集）：补片+点击场景主线程峰值 2584ms → 508ms；80 次点击 5.2s 无 `Input dispatching timed out`；SurfaceFlinger 采样修复前 mpv 补片窗口 p99 166.68ms vs 同窗口 ExoPlayer p95 8.56ms / jank 1.93%；修复后 mpv 稳态 24fps 满帧、无 >100ms 停顿。新增 `tools/player-stability/{Invoke-AnrRepro,Measure-FrameLatency}.ps1`；门禁 `assembleDebug + player:local:testDebugUnitTest + ktfmtCheck` 通过；回归：字幕 / 音轨 / 倍速 / 队列面板与补片正常 |
| 2026-10-01 | §11 D 组两个 bug 修复（PLAYER-BUG 会话 / 分支 `feature/player-autoselect-fix`）：① 自动选字幕 = `pickPrimary()` 默认轨兜底 + mpv `alang`/`slang` 传全量语言列表；② 打开即播 = `PlayerViewModel.startupInProgress` 起播窗口（窗口内不回存 / 不恢复 `playWhenReady`）。真机 Pad 5：mpv《夏日幽灵》由基线 `sid=1 ja-JP（日本語）` 纠正为 `● sid=2 zh-Hans-CN（简日双语）`；ExoPlayer 同一片自动选中 `index=4`（简日双语，cues=1072 解析成功）且 `state=3` 直接起播。遗留：`TrackSelectionEngine.pickTextTrack()` 的 auto 兜底未同步；「语言识别不出来（language=null）」的片源只走了代码路径、未单独真机复现 |
| 2026-09-28 | §1.4 通知封面完成（Coil 异步加载 + 回调刷新 + 单张缓存）；新增 §11「播放页 UI/UX 改造待办」（用户 2026-09-28 提出的 5 条，暂不处理） |
| 2026-09-28 | §1.2 音轨面板补全：自研 `AudioDelayProcessor`（ExoPlayer）+ `audio-delay`（mpv）+ 轨道描述 + 延迟偏好；真机验证通过（双内核听感确认） |
| 2026-09-28 | §1.1 字幕面板补全：自管字幕管线（下载/解析/Compose 渲染）+ 延迟 ±0.1s + 双语次字幕 + 外观五档；mpv 侧同步支持；真机验证通过 |
| 2026-09-28 | 文档重整：新增「快速上手」（项目结构 / 规范 / 命令 / 现状）与「§1 下一步任务」优先级清单；已完成阶段压缩为 §4 摘要；新增 §9 踩坑库 |
| 2026-09-28 | 选集栏默认收起 + 单击画面收起；修复 `MPVLib is not initialized`（mpv 释放后 surface 回调） |
| 2026-09-28 | 静默自动降级 mpv；队列入口收敛为右侧栏；选集 / 队列列表改为「缩略图 + 集号 + 标题」 |
| 2026-09-28 | 修复真机队列只显示一集（起播后台补全整剧）；修复 `MPVPlayer.addMediaItems` 下标越界崩溃 |
| 2026-09-28 | 切真机调试（小米平板 5 / Android 13）；debug 包开放播放页直启 |
| 2026-09-27 | 阶段 4 完成：`CinefinPlaybackService` + 自研通知 provider + 前台服务 + PiP + 媒体按键；`PlayerHolder` 成为播放器唯一持有者 |
| 2026-09-27 | 阶段 8.1–8.6 完成：`PlayerFormFactor` 多形态判定 + `SplitSide` / `SplitPortrait` / `Compact` 骨架 + 命中区 + 车机不沉浸 |
| 2026-09-27 | 阶段 1.1–1.3 完成：八态按钮、顶栏徽标、错误卡片；新增 `AGENTS.md`，旧文档合并为本文件 |

---

## 11. 播放页 UI/UX 改造（用户 2026-09-28 提出 · **先记录、暂不处理**）

> 用户明确说明：这一组问题**最后统一做**，本轮只登记。
> **2026-10-01 更新**：D 组（两个 bug）已由 PLAYER-BUG 会话单独修复并真机验证；A–C/E 仍按原计划统一做。
> **2026-10-01 二次更新（PLAYER-UI / 分支 `feature/player-ui-refactor`）**：A–C/E 已按「Prism v1.0 + S1 流光 A 手法」完成改造，
> 代码 + 编译 + 单测 + ktfmt 门禁通过；真机走查待 Pad 5 接回。落地结论、走查清单与未决项见 **§11.4**。
> 本质上它们是一次「播放页控制层重构」（控件体系 + 版式 + 面板形态），
> 与 §1.10 控制层视觉收口、§1.9 播放页设置面板高度重叠——**建议合并成一条线做，避免返工**。

### 11.1 问题清单（按类型分组）

**A. 控件精简（视觉噪音）** · ✅ 已落地（2026-10-01，见 §11.4）

1. 播放界面有多余的、离散的常驻控件：
   - 右上角的「画中画」与「锁定」，右下角还有一把固定的「锁」——同一个能力两个入口，且都常驻画面；
   - 现状落点：`PlayerTopBar`（PiP / 锁）+ `PlayerBottomBar` 最右侧的锁按钮；
   - 处理方向：常驻项只留一份；PiP 这类低频入口收进「更多」；锁的解锁入口保留在侧边/长按，
     锁屏时不显示其它控件（`LockedOverlay` 已有）。

**B. 图标语义（可读性）** · 🟡 文字标签已落地；core 图标重绘归 UI 会话（见 §11.4）

2. 控件图标需要重绘，并**加上文字标签**——目前纯图标（字幕 / 音轨 / 画面 / 信息 / 队列 / 睡眠…），
   不看 tooltip / 点一次试不出来是干啥的；
   - 处理方向：底栏按钮统一「图标 + 文字」，图标重绘保持 24dp 网格、线性风格、与既有 `ic_*` 一致；
   - 落点：`core/src/main/res/drawable/ic_*.xml` + `PlayerControlOverlay.kt` 的 `PlayerIconButton` / 底栏。

**C. 面板形态统一** · ✅ 已落地（2026-10-01，见 §11.4；真机走查待做）

3. 点击控件后的面板统一**从右侧滑出**（和选集栏同款），**半透明背景、不压缩播放画面**、直接盖在画面上：
   - 现状：面板是 `ModalBottomSheet`（底部弹起，遮住下半画面，且与平板侧栏两套交互）；
   - 处理方向：把面板宿主机从 ModalBottomSheet 换成右侧抽屉（复用 `PlayerContentPanel` 的侧栏形态），
     打开时 `PlayerActivity.applyVideoArea` 不再收窄画面；
   - 落点：`PlayerControlOverlay.kt`（panel host）、`PlayerContentPanel.kt`、`PlayerOverlayContainer.kt`（命中区）、
     `PlayerActivity.applyVideoArea`；
   - ⚠️ 风险：命中区（触摸分区）必须同步改，改完要真机走查「面板打开时的单击 / 滑动 / 手势仲裁」。

**D. 播放行为（bug 级）** · ✅ 2026-10-01 已修复（分支 `feature/player-autoselect-fix`）

4. 打开视频后**没有自动选择字幕**（以前实现过，疑似失效）；打开视频后**没有自动播放**。
   - 已知线索（新会话从这里查，别从零开始）：
     - 字幕：§1.1 之后主字幕由 `PlayerSubtitleController.pickPrimary()` 按语言优先级选；
       若源的 `language` 识别为空、且 `subtitleMode=auto`，则**不选任何轨**——需要补「识别不出来时按默认轨兜底」；
     - mpv 内核：`MPVPlayer` 初始化时把 `preferredTextLanguages.firstOrNull().split("-").last()`
       写进了 mpv 的 `slang`（`zh-Hans` → `Hans`），语言匹配必然失败——**"以前有、现在没有"最可能的原因**；
     - 自动播放：`initializePlayer` 里有 `player.play()`，但 `BasePlayerActivity.onPause` 会把
       `playWhenReady=false`；起播阶段若经历一次 pause/resume（内核切换、加载），可能被带成"要手点一次"。
   - 已落地修复：
     - 字幕：`pickPrimary()` 没命中语言优先级时按「默认轨 > 非强制字幕 > 轨道序号」兜底（auto / always 一致）；
       `MPVPlayer` 把 `alang` / `slang` 改为传**整份**语言优先列表（不再 `split("-").last()`）；
     - 打开即播：`PlayerViewModel` 新增起播窗口 `startupInProgress`，`initializePlayer` 到 `player.play()`
       之间不回存 / 不恢复 `playWhenReady`；`BasePlayerActivity` 改走 `rememberPlayWhenReady()` / `restorePlayWhenReady()`。
   - 真机验证（小米平板 5，2026-10-01）：mpv《夏日幽灵》自动选中 `● sid=2 zh-Hans-CN（简日双语）`（基线为
     `sid=1 ja-JP 日本語`）；ExoPlayer 同一片自动选中 `index=4`（简日双语）并解析 1072 条 cue，媒体会话
     `state=3` 直接播放、无需第二次点击。遗留：`language=null` 的片源只走了代码路径、未单独真机复现。
   - 验收：打开任意有字幕的新片自动出字幕（语言优先级仍生效）；打开即播、无需第二次点击。

**E. 控件排布** · ✅ 已落地（2026-10-01，见 §11.4）

5. 控件重新排列，参考主流播放器（VLC / Infuse / Jellyfin / Emby / 哔哩哔哩等）的排布习惯。
   - 处理方向：先做一次「控件审计」（列出全部控件、出现条件、使用频率），再定版式。主流共识大致是：
     顶部＝返回 + 标题（+ 更多）；中央＝播放/暂停 + ±10s + 上/下集；底部＝进度 + 时间 + 一排高频工具；
     低频（倍速 / 循环 / 信息 / 睡眠）进「更多」；锁定 / PiP 归顶部或侧边，不做双入口；
   - 与 A、B 是同一组改造，建议一次版式定稿。

### 11.2 做这组改造前先读

- **沿用现有设计锁**（§5.5.4）：朱砂 `#D2553C` 唯一强调色、墨系中性色、15/13/11sp 字阶、999dp 胶囊、
  16dp 面板圆角、180/220/240ms 动效曲线——**不新增配色、不换字体**。
- **播放画面优先**：常驻控件不抢画面注意力；面板打开默认不改变画面布局（C 的"不压缩画面"就是这条）。
- **命中区纪律**：任何控件 / 布局改动必须同步 `PlayerOverlayContainer` 的触摸分区，并真机走查手势（§5.2、§9）。
- 方法论参考（技能 `design-taste-frontend`，只借方法不套模板）：改动前先做 **audit**（现状清单 + 使用频率）、
  写一句 **design read**（受众＝自己家的观影场景、气质＝影院式克制、资产＝既有墨+朱砂设计系统）、
  坚持 **anti-default**（不为"看起来丰富"堆控件）。⚠️ 该技能面向 Web 页面，控件排布以主流**播放器**习惯为准。

### 11.3 建议顺序（新会话可直接照此开工）

1. **D（自动选字幕 + 自动播放）**——bug 级，线索已定位，改动小、体感收益最大；
2. **A + B + E（控件精简 / 图标文案 / 版式重排）**——一次定稿，避免反复改同一批文件；
3. **C（面板右侧化 + 不压缩画面）**——牵动命中区与侧栏交互，单独做 + 真机走查。

### 11.4 本轮落地记录（PLAYER-UI · 2026-10-01 · `feature/player-ui-refactor`）

**设计解读（做前 audit → design read）**

- 受众：自家片库的日常观影；气质：影院式克制的沉浸层（流光「内容即光源」，界面只在需要时浮现）。
- 资产：Prism v1.0 设计系统（影视域琥珀 `MediaFilm` 融入控件本体）+ `docs/design/s1-direction-a/README.md` 的流光手法；
  不新增 token、不做独立色块、不做发光。
- anti-default：不为"看起来丰富"堆控件——常驻项只留一份，低频进「更多」，面板覆盖而不是挤压画面。

**控件审计（改前 → 改后）**

| 位置 | 改前 | 改后 |
|------|------|------|
| 顶栏 | 返回 / 标题 / 画中画 / 锁 | 返回 / 标题 / 锁（单一锁入口） |
| 中央 | 上集 / −10s / 播放 / +10s / 下集 | 不变（视觉重做） |
| 底栏 | 进度 + 时间 + 倍速 / 循环 / 字幕 / 音轨 / 画面 / 信息 / 选集 / 睡眠 / 锁（9 键纯图标） | 进度 + 时间 + 字幕 / 音轨 / 画面 / 选集 / 更多（5 键「图标 + 文字」） |
| 更多面板 | （仅小窗可达） | 画中画 / 倍速 / 循环 / 字幕 / 音轨 / 画面 / 队列 / 信息 / 睡眠 |

**A–E 落实结论**

- **A 控件精简**：底栏固定锁与顶栏锁合并为顶栏一处；画中画收进「更多」；锁定后仍只有 `LockedOverlay` 解锁键。
- **B 图标 + 文字**：底栏高频键统一「22dp 图标 + LabelSmall 文字」、56dp 命中区；补齐缺失的「更多」图标
  （`player/local/.../drawable/ic_player_more.xml`，24dp 网格）。core `ic_*` 的重绘不在本会话范围（归 core / UI 迭代会话），本轮沿用现有图标 + 文字标签兜底可读性。
- **C 面板右侧化**：`ModalBottomSheet` 宿主整体下线，改为右侧抽屉（宽＝手机整宽 / 宽屏 46%，收敛 360–560dp）；
  半透明底（`surfaceContainer` 95%）+ 左缘 1dp 结构线，直接盖在画面上；**`applyVideoArea` 不改画面布局**（打开面板不再变窄 / 压缩画面）；
  触摸在打开期间继续由 `PlayerOverlayContainer.panelOpen` 全屏接管，点抽屉外空白关闭。
- **D 播放行为**：本轮不改；真机走查复核「打开即播 / 自动选字幕」（沿用 `ce30a03` 结论）。
- **E 控件排布**：顶部＝返回 + 标题 + 锁；中央＝上集 / ±10s / 播放；底部＝进度 + 时间 + 高频工具；
  低频（倍速 / 循环 / 信息 / 睡眠 / 画中画）进「更多」。常用路径：字幕 / 音轨 / 画面 / 选集仍 1 次点击；倍速 / 循环 / 信息 / 睡眠 / 画中画 2 次点击（按 §11.1 E 定稿，无隐藏路径）。

**流光手法落点（视觉收口）**

- 顶部 / 底部渐隐遮罩改为 `scrim` 令牌派生（不再用旧墨色硬编码）；控制层 280ms Decelerate「淡入 + 上浮 12dp」；
  面板 420ms Emphasized 右侧滑出（只动 transform / opacity）。
- 覆盖层键：玻璃底（scrim 45% 半透明）+ `OnSurface` 图标；主播放键 = 70dp / 圆角 22dp / `OnSurface` 底 + `InverseOnSurface` 图标（§8.7）；
  媒体色只出现在进度、进行中与激活项（选中键 = `Media.Container` + `Media.Outline` + `Media.Bright`），无 glow、无独立色块。
- 面板内：组标题 `LabelSmall` / `OnSurfaceFaint`；选项行 52dp + 12dp 圆角，选中＝媒体色容器 + 描边 + `ic_check` 指示图标（替代旧「行尾色点」）；chip 按 §8.3。
- 时间码统一 `MonoData`（tabular-nums，跳动不抖）。

**命中区同步（`PlayerOverlayContainer` / `PlayerActivity`）**

- 竖屏（SplitPortrait）：顶带 56→**64dp**（= 顶栏实高；走查中发现 72dp 会把可落手势区压到 ~30px，已回调）、中央半高 64→**56dp**、底带 100→**150dp**（底栏因图标 + 文字变高）。
- 小窗（Compact）：单行控制条高度 72→**92dp**。
- 面板打开：继续整层接管（`panelOpen`），不随抽屉宽度变化；锁定命中区不变。

**门禁与验证（2026-10-01，worktree `79d7`）**

- `:app:phone:assembleDebug` ✅｜`:player:local:testDebugUnitTest` ✅｜`ktfmtCheck` ✅。
- 真机：Pad 5（`43af8627`）走查完成（2026-10-01 10:27–11:05，负责人在 device-lock 指派窗口），命令全部带 `-s 43af8627`。

**真机走查结果（Pad 5 `43af8627`，2026-10-01）**

- ✅ **平板横屏（SplitSide）**：底栏 5 键「字幕 / 音轨 / 画面 / 选集 / 更多」完整显示；点「选集」开合右侧内容栏（画面让出 320dp，速度 / 时间随画面区左移）；进度条点按 / 拖动跟手。
- ✅ **面板不压缩画面**：字幕 / 更多两个入口都从右侧滑出（523dp 抽屉覆盖侧栏 + 画面右缘），打开期间画面与侧栏位置不变；点抽屉外空白关闭；抽屉内可滚动（颜色组滚到）。
- ✅ **命中区 / 手势**：面板打开时横向滑动不 seek（位置只随时间走）；单击显隐、双击 +10s（04:24→04:40）、横向滑 seek（04:40→05:24）、左亮度（0.514→0.988→0.511）、右音量（150→79）、长按 2×（按住期间速度显示 2×）全部正常。
- ✅ **锁定**：顶栏唯一锁入口（右上角）；锁定后无障碍树只剩「解锁」1 个节点、点画面不改状态；点右中解锁键恢复。
- ✅ **面板内容**：主 / 次字幕、延迟 ±0.1s、外观（大小 / 颜色 / 背景 / 描边 / 位置）渲染与交互正常（音轨 / 画面 / 队列等本轮未改逻辑，入口可达）。
- ✅ **更多面板**：画中画 / 倍速 / 循环 / 信息 / 睡眠全部就位；点「画中画」进入 PiP（`mode=pinned` + `pip-dismiss-overlay`）。
- ✅ **竖屏窄窗**（`wm size 800x1600` 把 Pad 5 模拟成 355dp 手机宽）：SplitPortrait 下方选集区正常、底栏 5 键不换行、竖屏命中带下点工具键能开抽屉、抽屉自动整宽。
- ✅ **D 组回归**：`am start` 打开剧集后**无需任何点按**进度自行推进（03:26→03:39）；自管字幕自动出（面板「主字幕＝外部 zh」选中、画面有字幕行）。
- ✅ **视觉**：顶 / 底渐隐遮罩、玻璃覆盖键、`OnSurface` 方形主播放键、选中态琥珀（字幕激活 / 面板选中行）与首页 / 详情页一致；无独立色块 / 色点 / 发光。
- ✅ **稳定性抽样**：整轮 `logcat` 无 `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out`；显隐与抽屉开合无可见卡顿。

**走查中发现并已修复（同分支补丁）**

1. 顶栏「锁定」被挤到顶栏中部：旧写法「标题 `weight(1f, fill=false)` + 尾部 `Spacer(weight(1f))`」不会把末尾控件推到右端；改为「标题吃满剩余空间」，锁与清晰度徽标回到右上角（复测截图确认）。
2. 竖屏命中带过紧：顶带 72dp 把「点画面显隐控制层」的可落手指区压到约 30px；按栏高精确化——竖屏顶带 72→**64dp**、中央半高 64→**56dp**（中央簇仍全包住），可落区回到约 80px。

**真机验证记录**

| 日期 | 设备 | 结论 | 备注 |
|------|------|------|------|
| 2026-10-01 | 小米平板 5 `43af8627`（Pad 5） | ✅ 走查通过（A–E + 流光视觉 + 手势回归 + PiP） | 10:27–11:05 指派窗口；设备侧已还原（`wm size` / density / 旋转 / 音量 / force-stop / 临时文件） |

**未决 / 移交项**

1. core `ic_*` 图标重绘（§11 B 的"重绘"部分）归 UI 迭代会话；播放器底栏已用现有图标 + 文字标签兜底可读性。
2. 背景模糊：Compose 没有 backdrop blur，抽屉按 §5.3 例外用「半透明底 + 结构线」实现（不做假 blur）。
3. 遥控 / D-pad：本轮补了抽屉 `paneTitle` 语义；TV 焦点环走查并入阶段 8。
4. K60（`8e875894`）归 UI 迭代会话；本轮竖屏项用 Pad 5 改窗口尺寸等价验证，如需 K60 原生复验并入 W5 全量回归。

---

## 12. W9-PLAYER 落地记录（2026-10-01 · 分支 `feature/w9-player-experience`）

> 本波一次做完 §1.6 / §1.7 / §1.8 / §1.9 四项。代码、单测、真机走查与本文件进度同一次提交写回。

### 12.1 决策补充（与 §0 同源）

| 编号 | 决策 |
|------|------|
| D11 | **本波新增的播放器偏好键声明在 `player/local/domain/PlayerExtraPreferences.kt`**（`pref_player_video_rotation` / `_mirror` / `_crop_percent` / `_letterbox_crop` / `pref_player_pause_after_item`）。原因：W9 波次里 `settings` 模块的 `AppPreferences.kt` 由阅读器会话持有；键名仍用 `pref_player_*`、存在同一个 SharedPreferences，读写走 `AppPreferences.getValue/setValue`。下个波次如需收口，把这 5 个 `Preference` 搬回 `AppPreferences.kt` 即可（值不变） |
| D12 | 「清空队列」= **保留正在播放的条目**，只清掉其余待播项；「播完暂停」= 与循环模式正交的独立开关（当前一集播完停在片尾）。这两个语义与主流播放器一致，避免「清空即停播」的意外 |
| D13 | 信息面板字段来源分两层：**媒体源（Jellyfin）+ 内核实测，内核优先**；两层都取不到的字段一律显示「—」，面板行数固定。双内核因此不需要各自一套面板 |

### 12.2 落地内容与文件

| 项 | 主要落点 |
|----|----------|
| §1.8 信息面板 | `player/core`：`PlayerMediaInfo`（+`PlayerItem.mediaInfo`、`PLAYER_EXTRA_MEDIA_INFO`）；`player/local`：`PlayerMediaInfoFormat`（纯函数 + 14 个单测）、`PlayerKernelMediaInfo`、`MPVPlayer.queryMediaInfo()`；`app/phone`：`InfoPanel` 重写（9 + 6 行固定字段） |
| §1.9 设置面板 | `app/phone/presentation/player/PlayerSettingsPanel.kt`（`PlayerSettingsController` + 六组 + `PanelSwitchRow` / `PanelChipRow` / `VideoTransformControls`）；`MorePanel` 增加「播放设置」；`PlayerViewModel.setSubtitleMode/setBackend/refreshSegmentPreferences`；`MPVPlayer.applyHwDec`；`PlayerGestureHelper` 手势总开关现读 |
| §1.7 队列管理 | `QueuePanel` + `QueueEditableRow`（长按拖动换位、行尾删除、清空、点行跳转）；`PlayerViewModel.moveQueueItem/removeQueueItem/clearQueue`；`MPVPlayer.moveMediaItems/removeMediaItems`（mpv `playlist-move` / `playlist-remove`）；`advanceAfterItemEnd()` 的「播完暂停」分支 |
| §1.6 画面调整 | `player/local/domain/PlayerVideoTransform.kt`（纯函数 + 5 个单测）；`PlayerActivity.applyVideoTransform()` / `applyExoPlayerVideoTransform()`；`MPVPlayer.applyVideoTransform()`；「画面」面板 = 比例 + 旋转 / 镜像 / 裁剪 / 去黑边 |

辅助脚本：`tools/w9-player/Device-Ui.ps1`（dump → 找节点 → 点中心；控制层自动淡出的时序问题用它规避）。

### 12.3 门禁（2026-10-01）

```
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest
```

- `:app:phone:assembleDebug` ✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **23** 项（既有）
- `:player:local:testDebugUnitTest` ✅ **23** 项 = 既有 9 + 本波 14（`PlayerMediaInfoFormatTest` 9 + `PlayerVideoTransformTest` 5）

### 12.4 真机走查（Pad 5 `43af8627`，命令全部带 `-s`）

素材：《灼眼的夏娜》S01E01–E04（h264 1080p，ExoPlayer 解码能力不足 → 自动降级 mpv，正好覆盖 mpv 路径）、《夏日幽灵》（ExoPlayer 路径）。

| 项 | 证据（文本） |
|----|--------------|
| §1.8 ExoPlayer | 《夏日幽灵》信息面板：容器「—」（ExoPlayer 无容器信息，按契约降级）、编码 `HEVC（H.265）`、分辨率 `1920 × 1080`、帧率「—」、码率「—」、音频 `AAC · 2 声道`、文件大小 `2.45 GB`、路径 `/Videos/0b834979-…/stream` |
| §1.8 mpv | 同一集信息面板：容器 `MKV`、`H.264（AVC）`、`1920 × 1080`、帧率 `23.976 fps`、`SDR`、`AAC · 2 声道 · 140 kbps`、`1.09 GB`、路径 `/Videos/6718656d-…/stream`；码率在 mpv 未暴露时为「—」——两内核字段差异全部按「—」降级，无空行、无崩溃 |
| §1.9 六组 | 「更多 → 播放设置」六组页签 `播放 / 解码 / 字幕 / 音频 / 画面 / 手势` 全部可达（手势组需横滑一屏）；`解码` 组在 ExoPlayer 下把 mpv 硬解两行置灰；`音频` 组三个语言预设 + 「下一次选轨生效」说明；`手势` 组 11 项全列 |
| §1.9 即时生效 | 字幕模式切「关闭」→ 画面字幕立即消失（`pref_subtitle_mode=off`），切回「自动」→ 重新选轨出字幕；`后台播放` 开关 → `pref_player_background_audio=true`；`手势总开关` 关 → 视频区单击不再输出 `PlayerControlsState: player controls visible=…`，开 → 立即恢复 |
| §1.7 拖拽排序 | 长按拖动 `queue move: 3 -> 2（共 31 项）`，面板顺序变为 `E01 / E02 / E04 / E03 / E05`（mpv `playlist-move` 生效） |
| §1.7 删除 / 清空 / 跳转 | 行尾删除键 → `queue remove: 4（共 30 项）`；「清空」→ `queue clear: 保留当前条目，队列剩 1 项` 且播放不中断；点 E03 → `Playing MediaItem: 5db08a9c-0ae2-5c4b-1995-9a6472a664d9`（跳转命中） |
| §1.7 播完暂停 | 开：`end of media item: state=3 … index=0/22 hasNext=true` → `pause after item end: stay at index=0（播完暂停）`（有下一集也不跳）；关：`advance after item end: from=3 … -> index=3` + `Playing MediaItem: 29fa54d9…`（原有自动连播不回归） |
| §1.6 ExoPlayer | 旋转 90° → `ExoPlayer 画面变换: rotation=90.0 scaleX=1.600 scaleY=1.600`；镜像水平 → `scaleX=-1.600`；裁剪 10% → `scaleX=-2.000 crop=10`；去黑边 → `resizeMode=0 → 4`（FIT→ZOOM）；`pref_player_video_*` 四项同步落盘 |
| §1.6 mpv | 退出重进后自动恢复：`mpv 画面变换: video-rotate=90 video-scale-x=-1.0 video-scale-y=1.0 video-zoom=0.3219/1.8301`（裁剪 × 填满）；面板全设回默认 → `video-rotate=0 video-scale-x=1.0 video-zoom=0.0000`，偏好同步归零 |
| 稳定性 | 整轮 `logcat` 无 `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out`；设备侧已还原（force-stop、`pref_player_background_audio` 复位、`wm` 与旋转未改动、`/sdcard/w9p*.xml` 清理） |

### 12.5 未决 / 移交项

1. **章节刻度开关**已接到控制层（关掉后不再向进度条传章节），但「视觉上刻度消失」未单独做像素采样；如需像素级验收并入 R4 回归。
2. **容器字段**：ExoPlayer 侧拿不到时显示「—」。若要做到两内核都显示，需要把 `MediaSourceInfo.container` 透传到 `FindroidSource`（`data` 模块，本波未动，避免与其它会话冲突）。
3. **随机播放**在 mpv 下仍禁用（既有能力限制，本轮未动）。
4. **手势总开关**关掉后仍保留「单击显隐控制层」（有意为之，避免控件唤不出的死角）；若后续要求「全关」，需要在设置页明确提示。
5. 新增偏好键收口回 `AppPreferences.kt`（见 D11）留给下个能安全改该文件的波次。

---

## 13. W10-PLAYER 落地记录（2026-10-01 · 分支 `feature/w10-player-ui2`）

> 用户 2026-10-01 晚对播放页的 7 条反馈。三组提交：`1a7384f`（①②）、`0f38c7e`（③④⑤）、`2f1e45f`（⑥⑦）+ 验收中发现的 Exo 比例补丁（同分支）。

### 13.1 决策补充（与 §0 同源）

| 编号 | 决策 |
|------|------|
| D14 | 播放页整层切 **A · Lumen**（极光青强调 / 曜石黑面板 / 月白主行动），色值只引用 `LumenColors` / 语义 token；面板、按钮、图标、进度条随 `ProvideLumenColors` 一起切，离开播放页自动回 Prism |
| D15 | **一个功能一个入口**：字幕 / 音轨 / 画面 / 选集 只在右上角工具簇；循环模式移入「播放设置 → 播放」组；「更多」只留 画中画 / 倍速 / 播放信息 / 睡眠定时 / 播放设置；小窗（Compact）没有工具簇，在「更多」里兜底这四个入口，功能不缩水 |
| D16 | 画面比例两内核对齐：ExoPlayer = `FIT` / `FILL` 原生 + **ZOOM 由等比视图缩放实现**（Media3 的 ZOOM 在内容框等于视频比例时无效）；mpv = `keepaspect` + `panscan`。裁剪 / 去黑边沿用同一套几何算法（`cropScale` / `letterboxFillScale`） |
| D17 | 面板选择**不再自动关闭**（倍速 / 循环 / 睡眠 / 队列跳转只更新状态）；从「更多 / 播放设置」进的子面板带返回箭头回到上一级，只有显式关闭 / 返回才退出 |
| D18 | 顶栏 / 底栏高度由 Compose `onSizeChanged` 实测回传 `PlayerOverlayContainer`（`topBarHeightPx` / `bottomBarHeightPx`），命中带永远跟控件实高一致，不再写死 dp |

### 13.2 逐条修复方式与文件

| # | 反馈 | 修复方式 | 主要文件 |
|---|------|----------|----------|
| ① | 画面比例无效、一直铺满且变形 | mpv 侧新增比例映射 `mpvResizeProperties`（FIT = keepaspect/panscan 关、裁剪填满 = panscan 开、拉伸填满 = keepaspect 关）并即时写 property；Activity 启动 / 切档 / 切内核都重放；Aspect 面板去掉「mpv 不支持」分支；双指缩放改走同一比例通路（还原 = 回偏好档位），去掉了直接在 mpv 写 panscan 的旧路径（残留源）。验收中发现 ExoPlayer 三档画面**逐像素相同**（Media3 的 ZOOM 在内容框 = 视频比例时是空操作）→ 「裁剪填满」改为「等比视图缩放填满画面区」，FIT/FILL 走原生，裁剪也改为纯视图缩放 | `player/local/domain/PlayerResizeMode.kt`（+5 单测）、`MPVPlayer.applyResizeMode`、`PlayerActivity.applyResizeModeToKernel` / `applyExoPlayerVideoTransform`、`PlayerGestureHelper.updateZoomMode`、`PlayerControlOverlay.AspectPanel` |
| ② | 字幕背景默认应为「无」 | `AppPreferences.playerSubtitleStyleBackground` 与 `SubtitleStyle.BACKGROUND_DEFAULT_INDEX` 2 → 0（选项顺序 无 / 轻纱 / 半透明 / 实底）；已存偏好不动（无法区分显式选择） | `settings/.../AppPreferences.kt`、`player/core/.../SubtitleStyle.kt` |
| ③ | 队列编辑不该在「更多」 | 队列整理（长按拖动排序 / 行尾删除 / 清空 / 点行跳转）移入「选集 → 播放队列」页：`PlayerEpisodeQueueList` 增可编辑模式，平板侧栏 / 竖屏下方内容区 / 小窗队列面板共用同一实现；「更多」里的播放队列入口删除 | `PlayerContentPanel.kt`（编辑行 + 整理头 + 删除键）、`PlayerControlOverlay.QueuePanel`、`PlayerSettingsPanel.kt` |
| ④ | 面板选择后不要自动关闭 | 倍速 / 循环 / 睡眠 / 队列跳转都只更新状态；抽屉新增返回箭头（「更多 → 子面板」） | `PlayerControlOverlay.kt`（SpeedPanel / RepeatPanel / SleepPanel / QueuePanel / PlayerPanelDrawer） |
| ⑤ | 「更多」去重与功能归位 | 从「更多」移除 字幕轨 / 音轨 / 画面比例 / 播放队列 / 循环；循环进「播放设置 → 播放」组，设置面板内与工具簇重复的三个入口行删除；小窗保留兜底项 | `PlayerControlOverlay.MorePanel`、`PlayerSettingsPanel.kt`、`player/local/res/values*/strings.xml` |
| ⑥ | 控件重排（左下 + 右上，进度条通栏） | 顶栏 = 返回 + 标题 + **右上角工具簇**（字幕 / 音轨 / 画面 / 选集 / 更多，<600dp 纯图标）+ 锁定；底栏 = **左下角**播放 / 上下集 / ±10s / 时间，倍速非 1× 时媒体色提示；**进度条通栏贴底**；中央只留缓冲转圈，整块还给手势；命中带按实测高度（D18） | `PlayerControlOverlay.kt`（PlayerTopBar / PlayerToolCluster / PlayerBottomBar / PlayerPlayKey / PlayerBufferingIndicator）、`PlayerOverlayContainer.kt`、`PlayerActivity.kt` |
| ⑦ | 播放页流光化 | 控制层整层包 `ProvideLumenColors`；进度条重绘为 Lumen 语言：极光青进度、缓冲层、章节刻度、拖拽放大 + 极光青描边、1dp 发丝线包边 + 顶部内高光（无 glow、无新色值） | `PlayerActivity.kt`（ProvideLumenColors）、`PlayerControlOverlay.PlayerSeekBar` |

### 13.3 门禁（2026-10-01）

```
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest
```

- `:app:phone:assembleDebug` ✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **23** 项（既有）
- `:player:local:testDebugUnitTest` ✅ **28** 项 = 既有 23 + `PlayerResizeModeTest` 5

### 13.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

| # | 证据（文本 / 数值） |
|---|--------------------|
| ① mpv | `mpv 画面比例: resizeMode=0 keepaspect=yes panscan=0.000000 video-zoom=0.000000`（适应）/ `resizeMode=4 keepaspect=yes panscan=1.000000`（裁剪填满）/ `resizeMode=3 keepaspect=no panscan=0.000000`（拉伸填满），每条都有 `player resize mode=<n> kernel=mpv` 伴随；切成 ExoPlayer 再切回 mpv 均为 `resizeMode=0 keepaspect=yes panscan=0`（无残留）。像素：适应 = 1600×900 居中（上下黑边区可见氛围底）、裁剪/拉伸 = 铺满 1600×2560 |
| ① Exo | `player resize mode=0/4/3 kernel=exoplayer` + `ExoPlayer 画面变换: rotation=0.0 scaleX=1.000/2.844/1.000 scaleY=… resizeMode=0/4(FIT+缩放)/3 zoomFill=1.000/2.844/1.000 viewAspect=0.625 videoAspect=1.778`；像素：适应 = 视频居中两侧/上下为氛围底，裁剪 = `zoomFill=2.844` 等比放大铺满，拉伸 = 画面铺满且比例被拉（`resizeMode=3`） |
| ② | K60 删除 `pref_player_subtitle_style_background` 后启动播放页：字幕面板「背景」选中项像素 = (54,122,118)（极光青容器 = 选中「无」），其余三项 (17,19,25) 未选中；全程 prefs 里该键**仍不存在**（证明是默认值 0 而非存储值） |
| ③ | K60「选集 → 播放队列」面板：`长按拖动排序，点行跳转` + `清空` [2974,242][3088,316] + 每行 `从队列移除`（E01–E04）；同一波「更多」面板只剩 画中画 / 选择播放速度 / 播放信息 / 睡眠定时 / 播放设置（无 播放队列/字幕轨/音轨/画面比例/循环） |
| ④ | Pad 5 选倍速 0.25× 后 dump 仍为「选择播放速度」面板（8 个档位全在），只是选中态变化；点「关闭面板」才退出 |
| ⑤ | Pad 5 / K60 的「更多」条目清单一致（见 ③）；循环模式入口出现在「播放设置 → 播放」组（`循环模式 / 顺序播放 / 列表循环 / 单集循环 / 随机`），且设置面板带「返回上一级」 |
| ⑥ | Pad 5 平板/竖屏：右上角 字幕 [832..893] 音轨 [967..1028] 画面 [1102..1163] 选集 [1237..1298] 更多 [1372..1433] 锁定 [1474..1582]；左下角 播放暂停 [45,2310][171,2436] 跳回 [196..300] 快退 [300..404] 快进 [404..508] 跳过 [508..616] 时间 [641..888]，倍速贴右下 [1517..1555]。Pad 5 手机形态（`wm 1080x2400` + `density 420` ≈411dp）：工具簇退化成纯图标 [292..1058]（无文字标签）、左下角播放 [53,716][200,863] + 时间 [748..1026]、下方「选集 / 播放队列」页签；点视频区中心 540,400 → 控制层隐藏（中央命中区未被控件吃掉）。K60 横屏同分布：工具簇 [1953..3171]、左下角 [70..956] + 时间 [994..1371] |
| ⑦ | Pad 5 截图底部进度条行 y=2491：已播段 = (92,225,210) ≈ `#5CE1D2`（4390 采样点）、knob = (242,245,249) 月白、缓冲/轨道 = (40,42,45)；整条轨道左右通栏到边缘 |
| 稳定性 | 两台设备整轮 `logcat` 无 `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out`；副作已还原（Pad 5 `wm size/density` reset、accel=1 / user_rotation=0；K60 `user-rotation free` + accel=1 / user_rotation=0；两台 force-stop、`/sdcard/w10*.xml` 清理、本地截图已删） |

### 13.5 未决 / 移交项

1. **ExoPlayer 的「裁剪填满」用视图缩放实现**（D16）：`zoomFill` 依赖 `uiState.currentMediaInfo` 的源分辨率；拿不到分辨率时退回 1.0（等效适应屏幕）。若后续拿到内核实测分辨率可再收敛。
2. `PlayerVideoTransform` 的 `cropScale` 同时作用于两个内核，语义一致；但「去黑边」在 ExoPlayer 侧仍是「FIT 提升为裁剪填满」，与 mpv 的 `video-zoom` 路径不同源，视觉已对齐、代码未统一。
3. 小窗（Compact）的「更多」保留四个工具兜底入口（D15），与常规形态的右上角工具簇分工不同——若后续小窗也加工具簇，可把兜底项撤掉。
4. K60 当前物理横置（传感器 landscape），手机竖屏形态用 Pad 5 `wm 1080x2400` + `density 420` 等价覆盖验证（与 W8/W9 同法）；K60 原生竖屏复验并入下次 R4 回归。

---

## 14. W11-PLAYER 落地记录（2026-10-01 · 分支 `feature/w11-player-ui3`）

> 用户 2026-10-01 深夜对播放页 / 季页的 9 条反馈 + 负责人 3 次补充（中心簇恢复五键、倍率显示与倍率控件合并、季卡同尺寸横滑）。
> 五组提交：`5b67a67`（①②③⑥ + ⑤ 去重）、`486c6e5`（④）、`26ffef7`（⑤⑦⑧）、`da5062f`（⑨）、`0d75c0d`（真机踩到的两处补丁 + 中央簇收放）。

### 14.1 决策补充（与 §0 同源）

| 编号 | 决策 |
|------|------|
| D19 | **取消「更多」**，入口按性质分流：右上工具簇 = 内容 / 显示类（字幕 · 音轨 · 画面 · 选集；宽屏再加 播放设置）；左下工具行 = 播放行为类（倍率键 · 睡眠 · 播放信息 ·（窄屏）播放设置 · 画中画）+ 右下角全屏键；小窗（Compact）用一行可横滑的小键行兜住全部入口，功能不缩水、也不越界 |
| D20 | **中央传输簇 = 上一个 · 快退 −10s · 播放 / 暂停 · 快进 +10s · 下一个**（五键居中，与 W9 版一致）；传输键只在中央出现，右上 / 左下都不再重复 |
| D21 | **锁定键**移到画面区右缘垂直居中（贴边、样式与其它覆盖键一致）；**全屏键**在右下角；**倍率显示与倍率控件合并成同一个键**（键面直接写 `1×` / `1.5×`，非 1× 用媒体色），全播放页只有这一个倍速入口 |
| D22 | **时间码两端对齐**：左 = 当前进度、右 = 总时长，各自有固定位置（旧版把「当前 / 总时长 + 倍率」挤在左下一行，窗口一窄就被挤出屏幕）；进度条仍通栏贴底 |
| D23 | **加载图标只留一个**：Media3 内置缓冲圈关掉（`app:show_buffering="never"`，两个内核都走自绘）；控件可见时 = 主播放键里的转圈（键仍可点，缓冲卡住还能暂停），控件整层隐藏时才用玻璃圈兜底 |
| D24 | **返回键优先级**：面板打开 → 先关面板（子面板回上一级，回完清标记）；没有面板才交回系统退出播放页 |
| D25 | **中央簇随画面区宽度收放**（302 → 266 → 240 → 160dp 四档）：锁定键固定在右缘中部，两者都吃中部空间，窄窗必须收尺寸；命中块改用实测尺寸回传 |
| D26 | **季卡统一尺寸**：固定宽度（150 / 168 / 184 / 208dp 分档）+ 标题块固定预留两行高度 → 一列季卡同宽同高，只保留横向滑动 |

### 14.2 逐条修复方式与文件

| # | 反馈 | 修复方式 | 主要文件 |
|---|------|----------|----------|
| ① | 控件全堆在右上角 | 删除 `MorePanel` / `PlayerPanel.More`，入口按 D19 分流；新增 7 个矢量图标 | `PlayerControlOverlay.kt`（`PlayerToolCluster` / `PlayerBottomBar` / `PlayerToolKey` / `PlayerCompactToolKeys`）、`PlayerContentPanel.kt`（`PlayerCompactBar`）、新图标：`ic_player_settings` `ic_player_info` `ic_player_pip` `ic_player_speed` `ic_player_sleep` `ic_player_fullscreen` `ic_player_fullscreen_exit` |
| ② | 锁按钮移到右缘中部 | 顶栏删掉锁键，改为画面区 `Alignment.CenterEnd` + 12dp 内边距的玻璃覆盖键；命中带加「右缘中部 76×72dp」块 | `PlayerControlOverlay.PlayerTopBar`、`PlayerOverlayContainer.shouldHandle` |
| ③ | 中央三键（补充后为五键） | 新建 `PlayerCenterCluster`：上一个 / 快退 / 播放（70dp 主行动键）/ 快进 / 下一个，整体居中；`playerCenterSpec` 按宽度收放键尺寸 | `PlayerControlOverlay.PlayerCenterCluster` / `playerCenterSpec`、`PlayerOverlayContainer`（命中块改实测尺寸） |
| ④ | 进度条流光渐变 | 已播段改 `Brush.horizontalGradient`：0→0.9 极光青、0.9→1 过渡到辅光蓝（辅光蓝 <10%）；缓冲层 / 章节刻度 / 拖拽钮 / 内高光保留；色标抽纯函数 + 单测 | `PlayerControlOverlay.PlayerSeekBar` / `playerProgressGradientStops` / `playerProgressBrush` |
| ⑤ | 两个加载图标 | Media3 内置缓冲圈关掉；控件可见时加载图标落在主播放键里（可点），控件隐藏时才画玻璃圈；承载视图在缓冲期间保持合成 | `activity_player.xml`（`show_buffering="never"`）、`PlayerControlOverlay.PlayerPlayKey` / `BufferingIndicator`、`PlayerActivity`（`onRegionsChanged` 增 `buffering`） |
| ⑥ | 右下角全屏键 | `toggleFullscreen()`：收起常驻内容栏 + `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`；同一个键按 `isFullscreen` 换图标 / 文案；退出时还原侧栏与方向策略 | `PlayerActivity`（`fullscreenMode` / `desiredOrientation` / `toggleFullscreen`）、`PlayerControlOverlay.PlayerBottomBar`、`PlayerContentPanel.PlayerCompactBar` |
| ⑦ | 返回键先关面板 | 覆盖层内 `BackHandler` + 纯函数 `resolvePlayerBack(panelOpen, hasParentPanel)`：子面板 → 上一级（并清标记），一级面板 → 关闭，无面板 → 放行给系统 | `PlayerControlOverlay`（`resolvePlayerBack` / `BackHandler`） |
| ⑧ | 缩小时时间错位 | 时间码独立成行、两端对齐；工具行 `weight(1f) + horizontalScroll` 兜底不越界；中央簇四档收放保证与锁定键不重叠 | `PlayerControlOverlay.PlayerBottomBar` / `playerControlSpec` / `playerCenterSpec`、`PlayerOverlayContainer` |
| ⑨ | 季页海报一大一小 | `ItemCard` 新增可选 `width`；季列表传 `rememberSeasonCardWidth()`（分档固定宽度）；标题块固定预留两行高度 → 同宽同高，只横向滑动 | `presentation/film/ShowScreen.kt`（`seasonCardWidthDp`）、`presentation/film/components/ItemCard.kt` |

### 14.3 门禁（2026-10-01）

```
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest :modes:film:testDebugUnitTest --console=plain
```

- `:app:phone:assembleDebug` ✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **34** 项 = 既有 23 + 新增 11（`PlayerControlLayoutTest` 9：版式分档 / 渐变色标 / 返回优先级 / 中央簇不重叠；`SeasonCardWidthTest` 2）
- `:player:local:testDebugUnitTest` ✅ **28** 项（既有）｜`:modes:film:testDebugUnitTest` ✅（该模块无测试源）

### 14.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

| # | 证据（文本 / 数值） |
|---|--------------------|
| ① | **Pad 5 平板横屏**（2560×1600）右上工具簇 = 字幕 `[1874,18][2000,144]` 音轨 `[2009..2135]` 画面 `[2144..2270]` 选集 `[2279..2405]` 设置 `[2414..2540]`；左下工具行 = 倍率键 `[45,1318][189,1426]` 睡眠 `[198..324]` 信息 `[333..459]` 画中画 `[468..594]`；右下 = 进入全屏 `[2405,1318][2513,1426]`；无障碍树整轮 **没有任何「更多」节点**（改动前每次 dump 都有 `content-desc="更多"`）。**Pad 5 手机形态**（`wm 1080x2400` + `density 420` ≈411dp）：右上退化成 4 个纯图标 `[556..1063]`，左下工具行 = `1×` `[32,702][153,812]` 睡眠 `[156..277]` 信息 `[277..398]` 设置 `[398..519]` 画中画 `[519..645]`，右下 进入全屏 `[929,694][1055,820]`。**K60 竖屏**：右上 `[746..1418]`、左下 `[42..858]`、右下 `[1240..1408]`。新图标 7 枚（24dp / 1.5dp 圆头描边，`player/local/src/main/res/drawable/ic_player_*.xml`）；`ic_player_more.xml` 随「更多」一起删除 |
| ② | 锁定键 = 画面区右缘垂直居中：Pad 5 平板 `锁定播放器 [2423,746][2531,854]`（右边距 29px，y 中心 800 = 1600/2，即画面区竖向中心）；K60 竖屏 `[1229,587][1397,755]`（右边距 43px，y 中心 671 = 视频区 1344/2）；Pad 5 手机形态 `[921,440][1047,566]`（y 中心 503 = 1008/2） |
| ③ | 中央五键整簇居中：Pad 5 平板 `上一集 [936..1044] 快退 [1067..1175] 播放暂停 [1200..1358] 快进 [1383..1491] 下一集 [1514..1622]`（簇中心 1279 ≈ 屏宽中心 1280）；K60 竖屏 `[283..444][444..612][622..818][829..990][990..1158]`（簇中心 720.5 = 1440/2）；Pad 5 手机形态 `[140..941]`（中心 540.5 = 1080/2） |
| ④ | 进度条像素采样（Pad 5，暂停在 39%，进度条行 y=1533）：x=20 / 250 / 500 / 750 / 850 / 880 全为 `#5CE1D2`（极光青），x=900 `#5CE0D3` → 920 `#63DBDC` → 940 `#69D5E5` → 960 `#70CFEE`（向辅光蓝 `#7CC4FF` 过渡，只占已播段末端 ≈9%）→ knob `#F2F5F9`；缓冲层 / 章节刻度 / 内高光保留 |
| ⑤ | `dumpsys activity top` 中 Media3 的 `android.widget.ProgressBar{… #7f0a00e4 app:id/exo_buffering}` 恒为 `G`（GONE，`0,0-0,0`，两台设备一致）→ 内置缓冲圈彻底关闭。切集缓冲瞬间两帧截图（间隔 350ms）对比：Pad 5 中心键区 19881 px 中 519 px 变化（2.6%，键内转圈在转）、键外 85–140px 半径 38864 px **0 变化**；K60 中心键区 32761 px 中 1015 px 变化（3.1%）、键外 63796 px **0 变化**；两处中心像素均为 `#F2F5F9`（月白主播放键 + 一条深色弧） |
| ⑥ | Pad 5 平板：侧栏展开时全屏键在视频区右下 `[1685,1318][1793,1426]`，点击 → 键变「退出全屏」+ 侧栏收起（`显示选集栏` + E01 消失）；再点 → 侧栏恢复（`隐藏选集栏` `[1559..1685]` + `E01 [2119,252]`）。Pad 5 手机形态点击 → `cur=2400x1080`（横屏）且底部「选集 / 播放队列」页签消失；K60 竖屏点击 → `cur=3200x1440` + 页签消失 + 键变「退出全屏」；退出后 `dumpsys` 方向策略回到 `SCREEN_ORIENTATION_FULL_SENSOR` |
| ⑦ | 两台设备都验证：打开「播放设置」→ 点「循环模式」进子面板 → BACK 回「播放设置」（`循环模式` 行回来）→ 再 BACK 面板关闭（只剩覆盖层键）；全程 `pidof` 不变（Pad 5 `16853` / K60 `24288`）、`topResumedActivity` 仍是 `PlayerActivity` |
| ⑧ | Pad 5 手机形态（1080×2400，411dp）：工具行 y 694–820、时间行 `10:46 [53,833][153,873]` / `24:25 [926,833][1026,873]`，两行互不相交、横向不出 1080；再缩到 `wm 800x2400`（305dp）：中央簇收到 `[160..639]`、锁定键 `[640,440][766,566]`（**不再重叠**；未收尺寸前实测簇右端 800 与锁定键左缘 640 重叠 160px = 26dp），时间行仍在两端；K60 竖屏 `13:43 [70,1113][205,1166]` / `24:25 [1234,1113][1369,1166]` |
| ⑨ | 同一部两季剧集：Pad 5 横屏两张季卡 `[72,194][486,893]` / `[540,194][954,893]` 均 **414×699**；Pad 5 竖屏 **378×690** ×2；K60 竖屏 **525×977** ×2；Pad 5 手机形态 **394×734** ×2（间距 54–63px = 24dp）；`wm 800x2400` 缩窄后横向滑动：滑动前 `[53..447]` / `[510..800]`，滑动后 `[0..290]` / `[353..747]`（尺寸不变，只横滑） |
| 稳定性 | 两台设备整轮 `logcat`：`FATAL EXCEPTION` 0 / `ANR in` 0 / `Input dispatching timed out` 0；副作已还原（Pad 5 `wm size/density` reset、accel=1、user_rotation=0；K60 accel=1、user_rotation=0；两台 force-stop、`/sdcard/w11*` 清理、本地截图已删） |

### 14.5 未决 / 移交项

1. 倍率键在窄屏是纯文字键（`1×` / `1.5×`），宽屏才带 `ic_player_speed` 图标；若以后要给窄屏也加图标，注意别挤到右下角全屏键。
2. `ItemCard` 新增了可选 `width`，本波只有季列表传值；`PersonScreen` 的两条 `LazyRow` 仍是旧的 `fillMaxWidth()`（同样的「按固有尺寸排布」风险），按本轮范围没动，留作后续打磨。
3. 中央簇最后一档 160dp 对应 <360dp 画面区；<280dp 的极窄窗口（真实设备上会走 Compact 骨架，只有 `wm` 尺寸覆盖能造出来）仍可能与锁定键轻微重叠，没有实际设备路径。
4. 「全屏」= 收起常驻内容栏 + 强制横屏；纯横屏设备（Pad 5 平板）上表现为侧栏收起 / 恢复，方向策略不变（K60 竖屏点击可见真实横屏全屏效果）。
5. Compact（自由窗口 / 分屏窄宽）已经没有「更多」：工具行横向可滚，入口一个不少；后续若要给小窗也做完整版式，可把这一行改成与左下工具行同源的实现。

---

## 15. W12-PLAYER 落地记录（2026-10-02 · 分支 `feature/w12-player-ui4`）

> 用户 2026-10-02 第四轮播放页反馈（多轮澄清后确认的**终版布局**）+ 面板重组 + 码率服务器转码 + 解码回退。
> 提交：`e69218d`（C 行为修复）、`eb3c592`（A 布局 / 控件样式 + B 面板重组 + 码率 / 解码接线）、`b7cb441`（ktfmt）+ 转码档位补丁（本波末次提交）。
> 基线 `master 49d6b08`；本波只写 `player:local`、`app:phone`、`data`（最小改）、`settings/AppPreferences`（只追加）。

### 15.1 决策补充（与 §0 同源）

| 编号 | 决策 |
|------|------|
| D27 | **终版布局**（用户确认，勿再变动）：进度条一行 = 当前时间 · 进度条（保留流光渐变）· 总时长；进度条下方左侧 6 键 = **音轨 · 字幕 · 倍率 · 码率 · 解码 · 详细信息**，右侧 = 全屏 / 退出全屏（同一个键）；右上角 5 键 = **画中画 · 睡眠 · 选集 · 画面 · 设置**（画面在选集与设置之间）；锁定键仍贴画面区右缘垂直居中；中央五键仍居中。顺序抽成 `PLAYER_BOTTOM_KEY_ORDER` / `PLAYER_TOP_KEY_ORDER` + 单测钉死（改顺序即回归）。 |
| D28 | **控件框统一**：覆盖层控件的玻璃底不透明度 0.45 → **0.28**（按下 0.44）、统一 1dp 描边（未选中 = 月白 16%，选中 = `Media.Outline`）、按键位版式收尺寸（宽屏 44dp / 窄屏 38dp；键内图标 24 / 22dp）。倍率键 = **图标在上、当前倍率在下**的垂直堆叠，图标与其它键同宽。 |
| D29 | **面板重组**：设置面板只剩 **播放 / 手势** 两个分类（Tab 切换，播放分类直接列设置项、无「播放」大按钮）；**解码**独立成面板（内核 + 硬解/软解策略 + 优先级说明）；**字幕模式 / 记住手动选轨** 并入「字幕」面板；**音轨语言优先级** 并入「音轨」面板；**画面** 从设置里删除（只保留右上角入口）；**码率** 独立成面板（自动 / 原始画质 / 1·2·3·5·8·12·20·40 Mbps）。 |
| D30 | **解码优先级与回退**：服务器转码 / 解码 → 本地硬解 → 软解（软解最耗电，放最后）。ExoPlayer 硬解优先 = 扩展渲染器兜底 + `setEnableDecoderFallback(true)`；解码能力类错误仍静默换 mpv（不弹错误卡片、不崩溃）。仅软解 = ExoPlayer 扩展渲染器优先（FFmpeg）/ mpv `hwdec=no`。未知偏好值一律按**硬解**处理，不静默降级成软解。 |
| D31 | **码率走服务器转码**：`pref_player_streaming_bitrate`（0 自动 / -1 原始画质 / >0 具体 Mbps）→ `maxStreamingBitrate`；**只有具体 Mbps 档才声明 `transcodingProfiles`**（HLS + ts + h264 + aac/mp3/ac3/opus）；「原始画质」把 `enableTranscoding=false` 关死转码。播放侧继续走既有「`transcodingPath` 优先」路径，选档位由 Activity 从当前位置重启播放页重新拉 PlaybackInfo。 |
| D32 | **选集 = 覆盖层**：平板 / 折叠展开的选集栏不再缩窄 `PlayerView`（画面永远整窗宽、不挤压不右移），改为盖在右缘 320dp 的半透明面板 + 左缘 1dp 结构线；触摸命中由 `PlayerOverlayContainer.sidePanelOpen` / `sidePanelWidthPx` 单独接管；返回键顺序 = 子面板 → 一级面板 → 选集栏 → 系统。 |

### 15.2 逐条修复方式与文件

| # | 反馈 | 修复方式 | 主要文件 |
|---|------|----------|----------|
| C1 | 选集面板挤压 / 右移画面 | `applyVideoArea` 不再按侧栏缩窄画面（宽度恒为 `MATCH_PARENT`）；侧栏改 `PlayerSideContent(containerColor = surfaceDim 94%)` 覆盖层 + 左缘 1dp 结构线；命中区新增右缘一条 | `PlayerActivity.kt`、`PlayerControlOverlay.kt`、`PlayerContentPanel.kt`、`PlayerOverlayContainer.kt` |
| C2 | 返回键要先关选集 | `resolvePlayerBack(panelOpen, hasParentPanel, sidePanelOpen)` 新增 `PlayerBackAction.CloseSidePanel`；`BackHandler` 三分支处理（收栏后保持控制层可见，不退出播放页） | `PlayerControlOverlay.kt`、`PlayerControlLayoutTest.kt` |
| A1 | 控件框更透 / 贴边 / 描边 | 新增 `PLAYER_GLASS_ALPHA=0.28` / `PLAYER_GLASS_PRESSED_ALPHA=0.44` / `PLAYER_GLASS_BORDER_ALPHA=0.16`；`PlayerIconButton` 新增 `iconSize` 参数（默认 40dp 键 / 24dp 图标）；`PlayerToolButton`（宽屏「图标 + 文字」键）随布局改版删除 | `PlayerControlOverlay.kt` |
| A2 | 倍率键图标上 / 1X 下 | `PlayerSpeedKey` 改垂直 `Column`（图标 `spec.iconSizeDp` + 数字 `labelSmall`），键宽 = 其它键宽；小窗（Compact）同样生效 | `PlayerControlOverlay.kt` |
| A3 | 终版布局 | `PlayerBottomBar` 重写为「进度行（时间-条-时间）+ 6 键行 + 右下全屏键」；`PlayerToolCluster` 重写为右上 5 键；顺序表 + 单测；新增图标 `ic_player_bitrate.xml`（三根信号条）/ `ic_player_decode.xml`（解码芯片） | `PlayerControlOverlay.kt`、`player/local/src/main/res/drawable/ic_player_*.xml`、`PlayerControlLayoutTest.kt`、`values/strings.xml`、`values-zh-rCN/strings.xml` |
| B1 | 设置面板两个分类 | `PlayerSettingsGroup` → `PlayerSettingsTab(Playback / Gesture)`；`SettingsGroupRow` 改收标签表 + 选中下标；删除解码 / 字幕 / 音频 / 画面四组分支 | `PlayerSettingsPanel.kt` |
| B2 | 解码独立入口 | 新增 `PlayerDecodePanel`（内核 + 策略 + 优先级文案）+ `PlayerPanel.Decode`；`PlayerDecodeMode` 纯函数（扩展渲染器模式 / 解码器回退 / mpv hwdec）；`PlayerHolder` 接 `extensionRendererMode` + `setEnableDecoderFallback` | `PlayerSettingsPanel.kt`、`PlayerControlOverlay.kt`、`player/local/domain/PlayerDecodeMode.kt`、`PlayerHolder.kt`、`PlayerDecodeModeTest.kt` |
| B3 | 字幕 / 音频唯一入口 | `SubtitlePanel` 顶部并入字幕模式 chips + 「记住手动选轨」；`AudioPanel` 顶部并入音轨语言优先级 chips（`AudioLanguagePresets` 改 internal）；设置面板不再有这两组 | `PlayerControlOverlay.kt`、`PlayerSettingsPanel.kt` |
| B4 | 码率面板 | 新增 `PlayerBitratePanel` + `PlayerStreamingQuality`（档位映射纯函数 + 单测）；`AppPreferences` 只追加 `pref_player_streaming_bitrate` / `pref_player_decode_mode` | `PlayerSettingsPanel.kt`、`settings/.../PlayerStreamingQuality.kt`、`AppPreferences.kt`、`PlayerStreamingQualityTest.kt` |
| B5 | 服务器转码生效 | `getMediaSources` 读偏好 → `maxStreamingBitrate` / `enableTranscoding` / 条件性 `transcodingProfiles`（HLS+ts+h264+aac 等）；选档位由 `PlayerActivity.restartPlaybackKeepingPosition()` 重新拉流并续播 | `JellyfinRepositoryImpl.kt`、`PlayerActivity.kt` |

### 15.3 门禁（2026-10-02）

```
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest --console=plain
```

- `:app:phone:assembleDebug` ✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **44** 项 = 既有 34 + 新增 10（`PlayerStreamingQualityTest` 5、`PlayerControlLayoutTest` 新增 5：两条顺序断言 + 尺寸分档 + 411dp 不越界 + 返回键覆盖选集）
- `:player:local:testDebugUnitTest` ✅ **31** 项 = 既有 28 + `PlayerDecodeModeTest` 3

### 15.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

| # | 证据（文本 / 数值） |
|---|--------------------|
| ① 选集不挤压画面 | Pad 5 平板横屏（2560×1600）：打开选集栏前后 `dumpsys activity top` 的 `PlayerView` 恒为 `0,0-2560,1600`（未压缩 / 未右移）；选集覆盖层占 x≈1885–2551（320dp），E01–E06 列表可见；`显示选集栏 → 隐藏选集栏` 状态切换正常 |
| ② BACK 先关面板 | Pad 5：选集栏打开时按 BACK → 无障碍树节点 99 → 54、标签回到「显示选集栏」，`pidof` 24805 不变、`topResumedActivity` 仍是 `PlayerActivity`；K60：「播放设置」面板按 BACK → 面板标题节点 0、`pidof` 1785 不变 |
| ③ 进度条行 + 6 键 | Pad 5 横屏：`00:02 [36,1369][121,1403]` / `04:00 [2437,1369][2522,1403]` **同一 y**；进度条像素 y=1386：x=150/180/210 = `#5CE1D2`（极光青已播段）、x=230/245 = `#F2F5F9`（knob）、x=2400 = `#393A3B`（轨道）；下方一行 = 选择音轨 `[41,1454][149,1562]` → 选择字幕轨 → 1× → 码率 → 解码 → 信息，右侧 进入全屏 `[2410,1454][2518,1562]`。K60 竖屏同序（`选择音轨 [25,1117][179,1285]` … 进入全屏 `[1247,1117][1415,1285]`），时间行 `00:00 [56,987]` / `04:00 [1248,987]` 同 y |
| ④ 右上 5 键顺序 | Pad 5 横屏：画中画 `[2045..2144]` → 睡眠 `[2144..2243]` → 显示/隐藏选集栏 `[2243..2342]` → 画面比例 `[2342..2441]` → 播放设置 `[2441..2549]`（y 均 9–117）；Pad 5 竖屏 `[1087..1591]`、K60 `[641..1425]` 同序 |
| ⑤ 控件框像素采样 | Pad 5（暂停帧，y=1470 横切「音轨」键）：框外视频 `#52565A` → 左描边 `#5B5E61`（x≈54–57）→ 框内玻璃 `#3D4143` → 右描边 `#474F51`（x≈140–144）；纵向 x=95：上描边 `#474C4F`（y≈1458）→ 框内 `#20–2D` → 下描边 `#2C2D30`（y≈1554）。框内合成值 = 视频 × 0.72 + 玻璃 × 0.28（实测 ≈ `#3D`，W11 的 0.45 档应 ≈ `#30` → **更透**）；视觉框 ≈ 89×97px（≈40–44dp）= 24dp 图标 + 一圈边距（语义/触摸框按 M3 最小 48dp 展开，108px） |
| ⑥ 倍率键竖排 | Pad 5 键框 `[279,1451][378,1564]`（宽 99px = 44dp，与其它键同宽、更高）；键内文本 `1×` 节点 `[312,1516][345,1555]` 落在**下部**，x=328 纵切：y=1470/1494 = `#F2F5F9`（仪表图标笔画，上部）→ y=1530 = `#D3D5D9`（数字，下部） |
| ⑦ 设置面板两分类 | Pad 5 / K60：抽屉标题「播放设置」+ 只有 `播放` `[1411,96][1557,204]` / `手势` `[1566,96][1712,204]` 两个 Tab；播放分类直接列 后台播放 / 跳过片头片尾按钮 / 自动跳过片头片尾 / 进度条显示章节刻度 / 播完暂停 / 循环模式（**无「播放」大按钮、无解码 / 字幕 / 音频 / 画面项**）；手势分类含 手势总开关 / 左右亮度音量 / 双指缩放 / 横向滑动进度 / 拖动预览 / 长按跳章节 / 记住亮度 / 进入时铺满 / 长按倍速 三档 |
| ⑧ 解码面板 + 回退 | 面板文本：播放内核（`ExoPlayer（硬解）` / `mpv（软解兜底）`）+ 解码策略（`硬解优先` / `仅软解`）+ `优先级：服务器转码 / 解码 → 本地硬解 → 软解`；点 ExoPlayer 行 → `Restart player (backend=exoplayer)` + 偏好落盘 `pref_player_backend=exoplayer`。回退链路（10-bit H.264 片源《染成茜色的坂道 NCED》）：`Player error on backend=exoplayer: ERROR_CODE_DECODING_FAILED` → `解码能力不足（ERROR_CODE_DECODING_FAILED），自动降级到 mpv 内核` → `Restart player with backend=mpv` → `pref_player_backend=mpv`、`pidof` 存活、媒体会话 `state=3`，全程 `FATAL/ANR/Input dispatching timed out` 0 条 |
| ⑨ 字幕 / 音频唯一入口 | 字幕面板文本：`选择字幕轨 | 字幕模式 | 自动 | 始终显示 | 关闭 | 记住手动选轨 | 字幕延迟 | −0.1s | 0.0s | +0.1s | 主字幕 | … | 次字幕（双语） | … | 字幕外观 | 大小 | …`；音轨面板文本：`选择音轨 | 音轨语言优先 | 中文优先 | 日语优先 | 英语优先 | 下一次选轨生效 | 音轨延迟 | … | 日语 FLAC · 2 声道 · 48 kHz`；设置面板已无这两组与画面项 |
| ⑩ 码率服务器转码 | 选「3 Mbps」：`pref_player_streaming_bitrate=3`；日志 `Restart player (streaming bitrate=3)` → `getMediaSources bitrate=3 maxStreamingBitrate=3000000 transcoding=true profiles=1`；`PlayerViewModel: Stream url: …/videos/<id>/master.m3u8?…&VideoBitrate=2552000&AudioBitrate=448000&SegmentContainer=ts…&TranscodeReasons=ContainerBitrateExceedsLimit`（mpv 打开同一 m3u8）；服务器会话 `TranscodingInfo{IsVideoDirect=False, Bitrate=2808000, Container=ts, VideoCodec=h264, AudioCodec=aac, TranscodeReasons=ContainerBitrateExceedsLimit}`；切回「自动」→ `bitrate=0 maxStreamingBitrate=1000000000 profiles=0`（回到改造前行为，续播位置保留） |
| 稳定性 / 还原 | 两台设备整轮 `logcat`：`FATAL EXCEPTION` 0 / `ANR in` 0 / `Input dispatching timed out` 0；副作还原：Pad 5 `accelerometer_rotation=1` / `user_rotation=0`（`wm size/density` 无覆盖）、K60 同；两台 force-stop、`/sdcard/w12*` 与本地截图 / dump 全部删除；码率偏好已回「自动」、后端偏好保持会话开始时的 `mpv` |

### 15.5 未决 / 移交项

1. **选集覆盖层会盖住右下角全屏键与进度条右端**（面板打开期间那一条不可点 / 不可见）——这是「覆盖层不挤压画面」的必然结果，用户本轮只要求画面不动；若日后要求面板打开时仍能操作全屏键，需要给覆盖层让出底部控制带。
2. **Jellyfin 会话的 `PlayState.PlayMethod` 仍可能显示 `DirectPlay`**，真正的转码事实看 `TranscodingInfo`（`IsVideoDirect=false` / `Bitrate` / `TranscodeReasons`）；验收脚本别只看 PlayMethod。
3. 转码档位只声明 HLS + ts + h264（+ aac/mp3/ac3/opus）：HEVC / AV1 片源会转成 h264（服务器默认行为，与官方客户端一致）；若日后要给高码率保留 HEVC 直通，再补 directPlayProfiles / codecProfiles。
4. 小窗（Compact）工具行顺序是「音轨 · 字幕 · 倍率 · 码率 · 解码 · 信息 · 睡眠 · 选集 · 画面 · 设置 · 画中画 · 锁定」，与画面区版式的两条固定顺序表同源但合并成一行；后续若给小窗也做完整版式，按 `PLAYER_BOTTOM_KEY_ORDER` / `PLAYER_TOP_KEY_ORDER` 拆行。
5. 本轮未做（用户未要求）：媒体信息面板内容不变（只是入口改名「详细信息」语义不变）、手势分类内容不变（只是从六组变两分类）。

---

## 16. W13-PLAYER 落地记录（2026-10-02 · 分支 `feature/w13-player-ui5`）

> 用户第五轮播放页反馈（**方案 A 分级显示** + 倍率图标化 + 1× 文本独立）。提交：`d16b088`（实现）+ 文档提交；基线 master `424fdea`。
> 本波只写 `app/phone`（`PlayerControlOverlay.kt` / `PlayerSettingsPanel.kt` / `PlayerControlLayoutTest.kt`）；`player:core` 无改动，`AppPreferences` / `NavigationRoot` / `settings.gradle.kts` / `libs.versions.toml` 未触碰。

### 16.1 决策补充（与 §0 同源）

| 编号 | 决策 |
|------|------|
| D33 | **左下工具行分级显示（方案 A）**：判据 = 先看真实全屏状态（`PlayerActivity.fullscreenMode`），再看宽度 / 形态档——全屏、宽度 ≥ **600dp**（与 `playerControlSpec` 的窄屏档、`PlayerFormFactor` 的 Phone → Tablet 分档同一条线）、或 Tablet / Foldable 形态 → **6 键全显**（音轨 · 字幕 · 倍率 · 码率 · 解码 · 详细信息 + 1×）；非全屏窄窗（手机形态 411dp、`wm` 收窄 <600dp、自由窗口）→ 只留 音轨 · 字幕 · 倍率 · 详细信息 + 1×。抽成纯函数 `playerToolRowShowsSecondaryKeys(isFullscreen, widthDp, formFactor)` + `playerToolRowVisibleKeys(showsSecondaryKeys)`，单测钉死（全屏全显 / 窄窗隐藏 / 宽屏非全屏全显 / 600dp 阈值）。 |
| D34 | **倍率键只显示图标**：改用 `PlayerIconButton(ic_player_speed)`，与其它工具键同宽同款，选中态 = 当前倍率 ≠ 1×；旧「图标上 / 数字下」的 `PlayerSpeedKey` 删除。**当前倍率文本独立成项**：`PlayerSpeedLabel` 固定在「详细信息」右侧，与其它覆盖层控件同玻璃底（0.28）+ 1dp 描边 + 同圆角；点图标 / 点数字打开同一个「选择播放速度」面板——全播放页仍然只有一个倍速语义入口（W11 D21 的约束不变）。 |
| D35 | **设置 → 播放 补两行兜底入口**（码率 / 解码，纯文本 PanelRow，不新增图标；副标题显示当前档位 `自动 / 原始画质 / n Mbps` 与 `内核 · 策略`），点击打开对应面板；从子面板 BACK 仍回「播放设置」（沿用 W11 ⑦ 的 `panelBackTarget`）。小窗（Compact）工具行与画面区版式**共用同一条判据**：非全屏窄窗同样隐藏 码率 / 解码，倍率只留图标 + 「信息」右侧 1×。 |

### 16.2 逐条落地与文件

| # | 反馈 | 落地方式 | 主要文件 |
|---|------|----------|----------|
| ① | 方案 A 分级显示 | 新增 `PLAYER_TOOL_ROW_WIDE_WIDTH_DP = 600f` + 两个纯函数；`PlayerBottomBar` 按 `playerToolRowVisibleKeys()` 渲染；调用点传 `layout.windowWidthDp` / `layout.formFactor` / `isFullscreen`；Compact 行加 `showsSecondaryKeys` 参数 | `PlayerControlOverlay.kt` |
| ② | 倍率键只显示图标 | `PlayerBottomKey.Speed` 分支改 `PlayerIconButton`（`selected = speed != 1f`）；删除 `PlayerSpeedKey` | 同上 |
| ③ | 1× 独立文本项 | 新增 `PlayerSpeedLabel`（玻璃文本项，`contentDescription = "倍速 1×"`），渲染在键表尾部（=「详细信息」右侧）；Compact 行同样插在「信息」之后 | 同上 |
| ④ | 设置兜底入口 | 「播放」分类顶部加 `码率` / `解码` 两个 `PanelRow`；新增 `streamingBitrateCaption()` / `decodeCaption()` 副标题（复用 `PlayerStreamingQuality.bitrateLabel` 与既有解码文案） | `PlayerSettingsPanel.kt` |
| ⑤ | 判据单测 | `PlayerControlLayoutTest` +5：全屏全显 / 非全屏窄窗隐藏 码率·解码 / 宽屏非全屏全显 / 600dp 阈值 / 「详细信息」必须是末键（1× 才落在它右侧）；`bottomRowWidthDp` 公式计入 1× 项 | `PlayerControlLayoutTest.kt` |

### 16.3 门禁（2026-10-02）

```
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest --console=plain
```

- `:app:phone:assembleDebug` ✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **49** 项 = 既有 44 + 新增 5
- `:player:local:testDebugUnitTest` ✅ **31** 项（既有，未改动）

### 16.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

素材：《灼眼的夏娜》S01E03（`itemId=5db08a9c-0ae2-5c4b-1995-9a6472a664d9`，mpv 内核）。

| # | 证据（文本 / 数值） |
|---|--------------------|
| ① 全屏 → 6 项齐全 | **Pad 5 平板全屏**（`PlayerActivity: player fullscreen=true`）：音轨 `[41,1461][149,1564]` · 字幕 `[158..266]` · 倍速 `[275..383]`（仅图标）· 码率 `[392..500]` · 解码 `[509..617]` · 信息 `[626..734]` · 1× `[747,1465][846,1564]` · 退出全屏 `[2410..2518]`，`PlayerView` 恒 `[0,0][2560,1564]`；**Pad 5 窄窗全屏**（`wm 800x1600` + `density 440` → 1600x756 ≈ 582dp）：同一窗口下 6 键回归（码率 `[386,636][508,756]`、解码 `[508..630]`、信息 `[630..752]`、1× `[765,649][870,754]`、退出全屏 `[1447..1579]`）；**K60 全屏**（3200x1440）：6 键 + 1× + 退出全屏 `[2968,1222][3136,1390]` |
| ② 非全屏窄窗隐藏码率/解码 | **Pad 5 `wm` 收窄**（1600x756 ≈ 582dp，非全屏）：音轨 `[20,636][142,756]` · 字幕 `[142..264]` · 倍速 `[264..386]` · 信息 `[386..508]` · 1× `[521,649][626,754]` · 进入全屏 `[1447..1579]`；无障碍树整轮**无 码率 / 解码 节点**。**K60 竖屏 411dp**（1440x3200，非全屏）：音轨 `[25,1135][179,1303]` · 字幕 `[179..333]` · 倍速 `[333..487]` · 信息 `[487..641]` · 1× `[658,1152][791,1285]`（文本节点 `1× [700,1188][750,1249]`）· 进入全屏 `[1247..1415]`，同样无 码率 / 解码 |
| ③ 宽屏非全屏仍全显 | Pad 5 原生 `[0,0][2560,1564]`（≈1280dp）非全屏 = 6 键 + 1×；K60 退出全屏后停在横屏 3200x1440（914dp）非全屏 = 6 键 + 1×（宽度档位兜底）；平板全屏态与窄窗全屏态对比证明**全屏优先于宽度**（同一 582dp 窗口仅因 `fullscreen=true` 就恢复 码率 / 解码） |
| ④ 设置 → 播放 兜底入口 | Pad 5（平板 + 窄窗两种形态）与 K60 竖屏：抽屉 `播放 / 手势` 两个 Tab，播放分类顶部 = `码率`（副标题 `自动`）+ `解码`（副标题 `mpv（软解兜底） · 硬解优先`）；点「码率」→ 面板标题 `码率` + 自动 / 原始画质 / 1–40 Mbps，BACK 回「播放设置」（pid 1903 不变）；点「解码」→ 面板标题 `解码` + 播放内核（ExoPlayer（硬解） / mpv（软解兜底））+ 解码策略 + 优先级文案 |
| ⑤ 倍率键仅图标、同宽 | Pad 5 键框宽 108px = 音轨 / 字幕 / 信息 / 全屏键同宽（视觉框 44dp），键内**无文本节点**，`content-desc="倍速"`；K60 `[333,1135][487,1303]`（154px）= 音轨 `[25..179]` / 字幕 `[179..333]` / 信息 `[487..641]` 同宽 |
| ⑥ 1× 位于详细信息右侧 + 可点 | Pad 5 `1×` 文本节点 `[780,1495][813,1534]` 在信息 `[626,734]` 右侧（同玻璃底 + 1dp 描边）；点它 → 抽屉 `选择播放速度` + `0.25× … 3×` 档位；选 1.5× 后文本项立即变 `1.5×`（`[767,1495][827,1534]`，`content-desc="倍速 1.5×"`），再选回 1× 复原；点倍速图标同样打开 `选择播放速度`（同一入口，无第二个面板） |
| ⑦ 既有布局 / 返回 / 比例不回归 | 右上 5 键顺序 画中画 `[2045..2144]` → 睡眠 → 选集 `[2243..2342]` → 画面 `[2342..2441]` → 设置 `[2441..2549]`；中央五键整簇 `[936..1622]`（中心 1279 ≈ 屏宽中心 1280）；锁定键贴右缘中部 `[2432,746][2540,854]`（y 中心 800 = 1564/2，右边距 20px）；选集**覆盖层**打开时 `PlayerView` 恒 `[0,0][2560,1564]`（不挤压、不右移），BACK 先收栏（`topResumedActivity` 仍 PlayerActivity、pid 1903 不变）；设置子面板 BACK 回上一级；画面比例面板（K60）正常列出 适应屏幕 / 裁剪填满 / 拉伸填满 + 旋转 0–270°；详细信息面板正常（标题 / 容器 `MKV` / 编码 `H.264（AVC）` / 分辨率 `1920 × 1080` / 时长等） |
| 稳定性 / 还原 | 两台设备整轮 `logcat`：`FATAL EXCEPTION` 0 / `ANR in` 0 / `Input dispatching timed out` 0；副作还原：Pad 5 `wm size/density` reset（Physical 1600x2560 / 360dpi）、`accelerometer_rotation=1`、`user_rotation=0`；K60 无 `wm` 覆盖、`accelerometer_rotation=1`、`user_rotation=0`；两台 force-stop、`/sdcard/w13*` `w14–w36` `k13*` `k14*` dump 已清理；码率偏好保持「自动」、倍率已回 1×、内核偏好保持会话开始时的 mpv |

### 16.5 未决 / 移交项

1. **Compact（自由窗口 / 分屏）未做真机取证**：代码与画面区版式共用同一条判据（非全屏窄窗同样隐藏 码率 / 解码、倍率仅图标、1× 在「信息」右侧），但本波没能在 MIUI 上造出真自由窗口（`wm shell splitscreen` / `am start --windowingMode` 在本机不可用）。如需像素级证据，下一波用手动「小窗」触发。
2. **Pad 5 走查期间发现系统级干扰**：MIUI「妙享桌面 / 镜像」（`com.xiaomi.mirror` + `MAGIC-POINTER` 悬浮窗）会在屏幕右缘占一条窗口，吃掉 x≈2391 附近的点击（正好覆盖右上「画面比例」键位置）——与本波改动无关；同一坐标在本波前半轮可用、后半轮被拦，K60 同键正常。后续验收脚本遇到「点了没反应」先看 `dumpsys window windows` 里有没有这个窗口。
3. 1× 文本项宽度 = `spec.toolKeySizeDp`（窄屏 38dp）；图标键因 M3 最小触摸框会扩到 44dp，故窄屏下文本项视觉上比图标键略窄（平板档两者同为 44dp）。若要求像素级等宽，把 `PlayerSpeedLabel` 的宽度改为与 `PlayerIconButton` 同源即可。**（W14 已作废本条：1× 改为文本自适应的纯展示徽标，见 §17。）**
4. 分级判据的 600dp 与 `playerControlSpec` / `PlayerFormFactor` 是同一个数但仍是三处独立常量；若日后要收敛，抽一个共享常量即可（值不变）。

---

## 17. W14-PLAYER 落地记录（2026-10-02 · 分支 `feature/w14-speed-badge`）

> 用户微调反馈：把 W13 新增的「1×」从**可点文本项**改为与右上角清晰度徽标**同款的纯展示徽标**。提交：`af0e63d`（实现）+ 文档提交；基线 master `058f5eb`（W13 已合并）。
> 本波只写 `app/phone`（`PlayerControlOverlay.kt` + `PlayerControlLayoutTest.kt`）；`AppPreferences` / `NavigationRoot` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` 未触碰，未合并 master。

### 17.1 决策补充（与 §0 同源）

| 编号 | 决策 |
|------|------|
| D36 | **1× 改纯展示徽标（不可点）**：新增 `PlayerOverlayBadge(text)` 共用组件——labelSmall 字号 + `CinefinShapes.Xs`（8dp）圆角 + 1dp `outlineVariant` 描边 + 水平 `Space2` / 垂直 2dp 内边距；无独立底色（顶 / 底由渐隐遮罩托底）。顶栏清晰度徽标改为调用同一组件（样式不可能再漂移）。倍速入口只剩左下倍率图标键一个（W11 D21「全页只有一个倍速入口」不变）；徽标保留 `contentDescription = "倍速 n×"` 供读屏与验收，但无 `clickable` / 无 `Role.Button` / 不参与选中态（激活态由倍率图标键承载，行为不变）。 |
| D37 | **底栏宽度预算改口径**：`PlayerControlSpec.bottomRowWidthDp` = 7 个工具键 + 1× 徽标最宽估值（`PLAYER_SPEED_BADGE_WIDTH_DP = 48dp`，labelSmall 11sp 下最宽档「0.25×」）+ 8 个间距；极端窄窗仍由工具行横向滚动兜底。 |

### 17.2 逐条落地与文件

| # | 反馈 | 落地方式 | 主要文件 |
|---|------|----------|----------|
| ① | 样式对齐清晰度徽标 | 抽 `PlayerOverlayBadge`；顶栏清晰度徽标（原内联 Text）改为调用它 | `PlayerControlOverlay.kt` |
| ② | 不可点击 | 删除 `PlayerSpeedLabel`（玻璃底 / 键框 / `clickable` / `Role.Button` / `selected` 全删）；新增 `PlayerSpeedBadge(speed)` = Box（仅 `semantics { contentDescription }`）+ `PlayerOverlayBadge` | 同上 |
| ③ | 位置不变 + 随倍率更新 | 两处调用点不动（键表尾部 = 「详细信息」右侧；Compact 行同样插在「信息」之后），文本仍走 `formatSpeed()` | 同上 |
| ④ | 预算 / 注释同步 | `bottomRowWidthDp` 改「7 键 + 徽标估值 + 8 间距」；键表尾注释、Compact 行注释与测试注释同步 | 同上 + `PlayerControlLayoutTest.kt` |

### 17.3 门禁（2026-10-02）

```
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest --console=plain
```

- `:app:phone:assembleDebug` ✅｜`ktfmtCheck` ✅（先 `ktfmtFormat` 修 `PlayerControlOverlay.kt`）
- `:app:phone:testLibreDebugUnitTest` ✅ **49** 项（既有基线，未新增 / 无回归）

### 17.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

素材：《灼眼的夏娜》S01E03（`itemId=5db08a9c-0ae2-5c4b-1995-9a6472a664d9`）。Pad 5 当前内核上报视频轨（顶栏有清晰度徽标，可与 1× 同屏比对）；K60 为 mpv（顶栏无清晰度徽标，按 ②③④ 逐条验证）。

| # | 证据（文本 / 数值） |
|---|--------------------|
| ① 同款样式 | **Pad 5（1600x2524 @360dpi，密度 2.25）同屏双徽标**：清晰度 `1080P` 文本盒 `[968,44][1060,83]` vs 倍速 `1×` 文本盒 `[765,2453][798,2492]` —— 文本盒高均 **40px（≈17.8dp）**；描边盒高均 **49px（≈21.8dp）**（`1080P` 描边 y39–41 / y85–87，`1×` 描边 y2448–2450 / y2494–2496，中心列实测）；文字像素峰值均 `(152,162,179) = #98A2B3`（Lumen `onSurfaceVariant`）；描边像素均 `(17,18,21)±1`（Lumen `outlineVariant` = 白 5%，随底透出：底 `(3,4,6)` → `(16,17,19)`、底 `(5,6,9)` → `(17,19,22)`）；水平内边距均 18px = 8dp（顶 950→968、底 747→765）、垂直内边距均 ≈2dp；两徽标内部像素 = 紧邻外侧涂底（`(4,6,9)`）→ **无独立底色、同一条规则**。**K60（1440x3200 @560dpi，密度 3.5）**：倍速徽标文字峰值同为 `(152,162,179)`、描边同为 `(18,19,22)±1`、描边盒高 75px（≈21.4dp，与 Pad 5 的 21.8dp 同档）、内部无底色。 |
| ② 点击 1× 不打开倍速面板 | **Pad 5**：点徽标中心 `(781,2472)` → `pid 1909 → 1909`；dump 无「选择播放速度」/档位节点；同一轮节点 `desc="倍速 1×" clickable=false`。**K60**：点 `(711,1218)` → `pid 22730 → 22730`；同样无面板、`clickable=false`。 |
| ③ 倍率图标仍可用 + 徽标随动 | **Pad 5**：点倍速图标 `(329,2471)` → 面板「选择播放速度」+ `0.25×…3×` 全档；点 `1.5×` 后（BACK 关面板）徽标变 `[747,2448][843,2497]`、`desc="倍速 1.5×"`（文本 `1.5×`，仍 `clickable=false`）；再选回 1× 复原 `[747,2448][816,2497]`。**K60**：倍速图标 `(410,1219)` 同样开出全档面板；选 `1.5×` 后 `[658,1181][805,1256]`、`desc="倍速 1.5×"`；还原 1× 复原 `[658,1181][764,1256]`（pid 全程 22730）。 |
| ④ 不回归 | **Pad 5（平板 / 宽度充足 → 6 键 + 徽标）**：右上 5 键 = 画中画 `[1087..1186]` → 睡眠 `[1186..1285]` → 播放队列 `[1285..1384]` → 画面比例 `[1384..1483]` → 播放设置 `[1483..1591]`；中央五键整簇 `[457..1143]`（中心 800 = 屏宽中心）；锁定键 `[1474,1225][1582,1333]` 贴右缘垂直居中；工具行 = 音轨 / 字幕 / 倍速 / 码率 / 解码 / 信息 + 徽标（徽标起 x=747，紧贴信息 `[626..734]` 右侧，间距 13px ≈ 6dp）。**K60（411dp 非全屏窄窗 → 方案 A 分级）**：工具行只剩 音轨 `[25..179]` / 字幕 `[179..333]` / 倍速 `[333..487]` / 信息 `[487..655]` + 徽标 `[658..764]`，无障碍树无「码率 / 解码」；右上 5 键顺序同源（`[641..1425]`）；中央五键 `[283..1158]`（中心 720.5 ≈ 屏宽中心 720）；锁定键 `[1243,587][1411,755]`、全屏键 `[1247,1135][1415,1303]` 就位。 |
| 稳定性 / 还原 | 两台设备整轮 `logcat`：`FATAL EXCEPTION` 0 / `ANR in` 0 / `Input dispatching timed out` 0；倍率已还原 1×；Pad 5 无 `wm` 覆盖（Physical 1600x2560 / 360dpi）、K60 无 `wm` 覆盖（Physical 1440x3200 / 560dpi）、两机 `accelerometer_rotation=1` / `user_rotation=0`（未改动）；两台 force-stop、`/sdcard/w14*` 临时 dump / 截图已清理；内核偏好未改动。 |

### 17.5 未决 / 移交项

1. 徽标改为纯展示后，读屏焦点中的「倍速 n×」是静态描述，倍速入口只在倍率图标键（`content-desc="倍速"`）——若后续希望读屏直接说明可点位置，可在图标键描述里补当前倍率值。
2. §16.5 第 3 条（1× 与图标键等宽）随本波作废：徽标按文本自适应，不再与键框对齐。

---

## 18. W15-LIBASS 落地记录（2026-10-02 · 分支 `feature/w15-libass`）

> 目标：§1.18 libass 特效字幕。基线 `master ed303f9`；本波只写 `player/core`（新增 extras 常量）与 `player/local`；
> `AppPreferences.kt` / `NavigationRoot.kt` / `settings.gradle.kts` / `libs.versions.toml` 未触碰，**未引入任何新依赖**。
> 提交：实现 + 文档（见 `git log --oneline feature/w15-libass`）。

### 18.1 方案调研与决策（D38–D40）

| 编号 | 决策 |
|------|------|
| D38 | **mpv 原生 libass 路径为本波唯一实现**。mpv 0.41.0（`dev.jdtech.mpv:libmpv:1.0.0`）的 `libmpv.so` 已静态内含 libass / harfbuzz / fribidi / fontconfig（本地二进制符号核验），DirectPlay 让 mpv 直接读容器内嵌 ASS，零新增依赖、零 APK 体积增量、与 mpv 渲染同一线程。 |
| D39 | **转码场景按需注入服务端字幕**：mpv 在 `FILE_LOADED` 后查 `track-list`——存在 `external=false` 的字幕轨（DirectPlay mkv 内嵌）就不动；没有（服务器转码 / HLS 容器无字幕）才把 MediaItem extras 里的 Jellyfin 内嵌文本字幕 `sub-add` 给 mpv（`flags=auto` + title/lang），由 libass 渲染 `Stream.ass` 原始特效。两条路径都保证同一媒体不会出现容器轨 + 注入轨重复。 |
| D40 | **Exo 路径保持现状**：Media3 1.11.1 的 `SsaParser` 只输出基础 Cue（能解析 `[V4+ Styles]` 的部分字段，但不支持 ASS override tags / 动画 / `\pos` 定位），官方也没有 ASS 实时渲染计划。为 Exo 引入 libass 需要新增原生依赖（红线要求先报告负责人），本波只完成调研：候选 `io.github.peerless2012:ass-media:0.5.1`（MIT，libass ISC，Jellyfin Android TV / 多个播放器在用；Media3 effect/overlay 集成；arm64 `libass.so` ≈2.9MB + `libc++_shared` ≈1.2MB；其依赖基线是 Media3 1.8.0，与项目 1.11.1 的兼容性未验证）。批准后单独开波接入。 |

**为什么 mpv 侧不必改样式开关**：mpv `--sub-ass-override` 默认 `scale`（`sub/sd_ass.c` 核验）：只把 `sub-pos` 当默认行位置、把 `sub-scale`/字号做选择性缩放（对话生效、typesetting 保留），**不覆盖字体名 / 颜色 / 边框**。所以现有「大小」档位对 ASS 对白仍生效，「颜色 / 背景 / 描边」在 ASS 上自动让位给脚本，避免强覆盖破坏特效。

### 18.2 实现（文件 + 行为）

| 文件 | 改动 |
|------|------|
| `player/core/.../models/PlayerItem.kt` | 新增 `PLAYER_EXTRA_SUBTITLE_SOURCES`（MediaItem extras 携带全量字幕源，Parcelable ArrayList） |
| `player/local/.../presentation/PlayerViewModel.kt` | ① `toMediaItem()` 把 `subtitleSources` 放进 extras（Exo 不读，仅 mpv 兜底用）；② mpv 字幕模式联动：起播 / 换集 / 面板切换时 `off → setSubtitleAutoSelect(false)`、`auto·always → setSubtitleAutoSelect(true)`；③ 自研覆盖层清单抽成 `playerSubtitleSourcesForBackend()` 纯函数（mpv → 空 = 让位） |
| `player/local/.../mpv/MPVPlayer.kt` | `prepareMediaItem` 记录 pending 清单；`FILE_LOADED` 后 `maybeAddServerSubtitles()`：解析 track-list 判内嵌轨 → 无则 `sub-add`（命令线程保序）→ 按 App 意图 `set sid auto|no`；新增 `setSubtitleAutoSelect()`；`sub-add` 命令仍走既有 `postCommand` 的 `runCatching`（失败只记日志，不崩 App） |
| 单测 | `player:local` 31 → **39**：`MPVServerSubtitleTest`（内嵌文本才注入 / 外挂不重复 / 图形与空 URL 跳过 / 内嵌轨识别）+ `PlayerSubtitleRoutingTest`（mpv 让位、Exo 保留清单） |

### 18.3 门禁（2026-10-02）

```
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest --console=plain
```

- `:app:phone:assembleDebug` ✅｜`ktfmtCheck` ✅（中间执行过 `ktfmtFormat`）
- `:app:phone:testLibreDebugUnitTest` ✅ **49** 项（既有基线，未改 app 模块）
- `:player:local:testDebugUnitTest` ✅ **39** 项 = 既有 31 + 新增 8

### 18.4 真机走查（K60 `8e875894`，命令全部带 `-s`）

素材：《夏日幽灵》`itemId=0b834979-9e2e-8c13-9014-783cfd83e7ed`（内嵌 3 条 ASS：日本語 / 简日双语 / 繁日雙語）。

| # | 证据（文本 / 数值） |
|---|--------------------|
| ① DirectPlay 不重复注入 | mpv 直连 mkv：字幕面板主 / 次字幕各 3 条（日本語 / 简日双语 / 繁日雙語），日志**无**注入记录（容器已有内嵌轨 → 跳过）；点「简日双语」→ `mpv 字幕状态: sid=2 visible=true delay=0.0 text=そんな顔してどうした`。 |
| ② 转码缺口修复（核心） | 3 Mbps 档 → HLS `…/hls1/main/*.ts` + `TranscodeReasons=ContainerBitrateExceedsLimit`。修复前基线：面板「当前媒体没有可调节的字幕」（0 条）；本波日志 `mpv 容器无内嵌字幕（转码等），注入 3 条服务端文本字幕` + `mpv 注入字幕：日本語（ja / ass）…`，面板恢复 3 条 ASS。 |
| ③ App 覆盖层让位 | 转码 + 字幕显示中执行 `uiautomator dump`：`subtitle_overlay_compose` 节点存在但内部文本节点 **0**、整棵树无字幕文本 → 字幕像素只可能来自 mpv 视频层（libass），不存在两套字幕叠加。 |
| ④ 像素对比（位置 / 样式） | 同暂停帧「开字幕（sid=2）/ 关字幕（sid=no）」：`screencap` 逐像素 diff = **281,568** 个采样点（步长 2，画面区 1440×1341），bbox **[0,266][1438,1284]**，平均色 (87,164,191)——简日双语 ASS 含覆盖画面中下部的大范围特效，关字幕后整块消失。对照：关字幕帧连续两张（间隔 4s）diff=0、关字幕帧与稳定对照帧 diff=0，证明暂停画面完全静止，差异全部来自字幕渲染。 |
| ⑤ 延迟 / 开关 / 模式联动 | 点 `+0.1s` → `mpv 字幕延迟 = 100 ms` + `delay=0.10000000149011612`；字幕模式 `关闭` → `sid=no text=null`，`自动` → `sid=2` 按 `slang`（zh-Hans 优先）重选；面板「关闭字幕」行同样 `sid=no`。 |
| ⑥ 性能采样（转码 + ASS） | SurfaceFlinger `--latency` 30s：**811 帧样本，p50 41.55ms / p95 57.99ms / p99 59.66ms / 平均 24fps / jank 0%**（阈值 74.79ms）；关闭字幕对照 20s：518 样本 p50 41.42 / p95 58.15 / p99 60.21 / 24fps / jank 0%。PSS：有字幕 447,621kB vs 无字幕 455,619kB（波动范围内，libass 未见额外内存压力）。 |
| ⑦ 稳定性 / Exo 回归 / 还原 | 整轮 `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out` 均 **0**；切回 ExoPlayer 打开同片：面板仍列 3 条 `ASS · 内嵌`（自管清单），进度正常推进、无崩溃（extras 改动不影响 Exo）。设备还原：`pref_player_backend=mpv`、删除临时 `pref_player_streaming_bitrate` 键、`/sdcard/w15*.xml` 与 prefs 备份已清理、force-stop。 |

### 18.5 未决 / 移交项

1. **ExoPlayer 路径 libass 引入待批准**：候选 `ass-media 0.5.1` 的 Media3 1.8.0 → 1.11.1 兼容性、`OVERLAY_OPEN_GL` 在 K60 / Pad 5 的性能未验证；属新依赖，按红线需负责人拍板后单独开波。
2. **sub-add 轨的 `lang` 仍是 PlaylistManager 归一化值**（本轮日志为 `ja` / `zh`）：简繁区分目前靠 mpv 自身匹配；若要精确到 `zh-Hans` / `zh-Hant`，需在 `LanguageMatcher` / `PlaylistManager` 侧细化（跨播放器线影响面较大，未夹带）。
3. 次字幕（`secondary-sid`）按 mpv 语义 `strip` 样式（只显示纯文本），主字幕保留 ASS 特效；ASS 内嵌字体（MKV attachment）在转码场景 Jellyfin 不交付，依赖系统 / mpv fontconfig 回退。
4. mpv 侧「记住手动选轨」仍未做（`manualTrackSelectionMediaId` 目前只在 Exo 路径生效）；本波未做「sub-add 网络失败」的真机注入验证（代码路径由 `postCommand` 的 `runCatching` + 命令线程保护兜底）。

## 19. W16-PLAYER 落地记录（2026-10-02 · 分支 `feature/w16-exo-libass-decode`）

> 目标（用户决定）：①**Exo 内核（含硬解）也要引入 libass**，ASS/SSA 特效字幕在 Exo 路径同样生效；
> ②**SRT 也覆盖**（libass 路径与既有渲染都要保证 SRT 正常，延迟 / 开关 / 语言 / 外观不回归）；
> ③解码优先级改为 **本地硬解 → 服务器解码/转码 → 本地软解**（与 W12 相反），Exo 硬解失败能自动进下一档、不崩溃。
> 基线 `master c365c7c`；本波写 `player:local` / `app:phone` / `data`（PlaybackInfo 参数）/ `settings`（`AppPreferences` 只追加 + 新增纯函数）；
> 咽喉 `libs.versions.toml` 与 `player/local/build.gradle.kts` 由本会话写（另在 `app/phone/build.gradle.kts` 加了原生库去重与合并补丁）；`NavigationRoot.kt` / `settings.gradle.kts` 未触碰。

### 19.1 方案调研与决策（D41–D43）

| 编号 | 决策 |
|------|------|
| D41 | **Exo 路径引入 `io.github.peerless2012:ass-kt:0.5.1`（libass ISC + MIT 包装），走「App 驱动」渲染**。选它而不是 media3 集成的 `ass-media`：① `ass-kt` **不依赖 Media3**（无 1.8.0 → 1.11.1 兼容风险，本波实测直接编译通过）；② 自研字幕管线的延迟 / 双语 / 选轨 / 外观在 Media3 轨道体系里做不了，`ass-media` 的 renderer 只跟轨道选择走；③ 原生库 arm64 `libass.so` ≈3.0MB + `libc++_shared` ≈1.2MB。**依赖已获批**，只加在 `libs.versions.toml` + `player/local/build.gradle.kts`。 |
| D42 | **字幕渲染路由**：Exo 主字幕统一交给 libass——ASS/SSA 原文透传（定位 / 字体 / 特效由脚本还原），SRT / WebVTT 由 `AssSubtitleScript.forCues()` 生成 ASS（字号 = 0.042×PlayResY×大小档位、位置 = MarginV、描边 = Outline、背景 = BorderStyle 3）。**归属**：延迟 / 开关 / 语言选择 / 大小倍率在 App 层（libass 只画）；ASS 的颜色 / 背景 / 位置 / 描边让位给脚本（与 mpv `sub-ass-override=scale` 一致）；SRT 的全部外观档位写进生成的 ASS 样式；次字幕仍纯文本（与 mpv `secondary-sid` strip 语义一致）。渲染基准 = 视频实际显示矩形（`currentVideoRect()`，跟随 FIT / 裁剪 / 旋转）。 |
| D43 | **解码回退链**（`settings/domain/PlayerDecodeFallback.kt` 纯函数 + 单测）：`自动` 档硬解失败 → 第 2 档**强制服务器转码**（`enableDirectPlay / enableDirectStream / allowVideoStreamCopy / allowAudioStreamCopy = false` + 声明 h264/aac 转码 profile，逼服务器重编码）→ 再失败 → 第 3 档**本地软解**（mpv `hwdec=no`，Exo 侧对应扩展渲染器优先）。用户选具体 Mbps 时服务器本来就在转码 → 直接落软解；「原始画质」= 只直连 → 也直接落软解。档位落盘 `pref_player_decode_fallback_stage`（0/1/2）+ `pref_player_decode_fallback_media_id`，换条目 / 用户显式改码率·内核·解码策略时清零（防死循环）。 |

### 19.2 实现（文件 + 行为）

| 文件 | 改动 |
|------|------|
| `gradle/libs.versions.toml`、`player/local/build.gradle.kts` | 新增 `ass-kt:0.5.1` 依赖（唯一咽喉文件改动） |
| `app/phone/build.gradle.kts` | `packaging.jniLibs.pickFirsts` 去重 `libc++_shared.so` + `merge*NativeLibs` 合并后用 **libmpv 的新版覆盖**（ass-kt 旧版缺 `__from_chars_floating_point`，会让 `libmpv.so` dlopen 失败，见 §9） |
| `player/local/.../subtitle/LibassSubtitleRenderer.kt`（新） | libass 包装：`load(脚本, 字号, storage/frame 尺寸)`、`renderFrame(ms)`、失败标记与日志；参数变化整体重建（绕开 libass 同时间戳帧缓存，§9）；全部 `runCatching`（含 `UnsatisfiedLinkError`）——失败只标记，不崩 |
| `player/local/.../subtitle/AssSubtitleScript.kt`（新，纯函数） | SRT/VTT cue → ASS 脚本（时间戳 / `&HAABBGGRR` 颜色 / 字号 / 边距换算 / 文本转义）+ ASS 原文归一化 |
| `player/local/.../subtitle/PlayerSubtitleController.kt` | 下载后同时产出 cue 列表与 ASS 原文（ASS/SSA 才有）；`SubtitleOverlayState` 新增 `primaryAssScript`；缓存改为 `LoadedSubtitle` |
| `app/phone/.../presentation/player/PlayerSubtitleOverlay.kt` | 每帧循环：主字幕走 libass（脚本 + 字号 + 存储 / 渲染尺寸变化时重建），画到视频显示矩形；次字幕 / libass 失败回退走既有 Compose 文本；`libass 字幕就绪` / 失败日志 |
| `app/phone/.../PlayerActivity.kt` | `currentVideoRect()`：画面显示区像素矩形（内容框 + 视图旋转 / 缩放，裁剪到画面区），供 libass 定位；解码回退事件处理（`RestartWithServerTranscode` / `FallbackToSoftware`）；用户显式改码率 / 内核 / 解码策略时清回退档位 |
| `settings/.../PlayerDecodeFallback.kt`（新，纯函数） | 档位规范化 / 下一档判定 / `forcesServerTranscode` / `forcesLocalSoftware` / 优先级表 |
| `settings/.../AppPreferences.kt` | 只追加 `playerDecodeFallbackStage` / `playerDecodeFallbackMediaId` 两个键 |
| `player/local/.../domain/PlayerDecodeMode.kt`、`presentation/PlayerHolder.kt` | `effectiveMode(mode, 回退档位)`：软解档强制软解（Exo 扩展渲染器优先 / mpv `hwdec=no`），不改用户偏好 |
| `player/local/.../presentation/PlayerViewModel.kt` | `handleCodecFallback()`（硬解失败 → 转码 / 软解）、`clearDecodeFallback()`、换条目清档位、新事件；解码策略注释更新 |
| `data/.../JellyfinRepositoryImpl.kt` | 转码档位时禁直连 / 直传 / 流拷贝并声明转码 profile；日志加 `forceTranscode` |
| `player/local/res/values{,-zh-rCN}/strings.xml` | 优先级文案改「本地硬解 → 服务器解码 / 转码 → 本地软解」+ 硬解说明 + 当前档位行 |
| 单测 | `PlayerDecodeFallbackTest`（6）、`AssSubtitleScriptTest`（5）、`PlayerDecodeModeTest`（+2）= player:local 39 → **52** |

### 19.3 门禁（2026-10-02）

```
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest --console=plain
```

- `:app:phone:assembleDebug` ✅（arm64 分包 103.8MB = 基线 + ≈3.1MB libass/原生库）｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **49** 项（基线未改）
- `:player:local:testDebugUnitTest` ✅ **52** 项 = 既有 39 + 新增 13
- APK 内 `lib/arm64-v8a/libc++_shared.so` = **libmpv 版**（1374336B / `C4C2FE5C…`，非 ass-kt 的 1253544B）——原生库冲突修复的直接证据

### 19.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

素材：ASS 特效《齐木楠雄的灾难》`be79c27f-05cf-8ded-6ce1-e6e8d78e8b4d`（外挂 ASS，含定位 / 描边特效）；
SRT《齐木楠雄的灾难 始动篇》`a35b7ea6-cdb3-ce23-966f-5798305028e7`（内嵌 17 轨含简中 SRT）；
Hi10P《学生会的一己之见》`32074ae5-0847-c53c-1d14-9bd32383eec4`（h264 10-bit，Exo 硬解必失败）。

| # | 证据（文本 / 数值） |
|---|--------------------|
| ① Exo + libass（ASS 特效） | `W/SubtitleRenderer: libass API version: 0x1704000`（原生库加载）→ `I/PlayerSubtitleOverlay: libass 字幕就绪：ASS 原文，script=46860 bytes，frame=2560x1440，storage=1920x1080，fontScale=1.00`；暂停帧「开 / 关字幕」像素 diff：**视频区 54,540 个采样点（步长 2）**、区域 [850,184]–[1878,1598]，字幕带截图确认对白「这是异常气象 是冰河期啊」由 libass 绘制；字体回退日志 `fontselect: (方正准圆_GBK) -> /system/fonts/MiSansVF.ttf` |
| ② SRT 走 libass | 面板选「Simplified Chinese（SUBRIP · 内嵌，index=14）」→ `字幕解析完成: index=14, cues=528, ass=false` → `libass 字幕就绪：**SRT 生成脚本**，script=35542 bytes`；开 / 关字幕像素 diff **55,045**（视频区，含简体对白）；语言选择 / 延迟 / 开关全部沿用既有面板且生效 |
| ③ 外观不回归 | 大小档位 75% ↔ 200% 播放中即时切换：日志 `fontScale=0.75` / `2.00`，暂停帧视频区 diff **29,952**（区域 [422,1298]–[1398,1484] = 字幕带），截图对比字号明显变化（修复了 libass 帧缓存导致的「改了不动」，§9）；ASS 的颜色 / 位置 / 描边按设计让位给脚本 |
| ④ 解码回退链（Pad 5） | 自动档 + Hi10P：`getMediaSources … forceTranscode=false profiles=0` → `Player error on backend=exoplayer: ERROR_CODE_DECODING_FAILED` → `解码能力不足（…），先请求服务器解码/转码重试（优先级：本地硬解 → 服务器转码 → 本地软解）` → `Restart player (fallback=server-transcode)` → `forceTranscode=true profiles=1` + `master.m3u8?VideoCodec=h264`；服务器会话 `TranscodingInfo{IsVideoDirect=False, Bitrate=6667836, Container=ts, VideoCodec=h264}`，播放 `state=3` 不再降级（修复了「转码流仍是 10-bit 直拷贝」的坑，见 §9）；档位落盘 `pref_player_decode_fallback_stage=1`。 |
| ⑤ 第 3 档本地软解 | 码率选「原始画质」后重放同一 Hi10P：`getMediaSources bitrate=-1 … transcoding=false forceTranscode=false profiles=0` → `解码能力不足（…），服务器转码不可用/已用尽，降级到本地软解（mpv hwdec=no）` → `Restart player with backend=mpv`；`pref_player_decode_fallback_stage=2`、`pref_player_backend=mpv`；mpv 正常起播（`state=3`，`Estimated source FPS: 23.923`）。 |
| ⑥ 解码面板文案 | 面板文本：`播放内核 | ExoPlayer（硬解） | mpv（软解兜底） | 解码策略 | 硬解优先 | 本地硬解，失败时先请求服务器转码，再回退本地软解 | 仅软解 | 当前档位：本地硬解 | 优先级：本地硬解 → 服务器解码 / 转码 → 本地软解`；选内核 / 策略 / 码率都会清回退档位（下一次重置为「当前档位：本地硬解」）。 |
| ⑦ 性能 / 内存（Pad 5，Exo + ASS 特效） | SurfaceFlinger `--latency` 30s：**821 帧 / present 24.2fps / p50 41.67ms / p95 50.07ms / p99 50.17ms / jank 0%**（阈值 74.79ms），`ready_jank 0.12%`；PSS **329,994kB**。 |
| ⑧ K60 复验 | 切换 Exo 后同片：libass 生效（截图对白「静默的囚徒被囚之身」）；Hi10P 链路同 Pad 5（`forceTranscode=true profiles=1`、`stage=1`、`state=PLAYING(3)`）；mpv 路径回归正常（`MPVPlayer: Starting playback...`，无 `UnsatisfiedLinkError`）。 |
| 稳定性 / 还原 | 两台设备整轮 `FATAL EXCEPTION` / `ANR` / `UnsatisfiedLinkError` 均 **0**；副作已还原：Pad 5 `pref_player_backend=exoplayer`、`streaming_bitrate=0`、`decode_fallback_stage=0`、`subtitle_style_size=1`、`subtitle_delay=0`（= 会话开始状态）；K60 回到 `backend=mpv`、`subtitle_mode=off`、档位 0；两机均 force-stop、`wm size` 为 Physical、`accelerometer_rotation=1`、`/sdcard/w16*` 清理干净。 |

### 19.5 未决 / 移交项

1. **许可证归属**：新增 libass（ISC）/ ass-kt（MIT）原生库，release 前需在 `NOTICE` / aboutlibraries 里补来源声明（本波红线只允许动依赖声明，未夹带）。
2. **libc++ 覆盖是构建补丁**（`merge*NativeLibs` 的 `doLast`）：若日后升级 libmpv / ass-kt 或 AGP 改变合并顺序，需要重新核对 APK 内 `libc++_shared.so` 的来源（校验方法见 §9）。
3. **SRT 背景 + 描边互斥**：libass 样式里 `BorderStyle=3`（背景框）与文字描边不能同时表达，两者都选时以背景框优先（生成逻辑注释已写，观感差异最小）。
4. 次字幕（双语）在 Exo 路径仍走纯文本（效果与 mpv 的 strip 一致）；ASS 的 `\pos` / 字体 / 特效在 KTV 型特效字幕（`\k`）上的表现未单独取样。
5. 未做「libass 初始化失败」的真机注入（代码路径：`LibassSubtitleRenderer` 全 `runCatching` + `failed` 标记 → 覆盖层切回文本渲染 + `libass 渲染不可用` 日志）。

### 19.6 构建补丁补记：TV 端同步 libc++ 覆盖（CI 修复，2026-10-02）

- 现象：CI 的根 `assembleDebug` 在 `:app:tv:mergeLibreDebugNativeLibs` 失败——`lib/arm64-v8a/libc++_shared.so`
  同时来自 libmpv 与 ass-kt（TV 模块也依赖 `player:local`），而 W16 初版只在 `app/phone` 做了 `pickFirsts` + libmpv 覆盖。
- 修复：`app/tv/build.gradle.kts` 与手机端同源补齐（`packaging.jniLibs.pickFirsts` + `merge*NativeLibs` 的 libmpv 覆盖 `doLast`）。
- 验证：本机根 `assembleDebug`（与 CI 同命令）BUILD SUCCESSFUL；TV APK 内 `lib/arm64-v8a/libc++_shared.so` = 1,374,336B 且含
  `__from_chars_floating_point`（与手机端一致）。CI 修复后重跑以 run 结果为准。

---

## 20. W17-PLAYER 落地记录（2026-10-02 · 分支 `feature/w17-player-labels-fallback`）

> 用户第六轮播放页反馈（6 项）：回退链修复 / 解码面板简化 / 设置去重 / 中央播放键样式 / 工具区加文字 / 横屏全屏键命中。
> 基线 master `0460235`（W16 已合并）。本波写 `player:local`（回退状态机 + `MPVPlayer` 日志）、`app:phone`（播放页 UI / Activity）
> 与 `player:local` 的字符串资源；`AppPreferences.kt` 只读不写，`NavigationRoot` / `settings.gradle.kts` / `libs.versions.toml` /
> `app/*/build.gradle.kts` 未触碰；未合并 master。

### 20.1 决策补充（与 §0 同源）

| 编号 | 决策 |
|------|------|
| D44 | **回退链的「真实切内核」由 Activity 显式指定目标，不再用 toggle**：第 3 档只写 `pref_player_decode_fallback_stage=2`，由 `FallbackToSoftware` 事件触发 `switchBackendAndRestart(mpv)`（内部 `setBackend(mpv)` → 重建 mpv 实例 → `hwdec=no`）。同时删除「同一媒体只自动降级一次」的 `autoFallbackMediaId` 拦截，防死循环完全交给档位状态机（stage 2 不再推进）；第 1 档只接 codec 类错误，第 2 档（服务器转码流）失败时不再判断错误码，一律落第 3 档，保证「只有全部失败才提示错误」。 |
| D45 | **工具区文字化 + 6 键恒定**：右上 5 键（画中画 · 睡眠 · 选集 · 画面 · 设置）与左下 6 键（音轨 · 字幕 · 倍速 · 码率 · 解码 · 详细信息）在图标下加 10sp 小字，键框 56×58dp（图标 ×0.82 缩小）；判据 `playerToolLabelsVisible(isFullscreen, widthDp, formFactor, isCompact)`——全屏 / ≥600dp / 平板 · 折叠显示文字，非全屏窄窗与 Compact 只留图标。W13 的「非全屏窄窗隐藏 码率 / 解码」作废：6 键在任何形态都可用（窄屏只去掉文字），设置面板里 W13 加的两行兜底入口按用户确认**完全删除**。右下全屏键 / 中央五键 / 右缘锁定键保持纯图标。 |
| D46 | **中央播放键统一玻璃语言**：`PlayerPlayKey` 从「月白填充 + 深色图标」改为「黑 28% 玻璃底 + 1dp 白 16% 描边 + OnSurface 图标」，与 `PlayerTransportButton` / `PlayerIconButton` 同一套规则；只靠尺寸（56/70dp）与圆角 Lg 保持主行动辨识度。 |
| D47 | **横屏全屏键命中修复**：平板 / 折叠展开的选集栏是覆盖在画面右缘的 320dp 面板；底栏原先铺满整窗，右下角全屏键会被面板盖住（真机实测点击无任何反应）。改为侧栏展开时**底栏整体让出侧栏宽度**（`Modifier.padding(end = sidePanelWidth)`），全屏键落到面板左侧始终可点；全屏态侧栏收起，底栏仍铺满整窗。 |

### 20.2 实现（文件 + 行为）

| 文件 | 改动 |
|------|------|
| `player/local/.../presentation/PlayerViewModel.kt` | `onPlayerError` 改为「回退链接管则直接返回」；`handleCodecFallback` 返回 Boolean（true=已接管）、删除 `autoFallbackMediaId`、第 3 档不再写 backend 偏好、stage=1 任意错误落软解、stage=2 停留并交给错误卡片 |
| `app/phone/.../PlayerActivity.kt` | `switchBackendAndRestart(target: String?)`：显式目标时 `setBackend(target)`，先读续播位置再写偏好；`FallbackToMpv` / `FallbackToSoftware` 显式传 mpv；错误卡片「改用 X 内核」带上目标内核 |
| `app/phone/.../presentation/player/PlayerControlOverlay.kt` | `PlayerPlayKey` 玻璃化；`PlayerIconButton` 新增 `label`（图标 + 10sp 小字，键框加宽 12dp / 加高 14dp）；工具簇 / 底栏传 `showLabels`；左下 6 键恒定齐全；新增 `playerToolLabelsVisible` 纯函数（删除 `playerToolRowShowsSecondaryKeys` / `playerToolRowVisibleKeys`）；底栏宽度预算新增 `bottomRowWidthDp(showLabels)`；`sidePanelInset` 让位；`onSwitchBackend` 改带目标参数 |
| `app/phone/.../presentation/player/PlayerSettingsPanel.kt` | 「设置 → 播放」删除 码率 / 解码 两行与 `streamingBitrateCaption` / `decodeCaption`；解码面板删除「优先级」Text，保留「当前档位」 |
| `player/local/res/values{,-zh-rCN}/strings.xml` | 内核名改纯 `ExoPlayer` / `mpv`；删除 `player_controls_decode_priority`；新增 `player_controls_label_details`（详细信息 / Details） |
| `player/local/.../mpv/MPVPlayer.kt` | 初始化时打印 `MPVPlayer hwdec=… vo=…`（回退链第 3 档验收证据） |
| `app/phone/src/test/.../PlayerControlLayoutTest.kt` | toolRow 分级断言改为「文字可见性 + 6 键恒定」；新增 Compact 不加文字、加文字后 600dp 宽度预算两项；51 项全绿 |

### 20.3 门禁（2026-10-02）

```
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest --console=plain
```

- 根 `assembleDebug`（含 `:app:tv`）✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **51** 项 = 既有 49 + 新增 2
- `:player:local:testDebugUnitTest` ✅ **52** 项（既有，未改动）

### 20.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

素材：Hi10P《学生会的一己之见》`32074ae5-0847-c53c-1d14-9bd32383eec4`（h264 10-bit，Exo 硬解必失败）。

| # | 证据（文本 / 数值） |
|---|--------------------|
| ① 链路第 1 档：本地硬解失败 | Pad 5：`Player error on backend=exoplayer: ERROR_CODE_DECODING_FAILED` → `解码能力不足（ERROR_CODE_DECODING_FAILED），先请求服务器解码/转码重试（优先级：…）` → `getMediaSources bitrate=0 … forceTranscode=true profiles=1` → `Changed player state to ExoPlayer.STATE_READY`；落盘 `pref_player_decode_fallback_stage=1`、`pref_player_backend=exoplayer`；此时 `uiautomator` 全树**无「播放失败」节点**（第 1 档失败不弹错误）。 |
| ② 链路第 2 档：服务器解码 / 转码 | 同上：`forceTranscode=true` + `Stream url … master.m3u8?…allowVideoStreamCopy=false`，服务器会话转码（W16 §19.4 ④ 已核 `TranscodingInfo{IsVideoDirect=False}`）；Exo 正常播放，不再降级。 |
| ③ 链路第 3 档：实际切 mpv + `hwdec=no` | **Pad 5（原始画质路径）**：`Restart player (streaming bitrate=-1) from position=26251` → `ERROR_CODE_DECODING_FAILED` → `服务器转码不可用/已用尽，降级到本地软解（mpv hwdec=no）` → `Restart player with backend=mpv from position=26251` → `MPVPlayer hwdec=no vo=gpu-next`；`stage=2` / `backend=mpv`。**K60**：同片同路径 `Restart player with backend=mpv from position=31000` + `MPVPlayer hwdec=no` ×2；`stage=2` / `backend=mpv`。 |
| ④ 第 2 档失败也落第 3 档（W17 新逻辑） | Pad 5 在 stage=1（Exo 转码流）播放中注入一次 Wi-Fi 断连：`Player error on backend=exoplayer: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED` → `解码能力不足（…），服务器转码不可用/已用尽，降级到本地软解（mpv hwdec=no）` → `Restart player with backend=mpv from position=188679`；`stage=2` / `backend=mpv`；恢复网络后 `uiautomator` 无「播放失败」节点。修复前该场景会被 `autoFallbackMediaId` 拦住、卡在 Exo。 |
| ⑤ 解码面板文案 | Pad 5 与 K60 `uiautomator`：`播放内核 / ExoPlayer / mpv / 解码策略 / 硬解优先 / 仅软解 / 当前档位：本地硬解`；**无**「优先级：…」节点；内核名后无括号说明。 |
| ⑥ 设置 → 播放去重 | Pad 5：面板文本 = `播放 / 手势 / 后台播放 / 跳过片头片尾按钮 / 自动跳过片头片尾 / 进度条显示章节刻度 / 播完暂停 / 循环模式`，**无**「码率」「解码」两行（W13 兜底入口已删）。 |
| ⑦ 中央播放键样式统一 | Pad 5 暂停帧像素采样（截图本地查看后即删）：播放键内部 `(119,156,168)` vs 键外 `(156,213,230)`；传输键「快退」内部 `(162,177,181)` vs 键外 `(215,246,254)`；锁定键内部 `(158,181,186)`。三键内部均为比背景**压暗 ≈24–27%** 的玻璃底（旧版播放键为月白填充 ≈`(242,245,249)`），描边同为白 16%。 |
| ⑧ 工具区 11 键文字 | **Pad 5 横屏（非全屏，宽 1137dp）**：右上 5 键 text = 画中画 `[1945,89][2011,118]` / 睡眠 / 选集 / 画面 / 设置；左下 6 键 text = 音轨 `[86,1509][130,1538]` / 字幕 / 倍速 / 码率 / 解码 / 详细信息 `[784,1509][872,1538]`；键框 `126×131px = 56×58dp`（density 2.25）。右下全屏键 `content-desc="进入全屏"` **无 text 子节点**。 |
| ⑨ 窄屏只留图标 | **K60 竖屏（411dp 非全屏窄窗）**：右上 / 左下全部只有 `content-desc`（画中画 / 睡眠 / 播放队列 / 画面比例 / 播放设置 / 选择音轨 / 选择字幕轨 / 倍速 / 码率 / 解码 / 信息），**无 text 子节点**；键框 `154×168px`（M3 最小触控 44×48dp，视觉 38dp）；右下全屏键纯图标；中央五键 + 右缘锁定键全程无文字。 |
| ⑩ 横屏退出全屏可点 | **修复前复现**：Pad 5 全屏 + 选集覆盖层展开（侧栏从 x=1840 起）时，旧版退出全屏键位于 `[2410,1445][2518,1553]`（面板之下），点击无 `player fullscreen=` 日志；**修复后**同场景键位 `[1690,1445][1798,1553]`（侧栏左侧），点击 → `player fullscreen=false`；随后每次 dump 定位按键连点 20 次：`tapped=20 events=20`，状态序列严格 `true,false,true,false…`（20 次全生效）。 |
| 稳定性 / 还原 | 两台设备整轮 `logcat`：`FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out` / `UnsatisfiedLinkError` 均 **0**。副作还原：Pad 5 `backend=mpv`、`streaming_bitrate=0`、`decode_fallback_stage=0`、`decode_fallback_media_id=""`、`subtitle_mode=auto`；K60 `backend=mpv`、`streaming_bitrate=0`、`stage=0`、`media_id=""`、`subtitle_mode=off`、`mpv_hwdec=mediacodec`；两机 force-stop、`/sdcard/w17_*.xml` 清理、K60 旋转设置回 `accelerometer_rotation=1` / `user_rotation=0`（Pad 5 未改 wm / 旋转）。 |

### 20.5 未决 / 移交项

1. **Compact（自由窗口 / 分屏）未真机取证**：判据 `playerToolLabelsVisible(isCompact=true) = false` 与单测已钉死，但 MIUI 上未造出真自由窗口；非全屏窄窗（K60 411dp）已覆盖「无文字」形态。
2. **mpv 内核错误不上报**：本波「只有全部失败才提示错误」指回退链的 Exo 两档；第 3 档 mpv 自身失败仍不会弹错误卡片（`MPVPlayer.getPlayerError()` 恒 null、无事件，属既有内核限制）。若要让 mpv 失败也提示，需要给 `MPVPlayer` 增加错误事件上报（下一波）。
3. **Wi-Fi 断连是验收注入手段**（第 2 档失败场景），不是用户日常场景；建议用户用真实超规格片源复测三段链路。
4. **中央播放键改用玻璃底后主行动辨识度只靠尺寸 / 圆角**；若后续用户觉得不够醒目，可在不引入媒体色的前提下加深描边或加大尺寸。

---

## 21. W18-PLAYER 落地记录（2026-10-02 · 分支 `feature/w18-manual-fallback-label`）

> 用户实测反馈两项：①**手动在解码面板切到 ExoPlayer 后，解码失败没有自动切回 mpv**（要求：手动选内核不能关闭自动回退链，
> 失败仍按「本地硬解 → 服务器解码/转码 → mpv 软解」逐级下降，只有全部失败才提示错误）；②解码面板「当前档位」文案要不带内核歧义。
> 基线 master `8b7a7cc`（W17 已合并）。本波写 `player:local`（回退判定 / mpv 错误上报 / 字符串）、`app:phone`（播放页内核切换路径 /
> 面板文案）与 `settings`（回退判定纯函数）；`AppPreferences.kt` 只读、`NavigationRoot.kt` / `settings.gradle.kts` /
> `libs.versions.toml` / `app/*/build.gradle.kts` 未触碰；未合并 master。

### 21.1 复现与定位（Pad 5 + K60）

1. **手动切 ExoPlayer 的基线与边角**：从解码面板手动切 ExoPlayer（含「先自动落到 mpv 软解档、再手动切回 Exo」的路径）后，
   硬解失败**会**进链路（实测 `ERROR_CODE_DECODING_FAILED` → `forceTranscode=true profiles=1` → `STATE_READY`，断网注入后再真实落
   `backend=mpv` + `hwdec=no`）。真正会让「手动切内核后不回退」复现的是三处状态机缺口：
   - **错误卡片切内核不清档位**：`switchBackendAndRestart`（错误卡片「改用 X 内核」）不调用 `clearDecodeFallback`，
     残留档位会让下一次失败直接命中末档（不降级）或跳过服务器转码；
   - **续播位置被读成 0**：解码面板的 `restartWithBackend` 先写 `playerBackend` 偏好再读位置——`PlayerHolder.player` getter
     会按新偏好**原地重建实例**，位置随即变 0（W17 的 `switchBackendAndRestart` 已按「先读后写」处理，两条路径不一致）；
   - **手动 mpv 失败不可见**：`MPVPlayer` 从不上报错误（`getPlayerError()` 恒 null、无事件），「手动 mpv → 失败也降级」根本不成立；
     第 3 档 mpv 全败时也弹不出错误卡片（W17 §20.5 遗留 2）。
2. **事件通道会静默丢事件**：`eventsChannel = Channel<PlayerEvents>()` 是**无缓冲**通道，`onPlayerError`（主线程）里的
   `trySend` 只有在接收方正挂起等待时才成功；主线程忙碌时事件被丢弃，而 `handleCodecFallback` 已返回「已接管」→ 既不回退也不报错。
3. **一次失败会连发多条**：mpv 一次打开失败会连发 2–3 条 `MPV_EVENT_END_FILE`，不去重会让 [第 1 档失败] 被处理两次
   （档位 0 → 1 → 2，服务器转码那一档被整段跳过）。

### 21.2 实现（文件 + 行为）

| 文件 | 改动 |
|------|------|
| `settings/.../domain/PlayerDecodeFallback.kt` | 新增 `BACKEND_EXOPLAYER` / `BACKEND_MPV`、`stageAfterFailure(stage, backend, bitrate, codecCapabilityError)`（**手动 / 自动同一条链路**：第 2 档失败一律落第 3 档；第 3 档失败返回 null = 全败才报错；ExoPlayer 第 1 档只接「解不了格式」类错误，mpv 上报无错误码、一律进链路）、`failureKey` / `isDuplicateFailure`（3 s 去重窗口） |
| `player/local/.../domain/PlayerDecodeMode.kt` | 新增 `DecodeStage`（EXO_HARDWARE / EXO_SOFTWARE / MPV_HARDWARE / MPV_SOFTWARE / SERVER_TRANSCODE）+ `decodeStage(backend, mode, fallbackStage)` 纯函数：先看回退档位、再按实际生效的解码方式判 |
| `player/local/.../presentation/PlayerViewModel.kt` | `eventsChannel` 改 `Channel(Channel.BUFFERED)`（trySend 不再丢事件）；`handleCodecFallback` 改用 `stageAfterFailure`（mpv 失败同样接管）；同内核 / 同档位 3 s 内重复上报直接忽略 |
| `player/local/.../mpv/MPVPlayer.kt` | `MPV_EVENT_END_FILE` + `eof-reached=false` → `PlaybackException(ERROR_CODE_UNSPECIFIED)` → `onPlayerError`（日志 `MPVPlayer 播放失败：END_FILE 未到文件末尾`）；用「自己发起的 END_FILE 预算（`markIntentionalEndFile`，只有已加载文件时才记）+ 3 s 窗口」抵消换片 / 切集 / 停止产生的旧文件 END_FILE，`FILE_LOADED` 清预算 |
| `app/phone/.../PlayerActivity.kt` | 内核切换拆成两条明确路径：`switchBackendAndRestart(target)`（**手动**：先读位置 → 写偏好 → **清回退档位** → 重启）与 `switchBackendForFallback(target)`（回退链：保留档位、显式目标内核）；解码面板与错误卡片都走手动路径；新增 `restartPlaybackFromPosition`（位置由调用方传入，修掉读成 0） |
| `app/phone/.../presentation/player/PlayerSettingsPanel.kt` | 「当前档位」改用 `PlayerDecodeMode.decodeStage` 映射：`ExoPlayer 硬解 / ExoPlayer 软解 / mpv 硬解 / mpv 软解 / 服务器转码` |
| `player/local/res/values{,-zh-rCN}/strings.xml` | 新档位文案 5 条（替换原「本地硬解 / 服务器解码 · 转码 / 本地软解」）；未恢复「优先级」提示与内核括号 |
| 单测 | `PlayerDecodeFallbackTest` 6 → 12（手动 Exo / 手动 mpv / 第 2 档任意错误 / 全败才报错 / 重复上报去重）；`PlayerDecodeModeTest` 5 → 6（档位文案映射）= player:local 52 → **59** |

### 21.3 门禁（2026-10-02）

```
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest --console=plain
```

- 根 `assembleDebug`（含 `:app:tv`）✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **51** 项（既有，未改动）
- `:player:local:testDebugUnitTest` ✅ **59** 项 = 既有 52 + 新增 7

### 21.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

素材：Hi10P《学生会的一己之见》`32074ae5-0847-c53c-1d14-9bd32383eec4`（h264 10-bit）。
**①手动切 ExoPlayer 后仍走完整链路（Pad 5）**：

```
PlayerViewModel: 清空解码回退档位（回到本地硬解）                     ← 从 stage=2（mpv 软解）手动切 ExoPlayer
PlayerActivity : Restart player with backend=exoplayer from position=0 (manual)
Player error on backend=exoplayer: ERROR_CODE_DECODING_FAILED
PlayerViewModel: 解码能力不足（backend=exoplayer，ERROR_CODE_DECODING_FAILED），先请求服务器解码/转码重试（优先级：本地硬解 → 服务器转码 → 本地软解）
PlayerActivity : Restart player (fallback=server-transcode) from position=1069000   ← 第 1 档失败（续播位置保留）
JellyfinRepositoryImpl$getMediaSources: getMediaSources bitrate=0 … forceTranscode=true profiles=1
PlayerViewModel: Changed player state to ExoPlayer.STATE_READY                      ← 第 2 档服务器转码真实播放
── 断网注入（第 2 档失败）──
Player error on backend=exoplayer: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
PlayerViewModel: 解码能力不足（backend=exoplayer，ERROR_CODE_IO_NETWORK_CONNECTION_FAILED），服务器转码不可用/已用尽，降级到本地软解（mpv hwdec=no）
PlayerActivity : Restart player with backend=mpv from position=1131930 (fallback)   ← 真实 setBackend(mpv)
MPVPlayer: MPVPlayer hwdec=no vo=gpu-next  ×2                                       ← 真实 hwdec=no
```

落盘 `pref_player_decode_fallback_stage=2` / `pref_player_backend=mpv`；uiautomator 全树**无「播放失败」节点**（第 1/2 档失败不弹错误）。

**②手动切 mpv 的失败也逐级下降（Pad 5）**：mpv 侧硬解失败会被 mpv 自己静默软回退、断网只会 stall（都不产生失败事件，见 §9），
可注入的是「打开失败」：手动 mpv（`hwdec=mediacodec`）→ 断网 → 点「下一集」：

```
MPVPlayer: MPVPlayer 播放失败：END_FILE 未到文件末尾（eof-reached=false），上报回退链
PlayerViewModel: Player error on backend=mpv: ERROR_CODE_UNSPECIFIED
PlayerViewModel: 解码能力不足（backend=mpv，ERROR_CODE_UNSPECIFIED），先请求服务器解码/转码重试（优先级：本地硬解 → 服务器转码 → 本地软解）
PlayerActivity : Restart player (fallback=server-transcode) from position=0
```

`backend=mpv` + `stage=1`（把链路产生的档位固定下来后实测）：`getMediaSources … forceTranscode=true profiles=1` →
`Stream url … master.m3u8?…VideoCodec=h264` → `mpv [stream_callback:v] Opening …/master.m3u8` → 会话 `state=3`（第 2 档真实播放）。
`backend=mpv` + `stage=2` 再注入一次打开失败：mpv 上报后**没有** `Restart player`，直接出错误卡片
（uiautomator：`播放失败 | mpv 播放失败：文件提前结束（eof-reached=false） | 重试 | 改用 ExoPlayer 内核`）＝**全败才报错**。

**③解码档位文案带内核（uiautomator 文本，4 态各一次）**：Pad 5 `当前档位：mpv 软解`（stage 2，mpv hwdec=no）→ 手动切 mpv 后
`当前档位：mpv 硬解` → 手动切 ExoPlayer 后 `当前档位：ExoPlayer 硬解` → 硬解失败进第 2 档后 `当前档位：服务器转码`；
K60 同面板取证 `当前档位：mpv 硬解`。面板文本仍只有 `播放内核 / ExoPlayer / mpv / 解码策略 / 硬解优先 / 仅软解 / 当前档位：…`，
**无「优先级：…」节点、内核名后无括号说明**（W17 未回归）。

**④K60 复验与稳定性**：K60 手动切 ExoPlayer 后播放 Hi10P → `pref_player_decode_fallback_stage=1` / `pref_player_backend=exoplayer`
（同链路生效），队列预取 URL 为 `…/master.m3u8?… TranscodeReasons=DirectPlayError`；两机整轮 `logcat`：`FATAL EXCEPTION` /
`ANR in` / `Input dispatching timed out` / `UnsatisfiedLinkError` 均 **0**。

**副作用与还原**：Pad 5 `backend=mpv`、`streaming_bitrate=0`、`decode_fallback_stage=0`、`media_id=""`、`subtitle_mode=auto`；
K60 `backend=mpv`、`streaming_bitrate=0`、`stage=0`、`media_id=""`、`subtitle_mode=off`、`mpv_hwdec=mediacodec`；
两机 force-stop、`/sdcard/w18_*.xml` 与 `/data/local/tmp/w18_*` 清理、Wi-Fi 恢复开启（注入用开关已复位）。
> ⚠️ 过程记录：本波用 `run-as` 改 prefs 时，`Get-Content` 失败导致 `tee` 用空输入**截断**了两机 prefs 文件；
> 已用会话中导出的完整备份（含服务器 / 用户键）恢复并逐项核对（backend / stage / bitrate / subtitle / server 全部正确），
> 恢复后两机重新起播正常（Pad 5 `state=3`、K60 首页正常）。教训写进 §9：**改 prefs 前必须确认输入流非空、写后必须 `ls -l` 核对字节数**。

### 21.5 未决 / 移交项

1. **队列换集失败触发的回退会重启到「播放页 Intent 的原条目」**：失败发生在「下一集」（不进新 Intent）时，回退重启复用旧 Intent，
   落回原条目而不是失败的那一集；建议下一波让回退动作携带当前 `mediaId`（本次未改，避免扩大范围）。
2. **mpv 的 `hwdec-current` 未纳入判定**：Hi10P 这类「mediacodec 不支持、mpv 自动软解」不算失败（保持本地软解、不强制服务器转码），
   观感与功耗策略不变；若用户希望「mpv 硬解不可用也先请服务器转码」，需要再拍板。
3. **mpv 断网只 stall、不报错**；真机「手动 mpv 失败」用打开失败注入，日常更常见的是文件 / 网络类打开失败。
4. W17 遗留继续挂账：Compact 自由窗口取证、libc++ 覆盖构建补丁在依赖升级后的复核。

---

## 22. W19-PLAYER 落地记录（2026-10-02 · 分支 `feature/w19-fallback-toggle`）

> 用户实测反馈（4 条）：手动把内核切成 **ExoPlayer** 后播放：①解码失败**不会切回 mpv**；②UI 解码面板**一直选中 ExoPlayer**；
> ③出现**频繁重新加载**（反复重启播放），必须手动切到 mpv 才恢复；④新需求：手动选内核后失败也要**自动回退**，
> 并给一个**选项**在「强制使用所选内核」与「失败时自动回退（切 mpv）」之间选择。
> 基线 master `ac4df5e`（W18 已合并）。本波写 `player:local`（回退会话 / 守卫 / 重启目标 / 字符串）、`app:phone`（Activity 重启路径、
> 解码面板、开关）与 `settings`（偏好只追加 + 纯函数）；`app/tv/.../PlayerScreen.kt` 只补 `initializePlayer` 的新参数（TV 冻结，仅保编译）；
> `NavigationRoot.kt` / `settings.gradle.kts` / `libs.versions.toml` / `app/*/build.gradle.kts` 未触碰；未合并 master。

### 22.1 复现与根因（Pad 5 `43af8627`，logcat 定量）

1. 直接以**剧集 id** 入口（`--es itemKind Episode`）链路正常：`DECODING_FAILED → forceTranscode=true profiles=1 → STATE_READY`，全程 **1 次重启**（与本波无关，作为对照）。
2. **复现用户场景**：`--es itemId <seasonId> --es itemKind Season` + Hi10P《学生会的一己之见》`32074ae5-0847-c53c-1d14-9bd32383eec4`
   + 偏好 ExoPlayer + 自动码率。60 秒内：`Restart player (fallback=server-transcode)` **7 次**、`Player error … ERROR_CODE_DECODING_FAILED` **7 次**、
   `清空解码回退档位（回到本地硬解）` **8 次**；`getMediaSources … forceTranscode=false` 反复出现，**从不出现 mpv、也不出现错误卡片**。
   → 与用户三条症状（不切 mpv / 一直 ExoPlayer / 频繁重载）完全对应。
3. **根因结论**：`PlayerActivity.switchBackendForFallback` 会写 `playerBackend` 偏好（`PlayerViewModel.setBackend`），所以「偏好没同步」不是主因；
   真因是**回退档位按「Intent 条目 id」判定换条目**——季 / 剧集入口与队列换集时 Intent 条目 ≠ 实际播放条目（季 id vs 集 id），
   `resetDecodeFallbackForItem` 每次回退重启都把刚推进的档位清零 → 链路永远停在「硬解失败 → 请求转码 → 重启 → 清零」。
   UI 面板读偏好（一直 exoplayer）与「频繁重载」都是同一根因的表现：链路根本没走到 mpv。
4. 附带缺陷（同源）：解码面板「切内核」在回调前先写偏好（`PlayerSettingsController.setBackend`），`PlayerHolder.player` 的 getter
   立即按新偏好重建**空实例** → 手动切内核时续播位置读成 0、当前条目取不到 → 重启落回 Intent 原条目（§21.5 遗留 1 的真因之一）。

### 22.2 实现（文件 + 行为）

| 文件 | 改动 |
|------|------|
| `settings/.../AppPreferences.kt` | **只追加**三个键：`pref_player_decode_fallback_session`（播放会话 id）、`pref_player_decode_fallback_guard`（失败回退重启守卫 `mediaId\|targetStage\|attempts`）、`pref_player_auto_fallback`（失败自动回退开关，默认 `true`） |
| `settings/.../PlayerDecodeFallback.kt` | 新增 `fallbackDecision(autoFallbackEnabled, candidateStage, restartGuardExceeded)`、`RestartGuard` + `recordRestart` / `formatGuard` / `parseGuard` / `MAX_FALLBACK_RESTARTS_PER_STAGE=2`（全部纯函数） |
| `player/local/.../PlayerViewModel.kt` | `initializePlayer(..., playbackSessionId)`：`prepareDecodeFallback(sessionId)` 取代按条目 id 的 `resetDecodeFallbackForItem`（同会话保留档位、新会话清零）；`handleCodecFallback` 走「链路判定 → 守卫计数 → 开关闸门」，关闭开关 / 守卫超限 = 不接管（交错误卡片）并留日志；`clearDecodeFallback` 同时清守卫；新增 `currentPlaybackItemKind()`（当前实际播放条目的 `BaseItemKind`） |
| `player/local/.../PlaylistManager.kt` | 新增 `findItem(itemId)`（在队列清单 + 起始条目里找原始条目） |
| `app/phone/.../PlayerActivity.kt` | 新增 `EXTRA_PLAYBACK_SESSION`（回退 / 手动重启复用会话 id；`onNewIntent` 换新会话）；`restartPlaybackFromPosition(position, target)` 把**实际播放条目**写回 Intent（`currentRestartTarget()` 必须在写偏好**之前**取）；解码面板选中态改读实际生效内核（`PlayerSettingsController(appPreferences) { viewModel.playerBackend }`）；新增 `selectAutoFallback`（关开关时清档位与守卫） |
| `app/phone/.../PlayerSettingsPanel.kt` | `PlayerSettingsSnapshot` 加 `autoFallback`；控制器 `effectiveBackend` 数据源 + `setAutoFallback`；内核行只发回调（不再先写偏好，删掉 `setBackend`）；解码面板新增「失败回退 → 失败时自动回退」`PanelSwitchRow`（整行可点） |
| `app/phone/.../PlayerControlOverlay.kt` | 新增 `onAutoFallbackChange` 参数并传给解码面板 |
| `player/local/res/values{,-zh-rCN}/strings.xml` | 新增 3 条文案（失败回退 / 失败时自动回退 / 说明） |
| 单测 | `PlayerDecodeFallbackTest` 59 → **62**：开关关闭不接管、守卫同目标计数 / 换目标重置 / 超限不接管、序列化往返与坏数据 |

### 22.3 门禁（2026-10-02）

```
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest --console=plain
```

- 根 `assembleDebug`（含 `:app:tv`）✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **51** 项（既有，未改）
- `:player:local:testDebugUnitTest` ✅ **62** 项 = 既有 59 + 新增 3

### 22.4 真机走查（Pad 5 `43af8627` + K60 `8e875894`，命令全部带 `-s`）

素材：Hi10P《学生会的一己之见》（系列 `0e00196b…` / 季 `086c0760…` / S1:E1 `32074ae5-0847-c53c-1d14-9bd32383eec4`、S1:E2 `64c132f8-f30d-6a0b-4533-ace5037ef423`）。

**① 死循环修复 + 重启次数**（Pad 5，季入口 + Exo + 自动码率，修复前 7 次/分钟）：

```
initializePlayer: itemId=086c0760… kind=Season session=5958cf8a… fallbackStage=0
Player error on backend=exoplayer: ERROR_CODE_DECODING_FAILED
回退重启计数：media=32074ae5… target_stage=1 attempts=1/2
解码能力不足（backend=exoplayer，ERROR_CODE_DECODING_FAILED），先请求服务器解码/转码重试…
Restart player (fallback=server-transcode) from position=0
Restart player 对准当前条目：itemId=32074ae5… kind=Episode
initializePlayer: itemId=32074ae5… kind=Episode session=5958cf8a… fallbackStage=1   ← 同会话保留档位
getMediaSources … forceTranscode=true profiles=1 → Changed player state to ExoPlayer.STATE_READY
```

整轮 `Restart player` **1 次**（修复前 7 次/分钟）、无 `清空解码回退档位` 重复、无错误卡片。

**② 手动选 ExoPlayer 后自动回退到 mpv（Pad 5，原始画质路径）**：解码面板点 `ExoPlayer` →
`Player backend set to exoplayer by settings panel` + `Restart player with backend=exoplayer from position=0 (manual)` →
`ERROR_CODE_DECODING_FAILED` → `回退重启计数 target_stage=2 attempts=1/2` → `降级到本地软解（mpv hwdec=no）` →
`Restart player with backend=mpv from position=78000 (fallback)` → `MPVPlayer hwdec=no vo=gpu-next` → mpv 起播。

**③ UI 面板与实际内核一致（Pad 5）**：uiautomator `当前档位：mpv 软解`；面板像素采样：`ExoPlayer` 行 `RGB(17,19,25)`（未选中，= 面板底），
`mpv` 行 `RGB(29,52,55)`（选中，极光青容器合成色）→ 选中态 = 实际内核（mpv）。

**④ 开关关闭 = 强制所选内核（Pad 5）**：解码面板点掉「失败时自动回退」→ `pref_player_auto_fallback=false` + `解码自动回退开关：关（强制所选内核）`；
再手动切 ExoPlayer → `ERROR_CODE_DECODING_FAILED` → `自动回退已关闭，保持所选内核（backend=exoplayer），交给错误卡片`。
统计（本段日志）：`Restart player` **1 次**（仅手动切内核那次）、`MPVPlayer hwdec` **0 次**；uiautomator 错误卡片 = `播放失败 / 重试 / 改用 mpv 内核`。

**⑤ 全败才报错不回归（Pad 5）**：开关恢复开 + 手动 Exo 落 mpv（stage=2）后断 Wi-Fi 点「下一集」→
`MPVPlayer 播放失败：END_FILE 未到文件末尾（eof-reached=false），上报回退链`（连发多条按 3s 去重）→ 第 3 档已用尽 →
uiautomator `播放失败 / mpv 播放失败：文件提前结束（eof-reached=false）`；本段 `Restart player` **0 次**（不再重启）。随后 Wi-Fi 恢复（ping 通）。

**⑥ 回退 / 手动重启对准当前条目（Pad 5，修 §21.5 遗留 1）**：从 S1:E1 点「下一集」到 S1:E2（Intent 仍是 E1）后手动切 ExoPlayer →
`Restart player with backend=exoplayer from position=23000 (manual)` + `Restart player 对准当前条目：itemId=64c132f8… kind=Episode` →
`initializePlayer: itemId=64c132f8…`（不再跳回第一集）；失败后回退 mpv 同样对准 E2、位置 23s 保留。

**⑦ K60 复验 + 稳定性**：K60（横屏）解码面板手动选 `ExoPlayer` → `pref_player_decode_fallback_stage=1`、`backend=exoplayer`、
`guard=32074ae5…|1|1`、队列预取 URL 为 `master.m3u8`（服务器转码）；面板文本 `播放内核 / ExoPlayer / mpv / 失败回退 / 失败时自动回退 / 当前档位：服务器转码`；
20s 稳态窗口 `Restart player=0 / Player error=0`。两机整轮 `logcat`：`FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out` / `UnsatisfiedLinkError` 均 **0**。

**⑧ 副作用与还原**：Pad 5 与 K60 均用会话开始备份的 prefs 原样写回（`adb push` + `run-as sh -c 'cat … > …'`，写后 `ls -l` 核对字节数 2729 / 2357），
核对 `backend`（Pad 5 `exoplayer` / K60 `mpv`）、`stage=0`、`streaming_bitrate=0`、`subtitle_mode`（`auto` / `off`）、`mpv_hwdec=mediacodec` 全部回到基线；
两机 force-stop，`/sdcard/w19_ui.xml`、`/data/local/tmp/w19_restore.xml` 清理；Wi-Fi 复原开启（注入用开关已复位）。

### 22.5 未决 / 移交项

1. **mpv 失败仍只在「打开失败 / 文件类错误」时上报**（`END_FILE + eof-reached=false`）：卡顿 / 断网 stall 仍不产生失败事件（W18 既有语义，未改）。
2. **解码回退档位现在按播放会话保留**：同一会话内切集（队列换集）不清档位——语义是「这台设备在本会话里解不了这类流，继续用已降级的档位」；
   若希望「每集都从硬解重试」，需要把守卫 / 档位改回按媒体 id 判定（会重新引入容器入口的判定复杂度，未做）。
3. **开关关闭只影响后续失败**：不会主动把当前播放的内核切回偏好内核（只清档位与守卫），面板选中态始终显示实际内核。
4. W17 / W18 遗留继续挂账：Compact 自由窗口取证、libc++ 覆盖构建补丁在依赖升级后的复核。

---

## 23. W20-PLAYER 落地记录（2026-10-02 · 分支 `feature/w20-playback-enhance`）

> 用户反馈 / 任务（§1.11 第一波三项）：①**进度记忆与服务端同步**（退出 / 切集 / 被杀后能恢复）；②**片头片尾阈值**复核 +
> 提示条统一（自动跳过可关、双内核一致）；③**Trickplay 预加载与失败降级**（拖动时按需拉、无数据 / 拉取失败不卡不报错）。
> 基线 master `7ec5015`（W19 已合并）。本波写 `player:local`（进度写入器 / Trickplay 加载 / 段偏好）、
> `app:phone`（Activity 接线、提示条可见性、设置面板档位）；`data` 层**未改任何文件**；
> `AppPreferences.kt` / `NavigationRoot.kt` / `settings.gradle.kts` / `libs.versions.toml` / `app/*/build.gradle.kts` 未触碰；未合并 master。

### 23.1 复现与背景（Pad 5 `43af8627`）

1. **会话进度不落 UserData**（本波最大缺口）：播放中 `Sessions/Playing/Progress` 每 5 秒都在发（logcat `Posting progress … position:` 递增），
   但 `GET /Users/{userId}/Items/{itemId}` 读回的 `UserData.PlaybackPositionTicks` **恒为 0**——服务端只在停止上报时落库。
   因此「系统杀掉进程」= 进度全丢（W19 时代续播只能靠 `Playing/Stopped`）。
2. **上报循环挂在 Activity 上**：`PlayerActivity` 的 `repeatOnLifecycle(STARTED)` 里跑 5 秒上报，切后台 / 锁屏即停；
   后台继续播（后台播放开关）时被杀，最后一段进度必丢。
3. **片头片尾阈值形同虚设**：设置里有「显示跳过按钮时长」（秒），播放页却写死 `delay(8000L)`；且控制层 3.5 秒自动淡出会把整层
   ComposeView 置成 `INVISIBLE`，提示条跟着消失——真机实测进片头段时「跳过片头」永远看不见（这才是用户报「提示条」的根因）。
4. **Trickplay 一次性预拉整片**：`PlayerViewModel.getTrickplay` 在换集时把**全部精灵图**下载并解码成 `List<Bitmap>` 常驻内存
   （一集几十到上百张，`for (i in 0..maxIndex)` 还会多请求一张越界图），缺失 / 失败时无失败标记、无降级语义。
   本服务器 2116 个影片 / 剧集**全部没有 Trickplay 数据**（`Trickplay` 空 map、`GET …/Trickplay/{width}/0.jpg` 404），
   即「服务器没有」这条路径是常态。

### 23.2 实现（文件 + 行为）

| 文件 | 改动 |
|------|------|
| `player/local/.../domain/PlaybackPositionWriter.kt`（新增） | 直写 `POST /UserItems/{itemId}/UserData`（只带 `PlaybackPositionTicks`）；SDK 1.8.12 的两个 UUID 参数顺序是「(path 的 itemId, query 的 userId)」，按 `(userId, itemId)` 传会拼成 `/UserItems/{userId}/UserData?userId={itemId}` → 400（真机踩到并修正） |
| `player/local/.../presentation/PlayerViewModel.kt` | ①进度上报循环从 Activity 迁到 ViewModel 常驻协程（`playerHolder.existingPlayer` 判空、`isPlaying` 才发）；②`updatePlaybackProgress` 同时直写 UserData；③新增快照 `lastProgressItemId/Position/Duration` + `reportOutgoingItemStop()`（切集 / 手动换条目给**上一集**补 `Playing/Stopped`，与自然播完去重 15 s）；④暂停（非片尾）立即落一次进度；⑤`releasePlayer` 去掉 `delay(200)`，同步取快照后立刻发停止上报；⑥`trickplayLoader` 按需加载 + `trickplayFrameAt()`；⑦`uiState` 新增 `skipChipDurationMs` / `trickplayIntervalMs` / `trickplayVersion`（移除整批 `currentTrickplay`） |
| `player/local/.../domain/TrickplayPreview.kt`（新增） | 纯逻辑：`TrickplayTiles`（精灵图索引换算 / 边界收敛）、`TrickplaySheetCache`（LRU，默认 2 张）、`TrickplayRequestState`（同图只发一次、**失败不重试**） |
| `player/local/.../presentation/TrickplayPreviewLoader.kt`（新增） | 只拉「当前拖动位置所在的精灵图」；IO 拉取 + 解码、主线程落缓存与状态；失败 `markFailed` 后 `frameAt` 恒返回 null（UI 自然降级为「没有预览图」）；缓存目录兼容离线布局 `files/trickplay/<itemId>/<sourceId>/<index>` |
| `app/phone/.../presentation/player/PlayerControlOverlay.kt` | 进度条与手势 HUD 改按需取图（`trickplayIntervalMs/Version/FrameAt`）；提示条超时读 `uiState.skipChipDurationMs`；`onRegionsChanged` 增加 `skipChipVisible` 维度（提示条可见时整层保持合成）；`SkipSegmentChip` 描边统一为 `outlineVariant`（与顶栏徽标 / 面板胶囊同一套 token） |
| `app/phone/.../presentation/player/PlayerContentPanel.kt` | `PlayerCompactBar` 同步按需取图参数 |
| `app/phone/.../presentation/player/PlayerSettingsPanel.kt` | 播放设置面板补「提示条显示时长」档位（3 / 5 / 8 / 10 秒，写既有偏好键；全局设置页仍可输入任意秒数） |
| `app/phone/.../utils/PlayerGestureHelper.kt` | seek HUD 的预览图从「整批列表」改为 `trickplayFrameAt` 提供者（未命中先不显示，不阻塞手势） |
| `app/phone/.../PlayerActivity.kt` | 移除 5 秒进度循环（迁往 ViewModel）；`trickplayFrameAt` 接线；`onRegionsChanged` 新参数参与可见性判定 |
| `player/local/res/values{,-zh-rCN}/strings.xml` | 新增 2 条（提示条显示时长 / %d 秒） |
| 单测 | 新增 `TrickplayPreviewTest` 6 项：精灵图换算（末张不满 / 越界 / 负数 / interval=0）、LRU 淘汰、请求去重与失败标记 = player:local 62 → **68** |

### 23.3 门禁（2026-10-02）

```
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :player:local:testDebugUnitTest --console=plain
```

- 根 `assembleDebug`（含 `:app:tv`）✅｜`ktfmtCheck` ✅
- `:app:phone:testLibreDebugUnitTest` ✅ **51** 项（既有，未改动）
- `:player:local:testDebugUnitTest` ✅ **68** 项 = 既有 62 + 新增 6

### 23.4 真机走查（Pad 5 `43af8627`，命令全部带 `-s`；K60 归 W21-MUSIC，全程未占用）

**① 进度：退出 / 杀进程 / 切集 / 双内核**（素材《秒速5厘米》`eb60b7e9…` + Hi10P《学生会的一己之见》S1:E1 `32074ae5…` / E2 `64c132f8…`）：

```
Posting progress of eb60b7e9…, position: 1896540000          ← 退出前最后一次周期写入
PlayerViewModel$releasePlayer: Sending playback stop          ← 返回键退出
GET /Users/{uid}/Items/eb60b7e9… → UserData.PlaybackPositionTicks = 189.7s   ← API 回读
initializePlayer … → media_session position=189659（重开续播点 = 退出点）   ← 差 5ms
am force-stop → 服务端 194.8s；重开后 position=194849        ← 杀进程后恢复
PlayerViewModel: 换集：为上一集补停止上报 itemId=32074ae5… position=142029
GET …/32074ae5… → 142.0s                                     ← 「切集」把上一集钉在切走的位置
（mpv）Posting progress position=2070000000 → 服务端 207s；退出 stop → 225s；重开续播 242s
```

**② 片头片尾**（服务器无 media segments，按 W5-R3I / W17 先例用**临时注入补丁**取证，验完已 `git` 还原并重装干净包）：
提示条 `text=跳过片头` bounds `[1364,2186][1510,2234]`（Exo / mpv 完全一致）；时长档 30 s：段内 8 s 仍可见，
档 3 s：段内 9 s 已收起；自动跳过**开**（关掉按钮）= 位置 60 s → 122 s（mpv）/ 122.6 s（Exo）；**关** = 停在段内不跳（74 s→89 s）。

**③ Trickplay 两条路径**（同临时注入 + 手工投喂精灵图）：

```
GET https://…/Videos/eb60b7e9…/Trickplay/160/0.jpg           ← 拖动触发按需拉取（服务器无图 → 404）
TrickplayPreviewLoader: Trickplay 预览降级：精灵图 0 不可用（本次播放不再重试）
（投喂 5×5 精灵图到 files/trickplay/<itemId>/<sourceId>/0 后）
TrickplayPreviewLoader: Trickplay 精灵图就绪：sheet=0 tiles=25
截图分析：预览色块 210412 px，bbox=(494,180)-(1105,523)（Exo=蓝 tile / mpv=绿 tile，几何位置一致）
```

拖动全程无卡顿、无异常、无错误卡片；`logcat` 整轮 `FATAL EXCEPTION` / `ANR in` / `Input dispatching timed out` / `UnsatisfiedLinkError` 均 **0**。

**④ 副作用与还原**：prefs 用会话开始备份原样写回（`adb push` + `run-as sh -c 'cat … > …'`，写后 `ls -l` 核对 2729 B；
`backend=exoplayer` / `stage=0` / `streaming_bitrate=0` / `subtitle_mode=auto`，测试期新增的 `media_segments_*` 键消失）；
注入的 `files/trickplay/**` 已删、`/data/local/tmp` 与 `/sdcard` 的 `w20_*` 清空、App force-stop、Wi-Fi 保持开启、`wm size/density` 未改动。

### 23.5 未决 / 移交项

1. **服务器数据缺失**：本波服务器（`jellyfins.zhangwenkang.com`）2116 个影片 / 剧集**全部无 Trickplay**、抽样剧集**无 media segments**，
   片头尾与 Trickplay 的「正常路径」只能用临时注入 + 投喂精灵图验证；等服务器侧生成数据后建议补一次真实数据回归（纯 `adb` 即可，无需改码）。
2. **UserData 写入频率**：与 5 秒会话上报同频（只写 `PlaybackPositionTicks`）；如担心服务端写压力，可降到 15 秒 + 暂停 / 退出 / 切集必写（本波按「杀进程精度 ≤5 s」取舍）。
3. **Trickplay 只缓存 2 张精灵图**：快速长距离拖动时可能出现「先空一帧、下一帧出图」；如观感不够，可把 `TrickplaySheetCache(capacity = 2)` 调到 3–4（内存每张约 1.4 MB）。
4. W17 / W18 遗留继续挂账：Compact 自由窗口取证、libc++ 覆盖构建补丁在依赖升级后的复核。
