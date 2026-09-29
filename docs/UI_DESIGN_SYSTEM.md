# Cinefin UI 设计系统 v1.0（Prism 棱镜）

> 设计会话：S4（2026-09-29）｜状态：v1.0（可落地冻结稿）
> 基础：S1 决策 —— **B · Prism 骨架 + A · Lumen 沉浸手法 + C · Nocturne 音乐/阅读皮肤**
> 用户修订（必须执行）：**媒体色融入按钮，不得独立显示在按钮旁**
> 适用范围：`app/phone`（手机 / 平板 / 折叠展开态）、`core` 设计系统模块、阅读器与音乐模式
> 对齐：Material Design 3（color roles / type scale / shape / motion token），数值均给 hex / dp / sp / ms / bezier，可直接翻译为 Compose token 与组件

---

## 0. 怎么用这份文档

| 角色 | 阅读顺序 |
|------|----------|
| 设计 / 视觉稿 | §1 原则 → §2 色彩 → §3 字体 → §6 动效 → §8 组件 |
| Compose 实现 | §2.4 token 映射 → §3.4 字阶 → §4 栅格 → §5 形状 → §9 Compose 落地 |
| 评审 / 验收 | §10 验收清单 + §2.6 媒体色禁止事项 |

一句话总结：**结构恒定、媒体色流动、颜色只进控件不进装饰、没有投影只有细线。**

---

## 1. 设计原则

1. **结构恒定，色彩流动（B · Prism）**——版面、栅格、列表、细线在全 App 恒定；变化来自当前内容域的 1 个媒体色。
2. **内容即光源（A · Lumen，限首页与播放器）**——全出血头图、焦点放大 1.04、底部渐隐遮罩；界面在需要时浮现。
3. **音乐与阅读有专属材质（C · Nocturne）**——黑胶沟槽纹理用于音乐；纸页 / 衬线 / 画框用于阅读；不改 B 的媒体色体系。
4. **媒体色只回答一个问题："我在哪个域"**——影视=琥珀、音乐=松石、阅读=天青；不承担装饰、不承担状态语义之外的表达。
5. **媒体色融入控件（本轮修订）**——按钮、分段控件、chip、徽标、导航选中态直接使用媒体色着色；禁止任何"按钮 + 右侧独立色块"的组合。
6. **无投影、细线优先、同心圆角**——用 1px 描边与顶部内高光构建层次；全系统 `elevation = 0dp`（唯一例外见 §5.3）。
7. **动效服务状态变化**——入场 560ms、错峰 40ms、面板 420ms；只动 `transform / opacity / color`。

---

## 2. 色彩系统

### 2.1 中性色（深色主题 = 默认主题）

| Token（Compose） | Hex | 用途 | M3 角色 |
|------------------|-----|------|---------|
| `CinefinColors.Surface` | `#151A21` | 全局底色（石板蓝黑） | `surface` |
| `CinefinColors.SurfaceDim` | `#10141A` | 播放器 / 阅读器深底、沉浸场景 | `surface-dim` |
| `CinefinColors.SurfaceContainer` | `#1B212B` | 卡片 / 面板 / 列表底 | `surface-container` |
| `CinefinColors.SurfaceContainerHigh` | `#222A36` | 悬浮、选中、分段控件底 | `surface-container-high` |
| `CinefinColors.SurfaceContainerHighest` | `#2A3340` | 对话框、菜单、弹层 | `surface-container-highest` |
| `CinefinColors.OnSurface` | `#F4F1EA` | 主文字（奶白，避免纯白刺眼） | `on-surface` |
| `CinefinColors.OnSurfaceVariant` | `#A7B0BD` | 次级文字、说明、元信息 | `on-surface-variant` |
| `CinefinColors.OnSurfaceFaint` | `#6E7887` | 三级文字、占位符、禁用文字 | 扩展 |
| `CinefinColors.Outline` | `#2C3542` | 1px 结构线、卡片描边（骨架） | `outline` |
| `CinefinColors.OutlineVariant` | `#232B36` | 弱分隔线（列表内、表格式信息） | `outline-variant` |
| `CinefinColors.InverseSurface` | `#F4F1EA` | Toast 底 | `inverse-surface` |
| `CinefinColors.InverseOnSurface` | `#14181F` | Toast 文字 | `inverse-on-surface` |
| `CinefinColors.Scrim` | `rgba(6,8,12,.72)` | 面板 / 对话框遮罩 | `scrim` |
| `CinefinColors.Error` | `#E87C6E` | 错误文字、错误图标、错误描边 | `error` |
| `CinefinColors.OnError` | `#1A0F0C` | 错误填充上的文字 | `on-error` |

系统**不设** success / warning / info 彩色 token：状态一律用"图标 + 文案 + 中性色"表达，避免引入第 4 个强调色（见 §2.6 第 7 条）。

状态层（叠加在任意可交互表面之上，对齐 M3 state layer）：

| Token | 值 | 用途 |
|-------|-----|------|
| `StateLayer.Hover` | 白 8%（`Color.White.copy(alpha = .08f)`） | 指针悬停 |
| `StateLayer.Focus` | 白 12% | 键盘 / 焦点 |
| `StateLayer.Pressed` | 白 12%（置于媒体色底时改用媒体色 24%，见 §2.2） | 按下 |
| `StateLayer.Disabled` | 内容 38% 不透明度 | 禁用 |

### 2.2 媒体色（三域身份，含明暗变体）

三域各 7 个 token，全部由 base 派生；**同一组件只允许使用当前域的 1 组**。

| Token | 影视 · 琥珀 | 音乐 · 松石 | 阅读 · 天青 | 用途 |
|-------|-------------|-------------|-------------|------|
| `Media.Base` | `#E8A15C` | `#3FC9A0` | `#6FA8FF` | 填充按钮底、进度填充、选中指示、当前行强调 |
| `Media.Bright` | `#EDB680` | `#69D5B5` | `#8FBBFF` | 深底上的媒体色文字 / 图标（≥4.5:1）、描边文字态 |
| `Media.Dim` | `#BE844B` | `#34A583` | `#5B8AD1` | 填充按钮按下底、次级描边、图表弱段 |
| `Media.Container` | `#3C3533` | `#213C3E` | `#28374D` | 选中 chip / 分段 / 导航项的底（= base 16% 叠加 SurfaceContainer） |
| `Media.ContainerPressed` | `#4C4037` | `#244947` | `#2F415E` | 上面三者的按下底（= base 24% 叠加） |
| `Media.OnBase` | `#141009` | `#06120D` | `#0A1220` | 媒体色填充上的文字 / 图标（深色，保证对比度） |
| `Media.Outline` | `#775B41` | `#2B6D60` | `#415E8A` | 描边态按钮 / chip 选中描边（= base 45% 叠加，视觉呈现色） |

