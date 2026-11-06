# Aurorama 项目教训索引

> 只收录会影响 2.0 决策、架构、实现或验收的项目内稳定经验。完整跨项目版本在 Engramory；每条经验必须有现象、根因、对策和来源。

## L001 长材料先索引、后分片

- **现象**：整篇读取大需求、日志或任务线会在开工前耗尽上下文。
- **根因**：材料体量远大于单次决策所需，历史事实与当前前沿混在一起。
- **对策**：先取标题、行号、哈希和目录；每次只读互斥白名单；结论回源到文件和行号。
- **来源**：Engramory `index-before-reading`；2.0 原始文档 1,156 行。

## L002 模糊需求先转判据

- **现象**：“好看”“顺手”“大概完成”会在验收时反复返工。
- **根因**：用户生活化表达未被翻译为入口、动作、状态和可观察结果。
- **对策**：要求三件套“原话 + 时间戳 + 解读”；形容词转成 UI 状态、数值、时序或故障边界。
- **来源**：Engramory `requirement-confirmation-gate`、`task-card-protocol`。

## L003 任务卡必须有非目标

- **现象**：AI 在实现同一需求时顺手扩项，导致 review 与 QA 范围漂移。
- **根因**：只有目标没有边界，验收无法判断哪些行为不应发生。
- **对策**：每卡至少两项非目标，并在派单、review、QA 和 merge-prep 各核对一次。
- **来源**：Engramory `task-card-protocol`；Aurorama W90 多轮范围门。

## L004 UI 先有可确认参照

- **现象**：直接实现后才发现信息层级、入口或交互方向错误。
- **根因**：视觉词与结构决策被混在一次实现里。
- **对策**：先信息架构、低保真或参考图，用户点头后再实现；视觉 QA 只看压缩证据并只回文字结论。
- **来源**：Engramory `ui-first-referent`、`screenshot-budget-visual-qa`。

## L005 单元测试不能替代真实入口点击

- **现象**：测试全绿但用户几分钟内发现核心按钮、通知或状态失效。
- **根因**：fake、DOM 语义或局部状态通过，真实 Compose/Media3/Worker/协议链路未执行。
- **对策**：候选包上真实点击核心旅程；记录 executed、serial、fixture、可观察结果和未覆盖。
- **来源**：Engramory `test-runner-executed-evidence`；AGENTS 核心真实链路规则。

## L006 跨界面事实需要系统不变量

- **现象**：下载页、通知和文件系统的计数/状态不一致。
- **根因**：页面持有各自的派生状态，后台任务、数据库和通知之间缺少唯一真源。
- **对策**：先定义领域状态、所有权和跨界面等式，再拆 UI/Worker/Repository 任务。
- **来源**：Engramory `requirements-as-system-invariants`、`background-owner-lifecycle`。

## L007 下载必须保留来源身份

- **现象**：切换服务器/用户或同名媒体后，下载完成项无法正确折叠和恢复。
- **根因**：创建时未持久化 server/user/source provenance，展示时用当前身份反推历史任务。
- **对策**：在接收意图时固化来源；旧记录来源未知必须显式降级；跨 namespace 用稳定身份。
- **来源**：Engramory `persist-source-provenance-at-creation`；W88-B sidecar v2。

## L008 异步操作必须可归因

- **现象**：旧请求结果覆盖新页面、取消后又复活、失败无法判断是哪一轮。
- **根因**：缺少 generation、source、accepted、done 和 deadline 的完整状态链。
- **对策**：每次操作生成身份，只有当前 generation 可提交；取消和迟到结果各有明确归宿。
- **来源**：Engramory `async-confirmation-evidence`；W90 下载取消修复。

## L009 数据迁移不能靠“签名相同”证明可回滚

- **现象**：高 schema 候选回装低 schema 旧包后触发破坏性降级并丢失登录。
- **根因**：保留 dataDir 或同签名不证明 Room 数据库可降级。
- **对策**：真实用户数据禁止高→低降级；迁移必须有升级 fixture、备份/回滚预案和可丢弃 profile。
- **来源**：Engramory `android-destructive-schema-rollback`；W90-R2 登录回退事故。

## L010 隔离 worktree 必须使用绝对路径

- **现象**：worktree 会话中的相对写路径落到主仓库，污染其他任务。
- **根因**：工具工作目录与代理假设不一致。
- **对策**：开工和提交双查 `git status`；编辑/证据写绝对路径；主仓 dirty 资产原样保留。
- **来源**：Engramory `worktree-session-abspath-edits`。

## L011 未及时合并的功能最终会变成孤儿分支

- **现象**：功能分支自测通过，但目标分支推进后无法安全合并或被遗忘。
- **根因**：“完成”只看功能分支，缺少 target 漂移和候选祖先证据。
- **对策**：功能分支通过 QA 后立即 merge-prep；Done 绑定集成分支和承诺矩阵。
- **来源**：2026-10-27 并行支线集成闭环规则；Engramory `merge-prep-audit-base-and-target`。

## L012 Windows 脚本文本编码必须固定

- **现象**：PowerShell 5.1 按 GBK 读取 UTF-8 XML/脚本，导致中文匹配失败和假 BLOCKED。
- **根因**：默认代码页与 UTF-8 文件不一致。
- **对策**：统一 PowerShell 7；读取 XML/JSON 显式 UTF-8；带中文脚本保存为 UTF-8。
- **来源**：Engramory `ps51-gbk-breaks-utf8-scripts`；MIUI 安装门事故。

## L013 设备取证要捕获取值并恢复

- **现象**：QA 将用户“永不锁屏”错误恢复为 120000，反复改变设备状态。
- **根因**：恢复动作使用硬编码经验值，没有保存测试前真实值。
- **对策**：所有设置、user、PID、screen、代理和包身份均 capture-and-restore；唯一锁和显式 serial。
- **来源**：Engramory `testing-delegation`；2026-10-31 设备锁事故。

## L014 发布材料不能先行

