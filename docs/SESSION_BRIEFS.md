# Cinefin 会话启动简报（SESSION_BRIEFS）

| 项 | 值 |
|----|----|
| 版本 | v1.0（2026-09-29） |
| 用途 | 每个开发会话的启动指令模板：把对应卡片内容作为该会话**第一条消息**发出即可开工 |
| 上游文档 | `docs/ROADMAP.md`（阶段）、`docs/PARALLEL_PLAN.md`（波次 / 分支 / 并发）、`docs/ROLE_SKILLS.md`（必学 skill） |
| 前提 | 并发上限 4（含负责人）：同一波最多 3 个开发会话；上一波未合并前下一波不开工 |

---

## 1. 通用启动模板（复制后替换 `<>` 内容）

```text
你是 Cinefin <会话代号> 会话（<角色名>）。全程简体中文。本会话只做 <任务线>，不改与本任务无关的文件。

## 第一步（强制）：开始前必须学习的 skill（学完先汇报 ≤300 字，再开工）
1. 读 `docs/PROJECT_PLAN.md` §1–§5（项目总览 / 模块地图 / 协作规程）；
2. 读 `docs/REQUIREMENTS.md`（重点 <章节>）；
3. 读 `docs/SESSION_BRIEFS.md` 本会话卡片 + `docs/PARALLEL_PLAN.md` 对应波次；
4. 逐条学习 `docs/ROLE_SKILLS.md` <§x.y> 列出的 skill（含 URL 与置信度），把关键结论 / 待确认项记入会话笔记；
5. `git log --oneline -10` + `git status`，确认工作区状态。

## 任务
<1–2 个"一屏能验证完"的任务，来自 SESSION_BRIEFS 卡片>

## 交付物
<代码 + 单测 + 文档更新 + 真机验证记录>

## 分支
<feature/...>（独立 worktree；合并前 rebase 最新 master）

## 验收标准
<编译 + ktfmt + 单测 + 真机验收要点>

## 依赖
<前置波次 / 接口冻结 / 测试数据>

## 代理与上下文纪律
- 需要联网时读 `E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\proxy.md` 取代理配置：
  命令行工具用 30001（`curl.exe -x "https://<user>:<pass>@proxy.zhangwenkang.com:30001" "<URL>"`），**Gradle 不配代理**（直连）；
  凭据只允许在命令变量中使用，**禁止写入仓库 / 文档 / 提交信息 / 会话输出**。
- 工具输出必须裁剪（`-Last N` / `Select-String`）；不把截图 / 大图贴进对话；单会话上下文过半即交接。
- **真机纪律**：使用 `adb` 真机前先读并登记 `E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\device-lock.md`
  （会话名 / 设备 / 开始时间 / 预计时长），完成后清空；同一时刻只允许一个会话占用真机，超 45 分钟未释放视为过期可接管；
  同波次真机回归优先由 R4 统一执行。

## 收工（必须）
1. 验收通过后勾任务清单、把进度 / 决策 / 踩坑写回对应任务线文档（新线先建线文档）；
2. `git add` 本会话文件 → commit（`feat(线): …` / `fix(线): …` / `docs(线): …`）→ push 分支；
3. 向负责人报告：分支名、提交号、验收结论、未决问题；**不要合并 master**。
```

---

## 2. 各会话简报卡片

> 卡片字段齐全（skill / 任务 / 交付物 / 分支 / 验收 / 依赖 / 代理），发第一条消息时把卡片内容并入 §1 模板。

### W0 · 接口与设计冻结波

#### W0-S2 · 架构与规范（S2-ARCH）

