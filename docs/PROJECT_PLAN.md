# Cinefin · 项目总览与协作规程

> **新会话第一步读这里**，再读对应任务线文档。最后更新：2026-09-30　分支 `master`
> 项目级状态、任务线地图、协作规程都在本文件；单个任务线内部的需求 / 进度 / 决策写各自的线文档。

## 1. 项目是什么

Cinefin = 基于 **Findroid**（GPL-3.0，上游 `a28ac9e`）改造的**自用 Jellyfin 客户端**：
平板优先、深色影院风（墨 + 朱砂），功能对齐官方客户端，并额外做官方没有的**手势体系**与**字幕 / 音轨语言智能识别与跨视频记忆**。

| 项 | 值 |
|----|----|
| 包名 | `com.zhangwenkang.cinefin`（debug 后缀 `.debug`，staging `.staging`） |
| SDK | compileSdk 37 / targetSdk 36 / minSdk 28 |
| 版本矩阵 | AGP 9.4.1 / Kotlin 2.4.20 / Gradle 9.7.1 / Compose 1.12.1 / Media3 1.11.1 |
| 播放内核 | ExoPlayer（硬解）+ FFmpeg 软解 + libmpv（二级兜底，解码失败静默降级） |
| **许可证** | 对外分发必须以 **GPL-3.0** 开源；参考 jellyfin/jellyfin-android（GPL-2.0）只借鉴功能与协议，不复制代码 |

## 2. 模块地图

| 模块 | 职责 | 状态 |
|------|------|------|
| `app/phone` | 手机 / 平板主应用：导航、首页、媒体库、播放页、控制台 WebView | 🟡 主力开发中 |
| `app/tv` | TV 入口（独立 Compose UI） | ⛔ 冻结，阶段后置 |
| `core` | 设计系统（主题 / 颜色 / 图标）、通用工具、常量、下载与图片 Worker | 🟡 |
| `data` | Jellyfin API、Room、仓库（在线 / 离线两套实现）、认证、下载 | 🟡 |
| `player/core` | 播放抽象：`PlayerItem` / `Track` / `Trickplay` / `PlayerChapter` | 🟡 |
| `player/local` | 播放内核：`PlayerHolder`、`PlayerViewModel`、`PlaylistManager`、`TrackSelectionEngine`、`mpv/MPVPlayer` | 🟡 |
| `modes/film` | 影视模式业务页（电影 / 剧集 / 搜索 / 收藏 / 下载页） | 🟡 |
| `settings` / `setup` | 偏好设置 / 首连向导、服务器发现、登录、Quick Connect | 🟡 |

## 3. 任务线（一条线 = 一个文档 = 一个会话）

