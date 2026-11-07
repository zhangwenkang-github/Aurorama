# Aurorama 架构设计（ARCHITECTURE）

| 项 | 值 |
|----|----|
| 文档版本 | v2.0（2026-11-07：V2 架构符合性、低耦合、逻辑 seam、守卫与并行基线） |
| 状态 | `V2_EXECUTABLE_BASELINE / USER_CONFIRMED_2026_11_07 / ARCH_GATE_PASS / ARCH_SEAM_DELIVERY_ACTIVE / BROAD_FEATURE_WAVES_SEAM_GATED / ARCH_GUARD_A0=INDEPENDENT_QA_PASS / MERGE_PREP_PASS / INTEGRATED / IDENTITY_CATALOG_W1A=IMPLEMENTATION_PENDING_INDEPENDENT_QA / A0_GUARD_IS_NOT_ARCH_REFACTOR=YES / ARCH_IMPLEMENTATION_REFACTORED=NOT_PROVEN` |
| 作者会话 | `AURORAMA_V2_ARCHITECTURE_CONFORMANCE_OWNER_2026_11_07`；`AURORAMA_V2_ARCH_OWNER_GUARD_W1P_INTEGRATION_DOCS_OWNER_2026_11_07`（integration sync）；`AURORAMA_V2_ARCH_SEAM_DELIVERY_PLANNING_OWNER_2026_11_07`（seam delivery planning）；`AURORAMA_V2_IDENTITY_CATALOG_SEAM_TASK_CARD_OWNER_2026_11_07`（W1 task card）；`AURORAMA_V2_IDENTITY_CATALOG_TASK_CARD_DI_CORRECTION_OWNER_2026_11_07`（DI scope correction） |
| 上游依据 | 外部原始文档第 154–286 行；`V2-REQ-ARCH-001～072`；`V2-D010～D020`、`D198`、`D211～D213`；`R035`；`L041`；G1-S1 / G1-S6 静态证据 |
| 适用对象 | V2 需求、架构、实现、独立 QA、merge-prep、Release 与多会话编排 |
| 维护规则 | 架构变更先改本文并通知受影响任务；不新增零散架构说明文件；实现事实仍以代码、Gradle、测试和候选证据为准 |

> 本文区分“架构方向已接受”“静态 seam 已冻结”“局部重构已完成”和“产品代码已全量重构”。
> 当前只满足前两项和有限的第三项；不得把本文存在、需求接受或静态审计通过表述为 V2 产品代码重构完成。

---

## 0. V2 可执行架构基线（2026-11-07）

### 0.1 审计裁决与现场

| 字段 | 当前值 | 证据 |
|---|---|---|
| 审计基线 | `6d76936253787dbb0c07ef939f8b936fe2e761cb`，`codex/v2-integration`，开始时 clean | 本轮 `git status --short`、`git rev-parse HEAD` |
| 架构需求 | `ARCH_REQUIREMENTS_ACCEPTED=YES`；72 条归一为 69 `MUST` / 3 `SHOULD` | `V2_PLAN.md` §5.2.2、§5.2.5 |
| 静态边界 | 8 个目标模块、19 条 direct project 边、19 条可解析 import 边、模块级环 0 | `.planning/v2-g1/G1-S1/report.json` |
| 稳定违规 | 11 条：P1 7 / P2 3 / P3 1 | 同上 `violations` / `priorities` |
| 当前生产树漂移 | G1-S1 基线 `00f4bfb3…` 到当前 HEAD，`app/core/data/player/modes/settings/setup` 的生产源码与 Gradle 文件变化 0 | `git diff --name-only 00f4bfb3..HEAD -- 'app/**' 'core/**' 'data/**' 'player/**' 'modes/**' 'settings/**' 'setup/**'` |
| 架构守卫 | A0 owner guard / debt ratchet 已独立 QA + merge-prep `PASS` 并集成；尚未接入 CI / Gradle 常规任务门 | source `a787b683`、`tools/architecture/**` 3 文件、integration `ab917404`；独立 QA 7/7、direct guard `19/19 + 8/8` |
| 全量 V2 重构 | `ARCH_IMPLEMENTATION_REFACTORED=NOT_PROVEN` | A0 只固化既有边界与债务 ratchet；contracts seam、adapter / consumer 迁移与候选架构门仍未实现 |
| 本文状态 | `ARCH_CONFORMANCE_AUDIT=PASS / USER_CONFIRMED_2026_11_07 / ARCH_GATE=PASS / A0_INTEGRATED / ARCH_SEAM_DELIVERY_ACTIVE / IDENTITY_CATALOG_W1A=IMPLEMENTATION_PENDING_INDEPENDENT_QA` | 实现候选已通过自身门禁，等待不同全新 QA；W0 仅为 `ISOLATED_PROBE / NOT_STARTED` |

“审计 PASS”只表示本基线足以指导后续实现并明确未证明项，不是产品架构完成、G1、
QA、runtime 或 Release PASS。

### 0.2 重构层级：到底重构了什么

| 层级 | 结论 | 事实边界 |
|---|---|---|
| 已接受的架构方向 / 契约 | 已接受 | `V2-D010`～`D020` 强约束、`V2-D198` 逻辑 seam / FQCN owner allowlist / debt ratchet、`D212` 修复 lineage |
| G1 静态 seam、allowlist、违规基线 | 已冻结为审计输入 | 19 条 direct edges、0 模块级环、11 个稳定违规 ID、45 条 package-only owner 歧义；不是运行时代码 |
| 已完成的局部重构 | 有，但不是 V2 全量重构 | ①W83 设计系统收敛到 `core/presentation/theme`；②`PlaybackCoordinator` 统一播放起播仲裁；③`61c04a38` 将下载行内容点击与尾部任务动作拆分，并有 `DownloadRowAccessibilityContractTest` |
| 尚未执行的产品代码重构 | 未执行 | contracts seam、adapter / consumer 迁移、Gradle 物理拆分、跨层依赖清零、候选架构门均未落地；A0 owner guard / debt ratchet 已集成，但不消除既有 11 个违规 |

因此正确状态是：

```text
ARCH_DIRECTION_ACCEPTED=YES
ARCH_STATIC_BASELINE_FROZEN=YES
LOCAL_REFACTORS_PRESENT=YES
ARCH_IMPLEMENTATION_REFACTORED=NOT_PROVEN
PHYSICAL_MODULE_SPLIT=NOT_EXECUTED
```

### 0.3 原始第 154–286 行符合性矩阵

矩阵按 72 条归一需求逐类覆盖。状态只使用 `COMPLIANT / GAP / N/A / NOT_PROVEN`；
每行给出证据与下一步。汇总：
`7 COMPLIANT / 9 GAP / 3 NOT_PROVEN / 1 N/A`。

| # | 类别 | 覆盖需求 | 状态 | 证据与边界 | 收敛动作 |
|---:|---|---|---|---|---|
| 1 | 交付目标、证据、阶段汇报 | 002、004、009 | `COMPLIANT` | `AGENTS.md` 最终格式、`R025`、`V2_PLAN` 门禁均要求实际执行 / 未覆盖 / 风险 / 证据 | 后续报告继续机械执行 |
| 2 | 需求澄清、用户故事、非目标、风险 | 003、020、029 | `COMPLIANT` | G0/G1 需求门、任务卡非目标、用户确认记录 | 新需求仍走同一门 |
| 3 | 编码前架构规范及用户确认 | 010、021、055、071 | `GAP` | 本文 v2.0 已由用户于 2026-11-07 确认；审计时曾有“首卡先批准、架构后补审”的次序倒置。当前不再要求重复确认方向，但 contracts seam / consumer migration 仍未实现，功能波必须按 seam gate 放行 | 以 D213 / R035 的长期计划账本和完整 seam DoD 放行，不以“架构已确认”代替实现 |
| 4 | ADR、已确认决策复用 | 011、051 | `COMPLIANT` | `DECISIONS` + ADR、D198 / D211～D213 可定位 | 不在后续会话重复争论 |
| 5 | 契约先行、必要接口、可测试边界 | 012、056、062 | `GAP` | 逻辑 seam 只有候选锚点；UI 仍可看到 data / player-local 具体实现 | 先建最小 public port + adapter 试点 |
| 6 | 依赖方向、无循环 / 反向 / 跨层 | 013、057 | `GAP` | 模块级环 0，但 7 个 P1 跨层 / 共享包违规仍在 | debt ratchet 禁新增，逐 seam 收敛 |
| 7 | 单一数据所有权 | 058 | `GAP` | `models` 49 文件跨 core/data、`utils` 39 文件跨 app/core/data；下载与播放状态仍有跨界面推导风险 | 在建 seam 时写明唯一 writer 与派生投影 |
| 8 | 显式依赖、全局 / 单例边界 | 059 | `NOT_PROVEN` | 现有 Hilt 构造注入较多，但 G1-S1 未做字段 / 方法级全局状态图 | 只对新增链路做构造 / owner 审查，不扩全仓 bespoke 扫描 |
| 9 | 公共层治理、影响面分析 | 014、060、064 | `GAP` | 低耦合与公共层规则存在；W0 隔离探针已冻结精确两文件；Identity/Catalog W1 已有精确合同 / adapter / consumer 白名单，但无实现证据 | root review 后只按冻结白名单实现；其他 seam 继续冻结影响矩阵并进入 TASKS 账本 |
| 10 | 统一失败、重试、超时、降级、日志 | 061 | `NOT_PROVEN` | 下载已有多个纯规则和测试，但尚无全链路统一错误合同 | W0 不扩到这里；由对应下载 / 写 / 搜索波的 seam 与功能切片验证 |
| 11 | 文档单一权威与同步 | 016–019、047 | `COMPLIANT` | README / ARCHITECTURE / DECISIONS+ADR / V2_PLAN / TASKS / TEST_PLAN / RELEASE_PLAN 映射已确认；禁建零散 md | Release notes 到候选门再更新 |
| 12 | 任务卡、正交拆分、分支基线、文件边界 | 017、022、030、031、035、036、066 | `GAP` | W0 与 W1 Identity/Catalog 均已冻结精确文件边界；W1 首个切片为 `ICS-W1A-SCOPED-SEARCH-SNAPSHOT`，当前无产品功能分支 | W1 仍待 root review；实现时从当次 clean integration 建独立 worktree |
| 13 | 专业角色、独立 QA、子代理报告 | 005、015、025、026、032、034、052、067 | `COMPLIANT` | `AGENTS.md`、`R004`、`R012`、`R030` 明确实现 / QA / review / merge-prep 分权 | 未执行阶段只写 NOT_REACHED |
| 14 | 标准工作流顺序 | 001、006–008、023–029、033、037–039 | `GAP` | 需求与架构方向均已确认；Identity/Catalog seam 任务卡已冻结待 review，当前尚未开始产品实现 | root review → RED / GREEN → slice tests → 后续子波 → 独立 QA → merge-prep / integrated gate |
| 15 | 自检、独立 QA、merge-prep、集成态证据 | 024、027、037–039、041–044、046、063、065、069 | `NOT_PROVEN` | 当前没有 2.0 seam / 功能实现候选、executed、独立 QA、祖先矩阵或 integrated gate；W0 只是隔离探针，A0 guard 只是债务门 | Identity/Catalog seam 完整 DoD 后才为 W1 功能波提供前置证据 |
| 16 | Token / 读序 / 停止错误方向 | 050、053、054 | `COMPLIANT` | 本轮只读指定段与原子报告；错误次序被记录为 GAP；无全篇加载 | 后续复用同一白名单策略 |
| 17 | P0/P1、测试、构建、迁移、配置、回滚、发布门 | 040–049、069 | `GAP` | 规则已配置，但当前 `DatabaseModule.kt:25` 仍有 `fallbackToDestructiveMigration(dropAllTables = true)`，发布门未满足；无 2.0 候选 | P0 迁移门必须在候选前关闭；当前不释放发布 |
| 18 | 架构守护、发布图门 | 068、070 | `GAP` | A0 owner guard / debt ratchet 已独立 QA、merge-prep 并集成到 `ab917404`，direct guard 与 11 违规 ratchet 可运行；CI / Gradle 常规 architecture / owner / contract drift 接线仍 pending。A0 只防新增债务，不是 seam 或产品重构 | 保留单一只读 guard + JSON 基线并补 CI 接线；后续 seam 以合同 / 消费者迁移 / 测试闭环，不继续扩 bespoke scanner |
| 19 | 原始材料取舍与吸收 | 072 | `COMPLIANT` | 72 条已归一、分级、逐条回链；外部原文只读；不机械复制文档结构 | 后续变化先登记决策 |
| 20 | 当前阶段截图 / Release 材料 | R013、V2 核心链路门 | `N/A` | 当前没有候选包核心旅程 PASS；按 `R013` 禁止制作发布材料 | 实现、独立 QA、候选旅程后另行授权 |

