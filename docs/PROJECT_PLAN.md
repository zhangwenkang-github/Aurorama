# Cinefin · 项目总览与协作规程

> **新会话第一步读这里**，再读对应任务线文档。最后更新：2026-10-04　分支 `master`
> 项目级状态、任务线地图、协作规程都在本文件；单个任务线内部的需求 / 进度 / 决策写各自的线文档。

## 1. 项目是什么

Cinefin = 基于 **Findroid**（GPL-3.0，上游 `a28ac9e`）改造的**自用 Jellyfin 客户端**：
平板优先、深色影院风（墨 + 朱砂），功能对齐官方客户端，并额外做官方没有的**手势体系**与**字幕 / 音轨语言智能识别与跨视频记忆**。

| 项 | 值 |
|----|----|
| 包名 | `applicationId` = `io.github.zhangwenkang.aurorama`（debug `.debug` / staging `.staging`）；Kotlin namespace / 包名 = `com.zhangwenkang.cinefin`（W41 方案 A：只改运行时身份） |
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
| **连接层**（HTTP(S) / 自签证书 / Quick Connect / 多用户） | 暂无独立文档（设计记入 `docs/ARCHITECTURE.md` §5.4） | 🟢 W31-CONN 已合并 master `9738877`；**K60 真机联调完成** | 自签证书 TOFU（指纹确认 / 记住 / 清除）与多用户（切换 / 删除顺延 / 当前标记）全流程在 K60 验证（见 ARCHITECTURE §5.4.1）；真机发现并修复「清除信任后 TLS 会话复用绕过校验」→ 分支 `fix/w31-trust-session-reuse`（待合并）。遗留：WebView 控制台 / `ImagesDownloaderWorker` 未接信任 |
| **Web 控制台**（内置 WebView + 影阁皮肤） | `docs/web-console-skin.css`（唯一权威副本，改后同步 `app/phone/src/main/res/raw/web_console_skin.css` 与服务器自定义 CSS） | ✅ 基本完成 | 跟随 App 令牌与配色 |
| **下载 / 离线** | `docs/DOWNLOAD_PLAN.md`（W32 建线） | 🟢 W32 + W34 + W36 + W37 + W39 均已合并 master `0449140`（W38 品牌资源覆盖同一代码基线） | W32：K60 真机验收（断网/force-stop 续传、批量暂停删除、存储占用）+ sidecar 修复；未触发项（FAILED 重试 / 空间不足 / reboot）待故障窗口补验；W34：**下载层级化**（视频 节目→季→剧集 / 音乐 专辑→曲目 / 书籍封面 + 媒体筛选 + 容器删除），Pad 5 真机通过；W36：**离线闭环**（离线媒体库 + 每项「允许离线观看」+ 登录页离线入口，双机真机 8 组）；W37：**本地媒体库**（SAF 建库 / 多文件夹独立层级·平铺 / 视频+音乐+书籍打开链路 / 音乐来源融合，428 项单测、双机装机）；**W39 媒体库总览改版 + 音乐空态/下拉刷新**（437 项单测、双机真机验收） | 后续：整专辑下载、未触发项补验、W37 遗留（视频首帧与书籍首页封面、CBZ 大包顺序流、本地条目并入搜索（计划 W42，范围=「媒体库与本地」，用户 2026-10-03 确认）、本地优先去重、旧书籍占位名） |
| **投屏 / 同步观看** | 无 | ⛔ 未开始 | `:player:cast` 模块尚未创建 |
| **稳定性 / 性能 / 发布**（崩溃兜底、体积、GPL 合规） | 无 | ⛔ 未开始 | 收尾阶段 |
| **扩展项目**（阅读器 / 音乐 / UI 重设计 / 测试） | `docs/ROADMAP.md`（阶段）+ `docs/PARALLEL_PLAN.md`（波次）+ `docs/SESSION_BRIEFS.md`（会话模板）+ `docs/ROLE_SKILLS.md`（角色 skill）+ `docs/UI_DESIGN_SYSTEM.md`（S4 设计系统 v1.0） | 🟢 W0–W56 全部完成（W50 自研下载引擎已合并，496 项单测全绿、CI 双绿）：W50 引擎 = OkHttp Range 真断点续传（暂停保片 / If-Range / 416 / 忽略 Range 安全重下）+ WorkManager 前台服务通知 + 失败分类与退避 + 存储预检 + 速度/ETA + Room v12（六列只追加；旧进行中任务「已取消，需重新下载」、已完成继续识别）+ 三项设置（仅 Wi-Fi 默认开 / 并发默认 2 / 完成通知默认开）；真机 8 项通过（暂停续传 / force-stop / reboot（MIUI 自启动限制记录）/ 飞行模式恢复 / 通知 / 三设置 / 旧动作 / v12 迁移）+ 真机修复 3 处；W49 遗留清理（对图判定重试 / 非黑帧 / CBZ 自然序 / 滑杆语义+零宽回归）；W48 SAF PDF 内存（Native 1.59GB→≤54MB）；W47 全量回归（M4A ✅ / reboot ✅ / 空间不足跳过 / FAILED 部分覆盖）；W46–W38 导航/封面/音乐/搜索/设置/品牌 | **W53 视频入口已交付、真机验收通过**（分支 `feature/w53-video-entry`，提交 `fcc7067`（rebase W50 `8f3ba0e`），505 项单测，含真机修复「离线模式仍走服务器仓库」）（NavEntryKey.Video 门控 / 手机底栏 首页·视频·音乐·书架 / 视频页两显示方式（库卡默认/聚合）/ 侧栏显示与视频显示方式设置）；**W52 下载界面改版已合并**（大海报 / 大条目 / 平板两列 / 容器聚合速度与剩余时间 / 旧组件重绘 / 多任务通知进下载页，master `82fd04b`，516 项单测）；**W51 下载粒度/动作排/提示/角标/三设置 UI 已交付**（分支 `feature/w51-download-granularity`，起点 `ce58847`，见 §3 W51 更新段）。全部完成 → **用户最终功能 / 界面全检合格后进入发布准备**（libass / ass-kt NOTICE、libc++ 复核、签名 / 体积 / GPL、商店图、字体兜底、继承译文、服务器 CSS 重贴）；发布后 backlog：投屏 / 同步观看（`:player:cast`）、真交叉淡化 |
| **阅读器**（EPUB / PDF / CBZ） | `docs/READER_PLAN.md`（R1 维护） | 🟢 W9/W15/W22 已合并 master；**W26-READER（已合并 master `a3dfc01`）：双栏「横版整页独占」+ 带纸边对图裁剪合并，`modes:book` 57 → 77 项单测；真机 12/12 命中（4 条纸边裁剪）+ W22 8/8 回归 + 0 误拼 + RTL/progression 不回归，见 READER_PLAN §2 D21 / §7.11**；**W29（已合并 master `e465a83`）：PDF 搜索（PdfBox-Android 2.0.27.0）+ 本地高亮/备注批注，`modes:book` 101 项单测；Pad 5 真机验收完成**；**W33-READER-PERF（分支 `fix/w33-spread-scan-memory`，已推送未合并）：双栏版式扫描改 PdfBox 页树元数据（修复 3649 页 PDF 进双栏 Native 748 MB 不回收），真机 Native 55–57 MB / PSS 286–299 MB 且能回落，W22 判定 8/8 + 横版独占 + 0 误拼不回归，book 106 项单测，见 READER_PLAN §7.14** | W4 已交付 PDF 分页懒加载 / CBZ 自研导航 / 三档模式；后续：金田一本体大文档扫描耗时实测、RTL 封面单张、Room 迁移、BC 瘦身复核 |
| **音乐**（播放 / 队列 / 歌词 / 离线） | `docs/MUSIC_PLAN.md`（R2 维护） | 🟢 **W23 交付（已合并 master `c5eca4d`；A `74214e7` / B `0124828` / C `65c6095` / D `179df99`）** + **W24 UI 细化（已合并 master `21a97d0`）** + **W25 扩展（已合并 master `ae8d0bd`）**：内嵌歌词只读探测（结论=服务器已覆盖，不做客户端解析）；本机歌词编辑 / 导入 LRC（UTF-8/GBK）/ 清除覆盖（来源链最高优先级，不写服务器）；悬浮窗「保持显示时长」2/3/5/10 秒 + 常显；设置页接 `pref_music_resume_queue` 开关；真机拦下并修复 K60 竖屏「词」面板半展开裁切；music **86** 项单测 + K60 真机 9 组（MUSIC_PLAN §5.8）；**W28 逐字歌词 + 提示行清理 + 编辑时间轴（已合并 master `e465a83`）；W30 音乐音效 EQ / ReplayGain / 交叉淡化（已合并 master `9738877`；K60 真机 13 组，MUSIC_PLAN §5.10）；W35-MUSIC-EXTRAS（已合并 master `2aad6fd`）：M4A/MP4 ReplayGain 标签 + 本机增益覆盖入口 + 真交叉双 deck 调研（结论=不实施，三阶段方案见 MUSIC_PLAN §4.6/D52）；K60 真机验收完成（RG 副本 -8.47 dB→0.38x、原曲 NONE、覆盖 -6/+6/清除回落、重启持久化、歌词·悬浮窗·队列·自然衔接、0 FATAL；真机拦下的"回读失败进负缓存"缺陷已修复并复验）；W39 音乐来源空态文案 + 四 tab 真实下拉刷新（分支 `feature/w39-media-library-polish`，music 104 项单测 + Pad 5/K60 真机，见 MUSIC_PLAN D55/§5.12，已推送待合并）** | 后续：离线本地文件取内嵌歌词（如需）；M4A 设备端样本待补（本库 0 个 m4a/mp4，解析由 12 项单测覆盖）；真交叉排期需 8–12 人日（方案已就绪） |
| **UI 重塑**（设计系统落地） | `docs/UI_PLAN.md`（R3 维护） | 🟢 W8 完成（2026-10-01，master `f99af8d`；W7-R3 已并入 `c545eb9`） | W8-R3（用户复测反馈，D29–D31）：①三页顶栏统一为 `CinefinPageTopBar`（56dp + `statusBarsPadding()`；修复音乐汉堡被状态栏挡、书架顶层去返回箭头与库名「书籍」、媒体库按钮与标题同栏）；②媒体库库卡改版（类型图标磁贴 + 项目数 + A 配色层次，服务器 `ItemFields.CHILD_COUNT`）；③侧栏「媒体库」**默认收起并移到音乐、书架之后**（覆盖 W7 的排布）；④W7 行为不回归（抽屉、侧柜常驻 Lumen、控制台胶囊与选中态）；遗留：spacings 桥接收敛、左缘滑出抽屉在系统手势导航下未验；**W39 媒体库总览两段式改版（`UI_PLAN` D39，分支 `feature/w39-media-library-polish`，437 项单测 + 双机真机验收，已推送待合并）** |
| **测试与验收**（真机矩阵 / 回归 / 性能） | `docs/TEST_PLAN.md`（R4 维护） | 🟢 W2 基线 + **W47 全量回归（`feature/w47-full-regression`，§7）** + **W47-B 补验（`feature/w47b-b-verification`，§7.5）** + **W47-C reboot 续传（`feature/w47c-reboot-resume`，§7.3/§7.5）** + **W61 发布前全量回归（`test/w61-full-regression`，§7.6）** | W47 A 组 6 项通过 / 部分通过；**D-W47-1（本地 SAF PDF 双栏逐页回退 → Native 1.59GB → 被 MIUI 杀进程，P1，W48 修复中）**、D-W47-2（冷启动中位 1322ms 超阈值，待拍板）；W47-B：**M4A ReplayGain 全部通过**；**下载 = 设备侧断网为系统自愈续传（不产生 FAILED，应用层重试仍未触发）**；**reboot 续传 ✅ 通过**（系统自行续传 + 本地文件播放校验）；空间不足 = 跳过（用户确认）；**W61（纯验证无代码提交）**：9 组回归完成（全链路冒烟 / 播放 / 阅读 / 音乐 / 离线 / 下载 / 性能 / 稳定性），Pad 5 冷启动中位 **1394 ms**（+5.4% vs W47 接受基线，未超阈）/ PSS **268,511 kB**（+8.4%）/ 音乐滚动 **1.07% janky**（较 W47 改善）；双机 0 App FATAL·ANR；缺陷仅服务器侧 `ChildCount` 随机（F1，非 App）+ 3 条 P3 观察（F2–F4）；未覆盖清单见 §7.6.3 |

