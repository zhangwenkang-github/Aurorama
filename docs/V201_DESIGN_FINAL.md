# 2.0.1 · W0-D2 设计定稿

> 状态：定稿（2026-11-24，负责人 Kimi 亲自裁定）｜分支 `codex/v2.0.1`｜卡 W0-D2
> 性质：**设计决策文档**。对 `docs/V201_DESIGN_AUDIT.md` §6 的 9 条不确定项逐条裁定，并给出 T09/T10/W3 的执行依据。
> 执行约束：deepseek 子代理按本文件实现，零设计自由裁量；与代码冲突时以本文件为准。
> 总原则一句话：**域色 = 身份，极光 = 行动**。琥珀/松石/天青只做域身份标识（极小面积），一切交互强调（CTA/进度/选中/焦点/开关/滑杆）归极光。

---

## 1. 九条不确定项裁定（对应审计 §6）

| # | 问题 | 裁定 |
|---|---|---|
| 1 | 琥珀最终角色 | **保留为影视域身份色，退出一切交互强调槽位**。允许出现：域身份标识（域徽标/占位图静态底色/域导航指示等极小面积）。禁止出现：按钮底、进度、选中态、开关/滑杆、M3 primary、链接文字。影视详情页交互元素同用极光（对齐定稿图零琥珀）。音乐松石、阅读天青同口径。 |
| 2 | 渐变覆盖范围 | **分级**：一级 CTA（播放/继续播放/播放全部/传输主键——即「开始消费内容」的主按钮）= `auroraGradient` 实底 + `onAurora` 文字 + `auroraGlow` 投影；次级/工具按钮（下载、重试、设置页普通按钮）= 中性 `surfaceContainer` 底或描边样式，**不用渐变也不用域色实底**；危险动作 = `error` 色。 |
| 3 | 音乐封面取色（AUDIT-09） | **撤销 `CoverMediaColors` 对 MediaColors 的整体覆盖**。封面取色唯一允许用途：全屏播放页**背景环境光晕**（K1 §3.1 明示的例外，必须来自当前封面）。任何控件着色槽位不得使用封面色；强调回极光，音乐域身份保留松石于域标识位。 |
| 4 | 阅读器纸页棕（AUDIT-10） | 按 K1 §2/§3.3 字面执行：阅读 4 套子主题**只覆盖正文区**（正文背景/文字/分页层）；外框（顶栏/底栏/按钮/chip/滑杆）服从 App 主题。删除 `ReaderSettings` 对 mediaColors 的整屏覆盖（S4 §8.14 历史例外作废）。 |
| 5 | 字阶（AUDIT-12） | **2.0.1 不做全站字阶迁移**（风险大、与主诉无关）。W3 音乐模块新布局直接按 K1 字阶（13/15/18/24/32/44，字重 400/500/700）写死；存量页面保持现状；全站迁移列 LATER（2.0.2）。 |
| 6 | 玻璃 blur（AUDIT-11） | **2.0.1 正式决定不做实时 blur**（滚动性能与 API 兼容优先）。玻璃 = `rgba(10,16,28,.58)` + `glassBorder` 描边，写入 token 注释作为正式决定；blur 列 LATER（2.0.2 评估 API 31+ `Modifier.blur`/haze）。 |
| 7 | 主题选择器「跟随系统」（AUDIT-19） | **加入**。选择器五项：极光（默认）/ 浅色 / AMOLED / 动态 / 跟随系统（浅色↔极光随系统）。`AppThemeResolution` 已支持 `system` 解析，只改选择器 UI 与文案。 |
| 8 | R1 文档处置（AUDIT-01） | **不归档**，`docs/UI_DESIGN_SYSTEM.md` 顶部加状态块（文本见 §5）：主题体系与强调色规则以 `V2_DESIGN.md` §3 与本文件为准；R1 保留为组件结构/间距/响应式细节参考。 |
| 9 | 黄铜/Nocturne 残留（AUDIT-17） | 评分徽标改**极光淡色 pill**（aurora1 12% 底 + aurora1 文字，对齐定稿图徽标语言）；黄铜 `#C9A227` 退出 phone。`Color.kt` 标注「仅 TV 保留，phone 禁止引用」；Nocturne 暖金作为 LATER 主题资产只留在设计文档，不进 phone 代码。 |

