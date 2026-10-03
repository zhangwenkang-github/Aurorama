# Cinefin · 下载 / 离线任务线（DOWNLOAD_PLAN）

> **本文件是下载 / 离线线的唯一权威文档**：需求、决策、进度、验收、踩坑都写在这里，不新建零散 `.md`。
> 维护会话：W37-LOCAL-LIBRARY（分支 `feature/w37-local-library`，基线 master `8fef16d`；W32/W34/W36 历史见 §4/§4.1）
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
8. **本地文件库（W37，用户 2026-10-03 细化）**：媒体库页面提供**常显**的「**+ 建立本地媒体库**」入口（在线 / 离线都可见）；**一个本地库可添加多个文件夹**、**库可命名**；**每个文件夹单独设置**浏览模式——「按文件夹层级显示」或「文件夹及子文件夹所有文件平铺显示」；库级设置 = 名称 / 类型 / 是否在媒体库显示——类型**允许「视频 / 音乐 / 书籍 / 混合」**（混合库按文件类型自动分组）；**首页是否显示本地媒体（最近播放 / 继续观看）由开关控制（默认关闭）**；覆盖**视频 / 音乐 / 书籍（PDF/CBZ/EPUB）**；本地音乐**并入音乐模式曲库且可切换来源**（服务器与本地可混合在同一播放列表）；采用 SAF 持久化权限；删除库 / 移除文件夹**只解除关联，不删除源文件**（需明确提示）。
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

### 3.2 W37 决策（本地媒体库）