### 3.1 执行顺序（用户 2026-09-28 批准）

1. ✅ **播放器 · 字幕面板补全**（`PLAYER_PLAN.md` §1.1）——延迟 / 双语 / 外观（2026-09-28 完成，真机验证通过）
2. ✅ **播放器 · 音轨面板补全**（§1.2）——音轨延迟 / 描述 / 默认轨记忆（2026-09-28 完成，真机验证通过）
3. **播放器 · libass 字幕渲染**（§1.18，M4 缺口）——特效字幕还原度
4. **下载 / 离线增强**（新线；W32 已开工，文档 `docs/DOWNLOAD_PLAN.md`；本地文件播放留后续波）
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

**W45 更新（2026-10-03，分支 `feature/w45-local-covers`，起点 master `d11f80d`，待负责人合并）**：①阶段 4「下载 / 离线」的 W37 遗留①已做——本地媒体封面 / 缩略图 = 视频第 1 秒首帧 / PDF 首页 / CBZ 第一张图 / EPUB（Readium metadata cover），音乐沿用 W37「内嵌标签 → 同目录封面」；**懒生成**（可见才生成、并发 ≤2）+ `files/local_thumbs/<itemId>.jpg` 缓存（≤512 / JPEG 80）+ `.fail` 失败标记，扫描索引与 Room 未动。②阶段 UI「本地媒体」按用户反馈**卡片化**：封面 + 库名 +「N 项 · 类型」+ 类型角标，与首页「继续观看」横排同宽同高（媒体库总览库卡 / 条目列表行 / 详情头部同步接缩略图，无图回退类型图标）。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、**471 项单测 0 失败**（data 新增 9 项）；真机 Pad 5 `43af8627` 主 + K60 `8e875894` 抽验通过（四条链路像素取证、125 项库滚动缓存命中后 0.53% janky），真机拦下并修复「新建库空库阶段请求封面被永久缓存」，详见 `DOWNLOAD_PLAN` §14 / `UI_PLAN` D47 / §5 W45 / 踩坑 67。W37 遗留只剩「CBZ 大包顺序流慢」与「本地优先去重未做」。
**W46 更新（2026-10-03，分支 `feature/w46-nav-settings`，起点 master `7fb250d`，待负责人合并）**：用户 2026-10-03 逐项确认的四条导航 / 设置细节已落地——①侧栏（平板侧轨 + 手机抽屉）底色 82% → **74%** 半透明、展开 150 → **168dp**、`服务器控制台 / 媒体资料管理器` → **控制台 / 资料管理**（中英 + zh-rTW；底栏 82% 不变）；②手机首页 / 音乐 / 书架 / 媒体库四个一级页顶栏入口统一为 app 图标（24dp、content-desc「打开侧栏」）；③平板取消抽屉（边缘手势关闭 + 顶栏不再给入口，导航走常显侧轨，收起 / 展开由侧轨按钮完成）；④设置「音乐」子页下线、音乐库选择并入「媒体库」子页（首页 / 书架 / 音乐三项并列）。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、**471 项单测 0 失败**；真机（Pad 5 主 + K60 抽验）待负责人统一调度，详见 `UI_PLAN` D48–D50 / §5 W46 / 踩坑 68–70。

