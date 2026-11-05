# Kokoro QA-v7 — Pad stop-only / native crash gate

结论：PASS_STOP_ONLY。在 Pad 5 43af8627 的 fresh install 新版 main/peer 上，
推理中通知 Stop 与真实 AudioTrack playing 中通知 Stop 均未出现当前运行 native crash、
Java FATAL、ANR、InputTimeout、tombstone 或新 AudioTrack，PID 存活且资源收敛。

本结果只关闭 REV6-P1-01 的 stop-only / native-crash 门；不覆盖完整 30 分钟、
K60 / 双机矩阵、Pad P2 性能、许可、生产 runtime、Q03、G2 或 G1。

- 报告：`report.json`
- 推理 Stop：`raw/inference-stop/`
- 播放 Stop：`raw/playing-stop/`
- 清理恢复：`raw/cleanup/`
- 工具：`tools/`

所有终端与 ADB 仅使用 PowerShell 7；截图 0；未保留未过滤宿主日志或通知 XML。
