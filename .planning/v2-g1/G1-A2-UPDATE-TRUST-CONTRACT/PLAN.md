# G1-A2 应用更新信任链合同执行计划

> 模式：静态合同证据；不实现生产代码，不访问设备、服务器或安装器。

## 输入与边界

- 唯一工作目录：`F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama`。
- 起始事实：HEAD `287c37e1d1287e2a90c047e628f7272a13cc5970`，分支 `codex/v2-integration`；开工时 docs 三文件已有未提交修改，按既有改动保留。
- 并发观测：执行中共享 worktree 由其他写入者将同一 docs 修改提交为 `6f0f2f1a…`；该提交直接以起始 HEAD 为父且未改 update 源码，未回退、未吸收为本人提交。
- 需求权威：`docs/V2_PLAN.md` §5.10.5 `V2-REQ-BASE-002`、§5.11.5 `UPDATE`、§9 `L14-16`、§12 Q03/Q04/G1 边界；`docs/DECISIONS.md` `V2-D196/D197/D199/D200`；规则 `R020/R025/R027/R028`。
- 实现证据：只读现有 update 选包、包校验与安装交接路径；不修改、构建或运行生产测试。
- 写域：仅本目录。禁止改生产代码、资源、schema、docs、设备、服务器、提交或推送。

## 合同结论目标

1. 固定 trusted source/channel：仅允许指定 GitHub repository 的 stable release；draft、prerelease、tag/version 不一致、非预期 asset URL、未知通道均 FAIL_CLOSED。
2. 固定校验权威与顺序：来源/通道与版本候选 → ABI 选包 → size/长度 → SHA-256 → APK package identity/versionCode + versionName/downgrade → signer continuity → VERIFIED → 显式用户/系统安装授权 → INSTALLER_HANDOFF。
3. 固定状态机：`DISCOVERED → DOWNLOADING → DOWNLOADED_UNVERIFIED → VERIFIED → INSTALL_AUTHORIZED → INSTALLER_HANDOFF → INSTALL_CONFIRMED | INSTALL_UNKNOWN | FAILED | CANCELLED`。
4. 下载完成只进入 `DOWNLOADED_UNVERIFIED`；安装器启动只进入 `INSTALLER_HANDOFF`；无外部回读证据时只能 `INSTALL_UNKNOWN`，有目标版本与当前安装包回读证据时才可 `INSTALL_CONFIRMED`。
5. 无可靠 ABI/签名/安装结果 API 表达时显式 FAIL_CLOSED 或 UNKNOWN，不推断成功。

## 交付物

- `README.md`：短入口、权威、状态与未覆盖边界。
- `trust-contract.json`：来源、通道、版本、ABI、大小、哈希、包身份、签名、安装交接、恢复规则。
- `trust-matrix.json`：用户承诺 → 入口 → 操作 → 可观察结果 → 权威/顺序 → 失败可见 → fixture/property 证据 → 未覆盖。
- `state-machine.json`：规定状态、合法迁移、终态和恢复语义。
- `fixtures/contract-cases.json`：正例与错 ABI、截断、hash mismatch、wrong package、wrong signer、downgrade、取消、installer absent/permission denied、重启残留。
- `tests/run-contract-tests.ps1`：读取 fixture/property，验证信任不变量；正测 exit 0，破坏不变量负测 exit 1。
- `manifest.json`：除自身外的文件 SHA-256、bytes、LF 计数。
- `commands.log`：实际只读检查、测试正负门和验证命令。
- `report.json`：结论、计数、门禁、未覆盖与 P0–P3。

## 执行顺序

1. 只读核对最多 3 个现有 update 实现文件，提取实际 source/channel、ABI、包校验、安装器交接与回读能力。
2. 编写合同、状态机、用户承诺矩阵和合成 fixture；所有签名/安装字段明确标记为合同数据，不冒充真实包或设备证据。
3. 编写静态 property tests：合法链可达性、非法捷径拒绝、下载≠安装、无确认=UNKNOWN、重启残留重校验/不可确认。
4. 运行正测和确定性破坏负测，记录退出码与实际执行。
5. 校验 JSON、LF、manifest、敏感值模式、写域和 git 状态；生成最终 manifest/report。
6. 执行 Engramory 收尾判断，报告记忆同步与剩余设备/真实链路缺口。

## 验收

- 每项安全校验均有 authority/order/failure-visible。
- 负例全部被拒绝或显式降级，且失败可见。
- 正测 exit 0；破坏一项信任不变量后 exit 1。
- Q03/Q04/G1 状态边界不被本任务改变。
- 最终结论最多 15 行，含状态、计数、关键 fail-closed、路径/hash/bytes、测试、未覆盖、P0–P3 与水位。
