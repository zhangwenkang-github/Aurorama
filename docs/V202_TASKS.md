# 2.0.2 任务卡台账

<!-- V202_STATUS_BEGIN -->
> **当前状态（2026-11-26）**：**W0–C7 DONE（C1–C7 + C5b，余 C8–C9）**——分支 `codex/v2.0.2`（基线 2.0.1 tip `91912da8`）；设计定稿 `docs/V202_DESIGN.md`（用户批准 + 补充裁定×2：黑底清理含播放器控件、图标垫层减重）；C1 U1 热修 `a3ed8f5d`：`UpdateRepositoryImpl.download()` 新增 `persistedTransfer()`——transfer 缺失/不匹配时 `stableUpdateId` 匹配则 `beginDownload` 重建；3 单测；K60 死锁链真机走通（重建→83MB 全量落盘→进校验）；安装器弹窗未验（debug 包名固有限制）。C2 XML 主题层清零 `2440ccf6`：橙→极光青全档（primary/container/on* day+night）、neutral_1000–800 对齐 Compose bg 阶、8 死 token 删除（gold_300/yellow/blue_*/green_* 全仓 XML+Kotlin 双向 grep 零引用）、day/night colorBackground+windowBackground 对齐 Compose 页底（消冷启动闪色）、播放器 XML 色对齐玻璃系（`player_background` 经核为叠视频半透明层，保 `AA` 改 `#AA0A101C`）；`activity_player.xml` 经核有 databinding 引用保留；门禁 assembleLibreDebug + ktfmtCheck + :core 单测全绿；K60：冷启动帧 `#080D17` 93.5%、旧橙命中 0.000%、Switch 开态 `#3DE8C2`、播放器 HUD 20 控件正常 ✅。
> C3 D1 安全区统一 `83569904`：`rememberSafePadding` 下沉 core（app 侧留委托，23 文件零改动）+ `@Composable Modifier.cinefinSafeTop()`（safeDrawing.only(Top)）；`CinefinPageTopBar`/`FilmSearchBar` 结果区/`ColdStartSplash`/`ReaderControlLayer` 顶 sheet 改 safeDrawing 口径；core 补 adaptive 1.1.0 + window 1.5.1 依赖；门禁 assemble+core 530/app 618/book 377 单测+ktfmt 全绿；K60 竖屏顶栏/搜索 ≥104px、阅读器控制层实测下移 104px 脱挖孔、横屏 ROT90/270 避让正常 ✅。
> C4 D4 黑底清理 `06f03d83`：BaseBadge（glass 58%+glassBorder）/ItemTopBar 图标键（scrim 28%+glassBorder）/多选圆点（scrim 28%+白24%描边，选中 aurora1 不动）/播放器缓冲背板+跳过 chip+歌词提示 chip/桌面歌词写死色 → 全 token 化；`DesignTokenConsistencyTest` 新增 D4 守卫（22 测绿）；K60 像素取证：多选圆点=aurora1 `#3DE8C2`、详情返回键玻璃透出海报非黑块、HUD 20 控件在位 ✅。
> C5 D5 批②黄橙清零 `f419bc1f`：21 点逐点裁定——加载圈/进度条→M3 primary 或 auroraGradient、SortByPanel/下载文字强调→navSelectedContent、LumenSurface 光晕→aurora2@40%、`UI_DESIGN_SYSTEM.md` §2.2 勘误（影视琥珀仅身份位）；白名单（歌词预设/Epub 黄/护眼纸色/TV/动态色）未动；Pad 像素实证：选中项/加载圈 `#3DE8C2`、UI 区琥珀 0 ✅；C4+C5 合态门禁负责人复跑全绿。
> C5b D5 规则外交互残项清零 `d5444007`：ItemButtonsBar 6 处/CinefinButton Media 档 13 处+焦点环/CinefinCard 7 处/LibrarySelectorChip 勾选 → 全按 C5 映射归极光（navSelectedContent/aurora1@16%/@45%/onAurora）；保留 `CinefinEmptyState` 占位图标 tint、`CinefinSourceBadge` 来源徽标（负责人裁定：小面积静态徽标非交互强调，留 C9 对账复核）；Pad 像素实证：收藏选中 `#3DE8C2`+aurora1@16% 底+@45% 描边、填充主键整块极光、琥珀 0 ✅。
> C6 D2 音乐头部重排 `ac40302a`：core 新增 `CinefinLibraryTabs`（无容器无描边、选中 auroraInk+20×2dp 极光渐变指示条、40dp 行高、LazyRow 横滑、gutter 4 档+单测）；音乐页两行收敛（顶栏只留搜索+⋮[收藏/最近播放/睡眠定时]；标签行=tabs+库 chip+⋮[来源/徽标/排序/筛选/每页数量]）；播放全部+随机降 36dp 紧凑行（仅歌曲/专辑，极光渐变幻灯去投影）；`MusicPrimaryToolbarLayout` 及单测退役（批量可达性迁入 `MusicToolbarVisibility.kt`）；头部→内容 Space3→Space5；门禁 core 532/music 496 绿；K60：5 tab 全在可横滑、指示条实测 19.8×2dp aurora1→2→3、播放全部 36.19dp、双 ⋮ 枚举全项、起播 PLAYING+迷你条回归 ✅。
> C7 D2 书籍/库头部 + D3 最近阅读入流 `a517ef6b`：`LibraryContentHeader` 删 chip 行（`LibraryTabRow`）+ 独立工具行（`LibraryToolbarRow`/`LibraryIconToggle`），新 `LibraryTabsRow` = 复用 core `CinefinLibraryTabs`（weight 1f、contentPadding 0）+ 行尾弱化计数 + 排序/视图切换 2 图标键 + ⋮ 直达筛选面板；视图切换合成单键（图标=目标视图）；生效筛选 chips 保留一行横滑、为空不占高度；头部→内容 Space5。D3：`headerContent` 签名改 `(@Composable (PaddingValues) -> Unit)?`，5 个内容分支作首 item 插入（网格 `GridItemSpan(maxLineSpan)`、固定 key），`bookshelfShelfHeaderVisible()` 空态零占位；core `CinefinLibraryTabs.label` 改 `@Composable`（库名 stringResource 需要，音乐页零改动）；W6 基线文档两处勘误。门禁 assemble+phone 619/film 228/core 532+ktfmt 全绿；K60：两行头部实测（标签行 40dp、计数/排序/视图/筛选同行）、最近阅读滚动后移出屏幕、tab/排序 7 项/筛选 chips/视图切换回归 ✅。
> **下一卡**：C8（D6 台账）→ C9。
> **用户确认（2026-11-26）**：「关自动旋转横置不转」手动验证通过，观察项核销。
> **补充裁定（2026-11-26 用户，图标垫层减重）**：D4 纯图标控件垫层 = scrim 28%（`PlayerGlassAlpha` 口径）+ `glassBorder` 细描边；含文字徽章维持 `glass` 58%（详见 V202_DESIGN.md D4）。
> **负责人裁定（2026-11-26，C5b 立项）**：D5 批②规则延伸至规则外可见残项——`ItemButtonsBar`/`CinefinButton` Media 档/`CinefinCard`/`CinefinEmptyState`/`LibrarySelectorChip` 的 media.* 交互消费（选中/进度/加载/文字强调）→ 极光或中性，纯域身份位保留（依据 = D5 批②既定裁定规则，非新设计）。
> **LATER 登记**：`beginDownload` 重建分支不回收旧 release 的 `.part`/`.apk`（既有行为，C1 实测陈旧 1.3.0 part 残留），列 C8 台账残留登记表或后续小卡；`header_gradient.xml` 死 drawable（全仓零引用，C2 发现）与 windowBackground 字面量 token 化，列 C8/D6 台账；测试服 TLS `-0x7280` 致 C2 播放链路未通（**2026-11-26 用户排查服务端无异常**，疑客户端侧/mpv TLS 栈或链路中间环节，补验继续受阻），播放器动态态（进度条/HUD 动态、Exo「重试」文字色=批②点位）待测试服恢复补验；**C3 留档×3**：①顶栏 end 侧 14 调用点 0 个传 end 避让（K60 ROT270 实测余量仅 6px，需裁定顶栏自吃水平 inset 或逐页补）②侧轨 `NavigationRoot.kt` 不吃 start inset（横屏与左挖孔重叠，D1「侧挖孔由 rememberSafePadding 覆盖」假设对侧轨不成立）③音乐页横屏完全无避让（`MusicModeScreen` 未用 rememberSafePadding）——三项均需设计裁定，列 C8 台账或 C6/C7 顺带评估；**C4 留档**：桌面歌词窗口底 `0xE60E1116`(90% 半透明)→`colors.surface`（不透明），如需保留半透明待裁定新增 window token；**C5 留档×2**：`LumenCardFrame(emphasized=true)` 全仓零调用=死分支（如需详情页极光描边光晕需单独接线裁定）；进度条/进度环运行时样本缺失（无进行中下载，点位 #4/#5/#14–#19 仅 token 链+编译+单测证据），待有下载任务时补拍；**C5b 留档（规则外新点位×4，列 C8 台账/C9 对账）**：`CinefinMultiSelect.kt:117` 选中圆点勾 media.base+onBase、`CinefinNavigation.kt:396`/`CinefinDrawer.kt:568` 按下层 media.containerPressed、`VideoScreen.kt:382` 睡眠定时激活图标 media.bright、`DetailHero.kt:367` eyebrow media.bright（文字强调 vs 身份待裁定）；另 `CinefinComponentPreviews` 预览板仍读 LocalMediaColors（调试资产，C8 顺手修）；**C6 留档**：库选择 chip 沿用既有 ≥3 库条件，测试服不足 3 库故 K60 未显示（非本次改动）；**C7 留档**：`ic_filter` drawable 已无引用（原工具行专用，交 D6 台账处置）；440dp 窄屏标签行计数挤占 tab 横滑空间（负责人裁定：D2 既定「窄屏横滑兜底」行为，不动，列 C9 对账复核）；书架「最近阅读+本地书籍双空」真机分支因红线造不出（由单测 `bookshelfShelfHeaderVisible` 兜底）。
> **红线**：生产服 `jellyfins.zhangwenkang.com` 永久只读；服务器写只在测试服验证；push/tag/Release 需用户授权。
<!-- V202_STATUS_END -->