| 项 | 内容 |
|----|------|
| 分支 | `feature/s2-architecture` |
| 必学 skill | `ROLE_SKILLS.md` §3（Android 架构建议、Readium 能力矩阵、Jellyfin OpenAPI、Media3、Google eng-practices、本仓库规范） |
| 必读文档 | REQUIREMENTS §1–§3/§11–§13、findings（模块与代码事实）、C-tech-options、PARALLEL_PLAN §1 |
| 任务 | ① 写 `ARCHITECTURE.md`：模块图 + 五条接口冻结清单（阅读 / 音乐 / 导航 / 设置 / 下载）；② 写 `DEV_STANDARDS.md`（ktfmt / 单测 / 审查 / 许可）与 `GIT_WORKFLOW.md`（分支 / PR / CI） |
| 交付物 | 三份文档 + CI 在 PR 上生效的确认记录 |
| 验收标准 | 接口冻结清单经负责人评审通过；R1 / R2 / R3 能按清单直接开工；PR 门禁（Build + Format）可触发 |
| 依赖 | 无（REQUIREMENTS v1.0 已确认） |
| 代理 | 需要查 Android 官方文档时用 30001；Gradle 直连 |

#### W0-S1 · 设计系统（S1-DS）

| 项 | 内容 |
|----|------|
| 分支 | `feature/s1-design-system` |
| 必学 skill | `ROLE_SKILLS.md` §2（high-end-visual-design / imagegen / imagegen-frontend-mobile / frontend-design） |
| 必读文档 | `docs/design/s1-decision.md`（B+A+C 组合 + 按钮媒体色规则）、REQUIREMENTS §6、三方向稿 README |
| 任务 | ① 写 `UI_DESIGN_SYSTEM.md`（token：颜色 / 字阶 / 间距 / 圆角 / 动效；按钮媒体色四种变形与状态；页面蓝图）；② 按新规则更新全套页面稿（文件交付） |
| 交付物 | 设计系统文档 + 全套页面稿文件（不出现在会话内联大图） |
| 验收标准 | token 可被 R3 直接映射到 Compose；按钮媒体色规则覆盖填充 / 描边 / 文本 / 图标四态；用户确认方向 |
| 依赖 | 无；S1 方向稿已完成 |
| 代理 | 查 Apple HIG / Material 3 资料时用 30001 |

#### W0-PLAYER · 播放器 bug 修复（PLAYER-BUG）

| 项 | 内容 |
|----|------|
| 分支 | `feature/player-autoselect-fix` |
| 必学 skill | `PLAYER_PLAN.md` §11 D 组线索 + §9 踩坑库；无需联网 skill |
| 必读文档 | PLAYER_PLAN §0（决策）/§1.1/§5.2/§9、REQUIREMENTS §3（保护性 P0） |
| 任务 | 修两个 bug：① 打开视频自动选字幕（`PlayerSubtitleController.pickPrimary()` 默认轨兜底 + mpv `slang` 语言匹配）；② 打开即播（`playWhenReady` 时序，避免 pause/resume 误置 false） |
| 交付物 | 两处修复 + 真机验证记录 |
| 验收标准 | 任意带字幕新片打开自动出字幕（语言优先级仍生效）；打开即播无需二次点击；不触碰 §11 A–C/E 面板改造 |
| 依赖 | 无（线索已定位，改动小） |
| 代理 | 不需要联网 |

### W1 · 三线骨架波

#### W1-R1 · 阅读器骨架（R1-SKELETON）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r1-reader-skeleton` |
| 必学 skill | `ROLE_SKILLS.md` §5.1 全表（Readium 官网 / PDF 指南 / androidx.pdf / PdfRenderer / EPUB 3.3 / ZipInputStream / Mihon） |
| 必读文档 | PROJECT_PLAN §1–§5、REQUIREMENTS §4/§11/§12、`ARCHITECTURE.md`（W0 产出）、C-tech-options §1 |
| 任务 | ① Readium 集成 PoC（`AndroidView` 承载导航器，打开 EPUB、翻页 / 滚动）；② 阅读数据模型 + `UserData` 进度接口实现 |
| 交付物 | PoC 代码 + 数据模型 + 单测 + 真机验证记录 + 学习笔记（集成方案 / PDF 路线建议 / CBZ 策略） |
| 验收标准 | 真机打开真实 EPUB 并翻页；进度读写成功；`:app:phone:compileLibreDebugKotlin` + `ktfmtCheck` 通过 |
| 依赖 | W0 接口冻结；`modes/book` 模块骨架已由负责人预建 |
| 代理 | 拉取 Readium Maven 依赖走 Gradle 直连（已确认可解析）；查文档用 30001 |