| 编号 | 决策 | 理由 / 后果 |
|------|------|-------------|
| D23 | **本地库模型全部落 `data`**：Room v11 追加 `local_libraries` / `local_library_folders` / `local_media_items` 三表（`AutoMigration 10 → 11`），仓库与纯函数同模块，UI 在 `app:phone/presentation/local` | core 依赖 data（离线仓库先例），反向会让 DI 成环；纯函数放 data 使 JVM 单测与实体/Schema 同源（新增 8 项单测） |
| D24 | **SAF 扫描用框架 `DocumentsContract`**（`buildChildDocumentsUriUsingTree` 递归 + 扩展名白名单），`takePersistableUriPermission` 落持久读权限 | 不新增 `androidx.documentfile` 依赖（`libs.versions.toml` 红线）；重启 / 冷启动后文件夹仍可读 |
| D25 | **索引只读、不拷贝源文件**；本地条目 itemId = `UUID.nameUUIDFromBytes(documentUri)` | 与需求一致（不复制 / 不移动）；确定性 UUID 让播放队列、阅读进度、书签、上报键跨重启稳定 |
| D26 | **不写 `sources` / `movies` 表**，改由 `JellyfinRepositoryImpl` / `JellyfinRepositoryOfflineImpl` 对本地 itemId **合成 LOCAL 源**（path = `content://` 文档 URI），播放上报对本地条目跳过 | 写 sources 会让下载页把用户文件当「已下载」，删除下载存在删源文件风险；合成源让视频复用现有 `PlayerActivity → PlaylistManager` 链路（Exo 原生支持 content URI），零改动 `player:core` / `player:local` |
| D27 | **阅读器支持 `content://` 三路**：PDF 走 `ParcelFileDescriptor` + PdfRenderer、CBZ 走 `ZipInputStream` 顺序重定位页源、EPUB 走 Readium ContentResolver 资源；本地书籍进度 `pendingSync=false`（只落本机） | SAF 拿不到随机访问 `File`；服务器没有本地 itemId，回传必然失败（会留下永远清不掉的待同步记录——真机拦下并修复） |
| D28 | **音乐曲库融合**：`MusicSong` 增 `localUri` / `source`；`MusicModeViewModel` 分开缓存「服务器（或离线已下载）」与「本地媒体库」两份曲库，按 `pref_music_source_filter`（全部 / 服务器 / 本地）重建专辑 / 艺术家 / 歌曲视图；曲目行来源徽标由 `pref_music_source_badge` 控制 | 需求第 8 条「本地音乐并入曲库且可切换来源」；混合同队列由 `MusicQueue` 现有模型承载，本地曲目解析不经过服务器（离线可播） |
| D29 | **首页开关 `pref_local_library_visible` 只管首页**：媒体库总览可见性由库级 `visibleInLibrary` 控制（总览页「眼睛」管理视图可恢复）；首页区块与库级开关互不影响 | 两个开关语义分离，避免"关了库级开关首页也一起消失"的困惑；首页默认关（沿用 W36 占位开关） |
| D30 | **封面策略分阶段**：音乐内嵌标签（`MediaMetadataRetriever`：标题 / 艺人 / 专辑 / 音轨 / 时长 / 内嵌封面落 `files/local_covers/`）→ 同目录封面图；视频首帧 / 书籍首页封面列为遗留 | D 组为第二优先；标签 + 同目录封面已覆盖音乐来源徽标 / 专辑分组的主要价值，首帧类封面成本高、留后续波 |
| D31 | **顺手修 W36 两项遗留**：①`DownloadsViewModel.refresh()` 对取消重抛（不再被 `runCatching` / 外层 `catch` 吞掉）；②`ImagesDownloaderWorker` 由「目录存在即跳过」改为「按 `primary` / `backdrop` 文件补拉」 | 两项都是 W36 §12 记录、改动局部且可回归：取消不再把下载页刷成 0 条；缓存不全的旧下载离线时能补图 |

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
- [x] **W37 本地媒体库（数据 + 仓库 + 纯函数）**：Room v11 三表（库 / 文件夹 / 条目，AutoMigration 10→11）+ SAF 递归扫描（`DocumentsContract` + 扩展名白名单 + 持久化权限）+ 确定性 itemId + 音乐标签 / 同目录封面；纯函数（分类 / 分组 / 层级·平铺）落 `data/local`，新增 8 项单测
- [x] **W37 打开链路**：本地视频 → 合成 LOCAL 源走现有播放器（Exo content URI）；本地 PDF / CBZ / EPUB → 阅读器 content URI 三路（PFD / ZipInputStream / Readium）；本地音乐 → 并入音乐曲库（来源筛选 全部 / 服务器 / 本地 + 混合同队列 + 来源徽标可关）
- [x] **W37 UI 与安全边界**：媒体库页（在线 / 离线）常显「＋ 建立本地媒体库」+ 库卡 + 文件夹管理（层级 / 平铺 + 移除）+ 库级「在媒体库显示」+ 首页开关（默认关）+ 删除 / 移除确认文案「只解除关联、不删源文件」
- [x] **W37 门禁**：根 `assembleDebug`（含 TV）+ `:app:phone:assembleDebug` + 根 `ktfmtCheck` 全绿；单测逐个 `--rerun` 数 `build/test-results/*.xml`：app **70** / core **16** / data **27**（新增 8）/ film **6** / book **106** / music **99**，全部 0 失败 0 错误
- [x] **W37 真机验收（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）**：建库（混合 + 2 文件夹）→ 层级 / 平铺 → 视频 / 音乐 / 书籍各打开一次 → 库级开关 → 首页开关 → 重启保留 → 删除后源文件仍在；见 §13
- [x] **W36 遗留顺手项**：下载页刷新竞态（取消不再吞 `JobCancellationException`）与 `ImagesDownloaderWorker` 按文件补拉（目录存在也补缺图）已修；旧书籍 `.title` 侧车式占位名仍留（见 §12）

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
| 2026-10-03 | W37-LOCAL-LIBRARY | 本地媒体库：Room v11 三表 + SAF 递归扫描（持久化权限 / 扩展名白名单 / 混合分组 / 每文件夹层级·平铺）+ 确定性 itemId + 合成 LOCAL 源（视频复用播放器）+ 阅读器 content:// 三路 + 音乐曲库来源筛选 / 混合同队列 / 来源徽标 + 媒体库与离线页常显入口 + 非破坏删除；顺手修 W36 两项遗留（下载刷新竞态 / 补拉图片）；门禁全绿（app 70 / core 16 / data 27 / film 6 / book 106 / music 99）；真机 Pad 5 + K60 抽验通过（见 §13） |

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

