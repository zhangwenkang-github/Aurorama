# W90-R2 P2 完整重下 supersede legacy 失败 — 实现报告

状态：`IMPLEMENTATION_DONE / SCOPED_REVIEW_PENDING`

## 计划与边界

- 目标：修复 QA 已归因的唯一 P2，使无 provenance 的 legacy/partial 旧记录继续 fail-closed、用户可见；用户从完整 provenance 正常入口重新下载后，新请求能安全接管旧失败，并在删除新 source 后不留下 partial 抑制或 ghost task。
- 非目标：不改 schema/v15、不新增 v16、不改 `Versions.kt`、workflow、签名或 release 配置；不触碰生产 Jellyfin；不启用设备 / adb / instrumentation / 截图；不做独立 QA、scoped review、merge-prep、merge、push、tag、Release。
- 固定起点：branch `codex/w90-r2-download-control`，HEAD `e03f357aa78fef1053392a7bccb259df3d0782fb`，parent `bfcab09fd2d15e740003caba929848d672913c03`，起点 clean。
- 实现候选：HEAD `1c9a98ddf7e4313a20a0899a7fe66a13d976c545`，parent `e03f357aa78fef1053392a7bccb259df3d0782fb`。
- 预计上下文 120k–180k；实际在本报告落盘前为 `308,290 / 1,048,576（29.4%）`。已按 30 万门禁停止新增工作，只完成当前最小收口：候选提交与本报告。

## 根因

1. `processReceivedIntents()` 以 item 为单位传播任一不完整 pending record，使同 item 的新完整 pending 也失去 resolver 资格。
2. `DownloadMediaSidecar.upsert()` 只在 incoming `sourceId` 非空时替换旧 pending；音乐入口的完整请求 `sourceId = null`，因此旧 partial 行保留。
3. `saveDownloadIntents()` 对所有请求使用 `INSERT OR IGNORE`，旧 legacy `FAILED` 行不会因完整重下转为新一代 `RECEIVED`。
4. 旧失败 intent 在最终清理中不受完整 pending 进度约束；新 source 暂时遮住它，删除 source 后重新显形。

## RED

命令在固定 HEAD 的非 Git archive 快照中执行；该快照仅覆盖测试先行版本，并复原旧保存 fake，生产代码仍为 `e03f357a`：

```powershell
$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'
.\gradlew.bat :core:testLibreDebugUnitTest --tests com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest --console=plain
```

- 快照：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\red-audit-e03f357a`
- XML：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\red-audit-e03f357a\core\build\test-results\testLibreDebugUnitTest\TEST-com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest.xml`
- executed：`68 tests, 2 failed, 0 errors, 0 skipped`；Gradle `60/60 executed`。

partial 变体：

- expected：完整重下后 partial 占位被可证明关联的新完整行替换，item 成功解析出新 source。
- actual：intent 仍为 `FAILED/CANCELLED/下载来源不可证明，请重新下载/taskGeneration=0`；sidecar 同时含 server-only partial 行和完整 pending 行，未插入 source。

missing-sidecar 变体：

- expected：删除新 source 后旧失败已被 takeover 并收口，intent 不从旧 FAILED 行复活。
- actual：intent 仍为 `FAILED/CANCELLED/下载来源不可证明，请重新下载/taskGeneration=0`，形成 ghost task。

两处失败均为 JUnit `AssertionError` 行为断言，不是编译失败。

## GREEN 与实现

核心行为：

- sidecar 的完整 pending 只替换同 item/kind、旧 `sourceId` 为空、且至少一个非空 server/user 段与完整请求一致、其余非空段不冲突的 partial 占位；双完整、冲突 partial、无关 source 和其他 item 均保留。
- pending 解析改为逐个完整 record 判定，旧 partial 不再按 item 阻断新完整 record；只有完整 pending 会维持 `RECEIVED` intent 等待解析。
- DAO 事务仅对完整 namespace 的显式请求执行条件 UPDATE：只接管当前 `RECEIVED` 或失败详情精确等于 legacy marker 的 intent，并推进 `taskGeneration`；普通 `FAILED`、DELETING 和无 provenance 请求继续保留。
- 旧 legacy resolver 仍使用 expected generation 条件写入，因此新请求先推进 generation 后，迟到解析不能回写旧代。
- `saveAll` 继续先原子写 sidecar，再同步 Room；sidecar 写失败抛错，DAO 写失败不吞。新 source 删除继续走既有 lifecycle / tombstone / active-job fence / sidecar remove 补偿，本轮未改写该路径。

