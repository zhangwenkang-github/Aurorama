# Cinefin · 项目总览

> **新会话第一步读这里**，再读所在波次/线文档的顶部状态块，最后查记忆库 `MEMORY.md`。
> 本文件只放项目级事实与各线一句话状态；过程日志与历史细节在 `docs/archive/`（按需读取，不主动加载）。
> 最后更新：2026-11-24　分支 `codex/v2-integration`

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
| 2.0.0 | 🟢 **T9 回归 R1–R11 全绿**（2026-11-24 会话15：K60 在线/断网域、孤儿清理终验 43.54MiB 已清、A3 根因钉死；Pad 写补偿/季页计数 24→23/搜索/书籍通知全绿；构建门 3463 单测 0 败+ktfmt+Release+JNI 契约全绿；新发现已裁定，搜索音乐结果导航=2.0.1 P1 首修）；**发布包装进行中**（版本 2.0.0/code 7 已 bump，签名构建→冒烟→待授权 push/tag/Release） | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · `V2_W9_TASKS.md` · `V2_W8_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 W7 已收官（2026-11-22）：55 条 = ✅51/⚠️4/❌0；修复卡 F1b 音乐重下载删档绕过 / F2 PENDING 滞留 / F4 音乐离线冷路径 / F5 书籍通知 / F6 作者折叠 / F7 引擎空转热循环，全登记 W9 聚合（F1 引擎层与 F3 已修销项） | `docs/V2_W7_TASKS.md` · `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-24）

1. **T9 回归 R1–R11 全绿（2026-11-24 会话15，全程子代理取证，证据 `F:\Develop\code_data\qa\w9-t9\`）**：K60 在线域 R1/R3/R4、断网域 R2/R8、R5 孤儿清理终验（两轮清 3 孤儿回收 43.54MiB、A3 根因=孤儿占位 rename 失败真机钉死、③snackbar 复验）、Pad R6 写补偿（三目标 4–9s 补传回读 favorite=True 且 played 未覆盖）/R11 季页计数（用户授权删《齐木楠雄的灾难》S1E1 Played=true，24→23 约 1.5s 自动刷新）/R7 搜索/R9 书籍通知直达阅读器。回归新发现已裁定（见 `V2_W9_TASKS.md` 裁定登记顶部）：**搜索音乐结果点击导航缺陷 = 2.0.1 P1 首修**，A4 徽标/A5 FAILED 同步/A6 计数/对账口径/「全部继续」/Pad 库页离线降级 = LATER，A1/A2 观察级 DROP。
2. **发布包装进行中**：构建门全绿（3463 单测 0 败 + ktfmtCheck + assembleLibreRelease + mpv JNI 契约 PASS）；版本 bump 2.0.0/code 7（`Versions.kt`）；签名发布构建（universal + 四 ABI 分包）→ apksigner 指纹核验 → 发布面真机冒烟（release 包 `install -r` 覆盖 K60/Pad）→ 回写 `RELEASE_PLAN.md` §7；**push/tag/GitHub Release 需用户当场授权**。
3. **新环境事实**：Gradle `installLibreDebug` 会安装到**所有已连设备**——多设备在线改 `assembleLibreDebug` + `adb -s <id> install -r` 定向安装；K60 触摸注入已恢复。根 `testDebugUnitTest` 不覆盖带 flavor 模块（`:core`/`:app:phone` 需 `testLibreDebugUnitTest`）；`verifyMpvJniReleaseContract` 不在 assemble 依赖链，发布门须显式加跑。
4. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布；master 已推送至 `d7c309f0`。

### W9 会话16 开工提示词（新会话直接粘贴；只含文档没有的引导，事实一律看状态块）

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W9_TASKS.md 状态块
→ 记忆库 MEMORY.md（读序外文件按需，不通读；设备/取证纪律以状态块为准）。
现状：HEAD 以 git log 为准；T9 R1–R12 全绿，2.0.0(7) 发布包装完成，
唯一剩余 = push/tag/GitHub Release（需用户当场授权）。
本会话任务（1–2 卡）：①获授权后执行 RELEASE_PLAN §5（push codex/v2-integration
+ tag v2.0.0 + GitHub Release 双 APK，资产 F:\Develop\code_data\release-assets\2.0.0\，
notes 用 docs/RELEASE_NOTES_v2.0.0.md，发布后自检：资产可下载+SHA-256 复核一致）；
②2.0.1 首修立项：搜索音乐结果点击导航缺陷（P1，裁定登记已立案）评估开工。
设计分权：设计以 docs/V2_DESIGN.md 为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线；adb 取证下放子代理；
多设备在线时装机用 assembleLibreDebug + adb -s 定向安装；
push/tag/GitHub Release 需用户当场授权。
交接：收口按 AGENTS.md 五件事；提示词只写文档没有的引导，不重复状态块内容。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