- ~~**下载页刷新竞态（既有）**：快速切页会取消正在执行的 `refresh()`（`JobCancellationException` 被 `runCatching` 吞掉），页面短暂显示 0 条，轮询 / 重进后自愈~~ → **W37 已修（D31）**：任务拉取与最外层 `catch` 都改为「取消重抛 + 失败兜底」，取消不再把状态刷成空。
- ~~**`ImagesDownloaderWorker` 目录存在即跳过**：缓存不完整（如首次拉图部分失败）时不会补拉，离线可能退回图标~~ → **W37 已修（D31）**：改为按文件补拉（`primary` / `backdrop` 缺失才下载，已有文件跳过）。
- **旧书籍无 `.title` 侧车**：W36 之前下载的书在离线书架显示「离线书籍 <id8>」占位；重新下载一次即补侧车。
- **离线模式下下载页**：已补剧集层级（D10 只改了在线实现，W36 真机发现并修复二者一致）；离线时「已完成」列表仍依赖 `currentServer` 偏好存在，纯新的无服务器设备以离线媒体库为准。
- ~~**本地媒体库**：W36 仅占位 + 开关；SAF 添加文件夹、平铺 / 层级浏览、与服务器队列混合属于 W37~~ → **W37 已交付**（见 §5 / §13）。
- **W37 新增遗留（本地媒体库）**：~~①D 组封面策略只做到「音乐内嵌标签 + 同目录封面」——视频首帧 / 书籍首页封面未做~~ → **W45 已做**（2026-10-03，`feature/w45-local-covers`：视频第 1 秒首帧 + PDF 首页 + CBZ 第一张图 + EPUB Readium 封面，懒生成 / `files/local_thumbs` 缓存 / 失败标记，见 §14 / `UI_PLAN` D47）；②CBZ 走 `ZipInputStream` 顺序重定位（大包逐页变慢，`ZipFile` 只接受 `File`）；~~③本地库条目不参与搜索（搜索仍未合并本地来源）~~ → **W43 已修**（2026-10-03，`feature/w43-search-local`：本地条目并入搜索 = 服务器 / 本地分区 + 来源徽标 + 打开链路，见 `UI_PLAN` D44 / §5 W43 验收）；④「本地优先」去重未做（只对 App 内下载媒体启用，用户自选文件夹不做自动匹配）。

## 13. W37 真机验收（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-03）

构建：`feature/w37-local-library`（起点 master `8fef16d`）；测试服务器只读（全程未写服务器）。测试素材 = 本机生成（Python：PDF 3 页 / 2 页、CBZ 3 页 PNG、WAV 正弦音）+ 1 个 788 KB 公开样例 mp4，推到 `/sdcard/W37Media/{Mixed,Extra}`（验收后已删除）。

