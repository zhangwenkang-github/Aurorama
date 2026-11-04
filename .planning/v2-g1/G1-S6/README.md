# Aurorama 2.0 · G1-S6 总验收矩阵与 G2 任务卡输入

状态：`D204_ACCEPTED_ENGINE_STRATEGY_A / SHERPA_MATCHA_COMPAT_FALLBACK / HIGH_EXPRESSIVE_SECOND_ENGINE_L1_SPIKE_SCOPING / C1_EXPRESSIVE_ENGINE_PROFILE_OPEN / S6_SCOPED_RECONFIRM_PASS_AFTER_D204 / G1_NOT_PASSED / Q03_OPEN / Q05_CLOSED_D204 / Q04_CLOSED_D201 / RUNTIME_UNVERIFIED / L2_L3_LOCKED`

当前 decision state：`D204_ACCEPTED_ENGINE_STRATEGY_A / Q05_CLOSED_D204 / C1_EXPRESSIVE_ENGINE_PROFILE_OPEN / Q03_OPEN / G1_NOT_PASSED / RUNTIME_UNVERIFIED / L2_L3_LOCKED`。
`EV-S6.status`：`S6_SCOPED_RECONFIRM_PASS_AFTER_D204 / HISTORICAL_SCOPED_REVIEW_PASS_WITH_P2_Q05_OPEN / RUNTIME_UNVERIFIED`。历史 review `PASS_WITH_P2` 仅限定为 Q05 当时 Open 的原始 S6 review，不是 D204 后当前 re-approval。

D204 docs/S6 集成已完成，fresh scoped documentation review 最终为 PASS / no findings，并已记录为当前 review state；460 条行级语义保持 schema v2（外部 456 + D203 addendum 4）；49 个 delivery wave 与 495 条 atomic membership 保持原 schema。READER-060 保持引擎中立，D204-A 仅表示兼容回退 + gated 第二引擎 Spike；不选择第二引擎、exact profile 或 voice。Q03 nested evidence 只陈述底层报告能证明的静态事实。

## 目的

本目录把已接受的 G0 八域、`V2-REQ-BASE-001～005`、A1/A2/BASE 静态合同、S4/Q03 静态输入、D203 voice-pack / 后台 TTS 与 UI V2.0 R1 设计权威，收敛为 S6 规划包。原始 review `PASS_WITH_P2` 只作为 Q05 Open 的历史输入；D204 后 fresh scoped documentation reconfirm 已 PASS。每 future artifact change 仍须重新 review；当前 PASS 不是 G1、QA、runtime、设备或 Release PASS。

## 权威输入与读序

1. `AGENTS.md` 与 `docs/PROJECT_PLAN.md` §3/§5/§7：工作目录、写边界、交接和验证纪律。
2. `docs/V2_PLAN.md` §5.4、§5.10、§5.11.6～§5.11.13、§6、§8～§12、§13 当前 D203/D204/R1/Q03 记录：外部 G0 445 / 376、D203 addendum 4 / normalized MVP 380、E0–E7、Q05 Closed/D204、Q03 与 S6 ACTIVE。
3. `docs/TASKS.md` In Progress、`docs/DECISIONS.md` `V2-D057` / `V2-D198～D204` / Open Q03：当前状态真源。
4. `docs/UI_DESIGN_SYSTEM.md` V2 R1：五主题、RWD、九条交互、无障碍与 `UNVERIFIED_RUNTIME`。
5. `.planning/v2-g1/G1-S1～S5`、A1/A2、BASE-001/003/004、Q03-SBOM/NATIVE/NOTICE/TTS-PDF：只读压缩报告、README 与 manifest。

精确章节、路径、证据等级和“最多证明 / 不能证明”见 `source-index.json`。

## 产物入口