**W50 更新（2026-10-03，分支 `feature/w50-download-engine`，起点 master `3dbca99`，已 rebase 到 W49 `1222bef`，待负责人合并）**：用户 2026-10-03 决定**不再使用系统 DownloadManager**（推翻 D1），下载 / 离线线完成自研引擎替换（W50 只做引擎 + 数据层 + 现有动作接线，UI 改版留 W51/W52）——OkHttp + HTTP Range 真断点续传（暂停保留残片、恢复从残片继续、服务器忽略 Range 安全重下）、失败分类 + 任务级指数退避（网络类无限 / 服务器类限次）、速度与 ETA 进数据模型；前台服务由 WorkManager 长时 worker `setForeground` 托管（dataSync），通知带进度 / 暂停 / 取消 + 完成 / 失败通知；三个下载设置已接引擎（仅 Wi-Fi 复用 `pref_downloads_mobile_data` 默认开、同时下载数 `pref_download_concurrency` 1–3 默认 2、完成通知 `pref_download_complete_notification` 默认开；设置 UI 由 W51 补）；Room v11→v12 六列只追加，旧「进行中」任务标记「需重下」、已完成文件继续识别；`DownloadManagerSupport` / `DownloadReceiver` 退役（`downloadId` 列保留做 UI 句柄兼容）。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、**496 项单测 0 失败**（W49 基线 486 + W50 净增 10：core 新增 8 + app 净增 2）；**真机 8 项验收通过（2026-10-03，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）**——暂停→残片保留→续传、force-stop 重开续传、重启后打开 App 从残片续传（MIUI 未放行自启动，见遗留）、飞行模式失败→网络恢复自动续传、前台/完成通知与权限弹窗、三个设置行为、容器/多选/存储/离线目录回归、v11→v12 迁移（K60 真实旧任务首启显示「已取消，需重新下载」）、0 FATAL/ANR；真机拦下并修复 3 处（网络策略误判 / 用户动作唤醒延迟 / 网络恢复等待过长），详见 `DOWNLOAD_PLAN` §18.7。

**W53 更新（2026-10-03，分支 `feature/w53-video-entry`，起点 master `1222bef`、已 rebase 到 W50 `8f3ba0e`，待负责人合并）**：用户 2026-10-03 确认的「视频入口」整波落地——①「视频」与首页 / 音乐 / 书架同级：`NavEntryKey.Video` 门控 = 服务器有 `movies` / `tvshows` 库（库列表未就绪保持可见走空态），侧栏 / 抽屉顺序 首页 → 视频 → 音乐 → 书架 → 媒体库 → 下载，手机底栏改 **首页 / 视频 / 音乐 / 书架**（「媒体库」移出底栏、仍留侧栏 / 抽屉）；②客户端设置「侧栏显示」加「视频」开关（`pref_ui_sidebar_show_video`）；③视频模式页两形态：库卡列表（默认，`LibraryEntryCard`）/ 聚合列表（`ItemCard` + Paging 3，按视频库顺序拼接、库内 `DateCreated` 倒序；Jellyfin 无跨库单查询接口，口径严格限定 movies / tvshows 库），「媒体库」子页新增「视频显示方式」下拉（`pref_ui_video_display_mode`，默认 `cards`）；④空态 / 骨架 / 错误沿用现有组件，顶栏复用 `CinefinPageTopBar`（手机 logo / 平板无抽屉键）。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、**505 项单测 0 失败**（W50 基线 496 + W53 净增 9；app 90 / core 37 / data 45 / player:local 105 / film 6 / book 113 / music 109）；红线仅动 `AppPreferences.kt`（2 键）与 `NavigationRoot.kt`（接线），均已申报。真机通过（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验；另修「离线模式仍走服务器仓库」→ `Provider` 按当前偏好解析），详见 `UI_PLAN` D52–D53 / §5 W53。

**W52 + W53 合并归档（2026-10-03，master `82fd04b`）**：负责人按「先合并、再开新波」执行——master `2963799` 先 ff 合并 **W52 下载界面改版**（`8bb81b7` feat + `c129508` docs；大海报 96×144dp / 大条目 / 平板两列 / 容器聚合速度与剩余时间 / 旧组件重绘 / 多任务通知进下载页），再把 **W53 视频入口** rebase 到 W52 之上（`b345e6f` → `82fd04b`，唯一冲突 `UI_PLAN` W52/W53 验收段已双保留），ff 合并进 master。合并门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、7 任务 `--rerun` **516 项 0 失败 0 错误**（app 93 / core 37 / data 45 / player:local 105 / film 14 / book 113 / music 109）；W52 真机像素采样 / 平板两列 / 聚合速度实测仍待设备窗口补验（清单 `DOWNLOAD_PLAN` §19.5）。**下一批（用户 2026-10-03 确认，负责人排期）**：W51 下载粒度 + 详情动作排重绘 + 侧栏角标 + 三项下载设置 UI → **W54 库页头部共享组件**（分类 tab / 计数 / 视图切换 / 排序 / 筛选 / 下拉刷新，视频库·书籍库·书架共用；含「其他2」家庭视频库缩略图）→ **W54 视频页**（两模式选库 + 顶栏收藏 / 睡眠图标 + 卡片去大字）→ **W54 首页与设置收口**（去掉「首页媒体库」选择、显示内容开关、本地库刷新）→ **W55 睡眠定时统一**（音乐 / 视频共享 + 自定义 1–240 分钟）。