1. **建立库（混合类型 + 2 个文件夹）**：通过。媒体库页常显「＋ 建立本地媒体库」→ 名称 `W37Mix` + 类型「混合」→ SAF 选择 `W37Media/Mixed` → 建库并扫描；详情页「＋ 添加文件夹」补 `W37Media/Extra`。库卡与标题显示「混合 · 2 个文件夹 · 视频 1 · 音乐 2 · 书籍 3」「共 6 项」。
2. **层级 / 平铺切换（每文件夹独立）**：通过。`Mixed` 切「平铺」→ 该文件夹条目按类型分组平铺；`Extra` 保持「层级」→ 子文件夹行 + 直属文件；两段设置互不影响（截屏与 UI 树核对）。
3. **本地视频 → 现有播放器**：通过。点 `sample.mp4` 进 `PlayerActivity`；`dumpsys media_session` 实测 `state=3`（PLAYING）position 2.8s → 10.0s（播到片尾），全程无 FATAL。
4. **本地音乐 → 音乐链路**：通过。点 `tone_a.wav` 起播（media_session `description=tone_a.wav`，position 走到 2.0s 结束）并自动切到音乐 Tab；曲库来源筛选「全部 / 服务器 / 本地」生效（本地 = 1 张专辑 1 首），曲目行显示「本地」来源徽标；`pref_music_source_filter` 持久化。
5. **本地书籍 → 现有阅读器**：通过。`book.pdf` 打开显示「离线可读 · 1 KB」「分页 · 1/3」，翻到 2/3 正常渲染；本地书籍进度只落本机（真机拦下「离线暂存 1 条进度」永远清不掉的缺陷 → `saveReadingProgress` 对 `pendingSync=false` 跳过服务器换算与回传，复验横幅消失）。
6. **库级「在媒体库显示」开关**：通过。关闭后库卡从媒体库总览消失、右上角「眼睛」进入管理视图可见「已隐藏」并可恢复；恢复后首页区块重新出现（首页开关此前已打开）。
7. **首页本地媒体开关（默认关）**：通过。默认关闭时首页无「本地媒体」区块；打开后首页出现「本地媒体」+ `W37Mix` 库卡，点击进详情；开关值实测 `pref_local_library_visible`。
8. **持久化与安全边界**：通过。①`am force-stop` 后重启 App，库 / 文件夹 / 索引仍在且可继续播放（SAF 持久权限）；②「删除媒体库」确认框明示「设备上的源文件不会被删除」，删除后媒体库页只剩「＋ 建立本地媒体库」，`find /sdcard/W37Media` 实测 6 个源文件全部保留；③移除文件夹同文案（只解除关联）。
9. **K60 抽验**：通过。装机后首页 / 底部导航正常，媒体库页（手机紧凑形态）显示「本地媒体库」区块 + 「＋ 建立本地媒体库」入口 + 首页开关；点入口弹出建库对话框（名称 / 类型 / 创建并选择文件夹），取消无副作用；0 FATAL / 0 ANR。

设备还原：Pad 5 删除测试库与 `pref_music_source_filter` 复位 `ALL`、`pref_local_library_visible=false`、删除 `/sdcard/W37Media` 与 `/sdcard/w37_ui*.xml`、App force-stop；K60 删除 `/sdcard/w37_k60.xml`、App force-stop；两机均未改旋转 / 网络 / 音量。

**W43 关联更新（2026-10-03）**：本节所属 W37 遗留 ③「本地条目并入搜索」已勾掉（`UI_PLAN` D44 / §5 W43 验收）；本地库模型、扫描与打开链路未变。

## 14. W45 本地封面 / 缩略图 + 首页本地媒体卡片化（2026-10-03，分支 `feature/w45-local-covers`，起点 master `d11f80d`）

需求来源：§2.2 第 8/9 条（元数据取「音乐内嵌标签 → 文件名/同目录封面 → 视频首帧/书籍首页」）+ §12 W37 遗留 ①；用户 2026-10-03 确认「懒生成 + `files/local_thumbs` 缓存 + 失败回退图标，不迁移 Room」。

### 14.1 决策

