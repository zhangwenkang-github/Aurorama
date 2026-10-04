# Cinefin 阅读器任务线（READER_PLAN）

> 本文件是阅读器线（R1）唯一权威文档：需求、决策、进度、验收标准与踩坑库都写在这里。
> 项目级状态仍写 `docs/PROJECT_PLAN.md`，本文件不复制项目级内容。

| 项 | 值 |
|----|----|
| 任务线 | 阅读器（EPUB / PDF / CBZ） |
| 会话 | W1-R1 骨架 → W2-R1 主体 → W3-R1 离线与进度 → W4-R1 · PDF / CBZ 格式支持 → W9-R1 漫画方向（RTL）→ W15-UI 顶栏修复 → W22-R1 · 跨页对图合并 → W26-R1 · 横版独占与带纸边合并 → **W29-R1 · PDF 搜索 + 本地高亮批注** |
| 分支 | `feature/r1-pdf-cbz`（W4）→ `feature/w9-reader-comics`（W9）→ `feature/w22-spread-merge`（W22）→ `feature/w26-reader-forms`（W26）→ **`feature/w29-pdf-search-annot`（W29，当前）** |
| 基线 | `master` `d972315`（2026-10-02；W22/W26 均已合并：`21a97d0` / `a3dfc01`） |
| 状态 | W4 PDF / CBZ 自研 + W9 RTL + W15-UI 顶栏 + W22 跨页对图合并 + W26 横版独占/带纸边裁剪均已合并 master（`modes:book` 77 项单测）；**W29 落地 PDF 文本层搜索 + 本地矩形高亮批注**（PdfBox-Android 流式扫描、`filesDir/reader/annotations/{itemId}.json`），`:modes:book` **103 项单测**（+26）、门禁四绿；**真机验收已在 Pad 5 完成**（§7.13）：真机拦下 1 个搜索崩溃缺陷（重复 key）已修复复验，另顺带发现 W26 版式扫描在大文档上的 native 内存膨胀（未修，交 W26 线） |

## 1. 需求与 W1 范围

需求基线为 `docs/REQUIREMENTS.md` §4（EB-1–EB-12）。

| 编号 | 需求 | W1 状态 |
|------|------|---------|
| EB-1 | 书源仅 Jellyfin Books 库 | 🟡 已用 Books 库真实 EPUB 验证；书籍详情入口归 W2 |
| EB-2 | 首期 EPUB | ✅ Readium 3.4.0 已集成并真机打开 |
| EB-3 | PDF 分页懒加载 | ✅ W4 落地（PdfRenderer 自研，D14）：打开 / 翻页 / 缩放 + 页窗口 3 张位图，真机见 §7.6 |
| EB-4 | CBZ 基础阅读 | ✅ W4 落地（ZipFile 自研，D15）：两本 CBZ 可打开，滚动 / 分页 / 双栏三档生效；RTL 开关后置 |
| EB-5 | 滚动 / 横向分页 / 双栏 | ✅ 三档模式（滚动 / 分页 / 双栏）真机切换通过，Pad 5 横屏双栏渲染正确 |
| EB-6 | 排版设置 | ✅ 字号 / 行距 / 边距 / 字体 / 对齐，即时生效并持久化 |
| EB-7 | 独立阅读主题 | ✅ 纸色 / 护眼 / 深色 / OLED / 跟随，独立于主 App 主题 |
| EB-8 | 批注 | 🟡 W3 完成**书签**（本地 JSON + 添加 / 跳转 / 删除）；高亮 / 笔记与导出后置 |
| EB-9 | 进度写回 UserData | ✅ W3 补齐离线暂存 + 联网回传（WorkManager）+ 冲突策略 + 30 秒 / 退后台上报 |
| EB-10 | 入口改造 | ✅ R3 在 W3 落地（`NavigationRoot` 书籍条目显式 Intent + `ReaderActivity` 改回 `exported=false`）；本会话按约束未改导航 |
| EB-11 | 离线阅读 | ✅ 整书下载到应用私有目录（`.part` 原子落盘）+ 阅读页离线状态 + 飞行模式可打开、可记录进度 |
| EB-12 | 辅助阅读 | ⛔ 本期不做 |

## 2. 决策记录

### D1 · 引擎：Readium Kotlin Toolkit 3.4.0

- 依赖：`readium-shared` / `readium-streamer` / `readium-navigator`，版本 `3.4.0`。
- 传递依赖与项目对齐（Kotlin 2.4.20 / compileSdk 37 / minSdk 28 / Media3 1.11.x）。
- 许可 BSD-3-Clause，与 GPL-3.0 兼容。
- 版本目录条目由 R1 单独提交（`f30f0fb`），是 W1 波次中唯一一次修改
  `gradle/libs.versions.toml`。

### D2 · 集成：Compose `AndroidView` + `FragmentContainerView`

- Readium 3.4.0 的 Visual Navigator 仍是 Fragment 体系，PoC Activity 由
  `AppCompatActivity.supportFragmentManager` 承载单个 `EpubNavigatorFragment`。
- `AndroidView.factory` 创建 `FragmentContainerView`，**必须在 `post {}` 后**添加
  Fragment；否则容器尚未挂到 Activity 视图树，`commitNow` 会抛
  `No view found for id ...`（见 §8 踩坑 3）。
- Fragment 用 `EpubNavigatorFactory.createFragmentFactory(...)` 生成，传入
  `initialLocator`、`EpubPreferences(scroll, theme)` 与 Listener；进度监听走
  `EpubNavigatorFragment.PaginationListener.onPageChanged`。
- W1 PoC 直接使用 Activity 的 FragmentManager；W2 接入导航后改回
  `childFragmentManager`。

### D3 · 取书：先落盘再打开（沿用 ARCHITECTURE §3.3）

- `ReaderRepository.ensureLocalFile(itemId)`：命中
  `filesDir/books/{itemId}.book` 直接返回；否则
  `GET /Items/{itemId}/Download`（`X-Emby-Token`）流式落盘。
- 落盘后 `AssetRetriever.retrieve(File)` → `DefaultPublicationParser` →
  `PublicationOpener.open(asset)`；打开失败会关闭 `Asset`。

### D4 · 本地精确定位：W1 用应用私有 JSON，W3 再迁 Room

- `ReaderProgressStore` 落 `filesDir/reader/progress.json`，保存 Readium `Locator`
  的 JSON、progression、ticks、`updatedAt` 与 `pendingSync`。
- 暂不使用 Room 的理由：W1 波次 R1 / R2 可能并行改 Room schema；数据库版本号
  属于咽喉资源（PARALLEL_PLAN §1.3）。W3-R1 再做带迁移的 `reader_annotations` /
  `reader_progress` 表。
- 冲突策略：最近 `updatedAt` 优先；本地 locator 永远保留用于精确恢复。

### D5 · PDF 路线建议（W4 执行前定稿）

评估结论（详见 §5 skill 2–4）：

1. **Readium pdfium 适配器**：与 EPUB 共用导航器 API、开发量最小；但依赖 JitPack
   的 `com.github.marain87:PdfiumAndroid` / `AndroidPdfViewer`，需验证 ABI 体积、
   中文渲染与 CI 可达性，且 pdfium 自身不保证「页窗口」内存上限。
2. **androidx.pdf 1.0.0-beta01**：`pdf-viewer-fragment` 已到 beta，Fragment 体系可
   直接复用；但仍为 beta，且 API 偏「整文档查看器」，懒加载窗口是否可控需 spike。
3. **PdfRenderer 自研**：`PdfRenderer.openPage(index)` + `Page.close()` 能严格实现
   EB-3 的「当前 ±1 页、位图 ≤3 张、降采样到屏幕宽」；工作量最大，但不引入
   JitPack 与 beta 依赖。

**建议**：W4 先用一本书 ≥200 页的 PDF 各做 1 天 spike；若 Readium pdfium 无法把
内存钉在常数区间，则 PDF 走 PdfRenderer 自研、EPUB 继续 Readium（ARCHITECTURE
§3.4 的备选方案）。

### D6 · CBZ 策略

- Readium 对 CBZ 标注「部分实现」，判定标准：单页 + 预取 ≤3 页、双页 / RTL、
  连续 20 页内存稳定；不满足则自研。
- 自研路径：`ZipFile` 随机读条目（`ZipInputStream` 顺序流仅作兜底）+
  `BitmapFactory.inSampleSize` 降采样 + Compose `HorizontalPager` + 下一页预取
  + 位图 LRU。
- 禁止整包解压；复用 §3.4 的页窗口预算。CBR 本期不做。

### D7 · W1 PoC 入口

- `ReaderActivity` 声明 `exported=true`，仅用于本波 adb 显式启动验证。
- W2 接入 `NavigationRoot` 后必须改回 `exported=false`，并把启动参数改为
  `itemId: UUID` 路由参数。

### D8 · 阅读设置模型与提交方式（W2）

- `ReaderSettings`（`modes/book/.../reader/ReaderSettings.kt`）是 UI / 持久化使用的纯数据：
  `mode` / `fontSize` / `lineHeight` / `pageMargins` / `font` / `textAlign` / `theme`。
- 每次设置变更构造**一整套** `EpubPreferences` 并 `navigator.submitPreferences(...)`，
  保证即时生效且无旧偏好残留；不需要重建 Navigator Fragment。
- 模式三档映射：滚动 = `scroll=true`；分页 = `scroll=false` + `columnCount=ONE`；
  双栏 = `scroll=false` + `columnCount=TWO`（官方建议把 `columnCount` / `spread`
  收敛为用户可见的单一"双页"开关）。
- **字号单位是百分比倍数**（`fontSize=1.0` → CSS 100%），不是 px；官方 supportedRange
  为字号 0.1–5.0、行距 1.0–2.0、边距 0.0–4.0，UI 取实用子区间 0.7–2.5 / 1.0–2.0 / 0.0–2.0，
  越界 / 非有限值在 `ReaderSettings.sanitized()` 中裁剪。
- 主题映射：纸色 / 护眼 = `Theme.LIGHT` + 自定义背景 / 文字色；深色 / OLED = `Theme.DARK`
  + 自定义背景 / 文字色；`跟随` 按系统深色解析为深色 / 纸色。
- 字体：默认不覆盖出版方字体；衬线 / 无衬线 / 等宽分别映射 Readium 内置
  `FontFamily.SERIF / SANS_SERIF / MONOSPACE`，不打包字体文件。
- 持久化：`AppPreferences` 只追加 `pref_reader_*` 键（`pref_reader_mode` /
  `pref_reader_font_size` / `pref_reader_line_height` / `pref_reader_page_margins` /
  `pref_reader_font_family` / `pref_reader_theme` / `pref_reader_text_align`），
  由 `ReaderViewModel` 读写，未重排文件、未改其他任务线的键。

### D9 · 阅读主题 / 面板 token（W2）

- 复用 core 已落地的 `PaperSurface` / `PaperAccent`；在 `CinefinTokens` 追加 §8.14 阅读器
  区块：`ReaderPanelDark`（`#191F28`，设计系统指定）、`ReaderEyeCareSurface`（`#E7EFE1`，
  按纸色向绿色相偏移的派生值）、`ReaderOledSurface`（`#000000`）。
- 强调色：纸色 / 护眼 = 纸页棕 `PaperAccent`；深色 / OLED = 阅读域天青 `MediaBook.base`；
  强调填充上的文字用 `OnSurfaceLight` / `MediaBook.onBase`。
- 这三项是 core token 的唯一追加（本波 core 主题文件无其他会话并行修改）；业务层不出现
  自造色值，全部经 token 引用。若后续 S1/R3 修订设计系统，以设计系统为准回调。
- 排版面板按 §8.14 实现：宽 512dp、圆角 22dp（`CinefinShapes.Lg`）、内边距 30/28dp、
  78dp 主题缩略图（选中 2dp 描边）、滑块 / chip 行随阅读主题换色。

### D10 · 面板滚动（真机发现）

- 真机（Pad 5 横屏）发现 `ModalBottomSheet` 内固定高度的面板内容超过可视区时不会自动滚动，
  主题行不可达。修复：`ReaderSettingsPanel` 的 Column 自带 `verticalScroll`，真机复测
  5 个主题缩略图全部可见、可点。

### D11 · 本地存储：W3 仍用应用私有 JSON，Room 迁移延后（负责人协调）

- 背景：本波 R2-LYRICS 同时在做歌词缓存，Room schema / 数据库版本号是咽喉资源
  （PARALLEL_PLAN §1.3：**同一时间只允许一条线改 schema**）。R1 本波**不改 Room**。
- 现状：进度 `filesDir/reader/progress.json`、书签 `filesDir/reader/bookmarks.json`，
  都是 `.tmp` + rename 的原子写，脏文件回退为空、不阻塞阅读页。
- 迁移方案（**仅记录，未执行**，待负责人排期，建议 R2-LYRICS 合并后由 R1 单独提交）：
  新增 `reader_progress` / `reader_bookmarks` 两表 + 一次 `AutoMigration`，首次升级时把两个
  JSON 一次性导入（导入后可保留原文件只读）；版本号只 bump 一次，避免与音乐线交叉。

### D12 · 离线 ticks 与多设备冲突策略（W3 细化）

- **ticks 换算**：把书目的 `RunTimeTicks` 缓存进本地进度记录（`ReadingProgress.runtimeTicks`）。
  离线保存时服务端不可达，旧实现会退回 `DEFAULT_BOOK_TIMELINE_TICKS`（10¹⁰）换算出偏大约
  1000 倍的 `PlaybackPositionTicks`，恢复联网后把这个错误值回传；缓存后离线与在线换算一致。
- **冲突**：仍是"最近 `updatedAt` 胜出"（ARCHITECTURE §3.6），但**本地 locator 只在与服务端
  胜出进度近似相等（±`PROGRESSION_MATCH_EPSILON` = 0.05%）时保留**：
  - 本机自己回传的进度会让服务端 `LastPlayedDate` 略晚于本地 `updatedAt`（W1 实测），若一律
    丢弃 locator 会丢掉精确位置 → 需要保留；
  - 另一台设备读到的新位置若继续套用本机 locator 会跳回旧章节 → 必须丢弃，改由
    `Publication.locateProgression(progression)` 定位（ReaderViewModel）。

### D13 · 书签模型（EB-8 基础版）

- `ReaderBookmark(id, itemId, locatorJson, progression, label, createdAt)`；标签 =
  Readium Locator 的章节标题 + 整书百分比（纯函数 `bookmarkLabel`，无标题时只用百分比）。
- 服务端没有标准批注 API（ARCHITECTURE §3.6），因此只落本地；跨端同步 / 导出后置。
- 阅读页顶栏"书签"打开面板：添加当前页、点击跳转（`navigator.go(locator, animated)`）、删除。

### D14 · PDF 路线定稿：PdfRenderer 自研（W4-R1）

按 D5 的三条候选路线完成 spike 后定稿（依据：Readium pdf.md 官方指南、androidx.pdf 发布说明、
`PdfRenderer` API 文档、本仓库依赖现状与 EB-3 硬约束）：

| 路线 | 结论 | 依据 |
|------|------|------|
| Readium pdfium 适配器 | ❌ 不选 | 需经 JitPack 引入第三方 `PdfiumAndroid` / `AndroidPdfViewer`（ABI 体积、CI 可达性、许可链都多一层），且 `PdfNavigatorFragment` 的页窗口与位图缓存不可控，EB-3 的「≤3 张位图」无法直接保证 |
| androidx.pdf `pdf-viewer-fragment` | ❌ 不选 | 仍为 beta，定位是「整文档查看器 + DocumentService」，懒加载窗口不可配置，与 EB-3 验收口径不匹配 |
| **PdfRenderer 自研** | ✅ 选用 | 框架 API（API 21+，零新增依赖）：`openPage(index)` + `Page.close()` 天然按页渲染回收；位图尺寸 / 降采样 / LRU 窗口全部由我们钉死，失败页降级占位 + 重试 |

实现（`PdfPageSource` + `PageImageCache` + `SimpleBookView`）：

- 位图长边 = `min(屏幕宽, 2048)`；`Page.render` 用 `Matrix` 等比缩放，先铺白底再渲染（避免透明通道
  在深色主题下泛黑）；`ARGB_8888`（PDF 文字锐利优先）。
- 内存窗口：`LruCache` 固定 3 张（当前页 ± 1），翻页只把新页放进窗口，旧页立即失去引用；
  `PdfRenderer` 非线程安全 → 单 `Mutex` 串行化所有页面操作。
- 文档对象（renderer + fd）在 `ReaderViewModel.onCleared` 统一释放；重复 close 安全。
- 缩放：分页 / 双栏模式支持双指缩放（1×–4×）+ 拖动，平移量按放大后尺寸夹取；滚动模式不做手势
  缩放（避免与纵向滚动抢事件）。
- **不做**：文本选择 / 搜索 / 批注 / 目录（EB-3 明确后置）；加密 PDF 受 `PdfRenderer` 能力限制，
  打不开时走错误态 + 重试。

### D15 · CBZ 路线定稿：ZipFile 自研 + 解析失败根因（W4-R1）

**根因（勘定结论）**：Readium `ArchiveSniffer.sniffContainer` 只在「压缩包内所有条目扩展名都在白名单
（位图 + `acbf` / `xml`）」或「资源带 `.cbz` 扩展名」时才把 ZIP 判为 InformalComic。本仓库取书统一
落盘为 `filesDir/books/{itemId}.book`（D3，**无扩展名**），于是只剩内容嗅探：Anda's Game 内含
`Fonts/` 下的 ttf / otf 与 `*.txt` 说明文件 → 判定为普通 ZIP → `ImageParser` 因
`format.conformsTo(InformalComic)` 为假返回 `FormatNotSupported` → 阅读页报「解析书籍失败」。
`futuristic_tales` 只有 4 张 jpg（+1 个目录条目）→ 能打开。即：**包结构问题被 Readium 的全包白名单
放大**，与包体积无关（16.5MB 本身不是原因）。

两本地图包的实际结构（`zipfile` 只读清点，本地副本 `test_files/`）：

| 文件 | 条目总数 | 条目构成 | 过滤后页数（本实现） | Readium 结果 |
|------|---------|----------|---------------------|--------------|
| `Andas_Game_2007.cbz`（16.5 MiB） | 37 | 24 × jpg + `AndasGame.acbf` + `ComicInfo.xml` + `Fonts/`（目录）+ 4 × txt + 6 × 字体（4 otf / 2 ttf） | **24**（`!cover.jpg` 在最前，与 Readium 的字符串排序一致） | ❌ `FormatNotSupported` |
| `futuristic_tales.cbz`（0.7 MiB） | 5 | 1 × 目录 + 4 × jpg | **4** | ✅ 可解析（但三种模式都渲染为分页） |

页序规则（`ComicPageOrder.kt`）：只保留位图条目（bmp/dib/gif/jif/jfi/jfif/jpg/jpeg/png/tif/tiff/webp），
过滤目录条目、隐藏文件 / 目录、`__MACOSX`、`Thumbs.db`，再按「连续数字当数值」的自然序排序
（`2.jpg` < `10.jpg`；`!cover.jpg` 因 ASCII 排序仍在最前，与 Readium 的 `sortedBy(toString())` 一致）。

**附带发现**：Readium 的图像导航只有「分页」语义，`EpubPreferences` 的 `scroll` / `columnCount`
对它无效 —— 用户实测三种模式都渲染为分页，与 EB-4 / EB-5 不符。

**定稿**：按 D6 既定兜底自研 CBZ：

- `ComicPageSource`：`ZipFile` 随机读条目；页 = 只取位图条目（`ComicPageOrder.kt`：过滤目录 /
  隐藏文件 / `__MACOSX` / `Thumbs.db` / 字体 / `acbf` / `xml`），按自然序排序（连续数字当数值比较，
  `!cover.jpg` 仍在最前，与 Readium 的排序一致）。
- 解码：先 `inJustDecodeBounds` 读尺寸，再 `BitmapFactory.decodeStream` + `inSampleSize`（2 的幂）
  降采样到与 PDF 相同的窗口预算；`RGB_565`（扫描页是位图，窗口内存直接减半）。
- 三种模式与 PDF 共用 `SimpleBookView`：滚动 = 纵向连续（当前页 = 首个可见页）；分页 = 单页横向
  Pager；双栏 = 双页横向 Pager（Pad 5 横屏左右各一页）。
- 书签暂不支持 PDF / CBZ（书签依赖 Readium Locator 语义，页序列格式改用 progression），顶栏
  「书签」入口在这两类格式下隐藏。

### D16 · 格式嗅探与进度语义（W4-R1）

- 本地缓存文件名看不出格式，按**内容**嗅探（`BookFormat.kt`）：`%PDF-` → PDF；ZIP 且含
  `META-INF/container.xml` → EPUB；其余 ZIP → CBZ；都不匹配 → Unknown（仍交给 Readium 尝试，
  错误原样透出）。不依赖扩展名与 Jellyfin 元数据，离线也成立。
- 健壮性细节：① ZIP 魔数（头 4 字节）优先于 PDF 判定，避免压缩包前 1 KB 恰好含 `%PDF-` 时误判；
  ② PDF 头按 PDF 1.7 §7.5.2 允许出现在**前 1 KiB**（探针 1024 字节内查找魔数）；③ ZIP 含
  `mimetype` 条目（即使缺 `container.xml`）也按 EPUB 处理；④ ZIP 过滤后一张位图都没有时不当作漫画，
  交回 Readium 报错（兼容结构异常的 EPUB / 非漫画 ZIP）。
- 进度：PDF / CBZ 用「页索引 / 总页数」换算 `progression`（页起点），写入与回传复用 EB-9 同一条
  `ReadingProgress` 链路（ticks 由服务端 `RunTimeTicks` 换算，D12）；恢复用
  `pageIndexForProgression`（floor + ε，抵消浮点往返误差）。`locatorJson` 留空，避免与 Readium
  Locator 语义混淆。

### D17 · RTL 右起翻页（漫画）与三档模式语义（W9-READER）

- 开关：`ReaderSettings.rtl`，持久化 `pref_reader_rtl`（`AppPreferences` 只追加，未重排既有键）；
  只在页序列文档（PDF / CBZ）的阅读设置面板出现——EPUB 的阅读方向由 Readium 出版物元数据决定，
  不提供全局开关（`ReaderSettingsPanel(showRtl = document is ReaderDocument.Simple)`）。
- **逻辑页序恒为 1, 2, 3…**：RTL 不改页号、不改进度语义（`progressionForPage` / `pageIndicatorText`
  仍按逻辑页），只改视觉呈现与翻页方向——三条不变量由 `SpreadOrder.kt` 的纯函数单测锁住。
