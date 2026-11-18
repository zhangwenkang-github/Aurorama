# 2.0 W1 · 设计系统落地（任务卡）

> 状态：`DONE`（2026-11-18，5/5 全部验收合入）　分支基座：`codex/v2-integration`
> 设计唯一权威：`docs/V2_DESIGN.md` §3（K1 定稿）。**执行者不做任何设计决策**——色值、圆角、尺寸、动效时长一律取本文档与 V2_DESIGN.md 的定稿值；遇到定稿未覆盖的样式问题，停下来报告，不自行发挥。

## 状态块

- 当前波次：W1 **完成**　进度：**5/5 任务**（T1 ✅ T2 ✅ T3 ✅ T4 ✅ T5 ✅）
- 基座 HEAD：`d021de57`（T1 `63c61f91` · T2 `31587506` · 状态栏修复 `c41cec61` · T4 `74298199` · T3 `8d691cb8` · T5 `4134cf25` + `d021de57`）
- 红线：不改播放器逻辑、不改 data 层、不动 `app/tv`；门禁 = `:core` 及相关模块单测 + `ktfmtCheck` + 构建。
- T1/T2 验收证据（2026-11-18，K60 `8e875894`）：core 446 + phone 528 用例 0 失败；四主题设置页切换即时生效且像素精确命中 token（aurora `#080D17` / light `#FAFAFB` / amoled `#000000` / dynamic·system 随系统）；pref 逐项写入、杀进程重启保持；浅色下状态栏图标切深色；升级用户无 `pref_theme` 键 → 默认 Aurora；详情页无残留色。
- T3 验收证据（2026-11-18）：aapt2 dump badging 确认 `mipmap-anydpi-v33/ic_launcher.xml`（v26/v33 双份，monochrome 注册）；fastlane 商店图标 1024×1024（Edge 管线渲染）；K60 桌面图标在 squircle 遮罩下圆弧/光束/极星完整。
- T4 验收证据（2026-11-18）：29 文件零增删改名（core 22 + player/local 5 + music 2），统一 24dp/2dp/圆头 + `?attr/colorControlNormal`；K60 抽屉截图确认导航系视觉一致，设置/下载/音乐页 dump 渲染正常。
- T5 验收证据（2026-11-18，K60）：外观页 4 张预览卡（三色带+极光小条+选中渐变环/对勾）渲染符合规格；逐卡切换像素精确命中；重启保持；legacy 组合（`dark` + `dynamicColors=true`）选中态正确落「极光」，显式选择后动态键清除为 `false`（`d021de57` 修复）。

## W1 交接（2026-11-18 收口）

**已交付**：主题引擎（`CinefinTheme(themeId)` + `CinefinThemes` 注册表 aurora/light/amoled/lumen + dynamic 特例；`CinefinColors` 含品牌槽位 aurora1/2/3、onAurora、glass、glassBorder）；K1 四套 token 全量落地；`ProvideLumen` 机制删除（Prism 退役，lumen 为遗产条目）；logo B adaptive icon 全套 + 通知图标 + fastlane 1024px；首批 29 K1 图标；外观页主题选择器（预览卡 + 即时切换 + legacy 迁移收口）。

**遗留清单（后续波次处理）**：
1. 存量偏好值 `dark` 已在 T5 选择器选中态正确解析（`resolveThemeSelectorSelection`），无残留问题。
2. 旧 Prism 中性常量暂留 `CinefinTokens`（reader 仍引用 `OnSurfaceLight`）→ W3 页面换肤后清理。
3. light `aurora3` 对 `onAurora` 对比度 4.5003 无余量 → 后续调值须同步改 `CinefinThemesTest`。
4. T4 三处已定稿几何观感微调点（齿轮齿宽不均/月牙右下小尖刺/倍速中心点被指针覆盖）→ 下一批图标（W3 或图标第二批）一并微调。
5. `settings` 模块有 5 个同名旧图标（ic_closed_caption/download/music/play/speaker）→ 图标第二批覆盖。
6. 「分享」图标未定稿（代码无入口）→ W3 新增分享动作时再绘。
7. **`app/tv` 在本分支编译失败**（`ui/MainScreen.kt:65` 缺 `MainViewModel.uiState`）：基座既有问题，与 W1 全部改动无关；TV 端冻结，恢复编译列入后续波次评估（W2 或独立修复波）。
8. 图标第二批（剩余约 66 个旧图标）与页面级 K1 换肤 → W3。
9. 形状 token（K1 §3.2 圆角 14/20/16/32-44）未替换现有 `CinefinShapes.M3`（28/22/16/12/8）→ 随 W3 页面重设计一并切换。

**下一波入口**：W2 播放器架构重构 + 1.4.1 遗留根治（依据 `V2_REQUIREMENTS.md` §15、`V2_ARCHITECTURE.md` §3）。

## T1 主题引擎改造（先做，后续任务都依赖它）

**目标**：`CinefinTheme` 支持运行时按 `themeId` 切换主题，浅色主题可达，Lumen 皮肤机制并入主题引擎。

**改点（依据 2026-11-18 模块调查报告 §5，行号以 HEAD 为准自行定位）**：

