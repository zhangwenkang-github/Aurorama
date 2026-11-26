# Aurorama 2.0.2 · UI 系统重构设计定稿（V202_DESIGN）

> 状态：定稿（2026-11-26，用户批准方案 + 当日补充裁定）｜分支 `codex/v2.0.2`（基线 `codex/v2.0.1` tip `91912da8`）
> 性质：**设计决策文档**。deepseek 子代理按本文件实现，零设计自由裁量；与代码冲突时以本文件为准。
> 补充裁定（2026-11-26 用户）：黑底清理范围**包括播放器控件**（D4 已并入）。
> 总原则一句话：**域色 = 身份，极光 = 行动，玻璃 = 图上控件唯一垫层**（沿用 2.0.1 定稿，新增玻璃裁定）。

## 0. 诊断依据（已全部在 2.0.1 代码核实）

- 音乐页头部三段堆叠（56dp 顶栏 + 44dp 重容器分段控件 + 独立工具行）；书籍/库页五层堆叠（顶栏 + chip 行 + 工具行 + 生效 chips + 固定头部）；两页两套视觉语言。
- 黄橙残留两根因：①XML 主题层仍是 v1 朱砂橙（`colors.xml` `primary_dark=#d2553c` 喂 `colorPrimary`→系统对话框/开关/涟漪/播放器进度/setup 按钮/占位图）；②Compose 层约 20 处 `media.base` 琥珀用于加载圈/进度/选中/氛围光晕，违反 2.0.1 定稿「琥珀退出一切交互强调」。
- 黑块残留：`BaseBadge` 黑 62%（7 徽标共用）、`ItemTopBar` 黑 60%、多选圆点黑 55%、播放器跳过 chip 黑 80%、播放器图标键手写玻璃 28%/44%、桌面歌词 v1 写死色。
- 安全区：2.0.1 T21 只修播放器竖屏；`CinefinPageTopBar` 用 `statusBarsPadding()` 不含挖孔；`rememberSafePadding` 困在 `app:phone`；搜索页顶部无避让；无全局封装。
- 「最近阅读」是页面级固定头部（`LibraryScreen.kt:480` 插槽），不随滚动 → 割裂。
- 无颜色/图标台账（126 图标散 5 模块 270 处调用，无映射表）。

## D1 · 全局安全区统一

1. `rememberSafePadding` 从 `app:phone` 下沉到 `core`（原处留 typealias/委托或全量改引用，取改动小者）；新增 `Modifier.cinefinSafeTop()` = `WindowInsets.safeDrawing.only(Top)` padding，状态栏∪挖孔统一口径。
2. `CinefinPageTopBar` 的 `statusBarsPadding()` 改为 safeDrawing 口径（一处改，14 页全受益）；横屏/侧挖孔 start/end 由既有 `rememberSafePadding` 覆盖，逐页核查。
3. 搜索页结果区补顶部安全区；冷启动品牌页补 safeDrawing。
4. 沉浸页（播放器/阅读器）结构不动；阅读器控制层 `statusBarsPadding` 核查挖孔，压挖孔则同口径修。

## D2 · 音乐/书籍头部统一轻量标签

新共享组件 **`CinefinLibraryTabs`**（core），替代音乐分段控件与书籍 chip 行：

- **单层文本标签**：无容器底、无描边；选中 = `auroraInk` 文字 + 2dp 极光渐变指示条（Symfonium / 官方 Jellyfin 语言），未选中 = `onSurfaceVariant`；行高 40dp，标签间距 Space4，可横滑，左右对齐 pageGutter。
- **音乐页头部收敛为两行**：①顶栏 56dp，右侧图标精简——收藏/最近播放/睡眠定时收 ⋮，只留搜索 + ⋮；②标签行 = `CinefinLibraryTabs` + 右侧库选择 chip（compact）+ ⋮（收纳排序/来源/每页数量，沿用 2.0.1 T14 菜单）。「播放全部 + 随机」降为内容区上方 36dp 紧凑工具行（仅歌曲/专辑 tab 显示）；播放全部保留 Aurora 渐变，高 38→36dp、去重投影。
- **书籍/库页头部收敛为两行**：①顶栏不动；②标签行 = `CinefinLibraryTabs` + 右侧排序、视图切换 2 个图标键 + ⋮（收纳筛选）；**删除独立工具行**（计数文本并入标签行尾弱化显示），生效筛选 chips 保留一行横滑 compact。
- 头部与内容区间距统一增大到 Space5；`MusicPrimaryToolbarLayout` 自定义 Layout 与其单测随重构更新/退役。
- 兼容：音乐 5 tab 全保留靠横滑兜底窄屏；书籍 tab 由 `LibraryHeaderRules` 供给；视频库页共用组件一并受益。

## D3 · 最近阅读入流

- 「最近阅读」「本地书籍」从页面级固定头部改为**网格内容流首段**（`LazyVerticalGrid` header item），随滚动离开；空列表零占位。
- 卡片样式不变（104dp 竖版 2:3）；段落间距对齐新头部节奏。
- `LibraryScreen` 的 `headerContent` 插槽机制改为 grid item；W6 验收基线（`V2_W6_TASKS.md`）对应更新。

