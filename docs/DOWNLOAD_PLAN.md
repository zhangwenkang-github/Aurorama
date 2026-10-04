# Cinefin · 下载 / 离线任务线（DOWNLOAD_PLAN）

> **本文件是下载 / 离线线的唯一权威文档**：需求、决策、进度、验收、踩坑都写在这里，不新建零散 `.md`。
> 维护会话：W51B-SHOW-DOWNLOAD（分支 `feature/w51b-show-download-fix`，基线 master `9fcea57`；W51 粒度见 §20、
> W50 引擎见 §18、W52 界面见 §19；W32/W34/W36/W37 历史见 §4/§4.1）
> 最后更新：2026-10-03

## 1. 范围与现状

| 项 | 说明 |
|----|------|
| 下载引擎 | **W50 起 = 自研 OkHttp Range 引擎**（原系统 `DownloadManager` 已退役，见 §18；历史实现见 §4） |
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
- **W51b · Jellyfin 按需字段（`MediaSources` / `CanDownload`）不请求就全为 null**：`/Shows/{id}/Episodes` 只带 `Fields=Overview` 时，SDK 映射出空 `sources` / `canDownload=false`——拿它做批量过滤门槛会把整季剧集误杀成 0 条（真机现象 = Snackbar「没有可下载的剧集」）。取数方必须显式请求（`DetailDownloadRules.EPISODE_FETCH_FIELDS`），筛选门槛改用「有媒体源」这一硬条件。
- **W51b · 队列行先于条目快照落库会踩竞态**：`downloadItem` 先插 PENDING 行、后写快照（剧集要拉 show + season 两次网络）时，批量入队会让**已唤醒的引擎**在快照落库前抢到该行 → `findItem()` 读不到条目 → FILE_ERROR「任务对应的媒体条目缺失」（同机 12 集入队实测 11 集失败）。修法 = 快照写入前移到 `insertSource` 之前；**队列可见的行必须已经具备执行所需的最小数据**。

## 8. 日志

| 日期 | 会话 | 内容 |
|------|------|------|
| 2026-10-02 | W32-DOWNLOAD | 建线；实现管理 UI + 任务韧性 + 单测；门禁 `assembleDebug`（含 TV）/ `ktfmtCheck` / app 单测 58 项全绿；提交 `138b189`(feat) + `4053cb5`(docs) 已推送未合并；设备待调度，真机清单见 §6 |
| 2026-10-02 | W32-DOWNLOAD | **K60 真机验收（8e875894）**：断网续传 / force-stop 续传 / 批量暂停删除 / 完成与删除 / 存储占用 / 0 FATAL-ANR 通过；暂停路径实测不续传（D2 修正）、修复 sidecar 残留 `5341027`（复验通过）；未触发：FAILED 自动重试、空间不足、reboot；设备已还原；修复提交已推送未合并 master |
| 2026-10-03 | W34-DOWNLOAD | 层级化 + 封面：新建 `DownloadHierarchy`（纯函数 6 项单测）、视频层级（已完成剧集 DAO 反查 + `getDownloads` 补剧集）、音乐下载入口 + 侧车、书籍离线纳入列表、`ReaderRepository` 绑定上移 data、修 `getItem` 本地 source 合并；门禁四绿；Pad 5 真机 6 组通过（见 §9）；提交见交接 |
| 2026-10-03 | W36-OFFLINE | 离线闭环：`OfflineMediaRepository`（无账号可读）+ `sources.allowOffline` Room v10 + 书籍开关偏好 + 登录页 / 用户页 / 服务器页 / 欢迎页离线入口 + 登录成功自动退出 + 离线首页 / 媒体库 / 音乐 / 书架 IA 与空态 + W37 本地文件库占位；门禁全绿（app 70 / data 19 / film 6 / music 99 / book 106）；Pad 5 + K60 真机 8 组通过（见 §11） |
| 2026-10-03 | W36-OFFLINE | 补充要求（负责人转达）：层级图规则（节目 / 季海报、剧集缩略图 + 回退）+ 离线媒体库只显示节目 + 本地媒体库占位 / 开关 + 时长快照；真机修复 2 处（离线 VM 缓存刷新、退出离线后音乐曲库不切换）+ 离线仓库 `getDownloads` 补剧集层级；下载测试数据已删、双机已还原 |
| 2026-10-03 | W37-LOCAL-LIBRARY | 本地媒体库：Room v11 三表 + SAF 递归扫描（持久化权限 / 扩展名白名单 / 混合分组 / 每文件夹层级·平铺）+ 确定性 itemId + 合成 LOCAL 源（视频复用播放器）+ 阅读器 content:// 三路 + 音乐曲库来源筛选 / 混合同队列 / 来源徽标 + 媒体库与离线页常显入口 + 非破坏删除；顺手修 W36 两项遗留（下载刷新竞态 / 补拉图片）；门禁全绿（app 70 / core 16 / data 27 / film 6 / book 106 / music 99）；真机 Pad 5 + K60 抽验通过（见 §13） |
| 2026-10-03 | W50-DOWNLOAD-ENGINE | **自研下载引擎替换**（推翻 D1/D2）：OkHttp Range 真断点续传 + 暂停保留残片 + Wi-Fi/漫游策略 + 失败分类退避 + 速度/ETA 数据模型 + Room v12 六列只追加 + 旧进行中任务标记需重下 + DownloadManagerSupport/DownloadReceiver 退役 + WM 长时 worker 托管前台通知（进度/暂停/取消）+ 完成/失败通知 + 三个下载设置接引擎；门禁根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿，496 项单测 0 失败（W49 基线 486 + W50 净增 10）；**真机 8 项验收通过（Pad 5 主 + K60 抽验：暂停续传 / force-stop / 重启后打开续传 / 飞行模式自动恢复 / 通知 / 三设置 / 回归 / 迁移，0 FATAL；真机拦下并修复网络策略误判、唤醒延迟、恢复等待过长 3 处，见 §18.7）** |
| 2026-10-03 | W52-DOWNLOADS-REDESIGN | 下载页改版（§19）：大海报 96×144dp + 大条目 + 平板两列 + 容器聚合（已下载 x/y / 已用与总大小 / 速度 / 剩余时间，`DownloadAggregateRules` + `DownloadFormatRules` 纯函数）+ 旧组件重绘（Lumen 确认对话框 / 状态徽标 / LumenSkeleton 骨架）+ 多任务前台通知点击进下载页；门禁根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、**507 项单测 0 失败**（app 84 / core 37 / data 45 / film 14 / music 109 / book 113 / player:local 105；基线 496 + 11）；提交 `8bb81b7`(feat) + 归档（docs）已推送分支 `feature/w52-downloads-redesign`（rebase origin/master `2963799`）；**真机验收待调度（设备由 W53 占用）**，清单见 §19.5 |

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
- ~~视频首帧固定取第 1 秒（黑场片源可能取到黑帧），未做「非黑帧搜索」~~ → **W49 已修**（候选帧 1 s / 10% / 30% + 近黑帧跳过，全部黑回退第 1 秒帧，见 §17）；
- ~~CBZ 顺序流取「归档顺序第一张图」，未做自然序重排~~ → **W49 已修**（自然序第一张页图，比较器与阅读器共用同一实现，见 §17）。

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

## 17. W49 本地缩略图遗留清理（2026-10-03，分支 `fix/w49-leftover-cleanup`，起点 master `3dbca99`）

W45 遗留两条（§14.4）本波落地；**缓存与懒生成策略不变**（`files/local_thumbs` + `.fail` + 并发 ≤2 + 最多现场
生成 3 张，D32 / D34）。

1. **视频非黑帧选择（D33 口径修订）**：`LocalThumbnailRules.videoFrameTimesUs(durationMs)` 给出候选帧 =
   **1 s → 时长 10% → 30% → 0 s**（去重；时长未知 / 非法只留 1 s 与 0 s）；`isNearlyBlack(pixels)` 用
   32×32 采样位图的**平均相对亮度 ≤ 0.10** 判近黑（sRGB 权重 0.2126 / 0.7152 / 0.0722）。逐候选取帧
   （`getScaledFrameAtTime` → 退 `getFrameAtTime`），跳过近黑帧；**全部候选都黑时回退第一张成功取到的帧**
   （通常即第 1 秒），一张都取不到才回退类型图标；亮度测量失败按「不黑」处理（照常显示该帧）。
2. **CBZ 自然序第一页（D33 口径修订）**：自然序比较器（连续数字按数值、大小写不敏感、`a.jpg < a1.jpg`）
   **下沉 data 层**（`LocalThumbnailRules.compareComicPageNames` / `sortedComicPageNames`），阅读器
   `orderComicPageNames`（modes:book）改为复用同一实现，不再两处各写一份。SAF 拿不到随机访问 `ZipFile`，
   封面改**两遍顺序流**：第一遍只列页名（仍过滤目录 / 隐藏文件 / `__MACOSX` / ≥32 MB）求自然序首图，
   第二遍流到该条目再解码（只读目标条目数据、不解码其他图）。
3. **单测（data 43 → 45）**：候选时间点（10 分钟 / 10 秒去重 / 时长 null / 0 / 负数）、近黑阈值
   （`0x000000` / `0x141414` / `0x404040` / 纯白 + 可调阈值 + 空数组均值 0）、自然序（`p1 < p2 < p10`、
   大小写不敏感、`p01 == p1`、`!cover` 在前、空表）；book 侧 `ComicPageOrderTest` 改为断言排序结果
   （比较器与 data 共用）。
4. **门禁**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；7 个测试任务 `--rerun` **486 项 0 失败**
   （基线 477 + 新增 9：core 4 / data 2 / book 3）。
5. **真机（Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，2026-10-03 17:0x–17:1x）**：本地混合库（1 个文件夹，
   2 视频 + 2 CBZ + 1 PDF）——黑场片头 mp4（0–1.5 s 黑 → 橙）缩略图 = **橙色帧** 512×288 mean
   `[253.5,140.5,0.5]`（1 s / 10% 候选均黑 → 命中 30% 候选）；全黑视频**回退第 1 秒黑帧**（`.jpg` 存在、
   无 `.fail`）；归档序 `p10(蓝)/p2(红)/p1(绿)` 的 CBZ 缩略图 = 384×512 mean `[0,255,1]` = **自然序 p1**
   （不是归档首图 p10）。副作用还原：测试库 App 内删除 → 缩略图随库 purge（`files/local_thumbs` 只剩用户
   既有 4 张）、`/sdcard/Download/w49media` 删除、App force-stop；双机 0 FATAL / ANR。

## 18. W50 自研下载引擎替换（2026-10-03，分支 `feature/w50-download-engine`，起点 master `3dbca99`，已 rebase 至 W49 `1222bef`）

**背景**：用户 2026-10-03 决定不再使用系统下载工具（推翻 D1「只用 DownloadManager」）：真机已证实系统路径无法
「暂停保留残片」、失败原因不可控、重试/进度能力受限。本波只做**引擎 + 数据层 + 现有下载动作接线**，下载界面
改版与粒度入口留给 W51/W52。

### 18.1 决策

