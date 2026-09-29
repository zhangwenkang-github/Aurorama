# Cinefin 角色 Skill 清单（ROLE_SKILLS）

| 项 | 值 |
|----|----|
| 版本 | v1.0（2026-09-29） |
| 状态 | S1/S2/S3 已完成调查；R1–R4 开发角色必学 skill 完整版已补齐（本文 §5，每条附来源 URL 与置信度） |
| 维护规则 | 每个角色会话启动前，项目负责人必须完成该角色的 skill 调查并更新本文件；<br>会话启动指令中必须包含"开始前必须学习的 skill"；能力缺口出现时动态补充并通知相关会话 |

---

## 1. 角色总览（并行开发规划）

| 角色 | 会话 | 主要职责 | Skill 调查状态 |
|------|------|---------|---------------|
| UI/UX 设计 | S1 | 设计方向、视觉稿、设计系统 | ✅ 已完成（本文 §2） |
| 架构与规范 | S2 | 架构文档、开发规范、Git 工作流 | ✅ 已完成（本文 §3） |
| 计划与 Skill 维护 | S3 | 路线图、排期、Skill 清单完整版 | ✅ 已完成（本文 §4） |
| 阅读器开发 | R1 | EPUB/PDF/CBZ 阅读器 | ✅ 已完成（本文 §5.1） |
| 音乐开发 | R2 | 播放/队列/歌词/离线 | ✅ 已完成（本文 §5.2） |
| UI 落地开发 | R3 | 设计系统实现与页面改造 | ✅ 已完成（本文 §5.3） |
| 测试与验收 | R4 | 真机矩阵、回归、验收 | ✅ 已完成（本文 §5.4） |
| 项目负责人 | 根会话 | 协调、审查、合并、对外沟通 | 持续 |

---

## 2. S1 · UI/UX 设计（已调查）

### 2.1 必学 skill（本地技能库，按顺序）

| # | Skill | 路径 | 作用 |
|---|-------|------|------|
| 1 | high-end-visual-design | `C:\Users\zhangwenkang\.agents\skills\high-end-visual-design\SKILL.md` | 高端视觉标准：字体、间距、阴影、卡片结构、动效；屏蔽"廉价感"默认套路 |
| 2 | imagegen | `C:\Users\zhangwenkang\.codex\skills\.system\imagegen\SKILL.md` | 图片生成/编辑的正确调用方式与降级策略 |
| 3 | imagegen-frontend-mobile | `C:\Users\zhangwenkang\.agents\skills\imagegen-frontend-mobile\SKILL.md` | 移动端 App 视觉稿规范：层级、可读性、多屏一致性、色彩控制 |
| 4 | frontend-design | `C:\Users\zhangwenkang\.codex\skills\frontend-design\SKILL.md` | 差异化设计方法：避免模板化、基于题材做有主见的选择 |

### 2.2 补充学习（联网，尽力而为）

- Apple tvOS Human Interface Guidelines（大屏焦点、沉浸感、内容优先）——
  ⚠️ 2026-09-29 实测本机直连 `developer.apple.com` 抓取失败，会话内需尝试镜像/替代来源并记录实际可用来源；
- Material 3 Expressive（Android 现代设计语言，含动效规范）。

### 2.3 调查依据

- 本地技能库（2026-09-29 核验四个 SKILL.md 存在且适用）；
- `.planning/cinefin-expansion/research/B-market-reference.md`（阅读器/音乐播放器产品与 UI 调查）；
- `docs/REQUIREMENTS.md` §6（用户明确：推翻"墨+朱砂"、参考 Apple TV、图标全新、深色为主）。

### 2.4 上下文纪律（S1 特别要求）

- 视觉稿一律**文件交付**，不在会话里贴大图/base64；
- 参考截图看完即止；
- 工具输出裁剪，分批生成图片。

---

## 3. S2 · 架构与规范（✅ 2026-09-29 已调查）

### 3.1 必学 skill（按顺序）