## 卡状态约定

`TODO / DOING / REVIEW / DONE / BLOCKED / DEFERRED`；每卡含验收标准与证据要求；实现一律派子代理，设计以 `docs/V202_DESIGN.md` 为准（deepseek 零设计权）。

## W0 开工准备

| 卡 | 状态 | 内容 | 验收 |
|---|---|---|---|
| W0 | DONE | 建分支 `codex/v2.0.2`；设计定稿 `V202_DESIGN.md`（含 2026-11-26 用户补充裁定：播放器控件纳入 D4）；本台账；AGENTS.md / PROJECT_PLAN 焦点切换 | 文档落库、分支在建 ✅ |

## 执行卡（按依赖排序；共享层 C2–C4 → 核心页面 C6–C7 → 台账回归 C8–C9）

| 卡 | 状态 | 内容 | 验收 |
|---|---|---|---|
| C1 | DONE（`a3ed8f5d`） | **U1 更新下载死锁热修**：`persistedTransfer()`——transfer 缺失/不匹配时读 `loadDiscoveredRelease()`，`stableUpdateId` 匹配则 `beginDownload` 重建后续原流程；READY_FOR_VERIFICATION 短路径与 cancel/check 语义未动；新增 3 单测。留档：安装器弹窗真机未验（debug 包名 PACKAGE_MISMATCH 固有限制）；旧 release `.part` 残留=既有行为，登记 LATER | :data 533 测 0 败 + ktfmt + assemble 绿；K60：死锁前置实锤（仅 release.json）→ 重建 → 83MB 全量落盘 → 进校验 ✅ |
| C2 | DONE（`2440ccf6`） | **D5 批① XML 主题层清零**：`colors.xml` 橙→极光、neutral 阶对齐 bg 阶、删死 token、`themes.xml`/night 同步、window 底色对齐、`activity_player.xml` 死资源核查（有 databinding 引用→保留）、`player_background`/`player_hud_background` 对齐玻璃色系 | assemble + ktfmt + :core 单测绿 ✅；K60：冷启动 `#080D17`/旧橙 0.000%、Switch 开态 `#3DE8C2`、HUD 20 控件正常 ✅；留档：测试服 TLS 未通，动态态补验 |
| C3 | DONE（`83569904`） | **D1 安全区统一**：`rememberSafePadding` 下沉 core（`core/presentation/utils/SafeInsets.kt`，app 侧留委托 23 文件零改动）+ `@Composable Modifier.cinefinSafeTop()`；`CinefinPageTopBar` 换 safeDrawing 口径；`FilmSearchBar` 结果区/`ColdStartSplash` 补顶部；`ReaderControlLayer` 顶 sheet 压挖孔实锤后同口径修；core 补 adaptive+window 依赖 | 门禁全绿（assemble+core 530/app 618/book 377+ktfmt）✅；K60：竖屏顶栏/搜索/阅读器控制层 ≥ 状态栏 104px，阅读器实测下移 104px 脱挖孔；横屏 ROT90/270 内容避让正常 ✅ |
| C4 | DONE（`06f03d83`） | **D4 黑底清理**：BaseBadge（glass 58%+glassBorder）/ItemTopBar（scrim 28%+glassBorder）/CinefinSelectIndicator（scrim 28%+白24%描边）/播放器缓冲背板+跳过 chip+歌词提示 chip/桌面歌词写死色 → 全走 glass/glassBorder/scrim token；`DesignTokenConsistencyTest` 新增 D4 守卫 | 门禁绿（core 531+phone 618+music 496，守卫 22 测）✅；K60：多选圆点像素=aurora1、返回键玻璃透出、HUD 20 控件在位 ✅；留档：播放链路测试服 TLS、桌面歌词底改不透明 |
| C5 | DONE（`f419bc1f`） | **D5 批② Compose 黄橙清零**：21 点逐点裁定（加载圈/进度→primary/auroraGradient、选中/文字强调→navSelectedContent）、`LumenSurface` 光晕→aurora2@40%、`UI_DESIGN_SYSTEM.md` §2.2 勘误；白名单未动 | 门禁绿+合态复跑绿 ✅；Pad：选中项/加载圈 `#3DE8C2` 像素实证、UI 区琥珀 0 ✅；留档：进度条运行时样本缺失待补拍、LumenCardFrame emphasized 死分支 |
| C5b | DONE（`d5444007`） | **D5 规则外交互残项清零**（负责人裁定延伸批②规则）：`ItemButtonsBar` 6 处、`CinefinButton` Media 档 13 处+焦点环、`CinefinCard` 7 处、`LibrarySelectorChip` 勾选 → 极光系；`CinefinEmptyState` 占位 tint/`CinefinSourceBadge` 来源徽标保留（小面积静态） | 门禁绿 ✅；Pad：收藏选中 `#3DE8C2`+aurora1@16% 底+@45% 描边、填充主键极光、琥珀 0 ✅；留档：规则外新点位×4 列 C8 |
| C6 | DONE（`ac40302a`） | **D2 音乐头部重排**：core 新建 `CinefinLibraryTabs`（20×2dp 极光指示条/40dp/横滑/gutter 单测）；音乐页两行收敛（顶栏搜索+⋮、标签行 tabs+库 chip+⋮）；播放全部降 36dp 紧凑行仅歌曲/专辑；`MusicPrimaryToolbarLayout` 退役（批量可达性迁 `MusicToolbarVisibility.kt`） | core 532+music 496 单测+ktfmt 绿 ✅；K60：5 tab 横滑/指示条 19.8×2dp aurora1→2→3/双 ⋮ 全项/起播 PLAYING+迷你条 ✅；留档：库 chip ≥3 库条件 |
| C7 | DONE（`a517ef6b`） | **D2 书籍/库头部 + D3 最近阅读入流**：书籍/库页两行收敛（删独立工具行）；最近阅读/本地书籍改 grid header item 随滚动；`LibraryScreen` headerContent 插槽机制改 grid item；视频库页连带统一；W6 基线文档更新 | 门禁 assemble+phone 619/film 228/core 532+ktfmt 全绿 ✅；K60：两行头部、最近阅读随滚动离开、tab/排序/筛选/视图切换回归 ✅；留档：ic_filter 死 drawable 列 D6、窄屏计数挤占列 C9 复核 |
| C8 | DONE（`e2500d81` / `eb174d31` / `ef7ee573`） | **D6 台账**：`docs/design/COLOR_ICON_INVENTORY.md`（token 表 / 图标表 137 项 / 残留登记表）+ 守卫单测补齐（a–c）+ `databinding` 判死勘误；C8a–C8d 逐批归极光（规则外 4 处、灰度 12 处、面板 19 处） | 台账生成且与代码抽查一致；core/app 单测绿 ✅ |
| C9 | DONE（本 commit） | **全量回归 + 变更对账表 + 发布门**：歌词外观/显示预设 chip ×4 归极光（media.* 交互态清零）；四主题切换、7 态抽查、C8 点位复跑、2 张压缩像素图；`assembleRelease` + `verifyMpvJniReleaseContract` + 9 模块单测 + `ktfmtCheck` 全绿；D1–D5 逐点对账见下节 | 对账表全绿；版本 bump 与发布另待用户授权 |