| 编号 | 决策 | 理由 / 后果 |
|------|------|-------------|
| D37 | **引擎 = 自研 OkHttp Range + Room 单一数据源**；前台服务由 **WorkManager 长时 worker `setForeground` 托管**（`DownloadEngineWorker`，`dataSync` 类型），不自建 Service | 官方长时任务推荐路径：Android 14+ 后台启动前台服务受限、Android 15+ dataSync 有 6h 配额；WM 自带设备重启恢复与网络约束，规避 `startForegroundService` 限制 |
| D38 | **暂停 = 取消协程 + 保留残片 + `PAUSED`**；恢复 = `Range: bytes=N-`，有校验器时带 `If-Range`（ETag/Last-Modified）；服务器回 200（忽略 Range / 校验变化）→ 截断残片**安全重下**；416 → 残片已覆盖完整内容按完成处理，否则 `CANNOT_RESUME` 安全重下 | 真断点续传；不再出现 W32「暂停即丢进度」 |
| D39 | **失败分类 + 任务级指数退避**：网络类无限重试（30s→60s→…封顶 30min）、服务器类 ≤5 次、残片失效 ≤3 次、空间/鉴权/用户取消不自动；退避由 `nextRetryAt` + `CONNECTED` 约束的下一次 worker 唤醒实现（不用 WM retry，避免双退避叠加） | 断网不空转、网络恢复即续；鉴权失败给「重新登录」文案 |
| D40 | **迁移只追加**：Room v11→v12 `AutoMigration`，`sources` 追加 `downloadedBytes / totalBytes / retryCount / resumeValidator / nextRetryAt / engineVersion`；旧「进行中」（engineVersion=0 且 downloadId 非空）首次启动标记 `FAILED + CANCELLED`（UI「已取消，需重新下载」），**已完成文件按 path 继续识别** | 不做无缝接管（旧系统任务不可查询/不可控）；不删旧列、不写新表 |
| D41 | **旧链路退役**：删除 `DownloadManagerSupport` / `DownloadReceiver`；新增 `DownloadActionReceiver` 承接前台通知的暂停/取消按钮；`downloadId` 列保留为**自研引擎句柄**（sourceId 稳定 hash，仅 UI 轮询兼容），engineVersion 区分新旧 | 保留读取旧 downloadId 的兼容路径；不再调用任何 DownloadManager API（仅保留状态常量映射给旧 UI） |
| D42 | **并发上限读偏好 `pref_download_concurrency`（1–3，默认 2，见 D44）**；通知 = 前台单条（当前任务进度 + 暂停/取消 + 「N 个任务」标题）+ 完成/失败单任务通知；速度（5s 滑动窗口）与 ETA 进 `DownloadTask`（W52 UI 数据源） | 通知与 UI 行为可用即可，粒度优化留 W52 |
| D43 | **凭据保持现状 + 更安全**：直链使用 SDK 生成的 `api_key` URL（与 DownloadManager 时代一致）；同主机请求额外带 `X-Emby-Token`，并用拦截器在跨主机重定向时剥离该头 | 不把令牌发给重定向目标 CDN；401/403 归类 AUTHENTICATION 提示重新登录 |
| D44 | **三个下载设置接引擎**（负责人 2026-10-03 追加，设置 UI 由 W51 补）：①**仅 Wi-Fi 下载**（默认开）复用既有键 `pref_downloads_mobile_data`（false = 仅计费网络拦截放宽，尊重系统 `NET_CAPABILITY_NOT_METERED`）——**未新增重复键**；②**同时下载数**新增 `pref_download_concurrency`（1–3，默认 2，`DownloadTaskRules.coerceConcurrency` 钳制）；③**下载完成通知**新增 `pref_download_complete_notification`（默认 true，关闭后仅保留进行中前台服务通知） | 只追加不重排 `AppPreferences.kt`（红线申报）；W51 UI 绑定「仅 Wi-Fi」时对该键取反即可，避免新旧两个开关互相打架 |

### 18.2 实现地图

| 文件 | 作用 |
|------|------|
| `core/utils/DownloaderImpl.kt` | 引擎门面：入队 / 队列调度（并发读偏好）/ 暂停恢复重试删除 / 对账 / 启动恢复 / 前台通知信息 |
| `core/utils/DownloadHttpEngine.kt` | OkHttp 传输：Range/If-Range/206 校验/416/200 安全重下/401 分类/重定向凭据剥离/64KB 缓冲 + 4s fsync |
| `core/utils/DownloadSpeedEstimator.kt` | 滑动窗口速度 + ETA 纯函数（`DownloadSpeedRules` / `DownloadSpeedMeter`） |
| `core/utils/DownloadTask.kt` | 任务模型（+速度/ETA/retryCount/nextRetryAt）+ 状态机 / 恢复策略 / 退避纯函数 |
| `core/utils/DownloadNotifications.kt` | 下载通知渠道、前台通知（进度/暂停/取消）、完成 / 失败通知 |
| `core/utils/DownloadActionReceiver.kt` | 通知按钮广播（@AndroidEntryPoint，不导出） |
| `core/work/DownloadEngineWorker.kt` | WM 长时 worker：setForeground + `runQueue()` + 兜底 retry |
| `data/.../FindroidSourceDto.kt` + `ServerDatabase.kt` + `ServerDatabaseDao.kt` | v12 六列只追加 + 进度/重试/校验器 DAO |
| `data/.../JellyfinRepository*.kt` | `getAccessToken()`（在线返回会话令牌，离线 null） |
| `app/phone/AndroidManifest.xml` | +`FOREGROUND_SERVICE_DATA_SYNC`、SystemForegroundService(dataSync) 覆盖、DownloadActionReceiver；−DownloadReceiver |
| `app/phone/.../MainActivity.kt` | Android 13+ `POST_NOTIFICATIONS` 请求 |
| `core/src/test/.../DownloadSpeedEstimatorTest.kt` / `DownloadContentRangeTest.kt` | 新增 8 项纯函数单测 |
| `app/phone/src/test/.../DownloadTaskRulesTest.kt` | 重写为 9 项（状态机 / 旧任务迁移 / 恢复 / 退避 / 偏移 / 并发钳制） |

### 18.3 门禁（2026-10-03）

- 根 `assembleDebug`（含 TV）全绿；根 `ktfmtCheck` 全绿；
- 单测逐个 `--rerun` 数 `build/test-results/*.xml`（rebase 到 W49 `1222bef` 后复跑）：core **37**（W50 新增 8）/ data **45**（W49 新增 2）/ film **6** / music **109** / book **113**（W49 新增 3）/ player:local **105** / app **81**（W50 净增 2）= **496 项 0 失败 0 错误**（W49 基线 486 + W50 净增 10）；
- 红线：未动 `settings.gradle.kts` / `libs.versions.toml` / `NavigationRoot.kt` / `player:core` / `player:local`；`AppPreferences.kt` **只追加** `pref_download_concurrency` / `pref_download_complete_notification` 两键（未重排，仅 Wi-Fi 复用既有 `pref_downloads_mobile_data`）；`AndroidManifest.xml` 改动已按任务书先行申报（新增 dataSync 权限与前台服务覆盖、通知操作 Receiver，移除 DownloadReceiver）。

### 18.4 迁移行为（旧数据）

1. **已完成（含 W32/W34 下载）**：`sources.path` 指向完整文件 → 下载页「已完成」/ 离线媒体库行为不变；
2. **旧进行中 / 暂停（DownloadManager 时代）**：首次启动（`BaseApplication.onCreate` → `recoverOnStartup()`）标记 `FAILED + CANCELLED`，下载页显示「已取消，需重新下载」，用户点重试即用新引擎重下（不做残片接管）；
3. **自研引擎中断任务**：`RUNNING/PENDING` 且 engineVersion=1 → 保留残片回 `PENDING` 续传；进程被杀 / 设备重启 / force-stop 重开都走这条；
4. `allowOffline` 等既有列在重下入队时保留用户设置。

### 18.5 真机验收清单（待负责人统一调度；Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

1. 下载中**暂停 → 残片保留**（`files/downloads/*.download` 字节不消失）→ 恢复 → 从残片偏移继续；
2. 下载中 **force-stop App** → 重开 → 恢复续传（不重下已有字节）；
3. 下载中**重启设备** → 解锁后自动恢复续传；
4. 开启飞行模式 → 任务显示「等待网络」（PENDING + 网络原因）→ 关飞行模式 → 自动续传；失败分类与退避日志正确；
5. 通知：前台常驻进度 + 暂停/取消按钮可用；完成/失败通知出现；Android 13+ 首次启动弹通知权限；
6. 三个设置项行为：仅 Wi-Fi 开关（默认开）打开时移动数据下不动、关闭后可下载；同时下载数 1 / 3 档生效（并发任务数变化）；完成通知开关关闭后无「下载完成」通知（前台服务通知仍在）；
7. 现有动作回归：下载 / 批量暂停 / 恢复 / 重试 / 删除 / 容器删除 / 存储占用 / 已完成识别 / 离线播放；
8. 全程 logcat 0 FATAL / 0 ANR；Room v11→v12 迁移后原服务器 / 账号 / 下载列表完整。

### 18.6 遗留

- 附属内容（外挂字幕流 / 分段 / Trickplay / 图片缓存）仍在主文件完成后执行：完成阶段进程被杀会缺失，下一次重下才补（与旧实现同级）；
- 前台通知的操作按钮对「当前任务」生效，多任务需进下载页操作（W52 UI 波改进）；
- 速度 / ETA 只在进程存活期有效（重启后归零，仍显示已下载字节）；
- Android 15+ dataSync 前台服务 6 小时配额（超长单文件极端情况会被系统停止，WM 重启后从残片继续）；
- 旧 `download_media.tsv` 侧车、书籍离线链路、`ImagesDownloaderWorker` 未动。

### 18.7 真机验收（2026-10-03，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

构建：`8f07ee8` + 真机修复提交；测试服务器只读；device-lock 登记与释放见
`E:\codex_work\Android_Studio_Work_Space\.planning\cinefin-expansion\device-lock.md`。

