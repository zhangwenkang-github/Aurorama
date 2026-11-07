# Aurorama 2.0 任务看板

> 只维护当前可执行任务。需求全文、决策理由和进度日志分别放 `V2_PLAN`、`DECISIONS`、ADR。

## In Progress

> **2026-11-07 当前状态**：`TTS_G2_DEFERRED_AFTER_2_0_CORE / DEFERRED_BLOCKED_CONTROL_PLANE /
> SOLVE_AFTER_OTHER_2_0_CORE_FEATURES / DO_NOT_RESUME_OLD_HANDOFF_LOOP /
> ARCH_SEAM_DELIVERY_ACTIVE / W0_ISOLATED_PROBE_ALLOWED /
> BROAD_FEATURE_WAVES_SEAM_GATED / PLAN_CONTINUITY_LEDGER=REQUIRED /
> ARCH_SEAM_FIRST=YES / INCIDENTAL_REFACTOR=NO /
> FEATURE_WAVE_GATED_BY_REQUIRED_SEAM=YES / ARCH_CONFORMANCE_PASS /
> USER_CONFIRMED_2026_11_07 / ARCH_GUARD_A0=INDEPENDENT_QA_PASS / MERGE_PREP_PASS / INTEGRATED /
> A0_GUARD_IS_NOT_ARCH_REFACTOR=YES / ARCH_IMPLEMENTATION_REFACTORED=NOT_PROVEN`。
> TTS 仍是 2.0 `MUST` 历史基线，但当前交付顺序延期；不得写成删除、已交付、QA / runtime /
> G1 PASS。本次规划从 clean `codex/v2-integration` /
> `fee63165f966a52318e332abe1ad7b184592a88a` 建 docs worktree；旧 G2 合同 / QA evidence
> 冻结，不得恢复 64/65、exact count 或 handoff 修复循环。
>
> `OPEN_LONG_TERM_PLAN_IDS=V2-ARCH-SEAM-IDENTITY-CATALOG-W1,
> V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2,V2-ARCH-SEAM-PLAYBACK-SESSION-W3,
> V2-ARCH-SEAM-READER-SOURCE-W4,V2-ARCH-SEAM-USER-WRITE-W5`；
> `NEXT_CRITICAL_PATH=V2-ARCH-SEAM-IDENTITY-CATALOG-W1 -> V2-NON-TTS-W1`；
> `last_evidence=711c4757 clean scope-race-repair docs + W1A QA R3 PASS
> P0-P3=0/0/0/3 at 120a011a + A0 integration ab917404`。
>
> 当前 W1A 已由 fresh QA R3 判 `PASS / P0-P3=0/0/0/3`，状态为
> `SLICE_W1A_QA_R3_PASS_WITH_P3_READY_FOR_MERGE_PREP`。repair `120a011a` 已关闭 QA R2
> 唯一 P2；剩余 3 个 P3 已登记，不得静默消失。首轮实现 candidate
> `16e19571c482b1af6958c4fc39ae58d84af42a0d` 与 test-compat
> `693eff11dd8cf43837ac090770cc6f3b37bace8b` 已通过实现自测、组合 build 与 A0 guard；
> 首轮 QA 为 `0/1/3/0`；repair chain
> `4da3a981 -> 0bb5c720 -> 012efb9c -> e05ba14c -> 120a011a` 全部机器门 PASS。
> QA R3 已关闭 P0-P2；下一步只允许独立 merge-prep，仍不得由实现 owner 自集成。
> DI 选择点位于 `:data` 的 `CatalogReadModule`，
> `core/RepositoryModule.kt` 保持原样。`V2-NON-TTS-W1` 仍保持
> `BLOCKED_BY_PREREQUISITES`，直到完整 seam DoD 通过。`V2-NON-TTS-W0-DOWNLOAD-CARD-ACTION`
> 可作独立探针，但状态为
> `ISOLATED_PROBE / NOT_ARCHITECTURE_PROGRESS / CURRENT_REPRO_REQUIRED`；仅两文件，无缺陷即
> `NO_DEFECT_REPRODUCED` 停止，不挡架构主线，也不计作 seam 进度。

## Architecture Critical Path / 长期计划账本

> 这是长期计划与 seam 派单的权威账本。功能波必须在 `PREREQUISITE_SEAMS` 达到
> `CONSUMER_MIGRATED + TESTS_PASS + INTEGRATED` 后才能开工；该压缩门只有在下列完整 seam
> DoD 全部满足后才成立。

| ID | 状态 | PREREQUISITE_SEAMS / 依赖 | 必须先于 | 交付物 / 可观察完成判据 | 证据门 | 下一门 | 当前未完成原因 |
|---|---|---|---|---|---|---|---|
| `V2-ARCH-SEAM-IDENTITY-CATALOG-W1` | `SLICE_W1A_QA_R3_PASS_WITH_P3_READY_FOR_MERGE_PREP` | `PREREQUISITE_SEAMS=NONE` | `V2-NON-TTS-W1` | canonical `Scope / Identity` 合同与 adapter 落地；目标 auth / preference / DB namespace / cache 消费者迁移；旧身份推断与跨账号双真源移除；切号 / 离线 / 缓存负例通过 | 完整 seam DoD；独立 QA `PASS` + integrated gate `PASS` | independent `MERGE_PREP` | W1A QA R3 PASS / P0-P2=0；P3=3 已登记 cleanup；父 seam仍未完成 |
| `V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2` | `BLOCKED_BY_IDENTITY_CATALOG` | `V2-ARCH-SEAM-IDENTITY-CATALOG-W1` 及所需 Catalog anchors | `V2-NON-TTS-W2` | command + immutable task / hierarchy projection 成为唯一下载真相；UI、通知、文件、sidecar、Room、离线投影消费同一投影；暂停 / 恢复 / 取消 / 删除 / 重启负例通过 | 完整 seam DoD；独立 QA `PASS` + integrated gate `PASS` | Identity/Catalog 完整 DoD | Identity/Catalog 前置未完成，当前只有 partial anchors；A0 guard 不是 seam |
| `V2-ARCH-SEAM-PLAYBACK-SESSION-W3` | `BLOCKED_BY_PREREQUISITES` | Identity/Catalog + Download-control seams | `V2-NON-TTS-W3` | `PlaybackSession` command / state / owner generation 落地；单活动逻辑会话与单 MediaSession；UI / service / notification 消费同一状态；旧 owner 不写新状态 | 完整 seam DoD；独立 QA `PASS` + integrated gate `PASS` | Identity/Catalog + Download 完整 DoD | 两项前置未完成；播放器仍为 partial session / coordinator 锚点 |
| `V2-ARCH-SEAM-READER-SOURCE-W4` | `BLOCKED_BY_PREREQUISITES` | Identity/Catalog + Download-control seams | `V2-NON-TTS-W4` 正文实现 | source-scoped book identity + progress / locator 合同落地；Readium / PDF / SAF / Range 消费者迁移；内容指纹与进度分离；错书 / 离线 / 强停恢复负例通过 | 完整 seam DoD；独立 QA `PASS` + integrated gate `PASS` | Identity/Catalog + Download 完整 DoD | 两项前置未完成；Reader 仅有 partial source anchors，非 TTS 切片尚未实现 |
| `V2-ARCH-SEAM-USER-WRITE-W5` | `BLOCKED_BY_PREREQUISITES` | Identity/Catalog seams | `V2-NON-TTS-W5` | user-state write intent → accepted → confirmed / denied 合同落地；data 写网关为唯一 writer；403 不重试；部分失败 / 回读 / 冲突负例通过 | 完整 seam DoD；独立 QA `PASS` + integrated gate `PASS` | Identity/Catalog 完整 DoD | 两项前置未完成，写 seam 当前 `NOT_IMPLEMENTED` |

**统一 seam Definition of Done**：

```text
CONTRACT_FROZEN -> ADAPTER_IMPLEMENTED -> TARGET_CONSUMERS_MIGRATED ->
OLD_PATH_REMOVED_OR_NO_DUAL_TRUTH -> CONTRACT/NEGATIVE/REGRESSION TESTS PASS ->
DEBT_COUNT_DECREASED -> INDEPENDENT_QA PASS -> INTEGRATED_GATE PASS
```

缺少任一状态不得标 Done，不得用“抽象已规划”“guard 通过”或历史 evidence 代替。

| 功能波 | PREREQUISITE_SEAMS | 当前门 |
|---|---|---|
| `V2-NON-TTS-W0-DOWNLOAD-CARD-ACTION` | `NONE（ISOLATED_PROBE / NOT_ARCHITECTURE_PROGRESS）` | 可独立派发；仅 `DownloadRows.kt` + `DownloadRowAccessibilityContractTest.kt`，无缺陷即停止，不挡架构主线 |
| `V2-NON-TTS-W1` | `V2-ARCH-SEAM-IDENTITY-CATALOG-W1` | `BLOCKED_BY_PREREQUISITES`；seam 未到三项压缩门前不得开工 |
| `V2-NON-TTS-W2` | `V2-ARCH-SEAM-IDENTITY-CATALOG-W1`、`V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2` | `BLOCKED_BY_PREREQUISITES` |
| `V2-NON-TTS-W3` | `V2-ARCH-SEAM-IDENTITY-CATALOG-W1`、`V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2`、`V2-ARCH-SEAM-PLAYBACK-SESSION-W3` | `BLOCKED_BY_PREREQUISITES` |
| `V2-NON-TTS-W4` | `V2-ARCH-SEAM-IDENTITY-CATALOG-W1`、`V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2`、`V2-ARCH-SEAM-READER-SOURCE-W4` | `BLOCKED_BY_PREREQUISITES` |
| `V2-NON-TTS-W5` | `V2-ARCH-SEAM-IDENTITY-CATALOG-W1`、`V2-ARCH-SEAM-USER-WRITE-W5` | `BLOCKED_BY_PREREQUISITES` |

### `V2-ARCH-SEAM-IDENTITY-CATALOG-W1` 冻结任务卡

- ID：`V2-ARCH-SEAM-IDENTITY-CATALOG-W1`
- 状态：`SLICE_W1A_QA_R3_PASS_WITH_P3_READY_FOR_MERGE_PREP`
- 冻结基线：root acceptance clean `codex/v2-integration` /
  `8865b0c8e0e57aa1dd790300e21515f92eb3c580`；前置 accepted candidate
  `310fdfdb7d5e41bdc1ce8ce63385c2f2d0e27a4f`（parent `d85f695b`）。本 DI scope correction
  仍是 docs-only candidate，root 集成并重新钉住 SHA 前不得派 implementation。
- 约束：`V2-D213`、`R035`、`L041`；
  `ARCH_SEAM_FIRST=YES / INCIDENTAL_REFACTOR=NO`
- `PREREQUISITE_SEAMS=NONE`。`G1-S3`、`G1-BASE-001`、`G1-BASE-003` 是静态输入合同，
  不是 seam 完成证据；A0 guard 只防债务。
- 本任务卡只冻结边界与执行卡；`V2-NON-TTS-W1` 继续保持 `BLOCKED_BY_PREREQUISITES`，
  直到父 seam 八状态 DoD 全部通过。当前不得宣告实现完成、父 seam 收口、QA `PASS` 或
  integrated `PASS`。

