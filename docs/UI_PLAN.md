# Cinefin UI 重塑任务线（UI_PLAN）

> 任务线：UI 重塑（设计系统落地）｜波次：W53（视频入口：侧栏 / 底栏 / 设置 + 视频模式页）｜分支：`feature/w53-video-entry`｜最后更新：2026-10-03
> 权威设计依据：`docs/UI_DESIGN_SYSTEM.md`（S4 v1.0，Prism 棱镜）。
> 本文件是该任务线的**唯一权威文档**：需求、决策、进度、验收、踩坑与日志都写在这里，不新建零散 `.md`。

> 波次历史：W1-R3（R3-TOKENS，`feature/r3-ui-tokens`，已合并 master）= token 与四类基础组件； W3-R3（R3-PAGES-A，`feature/r3-ui-pages-a`）= 音乐 / 阅读页面接入 Prism + 路由入口注册； W4-R3（R3-PAGES-B）= 首页 / 媒体库 / 详情 / 搜索 / 下载 / 设置 / 抽屉 / 欢迎页全套换新 + 导航形态分级（手机底部 Tab / 平板侧轨）+ Prism 字阶收敛； W5-R3（R3-UI-LUMEN）= 首页与视频详情改用 S1 流光（A · Lumen）手法 + 媒体库去拥挤 + 侧柜统一列表（取消「更多」分组）与入口行为一致化； W5-R3F（R3-UI-HOTFIX，`feature/r3-ui-hotfix`）= 四项 UI 验收缺陷热修； W5-R3G（R3-NAVFIX，`feature/r3-ui-navfix`）= 修复踩坑 28：带参路由统一用 `NavDestination.hasRoute` 判定，平板侧轨在设置 / 书籍库 / 带参媒体库页常驻且选中态正确； W5-R3H（R3-UI-LUMEN-A，`feature/r3-ui-lumen-a`）= 流光改 S1「A · Lumen」配色（`ProvideLumen` 局部覆盖 + 月白主按钮）+ 亮图文字阴影 / 水平渐隐 + 手机 hero 行动区排版修复； W5-R3I（R3-CONSOLE-ENTRY，`feature/r3-console-entry`）= 恢复 D18 删除的「服务器控制台 / 媒体资料管理器」入口（管理员门控、统一列表、按 path 区分选中态）+ 控制台返回不再落到空白种子页； W6-R6N（导航 IA + 客户端设置，`feature/r6-nav-ia`）= 顶层 IA 重排 + 媒体库二级分组 + 设置新增「媒体库」「侧栏显示」分类； W6-WEB（`feature/web-console-lumen`）= Web 控制台皮肤 v3「A · Lumen」+ WebView 滚动闸门； W6-VIS（`feature/r6-visual-all`）= A 配色全站化 + 侧栏 / 抽屉 / 设置视觉重设计 + 骨架屏加载过渡； W7-R3（`feature/r7-nav-fix`，已合并 master `c545eb9`）= 手机恢复抽屉入口 + 首页顶栏 app 图标 / 二级库列表进抽屉 / 侧柜常驻 Lumen / 控制台悬浮「返回影阁」胶囊 / 控制台选中态修正； **W8-R3（`feature/r8-ui-unify`，本波）= 三页共用顶栏 `CinefinPageTopBar`（56dp + 状态栏内边距）+ 媒体库总览库卡改版（类型图标 / 强调色 / 项目数）+ 侧栏「媒体库」分组默认收起并移到音乐 / 书架之后**。

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
| D20 | **流光区域改用 S1「A · Lumen」配色（局部覆盖）**（W5-R3H，用户 2026-10-01 批准「B 骨架 + A 配色局部覆盖」） | 用户要求"流光效果同时改配色"。落地：新增 `core/.../theme/LumenColors.kt`（`LumenTokens` + `LumenColors` / `LocalLumenColors` + `ProvideLumen`；**全项目第二处允许 `Color(0x…)` 的文件**，A 稿色值唯一落点）= 曜石黑 `#08090C` 页底、石墨 `#111319` 卡片、雾灰 `#171A21` 悬浮 / 高亮底、月白 `#F2F5F9` 主文字与主按钮底、次级文字 `#98A2B3`、三级文字 `#6B7483`、极光青 `#5CE1D2` **唯一强调色**（眉标 / 进度 / 焦点 / 激活态）、辅光蓝 `#7CC4FF` 只用于头图进度渐变（<10%）。`ProvideLumen` 只包 **首页 + 电影 / 剧集 / 季 / 集详情**：同步覆盖 `LocalCinefinColors` / `LocalMediaColors` / `LocalLumenColors`，既有 Prism 组件零改动切到 A 色板；主行动改月白填充（`CinefinButton` 新增 `CinefinButtonTone.Inverse`，对应 A 稿 `.btn.primary`），卡片描边在 Lumen 区域改 A 稿 `--line`（白 8.5%），"全部 ›"改三级灰。**与 §2.6 媒体色纪律的关系**：Lumen 不是"第 4 个媒体色"，而是影视域在 Lumen 区域的**局部皮肤覆盖**——媒体库 / 搜索 / 设置 / 音乐 / 阅读都在 `ProvideLumen` 之外，保持 Prism 与各自皮肤；A 色板不得扩散到音乐 / 阅读。**文字可读性**（同波）：`TextStyle.lumenTextShadow(Title / Meta)`（标题黑 62% / offset (0,2dp) / 模糊 12dp；眉标与元信息黑 72% / (0,1dp) / 6dp）+ 首页 / 详情头图新增 A 稿左侧水平渐隐 `lumenSideScrim`（`rgba(6,7,10,·)` 家族）。**行动区排版**（同波）：手机 16:9 头图高度（≈208dp）小于内容高度导致行动区被压扁 → 头图改 `heightIn(min = 比例高度)`（内容可撑高）+ 行动区 `FlowRow`（放不下自动换行）+ 时间文本 `softWrap = false`。 |
| D21 | **恢复「服务器控制台 / 媒体资料管理器」入口：`chromeDestinations` 统一列表 + 管理员门控 + 按 path 区分选中态**（W5-R3I，负责人 2026-10-01 交办；补 D18 缺口） | D18 把入口从抽屉删掉后没有接回统一列表（W5-R3G 走查发现并留档），本波按**历史实现**（`20c4fe3^`）恢复：①两条入口直接进 `chromeDestinations`（不再有「更多」分组），`bottom = false` → 手机底部 Tab 仍是 4 个真实目标，只在平板侧轨 / 手机抽屉出现；②**只对管理员显示**——沿用 `DrawerViewModel.isAdministrator`（`repository.isCurrentUserAdministrator()` 读取失败回落 false），非管理员一律隐藏，服务端管理页面对普通账号保持不可达；③路由复用历史定义：服务器控制台 `ConsoleRoute()`（默认 `/dashboard`）、媒体资料管理器 `ConsoleRoute(path = "/metadata")`，路径常量收口到 `ConsolePathDashboard` / `ConsolePathMetadata`（默认值 / 条目 / 选中态同源）；④两类入口**共用同一个目的地 id**，选中态只能按 `ConsoleRoute.path` 判定（`consoleEntrySelected`），否则两条会同时高亮；⑤导航走独立 `navigateConsole`（`popUpTo(start)` **不带** `saveState`、不 `restoreState`），否则统一入口的 `saveState + restoreState` 会按目的地 id 把上一次保存的控制台条目恢复出来——点「媒体资料管理器」会回到 `/dashboard`（踩坑 30）；⑥同波修掉控制台返回路径：种子页（写登录态用）不再留在 WebView 历史里，控制台页按一次系统返回即离开控制台（踩坑 31）。门控 / 路径 / 选中态抽成纯函数 `consoleEntrySpecs` / `consoleEntrySelected` 并补单测（3 项）。 |
| D22 | **导航 IA 与客户端设置改造（W6-R6N，2026-10-01 管理员账号验收反馈）** | ①**手机去掉左侧抽屉**：Compact 形态 `openDrawer = null`（首页 / 媒体库 / 书架 / 下载顶栏不再有 hamburger）、`CinefinModalDrawer(gesturesEnabled = showNavigation && !compactNavigation)`（边缘滑出手势一并关闭），底部 4 Tab 与顺序不变（`bottomNavKeys`）。②**控制台只留自己的侧栏**：`showNavigation` 移除 `ConsoleRoute` → 控制台 / 元数据管理器页不渲染 app 侧轨 / 底栏，边缘抽屉手势同时失效（控制台内建侧栏不与 app 侧栏打架）。③**顶层 IA**：首页 / 媒体库（二级分组）/ 音乐 / 书架 /（管理员：服务器控制台 / 元数据管理器）/ 客户端设置；`navEntryKeys` 纯函数门控（音乐 / 书架在服务器确认没有对应库时隐藏，库列表未就绪时保持可见走页面空态；控制台两条只对管理员；客户端设置常驻，避免"把自己关掉"）。抽屉与侧轨共用 `visibleRailKeys` 过滤后的同一份列表。④**媒体库二级分组**：侧轨「媒体库」行可展开 / 折叠（chevron），子项 = 服务器实际返回的全部库（同名多库逐条列出，`libraryIconRes` 按类型给图标），折叠轨（88dp）只显示一级图标；子项 44dp 紧凑行。⑤**文案**：`CoreR.string.title_settings` =「客户端设置」（settings 模块自身的 `title_settings` 不动 → TV 与设置内部索引语义不变）。⑥**设置项**：新增「媒体库」分类（首页媒体库 / 音乐库 / 书架媒体库，运行时选项来自 `pref_ui_library_catalog` 缓存，新增 `PreferenceDynamicSelect` + `SettingsDynamicSelectCard`）与「侧栏显示」分类（7 个开关，即时生效、持久化）；离线模式移到设置最后一项，**不再重启 Activity**（`RestartActivity` 事件不再有人发送）。⑦**离线模式不重启的三处配套**：`MainViewModel` 监听偏好变化只刷新状态（`check(showLoading = false)`，不把 UI 移出组合）；`DrawerViewModel` 用 `Provider<JellyfinRepository>` 按需解析在线 / 离线仓库并在 `LaunchedEffect(isOfflineMode)` 重新加载导航数据（切回在线时侧轨库列表立刻回来）；内容页仍在下一次启动完全切换（本期边界）。⑧新增偏好键一律 `pref_ui_*` 前缀追加，不重排既有键；设置模块不能依赖 data 层（依赖方向相反），所以媒体库目录由 `DrawerViewModel` 写入 `LibraryCatalog` 编码缓存、设置页读取。 |
| D23 | **Web 控制台皮肤升级 v3「A · Lumen」+ WebView 滚动闸门**（W6-WEB，2026-10-01 用户反馈） | 用户反馈两件事：①控制台 / 媒体资料管理器的 Web UI 要与 App 内 Lumen 一致；②控制台页上下滑动不流畅。落地：①`docs/web-console-skin.css` 由 v2「墨+朱砂」重绘为 v3「A · Lumen」——页底曜石黑 `#08090C`、卡片石墨 `#111319`、悬浮 / 顶栏 / 侧栏 `#0B0D11`～`#171A21`、主文字月白 `#F2F5F9`、次级 `#98A2B3`、三级 `#6B7483`、**唯一强调色极光青 `#5CE1D2`**（链接 / 选中 / 焦点 / 开关 / 进度 / 标签页）、进度条青→蓝渐变 `#5CE1D2→#7CC4FF`（<10% 用量）、主按钮改 A 稿 `.btn.primary` 月白填充 + `#0A0C11` 深字、危险按钮暗红 `#3A1A16`/`#FFB4A8`、发丝线白 8.5% / 弱分隔线白 5%、错误色沿用设计系统 `#E87C6E` 低饱和冷调、侧栏选中项用 A 稿悬浮轨「渐变底 + 内高光」手法；**显式覆盖服务器主题灌进 `.navMenuOption` / `.cardText` 等元素的强调色**（踩坑 32）。②滚动闸门（v3 §15 + `WebConsoleScreen`）：CSS 全站禁用 `backdrop-filter`、`scroll-behavior: auto`、`overscroll-behavior: none/contain`；WebView 侧 `overScrollMode=NEVER`（去掉边缘拉伸回弹）、`isNestedScrollingEnabled=false`、`setOffscreenPreRaster(true)`、渲染进程优先级 `IMPORTANT`、背景色 / 种子页同步 `#08090C`。**同步流程**：本文件（docs）为唯一权威副本 → 同步复制 `app/phone/src/main/res/raw/web_console_skin.css`（App 注入）→ 服务器「控制台 → 显示 → 自定义 CSS」由用户手动粘贴上传，App / 仓库不代传、不写服务器。 |

| D24 | **Lumen 全域化：影视域全覆盖 + 跨域页面统一处理 + 侧柜皮肤跟随**（W6-VIS，2026-10-01 用户反馈「除音乐库与书架外全部页面改流光配色」） | ①覆盖范围从「首页 + 电影 / 剧集 / 季 / 集详情」扩到影视域**全部页面**：媒体库总览、库内容页（非书籍类型）、收藏、合集、下载、演职人员、客户端设置（含设置子页 / 关于）、首连向导（欢迎 / 服务器 / 添加服务器 / 服务器地址 / 用户 / 登录）；②**跨域页面的统一处理**（§2.6 关系）：媒体库总览会同时列出音乐库与书籍库卡片，处理方式是「页面本体走 Lumen + 库卡片只用类型图标与中性色」——卡片不引用任何一域的媒体色，点进音乐库 → 音乐模式（Prism + 松石），点进书籍库 → 库内容页仍为 Prism，书架 / 音乐 / 阅读皮肤一律不动；③**侧柜（侧轨 / 底栏 / 抽屉）跟随当前目的地皮肤**：新增 `lumenChrome` 判据（音乐 / 书架 / 书籍库 = Prism，其余 = Lumen），避免"页面换了皮、侧栏还是旧配色"；④`ProvideLumen` 除三个语义色 CompositionLocal 外**内嵌一层 `MaterialTheme(LumenMaterialColorScheme)`**——仓库里仍有大量 `MaterialTheme.colorScheme` 存量引用（Scaffold / TopAppBar / Switch / TextField / 对话框 / SearchBar），只换语义色会在设置与向导页留下石板蓝黑底与琥珀 / 松石强调色（踩坑 38）；`LumenMaterialColorScheme` 由 `cinefinColorScheme(Movie)` 覆写中性 / 强调槽位生成，error 等**语义色**保持 Prism 取值；⑤新增 `ProvideLumenColors`（只借色板、不加壳）供侧轨 / 底栏 / 抽屉这类自带底色的组件使用——`ProvideLumen` 的 `fillMaxSize` 盒子会在 Column / Row 里抢走剩余空间。 | 
| D25 | **侧栏 / 抽屉 / 底栏与客户端设置视觉重设计（A 稿手法）**（W6-VIS，用户反馈「图标、背景色、排版太单调」） | ①**侧轨**：底 = 石墨 `#111319`（比曜石黑页底亮一档，形成"幕布 + 面板"层次）+ 右缘 1dp 发丝线（白 8.5%）+ 顶缘 1px 内高光；条目选中 = 雾灰 `#171A21` 容器 + 1dp 细线 + 顶部内高光 + **月白**标签 + **极光青**图标（唯一强调色只落在"当前焦点"），未选中 = 次级灰 `#98A2B3`，悬停 = 白 7% 幽灵底、按下 = 白 12%；品牌印记改成"雾灰 + 发丝线"小方框；②**底栏**同语言（石墨底 + 极光青指示条与图标 + 月白 / 次级灰标签），并把安全区一起铺满底色（踩坑 39）；③**抽屉** = 石墨面板 + 品牌方框 + 服务器行幽灵胶囊；④**客户端设置**：分类收进石墨卡（复用 `LumenCardFrame`：1dp 渐变描边 + 顶部内高光 + 极轻外发光），行内图标磁贴 = 雾灰 + 发丝线 + 顶部内高光，标题月白 / 说明与当前值次级灰 / 箭头三级灰，开关轨道 = 当前强调色 + 配对深色拇指，顶栏下补一条发丝线；⑤**不新增位图、不新增色值**：图标沿用现有矢量资源只做重着色，A 色值仍只在 `LumenTokens`。 | 
| D26 | **加载过渡：骨架屏 + shimmer + 淡入**（W6-VIS，用户反馈「加载直接显示黑板」） | ①新增 `presentation/components/LumenSkeleton.kt`：`Modifier.lumenShimmer()`（底色 + 横向高光带；动画值只在 `drawBehind` 里读 → 只触发重绘、不重组）、`LumenSkeletonBlock` / `LumenSkeletonLine`、`LumenSkeletonOverlay`（淡入淡出外壳，兼避 ColumnScope 重载冲突，见踩坑 40）、页面级骨架（首页 / 媒体库 / 库内容 / 详情 / 设置）与 `ColdStartSplash`；②接入点：**冷启动**（登录态解析期显示曜石黑品牌页，就绪后主界面 220ms 淡入）、首页、媒体库总览、库内容页（分页首屏）、下载、5 个详情页、客户端设置；③纪律：全部动效只走 **opacity / graphicsLayer / 渐变平移**（§6.4），不改宽高、不做模糊；内容始终参与组合（只把 alpha 置 0），因此过渡不会重建滚动位置与分页状态。 | 

| D27 | **手机恢复抽屉入口 + 顶栏 app 图标**（W7-R3，2026-10-01 用户复测反馈 1） | W6-R6N（D22 ①）按当时反馈把 Compact 抽屉整体移除，复测时用户要求恢复：①`openDrawer` 不再按形态置空（`val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }`），首页 / 媒体库 / 书架 / 下载顶栏重新出现抽屉键；②`CinefinModalDrawer(gesturesEnabled = showNavigation)`——手机也能从边缘滑出（控制台类页面仍让位给 WebView）；③`LaunchedEffect(showNavigation)` 只在**非侧柜页面**回收抽屉，窗口从平板缩回手机时不再强制关闭；④**首页顶栏的入口换成品牌图标**（`ic_logo`，26dp，contentDescription = `nav_open_drawer`「打开侧栏」），既是 app 图标也是抽屉入口（用户原话"也不显示 app 图标"）；媒体库 / 书架 / 下载顶栏沿用汉堡键，底部 4 Tab 顺序与行为不变。 |
| D28 | **二级库列表进抽屉：默认展开、紧跟「媒体库」、排在音乐 / 书架之前**（W7-R3，用户复测反馈 2） | 旧实现把库列表平铺追加在抽屉末尾（音乐 / 书架之后），与平板侧轨的二级分组不一致。改法：`drawerEntries` 用 `railDestinations.flatMap`，在 `NavEntryKey.Media` 行之后立刻展开 `drawerData.libraries` 子项（离线模式 / 无库时只留一级入口）；子项用 `CinefinNavItem(nested = true)` → `CinefinDrawerItem` 缩进 16dp、行高 48dp（与侧轨 44dp 紧凑行同语言），选中态 = 当前库；`drawerSelectedIndex` 与动作列表仍同源（踩坑 17）。`CinefinNavItem` 新增 `nested` 参数（默认 false，API 兼容）。侧轨一行未动（`mediaGroupExpanded` 默认已为 true）。 |
| D29 | **侧柜皮肤常驻 A · Lumen**（W7-R3，用户复测反馈 3） | D24 ③ 让侧柜跟随当前目的地域（音乐 / 书架 / 书籍库回 Prism）；用户复测要求"音乐、书架的侧边菜单也要 Lumen 皮肤，但界面本身不变"。改法：`lumenChrome = true` 常量 → 侧轨 / 底栏 / 抽屉**任何页面**都套 `ProvideLumenColors`；`LumenPage` 的分流不变，因此音乐（Prism + 松石）、书架 / 阅读、书籍库的**页面内容**皮肤零改动。 |
| D30 | **控制台页悬浮「返回影阁」胶囊**（W7-R3，用户复测反馈 4） | 控制台页 `showNavigation = false`（D22 ②），进得去但难出来。选定方案（保留系统返回一次回主界面的既有行为，**不**恢复平板双侧栏并存）：`composable<ConsoleRoute>` 用 `Box` 把 `WebConsoleScreen` 与 `ConsoleBackToAppPill` 叠放——右下角（`align(BottomEnd)` + `navigationBarsPadding` + 16dp）A 稿小胶囊：石墨底 94% + 1dp 发丝线 + 顶缘内高光 + `ic_logo` 18dp + 月白「返回影阁」，触控高 ≥44dp；点击 = `navigateHome`（回主界面）。文案新增 `CoreR.string.console_back_to_app`（en/zh）。 |
| D31 | **控制台入口选中态：不在控制台就不选中**（W7-R3，用户复测反馈 5） | 根因：`consoleEntrySelected` 把"当前不在 `ConsoleRoute`"（`currentPath = null`）回退成 `/dashboard`，与 `entryPath = /dashboard` 相等 → 退出控制台后「服务器控制台」一直高亮。改为 `currentPath != null && currentPath == entryPath`；`ConsoleEntrySpecTest` 增加"null 不选中"断言（原断言按旧语义写反，同步更正）。 |

| D32 | **三页共用顶栏 `CinefinPageTopBar`（媒体库 / 音乐 / 书架）**（W8-R3，2026-10-01 用户反馈 1） | 用户复测：音乐顶栏按钮"位置太高、被系统状态栏挡住"、媒体库"汉堡键与标题分两块"、书架顶层复用库内容页（出现返回箭头 + 库名「书籍」）。落地：①**core 新增 `CinefinPageTopBar`**——56dp 内容高 + `statusBarsPadding()`（音乐旧 `MusicHeader` 72dp 且无 insets 是遮挡根因）、44dp 图标键（图标 24dp，与首页 `HomeTopBar` 同尺寸）、页边距随窗口分级 20 / 24 / 32 / 48dp（与 `rememberPageGutter()` 同阈值，core 侧用 `LocalConfiguration` 复刻以便 `modes:music` 复用）、主标题 `TitleLarge` + 计数副标题 `BodySmall`、Lumen 区域顶栏下缘补一条发丝线；②三页统一左侧入口 = `ic_menu` + `content-desc="打开侧栏"`（新增 core 文案 `nav_back`）、右侧页面动作（媒体库搜索 / 书籍库与书架排序）；③**书架顶层去返回箭头与库名**：`LibraryScreen(topLevel = true, onOpenDrawer = …)` → 顶栏 =「书架 / 共 N 本」（新增 `bookshelf_item_count`），从媒体库点库卡进入仍是「返回 + 库名 + 共 N 个项目 + 排序」；④音乐详情（专辑 / 艺术家 / 歌单）仍用返回键 + 详情标题；首页 `HomeTopBar` 保持 `ic_logo` 不动。 |
| D33 | **媒体库总览改版：库卡带类型图标 / 强调色 / 项目数（A · Lumen 内）**（W8-R3，2026-10-01 用户反馈 2） | 用户反馈"媒体库配色单调"。落地：①**库卡** = 类型图标磁贴（雾灰 `panelElevated` + 1dp 发丝线，图标取当前强调色——Lumen 区域 = 极光青，Prism = 当前域媒体色）+ 库名（月白）+ **项目数**（`共 N 个项目`）+ 三级灰右箭头；卡片面板 = Lumen 石墨 `panel`、底部渐隐用 `LumenTokens.Scrim`；②**项目数来自服务器**：`getLibraries()` 显式请求 `ItemFields.CHILD_COUNT`（`BaseItemDto.childCount` → 新增 `FindroidCollection.itemCount`；服务器没给就只显示库名，不占位）；③标题 + 计数并入共用顶栏同一排，去掉旧版"按钮独占一行 + 大标题块"的两段式；④色值仍只出自 `LumenColors`（未新增 token / 位图）。 |
| D34 | **侧栏「媒体库」二级分组回退：默认收起 + 排到音乐 / 书架之后**（W8-R3，2026-10-01 用户反馈 4；覆盖 D22 ③ / D28） | ①`navEntryKeys` 顺序 = **首页 → 音乐 → 书架 → 媒体库 → 下载 → [服务器控制台 / 媒体资料管理器] → 客户端设置**（手机底部 4 Tab 顺序不变）；②新增纯常量 `MEDIA_GROUP_DEFAULT_EXPANDED = false`（单测断言），抽屉与侧轨共用 `mediaGroupExpanded` —— 展开后才显示库子项；③**抽屉的「媒体库」行补上侧轨同款行尾箭头**（`CinefinNavItem.trailing` + `CinefinDrawerItem` 渲染，标签在有 trailing 时 `weight(1f)`），点父行 = 导航 + 展开，点箭头 = 仅展开 / 收起（离线模式没有库列表 → 不给箭头）；④覆盖 W7-R3 的"默认展开、排在音乐 / 书架之前"。 |
| D35 | **首页海报墙等宽修复：页边距交给网格 `contentPadding` + 列数按实际宽度计算**（W15-UI，2026-10-02 用户反馈「平板首页竖版海报大小不一，手机正常」） | 根因：海报墙把页边距做成**首末列 item 的 `Modifier.padding`**——`GridCells.Fixed` 本身每列等宽，但首 / 末列卡片各自再减一个页边距（平板 3 列：中间列 428px、首末列 374px，同排三张大小不一；手机 2 列时两列同为首 / 末、同样变窄，故"看起来正常"）。修法：①页边距改为 `LazyVerticalGrid.contentPadding`（start / end = gutter，首末列仍精确贴页边线，同排所有列完全等宽）；②`columns` 改为在 `BoxWithConstraints` 内按网格**实际可用宽度**计算（已排除侧轨；旧公式按窗口宽度算，侧轨占宽时会多算一列、把卡片压到 152dp 最小列宽以下）；③hero / 走廊 / 区块标题同步移除自带两端 padding —— 否则与网格 `contentPadding` 双重内缩（实测「全部」按钮右缘 1546→1492px）。 |

| D36 | **品牌波第一段：改名边界与红线处理**（W38，2026-10-03） | 用户可见名 = `app_name`（`values` = `Aurorama`、新增 `values-en`、`values-zh-rCN` = 极光幕、`values-zh-rTW` = 極光幕；debug / staging 加后缀）。继承自上游的 17 份 setup `welcome*` 与 33 份 core `privacy_policy_notice` 按词面替换品牌名（fi / cs / et / sl / tr / az 修正格位）。`applicationId` / 包名 / `Theme.Cinefin*` / `ShapeAppearance.Cinefin.*` / `CinefinPlaybackService` / `CinefinEpubNavigator` 等内部标识不动（W39 评审）；`JellyfinApi.CLIENT_NAME` 沿用 `"Cinefin"`——它进 HTTP 头与服务端设备档案，改动会与「避免双机重装重登」冲突，W39 一并决策（**W41 已落定：`CLIENT_NAME` = `"Aurorama"`，服务器新增设备记录、旧记录保留，见 D45**）。`console_back_to_app` 的调用点落在红线文件 `NavigationRoot.kt`，本波按字面量更名（未申报不改红线）。 |
| D37 | **自适应图标方向 1「极光帘幕」三套矢量落地**（W38，2026-10-03） | ①**自适应**：`drawable/ic_launcher_foreground.xml`（108dp：极光主线自左上流下 + 副丝带纵深 + 幕布地平线，极光青 `#5CE1D2` → 辅光蓝 `#7CC4FF` 线性渐变；关键形体全部落在 66dp 安全区内）+ `color/ic_launcher_background` = `#0B0C0E`；②**单色**：新增 `drawable/ic_launcher_monochrome.xml`（纯路径剪影），只在 `mipmap-anydpi-v33/ic_launcher.xml` 声明 `<monochrome>`，旧 API 保持「背景 + 前景」；③**深色**：`values-night/ic_launcher_background.xml` = `#05070B`。应用内印记 `ic_logo` 同构重绘（暗场圆角片底 + 丝带 + 地平线，最小 18dp 可读）。**不新增位图**；`core/src/main/ic_launcher-playstore.png`（商店用）仍是旧标记，随发布波重出。 |
| D38 | **字体落地：MiSans（中文 UI）+ Literata（编辑 / 阅读衬线）**（W38 立项，W40 更正 2026-10-03） | **W40 更正**：W38 的「MiSans 不可打包」结论有误。官方《MiSans 字体知识产权许可协议》（`https://hyperos.mi.com/font/zh/download/` 许可协议节，原文已核逐条）三条款为：①「您应在软件中特别注明使用了 MiSans 字体」；②「您不得对 MiSans 字体或其任何单独组件进行改编或二次开发」；③「您不得**单独**将 MiSans 字体或其组件对外租赁、再许可、给予、出借或进一步分发字体软件或其任何副本以及重新分发或售卖。**此限制不适用于您使用 MiSans 字体创作的任何其他作品。如您使用 MiSans 字体创作宣传素材、logo、应用 App 等，您有权分发或出售该作品**」→ 结论：**允许随 App 内嵌分发**（App 属「其他作品」，条款③只禁止单独分发字体软件本身），义务 = 软件内特别注明 + 字体文件不改（不子集化、不改名、不转格式）；子集化撞条款②。此前误读漏掉③的「单独」与「其他作品」例外。W38 按误读落地的 Noto Sans SC 子集已由 W40 移除（Literata OFL 1.1 保留）。落地实现见 D40。 |
| D39 | **媒体库总览两段式改版（W39，用户 2026-10-03 确认）** | ①**入口收口**：页内常显的 M3 `SearchBar` 大搜索框整体下线，只保留顶栏「搜索」图标入口（点开仍是原 `FilmSearchBar` + `SearchViewModel` 流程）；「收藏」从整行大卡改为顶栏星形 `ic_star` 图标键，与搜索并列。②**两段式**：①本地媒体库（标题行右侧「＋ 新建」`CinefinButton(Outlined, Small)`；无库时只留一行「还没有本地媒体库 · 点「＋ 新建」」，全被库级开关隐藏时提示点「眼睛」）→ ②服务器媒体库（`SectionHeader` + 现有 16:9 `LibraryEntryCard` 网格，位置上移到首屏）。③**文案去重**：新增纯函数 `localLibraryCardDetail`（`app/phone`）——单一类型 `书籍 · 1 个文件夹`（总数由行尾「N 项」承担，空库 `尚未扫描到媒体`）、多类型 `混合 · 2 个文件夹 · 视频 1 · 音乐 2 · 书籍 3`、隐藏库追加 `· 已隐藏`；页内「只建立索引，不复制、不移动源文件」长说明移入新建对话框一行小字。④**开关归位**：首页「本地媒体」开关从媒体库页移到客户端设置「媒体库」分类（`PreferenceSwitch` + `settings_local_library_visible` 两语言字符串；`pref_local_library_visible` 键不变），媒体库页不再出现该开关。⑤**一致性与 token**：`LocalLibrarySection` 不再自带左右内边距（在线由栅格 `contentPadding`、离线由调用方承担），在线 / 离线两处卡片边缘一致；卡片沿用 `CinefinCard`（16dp 圆角）、区块间距 24–32dp、行内边距 `Space3/Space4` 不变；只使用既有矢量与 `CinefinSpacing` / `CinefinType` 语义 token，无新增配色 / 字体 / 位图。 |
| D41 | **客户端设置收敛为 5 组 + 统一行样式 + 文案精简（W42，用户 2026-10-03 确认）** | ①**信息架构**：顶层 13 个分类 → **5 组**（账号与服务器 / 媒体库 / 播放与音乐 / 外观与界面 / 其他），条目功能不变、只做「分桶 + 合并」：服务器 + 网络；媒体库选择 + 本地媒体（从媒体库子页提升为顶层开关）+ 下载与缓存（两个子页合并）；播放器 + 音乐（音乐库选择从媒体库子页独立）+ 桌面歌词（顶层开关改子页、权限提示随之入子页）+ 恢复播放队列；语言 + 界面 + 外观（从界面子页拆出主题 / 动态取色）+ 侧栏显示 + 隐藏底栏；设备 + 离线模式 + 关于。原「用户」顶层行下线，由**账号卡**承接（点卡片 → 用户管理，卡片补「管理用户与登录」一行）。实现 = `SettingsGroupLayout.kt` 的 `buildTopLevelPreferenceGroups` 按 `nameStringResource` 分桶拼装（纯函数、5 项单测），未列出的条目兜底并入「其他」不丢。②**行样式**：24dp 语义图标（40dp 雾灰磁贴）+ 标题 + **单行**描述（≤16 字）+ 右侧固定 **44dp 控制位**（开关 / 当前值 / 箭头三选一，`SettingsRow` 统一）；行高基线 60dp（Pad 5 实测 60.9dp = 60 + 1dp 分隔线），长解释留在子页。③**文案**：侧栏显示「选择侧栏要展示的入口」、桌面歌词「悬浮显示当前句与下一句」、恢复播放队列「重启后恢复上次队列与进度」、离线模式「断网时只显示本机已下载内容」、缓存「管理图片与下载缓存」（en / zh-rCN 同步）。④**图标**：16 个语义矢量（10 重绘 + 6 新增：媒体库 / 文件夹 / 音乐 / 歌词 / 设备 / 侧栏）统一 24dp / 1.75dp 描边 / 圆角端点，纯白描边、不新增颜色。⑤**视觉**：分组标题 `LabelSmall` 次级灰 + 组间距 32dp + 行间发丝线缩进到文字列。 |
| D42 | **侧栏 / 底部导航改版：72·150dp + 82% 半透明 + 3dp 极光青左缘指示条（W42，用户 2026-10-03 确认）** | ①**尺寸**：折叠 72dp / 展开 150dp（原 88 / 164dp），条目 48dp（二级子项 44dp）、图标 24dp。②**透明度**：底色 = 石墨面板 @82%（`CinefinTokens.ChromeTranslucency`），**先铺页底再叠面板**——侧柜容器默认就是同色石墨，只加 alpha 会被"吃掉"（真机采样仍是不透明 `#111319`），补衬底后实测 `#0F1216`（82% #111319 over #08090C，见踩坑 57）；右缘 1dp 发丝线 + 顶缘内高光保留。手机底栏同款（K60 实测 `#0F1216`），手势条安全区同步铺满。③**选中态**：3dp 极光青左缘指示条（行内垂直居中 24dp、两端圆角；Pad 5 实测 `#5CE1D2`、6–7px）+ 半透明雾灰容器（`#171A21`）+ 1dp 细线；未选中图标提亮到白 62%（实测最亮 `#A4A5A6`），hover / pressed 再提亮一档（`LocalNavItemInteraction` 把条目交互状态下发到图标槽，抽屉同款）。④**分组**：侧栏导航区按「内容 / 管理」两组，组间 12dp 空隙 + 细分隔线（`railGroupBreaks` 纯函数 + 单测）；「客户端设置」固定在底部设置区，与导航区之间用发丝线分隔（实测 `#1B1E22`）。 |
| D43 | **「隐藏底栏」开关 + 「设备」行的解释（W42，用户 2026-10-03 确认）** | ①**偏好**：新增 `pref_hide_bottom_bar`（默认关；`AppPreferences.hideBottomBar`，红线已申报）；`DrawerViewModel` 监听该键（新增 `DrawerState.hideBottomBar`）→ `NavigationRoot` 紧凑形态不再渲染 `CinefinBottomTab`，各页自身按 `safeDrawing` 留底部空白，不出现异常留白 / 遮挡；抽屉（顶栏 logo / 汉堡）与「客户端设置」入口保持可达。②**形态**：紧凑形态可开关（K60 实测：开 → 底栏消失 + 抽屉可达各页 + `am force-stop` 重启保留 `pref_hide_bottom_bar=true`；关 → 底栏恢复 + 偏好回落 false）；平板形态开关置灰 + 一行说明「平板形态使用侧轨，本就没有底栏」（Pad 5 实测 `enabled=false` 且开关禁用）。③**设备行**：仓库里 `settings_category_device` / `device_name` / `updateDeviceName(name)` 均为上游 Findroid 遗留的**孤儿**（从未接线）；按「只重排合并、不扩大范围」，设备行接系统「应用信息」页（权限 / 通知 / 存储），描述「应用权限与通知」；后续若要做「设备名」输入可直接复用现成的 `updateDeviceName`。 |
| D44 | **搜索合并「媒体库与本地」（W43，用户 2026-10-03 确认范围）** | ①**数据**：`SearchViewModel` 并行查服务器（既有 `getSearchItems`）+ 本地（新增 `LocalLibraryRepository.search`：`local_media_items` 全量 + `LocalLibrarySearch` 纯函数内存过滤，匹配文件名 / 内嵌标签标题，大小写与首尾空白归一化、扩展名参与；5000 条实测 3.8 ms —— 不做 Room LIKE 索引 / Schema 迁移）；②**展示**：`SearchState` 拆 `serverItems` / `localItems`，按「服务器 / 本地」分区 + 各显示命中数、空分区不渲染；本地行 = 类型图标 + 名称 + 一行元数据（库名 · 文件夹 · 音乐时长 · 大小）+ `CinefinSourceBadge`「本地」徽标（复用 W37 组件，未接 `pref_music_source_badge` 开关）；服务器条目沿用 `ItemCard` 海报卡；③**打开**：本地视频 / 书籍 / 音乐复用 W37 链路（`PlayerActivity` / `openReader(localUri)` / `LocalMusicPlayer` 同文件夹队列 —— 抽出 `playLocalMusicFolder` 与本地库详情页共用），音乐起播后跳音乐 Tab；④**文案 / 空态**：占位符改「搜索媒体库与本地」（en 同步），无结果空态「未找到「xxx」」；⑤**红线**：仅动 `NavigationRoot.kt`（MediaRoute 传 3 个本地打开回调，已申报），其余红线未动。 |

| D45 | **W41 包名重命名（方案 A：只改 `applicationId`，用户 2026-10-03 拍板）** | ①**身份**：`app/phone` + `app/tv` 的 `applicationId` → `io.github.zhangwenkang.aurorama`（`.debug` / `.staging` 后缀保留），**Kotlin namespace / 包名 / 目录 / import / proguard keep 规则 / 清单内相对类名全部不动**；`fastlane/Appfile` 同步；②**引用同步**：`tools/` 7 个脚本的 `$Package` 默认值（`$Activity` 是类名不变；`Invoke-WaveRegression` 的 media_session 匹配串一并更新）；`docs` 的 adb / run-as 示例（PROJECT_PLAN / DEV_ENVIRONMENT / PLAYER_PLAN / READER_PLAN / TEST_PLAN）；**仓库无自建 FileProvider**，唯一 authority 占位符是 `AndroidManifest` 的 `${applicationId}.androidx-startup`（`androidx.startup`，构建期自动跟随 applicationId）；无深链 / 快捷方式 / 小组件 provider 引用；③**客户端标识**：`JellyfinApi.CLIENT_NAME` `"Cinefin"` → `"Aurorama"`（服务器会新增设备记录，旧记录保留，可接受）；④**关于页**：旧 Findroid `ic_banner` → `ic_logo`（应用内印记，120dp，不新增位图）；⑤**代价 / 回滚**：新 applicationId = 新 App 身份——双机全新安装 + 重登，旧包数据（登录态 / 下载 / **SAF 本地媒体库授权**）不迁移，SAF 需重新选文件夹（预期行为）；新包功能正常后卸载旧包 `com.zhangwenkang.cinefin.debug`；**回滚 = 装回旧 APK + 重新登录 / 重下（旧包卸载后其私有目录数据不可恢复）**。 |

| D46 | **开关与滑杆收口为 core 共享组件（W44，用户 2026-10-03 确认：音效开关关闭态不可见 / M3 竖条拇指要改）** | ①**`CinefinSwitch`**（`core/.../components/CinefinSwitch.kt`）：全 App 的 `Switch` 唯一入口，配色由 `cinefinSwitchColors()` 从现有语义 token 派生——关闭态拇指 `onSurfaceVariant`（M3 默认取 `outline`，暗底上几乎不可见）、轨道 `surfaceContainerHigh`、描边 `onSurfaceFaint`；开启态 = 当前域 `Media.Base` + `OnBase`；禁用态用 `onSurfaceFaint` / `disabledAlpha` 合成，仍可辨。**Prism 与 Lumen 由 `LocalCinefinColors` / `LocalMediaColors` 自动切换**（`ProvideLumenColors` 已同步覆盖这两个 CompositionLocal），不新增色值；替换面 = 设置卡片 / 播放器面板 / 下载 / 本地库 / 离线 / 音效面板共 8 处，阅读器纸色面板同一组件显式传自定义 `colors`。②**`CinefinSlider`**（同目录 `CinefinSlider.kt`）：自绘 4dp 胶囊轨道 + 18dp 圆点拇指 + 极轻同色柔光，与播放页 W23 D38 的 `MusicProgressBar` 同一视觉语言；支持 `steps` 档位吸附、禁用态 40% 透明、RTL、36dp 触控带（自消费手势，纵向滚动容器不抢）；默认配色 = `progress-track` 未填充轨 + 当前域 `Media.Base` 已填充轨 + `onSurface` 拇指；数值 / 触摸换算抽 `cinefinSliderFraction` / `cinefinSliderValueAt` 纯函数（core 7 项单测）。替换面 = EQ 五段 + ReplayGain 覆盖两支 + 阅读器数值滑杆，仓库内 `androidx.compose.material3.Slider` 使用点清零。③**不做**：不新增配色 / 字体 / 位图，不改 `Slider` 的键盘步进语义（只提供 `progressBarRangeInfo` + `setProgress`），播放页自绘进度条保持不动（仅核对同语言） | 用户实测的两条反馈（"音效开关关闭态小圆点不明显"、"滑杆竖条拇指=控制条上的竖线"）本质是**同一类问题在多个页面的副本**：只改一处会留下新旧混排。把配色 / 尺寸 / 交互收进 core 后，皮肤（Prism / Lumen / 阅读器纸色）、禁用态、暗色对比三件事都只有一个落点；真机像素采样（`#A7B0BD` / `#222A36` / `#6E7887`，对比度 1.37:1 → 6.6:1）与 8+ 项纯函数单测构成可复核证据 |
| D48 | **侧栏细节：74% 半透明 + 展开 168dp + 长文案精简（W46，用户 2026-10-03 逐项确认）** | ①**透明度**：新增 `CinefinTokens.RailTranslucency = 0.74f`（只作用于**平板侧轨 + 手机抽屉**；底栏保持 `ChromeTranslucency = 0.82f` 不变），仍沿用「先铺页底衬底再叠面板」的两层画法（踩坑 57 的结论）；**选中态容器同 alpha**（`panelElevated.copy(alpha = 0.74f)`，否则选中行是一块实心板、把透出吃掉）。②**宽度**：展开 150 → **168dp**（折叠 72dp 不变）。按 16sp `NavLabel` 估算，168dp 下条目文字可用 ≈84dp，「客户端设置」（5 字 ≈80.5dp）首次完整显示；3dp 极光青指示条与分组分隔线不回归。③**文案**：`title_console` 服务器控制台 → **控制台**、`title_metadata_manager` 媒体资料管理器 → **资料管理**（默认 / zh-rCN / zh-rTW 同步；只改侧栏 / 抽屉显示名，路由与设置项标题不动）。④**抽屉**：`ModalDrawerSheet` 的 `drawerContainerColor` 必须置 `Color.Transparent`——Surface 自己的底色会盖住 modifier 里的半透明层（踩坑 68）。 |
| D49 | **导航形态：手机四页顶栏统一 app 图标 + 平板取消抽屉（W46，用户 2026-10-03 确认）** | ①**手机（Compact）**：首页 / 音乐 / 书架 / 媒体库四个一级页顶栏入口统一为 `ic_logo`（24dp、`content-desc="打开侧栏"`、点击 = 开抽屉）——`CinefinPageTopBar` 的 `onOpenDrawer` 分支由 `ic_menu` 换成 `ic_logo` + `Color.Unspecified`（品牌渐变不能被次级灰染掉），首页 logo 26 → 24dp；二级页（下载 / 客户端设置 / 详情）保持现状。②**平板（非 Compact）**：`openDrawer = null`（各页顶栏不再出现抽屉键）、`gesturesEnabled = showNavigation && compactNavigation`（左缘右滑不再拉出抽屉）、窗口从手机切回平板时主动 `close()`（踩坑 69）；收起 / 展开只由侧轨自身按钮完成。③**不动**：`NavEntryKey` / `navEntryKeys` / `visibleRailKeys` / `railGroupBreaks` / 底部 Tab 与抽屉条目语义零改动。 |
| D50 | **设置「音乐」子页下线：音乐库选择归位「媒体库」（W46，用户 2026-10-03 确认）** | ①`PreferenceDynamicSelect(settings_music_library → uiMusicLibraryId)` 从「播放与音乐 → 音乐」子页移入「媒体库」子页，与「首页媒体库 / 书架媒体库」并列；②删除 `PreferenceCategory(settings_category_music)` 入口与其空组（`settings_category_music` / `settings_music_summary` 两条资源同步删除）；③`SETTINGS_GROUP_LAYOUT` 的「播放与音乐」组只留播放器 / 桌面歌词 / 恢复播放队列，`SettingsGroupLayoutTest` 同步（分桶顺序与组内顺序断言更新）。 |

| D51 | **`CinefinSlider` 键盘 / 无障碍步进 + 光晕参数同源 + 阅读器滑杆零宽回归修复（W49，2026-10-03）** | ①**键盘步进（对齐 M3 `Slider` 的 `slideOnKeyEvents`）**：`focusable` + `onKeyEvent`，方向键按步长调整（`delta = 区间长度 / (steps + 1)`；无档位 = 区间 1%）、Home / End 到端点、PageUp / PageDown 跳 `clamp((steps + 1) / 10, 1, 10)` 个步长，RTL 下左右方向对调，KeyUp 回调 `onValueChangeFinished`；数值换算抽 `cinefinSliderStepSize` / `cinefinSliderSnappedValue` / `cinefinSliderSteppedValue` / `cinefinSliderPagedValue` 纯函数（core 单测 +4）。②**无障碍**：`setProgress` 目标按档位就近吸附（M3 `sliderSemantics` 同口径），`progressBarRangeInfo` 保留。③**视觉参数同源**：新增 core `CinefinProgressVisuals`（36dp 触控带 / 4dp 轨 / 9dp 拇指 / 光晕 2.6×、0.45 alpha / 禁用 0.4），`CinefinSlider` 与播放页 `MusicProgressBar` 共用，删除两处各自硬编码。④**真机拦下的 W44 回归**：M3 `Slider` 会填满 max 约束，自绘 `Canvas`（`Spacer`）只取 **min** 约束 → 阅读器 `SliderRow` 的 `weight(1f, fill = false)` 让三条滑杆宽度为 0（不可见、不可触摸、a11y 树里整节点消失）；修法 = 组件内 `fillMaxWidth()`（M3 同口径），上限仍由调用方 `widthIn(max = 230.dp)` 控制（Pad 230dp / K60 209dp）。 | 用户口径「滑杆要像 M3 一样能用」+ W44 遗留「自绘滑杆无完整 M3 键盘步进 / 光晕参数与播放页各写一份」；键盘步进的 delta / 吸附口径逐条对照 M3 `Slider.kt`（本机 1.4.0 源码）而非凭感觉。真机（Pad 5 主 + K60 抽验）：阅读器滑杆 `android.widget.SeekBar` 节点回归 a11y 树（230dp / 209dp）、tap 100%→160%、TAB + 方向键 200%→201%（一档 1.8%）；EQ 每按一次 +0.2 dB（1% 区间）、ReplayGain 覆盖每按一次 **+0.5 dB**（steps=47），触摸 / 落盘 / 清除均正常 |
| D52 | **下载页改版：大海报 + 大条目 + 容器聚合（W52，用户 2026-10-03 确认）** | ①**尺寸**：手机节目 / 季 / 电影 / 书籍海报 **96×144dp**（2:3 贴满行高，卡片行高 ≈148dp）、剧集缩略图 **112×63dp**（16:9）、专辑方形 96×96dp；平板海报 104×156dp。②**平板两列**：`DownloadGridGrouping` 只把相邻且已折叠的顶层容器两两成行，展开容器连同子项整宽（层级从属关系优先于网格）。③**容器聚合**：聚合进度条（按字节，未知回落计数）+「已下载 x/y」+「已用 / 总大小」+ 速度（= 子任务速度之和）+ 剩余时间（= 剩余 / 速度，缺数据「—」），口径落在 `DownloadAggregateRules` / `DownloadFormatRules` 纯函数 + 单测。④**旧组件重绘（范围 = 下载页）**：M3 `AlertDialog` → Lumen 确认对话框、文本状态行 → 状态徽标（12dp 前置图标）、加载圈 → `LumenSkeleton` 骨架、空态补图标；`LazyColumn` 逐行 `key` + `contentType`（Compose 性能规范）。⑤**不动**：`NavigationRoot.kt` / `AppPreferences.kt` / 详情页动作排（W51）/ `player:*`；多任务通知点击改用启动 Intent 附加标记（`EXTRA_OPEN_DOWNLOADS`）由 `MainActivity` 导航到下载页，不新增通知按钮。 |

| D52 | **视频顶层入口：与音乐 / 书架同级（W53，用户 2026-10-03 确认）** | ①**门控**：`NavEntryKey` 新增 `Video`，`navEntryKeys` 与音乐 / 书架同规则——服务器确认没有 `movies` / `tvshows` 库时隐藏入口，库列表未就绪（冷启动 / 拉取失败）时保持可见、由页面显示空态（不把网络故障翻译成"没有视频库"）；②**顺序**：首页 → **视频** → 音乐 → 书架 → 媒体库（二级分组）→ 下载 → [控制台 / 资料管理] → 客户端设置（`railGroupOf(Video) = Content`，`railGroupBreaks` 随 `navEntryKeys` 自动重算）；③**手机底栏改 首页 / 视频 / 音乐 / 书架**：「媒体库」移出底栏（仍保留在侧栏 / 抽屉，二级分组语义不变）；底栏顺序仍由 `bottomNavKeys` 单一来源导出，与侧轨排序解耦；④**设置**：客户端设置 →「侧栏显示」新增「视频」开关（`pref_ui_sidebar_show_video`，默认开；与既有开关同一持久化 / 监听机制，只作用于侧轨与抽屉、不影响底栏）；⑤图标复用 `ic_film`、文案新增 `CoreR.string.title_video`（en / zh-rCN / zh-rTW），**不新增配色 / 字体 / 位图**；⑥单测：`navEntryKeys` 门控与顺序、`visibleRailKeys` 过滤、`bottomNavKeys` 顺序共 3 项新增 + 既有断言同步。 | 用户 2026-10-03 确认的四条口径（与首页 / 音乐 / 书架同级、两显示方式、底栏改版、侧栏开关）逐条落地；「客户端设置」常驻规则不变（它自己的开关不能被关掉）。 |

| D53 | **视频模式页两种显示方式：库卡列表 / 聚合列表（W53，用户 2026-10-03 确认）** | ①**数据口径**：页面数据 = 服务器上 `movies` + `tvshows` 类型的全部库（`pickVideoLibraries` 纯函数 + 单测），顺序与服务器返回一致；②**库卡列表（默认）**：`LibraryEntryCard` 16:9 大卡网格（封面 / 库名 / 项目数），列宽沿用媒体库总览四档（300 / 320 / 380 / 420dp），点卡进库内容页（复用 `navigateToItem` → `LibraryRoute`）；③**聚合列表**：全部视频库的条目（电影 + 剧集）合并成一个懒加载网格（`ItemCard` 竖版海报 + `GridCellsAdaptiveWithMinColumns(176, 2)`），Paging 3 分页——Jellyfin 没有"一次查询多个媒体库"的接口，`VideoAggregatePagingSource` 按**库顺序**拼接、每个库内按「最近添加」（`DateCreated` 倒序）取，游标推进抽 `advanceVideoAggregateCursor` / `isVideoAggregateFinished` 纯函数 + 单测，空库 / 已取完的库自动跳过且不会让列表提前结束（一次 `load` 把 `loadSize` 填满）；④**设置项**：客户端设置 →「媒体库」子页新增「视频显示方式：库卡列表 / 聚合列表」（静态 `PreferenceSelect`，键 `pref_ui_video_display_mode`，默认 `cards`；取值 `cards` / `aggregated` 落盘），VideoViewModel 挂 SharedPreferences 监听，切换后回到页面即时生效；⑤**空态 / 骨架**：无视频库或聚合列表为空 → `CinefinEmptyState`（新增 `video_empty_title/message`），加载 → 库卡模式 `MediaLibrarySkeleton`、聚合模式 `LibraryGridSkeleton`（均走 `LumenSkeletonOverlay`）；⑥**顶栏**：复用 `CinefinPageTopBar`（手机 = app 图标入口 / 平板 = 无抽屉键，随 W46 形态规则），页面本体走 Lumen（影视域，D24）；⑦**仓库解析**：`VideoViewModel` 经 `Provider<JellyfinRepository>` 每次 `load()` 按当前偏好解析 在线 / 离线（踩坑 33 同类）——离线模式 `getLibraries()` 空列表给空态，不发网络请求（真机拦下后修复）。 | 已知边界：「最近添加」是**库内**排序（跨库全局排序需要服务器端祖先过滤，Jellyfin 无该接口，本波不做）；混合库（如 `mixed`）中的电影 / 剧集**不计入**聚合列表——口径严格限定在 movies / tvshows 库，与入口门控同源。 |

| D54 | **实机 Bug A/B 修复：顶层入口回落根页 + 同目的地不同参数不走 saveState / restoreState（W53 复验，用户 2026-10-03 实机反馈）** | ①**Bug A（手机底栏「视频」切不回来）**：`navigateTopLevel` 的 `popUpTo(start){saveState}` + `restoreState` 会把「Tab 根 + 子页面」整栈恢复（如 视频 → 某个库内容页），点底栏「视频」停在库内容页；修法 = 导航后 `popBackStack(route, inclusive = false)` 统一弹回入口根页（已是根页为 no-op，根页滚动 / 状态仍由 saveState 保留）。②**Bug B2（侧栏选「书籍3」页里仍是「书籍」）**：库入口是「同一目的地 + 不同参数」，`restoreState` 按目的地 id 恢复旧条目、把新参数顶掉（踩坑 30 同类）；修法 = `openLibrary` 改 `popUpTo(start)` 不回存 / 不恢复 + `launchSingleTop`，按点击的库新建条目。③**Bug B1（侧栏选「音乐测试」页里仍是「音乐」）**：旧 `libraryEntryRoute` 把所有 Music 类型都映射到 `MusicModeRoute`（忽略 libraryId），音乐模式只读「客户端设置 → 音乐库」；修法 = 新增独立目的地 `MusicLibraryRoute(libraryId, libraryName)`（音乐 Tab 仍走 `MusicModeRoute`，两者分开 → 不会被 `restoreState` 用旧参数顶掉），`MusicModeViewModel` 从 SavedStateHandle 取路由库（路由 > 偏好 > 自动），顶栏显示库名；解析抽 `resolveMusicLibraryId` 纯函数 + 单测。④**库子项可区分**：侧栏 / 抽屉库子项右侧显示项目数（服务器 `ChildCount`，无值不占位）——抽屉完整显示，侧轨 168dp 下最长 4 字库名会截断（呈现取舍待用户确认）。⑤路由决策抽 `libraryEntryRoute` 纯函数（app:phone 单测 4 项）+ 音乐库解析单测 3 项。 |
| D55 | **视频海报状态徽标：容器显示未看数、电影 / 单集已看打勾（W56，用户 2026-10-03 确认，官方口径）** | ①**规则纯函数**（`FindroidItem.posterStatusBadge()`，`app/phone` `presentation/film/components`）：Series / Season / 文件夹 → `UserData.UnplayedItemCount > 0` 显示未看条目数，`>99` 收敛 `99+`（`unplayedItemCountText`）；Movie / Episode → 服务器只给 `UserData.Played`：已看 → `PlayedBadge` 打勾、未看 → 不加角标；合集等本波未纳入类型 → 无；②**接入面** = `PosterItemCard`（首页海报墙，补「已看打勾」）/ `ItemCard`（库网格 / 搜索 / 视频聚合 / 季列表 / 演职人员）/ `LandscapeItemCard`（首页走廊与「接下来」）/ `EpisodeCard`（单集列表）统一走 `ItemStatusBadge(item)`（下载徽标仍独立、同排）；③**库卡改正**：`LibraryEntryCard` 的角标位删除——库视图（CollectionFolder）实测不返回 `UnplayedItemCount`、`FindroidCollection` 恒为 null，原先是死代码；库卡信息仍是「共 N 个项目」（`ChildCount`）；④**数据核验（只读接口探针，服务器 10.11.8）**：`/Items`（网格 / 搜索）、`/Shows/{id}/Seasons`、`/Items/Latest`、`/Suggestions` 对 Series / Season 均返回未看数，显式 `enableUserData=true` 与默认完全一致（服务端默认已含用户数据）→ **查询不改**；Movie / Episode / `/Views` 恒空，`/Shows/NextUp`、`/Items/Resume` 只有 `played`；⑤**不改**：离线 / 本地库路径（离线映射 `unplayedItemCount = null`，无角标是既有语义）；不新增配色 / 字体 / 位图。 | 视觉沿用封面右上角小胶囊（与下载徽标同排，黑 62% + 白 12% 描边，Prism / Lumen 通用）；`99+` 覆盖「整剧数百集未看」大库（真机样本 银魂 = 370 → `99+`）。 |
| D56 | **库内容页头部共享组件（W54-B，用户 2026-10-03 确认）** | ①**落点与共享**：库内容页（视频库 / 书籍库 / 书架三处入口）继续共用一份 `LibraryScreen`；头部 UI 新增 `app/phone` `presentation/film/components/LibraryContentHeader.kt`（tabs / 工具行 / 列表行 / 分类 tile / 筛选面板）+ `SortByPanel.kt`（排序面板），**口径纯函数**放 `modes:film` `presentation/library/LibraryHeaderRules.kt`（tab 出现规则 / 工具行动作 / 筛选映射 / 库类型→查询类型 / 排序映射 / 计数文案，全部可单测）。②**顶部 tabs（Jellyfin 官方客户端 IA）**：库名（第一项，标签 = 真实库名）/ 建议 / 即将播出 / 类型 / 制片发行商 / 剧集，按库类型出现——电影 = 库名 + 建议 + 类型 + 制片发行商；剧集 = 六项全给；图书 / 家庭视频 / 音乐 / 合集 = 库名 + 建议 + 类型；混合 / 文件夹 = 库名 + 建议 + 类型 + 制片发行商；播放列表 = 只有库名。③**取数口径**：建议 = 库内 `SortBy=Random` 抽样 24 条（SDK 1.8.12 与官方 OpenAPI stable 的 `/Items/Suggestions` 都**没有** `parentId`，做不了官方库内建议）；即将播出 = `/Shows/Upcoming?parentId=`（SDK `getUpcomingEpisodes`）；类型 / 制片发行商 = `/Genres` / `/Studios?parentId=`，点分类 → 回库名 tab + 库内过滤 chip（`/Items` 的 `genres` / `studios` 按**名称**过滤）；剧集 = `/Items?includeItemTypes=Episode&recursive=true&limit=300`。④**工具行**：条目计数「1-94 / 94」（`libraryCountText`；总数用 `limit=1` + `TotalRecordCount` 单发请求取，未知时退化为已加载条数、空库不出文案）+ 网格 / 列表切换 + 排序（原 `SortByDialog` → 工具行图标 + 底部 `SortByPanel`，排序项 / 方向口径不变）+ 筛选 funnel（常用筛选集合 = 未看 / 已看 / 收藏；书籍库文案改未读 / 已读；播放列表库不提供）。条目型 tab（建议 / 即将播出 / 剧集）保留计数与视图切换，分类 tab 只留计数。⑤**下拉刷新**：整块内容 `PullToRefreshBox`——分页列表走 `LazyPagingItems.refresh()`（真实重发请求），计数与当前 tab 由 ViewModel 重取（不重建 Pager，列表不闪空）。⑥**视图切换 = 进程内状态**：`LibraryViewMode` 不写 `AppPreferences`（红线文件，未申报）。⑦**新增 3 枚描边矢量图标**（core `ic_view_grid` / `ic_view_list` / `ic_filter`，24dp 网格 / 1.75dp 描边 / 无填充），**无新增配色 / 字体 / 位图**。⑧**任务 E 已知缺陷**：家庭视频库缩略图空白，根因与修法见踩坑 74（`ItemPoster` 横版分支 `backdrop ?: primary`）。 | 显示方式是否持久化留给 W54-D 收口（走 `AppPreferences` 需先申报）；「建议」不是官方 `/Items/Suggestions` 语义（SDK 限制），SDK 升级后可只改取数一行。 |
| D57 | **视频页「库选择」+ 书架选书库（W54-C，用户 2026-10-03 确认）** | ①**视频页顶栏「库选择」**：服务器上有 ≥2 个视频库（movies + tvshows）时出现紧凑 chip（当前选择常显）+ 下拉菜单（「全部库」+ 各库 + 项目数）；**库卡模式 = 过滤显示哪些库卡；聚合模式 = 只聚合所选库条目**（分页流按所选库重建）；选择落盘新增键 `pref_ui_video_library_id`（null = 全部库），写偏好触发 `SharedPreferences` 监听 → 页面即时生效（与「视频显示方式」同一机制）；选中的库被删 / 换类型 → 回落「全部库」（`resolveVideoLibrarySelection` 纯函数 + 单测）。②**书架顶栏「库选择」**：复用既有 `pref_ui_bookshelf_library_id`（与客户端设置「书架媒体库」同一键，两处表现一致），菜单 = 「自动」（第一个非空书籍库）+ 各 books 库；`BookshelfViewModel` 挂偏好监听 → 即时重解析（`resolveBookshelfLibrarySelection` 纯函数 + 单测）；服务器只有 1 个书籍库时不显示选择器。③**落点**：新增共享组件 `LibrarySelectorChip`（`presentation/components`，走 §8.3 `CinefinFilterChip` + M3 `DropdownMenu`）、`presentation/utils/LibrarySelection.kt`（偏好 ↔ UUID 映射）；书架顶栏由 `LibraryScreen` 渲染，故给它加了一个默认空的 `topBarActions` 槽位（越界 1 处，已申报）。 | 顶栏 56dp 内放 32dp 视觉高的 chip（触控热区 48dp）；`LibraryScreen.kt` 的改动只有「加 1 个参数 + 动作区调用」，W54-B 重写头部时按需重放。 |
| D58 | **顶栏「收藏」/「睡眠」入口 + 「库卡中间大字」证据（W54-C，用户 2026-10-03 确认；睡眠本体 W55 已转正）** | ①**收藏**：视频页 / 书架页顶栏 `ic_heart` ↔ `ic_heart_filled`，收藏目标是「当前显示的那个库」（视频 = 所选库或临时库；书架 = 已解析的书籍库），走既有 `markAsFavorite` / `unmarkAsFavorite`（data 层网络失败会标记「待同步」，与详情页收藏同语义）；「全部库」/ 无库时不显示（没有单一目标）。`FindroidCollection.favorite` 现已从服务器 `UserData.isFavorite` 映射（此前恒 false）。②**睡眠**：视频页顶栏 `ic_video_sleep`（与播放器 `ic_player_sleep` 同造型）→ **W55 已转正（2026-10-04）**：打开共享 `CinefinSleepTimerOptions` 面板（10 / 20 / 30 / 60 + 自定义 1–240 分钟），与音乐 / 播放器共用 `player:local.SleepTimerController` 状态源，激活时图标点亮；书架不显示睡眠入口（用户口径）。③**「库卡中间大字」= 服务器库封面自带**：只读探针 `GET /Items/{id}/Images/Primary` 返回 960×540 PNG，图中居中就有「电影」/「动漫」大字（`Backdrop/0` = 404 → 库卡走 `images.backdrop ?: images.primary`）；客户端 `LibraryEntryCard` 只画左下「图标 + 库名 + 共 N 个项目 + 箭头」，没有任何中间标题（**未改代码**）。**待用户拍板**：a) 保持现状（大字是库封面内容）；b) 库卡不用库封面、改回「类型图标 + 库名」的中性面板；c) 用户自行更换服务器库封面。 | 用户 20:20 截图（`E:\桌面\Screenshot_2026-10-03-20-20-46-377_io.github.zhangwenkang.aurorama.debug.jpg`）与只读接口图同源；改客户端无法去掉图片自带文字，属"数据 vs 呈现"取舍。 |
| D59 | **详情页动作排重绘 + 下载确认框 / Snackbar / 侧栏下载角标 + 三项下载设置 UI（W51，用户 2026-10-03 确认）** | ①**动作排**：Show / Season / Episode 三层详情页介绍上方 =「下载 / 已播放 / 喜欢」同排三键（`DetailLabeledButton`：48dp 触控高、12dp 圆角、1dp 描边；未选中中性透明、选中当前域容器 + `Media.Outline`，Lumen 下自动极光青）+ 播放行（播放 / 重播 / 预告 Icon 44dp）；下载键三态文案（下载 / 已在队列 / 已下载）；**§8.1 口径**（播放 = Filled、下载 / 收藏 = Outlined）；「已播放 / 喜欢」点击乐观更新 + API 失败回滚（三 VM）。Movie 保留旧「已下载 → 删除」分支（本波不改电影）。②**批量确认框**（`BatchDownloadDialog`）：Lumen 面板 + 月白主行动，默认「仅补齐缺失集」、已下载 / 已在队列动态计数、默认「单次上限 100 集」开关（可关）；全部已在库 / 队列时不弹框，直接三态 Snackbar。③**Snackbar**（新 core `CinefinSnackbarHost`，§8.12：反色底 / 圆角 12 / 高 ≥52 / 宽 ≤480 / 无投影）：三态 + 批量「已加入下载队列 · N 集」。④**角标**：`CinefinNavItem.badge` 槽位 + `CinefinCountBadge`（>99 收敛，`OnSurface` 底 / `Surface` 字，中立不占媒体色），落点 = 侧轨 + 抽屉的「下载」项，活动任务数 = 下载中 + 排队 + 暂停，0 隐藏；`NavigationRoot` 前台 RESUMED 期间 2s 只读轮询（**红线，已申报**）。⑤**设置**：下载与缓存子页新增「仅 Wi-Fi 下载（默认开，对既有键取反绑定）/ 同时下载数 1–3（默认 2，`PreferenceIntSelect`）/ 下载完成通知（默认开）」；**不新增配色 / 字体 / 位图**、`AppPreferences.kt` 零改动。 | 底栏（W53 后 = 首页 / 视频 / 音乐 / 书架）没有「下载」项，故无底栏角标落点；旧 M3 `AlertDialog`（取消 / 删除下载确认）同步重绘为下载页同款 Lumen 面板；Episode 详情页的「删除下载」入口统一移到下载页（三态按钮点击只提示状态）。 |
| D60 | **侧栏「本地媒体库」子分组 + 本地库卡 16:9 缩略图（W53B，用户 2026-10-03 确认）** | ①**位置**：「媒体库」组内、服务器库之后 = 细分隔线 + 子分组标题「本地媒体库」+ 每库一行（名称 + 项目数）；**侧轨与抽屉两处都生效**，样式沿用既有侧栏行（类型图标 / 紧凑二级行 / 子项缩进 / 项目数尾标），**不新增配色 / 字体 / 位图**。②**数据与规则**：`DrawerViewModel` 只读本机 `LocalLibraryRepository`，`sidebarLocalLibraries()` 纯函数按库级「在媒体库显示」过滤并映射 id / 名称 / 类型 / 项目数；**0 个本地库整组隐藏**（标题与分隔都不画）；本地库属本机索引，**离线模式同样列出**（服务器库仍按既有规则在离线时为空）。③**交互**：点一行 → 既有 `LocalLibraryRoute(libraryId)`（与媒体库页本地库卡同落点，`launchSingleTop` 防重复入栈）；返回 / 切其它入口自然回各自默认页（与 W53 临时库视图语义一致，不写偏好）；当前本地库在侧轨 / 抽屉子项高亮，父项「媒体库」随之保持高亮。④**168dp 轨宽取舍（完整名称优先）**：侧轨子项文字可用宽 = 168 − 2×10（导航列内边距）− 16（子项缩进）− 2×14（条目内边距）− 24（图标）− 12（图标与文字间距）= **68dp**；`libraryChildCountVisible()` 纯函数 + `TextMeasurer` 实测名称 / 项目数宽度决定是否显示「N 项」——名称 + 间距 + 项目数放不下就**省略项目数**（不再把「音乐测试」截断成「音乐测…」）；抽屉 320dp 宽仍走「有值就显示」。⑤**刷新**：导航变化（进出本地库详情 / 媒体库页新建后离开）与本地库详情页的开关 / 重命名 / 条目数变化各触发一次只读刷新（不重拉服务器库列表）。⑥**本地库卡**：媒体库页本地库卡保持紧凑行卡，缩略图从 40dp 正方形放大到 **100×56.25dp（16:9，落在「约 96–104dp 宽」档）**，无封面仍回退类型图标；**服务器库卡（16:9 大卡）不动**。⑦单测：本地库列表过滤 / 0 库 + 排版规则三态 + 68dp 尺寸链共 6 项（app）。 | 边界：168dp 展开轨下 ≥5 个全角字符的库名仍走既有省略表现（名称本身已超出 68dp，属尺寸边界）；抽屉里名称与项目数完整可读。 |
| D61 | **首页模块结构与首页设置收口（W54-D，用户 2026-10-03 确认「全部按推荐」）** | ①**走廊结构**：继续观看 / 继续阅读 / 继续收听三条独立走廊（各自开关、无内容自动隐藏）→ 接下来（保留，独立开关）→「最新 · <库名>」每库一条（逐库开关、默认全开、有内容才显示，顺序默认按服务器库顺序、设置里可上下调整）→「最近添加」分视频 / 书籍 / 音乐三条（各自开关；**视频保留原海报墙形态**，书籍 / 音乐同款竖版海报墙）。②**数据口径**：继续观看 = `getResumeItems(MOVIE/EPISODE)`（原查询不变）；继续阅读 = `BOOK`、继续收听 = `AUDIO`（仓库 `getResumeItems` 新增 `includeItemTypes` 参数；这两条查询失败只隐藏该走廊、不让首页进错误态）；「最近添加」= 同一批「最新」数据按库类型分桶（视频向 = movies / tvshows / homevideos / mixed / boxsets / folders / playlists；书籍 = books；音乐 = music），去重取前 60；**逐库开关同时决定该库是否进入「最近添加」**（「在首页显示」= 首页不再出现该库任何模块）。③**设置页**（客户端设置 → 界面 → 首页）：模块开关组（继续观看 / 继续阅读 / 继续收听 / 接下来 / 最近添加×3）+ 新「首页媒体库」组（每库一行 = 库名 +「在首页显示」开关 +「默认分页」下拉；末行「媒体库顺序」上下调整）。④**默认分页**：选项与 W54-B 库页 tabs 同源（movies = 库内容 / 建议 / 类型 / 制片发行商；tvshows 六项；books / homevideos / boxsets / music 三项；playlists 仅库内容；混合「null」/ folders 四项），**本波只落盘**（`pref_ui_home_library_pages`），库页 tabs 消费留后续（W54-B 已合入基线，接消费即可）。⑤**偏好键（红线申报）**：删除 `pref_ui_home_library_id`（旧「首页媒体库」单选，正是「首页只有最新电影」的根因）与 `home_latest`（旧全局「最新」总闸，被逐库开关取代）；新增 `home_continue_reading` / `home_continue_listening` / `home_recently_added_videos` / `home_recently_added_books` / `home_recently_added_music` + `pref_ui_home_libraries_hidden`（**关闭集合，默认空 = 全开**，服务器新增库自动可见）/ `pref_ui_home_library_pages` / `pref_ui_home_library_order`。⑥**迁移说明**：升级后首页不再只显示某一个库——旧选择被忽略（键删除），全部服务器媒体库按服务器顺序显示；用户在「首页媒体库」里逐库关闭或调序，不需要重新选择；旧的「显示新增媒体」开关被「最近添加×3 + 逐库开关」取代（旧值为 on 时行为不变）。⑦**不新增配色 / 字体 / 位图**：新增字符串（film 5 + settings 8）×3 语言，图标复用 `ic_chevron_up/down`。 | 落点：`modes:film` `HomeViewModel/HomeState`、`app:phone` `HomeScreen`（走廊 / 海报墙 / 两处 action 转发）、`settings` `AppPreferences` / `SettingsViewModel` / 新 `HomeLibrarySettings`（编解码 + 纯规则）/ 两个偏好模型 + `app:phone` `SettingsHomeLibraryCard`；纯函数单测 10 项（顺序 / 每库开关 / 默认分页 / 编解码 / 「全部」落点）。 |
| D62 | **「最新 · <库名>」右侧「全部」修复：action 转发 + 库内容页 + 默认「最近添加」排序（W54-D）** | ①**根因**：`HomeScreen.kt` 里 `HomeView(onAction = { if (action is HomeAction.OnItemClick) … })` 把 `HomeAction.OnLibraryClick` 丢掉了（点「全部」无任何反应）。②**修法**：`HomeScreen` 新增 `onLibraryClick: (FindroidCollection) -> Unit`，在走廊统一走 `HomeAction.dispatch`（`OnItemClick` → 详情、`OnLibraryClick` → 导航）；导航侧纯函数 `homeViewAllRoute(id, name, type)` 返回 `LibraryRoute(..., sortBy = DateAdded, sortOrder = Descending)`；`LibraryRoute` 新增可选 `sortBy/sortOrder`（红线申报），`LibraryViewModel.setup` 接到后只作**本次进入**的初始排序（不写 `pref_sort_by`，用户改排序才落全局键），其余入口传 null 行为不变。③**已知边界**：音乐库的 `LibraryRoute` 走既有兜底进音乐模式（音乐模式无「最近添加」排序面板）；「默认分页」本波只落盘，未接进库页 tabs。 | 单测 `HomeViewAllRouteTest` 2 项覆盖「同库参数 + DateCreated 倒序」。 |
| D63 | **设置账号卡并入「账号与服务器」组首行（W56，用户 2026-10-04 拍板）** | ①`SettingsScreen` 删除 LazyColumn 顶部独立账号卡（原 `item(key = "account")` + 独立 `LumenCardFrame`），改由 `SettingsGroupCard` 新增的 `header` 组内首行插槽渲染同一 `SettingsAccountHeader`（头像 / 昵称 / 服务器 / 身份徽标全保留、点击仍进用户管理），插槽与组内行之间画同规格发丝线；②识别口径 = `PreferenceGroup.nameStringResource == settings_group_account_server`（5 组 IA 的第一组，组内既有「服务器」「网络」两项不动）；③不新增配色 / 字体 / 位图 / 字符串资源，`AppPreferences.kt` 零改动。 | 账号卡原本与分组卡并列在列表顶部，割裂了「账号与服务器」的组归属；插槽式合并保持卡片壳 / 行样式与既有组件不变，其余分组零感知。 |
| D64 | **顶层图标统一「回对应主页」（W56，用户 2026-10-04 拍板）** | ①落点判定抽纯函数 `topLevelTapAction(isOnEntryHome, hasInPageOverlay)`（`app/phone` `presentation/navigation/TopLevelNavigation.kt`，4 项单测）：已在入口主页 → `Stay`（不重复导航 / 不闪烁）；主页 + 页内二级层 → `CollapseOverlay`；二级页 / 其它入口 → `Navigate`（沿用 W53 D54 的 `safeNavigate + popBackStack` 弹回入口根页）；②音乐全屏播放 / 歌词页 / 专辑·艺术家·歌单详情都是音乐页内状态（不在导航栈里）：`MusicModeScreen` 新增 `reselectSignal`（递增 = 收起覆盖层 + 退页内详情）与 `onInnerPageOpenChange`（回报「页内二级层是否打开」= 覆盖层或详情）；`NavigationRoot` 点「音乐」顶层图标走三分支，从二级页 / 其它入口回音乐时同样先收起恢复出来的覆盖层；③逐场景核对：首页 / 视频 / 音乐 / 书架 / 媒体库 / 下载 / 设置根页已在主页时均不重复导航；库内容页（视频 / 书籍）、临时库、视频与书架详情、本地库详情等由既有 `Navigate` 分支弹回对应根页；④已知边界：设置子页（同一目的地的不同 `indexes` 参数）不算「主页」，仍走既有导航；`Stay` 分支仍在最前面关抽屉，手机抽屉行为不回归。 | 音乐覆盖层此前无法用顶层图标关闭（点「音乐」是导航 no-op），是用户明确点名的缺口；把三态判定提成纯函数后，覆盖层语义可单测，其余入口只做「不重复导航」的最小改动。 |
| D65 | **下载设置手动输入 + 「仅 Wi-Fi」不看计费 + 下载页缩略图本地优先（W57，用户 2026-10-04 拍板）** | ①**下载与缓存子页**：同时下载数改 `PreferenceIntInput(1..8)`（原 1/2/3 单选）、新增「下载限速」`PreferenceIntInput(0..100 MB/s，0 = 不限速)`、图片缓存默认 20 → **50 MB**；`PreferenceIntInput` 新增 `valueRange`（越界输入在对话框与写库双层自动钳制）。②**网络策略**：`DownloadNetworkRules`（纯函数）——`hasTransport(WIFI/ETHERNET)` 直接允许、**不看系统计费标记**；其余网络（蜂窝等）仍看「允许移动数据 / 漫游」开关。③**缩略图**：所有条目入队即由 `ImagesDownloaderWorker` 落盘自身封面 + `DownloadArtworkRules`（本地优先 / 剧集 条目→季→节目→远程兜底）+ 无图类型图标占位；飞行模式（断网）实测仍显示。④**限速**：`DownloadSpeedLimitRules` + `DownloadThrottle`（本次会话平均速率节流），每次任务启动读取偏好（运行中的任务不打断）。⑤新增文案三语言（默认 / zh-rCN / zh-rTW）。 | 红线动 `AppPreferences.kt`（缓存默认 50 + 新键 `pref_download_speed_limit_mbps`，已申报）；`NavigationRoot.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local` 未动。门禁 599 项 / 0 失败（含 `player:core` 全量 606）；真机复现 + 复验见 `DOWNLOAD_PLAN` §21。 |
| D66 | **W58 多选批量的 UI 口径 = 下载页多选 + 设计系统 §8.5（用户 2026-10-04 拍板；三模式共用，先音乐打样）** | ①**入口**：长列表 / 网格行与卡片**长按**进入多选（`Modifier.cinefinSelectable`：普通态单击 = 原动作，长按 = 进入并选中该条）；②**选中反馈**：列表行行首 20dp 勾选（§8.5：选中 = `Media.Base` 填充 + `OnBase` 勾，未选中 = 透明底 + 1dp 描边），卡片左上角同款指示（§8.4「已选圆点」），不在卡片上叠媒体色底；③**顶栏**：多选态标题替换为「已选 N 项」+「全选 / 取消全选」+ × 退出（复用 `CinefinButton(Text, Small)` / `CinefinIconButton` + `ic_close`），返回键先退多选；④**底栏**：下载页同款工具条（`CinefinBatchBar`：「已选 N 项」+ 右动作键），可用性 = 任一选中条目满足（纯函数判定），全不选 / 系统返回自动退出；⑤**全选** = 当前视图**已加载**条目（分页列表不拉全库）；⑥不新增配色 / 字体 / 位图；新增文案 `selection_select_all` / `selection_select_none` / `selection_exit` / `selection_load_failed`（默认 / zh-rCN / zh-rTW），动作词条复用既有 `download_action_*` / `title_*`。⑦**红线口径**：音乐「删除」只删本机下载（已下载且非本地媒体库的服务器条目），纯服务器 / 本地媒体库条目入口置灰或隐藏； **不新增任何服务器媒体删除写操作**。 | 与下载页多选视觉 / 交互同源，避免三模式各造一套；状态抽 `core` `MultiSelectState` 纯函数后「选中集合增删 / 已加载全选 / 删除仅本机」都能单测。音乐落点与决策细节见 `MUSIC_PLAN` D66–D69 / §5.17；视频 / 书籍待接。 |

| D68 | **W59 下载页钻取式 IA + 书籍封面自动生成 + 视频层级图严格同级 + 音乐专辑批量下载（用户 2026-10-04 拍板，本波全部按推荐执行）** | ①**下载页 IA**：顶层列表只显示 Show 卡 / 专辑卡 / 电影条目 / 书籍条目（书籍平铺单条目行，不建容器）；卡上显示聚合进度（状态 · x/y 集 + 体积 + 速度 / 剩余）；页签「进行中 → 失败 → 已完成」+ 类型筛选保留；**Show / 专辑点击钻取详情**（同一路由内状态切换，未加路由）：上半 = 海报 + 标题 + 状态 / 总进度（x/y + 体积 + 速度剩余）+ 操作键「全部暂停 / 全部继续 / 删除」；下半 = 季卡（海报，点击展开剧集 16:9 缩略图）/ 专辑曲目平铺；进入详情**自动展开第一个进行中的季**并滚到可见（`DownloadDrilldownRules.autoExpandSeasonKey`）；行样式统一「左图固定比例 + 右侧标题 / 状态行 + 操作键右对齐」；多选批量操作保留（容器选中 = 全部后代条目）。②**书籍封面自动生成**：服务器图优先 → 生成缓存 → 生成 → 类型占位（`BookCoverRules.planCover`）；方案同本地书籍（PDF 首页 / CBZ 第一图 / EPUB 封面），**未下载的在线书籍也生成**——core `BookCoverProvider` 用 HTTP Range + `ZipArchiveReader`（只读中央目录 / 目标条目）+ PdfBox `RandomAccessRead` 适配，**不整本下载**；懒生成（条目可见时一次）+ `files/book_covers/<id>.jpg` 缓存 + `.fail` 失败标记；书架 `LibraryViewModel.requestBookCover` 仅对无服务器图的书籍触发，UI 用 `ItemPoster.imageOverride` + `placeholderIconRes(ic_book)` 回退类型占位。③**视频层级图严格同级**：Show / Season 只用自己的海报（primary）、Episode 只用自身缩略图（帧图），某级缺图 → 类型占位、**不跨级回退**（`DownloadArtworkRules.videoArtwork` 单测锁定）；顺带修 W52 遗留——进行中剧集按「存在 sources 即纳入」的层级查询（`getEpisodeHierarchyWithSources`）分组，不再被当电影平条、可正常钻取。④**音乐**：专辑列表长按多选（复用 W58 `MultiSelectState` / `CinefinBatchBar`，底栏仅「下载整张」）→ `MusicAlbumDownloadRules.planAlbums`（按专辑序 + 音轨序、去重、单次 100 首上限）走既有入队链路；专辑详情加「下载专辑（N 首）」按钮（仅补齐缺失 + 100 首上限，无缺失置灰「已全部下载」）；无图专辑 / 曲目用「现有音符矢量 `ic_music` + 媒体色底」占位，**不新增位图**、不再黑块空白。⑤**红线**：未动 `NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local`；申报非红线：`core/build.gradle.kts`（+pdfbox-android 同版本）、`data`（新 DAO 查询 + 仓库两实现）、`core` 新 `BookCover*` / `BookByteSource` / `ZipArchiveReader`、`modes:film` / `modes:music` / `app:phone`。 | 钻取式 IA 把「顶层聚合密度」和「层级详情」分开，避免 W52 折叠容器在大列表下的杂乱；书籍封面用 Range 只读片段，遵守「未下载也出图」又不整本拉流；严格同级图规则避免 W36 跨级回退的串图（剧集行显示季海报）；专辑多选与「下载专辑」共用同一计划纯函数，100 首上限与详情页整剧下载口径一致。详见 `DOWNLOAD_PLAN` §22。 |

| D69 | **W60 图标重绘（搜索 / 书签）+ 首页续读进度条 + 下载重试补齐（用户 2026-10-04 拍板；与 W59 并行，文件域隔离）** | ①`ic_search.xml` 重绘：24dp / 1.75dp 描边 / 圆角端点；圆 r 6.5 → **7**（更饱满）、45° 手柄加长（起点贴圆边 15.45 → 终点 21,21，墨迹仍留 2dp 安全边距）。②新增 `ic_bookmark.xml` / `ic_bookmark_filled.xml`（收藏新图标；填充态 = 填充 + 同色圆角补边，与 `ic_heart_filled` 同口径）。③新增 core 文案 `download_retry_in_progress`（默认 / zh-rCN / zh-rTW）。④首页走廊卡片进度改 `cardResumeFraction()`：优先 `playbackPosition / runtime`，无时长数据（书籍 `runtimeTicks = 0`）或位置为 0 时回退 `UserData.playedPercentage / 100`（`FindroidItem.playedPercentage` 接口默认 null + `FindroidFolder` / `FindroidMovie` 映射补齐）；「继续阅读 / 继续收听」与「继续观看」同款 `progressTrackOnImage` 3dp 进度条。⑤下载失败重试：`ImagesDownloaderWorker` 失败感知（5xx / 408 / 429 / IOException 为瞬时失败，最多 3 次尝试 + WorkManager 指数退避；临时文件改名避免残片被当缓存）+ 纯函数 `ImagesDownloadRetryRules`；应用层任务级分类退避（W50 已有）只读核对无缺口。 | 图标视觉对齐设计系统 §7.1（本波按任务书 1.75dp）；书签对与「重试中 · 第 N 次」文案的 UI 接线归 **W60b**（下载页 / 收藏按钮文件域属 W59 / W60b，本波只出图标 / 字符串 / 数据）。真机 K60 通过（搜索图标 / 续读进度条 / 断网自动重试），见 §5 W60。 |

| D70 | **W60b 收藏链路统一 + 下载状态徽标 / 反馈统一（用户 2026-10-04 拍板，W60b 任务书；两段提交 `7702f38` + `c1f85d2`）** | ①**收藏术语与图标统一**：条目级一律「收藏」，`ic_bookmark` / `ic_bookmark_filled` 取代 `ic_heart` 对（详情页动作排、音乐收藏页签 / 批量条 / 全屏播放收藏键、多选批量条）；`detail_action_favorite` zh 文案「喜欢」→「收藏」。②**去掉媒体库收藏**：视频页 / 书架顶栏「收藏当前媒体库」与媒体库页顶栏「收藏」（收藏的媒体库列表）三处入口连同 `VideoViewModel.toggleFavorite` / `BookshelfViewModel.toggleFavorite` / `FavoritesViewModel`（收藏媒体库聚合）一并删除。③**新增侧栏一级「我的收藏」**（`FavoritesRoute` 重定位）：`filters=IsFavorite` 跨库汇总（电影 / 剧集 / 单集）+ 类型筛选（全部 / 电影 / 剧集 / 单集）+ 排序（加入日期 = `DateCreated` 倒序 / 名称 = `SortName` 升序，服务端排序）+ 已加载全选 + 三键批量（收藏 / 下载 / 已看，复用 W58 `MultiSelectState` / `CinefinBatchBar`，新增 `MediaBatchMode.FAVORITES`）。④**单一数据源**：`data` 新增 `UserDataEvents.favoriteVersion`（仓库层广播，`markAsFavorite` / `unmarkAsFavorite` / 音乐 `setFavorite` 成功后自增）+ `FavoriteChangeEffect`（`rememberSaveable` 记录已处理版本，页面被详情盖住期间的变化回到页面补刷新）；库网格 / 首页走廊 / 我的收藏页 / 搜索结果一起刷新；卡片新增书签角标（`FavoriteBadge`，已收藏时显示）。⑤**下载状态徽标**（列表 + 详情海报）：`DownloadStatusMonitor`（单例，页面持有期间 1500ms 轮询下载引擎只读快照 + 阅读器 `files/books` 离线书籍）→ `DownloadBadgeInfo`（下载中 = 进度环 / 已下载 = 完成角标 / 暂停 = 双竖线 / 失败 = 红叹号）；错位规则 = 右上被收藏书签 / 未看数 / 已看打勾占用时下载徽标落右下（`downloadBadgeCorner` 纯函数），横版卡错位时上移 48dp 避开标题；覆盖库网格 / 首页走廊 / 搜索结果 / 详情海报 / 我的收藏页。⑥**下载反馈统一**：`CinefinSnackbarHost` 支持动作文字（`showSnackbar(actionLabel)`），详情页 / 整剧 / 全季 / 单集 / 电影 / 多选批量 / 音乐歌曲·专辑全部给三态 Snackbar +「查看」跳下载页；音乐新增下载反馈事件（`downloadQueued`）。⑦**下载页「重试中 · 第 N 次」**接线（`showsRetryLabel`：`retryCount > 0` 且 RUNNING / PENDING）。⑧**音乐批量播放解析优化**：选曲后其余曲目按 4 首并发预取（保列表序入队），起播不被预取阻塞。 | 收藏是条目级唯一状态、下载是引擎级唯一状态，两边都以「仓库 / 引擎快照」为源；角标错位规则只在 `CardBadgeOverlay` 一处实现，避免四套卡片各写一份；「查看」动作让反馈闭环（提示 → 跳转）。 |

| D71 | **W62 库卡 / 侧栏「项目数」口径改稳定值（服务器 `ChildCount` 随机；W61 F1）** | ①只读 API 探针复核：服务器 10.11.8 对 UserView 的 `ChildCount` 是随机值（同一请求连续采样 电影 8/4/6、动漫 4/5/2、书籍 9/5/3…），`RecursiveItemCount` 字段不返回；②新口径 = 按库直接子项查询的 `TotalRecordCount`（`parentId` + `recursive=false` + `limit=1`）→ **数字 = 进入该库后内容页看到的条目数**（电影 17 / 动漫 94 / 其他2 2 / 书籍 8 / 书籍3 8 / 音乐 124；空库 = 0，`LibraryEntryCard` 既有「0 不占位」口径不变；侧栏尾标仍「有值就显示」）；③`data` `LibraryItemCount.kt`（纯函数 + 60 s TTL 缓存）+ `getLibraries()` 并发取数，失败回退 `ChildCount`；**否决**递归口径（动漫 2364 含 2099 单集 + 171 季，与库页 `1-94 / 94` 不一致）。 | 真机 Pad 媒体库 8 库 + 视频页 电影 17 + K60 视频页 17/94，重进一致（`TEST_PLAN` §7.6.6）。 |
| D72 | **W62 离线书架书籍清单口径（与下载页「已完成 · 书籍」对齐；W61 F2）** | ①原实现：离线书架按 `allowOffline` 过滤，而下载页不过滤 → 实测 `pref_offline_blocked_books` 有遗留关闭的 `futuristic_tales`，书架 4 本且**无处回开**（离线媒体库只列视频、没有书籍管理视图）；②新口径：**书架列出全部已下载书籍**（关闭项行内 `OfflineLeafCard` 既有置灰 + 开关可回开），`OfflineMediaViewModel.UiState.downloadedBooks/.bookCount` 同源，离线首页「已下载 N 本」同步；③书名口径统一「`.title` 侧车优先 → 服务器元数据兜底 → 「离线书籍 <8>」占位」（`core` `offlineBookDisplayName`），下载页与书架同名。 | 真机离线模式：书架「已下载 5 本」= 下载页 5 本同名（`TEST_PLAN` §7.6.6）。 |
| D73 | **W63 下载域缺陷修复（用户 2026-10-04 全检 7 条；先复现后修）** | ①**批量入队不中断**：`Downloader.enqueueItems`（批量入口）+ `DownloaderImpl` 节目 / 季快照缓存（TTL 10 分钟）+ 首次并发拉取；`DetailDownloadViewModel` 以 `withContext(NonCancellable)` 调用（离开详情页整批照常入队），Snackbar 数 = 实际入队数。②**Season 页集列表长按多选**：复用 W58 core 框架（`rememberMultiSelectState` / `cinefinSelectable` / `CinefinSelectIndicator` / `MediaBatchTopBarActions` / `CinefinBatchBar`），底栏「下载」（仅补齐缺失集）。③**下载页长按多选**：`CinefinCard` 新增可选 `onLongClick`（`combinedClickable`），`DownloadRows` 四个行组件接长按 → 复用既有 `ToggleSelection`（与右上「选择」共用状态）。④**Snackbar 时长**：core `DownloadSnackbarDuration = SnackbarDuration.Long`，全域 8 处下载反馈显式传 `duration`（修复 `actionLabel` 默认 `Indefinite` 常驻）。⑤**侧栏角标**：`Downloader.queueChanges` 事件 + `DownloadBadgeViewModel` 订阅即时刷新（2 s 轮询兜底）；计数改纯函数 `DownloadBadgeRules.activeBadgeCount` = 活动集去重 − 已下载集。⑥**下载页首屏**：`DownloadsViewModel.refresh` 拆两阶段（阶段 1 只用 Room / 文件先上屏；阶段 2 服务器元数据 `mapBounded(4)` 补齐），本地图存在时不再请求远程兜底图。⑦不新增配色 / 字体 / 位图；season 多选文案复用既有 `download_selected_count` / `selection_select_all|none|exit` / film `batch_action_download`。 | 红线：动 `core`（`CinefinCard` / `Snackbar` / `Downloader` 接口 + `DownloaderImpl`）、`app:phone`（Season / Downloads 屏 + `EpisodeCard` + `DownloadRows` + 7 处 Snackbar）、`modes:film`（`DetailDownloadViewModel` / `DownloadsViewModel` / 角标 VM + 新规则）；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` / `data` 未动。门禁 **719 项 / 0 失败**（新增 6）；真机复验见 `DOWNLOAD_PLAN` §26 / §5 W63。 |
| D74 | **W65 侧栏宽度自适应（比例 + 夹取；用户 2026-10-04 拍板，全部按推荐）** | ①**平板侧轨展开宽** = `clamp(屏宽dp × 30%, 200dp, 240dp)`（Pad 5 711dp ≈ 213dp，「名称 + X 项」能完整显示）；**折叠轨恒定 72dp**。②**手机抽屉宽** = `clamp(屏宽dp × 55%, 208dp, 280dp)`（K60 411dp ≈ 226dp，遮挡约一半、文字完整）。③宽度计算抽纯函数 `railExpandedWidthDp(screenWidthDp)` / `drawerWidthDp(screenWidthDp)`（`NavigationIa.kt`；屏宽取 `LocalConfiguration.screenWidthDp`，旋转 / 分屏即时生效），单测 7 项覆盖比例 / 上下限 / 边界（360 / 393 / 600 / 711 / 1000dp）。④**让位规则保留并扩到抽屉**：库子项「名称优先、X 项放不下才让位」——`railLibraryLabelWidthDp(轨宽)` 尺寸链随展开宽实时计算（168dp 轨 = 68dp → Pad 5 213.3dp 轨 = 113.3dp），新增 `drawerLibraryLabelWidthDp(抽屉宽)`（320dp 旧抽屉 = 208dp → K60 226.3dp 抽屉 = 114.3dp），侧轨与抽屉共用实测宽度判定 `adaptiveLibraryCountTrailing`。⑤`CinefinModalDrawer` 新增 `drawerWidth: Dp = 320.dp` 参数（宽度参数化，默认值保持既有预览 / 调用方行为）。⑥其余侧栏行为（排序 / 分组 / 本地媒体库子分组 / 选中态 / 半透明 / 指示条）零改动；不新增配色 / 字体 / 位图 / 字符串。 | 红线申报：`NavigationRoot.kt`（宽度接线 + 让位判定）、`core/CinefinDrawer.kt`（宽度参数化）、`NavigationIa.kt`（纯函数 + 两条尺寸链）；`AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 未动。门禁 **730 项 / 0 失败**（基线 723 + 新增 7）；真机 K60 主 + Pad 5 抽验通过（见 §5 W65）。 |

## 4. 进度

### W65 侧栏宽度自适应（2026-10-04，分支 `fix/w65-adaptive-sidebar`，起点 master `c5e8384`；K60 `8e875894` 主 + Pad 5 `43af8627` 抽验，本会话自带真机）

- **范围（用户 2026-10-04 拍板，全部按推荐）**：解决「平板侧轨太紧凑看不到 X 项 / 手机抽屉遮屏过多」——侧轨展开 `clamp(屏宽 × 30%, 200, 240)dp`、手机抽屉 `clamp(屏宽 × 55%, 208, 280)dp`、折叠轨恒定 72dp；让位规则保留（库子项「名称优先、X 项放不下才让位」）。
- **落点**：`NavigationIa.kt`（`railExpandedWidthDp` / `drawerWidthDp` 纯函数 + `railLibraryLabelWidthDp(轨宽)` / `drawerLibraryLabelWidthDp(抽屉宽)` 尺寸链）、`NavigationRoot.kt`（**红线**：屏宽接线 + 侧轨 / 抽屉宽度 + 侧轨与抽屉共用的实测让位判定）、`core/CinefinDrawer.kt`（**红线**：`drawerWidth: Dp = 320.dp` 参数化）；`AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 零改动；不新增配色 / 字体 / 位图 / 字符串。
- **单测**：`AdaptiveSidebarWidthTest` 7 项（比例 / 上下限 / 360 / 393 / 600 / 711 / 1000dp 边界 / 两条尺寸链 / Pad 5 与 K60 让位样例）；`SidebarLocalLibraryTest` 尺寸链改传参。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **730 项 / 0 失败 0 错误**（app 179 / core 79 / data 50 / player:local 110 / film 53 / book 113 / music 134 / player:core 12 = 基线 723 + 新增 7）。
- **真机（K60 `8e875894` 主 + Pad 5 `43af8627` 抽验，15:00–15:04，device-lock 已写释放与结论；时间窗按会话日志更正）**：K60 抽屉 791px = 226.3dp = 54.9% 屏宽 + 库行「名称 + 项目数」完整；Pad 5 折叠 72dp / 展开 480px = 213.3dp + 库行「名称 + X 项」全部完整（修复前 168dp 下项目数被省略）；页面切换 / 分组展开正常、0 FATAL / ANR。

### W63 下载域缺陷修复（2026-10-04，分支 `fix/w63-download-fixes`，起点 master `a8a580f`；Pad 5 `43af8627` 主，本会话自带真机）

- **范围（用户 2026-10-04 全检 7 条，任务书）**：①Show / Season 页「下载」只入队 1 集；②Season 页集列表长按多选；③下载页长按多选（保留右上「选择」）；④Snackbar 常驻不消失（P1）；⑤侧栏角标延迟 + 数量错；⑥下载页首屏慢；⑦W60b 下载反馈复验。
- **落点**：`core`（`CinefinCard.onLongClick` / `DownloadSnackbarDuration` / `Downloader.enqueueItems` + `queueChanges` + `DownloaderImpl` 快照缓存 / 角标事件）、`app:phone`（`SeasonScreen` 多选 + 选择头 + 批量条、`DownloadsScreen` / `DownloadRows` 长按、`EpisodeCard` 选中态、7 处 Snackbar 时长）、`modes:film`（`DetailDownloadViewModel` 非取消批量、`DownloadsViewModel` 两阶段刷新 + 本地图优先、`DownloadBadgeRules` + 角标 VM 订阅）。
- **口径**：批量入队 = 实际入队数（`DownloadBatchResult`）；Season 多选「下载」与整季下载同口径（仅补齐缺失集、跳过已下载 / 队列内）；下载页长按与「选择」按钮共用同一选择状态；角标 = 活动队列去重 − 已下载。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **719 项 / 0 失败 0 错误**（app 172 / core 77 / data 50 / player:local 110 / film 53 / book 113 / music 132 / player:core 12；新增 6）。
- **真机（Pad 5 `43af8627`，13:39–14:40，device-lock 已写释放与结论）**：整剧 12 集（确认后 2 s 离开页面仍 12/12 入队）、全季 26 集、Season 长按多选（1 → 2 → 全选 12 → 批量 2 集入队）、下载页长按（已选 12 / 26）、Snackbar 自动消失（t≈14 s 消失）、角标即时（14 / 2 / 0）、首帧 2.9 s（修复前 >60 s 空白）、0 FATAL / ANR；测试下载全部清除（`files/downloads` 为空）。

### W60b 收藏链路统一 + 下载状态徽标 / 反馈统一（2026-10-04，两段提交：`7702f38` 收藏链路 / `c1f85d2` 下载体验；起点 master `32c0e37`；Pad 5 `43af8627` 主 + K60 `8e875894` 抽验已通过）

- **范围（用户 2026-10-04 拍板，W60b 任务书；分两段提交 + 各自门禁 / 真机）**：A 段 = 收藏概念一致性（术语 / 图标 / 去媒体库收藏 / 我的收藏页 / 单一数据源）；B 段 = 下载状态标识与反馈（徽标四态 / Snackbar 三态 + 查看 / 重试文案 / 音乐批量解析优化 / 搜索角标）。
- **A 段落点**：`core` 三语言（`title_my_favorites` / 筛选 / 排序 / 空态 / 徽标描述；`detail_action_favorite` 改「收藏」）、`ItemButtonsBar`（书签图标对）、`MediaBatchUi`（批量收藏图标）、`modes:music`（收藏页签 / 批量条 / 全屏播放收藏键）、`FavoritesScreen` 重写（跨库汇总 + 筛选 + 排序 + 三键批量）、新 `FavoriteRules` / `FavoritesItemsViewModel`、`FavoriteBadge` / `FavoriteChangeEffect`、`NavigationIa`（`NavEntryKey.Favorites`，书架之后；无可见性开关，常驻）、`NavigationRoot`（侧栏项 / 路由选中态 / `showNavigation` 白名单）、`VideoScreen` / `BookshelfScreen` / `MediaScreen`（去三处库收藏入口）、`data`（`getFavoriteItems(sortBy, sortOrder)` 服务端排序 + 离线空表 + `UserDataEvents` 收藏版本广播；`markAsFavorite` / `unmarkAsFavorite` / 音乐 `setFavorite` 成功后自增）、删除 `modes:film` `FavoritesViewModel`（收藏媒体库聚合）。
- **B 段落点**：新 `DownloadStatusBadge.kt`（`DownloadBadgeState` / `DownloadBadgeInfo` / `downloadBadgeInfo` 判定 / `downloadBadgeCorner` 错位 / 进度环 + 淡入）/ `DownloadStatusMonitor.kt`（单例轮询 + `badgeMapFor` 纯函数 + 页面级 `DownloadStatusViewModel` acquire/release）、`ItemCard` / `PosterItemCard` / `LandscapeItemCard` / `LibraryListRow` / `DetailPoster` 接 `CardBadgeOverlay`、`HomeScreen` / `HomeSection` / `HomeView` / `SearchBar` / `LibraryScreen` / `VideoScreen` / `FavoritesScreen` 传角标快照、`CinefinSnackbarHost` 支持动作文字、`DetailDownloadMessages.showsViewAction`、`MovieScreen`（`DownloaderEvent.Queued` → Snackbar + 查看）/ `ShowScreen` / `SeasonScreen` / `EpisodeScreen`（既有三态 Snackbar + 查看）、`DownloadRows.showsRetryLabel`（重试中文案）、`MusicModeViewModel`（`downloadQueued` + 批量播放 4 路并发预取）/ `MusicModeScreen`（Snackbar + 查看）、`LibraryScreen` / `VideoScreen` 批量 Snackbar + 查看。
- **纯函数 + 单测（新增 19）**：`FavoriteRulesTest` 3（排序 spec / 类型筛选）、`DownloadBadgeRulesTest` 6（四态判定 / 优先级 / 钳制 / 错位 / 详情三态映射）、`DownloadStatusMonitorTest` 4（任务优先级 / 已下载补充 / 已下载压过任务 / 暂停）、`DownloadRetryLabelTest` 4（重试窗口）、`DetailDownloadMessageRulesTest` 2（查看动作映射，失败不给动作）；`MediaBatchRulesTest` 扩充 `MediaBatchMode.FAVORITES` 动作集合、`NavigationIaTest` 顺序 / 分组 / 可见性（Favorites 常驻）。
- **门禁**：A 段 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun` **688 项 / 0 失败**（app 155 / core 73 / data 45 / player:local 110 / film 48 / book 113 / music 132 / player:core 12；基线 685 + 3）；B 段 = 根 `assembleDebug` + `ktfmtCheck` 全绿、8 任务 `--rerun` **704 项 / 0 失败**（app 171；B 段新增 16）。
- **真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-04 07:40–09:05，两段分别装机；device-lock 已写释放与结论）**：A 段——侧栏「我的收藏」+ 筛选 / 排序 / 空态 / 计数、长按多选 + 全选 / 取消 / ×、三键批量（取消收藏后条目即时消失）、详情收藏 → 收藏页 1 项 + 卡片书签角标、取消收藏 → 角标消失、媒体库 / 视频 / 书架三处库收藏入口消失；真机拦下并修复「我的收藏页未加入 `showNavigation` 白名单 → 侧轨消失」；K60 抽验抽屉条目 + 空态页。B 段——库网格进度环（下载中）/ 双竖线（暂停）/ 完成角标（K60 书架已下载书籍，含阅读器 `.book` 离线链路）；详情「已加入下载队列」+「查看」跳下载页、批量「已加入下载队列 · 2 项」+ 查看、音乐专辑「已加入下载队列 · 1 首」+ 查看；下载页「重试中 · 第 1 次」（下载中关 Wi-Fi → 重试 → 恢复 Wi-Fi 续传）；搜索结果收藏角标；音乐全选 124 首批量播放 → 首曲起播 + 队列 124 首按列表序（并发预取）；双机 0 FATAL / ANR。
- **未覆盖（留人工 / 用户代测）**：①失败徽标（红叹号）真机未复现——网络类失败按设计进入重试（`retryCount+1`，PENDING / RUNNING）而非 FAILED 终态；判定与配色由 `DownloadBadgeRulesTest` 单测覆盖，如需真机需服务器类故障注入；②搜索结果下载角标无已下载视频样本（搜索只覆盖视频库；代码与网格同一份 `CardBadgeOverlay`）；③角标淡入动画未逐帧验证（进度环旋转可见）；④音乐批量播放仅验证顺序与起始延迟，未做服务器压力上限测试。

### W60 图标重绘 + 下载失败重试 + 首页续读进度条（2026-10-04，分支 `feature/w60-icons-retry-resume-bar`，起点 master `9c65010`；与 W59 并行）

- **范围（用户 2026-10-04 拍板；真机由本会话自查）**：①图标重绘（只出图标文件，接线由 W60b 做）：`ic_search.xml` 重绘 + 新增 `ic_bookmark.xml` / `ic_bookmark_filled.xml`；②下载失败自动重试（历史遗留）：核对应用层现状 → 补齐 worker 失败重试 + 「重试中」文案（core 三语言）；③首页「继续阅读 / 继续收听」进度条（与「继续观看」同款）。
- **图标（core res）**：`ic_search` 圆更饱满（r 7）+ 45° 手柄加长（15.45 → 21,21），1.75dp 描边 / 圆角端点；书签对 = 书签外形 `M6.5,3.5 H17.5 V20.5 L12,16.75 L6.5,20.5 Z`（描边态 1.75dp；填充态 fill + 同色圆角补边）。
- **阅读进度（data + app）**：`FindroidItem` 新增 `playedPercentage: Double?`（接口默认 `null`，老实现零改动）；`FindroidFolder`（书籍）与 `FindroidMovie`（音频走该映射）从 `userData?.playedPercentage` 落值；`LandscapeItemCard` 新 `cardResumeFraction()`（`runtime` 换算优先、`playedPercentage / 100` 回退）供「继续阅读 / 继续收听」画 3dp 进度条。
- **下载重试（core）**：`ImagesDownloaderWorker` 由「吞掉失败恒 SUCCESS」改为感知瞬时失败（IOException / 5xx / 408 / 429）并 `Result.retry()`（`MAX_ATTEMPTS = 3`，WM 默认 30s 指数退避）；图片先写 `.part` 再改名；纯函数 `ImagesDownloadRetryRules` 4 项 core 单测。应用层任务级退避（`DownloadTaskRules`：网络类无限 / 服务器类 5 次 / 残片 3 次；`handleTaskFailure` + `wakeNetworkBlockedTasks` + WM CONNECTED 兜底）只读核对**无缺口**，未改。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **651 项 / 0 失败 0 错误**（app 152 / core 63 / data 45 / player:local 110 / film 40 / book 113 / music 128）、含 `player:core` 全量 **663 项 / 0 失败**；新增 8（core 4 + app 4）。
- **真机（2026-10-04 05:58–06:21，K60 `8e875894` 主；Pad 5 因 W59 未释放未使用；device-lock 已写释放与结论）**：①搜索图标——媒体库顶栏放大镜截图比对，圆 / 手柄 / 描边在暗背景可读、与相邻星形图标风格一致；②续读进度条——`futuristic_tales` 卡片底部出现与「继续观看」同款 3dp 进度条（`progressTrackOnImage` + `media.base`），无进度的 `虚构推理` 不画；「继续收听」本机无数据、走廊隐藏；③下载自动重试——电影「被狙击的学园」入队 → 飞行模式 → 引擎失败并调度任务级重试 → 关飞行后自动续传（残片 22.8 MB → 54.9 MB → 421 MB），DB 读出 `retryCount=1` / `taskStatus=RUNNING` / `nextRetryAt=0`；④0 FATAL / 0 ANR；测后测试下载删除（0 进行中 · 5 已完成 · 0 失败）、误触「已播放」已复原（`DELETE UserPlayedItems`）、飞行模式关闭、App force-stop、`/sdcard/w60.xml` 删除。
- **未覆盖 / 交 W60b**：①书签图标对未接线（设备无可见面）；②下载页「重试中 · 第 N 次」文案（`DownloadRows` 归 W59 / W60b；本波已出字符串 + `retryCount` 数据）；③图片缓存 worker 重试仅单测覆盖（设备故障注入时序难控）；④「继续收听」无数据样本。

### W59 下载页钻取式 IA + 书籍封面自动生成 + 层级图规则 + 专辑批量下载（2026-10-04，分支 `feature/w59-downloads-redesign`，起点 master `9c65010`；Pad 5 `43af8627` 主 + K60 `8e875894` 抽验已通过）

- **范围（用户 2026-10-04 拍板，决策 D68）**：①下载页钻取式 IA（顶层只显示 Show / 专辑 / 电影 / 书籍；卡上聚合进度；Show / 专辑点击进详情，自动展开进行中的季，操作键全部暂停 / 全部继续 / 删除）；②在线书籍封面自动生成（服务器图优先 → 生成 → 占位；HTTP Range 只读片段；懒生成 + 缓存 + 失败标记）；③视频层级图严格同级（Show / Season 海报、Episode 缩略图、缺图类型占位、不跨级）；④音乐专辑列表长按多选批量下载整张 + 专辑详情「下载专辑」（仅补齐缺失 + 100 首上限）+ 无图音符占位。
- **落点**：`modes:film` 新 `DownloadDrilldownRules`（排序 / 可钻取 / 自动展开 / 聚合 / `detailRows`）+ `DownloadArtworkRules.videoArtwork`（严格同级）+ `DownloadsViewModel`（容器级暂停继续 / 容器选中 = 全部后代 / 书籍封面懒生成 / 严格同级图 / 进行中剧集层级）；`app:phone` `DownloadsScreen`（顶层列表 + 钻取详情 + 自动展开滚动）+ `DownloadRows`（顶层卡 / 平铺行 / 详情头部 / 季卡 / 条目行 + 占位）+ `ItemCard` / `ItemPoster` / `LibraryListRow`（`imageOverride` + `placeholderIconRes`）；`core` 新 `BookCoverRules` / `BookByteSource`（HTTP Range 分块 LRU）/ `ZipArchiveReader`（EPUB / CBZ）/ `BookCoverProvider`（PdfBox 首页渲染）+ `DownloaderImpl` 层级口径；`data` 新 `getEpisodeHierarchyWithSources()`；`modes:music` 新 `MusicAlbumDownloadRules` + `MusicModeViewModel` 专辑作用域多选 / `downloadAlbum` + `MusicModeScreen`（专辑多选 / 专辑头部 / 音符占位）。
- **纯函数 + 单测（22 项新增）**：`DownloadDrilldownRulesTest` 6（film）、`DownloadArtworkRulesTest` +2（严格同级 / 不跨级回退）、`BookCoverRulesTest` 6 + `ZipArchiveReaderTest` 4（core）、`MusicAlbumDownloadRulesTest` 4（music）。
- **门禁（2026-10-04）**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务逐个 `--rerun` **677 项 / 0 失败 0 错误**（app 148 / core 69 / data 45 / player:local 110 / film 48 / book 113 / music 132 / player:core 12；基线 655 + 22）。
- **真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-04 06:0x–06:5x，device-lock 已写释放与结论）**：顶层聚合（Show 下载中 0/1 集 + 速度剩余 / 专辑已完成 1/1 首 / 书籍平铺）✓；页签进行中 → 失败 → 已完成 ✓；Show 钻取 + 自动展开进行中的季 + 全部暂停 / 继续（105 → 116 MB 续传）✓；层级严格同级 + 进行中剧集归属修复 ✓；书籍封面本地生成 3 张（395×512）+ 删除 `.book` 后 **HTTP Range** 重新生成 + 飞行模式缓存仍显示 ✓；专辑长按多选 +「下载整张」→ 专辑卡入队 ✓；专辑详情「下载专辑（1 首）」→ 置灰「已全部下载」✓；无图专辑音符占位（飞行模式滚动）✓；下载页多选批量删除（已选 5 项 → 9 → 5 已完成，占用 3.03 GB → 662 MB）✓；K60 紧凑列表 / 专辑多选「下载整张」抽验 ✓；双机 0 FATAL / ANR ✓。
- **红线**：未动 `NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local`；申报非红线：`core/build.gradle.kts`（+pdfbox-android 同版本）、`data`（新查询 + 仓库）、`core` / `modes:film` / `modes:music` / `app:phone`。

### W58b 视频 / 书籍多选批量（2026-10-04，分支 `feature/w58b-multiselect-video-book`，起点 master `5dc2c82`；Pad 5 `43af8627` 主 + K60 `8e875894` 抽验已通过）

- **范围（用户 2026-10-04 拍板 + 负责人补充指令）**：复用 W58 core 框架（`MultiSelectState` / `cinefinSelectable` / `CinefinSelectIndicator` / `CinefinBatchBar`），把长按多选 + 已加载全选 + 批量操作补到视频与书籍；真机由本会话自查。
- **落点**：`app/phone` `presentation/selection/`（新 `MediaBatchRules` / `MediaBatchViewModel` / `MediaBatchUi` / `VideoPlaybackLauncher`）、`LibraryScreen.kt`（视频库 / 书籍库共用，含列表行）、`VideoScreen.kt`（聚合网格；`collectAsLazyPagingItems` 上提到布局层供全选取已加载）、`ItemCard` / `LibraryListRow` 多选参数、`PlayerActivity` + `player/local` `PlaylistManager` / `PlayerViewModel`（显式队列，红线已申报）、`DownloadsViewModel`（顺带修书籍多选删除）。
- **核心口径（决策 D67）**：①视频五键 / 书籍三键；②「播放」= 显式播放队列（`getInitialItemForQueue` + `fillQueueInBackground`），剧集 → 下一集、季 → 第一集；③「下载」剧集 / 季按补齐缺失集展开（复用 `DetailDownloadRules`），书籍走 `ReaderRepository.downloadLocalFile`；④标记已看 / 已读与收藏双向；⑤删除仅本地 + 确认框；⑥全选 = 已加载；切 tab / 排序 / 筛选自动退出；系统返回先退多选。
- **纯函数 + 单测**：`MediaBatchRules`（动作集合 / 可用性 / 下载与删除目标 / 播放顺序 / 双向目标）12 项（app）+ `parsePlaybackQueueEntries`（并行数组解析 / 非法项丢弃 / 去重保序）5 项（player:local）；core 复用既有 `MultiSelectState` 13 项。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun-tasks` **655 项 / 0 失败 0 错误**（app 148 / core 59 / data 45 / player:local 110 / film 40 / book 113 / music 128 / player:core 12；新增 17 = app 12 + player:local 5）。
- **真机（2026-10-04 03:43–04:19，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验；device-lock 已写释放与结论）**：视频聚合网格（含临时库视图）长按 / 点选 / 全选 17 / 取消 / ×；批量播放 2 部电影 → 队列面板顺序 = 列表序（被狙击的学园 → 乔西的虎与鱼）+ `KEYCODE_MEDIA_NEXT` 顺序切换；批量下载 → 下载页出现并入队（1.83 GB 完成后清理）；标记已看 / 收藏经服务器筛选回读命中并复原；删除纯服务器条目置灰、已下载条目弹「删除本地下载（只删本机文件与索引）」→ 确认后本机文件删除；书籍（书架）三键（下载 / 标记已读 / 收藏）全通 + 筛选回读 + 下载 book 落盘 `files/books`；K60 视频长按 / 全选 17 / ×、书架三键通过；双机 0 FATAL / ANR。
- **未覆盖（留人工 / 用户代测）**：①批量队列跨片连播听感（已按序建队、播放页「自动下一集」默认开，未等整部影片播完实测自动接播）；②剧集库批量下载的整剧入队真机实测（代码与详情页同口径，本次真机未选剧集库做整剧下载）；③「删除」对本地媒体库条目置灰（代码判据存在，设备上无对应样本）；④uiautomator 对 Compose 禁用态 `enabled` 属性不敏感（本次用「点击无响应」判读）。

### W58 多选批量（2026-10-04，分支 `feature/w58-multi-select`，起点 master `ea18849`；音乐部分已交付）

- **范围（用户 2026-10-04 拍板）**：音乐 / 视频 / 书籍三模式长列表 / 网格长按进入多选 + 已加载全选 + 按类型批量操作；**本会话先做音乐打样**（含可复用框架与纯函数），视频 / 书籍后续接。
- **共用框架（core，已交付）**：`core/selection/MultiSelectState`（`selectionMode` + `selectedIds`；`longPress` / `toggle` / `selectAll` / `selectNone` / `retain` / `clear` 纯函数，非多选态选中集合恒为空）+ `core/presentation/components/CinefinMultiSelect.kt`（`cinefinSelectable` 长按手势 / `CinefinSelectIndicator` §8.5 20dp 勾选 / `CinefinBatchBar` 工具条）+ `CinefinListRow` 的 `onLongClick` / `selectionMode` / `selected` 默认参数 + 4 条新文案三语言（决策 D66）。
- **音乐（已交付）**：歌曲 Tab 与专辑 / 艺术家 / 歌单 / 收藏 / 最近播放详情长按进入多选；顶栏「已选 N 项」+ 全选 / 取消全选 + ×；底栏五键（播放 = 加入当前队列开始播 / 下载 / 收藏 / 删除只删本地 / 从歌单移除）；落点 `modes:music` `MusicBatchRules` + `MusicModeViewModel` / `MusicModeScreen`，细节见 `MUSIC_PLAN` §2.16 / §5.17。
- **视频 / 书籍（待做，已定边界）**：视频 = 库内容网格 / 聚合网格（Paging 已加载页）/ 临时库视图的卡片多选 → 播放（加入当前队列）/ 下载 / 标记已看 / 收藏 / 删除（仅本地下载）；书籍 = 书架库内容网格 → 下载 / 标记已读 / 收藏（无播放）；两者均复用本波 core 框架与 `CinefinListRow` / `ItemCard` 勾选指示，纯函数（动作可用性 / 已加载全选 / 删除仅本地 / 播放入队顺序）各自落 `app:phone` 单测。
- **接续指引（给下一会话，可直接照做）**：①`ItemCard` 增加 `selectionMode` / `selected` / `onLongClick` 三个默认参数（卡片左上角用 `CinefinSelectIndicator`，§8.4「已选圆点」）；②分页网格的「全选」只能在 `LazyPagingItems` 所在的 Composable 里取 `(0 until pagingItems.itemCount).mapNotNull { pagingItems[it]?.id }`，因此选中集合建议放在**页面层**（`rememberSaveable`，key = id 字符串）或由页面把「已加载条目」回传给 ViewModel，两条路线二选一但必须让 `retain()` 在翻页 / 刷新后求交集；③视频批量动作落点：聚合网格在 `presentation/video/VideoScreen.kt`，库内容网格在 `presentation/film/LibraryScreen.kt`（含书籍），两者的 ViewModel 各自注入 `JellyfinRepository` / `Downloader`；④视频「播放（加入当前队列）」复用 `PlaylistManager.getInitialItem` + `PlayerViewModel` 队列链路，删除只对 `item.isDownloaded()` 且能取到 LOCAL source 的条目；⑤书籍「标记已读」= `JellyfinRepository.markAsPlayed` / `markAsUnplayed`，收藏 = `markAsFavorite` / `unmarkAsFavorite`，下载 = `Downloader.downloadItem`（书籍无播放）；⑥红线提醒：`BOOK` 网格与 `video` 网格共用 `LibraryScreen.kt`，改这一处等于同时动书籍与视频入口，提交时在红线申报里写明。

- **负责人真机走查（2026-10-04 03:07–03:12，Pad 5 `43af8627`，master `8b9e5a4`）**：长按进入多选（「已选 1 项」+ 实心勾选）/ 全选 =「已选 124 项」（已加载全部）→ 取消全选 / 选 3 首「播放」→ 首曲开始播（`media_session` state=3、UI「正在播放」，按列表序入队）/ 纯服务器条目「删除」**置灰**（删除仅本地）/「×」退出恢复列表 / 0 FATAL·ANR。未覆盖：批量收藏服务器回读、批量下载入队、歌单「从歌单移除」、连播听感（留人工抽查）。**视频 / 书籍未交付** → 随后 W58b 补齐。
### W57 下载体验：设置手动输入 + 网络策略 + 下载页缩略图（2026-10-04，分支 `feature/w57-download-ux`，起点 master `3518dca`）

- **范围（用户 2026-10-04 拍板五条，决策 D65）**：①图片缓存默认 50 MB；②同时下载数手动输入（1–8，默认 2，越界钳制）；③新增下载限速（0–100 MB/s，0 = 不限速，引擎真实限速）；④「仅 Wi-Fi 下载」不看系统计费标记（`hasTransport(WIFI/ETHERNET)` 即允许，修复家庭 Wi-Fi 被判计费时永远等待）；⑤下载页缩略图任何网络下都显示（本地优先，无图类型占位）。
- **缩略图根因（真机复现，Pad 5 `43af8627` 01:48–01:56）**：等待网络的电影 / 剧集条目均无图——电影从未落盘自身封面（`persistItemSnapshot` Movie 分支不调度图片 worker + `videoImageUri` 只查本地）；剧集只落节目 / 季海报、自身封面未落盘且进行中无层级归属被当电影容器渲染。`ImagesDownloaderWorker` 链路本身正常。
- **落点**：`settings`（`AppPreferences.kt` **红线，已申报**：缓存默认 50 + 新键；`PreferenceIntInput.valueRange`；`SettingsViewModel` 两行接线）、`core`（`DownloadNetworkRules` / `DownloadSpeedLimitRules` / `DownloadThrottle` / `DownloadHttpEngine` 节流 / `DownloaderImpl` 网络策略 + 入队落图 / `ImagesDownloaderWorker` 非法地址兜底）、`modes:film`（`DownloadArtworkRules` + `DownloadsViewModel` 本地优先）。零新增配色 / 字体 / 位图；新增文案三语言。
- [x] **①-③设置**：并发 99 → 收敛 8、限速 500 → 收敛 100 MB/s、缓存默认 50 MB（真机实测）。
- [x] **④网络策略**：计费 Wi-Fi + 仅 Wi-Fi 开，单集入队立即下载（14.7 MB → 115 MB，1.2–2.3 MB/s），不再等待。
- [x] **⑤缩略图**：入队数秒后下载页显示剧集海报（`files/images` 落 episode/season/show 三目录）；飞行模式下仍显示；关飞行自动续传（115 → 119 MB）。
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **599 项 / 0 失败 0 错误**（app 136 / core 46 / data 45 / player:local 105 / film 40 / book 113 / music 114）、含 `player:core` 全量 **606 项 / 0 失败**；新增单测 16 项。
- [x] **真机（Pad 5 `43af8627`，2026-10-04 02:11–02:16；device-lock 已写释放与结论）**：全部通过、0 FATAL / 0 ANR；测试下载已删除、偏好还原、服务器只读。

- **负责人合并复核（2026-10-04 02:28–02:31，master `00f2408`）**：设置三项（缓存 50 MB / 并发 2 手动输入 / 限速 0 MB/s）；计费 Wi-Fi 下单集入队「正在下载…」立即开始（2.78–2.87 MB/s）+ 下载页剧集海报缩略图；删除测试下载后 0 进行中；0 FATAL / ANR。

### W56 交互修正：设置账号卡并入 / 顶层图标回主页 / 迷你条 × / 全屏去歌词按钮（2026-10-04，分支 `feature/w56-interaction-fixes`，起点 master `2ed356f`）

- **范围（用户 2026-10-04 拍板四项）**：①设置账号卡并入「账号与服务器」组首行；②音乐迷你播放条加「关闭面板」×（停播 + 收起，队列存档保留）；③全屏播放页去歌词按钮（点歌词行 / 左滑保留，迷你条「词」保留）；④顶层图标统一「回对应主页」（含音乐覆盖层收起）。音乐侧细节见 `MUSIC_PLAN` §2.15 / §5.16。
- **落点**：`app:phone` `NavigationRoot.kt`（**红线，已申报**：`navigateTopLevel` 三态判定 + 音乐覆盖层信号接线）、新 `presentation/navigation/TopLevelNavigation.kt`；`SettingsScreen.kt` / `SettingsGroupCard.kt`（`header` 组内插槽）；`modes:music` `MusicModeScreen.kt`（迷你条 × + `reselectSignal` / `onOverlayOpenChange`）、`MusicModeViewModel.kt`（`dismissNowPlayingBar()`）、`MusicNowPlayingScreen.kt`（六键 → 五键）、新 `MusicMiniBarRules.kt`。
- **零改动**：`AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local` / `modes:film` / `modes:book` / `core` 全零改动；无新增配色 / 字体 / 位图 / 字符串资源。
- [x] **①设置账号卡并入**：`SettingsGroupCard(header = ...)` 组内首行插槽；账号卡从 LazyColumn 顶部移除；点击仍进用户管理、信息不变。
- [x] **②迷你条 ×**：`CinefinIconButton` + `ic_close`（「关闭面板」）；活动会话 `stop()`（停播 + 清内存队列）、恢复态只丢展示快照；队列存档只写不清。
- [x] **③全屏去歌词按钮**：`PlayerActionRow` 五键；`LyricsPreview` 点击 / 左滑手势 / 迷你条「词」不回归。
- [x] **④顶层图标回主页**：纯函数 `topLevelTapAction` + `musicOverlayReselectSignal` / `musicInnerPageOpen` 接线（页内二级层 = 全屏覆盖层或专辑等详情）。
- [x] **单测**：`TopLevelNavigationTest` 4 项（app）+ `MusicMiniBarRulesTest` 3 项（music）= 净增 7。
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 任务 `--rerun` **583 项 / 0 失败 0 错误**（app 130 → 134 / music 111 → 114）；含 `:player:core:testDebugUnitTest` 全量 **590 项 / 0 失败**。
- [x] **真机（Pad 5 主 + K60 抽验，2026-10-04 01:36–01:43）**：四项全部通过（设置账号卡组内首行 / 迷你条 × 停播收起 + 重选恢复 / 全屏五键无歌词入口 + 点歌词行进歌词页 / 全屏·歌词页·艺术家详情·视频库内容页点对应图标均回主页）；双机 0 FATAL / ANR。详见 `device-lock.md`。

### W53B 侧栏「本地媒体库」子分组 + 本地库卡 16:9 缩略图（2026-10-03，分支 `feature/w53b-local-libs`，起点 master `9fcea57`）

- **开工学习（强制）**：读 `UI_DESIGN_SYSTEM` §2（色彩 / 三色纪律 / 禁止清单）、§4（间距刻度 / 平板与手机栅格 / 形态分级）、§5（圆角 / 描边 / 无投影）、`UI_PLAN` D52–D55 / D59 + 踩坑库（8 / 17 / 26 / 32 / 46 / 48）与侧栏、抽屉、本地库现状代码后动手（决策 D60）。
- **落点**：`NavigationRoot.kt`（**红线，已申报**：侧轨二级子分组渲染、抽屉三切片分组、`openLocalLibrary` 落点、导航变化只读刷新）、`DrawerViewModel`（新增 `localLibraries` 只读行 + `refreshLocalLibraries()`）、`NavigationIa.kt`（`SidebarLocalLibrary` / `sidebarLocalLibraries()` / `libraryChildCountVisible()` / 68dp 尺寸链纯函数）、`presentation/local/LocalLibraryScreens.kt`（本地库卡 16:9 缩略图 + 详情页设置变化回调 + `iconRes()` 提为 internal）、core `CinefinDrawer`（`CinefinDrawerGroup.dividerAboveTitle` 槽位）。
- **不改 / 零改动**：`AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local` / `modes:*` 全零改动；无新增配色 / 字体 / 位图 / 字符串资源。
- [x] **B 侧栏「本地媒体库」子分组**：「媒体库」组内、服务器库之后 → 分隔线（既有 `CinefinRailGroupDivider`）+ 标题（`LabelSmall` + 三级文字）+ 每库一行（类型图标 + 名称 + 项目数）；侧轨（展开态）与抽屉（独立带标题分组 + 标题上方细分隔线）两处生效；遵循库级「在媒体库显示」、0 库整组隐藏、点击进 `LocalLibraryRoute`、当前库高亮。
- [x] **B 168dp 取舍**：`libraryChildCountVisible()` + `TextMeasurer` 实测宽度，「完整名称优先」——名称 + 项目数放不下就省略项目数（服务库 4 字名不再截断）；抽屉 320dp 保持不变。
- [x] **C 本地库卡**：媒体库页本地库卡保持紧凑行卡，缩略图 40dp 正方形 → **100×56.25dp（16:9）**（`LocalThumbnailTile` 新增 `width` / `height`，默认值等于 `size`，既有调用点零改动）；服务器库卡 16:9 大卡不动。
- [x] **单测**：`SidebarLocalLibraryTest` 6 项（过滤 + 0 库 + 排版三态 + 68dp 尺寸链）。
- [x] **门禁**：根 `assembleDebug`（含 TV：`app:phone` + `app:tv`）BUILD SUCCESSFUL；`ktfmtCheck` 全模块通过；7 任务 `--rerun` **565 项 / 0 失败 0 错误**（app 120 / core 37 / data 45 / player:local 105 / film 33 / book 113 / music 112 = 基线 559 + 净增 6）。
- [ ] **真机**：未申请设备窗口（按协作约定先向负责人申报）——清单见 §5 W53B 验收。

### W54-D 首页模块与首页设置收口（2026-10-03，分支 `feature/w54-home-settings`，起点 master `9fcea57`）

- **开工学习（强制）**：读 `UI_DESIGN_SYSTEM` §2/§4/§5、`UI_PLAN`（D39 两段式 / D52–D59 / 踩坑库）后动手（决策 D61–D62）。
- **A 首页模块结构**：继续观看 / 继续阅读 / 继续收听三条独立走廊（`getResumeItems` 按 MOVIE·EPISODE / BOOK / AUDIO 分别取；后两条失败只隐藏走廊）；接下来保留；「最近添加」拆视频 / 书籍 / 音乐三条（视频保留原海报墙，书籍 / 音乐同款竖版海报墙、各自开关）；「最新 · <库名>」逐库一条、逐库开关默认全开、有内容才显示、顺序可调；删除旧「首页媒体库」单库过滤与 `take(3)` 上限（bug ②根因）。
- **B 设置页**：客户端设置 → 界面 → 首页 = 模块开关组（继续观看 / 继续阅读 / 继续收听 / 接下来 / 最近添加×3）+「首页媒体库」组（每库一行：在首页显示 + 默认分页；末行媒体库顺序上下调整）；「媒体库」子页的「首页媒体库」单选下线。
- **C bug 修复**：①`HomeAction.OnLibraryClick` 统一 dispatch → `homeViewAllRoute()` → `LibraryRoute` + 默认「最近添加」倒序（`LibraryRoute` 新增可选排序参数，只作本次进入初始值）；②见 A。
- **落点**：`settings` `HomeLibrarySettings`（编解码 + 顺序 / 开关 / 分页纯规则）/ `PreferenceHomeLibrary` / `PreferenceHomeLibraryOrder` / `AppPreferences`（删 2 键、加 8 键）/ `SettingsViewModel`；`app:phone` `HomeScreen` / `SettingsHomeLibraryCard` / `SettingsGroupCard` / `NavigationIa` / `NavigationRoot` / `LibraryScreen`；`modes:film` `HomeViewModel` / `HomeState` / `LibraryViewModel`；`data` `getResumeItems` 增加 `includeItemTypes`。
- **单测**：`HomeLibrarySettingsTest` 8 项（媒体库顺序 / 每库开关映射 / 默认分页映射与回落 / 编解码）+ `HomeViewAllRouteTest` 2 项（「全部」落点）。
- **门禁**：根 `assembleDebug`（含 TV）BUILD SUCCESSFUL；`ktfmtCheck` 通过；7 任务 `--rerun` **569 项 / 0 失败 0 错误**（app 124 / core 37 / data 45 / player:local 105 / film 33 / book 113 / music 112 = 基线 559 + 净增 10）。
- **红线 / 越界**：`AppPreferences.kt`（删 `pref_ui_home_library_id`、`home_latest`；加 8 键，已申报）；`NavigationRoot.kt`（`LibraryRoute` 加可选 `sortBy/sortOrder` + 「全部」接线，已申报）；`AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 未动。
- **未做 / 交接**：①真机走查待负责人设备窗口（清单：三条继续走廊、最近添加×3、逐库开关与顺序、默认分页落盘、首页「全部」进库 + 最近添加排序）；②「默认分页」只落盘，未接进库页 tabs 消费；③音乐库「全部」走既有兜底进音乐模式（不能选中被点库，见 D62 已知边界）。

### W54-B 库内容页头部共享组件（2026-10-03，分支 `feature/w54-lib-header`，起点 master `ce58847`）

- **开工学习（强制）**：读 `PROJECT_PLAN` §1–§5、`UI_DESIGN_SYSTEM` §2/§4/§5、`UI_PLAN`（D39 两段式 / D52–D55 / 踩坑库）后动手（决策 D56）。
- **落点**：`LibraryScreen.kt`（本波单写者）+ `components/LibraryContentHeader.kt` + `components/SortByPanel.kt`（替换 `SortByDialog.kt`）+ `modes:film` `LibraryHeaderRules.kt` / `LibraryState` / `LibraryAction` / `LibraryViewModel` + `data` 仓库 5 个新方法（计数 / 库内建议 / 即将播出 / 类型 / 制片发行商）与 paging 过滤参数（`filters` / `genres` / `studios`）透传（`JellyfinApi` 补 `genresApi` / `studiosApi` 两个只读入口）。
- **不改**：`presentation/video/VideoScreen.kt`、`presentation/film/BookshelfScreen.kt`（W54-C 单写者）；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml`（红线未动）。
- **E 已知缺陷（家庭视频缩略图）**：只读定位根因（踩坑 74）→ 一行回退修复（`ItemPoster`：`backdrop ?: primary`）。
- **单测**：`LibraryHeaderRulesTest` 13 项（tab 出现规则 / 工具行动作 / 筛选映射 / 计数文案 / 排序映射 / 库类型映射）。
- **门禁**：根 `assembleDebug`（含 TV）BUILD SUCCESSFUL；`ktfmtCheck` 通过；7 任务 `--rerun` **544 项 / 0 失败 0 错误**（app 105 / core 37 / data 45 / player:local 105 / film 27 / book 113 / music 112 = 基线 531 + 净增 13）。
- **未做 / 交接**：真机走查（未申请设备窗口）——tabs 切换 / 计数 / 列表视图 / 排序与筛选面板 / 下拉刷新 / 家庭视频缩略图六项待 Pad 5 主 + K60 抽验。

### W54-C 视频页库选择 + 书架选库 + 顶栏收藏 / 睡眠入口（2026-10-03，分支 `feature/w54-video-page`，起点 master `ce58847`）

- **决策（D57–D58，用户 2026-10-03 确认）**：①视频页两模式选库（库卡 = 过滤；聚合 = 只出所选库）并持久化；②书架同样支持选书籍库（复用「书架媒体库」键）；③顶栏收藏 / 睡眠图标（睡眠本体 = W55）；④「库卡中间大字」先只读核对、不擅自改。
- [x] **A 视频页库选择**：`VideoState` 拆 `allLibraries`（菜单来源）/ `libraries`（过滤后，库卡网格与聚合流都用）/ `selectedLibraryId`；顶栏 `LibrarySelectorChip`（「全部库」+ 各视频库 + 项目数，菜单行尾 `ic_check`）；`selectLibrary` 写 `pref_ui_video_library_id` → 偏好监听即时重算（库卡过滤 / 聚合流重建，不发额外网络请求）；库被删 / 偏好值损坏 → 回落「全部库」
- [x] **B 书架选书库**：`BookshelfViewModel` 新增 `librarySelection`（「自动」+ 各 books 库）与偏好监听（改「书架媒体库」即时重解析）；`BookshelfScreen` 顶栏（Ready 态经 `LibraryScreen.topBarActions` 注入、占位态自绘）显示同一套 chip；≥2 个书籍库才出现入口；临时库视图只显示路由指定的库（无选择器，收藏仍指向它）
- [x] **C 顶栏收藏 / 睡眠**：收藏 = 当前库 `markAsFavorite` / `unmarkAsFavorite` + 乐观更新（`FindroidCollection.favorite` 从 `UserData.isFavorite` 映射补齐）；睡眠 = 矢量 `ic_video_sleep` + 占位对话框（W55 接本体）；书架无睡眠入口
- [x] **D 「库卡中间大字」只读核对（未改代码）**：证据 = 服务器端库封面 PNG 自带库名大字（见 D57 ③）；建议口径 3 条已上报
- [x] **单测**：`VideoLibrarySelectionTest` 5 项（偏好映射 / 过滤 / 库删除回落）+ `BookshelfPickTest` +3 项（books 过滤 / 选中保留 / 失效回落「自动」）
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 任务 `--rerun` **539 项 0 失败**（app 113 / core 37 / data 45 / player:local 105 / film 14 / book 113 / music 112）
- [ ] **真机**：待设备窗口（清单：两模式选库即时生效 + 重启保留、书架多书库切换、收藏点亮 / 熄灭、睡眠占位、0 FATAL/ANR）
- **红线 / 越界**：`AppPreferences.kt` 新增 `pref_ui_video_library_id`（申报）；`LibraryScreen.kt` 新增 `topBarActions` 槽位（越界 1 处，申报：B 的顶栏落点只有它）；`NavigationRoot.kt` 未动


### W51 下载粒度 + 详情页动作排重绘 + 侧栏角标 + 三项下载设置 UI（2026-10-03，分支 `feature/w51-download-granularity`，起点 master `ce58847`）

- **决策（D59，用户 2026-10-03 确认）**：①三层详情页同排「下载 / 已播放 / 喜欢」一并重绘；②已播放 / 喜欢可点切换（乐观更新 + 失败回滚）；③点击下载三态 Snackbar；④侧轨 + 抽屉下载角标（下载中 + 排队 + 暂停，0 隐藏）；⑤设置下载子页三项（仅 Wi-Fi 默认开 / 并发 1–3 默认 2 / 完成通知默认开）。
- [x] **A 全部完成（下载粒度）**：Show = 下载整剧（点击时按需拉取剧集，`ShowViewModel.loadDownloadTargets`）/ Season = 下载全季（复用页面已加载剧集）/ Episode = 下载本集；批量确认框默认「仅补齐缺失集」（已下载 / 已在队列跳过）+ 默认单次上限 100 集（可关）；全部走 W50 自研引擎 `Downloader.downloadItem`（对活动队列幂等）。
- [x] **纯函数**：`DetailDownloadRules`（单集三态 / 容器聚合 / 批量选择 / 去重 / 上限）+ 6 项 film 单测；`DownloadTaskRules.isActiveQueueStatus` + 1 项 app 单测。
- [x] **B 动作排重绘**：`ItemButtonsBar` 两行式（播放行 + 三标签键）；三态标签 / 选中态 / Lumen 自动着色；`DownloaderCard` 继续承载单集进度与取消 / 重试；取消 / 删除确认对话框改 Lumen 面板。
- [x] **C Snackbar**：`CinefinSnackbarHost`（§8.12）+ `downloadEventMessage`（三态 / 批量 / 失败）；Show / Season / Episode 三层详情页接入。
- [x] **D 角标**：`CinefinNavItem.badge` + `CinefinCountBadge`（侧轨 / 抽屉共用槽位，底栏无下载项）；`DownloadBadgeViewModel` + `Downloader.activeItemIds()` 只读快照（不对账 / 不唤醒引擎），`NavigationRoot` 前台 2s 轮询。
- [x] **E 设置 UI**：`PreferenceSwitch.negateValue`（仅 Wi-Fi 对 `pref_downloads_mobile_data` 取反绑定）+ `PreferenceIntSelect` / `SettingsIntSelectCard`（并发 1–3）+ 完成通知开关；`AppPreferences.kt` 零改动。
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 任务 `--rerun` **538 项 0 失败 0 错误**（app 106 / core 37 / data 45 / player:local 105 / film 20 / book 113 / music 112）。
- [ ] **真机验收**：待负责人设备窗口（清单见 `DOWNLOAD_PLAN` §20.5）。
- [x] **W51b 真机缺陷修复（2026-10-03，分支 `feature/w51b-show-download-fix`，起点 master `9fcea57`）**：真机拦下「Show 整剧 → Snackbar『没有可下载的剧集』」——根因 = 取集只请求 `Fields=Overview`，`MediaSources` / `CanDownload` 按需字段缺失 → `canDownload=false` + 空 `sources` 被过滤成 0 条；修法 = 取集统一 `DetailDownloadRules.EPISODE_FETCH_FIELDS`（Overview + CanDownload + MediaSources）、目标筛选改「有媒体源」硬条件；同时修**批量入队竞态**（条目快照先于队列行落库 → 消除「文件写入失败 / 任务对应的媒体条目缺失」）。纯函数 +2 单测；门禁 **561 项 0 失败**；真机 Pad 5 复验：Show「将加入 12 集」→ Snackbar「已加入下载队列 · 12 集」、Season「将加入 11 集」→「· 11 集」、角标 12 → 删除后 0、单集回归正常、0 FATAL/ANR。详见 `DOWNLOAD_PLAN` §20.7。

### W53 实机 Bug A/B 修复（2026-10-03，同分支；用户实机反馈，D54）

**W53 追加：临时库视图（用户 2026-10-03 确认，本会话落实 A 全部 6 条；B / C 见下「交接」）**

- **落点**：侧栏 / 抽屉点服务器库 → 对应模式页直显该库（新目的地 `TemporaryLibraryRoute(libraryId, libraryName, kind, libraryType)`）：视频库（movies / tvshows / homevideos / 混合「其他」）→ 视频页**该库条目网格**（不走库卡总览）；音乐库 → 音乐模式该库；书籍库 → 书架该库；Playlists 等其余类型 → 通用库内容页（原行为）。归属判定抽 `temporaryLibraryKindOf` + `libraryEntryRoute` 纯函数（单测）。
- **返回语义**：返回键先退出临时库、回该模式页默认库（视频 / 书架 / 音乐三页各挂 `BackHandler`；音乐页在详情打开时让位给详情返回）；「返回默认 ×」胶囊（新增 core `CinefinBackToDefaultChip`，Prism / Lumen 自适应）一键同效；点其它底栏 / 侧栏入口同样回默认（D54 ①「入口回落根页」保证）。
- **临时态不写偏好**：库 id / 名称只走路由参数（进程内），不碰 `AppPreferences`。
- **顶栏**：视频页 = 真实库名 + 「类型 · 共 N 个项目」；音乐页 = 库名 + 「类型 · 当前 Tab 计数」；书架 = 库名 + 项目数。
- **真机复验（2026-10-03 21:11–21:20，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，device-lock 已写释放与结论）**：K60 抽屉「书籍3」→「书籍3 / 书籍库 · 共 8 本」+ 胶囊，返回键 →「书架」默认视图；「音乐测试」→「音乐测试 / 音乐库 · 共 0 张专辑」+ 胶囊，返回 →「音乐 / 共 105 张专辑」；「电影」→ 视频页该库**条目网格**（「电影 / 电影库 · 共 7 个项目」）+ 胶囊，返回 → 默认视频页；「Playlists」→ 通用库内容页（无胶囊）；Pad 侧轨「动漫」→「动漫 / 剧集库 · 共 3 个项目」+ 胶囊，返回 → 默认视频页。0 FATAL / ANR；临时态未写偏好。真机拦下并修：书架临时视图标题原显示「书架」（现为真实库名 + 类型前缀）。
- **交接（未做，下一步）**：①**B 侧栏本地库分组**——「媒体库」组内服务器库之后加「本地媒体库」子分组标题 + 分隔，列本地库（名称 + 项目数），遵循库级「在媒体库显示」开关、0 库整组隐藏、点击进 `LocalLibraryRoute`（需 `DrawerViewModel` 读本地库 + 侧轨 / 抽屉两处渲染）；②**C 本地库卡尺寸**——媒体库页本地库卡缩略图放大到 16:9（≈96–104dp 宽），服务器库卡保持 16:9 大卡（落点 `presentation/local` 的本地库卡组件）。

- **Bug A（手机底栏「视频」切不回来）**：从抽屉选库 / 从视频页进库后再点底栏「视频」停在库内容页。根因 = `navigateTopLevel` 的 `popUpTo(start){saveState}` + `restoreState` 会把「Tab 根 + 子页面」整栈恢复。修法：导航后 `popBackStack(route, inclusive = false)` 统一弹回入口根页（已是根页为 no-op；根页滚动 / 状态仍由 `saveState` 保留）。
- **Bug B2（侧栏选「书籍3」页里仍是「书籍」）**：库入口 = 同一目的地 + 不同参数，`restoreState` 按目的地 id 恢复旧条目、把新参数顶掉（踩坑 30 同类）。修法：`openLibrary` 改 `popUpTo(start)`（不回存 / 不恢复）+ `launchSingleTop`，按点击的库新建条目。
- **Bug B1（侧栏选「音乐测试」页里仍是「音乐」）**：旧 `libraryEntryRoute` 把所有 Music 类型映射到 `MusicModeRoute`（忽略 libraryId），音乐模式只读「客户端设置 → 音乐库」。修法：新增独立目的地 `MusicLibraryRoute(libraryId, libraryName)`（音乐 Tab 仍是 `MusicModeRoute`，两者分开 → 参数不会被 `restoreState` 互相覆盖），`MusicModeViewModel` 从 SavedStateHandle 取路由库（路由 > 偏好 > 自动），顶栏显示库名；`resolveMusicLibraryId` 纯函数 + 单测。
- **库子项可区分（D54 ④）**：侧栏 / 抽屉库子项右侧显示项目数（服务器 `ChildCount`，无值不占位）。
- [x] **单测**：`LibraryEntryRouteTest` 4 项（app）+ `MusicLibrarySelectionTest` 3 项（music），共 +7
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 任务 `--rerun` **512 项 0 失败**（app 94 / core 37 / data 45 / player:local 105 / film 6 / book 113 / music 112）
- [x] **真机复验（Pad 5 主 + K60 抽验，20:46–20:56，device-lock 已写释放与结论）**：Bug A 两条路径（抽屉选库 / 视频页进库 → 点底栏「视频」均回视频页）；B2 双机「书籍3」命中（K60 标题「书籍3 / 共 8 个项目」、Pad 同）；B1 双机「音乐测试」命中（K60「音乐测试 / 共 0 张专辑」、Pad 同），随后点底栏「音乐」=「音乐 / 共 105 张专辑」（Tab 不被库入口参数顶掉）；抽屉与侧轨库子项显示「N 项」（抽屉完整；侧轨 168dp 下 4 字库名截断，待用户确认呈现）；0 FATAL / ANR

### W56 视频海报状态徽标（未看数量 / 已看打勾，官方口径）（2026-10-03，分支 `feature/w56-unwatched-badges`，起点 master `97e1cb6`）

- **决策（D55，用户 2026-10-03 确认）**：①Series / Season / 文件夹封面角标 = 未看条目数（`>0` 才显示，`>99` 显示 `99+`）；②Movie / Episode 已看 → 打勾徽标、未看 → 不加角标；③库卡保持「项目数」信息、原未看徽标位改正；④规则抽纯函数（类型 → 数字 / 打勾 / 无）+ 单测。
- [x] **A1 规则纯函数**：`PosterStatusBadge.kt` —— `posterStatusBadge()`（类型 + 用户数据 → `UnplayedCount` / `Played` / `None`）、`unplayedItemCountText()`（`>99` → `99+`）、`ItemStatusBadge(item)` 组合渲染入口
- [x] **A2 卡片统一接入**：`PosterItemCard`（补已看打勾）/ `ItemCard` / `LandscapeItemCard` / `EpisodeCard` —— 原 `if (item.played) PlayedBadge()` 会给「已看完的整剧」误打勾，统一改走规则；`ItemCountBadge` 文案走 `99+` 收敛
- [x] **A3 库卡改正**：`LibraryEntryCard` 删除右上角未看徽标位（死代码：`FindroidCollection.unplayedItemCount` 恒 null、服务器库视图也不返回该字段），库卡第②行仍是「共 N 个项目」
- [x] **A4 数据核验（只读接口探针，服务器 10.11.8，用户 zhangwenkang）**：媒体库网格 / 搜索 / 聚合（`/Items`）、Seasons、Latest、Suggestions 对 Series / Season 返回未看数（实测 13 / 48 / 40 / 370…），显式 `enableUserData=true` 与默认结果逐条一致；Movie / Episode / `/Views` 恒空；NextUp / Resume 只有 `played` → **查询保持现状，无需显式开启用户数据**
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 7 任务 `--rerun` **523 项 / 0 失败**（W52+W53 基线 516 + W56 净增 7；app 100 / core 37 / data 45 / player:local 105 / film 14 / book 113 / music 109）
- [x] **真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-03 21:21–21:37）**：剧集未看数与服务器逐条一致（13 / 10 / 12 / 48 / 13 / 12）、`99+`（银魂 370）、已看打勾与未看不加角标、库网格·首页·搜索·视频页四处一致、0 FATAL·ANR —— 见 §5 W56 验收

### W53 视频入口 + 视频模式页（2026-10-03，分支 `feature/w53-video-entry`，起点 master `1222bef`、rebase 到 W50 `8f3ba0e`）

- **决策（D52–D53，用户 2026-10-03 确认）**：①「视频」顶层入口与音乐 / 书架同级（门控 = 服务器有 movies / tvshows 库）；②侧栏 / 抽屉顺序 首页 → 视频 → 音乐 → 书架 → 媒体库 → 下载；③手机底栏改 首页 / 视频 / 音乐 / 书架（媒体库移出底栏）；④客户端设置「侧栏显示」加「视频」开关；⑤视频模式页两种显示方式（库卡列表默认 / 聚合列表），设置里可切换。
- [x] **A1 导航 IA**：`NavEntryKey.Video` + `navEntryKeys(hasVideoLibrary)` 门控（未就绪保持可见）+ 顺序；`visibleRailKeys` 加视频开关；`bottomNavKeys` = 首页 / 视频 / 音乐 / 书架；`railGroupOf(Video) = Content`
- [x] **A2 客户端设置**：AppPreferences 新增 `pref_ui_sidebar_show_video`（默认 true，已申报）；`SidebarVisibility.video` + `readSidebarVisibility`；「侧栏显示」子页新增「视频」开关（插在首页之后，与既有开关同机制）
- [x] **B1 视频模式页**：`VideoScreen` / `VideoViewModel` / `VideoAggregatePagingSource`（app/phone `presentation/video`）
  - 库卡列表：`LibraryEntryCard` 16:9 网格（列宽 300 / 320 / 380 / 420dp），点卡进 `LibraryRoute`
  - 聚合列表：`ItemCard` 竖版网格 + Paging 3（按库顺序拼接、库内 `DateCreated` 倒序），游标纯函数 + 单测
  - 空态 / 骨架 / 错误：`CinefinEmptyState` + `video_empty_title/message`；`MediaLibrarySkeleton` / `LibraryGridSkeleton` + `LumenSkeletonOverlay`；库与分页各有 `ErrorCard` + 重试
- [x] **B2 设置项**：`pref_ui_video_display_mode`（默认 `cards`，已申报）+ `VideoDisplayMode` 枚举（settings domain models）+「媒体库」子页「视频显示方式」`PreferenceSelect`（新 string-array `video_display_mode`，en / zh-rCN / zh-rTW）
- [x] **C1 导航接线（红线，已申报）**：`VideoRoute` + `showNavigation` + 侧栏 / 抽屉条目（icon 复用 `ic_film`）+ 选中态 + `composable<VideoRoute>`（`ProvideLumen`）+ 顶栏入口走 `onOpenDrawer`（手机 logo / 平板 null）
- [x] **C2 单测**：`NavigationIaTest` 新增 3 项（视频门控 / 未就绪可见 / 侧栏开关过滤）+ 既有顺序断言同步；`VideoAggregateTest` 6 项（库筛选、游标推进 4 种、显示方式取值回退）
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 `--rerun` **505 项 / 0 失败**（W50 基线 496 + W53 净增 9；app 90 / core 37 / data 45 / player:local 105 / film 6 / book 113 / music 109）
- [x] **真机**：Pad 5 `43af8627` 主 + K60 `8e875894` 抽验全部通过（2026-10-03 19:02–19:26，device-lock 已写释放与结论）——见 §5 W53 验收
- [x] **真机修复（离线口径）**：`VideoViewModel` 原先直接注入 `JellyfinRepository` → 离线开关不生效（离线模式下视频页仍显示服务器库，同踩坑 33）→ 改注入 `Provider<JellyfinRepository>`，每次 `load()` 按当前偏好解析；K60 离线模式复验 = 空态「暂无视频库」，退出离线模式后库卡即时恢复

### W46 导航与设置细节（侧栏 / 顶栏 / 平板抽屉 / 音乐库归位）（2026-10-03，分支 `feature/w46-nav-settings`，起点 master `7fb250d`）

- **决策（D48–D50，用户 2026-10-03 逐项确认）**：①侧栏 82% → **74%**、展开 150 → **168dp**、`服务器控制台 / 媒体资料管理器` → `控制台 / 资料管理`；②手机四个一级页顶栏统一 app 图标（24dp）；③平板取消抽屉（边缘手势关闭 + 顶栏不再给入口）；④设置「音乐」子页下线、音乐库选择并入「媒体库」子页。
- [x] **A1 透明度**：新增 `CinefinTokens.RailTranslucency = 0.74f`（侧轨 + 抽屉；底栏 82% 不动），`CinefinSideRail` / `CinefinSideNavigation` / `CinefinModalDrawer` 三处换 token，仍保持「先铺页底衬底再叠面板」；选中态容器 `panelElevated.copy(alpha = 0.74f)` 同步
- [x] **A2 宽度**：展开 150 → **168dp**（`CinefinSideRail` 与 app `CinefinSideNavigation` 两处；折叠 72dp / 行高 48dp / 图标 24dp 不变）
- [x] **A3 文案**：`title_console` = 控制台 / Console、`title_metadata_manager` = 资料管理 / Metadata（默认 + zh-rCN + zh-rTW；只改侧栏 / 抽屉，路由与设置项标题不动）
- [x] **B 顶栏统一**：`CinefinPageTopBar` 的 `onOpenDrawer` 分支 `ic_menu` → `ic_logo`（24dp、`content-desc="打开侧栏"`、`tint = Color.Unspecified`）；首页 `HomeTopBar` logo 26 → 24dp；二级页（返回键 / 下载 / 客户端设置）不变
- [x] **C 平板取消抽屉**：`openDrawer` 按形态取值（非 Compact = null）、`gesturesEnabled = showNavigation && compactNavigation`、新增 `LaunchedEffect(compactNavigation)` 在切回平板时收抽屉；导航条目集合 / 排序 / 门控与手机抽屉语义零改动
- [x] **D 设置归位**：音乐库选择移入「媒体库」子页（首页 / 书架 / 音乐三项并列）、删除「音乐」子页入口与两条无用资源、`SETTINGS_GROUP_LAYOUT` 与 `SettingsGroupLayoutTest` 同步
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 `--rerun` **471 项 / 0 失败**（app 79 / core 25 / data 42 / player:local 104 / film 6 / book 106 / music 109）
- [ ] **真机**：待负责人统一调度（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）——见 §5 W46 验收

### W45 首页本地媒体卡片化 + 本地缩略图接入（2026-10-03，分支 `feature/w45-local-covers`，起点 master `d11f80d`）

- **决策（D47，用户 2026-10-03 反馈「纯文字卡与首页不搭」）**：①**卡片化** = 封面（库内首项缩略图）+ 库名 +「N 项 · 类型」+ 右上类型角标；`rememberLandscapeCardWidth()` + `aspectRatio(16f/9f)` + `LumenCardFrame`（默认 `CinefinShapes.Md`）+ `lumenEntrance(index)`，横排间距 `rememberGridGutter()`、`SectionHeader` 底部 Space4——与「继续观看」**同宽同高同 pitch**；无封面回退既有类型图标（`surfaceContainerHigh` 底 + 32dp 图标）。②**三处使用点** = 媒体库总览库卡 40dp 缩略图（替换类型图标磁贴）/ 本地条目列表行 `CinefinListRow.leading` / 本地库详情头部 140dp 通栏封面。③**载入** = `localCoverModel()` 把绝对路径转 `File`（Coil 3 → `file://`）、`content://` 原样传字符串；缓存沿用全局 `ImageLoader`，不新增缓存层。④**不做** = 不新增配色 / 字体 / 位图，不动音乐链路（内嵌标签 → 同目录封面），不改库卡点击行为；首项选取与缩略图口径见 `DOWNLOAD_PLAN` D32–D36
- [x] **A1 首页本地库卡改版**：`HomeLocalMediaSection` 从「纯文字卡（200dp 宽 + 库名 + W39 副标题）」改为首页同语言卡片——封面（库内首项缩略图）+ 库名 +「N 项 · 类型」+ 右上类型角标（`BaseBadge` 中性徽标）；尺寸 / 圆角 / 间距与「继续观看」横排一致（`rememberLandscapeCardWidth()` + `aspectRatio(16/9)` + `LumenCardFrame`(默认 `CinefinShapes.Md`) + `rememberGridGutter()`），`SectionHeader` 底部间距 Space3 → Space4 对齐走廊节奏
- [x] **A2 缩略图接入三处**：媒体库总览库卡（40dp `corner-xs` 缩略图替换原类型图标磁贴，无图回退类型图标）/ 本地条目列表行（`CinefinListRow.leading` = 缩略图，行可见时按需生成）/ 本地库详情头部（140dp 通栏封面，无图回退类型图标）
- [x] **A3 懒生成接线**：`LocalLibraryViewModel.loadCover` / `LocalLibraryDetailViewModel.loadHeaderCover` / `loadThumbnail` 去重请求；卡片 / 行的 `LaunchedEffect` 键含条目数（新建库扫描完成后自动重试）；`localCoverModel()` 把本地文件绝对路径转 `File` 交给 Coil 3（`file://` → `FileUriFetcher`），`content://`（同目录封面）原样传递
- [x] **A4 空库不请求**：库卡 / 详情头部 `itemCount <= 0` 时不发起请求（踩坑 67）
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 `--rerun` **471 项 / 0 失败**（data 新增 `LocalThumbnailRulesTest` 9 项：缓存 / 失败标记路径、缩放与旋转折算、首项顺序与限额、CBZ 第一张图过滤）
- [x] **真机验收**：见 §5 W45 验收（四条封面链路像素取证 + 首页卡 16:9 同宽同高 + 125 项滚动 gfxinfo）

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
- [x] **品牌波（用户 2026-10-03 决定：放在全部功能开发测试完成后、发布前执行）**：应用改名 **「极光幕 / Aurorama」**，**`applicationId` 改为 `io.github.zhangwenkang.aurorama`**（用户 2026-10-03 定；已同步 adb 脚本 / `tools/*` / 文档引用，仓库无自建 FileProvider authority，接受双机重装重登；**W41 已完成并双机验收，见 D45 与 §5 W41**）+ 自适应矢量图标（**方向 1「极光帘幕」**：极光丝带自左上流下 + 底部幕布地平线，极光青 `#5CE1D2` → 辅光蓝 `#7CC4FF`；深色 / 单色 / 自适应三套）+ 打包字体（MiSans / Literata 走官方渠道；其他字体先调研授权，不允许则用开源替代）与子集化；**约定：新增字符串一律引用 `app_name` 资源，不硬编码应用名**（保证发布前改名一次生效）；执行时补一轮 UI 布局回归（字体度量可能影响排版；W38 字体布局项已随 W41 双机走查覆盖：首页 / 媒体库 / 客户端设置 / 关于 / 播放页正常）。

### W43 搜索「媒体库与本地」（2026-10-03，分支 `feature/w43-search-local`，起点 master `7a9f393`）

- [x] **A1 双来源合并**：`SearchViewModel` 注入 `LocalLibraryRepository`，`async` 并行查服务器 + 本地；服务器失败沿用旧行为（保留旧结果 + `loading=false`），本地维度单独兜底（失败转空分区，不拖垮服务器结果）
- [x] **A2 分区 + 计数 + 徽标**：`SearchState(serverItems, localItems)`；`FilmSearchBar` 同一 `LazyVerticalGrid` 承载（分区标题全宽 item + 服务器海报网格 + 本地整行 item）；无命中的分区不渲染
- [x] **A3 本地行与打开链路**：类型图标 + 名称 + 元数据（`库名 · 文件夹` → 音乐时长 → 大小）+「本地」徽标；点击 → 视频 `PlayerActivity` / 书籍 `openReader(localUri)` / 音乐 `LocalMusicPlayer`（同文件夹队列）→ 音乐起播跳音乐 Tab
- [x] **A4 文案**：占位符 / 标题「搜索媒体库与本地」（`values` + `values-zh-rCN`）；新增 `search_section_server` / `search_section_local` / `search_empty_query`
- [x] **A5 空态 / 加载**：有查询无结果 →「未找到「xxx」」；本地无库 / 无命中时本地分区不出现；沿用既有防抖（50–300ms）+ Job 取消 + 输入框 loading 指示
- [x] **B 纯函数 + 单测**：`LocalLibrarySearch`（`data`：归一化 / 匹配 / 排序）+ `LocalLibrarySearchTest` 6 项（含 5000 条耗时实测 3.80 ms / 预算 250 ms）
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 `--rerun` **450 项 / 0 失败**（app 79 / core 18 / data 33（新增 6）/ player 104 / film 6 / book 106 / music 104）
- [x] **真机**：Pad 5 主 + K60 抽验（见 §5 W43 验收）

### W38 品牌波 · 第一段（改名 + 自适应图标 + 字体，2026-10-03，分支 `feature/w38-brand-aurorama`）

- [x] **A 改名**：`app_name` 全渠道（默认 `Aurorama` / 新增 `values-en` / `zh-rCN` 极光幕 / `zh-rTW` 極光幕 + debug、staging 变体）；launcher label（phone + TV manifest）、首连向导、抽屉 / 首页顶栏 / 冷启动 / 播放器兜底标题因引用 `CoreR.string.app_name` 自动跟随；setup `welcome` / `welcome_text` 17 语言、core `privacy_policy_notice` 33 语言词面更名并修正格位（fi / cs / et / sl / tr / az）
- [x] **B 图标**：方向 1「极光帘幕」自适应前景 / 背景 + 单色（`anydpi-v33`）+ 深色（`values-night`）+ 应用内 `ic_logo` 同构；全部矢量 XML、无新增位图（详见 D37）
- [x] **C 字体**：授权核验 → 官方源下载 → 子集化 → `CinefinType` 接入（详见 D38）；`assets/licenses/` 落 OFL 全文（**W40 更正**：MiSans 实际允许随 App 内嵌，子集化撞条款②；本条已由 W40 / D38 更正 / D40 取代）
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` + 相关模块单测（计数见 §5 W38 验收）
- [x] **体积**：官方原文件 19.63 MB → 子集 2.91 MB（Noto Sans SC 17.77 → 2.32 MB；Literata 0.96 / 0.90 → 0.37 / 0.36 MB），APK 对照见 §5
- [ ] **D 布局回归真机窗口**：Pad 5（`43af8627`）主 + K60（`8e875894`）抽验——等负责人统一调度
- [x] **W41 第二段（原 W39 命名）**：`applicationId` → `io.github.zhangwenkang.aurorama` + adb / `tools/*` / 文档引用 + `CLIENT_NAME` = Aurorama（2026-10-03 完成，见 D45；仓库无自建 FileProvider）

### W39 媒体库总览改版（2026-10-03，分支 `feature/w39-media-library-polish`）

- [x] **A1 搜索入口收口**：`MediaScreen` 页内常驻 `FilmSearchBar` 下线，只在 `searchExpanded` 时组合（顶栏搜索图标 / 首页搜索入口 → 原有 SearchBar + SearchViewModel 流程）；静态总览页无任何常驻搜索框
- [x] **A2 收藏进顶栏**：整行 `FavoritesCard` 下线，顶栏 `TopBarAction(ic_star)` → 现有收藏页；`MediaScreenLayout` 新增 `onFavoritesClick` 形参（页面动作与 `MediaAction` 解耦）
- [x] **A3 两段式**：本地媒体库（标题行 + 「＋ 新建」紧凑按钮 + 库卡 / 一行空态）→ 服务器媒体库（`SectionHeader` + 16:9 大卡网格）；移除旧「＋ 建立本地媒体库」大卡与页内长说明
- [x] **A4 文案去重**：`localLibraryCardDetail` 纯函数（单一类型不再重复「书籍 10」，行尾「N 项」承担总数；空库 / 隐藏库文案就位）；4 项单测（`LocalLibraryCardTextTest`）
- [x] **A5 开关归位**：客户端设置「媒体库」分类新增「首页显示本地媒体」`PreferenceSwitch`（`settings_local_library_visible` en/zh-rCN）；媒体库页开关与 `LocalLibraryViewModel.setHomeVisible` 一并下线（偏好键不变）
- [x] **A6 视觉统一**：`LocalLibrarySection` 去内边距（在线 / 离线一致）、区块标题统一 `CinefinType.SectionTitle`、区块间距 24–32dp；未新增配色 / 字体 / 位图 / 依赖
- [x] **A7 修改纪要**：`SettingsViewModel.kt`（设置项）、`LocalLibraryScreens.kt` / `LocalLibraryViewModels.kt`（两段与文案）、`MediaScreen.kt`（顶栏与两段）、`settings` 两语言字符串；未动 `NavigationRoot.kt` / `AppPreferences.kt` 等红线文件

### W40 字体修正波（2026-10-03，分支 `feature/w40-misans-font`，起点 master `6273346`）

- [x] **A 字体替换（路径 A1）**：官方 `MiSans.zip`（227,880,072 B）解出 `MiSans/可变字体/MiSansVF.ttf`（20,093,424 B，SHA-256 `0DDEF906…115E79`）**原字节入库** `core/src/main/assets/fonts/`；`CinefinType.CinefinSans` 换 MiSans 四档 wght（`MisansFont.kt`，解析期从 `context.assets` 加载）；删除 `res/font/noto_sans_sc.ttf` 与 `OFL-1.1-Noto-Sans-SC.txt`；Literata 保留
- [x] **B 软件内注明（条款①）**：关于页 `misans_attribution`（`values` 英文 / `values-zh-rCN` 中文）；许可全文 `assets/licenses/MiSans-License.txt`（官方页面许可协议节原文）
- [x] **C 文档更正**：D38 更正（三条款正确解读：允许内嵌 + 注明义务 + 不改字体文件）+ D40 新决策（字体 = MiSansVF 原文件 + Literata）
- [x] **D 品牌遗留注释**：`docs/web-console-skin.css` + raw 副本 + `docs/web-console-theme.css`；代码注释 3 处红线文件（`NavigationRoot.kt` / `AppPreferences.kt` / `player:local PlaylistManager.kt`，**已申报，仅注释**）
- [x] **门禁**：根 `assembleDebug`（phone + TV）+ `ktfmtCheck` 全绿；单测 `--rerun` 后 **439 项 / 0 失败**（app 74 / core 18（新增 2）/ data 27 / player:local 104 / film 6 / book 106 / music 104）
- [x] **体积**：arm64-v8a debug 基线 `124,877,877 B (119.09 MiB)` → **`144,226,720 B (137.55 MiB)`（+18.45 MiB）**；`assets` 字体未压缩存储（`noCompress += "ttf"`）比压缩多 ≈5.2 MB，但保住 mmap 与 API 28（详见 §5 W40）
- [x] **E 真机布局回归**：Pad 5（`43af8627`）主 + K60（`8e875894`）抽验——窗口由负责人 2026-10-03 批准（05:22–05:25）；首页 / 媒体库 / 音乐 / 书架 / 客户端设置 / 关于 / 阅读器全通过，0 FATAL / ANR，字重 400–700 可见差异，内存无原生膨胀（详见 §5 W40）
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 `--rerun` 后 437 项 / 0 失败（app 74 / core 16 / data 27 / player:local 104 / film 6 / book 106 / music 104）
- [x] **真机（Pad 5 主 + K60 抽验，2026-10-03 04:22–04:37）**：见 §5 W39 验收；期间拦下并修复「`weight(1f)` 传给 M3 `SearchBar` 导致展开态输入框撑满、结果区 0 高」的缺陷（踩坑 55）

**遗留（明示）**：①本地库条目并入搜索属 W40（范围 =「媒体库与本地」，用户 2026-10-03 确认）；②`LocalLibrarySection` 的「＋ 新建」按钮文案为硬编码中文（与 W37 既有本地库文案一致，未纳入本轮字符串资源化）。

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

- [x] ① 书籍点不开：`NavigationRoot` 的 `item.kind == "Book"` 与 `FindroidFolder.kind`（`BaseItemKind` 枚举名 `"BOOK"`）大小写不匹配，书籍一直落到「按文件夹下钻」分支 → 改 `kind.equals("BOOK", ignoreCase = true)`（见踩坑 10），书籍改走 `ReaderActivity`（`exported=false`）
- [x] ② 音乐库空列表：`LibraryViewModel` 对 `CollectionType.Music` 只查 `MUSIC_ALBUM`，服务器无该实体 → `NavigationRoot` 增加 `libraryEntryRoute`：媒体库卡片 / 抽屉 / 搜索三处入口统一分流到 `MusicModeRoute`；`LibraryRoute`（Music）兜底重定向
- [x] 真机端到端复验（Pad 5，2026-09-30 23:10–23:25）：见 §5「P0 链路复验」

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

### W5-R3F 本轮进度（UI 验收缺陷热修，2026-10-01，分支 `feature/r3-ui-hotfix`）

用户验收发现 4 项缺陷，负责人定位后交给本会话逐条修复并真机复验：

- [x] **① 首页头图打印对象（P0）**：根因是 Kotlin 字符串模板陷阱——`"S$this.parentIndexNumber E$this.indexNumber"` 里 `$this` 是**对象插值**，`.parentIndexNumber` 成了字面量（`LandscapeItemCard.cardMetaLine()` 有同款写法，首页卡片同样中招）。改为显式 `seasonCode()` / `indexCode()`（缺号回落 `S?` / `E?`，DTO 缺号被映射成 0 也算缺号）；头图眉标行加 `maxLines = 1` + `TextOverflow.Ellipsis`，集号文本 `weight(1f, fill = false)` 防再次溢出。新增 `ItemFormattingTest`（2 项，断言 `S1 E1` 与 `S? E?`）。
- [x] **② 书架入口跳媒体库（P0）**：真机复现 + 根因确认——`DrawerViewModel.load()` 原先只挂在 `drawerState.isOpen`，**冷启动从未打开抽屉时 `drawerData.libraries` 为空** → `booksLibrary == null` → 书架条目回退 `navigateTopLevel(MediaRoute)`（对照组：先打开一次抽屉再点「书架」即可正常进书籍库，证明路由与 books 库映射本身没问题）。修法：新增独立 `BookshelfRoute` + `BookshelfScreen` / `BookshelfViewModel`——书架 Tab 永远进书架页，由页面自己解析「第一个非空的 books 库」（`Items?limit=1` 轻量判空）→ 退回第一个 books 库 → 没有 books 库则空态；`LaunchedEffect(Unit)` 预载抽屉数据（书架选中态 / 抽屉列表也不再等抽屉打开）。`pickBooksLibrary` 抽成纯逻辑 + 3 项单测；库内容页补空态（空库显示说明而不是空白）。
- [x] **③ 媒体库搜索框悬浮遮挡（P1）**：`MediaScreen` 原先把搜索框与栅格放在同一 `Box`，靠栅格 `contentPaddingTop = 88/144dp` "让位"——只在滚动起点对齐，上滑后卡片钻到搜索框下面。改为 `Column`：搜索框是栅格上方的独立表头，栅格裁剪在自己区域内（顶部内边距收敛为 `Space6`）；M3 `SearchBar` 展开交互不变。
- [x] **④ 流光（Lumen）效果过弱（P1）**：`LumenSurface` 增强四件事——卡片描边改 **1dp 渐变**（白 16% → 5% → 10%，emphasized 时走媒体色渐变）、顶部 1px 内高光改成"中段 2.6× 亮度、两端收光"的横向渐变、沿轮廓加 **极轻外发光**（8dp / 3dp 两道低透明白描边，内半圈被卡片本体盖住）；新增 `lumenTopGlow`（顶部白 12% → 42% 处消散）铺在首页头图与详情页头图；`lumenBottomScrim` 起点从 4% 收到 42%，图上 42% 不再被压暗，明暗对比与"下方溶进底色"更明确。光的颜色纪律守住 §2.6 / §5.3：**只用无彩色的白 / 黑**，未新增 token / 色值 / 位图。
- [x] 门禁：`:app:phone:assembleDebug ktfmtCheck`、`:app:phone:testLibreDebugUnitTest`（5 项）、`:core:testLibreDebugUnitTest`、`:modes:film:testDebugUnitTest` 全绿
- [x] 真机：Pad 5（`43af8627`，横屏 2560×1600 用 `wm size/density` 模拟）与 K60（`8e875894`，竖屏）按 §5「W5-R3F 验收」逐条复验通过；设备副作用已还原、device-lock 已登记并释放
- [x] 未决项已由 **W5-R3G（`feature/r3-ui-navfix`）** 结清：`showNavigation` / `currentLibrary` / 各选中态统一改用 `NavDestination.hasRoute`（见踩坑 28 与 W5-R3G 小节）——平板侧轨在设置 / 书籍库 / 带参媒体库页面常驻且高亮正确，手机底部 Tab 无残留错误高亮

### W5-R3G 本轮进度（导航侧轨常驻修复，2026-10-01，分支 `feature/r3-ui-navfix`）

W5-R3F 交接的踩坑 28 单独一波（小改动，只动 `NavigationRoot.kt` + 文档）：

- [x] **根因**：`NavDestination.route` 对 data object 是 qualifiedName，对带参路由是「类名 + `/{arg}`」模板；`currentRoute == Route::class.qualifiedName` 因此对 `LibraryRoute` / `SettingsRoute` / `ConsoleRoute` 恒为 false。受影响的不止 `showNavigation`（平板侧轨在设置 / 控制台 / 书籍库"消失"），还有 `currentLibrary`（书架库高亮失效）与 `settingsSelected` 等全部选中态判断。
- [x] **修复**：新增文件内 `private inline fun <reified T : Any> NavDestination?.isRoute()`，统一走 `NavDestination.Companion.hasRoute`（按路由类序列化器哈希与 `composable<T>` 注册的目的地 id 比对，带参 / 默认值路由都能命中；navigation 2.10.1 的 import 是 `androidx.navigation.NavDestination.Companion.hasRoute`）。`showNavigation` 覆盖首页 / 媒体库 / 书架 / 下载 / 音乐 / 媒体库（Library）/ 设置 / 控制台全部 8 个统一目的地；`currentLibrary`、`homeSelected` / `musicSelected` / `mediaSelected` / `downloadsSelected` / `settingsSelected` / `booksSelected` 同步切换。未改 `navigateTopLevel` / `NavHost` 注册 / 抽屉动作，导航栈行为不变。
- [x] 门禁：`:app:phone:assembleDebug ktfmtCheck` 通过（先 `ktfmtFormat` 归位 import 与注释）。
- [x] 真机（Pad 5 `43af8627` 横屏 / K60 `8e875894` 竖屏，device-lock 已登记并在完成时释放）：Pad 5 首页 / 设置 / 书架（BookshelfRoute）/ 媒体库（MediaRoute）/ 电影库（LibraryRoute）/ 书籍库（LibraryRoute · Books）侧轨 6 项始终可见；设置项高亮 `SurfaceContainerHigh`（像素 34,42,54），书架项高亮 `Media.Container`（像素 60,53,51），首页项高亮 `SurfaceContainerHigh`；进入书籍库时"书架"项高亮——正是 `currentLibrary` 修复点。K60 底部 Tab 首页 / 音乐 / 书架 / 媒体库逐项切换，文字暖色像素只在当前项 >0（198 / 185 / 201 / 317，其余 0）；进设置页 4 项全部 0（无错误高亮），返回首页后首页项恢复高亮。
- [x] 纪律复核：不改 `settings.gradle.kts` / `libs.versions.toml` / `AppPreferences.kt` / `player/*`、不动其他 worktree、不改 `docs/PROJECT_PLAN.md`。
- **发现（留给负责人）**：真机走查确认**控制台（`ConsoleRoute`）与媒体资料管理器入口当前无法从 UI 到达**——D18 提交 `20c4fe3` 删掉原抽屉里的「服务器控制台 / 媒体资料管理器」条目后没有接回 `chromeDestinations`；`showNavigation` 仍保留 `ConsoleRoute` 判定，但已无导航来源。W5-R3G 未擅自恢复入口（需同时接 `DrawerViewModel.isAdmin` 权限判断，超出本波范围），如需恢复请单独排一波。

### W5-R3H 本轮进度（R3-UI-LUMEN-A，2026-10-01，分支 `feature/r3-ui-lumen-a`）

用户验收反馈三件事（负责人 2026-10-01 交办；决策见 D20）：

- [x] **① 流光配色改 S1「A 方案」**：新增 `core/.../theme/LumenColors.kt`（A 稿色值唯一落点）；`ProvideLumen` 接入首页 + 电影 / 剧集 / 季 / 集详情；`CinefinButton` 新增 `CinefinButtonTone.Inverse`（月白主按钮）；`LumenCardFrame` 在 Lumen 区域改用 A 稿 1px 白 8.5% 细线；`SectionHeader` 的「全部 ›」在 Lumen 区域改三级灰；hero 进度条改青→蓝渐变。音乐 / 阅读 / 媒体库 / 搜索 / 设置保持 Prism（未进入 `ProvideLumen`）。
- [x] **② 亮图文字可读性**：`TextStyle.lumenTextShadow(Title / Meta)` + 首页 / 详情头图 `lumenSideScrim`（A 稿左侧水平渐隐），应用到首页头图与四个视频详情页头图的标题 / 眉标 / 元信息；真机自检：亮底剧照上标题边缘干净、不糊字。
- [x] **③ 手机 hero 行动区截断修复**：根因 = 手机 16:9 头图高度（411dp 宽 → ≈208dp 高）小于内容高度（≈236dp），`Column` 底部对齐时行动区被**压缩测量**——按钮胶囊只剩 14dp 高、文字与「剩余 N 分钟」被裁（见踩坑 29）。修法 = 头图 `heightIn(min = 比例高度)` 由内容撑高 + 行动区 `FlowRow`（放不下自动换行）+ 时间文本 `maxLines = 1 / softWrap = false`。
- [x] 门禁：`:app:phone:assembleDebug ktfmtCheck`、`:core:testLibreDebugUnitTest`（16 项，含新增 3 项 Lumen token / 覆盖映射 / 反色按钮单测）全绿
- [x] 真机自检（Pad 5 `43af8627`，截图自检不入库）：横屏 2560×1600（首页 / 电影详情 / 集详情 A 配色与文字阴影）+ 手机规格 1080×2400 @420 ≈ 411dp（行动区完整显示）
- [ ] K60（`8e875894`）当前未接入：手机形态以 Pad 5 + K60 规格 `wm` 覆盖验证；K60 上线后补一次真机复验

### W5-R3I 本轮进度（控制台入口恢复，2026-10-01，分支 `feature/r3-console-entry`）

补 D18 缺口（W5-R3G 走查留档的「控制台 / 媒体资料管理器无法从 UI 到达」），决策见 D21：

- [x] **入口接回统一列表**：`chromeDestinations` 追加两条（服务器控制台 `ic_globe` / `title_console` → `ConsoleRoute()`；媒体资料管理器 `ic_database` / `title_metadata_manager` → `ConsoleRoute(path = "/metadata")`），位置在「下载」与「设置」之间；`bottom = false` → 手机底部 Tab 仍只有首页 / 音乐 / 书架 / 媒体库，平板侧轨与手机抽屉各 8 条（管理员）
- [x] **管理员门控**：条目构造走 `consoleEntrySpecs(drawerData.isAdministrator)`，非管理员（含 `isCurrentUserAdministrator()` 读取失败）返回空列表 → 两条入口不出现；测试账号（非管理员）真机可见 6 条（侧轨）/ 无控制台条目（抽屉）
- [x] **选中态按 path 区分**：`consoleEntrySelected(currentPath, entryPath)`（`navBackStackEntry.toRoute<ConsoleRoute>().path`，参数缺失回落 `/dashboard`）→ 控制台页高亮「服务器控制台」、资料管理器页高亮「媒体资料管理器」，不再两条同亮
- [x] **导航不复用状态**：`navigateConsole` = `popUpTo(startDestinationId)`（不带 `saveState`）+ `launchSingleTop`；统一入口的 `saveState + restoreState` 会把同一目的地 id 的旧条目恢复出来（踩坑 30），真机 CDP 实测资料管理器落到 `web/#/metadata`（修复前会回到 `#/dashboard`）
- [x] **控制台返回路径**：`ConsoleWebViewClient` 在种子页 → 控制台页切换完成后 `clearHistory()`，控制台页按一次系统返回即回主界面（修复前会先落到空白 `cinefin-seed` 页，踩坑 31）；平板侧轨 / 手机底部 Tab 在控制台页常驻（`showNavigation` 已含 `ConsoleRoute`），抽屉手势可用
- [x] **门禁**：`:app:phone:assembleDebug ktfmtCheck` 通过；`:app:phone:testLibreDebugUnitTest` 8 项全绿（新增 `ConsoleEntrySpecTest` 3 项：非管理员空列表、管理员两条路径与资源、选中态按 path）
- [x] **真机**：Pad 5 `43af8627`（`wm size 2560x1600` + `density 320` ≈ 1280dp 平板形态）与 K60 `8e875894`（原生竖屏）——非管理员隐藏、临时 `isAdministrator = true` 调试构建下出现且导航 / 高亮正确、还原后重装干净包复验隐藏；device-lock 已登记并在完成时释放

### W6-R6N 本轮进度（导航 IA + 客户端设置改造，2026-10-01，分支 `feature/r6-nav-ia`）

用户 2026-10-01 管理员账号验收反馈的「结构与交互」四项（决策见 D22）：

- [x] **①手机去掉左侧抽屉**：`openDrawer` 在 Compact 形态为 null（`HomeTopBar` / `MediaScreen` / `BookshelfScreen` / `DownloadsScreen` 的 `onOpenDrawer` 改可空），`CinefinModalDrawer` 的手势仅在非 Compact 且 `showNavigation` 时开启；底部 4 Tab 顺序与行为不变（新增 `bottomNavKeys` 与单测）
- [x] **②控制台 / 元数据管理器只留一个侧栏**：`showNavigation` 移除 `ConsoleRoute` —— 两个页面不渲染 app 侧轨 / 底部 Tab，抽屉手势同时关闭（控制台内建侧栏独占左侧）
- [x] **③顶层 IA + 媒体库二级分组**：`navEntryKeys`（首页 / 媒体库 / 音乐 / 书架 / 下载 / 管理员两条 / 客户端设置）+ `visibleRailKeys`；侧轨「媒体库」行展开出服务器实际存在的全部库（电影 / 动漫 / 书籍 / 音乐 / Playlists），折叠轨只显示一级图标；「设置」文案统一为「客户端设置」（`CoreR.string.title_settings`）
- [x] **④客户端设置新增项**：媒体库分类（首页媒体库 / 音乐库 / 书架媒体库，`PreferenceDynamicSelect` + `SettingsDynamicSelectCard`，选项来自 `LibraryCatalog` 目录缓存）；侧栏显示分类（7 个开关，即时生效 + 持久化）；离线模式移到设置最后一项且打开后停留在设置页（不跳转 / 不重建）
- [x] **配套**：`MainViewModel` 离线偏好监听（只刷新状态，不回 Loading，避免设置页闪跳与滚动归零）；`DrawerViewModel` 的 `Provider<JellyfinRepository>` 按需解析 + `LaunchedEffect(isOfflineMode)` 刷新导航数据（切回在线侧轨库列表立即恢复）；首页「最新」区块按 `pref_ui_home_library_id` 过滤；音乐库按 `pref_ui_music_library_id` 作为 `parentId` 查询；书架优先 `pref_ui_bookshelf_library_id`
- [x] **门禁**：`:app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest` 全绿（20 项，新增 `NavigationIaTest` 6 项 / `LibraryCatalogTest` 4 项 / `BookshelfPickTest` 2 项）
- [x] **真机（Pad 5 `43af8627`）**：平板形态侧轨 8 条 IA + 媒体库二级 5 个子项；控制台页无 app 侧轨、左缘滑动不再拉出 app 抽屉；设置页「客户端设置」标题 / 媒体库三选项 / 侧栏显示开关（关掉 → 侧轨即时消失、重开恢复）；离线模式在最后且开关不跳页（切到离线侧轨子项与控制台入口即时隐藏，切回即时恢复）；手机形态（`wm 1080x2400`+`density 420` ≈ 411dp）无 hamburger（顶栏标题 x=20dp）、左缘 / 左侧拖动都不出抽屉、底部 4 Tab 与媒体库（共 5 个媒体库）/ 书架（共 5 个项目）行为不变。设备副作用已还原（`wm size / density` reset、App force-stop、`/sdcard` 临时 xml 清理）；device-lock 已登记并释放
- [x] **TV 兼容**：两个新设置分类（媒体库 / 侧栏显示）限 `DeviceType.PHONE` —— TV 端设置页不渲染 `PreferenceDynamicSelect`（该模型未在 TV 实现），不会出现空行
- 本期边界（留给负责人）：内容页（首页 / 音乐 / 书架）在**不重启**的情况下仍持有进入时解析的仓库实例，离线模式对它们的切换在**下一次启动**完全生效；导航面（侧轨 / 抽屉 / 入口门控）已即时生效
### W6-R3 本轮进度（W6-WEB，2026-10-01，分支 `feature/web-console-lumen`）

用户反馈（负责人交办，本会话负责 Web 控制台部分）：①控制台 + 媒体资料管理器 UI 改流光（A · Lumen）配色；②控制台页上下滑动不流畅。决策见 D22。

- [x] **皮肤 v3（Lumen 重绘）**：`docs/web-console-skin.css` 全文重绘（v2 朱砂 → v3 Lumen；14 节覆盖 按钮 / 侧栏 / MUI 面 / 表格 / 输入框 / 滚动条 / 徽标 / 标签页 / 登录页 / 详情页 / legacy 收尾 + 新增 §15 滚动闸门）；同步 `app/phone/src/main/res/raw/web_console_skin.css`（哈希一致）；`WebConsoleScreen.kt` 注释 / 底色 / 种子页同步 `#08090C`
- [x] **服务器主题残留压制**（踩坑 32）：CDP 实测服务器侧自定义 CSS 把 `.navMenuOption` 染成 `#D2553C` → 新增显式覆盖后为 `#98A2B3`（次级灰）/ 选中极光青
- [x] **WebView 滚动设置**：`overScrollMode = OVER_SCROLL_NEVER` + `isNestedScrollingEnabled = false` + `settings.setOffscreenPreRaster(true)` + `setRendererPriorityPolicy(RENDERER_PRIORITY_IMPORTANT, false)`
- [x] **门禁**：`:app:phone:ktfmtCheck`、`:app:phone:testLibreDebugUnitTest`、`:app:phone:assembleDebug` 全绿
- [x] **真机自检**：K60 `8e875894`（1440×3200 @560）资料管理器 / 控制台 / Jellyfin 内建抽屉 —— 旧朱砂像素 **0**、极光青 + 石墨 + 曜石黑分布正确；顶部下拉 overscroll 前后截图 `diff=0`（拉伸消失）；内建抽屉展开 `(0,0,320,717)` ↔ 收起 `(-320,0,320,717)`
- [x] Pad 5 `43af8627`（临时 `wm size 2560x1600` + `density 320`）：控制台 dashboard 配色与滚动前后对比（有效数据见 §5；15:21:40 起设备被其他会话重装，已在下表如实标注数据边界）
- [ ] **服务器自定义 CSS 待用户手动更新**（把 v3 粘贴到 Jellyfin「控制台 → 显示 → 自定义 CSS」；App / 仓库不代传）
- 收尾：K60 临时 `navigation_mode`（手势→三键，便于左缘打开 App 抽屉）已还原为 2；用于进入控制台的临时 `DrawerViewModel.isAdministrator = true` 调试包已 `git restore` 并重装干净包

### W6-VIS 本轮进度（A 配色全站化 + 侧栏/设置重设计 + 加载过渡，2026-10-01，分支 `feature/r6-visual-all`）

用户 2026-10-01 管理员账号验收反馈的三项（决策见 D24 / D25 / D26）：

- [x] **①除音乐库与书架外全部页面改流光（A）配色**：`ProvideLumen` 覆盖到媒体库总览 / 库内容页（非书籍）/ 收藏 / 合集 / 下载 / 演职人员 / 客户端设置（含设置子页与关于）/ 首连向导 6 页；`ProvideLumen` 新增 `MaterialTheme(LumenMaterialColorScheme)` 覆盖，存量 `MaterialTheme.colorScheme` 引用（Scaffold / TopAppBar / Switch / 对话框 / SearchBar）零改动切到 A 色板；跨域页面（媒体库总览含音乐 / 书籍卡）按「页面 Lumen + 卡片中性色 + 点进各域保持各域皮肤」处理
- [x] **②侧栏 / 底栏 / 抽屉 / 客户端设置视觉重设计**：侧轨石墨底 + 右缘发丝线 + 顶缘内高光，选中 = 雾灰容器 + 1dp 细线 + 顶部内高光 + 月白标签 + 极光青图标，未选中次级灰，悬停幽灵白；底栏同语言并把安全区一起铺满；抽屉石墨面板 + 品牌方框 + 服务器幽灵胶囊；设置页分类收进石墨卡（LumenCardFrame）、图标磁贴雾灰 + 发丝线 + 内高光、开关轨道改当前强调色、顶栏下补发丝线。图标全部沿用现有矢量资源只重着色，未新增位图 / 色值
- [x] **③加载过渡动画**：新增 `LumenSkeleton.kt`（shimmer 只触发重绘 + 页面级骨架 + `LumenSkeletonOverlay`），接入冷启动品牌页（220ms 淡入主界面）、首页、媒体库总览、库内容页、下载、5 个详情页、客户端设置；全部动效只走 opacity / graphicsLayer / 渐变平移，内容始终参与组合（不重建滚动与分页状态）
- [x] **门禁**：`:app:phone:assembleDebug` + `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest` + `:core:testLibreDebugUnitTest` 全绿（新增 `lumen material scheme maps neutral slots to direction a values` 1 项）
- [x] **真机**：Pad 5 `43af8627`（`wm 2560x1600` + `density 320` ≈ 1280dp 平板形态：侧轨 164dp 展开 + 首页 / 媒体库 / 客户端设置 / 侧栏显示子页像素采样）+ K60 `8e875894`（1440×3200 @560 原生竖屏：底部 Tab + 冷启动骨架帧）；设备副作用已还原、device-lock 已登记并释放
- 本期边界：控制台（WebView）皮肤由 W6-WEB 的 CSS v3 负责，不在本轮代码范围；播放页（`player/*`）按纪律未动

### W7-R3 本轮进度（用户复测反馈修复波，2026-10-01，分支 `feature/r7-nav-fix`）

用户 2026-10-01 复测反馈的 5 项（决策见 D27–D31）：

- [x] **①手机恢复侧栏入口与顶栏 app 图标**：`openDrawer` 恢复为非空（Compact 也能开抽屉）、抽屉手势按 `showNavigation` 开启；首页顶栏入口换 `ic_logo`（26dp，`打开侧栏`）+ 媒体库 / 书架 / 下载顶栏汉堡键恢复
- [x] **②媒体库二级分组进抽屉且默认展开**：库列表紧跟「媒体库」行（缩进 16dp / 48dp 行高），排在音乐 / 书架之前；抽屉条目区改为可滚动（`weight(1f, fill = false) + verticalScroll`），避免条目变多后矮屏裁掉底部；侧轨顺序不变
- [x] **③侧柜常驻 Lumen**：`lumenChrome = true` —— 侧轨 / 底栏 / 抽屉在任何页面（含音乐 / 书架 / 书籍库）都是 A 稿；音乐、书架、阅读的**页面内容**皮肤未动
- [x] **④控制台悬浮返回入口**：`ConsoleBackToAppPill`（石墨 94% + 发丝线 + 月白「返回影阁」，≥44dp 触控）叠在 WebView 右下角，点击回主界面；系统返回键行为不变（控制台内先退网页历史，一次返回离开控制台）；未恢复平板双侧栏
- [x] **⑤控制台选中态修正**：`consoleEntrySelected` 对 `null` 返回 false；退出控制台后「服务器控制台」不再高亮
- [x] **门禁**：`:app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :core:testLibreDebugUnitTest` 全绿（`:app:phone` 22 项 / `:core` 16 项，0 失败；新增 `mediaGroupSitsBeforeMusicAndBookshelf`、`selectsNothingWhenNotOnConsoleRoute`）
- [x] **真机**：Pad 5 `43af8627`（平板 2560×1600 @320 + 手机形态 1080×2400 @420）与 K60 `8e875894`（1440×3200 @560 原生竖屏）逐条验证 1–5；像素采样 / dump 文本取证，logcat 无 FATAL / ANR；设备副作用已还原、device-lock 已写释放
- 本期边界：Web 控制台内部皮肤仍由 W6-WEB 的 CSS v3 负责；`player:*`、`docs/web-console-skin.css`、`AppPreferences` 等禁用文件零改动

### W8-R3 本轮进度（三页顶栏统一 + 媒体库改版 + 侧栏媒体库分组回退，2026-10-01，分支 `feature/r8-ui-unify`）

用户 2026-10-01 复测反馈的 4 组（决策见 D32–D34）：

- [x] **A 三页共用顶栏**：core 新增 `CinefinPageTopBar`（56dp + `statusBarsPadding()` + 44dp 键 + 统一页边距 / 标题排版 / 右侧动作槽）；媒体库 / 音乐 / 书架三页接入，音乐旧 72dp `MusicHeader`（无顶部 inset = 被状态栏挡的根因）下线；书架顶层改 `LibraryScreen(topLevel = true, onOpenDrawer = …)` → 顶栏「书架 / 共 N 本」、**无返回箭头、无库名「书籍」**；二级书籍库保持「返回 + 书籍 + 共 N 个项目 + 排序」；音乐详情保持「返回 + 详情标题 + 共 N 首曲目」
- [x] **B 媒体库总览改版**：库卡 = 类型图标磁贴（雾灰 + 发丝线 + 强调色图标）+ 库名 + 项目数 + 三级灰箭头；面板改 Lumen 石墨、渐隐改 `LumenTokens.Scrim`；项目数经 `ItemFields.CHILD_COUNT` → `FindroidCollection.itemCount`；标题 / 计数并入顶栏同排（去掉两段式）
- [x] **C 侧栏媒体库分组回退**：`navEntryKeys` = 首页 → 音乐 → 书架 → 媒体库 → 下载 → 控制台 / 资料管理器 → 客户端设置；`MEDIA_GROUP_DEFAULT_EXPANDED = false`；抽屉「媒体库」行补行尾箭头（与侧轨同款），展开后才显示库子项；手机底部 4 Tab 顺序不变
- [x] **门禁**：`:app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :core:testLibreDebugUnitTest` 全绿（`:app:phone` 23 项 / `:core` 16 项，0 失败；`NavigationIaTest` 顺序改为"音乐 / 书架在媒体库之前"+ 新增 `mediaGroupDefaultsCollapsed`）
- [x] **真机**：Pad 5 `43af8627`（平板 2560×1600 @320 + 手机形态 1080×2400 @420）与 K60 `8e875894`（1440×3200 @560 原生竖屏）逐条验证 A–D；像素采样 / dump 文本取证，logcat 无 FATAL / ANR；设备副作用已还原、device-lock 已写释放
- 本期边界：音乐 / 书架 / 书籍库**页面内容**仍为各自皮肤（只有侧柜常驻 Lumen，见 D29）；`player:*`、`AppPreferences`、`settings.gradle.kts`、`libs.versions.toml`、`docs/web-console-skin.css`、`res/raw/web_console_skin.css` 零改动

## 5. 验收

### W63 下载域缺陷修复验收（2026-10-04，分支 `fix/w63-download-fixes`，起点 master `a8a580f`；Pad 5 `43af8627`）

- **静态 / 门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **719 项 / 0 失败 0 错误**（新增 6 = film `DownloadBadgeRulesTest` 5 + core `DownloadSnackbarDurationTest` 1）。
- **真机**：7 条逐项通过（①整剧 12/12 + 多季 54；②全季 26；③Season 长按多选 + 批量 2 集、跳过已在队列；④下载页长按 12 / 26 项；⑤Snackbar ≈10 s 自动消失；⑥首帧 2.9 s；⑦角标即时 12 → 14 → 2 → 0）；0 FATAL / ANR；测试下载 / `/sdcard` 临时文件已清理。
- **红线申报**：动 `core`（`CinefinCard` / `CinefinSnackbar` / `Downloader` + `DownloaderImpl`）、`app:phone`（Season / Downloads / EpisodeCard / DownloadRows / 7 屏 Snackbar）、`modes:film`（详情与下载 VM / 角标规则）；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` / `data` 未动。

### W60b 验收（2026-10-04，两段提交 `7702f38` / `c1f85d2`，起点 master `32c0e37`；Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

- **静态 / 门禁**：A 段 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun` **688 项 / 0 失败**（app 155）；B 段同门禁 **704 项 / 0 失败**（app 171）；新增单测 19。
- **真机**：A 段 6 项 + B 段 6 项全部通过（清单见 §4 W60b；device-lock 07:40–09:05 已写释放与结论）。真机拦下 1 处缺陷：`FavoritesRoute` 未加入 `NavigationRoot.showNavigation` 白名单 → 我的收藏页不渲染侧轨（导航被困），修复后复验通过。
- **红线申报**：动 `NavigationRoot.kt`（侧栏 / 路由 / 白名单）、`data`（收藏查询排序 + 收藏广播 + 离线空表）、`core`（图标接线 / Snackbar 动作 / Downloader 事件）、`app/phone`（页面 / 卡片 / 徽标 / 选择框架）、`modes:music`（收藏图标 / 下载反馈 / 预取）、`modes:film`（删除收藏媒体库聚合 VM）；`AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 未动。
- **未覆盖**：失败徽标真机样本、搜索结果下载角标样本、角标淡入逐帧、音乐批量服务器压力（见 §4 W60b）。

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

### W5-R3F 验收（2026-10-01，分支 `feature/r3-ui-hotfix`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug :app:phone:ktfmtCheck --console=plain                    # BUILD SUCCESSFUL
.\gradlew.bat :app:phone:testLibreDebugUnitTest --console=plain                                # 5 项通过（ItemFormatting 2 + BookshelfPick 3）
.\gradlew.bat :core:testLibreDebugUnitTest :modes:film:testDebugUnitTest --console=plain      # BUILD SUCCESSFUL
```

- [x] **①** K60 冷启动首页：头图眉标 `继续观看 · S1 E2`、走廊卡 `S1 · E2 · 剩余 23 分钟`、海报卡 `S1 · E1`（修复前真机为 `S[].parentIndexNumber · E[S[].parentIndexNumber].indexNumber`，见 W5 遗留截图记录）；单测断言 `S1 E1` / `S? E?`
- [x] **②** K60：冷启动（**未打开抽屉**）点底部「书架」→ `书籍 · 共 5 个项目`（修复前落 `媒体库 · 共 7 个媒体库`）；Pad 5：冷启动点侧轨「书架」→ 同结果；两台设备「媒体库」仍进 `共 7 个媒体库`（行为未变）
- [x] **③** K60：媒体库上滑后最上可见卡片 `其他` y=1203、搜索框 y=625–796（无交集）；Pad 5：上滑后顶部卡片 y=1137、搜索框 y=476–623（无交集）。展开搜索输入 `9` → 命中 `9-nine- 支配者的王冠`（徽标 `13`），交互无回归
- [x] **④** 首页头图：卡片外一圈像素实测亮度 **25.3 → 35.1**（同帧远处页面背景对照恒为 25.3，排除内容差异），顶光 + 底渐隐 + 描边内高光肉眼可辨；Pad 5 横屏（`wm size 2560x1600` + `density 320` ≈ 1280dp）复验同款处理；详情页头图（`ItemHeader`）加同款顶部光晕 + 底部渐隐（截图自检，未入库、未贴回对话）
- [x] 设备副作用已还原：Pad 5 `wm size/density reset`、`wm fixed-to-user-rotation disabled`、`accelerometer_rotation 1`、`user_rotation 0`；K60 未做显示覆盖；两台设备 App 已 force-stop、`/sdcard/*.xml` 临时文件已清理；`device-lock.md` 已登记并在完成时释放
- [x] 纪律复核：不碰 `player/*`、不改 `settings.gradle.kts` / `libs.versions.toml` / `AppPreferences.kt`、不动其他 worktree、不改 `docs/PROJECT_PLAN.md`

### W5-R3G 验收（2026-10-01，分支 `feature/r3-ui-navfix`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck --console=plain    # BUILD SUCCESSFUL
```

- [x] **Pad 5（`43af8627`，横屏 2560×1564）**：侧轨（折叠 88dp，6 项）在 首页 / 设置 / 书架（BookshelfRoute）/ 媒体库（MediaRoute）/ 电影库（LibraryRoute）/ 书籍库（LibraryRoute · Books）全程常驻；选中态像素采样：设置与首页项 `rgb(34,42,54)`、书架与书籍库项 `rgb(60,53,51)`，未选中项均为 navSurface `rgb(18,23,29)`（修复前设置 / 书籍库页面整条侧轨消失）
- [x] **K60（`8e875894`，竖屏 1080×2400）**：底部 Tab 首页 / 音乐 / 书架 / 媒体库逐项切换，选中项文字暖色像素 198 / 185 / 201 / 317，其余 0；进入设置页 4 项全 0（不残留错误高亮），返回首页后首页项恢复；抽屉选择设置后自动关闭
- [x] 控制台（`ConsoleRoute`）：**当前 master 的 UI 无入口**（D18 提交 `20c4fe3` 移除后未接回 `chromeDestinations`），无法真机进入；带参路由判定已由设置（SettingsRoute）/ 书籍库与电影库（LibraryRoute）覆盖验证
- [x] 纪律：不改 `settings.gradle.kts` / `libs.versions.toml` / `AppPreferences.kt` / `player/*`、不动其他 worktree、不改 `docs/PROJECT_PLAN.md`；设备副作用已还原（Pad 5 自动旋转恢复、两台设备 force-stop、临时文件清理），device-lock 已登记并在完成时释放

### W5-R3H 验收（2026-10-01，分支 `feature/r3-ui-lumen-a`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck --console=plain   # BUILD SUCCESSFUL（269 tasks）
.\gradlew.bat :core:testLibreDebugUnitTest --console=plain         # BUILD SUCCESSFUL（16 项：原 13 + Lumen 3）
```

- [x] **① A 配色（Pad 5 `43af8627` 横屏）**：首页 / 电影详情 / 集详情全部走 A 色板——页底 `#08090C`、卡片 / 面板 `#111319` 系、眉标与进度极光青 `#5CE1D2`、主按钮月白 `#F2F5F9` + 深色内容 `#0A0C11`；hero 进度青→蓝渐变；Lumen 卡片描边白 8.5%。音乐 / 阅读 / 媒体库 / 搜索 / 设置未进入 `ProvideLumen`，保持 Prism（不扩散）
- [x] **② 亮图文字**：首页头图（亮点剧照）标题 / 眉标 / 元信息与四个详情页头图同款处理；自检结论 = 文字边缘干净、对比足够（参数：标题黑 62% / (0,2dp) / 模糊 12dp；眉标与元信息黑 72% / (0,1dp) / 6dp；另加 A 稿左侧水平渐隐）；截图未入库、未贴回对话
- [x] **③ 手机 hero 行动区（K60 规格：1080×2400 @420 ≈ 411dp，Pad 5 `wm` 覆盖）**：修复前按钮胶囊被压成 ≈14dp 高（橙色像素仅 y 662–699）、文字与「剩余 N 分钟」被裁；修复后按钮为完整 46dp 月白胶囊、文字完整，「剩余 6 分钟」完整，头图高度由内容撑到 ≈213dp（16:9 的 208dp 只是下限）
- [x] **布局不回退**：Pad 5 横屏首页 hero 21:9、按钮与剩余时间同一行（未换行）、详情页三栏 / 制作信息表 / 侧轨均未变；`FlowRow` 只在放不下时换行
- [x] 单测：Lumen token 9 色 + 覆盖映射 + `CinefinButtonTone.Inverse` 解析（3 项）通过
- [x] 纪律复核：不改 `settings.gradle.kts` / `libs.versions.toml` / `AppPreferences.kt` / `player/*`、不动其他 worktree、不改 `docs/PROJECT_PLAN.md`；Pad 5 `wm size/density` 已 reset、`accelerometer_rotation` 恢复 1 / `user_rotation` 0、App force-stop、`/sdcard` 临时文件清理；device-lock 已登记并在完成时释放
- [ ] **K60（`8e875894`）当前未接入**：手机形态暂以 Pad 5 + K60 规格覆盖验证；接入后补一次真机复验（预期与 Pad 5 覆盖一致）

### W5-R3I 验收（2026-10-01，控制台入口恢复，分支 `feature/r3-console-entry`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck --console=plain          # BUILD SUCCESSFUL
.\gradlew.bat :app:phone:testLibreDebugUnitTest --console=plain            # 8 项：原 5 + ConsoleEntrySpec 3
```

- [x] **① 非管理员隐藏（测试账号真名 `zhangwenkang`，服务端侧亦非管理员——jellyfin-web 把 `/dashboard` 重定向到 `#/home`）**：Pad 5 平板形态（侧轨展开 164dp）左侧 6 条 = 影阁 / 首页 / 音乐 / 书架 / 媒体库 / 下载 / 设置 + 收起，无控制台条目；抽屉（边缘手势打开）= 同一份 6 条 + 库列表（电影 / 动漫 / 其他 / 书籍 / 书籍3 / 音乐 / Playlists），无控制台条目；K60 底部 Tab 4 条 + 抽屉同样无控制台条目
- [x] **② 管理员门控为纯函数 + 临时调试构建证明**（真机无法用管理员账号登录）：临时把 `DrawerViewModel` 的 `isAdministrator` 置 `true` 出调试包（未提交，验证后已 `git restore` 还原并重装干净包）——Pad 5 侧轨出现「服务器控制台 / 媒体资料管理器」（8 条），K60 抽屉出现同样两条且点击后抽屉关闭；两份证据同时说明门控接线正确（同一份 APK 的差别只在 `isAdministrator`）
- [x] **③ 导航正确（CDP 取 WebView 真实 URL）**：Pad 5 点「服务器控制台」→ WebView `https://jellyfins.zhangwenkang.com/web/#/home`（服务端把 `/dashboard` 重定向到非管理员的首页），点「媒体资料管理器」→ 轮询抓到 `cinefin-seed` → `web/#/metadata`，**说明 app 侧确实带 `path = "/metadata"` 而不是复用旧条目**（踩坑 30 回归点）；K60 抽屉点「媒体资料管理器」→ `web/#/metadata`
- [x] **④ 侧轨常驻 + 选中态（像素采样，截图未入库）**：控制台页面上侧轨仍为 8 条；「服务器控制台」行底像素 `(60,53,51)`（= `Media.Container` 选中高亮），「媒体资料管理器」行 `(18,23,29)`（未选中）；切到资料管理器后两行像素互换 → 同一目的地按 path 区分选中态生效
- [x] **⑤ 返回 / 抽屉入口可用**：控制台页内按一次系统返回即回主界面（修复前先落到空白种子页，需按两次）；K60 控制台页底部 4 Tab 常驻，点「首页」直接回首页；平板侧轨在控制台页可点任意条目离开
- [x] 纪律复核：不改 `settings.gradle.kts` / `libs.versions.toml` / `AppPreferences.kt` / `player/*`、不动其他 worktree、不改 `docs/PROJECT_PLAN.md`；Pad 5 `wm size / density` 已 reset、`accelerometer_rotation` 1 / `user_rotation` 0、两台设备 App 已 force-stop、`/sdcard` 临时 xml 与本地临时截图 / xml 已清理、`adb forward` 9222–9224 已移除；device-lock 将于完成时释放

### W6-R6N 验收（2026-10-01，Pad 5 `43af8627`，分支 `feature/r6-nav-ia`）

设备按 device-lock 调度（Pad 5 → W6-NAV；K60 归 W6-WEB），全部命令带 `-s 43af8627`；手机形态用 `wm size 1080x2400` + `density 420`（≈411dp）覆盖，走查后已 reset。

- [x] **平板侧轨 IA**：侧轨 = 首页 / 媒体库（含 5 个子项：电影 / 动漫 / 书籍 / 音乐 / Playlists）/ 音乐 / 书架 / 下载 / 服务器控制台 / 媒体资料管理器 / **客户端设置** / 收起；uiautomator 文本核对与截图一致，子项用服务器返回顺序与图标（同名多库逐条列出）
- [x] **控制台单侧栏**：进入「服务器控制台」后 app 侧轨与底栏都不渲染（截图里只剩 jellyfin-web 自己的左侧栏：服务器 / 控制台 / 常规 / 品牌 / …）；从左缘 / 左侧拖动不再拉出 app 抽屉（左缘滑动走系统返回，直接回到主界面）；对比此前 `showNavigation` 含 `ConsoleRoute` 时侧轨常驻
- [x] **客户端设置页**：标题「客户端设置」；分类顺序 = 语言 / 媒体库 / 侧栏显示（副题：对平板侧轨与抽屉生效；客户端设置始终保留）/ 界面 / 播放器 / 用户 / 服务器 / 下载 / 网络 / 缓存 / 关于 / **离线模式（最后一项）**
- [x] **媒体库三选项（持久化）**：`媒体库` 分类内 = 首页媒体库 / 音乐库 / 书架媒体库（默认「自动」）；把「音乐库」选为 `音乐` 后 SharedPreferences `pref_ui_music_library_id` = `28e99602-…`（音乐库 id）且行尾显示「音乐」，切回「自动」后键被清空；目录缓存 `pref_ui_library_catalog` 记录 5 个库（id + 名称 + 类型）
- [x] **侧栏显示开关（即时生效）**：关闭「首页」→ 侧轨「首页」即时消失（无需重启 / 重进页面），重新打开即时恢复；「客户端设置」行常驻（可见性开关不允许把自己关掉）
- [x] **离线模式**：位于最后一项；打开后仍停在设置页同一滚动位置（离线模式行仍在视口内），**没有重启 / 跳页**；同时侧轨的媒体库子项与控制台 / 资料管理器入口即时隐藏，关闭后即时恢复（`DrawerViewModel` 按 `Provider<JellyfinRepository>` 重新解析 + 导航数据刷新）
- [x] **手机形态**：无 hamburger —— 首页顶栏「影阁」x0 = 20dp（= 页边距，此前为图标 + 间距之后）；从左缘 / 左侧拖动均不出现抽屉（`客户端设置` 文本为 0 命中）；底部 4 Tab 顺序不变（首页 / 音乐 / 书架 / 媒体库），点「媒体库」显示「共 5 个媒体库」、点「书架」显示「书籍 · 共 5 个项目」
- [x] 设备副作用还原：Pad 5 `wm size / density` reset（Physical 1600x2560 / 360）、App force-stop、`/sdcard` 临时 xml 清理；本地临时截图 / xml 走查后删除
### W6-R3 验收（2026-10-01，Web 控制台 Lumen 皮肤 + 滚动优化，分支 `feature/web-console-lumen`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:ktfmtCheck :app:phone:testLibreDebugUnitTest :app:phone:assembleDebug --console=plain
```

- [x] **① Lumen 配色（K60 `8e875894`，1440×3200 @560，像素采样步长 4）**：媒体资料管理器 —— 曜石黑 `#08090C` 113743 点、石墨 `#111319` 58291 点、雾灰 `#171A21` 61099 点、极光青 `#5CE1D2` 89 点、月白 `#F2F5F9` 1097 点、**旧朱砂 `#D2553C` = 0**；控制台 dashboard（Pad 5 `wm 2560×1600` + `density 320`）—— 青 189 点、旧朱砂 11 点（全部位于 App 侧「影阁」品牌图标 61–68px 区域，**网页内容 0**）；主按钮计算样式 = `rgb(242,245,249)` + `rgb(10,12,17)`，危险按钮 = `rgb(58,26,22)` / `rgb(255,180,168)`
- [x] **② 滚动流畅度（前后对比，同页同手势）**：K60 资料管理器电影库（RAF 帧间隔探针 + `dumpsys gfxinfo`，10 连滑）：baseline（v2 皮肤 + 默认 WebView）RAF p50 8.3ms / max 8.4–8.5ms、gfxinfo 1011/1033/1041 帧（Janky 0.00/0.19/0.19%，90th 7ms）；v3 RAF p50 8.3ms / max 8.4–16.5ms、gfxinfo 1002/1004 帧（Janky 0.10/0.20%，90th 7ms）→ **两版均 120fps 稳定、无回退**。Pad 5（2560×1600 @320）控制台 dashboard（8 连滑交替）：baseline RAF max 132.8/25/16.7ms（>20ms 帧 5/1/0）、gfxinfo Janky 1.04/0.19/0.19%、90th 16/10/15ms、Missed Vsync 3/0/1；v3 RAF max 150.4/33.4/33.4ms（>20ms 5/1/2）、gfxinfo 0 帧（该设备 WebView 走独立合成，HWUI 无提交）→ 首滑 100–150ms 尖峰两版均存在（疑 WebView 首帧栅格化 / MIUI 调度，记录待 R4 复核）
- [x] **③ overscroll 行为（可复现）**：K60 顶部下拉前后截图逐像素对比（步长 6 / 容差 12）**diff=0 / maxDelta=0**（`overScrollMode=NEVER` 生效，Android 12+ 拉伸回弹消失）；CDP 实测 `overscrollBehaviorY` = `none`（v3）vs `auto`（v2 注入对照）
- [x] **④ 控制台内建侧栏展开 / 收起**：K60 竖屏 Jellyfin 抽屉 `.mainDrawer` 展开 `(0,0,320,717)` ↔ 收起 `(-320,0,320,717)`（CDP 读 rect）；抽屉导航项颜色由服务器主题的 `rgb(210,85,60)` → `rgb(152,162,179)`（v3 覆盖后）
- [x] **⑤ 纪律复核**：未改 `NavigationRoot.kt` / `AppPreferences.kt` / `settings.gradle.kts` / `libs.versions.toml` / `player/*`；未动其他 worktree；未改 `docs/PROJECT_PLAN.md`；临时 `isAdministrator` 门控改动已 `git restore` 并重装干净包
- [ ] **未决（用户侧）**：服务器「自定义 CSS」需用户手动粘贴 v3 内容；更新前 App 内皮肤已自洽（服务器旧 CSS 的主要强调色已被 v3 显式覆盖，个别未覆盖元素可能残留）
- 数据边界说明：Pad 5 的 baseline 三轮采于 15:08–15:12、v3 三轮采于 15:20 前后（均为本会话包）；15:21:40 设备被其他会话重装后本会话立即停用 Pad 5，其后数据不计入（见 device-lock 更正记录）

### W6-VIS 验收（2026-10-01，A 配色全站化 + 侧栏/设置重设计 + 加载过渡，分支 `feature/r6-visual-all`）

验收命令（全部通过）：

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
.\gradlew.bat :app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :core:testLibreDebugUnitTest --console=plain
```

- [x] **除音乐库 / 书架外各主要页面均为 Lumen 观感**（像素采样，Pad 5 2560×1600 @320 平板形态 + K60 1440×3200 @560）：
  - 首页：精确色直方图前 24 位全部是 A 色板（`#08090C` 页底 386,277 / `#111319` 侧轨 60,763 / `#171A21` 容器 53,024 / `#F2F5F9` 月白 11,993 / `#98A2B3` 次级灰 2,652）；旧 Prism 强调色 **琥珀 0 / 松石 0 / 书籍蓝 0**；
  - 媒体库总览：全屏无 Prism 槽位色（`#151A21` / `#12171D` 命中 0），页底 `#08090C` + 卡片 `#171A21`；
  - 客户端设置：`#111319` 卡 1,686,017 / `#08090C` 页底 2,036,008 / 发丝线 `(38,39,45)` 25,284 / 月白文字 10,096，无 Prism 色；
  - 「侧栏显示」子页：7 个开关轨道 `#5CE1D2` 命中 6,770、配对深色拇指 `#0A0C11` 12,012 → **开关 = 极光青**；
  - **音乐页保持 Prism**：`#151A21` 3,305,745 / `#222A36` 78,444 / 松石系 `(33,60,62)` 22,503 / `(43,109,96)` 1,276，**极光青 0**；
  - 旧朱砂 `#D2553C` 仅在品牌 logo 位图内（19 px），非 UI 控件残留。
- [x] **侧栏视觉明显改善**：侧轨 164dp 展开 = 石墨底 `#111319` 426,777 px + 右缘发丝线（`(38,39,45)`）+ 选中项雾灰 `#171A21` 56,176 px + 月白标签 1,773 px + **极光青选中图标 704 px** + 次级灰 11,655 px；底栏（K60）= 石墨底 + 极光青指示条，手势条安全区同底色（修复前该处露出外层主题 `(21,26,33)`）。
- [x] **功能不回退**：侧栏显示开关、库选择、离线模式、导航 IA 均未改动逻辑（本轮只改配色与排版；`AppPreferences` / `settings.gradle.kts` / `libs.versions.toml` / `player/*` / `WebConsoleScreen.kt` / `web-console-skin.css` 零改动）。
- [x] **加载态有平滑过渡**：冷启动 4 连拍帧（K60）——第 1 帧系统桌面 → 第 2/3 帧为 shimmer 骨架（石墨块 + 扫光渐变，`#111319` / `#171A21` 连续色阶）→ 第 4 帧内容（卡片容器已就位、海报图仍在填充）；`logcat` 无 `FATAL` / `ANR in` / `Input dispatching timed out`。
- [x] **动效性能**：shimmer 只读绘制阶段状态（`drawBehind`），只触发重绘；骨架淡入淡出与内容淡入均走 `graphicsLayer{alpha}` / `AnimatedVisibility`（仅 opacity），未改宽高、未做模糊（§6.4）。
- [x] **device-lock**：使用前登记、完成后写释放时间（Pad 5 `43af8627` + K60 `8e875894`），设备副作用（`wm size/density` / 旋转 / 临时文件 / force-stop）已还原。

### W7-R3 验收（2026-10-01，用户复测反馈修复波，分支 `feature/r7-nav-fix`）

验收命令（全部通过）：`:app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :core:testLibreDebugUnitTest`（app 22 项 + core 16 项，0 失败）。

设备：Pad 5 `43af8627`（平板 `wm 2560x1600` + `density 320` ≈1280dp；手机形态 `wm 1080x2400` + `density 420` ≈411dp）+ K60 `8e875894`（1440×3200 @560 原生竖屏）。

- [x] **①手机侧栏入口 / app 图标**：首页顶栏入口 `content-desc="打开侧栏"`（Pad 5 手机形态 [77,100][145,168]、标题 x=190px=72dp；K60 [102,191][193,282]），点击后抽屉打开；媒体库 / 书架 / 下载顶栏汉堡键恢复；底部 4 Tab 顺序与位置不变（y≈2295–2341 / K60 3060–3121）。
- [x] **②媒体库二级分组（默认展开、位于音乐 / 书架之前）**：抽屉 dump 顺序 = 首页 → 媒体库 → 电影 / 动漫 / 书籍 / 音乐 / Playlists（缩进 x=221 vs 一级 179 → 16dp；K60 x=294 vs 238）→ 音乐 → 书架 → 下载 → 服务器控制台 → 媒体资料管理器 → 客户端设置，12 条在手机形态整屏可见；侧轨（Pad 5 平板）顺序一致：首页 y201 / 媒体库 y313 / 子项 415–825 / 音乐 y885 / 书架 y997 / 下载 y1109 / 控制台 y1221 / 资料管理器 y1333 / 客户端设置 y1445。
- [x] **③侧柜常驻 Lumen（内容皮肤不变）**：
  - 音乐页（Pad 5 平板）侧轨 = 石墨 `#111319` 356,087 + 选中雾灰 `#171A21` 27,571 + 次级灰 `#98A2B3` 11,687 + 月白 `#F2F5F9` 430；选中行（音乐）雾灰 11,243 + 月白 430 + 极光青 `#5CE1D2` 256；**音乐内容区仍是 Prism**：石板 `#151A21` 2,632,135 + 松石容器 `#213C3E` 20,091，极光青 0；
  - 书架页（Pad 5 平板）侧轨 = `#111319` 356,814 + 选中行 26,844 + 极光青 898；内容区 `#12131A` / `#222A36`（原样）；
  - K60 音乐页底栏 = `#111319` 189,914 + 次级灰 6,213 + 月白 918 + 极光青 502（指示条 / 图标）；内容区石板 2,937,078、极光青 0；
  - 手机抽屉（Pad 5 手机形态）= `#111319` 1,078,164 + 选中行 `#171A21` 90,931 + 月白 743 / 极光青 669。
- [x] **④控制台悬浮返回入口**：Pad 5 平板 pill 可点区 [2282,1444][2528,1540]（123×48dp，屏内），像素 `#111319` 14,552 + 月白 824 + 顶缘内高光 `#26272D` 472；点击 → 回首页（dump 出首页内容）。控制台页左缘 x<300 = `#0B0D11` + 极光青 1,209 → 只有 jellyfin-web 自带侧栏，**未恢复 app 双侧栏**。Pad 5 手机形态 / K60 pill 文本 [825,2230][996,2286] / [1101,2974][1328,3048]（可点区均 123×48dp；K60 像素 `#11141A` 为 94% 石墨底在 WebView 上的混色 + 月白 3,370）。**系统返回键行为不变**：控制台页按一次 BACK 即回主界面（Pad 5 手机形态实测，无空白种子页）。
- [x] **⑤退出控制台后不再残留选中**：Pad 5 平板回首页后 首页行 `#171A21` + 月白 445、服务器控制台行 `#111319` 9,652 + 次级灰 1,250；手机形态 首页行 40,112 + 极光青 669、控制台行 `#111319` 36,494；K60 首页行 70,982 + 极光青 1,293、控制台行 `#111319` 65,547。
- [x] **稳定性 / 副作用**：两台设备 logcat 无 `FATAL` / `ANR in` / `Input dispatching timed out`（仅 uiautomator 自身启动日志）；Pad 5 `wm size/density` reset（Physical 1600×2560 / 360）、`accelerometer_rotation` 1、`user_rotation` 0；K60 未改 wm；两台 App force-stop、`/sdcard/w7r3*` 临时文件清理、本地截图已删。
- 未验：**左缘滑出抽屉**在系统手势导航（`navigation_mode = 2`，两台设备均如此）下被系统「返回」手势占用，adb 左缘 swipe 触发的是系统返回（踩坑 43）；顶栏入口已验，如需手势断言需先切三键导航（如 W6-WEB 所做），本轮按"用户要求的是可见入口"处理。

### W8-R3 验收（2026-10-01，三页顶栏统一 + 媒体库改版 + 侧栏媒体库分组回退，分支 `feature/r8-ui-unify`）

验收命令（全部通过）：`:app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest :core:testLibreDebugUnitTest`（app 23 项 + core 16 项，0 失败）。

设备：Pad 5 `43af8627`（平板 `wm 2560x1600` + `density 320` ≈1280dp；手机形态 `wm 1080x2400` + `density 420` ≈411dp）+ K60 `8e875894`（1440×3200 @560 原生竖屏）。

- [x] **①三页顶栏按钮 / 标题 bounds 一致（音乐不再被状态栏遮挡）**：
  - Pad 5 手机形态：音乐 / 书架 / 媒体库的侧栏入口 `content-desc="打开侧栏"` 全部 = `[80,103][143,166]`（首页 `ic_logo` = `[77,100][145,168]`，两者中心 y 均为 134 —— 顶栏已落在状态栏之下）；标题 + 计数同排：音乐 `[190,73][304,149]` +「共 94 张专辑」`[190,149][375,195]`、书架 `[190,73][304,149]` +「共 5 本」`[190,149][304,195]`、媒体库 `[190,73][361,149]` +「共 5 个媒体库」`[190,149][409,195]`；右侧动作：书架「排序」`[938,103][1001,166]`、媒体库「搜索」`[938,103][1001,166]`。
  - Pad 5 平板（1280dp）：三页顶栏标题 x 全为 528px（rail 328px + 48dp 页边距 96px + 44dp 键 88px + 8dp 间距 16px），音乐 `[528,70][616,128]` / 媒体库 `[528,70][660,128]` / 书架 `[528,70][616,128]`，按钮同为 `[444,92][492,140]`。
  - K60：音乐 / 书架 / 媒体库的「打开侧栏」全部 = `[105,194][189,278]`，标题 y 全为 `[?,155][?,257]`（音乐 `[252,155][406,257]` / 书架 `[252,155][406,257]` / 媒体库 `[252,155][483,257]`）。
- [x] **②书架顶层无返回箭头、无库名「书籍」**：Pad 5 手机形态书架 = 「打开侧栏」+「书架 / 共 5 本」+「排序」；Pad 5 平板 / K60 同构；点进二级书籍库（抽屉 → 书籍）仍是「返回」`[105,194][189,278]` +「书籍」+「共 5 个项目」+「排序」（原行为保留）。
- [x] **③媒体库新布局 / 配色的像素采样（A 色板占比 + 强调色点缀）**：
  - Pad 5 手机形态（1080×2400，步长 4，162,000 采样）：曜石黑 `#08090C` 43,186（26.66%）+ 石墨 `#111319` 20,732（12.80%）+ 雾灰 `#171A21` 10,627（6.56%）+ 月白 582 + 次级灰 776 + **极光青 `#5CE1D2` 213（0.13%，5 张库卡的类型图标）** + 辅光蓝 0。
  - Pad 5 平板（步长 4，256,000 采样）：曜石黑 25.18% / 石墨 16.81% / 雾灰 13.16% / 极光青 117 点 / 辅光蓝 0。
  - K60（步长 6，128,160 采样）：曜石黑 30.20% / 石墨 13.07% / 雾灰 6.68% / 极光青 163 点。
  - 库卡信息条（dump）：卡片显示「库名 + 共 N 个项目」（Pad 5 手机形态 电影 5 / 动漫 1；平板 电影 9 / 动漫 5；K60 电影 3）。**N = 服务器 `ChildCount`**，三次读取值随服务器内容变化（服务端数据在变，非客户端不稳定）；类型图标不参与 dump（无文本），像素采样到的极光青即其着色。
- [x] **④抽屉 / 侧轨顺序（音乐 → 书架 → 媒体库，库子项默认收起、展开后可见）**：
  - Pad 5 手机形态抽屉（默认）：首页 358 → 音乐 505 → 书架 652 → 媒体库 799（行尾「展开」chevron `[677,764][803,890]`）→ 下载 946 → 服务器控制台 1093 → 媒体资料管理器 1240 → 客户端设置 1387（12 条整屏可见，无库子项）；点箭头后：电影 935 / 动漫 1061 / 书籍 1187 / 音乐 1313 / Playlists 1439（缩进 x=221 vs 一级 179 = 16dp）→ 下载 1576 / 控制台 1723 / 资料管理器 1870 / 客户端设置 2017（仍屏内）。
  - Pad 5 平板侧轨（164dp 展开）：首页 201 → 音乐 313 → 书架 425 → 媒体库 537（默认收起）→ 下载 649 → 控制台 761 → 资料管理器 873 → 客户端设置 985；展开后子项 639 / 731 / 823 / 915 / 1007（缩进 148 vs 116 = 16dp）→ 下载 1109 / 控制台 1221 / 资料管理器 1333 / 客户端设置 1445。侧轨初始为 88dp 折叠态是 `rememberSaveable` 恢复了上一会话的形态（踩坑 41），点底部「收起 / 展开」后 164dp 正常。
  - K60 抽屉：首页 535 → 音乐 731 → 书架 927 → 媒体库 1123（「展开」`[903,1076][1071,1244]`）→ 下载 1319 → 控制台 1515 → 资料管理器 1711 → 客户端设置 1907；展开后 电影 1305 / 动漫 1473 / 书籍 1641 / 音乐 1809 / Playlists 1977 → 下载 2159 → 控制台 2355 → 资料管理器 2551 → 客户端设置 2747。
  - 手机底部 4 Tab 顺序不变：首页 / 音乐 / 书架 / 媒体库（K60 `y=3060–3121`，Pad 5 手机形态 `y=2295–2341`）。
- [x] **⑤W7-R3 不回归**：首页顶栏仍是 `ic_logo`（`打开侧栏`）+ 搜索；手机抽屉入口与底部 4 Tab 正常；**音乐页内容区仍是 Prism**（K60：石板 `#151A21` 195,351 点 + 极光青 0）而底栏仍是 Lumen 石墨 `#111319` 21,907 + 极光青 134；控制台页悬浮「返回影阁」胶囊仍在（Pad 5 平板文本 `[2366,1471][2496,1513]`），一次系统返回回主界面；退出后「服务器控制台」行 `#111319`（未选中）、首页行 `#171A21`（选中）。
- [x] **稳定性 / 副作用**：两台设备 logcat 无 `FATAL` / `ANR in` / `Input dispatching timed out`；Pad 5 `wm size/density` reset（Physical 1600×2560 / 360）、`accelerometer_rotation` 1、`user_rotation` 0；K60 未改 wm（Physical 1440×3200 / 560）、自动旋转 1；两台 App force-stop、`/sdcard/w8.xml` 临时文件清理、本地截图与 dump 已删除。
- 未验：**左缘滑出抽屉**（系统手势导航占用，同 W7-R3 踩坑 43）；本波以顶栏可见入口为准。

### W15-UI 验收（2026-10-02，阅读页顶栏避让 + 平板首页海报等宽，分支 `feature/w15-reader-topbar-posters`）

验收命令（全部通过）：`:app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest`（app 49 项，0 失败）。
设备：Pad 5 `43af8627`——平板竖屏 1600×2560@360、平板横屏 2560×1600@360（真实旋转）、手机形态 `wm 1080x2400` + `density 420`（≈411dp）。

- [x] **①平板竖屏「最近添加」3 列等宽（修复验收）**：修复前三张卡片 `[252,1872][626,2496]`（374×624）/ `[685,1872][1113,2524]`（428×652）/ `[1172,1872][1546,2496]`（374×624）；修复后 `[252,1872][644,2523]` / `[703,1872][1095,2523]` / `[1154,1872][1546,2523]` —— **全部 392×651**，列距 59px=26dp，首末列仍精确贴页边线（左 252 / 右 1546，与「全部」「最近添加」标题同线）。
- [x] **②手机形态 2 列等宽（不回归）**：第一行两张 466×772（`[53,1128][519,1900]` / `[561,1128][1027,1900]`），无溢出；修复前两列只是"同样窄"（各减一侧 20dp），修后回到满列宽。
- [x] **③平板横屏 5 列（2560×1600@360）**：`[270,1445][667..]` / `[726..1123]` / `[1182..1578]` / `[1637..2033]` / `[2092..2488]`，宽 397/397/396/396/396（±1px 为 2560px÷5 的像素取整），右缘 2488=页边线。
- [x] **④其它不回归**：修复前后全文本节点对比仅 7 处 bounds 差异——海报行徽标 / 标题随列宽统一的自然位移、走廊末卡裁剪边界从屏边收到页边线（如「只有神知道的世界」右缘 1600→1546）、修复前一处未布局节点 `[0,0]` 转正常采集；页面其余元素（顶栏、hero、区块标题、「全部」按钮 `[1414,204][1483,249]` 等）与基线 bounds 完全一致。
- [x] **⑤稳定性 / 副作用**：Pad 5 logcat 无 `FATAL` / `ANR`；`wm size/density` reset（Physical 1600×2560 / 360）、`accelerometer_rotation` 1、`user_rotation` 0、App force-stop、`/sdcard/w15_*.xml` 清理。
- 备注：修复后横屏 1138dp 下按实际宽度得 5 列（176dp/列）；旧公式按窗口宽度会算 6 列（142dp/列 < 152dp 最小列宽），一并消除。

### W38 品牌波第一段静态验收（2026-10-03，分支 `feature/w38-brand-aurorama`）

**门禁**：根 `assembleDebug`（`:app:phone` + `:app:tv` 两个 APK 全绿）+ `ktfmtCheck` 通过；单测 `--rerun` 后逐模块统计 = **428 项 / 0 失败**（core 16、app:phone 70、modes:book 106、modes:music 99、data 27、player:local 104、modes:film 6；settings / player:core 无测试源）。

**体积（同基线 `35fdea0` 对照，arm64-v8a debug）**：`122,885,391 B (117.19 MiB)` → `124,877,877 B (119.09 MiB)`，**+1.90 MiB**（字体原文件 19.63 MB → 子集 2.91 MB，压缩后入包 ≈1.90 MiB）。文档旧基线 96.95 MiB 是 W2 `674ad8b` 的数值，之后 W31–W37 功能增量（下载 / 离线 / 本地媒体库）已把它抬到 117 MiB，与本波无关。

**字体**：子集字符集 5,715（GB2312 一级 3,755 + 应用文案补齐 1,088 + 拉丁 / 标点 / 箭头等区段）；**汉字缺失 0**；未覆盖字符只落在非中文脚本（韩文 276 / 拉丁扩展 103 / 天城文 58 / 阿拉伯 38 / 希伯来 27 / 箭头与符号 100+），由系统兜底。Noto 官方可变字体默认实例是 Thin(100)，已用 `varLib.instancer wght=400:900 --update-name-table` 收到 Regular；OFL 保留字体名为 `'Source'`，子集字体名 `Noto Sans SC` 不含该词，合规。

**图标**：三套矢量 XML 在本地按 pathData 同源光栅化检查（前景 / 圆形蒙版 / 单色 / `ic_logo` 四张 108–400px 渲染图，临时文件不入库），形体落在 66dp 安全区内、圆形蒙版无裁切；`ic_logo` 在 18dp 档仍为一整块可辨剪影。

**待真机**：字体度量回归（首页 / 媒体库 / 音乐 / 书架 / 设置 / 阅读器）+ 启动图标与主题图标落桌面效果，Pad 5（`43af8627`）主、K60（`8e875894`）抽验——等负责人调度窗口。

### W39 验收（2026-10-03，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，分支 `feature/w39-media-library-polish`）

窗口登记与释放见 `device-lock.md`（04:22–04:37）；服务器只读、未改旋转 / 网络 / 音量；Pad 5 偏好复原（`pref_music_source_filter=LOCAL` / `pref_offline_mode=false` / `pref_local_library_visible=true`），测试本地库（10 本书）原样保留，`/sdcard/w39_*.xml` 已清理。

| # | 项目 | 操作 / 证据 | 结果 |
|---|------|------------|------|
| 1 | 顶栏动作只剩收藏 + 搜索 | Pad 5 dump：`收藏` [1371,97][1425,151] + `搜索` [1470,97][1524,151]（侧轨折叠态在左，无页内搜索框）；K60 dump 同款 | ✅ |
| 2 | 两段结构与首屏 | Pad 5：「本地媒体库」y≈278（右侧「＋ 新建」[1397,290][1510,335]）→「服务器媒体库」y≈627 → 首张 16:9 大卡（`电影` 标签 y≈1337）首屏可见；K60：`test · 书籍 · 0 个文件夹 · 尚未扫描到媒体` + 服务器 `电影` 卡 | ✅ |
| 3 | 本地库卡文案去重 | Pad 5：`书籍 · 1 个文件夹` + 行尾 `10 项`（不再出现「书籍 10」）；空库卡无行尾计数 | ✅ |
| 4 | 收藏入口 | 点顶栏星形 → 收藏页（标题「收藏」）；返回正常 | ✅ |
| 5 | 搜索流程不回归 | 点顶栏搜索 → 展开态输入框在顶部（[198,282][1600,429]），输入 `9` 命中 `9-nine- 支配者的王冠` + 徽标 `13` | ✅（含缺陷修复，见踩坑 55） |
| 6 | 设置项与生效 | 客户端设置 →「媒体库」：`首页显示本地媒体` 开关（summary 一行）；关 → pref `false` + 首页「本地媒体」区块消失；开 → pref `true` + 区块回来 | ✅ |
| 7 | 在线 / 离线一致 | 离线（`pref_offline_mode=true`）媒体库页 =「已下载媒体」+ 同一份「本地媒体库」区块（`书籍 · 1 个文件夹 · 10 项`），无「服务器媒体库」段；退出离线后在线复原 | ✅ |
| 8 | 稳定性 | 双机整轮 `FATAL EXCEPTION` / `ANR in com.zhangwenkang` 0 条（含 2 次安装 / force-stop / 离线切换） | ✅ |

**待真机 / 未覆盖**：W38 品牌波第一段的字体度量回归（首页 / 媒体库 / 音乐 / 书架 / 设置 / 阅读器）仍是独立窗口事项，本轮未做（**W40 已完成**：见下节「W40 字体修正波验收」真机回归表）。

### W40 字体修正波验收（2026-10-03，分支 `feature/w40-misans-font`）

**门禁**：根 `assembleDebug`（`:app:phone` + `:app:tv` 两个 APK 全绿）+ `ktfmtCheck` 通过；单测逐个 `--rerun` 后逐模块统计 = **439 项 / 0 失败**（app:phone 74、core 18（含新增 `MisansFontVariationTest` 2 项）、data 27、player:local 104、modes:film 6、modes:book 106、modes:music 104；settings / setup 与 player:core 无测试源）。

**字体核验（本地）**：官方包内 `MiSans/可变字体/MiSansVF.ttf` = 20,093,424 B；入库文件 SHA-256 `0DDEF906…115E79`；fontTools 读取 `fvar`：唯一轴 `wght` 150–700（默认 330），四档 400/500/600/700 全部在轴范围内；`name` 表仍为官方 `MiSans VF`（未改名 / 未子集化 / 未转格式）。APK 内容实测：`assets/fonts/MiSansVF.ttf`（20,093,424 B、**未压缩存储**，`CompressedLength == Length`）、`assets/licenses/MiSans-License.txt`，`res/font` 仅剩 Literata，`noto` 条目 0；可变轴字符串与 Compose 官方 `toAndroidString` 同构（`'wght' 600`，单引号），2 项单测锁格式。

**体积（arm64-v8a debug）**：`124,877,877 B (119.09 MiB)` → **`144,226,720 B (137.55 MiB)`，+18.45 MiB**。说明：任务书预估 ≈130 MiB 是基于「压缩 asset」；实测 `noCompress += "ttf"`（未压缩）——压缩 asset 会让 `Typeface.Builder(assets, path)` 放弃 mmap、整包读入直接内存（20 MB × 4 档字重），且 API 28 存在取不到 fd 的风险；在「四档共用一份字体文件」的前提下，用 ≈5.2 MB 体积换内存与兼容性（取舍记录见 D40 / 踩坑 56）。

**真机回归（2026-10-03 05:22–05:25，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验；窗口由负责人批准，副作用见 `device-lock.md`）**：双机装机 Success、全程 0 FATAL / ANR（`logcat` 关键词扫描）。

| # | 项目 | 证据 | 结果 |
|---|------|------|------|
| 1 | 首页 / 媒体库 / 音乐 / 书架 | Pad 5 dump：首页 `继续观看 / 接下来 / 本地媒体 / 最新 电影` + 侧轨；媒体库 `共 7 个媒体库` + 收藏 / 搜索 + 本地媒体库 `书籍 · 1 个文件夹 / 10 项` + 服务器 电影 / 动漫 卡；音乐 `专辑 / 艺术家 / 歌曲 / 歌单` + `全部 / 服务器 / 本地` + 迷你播放条；书架 `共 8 本` | ✅ 无裁切 / 错位 / 溢出 |
| 2 | 客户端设置 / 关于 | 设置 9 个分类正常；关于页命中 `本软件使用了 MiSans 字体（© 小米科技有限责任公司）` [425,570][1175,609]（K60 [138,931][1302,992]） | ✅ |
| 3 | 阅读器 | `attention_is_all_you_need`（离线可读 · 2.1 MB）+ 搜索 / 批注 / 分页 / Aa + `分页 · 5/15`，顶栏避让状态栏正常 | ✅ |
| 4 | 字重 400 / 500 / 600 / 700 | 截图目视：600（页面标题 / 库名 / 区块标题）明显重于 400（正文 / 作者 / 时长），500 居中；四档由 `'wght'` 轴映射（单测锁格式 + fontTools 复核轴 150–700） | ✅ |
| 5 | 内存（mmap 效果） | Pad 5 首页 `Native Heap PSS 29,596 KB` / `TOTAL PSS 275,265 KB`——无压缩 asset 整包读入会出现的 ≈+80 MB 原生膨胀 | ✅ |
| 6 | K60 抽验 | 冷启动 988 ms；首页 + 底部 4 tab + 抽屉 + 客户端设置 + 关于注明正常，0 FATAL / ANR | ✅ |
| 7 | 稳定性 / 还原 | 双机 logcat 0 FATAL / ANR；force-stop、`/sdcard/w40*` 清理、未改旋转 / 网络 / 偏好 / 音量 | ✅ |

Pad 5 冷启动 1481 ms（装 137.55 MiB arm64 debug）。launcher 标签 / 图标未动（本波未改图标 / manifest 资源）；观察项：关于页仍用旧 `ic_banner`（Findroid 标识），属品牌波遗留，不在 W40 范围。

### W42 验收（2026-10-03，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，分支 `feature/w42-settings-rail`）

装 arm64 debug（`phone-libre-arm64-v8a-debug.apk`，双机 `install -r` Success）；真机窗口由负责人批准（05:58–06:09），副作用已还原（见 `device-lock.md`）。

| # | 项 | 结果 | 结论 |
|---|----|------|------|
| 1 | 设置页 5 组顺序 / 内容 | 双机 dump 一致：账号与服务器（服务器 / 网络）→ 媒体库（媒体库 / 本地媒体 / 下载与缓存）→ 播放与音乐（播放器 / 音乐 / 桌面歌词 / 恢复播放队列）→ 外观与界面（语言 / 界面 / 外观 / 侧栏显示 / 隐藏底栏）→ 其他（设备 / 离线模式 / 关于）；账号卡「管理用户与登录」承接原「用户」入口 | ✅ |
| 2 | 行样式 / 文案 | 每行 24dp 图标 + 单行描述；实测命中新文案 `选择侧栏要展示的入口` / `悬浮显示当前句与下一句` / `重启后恢复上次队列与进度` / `断网时只显示本机已下载内容` / `管理图片与下载缓存`；`语言→界面` 行距 137px = **60.9dp**（60dp 基线 + 1dp 分隔线），带开关行 63dp（M3 48dp 最小触控目标） | ✅ |
| 3 | 子页入口可达 | 桌面歌词子页（标题 + 开关 + `需要「显示在其他应用上层」权限；打开开关时会引导到系统设置授权。`）；下载与缓存子页合并下载 / 缓存两组；服务器 / 网络 / 媒体库 / 播放器 / 音乐 / 语言 / 界面 / 外观 / 侧栏显示 / 关于均可进入 | ✅ |
| 4 | 侧栏尺寸 / 半透明 | 折叠 72dp（图标轨）/ 展开 150dp（标签齐全）；底色 **#0F1216**（82% #111319 叠页底 #08090C；同坐标不透明板为 #111319）；右缘发丝线 #24262A | ✅ |
| 5 | 选中态 | **3dp 极光青左缘指示条 #5CE1D2**（x=23–29px，约 3dp）+ 雾灰容器 #171A21 + 1dp 细线；未选中图标最亮像素 #A4A5A6（≈白 62%），选中图标 #5CE1D2 | ✅ |
| 6 | 分组 / 底部设置区 | 底部设置区分隔线实测 #1B1E22；「客户端设置」固定在底部。分组间分隔线需管理员账号（测试账号为普通用户，无控制台两项），由 `railGroupBreaks` 单测覆盖 | 🟡（代码 + 单测） |
| 7 | 底栏半透明 | K60 底栏底色 #0F1216（与侧栏同款） | ✅ |
| 8 | 隐藏底栏 | K60（411dp 紧凑）：开 → 底栏标签消失 + 顶栏 logo → 抽屉各页可达 + `am force-stop` 重启后仍隐藏（`pref_hide_bottom_bar=true`）；关 → 底栏恢复 + 偏好 false；Pad 5（平板）：开关 `enabled=false` + 「平板形态使用侧轨，本就没有底栏」 | ✅ |
| 9 | 稳定性 / 还原 | 双机 logcat 0 FATAL / ANR；force-stop、`/sdcard/w42_*` 清理、偏好复原、未改旋转 / 网络 / 音量 | ✅ |

### W43 验收（2026-10-03，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，分支 `feature/w43-search-local`）

装 arm64 debug（`phone-libre-arm64-v8a-debug.apk`，双机 `install -r` Success）；真机窗口 06:45–07:00，副作用已还原（见 `device-lock.md`）。

| # | 项 | 结果 | 结论 |
|---|----|------|------|
| 1 | 入口文案 | Pad 5 展开搜索栏 dump 命中占位符 `搜索媒体库与本地`（EditText `focused=true`） | ✅ |
| 2 | 本地命中（非拼音词） | 搜 `Kindaichi` → `本地` 分区 1 条：`The.Kindaichi.Case.Files 復刻愛藏版_原画.pdf` + 「本地」徽标 + `本地媒体库 · MoonReader · 2.4 GB` | ✅ |
| 3 | 本地打开链路（书籍） | 点该行 → `ReaderActivity` 打开同名 PDF（`分页` 工具条在位） | ✅ |
| 4 | 双分区同屏 | 搜 `e` → `服务器`（14）+ `本地`（3，含 `嫌疑人X的献身…epub`）两分区（滚动后 dump 命中「本地」「MoonReader」） | ✅ |
| 5 | 服务器回归 | 搜 `9` → `服务器` 分区 1 条 `9-nine- 支配者的王冠`（徽标 13），无本地分区 | ✅ |
| 6 | 空态 | 搜 `futuristic` → `未找到「futuristic」` + 提示行 | ✅ |
| 7 | 本地音乐链路 | 临时建「音乐」库（推 1 个 ogg）→ 搜 `w43` → 本地行元数据 `本地媒体库 · W43Music · 0:24 · 1.0 MB`；点行 → media_session `state=3`（PLAYING）+ 自动跳音乐 Tab（迷你条 `0:05 / 0:24 · 正在播放`）；删除库后源文件保留 | ✅ |
| 8 | 本地视频 | Pad 5 本地库均为书籍（W37 测试视频已随验收清理），无新增覆盖；打开链路与 W37 详情页同一段代码 | 🟡（代码复用） |
| 9 | K60 抽验 | 媒体库页本地库 `test`（0 文件夹）→ 搜 `9` 仅 `服务器` 分区、紧凑形态渲染正常；双机 0 App FATAL / ANR | ✅ |
| 10 | 还原 | 临时库 / `/sdcard/Download/W43Music` / `/sdcard/w43_*.xml` 清理、双机 `am force-stop`、偏好未变（`pref_music_source_filter=LOCAL` / `pref_offline_mode=false` / `pref_local_library_visible=true` 为既有值） | ✅ |

### W41 验收（2026-10-03，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，分支 `feature/w41-aurorama-package`）

**门禁**：根 `assembleDebug`（`:app:phone` + `:app:tv` 全绿）+ `ktfmtCheck` 通过；单测 `--rerun` 后逐模块统计 = **450 项 / 0 失败**（app 79 / core 18 / data 33 / player:local 104 / `modes:film` 6 / `modes:book` 106 / `modes:music` 104；与 master 基线一致——本波只改身份配置 / 脚本 / 文档）。

**包名核验**：`aapt dump badging` 双 APK（phone + TV）均为 `package='io.github.zhangwenkang.aurorama.debug'`、label「极光幕 / Aurorama Debug」、`launchable-activity=com.zhangwenkang.cinefin.MainActivity`（类名按 namespace，未变）；合并清单 `package=io.github...`，**无任何 provider authority**（`${applicationId}.androidx-startup` 被 `tools:node="remove"` 移除）。

**双机（窗口 07:33–11:02，装机 / 重登由负责人完成）**：全新安装 10:29（Pad 5）/ 10:32（K60），版本 1.1.0 (33)；冷启动 Pad 5 **1501 ms** / K60 **905 ms**，首页服务器内容正常（继续观看 / 接下来），双机 0 App FATAL / ANR。

| # | 项 | 结果 | 结论 |
|---|----|------|------|
| 1 | 关于页品牌 | Pad 5 截图：`ic_logo` 极光印记（120dp，暗场圆角片底 + 丝带 + 地平线），无 Findroid `ic_banner` 残留；K60 dump：`1.1.0 (33)` + MiSans 注明 | ✅ |
| 2 | SAF 本地媒体库 | Pad 5 媒体库页 =「还没有本地媒体库 · 点「＋ 新建」」→ 旧包授权随 applicationId 失效 | ✅（预期失效，需重新选文件夹） |
| 3 | 内部 Intent 直启 | `am start -n <pkg>/com.zhangwenkang.cinefin.PlayerActivity --es itemId … --es itemKind Episode`：**旧包与新包表现一致**（起播前 Activity 自行结束、0 崩溃、未建立媒体会话），改用正常 UI 路径验证播放 | 🟡（既有现象，非本波回归；见踩坑 63） |
| 4 | 播放 + 媒体通知 | 详情页「继续播放」→ `dumpsys media_session` `state=3`（position 递增）；通知 `pkg=io.github.zhangwenkang.aurorama.debug` id=1001 标题「第 2 集」 | ✅ |
| 5 | 下载链路 | 详情页下载 → `Android/data/io.github.zhangwenkang.aurorama.debug/files/downloads` 文件 25.5 → 45.2 MB 持续增长；下载页「1 进行中 · 0 已完成 · 已占用 132 MB / 0.96 GB」 | ✅ |
| 6 | 下载删除（还原） | App 删除流程（确认对话框）→ 下载页回「0 进行中 / 0 已完成 / 0 失败」，主文件已删；残留 25 kB `.download.js` sidecar（shell / run-as 无权限删） | ✅（残留记踩坑 62） |
| 7 | 离线模式入口 | 客户端设置「离线模式」开关 on→off；`run-as` 读到 `shared_prefs/io.github.zhangwenkang.aurorama.debug_preferences.xml` 的 `pref_offline_mode` true→false（偏好文件随新包名） | ✅ |
| 8 | 旧包卸载 | 双机 `adb uninstall com.zhangwenkang.cinefin.debug` = `Success`，`pm list packages` 仅剩 `io.github.zhangwenkang.aurorama.debug` | ✅ |
| 9 | 还原 | 双机 `am force-stop`、`/sdcard/Download/aurorama-w41-debug.apk` 与 `/sdcard/w41*.xml` 清理、`pref_offline_mode` 归 false、未改旋转 / 网络 / 音量；保留 Pad 5「USB 安装」开关（发布前安装仍需） | ✅ |

**预期失效项与回滚说明**：换 `applicationId` = 新 App 身份——旧包数据（登录态 / 已下载内容 / SAF 本地媒体库授权）不迁移；SAF 需重新选文件夹（本波实测命中，属预期）。**回滚 = 装回旧 APK（`com.zhangwenkang.cinefin`）**：旧包已卸载，其私有数据已随之清除，回滚后需重新登录 + 重新下载 + 重新选择本地文件夹（新包数据同样不会迁回旧包）。

### W44 验收（2026-10-03，分支 `feature/w44-music-detail`，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

- [x] **组件**：core `CinefinSwitch` / `CinefinSlider`（+ `CinefinSliderColors` / `CinefinSliderDefaults` / 2 个纯函数）；全 App `Switch` 统一入口，`androidx.compose.material3.Slider` 使用点清零（含设置 / 下载 / 离线 / 本地库 / 音效 / 阅读器六类页面）
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 `--rerun` **462 项 / 0 失败**（core 18 → 25 新增 `CinefinSliderMathTest` 7 项，app 79 / data 33 / player:local 104 / film 6 / book 106，music 104 → 109 新增 `MusicSwipeGestureTest` 5 项）
- [x] **真机像素取证**（Pad 5，`screencap` 原图采样）：开关关闭态 thumb `(167,176,189)` = `onSurfaceVariant #A7B0BD` / 轨道 `(34,42,54)` = `surfaceContainerHigh #222A36` / 描边 `(110,120,135)` = `onSurfaceFaint #6E7887`；thumb 对轨道 **6.6:1**、对面板底 **7.74:1**（旧 M3 默认 `outline #2C3542` 对底 1.37:1）；Lumen 皮肤关闭态 `#98A2B3` / `#171A21` / `#6B7483`（7.21:1）；禁用态（隐藏底栏行）拇指 `#6B7483` 对轨道 3.69:1 仍可辨；开启态 Prism 松石 `#3FC9A0`、Lumen 极光青 `#5CE1D2` 命中
- [x] **滑杆真机**：EQ 五段 + RG 覆盖 + 阅读器全部圆点拇指（拇指 `(244,241,234)` = `onSurface`，已填充轨 `(63,203,161)`）；拖动 `+0.0 → +3.7 dB` 实时生效、松手写 `pref_music_eq_custom_bands`；RG 覆盖拖动 `+7.0 dB` → `files/replaygain/<itemId>.txt` 落盘 → 「清除本机覆盖」文件消失；EQ 关闭时五段 40% 透明仍可辨
- [x] **手势真机**（音乐线 D56）：全屏左滑进歌词 / 右滑留空（队列不再被左滑打开）/ 下滑关闭；歌词页右滑返回 / 下滑返回；「播放队列」按钮打开队列面板不回归；K60 抽验同款
- [x] **稳定性 / 还原**：双机 0 `FATAL EXCEPTION` / 0 ANR；双机 force-stop、`/sdcard/w44*` 清理、EQ / RG 覆盖复原、设置开关保持原值（详见 `device-lock.md`）

### W45 验收（2026-10-03，分支 `feature/w45-local-covers`，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

- [x] **四条封面链路（像素取证）**：自建素材（600×800 深蓝 PDF / 深绿 CBZ / 含深红封面的最小 EPUB / Big Buck Bunny 360p 10s mp4），Pad 5 取回 `files/local_thumbs/*.jpg` 用 PIL 读数——PDF 首页 `384×512 mean=[30,59,89]`、CBZ 第一张图 `384×512 mean=[20,111,70]`、EPUB `384×512 mean=[140,30,29]`、视频首帧 `512×288 mean=[90,105,57]`，逐条与素材底色对应；长边均 ≤512（JPEG）
- [x] **懒生成 + 并发**：清缓存后只进首页（不进详情）即生成 3 张库卡封面（三库各 1 张，未全量刷图）；125 项库滚动一圈后缓存 65 张（仅可见行生成）
- [x] **首页卡片化**：本地库卡 `content-desc=W45Mix [833,1945][1391,2259]` = 558×314px（16:9，与「继续观看」卡同宽同高同 pitch）+ 类型角标 `混合` + 库名 + `125 项 · 混合`；封面像素采样 `[66,79,57]`（叠底渐隐后的视频帧，回退态为 ≈`[26,31,39]` 中性底）→ 真图显示
- [x] **性能**：Pad 5 125 项混合库首轮滚动 762 帧 / 51 janky（6.69%）/ p50 7ms / p90 17ms / p99 34ms（含现场生成）；缓存命中后第二轮 759 帧 / **4 janky（0.53%）** / p50 7ms / p90 9ms / p99 14ms（`dumpsys gfxinfo`）
- [x] **列表行 / 详情头部 / 回退**：详情页视频行缩略图 40dp（`content-desc=视频 [90,1950][180,2040]`）、详情头部 140dp 通栏封面；音乐无内嵌 / 同目录封面时回退类型图标
- [x] **真机拦下并修复**（踩坑 67）：新建库「0 项」阶段请求封面 → null 永久缓存（表现为「库卡封面要进一次详情页才出现」）；改为「空库不请求 + 按条目数变化重试」后清缓存重启、仅首页复验三库全部出图
- [x] **K60 抽验**：装机 + 新建混合库（5 项）+ 库卡类型角标 + 详情行缩略图正常，0 FATAL / ANR
- [x] **还原**：双机删除测试库（源文件保留）、删除 `/sdcard/Download/W45Media|W45Fresh` 与 `/sdcard/w45_ui*.xml`、`run-as` 清空 `files/local_thumbs`、`am force-stop`；未改偏好 / 旋转 / 网络 / 音量（`device-lock.md`）

### W46 验收（2026-10-03，分支 `feature/w46-nav-settings`；静态 / 门禁部分）

- [x] **门禁**：根 `assembleDebug`（含 TV：`app:phone` + `app:tv`）BUILD SUCCESSFUL；`ktfmtCheck` 全模块通过；单测 `--rerun` **471 项 / 0 失败**（app 79 / core 25 / data 42 / player:local 104 / film 6 / book 106 / music 109）
- [x] **A 侧栏静态核对**：展开宽度两处 = 168dp（`CinefinSideRail` / `CinefinSideNavigation`）；`RailTranslucency = 0.74f` 只被侧轨 / 抽屉引用，底栏仍 `ChromeTranslucency = 0.82f`；选中容器 `panelElevated.copy(alpha = 0.74f)`
- [x] **A 文案预算**：`NavLabel` = 16sp；168dp 行内文字可用宽度 = 168 − 2×10（侧栏内边距）− 2×14（行内边距）− 24（图标）− 12（间距）= **84dp** > 最长静态文案「客户端设置」≈80.5dp；「媒体库」行含 20dp 箭头 → 64dp > ≈48.5dp
- [x] **B / C 静态核对**：四个一级页顶栏入口 = `ic_logo` 24dp + `content-desc="打开侧栏"`；`openDrawer` 在非 Compact = null；`gesturesEnabled = showNavigation && compactNavigation`；条目集合 / 排序 / 门控未动（`NavigationIaTest` 全绿）
- [x] **D 静态核对**：`settings_category_music` / `settings_music_summary` 全仓 0 引用；「媒体库」子页按声明顺序输出首页 / 书架 / 音乐库三项（`SettingsGroupLayoutTest` 覆盖分桶与组内顺序）
- [x] **真机（已完成）**：W46 合并验收（负责人，`device-lock` 14:02–14:08）已逐项取证；W47 回归（2026-10-03）再次复验 Pad 5——展开态侧轨 168dp 文案完整（首页 / 音乐 / 书架 / 媒体库 / 收起 / 电影 / 动漫 / 其他 / 书籍 / 书籍3 / 音乐 / 音乐测试 / Playlists / 下载 / 客户端设置）、收起展开正常、顶栏无重复入口；K60 顶栏 logo 入口正常。

### W47 验收（2026-10-03，分支 `feature/w47-full-regression`，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

- [x] **设置 / 导航冒烟**：Pad 5 客户端设置分组（账号与服务器 / 网络 / 媒体库 / 下载与缓存 / 播放与音乐 / 主题与取色 / 离线模式）逐页正常；下载页 0/0/0 + 25.01 kB / 53.68 GB；搜索「w47」本地分区命中 3 条（W47Media 库，带「本地」徽标）——搜索含本地链路不回归。
- [x] **Compact 自由窗口（W17/W20 遗留取证）**：`enable_freeform_support=1` + `am start --windowingMode 5` + `am task resize 0 0 1000 1600`（444dp 宽）→ `mode=freeform` 下播放器工具行纯图标无文字标签（`content-desc` 齐全）；造法与还原见踩坑 71。
- [ ] **未覆盖**：W46 的 74% 透明像素采样与「左缘右滑不出抽屉」由负责人 W46 验收负责（本轮以文字 / 结构复核，不重复像素）。

### W49 验收（2026-10-03，分支 `fix/w49-leftover-cleanup`，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

- [x] **阅读器滑杆零宽回归修复（P0，真机拦下）**：修前 `uiautomator dump` 无 `android.widget.SeekBar`、截图该行无轨道像素（宽度 0，不可见 / 不可触摸）；修后双机各有 3 条 `SeekBar`（Pad `[436,1277][954,1358]` = 230dp、K60 `[413,1210][1041,1336]` = 209dp）。
- [x] **触摸 / 键盘步进**：Pad 字号 tap 位置 `100%` → 中点 `200%`（K60 `160%`）→ 还原 `100%`；TAB 聚焦 `SeekBar` 后方向键一次 = 一档（字号 200% → 201%，步长 1.8%）。
- [x] **EQ / ReplayGain 不回归**：EQ 五段触摸 `+0.0 → +7.3 dB`（键盘每次 +0.2 dB，1% 区间）；ReplayGain 覆盖键盘每次 **+0.5 dB**（steps=47）；`关闭态` 语义为 `ProgressBar`（无 `setProgress`）、开启后转 `SeekBar`；收工 EQ 五段回 +0.0 dB、覆盖清除（未设置）。
- [ ] **未覆盖**：TalkBack 实机走查（本轮以 a11y 树里 `SeekBar` + `ProgressBarRangeInfo` / `setProgress` + 单测为准）；`CinefinSwitch` / 播放页 `MusicProgressBar` 视觉参数同源只做了代码级核对（像素采样沿用 W44 / W45 记录）。

### W52 验收（2026-10-03，分支 `feature/w52-downloads-redesign`；静态 / 门禁部分）

- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 `--rerun` **507 项 / 0 失败**（app 84 / core 37 / data 45 / player:local 105 / film 14 / book 113 / music 109，W52 净增 11）。
- [x] **排版静态核对**：手机海报 96×144dp / 行高 ≈148dp（容器卡文本列 = 标题 20 + 详情 20 + 聚合 20 + 进度 4 + 操作行 44 + 间距 16 + 内边距 24 = 148dp）；平板海报 104×156dp + 折叠容器两列；剧集缩略图 112×63dp（16:9）。
- [x] **纯函数单测**：容器聚合速度 / ETA（`DownloadHierarchyBuilderTest` ×2）、体积 / 速度 / 剩余 /「x/y」/ 百分比文案（`DownloadFormatRulesTest` ×6）、平板成行规则（`DownloadGridGroupingTest` ×3）。
- [ ] **真机（待调度）**：手机像素采样、平板两列、下载中聚合速度与剩余时间、三组与媒体筛选、空态与骨架、旧组件重绘、多任务通知进下载页、0 FATAL·ANR —— 清单见 `DOWNLOAD_PLAN` §19.5。
### W53 验收（2026-10-03，分支 `feature/w53-video-entry`；静态 / 门禁部分）

- [x] **门禁**：根 `assembleDebug`（含 TV：`app:phone` + `app:tv`）BUILD SUCCESSFUL；`ktfmtCheck` 全模块通过；单测 `--rerun` **505 项 / 0 失败**（W50 基线 496 + W53 净增 9；app 90 / core 37 / data 45 / player:local 105 / film 6 / book 113 / music 109）
- [x] **导航 IA 静态核对**：`navEntryKeys` 顺序 = 首页 / 视频 / 音乐 / 书架 / 媒体库 / 下载（+ 管理员两条 + 客户端设置）；视频门控与音乐 / 书架同规则（无库隐藏 / 未就绪可见）；`bottomNavKeys` = 首页 / 视频 / 音乐 / 书架（媒体库不在底栏、仍在 `navKeys`）；`visibleRailKeys` 视频开关独立可关；`railGroupBreaks` 分界随顺序移动（单测 `setOf(5)`）
- [x] **设置项静态核对**：`pref_ui_sidebar_show_video` / `pref_ui_video_display_mode` 两个新键（默认 `true` / `cards`，既有键未重排）；「侧栏显示」子页顺序 首页 → 视频 → 媒体库 → 音乐 → 书架 → 下载 → 控制台 → 资料管理；「媒体库」子页在首页 / 书架 / 音乐库三项后追加「视频显示方式」；`SettingsGroupLayoutTest` 不受影响（嵌套项不进顶层分桶）
- [x] **视频页静态核对**：`pickVideoLibraries` 只放行 movies / tvshows；聚合游标 4 种推进路径（同库续取 / 短页换库 / 空库跳过 / 末尾结束）；聚合分页 `prevKey = null`（只向后翻）、`getRefreshKey = null`（刷新从头）；空态两处（无视频库、聚合空）、骨架两处（库卡 / 条目）、错误两处（库列表 / 分页）均有重试入口
- [x] **真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-03 19:02–19:26）**：①Pad 侧轨顺序 首页 / 视频 / 音乐 / 书架 / 媒体库 / 下载，视频行选中态像素取证 = 极光青 `rgb(92,225,210)` 指示条（x 23–28 / y 330–384）；②侧栏「视频」开关即时生效（关 → 侧轨条目消失；开 → 回位）；③库卡列表（电影 / 动漫）点卡进库（电影库页「共 17 个项目」）；④「视频显示方式」默认库卡列表；切「聚合列表」后页面即时变为电影 + 剧集混合网格（滚动可见剧集未看角标 40 / 26 / 12 / 28，连续加载），收工已还原默认；⑤K60 底栏 4 Tab = 首页 / 视频 / 音乐 / 书架（可点区 `[0,2920][1440,3144]` 四等分，媒体库已移出底栏）、视频 Tab 选中态像素取证（x 498–580 / y 2944–3048）、顶栏入口 = app 图标（`content-desc`「打开侧栏」）、抽屉含「视频」条目；⑥离线模式（真机拦下并修复后复验）= 视频页空态「暂无视频库」，退出离线模式后库卡恢复；⑦0 FATAL / ANR（双机 crash buffer + main log 过滤为空）
- **备注（既有行为，非 W53 引入）**：库卡「共 N 个项目」读服务器 `ChildCount`，实测同一库在不同时刻 / 页面取值浮动（电影 1–8、动漫 1–9），与媒体库页同组件同数据源；库内容页按分页真实计数（电影 17）。Pad 测试中出现一次 MIUI 小窗 + 放大镜叠加窗口（`com.xiaomi.mirror`）挡住 uiautomator，force-stop + 重启恢复（未改无障碍设置）。

### W53 实机 Bug A/B 复验（2026-10-03 20:46–20:56，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验；device-lock 已写释放与结论）

- [x] **Bug A（手机底栏「视频」切不回来）**：K60 两条路径均通过——①抽屉选「书籍3」→ 点底栏「视频」= 视频页首屏；②视频页 → 点「电影」库卡进库 → 点底栏「视频」= 视频页首屏（此前停在库内容页）
- [x] **Bug B2（「书籍3」仍显示「书籍」）**：K60 抽屉选「书籍3」→ 标题「书籍3」+「共 8 个项目」+ 书目一致；Pad 侧轨选「书籍3」→ 同样命中（修复前双机都会落回「书籍」）
- [x] **Bug B1（「音乐测试」仍显示「音乐」）**：K60 抽屉选「音乐测试」→ 顶栏「音乐测试」+「共 0 张专辑」+ 空态；Pad 侧轨同样命中；随后点底栏「音乐」→ 「音乐 / 共 105 张专辑」（Tab 参数不被库入口顶掉）
- [x] **库子项项目数**：抽屉（K60）「电影 4 项 / 动漫 9 项 / 其他2 3 项 / 书籍 3 项 / 书籍3 9 项 / 音乐 2 项 / 音乐测试 1 项 / Playlists 3 项」（数值随服务器 `ChildCount` 浮动，同既有口径）；侧轨（Pad）同样显示（`ChildCount` 浮动：电影 7 / 动漫 8 / 其他2 3 / 书籍 1 / 书籍3 8 / 音乐 2 / 音乐测试 2 / Playlists 6）
- [x] **0 FATAL / ANR**（双机 crash buffer + main log 过滤为空）；副作用已还原（双机 force-stop、`/sdcard` 临时文件清理、未改设备偏好）
- 备注：侧轨展开 168dp 下 4 字库名（音乐测试 / Playlists）会省略成「音乐测…」——项目数是用户确认项，最终呈现（名称截断 vs 项目数）待用户拍板

### W56 验收（2026-10-03，分支 `feature/w56-unwatched-badges`，起点 master `97e1cb6`）

- [x] **数据口径核验（只读接口探针，服务器 10.11.8 + 官方 OpenAPI）**：`UserItemDataDto.UnplayedItemCount`（int32 可空）只在 `UserData` 下返回；`/Items`（媒体库网格 / 搜索 / 聚合）、`/Shows/{id}/Seasons`、`/Items/Latest`、`/Suggestions` 对 Series / Season 有值（13 / 48 / 40 / 370…），带不带 `enableUserData=true` 结果一致；Movie / Episode / `/Views` 恒空；NextUp / Resume 只有 `played`
- [x] **纯函数单测**（`ItemStatusBadgeTest` 7 项）：Series / Season / Folder 未看数 `>0` → `UnplayedCount`、`0` / `null` → `None`；**完全看完的整剧不打勾**；Movie / Episode 已看 → `Played`、未看 → `None`；文案 `1 / 99 / 100 / 4096` → `1 / 99 / 99+ / 99+`
- [x] **视觉静态核对**：四个卡片的徽标仍在封面右上角、与下载徽标同排（`Arrangement.spacedBy(Space2)`）；仍走 `BaseBadge`（黑 62% 底 + 白 12% 描边）；无新增配色 / 字体 / 位图
- [x] **门禁**：根 `assembleDebug`（含 TV：`app:phone` + `app:tv`）BUILD SUCCESSFUL；`ktfmtCheck` 全模块通过；单测 7 任务 `--rerun` **523 项 / 0 失败 0 错误**（app 100 / core 37 / data 45 / player:local 105 / film 14 / book 113 / music 109）
- [x] **真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-03 21:21–21:37，device-lock 已写释放与结论）**
  - ① **剧集未看数**：动漫库网格（Pad）逐条 = 服务器 `UserData.UnplayedItemCount`——9-nine- 支配者的王冠 13 / 巴哈姆特之怒 Manaria Friends 10 / 宝石幻想：光芒重现 12 / 冰海战记 48 / 超次元游戏 海王星 13 / 超级索尼子 12（K60 抽验 13 / 10 / 12 同值）
  - ② **已看打勾 / 未看不加角标**：`奇异太郎少年的妖怪绘日记` 特别篇 S0E13（played=true）缩略图右上 = 黑 62% 胶囊 + 白勾；同布局的第 1 季 12 集（全未看）同位置无角标（同屏对照取证）；电影库 17 部（服务器 0 已看）无任何角标；「特别篇」季卡（0 未看）不显示未看数
  - ③ **`99+`**：银魂（服务器 370）徽标文案 = `99+`（同屏其余 23 / 24 / 13 / 31 / 39 / 12 均为两位数）
  - ④ **四处一致**：库网格（动漫，见 ①）/ 首页走廊（学生会的一己之见 23、超能力女儿 12，与接口一致；「最近添加」海报墙为电影 = 无角标）/ 搜索（输入 `9` → 9-nine- 支配者的王冠 13）/ 视频页（库卡列表 = 库名 +「共 N 个项目」无未看角标；聚合列表 超次元游戏 海王星 13）
  - ⑤ **0 FATAL / ANR**：双机 `logcat -b crash` 与 main `FATAL EXCEPTION|ANR in` 过滤均为空

### W54-B 验收（2026-10-03，分支 `feature/w54-lib-header`，起点 master `ce58847`；静态 / 门禁部分）

- [x] **头部共享一份实现**：视频库 / 书籍库 / 书架三处都走 `LibraryScreen`（`BookshelfScreen` 传 `topLevel = true`），头部组件落在 `presentation/film/components/LibraryContentHeader.kt` + `SortByPanel.kt`，仓库里没有第二份实现
- [x] **tabs 按库类型出现**（纯函数 `libraryTabs` + 单测）：电影 / 剧集 / 图书 / 家庭视频 / 音乐 / 合集 / 混合 / 文件夹 / 播放列表逐类型断言；「库名」永远第一项；剧集库才给「即将播出」「剧集」
- [x] **工具行**：计数文案 `1-94 / 94`（未知总数 → 只显示已加载条数；空库不出文案）、网格 / 列表切换（`LazyVerticalGrid` / `LibraryListRow` 懒列表）、排序（工具行 `ic_arrow_down_up` 图标 → `SortByPanel` 底部面板）、筛选 funnel（`LibraryFilterPanel`：全部 / 未看 / 已看 / 收藏，书籍库读作未读 / 已读）
- [x] **生效筛选可清除**：funnel 选中的筛选与从「类型」/「制片发行商」点进来的库内过滤都在工具行下方显示成可点清除的 chip
- [x] **下拉刷新（真实重取）**：`PullToRefreshBox` → `LazyPagingItems.refresh()` 重发分页请求 + `LibraryAction.Refresh` 重取计数与当前 tab，非纯动画
- [x] **无新增配色 / 字体 / 位图**：仅 core 新增 3 枚描边矢量图标（非位图），颜色全部取语义 token / 当前域媒体色
- [x] **门禁**：根 `assembleDebug`（含 TV）BUILD SUCCESSFUL；`ktfmtCheck` 全模块通过；单测 7 任务 `--rerun` **544 项 / 0 失败 0 错误**（app 105 / core 37 / data 45 / player:local 105 / film 27 / book 113 / music 112；基线 531 + 净增 13）
- [ ] **真机**：tabs 切换 / 计数文案 / 列表视图 / 排序与筛选面板 / 下拉刷新 / 家庭视频缩略图（修 `ItemPoster` 后）待设备窗口（本会话未申请真机）

### W54-C 验收（2026-10-03，分支 `feature/w54-video-page`；静态 / 门禁部分）

- [x] **纯函数单测**（`VideoLibrarySelectionTest` 5 项 + `BookshelfPickTest` 3 项）：未选择 → 全部库；选中 → 只显示该库；库被删 / 坏值 → 回落「全部库」（书架 = 回落「自动」）；偏好映射 round-trip 与空白 / 非法值；书架选项只含 books 库且保持服务器顺序
- [x] **门禁**：根 `assembleDebug`（含 TV：`app:phone` + `app:tv`）BUILD SUCCESSFUL；`ktfmtCheck` 全模块通过；单测 7 任务 `--rerun` **539 项 / 0 失败 0 错误**（master `ce58847` 基线 531 + 新增 8）
- [x] **视觉静态核对**：chip 走 `CinefinFilterChip`（未选 = 中性色；选定 = 当前域媒体色容器 + `Media.Outline` 描边），菜单项行尾 `ic_check`，容器 = `surfaceContainerHighest` + 0 elevation（§5.3 / §8.11）；收藏复用 `ic_heart` / `ic_heart_filled`；睡眠图标为矢量（无新增位图 / 配色 / 字体）
- [ ] **真机（待窗口）**：视频页两模式选库（即时 + 重启保留 + 库卡过滤 / 聚合只出所选库）、书架多书库切换（含「自动」）、收藏点亮 / 熄灭并回读服务器、睡眠占位对话框、0 FATAL / ANR

### W51 验收（2026-10-03，分支 `feature/w51-download-granularity`，起点 master `ce58847`；静态 / 门禁部分）

- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 7 任务 `--rerun` **538 项 / 0 失败 0 错误**（app 106 / core 37 / data 45 / player:local 105 / film 20 / book 113 / music 112）
- [x] **纯函数单测**：`DetailDownloadRulesTest` 6 项（单集三态 / 容器聚合 / 批量保序去重 / 默认上限与溢出 / 不限上限 / 全跳过）+ `DownloadTaskRulesTest` 活动队列状态 1 项
- [x] **静态核对**：三标签键 = Outlined 48dp 触控高 + 选中态（`Media.Container` / `Media.Outline` / `Media.Bright`，Lumen 下自动极光青）；Snackbar = `InverseSurface` 底 + `InverseOnSurface` 字 + 圆角 12 + 高 ≥52 + 宽 ≤480 + 无投影；角标 = `OnSurface` 底 / `Surface` 字（中立，不占媒体色）；无新增配色 / 字体 / 位图，无 `Color(0x…)` 字面量
- [x] **红线**：仅 `NavigationRoot.kt`（角标接线，已申报）；`AppPreferences.kt` 零改动
- [ ] **真机验收**：待负责人设备窗口 —— 清单见 `DOWNLOAD_PLAN` §20.5（Show 整剧 / Season 全季 / Episode 本集三态 + 批量跳过 + 角标 + 三设置 + 乐观回滚）

### 三波合并真机走查（2026-10-03，负责人，Pad 5 `43af8627`；master `9fcea57`）

- **W54-B（通过）**：书架页头部完整——tabs（书籍 / 建议 / 类型，按库类型出现）、工具行「1-8 / 8」+ 网格 / 列表 + 排序 + 筛选；排序面板（升序 / 降序；标题 / IMDB 评分 / 家长分级 / 加入日期 / 播放日期 / 发行日期）与筛选面板（全部 / 未读 / 已读 / 收藏，书籍库文案正确）均可开；列表视图渲染正常；**下拉刷新为真实重取**（swipe 后 `GET /Items?...` 网络请求 ×2）。
- **W54-C（通过）**：视频页「全部库」chip → 菜单（全部库 / 电影 共 4 / 动漫 共 7）→ 选「电影」后库卡过滤为 1 张、副题「电影库 · 共 4 个项目」、出现「收藏当前媒体库」；收藏 toggle 点亮 → 服务器回读 `IsFavorite=true` → 再点 → 回读 `false`（已复原）；睡眠入口 → 占位对话框「睡眠定时将在下一版提供」；书架页有「自动」书库 chip、无睡眠入口（按设计）。
- **W51（部分通过）**：三层动作排「下载 / 已播放 / 喜欢」就位；单集下载 Snackbar「已加入下载队列」+ 按钮变「已在队列」；侧栏下载角标 = 1 → 删除后消失（0 隐藏）；「下载与缓存」三项设置 UI 就位（仅 Wi-Fi 下载 / 同时下载数 = 2（1–3）/ 下载完成通知）。**真机拦下缺陷**：Show 详情页「下载」提示「没有可下载的剧集」——服务器该剧 `Episodes` 返回 12 集、每集 1 个 `MediaSource`，属客户端取集 / 过滤把目标筛成空（已打回 W51 会话，开 `feature/w51b-show-download-fix` 修复）。
- **0 FATAL / ANR**（crash buffer + main 过滤为空）。**未覆盖**：K60 抽验、家庭视频库（「其他2」）缩略图修复效果（需截图比对）、平板两列 / 聚合模式下的选库表现。
- **后续拍板（用户 2026-10-03 晚）**：视频库卡「中间大字」= **保持现状**（那是服务器库封面图自带的内容，客户端不改；不做中性面板替换）。
### W53B 验收（2026-10-03，分支 `feature/w53b-local-libs`，起点 master `9fcea57`；静态 / 门禁部分）

- [x] **纯函数单测**（`SidebarLocalLibraryTest` 6 项）：库级「在媒体库显示」关闭的行不出现且保持仓库顺序 / 全关或 0 库返回空（整组隐藏）/ 名称 + 项目数都放得下才显示项目数 / 两者放不下（含差 1dp）与名称本身放不下都省略项目数 / 68dp 尺寸链与侧轨几何一致
- [x] **门禁**：根 `assembleDebug`（含 TV：`app:phone` + `app:tv`）BUILD SUCCESSFUL；`ktfmtCheck` 全模块通过；单测 7 任务 `--rerun` **565 项 / 0 失败 0 错误**（基线 559 + 净增 6：app 120 / core 37 / data 45 / player:local 105 / film 33 / book 113 / music 112）
- [x] **静态核对（不新增配色 / 字体 / 位图）**：子分组标题 = `CinefinType.LabelSmall` + `onSurfaceFaint`（与抽屉分组标题同款）；分隔 = 既有 `CinefinRailGroupDivider`；行 = 既有 `CinefinNavigationItem` / `CinefinNavItem`（紧凑二级行 + 类型图标 + 项目数尾标）；本地库卡缩略图 = 既有 `LocalThumbnailTile`（`surfaceContainerHigh` 底 + 类型图标回退），无 `Color(0x…)` 字面量
- [x] **规则核对**：库级「在媒体库显示」关闭 → 侧栏不出现（与媒体库页「眼睛」管理视图互不影响）；0 个本地库 → 标题 / 分隔 / 行都不渲染；抽屉分组仍是同一份 `drawerEntries` 的连续切片 → 拍平顺序与动作列表同源（踩坑 17 不复发）
- [ ] **真机（待窗口）**：Pad 5 侧轨展开 → 服务器库下方「本地媒体库」子分组（分隔线 + 标题 + 名称 + 项目数）→ 点库进详情 + 子项高亮、返回回原页；关「在媒体库显示」后该行即时消失、0 库整组隐藏；本地库卡缩略图 16:9（≈100dp 宽）与服务器库卡不变；抽屉同款；72dp 折叠轨不出现子项；0 FATAL / ANR

### W54-D 验收（2026-10-03，分支 `feature/w54-home-settings`，起点 master `9fcea57`；静态 / 门禁部分）

- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 7 任务 `--rerun` **569 项 / 0 失败 0 错误**（app 124 / core 37 / data 45 / player:local 105 / film 33 / book 113 / music 112；基线 559 + 净增 10）
- [x] **纯函数单测**：`HomeLibrarySettingsTest` 8 项（存储顺序优先 + 新库追加 / 上下移动与边界 no-op / 逐库开关映射默认全开 / 默认分页按库类型与非法值回落 / 分页映射与顺序编解码容错）+ `HomeViewAllRouteTest` 2 项（同库参数 + `DateCreated` 倒序）
- [x] **bug ① 核对**：`HomeScreenLayout` 两处走廊 + `HomeView`「全部」统一走 `HomeAction.dispatch`，`OnLibraryClick` 不再被丢；导航落点 `homeViewAllRoute()` → 库内容页 + 初始排序（不写偏好）
- [x] **bug ② 核对**：`HomeViewModel#loadViews` 不再读 `pref_ui_home_library_id`、`HomeScreen` 不再 `take(3)`；逐库开关（隐藏集合默认空）+ 设置里的媒体库顺序决定走廊集合与次序
- [x] **静态核对**：只用既有配色 / 字体 / 位图 token（新图标 0 枚，复用 `ic_chevron_up/down`）；设置行沿用 `SettingsRow` 60dp 行高 + 44dp 控制位、开关走 core `CinefinSwitch`、对话框复用 `SettingsOptionsDialog` / `BaseDialog`
- [x] **红线 / 迁移**：`AppPreferences.kt` 删 `pref_ui_home_library_id` / `home_latest` + 加 8 键（已申报）；`NavigationRoot.kt` 加 `LibraryRoute.sortBy/sortOrder` + 「全部」接线（已申报）；迁移说明见 D61 ⑥
- [ ] **真机验收**：待负责人设备窗口 —— 清单：继续观看 / 阅读 / 收听三条走廊（含空数据自动隐藏）、最近添加×3、逐库开关与媒体库顺序上下调整、默认分页落盘（重开设置保留）、首页「全部」进库内容页且默认「最近添加」排序、0 FATAL / ANR

### W51b 验收（2026-10-03，分支 `feature/w51b-show-download-fix`，起点 master `9fcea57`、已 rebase 到 `233b387`；Pad 5 `43af8627`）

- [x] **根因（只读探针）**：`GET /Shows/{id}/Episodes?Fields=CanDownload,MediaSources,Overview` → 12 集、`CanDownload` 非空、`MediaSources.Count=1`；`Fields=Overview` → 12 集、两者全 null（按需字段未请求）。
- [x] **修法**：`DetailDownloadRules.EPISODE_FETCH_FIELDS` 统一取集字段；`downloadTargets()` 改「`!missing && sources.isNotEmpty()`」；`DownloaderImpl.downloadItem` 条目快照前移到 `insertSource` 之前（消除竞态）。
- [x] **单测**：`DetailDownloadRulesTest` +2（媒体源入选 / 取集字段锁定）；film 33 → 35。
- [x] **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 任务 `--rerun` 起点 **561 项 / 0 失败 0 错误**（app 114），rebase 到 master `233b387`（并入 W53B / W54-D）后复跑 **577 项 / 0 失败 0 错误**（app 130 / core 37 / data 45 / player:local 105 / film 35 / book 113 / music 112）；`9fcea57 → 233b387` 对本波下载 / 详情页文件零 diff，真机结论同样成立。
- [x] **真机（Pad 5 `43af8627`，23:12–23:32，device-lock 已写释放与结论）**：Season 全季「将加入 11 集 / 已跳过 … 队列中 1 集」→ Snackbar「已加入下载队列 · 11 集」；Show 整剧「将加入 12 集」→ Snackbar「已加入下载队列 · 12 集」；下载页 `12 进行中 · 0 失败`、侧轨角标 12；单集下载回归 → 角标 1、`1 进行中 · 0 失败`；修复前同机同操作 11 失败（FILE_ERROR）对照；0 FATAL / ANR / FILE_ERROR；测试下载（12+11+1）已删除、偏好与临时文件未变。
- [ ] **遗留**：进行中批量仍显示逐集容器（完成后才聚合）；批量入队按剧集重复拉 show/season 快照（约 10 s）。

### W51b + W53B + W54-D 联合真机走查（2026-10-04，负责人，master `1bc026b`）

- **设备 / 时长**：Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，23:47–00:01（device-lock 已写释放与结论）。
- **W51b（通过）**：Show 详情页「下载」→ 确认框「下载整剧 / 将加入 12 集 / 单次上限 100 集」→「加入下载队列」→ 按钮转「已在队列」（极光青选中态），重复点击 Snackbar「所有剧集都在队列」；侧栏下载角标 =「12 个活动下载」；下载页「12 进行中 · 1 已完成 · 0 失败」；测试下载已逐条删除至 0。
- **W53B（通过）**：Pad 5 侧轨「媒体库」组内、服务器库之后 = 细分隔线 + 标题「本地媒体库」+ 库行（名称完整，168dp 下项目数按规则让位）；点击进本地库详情；「在媒体库显示」关 / 开 → 侧轨子分组即时消失 / 恢复；K60 抽屉同款（标题 + 库名 +「0 项」）。
- **W54-D（通过）**：首页三条继续走廊（「继续收听」无数据自动隐藏）+ 接下来 +「最新 电影 / 动漫 / 其他2」（各带「全部」）+「最近添加 · 书籍 / · 音乐」；「全部」点击进电影库内容页（tabs +「1-17 / 17」+ 条目顺序与「最新」走廊一致 = 最近添加倒序）；设置「界面 → 首页」= 七个模块开关 +「首页媒体库」组（在首页显示 + 默认分页 + 媒体库顺序）；K60 首页与底栏正常。
- **0 FATAL / ANR**：双机 crash buffer + main 过滤为空。
- **未覆盖**：K60 的 Show 整剧下载、默认分页改值后的重开持久化（只看到默认值「库内容」）、0 个本地库时整组隐藏（K60 有 1 个 0 文件夹本地库，按「库存在即显示」呈现）。

### W55 睡眠定时统一验收（2026-10-04，分支 `feature/w55-sleep-timer`；静态 / 门禁部分）

- **范围**：视频页顶栏睡眠入口（W54-C 占位 → 正式对话框）+ 播放器睡眠键 / 面板 + 音乐顶栏月亮 / sheet / 底栏倒计时全部接 `player:local` 进程级 `SleepTimerController`；共享 UI = core `CinefinSleepTimerOptions`（预设 10 / 20 / 30 / 60 + 自定义 1–240 分钟滑块）；到点暂停当前活跃播放（音乐 / 视频互斥，含后台 / 熄屏）。
- **静态核对**：视频页对话框标题 / 文案走 core 字符串（默认 / zh-rCN / zh-rTW），无新增配色 / 字体 / 位图；播放器右上 5 键顺序（画中画 · 睡眠 · 选集 · 画面 · 设置，D27）未动；书架仍无睡眠入口。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 任务 `--rerun` **576 项 / 0 失败 / 0 错误**；含新 `:player:core:testDebugUnitTest` 全量 **583 项 / 0 失败**。
- **真机（待窗口）**：清单见 `PLAYER_PLAN` §26.3 ①–⑥（视频页入口组、播放器组、音乐组、熄屏后台组、0 FATAL / ANR）。

### W56 交互修正验收（2026-10-04，分支 `feature/w56-interaction-fixes`，起点 master `2ed356f`；静态 / 门禁部分）

- **范围（用户 2026-10-04 拍板四项）**：设置账号卡并入「账号与服务器」组首行；音乐迷你播放条「关闭面板」×（停播 + 收起，队列存档保留）；全屏播放页去歌词按钮（点歌词行 / 左滑保留）；顶层图标统一「回对应主页」（含音乐全屏 / 歌词覆盖层收起）。音乐侧细节见 `MUSIC_PLAN` §2.15 / §5.16。
- **决策**：D63（账号卡并入）、D64（回主页三态判定）。
- **静态核对**：账号卡复用原 `SettingsAccountHeader`（头像 / 昵称 / 服务器 / 徽标 / 点击全保留，仅容器上移进组卡）；迷你条 × 复用 `CinefinIconButton` + `ic_close`；全屏按钮组五键（无「歌词」）；无新增配色 / 字体 / 位图；`AppPreferences.kt` 零改动（偏好键未动）。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 任务 `--rerun` **583 项 / 0 失败 0 错误**（app 134 / core 37 / data 45 / player:local 105 / film 35 / book 113 / music 114）；含 `:player:core:testDebugUnitTest` 全量 **590 项 / 0 失败**；新增单测 7 项（`TopLevelNavigationTest` 4 + `MusicMiniBarRulesTest` 3）。
- **真机（待窗口）**：①设置首屏——账号卡出现在「账号与服务器」卡内首行（头像 / 昵称 / 服务器 / 徽标），点击进用户管理；②音乐播放中迷你条 × → 停止播放 + 迷你条消失（媒体通知消退）；③× 后再次点曲目正常起播；重启后队列存档仍可恢复；④音乐全屏按钮组 = 五键（无「歌词」），点歌词预览行 / 左滑仍进歌词页，迷你条「词」仍开歌词 sheet；⑤音乐全屏 / 歌词页打开时点底栏 / 侧轨「音乐」→ 回音乐主页；点其它模式图标 → 对应主页；⑥视频库内容页 / 视频详情 / 书架详情 / 本地库详情 / 临时库下点对应顶层图标 → 弹回根页；⑦已在主页再点 → 不闪烁、不重复导航；⑧0 FATAL / ANR。

### W60 图标 + 重试 + 续读进度条验收（2026-10-04，分支 `feature/w60-icons-retry-resume-bar`，起点 master `9c65010`；静态 / 门禁 / 真机）

- **范围**：搜索图标重绘 + 书签图标对（只出图标文件）；下载失败自动重试补齐（worker + 文案）；首页「继续阅读 / 继续收听」进度条；决策 D69。
- **静态核对**：图标 = 24dp / 1.75dp 描边 / 圆角端点，几何在 2–22 内容区内（搜索手柄墨迹 21.875 ≤ 22）；书签对与 `ic_heart_filled` 填充口径一致。`FindroidItem.playedPercentage` 为接口默认属性，仅 `FindroidFolder` / `FindroidMovie` 覆写，`FindroidEpisode` / `Season` / `Show` / `BoxSet` / 本地库条目零改动。worker 重试为纯函数 + `Result.retry()`，不改引擎调度（避免与任务级退避叠加）。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **651 项 / 0 失败 0 错误**（app 152 / core 63 / data 45 / player:local 110 / film 40 / book 113 / music 128；新增 8 = core 4 + app 4）、含 `player:core` 全量 **663 项 / 0 失败**。
- **真机（K60 `8e875894`，2026-10-04 05:58–06:21；device-lock 已写释放与结论）**：①搜索图标：媒体库顶栏放大镜（亮 / 暗背景可读性截图比对）通过；②续读进度条：`futuristic_tales` 与「继续观看」同款 3dp 条、无进度书籍不画；③断网自动重试：飞行模式失败 → 自动调度重试 → 联网自动续传（`retryCount=1` / RUNNING），下载页无「失败」终态；④0 FATAL / 0 ANR；测后复原（测试下载删除 / 误触已播放复原 / 飞行模式关闭 / force-stop / 临时文件清理）。
- **未覆盖（分列）**：书签对可见性（未接线，W60b）；下载页「重试中 · 第 N 次」文案（文件域归 W59 / W60b）；图片缓存 worker 重试设备端故障注入；「继续收听」样本；Pad 5 抽验（W59 未释放）。

### W59 下载页钻取式 IA + 书籍封面自动生成 + 层级图规则 + 专辑批量下载验收（2026-10-04，分支 `feature/w59-downloads-redesign`，起点 master `9c65010`；静态 / 门禁 / 真机）

- **范围**：下载页钻取式 IA（顶层聚合 + Show / 专辑详情 + 自动展开进行中的季 + 详情操作键）、在线书籍封面自动生成（Range 懒生成 + 缓存 + 失败标记 + 类型占位）、视频层级图严格同级、音乐专辑多选批量下载 + 专辑详情「下载专辑」+ 音符占位；决策 D68。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务逐个 `--rerun` **677 项 / 0 失败 0 错误**（app 148 / core 69 / data 45 / player:local 110 / film 48 / book 113 / music 132 / player:core 12）；新增 22。
- **真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）**：见 §4 W59 条目与 `DOWNLOAD_PLAN` §22.3；双机 0 FATAL / ANR。
- **未覆盖**：详情页「删除」按钮未单点验证（与批量删除同 `deleteEntries` 路径，建议用户代测）；封面观感（PDF 首页 / CBZ 首图 / EPUB 封面）建议用户人工过目；平板两列大数据量视觉节奏未单独评估；弱网下 Range 生成失败即收敛（不自动重试，与懒生成一次口径一致）。

### W58b 视频 / 书籍多选批量验收（2026-10-04，分支 `feature/w58b-multiselect-video-book`，起点 master `5dc2c82`；静态 / 门禁 / 真机）

- **范围**：视频（视频页聚合网格 + 库内容网格）与书籍（书架 / 书籍库内容页）长按多选 + 已加载全选 + 批量动作；决策 D67。
- **静态核对**：多选态与工具条全走既有 core 组件（`CinefinMultiSelect` / `CinefinSelectIndicator` / `CinefinBatchBar` / `CinefinListRow`），`ItemCard` / `LibraryListRow` 只加默认参数，无新增配色 / 字体 / 位图；「删除」只对已下载且非本地媒体库的服务器条目启用，确认框明示「只删除本机文件与索引，服务器上的媒体不受影响」；无任何服务器媒体删除写操作；新增文案 13 条三语言（film 默认 / zh-rCN / zh-rTW）。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun-tasks` **655 项 / 0 失败 0 错误**（app 148 / core 59 / data 45 / player:local 110 / film 40 / book 113 / music 128 / player:core 12；新增 17）。
- **真机（2026-10-04 03:43–04:19，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）**：①视频聚合网格 / 临时库视图长按多选、点选累计「已选 2 项」、全选 =「已选 17 项」（已加载）、取消全选 → 0、× 退出；②批量「播放」2 部电影 → 播放器起播第 1 部（`initializePlayer kind=Movie`）+ 队列 2 项（`播放队列补全完成：本次新增 1 项`）+ 队列面板顺序 = 列表序 + `KEYCODE_MEDIA_NEXT` 切到第 2 部；③批量「下载」→ 下载页「1 进行中」→ 完成（1.83 GB，测后删除）；④批量「标记已看」「收藏」→ 服务器筛选回读命中 2 项，二次触发全部取消复原；⑤「删除」纯服务器条目置灰（无对话框）、已下载条目弹「删除本地下载」确认框 → 确认后本机文件删除、下载页回落 4 已完成；⑥书籍书架：动作条恰 3 键（下载 / 标记已读 / 收藏）、全选 8 / 取消 / 退出、标记已读 + 收藏筛选回读并复原、批量下载 futuristic_tales（703 KB）落盘 `files/books` 且下载键随即置灰；⑦K60：视频长按 / 全选 17 / ×、书架三键通过；⑧双机 0 FATAL / ANR。
- **未覆盖（留人工 / 用户代测）**：①批量队列跨片连播听感受（未等整片播完，建议用户代测）；②剧集库「批量下载 = 整剧补齐缺失集」真机实测；③本地媒体库条目的删除置灰样本；④uiautomator 不反映 Compose 禁用态（用点击无响应判读）。

### W58 多选批量验收（2026-10-04，分支 `feature/w58-multi-select`，起点 master `ea18849`；静态 / 门禁部分，音乐打样）

- **范围**：core 通用多选框架（状态纯函数 / 长按手势 / 勾选指示 / 批量工具条 / 文案三语言）+ 音乐曲目多选与五键批量动作；决策 D66。
- **静态核对**：多选工具条与勾选全走既有 core 组件（§8.5 / 下载页 `SelectionBar` 口径），无新增配色 / 字体 / 位图；「删除」只出现在已下载且非本地媒体库的服务器条目，确认框明示「只删本机文件与索引」；无任何服务器媒体删除写操作（歌单「移出」走 Jellyfin 歌单编辑接口）。
- **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun-tasks` **638 项 / 0 失败 0 错误**（app 136 / core 59 / data 45 / player:local 105 / film 40 / book 113 / music 128 / player:core 12；新增 32）。
- **真机（待窗口）**：清单见 `MUSIC_PLAN` §5.17（音乐八组）；视频 / 书籍多选落地后一并补清单。
- **未覆盖**：视频 / 书籍多选批量（后续会话）；音乐专辑 / 艺术家 / 歌单行不参与多选（按 D66 口径）。

- **负责人真机走查（2026-10-04 01:36–01:43，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，master `8181921`）**：①账号卡「账号与服务器」组内首行（K60 同款）；②迷你条 × → `dumpsys media_session` = `state=0` 停播 + 收起，重选曲恢复「正在播放」；③全屏五键（播放队列 / 顺序播放 / 收藏 / 桌面歌词 / 音效）**无歌词入口**；点歌词区域 → 歌词页（纯音乐提示），迷你条「词」保留；④全屏播放 → 点「音乐」→ 回音乐主页（播放不中断）；歌词页 / 艺术家「Aimer」详情 / 电影库内容页 → 点对应图标均回主页；已在主页再点图标停留无副作用；⑤0 FATAL / ANR。未覆盖：音乐「专辑」tab 详情（用艺术家详情等效）、设置子页回主页（可选）。

### W64 首页书籍封面 + 阅读 / 音乐修复验收（2026-10-04，分支 `fix/w64-reader-music-home`，起点 master `a8a580f`；静态 / 门禁，真机待设备窗口）

①**缺陷**：书架能显示的书籍封面（W59 `BookCoverProvider`：服务器图优先 → 本地生成 → 类型占位）在首页
「继续阅读 / 最近添加 · 书籍」卡片上不显示 —— `LandscapeItemCard` / `PosterItemCard` 只读 `item.images`，
没有本地封面覆盖链路（`ItemCard` 有 `imageOverride`，但两条首页卡没有）。②**修法**（提交 `caa5288` + `6d4bcd9`）：
core 新增纯函数 `BookCoverRules.coverOverride`（服务器图优先 / 缺图回退生成封面）；`HomeViewModel`
注入 `BookCoverProvider`（与书架 `LibraryViewModel` 同源）+ `bookCovers` 状态 + `requestBookCover`；
`LandscapeItemCard` / `PosterItemCard` 支持 `imageOverride` + `placeholderIconRes`（无图回退 `ic_book`，
与 `ItemPoster` 同口径）；`HomeSection` / `homePosterWall` 透传 + `LaunchedEffect(item.id)` 懒生成；
书架两处内联三元改用同一纯函数。**③口径修订（用户 2026-10-04 第 12 条，提交 `6d4bcd9`）**：
封面优先级 = **服务器图优先 → 本地封面（已生成缓存 / 本地提取）→ 占位**；服务器图加载失败（离线等）
自动回落本地封面；**占位美观** = 书籍图标 + 当前域媒体色底（新增 `BookCoverPlaceholder`，复用既有矢量与
token、不新增位图），与相邻真实书封并排不显黑块。实现 = `BookCoverRules.displaySource` 显示来源状态机
（服务器 → 本地 → 占位、失败逐级回落）+ `HomeViewModel` 暴露已缓存本地封面（服务器图存在时不触发生成）。
④单测 +2（core `BookCoverRulesTest`：覆盖值优先级 / 显示来源状态机）；门禁 8 任务 **717 项 / 0 失败**；
⑤**真机通过（K60 `8e875894`，2026-10-04 14:02–14:31）**：联网「继续阅读 + 最近添加 · 书籍」8 本全部显示
封面（区域 stddev 74–102 = 图像而非纯色）；离线（飞行模式）虚构推理 / W22 卡像素与联网完全一致
（(134,165,181) / (60,59,73)）= 本地封面回落；离线 + 无缓存未下载（金田一）= **风格化占位**（书图标 +
媒体色底：深青底 + 天青图标，视觉确认非黑块）。未覆盖：有服务器图 + 有本地缓存时「失败切换」的图源级
区分（观感一致已验）。

### W65 侧栏宽度自适应验收（2026-10-04，分支 `fix/w65-adaptive-sidebar`，起点 master `c5e8384`；静态 / 门禁 / 真机）

①**侧轨展开宽** = `clamp(屏宽dp × 30%, 200dp, 240dp)`：Pad 5（1600px @360dpi = 711.1dp）实测展开 **480px = 213.3dp**（预期 213.3dp）；折叠轨 **162px = 72dp** 恒定不回归。②**手机抽屉宽** = `clamp(屏宽dp × 55%, 208dp, 280dp)`：K60（1440px @560dpi = 411.4dp）实测 **791px = 226.3dp = 54.9% 屏宽**（预期 226.29dp）。③**让位规则**：Pad 5 展开轨（文字可用宽 113.3dp）库行「电影 17 项 / 动漫 94 项 / 其他2 2 项 / 书籍 8 项 / 书籍3 8 项 / 音乐 124 项 / 音乐测试 0 项 / Playlists 0 项」名称 + 项目数全部完整；K60 抽屉（文字可用宽 114.3dp）同 8 行完整，本地库行「本地媒体库」按「名称优先」省略项目数保完整库名。④**交互**：两机页面切换（K60 下载页 / 音乐库临时视图、Pad 5 视频页 / 返回首页）与「媒体库」分组展开 / 收起、侧轨折叠 / 展开往返均正常。⑤**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **730 项 / 0 失败 0 错误**（新增 7：`AdaptiveSidebarWidthTest`）。⑥**稳定性**：双机 crash buffer + 主缓冲 0 FATAL / 0 ANR。未覆盖：横屏 / 分屏形态（按回滚预案未改旋转）、K60 393dp 标准密度下的精确宽度（411.4dp 比例分支已覆盖）。

### W68 音乐锁屏 / 通知点击入口 + 桌面媒体胶囊元数据（2026-10-04，分支 `fix/w68-background-lockscreen`，起点 master `f7fc66d`；真机待设备窗口）

①**入口语义（D78，编号预留 D75–D77 给 W66/W67）**：媒体会话条目点击按类型分派——**音乐条目 → 音乐 Tab + 全屏播放覆盖层**（与点迷你条一致，不再打开视频播放页）；**视频条目 → 视频播放页**（现状不变）。同一份 PendingIntent 由通知内容点击（`CinefinMediaNotificationProvider`）与锁屏 / 蓝牙 / 车机点击（`MediaSession.setSessionActivity`）共用；音乐 ↔ 视频切换时服务监听播放器事件更新路由。音乐侧以 `MainActivity` + `EXTRA_OPEN_MUSIC_NOW_PLAYING` 实现：`NavigationRoot` 等 NavHost 就绪后 `navigateTopLevel(MusicModeRoute)` 并递增 `openNowPlayingSignal` → `MusicModeScreen` 在队列就绪后展开全屏播放（冷启动恢复队列同样生效）。②**桌面媒体胶囊元数据（用户澄清的"胶囊太窄"）**：MIUI / 澎湃桌面顶部胶囊的宽度 / 圆角 / 排版由系统决定，**不仿制不改尺寸**；应用侧只补齐喂给系统的元数据——音乐 `MediaItem` 显式 `setArtist`（`PlayerItem.artist`，拿不到不写）+ 无封面时用通用音符占位图 URI，通知副标题音乐 = 歌手（无则空）、视频 = 既有 S/E。③**红线**：动 `app:phone`（`playback` 包 / `MainActivity` / `NavigationRoot` / `BasePlayerActivity`）、`modes:music`（`MusicModeScreen` 信号参数 / `MusicTrackResolver` 补 artist）、`player:local`（`PlayerHolder` / `PlayerViewModel` / `MusicMediaItems`）、`player:core`（`PlayerItem.artist` 可选字段，必要扩展）、`settings`（后台播放默认值，已申报）；`AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` 零改动。

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
25. **`"S$this.parentIndexNumber"` 是 Kotlin 字符串模板陷阱**：编译通过，但语义是「`$this` 对象插值 + 字面量 `.parentIndexNumber`」，真机上海报 / 走廊卡 / 头图眉标会把整段 `FindroidEpisode` 打印出来（首页主视觉被盖满）。要插值属性必须写 `${this.parentIndexNumber}`；本波统一收口到显式 `seasonCode()` / `indexCode()`（缺号回落 `S?` / `E?`）。凡"人眼看到对象字符串"的 bug，先在代码里搜 `"$` + 属性访问。
26. **只在抽屉打开时才加载的导航数据 = 冷启动必踩**：`DrawerViewModel.load()` 原先挂在 `drawerState.isOpen`，而书架 Tab 的跳转 / 选中态都读这份库列表 → 冷启动点「书架」拿到空列表，被入口逻辑"兜底"到媒体库总览。两条纪律：① 顶部 / 侧边导航目的地依赖的数据要在 `LaunchedEffect(Unit)` 预载；② 入口不应把"数据未就绪"翻译成**另一个目的地**——本波把书架改成独立 `BookshelfRoute`，由页面自己解析（Loading → Ready / Empty / Failed）。真机定位手法：先开一次抽屉再点同一入口，若行为变正确，则基本可判定为"数据未加载 + 错误兜底"。
27. **悬浮在滚动内容上的搜索框必然遮挡**：`Box { FilmSearchBar(); LazyVerticalGrid(topPadding = 88.dp) }` 只在滚动起点对齐，上滑后卡片会钻到搜索框下（用户截图可见"搜索框挡住最前面的卡片"）。两种解法二选一：放进滚动内容第一项（M3 `SearchBar` 展开需要全屏约束，放 lazy item 里会拿到无界约束，风险高）或改成不覆盖布局——本波取 Column 表头 + 栅格自裁剪，并用 `uiautomator` 断言"最上可见卡片 bounds 与搜索框 bounds 无交集"。
28. **`currentRoute == Route::class.qualifiedName` 对带参路由恒为 false**：Navigation Compose 里 `destination.route` 对 data object 才是 qualifiedName，对带参路由是「类名 + `/{arg}` 模板」。所以 `showNavigation` 中的 `LibraryRoute` / `SettingsRoute` / `ConsoleRoute` 从未命中（这三个页面侧轨 / 底部 Tab 不常驻，与 D18 文字不符），`currentLibrary` 也恒为 null（书架库选中态失效）。正确写法是 `navBackStackEntry?.destination?.hasRoute<LibraryRoute>()`；本波新增的 `BookshelfRoute` 是 data object，不受影响。**W5-R3G 已修复**：统一收口到 `NavDestination?.isRoute<T>()`（内部走 `NavDestination.Companion.hasRoute`，navigation 2.10.1 的 import 为 `androidx.navigation.NavDestination.Companion.hasRoute`），覆盖 8 个统一目的地与全部选中态；真机对照见 §5 W5-R3G 验收。
29. **头图"内容比卡片高"时，底部对齐会先把行动区压扁**：手机竖屏 16:9 头图（411dp 屏 → 卡片 ≈371dp 宽 → ≈208dp 高）装不下「眉标 + 标题 + 元信息 + 副标题 + 行动区」（≈236dp）；`Column` 用 `align(BottomStart)` 后超出部分被 `clip(shape)` 裁掉——表现**不是**顶部被裁，而是最底部的行动区被**压缩测量**：46dp 的按钮胶囊只剩 ≈14dp 高、文字被拦腰裁切，「剩余 N 分钟」同样被裁（真机表现为用户报的"按钮与剩余时间显示不全"）。定位手法：截图后按行扫描按钮主色像素（橙色仅 y 662–699 → 胶囊高 38px）即可确认是垂直压缩而非水平溢出。修法：头图 `Modifier.heightIn(min = 比例高度)`（高度 = max(比例高度, 内容高度)，图片 / 遮罩层必须改 `matchParentSize`，否则 `fillMaxSize` 在高度无界时不参与测量）+ 行动区 `FlowRow`（放不下自动换行）+ 时间文本 `softWrap = false`。结论：**凡是"图 + 文字压底"的卡片，比例高度只能当 min，不能当固定高度**。
30. **`popUpTo(saveState = true) + restoreState = true` 会按「目的地 id」恢复旧条目，把新参数顶掉**：服务器控制台与媒体资料管理器是同一条 `ConsoleRoute`（只差 `path` 参数）→ 两者目的地 id 相同。沿用统一入口 `navigateTopLevel`（`popUpTo(start){ saveState = true }` + `restoreState = true`）时：先开控制台（`/dashboard`）→ 再点资料管理器，`NavController` 发现 `backStackMap` 里有该目的地 id 的保存条目，**优先恢复它**（`restoreStateInternal(backStackState)`），于是新传入的 `path = "/metadata"` 被丢弃，用户又看到 `/dashboard`。定位手法：`adb forward` 到 WebView 的 devtools socket，`curl http://127.0.0.1:<port>/json` 读页面 URL（debug 包已开 `setWebContentsDebuggingEnabled`），或轮询 URL 变化。修法：这类"一个目的地 + 多种参数"的入口走独立导航（`popUpTo(start)` 不带 `saveState`、不 `restoreState` + `launchSingleTop`），让参数生效；**只有"同一个页面、要保留滚动 / 输入状态"的 Tab 才该用 `saveState/restoreState`**。
31. **WebView 的"登录态种子页"会留在历史里，让返回变成两步空白页**：控制台的登录态是靠一个同源空白种子页写进 `localStorage` 的（先 `loadUrl(seedUrl)`，`onPageFinished` 里再 `loadUrl(consoleUrl)`），种子页因此成为 WebView 历史的第一条。系统返回键在 `ConsoleWebViewClient` 里优先走 `webView.canGoBack()`，于是控制台里按一次返回落到**看不见的种子页**（深色空白），要按第二次才离开控制台。修法：种子页 `onPageFinished` 置 `pendingSeedHistoryTrim`，等控制台页真正加载完再 `view.clearHistory()`（此时清掉的只有种子页那一步，控制台内部后续的 hash 导航历史不受影响）。
32. **侧轨分组父项加"尾部箭头"时，`Text(weight(1f, fill=false)) + Spacer(weight(1f))` 会把标签挤成 `…`**：两个带权重子项平分剩余空间，父项行内还要减去 22dp 图标 + 14dp 内边距 + 20dp 箭头 →「媒体库」只分到 ≈40dp，直接省略成「...」（真机截图一眼可见）。修法：有 `trailing` 时标签用 `Modifier.weight(1f)`（占满剩余空间、不额外放 Spacer），箭头紧随其后；`CinefinNavigationItem` 同时新增 `compact`（二级子项 44dp 紧凑行）与 `trailing` 槽位。
33. **长生命周期 ViewModel 里注入仓库 = 偏好切换不生效**：`provideJellyfinRepository` 虽未加 `@Singleton`，但注入点只解析一次——Activity 级 `DrawerViewModel` 在离线模式下创建后，即使把偏好切回在线，`repository.getLibraries()` 仍走离线实现（侧轨库列表回不来）。修法：注入 `javax.inject.Provider<JellyfinRepository>`，每次 `load()` 按当前偏好重新解析；导航面（侧轨 / 入口门控）因此可以做到"切换即生效"，内容页仍在下一次启动完全切换（本期边界，见 W6-R6N 进度）。
34. **`MainState.isLoading` 回流会把整棵 UI 移出组合**：`MainActivity` 里 `if (!state.isLoading) { NavigationRoot(...) }`，离线偏好监听若直接调 `check()`（先 `emit(isLoading = true)`），设置页会闪一下白（组合被移除）并丢掉 `LazyColumn` 滚动位置——用户看到的正是"重建页面"。修法：`check(showLoading = false)` 只刷新状态；对比手法：切开关后 `uiautomator dump` 检查「离线模式」行是否仍在视口内（滚动位置是否保留）。
35. **服务器主题 / 自定义 CSS 与 App 注入皮肤是两层，只改 MUI 变量不够**：Jellyfin 会在运行期加载服务器配置的主题与「自定义 CSS」，它们的强调色会直接写进 `.navMenuOption`（抽屉导航项）、`.cardText`（卡片标题）等元素；App 侧皮肤即使把 `--jf-palette-primary-main` 改成极光青，抽屉项仍是服务器主题的 `#D2553C`（CDP 实测 computed color）。**解法**：对这类"服务器主题直染"的选择器显式 `!important` 覆盖（v3 新增 `.navMenuOption` / `.navMenuOption .material-icons` / `.cardText` / `.cardText-first` 等规则）；验证手法 = CDP `getComputedStyle(el).color` 前后对比（`rgb(210,85,60)` → `rgb(152,162,179)` / 选中 `#5CE1D2`）。**推论**：服务器端 CSS 更新（用户手动粘贴）与 App 内注入是两条通道，验收时两边都要看。
36. **`dumpsys gfxinfo` 对 WebView 滚动的口径因设备而异**：同一控制台滚动场景，K60（骁龙 8+ Gen1）每轮稳定记录 ~1000 帧 HWUI 提交；Pad 5（骁龙 860）v3 包为 0 帧、baseline（v2 包）为 ~1050 帧——WebView 在不同设备 / 系统上走不同的合成路径（独立 Surface vs 进 App 绘制树），gfxinfo 只能当辅助口径。判断 WebView 滚动流畅度应加**页面内 rAF 帧间隔探针**（`requestAnimationFrame` 间隔的 p50/p90/max + >20ms 帧数）与 `longtask` 计数（本会话用 CDP + Node 临时脚本测量，未入库）。
37. **overscroll 拉伸是否关闭的验证法**：滚到顶部后向下拉一次，比较前后两张截图的 WebView 区域（步长 6 / 容差 12）：`overScrollMode=NEVER` 时 diff=0 / maxDelta=0（内容完全不动）；默认 `OVER_SCROLL_ALWAYS` 时 Android 12+ 会做拉伸回弹（内容位移 + 边缘光效）。这是"到顶到底不干脆"类反馈的可复现判据。

38. **`ProvideLumen` 只换语义色 CompositionLocal，换不掉存量 `MaterialTheme.colorScheme`**：首页与详情页当时几乎只读 `LocalCinefinColors` / `LocalMediaColors`，所以 W5-R3H 的局部覆盖看不出问题；一旦把 Lumen 扩到**设置 / 向导**这类大量使用 M3 组件的页面，`Scaffold` / `TopAppBar` 默认底色仍是 `#151A21`、`Switch` 的 `secondary` 仍是**音乐松石**、`TextField` / 对话框仍取 Prism 槽位——真机表现为"页面背景变了、控件还是旧配色"。修法：`ProvideLumen` 内嵌一层 `MaterialTheme(LumenMaterialColorScheme)`（用 `MaterialTheme.typography` / `shapes` 透传，避免排版与形状回归），由 `cinefinColorScheme(Movie)` `.copy(...)` 覆写中性 / 强调槽位；**error / onError 等语义色不参与改色**。推论：以后任何"局部皮肤"都必须同时给出 M3 槽位映射，否则只覆盖语义色的页面一定会漏。
39. **`Modifier.navigationBarsPadding()` 放在组件外会漏出外层主题色**：底栏的 modifier 链是「调用方 modifier（含 `navigationBarsPadding`）→ `.fillMaxWidth().height(64.dp).background(底)`」，背景画在安全区**以内**，手势条那一条留给外层 `CinefinTheme` 的 `#151A21` —— 真机上底栏下方就能看到一条石板色带（修好后同一坐标是 `#111319`）。修法：在底栏外再包一层 `Box(Modifier.fillMaxWidth().background(同色))`，或把安全区当作组件内部事务（背景先铺、padding 后加）。验证手法：`screencap` 后按行统计底部 200px 的最常见颜色。
40. **`AnimatedVisibility` 在 `Column` / `Row` 里会解析到作用域重载**：把 `AnimatedVisibility` 写在「Column → Box → …」里会命中 `ColumnScope.AnimatedVisibility`，Kotlin 报 *"cannot be called in this context with an implicit receiver"*（BoxScope 与 ColumnScope 两个隐式接收者打架）。修法：把它收进一个**没有作用域接收者的顶层函数**（本波 = `LumenSkeletonOverlay`），或显式指定接收者；同类问题也适用于 `Modifier.align` / `weight` 这类作用域扩展。

41. **`install -r` 后 `rememberSaveable` 的形态状态会被系统恢复，别当成默认值 bug**：Pad 5 走查时侧轨一开始是 88dp 折叠态，而代码里 `railDefaultExpanded = width ≥ 1200dp → true`。原因是重装 APK 只杀进程，Activity 任务的 saved instance state 仍被系统保留，下次启动把上一会话手动折叠的状态（`rememberSaveable`）恢复了。判据：先 `am force-stop` 再 `am start`（或清任务）后看默认值；本次用点击「收起 / 展开」行验证展开态。
42. **抽屉条目区必须可滚动**：把二级库列表移进抽屉后条目从 6 条涨到 12 条（12×56dp = 672dp + 96dp header）。矮屏（横屏手机 / 小屏平板）会把「客户端设置」裁到屏幕外，而 `ModalDrawerSheet` 自己不滚动。修法：`CinefinDrawerContent` 内条目区 `Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()))`——`fill = false` 保证条目少时仍按内容高度排布。
43. **系统手势导航会吃掉左缘滑动**：Pad 5 / K60 的 `navigation_mode = 2`（手势导航）下，`adb shell input swipe` 从左缘（x≈0）起手触发的是系统「返回」，不是 app 抽屉（实测把 app 直接退回桌面 / 上一页）。要断言抽屉手势，必须先切三键导航（`settings put secure navigation_mode 0`，W6-WEB 曾这么做并还原）或用不会与系统手势冲突的起点；本体入口（顶栏键）不受影响。
44. **MIUI「超级小爱悬浮窗」会顶掉 uiautomator dump**：Pad 5 上 dump 首行出现 `package="com.miui.voiceassist"` / `content-desc="超级小爱悬浮窗"`（全屏 FrameLayout），此时页面节点全部缺失。按一次 `KEYCODE_BACK` 关掉悬浮窗再 dump 即可；与踩坑 18 的「剪贴板与常用语」弹窗并列，走查脚本应有"首行不是目标包名 → 先关弹窗"的兜底。
45. **半透明悬浮胶囊的像素采样不会等于纯色值**：`ConsoleBackToAppPill` 用 `lumen.panel.copy(alpha = 0.94f)` 压在 WebView 上，K60 实测主色是 `#11141A`（而不是 `#111319`）。验收时按"石墨家族 + 月白文字 + 高光/描边都在"判断，别把 alpha 混合结果当色值错误；换用完全不透明底又会丢掉"悬浮"质感。

46. **`return@flatMap` 被折行就变成"返回 Unit"**：写成 `return@flatMap` 换行再给表达式时，Kotlin 在换行处补分号 → 编译报 `Return type mismatch: expected 'Iterable<DrawerEntry>', actual 'Unit'`（本波 `drawerEntries` 踩到，ktfmt 也不会替你把它并回一行）。要么把值写在同一行，要么改成 `if / else` 表达式。
47. **`getItems` 的库列表默认不返回 `ChildCount`**：媒体库卡片要显示"共 N 个项目"时，必须在请求里显式 `fields = listOf(ItemFields.CHILD_COUNT)`（只影响这一个请求的体积，不额外发请求）；SDK 1.8.12 的 `BaseItemDto` 有 `getChildCount()` / `getRecursiveItemCount()`。缺字段时卡片只显示库名（不占位）。验收时注意 `ChildCount` 是**服务器实时数据**，不同时刻 / 不同设备读到不同值属正常（本波同一台服务器三次读到 5 / 9 / 3）。
48. **"默认收起"的二级分组必须同时给出行尾入口**：只把子项藏起来而不给展开入口 = 把库列表藏死。抽屉与侧轨共用 `CinefinNavItem.trailing`（chevron），父行点击 = 导航 + 展开、箭头点击 = 仅开关；有 trailing 时标签必须 `weight(1f)`（否则重演踩坑 32 的"标签被挤成 …"）。
49. **core 组件拿不到 app 侧的窗口工具函数**：`modes:music` 只依赖 core，`rememberPageGutter()` 在 app:phone 里用不到。共享顶栏把页边距分级（20 / 24 / 32 / 48dp）内化到 core（用 `LocalConfiguration.screenWidthDp` 复刻同一阈值）——两处阈值必须同步，改一处要改另一处。

50. **MiSans 免费商用 ≠ 可打包**：官方《MiSans 字体知识产权许可协议》除「注明使用了 MiSans」外，还明确「不得对字体或其任何单独组件进行改编或二次开发」「不得再分发字体软件或其任何副本」——子集化属改编、APK 内嵌属再分发，两条都撞。厂商字体（小米 / OPPO / 华为等）多半同款条款，落地前先读协议原文再谈子集化；本波改用 OFL 的 Noto Sans SC。
51. **Google Fonts 的 `NotoSansSC[wght].ttf` 默认实例是 Thin(100)**（`name1 = "Noto Sans SC Thin"`）：直接 `Font(R.font.…)` 不传 variation 时全站变细体。必须先用 `fonttools varLib.instancer <字体> wght=400:900 --update-name-table` 把默认实例收窄到 Regular，再用 `FontVariation.Settings(FontVariation.weight(…))` 映射 400 / 500 / 600 / 700 四档，顺带避免系统合成加粗。
52. **`raw.githubusercontent.com` 大文件在本机会被重置**：HTTP/2 下拉 1 MB 以上文件常见 0 字节且 `Invoke-WebRequest` 报「远程主机强迫关闭了一个现有的连接」；改用 `curl.exe --http1.1 --retry 3`（不要加 `-sS`，本机实测会静默输出 0 字节），17.7 MB 的 Noto 也能一次拉完。`Invoke-WebRequest` 还会把整包读进内存，大字体容易挂死。
53. **`<monochrome>` 要单独放 `-v33` 目录**：`adaptive-icon` 的 monochrome 只在 API 33+ 生效，把 `mipmap-anydpi/ic_launcher.xml` 保持「背景 + 前景」两份、另建 `mipmap-anydpi-v33/ic_launcher.xml` 声明 monochrome，旧机型就不会遇到未知标签。
54. **品牌文案改 `%1$s` 注入时先看调用点是否红线文件**：`console_back_to_app` 的唯一调用点在红线文件 `NavigationRoot.kt`，本波按字面量更名（`Back to Aurorama` / `返回极光幕`），未申报不动红线；后续若改占位符注入 `app_name`，同样要先申报。
55. **给 M3 `SearchBar` 传 `weight(1f)` 会把展开态压坏**（W39 真机拦下）：`weight(1f)` 给子项的是**固定**高度约束（`minHeight = maxHeight`），而 `SearchBar` 的展开布局用 `inputField.minIntrinsicHeight(...)` 经 `Constraints.constrainHeight` 算起始高度、再把剩余高度分给结果区——固定约束下输入框被撑到全高、结果区被压成 0 高（真机表现：点搜索图标后输入框垂直居中、输入 `9` 无任何结果）。修法：`SearchBar` 的父布局**不要限制尺寸**（本波在媒体库页去掉 `weight(1f)`，只留 `fillMaxWidth()`，它自己会占满剩余空间）；M3 文档原文即「parent layouts must not pass any Constraints that limit its size」，`DockedSearchBar` 才是平板受限场景的替代。
56. **assets 内字体必须未压缩（`noCompress`）才能 mmap**（W40）：`Typeface.Builder(assets, path)`（Compose `AndroidAssetFont` / `Font(path, assetManager)` 的底层）先试 `AssetManager.openFd`，asset 被 AAPT 压缩时失败 → 退化为「整包读入直接内存」（本字体 ≈20 MB/实例），API 28 上还可能拿不到 fd；`.ttf` 不在 AAPT 默认 noCompress 列表，必须在**每个 app 壳**显式 `androidResources { noCompress += "ttf" }`（core 库模块的设置不生效）。另：`CinefinType` 是组合外静态 token，构造期拿不到 `AssetManager`，assets 字体的正确接法是 `AndroidFont` + `TypefaceLoader` 在解析期加载（`MisansFont.kt`），而不是 `Font(path, assetManager)`。

57. **半透明面板叠在"同色容器"上等于没做**（W42 真机拦下）：侧轨 / 底栏都在 `CinefinModalDrawer` 的容器里，容器铺的正是 Lumen 石墨 `#111319` —— `panel.copy(alpha = 0.82f)` 直接叠上去，真机像素采样仍是不透明的 `#111319`（用户完全看不到"透出"）。修法：半透明面前**显式铺一层页底衬底**（Lumen 用 `lumen.background` = #08090C，Prism 用 `colors.surface`）再叠面板，采样才得到真混合值 `#0F1216`。推论：任何"半透明表面"先确认衬底颜色，别假设父容器是页底。
58. **开关项的 `onClick` 拿到的是"切换前"的值**（W42 顺手修）：`SettingsGroupCard` 先 `OnUpdate(copy(value = !value))`，再把**原对象**交给 `preference.onClick(preference)` —— 桌面歌词的「打开时若没有悬浮窗权限就引导授权」用 `preference.value` 判断，实际只在**关闭**时才触发（权限引导反了）。修法：把 `toggled` 后的 copy 传给回调（`preference.onClick(toggled)`），语义统一为"回调拿到切换后的值"。
59. **M3 `Switch` 的宽度不能用 `Modifier.width` 压**：`Switch` 内部用 `requiredSize(52dp, 32dp)` + `minimumInteractiveComponentSize()`，父级传 `width(44.dp)` 会被 `requiredSize` 顶掉（不生效、也不报错）。要收进 44dp 控制位只能用 `Modifier.scale(0.846f)`（只影响绘制、保留最小触控热区），并让整行可点（`SettingsBaseCard`）兜住触控。真机实测：行高 63dp（被 48dp 最小触控目标撑高 3dp），视觉宽度 44dp。

60. **`LocalLibraryEntry` 不带 `libraryId`**（W43）：搜索要给本地行标注「库名 · 文件夹名」，但 W37 的扁平条目只有 `folderId`；给模型加字段会牵动全部构造点，改为从 `LocalMediaItemDto` 按 `itemId` 回连归属库；同文件夹音乐队列直接用 `folderId` 取（`folderId` 全局唯一），无需再解析库。
61. **MIUI `uiautomator dump` 会偶发 0 字节并打印 uiautomator 自身 FATAL**（W43）：`AccessibilityNodeInfoDumper.childNafCheck` 崩溃（进程 `com.android.uiautomator`）会让本次 dump 落空、logcat 出现 `FATAL EXCEPTION: main` —— **不是 App 崩溃**，重试一次即成功；`dumpsys dropbox` 里还混着历史条目（如 09-30 的 ReaderActivity `Invalid UUID` 崩溃、Play Store `system_app_wtf`），核对崩溃必须看条目头时间戳与 `Process:` 行。
62. **删除下载会残留 `.download.js` sidecar**（W41 下载链路回归时发现，非本波引入）：App 删除流程把 DB 记录与主 `.download` 文件清掉（下载页回 0 进行中 / 0 已完成），但同目录的 `.download.js`（25 kB）留在 `Android/data/<pkg>/files/downloads/`；Android 13 起 shell 与 `run-as` 都无权限删（`Permission denied`，MIUI 收紧了 Android/data 访问）。不影响功能，记待后续在删除动作里一并删 sidecar。
63. **adb 直启 `PlayerActivity` 在本机（Pad 5 / MIUI）是「起了又退」的既有现象**（W41 用 A/B 对照确认）：`am start -n <pkg>/…PlayerActivity --es itemId … --es itemKind Episode` 会被系统接受（`START u0 … has extras`）、进程内能看到 `ExoPlayerImpl: Init` → 随后 `PlayerViewModel: Clearing Player ViewModel` + `Release`，Activity 在起播前结束、无 FATAL、无媒体会话；**旧包 `com.zhangwenkang.cinefin.debug` 同命令同样表现**，与 applicationId 改名无关。要验证播放 / 通知请走正常 UI 路径（详情页 → 继续播放），并用 `dumpsys media_session` + `dumpsys notification` 取证。

64. **`adb shell input swipe` 起点落在屏幕边缘会被系统返回手势吞掉**（W44 真机验证自绘滑杆时踩到）：起点 x=65 px（≈29 dp）落在手势导航的侧边返回热区，整条拖动被系统接管——表现是"面板被关闭、滑杆值没变"，看起来像组件 bug。验证任何横向拖动（滑杆 / 手势页）都把起点放到内容区（本波 65 → 600 px）再复测；真机上滑杆本身在内容区起手时拖动正常（已复验 + 落盘取证）。
65. **M3 默认暗色 `Switch` / `Slider` 经不起暗底检验，且问题是"每页一份"**：`Switch` 关闭态拇指取 `outline`（#2C3542）对面板底 #171D25 仅 **1.37:1**，用户读作"小圆点看不见"；`Slider` 默认浮标是 4dp 宽 × 44dp 高的竖条，用户读作"控制条上的竖线"。修法不是逐页调色，而是把两者收成 core 共享组件（`CinefinSwitch` / `CinefinSlider`）再一次性替换调用点——否则新老样式会在设置 / 音效 / 播放器面板之间混排。验收用像素采样给对比度数字（1.37:1 → 6.6:1），比截图更可复核。
66. **自绘滑杆（Canvas + `pointerInput`）不会自动继承 M3 行为**：RTL（最小值从右往左）、`steps` 档位吸附、无障碍语义（`progressBarRangeInfo` + `setProgress`）、禁用态、**尺寸契约**都必须在组件里显式补；另外 `pointerInput` 的 key 里不能放每次重组都会新建的 lambda（会重启手势、拖动中途断开），要用 `rememberUpdatedState` 包住回调、只把 `enabled / valueRange / steps / isRtl` 当 key。W49 补齐：键盘步进（方向键 / Home / End / PageUp / PageDown，`delta = 区间长度 / (steps + 1)`，无档位 1%，RTL 左右对调）与 `setProgress` 档位就近吸附，实现口径逐条对照 M3 `Slider.kt` 的 `slideOnKeyEvents` / `sliderSemantics`。
67. **「按需加载 + 结果缓存」会让「尚未就绪」被当成「永远没有」**（W45 真机拦下）：库卡封面在 `LaunchedEffect(card.id)` 里请求一次，而新建库时库卡会先以「0 项 / 尚未扫描」出现——此刻 `repository.entries()` 返回空 → 得到 null 并写入 `covers[id]` + 请求去重集合，之后即使扫描完成（125 项）也不再请求；表现是「库卡封面必须进一次详情页才出现」（详情页的头部请求发生在数据就绪之后）。修法 = **空数据时不请求、请求键带上数据量**（`itemCount <= 0` 直接 return；`LaunchedEffect(card.id, card.itemCount)` + `coverRequests[id] == itemCount` 去重）。通用判据：凡「一次性请求 + 结果缓存」的 UI 状态，都要问一句「请求时数据可能还没到吗」——是则把「未就绪」与「确认为空」分开，或把数据量 / 版本号放进请求键。

68. **`ModalDrawerSheet` 的 Surface 底色会盖住 modifier 里的半透明层**（W46）：给 sheet 的 `modifier` 挂两层 `background`（先页底衬底、再 74% 面板）并不会生效——`ModalDrawerSheet` 内部是 `Surface(containerColor)`，它自己的 `background(color)` 画在传入 modifier **之后**，把半透明层整个盖住（真机表现为「抽屉还是实心石墨」）。修法 = `drawerContainerColor = Color.Transparent`，让 Surface 让位给 modifier 的两层底。同类组件（`Surface` / `Card` / `Scaffold`）都先查有没有自己的 `containerColor`。
69. **形态切换后要主动收抽屉**（W46 顺手做）：平板形态把 `openDrawer` 置 null + 关手势之后，抽屉只剩「已经被打开」这一种存在方式——手机（或折叠屏展开）拉开的抽屉在窗口切成平板后仍留在屏幕上（它没有入口、也没有手势能关）。修法 = `LaunchedEffect(compactNavigation) { if (!compactNavigation && drawerState.isOpen) drawerState.close() }`。通用判据：凡是"某形态下入口被移除"的组件，都要检查它在该形态下**已打开**的状态怎么退出。
70. **`Icon` 的 `tint` 默认会染色品牌图标**（W46）：顶栏入口从 `ic_menu`（单色）换成 `ic_logo`（极光青 → 辅光蓝渐变）时，若沿用 `tint = colors.onSurfaceVariant`，整枚图标会被染成次级灰、渐变全丢。`Icon` 的 `tint` 默认是 `LocalContentColor` 而不是 `Color.Unspecified`，品牌 / 多色图标必须显式传 `Color.Unspecified`。

71. **MIUI 真机造「自由窗口（Compact）」的可复现方法**（W47，已实测跑通）：`settings put global enable_freeform_support 1`（写前记原值，收工 `settings delete global enable_freeform_support` 还原）→ `am start -n <pkg>/<MainActivity> --windowingMode 5` → `am task resize <taskId> 0 0 1000 1600`。`dumpsys activity activities` 应出现 `mode=freeform` 与缩小后的 `mBounds`；播放器「Compact」判据（`isInMultiWindowMode && windowWidthDp < fullWidthDp×3/4`）随之命中，工具行变纯图标。两个坑：①`am task resize` 收**任务号**（`Task{#9610166}`），不是 ActivityRecord；②忘记删除 `enable_freeform_support` 会让之后所有任务默认 freeform。
72. **MIUI 杀前台进程不一定有 FATAL / ANR**（W47 读 2.36 GB PDF 时）：进程被 SIGKILL，logcat 无 Java / native 异常；判据是 `logcat -b events` 的 `killinfo: [<pid>,…]` + `am_proc_died` + `libprocessgroup: Successfully killed process cgroup` + `wm_finish_activity … proc died without state saved`，配套现象 = `pidof` 换号、`dumpsys meminfo` 读不到进程。排查内存爆增必须在操作**过程中**按秒采样 `dumpsys meminfo`（Native Heap + TOTAL PSS），等操作结束再取会错过峰值。

73. **自绘组件替换 M3 组件时，「尺寸契约」也会一起变**（W49 真机拦下，W44 回归）：`material3.Slider` 内部把宽度填满 **max** 约束，所以阅读器 `Modifier.weight(1f, fill = false).widthIn(max = 230.dp)` 一直正常；换成 `Canvas`（`Spacer`）后它按 **min** 约束尺寸（`Spacer` 直接 `layout(minWidth, minHeight)`）→ 宽度 0：轨道 / 拇指不绘制、触摸与键盘焦点都不响应、**连 a11y 树里的 `SeekBar` 节点都消失**（零面积节点被判不可见）。判据：`uiautomator dump` 里找不到 `android.widget.SeekBar`（有 `ProgressBarRangeInfo` 时 Compose 会把它标成 SeekBar）、截图列扫描发现该行没有任何轨道像素。修法：组件内 `fillMaxWidth()` 与 M3 对齐（上限交给调用方 `widthIn`）；**教训：换自绘组件时要连「尺寸 / 焦点 / a11y」三条契约一起对照原组件，不能只对齐配色与几何**。

74. **家庭视频库缩略图空白 = 映射层复用「电影」+ 横版卡一律取 Backdrop**（W54-B 定位，任务 E）：`BaseItemKind.VIDEO`（家庭视频 / 个人视频）在 `toFindroidItem` 里落到 `toFindroidMovie`，而 `ItemPoster` 的 `Direction.HORIZONTAL` 分支写的是「是 `FindroidMovie` → 取 `images.backdrop`」。影视库的电影 / 剧集有元数据来源（TMDB 等）所以有 Backdrop；**家庭视频没有任何元数据来源、只有 Primary（视频缩略图）** → `AsyncImage(model = null)` 只画出容器底色，表现为「整个家庭视频库没有缩略图」，同一个库在竖版（图书 / 电影）方向下反而正常。修法 = `item.images.backdrop ?: item.images.primary`（有 Backdrop 时行为不变）。判据：其它库正常、只有 homevideos / 个人视频库异常时，先查「库类型 → 映射成的 Findroid 类型 → 该方向取哪张图」这条链，而不是先查网络 / 服务器缩略图任务。
75. **PowerShell `Select-Object -First N` 会掐死 Gradle 管道**（W54-B 门禁时踩到）：`.\gradlew.bat <tasks> --console=plain 2>&1 | Select-String … | Select-Object -First 30` 一旦输出够 30 行就停掉上游管道，Gradle 进程被终止、构建只跑了 8 秒却返回 exit 0，看起来像「跑完了」。门禁输出一律用 `Select-Object -Last N`（或先落文件再过滤）。同理：多任务 `--rerun` 要写在**每个任务名之后**（`gradlew :t1 --rerun :t2 --rerun`），只在末尾写一次时 Gradle 只重跑其中一个（实测摘要 `242 actionable tasks: 1 executed`，改成逐任务写法后才是 `7 executed`）。

76. **「卡片中间大字」不一定是客户端画的**（W54-C）：用户反馈视频页库卡中间有大字；只读 `GET /Items/{id}/Images/Primary`（该服务器对库图不校验鉴权）拿到的库封面 PNG 里居中就印着库名，`Images/Backdrop/0` 反而 404 → `LibraryEntryCard` 走 `backdrop ?: primary` 显示的正是这张图。判据：把同一 URL 的图落到本地看一眼，比在 Compose 里翻代码快；客户端"去大字"只能靠不用该图（换占位样式 / 让用户换封面），属数据 vs 呈现取舍。
77. **`internal` 类型不能从 public 属性上暴露**（W54-C）：`BookshelfViewModel.librarySelection` 最初写成 public，暴露 `internal data class BookshelfLibrarySelection` → 编译报 `'public' property exposes its 'internal' type argument`；改成 `internal val`（同模块的 Composable 可访问）。
78. **库级收藏走仓库的两个方法，别自己发请求**（W54-C）：`markAsFavorite` / `unmarkAsFavorite` 内部会写本地库、并把网络失败标记为「待同步」——UI 侧按乐观更新处理（先改状态、失败只记日志），与详情页收藏同一语义；选择器这类偏好则统一「写偏好 → SharedPreferences 监听重算」，别在同一次动作里既写偏好又手工重算（会重建 Pager / 重复请求）。

79. **同一包里两个「State」会直接撞名**（W51）：详情页单条目下载态（枚举 `DetailDownloadState`）与 ViewModel 状态快照（`downloadedIds` / `queuedIds` 数据类）最初同名，Kotlin 报 `Redeclaration` + 一连串「Enum types cannot be instantiated」「Cannot access constructor()」误导性错误。修法：枚举保留 `DetailDownloadState`（UI 语义），快照改名 `DetailDownloadSnapshot`，屏幕内临时变量同步改 `downloadSnapshot`（与页面参数 `downloadState: DetailDownloadState` 区分）。**教训：UI 状态与领域 / 快照同包时先定名，不要让两者都叫 State。**

80. **`UiText.asString()` 的无参重载是 `@Composable`**（W51）：`Downloader.downloadItem` 失败返回 `UiText`，Snackbar 文案在非 Composable 的映射函数里拼字符串；无参 `asString()` 只能在 Composable 里调用，非 Composable 上下文必须用 `uiText.asString(context.resources)`（`Resources` 重载）。错误提示 / 通知等「事件 → 文案」的搬运层都要注意。

81. **Kotlin 块注释可嵌套：KDoc 里写出 `/*` 会吞掉后文**（W58b）：`/** … */` 文档里出现「斜杠 + 星号」序列会开启**嵌套注释**，第一个 `*/` 只关掉内层，外层注释一直吞到文件尾 → 编译器先报某个文件的 `Syntax error: Unclosed comment`，同包其它文件同时变成一堆「Unresolved reference」（很容易误判成依赖 / 模块问题）。实例：注释里写「files/books 目录下的星号点 book」的原始写法（路径通配）触发。判据：多个互不相关的文件同时报 Unresolved reference，且其中之一报 Unclosed comment → 先全文搜注释里的 `/*`。

## 7. 日志

- **2026-10-04 W63 下载域缺陷修复（本会话，`fix/w63-download-fixes`，起点 master `a8a580f`）**：先读 `PROJECT_PLAN` §1–§5（真机纪律 v3）、`DOWNLOAD_PLAN` §21–§25、`UI_PLAN` D66–D72 / W58 多选框架、`UI_DESIGN_SYSTEM` §8 后开工（决策 D73）。①**真机复现（Pad 5）**：确认框数字本身正确（12 / 54 / 26），但**离开详情页会截断批量入队**（`JobCancellationException`，DB 只落前几集）；34 任务时下载页 **>60 s 不渲染**（OkHttp 逐条串行 `GET /Items/{id}`）；Snackbar「加入下载队列」>20 s 常驻；角标 12 但页面空白。②**修法**：`Downloader.enqueueItems` + 节目 / 季快照缓存（TTL 10 分钟、首次并发）+ `NonCancellable`（离开页面不截断，DB 12/12）；`CinefinCard.onLongClick` + `DownloadRows` 长按接 `ToggleSelection`；Season 页接 W58 多选框架（顶栏「已选 N 项 + 全选 / ×」+ 底栏「下载」）；`DownloadSnackbarDuration = Long` 覆盖 8 处调用；`Downloader.queueChanges` + `DownloadBadgeRules.activeBadgeCount`（去重 − 已下载）；下载页两阶段刷新（本地先上屏 + `mapBounded(4)` 服务器元数据、本地图存在时不再远程兜底）。③**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **719 项 / 0 失败 0 错误**（app 172 / core 77 / data 50 / player:local 110 / film 53 / book 113 / music 132 / player:core 12；新增 6 = film 5 + core 1）。④**真机（Pad 5 `43af8627`，13:39–14:40，device-lock 已写释放与结论）**：整剧 12/12（离开页面）、多季 54、全季 26、Season 长按多选（1 → 2 → 全选 12 → 批量 2 集）、下载页长按（12 / 26 项）、Snackbar ≈10 s 消失、角标 12 → 14 → 2 → 0 即时且数量正确、首帧 2.9 s（修复前 >60 s）、0 FATAL / ANR；测试下载 40 项全部删除、`files/downloads` 空、`/sdcard/w63*.xml` 清理。⑤**红线**：动 `core` / `app:phone` / `modes:film`（清单见 §5 W63）；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` / `data` 未动。详见 `DOWNLOAD_PLAN` §26。

- **2026-10-04 W60 图标重绘 + 下载失败重试 + 首页续读进度条（本会话，`feature/w60-icons-retry-resume-bar`，起点 master `9c65010`）**：先读 `PROJECT_PLAN` §1–§5、`DOWNLOAD_PLAN` §18 / §21、`UI_PLAN` 近况、`UI_DESIGN_SYSTEM` §2 / §4 / §5 / §7 / §8 后开工（决策 D68）。①**只读核对**：任务级失败分类 + 指数退避（`DownloadTaskRules` / `handleTaskFailure` / `wakeNetworkBlockedTasks` / WM CONNECTED 兜底）W50 已完整；缺口 = `ImagesDownloaderWorker` 吞掉图片失败恒 SUCCESS、下载页无「重试中」文案（`DownloadRows` 属 W59 / W60b 文件域，未动）。②**图标**：`ic_search` 重绘（r 7 / 手柄至 21,21）；新增 `ic_bookmark` / `ic_bookmark_filled`。③**进度条**：`FindroidItem` + `playedPercentage`（接口默认 null）、`FindroidFolder` / `FindroidMovie` 映射、`LandscapeItemCard.cardResumeFraction()`（runtime 换算 → `playedPercentage / 100` 回退）。④**worker 重试**：`ImagesDownloadRetryRules`（瞬时失败判定 + 3 次尝试上限）+ `ImagesDownloaderWorker` `.part` 临时文件改名 + `Result.retry()`。⑤**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **651 项 / 0 失败 0 错误**（app 152 / core 63 / data 45 / player:local 110 / film 40 / book 113 / music 128；新增 8）、含 `player:core` 全量 **663 项 / 0 失败**。⑥**真机（K60 `8e875894`，05:58–06:21，device-lock 已写释放与结论）**：搜索图标暗背景可读（媒体库顶栏截图比对）；`futuristic_tales` 续读进度条与「继续观看」同款、无进度书籍不画；「被狙击的学园」断网失败 → 自动重试 → 联网续传（残片 22.8 MB → 54.9 MB → 421 MB，DB `retryCount=1` / RUNNING / `nextRetryAt=0`）；0 FATAL / 0 ANR；测后下载删除 / 已播放复原 / 飞行模式关闭 / App force-stop / 临时文件清理。⑦**红线**：动 `core`（utils / work / res）、`app/phone`（`LandscapeItemCard`）、`modes/film` 无（未动）、**`data`（`FindroidItem` / `FindroidFolder` / `FindroidMovie` 进度字段，未在原白名单内，已申报）**；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` / `DownloadsScreen.kt` / `DownloadRows.kt` / `ItemCard.kt` / `ItemPoster.kt` / `LibraryScreen.kt` / `LibraryViewModel.kt` / `modes/music/*` 未动。

- **2026-10-04 W58b 视频 / 书籍多选批量（本会话，`feature/w58b-multiselect-video-book`，起点 master `5dc2c82`）**：先读 `PROJECT_PLAN` §1–§5、`UI_PLAN` D66 / §4 W58 接续指引 / §5 W58 验收、`UI_DESIGN_SYSTEM` §8.4/§8.5、`MUSIC_PLAN` D66–D69 / §2.16（行为参照）后开工（决策 D67）。①**共用框架复用**：core `MultiSelectState` / `cinefinSelectable` / `CinefinSelectIndicator` / `CinefinBatchBar` 原样复用，只补 `rememberMultiSelectState()`（`rememberSaveable` 包装 + 自定义 saver）；`ItemCard` / `LibraryListRow` 各加 3 个默认参数（老调用零改动）。②**落点**：新 `presentation/selection/`（`MediaBatchRules` 纯函数 12 项单测 / `MediaBatchViewModel`（Provider 仓库 + Downloader + ReaderRepository）/ `MediaBatchUi`（顶栏动作 / 工具条 / 删除确认框 / 事件文案）/ `VideoPlaybackLauncher`），`LibraryScreen`（视频库 + 书籍库 Library tab 网格 / 列表：页面层选中集合、全选取 `LazyPagingItems` 已加载、切 tab / 排序 / 筛选清空、返回键先退多选）与 `VideoScreen`（`collectAsLazyPagingItems` 上提到布局层 + 聚合网格多选 + 沙盒临时库视图）。③**播放入队（红线申报：`player:local`）**：`PlaylistManager.getInitialItemForQueue`（显式队列 + `preferredItemId` 定位，回退重启不跳回队首）、`PlayerViewModel.initializePlayer(queueEntries)`、`PlayerActivity` 新 `queueItemIds` / `queueItemKinds` extra + `parsePlaybackQueueEntries` 纯函数 5 项单测；剧集 → 下一集、季 → 第一集在选择侧解析成具体可播放条目。④**下载分流**：电影 / 单集直接入队；剧集 / 季复用 `DetailDownloadRules`（`EPISODE_FETCH_FIELDS` + 有媒体源判据 + 100 集上限 + 跳过已下载 / 队列内）按整剧补齐缺失集；书籍走 `ReaderRepository.downloadLocalFile`；顺带修下载页多选删除书籍空转（`DownloadsViewModel.deleteEntries` 对 BOOK 走阅读器删除）。⑤**删除红线**：只对「已下载且非本地媒体库」条目启用，确认框「删除本地下载 / 只删除本机文件与索引」，无任何服务器删除写操作。⑥**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun-tasks` **655 项 / 0 失败 0 错误**（app 148 / core 59 / data 45 / player:local 110 / film 40 / book 113 / music 128 / player:core 12；新增 17）。⑦**真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，03:43–04:19，device-lock 已写释放与结论）**：视频长按 / 点选 / 全选 17 / 取消 / ×；批量播放 2 部电影 → 队列面板顺序 = 列表序 + `KEYCODE_MEDIA_NEXT` 顺序切换（`播放队列补全完成：本次新增 1 项`）；批量下载 → 下载页 1 进行中 → 完成（1.83 GB，测后经批量「删除」清除）；标记已看 / 收藏 → 服务器筛选回读命中 2 项 → 二次触发复原；删除纯服务器条目置灰、已下载条目弹确认框并删除本机文件；书架三键（下载 / 标记已读 / 收藏）+ 全选 8 / 取消 / 退出 + 筛选回读 + 书籍下载 703 KB 落盘 `files/books`（下载后按钮随即置灰）；K60 视频 / 书架抽验通过；双机 0 FATAL / ANR。⑧**红线**：动 `player:local`（显式队列，已申报）、`core`（`rememberMultiSelectState`）、`app/phone`（`LibraryScreen` / `VideoScreen` / `ItemCard` / `PlayerActivity`）、`modes/film`（`DownloadsViewModel` 顺手修 + 3 语言文案 13 条）；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` 未动。

- **2026-10-03 W53B 侧栏「本地媒体库」子分组 + 本地库卡 16:9 缩略图（本会话，`feature/w53b-local-libs`，起点 master `9fcea57`）**：先读 `UI_DESIGN_SYSTEM` §2/§4/§5、`UI_PLAN` D52–D55 / D59 + 踩坑库与 W53 交接（B / C 两条）后开工（决策 D60）。①**B**：`NavigationIa` 新增 `SidebarLocalLibrary` / `sidebarLocalLibraries()`（按库级「在媒体库显示」过滤，0 库整组隐藏）/ `libraryChildCountVisible()` + 68dp 尺寸链纯函数；`DrawerViewModel` 新增 `localLibraries` 与 `refreshLocalLibraries()`（只读 Room，不重拉服务器；`load()` 改 `copy` 保留本地行）；侧轨「服务器库 → 分隔线 → 标题 → 本地库行」、抽屉把本地行切成带标题（`dividerAboveTitle`）的独立分组（同一份 `drawerEntries` 连续切片 → 索引与动作列表同源）；点行 → `LocalLibraryRoute`（`launchSingleTop`），导航变化与详情页设置变化各刷新一次；离线模式同样列出（本地库不依赖服务器）。②**168dp 取舍**：`TextMeasurer` 实测名称 / 项目数宽度，「完整名称优先」——名称 + 项目数放不下就省略项目数（服务器 4 字库名「音乐测试」不再截断）；抽屉 320dp 保持「有值就显示」。③**C**：`LocalThumbnailTile` 增加 `width` / `height`（默认 = `size`），本地库卡缩略图 40dp → **100×56.25dp（16:9）**，服务器库卡不动。④**红线**：仅 `NavigationRoot.kt`（已申报）；`AppPreferences.kt` 等其余红线零改动，无新增配色 / 字体 / 位图 / 字符串资源。⑤**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 任务 `--rerun` **565 项 / 0 失败 0 错误**（基线 559 + 净增 6）。⑥**遗留**：真机未跑（未申请设备窗口，清单见 §5 W53B 验收）。

- **2026-10-03 W54-D 首页模块与首页设置收口（本会话，`feature/w54-home-settings`，起点 master `9fcea57`）**：先读 `UI_DESIGN_SYSTEM` §2/§4/§5、`UI_PLAN` D39/D52–D59 + 踩坑库与首页 / 设置现状代码后开工（决策 D61–D62）。①**A 模块结构**：继续观看（MOVIE/EPISODE）/ 继续阅读（BOOK）/ 继续收听（AUDIO）三条独立走廊，`data` 仓库 `getResumeItems` 加 `includeItemTypes` 参数，后两条失败只隐藏走廊；「最近添加」拆视频 / 书籍 / 音乐三条（视频保留原海报墙，书籍 / 音乐同款竖版海报墙，各自开关）；「最新 · <库名>」逐库一条、逐库开关默认全开、按设置顺序排列、有内容才显示；删除旧 `uiHomeLibraryId` 单库过滤 + `views.take(3)`（bug ② 根因，实测核对通过）。②**B 设置页**：客户端设置 → 界面 → 首页 = 模块开关组 + 新「首页媒体库」组（每库一行 = 在首页显示 + 默认分页；末行媒体库顺序上下调整），「媒体库」子页的旧「首页媒体库」单选下线；`HomeLibrarySettings` 纯规则（顺序 / 开关集合 / 分页映射 / 编解码）落 settings 域供首页与设置共用；默认分页只落盘（选项与 W54-B `libraryTabs` 同源）。③**C bug ①**：`HomeAction.OnLibraryClick` 统一 dispatch → `homeViewAllRoute()` → 库内容页 + 本次进入的初始「最近添加」倒序（`LibraryRoute` 加可选 `sortBy/sortOrder`，不写全局偏好）。④**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、7 任务 `--rerun` **569 项 / 0 失败 0 错误**（基线 559 + 10：`HomeLibrarySettingsTest` 8 + `HomeViewAllRouteTest` 2）。⑤**红线**：`AppPreferences.kt`（删 2 键 / 加 8 键，已申报）、`NavigationRoot.kt`（`LibraryRoute` 排序参数 + 「全部」接线，已申报）；其余红线未动。⑥**遗留**：真机走查待设备窗口；默认分页未接库页 tabs；音乐库「全部」走既有兜底进音乐模式（D62 边界）。

- **2026-10-03 W54-C 视频页库选择 + 书架选库 + 顶栏收藏 / 睡眠入口（本会话，`feature/w54-video-page`，起点 master `ce58847`）**：先读 `UI_DESIGN_SYSTEM` §2/§4/§5、`UI_PLAN` D52–D55 + 踩坑库与视频页 / 书架现状代码后开工（决策 D56–D57）。①**A**：`VideoState` 拆 `allLibraries` / `libraries` / `selectedLibraryId`，顶栏 `LibrarySelectorChip`（「全部库」+ 各库 + 项目数），新增 `pref_ui_video_library_id`（已申报）→ 写偏好触发监听即时重算（库卡过滤 / 聚合流重建）；②**B**：`BookshelfViewModel` 新增 `librarySelection` + `pref_ui_bookshelf_library_id` 监听（即时重解析），顶栏经 `LibraryScreen.topBarActions` 注入（越界 1 处，已申报），≥2 个 books 库才显示；③**C**：收藏 = 当前库 `markAsFavorite` / `unmarkAsFavorite`（`FindroidCollection.favorite` 映射补齐 UserData），睡眠 = 矢量月牙 + 占位对话框（本体 W55）；④**D**：只读探针证明「中间大字」来自服务器库封面 PNG（`Images/Primary`；`Backdrop` 404），客户端未画中间标题，证据与三条建议口径上报；⑤**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、7 任务 `--rerun` **539 项 / 0 失败**（master 531 + 8）；⑥**遗留**：真机窗口、「中间大字」口径待用户拍板、`topBarActions` 槽位需在 W54-B 重写头部后重放。

- **2026-10-03 W51 下载粒度 + 详情页动作排重绘 + 侧栏角标 + 三项下载设置 UI（本会话，`feature/w51-download-granularity`，起点 master `ce58847`）**：读 `PROJECT_PLAN` §1–§5、`DOWNLOAD_PLAN` §18–§19、`UI_DESIGN_SYSTEM` §2/§4/§5/§8.1/§8.9/§8.12、`UI_PLAN` D41–D55 + 踩坑库后开工（决策 D56）。①**A 下载粒度**：Show / Season / Episode 三层；批量确认框默认「仅补齐缺失集」+ 默认上限 100 集（可关）；纯函数 `DetailDownloadRules` + 6 单测；Show 目标按需加载（`showViewModel.loadDownloadTargets`）、Season 复用页面剧集、Episode 单集；全部走 W50 自研引擎（活动队列幂等，不调 DownloadManager）。②**B 动作排**：`ItemButtonsBar` 重绘为「播放行 + 下载 / 已播放 / 喜欢 三标签键」（Outlined + 选中态）；已播放 / 喜欢乐观更新 + 失败回滚（三 VM）；取消 / 删除确认对话框改下载页同款 Lumen 面板。③**C Snackbar**：新 core `CinefinSnackbarHost`（§8.12）+ 三态 / 批量文案映射。④**D 角标**：`CinefinNavItem.badge` 槽位 + `CinefinCountBadge`（侧轨 + 抽屉；底栏无下载项）；`Downloader.activeItemIds()` 只读快照（不对账 / 不唤醒引擎），`NavigationRoot` 前台 RESUMED 期间 2s 轮询。⑤**E 设置**：仅 Wi-Fi（对既有键取反绑定，默认开）/ 同时下载数 1–3（默认 2，`PreferenceIntSelect`）/ 完成通知（默认开）；`AppPreferences.kt` 零改动。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，**538 项 0 失败 0 错误**（W56 基线 531 + W51 净增 7）。**真机未验**（待负责人设备窗口，清单 `DOWNLOAD_PLAN` §20.5）。新增踩坑 74 / 75。

- **2026-10-03 W56 视频海报状态徽标（本会话，`feature/w56-unwatched-badges`，起点 master `97e1cb6`）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D32/D33/D39/D52–D53 + 踩坑库）、`UI_DESIGN_SYSTEM` §4/§5/§8.4/§8.9、官方 OpenAPI `UserItemDataDto` 与既有卡片 / 仓库代码后开工（决策 D54）。①**数据核验（只读探针）**：`/Items`（媒体库网格 / 搜索 / 聚合）、`/Shows/{id}/Seasons`、`/Items/Latest`、`/Suggestions` 对 Series / Season 均返回 `UserData.UnplayedItemCount`，显式 `enableUserData=true` 与默认一致；Movie / Episode / `/Views` 恒空、NextUp / Resume 只有 `played` → 查询保持现状，仅保持既有 `FindroidItem.unplayedItemCount` 映射；②**规则**：`posterStatusBadge()`（容器 → 未看数 / 电影·单集 → 已看打勾 / 其余无）+ `unplayedItemCountText()`（`99+`）；③**接入**：`PosterItemCard`（补打勾）/ `ItemCard` / `LandscapeItemCard` / `EpisodeCard` 统一 `ItemStatusBadge`，去掉会把「已看完整剧」误打勾的 `item.played` 判断；`LibraryEntryCard` 死徽标位删除；④**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 `--rerun` **523 项 / 0 失败**（基线 516 + 7）；⑤**真机（21:21–21:37，Pad 5 主 + K60 抽验，device-lock 已写释放与结论）**：动漫网格 13/10/12/48/13/12 与服务器逐条一致、银魂 370 → `99+`、特别篇 S0E13 已看白勾 vs 第 1 季 12 集未看无角标、电影库 17 部无角标、首页走廊 23/12、搜索 `9` → 13、视频页库卡无未看数 + 聚合 13、K60 抽验一致、0 FATAL/ANR；真机拦下并确认的既有行为：库卡「共 N 个项目」取 `ChildCount`（与 W53 备注一致，非本波引入）。

- **2026-10-03 W53 真机验收（本会话续，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，19:02–19:26）**：先 **rebase 到 W50 master `8f3ba0e`**（唯一冲突 = `PROJECT_PLAN` 的 W50 / W53 更新段，保留两段人工合并），复跑门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、单测 `--rerun` **505 项 0 失败**（W50 基线 496 + W53 净增 9；app 90 / core 37 / data 45 / player:local 105 / film 6 / book 113 / music 109），双机装机 `Success`。验收：①侧轨顺序（首页 / 视频 / 音乐 / 书架 / 媒体库 / 下载）与视频行选中态像素取证（极光青指示条 `rgb(92,225,210)`）；②侧栏「视频」开关即时生效（关 / 开）；③库卡列表进库（电影库「共 17 个项目」）；④「视频显示方式」库卡 ↔ 聚合即时生效（聚合 = 电影 + 剧集混合网格，含未看角标 40 / 26 / 12 / 28，滚动连续）；⑤K60 底栏 4 Tab 顺序 + 选中态像素 + 抽屉「视频」条目 + 顶栏 logo 入口；⑥0 FATAL / ANR（双机 crash buffer + main log 过滤为空）。**真机拦下并修复 P1**：离线模式下视频页仍显示服务器库（`VideoViewModel` 直接注入 `JellyfinRepository` → 离线开关不生效，同踩坑 33）→ 改注入 `Provider<JellyfinRepository>`、每次 `load()` 按当前偏好解析；K60 复验离线 = 空态「暂无视频库」、退出离线模式后库卡恢复。副作用已还原（Pad 显示方式回库卡列表、K60 退出离线模式、双机 force-stop、`/sdcard` 临时文件删除），device-lock 已写释放与结论。

- **2026-10-03 W53 视频入口 + 视频模式页（本会话，`feature/w53-video-entry`，起点 master `1222bef`、rebase 到 W50 `8f3ba0e`）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN` D22/D27/D28/D32/D41–D51 + 踩坑 38–49 / 68–73、`UI_DESIGN_SYSTEM` §2/§4/§5、`NavigationIa.kt`（Compose 官方自适应布局页经镜像站 `developer.android.google.cn` 学习：Compact / Medium / Expanded + `currentWindowAdaptiveInfo`；项目自绘底栏 / 侧轨沿用现有形态分级，不引入 `NavigationSuiteScaffold`）。①**入口**（D52）：`NavEntryKey.Video` + 门控（movies / tvshows，未就绪保持可见）+ 顺序 首页 → 视频 → 音乐 → 书架 → 媒体库 → 下载；手机底栏改 **首页 / 视频 / 音乐 / 书架**（媒体库移出底栏、仍留侧栏 / 抽屉）；客户端设置「侧栏显示」加「视频」开关（`pref_ui_sidebar_show_video`，红线已申报）。②**视频模式页**（D53）：库卡列表（`LibraryEntryCard` 16:9，列宽四档）/ 聚合列表（`ItemCard` 竖版 + Paging 3，`VideoAggregatePagingSource` 按库顺序拼接、库内 `DateCreated` 倒序；游标纯函数 + 单测）两形态；「媒体库」子页新增「视频显示方式」下拉（`pref_ui_video_display_mode`，默认 `cards`，本页挂 `SharedPreferences` 监听即时生效）；空态 / 骨架 / 错误沿用现有组件；顶栏 `CinefinPageTopBar`（手机 logo / 平板 null）。③单测 +9（`NavigationIaTest` 3 + `VideoAggregateTest` 6）。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，**505 项单测 0 失败**（W50 `8f3ba0e` 上 rebase 后复跑）。真机待负责人统一调度。红线实际改动 = `AppPreferences.kt`（2 键）/ `NavigationRoot.kt`（接线），其余红线未动。

- **2026-10-03 W49 滑杆收尾（本会话，`fix/w49-leftover-cleanup`，起点 master `3dbca99`）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN` D41–D50 + 踩坑 64–67、`READER_PLAN` §7.16（A 项）、`DOWNLOAD_PLAN` §14.4（B/C 项）；对照本机 M3 1.4.0 `Slider.kt` 源码（`slideOnKeyEvents` / `sliderSemantics`）补键盘步进与 `setProgress` 档位吸附，新增 core `CinefinProgressVisuals` 与播放页 `MusicProgressBar` 同源（36dp / 4dp / 9dp / 2.6× / 0.45），纯函数单测 25 → 29。真机拦下并修复 **W44 回归**：`weight(1f, fill = false)` 下自绘 Canvas 宽度为 0（M3 Slider 填充 max、Spacer 取 min），阅读器三条滑杆自 W44 起不可见 / 不可触摸 / 不进 a11y 树；`fillMaxWidth()` 修复后双机 SeekBar 节点回归（230dp / 209dp），tap / 键盘步进 / EQ / RG 全部通过（新增踩坑 73）。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，486 项单测 0 失败（基线 477 + 9）。真机副作用已还原（Pad 5 + K60），见 device-lock。

- **2026-10-03 W46 导航与设置细节（本会话，`feature/w46-nav-settings`，起点 master `7fb250d`）**：读 `PROJECT_PLAN` §5、`UI_PLAN`（D22 / D24 / D27 / D28 / D32 / D41–D46 + 踩坑 38–49、64–67）、`UI_DESIGN_SYSTEM` §5/§8.6；Android 官方 adaptive / graphics 两页在本机 `curl` 超时（HTTP 000），按项目既有 `currentWindowAdaptiveInfo` 用法与 Compose 绘制知识执行。四条改动（D48–D50）：①侧栏 74% / 168dp / 文案精简（新增 `RailTranslucency`，底栏 82% 不动）；②手机四页顶栏统一 `ic_logo` 24dp；③平板取消抽屉（`openDrawer = null` + 手势关 + 切形态收抽屉）；④设置音乐库归位「媒体库」子页、删除「音乐」子页。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，471 项单测 0 失败。真机待负责人调度。
- **2026-10-03 W45 首页本地媒体卡片化 + 本地缩略图接入（本会话，`feature/w45-local-covers`，起点 master `d11f80d`）**：读 `PROJECT_PLAN` §1–§5、`DOWNLOAD_PLAN` §2.2/§12/§13、`UI_DESIGN_SYSTEM` §4/§5、`UI_PLAN` D39/D46 + 踩坑 20/29/65、`MUSIC_PLAN` W37 封面口径后开工（developer.android.com 直连 20s 超时不可达 → 改用官方镜像 `developer.android.google.cn` 学 `MediaMetadataRetriever` / `PdfRenderer`；Coil 3 行为用本机 3.6.3 产物字节码核对：`StringMapper` → `isFileUri` 对无 scheme 绝对路径为真）。①**规则纯函数**（data）：`LocalThumbnailRules`（`files/local_thumbs/<itemId>.jpg` + `.fail`、≤512 / JPEG 80、视频 1s→0s、候选「视频 → 书籍 → 音乐」、最多现场生成 3 张、CBZ 页图过滤）+ 9 项单测；②**提取**（app:phone `LocalThumbnailProvider`，`Semaphore(2)` + in-flight 合并 + `Dispatchers.IO`）：视频 `getScaledFrameAtTime`（旋转折算）→ PDF `PdfRenderer` 白底降采样 → CBZ `ZipInputStream` 首图（≤32MB）→ EPUB `modes:book` 新增 `LocalEpubCover`（Readium `Publication.cover()`）；音乐沿用 W37 链路；③**UI**：首页本地库卡（16:9 + 与继续观看同宽 + 类型角标 + 底部渐隐）、媒体库总览库卡 40dp 缩略图、列表行缩略图、详情头部 140dp 封面，无图回退类型图标。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 `--rerun` **471 项 / 0 失败**（data 42 含新增 9）。真机（13:20–13:32，Pad 5 主 + K60 抽验）：四条封面链路像素取证（`[30,59,89]` / `[20,111,70]` / `[140,30,29]` / `[90,105,57]`）、首页卡 558×314 与继续观看同宽同高、125 项库滚动 `gfxinfo` 缓存命中后 0.53% janky（p90 9ms）；**拦下并修复**「新建库空库阶段请求封面被永久缓存」（踩坑 67）；双机 0 FATAL / ANR，副作用已还原。分支已推送未合并。
- **2026-10-03 W44 开关对比度 + 统一滑杆 + 音乐手势（本会话，`feature/w44-music-detail`，起点 master `77feba9`）**：读 `PROJECT_PLAN` §1–§5、`UI_DESIGN_SYSTEM` §2/§4/§5、`UI_PLAN` D41–D45 + 踩坑库、`MUSIC_PLAN` W23/W24/W28/W30/W35/W39 后开工（developer.android.com `Slider` / `Switch` 两页本机 20 s 超时不可达，按任务书回退到项目设计系统与播放页自绘进度条口径）。①**共享组件**：`CinefinSwitch`（关闭态拇指 `onSurfaceVariant` / 轨道 `surfaceContainerHigh` / 描边 `onSurfaceFaint`，Prism + Lumen 自动切换）+ `CinefinSlider`（4dp 胶囊轨 + 18dp 圆点拇指 + 极轻柔光，`steps` 吸附 / 禁用 / RTL / 36dp 触控带），替换 8 处 `Switch` 与 4 类 `Slider`（EQ 五段 / RG 覆盖 / 阅读器），M3 默认 `Slider` 使用点清零；②**纯函数 + 单测**：`cinefinSliderFraction` / `cinefinSliderValueAt`（core 7 项）、`swipeGestureDirection`（music 5 项）；③**手势**：全屏左滑进歌词 / 右滑留空 / 左滑队列取消 / 下滑关闭，歌词页右滑返回，判定矩阵出闭包。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 `--rerun` **462 项 / 0 失败**。真机（12:20–12:50，Pad 5 主 + K60 抽验）：开关关闭态像素 `#A7B0BD` / `#222A36` / `#6E7887`（对比度 1.37:1 → **6.6:1**）、Lumen `#98A2B3` / `#171A21` / `#6B7483`、禁用态 3.69:1；EQ `+3.7 dB` 与 RG 覆盖 `+7.0 dB` 实时生效且落盘 / 清除可回读；左滑进词 / 右滑返回 / 下滑关闭 / 队列按钮全部命中；双机 0 FATAL / ANR，副作用已还原。新增踩坑 64–66；分支已推送未合并。

- **2026-10-03 W41 包名重命名 · 方案 A（本会话，`feature/w41-aurorama-package`，起点 master `410e20f`）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D36–D44 / 踩坑 1–61）、`ARCHITECTURE` §1、`DEV_ENVIRONMENT`，并经代理读 Android 官方 `applicationId` / `FileProvider` 文档后开工。①**身份**：phone / TV `applicationId` → `io.github.zhangwenkang.aurorama`（`.debug` / `.staging` 后缀保留），**Kotlin namespace / 包名 / 目录 / import / proguard / 清单内相对类名全部未动**；`fastlane/Appfile`、`tools/*` 7 脚本 `$Package`、PROJECT_PLAN / DEV_ENVIRONMENT / PLAYER_PLAN / READER_PLAN / TEST_PLAN 的 adb 示例同步；**仓库无自建 FileProvider**，唯一 `${applicationId}` 占位符（androidx.startup）随 applicationId 自动解析、合并清单实测无 authority；②**标识**：`JellyfinApi.CLIENT_NAME` `"Cinefin"` → `"Aurorama"`；③**关于页**：旧 `ic_banner` → `ic_logo`（120dp）；④**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 `--rerun` **450 项 / 0 失败**；`aapt` 双 APK `package='io.github.zhangwenkang.aurorama.debug'`；⑤**双机**（窗口 07:33–11:02，装机 / 重登由负责人完成）：关于页品牌 / SAF 失效（预期）/ 播放 + 媒体通知 / 下载 + 删除 / 离线开关全通过，Pad 5 冷启动 1501 ms、K60 905 ms，0 FATAL / ANR，旧包双机卸载；新增踩坑 62–63。详见 §5 W41 与 `device-lock.md`。
- **2026-10-03 W43 搜索「媒体库与本地」（本会话，`feature/w43-search-local`，起点 master `7a9f393`）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D39 / D41–D43 / 踩坑 55）、`DOWNLOAD_PLAN` §2.2-9 / §12 / §13、`UI_DESIGN_SYSTEM` §2/§4/§5 后开工（developer.android.com 本机不可达，按项目既有 `SearchBar` / `SearchViewModel` 实现与踩坑 55 口径执行）。①**合并**：`SearchViewModel` 并行查服务器 + 本地；`LocalLibraryRepository.search` = `local_media_items` 全量 + `LocalLibrarySearch` 纯函数（归一化 / 大小写 / 扩展名 / 内嵌标签标题；5000 条 3.80 ms，不做 Room LIKE / Schema 迁移）；②**UI**：`SearchState` 拆双列表 →「服务器」/「本地」分区 + 计数 +「本地」来源徽标 + 本地行（类型图标 / 名称 / 库·文件夹·时长·大小）；占位符「搜索媒体库与本地」、空态「未找到「xxx」」、空分区不渲染；③**打开**：视频 / 书籍 / 音乐复用 W37 链路（抽出 `playLocalMusicFolder` 与详情页共用），音乐起播后跳音乐 Tab；④**红线**：仅 `NavigationRoot.kt`（3 个本地回调，已申报）；⑤**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 `--rerun` **450 项 / 0 失败**（data 新增 `LocalLibrarySearchTest` 6 项）；⑥**真机**（2026-10-03 06:45–07:00，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）：搜 `Kindaichi` 本地命中并打开阅读器、搜 `e` 双分区（服务器 14 + 本地 3）、搜 `9` 服务器回归、`futuristic` 空态、临时音乐库验证音乐链路 `state=3` + 跳音乐 Tab、K60 无本地媒体时仅服务器分区；0 App FATAL / ANR；设备副作用已还原（`device-lock.md`）。新增踩坑 60–61。
- **2026-10-03 W42 客户端设置 + 侧栏改版（本会话，`feature/w42-settings-rail`，起点 master `d6ad983`）**：读 `PROJECT_PLAN` §1–§7、`UI_PLAN`（D17–D40 / 踩坑 1–56）、`UI_DESIGN_SYSTEM` §2/§4/§5/§7/§8/§9、`ROLE_SKILLS` §5.3 后开工（developer.android.com 本机 30s 超时 ×2 不可达，按官方既有规范 + 项目权威文档执行）。①**设置页**：13 分类 → **5 组**（D41；`SettingsGroupLayout.kt` 纯函数分桶 + 5 项单测；「用户」行由账号卡承接、本地媒体提升为顶层、下载与缓存合并、外观从界面拆出、音乐独立、桌面歌词改子页）；行样式统一（24dp 图标 + 单行描述 + 右侧 44dp 控制位、行高 60dp 基线）；文案精简（en / zh-rCN 同步 5 条 + 新增 20 余条）；**16 个语义图标**重绘 / 新增（24dp / 1.75dp / 圆角端点，无新增色）。②**侧栏**：72 / 150dp、82% 半透明石墨（先铺页底衬底，真机 `#0F1216`）、48dp 行高、24dp 图标、3dp 极光青左缘指示条、未选中图标白 62%、hover / pressed 提亮、内容 / 管理分组空隙 + 细线、「客户端设置」固定底部设置区（发丝线分隔）；core `CinefinSideRail` / `CinefinNavigationItem` / `CinefinBottomTab` 与 app 侧 `CinefinSideNavigation` 同源改版。③**隐藏底栏**：`pref_hide_bottom_bar`（红线 `AppPreferences.kt` 已申报）+ `DrawerViewModel` 监听 + `NavigationRoot` 紧凑形态隐藏（红线 `NavigationRoot.kt` 已申报）；平板开关置灰 + 说明；「设备」行接系统应用信息页（上游遗留孤儿分类，未新建功能，见 D43）。④**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；单测 `--rerun` **444 项 / 0 失败**（app 79 / core 18 / data 27 / player 104 / film 6 / book 106 / music 104；新增 `SettingsGroupLayoutTest` 3 + `NavigationIaTest` 2）。⑤**真机**（2026-10-03 05:58–06:09，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，窗口负责人批准）：5 组 / 文案 / 子页、行高 60.9dp、侧栏 72·150dp、`#0F1216` 半透明、3dp 青指示条、图标 62% 白、底栏半透明、隐藏底栏开 / 关 / 重启保留 / 平板置灰全部命中，0 FATAL / ANR；详见 §5 W42 与 `device-lock.md`。新增踩坑 57–59。
- **2026-10-03 W40 字体修正波（本会话，`feature/w40-misans-font`，起点 master `6273346`）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D8 / D36–D39 / 踩坑 50–55）、`UI_DESIGN_SYSTEM` §3；官方 MiSans 许可协议节逐条核验（developer.android.com 本机不可达，Compose 字体 API 改从 Google Maven 官方 `ui-text 1.12.1` 源码包核对）。①**字体替换**：官方压缩包（227,880,072 B）解出 `MiSansVF.ttf`（20,093,424 B，SHA-256 `0DDEF906…115E79`）原字节入库 `core/src/main/assets/fonts/`，`CinefinType.CinefinSans` 四档 wght（`MisansFont.kt`：`AndroidFont` + `TypefaceLoader` 解析期加载，静态 token 无全局状态、预览可用）；删除 Noto 子集与 OFL 文本；②**注明义务**：关于页 `misans_attribution`（en/zh-rCN）+ `assets/licenses/MiSans-License.txt`；③**文档更正**：D38 更正三条款、D40 新决策；④**品牌遗留注释**：`web-console-skin.css` / raw 副本 / `web-console-theme.css` + 3 个红线文件注释（已申报）；⑤**门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 `--rerun` **439 项 / 0 失败**（core 新增 `MisansFontVariationTest` 2 项）；⑥**体积**：arm64 debug `119.09 MiB → 137.55 MiB（+18.45 MiB）`——未压缩存储（`noCompress += "ttf"`）换 mmap 与 API 28 兼容，比任务书 ≈130 MiB 预估高 ≈7.5 MiB，已记 D40 / 踩坑 56；⑦**真机回归**（2026-10-03 05:22–05:25，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，窗口由负责人批准）：冷启动 1481 / 988 ms，首页 / 媒体库 / 音乐 / 书架 / 客户端设置 / 关于（MiSans 注明命中）/ 阅读器全通过，字重 400–700 可见差异，Pad 5 首页 `Native Heap PSS 29,596 KB` 无压缩 asset 原生膨胀，0 FATAL / ANR，副作用已还原（`device-lock.md`）。
- **2026-10-03 W39 媒体库总览改版（本会话，`feature/w39-media-library-polish`，起点 master `e0686b9`）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D22–D38 / 踩坑 29–54）、`UI_DESIGN_SYSTEM` §2/§4/§5、`MUSIC_PLAN` §5、`DOWNLOAD_PLAN` §12/§13 后开工（决策 D39）。①**搜索收口**：页内常驻 M3 搜索框下线、只留顶栏图标入口；真机拦下「`weight(1f)` → 输入框撑满 / 结果区 0 高」缺陷并修复（踩坑 55），输入 `9` 命中 `9-nine-` + 徽标 13。②**收藏进顶栏**（`ic_star`）→ 收藏页正常。③**两段式**：本地媒体库（标题行 +「＋ 新建」）→ 服务器媒体库（`SectionHeader` + 16:9 大卡首屏可见）；旧整行收藏卡 / 本地大卡 / 长说明全部下线，说明移入新建对话框。④**文案去重**：纯函数 `localLibraryCardDetail` + 4 项单测。⑤**开关归位**：客户端设置「媒体库」分类新增「首页显示本地媒体」（en/zh-rCN 字符串），媒体库页不再出现；真机关 → 首页区块消失 / 开 → 复原，pref 实测同步。⑥**一致性**：`LocalLibrarySection` 去内边距，在线 / 离线两处一致（离线实测同一区块、无服务器段）。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 437 项 / 0 失败（app 74 / core 16 / data 27 / player:local 104 / film 6 / book 106 / music 104）。真机 Pad 5 主 + K60 抽验，0 FATAL / ANR；设备副作用已还原（详见 §5 W39 与 `device-lock.md`）。新增踩坑 55。
- **2026-10-03 W38 品牌波 · 第一段（本会话）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D8 字族兜底 + 品牌波待办 + 踩坑 1–49）、`UI_DESIGN_SYSTEM` §3 字体 / §7 图标后开工。①**改名**：`app_name` 覆盖默认 / `values-en`（新增）/ zh-rCN / zh-rTW + debug、staging 变体，launcher label（phone + TV）、首连向导、抽屉 / 首页顶栏 / 冷启动 / 播放器兜底标题随 `CoreR.string.app_name` 自动生效；setup 17 语言 `welcome*` 与 core 33 语言 `privacy_policy_notice` 词面更名（fi / cs / et / sl / tr / az 修正格位）；`applicationId` / 包名 / 内部资源名 / `CLIENT_NAME` 不动（W39）。②**图标**：方向 1「极光帘幕」自适应前景 + 背景 + monochrome（`anydpi-v33`）+ 深色（`values-night`）+ `ic_logo` 同构，全矢量、无新增位图；本地光栅化 QA 通过（含圆形蒙版）。③**字体**：MiSans 许可核验为「不得改编 / 不得再分发」→ 改打包 Noto Sans SC（OFL 1.1，3755 常用字 + 应用文案子集 2.32 MB）与 Literata（OFL 1.1，正体 + 斜体 0.37 / 0.36 MB），`CinefinType` 换真字族 + `FontVariation` 四档；许可全文落 `assets/licenses/`。门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，单测 428 项 / 0 失败；同基线 arm64 debug APK `117.19 → 119.09 MiB`（+1.90 MiB）。待办：布局回归真机窗口（等负责人调度）+ W39 包名段。新增踩坑 50–54。
- **2026-10-02 W15-UI（本会话）**：读 `PROJECT_PLAN` §1–§5、`READER_PLAN` §7.7.5 + 踩坑 20、`UI_PLAN`（D15–D26 / 踩坑 20·23·39·49）、`UI_DESIGN_SYSTEM` §4.4/§8、`docs/design/s1-direction-a/README.md`、`device-lock.md` 后开工。完成两项：①**阅读页顶栏避让状态栏**（`ReaderTopBar` 56dp 行补 `statusBarsPadding()`，与 W8 `CinefinPageTopBar` 同款、状态栏区由 chromeColor 铺满；平板竖屏 / 横屏 / 手机形态三形态取证按钮 top ≥99 > 状态栏 60px；设置面板 / 页指示复核无遮挡——结论已写 `READER_PLAN` §7.8 + 踩坑 20）；②**平板首页竖版海报等宽修复**（D35：页边距从首末列 item padding 改为网格 `contentPadding`、`BoxWithConstraints` 按实际宽度算列数、hero / 走廊 / 区块标题同步去重；平板 3 列 392×651 等宽、手机 2 列 466×772、横屏 5 列 397/397/396/396/396）。门禁 `assembleDebug + ktfmtCheck + app 49 项单测` 全绿；Pad 5 逐项 dump 取证，设备副作用已还原（wm/旋转 reset、App force-stop、临时文件清理）。分支 `feature/w15-reader-topbar-posters`，未合并 master。
- **2026-10-01 W8-R3（本会话）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D17–D31 / 踩坑 1–45，重点 W7-R3 小节与 D22/D24/D25）、`ROLE_SKILLS` §5.3、`UI_DESIGN_SYSTEM` §2/§4.4/§8、`docs/design/s1-direction-a/README.md`、`device-lock.md` 后开工。完成用户复测反馈 4 组（决策 D32–D34）：①**A 三页共用顶栏 `CinefinPageTopBar`**（core：56dp + `statusBarsPadding()` + 44dp 键 + 随窗口页边距 + 标题 / 计数排版 + Lumen 发丝线）接入媒体库 / 音乐 / 书架，音乐旧 72dp `MusicHeader` 下线（被状态栏遮挡的根因），书架顶层改 `LibraryScreen(topLevel = true)` →「书架 / 共 N 本」且不再出现返回箭头与库名「书籍」；②**B 媒体库改版**：库卡 = 类型图标磁贴（雾灰 + 发丝线 + 强调色图标）+ 库名 + 项目数（服务器 `ChildCount` → `FindroidCollection.itemCount`，`getLibraries` 显式请求字段）+ 三级灰箭头，面板 / 渐隐改 Lumen 石墨与 `Scrim`，标题与计数并入顶栏同排；③**C 侧栏「媒体库」分组回退**：`navEntryKeys` = 首页 → 音乐 → 书架 → 媒体库 → 下载 → 控制台 / 资料管理器 → 客户端设置，`MEDIA_GROUP_DEFAULT_EXPANDED = false`，抽屉「媒体库」行补侧轨同款行尾箭头（`CinefinNavItem.trailing`），展开后才显示库子项；④**D 不回归**：首页 `ic_logo`、手机抽屉与底部 Tab、音乐 / 书架页面内容皮肤、侧柜常驻 Lumen、控制台胶囊与选中态全部复核通过。门禁 `assembleDebug + ktfmtCheck + app 23 项 / core 16 项单测` 全绿；Pad 5（平板 1280dp + 手机形态 411dp）与 K60（原生竖屏）逐条 dump / 像素采样取证，设备副作用已还原。新踩坑 46–49。分支 `feature/r8-ui-unify`，未合并 master。
- **2026-10-01 W7-R3（本会话）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D17–D26 / 踩坑 1–40，重点 38–40 与 D22/D24/D25）、`ROLE_SKILLS` §5.3、`UI_DESIGN_SYSTEM` §2/§4.4/§8.6、`s1-direction-a/README.md`、`device-lock.md` 后开工。完成用户复测反馈 5 项（决策 D27–D31）：①手机恢复抽屉入口 + 首页顶栏换 `ic_logo` 作 app 图标 / 侧栏键；②二级库列表进抽屉（紧跟「媒体库」、缩进 48dp 行、先于音乐 / 书架）+ 抽屉条目区可滚动；③侧柜常驻 A · Lumen（`lumenChrome = true`），音乐 / 书架 / 阅读**内容**皮肤不变；④控制台页右下角新增 A 风格悬浮「返回影阁」胶囊（系统返回一次回主界面行为保留，未恢复双侧栏）；⑤`consoleEntrySelected` 对 `null` 不再回退 `/dashboard`，退出控制台不留选中。门禁 `assembleDebug + ktfmtCheck + app 22 项 / core 16 项单测` 全绿；Pad 5（平板 1280dp + 手机形态 411dp）与 K60（原生竖屏）逐条像素采样 / dump 取证，设备副作用已还原。新踩坑 41–45。分支 `feature/r7-nav-fix`，未合并 master。
- **2026-10-01 W6-VIS（本会话）**：读 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D17–D23 / 踩坑 1–37 / W6-R6N / W6-WEB）、`UI_DESIGN_SYSTEM` §2.6/§5/§6/§7、`s1-direction-a/README.md`、`LumenColors.kt` 与 film / settings / navigation / core components 代码地图后开工。完成三件事（决策 D24 / D25 / D26）：①Lumen 全域化（含 `ProvideLumen` 内嵌 M3 色板与跨域页统一处理、侧柜皮肤跟随 `lumenChrome`）；②侧栏 / 底栏 / 抽屉 / 客户端设置视觉重设计；③加载过渡（`LumenSkeleton.kt` + 冷启动品牌页 + 7 处页面接入）。门禁 `assembleDebug + ktfmtCheck + app/core 单测` 全绿；真机 Pad 5（1280dp 平板形态）与 K60（原生竖屏）像素采样与 4 连拍帧取证通过，设备副作用已还原。新踩坑 38 / 39 / 40。分支 `feature/r6-visual-all`（提交与推送见交接报告），未合并 master。
- **2026-09-30 W1-R3（本会话）**：读齐 `PROJECT_PLAN` §1–5、`UI_DESIGN_SYSTEM` v1.0 全文、`s1-decision`、`REQUIREMENTS` §6/§10/§12、`ARCHITECTURE` §2.4、`SESSION_BRIEFS` W1-R3、`PARALLEL_PLAN` §1.3/W1、`ROLE_SKILLS` §5.3（在线校验 5 篇官方文档）；完成 token → Compose 主题映射、Typography 归位 + 桥接、4 类基础组件 + 预览 + 13 项单测；验收命令与真机走查通过。分支 `feature/r3-ui-tokens`。
- **2026-09-30 W3-R3（本会话）**：读齐 `PROJECT_PLAN` §1–5、`UI_PLAN`、`UI_DESIGN_SYSTEM` §2.3–2.6/§4/§8–§10、`READER_PLAN`（D7–D10 + §9 遗留）、`MUSIC_PLAN`（W2 交付 + 踩坑）、`SESSION_BRIEFS` W3-R3、`PARALLEL_PLAN` §1.3/W3、`ROLE_SKILLS` §5.3；完成 core 三件剩余组件 + 音乐 / 阅读页 Prism 接入 + `NavigationRoot` 路由注册（音乐 + 书籍→阅读器）与 `exported=false`；门禁与真机走查（含手机形态、纸色主题色值采样）通过；顺带修正 `kind == "Book"` 大小写 bug（见踩坑 10）。分支 `feature/r3-ui-pages-a`。
- **交接提示（下一会话）**：① 负责人确认书籍入口对 PDF / CBZ 的影响面（§5 未决项）；② 歌词面板由 R2-LYRICS 并入 `MusicModeScreen`（本会话已把浏览 / 底栏 / 队列拆成独立私有 Composable，冲突面小）；③ W4-R3 继续剩余组件与其余页面换新，届时删 `LegacyTypography` 桥接。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
- **2026-09-30 W3-R3 · P0 插播修复（同会话继续）**：负责人验收发现两条阻断链路。①书籍点不开 = `kind` 大小写不匹配（本会话早前已修，本次按真实路径复验通过）；②音乐库空列表 = `LibraryViewModel` 只查 `MUSIC_ALBUM`、服务器无该实体 → 在 `NavigationRoot` 加 `libraryEntryRoute`，音乐库三处入口（媒体库卡片 / 抽屉 / 搜索）+ `LibraryRoute` 兜底统一进 `MusicModeRoute`。真机复验两条链路全通过（§5「P0 链路复验」），未改 `modes:film` 任何文件。提交 `fix(ui): 修复书籍入口与音乐库空列表两条 P0 链路`。
- **2026-10-01 W4-R3（本会话）**：读齐 `PROJECT_PLAN` §1–5、`UI_DESIGN_SYSTEM` §2–§10 全文、`UI_PLAN`（W1/W3 + 16 条踩坑）、`REQUIREMENTS` §6/§10/§12、`SESSION_BRIEFS` W4-R3、`PARALLEL_PLAN` W4、`ROLE_SKILLS` §5.3，并通读 core 组件与音乐 / 阅读页参照。完成：①导航骨架（手机底部 4 tab / 平板侧轨 88↔164 / `CinefinModalDrawer` 抽屉，D15 / D16）；②首页 / 媒体库 / 搜索换新（卡片描边 + 3dp 媒体色进度 + 中性徽标 + 窗口分级边距）；③详情 / 下载 / 设置 / 欢迎与首连流程换新（`CinefinButton` 四态、操作键方圆形、`CinefinEmptyState`）；④删除 `LegacyTypography` 桥接统一 Prism 字阶；⑤门禁 `:app:phone:assembleDebug ktfmtCheck` 通过；⑥K60 双形态真机走查（手机全流程 + 宽屏侧轨折叠 / 展开 + 像素采样，见 §5 W4 验收）。修复抽屉索引错位 bug（踩坑 17）。分支 `feature/r3-ui-pages-b`。
- **交接提示（下一会话 / 负责人）**：① `MaterialTheme.spacings` 6 档桥接（44 文件约 220 处）与 `HomeHeader / HomeCarousel / HomeCarouselItem` 死代码待后续收敛；② 真平板（Pad 5 横屏）走查需负责人分配窗口（本波用 K60 + `wm` 覆盖验证，Pad 5 归 PLAYER-STAB）；③ 播放器覆盖层 / 面板的字阶随 `LegacyTypography` 删除变 Prism，播放器线 W4 PLAYER-UI 需在收口时复核面板排版；④ `settings/components/*` 内部卡片仍是 M3 组件（色板已 Prism），如需完全组件化可另开小波次。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
- **2026-10-01 W5-R3（本会话）**：读齐 `PROJECT_PLAN` §1–§5、`UI_PLAN`（W1/W3/W4 + 19 条踩坑）、`UI_DESIGN_SYSTEM` §2–§10、`s1-decision`、**`s1-direction-a/README.md` + `home / detail / phone / library` 四张稿**、`REQUIREMENTS` §6、`SESSION_BRIEFS` W4-R3、`ROLE_SKILLS` §5.3；核对了 `presentation/film/*`、`presentation/navigation/CinefinDrawer.kt`、`core/.../components/*`。完成四条反馈：①首页与视频详情改 S1 流光手法（D17，新增 `LumenSurface` / `DetailPoster` / `LumenInfoTable` / `ItemFormatting` 四个组件文件，重写 `HomeHero` / `SectionHeader` / `LandscapeItemCard` / `PosterItemCard` / `ItemCard` / `ProgressBar` / `HomeSection` / `HomeView` / `MediaScreen` / `LibraryScreen` / `MovieScreen` / `ShowScreen`）；②媒体库去拥挤（D19：栅格放大 + 行距 24dp + 大标题层级 + 去发丝线）；③侧柜取消「更多」分组（D18）；④所有入口行为一致（`showNavigation` 统一集合，书架无库退媒体库）。门禁 `:app:phone:assembleDebug ktfmtCheck` + `:core:testLibreDebugUnitTest` + `:modes:film:testDebugUnitTest` 全绿。分支 `feature/r3-ui-lumen`。
- **交接提示（下一会话 / 负责人）**：① **真机走查未做**（Pad 5 / K60 当前未连接）——重点断言「设置 / 书架 / 控制台 页面侧轨是否常驻」「手机抽屉选择后是否关闭」「首页头图 21:9 裁切」「详情三栏 1280dp 呼吸感」，走查前按 `device-lock.md` 登记；② 若负责人本意是"抽屉里的分组标题也一并取消"，本波已按此实现（抽屉无任何分组标题）——需要恢复分组时只改 `NavigationRoot` 里 `drawerGroups` 一处；③ 播放器线仍在改 `player/*` 与 `presentation/player/*`，本波未触碰（避免覆盖层冲突）；④ `MaterialTheme.spacings` 桥接与 `HomeHeader / HomeCarousel*` 死代码仍未收敛。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
- **2026-10-01 W5-R3F（本会话，UI 验收缺陷热修）**：读齐 `PROJECT_PLAN` §1–§5、`UI_PLAN`（W5 + 24 条踩坑）、`UI_DESIGN_SYSTEM`（§2.5 / §2.6 / §5.2 / §5.3 / §6 / §9.4）、`docs/design/s1-direction-a/README.md`。真机复现 4 项验收缺陷（K60 首页 `S[].parentIndexNumber`、冷启动点「书架」落媒体库、媒体库上滑搜索框遮挡、流光观感过弱）。修复：① `episodeCode` / `cardMetaLine` 显式插值 + 眉标 `maxLines`/ellipsis + 2 项单测；② 新增 `BookshelfRoute` / `BookshelfScreen` / `BookshelfViewModel`（`pickBooksLibrary` 3 项单测）+ 抽屉数据启动预载 + 库内容页空态；③ `MediaScreen` 搜索框改不覆盖布局；④ `LumenSurface` 渐变描边 / 内高光 / 外发光 + `lumenTopGlow` + 重调 `lumenBottomScrim`。门禁 `:app:phone:assembleDebug ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`(5) + `:core:testLibreDebugUnitTest` + `:modes:film:testDebugUnitTest` 全绿；Pad 5 横屏 / K60 竖屏逐条复验通过（含像素采样：卡外 25.3 → 35.1）。分支 `feature/r3-ui-hotfix`。
- **交接提示（负责人 / 下一会话）**：① **踩坑 28 需单独一波**——`showNavigation` 对带参路由（Library / Settings / Console）恒不命中，D18 的"侧轨常驻"实际没生效；本波只把书架换成 data object 路由，其余未动；② 书架解析每次进入会多 1–2 次 `Items?limit=1` 查询（本地服务器 < 300ms，可接受；若嫌慢可在 `BookshelfViewModel` 里加内存缓存 / `hasRoute` 复用抽屉库列表）；③ 本波顺带发现 `LibraryScreen` 原先没有空态（空库=空白），已补 `CinefinEmptyState`；④ `MaterialTheme.spacings` 桥接与 `HomeHeader / HomeCarousel*` 死代码仍未收敛（W4/W5 遗留）。
- **2026-10-01 W5-R3G（本会话，导航侧轨常驻修复）**：读齐 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D18 / 踩坑 28 / W5-R3F 交接）、`UI_DESIGN_SYSTEM` §8.6，核对 `NavigationRoot.kt` 的路由定义 / `composable<T>` 注册 / `chromeDestinations`。修复踩坑 28：`currentRoute == Route::class.qualifiedName` 改为文件内 `NavDestination?.isRoute<T>()`（`NavDestination.Companion.hasRoute`，按序列化器哈希匹配，带参 / 默认值路由均可命中），覆盖 `showNavigation` 8 个统一目的地与 `currentLibrary` / 五个 `selected` 判断；未改导航栈与抽屉动作。门禁 `:app:phone:assembleDebug ktfmtCheck` 通过。真机：Pad 5 横屏（home / library / downloads / music / bookshelf / settings 侧轨 6 项常驻，设置 / 书架 / 首页高亮像素采样；进入书籍库时"书架"高亮——`currentLibrary` 修复点）与 K60 竖屏（4 tab 切换选中态逐项验证、进设置无残留高亮、返回恢复）通过；发现并记录控制台入口缺失（D18 提交 `20c4fe3` 删除后未接回，见 W5-R3G 小节的"发现"）。分支 `feature/r3-ui-navfix`。
- **交接提示（负责人 / 下一会话）**：① **控制台 / 媒体资料管理器入口待决策**——`ConsoleRoute` 判定已修好，但 UI 上没有入口（D18 删除后未接回 `chromeDestinations`，且需接 `DrawerViewModel.isAdmin`）；恢复入口属功能变更，本波未动。② 若后续把 `chromeDestinations` 改为按管理员动态生成，`showNavigation` 的 `isRoute<ConsoleRoute>()` 已就绪，无需再改判定。③ `MaterialTheme.spacings` 桥接与 `HomeHeader / HomeCarousel*` 死代码仍未收敛（W4/W5 遗留）。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
- **2026-10-01 W5-R3H（本会话，流光 A 配色 + 亮图文字 + 手机 hero 排版）**：读齐 `PROJECT_PLAN` §1–§5、`UI_PLAN`（W5 + 28 条踩坑）、`UI_DESIGN_SYSTEM` §2 色彩系统 / §2.6 媒体色纪律、`docs/design/s1-direction-a/README.md` + `home/detail/phone` 三张稿与渲染源 CSS 变量、`s1-decision`；核对 `presentation/film/*`、`components/LumenSurface.kt`、`core/.../theme/*` 与 `CinefinButton`。完成：①新增 `core/.../theme/LumenColors.kt`（`LumenTokens` / `LumenColors` / `LocalLumenColors` / `ProvideLumen`，A 稿色值唯一落点，D20）并接入首页 + 电影 / 剧集 / 季 / 集详情；`CinefinButton` 新增 `CinefinButtonTone.Inverse`（月白主按钮）；`LumenCardFrame` / `SectionHeader` 在 Lumen 区域改用 A 稿描边与三级灰；②`TextStyle.lumenTextShadow(Title/Meta)` + `lumenSideScrim`（A 稿左侧水平渐隐）应用到首页 / 详情头图文字；③定位并修复踩坑 29（手机 hero 高度不足 → 行动区被压缩测量），改 `heightIn(min = 比例高度)` + `FlowRow` + `softWrap = false`。门禁 `:app:phone:assembleDebug ktfmtCheck` + `:core:testLibreDebugUnitTest`（16 项）全绿；Pad 5 横屏（首页 / 电影详情 / 集详情）与 K60 规格手机形态（1080×2400 @420）真机自检通过（截图自检不入库）。分支 `feature/r3-ui-lumen-a`。
- **交接提示（负责人 / 下一会话）**：① **K60（`8e875894`）当前未接入**——手机形态以 Pad 5 + K60 规格 `wm` 覆盖验证，K60 上线后建议补一次真机复验（步骤见 §5 W5-R3H 验收）；② 季 / 集详情页仍是旧版布局（`MaterialTheme.spacings` 桥接 + 旧字阶），本波只换配色与文字阴影，未做 Lumen 版式重排，如需统一到 W5 版式另排一波；③ 底部 Tab / 侧轨等导航 chrome 仍走 Prism（`ProvideLumen` 只包页面内容），这是"局部覆盖不扩散"的刻意边界，若要一并换色需先解决 chrome 与音乐 / 阅读共用的问题；④ `MaterialTheme.spacings` 桥接与 `HomeHeader / HomeCarousel*` 死代码仍未收敛（W4/W5 遗留）。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。

- **2026-10-01 W5-R3I（本会话，控制台入口恢复 + 管理员门控）**：读齐 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D18–D20、W5-R3G 小节与「发现」、踩坑 28）、`ARCHITECTURE` 导航相关章节、`REQUIREMENTS` §7（控制台属官方对齐批次 1，走 Web 兜底）；`git log -S metadataRoute` / `-S 服务器控制台` 追溯到入口由 `c3241fa` 建立、`20c4fe3`（D18）删除，历史实现用 `DrawerViewModel.isAdministrator` 门控 + `ic_globe`/`ic_database` + `ConsoleRoute()`/`ConsoleRoute(path = "/metadata")`。落地：①`chromeDestinations` 接回两条入口（`bottom = false`，手机底部 Tab 不变，侧轨 / 抽屉可见）；②门控 / 路径 / 选中态抽成纯函数 `consoleEntrySpecs` / `consoleEntrySelected` + 路径常量收口，补 `ConsoleEntrySpecTest` 3 项；③选中态改按 `ConsoleRoute.path` 判定，避免两条同亮；④独立 `navigateConsole` 关闭 `saveState/restoreState`（踩坑 30：同目的地 id 会把旧 `/dashboard` 条目恢复出来）；⑤`WebConsoleScreen` 种子页不再留历史，返回一次即离开控制台（踩坑 31）。门禁 `:app:phone:assembleDebug ktfmtCheck` 通过、`:app:phone:testLibreDebugUnitTest` 8 项全绿；真机 Pad 5（`43af8627`，2560×1600 @320 ≈ 1280dp）+ K60（`8e875894`）——非管理员隐藏（侧轨 6 条 / 抽屉无控制台条目）、临时 `isAdministrator = true` 调试包证明出现与导航正确（CDP 实证 `web/#/metadata`）、选中态像素采样 `(60,53,51)` vs `(18,23,29)`、控制台页返回一次回主界面；临时改动已 `git restore` 还原并重装干净包复验。分支 `feature/r3-console-entry`（未合并 master）。
- **2026-10-01 W6-R6N（本会话，导航 IA + 客户端设置）**：读齐 `PROJECT_PLAN` §1–§5、`UI_PLAN`（D15–D21、踩坑 1–31）、`UI_DESIGN_SYSTEM` §8.6 导航 / §8 组件、`REQUIREMENTS` §4/§5/§6、`git log -15`，核对 `NavigationRoot.kt`（showNavigation / chromeDestinations / 抽屉 / 侧轨）、`presentation/navigation/*`、`presentation/settings/*`、`settings/.../SettingsViewModel`、`AppPreferences` 与 modes:film / modes:music 的首页与曲库加载路径。落地四项用户反馈（D22）：①手机去抽屉（可空 `onOpenDrawer` + 手势关闭，底栏顺序不变）；②控制台 / 元数据管理器隐藏 app 侧轨与抽屉手势；③顶层 IA 重排 + 媒体库二级分组（`navEntryKeys` / `visibleRailKeys` / `LibraryCatalog` 目录缓存 / `PreferenceDynamicSelect`）+「客户端设置」文案；④设置新增三处媒体库选择与侧栏可见性开关，离线模式移到末位且不再重启 Activity（配套 `MainViewModel` 状态刷新、`DrawerViewModel` 的 `Provider<JellyfinRepository>`）。门禁 `:app:phone:assembleDebug ktfmtCheck :app:phone:testLibreDebugUnitTest` 全绿（20 项单测）；Pad 5 平板 + 手机形态（`wm` 覆盖）逐条真机验证见 §5 W6-R6N 验收；新增踩坑 32–34。分支 `feature/r6-nav-ia`。
- **2026-10-01 W6-WEB（本会话，Web 控制台 Lumen 皮肤 + 滚动优化）**：读齐 `PROJECT_PLAN` §1–§5（含控制台皮肤的同步约定）、`UI_PLAN`（D20/D21 + W5 各波 + 31 条踩坑）、`UI_DESIGN_SYSTEM` §2 色彩系统、`docs/design/s1-direction-a/README.md` + 渲染源 CSS 变量、`core/.../theme/LumenColors.kt`（A 稿色值唯一落点）、`WebConsoleScreen.kt` / `ConsoleViewModel.kt`。完成：①`docs/web-console-skin.css` 重绘 v3「A · Lumen」（14 节覆盖 按钮 / 侧栏 / MUI 面 / 表格 / 输入框 / 滚动条 / 徽标 / 标签页 / 登录页 / 详情页 / legacy 收尾 + 新增 §15 滚动闸门），同步 raw 副本（哈希一致）；②`WebConsoleScreen` 加 `overScrollMode=NEVER` / `isNestedScrollingEnabled=false` / `setOffscreenPreRaster(true)` / 渲染进程优先级 `IMPORTANT` / 底色与种子页 `#08090C`；③修复服务器主题残留（`.navMenuOption` 等，踩坑 32）；④真机：K60 像素采样（旧朱砂 0、极光青 / 月白 / 石墨正确）、下拉 overscroll `diff=0`、内建抽屉展开 / 收起 CDP 实测、滚动前后 RAF/gfxinfo 对比无回退；Pad 5 控制台 dashboard 前后对比（数据与边界见 §5 W6-R3 验收）。门禁 `:app:phone:ktfmtCheck` + `:app:phone:testLibreDebugUnitTest` + `:app:phone:assembleDebug` 全绿。分支 `feature/web-console-lumen`。
- **交接提示（负责人 / 下一会话）**：① **服务器自定义 CSS 待用户手动更新**——把 `docs/web-console-skin.css`（v3）粘贴到 Jellyfin「控制台 → 显示 → 自定义 CSS」（App / 仓库不代传）；更新前 App 内已自洽，服务器 Web 端（浏览器直连）仍是旧朱砂。② **设备调度更正**：本会话 15:10–15:22 曾使用 Pad 5（登记时只写了 K60，已在 device-lock 更正并说明）；15:21:40 Pad 5 被其他会话重装后立即停用、后续只用 K60；K60 副作用（`navigation_mode` 0）已还原为 2；Pad 5 可能残留 `wm size 2560x1600` / `density 320` / `user_rotation 0` / `accelerometer_rotation 0`，请 W6-NAV 或负责人收尾时 reset。③ Pad 5 首滑 100–150ms 尖峰的机制未定（疑 WebView 栅格化 / MIUI 调度），建议 W5 全量回归或 R4 性能回归时附带观察。④ `MaterialTheme.spacings` 桥接与 `HomeHeader / HomeCarousel*` 死代码仍未收敛（W4/W5 遗留）。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
