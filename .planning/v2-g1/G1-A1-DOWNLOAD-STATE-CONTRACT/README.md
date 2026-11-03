# G1-A1 下载状态合同

状态：`STATIC_CONTRACT_COMPLETE_WITH_CAPABILITY_GAPS / RUNTIME_UNVERIFIED / NO_IMPLEMENTATION`。

本包只冻结静态合同，不修改生产代码、schema、资源或 Gradle，不接设备 / 服务器 / 播放，不 commit / push。

## 入口

- [state-equality-matrix.md](state-equality-matrix.md)：identity、因果字段、状态、跨存储等式、允许 / 禁止转移、capability gaps。
- [user-commitment-matrix.md](user-commitment-matrix.md)：用户承诺 → 入口 → 操作 → 可观察结果 → fixture 证据 → runtime 未覆盖。
- [fixture/contract-fixture.json](fixture/contract-fixture.json)：machine-checkable 合同与负例。
- [report.json](report.json)：计数、来源 hash、关键拒绝等式、severity 与边界。
- [manifest.json](manifest.json)：除自身外的证据 hash / bytes。

当前机械合同为 12 状态、24 允许转移、16 显式禁止转移、50→55 个 fixture case；其中五类删除 child 各有一条 `PENDING` 负例。

## 验证

```powershell
& '<contract-dir>\test_contract.ps1'
& '<contract-dir>\test_contract.ps1' -Mutation broken-invariant
& '<contract-dir>\test_contract.ps1' -Mutation broken-delete-predicate
```

第一条应为 exit 0；后两条分别破坏 Worker-only 完成与删除 CONFIRMED predicate，必须 exit 1。

## 核心结论

- 完整 `serverId+userId+itemId+mediaSourceId` 才可合并；缺段 `UNKNOWN_NO_MERGE`。
- `COMPLETED` 需要同代、同源、accepted/done/deadline、媒体、必需清单、持久状态、文件可读和字节一致。
- Worker `SUCCESS`、通知出现、路径存在或 rename 均不是完成证据。
- 页面、角标、活动通知共享同一活动 key 集合；完成通知是另一集合。
- 删除必须等文件、sidecar、持久状态、离线投影和终态通知 child receipt 全部为字面 `CONFIRMED`；`PENDING`/未知/FAILED/缺失都不能进入 `DELETED`。
- 当前实现有 6 个 P2 capability gaps；本静态合同不等于真实链路覆盖。
