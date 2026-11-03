# G1-A1 下载状态等式矩阵

状态：`STATIC_CONTRACT_COMPLETE_WITH_CAPABILITY_GAPS / RUNTIME_UNVERIFIED / NO_IMPLEMENTATION`。

本文件冻结 V2 下载控制 seam 的静态合同；它不证明 Room、WorkManager、NotificationManager、文件系统或真实 UI 已经接线，也不关闭 Q03/Q04、S5/S6 或 G1。

## 1. 权威输入

- `V2_PLAN` §5.7.2：D129/D130/D133–D136/D140/D143–D145。
- `V2_PLAN` §5.7.3：Worker `SUCCESS`、通知身份、删除残留与 finalizer 分层现状。
- `V2_PLAN` §5.7.4–§5.7.5：DOWNLOAD-031/032/037/038/061/065 与 `D-D`/`D-N`/`D-R` 证据边界。
- `V2_PLAN` §5.11.3：Download control 必须让页面、角标、通知、sidecar、DB读取同一任务真源。
- `V2_PLAN` §9：L14-04/05/19/27/28。
- `DECISIONS`：D129/D130/D133/D134/D136；Q03/Q04 仍 Open。

## 2. Canonical identity 与尝试因果键

### 2.1 稳定下载身份

```text
downloadKey =
  download:v1
  + server=base64url(serverId)
  + user=base64url(userId)
  + item=base64url(itemId)
  + source=base64url(mediaSourceId)
```

- 四个组件必须全部非空；缺任一段为 `UNKNOWN_NO_MERGE`。
- 只有完整 `downloadKey` 相同才折叠活动任务、完成项、离线项、通知与删除回执。
- 不接受当前账号、标题、路径、文件名或 `sourceId` 单字段回填 provenance。
- 复用同一 UUID 的跨 server/user 条目必须保持不同 key。
- base64url 以 UTF-8 字节为输入，避免分隔符、斜杠与中文编码碰撞。

### 2.2 尝试与投影键

| 键 / 字段 | 等式 | 用途 |
|---|---|---|
| `attemptKey` | `(downloadKey, generation)` | Worker、通知动作、迟到回调与删除 receipt 的归因单元 |
| `sourceEpoch` | 解析时冻结的 source/range-validator 版本 | source 变化后旧 done 证据失效 |
| `acceptedAt` | 持久 claim 受理时刻 | 没有受理锚点不得运行 |
| `doneAt` | 传输完成事实的观测时刻 | Worker `SUCCESS` 不能生成此字段 |
| `deadlineAt` | 当前阶段允许等待的绝对边界 | 超时只能失败关闭或新 generation |
| `leaseRevision` | 运行 claim 的单调租约版本 | UI 重建不自行续租 |
| `notificationTag` | `downloadKey` | Android 通知的稳定业务身份，不依赖 hash 碰撞 |
| `deleteReceiptId` | `(downloadKey, deletingGeneration, requestId)` | 可重放删除事实 |
| `projectionEpoch` | 持久状态提交后的单调版本 | 页面、角标、离线库、通知共同读取 |

当前 `DownloadTask` 已有 `taskGeneration/updatedAt/nextRetryAt`，`DownloadLifecycleClaim` 已用 `(sourceId,generation)` 做迟到回调 fence；但缺通用 `acceptedAt/doneAt/deadlineAt/notificationTag/projectionEpoch`，故这是明确 capability gap，不是已交付能力。

## 3. 状态与用户投影

| 内部状态 | 用户投影 | 活动计数 | 说明 |
|---|---|---:|---|
| `RESOLVING` | `PENDING` | 是 | 持久受理后解析来源 |
| `SOURCE_PENDING` | `PENDING` | 是 | 等待可选/默认 MediaSource 冻结 |
| `PENDING` | `PENDING` | 是 | 等待 Worker |
| `RUNNING` | `RUNNING` | 是 | 真实写入字节 |
| `PAUSED` | `PAUSED` | 是 | 保留残片与偏移 |
| `FINALIZING` | `RUNNING`（正在收尾） | 是 | 尚未发布完成 |
| `COMPLETED` | `COMPLETED` | 否 | 全部完成事实成立 |
| `FAILED` | `FAILED` | 否 | 有分类终态与重试入口 |
| `CANCELLED` | 隐藏 | 否 | 内部幂等 receipt；不是失败 |
| `DELETING` | `DELETING` | 是 | 删除 finalizer 进行中 |
| `DELETING_FAILED` | `DELETING / FAILED` | 是 | 子回执失败，可重试删除 |
| `DELETED` | 隐藏 | 否 | 全部删除子回执确认，保留 tombstone |

