# W90-R2 sidecar / Room 失败窗口修复 Scoped Review

结论：`SCOPED_REVIEW_PASS`

状态：`PASS / NO_QA / NO_MERGE / NO_PUSH / NO_RELEASE`

身份：与两轮实现代理、首轮 reviewer、独立 QA 均不同的资深 Android scoped reviewer。本报告只对下列固定 code range 给出代码审查结论，不构成 R2、W90、独立 QA 或发布通过。

## 1. 固定现场

- Worktree：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\Aurorama`
- Branch：`codex/w90-r2-download-control`
- Code range：`241e7ac14ddbd4c6b13cfa8a3e0c32691d4dcc54..bd15dfbf27edb575a72b7203d3e1b44db3462067`
- Code HEAD：`bd15dfbf27edb575a72b7203d3e1b44db3462067`
- Code parent：`241e7ac14ddbd4c6b13cfa8a3e0c32691d4dcc54`
- Docs HEAD：`87d6af1bc04c5fd542009209cd12d3df288b5a0a`
- Docs parent：`bd15dfbf27edb575a72b7203d3e1b44db3462067`
- Docs change：仅新增 `.superpowers/sdd/w90-r/w90-r2-p2-tx-fix.md`
- Code diff：3 files，`+349/-23`
- 开工状态：tracked / staged / untracked = `0 / 0 / 0`
- 开工 SHA、parent、branch、docs HEAD、范围均与派单一致，无漂移。

范围文件：

- `core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt`
- `core/src/test/java/com/zhangwenkang/cinefin/utils/DownloaderImplRecoveryTest.kt`
- `data/src/test/java/com/zhangwenkang/cinefin/database/ServerDatabaseIntentDeletionSqlTest.kt`

非目标：不改代码 / 测试 / schema / v16 / `Versions.kt` / workflow / 签名 / release；不重开已通过的 partial 替换、normal redownload、source lifecycle、tombstone、active-job fence 或 UI；不执行 QA、merge-prep、merge、push、tag、Release；不接触设备、adb、instrumentation、截图或生产 Jellyfin。

## 2. P0-P3

- P0：0
- P1：0
- P2：0
- 本轮新增 P3：0
- 首轮 P3-1（SQL 字段断言不足）：CLOSED
- 其他历史 P3：保持 OPEN，不属于本轮固定 range 的关闭声明，均不阻断本 scoped review

最终判定：`PASS`。任一 P0/P1/P2 才阻断，本轮为 0；历史开放 P3 仍不阻断后续独立 QA 启动，但不得被本报告宣称为关闭。

## 3. 逐项风险结论

### 3.1 同 batch 重试不再被 sidecar 短路

PASS。

- `existingCanonicalKeys()` 不再把“完整 pending sidecar 存在”单独当作 Room 已受理。
- pending record 必须同时满足完整 namespace 匹配，且该 item 当前存在 `RECEIVED` Room intent，才进入 existing 集合。
- 已解析 record 仍有非空 `sourceId` 时，保留既有已存在语义。
- Room 事务失败后，旧 legacy `FAILED` 仍在、sidecar pending 仍在，因此同一完整 batch 会重新进入 `saveAll()` 与 DAO 条件 UPDATE。
- `saveAll()` 在事务后重新读取全部请求对应 intent；任一未达到 `RECEIVED` 即显式抛错，不会把 sidecar 已写当 accepted。

证据：

- `DownloaderImpl.kt:349-375`：sidecar identity + Room `RECEIVED` accepted 门。
- `DownloaderImpl.kt:377-425`：sidecar 后写 Room，并强制回读验证。
- RED：`acceptedRequests=0`；GREEN：目标场景 `acceptedRequests=1`，source 后删除并再次冷恢复无 ghost。

### 3.2 Room intent 各状态

PASS。

- current = null：允许由精确完整 sidecar 通过既有 `saveDownloadIntents()` 条件插入，随后回读必须为 `RECEIVED`。
- current = `RECEIVED`：允许继续解析；sidecar 精确身份仍由前置 `sidecarContains(record)` 证明。
- legacy `FAILED`：仅当 `failureDetail == LEGACY_PROVENANCE_UNAVAILABLE` 时允许 takeover；DAO 推进 generation、写 `RECEIVED`、清空失败字段并更新展示字段。
- ordinary `FAILED`：拒绝生成 source，保持 fail-closed。
- 用户取消或删除后的逻辑 `DELETING` tombstone：实体状态为 `FAILED + failureReason=CANCELLED + failureDetail=null`，不满足 legacy marker，拒绝 takeover。
- 任意其他 failure detail：拒绝 takeover。
- takeover 后再次回读不是 `RECEIVED` 时返回 false；Room 异常不在 `runCatching` 内，直接离开 `processReceivedIntents()`，不会被静默报告成功。

没有发现绕过用户显式请求、插入非法新 intent 或吞掉 Room 异常的路径。

### 3.3 冷启动 takeover 与删除竞态

PASS，限于静态锁论证与 JVM 生产 seam 证据。

- `processReceivedIntents()` 全程持有 `receivedIntentLock`；pending sidecar 删除也进入同一锁。
- resolver 在 source 前先做 Room takeover，再做 `sidecarContains(record)` 检查；远程序列化前后都有 sidecar exact identity 检查。
- takeover 不回写 sidecar，因此不会在删除栅栏后把已消失的 pending 重新写回。
- 删除路径先通过 DAO generation tombstone，再进入同一 intent 锁删除 sidecar / source；resolver 若已越过检查，删除方在取得锁后会枚举并处理锁内已建立的新 source。
- 普通失败、取消 tombstone 与 legacy marker 的 SQL 门槛阻止迟到 resolver 将旧失败代次重新变成 source。
- 同 item 多 source / 多 namespace 仍以完整 sidecar identity 区分，没有新增裸 itemId 折叠或泛删。

未执行真机级并发压力、进程 kill 或真实 Room instrumentation，因此这里是组合证据，不宣称覆盖所有系统级交错。

### 3.4 `sidecarContains(record)` 语义

PASS。

- 语义是“当前完整 sidecar 快照中仍存在与该 record 全字段相等的一条记录”，不是只比较 itemId 或 namespace。
- 删除后、同 canonical identity 被改写为另一 record 后，旧 snapshot 不再命中。
- sidecar 缺失返回 false；不可读或损坏显式抛错，保持 fail-closed。
- takeover 不写 sidecar；resolver 在 origin / network / enqueue 前后多次检查，降低删除后复活窗口。
- 文件不存在与空完整文件统一为 `Missing`，不会误接受旧 pending。

由于 sidecar 没有独立 revision / timestamp，“完全相同的记录被合法新写入”会按新请求命中；现有内部写入路径没有发现可在删除栅栏后制造该记录的绕过。

### 3.5 持续 Room 失败与恢复

PASS。

- 持续失败时 `saveDownloadIntents()` 异常向上离开 resolver；后台入口记录“处理已受理下载意图失败”。
- 失败前不读取 origin、不创建 source、不启动 transfer。
- 完整 pending sidecar 保留，legacy Room intent 与 generation 保持不变，供修复后重试。
- Room 恢复后再次冷启动，DAO 条件 UPDATE 推进 generation，随后 source 插入成功。
- source 建好后 intent 清理失败仍不会退回 legacy `FAILED`；Room 状态是新的 `RECEIVED` 代次。

### 3.6 删除新 source 后旧失败不复活

PASS。

- takeover 成功后 Room 行已推进 generation 并清除 legacy failure。
- 新 source 删除继续复用既有 `deleteTaskById` / tombstone / lifecycle / active-job fence / sidecar remove 补偿，没有新增旁路。
- 删除后 Room intent 与 sidecar pending 均不存在；再次 `recoverOnStartup()` / `runQueue()` / `refreshDownloadTasks()` 不出现旧失败 ghost。
- 同 batch 失败窗口与 cold recovery 窗口均各有真实端到端断言。

### 3.7 测试 seam 与断言真实性

PASS，真实 Room instrumentation 仍 BLOCKED。

- 新测试从生产 `receiveMusicBatch()` 进入 `DownloadBatchInbox` 与生产 store 实现。
- 失败注入位于生产 `saveAll()` 调用 `database.saveDownloadIntents()` 的 DAO seam，不是在 helper / fake inbox 内伪造 accepted 结果。
- 测试直接断言：首次异常向上返回、sidecar 已写、旧 intent 未变、同 batch 重试重新调用保存、source 可恢复、持续失败无 source / transfer、Room 修复后可恢复、删除后无 ghost。
- DAO 条件 SQL 另由 SQLite 测试独立执行，不依赖 Downloader 测试替身。
- 真实 Room 事务回滚、instrumentation 与磁盘故障未执行，属于 BLOCKED，不是本轮代码缺陷结论。

### 3.8 P3-1 SQL 字段断言

CLOSED。

SQLite 测试现在直接断言：

- 成功 takeover 后 `status=RECEIVED`；
- `failureReason` / `failureDetail` 清空；
- `taskGeneration` 推进；
- `name / albumName / artist / trackIndex` 更新；
- ordinary `FAILED` 的 failure detail 与 generation 保持；
- 用户取消行保持 `FAILED/CANCELLED`，不被完整请求覆盖。

未关闭的 partial identity 扩展矩阵等其他历史 P3 不在本轮范围。

## 4. RED / GREEN 证据

RED XML：

`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\red-audit-241e7ac1-p2tx-20261026\workspace\core\build\test-results\testLibreDebugUnitTest\TEST-com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest.xml`

- tests / failures / errors / skipped：`70 / 2 / 0 / 0`
- timestamp：`2026-10-26T14:07:16.791Z`
- SHA-256：`F3192B5A9415152FDF3003B6CEC62744BDE051B6ED29AAF09856CAEE68FA353A`
- 失败 1：同 batch 重试 expected `1` actual `0`
- 失败 2：持续 Room 失败仍生成 `source-recovery`
- 两处均为 JUnit `AssertionError`，不是编译失败

Recovery 独立第 1 次：

- `60/60 executed`
- XML：`core\build\test-results\testLibreDebugUnitTest\TEST-com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest.xml`
- `70 / 0 / 0 / 0`
- timestamp：`2026-10-26T14:34:42.793Z`
- SHA-256：`79BE72808BD52D3D05E592150E39222B3101B781C0B7BD78818052A97453AE6D`

Recovery 独立第 2 次：

- `60/60 executed`
- `70 / 0 / 0 / 0`
- timestamp：`2026-10-26T14:36:23.423Z`
- SHA-256：`8A9B5F2D4A807800A444D4CDB1DC8971AF4E4AADFE8A6C040B0CBD1FFEDC547C`

最终全量 core/data：

- `68/68 executed`
- core：35 XML / `276 / 0 / 0 / 0`
- data：35 XML / `229 / 0 / 0 / 0`
- 合计：70 XML / `505 / 0 / 0 / 0`
- Recovery timestamp：`2026-10-26T14:42:06.361Z`
- Recovery SHA-256：`AE2BB317424258B23AB488DA18A90575F4CAEBFDC674A6B0815A0A6AECB1E925`
- SQL XML：`data\build\test-results\testDebugUnitTest\TEST-com.zhangwenkang.cinefin.database.ServerDatabaseIntentDeletionSqlTest.xml`
- SQL：`2 / 0 / 0 / 0`，timestamp `2026-10-26T14:41:48.055Z`

两条目标 case 在 RED、两次 focused GREEN、最终全量中均真实存在：

- `Room 保存失败后同一完整 batch 重试先接管旧代且删除新源不复活`
- `Room 持续失败时冷启动不得绕过接管生成 source 且修复后可恢复`

## 5. 独立门禁

| 门禁 | 独立结果 |
| --- | --- |
| Code SHA / parent / branch | 固定且无漂移 |
| Docs HEAD / docs-only 内容 | 固定且只新增实现报告 |
| 3 files / `+349/-23` | PASS |
| `git diff --check 241e7ac1..bd15dfbf` | exit 0 |
| Core/data JVM `--rerun-tasks` | 68/68 executed，70 XML / `505/0/0/0` |
| Recovery 连续两次 | 均 60/60 executed、`70/0/0/0` |
| 根 `ktfmtCheck --rerun-tasks` | 34/34 executed，BUILD SUCCESSFUL |
| `:app:phone:compileLibreDebugKotlin --rerun-tasks` | 114/114 executed，BUILD SUCCESSFUL |
| schema / v16 / Versions / workflow / signature / keystore / release 路径与内容扫描 | 0 命中 |
| 构建后 tracked / staged / untracked | `0 / 0 / 0` |

编译仅有仓库既有 Kotlin warning / Gradle deprecation，没有本次 range 新增错误。

## 6. BLOCKED / 未覆盖

- 真实 Room 事务故障与回滚 instrumentation：未执行，BLOCKED。
- 进程 kill、掉电、SQLite WAL / 文件损坏的真实时序：未执行，BLOCKED。
- 真机并发删除、WorkManager 抢占、真实跨服务器网络：未执行，BLOCKED。
- 独立 QA、merge-prep、merge、push、tag、Release：未执行；本 reviewer 无授权。
- 设备、adb、instrumentation、截图、生产 Jellyfin：零接触。

这些限制不改变本轮静态 / JVM scoped review 的 PASS，也不能被解释为 R2 / W90 / 发布通过。

## 7. 最终状态与下一步

- 最终判定：`PASS`
- P0 / P1 / P2 / P3（本轮新增）：`0 / 0 / 0 / 0`
- P3-1：CLOSED
- 其他历史 P3：OPEN，非阻断
- BLOCKED：真实 Room instrumentation、进程 kill / 掉电、真实磁盘 / 网络故障、设备并发
- 报告状态：docs-only

下一步角色：负责人显式决定是否启动与实现 / review 不同的全新独立 QA。

第一条只读动作：核对本报告固定 SHA、两轮 Recovery XML 与 70 XML 聚合计数。

停止条件：固定 SHA / status 漂移；要求 reviewer 修改代码、开启 QA、merge / push / release；需要 schema / v16 / 版本 / 发布扩权；或需要设备、生产写。

## 8. 实际上下文

报告落盘前 DeepSeek 状态：`230,964 / 1,048,576（22%）`；缓存命中 99.9%；请求数 438。未到 25 万收缩线。

本审查未执行独立 QA、merge-prep、merge、push、tag、Release、workflow、设备、adb、instrumentation、截图或生产 Jellyfin 访问。
