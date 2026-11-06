# G2 Entry Runtime Contract Checkpoint

状态：`R2_WIP_REPAIR_R3_IN_PROGRESS / qaDispatchable=false /
SETTLED_COMPATIBILITY_READY_PENDING_FINAL_AUDIT /
PENDING_SETTLEMENT_FINAL_AUDIT / no receipt / K60_GATED_LOCKED /
RUNTIME_UNVERIFIED / G1_NOT_PASSED / PRODUCT_P0_P3_NOT_ASSESSED / activePrompt=none`

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
  `dba045308623233b69f4a46cf0c1d773b8eefeb0be3b150fe5e54b4da55f19ac`。
- Tests：`tests/Test-G2RuntimeTools.ps1`，
  `1ef28350d7b10c3915aa069bcc5c380d9b790b9fe9417dc7f30cf0c2a496c5e5`。
- Audit receipt schema：`repair-audit-receipt.schema.json`，
  `8895cb8ac6287543aa89b75240d779f94924574de76fddd85b858a100fdaacac`。
- Tool manifest：`runtime-tools.manifest.json`，
  `5595f6124cdbb043dcb703500ad9b511c8dbd29c7be2ccbaf2e59c30e6206cc1`。
- Tools digest：
  `9f563e9d7a2cbb14db905960883a26b911203d5fdc49d0b29a127c0b76b4748d`。
- Contract：`contract.json`，
  `b076be9caca2923dee1e57d8116948513f11019fa3f1cf28d787cd56bca7d42b`。

| Manifest 非工具项 | SHA-256 |
|---|---|
| `tests/Test-G2RuntimeTools.ps1` | `1ef28350d7b10c3915aa069bcc5c380d9b790b9fe9417dc7f30cf0c2a496c5e5` |
| `repair-audit-receipt.schema.json` | `8895cb8ac6287543aa89b75240d779f94924574de76fddd85b858a100fdaacac` |
| `tests/fixtures/netstats-before.txt` | `3c226961c4a54583629521a79015195f5b706ae802d13c45a1783751f0eebf34` |
| `tests/fixtures/netstats-after.txt` | `8a6e7fadbe828801b909fc9c1fe7885276801bb6b7203b6bc2599641dff88bfe` |
| `tests/fixtures/runtime-log-happy.log` | `bb4857956bbdfe9b6a3284c3e8930a7ccb38c5d21a9ad37c831ab5f920ad6dbd` |
| `tests/fixtures/observations-complete.json` | `dd67847eae6094f89916a3102bc496cb1ee576417c0990b0b35950e0d9a9855f` |
| `tests/fixtures/cleanup-complete.json` | `15ea3df61cb5c15cee8551b7b4918e9cdee0fc7918eaf57112be268c05f00633` |

## 未执行 / 不可声明

- Pad / K60 runtime、安装、ADB、网络、账号和服务端调用均未执行。
- 产品 P0-P3 仍为 `NOT_ASSESSED`；没有 runtime 或 G1 PASS。
- R2 WIP 的旧状态、计数与 manifest 均未验收；R3 只修复已定位 finding，工具状态保持
  `R2_WIP_REPAIR_R3_IN_PROGRESS`，`qaDispatchable=false`，不声称 ready。
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
- 当前状态统一为 `R2_WIP_REPAIR_R3_IN_PROGRESS / qaDispatchable=false`。
- collector / finalizer / cleanup 已改为复用 `G2RuntimeStatePolicy.ps1` 的单一封闭状态门。
  当前态只允许 `Validate` / `StaticSelfTest`；`Register`、`RunWindow`、`Finalize`、
  `CaptureBefore` / `Verify` 在状态门即失败，不会创建 attempt 文件。
- future state `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA` 已由临时 fixture 演练：
  只有严格 `repair-audit-receipt.json` 存在且 audit commit / parent、scope、counts、
  tools digest、候选双 SHA、`qa-pad-v1` 五 SHA、外部锁与 next role 全部现场匹配时，
  collector / finalizer / cleanup context 才接受运行时动作。
- future K60 `CaptureBefore` / `Verify` 在任何 ADB、目录创建或文件写入前，复用 collector
  共享 predecessor helper，重读固定 `qa-pad-v2/report.json` 与 `device-lock.json`，
  校验 13 个唯一 PASS gate、cleanup PASS、候选双 SHA、RELEASED_ACTIVE_0 与 report
  pointer，并把实际 report / lock SHA 与 K60 registration 精确绑定。Pad 缺失、非 PASS、
  旧 scope、SHA 漂移、ACTIVE lock 五类负例 × 两个 action 均为 ADB=0、目录写入=0。