| # | 来源 | 路径 / URL | 要点 |
|---|------|-----------|------|
| 1 | 本仓库现状 | `settings.gradle.kts`、各模块 `build.gradle.kts` | 模块依赖图：app:phone → {core, data, player:core, player:local, setup, modes:film, settings}；core → {data, player:core, settings} |
| 2 | Android 架构建议 | `developer.android.com/topic/architecture/recommendations`（经代理可达） | 分层、UDF、ViewModel、数据层边界 |
| 3 | Readium Kotlin Toolkit | `readium.org/kotlin-toolkit` + GitHub README（能力矩阵已采集） | EPUB2/3 ✅；PDF ✅（搜索/高亮/TTS 为 👀 未实现）；CBZ 🚧 部分；**CBR ❌ 不支持**；分页/滚动/RTL ✅ |
| 4 | Jellyfin SDK / OpenAPI | `api.jellyfin.org/openapi/jellyfin-openapi-stable.json`、`org.jellyfin.sdk 1.8.12` | 写白名单：`/UserItems/{id}/UserData`、收藏、播放上报、播放列表；`/Audio/{id}/Lyrics` |
| 5 | Media3 | `developer.android.com/media/media3`（经代理） | 音频 gapless、MediaSession、后台播放 |
| 6 | Google 工程实践 | `google.github.io/eng-practices/review/` | 代码审查标准（已采集目录：标准 / 关注点 / 评论写法） |
| 7 | 本仓库工程规范 | ktfmt（kotlinLangStyle）、`.github/workflows/`、约定式提交历史 | CI 现状：Build（仅 master + docs 忽略 + concurrency）、Format（ktfmtCheck） |

### 3.2 调查记录

- 2026-09-29：经代理（30001）抓取 Android 架构建议页、Readium README 能力矩阵、Google eng-practices 目录页；
  其余来源列入 S2 会话学习清单（会话内自行深入）。

## 4. S3 · 计划与 Skill 维护（✅ 2026-09-29 已调查）

### 4.1 必学 skill（按顺序）

| # | 来源 | 路径 / URL | 要点 |
|---|------|-----------|------|
| 1 | planning-with-files-zh | `C:\Users\zhangwenkang\.codex\skills\planning-with-files-zh\SKILL.md` | 磁盘持久化规划（task_plan/findings/progress）与阶段门禁 |
| 2 | 里程碑与依赖排序 | 结合 `docs/REQUIREMENTS.md` 优先级 + 模块依赖图 | 关键路径识别、接口先行、可并行任务切分 |
| 3 | 技能调研方法 | 官方文档 + 权威仓库取证；输出含来源与置信度 | 本文件维护规则（动态补充、通知会话） |
| 4 | 上下文预算管理 | 单会话 ≤880KB 文本 / ≤48MiB 含图；工具输出裁剪；交接文件 | 长任务防中断纪律 |

### 4.2 调查记录

- 2026-09-29：核验 planning-with-files-zh 技能文档；确定调研与排期方法；
  项目级依赖与优先级输入来自 `docs/REQUIREMENTS.md` §3/§8 与 `.planning` 调查报告。

## 5. 开发角色（R1–R4，v1.0 完整清单）

> **置信度定义**：**高** = 官方文档 / W3C 等公开标准 / 首方权威仓库，2026-09-29 经代理（30001）实测可达；
> **中** = 社区权威参考实现（许可已核验、可借鉴思路）；**内部** = 项目内文档约束（验收口径补充，非 skill 来源）。
> 每个角色**开工第一条消息必须逐条学习本节清单**，学习笔记（关键结论 + 待确认项）写入该会话的 findings 后再动手。

### 5.1 R1 · 阅读器开发（EPUB / PDF / CBZ）

| # | 来源 | URL | 学习要点 | 置信度 |
|---|------|-----|---------|--------|
| 1 | Readium Kotlin Toolkit 官网 | https://readium.org/kotlin-toolkit/ | 能力矩阵：EPUB2/3 ✅、PDF 需适配器、CBZ 🚧、分页/滚动/RTL、Decoration API 高亮、内建 TTS；导航器为 Fragment/View 体系（Compose 内用 `AndroidView` 承载） | 高 |
| 2 | Readium PDF 指南（官方仓库） | https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/pdf.md | PDF 适配器启用方式（pdfium / 商业 pspdfkit）、PDF 搜索/高亮能力边界（多数 👀 未实现） | 高 |
| 3 | androidx.pdf 发布说明（官方） | https://developer.android.com/jetpack/androidx/releases/pdf | PDF 备选路线的模块构成（pdf-viewer / pdf-compose / pdf-ink）与 beta 稳定性评估 | 高 |
| 4 | PdfRenderer API（官方） | https://developer.android.com/reference/android/graphics/pdf/PdfRenderer | 按页渲染 + `PdfRenderer.Page.close()` 回收，落地 EB-3「分页懒加载，禁止整文档载入」 | 高 |
| 5 | W3C EPUB 3.3 规范 | https://www.w3.org/TR/epub-33/ | EPUB 包结构（OPF / Navigation / CSS）、阅读顺序，用于排版设置与阅读进度定位的理解 | 高 |
| 6 | java.util.zip 流式解压（官方） | https://developer.android.com/reference/java/util/zip/ZipInputStream | CBZ 解压与逐页图片流式读取、内存上限策略（禁止一次性全解压） | 高 |
| 7 | Mihon 漫画阅读器（Apache-2.0） | https://github.com/mihonapp/mihon | 漫画交互参考：RTL / 双页 / 裁边 / 连续滚动；许可已核验可借鉴思路与代码（保留声明） | 中 |