| # | 项 | 结果 |
|---|----|------|
| ① | 暂停 → 残片保留 → 续传 | ✅ Pad 5：177,417,957 B 暂停 → 残片保留 177,663,717 B（未删除）→ 继续 → 204,651,966 B 继续增长；UI「已暂停 · 178 MB / 1.15 GB」→「正在下载… 18% · 208 MB」 |
| ② | force-stop → 重开恢复 | ✅ Pad 5：233,221,530 → force-stop 冻结 233,328,044 → 重开后 236,117,107 → 283,319,393（从残片续传，无重下） |
| ③ | 重启设备 → 恢复 | ✅/⚠️ Pad 5：重启 + 解锁后 MIUI 未放行自启动（JobScheduler 无任务、进程未起）→ **打开 App 后从 344,742,347 B 残片继续到 359,671,945 B**；数据零丢失。MIUI 自启动限制见遗留 |
| ④ | 飞行模式 → 网络恢复 | ✅ Pad 5：失败（`NETWORK_UNAVAILABLE`、PENDING + 残片 310,533,199 B 冻结）→ 关飞行模式后自动唤醒续传（330,103,538 → 336,292,335，**未重开 App**） |
| ⑤ | 通知 | ✅ 前台常驻通知（id 4201、channel「下载」、进度 `55.52 MB / 1.27 GB · 4% · 7:33`、actions「暂停」「取消」在 dumpsys 注册）；完成通知默认开（id 8219「下载完成 · 我曾爱过一个人 (笛子版)」）；Android 13+ 首启权限弹窗实测（K60 选「始终允许」） |
| ⑥ | 三个设置 | ✅ 仅 Wi-Fi 默认开：双机在计费 Wi-Fi 上被拦截（日志「当前网络为计费网络且未允许移动数据」），关闭后（`pref_downloads_mobile_data=true`）立即开始并完成下载；并发=1：两条音乐严格串行；完成通知=关：两条完成均无通知 |
| ⑦ | 旧动作回归 | ✅ 容器删除（进行中视频容器删除 → 任务/残片/占用同步清除 470 MB → 91.46 MB）；多选删除（选择条 → 「选中的 N 项」确认 → 已完成 6→5、占用同步下降）；存储占用（UI 470 MB ↔ `du` 483,760 KB 量级一致）；离线媒体目录（离线模式「视频 0 · 音乐 3 · 书籍 2」，已下载音乐可读） |
| ⑧ | 迁移 + 稳定性 | ✅ Pad：v11→v12、user_version=12、六列已加、servers/users/local 库完整；注入旧引擎「进行中」行 → 启动后 `FAILED + CANCELLED`，UI「已取消，需重新下载」。K60：**真实旧任务**（downloadId=62）首启即标记「已取消，需重新下载」。双机抽查 0 FATAL / 0 ANR |

**真机拦下并修复（已回写代码，随本波提交）**：

1. **网络策略误判**：`activeNetwork` 能力读取失败时原实现直接拦截 → 改为「未知即放行」；漫游判定仅对蜂窝生效（Wi-Fi 不因缺 `NOT_ROAMING` 被拦）；拦截时打可读日志。
2. **用户动作唤醒延迟**：唯一工作链上已有「延迟退避」worker 时，新下载 / 继续 / 重试会被排在其后等待 → 改为无运行中 worker 时 `REPLACE` 立即唤醒；启动恢复同时清 `nextRetryAt`。
3. **网络恢复等待过长**：失败瞬间的连通性判断不可靠（Wi-Fi 拆除有延迟），叠加 30 min 退避会让恢复变慢 → 新增 `registerDefaultNetworkCallback`：网络可用即清网络类退避并强制唤醒；断网失败改为 0 延迟 + `CONNECTED` 约束等待。
4. **诊断增强**：自动重试路径打印异常栈；媒体源解析异常按 IOException / 其他分类（不再一律按网络错误）。

**真机新增遗留**：

- **MIUI 自启动限制**：重启设备后系统不允许 App 后台拉起（JobScheduler 无任务、进程不启动）→ 需用户打开 App 才续传；AOSP / 已授予自启动权限不受影响。如需彻底修复可加 `BOOT_COMPLETED` 接收器 + 引导用户开启自启动（本波评估后不引入，留 W51/W52 决定）。
- **MIUI 通知 action 点击**：前台通知的「暂停/取消」在 dumpsys 中确认注册（PendingIntent），但 MIUI 通知面板不把 action 暴露给 uiautomator，无法自动点按取证；同一引擎 API（`pauseTaskById` / `deleteTaskById`）已通过 App 内暂停 / 继续 / 删除路径验证。
- 双机各留有 25–47 KB 旧 DownloadManager `.js` 孤儿文件（历史遗留，非本波产生）。

## 19. W52 下载界面改版（2026-10-03，分支 `feature/w52-downloads-redesign`，起点 master `8f3ba0e`）

**背景**：W50 自研引擎已把速度 / ETA / 已下载字节 / 总大小写进 `DownloadTask`（§18），下载页仍是 W34 的 40dp 缩略图 + 文本行。
用户 2026-10-03 确认按「**大海报 + 大条目 + 容器聚合进度 + 速度 / 剩余时间**」重排，并重绘下载页内的旧 Findroid 组件。
本波只改下载页与下载流程组件，不动导航 / 设置 / 详情页动作排（W51）与视频入口（W53）。

### 19.1 排版规格（用户口径）

| 项 | 规格 |
|----|------|
| 手机（Compact）· 节目 / 电影 / 书籍容器 | 竖版海报 **96×144dp**（2:3，贴满行高）；卡片行高 = max(海报 144dp, 文本列 148dp) ≈ **148dp**（目标 144–150dp） |
| 手机 · 季容器 | 72×108dp 海报（第二级缩进 12dp） |
| 手机 · 剧集条目 | **112×63dp**（16:9）缩略图；曲目 56×56dp；书籍 48×72dp；电影子项 64×96dp |
| 专辑容器 | 方形封面 **96×96dp**（手机）/ 104×104dp（平板） |
| 平板（≥600dp） | 海报 104×156dp；**折叠的顶层容器两两并排（两列）**；展开容器连同子项整宽展示 |
| 条目信息 | 标题 / 季集号（`S1E2`）/ 大小 / 状态徽标 / 进度条；容器额外携带聚合信息 |
| 图片加载 | 沿用 Coil `AsyncImage` + W36 层级图规则（剧集缩略图 → 季海报 → 节目海报 → 类型图标）；占位 = 类型图标（加载中 / 失败露在图下层），不新增位图资源 |
| 列表性能 | `LazyColumn` 逐行 `key` + `contentType`（容器 / 季 / 条目 / 两列行四种类型），聚合与格式化全部走 `remember` / 纯函数（不逐帧重算） |

尺寸唯一落点 = `app/phone/.../presentation/film/downloads/DownloadRows.kt` 的 `DownloadListMetrics`；
平板成行规则 = 同目录 `DownloadGridGrouping`（纯函数）。

### 19.2 容器聚合口径

| 字段 | 口径 |
|------|------|
| 已下载 x/y | 容器内**已完成条目数** / 容器内条目总数（与 W34 聚合状态同源） |
| 已用 / 总大小 | `downloadedBytes` = 子条目已下载字节之和（已完成条目按文件体积计）；`totalBytes` = 子条目期待总大小之和（未知时回落到文件体积） |
| 速度 | **容器速度 = 子任务速度之和**（缺失 = 0 → 界面显示占位「—」） |
| 剩余时间 | **剩余字节 / 聚合速度**（向上取整）；剩余为 0 或无速度 = null → 界面显示占位「—」 |
| 进度条 | 优先按字节（`byteProgress`），总大小未知时回落到条目计数；轮询值 200ms linear 跟随（§6.3，不逐帧跳变） |

纯函数：`DownloadAggregateRules.of(entries)`（字节 / 速度 / ETA 聚合，`DownloadHierarchy.kt`）+
`DownloadFormatRules`（体积 / 速度 / 剩余时间 /「x/y」/ 百分比文案，`DownloadFormatting.kt`），均有 JVM 单测。

### 19.3 决策

| 编号 | 决策 | 理由 / 后果 |
|------|------|-------------|
| D45 | **手机大海报行 = 海报贴满行高（96×144dp），文本列 12dp 内边距；平板海报放大到 104×156dp** | 满足「海报宽 96–104dp、行高 144–150dp」，避免「海报 + 上下 12dp 留白把行高推到 168dp」的偏差；`DownloadListMetrics` 是尺寸唯一落点 |
| D46 | **平板两列只作用于「相邻且已折叠」的顶层容器**；展开容器连同子项整宽 | 层级从属关系（节目 → 季 → 剧集）不能被两列打断；`DownloadGridGrouping` 纯函数 + 3 项单测锁定规则 |
| D47 | **下载页旧组件在页面内重绘**：M3 `AlertDialog` → `DownloadConfirmDialog`（Lumen 面板 + 月白主行动）；文本状态行 → 状态徽标（12dp 前置图标 + LabelSmall）；加载圈 → `LumenSkeleton` 骨架；空态补类型图标 | 范围限定下载页；`CancelDownloadDialog` / `DownloaderCard` / `DownloadedBadge` 属于详情页动作排（W51）与浏览卡，本波不动 |
| D48 | **多任务前台通知点击进下载页**：`DownloadNotifications` 在 `activeCount > 1` 时给启动 Intent 加 `EXTRA_OPEN_DOWNLOADS`（`NEW_TASK + SINGLE_TOP`），`MainActivity` 在 `onCreate` / `onNewIntent` 消费后导航到 `DownloadsRoute` | 不新增通知按钮、不动 `NavigationRoot.kt`；单任务保持原行为（打开 App）；通知操作按钮仍只对当前任务生效 |

### 19.4 门禁（2026-10-03）

- 根 `assembleDebug`（含 TV）+ 根 `ktfmtCheck` 全绿；
- 单测逐个 `--rerun` 数 `build/test-results/*.xml`：app **84**（W52 净增 3）/ core **37** / data **45** / film **14**（W52 净增 8）/ music **109** / book **113** / player:local **105** = **507 项 0 失败 0 错误**（基线 496 + 11）；
- 红线：未动 `settings.gradle.kts` / `libs.versions.toml` / `AndroidManifest.xml` / `AppPreferences.kt` / `NavigationRoot.kt` / `player:core` / `player:local`。

### 19.5 真机验收清单（待负责人调度：Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

1. 手机（K60）容器海报像素采样 ≈ 96×144dp、行高 ≈ 148dp；剧集条目缩略图 16:9；
2. 平板（Pad 5）折叠容器两列并排；展开容器连同季 / 剧集整宽；gutter 与既有栅格一致；
3. 下载中容器聚合：已下载 x/y、已用 / 总大小随进度刷新；**速度 = 子任务之和、剩余时间 = 剩余 / 速度**（下载中实测；暂停 / 无速度显示「—」）；
4. 三组筛选（进行中 / 已完成 / 失败）+ 媒体筛选（全部 / 视频 / 音乐 / 书籍）+ 空态 / 骨架；
5. 旧组件重绘：删除确认对话框（Lumen 面板 / 月白主行动）、状态徽标、容器 / 条目行；展开折叠 / 暂停 / 恢复 / 重试 / 删除 / 离线开关 / 多选不回归；
6. 多任务前台通知点击进下载页（单任务 = 打开 App 不回归）；
7. 下载中条目动效 / 流畅度（进度条 200ms 跟随、列表滚动）；0 FATAL / ANR。

### 19.6 遗留

- **真机验收未执行**：设备由 W53 占用中（`device-lock` 2026-10-03 19:02 起），待负责人统一调度；
- 平板两列只覆盖「已折叠顶层容器」；展开容器整宽（如需「网格内展开」需要新交互设计）；
- 容器速度 = 子任务速度之和，不含排队任务的预估速度（排队任务速度为 0，显示「—」）。

## 20. W51 下载粒度 + 详情页动作排重绘（2026-10-03，分支 `feature/w51-download-granularity`，起点 master `ce58847`）

**背景**：W50 自研引擎与 W52 下载页改版之后，下载动作仍只落在单集（电影走旧入口）：节目 / 季没有下载入口；
详情页动作排是 W32 之前的图标行，「下载 / 已播放 / 喜欢」三个动作没有同排语义，也没有三态提示；侧栏 / 抽屉的下载入口没有活动任务数；
W50 已实现的三项下载设置没有 UI。本波按用户 2026-10-03 确认的口径一次补齐，**不改引擎、不新增偏好键**。

### 20.1 需求（用户口径）

