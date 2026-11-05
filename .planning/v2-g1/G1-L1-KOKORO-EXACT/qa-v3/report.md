# G1-L1-KOKORO-EXACT · Independent QA V3

## 结论

**verdict：`FAIL`**

固定候选的构建、双声音、真实 AudioTrack、前台 service、MediaSession、恢复、取消、互斥和离线运行大多已执行通过；但 30 分钟关屏窗口内真实音频只到关屏后 **93.819 s**，9/9 句结束后只剩 service 与进程存活。核心“连续播放 30 分钟”承诺未满足，因此不能给 `PASS` 或 `PASS_WITH_GAPS`。

| 固定事实 | 值 |
|---|---|
| branch / HEAD | `codex/v2-integration` / `a986f2e2ccf0e5d7f95e219f68ff813571e1ee33` |
| APK | `427,005,490 B` / `074ccfe96b6868c3ae9e02523a9bb9d7da2e35594cedc70721bbbca1287015e8` |
| device | K60 `8e875894`；Pad `43af8627` 零针对性命令 |
| contract tests | **28 executed / 0 failure / 0 error / 0 skip**，exit 0 |
| final APK restoration | 独立 build 后已恢复 expected size/SHA |

## 用户承诺矩阵

| # | 承诺 | K60 实际结果 | 证据 | 状态 |
|---:|---|---|---|---|
| 1 | 独立测试 / 构建 / 固定 APK 身份 | 28/0/0/0；build exit 0；包名、arm64、无 INTERNET、FGS mediaPlayback、单 service/MediaSession/AudioTrack 构造点通过 | `contract-test-summary.json`、`build-analysis.json` | PASS_WITH_P3 |
| 2 | K60 串行锁与设备快照 | 初始 ACTIVE=0，QA-V3 锁唯一，显式 serial；Pad 零命令 | `device-before.json`、device-lock | PASS |
| 3 | zf_001 sid3 / zm_010 sid59 真实 runtime | zf RTF `0.72875` / PSS `721705 KB`；zm RTF `0.71975` / PSS `722487 KB`；均真实 AudioTrack | `zf-launch.json`、`zf-warm-pss.json`、`zm-runtime.json`、runtime log | PASS |
| 4 | Activity 退出后前台 service owner | HOME 后 PID 不变、`isForeground=true`、`types=0x2`、单活动 MediaSession | `activity-background-owner.json` | PASS |
| 5 | UI / MediaSession / 通知控制，不伪造 seek | UI 与 MediaSession 覆盖 play/pause/resume/prev/next/stop；通知卡实际点击覆盖 pause/resume/prev/next | `controls.json`、`media-*.json`、`notification-*-tap.json` | PARTIAL |
| 6 | 关屏真实连续播放 30 分钟 | 1801.002 s 内零唤醒违规；真实音频最晚在 93.819 s，随后 9/9 完成、STOPPED；service 空活 | `background-30m.json`、`background-30m-samples.jsonl` | **FAIL** |
| 7 | force-stop 后恢复 + 篡改 checkpoint | 同第 7/9 句恢复 PAUSED、autoPlay=false、无 AudioTrack/focus；fake pack、zm_009/sid58 均拒绝 | `recreate-*.json`、`tamper-*.json` | PASS |
| 8 | UI cancel 收敛 | PID/service/通知/MediaSession 消失；无 WAV/tmp/part/partial；无 PLAYING 残留 | `ui-cancel.json` | PASS |
| 9 | AudioFocus | 真实 GAIN request 与 focus stack 已捕获；无授权第二音频 owner，OS-origin loss 未执行 | `audio-focus.json` | PARTIAL / NOT_RUN |
| 10 | 本地音乐互斥 | music→TTS 保留队列并暂停；TTS stop 后 music 仍 paused；TTS 活跃时 music restart 先终止 TTS；捕获时仅一个 started AudioTrack | `music-*.json`、`tts-*.json` | PASS_ISOLATED_ONLY |
| 11 | 零网络完整推理 | airplane=1、wifi=0、active=none；两段 AudioTrack 完成；UID 全分块与 app-map delta=0；网络设置恢复 MATCH | `zero-network.json`、`netstats/*.txt` | PASS（空 UID 计数器限制见报告） |
| 12 | 四层真值一致 / 0 FATAL·ANR·InputTimeout / speaker58 零调用 | PAUSED/PLAYING/STOPPED/COMPLETED/ERROR 已核对；日志扫描 0 崩溃/ANR/timeout；68 次 TTS 请求均只使用 sid3/sid59 | `states/`、`crash-anr-input.json`、`speaker-whitelist.json` | PASS |
| 13 | 清理与恢复 | uninstall Success，临时 XML 0，生产包逐项 MATCH，网络/音量/屏幕/user 恢复，lock ACTIVE=0 | `uninstall.json`、`cleanup-comparison.json`、`device-after.json` | PASS |

