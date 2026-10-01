# Cinefin UI 重塑任务线（UI_PLAN）

> 任务线：UI 重塑（设计系统落地）｜波次：W5-R3（R3-UI-LUMEN）｜分支：`feature/r3-ui-lumen`｜最后更新：2026-10-01
> 权威设计依据：`docs/UI_DESIGN_SYSTEM.md`（S4 v1.0，Prism 棱镜）。
> 本文件是该任务线的**唯一权威文档**：需求、决策、进度、验收、踩坑与日志都写在这里，不新建零散 `.md`。

> 波次历史：W1-R3（R3-TOKENS，`feature/r3-ui-tokens`，已合并 master）= token 与四类基础组件； W3-R3（R3-PAGES-A，`feature/r3-ui-pages-a`）= 音乐 / 阅读页面接入 Prism + 路由入口注册； W4-R3（R3-PAGES-B）= 首页 / 媒体库 / 详情 / 搜索 / 下载 / 设置 / 抽屉 / 欢迎页全套换新 + 导航形态分级（手机底部 Tab / 平板侧轨）+ Prism 字阶收敛； W5-R3（R3-UI-LUMEN，本波）= 首页与视频详情改用 S1 流光（A · Lumen）手法 + 媒体库去拥挤 + 侧柜统一列表（取消「更多」分组）与入口行为一致化。

## 1. 任务线定位

- 目标：把 Prism 设计系统（v1.0）落成 Compose 主题与基础组件，供影视 / 音乐 / 阅读三域页面复用。
- W1 范围（两件"一屏能验证完"的事）：
  1. 设计 token → Compose 主题映射（亮 / 暗、三域媒体色、字阶、形状、动效、间距、无投影规则）；
  2. 基础组件：按钮媒体色变形、卡片、导航、抽屉 + 组件预览 + token 一致性单测。
- W3 范围（本轮，两件事）：
  1. 音乐页（曲库浏览 / 正在播放底栏 / 队列面板）与阅读页（阅读页外壳 / 阅读设置面板）接入 Prism；补 §8.2 分段控件、§8.3 筛选 chip、§8.10 空状态 + 预览；
  2. 路由入口注册（`NavigationRoot.kt` 唯一写者）：`MusicModeRoute` + 抽屉「音乐」入口、书籍条目 → `ReaderActivity`、`ReaderActivity` 改回 `exported=false`。
- 明确不做：播放器控件改造（播放器线 W4）、歌词 / 阅读离线业务逻辑（R2-LYRICS / R1-OFFLINE）、字体文件打包（授权未确认）、TV 模块、`settings.gradle.kts` / `libs.versions.toml` / `AppPreferences.kt` 改动。

## 2. 代码地图（本线落点）

| 位置 | 内容 |
|------|------|
| `core/src/main/java/.../core/presentation/theme/CinefinTokens.kt` | 原始色值（全项目唯一允许 `Color(0x…)` 的**新**文件）+ 状态层 / 合成系数 / 纸色主题 / 覆盖层 |
| `.../theme/MediaColors.kt` | `MediaColors`（7 token）+ `MediaFilm` / `MediaMusic` / `MediaBook` + 亮色合成函数 |
| `.../theme/CinefinColors.kt` | 语义色数据类（亮 / 暗）、`LocalCinefinColors`、`cinefinColorScheme(domain, dark)` |
| `.../theme/CinefinType.kt` | `CinefinType`（v1.0 字阶 + 扩展 token）、`CinefinTypography`、`LegacyTypography`（旧值桥接） |
| `.../theme/CinefinShape.kt` | `CinefinShapes`（28 / 22 / 16 / 12 / 8 / 4 / full）+ M3 `Shapes` |
| `.../theme/CinefinMotion.kt` | 时长（100 / 200 / 220 / 280 / 420 / 560 / 40 / 700ms）+ 4 条曲线 |
| `.../theme/CinefinSpacing.kt` | 12 档间距（4 / 8 / 12 / 16 / 20 / 24 / 26 / 32 / 40 / 48 / 64dp） |
| `.../theme/CinefinTheme.kt` | `ContentDomain`、`LocalMediaColors`、`LocalCinefinTypography`、`CinefinTheme(domain, darkTheme, …)` |
| `.../core/presentation/components/` | `CinefinButton` / `CinefinCard`（Poster / Wide / ListRow）/ `CinefinNavigation`（侧轨 / 折叠轨 / 底部 Tab）/ `CinefinDrawer` / `CinefinInteraction` |
| `.../components/CinefinComponentPreviews.kt` | 6 组 `@Preview`：三域按钮、五状态矩阵、卡片、导航、抽屉 |
| `core/src/test/java/.../theme/DesignTokenConsistencyTest.kt` | token 一致性单测（13 项，逐值核对设计系统） |
| `app/phone/src/main/java/.../presentation/theme/Theme.kt` | 入口包装：core 主题 + `LegacyTypography` 桥接 + `LocalSpacings` |

已删除的旧落点（内容已迁移）：`app:phone` 的 `Color.kt`（旧亮 / 暗方案）、`Typography.kt`、`Shape.kt`。
`app:phone` 的 `Spacing.kt`（`MaterialTheme.spacings`，60 处存量引用）与 `Motion.kt`（2 处存量引用）按桥接保留，待页面会话收敛。

## 3. 决策记录

