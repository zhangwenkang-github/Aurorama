# Cinefin · 下载 / 离线任务线（DOWNLOAD_PLAN）

> **本文件是下载 / 离线线的唯一权威文档**：需求、决策、进度、验收、踩坑都写在这里，不新建零散 `.md`。
> 维护会话：W36-OFFLINE（分支 `feature/w36-offline-closure`，基线 master `bd6f8f0`；W32/W34 历史见 §4/§4.1）
> 最后更新：2026-10-03

## 1. 范围与现状

| 项 | 说明 |
|----|------|
| 下载引擎 | 系统 `DownloadManager`（`core/utils/DownloaderImpl`），支持系统级任务持久化与 Range 断点续传 |
| 记录 | Room `sources` 表存 `downloadId` / `path`（进行中为 `.download` 后缀） |
| 完成链路 | `DownloadReceiver` 收到 `DOWNLOAD_COMPLETE` → 重命名 → 更新 path |
| 离线入口 | `JellyfinRepository.getDownloads()`（movies + shows），下载页 `app:phone/DownloadsScreen` |
| 本波边界 | **不含本地文件播放**（留给后续波，避免与 `player:local` 冲突）；不改 `player:core` / `player:local` |

W32 之前的问题：没有失败任务概念（失败即删记录）、没有暂停 / 恢复 / 批量操作、失败原因不可读、网络恢复不会自动重试。

## 2. 本波需求（W32）

1. **下载管理 UI**：列表分「进行中 / 已完成 / 失败」，支持暂停 / 恢复 / 重试 / 删除、批量操作、存储占用显示；入口沿用现有「下载」页面。
2. **任务韧性**：进程被杀 / 重启后任务可续传（断点续传或安全重试）；网络恢复自动重试；失败原因可读（空间不足 / 网络 / 服务器错误）。
3. 本文件按项目惯例建立并维护。

### 2.1 W34 增量需求（用户 2026-10-03）

4. **下载列表层级化**：按媒体类型与层级组织——**视频：节目 → 季 → 剧集**；**音乐：专辑 → 曲目**；**书籍：书籍（含封面）**；层级可展开 / 折叠，容器上聚合进度与状态（如 `3/12 集`、下载中 / 已完成）。
5. **封面 / 缩略图**：三类下载都要有图——视频用节目海报 / 剧集缩略图、音乐用专辑封面、书籍用封面；无图时用类型图标占位（沿用现有 Prism 组件与 Coil 图片链路，不新增位图资源）。
6. **兼容 W32 行为**：与三组状态（进行中 / 已完成 / 失败）、暂停 / 恢复 / 重试 / 删除、存储占用、批量操作叠加，不回归。

### 2.2 W36/W37 离线与本地媒体需求（用户 2026-10-03）

7. **离线闭环（W36）**：离线模式（无账号 / 无网络）可访问并播放/阅读**已下载媒体**（复用 W34 层级：节目→季→剧集 / 专辑→曲目 / 书籍）；每个已下载条目增加「**允许离线模式观看**」开关（默认允许；关闭后离线隐藏、在线仍可用）；**登录页提供「离线模式」按钮**进入无账号离线模式，登录成功后自动退出离线模式。
8. **本地文件库（W37，用户 2026-10-03 细化）**：媒体库页面提供**常显**的「**+ 建立本地媒体库**」入口（在线 / 离线都可见）；**一个本地库可添加多个文件夹**、**库可命名**；**每个文件夹单独设置**浏览模式——「按文件夹层级显示」或「文件夹及子文件夹所有文件平铺显示」；库级设置 = 名称 / 类型 / 是否在媒体库显示；覆盖**视频 / 音乐 / 书籍（PDF/CBZ/EPUB）**；本地音乐**并入音乐模式曲库且可切换来源**（服务器与本地可混合在同一播放列表）；采用 SAF 持久化权限；删除库 / 移除文件夹**只解除关联，不删除源文件**（需明确提示）。
9. **在线融合设计建议**（W37 采用，先记录）：来源分层展示（媒体库总览加「本地媒体」入口；音乐曲库加「全部/服务器/本地」筛选；搜索合并两来源并标来源徽标）；本地条目复用同一套 UI/播放链路，元数据取「音乐内嵌标签 → 文件名/同目录封面 → 视频首帧/书籍首页」；队列项带 `source`，允许 server+local 混合队列，离线切换时不中断本地项；「本地优先」只对**本 App 下载的媒体**启用（用户自选文件夹不做自动匹配去重）；客户端设置新增「本地媒体」分组（来源开关 / 默认浏览模式 / 扫描范围 / 封面策略 / 来源徽标）。

