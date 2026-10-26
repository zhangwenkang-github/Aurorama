# W90-R2 sidecar / Room P2 回流修复 — 独立 QA

结论：`JVM_STATIC_QA_PASS_DEVICE_BLOCKED`

状态：`P0/P1/P2/P3 = 0/0/0/8 / DEVICE_INSTRUMENTATION_BLOCKED / RELEASE_NO_GO`

身份：与全部实现代理、两轮 scoped reviewer、旧 QA 均不同的全新独立移动端 QA。全程只做 QA、证据与报告；未修改候选产品代码 / 测试 / schema，未执行设备、adb、instrumentation、截图、生产 Jellyfin 访问，未 merge-prep / merge / push / tag / Release / workflow。

## 1. 固定对象与现场

- Worktree：`F:\Develop\code_data\work_tree\Aurorama\w90-r2-download-control\Aurorama`
- Branch：`codex/w90-r2-download-control`
- 固定 code HEAD：`bd15dfbf27edb575a72b7203d3e1b44db3462067`
- 固定 code parent：`241e7ac14ddbd4c6b13cfa8a3e0c32691d4dcc54`
- Review docs HEAD：`1c9ca8231fab59c1d8a6742aa3ebd5b77c961c88`
- W90-R2 base：`3f5c6a3220d97a764771bd126056be987c283085`
- 开工 worktree HEAD：`1c9ca823`（review 文档提交）；`1c9ca823^ = 87d6af1b`、`87d6af1b^ = bd15dfbf`、`bd15dfbf^ = 241e7ac1`
- 开工 tracked / staged / untracked：`0 / 0 / 0`
- `3f5c6a32..bd15dfbf`：40 commits / 71 files / `+17,493/-777`
- `241e7ac1..bd15dfbf`：3 files / `+349/-23`
- `git diff --check`：full range 与固定末段均 exit 0

本报告提交前 worktree clean。固定 SHA、parent、branch、status、range 均无漂移。

## 2. 独立快照

证据根：

`F:\Develop\code_data\qa\w90-r2-independent-qa-bd15dfbf-20261026`

从 code HEAD `bd15dfbf` 直接创建，不复制 worktree WIP，不使用候选 `.git`：

```powershell
git -c core.autocrlf=false archive --format=zip `
  --output=snapshot-bd15dfbf-canonical.zip `
  bd15dfbf27edb575a72b7203d3e1b44db3462067
```

- Canonical ZIP SHA-256：`6A9161BA8DA48352B0042F9393850DBF920883FDF364F0E2F6727EAAD4998305`
- ZIP：84,997,887 B
- Tracked files：1,575
- Unique tracked blobs：1,568
- 展开后的 tracked 路径差异：0
- 逐文件 raw blob mismatch：0
- 构建根：`...\snapshot-canonical`

首次默认 `git archive` 在 Windows `core.autocrlf=true` 下把 1,475 个文本 raw blob 转成 CRLF，虽路径一致但不能通过 raw-content 校验。该归档仅保留为制作方式诊断，未用于任何 QA 构建；正式门禁全部使用 `core.autocrlf=false` canonical 快照。

## 3. 必须重建的 P2 矩阵

| 目标场景 | 结论 | 独立证据 |
|---|---|---|
| partial namespace 失败 → 完整重下 → resolve/process/refresh → 删除新 source | PASS（JVM/static） | `partial namespace 首次失败后完整重下可替换旧占位且删除新源不复活失败`；旧 partial 被替换，新完整行可执行，删除后 refresh / cold recovery 均无 ghost |
| missing sidecar → 完整重下 supersede 旧失败 → 删除新 source | PASS（JVM/static） | `missing sidecar 完整重下显式接管旧失败且删除新源不复活 ghost`；generation takeover 后旧失败不复活 |
| sidecar 成功、第一次 Room 保存失败 → 同 batch 重试 → 删除新 source | PASS（JVM/static） | `Room 保存失败后同一完整 batch 重试先接管旧代且删除新源不复活`；首存显式异常，sidecar pending 保留，重试再次进入 DAO |
| Room 持续失败 → 冷启动不得绕过 takeover → pending 保留 → Room 恢复后可完成 → 删除无 ghost | PASS（JVM/static） | `Room 持续失败时冷启动不得绕过接管生成 source 且修复后可恢复`；失败期间 source / transfer 均为空 |
| 双完整 origin 歧义 | PASS（JVM/static） | `同 item source 双完整 origin 歧义时不得启动 transfer`；source 为 `SERVER_ERROR`，无 transfer，current server/user/baseUrl/token reads 均 0 |
| Unreadable / Corrupt / 普通 FAILED / DELETING fail-closed | PASS（JVM/static） | 既有 Unreadable / Corrupt / ambiguous / ordinary FAILED 用例；另执行 QA-only probe 补普通 FAILED + DELETING 冷启动与完整重下，二者均无 source、无 transfer、无 current fallback |
| 完整 provenance 正常路径 | PASS（JVM/static） | `完整 provenance 的接收意图仍正常入队执行` 等 Recovery 用例保持全绿 |