- `acceptance-matrix.json`：445 个外部 source node、4 个 D203 project addendum、449 个归一化 source node；456 条外部原子验收行 + 4 条 addendum，共 460 条；外部 MVP 376、addendum 4、归一化 MVP 380；另含 5 个 BASE MUST 组和 30 条 R1 横向门。
- `row-semantics.json`：460 条 schema v2 行级语义；含 actor、入口、动作组件、预期响应、源锚及显式状态 / 无障碍 / RWD gap。
- `wave-membership.json`：495 条 atomic row membership、560 条 row→wave 边；每条记录带 reason、source capability、entry tags 与 action tags，builder 只做 join。
- `thresholds.json`：50 条只来自已接受需求、决策或 fixture 的可测阈值；`UNRESOLVED` 项明确保留。
- `g2-task-cards.json`：16 张 planning umbrella、49 个可执行 wave；每 wave 有 owner / promise_ids / 1 个结构化验收物，单 wave 最大边界项 8，Q03 卡为 GATED。
- `risk-register.json`：P0/P1/P2/P3 = 1/19/11/2，含跨域不变量、关闭证据和假绿门。
- `source-index.json`：权威章节与证据包的文件、行段、大小、SHA-256、证据上限。
- `CHECKPOINT.md`：30 万交接门后的自包含状态、完成 / 未完成、下一步与停止条件。
- `verify_s6_artifacts.py`：本地只读 verifier。
- `manifest.json`：除自身外的文件大小、SHA-256、LF / CRLF，由生成器最后写入。

## 数据字段定义

- `source_id` / `subitem_id`：接受来源的稳定节点与不可丢复合子项标识。
- `source_origin`：`EXTERNAL_G0` 或 `PROJECT_G0_ADDENDUM`；D203 的四条 Reader 行永不占外部 445 source ID。
- `promise_id`：S6 原子验收行稳定 ID；同一 source 行可拆出多个复合子项。
- `row_id`：`wave-membership.json` 的原子行 join key；每条 atomic row 恰有一条记录，`wave_ids[]` 保留一个或多个显式 wave。
- `source_capabilities` / `entry_tags` / `action_tags`：membership 与 wave 的能力、入口、动作相容证据；每个 capability 必须由具体文件 / FQCN 边界承接。
- `level`：`MUST / SHOULD / LATER / OUT / PROCESS`；等级来自接受矩阵，不做二次升降。
- `mvp`：只对 `MUST` 产品子项为真；外部 `376` 不含 BASE 组与 R1 横向门。
- `entry`：从显式行语义记录 join 的领域入口；精确组件未定处明确保留 G2 实例化缺口，不能据此声称 runtime。
- `operation`：由 `actor + entry_surface + action_verb + action_object + expected_response` 机械组合；不得以 requirement ID 或结果文本冒充动作。
- `observable_result`：接受源中的可观察结果。
- `states_accessibility_rwd`：由显式记录提供，带 `EXPLICIT_ROW_RECORD:<promise_id>` basis；未枚举分支必须写缺口并留给 G2 执行。
- `e0_e7_strength`：当前最高证据与最终要求；任何静态行均为 E0 输入，`runtime/device/packageExecuted=false`。
- `automation_fixture_evidence`：可用 fixture / static package 与不能证明项。
- `candidate_device_evidence`：S6 统一为 `NONE_AT_S6`；不得把历史 1.4 记录升级。
- `threshold_ids`：关联 `thresholds.json`。
- `uncovered`：真实未覆盖、需要 G2 或 Q03 关闭的证据。
- `owner` / `g2_card_ids`：可执行 owner 或明确的非 MVP / BLOCKED / PROCESS 状态。

## E0–E7 定义与证据上限

| 层级 | 证据 | 最多证明 |
|---|---|---|
| E0 | 静态契约 / fixture | 请求形状、schema、golden body、哈希与静态合同 |
| E1 | JVM property / state test | 纯状态、解析、身份、队列与缓存不变量 |
| E2 | Robolectric | Notification / WorkManager / Context 模拟接线 |
| E3 | Compose / UI test | UI 状态映射、语义节点与动作可达 |
| E4 | instrumentation | 真实 Room / WorkManager / ContentResolver / MediaSession 接线 |
| E5 | 可丢弃 profile 真机 | OEM、WAL/SHM、权限、重启与系统界面 |
| E6 | 独立写服 | 真实 Jellyfin 写受理、403、回读与清理 |
| E7 | 候选包核心旅程 | 用户承诺完整真实链路与跨界面等式 |

