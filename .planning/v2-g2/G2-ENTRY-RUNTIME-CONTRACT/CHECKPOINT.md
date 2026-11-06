# G2 Entry Runtime Contract Checkpoint

> **2026-11-07 当前状态**：`PLAN_A_STATE_MODEL_REVIEW_PASS /
> SETTLEMENT_COMMIT=241b8b356e2aab4d1ef43016639dc0203eb10f7d /
> CLEAN_PREFLIGHT_PASS`。下方 `PENDING_FRESH_REVIEW` 文案只作 pre-review 证据。
> 用户已选择方案 A：receipt / contract / policy / schema 的 `currentTransitions=64`
> 是不可变 audit-time snapshot；root settlement 提交后的 canonical preflight 可记录不同
> live transition count（已实测 65），该值只作 observation，不与 64 比较。fresh review
> 已 PASS，settlement commit `241b8b35` 已 clean，canonical preflight 已 PASS；Pad QA
> 具备派发条件但尚未派发。其他 live 对象漂移仍 fail-closed；K60 锁定。

状态：`PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA /
qaDispatchable=true / activePrompt=G2_PAD_QA_OWNER_2026_11_06 /
AUDIT_RECEIPT_PASS / PAD_QA_PROMPT_DESCRIPTOR_READY_PENDING_FRESH_REVIEW /
PAD_QA_NOT_EXECUTED / WIP=PLAN_A_STATE_MODEL_REDESIGN_PATCHED_PENDING_FRESH_REVIEW /
AUDIT_SNAPSHOT_CURRENT_TRANSITIONS_EXACT_64 /
LIVE_PREFLIGHT_TRANSITIONS=OBSERVED_NOT_RECEIPT_BOUND /
K60_GATED_LOCKED /
RUNTIME_UNVERIFIED / G1_NOT_PASSED / PRODUCT_P0_P3_NOT_ASSESSED`

## 已完成

- 建立全新、一次性的合同 owner 与 evidenceRoot，不复用 QA-v9 owner、工具、证据或 prompt。
- 用户以 `V2-D210` 发出设备执行 receipt，绑定当前合同、main / peer SHA 与 `Pad->K60`
  顺序；Pad 可执行，K60 仍以 Pad 全 PASS 和锁释放为前提。
- 离线构建并固定新的 main / focus peer 候选身份；没有修改产品行为，只调整版本身份。
- 固定 `Pad->K60`、每设备一次连续 10 分钟、无延长、无重试、K60 当前锁定。
- 固定 13 个产品门、结果 verifier、report schema、stop conditions 与状态锁。
- 保留 Q03 Open、exact profile / voice Open、Pad P2 未接受、G1 NOT_PASSED、
  runtime UNVERIFIED、L2/L3 LOCKED。
- 全新一次性 tool repair owner 复现并修复 collector repo-relative 工具路径二次绑定；
  cleanup verifier 改为只接受当前 focus stack、真实路径、本 session 进程与回读后的锁状态。
- 定向 RED 复现“历史 blocker + 自持 ACTIVE 锁、无注册文件时仍拒绝新登记”；修复后改为
  历史证据与当前 attempt 双作用域，旧 `qa-pad-v1/` 不被删除、移动或改写。
- 合同固定 `qa-pad-v1/` 五个历史文件的 SHA-256；只有 blocker 明确证明窗口
  `NOT_STARTED`、候选未安装、`windowConsumed=false`，且 handoff 权威外部锁为
  `RELEASED_ACTIVE_0` 时，才允许进入空的 `qa-pad-v2/`。
- `qa-pad-v1/device-lock.json` 始终保持历史 `ACTIVE_COUNT_1`，不会再被解释为新 attempt
  已释放，也不会被 cleanup 覆盖。
- 全新 settlement 已把 `G2_FINAL_AUDIT_OWNER_2026_11_06` 在
  `96e19ca2606d851ecbe1c4ed15188767207f9f64` 的 PASS 事务化为
  `repair-audit-receipt.json`，并生成绑定同一 audited source/provenance 的 Pad QA descriptor。
  `contractPackageReady=true`、`qaDispatchable=true`、
  `activePrompt=G2_PAD_QA_OWNER_2026_11_06`；Pad QA 未执行。

## 候选与工具

- Main：`candidate-main-v6.apk`，`427009669 B`，
  `1b3aedb1707f6444505dd96f5a4fd39b6ab27bd24c82d58a86272db62b7ac2a3`。
- Peer：`candidate-focus-peer-v2.apk`，`12698 B`，
  `d1387ed57d2481397b4ade80932351210f88f0b79e8ac1d7afdd598253773a7a`。
