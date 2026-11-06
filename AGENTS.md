# Cinefin 协作约定

- 全程使用简体中文回答。

## Aurorama 2.0 交接启动硬门（2026-11-06，优先于下方通用开工顺序）

- 新会话第一条只读动作必须实际运行
  `pwsh -NoProfile -File 'F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama\tools\v2-handoff\Invoke-HandoffPreflight.ps1' -EmitPrompt`。
  该命令只读、离线、不得调用 `adb` 或网络。任何非零退出、`FAIL_CLOSED`、缺失字段、未知值、
  角色表不完整、gate owner / `requiredBefore` / 门序 / 状态漂移、prompt `actionClass` 或参数
  不符、设备顺序漂移、窗口非 10 分钟、APK/工具摘要不一致、`auditReceipt` 对象绑定漂移、
  未知顶层字段、非白名单 JSON pointer、`immutableStateSha256`、`activeRequiredTokens` 或
  `requiredTextAnchors` stable pointer 漂移都必须立即停止。
- 唯一权威机器契约为 `tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json`。唯一 `ACTIVE` 开头必须直接
  展示 `SUBAGENT_FIRST=YES`、`ONE_PHASE_PER_FRESH_AGENT=YES`、`NO_REUSE_SUBAGENT=YES`、
  `ROOT_INTEGRATION_ONLY=YES`、`DEVICE_WINDOW_MINUTES=10`、`DEVICE_ORDER=Pad->K60` 和完整
  角色表；重要规则不能只留在聊天或 Engramory。
- 唯一 gate 顺序为 `PREFLIGHT_PASS -> TOOL_NONCONVERGENCE_REPAIRED ->
  CONTRACT_DIGEST_REFRESHED -> INDEPENDENT_INTEGRATION_REVIEW_PASS -> PRE_HANDOFF_AUDIT_PASS ->
  QA_DISPATCHABLE`。每门必须含精确
  `id / owner / requiredBefore / status`；下游 PASS 的前置门未 PASS、owner 漂移、重复 / 缺门、
  乱序或未知状态一律 `FAIL_CLOSED`。QA 只有在 audit PASS 后的 `POST_HANDOFF_AUDIT_READY`
  且 padQa 仍 dispatchable 时才可派发；`POST_QA_RUNTIME_DEFERRED` 下 QA 必须退役。
- `validation.handoffPhase` 是唯一阶段开关：`PRE_HANDOFF_AUDIT` 只有两个合法子态：
  `PRE_REVIEW_PENDING`（review gate `BLOCKED`）与
  `PRE_REVIEW_PASS_AWAITING_AUDIT`（review gate `PASS`）；两者都要求
  audit / QA=`BLOCKED` 且 padQa 不可派发。`POST_HANDOFF_AUDIT_READY` 只在合法
  `auditReceipt` 的 `verdict=PASS`、source commit/tree、QA prompt / tools / device-lock /
  evidence-root / rejectedScope / memory hashes 与现场完全绑定后允许。PRE 下任何 audit / QA
  PASS、POST 缺 receipt 或 receipt 字段未知 / 缺失 / 算法漂移均 `FAIL_CLOSED`；不得放宽为任意 PASS。
  QA-v9 之后唯一允许的收口子态是 `POST_QA_RUNTIME_DEFERRED`：保留历史 receipt，要求
  `RUNTIME_QA_DEFERRED_TO_G2 / G1_STATIC_CLOSURE_IN_PROGRESS / PRODUCT_P0_P3_NOT_ASSESSED /
  K60_LOCKED / QA_DISPATCHABLE=DEFERRED_TO_G2 / activePrompt=none`，不得再派发 QA。
