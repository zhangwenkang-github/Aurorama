# Cinefin 阅读器任务线（READER_PLAN）

> 本文件是阅读器线（R1）唯一权威文档：需求、决策、进度、验收标准与踩坑库都写在这里。
> 项目级状态仍写 `docs/PROJECT_PLAN.md`，本文件不复制项目级内容。

| 项 | 值 |
|----|----|
| 任务线 | 阅读器（EPUB / PDF / CBZ） |
| 会话 | W1-R1 骨架 → W2-R1 主体 → W3-R1 离线与进度 → W4-R1 · PDF / CBZ 格式支持（R1-PDF-CBZ） |
| 分支 | `feature/r1-pdf-cbz`（W4） |
| 基线 | `master` `f44f89e`（2026-10-01） |
| 状态 | W4 PDF（PdfRenderer 自研）与 CBZ（ZipFile 自研）完成：编译 + 单测（31 项）+ ktfmt 通过，Pad 5 真机逐本验收 + 大 PDF 内存采样通过（§7.6） |

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
- [ ] 真机验证记录写入 §7.7（K60 `8e875894`：CBZ RTL 三档方向 / 滚动双指缩放 / 跨页结论 / EPUB 回归）。

## 7. 真机验证记录（2026-09-30）

设备：Xiaomi Pad 5（`nabu`，型号 21051182C），Android 13，2560×1600。

### 7.1 打开与渲染

```text
adb shell am start -W -n com.zhangwenkang.cinefin.debug/\
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

测量方法（可复现）：`adb -s 43af8627 shell dumpsys meminfo com.zhangwenkang.cinefin.debug`
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

### 7.7 W9 RTL / 滚动缩放真机验证（2026-10-01，Redmi K60 `8e875894`）——**进行中**

设备：Redmi K60（`8e875894`，1440×3200，Android 13 / MIUI），分支 `feature/w9-reader-comics`；
入口 = App 内「媒体库 → 书籍 → 点书 → ReaderActivity」（`exported=false`，adb 不能直启）。
设备缓存（`run-as … ls files/books`）：`6bbbb0ce-7d2d-3500-ac6e-9b12054be028.book` 719,861 B
= futuristic_tales（CBZ / 4 页）、`e2d0c13d-…` 670,643,292 B = 虚构推理（PDF / 3649 页）、
`81099153-…` 2,220,485 B = 雷普利全集（EPUB）。

> 待填：RTL 三档方向、滚动双指缩放、跨页结论、EPUB 回归、稳定性与释放时间（见 device-lock）。

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

## 10. 变更日志

| 日期 | 变更 |
|------|------|
| 2026-09-30 | W1-R1 创建本文档；完成 Readium PoC、进度接口、单测与真机验证 |
| 2026-09-30 | W2-R1：阅读模式（滚动 / 分页 / 双栏）、排版设置（字号 / 行距 / 边距 / 字体 / 对齐）、阅读主题（纸色 / 护眼 / 深色 / OLED / 跟随）；`pref_reader_*` 持久化；9 项单测 + Pad 5 真机验证 |
| 2026-09-30 | W3-R1：离线整书下载（原子落盘 + 状态 UI）、进度离线队列（30 秒 / 退后台 / WorkManager 启动补传 + 周期兜底、冲突策略细化、runtimeTicks 缓存）、书签基础（JSON + 面板）；15 项单测（data 10 / modes:book 5）；真机验证见 §7.5 |
| 2026-09-30 | W3-R1 rebase 到 `f283ef1`（R3 Prism 阅读页 + 入口）：`ReaderScreen.kt` 冲突按「保留 R3 外壳 + 并入离线功能」解决——顶栏沿用 Prism（`ReaderTopBar` 扩展下载状态 / 书签入口）、错误态用 `CinefinEmptyState`、`CompositionLocalProvider(LocalMediaColors)` 与设置面板保持 R3 版；离线暂存横幅、`jumpTarget` / `onJumpHandled` / `onNavigatorReady`、书签面板与 `DownloadAction` 全部保留 |
| 2026-10-01 | W4-R1：PDF 走 PdfRenderer 自研（D14）、CBZ 走 ZipFile 自研（D15，含 Andas_Game 解析失败根因）、格式嗅探与页进度语义（D16）；`SimpleBookView` 三档模式 + 页指示 + 双指缩放；格式嗅探健壮性（1 KiB PDF 探针 / mimetype-EPUB / 空图片 ZIP 回退 Readium）；新增 16 项单测（模块合计 31 项，全绿）；真机部分验证（§7.6：attention PDF 打开 + 翻页）后按负责人调度暂停；踩坑 15–19 |
| 2026-10-01 | W4-R1 真机验收完成（Pad 5，负责人指派窗口）：attention（15 页）/ 虚构推理（3649 页，639.6 MB）/ Anda's Game（24 页）/ futuristic_tales（4 页）逐本打开 + 分页 / 双栏 / 滚动三档行为通过（双栏双截图核对左右为不同页）；EPUB《雷普利全集》回归通过（Locator 推进 + 回传）；虚构推理连翻 40 页 + 静止复测：稳态 **≈358 MB**，与 attention（≈334 MB）同量级，内存不随页数 / 文档大小增长（EB-3 达标）；`progress.json` 五条记录 `pendingSync=false`（D16 换算精确）。详见 §7.6 |
| 2026-10-01 | W9-READER（分支 `feature/w9-reader-comics`）：①RTL 右起翻页（D17）——`SpreadOrder.kt` 页序层（`spreadPageSlots` / `spreadCount` / `isRtlPaging`）+ `SimpleBookView` 分页 / 双栏镜像 + 阅读设置面板开关（仅 PDF / CBZ）+ `pref_reader_rtl`；②跨页对图合并研判（D19）——本波不实施，记录内存 2× / 无可靠元数据 / 接缝与语义耦合四条理由与 W5 试点方案；③滚动模式双指缩放（D18）——`ZoomablePage` 下放到滚动列表页项（双指优先、单指不抢滚动），与分页共用 `PageZoom`（1×–4×、平移夹取、非有限值守卫）；新增 12 项单测（模块 43 项，6 个测试类全绿）+ 门禁全绿；真机验证见 §7.7 |