`FILE_UNAVAILABLE` 是完成记录的可用性降级，不是新的用户主状态：该记录必须从离线库移除，显示重新下载 / 删除记录，不能继续宣称“已完成可离线”。

## 4. 跨存储等式

| ID | 等式 / 真源 | 失败关闭结果 |
|---|---|---|
| `EQ-IDENTITY` | 四段完整 identity 相同才可合并 | `UNKNOWN_NO_MERGE` |
| `EQ-ACCEPT` | 持久 row ∧ generation 匹配 ∧ source 匹配 ∧ `acceptedAt>0` | 不受理 |
| `EQ-COMPLETE` | 受理 ∧ done 未超 deadline ∧ gen/source 当前 ∧ 媒体提交 ∧ 必需清单提交 ∧ 持久状态提交 ∧ 文件可读 ∧ 字节匹配 | 不得 `COMPLETED` |
| `EQ-FINALIZE` | `FINALIZING` 对外仍为运行；只有全部必需事实提交才可完成 | 保持运行或分类失败 |
| `EQ-TERMINAL-NOTICE` | `(downloadKey,generation,terminalKind)` 至多一次，且仅当前代次 | 不投递 |
| `EQ-ACTIVE-COUNT` | 页面队列数 = 抽屉角标 = 活动通知数 = 同 `projectionEpoch` 的活动 key 数 | 仅显示诊断，不自行补数 |
| `EQ-OFFLINE-COUNT` | 离线数 = `COMPLETED ∧ AVAILABLE ∧ !deleteRequested` | 不进入离线投影 |
| `EQ-DELETE-COMMIT` | `files ∧ sidecar ∧ persistentState ∧ offlineProjection ∧ terminalNotification` 全部确认 | `DELETING_FAILED` |
| `EQ-DELETE-PROJECTION` | 删除 intent 持久化后立即隐藏完成项并 purge 旧完成通知；`DELETING` 仍计入活动数 | 显示删除失败或待重试 |
| `EQ-RESTART` | 中断 `RUNNING` 且租约失效 → `PENDING`；保留 `FINALIZING/DELETING`；不得从路径猜完成 | `RECONCILE_PENDING` |

### 4.1 完成提交顺序

1. 传输写 `.download` 残片，按实际写入字节更新节流进度。
2. 校验可证明长度 / validator / 文件可读性。
3. 写必需 sidecar / 持久媒体清单的临时文件并原子替换。
4. 写 finalization witness；保留 `(downloadKey,generation,sourceEpoch)`。
5. 原子提交主媒体最终文件；文件事实只能证明“已提交”，不能单独证明任务完成。
6. 以同一 generation/source CAS 持久提交 `COMPLETED`。
7. 在同一投影 epoch 发布离线库、本地可用角标与完成 outbox。
8. 必需项任一失败：分类失败并保留可重试入口；可选字幕/歌词/封面/章节/Trickplay 独立归因，不阻塞主媒体 `COMPLETED`。

Worker `SUCCESS`、通知已出现、UI 文案、临时文件已写满、最终文件已 rename、路径存在均不能单独发布完成。

### 4.2 通知与计数

- 通知 tag 必须使用稳定 `downloadKey`；`generation` 必须随动作携带。
- 完成/失败通知在 outbox 中记录 terminal key，重复事件与旧代次事件都不得再投递。
- 删除 intent 持久化时即写入 `terminalNotification=PENDING`，并 purge 旧完成通知；purge 未确认时禁止删除提交。
- 活动通知聚合的 `activeCount` 与页面队列、抽屉角标同源。计数只数叶子 `downloadKey`，父容器数与卡片数不能冒充任务数。
- `DELETING/DELETING_FAILED` 仍占用活动计数，但不可出现“暂停/取消下载”动作；显示清理与重试。
- `COMPLETED/FAILED/CANCELLED/DELETED` 不计活动数。完成/失败终态通知与活动计数是不同集合，不得互相推导。

