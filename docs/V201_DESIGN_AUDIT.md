# 2.0.1 · W0-D1 设计偏差清单（审计）

> 状态：审计完成（2026-11-24）｜分支 `codex/v2.0.1` @ `17848eff`｜卡 W0-D1
> 性质：**纯审计**（事实 + 偏差定位，不做设计决策、不改代码）。定稿结论由负责人于 W0-D2 作出。
> 方法：静态读权威链文档 + 代码 token/组件/页面引用链；目视 `docs/design/k1-aurora/` 定稿图 2 张（phone-home、phone-detail）。
> 局限：未做真机取色/截图核对，未覆盖 TV 模块（`app/tv` 仍用废弃 `Color.kt`，与 2.0.1 无关）；页面抽查为 5 个主界面，非穷尽。

---

## 1. 结论摘要

用户反馈的「界面有很多黄色、橙色」**不是某处漏改，也不是有人私造颜色**，而是三条结构性原因叠加：

1. **`Neutral` 域被映射成影视琥珀**（`MediaColors.kt:86`），而主界面 `CinefinTheme` 根本不传 domain（`MainActivity.kt:132`，走默认 `Neutral`）。于是首页、设置、下载、搜索、书架、收藏这些**没有内容域的页面全部落在琥珀域**。
2. **M3 `primary/secondary/tertiary` 被直接赋成当前域媒体色**（`CinefinColors.kt:319-331`）。所有 M3 默认组件——填充按钮、开关、滑杆、复选框、chip、进度指示器——在影视/中性页面自动变琥珀。
3. **K1 Aurora 定稿的「极光渐变承担 CTA / 进度 / 选中 / 焦点」没有落地**。定稿图（`k1-aurora/phone-home.png`、`phone-detail.png`）里影视详情页**没有任何琥珀**，主按钮是青→蓝→紫渐变；而代码里组件的「品牌渐变」色调根本不存在（`CinefinButtonTone` 只有 `Media` / `Inverse`），全 App 极光渐变只有 3 处。

补充两条独立来源：影视**评分徽标**直接引用废弃 `Color.kt` 的黄铜 `#C9A227`（`CommunityRatingBadge.kt:74`）；音乐页与阅读器分别用**封面取色**、**纸页棕 `#A8843C`** 覆盖媒体色，封面偏暖或阅读主题为纸色时同样呈现橙黄。

判定口径：琥珀 `#E8A15C` 本身是 R1 授权过的**影视域色**，不是错色——错的是它的**角色与覆盖面**（被当成全局强调色、界面主色、选中主色）。因此修复方向不是「换掉琥珀」，而是「收回琥珀的适用范围」。

偏差总计 **22 条**：P0 5 条、P1 8 条、P2 8 条、P3 1 条（严重度定义见 §5）。

**Top 5（按用户可见度）**

| # | 编号 | 一句话 |
|---|---|---|
| 1 | AUDIT-02 | 全局 `Neutral → MediaFilm`：无域页面（首页/设置/下载…）整页落琥珀，这是「满屏黄橙」的总开关。 |
| 2 | AUDIT-03 | M3 primary/secondary/tertiary = 域媒体色，导致所有 M3 默认控件在影视页变琥珀，与 K1「极光=唯一强调」正面冲突。 |
| 3 | AUDIT-04 | 主 CTA 无「极光渐变」色调：播放/继续播放是琥珀实底，而定稿图是渐变态。 |
| 4 | AUDIT-05 | 进度条（首页 hero、海报卡、播放器已播段）用琥珀，定稿要求 `auroraGradient`。 |
| 5 | AUDIT-07 | 开关/滑杆选中轨道用琥珀，设置页与下载页因此整页发黄。 |

---

## 2. 权威设计链现状

### 2.1 时间线与各文档的角色

