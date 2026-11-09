# Cinefin 协作约定

- 全程使用简体中文回答。

## Aurorama 2.0 可执行工作流入口（2026-11-08）

> `docs/TASKS.md` 的唯一 ACTIVE 是唯一动态状态源。`AGENTS.md` 只保留入口指针、读取顺序和强制命令，
> 不复制当前 SHA、测试计数、当前 owner 或 next role。

- **唯一高效入口**：`tools/architecture/v2-workflow.ps1` 提供 `Snapshot`、`UpdateState`、`GatePlan`、
  `Handoff`、`QaReady`、`Closeout`。动态 ACTIVE / QA token 只走 `UpdateState`；测试派单只走
  `GatePlan`；`Handoff` / `QaReady` 只委派现有 `v2-handoff-lint.ps1` 并原样保留其机械 PASS / FAIL
  token 与退出码；`Closeout` 只证明控制面收口，不证明产品 QA、集成或 Release。

  ```powershell
  pwsh -NoProfile -ExecutionPolicy Bypass -File "<repo>/tools/architecture/v2-workflow.ps1" -Mode Snapshot -RepoRoot "<repo>"
  pwsh -NoProfile -ExecutionPolicy Bypass -File "<repo>/tools/architecture/v2-workflow.ps1" -Mode UpdateState -RepoRoot "<repo>" -StateUpdate "ACTIVE:TASK_STAGE=<stage>"
  pwsh -NoProfile -ExecutionPolicy Bypass -File "<repo>/tools/architecture/v2-workflow.ps1" -Mode Closeout -RepoRoot "<repo>" -ExpectedHead "<manager-pin>" -MemoryIndex "F:\Develop\codex_work\Documents\Codex\engramory-memory\MEMORY.md"
  ```

- **读序**：`AGENTS.md` → `docs/PROJECT_RULES.md` → `docs/TASKS.md` 唯一 ACTIVE / W1B 历史边界 /
  Architecture Critical Path → `docs/LESSONS.md` L028、L038、L039、L042 → 本次任务卡。
  `PROJECT_PLAN.md`、`V2_PLAN.md`、`ARCHITECTURE.md` 只读稳定决策、里程碑和历史；其中现存的
  current-looking 顶部状态在获授权 docs cleanup 前一律视为 `HISTORICAL`，不得作为派单事实源。
- **“交接”的固定语义（2026-11-09 用户纠偏，强制）**：用户说“交接”一律指按 R028 / R036 / R037
  交接**负责人自己的工作**：收口当前负责人波次、写回唯一 ACTIVE / 证据 / 未覆盖 / 下一步，提交
  clean，完成记忆与 fresh pre-handoff audit，然后创建后继**负责人会话**。不得把“交接”解释成
  派发子代理、转交子代理任务或创建一个直接执行下游 QA / 实现的新会话；子代理派发是后继负责人
  开工后的独立动作。创建后继会话必须使用本项目 `projectId`，创建后复核
  `list_threads`：`projectId` 正确且 `pinnedThreads` 不包含该会话；若被自动 pin，先取消 pin
  再报告交接完成。不得把 Pinned 视同项目分组。
- **交接机械门（提交 docs checkpoint 内容后、新会话或交接前）**：唯一 ACTIVE 的内容检查可以先在
  working tree 运行；它若仅因尚未提交而报 `WORKTREE_DIRTY`，该次结果不是交接 PASS。提交 docs
  checkpoint 后，由 manager 在提交外部 pin HEAD，再运行：
  `pwsh -NoProfile -ExecutionPolicy Bypass -File "F:\Develop\code_data\work_tree\Aurorama\v2-executable-workflow-hardening\Aurorama\tools\architecture\v2-handoff-lint.ps1" -Mode Handoff -RepoRoot "F:\Develop\code_data\work_tree\Aurorama\v2-executable-workflow-hardening\Aurorama" -ExpectedHead "<manager pin>" -TasksPath "docs/TASKS.md" -MemoryIndex "F:\Develop\codex_work\Documents\Codex\engramory-memory\MEMORY.md"`。
  只有 clean、pin 匹配且 stdout 出现 `HANDOFF_LINT=PASS` 才可进入 fresh pre-handoff audit；任何
  `FAIL:<CODE>:<path>` 由同一 docs / implementation owner 修正并重跑，PASS 后只做一次 fresh audit。
- **QA 派发机械门**：实现 owner / 负责人派 QA 前运行：

  ```powershell
  pwsh -NoProfile -ExecutionPolicy Bypass -File "F:\Develop\code_data\work_tree\Aurorama\v2-executable-workflow-hardening\Aurorama\tools\architecture\v2-handoff-lint.ps1" -Mode QaReady -RepoRoot "F:\Develop\code_data\work_tree\Aurorama\v2-executable-workflow-hardening\Aurorama" -TasksPath "docs/TASKS.md" -MemoryIndex "F:\Develop\codex_work\Documents\Codex\engramory-memory\MEMORY.md"
  ```

  只有 `QA_READY_LINT=PASS` 才可派发 fresh independent QA。QA owner 第一条动作必须自行重跑该命令，
  不信任上游自报；FAIL 时停止并回报 failure code。
