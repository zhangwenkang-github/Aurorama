# Aurorama G2 入口 Runtime 合同

状态：`R2_WIP_REPAIR_R3_IN_PROGRESS / qaDispatchable=false /
SETTLED_COMPATIBILITY_READY_PENDING_FINAL_AUDIT /
PENDING_SETTLEMENT_FINAL_AUDIT / no receipt / K60_GATED_LOCKED /
PRODUCT_P0_P3_NOT_ASSESSED`

本目录是全新的 G2 runtime 合同与证据边界。用户已通过 `V2-D210` 授权本合同下的运行时
工具建设和设备 QA。Pad 可先执行；K60 必须等待 Pad 全核心 PASS 且锁释放后另派全新 owner。
该授权不构成 runtime、产品、Q03、profile / voice、G1 或 G2 实现 PASS。

## 固定对象

- Contract owner：`G2_RUNTIME_CONTRACT_OWNER_AURORAMA_2026_11_06`，一次性使用，合同报告后退役。
- Device authorization：`V2-D210`，receipt 为 `authorization-receipt.json`。
- Source baseline：`35f9d493f16cdf79f946871fc331e2ceece287d2`。
- Main candidate：`candidate-main-v6.apk`，`427009669 B`，
  `1b3aedb1707f6444505dd96f5a4fd39b6ab27bd24c82d58a86272db62b7ac2a3`。
- Main identity：`dev.aurorama.kokoro.harness` / `versionCode=5` /
  `2.0-g2-runtime-contract-v6` / `arm64-v8a` / `INTERNET=false`。
- Focus peer：`candidate-focus-peer-v2.apk`，`12698 B`，
  `d1387ed57d2481397b4ade80932351210f88f0b79e8ac1d7afdd598253773a7a`。
- Source inputs digest：`f6c601311c0a06de3e8c9b647ab0e699208341dee57b3de39d529f94978a7662`。
- Active attempt roots：`qa-pad-v2/`、`qa-k60-v2/`。
- Historical blocked root：`qa-pad-v1/`，只作为不可改写的历史证据。

候选只做离线版本身份增量构建，没有执行 runtime、没有碰设备、没有联网。

## 固定执行门

- 顺序严格为 `Pad 43af8627 -> K60 8e875894`。
- 每设备只有一次连续 10 分钟窗口，不延长、不重试、不使用历史窗口替代。
- Pad 必须全部核心门 PASS、报告 PASS、cleanup PASS 且锁 `RELEASED_ACTIVE_0` 后，
  K60 才可由另一个全新 owner 登记和执行。
- 同一 main candidate SHA 与 focus peer SHA 必须用于两台设备。
- 必须同时覆盖 `zf_001` / `zm_010`、真实 AudioTrack start / completion、头 / 中 / 尾、
  `<=15s` gap、FGS / MediaSession / notification / UI / system 等式、
  AudioFocus / 单音频 owner、crash / ANR / InputTimeout、UI cancel、进程恢复、
  App UID 全分块零网络和完整 cleanup / restore。
- `PASS_WITH_GAPS` 不得覆盖任何核心门未执行或失败。

## 报告合同

`report.schema.json` 固定 `aurorama.g2-entry-runtime-report.v1`。报告按设备一份，
必须包含 candidate SHA、授权 receipt、设备与锁、单次 600 秒窗口、13 个产品门、
AudioTrack 事件、连续性、系统表面、focus、稳定性、UI cancel、恢复、零网络、
P0-P3、未覆盖、cleanup、最终 verdict。

## Runtime 工具

状态：`R2_WIP_REPAIR_R3_IN_PROGRESS / qaDispatchable=false / DEVICE_QA_NOT_RUN`。

- Collector：`Invoke-G2RuntimeCollector.ps1`，
  `9d248404c3f0cc450c249ec2b6aaf142978c90d52c0146163f677d0b1adeba49`。
- Finalizer：`Finalize-G2RuntimeReport.ps1`，
  `59b6f69dab3550a47740f3b141df25b26c6de22c77a00265efe2705af770aed1`。
- Cleanup verifier：`Test-G2CleanupRestore.ps1`，
  `6f581a5707720eb2085920a8a58e292a9461b751d13318030de34305c8379057`。
- Shared state policy：`G2RuntimeStatePolicy.ps1`，
  `e4ea0752e6a3390c742a8807f339d5f0dce2d388652d6f417d89a4ed9e8ea34a`。
- Contract validator：`Test-G2EntryRuntimeContract.ps1`，
  `987e3ae7ec47b32541799522c94cb2c15384422f72688541a6baf6efbdde09eb`。
