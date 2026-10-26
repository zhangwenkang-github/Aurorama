# W90-R2 P2 完整重下 supersede legacy 失败 — Scoped Review

状态：`RETURN_FOR_FIX`

审查角色：全新只读 scoped reviewer，与实现代理、历史 reviewer、独立 QA 均不同。

审查日期：2026-10-26

## 1. 固定现场

- Worktree：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\Aurorama`
- Branch：`codex/w90-r2-download-control`
- Docs HEAD：`ff686359c99684a6a8465b3bf797728e91f2c4c0`
- Code HEAD / Docs HEAD^：`1c9a98ddf7e4313a20a0899a7fe66a13d976c545`
- Code parent：`e03f357aa78fef1053392a7bccb259df3d0782fb`
- 起始 `git status --short --branch`：仅有分支行，tracked / staged clean。
- `1c9a98dd..ff686359`：仅新增 `.superpowers/sdd/w90-r/w90-r2-p2-retry-fix.md`；审查期间的 current tree 与固定 code HEAD 在 6 个产品 / 测试文件上一致。
- 待审 range：`e03f357a..1c9a98dd`，6 files，`+361/-34`：
  - `core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt` `+23/-21`
  - `core/src/test/java/com/zhangwenkang/cinefin/utils/DownloaderImplRecoveryTest.kt` `+162/-6`
  - `data/src/main/java/com/zhangwenkang/cinefin/database/ServerDatabaseDao.kt` `+48/-2`
  - `data/src/main/java/com/zhangwenkang/cinefin/utils/DownloadMediaSidecar.kt` `+33/-2`
  - `data/src/test/java/com/zhangwenkang/cinefin/database/ServerDatabaseIntentDeletionSqlTest.kt` `+56/-3`
  - `data/src/test/java/com/zhangwenkang/cinefin/utils/DownloadMediaSidecarIdentityTest.kt` `+39/-0`
- `git diff --check e03f357a..1c9a98dd`：exit 0。
- schema / v16 / `Versions.kt` / workflow / signature / keystore / release 路径命中：0；目标 `16.json` 不存在。

未发现 code SHA、docs HEAD 或 clean 状态漂移。

## 2. 输入与边界

已读实现报告全文、`PROJECT_PLAN` 的项目 / 模块 / 协作规程相关段、`PLAYER_PLAN` §48，以及限定 6 文件 diff 和必要 surrounding code。使用 `rg` 与 `graphify query` 定位 `DownloadBatchInbox`、`DownloadControlPlaneRules`、`DownloaderImpl`、`ServerDatabaseDao`、`DownloadMediaSidecar` 的调用关系。

输入缺口：

- 指定 `.superpowers/sdd/w90-r/w90-r2-independent-qa-rerun.md` 不存在；在 `work_tree\Aurorama`、项目主工作区和相关 Git 历史中均未找到同名报告。
- 当前 `docs/PLAYER_PLAN.md` §48 实际是较早的 W90-R2-A round 1，不包含任务所述的 “legacy intent provenance 产品语义裁决”。
- 上述缺口不改变固定 SHA、代码行为与独立复跑证据，但意味着无法对指定 QA 报告 §4/§7/§10 做逐字交叉核对。此缺口计入 `BLOCKED=1`，不作为代码 P 级 finding。

非目标均保持：未修改产品或测试代码，未开启独立 QA、merge-prep、merge、push、tag、Release 或 workflow，未接触设备、adb、instrumentation、截图或生产 Jellyfin，未执行 clean / reset / stash / worktree 删除。

## 3. 结论与分级

- 最终状态：`RETURN_FOR_FIX`
- P0：0
- P1：0
- P2：1
- P3：2
- P0/P1/P2 任一非 0，故不得进入 QA 或后续交付门。

P2 阻断。两条 P3 均不单独阻断；其中 P3-1 与 P2-1 的交易失败测试缺口相关，修复 P2 时应一并补上。

## 4. P2-1：完整 sidecar 已落盘但 Room 保存失败时，旧 legacy 失败未被新代接管，删除新 source 后复活

位置：

- `core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt:365-403`
- `core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt:349-363`
- `core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt:952-960`
- `core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt:926-935`
- `core/src/main/java/com/zhangwenkang/cinefin/utils/DownloaderImpl.kt:3120-3134`
- `data/src/main/java/com/zhangwenkang/cinefin/database/ServerDatabaseDao.kt:128-151`
- `data/src/main/java/com/zhangwenkang/cinefin/database/ServerDatabaseDao.kt:806-818`

复现逻辑：

1. 旧音乐 intent 已按 legacy 语义失败，保持 `FAILED / CANCELLED / 下载来源不可证明，请重新下载 / generation=0`。
2. 用户从完整 server + user provenance 重新下载。`saveAll()` 先成功 `sidecar.putAll(...)`，随后调用 `database.saveDownloadIntents(...)`；若 Room 事务失败，sidecar 中的完整 pending 已提交，但 Room 的条件 UPDATE 与 generation 推进整体回滚。
3. 用户重试同一 batch 时，`existingCanonicalKeys()` 会从 sidecar 的完整 pending 判定该请求已存在，现有 `fresh` 为空，因此不会再次修复 Room。
4. 冷启动看到完整 pending sidecar，会启动 `processReceivedIntents()`。`resolveSidecarIntent()` 只拒绝 `FAILED/CANCELLED/failureDetail=null`，不会拒绝仍带 legacy marker 的旧失败，因此仍能插入新 source。
5. 成功转成 source 后，最终清理只删除 `RECEIVED` intent；旧 marker 仍是 `FAILED`，不会删除，也不会补推进 generation。
6. 新 source 暂时遮住旧失败；删除新 source 后，旧失败重新成为可见 ghost。目标语义“删除新 source 后旧失败绝不复活”在此故障窗口不成立。

这不是理论上的不可达分支：`saveAll()` 明确采用 sidecar 先写、Room 后写的跨存储顺序，且没有失败补偿；任务本身也要求审查 DAO 事务失败 / 重试。当前测试没有在 sidecar 写成功后注入 Room `saveDownloadIntents` 失败，因此现有两个生产 seam GREEN 不覆盖该路径。

修复验收至少应证明：

- Room 保存失败后，重试 batch 或冷启动不能直接靠 sidecar 建新 source 而绕过旧 intent 的受控 takeover；
- 受控 takeover 必须推进 generation，之后删除新 source、再次冷启动仍无 legacy ghost；
- 失败补偿不得吞掉 Room 异常，也不得破坏可重试的完整 pending。

## 5. P3-1：SQL 测试证实了状态门槛，但未直接断言失败字段清除和普通取消行保留

位置：`data/src/test/java/com/zhangwenkang/cinefin/database/ServerDatabaseIntentDeletionSqlTest.kt` 新增用例。

该用例覆盖 `RECEIVED`、legacy marker `FAILED` 与普通 `FAILED` 的接管差异，并检查 generation 推进；SQL 的 `WHERE` 条件本身也能排除 `DELETING` 形态的 intent 记录。但测试只比较 `status + taskGeneration`，没有断言成功接管后 `failureReason` / `failureDetail` 为 null、音乐元数据被更新，也没有直接放入 `FAILED / CANCELLED / failureDetail=null` 行验证保留。

不单独阻断，但建议随 P2 修复补齐精确字段断言。

## 6. P3-2：partial 替换单测只覆盖 server 单段匹配、server 冲突和 full 已存在

位置：`data/src/test/java/com/zhangwenkang/cinefin/utils/DownloadMediaSidecarIdentityTest.kt` 新增用例。

生产 predicate 已正确实现同 item/kind、至少一个非空 origin 段相符、其余非空段不冲突、旧 sourceId 为空。现有新增测试覆盖了一个 server-only 匹配与一个 server 冲突保留；其他既有测试覆盖双完整跨 origin 保留。仍缺 user-only 匹配、无 origin 线索 partial、item/kind 不同、server+user 双冲突的 sidecar 级别直接断言。

不单独阻断；核心替换逻辑本身与要求一致。

## 7. 重点问题逐项结论

- partial 替换资格：PASS。不是裸 itemId 泛删；同 item/kind、至少一段 origin 相符、其余非空不冲突、旧 sourceId 必须为空；双完整、冲突 partial、无 origin partial、无关 item/kind 保留。
- per-record 解析与 legacy fail-closed：PASS。仅有 partial 的 item 仍进入 legacy 收口；完整 record 通过冻结 namespace 选择 origin repository，没有回退 current origin。
- DAO 条件 UPDATE 正常路径：PASS。只接管 `RECEIVED` 或 failureDetail 精确等于 marker 的行，普通失败、取消 tombstone 与其他错误行不更新；成功路径推进 generation。
- 迟到 resolver CAS：正常路径 PASS。legacy resolver 用 expected generation 条件写；新请求先推进 generation 后，旧代写失败。上述 P2-1 是 Room 整体失败后完全没有推进 generation 的独立窗口。
- 同 item 多请求 / 双完整 origin：正常路径 PASS。现有生产 seam 测试与 identity 测试表明两个完整 namespace 可并行保留并分别解析，不被裸 itemId 折叠。
- 重复 batch：正常无故障路径 PASS；DAO 保存失败后退化路径被 P2-1 阻断。完整 pending 会让 batch 被误判为已存在，无法自动修复 Room。
- 删除新 source：正常路径 PASS，复用既有 lifecycle / tombstone / active-job fence / sidecar 补偿；无新 bypass。DAO 保存失败窗口下旧失败复活，见 P2-1。
- RED 真实性：PASS。RED 不是编译失败，两个失败均为生产 seam 行为断言。
- 既有 P3：实现报告没有宣称关闭；本审查也不关闭历史 P3。

## 8. RED 证据核对

实现 RED 根目录：

`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\red-audit-e03f357a`

独立解析 XML：

`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\red-audit-e03f357a\core\build\test-results\testLibreDebugUnitTest\TEST-com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest.xml`

- Recovery：`68 tests / 2 failures / 0 errors / 0 skipped`
- 失败 1：`partial namespace 首次失败后完整重下可替换旧占位且删除新源不复活失败`
  - 真实断言失败：新 source 未插入，旧 intent 仍为 legacy `FAILED`，sidecar 同时保留 partial 与完整 pending。
- 失败 2：`missing sidecar 完整重下显式接管旧失败且删除新源不复活 ghost`
  - 真实断言失败：删除新 source 后旧 marker `FAILED` 仍存在。
- 两个用例都从 `legacy/partial failure -> complete re-download -> process -> runQueue -> delete new source -> refresh / recover`，使用 `DownloaderImpl`、真实临时 sidecar 与 SQLite / fake production seams；不是只测 helper 或 mock。

## 9. GREEN 独立复跑

所有命令均从与固定 code HEAD `1c9a98dd` 等价的产品代码树执行；`1c9a98dd..HEAD` 仅有实现报告 docs 文件。

1. 受影响全量：

```powershell
$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'
.\gradlew.bat :core:testLibreDebugUnitTest :data:testDebugUnitTest --rerun-tasks --console=plain
```

- 独立执行两次，均 `BUILD SUCCESSFUL`、`68 actionable tasks: 68 executed`。
- 最终 XML：
  - core：35 XML / `274 tests / 0 failures / 0 errors / 0 skipped`
  - data：35 XML / `229 tests / 0 failures / 0 errors / 0 skipped`
  - 合计：70 XML / `503/0/0/0`
- 最终 Recovery XML：`68/0/0/0`，timestamp `2026-10-26T13:57:39.275Z`，SHA-256 `7F7A9AE1AF4A07D589475A3D10E5A35D05348384A436F3EF72C0A5FF348CFADC`。
- SQL supersede XML：`2/0/0/0`，SHA-256 `95B73580E8663BECC522C2E69B0CAC086CC7949E08DB039ADAD02E5648807A8E`。
- Sidecar identity XML：`5/0/0/0`，SHA-256 `61898DA9E56ACE14978F5EFE6E0827B4900BD09963F75EBFD5E0A11F5AA53E26`。

2. Recovery 连续独立强制重跑：

```powershell
$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'
.\gradlew.bat :core:testLibreDebugUnitTest --tests 'com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest' --rerun-tasks --console=plain
```

- 第 1 次：`BUILD SUCCESSFUL`、`60/60 executed`、Recovery `68/0/0/0`、timestamp `2026-10-26T13:49:08.607Z`、SHA-256 `C66E28F31FFC0DF22895175F5129EE3F3F802C46EF79DFAB64E1FF784A17E123`。
- 第 2 次：`BUILD SUCCESSFUL`、`60/60 executed`、Recovery `68/0/0/0`、timestamp `2026-10-26T13:50:46.036Z`、SHA-256 `9CAEC53B98B187E500D69813AF9A526B06268CC79744825AB3CCEE087B3A51FB`。
- 两次 XML 均包含且执行两个目标 testcase：
  - `partial namespace 首次失败后完整重下可替换旧占位且删除新源不复活失败`
  - `missing sidecar 完整重下显式接管旧失败且删除新源不复活 ghost`

3. 格式与编译：

```powershell
.\gradlew.bat ktfmtCheck --rerun-tasks --console=plain
.\gradlew.bat :app:phone:compileLibreDebugKotlin --rerun-tasks --console=plain
```

- `ktfmtCheck`：`BUILD SUCCESSFUL`、`34/34 executed`。
- `:app:phone:compileLibreDebugKotlin`：`BUILD SUCCESSFUL`、`114/114 executed`。
- 仅保留仓库既有 Kotlin 编译 warning；没有本轮新增编译错误。

4. 最终现场：

```text
## codex/w90-r2-download-control
```

- tracked / staged clean；构建产物未进入 Git 脏状态。
- 未执行设备、adb、instrumentation、截图或生产访问。

## 10. BLOCKED / 未覆盖

- `BLOCKED=1`：指定独立 QA rerun 报告缺失，且 `PLAYER_PLAN` §48 正文与任务描述不匹配；无法逐字交叉核对 QA §4/§7/§10。
- 设备、Android Room instrumentation、真实进程 kill、真实跨服务器网络、真实 WorkManager 和真实文件系统故障注入均 `NOT_RUN`，符合本 reviewer 的显式非目标。
- 本次 P2-1 由固定代码路径与缺失测试窗口确认；没有通过新增测试或修改代码来复现，以满足只读边界。

## 11. 最终门禁

| 门 | 结果 |
|---|---|
| Code SHA / parent / branch | 固定且未漂移 |
| 6 files / `+361/-34` | PASS |
| `git diff --check` | PASS |
| Affected core+data JVM | `503/0/0/0` |
| Recovery 连续两次 | 均 `68/0/0/0`，两变体真实执行 |
| ktfmt | `34/34 executed` |
| Phone Kotlin compile | `114/114 executed` |
| 禁改范围 | 0 命中 |
| tracked / staged | clean |
| P0 / P1 / P2 / P3 | `0 / 0 / 1 / 2` |
| 最终状态 | `RETURN_FOR_FIX` |

本审查未开启独立 QA、merge-prep、merge、push、tag、Release 或 workflow，未接触设备或生产。下一步应由负责人另派全新实现代理，只修 P2-1 的 Room 失败补偿 / generation takeover 窗口并补交易失败 RED；修复后必须再次派新的 scoped reviewer，不得直接进入 QA。

## 12. 实际上下文

宿主未向 reviewer 暴露精确 token meter；按本会话工具输出与读入材料估计约 70k–90k，低于 25 万收缩线，未触发 checkpoint，也未接近 30/35 万停止线。