| 编号 | 决策 | 理由 / 后果 |
|------|------|-------------|
| D32 | **缩略图 = 派生产物，不进索引 / 不迁移 Room**：磁盘缓存 `files/local_thumbs/<itemId>.jpg`（JPEG ~80、最长边 ≤512px），失败标记 `files/local_thumbs/<itemId>.fail`（存在即不再重试），内存命中最长边与并发上限抽 `LocalThumbnailRules` 纯函数（data 层 9 项单测） | 扫描索引保持只读语义（W37 D25）；缓存可随时整目录删除重建；纯函数让「缓存路径 / 缩放尺寸 / 首项选取 / 回退」可 JVM 单测 |
| D33 | **分类提取口径**：视频 = `MediaMetadataRetriever` **第 1 秒首帧**（`getScaledFrameAtTime` 目标尺寸，失败退 `getFrameAtTime`）→ 失败退第 0 秒 → 类型图标；书籍 = PDF 首页（`PdfRenderer` 白底 + `Matrix` 降采样）/ CBZ 第一张图（`ZipInputStream` 顺序流，跳过 `__MACOSX` 与隐藏文件，单张 ≤32 MB）/ EPUB（Readium `Publication.cover()`，放 `modes:book` 以免把 Readium 类型带进 `app:phone` 编译面）；音乐 = 沿用 W37「内嵌标签封面 → 同目录封面」，不重做 | 首帧取 1s 避开黑场；PDF 必须铺白底（透明通道在深色主题下泛黑，与阅读器 `PdfPageSource` 同口径）；EPUB 走 Readium metadata cover = 用户口径 |
| D34 | **懒生成 + 并发 ≤2**：只在库卡 / 列表行 / 详情头部可见时请求；`app:phone` 侧 `LocalThumbnailProvider`（`@Singleton`）用 `Semaphore(2)` + 同条目 in-flight 合并 + `Dispatchers.IO`；库卡封面 = 「视频 → 书籍 → 音乐」顺序里第一个有缩略图的条目，**最多现场生成 3 张**（已有缓存的候选不消耗预算） | 列表滚动不阻塞主线程；100+ 项库不会一次刷满缩略图；首项规则与用户口径一致（`LocalThumbnailRules.coverCandidates` / `planCover` 单测锁死） |
| D35 | **UI 使用面**：首页「本地媒体」卡片化（封面 + 库名 +「N 项 · 类型」+ 类型角标，与「继续观看」横排**同宽同高**：`rememberLandscapeCardWidth()` + 16:9 + `CinefinShapes.Md` + `rememberGridGutter()` 间距）+ 媒体库总览库卡缩略图（40dp `corner-xs`）+ 本地条目列表行缩略图 + 本地库详情头部封面（140dp 通栏裁切）；无图统一回退既有类型图标 | 用户反馈「纯文字卡与首页不搭」；卡片语言与首页其它走廊一致（`LumenCardFrame` + 底部渐隐 + 中性角标），不新增配色 / 位图 |
| D36 | **空库不请求封面**：库卡 / 详情头部仅在 `itemCount > 0` 时发起封面请求，并按 `itemCount` 变化重试（LaunchedEffect 键含条目数） | 真机拦下的缺陷：新建库时库卡先以「0 项」出现，此刻请求会拿到 null 并永久缓存 → 表现为「库卡封面要进一次详情页才出现」（§14.3⑥） |

### 14.2 门禁（2026-10-03）

- 根 `assembleDebug`（含 TV）+ 根 `ktfmtCheck` 全绿；
- 单测逐个 `--rerun` 数 `build/test-results/*.xml`：app **79** / core **25** / data **42**（新增 `LocalThumbnailRulesTest` 9 项）/ player:local **104** / film **6** / book **106** / music **109** = **471 项 0 失败 0 错误**；
- 红线：未动 `settings.gradle.kts` / `libs.versions.toml` / `AndroidManifest.xml` / `AppPreferences.kt` / `NavigationRoot.kt` / `player:core` / `player:local`；`modes:book` 新增公开 `LocalEpubCover`（只暴露 `Bitmap`）。

### 14.3 真机验收（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-03 13:20–13:32）

