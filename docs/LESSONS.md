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
