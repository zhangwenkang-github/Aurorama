# INDEPENDENT QA V2: PARTIAL

任务：`V2-G1-L1-KOKORO-EXACT`
生成时间：2026-11-05T02:25:38.8790456+08:00

## 结论

隔离 harness 的两设备离线推理、契约 WAV、PSS、长文、零网络和 Pad API 33 P1 复验通过；产品级后台 owner、持久 resume、真实音频 owner / MediaSession / audio focus 未实现，按指定状态保持 BLOCKED。Pad 短关屏探针触发原有数字密码锁屏，未尝试绕过；其余清理已闭环。

## 固定对象

- branch：`codex/v2-integration`
- HEAD：`cb68738e6dd81cfcce7e224aabb361876286e636`
- parent：`c1974d9291edb2dc9ff40692a4778f2cfc1be77b`
- APK：`426,980,914 B` / `051a73dadb1b9b239f95e543e24dfdbeebf82c026d46652f3b392559e2357826`
- package：`dev.aurorama.kokoro.harness`；arm64-v8a；无 INTERNET；APK v2/v3 debug 签名有效
- DEX：`classes.dex` SHA-256 `d7002a7ba73ba73825f965f0532cd52b89db961b1dbf52275dddb8fa3c0aa899`；HexFormat 符号命中 0

## K60 / API 35

| voice | load ms | first ms | inference ms | audio s | RTF | PSS peak KB | samples / missing / max gap s |
|---|---:|---:|---:|---:|---:|---:|---:|
| zf_001 sid3 | 1611 | 2833 | 40469 | 58.075 | 0.696840 | 738090 | 36 / 0 / 1.218 |
| zm_010 sid59 | 1528 | 2801 | 40882 | 58.725 | 0.696160 | 737201 | 37 / 0 / 1.216 |

WAV SHA-256：zf `3d776f6cd8c931cd02daabff2caa4444efa9232c9e718bd62d05fb586f42a2a7`；zm `5f2fad7de2b7a40d76f17e14d09b87f3c2918bec749ef8b51353ad80646cba60`。

## Pad 5 / API 33

| voice | load ms | first ms | inference ms | audio s | RTF | PSS peak KB | samples / missing / max gap s |
|---|---:|---:|---:|---:|---:|---:|---:|
| zf_001 sid3 | 2415 | 4324 | 65179 | 58.075 | 1.122325 | 848051 | 57 / 0 / 1.215 |
| zm_010 sid59 | 1745 | 4272 | 67121 | 58.725 | 1.142971 | 919625 | 59 / 0 / 1.213 |

WAV SHA-256：zf `3361d3ad89a2b35938bfbf8a9e41905bb8116fd0b85cf62fbe29664785c327bb`；zm `afa0f4a934989d59fc91af13245b56af8d6b17017e6020d9e7ff40e7d9c8653b`。

Pad API 33 两路均 `PASS`；`NoClassDefFoundError` / `java.util.HexFormat` 运行日志命中 0，DEX 符号命中 0。原 P1 在本轮执行中未再出现。

## 长文

| device | chars / Han | duration s | inference ms | RTF | PSS peak KB | samples / missing | result |
|---|---:|---:|---:|---:|---:|---:|---|
| K60 | 401 / 356 | 116.300 | 81319 | 0.699218 | 739355 | 70 / 0 | COMPLETE / no crash |
| Pad 5 | 401 / 356 | 116.300 | 134322 | 1.154961 | 925482 | 115 / 0 | COMPLETE / no crash |

长文 WAV SHA-256：K60 `7b6efe2e86eea6cd1a1c09928f6f39be4c6ea4a7c46ac26b3eb40669aed7522f`；Pad 5 `59595a9ed114c7422d3f1f38cf378eb2e8060c93955a19281fadeb1113288f3f`。

## 零网络

