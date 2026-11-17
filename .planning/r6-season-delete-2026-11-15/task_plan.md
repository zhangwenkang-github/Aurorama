# 任务计划：Aurorama 1.4.1 A3 根因、修复、QA 与发布闭环

## 目标

由负责人自行判断后续授权并直接推进：先取得 A3 同 PID foreground miss 的脱敏失败栈，
定位并最小修复主线程网络异常，再以 exact signed candidate 完成真实用户链路、独立 QA、
Player Core release matrix、Release engineering 与 1.4.1 发布收口，不逐项等待用户确认。

## 下一步

更新 ACTIVE/PLAYER_PLAN 记录“所有后续授权由负责人判断”，创建 A3 单次脱敏失败栈 owner
的精确 prompt，运行 BugLoop lint，PASS 后在隔离 worktree 派发。

## 当前阶段

阶段 1：失败栈取证。

## 各阶段

### 阶段 1：A3 失败栈取证
- [x] R10 已证明业务层 foreground fetch 无 IO 切换
- [x] R10 已确认 SDK/OkHttp async enqueue 与运行态异常存在冲突
- [ ] 更新负责人自行授权策略与 ACTIVE
- [ ] 派 fresh debug-only owner 取得一次同 PID 脱敏失败栈
- [ ] 审查线程名、state、stack frame 与脱敏
- **状态：** in_progress

### 阶段 2：根因与合同
- [ ] 依据失败栈单选 pre-enqueue、worker、interceptor/transport 或其他边界
- [ ] 冻结最小修复合同与 state-effect matrix
- [ ] 明确 TDD RED / GREEN、允许路径和停止条件
- **状态：** pending

### 阶段 3：最小实现与真机复测
- [ ] fresh 实现 owner 在隔离 worktree 做 TDD
- [ ] 定向单测、格式、debug/release 构建
- [ ] exact debug APK 在同一 Show 下载对话框复测 A3
- [ ] 不创建下载任务、不播放、不删除用户数据
- **状态：** pending

### 阶段 4：独立 QA 与并行修复
- [ ] 与实现不同的 fresh 独立 QA 执行 A3 真实链路
- [ ] 独立 QA 执行 A3 删除首帧、Series same-state、Player Core PC-01–PC-12
- [ ] 每个开放 P1/P2 闭合、等效或由负责人形成明确发布决策
- [ ] 并行支线输出 MERGED / EQUIVALENT / DEFERRED / RETIRED 矩阵
- **状态：** pending

### 阶段 5：Signed Candidate 与 Release
- [ ] fresh Release owner 生成 exact signed 单 signer 候选
- [ ] 在候选包上真实执行核心用户旅程
- [ ] 生成截图 / Release notes / 资产并绑定候选
- [ ] 由负责人自行通过发布门后 push / tag / GitHub Release
- [ ] 发布后回读 tag、Release、APK、signer、version 与下载资产
- **状态：** pending

### 阶段 6：最终收口
- [ ] docs / ACTIVE / evidence / manifest clean
- [ ] Engramory sync + checker / doctor
- [ ] fresh pre-handoff / post-release audit PASS
- [ ] 输出最终 commit、门禁、P0–P3、证据与下一状态
- **状态：** pending

## 关键问题

1. `NetworkOnMainThreadException` 的失败线程与首帧栈落在哪个边界？
2. 修复是否必须改变线程/网络行为，还是只修正错误的 foreground/background 执行路径？
3. PC-10 与 Series/delete 证据能否用现有设备完成？
4. 最终 signed 候选的真实用户路径与 Player Core matrix 是否全部可执行并 PASS？

## 已做决策

| 决策 | 理由 |
|------|------|
| 用户 2026-11-15 将全部后续授权委托给负责人 | 直接推进修复与发布，不再逐阶段等待用户确认 |
| 根因仍以一次脱敏失败栈为最小输入 | R10 的静态证据存在矛盾，猜修会扩大风险 |
| 实现、独立 QA、Release engineering 由不同 fresh 子代理承担 | 保持项目强制的职责分权 |
| 最终发布以 exact signed 候选真实用户旅程为门 | 单测、debug trace 或 Worker SUCCESS 不能替代用户可见结果 |

## 遇到的错误

| 错误 | 尝试次数 | 解决方案 |
|------|---------|---------|
| planning 初始化误传 `-Help`，在仓库根生成模板 | 1 | 已删除误生成文件，改用 `.planning` 命名计划并设置 active plan |

## 备注

- 所有终端使用 pwsh；截图 0；服务器只读；不删除用户已有下载。
- 所有设备命令都必须持唯一锁并显式 serial；PC-10 所需设备由负责人按合同判断。
- 本计划只协调，不替代 `docs/TASKS.md` 唯一 ACTIVE 与 `docs/PLAYER_PLAN.md`。
