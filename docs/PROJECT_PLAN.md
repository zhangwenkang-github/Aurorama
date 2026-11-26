# Cinefin · 项目总览

> **新会话第一步读这里**，再读所在波次/线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-26　分支 `codex/v2.0.1`

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
| 维护/缺陷修复 → 2.0.1 | 🟡 **W5 收口就绪待发布**（2026-11-26）：分支 `codex/v2.0.1` HEAD=`f589fad4`；LATER 池 23 项裁定、T24 清理卡 DONE、回归四组全绿、发布门 5/5 绿（单测 3583/0 + 签名双 APK + JNI 契约 + 验签指纹一致 + 双机覆盖冒烟）；版本 2.0.1/code 8；余 push/tag/Release（待用户授权）+ 发布后升级 E2E | `docs/V201_TASKS.md` |

## 4. 当前焦点（2026-11-26）

1. **2.0.1（分支 `codex/v2.0.1`）**：W0–W5 全部收官，**发布就绪待授权**——LATER 池 23 项裁定回写；T24 清理卡 DONE（`74762dc2`+`941b7d57`）；全量回归四组全绿（`qa/w5-regression/`）；发布门 5/5 绿（10 模块单测 3583/0/0/0、ktfmt、签名双 APK arm64 78.92MiB/universal 169.14MiB、`verifyMpvJniReleaseContract` 8c/41m、验签指纹 `e449c4aa…e155ff`、双机覆盖冒烟 0 FATAL/ANR，`qa/w5-release/`）；HEAD=`f589fad4`（版本 2.0.1/code 8 在 `fa5eed4e`）。**余：push+tag `v2.0.1`+GitHub Release（用户授权）→ 发布后验证（资产 SHA-256 复核 + K60 应用内升级 E2E）→ RELEASE_PLAN §6 补行 → 全文档收口**。台账 `docs/V201_TASKS.md`。
2. **W0 已收官（2026-11-24）**：分支+台账+AGENTS.md；W0-D1 设计审计（22 条偏差）；W0-D2 设计定稿；W0-D3 master 对齐 v2.0.0（`312209a6`，远端推送待授权，随 2.0.1 发布一并推）。
3. **W1 修复快线已收官（2026-11-25）**：T01–T08 全 DONE（更新链路/管理员入口×2/logo/图片缓存/静默刷新/多选下移/跨季误下载/搜索音乐导航）。
4. **W2 设计基础已收官（2026-11-25）**：T09 主题还原三批全 DONE（批① `0d3aa49a`+`b17eaa24`；批② `c9cb41c9`；批③ `cd9d6ad0`）；T10 图标规范 `78df3ed7`；T11 抽屉左缘热区 `e3bafa2e`；T12 滑杆垂直手势优先 `ddf91d75`；T13 设置面板去重 `22988cf2`（串流码率无宿主暂留→T23）。
5. **W3 音乐重塑全波次已收官（2026-11-25）**：a 组 T14 `8aa7d705`+`2cd7f0cd`、T15 `00eb1cdf`+`748d76c0`、T16 `5efbf784`；T17 `4e391eb5`+`8c283888`（点单曲=追加队尾并起播、快照就近迁移）；b 组 T18 `6e71a8b8`、T20 `f7c1e8b2`、T19 `d29bd6b0`（修 PlaylistItemId 误用 bug）；K60/Pad 取证全绿（`F:/Develop/code_data/qa/w3-t14..t20/`）。
6. **W4 播放器收尾全波次已收官（2026-11-26）**：T21 `8d5f8213`、T22 `4b7392c4`（竖屏锁定解锁根因=容器高≠画面区中点）、T23 `29f3efdc`（码率迁设置页，底栏 6→5 键）。
7. **W5 回归裁定留档**：「关自动旋转横置不转」需用户手动验一次（红线内未验，SCREEN_ORIENTATION_USER 契约兜底）；R11 破坏性删测试服条目未授权跳过（单测作证）；R-B1「搜索已下载分区行只起播不开详情」= T08 定稿刻意口径非缺陷；KNOWN_ISSUE 观察=K60 首播偶发卡 BUFFERING 重进即恢复、测试服高码率转码能力不足（服务端）。
8. **环境事实**：多设备在线装机用 `assembleLibreDebug` + `adb -s <id> install -r`；全模块单测注意 `:core`/`:app:phone` 是 `testLibreDebugUnitTest`、`:data`/`:modes:film`/`:modes:music`/`:settings` 是 `testDebugUnitTest`；ktfmt 修正任务名是 `ktfmtFormat`（全仓任务，并发卡用「快照→格式化→只保留自己文件」隔离法）；发布门须显式加跑 `verifyMpvJniReleaseContract`；GitHub 上传双资产偶发 502 拆「先建 Release 再分次传」可解；**并发子代理共用工作区会互踩 Gradle 增量编译缓存**（等他方构建结束重跑即恢复）；**Gradle 强制重跑用 `--rerun-tasks`（`--rerun` 对指定任务不生效）**；**MIUI 手势导航下屏幕最外侧 ~24dp 应用收不到触摸事件**；**Jellyfin 查询缺字段不报错只返回 null**；**Jellyfin 歌单条目操作必须用 PlaylistItemId 而非媒体 itemId**（误用返回 204 但不生效）；**播放器容器高≠画面区高**（W4-T22：竖屏容器下半截是常驻选集区，命中区/居中一律按画面区算或实测回传）；**测试 JVM 全局 `Dispatchers.Main` 被 Unsafe 覆写必须 @AfterClass 还原（W5 单测污染根因）**；设备当前登录 jellyfin-test 可写测试服（生产服写验证一律先 dump 抽屉确认服务器）。