#### W1-R2 · 音乐骨架（R2-SKELETON）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r2-music-skeleton` |
| 必学 skill | `ROLE_SKILLS.md` §5.2 全表（Media3 概览 / Playlists / 后台播放 / Session API / 官方仓库 / Jellyfin OpenAPI / jellyfin-web） |
| 必读文档 | REQUIREMENTS §5/§11、`ARCHITECTURE.md`（单 MediaSession 接口）、C-tech-options §2–§3、findings（音频现状） |
| 任务 | ① `modes/music` 骨架 + 入口（抽屉 / 路由契约，不抢 `NavigationRoot.kt` 提交）；② 单 MediaSession 模型 + 列表→播放最小闭环 |
| 交付物 | 模块骨架 + 最小播放闭环 + 单测 + 真机验证记录 + 学习笔记（队列 / 歌词 / 离线方案） |
| 验收标准 | 音乐列表可播；通知 / 锁屏可控；与视频互斥（开始音乐停视频）；编译 + ktfmt 通过 |
| 依赖 | W0 接口冻结；`player:core` 会话模型冻结；`modes/music` 骨架已预建 |
| 代理 | 查 Media3 文档用 30001；Gradle 直连 |

#### W1-R3 · 设计 token 落地（R3-TOKENS）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r3-ui-tokens` |
| 必学 skill | `ROLE_SKILLS.md` §5.3 全表（Compose M3 / M3 规范 / 自适应布局 / 互操作 / 动画 / 性能） |
| 必读文档 | `UI_DESIGN_SYSTEM.md`（W0 产出）、s1-decision、REQUIREMENTS §6、findings（Typography 位置问题） |
| 任务 | ① 设计 token → Compose 主题映射（含亮 / 暗）；② 基础组件（按钮媒体色变形 / 卡片 / 导航 / 抽屉） |
| 交付物 | 主题代码 + 基础组件 + 组件预览 + 单测（token 一致性） |
| 验收标准 | 主题切换全 App 生效；组件无硬编码颜色；旧"墨+朱砂"配色在新组件中不复现 |
| 依赖 | W0 设计系统冻结；Typography 归属决策（S2） |
| 代理 | 查 Compose 文档用 30001 |

### W2 · 主体波 1

#### W2-R1 · 阅读器主体（R1-CORE）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r1-reader-core` |
| 必学 skill | `ROLE_SKILLS.md` §5.1（重点 Readium 官网 / EPUB 3.3） |
| 必读文档 | REQUIREMENTS EB-5/6/7、W1 阅读器 PoC 结论、续用 W1 分支的踩坑记录 |
| 任务 | 阅读模式（滚动 / 横向分页 / 平板双栏）、排版设置（字号 / 行距 / 边距 / 对齐 / 内置字体）、主题（纸色 / 护眼 / 深色 / OLED / 跟随系统） |
| 交付物 | 三项功能 + 设置持久化 + 真机验证记录 |
| 验收标准 | 设置即时生效并持久化；平板双栏正确；真机流畅无明显掉帧 |
| 依赖 | W1 R1 合并 |
| 代理 | 需要字体（OFL）资料时用 30001 |