| 线 | 文档 | 状态 | 当前焦点 |
|----|------|------|---------|
| **播放器**（含手势、字幕渲染、双内核） | `docs/PLAYER_PLAN.md` | 🟡 进行中 | §11 D 已修复（`ce30a03`）；稳定性专项（`9b18b0f`）；§11 A–C/E 播放页改造（`deb73ef`）；**W9–W19 已合并**；**W19-PLAYER（已合并 master `93c72e9`）** 见 §22；**W27-PLAYER（已合并 master `e465a83`）：外挂字幕导入（双内核 libass）+ 字幕语言优先级 + 播放结束行为（自动下一集 / 停在结束帧）+ `PlayerDebugOverlay`，见 §24**；**W20-PLAYER（已合并 master `8d5a383`）：①进度写入 Jellyfin UserData（实测会话上报不落库 → 新增 `PlaybackPositionWriter` 直写 + 常驻上报 + 暂停/切集/退出落盘，退出 / 杀进程 / 切集 / 双内核取证）；②片头尾提示条读设置时长 + 修「控制层淡出把提示条藏掉」缺陷 + 播放面板补档位；③Trickplay 改按需拉当前精灵图 + LRU + 失败降级（纯函数 + 6 单测），见 §23**；待做：§15.5 / §16.5 / §20.5 / §21.5 / §22.5 / §23.5 / §24.5 遗留（Compact 自由窗口取证、mpv stall 不报错、回退档位按会话保留的语义取舍、服务器无 segments/trickplay 数据的真实回归、end-帧 OFF 档服务器缓流复验）+ 许可证 NOTICE（libass/ass-kt，release 前）；遗留：libc++ 覆盖构建补丁需在依赖升级后复核 |
| **浏览体验**（首页 / 媒体库 / 详情 / 搜索） | 暂无独立文档（已完成主体，见 §4 M3） | ✅ 主体完成 | 打磨项按需开线 |
| **连接层**（HTTP(S) / 自签证书 / Quick Connect / 多用户） | 暂无独立文档（设计记入 `docs/ARCHITECTURE.md` §5.4） | 🟢 W31-CONN 实现完成 | **分支 `feature/w31-connection`（已推送未合并）**：自签证书 TOFU（指纹确认 / 记住 / 清除；data 网络层 + 添加服务器 / 登录 / 服务器页 UI；纯函数 + 真实 TLS 集成测试）；多用户（列表 / 切换 / 添加入口既有，修复删除当前用户悬空 + 当前账号标记 + 单测）。遗留：真机联调待设备调度、第二 Jellyfin 用户待服务器提供、WebView 控制台未接信任 |
| **Web 控制台**（内置 WebView + 影阁皮肤） | `docs/web-console-skin.css`（唯一权威副本，改后同步 `app/phone/src/main/res/raw/web_console_skin.css` 与服务器自定义 CSS） | ✅ 基本完成 | 跟随 App 令牌与配色 |
| **下载 / 离线** | 暂无独立文档 | 🟡 有基础（Downloader / Room 离线仓库 / 图片 Worker） | 下载管理 UI 与播放本地文件 |
| **投屏 / 同步观看** | 无 | ⛔ 未开始 | `:player:cast` 模块尚未创建 |
| **稳定性 / 性能 / 发布**（崩溃兜底、体积、GPL 合规） | 无 | ⛔ 未开始 | 收尾阶段 |
| **扩展项目**（阅读器 / 音乐 / UI 重设计 / 测试） | `docs/ROADMAP.md`（阶段）+ `docs/PARALLEL_PLAN.md`（波次）+ `docs/SESSION_BRIEFS.md`（会话模板）+ `docs/ROLE_SKILLS.md`（角色 skill）+ `docs/UI_DESIGN_SYSTEM.md`（S4 设计系统 v1.0） | 🟢 W0–W29 全部完成（master `e465a83`，347 项单测全绿） | W27（外挂字幕导入/语言优先级/结束行为/DebugOverlay）、W28（逐字歌词/提示行清理/编辑增强）、W29（PDF 搜索/本地批注）均已合并推送；Pad 5 + K60 已装新整合版（W29 真机验收进行中）；进行中：W30 音乐音效、W31 连接层（自签证书/多用户）、W32 下载管理；待办：品牌（字体/图标/名字）、全量回归与发布准备 |
| **阅读器**（EPUB / PDF / CBZ） | `docs/READER_PLAN.md`（R1 维护） | 🟢 W9/W15/W22 已合并 master；**W26-READER（已合并 master `a3dfc01`）：双栏「横版整页独占」+ 带纸边对图裁剪合并，`modes:book` 57 → 77 项单测；真机 12/12 命中（4 条纸边裁剪）+ W22 8/8 回归 + 0 误拼 + RTL/progression 不回归，见 READER_PLAN §2 D21 / §7.11**；**W29（已合并 master `e465a83`）：PDF 搜索（PdfBox-Android 2.0.27.0）+ 本地高亮/备注批注，`modes:book` 101 项单测；Pad 5 真机验收进行中** | W4 已交付 PDF 分页懒加载 / CBZ 自研导航 / 三档模式；后续：金田一本体大文档扫描耗时实测、RTL 封面单张、Room 迁移、BC 瘦身复核 |
| **音乐**（播放 / 队列 / 歌词 / 离线） | `docs/MUSIC_PLAN.md`（R2 维护） | 🟢 **W23 交付（已合并 master `c5eca4d`；A `74214e7` / B `0124828` / C `65c6095` / D `179df99`）** + **W24 UI 细化（已合并 master `21a97d0`）** + **W25 扩展（已合并 master `ae8d0bd`）**：内嵌歌词只读探测（结论=服务器已覆盖，不做客户端解析）；本机歌词编辑 / 导入 LRC（UTF-8/GBK）/ 清除覆盖（来源链最高优先级，不写服务器）；悬浮窗「保持显示时长」2/3/5/10 秒 + 常显；设置页接 `pref_music_resume_queue` 开关；真机拦下并修复 K60 竖屏「词」面板半展开裁切；music **86** 项单测 + K60 真机 9 组（MUSIC_PLAN §5.8）；**W28 逐字歌词 + 提示行清理 + 编辑时间轴（已合并 master `e465a83`）；W30 音乐音效 EQ / ReplayGain / 交叉淡化（分支 `feature/w30-audio-fx`，已推送未合并；K60 真机 13 组，MUSIC_PLAN §5.10）** | 后续：离线本地文件取内嵌歌词（如需）；W30 未决 = 本库 100 首 FLAC 无 ReplayGain 标签（需用户打标签 / 本机覆盖文件）、双实例真交叉未排期、M4A RG 标签未实现 |
| **UI 重塑**（设计系统落地） | `docs/UI_PLAN.md`（R3 维护） | 🟢 W8 完成（2026-10-01，master `f99af8d`；W7-R3 已并入 `c545eb9`） | W8-R3（用户复测反馈，D29–D31）：①三页顶栏统一为 `CinefinPageTopBar`（56dp + `statusBarsPadding()`；修复音乐汉堡被状态栏挡、书架顶层去返回箭头与库名「书籍」、媒体库按钮与标题同栏）；②媒体库库卡改版（类型图标磁贴 + 项目数 + A 配色层次，服务器 `ItemFields.CHILD_COUNT`）；③侧栏「媒体库」**默认收起并移到音乐、书架之后**（覆盖 W7 的排布）；④W7 行为不回归（抽屉、侧柜常驻 Lumen、控制台胶囊与选中态）；遗留：spacings 桥接收敛、左缘滑出抽屉在系统手势导航下未验 |
| **测试与验收**（真机矩阵 / 回归 / 性能） | `docs/TEST_PLAN.md`（R4 维护） | 🟢 W2 完成（基线：冷启动中位 1085ms / PSS 247,790kB / APK 96.95MiB；tools/test 6 脚本） | W3：回归支持；测试数据清单待用户确认；W5 全量回归 |