- **现象**：截图和文案先做好，最终核心下载/播放链路仍未完成。
- **根因**：发布工程与产品交付门解耦。
- **对策**：核心真实链路通过后才制作截图、Release notes、资产和最终验收。
- **来源**：AGENTS 2026-10-27 强制规则。

## L015 凭据只能记录位置

- **现象**：把服务器 URL、认证头或 prefs token 复制进报告会泄漏身份并污染证据。
- **根因**：为方便复现而保存完整请求或整库。
- **对策**：凭证只从指定资源文件按需读取；证据保存非敏感结构、哈希和掩码；日志/命令/记忆不复制值。
- **来源**：Engramory `jellyfin-db-token-minimize-evidence`、`proxy-credentials-process-only`。

## L016 跨会话哈希必须原样复制并校验长度

- **现象**：新会话的第一条只读门判定 SHA-256 不匹配并停止；仓库中的实际值和 handoff 文本均为有效的 64 位值。
- **根因**：创建新会话时手工重写首条 prompt，把 64 位哈希抄成 62 位，漏掉中间两个字符；仓库和外部文件均未漂移。
- **对策**：创建线程前从权威文档原样复制哈希，并机械断言长度必须为 64；创建后回读首条 prompt 再核对一次，禁止凭记忆重写。
- **来源**：2026-11-02 `V2-REQ-UX` 交接；实际值 `E7E2C21DE6C06799598628A98C82B32B7DF33BE7736A0533943864413920F229`。

## L017 Native crash gate 必须检查 ApplicationExitInfo 与 tombstones

- **现象**：Pad QA 初报 `0 FATAL / PASS`，fresh review 却发现通知停止后 ONNX Runtime / Sherpa JNI 进程以 `ApplicationExitInfo reason=5 APP CRASH(NATIVE), status=6` 退出。
- **根因**：崩溃门只匹配 Java `FATAL EXCEPTION`、ANR 和 InputTimeout，没有检查 native exit reason、当前 run 的 `F DEBUG` / signal，也未拒绝时间窗不匹配的旧 events 缓冲。
- **对策**：任何 Android runtime QA 都同时采集 `dumpsys activity exit-info`、当前 run 的 `F DEBUG` / crash buffer 和包过滤 logcat；只保留时间与目标 UID/package 匹配的证据，旧窗口必须显式标 `NON-EVIDENCE`。`reason=5`、signal / tombstone 与产品 P0–P2 分开记录，工具盲区不得吞掉产品失败。
- **来源**：2026-11-05 Pad QA-v6 / scoped review `REV6-P1-01`；不能把 stop 后 service 收敛当成 native inference 已安全结束。

## L018 交接规则必须进入唯一 ACTIVE 和机械 preflight

- **现象**：交接文档已有角色、fresh、设备与证据规则，但 `PROJECT_PLAN` 仍保留旧授权，ACTIVE 漏字段，新会话仍可能先按通用清单读材料；只靠记忆或长文无法证明规则生效。
- **根因**：规则分散在通用约定、历史交接与 Engramory，缺少唯一当前入口和可执行门禁；负责人又容易亲自批量读证据，导致规则尚未执行就消耗上下文。
- **对策**：把 `HANDOFF_BOOTSTRAP_V2` 放在 `tools/v2-handoff/`，新会话第一条只读动作实际运行
  `pwsh -NoProfile` preflight；缺字段、未知值、角色表不完整、设备顺序 / 窗口 / APK / digest 漂移
  一律 `FAIL_CLOSED`。交接首段直接压过通用规则，负责人只消费压缩 PASS 并派 fresh 专业角色。
- **来源**：2026-11-05 QA-v8 工具阻塞交接；本文件 L001/L016/L017。

## L019 一阶段一 fresh agent，设备顺序与时间窗必须固定

- **现象**：同一代理被 followup 追问多个阶段会膨胀上下文并混淆实现、工具、QA 与集成职责；旧 30 分钟结果又容易被拿来覆盖新的 10 分钟授权。
- **根因**：派单没有不可变角色表、freshness、A/B 写边界和机器可读设备策略，当前决定只埋在聊天或历史段落。
- **对策**：固定 `SUBAGENT_FIRST=YES`、`ONE_PHASE_PER_FRESH_AGENT=YES`、`NO_REUSE_SUBAGENT=YES`、
  `ROOT_INTEGRATION_ONLY=YES`；A=`qa-v8/tools`、B=docs/AGENTS/preflight 互斥。设备严格
  `DEVICE_ORDER=Pad->K60`、`DEVICE_WINDOW_MINUTES=10`，Pad 核心 PASS 且锁释放后才另派 K60；
  旧 30 分钟、安装门、stop-only、cleanup 或旧 APK 不得冒充本轮产品门。
- **来源**：2026-11-05 用户修正；QA-v8 工具门与既有 30 分钟证据历史。

## L020 交接门必须校验语义状态与完整命令，不得只校验名称或前缀

- **现象**：`preHandoffAudit=PASS` 但 independent review 仍 `NOT_RUN`、gate owner 漂移并删除
  `requiredBefore`、prompt 首动作换成任意 `pwsh -NoProfile -File ...`，旧 preflight 仍 exit 0。
- **根因**：校验只比较 gate id 集合和命令前缀，没有消费 `id / owner / requiredBefore / status`、
  下游 PASS 的前置门传播，也没有把 prompt 首动作与 canonical 路径和参数数组逐项绑定。
- **对策**：唯一 lifecycle 使用机械对象，任何下游 PASS 必须要求全部前置门 PASS；独立审查、
  pre-handoff audit、QA dispatch owner 固定且唯一。prompt 同时携带
  `actionClass=READ_ONLY_PREFLIGHT` 与精确参数数组，文本首动作、`firstAction` 和
  `firstReadOnlyActions` 必须逐字一致；替换路径、插入未知参数、乱序 / 重复 / 缺门、未知状态
  全部 `FAIL_CLOSED`。修复按 round2 P2 记录，fresh round3 PASS 前不得激活 QA。