### 2.3 W36 补充要求（负责人转达，2026-10-03）

10. **层级图规则**：节目（Show）/ 季（Season）用海报、剧集（Episode）用缩略图；回退顺序 = 剧集缩略图 → 节目/季海报 → 类型图标。W34 已有的 `ImagesDownloaderWorker` 在下载时缓存 `files/images/<id>/primary|backdrop`（show / season / episode / movie / 曲目 / 书籍均会入缓存），W36 只负责「本地优先 + 回退顺序 + 相对路径补全」；缓存不全的旧下载仍退回图标。
11. **离线媒体库范围**：离线时「媒体库」页面只显示两类——①已下载且允许离线观看的**节目**（显示标题、季/集号、时长等本地元数据快照）；②**本地媒体库**（W37，W36 占位 + 可见性开关）。其余服务器库在离线模式下不出现；音乐 / 书籍仍走各自入口（音乐 Tab / 书架 Tab）。
12. **本地媒体库**：W36 只落 `pref_local_library_visible`（默认关）与入口占位卡；「建立本地媒体库」（SAF 添加文件夹）属于 W37。

## 3. 决策记录

| 编号 | 决策 | 理由 / 后果 |
|------|------|-------------|
| D1 | **保留系统 DownloadManager 作为唯一下载引擎**，不换自研 Range 下载器 | 进程被杀 / 重启 / 换网络时任务由系统服务托管，天然续传；本波无法真机验证，换引擎风险过高。外部流 / trickplay 下载链路保持原样 |
| D2 | **暂停 = remove 系统任务**（进度不保留）；**恢复 = 安全重试**（重新入队，从 0 开始） | DownloadManager 没有公开 pause/resume API（已用 `javap android.jar` 核实）。K60 真机实测：`remove` 会删除 `.download` 残片，暂停后无法续传；真正的断点续传场景是「进程被杀 / 断网等待网络」由系统 Resume（D1）。原「保留残片」描述已按实测修正 |
| D3 | **状态持久化到 `sources` 表**：`taskStatus` / `failureReason` / `updatedAt`，Room v8 → v9（AutoMigration） | 暂停状态必须跨进程保留；旧数据（三列 NULL）按路径推断：`.download` = 失败残片，否则 = 已完成 |
| D4 | **失败任务不再删除记录**：`DownloadReceiver` / `DownloaderImpl` 失败时写 FAILED + 原因 | 失败任务要可在 UI 重试；旧实现失败即 `deleteItem`，用户看不到失败项 |
| D5 | **网络恢复自动重试 = CONNECTED 约束的 WorkManager 唯一任务**；只重试网络 / 服务器类失败，每个任务每个进程一次 | 断网时不空转；进程重启后重新获得一次机会；避免页面轮询造成无限重试 |
| D6 | **状态判定 / 恢复策略抽 `DownloadTaskRules` 纯函数** | 队列状态机与续传判定可单测（无 Android 运行时依赖），符合本波门禁要求 |
| D7 | 已完成列表沿用 `getDownloads()`（movies + shows）；仅有进行中 / 失败 source 的电影不重复出现在「已完成」 | 剧集容器（show，无 LOCAL source）保留为入口；其删除仍走详情页 |
| D8 | 暂停 / 重试 / 删除统一清理 DownloadManager sidecar（`.<目标名>.js`） | K60 实测 `remove` 后隐藏 sidecar 残留（约 50 kB/任务），存储占用清不干净；修复 `5341027`，真机复验删除后目录归零 |
| D9 | **层级化在 UI 层做**：新建纯函数模块 `DownloadHierarchy`（`DownloadHierarchyEntry` → 容器 / 展开折叠 / 聚合状态），不改 `sources` 表结构 | 层级只是展示折叠，不该动下载引擎；纯函数可单测，`modes:film` 6 项新增单测覆盖分组 / 聚合 / 展开折叠 |
| D10 | **视频层级的数据来源分层**：已完成剧集由 `getCompletedEpisodeHierarchy()`（LOCAL source 反查 `episodes` + joins）提供；进行中 / 失败任务的节目 / 季归属由 `DownloadTask` 侧的本地 DAO 查询补全 | `getDownloads()` 原本只返回 movies + shows，剧集本体在 `episodes` 表；不动引擎即可拿到 `seriesId` / `seasonId` 组织「节目 → 季 → 剧集」 |
| D11 | **音乐层级用「侧车 + 主库快照」双来源**：下载时把专辑 / 艺人 / 音轨号写 `files/download_media.tsv`；层级标题与封面优先取主库 `getMusicTrackMetadata()`（60 s TTL 缓存），离线退侧车 | 服务器无 MusicAlbum 实体（音乐线既有结论），专辑只能按名字聚合；侧车保证重启 / 离线后仍能分组 |
| D12 | **音乐下载入口加在音乐模式曲目菜单**（`下载` / `取消下载` / `删除下载` 三态）；书籍沿用阅读器离线文件（`files/books/*.book`），下载页只读展示 + 删除，不重复实现引擎 | 复用既有 DownloadManager 与阅读器离线链路；书籍占用单独显示（`含书籍 X`），避免和 `downloads/` 目录混淆 |
| D13 | **`ReaderRepository` 的 Hilt 绑定从 `modes:book` 上移到 `data`**（新增 `ReaderRepositoryModule`） | 下载页（`modes:film`）要读离线书籍列表；绑定留在 book 模块会让只依赖 `modes:film` 的宿主（TV 壳）缺绑定 |
| D14 | **修 `JellyfinRepositoryImpl.getItem()` 未合并本地 source** 的旧缺陷（补传 `database`） | W34 音乐「已下载」判定依赖 `item.sources` 里的 LOCAL 记录；缺失会让音乐菜单无法进入「删除下载」分支 |