Compose 侧推荐直接用 alpha 合成，避免维护两套色值：

```kotlin
// core/design/MediaColors.kt
data class MediaColors(
    val base: Color, val bright: Color, val dim: Color, val onBase: Color,
) {
    fun container(surface: Color) = base.copy(alpha = 0.16f).compositeOver(surface)
    fun containerPressed(surface: Color) = base.copy(alpha = 0.24f).compositeOver(surface)
    fun outline(surface: Color) = base.copy(alpha = 0.45f).compositeOver(surface)
}

val MediaFilm = MediaColors(Color(0xFFE8A15C), Color(0xFFEDB680), Color(0xFFBE844B), Color(0xFF141009))
val MediaMusic = MediaColors(Color(0xFF3FC9A0), Color(0xFF69D5B5), Color(0xFF34A583), Color(0xFF06120D))
val MediaBook = MediaColors(Color(0xFF6FA8FF), Color(0xFF8FBBFF), Color(0xFF5B8AD1), Color(0xFF0A1220))
```

对比度自检（WCAG 2.1，文字 ≥4.5:1，大字 ≥3:1）：

| 前景 / 背景 | 对比度 | 结论 |
|-------------|--------|------|
| `#EDB680` on `#151A21` | 9.0:1 | ✅ 正文可用 |
| `#E8A15C` on `#151A21` | 7.4:1 | ✅ 正文可用 |
| `#69D5B5` on `#151A21` | 8.8:1 | ✅ 正文可用 |
| `#3FC9A0` on `#151A21` | 7.1:1 | ✅ 正文可用 |
| `#8FBBFF` on `#151A21` | 8.0:1 | ✅ 正文可用 |
| `#6FA8FF` on `#151A21` | 6.6:1 | ✅ 正文可用 |
| `#141009` on `#E8A15C` | 9.1:1 | ✅ 填充按钮文字 |
| `#06120D` on `#3FC9A0` | 9.4:1 | ✅ 填充按钮文字 |
| `#0A1220` on `#6FA8FF` | 8.2:1 | ✅ 填充按钮文字 |

### 2.3 语义命名（组件层只准引用语义 token）

| 语义 token | 解析到 | 示例用法 |
|------------|--------|----------|
| `surface` | `Surface` | 页面底、沉浸场景底 |
| `surface-panel` | `SurfaceContainer` | 卡片、列表、面板 |
| `surface-raised` | `SurfaceContainerHigh` | 悬浮卡、分段底、选中项底 |
| `surface-overlay` | `SurfaceContainerHighest` | 对话框、菜单、抽屉 |
| `on-surface` / `on-surface-variant` / `on-surface-faint` | 同上表 | 主 / 次 / 三级文字 |
| `outline` / `outline-variant` | 同上表 | 1px 结构线 / 弱分隔线 |
| `media-movie` | `MediaFilm` | 影视域一切媒体色 |
| `media-music` | `MediaMusic` | 音乐域一切媒体色 |
| `media-book` | `MediaBook` | 阅读域一切媒体色 |
| `media-current` | 运行时 = 当前域 | 组件默认取当前域；**禁止手工跨域取色** |
| `progress-track` | 白 12%（深底）/ 黑 12%（纸色主题） | 进度条轨道 |
| `focus-ring` | `media-current` base 60% | 键盘焦点外环 |

### 2.4 M3 ColorScheme 映射（深色主题）

| M3 槽位 | 值 | 说明 |
|---------|-----|------|
| `primary` | 当前域 `Media.Base` | 随内容域切换（影 / 乐 / 书） |
| `onPrimary` | `Media.OnBase` | |
| `primaryContainer` | `Media.Container` | |
| `onPrimaryContainer` | `Media.Bright` | |
| `secondary` / `tertiary` | 当前域之外的另两个媒体色 | 仅首页 / 设置使用；业务页面不得引用 |
| `background` / `surface` | `#151A21` | |
| `surfaceVariant` | `#1B212B` | |
| `onSurface` / `onSurfaceVariant` | `#F4F1EA` / `#A7B0BD` | |
| `outline` / `outlineVariant` | `#2C3542` / `#232B36` | |
| `error` / `onError` | `#E87C6E` / `#1A0F0C` | |
| `inverseSurface` / `inverseOnSurface` | `#F4F1EA` / `#14181F` | Toast |

浅色主题（保留，§2.5）：`surface #F5F3EE`、`surfaceContainer #FFFFFF`、`onSurface #1B212B`、`onSurfaceVariant #59616E`、`outline #D8D3C8`、`outlineVariant #E7E2D8`；媒体色沿用同一组 base（浅底对比全部 ≥4.5:1），`Media.OnBase` 用于填充按钮文字。

### 2.5 三色纪律

1. 同一屏只允许出现**当前内容域的 1 个媒体色**（base / bright / dim / container 属同一色，算 1 个）。
2. **例外只有两处**：首页（三域并列入口）与设置页（主题 / 域选择）。例外页面内，单个组件仍只准用 1 个媒体色。
3. 影视域页面用琥珀、音乐域用松石、阅读域用天青；跨域跳转后**整屏色相立即切换**，不允许过渡期混色。
4. 播放器属于"当前播放内容"的域：放视频=琥珀，放音乐=松石；播放器 UI 只在进度、当前章节、激活面板上用媒体色。
5. 深浅主题切换不改变域色，只改变容器色与文字色。

### 2.6 媒体色"禁止事项"清单

| # | 禁止 | 原因 |
|---|------|------|
| 1 | ❌ 按钮、chip、列表行右侧/内部出现独立小色块、色点、色条 | 用户明确修订：媒体色必须融入控件本体 |
| 2 | ❌ 同屏出现 2 个以上媒体色（首页 / 设置页除外） | 三色纪律 |
| 3 | ❌ 媒体色渐变、发光、霓虹光晕 | 与"细线 + 无投影"的质感冲突 |
| 4 | ❌ 媒体色大面积铺底（整页背景、整张卡片底） | 内容图才是光源；媒体色是强调不是墙纸 |
| 5 | ❌ 媒体色 + 媒体色直接相邻（两个不同域的 chip 并排且同时高亮） | 首页例外也只允许单项高亮 |
| 6 | ❌ 用媒体色表示 error / warning / success / 下载状态 | 语义状态用 §2.1 的 error 或"图标 + 文案" |
| 7 | ❌ 新增第 4 个媒体色 / 业务侧自定义色值 | 全项目仅 3 域色；扩展必须回设计系统评审 |
| 8 | ❌ 在媒体色填充上使用白色文字 | `Media.OnBase` 深色才是配对前景（白色对比不足） |
| 9 | ❌ 未选中控件着色 | 未选中一律中性色；颜色只表达"当前 / 选中 / 进行中" |
| 10 | ❌ 深色底上直接使用 `Media.Base` 当小字号正文 | <16sp 文字用 `Media.Bright`（对比更高） |

