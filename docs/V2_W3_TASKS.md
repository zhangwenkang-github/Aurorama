# 2.0 W3 · 导航与新 UI 骨架（任务卡）

> 状态：`IN_PROGRESS`（2026-11-18 T0 完成：现状调查 + NAV 决策定稿，任务卡定稿待派）
> 依据：`docs/V2_REQUIREMENTS.md` §10（V2-NAV）与 §15 波次（W3 ← W1）；设计唯一权威 `docs/V2_DESIGN.md`。
> **执行约束**：设计决策（容器选型、动效参数、视觉样式）全部由 Kimi 定稿于本卡「W3 导航决策定稿」节；deepseek 子代理只按定稿执行，遇到定稿未覆盖的样式问题停下来报告，不自行发挥。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W3 开工基线 `7540f829` + W2 遗留提交（mpv 过早 stop 修复 + 判别性 harness，本会话首日提交）。
- **W2 遗留核查（2026-11-18 复核通过）**：① 第三个 flaky 已 cherry-pick 回 master（`a1e2951c`，master Build CI 不再阻塞）；② mpv 过早 stop 修复（`MPVPlayer.kt` 当前 source 已 START 且无 pending 时不 stop / stale 无 pending 只清理）+ 3 条 RED→GREEN 单测在工作区，Pad 实测 E1 起播/暂停续播/E1→E2 通过；③ VM `retryPlaybackAt` 判别性 harness 已补（`PlaybackRecoveryCopyTest.retryPlayerErrorSeeksToItemIndexCapturedBeforePrepare`，断言 `seekTo(2, 42000)`，回退裸 seekTo 时如期变红）。
- **T0 完成（2026-11-18，Kimi 亲自）**：导航现状调查 + NAV-01~13 决策定稿（见下节）；任务卡 T1–T5 定稿。
- **独立未覆盖项（不阻塞 W3）**：K60 / jellyfins 生产 HTTPS 间歇 `mbedtls -0x7280`（E1 可播、E2 握手失败）；`seekSink.retrySeek()` 条目归属另开最小修复。
- **本次质量门（W2 遗留提交时已过）**：`player/local` 539 + `app/phone` 529 单测全绿，全仓 `ktfmtCheck` 与 `:app:phone:assembleDebug` 通过。
- **环境备注**：全量构建带 `--max-workers=2`（16G 机器 OOM 前科）；构建前 `export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'`；CI（Build/Unit Tests/Format）仅 master push 与 PR 触发；K60=`8e875894`、Pad=`43af8627`；jellyfins 只读。
- **W1 遗留随 W3 处理**：图标第二批（约 66 个 + settings 5 个同名旧图标 + T4 三处几何微调）、形状 token（K1 §3.2 圆角）随页面换肤切换、旧 Prism 中性常量清理（reader 引用收口后）、light aurora3 对比度调值须同步 `CinefinThemesTest`。详见 `docs/V2_W1_TASKS.md` 交接节。

## W3 导航决策定稿（2026-11-18，Kimi 亲自；回应 V2-NAV-01~13）

### 约束分析（NAV-02）

- **平台与输入**：Android 手机/平板，纯触摸（TV 冻结仅参考，NAV-12）；无遥控器/鼠标悬停（悬停反馈只保留按压态语义）。
- **媒体类型**：3 个内容域（视频/音乐/书籍）+ 4 个横切域（首页/收藏/下载/设置）；详情深度 2–3 级。
- **内容规模**：个人服务器数十库级 + 本地库；单用户自用，无多租户。
- **来源混合**：服务器在线 + 本地离线双来源，离线模式是一等形态（离线首页直落）。
- **技术栈**：单 Activity + Compose Navigation；已有 `NavEntryKey` 统一 IA、纯函数门控（`NavigationIa.kt`）与三形态共享同一份目的地列表——W3 不推翻 IA，只校准容器与动效。

### 一级容器选型（NAV-03，定稿）

- **Compact（<600dp）= 底部标签栏 4 tab（首页/视频/音乐/书架）+ 边缘手势抽屉（全量入口）**。理由：拇指可达性（K1「高频操作下沉拇指区」）；抽屉承载低频全量入口不挤底栏。
- **Medium+（≥600dp）= 常显左侧轨**：折叠 72dp / 展开 `clamp(屏宽×30%, 200, 240)dp`；抽屉在平板形态停用。
- **排除顶部标签栏**：域仅 3–4 个但层级深 2–3 级，顶部 tab 占纵向内容高度，且与库页内筛选 tab 语义冲突。
- **跨形态阈值 = 600dp**（Material Medium 断点；W70 已在 Pad 5 711dp 验证）。

### 媒体类型与来源（NAV-04 / NAV-05，定稿）

- 媒体类型 = **一级并列项**（视频/音乐/书架与首页并列），不是筛选项；**任意媒体类型互切 = 1 次点击**（底栏/侧轨直达，W56「顶层图标回主页」语义不变）。合理性：自用场景域间切换高频（听歌↔看剧），筛选式至少 2 次操作。
- 来源（服务器/本地）= 正交维度，**不进一级**：服务器库在「媒体库」二级分组、本地库在「本地媒体库」子分组、离线模式由首页整体切换承接。理由：用户心智是任务（"看电影"）而非数据出处；来源入口放二级不影响任何域的 1 次互切。

### 高频入口（NAV-06，定稿）

