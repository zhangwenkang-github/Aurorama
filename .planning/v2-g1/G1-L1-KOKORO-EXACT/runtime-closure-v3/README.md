# Kokoro stop-path repair v3

本轮只修复 `REV6-P1-01`：通知停止或 service teardown 不得在
`OfflineTts.generateWithCallback` 仍占用 native 对象时释放 `OfflineTts`。

固定 v4 在 Pad `43af8627` 复现了
`FORTIFY: pthread_mutex_lock called on a destroyed mutex`、`SIGABRT` 和
`ApplicationExitInfo reason=5 APP CRASH(NATIVE)`。v5 通过
`NativeInferenceLifecycle` 将 teardown 延迟到推理 worker 收口后执行，并通过
推理中 stop 与 playing 中 stop 两类边界自测。

- 实现报告：`implementation-report.json`
- TDD 日志：`logs/native-inference-lifecycle-red.log`、`logs/native-inference-lifecycle-green.log`
- 新 APK 审计：`artifacts/artifact-audit.json`
- v4 复现证据：`evidence/repro-v4-before-home/`
- v5 自测证据：`evidence/fixed-v5-inference-stop/`、`evidence/fixed-v5-playing-stop/`
- 清理与锁：`evidence/cleanup/device-after.json`

边界：这是实现侧最小自测，不是独立 QA、完整 30 分钟、双机矩阵、生产 runtime、
profile freeze、Q03 或 G1 PASS。下一步必须由负责人另派不同全新 QA 先做
stop-only，再在新候选上完整重验。