- POST receipt 还须证明 `auditedSourceCommit` 存在且为当前 HEAD 祖先、`auditedSourceTree`
  与该 commit 的 tree 一致，并从该 commit 到工作树的变更恰为
  `auditReceipt.transitionPaths` 白名单；该文件范围门不能替代对象门。POST 还必须从 source
  commit 的固定 `commit:path` 以严格 UTF-8/LF 读取合同 JSON；source PRE 必须通过与真实
  PRE 相同的完整语义断言，确认 phase、`auditReceipt=null`、audit/QA gate `BLOCKED`、
  qaRoundState audit/QA/dispatch 非 PASS、padQa 不可派发且不是 active prompt，并校验
  `requiredBefore / owner / anchor / tools / APK / roles` 等完整绑定。随后递归比较当前 JSON
  的 mutation pointer，只允许 receipt、phase、
  audit / QA gate、qaRoundState audit / dispatch、active prompt / next path、prompt
  status / dispatch / retiredReason 与固定 validation 转换字段；同时 receipt
  `immutableStateSha256` 必须同时命中 source 与 current 的 canonical immutable projection。
  合法 finalizer 不触碰工具、产品或 QA 证据。`POST_QA_RUNTIME_DEFERRED` 例外只适用于
  QA-v9 已真实执行后的当前态：历史 receipt 的锁哈希 / memory hash / transitionPaths 仍是
  审计时快照，preflight 另行要求当前锁 `RELEASED_ACTIVE_0`、qa-v9 目录与报告存在且摘要
  `BLOCKED_TOOL_NONCONVERGENCE / PRODUCT_P0_P3_NOT_ASSESSED`；当前 transition set 只允许
  授权 docs、`AGENTS.md`、handoff 工具与 `qa-v9/**`，不得删除 / 移动证据。
- 文档 token 只能来自 preflight 按 `handoffPhase` 定义的不可变 PRE/POST 常量；合同只保存
  `ACTIVE_SECTION` 与五个稳定 anchor id/path。POST mutation allowlist 不得包含
  `activeRequiredTokens` 或 `requiredTextAnchors` 下的任何 pointer；token 为空、带首尾空白、
  通配符、长度不足或只命中空白均 `FAIL_CLOSED`。
- `roles[].writeScope` 不能只校验“是数组”。脚本必须按精确角色 ID / 类别常量逐项校验
  `kind / freshness / phaseLimit / reuse / deviceAccess / writeScope`：`ROOT_INTEGRATION`
  只有集成元数据占位域，不得写产品或 QA 证据；`TOOL_REPAIR_OWNER` 仅
  `qa-v8/tools/**`；协议/文档 owner 只可写授权 docs 与 handoff 工具；review / audit
  owner 写域必须为空；`PAD_QA_OWNER` 仅 `qa-v9/**`；`K60_QA_OWNER` 仅
  `qa-v10/**` 且只能在 Pad PASS 后解锁。unknown / duplicate role、非精确集合、重复项、
  空白、traversal、越界 wildcard 或生产路径均 `FAIL_CLOSED`；source 与 current 同步篡改
  即使重算 receipt / immutable hash 也必须被常量拦截。
- 新会话 prompt 首动作必须绑定 `actionClass=READ_ONLY_PREFLIGHT` 的精确命令参数；仅以
  `pwsh -NoProfile` 开头的任意命令不算通过。round7 因 `ROUND7-P1-01`、round8 因
  `ROUND8-P1-01` 永久退役，既往 pre-handoff audit owner 永久退役；B11 已修复 PRE 双子态
  与 source/current 共用断言；全新 `ROUND9_READONLY_INTEGRATION_REVIEW_OWNER` 与
  `PRE_HANDOFF_AUDIT_OWNER` 已完成历史 PASS 并永久退役。当前 `V2-D208` 状态为
  `RUNTIME_QA_DEFERRED_TO_G2 / G1_STATIC_CLOSURE_IN_PROGRESS /
  QA_V9_BLOCKED_TOOL_NONCONVERGENCE / PRODUCT_P0_P3_NOT_ASSESSED / K60_LOCKED`；
  `validation.handoffPhase=POST_QA_RUNTIME_DEFERRED`，历史
  `PRE_HANDOFF_AUDIT_PASS=PASS`，`QA_DISPATCHABLE=DEFERRED_TO_G2`，`activePrompt=none`。
  `PAD_QA_OWNER` / `K60_QA_OWNER` 无当前派发权；入口机器状态保留
  `G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS`。`V2-D209` 授权的全新
  `G2_RUNTIME_CONTRACT_OWNER` 已建立
  `G2_RUNTIME_CONTRACT_PACKAGE_READY`，合同根为
  `.planning/v2-g2/G2-ENTRY-RUNTIME-CONTRACT/`。用户以 `V2-D210` 确认设备执行：
  `WAITING_USER_NOTIFICATION_AFTER_G2_TOOL_REPAIR /
  PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT / K60_GATED_LOCKED`。首次 Pad QA
  在工具 Validate 阶段被仓库相对路径二次绑定阻断，窗口未开始、候选未安装、锁释放、K60
  零接触，产品 P0–P3 仍 `NOT_ASSESSED`。全新 repair owner 已修复路径基准与 6 项 cleanup
  假阳性，TDD 扩为 `57/57 PASS / 37 negative`，保留真实残留 fail-closed。下一阶段只能由
  全新只读 repair audit 核验；PASS 并提交 clean 后才可另派全新 Pad QA。用户现已要求
  暂停等待后续通知，`activePrompt=none`；在用户恢复前不得创建 repair audit、Pad/K60 QA。
  D210 不授权产品代码、G2 实现、Release 或 L2/L3。设备授权不是 QA / runtime / G1 PASS；
  settlement 合同回归保持 `104/104 PASS`。