### 0.4 文档开头工作流符合性

| 阶段 | 状态 | 事实 |
|---|---|---|
| 开场只读与初始化声明 | `PARTIAL` | 本轮按派单读取 `AGENTS`、V2 指定段、规则、决策、任务、项目地图、G1 与外部 1–286 行；首条回复未按要求输出“N 规则 / M 教训 / K 任务”计数声明，后续以精确读序和证据补足，不影响未开始编码 |
| 需求澄清 | `SATISFIED` | G0 完成；架构 72 条有来源、可观察验收、依赖、非目标和用户确认 |
| 架构与契约 | `PARTIALLY_SATISFIED_RECOVERED` | 需求已接受；本文补齐 V2 执行版；逻辑 seam 仍在代码侧未实现 |
| 用户确认架构 | `SATISFIED` | 用户于 2026-11-07 明确确认并指示执行下一步；架构门 PASS。W0 仅为隔离探针 |
| 任务卡 | `SATISFIED_ACCEPTED_IMPLEMENTED_PENDING_QA` | W0 有精确两文件；Identity/Catalog W1 有 public port、8 源码 + 6 测试白名单与停止门；实现 candidate `693eff11` 通过自身测试/build/A0 guard，等待独立 QA |
| 实现 | `NOT_REACHED` | 无产品代码修改 |
| 自检 | `NOT_REACHED` | 无 RED / GREEN / 构建 / 定向回归 |
| 独立 QA | `NOT_REACHED` | 未派发，不能用历史 W90 / 1.4 证据替代 |
| merge-prep | `NOT_REACHED` | 无功能分支、无 source/target/final 审计 |
| 集成分支门 | `NOT_REACHED` | 无祖先矩阵、集成态构建、核心回归或候选包 |

流程上的历史缺陷是“架构确认置于首卡批准之后”。用户已于 2026-11-07 确认本文；当前不再
要求重复确认方向。修复方式是把 W0 限定为隔离探针，并让所有功能波通过 Identity/Catalog
起始的 seam 账本与完整 DoD 放行，而不是降低门禁或写 `PASS_WITH_GAPS`。

### 0.5 低耦合规则（新代码强制）

1. **模块 owner 真源**：owner 只由 Gradle module 路径 + source owner 推导；不得用
   namespace、最长 package 前缀或手写包清单猜 owner。
2. **FQCN 唯一解析**：跨模块 import 必须解析到唯一 FQCN；歧义进入 `QUARANTINE`，不得
   按声明顺序猜测。
3. **只依赖直接声明的 public port**：禁止通过 transitive project dependency 导入实现。
4. **UI / feature 禁止直连实现**：presentation / ViewModel / Activity / Service 不得新导入
   `JellyfinApi`、DAO、repository impl、`DownloaderImpl`、`PlayerHolder`、`MPVPlayer`、
   `PlayerViewModel` 或 Worker 内部类型。
5. **共享包冻结**：`com.zhangwenkang.cinefin.models`、`utils`、`update` 的现有多 owner
   存量只减不增；不得继续把新内部类型塞入共享包。
6. **公共层改前说明影响面**：模块、契约、数据、配置、公共 UI、测试、回归点、回滚对象
   必须写入任务卡；共享 UI / token / 导航由单一 owner 串行修改。
7. **实现与证据分层**：静态 seam PASS 不能替代编译、契约回归、候选包真实入口、独立 QA
   或 Release 证据。

### 0.6 逻辑 seam 与数据所有权

本轮采用 `V2-D198` 的逻辑 contracts seam，不建全量物理 Gradle 拆分。物理拆模块只有在
单个 seam 的构建、DI / KSP、Room、instrumentation、APK 和回滚成本均证明收益后才重新提报。

| Seam | 唯一对外合同 | 隐藏实现 | 唯一 writer / 不变量 | 当前状态 |
|---|---|---|---|---|
| Scope / Identity | canonical scope + stable identity DTO | auth、preferences、DB namespace | 切号、离线、缓存、下载、通知、队列不串身份 | `PARTIAL_ANCHORS / W1A_IMPLEMENTED_PENDING_QA` |
| Catalog read | immutable snapshot + stale / generation | API、DAO、SWR、TTL | 页面只读投影；data 统一刷新 | `PARTIAL / W1A_SEARCH_SLICE_PLANNED` |
| User-state write | intent → accepted → confirmed / denied | SDK 写、回读、重试 | data 写网关是唯一 writer；403 不重试 | `NOT_IMPLEMENTED` |
| Download control | command + immutable task / hierarchy projection | WorkManager、Room、sidecar、文件、通知 | 下载控制实现单一写状态；页面 / 角标 / 通知同源 | `PARTIAL` |
| PlaybackSession | session command / state / owner generation | ExoPlayer、mpv、MediaSession、ViewModel 内部 | 单活动逻辑会话、单 MediaSession；旧 owner 不写新状态 | `PARTIAL` |
| Reader source | source-scoped book identity + progress / locator | Readium、PDF、SAF、Range | source-scoped bookId；内容指纹与进度分离 | `PARTIAL` |
| Music library / queue | stable queue snapshot + command | playlist store、ExoPlayer、具体 controller | 当前 / Next Up / 恢复序用稳定身份 | `PARTIAL` |

当前现有锚点包括 `JellyfinRepository`、`ReaderRepository`、`Downloader`、
`DownloadTask` / `DownloadAction`、`PlaybackCoordinator`、`MusicPlaybackController`。
这些是“可演进合同”，不是已经完成的严格 seam；合同不得泄漏 DAO、SDK、Worker、
`PlayerHolder` 或 `MPVPlayer`。

**Identity/Catalog W1 冻结方向**：`V2-ARCH-SEAM-IDENTITY-CATALOG-W1` 的任务卡、
首个切片、精确白名单和执行门只维护在
`docs/TASKS.md#v2-arch-seam-identity-catalog-w1-冻结任务卡`。摘要如下：

- public port：`com.zhangwenkang.cinefin.repository.catalog.CatalogReadPort`，位于 `:data`；
- snapshot contract：`CatalogSnapshot<T>` 携带 `CatalogScope`、content generation、
  `CatalogFreshness` 与 observed time；scope 复用既有
  `com.zhangwenkang.cinefin.repository.JellyfinOrigin`；
- implementation：`JellyfinRepositoryImpl` / `JellyfinRepositoryOfflineImpl`；由 `:data` 的
  `CatalogReadModule`（`@Module @InstallIn(SingletonComponent::class)`）按既有
  `AppPreferences.offlineMode` 选择 `CatalogReadPort`，复用 core `RepositoryModule` 已提供的
  implementation singleton，不重复创建，`core/RepositoryModule.kt` 保持不变；
- 第一个消费者：`SearchViewModel` / `SearchSession`；
- A0 预期：W1A 不改 `core/RepositoryModule.kt`，`coreToDataImports=130` 与 11 违规
  P1/P2/P3=`7/3/1` 保持不变；不得用 FQCN inline import 规避扫描，也不得修改 baseline；
- 首个切片 `ICS-W1A-SCOPED-SEARCH-SNAPSHOT` **不是完整 seam**：账号 / preference 单 writer、
  其他 catalog readers、offline namespace、401/403、durable fence 与独立 QA / integration
  均未实现；
- `V2-NON-TTS-W1` 仍 `BLOCKED_BY_PREREQUISITES`，`ARCH_IMPLEMENTATION_REFACTORED` 仍
  `NOT_PROVEN`。

**长期计划执行账本**：canonical seam 关键路径维护在
`docs/TASKS.md` 的 `Architecture Critical Path / 长期计划账本`。当前 open ID 为
`V2-ARCH-SEAM-IDENTITY-CATALOG-W1`、`V2-ARCH-SEAM-DOWNLOAD-CONTROL-W2`、
`V2-ARCH-SEAM-PLAYBACK-SESSION-W3`、`V2-ARCH-SEAM-READER-SOURCE-W4`、
`V2-ARCH-SEAM-USER-WRITE-W5`。功能波派单前必须核对 `PREREQUISITE_SEAMS`；交接必须列出
`OPEN_LONG_TERM_PLAN_IDS / NEXT_CRITICAL_PATH / last_evidence`。

seam 的 Definition of Done 统一为：

```text
CONTRACT_FROZEN -> ADAPTER_IMPLEMENTED -> TARGET_CONSUMERS_MIGRATED ->
OLD_PATH_REMOVED_OR_NO_DUAL_TRUTH -> CONTRACT/NEGATIVE/REGRESSION TESTS PASS ->
DEBT_COUNT_DECREASED -> INDEPENDENT_QA PASS -> INTEGRATED_GATE PASS
```

上述 `CONSUMER_MIGRATED + TESTS_PASS + INTEGRATED` 是功能波前置门的压缩表示，只有在完整
八状态全部满足后才成立。缺少任一状态不得标 Done，不得用“抽象已规划”“guard 通过”或历史
1.4 证据代替。功能只作为 seam 迁移后的验证切片，禁止在任意功能卡内顺手重构。

### 0.7 架构守护与 debt ratchet

初始 guard 必须以 G1-S1 为唯一债务快照，禁止重新设计第二套 bespoke 协议。

**P1 冻结 ID**：

- `G1-S1-V-f3f117c921f7`：`models` 跨 core/data
- `G1-S1-V-1d0658ad88d4`：`utils` 跨 app/core/data
- `G1-S1-V-d0a87242164d`：app presentation → data implementation
- `G1-S1-V-d8a9ce2529bf`：core 层塌缩
- `G1-S1-V-ba7ffc6c9bfe`：player/local presentation → data implementation
- `G1-S1-V-17dbbcd423d6`：music → player/local concrete
- `G1-S1-V-434beb5c4623`：app → player/local concrete

**P2**：`67c5e05b0c29`、`d6b630851ba5`、`3a750bb14da2`。
**P3**：`56f941f5ffea`。

最小 guard 每次输出并校验：

1. Gradle module DAG 与 19 条 exact direct edges；unexpected edge / cycle 必须为 0。
2. source path → module owner registry；跨模块 FQCN 唯一；歧义 quarantine 必须为 0。
3. 新增代码不得命中 UI→data impl、feature/app→player-local concrete、共享包扩散。
4. 既有 11 个违规 ID 只能数量持平或下降；不得用换路径、改包名或拆行隐藏债务。
5. 合同漂移与公共层改动必须绑定任务卡批准记录。

执行预算遵守 `R033`：guard 任务开工前声明 10–15 分钟静态验证时间盒；同一问题最多两轮修复、
最多一次全长回归。第二轮仍有 P1 时冻结当前实现，改用 G1-S1 JSON 基线与现有 Gradle /
graphify 输出的只读检查；不得继续扩 gate、字段或 exact count。