#### W2-R2 · 音乐主体（R2-CORE）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r2-music-core` |
| 必学 skill | `ROLE_SKILLS.md` §5.2（重点 Playlists / 后台播放 / Session API） |
| 必读文档 | REQUIREMENTS MU-2/3/4/9、W1 音乐骨架结论 |
| 任务 | 曲库浏览（专辑 / 艺术家 / 歌曲 / 歌单 / 收藏 / 最近播放）、队列（单队列 + 手动排序 + 下一首播放 + 队列保存）、gapless、播放上报 |
| 交付物 | 四项功能 + 单测（队列逻辑）+ 真机验证记录 |
| 验收标准 | 专辑 / 歌单连播正确；gapless 听觉无缝隙；队列操作正确；进度上报成功 |
| 依赖 | W1 R2 合并 |
| 代理 | 查 Media3 文档用 30001 |

#### W2-R4 · 测试基建（R4-BASE）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r4-test-base` |
| 必学 skill | `ROLE_SKILLS.md` §5.4 全表（adb / UI Automator / Espresso / Compose 测试 / Macrobenchmark / Logcat / 系统跟踪） |
| 必读文档 | REQUIREMENTS §9/§11/§12、DEV_ENVIRONMENT、PLAYER_PLAN §6/§9 |
| 任务 | ① 真机矩阵 + 每波回归清单；② 性能基线测量方法（启动时间 / 内存 / 体积，对比基准与阈值）；③ 测试数据清单（EPUB / PDF / CBZ、歌词样例、账号权限边界） |
| 交付物 | 测试基建文档 + 基线数值 + 回归脚本 / 命令集 |
| 验收标准 | 基线数值可复现；测试数据清单提交用户确认；写白名单写入检查项 |
| 依赖 | Pad 5 / K60 设备在线；用户补充测试内容 |
| 代理 | 不需要（本地 adb / 设备） |

### W3 · 主体波 2

#### W3-R1 · 阅读进度与离线（R1-OFFLINE）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r1-reader-offline` |
| 必学 skill | `ROLE_SKILLS.md` §5.1（重点 PdfRenderer 之外的进度 / 离线部分：Readium 官网、ZipInputStream） |
| 必读文档 | REQUIREMENTS EB-8/EB-9/EB-11、ARCHITECTURE（进度 / 下载接口）、现有 Downloader / Room 离线仓库 |
| 任务 | ① 进度同步（离线暂存 + 联网回传 + 冲突策略）；② 离线阅读；③ 书签 / 高亮基础（本地数据库） |
| 交付物 | 三项功能 + 单测（冲突策略 / 序列化）+ 真机验证记录 |
| 验收标准 | 离线可读；退出重进恢复进度；联网回传成功；多设备冲突按最近时间戳处理 |
| 依赖 | W2 R1 合并；下载接口冻结 |
| 代理 | 不需要（本地 + 测试服务器只读） |

#### W3-R2 · 歌词（R2-LYRICS）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r2-music-lyrics` |
| 必学 skill | `ROLE_SKILLS.md` §5.2（重点 Jellyfin OpenAPI、jellyfin-web 歌词组件） |
| 必读文档 | REQUIREMENTS §5.1（实测结构）、findings（三样例时间戳结构） |
| 任务 | ① 双语配对（相同 / 极近 Start 时间戳）；② 逐行语言识别 + 语言列表（简中 / 繁中 / 英文 / 日文 / 原文）；③ 语言切换（默认简体中文）+ 双语对照模式 + 滚动同步 |
| 交付物 | 歌词功能 + 单测（配对 / 语言识别 / 滚动）+ 三样例验证记录 |
| 验收标准 | aLIEz / Brave Shine / 爱的回归线识别正确；默认简体中文生效；滚动同步准确 |
| 依赖 | W2 R2 合并；服务端 `/Audio/{id}/Lyrics` 可用（已实测） |
| 代理 | 不需要（测试服务器只读实测） |