- 三档行为（真机口径见 §7.7）：
  - **分页**：整条页链镜像（`HorizontalPager(reverseLayout = rtl)`）——右起时向右滑动前进，
    单页槽位不变；
  - **双栏**：页链镜像 + spread 内左右页镜像（右 = 2k+1、左 = 2k+2，读序自右向左）；
    尾页单张时空槽留左（RTL 尾页贴右），LTR 仍左奇右偶；
  - **滚动**：纵向连续阅读没有可翻转的横向轴，**保持不变**（1 → 2 → 3 自上而下）；
    页指示不追加「右起」后缀（`isRtlPaging(mode, rtl)` 只在分页 / 双栏为真）。
- 页指示在 RTL 生效时显示「分页 · 1/24 · 右起」：既是用户提示，也是真机验收的文本证据
  （省的每次都要靠截图判断方向）。
- 切开关时 `key(rtl)` 重建 Pager，`initialPage` 取当前逻辑页 → 正在读的那一页不会因镜像跳页。
- 「封面单张」（RTL 首屏只放第 1 页、其后 2+3、4+5…）是实体漫画的排版习惯，但会改动
  spread ↔ 页号映射与 W4 已验收的「双栏 1-2」口径，本波不做，列入 W5 候选。

### D18 · 滚动模式双指缩放：页项消费的「双指优先」手势（W9-READER）

- 复用分页模式已验证的手势形态：手势挂在**每个页项**上（`ZoomablePage`），只在 ≥2 指同时按下时
  `calculateZoom/calculatePan` + `consume()`；单指拖动不消费，继续交给 `LazyColumn` 纵向滚动。
  这是踩坑 18 的推广——若把手势挂在 LazyColumn 的**父层**，Main pass 里滚动容器（子层）先消费，
  父层再 consume 也抢不回控制权，会出现「双指时列表还在滚」。
- 滚动模式语义 = **页内缩放**：在某一页上双指捏合只放大该页（1×–4×），内容裁在页槽内，双指拖动
  平移；纵向滚动始终可用（放大后仍是一指上下滚、换页）。
- 范围与分页模式完全一致：共用 `PageZoom` 状态机（1×–[PAGE_MAX_ZOOM] = 4×，平移按放大后的可移动
  范围夹取、缩回 1× 自动居中），`PageZoom.transform` 对 NaN / 无穷 / 非正倍率全部有守卫。
- 调试轨迹：缩放步进 ≥0.1× 时打一行 `Timber.d("reader zoom index=… scale=… offset=…")`，
  真机验收用 logcat 文本判断手势确实生效（不贴截图）。

### D19 · 跨页对图合并：本波**不实施**，只交付正确排序 + 方案记录（W9-READER）

**结论**：双栏 / 分页 / 滚动的页序本波已收敛（含 RTL 镜像，D17）；**跨页对图合并（把被拆成两张的
横版对图拼回一页）不做**——代价与风险都不满足「不许半成品上线」，理由与后续方案如下。

| 维度 | 研判 | 结论 |
|------|------|------|
| 内存 | 两半各按 2048 长边渲染时，合并图长边 2×2048 ≈ 3–4K px：ARGB 单张 ≈25 MB、RGB_565 ≈12 MB，是单页预算的 2×；合并瞬间还有「两张源图 + 一张目标图」的 3× 峰值。EB-3 的「窗口 3 张位图 / 长边 ≤2048」是按单页钉死的，合并需要**重建窗口预算与缓存键**（槽位从"页"变成"页/对图"两种粒度） | 高代价，需独立设计 |
| 检测 | CBZ 没有可靠的"这两页是一张跨页图"元数据：实测两本测试书**全部是竖版页**（Anda's Game 24 页 1327×2039，只有 `Type=FrontCover` / `Letters` 两个标记），futuristic_tales 连 ComicInfo.xml 都没有。自动判定只能靠宽高比 + 边缘连续性启发式，误判会让整本书的翻页配对错位 | 不可靠 |
| 画质 | 两半扫描图各自带纸边 / 页边距，直接并排仍有一条白缝；要"无缝"得先按内容裁边再对齐（逐本调参或做边缘检测），否则只是把两页搬到一个位图里 | 需要额外算法与逐本验证 |
| 语义耦合 | 合并后「一屏 = 一页」还是「一屏 = 两页」会改变页指示、progression 与恢复口径（D16），需与 RTL / 双栏 / 书签模型一起设计 | 牵涉面大 |

**本波交付**：单页 / 双栏的页序与 RTL 镜像（`SpreadOrder.kt` 纯函数 + 8 项单测）、页指示与
`PageZoom` 单测；跨页合并的可行方案记录在案。**后续（W5 候选）**：① 只在**双栏 + 用户显式开关**
下试用，先做「按 spread 手动标记合并」而不是全自动；② 合并位图单独走 `LruCache` 预算
（建议长边上限 3072、RGB_565 优先），窗口退化为「当前槽 + 邻槽」两级；③ 检测先用
`ComicInfo.xml` 的 `ImageWidth > ImageHeight`（横版单页）作为**只提示不自动合并**的信号；
④ 真机验收必须含"拼接前后同一 spread 的像素对比 + 内存采样"，先在两本测试书上扩测试集
（需要至少一本含横版对图的书）。

### D20 · 跨页对图合并：spread 局部自动合并（双栏限定，W22-R1）——**已落地**

**结论**：在**双栏**模式下自动把"被拆成两张扫描"的横版对图拼回一整幅；不命中 / 渲染失败时保持 W4
的两页渲染。**不新增偏好键**（沿用现有阅读模式 / RTL 开关），不引入任何依赖。相对 D19 的更新依据：

1. **D19 最大的顾虑（误判让整本书配对错位）在新方案里不成立**：合并不重排页链、不改页数、不改
   spread 划分——只是把当前槽位的两页画进同一张位图。误判最多影响这一屏观感，页号 / progression /
   恢复位置 / 书签语义全部不变（D16、D17 继续成立）。
2. **新素材复核把"检测不可靠"具体化**（§7.9）：金田一原画 PDF 5006 页里 4768 页是**横版整页**
   （每页就是一张已含中缝的对开图，抽样 16/20 页中央有 3.7%–11.1% 的居中白带），68 页是竖版封面单页。
   也就是说这份素材**没有"被拆成两张"的样本**——合并对它无触发点；能合并的对象必须形如
   "两张竖版半页并排 ≈1.41 的横幅对开"。
3. **判定口径按真素材标定**（256 px 缩略图、内缘 2 列亮度带）：真对图相关系数 0.52–0.93，
   独立页强切（对抗性反例）≤0.74，素材真实中缝与两本 CBZ 的真实相邻页全部落空 → 取保守门槛
   （宁可漏拼不误拼）。
4. **内存不重建窗口**：合并位图长边仍 ≤ [PAGE_BITMAP_MAX_SIDE_PX]（2048，双栏下长边 = 整幅宽度），
   PDF 用 ARGB_8888（≤11.8 MB/张）、CBZ 用 RGB_565（≤5.9 MB/张），`LruCache` 只放 **2 张**
   （当前槽 + 邻槽）；判定用 256 px 缩略图（各 ≈0.2 MB，用完立刻回收）。与单页 3 张窗口同量级，
   不随页数增长。
5. **三档模式 / RTL 的关系**：
   - **滚动**：纵向连续阅读，不合并（列表项 = 页，合并会让索引与进度错位）；
   - **分页**：一屏一页，不合并（要改页链配对，即 D19 的语义耦合）；
   - **双栏**：唯一合并入口；RTL（右起）时"先读的页在右"——合并位图右半 = 先读页，中缝相位与
     W9 的 `spreadPageSlots` 完全一致（`spreadPieceOrder` / `spreadInnerEdge` 单测锁住）；
   - 封面 / 尾页单张：合并只在"两页都存在"的槽位发生，尾页单张自动不合并。
6. **CBZ 适用性**：与 PDF 共用同一判定（CBZ 用 `inJustDecodeBounds` 的自然尺寸 + 解码位图取样），
   `ComicInfo.xml` 不作为信号（两本测试书只有 `FrontCover` / `Letters` 两个 Type，实测不可靠）。
   限制：CBZ 走 `BitmapFactory` 2 的幂降采样，合并图可能比 PDF 路径更软（PDF 是矢量按比例渲染），
   且带纸边（内缘纸白）的对图不合并（见 §9 遗留）。

落地：`SpreadMerge.kt`（纯函数：几何门槛 / 内缘取样 / 中缝证据 / 槽位相位 / 合并尺寸）+
`SpreadMergeCache.kt`（缩略图判定 → 两半统一高度合成 → 2 张 LRU → 邻槽预取 → 失败回退）+
`SimpleBookView.kt`（只在双栏挂 `MergedSpreadPage`，先在原位渲染两页、合并就绪后替换；命中打
`reader spread merge spread=… pages=… continuity=… corr=… diff=…` 日志供真机文本取证）+
`PageSource.pageSizePx`（PDF 页面点尺寸 / CBZ 位图尺寸，供几何门槛用；`pageAspectRatio` 改为默认实现）。

### D21 · W26：双栏「横版整页独占」+ 带纸边对图裁剪合并（W26-R1）——**已落地**

**背景**：W22 遗留①（金田一型横版整页在双栏被两两并排成一屏 4 页）与遗留②（两半各自留内缘纸白的对图
不肯合并）。W26 两项都落地，均不新增偏好键、不改页数 / spread 语义。

1. **横版整页独占（版式层，`SpreadLayout.kt`）**：双栏模式下，宽高比 ≥ `LANDSCAPE_FULL_PAGE_MIN_ASPECT`
   （1.15，与对开比例下限同源）的页单独占一个槽位（整屏 Fit、保持比例、不裁切；竖屏时按可用宽度铺满），
   不与相邻页配对；其余竖版页维持「两页一槽」与既有对图拼合。判定与布局是纯函数
   （`isLandscapeFullPage` / `twoColumnSlots` / `visualSlotPages` / `slotIndexForPage`），单测锁定。
2. **与 SpreadMerge 的优先级（固定为「先版式、后合并」）**：横版页先被版式层放进独占槽（1 页），不会进入
   「两页槽」，因此永远不会参与合并判定；合并层只在两页槽内工作，其几何门槛仍要求两个半页是竖版
   （0.55–0.98）。「两页槽不含横版整页」是两层之间的不变量（单测锁定）。灰区 0.98–1.15 的近似方形页维持
   W22 行为（配对渲染、不参与合并）。
3. **页号 / progression 语义不变**：槽位首页仍是进度锚点（`progressionForPage` 按逻辑页索引换算）；
   独占页翻页步进 = 1 页、普通槽 = 2 页；页指示显示实际页范围（独占 / 单张显示单页号，如「双栏 · 15/30」）。
   RTL 只改变槽位内左右相位与翻页方向，不改变划分。
4. **扫描时机**：进入双栏时后台扫描每页宽高比（`PageSource.pageAspectRatios()`，异常按竖版处理），
   完成前用 W22 固定两页划分占位；有横版页时 Pager 重建一次（按逻辑页回位），无横版页不重建。
5. **带纸边裁剪合并（判定层，`SpreadMerge.kt`）**：直接路径（W22 四门槛）优先；不通过时，若内缘为
   「纸白 / 低信息带」（列均值 ≥ 215 且列内标准差 ≤ 20），则向内找到第一列内容（上限 18% 半页宽），
   用内移后的采样带重算证据；裁剪路径要求相关性 ≥ 0.75、平均差 ≤ 0.15（比直接路径更严）。
   合成时按同一判据在全分辨率位图上重新检测纸边（失败用缩略图比例兜底），裁掉内缘后再拼接。
   黑边（均匀深色）不算纸白，避免误裁（踩坑 23 延续）。

### D22 · PDF 搜索：PdfBox-Android 流式文本层（W29-R1）——**已落地（真机待窗口）**

**选型**（D14 遗留「搜索 / 批注后置」的补课，评估口径 = 许可 / 体积 / 性能 / 与现架构的耦合）：

| 路线 | 结论 | 依据 |
|------|------|------|
| **PdfBox-Android 2.0.27.0** | ✅ 选用 | Apache-2.0（与 GPL-3.0 兼容）；纯 Java、**无 native / 无 ABI 体积**；`PDFTextStripper` 同时给逐页文本 + `TextPosition` 字符框（命中矩形要靠它）；`PdfRenderer` 继续负责渲染，两者互不干扰 |
| androidx.pdf | ❌ | 仍是 beta，公开 API 里没有文本提取 / 搜索，只有「整文档查看器」（与 D14 结论一致） |
| 有限自研解析 | ❌ | Content stream + ToUnicode CMap（尤其中文嵌入字体）自研成本远大于引入依赖，且 W4 已定「不引 JitPack 三方」 |

**依赖**：只加在 `modes/book/build.gradle.kts` + `gradle/libs.versions.toml`（本波唯一写入者）。
体积（未混淆构建，R8 关闭）：AAR 3.10 MB + 传递依赖 BouncyCastle 三件套 10.5 MB
（`bcprov-jdk15to18` 8.91 / `bcpkix` 0.98 / `bcutil` 0.65，仅证书加密路径使用）= **13.6 MB**；
**整包 A/B 实测（同机同命令，基线 `d972315` vs 本分支）：arm64 APK 102.40 → 115.45 MB，+13.04 MB**
（v7a 同样 +13.04 MB，与依赖件一致）；
如需瘦身可 `exclude` 三件套（−10.5 MB），代价是证书加密 PDF 走 `NoClassDefFoundError` 降级
（搜索报错、页面渲染与批注不受影响），**要真机验证后再做**（§9 遗留）。

**实现口径**：

- **内存**：`PDDocument.load(file, MemoryUsageSetting.setupMixed(8 MB))`——默认 `setupMainMemoryOnly`
  会把 639 MB 的《虚构推理》整份读进主内存；8 MB 主缓冲 + 溢出临时文件，与 EB-3「3 张页位图」同量级。
  文本层会话（`PdfBoxPageTextSource`）在首次搜索时按书常驻，换书 / 退出阅读页关闭（`PDDocument.close()`）。
- **线程**：PdfBox 非线程安全 → 页源内一把 `Mutex` 串行化；扫描在 `Dispatchers.IO`；
  取消（换关键词 / 关面板 / 离开阅读页）在**下一页边界**生效（回调里抛 `CancellationException`）。
- **流式 + 有界**：一遍从第 1 页扫到末页，逐页回调出结果（`PdfSearchEngine`）；单页 ≤
  `PDF_SEARCH_MAX_HITS_PER_PAGE`=6 条、整篇 ≤ `PDF_SEARCH_MAX_HITS`=400 条，达上限即停并标 `truncated`；
  进度每 `PDF_SEARCH_PROGRESS_STEP`=25 页刷新一次 → 结果列表用 LazyColumn + 稳定 key 增量追加，
  3649 页也不会把整表塞进一帧（「懒加载 / 分页」要求由此落地）。
- **命中矩形**：`TextPosition.getX()/getY()`（PDFBox 已按页面 `/Rotate` 折算）+ `getWidthDirAdj()/getHeightDir()`
  → 页面归一化矩形（与批注同一 `PageRect` 模型）；片段（含换行）与矩形按命中顺序配对；
  矩形匹配走「压缩文本」（去空白 + 大小写不敏感），所以同一词被换行拆开也能标出来。
- **无文本层**：整篇有效字符 < `PDF_SEARCH_MIN_TEXT_CHARS`=40 → UI 明确提示「该 PDF 没有文本层，无法搜索
  （可用矩形批注）」；扫描件搜索是「秒级空扫 + 明确提示」，不会卡住阅读页。
- **入口**：只对 PDF 开放（顶栏「搜索」）；结果点击 → 跳页 + 页面叠加命中矩形（`PageOverlay`）。

**本地性能取证**（桌面 JVM Apache PDFBox 2.0.27，与 Android 端口同源代码；只读 `test_files/`，
**未占用真机**；脚本为临时探针，未入库）：

| 素材 | 页数 / 体积 | 抽到字符 | 全量扫描 | 每页 |
|------|------------|---------|---------|------|
| attention_is_all_you_need.pdf（有文本层，英文论文） | 15 页 / 2.2 MB | 33,486 | 2.28 s | **152 ms** |
| W22-Spread-Test.pdf（图片页，无文本层） | 30 页 / 36 MB | 0 | 33 ms | **1.1 ms** |
| 金田一原画 PDF（图片页） | 5,006 页 / 2.36 GB | 0 | 0.20 s | **0.04 ms** |
| 深入理解计算机系统（第三版）（扫描版） | 775 页 / 524 MB | 0 | 0.07 s | **0.08 ms** |

结论：**扫描件（虚构推理 / 金田一同型）搜索是秒级的「空扫 + 无文本层提示」**；有文本层的 PDF 成本与
页数线性（桌面 ≈0.15 s/页，15 页含首次加载与 JIT 预热），3649 页纯文本 PDF 会是分钟级，靠
「流式出结果 + 可取消 + 400 条上限」兜住体验。Android（ART + 设备 IO）一定慢于桌面，**真机实测待窗口**。

### D23 · 本地高亮批注：矩形选区 + 页面归一化锚点（W29-R1）——**已落地（真机待窗口）**

- **粒度 = 矩形选区 + 备注**（不是文字选择）：扫描件没有文本层也能用，也不依赖 Readium 的 Decoration 体系；
  批注层与搜索命中共用同一套页面叠加（`PageOverlay` + `PageOverlayGeometry` 的 Fit 数学）。
- **存储 = 本地文件**：`filesDir/reader/annotations/{itemId}.json`，一个 itemId 一个文件，原子写
  （`.tmp` + rename）；读失败 / 坏 JSON 回退空列表，不阻塞阅读页。**不写服务器**（服务器没有标准批注 API）、
  **不用 Room**（D4 / D11 同款策略，Room schema 是咽喉资源）。
- **文件格式（version 1，字段缺省 / 未知字段可读）**：

  ```json
  {
    "version": 1,
    "itemId": "<jellyfin itemId>",
    "annotations": [
      {
        "id": "5f0c…",
        "page": 12,
        "left": 0.10, "top": 0.22, "right": 0.48, "bottom": 0.31,
        "note": "备注文本（可为空）",
        "createdAtMs": 1759400000000,
        "updatedAtMs": 1759400000000
      }
    ]
  }
  ```

- **锚点口径**：`page` = 0-based 逻辑页索引（与页指示 / progression 同口径）；矩形 = 页面归一化 [0,1]、
  左上原点、按**渲染方向**（PdfRenderer 的页面尺寸，含 `/Rotate`）归一化；不随阅读模式 / 缩放 / RTL 变化
  （叠加层在页面内部、跟随 `ContentScale.Fit` 与缩放变换）。
- **交互**：顶栏「批注」→ 面板（不依赖文本层）；「开始框选」进入批注模式（页面上单指拖动框选，
  单指翻页暂停、双指缩放暂停），松手弹备注输入；非批注模式单击已有框 → 编辑 / 删除；列表支持跳转 / 删除。
- **兼容边界（要求记录）**：
  - **RTL**：只改渲染相位，页索引与归一化矩形不变；
  - **双栏（未合并槽）**：每页各画自己的矩形，天然正确；
  - **合并槽位（W22/W26 对图拼合）**：合并位图是两页拼出来的，本波**不叠加**批注 / 命中矩形
    （数据仍按逻辑页保存，切回单页 / 分页 / 未合并槽即可见；映射方案见 §9 遗留）；
  - 后续若要改粒度（文字选择 / 手绘 / 导出）：新增字段或抬 `version`，旧记录按矩形继续可读。

### D24 · W48：SAF `content://` PDF 接 PdfBox 随机读（页缓存）+ 大书兜底阈值——**已落地**

**背景**：W33 的 `PdfLayoutSource` 只覆盖本地缓存文件路径；W37 本地媒体库的书籍是 SAF `content://`
（`ReaderViewModel.openLocalDocument` → `PdfPageSource(descriptor)`），`layout = null` → 进双栏回退逐页
`PdfRenderer.openPage` → W47 在金田一 5006 页（2.53 GB）上复现 native 爆增（1.59 GB / PSS 2.45 GB，进程被
MIUI 杀死）。

**决策**：
1. **SAF fd → PdfBox 随机读**：`PdfLayoutSource.forDescriptor(descriptor)`——`ParcelFileDescriptor.dup()` 出
   **独立 fd**（原 fd 继续归 `PdfRenderer`，close 互不影响），`Os.pread` 定位读（不改共享文件偏移），构造期
   用「长度 > 0 + 8 字节定位读探针」判定可用性；不可 seek（管道 / 代理 fd，`pread` 抛 `ESPIPE`）直接返回
   null。载入走 `PDFParser(RandomAccessRead, ScratchFile)` + `getPDDocument()`（本 fork 没有
   `PDDocument.load(RandomAccessRead)` 重载），与 `PDDocument.load(file, …)` 同一 8 MB 主缓冲 + 溢出临时文件
   策略；`PDDocument.close()` 连带释放随机读源，未载入就 close 时由 `pendingSource` 兜底释放。
2. **4 KB 页缓存（真机拦下的必需项）**：首版直接「一次 read = 一次 `pread`」在真机上出现 **CPU 100% 数分钟
   不结束**——SAF fd 多为 FUSE 代理 fd，跨进程开销大，PdfBox 解析的小块读被打成系统调用风暴。改为
   `PagedPositionalReader`（4 KB 页 + 256 页 LRU，与 PdfBox 自带 `RandomAccessBufferedFileInputStream` 同口径），
   单字节 / 小块读合并成整页读后，2.53 GB / 5006 页在数秒内扫完（`PagedPositionalReaderTest` 锁定「连续 300
   字节只打 1 次底层读 + 跨页读 + EOF」）。
3. **大书兜底阈值**：批量路径不可用（fd 不开 / 不可 seek / PdfBox 解析失败）时，`PageSource` 新增
   `perPageAspectScanMaxPages`（PDF = `PER_PAGE_LAYOUT_SCAN_MAX_PAGES` = **1500**，CBZ 默认 `Int.MAX_VALUE`
   不受限）；`collectPageAspectRatios()` 对超阈值的大书**跳过逐页 `openPage` 回退**，直接返回安全默认（全
   null = 竖版两页一屏，即 W26 之前的配对口径），并打 `reader spread layout skip-fallback pages=… max=…`；
   小书仍逐页回退保正确性。取值依据：逐页 native 实测 0.19–0.31 MB/页，1500 页最坏 ≈0.3–0.5 GB 已接近
   杀进程线，不再批准更大的逐页扫描。
4. **边界**：渲染路径仍是 PdfRenderer（D14 不变）；版式扫描只在双栏挂载、惰性、随 `PdfPageSource.close()`
   释放；扫描期间维持 W22 固定两页占位版式，完成后按横版页独占重排。

### D25 · W49：对图判定「null 不缓存 + 可重试」（W49-R1）——**已落地**