| # | 决策 | 说明 |
|---|------|------|
| D1 | **Typography 归位**（负责人 2026-09-30 冻结） | 设计系统字阶定义在 `core`：`CinefinType` / `CinefinTypography`（v1.0）与 `LegacyTypography`（迁移前 `app:phone` 原值）。`app:phone` 只保留 1 个入口包装，过渡期把 `LegacyTypography` 交给 MaterialTheme，保证 145 处存量 `MaterialTheme.typography` 引用排版零回归；W3/W4 页面按 Prism 字阶落地后改回默认并删除桥接。 |
| D2 | 颜色立即切到 Prism | `MaterialTheme.colorScheme` 由 `cinefinColorScheme(domain, dark)` 生成（primary = 当前域媒体色），全 App 立即生效；旧 `ColorLight` / `ColorDark`（墨 + 朱砂）保留在 `core/.../theme/Color.kt` 仅作历史兼容，不再被主题引用。 |
| D3 | 媒体色容器双轨 | 深色主题直接用文档精确值（`container` / `containerPressed` / `outline`）；同时提供 `containerOver(surface)` 等合成函数供亮色主题与自定义表面派生。单测校验两者偏差 ≤ 2/255。 |
| D4 | 组件放 `core` | 三域页面（`modes/film`、`modes/book`、`modes/music`）都依赖 `core`，基础组件放 core 可被 R1/R2 直接复用。为此在 `core/build.gradle.kts` 增加 compose foundation / material3 / tooling-preview 依赖（全部使用 `libs` 现有别名，未改 `libs.versions.toml`）。 |
| D5 | 卡片不绑定图片库 | `PosterCard` / `WideCard` 的图片是 `@Composable` 槽位，core 不依赖 Coil；加载、占位、失败态由页面层决定。 |
| D6 | 无 ripple，自绘状态层 | 设计系统的 hover / focus / pressed 是颜色与描边过渡（200ms Standard）；组件统一 `indication = null` 并自绘状态层，符合"细线 + 无投影"质感。 |
| D7 | 保留 `dynamicColor` 存量偏好 | 用户设置里的动态取色仍可用（系统取色只影响 M3 槽位）；Prism 是默认色板，媒体色纪律不受动态取色影响。是否在设置页下线动态取色，留待设置页改造会话决策。 |
| D8 | 字族先平台兜底 | 字号 / 行高 / 字重 / 字距已按 v1.0 全量落地；MiSans / Literata Italic / Cascadia Mono / Noto Serif SC 的授权与子集化未完成前，用平台 sans / serif / mono 兜底，后续只替换 `FontFamily`。 |
| D9 | 无投影门禁 | 新组件代码无 `Modifier.shadow`、无 `elevation > 0`；层次由表面色差 + 1dp 描边 + 顶部内高光构成（`CinefinTokens.TopHighlightAlpha`）。 |
| D10 | W3 剩余组件落 `core`（3 件） | 按 §8.2 / §8.3 / §8.10 新增 `CinefinSegmentedControl` / `CinefinFilterChip` / `CinefinEmptyState`（+ 4 组预览）。签名面向页面：分段与 chip 用「items + selected + label 映射」，空状态用「icon / action / secondaryAction」槽位，core 不绑定图片库与业务类型。`cinefinClickable` 由 `internal` 放开为公开修饰符，页面自绘可点击区（缩略图、主题缩略图、拖动柄）直接复用无 ripple 语义。 |
| D11 | 阅读器纸色主题的媒体色覆盖 | §8.14 例外（纸色 / 护眼主题把阅读域天青换成纸页棕）不再由面板手工传色，而是 `ReaderSettings.mediaColors(systemDark)` 派生一份 `MediaColors`（base / bright / dim / container / outline 全部改纸页棕，`container` 按 `Media.Container @16%` 合成到阅读底色），再经 `LocalMediaColors` 下发给顶栏按钮、分段控件、chip 与滑块。深色 / OLED 仍返回 `MediaBook`。 |
| D12 | 音乐页并入同一个导航抽屉 | `MusicModeScreen` 作为 `composable<MusicModeRoute>` 注册进 `NavHost`，抽屉新增「音乐」一等入口（与首页并列）；页面新增可选参数 `onOpenDrawer`（默认 null，不破坏 R2 调用方），并将 `MusicModeRoute` 加入 `showNavigation` 白名单以支持手势拉抽屉。不新建底部 Tab（底部导航换新属 W4 全页面换新）。 |
| D13 | 书籍入口 = 本机阅读器（EB-10） | `FindroidFolder.kind == "BOOK"`（大小写无关）时用显式 Intent 打开 `ReaderActivity`（`exported=false`），不再进 Web 控制台 `/details`。书架 = 抽屉里服务器 Books 库（沿用现状，不新建独立书架页）。影响面见 §5 W3 验收「未决项」。 |
| D14 | 音乐库入口直通音乐模式（P0 修复） | 测试服务器没有 MusicAlbum 实体，`LibraryViewModel` 对 `CollectionType.Music` 只查 `MUSIC_ALBUM` → 音乐库永远空列表。修法（限定在 `NavigationRoot.kt`，不动 `modes:film`）：新增 `libraryEntryRoute(...)`，媒体库卡片 / 抽屉 / 搜索结果里凡是 `CollectionType.Music` 的库一律路由到 `MusicModeRoute`（客户端按曲目分组出专辑 / 艺术家 / 歌曲 / 歌单）；`LibraryRoute` 组合里对 Music 类型做兜底重定向，防止历史返回栈 / 深链再落回空列表。 |
| D15 | 导航形态分级（W4-R3） | §4.4 窗口分级落地：Compact（<600dp）用 core `CinefinBottomTab` 4 tab（影 / 乐 / 书 / 更多，64dp + 安全区）；Medium 起用 `CinefinSideRail` 侧轨（840–1199dp 折叠 88dp、≥1200dp 默认展开 164dp，底部「收起 / 展开」手动切换，`rememberSaveable` 记忆）。侧轨 / 底部 tab 只在顶层路由（首页 / 媒体库 / 下载 / 音乐 / 书库）出现；「书」tab 无 Books 库时回退打开抽屉。「更多」= 打开 Prism 抽屉（库列表 / 管理 / 服务器等全量入口）。 |
| D16 | 抽屉换 `CinefinModalDrawer` + 分组顺序单一来源（W4-R3） | 删除旧 `presentation/navigation/CinefinDrawer.kt`（含 §2.6 禁止的朱砂竖条），改为 core `CinefinModalDrawer` + `CinefinDrawerHeader`（品牌 + 账号 + 服务器行）。`selectedIndex / onSelect` 与分组拍平顺序**同源**：先 `groupBy` 生成 groups，再从同一 grouped 结构拍平 actions，避免「分组聚合把未分组项提前」导致点击错位（见踩坑 17）。另给 core 组件补 `gesturesEnabled` 参数以保留非顶层页面的手势约束。 |
| D17 | **首页与视频相关页面改用 S1 流光（A · Lumen）手法**（W5-R3，用户 2026-10-01 反馈） | 设计依据 = `docs/design/s1-direction-a/README.md`（S1 决策：B 骨架 + A 沉浸手法）。落地四件事：①**全出血头图**——首页主视觉从「通栏方块」改为页边内的同心圆角大卡（手机 28dp / 平板 22dp），16:9 ↔ 21:9 随窗口切换、高度上限 420dp；②**底部渐隐 + 内暗角**——新增 `lumenBottomScrim` / `lumenVignette` 两个纯渐变（无新色值），文字永远压在可读暗场上；③**双层嵌套卡片**——`LumenCardFrame` 统一「1dp 描边 + 顶部 1px 内高光」，描边画在内容之上，压在图上也可见；④**入场动效**——`Modifier.lumenEntrance(index)` = opacity 0→1 + translateY 8dp→0、560ms `Emphasized`、40ms 错峰（§6.3），只走 `graphicsLayer`。详情页（电影 / 剧集）同语言：海报 + 眉标 + 大标题 + 元信息短标签 + 主行动压在头图上，平板右侧挂制作信息表。 |
| D18 | **侧轨 / 底部 Tab / 抽屉共用一份「统一目的地」列表，取消「更多」分组**（W5-R3，用户反馈 3 + 4） | 原 `chromeDestinations` 里的「更多」条目（`title_more` + `ic_menu`）是一个"桶"：点它弹抽屉，抽屉里再按 媒体 / 管理 / 用户 分区列条目——同一个目标在不同入口下行为不同（例如从侧轨点「设置」会因 `showNavigation=false` 把整条侧轨顶掉）。本波改成：**删除「更多」桶**，把媒体库 / 下载 / 设置 / 管理（控制台、媒体资料管理器）并入 `chromeDestinations` 一份列表；抽屉 = 同一份列表 + 服务器库列表（**无分组标题**）；`showNavigation` 改为「统一目的地集合」判定（首页 / 媒体库 / 下载 / 音乐 / 书架 / 设置 / 控制台）。结果：手机底部 Tab = 首页 / 媒体库 / 音乐 / 书架（4 个真实目标），抽屉仍可由顶栏菜单键与边缘手势打开；平板侧轨对**任何**条目都常驻，只换内容区。 |
| D19 | **媒体库去拥挤**（W5-R3，用户反馈 2） | ①**栅格**：总览页库卡从「固定 260dp 宽 + `GridCells.Adaptive(160/240/320)`」改为「整列宽 16:9 大卡 + 列宽 300 / 320 / 380 / 420dp（按窗口分级）」——旧组合在手机上会把卡片挤出屏幕、在平板上留不规则空档，是拥挤感的主因；库内容页海报最小列宽 160 → 176dp、音乐方形 184 → 200dp、横版 260 → 300dp，并让 `ItemCard` 宽度由栅格列决定（不再固定 150/184/260dp）。②**间距**：区块行距 16 → 24dp（`Space6`），页面底部留白 +32dp，列距保持 16 / 26dp 的既有栅格；首页区块间距 16 → 32dp（`Space8`）。③**层级**：媒体库总览与库内容页都加「大标题 + 计数副题」（`HeadlineLarge/Medium` + `BodyMedium/BodySmall`），标题从 22sp 升到 32/40sp；区块标题去掉横贯发丝线，改右侧「全部 ›」文字操作。④**信息**：海报墙只留片名 + 进度（去掉已看徽标），走廊卡标题下只留一行元信息与右下角片长。 |

