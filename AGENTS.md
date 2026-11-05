# Cinefin 协作约定

- 全程使用简体中文回答。

## Aurorama 2.0 交接启动硬门（2026-11-05，优先于下方通用开工顺序）

- 新会话第一条只读动作必须实际运行
  `pwsh -NoProfile -File 'F:\Develop\code_data\work_tree\Aurorama\v2-requirements-baseline\Aurorama\tools\v2-handoff\Invoke-HandoffPreflight.ps1' -EmitPrompt`。
  该命令只读、离线、不得调用 `adb` 或网络。任何非零退出、`FAIL_CLOSED`、缺失字段、未知值、
  角色表不完整、gate owner / `requiredBefore` / 门序 / 状态漂移、prompt `actionClass` 或参数
  不符、设备顺序漂移、窗口非 10 分钟或 APK/工具摘要不一致都必须立即停止。
- 唯一权威机器契约为 `tools/v2-handoff/HANDOFF_BOOTSTRAP_V2.json`。唯一 `ACTIVE` 开头必须直接
  展示 `SUBAGENT_FIRST=YES`、`ONE_PHASE_PER_FRESH_AGENT=YES`、`NO_REUSE_SUBAGENT=YES`、
  `ROOT_INTEGRATION_ONLY=YES`、`DEVICE_WINDOW_MINUTES=10`、`DEVICE_ORDER=Pad->K60` 和完整
  角色表；重要规则不能只留在聊天或 Engramory。
- 唯一 gate 顺序为 `INDEPENDENT_INTEGRATION_REVIEW_PASS -> PRE_HANDOFF_AUDIT_PASS ->
  QA_DISPATCHABLE`，并保留 `TOOL_NONCONVERGENCE_REPAIRED -> CONTRACT_DIGEST_REFRESHED ->
  INDEPENDENT_INTEGRATION_REVIEW_PASS` 的上一段语义。每门必须含精确
  `id / owner / requiredBefore / status`；下游 PASS 的前置门未 PASS、owner 漂移、重复 / 缺门、
  乱序或未知状态一律 `FAIL_CLOSED`。QA 必须在 audit PASS 后才可派发。
- 新会话 prompt 首动作必须绑定 `actionClass=READ_ONLY_PREFLIGHT` 的精确命令参数；仅以
  `pwsh -NoProfile` 开头的任意命令不算通过。round3 reviewer 与 pre-handoff audit 必须分别使用
  不同 fresh 角色，当前真实 QA 状态保持 `BLOCKED`。
- 负责人只做交付 / 集成编排、范围、白名单、压缩报告与关键 diff 审查、集成决策和交接，不亲自
  实现、修工具、写产品代码或跑真机 QA，也不批量读取 raw 证据。实现、工具修复、文档协议、
  集成审查、Pad QA、K60 QA 各用全新专业子代理；每个 fresh agent 只做一阶段，禁止 followup
  同一代理接下一阶段。
- 当前 A/B 写边界互斥：A = `.planning/v2-g1/G1-L1-KOKORO-EXACT/qa-v8/tools/**`，只给全新
  `TOOL_REPAIR_OWNER`；B = `AGENTS.md`、2.0 文档与本 preflight 工具根，只给全新
  `DOC_PROTOCOL_OWNER`。任何共享文件变更由负责人串行仲裁，A/B 不得由同一代理跨写。
- 每设备单次真实 runtime 观测窗固定 10 分钟，不得延长；Pad `43af8627` 核心全部 PASS 且锁释放
  后才允许登记 K60 `8e875894`，且 K60 必须另派全新 QA 串行执行。旧 30 分钟、安装门、stop-only
  或 cleanup 证据不得冒充本轮 10 分钟产品门。
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