**背景**：W48 真机（§7.16.3）在 LTR 冷启动 / 切 RTL 重建缓存瞬间观察到：某槽位的对图判定可能取到 null
（页面尺寸 / 缩略图取不到等瞬时失败，`runCatching` 把异常吞成 null）并被写进判定缓存，之后该轮不再复算；
同一页对在 RTL 相位 / 重建缓存后重新命中。判定链路（`pageSizePx` / `renderPage` / `spreadMergeDecision`）
本身不改，只把「未就绪」与「明确不合并」分开。

**修复**（`modes:book`，不新增偏好键）：

1. **判定结果分型**：`SpreadMergeDecisionResult` = `Ready(decision)`（判定链路完整跑完；`decision == null`
   表示几何 / 中缝证据**明确不合并**，可缓存）/ `NotReady`（页面尺寸 / 缩略图取不到，**不写缓存**）；
   `SpreadMergeDecisionMemo` 只记 `Ready`，纯 Kotlin + JVM 单测。
2. **调用侧重试**：`SpreadImageCache.shouldRetry(firstPage)`（未就绪 / 上次合成失败 = true；明确不合并或
   合并图已备好 = false）；`MergedSpreadPage` 首次失败后按 `SPREAD_MERGE_RETRY_DELAY_MS = 250 ms` 退避重试，
   上限 `SPREAD_MERGE_RETRY_ATTEMPTS = 3` 次；Pager 停稳的邻槽预取列表加入**当前槽**（`prefetch` 即「下一轮
   强制复算」）。
3. **不变式**：明确不合并仍然只判一次（保留 W22 的性能语义）；判定日志、2 张 LRU、合并几何与
   `pageAspectRatios` 扫描全部不动。

单测 `SpreadMergeDecisionMemoTest`（3 项）：先 `NotReady` 后 `Ready` 的重试路径（第二次重算并命中、命中后
不再复算）、明确不合并缓存 null 结论、未就绪只影响当前页对。

## 3. 接口契约（已落地）

```kotlin
interface ReaderRepository {
    suspend fun ensureLocalFile(itemId: UUID): File
    suspend fun downloadLocalFile(itemId: UUID, onProgress: (Float) -> Unit = {}): File
    suspend fun localFile(itemId: UUID): LocalBookFile?
    suspend fun deleteLocalFile(itemId: UUID)
    suspend fun getReadingProgress(itemId: UUID): ReadingProgress?
    suspend fun saveReadingProgress(itemId: UUID, progress: ReadingProgress)
    suspend fun flushPendingProgress(): Int
    suspend fun pendingProgressCount(): Int
    suspend fun getBookmarks(itemId: UUID): List<ReaderBookmark>
    suspend fun saveBookmark(bookmark: ReaderBookmark)
    suspend fun deleteBookmark(itemId: UUID, bookmarkId: String)
}

data class ReadingProgress(
    val itemId: UUID,
    val locatorJson: String = "",
    val progression: Double,
    val positionTicks: Long,
    val updatedAt: Instant,
    val pendingSync: Boolean = false,
    val runtimeTicks: Long = 0L,   // Jellyfin RunTimeTicks 缓存（离线换算用）
)
```

- 接口位于 `data/repository/ReaderRepository.kt`，实现
  `ReaderRepositoryImpl` 位于 data 模块；Hilt 绑定在
  `modes/book/.../BookModule.kt`（W0 预留的 DI 扩展点）。
- 写路径：本地 JSON 先落盘（`pendingSync=true`）→
  `POST /UserItems/{itemId}/UserData` 成功且记录未被后续翻页覆盖时标记同步
  （`markSyncedIfUnchanged`）；失败保留待同步标记。
- 读路径：`GET /Users/{userId}/Items/{itemId}/UserData` + 本地 Locator
  → `resolveReadingProgress(...)` 合并。
- 下载路径：`GET /Items/{itemId}/Download` → `filesDir/books/{itemId}.book.part`
  → rename 成 `{itemId}.book`；重复打开命中缓存不再打网络（离线可读）。
- 书签：`filesDir/reader/bookmarks.json`（`ReaderBookmarkStore`），与进度互不覆盖。

## 4. 进度同步与服务端行为

### 4.1 实测接口

| 操作 | 接口 | 结果 |
|------|------|------|
| 读 | `GET /Users/{userId}/Items/{itemId}/UserData` | 200，返回 ticks / percentage / played |
| 写 | `POST /UserItems/{itemId}/UserData` | 200，写入后再次 GET 可见 |
| 取书 | `GET /Items/{itemId}/Download` | 206 / `application/epub+zip`（支持 Range） |

### 4.2 ticks 换算（重要）

Jellyfin 服务端用 `PlaybackPositionTicks / RunTimeTicks × 100` 计算
`PlayedPercentage`。本服务器书籍 `RunTimeTicks = 10_000_000`（1 秒占位值），
因此 **不能**使用固定合成时间轴：

```text
positionTicks = progression × item.RunTimeTicks
```

W1 实现：`saveReadingProgress` 先读取该条目的 `RunTimeTicks`，再按上式换算。
实测写入 progression `0.003568879` 后，服务端返回
`PlayedPercentage = 0.35689`、`PlaybackPositionTicks = 35689`，一致。

### 4.3 上报时机（W1 实现 / W3 完善）

- W1：`onPageChanged` 去抖 2 秒后异步写本地 + 回传；失败保留 pending。
- W3（已实现）：
  - 打开恢复：`getReadingProgress` 最近时间戳合并；服务端胜出且位置不同时用
    `Publication.locateProgression` 重新定位；
  - 阅读中每 **30 秒**检查一次：位置有变化 → 落盘 + 回传；位置没变但仍有待同步记录 →
    重试回传（关掉飞行模式无需重开阅读页）；
  - 退到后台（`ReaderActivity.onStop`）→ 立即落盘 + 尝试回传；
  - 进程被杀 / 长时间离线 → `ReaderProgressSyncWorker`（`core/work`）调用
    `flushPendingProgress()`，由 `BaseApplication` 调度两条路径：**应用启动即尝试一次**
    （`readerProgressSyncNow`，进程被杀后重新打开应用就能补传）+ **15 分钟周期兜底**
    （`readerProgressSync`）；两条都带 `NetworkType.CONNECTED` 约束，
    没有待同步记录时只读一次本地 JSON、不发请求。

## 5. 学习笔记（ROLE_SKILLS §5.1 全表）

### 5.1 Readium Kotlin Toolkit 官网 / README

- 格式：EPUB 2/3 ✅、PDF ✅（需适配器）、CBZ 🚧、CBR ❓。
- 能力：EPUB 分页 / 滚动 / RTL / Decoration 高亮 / TTS ✅；PDF 搜索与高亮 👀。
- 最低要求：Readium 3.4.0 = minSdk 24 / compileSdk 37 / Kotlin 2.4.20 / Gradle
  9.7.0；本项目完全对齐。
- **必须启用 core library desugaring**；本项目 `app:phone` 已启用。
  Gradle 8.4+ / AGP 8.4+ 默认支持。

### 5.2 Readium PDF 指南

- PDF 支持由 `document` + `navigator` 两个适配器组成；官方提供 pdfium 与商业
  pspdfkit。pdfium 适配器通过 JitPack 引入第三方 `PdfiumAndroid` /
  `AndroidPdfViewer`。
- `PdfNavigatorFragment` 负责导航与 Locator 事件；引擎侧负责单页渲染。

### 5.3 androidx.pdf 发布说明

- 当前 beta：`androidx.pdf:pdf-viewer-fragment:1.0.0-beta01`（2026-08-26）。
- 模块：`pdf-viewer` / `pdf-viewer-fragment` / `pdf-document-service`；较新的
  alpha 还提供 Compose API 与 @ExperimentalPdfApi。
- 仍是 beta，暂不作为 W1 主路径。

### 5.4 PdfRenderer API

- `PdfRenderer(ParcelFileDescriptor)` 需要可 seek 的描述符；`openPage(index)` 从 0
  开始；`Page.close()` 回收；`close()` 时若还有页面未关闭会抛异常。
- 严格按页渲染是 EB-3 自研路线的基础。

### 5.5 W3C EPUB 3.3

- OCF 容器：`mimetype` + `META-INF/container.xml` 指向 package document。
- package document：metadata / manifest / **spine**；spine 定义默认阅读顺序。
- navigation document 是 XHTML 特例，提供 toc / page-list / landmarks。
- 固定版式与 reflowable 由 rendition 属性控制；阅读进度定位依赖 spine + CFI /
  Locator。

### 5.6 java.util.zip / ZipInputStream

- `getNextEntry()` 顺序读条目，`closeEntry()` 跳到下一个；适合流式读 CBZ，但
  随机访问不如 `ZipFile`。
- Android U+ 禁止含 `..` 或绝对路径的条目名；CBZ 自研需做路径校验。

### 5.7 Mihon（Apache-2.0）

- 成熟漫画阅读交互参考：多种查看器、阅读方向、RTL / 双页 / 裁边、本地阅读、
  主题与离线缓存。
- 仅借鉴交互与架构思路；若复制代码需保留 Apache-2.0 声明。

## 6. W1 任务清单

- [x] 版本目录独立提交（Readium 3.4.0 + JUnit，`f30f0fb`），并报告负责人。
- [x] `:modes:book` 集成 Readium，`AndroidView` 承载 `EpubNavigatorFragment`。
- [x] 真机打开 Books 库真实 EPUB《雷普利全集》，分页与滚动模式可切换。
- [x] `ReaderRepository` / `ReadingProgress` 数据模型落地。
- [x] UserData 进度读写实现（GET + POST，仅用户数据白名单）。
- [x] 本地 Locator 精确定位存储（W1 文件版，W3 迁 Room）。
- [x] data 模块 JVM 单测：progression 夹取 / ticks 换算 / 最近时间戳合并。
- [x] `:app:phone:compileLibreDebugKotlin` + `ktfmtCheck` 通过。
- [x] 真机验证记录（见 §7）写入本文件。

### W2 任务清单（R1-CORE，2026-09-30）

- [x] 阅读模式三档：滚动 / 分页 / 双栏（Readium `scroll` + `columnCount`）。
- [x] 平板双栏：Pad 5 横屏真机确认左右两栏渲染、翻页与进度正常。
- [x] 排版设置：字号 / 行距 / 边距 / 内置字体 / 对齐，即时生效。
- [x] 阅读主题：纸色 / 护眼 / 深色 / OLED / 跟随，独立于主 App 主题。
- [x] `AppPreferences` 追加 `pref_reader_*`，退出重进保持（真机 force-stop 复测）。
- [x] `modes/book` JVM 单测 9 项（模式 / 主题 / 颜色 / 字体 / 对齐 / 裁剪 / 回退 / 强调色 / 循环）。
- [x] `:modes:book:testDebugUnitTest`、`:app:phone:assembleDebug`、`ktfmtCheck` 通过。
- [x] W2 真机验证记录写入 §7.4，踩坑写入 §8。

### W3 任务清单（R1-OFFLINE，2026-09-30）

- [x] ① 离线阅读（EB-11）：整书下载到应用私有目录（`.part` + rename 原子落盘、进度回调、
      失败清理半截文件）、阅读页下载状态（下载 / 下载中 % / 离线可读 · 体积 / 重试）、
      离线命中缓存不再打网络。
- [x] ② 进度同步（EB-9）：离线暂存 + 联网回传（阅读中 30 秒 / 退后台 / WorkManager 周期
      约束回传 + 启动即补传）、`RunTimeTicks` 缓存修正离线 ticks、待同步标记只清当前记录、
      多设备冲突按最近时间戳 + locator 近似相等才保留。
- [x] ③ 书签基础（EB-8）：`ReaderBookmark` 模型 + JSON 存储 + 面板（添加 / 跳转 / 删除）。
- [x] 单测：data 10 项（进度合并 / ticks / 书签编解码，其中 7 项本波新增或改写）与
      modes/book 5 项（书签标签 / 百分比 / 体积）。
- [x] `:data:testDebugUnitTest`、`:modes:book:testDebugUnitTest`、
      `:app:phone:assembleDebug ktfmtCheck` 通过。
- [x] W3 真机验证记录写入 §7.5，踩坑写入 §8。

### W4 任务清单（R1-PDF-CBZ，2026-10-01）

- [x] ① PDF 支持（EB-3）：三条路线 spike → 定稿 PdfRenderer 自研（D14）；`PdfPageSource`
      （按页渲染 + Mutex + Matrix 缩放 + 白底）与 `PageImageCache`（LRU 3 张）落地。
- [x] ② CBZ 支持（EB-4）：勘定 Andas_Game 解析失败根因（D15，Readium 全包扩展名白名单 + 无扩展名
      缓存文件）；`ComicPageSource`（ZipFile 随机读 + 自然序页序 + 降采样解码）落地。
- [x] ③ 三档模式（EB-5 扩展到 PDF / CBZ）：`SimpleBookView` 滚动 / 分页 / 双栏 + 页指示文字；
      双指缩放（1×–4×）在分页 / 双栏模式生效。
- [x] ④ 格式嗅探与进度语义（D16）：按内容识别（PDF / EPUB / CBZ）+ 页索引 ↔ progression 换算，
      复用 EB-9 落盘 / 回传链路；readium 路径（EPUB）零行为变化。
- [x] 单测：格式嗅探 6 / CBZ 页序与过滤 4 / 尺寸·进度·页指示·缩放夹取 6，共 **16 项新增**；
      `:modes:book` 合计 **31 项**（5 个测试类）全绿（`:modes:book:testDebugUnitTest`）。
- [x] 门禁：`:app:phone:assembleDebug` + `:modes:book:testDebugUnitTest` + `ktfmtCheck` 通过。
- [x] 真机验证记录写入 §7.6（五本书逐本验收 + 三档模式 + 内存数据 + EPUB 回归 + 进度链路），
      并于完成后按调度立即释放设备。

### W9 任务清单（R1-COMIC-DIRECTION，2026-10-01 · 分支 `feature/w9-reader-comics`）

- [x] ① RTL 右起翻页开关（EB-4）：`ReaderSettings.rtl` + `pref_reader_rtl`（`AppPreferences` 只追加）；
      `SpreadOrder.kt` 页序层（`spreadPageSlots` / `spreadCount` / `isRtlPaging`）+ `SimpleBookView`
      镜像（分页 / 双栏 `reverseLayout` + spread 内左右交换）；面板开关只在 PDF / CBZ 出现；
      滚动模式行为不变（D17）。
- [x] ② 跨页对图合并：完成可行性研判，结论 = 本波不实施（内存 2× / 无可靠元数据 / 接缝与语义耦合），
      交付正确页序 + 方案记录（D19），计划在 W5 以「双栏 + 显式开关 + 手动标记」形态试点。
- [x] ③ 滚动模式手势缩放：`ZoomablePage` 下放到滚动列表的每个页项（双指优先、单指不消费）；
      与分页共用 `PageZoom` 状态机（1×–4×、平移夹取、非有限值守卫）；带 `Timber` 调试轨迹（D18）。
- [x] 单测：`SpreadOrderTest` 8 项（RTL 槽位 / 尾页空槽 / 越界 / RTL 只作用横向 / 页指示后缀）+
      `PageMetricsTest` +3 项（缩放夹取、平移夹取、非有限手势）+ `ReaderSettingsTest` +1 项
      （右起开关不进入 EPUB 偏好快照），合计 **12 项新增**；`:modes:book` 合计 **43 项**（6 个测试类）。
- [x] 门禁：`:app:phone:assembleDebug` + `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（23 项）+
      `:modes:book:testDebugUnitTest`（43 项）全绿。
- [x] 真机验证记录写入 §7.7（K60 `8e875894`：CBZ RTL 三档方向 / 滚动双指缩放 / 跨页结论 / PDF RTL /
      EPUB 回归 / 稳定性与还原；新发现「阅读页顶栏被状态栏压住」记入 §7.7.5 与踩坑 20）。

### W22 任务清单（R1-SPREAD，2026-10-02 · 分支 `feature/w22-spread-merge`）

- [x] ① 可行性复核（更新 D19）：新素材结构清点（`pypdf` 只读）+ 判定算法在真素材上的阈值标定
      （§7.9）；结论 = **自动行为、不新增偏好键、限定双栏槽位**（spread 局部合并 → 无配对错位风险）。
- [x] ② 判定 / 几何纯函数（`SpreadMerge.kt`）：几何门槛（竖版半页 + 2% 高度容差 + 1.15–2.05 对开比例）、
      内缘亮度取样、中缝证据（连续性 / 起伏 / 相关系数 / 归一化平均差）、双栏槽位相位（含 RTL）、
      合并高度与半页宽度。
- [x] ③ 合并渲染（`SpreadMergeCache.kt` + `SimpleBookView.kt`）：256 px 缩略图判定 → 命中才渲染两半并
      合成（长边 ≤2048、统一高度、左右相接、失败回退两页）、2 张 LRU、邻槽预取、命中日志。
- [x] ④ 覆盖 PDF 与 CBZ 的适用性说明（D20 第 6 条）：两者共用判定；CBZ 的分辨率上限与
      「`ComicInfo.xml` 不可作信号」写清；素材未上传 → 无真机样本。
- [x] ⑤ 单测 14 项（`SpreadMergeTest`：几何 / 高度 / 宽度 / 取样 / 证据 / 四门槛 / 相位 / 模式限定）；
      `:modes:book` 合计 **57 项**（7 个测试类）全绿。
- [x] ⑥ 门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest` +
      `:modes:book:testDebugUnitTest` 通过。
- [x] ⑦ 自造「拆页型对图」测试书（`tools/w22-spread-test/make_spread_test_book.py`）：PDF 36.1 MB /
      CBZ 34.6 MB × 30 页（8 对图 + 5 独立单页对 + 1 低相似 + 1 横版整页）；离线自测 LTR / RTL ×
      PDF / CBZ 四组全部 **对图 8/8 命中、0 误判**（§7.9.4；素材在 `test_files/`，不入库）。
- [x] ⑧ 真机验证（Pad 5 `43af8627`，负责人指派窗口 16:30–16:54）：PDF 15 槽位逐屏 —— 8 对图
      **全部命中**、7 负样本 **0 误拼**；CBZ 冷启动倒序复验同样 8 命中；中缝空带 0 px vs 307 px、
      即时帧无两页闪动；内存 A/B **+12.0 MB ≈ 1 张合并位图**、冷启动稳态 327.7 MB；RTL/页序/进度
      不回归；3649 页真实漫画 20 屏无误拼；无 FATAL/ANR。详见 §7.10。

### W26 任务清单（R1-FORMS，2026-10-02 · 分支 `feature/w26-reader-forms`）

- [x] ① 横版整页双栏独占：版式纯函数 `SpreadLayout.kt`（阈值 1.15 / 槽位划分 / 视觉相位 / 页→槽映射 /
      `pageAspectRatios` 扫描）+ `SimpleBookView` 双栏接入（扫描完成前占位、完成重建 Pager、独占槽
      fillMaxSize 不裁切）+ 页指示按实际页范围（`PageMetrics.pageIndicatorText(lastPageIndex)`）。
- [x] ② 带纸边对图：`SpreadMerge.kt` 列统计 / 纸白判据 / 裁剪上限 / 裁剪路径决策；`SpreadMergeCache` 合成
      时全分辨率重检 + 比例兜底 + 源范围纯函数（左半裁右缘、右半裁左缘）；命中日志追加 `trim=a/b`，
      合成日志 `reader spread compose`。
- [x] ③ 单测：`SpreadLayoutTest` 9 项（阈值 / 划分 / RTL 相位 / 映射 / 与合并优先级不变量）+
      `SpreadMergeTest` +8 项（列统计 / 纸白判据 / inset 取样 / 源范围 / 裁剪命中与四类负样本）；
      `:modes:book` 57 → **77** 项。
- [x] ④ 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（51）+
      `:modes:book:testDebugUnitTest`（77）全绿。
- [x] ⑤ 素材：脚本扩展 `--profile w26` 生成 `W26-Spread-Edge-Test.pdf/.cbz`（44 页 = 8 对图 + 5 独立单页对 +
      1 低相似 + 1 横版整页对 + 4 带纸边对图 + 3 带纸边负样本）；离线自测 LTR / RTL × PDF / CBZ
      **12/12 命中、0 误判**。
- [x] ⑥ 真机（Pad 5 `43af8627`）：横版独占页号计数 + 与分页渲染像素一致；W22 8/8 命中回归（新进程全走）；
      W26 44 页 12/12 命中（含 4 条 `trim=` 裁剪路径）、负样本 0 误拼；RTL / progression / 无 FATAL 全过。
      真机拦下并修复两个缺陷（页指示 0/1-based 错位、合成裁剪方向写反）。详见 §7.11。

### W29 任务清单（R1-SEARCH-ANNOT，2026-10-02 · 分支 `feature/w29-pdf-search-annot`）

- [x] ① PDF 搜索选型（D22）：PdfBox-Android 2.0.27.0（Apache-2.0，无 native）对比 androidx.pdf /
      有限自研；依赖只加 `modes/book/build.gradle.kts` + `libs.versions.toml`；本地素材性能取证见 §7.12。
- [x] ② 流式搜索链路：`PdfBoxPageTextSource`（8 MB 主缓冲 + 临时文件、Mutex 串行、逐页流式、
      空页补齐）+ `PdfSearchEngine`（单页 ≤6 / 整篇 ≤400、达上限即停、进度每 25 页、可取消）+
      `PdfSearchUiState`（去抖 400ms、结果增量追加）。
- [x] ③ 搜索 UI：顶栏「搜索」→ 面板（关键词输入 + 状态行 + 命中列表页码/片段 + 命中词高亮）→
      点击跳页 → 页面 `PageOverlay` 叠加命中矩形；无文本层 / 上限 / 取消 / 失败都有明确文案。
- [x] ④ 本地高亮批注（D23）：`ReaderAnnotation` + `ReaderAnnotationCodec`（version 1 JSON）+
      `ReaderAnnotationStore`（`filesDir/reader/annotations/{itemId}.json`，原子写）；矩形框选 + 备注输入、
      批注列表（跳转 / 删除）、单击已有框编辑 / 删除；批注模式单指框选（翻页 / 缩放在此模式暂停）。
- [x] ⑤ 兼容性与边界：RTL / 双栏（未合并槽）直接复用归一化矩形；合并槽（W22/W26 对图拼合）不叠加，
      数据仍按逻辑页保存；不依赖文本层（扫描件可用）；CBZ 未开放（同一页模型，留作后续）。
- [x] ⑥ 单测 24 项：`PdfSearchTest` 12（归一化 / 片段 / 字符框 / 配对 / 上限 / 流式 / 无文本层 / 空查询 /
      取消 / 进度）+ `ReaderAnnotationTest` 8（夹取 / 最小尺寸 / 摘要 / 编解码往返 / 兼容旧文件 /
      存储增改删 / 截断）+ `PageOverlayGeometryTest` 4（Fit 数学 / 退化尺寸 / 正反映射 / 越界夹取）。