| 时间 | 产物 | 角色 | 与当前实现的关系 |
|---|---|---|---|
| 2026-09-29 | `docs/design/s1-decision.md` | S1 方向决策：B Prism 骨架 + A Lumen 沉浸 + C Nocturne 皮肤；**媒体色融入按钮**（不独立色块） | 已被后续方向取代，但其「媒体色=按钮底色」规则仍是组件层的事实来源 |
| 2026-09-29 | `docs/design/s4-README.md` + `s4-revision/` | v1.0 设计系统交付说明（7 token/域、按钮四变形、三色纪律） | 组件层（`CinefinButton` / `CinefinCard` / chip）**仍严格按此实现** |
| 2026-11-02 | ADR-0007 | 主题=完整视觉身份；明暗/域语义/动态色/阅读偏好正交 | 部分被 V2_DESIGN 取代，正交原则仍在用 |
| 2026-11-02 | `docs/design/v2-theme-frames/` | 五主题框架稿（prism / lumen / nocturne / monolith / dynamic），证「五主题共享骨架」 | **框架稿资产**，非最终主题集 |
| 2026-11-04 | `docs/UI_DESIGN_SYSTEM.md`（R1） | 自称「V2 权威基线」；五主题（Prism/Lumen/Nocturne/Monolith/动态）+ 域色/语义色/响应式/状态合同；声明 `CinefinTokens.kt` 仍是唯一代码 token 源 | 与 K1 **部分冲突**，且未回写；见 §2.2 |
| 2026-11-18 | `docs/V2_DESIGN.md` + `docs/design/k1-aurora/` | **最新定稿**：Aurora 为默认主题；四套可选主题；K1 Aurora Cinema token（bg0–bg3、极光三段、渐变、玻璃）；Prism/Nocturne/Monolith 降为 LATER | 主题集**已按此落地**；K1 的视觉规则（渐变/选中/进度）**未落地** |
| 2026-11-18 起 | W1 实现（`63c61f91`、`31587506`、`d5749640`） | 主题引擎 + 四套色板 + K1 校准回写 | 见 §3 |

**哪份更新**：`V2_DESIGN.md`（2026-11-18）晚于 R1（2026-11-04），且 R1 自己在 §V2-R1-01 预留了「与 R1 冲突项由后续覆盖」的位置。故**主题体系以 V2_DESIGN 为准**：Aurora(默认) / Light / AMOLED / 动态色 四套，Prism/Nocturne/Monolith 为 LATER。

**关键裂缝**：`UI_DESIGN_SYSTEM.md` 至今仍写「V2 权威基线 / 唯一代码 token 源」，没有任何一行指向 V2_DESIGN/K1。实现者按它写代码、按 S4 写组件，完全合法；但它描述的视觉语言（媒体色当按钮底色、Neutral 回退影视色）已被 K1 取代。**这是本次偏差的文档层根因，与代码层根因同等重要。**

### 2.2 三处文档自相矛盾（需 D2 一次性裁定）

| 项 | R1 / S4 说法 | V2_DESIGN / K1 说法 | 实现取了谁 |
|---|---|---|---|
| 主 CTA 配色 | 域媒体色 Filled（影视=琥珀实底） | 极光渐变 `au1→au2→au3` | **R1/S4** |
| 选中/焦点态 | 域媒体色 container + outline | 极光淡色 pill + 极光焦点环 | 混用：底栏=K1，chip/card/播放器=R1 |
| 中性页面强调色 | `Neutral → MediaFilm`（v1.0 行为） | 极光（R1 亦明确「Neutral→MediaFilm 静默回退由 R1 取代」） | **v1.0** |
| 主题数量 | 5（Prism/Lumen/Nocturne/Monolith/动态） | 4（Aurora/Light/AMOLED/动态） | **V2_DESIGN** |
| 字阶/字重 | 57/45/36/40/32/24/22/17/15/16/13，含 600 | 13/15/18/24/32/44，仅 400/500/700 | **R1** |
| 圆角 | 按主题 8/12/16dp 基准 | 海报 14 / 卡片 20 / 按钮 16 / 玻璃 32 | **K1** |
| 玻璃 | — | `rgba(10,16,28,.58)` + blur 28–30 | 半透明色，**无 blur** |

---

## 3. 代码 token 现状（四层）

```
CinefinTokens（原始色值，唯一 Color(0x…) 源）
  └─ CinefinColors（语义槽 + M3 ColorScheme 映射，LocalCinefinColors）
       └─ MediaColors（域色：Film/Music/Book/中性，LocalMediaColors）
            └─ 组件（CinefinButton/Card/Chip/Switch/Slider…）与页面
```