## 计划 / 结果边界

| 级别 | 项目 |
|---|---|
| P0 | 无 |
| P1 | **30 分钟连续播放失败**：固定 9 句队列约 95 s 音频；真实音频最后到关屏后 93.819 s。需要 service-owned 的持续内容源或经批准的后续生成策略。 |
| P2 | MIUI SystemUI media card 未暴露“停止”按钮；NotificationRecord 有 action，但实际可点击节点只有上一/播放暂停/下一。 |
| P3 | 独立 debug build 非字节复现（D8 synthetic lambda/metadata）；长关屏测试必须先中和 K60 `stay_on_while_plugged_in=15`。 |

## 未覆盖 / 未执行推断

- Pad 5：`NOT_RUN_USER_UNLOCK_REQUIRED`；未发送任何 Pad 命令。
- OS-origin AudioFocus loss：`NOT_RUN`；没有授权第二音频 owner，未用同进程 harness 模拟冒充。
- 锁屏实际按钮：K60 唤醒直接回到 app，未出现可见 keyguard media card；没有尝试绕过。
- 通知“停止”按钮：`NOT_RUN_NOT_EXPOSED`。
- 主观听觉、蓝牙/耳机、生产 Aurorama、生产音乐、voice-pack 生命周期、许可分发、D203、Q03、profile freeze、Release、G1 PASS 均未执行。
- 不把“service 存活”推断为“音频连续”，不把本地音乐模拟推断为生产 music，不把独立 build 推断为候选 APK 字节复现。

## 零网络与 AudioFocus 限定

UID `10309` 在 before/after 的 traffic identity 行和 `mAppUidStatsMap` 行均为 0，差值为 0；这是“空集合零增量”，不是有一笔已知大流量后的校准结果。真实离线播放、无 INTERNET权限和网络恢复 MATCH 作为交叉证据保留。

AudioFocus 已看到真实 `GAIN` stack entry（UID 10309、`CONTENT_TYPE_SPEECH`、`PAUSES_ON_DUCKABLE_LOSS`）。没有安全的第二音频 owner，所以不能声明系统真实 focus loss 已通过。

## 证据与清理

证据根：`.planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v3/`。关键文件：`report.json`、`background-30m.json`、`zero-network.json`、`controls.json`、`speaker-whitelist.json`、`crash-anr-input.json`、`cleanup-comparison.json`。

harness 已卸载；PID、service、通知、MediaSession、包专属 external files 与 `/sdcard`、`/data/local/tmp` 的本轮 XML 均消失。生产 release/debug 的路径、size、SHA、dataDir、安装时间 MATCH；网络设置、音乐音量 0、屏幕 `Awake / 600000 / null / 15 / brightness 2 / mode 1`、current user 0 恢复。设备锁已 RELEASED，`ACTIVE=0`。

## 记忆同步

`added: none`；`updated: none`；`archived: none`；`skipped`：用户白名单只允许 `qa-v3` 与 device-lock，未写 Engramory。索引 **121 行 / 16,037 B**；check OK；doctor clean（仅 `leader-delegation-by-role.md` 13.1 KB info warning）。

## 最大可声明结论

该固定 harness 在 K60 上可完成双批准声线、单 AudioTrack/MediaSession/FGS、控制、暂停恢复、校验拒绝、取消、短时后台 owner、隔离音乐互斥与离线完整播放；但 **不满足 30 分钟后台连续朗读**，不能据此宣称生产 TTS、D203、G1、Q03、profile freeze 或 Release PASS。

## 下一步

由负责人另派**全新实现代理**：先读本报告、`background-30m.json` 和 `background-30m-samples.jsonl`，再提供 service-owned 的 30 分钟以上真实内容源或经批准的持续生成策略；不得用空跑、进程存活、手工反复启动或自动恢复已明确暂停/停止的会话冒充连续播放。修复后必须由另一名全新独立 QA 重跑 30 分钟窗口、通知停止路径和固定设备矩阵。