**W51 更新（2026-10-03，分支 `feature/w51-download-granularity`，起点 master `ce58847`，待负责人合并）**：用户 2026-10-03 确认的「下载粒度 + 详情页动作排」整波落地——①**下载粒度**：节目（Show）= 下载整剧 / 季（Season）= 下载全季 / 集（Episode）= 下载本集，全部走 W50 自研引擎；整剧 / 全季确认框默认**「仅补齐缺失集」**（已下载 / 已在队列自动跳过，不提供覆盖式重下）+ **默认单次上限 100 集**（可关开关解除）；Show 的剧集目标点击时按需拉取、Season 复用页面已加载剧集，状态判定 / 批量选择 / 去重 / 上限抽 `DetailDownloadRules` 纯函数 + 6 项 film 单测；活动队列口径抽 `DownloadTaskRules.isActiveQueueStatus` + `Downloader.activeItemIds()` 只读快照（不动引擎、不唤醒队列）。②**详情动作排**：Show / Season / Episode 介绍上方同排「下载 / 已播放 / 喜欢」重绘（Outlined + 选中态，Lumen 下自动极光青；Movie 保留旧分支）；「已播放 / 喜欢」改乐观更新 + 失败回滚；点击下载三态 Snackbar（已加入下载队列 / 已在队列 / 已下载），新 core `CinefinSnackbarHost`。③**侧栏角标**：`CinefinNavItem.badge` + `CinefinCountBadge`，活动任务数 = 下载中 + 排队 + 暂停、0 隐藏；落点侧轨 + 抽屉（底栏 W53 后无下载项），前台 RESUMED 期间 2s 只读轮询。④**三项下载设置 UI**：下载与缓存子页接「仅 Wi-Fi 下载（默认开，对既有键取反绑定）/ 同时下载数 1–3（默认 2）/ 完成通知（默认开）」，**`AppPreferences.kt` 零改动**。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、7 任务 `--rerun` **538 项 0 失败 0 错误**（W56 基线 531 + W51 净增 7：app 106 / core 37 / data 45 / player:local 105 / film 20 / book 113 / music 112，含 app +1 + film +6）。红线仅动 **`NavigationRoot.kt`**（角标接线，已申报）；其余红线（`settings.gradle.kts` / `libs.versions.toml` / `AndroidManifest.xml` / `AppPreferences.kt` / `player:core` / `player:local`）未动。真机待负责人设备窗口（清单 `DOWNLOAD_PLAN` §20.5），详见 `DOWNLOAD_PLAN` §20 / `UI_PLAN` D57 / §5 W51（决策 / 踩坑编号已让位并行 W54-B 的 D56 / 踩坑 74·75）。

**W51b 更新（2026-10-03，分支 `feature/w51b-show-download-fix`，起点 master `9fcea57`、已 rebase 到 `233b387`，待负责人合并）**：W51 合并后的真机走查拦下「Show 整剧 → Snackbar『没有可下载的剧集』」——根因 = 取集只请求 `Fields=Overview`，Jellyfin 按需字段 `MediaSources` / `CanDownload` 缺失导致 `canDownload=false` + 空 `sources`，批量目标被过滤成 0 条；修法 = `DetailDownloadRules.EPISODE_FETCH_FIELDS` 统一带上三字段、目标筛选改「有媒体源」硬条件。同时修**批量入队竞态**：`DownloaderImpl.downloadItem` 原「先插队列行、后写条目快照」会被已唤醒的引擎抢跑（同机 12 集入队 11 集 FILE_ERROR），改为快照先落库。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、7 任务 `--rerun` 起点 **561 项 0 失败**（基线 559 + 2）、rebase 到 `233b387`（并入 W53B / W54-D）后复跑 **577 项 0 失败 0 错误**（app 130 / core 37 / data 45 / player:local 105 / film 35 / book 113 / music 112；`9fcea57 → 233b387` 对本波下载 / 详情页文件零 diff）。真机 Pad 5 `43af8627` 复验通过：Season「将加入 11 集」/ Show「将加入 12 集」确认框 → Snackbar「已加入下载队列 · 11 / 12 集」、下载页 `12 进行中 · 0 失败`、侧轨角标 12、单集下载回归正常、0 FATAL/ANR，测试下载（12+11+1）已删除、设备已还原。详见 `DOWNLOAD_PLAN` §20.7 / `UI_PLAN` §5 W51b。

