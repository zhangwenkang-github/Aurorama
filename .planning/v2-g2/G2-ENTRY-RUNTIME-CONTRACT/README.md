# Aurorama G2 入口 Runtime 合同

状态：`USER_RESUMED_G2_TOOL_REPAIR_HANDOFF /
PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT / K60_GATED_LOCKED`

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

状态：`PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT /
READY_SELF_TESTED_STATIC_ONLY / DEVICE_QA_NOT_RUN`。

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
- Tests：`tests/Test-G2RuntimeTools.ps1`，`57/57 PASS`，含 37 个负例。

工具自测只使用静态 fixture、AST / JSON 和三份脚本的 `StaticSelfTest`；未调用 adb、
设备、网络或 Gradle。Collector 仅对接本合同的 Pad/K60 evidence root；K60 仍必须取得
Pad 全核心 PASS、Pad 锁 `RELEASED_ACTIVE_0` 和 `V2-D210` receipt 后才能登记。
本轮修复将 repo-relative 工具路径与 evidence-relative 合同路径分开解析，并把 cleanup
判定收紧为当前 focus stack、真实路径状态、当前 session 进程与外部锁回读。

## 本地验证

```powershell
pwsh -NoProfile -File '.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/tests/Test-G2RuntimeTools.ps1'
pwsh -NoProfile -File '.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/Test-G2EntryRuntimeContract.ps1'
pwsh -NoProfile -File 'tools/v2-handoff/Invoke-HandoffPreflight.ps1' -EmitPrompt
```

合同校验器只读取 APK、JSON、`aapt2` 和 `apksigner`，不调用 `adb`、不联网。

## 当前禁止项

Q03 保持 Open，exact profile / voice 保持 Open，Pad P2 未接受，G1 `NOT_PASSED`，
runtime `UNVERIFIED`，L2/L3 `LOCKED`。工具修复 checkpoint 已完成，用户已恢复并要求新会话
交接；下一会话只接 fresh read-only repair audit，`qaDispatchable=false`，不得直接接
Pad/K60 QA。K60 保持锁定，不得安装、运行或提前登记。G2 实现与 Release 仍未授权。
