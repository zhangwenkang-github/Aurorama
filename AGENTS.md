# Cinefin 协作约定（2026-11-26 · 2.0.2 版）

- 全程使用简体中文回答。
- **负责人**：Kimi Code（主负责人/技术总监）：规划、派任务、验收、收口。实现优先派 deepseek 子代理。
- **设计分权（2026-11-18 用户拍板，强制）**：一切设计决策（视觉方向、主题 token、配色、字体、布局、logo/图标选型、动效规格）由 Kimi 亲自制定，可借助设计类 skill；deepseek 子代理只按已定稿的 `V2_DESIGN.md`/DESIGN token/任务卡执行代码实现，**不得让 deepseek 做设计选择或自由发挥样式**。
- 本约定取代 2026-11-18 前的 V2 重流程（旧流程文档已归档 `docs/archive/2026-11/v2-old-process/`，仅供追溯）。

## 开工读序（每次会话）

1. `docs/PROJECT_PLAN.md`（任务线一句话状态 + 当前焦点）
2. 所在波次/任务线文档的**顶部状态块**（不要通读全文）
3. 记忆库索引 `F:\Develop\codex_work\Documents\Kimi\engramory-memory\MEMORY.md`（按需打开条目）
4. 2.0.2 以 `docs/V202_TASKS.md` 为唯一任务台账，设计定稿 `docs/V202_DESIGN.md`；2.0.1 台账 `docs/V201_TASKS.md` 与 2.0 账本/架构/设计文档仅追溯用。外部原始需求 `F:\Develop\codex_work\Documents\Codex\2.0更新文档.md` 永久只读。

## 工作方式（波次制，2026-11-24 按 2.0.1 用户拍板修订）

- 2.0.2 交付按 `docs/V202_TASKS.md` 台账（W0 + C1–C9）推进；当前波次见其状态块。
- **波次按依赖与风险排，不照搬需求编号**；下载类任务分散到所属功能波次，不设独立下载波次。
- **每个主题必须落卡**（台账登记编号/波次/验收/证据），收口逐卡核销，禁止漏排。
- 每个子代理只领一个"一屏能验证完"的小目标；任务卡自包含（目标/文件边界/验收标准/门禁/红线）。
- **adb 取证与简单修复下放子代理**（2026-11-23/24 用户拍板）：logcat 过滤 / dumpsys / uiautomator dump / 安装点按均可；负责人只复核证据关键行。复杂排错（跨层/复现困难/多假设）才由负责人亲自做。
- 子代理完成 = 门禁绿 + 证据（构建/测试输出关键行、真机文本取证）+ 交接说明；负责人抽查关键 diff 与证据后合入。
- **负责人会话任务量弹性化**：不按固定卡数，按负责人实际上下文消耗排——子代理为主、负责人只审证据的波次可多排卡；需负责人大量读写的少排。每波次收口评估剩余额度再定下一波规模。
- **意外缓冲**：每波次预留 ~20% 容量；短期无法解决的降级 KNOWN_ISSUE 落档后继续；用户新需求按内容归属插入最合适波次（可插队任意未开始波次），登记台账。
- 返修：resume 同一子代理一次；重复失败或范围漂移即停止，升级主负责人处理。

## 质量门（底线，不随流程精简取消）

- 收口前：构建 + `ktfmtCheck` + 相关模块单测绿。
- 核心用户链路必须真机（或真实 fixture）验证：Worker `SUCCESS`、页面打得开、日志无报错都不算完成；要看到真实任务的真实结果。
- 测试 fake 不得替生产协议映射作证；跨层关键边界至少一条契约测试或可复现 fixture。
- 截图预算：默认 0 截图；用 `dumpsys` / `uiautomator dump` / 过滤 logcat 取证。需视觉判断时单次最多 2 张压缩图（长边 ≤1280px、≤300KB），看完即删。

## 红线