## 4. 进度

- [x] ①-色彩：中性色亮 / 暗、三域媒体色 3×7、语义色、状态层、M3 `ColorScheme` 映射
- [x] ①-排版：v1.0 字阶 + 7 个扩展 token（DetailTitle / SectionTitle / WideCardTitle / NavLabel / MonoData / MonoDataSmall / Reader*）
- [x] ①-形状 / 动效 / 间距 / 无投影规则
- [x] Typography 归位 + 旧值桥接（`app:phone` 只留入口包装）
- [x] ②-按钮：Filled / Outlined / Text / Icon × 默认 / 悬停 / 按下 / 聚焦 / 禁用（媒体色融入本体，无独立色块）
- [x] ②-卡片：`CinefinCard` / `CinefinPosterCard`（2:3）/ `CinefinWideCard`（16:9 + 渐隐 + 3dp 进度）/ `CinefinListRow`（当前行渐变）
- [x] ②-导航：侧导航 164dp / 折叠轨 88dp / 底部 Tab 64dp（24×3dp 指示条）
- [x] ②-抽屉：320dp、header 96dp、条目 56dp / 圆角 12dp、分组标题
- [x] 组件预览（6 组 `@Preview`，覆盖三域 + 亮暗 + 五状态矩阵）
- [x] token 一致性单测（13 项）
- [x] 验收命令 + 真机走查
- [x] W3-R3 组件：分段控件 / 筛选 chip / 空状态（见下节）；徽标改中性（§8.9，无彩色圆点）
- [x] 音乐 / 阅读页面已按域接入 `ContentDomain`；W4-R3 起影视 / 设置 / 下载 / 欢迎页全部换新，`MaterialTheme.typography` 存量引用随桥接删除统一走 Prism 字阶
- [ ] W4-R3 组件（剩余）：对话框统一 `CinefinDialog` / Toast `CinefinToastHost` / 歌词组件收敛 / 播放器右侧面板（播放器线 W4 PLAYER-UI 负责）
- [ ] 后续：打包字体（授权 + 子集化）与 App 图标（三棱镜光栅多尺寸）

### W3-R3 本轮进度（R3-PAGES-A，2026-09-30）

