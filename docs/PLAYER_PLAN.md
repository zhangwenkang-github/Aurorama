# Cinefin 播放界面 · 任务与进度（唯一权威文件）

> **新对话从这里开始。** 开工前读本文件，收工前把进度写回本文件。
> 纪律：需求变更、决策、完成度、勾选项、更新日志，都在**同一次改动**里写回这里；不再新建零散 `.md`。
>
> 最后更新：2026-09-27　分支：`master`　基线提交：`8ab9c7a`（工作区含未提交改动）

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

## 1. 本轮目标

把已能播放的播放器界面，做成**平板与手机上"稳、顺、好看"的自用播放器**：

1. 控制层补齐覆盖态（错误/重试/切内核）与视觉统一（八态按钮、字阶、遮罩）。
2. 系统层补上 `MediaSessionService`（通知栏 + 锁屏 + 后台播放可靠）。
3. 平板/折叠屏做分栏与半开形态；手机竖横屏打磨。
4. 手势零冲突，锁屏防误触可靠。
5. 全流程可无障碍使用（TalkBack、大字体、遥控/键盘焦点）。

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

| 模块 | 完成度 | 状态 | 证据 / 落点 |
|------|--------|------|------------|
| 播放内核（ExoPlayer + FFmpeg + mpv 降级） | 85% | 🟡 | `player/local/.../PlayerViewModel.kt`、`mpv/MPVPlayer.kt` |
| 手势层 | 80% | 🟡 | `utils/PlayerGestureHelper.kt`（589 行） |
| 控制层（Compose 三栏 + 进度条 + 锁屏 + 错误卡片 + 八态按钮 + 清晰度徽标） | 82% | 🟡 | `presentation/player/PlayerControlOverlay.kt`；错误卡片、按钮八态、顶栏徽标均已实测；新增「更多」聚合面板 |
| 面板系统 | 58% | 🟡 | 同文件：倍速/循环/画面比例/字幕/音轨/信息/队列/睡眠 + 更多 |
| 系统层（通知栏 / 后台 / 焦点 / PiP） | 32% | ⛔ | `BasePlayerActivity.kt` 内联 `MediaSession`，**无 Service / 无通知**；PiP 状态已接入形态判定（Pip 骨架） |
| 多形态骨架（手机 / 平板 / 折叠 / 小窗 / TV / 车机） | 55% | 🟡 | 新增 `PlayerFormFactor.kt` + `PlayerContentPanel.kt`；平板 SplitSide、手机竖屏 SplitPortrait、小窗 Compact、车机不沉浸已实现；平板与竖屏已实测（见 §10），折叠 / TV / 车机 / 小窗待实机 |
| 无障碍 | 55% | 🟡 | 可点节点已自带标签（实测 `uiautomator` 可见）、role/selected 语义、48dp 命中区；焦点顺序/大字体/高对比未验证 |
| 测试与验收 | 10% | ⛔ | 无 Compose UI 测试、无性能基线 |

### 3.1 工作区状态（交接必读）

- 当前未提交改动 **108 项**，其中大部分是**更早会话**留下的改造（主题、首页组件、设置页、播放控制层），不属于本次任务，也不要当成"本轮进度"。
- 本任务到目前为止的改动：`docs/PLAYER_PLAN.md`+`AGENTS.md`（新增）、删除 5 个旧 `.md`；
  以及阶段 1.3 涉及的 8 个文件 —— `PlayerViewModel.kt`、`MPVPlayer.kt`、`PlayerActivity.kt`、
  `PlayerControlOverlay.kt`、`PlayerOverlayContainer.kt`、`BasePlayerActivity.kt`（注释）、
  `player/local` 的三个 `strings.xml`。
- 阶段 8.1–8.6 的新增/改动：**新增** `presentation/player/PlayerFormFactor.kt`、
  `presentation/player/PlayerContentPanel.kt`；**改动** `PlayerControlOverlay.kt`（骨架化 + `MorePanel`）、
  `PlayerOverlayContainer.kt`（按画面区划命中带）、`PlayerActivity.kt`（画面区排版 / 方向 / 车机不沉浸 / PiP 状态）、
  `app/phone/src/main/AndroidManifest.xml`（`fullSensor` + `resizeableActivity`）、
  `player/local` 三个 `strings.xml`（`player_controls_episodes` / `player_controls_more` / `player_controls_side_panel_collapse`）。
- **判断进度只看本文件 §3 看板与 §4 勾选项**，不要用 `git status` 的改动量推断。
- 可用验证环境：模拟器 `emulator-5554`（API 36 / 2560×1600 平板）在跑，且已安装 `com.zhangwenkang.cinefin.debug`；测试服务器凭据不入库。