继续观看/最近添加 = 首页货架首屏（不动）；我的收藏 = 侧栏一级（W60b 已有）；下载 = 侧栏一级 + 活动任务角标。以上均 0–1 次点击可达，无二次点击深埋。

### 动效规格（NAV-07~10，定稿值）

| 项 | 定稿值 | 变化了什么（NAV-07 答辩） |
|---|---|---|
| 抽屉/侧轨开合 | 开 **200ms** / 关 **160ms**，EmphasizedDecelerate，无弹跳 | 导航容器进出视口 |
| 一级页内容切换 | **120ms**，仅 opacity + translateX **8dp**，不触发布局重排；替换现状 NavHost 全局 300ms fade | 内容域切换，方向感来自位移 |
| 页内二级导航 | 沿用 120ms 同规格 | 层级进退 |
| 选中态 | **离散**：aurora 渐变指示条 + 背景 pill + 字重 500→700；不依赖任何动画 | 当前位置 |
| 按压反馈 | 300ms 内背景渐变到 `bg3`（触摸设备无悬停） | 触点确认 |
| Reduce Motion | 全部退化为即时切换或 ≤50ms 淡入；选中态全部保留 | — |

弹簧动画仅允许用于可中断接管的手势跟手场景（K1 §3.2），导航容器禁用弹簧。

### 平台适配表（NAV-11/12，定稿）

| 形态 | 一级容器 | 层级一致性 | 保留线索 |
|---|---|---|---|
| 手机竖屏/横屏（<600dp） | 底栏 4 tab + 手势抽屉（全量） | 底栏 = 侧轨 `bottomNavKeys` 子集 | 选中态/角标/门控全保留 |
| 平板（≥600dp） | 常显侧轨（全量入口 + 库分组） | 侧轨 = 抽屉内容 1:1 | 同上 + 分组结构 |
| TV | 冻结，仅参考 | — | — |

互转原则：同一 `NavEntryKey` 列表单一来源；切形态只换容器，不换 IA、不丢选中态。

### 风险假设与降级（NAV-13）

1. **玻璃模糊性能**：K1 稿「宽屏左玻璃侧栏」的实时 blur 在中低端设备有掉帧风险。**定稿降级**：常显侧轨用 `bg1` 实底 + `glassBorder` 发丝线，**不做实时 blur**；blur（glass token）只用于悬浮层（迷你播放器/顶栏/悬浮导航）。
2. **底栏隐藏开关与新手势冲突**：W4 手势体系落地时适配；W3 保留 `hideBottomBar` 语义不变。
3. **图标第二批破坏 QA 语义**：全部 `testTag`/`contentDescription` 保持不变，只换矢量几何；QA 用例不许改断言。

## W3 任务卡（定稿）

> 门禁（每任务）：相关模块单测 + `ktfmtCheck` + `:app:phone:assembleDebug --max-workers=2` 绿；核心链路 K60 真机验证。每任务一个子代理一个 commit。

### T0 ✅ 导航现状调查 + 任务卡定稿（Kimi，2026-11-18 完成）

### T1 导航动效与容器校准

- **目标**：上表动效规格落地。NavHost 全局过渡 300ms fade → 120ms opacity+translateX(8dp)；抽屉开合校准 200/160ms（替换 M3 默认时长）；Reduce Motion 退化；一级切换不触发布局重排。
- **文件边界**：`app/phone/.../NavigationRoot.kt`、`core/.../components/CinefinDrawer.kt` 及导航组件；不动路由表与 IA 门控。
- **验收**：K60 实测底栏切换/抽屉开合时长与手感；Reduce Motion 开关下即时切换且选中态可见；`app/phone` + `core` 单测绿。

### T2 侧轨/抽屉/底栏 K1 换肤

- **目标**：按 K1 定稿换肤导航容器——侧轨 `bg1` 实底 + `glassBorder` 发丝线 + 选中 aurora 渐变指示条 + 背景 pill（圆角按形状 token）；抽屉同语言；底栏选中态同一离散规则。**禁止**实时 blur（决策定稿）。
- **文件边界**：`NavigationRoot.kt` 的 `CinefinSideNavigation` 段、`core` 导航组件、`CinefinDrawer.kt`。
- **验收**：K60 + Pad 双形态截图级对比 K1 稿（token 像素命中）；aurora/light/amoled 三主题无残留色；单测绿。

### T3 图标第二批（约 66 个 + settings 5 个同名 + T4 三处几何微调）

- **前置**：Kimi 先出 SVG 样张规范（统一 24dp 栅格/2dp 笔宽/圆头延续 W1-T4 规范）后派单。
- **验收**：29→全量图标零 testTag 变化；`aapt2`/渲染抽查；K60 抽屉+设置+下载页取证。

### T4 形状 token 切换 + 页面级换肤首批

- **目标**：`CinefinShapes.M3`（28/22/16/12/8）→ K1 圆角（海报 14/卡片 20/按钮 16/玻璃容器 32–44）；首页/库页首批页面级换肤。
- **验收**：K60 首页/库页 token 命中；三主题抽查。

### T5 旧 Prism 中性常量清理

- **前置**：T4 完成、reader 对 `OnSurfaceLight` 引用收口后执行；light aurora3 调值须同步 `CinefinThemesTest`。
- **验收**：`CinefinTokens` 无旧 Prism 残留引用；core 单测绿。
