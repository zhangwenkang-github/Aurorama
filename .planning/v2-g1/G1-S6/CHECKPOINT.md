# G1-S6 自包含 checkpoint（2026-11-04）

状态：`D204_ACCEPTED_ENGINE_STRATEGY_A / SHERPA_MATCHA_COMPAT_FALLBACK / HIGH_EXPRESSIVE_SECOND_ENGINE_L1_SPIKE_SCOPING / C1_EXPRESSIVE_ENGINE_PROFILE_OPEN / S6_RECONFIRM_REQUIRED_AFTER_D204 / G1_NOT_PASSED / Q03_OPEN / Q05_CLOSED_D204 / Q04_CLOSED_D201 / RUNTIME_UNVERIFIED / L2_L3_LOCKED`

`EV-S6.status`：`S6_RECONFIRM_REQUIRED_AFTER_D204 / HISTORICAL_SCOPED_REVIEW_PASS_WITH_P2_Q05_OPEN / RUNTIME_UNVERIFIED`；历史 `PASS_WITH_P2` 仅适用于 Q05 当时 Open 的原始 review。

## 已完成

- 已完成本轮允许范围内的静态读取与压缩取证：G0 八域最终表、五个 BASE 组、A1/A2/BASE-001/003/004、S1–S5、Q03-SBOM/NATIVE/NOTICE/TTS-PDF、当前 DECISIONS/TASKS/UI R1。
- 已生成 449 个归一化 source node，其中外部 445 + D203 addendum 4；460 条原子验收行，其中外部 456 + addendum 4；归一化 MVP 380（外部 376 + addendum 4）。复合节点未压平，流程 / LATER / OUT 保留可追踪。
- 已生成 460 条 schema v2 行语义记录；operation 由 actor / entry_surface / action_verb / action_object / expected_response 组合，MUSIC-003/014 与 NAV-004/005/006 已源特定修复，全部 action_object 元数据泄漏为 0；源中缺失的状态、无障碍与 RWD 分支显式标记 `UNSPECIFIED_IN_SOURCE`。
- 已生成 495 条独立 explicit row membership、560 条 row→wave 边；builder 只按 `wave-membership.json` join，轮转推断已删除。MUSIC-003→本地扫描、MUSIC-014→queue-recovery、NAV-005→account-select/identity W2 均已修复，NAV-004/006 保持 W2。
- 已生成 5 个 BASE MUST 组、30 条 R1 横向承诺、50 条阈值、16 张 planning umbrella、49 个可执行 delivery wave；单 wave 最大边界项 8。
- 49 个 wave 均有专业 owner、非空真实 promise_ids、1 个结构化验收物、显式 capability / entry / action tags 与具体文件 / FQCN 边界；矩阵 495 条 membership 已映射到至少一个 wave。
- card 级 `EV-S6` 自证引用为 0；对外证据保留具体证据 ID 或显式 `NO_EVIDENCE_YET` 缺口。
- 风险登记 P0/P1/P2/P3=`1/19/11/2`。
- `verify_s6_artifacts.py` 与 495 条 explicit membership、560 条边的语义 / 能力 / 入口 / 动作 / 边界门禁及 57 个负向 self-test 已在 D204 review 修复后重新生成并执行；manifest、SHA-256 与 LF / CRLF 由最终产物复算。
- `EV-S6.status` 当前为 `S6_RECONFIRM_REQUIRED_AFTER_D204 / HISTORICAL_SCOPED_REVIEW_PASS_WITH_P2_Q05_OPEN / RUNTIME_UNVERIFIED`；原始 S6 scoped review 仍为 `PASS_WITH_P2`，唯一 P2 `G1-S6/__pycache__/*.pyc` 保持排除。该历史 review 发生在 Q05 当时 Open，D204 后当前必须 fresh scoped reconfirm，不得宣告 re-approved。

## 未完成 / 不可声明

- S6 通过不等于 G1 PASS；G1 仍为 `NOT_PASSED`，runtime 仍为 `UNVERIFIED`。
- Q05 已由 D204 关闭 engine strategy，但第二引擎、exact profile / 试听 / 许可、后台锁屏零网络 runtime 仍未关闭；Q03 仍 Open，PDFium 16 KB / Range / native revision / notice、packaged SBOM / source offer、attestation / relink 均未关闭。Sherpa / Matcha 兼容回退不等于 Q03 关闭。
- 未执行实现、Gradle、ADB、设备、服务器、网络、独立 QA、Release；不得写 G1/runtime/device/QA PASS。
- Base-003 TTL / capacity / prefetch、BASE-004 重复 / 部分导入、搜索排序 / 跨源呈现等产品策略仍未由用户拍板。