**学习产出（R1 会话 findings 必写）**：① Readium 集成方案（模块、`AndroidView` 承载方式、desugaring 要求）；
② PDF 路线决策建议（Readium pdfium 适配器 vs androidx.pdf vs PdfRenderer 自研，对照 EB-3）；
③ CBZ 支持策略与内存预算；④ 阅读进度写回 `/UserItems/{itemId}/UserData` 的接口契约（对接 S2 接口冻结）。
**内部依据**：`docs/REQUIREMENTS.md` §4（EB-1–EB-12）、§8；`.planning/.../research/C-tech-options.md` §1、§4（许可）。

### 5.2 R2 · 音乐开发（播放 / 队列 / 歌词 / 离线）

| # | 来源 | URL | 学习要点 | 置信度 |
|---|------|-----|---------|--------|
| 1 | Media3 概览（官方） | https://developer.android.com/media/media3 | 模块结构（exoplayer / session / ui）；本项目已有 Media3 1.11.1 + FFmpeg 解码器，音乐复用 `player:core` 抽象 | 高 |
| 2 | Media3 Playlists（官方） | https://developer.android.com/media/media3/exoplayer/playlists | 播放队列 API、gapless 语义与 `isGaplessSupported` 能力查询（MU-3 / MU-4） | 高 |
| 3 | Media3 后台播放（官方） | https://developer.android.com/media/media3/session/background-playback | `MediaSessionService`、通知 / 锁屏 / 音频焦点 / 后台长驻（MU-7） | 高 |
| 4 | Media3 Session API 参考（官方） | https://developer.android.com/reference/androidx/media3/session/package-summary | `MediaSession` / `MediaController` / `MediaLibraryService`（Android Auto 后置的预留接口） | 高 |
| 5 | Media3 官方仓库（RELEASENOTES） | https://github.com/androidx/media | gapless / ReplayGain / 变速等第一手发布记录（C-tech-options §2.2 的证据源） | 高 |
| 6 | Jellyfin OpenAPI（stable） | https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json | 音乐库查询、`/Audio/{itemId}/Lyrics` + `LyricDto`（逐行 + cue 时间）、`Sessions/Playing*` 上报（MU-5 / MU-9） | 高 |
| 7 | jellyfin-web（官方功能基线） | https://github.com/jellyfin/jellyfin-web | 官方音乐 UI 的信息架构与歌词组件（lyricseditor / lyricsuploader），parity 对照基线 | 高 |

**学习产出（R2 会话 findings 必写）**：① 单 MediaSession 方案（与视频互斥，MU-8 对 `player:core` / `player:local` 的接口改动）；
② 队列模型设计（单队列 + 手动排序 + 下一首播放 + 队列保存）；③ 歌词双语配对与逐行语言识别算法（对齐 MU-5 实测结构）；
④ 离线下载与容量管理方案（复用现有 Downloader / Room 离线仓库）。
**内部依据**：`docs/REQUIREMENTS.md` §5（MU-1–MU-9 + §5.1 歌词细则）、§11 写白名单；C-tech-options §2–§3。

### 5.3 R3 · UI 落地开发（设计系统实现与页面改造）

