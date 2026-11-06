# G2 Entry Runtime Contract Checkpoint

状态：`PAD_RUNTIME_TOOLS_READY_PENDING_INDEPENDENT_QA / K60_GATED_LOCKED / RUNTIME_UNVERIFIED / G1_NOT_PASSED`

## 已完成

- 建立全新、一次性的合同 owner 与 evidenceRoot，不复用 QA-v9 owner、工具、证据或 prompt。
- 用户以 `V2-D210` 发出设备执行 receipt，绑定当前合同、main / peer SHA 与 `Pad->K60`
  顺序；Pad 可执行，K60 仍以 Pad 全 PASS 和锁释放为前提。
- 离线构建并固定新的 main / focus peer 候选身份；没有修改产品行为，只调整版本身份。
- 固定 `Pad->K60`、每设备一次连续 10 分钟、无延长、无重试、K60 当前锁定。
- 固定 13 个产品门、结果 verifier、report schema、stop conditions 与状态锁。
- 保留 Q03 Open、exact profile / voice Open、Pad P2 未接受、G1 NOT_PASSED、
  runtime UNVERIFIED、L2/L3 LOCKED。

## 候选与工具

- Main：`candidate-main-v6.apk`，`427009669 B`，
  `1b3aedb1707f6444505dd96f5a4fd39b6ab27bd24c82d58a86272db62b7ac2a3`。
- Peer：`candidate-focus-peer-v2.apk`，`12698 B`，
  `d1387ed57d2481397b4ade80932351210f88f0b79e8ac1d7afdd598253773a7a`。
- Builder：`Build-G2Candidate.ps1`，
  `069caf154e71310d0c6632837a7a13bfee8cf575ef1a4f5af3b07a116eb8839c`。
- Validator：`Test-G2EntryRuntimeContract.ps1`，
  `39c5d76fae368d5a464a2e7e321620ba8dd5db5c4f9fdb630da00ba64ad157a7`。
- Report schema：`report.schema.json`，
  `8cb6d8766e773107e9493b828239ee417c61cf703a3c7756f94b684729921046`。
- Collector：`Invoke-G2RuntimeCollector.ps1`，
  `b40a11358d3e451e81bdeddcec71c2256dc926c39350daf661b3c0f5059c2720`。
- Finalizer：`Finalize-G2RuntimeReport.ps1`，
  `c81aab0fa93e8b917a997e9388b7d52384e7c338e66a73dfa9e3b8707ff5e42e`。
- Cleanup verifier：`Test-G2CleanupRestore.ps1`，
  `4718ff2f20b364b83e31391be174e23e01970f0cc45945972c3e0365d43ac20a`。
- Tool manifest：`runtime-tools.manifest.json`，
  `88ef952eb7f87a3eb03bd1896a948e955a564d24ce744582b9d9c9b84e3c48bb`。
- Tools digest：
  `df4d2425054c27f6f1bc5fc4d19029dfa03aeffb3b7ed5f57a8928d2adee6267`。

## 未执行 / 不可声明

- Pad / K60 runtime、安装、ADB、网络、账号和服务端调用均未执行。
- 产品 P0-P3 仍为 `NOT_ASSESSED`；没有 runtime 或 G1 PASS。
- Runtime 工具已 `READY_SELF_TESTED_STATIC_ONLY`；尚未生成可执行 Pad QA prompt，
  `activePrompt=G2_PAD_QA_READY_PENDING_FRESH_QA_OWNER`。
- 未关闭 Q03、未冻结 profile / voice、未接受 Pad P2、未启动 G2 实现或 Release。

## 已验证

- `Test-G2EntryRuntimeContract.ps1`：`PASS G2_ENTRY_RUNTIME_CONTRACT`。
- `Test-HandoffPreflight.ps1`：`104/104 PASS + TEMP_CLEANUP=PASS`。
- 新 G2 post-QA transition 白名单正例：
  `ASSERT POST-QA-G2-TRANSITION-ALLOWED=PASS`。
- `Invoke-HandoffPreflight.ps1 -EmitPrompt`：合同阶段 `PASS`；
  当前合同阶段为 `PAD_RUNTIME_TOOLS_READY_PENDING_INDEPENDENT_QA /
  K60_GATED_LOCKED`。
- 候选仅通过本地 `aapt2` / `apksigner` 身份与签名校验，不代表 runtime。
- `tests/Test-G2RuntimeTools.ps1`：`48/48 PASS`，含 33 个负例；仅静态 fixture / AST /
  JSON / StaticSelfTest，`DEVICE_COMMANDS=0`。
- Runtime 工具路径、owner、candidate source / main / peer SHA、report schema、Pad/K60
  evidence root、K60 predecessor 与授权 receipt 均由三份工具现场绑定并 fail closed。

## 下一门

Runtime tool owner 阶段已完成静态工具与自测；下一步由另一个全新
`G2_PAD_QA_OWNER_2026_11_06` 执行 Pad。工具阶段与 QA 阶段不得复用 owner。
Pad owner 的第一条只读动作必须是 handoff preflight，随后运行合同校验器。K60 继续保持锁定。
