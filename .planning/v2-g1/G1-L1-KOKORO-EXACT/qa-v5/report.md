# QA-v5 K60 runtime 收敛报告

## 结论

`K60_PASS_PAD_ABORTED_BY_USER`

K60 在固定隔离 harness 上完整通过本轮用户承诺矩阵。Pad 在本消息前已开始安装与长跑，但用户随后明确要求 QA-v5 立即停止且不得继续 Pad；本报告只把 Pad 记为部分执行、已安全清理、`USER_UNLOCK_REQUIRED`，不作 runtime PASS。

这不是生产 runtime、D203、profile freeze、Q03 或 G1 PASS。

## 对象与门禁

- worktree：`F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama`
- branch：`codex/v2-integration`
- HEAD：`1388e00e97e42dc28e53391adb4d0e9e75277204`
- 实现候选：`191a46e227c6cecac14392a2de4bc0a0fae026ee`，祖先检查 exit 0
- 开工 Git clean；结束仅 `qa-v5/` untracked，tracked diff 0
- main：`427,009,671 B / 9bfba9aaaf318a167ea8facf5c109e7f38a208859ca4bbe7bb04fa10ebd6778a`
- peer：`12,696 B / de651ddab3c13110e5230f1df2407048d03170e539785cce8001e662aee8f90c`
- main：arm64、无 INTERNET、一个 `mediaPlayback` FGS service；peer：零权限
- contract：exit 0，`36/0/0/0`，11 份 XML
- 独立构建：纠正本地错误任务名后 exit 0；产物非字节复现，设备只使用冻结副本（P3）

## K60 实测

| 声线 | 首音 | 首句 RTF | 样本聚合 RTF | 峰值 PSS |
|---|---:|---:|---:|---:|
| `zf_001` sid3 | 4,584 ms | 1.146000 | 0.886866 | 924,446 KB |
| `zm_010` sid59 | 2,837 ms | 0.718228 | 0.712673 | 655,124 KB |

30 分钟关屏长跑：

- 窗口 `1,804.924 s`，30/30 采样均 Asleep
- PID、mediaPlayback FGS、MediaSession、notification 均零掉线
- 真实 AudioTrack：165 次 start + 165 次 completion
- 首段 `0–60 s`：5 start / 5 completion
- 中段 `870–930 s`：6 start / 5 completion
- 末段 `1740–1800 s`：6 start / 6 completion
- 队列 `0 → 165/351`，累计真实音频 `1,061,650 ms`
- 窗口内无提前 STOPPED/COMPLETED，无 service-only 空活
- 最长“完成 → 下一次启动”无音频间隔：`7,000 ms`

## 系统控制与恢复

- SystemUI 自定义通知六次实际点击：暂停、下一句、继续、上一句、暂停、停止
- 下一句：index `173 → 174`；上一句日志：`TTS_NAVIGATE from=175 to=174 wasActive=true`
- 停止：`SERVICE_DESTROYED`，FGS/notification/active MediaSession 归零
- OS focus peer：UID `10313` 获 `GAIN_TRANSIENT` 并起真实 AudioTrack；main UID `10312` 收到 `change=-2 LOSS_TRANSIENT`
- peer 释放后 main 保持 `PAUSED`、`autoPlay=false`、`focusHeld=false`、`audioTrackPlaying=false`，新主 AudioTrack=0
- force-stop 后手动启动：同 stable sentence / index 恢复为 PAUSED，8 秒无新 AudioTrack
- 篡改 pack、`zm_009/sid58`：均 fail-closed 到 IDLE，无 AudioTrack/FGS/active MediaSession；原 checkpoint SHA 恢复
- UI cancel：真实点击 `runtime_command:cancel`，`CANCEL_CONVERGED` 与 `CANCEL_PROCESS_EXIT` 后全部资源清空
- music/TTS：全程单 owner；音乐队列保留，TTS 停止后音乐保持 PAUSED，无自动恢复

## 零网络、崩溃与清理

- 飞行模式 `1`、Wi-Fi `0`、active network `none` 时完成两段真实 TTS AudioTrack
- UID `10312` 全 identity blocks 与 `mAppUidStatsMap` 前后均 0 行、0 字节
- main/peer 均无 INTERNET；网络恢复 `0/1/1 + WIFI`
- FATAL / app ANR / InputTimeout = `0/0/0`
- forbidden TTS request = 0；日志中的 15 条 `speaker 58 / zm_009` 文本均来自故意篡改后的拒绝证据
- 两个隔离包均 `Success` 卸载；包/service/notification/active MediaSession/external data/远端 XML 均清空
- K60 生产 release/debug 的 path、SHA、metadata 全 MATCH
- K60 current user 0、Awake、screen 设置 MATCH、effective STREAM_MUSIC 0
- K60 最终 UID netstats 0 B

## Pad 中止记录

- 用户更新前已安装 main/peer，UID `10332/10333`，并完成两条声线的真实音频样本
- Pad 性能信号为 zf 首音/RTF/PSS `8,274 ms / 2.0685 / 875,947 KB`，zm `4,341 ms / 1.098987 / 693,323 KB`；仅作部分执行记录，不作 Pad 结论
- `play_long` 在 `13:05:45` 启动且已有真实 AudioTrack，但中止前 monitor sample=0
- 收到更新后立即 force-stop、双包卸载；包/service/notification/active MediaSession/external data/临时 XML 均清空
- 网络 `0/1/1`、stay-on `7`、screen timeout `2147483647`、lock-after `null`、volume `41/150`、current user 0 恢复
- 生产 release `acb1648d…5bb`、path、metadata MATCH
- 唤醒后 keyguard `showing=true`，未尝试密码/生物识别；状态 `USER_UNLOCK_REQUIRED`

## 问题分级

- K60 P0/P1/P2/P3：`0/0/0/1`
- 唯一 P3：独立 debug 构建非字节复现；设备固定 hash 不受影响
- Pad：无完整 verdict；用户覆盖后不把部分样本提升为结论

## 最大可声明结论

K60 固定 API35 arm64 隔离 harness 的完整 runtime 用户承诺矩阵 PASS，包括 30 分钟真实 AudioTrack、系统通知、OS focus、暂停重建、fail-closed、UI cancel、单音频 owner、零网络、零崩溃和完整清理。

Pad 未完整执行。全局双机 runtime、生产 runtime、D203、Q03、profile freeze 与 G1 均未通过。

## 交接

- 设备锁：K60 `RELEASED`；Pad `RELEASED`；`ACTIVE=0`
- 锁文件 SHA-256：`93D7EACBB57EF0B257C1261BD7BDB7F68D12E5CE592B9A46F4E9E3A27792D20B`
- 下一角色：负责人另派全新小型 Pad QA
- 第一条只读动作：用户解锁后，核验 keyguard=false、两 harness 未安装、`ACTIVE=0`、HEAD/候选祖先与固定 APK hash
- 停止条件：Pad 仍锁屏则不得尝试密码；对象漂移则不安装

机器可读报告：`qa-v5/report.json`。
