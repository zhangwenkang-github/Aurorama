# Kokoro QA-v8 - BLOCKED_TOOL_NONCONVERGENCE

QA-v8 只在 Pad 5 `43af8627` 执行，两次均停在工具门：第一次 prepare 日期解析失败且
finalize 被 OOM 终止；第二次 fresh install / 设备身份 PASS，但 prepare 因遗留 logcat job
不返回，finalize 又在 `[Math]::Min` 参数绑定处失败。核心步骤 `0` 个，
30 分钟 / 样音 / 真实 AudioTrack / PID 均为 `0 / 0 / 0 / none`；K60 未登记、未运行。

因此全矩阵、双机、通知、focus、30 分钟与零网络均为 `NOT_RUN`。产品 P0–P3 为
`NOT_ASSESSED`，不得写成产品 FAIL 或 `PASS_WITH_GAPS`。工具 findings 为重复非收敛 P1
与设备级 manifest `2/19` 漂移 P2。

独立清理 PASS：双包、PID、service、notification、active MediaSession 与 external 均无；
网络 `0/1/1/190`、音量 `41`、屏幕 `2147483647/null/7`、user0、Awake、keyguard=false；
生产 release SHA `acb1648d…2ff5bb` MATCH。工具 digest
`d8e98a90b250bafb1bb48d62f315041fd0f67653d8d81c4650aca9fb617d0bfb`。

- 完整报告：`report.json`
- 第一次失败归档：`attempts/20261105-pad-01-tool-blocked/`
- 第二次安装 / 工具失败：`device-PAD5-43af8627/`
- 最终清理：`device-PAD5-43af8627/cleanup-recovery.json`
- 敏感扫描：`security/sensitive-scan.json`
- 最终清单：`SHA256SUMS`
- 工具摘要：`tools.sha256`

下一步只能由交付负责人决定是否授权一次受控工具修复并另派全新 QA 轮次；不自动重试设备，
也不得把本次结果外推为产品、许可、Q03、G2、G1 或 Release 结论。