---

## 4. 开发步骤与进度

> 每完成一项，把 `[ ]` 改成 `[x]` 并在 §10 记一行日志。预估为单人工作量（含自测）。

### 阶段 0 · 基线校验（0.5 天）

- [x] 0.1 用固定 JDK 构建并确认基线可编译（`assembleDebug`）— 2026-09-27 通过（25s，197 tasks up-to-date）
- [ ] 0.2 连测试服务器（只读）播一条网络片源，验证首帧 / 进度 / 字幕切换
- [ ] 0.3 新增 `PlayerDebugOverlay`（长按标题 2s 显示：内核 / 解码器 / 码率 / 缓冲 / 丢帧）
- [ ] 0.4 `ktfmtFormat` + `lintDebug` 基线无新增告警

### 阶段 1 · 控制层补完（1–1.5 天）★最高优先

- [x] 1.1 `PlayerIconButton` 八态化：默认 / 按下（缩放 0.94 + 底色）/ 聚焦（2dp 朱砂描边，遥控与键盘用）/ 选中（朱砂图标 + 浮起底）/ 禁用（38% 透明度）/ 加载（转圈代图标）/ 激活徽标（右上朱砂点）/ 错误（朱砂底）
      · 无障碍一并修掉：`contentDescription` + `role=Button` + `selected` 直接写在可点节点上（此前可点节点无标签）
      · 选中态改由 `selected` 参数驱动，循环 / 字幕 / 画面比例 / 睡眠定时四处调用已迁移
      · 实测量测：图标按钮 48×48dp、中央播放键 72×72dp
- [x] 1.2 顶栏：清晰度徽标（`1080P`，无轨道信息时不显示假值）、标题两行省略
- [x] 1.3 中央错误卡片：原因 + 重试 + 一键切内核 — 2026-09-27 完成，模拟器实测
      （证据 `docs/screenshots/player-error-card-2026-09-27.png`：断网 → 卡片 → 重试恢复）
- [ ] 1.3b 中央加载细线（顶部 2dp）
- [ ] 1.4 底栏：时间可点切换"总时长 / 剩余时间"、章节入口、进度条命中区 ≥32dp
- [ ] 1.5 视觉收口：渐变遮罩统一、15sp/13sp 字阶、等宽数字、发丝线、淡入淡出 150–200ms
- [ ] 1.6 验收：竖横屏全控件走查 + TalkBack 能读完顶→中→底 + 重启后记住倍速/字幕/音量

**阶段 1 过程中发现并已处理的问题**

| 问题 | 影响 | 处理 |
|------|------|------|
| `MPVPlayer.setVideoTextureView()` 是 `TODO()` 桩 | 控制层改 Compose 后视频输出换成 TextureView，选 mpv 内核**必崩**（实测 `kotlin.NotImplementedError`，堆栈 `MPVPlayer.kt:1350 ← PlayerView.setPlayer ← PlayerActivity.onCreate`） | 已按 SurfaceView 同一套 `attachSurface` 实现 TextureView 挂载/卸载；模拟器实测出画面（`docs/screenshots/player-mpv-fallback-2026-09-27.png`） |
| `PlayerActivity` 是 `launchMode="singleTask"` | 用 `startActivity` 重启换内核会被复用成同一实例，紧接着 `finish()` 直接退回上一页（实测过一次） | 改为 `intent.putExtra(位置) → viewModelStore.clear() → recreate()` |
| mpv 内核不上报播放错误 | `getPlayerError()` 恒为 null，出错只有黑屏没有卡片 | **待办**：补 mpv 事件（`MPV_EVENT_END_FILE`）错误上报 |

### 阶段 2 · 手势与锁屏收口（1–1.5 天）

- [ ] 2.1 优先级仲裁：锁屏 > 面板 > 边缘亮度音量 > 横滑进度 > 双击 > 单击
- [ ] 2.2 双击分区可视化 + 触觉反馈；亮度/音量/进度 HUD 统一为一个组件
- [ ] 2.3 长按倍速胶囊（与"长按跳章节"设置互斥）
- [ ] 2.4 双指缩放结果写回画面比例并记忆
- [ ] 2.5 锁屏后吞掉全部触摸，仅留可拖解锁钮；锁定时锁定方向
- [ ] 2.6 验收：连续 20 次手势无冲突无误触；锁屏后任意触摸不改变播放状态

### 阶段 3 · 面板系统扩展（1.5–2 天）