---

## 2. T09 token 修改清单（按三批执行）

### 批① 角色收口（纯 token 层，改动小、见效最大；覆盖 AUDIT-02/03/17/18/21）

1. **`MediaColors.kt:86`**：`Neutral -> MediaFilm` 改为 `Neutral -> MediaAurora`（新增中性媒体色板）：
   - `base = #3DE8C2`（Aurora1）、`bright = #8FF5DD`、`dim = #2BA98E`、`onBase = onAurora`（深色 `#04201A` / 浅色 `#000000`，随色板）；
   - container/containerPressed/outline 由现有 `derivedOver` 机制自动派生，不加手写值；
   - `otherMediaColorsFor(Neutral)` 保持返回 Music/Book 不变（§2.5 例外 2 不破坏）。
2. **`CinefinColors.kt:299-331` `cinefinColorScheme()`**：M3 角色与域媒体色解耦——
   - `primary = Aurora1`、`onPrimary = onAurora`、`primaryContainer = aurora container 派生`、`inversePrimary = aurora dim`；
   - `secondary/tertiary` 改中性系（`secondary = onSurfaceVariant` 档、`secondaryContainer = surfaceContainerHigh`），不再绑定另外两域媒体色；
   - **dynamic 主题（AUDIT-18）**：dynamic 色板下 M3 primary/secondary/tertiary 走系统动态色，且 `CinefinColors.aurora1/2/3` 映射为系统 dynamic 的 primary/secondary/tertiary，消除「系统色 + 固定极光」混色。
3. **语义 token 补齐（AUDIT-21）**：`CinefinTokens`/`CinefinColors` 新增 `warning = #E0B84F`、`success = #45D483`、`info = #6FA0FF`（Aurora2 兼任），含各自 `onX`；后续告警/成功/信息态一律走 token，禁手写。
4. **评分徽标（AUDIT-17）**：`CommunityRatingBadge.kt:74` 黄铜 → 极光淡色 pill（aurora1 alpha 12% 底 + aurora1 文字）；顺手修正 `Color.kt` 注释为「仅 TV 引用，phone 禁止引用」。

### 批① 验收裁定（2026-11-25，Kimi；批①已落地 `0d3aa49a`+`b17eaa24`）

- **批准定稿未给的派生值**：`onPrimaryContainer=onSurface`；`secondary=tertiary=onSurfaceVariant`、`onSecondary/onTertiary=surface`、`onSecondaryContainer/onTertiaryContainer=onSurface`；`onWarning/onSuccess/onInfo` 取同族深墨（实测 8.46/8.97/7.27:1）。均符合 M3 角色语义，转正式。
- **浅色主题「aurora1 文字 + aurora1 12% 底」≈2.5–2.7:1**（评分徽标与批② chip 选中同源问题；深色主题 8.75:1 达标）：批①/批②仍按定稿原值实现，**浅色档对比度修复整体列批③**，批③开工前由 Kimi 出专项裁定（候选：引入 `auroraInk` 深色文字 token）。
- **`inversePrimary` 2.73:1**：phone 全仓无消费点，不处理。
- **dynamic 主题下 `Neutral` 媒体色保持固定 #3DE8C2**：域色=身份不随系统色，转正式。

### 批② 组件换肤（覆盖 AUDIT-04/05/06/07/08）