### 3.1 W36 决策（离线闭环）

| 编号 | 决策 | 理由 / 后果 |
|------|------|-------------|
| D15 | **`sources` 表加 `allowOffline`（默认 1），Room v9 → v10 AutoMigration**；书籍开关（无 source 行）落 `pref_offline_blocked_books` 字符串集合 | 每个「已下载条目」一个开关；视频 / 音乐天然有 source 行，书籍离线文件不经过 DownloadManager，只能落偏好；默认全开，老数据迁移后行为不变 |
| D16 | **新增 `OfflineMediaRepository`（`core/utils`）**：直接读 Room `sources` + `episodes`/`shows`/`seasons` + `download_media.tsv` 侧车 + `ReaderRepository.listLocalFiles()`，**不依赖 currentServer / userId** | 「无账号离线模式」要求不依赖任何服务器会话；DAO 层能拿到全部已下载条目与层级归属 |
| D17 | **离线可见性 / 导航门控抽纯函数**：`OfflineMediaVisibility`（允许开关过滤 / 层级构建 / 只保留节目）与 `resolveStartDestinationKind`（离线优先落离线首页） | 门禁要求；`app:phone` 新增 7 项单测（过滤、层级、管理视图、视频范围、导航分支） |
| D18 | **离线图片 = 本地优先**：下载时 `ImagesDownloaderWorker` 已缓存 `images/<id>/primary|backdrop`；W36 在装配时做「本地存在性判断 + 回退顺序」（剧集缩略图 → 季海报 → 节目海报 → 图标），并修 `ArtworkThumb` 相对路径补全 | 不新增下载流程；离线也能显示海报 / 缩略图；缓存缺失的旧下载自动退回图标 |
| D19 | **离线仓库读取方法容忍 `userId = null`**（`toFindroid*` 系列签名放宽为 `UUID?`）；写操作无账号时静默跳过 | 无账号离线模式下浏览 / 播放本地文件不崩；播放进度等写操作在无账号时无处可写，直接跳过 |
| D20 | **书籍下载写 `<id>.title` 侧车**（下载完成后补一次 `GET /Users/{uid}/Items/{id}` 取书名） | 离线书架 / 离线媒体库能显示真实书名；W36 之前下载的旧书无侧车，退回「离线书籍 <id8>」占位 |
| D21 | **离线媒体库只显示「节目（视频）」+「本地媒体库」占位**（音乐 / 书籍在各自入口） | 用户补充要求；音乐走音乐 Tab 的离线曲库，书籍走书架 Tab 的离线列表 |
| D22 | **离线曲库随模式切换自动重载**：`MusicModeViewModel` 监听 `offlineMode` 偏好变化 → `refresh()` | 真机缺陷：退出离线后音乐页仍显示离线曲库（VM 复用同一实例）；监听后进出模式都即时切换来源 |

