# W90-R2 P2 sidecar/Room 跨存储恢复窗口修复

状态：`IMPLEMENTATION_DONE / SCOPED_REVIEW_PENDING`

身份：全新资深 Android 下载生命周期 / provenance 实现代理。未执行独立 QA、scoped review、merge-prep、merge、push、tag、Release、workflow、设备、adb、instrumentation、截图或生产 Jellyfin 访问。

## 1. 计划与边界

- 目标：只关闭首轮 review P2 的跨存储失败窗口：sidecar 已成功写入完整 pending，随后 Room `saveDownloadIntents` 失败时，同 batch 重试不得被 sidecar 查重永久短路；cold recovery 必须先完成 legacy generation takeover 才能创建 source；删除新 source 后旧失败不得复活。
- 非目标：不改 schema / v16、`Versions.kt`、workflow、签名、release；不扩展 partial identity 大矩阵；不重做已通过的 partial 替换、普通删除 lifecycle、source tombstone / active-job fence 或 UI。
- 预计上下文 80k–140k；实际进入实现时已超过 25 万收缩线。开工时项目工具未暴露精确 meter；首次实查为 `261,158 / 1,048,576（24.9%）`。报告落盘前实查 `300,128 / 1,048,576（28.6%）`，已按 30 万门禁停止新增事项，只完成当前实现单元收口。
- 白名单：`DownloaderImpl.kt`、`DownloaderImplRecoveryTest.kt`、`ServerDatabaseIntentDeletionSqlTest.kt`、本报告。实际修改严格为前三者。

## 2. 固定现场与候选

- Worktree：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\Aurorama`
- Branch：`codex/w90-r2-download-control`
- 固定起点 HEAD：`241e7ac14ddbd4c6b13cfa8a3e0c32691d4dcc54`
- 起点 parent：`ff686359c99684a6a8465b3bf797728e91f2c4c0`
- 代码候选：`bd15dfbf27edb575a72b7203d3e1b44db3462067`
- 代码 parent：`241e7ac14ddbd4c6b13cfa8a3e0c32691d4dcc54`
- 代码变更：3 files，`+349/-23`
- 开工与代码提交后均为 tracked / staged / untracked clean。

## 3. 根因

1. `existingCanonicalKeys()` 把完整 sidecar record 的存在直接视为 batch 已受理。Room 事务失败时，sidecar pending 已存在但 Room 仍为旧 legacy `FAILED`，同 batch 重试得到 `fresh=[]`，不会再次执行 takeover。
2. `processReceivedIntents()` 对完整 pending sidecar 直接在 `resolveSidecarIntent()` 中准备 origin / source，只对普通 `FAILED/CANCELLED/failureDetail=null` 做了狭窄拦截；带 legacy marker 的旧 `FAILED` 可绕过尚未完成的 generation takeover。
3. Room 失败后，新 source 可暂时遮住旧失败；最终清理只处理 `RECEIVED` intent，旧 legacy marker 不删除。删除新 source 后旧失败重新显形。

这不是 sidecar 与 Room 的假原子问题，而是“持久化完成”与“legacy 接管完成”被错误合并成一个状态。

## 4. RED

从固定起点 `241e7ac1` 执行 `git archive` 建立独立快照，只覆盖测试先行版本；生产代码保持 `241e7ac1`：

- 快照根：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\red-audit-241e7ac1-p2tx-20261026\workspace`
- Archive：同根 `baseline.zip`
- Recovery XML：`...\workspace\core\build\test-results\testLibreDebugUnitTest\TEST-com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest.xml`
- 结果：`70 tests / 2 failures / 0 errors / 0 skipped`；Gradle `60/60 executed`

失败 1：`Room 保存失败后同一完整 batch 重试先接管旧代且删除新源不复活`

- expected：Room 失败后同 batch 重试 `acceptedRequests=1`，重新执行 takeover。
- actual：`acceptedRequests=0`，完整 pending sidecar 被直接判为已受理。

失败 2：`Room 持续失败时冷启动不得绕过接管生成 source 且修复后可恢复`

- expected：Room 持续失败时显式中止处理且不生成 source。
- actual：`sources=[source-recovery]`、无“处理已受理下载意图失败”日志，旧 legacy `FAILED` 被完整 sidecar 绕过。

