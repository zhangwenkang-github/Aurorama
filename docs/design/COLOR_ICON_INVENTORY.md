# Cinefin 颜色 / 图标台账（2.0.2 · D6）

> **状态（2026-11-26 · C8b 生成，C8c 勘误 + 灰度裁定，C8d 面板焦点/选中态收口）**：本表是 2.0.2 主题 / 配色的**核对清单**，只记录代码真实现状，不含设计提案。今后改主题、改配色、增删图标，先对表再动代码；表中「死资源」只留档不删除（2.0.2 负责人裁定）。
>
> **扫描口径**：在仓库根目录执行（排除 `build/`、`graphify-out/`）——
>
> - token 消费面：`grep -rIn --include=*.kt "colors.<slot>"` / `"CinefinTokens.<name>"`，计数 = 命中行数 / 命中文件数（**含单测**，因而略高于生产消费量）；「生产零消费」= 去掉 `src/test` 与主题文件后无命中。
> - 图标引用：`grep -rIl -e "R\.drawable\.<name>" -e "@drawable/<name>"`，剔除 `build/`、`src/test`；**同名资源按名字合并计数**（core 与 settings 有 8 个同名图标，见 B7）。
> - tint 状态：对每处 `<name>` 引用行向后取 6 行窗口，窗口内出现 `tint =` 即判为显式 tint；其中 `Color.White/Black/Gray`、`Color(0x…)` 记「写死色」，其余（`colors.*`、`LocalContentColor`、`Color.Unspecified`）记「token tint」。**这是启发式分桶**：窗口可能蹭到相邻图标调用，故「混合」行的比例只作量级参考，精确到单点时以引号内文件为准。
> - 代表引用：仓内相对路径的模块前缀缩写——`phone:` = `app/phone/src/main/java/com/zhangwenkang/cinefin/`、`core:` = `core/src/main/java/com/zhangwenkang/cinefin/`、`music:` = `modes/music/.../music/`、`settings:` = `settings/.../settings/`、`player:` = `player/local/.../player/local/`、`tv:` = `app/tv/.../cinefin/`。XML 引用给全路径。

---

## A. Token 表

### A1 语义槽 `CinefinColors`（组件层唯一允许入口）

`LocalCinefinColors.current` 提供下表 43 个槽位；组件禁止直接引用 `CinefinTokens` 原始值（例外见 A2 备注）。

| 语义槽 | aurora.dark | light | 消费面（行/文件） | 代表消费点 |
| --- | --- | --- | --- | --- |
| `surface` | `#080D17` | `#FAFAFB` | 28 / 17 | `core:presentation/components/CinefinNavigation.kt`、`phone:presentation/offline/OfflineScreens.kt` |
| `surfaceDim` | `#04070D` | `#E6E9F0` | 5 / 4 | `phone:presentation/player/PlayerContentPanel.kt`、`PlayerDebugOverlay.kt` |
| `surfaceBright` | `#131D30` | `#FFFFFF` | 1 / 1 | **生产零消费**（仅 `DesignTokenConsistencyTest`） |
| `surfaceContainerLowest` | `#04070D` | `#FFFFFF` | 3 / 3 | `phone:presentation/player/PlayerControlOverlay.kt`、`film/components/HomeHero.kt` |
| `surfaceContainerLow` | `#080D17` | `#FAFAFB` | 6 / 2 | `phone:presentation/player/PlayerControlOverlay.kt`（5 处）、`PlayerSettingsPanel.kt` |
| `surfaceContainer` | `#0D1522` | `#F0F2F6` | 41 / 21 | `music:presentation/MusicModeScreen.kt`、`phone:presentation/player/PlayerControlOverlay.kt` |
| `surfaceContainerHigh` | `#131D30` | `#E6E9F0` | 51 / 29 | `phone:presentation/film/BookshelfScreen.kt`、`film/components/PosterItemCard.kt` |
| `surfaceContainerHighest` | `#1C2638` | `#DDE0E6` | 12 / 10 | `music:presentation/MusicModeScreen.kt`、`MusicNowPlayingScreen.kt` |
| `onSurface` | `#F5F7FB` | `#10151F` | 153 / 60 | `phone:presentation/player/PlayerControlOverlay.kt`、`music:presentation/MusicModeScreen.kt` |
| `onSurfaceVariant` | `#9FAEC4` | `#4A5568` | 235 / 58 | 同上（全仓使用面最广的次级文字色） |
| `onSurfaceFaint` | `#5D6C85` | `#8A94A6` | 66 / 26 | `phone:presentation/film/downloads/DownloadRows.kt`、`PlayerControlOverlay.kt` |
| `outline` | 白 14% | `#10151F`@14% | 27 / 18 | `phone:presentation/player/PlayerControlOverlay.kt`、`settings/SettingsScreen.kt` |
| `outlineVariant` | 白 9% | `#10151F`@8% | 23 / 11 | `phone:presentation/player/PlayerControlOverlay.kt`、`settings/components/SettingsGroupCard.kt` |
| `inverseSurface` | `#F5F7FB` | `#10151F` | 7 / 4 | `core:presentation/components/CinefinButton.kt`、`CinefinSnackbar.kt` |
| `inverseOnSurface` | `#080D17` | `#F5F7FB` | 9 / 5 | 同上 |
| `scrim` | `#06080C`@72% | 同左 | 9 / 5 | `phone:presentation/player/PlayerControlOverlay.kt`、`core:CinefinMultiSelect.kt`、`core:CinefinDrawer.kt` |
| `error` | `#E87C6E` | 同左 | 31 / 14 | `phone:presentation/player/PlayerControlOverlay.kt`、`film/downloads/DownloadRows.kt` |
| `onError` | `#1A0F0C` | 同左 | 1 / 1 | **组件零消费**，仅进 M3 `ColorScheme` |
| `errorContainer` | `#372A2D` | `#F6D9D3` | 1 / 1 | `phone:presentation/film/components/ErrorCard.kt` |
| `onErrorContainer` | `#F5F7FB` | `#3F120B` | 1 / 1 | 同上 |
| `warning` | `#E0B84F` | 同左 | 2 / 2 | **预热位**：仅色板注册 + 单测，生产零消费 |
| `onWarning` | `#2A2100` | 同左 | 1 / 1 | 预热位 |
| `success` | `#45D483` | 同左 | 2 / 2 | 预热位 |
| `onSuccess` | `#04201A` | 同左 | 1 / 1 | 预热位 |
| `info` | `#6FA0FF` | 同左 | 2 / 2 | 预热位 |
| `onInfo` | `#0A1220` | 同左 | 1 / 1 | 预热位 |
| `navSurface` | `#0A101C` | `#FFFFFF` | 0 / 0 | **零消费**（导航栏实际走 `glass` / `surfaceContainer`） |
| `stateHover` | 白 8% | 黑 8% | 12 / 6 | `core:CinefinButton.kt`、`CinefinSegmentedControl.kt` |
| `stateFocus` | 白 12% | 黑 12% | 0 / 0 | **零消费**（焦点态走 `FocusRingAlpha`） |
| `statePressed` | 白 12% | 黑 12% | 4 / 3 | `core:CinefinButton.kt`、`CinefinNavigation.kt` |
| `progressTrack` | 白 12% | 黑 12% | 4 / 3 | `phone:film/downloads/DownloadRows.kt`、`core:CinefinSlider.kt` |
| `progressTrackOnImage` | 白 16% | 白 16% | 6 / 6 | `core:CinefinCard.kt`、`phone:film/components/ProgressBar.kt` |
| `topHighlight` | 白 5% | 白 5% | 4 / 4 | `core:CinefinCard.kt`、`phone:settings/components/SettingsRow.kt` |
| `disabledAlpha` | `0.38` | `0.38` | 9 / 8 | `phone:film/components/ItemButtonsBar.kt`、`core:CinefinSwitch.kt` |
| `aurora1` | `#3DE8C2` | `#0C9E82` | 60 / 20 | `core:CinefinButton.kt`（11 处）、`phone:ItemButtonsBar.kt`（6 处） |
| `aurora2` | `#6FA0FF` | `#3E6FE4` | 8 / 7 | `core:theme/CinefinBrushes.kt`、`music:MusicModeScreen.kt` |
| `aurora3` | `#B07CFF` | `#8B53E8` | 5 / 4 | `core:theme/CinefinBrushes.kt` |
| `onAurora` | `#04201A` | `#000000` | 13 / 8 | `core:CinefinButton.kt`、`music:MusicModeScreen.kt` |
| `auroraInk` | `#3DE8C2` | `#0A6B5A` | 4 / 4 | `music:MusicNowPlayingScreen.kt`、`core:CinefinNavigation.kt`、`CinefinLibraryTabs.kt` |
| `glass` | `#0A101C`@58% | 白@72% | 21 / 10 | `phone:PlayerControlOverlay.kt`（8 处）、`music:MusicNowPlayingScreen.kt` |
| `glassBorder` | 白 9% | `#10151F`@8% | 29 / 14 | `phone:PlayerControlOverlay.kt`（11 处）、`core:CinefinNavigation.kt` |
| `scrimBehindControl` | `#52000000` | 同左 | 2 / 2 | `core:theme/CinefinMediaSurface.kt` |
| `mediaContentShadow` | `#99000000` | 同左 | 4 / 2 | 同上 |

