# 发现与决策

## 需求
- 用户 2026-11-15 补充：要复现慢删除，应一次性下载约 12 集的一整季，并在下载进行中删除。
- 证据合同由 R6 负责人定义；并行推进，目标是加快 1.4.1 发布。

## 研究发现
- 2026-11-15 已有 R6 快路径单叶删除未复现 `>10 s`：canonical `≤2.829 s`、文件 `≤729 ms`。
- 既有 signed QA R4 在双叶活动时观察到 `删除中` 在 +2/+5/+10 秒仍存在。
- 源码顺序已明确，但 job cancel/join、文件递归清理、post-delete refresh 的耗时仍未分开。

## 技术决策
| 决策 | 理由 |
|------|------|
| 整季约 12 叶作为主复现，最多两次删叶尝试 | 提高并发、活动 job 与递归清理重叠概率 |
| 设备证据与零设备 trace 设计并行 | 互斥且能缩短根因裁决时间 |
| release audit 单独 fresh owner | 回答剩余工作量，不占设备或产品写域 |

## 遇到的问题
| 问题 | 解决方案 |
|------|---------|
| 上一次快路径证据不足 | 改用“整季下载中删叶”，并增加阶段观测 |
| release DB 不可读 | 设计行为不变的 debug-only trace，而不是绕过 release 沙箱 |

## 资源
- `docs/TASKS.md` 唯一 ACTIVE
- `docs/PLAYER_PLAN.md` §52.70.27–§52.70.29
- R6 source diagnosis / signed observer / pre-handoff audit
- exact signed R4 arm64 与共享设备锁
