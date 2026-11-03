# Aurorama 2.0 任务看板

> 只维护当前可执行任务。需求全文、决策理由和进度日志分别放 `V2_PLAN`、`DECISIONS`、ADR。

## In Progress

| ID | 需求域 | 目标 | 依赖 | 停止条件 |
|---|---|---|---|---|
| `V2-SOLUTION-BASELINE` | G1 方案门 | 建立目标架构、模块依赖、数据模型 / 迁移、协议契约、依赖许可、技术 Spike、UX 方向 / 低保真与验收矩阵，形成可确认的方案基线 | G0、G1/0、S1–S3、Q01/Q02、S4-A/B 已完成；Q03 static integration `d234b40c`、gaps integration `287c37e1` 与公共 LF 规则 `c01e7361` 已落账；BASE 最终批次 `9171494122a05dbade4e22f438489903ca0a55bd`、BASE-004 修复 `5b7841d1b84fd91d60966851f5ba6da45005a4f6` 已提交；clean clone `HEAD=5b7841d1…` 上 BASE-001 verify、BASE-003 fixture、BASE-004 双跑、S5 verifier 与 SBOM validator 均 exit 0 且 worktree clean；五组证据 75/75 LF、CRLF 0；A1/A2 与 BASE 静态合同 reviewer PASS，入口 / hash 见 `V2_PLAN` ACTIVE；前沿定格 `NOW_SAFE=0`，唯一下一步为用户选择 R1/A2/A3，或明确授权对应 B/C/D | G1 NOT_PASSED；`V2-Q-G1-03` 与 `V2-Q-G1-04` 均 Open；剩余 `B=用户视觉 Q04 / 引擎 voice / 排序 TTL 合并策略`、`C=runtime/device`、`D=production implementation`、`E=S6/G2/QA/Release 未授权`；A1/A2 gaps 与 Q03 packaged / runtime / device 未完成；静态合同与 clean-clone 机械通过不等于 implementation / runtime / device / QA / G1 Done；无用户选择不得进入 S6/G2/实现/QA/设备/Release；`UI_DESIGN_SYSTEM` 仍为 v1.0；最终文档提交 SHA 由 Git 实读，不自引用 |

## Review

无。

## Todo

| ID | 需求域 | 目标 | 依赖 | 退出条件 |
|---|---|---|---|---|
| `V2-IMPLEMENTATION-PLAN` | G2 开发门 | 交付波次、完整任务卡、QA/集成顺序和用户开工批准 | G1 完整收口 + 用户明确解除 E 门；当前不得启动 | 用户批准开始实现 |

## Done