- [x] ⑦ 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest` +
      `:modes:book:testDebugUnitTest`（**101 项**）全绿。
- [x] ⑧ 真机走查（Pad 5 `43af8627`，负责人指派窗口）：搜索命中 65 条 / 跳转 + 命中矩形像素取证、
      无文本层提示、非 PDF 无入口、3649 页文档 ~1.3 ms/页（无文本层）+ 退后台取消、批注增删改与列表
      跳转、RTL / 双栏相位、合并槽 A/B 0 差异；**真机拦下 1 个搜索崩溃缺陷（重复 key）已修复复验**；
      另顺带发现 W26 版式扫描在大文档上的 native 内存膨胀（交 W26 线）。详见 §7.13。

## 7. 真机验证记录（2026-09-30）

设备：Xiaomi Pad 5（`nabu`，型号 21051182C），Android 13，2560×1600。

### 7.1 打开与渲染

```text
adb shell am start -W -n io.github.zhangwenkang.aurorama.debug/\
  com.zhangwenkang.cinefin.book.presentation.reader.ReaderActivity \
  -e itemId 8109915342fc71c405da49d632fc6978
```

- `Status: ok`、`LaunchState: COLD`、ReaderActivity 获得焦点。
- `uiautomator dump` 显示 `EpubNavigatorFragment` 的
  `resourcePager` / `webView` 节点，WebView 内出现 EPUB 正文文本。
- logcat 出现 Readium 内容页的 Chromium 控制台日志（远程字体被 CORS 拦截），
  证明真实 EPUB 已渲染；正文使用内置字体不受影响。

### 7.2 翻页与滚动

- 连续左滑后，本地 locator 从 `5o6jvk_z_split_002.html` 变为
  `5o6jvk_z_split_004.html`，`totalProgression` 从 `0.0021413` 增至
  `0.0035689` —— 翻页有效。
- 点击右上角模式按钮，`uiautomator` 标签由「翻页模式」变为「滚动模式」——
  `EpubPreferences(scroll=true)` 生效，滚动模式可用。

### 7.3 进度读写

- 写入后服务端 `UserData` 返回：
  `PlayedPercentage=0.35689`、`PlaybackPositionTicks=35689`、
  `LastPlayedDate=2026-09-30 12:42:54`。
- 本地 `files/reader/progress.json` 记录同一 progression 与 locator，
  `pendingSync` 在回传成功后变为 `false`。

### 7.4 W2 阅读模式 / 排版 / 主题（2026-09-30 21:24–21:38，Pad 5）

设备：Xiaomi Pad 5（`nabu`，Android 13，2560×1600，横屏）；adb 文本命令优先，
截图仅用于双栏 / 底色判断（本地查看后即删，未贴回对话）。

| 项 | 操作 | 结果 |
|----|------|------|
| 面板完整性 | 顶栏点「Aa」 | 「阅读设置」面板显示模式 / 字号 / 行距 / 边距 / 字体 / 对齐 / 主题全部字段 |
| 面板滚动 | 面板内上滑 | 修复后 5 个主题缩略图（纸色 / 护眼 / 深色 / OLED / 跟随）全部可见 |
| 双栏 | 点「双栏」 | 顶栏显示「双栏」；截图确认左右两栏分页渲染，中间栏距正常 |
| 字号即时生效 | 字号滑块 100% → 160% | 面板值即时变 160%，正文即时放大且保持双栏 |
| 纸色主题 | 点「纸色」 | 正文底采样 `#FBF6EC`，顶栏同底，深墨正文 |
| 滚动 + OLED | 点「滚动」+「OLED」 | 顶栏显示「滚动」，连续滚动单栏；正文底 `#000000`、顶栏底 `#191F28` |
| 分页 + 护眼 | 点「分页」+「护眼」 | 顶栏显示「分页」，单栏分页；正文底 `#E7EFE1` |
| 深色主题（默认） | 点「深色」 | 正文底 `#151A21` |
| 持久化 | 双栏 + 纸色 + 160% 后 `force-stop` 重启 | 顶栏仍「双栏」、字号仍 160%、正文底仍 `#FBF6EC`；退出重进保持成立 |
| 进度回归 | `run-as ... cat files/reader/progress.json` | `progression=0.003568879`、`pendingSync=false`，W1 进度链路无回归 |
| 崩溃 | `adb logcat -d` 过滤 | 无 FATAL / `E cinefin` |

> 验证后已把设置恢复为默认（滚动 / 深色 / 100% / 行距 1.2 / 边距 1.0 / 默认字体 / 两端对齐）
> 并释放 device lock。截图临时文件位于 `%TEMP%\cinefin-r1-*.png`（未入库、未贴回对话）。

### 7.5 W3 离线 / 进度同步 / 书签（2026-09-30 22:19–22:5x，Pad 5）

设备：Xiaomi Pad 5（`nabu`，Android 13，2560×1600 横屏）；adb 文本命令为主，截图仅 1 张用于确认
离线正文渲染（本地查看后删除，未贴回对话）。书籍：Books 库《雷普利全集》
`81099153-42fc-71c4-05da-49d632fc6978`（EPUB，2.2 MB）。

> 设备占用纪律：本会话到达时 W3-R3 已登记占用（22:05–22:20），按规程排队等待，22:21 登记后开始验证。
>
> 本表在 rebase 到 `f283ef1` **之前**完成：当时 manifest 仍是 `exported=true`，用 adb 显式启动
> `ReaderActivity`。R3 的 Prism 阅读页外壳与 `exported=false` 在本会话 rebase 时合入（外壳保留、
> 离线功能并入，见 §10 变更日志）。

| 步骤 | 操作 | 结果 |
|------|------|------|
| 在线打开 | `am start -W … ReaderActivity -e itemId …` | `Status: ok`、`LaunchState: COLD`、`TotalTime 1932 ms`；顶栏「离线可读 · 2.1 MB」，命中 `files/books/{itemId}.book` 缓存 |
| 进度基线 | 本地 `progress.json` | `progression=0.003568879…`、`positionTicks=35689`、`pendingSync=false`、`runtimeTicks=10000000` |
| 服务端基线 | `GET /Users/{id}/Items/{id}/UserData` | `PlaybackPositionTicks=35689`、`PlayedPercentage=0.35689`、`LastPlayedDate=14:19:48Z`（22:19:48 CST） |
| 飞行模式 | `cmd connectivity airplane-mode enable` | `airplane_mode_on=1`；`ping jellyfins.…` → `unknown host` |
| 离线冷启动 | force-stop → `am start -W` | `Status: ok`、`COLD`、`TotalTime 1921 ms`（未先卡超时）；顶栏「离线可读 · 2.1 MB」+ 横幅「离线暂存 1 条进度，联网后自动回传」；截图确认正文正常渲染（飞行模式图标可见） |
| 离线继续阅读 | 滚动一屏 | 本地进度 → `position=7`、`totalProgression=0.004282655246252677`、`pendingSync=true`、`positionTicks=42827`（= 0.00428265×10⁷，**离线 ticks 正确**，旧逻辑会算出约 4 286 万） |
| 阅读页内回传 | 关闭飞行模式，等 30 秒 ticker | `progress.json → pendingSync=false`，`updatedAt` 保持离线写入的时间戳（未重写内容） |
| 服务端确认 | `GET …/UserData` | `PlaybackPositionTicks=42827`、`PlayedPercentage=0.42827`、`LastPlayedDate=14:22:07Z`（22:22:07 CST） |
| 退出重进恢复 | force-stop → 冷启动 → 向上滚一屏 | 回滚后仍在第 4 章（`…004.html`）`position=6`：说明冷启动恢复到了章节中段 `position=7`，若从头打开回滚会落到 `…003.html`；新进度在线同步成功 |
| 书签添加 | 顶栏「书签」→「添加当前页书签」 | `files/reader/bookmarks.json` 新增 `label="1 · 0.4%"`、`progression=0.003568879…` |
| 书签跳转 | 先下滚两屏（`position=8`、0.0049964），再从面板点该书签 | 回到 `position=6`、`progression=0.003568879…`，并即时回传 |
| 后台回传兜底 | 飞行模式下滚动一屏 → `force-stop`（记录 `pendingSync=true` 留存）→ 关闭飞行模式 → 重新打开应用主界面（**不进阅读页**） | logcat：`WM-WorkerWrapper: Starting work for …ReaderProgressSyncWorker` → `Worker result SUCCESS`；`progress.json → pendingSync=false`；服务端 `LastPlayedDate=14:28:35Z`（22:28:35 CST）与 Worker 运行时刻一致 |
| 稳定性 | `adb logcat -d` 过滤 `FATAL / ANR / E cinefin` | 无应用崩溃（命中的 AndroidRuntime 日志来自 `uiautomator` 命令自身） |

> 截图临时文件位于 `%TEMP%\cinefin-r1-w3-offline.png`：本会话执行环境禁止 `Remove-Item`
> （策略拦截），未能删除；该文件未入库、未贴回对话。
>
> 真机过程中发现并修掉一个设计缺口：WorkManager 周期任务用 `KEEP` 策略时，应用重启不会立刻
> 补传（要等下一个 15 分钟周期）；补了「启动即尝试一次」的一次性任务后，重新打开应用即可补传
> （上表最后一行即验证记录）。

### 7.6 W4 PDF / CBZ 真机验证（2026-10-01，Pad 5）——**完成**

设备：Xiaomi Pad 5（`43af8627`，Android 13，2560×1600 横屏，手势导航），分支 `feature/r1-pdf-cbz`
（安装后核验 `classes3.dex` 含 `sniffBookFormat` / `SimpleBookView`，排除"跑在别人的构建上"）。
入口链路统一为「媒体库 → 书籍 → 点书 → `ReaderActivity`」，读取的缓存书均为服务端真实条目
（虚构推理用 adb 只读预置，字节数 `670,643,292` 校验一致）。

**7.6.1 逐本验收结论**

| 书 | 格式 / 规模 | 结果 |
|----|------------|------|
| attention_is_all_you_need | PDF / 2.2 MB / **15 页**（与 `pypdf` 本地读数一致） | ✅ 打开（「离线可读 · 2.1 MB」、无书签按钮）；分页 `1/15 → 2/15`；双栏 `1-2/15`（截图确认左右两页并排：标题页 + 正文第 2 页）；滚动连续滚动 `1 → 3 → 4 → 6/15` |
| 虚构推理 (2026) | PDF / 639.6 MB / **3649 页** | ✅ 打开（离线可读 · 639.6 MB）；分页连翻 40 页（`1/3649 → 41/3649`）无卡死；双栏 `41-42/3649`；内存见 7.6.2 |
| Anda's Game | CBZ / 16.5 MB / **24 页**（此前 Readium 报「解析书籍失败」） | ✅ 打开（离线可读 · 16.5 MB）；分页 `1/24 → 2/24`；双栏 `1-2/24 → 3-4/24`（截图确认左 `!cover.jpg`、右 `01.jpg` 为不同页）；滚动 `3/24 → 5/24` |
| futuristic_tales | CBZ / 703 KB / **4 页**（用户实测"三种模式都渲染为分页"） | ✅ 打开（滚动 `2/4` 恢复）；分页 `2/4 → 3/4`；双栏 `3-4/4`（截图确认左目录页、右正文页为不同页）；滚动 `3/4 → 4/4` |
| 雷普利全集（EPUB，回归） | EPUB / 2.1 MB | ✅ 打开：顶栏有「书签」按钮、UI 树存在 WebView 节点（Readium 渲染）；左滑翻页后 locator `5o6jvk_z_split_004.html → 005.html`、`totalProgression 0.003568879 → 0.009279086`、`pendingSync=false`，**无回归** |

> 三档模式判定口径：顶栏模式按钮文字 + 页指示（`滚动 · n/N` / `分页 · n/N` / `双栏 · n-m/N`）
> 与真实手势结果（横向翻页只换一页；双栏每次跨两页；滚动按可见页递增）。双栏另用截图核对
> 「左右是不同页」，避免"两栏同一页"假通过。

**7.6.2 PDF 内存数据（EB-3 硬约束）**

测量方法（可复现）：`adb -s 43af8627 shell dumpsys meminfo io.github.zhangwenkang.aurorama.debug`
取 **App Summary 的 TOTAL PSS**；单指左滑翻页，每次翻页后停 2 s 再采样（避免把渲染在途的瞬时值
当稳态）；静止复测为连续 5 次、间隔 5 s。

| 场景 | 页 / 采样 | TOTAL PSS（kB） |
|------|-----------|-----------------|
| attention（2.2 MB / 15 页） | 第 6 页附近静止 | 368,971 / 334,374 / 334,353（≈334 MB 稳态） |
| 虚构推理（639.6 MB / 3649 页） | 第 1 页起点 | 356,910 |
| 虚构推理 | 连翻 20 页（第 2–21 页，每页采样） | 321,257–406,692（均值 368,299） |
| 虚构推理 | 再翻 20 页（第 22–41 页，隔页采样） | 434,449–462,235（渲染在途的瞬时峰值） |
| 虚构推理 | 第 41 页静止复测 5 次 | **357,823–358,154**（稳定 ≈358 MB） |

`dumpsys meminfo` 分解（第 41 页）：Java heap 16.9 MB / **Native heap 121.5 MB**（≤3 张页面位图 +
pdfium 内部结构）/ **Graphics 156.1 MB**（全屏渲染缓冲，EGL mtrack 72.4 MB）/ Code 33.4 MB。

**结论**：① 内存由**固定渲染窗口**（3 张位图 + 全屏缓冲 + pdfium 结构）主导，**不随页号增长**——
第 22–41 页的采样区间与第 2–21 页同量级、静止值回落到 ≈358 MB；② **不随文档大小线性增长**——
640 MiB / 3649 页的《虚构推理》与 2.2 MB / 15 页的 attention 稳态 PSS 同为 334–358 MB 量级；
③ 翻页期间的瞬时峰值（最高 462 MB）来自渲染流水线在途，稍后回落，属可接受范围。EB-3 通过。

**7.6.3 进度链路附带验证（D16）**

`files/reader/progress.json` 五条记录 `pendingSync` 全部为 `false`（联网后自动回传成功）；
PDF/CBZ 的 progression 与页号精确对应：虚构推理 `0.0109619 × 3649 = 40`（第 41 页）、
Anda's Game `0.166667 × 24 = 4`（第 5 页）、futuristic_tales `0.75 × 4 = 3`（第 4 页）；
EPUB 仍走 Readium Locator（见上表）。说明「页索引 / 总页数」换算 + EB-9 回传链路成立。

**7.6.4 稳定性与未覆盖项**

- `adb logcat -d` 过滤 `FATAL / ANR / E cinefin`：无崩溃、无 ANR。
- **未覆盖**：双指缩放的整机手势（adb 无法注入标准多指事件，仅 `clampPageOffset` 单测覆盖）；
  RTL 开关、跨页拼接、滚动模式缩放（§9 已列为 W5 候选）；PDF/CBZ 排版的字号 / 行距等控件为
  EPUB 专属，页序列格式下暂为可点的空操作（§9）。
- 调度留档：本波真机窗口为负责人明确指派（W3-R3b 01:05 释放 → W4-R1 01:31 使用 → 完成后立即释放）；
  K60（`8e875894`）因 MIUI 无 SIM 卡限制「USB 安装」放弃，未产生任何验证数据。

### 7.7 W9 RTL / 滚动缩放真机验证（2026-10-01，Redmi K60 `8e875894`）——**完成**

设备：Redmi K60（`8e875894`，1440×3200，Android 13 / MIUI），分支 `feature/w9-reader-comics`；
入口 = App 内「媒体库 → 书籍 → 点书 → ReaderActivity」（`exported=false`，adb 不能直启）。
设备缓存（`run-as … ls files/books`）：`6bbbb0ce-7d2d-3500-ac6e-9b12054be028.book` 719,861 B
= futuristic_tales（CBZ / 4 页）、`e2d0c13d-…` 670,643,292 B = 虚构推理（PDF / 3649 页）、
`81099153-…` 2,220,485 B = 雷普利全集（EPUB）。

**7.7.1 RTL 三档行为（EB-4 / D17）**

| 步骤 | 操作 | 结果 |
|------|------|------|
| 装机核验 | `install -r phone-libre-arm64-v8a-debug.apk`（102,935,506 B）+ 打开 futuristic_tales | `Success`；顶栏「离线可读 · 703 KB」，页指示「滚动 · 1/4」；面板出现「翻页方向 / 右起翻页（漫画）」且带说明「分页 / 双栏右到左；滚动模式仍自上而下」 |
| 分页 LTR | 切「分页」后左滑 / 右滑 | 左滑 `分页 · 1/4 → 2/4`、右滑回 `1/4`（标准左到右） |
| 分页 RTL | 面板开「右起翻页」→ 关面板 | 页指示变「分页 · 1/4 · 右起」；右滑（手指左→右）`1/4 → 2/4 · 右起`、左滑回 `1/4`（方向镜像且页号仍是逻辑序） |
| 双栏 RTL 页序 | 切「双栏」（RTL 开）→ `screencap`；再关 RTL → `screencap` | 指示「双栏 · 1-2/4 · 右起」；两张截图裁剪内容区（y 200–2900）后按半屏比对：**RTL 左半 = LTR 右半、RTL 右半 = LTR 左半，灰度差 0.00/255；未镜像差 32.2/255** → RTL 双栏 = 右 2k+1、左 2k+2，整屏是 LTR 的精确水平镜像 |
| 滚动 + RTL | 回「滚动」（RTL 保持开）→ 单指上滑 | 指示仍「滚动 · 1/4 → 2/4」不带右起、纵向顺序不变（**滚动模式不受 RTL 影响**，与 D17 口径一致） |
| PDF + RTL | 打开虚构推理（PDF / 3649 页，缓存命中 639.6 MB） | 指示「分页 · 1/3649 · 右起」；右滑 `1/3649 → 2/3649`、左滑回 `1/3649` → RTL 对 PDF 同样生效 |
| 持久化 | 验证后 `force-stop` 检查 `shared_prefs` | `pref_reader_rtl=false`（还原后）、`pref_reader_mode=scroll`；说明开关经 `AppPreferences` 正常读写 |

**7.7.2 滚动模式双指缩放（EB-3 扩展 / D18）**

注入方式：adb `input` 不支持多指；`sendevent /dev/input/event7` 被 SELinux 拒（`Permission denied`）→
用临时 `app_process`（`InputManagerGlobal.injectInputEvent` + 2 指 `MotionEvent`）注入，工具在
`%TEMP%` 与本机 `/data/local/tmp`，验证后已删除、未入库。

| 步骤 | 操作 | 结果 |
|------|------|------|
| 滚动模式捏合放大 | 页 3 上双指张到 6× | logcat `reader zoom index=2 scale=1.16 → 1.32 → … → 2.92`（步进 ≥0.1 才打点），页面就地放大 |
| 缩放上限 | 再注入一次更大幅度捏合 | `reader zoom … scale=4.00`（最大值打点一次后不再变）→ 1×–4× 夹取在真机成立 |
| 不抢纵向滚动 | 缩放过程中与缩放后各做一次单指上滑 | 缩放中页指示停在 `2/4`（列表未被双指手势拖动）；缩放后单指上滑 `2/4 → 3/4`（纵向滚动未被吞） |
| 捏合缩小 | 双指由 6× 距离捏回 | `scale=4.00 → 1.68 → … → 1.03`、`offset=(0,0)`（缩到 1× 自动居中） |
| 分页模式对照 | 切「分页」重复捏合 + 单指翻页 | 同一手势 `scale` 最大 **4.00**；放大后单指滑动仍翻页（`分页 · 1/4 → 2/4 · 右起`）→ 两模式共用 `PageZoom` 口径一致 |

**7.7.3 跨页拼接结论的可见性核对（D19）**

两本测试书逐页尺寸（本地只读解包）：futuristic_tales = 654×1040 + 676×1040×3；
Anda's Game = 800×1280（封面）+ 1327×2039×23，ComicInfo.xml 只有 `FrontCover` / `Letters` 两个 Type，
**没有任何横版页**。即：现有测试集不含「被拆成两张的跨页对图」，跨页合并既无样本可验证、
也无元数据可判定 → D19「本波不实施、先交付正确页序 + 方案记录」成立（真机部分只核对到页序正确）。

**7.7.4 EPUB 回归与稳定性**

| 项 | 结果 |
|----|------|
| 打开 | 雷普利全集（离线可读 · 2.1 MB）UI 树存在 WebView 节点，顶栏有「书签」按钮 |
| 阅读设置面板 | EPUB 下**没有**「翻页方向 / 右起翻页」行、无开关（RTL 只对 PDF / CBZ，符合 D17） |
| 翻页 + 进度 | 分页左滑后 locator 落到 `5o6jvk_z_split_000.html`、`progression=0.0007137758743754461`、`pendingSync=false` |
| 书签 | 「添加当前页书签」→ `bookmarks.json` 新增 `版权信息 · 0.1%`；「删除」后回到 `{}` |
| 待同步队列 | 期间 futuristic_tales 出现过「离线暂存 1 条进度」横幅，30 秒 ticker 后 `pendingSync=false`（W3 队列无回归） |
| 稳定性 | `logcat` 过滤 `FATAL EXCEPTION / ANR in com.zhangwenkang / E cinefin`：0 命中 |
| 还原 | 设置回默认（`pref_reader_mode=scroll`、`pref_reader_rtl=false`）；`/sdcard/w9_*.png`、`/sdcard/u.xml`、`/data/local/tmp/w9pinch.dex`、`/data/local/tmp/w9probe.dex`、`/data/local/tmp/dalvik-cache` 已删；App force-stop |

**7.7.5 本波真机发现（缺陷候选，交 UI 线）**

- **阅读页顶栏被状态栏压住**：`ReaderTopBar`（56dp = 196px）没有 `statusBarsPadding()`，而 K60 状态栏
  高 138px → 顶栏上 2/3 落在状态栏下，模式 / 书签 / Aa 按钮的可点区只剩 y≈138–183（45px）；
  本次验收必须先点 y=170 才生效（点 y=99 落到状态栏）。W8 只统一了三页主界面顶栏
  （`CinefinPageTopBar`），阅读页外壳（R3 的 Prism 顶栏）还没有；建议 UI 线后续统一补
  `statusBarsPadding()` 或直接换 `CinefinPageTopBar`。

### 7.8 W15-UI 顶栏避让修复真机验证（2026-10-02，Pad 5 `43af8627`，分支 `feature/w15-reader-topbar-posters`）——**完成**

