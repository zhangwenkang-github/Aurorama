# Cinefin · 项目总览

> **新会话第一步读这里**，再读所在波次/线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-22　分支 `codex/v2-integration`

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
| 2.0.0 | 🟡 **W7 下载域已收官**（2026-11-22 会话4，HEAD `648b4b82`）：§8 全 55 条终判 ✅50/⚠️5/❌0（⚠️=06/11/27/28/44 全裁定去向：修复卡 F1–F6、backlog-low L1–L3、OUT-接受 O1、样本窗口 S1，登记 `V2_W7_TASKS.md`「裁定登记」）；K60 全链路矩阵绿（含视频完成通知直达、断网离线播放）；**下一步 = W9-T0 backlog 聚合**（W4/W5/W8 波次未收官，聚合先行） | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · `V2_W7_TASKS.md` · `V2_W6_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 W7 已收官（2026-11-22）：55 条 = ✅50/⚠️5/❌0；修复卡 F1 COMPLETED 幂等 / F2 PENDING 滞留 / F3 季页多选提示层 / F4 音乐离线冷路径 / F5 书籍通知 / F6 作者折叠，全登记 W9 聚合 | `docs/V2_W7_TASKS.md` · `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-22）

1. **2.0.0 W7 下载域已收官（2026-11-22 会话4，HEAD `648b4b82`）**：T6 全量验收完成，55 条 = ✅50/⚠️5/❌0；⚠️ 全裁定（修复卡 F1–F6 / backlog-low L1–L3 / OUT-接受 O1 / 样本窗口 S1，见 `V2_W7_TASKS.md`「裁定登记」）；K60 全链路矩阵绿（提示层→入队→暂停/续传→完成→视频完成通知直达 PlayerActivity→断网离线播放），设备还原复核 ✓；证据 `F:\Develop\code_data\qa\w7-s4\`。**下一步 = W9-T0 backlog 聚合**（W4 播放器 / W5 音乐 / W8 服务器写波次未收官，按用户指令聚合先行）。
2. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W9 会话1 开工提示词（新会话直接粘贴；格式遵循 AGENTS.md「开工提示词统一标准」，W7 各会话提示词已存档于 `V2_W7_TASKS.md` 状态块）

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W7_TASKS.md 状态块
与「裁定登记」节 → 记忆库 MEMORY.md（读序外文件按需，不通读）。
现状：W7 下载域已收官（2026-11-22，55 条 = ✅50/⚠️5/❌0，HEAD 以 git log 为准）；
W4 播放器 / W5 音乐 / W8 服务器写波次未收官，其 backlog/观察项散在各自波次文档。
本会话任务（1 卡，收集派子代理、裁剪 Kimi 亲自）：W9-T0 backlog 聚合——
收集 W4–W8 各波次文档（V2_W4_TASKS.md / V2_W6_TASKS.md / V2_W7_TASKS.md /
W5、W8（如已建）+ DOWNLOAD_PLAN.md / PLAYER 线文档）的 backlog/观察项清单
（含 W7 裁定登记 F1–F6/L1–L3/O1/S1）→ 去重合并、标注来源与严重度 →
Kimi 裁剪（修 / backlog / OUT）→ W9 修复卡清单定稿（新建 docs/V2_W9_TASKS.md，
状态块在顶部，本提示词移入存档）；样本窗口 S1 的申请书一并起草给用户。
设计分权：设计以 docs/V2_DESIGN.md 为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线；本卡为文档/裁定工作，预期不动代码、
无需真机（子代理 adb 已于 2026-11-22 解禁，本卡用不到）。
交接：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
