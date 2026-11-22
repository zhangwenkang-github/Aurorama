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
| 2.0.0 | 🟡 **W8 进行中（T1–T6 已交付，T6 真机复验收官中，剩 T7）**（2026-11-23 会话6）：T6 元数据编辑 `425e155b`+`ef53444e`+`8a5eac2a`+`b9ce45fc`（raw JSON 通道绕开 SDK DateTime 400 缺陷、ForcedSortName 实证，门控/预填/失败可见已 Pad 验证，成功闭环待跑）；W9-T0 聚合顺延 | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · `V2_W8_TASKS.md` · `V2_W7_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 W7 已收官（2026-11-22）：55 条 = ✅51/⚠️4/❌0；修复卡 F1b 音乐重下载删档绕过 / F2 PENDING 滞留 / F4 音乐离线冷路径 / F5 书籍通知 / F6 作者折叠 / F7 引擎空转热循环，全登记 W9 聚合（F1 引擎层与 F3 已修销项） | `docs/V2_W7_TASKS.md` · `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-22）

1. **2.0.0 W8 服务器写进行中（2026-11-23 会话6）**：T1/T1r/T2/T3'/T4/T5 已验收；**T6 元数据编辑已交付**（`425e155b`+`ef53444e`+`8a5eac2a`+`b9ce45fc`，四门绿）：详情页编辑入口（T2 canEditMetadata 仅管理员门控）+ 三字段对话框 + **raw JSON 写通道**（实证两大陷阱已修：①SortName 必须写 ForcedSortName；②SDK 类型化 DTO 回环把 MinValue DateTime 序列化成 `+08:05:43` 致稳定 400，改 `ApiClient.request` GET 原文→换三键→FileInfo POST）。Pad 已验：反向门控、预填、失败可见；**成功闭环（编辑→回读→还原）未跑**，样本与快照就绪（`qa/w8-t6/`，乔西的虎与鱼+结婚了 S1E1）。设备遗留：Pad App 停留在 localhost:18080 抓包服（已失效），复验前先切回直连 jellyfin 服 admin；测试服无 T6 残留。下一步 T6 复验收官 → T7 全量验收矩阵。
2. **W7 下载域已收官（2026-11-22 会话4，HEAD `da4791d5`）**：55 条 = ✅51/⚠️4/❌0；⚠️ 全裁定（F1b/F2/F4/F5/F6/F7 + L1–L3 + O1 + S1，见 `V2_W7_TASKS.md`「裁定登记」）；F3（`d8ee43af`）与 F1 引擎层（`d5e8b119`）已修；F1b/F7 根因已定位、修复卡就绪（优先候选）；W9-T0 聚合顺延至 W8 收官后。
3. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W8 会话7 开工提示词（新会话直接粘贴；格式遵循 AGENTS.md「开工提示词统一标准」）

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W8_TASKS.md 状态块
→ 记忆库 MEMORY.md（读序外文件按需，不通读）。
现状：T1–T6 已交付（T6=425e155b/ef53444e/8a5eac2a/b9ce45fc，raw JSON 写通道+
ForcedSortName，两门真缺陷已修，四门绿）；T6 Pad 已验反向门控/预填/失败可见，
成功闭环未跑；测试服无残留；HEAD 以 git log 为准。
本会话任务（1–2 卡，Kimi 主导真机）：①W8-T6 复验收官——Pad App 先从「服务器/
用户」页切回直连 jellyfin 服 admin（localhost 抓包服已失效），装 b9ce45fc 构建，
电影《乔西的虎与鱼》编辑→服务端回读新值→API 还原→回读原值，单集《结婚了》S1E1
（纯 ASCII 名）做全 App 内闭环；样本与快照见 V2_W8_TASKS.md T6 卡与 qa/w8-t6/。
②视容量续 W8-T7 全量验收矩阵+多服务器隔离核查（Kimi 主导）。
设计分权：设计以 docs/V2_DESIGN.md 为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线；子代理禁 adb；真机取证 Kimi 做（Pad=
43af8627，对话框坐标按 dump+50 修正；Pad 无中文输入，中文还原走 API）。
交接：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