## 4. 实现地图（W32）

| 文件 | 作用 |
|------|------|
| `core/utils/DownloadTask.kt` | 任务模型 + 状态机 / 失败分类 / 恢复策略纯函数（`DownloadTaskRules`） |
| `core/utils/DownloadManagerSupport.kt` | DownloadManager 查询快照、完成落盘、失败分类 |
| `core/utils/DownloaderImpl.kt` | 对账 / 暂停 / 恢复 / 重试 / 删除 / 占用统计 / 网络重试 worker 入队 |
| `core/utils/DownloadReceiver.kt` | 完成补重命名；失败归档原因并触发网络重试入队 |
| `core/work/DownloadRetryWorker.kt` | 网络恢复后自动重试（CONNECTED 约束、唯一任务名 `downloadNetworkRetry`） |
| `data/.../FindroidSourceDto.kt` + `ServerDatabaseDao.kt` + `ServerDatabase.kt` | sources 三列 + DAO 查询 + v9 AutoMigration |
| `modes/film/.../downloads/DownloadManagerState.kt` | UI 状态 / 动作 / 选择 key |
| `modes/film/.../downloads/DownloadsViewModel.kt` | 1.5s 对账轮询 + 批量操作编排 |
| `app/phone/.../film/DownloadsScreen.kt` | 三组管理 UI（分组 chips / 任务卡 / 多选操作栏 / 存储占用 / 删除确认） |
| `app/phone/src/test/.../DownloadTaskRulesTest.kt` | 纯函数单测（7 项） |

## 4.1 实现地图（W34 增量）

| 文件 | 作用 |
|------|------|
| `modes/film/.../downloads/DownloadHierarchy.kt` | 层级模型 + 构建器 + 展开折叠扁平化（纯函数：`DownloadHierarchyBuilder` / `DownloadHierarchyFlattener` / `aggregateStatus`） |
| `modes/film/src/test/.../DownloadHierarchyBuilderTest.kt` | 层级单测 6 项（节目→季→剧集 / 专辑 / 书籍 / 聚合状态 / 默认折叠与展开） |
| `data/.../database/ServerDatabaseDao.kt` | `getDownloadedEpisodeHierarchy()` / `getCompletedEpisodeHierarchy()` / `getDownloadedItemIds()` |
| `data/.../database/DownloadedEpisodeHierarchy.kt` | 已下载剧集的节目 / 季归属行 |
| `data/.../repository/MusicTrackMetadata.kt` | 主库音频快照（专辑 / 艺人 / 音轨号 / 封面） |
| `data/.../repository/ReaderRepositoryModule.kt` | 阅读器仓储绑定上移到 data 层 |
| `core/.../utils/DownloadMediaSidecar.kt` | 音乐 / 书籍下载媒体侧车（`files/download_media.tsv`） |
| `core/.../utils/DownloadTask.kt` / `Downloader.kt` / `DownloaderImpl.kt` | `DownloadTask` 增媒体类型 + 视频 / 音乐层级字段；`Downloder` 增带专辑元数据的下载重载、`downloadedItemIds()`、`mediaSidecar()` |
| `modes/music/.../MusicModeViewModel.kt` / `MusicModeScreen.kt` | 曲目菜单「下载 / 取消下载 / 删除下载」+ 行内下载态 |
| `app/phone/.../film/DownloadsScreen.kt` | 层级列表（容器 / 季 / 条目卡 + 封面 / 占位图标）+ 媒体筛选 chips + 容器删除确认 |