GREEN 同一 Recovery 路径最终包含于实际执行的 core 全任务：`68` 条本类全绿；core 汇总 `35 XML / 274 tests / 0 failures / 0 errors / 0 skipped`。

XML：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\Aurorama\core\build\test-results\testLibreDebugUnitTest\TEST-com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest.xml`

## 实际执行

通过的 Gradle 命令：

```powershell
$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'
.\gradlew.bat :core:testLibreDebugUnitTest :data:testDebugUnitTest --console=plain
.\gradlew.bat ktfmtCheck :app:phone:assembleDebug --console=plain
```

- core：`35 XML / 274/0/0/0`。
- data：`35 XML / 229/0/0/0`。
- focused sidecar identity：`5/0/0/0`。
- focused SQLite supersede SQL：`2/0/0/0`。
- `ktfmtCheck`：通过。
- `:app:phone:assembleDebug`：成功；最后执行 `273 tasks / 27 executed / 246 up-to-date`。
- `git diff --check`：exit 0。
- `graphify update .`：exit 0，`14,123 nodes / 44,877 edges / 475 communities`；保留既有 `ItemHeader.kt` / `ItemTopBar.kt` 两项 AST warning。
- 禁改扫描：schema / v16 / `Versions.kt` / workflow / signature / keystore / release 命中 `0`；`16.json` 不存在。

## 变更

- `core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt`：`+23/-21`
- `core/src/test/java/com/zhangwenkang/cinefin/utils/DownloaderImplRecoveryTest.kt`：`+162/-6`
- `data/src/main/java/com/zhangwenkang/cinefin/database/ServerDatabaseDao.kt`：`+48/-2`
- `data/src/main/java/com/zhangwenkang/cinefin/utils/DownloadMediaSidecar.kt`：`+33/-2`
- `data/src/test/java/com/zhangwenkang/cinefin/database/ServerDatabaseIntentDeletionSqlTest.kt`：`+56/-3`
- `data/src/test/java/com/zhangwenkang/cinefin/utils/DownloadMediaSidecarIdentityTest.kt`：`+39/-0`
- 合计：6 files，`+361/-34`。

## 自报分级

- 本轮范围内实现自报：P0=0、P1=0、P2=0、P3=0（仅为实现候选，不构成 review / QA 结论）。
- QA 既有的 7 项 P3 未由本实现声明关闭；双完整 `Ambiguous`、Unreadable/Corrupt fail-closed、一成一败、普通 CANCELLED 文案均由 focused / core 回归覆盖并保持。

## 未覆盖 / BLOCKED

- 未执行设备、adb、Android Room instrumentation、进程 kill、真实跨服务器网络、真实 WorkManager 或真实文件系统故障注入；这些保持 `DEVICE_INSTRUMENTATION_BLOCKED` / 未覆盖。
- 并发证据为侧写锁、DAO 条件 UPDATE + generation fence、既有 lifecycle 回归与冷启动恢复测试组合，不等于真机级并发验收。
- 未做独立 QA、scoped review、merge-prep、merge、push、tag、Release 或任何生产写。

## 下一步

角色：与实现者不同的 fresh scoped reviewer。

第一条只读动作：核对 `e03f357a..1c9a98dd` 的 6 文件 diff，先读本报告与上述 RED/GREEN XML，再审查 partial 替换资格、完整 namespace 门、intent generation 条件 UPDATE、完整 pending 清理口径及删除 source 后无 ghost。

停止条件：需改 schema/v16/版本/workflow/签名/release，或要求实现代理自开 QA / merge / push / release 时停止。本实现代理停在报告，不推进下一角色。