## C9 · D1–D5 逐点对账表（2026-11-27，`codex/v2.0.2`）

数据来源：`docs/V202_DESIGN.md` D1–D5 各点位 + C1–C9 实际 commit + 本轮 K60 复跑证据。行号为改动时行号，以控件名定位为准。

### D1 全局安全区统一

| 点位 | 改前 → 改后 | 验证方式 |
| --- | --- | --- |
| `rememberSafePadding` 归属 | app:phone 私有 → `core/presentation/utils/SafeInsets.kt`（app 侧留委托，23 调用文件零改动） | 编译 + `:core:testLibreDebugUnitTest` 536 绿 |
| `CinefinPageTopBar` 顶部口径 | `statusBarsPadding()`（不含挖孔）→ `WindowInsets.safeDrawing.only(Top)`，14 页统一受益 | K60 竖屏顶栏/搜索 ≥ 状态栏 104px；C9 复跑详情页 eyebrow 在 [497,974] 未压状态栏 |
| 搜索页结果区 / 冷启动品牌页 | 无顶部避让 → `Modifier.cinefinSafeTop()` / safeDrawing | K60 搜索页温度取证（C3）+ C9 首页顶栏 [140,216] |
| 阅读器控制层顶 sheet | `statusBarsPadding()` → safeDrawing（实测压挖孔后修） | K60 实测下移 104px 脱挖孔（C3） |
| 顶栏 end 侧水平 inset | 14 调用点逐页补 → 顶栏自吃 `cinefinSafeEnd()`，删除 7 处调用点 `padding(start = safePadding.start)` | K60 ROT90/ROT270：右侧控件止于 2222px < 2400−138 挖孔内缩 |
| 侧轨 start 侧水平 inset | 侧轨不吃 start inset（横屏与左挖孔重叠）→ `cinefinSafeStart()` | K60 ROT90 内容左缘 439px；ROT270 左缘 231px（均 > 挖孔 138px） |
| 音乐页横屏 | 完全无避让 → `cinefinSafeHorizontal()`（C8a 裁定接受，口径登记台账 #13） | K60 横屏音乐页 dump：标题 [502,117]、搜索 [2110,149] 均在安全区内 |

