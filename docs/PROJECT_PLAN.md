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
| 2.0.0 | 🟢 **W9 NOW 卡全部收口**（2026-11-24 会话14：T8 裁定落档、T8b=`532e6f22`、孤儿对账=`28c95fcc` K60 dry-run 命中 3 孤儿 43.5MB、抽查窗口 ③④/T6b 手势全绿）；**距发布仅剩 T9 回归发布**（矩阵草案已入 T9 卡） | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · `V2_W9_TASKS.md` · `V2_W8_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟢 W7 已收官（2026-11-22）：55 条 = ✅51/⚠️4/❌0；修复卡 F1b 音乐重下载删档绕过 / F2 PENDING 滞留 / F4 音乐离线冷路径 / F5 书籍通知 / F6 作者折叠 / F7 引擎空转热循环，全登记 W9 聚合（F1 引擎层与 F3 已修销项） | `docs/V2_W7_TASKS.md` · `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-24）

1. **W9 NOW 卡全部清空（2026-11-24 会话14，全程子代理实现/取证）**：T8 裁定落档（W4–W6 backlog 补裁定 NOW/LATER/DROP + 账户设置域裁剪「不新增功能只收口反馈类缺陷」+ 账户切换缺陷归因修正，详见 `V2_W9_TASKS.md` 裁定登记顶部两条）；T8b=`532e6f22`（切账号 stale/RECOVERING 静默分支接入既有 activationFailure 行内红字，setup 单测 4/4）；孤儿对账=`28c95fcc`（core `DownloadIndexReconciler` 三分类 + 下载页「下载索引对账」入口 + 显式二次确认清理开关，15 单测；K60 dry-run 命中 3 孤儿 43.5MB 与磁盘/sources 表逐字节吻合，零写入）；K60 抽查窗口（③snackbar✅、401 行内红字✅、④行为点验✅弱、T6b 360dp 横滚手势✅残项销项；⑩改判=删本地下载不触发 contentVersion 不能作证，真证需授权删测试服条目，单测作证不阻塞）。**距发布仅剩 T9**。
2. **T9 开工前两件 fixture 终验（随回归窗口做）**：①孤儿清理真机终验——K60 现留 3 孤儿作 fixture，验后即清（预期 70.6→27.1MB）；②⑩ 季页计数真机证——需用户授权在测试服删一条服务器条目（破坏性）。
3. **新环境事实**：Gradle `installLibreDebug` 会安装到**所有已连设备**（会话14 误装 Pad 一次）——多设备在线改 `assembleLibreDebug` + `adb -s <id> install -r` 定向安装；K60 触摸注入已恢复（会话13 失效为暂时性）。
4. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W9 会话15 开工提示词（新会话直接粘贴；只含文档没有的引导，事实一律看状态块）

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W9_TASKS.md 状态块
→ 记忆库 MEMORY.md（读序外文件按需，不通读；设备/取证纪律以状态块为准）。
现状：HEAD 以 git log 为准；W9 NOW 卡全部收口，仅剩 T9。
本会话任务（1–2 卡）：W9-T9 全域回归 + 发布（Kimi 主导；
回归矩阵草案已在 V2_W9_TASKS.md T9 卡，开工先定稿）；
回归窗口顺手两件 fixture 终验：K60 孤儿清理（3 个已保留，验后即清）
+ ⑩ 季页计数（须先取得用户授权删测试服一条服务器条目）。
设计分权：设计以 docs/V2_DESIGN.md 为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线；adb 取证下放子代理（会话13 起用户拍板）；
多设备在线时装机用 assembleLibreDebug + adb -s 定向安装；
push/tag/GitHub Release 需用户当场授权。
交接：收口按 AGENTS.md 五件事；提示词只写文档没有的引导，不重复状态块内容。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