修复：`ReaderTopBar` 的 56dp 行补 `statusBarsPadding()`（与 W8 `CinefinPageTopBar` 同款——状态栏
区域由顶栏 `chromeColor` 底铺满，按钮整体落到状态栏之下，横竖屏 / 手机 / 平板同一口径）。
阅读设置面板与底部页指示同步复核，无同类遮挡（M3 `ModalBottomSheet` 自带 windowInsets）。

| 形态 | 状态栏 insets | 顶栏按钮 bounds（uiautomator） | 结论 |
|------|---------------|------------------------------|------|
| 平板竖屏 1600×2560@360 | `[0,0][1600,60]` | 离线可读 `[899,99][1254,147]`、分页 `[1326,101][1395,146]`、Aa `[1485,101][1528,146]` | 按钮 top ≥99 > 60，无重叠 |
| 平板横屏 2560×1600@360 | `[0,0][2560,60]` | 离线可读 `[1859,99][2214,147]`、分页 `[2286,101][2355,146]`、Aa `[2445,101][2488,146]` | 同上；页指示 `[1158,1480][1402,1528]` 在导航栏（1564 起）之上 |
| 手机形态 1080×2400@420（≈411dp） | `[0,0][1080,60]` | 书签 `[574,108][655,160]`、分页 `[760,108][841,160]`、Aa `[946,108][996,160]` | 无重叠；设置面板最底「主题」行 2205 < 导航栏 2358 |

「不留多余空带」：状态栏区域由顶栏同色底铺满（总高 = 状态栏 60px + 内容 126px + 1px 发丝线），
没有旧行高被顶掉或双倍内边距。修复前遮挡记录见 §7.7.5（K60：按钮 `[959,15][1178,183]`、
实际可点区仅 y 138–183）；修复后本次三形态按钮 top ≥99 且全部 > 状态栏高度。

### 7.9 W22 素材复核与算法标定（2026-10-02，本地只读；**未占用真机**）

设备未分配（device-lock 未登记 W22），本会话只做代码 + 单测 + 本地素材取证，全部命令均为本地只读
（`pypdf` / `pdftoppm` 读 `test_files/`，不触碰 Jellyfin 服务器、未使用 adb）。

**7.9.1 新素材结构（`The.Kindaichi.Case.Files 復刻愛藏版_原画.pdf`，2.36 GB）**

| 项 | 数值 | 说明 |
|----|------|------|
| 总页数 | **5006** | PDF 页面框；每页内嵌一张 1920×1350 JPEG |
| 横版整页 | **4768** 页 = 1440×1012（比例 1.423 ≈ B4 横） | 每页 = 一张**已含中缝的对开图**：抽样 20 页里 16 页中央有 3.7%–11.1% 的居中白带（中缝），4 页是满版跨页画（无白带，内容跨过中线） |
| 竖版单页 | **68** 页 = 1031×1440（另有 2 页 1026×1440） | 卷封面 / 扉页等单页（如 p125 = File 02 封面，p0 = 卷首单页） |
| 其他横版 | 170 页 = 1440×620 / 953 / 936 / 968 等 | 章扉、插图条等 |

**结论**：这份素材的"对图"**已经是整页**（扫描时就按对开合过），不存在"相邻两页被拆开"的情况 →
跨页合并对它没有触发点；反过来，**双栏会把两张整页对图并排（一屏 4 页）**，这本素材的正确形态是
分页（1 页 / 屏）。后者记为遗留（§9），需负责人决定是否做"横版整页在双栏下整屏独占"的形态适配。

**7.9.2 判定阈值标定**（256 px 缩略图 + 内缘 2 列亮度带，与实现同口径）

| 样本 | 连续性 | 相关系数 | 结论 |
|------|--------|----------|------|
| 14 例"真连续内容人为拆开"（从素材页里取内容区再切一半） | 0.10–0.64 | 0.52–0.93 | 12/14 命中；2 例切口落在低结构区域被保守拒绝 |
| 14 例"同素材独立页强切 30%/70%"（对抗性反例，两半内缘都有内容） | 0.06–0.28 | −0.48–0.74 | 1/14 误命中（corr 0.744 的巧合） |
| 14 例"素材真实中缝"（横版页内的两半，本来就是两页） | 13 例 0（纸白），1 例均匀色块 | — | 0 误判 |
| Anda's Game（CBZ）真实相邻页 | 全 0 或均匀色块 | — | 0 误判 |
| futuristic_tales（CBZ）真实相邻页 | 0 | — | 0 误判 |

附加鲁棒性：±4 级扫描噪点、±70 亮度折痕阴影不影响相关系数（差值去均值后再比较）；0.3° 相对旋转
仍命中（corr 1.00），1.0° 会落空（保守）。据此取门槛：连续性 ≥0.05、起伏 ≥10、相关系数 ≥0.6、
归一化平均差 ≤0.18（[SpreadMerge.kt] 常量，单测锁定）。

**7.9.3 内存核算**（Pad 5 class：屏幕宽 2560 → 位图长边 2048；EB-3 红线同口径，**待真机采样复核**）

| 项 | PDF（ARGB_8888） | CBZ（RGB_565） |
|----|------------------|----------------|
| 合并位图（1.41 对开：2048×1447） | ≈11.8 MB | ≈5.9 MB |
| 合并缓存窗口（2 张） | ≤23.6 MB | ≤11.8 MB |
| 合成瞬时峰值（2 张半页 ≈1024×1447） | ≈11.9 MB（合成完立即释放） | ≈5.9 MB |
| 判定缩略图（2 张 256 px） | ≈0.4 MB（取完立即回收） | ≈0.4 MB |

即：新增常驻内存与单页窗口（3 张 ≤35 MB）同量级、不随页数增长；真机 `dumpsys meminfo` 采样与
「拼接前后同一 spread 像素对比」等负责人派窗口后补（§9 遗留）。

**7.9.4 W22 自造素材「拆页型对图」测试书（供真机终验）**

产物放在 `E:\codex_work\Android_Studio_Work_Space\test_files\`（**不入库**，等用户上传 Jellyfin）：

| 文件 | 体积 | 页数 | 说明 |
|------|------|------|------|
| `W22-Spread-Test.pdf` | 36.1 MB | 30 | 单页 1408×1992（检出 675.8×956.2 pt），Pillow 写 DCTDecode，走现有 PDF 检测路径 |
| `W22-Spread-Test.cbz` | 34.6 MB | 30 | 同一批 JPEG 页，覆盖 CBZ 的 2 的幂降采样分支 |

布局（15 个槽位 = 相邻两页，槽位与 `spread = 2k / 2k+1` 一一对应）：
独立单页对 5 对（封面 / 扉页 / 版权，内缘纸白）｜**对图 8 对**（一张横跨中缝的整幅画被切成左右两页，
竖版半页、水平镜像对称 → LTR 与 RTL 两个相位都命中）｜低相似负样本 1 对（两页内缘都是正弦暗带、
相位相反）｜横版整页 1 对（每页本身就是一幅对开图，与金田一素材同型）。

离线自测（生成脚本内置，与 `SpreadMerge.kt` **同口径**：几何门槛 + 256 px 缩略图 + 内缘 2 列亮度带 +
四条门槛；`--check-only` 可复检）：

| 路径 | LTR 相位 | RTL 相位 |
|------|----------|----------|
| PDF（按比例缩放到长边 256） | 对图 **8/8 命中**、0 漏判、0 误判 | 对图 **8/8 命中**、0 漏判、0 误判 |
| CBZ（2 的幂 `inSampleSize` 降采样） | 对图 **8/8 命中**、0 漏判、0 误判 | 对图 **8/8 命中**、0 漏判、0 误判 |

负样本被拦下的原因（逐槽位打印）：独立单页对连续性 0.000（纸白中缝）；低相似对连续性 1.000、
起伏 56.5 但**相关系数 −1.000**（只被相关性门槛拦下，正是该样本的设计目的）；横版整页相关度 1.000
但**几何门槛**否决（宽高比 1.41 不是竖版半页）。

脚本：`tools/w22-spread-test/make_spread_test_book.py`（固定种子可复现，输出 PDF + CBZ 并自测，
不达标以非零码退出）。真机验收口径：进入双栏 → 看图 / logcat 是否出现
`reader spread merge spread=… pages=… continuity=… corr=… diff=…` → 拼接前后同屏像素对比 + 内存采样。

### 7.10 W22 跨页对图合并真机验证（2026-10-02 16:30–16:54，Pad 5 `43af8627`，负责人指派窗口）——**完成**

设备：Xiaomi Pad 5（`43af8627`，Android 13，`user_rotation=1` 横屏 2560×1600；K60 归音乐会话未占用）。
安装本分支构建（APK 104.4 MB，`classes3.dex` 含 `reader spread merge` 特征串，装机前核验）。素材 =
用户已上传的 `W22-Spread-Test`，服务器同时有 **PDF**（`b5512d3b…`，36.1 MB，缓存 37,903,960 B）与
**CBZ**（`2f887288…`，34.6 MB，缓存 36,309,369 B），两者字节数与本地生成件一致。

**7.10.1 PDF 全 15 槽位（双栏，逐屏核验页指示）**

| 槽位 | 类型 | 页指示 | 命中日志 | 中缝空带 / 内容占比 |
|------|------|--------|----------|---------------------|
| 0 / 3 / 8 | 独立单页对 | 1-2 / 7-8 / 17-18 | 无 | **307 px / 0.0%**（未合并） |
| 5 | 低相似负样本 | 11-12 | 无 | **307 px / 0.0%** |
| 7 | 横版整页 | 15-16 | 无（几何门槛否决） | 0 px / 86.5%（两页各自铺满槽位） |
| 11 / 13 | 独立单页对 | 23-24 / 27-28 | 无 | **307 px / 0.0%** |
| 1 / 2 / 4 / 6 / 9 / 10 / 12 / 14 | **对图 ×8** | 3-4 … 29-30 | **8 条命中**（continuity 0.88–0.89 / corr 1.00 / diff 0.00） | **0 px / 100%** |

页指示从 `1-2/30` 到 `29-30/30` 逐屏 2 页递增（页号语义不变）；**8 对全部合并、7 个负样本 0 误拼**。
中缝相邻两列（合并槽位）相关 `corr +0.9972 / +0.9985`、归一化差 0.0027–0.0028 → 连续无缝；滑动后
**即时帧**（2 次抽取）已是合并态（0 空带 / 100% 内容）→ 邻槽预取 + 首帧查缓存**无两页闪动**。

**7.10.2 CBZ 复验**（`2f887288`，34.6 MB）

冷启动重开后**倒序走完 15 槽位**（绕开判定缓存，保证每槽位重新判定）：logcat 同样 **8 条命中**
（spread=14/12/10/9/6/4/2/1，continuity 0.86–0.89 / corr 1.00 / diff 0.00）、负样本无命中；15 张
截图逐槽位量化（空带 0 px + 内容 >50% 判为合并）**14/15 与代码行为一致**，唯一例外是横版整页槽位
（两页并排时也是"0 空带 + 有内容"，只能靠几何门槛与"无命中日志"区分）⇒ §9 遗留①的形态问题，不是缺陷。

**7.10.3 内存（`dumpsys meminfo` App Summary，位置逐一核验）**

| 场景 | 未合并 | 合并 | 结论 |
|------|--------|------|------|
| 30 页 PDF 首轮 A/B（17-18 vs 19-20） | 463.7 MB（Native 159.2） | **475.7 MB**（Native 171.1） | **+12.0 MB ≈ 1 张 2048×1447 ARGB**，与 D20「LRU 2 张」预算一致 |
| CBZ A/B（23-24 vs 25-26） | 289.1 MB（Native 69.2） | 271.8 MB（Native 65.8） | −17.3 MB：合并开销落在 Graphics/GC 噪声内 |
| 30 页 PDF 连翻 10 屏瞬态 | — | 285–307 MB | 无持续增长 |
| 3649 页真实漫画（冷启动稳态） | — | **327.7 MB**（Native 71.7 / Graphics 105.7） | 与 W4 基线 358 MB 同量级 → 未命中的书零常驻开销 |

长会话（多书往返 + 旋转 + 长时间翻页后）曾读到 497–614 MB，冷启动即回落 327.7 MB ⇒ 为会话累积 /
图形栈抖动，非合并引入；全程无 OOM。

**7.10.4 RTL / 页序 / 进度语义**

右起开 → 页指示 `双栏 · 25-26/30 · 右起`（**页号不变**），前进一步改为右滑（方向镜像）且 logcat 重新
命中（spread=12、14；缓存随 `remember(document, rtl)` 重建）；`progress.json` 记录
`progression 0.9333 × 30 = 28` = 第 29 页起点，与页指示一致、`pendingSync=false`；force-stop 后重开
恢复到 `29-30/30` ✓。

**7.10.5 大文档压力 + 稳定性**

虚构推理（3649 页 / 639.6 MB，缓存命中）双栏连翻 20 屏：页指示 `1-2 → 41-42/3649`、
**merge 命中 0 次**（真实竖版漫画无误拼）、瞬态 PSS 峰值 614 MB；两本书整轮 `FATAL EXCEPTION / ANR /
Input dispatching timed out / E cinefin` 均 0。

**7.10.6 还原**

阅读模式回 `paged`、`pref_reader_rtl=false`（与开场备份一致；主题 eyecare 等未动）、
`accelerometer_rotation=1` / `user_rotation=0`、App force-stop、`/sdcard/w22_*`（1 xml + 21 张截图）
全部删除、K60 未触碰。

### 7.11 W26 双栏横版独占 + 带纸边合并真机验证（2026-10-02 18:0x–18:3x，Pad 5 `43af8627`，负责人指派窗口）——**完成**

设备：Xiaomi Pad 5（`43af8627`，Android 13；竖屏 1600×2560 与 `user_rotation=1` 横屏 2560×1600 两形态）；
安装本分支构建（APK 核验 `classes3.dex` 含 `reader spread layout` 与 `trim=%d/%d` 特征串）后 `install -r`；
K60 归 W25 未触碰。素材：`W22-Spread-Test.pdf`（服务器 / 已有缓存）+ 自造 `W26-Spread-Edge-Test.cbz`
（注入方式：`adb push` → `run-as` 覆盖已下载缓存 `2f887288…book`，验证后按原字节还原；服务器全程只读）。

**7.11.1 横版整页独占（W22 测试书 PDF）**

- 布局日志：`reader spread layout pages=30 slots=16 landscape=2`（30 页 → 16 槽、2 个横版独占页）。
- 页号计数（横屏双栏，逐屏 dump）：`21-22 → 19-20 → 17-18 → 16 → 15 → 13-14/30`——横版整页（页 15、16）
  各自独占一屏、翻页步进 1 页；竖版页维持两页一槽（步进 2 页）。
- 独占渲染 vs 分页单页（同一页 15）：竖屏截图逐像素 **平均差 0.06/255、差异 >12 占比 0.1%**；
  横屏（2560×1600）**平均差 2.43/255、差异 >12 占比 4.1%**（旋转后重新解码的插值差异）。两种形态都证明
  「双栏独占 == 分页单页渲染」（Fit、保持比例、不裁切；竖屏铺满宽度 / 横屏铺满可用高度）。

**7.11.2 竖版对图 8/8 回归 + 0 误拼（W22-Spread-Test.pdf）**

- 重启 App（判定缓存清空）+ `logcat -c` 后从 1-2 倒走 15 屏到 29-30：恰好 **8 条命中**，页对 3-4 / 5-6 /
  9-10 / 13-14 / 19-20 / 21-22 / 25-26 / 29-30，`continuity 0.86–0.89 / corr 1.00 / diff 0.00 / trim=0/0`；
  负样本（独立单页对、低相似对、横版页）无命中 → **0 误拼**。

**7.11.3 带纸边对图（W26-Spread-Edge-Test.cbz，44 页）**

- 全程 1-2 → 43-44：**12 条命中** = 8 条原对图（trim=0/0）+ **4 条带纸边对图**（页 31-32 / 33-34 / 35-36 /
  41-42，`trim=10/10、7/7、14/14、4/4`，continuity 0.79–0.86 / corr 1.00 / diff 0.00）；
  3 个带纸边负样本（独立页对 / 相位相反 / 中相关 `corr 0.505` < 0.75）无命中 → **0 误拼**。
- 中缝数字（修复后）：41-42 中缝像素 = 内容色 `(66,61,82)`、无亮色带段；35-36 中缝 = accent 色
  `(173,138,49)`、无亮色带段。
- **真机拦下的缺陷（已修复复验）**：① 页指示把 0-based 槽末页当 1-based 用 → 两页槽显示「21/30」（应
  「21-22/30」）；② 合成裁剪方向写反（左半裁了外缘、右半裁了外缘），纸边原样保留（36px 纸边在屏上残留
  42px 白带、110px 纸边残留 134px）→ 修正为「左半裁内缘（右缘）、右半裁内缘（左缘）」并抽纯函数
  `spreadHalfSourceRange` + 单测锁定。

**7.11.4 RTL / progression / 稳定性**

- 右起开：页指示 `双栏 · 1-2/30 · 右起`；前进方向镜像（右滑 1-2 → 3-4 → 5-6 → … → 16，横版页 16 独占
  仍为单页号）；切换后合并缓存随 rtl 重建并重新命中（`spread=4 … corr 1.00`）。
- `progress.json`（W22 PDF）：停在页 16 时 `progression=0.5`、`positionTicks=5000000`、
  `runtimeTicks=10000000`、`pendingSync=false` → 与「15/30（0-based 起点 15）」的页起点完全一致。
- 整轮 `FATAL EXCEPTION / ANR / Input dispatching timed out` 0 条；两本素材合计走查约 60 屏。

**7.11.5 还原**

- 阅读模式回 `paged`、`pref_reader_rtl=false`（与开场基线一致，其余 reader 偏好未动）、
  `accelerometer_rotation=1` / `user_rotation=0`、App force-stop；
- `files/books/2f887288….book` 已按原字节（36,309,369 B）还原；`/sdcard/w26_ui.xml`、
  `/sdcard/Download/{w26_edge,w22_restore}.cbz` 全部删除；K60 未触碰。设备副作用仅剩 W22 PDF 的正常阅读进度
  （0.5）。

### 7.12 W29 PDF 搜索 + 本地批注：本地取证（2026-10-02；**未占用真机**）

设备未分配（device-lock：W29 登记为「暂未分配」；Pad 5 归 W27、K60 归 W28），本波只做
**代码 + 单测 + 本地只读素材取证**：不碰 Jellyfin 服务器、不使用 adb、不写服务器数据。

**7.12.1 门禁（本分支）**

| 命令 | 结果 |
|------|------|
| `assembleDebug`（根，含 TV） | ✅ BUILD SUCCESSFUL |
| `ktfmtCheck` | ✅ 0 未格式化文件 |
| `:app:phone:testLibreDebugUnitTest` | ✅ 全绿 |
| `:modes:book:testDebugUnitTest` | ✅ **101 项**（W26 的 77 + 本波 24）全绿 |

**7.12.2 构建特征（真机走查时的装机核验口径）**

- arm64 APK 115.45 MB（基线 `d972315` 同命令构建 102.40 MB，**+13.04 MB** = PdfBox + BouncyCastle；
  v7a 同样 +13.04 MB）；
- `classes3.dex` 含本波特征串 `reader pdf search stopped`（同一 dex 里还有 W22/W26 的
  `reader spread merge` / `reader spread layout`）；PdfBox 类（`com/tom_roush`）分布在
  `classes3/17/33.dex` —— 装机后可比对 dex 特征串，排除「跑在别人的构建上」（踩坑 19）。

**7.12.3 单测覆盖（24 项）**

- `PdfSearchTest`（12）：关键词归一化 / 片段折叠换行并定位命中 / 字符框大小写与空白无关匹配 /
  中文命中与上限 / 片段与矩形配对 / 单页上限 / 流式按页出命中与完成状态 / 达上限提前结束 /
  无文本层标记 / 空关键词不扫描 / 取消在页边界生效 / 进度步长。
- `ReaderAnnotationTest`（8）：矩形夹取（含 NaN 与反向输入）/ 最小可点尺寸 / 备注摘要 /
  编解码往返 / 标签 / 坏文件与未知字段兼容 / 存储按 itemId 分文件 + 增改删 + 重新读盘 / 备注截断。
- `PageOverlayGeometryTest`（4）：Fit 落位与 `ContentScale.Fit` 一致 / 退化尺寸除零保护 /
  归一化矩形 ↔ 像素正反映射 / 框选越界夹取与过小框忽略。

**7.12.4 素材与性能（桌面 JVM 同源代码，详见 D22 表）**

- `attention_is_all_you_need.pdf`：15 页抽出 33,486 字符，2.28 s（152 ms/页，含首次加载 + JIT 预热）；
- 扫描件（W22-Spread-Test 30 页、金田一原画 5006 页、深入理解计算机系统 775 页）：**0 字符**、
  合计毫秒级 → 「无文本层」路径成立且不会拖慢阅读页；
- **未做**：真机（ART + 设备 IO）耗时、`dumpsys meminfo` PSS、3649 页《虚构推理》实机搜索 —— 等窗口。

**7.12.5 未覆盖 / 真机待验清单（交负责人调度）**

1. 搜索：命中列表滚动流畅度（3649 页文档边扫边滚）、跳页后命中矩形与文字的贴合度、
   「无文本层」提示在真实扫描件上的文案与路径、旋转页（`/Rotate` 90/270）矩形相位；
2. 批注：矩形框选（含双指 / 单指竞争）、备注输入与保存、列表跳转 / 删除、
   RTL 与双栏相位、合并槽位（W22/W26 命中对图）下不叠加的可见性确认；
3. 内存：搜索会话（8 MB 主缓冲 + 临时文件）与批注叠加层对 PSS 的影响；
4. 交互回归：批注模式下翻页暂停是否符合预期（退出批注模式恢复）、`ModalBottomSheet` 内的
   搜索输入与列表在手机形态（411dp）下的可达性。

### 7.13 W29 真机验收（2026-10-02 20:33–21:0x，Pad 5 `43af8627`，负责人指派窗口）——**完成（真机拦下 1 个缺陷已修）**

设备：Xiaomi Pad 5（`43af8627`，Android 13，竖屏 1600×2560）；K60 空闲未占用（按调度纪律）。
构建：先装整合版 master `e465a83`（装机前核验 `classes3.dex` 含 `reader pdf search stopped`），
真机发现搜索崩溃后换装修复分支 `fix/w29-search-hit-position`（提交 `9b7958c`）复测；
入口统一为「媒体库 → 书籍 → 点书」（`ReaderActivity` 仍 `exported=false`）。素材全部取自本地缓存：
`attention_is_all_you_need`（PDF / 15 页 / 2.1 MB，有文本层）、`W22-Spread-Test.pdf`（36.1 MB / 30 页，
图片页「无文本层」+ 8 对合并槽）、`W22-Spread-Test.cbz`（34.6 MB，对照：无 PDF 工具入口）、
`虚构推理 (2026)`（639.6 MB / 3649 页，大文档）。

**7.13.1 真机拦下的缺陷（已修复 + 复验，交负责人合并）**

- **现象**：打开 attention → 「搜索」→ 输入 `attention` → 进程 **FATAL**
  （`java.lang.IllegalArgumentException: Key "0-25-9" was already used. If you are using
  LazyColumn/Row please make sure you provide a unique key for each item.`），ReaderActivity 被
  `Force finishing`，App 回到 MainActivity（日志时间戳 20:36:13）。
- **根因**：`buildSnippet` 用「整段窗口 `indexOf(query)`」定位命中在片段里的位置——同一页出现两次
  相同关键词时，后一个窗口会重新命中窗口里的**第一处**，两条命中的 `matchStartInSnippet` 相同 →
  结果列表 `LazyColumn` 的 key（页码-片段位置-长度）重复 → Compose 直接抛异常。
- **修复**（分支 `fix/w29-search-hit-position`，基于 master `e465a83`）：
  ① 片段命中位置改为「原始下标 → 折叠后下标」的逐字符映射（`charMap`，连续空白映射到同一位置）；
  ② 结果列表 key 前缀加下标护栏（追加式列表，下标稳定）；
  ③ 补 2 项回归单测（同页 3 次命中位置互不相同且可定位 / 换行处命中映射），
  `:modes:book` 101 → **103** 项，门禁（`assembleDebug` + `ktfmtCheck` + app 单测 + book 103）四绿。
- **复验**：同一条搜索（attention / `attention`）→ 65 条命中、6 行/页、**无崩溃**，跳转与矩形正常 ✓。

**7.13.2 搜索**

| 项 | 结果 |
|----|------|
| 文本层命中（attention，15 页） | 「扫描完成 · 共 **65 条命中**」（6 条/页上限生效）；结果列表滚动到第 4–5 页行正常（懒加载）|
| 点击跳页 + 命中矩形 | 「跳转」→ `分页 · 5/15`，页面叠加 5–6 条命中矩形：像素取证 5 条色带、每条高 33 px、带内深色文字像素占比 5.9–13.4%（矩形压在文字行上，宽度 105–467 px 随行宽变化）|
| 无文本层（W22-Spread-Test.pdf，30 页） | ≈1–2 s 出「**该 PDF 没有文本层，无法搜索（可用矩形批注）**」+ 提示行「可以退出搜索，用「批注」在页面上框选高亮」|
| 非 PDF 不出现工具入口 | W22-Spread-Test.cbz 顶栏只有下载状态 / 模式 / Aa，**无「搜索 / 批注」** ✓ |
| 大文档（虚构推理，3649 页） | 首次搜索 ≈14 s（含 639 MB 文档加载与 8 MB 缓冲 + 临时文件建索引）→ 无文本层提示；热扫描 ≈**1.3 ms/页**（2125 页 ≈2.5–3 s）|
| 搜索会话内存 | 搜索加载 PdfBox 后 Native ≈64 MB（8 MB 主缓冲 + 临时文件），与 EB-3「3 张页位图」同量级，未见随页数增长 |
| 退后台取消 | 输入关键词后立即按 Home → 返回阅读页显示「**已暂停 · 已扫描 2125/3649 页 · 命中 0**」+「已暂停扫描：重新输入或改关键词会从头扫描。」✓ |

**7.13.3 本地批注**

| 项 | 结果 |
|----|------|
| 新增（空备注 / 带备注） | 框选 → 弹「添加批注（第 N 页）」→ 保存；落盘 `files/reader/annotations/{itemId}.json`（version 1，`page` 0-based、归一化矩形：如 `left 0.4375 / top 0.2011 / right 0.7496 / bottom 0.2735`）|
| 编辑 | 单击已有框 → 弹窗**回填原备注**（`w29noteA`）→ 改为 `B` 保存 → `updatedAtMs` 更新、`createdAtMs` 不变 ✓ |
| 删除 | 弹窗内「删除」与列表「删除」两条路径都生效，文件回到 `"annotations":[]` ✓ |
| 列表 | 「共 N 条」+「第 5 页 · B」+「跳转 / 删除」；跳转从 `双栏 · 3-4/15` 回到 `5-6/15` ✓ |
| 页面叠加 | attention 第 5 页 2 条批注 → 2 条色带，位置/尺寸与存储矩形换算一致（高 155 px、宽 505 px 对得上 0.072 / 0.31 的比例）|
| RTL + 双栏相位 | 同一条批注：LTR 在左半（x 347–601）/ RTL 在右半（x 1147–1401），**高度、宽度与 y 完全一致** → 锚点只随页框走、不随相位漂移 ✓ |
| 合并槽（W22 页 3-4 对图） | 页 3 的批注在合并槽**不叠加**且无错位：同槽位 A/B 截图逐像素比对，页面区域 **0 差异**（唯一差异是状态栏时钟 y 15–44）✓；未合并槽（页 7-8）批注正常叠在页 7 半格（x 244–607）✓ |
| 交互备注 | 批注模式下单击已有批注不弹编辑（需先「结束」框选）；框选时单指翻页暂停 —— 与设计一致 |

**7.13.4 大文档双栏与 PSS（顺带发现的 W26 线缺陷，本波未修）**

| 场景（虚构推理 3649 页） | TOTAL PSS | Native Heap |
|--------------------------|-----------|-------------|
| 冷启动 `分页`（无版式扫描） | **357 MB**（复测 340 MB） | 58 MB |
| 冷启动 → 切 `双栏`（触发 `pageAspectRatios` 3649 页扫描）+5 s | **962 MB** | **748 MB** |
| 扫描完成后（+95 s） | 929 MB | 716 MB |
| 再从双栏切回 `分页` | 897 MB（不回落） | 740 MB |

- 同一进程内 20 槽连翻（33-34 → 73-74/3649，每槽精确 +2 页）**0 次误拼**（`reader spread merge`
  命中 0）、0 FATAL / 0 ANR → W22/W26 的合并与版式判定在真实大文档上不回归 ✓；
- **结论**：**W26 的「进入双栏逐页 `PdfRenderer.openPage` 扫全书宽高比」在 3649 页 PDF 上把
  native heap 抬 +600 MB 量级且不回收**（冷启动分页 357 MB → 双栏 962 MB；扫描完成后 929 MB；
  切回分页 897 MB；PSS 含 670–720 MB swapped，RSS 仍 ~300–400 MB）。W22 时代同文档双栏为 327.7 MB
  （那时还没有版式扫描）⇒ 属 **W26 引入、本波 PSS 采样顺带发现**，不在 W29 范围（未修，
  建议交 W26/W5 线：改用 PDFBox 读页尺寸 / 扫描分块 + 显式回收 / 可见区优先按需扫描）。

**7.13.5 未覆盖**

- 手机形态（K60 未占用）；BouncyCastle 瘦身与证书加密 PDF 降级；合并槽内的叠加映射；
  金田一 5006 页本体（2.36 GB，需下载；与虚构推理同型、同走「无文本层」路径）。

**7.13.6 设备副作用还原**

- 测试批注文件已删、`files/reader/annotations` 目录已移除（`files/reader` 回到 `bookmarks.json` +
  `progress.json`）；
- prefs 与开场基线一致：`pref_reader_mode=paged`、`pref_reader_rtl=false`、主题 `eyecare`、
  字号 `0.9988`；`accelerometer_rotation=1` / `user_rotation=0` 未改；
- App `force-stop`（前台回到 MIUI 桌面）、`/sdcard/w29.xml` 已删；K60 未触碰；
- 保留的正常副作用：三本测试书的阅读进度随走查前进（attention 第 5 页、W22 PDF 第 3 页、
  虚构推理第 75 页）——与 W22/W26 同样按「正常阅读副作用」保留。

### 7.14 W33 双栏版式扫描内存修复（2026-10-02 21:4x–21:5x，Pad 5 `43af8627`，负责人指派窗口）——**完成**

分支 `fix/w33-spread-scan-memory`（起点 master `9738877`），只动 `modes/book`；提交见交接报告。

**7.14.1 根因与方案**

- **根因**：W26 进入双栏时的全书宽高比扫描（`PageSource.pageAspectRatios()`）在 PDF 上逐页走
  `PdfRenderer.openPage(index).use { it.width to it.height }`。`Page.close()` 只回收 Java 侧 Page 对象，
  3649 次 openPage 让 **native heap 从 58 MB 抬到 716–748 MB 且不回收**（踩坑 29：PSS 357 → 962 MB，
  切回分页 897 MB）。这是「用渲染器读元数据」的架构性错误：判定版式**不需要渲染、也不需要打开页面**。
- **方案（选定「PdfBox 页树元数据批量读」）**：新增 `PdfLayoutSource.kt`——`PDDocument.load(file,
  setupMixed(8 MB))` 只读页树，按 `page.cropBox`（缺省回退 `/MediaBox`）+ 继承的 `/Rotate` 折算显示尺寸
  （与 `PdfRenderer.Page.width/height` 同口径：旋转 90/270 交换宽高，非法角度按 0），一次遍历全书得到
  宽高比表；页面对象读完即丢弃，常驻只有页树与 8 MB 主缓冲（溢出到临时文件，与 W29 文本层同源策略）。
  `PageSource` 新增可选批量方法 `pageAspectRatios()`（默认 null），`PdfPageSource` 覆写走 PdfBox；
  `collectPageAspectRatios()` 优先批量路径、不可用（返回 null / 页数不符 / 抛错）时回退逐页扫描。
- **边界**：渲染路径仍走 PdfRenderer（D14 不变），只有「版式判定」换元数据源；`PdfLayoutSource` 惰性加载，
  分页 / 滚动模式不会打开它；关闭随 `PdfPageSource.close()` 释放。旋转页、`/CropBox` 裁剪、继承
  `/Rotate` 都有单测锁定（`PdfLayoutSourceTest`，book 103 → **106**）。

**7.14.2 门禁**

`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（61）+ `:modes:book:testDebugUnitTest`（106）四绿。

