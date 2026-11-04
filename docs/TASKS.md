# Aurorama 2.0 任务看板

> 只维护当前可执行任务。需求全文、决策理由和进度日志分别放 `V2_PLAN`、`DECISIONS`、ADR。

## In Progress

| ID | 需求域 / 关系 | 目标 | 非目标（≥2） | 依赖 | 验收判据 | 停止条件 |
|---|---|---|---|---|---|---|
| `V2-SOLUTION-BASELINE` | G1 方案门；总括行，非执行卡 | 建立目标架构、模块依赖、数据模型 / 迁移、协议契约、依赖许可、技术 Spike、UX 方向 / 低保真与验收矩阵，形成可确认的方案基线 | 不自行启动 runtime / 设备 / 新网络 / 服务器 / 生产代码 / G2 / 独立 QA / Release；不把 S6 历史 `PASS_WITH_P2`、network review 或任一静态证据写成当前 S6 re-approval / G1 PASS | `V2-D202` 仅授权 `L1`；D203 voice-pack / 后台锁屏条款保持；`V2-D204` 接受 engine strategy A 并关闭 Q05，Sherpa / Matcha 仅兼容回退，第二引擎与 exact profile 未选。当前 `HIGH_EXPRESSIVE_SECOND_ENGINE_L1_SPIKE_SCOPING / C1_EXPRESSIVE_ENGINE_PROFILE_OPEN`；S6 需 D204 后 fresh scoped reconfirm；Q03 Open、Q04 Closed/D201；L2/L3 locked | G1 总体只在第二引擎静态可行性 / 许可门、exact profile / 许可 / notice / hash、Q03 closure、后台 / 锁屏 runtime 证据与完整闭包均通过后收口；当前总括行不得代签 | 任何新域名 / voice-pack 下载、把 D204-A 解释为具体引擎已选、越权设备 / 账号 / 服务器 / runtime / 生产代码 / G2 / QA / Release、Q03 / Q05 / G1 状态漂移、证据不可复算时立即停止 |
| `V2-G1-L1-EVIDENCE` | G1 `L1`；状态 `HIGH_EXPRESSIVE_SECOND_ENGINE_L1_SPIKE_SCOPING / C1_EXPRESSIVE_ENGINE_PROFILE_OPEN` | 在 D203 voice-pack / 后台锁屏静态边界和 D204 engine strategy A 上，只读 / 静态设计高表现力第二引擎 Android arm64 可行性 Spike、官方 runtime 与逐资产许可 / notice / hash 门 | ①网络 allowlist 已结束，任何新域名或 engine / voice-pack / profile 下载须另行授权；不得接触设备 / 账号 / 服务器或修改生产代码；②不启动 runtime、Gradle / ADB、独立 QA、G2 或 Release；③不把 D204-A 解释为具体引擎已选，不冻结 IndexTTS / CosyVoice / GPT-SoVITS / Matcha / Kokoro profile，不关闭 Q03，不把静态 / Spike 结果写成 G1 PASS | **当前 readiness = SECOND_ENGINE_L1_SPIKE_SCOPING_AND_LICENSE_GATE_OPEN（READ_ONLY_STATIC_ONLY）**；`V2-D202` + D203 voice-pack / 后台条款 + `V2-D204` Accepted + Q05 Closed/D204；`SHERPA_QUALITY_INSUFFICIENT`；四类候选均无官方 Android arm64 runtime 证据；Matcha zh-en 可兼容回退补样、Baker 非商业；Kokoro sid58 否决但 family 未整体定性；CosyVoice 2/3 许可页面冲突待解析；`L2/L3 LOCKED`；Q03 Open；Q04 Closed/D201；runtime remain open | 证据可本地复算；voice-pack import append / idempotency / delete fallback / hidden restore 与后台 / 锁屏 / MediaSession 静态合同保持；第二 engine、exact profile、逐资产许可 / notice / hash 与 runtime 均未冻结；用户主观质量门仍未过 | 需要任何新域名 / engine / profile download、设备 / 账号 / 服务器、生产代码或 runtime；重开 D204、把 A 写成具体引擎已选、申请 L2/L3 或关闭 Q03；出现 P0–P2、范围漂移、hash / manifest 不可复算时停止并请求明确授权 |

## Review

