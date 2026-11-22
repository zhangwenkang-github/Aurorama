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
| 2.0.0 | 🟡 **W7 已收官，W8 服务器写待开工**（2026-11-22 会话4，HEAD `da4791d5`）：W7 终判 ✅51/⚠️4/❌0，F3/F1 已修、F1b/F7 根因定位登记 W9；用户拍板 **W8 先行**（测试服 `jellyfin-test` 可写授权：可删/改元数据、优先已播完内容），W9-T0 聚合顺延至 W8 后；W8 波次文档已预建 | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · `V2_W8_TASKS.md` · `V2_W7_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 W7 已收官（2026-11-22）：55 条 = ✅51/⚠️4/❌0；修复卡 F1b 音乐重下载删档绕过 / F2 PENDING 滞留 / F4 音乐离线冷路径 / F5 书籍通知 / F6 作者折叠 / F7 引擎空转热循环，全登记 W9 聚合（F1 引擎层与 F3 已修销项） | `docs/V2_W7_TASKS.md` · `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-22）

1. **2.0.0 W7 下载域已收官（2026-11-22 会话4，HEAD `da4791d5`）**：T6 全量验收完成，55 条 = ✅51/⚠️4/❌0；⚠️ 全裁定（F1b/F2/F4/F5/F6/F7 + L1–L3 + O1 + S1，见 `V2_W7_TASKS.md`「裁定登记」）；当日追加修复 F3（`d8ee43af`，K60 复验 ✅）与 F1 引擎层（`d5e8b119`）；F1b/F7 根因已定位、修复卡就绪（优先候选）。设备还原复核 ✓；证据 `F:\Develop\code_data\qa\w7-s4\`。
2. **下一步 = W8 服务器写（2026-11-22 用户拍板，修正此前「W9 聚合先行」的交接口径）**：测试服 `jellyfin-test.zhangwenkang.com` 已获可写授权（可删除/改元数据，优先操作已播完内容；凭据在 `资源.txt`，值不入库）；波次文档 `docs/V2_W8_TASKS.md` 已预建（状态块 + T0 卡），会话1 = T0 现状核对 + 任务卡定稿。W9-T0 backlog 聚合顺延至 W8 之后。
3. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W8 会话1 开工提示词（新会话直接粘贴；格式遵循 AGENTS.md「开工提示词统一标准」；W9-T0 backlog 聚合顺延至 W8 收官后，届时另写提示词）

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W8_TASKS.md 状态块
→ 记忆库 MEMORY.md（读序外文件按需，不通读）。
现状：W7 下载域已收官（55 条 = ✅51/⚠️4/❌0，F1b/F7 修复卡根因已定位，登记
V2_W7_TASKS.md 裁定登记）；W8 服务器写未开工，依赖 W3 已满足；测试服
jellyfin-test.zhangwenkang.com 已获用户可写授权（可删除/改元数据，优先操作
已播完内容；凭据在资源.txt，值不入库）；W9-T0 聚合顺延至 W8 后。
本会话任务（1 卡，Kimi 亲自）：W8-T0 现状核对 + 任务卡定稿——对照 §6 V2-WRITE
全 12 条逐条核对现有写能力（收藏/已看/进度上报/评分/服务端播放列表/条目
元数据编辑/删除，✅/⚠️/❌+归证）；以官方 jellyfin-android / Findroid 上游
写操作清单做 WRITE-01 完备度对照；盘点测试服账号（受限用户有无，无则申请）
与已播完可写样本；任务卡 T1+ 定稿写入 V2_W8_TASKS.md（每卡含写边界与还原
声明），本提示词移入其状态块存档。
设计分权：设计以 docs/V2_DESIGN.md 为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线；本卡为核对/文档工作，不动代码、无需
真机（子代理 adb 已解禁，本卡用不到）。
交接：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