四个跨存储目标 case 均从生产入口跨越：

```text
legacy/partial failure
  -> complete provenance request
  -> sidecar + Room save
  -> process / resolve
  -> source / queue
  -> delete new source
  -> refresh / cold recovery
```

故障注入位于生产 `saveAll()` 调用 `saveDownloadIntents()` 的 DAO seam；Room 持续失败由同一生产入口向上抛错。不是只测 helper 或 fake inbox。

QA-only probe 仅在 canonical 快照中临时加入，断言：

- 完整 sidecar 不会接管普通 `FAILED` 或逻辑 `DELETING`（`FAILED/CANCELLED/failureDetail=null`）；
- 冷启动不生成 source / transfer；
- 完整重下显式失败；
- current-origin reads 为 0。

探针执行结果为 `71 / 0 / 0 / 0`（候选原 70 + QA-only 1），XML SHA-256：

`0E11969CFB3046A51DA3E437180EF929B81E7CD5900BEED5F7E4CCE78610E30A`

探针随后从快照移除；全树 1,575 个 tracked 文件再次与 `bd15dfbf` raw blob 比对，mismatch=0。证据 XML 保留在 `logs\recovery-qa-probe.xml`，归属明确为 QA-only，不属于候选 suite。

## 4. JVM 门禁与 executed

命令：

```powershell
$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'
.\gradlew.bat :core:testLibreDebugUnitTest :data:testDebugUnitTest `
  :modes:film:testDebugUnitTest :modes:music:testDebugUnitTest `
  :app:phone:testLibreDebugUnitTest --rerun-tasks --console=plain
```

- Exit：0
- Gradle：`BUILD SUCCESSFUL`
- Actionable tasks：`226 / 226 executed`
- XML 合计：184 suites / `1,246 / 0 / 0 / 0`

| Module | XML | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| core | 35 | 276 | 0 | 0 | 0 |
| data | 35 | 229 | 0 | 0 | 0 |
| film | 15 | 108 | 0 | 0 | 0 |
| music | 44 | 282 | 0 | 0 | 0 |
| phone | 55 | 351 | 0 | 0 | 0 |
| **合计** | **184** | **1,246** | **0** | **0** | **0** |

关键 suite：

- `DownloaderImplRecoveryTest`：候选最终 `70 / 0 / 0 / 0`
- `DownloadMediaSidecarIdentityTest`：`5 / 0 / 0 / 0`
- `ServerDatabaseIntentDeletionSqlTest`：`2 / 0 / 0 / 0`

Recovery 连续两次独立强制重跑：

| Run | Executed | Tests | Failures | Errors | Skipped | XML SHA-256 |
|---|---:|---:|---:|---:|---:|---|
| 1 | 60/60 | 70 | 0 | 0 | 0 | `CB17BEA5B1F85ACC9EBA4F5A39FD1F22587AD1BFD313A2CA73CCB792E9F20DEA` |
| 2 | 60/60 | 70 | 0 | 0 | 0 | `BDDF6738D5602EA47B6B8C1FE4CC3EF0ECD4480493A77B7BF3F818FE7CC88CA9` |

移除 QA-only probe 后，最终候选 Recovery 再次执行 60/60，XML：

- `70 / 0 / 0 / 0`
- Timestamp `2026-10-26T15:05:13.154Z`
- SHA-256 `688512A1DC98F8DDD187FC5BCA65FDD396ABE613E73BEB6CF02D4F636B7823D8`

两条 Room 跨存储 case 与 partial / missing-sidecar 两条重下 case 均在 XML 中真实存在并执行。测试数量只作执行证据，不代替本节 P2 场景判定。