**W51b + W53B + W54-D 合并归档（2026-10-04，master `1bc026b`）**：负责人按「逐个验收合并」执行——W53B（侧栏「本地媒体库」子分组 + 本地库卡 16:9）`f24c7b8` → W54-D（首页模块 + 设置收口 + 「全部」修复）rebase 让号 **D61 / D62** 后 `233b387` → W51b（Show / Season 取集字段 `Overview + CanDownload + MediaSources` + 批量入队快照前移）`1bc026b`；master 门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` + 7 任务 `--rerun` **577 项 / 0 失败 0 错误**（app 130 / core 37 / data 45 / player:local 105 / film 35 / book 113 / music 112）；CI Build + Format 双绿；三波联合真机走查（Pad 5 主 + K60 抽验）通过、0 FATAL / ANR（清单与结论见 `UI_PLAN` §5 / `DOWNLOAD_PLAN` §20.7）。**下一波**：W55 睡眠定时统一（音乐 / 视频共享 + 自定义 1–240 分钟）→ 发布准备（待用户对功能界面最终全检）。

**W55 更新（2026-10-04，分支 `feature/w55-sleep-timer`，起点 master `1aae466`，待负责人合并）**：用户 2026-10-04 确认的「睡眠定时统一」整波落地——音乐 / 视频共享 `player:local` 进程级单例 `SleepTimerController`（离开页面 / 熄屏 / 后台播放继续生效；到点暂停唯一共享播放器实例，音视频互斥）；预设保留 10 / 20 / 30 / 60 + 新增自定义 1–240 分钟（滑块，core 共享组件 `CinefinSleepTimerOptions`）；播放器睡眠键 / 面板、视频页顶栏（W54-C 占位转正）、音乐顶栏 / sheet / 底栏全部接同一状态源；纯逻辑落 `player:core`（`SleepTimerSpec` + `SleepTimerStateMachine`）并新增 7 项单测。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、7 任务 `--rerun` **576 项 / 0 失败 0 错误**（music −1 = 旧 `MusicSleepTimerTest` 迁移）、含新 `:player:core:testDebugUnitTest` 全量 **583 项 / 0 失败**。红线动 `player/core` / `player/local` / `core` / `app/phone` / `modes/music` / `modes/film`（删占位文案）；`AppPreferences.kt` / `NavigationRoot.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` 未动。真机验收待设备窗口（清单 `PLAYER_PLAN` §26 / `UI_PLAN` §5 / `MUSIC_PLAN` §5.15）。

**W56 交互修正更新（2026-10-04，分支 `feature/w56-interaction-fixes`，起点 master `2ed356f`，待负责人合并）**：用户 2026-10-04 拍板的四项界面修正落地——①设置页账号卡并入「账号与服务器」组首行（`SettingsGroupCard` 新增 `header` 组内插槽；账号卡点击 / 头像 / 昵称 / 服务器 / 徽标不变，`AppPreferences.kt` 零改动）；②音乐迷你播放条新增「关闭面板」×（活动会话 `stop()` 停播 + 清内存队列、恢复态只丢展示快照；队列存档只写不清，再次选择曲目即可恢复；纯函数 `musicMiniBarDismissTarget` + 3 项单测）；③全屏播放页删除「歌词」入口按钮（`PlayerActionRow` 六键 → 五键；点歌词行 / 左滑两条路径与迷你条「词」保留）；④顶层图标统一「回对应主页」——新纯函数 `topLevelTapAction`（Stay / CollapseOverlay / Navigate，4 项单测）+ 音乐覆盖层 `reselectSignal` / `onOverlayOpenChange`：已在主页不重复导航、从二级页弹回根页、音乐全屏 / 歌词页用信号收起。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、7 任务 `--rerun` **583 项 / 0 失败 0 错误**（app 130 → 134 / music 111 → 114）、含 `:player:core:testDebugUnitTest` 全量 **590 项 / 0 失败**；红线仅动 `NavigationRoot.kt`（已申报），`AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local` 未动。真机待设备窗口（清单 `UI_PLAN` §5 W56 / `MUSIC_PLAN` §5.16）。

**W57 下载体验更新（2026-10-04，分支 `feature/w57-download-ux`，起点 master `3518dca`，待负责人合并）**：用户 2026-10-04 拍板的下载体验 5 条落地——①图片缓存默认 20 → **50 MB**；②同时下载数改**手动输入**（1–8，默认 2，越界自动钳制；引擎上限 3 → 8）；③新增**下载限速**（0–100 MB/s，0 = 不限速，新键 `pref_download_speed_limit_mbps`；`DownloadThrottle` 按本次会话平均速率节流，每次任务启动读取偏好、运行中不打断）；④「仅 Wi-Fi 下载」**不看系统计费标记**（`DownloadNetworkRules`：Wi-Fi / 以太网直接允许；其余看移动数据 / 漫游开关）——修复「家庭 Wi-Fi 被判计费 → 永远等待网络」；⑤下载页缩略图**任何网络下都显示**（所有条目入队即落盘自身封面 + 本地优先回退链 + 类型图标占位）——真机复现根因：电影从未落盘自身封面、剧集自身封面未落盘（`ImagesDownloaderWorker` 链路本身正常）。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun` **599 项 / 0 失败 0 错误**（app 136 / core 46 / data 45 / player:local 105 / film 40 / book 113 / music 114）、含 `:player:core` 全量 **606 项 / 0 失败**（新增单测 16）；红线动 `AppPreferences.kt`（缓存默认 + 新键，已申报），`NavigationRoot.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local` 未动。真机（Pad 5 `43af8627`，复现 01:48–01:56 + 复验 02:11–02:16，device-lock 已写释放与结论）：计费 Wi-Fi 立即下载（不再等待）/ 下载中出图 + 飞行模式仍出图 / 并发 99→8、限速 500→100、缓存默认 50 MB / 断网续传 115→119 MB / 0 FATAL·ANR；详见 `DOWNLOAD_PLAN` §21 / `UI_PLAN` D65。

**W58 多选批量更新（2026-10-04，分支 `feature/w58-multi-select`，起点 master `ea18849`，待负责人合并；本会话 = 音乐打样）**：用户 2026-10-04 拍板的「音乐 / 视频 / 书籍长按多选 + 全选 + 批量操作」先落**音乐部分**——①**通用框架（core，三模式共用）**：`MultiSelectState` 纯状态（进入 / 切换 / 已加载全选 / 取消全选 / 求交集 / 退出；非多选态选中集合恒为空）+ `CinefinMultiSelect.kt`（`cinefinSelectable` 长按手势 / §8.5 `CinefinSelectIndicator` 20dp 勾选 / `CinefinBatchBar` 下载页同款工具条）+ `CinefinListRow` 增 `onLongClick` / `selectionMode` / `selected` 默认参数 + 4 条新文案三语言；②**音乐**：歌曲 Tab 与专辑 / 艺术家 / 歌单 / 收藏 / 最近播放详情长按进入多选，顶栏「已选 N 项」+ 全选 / 取消全选 + ×，底栏五键——播放（**加入当前播放队列开始播**，按列表序解析、失败跳过）、下载（跳过已下载 / 队列内）、收藏（批量同向）、删除（**只删本地**：仅已下载且非本地媒体库的服务器条目，确认框明示，纯服务器 / 本地库置灰）、从歌单移除（仅歌单详情，走 Jellyfin 歌单编辑）；③**纯函数 + 单测**：`MusicBatchRules` 14 项 + `MultiSelectState` 13 项 + `MusicQueue.move` 追加队尾 5 项（顺带修掉 `toIndex == items.size` 被拒导致批量入队留尾的缺陷）；④**红线**：动 `player:core`（`MusicQueue.move` 追加语义 + 5 单测，已申报）、`core` 设计系统（新增多选组件 / `CinefinListRow` 参数）、`modes:music`、`modes:music` 仓库接口（`removeFromPlaylist`，Jellyfin 服务端写白名单内的歌单编辑）；`AppPreferences.kt` / `NavigationRoot.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:local` 未动。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **638 项 / 0 失败 0 错误**（app 136 / core 59 / data 45 / player:local 105 / film 40 / book 113 / music 128 / player:core 12；新增 32）；真机待设备窗口（清单 `MUSIC_PLAN` §5.17）。**视频 / 书籍多选批量未做**，复用本波 core 框架接续（口径与边界已写 `UI_PLAN` D66 / §4 W58），详见 `MUSIC_PLAN` D66–D69 / §2.16 / §5.17。