### 3.1 执行顺序（用户 2026-09-28 批准）

1. ✅ **播放器 · 字幕面板补全**（`PLAYER_PLAN.md` §1.1）——延迟 / 双语 / 外观（2026-09-28 完成，真机验证通过）
2. ✅ **播放器 · 音轨面板补全**（§1.2）——音轨延迟 / 描述 / 默认轨记忆（2026-09-28 完成，真机验证通过）
3. **播放器 · libass 字幕渲染**（§1.18，M4 缺口）——特效字幕还原度
4. **下载 / 离线增强**（新线；开工时建 `docs/DOWNLOAD_PLAN.md`）
5. **投屏 / 同步观看**（新线；需先建 `:player:cast` 模块）
6. **播放页 UI/UX 改造组**（`PLAYER_PLAN.md` §11，用户 2026-09-28 记录、要求最后统一做）——
   控件精简 / 图标文案 / 右侧面板 / 控件重排 + 两个 bug（自动选字幕、自动播放）。
   其中 bug 建议**提前到第 3 项之前单独修**（影响"打开就能看"，且线索已写入 §11）。

穿插项（不占主线，可顺手做）：阶段 4 收尾验收（§1.5）、手势打磨（§1.3）、多形态实机走查（§1.12–1.14）；
通知封面（§1.4）2026-09-28 已完成。

**扩展项目并行安排（2026-09-29 定，详见 `docs/ROADMAP.md` / `docs/PARALLEL_PLAN.md`）**：

- 路线图阶段 0–5、波次 W0–W6；**接口先行 → 并行开发**，每会话 1 个 feature 分支 + 独立 worktree；
- 并发上限 4（含项目负责人）→ 每波最多 3 个开发会话；咽喉文件单波单写（PARALLEL_PLAN §1.3）；
- 播放器线的 libass（§1.18）与播放页 UI/UX 改造（§11）已排入扩展波次 W5 / W4，避免两线同时改 `player:core`；
- 播放器线会话首轮需把 `docs/PLAYER_PLAN.md` 决策 D2「音乐不做」更新为与 REQUIREMENTS v1.0 一致（REQUIREMENTS 为唯一需求基线）。

**两条已定决策**：

- **手势不单独成线**（2026-09-28 定）：手势与播放器共用 Activity / 触摸命中区 / HUD，拆线会让两个会话改同一批文件；
  且手势功能本身已完成（长按倍速、双击、滑动 seek、亮度音量、双指缩放、锁屏屏蔽、灵敏度），剩余打磨并入播放器线。
- **TV 端继续冻结**（2026-09-28 用户确认）：`app/tv` 代码保留、不主动改，阶段 8.9 再议。

## 4. 里程碑状态（源自旧 `docs/PLAN.md`，已合并）

| 阶段 | 内容 | 状态 |
|------|------|------|
| M0 | 环境打通、基线构建、服务器连通性 | ✅ |
| M1 | 品牌化（影阁）+ 深灰蓝影院风设计系统 + 首页 | ✅ |
| M2 | 连接层：HTTP/HTTPS、自签名证书、Quick Connect、多用户 | 🟡 Quick Connect 已有；**W31 自签证书 TOFU + 多用户审计完成（待真机联调）** |
| M3 | 浏览体验：首页、媒体库、详情、搜索、筛选 | ✅ 主体完成（抽屉导航 + 海报墙 + 媒体库分区 + 设置对齐官方） |
| M4 | 播放器重构：双内核、libass 字幕、倍速、比例、章节、Trickplay、跳片头、PiP | 🟡 双内核 / 倍速 / 比例 / 章节 / Trickplay / 跳片头 / PiP 已有；**libass 双内核完成：mpv 原生（W15，`0c3f8bc`）+ Exo 路径（W16 `feature/w16-exo-libass-decode`，含 SRT 覆盖与失败回退）**；详见 `PLAYER_PLAN.md` §18 / §19 |
| M5 | 手势体系：长按 2×、滑动 seek、左亮度 / 右音量、双击、双指缩放、锁定、灵敏度 | 🟡 横向 seek（含渐进加速）/ 长按倍速 / 双指缩放已有；锁屏与优先级仲裁见 `PLAYER_PLAN.md` §1.3 |
| M6 | 字幕 / 音轨语言智能识别与跨视频记忆 | ✅ |
| M7 | 下载离线增强、投屏、同步观看 | ⛔ |
| M8 | 稳定性与性能：崩溃兜底、降级策略、布局回归、体积与内存 | ⛔ |