- K60：UID `10305`，airplane=`1`、Wi-Fi=`0`、蜂窝=`1`、活动网络=`none`；identity Δ=`0`，appUidStatsMap Δ=`0`。
- Pad 5：UID `10331`，airplane=`1`、Wi-Fi=`0`、蜂窝=`0`、活动网络=`none`；identity Δ=`0`，appUidStatsMap Δ=`0`。

两机均在网络关闭时完成一次完整推理，网络已恢复。Android Netstats 口径覆盖该 UID 全部 ownership identity 块与 BPF `mAppUidStatsMap`。

K60 的 MIUI 在飞行模式下保留 `mobile_data` 用户偏好为 1；`cmd phone data disable` exit=0，且 airplane=1、活动网络=none、UID 流量增量为 0。Pad 5 的 airplane / Wi-Fi / mobile_data 均显式为 1/0/0。

## 产品能力门

- 取消：K60 推理中 force-stop，PID `11697 -> null`；目标 WAV / 临时文件 / metrics 均无；`NOT_IMPLEMENTED / FORCED_CANCEL`。
- 后台：Manifest service=0；5 秒关屏短探针在 K60 180 秒、Pad 60 秒内均未完成；`BLOCKED: NO_BACKGROUND_OWNER`，短探针不升级为 PASS。
- 进程重建：K60 中断 / 完成态、Pad 中断 / 完成态均无持久 mode；重启只显示准备态；`BLOCKED: NO_RESUME_STATE`。
- 音频 owner：源码 AudioTrack / MediaSession / AudioFocus 引用均 0，两机运行 dump 无 harness；`BLOCKED: HARNESS_NOT_AUDIO_OWNER`。

## speaker58 / zm_009

静态白名单仅允许 sid `3,59`，并显式拒绝 58 / `zm_009`；启动 mode 仅 `probe-zf, warm-zf, zf, zm`；8 次生成调用仅出现 `zf_001/3` 与 `zm_010/59`；坏调用、坏日志命中均 0。未运行 speaker58 / `zm_009`。

## 清理与设备锁

- K60：harness 卸载 Success，PID/external files 消失；release/debug 生产包逐字段 MATCH；网络与屏幕设置恢复。
- Pad 5：harness 卸载 Success，PID/external files 消失；release 逐字段 MATCH，debug 保持未安装，网络恢复；屏幕因设备原有数字密码锁屏，需用户本人解锁。
- 设备锁：K60 与 Pad 均先 ACTIVE 后 RELEASED；最终 ACTIVE=0（0）。
- Git：初始 186 个证据根未跟踪项之外，本轮新增均在 `qa-v2/`；证据根外 0。当前 `155` 个 qa-v2 状态项。

## 级别

- P0：0
- P1：0（旧 API 33 HexFormat P1 本轮未再出现）
- P2：1（Pad RTF > 1.1，PSS 峰约 0.85–0.93 GB）
- P3：1（两机报告 `ttsLang=en-us`，而配置请求 zh；发音质量未验收）

## 未覆盖

- 生产 Aurorama 集成、voice-pack 导入/删除/回退。
- 主观音色质量、普通话发音、真实扬声器播放。
- 30 分钟后台锁屏、持久 paused sentence、MediaSession 控制、真实 audio focus。
- 其他 API / ABI / 非目标设备。
- 完整许可、notice、model card、声音权属闭包。

## 最多能证明什么

该隔离 debug APK 在指定的 K60 / Pad 5 上能离线完成两个固定 Kokoro 声线的推理并产出合同 WAV；Pad API 33 P1 未复发；零网络与 UID 全分块流量门通过。不能据此证明生产 TTS、后台锁屏、进程恢复、音频 focus / MediaSession、音质或 G1 PASS。

## 下一步

交付负责人先由用户解锁 Pad 5，再只读复核本报告、`ACTIVE=0` 与最终 Git 白名单。若要把后台 / resume / audio owner 或产品集成纳入门禁，须另立实现任务；不得把本隔离结果写成 G1 / Q03 / profile / voice 冻结。