- 负责人只做交付 / 集成编排、范围、白名单、压缩报告与关键 diff 审查、集成决策和交接，不亲自
  实现、修工具、写产品代码或跑真机 QA，也不批量读取 raw 证据。实现、工具修复、文档协议、
  集成审查、Pad QA、K60 QA 各用全新专业子代理；每个 fresh agent 只做一阶段，禁止 followup
  同一代理接下一阶段。
- `qa-v8/tools/**` 已冻结；`qa-v9/**` 是只读历史证据，当前 settlement 与后续静态 planning
  均不得修改、移动或删除。`AGENTS.md`、2.0 文档与 `tools/v2-handoff/**` 只给当前授权
  settlement / fresh 静态 owner；B6 / B7 / B8 / B9 / B10 / B11 / B12 均永久退役。任何共享
  文件变更由负责人串行仲裁。
- 每设备单次真实 runtime 观测窗固定 10 分钟，不得延长；Pad `43af8627` 核心全部 PASS 且锁释放
  后才允许登记 K60 `8e875894`，且 K60 必须另派全新 QA 串行执行。旧 30 分钟、安装门、stop-only
  或 cleanup 证据不得冒充本轮 10 分钟产品门。`V2-D208` 后完整 Pad/K60 runtime 门延期到
  G2 入口；当前 K60 未登记并保持锁定，禁止用延期触发设备动作。
- **开工顺序**：先读 `docs/PROJECT_PLAN.md`（项目总览 / 模块地图 / 任务线 / 多会话协作规程）→ 再读所在任务线的文档。
  播放器线 = `docs/PLAYER_PLAN.md`（该线的需求、决策、进度、验收标准与踩坑库都在里面）。
- **Aurorama 2.0 权威入口**：2.0 工作先读 `docs/V2_PLAN.md`，再按其中的本次只读白名单读取外部原始需求；执行规则以 `docs/PROJECT_RULES.md` 为准，任务状态看 `docs/TASKS.md`，决策看 `docs/DECISIONS.md`，项目内踩坑看 `docs/LESSONS.md`。外部原始需求永久只读，不在聊天中重复整段搬运。
- **2.0 需求阅读分工（2026-11-03，用户修订）**：主负责人可以直接通读本次需求明确指定的必要章节、ACTIVE 和相关权威文档；子代理主要用于 Git / 哈希门、事实核验、交叉检查、只读诊断和压缩取证。仍禁止无目的通读无关或越界材料。
- **负责人优先委派子代理（2026-11-03，用户拍板，强制）**：每位负责人每一波开工前先按专业角色拆分工作；凡可委派的调研、证据整理、诊断、实现、QA、文档 / 交接整理都优先派**全新**且固定 `deepseek-flash / reasoning_effort=max / fork_turns=none` 的子代理。负责人只保留范围、白名单、压缩报告 / 关键 diff 审查、集成与门控，不亲自吸收可委派的批量专业工作；必要正文和关键 diff 的短核对仍可由负责人完成。无法派子代理且该工作会显著扩大负责人上下文时，立即停止并报告，不得硬扛后宣称完成。
- **2.0 文档职责**：`docs/V2_PLAN.md` 是需求与交付权威账本；`PROJECT_RULES` / `LESSONS` / `TASKS` / `DECISIONS` 分别是规则、教训、看板、决策索引。除这些约定文件外，不新建零散说明文档。
- 收工前把完成度、勾选项、日志写回**对应任务线文档**；项目级变化（任务线增删、里程碑推进）写回 `docs/PROJECT_PLAN.md`。
- **一条任务线一个会话**，单会话只做 1–2 个"一屏能验证完"的任务，避免上下文过长；需要长任务时先交接再继续。
- **实现 / QA 分权（2026-10-24，强制）**：实现子代理只做自身 TDD、构建、边界内最小真机自测和实现报告；**禁止自行开启、派发或撰写独立 QA，禁止自行进入 QA 阶段或 merge-prep**。独立 QA 必须由负责人在实现收口后显式启动，且使用与实现不同的全新代理。
- **负责人退出契约（2026-10-24，强制）**：阶段或任务收口时，以下项目必须全部完成，缺一项不得宣告结束：
  1. 把完成 / 未完成 / BLOCKED、决策、实际门禁与证据指针写回对应任务线文档；
  2. 更新唯一 `ACTIVE` handoff，含当前 commit、分支、设备 / 锁、未覆盖项、下一步入口与实际上下文水位，旧状态标 `HISTORY`；
  3. 执行一次 Engramory 收尾判断，并在最终回复报告 `added / updated / archived / skipped`、索引行数 / 字节与 validator 结果；
  4. 明确写出下一步由什么角色做什么、第一条只读动作、停止条件；没有下一步时写“无”；
  5. **交接时机与硬门（2026-11-05 修订）**：到 30 万停止扩项并写完整检查点；到 35 万若仍有已授权可执行工作，按固定顺序“完成当前最小收口 → 写回任务线 / 任务板 / 决策 / 日志 / 证据与唯一 `ACTIVE` → 提交并确认 clean → Engramory sync + checker/doctor → 旧会话 fresh read-only pre-handoff audit PASS”后创建具体后继。**可交接硬门**：当前波次已收口或明确 `BLOCKED`；无运行中的实现 / QA / 审查代理；设备锁已释放或明确移交；下一任务已授权、具体、可立即执行且无需旧会话补材料；首条 prompt 自包含（专业身份、精确读序、当前事实、非目标 / 红线、第一步与停止条件）。设备在线 / keyguard 只审计下一任务实际需要的设备，无关设备离线不得阻塞。若只剩等用户 / 等设备解锁 / 等外部状态，留在当前线程写检查点并停止，不创建空闲会话；用户恢复且出现具体动作后再交接。新会话做 startup gate 与最小对象 spot-check 后直接执行第一项实际任务，不得等待前任补文档；旧会话创建后继后不得再改实质工作树。禁止为满足 35 万数字制造空会话，thread id 不作为后继门；
  6. 最终回复固定包含：结论、commit / SHA、门禁与 executed、P0–P3、未覆盖 / BLOCKED、证据链接、记忆同步、下一步。不得只用“完成 / 已推送”作为收口。