## 5. 进度

- [x] sources 状态列 + Room v9 AutoMigration + DAO
- [x] 任务模型 / 状态机 / 恢复策略纯函数 + 单测（7 项）
- [x] 对账（完成补重命名 / 失败归档 / 进度刷新）
- [x] 暂停 / 恢复 / 重试 / 删除（单个 + 批量）
- [x] 存储占用显示（下载目录占用 + 可用空间）
- [x] 网络恢复自动重试 worker（CONNECTED + 每进程一次）
- [x] 下载管理 UI（进行中 / 已完成 / 失败 + 多选）
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（58 项全绿；新增 `DownloadTaskRulesTest` 7 项）
- [x] **K60 真机验收（2026-10-02，`8e875894`）**：断网续传 / 进程被杀续传 / 批量暂停删除 / 完成与删除 / 存储占用全部通过，并修复 sidecar 残留（`5341027`）；逐条结论与未触发项见 §6 / §7
- [x] **W34 层级化 + 封面（Pad 5 `43af8627`，2026-10-03）**：视频 节目→季→剧集 / 音乐 专辑→曲目 / 书籍封面 + 容器聚合进度、无图占位、暂停 / 恢复 / 删除 / 批量 / 存储占用叠加不回归，见 §9
- [x] W34 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（58 项）+ `:modes:film:testDebugUnitTest`（6 项，新增）全绿
- [x] **W36 离线闭环（2026-10-03）**：离线媒体目录（`OfflineMediaRepository`）+ 每项「允许离线模式观看」开关（Room v10 `sources.allowOffline` / 书籍偏好）+ 登录页 / 用户页 / 服务器页 / 欢迎页离线入口 + 登录成功自动退出 + 离线首页 / 媒体库 / 音乐 / 书架 IA 与空态 + W37 本地文件库占位（`pref_local_library_visible`）
- [x] **W36 补充要求**：层级图规则（节目 / 季海报、剧集缩略图 + 回退）读本地缓存；离线媒体库只显示节目 + 本地媒体库占位；条目时长快照
- [x] W36 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（70 项，新增 7）+ `:data:testDebugUnitTest`（19）+ `:modes:film:testDebugUnitTest`（6）+ `:modes:music:testDebugUnitTest`（99）+ `:modes:book:testDebugUnitTest`（106）全绿

## 9. W34 真机验收（Pad 5 `43af8627`，2026-10-03）

构建：`feature/w34-download-hierarchy`（起点 master `4c80276`）；测试服务器只读，全程未写服务器。

1. **视频层级（节目 → 季 → 剧集）**：通过。下载「超能力女儿」第 1、2 集后，「已完成」页出现节目容器 `超能力女儿`（`2/2 集 · 已完成 · 2.49 GB`）；点开 → 季容器 `超能力女儿 / 2/2 集 · 已完成`；再点开 → 两条剧集行 `Hinamatsuri · 已完成 · 1.15 GB / 1.35 GB`（按集号排序）。默认折叠，点卡片展开 / 折叠（截图为展开三级）。
2. **音乐层级（专辑 → 曲目）**：通过。音乐模式曲目菜单新增「下载」；下载完成后「已完成」页出现专辑容器 `Ang 5.0 / 张韶涵 · 1/1 首 · 26.40 MB`，展开为曲目行 `亲爱的，那不是爱情 · 已完成 · 26.40 MB`。下载中状态（`Ang 5.0 · 0/1 首` + 行内 `正在下载… 79% · 20.97 MB / 26.40 MB`）同样验证到。
3. **书籍封面**：通过。阅读器离线书籍（7 本，767 MB）全部出现在「已完成」页书籍分组，显示书名 + 体积；封面走既有 Coil 链路（服务器主图 / 本地无图占位）。
4. **无图占位**：通过。无主图条目显示类型图标（视频 🎞 / 音乐 ♪ / 书籍 📖 对应 Core 图标），不新增位图资源。
5. **W32 行为不回归**：通过。①状态三组（进行中 / 已完成 / 失败）计数与筛选正常；②条目级暂停 / 删除按钮、容器级删除（含子项）正常；③多选 → 选择栏（暂停 / 继续 / 重试 / 删除）→「确定删除选中的 1 项下载吗？」→ 删除后「已完成 4 → 3」、占用同步下降；④存储占用显示 `含书籍 767 MB`，与书籍目录实测一致。
6. **稳定性**：通过。整轮 logcat 0 FATAL / 0 ANR；`W34` 调试日志确认层级装配（tasks / completed / books / music 计数）。

