# Aurorama G2 入口 Runtime 合同

状态：`PAD_RUNTIME_TOOLS_READY_PENDING_INDEPENDENT_QA / K60_GATED_LOCKED`

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

状态：`PAD_RUNTIME_TOOLS_READY_PENDING_INDEPENDENT_QA /
READY_SELF_TESTED_STATIC_ONLY / DEVICE_QA_NOT_RUN`。

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
- Tests：`tests/Test-G2RuntimeTools.ps1`，`48/48 PASS`，含 33 个负例。

工具自测只使用静态 fixture、AST / JSON 和三份脚本的 `StaticSelfTest`；未调用 adb、
设备、网络或 Gradle。Collector 仅对接本合同的 Pad/K60 evidence root；K60 仍必须取得
Pad 全核心 PASS、Pad 锁 `RELEASED_ACTIVE_0` 和 `V2-D210` receipt 后才能登记。

## 本地验证

```powershell
pwsh -NoProfile -File '.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/tests/Test-G2RuntimeTools.ps1'
pwsh -NoProfile -File '.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/Test-G2EntryRuntimeContract.ps1'
pwsh -NoProfile -File 'tools/v2-handoff/Invoke-HandoffPreflight.ps1' -EmitPrompt
```

合同校验器只读取 APK、JSON、`aapt2` 和 `apksigner`，不调用 `adb`、不联网。

## 当前禁止项

Q03 保持 Open，exact profile / voice 保持 Open，Pad P2 未接受，G1 `NOT_PASSED`，
runtime `UNVERIFIED`，L2/L3 `LOCKED`。工具已静态自测完成；随后必须由另一个全新 Pad owner
执行。K60 保持锁定，不得安装、运行或提前登记。G2 实现与 Release 仍未授权。