## 5. 格式、debug 与四 ABI APK

命令：

```powershell
.\gradlew.bat :core:ktfmtCheck :data:ktfmtCheck `
  :modes:film:ktfmtCheck :modes:music:ktfmtCheck `
  :app:phone:ktfmtCheck :app:phone:assembleDebug `
  --rerun-tasks --console=plain
```

- Exit：0
- Gradle：`BUILD SUCCESSFUL`
- Actionable tasks：`258 / 258 executed`
- 构建只保留仓库既有 warnings / Gradle deprecations

四 ABI 公共 APK 身份：

- Package：`io.github.zhangwenkang.aurorama.debug`
- Version：`1.4.0(5)`
- minSdk：28
- targetSdk：36
- APK Signature Scheme v2：true
- V2 signer：`Android Debug`，cert SHA-256 `a645eac56cff83f4cc7a93b092db628ee8b504c3347cc951de2ffe85d9e910d6`

| ABI | Bytes | SHA-256 | v2 |
|---|---:|---|---|
| arm64-v8a | 149,681,535 | `4ADDD680956CFDF33D4DE7DBDE4693E48BD60F0CC5EDF47B8E69B6AC2516B047` | true |
| armeabi-v7a | 146,330,115 | `693A28A2B791503D566C0B83DED4BDBF39524EAD07130840E5F29D66D806733E` | true |
| x86_64 | 154,770,477 | `CE303BCED90F053472096067C2EDBEA34032AE903F9741DF708B60BDCE714650` | true |
| x86 | 152,712,923 | `6C4441BCD52AE05BBD4A9E435D74460D48A35719563CFA24C2D3DD166A119327` | true |

APK 只在宿主快照构建、读取和哈希；未安装、未启动、未连接设备。

debug APK 非字节稳定是历史 P3，不作为本轮错误；本轮四 ABI 哈希已固定记录。

## 6. 范围、schema 与静态检查

- `3f5c6a32..bd15dfbf`：40 commits / 71 files / `+17,493/-777`
- 固定末段 `241e7ac1..bd15dfbf`：3 files / `+349/-23`
- 固定末段 schema：0
- 全 R2 range schema：仅新增获准的 `data/schemas/.../15.json`
- `ServerDatabase.version = 15`，包含 `AutoMigration(14, 15)`
- Schema 文件最高为 `15.json`；不存在 `16.json`
- `Versions.kt` 在 R2 range 命中：0；当前身份保持 `1.4.0(5)`
- Workflow / signature / keystore / release 路径命中：0
- Full range `git diff --check`：exit 0
- Final range `git diff --check`：exit 0
- graphify update：exit 0，14,152 nodes / 44,943 edges / 482 communities
- graphify 保留仓库既有 `ItemHeader.kt` / `ItemTopBar.kt` AST warnings
- QA 自身候选产品 / 测试 / schema 修改：0

## 7. P0-P3

- P0 = 0
- P1 = 0
- P2 = 0
- P3 = 8

前次 QA 的 7 项 P3 重审后均仍保留：

1. 单个持续不可删的 DELETING 产物可让单轮启动恢复提前结束，延后其余 source；当前只有 JVM/static 证据，不能证明会复活任务。
2. P2-4 的 intent→task mapper 与音乐行文案分别由两段测试连接，仍无单一 `intent -> task -> row` 连续契约测试。
3. `visibleFailureDetail` 未按 `FAILED` 状态门控；当前 lifecycle 使风险不可达。
4. sidecar 坏 base64 的生产恢复测试仍主要覆盖必需 itemId；可选字段错误会成为 null，尚未证明真实磁盘故障矩阵。
5. 既有 Step B RED XML 被 GREEN 同路径覆盖、报告路径偏差等证据债仍在。
6. debug APK 同源码构建非字节稳定；本轮记录哈希，不宣称可复现二进制。
7. 64 路 source lock hash collision 与 approved-v15 同 sourceId 跨 namespace 限制仍保留。

新增开放 P3：

8. partial sidecar identity 测试仍未覆盖 user-only、无 origin 线索、item/kind 不同、server+user 双冲突的完整矩阵。

跨存储修复已关闭前一轮 SQL 字段断言 P3；本轮没有发现能让以上 P3 升级为 P0/P1/P2 的新证据。

## 8. BLOCKED / NOT_RUN

以下全部保持 `BLOCKED`：

- 设备安装 / 启动 / 升级 / 回归、真实 bounds、键盘焦点、触控与进程生命周期
- Compose runtime semantics、TalkBack、可见文案时长与视觉验收
- Android Room instrumentation、真实事务回滚与迁移
- 真实进程 kill、掉电、SQLite WAL / 文件损坏时序
- WorkManager 真实抢占、前台服务与通知并发
- 真实跨 server/user 网络、token 轮换、sourceId 碰撞与网络取消
- 真实文件系统只读、磁盘满、atomic move / rename / delete 故障
- 生产 Jellyfin 任意请求或写入

本任务设备锁为 0 ACTIVE，且未获唯一 serial 授权；本线程未执行 `adb`，也不复用历史 `adb devices -l` 作为本轮执行证据。

## 9. 结论与剩余风险

固定 `bd15dfbf` 的独立 JVM、XML、格式、debug 构建、APK 身份、范围合同与允许范围内静态矩阵全部通过；指定 partial / missing-sidecar / Room 失败重试 / 持续 Room 失败 / 删除后无 ghost 场景均由独立快照证据支持。

未发现新的 attributable P0/P1/P2。但设备、Compose、TalkBack、真实触控、Room instrumentation、进程 / 掉电、WorkManager 与真实 I/O / 网络故障均未执行，因此最高只能是：

`JVM_STATIC_QA_PASS_DEVICE_BLOCKED`

这不是 R2、W90 或 1.4.0 发布 PASS。当前发布门仍 **NO-GO**，等待负责人结合设备 / instrumentation 结果和整体发布裁决。

## 10. 下一步

角色：W90-R 负责人 / 发布门决策者。

第一条只读动作：读取本报告 §3、§4、§8、§9，核对 canonical snapshot、两份独立 Recovery XML 与 APK 静态摘要；再确认设备锁与唯一 serial 授权。

只有负责人另行授权且存在唯一 serial 时，才由新的设备 / instrumentation QA 执行剩余 BLOCKED 项；本 QA 不自行修复、不 merge / push / release。

停止条件：固定 code SHA / clean 漂移；需要 QA 修改产品 / 测试 / schema；发现 P0/P1/P2；无唯一 serial 却要求设备执行；要求 QA 接触生产写、merge、push、tag、Release 或 workflow。

## 11. 已套用经验 / 新增可复用经验 / 待确认提议

已套用：

- `test-runner-executed-evidence`：同时核对 Gradle exit、actionable tasks executed、XML case / counts。
- `context-budget-two-tier`：在 28 万水位停止扩项，只完成报告与收口。
- `screenshot-budget-visual-qa`：零截图、零视觉主会话判断。

新增可复用经验：

- Windows `core.autocrlf=true` 下直接 `git archive` 会转换文本换行，使展开快照 raw blob 与 Git object 大量“假 mismatch”；独立快照应使用 `git -c core.autocrlf=false archive`，并同时校验路径、文件数、unique blobs 与 raw-content。

待确认提议：无。

## 12. 证据索引

证据根：

`F:\Develop\code_data\qa\w90-r2-independent-qa-bd15dfbf-20261026`

- 快照校验：`logs\snapshot-verification-canonical.txt`
- Full JVM：`logs\full-jvm.log`
- XML 聚合：`logs\jvm-xml-summary.tsv`
- Recovery 两次：`logs\recovery-focused-1.xml`、`logs\recovery-focused-2.xml`
- QA-only probe：`logs\recovery-qa-probe.xml`
- 最终候选 Recovery：`logs\recovery-final-baseline.xml`
- 格式 / debug：`logs\ktfmt-phone-debug.log`
- APK：`logs\apk-static-summary.tsv` 与各 APK `badging` / `signing` 文本
- graphify：`logs\graphify-update.log`

## 13. 实际上下文

收口前 DeepSeek 状态：`280,142 / 1,048,576（26.7%）`，缓存命中 99.7%，请求数 532。已进入 30 万前收缩区，不扩新矩阵；只保留报告、commit 与记忆同步。

本 QA 未执行 merge / push / release / workflow，未接触设备 / adb / instrumentation / 截图，未访问或写入生产 Jellyfin。