- **Round3 结果（2026-11-06）**：全新只读 `ROUND3_READONLY_INTEGRATION_REVIEW_OWNER` 已真实
  `PASS`；Git 边界无越界，tools `31/31`、manifest `2984 B`、digest
  `69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4`，QA tools `25/25`、
  self-test AST/UTF-8 31、contract `16/16`，15 类伪造契约 exit 2，QA-V9 dry-run 未改变
  tools / manifest hash 或 mtime。`ROUND1-P1-01` / `ROUND1-P2-01` / `ROUND1-P2-02` 已由
  round2 关闭，`ROUND2-P2-01` 已由 round3 关闭。产品 P0–P3 仍 `NOT_ASSESSED`，K60 未运行。
- **Next-phase guard**：当前 schema 尚无 audit actual receipt，review PASS 后必须保持
  `PRE_HANDOFF_AUDIT_PASS=BLOCKED / QA_DISPATCHABLE=BLOCKED`，唯一下一角色为全新
  `PRE_HANDOFF_AUDIT_OWNER`；伪造 audit PASS、audit owner 漂移、QA 提前 PASS 均须 exit 2，
  不能把“允许 audit”误写成“audit 已 PASS”。
- **来源**：2026-11-06 `ROUND2-P2-01` B4 修复、round3 独立复审与 B5 状态收口；上述三类
  绕过及 round3 正负门均复现。

## L021 后审计阶段必须是独立状态，不得用“等待 audit”永久封死

- **现象**：pre-handoff audit 准备提交 `PASS` 时被 preflight 拒绝；脚本把 audit / QA 永久固定为
  `BLOCKED`，契约没有合法 post-audit 状态，也没有可绑定的审计 receipt。
- **根因**：状态机只实现 PRE 断言，没有表达“同一合同在合法 audit PASS 后进入 QA 可派发”的
  第二状态；同时 round3 与旧的未提交 / force-add 叙述仍被当成当前事实。
- **对策**：引入 `validation.handoffPhase` 与嵌入式 `auditReceipt`。PRE 严格保持
  audit / QA=`BLOCKED`、padQa 不可派发；POST 只接受 exact receipt，并绑定 source commit / tree、
  QA prompt、tools digest、设备锁状态与哈希、证据根、rejectedScope、memory index 与
  `transitionPaths`。任何字段未知 / 缺失、非祖先、tree / hash / path 漂移均 `FAIL_CLOSED`；
  合法 finalizer 只改 receipt / 阶段 / gate / prompt / validation / ACTIVE / TASKS。
- **验证**：合同回归 **43/43 PASS**；PRE 与临时 POST 正例通过，原 16 项与新增 receipt /
  source / tree / hash / role / path 负例全部 exit 2。未改动的 QA tools 仍 `25/25`，
  self-test artifact / AST / UTF-8=`31/31/31`，digest / manifest 不变。
- **来源**：2026-11-06 B6 `HANDOFF_POST_AUDIT_PHASE_REPAIR_OWNER`；10 个 QA 工具文件已由
  `c4a2d2a1` 提交，不得再沿用 force-add / uncommitted 叙述。round3 与既往 audit owner 永久退役，
  下一唯一角色为全新 `ROUND4_READONLY_INTEGRATION_REVIEW_OWNER`。

- **Round4 结果（2026-11-06）**：round4 在 B6 候选上发现 `ROUND4-P1-01` 并退役。合法 POST
  仍可改 `acceptedArtifacts.main.versionName`、给 `PAD_QA_OWNER.writeScope` 追加
  `app/phone/src/main/**`、删除 `NEW_P0_P2_OR_EVIDENCE_TAMPER`，三次均 exit 0。因此 B6
  `43/43` 只证明 receipt / path 门，不证明 JSON 对象 mutation scope。

## L022 POST 必须校验 JSON 对象 mutation，不得只校验变化文件路径

- **现象**：B6 的合法 POST 已绑定 receipt、source / tree / hash 与三文件 `transitionPaths`，
  但合同内部可被任意改写；`acceptedArtifacts.main.versionName`、角色 `writeScope` 和
  `stopConditions` 都位于同一个合法合同路径，路径级白名单无法判别对象 pointer。
- **根因**：校验层把“git diff 文件集合”误当成“业务对象 mutation allowlist”，没有从 audited
  source commit 读取合同 JSON，也没有递归比较 current JSON 或绑定 immutable projection。
- **对策**：`auditReceipt.immutableStateSha256` 绑定 source / current 的 canonical immutable
  projection；POST 逐 pointer 比较 source commit JSON 与 current JSON。只允许 receipt、phase、
  audit / QA gates、qaRoundState audit / dispatch、active prompt / next path、prompt
  status / dispatch / retiredReason 与固定 validation 转换字段；未知顶层字段、未知 nested
  field、任何其他 pointer 或 hash 漂移都 exit 2。三文件 `transitionPaths` 继续保留为附加门。
- **验证**：B7-RETRY 保留原 43 项并新增三项 P1 精确回归，以及 unknown top-level / nested
  pointer / non-allowlisted pointer / immutable hash 负例；最终 **50/50 PASS**、
  `TEMP_CLEANUP=PASS`。合法 PRE、合法临时 POST 与 receipt / source / tree / hash / path
  既有正负例继续执行。真实合同仍 PRE，不写 audit / QA PASS。
- **来源**：2026-11-06 `ROUND4-P1-01` 与 B7-RETRY owner；下一唯一角色为全新
  `ROUND5_READONLY_INTEGRATION_REVIEW_OWNER`，round4 永久退役。

## L023 Anchor token 必须来自 phase 常量，合同不能保存可变正文 token

- **现象**：合法 POST 可把所有 TASKS anchor token 改成等长空白，再恢复 PRE TASKS 并在无害
  HTML 注释中放置对应长度空白；旧 `Contains` 命中后仍 exit 0。
- **根因**：POST mutation allowlist 放行 `requiredTextAnchors/*/tokens/*`，合同自身决定待检
  token，`Assert-Tokens` 又只检查空白是否出现；三层叠加把“锚点存在”降成“任意空白存在”。
