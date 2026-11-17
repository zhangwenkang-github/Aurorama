# FIX_PLAN · 维护/缺陷修复线（精简版）

> 2026-11-17 文档治理重组：完整历史（W73–W90 全程、#1–#55 缺陷/需求登记、Q1–Q11 决策记录）见
> `docs/archive/2026-11/FIX_PLAN.full-20261117.md`（276 KB，按需读取）。
> 本文件只维护**当前状态 + 开放 backlog + 最近日志**。

## 当前状态（2026-11-18）

- **维护线已完结**：v1.1.0–v1.4.1 全部发布（GitHub Releases + 双 APK）；最新 **v1.4.1**（2026-11-17，master `76f5350b`、tag `v1.4.1`）。
- **1.4.0**（2026-11-02 发布）：W84–W90-A2 全部集成；tag `v1.4.0` + GitHub Release；发布说明 `docs/RELEASE_NOTES_v1.4.0.md` 已入库。
- **1.4.1**（2026-11-17 发布）：三个播放器修复——起播静默悬挂 + 假「定位超时」（`12ef174e`）、起播源错误无限静默转圈（`6cab56a9`）、跨集切换静默回退上一集（`76f5350b`）。
- 维护线后续缺陷与增强转入 2.0.0 波次（`docs/V2_REQUIREMENTS.md` 账本），本线不再开新波。

## 开放 backlog（转入 2.0.0 需求账本管理）

| # | 摘要 | 级别 |
|---|------|------|
| B13 | 音乐补队列逐首发 PlaybackInfo，满库补齐分钟级；评估懒补队列/并发 | P3 |
| B14 | EPUB 滚动档滚到底不自动进下一章（Readium 语义，产品决策待定） | 决策 |
| B16 | 阅读器亮度手势不落偏好（与播放器口径不同） | P3 |
| B17 | App 主动检测通知开关并引导授权（用户建议） | P2 |
| B18 | debug/release 首页「继续观看」进度不同步 | 待复核 |
| B19 | 书籍批量下载 `DownloadSkipped` 文案死分支（置灰即反馈 or Snackbar） | P3 |
| B20 | 书籍下载失败断网/DNS 文案为 OkHttp 英文原文 | P3 |
| B4 | `PlayerControlOverlay.kt`（~2.4k 行）/ `NavigationRoot.kt`（~2.2k 行）重构波 → 已由 2.0 W2 播放器架构重构覆盖 | 待排期 |
| B6 | 冷启动中位 ~1394 ms 超阈值观察项 | 待定 |
| B7 | mpv 下 `media_session` speed/position 恒 0/-1（既有） | P3 |
| Q8 | release 上 mini-player 入口疑似不开播放页（debug/release 对照复核） | 待复核 |

> 已关闭：B15（书籍整本下载已有持久 `bookDownloadTasks`，剩余口径见 V2 `L14-14/23/27`）、B21（书籍任务已并入侧栏角标聚合，见 V2 `L14-19`）；Q1–Q11、B1–B12 已修复或关闭（见归档版）。

## 最近日志

- 2026-11-17/18：v1.4.1 发布（master `76f5350b`、tag `v1.4.1`、GitHub Release 双 APK）；维护线关闭，开放 backlog 转 2.0.0 需求账本。
- 2026-11-17：文档治理重组（状态块精简 + 2026-11 历史归档，本文件瘦身）。
- 2026-11-02：v1.4.0 发布（tag `v1.4.0` + GitHub Release 双 APK）；W90-A2 集成 CI 三绿。
- 2026-11-01：搜索 fixture r6/r7/r8 合同链与设备复核 PASS；HyperOS 安装弹窗口径确认（adb install + AdbInstallActivity 并发轮询）。
- 2026-10-31：Download GET / `.finalizing` / 完成时序修复实现收口（见 `PLAYER_PLAN.md` §49–§51 归档）。
