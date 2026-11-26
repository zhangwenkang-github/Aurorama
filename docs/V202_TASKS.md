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
| C8 | TODO | **D6 台账**：`docs/design/COLOR_ICON_INVENTORY.md`（token 表/图标表/残留登记表）+ 守卫单测补齐 + 文档指针勘误 | 台账生成且与代码抽查一致；单测绿 |
| C9 | TODO | **全量回归 + 变更对账表 + 发布门**：四主题切换无残留色、7 态抽查、D1–D5 逐点对账表回写本台账；发布门（assembleRelease + verifyMpvJniReleaseContract + 全模块单测） | 对账表全绿；版本 bump 与发布另待用户授权 |

## LATER 池（本期不做，裁定见 V202_DESIGN.md「明确不做」）

字阶全站迁移、玻璃实时 blur、新主题（Prism/Nocturne/Monolith）、TV 端、播放器横屏布局。