- Tests：`tests/Test-G2RuntimeTools.ps1`，
  `99f200a1dd1fa4766ee41865f867282a6fe309588358c496c2491e40a5db4269`。
- Repair audit receipt schema：`repair-audit-receipt.schema.json`，
  `566f97577861ca78e2416dbba7ad383b3ada99c3a0bba65f82d070a56f0c542e`。
- Tool manifest：`runtime-tools.manifest.json`，
  `ef8fadc2fe97e58d857928a1f6ca831e7cd51645b05e336958c3cd06a0e348c0`。
- Contract：`contract.json`，
  `81bce792d0ae3e86d3b311e12b3b8d425eecc422b805ef261d9e64a1161f424b`。
- Tools digest：
  `5cd2474619cd789b734798b38eae1c2c272600c6865ec8bdec809539a6ba502a`。
- 当前实现侧结果为 `116 PASS / 0 FAIL / 188 dynamic negative`；该结果不是独立 audit、
  QA 或 runtime PASS。

工具自测只使用静态 fixture、AST / JSON 和三份脚本的 `StaticSelfTest`；未调用 adb、
设备、网络或 Gradle。Collector 仅对接本合同的 Pad/K60 evidence root；K60 仍必须取得
Pad 全核心 PASS、Pad 锁 `RELEASED_ACTIVE_0` 和 `V2-D210` receipt 后才能登记。
current handoff preflight 为 PASS，实际 `roles=22 / tasks=3 / gates=6 /
currentTransitions=64`。完整 handoff 的现场基线是 `108/108`；本轮未运行该长回归，
必须标为“待 final audit”，历史 `104/104 PASS` 不能当作当前完整数。

## Tool-facing 状态策略

`G2RuntimeStatePolicy.ps1` 是 collector / finalizer / cleanup 的唯一状态门，不使用 OR
接受任意状态。当前合同只允许以下封闭集合：

- `R2_WIP_REPAIR_R3_IN_PROGRESS`：`qaDispatchable=false`、`activePrompt=none`、
  `generatedExecutableQaPrompt=false`、`authorization.qaDispatch=null`；
  `repair-audit-receipt.json` 不得存在；三工具仅允许 `Validate` / `StaticSelfTest`，
  `Register`、`RunWindow`、`Finalize`、`CaptureBefore` / `Verify` 全部拒绝。
- `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA`：只可在合法
  `repair-audit-receipt.json` 下成立，要求 `qaDispatchable=true`，
  `activePrompt=G2_PAD_QA_OWNER_2026_11_06`、`generatedExecutableQaPrompt=true`、
  `contractPackageReady=true`，并携带具体 `authorization.qaDispatch`。descriptor 必须绑定
  canonical read-only preflight、显式 worktree / `codex/v2-integration` / audited head、
  固定读序、`qa-pad-v2/**` 写域、Pad-only / K60 forbidden、单次 10 分钟无 retry、13 个
  product gates、non-goals 与全部 stop conditions；`promptText` 的 UTF-8 SHA-256 必须同时
  等于 contract `promptSha256`、receipt `qaPromptSha256` 与 policy 复算值。Collector 才可
  进入全新 Pad `qa-pad-v2` 的 `Register` / `RunWindow`；K60 predecessor 与锁门保持不变。

receipt schema 为 `aurorama.g2-repair-audit-receipt.v1`，严格 UTF-8 no BOM / LF、
Ordinal 大小写敏感字段集合、重复 JSON 属性与未知字段 fail-closed。receipt 绑定 auditor、
最低审计基线 `6b04eb300256fc8384e5407cee24907e8343f949` 的**严格后继** audit commit 及
其实际 parent、scope、preflight / G2 tools / handoff counts、
tools digest、候选双 SHA、`qa-pad-v1` 五 SHA、外部锁 state / SHA、
`boundaryViolations=0` 与 `nextRole=G2_PAD_QA_OWNER_2026_11_06`。
audit commit 等于最低基线、等于其 parent、或不属于候选 source commit 的后继均拒绝；
future fixture 使用临时 Git object 中的合成严格后继，不使用当前 `HEAD` 相等冒充实审计。

