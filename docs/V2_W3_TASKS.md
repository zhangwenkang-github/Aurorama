# 2.0 W3 · 导航与新 UI 骨架（任务卡）

> 状态：`IN_PROGRESS`（2026-11-18 T0 完成：现状调查 + NAV 决策定稿，任务卡定稿待派）
> 依据：`docs/V2_REQUIREMENTS.md` §10（V2-NAV）与 §15 波次（W3 ← W1）；设计唯一权威 `docs/V2_DESIGN.md`。
> **执行约束**：设计决策（容器选型、动效参数、视觉样式）全部由 Kimi 定稿于本卡「W3 导航决策定稿」节；deepseek 子代理只按定稿执行，遇到定稿未覆盖的样式问题停下来报告，不自行发挥。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W3 开工基线 `7540f829` + W2 遗留提交（mpv 过早 stop 修复 + 判别性 harness，本会话首日提交）。
- **W2 遗留核查（2026-11-18 复核通过）**：① 第三个 flaky 已 cherry-pick 回 master（`a1e2951c`，master Build CI 不再阻塞）；② mpv 过早 stop 修复（`MPVPlayer.kt` 当前 source 已 START 且无 pending 时不 stop / stale 无 pending 只清理）+ 3 条 RED→GREEN 单测在工作区，Pad 实测 E1 起播/暂停续播/E1→E2 通过；③ VM `retryPlaybackAt` 判别性 harness 已补（`PlaybackRecoveryCopyTest.retryPlayerErrorSeeksToItemIndexCapturedBeforePrepare`，断言 `seekTo(2, 42000)`，回退裸 seekTo 时如期变红）。
- **T0 完成（2026-11-18，Kimi 亲自）**：导航现状调查 + NAV-01~13 决策定稿（见下节）；任务卡 T1–T5 定稿（`a0d88660` + `fe328e0f`）。
- **T1 ✅（2026-11-18，commit `38a4a8ec`，deepseek 执行 / Kimi 验收通过）**：导航动效 token 唯一源 `CinefinNavigationMotion`（120ms/8dp、抽屉 200/160ms、RM 50ms，EmphasizedDecelerate 无弹簧，`NavigationMotionTest` 锁定数值）；抽屉换 `AnchoredDraggableState` 自绘外壳（M3 规格不可外部定制，任务卡已授权）；Reduce Motion 接 `ANIMATOR_DURATION_SCALE`（ContentObserver 监听）。门禁：core 451 + phone 529 单测 0 失败、ktfmtCheck、assembleDebug（--max-workers=2）全绿；K60 实测抽屉开 211ms/关 176ms、RM 下 19/8ms 即时、底栏切换 0 FATAL、选中态可见。Kimi 批准两项报备微决策：popEnter/popExit 显式镜像 predictive 槽；手势吸附统一 200ms 减速。
- **T2 ✅（2026-11-19，commits `f58104d1` + `3a1388a3` + `61cf998b`，deepseek 执行 / Kimi 验收通过）**：三容器 K1 换肤——侧轨 bg1 实底 + 右缘 glassBorder 发丝线（禁实时 blur）；选中 = 16dp 圆角 pill（aurora1@12%，浅色 16%）+ glassBorder 描边 + aurora1 图文；底栏悬浮玻璃条（glass + 32dp 圆角 + 12/8dp 外缩）+ 选中图文 textPrimary + 20×3dp aurora 渐变指示条。浅色选中图文定稿 `#0A6B5A`（页面 6.17 / 侧轨 pill 5.16 / 抽屉 pill 4.82，全过 AA，`CinefinNavigationSelectionTest` 钉死）。门禁全绿；K60/Pad 双形态三主题像素取证命中。Kimi 裁决：底栏 12/8dp 外缩批准；AMOLED 玻璃微亮接受；底栏 textPrimary vs 侧轨 aurora1 非笔误；**侧轨底部用户芯片缓建**（列 W3 后续候选）。
- **T3 ✅（2026-11-19，commit `0cf22094`，deepseek 执行 / Kimi 验收通过）**：82 个图标归一到 K1 标准（core 47 / settings 19 / modes 10 / player 6），零改名零 Kotlin 改动；三张定稿样张 md5 逐字节命中；`ic_speed_forward` 重建 24dp 栅格、`ic_unlock` 对齐锁体。例外 10 个均有理由（品牌/启动图标/通知/level-list/厂商标识）。门禁：core 454 + phone 529 单测 0 失败、ktfmt、构建全绿；K60 抽屉/设置/下载页取证通过。遗留小项：① `activity_player.xml:202` 手势倍速 ImageView 仍 36×24dp（随 T4 顺带改 24dp）；② 8 个 core 图标用 `@android:color/white`（功能等价，可选清理）；③ `ic_minus_fat` 死资源保留。
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
| 选中态 | **离散**（对齐 K1 tablet-home 稿）：整行圆角 pill（aurora 淡色玻璃填充 = `aurora1` 低透明度 + `glassBorder` 发丝线描边）+ 图标/文字转 `aurora1` 色；不依赖任何动画 | 当前位置 |
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

