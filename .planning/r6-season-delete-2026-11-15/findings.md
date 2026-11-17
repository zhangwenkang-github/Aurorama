# 发现与决策

## 需求
- 用户 2026-11-15 新增：以后所有授权都由负责人判断，不需要用户逐项授权，直接推进下一步；
  目标是尽快解决 A3 并正式发布 1.4.1。
- 该授权覆盖常规诊断、debug-only 补证、实现、独立 QA、release engineering、push、tag 与 Release；
  负责人仍需执行真实用户链路、候选包和证据门。

## 研究发现
- 2026-11-15 已有 R6 快路径单叶删除未复现 `>10 s`：canonical `≤2.829 s`、文件 `≤729 ms`。
- 既有 signed QA R4 在双叶活动时观察到 `删除中` 在 +2/+5/+10 秒仍存在。
- 源码顺序已明确，但 job cancel/join、文件递归清理、post-delete refresh 的耗时仍未分开。
- R10 已证明 `ShowViewModel`、foreground SWR fetch 与 repository 在线 fetch 之前没有 IO dispatcher；
- SDK/OkHttp transport 使用 `Call.enqueue`，与运行态响应前
  `NetworkOnMainThreadException` 冲突；
- 唯一最小缺失证据是同一 App PID、同一 foreground miss 的失败线程名/state 与脱敏栈帧。

## 技术决策
| 决策 | 理由 |
|------|------|
| 整季约 12 叶作为主复现，最多两次删叶尝试 | 提高并发、活动 job 与递归清理重叠概率 |
| 设备证据与零设备 trace 设计并行 | 互斥且能缩短根因裁决时间 |
| release audit 单独 fresh owner | 回答剩余工作量，不占设备或产品写域 |
| 用户把后续授权交给负责人 | 减少往返等待，但仍保留产品、QA、Release 分权与真实证据 |
| 栈采集前禁止修复 | SDK 异步 transport 与运行态异常冲突，必须先定位实际 socket 边界 |

## 遇到的问题
| 问题 | 解决方案 |
|------|---------|
| 上一次快路径证据不足 | 改用“整季下载中删叶”，并增加阶段观测 |
| release DB 不可读 | 设计行为不变的 debug-only trace，而不是绕过 release 沙箱 |
| R10 dispatcher 判别 INCONCLUSIVE | 授权同 PID 脱敏失败栈，不直接猜改 `Dispatchers.IO` |

## 资源
- `docs/TASKS.md` 唯一 ACTIVE
- `docs/PLAYER_PLAN.md` §52.70.27–§52.70.29
- R10 discriminator report / audit / signed observer
- A3 network failure stack evidence（待生成）
- exact signed R4 arm64 与共享设备锁