设备还原：删除测试产生的 3 个视频下载与 1 个音乐下载（`downloads/` 归零，仅保留 W34 之前的阅读器离线书籍）；App force-stop；未改 prefs / 旋转 / 网络；K60 全程未占用。

## 6. 验收结果（K60 真机，2026-10-02，serial `8e875894`）

环境：整合版 master `9738877`（APK 21:31）+ 修复 `5341027`；测试服务器只读，全程未写服务器。

1. **进行中 / 暂停 / 恢复**：通过。详情页发起「超能力女儿」第 1 集 → 下载页显示进度 / 大小；暂停后状态「已暂停」、`remove` 清掉 108 MB 残片；恢复重新入队从 0 开始（安全重试）。**续传性结论：暂停路径不续传**（原决策 D2 已修正）。
2. **断网续传**：通过（两次）。下载中关 Wi-Fi + 数据 → 状态「等待网络」、文件停在 295.5 MB；恢复网络后自动回到「正在下载」并从 295.5 MB 继续（31.4 → 48.3 MB 第二次同样通过）。
3. **网络恢复自动重试一次**：**未真机触发**。断网 2 分钟系统始终为「等待网络」，不进入 FAILED；网络 / 服务器类 FAILED 的自动重试由 `DownloadTaskRules.isAutoRetryEligible` 单测 + `DownloadRetryWorker` 代码路径覆盖，建议后续用服务器故障窗口复验（§7）。
4. **空间不足失败原因**：**未真机触发**（可用 219 GB，服务器无更大条目）；enqueue 前拦截与 STORAGE 原因映射由既有逻辑 + 单测覆盖。
5. **force-stop 续传**：通过。下载中 `am force-stop` 后进程消失，10 秒内文件 314.6 → 321.7 MB 继续增长；重启 App 显示「进行中 28% · 332 MB」，对账正常。
6. **reboot 续传**：**未验证**。设备有锁屏，reboot 后无法自动解锁，避免卡死未执行；与 force-stop 同属系统任务持久化，建议后续窗口补验。
7. **已完成 / 删除 / 批量**：通过。344 MB（你遭难了吗？第 1 集）完整下载 → 重命名 + 外部字幕流落盘、「已完成」显示、占用 379 MB；详情页删除后文件与条目清空；批量（2 任务）选择 → 批量暂停（两项「已暂停」）→ 批量删除确认对话框 → 全部清空。
8. **存储占用**：通过。UI「已占用 379 MB」与目录实测 378.57 MB 一致；修复 sidecar 后暂停 / 删除可归零。
9. **稳定性**：通过。全程 logcat 无 FATAL / ANR / Input dispatching timeout。

设备还原：App force-stop；`pref_downloads_mobile_data` 还原 `false`；移动数据 / Wi-Fi 保持开启；旋转未改动（自动旋转 1 / user 0）；下载目录清空、`/sdcard` 临时文件与遗留孤儿 sidecar 已删除。

## 7. 踩坑与遗留

- **DownloadManager 没有公开 pause/resume**：官方 API 只有 enqueue / remove / query（`javap` 核实）；暂停必须 remove，恢复只能重新入队，「断点续传 or 安全重试」在暂停路径当前取安全重试。
- **`remove` 会删除残片（K60 / Android 15 实测）**：暂停后已下载字节不可保留，恢复从 0 重新下载；「保留残片」的旧描述已修正，若后续要真正暂停续传需自研 Range 下载器。
- **sidecar 残留**：`remove` 后隐藏文件 `.<目标名>.js`（约 50 kB/任务）残留，导致存储占用清不干净；`5341027` 在暂停 / 重试 / 删除时同时清理 `.<name>.js` 与 `<name>.js`，真机复验通过。修复前遗留的孤儿 sidecar 需手工清理（本次已清）。
- **失败任务的 DownloadManager 记录**：失败后系统记录仍在（可查询 reason）；删除任务时 `remove` 清掉，避免残留。
- **旧数据判定**：v9 迁移后旧 source 的 `taskStatus` 为 NULL，首次进入下载页按路径自动补写状态（`.download` → FAILED，其余 → COMPLETED）。
- **未触发项**：网络 / 服务器 FAILED 的自动重试、空间不足失败列表、reboot 续传（见 §6 3/4/6）；建议在服务器可停机窗口或备用条目下补验。
- **未做**：本地文件播放（后续波）；下载限速 / 并发队列调整；已完成列表的封面图（当前为文本行卡片）；孤儿 sidecar 的自动清扫。

