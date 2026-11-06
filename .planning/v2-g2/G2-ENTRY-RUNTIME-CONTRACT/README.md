# Aurorama G2 入口 Runtime 合同

状态：`R2_WIP_REPAIR_R3_IN_PROGRESS / qaDispatchable=false /
K60_GATED_LOCKED / PRODUCT_P0_P3_NOT_ASSESSED`

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
  `e00cdcb2c153670a259148f4ed6e34f9b5d46eab26e3ddd8ce2bb6ed6e9a7b6a`。
- Finalizer：`Finalize-G2RuntimeReport.ps1`，
  `bb83072433a0b9e3c4c42dc476a10f6752f0f3f23bdc6090f95fbd86ccc7a628`。
- Cleanup verifier：`Test-G2CleanupRestore.ps1`，
  `eada2d45011ce989a11188f5ed8d9dc24d052f09f7d9fc5a82482353b073087e`。
- Tool manifest：`runtime-tools.manifest.json`，
  `c35f51008be1bcb235a5969d23782856ebac17c721b6edda2be749e00fed9a13`。
- Tools digest：
  `d27166fcc3c5481c1943bdbb0178015caa7a7af5783c35799eb3ed4a976d5c6e`。
- Tests：`tests/Test-G2RuntimeTools.ps1`；实际 PASS 与动态 negative 数只从本文档
  `CHECKPOINT.md` 的现场回读段读取，不把旧 WIP 自报值当验收事实。

工具自测只使用静态 fixture、AST / JSON 和三份脚本的 `StaticSelfTest`；未调用 adb、
设备、网络或 Gradle。Collector 仅对接本合同的 Pad/K60 evidence root；K60 仍必须取得
Pad 全核心 PASS、Pad 锁 `RELEASED_ACTIVE_0` 和 `V2-D210` receipt 后才能登记。

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
runtime `UNVERIFIED`，L2/L3 `LOCKED`。本轮只完成 R3 修复和实现侧静态门，
`qaDispatchable=false`。下一步由 root 决定是否启动 fresh scoped review；review 首条只读
动作为 `git status --short` 与 `git diff --stat`，确认 WIP 范围后才读 diff。K60 保持锁定，
不得安装、运行或提前登记。G2 实现与 Release 仍未授权。