#### 目标与用户可观察不变量

父 seam 目标：把当前分散的 server / user / auth / preference / DB namespace / cache
身份收敛到 canonical `Scope / Identity` 合同；让目标消费者只依赖声明的 public port；
身份变化、离线、缓存 generation 与 stale snapshot 均不能把另一账号内容投到当前界面。

首个切片必须满足：

1. 一次在线搜索只绑定一个 `CatalogScope`：同一请求中的服务器、用户、凭据、请求资源、
   in-memory cache、disk snapshot 与返回快照必须属于该 scope。
2. 同一 server/user/context/query/scope 的重复读取可使用同一缓存；server、user、
   principal context、查询域或 generation 任一不同都不得复用。
3. 请求期间当前 scope 改变时，旧结果不得提交、不得覆盖新 generation，也不得由
   `SearchViewModel` 发到界面。
4. 在线 cache / disk hit 是陈旧但可容纳的数据时，public port 必须返回显式 `STALE`；
   网络成功或无陈旧数据的本地查询不得伪装为 `STALE`。
5. 离线目录读只能使用保存的当前账号身份和当前范围；没有完整身份时
   `IDENTITY_UNAVAILABLE`，不得回退到匿名、另一 server 或另一 user。
6. `SearchViewModel` 不再分别读取 `AppPreferences.currentServer` 与
   `JellyfinRepository.getUserId()` 猜测身份；旧 `getSearchItems` 搜索路径必须删除。

#### 非目标

1. 不重构 `JellyfinRepository` 全部方法，不把所有 metadata / image / music / download /
   reader / playback 消费者一次迁移到新 seam。
2. 不改 Room schema、数据迁移、下载 sidecar、Worker、通知、导航宿主、公共 UI、theme、
   `settings.gradle.kts`、根 build 或依赖目录。
3. 不实现跨进程 durable generation、304/ETag、完整 10 状态 freshness 机、401/403
   stale-if-error、eviction / pin / prefetch 策略或完整 BASE-003 运行时验收。
4. 不改账号切换写入事务；首个切片只在读侧 fail-closed，身份写入单写者留给 `ICS-W1B`。
5. 不做搜索排序、跨源混排、视觉、设备、网络服务器或 Release 工作；本切片不产生
   UI 截图或 runtime 证据。

#### 首个纵向切片

- ID：`ICS-W1A-SCOPED-SEARCH-SNAPSHOT`
- 名称：`Scoped Search Catalog Snapshot`
- 目的：只迁移服务器搜索这一条真实读路径，使 online / offline adapter、
  `MetadataCache`、`MetadataSnapshotStore` / SWR 与 `SearchViewModel` 通过同一
  `CatalogScope` 和显式 generation 闭环。
- 为什么先做：静态调用图显示生产 `getSearchItems` 只有
  `SearchViewModel` 一个调用方；底层已有 search cache key、snapshot store、SWR 与
  origin-bound client 锚点。爆炸半径小于首页、详情、图片、下载或播放，并能真实证明
  “同 scope 读取、切号拒绝旧结果、离线不跨域”。
- 复用既有 canonical 身份 DTO：`com.zhangwenkang.cinefin.repository.JellyfinOrigin`
  已表达 serverId + UUID userId，并已有 `OriginScopedJellyfinRepository` 使用先例；
  本切片不得另造与之等价的第二份 server/user DTO。

#### 当前身份 / 缓存 / 目录静态事实

| 维度 | 当前锚点 | 已识别缺口 |
|---|---|---|
| 当前 server | `AppPreferences.currentServer` | 与 user/auth 分步写入，不是原子 scope |
| 当前 user | `JellyfinApi.userId`；DB `servers.currentUserId` 指向 saved user | 读取路径混用内存 API 与 DB current user |
| auth / address | `JellyfinApi.api.accessToken/baseUrl`；DB user / address | singleton 可变；搜索当前没有显式绑定 origin client |
| canonical origin 锚点 | `JellyfinOrigin` + `bindOrigin` | 现有消费主要是下载 origin，搜索尚未使用 |
| cache namespace | `MetadataNamespace(serverId, userId.toString())` | 由 `AppPreferences` 与 `JellyfinApi.userId` 动态拼接 |
| cache generation | `MetadataFence.contentGeneration`；`invalidateAll` 只递增当前 namespace | generation 是进程内 `HashMap`，不是 durable fence |
| disk snapshot | `MetadataSnapshotStore` envelope 含 server/user/key/generation/timestamps/payload | 未含 principal context、完整 BASE-003 query shape 或外部 freshness |
| SWR | `MetadataSnapshotSwr` memory -> disk -> network，stale 可先返回并后台刷新 | 当前返回值只有 payload，没有向调用方暴露 `FRESH/STALE` |
| search key | `search:<SearchScope>:<trim/lowercase term>` | 对当前 endpoint 已区分域，但不是完整 BASE-003 canonical key |
| online search | `JellyfinRepositoryImpl.getSearchItems` -> `MetadataCache` / `MetadataSnapshotSwr` | 抓取使用 singleton `jellyfinApi.userId`；没有 origin-bound request |
| offline search | `JellyfinRepositoryOfflineImpl.getSearchItems` 按 serverId 查 Room | 忽略 `SearchScope`；userData 使用当前 singleton `jellyfinApi.userId` |
| consumer identity | `SearchNamespace` + `SearchNamespaceGuard` | 复制 server/user DTO；server 与 user 分两次读取 |

graphify 定向证据（只作导航，不作实现证据）：`JellyfinRepository` 为 175 度节点；
`AppPreferences -> JellyfinRepositoryImpl -> MetadataCache` 为 2 hop；
`MetadataCache -> MetadataSnapshotSwr -> MetadataSnapshotStore` 为 2 hop；
`SearchViewModel -> JellyfinRepository` 为 1 hop。

#### Contract / public port

全部位于 `:data` 模块。首个切片语义冻结如下；root review 后实现 owner 才可创建符号：

| 符号 | 真实 FQCN | 冻结字段 / 语义 |
|---|---|---|
| scope DTO | `com.zhangwenkang.cinefin.repository.JellyfinOrigin` | 既有 `serverId: String + userId: UUID`；非空 serverId；不得复制 |
| scope | `com.zhangwenkang.cinefin.repository.catalog.CatalogScope` | `origin: JellyfinOrigin` + `principalContext: CatalogPrincipalContext` |
| principal context | `com.zhangwenkang.cinefin.repository.catalog.CatalogPrincipalContext` | `ONLINE_ACCOUNT` / `SAVED_OFFLINE_ACCOUNT`；无名回退禁止 |
| query | `com.zhangwenkang.cinefin.repository.catalog.CatalogSearchQuery` | `term: String` + `scope: SearchScope` |
| freshness | `com.zhangwenkang.cinefin.repository.catalog.CatalogFreshness` | `FRESH` / `STALE` / `LOCAL_QUERY`；不得把本地查询标成服务器新鲜 |
| snapshot | `com.zhangwenkang.cinefin.repository.catalog.CatalogSnapshot<T>` | `scope`、`contentGeneration: Long`、`freshness`、`observedAtEpochMs: Long`、`value: T` |
| outcome | `com.zhangwenkang.cinefin.repository.catalog.CatalogReadOutcome<T>` | `Available(snapshot)` / `IdentityUnavailable`；其他异常继续显式失败 |
| public port | `com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort` | `suspend fun currentScope(): CatalogScope?`；`suspend fun search(query): CatalogReadOutcome<List<FindroidItem>>` |

`CatalogSnapshot.scope` 与 snapshot 内容必须是同一冻结 scope；port 不得暴露
`JellyfinApi`、DAO、Room entity、`MetadataNamespace`、`MetadataFence` 或 snapshot store
内部类型。`MetadataNamespace` 只作为 data 内部 cache adapter 细节。

#### Adapter / implementation 与依赖注入

| 角色 | 真实目标 | 约束 |
|---|---|---|
| online implementation | `data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryImpl.kt` | 实现 `CatalogReadPort`；先解析并校验 canonical origin，再用既有 `bindOrigin` 的 origin-bound client 抓取；复用 `MetadataCache` + `MetadataSnapshotSwr` |
| offline implementation | `data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryOfflineImpl.kt` | 实现 `CatalogReadPort`；按保存的 current server/user 解析 scope，按 `SearchScope` 过滤 Room 结果，显式使用 scope.userId 映射 userData |
| support / resolver | `data/src/main/java/com/zhangwenkang/cinefin/repository/catalog/CatalogReadPort.kt` | 可在同文件放 internal scope resolver；不得另建匿名 namespace |
| DI selection | `data/src/main/java/com/zhangwenkang/cinefin/di/CatalogReadModule.kt`（新建） | `@Module @InstallIn(SingletonComponent::class)`；按既有 `AppPreferences.offlineMode` 选择 online / offline port；复用 core `RepositoryModule` 已提供的 `JellyfinRepositoryImpl` / `JellyfinRepositoryOfflineImpl` singleton，不重复创建；`core/RepositoryModule.kt` 不变 |
| 旧接口 | `data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepository.kt` | 删除 `getSearchItems` 声明；其他方法不迁移 |

`CatalogReadModule` 使用 `:data` 既有 `:settings` 依赖读取 `AppPreferences.offlineMode`，并仅为
`CatalogReadPort` 提供选择绑定；不得再次 `@Provides` 两个 repository implementation，也不得
直接 `new` / 手工构造替代既有 singleton。core 的
`RepositoryModule.provideJellyfinRepositoryImpl` /
`provideJellyfinRepositoryOfflineImpl` 保持原样。若 Hilt graph 无法在该数据模块内解析上述既有
singleton，或必须修改 core DI、增加 Gradle 依赖或改用其它 builder，停止并报告 `BLOCKED`；
不得把绑定退回 core。

online search 必须使用由 `JellyfinOrigin` 解析出的 DB user/address/token 建立或复用
origin-bound client；不得在抓取 lambda 内再次读取当前 singleton `jellyfinApi.userId`。
materialization 的 base URL / access token 也必须来自该绑定，不得从可变 singleton
重新猜测。

进入 cache / disk / network 前必须验证
`MetadataFence.namespace == MetadataNamespace(origin.serverId, origin.userId.toString())`；
不等即 `IdentityUnavailable`，禁止 lookup、join、prefetch 或 write。抓取与 materialize
结束后还必须再次验证同一 `CatalogScope` 与 fence；任一变化按 fenced failure 丢弃。

#### 目标消费者与旧路径

| 目标消费者 | 当前旧路径 | 本切片目标 | 旧路径删除证据 |
|---|---|---|---|
| `modes/film/.../SearchViewModel.kt` | `JellyfinRepository.getSearchItems`；分别读 `AppPreferences.currentServer` 与 `repository.getUserId()` | 只注入 `CatalogReadPort`；用 `currentScope()` 开始 guard，用返回 snapshot 的 scope/generation 结束 guard | 生产搜索目录中无 `getSearchItems`、`getUserId()`、`AppPreferences.currentServer` |
| `modes/film/.../SearchSession.kt` | 本地 `SearchNamespace(serverId, userId)` | 直接使用 public `CatalogScope`；guard 同时检查 request generation、scope 与 snapshot content generation | 无 `data class SearchNamespace`、无第二份 server/user DTO |
| `JellyfinRepository.kt` | public `getSearchItems(query, scope)` | `CatalogReadPort.search(CatalogSearchQuery)` | interface / online / offline 均无 `getSearchItems` |
| `JellyfinRepositoryImpl.kt` | singleton-user search | origin-bound `CatalogReadPort` | 旧 override 删除，port 是唯一生产搜索入口 |
| `JellyfinRepositoryOfflineImpl.kt` | server-only Room search，忽略 scope | scope-filtered offline search | 旧 override 删除；`MUSIC/BOOK` 当前离线索引不可表达时返回空，不跨域借用视频 |

