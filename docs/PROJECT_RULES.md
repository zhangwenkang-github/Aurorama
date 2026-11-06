# Aurorama 2.0 项目规则

> 本文件只放可执行规则，不放项目进度或长篇复盘。规则冲突时先读 `AGENTS.md` 并按 `V2_PLAN.md` 的优先级停止报告。

| ID | 规则 | 触发条件 | 违反后果 |
|---|---|---|---|
| `R001` | 每个 2.0 会话按 `V2_PLAN` 交接段读入口、规则、教训索引、活跃任务、决策和本次需求白名单；外部需求只读指定行段 | 任一 2.0 需求、设计、实现、QA、集成或发布会话 | 停止决策与实现，补齐读取声明后重来 |
| `R002` | 未形成来源、解读、验收、分类和依赖的需求不得进入任务卡或实现 | 新增/修改需求、发现隐含范围 | 退回需求门，不得以“先做再补”开工 |
| `R003` | 澄清轮次无总上限；每轮只问当前前沿，事实由团队调查，不把可查证问题推给用户 | 需求存在歧义、冲突或未知事实 | 停止落档；不得用“最多三问”或自行假设跳过关键分支 |
| `R004` | 任务卡必须包含目标、非目标至少两项、接口 / 文件边界、依赖、验收、适用规则、测试命令 / 证据、风险、分支和停止条件 | 任何实现/诊断/QA/Release 任务派单 | 不派单；已派单则撤回 |
| `R005` | 产品范围、用户可见行为、UX、数据兼容、服务器写、外部推送和不可逆决策由用户拍板；内部可逆细节由团队留痕 | 任何决策分叉 | 超出权限的决策作废并回到用户 |
| `R006` | 原始需求默认全部进入候选范围，`MUST/SHOULD/LATER/OUT` 变化必须有用户确认和来源记录 | 排期、范围压缩、资源不足、发现冲突 | 不得静默延期、删除或伪装成已完成 |
| `R007` | UI/UX 先出信息架构和低保真交互，用户确认后再做视觉与实现；最终视觉由独立视觉 QA 验证 | 新页面、重设计、导航或交互变化 | 退回设计确认门，已实现部分不计交付 |
| `R008` | 不复制来源不明代码；新依赖必须有明确许可证、维护状态、集成成本和回滚路径 | 引入库、引擎、组件或开源实现 | 不合并依赖或代码 |
| `R009` | 数据模型与迁移必须保持旧安装可升级；破坏性降级、清库或猜测迁移禁止用于真实用户数据 | schema、身份、下载 provenance、歌单、进度、书签或缓存变化 | 停止实现/QA；按数据风险 P0/P1 处理 |
| `R010` | 每个跨界面事实必须有唯一状态真源和跨界面等式；不得用容器数、缓存或界面局部状态冒充领域状态 | 下载、播放、队列、离线、进度、通知、批量操作 | 任务卡不得通过；补领域不变量和契约测试 |
| `R011` | 服务器/API/事件无法表达的输入或失败必须显式降级并告知用户，不得伪造成功 | 协议能力不足、错误码未知、功能不可观测 | 标记 `BLOCKED_CAPABILITY`，不得判 PASS |
| `R012` | 实现与独立 QA 必须使用不同全新代理；实现者不得自派 QA、review、merge-prep 或 Release | 功能实现收口 | 当前实现不计完成，重启独立 QA 分权 |
| `R013` | 核心用户旅程必须在候选包和真实入口上执行通过后，才可制作截图、发布文案、资产和最终验收材料 | 每个交付波或发布准备 | 发布材料全部作废，回到真实链路 QA |
| `R014` | 测试结论必须证明实际执行的测试、设备/serial、fixture、断言、退出码和产物；`exit 0`、XML 数量或 Worker `SUCCESS` 不足以证明链路完成 | 单元/UI/设备/下载/播放/阅读验证 | 判 `INCONCLUSIVE` 或 `NOT_RUN`，不得声称 PASS |
| `R015` | “完成”要求进入 `codex/v2-integration` 并通过集成态门禁；任务分支自测通过只算候选 | 实现/诊断/修复任务收口 | 保持 Review/In Progress，不得移入 Done |
| `R016` | 凭据值只从 `F:\Develop\codex_work\Documents\Codex\资源.txt` 按需读取；仓库、日志、报告、截图、命令回显和记忆只记录位置 | 服务器、代理、登录、网络或测试环境操作 | 立即停止、清理泄漏、按安全问题处理 |
| `R017` | 生产服务器只读；服务器写测试只可针对独立测试写入服务器，并在任务卡声明对象、动作与清理 | 任何服务器 API/UI 写操作 | 未授权写即停止并报告；不得用“非敏感数据”扩大授权 |
| `R018` | 任一时刻每个设备只能有一个 ACTIVE 锁；使用捕获取值和恢复原值，禁止硬编码恢复 | adb、真机、模拟点击、安装或设备设置修改 | 不执行设备任务；现锁释放并记录漂移 |
| `R019` | 所有终端、脚本、ADB 包装和取证一律使用 PowerShell 7，UTF-8 XML/JSON 显式按 UTF-8 读取 | 任何命令或脚本 | PowerShell 5.1 结果作废，重跑或停止 |
| `R020` | 上下文到 30 万停止扩项并完成当前最小收口 / 完整检查点；到 35 万只有在存在已授权、具体、可立即执行的后继任务且 R028 PASS 时创建新负责人会话。若只剩等待用户、设备解锁或外部状态，写检查点后停止，用户恢复后再创建 | 长需求、实现、QA、诊断或交接 | 停止扩项；不得为空闲会话或无具体动作创建 / 保留后继 |
| `R021` | 每个需求域和交付波实时更新 `V2_PLAN` / `TASKS` / `DECISIONS`；新坑经确认后写 `LESSONS`；跨项目经验才进 Engramory | 决策、进度、踩坑、任务状态变化 | 阶段不得收口 |
| `R022` | 外部 push、tag、GitHub Release、上传资产和正式发布必须单独获得用户明确授权 | 任一远端或发布动作 | 视为越权；停止并报告影响 |
| `R023` | 除已规定权威文件外，不新建零散说明文档；临时分析进入对话、任务报告或对应需求域 | 需要记录设计、进度、复盘或交接 | 合并到权威文件，临时文档不得作为交付证据 |
| `R024` | 阻塞必须说明对象、复现、已尝试的可逆动作、根因边界和下一步；单点 harness 失败可在 5–10 分钟内由同一 owner 做一次受控重试 | 工具失败、环境失败、证据不足 | 不得机械判 BLOCKED，也不得无限重试或为每个小修复新建代理 |
| `R025` | 每份阶段报告分列“已实际执行 / 未覆盖 / 未执行推断 / P0–P3 / BLOCKED / 证据指针” | QA、review、merge-prep、Release 或阶段收口 | 缺少任一维度不得宣告 PASS |
| `R026` | 主负责人可直接通读本次需求明确指定的必要章节、ACTIVE 与相关权威文档；子代理主要用于 Git / 哈希门、事实核验、交叉检查、只读诊断和压缩取证 | 需求收敛、方案、诊断、review 或交接会话 | 不得以“必须派子代理”为由回避必要正文；也不得无目的通读无关或越界材料 |
| `R027` | 负责人每波先按专业角色拆出可委派工作；调研、证据整理、诊断、实现、QA、文档 / 交接整理优先派全新 `deepseek-flash / reasoning_effort=max / fork_turns=none` 子代理，负责人只保留范围、白名单、压缩报告 / 关键 diff 审查、集成与门控 | 任一负责人波次开工、上下文预计明显增长或出现可委派专业工作 | 未先拆单即执行可委派工作，不得宣告该波完成或进入下一阶段；无法派子代理且工作会显著扩大负责人上下文时，立即停止并报告 `BLOCKED` |
| `R028` | 交接固定顺序：当前最小可交付波次收口或明确 `BLOCKED` → 写回任务线 / 任务板 / 决策 / 日志 / 证据与唯一 `ACTIVE` → 提交并确认 clean → Engramory sync + checker/doctor → 旧会话 fresh read-only pre-handoff audit `PASS` → 创建后继并派具体任务。可交接须同时满足：无运行中的实现 / QA / 审查代理；设备锁已释放或明确移交；下一任务已授权、具体、可立即执行、无需旧会话补材料；prompt 自包含。设备在线 / keyguard 只核验下一任务实际使用的设备；历史或无关设备离线不得阻塞。仅等待用户 / 设备 / 外部状态不得创建。新会话 startup gate 后做最小对象 spot-check，并直接执行第一项实际任务；不得等待前任补文档，旧会话创建后不得再改实质工作树。thread id 不作为后继门 | 交接准备、pre-handoff audit、新建会话或后继开工 | 任一硬门未过、处于等待态、空后继或 prompt 不完整时不得创建；不得在同一新线程等前任补材料，禁止为满足 35 万数字制造空会话，也禁止让无关设备状态制造假阻塞 |
| `R029` | 新会话第一条只读动作必须实际运行 `pwsh -NoProfile -File tools/v2-handoff/Invoke-HandoffPreflight.ps1 -EmitPrompt`，不得只读说明代替执行。preflight 只读、离线、显式 UTF-8、LF，且必须机械校验 `HANDOFF_BOOTSTRAP_V2` 必填字段 / 未知值 / 完整角色表 / `validation.handoffPhase` / `auditReceipt` / 设备顺序 / 10 分钟窗 / APK SHA / 工具摘要 / 唯一 ACTIVE；canonical gate lifecycle 的每门必须含精确 `id / owner / requiredBefore / status`，下游 PASS 必须等待全部前置门 PASS；prompt 必须用 `actionClass=READ_ONLY_PREFLIGHT` + 精确参数数组逐项命中，不接受任意 `pwsh -NoProfile` 前缀。POST 还要从 audited source commit 的固定 `commit:path` 以严格 UTF-8/LF 读取合同 JSON，对 source PRE 与 current POST 分别执行完整断言，再递归校验 mutation pointer allowlist 与 `immutableStateSha256`；QA dispatch 只能排在合法 POST audit receipt 后 | 任一 Aurorama 2.0 交接或新会话开工 | 非零、`FAIL_CLOSED`、`owner / 门序 / 状态 / 前置门 / actionClass / 参数 / 阶段 / receipt / immutable hash` 漂移，source 读取歧义 / UTF-8 / LF 失败，或出现未知顶层字段与非白名单 pointer 时停止；不得绕过门禁继续读 raw、实现、QA、commit 或 Release |
| `R029A` | 激活文档与五个文本 anchor 的 PRE/POST exact token 必须硬编码在 preflight 的 phase 常量；合同只保存 `ACTIVE_SECTION` 与稳定 anchor id/path。`requiredTextAnchors` 与 `activeRequiredTokens` 在 POST mutation 中均为 immutable，`Assert-Tokens` 只接受去空白后长度至少 2、无通配符且逐字 `Ordinal Contains` 的 token | 任一 handoff 合同、anchor、token 或 POST mutation 校验 | 合同出现实 token、空白/通配 token、未知 anchor、token pointer 漂移或 WHITESPACE_ONLY 命中时 `FAIL_CLOSED` |
| `R030` | `SUBAGENT_FIRST=YES`、`ONE_PHASE_PER_FRESH_AGENT=YES`、`NO_REUSE_SUBAGENT=YES`、`ROOT_INTEGRATION_ONLY=YES`。负责人只做集成编排且不亲自跑真机 QA；实现、工具修复、文档协议、B6 两阶段状态机修复、B7 POST mutation-scope 修复、B8 anchor hardening 修复、B9 source PRE validation 修复、B10 role-scope hardening 修复、B11 review-pass PRE 状态修复、集成审查、Pad QA、K60 QA 全部使用全新专业代理，一名代理只做一个阶段，禁止 followup 接续。当前 A = `.planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools/**`，B = `AGENTS.md` + 2.0 文档 + `tools/v2-handoff/**`；A/B 互斥并由负责人串行仲裁 | 任一 2.0 派单、交接、工具修复或 QA | 复用代理、root 亲跑设备、跨 A/B 写或共享文件并发写即停止；已做阶段不得计入完成 |
| `R031` | 新设备决策固定 `DEVICE_WINDOW_MINUTES=10`、`DEVICE_ORDER=Pad->K60`。每设备只执行一次 10 分钟真实 runtime 观测窗，仍须验证真实 AudioTrack、头/中/尾连续性、最大 audio gap ≤15 秒、FGS / MediaSession / notification、PID、当前 run native/Java crash、resource / focus / 单音频 owner 与 cleanup。Pad `43af8627` 核心全部 PASS 且锁释放后才登记 K60 `8e875894`，K60 另派全新 QA。旧 30 分钟、install、stop-only、cleanup 或旧 APK 证据不可替代 | 2.0 runtime QA、设备登记、交接与验收 | 延长窗口、K60 先行、复用旧证据、核心未执行或任一核心失败即停止；不得写产品 PASS / `PASS_WITH_GAPS` |
| `R032` | handoff 只有 `PRE_HANDOFF_AUDIT` 与 `POST_HANDOFF_AUDIT_READY` 两阶段；PRE 仅合法两子态：`PRE_REVIEW_PENDING` 的 review gate 必须 `BLOCKED`，`PRE_REVIEW_PASS_AWAITING_AUDIT` 的 review gate 必须 `PASS`，两者均要求 audit / QA 门 `BLOCKED`、qaRoundState audit / QA / dispatch 非 PASS、padQa `dispatchable=false` 且非 active prompt、`auditReceipt=null`。source PRE 与 current PRE 必须共用 `Assert-PreAuditState(mode)`，不得把 round8 待审状态写死。POST 只接受 `verdict=PASS` 的 exact `auditReceipt`，并要求 audited source commit / tree、qaPrompt / tools / deviceLock / evidenceRoot / rejectedScope / memory / immutableState hashes 与现场一致。POST 必须用固定 `commit:path` 严格 UTF-8/LF 读取 source 合同，对 source PRE 与 current POST 分别执行完整规则、owner / `requiredBefore` / 门序 / anchor / roles / tools / APK 校验，再验证 source 到 current 的三文件 `transitionPaths` 和递归 JSON pointer allowlist；`acceptedArtifacts / qaTools / ... / requiredTextAnchors` 等 immutable projection 不得漂移。角色 ID / 类别与 `kind / freshness / phaseLimit / reuse / deviceAccess / writeScope` 必须逐项命中脚本周周期常量；`ROOT_INTEGRATION` 不写产品 / QA 证据，tool repair 仅 `qa-v8/tools/**`，协议 owner 仅授权 docs+handoff，review/audit 空写域，Pad 仅 `qa-v9/**`，K60 仅 Pad PASS 后 `qa-v10/**`。source 与 current 同步篡改并重算摘要仍必须 fail closed | 任一 handoff preflight、audit、finalization 或 QA 激活 | round8 旧状态残留、review PASS 与 gate / ACTIVE token 不一致、source 或 current 任一出现 audit / QA PASS、qaRoundState 伪造、padQa 可派发 / active、receipt 伪值、字段未知 / 缺失、source 非祖先或 tree 漂移、读取歧义、UTF-8 / LF 失败、unknown top-level / pointer、immutableStateSha256、anchor/token pointer、unknown/duplicate role、角色语义、路径语法 / 重复 / traversal / wildcard / 生产越界，或任一既有 hash / path / owner / requiredBefore / 门序 / 首动作 / 角色 / 设备策略漂移即 `FAIL_CLOSED`；路径白名单不得替代对象校验，也不得放宽为任意 PASS |
| `R033` | 工具 / 门禁任务必须在开工前声明时间盒、最大往返次数和中止后备选路径。同一控制面问题最多经历两轮修复，并最多执行一次全长回归；第二轮仍出现 P1 时默认冻结当前方案，改走隔离 fixture、简化门禁、成熟开源 / 标准工具、后置或明确延期，不得继续堆字段、gate 或 exact count。若考虑放宽 / 跳过某门，必须写明风险、补偿证据与用户批准边界，且只可涉及非产品、非安全、非数据完整性的流程门 | 任何工具、harness、审计协议、状态机或交接门禁任务 | 未声明预算不得开工；第二轮仍 P1 仍继续修补即停止；跳过产品 P0/P1、设备授权、生产写或数据迁移安全即判越权 |

> **HISTORY（2026-11-07）**：上述 round9 review、`PRE_HANDOFF_AUDIT_OWNER` 角色链以及
> `R029` / `R029A` / `R032` 的旧 G2 handoff 流程只适用于冻结合同的历史追溯，不再触发
> 当前入口。用户已以 `V2-D211` 将 TTS/G2 延期到其他 2.0 core 功能之后；不得重入 64/65、
> exact-count、handoff 修复或旧 QA 派发循环。未来恢复必须新任务、新方案、新验收和新合同，
> 并在新任务卡中重新声明 `R033` 的时间盒与停止门。

## 资源位置

- 生产服务器：`jellyfins.zhangwenkang.com`，只读。
- 可写测试服务器和可选网络代理：值位于 `F:\Develop\codex_work\Documents\Codex\资源.txt`；正文中的账号、密码、令牌和代理认证不得复制。
