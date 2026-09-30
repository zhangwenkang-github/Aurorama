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
| **播放器**（含手势、字幕渲染、双内核） | `docs/PLAYER_PLAN.md` | 🟡 进行中 | §1.1 / 1.2 / 1.4 已完成；用户新提 5 条播放页 UI/UX 改造（PLAYER_PLAN §11，含「自动选字幕/自动播放」bug）→ 之后 libass（§1.18）。该文档 §1 是任务清单，§11 是本轮新增待办 |
| **浏览体验**（首页 / 媒体库 / 详情 / 搜索） | 暂无独立文档（已完成主体，见 §4 M3） | ✅ 主体完成 | 打磨项按需开线 |
| **连接层**（HTTP(S) / 自签证书 / Quick Connect / 多用户） | 暂无独立文档 | 🟡 大部分完成 | 自签证书与多用户待补 |
| **Web 控制台**（内置 WebView + 影阁皮肤） | `docs/web-console-skin.css`（唯一权威副本，改后同步 `app/phone/src/main/res/raw/web_console_skin.css` 与服务器自定义 CSS） | ✅ 基本完成 | 跟随 App 令牌与配色 |
| **下载 / 离线** | 暂无独立文档 | 🟡 有基础（Downloader / Room 离线仓库 / 图片 Worker） | 下载管理 UI 与播放本地文件 |
| **投屏 / 同步观看** | 无 | ⛔ 未开始 | `:player:cast` 模块尚未创建 |
| **稳定性 / 性能 / 发布**（崩溃兜底、体积、GPL 合规） | 无 | ⛔ 未开始 | 收尾阶段 |
| **扩展项目**（阅读器 / 音乐 / UI 重设计 / 测试） | `docs/ROADMAP.md`（阶段）+ `docs/PARALLEL_PLAN.md`（波次）+ `docs/SESSION_BRIEFS.md`（会话模板）+ `docs/ROLE_SKILLS.md`（角色 skill）+ `docs/UI_DESIGN_SYSTEM.md`（S4 设计系统 v1.0） | 🟡 W1 进行中（2026-09-30） | W0 已完成：S2 架构 / S4 设计系统（f9f7e57，含修订稿）+ 负责人基线（2b00120：`modes/book`、`modes/music` 骨架 + 音频接口冻结 + ktfmt 存量修复）；W1 三线骨架启动中 |
| **阅读器**（EPUB / PDF / CBZ） | `docs/READER_PLAN.md`（W1-R1 创建并维护） | 🟡 W1 启动中 | Readium 集成 PoC + 阅读进度接口；测试内容已就绪（`test_files`：2 CBZ + 1 PDF） |
| **音乐**（播放 / 队列 / 歌词 / 离线） | `docs/MUSIC_PLAN.md`（W1-R2 创建并维护） | 🟡 W1 启动中 | `modes/music` 单 MediaSession 最小闭环（列表→播放→通知可控） |
| **UI 重塑**（设计系统落地） | `docs/UI_PLAN.md`（W1-R3 创建并维护） | 🟡 W1 启动中 | UI_DESIGN_SYSTEM v1.0 → Compose token 主题 + 基础组件（含 Typography 归位） |

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
| M2 | 连接层：HTTP/HTTPS、自签名证书、Quick Connect、多用户 | 🟡 Quick Connect 已有；自签证书 / 多用户待补 |
| M3 | 浏览体验：首页、媒体库、详情、搜索、筛选 | ✅ 主体完成（抽屉导航 + 海报墙 + 媒体库分区 + 设置对齐官方） |
| M4 | 播放器重构：双内核、libass 字幕、倍速、比例、章节、Trickplay、跳片头、PiP | 🟡 双内核 / 倍速 / 比例 / 章节 / Trickplay / 跳片头 / PiP 已有；**libass 字幕渲染未做**；详见 `PLAYER_PLAN.md` |
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
