# Cinefin · 项目总览


> **新会话第一步读这里**，再读活跃线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-18　分支 `master`

## 1. 项目是什么

Cinefin = 基于 **Findroid**（GPL-3.0）改造的**自用 Jellyfin 客户端**：平板优先、深色影院风，功能对齐官方客户端，另有手势体系与字幕/音轨语言智能识别。

| 项 | 值 |
|----|----|
| 包名 | `applicationId` = `io.github.zhangwenkang.aurorama`（debug `.debug`）；Kotlin 包名 `com.zhangwenkang.cinefin` |
| SDK | compileSdk 37 / targetSdk 36 / minSdk 28 |
| 版本矩阵 | AGP 9.4.1 / Kotlin 2.4.20 / Gradle 9.7.1 / Compose 1.12.1 / Media3 1.11.1 |
| 播放内核 | ExoPlayer（硬解）+ FFmpeg 软解 + libmpv（二级兜底） |
| 许可证 | 对外分发必须 GPL-3.0 开源 |

## 2. 模块地图


| 模块 | 职责 |
|------|------|
| `app/phone` | 手机/平板主应用（TV 端 `app/tv` 冻结） |
| `core` | 设计系统、通用工具、下载与图片 Worker |
| `data` | Jellyfin API、Room、仓库（在线/离线）、认证、下载 |
| `player/core` · `player/local` | 播放抽象与内核（ExoPlayer/FFmpeg/mpv） |
| `modes/film` · `modes/book` · `modes/music` | 影视 / 阅读器 / 音乐业务页 |
| `settings` / `setup` | 偏好设置 / 首连向导、登录 |

## 3. 任务线状态（每线一句话 + 文档指针）

| 线 | 状态 | 文档 |
|----|------|------|
| 维护/缺陷修复 → 1.4.1 | 🟢 **已完结**（2026-11-18）：v1.4.0（2026-11-02）与 v1.4.1（2026-11-17，master `76f5350b`）均已发布；开放 backlog 转 2.0.0 需求账本 | `docs/FIX_PLAN.md` |
| 播放器 | 🟢 主体完成；历史搜索 ANR P1 = ACCEPTED_RESIDUAL（复现即重开）；遗留打磨项见线文档 | `docs/PLAYER_PLAN.md` |
| 下载/离线 | 🟢 完成（自研引擎、层级化、离线闭环、本地媒体库） | `docs/DOWNLOAD_PLAN.md` |
| 阅读器 | 🟢 完成（流式首开、PDF 搜索/批注）；线文档已归档 | `docs/archive/2026-11/READER_PLAN.md` |
| 音乐 | 🟢 完成（队列/歌词/EQ/ReplayGain）；线文档已归档 | `docs/archive/2026-11/MUSIC_PLAN.md` |
| UI 重塑 | 🟢 完成；设计系统留存 | `docs/UI_DESIGN_SYSTEM.md` + `docs/archive/2026-11/UI_PLAN.md` |
| 测试与验收 | 🟢 W61 全量回归完成 | `docs/TEST_PLAN.md` |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| Web 控制台 | 🟢 完成 | `docs/web-console-skin.css`（权威副本） |
| 浏览体验 | ✅ 主体完成，打磨按需开线 | — |
| 投屏/同步观看 | ⛔ 未开始（`:player:cast` 未创建） | — |

## 4. 当前焦点（2026-11-18）

1. **维护线已完结**：v1.4.0 / v1.4.1 全部发布，开放 backlog（B13–B20 等）转入 2.0.0 需求账本（`docs/V2_REQUIREMENTS.md`，在 `codex/v2-integration` 分支维护）。
2. **2.0.0 开发**在 `codex/v2-integration` 分支推进（W1 设计系统已完成，下一波 W2 播放器架构重构）；master 保持 1.4.1 发布态，仅接收文档与热修。

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 活跃线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
- 2026-11-17 前的完整旧版：`docs/archive/2026-11/*.full-20261117.md` 及 git 历史。
