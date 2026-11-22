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
| 2.0.0 | 🟡 **W7 下载域进行中**（2026-11-22 会话2）：T1–T5 全部收口（`cc7b88a1`/`c038b001`/`d0b52cf5`/`c03e56cf`/`081bd69a`/`e716aede`/`13692252`/`5779ac29`，K60 取证绿，证据 `qa/w7-s2/`）；T5b 修复音乐离线播放既有缺陷；观察项登记 T6；**仅剩 T6 全量验收** | `docs/V2_REQUIREMENTS.md` · `V2_DESIGN.md` · `V2_W7_TASKS.md` · `V2_W6_TASKS.md` |
| 播放器 | 🟢 W2 架构重构完成；W4 开工（2026-11-19）：T0 现状核对 + 任务卡定稿（T1–T7）；retrySeek 跨条目重建已修（`ccdafefa`）；生产 TLS `-0x7280` 复验通过（服务器侧修复生效，K60 mpv 正常播放） | `docs/V2_W4_TASKS.md` · `docs/V2_ARCHITECTURE.md` §3 |
| 下载/离线 | 🟡 W7 进行中：T1–T5 全收口（提示层全入口/音乐 stream 端点/歌单批量/书籍状态机/展示补齐/通知直达+500ms+权限引导，K60 取证绿）；仅剩 T6 全量验收 | `docs/V2_W7_TASKS.md` · `docs/DOWNLOAD_PLAN.md` |
| 阅读器 / 音乐 / UI | 🟢 1.4.x 完成；2.0 增强分别列入 W6 / W5 / W1+W3 | `docs/V2_REQUIREMENTS.md` 各域 |
| 连接层 | 🟢 W31 完成；遗留 `fix/w31-trust-session-reuse` 分支待合并 | `docs/ARCHITECTURE.md` §5.4 |
| 投屏/同步观看 | ⛔ 投屏未开始（LATER）；SyncPlay 为 SHOULD（W4 评估） | `docs/V2_REQUIREMENTS.md` §9.5 |

## 4. 当前焦点（2026-11-22）

1. **2.0.0 W7 下载域进行中（2026-11-22 会话2）**：T1–T5 全部收口（T1 提示层 `cc7b88a1` / T1b 多选补口 `c038b001` / T2 音乐端点+歌单批量 `d0b52cf5` / T3 书籍状态机 `081bd69a` / T4 展示补齐 `e716aede` / T5 通知直达+500ms+权限引导 `13692252` / Kimi 修正 `c03e56cf` + T5b 音乐离线播放既有缺陷修复 `5779ac29`，K60 取证绿，证据 `F:\Develop\code_data\qa\w7-s2\`）；观察项登记 T6（①遗留记录重复入队 FILE_ERROR、②PENDING 滞留=W76-B9 遗留④两次复现建议立项、③书籍提示层/徽标/分区无服务端样本、⑤网格条目无 sources 多选大小恒未知；T5 未验项：视频/书籍完成通知落点、权限引导条、书籍完成通知缺失裁定）。**仅剩 T6 全量验收（Kimi 亲自）**。
2. **主仓工作区卫生已清（2026-11-18）**：v1.4.0 实际已于 2026-11-02 发布（tag + GitHub Release 均在）；master 已推送至 `d7c309f0`（发布材料入库 + docs 精简线 rebase 到 1.4.1，1.4.1 过程日志归档 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`）。

### W7 会话4 开工提示词（新会话直接粘贴；格式遵循 AGENTS.md「开工提示词统一标准」，W7 会话1 提示词已存档于 `V2_W7_TASKS.md` 状态块）

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W7_TASKS.md 状态块
→ 记忆库 MEMORY.md（读序外文件按需，不通读）。
现状：W7 会话2 已收口 T1–T5 全部实现卡（T1/T1b/T2/T3/T4/T5 + Kimi 修正与 T5b 音乐离线
播放修复，共 8 commit，K60 取证绿，证据 qa/w7-s2/，观察项与 T5 未验项见状态块）；
HEAD 以 git log 为准。
本会话任务（1 卡，Kimi 亲自）：W7-T6 全量验收收官——对照 §8 全 55 条逐条终判
（✅/⚠️/❌+证据），⚠️/❌ 裁定去向（修复卡/backlog/OUT）；观察项裁定（①遗留记录重复
入队 FILE_ERROR、②PENDING 滞留=W76-B9 遗留④建议立项修、③书籍无服务端样本、⑤多选
大小恒未知）；补验 T5 未验项（视频完成通知落点；书籍完成通知缺失裁定立项与否；
权限引导条维持单测口径）；T3/T4 无样本项（书籍暂停续传、SP 其他组、无季文案、书籍
徽标分区）按「单测+契约证据」终判或申请样本窗口。K60 全链路矩阵（提示层→入队→
暂停/续传→完成→通知直达→离线打开）后清空测试任务、还原设备。证据根
F:\Develop\code_data\qa\w7-s4\。backlog/观察项按 W9 聚合机制登记。
设计分权：设计以 docs/V2_DESIGN.md 为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线（全仓单测 + ktfmtCheck + assembleDebug 绿）；
子代理禁 adb；K60 真机取证 Kimi 做。
交接：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新（W7 收官后写 W9 聚合提示词）。
```

## 5. 文档治理与备份（细则见 `AGENTS.md`）

- 三层：常驻（本文件 ≤150 行）/ 波次与线文档（状态块在顶部，超 ~1500 行拆历史到 archive）/ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = 覆盖更新状态块，**禁止追加流水账**；进度日志只留最近几条。
- 备份：`pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。