### 4.3 删除回执

删除父提交只有在同一个 `deleteReceiptId` 下，以下 child 全部为 `CONFIRMED` 时才成立：

| Child | 必须证明 |
|---|---|
| `files` | 主媒体、残片、witness 与可证明归属的附属文件已清除或确认不存在 |
| `sidecar` | 持久清单记录已删除或原子 tombstone |
| `persistentState` | Room / 主库持久任务与离线投影已提交删除 |
| `offlineProjection` | 离线库与本地可用角标不再展示 |
| `terminalNotification` | 旧完成/失败通知已由系统确认 purge |

任一 child `PENDING/FAILED` 时父项保持 `DELETING` 或 `DELETING_FAILED`。批量删除只有所有 child 任务的删除回执均 commit，父容器才能 commit。重放只执行 PENDING child，不重跑已 CONFIRMED child。

## 5. 允许转移

每条允许边除表内 guard 外，始终要求当前 generation 与 source 匹配；每条都有 stale-generation / 缺失 guard 的负例。

| ID | From → To | 额外 guard | 负例 |
|---|---|---|---|
| `TR-001` | `RESOLVING → SOURCE_PENDING` | source 解析继续 | stale-generation |
| `TR-002` | `RESOLVING → PENDING` | source 已冻结 | source-mismatch |
| `TR-003` | `SOURCE_PENDING → PENDING` | source 已选择 | stale-generation |
| `TR-004` | `SOURCE_PENDING → FAILED` | 分类必需失败 | unclassified-failure |
| `TR-005` | `PENDING → RUNNING` | 持久 claim 受理 | missing-acceptance |
| `TR-006` | `PENDING → PAUSED` | 用户暂停 | stale-generation |
| `TR-007` | `PENDING → CANCELLED` | 用户取消 | missing-cancel-intent |
| `TR-008` | `RUNNING → PAUSED` | 停止读取并保残片 | stale-generation |
| `TR-009` | `RUNNING → FINALIZING` | `doneAt` 证据存在 | worker-success-without-done |
| `TR-010` | `RUNNING → FAILED` | 分类必需失败 | unclassified-failure |
| `TR-011` | `RUNNING → CANCELLED` | 用户取消 | missing-cancel-intent |
| `TR-012` | `PAUSED → PENDING` | 新 generation | stale-generation |
| `TR-013` | `FAILED → PENDING` | 用户重试 | missing-retry-intent |
| `TR-014` | `FINALIZING → COMPLETED` | 全部完成事实 | missing-manifest |
| `TR-015` | `FINALIZING → FAILED` | 仅必需项失败 | optional-only-failure |
| `TR-016` | `COMPLETED → DELETING` | 用户确认删除 | missing-delete-intent |
| `TR-017` | `FAILED → DELETING` | 用户确认删除 | missing-delete-intent |
| `TR-018` | `PENDING → DELETING` | 用户确认删除 | missing-delete-intent |
| `TR-019` | `RUNNING → DELETING` | 用户确认删除 | missing-delete-intent |
| `TR-020` | `PAUSED → DELETING` | 用户确认删除 | missing-delete-intent |
| `TR-021` | `DELETING → DELETED` | 全部 child receipt 确认 | one-child-pending |
| `TR-022` | `DELETING → DELETING_FAILED` | 任一 child 失败 | all-children-confirmed |
| `TR-023` | `DELETING_FAILED → DELETING` | 用户重试删除 | automatic-retry-without-intent |
| `TR-024` | `DELETED → PENDING` | 用户重新下载且新 generation | late-old-generation-event |

## 6. 禁止转移

下表边即使 generation/source 看似当前也必须拒绝。完整属性测试另扫描全部 `12 × 12 = 144` 个 from/to 对，未声明的 120 对均默认 forbid。