**7.14.3 真机：虚构推理（3649 页 / 639.6 MB，冷启动 → 切双栏）**

| 阶段 | Native Heap | TOTAL PSS | 说明 |
|------|-------------|-----------|------|
| 分页基线（打开后静止） | 57.1 MB | 275.2 MB | 与 W22 基线 327.7 MB 同量级 |
| 切双栏 +3 s | 55.3 MB | **299.2 MB** | 扫描已完成（日志见下）；修复前同点 **748 MB / 962 MB** |
| 切双栏 +60 s（采样 3 次） | 57.0 MB | 286–299 MB | 不增长 |
| 双栏 → 滚动 → 分页（回落） | 48.9 → 67.5 MB | **284.9 → 309.9 MB** | 能回落；修复前切回分页 897 MB 不回落 |
| 冷启动直接双栏（RTL 右起） | 60.7 MB | 295.0 MB | 同书冷启动直接进双栏与修复前 W22 基线同量级 |

- 扫描日志（文本取证）：`reader spread layout pages=3649 slots=1825 landscape=0 at=` —— 3649 页 → 1825 槽、
  0 个横版独占；同书双栏连翻（73-74 → 75-76）页号精确 +2、0 次误拼、0 FATAL / ANR。
- **判定一致性（W22-Spread-Test.pdf，30 页）**：`pages=30 slots=16 landscape=2 at=14,15`（横版页 15、16 独占，
  与 W26 修复前 `landscape=2` 一致）；对图 **8/8 命中**（分两段走查：3-4 / 5-6 / 9-10 / 13-14 / 19-20 /
  21-22 / 25-26 / 29-30，`continuity 0.88–0.89 / corr 1.00 / diff 0.00 / trim=0/0`）；负样本
  （独立单页对 / 低相似对 / 横版页）**0 命中 = 0 误拼**；RTL 右起翻页方向镜像、页指示
  `双栏 · 1-2/30 · 右起`、`spread=4 pages=5-6` 合并在 RTL 相位下重新命中。
- **不回归**：搜索 `attention` → 「扫描完成 · 共 65 条命中」（W29 复验值，同页多次命中无崩溃）；
  批注「开始框选 → 拖动 → 保存」→ `files/reader/annotations/0a1f4dc1….json` version 1 落盘
  （page 5、归一化矩形 0.625 / 0.0 / 1.0 / 0.1907、note 空）；分页 / 双栏 / 滚动切换页号与 progression 正常。

**7.14.4 设备还原**

- prefs 回 `pref_reader_mode=paged`、`pref_reader_rtl=false`、主题 `eyecare`、字号 0.9988212（逐键比对）；
- 测试批注文件已删、`files/reader/annotations` 目录已移除（`files/reader` 回到 `bookmarks.json` + `progress.json`）；
- `/sdcard/w33_ui.xml` 与临时 APK 已删；App `force-stop`；**K60 未触碰**；
- 保留的正常副作用：测试书阅读进度随走查前进（「虚构推理」本轮前进到 71-72 槽附近）。

### 7.15 W47 回归复验（2026-10-03，Pad 5 `43af8627`，分支 `feature/w47-full-regression`）

**7.15.1 金田一 2.36 GB（本地 MoonReader 副本，5006 页）**

- 打开（滚动模式，tap → 内容区像素均值 >100）：**≈13.4 s**（首帧 ReaderActivity 启动 353 ms；`打开本地书籍 w47…` / `离线可读 · 2412.7 MB`）。
- 双栏扫描：`reader spread layout pages=5006 slots=4973 landscape=4938 at=1,2,3,…` —— 本书为整页横版扫描件，98% 页被判为横版独占。
- **触发 D-W47-1（P1）**：本地 SAF 打开时 `PdfPageSource(descriptor)` 不接 PdfBox（`layout = null`），双栏扫描回退**逐页 `PdfRenderer.openPage`**（W33 只修了 `File` 路径）→ Native Heap 21 MB → **1,586 MB**、PSS 1.72–2.45 GB → MIUI `killinfo` + SIGKILL，进程（pid 9617 / 13072）两次被杀（`wm_finish_activity … proc died without state saved`；无 Java / native crash）。修复建议见 `TEST_PLAN` §7.2。

**7.15.2 自造 `/Rotate` 旋转页 PDF（`w47_rotate.pdf`：/Rotate 90 + 竖版 + 原生横版 + /Rotate 270，共 4 页）**

- 打开 → 双栏扫描日志 `pages=4 slots=4 landscape=3 at=0,2,3`（三张横版页独占、竖版页单独成槽）——`/Rotate` 在本地打开链路的页尺寸判定与渲染一致；
- 第 1 页白底页面区域截图量测宽高比 **1.43 ≈ 842/595**（旋转 90° 后按横版渲染，未被压扁）。

**7.15.3 设备还原**：`files/books/*.part` 残片删除、阅读器偏好回「滚动」、App force-stop（见 `TEST_PLAN` §7.4）。

### 7.16 W48 SAF PDF 双栏扫描修复真机验证（2026-10-03 15:39–16:12，K60 `8e875894`，负责人转派窗口）——**完成**

分支 `fix/w48-saf-pdf-scan-memory`（起点 master `1ebdbcc`），只动 `modes/book`（+ 文档）；安装包 = 本
worktree `:app:phone:assembleDebug`（arm64-v8a，`install -r`）。素材：金田一 2.53 GB / 5006 页（从
`test_files/` 推 `/sdcard/Download/W48Media/w48_kindaichi_5006p.pdf`；adb push 中文文件名到 `/sdcard` 失败，
改 ASCII 目标名）+ `W22-Spread-Test.pdf`（36.1 MB / 30 页）。服务器只读；设备窗口见 device-lock
（K60 15:39 登记 / 16:12 释放）。

**7.16.1 金田一 5006 页（本地 SAF，双栏）**

- 扫描日志与修复前**逐字一致**：`reader spread layout pages=5006 slots=4973 landscape=4938
  at=1,2,3,4,5,6,7,8`（两次会话复现）；端到端数秒完成（页缓存修复后）。
- 内存（`dumpsys meminfo`，同机同书）：滚动基线 Native 44.9 MB / PSS 316.3 MB → 双栏扫描完成后
  Native **49.8 MB** / PSS **358.4 MB**（另一次会话 53.9 / 367.9），+60 s 三次采样 368.1 → 367.9 MB
  **不增长**；双栏 → 滚动 → 分页 = 368.2 / 368.3 MB **持平回落**；全程 0 FATAL / ANR / 进程重启。
- 对照（W47 修复前，Pad 5）：同操作 Native 21 MB → **1,586 MB**、PSS 1.72–2.45 GB → MIUI SIGKILL。
- 备注：W33 的 Pad 5 口径（Native ≤60 MB / PSS ≤310 MB）中 PSS 一项在 K60 上绝对值更高（同机滚动基线
  即 316.3 MB，屏 1440×3200 的 EGL + Graphics 就占 ≈189 MB），故以「与同机基线持平、60 s 不增长、可回落」
  为准；Native 一项双机都 ≤60 MB。
- 页指示：扫描完成后 `双栏 · 1/5006`，翻页步进 1 页（横版整页独占语义与 W26 一致）。

**7.16.2 首版缺陷（本会话真机拦下）**：无页缓存版本在 2.53 GB SAF fd 上解析 8 分钟 CPU 100% 不结束
（`pread` 逐次系统调用 + FUSE 代理开销，见 §8 踩坑 30）。加 4 KB 页缓存（`PagedPositionalReader`）后
同一操作数秒完成，内存同 §7.16.1。

**7.16.3 W22 回归（拆页对图 / 横版独占 / RTL）**

- 版式：`pages=30 slots=16 landscape=2 at=14,15`（与 W33/W26 口径一致）。
- **RTL 相位对图 8/8 命中**：`3-4 / 5-6 / 9-10 / 13-14 / 19-20 / 21-22 / 25-26 / 29-30`
  （continuity 0.86–0.89 / corr 1.00 / diff 0.00 / trim=0/0）；0 误拼（1-2、7-8、11-12、17-18、23-24、
  27-28 与两张横版页均无合并日志）；页指示全程带 `· 右起`。
- **遗留观察（非本波引入）**：LTR 冷启动时「当次缓存建立瞬间所在槽位」的判定可能取到 null 并**被缓存**
  （之后不再复算），表现为该槽位本轮不合并；同一页对在 RTL 相位 / 切模式重建缓存后重新命中。判定链路
  （`pageSizePx` / `renderPage` / `spreadMergeDecision`）本波未改动，建议 W22/W26 线后续把 null 判定改为
  可重试（例如不缓存 null，或下次 settle 重算）。

**7.16.4 兜底阈值**：`PER_PAGE_LAYOUT_SCAN_MAX_PAGES = 1500` + `perPageAspectScanMaxPages` 由单测锁定
（1500 允许逐页 / 1501 跳过并用安全默认）；真机无「不可 seek 的 SAF fd」样本，未做实机触发取证。

**7.16.5 设备还原**：W48Books 测试库（App 内删除）+ `/sdcard/Download/W48Media`（2.53 GB）删除、
`/sdcard/w48_ui.xml` 清理；阅读器偏好 `pref_reader_mode=scroll` / `pref_reader_rtl=false`（这些键本次
会话首次生成，值即默认）；`accelerometer_rotation=1` / `user_rotation=0`；App force-stop；未改网络 /
音量；服务器只读。

### 7.17 W49 对图判定可重试复验（2026-10-03 17:0x–17:1x，Pad 5 `43af8627` 主 + K60 `8e875894` 抽验，负责人调度窗口）——**完成**

分支 `fix/w49-leftover-cleanup`（起点 master `3dbca99`），素材 = `tools/w22-spread-test/make_spread_test_book.py`
重新生成的 `W22-Spread-Test.pdf`（36.1 MB / 30 页；生成器离线自测 LTR / RTL 双路径 8/8 达标），经本地媒体库
（SAF，`/sdcard/Download/w49media`）打开；测试服务器只读。安装包 = 本 worktree `:app:phone:assembleDebug`
（arm64-v8a，`install -r`）。

- **LTR 冷启动（双栏）**：`reader spread layout pages=30 slots=16 landscape=2 at=14,15`（与 W33 / W26 / W48
  口径逐字一致）；8 对拆页对图**全部命中** —— `pages=3-4 / 5-6 / 9-10 / 13-14 / 19-20 / 21-22 / 25-26 /
  29-30`（continuity 0.88–0.89 / corr 1.00 / diff 0.00 / trim=0/0）；翻满 15 槽仅这 8 条合并日志（0 误拼）。
- **切 RTL 重建缓存**：打开右起后逐槽走查，同样 8/8 命中（corr 1.00 / diff 0.00），页指示
  「双栏 · n-m/30 · 右起」全程正常；整会话合并日志 16 条 = LTR 8 + RTL 8，无多余合并、无漏合并。
- **未确定性触发的项**：瞬时「判定未就绪」窗口（内存压力 / 解码瞬时失败）本轮未复现（与 W48 的
  3-4/5-6 概率一致）；「不缓存未就绪 + 重试」由 `SpreadMergeDecisionMemoTest` 单测锁定，真机确认该改动
  没有改变命中结果与 0 误拼。
- K60 抽验：同 APK 装机 + 打开服务器书籍（attention PDF）回归阅读器滑杆修复（`UI_PLAN` D51）。

## 8. 踩坑库

1. **Readium 包名是 `org.readium.r2.*`**，不是 `org.readium.navigator.*`；
   3.4.0 的入口为 `org.readium.r2.navigator.epub.EpubNavigatorFactory`。
2. **Fragment 添加时机**：`FragmentContainerView` 尚未 `onAttachedToWindow` 时
   `commitNow` 会抛 `No view found for id ...`；必须在 `container.post {}` 中
   添加 Fragment（本会话已修）。
3. **Jellyfin UUID 两种格式**：REST 返回 32 位无连字符 UUID，
   `UUID.fromString` 只接受 36 位标准格式；PoC 已兼容两种写法。
4. **PlayedPercentage 由服务端反算**：不能使用固定合成时间轴；
   必须按条目 `RunTimeTicks` 换算，否则会出现 214% 的假进度。
5. **Readium 3.4.0 部分 API 标注 `@ExperimentalReadiumApi`**（Fragment Listener、
   `submitPreferences` 等），调用处需 `@OptIn`；升级时优先复查这些 API。
6. **共享测试机的并发安装**：其他会话可能重装同一 debug 包，导致
   ReaderActivity 临时“消失”；验证前重装本分支 APK 并立即执行。
7. **凭据纪律**：`.env.local` 只在命令变量中使用；不得写入仓库、文档、提交
   信息或会话输出。测试日志只取过滤片段。
8. **`EpubPreferences.fontSize` 是百分比倍数**（1.0 = CSS 100%），不是 px；
   官方 supportedRange 0.1–5.0，超范围会静默失效或异常显示。行距 1.0–2.0、
   边距 0.0–4.0，UI 需自行收窄到实用区间。
