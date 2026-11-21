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
| 2.0.0 | 🟡 **W5 音乐域已收官**（2026-11-21 会话6）：T1–T12 + 全部返修/fixpack 合入并 K60 验收通过，全波判定 ✅47/⚠️3/❌0（18 渐进加载→W7 观察、35 内嵌歌词/RG 入库→backlog、49 本地缺失标记观感→观察项）；**焦点移交 W6 阅读器增强** | `docs/V2_REQUIREMENTS.md` · `V2_ARCHITECTURE.md` · `V2_DESIGN.md` · `V2_W5_TASKS.md` · `V2_W4_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 1.4.1 回归全 PASS；口径类遗留列入 W7 | `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-21）

1. **2.0.0 W5 音乐域正式收官（2026-11-21，会话6）**：T12 Auto DJ（merge `eef7feba`，含真机返修：ENDED 接缝改 `playFromQueueIndex` 起播）合入，集成门禁绿；K60 四场景（默认关/续播+上报链/睡眠闸门/断网静默）与观感补验 5 项（MUSIC-26/41/44/45/49）全过；全波判定 50 条 = ✅47/⚠️3/❌0，残项已裁定去向。**下一焦点：W6 阅读器增强**（开工前读 `V2_REQUIREMENTS.md` 对应域与 `V2_DESIGN.md`，沿用波次制：T0 现状核对 → 设计定稿 → 任务卡）。
2. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W6 开工提示词（新会话直接粘贴）

```
继续 Aurorama 2.0.0 W6 阅读域开工会话。你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_REQUIREMENTS.md §5（V2-READER
全 48 条）与 §15 波次表（W6 阅读域 ← W3）→ docs/V2_DESIGN.md → 记忆库 MEMORY.md。
W5 参考口径（只读状态块，不通读）：docs/V2_W5_TASKS.md。
现状：W5 音乐域已正式收官（2026-11-21，HEAD 以 git log 为准），全波判定 50 条 = ✅47/⚠️3/❌0；
三条 ⚠️ 已有去向（18 渐进加载→W7 观察、35 内嵌歌词/RG 入库→backlog、49 本地缺失标记观感→观察项）。
本会话任务（沿用波次制，一天做不完的回写状态后交接）：
① W6-T0 现状核对：§5 全 48 条逐条核对 modes/book 现状（✅/⚠️/❌+代码位置归证），
   阅读器模块在 modes/book，设计 token 与动效沿用 W3/W4 落地件；
② 设计定稿：视觉/动效/布局/交互规格由 Kimi 亲自定稿（可借助设计类 skill），deepseek 零设计权；
③ 任务卡定稿：每卡自包含（目标/文件边界/验收要点/门禁/红线），新建 docs/V2_W6_TASKS.md
   （状态块在顶部），本提示词移入其状态块存档。
纪律：每任务一子代理一 commit；子代理禁 adb，只模块单测+ktfmtCheck+构建（--max-workers=2）；
核心链路 K60（8e875894）真机取证由 Kimi 做——行为日志一律流式 adb logcat --pid=<pid> 落盘
（dump 会被 MediaCodec 刷屏冲掉；Timber tag=调用类名；PID 随重装变更需重开流）；
装机前核对 APK mtime 晚于目标合并；设备用后停播放（state=NONE + force-stop）。
红线：生产 jellyfins.zhangwenkang.com 永久只读；只删自己创建的文件。
交接：收口按 AGENTS.md 五件事，含为下一任务写开工提示词（放 PROJECT_PLAN 焦点节）。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
