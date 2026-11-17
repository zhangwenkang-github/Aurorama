# Cinefin 协作约定（2026-11-17 精简版）

- 全程使用简体中文回答。
- **负责人**：Kimi Code。本约定取代 2026-11-17 前的 DeepSeek 版重流程；质量底线保留，仪式层全部精简。

## 开工读序（每次会话）

1. `docs/PROJECT_PLAN.md`（任务线一句话状态 + 当前焦点）
2. 所在任务线文档的**顶部状态块**（不要通读全文）
3. 记忆库索引 `F:\Develop\codex_work\Documents\Kimi\engramory-memory\MEMORY.md`（engramory，按需打开条目）

## 工作方式

- 一个会话只做 1–2 个"一屏能验证完"的任务；做不完就回写状态后交接。
- 终端一律 PowerShell 7 `pwsh`（禁止 Windows PowerShell 5.1）；读无 BOM UTF-8 文件用显式 UTF-8 API。
- 构建：`$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; .\gradlew.bat :app:phone:assembleDebug --console=plain`
- 测试服务器 `jellyfins.zhangwenkang.com` 为生产环境，**只读**，禁止任何写入/删除调用。
- 本地、可逆、已定位的脚本/harness 小失败：同一 owner 限时修复后受控重试一次；重复失败、对象漂移、生产写、范围扩大才停止上报。

## 质量门（保留的底线）

- 收口前：构建 + `ktfmtCheck` + 相关模块单测绿。
- 核心用户链路必须真机（或真实 fixture）验证：Worker `SUCCESS`、页面打得开、日志无报错都**不算**完成；要看到真实任务的真实结果。
- 测试 fake 不得替生产协议映射作证；跨层关键边界至少一条契约测试或可复现 fixture。
- 截图预算：默认 0 截图；用 `dumpsys` / `uiautomator dump` / 过滤 logcat 取证。需视觉判断时单次最多 2 张压缩图（长边 ≤1280px、≤300KB），看完即删。
- 上下文：工具输出必须裁剪（`-Last N` / 过滤命中行）；接近 35 万 token 主动交接。

## 文档治理

- 三层：常驻（`PROJECT_PLAN.md` ≤150 行 + 本文件）→ 活跃线文档 → 存档 `docs/archive/YYYY-MM/`（按需读取）。
- 写文档 = **覆盖更新状态块**（最新结论在顶部），禁止追加式流水账；日志段只留最近 ~5 条，更老的移 archive。
- 线文档超 ~1500 行：把历史段整体移到 `docs/archive/YYYY-MM/<文档名>.full-<日期>.md`，活文档留指针。
- 不新建零散 .md；新需求/决策/进度并入对应线文档。
- 备份：文档大改后或每周运行 `pwsh tools/backup-docs.ps1`（docs + 记忆库 → `F:\Develop\codex_work\backups\aurorama\`，各留 10 份）。

## 收口（每次任务结束做四件事）

1. 回写线文档状态块 + `PROJECT_PLAN.md` 对应行。
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