- **同一 docs 提交不得写自身最终 SHA**：candidate / base / required ancestors / failed candidate 的稳定
  SHA 放在 `V2_HANDOFF_OBJECT_BEGIN/END`；当前 candidate HEAD 只由 manager 通过 `-ExpectedHead` 外部 pin。
- **Selector 机械门**：含 `--tests` 的真实命令只放 QA test matrix；空引号、裸选项、空等号或
  下一参数仍是选项均 FAIL。命令在 ACTIVE / QA_READY 的 matrix 外出现也 FAIL。
  `TEST_SELECTOR_VERIFIED=PASS` 必须附带 `TEST_SELECTOR_EVIDENCE=<absolute nonempty file>`；
  `NO_MATCHING_TESTS` / `EQUIVALENT` 必须由 `Test-Evidence` 校验现有非空证据。Owner 自报不算证据。
- **对象、scope、role 与 memory 机械门**：HANDOFF_OBJECT 空白、必需 token 缺失或任一 scope token
  非法时，linter 在 ancestry / diff 前 FAIL。`REQUIRED_ANCESTOR` 与 `FAILED_CANDIDATE` 用显式
  `NONE` 表达空集合；`BASE_HEAD` 不接受 `NONE`。`ALLOWED_PATH` 只接受唯一、canonical、
  repo-relative POSIX 路径。Handoff 始终检查 NEXT_ROLE / MANUAL_OWNER 唯一、精确相等且不命中
  `RETIRED_ROLE`；MEMORY index 和 pointer 从 store 起的每个路径组件均拒绝 reparse point，
  且不读取逃逸目标。上述均由 linter 机械执行；稳定文档是否应物理去重、历史文案是否可读以及
  是否接受剩余 P2 由人工 owner 决策，不能替代机械 PASS。
- **红线**：实现与独立 QA 分权；实现 owner 不派 QA、不 merge-prep、不 push / tag / Release。生产服务器
  只读；设备、网络、Release 与产品代码均需本次任务卡明确授权。控制面工具 PASS 不等于产品 QA、A0、
  architecture seam、W1 或 Release PASS。

- **开工顺序**：先读 `docs/PROJECT_RULES.md` → `docs/TASKS.md` 唯一 `ACTIVE` 与任务卡 → 所在任务线稳定文档；
  播放器线稳定需求 / 验收见 `docs/PLAYER_PLAN.md`。项目总览和架构文档只提供稳定背景。
- **Aurorama 2.0 权威入口**：动态派单事实只读 `docs/TASKS.md`；`docs/V2_PLAN.md` 只提供稳定需求、
  外部原始需求白名单与历史，`docs/PROJECT_RULES.md` 提供执行规则，`docs/DECISIONS.md` 提供稳定决策，
  `docs/LESSONS.md` 提供踩坑。外部原始需求永久只读。
- **2.0 需求阅读分工（2026-11-03，用户修订）**：主负责人可以直接通读本次需求明确指定的必要章节、ACTIVE 和相关权威文档；子代理主要用于 Git / 哈希门、事实核验、交叉检查、只读诊断和压缩取证。仍禁止无目的通读无关或越界材料。
- **负责人优先委派子代理（2026-11-03，用户拍板，强制）**：每位负责人每一波开工前先按专业角色拆分工作；凡可委派的调研、证据整理、诊断、实现、QA、文档 / 交接整理都优先派**全新**且固定 `deepseek-flash / reasoning_effort=max / fork_turns=none` 的子代理。负责人只保留范围、白名单、压缩报告 / 关键 diff 审查、集成与门控，不亲自吸收可委派的批量专业工作；必要正文和关键 diff 的短核对仍可由负责人完成。无法派子代理且该工作会显著扩大负责人上下文时，立即停止并报告，不得硬扛后宣称完成。
- **2.0 文档职责**：`docs/V2_PLAN.md` 是需求与交付权威账本；`PROJECT_RULES` / `LESSONS` / `TASKS` / `DECISIONS` 分别是规则、教训、看板、决策索引。除这些约定文件外，不新建零散说明文档。
- 收工前把完成度、勾选项、日志写回**对应任务线文档**；项目级变化（任务线增删、里程碑推进）写回 `docs/PROJECT_PLAN.md`。
- **一条任务线一个会话**，单会话只做 1–2 个"一屏能验证完"的任务，避免上下文过长；需要长任务时先交接再继续。
- **实现 / QA 分权（2026-10-24，强制）**：实现子代理只做自身 TDD、构建、边界内最小真机自测和实现报告；**禁止自行开启、派发或撰写独立 QA，禁止自行进入 QA 阶段或 merge-prep**。独立 QA 必须由负责人在实现收口后显式启动，且使用与实现不同的全新代理。
- **负责人退出契约（2026-10-24，强制）**：阶段或任务收口时，以下项目必须全部完成，缺一项不得宣告结束：
  1. 把完成 / 未完成 / BLOCKED、决策、实际门禁与证据指针写回对应任务线文档；
  2. 更新唯一 `ACTIVE` handoff，含分支 / worktree policy、manager 外部 pin 来源、设备 / 锁、未覆盖项、下一步入口、
     `OPEN_LONG_TERM_PLAN_IDS`、`NEXT_CRITICAL_PATH`、`last_evidence` 与实际上下文水位，旧状态标 `HISTORY`；
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