**A0 落地事实（2026-11-07）**：source
`a787b683c89389491099ea7acb069422190327c3`（parent `c56ffdc3`）与旧 target
`d6b431764e6a7d088ec4e8187af30b5b80223289` 的 scoped diff 均只新增
`tools/architecture/v2-owner-baseline.json`、`v2-owner-gate.ps1`、
`v2-owner-gate.tests.ps1`；source/final stable patch-id
`eaf42d616966c3cea0b8274ec9e659075f6e48bd` MATCH，integration
`ab9174043db9cb9e637054210cfee580d06f2965`。独立 QA `PASS` 7/7、merge-prep `PASS`；
integration worktree 复跑 `passed=7 failed=0 total=7`。direct guard 内部边 `19/19`、
外部边 `8/8`、cycles `0`、FQCN ambiguity `0`、debt-ratchet failure `0`；既有 11 违规仍为
P1/P2/P3=`7/3/1`。A0 findings P0–P3=`0/0/0/0`。该门禁只冻结继承基线，不证明产品重构。

**W1A DI scope correction（2026-11-07）**：`CatalogReadModule` 属于 `:data`，通过既有
`:settings` 依赖读取 `AppPreferences.offlineMode`，只选择 core `RepositoryModule` 已提供的
`JellyfinRepositoryImpl` / `JellyfinRepositoryOfflineImpl` singleton。它不得重复提供这两个
singleton；`core/RepositoryModule.kt` 不在 W1A 写域内。预期 `coreToDataImports=130` 不增、
11 违规 P1/P2/P3=`7/3/1` 不变；若实现只能靠 core import、FQCN inline 规避扫描或 baseline
修改才能成立，必须停止并报告 `BLOCKED`，不得把 DI 退回 core。

### 0.8 共享文件锁与可并行候选

所有 seam / 功能任务必须从当次已提交、clean 的 integration SHA 单独创建 worktree；禁止从
另一功能分支派生再合入。本文审计时 integration SHA 为
`6d76936253787dbb0c07ef939f8b936fe2e761cb`；随后 A0 guard 已独立 QA / merge-prep 并集成到
`ab9174043db9cb9e637054210cfee580d06f2965`。2026-11-07 seam 规划基线为 clean
`fee63165f966a52318e332abe1ad7b184592a88a`；W1 任务卡冻结基线为 clean
`d85f695b9ba1a220d85d529388769a7ad81b76e8`。后续 docs candidate 集成后，Identity/Catalog
implementation worktree 必须从 root 钉住的新 clean integration 创建，不得继续从审计 SHA、
A0 integration SHA 或任一 sibling feature branch 派生。

| 串行咽喉 | 锁范围 | 规则 |
|---|---|---|
| 构建注册 | `settings.gradle.kts`、根 `build.gradle.kts`、`gradle/libs.versions.toml` | 单 owner；新增模块 / 仓库 / 依赖必须先批准 |
| 公共架构合同 | `CatalogReadPort.kt`、`JellyfinRepository.kt`、`Downloader.kt`、`PlaybackCoordinator.kt`、`CinefinPlaybackService.kt` | W1A 单 owner 冻结 search port / snapshot DTO；其他 owner 只迁移消费者，不各自改合同 |
| Catalog cache / SWR | `MetadataCache.kt`、`MetadataSnapshotSwr.kt`、`MetadataSnapshotStore.kt`、`MetadataSnapshotCodec.kt` | W1A 与 `G2-PERSISTENT-SWR-W1P` 串行；不得并行改 generation / stale 语义 |
| 下载真相 | `DownloaderImpl.kt`、`DownloadTask.kt`、`DownloadControlPlane.kt`、Worker / Job、sidecar、Room、通知、`DownloadsViewModel.kt` | 同一波串行；禁止 UI 与 Worker 并行改状态语义 |
| 导航 | `NavigationRoot.kt`、`DownloadsScreen.kt`、导航宿主回调 | 单 owner；W0 默认不进入 |
| 公共 UI / token | `CinefinCard.kt`、theme / token、跨 feature 组件 | UI foundation 单 owner；W0 不得顺手改 |
| 身份 / 配置 | `AppPreferences.kt`、auth、账号选择、离线 scope | Identity seam 单 owner；所有依赖方等待合同 |

预留 seam / 构建变更的影响：根 build 或依赖目录变化会阻塞全部四个候选；公共
`JellyfinRepository` / search port 冻结会影响 Search、SWR、Reader 与 Write；下载真相
会同时影响 W0 UI、W2、通知与离线投影；PlaybackSession 会影响 W3、音乐 / 视频和后续
TTS；`NavigationRoot` / `DownloadsScreen` 或共享 UI 会影响 W0、W1 与导航任务。依赖这些
咽喉的候选不得与新合同实现并行，只能在合同提交后从新 integration SHA 重建。

**并行任务（A0 已集成；其余候选尚未授权实现）**

| 候选 | 目标 / 单一可观察结果 | 文件白名单 | 为什么可并行 |
|---|---|---|---|
| `V2-ARCH-OWNER-GUARD-W1P` | 已集成（`ab917404`）：把 G1-S1 的 19 边 / 11 违规快照落成只读 owner guard 与负例 | `tools/architecture/v2-owner-gate.ps1`、`tools/architecture/v2-owner-baseline.json`、`tools/architecture/v2-owner-gate.tests.ps1` | 只读工具目录；不碰产品或 Gradle |
| `V2-NON-TTS-W0-DOWNLOAD-CARD-ACTION` | 先证明当前候选是否仍有完成卡点击缺陷；有则最小修复，无则停止 | `app/phone/src/main/java/com/zhangwenkang/cinefin/presentation/film/downloads/DownloadRows.kt`、`app/phone/src/test/java/com/zhangwenkang/cinefin/presentation/film/downloads/DownloadRowAccessibilityContractTest.kt` | app 下载行 UI 与测试；不碰 data、player、构建或契约 |
| `G2-UPDATE-TRUST-W1P` | 固定更新包信任判断的纯逻辑与负例 | `data/src/main/java/com/zhangwenkang/cinefin/update/UpdateHttpDownloader.kt`、`data/src/main/java/com/zhangwenkang/cinefin/update/UpdateTrustVerifier.kt`、`data/src/test/java/com/zhangwenkang/cinefin/update/UpdateHttpDownloaderTest.kt` | data/update 独立子树；不碰下载 UI、导航或 Room |
| `G2-PERSISTENT-SWR-W1P` | 固定持久快照 codec / store 的 generation 与损坏恢复逻辑 | `data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotStore.kt`、`data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotCodec.kt`、`data/src/test/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotSwrTest.kt`、`data/src/test/java/com/zhangwenkang/cinefin/repository/MetadataSnapshotStoreTest.kt` | data/repository 的独立快照文件；不碰 app UI、下载、播放 |

四组白名单两两无交集。任一任务若需要 `settings.gradle.kts`、根 build、
`JellyfinRepository.kt`、下载真相、导航宿主或公共 UI，必须停止并排队串行，不得自行扩锁。

`ICS-W1A-SCOPED-SEARCH-SNAPSHOT` 不属于上述可并行四组：它串行占用
`CatalogReadPort` / `JellyfinRepository` search contract、`MetadataSnapshotSwr`、
`:data` 的 `CatalogReadModule` 与 Search consumer 锁；与 `G2-PERSISTENT-SWR-W1P` 的
`MetadataSnapshotSwrTest.kt` 重叠，必须先完成一项并重新从新 integration 派生另一项。
`core/src/main/java/com/zhangwenkang/cinefin/di/RepositoryModule.kt` 不在 W1A 写域内，
必须保持字节不变，以维持 `coreToDataImports=130`。

### 0.9 W0 隔离探针约束与 D212 lineage

`V2-NON-TTS-W0-DOWNLOAD-CARD-ACTION` 是
`ISOLATED_PROBE / NOT_ARCHITECTURE_PROGRESS`，不是 seam，也不能作为后续功能波的前置证明。
其静态审计结果：

```text
HISTORICAL_FIX=61c04a38a037dbc5fa450c9e809d2277fcd33151
ANCESTOR_OF_V1_4=YES
ANCESTOR_OF_CURRENT_HEAD=YES
CURRENT_STATIC_IMPLEMENTATION_PRESENT=YES
CURRENT_DEFECT_REPRO=NOT_PROVEN
CANDIDATE_REAL_ENTRY_REGRESSION=NOT_RUN
LINEAGE_STATUS=UNPROVEN
```

`61c04a38` 已把 `CinefinCard` 的整卡 click / long-click 置空，把内容区交互与尾部任务动作
拆成兄弟范围，并新增 `DownloadRowAccessibilityContractTest`。当前代码的完成叶行仍通过
`DownloadAction.OpenEntry` → `resolveEntryItem` → `Host.onItemClick` 打开目标。因此该探针不能
默认“尚未修复”：实现 owner 必须先做当前对象的最小复现；无缺陷时返回
`NO_DEFECT_REPRODUCED` 并停止。

W0 隔离探针放行条件：

1. 用户已于 2026-11-07 确认本 V2 架构方向；执行仍需遵守 seam gate，但不重复询问方向。
2. 集成 owner 提交文档并确认 clean integration SHA；实现 owner 从该 SHA 单独 worktree。
3. 默认白名单只有 `DownloadRows.kt` 与 `DownloadRowAccessibilityContractTest.kt`。
4. 先绑定 `V2-FIX-DOWNLOAD-001` / `V2-REG-DOWNLOAD-001`，记录当前 RED 或明确无缺陷。
5. 若需要 `DownloadsScreen.kt`、`NavigationRoot.kt`、`DownloadsViewModel.kt`、`CinefinCard.kt`、
   下载状态机、通知、schema 或迁移，停止并拆新卡；不得扩项。
6. 修复后必须另有独立候选包场景；静态测试、历史 v1.4 通过或 `61c04a38` 祖先都不能单独
   证明当前候选 `FIXED_IN_CANDIDATE`。
7. 该探针可独立派发，但不得阻挡 Identity/Catalog seam，也不得计入
   `CONTRACT_FROZEN` 之后的任一 seam 状态。

### 0.10 未证明项与确认门

- 产品代码尚未完成 V2 contracts seam / adapter / consumer migration。
- A0 owner guard / debt ratchet 已落盘并 integrated：source `a787b683`、旧 target
  `d6b43176`、integration `ab917404`，只新增 `tools/architecture/**` 3 文件，source/final
  stable patch-id MATCH；独立 QA `PASS` 7/7、merge-prep `PASS`，integration worktree 复跑
  7/7，direct guard 内部边 `19/19`、外部边 `8/8`、cycles `0`、FQCN ambiguity `0`、
  debt-ratchet failure `0`，既有 11 违规仍 P1/P2/P3=`7/3/1`，A0 findings
  P0–P3=`0/0/0/0`。该门禁只固定既有边界，尚未接入 CI / Gradle 常规门。
- contracts seam、adapter / consumer migration 与产品全量架构重构仍 `NOT_PROVEN`；
  guard 集成不等于产品架构重构完成。
- 模块级无环不等于包级、类型级或调用级低耦合。
- `V2-ARCH-SEAM-IDENTITY-CATALOG-W1` 任务卡已冻结为
  `SLICE_W1A_IMPLEMENTATION_PENDING_INDEPENDENT_QA`；自身实现/测试/build/A0 guard 已有证据，
  但尚无独立 QA 或 integration 证据。W1–W5 功能波
  在各自 `PREREQUISITE_SEAMS` 达到完整 DoD 前保持 `BLOCKED_BY_PREREQUISITES`。
- 首个切片 `ICS-W1A-SCOPED-SEARCH-SNAPSHOT` 只计划证明搜索目录读、SWR freshness、
  origin-bound request 与 Search consumer 的同 scope；身份写入原子性、其他 catalog
  consumers、401/403、durable generation 与跨进程语义仍未证明。
- `DatabaseModule.kt:25` 的破坏性 fallback 仍是 P0 发布阻断，归
  `G2-ARCH-CONTRACTS-MIGRATION`，不得由 W0 下载卡修复夹带。
- W0 隔离探针所示缺陷已有历史修复，但当前候选复现、独立 QA 和真实入口回归未执行。
- 2.0 实现、自检、独立 QA、merge-prep、核心候选旅程、P0–P3 与 Release 均未到达。