- [ ] 3.1 字幕：延迟 ±0.1s、双语次字幕、大小/颜色/背景/描边/位置
- [ ] 3.2 音轨：延迟、描述、默认轨记忆（记忆已有）
- [ ] 3.3 画面：补旋转 / 镜像 / 裁剪 / 去黑边（比例已有）
- [ ] 3.4 队列：拖拽排序、删除、清空、跳转；循环模式 顺序/单曲/随机/播完暂停
- [ ] 3.5 信息面板：容器/编码/分辨率/码率/帧率/HDR/音频格式/文件大小/路径
- [ ] 3.6 新增设置面板：播放 / 解码 / 字幕 / 音频 / 画面 / 手势 分组
- [ ] 3.7 睡眠定时补"播完当前"
- [ ] 3.8 验收：任意时刻仅一个面板；返回键逐级收面板 → 收控制层 → 退出播放

### 阶段 4 · 系统层：后台 / 通知栏 / PiP（1–1.5 天）★体验关键

- [ ] 4.1 `CinefinPlaybackService : MediaSessionService`，把内联 `MediaSession` 迁入，`onGetSession` 返回会话
- [ ] 4.2 通知栏全套按钮（D4）：标题/封面/上一集/播放暂停/下一集/快退/快进/关闭/进度
- [ ] 4.3 前台服务权限 + `startForeground`；后台播放开关交由 Service 决策
- [ ] 4.4 音频焦点、来电暂停、耳机拔出暂停、蓝牙切换
- [ ] 4.5 PiP：`setAspectRatio` 跟随比例、进入/退出不闪控制层、遥控三键、可选自动进 PiP 开关
- [ ] 4.6 屏幕常亮按播放状态切换（复核暂停时是否释放）
- [ ] 4.7 验收：`dumpsys media_session` 可见活跃会话；锁屏 30 分钟音频不中断；通知栏按钮全部可用

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

## 5. 界面规格（实现基准）

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

#### 5.5.2 骨架线框

手机竖屏 `SplitPortrait`（视频不再强制横屏，16:9 定高）：

```
┌──────────────────────────────┐
│ ← 标题 · S01E03         ⋮  ⤢ │ 顶栏（叠在视频上，76dp 安全区）
│        ⏮ ↺10 ▶ ↻10 ⏭        │ 中央簇
│ ──────●────────────  12:31/24:05
├ ─ ─ ─ ─ 视频区结束 ─ ─ ─ ─ ─ ┤   ← 视频区 = max(宽 × 9/16, 42% 窗口高)
│ 选集 · 简介 · 队列            │ Tab 行 56dp
│  ┌────┐ ┌────┐ ┌────┐        │
│  │E01 │ │E02 │ │E03 │  →     │ 横滑卡片（选集封面 16:9 + 集数）
│  └────┘ └────┘ └────┘        │
└──────────────────────────────┘
上滑内容区可全屏化（默认露 56dp 手柄），下滑回到视频比例
```

平板 / 折叠展开 `SplitSide`（视频区控制层保持现有布局，右侧新增内容栏）：

```
┌───────────────────────────────┬──────────────┐
│ ← 标题 S01E03 1080P    ⤢ 🔒 ⋮ │ 选集 队列 简介│ 320dp
│         ⏮ ↺10 ▶ ↻10 ⏭        │ ┌──────────┐ │
│                               │ │E01  ▶ 播放中│ │
│ ──────●────────────  12:31    │ │E02       │ │
│ ⏮ ↺10 ▶ ⏭ ↻10 1.0× 💬 🎧 ▭ ☰ │ │E03       │ │
└───────────────────────────────┴──────────────┘
   视频区（宽 - 320dp）               内容栏，可折叠
```

折叠半开 `FoldHalfOpen`（垂直折痕）：

```
┌───────────────────────────────┐
│         画面（折痕上方）         │  视频区高度 = 折痕上边缘
├ ─ ─ ─ ─ ─ 铰链 ─ ─ ─ ─ ─ ─ ─ ─┤
│ ──────●──────────────  12:31   │  进度条（下屏首行）
│ ⏮ ↺10 ▶ ↻10 ⏭   1.0× 💬 🎧 ▭ ☰ │  控制行（64dp 命中区）
│ 选集 E01 E02 E03 →             │  内容横滑（下屏剩余空间）
└───────────────────────────────┘
```

小窗 `Compact`（自由窗口 / 分屏窄宽）：单行合并，点击标题出更多菜单

```
┌───────────────────┐
│ ▶  标题  12:31/24:05│
│ ─────●──────────── │
└───────────────────┘
```

