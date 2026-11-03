# G1-A1 用户承诺矩阵

状态：`STATIC_FIXTURE_PASS / CANDIDATE_PACKAGE_NOT_EXECUTED / RUNTIME_UNVERIFIED`。矩阵只证明合同与 fixture 能拒绝假完成和分叉，不证明真实下载链路。

| 用户承诺 | 入口 | 操作 | 可观察结果 | 自动化 / fixture 证据 | Runtime 未覆盖 |
|---|---|---|---|---|---|
| 确认后可创建持久叶子任务，进程重建仍保留来源与断点（DOWNLOAD-012/013/021） | 详情 / 剧集 / 专辑 / 书籍下载入口 | 前置检查后确认下载 | 重启后看到同一 `downloadKey`、generation、来源和可继续状态 | `ID-IDENTICAL`、`ID-OTHER-USER`、`ID-MISSING-SOURCE`、`TR-002`、`TR-005`、`RST-PENDING` | 真实 Room + Worker 事务、进程被系统杀死后的扫描 |
| 运行进度只来自真实写入字节，页面、角标和通知读取同一快照（DOWNLOAD-003/021/034/037） | 下载页 / 侧栏 / 活动通知 | 运行一个或多个任务 | 页面、角标、通知的 `activeCount` 相等，进度不随通知文本或 Worker 状态伪造 | `CMP-STALE-GENERATION`、`CMP-SOURCE-MISMATCH`、`CNT-MIXED-PROJECTION`、`EQ-ACTIVE-COUNT` | Compose 500ms UI 刷新、系统通知渲染、多个真实消费者同刻采样 |
| 暂停保留残片；恢复、重试和新 claim 使用新 generation（DOWNLOAD-024/025/027/043） | 条目 / 下载中心 / 通知动作 | 暂停后恢复，或在运行中重试 | 旧代次 callback / progress / done 不覆盖新状态；残片和偏移保留 | `TR-006`、`TR-008`、`TR-012`、`TR-N10`、`CMP-STALE-GENERATION` | 真实 Range、存储卷脱挂、WorkManager 重调度 |
| Worker `SUCCESS` 不发布假 `COMPLETED`（DOWNLOAD-030/031/065） | Worker 调度与传输 finalizer | Worker 返回 success，但无 done/DB/文件提交 | 仍显示 `RUNNING`（收尾中），离线库和完成通知不出现 | `CMP-WORKER-ONLY`、`CMP-MISSING-DB-COMMIT`、`TR-N01`、`EQ-FINALIZE` | 真实 `Result.success()`、rename 与 DB commit 之间强杀 |
| Trickplay 等可选增强失败不阻塞主媒体完成（DOWNLOAD-011/032） | finalizer / 附属内容状态 | 主媒体必需项成功，Trickplay 失败 | 主媒体 `COMPLETED`，同时显示“附属内容不完整”与单项重试 | `CMP-OPTIONAL-GAP`、`TR-015` | 真实 Trickplay / 字幕 / 封面端点与单项补偿 |
| 文件、必需清单、原子提交和持久状态全部成立后才进入离线库（DOWNLOAD-031/033/040/058） | 下载中心 / 离线库 / 在线媒体库角标 | 完成任务并离线打开 | 完成状态、离线库和本地可用角标同步出现；截断或不可读不能完成 | `CMP-VALID`、`CMP-MISSING-MANIFEST`、`CMP-LENGTH-MISMATCH`、`CMP-UNREADABLE-FILE` | 真实 sidecar、Room+WAL、文件可读性和播放 / 阅读入口 |
| 失败有分类终态，重试推进 generation，旧回调不能复活（DOWNLOAD-026/027/041） | 条目 / 下载中心 | 错误耗尽后重试 | 先 `FAILED`，再新 generation 进入 `PENDING`；旧代次拒绝 | `TR-010`、`TR-013`、`TR-N09`、`CMP-STALE-GENERATION` | 真实网络错误、退避、`Retry-After`、WorkInfo |
| 取消不归入失败，并清理残片与迟到回调（DOWNLOAD-023/060） | 条目 / 下载中心 / 通知动作 | 取消下载 | 任务离开活动列表；内部保留 `CANCELLED` receipt；不出现失败通知 | `TR-007`、`TR-011`、`TR-N14`、`NTF-CANCEL-NO-FAILURE` | 停止真实 I/O、残片删除、系统通知取消 |
| 删除过程可见，任一清理子项失败都能解释并重试（DOWNLOAD-061/065） | 完成项 / 下载中心 | 确认删除，或注入文件/通知失败 | 删除意图立即隐藏完成投影；失败显示 `DELETING / FAILED` 与重试 | `DEL-FILES-FAILED`、`DEL-NOTIFICATION-FAILED`、`DEL-MISSING-CHILD`、`TR-022`、`TR-023` | 真实文件删除失败、sidecar/Room 故障、NotificationManager purge 失败 |
| 删除提交必须证明文件、清单、持久状态、离线投影、通知全清理（DOWNLOAD-061/065） | 删除 finalizer / 重启恢复 | 删除中途强杀后恢复 | 仅全部 child receipt `CONFIRMED` 才 `DELETED`；否则保持可重试删除态 | `DEL-ALL-CONFIRMED`、`DEL-BATCH-PARTIAL`、`TR-021`、`RST-DELETING` | 主库+WAL、sidecar、文件系统与通知跨存储重放 |
| 完成/失败通知按稳定任务身份精确一次，点击回正确目标（DOWNLOAD-037/038） | 系统通知 | 同一终态重复回调 / 旧代次迟到 | 仅当前代次首次投递；重复与旧代次不新增通知 | `NTF-COMPLETE-FIRST`、`NTF-COMPLETE-DUPLICATE`、`NTF-COMPLETE-STALE`、`NTF-FAILED-FIRST` | 真实 NotificationManager、PendingIntent 路由、通知权限 |
| 页面、角标、活动通知共享同一计数（DOWNLOAD-037/058） | 下载页 / 侧栏 / 通知 | 混合等待、运行、暂停、收尾、删除 | 三处 `activeCount` 相等；父容器和卡片数不能冒充叶子数 | `CNT-MIXED-PROJECTION`、`EQ-ACTIVE-COUNT` | 真实多消费者同时订阅与系统通知聚合 |
| 删除后完成通知、离线库与完成角标同步清除（DOWNLOAD-038/058/061/065） | 完成项删除 / 系统通知 | 删除完成项后回下载页、离线库和通知栏 | 旧完成通知立刻 purge；完成投影移出；最终无幽灵状态 | `NTF-DELETE-PURGES`、`DEL-ALL-CONFIRMED`、`DEL-NOTIFICATION-FAILED`、`TR-016`、`TR-N06` | 系统通知真实残留、force-stop、主库+WAL 与离线角标 |

## 证据升级边界

本矩阵中 fixture PASS 只能标记为 `STATIC_CONTRACT_TEST_PASS`。真实候选包必须至少执行：

1. 真实创建任务并验证 `acceptedAt`、`doneAt`、deadline 和同代 source。
2. Worker 返回与 finalizer、DB、sidecar、最终文件分离取证。
3. 暂停 / 恢复 / 重试 / 取消及旧代次迟到注入。
4. 删除子项故障注入，强杀后只重放 PENDING child。
5. 下载页、抽屉角标、活动通知的真实 active key 对账。
6. 删除完成项后检查通知栏、离线库、本地角标、主库+WAL 与文件。

任一项未执行时保持 `RUNTIME_UNVERIFIED`，不得用本矩阵替代 D-T 真实候选包旅程。
