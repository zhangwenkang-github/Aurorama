# Cinefin · 项目总览

> **新会话第一步读这里**，再读所在波次/线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-25　分支 `codex/v2.0.1`

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
| 2.0.0 | 🟢 **已发布**（2026-11-24，用户当场授权）：分支 `codex/v2-integration`=`312209a6`、tag `v2.0.0`、GitHub Release 双 APK（发布后下载资产 SHA-256 复核一致）；T9 R1–R12 全绿（回归证据 `F:\Develop\code_data\qa\w9-t9\`）；2.0.1 候选已裁定（首修 P1=搜索音乐结果导航，余 LATER 见裁定登记） | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · `V2_W9_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 W7 已收官（2026-11-22）：55 条 = ✅51/⚠️4/❌0；修复卡 F1b 音乐重下载删档绕过 / F2 PENDING 滞留 / F4 音乐离线冷路径 / F5 书籍通知 / F6 作者折叠 / F7 引擎空转热循环，全登记 W9 聚合（F1 引擎层与 F3 已修销项） | `docs/V2_W7_TASKS.md` · `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |
| 维护/缺陷修复 → 2.0.1 | 🟡 **W1 已收官、W2 开工**（2026-11-25）：分支 `codex/v2.0.1`；T01–T08 全 DONE；T09 批① 角色收口 DONE（`0d3aa49a`+`b17eaa24`）；设计定稿 `docs/V201_DESIGN_FINAL.md`（含批①验收裁定） | `docs/V201_TASKS.md` |

## 4. 当前焦点（2026-11-25）

1. **2.0.1 开工（分支 `codex/v2.0.1`）**：计划经用户两轮审批通过——21 项需求/bug + 2.0.0 遗留，波次 W0→W5（修复快线→设计基础→音乐重塑→播放器收尾→回归发布），台账 `docs/V201_TASKS.md`（22 卡全登记）。工作流七条已制度化进 `AGENTS.md`。
2. **W0 已收官（2026-11-24）**：✅ 分支+台账+AGENTS.md；✅ W0-D1 设计审计（22 条偏差）；✅ W0-D2 设计定稿（`docs/V201_DESIGN_FINAL.md`，9 条裁定：域色=身份、极光=行动；琥珀退出交互强调；封面取色只留背景光晕；玻璃无 blur 为正式决定；主题选择器加跟随系统）；✅ W0-D3 master 已对齐 v2.0.0（`312209a6`，远端推送待授权）。
3. **W1 修复快线已收官（2026-11-25）**：T01–T08 全 DONE。本批三卡：✅ T02b 管理员入口首次查询缺口（`9bc5693c`：缓存无当前账号记录视同换账号补查；留档：补查未节流）、✅ T05 静默刷新（`71a15850`：同剧刷新保留明细、失败沿用旧值，K60 滚动偏移仅 31px；留档：已看勾选无语义图标不可文本取证）、✅ T07 跨季误下载（`c014f948`：根因=服务器按 ParentIndexNumber 归季返回跨季 dto，加 `episodeMatchesRequestedSeason` 一致性过滤；K60 实测 S01 12 集、S02 仍 11 集）。
4. **W2 开工（2026-11-25）**：✅ T09 批① 角色收口（`0d3aa49a`+`b17eaa24`：Neutral→MediaAurora、M3 解耦域色+dynamic 映射、warning/success/info 语义 token、评分徽标极光淡色 pill、黄铜退出 phone）；批①验收裁定已落 `V201_DESIGN_FINAL.md` §2（派生值转正、浅色档对比度列批③专项、inversePrimary 无消费点不处理、dynamic 下 Neutral 固定极光）。下一批：T09 批② 组件换肤（token 已就位）+ T11/T12 手势误触可并发。
5. **主题特别要求（用户拍板）**：#9 按 W0-D2 定稿还原——T09 依 `V201_DESIGN_FINAL.md` §2 三批执行，批① 已核销。
6. **环境事实**：多设备在线装机用 `assembleLibreDebug` + `adb -s <id> install -r`；全模块单测注意 `:core`/`:app:phone` 是 `testLibreDebugUnitTest`、`:data`/`:modes:film` 是 `testDebugUnitTest`；ktfmt 修正任务名是 `ktfmtFormat`（无 `ktfmtApply`）；发布门须显式加跑 `verifyMpvJniReleaseContract`；GitHub 上传双资产偶发 502 拆「先建 Release 再分次传」可解；**并发子代理共用工作区会互踩 Gradle 增量编译缓存**（Kotlin 内部错误/class-attributes.tab 冲突），并发卡尽量错开构建或接受失败后重跑。

### 下会话开工提示词

```
1. 角色与工作区（固定一行）：你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
   工作区：F:/Develop/code_data/work_tree/Aurorama/v2-merge-141（分支 codex/v2.0.1）。
2. 开工读序（固定）：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V201_TASKS.md 状态块
   → 记忆库 MEMORY.md（读序外文件按需，不通读）。
3. 现状（≤4 行）：HEAD 以 git log 为准；W1 已收官（T01–T08 全 DONE），W2 开工——T09 批① DONE（0d3aa49a+b17eaa24）；
   master=312209a6（远端推送待授权）；设计定稿=docs/V201_DESIGN_FINAL.md（§2 含批①验收裁定）。
4. 本会话任务（1–2 卡）：W2-T09 批②（组件换肤：Aurora tone/进度/选中/开关滑杆/SectionHeader，批① token 已就位）
   + T11/T12（抽屉/滑杆手势误触，独立于 token）可并发派子代理；批③ 开工前需 Kimi 先出浅色档对比度专项裁定（候选 auroraInk）。
5. 设计分权（固定一行）：设计以 docs/V201_DESIGN_FINAL.md + docs/V2_DESIGN.md §3 为准，deepseek 零设计权。
6. 门禁与纪律（固定引用）：见 AGENTS.md 质量门/红线；adb 取证派子代理，负责人复核证据关键行。
   【并发卡共用工作区会互踩 Gradle 增量编译缓存，派卡时声明构建错开或接受重跑】
7. 交接（固定一行）：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
