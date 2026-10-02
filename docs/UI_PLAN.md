# Cinefin UI 重塑任务线（UI_PLAN）

> 任务线：UI 重塑（设计系统落地）｜波次：W8-R3（三页顶栏统一 + 媒体库改版 + 侧栏媒体库分组回退）｜分支：`feature/r8-ui-unify`｜最后更新：2026-10-01
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
- [ ] **品牌波（用户 2026-10-03 决定：放在全部功能开发测试完成后、发布前执行）**：应用改名 **「极光幕 / Aurorama」**（仅显示名与资源；`applicationId` 是否调整待确认）+ 自适应矢量图标（**方向 1「极光帘幕」**：极光丝带自左上流下 + 底部幕布地平线，极光青 `#5CE1D2` → 辅光蓝 `#7CC4FF`；深色 / 单色 / 自适应三套）+ 打包字体（MiSans / Literata 走官方渠道；其他字体先调研授权，不允许则用开源替代）与子集化；**约定：新增字符串一律引用 `app_name` 资源，不硬编码应用名**（保证发布前改名一次生效）；执行时补一轮 UI 布局回归（字体度量可能影响排版）。

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

## 7. 日志

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
