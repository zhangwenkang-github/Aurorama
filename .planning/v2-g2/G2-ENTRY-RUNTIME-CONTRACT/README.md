# Aurorama G2 入口 Runtime 合同

> **2026-11-07 当前状态**：`PLAN_A_STATE_MODEL_REVIEW_PASS /
> SETTLEMENT_COMMIT=241b8b356e2aab4d1ef43016639dc0203eb10f7d /
> CLEAN_PREFLIGHT_PASS`。下方 `PENDING_FRESH_REVIEW` 文案只作 pre-review 证据。
> 用户已选择方案 A：receipt / contract / policy / schema 的 `currentTransitions=64`
> 是不可变 audit-time snapshot；root settlement 提交后的 canonical preflight 可记录不同
> live transition count（已实测 65），该值只作 observation，不与 64 比较。fresh review
> 已 PASS，settlement commit `241b8b35` 已 clean，canonical preflight 已 PASS；Pad QA
> 具备派发条件但尚未派发。其他 live branch / HEAD / dirty / candidate / tool / manifest /
> lock / receipt 漂移仍 fail-closed；K60 锁定。

状态：`PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA /
qaDispatchable=true / activePrompt=G2_PAD_QA_OWNER_2026_11_06 /
AUDIT_RECEIPT_PASS / PAD_QA_PROMPT_DESCRIPTOR_READY_PENDING_FRESH_REVIEW /
PAD_QA_NOT_EXECUTED / WIP=PLAN_A_STATE_MODEL_REDESIGN_PATCHED_PENDING_FRESH_REVIEW /
AUDIT_SNAPSHOT_CURRENT_TRANSITIONS_EXACT_64 /
LIVE_PREFLIGHT_TRANSITIONS=OBSERVED_NOT_RECEIPT_BOUND /
K60_GATED_LOCKED /
PRODUCT_P0_P3_NOT_ASSESSED`

本目录是全新的 G2 runtime 合同与证据边界。用户已通过 `V2-D210` 授权本合同下的运行时
工具建设和设备 QA；实际 Pad dispatch 仍必须等 fresh review PASS、root settlement commit
clean 与 canonical preflight PASS。K60 必须等待 Pad 全核心 PASS 且锁释放后另派全新 owner。
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

状态：`PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA / qaDispatchable=true /
DEVICE_QA_NOT_RUN /
PLAN_A_STATE_MODEL_REDESIGN_PATCHED_PENDING_FRESH_REVIEW`。

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
  `5562c1dcccbb9079794badf21b6aef22a116c8f3b3ac35286442247f170dbbd5`。
- Contract：`contract.json`，
  `70d3b869cfb33f34f372c7de9e13cb5198f5e8502dc6c07383bb559e9f91bad0`。
- Repair audit receipt：`repair-audit-receipt.json`，
  `c7d9005143231e6cc2661c9476380d4194f8d6dab90fdd8d61c5e8e9c3219a9d`。
- Pad QA prompt SHA-256：
  `5216b3d0e401aa9a67136762e155505a636300ccb27c715abe5e06a1a29d7172`。
- Tools digest：
  `5cd2474619cd789b734798b38eae1c2c272600c6865ec8bdec809539a6ba502a`。
- 独立 final audit 已 PASS：G2 tools `116 PASS / 0 FAIL / 188 dynamic negative`、
  handoff `108/0`、clean witness `33/33`、runtime manifest `11/11`。该结果只证明工具与
  contract audit，不代表 QA 或 runtime PASS。

工具自测只使用静态 fixture、AST / JSON 和三份脚本的 `StaticSelfTest`；未调用 adb、
设备、网络或 Gradle。Collector 仅对接本合同的 Pad/K60 evidence root；K60 仍必须取得
Pad 全核心 PASS、Pad 锁 `RELEASED_ACTIVE_0` 和 `V2-D210` receipt 后才能登记。
final audit 时的 handoff preflight snapshot 为 `roles=22 / tasks=3 / gates=6 /
currentTransitions=64`；该 64 是 immutable audit-time snapshot，不是 settlement 后
live equality oracle。root settlement 提交后，QA 必须从新的 canonical preflight 读取并
记录 `LIVE_PREFLIGHT_TRANSITIONS=OBSERVED_NOT_RECEIPT_BOUND`（当前推导 65），不得将
该值与 receipt / contract 的 64 比较。当前 dirty WIP 不运行 preflight。完整 handoff 的
audit 结果为 `108/0 + TEMP_CLEANUP=PASS`；历史 `104/104 PASS` 只是旧快照。

## Tool-facing 状态策略

`G2RuntimeStatePolicy.ps1` 是 collector / finalizer / cleanup 的唯一状态门，不使用 OR
接受任意状态。封闭集合如下：

- `R2_WIP_REPAIR_R3_IN_PROGRESS`（历史 PRE）：`qaDispatchable=false`、`activePrompt=none`、
  `generatedExecutableQaPrompt=false`、`authorization.qaDispatch=null`；
  `repair-audit-receipt.json` 不得存在；三工具仅允许 `Validate` / `StaticSelfTest`，
  `Register`、`RunWindow`、`Finalize`、`CaptureBefore` / `Verify` 全部拒绝。
