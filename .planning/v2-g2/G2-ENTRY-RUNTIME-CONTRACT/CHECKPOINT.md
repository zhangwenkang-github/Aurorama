# G2 Entry Runtime Contract Checkpoint

状态：`WAITING_USER_NOTIFICATION_AFTER_G2_TOOL_REPAIR / PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT / K60_GATED_LOCKED / RUNTIME_UNVERIFIED / G1_NOT_PASSED`

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

## 候选与工具

- Main：`candidate-main-v6.apk`，`427009669 B`，
  `1b3aedb1707f6444505dd96f5a4fd39b6ab27bd24c82d58a86272db62b7ac2a3`。
- Peer：`candidate-focus-peer-v2.apk`，`12698 B`，
  `d1387ed57d2481397b4ade80932351210f88f0b79e8ac1d7afdd598253773a7a`。
- Builder：`Build-G2Candidate.ps1`，
  `069caf154e71310d0c6632837a7a13bfee8cf575ef1a4f5af3b07a116eb8839c`。
- Validator：`Test-G2EntryRuntimeContract.ps1`，
  `10acad646a78375a4a33b3b1cbb908c589dbada77d28170a26146e76ed9ba402`。
- Report schema：`report.schema.json`，
  `8cb6d8766e773107e9493b828239ee417c61cf703a3c7756f94b684729921046`。
- Collector：`Invoke-G2RuntimeCollector.ps1`，
  `6b6a3e94dabd9c73b10c746a444b32d309c2de31eb1f9363a4ecb96d65038971`。
- Finalizer：`Finalize-G2RuntimeReport.ps1`，
  `4a149f2726c92c278be59acc25a7457a795e3d777906ee45605dc6e54827a666`。
- Cleanup verifier：`Test-G2CleanupRestore.ps1`，
  `f41b7b5669f39855f2603881fd632db1dd9d91507ff3c1a9509351954160257e`。
- Tool manifest：`runtime-tools.manifest.json`，
  `c956c67adde8befb27166ab9a5107861879d7ed125077d7a4e7d49e878434bcc`。
- Tools digest：
  `dac3540bca28945375f0d41e03cb9d10c4c6dbc2a5714b6dbdcb299cf639d9e5`。

## 未执行 / 不可声明

- Pad / K60 runtime、安装、ADB、网络、账号和服务端调用均未执行。
- 产品 P0-P3 仍为 `NOT_ASSESSED`；没有 runtime 或 G1 PASS。
- Runtime 工具已 `READY_SELF_TESTED_STATIC_ONLY`；用户已要求暂停等待后续通知，
  `activePrompt=none`、`qaDispatchable=false`，不得创建 fresh audit 或 Pad/K60 QA。
- 未关闭 Q03、未冻结 profile / voice、未接受 Pad P2、未启动 G2 实现或 Release。

## 已验证

- `Test-G2EntryRuntimeContract.ps1`：`PASS G2_ENTRY_RUNTIME_CONTRACT`。
- `Test-HandoffPreflight.ps1`：`104/104 PASS + TEMP_CLEANUP=PASS`。
- 新 G2 post-QA transition 白名单正例：
  `ASSERT POST-QA-G2-TRANSITION-ALLOWED=PASS`。
- `Invoke-HandoffPreflight.ps1 -EmitPrompt`：合同阶段 `PASS`；
  当前合同阶段为 `PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT /
  K60_GATED_LOCKED`。
- 候选仅通过本地 `aapt2` / `apksigner` 身份与签名校验，不代表 runtime。
- `tests/Test-G2RuntimeTools.ps1`：`57/57 PASS`，含 37 个负例；仅静态 fixture / AST /
  JSON / StaticSelfTest，`DEVICE_COMMANDS=0`。
- repo-relative 工具路径与 evidence-relative 合同路径均只解析一次；绝对、traversal 与
  reparse escape 继续 fail closed。
- cleanup 历史 focus event、`ls ... No such file` 文本、无本 session 的 adb-logcat 进程均
  不再冒充残留；真实当前 focus / 路径 / 进程与外部 ACTIVE lock 仍会 FAIL。
- Runtime 工具 owner、candidate source / main / peer SHA、report schema、Pad/K60 evidence
  root、K60 predecessor 与授权 receipt 均由三份工具现场绑定并 fail closed。

## 下一门

Tool repair checkpoint 保持 `PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT`，但外层状态为
`WAITING_USER_NOTIFICATION_AFTER_G2_TOOL_REPAIR / activePrompt=none`。用户恢复前不得创建
audit、Pad QA 或设备动作。恢复后仍须由另一个全新只读 audit 复核两份 bug diff、`57/57`
回归与全部摘要；K60 继续保持锁定。