| # | 来源 | URL | 学习要点 | 置信度 |
|---|------|-----|---------|--------|
| 1 | Compose Material 3（官方） | https://developer.android.com/develop/ui/compose/designsystems/material3 | 主题 / 色彩 / 排版 token 的 Compose 落地方式（对接 S1 设计系统） | 高 |
| 2 | Material 3 规范 | https://m3.material.io/ | 组件形态、状态层、动效与无障碍基准（避免自造轮子） | 高 |
| 3 | 自适应布局（官方） | https://developer.android.com/develop/ui/compose/layouts/adaptive | `WindowSizeClass`、列表-详情双栏（平板优先，UI-6） | 高 |
| 4 | 互操作 API（官方） | https://developer.android.com/develop/ui/compose/migrate/interoperability-apis | `AndroidView` / `ComposeView`：承载 Readium 导航器、Media3 `PlayerView`、mpv Surface | 高 |
| 5 | Compose 动画（官方） | https://developer.android.com/develop/ui/compose/animation/introduction | 内容过渡为主的克制动效（UI-7），含 `AnimatedVisibility` / 转场 | 高 |
| 6 | Compose 性能（官方） | https://developer.android.com/develop/ui/compose/performance | 稳定性（stable 类型）、重组优化、列表懒加载（性能红线对齐） | 高 |

**补充（本地技能库，配合 S1）**：`high-end-visual-design`（`C:\Users\zhangwenkang\.agents\skills\high-end-visual-design\SKILL.md`）、
`frontend-design`（`C:\Users\zhangwenkang\.codex\skills\frontend-design\SKILL.md`）——
仅在需要重新判断视觉细节时学习；**设计 token 的权威定义以 S1 交付的 `UI_DESIGN_SYSTEM.md` 为准，不自行创造新配色 / 字体**。
**学习产出（R3 会话 findings 必写）**：① 设计 token → Compose 主题映射表；② 组件清单与页面改造顺序；
③ `AndroidView` 承载三方 View 的边界与性能验证方法。
**内部依据**：`docs/design/s1-decision.md`（B+A+C 组合与按钮媒体色规则）、REQUIREMENTS §6（UI-1–UI-8）。

### 5.4 R4 · 测试与验收（真机矩阵 / 回归 / 性能）

| # | 来源 | URL | 学习要点 | 置信度 |
|---|------|-----|---------|--------|
| 1 | adb（官方） | https://developer.android.com/tools/adb | 设备连接、安装 APK、`dumpsys` / `am` / 截图；本项目文本优先的验证纪律 | 高 |
| 2 | UI Automator（官方） | https://developer.android.com/training/testing/other-components/ui-automator | `uiautomator dump` 与跨应用 UI 断言（播放器 / 阅读器 / 音乐界面走查） | 高 |
| 3 | Espresso（官方） | https://developer.android.com/training/testing/espresso | 视图层 UI 测试（与 Compose 测试的分工） | 高 |
| 4 | Compose 测试（官方） | https://developer.android.com/develop/ui/compose/testing | `ComposeTestRule` / 语义树断言，新增 UI 的回归测试 | 高 |
| 5 | Macrobenchmark（官方） | https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview | 启动时间 / 滚动性能基线测量（性能红线「不劣化」） | 高 |
| 6 | Logcat（官方） | https://developer.android.com/studio/command-line/logcat | 过滤日志、崩溃与异常定位（`logcat` + 关键字） | 高 |
| 7 | 系统跟踪 / Perfetto（官方） | https://developer.android.com/topic/performance/tracing | 卡顿与 IO 追踪，阅读器 / 播放器性能问题定位 | 高 |

**内部依据（验收口径，必读）**：`docs/REQUIREMENTS.md` §9（设备矩阵：Pad 5 / K60）、§11（写白名单：只读 + 进度 / 收藏 / 播放列表）、
§12（通用门禁 + 功能验收 + 性能红线）；`docs/PLAYER_PLAN.md` §6、§9（播放器线验收与踩坑）；
`docs/DEV_ENVIRONMENT.md`（构建 / 设备 / 会话约定）。
**学习产出（R4 会话 findings 必写）**：① 真机矩阵与每波回归清单；② 性能基线测量方法（对比基准与阈值）；
③ 测试数据准备清单（EPUB / PDF / CBZ、歌词样例、账号与权限边界）。

---

## 6. 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v0.2 | 2026-09-29 | S1/S2/S3 调查完成；R1–R4 方向稿 |
| v1.0 | 2026-09-29 | 补齐 R1–R4 完整必学 skill 清单（来源 URL + 置信度 + 学习产出要求） |

> 每个角色启动前：项目负责人复核本节清单 → 将"必学 skill"写入会话启动指令第一条消息（模板见 `docs/SESSION_BRIEFS.md`）。