`E0–E4` 不能证明生产协议 / runtime / device；fake、static fixture、S5 静态覆盖、历史 1.4 记录、Worker `SUCCESS`、截图和“通知出现”都不能单独升级为 E7。核心用户旅程未执行时，不得用 `PASS_WITH_GAPS` 覆盖。

## R1 横向质量门摘要

- 五主题：Prism / Lumen / Nocturne / Monolith / 系统动态色；共享 IA、入口顺序、动作含义、状态语义、可访问名称与 48dp。
- RWD：Compact / Medium / Expanded / Large / ExtraLarge，外加折叠帧；按窗格有效宽度选择，变化时保留滚动、选择、焦点、进行中动作与返回路径。
- 无障碍：正文 ≥4.5:1、必要边界 ≥3:1、48×48dp、2dp 焦点环、18/18 可访问动作名称、200% 重排、Reduce Motion 0ms / 直接切换。
- 阅读正文局部主题不得污染外框、顶栏、按钮和弹层。
- Nocturne 基础圆角仅由 D201 窄覆盖 D057 为 8dp；Prism 12 / Lumen 16 / Monolith 8 / 动态色 12 与其他 D057 内容保持不变。

以上均为静态 / 合同输入。Compose、TalkBack、真实字体、动态色、折叠、权限弹窗与设备行为仍为 `UNVERIFIED_RUNTIME`。

## Q03 开放门

`V2-Q-G1-03` 保持 Open。至少以下均未关闭：

1. D204 已关闭 TTS engine strategy A，但第二引擎、exact profile / model / lexicon / rule、试听、许可与零 App 网络 / 后台锁屏 runtime 仍未关闭；Sherpa / Matcha 仅作兼容回退，不把 D204 写成 Q03 closure；
2. PDFium exact native revision、16 KB issue #56、Range / 206、取消与大文件；
3. candidate packaged SBOM、complete notices、source bundle / written offer、attestation / relink；
4. Media3 / decoder / libmpv / libass 双内核 load-order、符号解析、R8 与设备；
5. 真实候选包、设备、server runtime 与独立 QA。

Q03 与 Reader TTS 卡只能为 `GATED/BLOCKED` 输入；D204 只给出 engine strategy A，second engine、exact profile 与 Q03 均未通过，不能暗中冻结新引擎或 profile，也不能把 TTS 卡转为 READY。

## Q05 当前决策（Closed / Accepted / D204）

用户于 2026-11-04 原话“按推荐选择A。”，A 已接受，B/C 未选。`V2-D204` 只关闭 engine strategy：保留 Sherpa / Matcha 兼容回退，另立高表现力开源第二引擎 Android arm64 可行性 Spike；IndexTTS 2.5、CosyVoice 2、CosyVoice 3、GPT-SoVITS 均未选择，任一候选都没有已证实的官方 Android arm64 runtime。Matcha `zh-en` 只补样 / 兼容回退，Kokoro speaker 58 主观否决不整体淘汰家族；官方 v1.1-zh 模型页有 4 个样音可试听但本轮未执行。CosyVoice 2/3 仓库 / 模型权重许可页面冲突未解析。用户主观小说质量门仍未过，exact profile / voice、许可 / notice / hash、RAM / RTF / 首音、后台锁屏与 runtime 全部保持 Open。

## S6 完成与 scoped review 结论

本包保留以下历史静态交付门与原始 review `PASS_WITH_P2`；D204 后 `EV-S6` 当前状态为 `S6_SCOPED_RECONFIRM_PASS_AFTER_D204 / HISTORICAL_SCOPED_REVIEW_PASS_WITH_P2_Q05_OPEN / RUNTIME_UNVERIFIED`：

