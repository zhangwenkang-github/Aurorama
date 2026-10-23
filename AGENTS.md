# Cinefin 协作约定

- 全程使用简体中文回答。
- **开工顺序**：先读 `docs/PROJECT_PLAN.md`（项目总览 / 模块地图 / 任务线 / 多会话协作规程）→ 再读所在任务线的文档。
  播放器线 = `docs/PLAYER_PLAN.md`（该线的需求、决策、进度、验收标准与踩坑库都在里面）。
- 收工前把完成度、勾选项、日志写回**对应任务线文档**；项目级变化（任务线增删、里程碑推进）写回 `docs/PROJECT_PLAN.md`。
- **一条任务线一个会话**，单会话只做 1–2 个"一屏能验证完"的任务，避免上下文过长；需要长任务时先交接再继续。
- **负责人退出契约（2026-10-24，强制）**：阶段或任务收口时，以下项目必须全部完成，缺一项不得宣告结束：
  1. 把完成 / 未完成 / BLOCKED、决策、实际门禁与证据指针写回对应任务线文档；
  2. 更新唯一 `ACTIVE` handoff，含当前 commit、分支、设备 / 锁、未覆盖项、下一步入口与实际上下文水位，旧状态标 `HISTORY`；
  3. 执行一次 Engramory 收尾判断，并在最终回复报告 `added / updated / archived / skipped`、索引行数 / 字节与 validator 结果；
  4. 明确写出下一步由什么角色做什么、第一条只读动作、停止条件；没有下一步时写“无”；
  5. 到 30 万准备交接、35 万必须主动创建新会话。创建时首条 prompt 必须包含专业身份、精确读序、当前事实、非目标 / 红线、第一步与停止条件；
  6. 最终回复固定包含：结论、commit / SHA、门禁与 executed、P0–P3、未覆盖 / BLOCKED、证据链接、记忆同步、下一步。不得只用“完成 / 已推送”作为收口。
- 不要新建零散的 `.md` 说明文件；新需求、新决策、新进度一律写进对应任务线文档。
- 构建命令：`$env:JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; .\gradlew.bat :app:phone:assembleDebug --console=plain`（2026-10-06 工具链迁至 F 盘）。
- 测试服务器 `jellyfins.zhangwenkang.com` 为生产环境，**只读**，禁止任何写入/删除调用。
- **上下文预算（硬约束）**：模型单次请求纯文本 ≤ 880KB、含内联图片 ≤ 48MiB。
  - 不要把截图或大图塞进对话；本地图片用文件链接给出，不要 inline 渲染。
  - 工具输出必须裁剪：只取 `-Last N`、`Select-String` 命中行，禁止整文件回显。
  - 验证优先级：编译 → `adb` 文本命令（`dumpsys` / `uiautomator dump` / 过滤 `logcat`）→ 必要时才截图，看完即删、不贴回对话。
  - **截图预算（2026-10-17 用户拍板）**：实现 / 修复 / 门禁 / 发布会话默认 **0 截图**，不使用 `view_image`；只用 `uiautomator dump` / `dumpsys` / 过滤 `logcat` / `aapt2` / `apksigner` / 哈希 / 本地像素 diff 取证。需要视觉判断时，把证据目录交给**独立视觉 QA 会话**，只接收文字结论；单次视觉验收最多 2 张压缩图（长边 ≤1280px、JPEG/WebP ≤300KB），同一界面只截一次。主会话已看 2 张或证据目录 >5 张即停止看图并交接。
  - 接近上限时主动提醒用户，并建议开新对话（`docs/PLAYER_PLAN.md` 已含全部进度，可无损接续）。
  - **会话交接阈值（2026-10-06 用户拍板）**：上下文接近 **35 万 token** 时主动交接换会话（本机 1M 窗口模型；不等 auto-compact 触发）；交接文档**全程实时更新**（每完成一波 / 做一个决定即回写任务线文档进度日志），不是收尾才写；由此**一个负责人会话只做少量任务（1–2 波）**。

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, use the installed graphify skill or instructions before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