9. **Readium `Theme` 枚举在 JVM 单测初始化时会调用 `android.graphics.Color.parseColor`**
   （unit test 报 `not mocked`）。映射拆成纯 Kotlin 的 `ReaderPreferenceSpec` 中间层，
   单测只断言该层；Readium 适配层（`toEpubPreferences`）留给真机 / instrumentation。
10. **`ModalBottomSheet` 内超屏内容不会自动滚动**：面板必须自带 `verticalScroll`，
    否则横屏下主题行不可达（本会话真机已修）。
11. **adb 点击 Material3 Slider 轨道可以跳值**（真机验证用），但按像素换算的落点
    有 ±2% 误差；需要精确默认值时多点一次或用 `value` 文本确认。
12. **离线保存进度会把 ticks 算错 1000 倍**：服务端不可达时 `getItem(...).runTimeTicks`
    取不到，旧实现退回 `DEFAULT_BOOK_TIMELINE_TICKS`（10¹⁰），而本书真实 `RunTimeTicks`
    只有 10⁷；这个错误值会被随后的回传写到服务端。修复：把 `runtimeTicks` 缓存在本地进度
    记录里，离线沿用缓存值（`ReaderRepositoryImpl.ensureRuntimeTicks`）。
13. **清待同步标记必须校验 `updatedAt`**：回传是异步的，`markSynced(itemId)` 会把回传期间
    新翻页写入的记录也标成已同步，导致这一页进度永远不回传。改为
    `markSyncedIfUnchanged(itemId, updatedAt)`。
14. **WorkManager 周期任务最小 15 分钟**，不能当"联网后立即回传"用；真正的快速补传是阅读页
    内 30 秒 ticker（只在有待同步记录时触发），Worker 负责进程被杀 / 长时间离线后的兜底。
    Worker 无待同步记录时只读一次 JSON，不发请求。
15. **Readium 的 CBZ 判定是「全包扩展名白名单」**：`ArchiveSniffer.sniffContainer` 只在「压缩包内
    所有条目扩展名 ∈ 位图 + `acbf`/`xml`」或「资源带 `.cbz` 扩展名」时才判 InformalComic。本项目
    取书统一落盘为 `{itemId}.book`（无扩展名），Anda's Game 里的 `Fonts/*.ttf`、`*.otf`、`*.txt`
    会让整包被判成普通 ZIP → `ImageParser` 返回 `FormatNotSupported` → 阅读页「解析书籍失败」；
    futuristic_tales 只有 jpg 所以能开。**教训：格式能力不能只看容器，要看库的具体判定条件。**
16. **Kotlin 注释里出现 `/*` 会开启嵌套块注释**：KDoc 里写路径通配（如 `Fonts/*.ttf`）会报
    `Unclosed comment`，且报错位置在文件末尾，容易误判成后半段代码问题。
17. **ktfmt 是独立门禁**：新代码编译通过 ≠ 格式通过；本波 8 个文件首轮 `ktfmtCheck` 全红，需先跑
    `:modes:book:ktfmtFormat`（只格式化本模块，避免顺手改动其他任务线文件）。
18. **`detectTransformGestures` 会把单指拖动也消费掉**：把它挂在 Pager 的页面上，分页 / 双栏模式
    会「翻不动页」（真机实测：页指示停在 1/15）。修复：自定义 `detectMultiTouchZoom`，只在
    **≥2 指**时 `calculateZoom/calculatePan` 并 `consume()`，单指拖动留给 `HorizontalPager`。
19. **真机并发会把对方 APK 覆盖掉**：本波首轮真机验证跑在旧代码上（日志里 `openPublication` 行号与
    源码不符），事后查明是另一会话在 00:22 重装了自己的构建。**教训：真机验证前先核验安装包
    与本地构建一致（比对 APK 大小 / dex 特征字符串），再由负责人统一调度设备。**
20. **阅读页顶栏没有状态栏内边距**：`ReaderTopBar` 用 56dp 高度直接顶到窗口顶部，K60 状态栏 138px
    把上 2/3 压住——`uiautomator` 报按钮 `[959,15][1178,183]`，实际可点区只有 y 138–183；adb 点
    y=99 会落到状态栏（表现为「点了没反应」）。真机脚本要用 y≈170 命中，或等 UI 线补
    `statusBarsPadding()`（W8 只统一了主界面三个页面）。**W15-UI（2026-10-02）已修复**：
    `ReaderTopBar` 补 `statusBarsPadding()`，三形态真机取证见 §7.8。
21. **多指手势的注入方式**：`adb shell input` 只有单指；`sendevent /dev/input/event*` 在 K60 被
    SELinux 拒（shell 虽在 `input` 组，写入仍 `Permission denied`）；可行路径是临时 `app_process`
    + `InputManagerGlobal.getInstance().injectInputEvent(event, WAIT_FOR_FINISH)` 构造 2 指
    `MotionEvent`（注意：`InputManager.getInstance()` 在该 MIUI 上会抛 NPE，必须走 `InputManagerGlobal`；
    keyguard 窗口会拒收注入的 MOVE——解锁后再注入）。验证完必须删除 `/data/local/tmp` 下的 dex
    与 `/sdcard` 截图。
22. **"横版整页"也是"对图"，但它不需要合并**：金田一原画 PDF 的 4768 页是 1920×1350 横版整页
    （每页一张已含中缝的对开图）。跨页合并的几何门槛必须限定"半页是竖版"（0.55–0.98），否则横版
    整页会被拿去和邻居配对；反过来这类素材在**双栏**下会被排成"一屏 4 页"，正确形态是分页
    （1 页/屏）——已记 W22 遗留（§9）。
23. **中缝判定不能只看"有没有内容"**：真实书页的内缘常常本来就是纸白，两张独立页强切之后也都能
    有内容。可靠信号是"内缘亮度随行号变化的曲线相关性"：本实现取连续性 + 起伏 + 相关系数 +
    归一化平均差四条门槛，且**均匀色块（std≈0）必须判"无法确认"**——不能因为两条曲线都平、
    差值 =0 就当连续（否则黑边页会误拼）。

24. **Compose inline `key` 里的 `return@key` 会让 D8 无法 dex**：Kotlin 2.4 为 inline `key` 里的标签返回
    生成 `$$$$$NON_LOCAL_RETURN$$$$$` 合成类（方法名 `<anonymous>`），D8 报「Method name '<anonymous>'
    cannot be represented in dex format」，`:app:phone:mergeLibDexLibreDebug` 直接失败；改为
    `if (...) { ... }` 包裹即可（本波真机前门禁拦下）。
25. **页指示的 0/1-based 混用会静默显示错页号**：`lastPageIndex` 传 0-based 槽末页、函数内部按 1-based
    转换，否则两页槽会显示成单页号（真机把「21-22/30」显示成「21/30」）；单测要同时锁两种口径。
26. **合成裁剪要区分「内缘在左还是右」**：拼图左半的内缘在其右缘、右半的内缘在其左缘（LTR / RTL 同相位）；
    裁剪方向写反时合成图宽高比仍然正确、只是中缝保留纸白（白带宽度≈纸边全宽），肉眼才看得出来。
    几何抽 `spreadHalfSourceRange` 纯函数单测锁住。
27. **真机自造素材的注入方式（服务器只读前提）**：把本地生成的 CBZ `adb push` 到 `/sdcard`，再经
    `cat … | run-as <pkg> sh -c 'cat > files/books/<itemId>.book'` 覆盖已下载缓存（`ensureLocalFile`
    只检查文件存在与长度 >0）；验证后按原字节还原缓存并删除 `/sdcard` 临时件，全程不写服务器。
28. **`LazyColumn` 重复 key 是硬崩溃，片段命中位置不能对窗口做 `indexOf`**：W29 首轮真机搜索
    「attention」直接 `FATAL EXCEPTION: Key "0-25-9" was already used`（ReaderActivity 被
    Force finishing）。根因是 `buildSnippet` 在窗口里找**第一处**命中——同一页出现两次相同关键词时两条
    命中的片段位置相同 → 列表 key 重复。修复：命中位置按「原始下标 → 折叠后下标」逐字符映射，
    key 再加下标护栏；回归单测必须覆盖「同页多次命中位置互不相同」（只看 `substring` 相等会漏）。
    另：`adb shell input text` 会弹 IME，弹窗按钮坐标会整体上移——脚本里必须重新 dump 取 bounds，
    用旧坐标会点到 scrim 把弹窗关掉（本波两次「保存没生效」都是这个原因，不是应用缺陷）。
29. **大文档「逐页 `PdfRenderer.openPage`」的 native 内存不回收**：W26 的 `pageAspectRatios()` 对
    3649 页 PDF 逐页 openPage/close 后，进程 native heap 从 58 MB 涨到 **716–748 MB**（TOTAL PSS
    357 → 962 MB），扫描完成后不回落、切回分页也不回落（PSS 里 670–720 MB 是 swapped）。
    W22 时代同文档双栏只有 327.7 MB（那时没有版式扫描）。**教训：大文档的"元数据扫描"也要按内存红线
    设计**，并在 >1000 页素材上实测。**W33 已修复**：只读元数据不要用渲染器——改走 PdfBox 页树
    （`/CropBox` + 继承 `/Rotate`），native heap 回到 55–68 MB、PSS 285–310 MB 且能回落（踩坑 30）。
30. **「读页面尺寸」要区分元数据与渲染两条路径**：`PdfRenderer.Page.width/height` 只能通过
    `openPage` 拿到，而 openPage 在 PdfRenderer 的 native 侧有释放缺陷（踩坑 29）；PdfBox 的
    `PDPage.cropBox` 是页树元数据读取，不需要打开 / 渲染页面，代价只有 8 MB 主缓冲 + 临时文件。
    两者口径要对齐：`/Rotate` 只接受 90 的倍数、非法值按 0（PdfRenderer 行为），90/270 交换宽高、
    缺省 `/CropBox` 回退 `/MediaBox`（PdfBox 自带）。`PdfLayoutSourceTest` 用 JVM 生成 PDF 锁定这三条。
    **教训：任何"全书扫描"先问一句"这是什么性质的数据"**，能读元数据就不要渲染。
31. **SAF `content://` 的 fd 不能「一次 read 打一次系统调用」**（W48 真机拦下）：把 `dup` 出的 SAF fd 直接
    接到 PdfBox（`Os.pread` 逐次调用）后，2.53 GB / 5006 页的 PDF 进双栏后 **CPU 100%、8 分钟不结束**——
    SAF fd 多是 FUSE / 代理 fd，每次 `pread` 都有跨进程开销，而 PdfBox 解析（逐字节 token + 页树对象）
    把小块读打成了系统调用风暴。改成 4 KB 页 + LRU 缓存（`PagedPositionalReader`，与 PdfBox 自带
    `RandomAccessBufferedFileInputStream` 同口径）后，同一操作数秒完成、内存不变。
    **教训：给随机访问接口做 fd 直连时，先对齐上游实现的缓冲粒度；「本地文件 11s」不等于「SAF fd 11s」。**

## 9. 未决问题与下一波

### W2 遗留（交接 W3）

- ✅（W3-R3 已完成）`ReaderActivity` 改回 `exported=false`；Books 库 → 书籍详情 → 阅读器路由由 R3
  在 `NavigationRoot.kt` 统一注册，`EXTRA_ITEM_ID` / `EXTRA_TITLE` 路由参数沿用 W1 契约。
- 「跟随」主题有单测覆盖（系统深色 → 深色、浅色 → 纸色）；真机系统当前为浅色，
  未单独点选走查，W3 可在系统深色下补一次回归。
- 字体只提供默认 + Readium 内置字体族（衬线 / 无衬线 / 等宽）；设计 §3.1 的
  Noto Serif SC / 思源宋体（OFL 1.1，打包子集）待字体资源到位后接入。
- 字号 / 行距 / 边距滑块为实用子区间（0.7–2.5 / 1.0–2.0 / 0.0–2.0），
  如需放宽改 `ReaderSettings.kt` 顶部常量。
- 本地进度仍是文件版 `progress.json`；W3 迁 Room 时补迁移与并发测试。
- 设备占用纪律生效：本会话真机验证需排队等待其他会话释放（见 §7.5）。

### W3 遗留（交接 W4 / 负责人）

- ✅ 入口与 `exported=false` 已由 R3 在 W3 一并落地（rebase 到 `f283ef1` 时合入）。注意：本会话
  §7.5 的真机验证跑在 rebase 之前（manifest 仍 `exported=true`）的 APK 上，用 adb 显式启动；合入
  后入口链路以 R3 的页面走查为准，adb 直启需临时改 manifest。
- 书签只做到"添加 / 跳转 / 删除"；高亮、笔记、导出 / 跨端同步后置（EB-8）。
- 阅读页不提供"删除下载"入口：正在渲染的 Publication 仍可能按需读取本地文件，删除交给后续
  下载管理 UI（`ReaderRepository.deleteLocalFile` 已就绪）。
- Room 迁移（D11）未执行：等 R2-LYRICS 合并、schema 空闲后由 R1 单独提交一次迁移。

### W3 / W4

- W3：Room 本地 Locator、批注、离线进度队列（WorkManager）、30 秒定时上报。
- W4：PDF 路线 spike 后定稿、CBZ 判定与自研窗口化、内存红线验证。

### W4 遗留（交接负责人 / W5）

- **真机验证未跑完**（2026-10-01 按负责人调度暂停）：只完成 attention PDF 打开 + 翻页；
  虚构推理 PDF、两本 CBZ 的三档模式、EPUB 回归、内存采样见 §7.6「未完成」清单，等重新指派设备后补。
- ✅（W9-READER 已完成）**RTL 开关**：`SpreadOrder.kt` + `SimpleBookView(reverseLayout)` + 面板开关 +
  `pref_reader_rtl`（D17）；横屏双栏 RTL = 右 2n+1、左 2n+2，滚动模式不变。
- **跨页拼接**（EB-4 后置项）：D19 研判后本波不实施，方案与后续试点条件已记录（需横版对图测试书）。
- ✅（W9-READER 已完成）**滚动模式手势缩放**：`ZoomablePage` 下放到滚动列表页项，双指优先、
  单指不抢纵向滚动，1×–4× 与分页共用 `PageZoom`（D18）。
- **位图长边上限 2048**（ARCHITECTURE §3.4 的示例值）：Paged / TwoColumn 下页面按高度适配，
  文字 / 线条锐利；滚动模式下页面按屏宽铺满（Pad 5 横屏约 1.7× 软放大）会略糊，若后续要更锐，
  按模式分别调 `PAGE_BITMAP_MAX_SIDE_PX` 并复测内存。
- **PDF / CBZ 不支持书签与精确 locator**：进度按页索引换算 progression（D16），书签入口隐藏；
  如需批注，需要为页序列格式单独设计锚点模型。
- **排版面板对 PDF / CBZ 只部分生效**：字号 / 行距 / 边距 / 字体 / 对齐是 EPUB（Readium 偏好）专属，
  PDF / CBZ 下这些控件仍是可点的空操作；已生效的是阅读模式（滚动 / 分页 / 双栏）与主题。后续可在
  `ReaderSettingsPanel` 里按 `ReaderDocument` 类型隐藏不适用的行。
- **加密 PDF / 损坏页**：`PdfRenderer` 打不开的文档案走错误态 + 重试；单页渲染失败显示占位 + 重试，
  未做整本文档级降级（如切换其他引擎）。

### W9 遗留（交接负责人 / W5）

- **跨页对图合并**：D19 已给结论与方案（双栏 + 显式开关 + 手动标记起步；合并位图单独预算，
  RGB_565 优先、长边上限 3072、两级槽位窗口）；开工前需要至少一本含横版对图的测试书，
  否则没有可验证样本。
- **RTL「封面单张」排版**：实体漫画常把封面单独放右页、其后 2+3、4+5…；会改动 spread ↔ 页号映射
  与 W4 已验收口径，列为 W5 候选（D17）。
- **PDF / CBZ 的 EPUB 专属行**：排版面板的字号 / 行距 / 边距 / 字体 / 对齐对页序列文档仍是可点的
  空操作（W4 遗留，未在本波扩大改动面）；后续按 `ReaderDocument` 类型隐藏。
- **滚动模式清晰度**：位图长边上限 2048 在滚动模式按屏宽铺满时略糊（W4 遗留）；若调高需按模式
  分别设上限并复测内存（EB-3 红线）。
- Room 迁移（D11）仍待 R2-LYRICS 合并后由 R1 单独提交。

### W22 遗留（交接负责人 / W5）

- ✅（2026-10-02 完成）**真机验证**：Pad 5 指派窗口 16:30–16:54，PDF + CBZ 双格式全槽位走查、像素
  对比、内存 A/B、RTL 相位、3649 页大文档压力全部通过（§7.10）。素材 `W22-Spread-Test.pdf/cbz`
  仍留在 `test_files/` 与 Jellyfin 书籍库（用户资产，未删）。
- ✅（W26 完成）**横版整页的形态适配**：双栏下检测横版整页（宽高比 ≥1.15）整屏独占，不再与相邻页
  配对；页号 / progression / 恢复口径经设计后不变（D21、§7.11.1）。实现为版式纯函数
  `SpreadLayout.kt` + 双栏后台扫描。
- ✅（W26 完成）**带纸边的对图合并**：以"纸白 / 低信息带"判据先做内缘裁剪（上限 18% 半页宽），
  用更严的裁剪路径门槛（corr ≥0.75 / diff ≤0.15）兜住误拼；合成时全分辨率重检 + 比例兜底（D21、
  §7.11.3）。W26 新素材 4 对带纸边对图真机 4/4 命中、3 类带纸边负样本 0 误拼。
- ✅（W49 完成）**「未就绪判定」不再被缓存**：W48 真机发现的「瞬时 null 判定被写进缓存 → 该轮不再复算」
  改为结果分型 + 调用侧退避重试（D25、§7.17）；明确不合并仍然只判一次。
- **CBZ 分辨率上限**：CBZ 走 `BitmapFactory` 2 的幂降采样，合并位图可能比 PDF 路径更软；如需更锐
  要改解码方式（分块 / 二次缩放），本波不动。
- 合并判定只覆盖"两页等高"的对图；两半高度差 >2% 的扫描（跨页图被裁成不同高度）本波不合并。

### W26 遗留（交接负责人 / W5）

- **金田一本体未真机下载**：同型横版整页由 `W22-Spread-Test` 的横版页对覆盖（几何口径一致），但金田一
  原画 PDF（2.36 GB）未在真机下载打开——首次进入双栏要扫描 5006 页尺寸，耗时未实测（30 / 44 页样本
  瞬时完成）。若后续验证发现扫描过慢，可改为「滚动 / 分页不扫描 + 双栏按需扫描」之外的渐进策略。
- **灰区页维持 W22 行为**：宽高比 0.98–1.15 的近似方形页既不独占、也不参与合并（配对渲染）；如需
  覆盖需重新标定阈值与负样本。
- **裁剪上限 18%**：真实扫描纸边超过半页宽 18% 时放弃裁剪（退回两页渲染，保守）；若遇到更宽的纸边
  扫描件需按素材重新定阈。
- **判定扫描的边界成本**：`pageAspectRatios` 对 CBZ 要读 3649 个条目头、对 PDF 要 openPage 全书；
  大文档首次进双栏会有一次后台扫描（不阻塞首帧，完成前用 W22 版式占位）。

### W29 遗留（交接负责人 / 下一波）

- ✅（2026-10-02 真机完成）**真机走查**：Pad 5 按 §7.12.5 清单跑完（§7.13），真机拦下 1 个搜索崩溃
  缺陷已修复复验（`fix/w29-search-hit-position`，单测 101 → 103）。
- **仍未覆盖（等下一波 / 视需要）**：手机形态（K60）下的搜索面板与结果列表可达性；
  旋转页（`/Rotate` 90/270）命中矩形相位（真机样本里没有旋转页素材）；
  搜索输入响应在文本层大文档（数千页纯文字 PDF）上的实测（本波大文档样本是扫描件，走「无文本层」路径）。
- **合并槽位的叠加映射**：W22/W26 命中对图的合并槽位不叠加批注 / 命中矩形（数据仍按逻辑页保存）。
  后续可用 `spreadPieceOrder` + `spreadHalfSourceRange` + 裁剪比例把逻辑页矩形映射到合并位图；
  映射要做成纯函数 + 单测（左半裁内缘、右半裁内缘的方向不能写反，踩坑 26）。真机已确认现状是
  「不叠加且无错位」（A/B 页面区域 0 差异，§7.13.3）。
- **CBZ 未开放搜索 / 批注**：同一页模型与同一存储格式可直接复用（无文本层 → 只开放批注即可），
  本波为了验收口径只开放 PDF；如需开放是一行开关 + 真机走查。
- **BouncyCastle 瘦身**：`exclude` 三件套可 −10.5 MB（+13.04 MB → +2.5 MB）；
  代价是证书加密 PDF 走 `NoClassDefFoundError` 降级（搜索报错、页面渲染与批注不受影响），
  需要真机用加密样本验证后再做。
- **重复搜索无索引缓存**：每次改关键词都从头扫一遍（扫描件毫秒级、文本层 PDF 分钟级）；
  若真机实测文本层 PDF 体验差，可做「页文本缓存 / 落盘索引（按 itemId + 文件大小指纹）」，
  并把「从当前页向外扫」作为可选项。
- **搜索会话内存**：`PDDocument`（8 MB 主缓冲 + 临时文件）在首次搜索后按书常驻到换书；
  真机实测 Native ≈64 MB（§7.13.2），与 EB-3 页窗口同量级，暂不需要调整；若后续要更省可改为
  「关面板即 `close()`」或 `setupTempFileOnly()`。
- **命中矩形与片段的一致性**：片段按原文本（含换行）匹配，矩形按压缩文本匹配，两者在
  「跨行拆词」时可能数量不一致（矩形按序号配对、多余矩形丢弃）；真机走查时确认贴合度。

### W26 遗留补充（本波 PSS 采样发现，交接 W26 / W5 线）——**W33 已修复（2026-10-02，§7.14）**