- `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA`（当前真实状态）：只可在合法
  `repair-audit-receipt.json` 下成立，要求 `qaDispatchable=true`，
  `activePrompt=G2_PAD_QA_OWNER_2026_11_06`、`generatedExecutableQaPrompt=true`、
  `contractPackageReady=true`，并携带具体 `authorization.qaDispatch`。descriptor 必须绑定
  canonical read-only preflight、显式 worktree / `codex/v2-integration` / audited source binding、
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

当前真实合同的 `validation.repairAuditExpectations` 已由 final audit settlement 填为
`SETTLED_FROM_FINAL_AUDIT`。策略文件自身固定独立 exact
constants：`toolFiles=4`、`testsPassed=116`、`testsFailed=0`、
`dynamicNegative=188`、`currentTransitions=64`、`staticSelfTests=3`、`handoffPassed=108`、
`handoffFailed=0`、`handoffDynamicNegative=0`、`tempCleanupPassed=true`、
`boundaryViolations=0`。future audit 必须让 contract expectations 与 receipt 同时逐项
exact equal 这组策略常量，任一方不能提供或抬高该基线；高位、低位、缺失、null 与类型漂移
全部拒绝。这里的 `currentTransitions=64` 仅指 final audit 前的 immutable audit-time
snapshot；receipt schema 的 `const: 64` 继续固定该历史事实。settlement 后 canonical
preflight 的 live `currentTransitions`（当前推导 65）只记录为
`OBSERVED_NOT_RECEIPT_BOUND`，不在 receipt / contract / QA live gate 中与 64 比较。
runtime manifest 同时要求恰好 11 个唯一路径和固定 kind 集合。

当前真实合同已有 schema 合法的 `repair-audit-receipt.json`，
`authorization.qaDispatch` 与可执行 Pad QA prompt 已生成；receipt、contract descriptor 与
policy 复算的 `promptSha256` 三方一致。`contractPackageReady=true`、`qaDispatchable=true`、
`activePrompt=G2_PAD_QA_OWNER_2026_11_06`，但这是未提交 WIP，fresh review PASS、root
commit clean 与 canonical preflight PASS 前不可派发；Pad QA 尚未执行。contract 根对象、manifest、
authorization、candidate、registration、
session、device-lock 与 runtime report 的状态承载对象均按 Ordinal exact-property 校验。
`Status` / `Validation`、`g2ToolsCounts` / `G2ToolsCounts`、K60
`deviceOrder` / `DeviceOrder`、未知字段与同一对象中的重复属性均有负例覆盖。

`qaDispatch.head` / `headExpectation` 是 policy 要求的 audited source/provenance binding，
不是 QA live checkout HEAD。`POST_QA_RUNTIME_DEFERRED` 和 canonical preflight emitted
`NEXT_PROMPT_BEGIN` 保留为审计时 handoff-bootstrap 历史措辞；preflight exit / PASS 仍是机械
门。只有 settlement 提交 clean 且 preflight PASS 后，`qaDispatch.promptText` 才覆盖历史
emitted prompt 作为可执行派发源。QA 的 live HEAD 必须从 clean settlement 后的
canonical preflight 结果读取并与合同一致；合法 settlement 后继不得因不等于 audited SHA
误停。receipt / contract 的 `currentTransitions=64` 是 audit snapshot；live transition
count 单独记录且不与 64 比较。其他 live HEAD / branch / dirty / candidate / tool /
manifest / lock / receipt 漂移仍 `BLOCKED`。

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

Plan A patch 的本轮唯一一次 `Test-G2EntryRuntimeContract.ps1` 已返回
`PASS / exit 0`，`TOOL_STATE_MODE=AUDIT_PASS_PENDING_FRESH_PAD_QA`。这是合同静态一致性结果，
不是 fresh review、QA 或 runtime PASS，也不构成 dirty WIP dispatch 授权。

```powershell
pwsh -NoProfile -File '.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/tests/Test-G2RuntimeTools.ps1'
pwsh -NoProfile -File '.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/Test-G2EntryRuntimeContract.ps1'
```

合同校验器只读取 APK、JSON、`aapt2` 和 `apksigner`，不调用 `adb`、不联网。
handoff preflight 只在 root 完成状态收口并形成稳定 / 提交对象后，由另一个 fresh audit
执行；dirty WIP 上的 fresh scoped review 不得先跑该套件。

## 当前禁止项

Q03 保持 Open，exact profile / voice 保持 Open，Pad P2 未接受，G1 `NOT_PASSED`，
runtime `UNVERIFIED`，L2/L3 `LOCKED`。方案 A 已端到端修补 round-2 的 P1-01/P1-02，
本轮未执行 QA。下一步只由 root 启动 fresh review；review 首条只读动作为
`git status --short` 与 `git diff --stat`，确认 Plan A patch 范围后才读 diff。fresh review
PASS 后 root 才可提交；commit clean 与重新预检全部完成后，才可另派全新 Pad QA。K60
保持锁定，不得安装、运行或提前登记。G2 实现与 Release 仍未授权。
