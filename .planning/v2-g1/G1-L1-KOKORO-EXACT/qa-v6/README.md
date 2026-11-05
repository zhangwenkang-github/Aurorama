# Kokoro Pad QA v6

固定 final APK 在 Xiaomi Pad 5 `43af8627`（API 33 / arm64-v8a）上的独立差异化 runtime QA。

- 结论：**FAIL**，`FAIL_P1_NATIVE_CRASH_AFTER_STOP / PAD_PERFORMANCE_P2`；P0/P1/P2/P3=`0/1/1/0`。
- 通知停止：控件可点、service/notification/MediaSession 表面收敛，但 `pid 9571` 随后在 `kokoro-tts-runt` 中产生 ONNX/Sherpa `F DEBUG` 栈；`ApplicationExitInfo` 为 `reason=5 APP CRASH(NATIVE) status=6`（信号 6 / SIGABRT）。这不是后续的 intentional force-stop。
- `zf_001` 最终样本：首音 6,416 ms、首句 RTF 1.597500、聚合 RTF 1.301791、峰值 PSS 884,597 KB；最差有效样本：7,625 ms、1.87875、峰值 956,214 KB。`zm_010`：4,303 ms、1.083291、1.093069、700,338 KB。
- 30 分钟：1,800.865 s、30/30 Asleep、单 PID、134 starts / 135 completions、首/中/末段均有证据、队列 `0→135/351`、最长事件间隔 9,629 ms。
- 30 分钟锁屏 PASS 不能抵消通知停止 P1；整体 QA 仍为 FAIL。final APK UID 全分块零网络、Java FATAL/ANR/InputTimeout=`0/0/0`、禁止请求 0 为独立窄结果，但 native crash=1。
- 清理与恢复 PASS；双包无残留，生产 release 哈希/路径/metadata MATCH，Pad 锁 `RELEASED / ACTIVE=0`。
- 证据卫生：2 份 netstats 共脱敏 2,996 个 `wifiNetworkKey` 值并重新复算 UID 10334 全块 delta=0；完整 lock 历史与未脱敏副本均未保留；失败安装时的无关 launcher XML 已最小化。
- 敏感扫描只保存文件名与计数：非脱敏网络键、网络身份值、凭据值和非 XML namespace URL 均为 0；40 个 XML namespace URL、60 个 `password="false"` 和 5 个 `token` 标签仅为结构/UI 元数据。
- 最终 73/73 个交付文本文件通过严格 UTF-8、无 BOM、LF 检查；71 项 SHA 清单不含自身与 receipt。

## 证据索引

- `report.json`：最终报告、对象门、矩阵、finding、未覆盖与下一步。
- `raw/install/`：APK 哈希、MIUI 安装、包/权限/ABI/manifest 审计。
- `raw/notification-permission/`：API 33 系统通知权限弹窗与授权结果。
- `raw/voices/`：两条声线真实 AudioTrack、RTF、PSS 与受控重跑原始数据。
- `raw/long-run/`：30 分钟逐分钟 JSONL、原始脚本汇总、修正分析、音频事件和锁屏恢复。
- `raw/notification-stop/`：通知停止控件、表面资源收敛及停止路径 FAIL 结论。
- `raw/zero-network/netstats/`：离线播放前后已脱敏 netstats、结构计数与 UID delta 复算。
- `raw/crash-forbidden/`：`REV6-P1-01` 的 `F DEBUG`、signal/exit-info 关联、包过滤日志与禁止声线计数；01:xx 旧 events 文件已明确标为 NON-EVIDENCE。
- `raw/logs/`：1,017 行任务过滤宿主日志与提取元数据；628,978 B 未过滤宿主日志已删除。
- `raw/cleanup/`：卸载事件、设备前后快照与恢复 MATCH。
- `raw/device-lock-before.json`：开工 lock 的最小非敏感元数据。
- `raw/security/sensitive-scan.json`：仅文件名/模式计数的敏感扫描结果。
- `.gitattributes`：限定本目录 JSON/JSONL/TXT/MD/XML 与 `SHA256SUMS` 使用 LF。
- `SHA256SUMS`：覆盖当前 71 个交付文件，清单自身不纳入自身哈希。
- `integrity-receipt.json`：发布/交接 receipt，覆盖 SHA 清单、report 与 sensitive scan 的最终哈希；自身不计入 `SHA256SUMS`。
- `.gitignore`：仅反忽略两份任务过滤日志与六个 `tools/*.ps1`；8/8 目标可由 `git ls-files --others --exclude-standard` 导出。`tmp/`、APK、图片与未过滤 host log 均显式排除。

## 边界

本轮不重复 K60 的 OS focus、tamper、音乐互斥、进程重建和四控件矩阵；不证明生产 Aurorama 集成、voice/profile 冻结、许可、Q03、G2、G1 或 Release。保留 P2：Pad 的 `zf_001` 可超过实时且峰值 PSS 约 956 MB。修复与修复后独立复测均未在本轮执行。