> amoled 变体只覆写 surface 系 8 槽 + `navSurface`（纯黑），lumen 走遗产色板；其余槽位三者共用。

### A2 原始色值 `CinefinTokens`

「消费面」= `CinefinTokens.<name>` 的跨文件引用；**除注明外，所有原始值只被 `CinefinColors.kt` 的色板注册引用**（即组件层不可见），这是 2.0.1 起的分层纪律。

#### A2.1 v1.0 §2.1 深色中性阶（遗留，13/14 零引用）

| 常量 | 值 | 槽位 | 消费面 |
| --- | --- | --- | --- |
| `SurfaceDark` | `#151A21` | — | `book:presentation/reader/ReaderSettings.kt`（阅读器深色底，跨层直引原始 token）+ 单测 |
| `SurfaceDimDark` | `#10141A` | — | 零引用 |
| `SurfaceContainerDark` | `#1B212B` | — | 零引用 |
| `SurfaceContainerHighDark` | `#222A36` | — | 零引用 |
| `SurfaceContainerHighestDark` | `#2A3340` | — | 零引用 |
| `SurfaceContainerLowestDark` | `#0E1217` | — | 零引用（派生） |
| `SurfaceContainerLowDark` | `#171D25` | — | 零引用（派生） |
| `SurfaceBrightDark` | `#323C4B` | — | 零引用（派生） |
| `OnSurfaceVariantDark` | `#A7B0BD` | — | 零引用 |
| `OnSurfaceFaintDark` | `#6E7887` | — | 零引用 |
| `OutlineDark` | `#2C3542` | — | 零引用 |
| `OutlineVariantDark` | `#232B36` | — | 零引用 |
| `InverseSurfaceDark` | `#F4F1EA` | — | 零引用 |
| `InverseOnSurfaceDark` | `#14181F` | — | 零引用 |

#### A2.2 v1.0 §2.4 浅色中性阶（遗留，全部零引用）

`SurfaceLight #F5F3EE`、`SurfaceDimLight #E9E5DC`、`SurfaceContainerLight #FFFFFF`、`SurfaceContainerHighLight #EFECE5`、`SurfaceContainerHighestLight #E6E2D9`、`SurfaceContainerLowestLight #FFFFFF`、`SurfaceContainerLowLight #FAF8F4`、`SurfaceBrightLight #FFFFFF`、`OnSurfaceVariantLight #59616E`、`OnSurfaceFaintLight #8A9099`、`OutlineLight #D8D3C8`、`OutlineVariantLight #E7E2D8`、`InverseSurfaceLight #1B212B`、`InverseOnSurfaceLight #F4F1EA` —— 均**零引用**（浅色主题实际走 A2.7 的 `AuroraLight*`）。

#### A2.3 语义色

| 常量 | 值 | 槽位 | 消费面 |
| --- | --- | --- | --- |
| `Error` | `#E87C6E` | `error`（全主题） | 色板注册 |
| `OnError` | `#1A0F0C` | `onError` | 色板注册 |
| `ErrorContainerDark` | `#372A2D` | `errorContainer`（aurora / amoled / lumen） | 色板注册 |
| `OnErrorContainerDark` | `#F4F1EA` | — | 零引用（lumen 改用 `LumenOnErrorContainer`） |
| `ErrorContainerLight` | `#F6D9D3` | — | 零引用（light 改用 `AuroraLightErrorContainer`，同值） |
| `OnErrorContainerLight` | `#3F120B` | — | 零引用（light 改用 `AuroraLightOnErrorContainer`，同值） |
| `Warning` | `#E0B84F` | `warning`（全主题） | 色板注册 + 单测 |
| `OnWarning` | `#2A2100` | `onWarning` | 同上 |
| `Success` | `#45D483` | `success` | 同上 |
| `OnSuccess` | `#04201A` | `onSuccess` | 同上 |
| `Info` | `#6FA0FF` | `info` | 同上 |
| `OnInfo` | `#0A1220` | `onInfo` | 同上 |
| `Scrim` | `#06080C` | `scrim` | 色板注册 + `book:ReaderScreen.kt`（9 处）、`ReaderTtsBar.kt` 直引 |
| `NavSurfaceDark` | `#12171D` | — | 零引用 |
| `NavSurfaceLight` | `#EDEAE3` | — | 零引用 |

#### A2.4 状态层 / 透明度常量

| 常量 | 值 | 用途 | 消费面 |
| --- | --- | --- | --- |
| `StateHoverAlpha` | 0.08 | `stateHover` | 色板注册 |
| `StateFocusAlpha` | 0.12 | `stateFocus` | 色板注册 |
| `StatePressedAlpha` | 0.12 | `statePressed` | 色板注册 |
| `DisabledAlpha` | 0.38 | `disabledAlpha` | 色板注册 |
| `ScrimAlpha` | 0.72 | `scrim` | 色板注册 + 阅读器直引（随 `Scrim`） |
| `ChromeTranslucency` | 0.82 | 底栏半透明（W42） | **零引用**（导航改走 `glass` 后失效） |
| `RailTranslucency` | 0.74 | 侧轨 / 抽屉半透明（W46） | **零引用** |
| `NavUnselectedIconAlpha` | 0.62 | 侧栏未选中图标提亮 | **零引用** |
| `MediaContainerAlpha` | 0.16 | 域媒体色容器合成 | 8 文件：`MediaColors`、`CinefinButton`、`CinefinCard`、`ItemButtonsBar`、`DownloadRows`、`ReaderSettings` 等 |
| `MediaContainerPressedAlpha` | 0.24 | 按下态容器 | 7 文件（同上消费面） |
| `MediaOutlineAlpha` | 0.45 | 媒体色描边 | 6 文件 |
| `RatingBadgeAlpha` | 0.12 | 评分徽标淡底 | `phone:film/components/CommunityRatingBadge.kt` + 单测 |
| `FocusRingAlpha` | 0.60 | 焦点环 | `core:CinefinButton.kt`、`CinefinComponentPreviews.kt` |
| `TopHighlightAlpha` | 0.05 | `topHighlight` | 色板注册 |
| `ProgressTrackOnImageAlpha` | 0.16 | `progressTrackOnImage` | 色板注册 |
| `GlassAlpha` | 0.58 | `glass` | 色板注册 |
| `ProgressTrackAlpha` | 0.12 | `progressTrack` | 色板注册 |