- **目标**：按 K1 定稿稿（`docs/design/k1-aurora/tablet-home.png`）换肤导航容器——侧轨 `bg1` 实底 + 右缘 `glassBorder` 发丝线；选中行 = 整行圆角 pill（`aurora1` 低透明度填充 + `glassBorder` 描边）+ 图标/文字 `aurora1` 色，未选中 `textSecondary`；顶部品牌行（logo + 极光幕字标）与底部用户芯片沿用现状结构换 token；抽屉/底栏同一离散选中规则。**禁止**实时 blur（决策定稿）。
- **文件边界**：`NavigationRoot.kt` 的 `CinefinSideNavigation` 段、`core` 导航组件、`CinefinDrawer.kt`。
- **验收**：K60 + Pad 双形态截图级对比 K1 稿（token 像素命中）；aurora/light/amoled 三主题无残留色；单测绿。

### T3 图标第二批（约 56 个 core 旧图标 + settings 19 个（含 5 个同名）+ W1-T4 三处几何微调）

- **现状盘点（2026-11-18 T0 实测）**：core `drawable/ic_*.xml` 共 86 个，其中 56 个未引用 `colorControlNormal`、78 个仍写死 `fillColor="#"`；settings 模块另有 19 个（`ic_closed_caption/download/music/play/speaker` 与 core 同名）。
- **造型规范（Kimi 定稿，沿用 W1-T4 既定事实标准）**：24dp 视框；描边图标 `fillColor=#00000000` + `strokeColor=#FFFFFFFF` + `strokeWidth=2` + round cap/join；填充变体（`*_filled`）纯 `fillColor=#FFFFFFFF`；混合式（如仪表盘指针+中心点）描边主体 + 填充点缀；文件头保留「K1 图标体系」注释；根元素 `tint=?attr/colorControlNormal`。禁止新增写死 fillColor。
- **三处几何微调已定稿出样张（2026-11-18，Kimi，渲染对比验证通过）**：`docs/design/k1-aurora/icon-w3-samples/`（`ic_settings.xml` 8 齿均匀化 / `ic_music_sleep.xml` 内弧终点缩短消尖刺 / `ic_player_speed.xml` 指针退出中心点；before/after 见 `preview-fixes.png`）。T3 直接应用这三份文件内容，不得改动。
- **前置**：样张已出 ✅；批量执行延续 W1-T4 事实标准；QA 语义（testTag/contentDescription）零变化。
- **验收**：全量图标零 testTag/contentDescription 变化、零新增写死 fillColor；`aapt2`/渲染抽查；K60 抽屉 + 设置 + 下载页取证。

### T4 形状 token 切换 + 页面级换肤首批

- **形状定稿（2026-11-19 Kimi，对齐 K1 §3.2：海报 14 / 卡片 20 / 按钮 16 / 玻璃容器 32–44）**：
  - 新增槽位 `Poster = 14`（海报/封面图）、`Glass = 32`（悬浮玻璃容器：底栏/迷你播放器/悬浮顶栏）。
  - 语义重命名换值：`Sm 12 → Button 16`（按钮/chip/输入框/导航 pill）；`Md 16 → Card 20`（通用卡片/横版卡）；`Lg 22 → 并入 Card 20`；`Xl 28 保留`（对话框/大面板，非玻璃）；`Xs 8 / TwoXs 4 / Full 不变`。
  - 迁移规则：海报网格/封面 → `Poster(14)`；通用卡片与面板（侧板/书架大卡）→ `Card(20)`；按钮/chip/输入框/Toast/导航 pill → `Button(16)`；悬浮玻璃容器 → `Glass(32)`。`CinefinShapes.M3` 槽位同步更新并在 `DesignTokenConsistencyTest` 钉死新值。
  - 首批页面范围：首页 + 库页 + 导航容器（导航 pill 已在 T2 取 16，T4 统一走 token）；其余页面列入后续批次。
- **验收**：K60 首页/库页圆角 token 命中（海报 14/卡片 20/按钮 16）；三主题抽查；core + phone 单测绿。

### T5 旧 Prism 中性常量清理

- **前置已查清（2026-11-19）**：`CinefinTokens.OnSurfaceLight`/`OnSurfaceDark` 仅剩阅读域引用（`modes/book/.../ReaderSettings.kt` 3 处）+ `DesignTokenConsistencyTest` 钉值 + `ReaderSettingsTest` 断言。
- **定稿（Kimi）**：阅读器正文文字色属阅读域 4 套子主题自管范畴——把 `#1B212B` / `#F4F1EA` 两值迁移为 `modes/book` 阅读域自有常量（如 `ReaderInkLight` / `ReaderInkDark`，命名以实现时阅读域现有命名风格为准），core `CinefinTokens` 删除这两个常量；`DesignTokenConsistencyTest` 对应钉值同步删除，`ReaderSettingsTest` 断言改指阅读域常量。同时顺手清理散落的「Prism」表述注释（`LumenSkeleton.kt`、`ReaderScreen.kt`、`NavigationRoot.kt` 皮肤开关注释等）：Prism 已退役，注释改述为「App 主题（Aurora 系）」语义；只改注释不改行为。
- **验收**：core + book 模块单测绿；全仓 grep 无 `CinefinTokens.OnSurfaceLight/Dark` 残留；构建绿。