- Builder：`Build-G2Candidate.ps1`，
  `069caf154e71310d0c6632837a7a13bfee8cf575ef1a4f5af3b07a116eb8839c`。
- Validator：`Test-G2EntryRuntimeContract.ps1`，
  `987e3ae7ec47b32541799522c94cb2c15384422f72688541a6baf6efbdde09eb`。
- Report schema：`report.schema.json`，
  `a1c1549eb08b8c49ad0a2c4b6bb59fb8bbf641d1529f05948d1bb9e591409bae`。
- Collector：`Invoke-G2RuntimeCollector.ps1`，
  `9d248404c3f0cc450c249ec2b6aaf142978c90d52c0146163f677d0b1adeba49`。
- Finalizer：`Finalize-G2RuntimeReport.ps1`，
  `59b6f69dab3550a47740f3b141df25b26c6de22c77a00265efe2705af770aed1`。
- Cleanup verifier：`Test-G2CleanupRestore.ps1`，
  `6f581a5707720eb2085920a8a58e292a9461b751d13318030de34305c8379057`。
- Shared state policy：`G2RuntimeStatePolicy.ps1`，
  `e4ea0752e6a3390c742a8807f339d5f0dce2d388652d6f417d89a4ed9e8ea34a`。
- Tests：`tests/Test-G2RuntimeTools.ps1`，
  `99f200a1dd1fa4766ee41865f867282a6fe309588358c496c2491e40a5db4269`。
- Audit receipt schema：`repair-audit-receipt.schema.json`，
  `566f97577861ca78e2416dbba7ad383b3ada99c3a0bba65f82d070a56f0c542e`。
- Tool manifest：`runtime-tools.manifest.json`，
  `5562c1dcccbb9079794badf21b6aef22a116c8f3b3ac35286442247f170dbbd5`。
- Tools digest：
  `5cd2474619cd789b734798b38eae1c2c272600c6865ec8bdec809539a6ba502a`。
- Contract：`contract.json`，
  `70d3b869cfb33f34f372c7de9e13cb5198f5e8502dc6c07383bb559e9f91bad0`。
- Repair audit receipt：`repair-audit-receipt.json`，
  `c7d9005143231e6cc2661c9476380d4194f8d6dab90fdd8d61c5e8e9c3219a9d`。
- Pad QA prompt SHA-256：
  `5216b3d0e401aa9a67136762e155505a636300ccb27c715abe5e06a1a29d7172`。

| Manifest 非工具项 | SHA-256 |
|---|---|
| `tests/Test-G2RuntimeTools.ps1` | `99f200a1dd1fa4766ee41865f867282a6fe309588358c496c2491e40a5db4269` |
| `repair-audit-receipt.schema.json` | `566f97577861ca78e2416dbba7ad383b3ada99c3a0bba65f82d070a56f0c542e` |
| `tests/fixtures/netstats-before.txt` | `3c226961c4a54583629521a79015195f5b706ae802d13c45a1783751f0eebf34` |
| `tests/fixtures/netstats-after.txt` | `8a6e7fadbe828801b909fc9c1fe7885276801bb6b7203b6bc2599641dff88bfe` |
| `tests/fixtures/runtime-log-happy.log` | `bb4857956bbdfe9b6a3284c3e8930a7ccb38c5d21a9ad37c831ab5f920ad6dbd` |
| `tests/fixtures/observations-complete.json` | `dd67847eae6094f89916a3102bc496cb1ee576417c0990b0b35950e0d9a9855f` |
| `tests/fixtures/cleanup-complete.json` | `15ea3df61cb5c15cee8551b7b4918e9cdee0fc7918eaf57112be268c05f00633` |

## 未执行 / 不可声明

- Pad / K60 runtime、安装、ADB、网络、账号和服务端调用均未执行。
- 产品 P0-P3 仍为 `NOT_ASSESSED`；没有 runtime 或 G1 PASS。
- R2 WIP 的旧状态、计数与 manifest 已由 final audit 验收；当前真实状态为
  `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA`，`qaDispatchable=true`，但尚未
  执行 QA。
- 未关闭 Q03、未冻结 profile / voice、未接受 Pad P2、未启动 G2 实现或 Release。

## R3 修复

- cleanup schema pin 改为合同字段与实际 `report.schema.json` 文件 SHA 双绑定；旧
  `8cb6d876…` 常量已移除，schema 漂移与旧 pin 均有负例。
- K60 `RunWindow` 的 registration 复核改为回读固定 `qa-pad-v2/report.json` 与
  `device-lock.json`，复算实际 SHA，并现场验证 candidate main / peer、13 gate、
  verdict / productVerdict、cleanup 与释放锁；伪造 registration、`a*64 / b*64`、旧 scope、
  文件漂移均 fail closed。