| # | 需求 |
|---|------|
| A | **下载粒度**：节目（Show）= 下载整剧 / 季（Season）= 下载全季 / 集（Episode）= 下载本集；全部走 W50 自研引擎（不调用系统 DownloadManager） |
| A1 | 整剧 / 全季确认框**默认「仅补齐缺失集」**（已下载 / 已在队列的条目跳过），**默认单次上限 100 集** |
| B | 三层详情页**介绍上方**同排「下载 / 已播放 / 喜欢」三个动作一并重绘；后两者可点切换（既有 API，乐观更新 + 失败回滚） |
| C | 点击下载给 Snackbar 三态：**已加入下载队列 / 已在队列 / 已下载** |
| D | 侧栏（rail）+ 抽屉的「下载」项显示活动任务数（**下载中 + 排队 + 暂停**），0 隐藏 |
| E | 客户端设置 → 下载子页：**仅 Wi-Fi 下载（默认开）/ 同时下载数 1–3（默认 2）/ 下载完成通知（默认开）** |

### 20.2 决策

| 编号 | 决策 | 理由 / 后果 |
|------|------|-------------|
| D49 | **批量只提供「仅补齐缺失集」**（跳过已下载 / 已在队列）+ 默认上限 100 集（可关开关解除上限） | 不提供「覆盖式重下」：已完成条目直接重下会把 `sources` 行改写为新的 `.download` 残片，任务失败时该条目在离线索引里暂时消失（文件仍在盘上）。需要重下时先在下载页删除再补齐。上限可关 = 满足「默认 100 集」又不把 370 集大库卡死 |
| D50 | **状态判定 / 批量集选择 / 去重 / 上限抽纯函数** `DetailDownloadRules`（`stateOf` / `containerState` / `selectBatch`），落 `modes:film` + 6 项单测 | 门禁要求（纯 Kotlin、无 Android 依赖）；单集三态「已下载优先于已在队列」，容器聚合「缺失集优先于队列」 |
| D51 | **活动队列口径抽 `DownloadTaskRules.isActiveQueueStatus`**（PENDING / RUNNING / PAUSED）；新增 `Downloader.activeItemIds()` **只读 Room 快照**（不做对账 / 不唤醒引擎 / 不发网络） | 角标 2s 轮询不能复用 `refreshDownloadTasks()`（它对账 + 读侧车 + 可能唤醒引擎）；详情页点击时实时读一次，避免页面快照过期把「已在队列」误报成「已加入」 |
| D52 | **批量目标按需加载**：Show 点击下载时才按季拉剧集（`ShowViewModel.loadDownloadTargets`，结果缓存），Season 直接用页面已加载的 `state.episodes`；过滤 `canDownload && !missing` | 节目详情页打开成本不变（大库不预加载）；Season 页面本来就有剧集列表，零额外请求 |
| D53 | **详情动作排重绘 = 两行 + 三标签键**：第一行 播放 / 重播 / 预告（Icon 44dp）；第二行 **下载 / 已播放 / 喜欢**（Outlined 46dp + 选中态，Lumen 下自动切极光青）；下载键文案随三态变化（下载 / 已在队列 / 已下载）；Movie 保留旧的「已下载 → 删除确认」分支 | §8.1 详情页口径：播放 = Filled，下载 / 收藏 = Outlined；K60 360dp 下三键按内容宽排布不截断；Movie（不在本波范围）行为不回归 |
| D54 | **「已播放 / 喜欢」乐观更新 + 失败回滚**（Show / Season / Episode 三个 ViewModel） | 旧实现成功后整页 reload（慢且闪）；现在点击立即翻转 `played` / `favorite`，API 失败回滚到点击前的值 |
| D55 | **Snackbar = 新 core 组件 `CinefinSnackbarHost`**（§8.12：`InverseSurface` 底 / 反色文字 / 圆角 12 / 高 ≥52dp / 宽 ≤480dp / 无投影），详情页 `Box` 底部居中 | 全项目首个 Snackbar 落点；批量结果用扩展文案「已加入下载队列 · N 集」；三态与批量文案全部走 core 字符串（en / zh-rCN / zh-rTW） |
| D56 | **设置页复用既有键**：「仅 Wi-Fi 下载」对 `pref_downloads_mobile_data` **取反绑定**（`PreferenceSwitch` 新增 `negateValue`，默认开）；「同时下载数」新增 `PreferenceIntSelect`（1 / 2 / 3 单选，默认 2）；「下载完成通知」接 `pref_download_complete_notification` | 不新增重复键（W50 D44 口径）；`AppPreferences.kt` **零改动**（红线未动）；漫游开关沿用「允许移动数据时才可用」的依赖关系 |
| D57 | **角标渲染 = `CinefinNavItem.badge` 槽位 + `CinefinCountBadge`**（>99 收敛 `99+`；`OnSurface` 底 / `Surface` 字，中立不占媒体色）；落点 = **侧轨 + 抽屉**；`NavigationRoot` 前台 RESUMED 期间 2s 只读轮询 | 底栏（W53 后 = 首页 / 视频 / 音乐 / 书架）不含「下载」项，无底栏落点，已在验收清单注明；角标按 itemId 去重计数（同一媒体多来源任务的极端情况会少计） |

### 20.3 实现地图

| 文件 | 作用 |
|------|------|
| `modes/film/.../presentation/detail/DetailDownloadRules.kt` | 三态判定 / 容器聚合 / 批量集选择 + 上限（纯函数，6 项单测） |
| `modes/film/.../presentation/detail/DetailDownloadViewModel.kt` | 详情页共用：已下载 / 活动队列快照 + 单集 / 批量入队 + 结果事件 |
| `modes/film/.../presentation/detail/DetailDownloadSnapshot.kt` / `DetailDownloadEvent.kt` | 状态快照 / 事件模型 |
| `modes/film/.../presentation/downloads/DownloadBadgeViewModel.kt` | 侧栏角标计数（只读快照） |
| `modes/film/.../presentation/show/ShowViewModel.kt` + `ShowState/Action.kt` | 整剧目标按需加载 + 已播放 / 喜欢乐观更新 |
| `modes/film/.../presentation/season/SeasonViewModel.kt` / `episode/EpisodeViewModel.kt` | 已播放 / 喜欢乐观更新 |
| `core/.../utils/DownloadTask.kt` / `Downloader.kt` / `DownloaderImpl.kt` | `isActiveQueueStatus` + `activeItemIds()` 只读快照 |
| `core/.../core/presentation/components/CinefinSnackbar.kt` | Snackbar 组件（§8.12） |
| `core/.../core/presentation/components/CinefinNavigation.kt` / `CinefinDrawer.kt` | `CinefinNavItem.badge` 槽位 + `CinefinCountBadge`（侧轨 / 底栏 / 抽屉三处共用槽位） |
| `app/phone/.../presentation/film/ShowScreen.kt` / `SeasonScreen.kt` / `EpisodeScreen.kt` | 三态下载动作接线 + 批量确认框 + Snackbar |
| `app/phone/.../presentation/film/components/ItemButtonsBar.kt` | 动作排重绘（播放行 + 下载 / 已播放 / 喜欢三标签键；Movie 旧分支保留） |
| `app/phone/.../presentation/film/components/BatchDownloadDialog.kt` | 整剧 / 全季确认框（仅补齐缺失集 + 上限开关 + 动态计数） |
| `app/phone/.../presentation/film/components/DetailDownloadMessages.kt` | 事件 → Snackbar 文案 |
| `app/phone/.../presentation/film/components/CancelDownloadDialog.kt` / `DeleteDownloadDialog.kt` | 旧 M3 AlertDialog → 下载页同款 Lumen 面板 |
| `app/phone/.../NavigationRoot.kt` | 角标接线 + 前台 2s 只读轮询（**红线，已申报**） |
| `settings/.../presentation/models/PreferenceSwitch.kt` / `PreferenceIntSelect.kt` | 取反绑定标志 / Int 单选项模型 |
| `settings/.../presentation/settings/SettingsViewModel.kt` | 下载子页三行接线（仅 Wi-Fi / 并发 / 完成通知） |
| `app/phone/.../presentation/settings/components/SettingsIntSelectCard.kt` | Int 单选项卡片（复用通用选项对话框） |

### 20.4 门禁（2026-10-03）

- 根 `assembleDebug`（含 TV）+ 根 `ktfmtCheck` 全绿；
- 单测逐个 `--rerun` 数 `build/test-results/*.xml`：app **106**（W51 净增 1）/ core **37** / data **45** / player:local **105** / film **20**（W51 净增 6）/ book **113** / music **112** = **538 项 0 失败 0 错误**（W56 基线 531 + W51 净增 7）；
- 红线：**仅动 `NavigationRoot.kt`**（角标接线，已申报）；**未动** `settings.gradle.kts` / `libs.versions.toml` / `AndroidManifest.xml` / `AppPreferences.kt` / `player:core` / `player:local`。

### 20.5 真机验收清单（待负责人统一调度；Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

1. Show 详情 →「下载」：确认框默认「仅补齐缺失集」+「单次上限 100 集」开关；确认后 Snackbar「已加入下载队列 · N 集」，下载页出现对应剧集；
2. Show / Season 再次点「下载」（全部已在库 / 队列）：Snackbar「已在队列」/「已下载」，**不重复入队**（下载页条目数不增加）；
3. Season 详情 → 下载全季同上；Episode 详情 → 下载本集：未下载 →「已加入下载队列」、队列中 →「已在队列」、已下载 →「已下载」；
4. 三动作排：下载 / 已播放 / 喜欢同排；已播放 / 喜欢点击立即高亮（乐观更新），断网 / 失败后回滚；
5. 侧轨 + 抽屉下载角标：0 隐藏；排队 / 下载中 / 暂停计数；完成 / 删除后计数下降（底栏无「下载」项，属 W53 后的 IA 口径）；
6. 设置 → 下载与缓存：仅 Wi-Fi 默认开（关闭后移动数据可下载）、并发 1 / 3 生效、完成通知关闭后无完成通知；
7. 回归：电影详情动作排 / 下载页 / 离线媒体库 / 封面缓存不回归；全程 0 FATAL / 0 ANR。

### 20.6 遗留

- **真机验收未执行**（需负责人设备窗口）；
- 单次上限固定 100 集（可关闭上限，但不可改数值）；
- 批量下载落默认存储（`storageIndex = 0`），不提供 SD 卡选择；单集仍保留存储选择对话框；
- Episode 详情页不再提供「删除下载」入口（三态按钮点击只提示），删除统一走下载页（W52 已支持单条 / 容器删除）；
- 角标按 itemId 去重计数：同一媒体存在多来源任务的极端情况会少计。

### 20.7 W51b 真机缺陷修复（2026-10-03，分支 `feature/w51b-show-download-fix`，起点 master `9fcea57`，已 rebase 到 master `233b387`）

**背景**：W51 合并后（master `9fcea57`，559 单测）负责人 Pad 5 走查拦下——Show 详情页点「下载」提示 Snackbar
「**没有可下载的剧集**」；只读探针显示服务器 `/Shows/{id}/Episodes` 对「超能力女儿」返回 12 集、每集 1 个 MediaSource，
数据侧无缺失。本轮定位并修复**两条**缺陷（第二条为真机批量复验时新发现）。

