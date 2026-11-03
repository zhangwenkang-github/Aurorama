# G1-A2 应用更新信任链合同

状态：`STATIC_CONTRACT_READY_FOR_G2_IMPLEMENTATION / G1_NOT_PASSED / NO_PRODUCTION_WRITE`。

本目录把 `V2-REQ-BASE-002` / `L14-16` 转为可实现、可负测的静态合同。真实 APK、签名、安装器和设备均未执行；fixture 全是合成合同数据，不得冒充真实验签或真机安装结果。

入口顺序：

1. `trust-contract.json`：可信来源、通道、version/versionCode、ABI、size/SHA、package/signer、安装确认与 23 个有序校验门。
2. `state-machine.json`：10 状态与重启恢复规则；下载完成、安装器启动和安装成功严格分离。
3. `trust-matrix.json`：10 条用户承诺及 fixture/property/未覆盖证据。
4. `fixtures/contract-cases.json`：正例与错 ABI、截断、hash、package、signer、downgrade、取消、安装器缺失/权限、重启残留负例。
5. `tests/run-contract-tests.ps1`：结构与 property 测试。
6. `report.json`、`manifest.json`、`commands.log`：结论、机械哈希和实际命令。

关键 fail-closed：

- 无法观察最终重定向 URL、APK 内 ABI 清单或缺失 SHA-256 digest 时不得进入 `VERIFIED`。
- 安装器启动只进入 `INSTALLER_HANDOFF`；缺少目标 package/versionCode/versionName/signer 外部回读时只能 `INSTALL_UNKNOWN`。
- 现有实现的 `versionCode > baseline` 捷径必须被更严格的精确目标回读替换；该缺口由 G2 实现关闭。

边界：不关闭 Q03/Q04，不改变 G1 `NOT_PASSED`，不批准实现、QA、设备、服务器写、Release、commit 或 push。