- collector / finalizer / cleanup 的 v2 evidence root 解析均调用 reparse helper；evidence
  root 本身为 junction 时拒绝。
- attempt root 必须完全为空；任意文件、目录、隐藏项或未知条目都会拒绝登记。
- 修复期历史状态为 `R2_WIP_REPAIR_R3_IN_PROGRESS / qaDispatchable=false`；settlement 后
  当前真实状态为 `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA / qaDispatchable=true`。
- collector / finalizer / cleanup 已改为复用 `G2RuntimeStatePolicy.ps1` 的单一封闭状态门。
  PRE 状态只允许 `Validate` / `StaticSelfTest`；settled 状态才开放运行时动作。
- current state `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA` 已由 contract validator
  验证，并由 fixture 覆盖：
  只有严格 `repair-audit-receipt.json` 存在且 audit commit / parent、scope、counts、
  tools digest、候选双 SHA、`qa-pad-v1` 五 SHA、外部锁与 next role 全部现场匹配时，
  collector / finalizer / cleanup context 才接受运行时动作。
- future K60 `CaptureBefore` / `Verify` 在任何 ADB、目录创建或文件写入前，复用 collector
  共享 predecessor helper，重读固定 `qa-pad-v2/report.json` 与 `device-lock.json`，
  校验 13 个唯一 PASS gate、cleanup PASS、候选双 SHA、RELEASED_ACTIVE_0 与 report
  pointer，并把实际 report / lock SHA 与 K60 registration 精确绑定。Pad 缺失、非 PASS、
  旧 scope、SHA 漂移、ACTIVE lock 五类负例 × 两个 action 均为 ADB=0、目录写入=0。
- 当前真实合同的 `validation.repairAuditExpectations` 已为
  `SETTLED_FROM_FINAL_AUDIT`，source / counts 绑定最终 audit commit。R7 把策略基线改为不可由
  contract / receipt 提供或抬高的独立 exact constants：`toolFiles=4`、`testsPassed=116`、
  `testsFailed=0`、`dynamicNegative=188`、`staticSelfTests=3`、
  `handoffPassed=108`、`handoffFailed=0`、`handoffDynamicNegative=0`、
  `tempCleanupPassed=true`、`boundaryViolations=0`。future fixture 要求 contract
  expectations 与 receipt 三方逐项 exact equal；同步 `111/151/109`、低值、缺失、null 与
  字符串漂移均拒绝。
- R7 RED 在合法 manifest 隔离 fixture 上分别证明同步 `111/151/109` 与根 `Status` 未抛错；
  修复后 `status` / `Status`、`validation` / `Validation`、根 unknown、嵌套状态对象
  case-variant 与合法 future exact receipt 正反例均进入同一最终套件。
- R8 RED 证明 contract expectations 与 receipt 同步 `currentTransitions=61` 仍被接受。
  修复后 policy 独立常量与 receipt schema 都固定 `62`；合法 `62` 保留，contract-only /
  receipt-only / 同步 `61`、缺失、null 与字符串均 fail-closed。
- 2026-11-07 current transition repair 将当前 oracle 原位刷新为 `64`：policy、receipt schema
  与 future fixture 一致，负例改为同步 / 单层 `63` 与字符串 `"64"`。该变更是 R8 历史
  `62` 的当前基线刷新，不改变 case 数量，也不是 audit / QA / runtime PASS。方案 A 将该
  `64` 明确限定为 immutable audit-time snapshot；settlement 提交后的 canonical preflight
  live count（当前推导 65）只作 `OBSERVED_NOT_RECEIPT_BOUND` 记录，不与 64 比较，也不影响
  clean settlement 的其它绑定门。
- runtime manifest 精确要求 11 个唯一路径与
  `4 tool + 1 test + 1 schema + 5 fixture`；10 / 12 项、duplicate、未知 kind 与 SHA
  漂移均 fail-closed。
- contract 根对象、manifest、authorization、candidate、registration、session、
  device-lock 与 runtime report 的状态承载对象均使用 Ordinal 大小写敏感 exact-property
  set；`status`→`Status`、根 `validation`→`Validation`、`g2ToolsCounts`→`G2ToolsCounts`、
  K60 `deviceOrder`→`DeviceOrder`、未知字段与同一对象重复属性均拒绝。四个严格 JSON
  读取入口继续在反序列化前扫描重复属性。
- actual `repair-audit-receipt.json` 已创建并通过 strict schema / policy 验证；这仍属于
  未提交 WIP，不是当前可执行授权。fresh review PASS、root settlement commit clean 与
  canonical preflight PASS 前不得派发 Pad QA；当前没有执行 QA。