## 5. 多会话协作规程（避免单会话过长）

1. **一条任务线一个会话**；单个会话只做 **1–2 个"一屏能验证完"的任务**，做完就提交并交接。
2. **会话开始**：读本文件 → 读对应任务线文档 → `git log --oneline -10` 看最新状态 → 挑一个任务开工。
3. **会话结束**：更新任务线文档（勾进度 + 写日志）→ 需要时更新本文件 §3 状态 → `git commit`（`feat(线): …` / `fix(线): …`）。
4. **上下文纪律**：工具输出裁剪（`-Last N` / `Select-String`）；截图只在必要时看、看完即删；单会话上下文过半就交接（进度写进文档即可无损接续）。
5. **验证纪律**：真机为准（小米平板 5 / Android 13，模拟器已弃用）；改播放 / 布局必须在真机验证后再勾任务。
6. **文档纪律**：项目级状态只写本文件；任务线细节写各自文档；不新建零散 `.md`。
7. **真机纪律（v2，2026-10-01 起 · 负责人统一调度）**：`adb` 真机同一时刻只允许一个会话使用。
   使用前在 `E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\device-lock.md` 登记
   （会话名 / 设备 / 开始时间 / 预计时长），完成后**立即清空并写释放时间**。
   **禁止按超时自动接管**（v1 的"45 分钟过期"规则已废除——它曾导致两个会话并发占用设备）；
   **接管必须由负责人明确指派**。同一波次的真机回归优先由 R4 统一执行，开发会话只做必要的最小验证。
   若第二台设备（Redmi K60）接入，由负责人按 serial 分配，可并行使用。

### 全局命令

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
cd E:\codex_work\Android_Studio_Work_Space\Cinefin
.\gradlew.bat :app:phone:assembleDebug --console=plain                         # 构建
adb install -r app\phone\build\outputs\apk\libre\debug\phone-libre-arm64-v8a-debug.apk   # 装机（真机 arm64）
adb shell dumpsys media_session | Select-String cinefin                        # 播放会话
adb shell uiautomator dump /sdcard/u.xml; adb shell cat /sdcard/u.xml | Select-String 'text="'   # UI 文本验证
```

服务器 `https://jellyfins.zhangwenkang.com`（Jellyfin 10.11.8）**只读**：禁止任何写入 / 删除调用。

## 6. 全局风险与已知坑

| 风险 | 说明 / 对策 |
|------|------------|
| 服务器偶发视频流超时 | 真机偶发 `SocketTimeoutException`（ping 正常），重试即可，不是客户端 bug |
| 10-bit H.264 片源 | ExoPlayer 硬解报 `NO_EXCEEDS_CAPABILITIES`，已静默降级 mpv（播放器线） |
| 令牌失效 | App 令牌过期会导致首页空白、Web 控制台反复弹登录；重新登录可恢复（控制台已做失效保护） |
| TV 端 | `app/tv` 与主模块并行存在，改动主模块时注意别破坏 TV 编译 |
| 工作区历史改动 | 除本任务线外的未提交改动属于其他任务线，不要顺手提交或回滚 |
| 播放器线细节坑（Media3 通知、MPVPlayer 崩溃等 15 条） | 见 `PLAYER_PLAN.md` §9 |

## 7. 新会话交接清单

- [ ] 读本文件（§1–§5）+ 目标任务线文档
- [ ] **读 `PLAYER_PLAN.md` §11**（2026-09-28 新增：播放页 UI/UX 改造待办，含自动选字幕/自动播放两个 bug 的线索）
- [ ] `git log --oneline -10` + `git status --porcelain | Select-String "<任务线关键词>"`
- [ ] 确认设备在线：`adb devices`（真机 `nabu`）
- [ ] 从任务线文档的任务清单里挑一条（P0 优先），动手前先写一句"本轮做什么"
- [ ] 完成后：勾进度 → 写日志 → `git commit` → 需要时更新本文件 §3
