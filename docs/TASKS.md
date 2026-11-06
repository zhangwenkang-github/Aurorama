# Aurorama 2.0 任务看板

> 只维护当前可执行任务。需求全文、决策理由和进度日志分别放 `V2_PLAN`、`DECISIONS`、ADR。

## In Progress

> 当前覆盖状态（2026-11-06，`V2-D208`）：`RUNTIME_QA_DEFERRED_TO_G2 /
> G1_STATIC_CLOSURE_IN_PROGRESS / QA_V9_BLOCKED_TOOL_NONCONVERGENCE /
> PRODUCT_P0_P3_NOT_ASSESSED / K60_LOCKED /
> validation.handoffPhase=POST_QA_RUNTIME_DEFERRED /
> INDEPENDENT_INTEGRATION_REVIEW_PASS=PASS / PRE_HANDOFF_AUDIT_PASS=PASS /
> QA_DISPATCHABLE=DEFERRED_TO_G2`。历史 `POST_HANDOFF_AUDIT_READY`、exact `auditReceipt`
> 与 qa-v9 证据原样保留；当前设备锁只要求 `RELEASED_ACTIVE_0`，QA 后 transition set 按
> `qa-v9/**` 与授权 docs / handoff 路径单独校验。

> QA-v9 实际执行为 `CORE_STEPS_1 / PAD_10_MIN_PARTIAL_TOOL_BLOCKED`：仅 `zf_001` 有真实
> AudioTrack start partial；`zm_010`、完整 10 分钟、gap、完整通知/focus、零网络与 K60 均未执行。
> cleanup / restore PASS、锁 `ACTIVE=0`，K60 `8e875894` 未登记。当前无 QA role、无
> `activePrompt` 可执行 prompt；`V2-D209` 已批准
> `G2_ENTRY_AUTHORIZED_RUNTIME_CONTRACT_IN_PROGRESS /
> G2_RUNTIME_CONTRACT_PACKAGE_READY / PAD_RUNTIME_TOOLS_READY_PENDING_INDEPENDENT_QA /
> K60_GATED_LOCKED`。全新
> `G2_RUNTIME_CONTRACT_OWNER_AURORAMA_2026_11_06` 已建立独立合同根、候选身份、13 个产品门、
> report schema 与合同校验器；没有执行 QA、adb、设备、网络、产品实现、Release 或服务器写。
> 用户以 `V2-D210` 确认下一步并签署绑定合同 / candidate SHA 的授权 receipt；全新 runtime
> tool owner 已完成三件工具与 `48/48` 静态自测。下一阶段只能由另一全新 Pad owner 执行。
> 当前不是 QA / runtime / G1 PASS，延期不是
> `PASS_WITH_GAPS`。
>
> 历史 `ROUND9_READONLY_INTEGRATION_REVIEW_OWNER`、
> `ROUND8_READONLY_INTEGRATION_REVIEW_OWNER`、`ROUND7-P1-01`、`ROUND6-P1-01` 与 B10
> `86/86`、B11 `96/96` 回归只作退役证据保留，不得复活为当前执行角色或 PASS；当前
> settlement 合同回归 `104/104 PASS + TEMP_CLEANUP=PASS`；D209 只授权全新
> `G2_RUNTIME_CONTRACT_OWNER` 已只建立 runtime 合同；这仍不等于 QA、设备、G2 实现或
> Release 已授权。