#### A2.5 域媒体色（影视 / 音乐 / 阅读 / 极光）

| 常量 | 值 | `MediaColors` 槽 | 消费面 |
| --- | --- | --- | --- |
| `MediaFilmBase` / `Bright` / `Dim` | `#E8A15C` / `#EDB680` / `#BE844B` | `base` / `bright` / `dim` | `MediaColors.kt` 的 `MediaFilm` |
| `MediaFilmContainer` / `ContainerPressed` / `Outline` | `#3C3533` / `#4C4037` / `#775B41` | `container` / `containerPressed` / `outline` | 同上（运行时被 `derivedOver` 重算） |
| `MediaFilmOnBase` | `#141009` | `onBase` | 同上 |
| `MediaMusicBase` / `Bright` / `Dim` | `#3FC9A0` / `#69D5B5` / `#34A583` | 同名 | `MediaMusic` |
| `MediaMusicContainer` / `ContainerPressed` / `Outline` | `#213C3E` / `#244947` / `#2B6D60` | 同名 | 同上 |
| `MediaMusicOnBase` | `#06120D` | `onBase` | 同上 |
| `MediaBookBase` / `Bright` / `Dim` | `#6FA8FF` / `#8FBBFF` / `#5B8AD1` | 同名 | `MediaBook` |
| `MediaBookContainer` / `ContainerPressed` / `Outline` | `#28374D` / `#2F415E` / `#415E8A` | 同名 | 同上 |
| `MediaBookOnBase` | `#0A1220` | `onBase` | 同上 |
| `MediaAuroraBright` | `#8FF5DD` | 中性域 `bright` | `MediaAurora` |
| `MediaAuroraDim` | `#2BA98E` | 中性域 `dim` + M3 `inversePrimary` | `MediaAurora` + `CinefinColors.kt`（scheme 映射） |

> 中性域 `base` 直接用 `Aurora1Dark`、`onBase` 用 `OnAuroraDark`、容器三档在 `AuroraDarkSurfaceContainer` 上按 alpha 合成 —— 三者同时被 `MediaColors.kt` 与色板注册引用。

#### A2.6 aurora.dark（默认主题）与 amoled.dark

aurora.dark：`AuroraDarkSurface #080D17`、`SurfaceDim #04070D`、`SurfaceBright #131D30`、`ContainerLowest #04070D`、`ContainerLow #080D17`、`Container #0D1522`、`ContainerHigh #131D30`、`ContainerHighest #1C2638`、`OnSurface #F5F7FB`、`OnSurfaceVariant #9FAEC4`、`OnSurfaceFaint #5D6C85`、`OutlineAlpha 0.14`、`OutlineVariantAlpha 0.09`、`InverseSurface #F5F7FB`、`InverseOnSurface #080D17`、`NavSurface #0A101C`、`OnErrorContainer #F5F7FB`、`GlassBorderAlpha 0.09`、`Aurora1 #3DE8C2`、`Aurora2 #6FA0FF`、`Aurora3 #B07CFF`、`OnAurora #04201A` —— 全部只被 `CinefinColors.kt` 引用（`AuroraDarkSurfaceContainer` / `Aurora1Dark` / `OnAuroraDark` 额外被 `MediaColors.kt`）。

amoled.dark：`Surface/SurfaceDim/ContainerLowest/ContainerLow #000000`、`SurfaceBright #121A2A`、`Container #0A0F18`、`ContainerHigh #121A2A`、`ContainerHighest #1B2332`、`NavSurface #000000` —— 只被色板注册。

#### A2.7 light（aurora / amoled / lumen 共用浅色变体）

`AuroraLightSurface #FAFAFB`、`SurfaceDim #E6E9F0`、`SurfaceBright #FFFFFF`、`ContainerLowest #FFFFFF`、`ContainerLow #FAFAFB`、`Container #F0F2F6`、`ContainerHigh #E6E9F0`、`ContainerHighest #DDE0E6`、`OnSurface #10151F`、`OnSurfaceVariant #4A5568`、`OnSurfaceFaint #8A94A6`、`OutlineAlpha 0.14`、`OutlineVariantAlpha 0.08`、`InverseSurface #10151F`、`InverseOnSurface #F5F7FB`、`NavSurface #FFFFFF`、`ErrorContainer #F6D9D3`、`OnErrorContainer #3F120B`、`GlassAlpha 0.72`、`GlassBorderAlpha 0.08`、`AuroraInkLight #0A6B5A`、`Aurora1Light #0C9E82`、`Aurora2Light #3E6FE4`、`Aurora3Light #8B53E8`、`OnAuroraLight #000000`。

其中 `AuroraLightOnSurface` 被色板 4 处引用（outline 两档复用），其余仅色板注册。

#### A2.8 Lumen 遗产色板（非用户可选）

`LumenBackground #08090C`、`LumenPanel #111319`、`LumenPanelElevated #171A21`、`LumenText #F2F5F9`、`LumenTextSecondary #98A2B3`、`LumenTextFaint #6B7483`、`LumenAccent #5CE1D2`、`LumenAccentSecondary #7CC4FF`、`LumenOnPrimary #0A0C11`、`LumenOnErrorContainer #F4F1EA`、`LumenLineAlpha 0.085`、`LumenLineSoftAlpha 0.05`、`LumenProgressTrackAlpha 0.14`、`LumenScrim #06070A`（**零引用**）—— 除 `LumenScrim` 外均只被 `lumenDark` 色板引用。

#### A2.9 阅读器 / 覆盖层 / 阴影 / 发光

| 常量 | 值 | 消费面 |
| --- | --- | --- |
| `PaperSurface` / `PaperAccent` | `#FBF6EC` / `#A8843C` | 阅读器纸色主题（6 / 5 处，`book` 模块） |
| `ReaderPanelDark` | `#191F28` | 阅读器排版面板（4 处） |
| `ReaderEyeCareSurface` | `#E7EFE1` | 护眼主题（3 处） |
| `ReaderOledSurface` | `#000000` | OLED 主题（2 处） |
| `AuroraGlowElevationDp` / `AuroraGlowAlpha` | `6` / `0.28` | 一级 CTA 投影：`core:CinefinButton.kt`、`phone:ItemButtonsBar.kt` |
| `ScrimBehindControl` | `#52000000` | `core:CinefinMediaSurface.kt` |
| `MediaContentShadowColor` / `OffsetYPx` / `BlurRadiusPx` / `ElevationDp` | `#99000000` / `1f` / `4f` / `2` | `core:CinefinMediaSurface.kt` |
| `OverlayGlass` | `#99000000` | **仅色板外的 1 处**（黑 60% 覆盖层底；2.0.1 起玻璃不做实时 blur） |
| `PlayerGlassAlpha` / `PlayerGlassPressedAlpha` | `0.28` / `0.44` | `phone:ItemTopBar.kt`、`PlayerControlOverlay.kt`、`core:CinefinMultiSelect.kt` |