- **对策**：PRE/POST exact token 只由 `Invoke-HandoffPreflight.ps1` 的 phase 常量提供；合同
  `activeRequiredTokens` 仅保存 `ACTIVE_SECTION`，`requiredTextAnchors` 仅保存五个稳定
  id/path。两字段从 POST mutation allowlist 删除。token 必须非空、无首尾空白、无 `* / ?`
  通配、trim 后长度至少 2，并用 `Ordinal Contains` 逐字命中。
- **验证状态**：B8 已实际加入空格 token + PRE TASKS/注释、单 token 漂移、未知 anchor、空白
  activeRequiredToken 回归；合同回归 `54/54 PASS`、四项 anchor 负例均 exit 2、
  `TEMP_CLEANUP=PASS`。这只表示修复输入待 round6 复审，不是 review / audit / QA PASS。
- **来源**：2026-11-06 `ROUND5-P1-01` 与 B8 `HANDOFF_ANCHOR_HARDENING_REPAIR_OWNER`；
  round5 永久退役，下一唯一角色为 `ROUND6_READONLY_INTEGRATION_REVIEW_OWNER`。

## L024 source PRE 必须与 current POST 使用同等完整断言

- **现象**：POST 从 `auditedSourceCommit` 读取 source 合同时只检查 phase、receipt 与少量
  immutable pointer；伪 source 同时把 audit/QA gate、qaRoundState audit/dispatch 和 padQa
  dispatch 设为 PASS，并令 activePrompt=padQa、auditReceipt=null，旧 preflight 仍 exit 0。
- **根因**：把“source/current JSON pointer 一致”和“immutable hash 一致”误当成 source 自身
  合法 PRE 的充分条件。允许 POST 转换的字段恰好包括 audit/QA gate 与派发状态，因此若 source
  已经伪造这些值、current 保持相同的伪 POST 值，递归 diff 看不到变化。
- **对策**：新增 `Assert-PreAuditState`：source 合同必须具有 exact top-level / roles /
  validation schema，phase=`PRE_HANDOFF_AUDIT`、auditReceipt=null、review gate 可为当前合法
  PASS，但 audit/QA gate 必须 `BLOCKED`；qaRoundState audit/QA/dispatch 不得 PASS；
  padQa 必须 `BLOCKED_PENDING_PRE_HANDOFF_AUDIT / dispatchable=false`，activePrompt 不得为
  padQa；owner、`requiredBefore`、门序、anchor pointer、immutable tools / APK / roles 与
  current POST 的既有完整校验分别执行。source 读取改为固定 `commit:path` 的原始 blob，
  严格 UTF-8、no BOM、LF。
- **验证状态**：B9 保留原 54 项并新增 13 项，合法 source PRE / 临时合法 POST 通过；
  source audit/QA gate PASS、qaRoundState audit/dispatch PASS、padQa active/dispatchable、
  activePrompt=padQa、receipt nonnull、owner / requiredBefore / anchor / tools / APK 漂移及
  UTF-8 BOM 均 exit 2。实际 `67/67 PASS + TEMP_CLEANUP=PASS`，source 负例
  expected/actual 均为 `2/2`。这只表示修复输入待 round7 复审，不是 review / audit / QA PASS。
- **来源**：2026-11-06 `ROUND6-P1-01` 与 B9
  `HANDOFF_SOURCE_PRE_VALIDATION_REPAIR_OWNER`；round6 永久退役，下一唯一角色为
  `ROUND7_READONLY_INTEGRATION_REVIEW_OWNER`。

## L025 角色 writeScope 必须按类别常量做精确语义校验

- **现象**：`roles[].writeScope` 只被验证为数组；给 `PAD_QA_OWNER` 追加
  `app/phone/src/main/**`，并在 source/current 同步篡改后重算 receipt /
  `immutableStateSha256`，旧 POST 仍 exit 0。类似地，tool repair、reviewer/audit 与 root
  都可借摘要重算获得越界写域。
- **根因**：摘要只证明两边一致，不证明角色语义合法；角色 ID 集合、`kind`、freshness、
  phase、deviceAccess 与 `writeScope` 的组合没有独立于 JSON 的权威常量。
- **对策**：preflight 内固化角色 ID / 类别与精确 `writeScope` 常量；`ROOT_INTEGRATION`
  只允许集成元数据占位域，`TOOL_REPAIR_OWNER` 仅 `qa-v8/tools/**`，协议 owner 仅授权
  docs+handoff，review/audit 空写域，`PAD_QA_OWNER` 仅 `qa-v9/**`，`K60_QA_OWNER` 仅
  `qa-v10/**` 且 Pad PASS 前锁定。source PRE 与 current POST 共用同一角色目录断言，并额外
  拒绝 unknown/duplicate role、非精确集合、重复 scope、空白、traversal、越界 wildcard、
  生产路径和错误 `kind / freshness / phaseLimit / reuse / deviceAccess`。
- **验证状态**：B10 已实现同步 source/current PAD 生产写域重算摘要负例及 tool/review/audit/
  root、unknown/duplicate、空白/traversal/wildcard 回归；合同测试 **86/86 PASS +
  TEMP_CLEANUP=PASS**，同步篡改 expected/actual=`2/2`。该状态只表示修复输入待 round8 复审，
  不是 review / audit / QA PASS。
- **来源**：2026-11-06 `ROUND7-P1-01` 与 B10
  `HANDOFF_ROLE_SCOPE_HARDENING_REPAIR_OWNER`；round7 永久退役。round8 随后因
  `ROUND8-P1-01` 退役，B11 修复后下一唯一角色为
  `ROUND9_READONLY_INTEGRATION_REVIEW_OWNER`。

## L026 PRE 必须同时接受 review pending 与 review PASS awaiting audit

- **现象**：source PRE 已允许 review `PASS` 并接受
  `ROUND8_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT`，但 current PRE 主路径、ACTIVE token 与
  round8 角色 owner 仍硬编码“待 review / BLOCKED”。真正 round8 `PASS` 后 preflight 反而死锁。