- [x] core 补 §8.2 分段控件 / §8.3 筛选 chip / §8.10 空状态（+ 预览 4 组）；`cinefinClickable` 公开
- [x] 音乐页 Prism：头部（抽屉 / 返回 + 计数）、四维浏览改分段控件、专辑 / 艺术家 / 歌单 / 歌曲行改 `CinefinListRow`、底栏「正在播放」、队列面板（等高 72dp 行 + 当前行媒体色渐变 + 拖拽柄）、空 / 错误态改 `CinefinEmptyState`
- [x] 阅读页 Prism：顶栏（标题 + 模式按钮 + Aa）、加载 / 错误态、设置面板改用核心分段控件与 chip，主题缩略图行改横向滚动（手机可达）
- [x] 阅读器媒体色覆盖（D11）：纸色 / 护眼 = 纸页棕 `#A8843C`，深色 / OLED = 阅读域天青
- [x] 路由入口：`MusicModeRoute` 注册 + 抽屉「音乐」入口；书籍条目 → `ReaderActivity`；`ReaderActivity` 改回 `exported=false`
- [x] 构建 / 格式 / 单测门禁：`:app:phone:assembleDebug ktfmtCheck`、`:modes:book:testDebugUnitTest`、`:core:testLibreDebugUnitTest`、`:modes:music:testDebugUnitTest` 全绿
- [x] 真机走查：Pad 5 横屏双栏 + 手机形态（wm 覆盖 392dp 宽）各一次，色值采样核对（见 §5）
- [ ] 未决（负责人确认）：书籍入口替换 Web 控制台后的 PDF / CBZ 影响面（W4 补齐前会停在阅读器错误态）

### W4-R3 本轮进度（R3-PAGES-B，2026-10-01）

- [x] 导航骨架：手机底部 4 tab / 平板侧轨（88↔164）+ `CinefinModalDrawer` 抽屉换新（D15 / D16）
- [x] 首页：顶栏（44dp 无 ripple 图标键）、Hero（Filled 播放键 + 媒体色标签）、走廊横版卡（3dp 媒体色进度 + 1dp 描边）、海报墙（2:3 + 中性徽标 + 进度）；页面边距随窗口分级（20 / 24 / 32 / 48dp）、卡片间距 16 / 26dp
- [x] 媒体库 / 搜索：库卡片描边 + Prism 字阶、搜索空状态 `CinefinEmptyState`、结果网格 Prism 间距
- [x] 详情（电影 / 剧集 / 季 / 集）：`PlayButton` → `CinefinButton(Filled)`；行内操作键改 44dp 方圆形（选中 = `Media.Container` + `Media.Base`）；`ItemTopBar` 覆盖层键（黑 60% + 12dp 圆角 + 白图标）；`DownloaderCard` 改中性状态 + 媒体色进度（去掉跨域 `tertiary`）
- [x] 下载页：顶栏 + `CinefinEmptyState`
- [x] 设置页：顶栏 + 账号条（徽标改中性，去掉 `secondaryContainer` 跨域色）+ 分组间距
- [x] 欢迎 / 首连流程：Welcome（DisplayMedium + Filled / Text 按钮）、Servers / Users（Filled 形态主行动）、AddServer / Login（Prism 按钮 + 错误色）、ServerAddresses（FAB + 卡片行）
- [x] 排版桥接收敛：删除 `LegacyTypography` 桥接（`app:phone` Theme 包装不再传 typography），101 处 `MaterialTheme.typography` 引用统一走 `CinefinTypography`
- [x] 门禁：`:app:phone:assembleDebug ktfmtCheck` 通过
- [x] 双形态真机走查（K60）：手机形态全流程 + `wm` 宽屏覆盖验证侧轨折叠 / 展开（详见 §5 W4 验收）
- [ ] 未决：`MaterialTheme.spacings`（6 档桥接，44 个文件约 220 处）未收敛到 `CinefinSpacing`；`HomeHeader` / `HomeCarousel` / `HomeCarouselItem` 为旧首页死代码待清理；真平板（Pad 5 横屏）走查待负责人窗口

### P0 阻断修复（负责人 2026-09-30 插播，同分支）

### W5-R3 本轮进度（R3-UI-LUMEN，2026-10-01）

- [x] **流光手法原语**：`film/components/LumenSurface.kt`（`LumenCardFrame` 双层嵌套 + `Modifier.lumenEntrance` 入场 + `lumenBottomScrim` / `lumenVignette` 两个纯渐变）；**未新增任何 token / 色值 / 位图资源**
- [x] **首页（影视区）**：`HomeHero` 重写（全出血圆角头图 + 眉标圆点 + 大标题 + `2026 · TV-14 · 24 分钟` 短标签 + 单一 Filled 主行动 + 3dp 图上进度）；`SectionHeader` 改为「大标题 + 全部 ›」（去发丝线）；`LandscapeItemCard` / `PosterItemCard` 换双层嵌套外壳 + 强底部渐隐 + 右下角片长；区块行距 16 → 32dp
- [x] **视频详情页**：`MovieScreen` / `ShowScreen` 头图 288 → 300 / 400dp，标题升级 `HeadlineMedium`（手机）/ `DisplaySmall`（平板），眉标 + 元信息短标签 + 主行动全部压在图上；平板（≥840dp）新增左侧 216dp 海报 + 右侧 360dp 制作信息表（`LumenInfoTable`，44dp 行高 / key `OnSurfaceFaint` / value 右对齐）；季 / 接下来 / 演职人员统一走区块标题与 24dp 行距
- [x] **媒体库去拥挤**：总览页大标题 + 计数副题 + 整列宽 `LibraryEntryCard`（16:9 双层嵌套）；库内容页标题区（库名 + 项目数）+ 栅格放大一档 + 行距 24dp；`ItemCard` / `ProgressBar` 改为宽度自适应
- [x] **侧柜统一**（D18）：删除「更多」条目；抽屉 = 统一目的地列表 + 服务器库列表（无分组标题）；`showNavigation` 改为统一目的地集合；书架无库时退到媒体库总览（不再弹抽屉）
- [x] 门禁：`:app:phone:assembleDebug ktfmtCheck`、`:core:testLibreDebugUnitTest`、`:modes:film:testDebugUnitTest` 全绿
- [x] 组件预览：`HomeHero`（平板 / 手机）、`LandscapeItemCard`、`PosterItemCard`、`ItemCard`、`LibraryEntryCard` 均带 `@Preview`
- [ ] **待真机**（设备当前未连接，等负责人分配）：Pad 5 横屏（侧轨常驻 + 首页头图 21:9 + 详情三栏）与 K60 竖屏（底部 4 tab + 抽屉统一列表 + 媒体库单列）走查；走查前先按 `device-lock.md` 登记
- [ ] 未决：`MaterialTheme.spacings` 桥接（W4 遗留）本波未动；`HomeHeader` / `HomeCarousel` 旧死代码仍在

