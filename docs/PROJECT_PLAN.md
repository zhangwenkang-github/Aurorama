# Cinefin · 项目总览

> **新会话第一步读这里**，再读所在波次/线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-27　分支 `codex/v2.0.2`

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
| UI 重构/热修 → 2.0.2 | 🟢 **代码面收官（2026-11-27，分支 `codex/v2.0.2`，HEAD `7a1dafbe`）**：**W0–C9 全卡 DONE**——C1–C7+C5b（U1 热修/XML 主题清零/安全区统一/黑底清理/黄橙清零/残项清零/音乐头部/书籍库头部）+ C8 D6 台账（`docs/design/COLOR_ICON_INVENTORY.md`，留档裁定全执行+漏网 31 处归极光）+ C9 回归（media.* 交互态清零、发布门全绿、K60 四主题/7 态/像素取证达标、D1–D5 对账表回写）；**测试服 TLS `-0x7280` C9 复测未复现，降级观察**；**发布（并 master→bump→tag→Release）待用户授权** | `docs/V202_TASKS.md` · `docs/V202_DESIGN.md` |

## 4. 当前焦点（2026-11-27）

1. **2.0.2 代码面收官（分支 `codex/v2.0.2`，HEAD `7a1dafbe`）**：**W0–C9 全卡 DONE**——C1–C7+C5b（详见 V202_TASKS 卡行）；C8 D6 台账 `9422ddeb`/`e2500d81`/`eb174d31`/`ef7ee573`：`docs/design/COLOR_ICON_INVENTORY.md` 落地（43 token 槽+137 图标+23 条残留登记），留档裁定全执行（安全区×3/规则外点位/死资源/双层留白×7），台账审计揪出漏网 31 处交互态 media.* 全归极光；C9 `a194b73a`/`4addc350`/`7a1dafbe`：media.* 交互态清零（仅剩身份位 4+静态徽标 1+书页标注 1+白名单）、阅读器进度归极光、发布门全绿（assembleRelease+MPV JNI 合约 PASS+全模块 2987 单测 0 败）、K60 四主题/7 态/像素取证达标（琥珀 0.000%、浅色对比 5.16:1 AA）、D1–D5 对账表回写。**发布待用户授权**（纪律：先并 master 对齐→bump→tag `v2.0.2`→Release→K60 升级 E2E）。
2. **测试服 TLS `-0x7280`：C9 复测未复现**（播放 00:12/24:25 在走 + 17.4MB FLAC 下载全通），KNOWN_ISSUE 降级观察。
3. **2.0.1 已发布（2026-11-26，用户授权）**：tag `v2.0.1`（@`d463e64b`）+ master 对齐 `312209a6` + GitHub Release 双 APK（SHA-256 复核一致）；K60 升级 E2E ✅。
4. **留档观察项**：R11 破坏性删测试服条目未授权跳过；KNOWN_ISSUE=K60 首播偶发卡 BUFFERING 重进即恢复、测试服高码率转码能力不足（服务端）；M3 FilterChip 残留×3 与死资源 12 个删除列后续小卡（详见 V202_TASKS 留档行）。
5. **环境事实**：多设备在线装机用 `assembleLibreDebug` + `adb -s <id> install -r`；**Android 15 禁止 release 包同包降级**（`install -d` 仅对 debuggable 生效，真机升级 E2E 只能前向或用 debug 包等效）；全模块单测注意 `:core`/`:app:phone` 是 `testLibreDebugUnitTest`、`:data`/`:modes:film`/`:modes:music`/`:settings` 是 `testDebugUnitTest`；ktfmt 修正任务名是 `ktfmtFormat`（全仓任务，并发卡用「快照→格式化→只保留自己文件」隔离法）；发布门须显式加跑 `verifyMpvJniReleaseContract`；GitHub 上传双资产偶发 502 拆「先建 Release 再分次传」可解；**并发子代理共用工作区会互踩 Gradle 增量编译缓存**（等他方构建结束重跑即恢复）；**Gradle 强制重跑用 `--rerun-tasks`（`--rerun` 对指定任务不生效）**；**MIUI 手势导航下屏幕最外侧 ~24dp 应用收不到触摸事件**；**Jellyfin 查询缺字段不报错只返回 null**；**Jellyfin 歌单条目操作必须用 PlaylistItemId 而非媒体 itemId**（误用返回 204 但不生效）；**播放器容器高≠画面区高**（W4-T22：竖屏容器下半截是常驻选集区，命中区/居中一律按画面区算或实测回传）；**测试 JVM 全局 `Dispatchers.Main` 被 Unsafe 覆写必须 @AfterClass 还原（W5 单测污染根因）**；**layout XML 判死必须查 databinding 生成类（文件名 grep 不到 ≠ 死资源，C8b 教训）**；设备当前登录 jellyfin-test 可写测试服（生产服写验证一律先 dump 抽屉确认服务器）。

### 下会话开工提示词

```
1. 角色与工作区（固定一行）：你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
   工作区：F:/Develop/code_data/work_tree/Aurorama/v2-merge-141（分支 codex/v2.0.2）。
2. 开工读序（固定）：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V202_TASKS.md 状态块
   → 记忆库 MEMORY.md（读序外文件按需，不通读）。
3. 现状（≤4 行）：HEAD 以 git log 为准；W0–C9 全卡 DONE（media.* 交互态清零、发布门全绿、
   K60 四主题/7 态/像素取证达标、对账表回写；测试服 TLS -0x7280 复测未复现降级观察）；
   发布（并 master→bump→tag→Release）待用户授权。
4. 本会话任务（按额度 1–N 卡）：【槽：R1 发布卡=并 master 对齐→版本 bump→tag v2.0.2→GitHub
   Release→K60 升级 E2E（逐步需用户授权）；顺带 ReaderControlLayer 防回退守卫；余量可做
   M3 FilterChip 统一小卡（SearchBar/LyricsSheet/MusicEffectsSheet→CinefinFilterChip）】。
5. 设计分权（固定一行）：设计以 docs/V202_DESIGN.md + docs/design/COLOR_ICON_INVENTORY.md 为准，deepseek 零设计权。
6. 门禁与纪律（固定引用）：见 AGENTS.md 质量门/红线/发布纪律（发布前先并 master）；adb 取证派子代理，负责人复核证据关键行。
7. 交接（固定一行）：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