**W58b 视频 / 书籍多选批量更新（2026-10-04，分支 `feature/w58b-multiselect-video-book`，起点 master `5dc2c82`，待负责人合并；负责人已指定真机由本会话自查）**：W58 音乐打样的 core 通用框架之上补齐**视频 / 书籍**长按多选 + 已加载全选 + 批量操作——①**视频**（视频页聚合网格（含侧栏临时库视图）+ 库内容网格 `LibraryScreen`（含首页「最新 · 库名 → 全部」入口））五键：**播放 = 显式播放队列**（选中条目按列表序解析成电影 / 单集 → `PlayerActivity` 新 `queueItemIds` / `queueItemKinds` extra → `PlaylistManager.getInitialItemForQueue` 建队，播放页后台补队列；剧集 → 下一集、季 → 第一集）、下载（电影 / 单集直接入队；**剧集 / 季复用 `DetailDownloadRules` 按「补齐缺失集」整剧展开**，跳过已下载 / 队列内 / 100 集上限）、标记已看、收藏（后两者双向：任一未完看 / 未收藏 → 全部标记，全量已看 / 已收藏 → 全部取消）、删除（**红线：只删本机**——已下载且非本地媒体库，确认框明示；纯服务器条目置灰）；②**书籍**（书架 / 书籍库内容页同一 `LibraryScreen`）三键：下载（走阅读器离线链路 `ReaderRepository.downloadLocalFile`，与下载页「书籍」分组同源）、标记已读、收藏（无播放、无删除）；③**交互**：页面层 `rememberMultiSelectState()`（core 新增 `rememberSaveable` 包装）、顶栏「已选 N 项 + 全选 / 取消全选 + ×」、底栏 core `CinefinBatchBar`、全选 = 当前已加载、切 tab / 排序 / 筛选自动退出、系统返回先退多选；④**纯函数 + 单测**：`MediaBatchRules` 12 项（app）+ `parsePlaybackQueueEntries` 5 项（player:local）；⑤**顺带修**：下载页多选删除书籍空转（改走 `readerRepository.deleteLocalFile`）；⑥**红线**：动 `player:local`（显式队列，已申报）、`core`（`rememberMultiSelectState`）、`app/phone`（`LibraryScreen` / `VideoScreen` / `ItemCard` / `PlayerActivity`）、`modes/film`（`DownloadsViewModel` + 文案 13 条三语言）；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` 未动。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **655 项 / 0 失败 0 错误**（app 148 / core 59 / data 45 / player:local 110 / film 40 / book 113 / music 128 / player:core 12；新增 17 = app 12 + player:local 5）；真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-04 03:43–04:19，device-lock 已写释放与结论）——视频长按 / 点选 / 全选 17 / 取消 / ×、批量播放 2 部电影（队列面板顺序 = 列表序 + `KEYCODE_MEDIA_NEXT` 顺序切换）、批量下载（1.83 GB 完成 → 批量删除清除）、标记已看 / 收藏服务器筛选回读并复原、删除纯服务器条目置灰 / 已下载条目确认框「只删本机文件与索引」后删除、书架三键 + 全选 8 / 取消 / 退出 + 筛选回读 + 书籍下载 703 KB 落盘 `files/books`、K60 抽验通过、双机 0 FATAL / ANR；未覆盖：批量队列跨片连播听感（建议用户代测）、剧集库整剧下载真机、本地媒体库条目删除置灰样本。细节见 `UI_PLAN` D67 / §4 W58b / §5 W58b / 踩坑 81 / §7 日志。

**W59 下载页重构更新（2026-10-04，分支 `feature/w59-downloads-redesign`，起点 master `9c65010`，待负责人合并）**：用户 2026-10-04 拍板的四条落地——①**下载页钻取式 IA**：顶层列表只显示 Show 卡 / 专辑卡 / 电影条目 / 书籍条目（书籍平铺、不建容器），卡上显示聚合进度（状态 · x/y 集 + 体积 + 速度 / 剩余）；页签改为「进行中 → 失败 → 已完成」、类型筛选与多选批量保留；**Show / 专辑点击钻取详情**（同一路由内状态切换、未加路由）：上半 = 海报 + 标题 + 总进度 + 操作键「全部暂停 / 全部继续 / 删除」，下半 = 季卡（点击展开 16:9 剧集）/ 曲目平铺，进入详情**自动展开第一个进行中的季**并滚到可见。②**在线书籍封面自动生成**（新 core `BookCoverProvider`）：服务器图优先 → 生成缓存 → 生成 → 类型占位；方案同本地书籍（PDF 首页 / CBZ 第一图 / EPUB 封面），**未下载的在线书籍也生成**——HTTP Range + ZIP 只读解析 + PdfBox `RandomAccessRead`，不整本下载；懒生成 + `files/book_covers/<id>.jpg` + `.fail` 失败标记；书架仅对无服务器图的书籍触发。③**视频层级图严格同级**：Show / Season 只用自己的海报、Episode 只用自身缩略图，缺图类型占位、不跨级回退（W36 跨级回退下线）；顺带修 W52 遗留——进行中剧集按「存在 sources 即纳入」的层级查询分组，不再被当电影平条。④**音乐**：专辑列表长按多选 → 批量下载整张（复用 W58 `MultiSelectState` / `CinefinBatchBar`，底栏仅「下载整张」，`MusicAlbumDownloadRules.planAlbums` 按专辑序 + 音轨序 + 去重 + 单次 100 首上限）；专辑详情「下载专辑（N 首）」仅补齐缺失（队列内 / 已下载跳过，无缺失置灰「已全部下载」）；无图专辑 / 曲目 = 音符矢量 + 媒体色底占位（不新增位图）。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务逐个 `--rerun` **677 项 / 0 失败 0 错误**（app 148 / core 69 / data 45 / player:local 110 / film 48 / book 113 / music 132 / player:core 12；基线 655 + 新增 22）；真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-04 06:0x–06:5x，device-lock 已写释放与结论）——顶层聚合 / 页签 / Show 钻取 + 自动展开 + 全部暂停继续（105 → 116 MB）/ 严格同级图 + 进行中归属修复 / 书籍封面本地生成 + 删除本地 `.book` 后 HTTP Range 重新生成 + 飞行模式缓存显示 / 专辑多选「下载整张」/ 专辑详情「下载专辑（1 首）」→ 置灰 / 音符占位 / 下载页多选批量删除（已选 5 项 → 9 → 5 已完成、占用 3.03 GB → 662 MB）/ K60 紧凑列表与专辑多选抽验 / 双机 0 FATAL·ANR；未覆盖：详情页「删除」按钮未单点验证（同 `deleteEntries` 路径）、封面观感建议用户过目、平板两列大数据量视觉节奏。红线：未动 `NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local`；申报非红线：`core/build.gradle.kts`（+pdfbox-android 同版本）、`data`（新 DAO 查询 + 仓库两实现）、`core` / `modes:film` / `modes:music` / `app:phone`。详见 `DOWNLOAD_PLAN` §22 / `UI_PLAN` D68 / §4 / §5 W59。

**W60 图标重绘 + 下载失败重试 + 首页续读进度条更新（2026-10-04，分支 `feature/w60-icons-retry-resume-bar`，起点 master `9c65010`，待负责人合并；与 W59 下载页重构并行、真机由本会话自查）**：用户 2026-10-04 拍板的三项落地——①**图标重绘**（只出图标文件，接线由 W60b 做）：`ic_search.xml` 圆更饱满（r 6.5 → 7）+ 45° 手柄加长（至 21,21），24dp / 1.75dp 描边 / 圆角端点；新增 `ic_bookmark.xml` / `ic_bookmark_filled.xml`（收藏新图标）。②**下载失败自动重试补齐**：只读核对确认 W50 任务级分类退避（网络类无限 / 服务器类 5 次 / 残片 3 次 + CONNECTED 兜底）已完整；缺口 = `ImagesDownloaderWorker` 恒成功不重试 → 新增瞬时失败感知（IOException / 5xx / 408 / 429）最多 3 次尝试 + `Result.retry()` + `.part` 临时文件改名防残片；core 新增三语言 `download_retry_in_progress`（「重试中 · 第 N 次」供 W60b 接线）。③**首页续读进度条**：`FindroidItem.playedPercentage`（接口默认 null）经 `FindroidFolder`（书籍）/ `FindroidMovie`（音频）映射，`LandscapeItemCard.cardResumeFraction()` 以 `playbackPosition / runtime` 优先、`playedPercentage / 100` 回退，「继续阅读 / 继续收听」与「继续观看」同款 3dp `progressTrackOnImage` 进度条。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun` **651 项 / 0 失败 0 错误**（app 152 / core 63 / data 45 / player:local 110 / film 40 / book 113 / music 128；新增 8）、含 `:player:core` 全量 **663 项 / 0 失败**；真机（K60 `8e875894`，05:58–06:21，device-lock 已写释放与结论）：搜索图标暗背景可读、`futuristic_tales` 续读进度条与「继续观看」同款、电影断网失败 → 自动重试 → 联网续传（DB `retryCount=1` / RUNNING）、0 FATAL / ANR，测后全部复原。红线动 `core`（utils / work / res）、`app/phone`（`LandscapeItemCard`）、**`data`（进度字段，未在原白名单内，已申报）**；W59 文件域（`DownloadsScreen.kt` / `DownloadRows.kt` / `ItemCard.kt` / `ItemPoster.kt` / `LibraryScreen.kt` / `LibraryViewModel.kt` / `modes/music/*`）与 `NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 未动；书签对与「重试中」文案 UI 接线归 W60b。详见 `UI_PLAN` D69 / §4 W60 / §5 W60、`DOWNLOAD_PLAN` §23。

**W60b 收藏链路 + 下载体验更新（2026-10-04，两段提交 `7702f38`（收藏链路）+ `c1f85d2`（下载体验），起点 master `32c0e37`，本线程分支 `feature/w60b-favorites-download-feedback`）**：用户 2026-10-04 拍板、**分两段提交**落地——**A 段（收藏链路）**：①条目级术语统一「收藏」+ 书签图标对（`ic_bookmark` / `ic_bookmark_filled` 取代 `ic_heart` 对：详情动作排 / 音乐收藏页签 / 批量条 / 全屏播放键）；②去掉媒体库收藏三处入口（视频页 / 书架顶栏「收藏当前媒体库」+ 媒体库页顶栏「收藏」）与配套 VM；③侧栏一级新增「**我的收藏**」页：`filters=IsFavorite` 跨库汇总（电影 / 剧集 / 单集）+ 类型筛选 + 服务端排序（加入日期 / 名称）+ 已加载全选 + 三键批量（收藏 / 下载 / 已看，复用 W58 框架）；④**单一数据源**：`data` 仓库层收藏版本广播（`UserDataEvents`）+ `FavoriteChangeEffect` 补刷新，详情 / 库网格 / 首页走廊 / 收藏页 / 搜索结果的收藏书签角标即时一致。**B 段（下载体验）**：⑤下载状态徽标四态（进度环 / 完成角标 / 双竖线 / 红叹号）覆盖库网格 / 首页走廊 / 搜索结果 / 详情海报，与未看数 / 已看打勾**错位排布**（右上被占 → 右下；纯函数 + 单测）；⑥所有下载入口统一 Snackbar 三态 +「**查看**」跳下载页（core Snackbar 支持动作文字；音乐新增下载反馈事件）；⑦下载页「重试中 · 第 N 次」接线；⑧音乐批量播放改 4 路并发预取（保列表序）。**门禁**：A 段 8 任务 **688 项 / 0 失败**（app 155）、B 段 **704 项 / 0 失败**（app 171，新增 19），根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿。**真机**（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，07:40–09:05，两段分别装机，device-lock 已写释放与结论）：A 段 6 项 / B 段 6 项全通过（含真机拦下并修复「我的收藏页未加入 `showNavigation` 白名单 → 侧轨消失」；重试中文案实测、「查看」三入口、双机 0 FATAL / ANR）。红线：`NavigationRoot.kt` / `data`（收藏查询 + 广播）/ `core` / `app/phone` / `modes:music` / `modes:film`（删除收藏媒体库 VM）已申报；`AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 未动。未覆盖：失败徽标真机样本（网络类失败进入重试）、搜索结果下载角标样本、角标淡入逐帧、音乐批量服务器压力。详见 `UI_PLAN` D70 / §4 W60b / §5 W60b、`DOWNLOAD_PLAN` §24。