- [x] ① 书籍点不开：`NavigationRoot` 的 `item.kind == "Book"` 与 `FindroidFolder.kind`（`BaseItemKind` 枚举名 `"BOOK"`）大小写不匹配，书籍一直落到「按文件夹下钻」分支 → 改 `kind.equals("BOOK", ignoreCase = true)`（见踩坑 10），书籍改走 `ReaderActivity`（`exported=false`）
- [x] ② 音乐库空列表：`LibraryViewModel` 对 `CollectionType.Music` 只查 `MUSIC_ALBUM`，服务器无该实体 → `NavigationRoot` 增加 `libraryEntryRoute`：媒体库卡片 / 抽屉 / 搜索三处入口统一分流到 `MusicModeRoute`；`LibraryRoute`（Music）兜底重定向
- [x] 真机端到端复验（Pad 5，2026-09-30 23:10–23:25）：见 §5「P0 链路复验」

## 5. 验收

验收命令（2026-09-30 通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:compileLibreDebugKotlin ktfmtCheck --console=plain   # BUILD SUCCESSFUL
.\gradlew.bat :core:testLibreDebugUnitTest --console=plain                    # BUILD SUCCESSFUL（13 项）
.\gradlew.bat :app:phone:assembleLibreDebug --console=plain                   # BUILD SUCCESSFUL（真机包）
```

- [x] 主题可切换（亮 / 暗、三域 `domain` 参数）且全 App 生效（`MaterialTheme.colorScheme` 来自 Prism）
- [x] 新组件无硬编码颜色：组件目录 `Color(0x…)` 命中数为 0（仅 `CinefinTokens.kt` / 历史 `Color.kt` 持有原始色值）
- [x] 旧"墨 + 朱砂"配色在新组件中不复现（组件目录无 `D2553C` / `9C3623` / 墨色值）
- [x] 存量页面排版零回归：`MaterialTheme.typography` 仍取 `LegacyTypography` 原值（单测断言）
- [x] 真机走查：Xiaomi Pad 5（`21051182C` / Android 13）安装启动、首页渲染正常；Prism 石板底 + 琥珀 `Filled` 按钮 + `Media.Bright` 文字生效，朱砂红不再出现
- [x] `ktfmtCheck` 全模块通过

### W3 验收（2026-09-30，分支 `feature/r3-ui-pages-a`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck --console=plain   # BUILD SUCCESSFUL
.\gradlew.bat :modes:book:testDebugUnitTest --console=plain        # 9 项通过
.\gradlew.bat :core:testLibreDebugUnitTest --console=plain         # 13 项通过
.\gradlew.bat :modes:music:testDebugUnitTest --console=plain       # 4 项通过
```

- [x] 旧配色残留：`modes/music` / `modes/book` / 新组件目录搜索无 `ColorLight` / `ColorDark` / `D2553C` / `9C3623`（残留仅历史 `core/…/theme/Color.kt`、冻结的 `app/tv`、播放器线 `PlayerControlOverlay.kt` 与品牌 logo 资源）
- [x] 功能不回退：音乐四维浏览（94 专辑）、点歌入队、底栏播放 / 暂停 / 下一首、队列面板跳转 / 移除 / 拖拽排序可用；阅读页模式循环（滚动 → 分页 → 双栏）、主题切换、设置面板全字段可用
- [x] 平板双栏：Pad 5 横屏（2560×1600）EPUB 双栏渲染 + 顶栏「双栏」状态正确
- [x] 手机形态：`wm size 1080x1920` + `wm density 440`（≈392dp 宽）走查音乐页与阅读页 / 设置面板；走查后已 `wm size reset` / `wm density reset`
- [x] 色值采样（截图逐像素计数，容差 ±2）：
  - 音乐页（平板）：`#213C3E` 松石容器 1741 px + `#2B6D60` 描边 + `#69D5B5` 亮色文字 → 分段选中态生效；朱砂 `#D2553C` = 0
  - 阅读页深色：顶栏底 `#191F28`、模式按钮 `#415E8A` 描边 / `#8FBBFF` 文字；设置面板 `#28374D` 容器 4494 px
  - 阅读页纸色：顶栏底 `#FBF6EC`、纸页棕 `#A8843C` 83 px、天青命中 0（§8.14 例外生效）
- [x] 书籍入口路由：抽屉 → 书籍库 → 点书 → `topResumedActivity=ReaderActivity`（显式 Intent + `exported=false`）
- [ ] 未决（请负责人确认）：书籍入口改走本机阅读器后，非 EPUB（PDF / CBZ）点开只会停在「打不开这本书」错误态；W4 `R1-PDFCBZ` 补齐前如需兜底，可临时保留控制台入口或按格式分流

### P0 链路复验（2026-09-30 23:10–23:25，Pad 5 / `43af8627`）

按用户真实路径走查（`device-lock.md` 已登记 / 释放）：

| # | 链路 | 操作 | 结果 |
|---|------|------|------|
| 1 | 媒体库 → 书籍库 | 抽屉 →「媒体库」→ 卡片「书籍」 | 列出 5 本书（Anda's Game / attention_is_all_you_need / futuristic_tales / 雷普利全集 / 虚构推理）|
| 2 | 书籍库 → 阅读页 | 点《雷普利全集》 | `topResumedActivity=…book.presentation.reader.ReaderActivity`（未跳 Web 控制台）；顶栏「标题 + 滚动 + Aa」；EPUB 内容节点出现（`a-fc.jpg (654×1040)`），渲染正常 |
| 3 | 媒体库 → 音乐库 | 返回「媒体库」→ 卡片「音乐」 | 直接进音乐模式：`音乐 · 共 94 张专辑 · 专辑/艺术家/歌曲/歌单`（客户端分组生效）|
| 4 | 音乐播放 | 点专辑《A/Z|aLIEz》→ 点曲目 `aLIEz` | 详情「共 1 首曲目 / 01 aLIEz 4:07」；`dumpsys media_session`：`state=3 (PLAYING), position 递增`；底栏「队列 1/1 · 正在播放」 |
| 5 | 收尾 | 返回首页、媒体键暂停 | `state=2 (PAUSED)`；临时 dump 文件已清理 |