### D2 音乐 / 书籍头部统一轻量标签

| 点位 | 改前 → 改后 | 验证方式 |
| --- | --- | --- |
| 新共享组件 `CinefinLibraryTabs` | 音乐 M3 分段控件 + 书籍 chip 行两套语言 → 单层文本标签（40dp / 无容器 / 选中 auroraInk + 20×2dp 极光渐变指示条 / 横滑 / gutter 4 档） | 单测 `gutter follows the top bar breakpoints`；K60 指示条实测 19.8×2dp、tab 5 个全在可横滑 |
| 音乐页头部 | 56dp 顶栏 + 44dp 重容器分段 + 独立工具行（三段堆叠）→ 两行（顶栏搜索+⋮ / 标签行 tabs + 库 chip + ⋮） | K60 dump：tab 行 y 284–336、双 ⋮ 枚举全项（C6） |
| 音乐「播放全部 + 随机」 | 38dp 带投影 → 36dp 紧凑行、去投影、仅歌曲/专辑 tab 显示 | K60 实测 36.19dp（C6）；C9 复跑 [720,442][2200,494] |
| 书籍/库页头部 | 顶栏 + chip 行 + 工具行 + 生效 chips + 固定头部（五层）→ 两行 + 生效 chips 一行横滑（为空不占高度） | K60 dump：书籍 (42,310)–(366,362) 一行 3 tab + 计数 + 3 图标键；C9 复跑 `1-8 / 8` |
| 头部 → 内容间距 | Space3 → Space5 | K60 头部/内容区 dump 坐标（C6/C7） |
| `MusicPrimaryToolbarLayout` | 自定义 Layout（使能态批量操作）→ 退役，可达性迁 `MusicToolbarVisibility.kt` | music 496 单测绿（含批量可达性用例） |