明确留在后续子波、不得在本切片顺手改的消费者：

- `MetadataNamespace` 的其他用户：`ImageCacheRules`、`ImageCacheStore`、`OfflineMediaRepository`、
  `MusicRepository`、`ServerPlaylistCardCache`、download / image worker 与既有测试。
- `SetupRepositoryImpl`、`ServersViewModel`、`AddServerViewModel`、`ApiModule` 的账号 / server /
  preference / auth 写入顺序。
- `ReaderRepositoryImpl`、`JellyfinRepository` 其余元数据方法、Downloader / Worker、
  Playback、User-state write、导航与全部公共 UI。

这表示 `ICS-W1A` 完成后父 seam 仍有双表示与身份写入风险；不得把搜索单路径迁移写成
全仓 Identity/Catalog 完成。

#### 首个实现精确文件白名单

源码（8）：

1. `data/src/main/java/com/zhangwenkang/cinefin/repository/catalog/CatalogReadPort.kt`（新建）
2. `data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepository.kt`
3. `data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryImpl.kt`
4. `data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryOfflineImpl.kt`
5. `data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotSwr.kt`
6. `data/src/main/java/com/zhangwenkang/cinefin/di/CatalogReadModule.kt`（新建）
7. `modes/film/src/main/java/com/zhangwenkang/cinefin/film/presentation/search/SearchSession.kt`
8. `modes/film/src/main/java/com/zhangwenkang/cinefin/film/presentation/search/SearchViewModel.kt`

对应测试（6）：

1. `data/src/test/java/com/zhangwenkang/cinefin/repository/CatalogReadPortContractTest.kt`（新建）
2. `data/src/test/java/com/zhangwenkang/cinefin/repository/JellyfinRepositorySearchItemsDispatcherTest.kt`
3. `data/src/test/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotSwrTest.kt`
4. `modes/film/src/test/java/com/zhangwenkang/cinefin/film/presentation/search/SearchSessionTest.kt`
5. `modes/film/src/test/java/com/zhangwenkang/cinefin/film/presentation/search/SearchCatalogConsumerTest.kt`（新建）
6. `app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/search/SearchViewModelTest.kt`

白名单外即停止。尤其禁止修改 `settings.gradle.kts`、根 build、依赖目录、
`NavigationRoot.kt`、`CinefinCard.kt`、theme/token、Room schema、Downloader / Worker、
`AppPreferences.kt`、`JellyfinApi.kt`、`ApiModule.kt`、
`core/src/main/java/com/zhangwenkang/cinefin/di/RepositoryModule.kt`、
`SetupRepositoryImpl.kt`、`SearchQueryRules.kt` 或其他 `MetadataNamespace` 消费者。

#### 八状态 DoD 与父 seam 边界

父 seam 的八状态是整条 Identity/Catalog 的完成门，不是首个切片的自动完成声明：

| 状态 | 父 seam 通过条件 | 首个切片 `ICS-W1A` 的边界 |
|---|---|---|
| `CONTRACT_FROZEN` | root 已接受本卡、FQCN、字段、白名单与停止门 | 已接受；W1A QA FAIL 后修复中，父 seam 不得前进 |
| `ADAPTER_IMPLEMENTED` | online / offline adapter 与 read-side scope/generation 检查落地 | 可形成 slice-local 证据，不能覆盖 auth / settings 写入与其他 catalog consumers |
| `TARGET_CONSUMERS_MIGRATED` | auth / preference / DB namespace / cache 与全部声明目录消费者迁移 | 本切片只迁移 SearchViewModel / SearchSession |
| `OLD_PATH_REMOVED_OR_NO_DUAL_TRUTH` | 全局旧身份推断、旧搜索路径与跨账号双真源移除 | 本切片只要求删除 search 旧路径；其他旧路径仍存在 |
| `CONTRACT/NEGATIVE/REGRESSION TESTS PASS` | 全 seam 合同、负例、回归均在同一候选执行 | 本切片只允许 `SLICE_W1A_TESTS_PASS` 局部标签 |
| `DEBT_COUNT_DECREASED` | A0 debt ratchet 证明目标违规真实下降 | 本切片预期不改变 11 违规计数，不能通过此状态 |
| `INDEPENDENT_QA PASS` | 与实现者不同的全新 QA 在候选包执行 | 本任务与首个实现 owner 均不得自派 QA |
| `INTEGRATED_GATE PASS` | 另一个 merge-prep / integrated owner 证明候选祖先与集成门 | 本切片不得进入 merge-prep |

首个切片只计划推进到：

```text
SLICE_W1A = CONTRACT_FROZEN (root review 后)
          -> ADAPTER_IMPLEMENTED
          -> SEARCH_CONSUMERS_MIGRATED
          -> SEARCH_OLD_PATH_REMOVED
          -> SLICE_W1A_TESTS_PASS
```

父 seam 在首个切片后最多保持 `IN_PROGRESS / CONTRACT_FROZEN`，不得据 slice-local 测试
推进到完整 `TARGET_CONSUMERS_MIGRATED`、`DEBT_COUNT_DECREASED`、QA 或集成 PASS。

#### Independent QA FAIL（2026-11-07；repair required）

candidate `693eff11dd8cf43837ac090770cc6f3b37bace8b` 由不同全新
`AURORAMA_V2_IDENTITY_CATALOG_W1A_INDEPENDENT_QA_OWNER_2026_11_07` 独立执行，结果为
`FAIL / P0-P3=0/1/3/0`，禁止 merge-prep / integration：

- `P1-01`：`SearchCatalogGuard` 对 `requested=null && current=null` 误判为合法，随后
  snapshot=null 也放行，导致无身份时 local/download 结果仍可能 emit；必须全链路
  `IDENTITY_UNAVAILABLE` fail-closed。
- `P2-01`：请求开始时解析的 frozen `CatalogScope` 未传给 `CatalogReadPort.search`；data 层
  执行时重新解析，切号竞态可为新 scope 抓取/写 cache。必须把冻结 scope 绑定到 query/port，
  并在读取、抓取、materialize、cache write 前再次校验。
- `P2-02`：`DownloadSearchRules` 对未知/缺失 provenance 或无法解析 userId 的记录 fail-open；
  必须隔离未知记录，不能猜测属于当前 scope。
- `P2-03`：online scope 只验证 server/user，不验证 token / address；空 token 仍可绑定
  client 并发起请求。必须在 scope resolve 或 adapter 入口显式 `IdentityUnavailable`，
  不得匿名回退。

独立 QA 同时实测 build/unit/A0 全绿，这不抵消语义 P1/P2。修复必须由另一个全新
repair owner 在同一 8 源码 + 6 测试白名单内完成；旧实现 owner / QA owner 均不得复用。

repair chain 已形成：`4da3a981`（四项 patch + 首次 harness BLOCKED）→
`0bb5c720`（fake 自递归修复）→ `012efb9c`（3 个 data test 机械格式）→
`e05ba14c`（`SearchSession.kt` 机械格式）。`e05ba14c` 自身报告 data/film/app
`296/153/414` 全绿、assemble、root ktfmt、A0 7/7 与 direct guard PASS；但这些尚未由独立
QA 复核，当时状态为 `SLICE_W1A_QA_REPAIR_CANDIDATE_PENDING_FRESH_QA`（已被后续 R2/R3
结果取代）。

fresh QA R2 随后对 `e05ba14c` 独立重跑同一完整门并全部 PASS，关闭首轮 null-scope、
unknown provenance、blank credential 三项；仍判
`INDEPENDENT_QA_R2=FAIL / P0-P3=0/0/1/0`：`SearchViewModel` 同 query 重触发时连续两次读取
`currentScope()`，随后 completed-request 早返回只比较第一次 `requestedScope`，若 A→B 切号
发生在两次读取之间，可保留 A 的旧结果并绕过 data/consumer 检查。修复必须把 completed-request
判断放到已通过 non-null scope guard 之后，或用等价的第二次 scope 结果共同验证；不得只改测试。

fresh QA R3 对 scope-race repair `120a011a` 独立重跑 data `296/296`、film `153/153`、
app `416/416`、assemble、root ktfmt、A0 7/7 与 direct guard，判
`INDEPENDENT_QA_R3=PASS / P0-P3=0/0/0/3`。P3 cleanup 必须保留：

- `W1A-P3-COMPLETED-MARKER`：A 请求经历一次跨 scope 失败重触发后，completed marker 未清除；
  scope 回到 A 再同 query 时可能 early-return 保留失败态。无跨账号泄漏，`Retry(force)` 可恢复。
- `W1A-P3-LOCAL-ONLY-REGRESSION`：server-only failure 保留 local 结果的既有显式测试被替换，
  当前缺少直接单测；实现路径仍在。
- `W1A-P3-DOC-STATE-SYNC`：ACTIVE 后段/§0.10 的旧 QA FAIL 文案已在本轮修正；后续每次
  QA 状态变化必须同步所有当前态指针。

W1A 可进入独立 merge-prep，但不得把 P3 cleanup 写成已关闭；父 seam 仍不得因 W1A PASS
进入完整 `TARGET_CONSUMERS_MIGRATED`、`DEBT_COUNT_DECREASED` 或最终 seam PASS。

#### 测试命令、层级与负例

实现 owner 从自身 clean implementation worktree 以 `pwsh` 执行；`$repoRoot` 必须是该
worktree 的绝对路径：

```powershell
$repoRoot = '<implementation-worktree-absolute-path>'
$env:JAVA_HOME = 'F:\Develop\Android\Android Studio\jbr'
& "$repoRoot\gradlew.bat" :data:testDebugUnitTest `
  --tests 'com.zhangwenkang.cinefin.repository.CatalogReadPortContractTest' `
  --tests 'com.zhangwenkang.cinefin.repository.JellyfinRepositorySearchItemsDispatcherTest' `
  --tests 'com.zhangwenkang.cinefin.repository.MetadataSnapshotSwrTest' `
  --console=plain
& "$repoRoot\gradlew.bat" :modes:film:testDebugUnitTest `
  --tests 'com.zhangwenkang.cinefin.film.presentation.search.SearchSessionTest' `
  --tests 'com.zhangwenkang.cinefin.film.presentation.search.SearchCatalogConsumerTest' `
  --console=plain
& "$repoRoot\gradlew.bat" :app:phone:testDebugUnitTest `
  --tests 'com.zhangwenkang.cinefin.presentation.search.SearchViewModelTest' `
  --console=plain