### A3 `mediaColorsFor()` 域映射图（`MediaColors.kt:106`）

```
mediaColorsFor(ContentDomain, CinefinColors)
├─ Movie   → MediaFilm   (base #E8A15C 琥珀 / bright #EDB680 / dim #BE844B / onBase #141009)
├─ Music   → MediaMusic  (base #3FC9A0 松石 / bright #69D5B5 / dim #34A583 / onBase #06120D)
├─ Book    → MediaBook   (base #6FA8FF 天青 / bright #8FBBFF / dim #5B8AD1 / onBase #0A1220)
└─ Neutral → MediaAurora (base = Aurora1Dark #3DE8C2 / bright #8FF5DD / dim #2BA98E
                         / onBase ← 随色板 onAurora：深 #04201A、浅 #000000)
        ↓ 统一 derivedOver(colors.surfaceContainer)
   container        = base @16% 叠 surfaceContainer
   containerPressed = base @24% 叠 surfaceContainer
   outline          = base @45% 叠 surfaceContainer
```

- 发布值（`MediaFilm` / `MediaMusic` / `MediaBook` / `MediaAurora`）只服务「无需随色板派生」的静态场景（占位图底色等）；运行期一律取 `mediaColorsFor`。
- `otherMediaColorsFor()`（同文件 `:116`）给首页 / 设置例外页提供「并排展示另外两域颜色」，同样逐域 `derivedOver`。
- 消费面：`media.base` 20 行、`media.bright` 32 行、`media.container` 18 行、`media.outline` 13 行、`media.onBase` 3 行；`media.dim` / `media.containerPressed` 仅单测引用（无生产消费）。`LocalMediaColors` 共 16 个文件引用。

### A4 `CoverMediaColors`（封面取色 → `MediaColors`）

`mediaColorsFromCover(vibrant, muted, dominant, colors)`（`CoverMediaColors.kt:46`）纯函数派生：

1. 取色优先级 `vibrant ?: muted ?: dominant`；三者皆空 → 返回 `null`，调用方回落 `mediaColorsFor` 域色。
2. 提取色 HSL 饱和度 < `PlainSaturation 0.08` → 视为纯色封面，同样返回 `null`。
3. 可读性护栏 `readableCoverBase`：与 `surface` 对比度 < 3:1 时按 +8% 明度提亮，最多 3 步（不返回 null）。
4. `bright` / `dim` = base 明度 ±10%（`ToneShift`）；`onBase` 取黑 / 白中对比度更高者。
5. `container` / `containerPressed` / `outline` 交给 `derivedOver(surfaceContainer)`，与域色同一口径。

调用方（音乐模块）只负责把封面位图交给 `androidx.palette` 提取并按 itemId 缓存，全部判定集中在本文件，纯 JVM 可测。

### A5 2.0.2 裁定口径（D6 收口语）

- `media.*` **只作域身份位**：占位图标 tint、域徽标、封面 / 缩略图氛围底、空态插图。
- 交互强调（CTA、进度、选中、焦点、开关、滑杆、文字强调）**一律极光**（`aurora1` / `auroraInk` / `onAurora` / `glass` 系）。
- 新增「状态色」需求（告警 / 成功 / 信息）只准用 `warning` / `success` / `info` 三组槽位，禁手写 `Color(0x…)`。
- 图上控件垫层只准用 `glass` + `glassBorder` 或 `PlayerGlassAlpha`（D4 守卫已钉死）。

---

## B. 图标表

### B1 口径

- 资源总数 **137**：core 96 + music 10 + player 9（+ `drawable-nodpi/ic_music_placeholder.png` 1）+ settings 19 + film 1 + tv 1（冻结端）。
- 「Kotlin / XML 引用文件数」= 生产源码（剔除 `src/test`、`build/`）中命中 `R.drawable.<name>` / `@drawable/<name>` 的文件数；同名资源按名字合并计数。
- tint 状态分桶见文档开头口径说明；`XML` = 只被布局 / 主题 XML 引用（无 Kotlin 消费），`死资源` = 生产源码零引用（布局引用需按 ViewBinding 生成类名复核，见 B7）。
- 「token tint」= 显式 tint 且非写死色，其中既有 `colors.*`（语义 token，合规）也有 `media.*`（域媒体色）；后者是否落在身份位由 A5 口径判定。C8c/C8d/C9 裁定执行后全仓仅余 **4 处身份位**（`BookCoverPlaceholder` / `CinefinCard` 域徽标 / `CinefinEmptyState` / `MusicModeScreen` 占位音符）+ **1 处静态字幕轨徽标** 的 `tint/color = media.*`；字幕颜色预设与歌词高亮 / 外观属白名单，不计入交互槽。逐点见 C 节 #20 / #21 / #22。
- **死资源只标记不删除**（`ic_filter` / `header_gradient` 已在 C8a 删除，本表不留行）。

### B2 core 模块（96 个）