| ID | 成果 | 证据 |
|---|---|---|
| `V2-P0-01` | 第零章、8 项根决策、资源边界、权威文档和 ADR 基线；基线提交 `ee7ff239` 已本地进入集成分支 | `docs/V2_PLAN.md`、`PROJECT_RULES`、`LESSONS`、`DECISIONS`、ADR-0003～0005 |
| `V2-REQ-ARCH` | 引言 + 第一章 72 条需求完成归一与分级：69 `MUST` / 3 `SHOULD` / 0 `LATER` / `0 OUT`；11 组决策全部确认 | 收口提交 `426e5c01`；`docs/V2_PLAN.md` §5.2、§12、§13；`DECISIONS` `V2-D010`～`D020`；ADR-0003/0004/0006 |
| `V2-REQ-UX` | 第二章 38 条最终为 `21 MUST / 3 SHOULD / 2 LATER / 12 OUT`；一级 / 二级页面、首页边界、五档响应式、七类状态、选择性图标词表、五主题约束和证据口径完成；用户于 2026-11-02 接受整包 | 建议包提交 `c22963b5`；`docs/V2_PLAN.md` §5.3.15～§5.3.17、§12、§13；`DECISIONS` `V2-D081`；`V2-Q-UX-60` 已关闭 |
| `V2-REQ-READER` | 第三章 86 条最终为 63 单级 `MUST` / 9 单级 `SHOULD` / 7 `LATER` / 3 `OUT` / 3 复合节点 / 1 流程节点；MVP 66。`READER-056` 由 D197 确认为字段 `MUST` / 导出 `LATER` 的第三复合；跨格式阅读、批注、搜索、TTS、SAF、性能、手势、TXT 和证据口径完成 | 第三轮提交 `df778c9b`；`docs/V2_PLAN.md` §5.4.5～§5.4.10、§5.10、§12、§13；`DECISIONS` `V2-D082`～`D098`、`D197`；`V2-Q-READER-01`～`17` 全部关闭 |
| `V2-REQ-WRITE` | 第四章 13 个 source ID 最终为 11 单级 `MUST` + 1 复合 `MUST/LATER` + 1 验收流程节点；其中 `WRITE-011` 是 `WRITE-005` 的证据别名，不作为第二个独立产品功能；用户级写 MVP、权限三态、写状态机、失败分类、危险确认、多服务器隔离和 `W-D`～`W-U` 证据口径完成；用户于 2026-11-03 接受全部推荐 | `docs/V2_PLAN.md` §5.5、§5.10、§12、§13；`DECISIONS` `V2-D099`～`D105`；`V2-Q-WRITE-01`～`06` 全部关闭 |
| `V2-REQ-MUSIC` | 第五章音乐 62 个 source ID 最终为 47 单级 `MUST` / 5 单级 `SHOULD` / 2 单级 `LATER` / 5 单级 `OUT` / 2 复合节点 / 1 流程节点；MVP 50。`MUSIC-051` 由 D197 后决归一为 `MUST/MUST`，`MUSIC-018` 的一次重试谓词由 D194 固定；`M-D`～`M-T` 证据与全部冲突处置完成 | 收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.6、§5.10、§12、§13；`DECISIONS` `V2-D106`～`D127`、`D194`、`D197`；`V2-Q-MUSIC-01`～`22` 与 `V2-Q-FINAL-01` 全部关闭 |
| `V2-REQ-DOWNLOAD` | 第二个第五章下载 68 节点最终为 62 单级 `MUST` / 2 单级 `SHOULD` / 0 单级 `LATER` / 1 单级 `OUT` / 2 复合节点 / 1 流程节点，MVP 为 64 个 MUST 子项；四类媒体、provenance、提示 / 存储、传输恢复、通知 / 离线库、迁移与 `D-D`～`D-T` 证据完成；用户于 2026-11-03 原话“接受整包” | 矩阵提交 `8cade229`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.7、§12、§13；`DECISIONS` `V2-D128`～`D146`；`V2-Q-DOWNLOAD-01`～`18` 与 `FINAL` 全部关闭 |
| `V2-REQ-VIDEO` | 第六章视频 97 节点最终为 86 单级 `MUST` / 2 单级 `SHOULD` / 5 单级 `LATER` / 1 单级 `OUT` / 2 复合节点 / 1 流程节点，MVP 为 88 个 MUST 子项；双内核、媒体能力、字幕、手势、SyncPlay、本地媒体、恢复、响应式、`V-*` 证据与 D1–D67 处置完成；用户于 2026-11-03 原话“整包接受” | 候选矩阵提交 `171030268b4d5401ee91ace30b35ae2206062c93`；最终确认交接 `01fdb329073597057a1928d552da1b6bbd29789b`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.8、§12、§13；`DECISIONS` `V2-D147`～`D180`；`V2-Q-VIDEO-01`～`33` 与 `FINAL` 全部关闭 |
| `V2-REQ-NAV` | 第七章 NAV 9 节点最终为 6 个单级产品节点 `4 MUST / 2 SHOULD / 0 LATER / 0 OUT` + 1 个复合 `MUST / MUST` + 2 个流程节点，MVP 为 6 个 MUST 子项；UX / HOME / RWD 去重、五条旧缺陷有主域映射、`N-*` 证据与 G1 待核验完成；用户于 2026-11-03 原话“整包确认” | 矩阵提交 `9170ce52`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.9、§12、§13；`DECISIONS` `V2-D181`～`D193`；`V2-Q-NAV-01`～`13` 全部关闭 |
| `V2-REQ-BASE` | 1.4 既有能力补充基线：`V2-REQ-BASE-001～005` 分别为搜索产品契约、应用内更新、持久 SWR、本地歌单完整管理、音乐懒补队列 / 写放大；五组均为 MUST，已有来源、解读、可观察验收、依赖、非目标与 G1 未决项 | `docs/V2_PLAN.md` §5.10.5 与 ACTIVE；`DECISIONS` `V2-D196`、`V2-D197`；旧债 `L14-02/16/17/18/33` 已 MAPPED；BASE-001/003/004 静态合同已冻结并独立 reviewer PASS；最终批次 `9171494122a05dbade4e22f438489903ca0a55bd`、BASE-004 确定性修复 `5b7841d1b84fd91d60966851f5ba6da45005a4f6`，clean clone 双跑 exit 0 且 clean；BASE-004 为 24 文件 / 115,629 B，manifest SHA-256 `755368ADF03E5D4FBA500B7F94B7E240486D2111415AC86E9F8608F9DC49A8D8`，24/24 LF；仅表示合同输入与机械复现就绪，B/C/D、runtime、device、implementation 与 QA 未进入 Done |
| `V2-FINAL-CHECK` | 完整 G0 PASS：外部 `1–1156` 全覆盖，三项产品问题关闭，外部八域 `445` 个 source ID、`376` 个 MVP 子项，另有五个既有能力 MUST 组；`MUSIC 49→50`、`READER 65→66` | 用户于 2026-11-03 19:43:40 +08:00（记录时间）原话“1、2、3 均按推荐；接受完整 G0，包含 MUSIC 49→50 与 READER 65→66，按此收口。”；`docs/V2_PLAN.md` §5.10、§12、§13；`DECISIONS` `V2-D194`～`D197`；结构检查见 §13 |

## 任务卡模板

```text
ID / 身份：
目标（可观察）：
非目标（至少两项）：
验收标准（逐条执行）：
适用规则：
分支 / 集成目标：
接口 / 文件边界：
依赖：
测试命令 / 证据：
风险与 P0–P3 边界：
证据目录：
停止条件：
```