#### W3-R3 · 音乐 / 阅读页面（R3-PAGES-A）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r3-ui-pages-a` |
| 必学 skill | `ROLE_SKILLS.md` §5.3（重点自适应布局 / 互操作 / 动画） |
| 必读文档 | `UI_DESIGN_SYSTEM.md`、W1 token 实现、W2 音乐 / 阅读页面结构、REQUIREMENTS UI-3（音乐 / 阅读部分） |
| 任务 | 音乐页面（浏览 / 正在播放 / 队列 / 歌词）与阅读页面（书架 / 阅读页 / 设置）接入新设计 |
| 交付物 | 页面实现 + 平板 / 手机走查记录 |
| 验收标准 | 无旧配色残留；平板双栏体验通过；动效流畅 |
| 依赖 | W1 token 合并；W2 页面结构稳定 |
| 代理 | 查 Compose 文档用 30001 |

### W4 · 收口波 1

#### W4-R1 · PDF / CBZ（R1-PDFCBZ）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r1-pdf-cbz` |
| 必学 skill | `ROLE_SKILLS.md` §5.1（重点 PdfRenderer / androidx.pdf / ZipInputStream） |
| 必读文档 | REQUIREMENTS EB-3/EB-4、C-tech-options §1（PDF 路线对比） |
| 任务 | ① PDF 分页懒加载（按页渲染与回收，内存红线）；② CBZ 基础阅读（单页 / 双页 / 缩放 / RTL 开关） |
| 交付物 | 两项功能 + 内存测量记录 + 真机验证记录 |
| 验收标准 | PDF 内存峰值不随文档大小线性增长；CBZ 翻页与 RTL 正确；缩放流畅 |
| 依赖 | W3 R1 合并；PDF / CBZ 测试内容到位 |
| 代理 | 不需要（依赖已就绪） |

#### W4-PLAYER · 播放器 UI 改造（PLAYER-UI）

| 项 | 内容 |
|----|------|
| 分支 | `feature/player-ui-refactor` |
| 必学 skill | PLAYER_PLAN §11 A–E + §5.2/§5.5.4 设计锁 + §9 踩坑库；可借 `design-taste-frontend` 的 audit 方法（只借方法） |
| 必读文档 | PLAYER_PLAN §11 全文、§1.10、REQUIREMENTS §3（保护性 P0） |
| 任务 | ① 控件精简（去重复入口）；② 图标重绘 + 文字标签；③ 面板统一右侧滑出且不压缩画面；④ 控件重排（参考主流播放器）；⑤ §1.10 视觉收口 |
| 交付物 | 控制层重构 + 命中区走查记录 + 真机验证记录 |
| 验收标准 | 面板打开不压缩画面；手势命中区无回归；款式与设计锁一致（不新增配色 / 字体） |
| 依赖 | W0 bug 修复合并；命中区契约（§5.2） |
| 代理 | 不需要 |

#### W4-R3 · 全页面落地（R3-PAGES-B）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r3-ui-pages-b` |
| 必学 skill | `ROLE_SKILLS.md` §5.3 |
| 必读文档 | REQUIREMENTS UI-3 全清单、`UI_DESIGN_SYSTEM.md`、W3 页面经验 |
| 任务 | 首页 / 媒体库 / 详情 / 搜索 / 下载 / 设置 / 抽屉 / 欢迎页全套换新 |
| 交付物 | 全页面实现 + 手机 / 平板 / 折叠展开态走查记录 |
| 验收标准 | UI-3 全清单覆盖；旧配色清零；布局无回归；动效流畅 |
| 依赖 | W1 token；W3 页面；W0 设计系统 |
| 代理 | 查 Compose 文档用 30001 |

### W5 · 收口波 2

#### W5-PLAYER · libass 字幕渲染（PLAYER-LIBASS）

| 项 | 内容 |
|----|------|
| 分支 | `feature/player-libass` |
| 必学 skill | PLAYER_PLAN §1.18 + §9 踩坑库；Media3 字幕渲染链路 | 
| 必读文档 | PLAYER_PLAN §1.18、§1.1（现有自研字幕管线） |
| 任务 | 接入 libass 提升 ASS/SSA 特效字幕还原度，保留语言识别与样式设置；先做衔接方案调研再实施 |
| 交付物 | libass 渲染管线 + 双内核验证记录 |
| 验收标准 | 复杂特效 ASS 与电脑端播放器基本一致；双内核切换不受影响 |
| 依赖 | W4 PLAYER-UI 合并；libass 依赖许可核验（LGPL/GPL 边界） |
| 代理 | 需要查 libass 资料时用 30001 |

