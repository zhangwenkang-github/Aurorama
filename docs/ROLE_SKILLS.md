# Cinefin 角色 Skill 清单（ROLE_SKILLS）

| 项 | 值 |
|----|----|
| 版本 | v0.2（2026-09-29） |
| 状态 | S1/S2/S3 已完联网调查；开发角色（R1–R4）待相应会话启动前完善 |
| 维护规则 | 每个角色会话启动前，项目负责人必须完成该角色的 skill 调查并更新本文件；<br>会话启动指令中必须包含"开始前必须学习的 skill"；能力缺口出现时动态补充并通知相关会话 |

---

## 1. 角色总览（并行开发规划）

| 角色 | 会话 | 主要职责 | Skill 调查状态 |
|------|------|---------|---------------|
| UI/UX 设计 | S1 | 设计方向、视觉稿、设计系统 | ✅ 已完成（本文 §2） |
| 架构与规范 | S2 | 架构文档、开发规范、Git 工作流 | ✅ 已完成（本文 §3） |
| 计划与 Skill 维护 | S3 | 路线图、排期、Skill 清单完整版 | ✅ 已完成（本文 §4） |
| 阅读器开发 | R1 | EPUB/PDF/CBZ 阅读器 | 🟡 方向（本文 §5，启动前完善） |
| 音乐开发 | R2 | 播放/队列/歌词/离线 | 🟡 方向（本文 §5，启动前完善） |
| UI 落地开发 | R3 | 设计系统实现与页面改造 | 🟡 方向（本文 §5，启动前完善） |
| 测试与验收 | R4 | 真机矩阵、回归、验收 | 🟡 方向（本文 §5，启动前完善） |
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

## 5. 开发角色（R1–R4，方向稿，启动前必须完成调查）

| 角色 | 初步 skill 方向 |
|------|----------------|
| R1 阅读器 | Readium Kotlin Toolkit 文档；EPUB3 渲染与 CSS 注入；PDF 分页懒加载（PdfRenderer/androidx.pdf 对比）；CBZ 解压与图片内存策略；Jellyfin `/UserItems/{id}/UserData` 进度 |
| R2 音乐 | Media3 音频（gapless、MediaSession、音频焦点）；Jellyfin 音频流/转码与 `/Audio/{id}/Lyrics`；LRC 解析与逐行语言检测；离线下载与缓存管理 |
| R3 UI 落地 | Compose 主题与设计 token；AndroidView 互操作（Readium）；自适应布局（WindowSizeClass/折叠）；动效 API |
| R4 测试 | adb 工具链（dumpsys/uiautomator/logcat）；真机矩阵（Pad 5 / K60）；Jellyfin 测试数据准备（只读 + 写白名单）；性能基线测量 |

> 每个角色启动前：项目负责人完成该角色的联网 skill 调查 → 更新本文档 → 将"必学 skill"写入会话启动指令第一条消息。