- **根因**：PRE 被误写成单一静态快照，current 与 source 各自维护重复断言；
  `validation.handoffPhase=PRE_HANDOFF_AUDIT` 没有显式拆成
  `PRE_REVIEW_PENDING` 与 `PRE_REVIEW_PASS_AWAITING_AUDIT`。
- **对策**：两个子态均保持 `PRE_HANDOFF_AUDIT`。pending 只允许 review gate `BLOCKED`；
  review-pass 只允许 review gate `PASS` 并对应
  `ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT`；两者始终要求 audit / QA `BLOCKED`、
  qaRoundState audit / QA / dispatch 非 PASS、padQa `dispatchable=false` 且 receipt `null`。
  source PRE 与 current PRE 共用 `Assert-PreAuditState(mode)`；ACTIVE token 按子态选择，
  round9 pending 与未来 round9 PASS formal token 都由脚本常量固定，合同只保存稳定 pointer。
- **验证状态**：B11 保留 B10 原始 86 项，新增 pending PRE、临时 round9 review-pass PRE、
  legal POST、review PASS 下 audit / QA PASS、padQa dispatch、receipt nonnull、
  ACTIVE token / gate 不一致、round8 旧状态和 round9 reviewer 越界写域回归；目标
  **96/96 PASS + TEMP_CLEANUP=PASS**。fresh round9 reviewer 随后独立复核：测试再次
  `96/96 PASS + TEMP_CLEANUP=PASS`、独立 `21/21`；pending 与 review-pass PRE 正例 PASS，
  audit / QA 提前 exit 2，role / source / current / receipt 门 PASS；QA tools `25/25`、
  self-test `31`，无设备 / 网络 / secret / 残留。B12 据此把真实合同推进到
  `ROUND9_REVIEW_PASS_AWAITING_PRE_HANDOFF_AUDIT`，review gate `PASS`；audit / QA 仍
  `BLOCKED`、padQa 不可派发、receipt `null`。下一唯一角色为全新
  `PRE_HANDOFF_AUDIT_OWNER`；review PASS 仍不是 audit / QA / G1 PASS。
- **来源**：2026-11-06 `ROUND8-P1-01` 与 B11
  `HANDOFF_REVIEW_PASS_PRE_STATE_REPAIR_OWNER`，以及 B12 round9 状态收口；round8 因 P1
  永久退役，round9 reviewer 已 PASS 后永久退役，均禁止复用、followup、commit、audit、
  QA 或 Release。

## L027 pre-window 工具阻断不得消费设备 attempt

- **现象**：首次 G2 Pad QA 在 collector `Validate` 的路径绑定处失败，600 秒窗口未开始、
  fresh install / 核心矩阵均未运行；cleanup verifier 却在该证据根写入
  `device-lock.json: ACTIVE_COUNT_1`。后续 `Register` 只按该文件存在判断 attempt 已登记，
  于是尚未消费窗口的新 QA 被 `ATTEMPT_ALREADY_REGISTERED RETRY_FORBIDDEN` 永久挡住。
- **根因**：把“工具生命周期”与“产品设备 attempt 生命周期”混成同一状态；静态 Validate、
  cleanup 观测、设备登记、600 秒窗口和 attempt 消费没有独立状态机。文件存在被误当作
  attempt 已消费，cleanup 在从未登记时也会自造锁。
- **对策**：Validate 必须无副作用；只有登记成功后才可创建 attempt 锁；attempt 至少区分
  `NOT_CREATED / RESERVED / WINDOW_STARTED / CONSUMED / CLEANED`。pre-window 工具阻断、
  cleanup 快照和自持锁必须进入不可变工具证据域，不得在 attempt 域内冒充已消费窗口。
  未消费阻断恢复时使用版本化新 scope，保留旧证据字节；登记前同时证明窗口 `NOT_STARTED`、
  install/core `NOT_RUN`、`windowConsumed=false` 与权威外部锁 `RELEASED_ACTIVE_0`。
  真实 registration、真实 session、live ACTIVE lock、重复登记和 retry 仍必须 FAIL_CLOSED。
- **验证要求**：至少覆盖“历史 pre-window 锁 + 无 registration 可开新 scope”“真实
  registration / live lock 不可重开”“旧证据 SHA 不变”“finalizer/K60 只消费新 scope”
  四组正负例；不能用删除旧 blocker 或改写旧锁来绕过。
- **来源**：2026-11-06 G2 首次 Pad QA 工具阻断、repair audit 后新 `Register` 阻断，以及
  `V2-D210` “每设备一次、未开始窗口不消费”的既有约束。

## L028 审计 PASS 必须作为控制面事务回写，不得只存在于聊天

- **现象**：fresh repair audit 已在聊天中明确 PASS，但合同、ACTIVE 与 handoff 仍停留在
  `PAD_RUNTIME_TOOLS_REPAIRED_PENDING_FRESH_AUDIT / qaDispatchable=false`；root 直接按聊天
  结论进入下一修复，形成“审计已完成、机器状态仍待审计”的双真源。
- **根因**：把 audit 只当成代理最终回复，没有要求 immutable receipt、权威文档回写、
  提交与 clean preflight；下一阶段又从脏的 WIP 开始，后续代理无法判断哪些改动已审计。
- **对策**：每次 audit 必须产生固定输入 / 输出对象：被审计 commit/tree、owner、命令、
  计数、证据 SHA、结论与下一角色；root 先更新合同 / ACTIVE / TASKS 并提交 clean，
  再由 preflight 切换到下一状态。审计后未回写的 WIP 只能标 `WIP_NOT_ACCEPTED`，不得派发
  QA 或继续叠加第二阶段实现。
- **来源**：2026-11-06 G2 repair audit 到 pre-window recovery repair 的状态衔接。

## L029 长回归必须分层并设置硬超时