- 不要新建零散的 `.md` 说明文件；新需求、新决策、新进度一律写进对应任务线文档。
- 构建命令：`$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; .\gradlew.bat :app:phone:assembleDebug --console=plain`（2026-10-06 工具链迁至 F 盘）。
- 测试服务器 `jellyfins.zhangwenkang.com` 为生产环境，**只读**，禁止任何写入/删除调用。2.0 的服务器写测试只允许使用用户指定的独立可写测试服务器；地址和凭据位置见 `PROJECT_RULES`，不得把凭据值写入仓库、日志、报告或记忆。
- **上下文预算（硬约束）**：模型单次请求纯文本 ≤ 880KB、含内联图片 ≤ 48MiB。
  - 不要把截图或大图塞进对话；本地图片用文件链接给出，不要 inline 渲染。
  - 工具输出必须裁剪：只取 `-Last N`、`Select-String` 命中行，禁止整文件回显。
  - 验证优先级：编译 → `adb` 文本命令（`dumpsys` / `uiautomator dump` / 过滤 `logcat`）→ 必要时才截图，看完即删、不贴回对话。
  - **截图预算（2026-10-17 用户拍板）**：实现 / 修复 / 门禁 / 发布会话默认 **0 截图**，不使用 `view_image`；只用 `uiautomator dump` / `dumpsys` / 过滤 `logcat` / `aapt2` / `apksigner` / 哈希 / 本地像素 diff 取证。需要视觉判断时，把证据目录交给**独立视觉 QA 会话**，只接收文字结论；单次视觉验收最多 2 张压缩图（长边 ≤1280px、JPEG/WebP ≤300KB），同一界面只截一次。主会话已看 2 张或证据目录 >5 张即停止看图并交接。
  - 接近上限时主动提醒用户，并将进度实时写回任务线 / 唯一 `ACTIVE`，不依赖聊天上下文接续。
  - **会话预算（2026-11-05 修订）**：到 **30 万 token** 停止扩项并准备交接；到 **35 万 token** 仅在 R020 / R028 的交接硬门 PASS 后创建后继（本机 1M 窗口，不等 auto-compact）；若只剩等待则写完整检查点并停止。交接文档每完成一波 / 做一个决定即回写，一个负责人会话只做少量任务（1–2 波）。

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