---

## 3. 字体

### 3.1 字体族

| 角色 | 设计稿字体（HTML 渲染源） | 落地字体（Android 打包 / 系统） | 许可 |
|------|--------------------------|-------------------------------|------|
| 中文 UI（首选） | MiSans | MiSans（需确认项目内授权） | 小米字体授权，落地前确认 |
| 中文 UI（兜底） | Noto Sans SC | Noto Sans SC / 系统 `sans-serif` | OFL 1.1 ✅ |
| 编辑式衬线（斜体副题、章节题、专辑名） | Georgia Italic | **Literata Italic**（推荐落地）或 Noto Serif SC | Literata = OFL ✅；Georgia 不可自由分发 ❌ |
| 数据与编号（时间码、集数、页码、码率） | Cascadia Mono | Cascadia Mono（打包）或 `monospace` | OFL 1.1 ✅ |
| 阅读正文（纸色主题） | SimSun 渲染近似 | Noto Serif SC / 思源宋体（打包子集） | OFL 1.1 ✅ |

> 落地提示：Android 无 Georgia。视觉稿中的 Georgia 仅作气质近似，实现统一换成 **Literata Italic**（字面宽度差异 <3%，同为编辑衬线）；阅读正文统一 Noto Serif SC。字体文件进 `core` 模块资源，中文子集化后随 APK 分语言打包。

### 3.2 字阶（sp，深色主题）

| Token（Compose） | Size | LineHeight | Weight | 字距 | 用途 | M3 对应 |
|------------------|------|-----------|--------|------|------|---------|
| `DisplayLarge` | 57sp | 64sp | 400 | −0.25sp | 品牌大字（欢迎页 / 关于页） | displayLarge |
| `DisplayMedium` | 45sp | 52sp | 400 | 0sp | 欢迎页标语、空状态大标题 | displayMedium |
| `DisplaySmall` | 36sp | 44sp | 400 | 0sp | 播放器剩余时长（沉浸态） | displaySmall |
| `HeadlineLarge` | 40sp | 48sp | 600 | −0.2sp | 页面标题（平板，B 稿 40px） | headlineLarge |
| `HeadlineMedium` | 32sp | 40sp | 600 | 0sp | 详情主标题（手机）/ 区块大标题 | headlineMedium |
| `DetailTitle` ★ | 52sp | 58sp | 600 | −0.4sp | 详情页主标题（平板专用，B 稿 52px） | 扩展 |
| `HeadlineSmall` | 24sp | 32sp | 600 | 0sp | 次级标题、阅读章节题（无衬线版） | headlineSmall |
| `SectionTitle` ★ | 23sp | 30sp | 600 | 0sp | 区块标题（B 稿 23px） | 扩展 |
| `TitleLarge` | 22sp | 28sp | 600 | 0sp | 卡片 / 面板标题、歌词当前行原文 | titleLarge |
| `TitleMedium` | 17sp | 24sp | 500 | 0.15sp | 列表主文字、剧集名 | titleMedium |
| `TitleSmall` | 15sp | 20sp | 500 | 0.1sp | 小标题、行内强调 | titleSmall |
| `BodyLarge` | 17sp | 33sp | 400 | 0.2sp | 详情简介、长文（行高 ≈1.95，B 稿） | bodyLarge（改行高） |
| `BodyMedium` | 15sp | 22sp | 400 | 0.25sp | 常规说明、设置描述 | bodyMedium |
| `BodySmall` | 13sp | 20sp | 400 | 0.3sp | 元信息、辅助说明 | bodySmall |
| `LabelLarge` | 16sp | 20sp | 600 | 0.1sp | 按钮文字（B 稿 16px） | labelLarge（改字重） |
| `LabelMedium` | 15sp | 20sp | 500 | 0.3sp | chip、分段控件、tab | 扩展 |
| `LabelSmall` | 13sp | 16sp | 500 | 0.4sp | 徽标、时间标签 | labelSmall（改字号） |
| `MonoData` ★ | 14sp | 20sp | 400 | 0sp | 时间码、集数、页码、文件信息（tabular-nums） | 扩展 |
| `MonoDataSmall` ★ | 13sp | 18sp | 400 | 0sp | 队列编号、进度百分比 | 扩展 |
| `ReaderChapter` ★ | 24sp | 34sp | 400 | 0.2sp | 阅读章节题（Literata Italic / 渲染稿 Georgia） | 扩展 |
| `ReaderBody` ★ | 21sp | 42sp | 400 | 0.2sp | 阅读正文 · 深色主题（行高 ≈1.98） | 扩展 |
| `ReaderBodyPaper` ★ | 22sp | 46sp | 400 | 0.2sp | 阅读正文 · 纸色主题（宋体，行高 ≈2.08） | 扩展 |

★ = Cinefin 扩展 token（无 M3 对应，写入 `CinefinTypography`）。

### 3.3 排版纪律

1. 正文行宽 ≤ **60 个汉字**（约 34em）；阅读器正文列宽 ≤ 1060dp（平板双页）。
2. 大标题字距收紧（−0.2 ~ −0.4sp）；正文不收紧。
3. 所有时间码 / 编号用 `MonoData` + `tabular-nums`，保证跳动不抖动。
4. 中文正文行高 ≥1.6；阅读正文 ≥1.95；UI 说明文字 ≥1.4。
5. 不使用全大写英文标签堆叠；英文标签用 Georgia/Literata 斜体或句首大写。
6. 数字与中文混排时中文与拉丁间自动间距由字体处理，不手工加空格。

### 3.4 Compose Typography 骨架

