# G2 Entry Runtime Contract Checkpoint

状态：`R2_WIP_REPAIR_R3_IN_PROGRESS / qaDispatchable=false / K60_GATED_LOCKED /
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
  `9578fdbf377e64f5d154d4a10d325c3a55820905cc52a64bb96937a6f230bec7`。
- Report schema：`report.schema.json`，
  `a1c1549eb08b8c49ad0a2c4b6bb59fb8bbf641d1529f05948d1bb9e591409bae`。
- Collector：`Invoke-G2RuntimeCollector.ps1`，
  `e00cdcb2c153670a259148f4ed6e34f9b5d46eab26e3ddd8ce2bb6ed6e9a7b6a`。
- Finalizer：`Finalize-G2RuntimeReport.ps1`，
  `bb83072433a0b9e3c4c42dc476a10f6752f0f3f23bdc6090f95fbd86ccc7a628`。
- Cleanup verifier：`Test-G2CleanupRestore.ps1`，
  `eada2d45011ce989a11188f5ed8d9dc24d052f09f7d9fc5a82482353b073087e`。
- Tool manifest：`runtime-tools.manifest.json`，
  `c35f51008be1bcb235a5969d23782856ebac17c721b6edda2be749e00fed9a13`。
- Tools digest：
  `d27166fcc3c5481c1943bdbb0178015caa7a7af5783c35799eb3ed4a976d5c6e`。

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

## 实现侧验证

- `Test-G2RuntimeTools.ps1`：`87 PASS / 0 FAIL / 52 dynamic negative`；该计数只是本轮
  implementation-side 观测，不是独立 audit / QA / runtime PASS。
- `Test-G2EntryRuntimeContract.ps1`：`PASS G2_ENTRY_RUNTIME_CONTRACT`。
- collector / finalizer / cleanup `StaticSelfTest` 通过，`DEVICE_COMMANDS=0`。
- 候选在线身份、schema 与工具摘要从现场复算；真实 SHA 见固定对象段和
  `runtime-tools.manifest.json`。
- 未运行 `Test-HandoffPreflight.ps1` 104 项长套件；它只应在 root 稳定/提交候选后由另一个
  fresh audit 执行。

## 下一门

无自动下一角色。root 决定是否启动 fresh scoped review；review 第一条只读动作必须是
`git status --short`，随后 `git diff --stat`。若 HEAD、17 文件 WIP 范围、候选 SHA、
`qa-pad-v1` 五文件或授权对象漂移，立即 `BLOCKED_SCOPE_DRIFT`。review 前不得运行 handoff
preflight、不得派 Pad/K60 QA，K60 继续保持锁定。