- 生产服务器 `jellyfins.zhangwenkang.com` 永久只读，禁止任何写入/删除（App 播放链路自身的请求除外）。
- 服务器写测试只用用户指定的独立可写测试服务器；凭据位置 `F:\Develop\codex_work\Documents\Codex\资源.txt`，值不得进仓库/日志/报告/记忆。
- 设备：禁止 `pm clear`/卸载/降级；禁止改系统设置。K60=`8e875894`，Pad=`43af8627`。
- push/tag/Release 需用户授权（本次 1.4.1 与 v2-integration 已授权除外）。

## 环境

- 终端一律 PowerShell 7 `pwsh`（禁止 Windows PowerShell 5.1）；读无 BOM UTF-8 文件用显式 UTF-8 API。
- 构建：`export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'; ./gradlew.bat :app:phone:assembleDebug --console=plain`（缺 GRADLE_USER_HOME 会重下整套依赖）。

## 文档治理

- 三层：常驻（`PROJECT_PLAN.md` ≤150 行 + 本文件）→ 波次/线文档（状态块在顶部）→ 存档 `docs/archive/YYYY-MM/`。
- 写文档 = **覆盖更新状态块**，禁止追加流水账；日志段只留最近 ~5 条。
- 线文档超 ~1500 行：历史段移到 `docs/archive/YYYY-MM/<文档名>.full-<日期>.md`，活文档留指针。
- 不新建零散 .md；新需求进 `V2_REQUIREMENTS.md`，新进度进对应波次文档。
- 备份：文档大改后或每周 `pwsh tools/backup-docs.ps1`。

## 收口（每次任务结束做五件事）

1. 回写波次/线文档状态块 + `PROJECT_PLAN.md` 对应行。
2. engramory checkpoint：有持久价值才写记忆，并报告 added/updated/archived/skipped。
3. 改过代码就跑 `graphify update .`。
4. 最终回复说清：结论、commit/SHA、验证过什么、未完成/BLOCKED、下一步入口。不要只说"完成"。
5. **交接提示词（2026-11-21 用户拍板，此后每次交接必做）**：为下一任务/下一波次写一段可直接粘贴的开工提示词，放在 `PROJECT_PLAN.md` 当前焦点节（波次文档已建则放其状态块）。格式必须遵循下文「开工提示词统一标准」。

## 开工提示词统一标准（2026-11-21 用户拍板）

- **固定骨架 + 变量槽**：段序不可变；【槽】是唯一允许逐次填写的内容。固定纪律（门禁/红线/质量门/设备纪律）一律引用本文件，**不在提示词里复述**（开工读序第一步必读 AGENTS.md，复述是重复消耗上下文）。
- **计划以任务量为准，不以时间为准**：会话容量单位 = 卡；卡数按负责人上下文消耗弹性定（子代理为主可多排，下限 1 张，spike 类除外）。做不完回写状态块交接，不写"半天/一天"口径。
- **实现一律派子代理**（省负责人上下文与额度）；任务卡必须附「证据清单」（构建/测试输出关键行、diff 摘要、真机文本取证要求），验收成本才可控；返修 resume 同一子代理。

骨架（总长度 ≤60 行）：

```
1. 角色与工作区（固定一行）：你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
   工作区：【槽：路径】（分支【槽】）。
2. 开工读序（固定）：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 →【槽：波次文档】状态块
   → 记忆库 MEMORY.md（读序外文件按需，不通读）。
3. 现状（≤4 行）：HEAD 以 git log 为准；上一会话/波次判定一句话；【槽：残项去向，无则省】。
4. 本会话任务（1–2 卡）：【槽：卡号+一句话目标；并行声明；spike 时限（如有）】。
5. 设计分权（固定一行）：设计以【槽：定稿文档/节】为准，deepseek 零设计权。
6. 门禁与纪律（固定引用）：见 AGENTS.md 质量门/红线；adb 取证派子代理，负责人复核证据关键行。
   【槽：本任务特殊取证/素材要求，无则省】
7. 交接（固定一行）：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

灵活调整只允许出现在【槽】位；要加固定内容必须先改本节，不允许只在提示词里加。

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
