# Cinefin 协作约定

- 全程使用简体中文回答。
- 开工前先读 `docs/PLAYER_PLAN.md`：它是任务计划、需求、决策、完成度、进度勾选与验收标准的**唯一权威文件**；收工前把完成度、勾选项和更新日志写回该文件。
- 不要新建零散的 `.md` 说明文件；新需求、新决策、新进度一律写进 `docs/PLAYER_PLAN.md`。
- 构建命令：`$env:JAVA_HOME='D:\Android\Android Studio\jbr'; .\gradlew.bat :app:phone:assembleDebug --console=plain`。
- 测试服务器 `jellyfins.zhangwenkang.com` 为生产环境，**只读**，禁止任何写入/删除调用。
- **上下文预算（硬约束）**：模型单次请求纯文本 ≤ 880KB、含内联图片 ≤ 48MiB。
  - 不要把截图或大图塞进对话；本地图片用文件链接给出，不要 inline 渲染。
  - 工具输出必须裁剪：只取 `-Last N`、`Select-String` 命中行，禁止整文件回显。
  - 验证优先级：编译 → `adb` 文本命令（`dumpsys` / `uiautomator dump` / 过滤 `logcat`）→ 必要时才截图，看完即删、不贴回对话。
  - 接近上限时主动提醒用户，并建议开新对话（`docs/PLAYER_PLAN.md` 已含全部进度，可无损接续）。
