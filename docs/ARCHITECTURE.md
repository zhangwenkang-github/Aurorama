# Cinefin 架构设计（ARCHITECTURE）

| 项 | 值 |
|----|----|
| 文档版本 | v1.0（2026-09-29） |
| 状态 | 已定稿，待项目负责人审查 |
| 作者会话 | S2 · 架构与规范 |
| 上游依据 | `docs/REQUIREMENTS.md` v1.0；`docs/design/s1-decision.md`；S2 代码走查与联网调查（见 §9） |
| 适用对象 | 阅读器线（R1）、音乐线（R2）、UI 落地线（R3）、测试线（R4）、计划线（S3） |
| 维护规则 | 架构变更必须先改本文并通知相关任务线；实现细节写各自任务线文档，不另建零散 md |

> 本文只定义**目标架构、模块边界、接口形态与并行约束**，不写具体实现代码。文中所有"待验证"项必须在对应任务线实现时用真机 / 服务器实测确认，并把结论回写本文或任务线文档。

---

## 1. 现状基线（as-is）

### 1.1 模块与依赖（`settings.gradle.kts` 实测）

当前 9 个模块：`:app:phone`、`:app:tv`、`:core`、`:data`、`:player:core`、`:player:local`、`:setup`、`:modes:film`、`:settings`。

| 模块 | 职责 | 关键依赖 |
|------|------|---------|
| `app/phone` | 应用入口：`NavigationRoot`（类型安全导航）、播放 Activity、前台播放服务、主题 | core、data、player:core、player:local、setup、modes:film、settings |
| `core` | 设计系统（`core/presentation/theme`）、通用工具、常量、下载器（`Downloader` / `DownloaderImpl`）、Worker（Sync / Images / MpvCleanup）、DI | data、player:core、settings |
| `data` | Jellyfin SDK 封装（`JellyfinApi`）、`JellyfinRepository`（在线 / 离线两实现）、Room（`ServerDatabase` v8） | settings |
| `player/core` | 播放领域模型：`PlayerItem`、`Track`、`PlayerSubtitleSource`、`PlayerChapter`、`TrickplayInfo` | 无（纯模型） |
| `player/local` | 播放内核：`PlayerHolder`（单例，ExoPlayer/mpv 双内核）、`PlayerViewModel`、`PlaylistManager`、`TrackSelectionEngine`、`SubtitleParser` + `PlayerSubtitleController`、`MPVPlayer` | player:core、data、settings |
| `modes/film` | 影视模式业务：Home / Library / Movie / Show / Season / Episode / Collection / Favorites / Downloads 的 State+Action+ViewModel | core、data、settings |
| `settings` | `AppPreferences`（SharedPreferences 封装）、语言匹配（`LanguageMatcher`）、设置 UI 模型 | 无 |
| `app/tv` | TV 入口，**冻结**（仅保证可编译） | — |

### 1.2 现状关键事实（本会话实测）

1. **播放器是进程级单例**：`PlayerHolder`（`@Singleton`）按 `AppPreferences.playerBackend` 创建 ExoPlayer 或 `MPVPlayer`；`CinefinPlaybackService`（`MediaSessionService`）复用同一实例，通知栏 / 锁屏与播放页操作同一 Player。
2. **视频队列**：`PlaylistManager` 负责"整剧 / 整季 / 单片"的队列构建，逐集按需构建 `PlayerItem`（不预先全量拉流）。
3. **MVI 范式**：业务页统一 `Screen + State(data class) + Action(sealed) + ViewModel(@HiltViewModel)`，状态用 `MutableStateFlow` + `asStateFlow()`。
4. **数据层**：`JellyfinRepository` 接口 + `JellyfinRepositoryImpl`（在线）/ `JellyfinRepositoryOfflineImpl`（离线，Room 缓存），`JellyfinApi` 用 jellyfin-sdk 1.8.12 的 `*Api` 扩展（`playStateApi`、`itemsApi`、`userLibraryApi`、`sessionApi` 等）。
5. **设计系统当前散落两处**：`core/.../core/presentation/theme`（`Color`、`Spacings`）与 `app/phone/.../presentation/theme`（`Theme`、`Color`、`Typography`、`Shape`、`Spacing`、`Motion`）；`LocalSpacings` 由 `CinefinTheme` 提供。
6. **阅读入口现状**：`NavigationRoot.navigateToItem()` 对 `FindroidFolder(kind == "Book")` 直接跳 `ConsoleRoute("/details?id=…")`（服务器 WebView 阅读器）——**本期要替换成原生阅读器**。
7. **音乐现状**：无音乐模式与入口；音频只能被当作"视频"塞进视频播放器。音乐库类型（`CollectionType.Music`）已在 `supported` 列表内可浏览。
8. **CI 现状**：Build 仅 `master` push 触发 + PR 触发、忽略 `docs/**` 与 `**.md`、带 concurrency 取消；Format 跑 `./gradlew ktfmtCheck`（`**.kt` / `**.kts`）。仓库当前**没有任何单元测试**（未找到 test 目录）。

---

## 2. 目标架构总览（to-be）

### 2.1 分层原则