5. **`CinefinButton` 新增 `Aurora` tone**：`auroraGradient`（100° au1→au2→au3）实底 + `onAurora` 文字 + `auroraGlow` 投影；`Media` tone 收缩为仅「域身份」场景可用（在 KDoc 注明）。`PlayButton.kt:53`、`ItemButtonsBar` 主 CTA、首页 hero 播放键换 `Aurora` tone。
6. **进度全部改极光（AUDIT-05）**：`ProgressBar.kt:56`、`HomeHero.kt:237`、`CinefinCard.kt:243`（图上进度）、`PlayerControlOverlay.kt:2810-2811`（已播段）→ `auroraGradient` 填充，track = 白 12%（`progressTrack` token）。
7. **选中语言统一（AUDIT-06）**：`CinefinFilterChip`/`CinefinSegmentedControl`/`CinefinCard` 选中 = 极光淡色 container（aurora1 alpha 12–16% 派生）+ aurora1 描边，对齐 `CinefinNavigation.kt:263-285` 既有语言；全 App 单套选中语义。
8. **开关/滑杆（AUDIT-07）**：`CinefinSwitch.kt:46-47`、`CinefinSlider.kt:59` 选中轨/激活轨改读 M3 `primary`（批①后即为极光），删除组件内手写的 `media.base`。
9. **`SectionHeader.kt:40`（AUDIT-08）**：`actionColor` 改 `onSurfaceVariant`（「全部 >」回次级灰，对齐定稿图）。

### 批③ 细节（覆盖 AUDIT-11/13/22）

10. **`PageMetrics.kt:17`（AUDIT-13）**：Compact 页边距 20→16、gutter 16→12（对齐 R1）。
11. **玻璃（AUDIT-11）**：`CinefinTokens.kt`/`CinefinNavigation.kt` 注释更新为正式决定（无 blur，见 §1 裁定 6）。
12. **`PlayerControlOverlay.kt:4711/4738/4744`（AUDIT-22）**：字幕预览面板写死色移入表面系 token（低优先，可最后做）。

### 批③ 专项裁定：浅色档对比度（2026-11-25，Kimi；批①验收裁定的遗留项）