### W4 验收（2026-10-01，分支 `feature/r3-ui-pages-b`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck --console=plain   # BUILD SUCCESSFUL（268 tasks）
```

- [x] 旧配色清零：`app:phone` 全模块无 `ColorLight` / `ColorDark` / `D2553C` / `9C3623` 引用（存量仅播放器线 3 处色值常量，归 PLAYER-STAB 波次）
- [x] 媒体色融入控件：抽屉朱砂竖条删除；卡片进度条改 3dp `Media.Base`；详情操作键选中态 = `Media.Container` 底 + `Media.Base` 图标；徽标统一中性（无彩色圆点）；`DownloaderCard` 去掉跨域 `tertiary`
- [x] 双形态走查（K60 / `8e875894`，02:33–02:49）：
  - 手机形态（1080×2400 @420dpi ≈ 411dp）：首连全流程（欢迎 → 服务器 → 登录）→ 首页（底部 4 tab：首页 / 音乐 / 书架 / 更多）→ 媒体库 → 搜索（“9” 命中 9-nine + 13 集徽标）→ 电影详情（元信息 + Filled 播放键）→ 剧集详情 → 下载（空态）→ 设置（账号条 + 分组）→ 抽屉（品牌 header + 库列表 + 分组标题）
  - 宽屏覆盖（`wm size 2560x1600` + `density 320` ≈ 1280dp）：侧轨出现且 88dp 折叠（仅图标）↔ 164dp 展开（logo + 文字 + 收起）切换正常；底部 tab 隐藏；`wm` 已 reset
  - 像素采样（步长 6）：`#151A21` 31420 点、`#E8A15C` 786 点、旧朱砂 `#D2553C` 0 点
- [x] 功能不回退：抽屉导航（下载 / 设置 / 库列表）、搜索（输入即搜）、详情（元信息 / 播放 / 演职人员）、底部 tab 切换保持