沿用 Android 官方分层建议（[Recommendations for Android architecture](https://developer.android.com/topic/architecture/recommendations)）：

- **UI 层**（Compose + ViewModel）：只做状态渲染与意图转发，通过 **UDF（单向数据流）** 把 Action 交给 ViewModel；
- **Domain / 业务层**：纯 Kotlin 规则（解析、配对、队列、进度换算），**必须可单测**；
- **Data 层**：Repository 作为唯一数据入口，持有 SDK / Room / 文件缓存；UI 不直接碰 SDK 与文件 IO；
- **单一数据源（SSOT）**：在线数据以服务器为准，离线数据以 Room 为准，二者由 Repository 统一对外；
- 新增逻辑优先放 Domain 纯函数（便于单测），Android 相关（Fragment、Media3、文件）只出现在 UI / Data / Player 层。

### 2.2 模块地图（新增 2 个模块）

```text
:app:phone
 ├─ :modes:film        （既有）影视模式
 ├─ :modes:book      （新增）阅读模式：书架 / 阅读页 / 阅读设置
 ├─ :modes:music       （新增）音乐模式：曲库 / 正在播放 / 队列 / 歌词 / 离线
 ├─ :settings / :setup （既有）
 ├─ :player:local      （既有，扩展音频队列能力）
 ├─ :player:core       （既有，扩展音频 / 阅读领域模型）
 ├─ :data              （既有，扩展阅读进度 / 歌词 / 音乐 / 书籍下载接口）
 └─ :core              （既有，设计系统与下载器）
```

| 新模块 | 职责 | 允许依赖 | 明确禁止 |
|--------|------|---------|---------|
| `:modes:book` | 书架（Books 库）、书籍详情入口、EPUB/PDF/CBZ 阅读页、排版与主题设置、批注与进度 | `:core`、`:data`、`:settings`、Readium 三件套 + pdfium 适配器、Compose | 不依赖 `:player:*`（阅读不播放）；不直接调 SDK |
| `:modes:music` | 音乐库浏览、正在播放、队列面板、歌词面板、离线管理 | `:core`、`:data`、`:settings`、`:player:core`、`:player:local`、Compose | 不直接调 SDK；不自己 new ExoPlayer |

> **决策 1**：Readium 只在 `:modes:book` 引入，避免污染其他模块的依赖图（`app:phone` 不直接依赖 Readium）。
> **决策 2**：音乐复用 `:player:local` 的 `PlayerHolder` 单实例（不新建第二个 Player），满足 MU-8「全 App 单 MediaSession，音视频互斥」。

### 2.3 依赖规则

| 规则 | 说明 |
|------|------|
| `data` 不得依赖任何 `modes/*`、`app/*` | 数据层向下依赖，禁止反向 |
| `player/*` 不得依赖 `modes/*` | 播放内核不认识业务页 |
| `core` 不得依赖 `modes/*` | 设计系统与工具保持通用 |
| `modes/*` 之间不得互相依赖 | 阅读与音乐通过 data / player 解耦；需要共享的模型提升到 `core` 或 `player:core` |
| UI 不得直接持有 SDK / File / ExoPlayer | 一律经 Repository / `PlayerHolder` |

### 2.4 咽喉文件（多线共改，必须串行保护）

| 咽喉文件 | 被哪些任务线改动 | 保护措施（建议） |
|----------|-----------------|-----------------|
| `settings.gradle.kts` | R1（注册 `:modes:book`）、R2（注册 `:modes:music`）、R3 | **一次改完两个模块注册**（由首个落地会话一次性提交），之后各线不再动 |
| `gradle/libs.versions.toml` | R1（Readium / pdfium）、R2（可能新增库）、R3 | 版本目录改动集中提交；新增库必须先过许可审查（§7） |
| 根 `build.gradle.kts`（`allprojects.repositories`） | R1（**必须新增 JitPack 仓库**给 pdfium 适配器） | 与模块注册同批提交，避免多条线各改一次 |
| `app/phone/.../NavigationRoot.kt` | R1（书籍详情 / 阅读路由）、R2（音乐路由）、R3 | 路由注册分两次小提交，改前 rebase；同日改动需互相告知 |
| `settings/.../AppPreferences.kt` | R1（阅读偏好）、R2（音乐 / 歌词偏好）、R3（界面偏好） | 前置约定 key 前缀（`reader_*` / `music_*`），一次提交只加自己前缀的 key |
| 设计系统核心（`core/.../presentation/theme/*`、`app/phone/.../presentation/theme/*`） | R3 主改，R1/R2 只读 | **设计系统由 R3 串行独家改动**；R1/R2 只用 token，不新增 token。**Typography 归位（2026-09-30 负责人决策）**：设计系统核心统一归 `core`，`app:phone` 只保留入口包装，由 R3 在 W1 落地 |
| `app/phone/.../playback/CinefinPlaybackService.kt` | R2（通知行为） | 由 R2 单独改 |
| `player/local/.../presentation/PlayerHolder.kt` | R2（音频后端策略）、播放器线 | 改动前与播放器线确认；改后必须跑视频回归 |
| `core/.../utils/DownloaderImpl.kt` | R1（书籍下载）、R2（音乐下载）、下载线 | 由**一条线先做通用化**（见 §6.1），另一条线只加自己的分支 |

---

## 3. 阅读器架构（R1）

### 3.1 引擎选型结论：Readium Kotlin Toolkit 3.4.0

| 项 | 结论 | 来源 |
|----|------|------|
| 版本 | **3.4.0**（Maven Central 的 `latest` / `release` 均为 3.4.0） | <https://repo1.maven.org/maven2/org/readium/kotlin-toolkit/readium-shared/maven-metadata.xml> |
| 许可 | **BSD-3-Clause**（与 GPL-3.0 兼容） | <https://github.com/readium/kotlin-toolkit> |
| 版本对齐 | 3.4.0 要求 Kotlin 2.4.20 / compileSdk 37 / minSdk 24；本项目 Kotlin 2.4.20、compileSdk 37、minSdk 28 → **完全对齐** | README「Minimum Requirements」 |
| 传递依赖对齐 | `readium-navigator:3.4.0` 依赖 `androidx.media3:media3-*:1.11.0`、`androidx.appcompat:1.8.0`、`androidx.core:core-ktx:1.19.0`、`kotlinx-serialization-json:1.11.0`、`timber:5.0.1`；本项目为 Media3 **1.11.1**、appcompat 1.8.0、core 1.19.0、serialization 1.11.0、timber 5.0.1 → **无冲突**（Media3 补丁号向上兼容） | `readium-navigator-3.4.0.pom` |
| 需引入模块 | `readium-shared`、`readium-streamer`、`readium-navigator`（`readium-opds` / `readium-lcp` 本期不需要） | 同上 |
| PDF 适配器 | `readium-adapter-pdfium:3.4.0`，运行时拉取 **JitPack** 坐标 `com.github.marain87:PdfiumAndroid:1.9.8` 与 `com.github.marain87:AndroidPdfViewer:3.2.8` → **根 build 必须新增 `maven("https://jitpack.io")`** | `readium-adapter-pdfium-navigator-3.4.0.pom` |

能力矩阵（R1 排期与验收依据）：

| 能力 | EPUB 2/3（reflow） | EPUB（FXL） | PDF |
|------|:---:|:---:|:---:|
| 分页 / 滚动 / RTL | ✅ / ✅ / ✅ | ✅ / 👀 / ✅ | ✅ / ✅ / ✅ |
| 文本搜索 / 高亮（Decoration） | ✅ / ✅ | ✅ / ✅ | **👀 / 👀（未实现）** |
| TTS | ✅ | ✅ | **👀（未实现）** |

| 格式 | 状态 |
|------|:---:|
| EPUB 2 / EPUB 3 | ✅ / ✅ |
| PDF | ✅（依赖第三方引擎适配器） |
| CBZ | 🚧 部分实现 |
| CBR | ❓ 不支持 |
| Divina / RWPM | 🚧 / 🚧 |

来源：<https://github.com/readium/kotlin-toolkit#features>。**结论：EPUB 与 PDF 走 Readium；CBZ 按 §3.5 评估；CBR 本期不做。**

### 3.2 集成方式：View 体系 → Compose `AndroidView`

Readium 的 Visual Navigator 以 **Fragment** 形式提供（`EpubNavigatorFragment`、`PdfNavigatorFragment`），本项目 UI 是 Compose，因此：

```text
Compose ReaderScreen
 └─ AndroidView(factory = { FragmentContainerView(带 childFragmentManager) })
     └─ EpubNavigatorFragment / PdfNavigatorFragment
```

- 用 `FragmentContainerView` + `childFragmentManager` 承载 Readium Fragment；`AndroidView` 的 `onRelease` 中释放 `NavigatorFactory` 与 Fragment；
- 打开流程（官方 API）：`AssetRetriever` → `DefaultPublicationParser` → `PublicationOpener.open(asset)`（见 [Opening a publication](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/open-publication.md)）；
- 排版 / 主题设置直接映射 Readium 偏好模型（reflowable 支持）：`fontSize`、`fontFamily`、`lineHeight`、`letterSpacing`、`wordSpacing`、`pageMargins`、`paragraphIndent`、`paragraphSpacing`、`textAlign`、`columnCount`、`scroll`、`theme`、`textColor`、`backgroundColor`、`publisherStyles`、`verticalText` → 覆盖 EB-5 / EB-6 / EB-7；FXL 另支持 `spread`（双页）。来源：[Configuring the Navigator](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/navigator/preferences.md)。
- 阅读器主题（纸色 / 护眼 / 深色 / OLED）与主 App 主题**解耦**（EB-7），避免互相污染。

### 3.3 取书与落盘（离线优先）

```text
Books 库（CollectionType=books）
  → LibraryScreen（既有，includeTypes=[BaseItemKind.BOOK]）
  → BookDetailScreen（新增：封面、简介、继续阅读）
  → 点击阅读：ReaderRepository.ensureLocalFile(itemId)
       ├─ 本地已有 → 直接用
       └─ 否则 GET /Items/{itemId}/Download → 落盘
  → PublicationOpener.open(FileAsset(本地文件))
```

实测证据（2026-09-29，测试服务器 10.11.8）：

- `GET /Items?includeItemTypes=Book` 返回 1 本 EPUB（2.2 MB）；
- `GET /Items/{id}/Download` 支持 **Range 请求**：`206 Partial Content`、`Content-Type: application/epub+zip`、文件头 `PK`；
- 服务器 Books 目录结构遵循官方约定（`Books/`、`Audiobooks/`、`Comics/`）：<https://jellyfin.org/docs/general/server/media/books/>。

> **决策 3（先落盘再打开）**：EPUB / PDF / CBZ 一律先下载到应用私有目录，再用 `FileAsset` 打开。理由：① ZIP / PDF 需要随机访问，HTTP 分块读取路径长且易碎；② 离线阅读（EB-11）本来就要落盘；③ 与既有 `Downloader` / 离线体系复用。**流式打开（Readium `HttpClient` + `https` URL）列为"待验证"的后续优化，不作为首期方案。**

### 3.4 PDF 分页懒加载（硬约束，EB-3）

Readium 的 PDF 能力由第三方引擎适配器提供（[Supporting PDF documents](https://github.com/readium/kotlin-toolkit/blob/develop/docs/guides/pdf.md)）：本期选 **pdfium 适配器**，其 `PdfNavigatorFragment` 负责渲染；业务侧必须满足"内存峰值与文档页数无关"的验收红线。

| 策略 | 规定 |
|------|------|
| 页窗口 | 只保留「当前页 ± 1 页」的解码结果；滚动 / 翻页时按需渲染新页 |
| 位图上限 | 内存中同时最多 **3 张**页面位图（当前 ± 1），超出按 LRU 立即回收 |
| 降采样 | 按"屏幕宽 × 设备像素比"计算目标位图尺寸并做上限截断（例如长边 ≤ 2048 px），**禁止按 PDF 原始分辨率渲染** |
| 禁止项 | ① 禁止一次性把整本 PDF 解码 / 渲染成位图；② 禁止为每页建立永久缓存；③ 禁止在 `onDraw` 内同步解码 |
| 释放时机 | 页面离开窗口立即回收；阅读页 `onRelease` 释放 `PdfDocumentFragment` 与引擎文档对象 |
| 降级 | 单页渲染失败显示占位 + 重试按钮，不崩溃、不影响翻页 |
| 验收 | 用一本 ≥ 200 页 PDF 实测：连续翻 20 页后 `dumpsys meminfo` 增量应稳定在常数区间（不随页号增长） |

> **待验证**：pdfium 适配器经 JitPack 拉取 `com.github.marain87:*`，需确认 ① CI（GitHub Actions）能访问 jitpack.io（本机实测直连 200、1.4 s）；② 应用体积增量与 ABI 支持范围；③ 中文排版 PDF 的渲染效果。
> **备选方案（未选定）**：`androidx.pdf:pdf-viewer`（Google Maven，当前最新 `1.0.0-beta01`）可独立渲染 PDF 并与 Compose 配合；若 Readium 的 PDF 导航器在懒加载上不可控，则用 `androidx.pdf` 自建 PDF 阅读页（仅 PDF 走自研，EPUB 仍走 Readium）。该方案在 R1 启动时做 1 天 spike 后定夺。

### 3.5 CBZ / CBR（EB-4，P1）

- Readium 对 **CBZ 为 🚧 部分实现**、**CBR 明确不支持**（`❓ Not planned`）。R1 启动时先做**可行性验证**：用 `AssetRetriever` 打开一个 CBZ → 能否按页 / 双页翻页、RTL、缩放，且内存可控。
- 判定标准（全部满足才用 Readium）：① 单页渲染 + 预取 ≤ 3 页；② 双页 / RTL 开关可用；③ 连续 20 页 `dumpsys meminfo` 稳定。
- 不满足则**自研 CBZ 阅读器**：`ZipFile` 随机读条目（`BitmapFactory` + `inSampleSize` 降采样）+ Compose `HorizontalPager`（左右翻页）+ 预取下一页 + 位图 LRU；窗口化参数复用 §3.4。
- 服务器测试内容暂无 CBZ / CBR（用户后续补充）→ 该任务**等素材到位后开工**；R1 先用自造样例（不入库）做实现验证。
- CBR 本期不做（Readium 不支持；自研需引入解压库，许可与体积都不划算）。

### 3.6 进度同步与批注（EB-8、EB-9）

| 项 | 设计 |
|----|------|
| 读进度（服务器） | `GET /Users/{userId}/Items/{itemId}/UserData`（**10.11.8 实测 200**，返回 `PlaybackPositionTicks`、`PlayCount`、`IsFavorite`、`Played`） |
| 写进度（服务器） | `POST /UserItems/{itemId}/UserData`，body 用 `UpdateUserItemDataDto`：`PlaybackPositionTicks`、`PlayedPercentage`、`Played`、`LastPlayedDate`（字段来自 OpenAPI，见 §9） |
| 本地精确定位 | Room 存 **Readium `Locator` 的 JSON**（spine 索引 + progression + 文本上下文），退出重进可精确回到原位置 |
| 服务器值换算 | `PlaybackPositionTicks = progression × 章节总 ticks`（Jellyfin 1 tick = 100 ns，即 **1 ms = 10 000 ticks**）；`PlayedPercentage` = progression × 100 |
| 上报时机 | 打开恢复 → 每 30 s（阅读中）→ 页面 / 章节切换去抖 2 s → 退到后台 → 关闭阅读页 |
| 离线 | 进度先写 Room"待同步队列"（含 `updatedAt`），联网后由 WorkManager 回传（复用 `SyncWorker` 思路） |
| 多设备冲突 | 默认"最近时间戳优先"（比较本地 `updatedAt` 与服务器 `LastPlayedDate`），冲突时以新者覆盖并记日志 |
| 批注（书签 / 高亮 / 笔记） | 全部存本地 Room（`reader_annotations`）；服务端无标准批注 API，导出 / 跨端同步后置 |

> **待验证**：① 与 jellyfin-web 官方阅读器的进度字段是否完全一致（用 Web 读一段后对比 `UserData` 变化）；② `POST` 写入在 10.11.8 的完整字段行为（首次实现时验证）。

### 3.7 阅读器数据流

```text
LibraryScreen / BookDetail ──(intent)──▶ ReaderViewModel
                                          │ 1. ReaderRepository.ensureLocalFile(itemId) → Downloader / Room
                                          │ 2. ReaderRepository.getProgress(itemId)     → UserData / Room
                                          │ 3. 打开 Publication（Readium）
                                          ▼
                                ReaderState(loading / ready(publication, locator) / error)
                                          ▼
                                ReaderScreen → AndroidView(Readium Fragment)
                                          ▼
                                Locator 变化 → ReaderViewModel.onAction(OnProgressChanged)
                                          ▼
                                Room（本地精确位置） + 去抖上报 UserData
```

---

## 4. 音乐架构（R2）

### 4.1 播放与系统集成（MU-4、MU-7、MU-8）

| 项 | 决策 |
|----|------|
| 播放内核 | **音频强制 ExoPlayer**（Media3），不使用 mpv。理由：gapless、音频焦点、MediaSession 生态都在 Media3；mpv 仅保留给视频兜底。实现上由 `PlayerHolder` 增加"音频会话强制 ExoPlayer"的入口（不改视频默认行为） |
| 会话模型 | 复用**单例 `PlayerHolder.player`** + 单个 `MediaSession`（`CinefinPlaybackService`，`MediaSessionService`，`foregroundServiceType=mediaPlayback`），符合官方 [Background playback with a MediaSessionService](https://developer.android.com/media/media3/session/background-playback) 的要求 |
| 音视频互斥（MU-8） | 新增 `PlaybackCoordinator`（`player/local`，`@Singleton`）：`startMusic()` 前先停视频队列并上报 `Sessions/Playing/Stopped`；`startVideo()` 前先停音乐。两条路径都必须经它，禁止直接 `player.play()` |
| 后台行为 | 音乐：后台长驻（`onTaskRemoved` 正在播放时不收摊，沿用现有逻辑）；视频：维持现状（默认离开即停） |
| 通知 / 锁屏 / 耳机 | 复用 `CinefinMediaNotificationProvider`；耳机按键与音频焦点由 ExoPlayer `setHandleAudioBecomingNoisy` / `setAudioAttributes(..., true)` 提供（已具备） |
| 睡眠定时（P1） | `player/local` 内实现（延时暂停 + 会话元数据），与视频共用 |
| 音质（MU-4） | 直连优先 + 可配置转码码率（Wi-Fi / 蜂窝分别设置）→ 走 `AppPreferences` 新增 `music_*` 前缀键；转码地址复用 `mediaSource.transcodingPath` 机制 |

gapless 说明：Media3 词汇表定义 gapless 为"跳过曲目之间的静音间隔"（<https://developer.android.com/media/media3/exoplayer/glossary#gapless-playback>）；ExoPlayer 在 MP3/AAC 上按 Xing/Info 头与编码器延迟元数据做无缝衔接（[Media3 RELEASENOTES](https://github.com/androidx/media/blob/release/RELEASENOTES.md) 中 "MP3: Use gapless-aware durations from Xing/Info headers" 等条目）。

> **待验证**：同一专辑内不同采样率 / 编码的曲目切换是否存在可感知间隙；耳机 / 蓝牙设备上的实测表现。

### 4.2 队列模型（MU-3）

```kotlin
// player/core（纯领域模型，可单测）
data class MusicQueue(
    val items: List<PlayerItem>,   // 复用 PlayerItem（name/itemId/mediaSourceId/mediaSourceUri/thumbnailUri）
    val currentIndex: Int = 0,
    val repeatMode: RepeatMode = RepeatMode.OFF,   // OFF / ALL / ONE
    val shuffleEnabled: Boolean = false,
    val source: QueueSource,       // ALBUM / ARTIST / PLAYLIST / FAVORITES / SEARCH / MANUAL
    val sourceId: String? = null,
)
```

| 能力 | 设计 |
|------|------|
| 单队列 | 全局唯一队列；换播放来源 = "替换队列 + 定位到点播曲目" |
| 手动排序 | `move(from, to)` 纯函数，UI 用拖拽列表 |
| 下一首播放 | `insertNext(item)`（插到 `currentIndex + 1`，并立即刷新播放器队列） |
| 随机 / 循环 | 由 ExoPlayer 的 `shuffleModeEnabled` / `repeatMode` 承担；队列自身的"未随机顺序"始终保留 |
| 队列保存（MU-3） | Room 表 `music_queue`（含顺序与来源），重启恢复；"多队列"后置 |
| 队列与播放器同步 | `MusicPlaybackController` 负责把 `MusicQueue` 映射为 ExoPlayer 的 media items 与索引，禁止 UI 直接 `player.addMediaItem()` |

### 4.3 歌词子系统（MU-5，重点）

**数据来源优先级**：服务端 `/Audio/{itemId}/Lyrics` → 音频内嵌歌词 → 同目录外挂 `.lrc`。

> 服务端实测（2026-09-29，10.11.8）：`aLIEz` 返回 83 行，`Start`（ticks）升序；**40 组行共享完全相同的 `Start`**（原文 + 翻译成对，如 `28440000` 两行）；另有 3 行元数据（作词 / 作曲 / 编曲，`Start` 为 0、10⁷、2×10⁷）。

**解析层（纯 Kotlin，必须单测）**

| 组件 | 职责 |
|------|------|
| `LrcParser` | 解析 LRC 文本：`[mm:ss]`、`[mm:ss.xx]`、`[mm:ss.xxx]`、一行多时间戳 `[00:01.00][00:05.00]歌词`、`[offset:+/-ms]`、ID 标签（`[ti:]` / `[ar:]` / `[al:]` / `[by:]`）、空行与注释；输出 `List<LyricLine>(startMs, rawText, isMetadata)` |
| `LyricsPairer` | 双语配对：**相同 `Start` 成对**；时间戳差 ≤ **250 ms** 视为极近，也成对；产出 `LyricBlock(startMs, primary, secondary)` 与未配对的单语块 |
| `LineLanguageDetector` | **逐行**语言检测：① 含平假名 / 片假名 → 日文；② 仅汉字 → 中文（按字形特征分简 / 繁，并保留"混合行"标记）；③ 仅拉丁字母 → 英文 / 其他；④ 中日英混合行打 `MIXED` 标并保留原文 |
| `LyricsNormalizer` | 去元数据行、去空白、去重复行、时间戳对齐（不足毫秒补齐） |

**语言判定与显示规则（对应 MU-5 能力 1–4）**

1. 语言候选列表来自"逐行检测结果聚合"（至少：简体中文、繁体中文、英文、日文、原文）；
2. 默认显示**简体中文**：存在中文（简 / 繁）行时优先展示中文侧，否则回落原文；
3. 提供"原文 + 翻译"双语对照模式；
4. 语言切换只影响显示侧，不改变配对与时间轴。

**语言检测实现**：优先规则法（上述 ①②③，零依赖、可单测）；规则法无法判定的长拉丁行再交给 `com.github.pemistahl:lingua:1.2.2`（Apache-2.0，Maven Central 已确认可获取：<https://repo1.maven.org/maven2/com/github/pemistahl/lingua/maven-metadata.xml>）。

> **待验证**：① 规则法对中英混排行（"中文行夹英文单词"）的判定准确率；② 简 / 繁区分是否需要字形表或 `Locale` 提示；③ 是否真的需要引入 Lingua（规则法达标则不引入，减小体积）。

**渲染**：`LazyColumn` + 当前行高亮 + 平滑滚动（`animateScrollToItem`），双语对照用主 / 副两行排版。

### 4.4 离线缓存（MU-6）

- 复用 `Downloader`（`DownloadManager` + `WorkManager` + Room 记录），扩展"单曲 / 专辑 / 歌单"批量下载；
- 容量管理：设置页显示占用 + 一键清理（复用现有下载页样式）；
- 离线播放：播放源指向本地文件（`PlayerItem.mediaSourceUri` 为本地 `file://`），离线模式不出网；
- 自动缓存策略后置。

### 4.5 进度上报（MU-9）

复用既有 `SessionApi`：`postPlaybackStart` / `postPlaybackProgress` / `postPlaybackStop`（`JellyfinRepository` 已封装）；音乐上报间隔 10 s，暂停立即上报，切歌先 Stop 再 Start。

### 4.6 音乐数据流

```text
MusicLibraryScreen ─▶ MusicViewModel ─▶ JellyfinRepository.getItems(...)（专辑 / 艺术家 / 歌曲 / 歌单）
                                  │
                                  └─(点击播放)─▶ MusicPlaybackController.playAlbum(albumId)
                                                     ├─ 构建 MusicQueue
                                                     ├─ PlaybackCoordinator.startMusic()（停视频）
                                                     └─ PlayerHolder.player(ExoPlayer).setMediaItems(...)
NowPlayingScreen ─▶ LyricsViewModel ─▶ LyricsRepository.getLyrics(itemId) ─▶ LrcParser / LyricsPairer / Detector
```

---

## 5. 接口定义与数据流（新增接口草案）

> 以下签名是**接口契约**（R1 / R2 可微调参数，但不得改变职责划分）。落地顺序：先提交接口 + 假实现（可编译、可单测），再并行填实现。

### 5.1 `data` 层新增

```kotlin
// data/repository/ReaderRepository.kt（新增）
interface ReaderRepository {
    suspend fun ensureLocalFile(itemId: UUID): File          // 下载 / 命中缓存
    suspend fun deleteLocalFile(itemId: UUID)
    suspend fun getReadingProgress(itemId: UUID): ReadingProgress?              // UserData + 本地 Locator
    suspend fun saveReadingProgress(itemId: UUID, progress: ReadingProgress)    // 本地写 + 去抖上报
    suspend fun flushPendingProgress()                                          // 联网回传（WorkManager 调用）
}

data class ReadingProgress(
    val itemId: UUID,
    val locatorJson: String,   // Readium Locator
    val progression: Double,   // 0.0–1.0
    val positionTicks: Long,   // Jellyfin 用
    val updatedAt: Instant,
)

// data/repository/MusicRepository.kt（新增）
interface MusicRepository {
    suspend fun getAlbums(parentId: UUID?): List<FindroidItem>
    suspend fun getArtists(parentId: UUID?): List<FindroidItem>
    suspend fun getSongs(parentId: UUID?, albumId: UUID?): List<FindroidItem>
    suspend fun getPlaylists(): List<FindroidItem>
    suspend fun getPlaylistItems(playlistId: UUID): List<FindroidItem>
    suspend fun getLyrics(itemId: UUID): RawLyrics?      // 服务端 → 外挂 → 内嵌 的合并结果
    suspend fun downloadSong(itemId: UUID)
    suspend fun downloadAlbum(albumId: UUID)
    suspend fun downloadPlaylist(playlistId: UUID)
}
```

对 `JellyfinRepository` 的增量（保持既有接口不破坏）：

| 方法 | 用途 | 备注 |
|------|------|------|
| `getUserItemData(itemId)` | 阅读进度读取（`/Users/{userId}/Items/{itemId}/UserData`） | 10.11.8 实测可用 |
| `updateUserItemData(itemId, positionTicks, playedPercentage)` | 阅读进度写回（`POST /UserItems/{itemId}/UserData`） | 走用户数据白名单 |
| `getLyrics(itemId)` | `/Audio/{itemId}/Lyrics` | SDK 1.8.12 已生成 `LyricsApi.getLyrics`（实测类：`org/jellyfin/sdk/api/operations/LyricsApi`） |
| `getBookFileUrl(itemId)` | `/Items/{itemId}/Download` | 支持 Range |

### 5.2 `player` 层扩展

| 位置 | 新增 | 说明 |
|------|------|------|
| `player/core/domain/models` | `MusicQueue`、`RepeatMode`、`QueueSource` | 纯模型，可单测 |
| `player/local/domain` | `MusicPlaybackController` | 音乐队列 → ExoPlayer 的唯一入口 |
| `player/local/domain` | `PlaybackCoordinator` | 音视频互斥仲裁（MU-8） |
| `player/local/presentation` | `PlayerHolder.audioSession()`（暂名） | 音频会话强制 ExoPlayer 的入口，不改视频行为 |
| `modes/music/domain/lyrics`（建议位置） | `LrcParser`、`LyricsPairer`、`LineLanguageDetector`、`LyricsNormalizer` | 纯函数，随音乐业务演进；也可放 `player/core` |

### 5.3 能力复用对照

| 需求 | 复用 | 新增 |
|------|------|------|
| 书籍下载 | `Downloader` / `DownloaderImpl`（需扩 Book / Audio 分支） | 书籍 / 音频单文件下载分支 |
| 音乐播放 | `PlayerHolder`、`CinefinPlaybackService`、`CinefinMediaNotificationProvider` | `MusicPlaybackController`、`PlaybackCoordinator` |
| 阅读进度 | `JellyfinRepository` + Room + WorkManager | `ReaderRepository`、待同步队列 |
| 歌词 | `JellyfinApi`（SDK `LyricsApi`） | LRC 解析 / 配对 / 语言检测 / 缓存 |
| 离线 | `JellyfinRepositoryOfflineImpl`、Room | 音乐 / 书籍离线索引 |

---

### 5.4 连接层：自签证书信任（W31-CONN）

**背景**：Android 默认拒绝不受系统信任的证书，自签 HTTPS 的 Jellyfin 服务器此前直接连接失败。

**方案（TOFU，Trust On First Use）**：不关闭校验，只叠加「用户显式确认过的指纹」：

| 组件 | 位置 | 职责 |
|------|------|------|
| `CertificateFingerprint` / `TrustKey` | `data/.../network` | SHA-256 指纹归一化 / 格式化 / 匹配、`host:port` 信任键（纯函数，单测覆盖） |
| `CertificateTrustStore` | `data/.../network` | 「信任键 → 指纹」持久化；`SharedPreferencesCertificateTrustStore` 写入应用私有 `cinefin_trusted_certificates` |
| `ToFuTrustManager` + `TrackedSslSocketFactory` | 同上 | 系统校验失败时，仅当「当前连接地址」的指纹被信任才放行；OkHttp 5 建连前调用 `createSocket(raw, host, port, autoClose)`，包装工厂据此记录 host 供 trust manager 判定 |
| `ServerCertificateProbe` | 同上 | 只读 TLS 握手取出服务器证书指纹；探测用的一次性「接受任意证书」信任管理器不用于任何业务流量 |
| `CertificateTrustRequiredException` | 同上 | 首次连接 / 证书变化时携带指纹交给 UI 确认 |

- **接入点**：`JellyfinApi` 通过 `OkHttpFactory(自定义 OkHttpClient)` 注入 `apiClientFactory` / `socketConnectionFactory`（jellyfin-core 1.8.12 支持）；Coil 图片加载与 `ReaderRepositoryImpl` 书籍下载复用同一信任仓库。
- **UI**：添加服务器、登录页展示 `host:port` + 完整 SHA-256 指纹（证书变化时额外警告）；服务器页「已信任证书」弹窗可随时清除，清除后对应地址立即回到默认拒绝。
- **边界**：主机名（SAN/CN）校验保持 OkHttp 默认——自签证书必须把访问用的主机名 / IP 写进 SAN；未接入：WebView 控制台（`ConsoleViewModel`）与 `ImagesDownloaderWorker`。
- **多用户（W31 审计）**：Room `users` 表 + `userdata(userId,itemId)` 已按用户隔离，`UsersScreen` 列表 / 切换 / 添加 / 删除入口位于设置页与抽屉；W31 修复「删除当前用户留下悬空 `currentUserId`」（有剩余用户顺延、否则清空令牌），并在列表标出当前账号；管理员缓存本身按账号 id 校验，不跨账号串号。

#### 5.4.1 K60 真机联调（2026-10-02）

- **环境**：PC 上起自签 HTTPS 服务（局域网 `10.71.41.146:8444`，证书 SAN `IP:10.71.41.146`，SHA-256 `BE:C8:E7:82:19:DC:CA:FD:A8:3D:76:59:E1:65:A3:E1:1C:78:19:ED:0E:84:2E:07:51:A5:B4:21:C3:5B:31:D0`），K60 走局域网直连（`http=200` 预检通过）。
- **证书流程**：添加服务器 → 弹窗显示完整指纹（与 PC 一致）→ 信任并继续 → 服务器识别成功（`GET /System/Info/Public` 到达本地服务）；清除信任后**同进程**再连 → 立即 `SSLHandshakeException: Trust anchor ... not found` + 弹窗重现（无业务请求外发）；换第二张同 SAN 新证书 → 弹窗显示新指纹 + 「警告：证书已发生变化」+ 此前指纹；恢复原证书后旧信任仍可用（`POST /Users/AuthenticateByName` 到达）。
- **真机发现并修复**：TLS 客户端会话缓存会让「清除信任」后的下一个连接走会话复用、跳过 trust manager。`CertificateAwareOkHttp` 现将证书感知客户端的 `SSLSessionContext` 限制为 1 条 / 1 秒（`sessionCacheSize=1`、`sessionTimeout=1`），保证每次连接重新做完整校验；修复分支 `fix/w31-trust-session-reuse`（待负责人合并）。
- **多用户（K60 + 生产服务器）**：admin `zhangwenkang` 与第二个普通用户分别登录成功；Home「继续观看」仅 admin 出现（进度按用户隔离），`admin → test → admin` 来回切换正常；用户列表标出「当前」账号；删除当前用户（test）后 `servers.currentUserId` 顺延到 admin（设备 DB 实测）且无崩溃；错误密码显示「用户名或密码错误」；整轮 0 FATAL / ANR。
- **测试服务备注**：PC 侧最终用 Python/OpenSSL 起服务；JBR `com.sun.net.httpserver.HttpsServer` 与 Android Conscrypt 的 engine socket 在 IP 直连时握手卡住（测试服务自身互通问题，与客户端无关）。
- 遗留：WebView 控制台（`ConsoleViewModel`）与 `ImagesDownloaderWorker` 仍未接入信任；自签证书必须包含访问地址的 SAN。

---

### 5.5 元数据缓存与「缓存优先 + 静默刷新」（W69）

**背景**：用户 2026-10-04 复验反馈——刚进首页 / 从季（剧集）详情返回库界面时会触发元数据加载，加载期间**整面海报变黑 / 空白**（「元数据加载与缓存策略要优化，要优先使用本地已缓存的元数据，从服务器加载元数据时，要静默」）。

**规则**（`data/.../repository/MetadataCache.kt`，纯函数 + 常量集中定义，单测 `MetadataCacheTest` 12 项）：

| 规则 | 值 / 行为 |
|------|-----------|
| 列表 / 详情 TTL | `MetadataCacheRules.DEFAULT_TTL_MS` = **10 分钟**（任务书建议 5–15 分钟） |
| 分页页片 TTL | `MetadataCacheRules.PAGING_TTL_MS` = **5 分钟**（分页内容更"活"，更快看到新数据） |
| 容量 | `DEFAULT_MAX_ENTRIES` = 256 条（LRU 淘汰；进程内存，不持久化） |
| TTL 内 | **直接复用、不发请求**（Timber `metadata cache hit: <key>`） |
| TTL 外 | 页面保留已上屏内容，读路径重新请求并回填（`metadata cache miss (refreshing)`）——刷新期间不清列表、不置空图片 |
| 强制刷新 | `JellyfinRepository.invalidateMetadataCache()`：下拉刷新 / 重试 / 排序筛选变化先调用，再照常读取 |
| 用户动作失效 | 收藏 / 取消收藏 / 标记已看 / 取消已看 / 播放结束（`postPlaybackStop`）→ 全量失效（状态内嵌在条目里） |

**实现落点**：

- `MetadataCache`：会话级（`JellyfinRepositoryImpl` 是 `@Singleton`）进程内存缓存，键按「服务器地址 + 用户 id」**命名空间隔离**（切换服务器 / 账号不串数据）；LRU 表加锁访问、时钟可注入（单测）。
- `MetadataCacheKeys`：每个读方法的键（`views` / `latest:<库>` / `resume:<类型>` / `nextup:<剧>` / `items:<库|类型|排序|过滤|分页窗口>` / `show|season|movie|episode:<id>` / `seasons` / `episodes` / `favorites:<排序>` / `search:<关键词>` / `count` / 库内建议 / 即将播出 / 类型 / 制片发行商 / 演职人员条目），前缀常量同时用于分组失效。
- 覆盖读方法：`getUserViews` / `getLibraries` / `getItem(s)`（含分页页片）/ `getItemCount` / `getLatestMedia` / `getResumeItems` / `getNextUp` / `getSuggestions` / `getFavoriteItems` / `getSearchItems` / `getLibrarySuggestions` / `getUpcomingEpisodes` / `getGenres` / `getStudios` / `getPersonItems` / `getShow` / `getSeason` / `getMovie` / `getEpisode` / `getSeasons` / `getEpisodes`。
- **不缓存**：播放链路（`getMediaSources` / `getStreamUrl` / `getSegments` / `getTrickplayData`）与下载页自有短 TTL 链路（`getDownloads` / `getPrimaryImageUrl` / `ImagesDownloaderWorker`）。

**页面层不变量**（后续新增列表 / 详情页照此办理）：

1. **无缓存才骨架**：骨架只在"真的没有任何可渲染内容"时显示（首页 `hasRenderableContent`；库内容页 `items.itemCount == 0`；详情页 `model == null`）。
2. **不重建分页流**：`VideoViewModel.load()` / `LibraryViewModel.loadItems()` 在 TTL 内直接复用已上屏的 `Pager`（`LazyPagingItems` + `cachedIn` 的数据原样保留）；TTL 外只发 `refreshSignal` 触发 `LazyPagingItems.refresh()`——保留现有条目，新页回来原地替换。
3. **图片不置空**：海报 / 剧照 / 头图统一走 `RetainedAsyncImage`（`app:phone`）——模型变化时继续画上一张成功加载的图，新图就绪后 160ms 交叉淡入；失败回落占位，绝不先把海报变成黑底。
4. **列表就地更新**：走廊 / 网格使用稳定 `key`（`item.id`），数据刷新只增删真正新增 / 删除的行。

**取舍与未覆盖**：进程重启后没有内存缓存（首次进入仍按网络加载 → 骨架，任务书口径允许）；TTL 期间服务器新增条目要等下拉刷新或 TTL 过期；缓存不做持久化（Room 快照 / 上次会话快照留给后续按需）。

## 6. 并行开发边界（供 S3 排期）

### 6.1 必须串行（有硬依赖）

1. **第 0 步（一次做完，单会话）**：`settings.gradle.kts` 注册 `:modes:book` + `:modes:music`；根 `build.gradle.kts` 加 JitPack 仓库；`gradle/libs.versions.toml` 加 Readium 版本与坐标；两个新模块的空骨架（`build.gradle.kts` + 空包）→ 保证 `assembleDebug` 通过。**此后除依赖版本升级外不再有人动这三个文件。**
2. **接口先行**：`data` 层新增接口（§5.1）+ `player/core` 新模型（§5.2）先合入，R1 / R2 才能并行填实现。
3. **下载器通用化**：`core/utils/DownloaderImpl.kt` 由一条线先完成"通用条目下载"重构，另一条线再加自己的分支。
4. **`NavigationRoot.kt`**：两次小提交（阅读路由、音乐路由），改前 rebase。
5. **设计系统**：由 R3 独家串行改动 `core` / `app` 的 theme 文件；R1 / R2 只消费 token。

### 6.2 可并行（无硬依赖）

| 并行组 | 说明 |
|--------|------|
| R1 阅读内部 | EPUB 渲染、PDF 懒加载、CBZ 评估、进度同步可分别成任务并行（同一会话内按 1–2 项串行做） |
| R2 音乐内部 | 曲库浏览、队列模型（纯逻辑）、歌词解析 / 配对（纯逻辑）、离线下载可并行（纯逻辑可最先开工） |
| R3 UI 落地 | 与播放器线解耦的页面（首页 / 媒体库 / 设置）可与 R1 / R2 并行；**动咽喉文件时排队** |
| R4 测试 | 测试数据准备、性能基线测量、真机矩阵脚本可与开发并行 |

### 6.3 依赖顺序（关键路径）

```text
设计系统 token（R3） ─┬─▶ 阅读页 UI（R1）
                      └─▶ 音乐页 UI（R2）
模块注册 + 接口骨架（第 0 步）─┬─▶ EPUB 阅读闭环（R1）
                              ├─▶ 音乐播放闭环（R2）
                              └─▶ 歌词纯逻辑（R2，可最早开工）
PDF 懒加载 spike（R1）──▶ 走 Readium PDF 或自研 PDF（二选一）
```

---

## 7. 风险与待验证清单

| # | 风险 / 待验证 | 影响 | 处理 |
|---|--------------|------|------|
| 1 | pdfium 适配器依赖 **JitPack**（`com.github.marain87:*`） | 构建可用性 | 本机直连实测 200 / 1.4 s；CI 首次 PR 时验证；失败则启用 `androidx.pdf` 备选 |
| 2 | 应用体积：Readium（navigator / streamer / pdfium）+ pdfium native | APK 体积劣化 | 仅 `:modes:book` 引入；PR 时对比 `assembleLibreDebug` 体积，超阈值评审说明 |
| 3 | Readium `readium-navigator` 依赖 fragment / constraintlayout / webkit 等 View 体系库 | 与 Compose 混用复杂度 | `AndroidView` 封装成单一 Composable，Fragment 生命周期收敛在容器内 |
| 4 | CBZ 在 Readium 为 🚧 | 漫画需求 | R1 先做可行性 spike，不达标自研（§3.5） |
| 5 | 阅读进度字段与官方 Web 阅读器是否一致 | 跨端进度 | Web 读一段后对比 `UserData` 变化（待验证） |
| 6 | 服务器 10.11.8 与 OpenAPI 12.1 规范存在差异 | 接口调用失败 | 已实测：读进度用 `/Users/{userId}/Items/{itemId}/UserData`；写进度 `POST /UserItems/{itemId}/UserData` 待首次实现验证 |
| 7 | 歌词语言检测准确率（中英混排行） | 验收样例 | 用 `aLIEz` / `Brave Shine` / `爱的回归线` 三例做基线；必要时引入 Lingua |
| 8 | 音频 gapless 在不同编码 / 采样率下表现 | MU-4 验收 | 真机听感 + 日志验证；不达标时把 crossfade 提前评估 |
| 9 | `PlayerHolder` 单例被音乐复用后视频回归 | 稳定性 | `PlaybackCoordinator` 全量接管启停；每次改动跑视频冒烟 |
| 10 | 新依赖许可（GPL-3.0 兼容性） | 合规 | Readium **BSD-3-Clause** ✅、PdfiumAndroid / AndroidPdfViewer **Apache-2.0** ✅、Lingua **Apache-2.0** ✅；任何新库进 PR 前必须核许可 |

---

## 8. 需求条款映射

| 需求 | 本文章节 | 关键结论 |
|------|---------|---------|
| EB-2 EPUB | §3.1–3.3 | Readium 3.4.0 + 先落盘再打开 |
| EB-3 PDF 懒加载 | §3.4 | 页窗口 ±1、位图 ≤3、降采样、禁止整文档渲染 |
| EB-4 CBZ | §3.5 | Readium 🚧 先验证，不达标自研；CBR 不做 |
| EB-5 / 6 / 7 排版与主题 | §3.2 | 直接映射 Readium 偏好；阅读器主题与主 App 解耦 |
| EB-8 批注 | §3.6 | 本地 Room，服务端无标准 API |
| EB-9 进度同步 | §3.6 | UserData（ticks / percentage）+ 本地 Locator + 离线队列 + 最近时间戳 |
| EB-10 入口改造 | §2.2、§6.1 | 替换 `Book → ConsoleRoute` 分支为原生阅读路由 |
| EB-11 离线 | §3.3、§4.4 | 复用下载体系 |
| MU-1 / 2 入口与曲库 | §2.2、§4.6 | 独立 `:modes:music` + 抽屉入口 |
| MU-3 队列 | §4.2 | 单队列 + 排序 + 下一首 + 保存 |
| MU-4 音质 / gapless | §4.1 | 音频强制 ExoPlayer；gapless 依赖 Media3 |
| MU-5 歌词 | §4.3 | 同戳 / 极近成对 + 逐行语言检测 + 默认简体中文 |
| MU-6 离线 | §4.4 | 复用下载器 |
| MU-7 系统集成 | §4.1 | 复用 MediaSessionService / 通知 |
| MU-8 会话互斥 | §4.1 | 单 Player + `PlaybackCoordinator` |
| MU-9 进度上报 | §4.5 | 复用 `Sessions/Playing*` |

---

## 9. 来源

**本项目**

- `docs/REQUIREMENTS.md`（v1.0，唯一需求基线）
- `docs/PROJECT_PLAN.md`、`docs/DEV_ENVIRONMENT.md`、`docs/design/s1-decision.md`
- 代码实测：`settings.gradle.kts`、根 `build.gradle.kts`、`core/build.gradle.kts`、`data/build.gradle.kts`、`player/*`、`modes/film/*`、`app/phone/.../NavigationRoot.kt`、`app/phone/.../playback/CinefinPlaybackService.kt`、`.github/workflows/*`

**外部（均经代理 30001 抓取，2026-09-29）**

- Android 架构建议：<https://developer.android.com/topic/architecture/recommendations>
- Readium Kotlin Toolkit：<https://github.com/readium/kotlin-toolkit>（能力矩阵）、<https://readium.org/kotlin-toolkit>
- Readium 指南（`develop` 分支）：`docs/guides/pdf.md`、`docs/guides/open-publication.md`、`docs/guides/navigator/navigator.md`、`docs/guides/navigator/preferences.md`
- Readium 制品与版本：<https://repo1.maven.org/maven2/org/readium/kotlin-toolkit/>
- Jellyfin OpenAPI（stable，抓取到 12.1.0 规范）：<https://api.jellyfin.org/openapi/jellyfin-openapi-stable.json>
- Jellyfin Books 目录约定：<https://jellyfin.org/docs/general/server/media/books/>
- Media3 后台播放：<https://developer.android.com/media/media3/session/background-playback>
- Media3 词汇表（gapless）：<https://developer.android.com/media/media3/exoplayer/glossary#gapless-playback>
- Media3 发布说明（gapless 相关条目）：<https://github.com/androidx/media/blob/release/RELEASENOTES.md>
- androidx.pdf 版本（备选方案）：<https://dl.google.com/android/maven2/androidx/pdf/pdf-viewer/maven-metadata.xml>
- JitPack：<https://jitpack.io>（`com.github.marain87:PdfiumAndroid:1.9.8`）
- Lingua 语言检测：<https://repo1.maven.org/maven2/com/github/pemistahl/lingua/maven-metadata.xml>

**服务器实测（只读 + 用户数据白名单）**

- `GET /Items?includeItemTypes=Book`、`GET /Items/{id}/Download`（206 Range）、`GET /Users/{userId}/Items/{itemId}/UserData`、`GET /Audio/{id}/Lyrics`（aLIEz 83 行 / 40 组同戳配对）