| 资源 | 定义模块 | Kotlin 引用 | XML 引用 | tint 化状态 | 代表引用 |
| --- | --- | --- | --- | --- | --- |
| `bg_rounded_rect_12dp` | core | 0 | 0 | 死资源 |  |
| `button_setup_background` | core | 0 | 0 | 死资源 |  |
| `circle_background` | core | 0 | 1 | XML | app/phone/src/main/res/layout/exo_main_controls.xml |
| `ic_alert_circle` | core | 7 | 0 | 混合：token 5 / 未 tint 2 | phone:presentation/film/components/DownloadStatusBadge.kt |
| `ic_arrow_down_up` | core | 2 | 0 | 未 tint | phone:presentation/film/components/LibraryContentHeader.kt |
| `ic_arrow_left` | core | 15 | 1 | 混合：token 4 / 未 tint 12 | phone:presentation/film/CollectionScreen.kt |
| `ic_arrow_right` | core | 8 | 0 | 混合：token 7 / 未 tint 1 | phone:NavigationRoot.kt |
| `ic_aspect` | core | 1 | 0 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_banner` | core | 1 | 0 | token tint | tv:presentation/setup/welcome/WelcomeScreen.kt |
| `ic_banner_foreground` | core | 0 | 1 | XML | core/src/main/res/mipmap-anydpi-v26/ic_banner.xml |
| `ic_book` | core | 8 | 0 | 混合：token 3 / 未 tint 10 | phone:NavigationRoot.kt |
| `ic_bookmark` | core | 7 | 0 | 混合：token 1 / 未 tint 7 | phone:NavigationRoot.kt |
| `ic_bookmark_filled` | core | 3 | 0 | 写死色 1 处 | phone:presentation/film/components/FavoriteBadge.kt |
| `ic_certificate` | core | 1 | 0 | 未 tint | phone:presentation/setup/servers/ServersScreen.kt |
| `ic_check` | core | 18 | 0 | 混合：token 14 / 写死 1 / 未 tint 8 | phone:presentation/film/components/ItemButtonsBar.kt |
| `ic_chevron_down` | core | 5 | 0 | 混合：token 3 / 未 tint 5 | phone:NavigationRoot.kt |
| `ic_chevron_up` | core | 5 | 0 | 混合：token 3 / 未 tint 5 | phone:NavigationRoot.kt |
| `ic_close` | core | 14 | 0 | 混合：token 9 / 未 tint 9 | phone:playback/CinefinMediaNotificationProvider.kt |
| `ic_closed_caption` | core | 3 | 1 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_coffee` | core | 0 | 0 | 死资源 |  |
| `ic_database` | core | 2 | 0 | 混合：token 1 / 未 tint 1 | phone:NavigationRoot.kt |
| `ic_dolby` | core | 1 | 0 | 未 tint | phone:presentation/film/components/VideoMetadataBar.kt |
| `ic_download` | core | 11 | 0 | 混合：token 3 / 写死 1 / 未 tint 14 | phone:NavigationRoot.kt |
| `ic_eye` | core | 3 | 0 | 混合：token 2 / 未 tint 1 | phone:presentation/local/LocalLibraryScreens.kt |
| `ic_eye_off` | core | 3 | 0 | 混合：token 2 / 未 tint 1 | phone:presentation/local/LocalLibraryScreens.kt |
| `ic_fast_forward` | core | 3 | 1 | 未 tint | phone:playback/CinefinMediaNotificationProvider.kt |
| `ic_film` | core | 8 | 0 | 未 tint | phone:NavigationRoot.kt |
| `ic_gauge` | core | 1 | 1 | 未 tint | book:presentation/reader/ReaderControlLayer.kt |
| `ic_github` | core | 0 | 0 | 死资源 |  |
| `ic_globe` | core | 3 | 0 | 未 tint | phone:NavigationRoot.kt |
| `ic_heart` | core | 3 | 0 | 混合：token 3 / 未 tint 1 | tv:presentation/film/ShowScreen.kt |
| `ic_heart_filled` | core | 3 | 0 | 混合：token 3 / 未 tint 1 | tv:presentation/film/ShowScreen.kt |
| `ic_home` | core | 3 | 0 | 未 tint | phone:NavigationRoot.kt |
| `ic_info` | core | 2 | 0 | 未 tint | music:presentation/MusicModeScreen.kt |
| `ic_launcher_background` | core | 0 | 2 | XML | core/src/main/res/mipmap-anydpi-v26/ic_launcher.xml |
| `ic_launcher_foreground` | core | 0 | 2 | XML | core/src/main/res/mipmap-anydpi-v26/ic_launcher.xml |
| `ic_launcher_monochrome` | core | 0 | 1 | XML | core/src/main/res/mipmap-anydpi-v33/ic_launcher.xml |
| `ic_library` | core | 7 | 0 | 混合：token 3 / 未 tint 6 | phone:NavigationRoot.kt |
| `ic_lock` | core | 4 | 1 | 混合：token 1 / 未 tint 4 | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_logo` | core | 9 | 0 | 混合：token 9 / 未 tint 1 | phone:NavigationRoot.kt |
| `ic_logs` | core | 1 | 0 | 未 tint | phone:presentation/film/components/ErrorCard.kt |
| `ic_menu` | core | 1 | 0 | 未 tint | book:presentation/reader/ReaderControlLayer.kt |
| `ic_minus_fat` | core | 0 | 0 | 死资源 |  |
| `ic_music` | core | 8 | 0 | 混合：token 3 / 未 tint 10 | phone:NavigationRoot.kt |
| `ic_pause` | core | 9 | 0 | 混合：token 4 / 写死 1 / 未 tint 5 | phone:playback/CinefinMediaNotificationProvider.kt |
| `ic_picture_in_picture` | core | 0 | 1 | XML | app/phone/src/main/res/layout/exo_main_controls.xml |
| `ic_play` | core | 18 | 1 | 混合：token 7 / 未 tint 16 | phone:playback/CinefinMediaNotificationProvider.kt |
| `ic_playlist` | core | 4 | 0 | 未 tint | phone:presentation/navigation/CinefinDrawer.kt |
| `ic_plus` | core | 5 | 0 | 混合：token 5 / 未 tint 2 | phone:presentation/film/BookshelfScreen.kt |
| `ic_repeat` | core | 2 | 0 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_rewind` | core | 2 | 1 | 未 tint | phone:presentation/player/PlayerContentPanel.kt |
| `ic_rotate_ccw` | core | 10 | 0 | 混合：token 4 / 未 tint 6 | phone:presentation/console/WebConsoleScreen.kt |
| `ic_save` | core | 1 | 0 | 未 tint | phone:presentation/settings/SettingsFileEditScreen.kt |
| `ic_search` | core | 7 | 0 | 混合：token 2 / 未 tint 6 | phone:presentation/components/TopBarAction.kt |
| `ic_server` | core | 11 | 0 | 混合：token 3 / 写死 1 / 未 tint 7 | phone:presentation/film/components/ServerSelectionItem.kt |
| `ic_server_off` | core | 2 | 0 | 混合：token 1 / 未 tint 1 | phone:presentation/offline/OfflineScreens.kt |
| `ic_settings` | core | 3 | 0 | 混合：token 1 / 未 tint 2 | phone:NavigationRoot.kt |
| `ic_skip_back` | core | 5 | 1 | 混合：token 1 / 未 tint 4 | phone:playback/CinefinMediaNotificationProvider.kt |
| `ic_skip_forward` | core | 7 | 2 | 混合：token 3 / 未 tint 6 | phone:playback/CinefinMediaNotificationProvider.kt |
| `ic_smartphone` | core | 1 | 0 | 未 tint | music:presentation/MusicNowPlayingScreen.kt |
| `ic_sparkles` | core | 0 | 0 | 死资源 |  |
| `ic_speaker` | core | 9 | 1 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_speed_forward` | core | 0 | 1 | XML | app/phone/src/main/res/layout/activity_player.xml |
| `ic_star` | core | 5 | 0 | 混合：token 3 / 未 tint 2 | phone:presentation/film/components/FavoritesCard.kt |
| `ic_stat_aurorama` | core | 1 | 0 | 未 tint | phone:playback/CinefinMediaNotificationProvider.kt |
| `ic_sun` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_0` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_12` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_25` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_37` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_50` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_62` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_75` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_87` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_sun_level` | core | 0 | 1 | XML | app/phone/src/main/res/layout/activity_player.xml |
| `ic_trash` | core | 6 | 0 | 混合：token 1 / 未 tint 6 | phone:presentation/film/downloads/DownloadRows.kt |
| `ic_tv` | core | 2 | 0 | 未 tint | phone:presentation/film/components/LibraryPlaceholderIcons.kt |
| `ic_unlock` | core | 2 | 1 | token tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_user` | core | 7 | 0 | 写死色 2 处 | phone:presentation/film/components/HomeHeader.kt |
| `ic_user_color_on_primary` | core | 0 | 0 | 死资源（随 user_placeholder 失效） |  |
| `ic_video` | core | 2 | 0 | 未 tint | phone:presentation/local/LocalLibraryScreens.kt |
| `ic_view_grid` | core | 1 | 0 | 未 tint | phone:presentation/film/components/LibraryContentHeader.kt |
| `ic_view_list` | core | 2 | 0 | 未 tint | phone:presentation/film/components/LibraryContentHeader.kt |
| `ic_volume` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_volume_0` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_volume_50` | core | 0 | 1 | XML（level-list → activity_player） |  |
| `ic_volume_level` | core | 0 | 1 | XML | app/phone/src/main/res/layout/activity_player.xml |
| `ic_x` | core | 2 | 0 | 混合：token 1 / 未 tint 1 | phone:presentation/film/components/DownloaderCard.kt |
| `overlay_background` | core | 0 | 2 | XML | app/phone/src/main/res/layout/activity_player.xml |
| `person_placeholder` | core | 0 | 0 | 死资源 |  |
| `progress_scale_drawable` | core | 0 | 1 | XML | app/phone/src/main/res/layout/activity_player.xml |
| `ripple_background` | core | 0 | 0 | 死资源 |  |
| `rounded_corner` | core | 0 | 1 | XML | app/phone/src/main/res/layout/exo_locked_controls.xml |
| `transparent_circle` | core | 0 | 1 | XML | app/phone/src/main/res/layout/activity_player.xml |
| `transparent_circle_background` | core | 0 | 1 | XML | app/phone/src/main/res/layout/exo_main_controls.xml |
| `user_placeholder` | core | 0 | 0 | 死资源 |  |

### B3 music 模块（10 个）

| 资源 | 定义模块 | Kotlin 引用 | XML 引用 | tint 化状态 | 代表引用 |
| --- | --- | --- | --- | --- | --- |
| `ic_music_equalizer` | music | 1 | 0 | 未 tint | music:presentation/MusicNowPlayingScreen.kt |
| `ic_music_lyrics` | music | 1 | 0 | 未 tint | music:presentation/MusicNowPlayingScreen.kt |
| `ic_music_palette` | music | 1 | 0 | token tint | music:presentation/MusicLyricsOverlayContent.kt |
| `ic_music_recent` | music | 0 | 0 | 死资源 |  |
| `ic_music_repeat_one` | music | 1 | 0 | 未 tint | music:presentation/MusicNowPlayingScreen.kt |
| `ic_music_sequence` | music | 1 | 0 | 未 tint | music:presentation/MusicNowPlayingScreen.kt |
| `ic_music_shuffle` | music | 2 | 0 | 混合：token 1 / 未 tint 2 | music:presentation/MusicModeScreen.kt |
| `ic_music_sleep` | music | 1 | 0 | 未 tint | music:presentation/MusicNowPlayingScreen.kt |
| `ic_music_text_size` | music | 1 | 0 | 未 tint | music:presentation/MusicLyricsOverlayContent.kt |
| `ic_music_timer` | music | 1 | 0 | 未 tint | music:presentation/MusicLyricsOverlayContent.kt |

### B4 player/local 模块（9 个矢量 + 1 个 nodpi 位图）

| 资源 | 定义模块 | Kotlin 引用 | XML 引用 | tint 化状态 | 代表引用 |
| --- | --- | --- | --- | --- | --- |
| `ic_player_decode` | player | 1 | 0 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_player_fullscreen` | player | 2 | 0 | 未 tint | phone:presentation/player/PlayerContentPanel.kt |
| `ic_player_fullscreen_exit` | player | 2 | 0 | 未 tint | phone:presentation/player/PlayerContentPanel.kt |
| `ic_player_info` | player | 1 | 0 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_player_more` | player | 0 | 0 | 死资源 |  |
| `ic_player_pip` | player | 1 | 0 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_player_settings` | player | 1 | 0 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_player_sleep` | player | 1 | 0 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_player_speed` | player | 1 | 0 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_music_placeholder` | player | 1 | 0 | 未 tint | player:presentation/PlayerHolder.kt |

### B5 settings 模块（19 个）

| 资源 | 定义模块 | Kotlin 引用 | XML 引用 | tint 化状态 | 代表引用 |
| --- | --- | --- | --- | --- | --- |
| `ic_closed_caption` | settings | 3 | 1 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_device` | settings | 1 | 0 | 未 tint | settings:presentation/settings/SettingsViewModel.kt |
| `ic_download` | settings | 11 | 0 | 混合：token 3 / 写死 1 / 未 tint 14 | phone:NavigationRoot.kt |
| `ic_folder` | settings | 1 | 0 | 未 tint | settings:presentation/settings/SettingsViewModel.kt |
| `ic_hard_drive` | settings | 1 | 0 | 未 tint | settings:presentation/settings/SettingsViewModel.kt |
| `ic_info` | settings | 2 | 0 | 未 tint | music:presentation/MusicModeScreen.kt |
| `ic_languages` | settings | 3 | 0 | 未 tint | phone:presentation/settings/SettingsScreen.kt |
| `ic_layout_dashboard` | settings | 1 | 0 | 未 tint | settings:presentation/settings/SettingsViewModel.kt |
| `ic_lyrics` | settings | 1 | 0 | 未 tint | settings:presentation/settings/SettingsViewModel.kt |
| `ic_media_library` | settings | 1 | 0 | 未 tint | settings:presentation/settings/SettingsViewModel.kt |
| `ic_music` | settings | 8 | 0 | 混合：token 3 / 未 tint 10 | phone:NavigationRoot.kt |
| `ic_network` | settings | 1 | 0 | 未 tint | settings:presentation/settings/SettingsViewModel.kt |
| `ic_palette` | settings | 3 | 0 | 未 tint | phone:presentation/settings/SettingsScreen.kt |
| `ic_play` | settings | 18 | 1 | 混合：token 7 / 未 tint 16 | phone:playback/CinefinMediaNotificationProvider.kt |
| `ic_server` | settings | 11 | 0 | 混合：token 3 / 写死 1 / 未 tint 7 | phone:presentation/film/components/ServerSelectionItem.kt |
| `ic_server_off` | settings | 2 | 0 | 混合：token 1 / 未 tint 1 | phone:presentation/offline/OfflineScreens.kt |
| `ic_sidebar` | settings | 1 | 0 | 未 tint | settings:presentation/settings/SettingsViewModel.kt |
| `ic_speaker` | settings | 9 | 1 | 未 tint | phone:presentation/player/PlayerControlOverlay.kt |
| `ic_user` | settings | 7 | 0 | 写死色 2 处 | phone:presentation/film/components/HomeHeader.kt |