- R9 把合法 receipt 的 `auditedCommit` 收紧为最低基线
  `6b04eb300256fc8384e5407cee24907e8343f949` 的严格后继：相等、最低基线 parent 与
  parent 漂移均拒绝，严格后继正例接受；audited commit 不再要求是当前 `HEAD` 祖先。
  候选 source commit 仍必须位于 audited commit 之前。future fixture 在临时 Git object
  directory 中生成合成严格后继及其实际 parent，不写工作树、ref 或永久 Git object。
- 新增 synchronized pure-high `testsPassed+1 / dynamicNegative+1 / handoffPassed+1`
  负例，防止 contract 与 receipt 同步抬高 counts 解闸。
- settled QA-ready 兼容已由 final audit PASS，并由真实 contract 使用：当前要求
  `generatedExecutableQaPrompt=true` 与具体 descriptor。descriptor 绑定 owner、canonical read-only
  preflight、worktree / branch / audited source binding、固定读序、non-goals、`qa-pad-v2/**` 写域、
  Pad-only / K60 forbidden、单次 10 分钟无 retry、13 gates 与全部 stop conditions。
  `promptText` 的 UTF-8 SHA-256 同时绑定 contract / receipt / policy，descriptor 或 hash
  缺失、owner / device / window / K60 / read order / non-goal / stop fields 漂移均拒绝。

方案 A 已端到端修补 round-2 的 P1-01/P1-02：`HANDOFF_BOOTSTRAP_V2` 的
`POST_QA_RUNTIME_DEFERRED` 与 canonical preflight emitted `NEXT_PROMPT_BEGIN` 是审计时历史
措辞，preflight exit / PASS 仍是机械门；settlement 提交 clean 且 preflight PASS 后，
`contract.authorization.qaDispatch.promptText` 才作为可执行派发源。descriptor
`head=96e19...` / `headExpectation=EXACT_AUDITED_COMMIT_CLEAN` 仅是 policy 要求的 audited
source/provenance binding，不是 QA live HEAD；QA live clean HEAD 由 canonical preflight
现场返回并校验。receipt / contract 的 `currentTransitions=64` 是 immutable audit snapshot；
settlement 后 live count（当前推导 65）只作 `OBSERVED_NOT_RECEIPT_BOUND` 记录，不与 64
比较，也不否定 clean settlement。合法 HEAD / transition 后继不得误停；其他现场对象漂移
仍 `BLOCKED`。

## 验证与审计

- `Test-G2RuntimeTools.ps1`：final audit 为 `116 PASS / 0 FAIL / 188 dynamic negative`；
  该结果不是 QA / runtime PASS。
- 本轮唯一一次 `Test-G2EntryRuntimeContract.ps1` 已对 Plan A patch 返回
  `PASS / exit 0`，`TOOL_STATE_MODE=AUDIT_PASS_PENDING_FRESH_PAD_QA`；此前 settlement 的
  PASS 不覆盖当前修订对象。本结果不是 review、QA 或 runtime PASS。
- collector / finalizer / cleanup `StaticSelfTest` 通过，`DEVICE_COMMANDS=0`。
- collector `Validate` PASS，输出 tools digest
  `5cd2474619cd789b734798b38eae1c2c272600c6865ec8bdec809539a6ba502a`；
  settlement 未执行 `Register` / `RunWindow`，`qa-pad-v2/` 不存在。
- final audit 时的 handoff preflight snapshot 为
  `roles=22 / tasks=3 / gates=6 / currentTransitions=64`；该 64 仅绑定 audit-time
  receipt / contract / policy / schema，不是 settlement 后 live equality oracle。当前 dirty
  WIP 不运行 preflight；root commit clean 后才从 canonical preflight 记录 live
  `currentTransitions`（当前推导 65）为 observation。实际 QA 未派发。
- 候选在线身份、schema 与工具摘要从现场复算；真实 SHA 见固定对象段和
  `runtime-tools.manifest.json`。
- final audit 的完整 handoff 为 `108/0 + TEMP_CLEANUP=PASS`；settlement 未重跑该长回归。
  历史 `104/104 PASS` 只是旧快照。

## 下一门

无自动下一角色。下一步只由 root 启动 fresh review；review 第一条只读动作必须是
`git status --short`，随后 `git diff --stat`。必须核对 Plan A patch 的写域仅限现有
settlement WIP、`AGENTS.md`、contract root 状态文档以及 prompt/SHA 绑定；工具、测试、schema、
policy、候选、authorization、handoff 合同、preflight、`qa-pad-v1` 与 `qa-v9` 字节不变。
若 HEAD、WIP 范围或对象漂移，立即 `BLOCKED_SCOPE_DRIFT`。fresh review PASS 后 root 才可
提交；commit clean 和后续 canonical preflight 前，不得派 Pad/K60 QA；K60 继续保持锁定。