- **现象**：`Test-HandoffPreflight.ps1` 的 104 项完整回归约需数分钟；第二轮 repair 在
  内循环再次整套执行，超过 18 分钟仍无最终报告。中断父代理后，子 `pwsh` / 临时仓库
  preflight 仍作为孤儿进程运行，必须由 root 再定位并终止。
- **根因**：没有 fast / full 分层，没有 per-case timeout、总 wall-clock budget 或中断时
  的进程树清理；每个 case 启动新 PowerShell 子进程，长套件被当成普通单测反复运行。
- **对策**：日常 RED/GREEN 只跑 `-Case` / `-Category` / `-Fast` 定向集；完整 104 项只在
  候选稳定、准备提交或 fresh audit 时跑一次。测试需要每 case 超时、总超时、失败即输出
  case 名 / child PID / 临时路径，并在 finally 中终止整个进程树、清理 temp。进度按 case
  刷盘或写日志，不能等 15 分钟后才判断是否卡住。
- **来源**：2026-11-06 R2 repair owner 长时间运行与中断后孤儿测试进程。

## L030 命令调用必须使用单一 canonical wrapper

- **现象**：同一 preflight 在 root 中可直接运行，但 fresh repair owner 用
  `pwsh -File '<quoted path>'` 的外层包装时 exit 64，带引号路径被原样传给 `-File`；
  另有嵌套 `pwsh -Command` 中 `$` 变量被外层提前展开，导致命令实际变形。
- **根因**：两层 shell 同时解析同一字符串；引号、变量和参数数组没有在唯一边界确定。
- **对策**：在已确认 PowerShell 7 的会话中优先用
  `& '<absolute-script>' -Arg value` 直接调用；需要子进程时使用参数数组，不手拼命令串。
  首动作脚本由机器合同固定完整 argv，失败时记录 `exit / stderr / argv`。禁止把同一命令
  在 `pwsh -Command` 与 `pwsh -File` 之间反复嵌套重写。
- **来源**：2026-11-06 R2 repair owner preflight exit 64，以及 root 早期嵌套变量调用失配。

## L031 handoff allowlist 必须从真实 audited-source→HEAD diff 推导

- **现象**：G2 post-QA allowlist 已加入 G2 root prefix，却漏掉真实 transition 中合法的
  exact `docs/LESSONS.md`；ACTIVE 与 TASKS anchor 也缺少 preflight 常量要求的字面 token，
  直到 handoff 首跑才同时暴露三项错误。
- **根因**：allowlist 按“预期改了什么”维护，没有从固定 `auditedSourceCommit` 到当前 HEAD
  的真实 diff 逐项推导；文档 token、测试与 preflight 常量也没有在同一提交前做完整实证。
- **对策**：每次更新 post-QA transition 约束时，先用
  `git diff --name-only <auditedSourceCommit>..HEAD` 列出真实 set，再逐项决定 exact path
  或合同规定的最小 prefix；不得为省事放宽整个 `docs/**` / `tools/**`。提交前必须实际运行
  带 `-EmitPrompt` 的 preflight，确认 ACTIVE、In Progress anchor 与 transition 同时 PASS。
- **验证要求**：至少覆盖新增 exact path 正例、同目录任意 `docs/*.md` 负例，以及 ACTIVE /
  TASKS 缺失 token 各自 fail-closed；完整 handoff 回归只跑一次并核对真实计数与
  `TEMP_CLEANUP=PASS`。
- **来源**：2026-11-06 G2 post-QA handoff 首跑三项 `FAIL_CLOSED`。

## L032 工具状态常量必须先做 transition rehearsal

- **现象**：settlement owner 将合同从 `R2_WIP_REPAIR_R3_IN_PROGRESS` 推进到
  `PAD_RUNTIME_TOOLS_AUDIT_PASS_PENDING_FRESH_PAD_QA` 时，collector / finalizer / cleanup
  各自按旧常量拒绝合同，导致合法未来状态没有统一入口。
- **根因**：状态字符串散落在三份脚本，既没有封闭状态表，也没有在真实切换前用临时 fixture
  演练“当前态只静态、audit-ready 才可运行时”的权限差。
- **对策**：tool-facing 状态只由单一 `Assert-G2RuntimeToolStatePolicy` 解析；每个状态必须
  显式列出 `qaDispatchable / activePrompt / tool status / deviceAccess / allowed actions`。
  禁止用 OR 放行任意状态。任何新增状态上线前，先用临时合同对象和 receipt 正负 fixture 分别
  证明当前态不能开 attempt、未来态只在合法 receipt 下允许 `Register / RunWindow`。
- **验证要求**：至少覆盖两状态权限矩阵、未知状态、`qaDispatchable` 不一致、当前态已有
  receipt、future state 缺 receipt，以及 collector / finalizer / cleanup 三 context 正例。
- **来源**：2026-11-06 G2 settlement 状态推进被三份硬编码状态拒绝。

## L033 audit receipt 必须可机械复算，不得充当状态便签

- **现象**：聊天中的 audit PASS 曾与机器状态不一致；若只凭一个 `verdict=PASS` 文件即可推进
  QA，任何旧 commit、漂移候选或历史证据都能被伪装成 fresh audit。
- **根因**：receipt 没有严格 schema、未知字段拒绝、严格后继 commit / 实际 parent 关系，
  以及当前候选、tools digest、`qa-pad-v1` 五 SHA、外部锁 state/SHA 和 boundary 门绑定。
- **对策**：receipt 固定 UTF-8 no BOM / LF，未知顶层或嵌套字段 fail-closed；每次读取都现场
  复算 audited commit 是否为最低基线的严格后继、parent 是否精确、候选 source commit 是否
  仍为 audited commit 祖先，并核对候选双 SHA、tool digest、五个历史文件 SHA 与外部锁 SHA。
  audited commit 等于最低基线、等于最低基线 parent 或 parent 漂移均拒绝；不得要求 audited
  commit 必须是当前 `HEAD` 祖先，否则提交后会错误拒绝真实审计。任何一个漂移只允许停留在
  pre-audit，不得静默降级为 QA ready。