两处均为 JUnit `AssertionError` 行为失败，不是编译失败。

## 5. GREEN 与实现 seam

- `existingCanonicalKeys()` 保留完整 namespace 匹配，但完整 pending sidecar 只有在其 Room intent 当前为 `RECEIVED` 时才算已受理；已解析 sidecar source 仍按原语义作为 existing。
- `saveAll()` 保持 sidecar 先写、Room 后写，不宣称跨存储原子。Room 调用后重新读取 intent；任一请求未得到当前 `RECEIVED` 就显式失败，避免“sidecar 成功、Room 静默 no-op”被报告为 accepted。
- `resolveSidecarIntent()` 在读取 origin / 创建 source 前执行 Room-only takeover：
  - Room 已 `RECEIVED`：允许继续；
  - 无 intent 或精确 legacy marker `FAILED`：复用 `saveDownloadIntents` 条件 UPDATE / INSERT；
  - 普通 `FAILED`、`CANCELLED`、其他 failure detail：拒绝生成 source；
  - takeover 后必须回读为 `RECEIVED`。
- cold preflight 不向 sidecar 写回记录，避免把已进入删除流程的 pending 重新复活。
- legacy takeover 推进 `taskGeneration + 1`，同时清空 `failureReason` / `failureDetail` 并更新名称、专辑、歌手、曲序；由 DAO 原 SQL 保证，不在本修复另造状态源。
- 删除新 source 继续复用既有 `deleteTask` / tombstone / lifecycle / active-job fence / sidecar remove 补偿，没有新增 bypass。

## 6. 部分失败点与可恢复性

| 失败点 | 行为 |
|---|---|
| sidecar 写失败 | `putAll` 返回 false 后显式抛错；没有 Room accepted，也没有 source。 |
| sidecar 成功、Room 事务抛错 | 异常向上返回；sidecar pending 保留；同 batch 重试会再次进入 `saveAll`。 |
| Room 事务成功但回读非 `RECEIVED` 或回读失败 | 显式失败；不会被 sidecar 误报为 accepted。 |
| cold preflight Room 持续失败 | 异常离开 `processReceivedIntents`，后台记录处理失败；不读取 origin、不建 source，pending 保留。 |
| takeover 成功、后续 origin / source 失败 | 仍由既有 resolver 日志与 pending sidecar 保留处理，重试不会回到旧 legacy `FAILED` 代。 |
| 删除 pending / source 失败 | 继续保留既有 `DELETING` tombstone 或 FAILED tombstone，供冷启动重试；本修复未改删除顺序。 |

因此不会因为一次 cross-storage 失败永久卡死，也不会用“已经写 sidecar”伪装 takeover 成功。

## 7. 并发与 fail-closed

- `resolveSidecarIntent()` 在 source 前至少两次检查目标 sidecar identity；takeover 本身不写 sidecar，不会把并发删除后消失的 pending 重新加回。
- pending 删除先经 DAO tombstone / generation CAS，再在 `receivedIntentLock` 内移除 sidecar 并删除当前 generation；resolver 也在同锁内串行处理，迟到代次不能回写旧 intent。
- 普通 `FAILED`、`CANCELLED`、无 legacy marker 行保持 fail-closed。
- sidecar `Unreadable` / `Corrupt` 与无 provenance legacy 路径保持不变。
- 双完整 origin 的媒体源歧义仍由 `singleOrNull()` fail-closed；本修复只补 Room 受理前置条件，不按裸 itemId 折叠或泛删。
- 原 `同 UUID 的 sidecar key 失败不得阻断另一 key` fixture 已把“普通 FAILED”改为精确 legacy marker，以符合现行裁决；其 origin A 失败、origin B 成功及 A 重试目标未改变。

## 8. P3 SQL 字段断言

`ServerDatabaseIntentDeletionSqlTest` 新增直接字段读取并断言：

- 成功 takeover 后 `name/albumName/artist/trackIndex` 更新；
- `status=RECEIVED`，`failureReason=failureDetail=null`；
- `taskGeneration` 推进；
- 普通 FAILED 的 failure detail、generation 保持不变；
- CANCELLED 行不被 takeover 覆盖，失败原因保留。

partial identity 的 user-only / 无 origin / item-kind / 双冲突矩阵未扩项：它不是本 P2 的直接失败路径，且已过 25 万收缩线；既有 P3 不声明关闭。