### 下会话开工提示词

```
1. 角色与工作区（固定一行）：你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
   工作区：F:/Develop/code_data/work_tree/Aurorama/v2-merge-141（分支 codex/v2.0.1）。
2. 开工读序（固定）：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V201_TASKS.md 状态块
   → 记忆库 MEMORY.md（读序外文件按需，不通读）。
3. 现状（≤4 行）：HEAD 以 git log 为准；W0–W5 全收官，发布门 5/5 绿（单测 3583/0、签名双 APK、
   JNI 契约、验签指纹 e449c4aa…e155ff、双机覆盖冒烟），版本 2.0.1/code 8；
   master=`312209a6`；签名双 APK 在 app/phone/build/outputs/apk/libre/release/（构建于 fa5eed4e，
   HEAD 仅多一个测试代码提交 f589fad4，产品代码一致）。
4. 本会话任务（1–2 卡）：发布执行——【取得用户授权后】push codex/v2.0.1 + master、
   打 tag v2.0.1、建 GitHub Release 传双 APK（正文=docs/RELEASE_NOTES_v2.0.1.md；
   502 则先建 Release 再分次传）→ 发布后验证（下载资产 SHA-256 复核本地构建；
   K60 应用内 2.0.0→2.0.1 升级 E2E：检查更新→AVAILABLE→更新说明渲染→下载→安装；
   需先在 K60 装回 release 2.0.0）→ RELEASE_PLAN.md §6 补 2.0.1 行 → 台账/PROJECT_PLAN 收口
   【spike 时限：升级 E2E 若 GitHub Release 上架后 App 轮询不到，查明原因再处理，不硬改】。
5. 设计分权（固定一行）：设计以 docs/V201_DESIGN_FINAL.md + docs/V2_DESIGN.md §3 为准，deepseek 零设计权。
6. 门禁与纪律（固定引用）：见 AGENTS.md 质量门/红线；adb 取证派子代理，负责人复核证据关键行。
   【「关自动旋转横置不转」可请用户手动关自动旋转验一次（勿用 adb 改系统设置）】
7. 交接（固定一行）：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