### W5 验收（2026-10-01，分支 `feature/r3-ui-lumen`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck --console=plain        # BUILD SUCCESSFUL
.\gradlew.bat :core:testLibreDebugUnitTest :modes:film:testDebugUnitTest --console=plain   # BUILD SUCCESSFUL
```

- [x] 流光方案在首页与视频相关页面生效，手法与 `s1-direction-a/README.md` 一致（全出血头图 / 底部渐隐 / 内暗角 / 双层嵌套 / 5xx ms 入场）；音乐与阅读页面未改动（各自皮肤保持）
- [x] 媒体库信息密度下降：库卡最小列宽 160 → 300dp（手机单列）、库内容海报 160 → 176dp、行距 16 → 24dp、标题 22 → 32/40sp、区块发丝线删除（数值见 D19）
- [x] 侧柜无「更多」分组；`chromeDestinations` 一份列表同时驱动侧轨 / 底部 Tab / 抽屉，索引与动作同源（无分组聚合，踩坑 17 的隐患一并消失）
- [x] 入口行为一致：`showNavigation` = 统一目的地集合（首页 / 媒体库 / 下载 / 音乐 / 书架 / 设置 / 控制台）；手机选择后关闭抽屉、平板侧轨常驻；书架无库时退到媒体库总览而不是弹抽屉
- [x] 不新增 token / 色值 / 位图；业务层仍无 `Color(0x…)`（新组件全部引用 `CinefinTokens` / `CinefinColors` / `CinefinSpacing` / `CinefinMotion`）
- [x] 旧配色清零（沿用 W4 门禁）：新增文件无 `ColorLight` / `ColorDark` / `D2553C`
- [ ] **待真机走查**（设备未连接，负责人分配后补）：Pad 5 横屏 + K60 竖屏双形态；重点看侧轨在 设置 / 书架 / 控制台 三个页面是否常驻、首页头图在 21:9 与 16:9 的裁剪、详情页三栏在 1280dp 下的呼吸感

## 6. 踩坑库

1. **`Modifier.clickable(indication = null, onClick = …)` 不存在**：foundation 1.12 的两条重载里，带 `indication` 的那条必须显式传 `interactionSource`；封装 `Modifier.cinefinClickable` 统一处理（内部 `remember { MutableInteractionSource() }` + `indication = null`）。
2. **焦点环被 `clip` 裁掉**：`drawBehind` 画在布局外的环必须排在 `clip(shape)` **之前**（Modifier 链中越靠前越外层）。
3. **enum 构造参数不能用 `internal val`**：`CinefinButtonSize` 改为纯枚举 + 文件内 `private` 扩展属性提供尺寸 / 字阶。
4. **core 原本没有 Compose UI 依赖**：`implementation(libs.androidx.compose.foundation/material3/runtime/ui.tooling.preview)` + `debugImplementation(libs.androidx.compose.ui.tooling)`；别名在 `libs.versions.toml` 已存在，未新增版本条目。
5. **仓库无单测基建**：版本目录没有 junit 别名，`core` 用 `testImplementation("junit:junit:4.13.2")`（不改 `libs.versions.toml`）；测试源集放 `core/src/test/java`。
6. **ktfmt 重排文本**：`ktfmtFormat` 会合并 `.then(...)` 等结构，改文件前先读当前内容再 patch，避免上下文不匹配。
7. **`@Preview` 里的嵌套主题**：`CinefinTheme` 默认铺 `fillMaxSize` 背景，嵌套展示多域时必须传 `surfaceBackground = false`，否则内层主题会撑满高度。
8. **单测中的曲线断言**：`CubicBezierEasing` 不暴露控制点，改为断言端点值与"更快出"性质（Emphasized 在 0.25 处大于 Standard）。
9. **真机 `uiautomator dump` 在 Pad 5 上可能报 `theme_compatibility.xml` 失败**：改用 `screencap` + `pull` 走查；截图看完即删（不贴回对话）。
10. **`FindroidFolder.kind` 存的是枚举常量名，不是 JSON 名**：`type?.name` 得到 `"BOOK"`（`BaseItemKind.BOOK`），而老代码比较 `== "Book"` 永不命中，书籍一直被当普通文件夹下钻（Web 控制台分支实际是死代码）。路由判断改成 `kind.equals("BOOK", ignoreCase = true)`（本会话修正，属既有 bug）。
11. **手机形态走查可在真机用 `wm` 覆盖**：`adb shell wm size 1080x1920` + `wm density 440`（≈392dp 宽）；竖屏需先 `settings put system accelerometer_rotation 0` + `user_rotation 0`，走查后 `wm size/density reset` 并恢复 `accelerometer_rotation 1`（本会话已按此还原）。
12. **固定宽度滑块在 392dp 宽会被挤出屏幕**：设置面板「标签 48dp + 滑块 230dp + 数值文本」在手机上放不下，数值文本被裁。改为 `Modifier.weight(1f, fill = false).widthIn(max = 230.dp)`：平板保持 §8.14 的 230dp，手机自动收缩。
13. **`CinefinEmptyState` / `CinefinFilterChip` 在紧凑布局里也要给触控热区**：chip 视觉高 40/32dp，但外层用 `defaultMinSize(minHeight = 48.dp)` 外扩；分段控件的焦点环画在 `clip` 之后（内嵌 2dp），因为容器圆角会裁掉外环。
14. **MIUI 上 `uiautomator dump` 会打印 `theme_compatibility.xml` 堆栈**，但文件仍正常生成；用 `2>$null` 抑制并把 dump 落成 XML 再解析文本 / bounds，比人肉看截图快且稳。
15. **音乐库空列表的根因不在音乐模块**：`LibraryViewModel`（modes:film）对 `CollectionType.Music` 只查 `BaseItemKind.MUSIC_ALBUM`，而服务器无 MusicAlbum 实体 → 列表恒为空。修复放在 `NavigationRoot` 的路由层（音乐库一律进 `MusicModeRoute`，由音乐模块自己拉曲目并客户端分组），既最小化改动也避免两个模块双写分组逻辑。
16. **媒体库网格里的库卡片文本可能带 `bounds=[0,0][0,0]`**（未滚到可视区的行不会布局）；真机脚本要先把目标滚进视口再取 bounds 点击，否则点了 (0,0) 会静默无效。
17. **抽屉分组 + 拍平索引必须同源**：`groupBy` 会按「组首次出现顺序」重排条目——未分组项（首页 / 音乐 / 下载）若写在分组项之后，`groupBy` 后会被整体提前，而另一份 `map { it.second }` 的动作列表仍按原顺序拍平 → `onSelect(index)` 全部错位（真机表现为「点下载进了媒体库」）。修法：先 `groupBy`，再从同一 grouped 结构拍平 actions（本会话 D16）。
18. **MIUI（Redmi K60 / Android 15）会弹「剪贴板与常用语」系统弹窗**：走查中 `input text` 后偶发出现，遮挡 UI 并让 `uiautomator dump` 只剩弹窗节点；脚本要容忍「dump 首行不是目标页面」并检测 `text="不同意"` 后点掉再继续。
19. **`wm size` 覆盖后 `currentWindowAdaptiveInfo()` 需要一次前后台切换才刷新**：`wm size 2560x1600` / `wm size reset` 后若不重启或切后台，窗口分级可能停留在旧值（真机表现为底部 tab 与侧轨都不出现 / 切换滞后）；走查脚本在改 `wm` 后按 HOME → 重新 `am start` 再断言。
20. **「固定宽度卡片 + 自适应栅格」必然拥挤**：`ItemCard` 固定 260dp 宽、父级用 `GridCells.Adaptive(minSize = 160.dp)`，在 411dp 手机上两列装不下（520 > 371）会溢出裁切，在平板上又会留不规则空档。修法见 D19：卡片宽度一律由栅格列决定（`fillMaxWidth` + 比例），再由列宽反推"一屏几张"。
21. **`BoxScope.matchParentSize()` 是成员扩展，不能 import**：写成 `import androidx.compose.foundation.layout.matchParentSize` 会直接编译失败（`unresolved reference`），在 `BoxScope` 内容 lambda 里直接调即可。
22. **`Brush.verticalGradient` 的多档写法要用 `colorStops = arrayOf(...)`**：直接传 `List<Color>` 只有等距三档够用；要精确控制"平台期 + 渐隐段"（Lumen 底部渐隐 0.45 起、0.78 落）必须用 `Pair<Float, Color>` 的 vararg / `colorStops` 参数，且 stop 必须单调递增。
23. **`ktfmtCheck` 会在"改完文件"后立刻失败**：本仓的 `ktfmtCheck` 不参与增量缓存判定"只检查被改文件"，新增 / 编辑 Kotlin 后要先跑 `:app:phone:ktfmtFormat`（或全量 `ktfmtFormat`）再跑门禁；PowerShell 里用 `$LASTEXITCODE` 取 Gradle 退出码，别信 `Select-String` 管道的退出码（它是 0）。
24. **`stringResource` 只能出现在 `@Composable` 里**：条目格式化工具（`runtimeLabel` / `remainingMinutes`）保持纯函数，只有需要文案的（`metaLine` / `cardMetaLine`）才标 `@Composable`——否则 `@Preview` 与单元测试都编译不过。

## 7. 日志

- **2026-09-30 W1-R3（本会话）**：读齐 `PROJECT_PLAN` §1–5、`UI_DESIGN_SYSTEM` v1.0 全文、`s1-decision`、`REQUIREMENTS` §6/§10/§12、`ARCHITECTURE` §2.4、`SESSION_BRIEFS` W1-R3、`PARALLEL_PLAN` §1.3/W1、`ROLE_SKILLS` §5.3（在线校验 5 篇官方文档）；完成 token → Compose 主题映射、Typography 归位 + 桥接、4 类基础组件 + 预览 + 13 项单测；验收命令与真机走查通过。分支 `feature/r3-ui-tokens`。
- **2026-09-30 W3-R3（本会话）**：读齐 `PROJECT_PLAN` §1–5、`UI_PLAN`、`UI_DESIGN_SYSTEM` §2.3–2.6/§4/§8–§10、`READER_PLAN`（D7–D10 + §9 遗留）、`MUSIC_PLAN`（W2 交付 + 踩坑）、`SESSION_BRIEFS` W3-R3、`PARALLEL_PLAN` §1.3/W3、`ROLE_SKILLS` §5.3；完成 core 三件剩余组件 + 音乐 / 阅读页 Prism 接入 + `NavigationRoot` 路由注册（音乐 + 书籍→阅读器）与 `exported=false`；门禁与真机走查（含手机形态、纸色主题色值采样）通过；顺带修正 `kind == "Book"` 大小写 bug（见踩坑 10）。分支 `feature/r3-ui-pages-a`。
- **交接提示（下一会话）**：① 负责人确认书籍入口对 PDF / CBZ 的影响面（§5 未决项）；② 歌词面板由 R2-LYRICS 并入 `MusicModeScreen`（本会话已把浏览 / 底栏 / 队列拆成独立私有 Composable，冲突面小）；③ W4-R3 继续剩余组件与其余页面换新，届时删 `LegacyTypography` 桥接。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
- **2026-09-30 W3-R3 · P0 插播修复（同会话继续）**：负责人验收发现两条阻断链路。①书籍点不开 = `kind` 大小写不匹配（本会话早前已修，本次按真实路径复验通过）；②音乐库空列表 = `LibraryViewModel` 只查 `MUSIC_ALBUM`、服务器无该实体 → 在 `NavigationRoot` 加 `libraryEntryRoute`，音乐库三处入口（媒体库卡片 / 抽屉 / 搜索）+ `LibraryRoute` 兜底统一进 `MusicModeRoute`。真机复验两条链路全通过（§5「P0 链路复验」），未改 `modes:film` 任何文件。提交 `fix(ui): 修复书籍入口与音乐库空列表两条 P0 链路`。
- **2026-10-01 W4-R3（本会话）**：读齐 `PROJECT_PLAN` §1–5、`UI_DESIGN_SYSTEM` §2–§10 全文、`UI_PLAN`（W1/W3 + 16 条踩坑）、`REQUIREMENTS` §6/§10/§12、`SESSION_BRIEFS` W4-R3、`PARALLEL_PLAN` W4、`ROLE_SKILLS` §5.3，并通读 core 组件与音乐 / 阅读页参照。完成：①导航骨架（手机底部 4 tab / 平板侧轨 88↔164 / `CinefinModalDrawer` 抽屉，D15 / D16）；②首页 / 媒体库 / 搜索换新（卡片描边 + 3dp 媒体色进度 + 中性徽标 + 窗口分级边距）；③详情 / 下载 / 设置 / 欢迎与首连流程换新（`CinefinButton` 四态、操作键方圆形、`CinefinEmptyState`）；④删除 `LegacyTypography` 桥接统一 Prism 字阶；⑤门禁 `:app:phone:assembleDebug ktfmtCheck` 通过；⑥K60 双形态真机走查（手机全流程 + 宽屏侧轨折叠 / 展开 + 像素采样，见 §5 W4 验收）。修复抽屉索引错位 bug（踩坑 17）。分支 `feature/r3-ui-pages-b`。
- **交接提示（下一会话 / 负责人）**：① `MaterialTheme.spacings` 6 档桥接（44 文件约 220 处）与 `HomeHeader / HomeCarousel / HomeCarouselItem` 死代码待后续收敛；② 真平板（Pad 5 横屏）走查需负责人分配窗口（本波用 K60 + `wm` 覆盖验证，Pad 5 归 PLAYER-STAB）；③ 播放器覆盖层 / 面板的字阶随 `LegacyTypography` 删除变 Prism，播放器线 W4 PLAYER-UI 需在收口时复核面板排版；④ `settings/components/*` 内部卡片仍是 M3 组件（色板已 Prism），如需完全组件化可另开小波次。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
- **2026-10-01 W5-R3（本会话）**：读齐 `PROJECT_PLAN` §1–§5、`UI_PLAN`（W1/W3/W4 + 19 条踩坑）、`UI_DESIGN_SYSTEM` §2–§10、`s1-decision`、**`s1-direction-a/README.md` + `home / detail / phone / library` 四张稿**、`REQUIREMENTS` §6、`SESSION_BRIEFS` W4-R3、`ROLE_SKILLS` §5.3；核对了 `presentation/film/*`、`presentation/navigation/CinefinDrawer.kt`、`core/.../components/*`。完成四条反馈：①首页与视频详情改 S1 流光手法（D17，新增 `LumenSurface` / `DetailPoster` / `LumenInfoTable` / `ItemFormatting` 四个组件文件，重写 `HomeHero` / `SectionHeader` / `LandscapeItemCard` / `PosterItemCard` / `ItemCard` / `ProgressBar` / `HomeSection` / `HomeView` / `MediaScreen` / `LibraryScreen` / `MovieScreen` / `ShowScreen`）；②媒体库去拥挤（D19：栅格放大 + 行距 24dp + 大标题层级 + 去发丝线）；③侧柜取消「更多」分组（D18）；④所有入口行为一致（`showNavigation` 统一集合，书架无库退媒体库）。门禁 `:app:phone:assembleDebug ktfmtCheck` + `:core:testLibreDebugUnitTest` + `:modes:film:testDebugUnitTest` 全绿。分支 `feature/r3-ui-lumen`。
- **交接提示（下一会话 / 负责人）**：① **真机走查未做**（Pad 5 / K60 当前未连接）——重点断言「设置 / 书架 / 控制台 页面侧轨是否常驻」「手机抽屉选择后是否关闭」「首页头图 21:9 裁切」「详情三栏 1280dp 呼吸感」，走查前按 `device-lock.md` 登记；② 若负责人本意是"抽屉里的分组标题也一并取消"，本波已按此实现（抽屉无任何分组标题）——需要恢复分组时只改 `NavigationRoot` 里 `drawerGroups` 一处；③ 播放器线仍在改 `player/*` 与 `presentation/player/*`，本波未触碰（避免覆盖层冲突）；④ `MaterialTheme.spacings` 桥接与 `HomeHeader / HomeCarousel*` 死代码仍未收敛。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