- **问题**：浅色主题「aurora1(#0C9E82) 文字 + aurora1 12–16% 底」实测仅 2.5–2.7:1，不达 AA（4.5:1）；评分徽标与批② chip/segmented/card 选中同源。
- **裁定 1（新增 token）**：`CinefinColors` 新增 `auroraInk`——浅色主题 = `#0A6B5A`（现 `LightNavSelectedContent` 转正为正式 token，单一来源）；深色/AMOLED 主题 = `aurora1`。消费方统一读 `auroraInk`，禁止各组件自判明度。
- **裁定 2（对比度复核）**：#0A6B5A 对「aurora1@16% 叠 #FAFAFB」容器 ≈ 5.2:1（对 12% 底 ≥5.1:1），达 AA；深色档 aurora1 对 12% 底 8.75:1 维持不变。`DesignTokenConsistencyTest` 同步钉死。
- **裁定 3（适用槽位）**：评分徽标文字、chip/segmented/card 选中内容色、导航选中内容色（`navSelectedContent` 改读 token）。容器填充值不动（浅 16% / 深 12%）。
- **裁定 4（执行时点）**：批② 仍按定稿原值落地，不返工；auroraInk 替换列批③第 13 条执行。

验收：每批过后跑 `CinefinThemesTest` / `DesignTokenConsistencyTest`（对比度钉死）+ `CinefinComponentPreviews` 目视 + 真机抽查（首页/详情/设置/下载无黄橙违和色）。

---

## 3. T10 图标规范（去方框底 + 描边 token；覆盖 AUDIT-15/16）

1. **去方框磁贴底**：删除 `LibraryEntryCard.kt:140-146`、`LibraryContentHeader.kt:181-185`、`DownloadRows.kt:703-704`、`OfflineScreens.kt:723`、`LocalLibraryScreens.kt:175/288-290`、`PlayerContentPanel.kt` 中图标的 `surfaceContainerHigh` 圆角方框底；图标扁平直接呈现。
2. **图标着色规则**：默认 `onSurfaceVariant`；强调/选中态 `aurora1`；域身份位（且仅域标识）可用域色。禁止新代码在图标上写死色值。
3. **复杂背景可读性 token（新增）**：
   - `scrimBehindControl` = 黑 32% 圆角底，**仅**用于 hero/封面图上的控件垫层；
   - `mediaContentShadow` = `0 1px 4px rgba(0,0,0,.6)`，用于图上图标与文字的统一投影（等效窄描边，避免逐处手写 stroke）。
4. **写死 fillColor 清理（AUDIT-16）**：11 个含 `#FFFFFF` 的 vector 改为可被 tint 的形式（`?attr/colorControlNormal` 或由调用处 tint）；18 处 `?attr/colorOnBackground` 复核主题切换表现。

---

## 4. W3 音乐模块交互稿（T14/T15/T16/T17/T18 依据）

### 4.1 工具行重构（T14）

- **Tab 序列对齐官方**：建议 / 歌曲 / 专辑 / 艺术家 / 歌单。「建议」为新增 tab（对标官方 Suggestions：最近添加 + 最常播放 + 收藏混排，数据源可复用现有 MusicRecentStore/播放历史）；库选择 chip 保持在 tab 行右侧。
- **工具行（一层）**：
  - 左：收纳溢出「⋮」菜单，内含——来源筛选（全部/服务器/本地）、来源徽标开关、**排序**（名称/专辑/艺术家/添加日期/播放日期/时长 + 升/降序，作用于当前 tab 列表并持久化）、**筛选**（收藏；其余列 LATER）、**每页数量**（50/100/200，默认 100）。
  - 右：主键「播放全部」（一级 CTA，`Aurora` tone 渐变）+ 随机播放图标键。
- **翻页**：歌曲列表改分页（底部页码栏：上一页/页码指示/下一页），每页数量按上；专辑/艺术家/歌单 tab 维持现有滚动，不强行分页。
- 现「来源▾」chip 收进溢出菜单，工具行不再平铺筛选 chip。

### 4.2 歌曲行（T15）

- 去左侧序号（`SongRow` leading 只留 40dp 封面）。
- 右侧操作区：收藏（heart）+ ⋮竖三点；「加歌单」在 ≥600dp 档位提升为第三个独立图标（加歌单/收藏/⋮），compact 档收进 ⋮ 菜单首项。
- ⋮ 菜单对标官方全量：播放 / 下一首播放 / 加入队列 / 加入歌单 / 收藏·取消收藏 / 下载 / 查看专辑 / 查看艺术家 / 刷新元数据（T20 接入）；歌单上下文追加上移/下移（现有逻辑保留）。
- 当前曲目着色：aurora1（不再随封面色）。

### 4.3 多选（T16）

- 删除工具行显式「多选」按钮（`MusicModeScreen.kt:1597-1604` 及 `:1376-1383`），统一**长按进多选**；批量态由批量条接管（现状保留）；清理死代码 `onBatchLongPress`；全局排查其他页面残留多选按钮。

### 4.4 队列语义（T17）

- `playSong` 点单曲 = **追加队尾并起播**（不再整列重建队列）；仅「全部播放」执行整列加载。
- 队列快照失效策略 = 仅剔除确认过时项（不整快照作废）。
- RELEASE_NOTES 必须明示此行为变化。

### 4.5 播放页布局（T18）

- **修文字重叠**：非 compact 的艺术家·专辑 Row（`MusicNowPlayingScreen.kt:1214-1256`）artist/album 两个 Text 各加 `weight(1f, fill = false)` + `Ellipsis`，Row 加 `fillMaxWidth` 约束；`:550/:566` 区域根容器改可滚动（`verticalScroll`），短屏自然降级不裁剪。
- **入口去重（单入口原则）**：全屏播放页每个功能只保留一个入口——底部辅助行 = 左「音效」、中「歌词」、右「队列」；菜单/其他位置重复的队列与音效入口删除（实现时先枚举现状重复点列进证据）。
- **隐藏底栏**：全屏播放页隐藏 App 底导航（玻璃底栏），返回即恢复。
- **底部不遮挡**：根容器应用 `safeDrawing` insets padding，底部按钮永远在手势区之上。
- **封面取色**：仅用于背景环境光晕（见 §1 裁定 3）；迷你条进度线/当前曲目/传输键全部回极光。

---

## 5. R1 状态块文本（裁定 8，T09 批①顺带执行）

在 `docs/UI_DESIGN_SYSTEM.md` 顶部（标题下）加入：

> **状态（2026-11-24）**：本文档为 v1.0 设计系统说明。主题体系与强调色规则已被 `docs/V2_DESIGN.md` §3（K1 Aurora Cinema）与 `docs/V201_DESIGN_FINAL.md` 取代——域色只做域身份，交互强调一律极光；冲突项以新文档为准。本文档保留为组件结构/间距/响应式的细节参考。

---

## 6. 不在本卡范围（登记去向）

- **设置页 5 组重排布局稿**（AUDIT-20/T13）：已出稿，见 §7（2026-11-25）。
- 全站字阶迁移、玻璃 blur、Nocturne 主题：LATER（2.0.2 再裁）。

---

## 7. T13 设置页布局稿（2026-11-25，Kimi；需求 #2；去重方向为本稿裁定，用户可否决）

### 7.1 前置勘察结论

- 设置页树（`SettingsViewModel.kt`）**无同键重复条目**（全量 `nameStringResource` 唯一）；5 组骨架（`SettingsGroupLayout.kt`：账号与服务器/媒体/播放/界面/其他）为 W42 用户确认版，**骨架不动**。
- 「播放设置重复」实指：**播放器内「播放设置」面板**（`PlayerSettingsPanel.kt`，987 行）与全局设置页共享约 20 个偏好键——playerBackend、playerMpvHwdec、mediaSegments×3、chapterMarkers、subtitleMode、rememberTrackSelection、preferredAudio/SubtitleLanguages、playerGestures 全家 ×10、streamingBitrate、decodeMode、autoFallback、backgroundAudio。

### 7.2 布局裁定

1. **设置页 = 唯一全量事实源，一项不删**；5 组骨架与组内归属维持现状（播放组 = 播放器大类 → 桌面歌词 → 恢复播放队列，现状已符合）。
2. **去重方向**：播放器内「播放设置」面板收敛为「播放会话高频项」，与设置页重复的「配置一次长期有效」项移出面板：
   - **面板保留**：解码模式/自动回退、手势总开关及直接子项、长按倍速档、画面变换（旋转/镜像/裁切）、字幕模式、时钟格式、提示条显示时长。
   - **面板移除（仅设置页保留）**：播放器后端、mpv hwdec/vo/ao、片段跳过按钮/自动跳过、章节标记、记住轨道选择、首选音轨/字幕语言、后台音频、串流码率。
   - **勘误（2026-11-25，T13 执行时发现的稿内矛盾）**：「提示条显示时长」与「片段跳过时长」是同一偏好键——按保留清单执行，时长 chips 留面板。「串流码率」在设置页无宿主，照稿删除=功能消失，裁定**暂留码率面板**，后续卡先在设置→播放器补码率行再删面板（登记台账）。
   - 面板顶部加「**全部播放设置 →**」入口，跳转设置页播放组（`settings_category_player`）。
   - 实现时子代理先枚举面板现状项清单，按上表分类出对照表进证据；表外项按「播放中会不会调」原则归类并列出。
3. **设置页渲染**：`SettingsScreen.kt:246` 已按组渲染 `SettingsGroupCard`；T13 只需核对 5 组卡片的分组头样式（小号次级灰）与组间距一致，不重写渲染管线。

### 7.3 验收口径

- 设置页 5 组结构与条目数不减少（前后 uiautomator 对照）。
- 面板不再含 §7.2 移除清单各项，顶部有「全部播放设置」入口且可跳转。
- 面板保留项与设置页同键联动（改一边另一边回显一致——同键天然满足，抽查 2 项即可）。