- `V2-G1-L1-EVIDENCE`：离线证据与 network artifact 子项均有历史独立 review；Sherpa
  `PASS_WITH_P2`、PDFium `PASS` 不代表 D204 后当前状态已复核。P1 ORT notice / provenance、
  P2 source tag→commit 以及 PDFium source / notice / runtime 缺口不由此关闭；Q03 仍 Open。

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
| `V2-REQ-READER` | 外部第三章历史为 86 条：63 单级 `MUST` / 9 单级 `SHOULD` / 7 `LATER` / 3 `OUT` / 3 复合 / 1 流程，MVP 66；外部 source `86` 不变。`V2-D203` 追加 `READER-087～090` 四条项目来源 addendum 后，当前矩阵 90、单级 MUST 67、MVP 70；外部八域 source 仍 `445`、外部 MVP `376`，归一化 MVP `380`。新增 voice-pack 追加导入 / 删除回退、后台锁屏、单音频 owner / MediaSession 与全局 / 逐书音色选择 | `docs/V2_PLAN.md` §5.4.5～§5.4.10、§5.10、§5.11.12、§12、§13；`DECISIONS` `V2-D082`～`D098`、`D197`、`D203`；`TASKS` `V2-G1-L1-EVIDENCE` |
| `V2-REQ-WRITE` | 第四章 13 个 source ID 最终为 11 单级 `MUST` + 1 复合 `MUST/LATER` + 1 验收流程节点；其中 `WRITE-011` 是 `WRITE-005` 的证据别名，不作为第二个独立产品功能；用户级写 MVP、权限三态、写状态机、失败分类、危险确认、多服务器隔离和 `W-D`～`W-U` 证据口径完成；用户于 2026-11-03 接受全部推荐 | `docs/V2_PLAN.md` §5.5、§5.10、§12、§13；`DECISIONS` `V2-D099`～`D105`；`V2-Q-WRITE-01`～`06` 全部关闭 |
| `V2-REQ-MUSIC` | 第五章音乐 62 个 source ID 最终为 47 单级 `MUST` / 5 单级 `SHOULD` / 2 单级 `LATER` / 5 单级 `OUT` / 2 复合节点 / 1 流程节点；MVP 50。`MUSIC-051` 由 D197 后决归一为 `MUST/MUST`，`MUSIC-018` 的一次重试谓词由 D194 固定；`M-D`～`M-T` 证据与全部冲突处置完成 | 收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.6、§5.10、§12、§13；`DECISIONS` `V2-D106`～`D127`、`D194`、`D197`；`V2-Q-MUSIC-01`～`22` 与 `V2-Q-FINAL-01` 全部关闭 |
| `V2-REQ-DOWNLOAD` | 第二个第五章下载 68 节点最终为 62 单级 `MUST` / 2 单级 `SHOULD` / 0 单级 `LATER` / 1 单级 `OUT` / 2 复合节点 / 1 流程节点，MVP 为 64 个 MUST 子项；四类媒体、provenance、提示 / 存储、传输恢复、通知 / 离线库、迁移与 `D-D`～`D-T` 证据完成；用户于 2026-11-03 原话“接受整包” | 矩阵提交 `8cade229`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.7、§12、§13；`DECISIONS` `V2-D128`～`D146`；`V2-Q-DOWNLOAD-01`～`18` 与 `FINAL` 全部关闭 |
| `V2-REQ-VIDEO` | 第六章视频 97 节点最终为 86 单级 `MUST` / 2 单级 `SHOULD` / 5 单级 `LATER` / 1 单级 `OUT` / 2 复合节点 / 1 流程节点，MVP 为 88 个 MUST 子项；双内核、媒体能力、字幕、手势、SyncPlay、本地媒体、恢复、响应式、`V-*` 证据与 D1–D67 处置完成；用户于 2026-11-03 原话“整包接受” | 候选矩阵提交 `171030268b4d5401ee91ace30b35ae2206062c93`；最终确认交接 `01fdb329073597057a1928d552da1b6bbd29789b`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.8、§12、§13；`DECISIONS` `V2-D147`～`D180`；`V2-Q-VIDEO-01`～`33` 与 `FINAL` 全部关闭 |
| `V2-REQ-NAV` | 第七章 NAV 9 节点最终为 6 个单级产品节点 `4 MUST / 2 SHOULD / 0 LATER / 0 OUT` + 1 个复合 `MUST / MUST` + 2 个流程节点，MVP 为 6 个 MUST 子项；UX / HOME / RWD 去重、五条旧缺陷有主域映射、`N-*` 证据与 G1 待核验完成；用户于 2026-11-03 原话“整包确认” | 矩阵提交 `9170ce52`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.9、§12、§13；`DECISIONS` `V2-D181`～`D193`；`V2-Q-NAV-01`～`13` 全部关闭 |
| `V2-REQ-BASE` | 1.4 既有能力补充基线：`V2-REQ-BASE-001～005` 分别为搜索产品契约、应用内更新、持久 SWR、本地歌单完整管理、音乐懒补队列 / 写放大；五组均为 MUST，已有来源、解读、可观察验收、依赖、非目标与 G1 未决项 | `docs/V2_PLAN.md` §5.10.5 与 ACTIVE；`DECISIONS` `V2-D196`、`V2-D197`；旧债 `L14-02/16/17/18/33` 已 MAPPED；BASE-001/003/004 静态合同已冻结并独立 reviewer PASS；最终批次 `9171494122a05dbade4e22f438489903ca0a55bd`、BASE-004 确定性修复 `5b7841d1b84fd91d60966851f5ba6da45005a4f6`，clean clone 双跑 exit 0 且 clean；BASE-004 为 24 文件 / 115,629 B，manifest SHA-256 `755368ADF03E5D4FBA500B7F94B7E240486D2111415AC86E9F8608F9DC49A8D8`，24/24 LF；仅表示合同输入与机械复现就绪，B/C/D、runtime、device、implementation 与 QA 未进入 Done |
| `V2-FINAL-CHECK` | 完整 G0 PASS：外部 `1–1156` 全覆盖，三项产品问题关闭，外部八域 `445` 个 source ID、`376` 个 MVP 子项，另有五个既有能力 MUST 组；`MUSIC 49→50`、`READER 65→66` | 用户于 2026-11-03 19:43:40 +08:00（记录时间）原话“1、2、3 均按推荐；接受完整 G0，包含 MUSIC 49→50 与 READER 65→66，按此收口。”；`docs/V2_PLAN.md` §5.10、§12、§13；`DECISIONS` `V2-D194`～`D197`；结构检查见 §13 |
| `V2-Q-G1-04` | 用户接受 S5 整包 R1，Q04 Closed/Accepted；五主题 DNA 与共享骨架成为 V2 R1 视觉方向；仅 D057 Nocturne 基础圆角 12→8dp，其他主题圆角与 D057 其他内容不变 | 用户于 2026-11-04 原话“选择R1。继续下一步，注意交接规范。还是尽量不要停，尽量使用子代理。”；`DECISIONS` `V2-D201`；`.planning/v2-g1/G1-S5/decision-brief.md`、`manifest.json`；`docs/UI_DESIGN_SYSTEM.md` V2 R1；Q03、runtime/device、S6、implementation 与 QA 均未因此 Done |
| `V2-G1-S6` | G1-S6 总验收矩阵与 G2 任务卡输入完成：外部 445 source / 456 rows / 376 MVP 历史不变；D203 另列 4 个 Reader project addendum，归一化 source 449 / rows 460 / MVP 380，Reader 当前 90 / 单级 MUST 67 / MVP 70。另有 5 BASE、30 R1、16 cards、49 waves、495 memberships、560 edges、50 thresholds、风险 P0–P3=`1/19/11/2`；原始 fresh scoped review `PASS_WITH_P2` 只作为 Q05 当时 Open 的历史；D204 后 fresh scoped documentation reconfirm 最终 `PASS / no findings`，当前 `S6_SCOPED_RECONFIRM_PASS_AFTER_D204`，future artifact change 仍须重新 review | `.planning/v2-g1/G1-S6/README.md`、`CHECKPOINT.md`、`manifest.json`、`acceptance-matrix.json`、`row-semantics.json`、`wave-membership.json`、`g2-task-cards.json`、`source-index.json`；self-test live=`57 negative + 10 base + 1 计数 = 68` GREEN；README / CHECKPOINT / source-index 均同时保留历史 Q05_OPEN pass 与当前 D204 后 review PASS；通用 evidence `can_prove` 禁止未执行的选择 / Android runtime / production overclaim；READER-060 当前契约为目标 Android 完全离线本地 TTS，D204 仅在依赖中固定 A 的兼容回退 + gated 第二引擎 Spike，并拒绝主引擎等价写法；根 `.gitattributes` 自稳定且固定 DECISIONS / V2_PLAN / TASKS 为 LF；Q03 nested evidence 只陈述底层静态证据上限；唯一历史 P2 `__pycache__` 已排除；Q05 Closed/D204、Q03 Open、Sherpa / Matcha 仅兼容回退、C1 exact profile Open、L2/L3 locked、runtime UNVERIFIED、G1 NOT_PASSED；review PASS 不等于 G1、QA、runtime 或 Release PASS |
| `V2-G1-L1-NETWORK-ARTIFACTS` | G1-L1 Sherpa-ONNX / PDFium 网络 artifact 静态审计完成：Sherpa review `PASS_WITH_P2`；PDFium review `PASS`。仅完成允许的网络下载、hash / AAR / ELF / license / source 静态审计，不关闭 Q03 | `.planning/v2-g1/G1-L1-NETWORK-SHERPA/README.md`、`CHECKPOINT.md`、`manifest.json`、`manifest.sha256`；`.planning/v2-g1/G1-L1-NETWORK-PDFIUM/README.md`、`CHECKPOINT.md`、`manifest.json`、`manifest.sha256`；`docs/V2_PLAN.md` §12、§13。P1 ORT notice / provenance、P2 source tag→commit、PDFium 32-bit 4 KB / 64-bit runtime / GNU_RELRO / source / notice 与 C1/Q03 仍未关闭 |

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