- 外部 `445` 个 source node / `376` 个 MVP 子项保持可复算，D203 另列 4 个 project addendum / 4 个 MVP；归一化 source 449、MVP 380；
- 五个 BASE MUST 组与 R1 横向承诺均有 owner / card；
- 每个原子行有可观察结果、E0–E7 强度、fixture / device 边界、阈值引用、未覆盖和 G2 owner；
- G2 卡 `non_goals[] >= 2`，所有 MUST 承诺被至少一张卡承接；
- 460 条行语义均有动作组件与源锚；最终 live 口径为 57 negative fixtures + 10 base checks + 1 计数输出 = 68 checks。复核前为 55 + 10 + 1 = 66（相对基线 49 新增 6 negative）；本轮再以通用 evidence overclaim 与 READER-060 主引擎等价写法两条 negative 收敛，故最终相对基线累计新增 8 negative；全部真实执行并通过。
- Q03 / Q04 / UI / runtime / G1 状态等式成立；
- JSON、manifest、LF / CRLF 与本地 verifier 通过。

机械检查不能单独证明 460 条自然语言全部等价；D204 后 fresh scoped documentation review 已 PASS / no findings，当前记录不是 G1、runtime、device 或 independent QA PASS。每 future artifact change 仍须重新 review；原始 S6 P2 `__pycache__` 仍不得进入提交。

## 非目标

- 不运行 Gradle、ADB、设备、服务器、网络探测、独立 QA、Release 或生产实现。
- 不修改生产源码、Gradle、Manifest、resources、schema、其他证据目录、memory；本包只重建根 `.gitattributes` 白名单与 `G1-S6/`。
- 不选择 TTS 第二引擎、exact voice-pack profile、PDF 最终引擎、TTL / 排序 / 合并策略或任何未接受的产品取舍。
- 不把 S6 `PASS_WITH_P2`、静态 fixture、S5 覆盖、R1 设计权威或历史 1.4 记录写成 G1 / runtime / device / QA PASS。

## 停止条件

出现以下任一项立即停止并报告 `BLOCKED`，不得代签：下一步超出只读 / 静态 Second-engine L1 Spike scoping 与许可门；`__pycache__` 或其他非白名单产物进入提交；Q03 需要选型或用户拍板；把 D204-A 写成具体引擎已选或解锁 L2/L3；需要设备 / 服务器 / 网络 runtime；需要生产实现 / 独立 QA / Release；G0 计数、UI 权威或 Q03/Q04/Q05 状态漂移；manifest 不能复算。

## 下游 G2 消费方式

1. 按 `g2-task-cards.json.status` 过滤：`GATED_*` 不进入实现，`READY_FOR_G2_PLANNING` 仍需负责人批准。
2. 用 `covers_must_source_ids`、`covers_base_groups`、`covers_r1_promises` 锁定每卡承诺；不得删掉只挂在一张卡上的 MUST。
3. 从 `delivery_waves[].file_interface_boundaries` 读取具体文件 / FQCN，并用 `wave_dependencies`、`external_dependencies` 与 `stop_condition` 复建执行 DAG；不得把 planning umbrella 当实现波。
4. 从 `risk-register.json` 继承 P0–P3；卡内只能关闭有候选包 / fixture 证据的风险。
5. 实现完成后由负责人另派独立 QA；本卡输入不授权 QA、设备、服务器写或 Release。

## 复现与 verifier

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -X utf8 .planning/v2-g1/G1-S6/build_s6_artifacts.py
python -X utf8 .planning/v2-g1/G1-S6/verify_s6_artifacts.py
python -X utf8 .planning/v2-g1/G1-S6/verify_s6_artifacts.py --self-test
```

生成器只写 `G1-S6/`，不访问网络、设备或服务器。`row-semantics.json` 由显式 seed 与确定性、可回链源行的动作组合生成；`wave-membership.json` 是独立显式 join 表，builder 不得从行顺序、哈希或关键词重新分配。原始 review 只作为 Q05 Open 的历史；D204 后 fresh scoped documentation reconfirm 已 PASS。每 future artifact change 仍须重新 review，并重跑 verifier、57 negative fixtures + 10 base checks + 1 计数输出（68 live checks）与新鲜度审计。
