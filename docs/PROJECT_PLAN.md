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
| 2.0.0 | 🟡 **W6 阅读域已开工**（2026-11-21 会话1）：T0 现状核对完成（§5 全 48 条 = ✅9/⚠️17/❌22）+ 设计定稿八节 + 任务卡 T1–T11 定稿待派；W5 音乐域已收官（✅47/⚠️3/❌0，残项均有去向） | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · **`V2_W6_TASKS.md`** · `V2_W5_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 1.4.1 回归全 PASS；口径类遗留列入 W7 | `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-21）

1. **2.0.0 W6 阅读域开工完成（2026-11-21，会话1）**：T0 现状核对（✅9/⚠️17/❌22）+ 设计定稿八节 + 任务卡 T1–T11 定稿（`docs/V2_W6_TASKS.md`，含两条 ⚠️ 预案：EPUB 仿真/覆盖翻页→backlog、EPUB 划词→T8 spike 前置）。**下一步：按依赖派 T1（沉浸控制层与手势重排），T7（TXT）与 T8-spike 可首批并行。**
2. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W6 会话2 开工提示词（新会话直接粘贴；W6 原始开工提示词已存档于 `V2_W6_TASKS.md` 状态块）

```
继续 Aurorama 2.0.0 W6 阅读域实现会话。你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W6_TASKS.md 状态块 + 任务卡
→ docs/V2_REQUIREMENTS.md §5 → 记忆库 MEMORY.md。
现状：T0 现状核对完成（§5 全 48 条 = ✅9/⚠️17/❌22），设计定稿八节与任务卡 T1–T11 已定稿
（HEAD 以 git log 为准）。两条 ⚠️ 预案已锁定：EPUB 仿真/覆盖翻页→backlog（EPUB 只给滑动/无动画），
EPUB 划词→T8 spike 前置（≤半天，结论写回状态块再实现）。
本会话任务：按依赖派卡。首批：T1（沉浸控制层与手势重排，modes/book）必发；
T7（TXT 支持与导入）与 T8-spike（Readium 选择/Decoration 能力验证）可与 T1 并行；
T2–T6 待 T1 合入后派。
设计决策一律以 V2_W6_TASKS.md「设计定稿」八节为准，deepseek 零设计权。
纪律：每任务一子代理一 commit；子代理禁 adb，只模块单测+ktfmtCheck+构建（--max-workers=2）；
核心链路 K60（8e875894）真机取证由 Kimi 做（流式 adb logcat --pid 落盘；装机前核对 APK mtime）；
设备用后停播放（state=NONE + force-stop）。
红线：生产 jellyfins.zhangwenkang.com 永久只读；只删自己创建的文件。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