- 当前真实合同的 `validation.repairAuditExpectations` 仍为
  `PENDING_SETTLEMENT_FINAL_AUDIT`，source / counts 全为 null。R7 把策略基线改为不可由
  contract / receipt 提供或抬高的独立 exact constants：`toolFiles=4`、`testsPassed=116`、
  `testsFailed=0`、`dynamicNegative=188`、`currentTransitions=62`、`staticSelfTests=3`、
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
- runtime manifest 精确要求 11 个唯一路径与
  `4 tool + 1 test + 1 schema + 5 fixture`；10 / 12 项、duplicate、未知 kind 与 SHA
  漂移均 fail-closed。
- contract 根对象、manifest、authorization、candidate、registration、session、
  device-lock 与 runtime report 的状态承载对象均使用 Ordinal 大小写敏感 exact-property
  set；`status`→`Status`、根 `validation`→`Validation`、`g2ToolsCounts`→`G2ToolsCounts`、
  K60 `deviceOrder`→`DeviceOrder`、未知字段与同一对象重复属性均拒绝。四个严格 JSON
  读取入口继续在反序列化前扫描重复属性。
- actual `repair-audit-receipt.json` 未创建；真实合同仍为 pre-audit，未形成 audit 或 QA
  权限。
- R9 把合法 receipt 的 `auditedCommit` 收紧为最低基线
  `6b04eb300256fc8384e5407cee24907e8343f949` 的严格后继：相等、最低基线 parent 与
  parent 漂移均拒绝，严格后继正例接受；audited commit 不再要求是当前 `HEAD` 祖先。
  候选 source commit 仍必须位于 audited commit 之前。future fixture 在临时 Git object
  directory 中生成合成严格后继及其实际 parent，不写工作树、ref 或永久 Git object。
- 新增 synchronized pure-high `testsPassed+1 / dynamicNegative+1 / handoffPassed+1`
  负例，防止 contract 与 receipt 同步抬高 counts 解闸。
- settled QA-ready 兼容已就绪但仍待 final audit：PRE 要求
  `generatedExecutableQaPrompt=false` 且 `authorization.qaDispatch=null`；future temp
  fixture 才要求 `true` 与具体 descriptor。descriptor 绑定 owner、canonical read-only
  preflight、worktree / branch / audited head、固定读序、non-goals、`qa-pad-v2/**` 写域、
  Pad-only / K60 forbidden、单次 10 分钟无 retry、13 gates 与全部 stop conditions。
  `promptText` 的 UTF-8 SHA-256 同时绑定 contract / receipt / policy，descriptor 或 hash
  缺失、owner / device / window / K60 / read order / non-goal / stop fields 漂移均拒绝。

## 实现侧验证

- `Test-G2RuntimeTools.ps1`：`116 PASS / 0 FAIL / 188 dynamic negative`；该计数只是本轮
  implementation-side 观测，不是独立 audit / QA / runtime PASS。
- `Test-G2EntryRuntimeContract.ps1`：`PASS G2_ENTRY_RUNTIME_CONTRACT`，
  `TOOL_STATE_MODE=PRE_AUDIT_STATIC_ONLY`。
- collector / finalizer / cleanup `StaticSelfTest` 通过，`DEVICE_COMMANDS=0`。
- collector `Validate` PASS，输出 tools digest
  `9f563e9d7a2cbb14db905960883a26b911203d5fdc49d0b29a127c0b76b4748d`；
  当前态直接执行 `Register` 在状态门失败且未写 registration/session。
- current handoff preflight PASS，实际
  `roles=22 / tasks=3 / gates=6 / currentTransitions=62`，无 QA prompt 派发。
- 候选在线身份、schema 与工具摘要从现场复算；真实 SHA 见固定对象段和
  `runtime-tools.manifest.json`。
- 完整 handoff 的现场基线为 `108/108`，本轮未运行；只能标“待 final audit”。
  历史 `104/104 PASS` 只是过去快照，不能充当当前完整数。

## 下一门

无自动下一角色。root 决定是否启动 fresh scoped review；review 第一条只读动作必须是
`git status --short`，随后 `git diff --stat`。若 HEAD、WIP 范围、候选 SHA、
`qa-pad-v1` 五文件或授权对象漂移，立即 `BLOCKED_SCOPE_DRIFT`。review 前不得运行 handoff
preflight、不得派 Pad/K60 QA、不得创建 PASS receipt 或切换 future state，K60 继续保持锁定。
