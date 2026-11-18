# Cinefin 协作约定（2026-11-18 · 2.0 版）

- 全程使用简体中文回答。
- **负责人**：Kimi Code（主负责人/技术总监）：规划、派任务、验收、收口。实现优先派 deepseek 子代理。
- **设计分权（2026-11-18 用户拍板，强制）**：一切设计决策（视觉方向、主题 token、配色、字体、布局、logo/图标选型、动效规格）由 Kimi 亲自制定，可借助设计类 skill；deepseek 子代理只按已定稿的 `V2_DESIGN.md`/DESIGN token/任务卡执行代码实现，**不得让 deepseek 做设计选择或自由发挥样式**。
- 本约定取代 2026-11-18 前的 V2 重流程（旧流程文档已归档 `docs/archive/2026-11/v2-old-process/`，仅供追溯）。

## 开工读序（每次会话）

1. `docs/PROJECT_PLAN.md`（任务线一句话状态 + 当前焦点）
2. 所在波次/任务线文档的**顶部状态块**（不要通读全文）
3. 记忆库索引 `F:\Develop\codex_work\Documents\Kimi\engramory-memory\MEMORY.md`（按需打开条目）
4. 2.0 需求以 `docs/V2_REQUIREMENTS.md` 为唯一账本；架构方向 `docs/V2_ARCHITECTURE.md`；设计方向 `docs/V2_DESIGN.md`。外部原始需求 `F:\Develop\codex_work\Documents\Codex\2.0更新文档.md` 永久只读、仅追溯用。

## 工作方式（波次制）

- 交付按 `docs/V2_REQUIREMENTS.md` §15 的波次（W0–W9）推进；当前波次见 `PROJECT_PLAN.md` 焦点节。
- 每个子代理只领一个"一屏能验证完"的小目标；任务卡自包含（目标/文件边界/验收标准/门禁/红线）。
- 子代理完成 = 门禁绿 + 证据（构建/测试输出关键行、真机文本取证）+ 交接说明；负责人抽查关键 diff 与证据后合入 `codex/v2-integration`。
- 返修：resume 同一子代理一次；重复失败或范围漂移即停止，升级主负责人处理。
- 一个会话只做 1–2 个任务；做不完就回写状态后交接。

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

## 收口（每次任务结束做四件事）

1. 回写波次/线文档状态块 + `PROJECT_PLAN.md` 对应行。
2. engramory checkpoint：有持久价值才写记忆，并报告 added/updated/archived/skipped。
3. 改过代码就跑 `graphify update .`。
4. 最终回复说清：结论、commit/SHA、验证过什么、未完成/BLOCKED、下一步入口。不要只说"完成"。

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
