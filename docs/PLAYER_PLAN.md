# Cinefin 播放界面 · 任务与进度（唯一权威文件）

> **新对话从这里开始。** 开工前读本文件，收工前把进度写回本文件。
> 纪律：需求变更、决策、完成度、勾选项、更新日志，都在**同一次改动**里写回这里；不再新建零散 `.md`。
>
> 最后更新：2026-09-28　分支：`master`　最新提交：`768c81c`

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

### P1 · 体验提升

- [ ] **1.6 画面调整**（原阶段 3.3）——旋转 / 镜像 / 裁剪 / 去黑边（比例已有，偏好仿 `pref_player_resize_mode`）。
- [ ] **1.7 队列管理**（原阶段 3.4）——拖拽排序、删除、清空、跳转；循环模式补「播完暂停」。
- [ ] **1.8 信息面板补全**（原阶段 3.5）——容器 / 编码 / 分辨率 / 码率 / 帧率 / HDR / 音频格式 / 文件大小 / 路径。
- [ ] **1.9 播放页设置面板**（原阶段 3.6）——播放 / 解码 / 字幕 / 音频 / 画面 / 手势 六组，页内直接改。
- [ ] **1.10 控制层视觉收口**（原阶段 1.4 / 1.5）——底栏时间可点切换「总时长 / 剩余」、章节入口、
      15sp / 13sp 字阶、等宽数字、渐变遮罩统一、加载细线。
- [ ] **1.11 播放增强**（原阶段 6）——进度记忆与服务端同步、片头片尾阈值设置、Trickplay 预加载与失败降级、
      外挂字幕导入、播放结束行为（自动下一集 / 停在结束帧）。
- [ ] **1.18 libass 字幕渲染**（M4 缺口）——当前字幕走 Media3 默认渲染；目标是接入 libass 提升 ASS/SSA 特效字幕
      （字体、定位、动画）的还原度，并保留现有"字幕语言智能识别 + 样式设置"。
      落点：`player/local`（渲染管线）+ `PlayerActivity.configureSubtitleStyle()`；需先调研 Media3 与 libass 的衔接方式。
      验收：带复杂特效的 ASS 外挂字幕位置 / 字体 / 动画与电脑端播放器基本一致，且不影响双内核切换。

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
| 播放内核（ExoPlayer + FFmpeg + mpv 双内核 + 静默降级） | 88% | 🟡 | `player/local`：`PlayerHolder` / `PlayerViewModel` / `mpv/MPVPlayer` |
| 队列与选集（整剧补全、按季分组、缩略图行） | 85% | 🟡 | `PlaylistManager` + `PlayerContentPanel` |
| 控制层（三栏 + 进度条 + 锁屏 + 错误卡片 + 八态按钮 + 清晰度徽标） | 88% | 🟡 | `presentation/player/PlayerControlOverlay.kt` |
| 面板系统（倍速 / 循环 / 比例 / 字幕 / 音轨 / 信息 / 队列 / 睡眠 / 更多） | 78% | 🟡 | 字幕（§1.1）与音轨（§1.2）面板已补全；画面 / 信息 / 队列等待办见 §1.6–1.9 |
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

| 权威内容 | 位置 |
|----------|------|
| 播放界面任务 / 需求 / 决策 / 进度 | **本文件** |
| 服务器控制台皮肤（旁支功能） | `docs/web-console-skin.css`（改后同步 `app/phone/src/main/res/raw/web_console_skin.css` 与服务器自定义 CSS） |
| 旧文档（PLAN / PLAYER_SPEC / DEV_ENVIRONMENT / WEB_CONSOLE_SKIN） | 已合并进本文件，历史见 git |

---

## 10. 更新日志（近 8 条；更早见 `git log -p docs/PLAYER_PLAN.md`）

| 日期 | 变更 |
|------|------|
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
> 本质上它们是一次「播放页控制层重构」（控件体系 + 版式 + 面板形态），
> 与 §1.10 控制层视觉收口、§1.9 播放页设置面板高度重叠——**建议合并成一条线做，避免返工**。

### 11.1 问题清单（按类型分组）

**A. 控件精简（视觉噪音）**

1. 播放界面有多余的、离散的常驻控件：
   - 右上角的「画中画」与「锁定」，右下角还有一把固定的「锁」——同一个能力两个入口，且都常驻画面；
   - 现状落点：`PlayerTopBar`（PiP / 锁）+ `PlayerBottomBar` 最右侧的锁按钮；
   - 处理方向：常驻项只留一份；PiP 这类低频入口收进「更多」；锁的解锁入口保留在侧边/长按，
     锁屏时不显示其它控件（`LockedOverlay` 已有）。

**B. 图标语义（可读性）**

2. 控件图标需要重绘，并**加上文字标签**——目前纯图标（字幕 / 音轨 / 画面 / 信息 / 队列 / 睡眠…），
   不看 tooltip / 点一次试不出来是干啥的；
   - 处理方向：底栏按钮统一「图标 + 文字」，图标重绘保持 24dp 网格、线性风格、与既有 `ic_*` 一致；
   - 落点：`core/src/main/res/drawable/ic_*.xml` + `PlayerControlOverlay.kt` 的 `PlayerIconButton` / 底栏。

**C. 面板形态统一**

3. 点击控件后的面板统一**从右侧滑出**（和选集栏同款），**半透明背景、不压缩播放画面**、直接盖在画面上：
   - 现状：面板是 `ModalBottomSheet`（底部弹起，遮住下半画面，且与平板侧栏两套交互）；
   - 处理方向：把面板宿主机从 ModalBottomSheet 换成右侧抽屉（复用 `PlayerContentPanel` 的侧栏形态），
     打开时 `PlayerActivity.applyVideoArea` 不再收窄画面；
   - 落点：`PlayerControlOverlay.kt`（panel host）、`PlayerContentPanel.kt`、`PlayerOverlayContainer.kt`（命中区）、
     `PlayerActivity.applyVideoArea`；
   - ⚠️ 风险：命中区（触摸分区）必须同步改，改完要真机走查「面板打开时的单击 / 滑动 / 手势仲裁」。

**D. 播放行为（bug 级）**

4. 打开视频后**没有自动选择字幕**（以前实现过，疑似失效）；打开视频后**没有自动播放**。
   - 已知线索（新会话从这里查，别从零开始）：
     - 字幕：§1.1 之后主字幕由 `PlayerSubtitleController.pickPrimary()` 按语言优先级选；
       若源的 `language` 识别为空、且 `subtitleMode=auto`，则**不选任何轨**——需要补「识别不出来时按默认轨兜底」；
     - mpv 内核：`MPVPlayer` 初始化时把 `preferredTextLanguages.firstOrNull().split("-").last()`
       写进了 mpv 的 `slang`（`zh-Hans` → `Hans`），语言匹配必然失败——**"以前有、现在没有"最可能的原因**；
     - 自动播放：`initializePlayer` 里有 `player.play()`，但 `BasePlayerActivity.onPause` 会把
       `playWhenReady=false`；起播阶段若经历一次 pause/resume（内核切换、加载），可能被带成"要手点一次"。
   - 验收：打开任意有字幕的新片自动出字幕（语言优先级仍生效）；打开即播、无需第二次点击。

**E. 控件排布**

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