测试素材 = 本机生成（600×800 深蓝 PDF / 3 页深绿 CBZ / 含深红封面的最小 EPUB 3 + 440Hz WAV）+ 1 个 991 KB 公开样例 mp4（Big Buck Bunny 360p 10s），推到 `/sdcard/Download/W45Media/{Mixed,Bulk}`（Bulk 120 件用于滚动观测）；测试服务器只读（全程未写服务器）。

1. **四条封面链路（像素取证）**：取回 `files/local_thumbs/*.jpg` 用 PIL 读数——PDF 首页 `384×512 mean=[30,59,89]`、CBZ 第一张图 `384×512 mean=[20,111,70]`、EPUB `384×512 mean=[140,30,29]`、视频首帧 `512×288 mean=[90,105,57]`，与素材页面底色一一对应；长边均 ≤512（JPEG）。已有 W37 库（MoonReader PDF）首页 `367×512 mean=[170,148,122]`。
2. **懒生成**：清空缓存后**只进首页**即生成 3 张库卡封面（三库各 1 张）；125 项库滚动一圈后缓存 65 张（仅可见行生成，非全量）。
3. **首页卡片化**：本地库卡 `content-desc=W45Mix [833,1945][1391,2259]` = 558×314px（16:9，与「继续观看」卡同宽同高同 pitch）+ 右上角类型角标 `混合` + 库名 + `125 项 · 混合`；卡片封面像素采样 `[66,79,57]`（叠底渐隐后的视频帧；回退态为 ≈`[26,31,39]` 中性底）→ 真图确认显示。
4. **性能（125 项混合库）**：首轮滚动 762 帧 / 51 janky（6.69%）/ p50 7ms / p90 17ms / p99 34ms（含现场生成）；缓存命中后第二轮 759 帧 / **4 janky（0.53%）** / p50 7ms / p90 9ms / p99 14ms。
5. **列表行 / 详情头部**：详情页视频行缩略图 `content-desc=视频 [90,1950][180,2040]`（40dp 方块）；详情头部通栏封面 140dp × 全宽。
6. **真机拦下并修复**：新建库「0 项」阶段请求封面 → null 永久缓存（缺陷与修法见 D36）；修复后清缓存重启、仅首页复验三库全部出图。
7. **K60 抽验**：装机 + 新建混合库（5 项）+ 库卡类型角标 + 详情行缩略图正常，0 FATAL / ANR。
8. **无封面回退**：音乐无内嵌 / 同目录封面时返回 null，库卡与列表行回退既有类型图标。

设备还原：双机删除测试库（源文件保留）、删除 `/sdcard/Download/W45Media|W45Fresh` 与 `/sdcard/w45_ui*.xml`、`run-as` 清空 `files/local_thumbs`、`am force-stop`；未改偏好 / 旋转 / 网络 / 音量（`device-lock.md`）。

### 14.4 W45 遗留

- ~~缩略图为**本 App 私有派生缓存**，不随库删除清理（同一个文件重新建库即命中）；如需「删除库即清缓存」另开小任务~~ → **W47 已修**（`LocalThumbnailRules.purgeThumbnails` + `deleteLibrary` / `removeFolder` 调用 + DAO `getLocalMediaItemsByFolder`，data 单测净增 1 项：`purge` 只删目标条目 `<itemId>.jpg|.fail`、不动其他文件）；
- 视频首帧固定取第 1 秒（黑场片源可能取到黑帧），未做「非黑帧搜索」；
- CBZ 顺序流取「归档顺序第一张图」，未做自然序重排（与阅读器 `orderComicPageNames` 的差异仅在归档顺序异常时可见）。

## 15. W47-B 下载补验（2026-10-03，Pad 5 `43af8627`，分支 `feature/w47b-b-verification`）