- **验证要求**：覆盖合法 receipt 正例，以及 missing、verdict 非 PASS、未知字段、BOM/CRLF、
  最低基线相等、最低基线 parent、parent/SHA/digest/lock/qa-pad-v1 漂移的独立负例；合法
  正例必须使用临时 Git object 中的合成严格后继 provenance，禁止把 current `HEAD` 相等
  当作审计后代。contract 与 receipt 同步抬高 tests / dynamic negative / handoff counts
  也必须拒绝。
- **来源**：2026-11-06 G2 audit PASS 事务化、状态兼容与 commit-boundary 修复。

## L034 运行时 cleanup 必须在首个设备或文件副作用前完成 predecessor 门

- **现象**：future 状态的 K60 cleanup context 只校验状态、授权与 root，没有验证 Pad
  predecessor；后续 `CaptureBefore` 会先执行 ADB 快照并创建 evidence 目录，`Verify` 也会
  force-stop / uninstall 候选包。
- **根因**：前置关系只在 collector 的 K60 registration 路径实现，cleanup 作为独立工具入口
  没有复用同一 predecessor helper；context 返回不代表所有跨设备前置已经闭合。
- **对策**：把 Pad report / lock 回读、13 gate、cleanup、候选双 SHA、释放锁和 pointer
  校验抽为共享 helper；cleanup context 对 future K60 在同一函数内执行，并在任何 ADB、
  `Directory.CreateDirectory` 或文件写入之前完成。K60 registration 记录的实际 report / lock
  SHA 必须与现场复算值一致。
- **验证要求**：Pad 缺失、非 PASS、旧 scope、SHA drift、ACTIVE lock 各覆盖
  `CaptureBefore` / `Verify`，并断言 ADB 与目录写入计数均为 0。
- **来源**：2026-11-06 G2 R4 state-policy scoped finding P1-01。

## L035 审计计数与 manifest 必须 exact，不得用下限或 tool 子集代替

- **现象**：future receipt 的 tests / dynamic negative / handoff 只做下限检查，
  `59/1/1` 也能解闸；manifest 只校验四个 tool 文件，10 / 12 项、duplicate 与未知 kind
  都可能通过局部检查。
- **根因**：实现把“非零 / 至少 104 / tool digest 正确”误当成合同完整集合，未把最终 audit
  的精确计数与 11 个唯一文件条目写入受审计字段。
- **对策**：pre-audit 合同只保存 pending / null expectations；settlement 后由最终 audit
  commit 和命令结果填充 exact counts。policy 对 receipt 的每一个计数逐项相等比较，并对
  manifest 的 11 个 path-kind pair、唯一性和 SHA 做完整集合校验。schema 不承担安全下限，
  只保证类型；精确语义由 policy 与合同共同裁决。
- **验证要求**：低值、高值、缺失、null、字符串、count source drift，以及 manifest
  10 / 12 / duplicate / unknown kind / SHA drift 都必须是独立负例。
- **来源**：2026-11-06 G2 R4 state-policy scoped findings P1-02 / P2-01。

## L036 严格 LF 哈希必须先由版本化 attributes 保证 checkout

- **现象**：Windows 全局 `core.autocrlf=true` 时，工作区里的 UTF-8 no BOM / LF
  文本可能在 checkout 后变成 CRLF；此时直接计算文件 SHA 会遇到假 mismatch，不能证明
  固定提交的内容不可复现。
- **根因**：只在生成证据时写出 LF，没有把 `text eol=lf` 绑定到受版本控制的精确扩展名；
  后续 checkout 仍服从机器级换行策略。
- **对策**：在共享 `.gitattributes` 为实际使用的纯文本扩展名声明
  `text eol=lf`，包括 `.ps1` / `.json` / `.md` / `.txt` / `.log`；不使用会污染未来
  二进制的目录级 `** text`，APK 等二进制的 `text` 必须保持 unspecified。
- **验证要求**：先跑 `git check-attr text eol -- <text> <apk>`，确认文本为
  `text: set` / `eol: lf`，APK 不得为 `text: set`；再在系统临时目录构造
  `GIT_INDEX_FILE` 与 `checkout-index` 验证真实 checkout 字节，最后才比较严格 LF hash。
- **来源**：2026-11-06 G2 checkout LF 可复现性修复；`L033` 已被 audit receipt 使用，
  因此顺序编号，不重复既有 lesson。

## L037 manifest 所列文件必须由 Git 跟踪并做 clean-checkout 校验

- **现象**：`runtime-tools.manifest.json` 列出的 fixture 在当前工作区存在且 SHA 匹配，
  但其 `*.log` 扩展名被全仓忽略，导致该文件不在 HEAD；clean checkout 实际只有 10/11，
  当前工作区的 manifest 校验仍会通过。
- **根因**：manifest 校验把“工作区可读”误当成“合同文件已持久化”，没有把 manifest path
  set 与 Git tracked set / clean checkout 做交叉验证；同时全扩展名 ignore 与单 fixture
  例外没有成对维护。
- **对策**：manifest 每个 `path-kind` 项都必须是受版本控制的合同文件；需要放行 ignored
  扩展名时只写根锚定的单一 exact negation，禁止用 `!*.log` 等 broad unignore。实现侧
  可在提交前报告 expected untracked，但阶段门必须要求提交后 tracked set 为 11/11。
- **验证要求**：逐项比对 manifest paths、`git ls-files` 与 clean checkout / archive；
  `git check-ignore -q` 证明 exact fixture 不为 ignored，第二个同扩展名 probe 仍被原规则
  拦截；fixture SHA / size 在 ignore 调整前后必须完全不变。
- **来源**：2026-11-07 G2 manifest fixture persistence P1；工作区 1219 B /
  `bb4857956bbdfe9b6a3284c3e8930a7ccb38c5d21a9ad37c831ab5f920ad6dbd`。

## L038 工具协议修复必须有收敛预算，audit PASS 后停止扩锁