#### W5-R2 · 音乐 P1（R2-P1）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r2-music-p1` |
| 必学 skill | `ROLE_SKILLS.md` §5.2（重点 Session API / 后台播放） |
| 必读文档 | REQUIREMENTS §8（P1 清单）、MU-6/MU-7、parity 批次 2 |
| 任务 | ① 睡眠定时；② 离线容量管理收尾；③ 音乐 parity 批次 2 补缺 |
| 交付物 | 三项功能 + 真机验证记录 |
| 验收标准 | 睡眠定时可用（到点暂停）；离线管理可用（容量 / 删除）；parity 缺口表更新 |
| 依赖 | W3 R2 合并 |
| 代理 | 不需要（本地 + 测试服务器只读） |

#### W5-R4 · 全量回归（R4-REGRESSION）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r4-regression` |
| 必学 skill | `ROLE_SKILLS.md` §5.4 |
| 必读文档 | W2 基线、REQUIREMENTS §12、每波回归清单 |
| 任务 | 全量回归 + 性能红线对比（启动 / 内存 / 体积） |
| 交付物 | 回归报告 + 性能对比报告 + 缺陷清单 |
| 验收标准 | 关键路径无回归；性能不劣化（超阈值需评审说明）；缺陷全部登记 |
| 依赖 | W2–W4 功能合并 |
| 代理 | 不需要 |

### W6 · 验收波

#### W6-R4 · 最终验收（R4-FINAL）

| 项 | 内容 |
|----|------|
| 分支 | `feature/r4-acceptance` |
| 必学 skill | `ROLE_SKILLS.md` §5.4 |
| 必读文档 | REQUIREMENTS §12 全文、W5 回归报告 |
| 任务 | 最终验收（功能 + 性能 + 真机矩阵）+ 用户阶段验收支持 |
| 交付物 | 最终验收报告（含截图 / 日志留档索引） |
| 验收标准 | §12.1 / §12.2 全过；用户确认阶段验收 |
| 依赖 | W5 全部合并 |
| 代理 | 不需要 |

#### W6-负责人 · 发布准备

| 项 | 内容 |
|----|------|
| 分支 | `feature/release-prep` |
| 必学 skill | GPL-3.0 合规清单（C-tech-options §4）；品牌资产更新 |
| 任务 | GPL / NOTICE 检查、图标 / 启动图更新、README 更新、发布检查清单 |
| 交付物 | 发布检查清单 + 合规记录 |
| 验收标准 | 无未声明的第三方许可风险；发布清单完成 |
| 依赖 | W6-R4 |
| 代理 | 需要核验许可资料时用 30001 |

---

## 3. 统一代理说明（所有会话适用）

1. 联网前读本地共享文件 `E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\proxy.md`（仓库外，禁止提交）；
2. 命令行工具首选 30001：`curl.exe -x "https://<user>:<pass>@proxy.zhangwenkang.com:30001" "<目标 URL>"`；
3. **Gradle 保持直连**（30000 走不通 Google Maven，会导致依赖解析失败）；
4. 凭据只允许存在于命令变量中；**禁止写入仓库 / 文档 / 提交信息 / 会话输出**；
5. 测试服务器 `jellyfins.zhangwenkang.com` 为**只读 + 用户数据写白名单**（进度 / 收藏 / 播放列表），
   禁止媒体库管理 / 扫描 / 删除（REQUIREMENTS §11）。

## 4. 版本历史

| 版本 | 日期 | 变更 |
|------|------|------|
| v1.0 | 2026-09-29 | 首版：通用模板 + W0–W6 全部会话简报 + 统一代理说明 |