& "$repoRoot\gradlew.bat" :data:testDebugUnitTest :modes:film:testDebugUnitTest `
  :app:phone:testDebugUnitTest :app:phone:assembleDebug --console=plain
pwsh -NoProfile -File "$repoRoot\tools\architecture\v2-owner-gate.ps1" -Root $repoRoot
```

测试层级：

- contract / pure rules：scope 校验、query 形状、freshness 枚举、`IdentityUnavailable`。
- adapter unit：online cache / snapshot / SWR 复用与 stale marker；offline scope 过滤。
- DI / Hilt：`CatalogReadModule` 的选择行为与绑定测试归 `:data`；使用 6 个 data / film /
  app 测试文件，至少覆盖 offline preference 的 true / false 选择，并由
  `:app:phone:assembleDebug` 证明 Hilt graph 可在不修改 `core/RepositoryModule.kt` 的情况下
  完成。不得新增第六个测试文件，也不得在 core DI 中塞入测试 seam。
- consumer unit：`SearchViewModel` / `SearchSessionGuard` 只接受返回 snapshot 的同一 scope。
- regression：既有搜索 dispatcher、snapshot corruption / mismatch、repo build 与 A0 guard。
- 不运行 ADB、设备、网络、生产服务器、产品 QA、runtime 或 Release。

首个切片必测负例：

1. 切号：A 请求在 B 成为当前 scope 后不得提交、不得写入 A/B cache，也不得由 consumer emit。
2. server / user 不同：同一 term/scope 在 server A/user A、server A/user B、server B/user A
   三组中 key 与 snapshot 均不同；不得仅按 term 命中。
3. cache generation：用户失效/内容刷新后，旧 generation 的 fetch 完成不得覆盖新值。
4. stale snapshot：当前 scope 的 disk snapshot 过 soft TTL 时返回 `STALE`，后台只启动一个
   刷新；刷新失败不把 stale 改成 fresh。
5. 离线 / 无身份：saved offline scope 只读该 server/user；缺 server、user 或 origin 时
   `IdentityUnavailable`；离线 `MUSIC/BOOK` 不可表达时返回空，不借用视频结果。
6. 并发：同 scope/key 单飞；不同 scope/key 不共享 deferred，旧请求 finish 不删除新请求。
7. 损坏 / 错身份：既有 `MetadataSnapshotStoreTest` 的 schema、checksum、server/user mismatch
   保持 PASS；跨账号 payload 只 miss / quarantine，不投影。
8. 权限：本切片只证明身份变化后 fail-closed；401/403 stale-if-error 明确未覆盖，必须由
   `ICS-W1E` 补，不得写 permission PASS。

#### 架构债务预期

- 预期定性下降：`SearchViewModel` 不再分别依赖 settings 身份字段与宽
  `JellyfinRepository` 搜索；旧 `getSearchItems` search path 删除；搜索读写只在
  `CatalogReadPort` 一个入口。
- A0 预期：`coreToDataImports=130` 与 11 违规 P1/P2/P3=`7/3/1` 均保持不变。
  `CatalogReadModule` 必须留在 `:data`，`core/RepositoryModule.kt` 不得新增 import；
  不得用 FQCN inline import 规避扫描，不得修改 `tools/architecture/v2-owner-baseline.json`
  或制造 baseline 下降。本切片不得声称 `DEBT_COUNT_DECREASED`。
- `ARCH_IMPLEMENTATION_REFACTORED` 在完整父 seam QA / integration 前保持
  `NOT_PROVEN`。

#### 风险、回滚与停止条件

- 风险 1：server / user / token / preferences 当前分步写入。本切片用 canonical DB
  current user + origin-bound client + read-time revalidation 限制串号，但不修写入原子性；
  若实现需要改切换写入顺序，停止并转 `ICS-W1B`。
- 风险 2：`JellyfinApi` singleton 仍被其他消费者使用。搜索 materialization 必须使用
  origin-bound client / scoped view；不得临时改写 singleton 再“恢复”。
- 风险 3：disk snapshot 没有 principal context / durable fence。在线搜索只按已验证
  online scope 使用；离线不读该 disk snapshot。跨进程与 durable 语义继续 Open。
- 风险 4：stale marker 在首个切片只到 consumer 边界，不新增 UI badge；不得写 BASE-003
  runtime / product PASS。
- 回滚：删除新 `CatalogReadModule` 与 port 文件、恢复 `getSearchItems` 与 consumer 注入；
  `core/RepositoryModule.kt` 仍保持原样，不涉及 schema、磁盘 envelope 或 build，现有
  snapshot 文件无需迁移。任何已写 cache 失败现场只按既有 namespace 删除/隔离，不跨账号复用。
- 立即停止：dirty / branch / HEAD 不符；root review 未批准；白名单越界；需要改 auth /
  preferences / API singleton 写入顺序、DB schema、build、Gradle、导航、公共 UI；
  `core/RepositoryModule.kt` 需要改动；
  需要设备 / 网络服务器 / QA / merge-prep；发现第二个生产搜索消费者；保留双搜索路径；
  用 FQCN inline import 规避 owner 扫描、修改 A0 baseline，或无法用 fixture 证明切号后旧
  结果不可提交。
- 工具失败收敛：本地测试 harness 的已定位单点问题最多一次修复后受控重试；第二轮仍 P1
  即停止，不扩 scanner / exact-count / 新 harness。

#### 后续子波（父 seam 不得被首个切片误判完成）

| 子波 | 交付物 | 前置 |
|---|---|---|
| `ICS-W1B-IDENTITY-INGRESS-SINGLE-WRITER` | auth / preferences / server / user 切换的单 writer、启动 hydration、identity generation 与失效时机 | `ICS-W1A` 完成并 review |
| `ICS-W1C-CATALOG-READERS` | views / items / detail / home / paging 等 catalog readers 共用 canonical scope 与 snapshot | `ICS-W1B` |
| `ICS-W1D-OFFLINE-NAMESPACE` | saved offline account、无账号、image cache / DB namespace / offline projection 收敛 | `ICS-W1B` |
| `ICS-W1E-FRESHNESS-PERMISSIONS` | 完整 freshness / stale reason、401/403、durable fence、跨进程与损坏策略 | `ICS-W1A`–`W1D` |
| `ICS-W1F-TARGET-MIGRATION-DEBT` | 所有声明消费者迁移、旧路径删除、debt ratchet 实际下降、回归 | `ICS-W1C`–`W1E` |
| `ICS-W1G-FULL-SEAM-CLOSEOUT` | 独立 QA、integrated gate、父八状态证据闭环 | `ICS-W1F`；root 另派 QA / merge-prep |

#### 同一切片禁止双路径

- `CatalogReadPort` 是 search 的唯一生产入口；不得在新 port 内调用旧
  `getSearchItems`，也不得保留 deprecated fallback。
- `SearchViewModel` 完成后不得 import `JellyfinRepository` 或
  `AppPreferences.currentServer`。只允许 import public catalog port / DTO、本地搜索与
  downloader 的既有关注点。
- `SearchNamespace` 删除；不得保留与 `CatalogScope` 等价的数据类或字符串拼接 key。
- search cache / snapshot 仍使用既有 `MetadataCache` 与 `MetadataSnapshotStore`；
  不得新增第二套 search cache、第二套 snapshot store 或“先查旧缓存再查新 port”。
- 证明命令必须在实现候选执行：

```powershell
rg -n 'getSearchItems|class SearchNamespace|AppPreferences|getUserId\(' `
  "$repoRoot\modes\film\src\main\java\com\zhangwenkang\cinefin\film\presentation\search"