PiP：系统三键（上一集 / 播放暂停 / 下一集）+ 细进度条，比例跟随视频，控制层不闪。

TV `Fullscreen(Tv)`：10-foot 尺度、焦点描边 2dp 朱砂 + 16dp 光晕、左右键 ±10s、中央键播放暂停、返回逐级收面板。

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
| 模拟器 | AVD `CinefinTablet`（2560×1600 / 320dpi / API 36），**无硬件加速**（需管理员安装 AEHD 驱动），优先真机 |
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

## 9. 权威文件索引

| 内容 | 位置 |
|------|------|
| 播放界面任务 / 需求 / 决策 / 进度 | **本文件** |
| 服务器控制台皮肤（已实现的旁支功能） | `docs/web-console-skin.css` 为唯一权威副本；改这里后同步 `app/phone/src/main/res/raw/web_console_skin.css` 与服务器自定义 CSS |
| 项目级里程碑（原 `docs/PLAN.md`） | 已合并进本文件；历史版本见 git |
| 界面详细规格（原 `docs/PLAYER_SPEC.md`） | 已合并进本文件 §5–§6；历史版本见 git |

---

## 10. 更新日志

| 日期 | 变更 |
|------|------|
| 2026-09-27 | 建立本文件；合并原 `PLAYER_SPEC.md` / `PLAN.md` / `DEV_ENVIRONMENT.md` / `PLAYER_DEV_PLAN.md` / `WEB_CONSOLE_SKIN.md`，删除后四者 |
| 2026-09-27 | 记录 D1–D7 决策：平板优先、只做视频、TV 暂停、通知栏全套、外部播放器不做（进度无法同步） |
| 2026-09-27 | 阶段 0.1 完成：固定 JDK 构建通过（`assembleDebug`，197 tasks up-to-date）；新增仓库级 `AGENTS.md` 指向本文件 |
| 2026-09-27 | 修正 `BasePlayerActivity` 中指向已删文档的注释；补充 §3.1 工作区状态（108 项未提交改动中多数来自更早会话） |
| 2026-09-27 | 阶段 1.3 完成：播放错误卡片（原因 + 重试 + 一键切内核），`PlayerViewModel` 加 `onPlayerError`/`retryPlayback`/`switchBackend`，`PlayerOverlayContainer` 错误态放大命中区；模拟器断网实测：卡片出现 → 重试恢复播放 |
| 2026-09-27 | 修复 `MPVPlayer.setVideoTextureView()` 未实现导致的 mpv 必崩；修复 singleTask 下换内核重启退回上一页（改 `viewModelStore.clear() + recreate()`）；两者均在模拟器实测通过 |
| 2026-09-27 | 新增待办：mpv 错误上报（`MPV_EVENT_END_FILE`）；可点节点无障碍标签为空并入 1.1 |
| 2026-09-27 | 阶段 1.1 + 1.2 完成：`PlayerIconButton` 八态化（含聚焦/禁用/加载/错误/徽标）并修好可点节点的无障碍标签；顶栏加清晰度徽标（实测显示 1080P）与标题两行。模拟器文本验证：所有可点节点均带 `content-desc`，命中区 48dp / 72dp |
| 2026-09-27 | 记录 D8 上下文预算硬约束（纯文本 ≤880KB、内联图片 ≤48MiB）：少传图、工具输出裁剪、优先文本验证 |
| 2026-09-27 | 记录 D9/D10：多形态由代码判定驱动（`PlayerFormFactor` + `PlayerChromeLayout`），TV / 车机 / 小窗纳入范围（D1/D3/D6 相应调整）；§5.5 补齐多形态规格（骨架矩阵、线框、组件映射、动效与系统集成） |
| 2026-09-27 | 阶段 8.1–8.6 完成：新增 `PlayerFormFactor.kt`（形态判定 + 布局上下文）、`PlayerContentPanel.kt`（侧栏 / 竖屏内容区 / 横滑选集 / 小窗单行条）、`MorePanel`；`PlayerControlOverlay` 骨架化；`PlayerOverlayContainer` 命中区随骨架变化；`PlayerActivity` 形态驱动画面区排版 + `fullSensor` 方向 + 车机不沉浸；Manifest 放开 `sensorLandscape` 并显式 `resizeableActivity`。模拟器实测：平板横屏侧栏（选集 / 队列 / 收起后画面区全宽）、平板竖屏全屏、手机竖屏（视频区 42% 高 + 下方横滑选集），改尺寸与旋转不中断播放 |