## 8. 日志

| 日期 | 会话 | 内容 |
|------|------|------|
| 2026-10-02 | W32-DOWNLOAD | 建线；实现管理 UI + 任务韧性 + 单测；门禁 `assembleDebug`（含 TV）/ `ktfmtCheck` / app 单测 58 项全绿；提交 `138b189`(feat) + `4053cb5`(docs) 已推送未合并；设备待调度，真机清单见 §6 |
| 2026-10-02 | W32-DOWNLOAD | **K60 真机验收（8e875894）**：断网续传 / force-stop 续传 / 批量暂停删除 / 完成与删除 / 存储占用 / 0 FATAL-ANR 通过；暂停路径实测不续传（D2 修正）、修复 sidecar 残留 `5341027`（复验通过）；未触发：FAILED 自动重试、空间不足、reboot；设备已还原；修复提交已推送未合并 master |
| 2026-10-03 | W34-DOWNLOAD | 层级化 + 封面：新建 `DownloadHierarchy`（纯函数 6 项单测）、视频层级（已完成剧集 DAO 反查 + `getDownloads` 补剧集）、音乐下载入口 + 侧车、书籍离线纳入列表、`ReaderRepository` 绑定上移 data、修 `getItem` 本地 source 合并；门禁四绿；Pad 5 真机 6 组通过（见 §9）；提交见交接 |
| 2026-10-03 | W36-OFFLINE | 离线闭环：`OfflineMediaRepository`（无账号可读）+ `sources.allowOffline` Room v10 + 书籍开关偏好 + 登录页 / 用户页 / 服务器页 / 欢迎页离线入口 + 登录成功自动退出 + 离线首页 / 媒体库 / 音乐 / 书架 IA 与空态 + W37 本地文件库占位；门禁全绿（app 70 / data 19 / film 6 / music 99 / book 106）；Pad 5 + K60 真机 8 组通过（见 §11） |
| 2026-10-03 | W36-OFFLINE | 补充要求（负责人转达）：层级图规则（节目 / 季海报、剧集缩略图 + 回退）+ 离线媒体库只显示节目 + 本地媒体库占位 / 开关 + 时长快照；真机修复 2 处（离线 VM 缓存刷新、退出离线后音乐曲库不切换）+ 离线仓库 `getDownloads` 补剧集层级；下载测试数据已删、双机已还原 |

## 10. W34 遗留

- 音乐「已完成」判定依赖主库快照 / 侧车：完全离线且侧车缺失时会退化为「未分类」专辑（不崩溃，分组名降级）。
- 书籍删除走阅读器 `deleteLocalFile()`，与下载引擎的 `sources` 表不互通（设计如此：书籍离线本就不在 DownloadManager 链路）。
- 音乐下载入口目前只做单曲；整专辑批量下载未做（如需可后续波次加法）。
- 视频容器只列已下载剧集，「3/12 集」表示容器内已下载数 / 容器内条目数，不是全季总集数（全季集数需要额外请求 `getEpisodes`，评估后留给后续波次）。

## 11. W36 真机验收（Pad 5 `43af8627` + K60 `8e875894`，2026-10-03）

构建：`feature/w36-offline-closure`（起点 master `bd6f8f0`）；测试服务器只读（登录为认证 + 用户数据白名单，未写媒体库）。