#### 根因与修法

| # | 现象 | 根因 | 修法 |
|---|------|------|------|
| ① | 整剧 / 全季目标被筛成 0 条 → Snackbar「没有可下载的剧集」 | `ShowViewModel.loadDownloadTargets` / `SeasonViewModel.loadSeason` 取集只请求 `Fields=Overview`；Jellyfin 的 `MediaSources` / `CanDownload` 都是**按需字段**，未请求时 `BaseItemDto` 里为 null → SDK 映射出空 `sources` / `canDownload=false`，W51 的 `canDownload && !missing` 过滤把 12 集全部丢掉（Episode 单集走 `userLibraryApi.getItem` 默认字段，故正常） | 取集统一走 `DetailDownloadRules.EPISODE_FETCH_FIELDS`（Overview + CanDownload + MediaSources）；目标筛选抽 `downloadTargets()`（判据 = `!missing && sources.isNotEmpty()`，不再以会缺失的 `canDownload` 当门槛，权限失败交引擎 401/403 分类） |
| ② | 批量入队 12 集：`1 进行中 + 11 失败`（下载页「文件写入失败」，日志「任务对应的媒体条目缺失」） | `DownloaderImpl.downloadItem` 先插 PENDING 队列行、后写条目快照（剧集快照要拉 show + season 两次网络）；批量入队时引擎已被前一条唤醒，会在快照落库前抢到该行 → `findItem()` 读不到条目 → FILE_ERROR | 快照写入**前移到 `insertSource` 之前**——队列行一旦对引擎可见，条目快照必已落库；幂等命中 PENDING/RUNNING/PAUSED 时仍直接返回，不重复写 |

**只读探针（服务器 zhangwenkang，`GET /Shows/bf24fa94-…/Episodes`）**：

- `Fields=CanDownload,MediaSources,Overview` → 12 集，`CanDownload` 全部非空、`MediaSources.Count=1`；
- `Fields=Overview` → 12 集，`CanDownload` / `MediaSources` **全部为 null**（与根因 ① 完全吻合）。

#### 单测（+2）

- `批量目标_有媒体源即入选_不因CanDownload字段缺失被误杀`：`canDownload=false` + 有源 → 入选；无源 / 虚拟集 → 剔除；
- `取集字段_必须显式请求媒体源与下载权限`：锁定 `EPISODE_FETCH_FIELDS` 必须包含 `MEDIA_SOURCES` / `CAN_DOWNLOAD`。

#### 门禁（2026-10-03）

- 根 `assembleDebug`（含 TV）+ 根 `ktfmtCheck` 全绿；
- 7 任务逐个 `--rerun` 数 XML：起点基线 = **561 项 0 失败 0 错误**（W51 基线 559 + 净增 2；app 114 / core 37 / data 45 / player:local 105 / film 35 / book 113 / music 112）；
- **rebase 到 master `233b387`（并入 W53B / W54-D）后复跑**：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、7 任务 `--rerun` **577 项 0 失败 0 错误**（app 130 / core 37 / data 45 / player:local 105 / film 35 / book 113 / music 112）；
- 说明：`9fcea57 → 233b387` 对本波涉及的下载 / 详情页文件 **零 diff**（`git diff` 为空），故真机复验结论对 rebase 后的分支同样成立。

#### 真机复验（Pad 5 `43af8627`，23:12–23:32；device-lock 已写释放与结论）

| # | 项 | 结果 |
|---|----|------|
| ① | Season 全季 | ✅ 「下载全季」→ 确认框「将加入 11 集 / 已跳过：已下载 0 集 · 队列中 1 集 / 单次上限 100 集」→ 确认后 Snackbar「**已加入下载队列 · 11 集**」（截图取证），按钮转「已在队列」 |
| ② | Show 整剧 | ✅ 「下载整剧」→ 确认框「**将加入 12 集** / 单次上限 100 集」→ 确认后 Snackbar「**已加入下载队列 · 12 集**」（轮询取证）；原「没有可下载的剧集」不再出现 |
| ③ | 队列 / 角标 | ✅ 下载页 `12 进行中 · 0 失败`；侧轨角标「**12 个活动下载**」；单集下载回归 → 角标 1、`1 进行中 · 0 失败`、按钮「已在队列」 |
| ④ | 竞态修复旁证 | ✅ 修复前同机同操作 = `1 进行中 · 11 失败（文件写入失败）`；修复后 **0 失败** |
| ⑤ | 稳定性 | ✅ 0 FATAL / 0 ANR / 0 FILE_ERROR（crash buffer + main 过滤为空） |

备注：Pad 5 当前 Wi-Fi 被判为计费网络 → 默认「仅 Wi-Fi 下载」下任务停在「等待网络」（W50 既有策略，非缺陷；复验只覆盖入队与角标）；
测试下载 12 + 11 + 1 已全部删除，偏好 / `/sdcard` 临时文件 / App 状态已还原，服务器全程只读。

#### 遗留

- **进行中任务的容器分组**：下载页对进行中的整季批量仍显示为**逐集容器**（`getDownloadedEpisodeHierarchy` 只含已完成
  `path NOT LIKE '%.download'`），下载完成后才合并成「节目 → 季 → 剧集」；本次未改（属 W34 层级查询口径，需要新增「含进行中」的
  查询并按调用方分流以免影响离线可用性，留后续）。
- **批量入队会为每集重复拉 show / season 快照**（12 集 = 24 次 GET + 图片 worker，入队约 10 s）；可按 show/season 去重优化，留后续。

## 21. W57 下载体验（2026-10-04，分支 `feature/w57-download-ux`，起点 master `3518dca`）

**背景**：用户 2026-10-04 拍板下载体验 5 条——图片缓存默认 50 MB、并发改手动输入（1–8）、新增下载限速（0–100 MB/s，0 = 不限速）、「仅 Wi-Fi 下载」不看系统计费标记（修复家庭 Wi-Fi 被判计费时永远「等待网络」）、下载页缩略图任何网络下都显示。本波只动下载设置 / 网络策略 / 缩略图链路，不动下载粒度与下载页布局。

### 21.1 需求与决策

| # | 需求（用户口径） | 落地 |
|---|------------------|------|
| ① | `pref_image_cache_size` 默认 20 → **50**（MB） | `AppPreferences.kt` 默认值改 50（只影响未设置过的设备；本机已存值交负责人走查手改，**不做迁移**） |
| ② | 同时下载数改**手动输入**：1–8、默认 2、越界自动钳制 | `PreferenceIntSelect(1/2/3)` → `PreferenceIntInput(valueRange = 1..8)`；`DownloadTaskRules.MAX_CONCURRENT_TASKS` 3 → 8（引擎读值仍钳制） |
| ③ | 新增**下载限速**：0–100 MB/s、0 = 不限速、默认 0；引擎真实限速 | 新键 `pref_download_speed_limit_mbps`；`DownloadSpeedLimitRules`（钳制 / MB/s→B/s）+ `DownloadThrottle`（按**本次会话平均速率**节流）；`DownloadHttpEngine.download(speedLimitBytesPerSecond)` 在每读块后计算等待；**每次任务启动时读取偏好——运行中的任务不打断，新任务 / 重试立即生效**；媒体主体与外挂字幕流均受控 |
| ④ | 「仅 Wi-Fi 下载」**不看计费标记** | `DownloadNetworkRules.decide`（纯函数）：`hasTransport(WIFI/ETHERNET)` → 直接允许；其余网络（蜂窝等）仍看「允许移动数据」（默认 false = 等待）与漫游开关。**修复「家庭 Wi-Fi 被判计费 → 永远等待」** |
| ⑤ | 下载页缩略图**任何网络下都显示**（本地优先；无图用类型占位） | `persistItemSnapshot` 对**所有条目入队即** `startImagesDownloader(item)` 落盘自身封面（`filesDir/images/<itemId>/primary`）；`DownloadArtworkRules`（纯函数：本地优先 / 剧集 条目→季→节目→远程兜底）；音乐条目改本地优先；`ImagesDownloaderWorker` 对非法地址不再中断整个任务 |

### 21.2 缩略图根因（真机复现取证，Pad 5 `43af8627`，01:48–01:56）

- **现象**：等待网络中的「萤火之森」（电影）与「灼眼的夏娜 S1E2」（剧集）在下载页均只有类型图标占位；`files/images/` 为空。
- **根因两层**：
  1. 电影：`persistItemSnapshot` 的 Movie 分支不调度图片 worker，且 `videoImageUri(非剧集)` 只查本地 → 进行中/等待中永远无图（自身封面要等下载完成后的 `downloadExtras` 才落盘）；
  2. 剧集：入队时只落节目 / 季海报（`ImagesDownloaderWorker` 实测 SUCCESS ×2、两个目录落盘），但**条目自身（episode）封面未落盘**；且进行中剧集因无完成态层级归属被当电影容器渲染 → 仍无图。
- **旁证**：`ImagesDownloaderWorker` 链路本身正常（拉图落盘成功）；Coil 在线加载正常（详情页大海报可用）。

### 21.3 门禁（2026-10-04）

- 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；
- 8 任务逐个 `--rerun` 数 XML：app **136** / core **46** / data **45** / player:local **105** / film **40** / book **113** / music **114** = **599 项 / 0 失败 0 错误**（W56 基线 583 + 净增 16）；含新 `:player:core:testDebugUnitTest` 全量 **606 项 / 0 失败**；
- 新增单测 16 项：`DownloadNetworkRulesTest` 5（core）/ `DownloadSpeedLimitRulesTest` 4（core）/ `DownloadArtworkRulesTest` 5（film）/ `PreferenceIntInputTest` 2（app）；
- 红线：动 **`AppPreferences.kt`**（图片缓存默认 50 + 新键 `pref_download_speed_limit_mbps`，已申报）；`NavigationRoot.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local` 未动。

### 21.4 真机复验（Pad 5 `43af8627`，02:11–02:16；device-lock 已写释放与结论）

| # | 项 | 结果 |
|---|----|------|
| ① | 计费 Wi-Fi 立即下载 | ✅ 单集入队后立即开始（14.7 MB → 115 MB，1.2–2.3 MB/s），**不再「等待网络」** |
| ② | 下载页缩略图 | ✅ 入队数秒后条目显示剧集海报；`files/images` 落 episode/season/show 三目录；**飞行模式下仍显示**（本地优先） |
| ③ | 设置三项 | ✅ 并发输 99 → 收敛 8；限速输 500 → 收敛 100 MB/s；缓存默认显示 **50 MB**；已还原 2 / 0 |
| ④ | 断网恢复 | ✅ 飞行模式 → 关飞行后自动续传（115 → 119 MB） |
| ⑤ | 稳定性 / 还原 | ✅ 0 FATAL / 0 ANR；测试下载删除、`files/images` 随之清空、偏好还原、服务器只读 |

### 21.5 遗留

- 书籍条目封面本地落盘（`ReaderViewModel.downloadBook` 链路）未纳入本波：在线走服务器 URL 兜底、离线无本地图时用类型占位（Coil 磁盘缓存命中则仍可显示）；
- 进行中剧集在下载页仍按「电影容器」渲染（无完成态层级归属，W51b / W52 既有遗留：下载中容器分组）；
- 限速为「任务平均吞吐」语义（可小段突发），运行中任务不动态跟随新设置；
- `ImagesDownloaderWorker` 仍无失败重试（入队幂等跳过已落盘文件）。→ **W60 已补齐，见 §23**。