### D3 最近阅读入流

| 点位 | 改前 → 改后 | 验证方式 |
| --- | --- | --- |
| 最近阅读 / 本地书籍 | 页面级固定头部（随页面不动）→ `LazyVerticalGrid` header item，随滚动离开 | K60 滚动后移出屏幕（C7）；C9 复跑「最近阅读」y 441 → 滚动后不可见 |
| `LibraryScreen.headerContent` | 固定插槽 → `@Composable (PaddingValues) -> Unit`，5 个内容分支作首 item（`GridItemSpan(maxLineSpan)` + 固定 key） | film 228 / phone 619 单测绿 |
| 空列表形态 | 占位区块 → 零占位 | 单测 `bookshelfShelfHeaderVisible`（双空 false / 单有 true）；真机双空分支因红线造不出 |
| 卡片规格 | 104dp 竖版 2:3 保持 | K60 网格 dump：卡片标题 y 945 / 进度 y 991 节奏不变 |
| W6 验收基线 | 旧五层描述 → 两行 + 入流口径勘误 | `docs/V2_W6_TASKS.md` 两处更新（C7） |

### D4 黑底清理（图上控件唯一垫层 = 主题玻璃）

| 点位 | 改前 → 改后 | 验证方式 |
| --- | --- | --- |
| `BaseBadge` | 黑 62% → `colors.glass` 58% + `glassBorder` | 守卫 `d4 image overlay controls back their content with glass tokens only`；K60 详情返回键玻璃透出海报（C4） |
| `ItemTopBar.OverlayIconAction` | 黑 60% → 纯图标控件减重：scrim 28% + `glassBorder` | 同上守卫 + K60 详情页顶栏取证 |
| `CinefinSelectIndicator` 未选中 | 黑 55% → scrim 28% + 白 24% 描边（选中环 aurora1 不动） | C4 像素实证 + C9 多选「已选 1 项」行描边 `#3DE8C2` |
| 播放器 `PlayerIconButton` | 手写玻璃 28%/44% → `glass` / `glassBorder` token | K60 播放器 HUD 20 控件在位（C2/C4） |
| 播放器缓冲背板 / 跳过 chip / 字幕提示 chip | 黑 45% / 黑 80% / `0x66000000` → 同口径 token | 代码 token 化 + 播放器实机渲染无黑块（C9 深色面板图） |
| 顶/底控制条渐变 | 写死黑 → 功能保留、色值归 token | 代码 + K60 播放器渲染 |
| XML `player_background` / `player_hud_background` | `#AA` + 旧值 → 玻璃系 `#AA0A101C` 等 | 冷启动/播放器实测（C2） |
| 桌面歌词悬浮窗 | `0xE60E1116` / `0xB30E1116` 写死 → `colors.surface` 等 token（不透明度留档 #7） | 代码 + K60 桌面歌词开启渲染（C4） |