- **现象**：一个 collector 路径绑定错误在连续多轮中继续暴露 cleanup、attempt 状态、
  receipt、字段大小写、精确计数、repo-relative / evidence-relative、reparse、LF checkout、
  manifest fixture persistence 等问题。每轮 review 都发现新的真实边角，修复又扩大
  policy / schema / tests / docs；最终上下文超过 50 万 token，耗时远超一次 Pad QA。
- **根因**：把“工具合同尽可能完备”当成无上限目标，没有在任务开始时规定协议修复轮次、
  必须覆盖的产品风险、可接受残余风险与停止点。review 一次只交付一个 finding，修复后
  又产生新的 exact oracle，形成“审计越细 → 新字段越多 → 再新增审计”的循环。
- **对策**：
  1. 每个工具合同先冻结 3–5 个用户风险不变量；只有能直接导致错机型、越权、伪 PASS、
     不可复现或丢证据的问题才进入 P1。
  2. 同一不变量最多经历“修复 → scoped review → 一次定点补丁”两轮；第三轮直接停止并
     重新设计状态模型，不再逐 finding 叠加。
  3. review findings 必须批量交付；同一 owner 一次修完，不能“一条 finding 一个 agent”。
  4. 独立 audit PASS 后立即冻结 audited commit，下一步只允许 receipt settlement 或 QA；
     没有真实 QA 失败或对象漂移时，禁止继续增加工具门。
  5. 上下文到 25/30/35 万时分别记录水位、触发 checkpoint 和交接；工具协议循环也必须
     受同一阈值约束，不能因“马上修完”继续。
- **应用判据**：若连续两次修复后仍有新的协议边角，先停下来判断它是否属于用户承诺或
  真实越权；纯理论完备性、可无限精确化的统计字段和自洽摘要优化一律延后。
- **来源**：2026-11-07 G2 runtime 工具从一次路径错误扩展到多轮状态机修复；本 lesson
  用于后续 QA、下载、播放器与本项目其他高风险工具链。

## L039 控制面必须与产品验收分层并设结构性止损

- **现象**：一个 cleanup 路径 bug 扩张为多轮 bespoke contract、audit、state 与 handoff
  控制面；最终 cleanup 修复的隔离 PRE 回归达到 `116 PASS / 0 FAIL / 188 dynamic-negative /
  3 StaticSelfTest PASS`，但 handoff 仍报 `65 errors`。新增的大量字段、精确计数和审计协议没有
  产出等价的 TTS / 产品证据，工具工作本身反而成为目标。
- **根因**：把内部工具完整性当成产品目标；contract、receipt、manifest、docs 与 tests 互相
  exact 绑定，一次变更同时制造多个 oracle；PRE 与 settled 状态混用；手写状态机和审计协议
  持续扩张；任务开始时没有定义产品可观察承诺、最便宜可信证据与结构性停止点。
- **对策**：先定义用户可观察承诺和最便宜可信证据，再选择控制面。每个工具 / 门禁任务预设
  时间盒、往返上限与中止后备选；同一控制面问题最多经历 2 轮修复，并最多执行 1 次全长
  回归。第二轮仍出现 P1 时立即停止，转到隔离 fixture、简化门禁、成熟开源 / 标准工具、
  后置非核心能力或明确延期；不得依靠继续增加字段、gate 或 exact count 推进。
- **安全边界**：允许放宽的只能是既非产品、又非安全、又非数据完整性的流程门。P0/P1 产品
  真实失效、设备授权与顺序、生产服务器写、数据迁移 / 完整性与用户可见状态一致性不得跳过；
  任何放宽或跳过必须写明风险、补偿证据和用户批准边界。
- **分层要求**：“产品验收”证明用户承诺在真实入口成立；“控制面机制”只证明协调、审计与
  工具可靠性。两者不得互相冒充。标准 Git / test runner、成熟开源实现或协议标准优先于
  bespoke harness；工具局部 PASS 不是产品 PASS。
- **来源**：2026-11-07 Aurorama TTS/G2 control-plane 收口、cleanup `de6dd31e` 与 handoff
  `65 errors` 复盘；用于后续 QA、下载、播放器及其他高风险工具链。

## L040 修复要按 bug lineage 核候选祖先与候选回归，不能只看任务合并

- **现象**：历史修复可能在下一次集成或发布后再次以用户可见形式出现；仅看到任务曾完成、
  分支曾合并、CI 绿或单测存在，无法回答“这个具体 bug 是否仍在当前候选”。Aurorama 1.4
  W90 的歌词回页、下载多选、即时反馈等缺陷说明，版本推进后仍需逐个用户场景复核。
- **根因**：流程以任务 / 分支 / patch-id 为中心记录交付，缺少“用户 bug → fix commit →
  回归 test / fixture → 当前候选”的逐项绑定；同时 `release candidate` 与普通分支、历史
  1.4 证据、控制面局部测试之间没有统一解释边界。
- **对策**：
  1. 每个用户 bug 保留稳定 `V2-FIX-<AREA>-NNN`，复发时沿用同一 ID，只追加 fix / test。
  2. 候选祖先和候选回归同时成立才可写 `FIXED_IN_CANDIDATE`；前者用 Git
     `merge-base --is-ancestor` 或负责人批准的 `EQUIVALENT`，后者在候选 commit / 包上执行。
  3. 核心或用户直接可见 bug 至少执行一条真实入口场景；单测、静态合同、CI 或旧版本通过
     都不能单独代替。
  4. 并行分支、cherry-pick、tag / Release 前只生成该范围的小矩阵；不要扩成全仓扫描或
     新 bespoke 审计系统。
- **证据边界**：若 Git / 测试无法区分漏测、漏合并与行为回归，状态只能是
  `CAUSE_NOT_PROVEN / UNPROVEN`；下一步只针对具体 lineage 比较最后已知好 tag、首个坏候选
  与相关 test / fix，必要时才 `git bisect`。
- **来源**：2026-11-07 非 TTS 2.0 规划时对 release tags、v1.4 候选 lineage、W90 已知债与
  当前 G1-S6 矩阵的只读审计；执行规则见 D212 / R034。