```kotlin
// core/design/Type.kt
private val Sans = FontFamily(
    Font(R.font.misans_regular, FontWeight.Normal),
    Font(R.font.misans_medium, FontWeight.Medium),
    Font(R.font.misans_semibold, FontWeight.SemiBold),
)
private val Serif = FontFamily(Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic))
private val Mono = FontFamily(Font(R.font.cascadia_mono_regular, FontWeight.Normal))

val CinefinTypography = Typography(
    displayLarge = TextStyle(Sans, 57.sp, 64.sp, FontWeight.Normal, letterSpacing = (-0.25).sp),
    displayMedium = TextStyle(Sans, 45.sp, 52.sp),
    displaySmall = TextStyle(Sans, 36.sp, 44.sp),
    headlineLarge = TextStyle(Sans, 40.sp, 48.sp, FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(Sans, 32.sp, 40.sp, FontWeight.SemiBold),
    headlineSmall = TextStyle(Sans, 24.sp, 32.sp, FontWeight.SemiBold),
    titleLarge = TextStyle(Sans, 22.sp, 28.sp, FontWeight.SemiBold),
    titleMedium = TextStyle(Sans, 17.sp, 24.sp, FontWeight.Medium, letterSpacing = 0.15.sp),
    titleSmall = TextStyle(Sans, 15.sp, 20.sp, FontWeight.Medium, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(Sans, 17.sp, 33.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(Sans, 15.sp, 22.sp, letterSpacing = 0.25.sp),
    bodySmall = TextStyle(Sans, 13.sp, 20.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(Sans, 16.sp, 20.sp, FontWeight.SemiBold, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(Sans, 15.sp, 20.sp, FontWeight.Medium, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(Sans, 13.sp, 16.sp, FontWeight.Medium, letterSpacing = 0.4.sp),
)
// 扩展：DetailTitle 52/58、SectionTitle 23/30、MonoData 14/20、ReaderBody 21/42 等
// 以 extension TextStyle 常量提供（CinefinType.DetailTitle 等）。
```

---

## 4. 间距与栅格

### 4.1 间距刻度（4 / 8 基数）

| Token | dp | 用途 |
|-------|----|------|
| `space-1` | 4dp | 图标与文字间隙、徽标内边距 |
| `space-2` | 8dp | 相邻小元素、chip 之间 |
| `space-3` | 12dp | 按钮组、列表行内元素 |
| `space-4` | 16dp | 卡片内边距（紧凑）、表单行距 |
| `space-5` | 20dp | 手机页面边距（左右） |
| `space-6` | 24dp | 卡片内边距（标准）、面板内边距 |
| `space-7` | 26dp | **栅格 gutter（专用，平板）** |
| `space-8` | 32dp | 区块内间距、折叠态边距 |
| `space-10` | 40dp | 区块间距 |
| `space-12` | 48dp | 平板页面边距、大区块间距 |
| `space-16` | 64dp | 沉浸区留白、空状态上下留白 |

### 4.2 平板栅格（主验收：Xiaomi Pad 5，2048×1280 @ 横屏）

| 项 | 值 |
|----|----|
| 侧导航 | 164dp（展开态，含文字标签）/ 88dp（折叠态，仅图标） |
| 页面边距 | 48dp（左右） |
| 列数 | 12 列 |
| 列间距（gutter） | 26dp |
| 列宽 | ≈125dp（(2048 − 164 − 96 − 286) / 12） |
| 内容最大宽 | 1680dp（>1600dp 时居中，两侧留白） |
| 顶部安全区 | 44dp（页面标题起点 y=214dp，含标题块） |

常用组合：

| 版式 | 用法 |
|------|------|
| 2 列（6+6） | 双栏阅读、设置分栏 |
| 3 列（8+4） | 首页 bento（hero 2/3 + 侧栏 1/3，即 `hero span 8` + `stack span 4`） |
| 3 栏（3+6+3） | 详情页：海报 392dp / 主信息 auto / 制作信息 430dp，栏距 44dp（B 稿实测值） |
| 4 列海报墙 | `repeat(4, 1fr)`，间距 26dp |
| 6 列书架 | `repeat(6, 1fr)`，间距 32dp |

### 4.3 手机栅格（Redmi K60，≈360dp 宽）

| 项 | 值 |
|----|----|
| 页面边距 | 20dp |
| 列数 | 8 列 |
| Gutter | 12dp |
| 列宽 | ≈29.5dp |
| 底部 tab | 高度 64dp + 系统安全区 |
| 卡片间距 | 16dp |
| 沉浸头图高度 | 16:9 全宽 + 底部 96dp 渐隐 |

### 4.4 折叠 / 窗口尺寸分级

| Class | 宽度 | 导航形态 | 列数 | 页面边距 |
|-------|------|----------|------|----------|
| Compact | < 600dp | 底部 4 tab | 4 | 20dp |
| Medium | 600–839dp | 底部 tab / 88dp 图标轨 | 8 | 24dp |
| Expanded | 840–1199dp | 88dp 图标轨（可展开） | 8 → 12 | 32dp |
| Large | 1200–1599dp | 164dp 侧导航 | 12 | 48dp |
| ExtraLarge | ≥1600dp | 164dp 侧导航 + 内容居中 | 12 | 48dp |

折叠展开态规则：侧导航从 88dp 展开到 164dp 用 420ms `emphasized`；内容区列数变化不做补间动画（直接重排 + 淡入 200ms）。

---

## 5. 形状

### 5.1 圆角刻度（同心递减）

| Token | dp | 用途 | M3 对应 |
|-------|----|------|---------|
| `corner-xl` | 28dp | 对话框、大面板、手机封面、播放键 | extra-large |
| `corner-lg` | 22dp | 侧板（字幕 / 队列）、阅读排版面板、书架大卡 | 扩展 |
| `corner-md` | 16dp | 卡片、海报、横版卡、播放器工具键 | large |
| `corner-sm` | 12dp | 按钮、chip、输入框、分段控件、Toast | medium |
| `corner-xs` | 8dp | 徽标、缩略图、图标底、小方块 | small |
| `corner-2xs` | 4dp | 进度 knob、指示条、色带端点 | 扩展 |
| `corner-full` | 50% | 头像、圆形播放键、圆点指示 | full |

同心规则：内层半径 = 外层半径 − 包边厚度；双包边卡片外 22dp / 内 21dp，外 28dp / 内 27dp。

### 5.2 描边与内高光

| 元素 | 规格 |
|------|------|
| 结构线 / 卡片描边 | 1dp `Outline #2C3542` |
| 弱分隔线 | 1dp `OutlineVariant #232B36`（或白 7%） |
| 卡片顶部内高光 | `inset 0 1px 0 rgba(255,255,255,.05)` |
| 双包边外壳 | 1dp 渐变描边（白 10% → 白 2%），内核同款内高光 |
| 选中态描边 | 1dp `Media.Outline`（base 45% 呈现色） |
| 焦点环 | 2dp 外环 `Media.Base @ 60%` + 1dp 间隙（offset） |

### 5.3 无投影规则

1. 全系统 `elevation = 0dp`、`shadowElevation = 0dp`；层次只靠"表面色差 + 1dp 描边 + 内高光"。
2. 唯一例外：**画面覆盖层**（播放器控制条、全屏对话框下的 scrim、Toast）允许 `backdrop blur 14dp` + 黑 60% 半透明底；这不是投影。
3. 禁止 `shadow-md` 式硬投影、彩色光晕、媒体色 glow。

