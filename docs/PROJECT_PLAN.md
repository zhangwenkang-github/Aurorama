# Cinefin · 项目总览

> **新会话第一步读这里**，再读所在波次/线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-18　分支 `codex/v2-integration`

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
| `core` | 设计系统、通用工具、下载与图片 Worker（W1 起拆分 `core/designsystem`） |
| `data` | Jellyfin API、Room、仓库（在线/离线）、认证、下载 |
| `player/core` · `player/local` | 播放抽象与内核（ExoPlayer/FFmpeg/mpv） |
| `modes/film` · `modes/book` · `modes/music` | 影视 / 阅读器 / 音乐业务页 |
| `settings` / `setup` | 偏好设置 / 首连向导、登录 |

## 3. 任务线状态（每线一句话 + 文档指针）

| 线 | 状态 | 文档 |
|----|------|------|
| 1.4.1 | 🟢 **已发布**（2026-11-18）：master=`76f5350b`、tag `v1.4.1`、GitHub Release 双 APK | 发布目录 `releases/1.4.1-signed-r34-2026-11-17/` |
| 2.0.0 | 🟢 **W1 完成**（2026-11-18）：主题引擎+四套 K1 token+logo B 全套+首批 29 图标+主题选择器全部验收合入（HEAD `d021de57`，K60 真机全项验证）；下一波 W2 播放器架构重构 | `docs/V2_REQUIREMENTS.md` · `V2_ARCHITECTURE.md` · `V2_DESIGN.md` · `V2_W1_TASKS.md` |
| 播放器 | 🟢 1.4.1 三修复已发布；架构重构（单 owner/状态机/引擎抽象）列入 W2 | `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 1.4.1 回归全 PASS；口径类遗留列入 W7 | `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-18）

1. **2.0.0 W2 播放器架构重构**（W1 设计系统已于 2026-11-18 完成收口）：单 owner/状态机/引擎抽象（`docs/V2_ARCHITECTURE.md` §3）+ 1.4.1 三项遗留根治；任务卡待备。**W2 开工第一批先修两条 master CI 既有失败**（2026-11-18 查实，1.4.1 起存在）：①Build `:player:local:mergeDebugNativeLibs` 重复打包 `libc++_shared.so`（mpv-binding jniLibs 与 CMake STL 撞名，修法 = `packagingOptions pickFirst`，修后本地 clean 全量构建验证 + CI 复核，可按用户授权 cherry-pick 回 master）；②Unit Tests `:data` 的 `JellyfinEpisodeMappingTest` flaky（两次 CI 挂不同用例，本地 `:data:testDebugUnitTest --tests` 连跑复现后定因）。W1 遗留 9 条见 `docs/V2_W1_TASKS.md` 交接节（含 app/tv 既有编译失败、图标第二批、形状 token 随 W3 切换）。
2. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