| 文件 | 现状要点 |
|---|---|
| `core/.../theme/CinefinTokens.kt` | K1 四套表面 + 极光三段 + 玻璃 alpha 已按定稿写入（`Aurora1Dark #3DE8C2` 等）；影视/音乐/阅读三域色仍是 R1 值（Film `#E8A15C`） |
| `core/.../theme/CinefinColors.kt` | 注册表 5 个 id：`aurora`(默认)/`light`/`amoled`/`lumen`(遗产，不可选)/`dynamic`；**`cinefinColorScheme()` 把 M3 primary/secondary/tertiary 设为域媒体色**（`:299-331`） |
| `core/.../theme/MediaColors.kt` | 三域 7 槽；**`Neutral -> MediaFilm`**（`:86`） |
| `core/.../theme/CinefinTheme.kt` | `domain: ContentDomain = Neutral` 为默认值（`:48`）；`LocalMediaColors` / `LocalCinefinColors` / `LocalCinefinTypography` 注入 |
| `core/.../theme/CoverMediaColors.kt` | 封面取色 → 整体替换 `MediaColors`（音乐模块调用） |
| `core/.../theme/CinefinShape.kt` | 与 K1 §3.2 一致（海报 14 / 卡片 20 / 按钮 16 / 玻璃 32 / Xl 28） |
| `core/.../theme/CinefinType.kt` | 字族 MiSans + Literata（符合）；字阶与字重仍是 v1.0 |
| `core/.../theme/CinefinSpacing.kt` | 4/8 基数，符合 |
| `core/.../theme/Color.kt` | 废弃；phone 端**仍有 1 处引用**（评分黄铜，见 AUDIT-17），非「零引用」 |

**「黄橙」的四个可执行来源**（按影响面排序）

1. `MediaColors.kt:86` `Neutral -> MediaFilm` + `MainActivity.kt:132` 不传 domain。影响：首页（hero 播放、图上进度、海报角标、区块标题「全部 >」）、设置（全部开关）、下载（滑杆/进度）、搜索、书架、收藏、媒体库总览。
2. `CinefinColors.kt:319-331` M3 primary/secondary/tertiary = 域媒体色。影响：所有走 `MaterialTheme.colorScheme.primary` 的默认控件；影视页 = 琥珀。
3. 组件层的媒体色绑定：`CinefinButton`(填充/描边/文字三变形)、`CinefinCard`(选中描边/按压容器/图上进度)、`CinefinFilterChip`/`CinefinSegmentedControl`(选中容器+描边+文字)、`CinefinSwitch`(选中轨道)、`CinefinSlider`(激活轨)、`CinefinNavigation`（此处已改极光）。
4. 域外覆盖：`CoverMediaColors`（音乐）、`ReaderSettings.mediaColors()`（阅读，纸页棕）、`CommunityRatingBadge`（黄铜）。

统计：`LocalMediaColors` 在 **44 个文件、84 处**被引用（不含 theme 目录），说明「域色驱动」是组件层的系统性约定，不是零星用法——修复必须从 token 角色下手，不宜逐页替换。

---

## 4. 主要界面抽样核对

| 界面 | 域（实际） | 强调色来源（文件:行） | 用户看到的颜色 |
|---|---|---|---|
| 首页 | `Neutral`（`MainActivity.kt:132` 默认） | `HomeHero.kt:125/134/237`（角标点/标题/图上进度）、`ProgressBar.kt:56`、`PosterItemCard.kt:154`、`SectionHeader.kt:40` | 琥珀（播放卡、进度线、「全部 >」） |
| 影视库/详情 | `Neutral`（同一入口，未切域） | `CinefinFilterChip.kt:62-78`、`CinefinSegmentedControl.kt:99-114`、`PlayButton.kt:53`(`tone=Media`)、`ItemButtonsBar.kt:391-395`、`CommunityRatingBadge.kt:74` | 琥珀（选中 chip、播放按钮、收藏/下载选中态、评分黄铜） |
| 音乐页 | `Music`（`MusicModeScreen.kt:516-523`） | `LocalMediaColors`（松石）+ `MusicModeScreen.kt:731-739`、`MusicNowPlayingScreen.kt:191` 封面取色覆盖 | 常态青绿；封面偏暖→橙黄（迷你条进度线、当前曲目、传输键） |
| 设置页 | `Neutral` | `CinefinSwitch.kt:46-47`（开关轨道）、`SettingsThemeSelectorCard.kt:142`（唯一的极光渐变，符合） | 开关琥珀；主题卡片是极光（对照明显，割裂感来源） |
| 视频播放页 | `Movie`（`PlayerActivity.kt:375-398`） | `PlayerControlOverlay.kt:2810-2811`（已播段 base→bright 渐变）、`:3205/3295/3559`（选中/主按钮） | 琥珀（进度、选中控件、主按钮） |

对照定稿图（`k1-aurora/phone-home.png`、`phone-detail.png`）：主 CTA 为极光渐变、进度为极光、徽标为极光淡色、详情页无琥珀。