## 文件指针

- `README.md`
- `acceptance-matrix.json`
- `row-semantics.json`
- `wave-membership.json`
- `thresholds.json`
- `g2-task-cards.json`
- `risk-register.json`
- `source-index.json`
- `manifest.json`
- `build_s6_artifacts.py`
- `verify_s6_artifacts.py`

## 已修正失败

- 最终 fresh reviewer 三项 P2 RED：自测曾把 negative fixture 数误当总检查数；通用 evidence `can_prove` 未阻断 chosen engine / Android runtime / production overclaim；READER-060 只识别旧 Sherpa 句式。现统一 live 计数为 57 negative + 10 base + 1 计数 = 68，并新增通用 overclaim 与三处主引擎等价注入门。
- 第二轮 fresh reviewer RED：根 `.gitattributes` 未自稳定、Q03 nested `can_prove` 越权承载 D204、EV-S6 仍以无条件 pass 作当前状态；现新增自稳定 / eol 负例、底层报告语义交叉核对与历史 Q05_OPEN 双字段门。
- 第五轮 reviewer RED：builder 按轮转分配导致 MUSIC-003 挂到 large-queue、MUSIC-014 挂 large-queue/playlist、NAV-005 挂 route/token；现改为独立 explicit membership，新增 8 个语义变异门与全量 capability mismatch 扫描，self-test 注入总数 29。
- 第四轮 reviewer RED：MUSIC-003 歌词入口错域、MUSIC-014 系统媒体控制错域、NAV-004/005/006 泛化同动作、action_object 源码/决策元数据泄漏，已转为 3 个新增真实变异门与全量扫描门；self-test 注入总数 21。
- 第三轮 reviewer RED：operation 结果包装、card `EV-S6` 自证、wave 缺 owner / promise / 结构化验收物、9 边界与悬空外部依赖均已转为 verifier 与注入负例断言。
- 第二轮 reviewer RED：32 行 `DOMAIN_SEMANTICS`、已知 4 条入口错位、旧 verifier 只检查 basis / `row_specific`、G2 大目录通配、8 条 NAV 来源数字噪声与名称门错挂 focus ring，均已转为行为断言。
- 首轮 reviewer RED：456/456 行引用 `EV-S6`、`EV-DOWNLOAD` 未定义、卡/证据 DAG 缺失、READER-057 EPUB owner 错挂 PDF、对比度统一写成 130 pair。
- READER 表含一条 `G0 状态轴`说明行；生成器已过滤非 `V2-REQ-*` 行。
- NAV 表将来源与解读合并为一列；生成器已按该表形读取。
- S6 自身证据行不自引用矩阵哈希；由 manifest 对实际文件字节统一校验。
- 无适用阈值的行使用显式 `NONE_IN_ACCEPTED_SOURCE`；G2 卡输出 `acceptance_criteria[]`；BASE 卡可只覆盖 BASE 而不强绑外部八域。

## Scoped review 结论与残余门

**结论**：原始 S6 fresh scoped review `PASS_WITH_P2` 历史保留；该 Q05 当时 Open 的 review 不能自动覆盖 D204。D204 后当前状态为 `S6_RECONFIRM_REQUIRED_AFTER_D204`；builder / verifier 重跑只更新静态规划包，不能代替新的 fresh reviewer。S6 仍只作为规划包与 docs integration 输入。

**下一步入口**：fresh scoped reviewer 第一条只读动作是核对本 checkpoint、`README.md`、`manifest.json` 的 current decision / historical review 双字段、`docs/DECISIONS.md` Q05 Closed/D204 / Open Q03 与 `docs/TASKS.md` `V2-G1-L1-EVIDENCE`，确认 external 445/376 与 addendum 4 / normalized 380 分离、D204 不选择第二引擎、Sherpa / Matcha 仅兼容回退；不得代签 QA 或推进 G2。

**停止条件**：下一步不得启动 Q03 runtime、第二引擎 / exact profile 实际选型、设备、生产实现、G2、独立 QA 或 Release；任何计数不等、显式语义 / 源锚不可复算、`__pycache__` 回流、D204/Q03/Q04/UI/G1 状态漂移、L2/L3 解锁、manifest / LF 不复算，或需要写授权范围外文件时停止并报告 `BLOCKED`。

## 上下文水位

本文件不固化易漂移的 token 数；负责人收口时必须从当前会话状态工具读取实际水位。
达到 25 万收缩、30 万写 checkpoint 并停止；下一步若超出只读残余门审计或用户决策准备，必须由负责人另建对应专业会话并取得明确授权。
