# Cinefin 角色 Skill 清单（ROLE_SKILLS）

| 项 | 值 |
|----|----|
| 版本 | v0.1（2026-09-29） |
| 状态 | S1 设计角色已完成联网调查；S2/S3 与开发角色待相应会话启动前完善 |
| 维护规则 | 每个角色会话启动前，项目负责人必须完成该角色的 skill 调查并更新本文件；<br>会话启动指令中必须包含"开始前必须学习的 skill"；能力缺口出现时动态补充并通知相关会话 |

---

## 1. 角色总览（并行开发规划）

| 角色 | 会话 | 主要职责 | Skill 调查状态 |
|------|------|---------|---------------|
| UI/UX 设计 | S1 | 设计方向、视觉稿、设计系统 | ✅ 已完成（本文 §2） |
| 架构与规范 | S2 | 架构文档、开发规范、Git 工作流 | 🟡 初版（本文 §3，启动前完善） |
| 计划与 Skill 维护 | S3 | 路线图、排期、Skill 清单完整版 | 🟡 初版（本文 §4） |
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

## 3. S2 · 架构与规范（初版，启动前完善）

初步 skill 方向（启动前将联网核实并补充）：

- 现有代码库研究：模块依赖图、Compose UDF/MVI 现状、Hilt DI、Room3、Media3 集成；
- Jellyfin SDK 1.8.12 与 OpenAPI（写白名单接口）；
- Readium Kotlin Toolkit 3.4.0 集成约束（View/Fragment 体系 + AndroidView 承载）与许可（BSD-3）；
- 工程规范：ktfmt（kotlinLangStyle）、GitHub Actions（build/format）、Conventional Commits；
- Git 工作流：worktree、feature 分支、PR、咽喉文件串行保护。

## 4. S3 · 计划与 Skill 维护（初版）

- 技术项目管理：里程碑拆分、依赖排序、并行排期；
- 技能调研方法：官方文档 / 权威仓库 / 行业最佳实践取证；
- 《角色 Skill 清单》动态维护机制。

## 5. 开发角色（R1–R4，方向稿，启动前必须完成调查）

| 角色 | 初步 skill 方向 |
|------|----------------|
| R1 阅读器 | Readium Kotlin Toolkit 文档；EPUB3 渲染与 CSS 注入；PDF 分页懒加载（PdfRenderer/androidx.pdf 对比）；CBZ 解压与图片内存策略；Jellyfin `/UserItems/{id}/UserData` 进度 |
| R2 音乐 | Media3 音频（gapless、MediaSession、音频焦点）；Jellyfin 音频流/转码与 `/Audio/{id}/Lyrics`；LRC 解析与逐行语言检测；离线下载与缓存管理 |
| R3 UI 落地 | Compose 主题与设计 token；AndroidView 互操作（Readium）；自适应布局（WindowSizeClass/折叠）；动效 API |
| R4 测试 | adb 工具链（dumpsys/uiautomator/logcat）；真机矩阵（Pad 5 / K60）；Jellyfin 测试数据准备（只读 + 写白名单）；性能基线测量 |

> 每个角色启动前：项目负责人完成该角色的联网 skill 调查 → 更新本文档 → 将"必学 skill"写入会话启动指令第一条消息。