---

## 6. 动效

### 6.1 时长

| Token | ms | 用途 |
|-------|----|------|
| `motion-instant` | 100ms | 图标切换、勾选 |
| `motion-fast` | 200ms | hover / focus 描边与颜色过渡 |
| `motion-reader` | 220ms | 阅读翻页交叉淡入 |
| `motion-player` | 280ms | 播放器控制层出现 / 消失 |
| `motion-page` | 420ms | 面板、侧滑层、导航展开 |
| `motion-enter` | 560ms | 卡片 / 列表首次入场 |
| `motion-stagger` | 40ms | 列表项错峰间隔 |
| `motion-immersive` | 700ms | 首页沉浸头图（A 手法，仅此一处慢动效） |

### 6.2 曲线

| Token | 值 | 用途 |
|-------|-----|------|
| `Emphasized` | `cubic-bezier(0.2, 0.8, 0.2, 1)` | 入场、面板、导航（B 稿主曲线） |
| `Standard` | `cubic-bezier(0.4, 0, 0.2, 1)` | 颜色 / 描边状态过渡 |
| `Decelerate` | `cubic-bezier(0, 0, 0.2, 1)` | 元素出现 |
| `Accelerate` | `cubic-bezier(0.4, 0, 1, 1)` | 元素消失 |

> 与 M3 官方关系：M3 `emphasized = (0.2, 0, 0, 1)`；Cinefin 采用 B 稿的"更快出、更缓收"变体 `(0.2, 0.8, 0.2, 1)`，全项目统一，不混用。

### 6.3 入场与状态规则

| 场景 | 规则 |
|------|------|
| 卡片 / 列表入场 | opacity 0→1 + translateY 8dp→0 + blur 6dp→0，560ms Emphasized；列表逐项 40ms 错峰 |
| 面板升起（字幕 / 队列 / 排版） | translateY 24dp→0 + opacity，420ms Emphasized |
| 播放器控制层 | opacity + translateY 12dp→0，280ms Decelerate；禁止缩放 |
| 悬停 / 焦点 | 只过渡颜色（描边 `#2C3542` → `Media.Outline`），200ms Standard；**不做位移、不做缩放**（B 纪律） |
| 进度条 | 播放进度用 200ms linear 跟随（仅在此场景允许 linear）；seek 落点直接跳变 |
| 阅读翻页 | 交叉淡入 220ms；章节切换附加一次章节线横向展开（300ms） |
| 减少动画 | 系统动画缩放为 0 时全部时长置 0，直接切换 |

### 6.4 性能规则

- 只动画 `transform / opacity / color`；禁止 `width / height / top / left`。
- blur 只作用于固定层（顶栏、控制层、弹层）；滚动容器内禁用 blur。
- 噪点纹理只挂在 `pointer-events: none` 的固定覆盖层（opacity ≤5%）。

---

## 7. 图标

### 7.1 图标风格规范

| 项 | 规格 |
|----|------|
| 网格 | 24×24dp，内边距 2dp |
| 描边 | 1.5dp，圆头圆角（`stroke-linecap: round; stroke-linejoin: round`） |
| 填充变体 | 激活 / 播放 / 选中态用 fill（无描边） |
| 尺寸刻度 | 16 / 20 / 24 / 28 / 32 / 44dp |
| 颜色 | 默认 `OnSurfaceVariant`；激活 / 当前域 `Media.Bright`；强调 `OnSurface`；禁用 38% |
| 风格 | 自绘几何（棱镜语言）：无填充噪声、无厚描边；禁用 Material 默认图标的粗重外形 |
| 特殊键 | 导航 / 播放键使用"方圆形"（corner-16/22），与卡片同心 |

### 7.2 App 图标（三棱镜光栅）落地指引

概念：**白色光束从左侧射入一枚三角棱镜，右侧分出琥珀 / 松石 / 天青三条色带**——"白入彩出"，表达多媒体聚合；无播放三角、无胶片孔、无字母。

| 尺寸 | 交付 | 规则 |
|------|------|------|
| 512×512 | 商店 / 关于页 | 完整光栅细节，三色带 4dp 间隙 |
| 192 / 144 / 96 / 72 | launcher 位图兜底 | 三角 + 三带，细节按尺寸裁减 |
| 108×108（安全区 72dp） | 自适应图标前景层 | 三角居左 40%，光带向右展开；背景层纯 `#151A21` 无渐变 |
| 48×48 | 最小可读 | 最小色带宽 ≥3dp，三角描边 ≥2dp |
| 单色主题图标 | Android 13+ 主题图标 | 纯路径：棱镜轮廓 + 单色光带，颜色 `#F4F1EA` |
| 浅色变体 | 浅色 launcher | 背景 `#F5F3EE`，棱镜 `#1B212B`，三色带同 base |

落地文件：`core/src/main/res/drawable/ic_launcher_foreground.xml`（矢量前景）+ `mipmap-anydpi-v26/ic_launcher.xml`（自适应）+ `drawable/ic_launcher_monochrome.xml`；启动图动效为"三色带横向展开"，560ms Emphasized，不出现文字。

---

## 8. 组件规范（含全状态矩阵）

> 通用约定：所有组件只引用 §2.3 语义 token；状态含义统一为 default / hover / pressed / focused / disabled；媒体色代入的是**当前域**（`media-current`）。

### 8.1 按钮 Button（本轮修订重点）

**媒体色融入按钮**：媒体色直接进入按钮的底、描边、文字、图标四种变形；按钮右侧 / 内部**不再出现独立色块**。

尺寸：

| 尺寸 | 高 | 左右内边距 | 圆角 | 文字 | 图标 | 最小触控高 |
|------|----|------------|------|------|------|------------|
| Large | 56dp | 26dp | 12dp | LabelLarge 16sp/600 | 18dp | 56dp |
| Medium | 46dp | 20dp | 12dp | LabelLarge 16sp/600 | 18dp | 48dp |
| Small（列表内） | 38dp | 16dp | 12dp | LabelMedium 15sp/500 | 16dp | 48dp（外扩热区） |

四种变形 × 状态矩阵：