## 9. 实际执行

GREEN focused：

```powershell
.\gradlew.bat :core:testLibreDebugUnitTest --tests 'com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest' :data:testDebugUnitTest --tests 'com.zhangwenkang.cinefin.database.ServerDatabaseIntentDeletionSqlTest' --rerun-tasks
```

- `68 actionable tasks / 68 executed`，BUILD SUCCESSFUL。
- Recovery：`70/0/0/0`。
- SQL：`2/0/0/0`。

Recovery 连续独立重跑两次：

- 第 1 次：`60/60 executed`，`70/0/0/0`，timestamp `2026-10-26T14:19:56.759Z`，SHA-256 `D506700A4820A2826E380115EF18432BCBCC6D67F1608F69984FBA4E7A4A9F93`。
- 第 2 次：`60/60 executed`，`70/0/0/0`，timestamp `2026-10-26T14:21:40.296Z`，SHA-256 `B40211B18E412B85F61DEE6BDA3EB4E6C2ADA246521048F40331D686F1B54DAB`。

全量 impacted JVM：

```powershell
.\gradlew.bat :core:testLibreDebugUnitTest :data:testDebugUnitTest --rerun-tasks
```

- `68/68` tasks executed，BUILD SUCCESSFUL。
- core：35 XML / `276/0/0/0`。
- data：35 XML / `229/0/0/0`。
- 合计：70 XML / `505/0/0/0`。

格式与编译：

```powershell
.\gradlew.bat :core:ktfmtCheck :data:ktfmtCheck --rerun-tasks
.\gradlew.bat :app:phone:compileLibreDebugKotlin --rerun-tasks
```

- ktfmt：`10/10 executed`，BUILD SUCCESSFUL。
- phone Kotlin：`114/114 executed`，BUILD SUCCESSFUL；只有仓库既有 warning。
- `git diff --check`：exit 0，仅有仓库既有 CRLF 提示。
- `graphify update .`：exit 0，14,154 nodes / 44,943 edges / 484 communities；保留既有 `ItemHeader.kt` / `ItemTopBar.kt` AST warning。
- v16 不存在；代码变更 3 files，schema / `Versions.kt` / workflow / signature / keystore / release 路径命中 0。

## 10. 证据路径

- RED XML：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\red-audit-241e7ac1-p2tx-20261026\workspace\core\build\test-results\testLibreDebugUnitTest\TEST-com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest.xml`
- GREEN Recovery XML：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\Aurorama\core\build\test-results\testLibreDebugUnitTest\TEST-com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest.xml`
- SQL XML：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\Aurorama\data\build\test-results\testDebugUnitTest\TEST-com.zhangwenkang.cinefin.database.ServerDatabaseIntentDeletionSqlTest.xml`
- HTML 报告：各模块 `build/reports/tests/.../index.html`。

## 11. P0–P3（仅实现候选）

- P0：0
- P1：0
- P2：0（候选修复，不构成 review / QA 结论）
- P3：0（本候选新增；既有 P3 不关闭）

## 12. 未覆盖 / BLOCKED

- 独立 scoped review、独立 QA、真实设备、adb、Android Room instrumentation、进程 kill / 掉电、真实磁盘故障、真实跨服务器网络、WorkManager 真抢占：未执行 / 待后续角色。
- SQLite fake proxy 注入只能证明生产调用序列与状态机；真实 Room 事务故障仍属 instrumentation 未覆盖。
- 并发证据为锁、条件 UPDATE、generation fence 与生产 seam 测试组合，不等于真机级并发验收。
- R2 / W90 / 发布均未通过；本候选停在实现单元。

## 13. 下一步

角色：与本次实现者不同的全新 scoped reviewer。

第一条只读动作：核对 `241e7ac1..bd15dfbf` 的 3 文件 diff，先读本报告与 RED/GREEN XML，再审查 pending sidecar 的 Room accepted 门、cold-start takeover 顺序、删除并发和 SQL 字段断言。

停止条件：需改 schema / v16 / `Versions.kt` / workflow / signature / release，或要求实现代理自开 QA / merge / push / release 时停止。

本实现未执行 QA、review、merge-prep、merge、push、tag、Release、设备或生产写。