用户已于 2026-11-07 确认本文件，关闭“架构规范与并行编排门”。当前唯一架构主线入口是
`V2-ARCH-SEAM-IDENTITY-CATALOG-W1` 任务卡的 root review；review 前不实现。W0 只能作为
独立隔离探针，仍不自动批准设备、独立 QA 或 Release。

---

## 1. 1.4 历史现状基线（as-is，非 V2 完成声明）

> 本节至 §9 保留 2026-10-17 前后的 1.4 实施、阅读器 / 音乐设计与历史验证细节，供追溯和
> 局部复用。模块版本、数据库 schema、CI、任务状态等易漂移事实不得从本节直接当作当前
> 真值；若与 §0 冲突，以 §0、Gradle、代码、Git 和候选证据为准。

### 1.1 模块与依赖（`settings.gradle.kts` 实测）

当前 11 个模块：`:app:phone`、`:app:tv`、`:core`、`:data`、`:player:core`、`:player:local`、`:setup`、`:modes:film`、`:modes:book`、`:modes:music`、`:settings`。

| 模块 | 职责 | 关键依赖 |
|------|------|---------|
| `app/phone` | 应用入口：`NavigationRoot`（类型安全导航）、播放 Activity、前台播放服务、主题 | core、data、player:core、player:local、setup、modes:film、settings |
| `core` | 设计系统（`core/presentation/theme`）、通用工具、常量、下载器（`Downloader` / `DownloaderImpl`）、Worker（Sync / Images / MpvCleanup）、DI | data、player:core、settings |
| `data` | Jellyfin SDK 封装（`JellyfinApi`）、`JellyfinRepository`（在线 / 离线两实现）、Room（`ServerDatabase` v8） | settings |
| `player/core` | 播放领域模型：`PlayerItem`、`Track`、`PlayerSubtitleSource`、`PlayerChapter`、`TrickplayInfo` | 无（纯模型） |
| `player/local` | 播放内核：`PlayerHolder`（单例，ExoPlayer/mpv 双内核）、`PlayerViewModel`、`PlaylistManager`、`TrackSelectionEngine`、`SubtitleParser` + `PlayerSubtitleController`、`MPVPlayer` | player:core、data、settings |
| `modes/film` | 影视模式业务：Home / Library / Movie / Show / Season / Episode / Collection / Favorites / Downloads 的 State+Action+ViewModel | core、data、settings |
| `modes/book` | 阅读模式业务：书架 / 阅读器 / 批注（Reader 状态与领域规则） | core、data、settings |
| `modes/music` | 音乐模式业务：浏览 / 队列 / 歌词 / 离线（队列持久化与编辑） | core、data、settings、player:core、player:local |
| `settings` | `AppPreferences`（SharedPreferences 封装）、语言匹配（`LanguageMatcher`）、设置 UI 模型 | 无 |
| `app/tv` | TV 入口，**冻结**（仅保证可编译） | — |

### 1.2 现状关键事实（本会话实测）

1. **播放器是进程级单例**：`PlayerHolder`（`@Singleton`）按 `AppPreferences.playerBackend` 创建 ExoPlayer 或 `MPVPlayer`；`CinefinPlaybackService`（`MediaSessionService`）复用同一实例，通知栏 / 锁屏与播放页操作同一 Player。
2. **视频队列**：`PlaylistManager` 负责"整剧 / 整季 / 单片"的队列构建，逐集按需构建 `PlayerItem`（不预先全量拉流）。
3. **MVI 范式**：业务页统一 `Screen + State(data class) + Action(sealed) + ViewModel(@HiltViewModel)`，状态用 `MutableStateFlow` + `asStateFlow()`。
4. **数据层**：`JellyfinRepository` 接口 + `JellyfinRepositoryImpl`（在线）/ `JellyfinRepositoryOfflineImpl`（离线，Room 缓存），`JellyfinApi` 用 jellyfin-sdk 1.8.12 的 `*Api` 扩展（`playStateApi`、`itemsApi`、`userLibraryApi`、`sessionApi` 等）。
5. **设计系统已统一归 `core`**（W83，2026-10-17）：`core/.../core/presentation/theme` 承载颜色、排版、形状、间距、主题与动效；phone 侧原 `Theme` / `Spacing` / `Motion` 三文件已迁入 `core`，原 `app/phone/.../presentation/theme` 目录移除；`LocalSpacings` 仍由 `CinefinTheme` 提供，保留兼容层 `LegacySpacings`。
6. **阅读入口现状**：`NavigationRoot.navigateToItem()` 对 `FindroidFolder(kind == "Book")` 直接跳 `ConsoleRoute("/details?id=…")`（服务器 WebView 阅读器）——**本期要替换成原生阅读器**。
7. **音乐现状**：无音乐模式与入口；音频只能被当作"视频"塞进视频播放器。音乐库类型（`CollectionType.Music`）已在 `supported` 列表内可浏览。
8. **CI 现状**：Build 仅 `master` push 触发 + PR 触发、忽略 `docs/**` 与 `**.md`、带 concurrency 取消；Format 跑 `./gradlew ktfmtCheck`（`**.kt` / `**.kts`）。仓库现有 **975 个 JVM 单测**（8 模块、纯 JUnit4；无 Robolectric / `androidTest`，Compose 层不可单测）；**单测门禁自 2026-10-17 起由 `.github/workflows/test.yaml` 承担**。
9. **Jellyfin 兼容基线（2026-10-21）**：当前测试服务器为 **12.2.0**；SDK 与自定义 OkHttp 的认证都必须兼容 `Authorization: MediaBrowser Token="…"`。文中 10.11.8 的实测数字保留为历史证据，不作为新实现继续沿用的唯一版本假设。

---

### 1.3 结构审计与重构候选（2026-10-17 历史结论）

历史结论：**模块级依赖图无环**（11 模块、无循环依赖）。该结论只描述 Gradle 声明图，
不等于低耦合：G1-S1 已证明 UI→data 实现、app/music→具体播放器、共享 models/utils 包和
core 层塌缩仍存在。当前 V2 判断见 §0.1、§0.5、§0.7，本节不再作为“依赖图健康”依据。

重构候选（按执行策略分档；政策见 `FIX_PLAN` D-F17，重构目标 = **更大灵活度、支撑后续新增需求**）：

- 波内局部（低风险、随对应波做）：会话 / 通知展示状态收敛为单一数据类（W78）；手势「识别→意图」下沉 `PlayerSeekGestureRules`（W81）；缩略图策略收敛 `ImageCacheRules` + phone theme 3 文件并入 `core`（W83，**已完成**：图片 URL / 尺寸质量 / 占位来源 / 缓存 key 单一来源；`core` 原依赖 `data`，无循环依赖）。
- 单独立项（本批后评估）：影视 UI 分批迁入 `modes:film`（让 `app/tv` 可复用）；`PlayerViewModel` 按职责拆分；`core` 拆 `:core:design` / `:core:download`；测试面扩展（CI 单测门禁已补，Robolectric / coroutines-test 后评估）。

## 2. 目标架构总览（to-be）

### 2.1 分层原则