| ID | From → To | 负例理由 |
|---|---|---|
| `TR-N01` | `RUNNING → COMPLETED` | 直接成功，跳过 finalizer |
| `TR-N02` | `PENDING → FINALIZING` | 没有传输 done |
| `TR-N03` | `FINALIZING → PENDING` | finalizer 倒退 |
| `TR-N04` | `COMPLETED → RUNNING` | 终态复活 |
| `TR-N05` | `CANCELLED → RUNNING` | 取消后旧任务复活 |
| `TR-N06` | `DELETING → COMPLETED` | 删除中的迟到完成 |
| `TR-N07` | `DELETING → RUNNING` | 删除回到传输 |
| `TR-N08` | `DELETING → PENDING` | 删除回到队列 |
| `TR-N09` | `FAILED → COMPLETED` | 失败无新 attempt 直接成功 |
| `TR-N10` | `PAUSED → RUNNING` | 跳过新 PENDING generation |
| `TR-N11` | `SOURCE_PENDING → RUNNING` | source 未 ready |
| `TR-N12` | `DELETED → COMPLETED` | tombstone 复活 |
| `TR-N13` | `RESOLVING → COMPLETED` | 解析直接完成 |
| `TR-N14` | `FINALIZING → CANCELLED` | transfer 已完成；应删除或完成 |
| `TR-N15` | `COMPLETED → PENDING` | 无显式重新下载 |
| `TR-N16` | `DELETED → RUNNING` | 旧 Worker 在删除后运行 |

## 7. 重启恢复

| 持久事实 | 恢复结果 | 禁止 |
|---|---|---|
| `RUNNING` 且租约失效 | `PENDING` | 直接 `COMPLETED` / 直接 `ERROR` |
| `PENDING` | 保持 `PENDING` | 因 UI 不在而取消 |
| `FINALIZING` | 保持 `FINALIZING` 并重放 finalizer | 回退 `PENDING` 后重下主媒体 |
| `DELETING` | 保持 `DELETING` 并重放 PENDING child | 因进程死亡直接删除父记录 |
| `DELETING_FAILED` | 保持可见失败与重试入口 | 自动无限重试 |
| `COMPLETED` 且完成事实可重读 | 保持 `COMPLETED` | 仅凭路径存在补成功 |
| `COMPLETED` 但文件/清单不可验证 | `FAILED`（文件不可用） | 继续出现在离线库 |
| `CANCELLED` | 保持隐藏 receipt | 进入 FAILED 或发失败通知 |

设备重启只承诺 Android 允许的下一次 App/Worker 扫描；系统未调度时必须显示真实等待，不伪造恢复或完成。

## 8. 当前实现 capability gaps

| ID | 当前事实 | 合同要求 | 影响 |
|---|---|---|---|
| `GAP-IDENTITY` | 当前任务仅部分持久 `serverId/userId`，书籍 source 可缺失 | 四段完整 identity，缺段不合并 | P2 |
| `GAP-CAUSAL-FIELDS` | 有 `taskGeneration/updatedAt/nextRetryAt`，无通用 accepted/done/finalize deadline | 持久因果字段并参与 CAS | P2 |
| `GAP-CANCEL` | `beginCancellation()` 写 `FAILED` | 内部 `CANCELLED`，不发失败通知 | P2 |
| `GAP-NOTIFICATION` | sourceId hash、固定前台 ID、完成点击 HOME、无 purge API | tag=downloadKey、generation 动作、精确一次与可证明 purge | P2 |
| `GAP-FINALIZER-SEVERITY` | 后置 effects 失败统一成 incomplete | 必需/可选分级，Trickplay 失败不阻塞主媒体 | P2 |
| `GAP-PROJECTION` | 页面、角标、通知和离线项由不同调用点组合 | 同一 `projectionEpoch` 的活动/离线键集合 | P2 |

这些问题只冻结为后续实现合同，不改变 Q03/Q04 Open、G1 Not Passed 或生产代码。

## 9. Fake 证据边界

- 静态 transition fixture 不替真实 Room + sidecar + WAL 事务映射作证。
- 模拟通知 ledger 不替 NotificationManager 投递、权限、PendingIntent 路由或 purge 作证。
- Worker `SUCCESS` 不替实际字节、doneAt、文件可读与持久终态作证。
- 路径扫描不替媒体播放 / 阅读能力作证。
- 静态计数不替 Compose 页面、抽屉角标和系统通知同刻可见值作证。
- 必须由候选包真实创建任务，注入暂停、取消、删除、迟到回调、进程死亡、force-stop / reboot 与通知清理后，才可提升 runtime 证据。