## 22. W59 下载页钻取式 IA + 书籍封面自动生成 + 层级图规则 + 专辑批量下载（2026-10-04，分支 `feature/w59-downloads-redesign`，起点 master `9c65010`）

**背景**：用户 2026-10-04 拍板 W59 四条——①下载页从「层级容器平铺」改为**钻取式 IA**（顶层只显示 Show 卡 / 音乐专辑卡 / 电影条目 / 书籍条目，Show / 专辑点击进详情）；②**在线书籍封面自动生成**（服务器图优先；没有图就生成，未下载的在线书籍也生成；HTTP Range 只读片段 + 懒生成 + 缓存 + 失败标记）；③**视频层级图严格同级**（Show / Season 只用自己的海报，Episode 只用缩略图，缺图类型占位、不跨级回退）；④**音乐专辑批量下载**（专辑列表长按多选 → 下载整张；专辑详情「下载专辑」按钮）+ 无图占位图。

### 22.1 需求与决策

| # | 需求（用户口径） | 落地 |
|---|------------------|------|
| ① | 顶层列表只显示顶层项（Show 卡 / 专辑卡 / 电影条目 / 书籍条目）+ 卡上聚合进度（「下载中 3/12 集 · 2.1 MB/s」） | `DownloadDrilldownRules.sortForDisplay`（进行中 → 失败 → 已完成）+ `DownloadTopLevelCard`（Show / 专辑，聚合 x/y + 体积 + 速度 + 剩余）+ `DownloadTopLevelRow`（电影 / 书籍平铺单条目行，左图固定比例 + 标题 + 状态行 + 操作键右对齐）；页签顺序改「进行中 → 失败 → 已完成」，类型筛选 / 多选保留 |
| ② | 点击 Show / 专辑 → 钻取详情页（上半 = 海报 + 信息 + 总进度 + 全部暂停 / 全部继续 / 删除；下半 = 季卡可展开剧集 / 曲目列表） | 同一路由内 `drilldownKey` 状态切换（系统返回先退详情）；`DownloadDrilldownHeader` + `detailRows(container, expandedSeasons)`；季卡 72×108dp、剧集行 112×63dp（16:9）；专辑详情 = 曲目平铺 |
| ③ | 进入详情自动展开第一个进行中的季并滚到可见 | `DownloadDrilldownRules.autoExpandSeasonKey`（进行中 → 第一个未完成 → 全完成不展开）+ `LaunchedEffect` + `animateScrollToItem` |
| ④ | 在线书籍封面自动生成（服务器图优先；未下载也生成；Range 只读片段；懒生成 + 缓存 + 失败标记；失败回退类型占位） | 新 core `BookCoverProvider` + `BookCoverRules.planCover`（服务器图 → 生成缓存 → 生成 → 占位）+ `HttpByteSource`（HTTP Range 分块 LRU，不整本下载）+ `ZipArchiveReader`（EPUB / CBZ 只读中央目录 + 目标条目）+ PdfBox-Android（`PdfBoxRandomAccess` 适配 Range 随机读，PDF 首页渲染）；缓存 `files/book_covers/<id>.jpg` + `files/book_covers/<id>.fail`；书架 `LibraryViewModel.requestBookCover` + `ItemPoster.placeholderIconRes`（`ic_book`） |
| ⑤ | 视频层级图严格同级（Show / Season 海报、Episode 缩略图，缺图类型占位，不跨级） | `DownloadArtworkRules.videoArtwork`（本级本地优先 / 本级远程兜底；季 / 节目图永不作为回退）+ ViewModel 每级只传自身图；容器构建器去掉 W36 跨级回退 |
| ⑥ | 音乐专辑列表长按多选 → 批量下载整张（复用 W58 core 框架与批量下载链路） | `MusicModeViewModel.onAlbumBatchLongPress/Toggle`（专辑 key 作用域，非多选态恒空）+ `onBatchSelectAll/None` 按作用域取专辑；底栏 `MusicBatchActionBar(albumsOnly)` 只给「下载整张」；`MusicAlbumDownloadRules.planAlbums`（按专辑序 + 音轨序、去重、单次 100 首上限）→ 既有 `enqueueSongs` 入队链路 |
| ⑦ | 专辑详情「下载专辑」（仅补齐缺失 + 100 首上限，与详情页整剧下载同口径） | `AlbumDetailHeader`（封面 + 专辑信息 + `下载专辑（N 首）` / 已全部下载置灰）+ `downloadAlbum()`；`MusicAlbumDownloadRules.plan` 跳过已下载 / 队列内 |
| ⑧ | 无图专辑 / 曲目用通用音乐占位图（现有音符矢量 + 媒体色底，不新增位图） | `ArtworkThumb` 常驻 `ic_music` + `media.container` 底（无图 / 加载中 / 加载失败都不再黑块空白） |
| ⑨ | 进行中剧集的显示归属（W52 遗留：下载中剧集被当电影平条 / 无法钻取） | 新 DAO / 仓库查询 `getEpisodeHierarchyWithSources()`（**存在 sources 即纳入**，与「已完成」查询并列），下载页与 `DownloaderImpl.refreshDownloadTasks` 改用该口径 |

### 22.2 门禁（2026-10-04）

- 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；
- 8 任务逐个 `--rerun` 数 XML：app **148** / core **69** / data **45** / player:local **110** / film **48** / book **113** / music **132** / player:core **12** = **677 项 / 0 失败 0 错误**（W58b 基线 655 + W59 新增 **22**）；
- 新增单测 22：`DownloadDrilldownRulesTest` 6（film）+ `DownloadArtworkRulesTest` +2（严格同级 / 不跨级回退）+ `BookCoverRulesTest` 6 + `ZipArchiveReaderTest` 4（core）+ `MusicAlbumDownloadRulesTest` 4（music）；
- 红线：未动 `NavigationRoot.kt`（钻取在同一路由内状态切换，未加路由）/ `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:core` / `player:local`；**申报非红线改动**：`core/build.gradle.kts`（+ `pdfbox-android`，与 modes:book 同版本）、`data`（`ServerDatabaseDao` 新查询 + `JellyfinRepository` 两实现）、`core`（新 `BookCover*` / `BookByteSource` / `ZipArchiveReader` + `DownloaderImpl` 层级口径）、`modes:film` / `modes:music` / `app:phone`。

### 22.3 真机验收（2026-10-04，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验；device-lock 已写释放与结论）

| # | 项 | 结果 |
|---|----|------|
| ① | 顶层聚合 + 页签顺序 | ✅ Show 卡「超能力女儿 正在下载… · 0/1 集 / 1.23 GB / 1.26 GB / 1.36 MB/s · 剩余 0:23」；专辑卡「已完成 · 1/1 首 + 体积」；书籍平铺（Anda's Game / attention_is_all_you_need / futuristic_tales / 虚构推理 / 雷普利全集）；页签 进行中 → 失败 → 已完成、类型筛选保留 |
| ② | Show 钻取 + 自动展开 + 操作键 | ✅ 详情头部（海报 + 状态 + x/y + 体积 + 速度剩余）+「全部暂停 / 全部继续 / 删除」；进入自动展开进行中的季并滚到可见（S1E2 行）；季卡展开 / 折叠；全部暂停（105 MB 停住、速度「—」）→ 全部继续（105 → 116 MB 续传） |
| ③ | 层级图严格同级 + 进行中归属 | ✅ 下载中剧集以 Show → 季 → 剧集分组（W52 遗留修复）；Show / 季 / 剧集各用自己的图 |
| ④ | 书籍封面生成（本地 + Range + 断网） | ✅ 本地已下载书籍生成 3 张 JPEG（395×512 / ≤512 / 缓存 `files/book_covers`）；删除本地 `.book` 后由书架触发 **HTTP Range** 重新生成（此刻 `files/books` 为空 → 证明未下载在线书籍路径）；飞行模式下已加载书架仍显示 3 张生成封面（含本地文件已删除的那本）；随后打开书籍重新下载恢复本地文件 |
| ⑤ | 专辑多选 + 下载整张 | ✅ 长按专辑 → 「已选 N 项」+ 全选 / ×、底栏仅「下载整张」；批量入队 3 张专辑 → 下载页专辑卡（正在等待… · 0/1 首 → 完成） |
| ⑥ | 专辑详情「下载专辑」 | ✅ `下载专辑（1 首）` → 点击后置灰「已全部下载」+ 曲目行「下载中」；已在队列的专辑直接「已全部下载」（跳过已下载 / 队列内） |
| ⑦ | 音乐占位图 | ✅ 飞行模式滚动专辑列表：无图 / 加载失败专辑显示「音符矢量 + 媒体色底」（非黑块） |
| ⑧ | 多选批量操作保留 + 清理 | ✅ 顶栏「选择」→ 选 4 个容器（Show + 3 专辑，已选 5 项）→ 批量删除确认 → 9 已完成 → 5 已完成、占用 3.03 GB → 662 MB；测试下载全清、`files/downloads` 移除、`files/images` 清空 |
| ⑨ | K60 抽验 | ✅ 抽屉「下载」入口、紧凑单列顶层列表（书籍平铺）、音乐专辑长按多选 +「下载整张」 |
| ⑩ | 稳定性 | ✅ 双机 0 FATAL / 0 ANR（crash buffer 干净） |

### 22.4 遗留

- 详情页「删除」按钮本次未单点验证（与批量删除同为 `deleteEntries` 路径）；
- 封面观感（PDF 首页 / CBZ 首图 / EPUB 封面）建议用户人工过目；失败即写 `.fail` 不再重试（换源 / 修复后需清缓存）；
- 平板两列成对规则沿用 W52（相邻折叠顶层项两两并排），大数据量下的视觉节奏未单独评估；
- Range 生成的封面在弱网下按失败标记一次性收敛，无自动重试（与「懒生成一次」口径一致）。

## 23. W60 下载失败重试补齐（2026-10-04，分支 `feature/w60-icons-retry-resume-bar`，起点 master `9c65010`；与 W59 并行）

**背景**：用户 2026-10-04 拍板「下载失败自动重试（历史遗留）」——先只读核对现状，再补齐应用层失败重试（网络类无限 / 服务器类限次退避）与 worker 失败重试，下载页显示重试状态（如「重试中 · 第 N 次」）。本波文件域与 W59（下载页重构）隔离：只动 `core`（utils / work / res 字符串）与必要的 `data` 进度字段。

### 22.1 现状核对（只读结论）

