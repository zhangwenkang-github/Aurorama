# Cinefin UI 重塑任务线（UI_PLAN）

> 任务线：UI 重塑（设计系统落地）｜波次：W3-R3（R3-PAGES-A）｜分支：`feature/r3-ui-pages-a`｜最后更新：2026-09-30
> 权威设计依据：`docs/UI_DESIGN_SYSTEM.md`（S4 v1.0，Prism 棱镜）。
> 本文件是该任务线的**唯一权威文档**：需求、决策、进度、验收、踩坑与日志都写在这里，不新建零散 `.md`。

> 波次历史：W1-R3（R3-TOKENS，`feature/r3-ui-tokens`，已合并 master）= token 与四类基础组件； 本轮 W3-R3（R3-PAGES-A）= 音乐 / 阅读页面接入 Prism + 路由入口注册。

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
- [x] W3-R3 组件：分段控件 / 筛选 chip / 空状态（见下节）；[ ] W4-R3 组件：徽标 / 对话框 / Toast / 歌词 / 播放器右侧面板
- [x] 音乐 / 阅读页面已按域接入 `ContentDomain` 并切到 Prism 字阶；[ ] 其余页面（影视 / 设置）与 `spacings` / `Motion` 桥接收敛归 W4
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

## 7. 日志

- **2026-09-30 W1-R3（本会话）**：读齐 `PROJECT_PLAN` §1–5、`UI_DESIGN_SYSTEM` v1.0 全文、`s1-decision`、`REQUIREMENTS` §6/§10/§12、`ARCHITECTURE` §2.4、`SESSION_BRIEFS` W1-R3、`PARALLEL_PLAN` §1.3/W1、`ROLE_SKILLS` §5.3（在线校验 5 篇官方文档）；完成 token → Compose 主题映射、Typography 归位 + 桥接、4 类基础组件 + 预览 + 13 项单测；验收命令与真机走查通过。分支 `feature/r3-ui-tokens`。
- **2026-09-30 W3-R3（本会话）**：读齐 `PROJECT_PLAN` §1–5、`UI_PLAN`、`UI_DESIGN_SYSTEM` §2.3–2.6/§4/§8–§10、`READER_PLAN`（D7–D10 + §9 遗留）、`MUSIC_PLAN`（W2 交付 + 踩坑）、`SESSION_BRIEFS` W3-R3、`PARALLEL_PLAN` §1.3/W3、`ROLE_SKILLS` §5.3；完成 core 三件剩余组件 + 音乐 / 阅读页 Prism 接入 + `NavigationRoot` 路由注册（音乐 + 书籍→阅读器）与 `exported=false`；门禁与真机走查（含手机形态、纸色主题色值采样）通过；顺带修正 `kind == "Book"` 大小写 bug（见踩坑 10）。分支 `feature/r3-ui-pages-a`。
- **交接提示（下一会话）**：① 负责人确认书籍入口对 PDF / CBZ 的影响面（§5 未决项）；② 歌词面板由 R2-LYRICS 并入 `MusicModeScreen`（本会话已把浏览 / 底栏 / 队列拆成独立私有 Composable，冲突面小）；③ W4-R3 继续剩余组件与其余页面换新，届时删 `LegacyTypography` 桥接。合并前 rebase 最新 `master`；`docs/PROJECT_PLAN.md` 由负责人维护，本线不改。
