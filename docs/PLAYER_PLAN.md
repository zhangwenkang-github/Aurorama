# PLAYER_PLAN · 播放器线（精简版）

> 2026-11-17 文档治理重组：完整历史（§1–§51，含决策库 D1–D5x、踩坑库、W90 全程日志）见
> `docs/archive/2026-11/PLAYER_PLAN.full-20261117.md`（914 KB，按需读取）；
> 1.4.1 修复线全程过程日志（R 系列交接）见 `docs/archive/2026-11/PLAYER_PLAN.full-20261118-141.md`。
> 本文件只维护**当前状态 + 待办 + 最近日志**，新内容覆盖更新状态块，不再追加流水账。

## 当前状态（2026-11-18）

- **1.4.0 / 1.4.1 均已发布**：v1.4.0（2026-11-02，tag `v1.4.0` → `7b69e531`）；v1.4.1（2026-11-17，master `76f5350b`、tag `v1.4.1`、GitHub Release 双 APK）。
- **1.4.1 三个播放器修复**（过程日志见归档 `PLAYER_PLAN.full-20261118-141.md`）：
  - 起播静默悬挂 + 假「定位超时」→ `12ef174e`（repair silent startup hang and false seek timeout）；
  - 起播源错误无限静默转圈 → `6cab56a9`（bound startup source retries by count, not wall clock）；
  - 跨集切换静默回退上一集 → `76f5350b`（keep the progress watchdog quiet during cross-item seeks）。
- **1.4.1 已知遗留（转入 2.0 W2 根治）**：跨集切换超时后「重试」落回上一集；mpv `prepare()` 重载会话首集（F2 结构性）；`onExhausted` 不覆盖恢复被静默取消路径。
- **历史搜索 ANR P1 = ACCEPTED_RESIDUAL**（2026-11-02 裁决）：发布后未复现；若复现立即重开。
- **后续方向**：2.0 W2 播放器架构重构（单 owner / 状态机 / 引擎抽象），任务卡与进展在 `codex/v2-integration` 分支的 `docs/V2_ARCHITECTURE.md` §3 与对应波次文档维护。

## 待办（按优先级）

1. 播放器遗留打磨（历史登记，未排期）：Compact 自由窗口取证、mpv stall 不报错、回退档位按会话保留的语义取舍、无 segments/trickplay 数据的真实回归、end-帧 OFF 档服务器缓流复验。
2. 发布合规：libass / ass-kt 许可证 NOTICE；libc++ 覆盖构建补丁在依赖升级后复核。
3. `fix/w31-trust-session-reuse`（TLS 会话复用绕过校验修复）待合并；WebView 控制台 / `ImagesDownloaderWorker` 未接信任。

## 最近日志

- 2026-11-17/18：v1.4.1 发布（三播放器修复，见当前状态）；1.4.1 线过程日志归档 `PLAYER_PLAN.full-20261118-141.md`，本文件精简。
- 2026-11-02：v1.4.0 发布（tag + GitHub Release 双 APK）；Release QA 双设备 PASS_WITH_GAPS；搜索 P1 裁决 ACCEPTED_RESIDUAL；CI 三绿。证据根 `F:\Develop\code_data\qa\w90a2-*-20261102`。
- 2026-11-01：r6/r7/r8 搜索 fixture 合同链（determinism → 四 scope → 真实 SDK request-shape）与 K60 设备复核全部 PASS；34 B / 147,999 B 两次 body 差异均定位为 fixture 合同缺口（非产品缺陷）。
- 2026-10-31：Download GET（§49）、`.finalizing` orphan（§50）、删除/完成时序 P2（§51）实现收口。