---

## 5. 偏差清单

严重度：**P0** 直接构成用户「主题不符」的感知；**P1** 明显可见或结构性；**P2** 细节或文档层；**P3** 待裁定/影响有限。
归属卡：`T09`=主题还原（去黄橙+还原定稿）、`T10`=图标规范、`T13`=设置页重布局、`T03`=logo、`W3`=音乐模块、`D2`=需负责人先定稿。

| 编号 | 位置 | 定稿要求 | 实现现状 | 严重度 | 归属 |
|---|---|---|---|---|---|
| AUDIT-01 | `docs/UI_DESIGN_SYSTEM.md:1-9`、`:25` | V2_DESIGN 定稿：Aurora(默认)/Light/AMOLED/动态 四套，Prism/Nocturne/Monolith 为 LATER | 代码与 V2_DESIGN 一致；但 R1 文档仍自称「V2 权威基线」「唯一 token 源」，未指向 K1，读者会按 R1 五主题与媒体色规则实施 | P1（文档） | D2 回写 + T09 |
| AUDIT-02 | `MediaColors.kt:86`、`MainActivity.kt:132` | 中性页面强调色应为极光；R1 亦写明「`Neutral → MediaFilm` 静默回退由 R1 取代」 | `Neutral -> MediaFilm`，且主界面不传 domain，所有无域页面走琥珀 | **P0** | T09 |
| AUDIT-03 | `CinefinColors.kt:319-331` | K1 §3.1：渐变/强调只属极光；域色只做域身份，不承担全局强调 | M3 `primary/secondary/tertiary` = 域媒体色 → 所有 M3 默认控件在影视/中性页变琥珀 | **P0** | T09 |
| AUDIT-04 | `CinefinButton.kt:64-67`、`PlayButton.kt:53`、`ItemButtonsBar.kt` | 定稿图主 CTA = `au1→au2→au3` 渐变 + `onAurora` 文字 | 只有 `Media`/`Inverse` 两色调；播放/继续播放为琥珀实底。全 App 极光渐变仅 3 处（`SyncPlayEntry.kt:292`、`SettingsThemeSelectorCard.kt:142`、`CinefinNavigation.kt:304`） | **P0** | T09 |
| AUDIT-05 | `ProgressBar.kt:56`、`HomeHero.kt:237`、`CinefinCard.kt:243`、`PlayerControlOverlay.kt:2810-2811` | K1：进度 = 极光渐变；`progressTrack` 为白 12% | 进度填充 = `media.base`（影视/中性=琥珀）；播放器已播段是琥珀→亮琥珀渐变 | **P0** | T09 |
| AUDIT-06 | `CinefinFilterChip.kt:62-78`、`CinefinSegmentedControl.kt:99-114`、`CinefinCard.kt:64-67` | K1 选中 = 极光淡色 pill + 极光描边（底栏已按此实现） | 选中 = 琥珀 container + 琥珀 outline；与底栏极光选中语言并存，同 App 两套选中语义 | P1 | T09 |
| AUDIT-07 | `CinefinSwitch.kt:46-47`、`CinefinSlider.kt:59` | 同上（选中/激活 = 极光） | 开关选中轨道、滑杆激活轨 = `media.base` 琥珀；设置页因此整页发黄 | **P0** | T09 |
| AUDIT-08 | `SectionHeader.kt:40` | 定稿图「全部 >」为次级灰（`onSurfaceVariant`） | `actionColor = media.bright`（琥珀），首页/库页每个区块标题都带一条琥珀链接 | P1 | T09 |
| AUDIT-09 | `CoverMediaColors.kt:46-81`、`MusicModeScreen.kt:731-739`、`MusicNowPlayingScreen.kt:191` | K1 §3.1：强调色只允许极光三段；封面取色属 W5-T2/MUSIC-17 自行引入，定稿文档无授权 | 封面 vibrant/muted 覆盖整个 `MediaColors`：迷你条进度线、当前曲目、传输键随封面变橙黄 | P1 | D2 裁定 + T09 / W3 |
| AUDIT-10 | `ReaderSettings.kt:239-255`、`ReaderScreen.kt:882-890`、`ReaderSettingsPanel.kt:67` | V2_DESIGN §2：阅读主题只覆盖正文；外框/顶栏/按钮服从 App 主题 | 纸色/护眼主题把媒体色整体换成纸页棕 `#A8843C`，顶栏按钮、分段、chip、滑杆全部变棕（S4 §8.14 的历史例外） | P1 | D2 裁定 + T09 |
| AUDIT-11 | `CinefinNavigation.kt:313-334`、`CinefinTokens.kt:145` | K1 §3.1：`glass` + blur 28–30 | 注释自述「实底、不做实时 blur」；全仓库无 `Modifier.blur`/haze，仅半透明色 + 描边 | P2 | D2（性能/兼容权衡）+ T09 |
| AUDIT-12 | `CinefinType.kt:43-260` | V2_DESIGN §3.2：字阶 13/15/18/24/32/44，字重 400/500/700 | 沿用 v1.0 字阶（57/45/36/40/32/24/22/17/15/16/13），大量 600 SemiBold；K1 字阶未落地 | P2 | D2 + T09 |
| AUDIT-13 | `PageMetrics.kt:17` | R1 Compact：页边距 16 / gutter 12dp（明确取代旧 20dp） | 页边距 20dp、gutter 16dp（平板档位与 R1 一致） | P2 | T09 |
| AUDIT-14 | `core/src/main/res/drawable/ic_logo.xml:3` | V2_DESIGN §3.4：B「极幕之眼」 | 启动图标已是 B（`ic_launcher_foreground.xml:2`），App 内 logo 仍是 A「极光帘幕」 | P1 | T03（已有卡） |
| AUDIT-15 | `LibraryEntryCard.kt:140-146`、`LibraryContentHeader.kt:181-185`、`DownloadRows.kt:703-704`、`OfflineScreens.kt:723`、`LocalLibraryScreens.kt:175/288-290`、`PlayerContentPanel.kt` | K1：扁平图标 + 极光着色，无方框磁贴底 | 图标坐在 `surfaceContainerHigh` 圆角方框里（库页 44dp 磁贴最典型），形成「应用图标墙」观感 | P1 | T10 |
| AUDIT-16 | `core/src/main/res/drawable/*.xml`（98 个 vector） | V2_DESIGN §4：消灭图标内写死 fillColor | 186 处透明正常；**11 个文件含写死白**（`#FFFFFF`/`#FFFFFFFF`），18 处用 `?attr/colorOnBackground`；主题切换时有图标不随色 | P2 | T10 |
| AUDIT-17 | `CommunityRatingBadge.kt:74` | K1 无黄铜；`Color.kt` 自述「phone 零引用、仅 TV 保留」 | phone 直接引用 `ColorDark.tertiaryDark`（`#C9A227` 黄铜）作评分色，绕过主题系统；注释还写着「该 token 目前只被 TV 引用」（与事实不符） | P1 | T09 |
| AUDIT-18 | `CinefinColors.kt:228`、`CinefinTheme.kt:60-65` | R1：动态色只替换骨架角色，不得保留固定品牌语义色形成混色；V2_DESIGN：动态色独立不叠加品牌 | `dynamic` 色板 = aurora，M3 走系统色但 nav 选中/极光强调仍用 `aurora1/2/3` → 混色 | P2 | T09 |
| AUDIT-19 | `CinefinColors.kt:265-270` | R1：品牌主题支持 浅色/深色/**跟随系统** | 选择器只有 极光/浅色/AMOLED/动态 四项；`system` 仅在旧偏好中可进入（`AppThemeResolution.kt` 已支持解析） | P2 | D2 / T09 |
| AUDIT-20 | `SettingsScreen.kt:247`（`state.preferenceGroups`） | T13：去重、重排 5 组 | 布局稿尚未产出；当前为单卡渲染既有分组，已知存在播放设置重复 | P2 | T13（依赖 D2 稿） |
| AUDIT-21 | `CinefinColors.kt:22-57` | R1 §V2-R1 token：error / warning / success / info 四语义角色 | 只有 error/onError/errorContainer；其余状态无 token，靠域色或手写 | P2 | T09 |
| AUDIT-22 | `PlayerControlOverlay.kt:4711/4738/4744` | `CinefinTokens` 注释：业务代码禁用 `Color(0x…)` | 字幕预览面板写死 `#2B2F36/#15171B` 渐变与 `#E6E6E6` 文字（模拟画面，可辩），另有 `#66000000` 底 | P3 | T09（低优先） |

> 已核对为「与定稿一致、无需改动」的项：形状 token（海报 14/卡片 20/按钮 16/玻璃 32）、4/8 间距刻度、MiSans + Literata 字族、底栏/侧栏极光选中语言（`CinefinNavigation.kt:263-285`）、浅色主题 `onAurora=#000` 校准、对比度测试（`CinefinThemesTest` / `DesignTokenConsistencyTest`）。

---

## 6. 不确定项（留给 W0-D2 定稿）

1. **琥珀的最终角色**：是「完全退出界面（只保留海报角标/极域标识）」，还是「保留为影视域标识色，但不再驱动按钮/进度/选中」？前者更彻底、后者改动面小。建议后者，但需负责人拍板并写入 token 契约。
2. **K1 渐变是否覆盖全部 CTA**：定稿图只给了首页/详情。音乐传输键、下载/重试按钮、设置页主按钮是否同为渐变态？还是仅「一级 CTA」用渐变、次级用描边？
3. **音乐封面取色（AUDIT-09）去留**：保留（辨识度/沉浸感）但仅限封面区，还是整体回退到极光？若保留，需明确「允许出现在哪些槽位」。
4. **阅读器纸页棕（AUDIT-10）**：外框是否强制服从 App 主题（K1 字面），还是保留 S4 §8.14 的整屏纸色皮肤？
5. **字阶（AUDIT-12）**：采用 K1 的 13/15/18/24/32/44 需要全站改字号，风险大；是否改为「K1 字阶只用于新组件，存量页面保持 v1.0」的灰度方案？
6. **玻璃 blur（AUDIT-11）**：是否引入实时模糊（API 31+ 可用，需评估滚动性能）？还是接受「无 blur」为定稿偏差的正式决定？
7. **主题选择器是否加入「跟随系统」**（AUDIT-19）：R1 要求、V2_DESIGN 未提。加入会改变主题卡片数量与文案。
8. **R1 文档的处置**：是在 `UI_DESIGN_SYSTEM.md` 顶部加「已被 V2_DESIGN 部分取代，主题与强调色以 K1 为准」的状态块，还是把 R1 整体归档、另写 2.0 设计系统？关系到后续所有实现者的读序。
9. **黄铜/Nocturne 残留资产**：`Color.kt` 的 `#C9A227` 与 Nocturne 暖金是否仍属「未来可选主题」？若是，AUDIT-17 的评分色应改为主题槽位而不是删除。

---

## 7. 分派汇总（供 W1 排卡）

| 卡 | 覆盖偏差 | 说明 |
|---|---|---|
| **D2**（先做） | 01、09、10、12、19 | 出「token 修改清单 + 交互稿」，把 §6 的 9 个问题定死；否则 T09 无处落地 |
| **T09** | 02、03、04、05、06、07、08、11、13、17、18、21、22 | 建议拆三批：① 角色收口（02/03/17/18/21，纯 token 层，改动小、见效最大）；② 组件换肤（04/05/06/07/08）；③ 细节（11/13/22） |
| **T10** | 15、16 | 去方框磁贴底 + 图标描边/填充 token；与 T09 的极光着色联动 |
| **T13** | 20 | 等 D2 布局稿 |
| **T03** | 14 | 已有卡，换 `ic_logo.xml` 为 B |
| **W3** | 09（布局侧） | 迷你条/全屏播放页的封面色使用范围，随 T14/T18 一起改 |

**下一步入口**：W0-D2 先答 §6 的 9 个问题 → 产出 token 修改清单 → T09 按三批执行，每批用 `CinefinComponentPreviews` + 真机抽查核销。

---

## 8. 审计过程记录

- 读：`docs/V2_DESIGN.md`（全）、`docs/UI_DESIGN_SYSTEM.md`（§1–§3.3 + R1 token 表）、`docs/V201_TASKS.md`、`docs/design/s1-decision.md`、`s4-README.md`、`v2-theme-frames/index.html`（索引与文案）。
- 代码：`core/.../theme/` 全部 16 个文件；`core/.../components/` 抽取 Button/Card/Switch/Slider/Chip/Segmented/Navigation/Drawer；`app/phone` 的 MainActivity/HomeScreen/HomeHero/PlayButton/ProgressBar/ItemButtonsBar/SectionHeader/PosterItemCard/SettingsScreen/PlayerControlOverlay/CommunityRatingBadge/PageMetrics；`modes/music` 两屏；`modes/book` reader 三文件。
- 目视：`k1-aurora/phone-home.png`、`phone-detail.png`（2 张，截图预算用尽）。
- 未做：真机取色/uiautomator 取证、TV 模块、逐页穷尽清单、动态色真机表现。