**W62 回归缺陷修复更新（2026-10-04，分支 `fix/w62-regression-defects`，起点 master `2cdaff8`，待负责人合并）**：W61 全量回归的 4 条缺陷全部收口——①**F1 库卡数字口径**：服务器 10.11.8 的 `ChildCount` 对 UserView 随机（只读 API 探针复核，同请求连续采样 8/4/6…；`RecursiveItemCount` 字段不返回）；改用「按库直接子项 `TotalRecordCount`」稳定值（`data` 纯函数 + 60 s 缓存 + `ChildCount` 回退，+5 单测），数字 = 进库后内容页条目数（电影 17 / 动漫 94 / 其他2 2 / 书籍 8 / 书籍3 8 / 音乐 124，空库不占位）；递归口径（动漫 2364）经复核否决。②**F2 离线书架 vs 下载页书籍清单**：根因 = `pref_offline_blocked_books` 里遗留关闭的 `futuristic_tales`（书架按 allowOffline 过滤，而书籍没有管理视图出口）+ 下载页离线时书名退化成「离线书籍 xxxxxxxx」占位；修法 = 书架列出**全部**已下载书籍（关闭项置灰 + 开关可回开）+ 书名统一「侧车优先」（`core` / `app/phone` / `modes:film`，+4 单测）。③**F3 EPUB 滚动**：**不改**——Readium 3.4 Android 为「资源内垂直滚动 + 资源间左右翻页」，正文资源实测 18.7–22.7% 位移，W61 观察源于书首短资源 / 滚到边界。④**F4 长按倍速**：**不改**——adb 长按实测 `speed` 1.0→2.0→1.0，W61 未复现系采样时播放器不在播放态。门禁 8 任务 **713 项 / 0 失败**（基线 704，新增 9）；真机 Pad 5 主 + K60 抽验全过、双机 0 FATAL·ANR；改动 = `data` / `core` / `modes:film` / `app/phone`（无红线文件）。详见 `TEST_PLAN` §7.6.6。