当前真实合同的 `validation.repairAuditExpectations` 仍为
`PENDING_SETTLEMENT_FINAL_AUDIT`，source / counts 全为 null。策略文件自身固定独立 exact
constants：`toolFiles=4`、`testsPassed=116`、`testsFailed=0`、
`dynamicNegative=188`、`currentTransitions=64`、`staticSelfTests=3`、`handoffPassed=108`、
`handoffFailed=0`、`handoffDynamicNegative=0`、`tempCleanupPassed=true`、
`boundaryViolations=0`。future audit 必须让 contract expectations 与 receipt 同时逐项
exact equal 这组策略常量，任一方不能提供或抬高该基线；高位、低位、缺失、null 与类型漂移
全部拒绝。receipt schema 同时以 `const: 64` 固定 `currentTransitions`；同步 `63`、任一层
单独 `63`、缺失、null 与字符串均 fail-closed。runtime manifest 同时要求恰好 11 个唯一路径
和固定 kind 集合。

当前真实合同**没有** `repair-audit-receipt.json`，`authorization.qaDispatch=null`，仍保持
pre-audit / no executable prompt 状态；settled 兼容只在 temp fixture 中验证，本轮不推进
真实状态，也没有生成 PASS receipt。contract 根对象、manifest、authorization、candidate、registration、
session、device-lock 与 runtime report 的状态承载对象均按 Ordinal exact-property 校验。
`Status` / `Validation`、`g2ToolsCounts` / `G2ToolsCounts`、K60
`deviceOrder` / `DeviceOrder`、未知字段与同一对象中的重复属性均有负例覆盖。

## Attempt 状态机

- `qa-pad-v1/` 是不可改写的历史 `PRE_WINDOW_TOOL_BLOCK`：`blocker.json` 固定
  `attemptWindow=NOT_STARTED`、`freshInstall=NOT_RUN`、`coreRuntimeMatrix=NOT_RUN`、
  `windowConsumed=false`；其中的 `device-lock.json` 是历史自持锁，不是权威外部占用。
- 上述五个历史文件（含 blocker 与两份 cleanup 快照）的 SHA-256 已固定在合同内；缺失、
  漂移或出现 `collector-registration.json` / `window-started.json` /
  `collector-session.json` / `report.json` 时新登记失败关闭。
- 新 attempt 只允许写入空的 `qa-pad-v2/` 或 `qa-k60-v2/`。登记前只读核验
  `tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json` 指向的权威外部锁为
  `RELEASED_ACTIVE_0`；若为 ACTIVE 则拒绝。
- 新 scope 注册后只允许 `attemptNumber=1`、连续 `600` 秒、无 retry / extension。
  `window-started.json` 或 `collector-session.json` 已存在时不得再次运行窗口。
- 当前 attempt root 必须完全为空；任何文件、目录、隐藏项或未知内容都拒绝登记。
- 运行时新 scope 的锁必须为 `ACTIVE_COUNT_1`；cleanup 后 finalizer 只接受同一 scope 的
  `RELEASED_ACTIVE_0`。K60 predecessor 必须指向 `qa-pad-v2/report.json`，并绑定该报告与
  锁的 SHA-256。K60 使用前必须重新读取固定 Pad root 的 `report.json` 与
  `device-lock.json`，复算文件 SHA，并现场验证候选、13 gate、verdict、cleanup 和释放锁。
- future K60 cleanup `CaptureBefore` / `Verify` 在 ADB 和目录写入之前执行同一共享
  predecessor 校验，并额外比对 K60 registration 中记录的实际 report / lock SHA；Pad
  report 缺失、非 PASS、旧 scope、SHA 漂移或 ACTIVE lock 时不会进入设备动作。

## 本地验证

```powershell
pwsh -NoProfile -File '.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/tests/Test-G2RuntimeTools.ps1'
pwsh -NoProfile -File '.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/Test-G2EntryRuntimeContract.ps1'
```

合同校验器只读取 APK、JSON、`aapt2` 和 `apksigner`，不调用 `adb`、不联网。
handoff preflight 只在 root 完成状态收口并形成稳定 / 提交对象后，由另一个 fresh audit
执行；dirty WIP 上的 fresh scoped review 不得先跑该套件。

## 当前禁止项

Q03 保持 Open，exact profile / voice 保持 Open，Pad P2 未接受，G1 `NOT_PASSED`，
runtime `UNVERIFIED`，L2/L3 `LOCKED`。本轮只完成兼容实现与实现侧静态门，
`qaDispatchable=false`，等待 fresh audit。下一步由 root 决定是否启动 fresh scoped review；
review 首条只读动作为 `git status --short` 与 `git diff --stat`，确认 WIP 范围后才读 diff。
只有 review、提交、clean、fresh audit receipt 与合同状态事务化回写全部完成后，才可另派
全新 Pad QA。K60 保持锁定，不得安装、运行或提前登记。G2 实现与 Release 仍未授权。