- ✅ **进入双栏的「全书宽高比扫描」在大文档上内存不回收**（踩坑 29）：虚构推理 3649 页
  冷启动分页 PSS 357 MB（Native 58 MB）→ 切双栏 962 MB（Native 748 MB）→ 扫描完成后 929 MB →
  切回分页 897 MB（不回落）。W22 时代同文档双栏 327.7 MB（当时无扫描）⇒ W26 引入的回归。
  修复（W33，分支 `fix/w33-spread-scan-memory`）：采用建议方向①「用 PdfBox 读页尺寸（只解析页树，
  不逐页 `openPage`）」——新增 `PdfLayoutSource`（页树元数据批量读、8 MB 主缓冲 + 临时文件、
  `/Rotate` 折算、失败回退逐页），真机 3649 页 Native 55–68 MB / PSS 285–310 MB 且能回落，
  W22 测试书 8/8 命中 + 横版独占 + 0 误拼不回归（§7.14）。未采用②「分块 + `System.gc()`」：
  根因是 native 侧不回收，强制 GC 不解决、还引入停顿；也未采用③④（按需扫描 / 阈值禁扫描）：
  前者改版式语义（横版页在可视区外时无法提前独占）、后者牺牲大文档的版式正确性。
  其余候选（未采用，留档）：② 分块扫描 + 每块 `System.gc()` / 显式回收；
  ③ 只扫「可见区 + 前后 N 页」的按需版式（与 `pageAspectRatios` 的缓存语义配套）；
  ④ 扫描时禁止 3649 页级大文档一次性扫描（超过阈值走分页语义并提示）。
  **开工前建议先在 5006 页金田一样本上复现一次**（本波只测了 3649 页 PDF 与 30/44 页小样本）。

- ✅ **W47 发现的 D-W47-1：本地 SAF（`content://`）PDF 双栏仍走逐页扫描 → 大书被杀**（金田一 5006 页：
  Native 1.59 GB / PSS 2.45 GB → MIUI SIGKILL；W33 只修了 File 路径）。**W48 已修复**（分支
  `fix/w48-saf-pdf-scan-memory`，§2 D24 / §7.16）：SAF fd 经 `dup` + `Os.pread` 页缓存接
  `PdfLayoutSource`（`PDFParser` + `ScratchFile`），K60 复验 Native 49.8–53.9 MB、PSS 与同机基线持平且
  60 s 不增长、可回落，扫描日志 `pages=5006 slots=4973 landscape=4938` 与修复前一致；批量路径不可用时
  的大书兜底阈值（1500 页）见 §2 D24 第 3 条。

## 10. W64 阅读加载取消 / 打开耗时 / 批注范围（2026-10-04，分支 `fix/w64-reader-music-home`，起点 master `a8a580f`）

### 10.1 加载中返回仍占网络（修，提交 `06bd7d8`）

**复现**：打开一本**未下载**的大书（如虚构推理 639.6 MB），在「正在下载 / 加载」阶段返回书架 ——
网络请求继续把整本书下完，`files/books` 残片继续变大。

**根因**：`ReaderRepositoryImpl.download()` 用阻塞 `OkHttp.call.execute()` 在 `Dispatchers.IO` 里跑；
`viewModelScope` 取消只能让协程在挂起点退出，**不能中断线程里阻塞的 socket 读** → 下载一路跑完。

**修法**：

1. `data/ReaderRepositoryImpl.download()`：读循环每块 `ensureActive()`；注册当前 `Job` 完成回调 →
   协程取消时 `call.cancel()` 立即中止在途 HTTP；`finally` 释放回调；catch 里 `ensureActive()`
   把 cancel 抛出的 `IOException` 归一为 `CancellationException`，并删除 `.part` 残片（不留半截文件）。
2. `modes/book/ReaderViewModel` 的 `open()` / `openLocal()` / `downloadBook()`：`runCatching` 改为
   try/catch，`CancellationException` 直接重抛 —— 取消不再被写成「打开失败 / 下载失败」。

**真机取证**：K60 `8e875894` 未接入 adb、Pad 5 `43af8627` 被 W63 会话占用 → **本波未完成，待设备窗口**。
步骤：打开未下载的大书（虚构推理）→ 加载中立即返回 → 用 `adb shell cat /proc/<uid>/...` 或
`logcat`（cancel 后无下载字节增长）取证「网络请求已停止、`.part` 已删除」。

### 10.2 已下载的书打开偶尔慢（修，提交 `06bd7d8`）

**根因**：打开路径 = 文件检查 → `getReadingProgress()` → 解析文档；其中 `getReadingProgress` 在
网络活跃时**无条件同步查服务器**（`Items/{id}/UserData` + `Items/{id}` 两次请求，服务器偶发慢 /
超时最长 30 s）—— 书已经下载完成，打开首帧却被网络拖住，表现为「偶尔很慢」。

**修法**：`fetchRemoteProgress` 外包 `withTimeoutOrNull(2000ms)`，超时 / 失败回退本地进度
（正常网络下多设备进度不受影响）；`fetchRunTimeTicks` 的 `runCatching` 重抛取消异常；
`ReaderViewModel` 打开路径加分段耗时日志（文件 / 进度 / 文档 / 合计），供回归采样。

**优化前后数字**：**待真机窗口实测**（同书同起点 3–5 次取中位数；对照组 = 飞行模式直开）。

### 10.3 批注范围（只读核对 + 文案，提交 `06bd7d8`）

**核对结论**：批注（与搜索）为 **PDF 专属** —— `ReaderScreen.pdfToolsAvailable` 只在
`ReaderDocument.Simple.format == Pdf` 时为 true，顶栏「搜索 / 批注」仅此时出现；EPUB（Readium Rich）
与 CBZ 都没有入口。用户看到的「虚构推理、W22-Spread-Test 有批注」= 两本都是 PDF；其他书没有入口
是**当前设计**，不是缺陷。

**文案**：`ReaderAnnotationSheet` 副题补「批注仅支持 PDF，EPUB / CBZ 暂不支持」；
`ReaderSettingsPanel` 末尾加同口径说明（对 EPUB / CBZ 用户也能看到）。

### 10.4 EPUB 批注成本评估（本波不实现，待负责人确认是否扩展）

- **可行路径**：Readium 3.4 有 Decoration API（`DecorableNavigator`，`DecorationStyle` 高亮），
  可复用现有 `ReaderAnnotation` JSON v1 存储与面板 UI。
- **成本**（估 **5–8 人日**）：
  1. 选择粒度从「矩形框选」改「文本选择」（Readium `Selection` / 选区监听）；
  2. 锚点从「页面归一化矩形」改「Locator + 文本上下文」（progression 会随字号 / 排版漂移，
     现有矩形锚点不能直接复用）；
  3. 渲染 = Decoration 叠加（与现有 `PageOverlay` 是两套叠加路径），需覆盖双主题 / 字体 /
     双栏布局回归与真机矩阵。
- **建议**：本波不做（范围控制）；如确需 EPUB 批注，单开一波按上述方案细化。**请负责人确认。**

### 10.5 工程与真机状态

- 门禁：根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿；8 任务 `--rerun` **716 项 / 0 失败 0 错误**
  （基线 713 + 新增 3：core 1 + music 2）。
- **真机测试未覆盖**（K60 未接入 adb + Pad 5 被 W63 占用）：10.1 取消取证、10.2 打开耗时前后数字、
  批注文案真机走查均待设备窗口。测试服务器全程只读。

## 11. 变更日志

| 日期 | 变更 |
|------|------|
| 2026-09-30 | W1-R1 创建本文档；完成 Readium PoC、进度接口、单测与真机验证 |
| 2026-09-30 | W2-R1：阅读模式（滚动 / 分页 / 双栏）、排版设置（字号 / 行距 / 边距 / 字体 / 对齐）、阅读主题（纸色 / 护眼 / 深色 / OLED / 跟随）；`pref_reader_*` 持久化；9 项单测 + Pad 5 真机验证 |
| 2026-09-30 | W3-R1：离线整书下载（原子落盘 + 状态 UI）、进度离线队列（30 秒 / 退后台 / WorkManager 启动补传 + 周期兜底、冲突策略细化、runtimeTicks 缓存）、书签基础（JSON + 面板）；15 项单测（data 10 / modes:book 5）；真机验证见 §7.5 |
| 2026-09-30 | W3-R1 rebase 到 `f283ef1`（R3 Prism 阅读页 + 入口）：`ReaderScreen.kt` 冲突按「保留 R3 外壳 + 并入离线功能」解决——顶栏沿用 Prism（`ReaderTopBar` 扩展下载状态 / 书签入口）、错误态用 `CinefinEmptyState`、`CompositionLocalProvider(LocalMediaColors)` 与设置面板保持 R3 版；离线暂存横幅、`jumpTarget` / `onJumpHandled` / `onNavigatorReady`、书签面板与 `DownloadAction` 全部保留 |
| 2026-10-01 | W4-R1：PDF 走 PdfRenderer 自研（D14）、CBZ 走 ZipFile 自研（D15，含 Andas_Game 解析失败根因）、格式嗅探与页进度语义（D16）；`SimpleBookView` 三档模式 + 页指示 + 双指缩放；格式嗅探健壮性（1 KiB PDF 探针 / mimetype-EPUB / 空图片 ZIP 回退 Readium）；新增 16 项单测（模块合计 31 项，全绿）；真机部分验证（§7.6：attention PDF 打开 + 翻页）后按负责人调度暂停；踩坑 15–19 |
| 2026-10-01 | W4-R1 真机验收完成（Pad 5，负责人指派窗口）：attention（15 页）/ 虚构推理（3649 页，639.6 MB）/ Anda's Game（24 页）/ futuristic_tales（4 页）逐本打开 + 分页 / 双栏 / 滚动三档行为通过（双栏双截图核对左右为不同页）；EPUB《雷普利全集》回归通过（Locator 推进 + 回传）；虚构推理连翻 40 页 + 静止复测：稳态 **≈358 MB**，与 attention（≈334 MB）同量级，内存不随页数 / 文档大小增长（EB-3 达标）；`progress.json` 五条记录 `pendingSync=false`（D16 换算精确）。详见 §7.6 |
| 2026-10-01 | W9-READER（分支 `feature/w9-reader-comics`）：①RTL 右起翻页（D17）——`SpreadOrder.kt` 页序层（`spreadPageSlots` / `spreadCount` / `isRtlPaging`）+ `SimpleBookView` 分页 / 双栏镜像 + 阅读设置面板开关（仅 PDF / CBZ）+ `pref_reader_rtl`；②跨页对图合并研判（D19）——本波不实施，记录内存 2× / 无可靠元数据 / 接缝与语义耦合四条理由与 W5 试点方案；③滚动模式双指缩放（D18）——`ZoomablePage` 下放到滚动列表页项（双指优先、单指不抢滚动），与分页共用 `PageZoom`（1×–4×、平移夹取、非有限值守卫）；新增 12 项单测（模块 43 项，6 个测试类全绿）+ 门禁全绿；真机验证见 §7.7 |
| 2026-10-01 | W9-READER 真机验收完成（K60 `8e875894`，负责人指派 + 设备解锁后窗口）：RTL 三档（分页方向镜像 / 双栏逐像素水平镜像 / 滚动不变）、PDF 同样生效、滚动模式双指缩放（1.16→2.92、上限 4.00、捏回过 1× 不抢纵向滚动）、跨页结论可见性核对（两本书全竖版页，无对图样本）、EPUB 回归（WebView / locator 推进 / 书签增删 / 待同步队列），无 FATAL / ANR，设置与设备副作用全部还原。新发现阅读页顶栏未避让状态栏（§7.7.5、踩坑 20）、多指注入方法（踩坑 21） |
| 2026-10-02 | W15-UI：阅读页顶栏避让修复——`ReaderTopBar` 补 `statusBarsPadding()`（与 W8 `CinefinPageTopBar` 同款），平板竖屏 / 横屏 / 手机形态三态真机取证，按钮 top 全部 > 状态栏高度；设置面板 / 页指示复核无遮挡。见 §7.8；首页平板海报等宽修复另见 `UI_PLAN.md` D35 |
| 2026-10-02 | W26-READER（分支 `feature/w26-reader-forms`）：①双栏「横版整页独占」——新增 `SpreadLayout.kt` 版式纯函数（阈值 1.15、槽位划分、RTL 相位、页→槽映射）与双栏接入（后台扫描尺寸、无横版不重建、独占槽整屏 Fit），页指示按实际页范围；②带纸边对图裁剪合并——`SpreadMerge.kt` 列统计 / 纸白判据 / 裁剪上限 / 更严裁剪路径门槛，合成时全分辨率重检 + 比例兜底 + 源范围纯函数（修「裁剪方向写反」真机缺陷）；③单测 57 → 77；④自造 `W26-Spread-Edge-Test`（44 页）离线 12/12 命中 0 误判、真机 12/12（4 条 trim 路径）+ W22 8/8 回归 + 0 误拼 + RTL/progression 不回归（§7.11）；门禁四绿；见 §2 D21 |
| 2026-10-02 | **W22-R1（分支 `feature/w22-spread-merge`）：跨页对图合并（D20）** —— 判定 / 几何纯函数（`SpreadMerge.kt`：竖版半页几何门槛、内缘亮度取样、中缝四条门槛、双栏槽位相位含 RTL、合并尺寸）+ `SpreadMergeCache.kt`（256 px 缩略图判定 → 两半统一高度合成 → 2 张 LRU → 邻槽预取 → 失败回退两页）+ `SimpleBookView.kt` 双栏槽位接入 + `PageSource.pageSizePx`；**只在双栏生效**，页号 / progression / 分页 / 滚动语义零变化，无新增偏好键；新增 14 项单测（模块 57 项，7 类）全绿；门禁根 `assembleDebug`（含 TV）+ `ktfmtCheck` + app / book 单测通过；素材复核与阈值标定见 §7.9（金田一原画 PDF 5006 页：4768 页横版整页已是对图 → 无合并触发点，另暴露"双栏一屏 4 页"的形态问题）；随后自造 `W22-Spread-Test`（PDF 36.1 MB / CBZ 34.6 MB × 30 页，脚本 `tools/w22-spread-test/make_spread_test_book.py`），离线自测四组（LTR/RTL × PDF/CBZ）全部 8/8 命中、0 误判（§7.9.4）；真机待派窗口 |
| 2026-10-02 | **W22-R1 真机终验完成**（Pad 5 `43af8627`，16:30–16:54，负责人指派窗口；素材 = 用户上传的 `W22-Spread-Test` PDF + CBZ）：双栏逐槽位 —— 8 对拆页型对图**全部命中**（`reader spread merge` 8 条，continuity 0.86–0.89 / corr 1.00 / diff 0.00）、5 对独立单页 + 低相似 + 横版整页**0 误拼**；中缝空带合并 0 px / 未合并 307 px、相邻两列 corr ≥0.9972；滑动后即时帧无两页闪动；内存 A/B **+12.0 MB ≈ 1 张合并位图**（PDF）、CBZ −17.3 MB（噪声内）、3649 页真实漫画冷启动稳态 **327.7 MB** 且 0 次误拼；RTL 相位 / 页号 / progression / 冷启动恢复不回归；无 FATAL/ANR；设备副作用还原。详见 §7.10 |
| 2026-10-02 | **W29-R1（分支 `feature/w29-pdf-search-annot`）：PDF 搜索 + 本地高亮批注**（D22 / D23）——①搜索：引入 `PdfBox-Android 2.0.27.0`（Apache-2.0，无 native；依赖只加 `modes/book/build.gradle.kts` + `libs.versions.toml`），`PdfBoxPageTextSource`（8 MB 主缓冲 + 临时文件、Mutex 串行、逐页流式、空页补齐）+ `PdfSearchEngine`（单页 ≤6 / 整篇 ≤400、达上限即停、进度每 25 页、可取消）+ 搜索面板（去抖 400ms、LazyColumn 增量追加、命中词高亮）+ `PageOverlay` 命中矩形叠加；无文本层 / 上限 / 取消 / 失败均有明确文案；②批注：`ReaderAnnotation` + version 1 JSON 编解码 + `ReaderAnnotationStore`（`filesDir/reader/annotations/{itemId}.json`，原子写）+ 矩形框选 / 备注 / 列表跳转 / 删除，不写服务器、不用 Room、不依赖文本层；③单测 77 → **101**（新增 `PdfSearchTest` / `ReaderAnnotationTest` / `PageOverlayGeometryTest` 共 24 项）；④门禁 `assembleDebug`（含 TV）+ `ktfmtCheck` + app + book（101）四绿；⑤本地取证（未占真机）：桌面同源 PdfBox 实测（15 页文本层 152 ms/页；5006 页扫描件 0.2 s 空扫）与整包 A/B（**+13.04 MB**，102.40 → 115.45 MB），见 §7.12；真机窗口待负责人调度（§9 遗留） |
| 2026-10-02 | **W29 真机验收（Pad 5 `43af8627`，负责人指派窗口）+ 真机缺陷修复**（分支 `fix/w29-search-hit-position`，基于整合版 master `e465a83`）——①**真机拦下的崩溃**：搜索「attention」触发 `IllegalArgumentException: Key "0-25-9" was already used`（同页两次命中片段位置相同 → 结果列表 LazyColumn key 重复）→ 修复 `buildSnippet` 为「原始下标 → 折叠后下标」逐字符映射 + key 加下标护栏（踩坑 28），补 2 项回归单测（101 → **103**），同命令复验 65 条命中无崩溃；②搜索真机：命中列表懒加载滚动、跳转 `分页 · 5/15` + 命中矩形像素取证（5 条色带 33 px 高、带内文字像素 5.9–13.4%）、无文本层提示（W22 PDF ≈1–2 s）、非 PDF 无入口、虚构推理 3649 页首扫 ≈14 s / 热扫 ≈1.3 ms/页、退后台取消（已扫描 2125/3649 → 已暂停）、搜索会话 Native ≈64 MB；③批注真机：新增（空/带备注）→ 文件 version 1 落盘、编辑回填原备注、弹窗与列表两路删除、列表跳转、页面叠加与存储矩形换算一致、RTL 双栏相位锚点一致（LTR x 347–601 / RTL x 1147–1401，高宽 y 相同）、合并槽 A/B 页面区域 0 差异；④**顺带发现 W26 线缺陷**（踩坑 29，未修）：3649 页 PDF 进双栏的版式扫描把 Native 58 → 716–748 MB（PSS 357 → 962 MB）且不回收，交 W26 线；⑤设备还原：批注文件 / 目录删除、prefs 回 `paged` + `rtl=false` + `eyecare` + 字号 0.9988、旋转未改、App force-stop、`/sdcard/w29.xml` 删除，K60 未触碰。详见 §7.13 |
| 2026-10-02 | **W33-READER-PERF（分支 `fix/w33-spread-scan-memory`，起点 master `9738877`）：双栏版式扫描内存回归修复**——①根因：W26 进双栏逐页 `PdfRenderer.openPage` 读尺寸（踩坑 29），native 不回收；②方案：新增 `PdfLayoutSource`（PdfBox 页树元数据批量读：`/CropBox` 回退 `/MediaBox` + 继承 `/Rotate` 折算、8 MB 主缓冲 + 临时文件、失败回退逐页），`PageSource.pageAspectRatios()` 可选批量路径 + `collectPageAspectRatios()` 优先批量，渲染路径不变；③单测 103 → **106**（`PdfLayoutSourceTest`：旋转折算 / 页树批量读取与缺省回退 / 继承 Rotate）；④门禁四绿（`assembleDebug` + `ktfmtCheck` + app 61 + book 106）；⑤真机 Pad 5：虚构推理 3649 页切双栏 Native 55–57 MB / PSS 286–299 MB（修复前 748 MB / 962 MB）、能回落、扫描日志 `slots=1825 landscape=0`；W22 测试书 `landscape=2 at=14,15` + 对图 8/8 + 0 误拼 + RTL / 搜索 65 条 / 批注不回归；⑥设备副作用还原（见 §7.14） |
| 2026-10-03 | **W48-READER-PERF（分支 `fix/w48-saf-pdf-scan-memory`，起点 master `1ebdbcc`）：本地 SAF PDF 双栏扫描内存爆增修复（D-W47-1）**——①根因：W33 只覆盖本地缓存文件路径，本地媒体库 `content://` 的 `PdfPageSource(descriptor)` 仍 `layout = null` → 双栏逐页 `PdfRenderer.openPage`（W47 金田一 5006 页 Native 1.59 GB / PSS 2.45 GB 被杀）；②方案（D24）：`PdfLayoutSource.forDescriptor()`——`dup` 独立 fd + `Os.pread` 定位读（探针判定可 seek）+ `PDFParser(RandomAccessRead, ScratchFile)`（8 MB 混合缓冲），**4 KB 页 + 256 页 LRU 缓存**（首版无缓存真机 CPU 100% 数分钟不结束，踩坑 31）；③兜底：`PageSource.perPageAspectScanMaxPages`（PDF = 1500），批量不可用时大书跳过逐页扫描用安全默认并打 `skip-fallback` 日志，小书仍逐页；④单测 476 项（book 106 → **110**：阈值边界 / fd 可用性判定 / 页缓存合并读与跨页 / 随机读源批量读取）；⑤门禁四绿（根 `assembleDebug` 含 TV + `ktfmtCheck` + 7 个测试任务 `--rerun` 476 项 0 失败）；⑥真机 K60：金田一 5006 页扫描日志与修复前逐字一致（`slots=4973 landscape=4938`），Native 49.8–53.9 MB / PSS 358–368 MB（同机滚动基线 316.3 MB）、60 s 不增长、可回落；W22 `landscape=2 at=14,15`、RTL 相位对图 8/8、0 误拼；⑦设备副作用还原（§7.16） |
| 2026-10-03 | **W49-READER（分支 `fix/w49-leftover-cleanup`，起点 master `3dbca99`）：对图判定「null 不缓存 + 可重试」（D25）**——`SpreadMergeDecisionResult`（`Ready` / `NotReady`）+ `SpreadMergeDecisionMemo` 只缓存明确结论；`shouldRetry` + 250 ms 退避重试（上限 3 次）+ 停稳预取补当前槽；单测 110 → **113**（`SpreadMergeDecisionMemoTest` 3 项）；真机 LTR 冷启动 8/8 + 切 RTL 重建缓存 8/8 + `landscape=2 at=14,15` + 0 误拼（§7.17）；同波将 CBZ 页序自然序比较器下沉 data 共用（`DOWNLOAD_PLAN` §17） |
| 2026-10-04 | **W62（分支 `fix/w62-regression-defects`）：EPUB「滚动」口径复核（非缺陷，不改代码）**——Readium 3.4.0 Android 的滚动 = **每个 spine 资源内部垂直滚动 + 资源之间左右滑动翻页**（无「纵向滚到底自动翻章」；AAR `R2BasicWebView.scrollLeft/scrollRight` + `disablePageTurnsWhileScrolling` 佐证）；真机 Pad 5（滚动档）：正文资源 `split_008/009/016` 上下滑 **18.7–22.7%** 像素位移、资源底部再滑 0.00–0.01%、左右滑翻资源 18.5–19.2%（logcat 资源名 `_split_009 → _split_010`）；W61「上下滑无位移」= 书首封面 / 扉页 / 版权等**不足一屏**资源 + 滚到边界。人工复测步骤见 `TEST_PLAN` §7.6.6 F3。 |