**W64 阅读 / 音乐 / 首页修复更新（2026-10-04，分支 `fix/w64-reader-music-home`，起点 master `a8a580f`，已推 origin，待负责人合并；真机已通过）**：用户功能全检的 5 项收口——①**阅读加载中返回仍占网络**：根因 = 阻塞 OkHttp `execute()` 不随 `viewModelScope` 取消；修法 = 下载循环 `ensureActive()` + `Job` 取消回调 `call.cancel()` + `.part` 清理，ViewModel 侧 `CancellationException` 重抛；②**已下载的书打开偶尔慢**：根因 = 打开关键路径同步查服务器阅读进度（2 次请求 / 最长 30 s）；修法 = 2 s 超时回退本地 + 分段耗时日志；③**批注范围**：只读核对 = 批注 / 搜索为 PDF 专属（EPUB / CBZ 无入口，属设计）；UI 文案已注明；EPUB 批注成本评估 5–8 人日写入 `READER_PLAN` §10，本波不做待确认；④**音乐歌曲 Tab「播放全部 / 随机播放」**：纯函数 `playAllOrder`（2 单测）+ 顶部两键（多选态禁用）；⑤**首页书籍卡封面**（含用户第 12 条口径修订）：与书架同源的 `BookCoverProvider` 链路，优先级 = **服务器图 → 本地封面（已生成缓存 / 本地提取）→ 风格化占位**（`displaySource` 状态机，服务器图加载失败自动回落本地；离线可用）；占位 = 书图标 + 当前域媒体色底（`BookCoverPlaceholder`，复用矢量 / token，不新增位图）。门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun` **717 项 / 0 失败 0 错误**（基线 713 + 新增 4 = core 2 + music 2）；红线 = `modes/book` / `modes/music` / `modes:film`（首页）已申报；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 未动。提交 `06bd7d8` / `360c378` / `caa5288` / `6d4bcd9`。**真机通过（K60 `8e875894`，2026-10-04 14:02–14:31）**：取消 `.part` 返回 1 s 内删除、打开耗时 2,035·1,028·2,007 ms（旧包粗测 2,498·2,495·4,903 ms）/ 飞行 2 ms、音乐「播放全部」列表序首曲 + 随机首曲「出陣」、首页 8 本封面 + 离线回落 + 风格化占位、批注文案 + EPUB 无入口、0 FATAL·ANR；未覆盖 Pad 5 交叉抽验（W63 占用）。详见 `READER_PLAN` §10 / `MUSIC_PLAN` §7 / `UI_PLAN` §5 W64。

## 4. 里程碑状态（源自旧 `docs/PLAN.md`，已合并）

| 阶段 | 内容 | 状态 |
|------|------|------|
| M0 | 环境打通、基线构建、服务器连通性 | ✅ |
| M1 | 品牌化（影阁）+ 深灰蓝影院风设计系统 + 首页 | ✅ |
| M2 | 连接层：HTTP/HTTPS、自签名证书、Quick Connect、多用户 | 🟢 Quick Connect 已有；**W31 自签证书 TOFU + 多用户已完成 K60 真机联调**（会话复用修复待合并） |
| M3 | 浏览体验：首页、媒体库、详情、搜索、筛选 | ✅ 主体完成（抽屉导航 + 海报墙 + 媒体库分区 + 设置对齐官方） |
| M4 | 播放器重构：双内核、libass 字幕、倍速、比例、章节、Trickplay、跳片头、PiP | 🟡 双内核 / 倍速 / 比例 / 章节 / Trickplay / 跳片头 / PiP 已有；**libass 双内核完成：mpv 原生（W15，`0c3f8bc`）+ Exo 路径（W16 `feature/w16-exo-libass-decode`，含 SRT 覆盖与失败回退）**；详见 `PLAYER_PLAN.md` §18 / §19 |
| M5 | 手势体系：长按 2×、滑动 seek、左亮度 / 右音量、双击、双指缩放、锁定、灵敏度 | 🟡 横向 seek（含渐进加速）/ 长按倍速 / 双指缩放已有；锁屏与优先级仲裁见 `PLAYER_PLAN.md` §1.3 |
| M6 | 字幕 / 音轨语言智能识别与跨视频记忆 | ✅ |
| M7 | 下载离线增强、投屏、同步观看 | 🟡 下载管理 UI + 任务韧性（W32，待真机验收）；投屏 / 同步观看未开始 |
| M8 | 稳定性与性能：崩溃兜底、降级策略、布局回归、体积与内存 | ⛔ |

## 5. 多会话协作规程（避免单会话过长）

1. **一条任务线一个会话**；单个会话只做 **1–2 个"一屏能验证完"的任务**，做完就提交并交接。
2. **会话开始**：读本文件 → 读对应任务线文档 → `git log --oneline -10` 看最新状态 → 挑一个任务开工。
3. **会话结束**：更新任务线文档（勾进度 + 写日志）→ 需要时更新本文件 §3 状态 → `git commit`（`feat(线): …` / `fix(线): …`）。
4. **上下文纪律**：工具输出裁剪（`-Last N` / `Select-String`）；截图只在必要时看、看完即删；单会话上下文过半就交接（进度写进文档即可无损接续）。
5. **验证纪律**：真机为准（小米平板 5 / Android 13，模拟器已弃用）；改播放 / 布局必须在真机验证后再勾任务。
6. **文档纪律**：项目级状态只写本文件；任务线细节写各自文档；不新建零散 `.md`。
7. **真机纪律（v3，2026-10-04 起 · 设备直接分配给开发会话）**：`adb` 真机同一时刻只允许一个会话使用。
   开波时由负责人**直接分配设备**给开发会话（写明 serial + 主 / 抽验）；开发会话在门禁全绿后**顺便完成真机测试**
   （负责人本会话不复测，避免重复劳动）。使用前在 `E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\device-lock.md`
   登记（会话名 / 设备 / 开始时间 / 预计时长 / 回滚预案），完成后**立即写释放时间 + 结论 + 还原**。
   **禁止按超时自动接管**；设备冲突时由负责人明确指派。开发会话汇报必须**分列「已完成真机测试」与「未覆盖项」**，
   人工感知类条目转交用户代测；负责人验收只做范围核对 / 门禁复跑 / 合并 / CI / 文档。
   若第二台设备（Redmi K60）接入，由负责人按 serial 分配，可并行使用。

### 全局命令

```powershell
$env:JAVA_HOME='D:\Android\Android Studio\jbr'
cd E:\codex_work\Android_Studio_Work_Space\Cinefin
.\gradlew.bat :app:phone:assembleDebug --console=plain                         # 构建
adb install -r app\phone\build\outputs\apk\libre\debug\phone-libre-arm64-v8a-debug.apk   # 装机（真机 arm64）
adb shell dumpsys media_session | Select-String aurorama                      # 播放会话（W41 起包名 aurorama）
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