### B6 film（1 个）/ tv（1 个，冻结端）

| 资源 | 定义模块 | Kotlin 引用 | XML 引用 | tint 化状态 | 代表引用 |
| --- | --- | --- | --- | --- | --- |
| `ic_video_sleep` | film | 1 | 0 | token tint | phone:presentation/video/VideoScreen.kt |
| `ic_banner` | tv | 1 | 0 | token tint | tv:presentation/setup/welcome/WelcomeScreen.kt |

### B7 死资源清单与同名资源

**直接零引用（9 个，core）**：`bg_rounded_rect_12dp`、`button_setup_background`、`ic_coffee`、`ic_github`、`ic_minus_fat`、`ic_sparkles`、`person_placeholder`、`ripple_background`、`user_placeholder`。

**链式死资源（引用链只连到死 drawable）**：

- `user_placeholder.xml` 全仓零引用 → 其唯一引用的 `ic_user_color_on_primary`（1 个）随链失效。

**其它模块死资源**：music `ic_music_recent`、player `ic_player_more`。

合计 **12 / 137** 为死资源（core 10、music 1、player 1），全部只做「死资源-留档」标记，不删除。

> **判活口径（2026-11-26 C8c 勘误）**：布局是否存活不能只按文件名 grep——ViewBinding / DataBinding 的引用点是生成类 `<帕斯卡类名>Binding`。
> - `app/phone/src/main/res/layout/activity_player.xml` → `PlayerActivity.kt:56` import、`:185` 声明、`:350` `ActivityPlayerBinding.inflate()`，**存活**；其链上的 `ic_speed_forward`、`ic_sun_level`（level-list）、`ic_sun`、`ic_sun_0/12/25/37/50/62/75/87`、`ic_volume_level`（level-list）、`ic_volume`、`ic_volume_0/50`、`progress_scale_drawable`、`transparent_circle` 因此全部**存活**。
> - `exo_player_view.xml` / `exo_player_control_view.xml` 是 media3 `PlayerView` / `PlayerControlView` 的默认布局名，由库内按 `R.layout.exo_*` 取用，源码里不出现字符串；`exo_main_controls.xml` / `exo_locked_controls.xml` 被 `exo_player_control_view.xml` `<include>`。因此 `circle_background`、`ic_picture_in_picture`、`transparent_circle_background`、`rounded_corner`、`overlay_background` 均为**活资源**。
> - 现仓内仅 `app/phone` 有 `res/layout`（5 个文件），已逐个按「文件名 → 帕斯卡 + `Binding`」复核：只有 `activity_player` 有生成类，其余 4 个按上述媒体库默认名 / `<include>` 判活。