| ID | 需求域 / 关系 | 目标 | 非目标（≥2） | 依赖 | 验收判据 | 停止条件 |
|---|---|---|---|---|---|---|
| `V2-SOLUTION-BASELINE / V2-G2-ENTRY-CONTRACT` | G1→G2 入口 static→runtime QA；当前独立 Pad 待派发 | `V2-D209` 合同与 `V2-D210` 设备授权已落档；全新 runtime tool owner 已完成 collector / finalizer / cleanup verifier、manifest、fixtures 与 `48/48` 静态自测。下一阶段只允许全新 `G2_PAD_QA_OWNER_2026_11_06` 执行 Pad | ①不复用 qa-v9 工具 / owner / 证据 / prompt，也不复用 tool owner；②不写产品代码、G2 实现、Release 或服务器写；③不关闭 Q03，不冻结 exact profile / voice，不接受 Pad P2，不让 K60 提前登记 | `V2-D202`～`D205`、`V2-D208`～`D210`；contract / receipt / runtime-tools manifest；qa-v9 摘要仅历史；G1-S6 checkpoint + 16 张 G2 卡；A1/A2、BASE-001/003/004、Q03 入口；状态 `G2_RUNTIME_CONTRACT_PACKAGE_READY / PAD_RUNTIME_TOOLS_READY_PENDING_INDEPENDENT_QA / K60_GATED_LOCKED / PRODUCT_P0_P3_NOT_ASSESSED` | main SHA `1b3aedb1707f6444505dd96f5a4fd39b6ab27bd24c82d58a86272db62b7ac2a3`；工具 `48/48 PASS / 33 negative / DEVICE_COMMANDS=0`，contract validator PASS；工具提交 clean 后由另一全新 Pad owner 执行一次 600 秒矩阵；唯一 ACTIVE=1 / TASKS=3 | 非零 / FAIL_CLOSED、owner / 工具 / 证据复用、K60 提前、窗口延长 / 重试、证据篡改、产品 / Q03 / profile / P2 / G2 实现过度声明、越界写入或新范围决策时停止 |
| `V2-G1-L1-EVIDENCE` | G1 L1 证据与静态收口 | 保留 qa-v8/qa-v9 历史证据与全部 fail-closed 回归，只允许补可离线复核的 G1 静态缺口 / G2 planning 输入 | ①不下载 MOSS、不触发新网络域；②不关闭 Q03、不冻结 profile / voice、不接受 Pad P2；③不用旧 30 分钟、stop-only、安装、cleanup、partial AudioTrack 或工具阻塞替代产品证据 | main `427,009,671 B / 252a3a15f637b05e874d9b2ad2aa0c48076d2babb83f66c5ec18683dc533e161`；peer `12,696 B / 61c79aa1b56b9fc921ce23199394d342f4badad31389b842f38c1d5c3a4bebab`；tools 31/2984/digest `69a723ab58b0a8425887b2b71b1993c110d56a319dac7ca9286a9411189d89f4`；QA-v8 tools 冻结 | `qa-v9/report.json` 为 `BLOCKED_TOOL_NONCONVERGENCE / BLOCKED / PRODUCT_P0_P3_NOT_ASSESSED`；`CORE_STEPS_1 / PAD_10_MIN_PARTIAL_TOOL_BLOCKED / K60_LOCKED`；cleanup PASS；历史 `auditReceipt` 与 transitionPaths 保留，当前 post-QA 集合并列校验；ACTIVE=1 / TASKS=3 | 复用 QA / audit owner、改旧 report 或 raw 证据、digest / manifest / APK 漂移、证据根删除 / 移动、产品过度声明、设备 / 网络 / 生产写或越界时停止 |
| `V2-G1-L1-KOKORO-EXACT` | G1 L1→L3 scoped 子任务；runtime 部分延期 | 把完整 Pad/K60 10 分钟 runtime matrix 延期到 G2 入口门；G1 只保留 qa-v9 partial 证据和静态 planning 输入 | 不做 QA 重试、Pad/K60 设备动作、P2 接受、生产代码、Q03、G2 实现或 Release；不把 qa-v9 start-only、cleanup、旧 K60 v5 或 Pad v6/v7 结果扩写为完整门 | v5 输入基线、`qa-v9/report.json`、`qa-v8/report.json` 摘要、`qa-v8/tools` digest、G1-S6 checkpoint；`ACTIVE=1 / TASKS=3` | 当前 `POST_QA_RUNTIME_DEFERRED`、auditReceipt 历史 PASS、`QA_DISPATCHABLE=DEFERRED_TO_G2`、padQa retired / dispatchable=false、K60 `8e875894` 未登记、锁 `ACTIVE=0`、产品 P0–P3 `NOT_ASSESSED` 一致 | QA / runtime PASS 过度声明、QA 重试、设备 / 首动作 / hash / path / owner / role writeScope 漂移、K60 提前、需产品代码或新范围决策时停止 |

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

## Todo

| ID | 需求域 | 目标 | 依赖 | 退出条件 |
|---|---|---|---|---|
| `V2-IMPLEMENTATION-PLAN` | G2 开发门 | 交付波次、完整任务卡、QA/集成顺序和用户开工批准 | G1 完整收口 + 用户明确解除 E 门；当前不得启动 | 用户批准开始实现 |

## Done

| ID | 成果 | 证据 |
|---|---|---|
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