沿用 Android 官方分层建议（[Recommendations for Android architecture](https://developer.android.com/topic/architecture/recommendations)）：

- **UI 层**（Compose + ViewModel）：只做状态渲染与意图转发，通过 **UDF（单向数据流）** 把 Action 交给 ViewModel；
- **Domain / 业务层**：纯 Kotlin 规则（解析、配对、队列、进度换算），**必须可单测**；
- **Data 层**：Repository 作为唯一数据入口，持有 SDK / Room / 文件缓存；UI 不直接碰 SDK 与文件 IO；
- **单一数据源（SSOT）**：在线数据以服务器为准，离线数据以 Room 为准，二者由 Repository 统一对外；
- 新增逻辑优先放 Domain 纯函数（便于单测），Android 相关（Fragment、Media3、文件）只出现在 UI / Data / Player 层。

### 2.2 模块地图（新增 2 个模块）

```text
:app:phone
 ├─ :modes:film        （既有）影视模式
 ├─ :modes:book      （新增）阅读模式：书架 / 阅读页 / 阅读设置
 ├─ :modes:music       （新增）音乐模式：曲库 / 正在播放 / 队列 / 歌词 / 离线
 ├─ :settings / :setup （既有）
 ├─ :player:local      （既有，扩展音频队列能力）
 ├─ :player:core       （既有，扩展音频 / 阅读领域模型）
 ├─ :data              （既有，扩展阅读进度 / 歌词 / 音乐 / 书籍下载接口）
 └─ :core              （既有，设计系统与下载器）
```

| 新模块 | 职责 | 允许依赖 | 明确禁止 |
|--------|------|---------|---------|
| `:modes:book` | 书架（Books 库）、书籍详情入口、EPUB/PDF/CBZ 阅读页、排版与主题设置、批注与进度 | `:core`、`:data`、`:settings`、Readium 三件套 + pdfium 适配器、Compose | 不依赖 `:player:*`（阅读不播放）；不直接调 SDK |
| `:modes:music` | 音乐库浏览、正在播放、队列面板、歌词面板、离线管理 | `:core`、`:data`、`:settings`、`:player:core`、`:player:local`、Compose | 不直接调 SDK；不自己 new ExoPlayer |

> **决策 1**：Readium 只在 `:modes:book` 引入，避免污染其他模块的依赖图（`app:phone` 不直接依赖 Readium）。
> **决策 2**：音乐复用 `:player:local` 的 `PlayerHolder` 单实例（不新建第二个 Player），满足 MU-8「全 App 单 MediaSession，音视频互斥」。

### 2.3 依赖规则

| 规则 | 说明 |
|------|------|
| `data` 不得依赖任何 `modes/*`、`app/*` | 数据层向下依赖，禁止反向 |
| `player/*` 不得依赖 `modes/*` | 播放内核不认识业务页 |
| `core` 不得依赖 `modes/*` | 设计系统与工具保持通用 |
| `modes/*` 之间不得互相依赖 | 阅读与音乐通过 data / player 解耦；需要共享的模型提升到 `core` 或 `player:core` |
| UI 不得直接持有 SDK / File / ExoPlayer | 一律经 Repository / `PlayerHolder` |
| 服务器写操作不得散落在 UI / ViewModel | 未来歌单互导与元数据修改统一经 data 层的服务器写网关；能力关闭时不存在可调用写路径 |

### 2.4 咽喉文件（多线共改，必须串行保护）

| 咽喉文件 | 被哪些任务线改动 | 保护措施（建议） |
|----------|-----------------|-----------------|
| `settings.gradle.kts` | R1（注册 `:modes:book`）、R2（注册 `:modes:music`）、R3 | **一次改完两个模块注册**（由首个落地会话一次性提交），之后各线不再动 |
| `gradle/libs.versions.toml` | R1（Readium / pdfium）、R2（可能新增库）、R3 | 版本目录改动集中提交；新增库必须先过许可审查（§7） |
| 根 `build.gradle.kts`（`allprojects.repositories`） | R1（**必须新增 JitPack 仓库**给 pdfium 适配器） | 与模块注册同批提交，避免多条线各改一次 |
| `app/phone/.../NavigationRoot.kt` | R1（书籍详情 / 阅读路由）、R2（音乐路由）、R3 | 路由注册分两次小提交，改前 rebase；同日改动需互相告知 |
| `settings/.../AppPreferences.kt` | R1（阅读偏好）、R2（音乐 / 歌词偏好）、R3（界面偏好） | 前置约定 key 前缀（`reader_*` / `music_*`），一次提交只加自己前缀的 key |
| 设计系统核心（`core/.../presentation/theme/*`） | R3 主改，R1/R2 只读 | **设计系统由 R3 串行独家改动**；R1/R2 只用 token，不新增 token。**Typography / Theme / Spacing / Motion 已统一归 `core`（W83，2026-10-17）**，phone 侧 theme 目录已移除 |
| `app/phone/.../playback/CinefinPlaybackService.kt` | R2（通知行为） | 由 R2 单独改 |
| `player/local/.../presentation/PlayerHolder.kt` | R2（音频后端策略）、播放器线 | 改动前与播放器线确认；改后必须跑视频回归 |
| `core/.../utils/DownloaderImpl.kt` | R1（书籍下载）、R2（音乐下载）、下载线 | 由**一条线先做通用化**（见 §6.1），另一条线只加自己的分支 |

---

## 3. 阅读器架构（R1）

### 3.1 引擎选型结论：Readium Kotlin Toolkit 3.4.0

| 项 | 结论 | 来源 |
|----|------|------|
| 版本 | **3.4.0**（Maven Central 的 `latest` / `release` 均为 3.4.0） | <https://repo1.maven.org/maven2/org/readium/kotlin-toolkit/readium-shared/maven-metadata.xml> |
| 许可 | **BSD-3-Clause**（与 GPL-3.0 兼容） | <https://github.com/readium/kotlin-toolkit> |
| 版本对齐 | 3.4.0 要求 Kotlin 2.4.20 / compileSdk 37 / minSdk 24；本项目 Kotlin 2.4.20、compileSdk 37、minSdk 28 → **完全对齐** | README「Minimum Requirements」 |
| 传递依赖对齐 | `readium-navigator:3.4.0` 依赖 `androidx.media3:media3-*:1.11.0`、`androidx.appcompat:1.8.0`、`androidx.core:core-ktx:1.19.0`、`kotlinx-serialization-json:1.11.0`、`timber:5.0.1`；本项目为 Media3 **1.11.1**、appcompat 1.8.0、core 1.19.0、serialization 1.11.0、timber 5.0.1 → **无冲突**（Media3 补丁号向上兼容） | `readium-navigator-3.4.0.pom` |
| 需引入模块 | `readium-shared`、`readium-streamer`、`readium-navigator`（`readium-opds` / `readium-lcp` 本期不需要） | 同上 |
| PDF 适配器 | `readium-adapter-pdfium:3.4.0`，运行时拉取 **JitPack** 坐标 `com.github.marain87:PdfiumAndroid:1.9.8` 与 `com.github.marain87:AndroidPdfViewer:3.2.8` → **根 build 必须新增 `maven("https://jitpack.io")`** | `readium-adapter-pdfium-navigator-3.4.0.pom` |

能力矩阵（R1 排期与验收依据）：

| 能力 | EPUB 2/3（reflow） | EPUB（FXL） | PDF |
|------|:---:|:---:|:---:|
| 分页 / 滚动 / RTL | ✅ / ✅ / ✅ | ✅ / 👀 / ✅ | ✅ / ✅ / ✅ |
| 文本搜索 / 高亮（Decoration） | ✅ / ✅ | ✅ / ✅ | **👀 / 👀（未实现）** |
| TTS | ✅ | ✅ | **👀（未实现）** |

| 格式 | 状态 |
|------|:---:|
| EPUB 2 / EPUB 3 | ✅ / ✅ |
| PDF | ✅（依赖第三方引擎适配器） |
| CBZ | 🚧 部分实现 |
| CBR | ❓ 不支持 |
| Divina / RWPM | 🚧 / 🚧 |

来源：<https://github.com/readium/kotlin-toolkit#features>。**结论：EPUB 与 PDF 走 Readium；CBZ 按 §3.5 评估；CBR 本期不做。**

### 3.2 集成方式：View 体系 → Compose `AndroidView`

Readium 的 Visual Navigator 以 **Fragment** 形式提供（`EpubNavigatorFragment`、`PdfNavigatorFragment`），本项目 UI 是 Compose，因此：

```text
Compose ReaderScreen
 └─ AndroidView(factory = { FragmentContainerView(带 childFragmentManager) })
     └─ EpubNavigatorFragment / PdfNavigatorFragment
```

- 用 `FragmentContainerView` + `childFragmentManager` 承载 Readium Fragment；`AndroidView` 的 `onRelease` 中释放 `NavigatorFactory` 与 Fragment；
- 打开流程（官方 API）：`AssetRetriever` → `DefaultPublicationParser` → `PublicationOpener.open(asset)`（见 [Opening a publication](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/open-publication.md)）；
- 排版 / 主题设置直接映射 Readium 偏好模型（reflowable 支持）：`fontSize`、`fontFamily`、`lineHeight`、`letterSpacing`、`wordSpacing`、`pageMargins`、`paragraphIndent`、`paragraphSpacing`、`textAlign`、`columnCount`、`scroll`、`theme`、`textColor`、`backgroundColor`、`publisherStyles`、`verticalText` → 覆盖 EB-5 / EB-6 / EB-7；FXL 另支持 `spread`（双页）。来源：[Configuring the Navigator](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/preferences.md)。
- 阅读器主题（纸色 / 护眼 / 深色 / OLED）与主 App 主题**解耦**（EB-7），避免互相污染。

### 3.3 取书与落盘（离线优先）

```text
Books 库（CollectionType=books）
  → LibraryScreen（既有，includeTypes=[BaseItemKind.BOOK]）
  → BookDetailScreen（新增：封面、简介、继续阅读）
  → 点击阅读：ReaderRepository.ensureLocalFile(itemId)
       ├─ 本地已有 → 直接用
       └─ 否则 GET /Items/{itemId}/Download → 落盘
  → PublicationOpener.open(FileAsset(本地文件))
```

实测证据（2026-09-29，测试服务器 10.11.8）：

- `GET /Items?includeItemTypes=Book` 返回 1 本 EPUB（2.2 MB）；
- `GET /Items/{id}/Download` 支持 **Range 请求**：`206 Partial Content`、`Content-Type: application/epub+zip`、文件头 `PK`；
- 服务器 Books 目录结构遵循官方约定（`Books/`、`Audiobooks/`、`Comics/`）：<https://jellyfin.org/docs/general/server/media/books/>。

> **决策 3（先落盘再打开）**：EPUB / PDF / CBZ 一律先下载到应用私有目录，再用 `FileAsset` 打开。理由：① ZIP / PDF 需要随机访问，HTTP 分块读取路径长且易碎；② 离线阅读（EB-11）本来就要落盘；③ 与既有 `Downloader` / 离线体系复用。**流式打开（Readium `HttpClient` + `https` URL）列为"待验证"的后续优化，不作为首期方案。**

### 3.4 PDF 分页懒加载（硬约束，EB-3）

Readium 的 PDF 能力由第三方引擎适配器提供（[Supporting PDF documents](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/pdf.md)）：本期选 **pdfium 适配器**，其 `PdfNavigatorFragment` 负责渲染；业务侧必须满足"内存峰值与文档页数无关"的验收红线。

| 策略 | 规定 |
|------|------|
| 页窗口 | 只保留「当前页 ± 1 页」的解码结果；滚动 / 翻页时按需渲染新页 |
| 位图上限 | 内存中同时最多 **3 张**页面位图（当前 ± 1），超出按 LRU 立即回收 |
| 降采样 | 按"屏幕宽 × 设备像素比"计算目标位图尺寸并做上限截断（例如长边 ≤ 2048 px），**禁止按 PDF 原始分辨率渲染** |
| 禁止项 | ① 禁止一次性把整本 PDF 解码 / 渲染成位图；② 禁止为每页建立永久缓存；③ 禁止在 `onDraw` 内同步解码 |
| 释放时机 | 页面离开窗口立即回收；阅读页 `onRelease` 释放 `PdfDocumentFragment` 与引擎文档对象 |
| 降级 | 单页渲染失败显示占位 + 重试按钮，不崩溃、不影响翻页 |
| 验收 | 用一本 ≥ 200 页 PDF 实测：连续翻 20 页后 `dumpsys meminfo` 增量应稳定在常数区间（不随页号增长） |

> **待验证**：pdfium 适配器经 JitPack 拉取 `com.github.marain87:*`，需确认 ① CI（GitHub Actions）能访问 jitpack.io（本机实测直连 200、1.4 s）；② 应用体积增量与 ABI 支持范围；③ 中文排版 PDF 的渲染效果。
> **备选方案（未选定）**：`androidx.pdf:pdf-viewer`（Google Maven，当前最新 `1.0.0-beta01`）可独立渲染 PDF 并与 Compose 配合；若 Readium 的 PDF 导航器在懒加载上不可控，则用 `androidx.pdf` 自建 PDF 阅读页（仅 PDF 走自研，EPUB 仍走 Readium）。该方案在 R1 启动时做 1 天 spike 后定夺。

### 3.5 CBZ / CBR（EB-4，P1）

- Readium 对 **CBZ 为 🚧 部分实现**、**CBR 明确不支持**（`❓ Not planned`）。R1 启动时先做**可行性验证**：用 `AssetRetriever` 打开一个 CBZ → 能否按页 / 双页翻页、RTL、缩放，且内存可控。
- 判定标准（全部满足才用 Readium）：① 单页渲染 + 预取 ≤ 3 页；② 双页 / RTL 开关可用；③ 连续 20 页 `dumpsys meminfo` 稳定。
- 不满足则**自研 CBZ 阅读器**：`ZipFile` 随机读条目（`BitmapFactory` + `inSampleSize` 降采样）+ Compose `HorizontalPager`（左右翻页）+ 预取下一页 + 位图 LRU；窗口化参数复用 §3.4。
- 服务器测试内容暂无 CBZ / CBR（用户后续补充）→ 该任务**等素材到位后开工**；R1 先用自造样例（不入库）做实现验证。
- CBR 本期不做（Readium 不支持；自研需引入解压库，许可与体积都不划算）。

### 3.6 进度同步与批注（EB-8、EB-9）

| 项 | 设计 |
|----|------|
| 读进度（服务器） | `GET /Users/{userId}/Items/{itemId}/UserData`（**10.11.8 实测 200**，返回 `PlaybackPositionTicks`、`PlayCount`、`IsFavorite`、`Played`） |
| 写进度（服务器） | `POST /UserItems/{itemId}/UserData`，body 用 `UpdateUserItemDataDto`：`PlaybackPositionTicks`、`PlayedPercentage`、`Played`、`LastPlayedDate`（字段来自 OpenAPI，见 §9） |
| 本地精确定位 | Room 存 **Readium `Locator` 的 JSON**（spine 索引 + progression + 文本上下文），退出重进可精确回到原位置 |
| 服务器值换算 | `PlaybackPositionTicks = progression × 章节总 ticks`（Jellyfin 1 tick = 100 ns，即 **1 ms = 10 000 ticks**）；`PlayedPercentage` = progression × 100 |
| 上报时机 | 打开恢复 → 每 30 s（阅读中）→ 页面 / 章节切换去抖 2 s → 退到后台 → 关闭阅读页 |
| 离线 | 进度先写 Room"待同步队列"（含 `updatedAt`），联网后由 WorkManager 回传（复用 `SyncWorker` 思路） |
| 多设备冲突 | 默认"最近时间戳优先"（比较本地 `updatedAt` 与服务器 `LastPlayedDate`），冲突时以新者覆盖并记日志 |
| 批注（书签 / 高亮 / 笔记） | 全部存本地 Room（`reader_annotations`）；服务端无标准批注 API，导出 / 跨端同步后置 |

> **待验证**：① 与 jellyfin-web 官方阅读器的进度字段是否完全一致（用 Web 读一段后对比 `UserData` 变化）；② `POST` 写入在 10.11.8 的完整字段行为（首次实现时验证）。

### 3.7 阅读器数据流

```text
LibraryScreen / BookDetail ──(intent)──▶ ReaderViewModel
                                          │ 1. ReaderRepository.ensureLocalFile(itemId) → Downloader / Room
                                          │ 2. ReaderRepository.getProgress(itemId)     → UserData / Room
                                          │ 3. 打开 Publication（Readium）
                                          ▼
                                ReaderState(loading / ready(publication, locator) / error)
                                          ▼
                                ReaderScreen → AndroidView(Readium Fragment)
                                          ▼
                                Locator 变化 → ReaderViewModel.onAction(OnProgressChanged)
                                          ▼
                                Room（本地精确位置） + 去抖上报 UserData
```

---

## 4. 音乐架构（R2）

### 4.1 播放与系统集成（MU-4、MU-7、MU-8）

| 项 | 决策 |
|----|------|
| 播放内核 | **音频强制 ExoPlayer**（Media3），不使用 mpv。理由：gapless、音频焦点、MediaSession 生态都在 Media3；mpv 仅保留给视频兜底。实现上由 `PlayerHolder` 增加"音频会话强制 ExoPlayer"的入口（不改视频默认行为） |
| 会话模型 | 复用**单例 `PlayerHolder.player`** + 单个 `MediaSession`（`CinefinPlaybackService`，`MediaSessionService`，`foregroundServiceType=mediaPlayback`），符合官方 [Background playback with a MediaSessionService](https://developer.android.com/media/media3/session/background-playback) 的要求 |
| 音视频互斥（MU-8） | 新增 `PlaybackCoordinator`（`player/local`，`@Singleton`）：`startMusic()` 前先停视频队列并上报 `Sessions/Playing/Stopped`；`startVideo()` 前先停音乐。两条路径都必须经它，禁止直接 `player.play()` |
| 后台行为 | 音乐：后台长驻（`onTaskRemoved` 正在播放时不收摊，沿用现有逻辑）；视频：维持现状（默认离开即停） |
| 通知 / 锁屏 / 耳机 | 复用 `CinefinMediaNotificationProvider`；耳机按键与音频焦点由 ExoPlayer `setHandleAudioBecomingNoisy` / `setAudioAttributes(..., true)` 提供（已具备） |
| 睡眠定时（P1） | `player/local` 内实现（延时暂停 + 会话元数据），与视频共用 |
| 音质（MU-4） | 直连优先 + 可配置转码码率（Wi-Fi / 蜂窝分别设置）→ 走 `AppPreferences` 新增 `music_*` 前缀键；转码地址复用 `mediaSource.transcodingPath` 机制 |

gapless 说明：Media3 词汇表定义 gapless 为"跳过曲目之间的静音间隔"（<https://developer.android.com/media/media3/exoplayer/glossary#gapless-playback>）；ExoPlayer 在 MP3/AAC 上按 Xing/Info 头与编码器延迟元数据做无缝衔接（[Media3 RELEASENOTES](https://github.com/androidx/media/blob/release/RELEASENOTES.md) 中 "MP3: Use gapless-aware durations from Xing/Info headers" 等条目）。

> **待验证**：同一专辑内不同采样率 / 编码的曲目切换是否存在可感知间隙；耳机 / 蓝牙设备上的实测表现。

### 4.2 队列模型（MU-3）

```kotlin
// player/core（纯领域模型，可单测）
data class MusicQueue(
    val items: List<PlayerItem>,   // 复用 PlayerItem（name/itemId/mediaSourceId/mediaSourceUri/thumbnailUri）
    val currentIndex: Int = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,   // OFF / ALL / ONE
    val shuffleEnabled: Boolean = false,
    val source: QueueSource,       // ALBUM / ARTIST / PLAYLIST / FAVORITES / SEARCH / MANUAL
    val sourceId: String? = null,
)
```

| 能力 | 设计 |
|------|------|
| 单队列 | 全局唯一队列；换播放来源 = "替换队列 + 定位到点播曲目" |
| 手动排序 | `move(from, to)` 纯函数，UI 用拖拽列表 |
| 下一首播放 | `insertNext(item)`（插到 `currentIndex + 1`，并立即刷新播放器队列） |
| 随机 / 循环 | 由 ExoPlayer 的 `shuffleModeEnabled` / `repeatMode` 承担；队列自身的"未随机顺序"始终保留 |
| 队列保存（MU-3） | Room 表 `music_queue`（含顺序与来源），重启恢复；"多队列"后置 |
| 队列与播放器同步 | `MusicPlaybackController` 负责把 `MusicQueue` 映射为 ExoPlayer 的 media items 与索引，禁止 UI 直接 `player.addMediaItem()` |

### 4.3 歌词子系统（MU-5，重点）

**数据来源优先级**：服务端 `/Audio/{itemId}/Lyrics` → 音频内嵌歌词 → 同目录外挂 `.lrc`。

> 服务端实测（2026-09-29，10.11.8）：`aLIEz` 返回 83 行，`Start`（ticks）升序；**40 组行共享完全相同的 `Start`**（原文 + 翻译成对，如 `28440000` 两行）；另有 3 行元数据（作词 / 作曲 / 编曲，`Start` 为 0、10⁷、2×10⁷）。

**解析层（纯 Kotlin，必须单测）**

| 组件 | 职责 |
|------|------|
| `LrcParser` | 解析 LRC 文本：`[mm:ss]`、`[mm:ss.xx]`、`[mm:ss.xxx]`、一行多时间戳 `[00:01.00][00:05.00]歌词`、`[offset:+/-ms]`、ID 标签（`[ti:]` / `[ar:]` / `[al:]` / `[by:]`）、空行与注释；输出 `List<LyricLine>(startMs, rawText, isMetadata)` |
| `LyricsPairer` | 双语配对：**相同 `Start` 成对**；时间戳差 ≤ **250 ms** 视为极近，也成对；产出 `LyricBlock(startMs, primary, secondary)` 与未配对的单语块 |
| `LineLanguageDetector` | **逐行**语言检测：① 含平假名 / 片假名 → 日文；② 仅汉字 → 中文（按字形特征分简 / 繁，并保留"混合行"标记）；③ 仅拉丁字母 → 英文 / 其他；④ 中日英混合行打 `MIXED` 标并保留原文 |
| `LyricsNormalizer` | 去元数据行、去空白、去重复行、时间戳对齐（不足毫秒补齐） |

**语言判定与显示规则（对应 MU-5 能力 1–4）**

1. 语言候选列表来自"逐行检测结果聚合"（至少：简体中文、繁体中文、英文、日文、原文）；
2. 默认显示**简体中文**：存在中文（简 / 繁）行时优先展示中文侧，否则回落原文；
3. 提供"原文 + 翻译"双语对照模式；
4. 语言切换只影响显示侧，不改变配对与时间轴。

**语言检测实现**：优先规则法（上述 ①②③，零依赖、可单测）；规则法无法判定的长拉丁行再交给 `com.github.pemistahl:lingua:1.2.2`（Apache-2.0，Maven Central 已确认可获取：<https://repo1.maven.org/maven2/com/github/pemistahl/lingua/maven-metadata.xml>）。

> **待验证**：① 规则法对中英混排行（"中文行夹英文单词"）的判定准确率；② 简 / 繁区分是否需要字形表或 `Locale` 提示；③ 是否真的需要引入 Lingua（规则法达标则不引入，减小体积）。

**渲染**：`LazyColumn` + 当前行高亮 + 平滑滚动（`animateScrollToItem`），双语对照用主 / 副两行排版。

### 4.4 离线缓存（MU-6）

- 复用 `Downloader`（`DownloadManager` + `WorkManager` + Room 记录），扩展"单曲 / 专辑 / 歌单"批量下载；
- 容量管理：设置页显示占用 + 一键清理（复用现有下载页样式）；
- 离线播放：播放源指向本地文件（`PlayerItem.mediaSourceUri` 为本地 `file://`），离线模式不出网；
- 自动缓存策略后置。

### 4.5 进度上报（MU-9）

复用既有 `SessionApi`：`postPlaybackStart` / `postPlaybackProgress` / `postPlaybackStop`（`JellyfinRepository` 已封装）；音乐上报间隔 10 s，暂停立即上报，切歌先 Stop 再 Start。

### 4.6 音乐数据流

```text
MusicLibraryScreen ─▶ MusicViewModel ─▶ JellyfinRepository.getItems(...)（专辑 / 艺术家 / 歌曲 / 歌单）
                                  │
                                  └─(点击播放)─▶ MusicPlaybackController.playAlbum(albumId)
                                                     ├─ 构建 MusicQueue
                                                     ├─ PlaybackCoordinator.startMusic()（停视频）
                                                     └─ PlayerHolder.player(ExoPlayer).setMediaItems(...)
NowPlayingScreen ─▶ LyricsViewModel ─▶ LyricsRepository.getLyrics(itemId) ─▶ LrcParser / LyricsPairer / Detector
```

---

## 5. 接口定义与数据流（新增接口草案）

> 以下签名是**接口契约**（R1 / R2 可微调参数，但不得改变职责划分）。落地顺序：先提交接口 + 假实现（可编译、可单测），再并行填实现。

### 5.1 `data` 层新增

```kotlin
// data/repository/ReaderRepository.kt（新增）
interface ReaderRepository {
    suspend fun ensureLocalFile(itemId: UUID): File          // 下载 / 命中缓存
    suspend fun deleteLocalFile(itemId: UUID)
    suspend fun getReadingProgress(itemId: UUID): ReadingProgress?              // UserData + 本地 Locator
    suspend fun saveReadingProgress(itemId: UUID, progress: ReadingProgress)    // 本地写 + 去抖上报
    suspend fun flushPendingProgress()                                          // 联网回传（WorkManager 调用）
}

data class ReadingProgress(
    val itemId: UUID,
    val locatorJson: String,   // Readium Locator
    val progression: Double,   // 0.0–1.0
    val positionTicks: Long,   // Jellyfin 用
    val updatedAt: Instant,
)

// data/repository/MusicRepository.kt（新增）
interface MusicRepository {
    suspend fun getAlbums(parentId: UUID?): List<FindroidItem>
    suspend fun getArtists(parentId: UUID?): List<FindroidItem>
    suspend fun getSongs(parentId: UUID?, albumId: UUID?): List<FindroidItem>
    suspend fun getPlaylists(): List<FindroidItem>
    suspend fun getPlaylistItems(playlistId: UUID): List<FindroidItem>
    suspend fun getLyrics(itemId: UUID): RawLyrics?      // 服务端 → 外挂 → 内嵌 的合并结果
    suspend fun downloadSong(itemId: UUID)
    suspend fun downloadAlbum(albumId: UUID)
    suspend fun downloadPlaylist(playlistId: UUID)
}
```

对 `JellyfinRepository` 的增量（保持既有接口不破坏）：

| 方法 | 用途 | 备注 |
|------|------|------|
| `getUserItemData(itemId)` | 阅读进度读取（`/Users/{userId}/Items/{itemId}/UserData`） | 10.11.8 实测可用 |
| `updateUserItemData(itemId, positionTicks, playedPercentage)` | 阅读进度写回（`POST /UserItems/{itemId}/UserData`） | 走用户数据白名单 |
| `getLyrics(itemId)` | `/Audio/{itemId}/Lyrics` | SDK 1.8.12 已生成 `LyricsApi.getLyrics`（实测类：`org/jellyfin/sdk/api/operations/LyricsApi`） |
| `getBookFileUrl(itemId)` | `/Items/{itemId}/Download` | 支持 Range |

### 5.2 `player` 层扩展

| 位置 | 新增 | 说明 |
|------|------|------|
| `player/core/domain/models` | `MusicQueue`、`RepeatMode`、`QueueSource` | 纯模型，可单测 |
| `player/local/domain` | `MusicPlaybackController` | 音乐队列 → ExoPlayer 的唯一入口 |
| `player/local/domain` | `PlaybackCoordinator` | 音视频互斥仲裁（MU-8） |
| `player/local/presentation` | `PlayerHolder.audioSession()`（暂名） | 音频会话强制 ExoPlayer 的入口，不改视频行为 |
| `modes/music/domain/lyrics`（建议位置） | `LrcParser`、`LyricsPairer`、`LineLanguageDetector`、`LyricsNormalizer` | 纯函数，随音乐业务演进；也可放 `player/core` |

### 5.3 能力复用对照

| 需求 | 复用 | 新增 |
|------|------|------|
| 书籍下载 | `Downloader` / `DownloaderImpl`（需扩 Book / Audio 分支） | 书籍 / 音频单文件下载分支 |
| 音乐播放 | `PlayerHolder`、`CinefinPlaybackService`、`CinefinMediaNotificationProvider` | `MusicPlaybackController`、`PlaybackCoordinator` |
| 阅读进度 | `JellyfinRepository` + Room + WorkManager | `ReaderRepository`、待同步队列 |
| 歌词 | `JellyfinApi`（SDK `LyricsApi`） | LRC 解析 / 配对 / 语言检测 / 缓存 |
| 离线 | `JellyfinRepositoryOfflineImpl`、Room | 音乐 / 书籍离线索引 |

---

### 5.4 连接层：自签证书信任（W31-CONN）

**背景**：Android 默认拒绝不受系统信任的证书，自签 HTTPS 的 Jellyfin 服务器此前直接连接失败。

**方案（TOFU，Trust On First Use）**：不关闭校验，只叠加「用户显式确认过的指纹」：

| 组件 | 位置 | 职责 |
|------|------|------|
| `CertificateFingerprint` / `TrustKey` | `data/.../network` | SHA-256 指纹归一化 / 格式化 / 匹配、`host:port` 信任键（纯函数，单测覆盖） |
| `CertificateTrustStore` | `data/.../network` | 「信任键 → 指纹」持久化；`SharedPreferencesCertificateTrustStore` 写入应用私有 `cinefin_trusted_certificates` |
| `ToFuTrustManager` + `TrackedSslSocketFactory` | 同上 | 系统校验失败时，仅当「当前连接地址」的指纹被信任才放行；OkHttp 5 建连前调用 `createSocket(raw, host, port, autoClose)`，包装工厂据此记录 host 供 trust manager 判定 |
| `ServerCertificateProbe` | 同上 | 只读 TLS 握手取出服务器证书指纹；探测用的一次性「接受任意证书」信任管理器不用于任何业务流量 |
| `CertificateTrustRequiredException` | 同上 | 首次连接 / 证书变化时携带指纹交给 UI 确认 |

- **接入点**：`JellyfinApi` 通过 `OkHttpFactory(自定义 OkHttpClient)` 注入 `apiClientFactory` / `socketConnectionFactory`（jellyfin-core 1.8.12 支持）；Coil 图片加载与 `ReaderRepositoryImpl` 书籍下载复用同一信任仓库。
- **UI**：添加服务器、登录页展示 `host:port` + 完整 SHA-256 指纹（证书变化时额外警告）；服务器页「已信任证书」弹窗可随时清除，清除后对应地址立即回到默认拒绝。
- **边界**：主机名（SAN/CN）校验保持 OkHttp 默认——自签证书必须把访问用的主机名 / IP 写进 SAN；未接入：WebView 控制台（`ConsoleViewModel`）与 `ImagesDownloaderWorker`。
- **多用户（W31 审计）**：Room `users` 表 + `userdata(userId,itemId)` 已按用户隔离，`UsersScreen` 列表 / 切换 / 添加 / 删除入口位于设置页与抽屉；W31 修复「删除当前用户留下悬空 `currentUserId`」（有剩余用户顺延、否则清空令牌），并在列表标出当前账号；管理员缓存本身按账号 id 校验，不跨账号串号。

#### 5.4.1 K60 真机联调（2026-10-02）

- **环境**：PC 上起自签 HTTPS 服务（局域网 `10.71.41.146:8444`，证书 SAN `IP:10.71.41.146`，SHA-256 `BE:C8:E7:82:19:DC:CA:FD:A8:3D:76:59:E1:65:A3:E1:1C:78:19:ED:0E:84:2E:07:51:A5:B4:21:C3:5B:31:D0`），K60 走局域网直连（`http=200` 预检通过）。
- **证书流程**：添加服务器 → 弹窗显示完整指纹（与 PC 一致）→ 信任并继续 → 服务器识别成功（`GET /System/Info/Public` 到达本地服务）；清除信任后**同进程**再连 → 立即 `SSLHandshakeException: Trust anchor ... not found` + 弹窗重现（无业务请求外发）；换第二张同 SAN 新证书 → 弹窗显示新指纹 + 「警告：证书已发生变化」+ 此前指纹；恢复原证书后旧信任仍可用（`POST /Users/AuthenticateByName` 到达）。
- **真机发现并修复**：TLS 客户端会话缓存会让「清除信任」后的下一个连接走会话复用、跳过 trust manager。`CertificateAwareOkHttp` 现将证书感知客户端的 `SSLSessionContext` 限制为 1 条 / 1 秒（`sessionCacheSize=1`、`sessionTimeout=1`），保证每次连接重新做完整校验；修复分支 `fix/w31-trust-session-reuse`（待负责人合并）。
- **多用户（K60 + 生产服务器）**：admin `zhangwenkang` 与第二个普通用户分别登录成功；Home「继续观看」仅 admin 出现（进度按用户隔离），`admin → test → admin` 来回切换正常；用户列表标出「当前」账号；删除当前用户（test）后 `servers.currentUserId` 顺延到 admin（设备 DB 实测）且无崩溃；错误密码显示「用户名或密码错误」；整轮 0 FATAL / ANR。
- **测试服务备注**：PC 侧最终用 Python/OpenSSL 起服务；JBR `com.sun.net.httpserver.HttpsServer` 与 Android Conscrypt 的 engine socket 在 IP 直连时握手卡住（测试服务自身互通问题，与客户端无关）。
- 遗留：WebView 控制台（`ConsoleViewModel`）与 `ImagesDownloaderWorker` 仍未接入信任；自签证书必须包含访问地址的 SAN。

---

### 5.5 元数据缓存与「缓存优先 + 静默刷新」（W69）

**背景**：用户 2026-10-04 复验反馈——刚进首页 / 从季（剧集）详情返回库界面时会触发元数据加载，加载期间**整面海报变黑 / 空白**（「元数据加载与缓存策略要优化，要优先使用本地已缓存的元数据，从服务器加载元数据时，要静默」）。

**规则**（`data/.../repository/MetadataCache.kt`，纯函数 + 常量集中定义，单测 `MetadataCacheTest` 12 项）：

| 规则 | 值 / 行为 |
|------|-----------|
| 列表 / 详情 TTL | `MetadataCacheRules.DEFAULT_TTL_MS` = **10 分钟**（任务书建议 5–15 分钟） |
| 分页页片 TTL | `MetadataCacheRules.PAGING_TTL_MS` = **5 分钟**（分页内容更"活"，更快看到新数据） |
| 容量 | `DEFAULT_MAX_ENTRIES` = 256 条（LRU 淘汰；进程内存，不持久化） |
| TTL 内 | **直接复用、不发请求**（Timber `metadata cache hit: <key>`） |
| TTL 外 | 页面保留已上屏内容，读路径重新请求并回填（`metadata cache miss (refreshing)`）——刷新期间不清列表、不置空图片 |
| 强制刷新 | `JellyfinRepository.invalidateMetadataCache()`：下拉刷新 / 重试 / 排序筛选变化先调用，再照常读取 |
| 用户动作失效 | 收藏 / 取消收藏 / 标记已看 / 取消已看 / 播放结束（`postPlaybackStop`）→ 全量失效（状态内嵌在条目里） |

**实现落点**：

- `MetadataCache`：会话级（`JellyfinRepositoryImpl` 是 `@Singleton`）进程内存缓存，键按「服务器地址 + 用户 id」**命名空间隔离**（切换服务器 / 账号不串数据）；LRU 表加锁访问、时钟可注入（单测）。
- `MetadataCacheKeys`：每个读方法的键（`views` / `latest:<库>` / `resume:<类型>` / `nextup:<剧>` / `items:<库|类型|排序|过滤|分页窗口>` / `show|season|movie|episode:<id>` / `seasons` / `episodes` / `favorites:<排序>` / `search:<关键词>` / `count` / 库内建议 / 即将播出 / 类型 / 制片发行商 / 演职人员条目），前缀常量同时用于分组失效。
- 覆盖读方法：`getUserViews` / `getLibraries` / `getItem(s)`（含分页页片）/ `getItemCount` / `getLatestMedia` / `getResumeItems` / `getNextUp` / `getSuggestions` / `getFavoriteItems` / `getSearchItems` / `getLibrarySuggestions` / `getUpcomingEpisodes` / `getGenres` / `getStudios` / `getPersonItems` / `getShow` / `getSeason` / `getMovie` / `getEpisode` / `getSeasons` / `getEpisodes`。
- **不缓存**：播放链路（`getMediaSources` / `getStreamUrl` / `getSegments` / `getTrickplayData`）与下载页自有短 TTL 链路（`getDownloads` / `getPrimaryImageUrl` / `ImagesDownloaderWorker`）。

**页面层不变量**（后续新增列表 / 详情页照此办理）：

1. **无缓存才骨架**：骨架只在"真的没有任何可渲染内容"时显示（首页 `hasRenderableContent`；库内容页 `items.itemCount == 0`；详情页 `model == null`）。
2. **不重建分页流**：`VideoViewModel.load()` / `LibraryViewModel.loadItems()` 在 TTL 内直接复用已上屏的 `Pager`（`LazyPagingItems` + `cachedIn` 的数据原样保留）；TTL 外只发 `refreshSignal` 触发 `LazyPagingItems.refresh()`——保留现有条目，新页回来原地替换。
3. **图片不置空**：海报 / 剧照 / 头图统一走 `RetainedAsyncImage`（`app:phone`）——模型变化时继续画上一张成功加载的图，新图就绪后 160ms 交叉淡入；失败回落占位，绝不先把海报变成黑底。
4. **列表就地更新**：走廊 / 网格使用稳定 `key`（`item.id`），数据刷新只增删真正新增 / 删除的行。

**取舍与未覆盖**：进程重启后没有内存缓存（首次进入仍按网络加载 → 骨架，任务书口径允许）；TTL 期间服务器新增条目要等下拉刷新或 TTL 过期；缓存不做持久化（Room 快照 / 上次会话快照留给后续按需）。

#### 5.5.1 W69b 补充：预加载、并发去重与刷新冷却

| 机制 | 约定 |
|------|------|
| 预加载（[MetadataPreloader](../../data/src/main/java/com/zhangwenkang/cinefin/repository/MetadataPreloader.kt)） | 首屏渲染后**低优先级**预取（`Dispatchers.IO.limitedParallelism(2)`）：首页英雄卡 + 走廊 / 海报墙前 6 项详情；库内容页第二页（与 Paging 同 key）；失败静默；`cancel(source)` 按来源取消（页面 `onCleared` 调用） |
| 并发去重（`InFlightRequests`） | 同 key 同时只跑一个请求：第一个调用方执行、其余等待同一 `CompletableDeferred`；领头失败时等待方自行补一次（不互相牵连） |
| 刷新冷却（`MetadataCacheRules.MIN_REFRESH_COOLDOWN_MS` = 30 s） | `MetadataCache` 记录每 key 最近一次**请求发起**时间：缓存过期但仍在冷却窗口内 → 直接复用旧值、不打服务器；下拉刷新 / 重试走 `invalidateMetadataCache()`（清缓存与冷却记录）不受限；库内容页「静默重取」信号受同一冷却 |
| 分页页片大小 | `ItemsPagingSource.PAGE_SIZE = 10` 单一常量（Paging 与预取共用，保证缓存键一致） |

**书籍封面完整回退链（W69b，首页 / 书架 / 下载页同源）**：服务器图 → 本地已有 → **本地生成**（未下载在线书籍也生成：PDF 首页 / CBZ 第一图 / EPUB 封面，`Items/<id>/Download` HTTP Range 懒生成）→ 风格化类型占位；**服务器给了 URL 但取图 404 / 失败同样回落到生成**——`BookCoverRules.planCover(serverImageUnavailable = true)` + 卡片 `onServerImageFailed` 回调触发 `requestBookCoverFallback`，生成结果贴回卡片；失败写 `.fail` 不再重试。

**W69c 补记（无图卡片不出现黑卡）**：所有「书 / 音乐」卡片组件（`PosterItemCard` / `LandscapeItemCard` / `ItemPoster`）把**类型占位常驻底层**——书籍 = `ic_book` + 媒体色底（`BookCoverPlaceholder`），音乐 = `ic_music` + 媒体色底（与 W59 下载页 / W68 媒体会话同口径），无图 / 加载中 / 加载失败都露占位，服务器图或生成的本地封面就绪后覆盖在上层；「最新 · <库名>」走廊（`HomeView`）与走廊 / 海报墙统一接线（本地封面 override / 占位图标 / 懒生成请求 / 服务器图失败回落），书籍回调只对 `FindroidFolder` 触发；按库类型选占位的映射是纯函数 `libraryPlaceholderIconRes`（单测覆盖）。

### 5.6 服务器写入与歌单互通扩展点（1.4.0 只预留，不实现）

**边界**：当前生产测试服务器 `jellyfins.zhangwenkang.com` 仍为强只读；1.4.0 不增加写按钮、不发送写请求、不保存服务器凭据副本。未来只能由用户明确启用的测试服务器环境进入写路径。

目标接口形态（名称可调整，职责不可散回 UI）：

```text
ServerCapabilities
  -> supportsPlaylistWrite / supportsMetadataWrite / readOnly

ServerMutationIntent
  -> PlaylistCreate | PlaylistReplace | PlaylistAppend
  -> MetadataUpdate

ServerMutationGateway
  -> preview(intent): MutationPreview
  -> execute(intent, idempotencyKey): MutationResult

MutationPolicy
  -> writeEnabled = false（默认）
  -> allowDestructive = false（默认）
  -> requireDryRun = true
```

| 扩展点 | 约定 |
|--------|------|
| 能力探测 | 服务器地址 / 版本 / 用户权限决定 available；UI 不按 Jellyfin 版本号硬编码按钮 |
| 写开关 | `writeEnabled` 默认 false；生产服务器即使误配也必须被硬性拒绝；测试服务器启用需显式配置 |
| 幂等与审计 | 每次写意图携带稳定 `idempotencyKey`；本地保留结果审计，不把令牌写入日志 / 记忆 |
| 预览与回滚 | 歌单互导先预览新增 / 冲突 / 缺失项；破坏性操作默认不允许，无法回滚的操作需用户显式确认 |
| 歌单来源 | `PlaylistRef = Local(id) or Server(serverId,id)`；`TrackRef = Local(localLibraryId,itemId) or Server(serverId,itemId)`，标题 / 路径不能充当稳定身份 |
| 交换格式 | Room 本地模型为真源；版本化 JSON 保存完整身份与顺序；M3U8 仅作兼容适配器，匹配失败保留不可用项 |
| 认证基线 | Jellyfin 12.2 的自定义 HTTP 统一使用 `Authorization: MediaBrowser Token="…"`；SDK 路径跟随 SDK |

**本批验收**：文档与依赖方向明确、默认只读策略可验证、未来写能力无需推翻本地歌单 / 下载 / 导航模型即可接入；不要求存在可用的写实现。

## 6. 并行开发边界（供 S3 排期）

### 6.1 必须串行（有硬依赖）

1. **第 0 步（一次做完，单会话）**：`settings.gradle.kts` 注册 `:modes:book` + `:modes:music`；根 `build.gradle.kts` 加 JitPack 仓库；`gradle/libs.versions.toml` 加 Readium 版本与坐标；两个新模块的空骨架（`build.gradle.kts` + 空包）→ 保证 `assembleDebug` 通过。**此后除依赖版本升级外不再有人动这三个文件。**
2. **接口先行**：`data` 层新增接口（§5.1）+ `player/core` 新模型（§5.2）先合入，R1 / R2 才能并行填实现。
3. **下载器通用化**：`core/utils/DownloaderImpl.kt` 由一条线先完成"通用条目下载"重构，另一条线再加自己的分支。
4. **`NavigationRoot.kt`**：两次小提交（阅读路由、音乐路由），改前 rebase。
5. **设计系统**：由 R3 独家串行改动 `core` 的 theme 文件；R1 / R2 只消费 token（W83 后 phone 侧 theme 目录已移除）。

### 6.2 可并行（无硬依赖）

| 并行组 | 说明 |
|--------|------|
| R1 阅读内部 | EPUB 渲染、PDF 懒加载、CBZ 评估、进度同步可分别成任务并行（同一会话内按 1–2 项串行做） |
| R2 音乐内部 | 曲库浏览、队列模型（纯逻辑）、歌词解析 / 配对（纯逻辑）、离线下载可并行（纯逻辑可最先开工） |
| R3 UI 落地 | 与播放器线解耦的页面（首页 / 媒体库 / 设置）可与 R1 / R2 并行；**动咽喉文件时排队** |
| R4 测试 | 测试数据准备、性能基线测量、真机矩阵脚本可与开发并行 |

### 6.3 依赖顺序（关键路径）

```text
设计系统 token（R3） ─┬─▶ 阅读页 UI（R1）
                      └─▶ 音乐页 UI（R2）
模块注册 + 接口骨架（第 0 步）─┬─▶ EPUB 阅读闭环（R1）
                              ├─▶ 音乐播放闭环（R2）
                              └─▶ 歌词纯逻辑（R2，可最早开工）
PDF 懒加载 spike（R1）──▶ 走 Readium PDF 或自研 PDF（二选一）
```

---

## 7. 风险与待验证清单

| # | 风险 / 待验证 | 影响 | 处理 |
|---|--------------|------|------|
| 1 | pdfium 适配器依赖 **JitPack**（`com.github.marain87:*`） | 构建可用性 | 本机直连实测 200 / 1.4 s；CI 首次 PR 时验证；失败则启用 `androidx.pdf` 备选 |
| 2 | 应用体积：Readium（navigator / streamer / pdfium）+ pdfium native | APK 体积劣化 | 仅 `:modes:book` 引入；PR 时对比 `assembleLibreDebug` 体积，超阈值评审说明 |
| 3 | Readium `readium-navigator` 依赖 fragment / constraintlayout / webkit 等 View 体系库 | 与 Compose 混用复杂度 | `AndroidView` 封装成单一 Composable，Fragment 生命周期收敛在容器内 |
| 4 | CBZ 在 Readium 为 🚧 | 漫画需求 | R1 先做可行性 spike，不达标自研（§3.5） |
| 5 | 阅读进度字段与官方 Web 阅读器是否一致 | 跨端进度 | Web 读一段后对比 `UserData` 变化（待验证） |
| 6 | 当前 Jellyfin 12.2.0 与历史 10.11.8 实测 / SDK 规范可能存在差异 | 接口或鉴权调用失败 | 新功能以 12.2.0 只读实测为准；认证统一 `Authorization: MediaBrowser Token="…"`；历史 10.11.8 结论只作沿革，接口变化先只读复测 |
| 7 | 歌词语言检测准确率（中英混排行） | 验收样例 | 用 `aLIEz` / `Brave Shine` / `爱的回归线` 三例做基线；必要时引入 Lingua |
| 8 | 音频 gapless 在不同编码 / 采样率下表现 | MU-4 验收 | 真机听感 + 日志验证；不达标时把 crossfade 提前评估 |
| 9 | `PlayerHolder` 单例被音乐复用后视频回归 | 稳定性 | `PlaybackCoordinator` 全量接管启停；每次改动跑视频冒烟 |
| 10 | 新依赖许可（GPL-3.0 兼容性） | 合规 | Readium **BSD-3-Clause** ✅、PdfiumAndroid / AndroidPdfViewer **Apache-2.0** ✅、Lingua **Apache-2.0** ✅；任何新库进 PR 前必须核许可 |

---

## 8. 需求条款映射

| 需求 | 本文章节 | 关键结论 |
|------|---------|---------|
| EB-2 EPUB | §3.1–3.3 | Readium 3.4.0 + 先落盘再打开 |
| EB-3 PDF 懒加载 | §3.4 | 页窗口 ±1、位图 ≤3、降采样、禁止整文档渲染 |
| EB-4 CBZ | §3.5 | Readium 🚧 先验证，不达标自研；CBR 不做 |
| EB-5 / 6 / 7 排版与主题 | §3.2 | 直接映射 Readium 偏好；阅读器主题与主 App 解耦 |
| EB-8 批注 | §3.6 | 本地 Room，服务端无标准 API |
| EB-9 进度同步 | §3.6 | UserData（ticks / percentage）+ 本地 Locator + 离线队列 + 最近时间戳 |
| EB-10 入口改造 | §2.2、§6.1 | 替换 `Book → ConsoleRoute` 分支为原生阅读路由 |
| EB-11 离线 | §3.3、§4.4 | 复用下载体系 |
| MU-1 / 2 入口与曲库 | §2.2、§4.6 | 独立 `:modes:music` + 抽屉入口 |
| MU-3 队列 | §4.2 | 单队列 + 排序 + 下一首 + 保存 |
| MU-4 音质 / gapless | §4.1 | 音频强制 ExoPlayer；gapless 依赖 Media3 |
| MU-5 歌词 | §4.3 | 同戳 / 极近成对 + 逐行语言检测 + 默认简体中文 |
| MU-6 离线 | §4.4 | 复用下载器 |
| MU-7 系统集成 | §4.1 | 复用 MediaSessionService / 通知 |
| MU-8 会话互斥 | §4.1 | 单 Player + `PlaybackCoordinator` |
| MU-9 进度上报 | §4.5 | 复用 `Sessions/Playing*` |

---

## 9. 来源

**本项目**

- `docs/REQUIREMENTS.md`（v1.0，唯一需求基线）
- `docs/PROJECT_PLAN.md`、`docs/DEV_ENVIRONMENT.md`、`docs/design/s1-decision.md`
- 代码实测：`settings.gradle.kts`、根 `build.gradle.kts`、`core/build.gradle.kts`、`data/build.gradle.kts`、`player/*`、`modes/film/*`、`app/phone/.../NavigationRoot.kt`、`app/phone/.../playback/CinefinPlaybackService.kt`、`.github/workflows/*`

**外部（均经代理 30001 抓取，2026-09-29）**

- Android 架构建议：<https://developer.android.com/topic/architecture/recommendations>
- Readium Kotlin Toolkit：<https://github.com/readium/kotlin-toolkit>（能力矩阵）、<https://readium.org/kotlin-toolkit>
- Readium 指南（`develop` 分支）：`docs/guides/pdf.md`、`docs/guides/open-publication.md`、`docs/guides/navigator/navigator.md`、`docs/guides/navigator/preferences.md`
- Readium 制品与版本：<https://repo1.maven.org/maven2/org/readium/kotlin-toolkit/>
- Jellyfin OpenAPI（stable，抓取到 12.1.0 规范）：<https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json>
- Jellyfin Books 目录约定：<https://jellyfin.org/docs/general/server/media/books/>
- Media3 后台播放：<https://developer.android.com/media/media3/session/background-playback>
- Media3 词汇表（gapless）：<https://developer.android.com/media/media3/exoplayer/glossary#gapless-playback>
- Media3 发布说明（gapless 相关条目）：<https://github.com/androidx/media/blob/release/RELEASENOTES.md>
- androidx.pdf 版本（备选方案）：<https://dl.google.com/android/maven2/androidx/pdf/pdf-viewer/maven-metadata.xml>
- JitPack：<https://jitpack.io>（`com.github.marain87:PdfiumAndroid:1.9.8`）
- Lingua 语言检测：<https://repo1.maven.org/maven2/com/github/pemistahl/lingua/maven-metadata.xml>

**服务器实测（只读 + 用户数据白名单）**

- `GET /Items?includeItemTypes=Book`、`GET /Items/{id}/Download`（206 Range）、`GET /Users/{userId}/Items/{itemId}/UserData`、`GET /Audio/{id}/Lyrics`（aLIEz 83 行 / 40 组同戳配对）
