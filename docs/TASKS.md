# Aurorama 2.0 任务看板

> 只维护当前可执行任务。需求全文、决策理由和进度日志分别放 `V2_PLAN`、`DECISIONS`、ADR。

## In Progress

| ID | 需求域 | 目标 | 依赖 | 当前退出条件 |
|---|---|---|---|---|
| `V2-REQ-UX` | 第二章 | 收敛视觉基线、结构、参考、Skill/MCP 和工作流要求 | `V2-REQ-ARCH` | 手机 / 平板视觉方向通过独立 QA `PASS_WITH_GAPS`（P0/P1/P2/P3=`0/0/3/1`），状态为 `VISUAL_DIRECTION_READY_FOR_USER_CONFIRMATION_WITH_GAPS`；`UI_DESIGN_SYSTEM` 尚未升 V2，等待用户确认后才扩展 G1，38 条需求矩阵仍待继续 |

## Review

无。

## Todo

| ID | 需求域 | 目标 | 依赖 | 退出条件 |
|---|---|---|---|---|
| `V2-REQ-READER` | 第三章 | 收敛阅读器核心模块、交互、状态、非功能和 MVP | `V2-REQ-UX` | 395–532 行全部需求已澄清并落档 |
| `V2-REQ-WRITE` | 第四章 | 收敛服务器写能力、授权边界、协议和失败策略 | `V2-REQ-ARCH` | 533–557 行全部需求已澄清并落档 |
| `V2-REQ-MUSIC` | 第一个第五章 | 收敛音乐引擎、双队列、UI、歌词、设置、动效和验收 | `V2-REQ-ARCH` | 558–746 行全部需求已澄清并落档 |
| `V2-REQ-DOWNLOAD` | 第二个第五章 | 收敛下载提示、状态机、反馈、层级、分区和边界 | `V2-REQ-ARCH`、`V2-REQ-MUSIC` 的共享媒体身份结论 | 747–930 行全部需求已澄清并落档 |
| `V2-REQ-VIDEO` | 第六章 | 收敛双内核、功能矩阵、手势、UI、SyncPlay 和本地统一管线 | `V2-REQ-MUSIC`、`V2-REQ-DOWNLOAD` | 931–1047 行全部需求已澄清并落档 |
| `V2-REQ-NAV` | 第七章 | 收敛一级导航、媒体暴露、动效、响应式和输出要求 | `V2-REQ-UX`、前序媒体域 | 1048–1156 行全部需求已澄清并落档 |
| `V2-FINAL-CHECK` | 全量交叉检查 | 去重、冲突、依赖、范围分类、旧债矩阵和用户总确认 | 前序全部需求域 | G0 需求门通过 |
| `V2-SOLUTION-BASELINE` | G1 方案门 | 架构、模块、数据迁移、许可、Spike、UX 和验收矩阵 | G0 | 用户确认方案门 |
| `V2-IMPLEMENTATION-PLAN` | G2 开发门 | 交付波次、完整任务卡、QA/集成顺序和用户开工批准 | G1 | 用户批准开始实现 |

## Done

| ID | 成果 | 证据 |
|---|---|---|
| `V2-P0-01` | 第零章、8 项根决策、资源边界、权威文档和 ADR 基线；基线提交 `ee7ff239` 已本地进入集成分支 | `docs/V2_PLAN.md`、`PROJECT_RULES`、`LESSONS`、`DECISIONS`、ADR-0003～0005 |
| `V2-REQ-ARCH` | 引言 + 第一章 72 条需求完成归一与分级：69 `MUST` / 3 `SHOULD` / 0 `LATER` / `0 OUT`；11 组决策全部确认 | 收口提交 `426e5c01`；`docs/V2_PLAN.md` §5.2、§12、§13；`DECISIONS` `V2-D010`～`D020`；ADR-0003/0004/0006 |

## 任务卡模板

```text
ID / 身份：
目标（可观察）：
非目标（至少两项）：
验收标准（逐条执行）：
适用规则：
分支 / 集成目标：
风险与 P0–P3 边界：
证据目录：
停止条件：
```
