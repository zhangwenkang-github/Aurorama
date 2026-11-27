# Cinefin · 项目总览

> **新会话第一步读这里**，再读所在波次/线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-28　分支 `codex/v2.0.3`

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
| 维护/缺陷修复 → 2.0.1 | 🟢 **已发布**（2026-11-26，用户授权）：分支 `codex/v2.0.1`、tag `v2.0.1`（@`d463e64b`）、master 强推对齐 `312209a6`、GitHub Release 双 APK（发布后重下载 SHA-256 复核一致）；K60 升级 E2E：AVAILABLE+更新说明渲染 ✅ | `docs/V201_TASKS.md` |
| UI 重构/热修 → 2.0.2 | 🟢 **已发布（2026-11-27，用户四步一次性授权）**：发布点 `d7ff30a7`（分支 `codex/v2.0.2`）、tag `v2.0.2`、master 快进对齐、GitHub Release 双 APK（发布后 SHA-256 复核一致）；K60 自 2.0.1(8) 升级 E2E ✅（数据保留、四页渲染、FATAL/ANR 空、更新页「已是最新」口径正确）；顺带两小卡销项（守卫 `a437931b` / FilterChip×3 `c72c93ea`） | `docs/V202_TASKS.md` · `docs/V202_DESIGN.md` |
| 体验修复 → 2.0.3 | 🟢 **已发布（2026-11-28，用户授权）**：发布点 `0735849c`、master 先对齐后推送（`d7ff30a7..0735849c`）、tag `v2.0.3`、GitHub Release 双 APK；11 bug 全修 + QA 返修 F1–F6；**Pad 砖态终验 ✅**（死锁态 2.0.2→2.0.3 覆盖后起播成功）、K60 升级 E2E ✅ | `docs/V203_TASKS.md` · `RELEASE_NOTES_v2.0.3.md` |

## 4. 当前焦点（2026-11-28）

1. **2.0.3 已发布（2026-11-28，用户授权）**：发布点 `0735849c`（分支 `codex/v2.0.3`）；发布纪律执行（本地 master 先对齐发布点再推送 `d7ff30a7..0735849c`）+ tag `v2.0.3` + GitHub Release 双 APK；发布门绿（assembleLibreRelease 7m24s + verifyMpvJniReleaseContract PASS + 全模块单测 0 败 + ktfmtCheck）；**Pad 砖态终验 ✅**（43af8627 自死锁态 2.0.2(9) 覆盖 2.0.3(10) 后起播成功：mpv 出帧 + PLAYING 持续）；K60 升级 E2E ✅（数据保留、首页渲染、crash 空）；`RELEASE_PLAN.md` §7 已回写。11 项用户实测 bug（更新无反应/首页评分/删除编辑入口/详情按钮/播放顺序/⋮大小/音乐双菜单/返回回顶/字幕面板/垫层重叠/切内核死锁）全修，另找茬复审修 5 P1 + 真机揪 VideoScreen ⋮ 漏网（详见 `docs/V203_TASKS.md` 状态块）。
2. **留档观察项**：字幕行新布局未在有字幕片实轨目视；P3-2 循环档位徽标/P3-5 音乐子菜单返回语义未做；core `DownloaderImplRecoveryTest` 2 条并行偶发（单跑绿）；测试服 TLS `-0x7280` 降级观察；K60 并存旧 debug 包待裁定；2.0.2 及以下版本的更新器有 bug（下载链自愈 2.0.3 才修好），**2.0.0–2.0.2 用户收不到 2.0.3 的应用内更新，需手动装一次 2.0.3**——后续版本起更新链恢复正常。
3. **环境事实（新增）**：**子代理平台会整体故障**（2026-11-27 约 1 小时，派单/resume 秒挂 `[object Object]`，worktree 磁盘现场不丢，恢复后 resume 可续跑收口）；**并行 worktree 构建共享 GRADLE_USER_HOME 会互踩**（daemon 被外部 stop、页面文件 1455，重跑/`--no-daemon` 即恢复）；**MIUI(Pad) USB 安装会拦 `INSTALL_FAILED_USER_RESTRICTED`——开「开发者选项→USB 安装」后即通**；uiautomator dump 的 XML 是单行，统计用 `grep -o | wc -l`；`adb` 路径在 Git Bash 下要 `export MSYS2_ARG_CONV_EXCL='*'`。其余同 2.0.2 行（install -r 定向、testLibreDebugUnitTest 口径、ktfmtFormat、--rerun-tasks、MIUI 边缘 24dp、PlaylistItemId、播放器容器高、@AfterClass 还原、databinding 判死）。

### 下会话开工提示词

```
1. 角色与工作区（固定一行）：你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
   工作区：F:\Develop\codex_work\project\Aurorama（分支 codex/v2.0.3）。
2. 开工读序（固定）：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V203_TASKS.md 状态块
   → 记忆库 MEMORY.md（读序外文件按需，不通读）。
3. 现状（≤4 行）：HEAD 以 git log 为准；2.0.3 已发布（2026-11-28）：tag v2.0.3 @0735849c、
   master 对齐推送、Release 双 APK、Pad 砖态终验与 K60 升级 E2E 全绿；
   留档=字幕行实轨目视/P3-2/P3-5（见 V203_TASKS 观察项）。
4. 本会话任务（1–2 卡）：【槽：新需求按内容归属插卡；候选=留档小卡（死资源 12 个删除+回归）、
   2.0.3 用户反馈新发现】。
5. 设计分权（固定一行）：设计以 docs/V202_DESIGN.md + docs/design/COLOR_ICON_INVENTORY.md 为准，deepseek 零设计权。
6. 门禁与纪律（固定引用）：见 AGENTS.md 质量门/红线/发布纪律；adb 取证派子代理，负责人复核证据关键行；
   MIUI(Pad) USB 安装需设备上确认；子代理平台故障时 worktree 现场不丢、恢复后 resume 续跑。
7. 交接（固定一行）：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