**跨模块同名资源（8 组）**：`ic_play`、`ic_info`、`ic_music`、`ic_server`、`ic_server_off`、`ic_speaker`、`ic_user`、`ic_download` 在 `core` 与 `settings` 各有一份；其中 7 组两份内容不同（仅 `ic_user` 完全一致）。AAPT 资源合并后同配置只保留一份，**哪一份生效取决于模块依赖顺序，本次未做链接级实证** → 列入 C 节 C9 视觉复核。

---

## C. 残留登记表（D4 / D5 / D6 全点位）

状态以当前代码为准；「留档」= 已知且有意保留，不视为待修。

| # | 点位 | 卡号 | 处置状态 | 说明 / 证据 |
| --- | --- | --- | --- | --- |
| 1 | 规则外点位 ×4（D5 媒体色残留） | C5b | **DONE** | C8a `9422ddeb`：四点位统一归极光映射 |
| 2 | 留档 ×3（顶栏 end / 侧轨 start / 音乐横屏） | C3 | **DONE** | C8a `9422ddeb`：安全区按 safeDrawing 口径收口 |
| 3 | `ic_filter` / `header_gradient` 死 drawable | — | **DONE** | C8a 已删除；本表 B1/B7 留痕（守卫 b 防复活） |
| 4 | `windowBackground` 字面量 | — | **DONE** | C8a token 化（主题层随配色变） |
| 5 | 预览板 `LocalMediaColors` | — | **DONE** | C8a 主对齐；C8b 清理 `CinefinComponentPreviews.kt` 残余读取（见 #16） |
| 6 | `beginDownload` 重建不回收旧 release `.part/.apk` | C1 | **LATER（既有行为）** | 下载引擎历史行为，非本轮回归；随 C9 下载任务一并评估 |
| 7 | 桌面歌词底改不透明 `colors.surface` | C4 | **留档（负责人裁定）** | 维持不透明，不新增 `window` token |
| 8 | `LumenCardFrame(emphasized = true)` 全仓零调用 = 死分支 | C5 | **留档** | 保留不接线；后续新页面需要时再接 |
| 9 | 进度条 / 进度环运行时样本缺失（点位 #4/#5/#14–#19） | C5→C9 | **DONE（C9 补样本）** | K60 测试服真实下载（17.4MB FLAC，151→14 KB/s 在跑）：下载行进度条 = 填充 `aurora1` 实心 / 轨道 `#D3D5D8`（浅色主题实测 `#0C9E82` + `#D3D5D8`，琥珀 0）。海报 `auroraGradient` 进度仍仅 token 级证据（测试服无可续播海报进度） |
| 10 | `CinefinEmptyState` 占位图标 tint / `CinefinSourceBadge` 来源徽标 | C5b | **留档** | 小面积静态身份位，允用媒体色；C9 对账复核 |
| 11 | 库选择 chip 需 ≥3 库才显示 | C6 | **留档（既有条件）** | 产品既有条件，非缺陷 |
| 12 | 440dp 窄屏标签行计数挤占 tab 横滑 | C7 | **D2 既定行为，C9 复核** | 书架「最近阅读 + 本地书籍双空」真机分支由单测 `bookshelfShelfHeaderVisible` 兜底 |
| 13 | 音乐页横屏改用 `cinefinSafeHorizontal()` | C8a | **已裁定接受** | 与 `safeDrawing` 同源、消费式；音乐页 start 边在侧轨形态下属额外避让（口径登记） |
| 14 | 颜色像素未实证 3 点位（多选圆点 / 睡眠定时 / eyebrow） | C8a→C9 | **DONE（C9 像素实证）** | 睡眠定时激活图标 `#3DE8C2`（裁剪区 17.2%）；详情页 eyebrow「动画」`#3DE8C2`（22.1%）；下载页多选：进入多选「已选 1 项」+ 选中行描边 `#3DE8C2`（圆点本体 C4 已实证） |
| 15 | 测试服 TLS `-0x7280` 未通 | D5 后续 | **KNOWN_ISSUE（C9 复测未复现）** | C9 实测：视频播放链路正常（00:12/24:25 在走）、下载链路正常（17.4MB 真实下载 151 KB/s）。此前 `-0x7280` 未在本轮重现，口径改为「未复现，保持观察」 |
| 16 | `CinefinPageTopBar` 调用点双层水平留白 ×7 | C8b | **DONE** | 顶栏自 C8a 起自吃水平 inset；删除 `BookshelfScreen` / `LocalLibraryScreens` / `OfflineScreens`×3 / `AboutScreen` / `UpdateScreen` 的 `padding(start = safePadding.start)` |
| 17 | `CinefinComponentPreviews.kt` 残余读 `LocalMediaColors` | C8b | **DONE** | 两处占位底改 `colors.surfaceContainerHigh`（仅 @Preview），守卫 c 钉住 |
| 18 | 跨模块同名 drawable ×8 组 | C8b | **登记 / C9 复核** | 见 B7；未做链接级实证 |
| 19 | 原始 token 遗留：§2.1 深色阶 13 个、§2.4 浅色阶 14 个、`ChromeTranslucency` / `RailTranslucency` / `NavUnselectedIconAlpha` / `LumenScrim` / `NavSurfaceDark` / `NavSurfaceLight` 零引用 | C8b | **留档** | 属色板历史层，删除需评估主题 id 兼容；本卡不动 |
| 20 | 15 处 `tint / color / background = media.*`（C8b 台账抽查新发现） | C8b→C8c | **已裁定（2026-11-26）：12 处归极光 / 1 处按先例保留，DONE（本 commit）** | 身份位 3 处合规不动；逐点处置见下表 |
| 21 | 同状态姊妹点位（C8c 登记 19 处） | C8c→C8d | **已裁定（2026-11-26）全部归极光：DONE** | 19 处中 16 处转换、3 处实为字幕颜色预设（白名单，不改）；审计另补 1 处漏登的播放器 Tab。逐点见 C-21 明细 |
| 22 | 歌词外观 / 显示预设 chip 选中态 4 行（`MusicLyricsPage` / `MusicLyricsTextControls`） | C8d→C9 | **DONE（负责人裁定：交互强调，非白名单）** | 两处 chip 改委托 `CinefinFilterChip(compact = true)`：选中 = aurora1@16% 容器 + aurora1 描边 + `navSelectedContent` 文字；K60 像素：选中 chip 容器 `#0E272C`（深色）/`#D4EBE8`（浅色）+ `#3DE8C2` 20.1% 命中，未选中 0%；守卫 e 钉住 |

### C-20 处置明细（2026-11-26 C8c）