1. **三类媒体在线下载 → 断网 → 离线模式浏览 / 播放 / 阅读（Pad 5，Pad 5 全程开飞行式断网）**：通过。①视频「超能力女儿」S1E1（1.15 GB）下载完成后断网 + 离线媒体库直接播放——进程 fd 实测打开 `/storage/.../files/downloads/beec8170-…`，`media_session state=PLAYING` position 5.9s→18.0s 递增；②音乐「我曾爱过一个人 (笛子版)」离线曲库（"共 1 张专辑 / 离线模式 · 仅显示本机已下载的 1 首曲目"）点击起播，fd 打开 `1b3500b3-…` 本地文件；③书籍 `futuristic_tales` 离线书架 → 直接进阅读器（"离线可读 · 703 KB / 4/4"）。
2. **「允许离线模式观看」开关**：通过。①离线媒体库关闭书籍 `futuristic_tales` → 列表隐藏（书籍 7→6）；右上角「眼睛」管理视图重现（Switch `checked=false`）→ 重新打开恢复；②音乐专辑容器开关关闭 → 音乐 1→0，DB 实测 `sources.allowOffline: 1b3500b3=0` → 管理视图恢复 → DB=1；③下载页容器开关关闭视频 → DB `beec8170=0` → 恢复=1；④在线不受影响：在线音乐 105 张专辑 / 书架 8 本 / 下载页 9 已完成均正常。
3. **登录页离线入口（无账号路径）**：通过。用户页「离线模式」按钮 → 离线首页（不依赖任何服务器会话）；登录页（添加用户）与服务器页同款入口已实现并经用户页 / 登录页真机到达。
4. **登录成功自动退出离线模式**：通过。离线模式内「登录到服务器」→ 添加用户 → `zhangwenkang` 登录成功 → `pref_offline_mode` 自动置 `false` + 回到在线首页；错误密码路径同时验证（提示「用户名或密码错误」，停留在离线模式）。
5. **空态与提示**：通过。离线首页（K60：视频 0 · 音乐 0 · 书籍 4）、音乐 Tab 空态（"共 0 张专辑 / 离线模式 · 还没有下载的音乐…"）、媒体库空态（全部关闭后提示进管理视图）、书架空态、各页「离线模式」提示条、W37 本地媒体库占位卡（开关默认关）。
6. **补充要求（层级图 / 媒体库范围）**：通过。离线媒体库只显示节目 + 本地媒体库占位；节目容器显示**节目海报**、季容器显示**季海报**、剧集行显示**缩略图**（截屏核对，来源 `files/images/<id>/primary`）；剧集行显示「本地文件 · 23 分钟 · 1.07 GB」。
7. **稳定性**：通过。Pad 5 + K60 全程 0 FATAL / 0 ANR。
8. **Room v9 → v10 迁移**：通过。双机安装后原服务器 / 账号 / 下载列表完整（K60 保留 4 本离线书）。

设备还原（两机）：删除测试下载（`downloads/` 目录归零，占用 1.92 GB → 767 MB 仅书籍）；`pref_offline_mode=false`、`pref_offline_blocked_books` 空、本地媒体库开关未写入；`am force-stop`；`/sdcard/w36_*.xml` 清理；K60 未改其他状态。

## 12. W36 遗留

- **下载页刷新竞态（既有）**：快速切页会取消正在执行的 `refresh()`（`JobCancellationException` 被 `runCatching` 吞掉），页面短暂显示 0 条，轮询 / 重进后自愈；本次未改（W32/W34 行为），建议后续单独加「取消不吞 + 重进重试」修复。
- **`ImagesDownloaderWorker` 目录存在即跳过**：缓存不完整（如首次拉图部分失败）时不会补拉，离线可能退回图标；后续可在对账时校验 `primary` 是否存在。
- **旧书籍无 `.title` 侧车**：W36 之前下载的书在离线书架显示「离线书籍 <id8>」占位；重新下载一次即补侧车。
- **离线模式下下载页**：已补剧集层级（D10 只改了在线实现，W36 真机发现并修复二者一致）；离线时「已完成」列表仍依赖 `currentServer` 偏好存在，纯新的无服务器设备以离线媒体库为准。
- **本地媒体库**：W36 仅占位 + 开关；SAF 添加文件夹、平铺 / 层级浏览、与服务器队列混合属于 W37。