### D5 黄橙清零

| 点位 | 改前 → 改后 | 验证方式 |
| --- | --- | --- |
| XML `primary_dark` / `on_primary_dark` / container | `#d2553c` 朱砂橙 → `#3DE8C2` / `#04201A` + 派生 | K60 冷启动帧 `#080D17` 93.5%、旧橙命中 0.000%（C2） |
| XML `neutral_*` 墨阶 | 旧阶 → `#04070D/#080D17/#0D1522/#131D30` 对齐 Compose | 同上像素统计 |
| 死 token | `gold_300` / `yellow` / `blue_*` / `green_*` → 删除（XML+Kotlin 双向 grep 零引用） | grep 证据（C2） |
| `themes.xml` day/night + 窗口底色 | 与 Compose 页底不一致（冷启动闪色）→ 对齐 | K60 冷启动帧取样（C2） |
| `activity_player.xml` 死资源核查 | 疑似死布局 → 核实有 databinding 引用，保留（C8b 勘误） | 台账 B7 + 链接级复核 |
| 加载圈（`HomeTopBar` / 搜索） | `media.base` 琥珀 → M3 `primary`（极光） | K60 像素：加载圈 `#3DE8C2`（C5） |
| 海报/横版卡进度 | `media.base` 琥珀 → `auroraGradient` | 代码 token；运行时仅 token 级（测试服无可续播海报进度，台账 #9 备注） |
| `SortByPanel` 选中 | 琥珀 → `navSelectedContent` | 代码 + K60 排序面板选中态（C6/C7） |
| Download 四组件（`DownloadRows` / `BatchDownloadDialog` / `DownloadStatusBadge` / `DownloaderCard`） | `media.base` 琥珀（进度/加载/选中/文字强调）→ 极光或中性 | C9 真实下载：进度条填充 `#0C9E82`（浅色 aurora1）+ 轨道 `#D3D5D8`，琥珀 0 |
| `LumenSurface` 详情氛围光晕 | 琥珀 → `aurora2` @40% | 代码 + K60 详情页渲染（C5） |
| `UI_DESIGN_SYSTEM.md` §2.2 | 影视=琥珀通吃 → 勘误「仅身份位」 | 文档核对（C5） |
| C5b 规则外残项 | `ItemButtonsBar` 6 处 / `CinefinButton` Media 档 13 处 + 焦点环 / `CinefinCard` 7 处 / `LibrarySelectorChip` 勾选 → 极光系 | Pad 像素：收藏选中 `#3DE8C2` + aurora1@16% 底 + @45% 描边、琥珀 0（C5b） |
| C8a 规则外 4 处 | 多选圆点勾 / 导航与抽屉按下层 / 视频页睡眠图标 / 详情 eyebrow → 极光 | C9 像素：睡眠 `#3DE8C2` 17.2%、eyebrow `#3DE8C2` 22.1% |
| C8c 灰度 12 处 | 选集勾/角标、睡目标签、跳过 chip 图标、解锁键、面板勾、菜单勾、队列「正在播放」×2、重试文字 → 极光 | K60：选集行选中 + 「正在播放」角标（C9 深色面板图 + nodes） |
| C8d 面板焦点/选中 19 处 | 焦点环 / 错误面板按钮 / 工具键 / `PanelChip` / 分类 Tab / 面板 Tab → 极光 | core 守卫 + K60 播放器面板渲染（C9） |
| C9 歌词外观/显示预设 chip 4 行 | 域媒体 `media.container/bright` 选中态 → `CinefinFilterChip`（aurora1@16% + @45% 描边 + `navSelectedContent`） | K60 像素：选中 chip `#0E272C` + `#3DE8C2` 20.1%、未选中 0%；守卫 `d6 歌词预设 chip 不自造选中色` |
| C9 补 阅读器进度条 / 书签点 / 拖拽 thumb | `accent = media.base`（深色 `MediaBook` 书域蓝、纸色 / 护眼 `PaperAccent` `#A8843C`）→ `navSelectedContent(colors)`（深色 aurora1 / 浅色 auroraInk） | 代码 token 链 + book 377 单测 + `assembleLibreDebug` + `ktfmtCheck`；`ReaderProgressBar` 参数不变、无重设计（章节点本就非 accent 驱动，保持 `contentColor@36%`） |
| C9 补 书页叠加层（搜索命中 / 批注框 / 实时框选） | `accent = media.base` 保留（内容标注身份位：深色 = `MediaBook`，纸色 / 护眼 = `PaperAccent` `#A8843C` = D5 白名单「阅读护眼纸色」） | 原地加分类注记注释；token 未见交互槽 |