1. `core/.../theme/CinefinTheme.kt`：签名增加 `themeId: String`（默认 `"aurora"`）；内部按 themeId 从注册表取 palette，替代 `if (dark) CinefinColorsDark else CinefinColorsLight`。
2. `CinefinColors.kt`：`CinefinColorsDark/Light` 两个顶层 val 收编为 `object CinefinThemes { val map: Map<String, Pair<CinefinColors, CinefinColors>> }`（themeId → dark/light 变体）；`cinefinColorScheme()` 改为接收 palette 参数。
3. `MediaColors.kt`：三域媒体色改为按 palette 派生的函数（保持现有三域色值不变，仅改组织方式）。
4. `LumenColors.kt`：Lumen 作为 themeId `"lumen"` 的一组 palette 注册进注册表；删除 `ProvideLumen` 包裹机制（8 文件 52 处）改为统一走 `CinefinTheme`。
5. `Color.kt`（旧 71 处字面量色板）：确认无引用后删除；有引用则迁移到 tokens 后删除。
6. `MainActivity.kt:76`：`darkTheme` 从主题状态取值（现状恒 true 是 bug）；`MainViewModel` 的 `MainState` 增加 `themeId`、`isDarkTheme`。
7. `SettingsViewModel.kt` 既有 `pref_theme`：值域改为 `aurora / light / amoled / dynamic / system`；设置页主题选择器 UI（T5 做页面，本任务只打通状态流）。

**验收**：单测新增 `CinefinThemesTest`（注册表完整、每主题含明暗变体、onAurora 对比度 ≥ 4.5 的纯函数校验）；构建绿；K60 真机：设置切主题即时生效、重启保持、浅色可读。

## T2 四套主题 token 集

**Aurora（默认，深色）**：照抄 `V2_DESIGN.md` §3.1 表。
**Light**：bg0 `#FFFFFF` / bg1 `#FAFAFB` / bg2 `#F0F2F6` / bg3 `#E6E9F0`；text `#10151F` / `#4A5568` / `#8A94A6`；极光强调降明度版 au1 `#0C9E82` / au2 `#3E6FE4` / au3 `#8B53E8`；玻璃 `rgba(255,255,255,.72)` + blur，玻璃描边 `rgba(16,21,31,.08)`。
**AMOLED**：bg0/bg1 `#000000` / bg2 `#0A0F18` / bg3 `#121A2A`；其余同 Aurora 深色。
**Dynamic**：沿用 M3 dynamic color 映射到同一 `CinefinColors` 结构（现状逻辑保留，接入注册表）。

**验收**：T1 的单测覆盖四套；真机四主题切换无残留色（抽查首页/详情/设置/播放器壳）。

## T3 Logo 资源（B 定稿）

1. 将 `_src/direction-k1.html` `icon-b` 段的 SVG 提取为独立矢量：前景层（光圈+光束+极星）→ `ic_launcher_foreground.xml`（108×108dp，安全区内 66%）；背景层 `#060B15`→`#101C33` 径向 → `ic_launcher_background.xml`；monochrome 版本（单 alpha 轮廓）→ v33 `ic_launcher_monochrome`。
2. 修正目录：`core/src/main/res/mipmap-anydpi/` → `mipmap-anydpi-v26/`。
3. 通知小图标：单色轮廓版 `drawable/ic_stat_aurorama.xml`（无 fillColor 写死）。
4. 同步替换 fastlane metadata 图标（`fastlane/metadata/**/icon.png`，用 Edge 管线 1024px 渲染导出）。

**验收**：`aapt2 dump badging` 图标路径正确；K60 安装 debug 包后桌面图标在深色/浅色壁纸、圆形/方形遮罩下均正常（2 张内截图验收）。

## T4 首批界面图标（30 个）

规范（不可偏离）：24dp 视框、2dp 笔宽、圆头端点与拐角、无写死 fillColor（用 `android:fillColor="?attr/colorControlNormal"` 或 tint 由调用方注入）；造型样张见 K1 稿导航栏与播放器图标（`_src/direction-k1.html` 内联 SVG 为准绳）。
清单：首页/影视/音乐/书籍/下载/设置（导航 6）＋ 播放/暂停/上一首/下一首/倍速/字幕/音轨/画质/选集/全屏（播放 10）＋ 搜索/收藏/更多/返回/分享/删除/暂停下载/继续下载/重试/筛选（通用 10）＋ 喜欢/队列/歌词/睡眠定时（音乐 4）。
做法：逐个对照 `core/src/main/res/drawable/ic_*.xml` 替换，引用点 313 处不得改名（只换矢量内容）。

**验收**：构建绿；抽查 5 个页面图标视觉一致（dump + 1 张截图）。

## T5 主题选择器设置页

设置 → 外观：主题列表（极光/浅色/AMOLED/跟随系统动态色）带即时预览缩略块；阅读器子主题入口保持原位。
**验收**：真机切换即时生效、持久化、升级用户保留显式选择（无显式选择进 Aurora）。

## 交接与顺序

T1 → T2 可同代理串行；T3、T4 互相独立可与 T1 并行（不同文件域）；T5 最后。每任务一个子代理、一个 commit，完成后主负责人验收再合入。