- **设备侧断网 ≠ FAILED（结论修正）**：超能力女儿 第 1 集（1.15 GB）下载中关 Wi-Fi + 飞行模式 2 分钟 —— DownloadManager 停在 `PAUSED_WAITING_FOR_NETWORK`，App 映射为 `PAUSED` + `NETWORK_UNAVAILABLE`（下载页仍计「进行中」、0 失败），文件停在 115,672,729 B；恢复网络后**系统自行续传**（116.8 → 186.7 MB / 40 s，无应用层日志）。
- **应用层自动重试仍未真机触发**：`DownloadTaskRules.isAutoRetryEligible` 只接受 `status == FAILED` +（网络 / 服务器原因），断网路径不产生 FAILED → `DownloadRetryWorker`（CONNECTED 约束）没有执行；补验需要服务器错误注入或传输层故障窗口（纯设备侧手段无法触发）。
- **空间不足失败列表**：**跳过（用户确认 2026-10-03）**，原因 = 用户明确不方便、可不测或用其他方法；不算失败。
- **reboot 续传**：✅ **通过（2026-10-03 16:03–16:10，用户配合窗口）** —— 重启前 201,328,281 B / 进行中（不暂停不删除）→ 重启 + 解锁后**系统自行续传**（16:06 494,962,329 B，+293.6 MB），30 s 采样持续增长至 16:10 完成，最终 **1,145,104,598 B**（`.download` 残片重命名）；下载页 `0 进行中 · 2 已完成 · 0 失败`、占用 1.15 GB 与文件一致；本地文件播放校验通过（PlayerActivity path/fd 指向 `files/downloads/beec8170-…`、`state=PLAYING(3)`）；测试下载经 App 删除流程清理。详见 `TEST_PLAN` §7.3/§7.5。
- 清理：测试下载经 App 删除流程移除（`0 进行中 / 1 已完成（既有书籍）/ 0 失败`），`files/downloads` 空。

## 16. W48 本地 SAF PDF 版式扫描修复（2026-10-03，分支 `fix/w48-saf-pdf-scan-memory`，起点 master `1ebdbcc`）

**背景**：本地媒体库（W37，D27）的书籍是 SAF `content://`，阅读器 PDF 走 `ParcelFileDescriptor` + PdfRenderer；
双栏「横版整页独占」需要全书页宽高比，W33 的 PdfBox 批量路径只接了本地缓存文件，本地 SAF 打开仍回退逐页
`PdfRenderer.openPage` → W47 金田一 5006 页（2.53 GB）Native 1.59 GB / PSS 2.45 GB 被 MIUI 杀进程（D-W47-1）。

**修复**（细节见 `READER_PLAN` §2 D24 / §7.16）：`PdfLayoutSource.forDescriptor()` 把 SAF fd `dup` 成独立 fd，
用 `Os.pread` 定位读（4 KB 页 + 256 页 LRU 缓存）交给 PdfBox `PDFParser` + `ScratchFile(8 MB 混合)`；不可
seek / 打不开 / 解析失败时按 `PER_PAGE_LAYOUT_SCAN_MAX_PAGES = 1500` 兜底——大书跳过逐页扫描用安全默认
（竖版两页一屏）并打 `reader spread layout skip-fallback`，小书仍逐页回退。**本地来源语义未变**：索引只读、
不拷贝源文件（D25），阅读器仍直接读用户文件夹（不复制到 `files/books`），只多了「版式元数据一次遍历」。

**真机（K60 `8e875894`，2.53 GB / 5006 页本地 SAF）**：扫描日志 `pages=5006 slots=4973 landscape=4938`
（与修复前逐页扫描一致）；Native 49.8–53.9 MB、PSS 358–368 MB（同机滚动基线 316.3 MB）、+60 s 不增长、
双栏→滚动→分页持平可回落；W22 测试书 `landscape=2 at=14,15`、RTL 相位对图 8/8、0 误拼。设备还原：测试库
删除 + `/sdcard/Download/W48Media`（2.53 GB 素材）删除、阅读器偏好回 `scroll` / `rtl=false`、App force-stop。