| 变形 \ 状态 | Default | Hover | Pressed | Focused | Disabled |
|-------------|---------|-------|---------|---------|----------|
| **Filled 填充** 主行动 | 底 `Media.Base`；文字/图标 `Media.OnBase` | 叠加白 8% 状态层 | 底 `Media.Dim`；内容 `OnBase` | 2dp 外环 `Media.Base@60%`（offset 1dp） | 底 `SurfaceContainerHigh`；内容 `OnSurfaceFaint`（38%） |
| **Outlined 描边** 次级 | 透明底；1dp `Media.Outline`；内容 `Media.Bright` | 叠白 8% 状态层；描边变 `Media.Base` | 底 `Media.Container`；描边 `Media.Base`；内容 `Bright` | 2dp 外环 | 描边 `Outline`；内容 38% |
| **Text 文本** 三级 | 无底无边；内容 `Media.Bright` | 白 8% 底（圆角 12） | 底 `Media.Container`（16%）；内容 `Bright` | 2dp 外环 | 内容 38% |
| **Icon 图标** 行内操作 | 44dp 方圆形（corner 12 或 full）；图标 `Media.Bright`；无底 | 白 8% 底 | 底 `Media.Container`；图标 `Base` | 2dp 外环 | 图标 38% |

使用规则：

1. 一屏内 `Filled` 最多 1 个（主行动）；同组按钮降级顺序 Filled → Outlined → Text。
2. 详情页：`播放` = Filled（琥珀）；`加入收藏` / `下载到本机` = Outlined；`更多` = Icon。
3. 面板确认（字幕"应用并继续播放"、排版面板）= Filled；面板内其他动作 = Text。
4. **播放器画面覆盖层**为唯一特例：主播放键用 `OnSurface` 填充 + `InverseOnSurface` 图标（避免媒体色与画面内容互抢）；次级键用玻璃底（黑 60% + blur 14dp）+ `OnSurface` 图标。媒体色在此层只用于进度与当前章节。
5. 禁止：按钮右侧色块、按钮内叠加媒体色与中性色的双色装饰、渐变底。

```kotlin
// 示例签名
@Composable fun CinefinButton(
    text: String, onClick: () -> Unit,
    variant: ButtonVariant = ButtonVariant.Filled,   // Filled / Outlined / Text / Icon
    size: ButtonSize = ButtonSize.Large, enabled: Boolean = true,
)
```

### 8.2 分段控件 SegmentedControl

| 项 | 规格 |
|----|------|
| 容器 | 高 44dp；底 `SurfaceContainerHigh`；1dp `Outline`；圆角 12dp |
| 段 | 等宽；文字 LabelMedium 15sp/500；分隔 1dp `Outline`（仅相邻未选中段之间） |
| 选中段 | 底 `Media.Container`；文字 `Media.Bright` 600；内 1dp `Media.Outline`；圆角 10dp（容器 12 − 内缩 2） |
| 未选中段 | 透明底；文字 `OnSurfaceVariant` |
| Pressed | 底 `Media.ContainerPressed`（选中段）/ 白 8%（未选中段） |
| Focused | 2dp 外环 `Media.Base@60%` |
| Disabled | 文字 38%；容器描边不变 |

### 8.3 筛选 Chip FilterChip

| 项 | 规格 |
|----|------|
| 尺寸 | 高 40dp（标准）/ 32dp（紧凑）；左右内边距 20/16dp；圆角 12dp；文字 LabelMedium 15sp |
| 未选中 | 底 `SurfaceContainer`；1dp `Outline`；文字 `OnSurfaceVariant` |
| 选中 | 底 `Media.Container`；1dp `Media.Outline`；文字 `Media.Bright` 600 |
| Pressed | 底 `Media.ContainerPressed`；描边 `Media.Base` |
| Focused | 2dp 外环 |
| Disabled | 全部 38% |
| 图标（可选） | 前置 16dp 图标，选中态用 `Media.Bright`；**禁止任何"色点 / 小色块"** |

首页 / 设置例外页：三域 chip 可同时可见；同一时刻只允许 1 个 chip 处于选中态；"全部"选中时用 `OnSurface` 填充 + `InverseOnSurface` 文字（中性）。

### 8.4 卡片 Card

| 类型 | 尺寸 / 结构 | 圆角 | 状态 |
|------|-------------|------|------|
| 海报卡 PosterCard | 2:3；图 + 标题（TitleSmall 15sp）+ 元信息（BodySmall 13sp） | 16dp | default：1dp `Outline` + 顶部内高光；hover/focus：描边 `Media.Outline`；pressed：底 `Media.Container` 叠加图 92% 不透明度；selected：1dp `Media.Outline` + 左上角"已选"填充圆点（Icon 变体，不算装饰色块） |
| 横版卡 WideCard | 16:9；底部 96dp 渐隐遮罩；标题 19sp/600；进度条 3dp | 16dp | 进度/hover 同海报卡；"继续观看"CTA 为 Filled Small |
| 列表行 ListRow | 高 88dp（双行）/72dp（单行）；缩略图 46–56dp（圆角 8dp）；间距 16dp | 0（行）/16dp（独立卡） | 当前行：底 `Media.Container` 横向渐变（100% → 0%）；序号文字 `Media.Bright`；分隔 1dp `OutlineVariant` |

卡片禁止：投影、整卡媒体色底、图片上叠加媒体色渐变。

### 8.5 列表 List

| 项 | 规格 |
|----|------|
| 行高 | 单行 56dp；双行 72dp；三行 88dp |
| 分隔 | 1dp `OutlineVariant`；最后一行不画 |
| 表格式信息行 | 高 44dp；key = BodySmall 13sp `OnSurfaceFaint`；value = BodySmall 13sp `OnSurfaceVariant`；1dp `OutlineVariant` |
| 分组头 | 高 48dp；sticky；底 `Surface`；文字 LabelSmall 13sp `OnSurfaceFaint` |
| 多选 | 左侧 20dp 勾选圆角 8：选中 = `Media.Base` 填充 + `OnBase` 勾 |
| 当前播放集 | 整行 `Media.Container` 横向渐变 + 序号 `Media.Bright`（与卡片同一规则，不引入色块） |
| 滚动 | 内容上下内边距 24dp；滚动条 3dp `Outline`，仅滚动时可见 |

### 8.6 导航

| 类型 | 规格 | 选中态 |
|------|------|--------|
| 侧导航 SideRail（≥1200dp） | 宽 164dp；底 `#12171D`；右 1dp `Outline`；logo 38dp；item 高 54dp、圆角 14dp、间距 2dp、内边距 14dp；图标 22dp + 文字 16sp | 首页：底 `SurfaceContainerHigh` + 文字 `OnSurface`；域页：底 `Media.Container` + 图标/文字 `Media.Bright`；**删除原稿右侧色点** |
| 折叠轨 Rail（840–1199dp） | 宽 88dp；仅图标 24dp 居中；item 高 56dp | 同上（无文字） |
| 底部 Tab（<600dp） | 高 64dp + 安全区；4 tab：影 / 乐 / 书 / 更多；图标 24dp + 文字 13sp；间距 4dp | 图标 fill + `Media.Bright` + 文字 `Media.Bright`；指示条 24×3dp、圆角 4dp、`Media.Base`，位于图标上方 4dp（该指示条是导航指示器，不属于"按钮旁色块"） |
| 抽屉 Drawer | 宽 320dp；底 `SurfaceContainer`；header 96dp；item 高 56dp、圆角 12dp；分组标题 LabelSmall `OnSurfaceFaint` | 同侧导航 |

