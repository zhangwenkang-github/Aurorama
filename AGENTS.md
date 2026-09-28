# Cinefin 协作约定

- 全程使用简体中文回答。
- **开工顺序**：先读 `docs/PROJECT_PLAN.md`（项目总览 / 模块地图 / 任务线 / 多会话协作规程）→ 再读所在任务线的文档。
  播放器线 = `docs/PLAYER_PLAN.md`（该线的需求、决策、进度、验收标准与踩坑库都在里面）。
- 收工前把完成度、勾选项、日志写回**对应任务线文档**；项目级变化（任务线增删、里程碑推进）写回 `docs/PROJECT_PLAN.md`。
- **一条任务线一个会话**，单会话只做 1–2 个"一屏能验证完"的任务，避免上下文过长；需要长任务时先交接再继续。
- 不要新建零散的 `.md` 说明文件；新需求、新决策、新进度一律写进对应任务线文档。
- 构建命令：`$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat :app:phone:assembleDebug --console=plain`。
- 测试服务器 `jellyfins.zhangwenkang.com` 为生产环境，**只读**，禁止任何写入/删除调用。
- **上下文预算（硬约束）**：模型单次请求纯文本 ≤ 880KB、含内联图片 ≤ 48MiB。
  - 不要把截图或大图塞进对话；本地图片用文件链接给出，不要 inline 渲染。
  - 工具输出必须裁剪：只取 `-Last N`、`Select-String` 命中行，禁止整文件回显。
  - 验证优先级：编译 → `adb` 文本命令（`dumpsys` / `uiautomator dump` / 过滤 `logcat`）→ 必要时才截图，看完即删、不贴回对话。
  - 接近上限时主动提醒用户，并建议开新对话（`docs/PLAYER_PLAN.md` 已含全部进度，可无损接续）。