| 项 | 现状 | 结论 |
|----|------|------|
| 失败分类 | `DownloadHttpEngine`：401/403 → 鉴权；5xx / 其他 4xx → 服务器错误；416 / 起点不一致 → 残片失效；提前断流 / IOException → 网络类 | ✅ 完整 |
| 任务级退避 | `DownloadTaskRules.isAutoRetryEligible`：网络类无限、服务器类 5 次、残片失效 3 次；`backoffDelayMs` 30s 起指数、封顶 30 分钟 | ✅ 完整 |
| 自动重试落库 | `DownloaderImpl.handleTaskFailure`：可重试 → `PENDING + failureReason + retryCount + nextRetryAt`（离线不加长退避，交给 CONNECTED）；不可重试 → `FAILED` + 失败通知 | ✅ 完整 |
| 网络恢复唤醒 | `networkCallback` → `wakeNetworkBlockedTasks()`（清退避 + forceStart），进程死亡由 WM CONNECTED 延迟任务兜底 | ✅ 完整 |
| 任务重试数据 | `DownloadTask.retryCount` / `nextRetryAt` 已进模型与下载页数据（`buildTask` 透传） | ✅ 可供 UI |
| 图片缓存 worker | `ImagesDownloaderWorker` 吞掉图片失败、恒 `Result.success()`，无重试 | ❌ **缺口 → 本波补齐** |
| 下载页「重试中 · 第 N 次」 | 状态徽标对 PENDING 只显示「等待下载 / 等待网络」 | ⏸ 文件属 W59 / W60b（`DownloadRows.kt`），本波只出 core 字符串 + 数据 |

### 22.2 落地（本波改动）

| 文件 | 改动 |
|------|------|
| `core/utils/...` | 无改动（任务级退避已满足口径，避免动 W50 稳定链路） |
| `core/work/ImagesDownloaderWorker.kt` | 新增 `ImagesDownloadRetryRules`（`MAX_ATTEMPTS = 3`；IOException / 5xx / 408 / 429 为瞬时失败 → `Result.retry()`，WM 默认 30s 指数退避；404 / 403 / 非法地址为永久失败不重试）；图片先写 `name.part` 再 `renameTo`，避免写失败的残片被下一次当作已缓存跳过；非法 / 缺失 itemId 直接跳过 |
| `core/src/test/.../ImagesDownloadRetryRulesTest.kt` | 新增 4 项（限次重试 / 到上限停止 / 无失败不重试 / HTTP 状态分类） |
| `core/res`（默认 / zh-rCN / zh-rTW） | 新增 `download_retry_in_progress`（重试中 · 第 %1$d 次）供 W60b 接线 |

### 22.3 门禁（2026-10-04）