### 8.7 播放器控件

| 控件 | 规格 |
|------|------|
| 主播放键 | 70dp、圆角 22dp；底 `OnSurface`、图标 `InverseOnSurface`；pressed 白 12% 状态层 |
| 次级传输键（±15s 等） | 44dp；透明底；图标 `OnSurface`；hover 白 8% |
| 覆盖层工具键 | 50dp、圆角 16dp；底黑 60% + blur 14dp；1dp 白 12%；图标 `OnSurface` |
| 进度条（画面层） | 高 6dp；轨道白 18%；填充 `Media.Base`；knob 20dp、圆角 5dp、白；章节刻度 2×14dp 白 50%；buffered 白 24% |
| 进度条（面板内） | 高 5dp；轨道白 12%；填充 `Media.Base`；knob 16dp、圆角 4dp |
| 右侧面板 | 宽 560dp；底 `#161B23`；左 1dp `Outline`；内边距 38/34dp；组标题 LabelSmall 13sp、字距 0.14em、`OnSurfaceFaint` |
| 选项行 Option | 高 52dp；圆角 12dp；1dp `Outline`；底 `#1A2029`；选中：底 `Media.Container` + 描边 `Media.Outline` + 指示图标 `Media.Base`；pressed：`Media.ContainerPressed` |
| 面板确认按钮 | Filled Medium（媒体色） |

### 8.8 进度条 ProgressBar

| 类型 | 规格 |
|------|------|
| 线性（卡片内） | 高 3dp；圆角 full；轨道白 16%；填充 `Media.Base` |
| 线性（页面） | 高 5dp；轨道白 12%；填充 `Media.Base`；knob 16dp 圆角 4dp 白 |
| 环形（下载 / 缓冲） | 直径 44dp；描边 3dp；轨道 `Outline`；填充 `Media.Base`；中心文字 MonoDataSmall |
| 不确定态 | 填充段 40% 宽循环平移，周期 1100ms linear；仅在必须表达"进行中"时使用 |

进度类组件统一使用当前域媒体色——进度表达"内容域 + 进行中"，不涉及 success / error 语义（§2.6 第 6 条）。

### 8.9 徽标 Badge

| 项 | 规格 |
|----|------|
| 尺寸 | 高 32dp；左右内边距 13dp；圆角 9dp；文字 LabelSmall 13sp/500 |
| 域徽标 | 1dp `Media.Outline`；文字 `Media.Bright`；底 `Media.Container@8%` 可省略；**删除原稿的 7dp 色点** |
| 中性徽标 | 1dp `Outline`；文字 `OnSurfaceVariant`；底 `rgba(255,255,255,.02)` |
| 状态徽标（已离线 / 已下载） | 中性 + 12dp 前置图标（`OnSurfaceVariant`），不用彩色 |

### 8.10 空状态 EmptyState

| 项 | 规格 |
|----|------|
| 布局 | 垂直居中；上下留白 64dp；内容最大宽 480dp |
| 图标 | 44dp 单色描边图标；颜色 `Media.Bright`（当前域） |
| 标题 | HeadlineSmall 24sp/600 `OnSurface` |
| 说明 | BodyMedium 15sp `OnSurfaceVariant`，≤2 行 |
| 主行动 | Filled；次行动 Text |
| 文案 | 说明"发生了什么 + 下一步"；不道歉、不卖萌（示例："这个媒体库还没有内容 · 在服务器添加后点刷新"） |

### 8.11 对话框 Dialog

| 项 | 规格 |
|----|------|
| 尺寸 | 宽 480dp（平板）/ 左右 20dp（手机）；内边距 32/28dp |
| 表面 | 底 `SurfaceContainerHighest`；1dp `Outline`；圆角 28dp；无投影 |
| 标题 | HeadlineSmall 24sp/600 |
| 正文 | BodyMedium 15sp `OnSurfaceVariant` |
| 按钮 | 右对齐：Text（取消）+ Filled（确认）；破坏性操作 Filled 换 `Error` 底 |
| 遮罩 | `Scrim`；淡入 200ms Standard |

### 8.12 Toast / Snackbar

| 项 | 规格 |
|----|------|
| 表面 | 底 `InverseSurface #F4F1EA`；文字 `InverseOnSurface #14181F`；圆角 12dp；无投影 |
| 尺寸 | 高 ≥52dp；左右内边距 20dp；最长 2 行；宽 ≤ 480dp（平板）/ 全宽−40dp（手机） |
| 位置 | 底部居中；距底 96dp（有 tab 时再加 tab 高度） |
| 动效 | 淡入 + translateY 8dp→0，200ms Decelerate；展示 3200ms |
| 语义 | 主动语态结果 + 下一步（"已加入收藏 · 可在收藏页查看"）；错误 Toast 前置 16dp `Error` 图标 |

### 8.13 歌词组件 Lyrics

| 项 | 规格 |
|----|------|
| 行距 | 行间 34dp；语言切换 chip 距歌词 36dp |
| 原文行 | 17→21sp；非当前 opacity 40%；当前 32sp/600 奶白 |
| 译文行 | 17→24sp；当前 `Media.Bright`（音乐域 = 松石）；非当前 `OnSurfaceVariant` |
| 当前行标记 | 译文行前置 16×2dp `Media.Base` 短线（文本装饰，允许） |
| 语言 chip | FilterChip 规范；选中 = `Media.Container` + `Media.Bright`；选项：简体中文 / 原文 / 双语对照 / 日本語 |
| 滚动 | 当前行固定于可视区 38% 高度；切行滚动 300ms Emphasized；长按可暂停跟随 |

### 8.14 阅读器排版面板 ReaderPanel