rg -n 'getSearchItems' "$repoRoot\data\src\main" "$repoRoot\modes" "$repoRoot\app"
$baseSha = '<root-pinned-integration-sha>'
$allowedFiles = @(
  'data/src/main/java/com/zhangwenkang/cinefin/di/CatalogReadModule.kt',
  'data/src/main/java/com/zhangwenkang/cinefin/repository/catalog/CatalogReadPort.kt',
  'data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepository.kt',
  'data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryImpl.kt',
  'data/src/main/java/com/zhangwenkang/cinefin/repository/JellyfinRepositoryOfflineImpl.kt',
  'data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotSwr.kt',
  'data/src/test/java/com/zhangwenkang/cinefin/repository/CatalogReadPortContractTest.kt',
  'data/src/test/java/com/zhangwenkang/cinefin/repository/JellyfinRepositorySearchItemsDispatcherTest.kt',
  'data/src/test/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotSwrTest.kt',
  'modes/film/src/main/java/com/zhangwenkang/cinefin/film/presentation/search/SearchSession.kt',
  'modes/film/src/main/java/com/zhangwenkang/cinefin/film/presentation/search/SearchViewModel.kt',
  'modes/film/src/test/java/com/zhangwenkang/cinefin/film/presentation/search/SearchCatalogConsumerTest.kt',
  'modes/film/src/test/java/com/zhangwenkang/cinefin/film/presentation/search/SearchSessionTest.kt',
  'app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/search/SearchViewModelTest.kt'
)
$changedFiles = @(git -C $repoRoot diff --name-only "$baseSha...HEAD")
$unexpectedFiles = @($changedFiles | Where-Object { $_ -notin $allowedFiles })
if ($changedFiles.Count -eq 0 -or $unexpectedFiles.Count -gt 0) {
  throw "changed-file whitelist failed: $($unexpectedFiles -join ', ')"
}
if ($allowedFiles -contains 'core/src/main/java/com/zhangwenkang/cinefin/di/RepositoryModule.kt') {
  throw 'core RepositoryModule must remain outside the W1A write scope'
}
if ($allowedFiles -notcontains 'data/src/main/java/com/zhangwenkang/cinefin/di/CatalogReadModule.kt') {
  throw 'data CatalogReadModule is missing from the W1A write scope'
}
git -C $repoRoot diff --check
```

前两条预期无命中；若保留 test helper 名称，也必须不以生产方式暴露。

#### 首个 implementation owner 执行门

- 专业身份：全新
  `AURORAMA_V2_IDENTITY_CATALOG_W1A_IMPLEMENTATION_OWNER_2026_11_07`
- 首条只读动作：`git status --short`；随后 `git branch --show-current`、
  `git rev-parse HEAD`；必须 clean 且等于 root 派单钉住的已集成 SHA。
- 精确读序：
  1. `AGENTS.md` 顶部当前硬门、R035、负责人退出契约；
  2. 本任务卡与 Architecture Critical Path / `V2-NON-TTS-W1` 行；
  3. `docs/V2_PLAN.md` §10、§12 ACTIVE、§13 最新相关日志；
  4. `docs/ARCHITECTURE.md` §0.5–§0.8、§0.10；
  5. `docs/DECISIONS.md` `V2-D213`、`docs/PROJECT_RULES.md` `R035`、
     `docs/LESSONS.md` `L041`；
  6. `G1-S3/contracts.json` 搜索/SWR局部；
     `G1-BASE-001-SEARCH-ROUTE-CONTRACT/scope-query-matrix.json`、
     `stable-identity-matrix.json`；
     `G1-BASE-003-PERSISTENT-SWR/cache-identity-matrix.json`、
     `freshness-state-machine.json`；
  7. 白名单内 8 个源码与 5 个测试现场；graphify 仅辅助，源码/Git 为事实。
- 首动作后先写 RED 合同测试，再实现最小 GREEN；不得先重构宽仓库。
- 写域：仅上面 8 源码 + 6 测试文件。`docs/TASKS.md` / `V2_PLAN.md` /
  `ARCHITECTURE.md` 只按实现报告规则回写，不由实现 owner 自行扩卡。
- 必须执行上面的 data / film 单测、全量相关单测、`:app:phone:assembleDebug`、
  `git diff --check`、changed-file 白名单与旧 token 检查。
- 停止：任何白名单越界、root review 未 PASS、需要 schema / build / auth 写入 /
  导航 / 公共 UI / 设备 / 网络服务器 / QA / merge-prep，或无法证明切号后旧结果不提交。
- 收口：最多写 `SLICE_W1A_TESTS_PASS` 与父 seam `IN_PROGRESS`；不得自行开启独立 QA，
  不得进入 merge-prep，不得宣告父 seam 收口。

## HISTORY — G2 / TTS 控制面失败现场（非当前入口）

> **HISTORY（2026-11-07 前）**：以下旧 `PLAN_A_STATE_MODEL_REVIEW_PASS /
> SETTLEMENT_COMMIT=241b8b356e2aab4d1ef43016639dc0203eb10f7d /
> CLEAN_PREFLIGHT_PASS`。下方 `PENDING_FRESH_REVIEW` 文案只作 pre-review 证据。
> 用户已选择方案 A：receipt / contract / policy / schema 的 `currentTransitions=64`
> 是不可变 audit-time snapshot；root settlement 提交后的 canonical preflight 可记录不同
> live transition count（已实测 65），该值只观测、不与 64 比较。fresh review 已 PASS，
> settlement commit `241b8b35` 已 clean，canonical preflight 已 PASS；Pad QA 具备派发条件
> 但尚未派发。其他 live 对象漂移仍 fail-closed。产品 P0–P3 仍 `NOT_ASSESSED`，K60 锁定。

> 当前覆盖状态（2026-11-07）：`PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA /
> AUDIT_RECEIPT_PASS / qaDispatchable=true /
> activePrompt=G2_PAD_QA_OWNER_2026_11_06 /
> PAD_QA_PROMPT_DESCRIPTOR_READY_PENDING_FRESH_REVIEW / PAD_QA_NOT_EXECUTED /
> WIP=PLAN_A_STATE_MODEL_REDESIGN_PATCHED_PENDING_FRESH_REVIEW /
> AUDIT_SNAPSHOT_CURRENT_TRANSITIONS_EXACT_64 /
> LIVE_PREFLIGHT_TRANSITIONS=OBSERVED_NOT_RECEIPT_BOUND /
> auditedCommit=96e19ca2606d851ecbe1c4ed15188767207f9f64 /
> HISTORICAL_OUTER_HANDOFF=RUNTIME_QA_DEFERRED_TO_G2 /
> G1_STATIC_CLOSURE_IN_PROGRESS / QA_V9_BLOCKED_TOOL_NONCONVERGENCE /
> PRODUCT_P0_P3_NOT_ASSESSED / K60_GATED_LOCKED`。
> `V2-D208` 的 runtime 延期与静态收口语义仍有效；历史 `POST_HANDOFF_AUDIT_READY`、
> exact `auditReceipt` 与 qa-v9 证据原样保留；
> 当前设备锁只要求 `RELEASED_ACTIVE_0`。
> `HANDOFF_BOOTSTRAP_V2` 的 `POST_QA_RUNTIME_DEFERRED` 与 canonical preflight emitted
> `NEXT_PROMPT_BEGIN` 是审计时历史措辞；preflight exit / PASS 仍是机械门，settlement 提交
> clean 且 preflight PASS 后，实际派发源是 G2 `contract.authorization.qaDispatch.promptText`。

> QA-v9 实际执行为 `CORE_STEPS_1 / PAD_10_MIN_PARTIAL_TOOL_BLOCKED`：仅 `zf_001` 有真实
> AudioTrack start partial；`zm_010`、完整 10 分钟、gap、完整通知/focus、零网络与 K60 均未执行。
> cleanup / restore PASS、锁 `ACTIVE=0`，K60 `8e875894` 未登记。settlement 已生成
> `G2_PAD_QA_OWNER_2026_11_06` 的 executable prompt，但尚未派发；`V2-D209` 已批准
> `G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS /
> K60_GATED_LOCKED`。全新
> `G2_RUNTIME_CONTRACT_OWNER_AURORAMA_2026_11_06` 已建立独立合同根、候选身份、13 个产品门、
> report schema 与合同校验器；没有执行 QA、adb、设备、网络、产品实现、Release 或服务器写。
> 用户以 `V2-D210` 确认下一步；首次 Pad QA 在 collector Validate 的路径二次绑定处停止，
> 窗口未开始、候选未安装、锁已释放、K60 零接触。全新 repair owner 已修复路径与 cleanup
> 假阳性；第二轮 repair owner 又用 TEMP RED 复现历史 `qa-pad-v1` 自持锁阻止新登记的缺陷，
> 将历史 `PRE_WINDOW_TOOL_BLOCK` 与 `qa-pad-v2` / `qa-k60-v2` 新 attempt 分离，并绑定外部
> handoff 锁、registration/session、finalizer released lock 与 K60 predecessor SHA。
> 该 WIP 已由 `G2_FINAL_AUDIT_OWNER_2026_11_06` 在 `96e19ca2` final audit PASS；
> R3–R9 的历史修复与 exact counts 已结算，不再继续扩展工具门。
> 本轮 compatibility repair 已把 collector / finalizer / cleanup 的硬编码状态收敛到
> `G2RuntimeStatePolicy.ps1`：历史 pre-audit 只允许 `Validate / StaticSelfTest`；
> 当前 `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA` 只有在严格
> `repair-audit-receipt.json` 下才开放运行时动作。实际 receipt 已创建并通过 validator。
> R6 又修复 JSON 字段集合大小写与重复属性绕过：字段集合改为 Ordinal 大小写敏感，
> `Status` / `G2ToolsCounts`、未知字段与重复 JSON 属性均拒绝；失败调用 / 窗口 / 计数
> 既有回归保留。R7 将 settlement policy 改为独立 exact constants（不可由 contract / receipt
> 抬高或提供），并给 contract 根、manifest、authorization、candidate、registration、
> session、device-lock 与 runtime report 补 Ordinal exact-property；同步 `111/151/109`
> 与根 `Status` / `Validation` 均已拒绝。R8 把 `currentTransitions` 固定为独立 exact `62`，
> contract expectation 与 receipt 两层及 schema `const` 同步；同步 `61`、单层 `61`、
> 缺失、null 与字符串均拒绝。2026-11-07 `G2_TRANSITION64_REPAIR_OWNER_2026_11_07`
> 将当前 oracle 原位刷新为 exact `64`，负例改为 `63` / 字符串 `"64"`；R8 的 `62` 只保留
> 为历史事实、不再是当前基线。R9 把 audited commit 收紧为最低基线
> `6b04eb300256fc8384e5407cee24907e8343f949` 的严格后继，相等 / parent / parent 漂移拒绝，
> 合法后继接受；future fixture 使用临时合成 Git object 的严格后继 provenance，候选 source
> commit 仍须为祖先，并新增 synchronized pure-high counts 负例。
> `Test-G2RuntimeTools` final audit 为 `116/0/188`，contract validator /
> collector Validate / 三份 StaticSelfTest PASS；settled QA-ready 兼容已由 audit PASS，
> 并已在真实 contract 中支持
> `generatedExecutableQaPrompt=true` 与具体 `authorization.qaDispatch`。Round-1 scoped review
> 的 P1-01/P1-02 已由方案 A 端到端修补：历史 emitted prompt /
> `QA_DISPATCHABLE=DEFERRED_TO_G2` / `activePrompt=none` 只作 outer-handoff anchor；
> audited `head/headExpectation` 只是 source/provenance binding，QA live HEAD 必须来自
> clean settlement 后的 canonical preflight。audit snapshot `currentTransitions=64` 与
> settlement 后 live observation 分离。新 prompt 文本 SHA-256 绑定 contract / receipt / policy
> `5216b3d0e401aa9a67136762e155505a636300ccb27c715abe5e06a1a29d7172`。真实机器合同仍为
> `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA`；receipt 新 SHA
> `c7d9005143231e6cc2661c9476380d4194f8d6dab90fdd8d61c5e8e9c3219a9d`，contract 新 SHA
> `70d3b869cfb33f34f372c7de9e13cb5198f5e8502dc6c07383bb559e9f91bad0`。
> Pad QA prompt 已生成但尚未派发；下一步仅 fresh reviewer，review PASS 后 root 才可提交。
> 完整 handoff 的 final audit 为 `108/0 + TEMP_CLEANUP=PASS`；历史
> `104/104 PASS` 只是过去快照。当前不是 QA / runtime / G1 PASS，延期不是 `PASS_WITH_GAPS`。
> Plan A patch 的 contract validator 已执行 `1 run / exit 0 / PASS`，
> `TOOL_STATE_MODE=AUDIT_PASS_PENDING_FRESH_PAD_QA`；该结果不是 fresh review PASS，也不授权 QA。
>
> 历史 `ROUND9_READONLY_INTEGRATION_REVIEW_OWNER`、
> `ROUND8_READONLY_INTEGRATION_REVIEW_OWNER`、`ROUND7-P1-01`、`ROUND6-P1-01` 与 B10
> `86/86`、B11 `96/96` 回归只作退役证据保留，不得复活为当前执行角色或 PASS；当前
> settlement 历史回归 `104/104 PASS + TEMP_CLEANUP=PASS` 只作历史快照；D209 只授权全新
> `G2_RUNTIME_CONTRACT_OWNER` 已只建立 runtime 合同；这仍不等于 QA、设备、G2 实现或
> Release 已授权。

| ID | 需求域 / 关系 | 目标 | 非目标（≥2） | 依赖 | 验收判据 | 停止条件 |
|---|---|---|---|---|---|---|
| `V2-SOLUTION-BASELINE / V2-G2-ENTRY-CONTRACT` | 方案 A 已重设计 audit-snapshot / live-transition 语义并完成一次静态验证，等待 fresh review 与 root 提交 | 保持 audited source/provenance commit `96e19ca2606d851ecbe1c4ed15188767207f9f64`、修订后 receipt `c7d9005143231e6cc2661c9476380d4194f8d6dab90fdd8d61c5e8e9c3219a9d`、Pad QA descriptor 与新旧文档单一真源；fresh review PASS、root commit clean、canonical preflight PASS 后才可派全新 Pad QA | ①不执行 QA、adb、Register/RunWindow、设备或 K60；②不改工具、测试、schema、policy、候选、授权对象、`qa-pad-v1`、handoff 合同或 preflight；③不把 audit settlement / review 写成 QA/runtime/G1 PASS；④不继续扩工具门或协议 | `V2-D210`；audit owner `G2_FINAL_AUDIT_OWNER_2026_11_06`；audited commit `96e19ca2` / parent `ebe5dc59`；G2 tools `116/0/188`；handoff `108/0 + TEMP_CLEANUP=PASS`；audit snapshot transitions `64`；settlement 后 live transitions `OBSERVED_NOT_RECEIPT_BOUND`（当前推导 `65`）；manifest `11/11`；external lock `RELEASED_ACTIVE_0`；contract `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA / qaDispatchable=true / activePrompt=G2_PAD_QA_OWNER_2026_11_06`；prompt SHA `5216b3d0e401aa9a67136762e155505a636300ccb27c715abe5e06a1a29d7172` | Plan A patch contract validator `1 run / PASS / exit 0`，`TOOL_STATE_MODE=AUDIT_PASS_PENDING_FRESH_PAD_QA`；contract / receipt / policy prompt SHA 三方一致；receipt/contract 的 64 不随 settlement 改写，live transition count 只记录、不比 64；`head/headExpectation` 仅 audited source/provenance binding，QA live HEAD 取 clean settlement 后 canonical preflight 现场值；未执行 QA，产品 P0–P3 `NOT_ASSESSED`。下一步 fresh review，首动作 `git status --short`、`git diff --stat`；review PASS 后 root 才提交，clean preflight 后才可派 Pad QA | HEAD / audit commit / 非 transition live 对象 / tools digest / prompt hash / candidate / qa-pad-v1 / lock 任一漂移；live transition count 与 audit snapshot 不等本身不是停止条件；review 发现工具、schema、候选或授权必须改变；设备动作；Memory 写入越界；fresh review FAIL |
| `V2-G1-L1-EVIDENCE` | G1 L1 证据与静态收口 | 保留 qa-v8/qa-v9 历史证据与全部 fail-closed 回归，只允许补可离线复核的 G1 静态缺口 / G2 planning 输入 | ①不下载 MOSS、不触发新网络域；②不关闭 Q03、不冻结 profile / voice、不接受 Pad P2；③不用旧 30 分钟、stop-only、安装、cleanup、partial AudioTrack 或工具阻塞替代产品证据 | main `427,009,671 B / 252a3a15f637b05e874d9b2ad2aa0c48076d2babb83f66c5ec18683dc533e161`；peer `12,696 B / 61c79aa1b56b9fc921ce23199394d342f4badad31389b842f38c1d5c3a4bebab`；tools 31/2984/digest `69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4`；QA-v8 tools 冻结 | `qa-v9/report.json` 为 `BLOCKED_TOOL_NONCONVERGENCE / BLOCKED / PRODUCT_P0_P3_NOT_ASSESSED`；`CORE_STEPS_1 / PAD_10_MIN_PARTIAL_TOOL_BLOCKED / K60_LOCKED`；cleanup PASS；历史 `auditReceipt` 与 transitionPaths 保留，当前 post-QA 集合并列校验；ACTIVE=1 / TASKS=3 | 复用 QA / audit owner、改旧 report 或 raw 证据、digest / manifest / APK 漂移、证据根删除 / 移动、产品过度声明、设备 / 网络 / 生产写或越界时停止 |
| `V2-G1-L1-KOKORO-EXACT` | G1 L1→L3 scoped 子任务；runtime 部分延期 | 把完整 Pad/K60 10 分钟 runtime matrix 延期到 G2 入口门；G1 只保留 qa-v9 partial 证据和静态 planning 输入 | 不做 QA 重试、Pad/K60 设备动作、P2 接受、生产代码、Q03、G2 实现或 Release；不把 qa-v9 start-only、cleanup、旧 K60 v5 或 Pad v6/v7 结果扩写为完整门 | v5 输入基线、`qa-v9/report.json`、`qa-v8/report.json` 摘要、`qa-v8/tools` digest、G1-S6 checkpoint；`ACTIVE=1 / TASKS=3` | 历史 outer-handoff 证据仍记录 `POST_QA_RUNTIME_DEFERRED`、auditReceipt 历史 PASS、`QA_DISPATCHABLE=DEFERRED_TO_G2`、padQa retired / dispatchable=false；这些只作历史 anchor，从属于首行当前 G2 dispatch。K60 `8e875894` 未登记、锁 `ACTIVE=0`、产品 P0–P3 `NOT_ASSESSED` 事实不变 | QA / runtime PASS 过度声明、QA 重试、设备 / 首动作 / hash / path / owner / role writeScope 漂移、K60 提前、需产品代码或新范围决策时停止 |

## HISTORY — B7 POST mutation-scope 修复待 round5 的快照（已被 B9 取代）

| ID | 需求域 / 关系 | 目标 | 非目标（≥2） | 依赖 | 验收判据 | 停止条件 |
|---|---|---|---|---|---|---|
| `V2-SOLUTION-BASELINE` | G1 方案门；总括行，非执行卡 | 建立目标架构、模块依赖、数据模型 / 迁移、协议契约、依赖许可、技术 Spike、UX 方向 / 低保真与验收矩阵，形成可确认的方案基线 | ①不启动生产 runtime / 服务器写 / 生产代码 / G2 / Release；②不把安装门、cleanup、实现自测、QA-v7 stop-only、QA-v8 工具阻塞、旧 30 分钟、S6 历史或 D207 试听接受写成 profile、Q03、runtime 或 G1 PASS | `V2-D202` 仅授权 L1；D203–D207 保持；状态 `POST_SCOPE_P1_REPAIRED_AWAITING_FRESH_ROUND5_REVIEW / validation.handoffPhase=PRE_HANDOFF_AUDIT / INDEPENDENT_INTEGRATION_REVIEW_PASS=BLOCKED / PRE_HANDOFF_AUDIT_PASS=BLOCKED / QA_DISPATCHABLE=BLOCKED / CORE_STEPS_0 / PAD_10_MIN_CORE_NOT_RUN / K60_NOT_REGISTERED_NOT_RUN / CLEANUP_PASS / PRODUCT_P0_P3_NOT_ASSESSED / DEVICE_LOCK_ACTIVE_0`；Q03 Open；L2/L3 locked | B7-RETRY 已为合法 POST 增加 source/current canonical `immutableStateSha256`、递归 JSON pointer allowlist 与未知字段负例；`ROUND4-P1-01` 三项绕过待 round5 复审，不写 audit / QA PASS。真实合同仍 PRE，padQa 不可派发。tools 31/31、manifest `2984 B`、digest `69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4`、QA tools `25/25`、self-test AST/UTF-8 31 保持；下一步唯一 fresh `ROUND5_READONLY_INTEGRATION_REVIEW_OWNER`；round5 PASS 后由 root 提交，再由另一全新 pre-handoff audit 产出 receipt | preflight / audit 非零、Git / ACTIVE / TASKS / memory / 设备锁 / next prompt 不一致或需写仓库、round4 / 既往 audit / 同代理复用、A/B 重叠、auditReceipt 缺失 / 未知 / hash / tree / transitionPaths 漂移、未知顶层字段 / 非白名单 pointer / immutable hash 漂移、QA 提前激活、窗口非 10 / 超时、K60 先行、核心未执行或失败、crash、证据不可复算、越权时停止 |
| `V2-G1-L1-EVIDENCE` | G1 `L1`；状态 `POST_SCOPE_P1_REPAIRED_AWAITING_FRESH_ROUND5_REVIEW / validation.handoffPhase=PRE_HANDOFF_AUDIT / PREFLIGHT_PASS / INDEPENDENT_INTEGRATION_REVIEW_PASS=BLOCKED / PRE_HANDOFF_AUDIT_PASS=BLOCKED / QA_DISPATCHABLE=BLOCKED / PAD_10_MIN_CORE_NOT_RUN / K60_NOT_REGISTERED_NOT_RUN / CORE_STEPS_0 / CLEANUP_PASS / PRODUCT_P0_P3_NOT_ASSESSED / PAD_P2_UNACCEPTED / KOKORO_LICENSE_PARTIAL` | 先由全新 `ROUND5_READONLY_INTEGRATION_REVIEW_OWNER` 复审 `ROUND4-P1-01` 的 immutable hash、递归 mutation allowlist 与 fail-closed 回归；round5 PASS、root 提交后由另一全新 pre-handoff audit 生成合法 receipt，再以全新 Pad / K60 QA 串行闭合 10 分钟 runtime、许可和 owner 证据 | ①不下载 MOSS、不复活 speaker58 / `zm_009`；②不触账号 / 服务器生产写 / 生产代码，不启动 G2 / Release；③不把旧 30 分钟、stop-only、安装、cleanup、旧 APK、工具自测、工具阻塞或 round5 review PASS 扩写成产品失败 / PASS / `PASS_WITH_GAPS` | main `427,009,671 B / 252a3a15f637b05e874d9b2ad2aa0c48076d2babb83f66c5ec18683dc533e161`；peer `12,696 B / 61c79aa1b56b9fc921ce23199394d342f4badad31389b842f38c1d5c3a4bebab`；tools 31 项 / `2984 B` / digest `69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4`；`c4a2d2a1` 已提交原 10 个 ignored 工具文件 | B7-RETRY 已把合法 POST 从“只看三路径”收紧为 source/current 对象 diff：三项原 P1 绕过、未知顶层字段、非白名单 pointer 与 immutable hash 漂移均 exit 2；合法 PRE / POST 保留。真实 audit / QA 仍 BLOCKED，samples / AudioTrack / PID=`0/0/0/none`，Pad 10 分钟核心与 K60 均未运行，产品 `NOT_ASSESSED`。下一动作仅 round5 review | 复用代理 / 旧证据、root 自证或亲跑设备、A/B 重叠、digest / manifest / 2984 B 未匹配、auditReceipt / immutable hash / 权限 / ABI / 锁漂移、unknown top-level / pointer、Git / ACTIVE / TASKS / memory / next prompt 不一致、需要写仓库、窗口延长、K60 先行、核心零执行或任一 core fail、crash、新 P0–P2、越权时停止 |
| `V2-G1-L1-KOKORO-EXACT` | G1 `L1→L3` scoped 子任务；状态 `POST_SCOPE_P1_REPAIRED_AWAITING_FRESH_ROUND5_REVIEW / RUNTIME_V5_BUILT / DEVICE_LOCK_ACTIVE_0 / CLEANUP_PASS / LICENSE_PARTIAL` | v5 main 为 `versionCode 4 / 2.0-kokoro-runtime-v5`；QA-v7 stop-only 与 QA-v8 工具门为历史事实；round1 / round2 findings 已关闭，B7-RETRY 已修复 POST 对象 mutation 绕过 | 不做 P2 接受、许可闭环、生产代码、Q03、G2、Release；stop-only、安装、cleanup、工具自测、round5 review PASS、旧 30 分钟不得扩写成新产品门；audit / QA 后必须写回证据与 docs | 输入基线与 APK SHA 见机器契约；`qa-v8/report.json`、cleanup-recovery、runtime implementation report 与 round5 evidence 为压缩入口；tools 31 项 / digest `69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4`，manifest `2984 B`，audit / QA 仍禁止 | B7-RETRY 已实现 `immutableStateSha256` 与 source/current 递归 pointer diff；真实合同仍 PRE，没有伪造 audit PASS。核心 0，产品 P0–P3 `NOT_ASSESSED`；最终 cleanup 12/12 PASS 且生产包 MATCH。下一步仅 fresh `ROUND5_READONLY_INTEGRATION_REVIEW_OWNER`，后续 audit / Pad / K60 均另派 | 越界 A/B、复用 round4 / 既往 audit / B7 owner、Git / ACTIVE / TASKS / memory / 设备锁 / next prompt 不一致、需写仓库、改旧 report / 证据 / 生产、receipt / immutableState / digest / manifest / bytes 漂移、unknown pointer、QA 提前派发、窗口非 10、K60 先行、crash、新 P0–P2、MOSS / 新域、生产写、代签 Q03/G1/Release 时停止 |

## Review

- `V2-G1-L1-KOKORO-EXACT` Pad QA-v6 证据闭环复审：`PASS`（仅证据包修正），产品整体
  `FAIL / P1 REV6-P1-01`。初次报告误判 PASS；fresh reviewer 从
  `ApplicationExitInfo reason=5 APP CRASH(NATIVE), status=6` 与 ONNX / Sherpa `F DEBUG`
  抓到通知停止后的 native crash。QA 已撤回 PASS、删除未过滤宿主整日志、补齐 native crash
  gate；最终证据卫生修正后 71/71 SHA MATCH，网络键全部脱敏、完整锁副本最小化为摘要、
  `integrity-receipt.json` 固定清单/report/scan 哈希。30 分钟、零网络、清理保留为窄门 PASS，
  不得覆盖 P1。
- `V2-G1-L1-EVIDENCE`：离线证据与 network artifact 子项均有历史独立 review；Sherpa
  `PASS_WITH_P2`、PDFium `PASS` 不代表 D204 后当前状态已复核。P1 ORT notice / provenance、
  P2 source tag→commit 以及 PDFium source / notice / runtime 缺口不由此关闭；Q03 仍 Open。
- `V2-G1-L1-EVIDENCE` 本轮 fresh scoped evidence review：`CHANGES_REQUIRED`。已撤销未经
  exact manifest 复算的下载体积，Ram / 首音 / RTF 保持未验证；四候选的相对工程复杂度仅为
  hypothesis；`silence_scale` 是全局静区缩放，VITS 情感只能选模型已提供的情感槽。
- `V2-G1-L1-EVIDENCE` D205 候选复审：`CHANGES_REQUIRED` 后已修正为 Kokoro 先、MOSS 二、
  ZipVoice 许可 HOLD；MOSS 只能称 Android smoke，763MB 为两仓运行资产合计；Kokoro
  先前“4 条样音”口径须按 archive / 最新 tree 复验，当前只固定 `zf_001` sid3 /
  `zm_010` sid59，speaker58 / `zm_009` 继续否决；外部 TTS engine 的冲突是核心验收边界，
  不等于完全不可集成。
- `V2-G1-L1-EVIDENCE` D205 current docs/S6 revision fresh scoped review：`PASS / no findings`，
  P0–P3=`0/0/0/0`；3 个权威 docs + 12 个 G1-S6 派生文件范围、manifest / LF / verifier
  均通过。该 PASS 只表示静态文档与证据一致，不表示 G1、runtime、QA 或 Release PASS。

## Todo / Deferred

| ID | 需求域 | 目标 | 依赖 | 退出条件 |
|---|---|---|---|---|
| `V2-NON-TTS-W0-DOWNLOAD-CARD-ACTION`（`ISOLATED_PROBE / NOT_ARCHITECTURE_PROGRESS / READY_FOR_INDEPENDENT_IMPLEMENTATION_OWNER / CURRENT_REPRO_REQUIRED / NOT_STARTED`） | 下载完成项可达性；`V2-FIX-DOWNLOAD-001` / `L14-15` | 由 root 决定是否派全新 `ANDROID_DOWNLOAD_UI_IMPLEMENTATION_OWNER`；该 owner 从固定 clean integration SHA 建独立 worktree，首条只读动作 `git status --short` / `git rev-parse HEAD`，随后先复现当前对象；仅真实 RED 时最小修复 | `PREREQUISITE_SEAMS=NONE`；`ARCHITECTURE.md` v2.0；A0 guard integrated，但不作为 seam progress；D212 / R034；精确白名单仅 `DownloadRows.kt` + `DownloadRowAccessibilityContractTest.kt`；原 worktree 已建但未派，派单前必须复核 branch / HEAD / dirty | 当前对象有 RED 并在同一 lineage 修复，或 `NO_DEFECT_REPRODUCED`；无缺陷即停止；触碰导航 / 状态机 / 通知 / schema / 设备 / TTS/G2 或第三文件即停止；不得计作架构进度，不得挡 W1 seam |
| `V2-NON-TTS-W1`（`BLOCKED_BY_PREREQUISITES`） | 读路径信任与共享 UI 基座 | 搜索正 / 空 / 错 / 离线与返回恢复、持久 SWR、更新信任、R1 共享 UI 在 seam 迁移后形成可验证切片 | `PREREQUISITE_SEAMS=V2-ARCH-SEAM-IDENTITY-CATALOG-W1`；该 seam 达到 `CONSUMER_MIGRATED + TESTS_PASS + INTEGRATED` 前不得开工 | 前置 seam 完整 DoD + 独立 QA + integrated gate；本波自身真实入口 QA 另行授权 |
| `V2-NON-TTS-W2`（`BLOCKED_BY_PREREQUISITES`） | 下载真相、离线投影与迁移 | 由同一下载控制投影驱动 UI / 通知 / 文件 / sidecar / Room / 离线与恢复；不得以 W0 探针替代 | `PREREQUISITE_SEAMS=V2-ARCH-SEAM-IDENTITY-CATALOG-W1,V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2`；两项未到三项压缩门前不得开工 | 两项前置 seam 完整 DoD + 独立 QA + integrated gate；真实任务 / 暂停恢复取消删除 / 重启另授权 |
| `V2-NON-TTS-W3`（`BLOCKED_BY_PREREQUISITES`） | 音乐 / 视频连续性与身份收敛 | 在 seam 迁移后交付单活动播放会话、队列 / Next Up / 恢复、切号原子清场与本地歌单切片 | `PREREQUISITE_SEAMS=V2-ARCH-SEAM-IDENTITY-CATALOG-W1,V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2,V2-ARCH-SEAM-PLAYBACK-SESSION-W3`；前置门未全过时不得开工 | 三项前置 seam 完整 DoD + 独立 QA + integrated gate；不触 TTS |
| `V2-NON-TTS-W4`（`BLOCKED_BY_PREREQUISITES`） | 非 TTS 阅读核心切片 | 在 source-scoped identity / locator seam 迁移后交付 EPUB / CBZ / 现有 PDF、本地持久化与非 TTS 恢复切片 | `PREREQUISITE_SEAMS=V2-ARCH-SEAM-IDENTITY-CATALOG-W1,V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2,V2-ARCH-SEAM-READER-SOURCE-W4`；正文实现前必须全过 | 三项前置 seam 完整 DoD + 独立 QA + integrated gate；父 TTS promise 保持 `PARTIAL_NON_TTS / UNVERIFIED` |
| `V2-NON-TTS-W5`（`BLOCKED_BY_PREREQUISITES`） | 受控写操作 | 在唯一写网关与身份 seam 迁移后交付 intent / accepted / confirmed / denied、权限、回读、冲突与部分失败 | `PREREQUISITE_SEAMS=V2-ARCH-SEAM-IDENTITY-CATALOG-W1,V2-ARCH-SEAM-USER-WRITE-W5`；写功能派单前必须全过 | 两项前置 seam 完整 DoD + 独立 QA + integrated gate；独立测试写服务器与真实写 QA 另行授权 |
| `V2-TTS-G2-SPECIAL-SOLVE` | TTS / G2 runtime 后期专项（Deferred） | 在其他非 TTS 的 2.0 core 功能之后，以新任务、新方案、新验收解决离线 TTS、G2 runtime、handoff 与设备闭环；保留当前失败现场作为历史输入 | `V2-D211`；用户重新授权；新 owner / 新合同 / 新验收；旧分支 `codex/g2-prewindow-blocked-20261107` 与 G2 evidence 只读 | 新方案明确产品可观察承诺、最便宜可信证据、设备/安全/数据边界、两轮止损预算和恢复入口，并经用户批准；旧 QA attempt 不得作为 PASS，且不得复活 64/65 / exact-count / handoff 循环 |
| `V2-IMPLEMENTATION-PLAN`（`RETIRED / 2026-11-07`） | 历史 G2 开发门占位 | 当前由 `V2-D213` / `R035` 的长期 seam 账本、W1–W5 `PREREQUISITE_SEAMS` 与唯一架构主线入口取代 | 不再是当前派单门；不得据此重新询问用户确认已批准的架构方向 | 只作历史占位，未迁移到新账本前不得重新启用 |

## Done

| ID | 成果 | 证据 |
|---|---|---|
| `V2-ARCH-OWNER-GUARD-W1P` | A0 owner guard / debt ratchet 已集成；把 G1-S1 的 19 条内部边、8 条外部边、11 个违规基线与 static-input limits 固化为只读门禁 | source `a787b683c89389491099ea7acb069422190327c3`（parent `c56ffdc31b3d254e5993f5c4ca5e1d576d235620`）；旧 target `d6b431764e6a7d088ec4e8187af30b5b80223289`；integration `ab9174043db9cb9e637054210cfee580d06f2965`；source/final stable patch-id `eaf42d616966c3cea0b8274ec9e659075f6e48bd` MATCH；仅新增 `tools/architecture/v2-owner-baseline.json`、`v2-owner-gate.ps1`、`v2-owner-gate.tests.ps1`；独立 QA `PASS` 7/7，A0 findings P0–P3=`0/0/0/0`；merge-prep `PASS`；integration worktree 复跑 `passed=7 failed=0 total=7`；direct guard 内部边 19/19、外部边 8/8、cycles 0、FQCN ambiguity 0、debt-ratchet failure 0，既有 11 违规仍 P1/P2/P3=`7/3/1`；static-input limits 保持 Gradle include 字面量、顶层函数/属性非完整 FQCN、import owner 限制、无方法/字段级图；不证明 contracts seam / 消费者迁移 / 产品全量架构重构 / G1 / Q03 / 产品 QA / runtime / Release PASS |
| `V2-ARCH-CONFORMANCE-AND-PARALLELIZATION` | `ARCHITECTURE.md` 升级为 v2.0；完成 20 类矩阵、工作流、低耦合、逻辑 seam、守卫、共享文件锁、四组无交集并行候选与首卡复现门；用户于 2026-11-07 确认架构门 | `docs/ARCHITECTURE.md` §0；`docs/V2_PLAN.md` §12 ACTIVE、§13；`.planning/v2-g1/G1-S1/report.json`；架构门不开产品重构或 QA / Release PASS |
| `V2-CORE-NON-TTS-DELIVERY-PLANNING` | 非 TTS 2.0 六波、依赖、风险、可观察验收、文件面、TTS 排除边界与首张任务卡已规划；新增轻量历史修复 lineage 机制；未开始产品实现 / QA / 设备 / Release | `docs/V2_PLAN.md` §12 ACTIVE、§13；`docs/DECISIONS.md` `V2-D212`；`docs/PROJECT_RULES.md` `R034`；`docs/LESSONS.md` `L040`；Git 只读证据为 `v1.4.0` 目标 `7b69e531` 与 sampled fix/candidate 均进入当前集成分支 |
| `V2-P0-01` | 第零章、8 项根决策、资源边界、权威文档和 ADR 基线；基线提交 `ee7ff239` 已本地进入集成分支 | `docs/V2_PLAN.md`、`PROJECT_RULES`、`LESSONS`、`DECISIONS`、ADR-0003～0005 |
| `V2-REQ-ARCH` | 引言 + 第一章 72 条需求完成归一与分级：69 `MUST` / 3 `SHOULD` / 0 `LATER` / `0 OUT`；11 组决策全部确认 | 收口提交 `426e5c01`；`docs/V2_PLAN.md` §5.2、§12、§13；`DECISIONS` `V2-D010`～`D020`；ADR-0003/0004/0006 |
| `V2-REQ-UX` | 第二章 38 条最终为 `21 MUST / 3 SHOULD / 2 LATER / 12 OUT`；一级 / 二级页面、首页边界、五档响应式、七类状态、选择性图标词表、五主题约束和证据口径完成；用户于 2026-11-02 接受整包 | 建议包提交 `c22963b5`；`docs/V2_PLAN.md` §5.3.15～§5.3.17、§12、§13；`DECISIONS` `V2-D081`；`V2-Q-UX-60` 已关闭 |
| `V2-REQ-READER` | 外部第三章历史为 86 条：63 单级 `MUST` / 9 单级 `SHOULD` / 7 `LATER` / 3 `OUT` / 3 复合 / 1 流程，MVP 66；外部 source `86` 不变。`V2-D203` 追加 `READER-087～090` 四条项目来源 addendum 后，当前矩阵 90、单级 MUST 67、MVP 70；外部八域 source 仍 `445`、外部 MVP `376`，归一化 MVP `380`。新增 voice-pack 追加导入 / 删除回退、后台锁屏、单音频 owner / MediaSession 与全局 / 逐书音色选择 | `docs/V2_PLAN.md` §5.4.5～§5.4.10、§5.10、§5.11.12、§12、§13；`DECISIONS` `V2-D082`～`D098`、`D197`、`D203`；`TASKS` `V2-G1-L1-EVIDENCE` |
| `V2-REQ-WRITE` | 第四章 13 个 source ID 最终为 11 单级 `MUST` + 1 复合 `MUST/LATER` + 1 验收流程节点；其中 `WRITE-011` 是 `WRITE-005` 的证据别名，不作为第二个独立产品功能；用户级写 MVP、权限三态、写状态机、失败分类、危险确认、多服务器隔离和 `W-D`～`W-U` 证据口径完成；用户于 2026-11-03 接受全部推荐 | `docs/V2_PLAN.md` §5.5、§5.10、§12、§13；`DECISIONS` `V2-D099`～`D105`；`V2-Q-WRITE-01`～`06` 全部关闭 |
| `V2-REQ-MUSIC` | 第五章音乐 62 个 source ID 最终为 47 单级 `MUST` / 5 单级 `SHOULD` / 2 单级 `LATER` / 5 单级 `OUT` / 2 复合节点 / 1 流程节点；MVP 50。`MUSIC-051` 由 D197 后决归一为 `MUST/MUST`，`MUSIC-018` 的一次重试谓词由 D194 固定；`M-D`～`M-T` 证据与全部冲突处置完成 | 收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.6、§5.10、§12、§13；`DECISIONS` `V2-D106`～`D127`、`D194`、`D197`；`V2-Q-MUSIC-01`～`22` 与 `V2-Q-FINAL-01` 全部关闭 |
| `V2-REQ-DOWNLOAD` | 第二个第五章下载 68 节点最终为 62 单级 `MUST` / 2 单级 `SHOULD` / 0 单级 `LATER` / 1 单级 `OUT` / 2 复合节点 / 1 流程节点，MVP 为 64 个 MUST 子项；四类媒体、provenance、提示 / 存储、传输恢复、通知 / 离线库、迁移与 `D-D`～`D-T` 证据完成；用户于 2026-11-03 原话“接受整包” | 矩阵提交 `8cade229`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.7、§12、§13；`DECISIONS` `V2-D128`～`D146`；`V2-Q-DOWNLOAD-01`～`18` 与 `FINAL` 全部关闭 |
| `V2-REQ-VIDEO` | 第六章视频 97 节点最终为 86 单级 `MUST` / 2 单级 `SHOULD` / 5 单级 `LATER` / 1 单级 `OUT` / 2 复合节点 / 1 流程节点，MVP 为 88 个 MUST 子项；双内核、媒体能力、字幕、手势、SyncPlay、本地媒体、恢复、响应式、`V-*` 证据与 D1–D67 处置完成；用户于 2026-11-03 原话“整包接受” | 候选矩阵提交 `171030268b4d5401ee91ace30b35ae2206062c93`；最终确认交接 `01fdb329073597057a1928d552da1b6bbd29789b`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.8、§12、§13；`DECISIONS` `V2-D147`～`D180`；`V2-Q-VIDEO-01`～`33` 与 `FINAL` 全部关闭 |
| `V2-REQ-NAV` | 第七章 NAV 9 节点最终为 6 个单级产品节点 `4 MUST / 2 SHOULD / 0 LATER / 0 OUT` + 1 个复合 `MUST / MUST` + 2 个流程节点，MVP 为 6 个 MUST 子项；UX / HOME / RWD 去重、五条旧缺陷有主域映射、`N-*` 证据与 G1 待核验完成；用户于 2026-11-03 原话“整包确认” | 矩阵提交 `9170ce52`；收口提交以 Git 实况为准；`docs/V2_PLAN.md` §5.9、§12、§13；`DECISIONS` `V2-D181`～`D193`；`V2-Q-NAV-01`～`13` 全部关闭 |
| `V2-REQ-BASE` | 1.4 既有能力补充基线：`V2-REQ-BASE-001～005` 分别为搜索产品契约、应用内更新、持久 SWR、本地歌单完整管理、音乐懒补队列 / 写放大；五组均为 MUST，已有来源、解读、可观察验收、依赖、非目标与 G1 未决项 | `docs/V2_PLAN.md` §5.10.5 与 ACTIVE；`DECISIONS` `V2-D196`、`V2-D197`；旧债 `L14-02/16/17/18/33` 已 MAPPED；BASE-001/003/004 静态合同已冻结并独立 reviewer PASS；最终批次 `9171494122a05dbade4e22f438489903ca0a55bd`、BASE-004 确定性修复 `5b7841d1b84fd91d60966851f5ba6da45005a4f6`，clean clone 双跑 exit 0 且 clean；BASE-004 为 24 文件 / 115,629 B，manifest SHA-256 `755368ADF03E5D4FBA500B7F94B7E240486D2111415AC86E9F8608F9DC49A8D8`，24/24 LF；仅表示合同输入与机械复现就绪，B/C/D、runtime、device、implementation 与 QA 未进入 Done |
| `V2-FINAL-CHECK` | 完整 G0 PASS：外部 `1–1156` 全覆盖，三项产品问题关闭，外部八域 `445` 个 source ID、`376` 个 MVP 子项，另有五个既有能力 MUST 组；`MUSIC 49→50`、`READER 65→66` | 用户于 2026-11-03 19:43:40 +08:00（记录时间）原话“1、2、3 均按推荐；接受完整 G0，包含 MUSIC 49→50 与 READER 65→66，按此收口。”；`docs/V2_PLAN.md` §5.10、§12、§13；`DECISIONS` `V2-D194`～`D197`；结构检查见 §13 |
| `V2-Q-G1-04` | 用户接受 S5 整包 R1，Q04 Closed/Accepted；五主题 DNA 与共享骨架成为 V2 R1 视觉方向；仅 D057 Nocturne 基础圆角 12→8dp，其他主题圆角与 D057 其他内容不变 | 用户于 2026-11-04 原话“选择R1。继续下一步，注意交接规范。还是尽量不要停，尽量使用子代理。”；`DECISIONS` `V2-D201`；`.planning/v2-g1/G1-S5/decision-brief.md`、`manifest.json`；`docs/UI_DESIGN_SYSTEM.md` V2 R1；Q03、runtime/device、S6、implementation 与 QA 均未因此 Done |
| `V2-G1-S6` | G1-S6 总验收矩阵与 G2 任务卡输入完成：外部 445 source / 456 rows / 376 MVP 历史不变；D203 另列 4 个 Reader project addendum，归一化 source 449 / rows 460 / MVP 380，Reader 当前 90 / 单级 MUST 67 / MVP 70。另有 5 BASE、30 R1、16 cards、49 waves、495 memberships、560 edges、50 thresholds、风险 P0–P3=`1/19/11/2`；原始 fresh scoped review `PASS_WITH_P2` 只作为 Q05 当时 Open 的历史；D204 后 fresh scoped documentation reconfirm 最终 `PASS / no findings`，当前 `S6_SCOPED_RECONFIRM_PASS_AFTER_D204`，future artifact change 仍须重新 review | `.planning/v2-g1/G1-S6/README.md`、`CHECKPOINT.md`、`manifest.json`、`acceptance-matrix.json`、`row-semantics.json`、`wave-membership.json`、`g2-task-cards.json`、`source-index.json`；self-test live=`57 negative + 10 base + 1 计数 = 68` GREEN；README / CHECKPOINT / source-index 均同时保留历史 Q05_OPEN pass 与当前 D204 后 review PASS；通用 evidence `can_prove` 禁止未执行的选择 / Android runtime / production overclaim；READER-060 当前契约为目标 Android 完全离线本地 TTS，D204 仅在依赖中固定 A 的兼容回退 + gated 第二引擎 Spike，并拒绝主引擎等价写法；根 `.gitattributes` 自稳定且固定 DECISIONS / V2_PLAN / TASKS 为 LF；Q03 nested evidence 只陈述底层静态证据上限；唯一历史 P2 `__pycache__` 已排除；Q05 Closed/D204、Q03 Open、Sherpa / Matcha 仅兼容回退、C1 exact profile Open、L2/L3 locked、runtime UNVERIFIED、G1 NOT_PASSED；review PASS 不等于 G1、QA、runtime 或 Release PASS |
| `V2-G1-L1-NETWORK-ARTIFACTS` | G1-L1 Sherpa-ONNX / PDFium 网络 artifact 静态审计完成：Sherpa review `PASS_WITH_P2`；PDFium review `PASS`。仅完成允许的网络下载、hash / AAR / ELF / license / source 静态审计，不关闭 Q03 | `.planning/v2-g1/G1-L1-NETWORK-SHERPA/README.md`、`CHECKPOINT.md`、`manifest.json`、`manifest.sha256`；`.planning/v2-g1/G1-L1-NETWORK-PDFIUM/README.md`、`CHECKPOINT.md`、`manifest.json`、`manifest.sha256`；`docs/V2_PLAN.md` §12、§13。P1 ORT notice / provenance、P2 source tag→commit、PDFium 32-bit 4 KB / 64-bit runtime / GNU_RELRO / source / notice 与 C1/Q03 仍未关闭 |

## 任务卡模板

```text
ID / 身份：
目标（可观察）：
非目标（至少两项）：
验收标准（逐条执行）：
适用规则：
分支 / 集成目标：
接口 / 文件边界：
依赖：
测试命令 / 证据：
风险与 P0–P3 边界：
证据目录：
停止条件：
```
