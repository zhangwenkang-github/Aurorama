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
| 2.0.0 | 🟡 **W6 阅读域实现卡全部合入**（2026-11-21 会话3）：T1–T10 + 修复卡 T4r/T6r/T9r/T9r2 全绿，K60 大矩阵取证完成（矩阵见 `V2_W6_TASKS.md` 状态块）；剩 T6r2（空转/倍速）→ T9r2 复验 → T11 收官 | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · **`V2_W6_TASKS.md`** · `V2_W5_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 1.4.1 回归全 PASS；口径类遗留列入 W7 | `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-21）

1. **2.0.0 W6 阅读域实现阶段完成（2026-11-21，会话3）**：T1–T10 全部合入（T1 `436aaf07`、T2 `b84c14be`、T3 `baa306a2`、T4 `6c80cd9d`、T5 `773fe87c`、T6 `9aecc478`、T7 `d238397d`+`9a794ae6`、T8 `22abb8a6`、T9 `e27070ac`、T10 `dab1ae32`）+ 真机缺陷修复卡 T4r `ac2b2d2e`/T6r `bc331698`/T9r `feae0fd3`/T9r2 `99f460ac`；K60 大矩阵取证完成（判定矩阵在 `V2_W6_TASKS.md` 状态块）。**下一步：会话4 = 派 T6r2（末章空转+滚速 2.5×），K60 复验 T9r2（句高亮）与 T6r2，然后 T11 全量验收收官。**
2. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W6 会话4 开工提示词（新会话直接粘贴；格式遵循 AGENTS.md「开工提示词统一标准」，W6 原始开工提示词存档于 `V2_W6_TASKS.md` 状态块）

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W6_TASKS.md 状态块与任务卡
→ 记忆库 MEMORY.md（读序外文件按需，不通读）。
现状：W6 实现卡 T1–T10 全部合入 + 修复卡 T4r/T6r/T9r/T9r2（HEAD 以 git log 为准，应含 99f460ac）；
K60 取证判定矩阵在 V2_W6_TASKS.md 状态块「会话3」；T0=✅9/⚠️17/❌22。
本会话任务（按序，1–2 件）：
① 派 T6r2（卡面在 V2_W6_TASKS.md 任务卡末尾）：末章到底 goForward 空转不停 + 滚速 2.5× 偏差，
   子代理修完门禁绿后由 Kimi 在 K60（8e875894）流式 logcat 复验（tag=ReadiumEpubViewKt 过滤
   「自动滚动」：末章 ≤2 次空转内 stop；60px/s 实测 48–72）。
② Kimi K60 复验 T9r2（99f460ac）：朗读中无「应用 TTS 句高亮失败」、每句有
   「TTS 句高亮已下发」debug 行、跨章跟随跳章、停止后高亮清空；
   顺带回归 T9 定时/上下句/后台通知。书架有本地书 w6-test-utf8 可直接开（若被清则重推 fixture 导入）。
③ 全绿后进 T11（Kimi 亲自）：§5 全 48 条逐条销号、⚠️ 裁定、回写 V2_REQUIREMENTS.md、
   清 pageIndicatorText 死代码、焦点移交 W7。
设计分权：以 V2_W6_TASKS.md「设计定稿」八节为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线（子代理禁 adb；K60 真机取证 Kimi 做，流式 logcat --pid 落盘；
设备用后停播放 state=NONE + force-stop；生产 jellyfins 永久只读）。
交接：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
