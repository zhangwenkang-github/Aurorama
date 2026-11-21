# Cinefin · 项目总览

> **新会话第一步读这里**，再读所在波次/线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-21　分支 `codex/v2-integration`

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
| 2.0.0 | 🟡 **W6 全部卡合入并 K60 取证全绿**（2026-11-22 会话4）：T1–T10 + 修复卡 T4r/T6r/T9r/T9r2/T6r2（`b2bb8b60`）全收口，T9r2/T6r2 复验通过（矩阵见 `V2_W6_TASKS.md` 状态块）；**只剩 T11 全量验收收官** | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · **`V2_W6_TASKS.md`** · `V2_W5_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 1.4.1 回归全 PASS；口径类遗留列入 W7 | `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-21）

1. **2.0.0 W6 会话4 完成（2026-11-22）**：T6r2 修复合入 `b2bb8b60` 并 K60 复验全绿（书末 ≤2 次空转停止、60px/s 实测 60.06），T9r2 复验全绿（句句下发/跨章跟随/停止清空 + T9 回归）；4 条观察项待 T11 裁定（详见 `V2_W6_TASKS.md` 状态块「会话4」）。**下一步：会话5 = T11 全量验收收官（Kimi 亲自）→ 焦点移交 W7。**
2. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W6 会话5 开工提示词（新会话直接粘贴；格式遵循 AGENTS.md「开工提示词统一标准」，W6 原始开工提示词存档于 `V2_W6_TASKS.md` 状态块）

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W6_TASKS.md 状态块
→ 记忆库 MEMORY.md（读序外文件按需，不通读）。
现状：W6 全部卡（T1–T10 + T4r/T6r/T9r/T9r2/T6r2）合入并 K60 取证全绿（HEAD 以 git log 为准，
应含 b2bb8b60）；取证判定与 4 条待裁定观察项在 V2_W6_TASKS.md 状态块「会话4」。
本会话任务（1 件，Kimi 亲自不派子代理）：
① T11 全量验收收官：对照 V2_REQUIREMENTS.md §5 全 48 条逐条销号（✅/⚠️/❌+证据），
   裁定会话4 的 4 条观察项与既有 ⚠️ 去向（观察/backlog），回写 V2_REQUIREMENTS.md 关联判定、
   清 pageIndicatorText 死代码（改后跑门禁）、PROJECT_PLAN 焦点移交 W7。
   K60 复用机上 fixture /sdcard/Download/w6-test-utf8.txt（书架已在架）做核心链路抽查；
   新取证日志落 F:\Develop\code_data\qa\w6-s5\（既有证据 w6-s4\app-p17125.log）。
设计分权：以 V2_W6_TASKS.md「设计定稿」八节为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线。
交接：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新（写 W7 开工提示词）。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