- 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；
- 8 任务逐个 `--rerun`：app **152** / core **63** / data **45** / player:local **110** / film **40** / book **113** / music **128** = **651 项 / 0 失败 0 错误**（基线 643 + 新增 8）；含 `:player:core` 全量 **663 项 / 0 失败**；
- 红线：`core`（utils / work / res）、`app/phone`（`LandscapeItemCard`，另属图标 / 进度条需求）、`data`（`FindroidItem` / `FindroidFolder` / `FindroidMovie` 进度字段，**未在原白名单内，已申报**）；`NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 与 W59 文件域未动。

### 22.4 真机验证（K60 `8e875894`，2026-10-04 05:58–06:21）

| # | 项 | 结果 |
|---|----|------|
| ① | 下载中开飞行模式 | 引擎失败并调度任务级重试：WM 延迟任务（CONNECTED 约束）当场注册，下载停止（残片保留 22.8 MB） |
| ② | 关飞行模式 | 自动续传：残片 22.8 MB → 54.9 MB → 421 MB（无需手动点重试） |
| ③ | 重试状态数据 | DB（sources 行）读出 `retryCount=1`、`failureReason=NULL`、`taskStatus=RUNNING`、`nextRetryAt=0`；全程无「失败」终态 |
| ④ | 复原 | 测试下载（被狙击的学园）经下载页删除 → 0 进行中 · 5 已完成 · 0 失败、`files/downloads` 为空；0 FATAL / 0 ANR |

### 22.5 遗留

- 下载页「重试中 · 第 N 次」文案接线（`DownloadRows.kt` 状态徽标）归 **W60b**：core 字符串与 `DownloadTask.retryCount` 已就绪；
- 图片缓存 worker 重试的设备端故障注入（时序难控，本波以 4 项单测覆盖策略）；
- 服务器类限次（5 次）与残片失效（3 次）的真实故障注入仍未做（既有遗留，需服务器 / 代理侧注入）。

## 24. W60b 下载状态徽标 + 反馈统一（2026-10-04，提交 `c1f85d2`，起点（A 段）`7702f38`；Pad 5 `43af8627` 主 + K60 `8e875894` 抽验）

### 24.1 需求与决策（用户 2026-10-04 拍板）

- **下载状态徽标（列表 + 详情海报）**：下载中 = **进度环** / 已下载 = **完成角标** / 暂停 = **双竖线** / 失败 = **红色叹号**；与未看数 / 已看打勾**错位排布**（右上被收藏书签 / 未看数 / 已看打勾占用 → 右下）；覆盖库网格 / 首页走廊 / 搜索结果 / 详情海报 / 我的收藏页。
- **反馈统一**：所有下载入口（详情页 / 整剧 / 全季 / 单集 / 电影 / 多选批量 / 音乐歌曲 / 专辑）→ Snackbar 三态（已加入队列 · N / 已在队列 / 已下载）+「**查看**」跳下载页 + 轻动画（角标淡入 / 进度环旋转 / 完成切换）。
- **下载页「重试中 · 第 N 次」**接线（core 字符串 + `DownloadTask.retryCount` 就绪，W60 遗留）。
- **音乐批量播放解析优化**（W58 遗留）：选中多曲逐首串行解析慢 → 4 首并发预取（保列表序入队，起播不被预取阻塞）。
- **搜索结果条目显示收藏 / 下载角标**（与列表一致）。

### 24.2 实现地图

| 位置 | 内容 |
|------|------|
| `app/phone .../film/components/DownloadStatusBadge.kt` | 新增：`DownloadBadgeState`（NONE / IN_PROGRESS / PAUSED / FAILED / DOWNLOADED）+ `DownloadBadgeInfo`（progress / indeterminate）+ 纯函数 `downloadBadgeInfo(downloaded, taskStatus, progress, totalKnown)`（优先级：已下载 > 下载中 / 排队 > 暂停 > 失败）+ `downloadBadgeCorner(hasTopEndBadge)` 错位 + 详情三态映射 + `CardBadgeOverlay`（右上收藏书签 / 未看数 / 已看打勾，下载徽标按错位规则落角）+ `DownloadStatusBadge`（进度环 Canvas + 1100ms 旋转 / 完成角标 / 双竖线 / 红叹号）+ 淡入淡出 |
| `app/phone .../presentation/downloads/DownloadStatusMonitor.kt` | 新增：`@Singleton` 监控（页面持有计数 acquire / release，1500ms 轮询 `Downloader.refreshDownloadTasks()` + `downloadedItemIds()` + 阅读器 `files/books` 离线书籍）+ 纯函数 `badgeMapFor(tasks, downloaded)`（同 itemId 取优先级最高）+ 页面级 `DownloadStatusViewModel` |
| 卡片 / 页面接线 | `ItemCard` / `PosterItemCard` / `LandscapeItemCard`（错位时上移 48dp）/ `LibraryListRow` / `DetailPoster` 接 `CardBadgeOverlay`；`LibraryScreen` / `VideoScreen` / `HomeScreen`（含 `HomeSection` / `HomeView`）/ `SearchBar` / `FavoritesScreen` / `MovieScreen` / `ShowScreen` 传角标快照 |
| `core/.../components/CinefinSnackbar.kt` | `CinefinSnackbarHost` 支持 `actionLabel`（动作文字 + `performAction()`） |
| 反馈入口 | `app/phone`：`MovieScreen`（`DownloaderEvent.Queued` → Snackbar + 查看）/ `ShowScreen` / `SeasonScreen` / `EpisodeScreen`（既有三态 Snackbar 加「查看」；`DetailDownloadMessages.showsViewAction`：失败不给动作）/ `LibraryScreen` / `VideoScreen`（批量）+ 既有 `MediaBatchEvent` 文案；`modes:music`：`MusicModeViewModel.downloadQueued`（默认 / 批量 / 专辑三路）+ `MusicModeScreen` Snackbar + 查看 |
| 重试文案 | `DownloadRows.showsRetryLabel(task)`（`retryCount > 0` 且 RUNNING / PENDING）→ 行内徽标显示「重试中 · 第 %d 次」 |
| 音乐预取 | `MusicModeViewModel.playSelected`：其余曲目 `chunked(4)` + `async` 并发解析（`resolveQueueItem` 保序），逐批按列表序入队 |

### 24.3 门禁（2026-10-04）

- 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；
- 8 任务逐个 `--rerun`：app **171** / core **73** / data **45** / player:local **110** / film **48** / book **113** / music **132** / player:core **12** = **704 项 / 0 失败 0 错误**（A 段 688 → B 段 +16）；
- 新增单测：`DownloadBadgeRulesTest` 6 / `DownloadStatusMonitorTest` 4 / `DownloadRetryLabelTest` 4 / `DetailDownloadMessageRulesTest` 2；
- 红线：`core`（Snackbar / Downloader 事件）、`app/phone`（徽标 / 卡片 / 页面 / 监控）、`modes:music`（反馈 + 预取）、`NavigationRoot.kt`（我的收藏白名单 /「查看」回调）；`AppPreferences.kt` / `AndroidManifest.xml` / `settings.gradle.kts` / `libs.versions.toml` / `player:*` 未动。

### 24.4 真机验收（2026-10-04 07:40–09:05，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验；device-lock 已写释放与结论）

| # | 项 | 结果 |
|---|----|------|
| ① | 下载中 = 进度环 | 库网格「被狙击的学园」卡片右上出现空心底 + 媒体色弧线进度环（下载中） |
| ② | 暂停 = 双竖线 | 下载页暂停任务 → 返回库网格，卡片角标切换为双竖线（深色圆角底 + 白色双杠） |
| ③ | 已下载 = 完成角标 | K60 书架已下载书籍（`attention_is_all_you_need`）卡片右上出现下载箭头完成角标（阅读器 `.book` 离线链路并入数据源） |
| ④ | Snackbar 三态 + 查看 | 电影详情「已加入下载队列」+ 查看 → 跳下载页；多选批量 2 部「已加入下载队列 · 2 项」+ 查看 → 2 进行中；音乐专辑「已加入下载队列 · 1 首」+ 查看 → 跳下载页 |
| ⑤ | 重试中 · 第 N 次 | 下载中关闭 Wi-Fi → 下载页行内显示「重试中 · 第 1 次」（retryCount 就绪）→ 恢复 Wi-Fi 后续传（2% · 337 MB） |
| ⑥ | 搜索角标 | 搜索结果卡片显示收藏书签角标（AURA；收藏 → 搜索命中一致） |
| ⑦ | 音乐批量预取 | 全选 124 首 → 播放：首曲立即起播 + 队列面板 124 首按列表序（约 8s 内补齐） |
| ⑧ | 0 FATAL / ANR | 双机 crash buffer + 主缓冲过滤为空；测试下载全部删除（0 进行中 · 5 已完成 · 662 MB）、收藏复原（0 项）、Wi-Fi / 飞行模式还原 |

### 24.5 遗留

- **失败徽标（红色叹号）**：真机未复现 FAILED 终态（网络类失败按设计进入重试）；需要服务器类故障注入（5 次限次）或代理侧在测；判定逻辑已由 `DownloadBadgeRulesTest` 单测覆盖；
- 搜索结果下载角标：设备上无「已下载视频」样本（搜索只覆盖视频库，已下载的是书籍）；代码与网格共用同一份 `CardBadgeOverlay`；
- 角标淡入动画未逐帧验证（进度环旋转可见）；音乐批量播放未做服务器压力上限（124 首为实测上限）。

## 25. W62 离线书籍清单口径（2026-10-04 · 分支 `fix/w62-regression-defects`）

W61 全量回归 F2（P3）：「离线书架『已下载 4 本』与下载页书籍清单不一致（书架多「虚构推理 (2026) 639.6 MB」、少 `futuristic_tales` 703 KB）」。本波复核 + 修复 = **两处口径都对齐**：

- **数据源**：两边本来就同源 = `ReaderRepository.listLocalFiles()`（`files/books` 下的 `.book` 文件），差异只在 ①过滤条件 ②显示名。
- **过滤口径（修）**：离线书架原按 `allowOffline` 过滤（被关闭的书从离线界面消失），而下载页不过滤。本机实测 `pref_offline_blocked_books` 里正是 `futuristic_tales`（`6bbbb0ce…`，W36 以来遗留状态）→ 书架少 1 本，且书籍**没有管理视图出口**（离线媒体库只列视频）无法回开。改为书架列出**全部已下载书籍**（关闭项行内 `OfflineLeafCard` 既有置灰 + 开关可回开）；`OfflineMediaViewModel.UiState.downloadedBooks` 同一口径，离线首页「已下载 N 本」同步。视频 / 音乐的「管理视图」语义不变（`OfflineMediaVisibility.visibleEntries`）。
- **显示名口径（修）**：下载页原「服务器元数据 → 「离线书籍 xxxxxxxx」占位」，离线时退化占位名 → 与书架（`.title` 侧车）不同名；统一为 `offlineBookDisplayName(serverName, sidecarTitle, itemId)` =「侧车优先 → 服务器名兜底 → 占位」（`core`，+3 单测）。
- **真机（离线模式）**：书架「离线模式 · 已下载 **5 本**」= 下载页「已完成 · 书籍」**5 本同名**（attention_is_all_you_need / futuristic_tales / 雷普利全集 / Anda's Game / 虚构推理 (2026)）；详见 `TEST_PLAN` §7.6.6 F2。

## 26. W63 下载域缺陷修复（2026-10-04，分支 `fix/w63-download-fixes`，起点 master `a8a580f`）

**背景**：用户发布前全检提交 7 条下载域问题（整剧 / 全季入队、季页 / 下载页长按多选、Snackbar 常驻、侧栏角标、下载页首屏慢）。本波按纪律**先真机复现 → 再定位修改 → 单测 → 真机复验**（Pad 5 `43af8627`，device-lock 已写释放与结论）。

### 26.1 复现与根因（Pad 5 真机取证 + 只读 API 探针）

| # | 现象（用户口径） | 真机复现 | 根因 | 修法 |
|---|------------------|----------|------|------|
| ① | Show / Season 页「下载」只入队 1 集 | 确认框数字本身**正确**（超能力女儿 12 集 / 只有神知道的世界 54 集，只读探针 12 集无重复、每集 1 个 MediaSource）；但**入队过程中离开详情页 → `JobCancellationException`，批量被截断**（DB 只落前几集）；单集入队每条都重新拉 show + 季（2 次网络、~2–3 s），54 集批量 = 分钟级 | `DetailDownloadViewModel.enqueue` 在页面 `viewModelScope` 内**串行**逐条 `downloadItem`；每条 `persistItemSnapshot` 重新解析节目 / 季快照 → 慢 + 页面退出即取消 | ① `Downloader.enqueueItems` 新增批量入口，`DownloaderImpl` 内**节目 / 季快照缓存（TTL 10 分钟）+ 首次并发拉取**（同剧第二集起近乎零网络）；② `DetailDownloadViewModel` 用 `withContext(NonCancellable)` 调批量入口（离开页面整批照常完成），事件通道改 `BUFFERED`；③ Snackbar 数 = 实际入队数（`DownloadBatchResult.addedIds`） |
| ② | Season 页集列表无长按多选 | 长按无反应（旧实现只有单击进集详情） | 未接 W58 core 多选框架 | 复用 `rememberMultiSelectState` / `cinefinSelectable` / `CinefinSelectIndicator` / `CinefinBatchBar` / `MediaBatchTopBarActions`：长按进入并选中、单击切换、已加载全选 / 取消全选、× / 系统返回退出，底栏「下载」（仅补齐缺失集，与整季下载同口径） |
| ③ | 下载页无长按多选（只能点右上「选择」） | 容器卡 / 条目长按无反应 | `CinefinCard` 只支持 `onClick`，下载页行组件未接长按 | `CinefinCard` 新增可选 `onLongClick`（`combinedClickable`）；`DownloadRows` 四个行组件（顶层卡 / 顶层行 / 季卡 / 集行）接 `onLongPress` → 复用既有 `ToggleSelection`（与「选择」按钮共用同一状态） |
| ④ | Snackbar 不自动消失（P1） | 书架 / 详情页「已加入下载队列 · 12 集」常驻 >20 s | Material3 `showSnackbar` 带 `actionLabel`（「查看」）时默认 `Indefinite`，调用方未显式传 `duration` | core 新增 `DownloadSnackbarDuration = SnackbarDuration.Long`（≈10 s）；全域 8 处下载反馈调用（Show / Season / Episode / Movie / Library / Video / Favorites / 音乐）显式传入 |
| ⑤ | 侧栏角标延迟大 + 数量错误 | 入队后角标要等首个快照网络 + 2 s 轮询才出现；活动快照里若残留「已落盘完成」条目会被计入 | ① 角标只在 `NavigationRoot` 2 s 轮询里刷新；② `activeItemIds()` 未剔除已下载条目 | ① `Downloader.queueChanges`（入队 / 完成 / 失败 / 删除时 `tryEmit`）+ `DownloadBadgeViewModel.init` 订阅 → 即时刷新（2 s 轮询保留兜底）；② 新纯函数 `DownloadBadgeRules.activeBadgeCount(activeItemIds, downloadedItemIds)` = 活动集去重 − 已下载集 |
| ⑥ | 下载页首屏加载慢（不应取服务器数据） | 34 个任务时页面 **>60 s 不渲染**（只有 0/0/0）；OkHttp 日志逐条串行 `GET /Items/{id}`（每任务 2–3 个远程图请求，逐一约 1.4 s） | `refresh()` 单阶段：本地任务与服务器元数据（`getDownloads` / 曲库 / 书籍名与封面 / 逐条远程兜底图）串成一条链，全部完成才 `_state.update` | 拆两阶段：**阶段 1 只用 Room / 文件**（活动 / 失败任务 + 本地书籍 + 存储）立即上屏；**阶段 2** 服务器元数据补齐（`mapBounded` 并发上限 4）后二次上屏；且**本地图存在时不再打远程兜底**（`remotePrimaryImageIfAllowed`） |
| ⑦ | W60b 下载反馈「基本没看到」 | 随 ④⑤⑥ 一并复验 | ④（常驻被忽略）/ ⑤（角标迟到）/ ⑥（页面无内容）叠加 | 见 ④⑤⑥ |

### 26.2 门禁（2026-10-04）

- 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿（含 format 后复跑）；
- 8 任务逐个 `--rerun`：app **172** / core **77** / data **50** / player:local **110** / film **53** / book **113** / music **132** / player:core **12** = **719 项 / 0 失败 0 错误**（基线 713 + 新增 **6**）；
- 新增单测：`DownloadBadgeRulesTest` 5（film：去重 / 剔除已下载 / 空集 / 混合）+ `DownloadSnackbarDurationTest` 1（core：显式 `Long` 且非 `Indefinite`）；
- 首屏渲染顺序不可单测 → 真机取证（见 26.3 ⑥）。

### 26.3 真机复验（Pad 5 `43af8627`，13:39–14:40；device-lock 已写释放与结论）

| # | 项 | 结果 |
|---|----|------|
| ① | Show 整剧 + 立即离开页面 | ✅ 对话框「将加入 **12 集**」→ 确认后 2 s 内按返回离开详情页 → 20 s 后 DB `sources` 校验 **12/12 集全部入队**（旧包同操作被 `JobCancellationException` 截断）；多季剧对话框 **54 集**（与探针一致） |
| ② | Season 全季 | ✅ 「将加入 **26 集**」→ 确认后 DB **26 行**（PENDING 25 + RUNNING 1），快照 26 集全落库 |
| ③ | Season 长按多选 | ✅ 长按第 1 集 →「已选 1 项」+ 顶栏 全选 / × + 底栏「下载」；单击第 2 集 →「已选 2 项」；全选 →「已选 12 项」（按钮变「取消全选」）；批量下载 2 集 → Snackbar「**已加入下载队列 · 2 集**」+「查看」；12 集全在队列时再点 →「所有剧集都已在队列」（跳过口径生效，无重复入队） |
| ④ | 下载页长按多选 | ✅ 长按 Show 容器卡 →「已选 **12 项**」（整容器）；另一部剧 →「已选 **26 项**」；底部批量条 = 暂停 / 继续 / 重试 / 删除；删除确认「确定删除选中的 12 项下载吗？」→ 删除后角标即时 14 → 2 |
| ⑤ | Snackbar 自动消失 | ✅「已加入下载队列 · 2 集」+「查看」：t≈3 s 可见、**t≈14 s 已消失**（两次复验一致；`duration = Long`） |
| ⑥ | 下载页首帧 | ✅ 冷启动 → 点侧栏「下载」，**2.9 s 内**已渲染活动任务卡（含 `uiautomator dump` 开销；修复前同队列 >60 s 仍空白）；本地任务先出，服务器元数据随后补齐（OkHttp 请求在首帧之后才出现） |
| ⑦ | 侧栏角标 | ✅ 入队后即时更新：单批 12 → 徽标 12；再批量 2 集 → **14**（12+2，数量正确）；删除 12 项 → **2**；删除剩余 2 项 → 0；含「已下载」时不重复计数 |
| ⑧ | 清理 / 稳定性 | ✅ 全部测试下载（12 + 2 + 26）经下载页多选删除清除：`0 进行中 · 完成态回 664 MB（含书籍 661 MB）`、`files/downloads` 为空；0 FATAL / 0 ANR；App force-stop、`/sdcard/w63*.xml` 删除；未改设备偏好 / 旋转 / 网络 |

### 26.4 未覆盖项

- 失败徽标（红叹号）真机样本（需服务器类故障注入，既有遗留）；
- 音乐侧 Snackbar 时长的真机逐项复验（与视频侧同一常量，代码走查覆盖）；
- K60 抽验（本波任务书 = Pad 5 主；K60 未接入，交叉抽验留负责人调度）；
- 首帧「毫秒级」基准对比（本轮用 `uiautomator` 轮询取证，未接 `gfxinfo`）。

### 26.5 遗留

- 整剧 / 全季入队仍以「快照缓存 + 非取消」保证完整，若单条入队真失败（存储不可用等）Snackbar 只报最后一次失败文案，无逐条错误清单；
- 阶段 2 服务器元数据补齐仍按条目逐条请求远程图（本地图缺失时），并发已限 4；后续可考虑持久化远程图 URL。