## D4 · 黑底清理：图上控件唯一垫层 = 主题玻璃（含播放器控件）

裁定：图上控件/徽标需要垫层保可读性，但**一律走 `colors.glass` + `glassBorder`，全仓禁止新写 `Color.Black.copy`**；既有手写黑底全部清除：

- `BaseBadge` 默认容器 黑62% → `glass`（评分徽标维持极光淡色 pill 不动）。
- `ItemTopBar.OverlayIconAction` 黑60% → `glass` + `glassBorder`。
- `CinefinSelectIndicator` 未选中 黑55% → `glass` + 白 24% 描边。
- **播放器控制层（2026-11-26 用户补充裁定纳入）**：
  - `PlayerIconButton` 手写玻璃 28%/44% → 读 `glass`/`glassBorder` token；
  - 缓冲转圈背板 黑45%、跳过片段 chip 黑80%、字幕提示 chip `0x66000000` → 同口径 token 化；
  - 顶/底控制条渐变遮罩**功能保留**（图上文字可读性行业标准），色值归 token；XML `player_background`/`player_hud_background` 值对齐主题玻璃色系（D5 批①同步）。
- 桌面歌词悬浮窗 v1 写死色（`0xE60E1116`/`0xB30E1116` 等）→ token 化（glass / surface 系 / aurora1）。
- 守卫：扩展 `DesignTokenConsistencyTest`，钉死上述组件源码不再出现 `Color.Black.copy` 与写死黑值。
- 全屏 scrim（模态遮罩、`Scrim` token 本身）不动。

## D5 · 黄橙清零

**批① XML 主题层**（一处改全系统受益）：
- `colors.xml`：`primary_dark #d2553c` → 极光青 `#3DE8C2`；`on_primary_dark` → `#04201A`；container 派生值同步；`neutral_*` 墨色阶对齐 Compose bg 阶（`#04070D/#080D17/#0D1522/#131D30`）；`logo_primary` → aurora1；删死 token（`gold_300/yellow/blue_*/green_*`）；`themes.xml`/`values-night/themes.xml` 同步。
- `android:colorBackground` 与 Compose 页底同色（消冷启动闪色）。
- 核查死资源 `activity_player.xml`（无 Kotlin 引用则删）；`exo_main_controls.xml` 的 `?attr/colorPrimary` 随改色自动变极光。

**批② Compose 交互强调归极光**（2.0.1 定稿未竟部分）：
- 加载圈（`HomeTopBar`、`SearchBar`）→ M3 `primary`；`LandscapeItemCard`/`PosterItemCard` 进度 → `auroraGradient`；`SortByPanel` 选中 → `navSelectedContent`；`DownloadRows`/`BatchDownloadDialog`/`DownloadStatusBadge`/`DownloaderCard` 的 `media.base` 逐点裁定：进度/加载/选中/文字强调 → 极光或中性，纯域身份位（占位图标 tint、域徽标）保留域色。
- `LumenSurface` 详情页琥珀氛围光晕 → 极光系（aurora2 @40%）；琥珀退出大面积可见位。
- `UI_DESIGN_SYSTEM.md` 域色表勘误对齐（影视=琥珀仅身份位）。

**保留不动**：字幕/歌词颜色预设（用户可选项）、Epub 批注黄 `#FFD54F`、阅读护眼纸色 `#A8843C`（仅正文区）、TV 端（冻结）、动态色主题。

## D6 · 颜色 + 图标台账

- 新建 `docs/design/COLOR_ICON_INVENTORY.md`：
  - **Token 表**：`CinefinTokens`/`CinefinColors` 每值 → 语义槽 → 引用点（含 `mediaColorsFor` 域映射图）；
  - **图标表**：126 个图标 → 模块 → 引用位置 → tint 化状态 → 死资源标记；
  - **残留登记表**：D4/D5 全点位 + 处置状态。
- 今后主题/配色修改以台账为核对清单；`UI_DESIGN_SYSTEM.md` 顶部状态块指针更新。

## 验收口径（全波次通用）

- 每卡门禁：`:app:phone:assembleLibreDebug` + `ktfmtCheck` + 相关模块单测（`:core`/`:app:phone` 用 `testLibreDebugUnitTest`，`:data` 等用 `testDebugUnitTest`）+ 证据清单（构建关键行 + diff 摘要 + K60 uiautomator 文本取证；截图预算按 AGENTS.md）。
- C9 输出变更对账表：D1–D5 每点位「改前 → 改后 → 验证方式」，自查拥挤/多余图标/旧样式残留。
- 状态覆盖：默认/选中/空态/长文案溢出/横屏挖孔/浅色主题，纳入每页验收。

## 明确不做（本期）

字阶全站迁移、玻璃实时 blur、新主题（Prism/Nocturne/Monolith）、TV 端、播放器横屏布局重构。