### 自查结论（拥挤 / 多余图标 / 旧样式残留）

- **拥挤**：D2 后两页头部均为两行，K60 dump 未见控件重叠；下载页「进行中/失败/已完成 + 排序」与「全部/视频/音乐/书籍」两行 chips 在 1080px 宽下无挤压（选中 chip 底 `#D4EBE8` 完整可见）。440dp 窄屏计数挤占 tab 属 D2 既定横滑行为（台账 #12），不再改动。
- **多余图标**：音乐顶栏已由 5 键收敛为搜索 + ⋮；书籍/库页工具行删除后仅余 排序 / 视图 / 筛选 3 键；`ic_filter` / `header_gradient` 死资源已删且守卫 b 防复活。四主题 + 7 态 dump 未发现重复或空占位图标。
- **旧样式残留**：全仓 `media.*` 仅剩 4 处身份位 + 1 处静态字幕轨徽标 + 字幕/歌词颜色预设白名单；`FilterChipDefaults` 在歌词两文件已清零（守卫 e）。仍存 4 个文件使用 M3 原生 `FilterChip`（`SearchBar` / `LyricsSheet` / `MusicEffectsSheet`），走主题桥接色但尺寸/描边与 `CinefinFilterChip` 不完全同款 —— 属既有差异，本卡未扩大范围，登记后续统一。
- **已裁定（C9 补）**：`ReaderControlLayer` 的 `accent` 归极光（`navSelectedContent`，覆盖进度填充 / 书签点 / thumb）；`PageOverlay` 的 `accent` 保留域媒体色（搜索命中 / 批注框 / 实时框选 = 内容标注身份位，与 Epub 批注黄同类）。

## LATER 池（本期不做，裁定见 V202_DESIGN.md「明确不做」）

字阶全站迁移、玻璃实时 blur、新主题（Prism/Nocturne/Monolith）、TV 端、播放器横屏布局。