| 项 | 规格 |
|----|------|
| 面板 | 宽 512dp；圆角 22dp；内边距 30/28dp；深色主题底 `#191F28`；纸色主题底 `#FBF6EC` |
| 行项 | 标签 BodyMedium 15sp + 滑块（宽 230dp）；行距 20dp |
| 滑块 | 轨道 5dp；knob 14dp 圆角 4dp 白；填充：深色主题 `Media.Base`（阅读域 = 天青）、纸色主题 `#A8843C`（纸页棕，见下） |
| 主题缩略图 | 5 个 78dp 圆角 14dp（纸色 / 护眼 / 深色 / OLED / 跟随）；选中 = 2dp 描边 + 1dp 间隙（不用外发光） |
| 字体 / 对齐 / 行距 / 边距 | 与主题行同规格；选项用 chip 行 |
| 纸色主题例外 | 纸色 / 护眼主题内，阅读域天青替换为**纸页棕 `#A8843C`**（C · Nocturne 皮肤语言）；该替换只发生在阅读器纸色主题内部，深色 / OLED 主题仍用天青 |

---

## 9. Compose 落地映射

### 9.1 文件结构

```
core/src/main/kotlin/.../design/
├── Token.kt        # 原始色值 / 尺寸常量（唯一允许写 Color(0x…) 的文件）
├── MediaColors.kt  # MediaFilm / MediaMusic / MediaBook + 合成函数
├── Color.kt        # CinefinColors + M3 ColorScheme 映射
├── Type.kt         # CinefinTypography + 扩展 TextStyle
├── Shape.kt        # CinefinShapes（28/22/16/12/8）
├── Motion.kt       # CinefinMotion（时长 + 曲线）
└── Theme.kt        # CinefinTheme(domain) + LocalMediaColors
```

### 9.2 主题与域切换

```kotlin
enum class ContentDomain { Movie, Music, Book, Neutral }

val LocalMediaColors = staticCompositionLocalOf { MediaFilm }

@Composable
fun CinefinTheme(domain: ContentDomain, content: @Composable () -> Unit) {
    val media = when (domain) {
        ContentDomain.Movie -> MediaFilm
        ContentDomain.Music -> MediaMusic
        ContentDomain.Book  -> MediaBook
        ContentDomain.Neutral -> MediaFilm   // 首页/设置由页面显式取三色
    }
    CompositionLocalProvider(LocalMediaColors provides media) {
        MaterialTheme(
            colorScheme = cinefinColorScheme(media),
            typography = CinefinTypography,
            shapes = CinefinShapes,
            content = content,
        )
    }
}

object CinefinShapes {
    val Xl = RoundedCornerShape(28.dp); val Lg = RoundedCornerShape(22.dp)
    val Md = RoundedCornerShape(16.dp); val Sm = RoundedCornerShape(12.dp)
    val Xs = RoundedCornerShape(8.dp)
}

object CinefinMotion {
    const val Fast = 200; const val Player = 280; const val Page = 420
    const val Enter = 560; const val Stagger = 40; const val Reader = 220
    val Emphasized = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val Standard   = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
}
```

### 9.3 组件清单（Compose）

| 组件 | 名称 | 关键点 |
|------|------|--------|
| 按钮 | `CinefinButton(variant, size)` | variant = Filled / Outlined / Text / Icon；内部做状态层，不做独立色块 |
| 分段 | `CinefinSegmentedControl(items, selected)` | 选中段 `Media.Container` |
| 筛选 | `CinefinFilterChip(selected)` | 无前置色点；选中 `Media.Container` |
| 卡片 | `PosterCard` / `WideCard` / `ListRow` | 1dp 描边 + 内高光；无 elevation |
| 徽标 | `MediaBadge(text)` / `NeutralBadge(text)` | 无颜色圆点 |
| 空状态 | `CinefinEmptyState(icon, title, message, action)` | 图标 `Media.Bright` |
| 对话框 | `CinefinDialog` | 28dp 圆角 + scrim |
| Toast | `CinefinToastHost` | inverse 色 + 3200ms |
| 歌词 | `LyricsPanel(lines, language)` | 当前行 38% 定位 |
| 阅读面板 | `ReaderSettingsPanel(settings)` | 纸色主题换 `#A8843C` |

### 9.4 硬编码禁令（评审门禁）

1. 除 `Token.kt` / 视觉稿外，任何文件禁止出现 `Color(0x…)` 字面量；一律引用 token。
2. 禁止 `Modifier.shadow(...)` / `elevation > 0.dp`（§5.3 例外清单外）。
3. 禁止新增媒体色；跨域取色（在影视页引用 `MediaMusic`）需设计系统评审。
4. 组件默认从 `LocalMediaColors.current` 取色，禁止页面手工传 4 套色值。
5. PR 描述需附：受影响 token 列表 + 本页媒体色（1 个）声明。

---

## 10. 验收清单

发布前逐项核对（对齐 REQUIREMENTS §12 的 UI 门禁）：

- [ ] 全量 token 落地，无旧"墨 + 朱砂"配色残留；业务层无 `Color(0x…)`。
- [ ] 同屏仅 1 个媒体色；首页 / 设置页例外且单项不混色。
- [ ] 媒体色全部融入控件（按钮 / chip / 分段 / 徽标 / 导航选中），全项目搜索无"按钮 + 独立色块"结构。
- [ ] 按钮四种变形（Filled / Outlined / Text / Icon）× 五状态（default / hover / pressed / focused / disabled）颜色与本文一致。
- [ ] 无投影：全局 `elevation = 0dp`；仅画面覆盖层使用 blur。
- [ ] 手机（K60）与平板（Pad 5）双形态走查：栅格、边距、tab / 侧导航行为正确；折叠展开态侧导航 88↔164 正常。
- [ ] 深色主题 + 阅读器 5 主题（纸色 / 护眼 / 深色 / OLED / 跟随）实际渲染通过；纸色主题使用 `#A8843C` 纸页棕。
- [ ] 动效：入场 560ms / 错峰 40ms / 面板 420ms；只动 transform / opacity / color；减少动画开关生效。
- [ ] 对比度：正文 ≥4.5:1、大字 ≥3:1（§2.2 表）；截图抽检。
- [ ] App 图标（三棱镜光栅）多尺寸（48 / 72 / 96 / 144 / 192 / 512 + 自适应 + 单色主题）全部产出并与本文规则一致。

---

## 11. 关联文件

| 文件 | 说明 |
|------|------|
| `docs/design/s1-decision.md` | S1 方向决策（B + A + C；媒体色融入按钮的修订来源） |
| `docs/design/s1-compare.md` | 三方向对比 |
| `docs/design/s1-direction-{a,b,c}/README.md` | 三方向规范细节 |
| `docs/design/_src/direction-s4.html` | 本设计系统配套修订稿渲染源（4 屏 + 3 状态板） |
| `docs/design/s4-revision/` | 修订稿 PNG 交付（含组件状态板） |
| `docs/design/s4-README.md` | S4 交付说明与复现命令 |
| `docs/REQUIREMENTS.md` | 需求基线（§6 UI / §9 平台 / §10 语言） |