点位行号为 C8b 上报时（`e2500d81`）的行号，C8c 改动后整体有位移；以组件名 / 语义定位为准。

| # | 点位 | 分类 | 处置 |
| --- | --- | --- | --- |
| 1 | `PlayerContentPanel.kt:332` 选集行选中勾 | 选中勾 | 归极光：勾 `colors.aurora1`；同行「选中底色 / 描边 / 集号文字」一并归极光（16% / 45% / aurora1），不留半套 |
| 2 | `PlayerContentPanel.kt:609/614`「正在播放」角标 | 激活态 | 归极光：角标底 `colors.aurora1` + 文字 `colors.onAurora`；同卡片选中底 / 描边 / 集号文字一并归极光 |
| 3 | `PlayerControlOverlay.kt:2585` 睡目标签 | 激活态 | 归极光：`colors.aurora1`（与视频页睡眠定时图标同口径） |
| 4 | `PlayerControlOverlay.kt:3302` 跳过片头 chip 图标 | 交互控件 | 归极光：`colors.aurora1`；玻璃底 / 描边维持 D4 口径 |
| 5 | `PlayerControlOverlay.kt:3342` 解锁键图标 | 交互控件 | 归极光：图标 `colors.aurora1`，键底 / 描边同步归极光（16% / 45%） |
| 6 | `PlayerControlOverlay.kt:3541` 面板选中勾 | 选中勾 | 归极光：勾与选中行底 / 描边 / 标题一并归极光 |
| 7 | `PlayerControlOverlay.kt:4307` 字幕轨徽标 | **静态信息徽标** | **保留现状**：主 / 次 / 原生字幕轨标，无选中 / 激活态，按 `CinefinSourceBadge` 先例保留域媒体色（代码内已加分类注记） |
| 8 | `MusicModeScreen.kt:1729` 菜单勾选 | 选中勾 | 归极光：`navSelectedContent(colors)`（深色 = aurora1 / 浅色 = auroraInk，保证浅色主题 AA） |
| 9 | `MusicModeScreen.kt:3500` 菜单行尾文字 | 文字强调 | 归极光：`navSelectedContent(colors)` |
| 10 | `MusicModeScreen.kt:3792` 队列「正在播放」 | 激活态 | 归极光：`navSelectedContent`；同行序号 `:3782` 一并归极光 |
| 11 | `MusicModeScreen.kt:4004` 接下来播放「正在播放」 | 激活态 | 归极光：`navSelectedContent`；同行标题 `:3986` 一并归极光 |
| 12 | `MusicModeScreen.kt:4081`「重试」按钮文字 | 操作强调 | 归极光：`navSelectedContent(colors)` |
| — | `BookCoverPlaceholder.kt:35` 占位图标 tint | 身份位 | 合规不动 |
| — | `CinefinCard.kt:388` 域徽标文字 | 身份位 | 合规不动 |
| — | `MusicModeScreen.kt:2483` 占位音符图标 | 身份位 | 合规不动 |

### C-21 处置明细（2026-11-26 C8d）

行号为 C8c 登记时（`eb174d31`）的行号；C8d 改动后整体有位移，以控件名定位为准。

| # | 点位 | 控件 / 语义 | 处置 |
| --- | --- | --- | --- |
| 1 | `PlayerControlOverlay.kt:2019` | 次级传输键焦点环 | `colors.aurora1.copy(alpha = 0.6f)` |
| 2 | `PlayerControlOverlay.kt:3198` | 播放器工具键焦点环 | 同上 |
| 3 | `PlayerControlOverlay.kt:2332` | 错误面板主按钮底 | `colors.aurora1`（字 `onAurora`） |
| 4 | `PlayerControlOverlay.kt:2333` | 错误面板次按钮描边 | `aurora1`@45%（字 `aurora1`） |
| 5 | `PlayerControlOverlay.kt:2339` | 错误面板按钮文字 | 主 `onAurora` / 次 `aurora1` |
| 6 | `PlayerControlOverlay.kt:3179` | 工具键选中底 | `aurora1`@16%（`MediaContainerAlpha`） |
| 7 | `PlayerControlOverlay.kt:3191` | 工具键选中描边 | `aurora1`@45%（`MediaOutlineAlpha`） |
| 8 | `PlayerControlOverlay.kt:3236` | 工具键选中图标 | `colors.aurora1` |
| 9 | `PlayerControlOverlay.kt:3253` | 工具键选中标签 | `colors.aurora1` |
| 10 | `PlayerControlOverlay.kt:3269` | 工具键「有内容 / 需注意」激活点 | `colors.aurora1` |
| 11 | `PlayerControlOverlay.kt:5185` | `PanelChip` 选中底 | `aurora1`@16% |
| 12 | `PlayerControlOverlay.kt:5191` | `PanelChip` 选中描边 | `aurora1`@45% |
| 13 | `PlayerControlOverlay.kt:5209` | `PanelChip` 选中文字 | `colors.aurora1` |
| 14 | `PlayerSettingsPanel.kt:529` | 分类 Tab（播放 / 手势）选中文字 | `colors.aurora1` |
| 15 | `PlayerSettingsPanel.kt:532` | 分类 Tab 选中底 | `aurora1`@16% |
| 16 | `PlayerSettingsPanel.kt:535` | 分类 Tab 选中描边 | `aurora1`@45% |
| 17 | `PlayerContentPanel.kt:145/153`（**C8c 漏登，C8d 审计补漏**） | 选集 / 队列 Tab 选中文字 + 下划线 | `colors.aurora1` |
| — | `PlayerControlOverlay.kt:5021/5022/5038` | **字幕颜色预设 chip**（`SubtitleColorChip`） | **白名单不改**：#21 登记时误归为「面板列表选中行」，执行时按控件更正 |
| — | `PlayerControlOverlay.kt:4325/4329/4330` | 字幕轨静态徽标 | C8c 已裁保留（先例 `CinefinSourceBadge`） |

**审计后仍保留的 `media.*`（全部合规）**：

- 身份位 4 处：`BookCoverPlaceholder.kt:29/35`、`CinefinCard.kt:380/381/388`、`CinefinEmptyState.kt:56`、`MusicModeScreen.kt:2477/2484`。
- 字幕颜色预设白名单：`PlayerControlOverlay.kt:5026/5027/5043/5063`（chip + swatch）。
- 歌词高亮白名单：`LyricsSheet.kt:231/232`、`MusicLyricsPage.kt:513/514/535/549`（C9 改动后行号有位移）。
- 歌词外观 / 显示预设 chip：**C9 已归极光**（#22），两文件只余歌词高亮本身消费 `media.base/bright`，交互槽为 0。

**审计复查（2026-11-26 C9）**：全仓 `media.*` 只剩上述白名单 + 身份位，无遗漏交互态；`MusicModeScreen` 两处 C8c 遗留的未使用 `val media` 已清。另登记待裁定：`ReaderControlLayer.kt:148` / `PageOverlay.kt:47` 的 `accent = LocalMediaColors.current.base` 驱动阅读器进度条 / 章节点 / 批注框描边（书籍域内容色，D5 白名单未列），本卡未改。

---

**维护约定**：本表与代码的对应关系由 `DesignTokenConsistencyTest` 的 D4 / D6 守卫兜底（五文件不消费 `media.base` 等交互槽、`ic_filter` / `header_gradient` 零引用、预览板不读 `LocalMediaColors`）。改主题或配色时，先改代码、再同步本表与 `docs/UI_DESIGN_SYSTEM.md` 顶部指针。
