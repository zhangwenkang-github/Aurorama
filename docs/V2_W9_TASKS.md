# 2.0 W9 · 账户设置 + 旧 bug 清扫 + 全域回归 + 发布（任务卡）

> 状态：**T0 聚合裁定完成**（2026-11-23 会话10，Kimi 亲自，纯文档无代码）：W7 裁定登记 11 项 + W8 backlog 12 条逐条裁定（NOW 12 / LATER 8 / DROP 3），任务卡 T1–T9 骨架定稿；W8-T3 个人评分备份分支裁定 **DROP（不启用，分支保留备查）**，全局搜索不渲染 **NOW 立项（W9-T3）**；W4–W6 backlog 补汇总登记为 T8 前置（§15 要求 T0 汇总 W4–W8，本卡按用户指令先聚合 W7+W8）。
> 依据：`docs/V2_REQUIREMENTS.md` §15（W9 ← 全部：账户设置 + 旧 bug 清扫 + 全域回归 + 发布）；设计唯一权威 `docs/V2_DESIGN.md`。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W9 基线 = W8 收官 HEAD（以 git log 为准）。
- **裁定口径**：NOW = W9 本波次交付（P0 > P1 > P2，P0 = 数据丢失/资源耗尽级，P1 = 核心链路正确性，P2 = 小修与体验补齐）；LATER = 2.0 内不排期（W10/2.x 或挂 W5 链候选）；DROP = 不做或已另有出口。
- **逐条裁定**：见下方「裁定汇总」两表（W7 来源 / W8 来源）；任务卡骨架见「任务卡」节——骨架只定目标/来源/边界/验收方向，**各卡开工时由 Kimi 定稿设计口径与完整验收要点**（设计分权不变）。
- **T1/T2 已收口（2026-11-23 会话11，K60 真机验收全绿）**：T1=`441b5dc9`（`DownloadSourceControl.adoptGeneration` 入队 adopt 新代次 + claim 拒绝有界退避 ≥1s 带原因日志 + 引擎级测试真跑）；T2=`4b8edfe7`（toggle 拆 `downloadSong`/`cancelSongDownload`/`deleteSong`，下载入口永不删除、COMPLETED/队列中只提示；菜单按 `isDownloaded` 渲染「删除下载」；`projectLocalPlaylistTrack` 与最近播放（MusicDatabase v5→v6 加可空 serverId/userId 列 + `MIGRATION_5_6`）两条投影补 identity）；W8-T5 预存红测试收口=`2ee5f71d`。K60 链路实证：同进程删除→再入队 taskGeneration=3 直接 COMPLETED（修复前 claim 永久拒绝）、logcat 仅一条「下载完成：夜曲（26952162 字节）」、取消后 20s 日志零行（无自旋）、菜单「删除下载/取消下载/下载」三态渲染全对、删除三清（文件/sources/sidecar）。F1b 根因三候选定论 = ③identity 缺侧（两条投影路径丢 serverId/userId），①②被 K60 sidecar+Room 实证排除。
- **T3 已收口（2026-11-23 会话12，Pad 真机双路径实证全绿）**：`2b82145e`（deepseek）。根因=渲染层：NavHost `fillMaxSize` 的固定 min=max 高度约束经 AnimatedContent 原样传给目的地根节点，M3 SearchBarLayout 把输入框钳到整屏高、结果区 maxHeight 被压成 0（连空态都不显示）；状态层经复现单测钉死无罪。修复=SearchBar modifier 自防御 `freeSearchBarHeight()`（测量前放宽 minHeight=0）+ 约束纯函数回归锚点。Pad 实证：全局搜 Tonari 结果区渲染 25 条（邻座的怪同学/隔壁的吸血鬼美眉/S1:E1–E3）；音乐内搜 Nirvana（`includeItemTypes=Audio`，会话11 原复现路径）渲染 2 条；几何铁证=输入框 bounds 高 147px（修复前整屏 ~2560px）。证据 `F:\Develop\code_data\qa\w9-t3\`。
- **T5 部分收口（2026-11-23 会话12，K60 断网实证：⑥✅/F4可见✅/F4可播❌→残项 F4b）**：三 commit（deepseek）——`ac91523f`（F4 根因1=在线分支失败从不读已下载曲库，修=`MusicLibrarySourceLoader` 降级+只填空不覆盖；⑥根因=`MetadataSnapshotStore` contentGeneration 是进程内代次、冷启动全失效，修=跨进程可读+浏览类 30 天离线服务窗口）+ `5f1b8027`（下载页已完成音乐单击直开本地播放，复用 `DownloadPlaybackRequest.Music` 通道）+ `49e8f5fd`（可播返修：离线索引宽容判据 vs `getCompletedSource` SQL 死认 `'COMPLETED'`——「两套已完成判据」继 F1b 后第二次现身；新增 `getDownloadedLocalSource` 按 itemId 直查只看文件存在、`MusicTrackResolver` 先本地短路，红→绿契约 5 条）。K60 断网（`svc wifi/data disable` 温和手段，验后恢复）实证：首页/媒体库缓存渲染无「加载数据错误」、降级曲库 songs=1 带日志铁证；**F4 可播重复失败**：tap 歌曲行零 resolve 日志（播放器空开、无会话），按播放键走持久化队列快照里的旧流 URL（jellyfins，非当前 jellyfin-test 会话）→ UnknownHost/BUFFERING 卡死——根因=队列快照恢复路径不重新解析，登记残项 **F4b（P1）**（见裁定登记）。观察：降级曲库冷启动单曲不稳定（三曲轮换）；下载页 `getDownloads()` 不含 Audio → `5f1b8027` 真机不可验、仅单测作证。证据 `F:\Develop\code_data\qa\w9-t5\`。
- **设备与取证约束（新）**：Pad=43af8627 直连测试服 admin，**飞行模式测试禁用**（ZTE CPE 蜂窝脆弱，掉注册 47+ 分钟）——离线场景真机取证改用 K60=8e875894（生产服只读，浏览/离线播放等只读场景可用；一切写验证仍只用 Pad + 测试服）。写补偿末环（断网写→恢复补传）若需 Pad 断网，改用「关 Wi-Fi/拔 CPE 网线」等温和手段先试，触发异常即停止上报。**K60 断网定式（会话12 定型）**：`svc wifi disable` + `svc data disable`，`dumpsys connectivity` 确认 none 后取证，验毕 `enable` 恢复。
- **通用约束（每卡继承，不在卡内复述）**：构建 `./gradlew.bat :app:phone:assembleDebug --console=plain` + 根 `ktfmtCheck` + 相关模块单测绿；每任务一子代理一 commit；子代理禁 adb；红线见 `AGENTS.md`；证据清单 = 构建/测试输出关键行 + diff 摘要，核心链路另需真机文本取证（Kimi 执行）。

## 裁定汇总

### W7 裁定登记来源（11 项，`V2_W7_TASKS.md`「裁定登记」）

| 条目 | 内容 | 裁定 | 去向/理由 |
|------|------|------|-----------|
| F1b | 音乐重下载删档绕过（「下载·离线」实为 toggle，误删已下载文件；根因已定位） | **NOW P0** | W9-T2；数据丢失风险，修复方向已登记 |
| F2 | 删除链取消 worker → WM 退避致 PENDING 滞留 | **NOW P0** | W9-T1，与 F7 同域并案 |
| F7 | 引擎空转热循环（同进程删除→再入队代次错位，claim 永久拒绝 + ~8.7 次/秒自旋 CPU 满载；根因强推断已定位） | **NOW P0** | W9-T1；资源耗尽 + 「App 内删除不能终止」 |
| F4 | 音乐离线冷路径断（冷启动索引 songs=0、已完成点击仍发网络请求） | **NOW P1** | W9-T5：冷启动可见/降级已修（`ac91523f`+`49e8f5fd`，K60 实证）；**可播 → 残项 F4b**（队列恢复重解析，P1，见裁定登记） |
| F5 | 书籍链无完成/失败通知 | **NOW P2** | W9-T7 |
| F6 | 下载中心书籍按作者折叠 | LATER | 增强项，依赖 S1 样本验证，W10 候选 |
| L1 | 下载页已完成分组行整行可点 | LATER | 体验打磨，backlog-low 维持 |
| L2 | 已下载音乐歌词离线保存核对 | LATER | 挂 W5 音乐链 |
| L3 | 有声书章节数投影 | LATER | 依赖 S1 样本，随 W9-T8 补验窗口再评 |
| O1 | 无 sources 时大小恒「未知」（零网络口径固有取舍） | **DROP** | 维持 OUT-接受，代码注释已明示 |
| S1 | 书籍/有声书/SP/无季 Show 服务端样本窗口 | **NOW（用户动作申请）** | W9-T8 前置；解锁书籍侧 12/21–24/34/37/44/45 真机补验 |

### W8 backlog 来源（12 条，`V2_W8_TASKS.md` T7 卡移交清单）

| 条目 | 内容 | 裁定 | 去向/理由 |
|------|------|------|-----------|
| ① | T0 裁定 LATER 四项写能力：Likes / 合集创建加入 / 刷新元数据 / 图片上传删除 | LATER（维持） | WRITE-01 完备度扩展，2.0 内不排期，W10/2.x 候选 |
| ② | 全局搜索结果区不渲染（T7 复现：`GET /Items?searchTerm=` 正常返回但结果区空白，指针 `SearchState.kt:38-52`） | **NOW P1** | **W9-T3 ✅ 已收口**（`2b82145e`，会话12 Pad 双路径实证；根因=NavHost 固定高度约束压垮 M3 SearchBar 结果区） |
| ③ | `ServerAddressesViewModel` 删当前地址被拒无 UI 反馈（仅 Timber 日志） | **NOW P2** | W9-T6；会话7 设备事故的直接诱因之一 |
| ④ | WRITE-11 轻缺口：`primaryImageUrls`/`musicTrackCache` 无身份 60s TTL 缓存（`JellyfinRepositoryImpl.kt:206/208`） | **NOW P2** | W9-T6；多服隔离收口，小改动 |
| ⑤ | 音乐域收藏写失败不排队（与影视详情路径补偿策略不统一，WRITE-05 ⚠️+ 残留） | **NOW P1** | W9-T4 |
| ⑥ | 飞行模式下媒体库/首页直出「加载数据错误」不降级 Room 缓存 | **NOW P1** | **W9-T5 ✅ 已收口**（`ac91523f`：快照跨进程可读 + 浏览类 30 天离线窗口；会话12 K60 断网实证首页/媒体库缓存渲染） |
| ⑦ | T1r 遗留：SyncWorker 只扫 movies/episodes，季/剧排队写不补传（WRITE-08 ⚠️+ 残留） | **NOW P1** | W9-T4 |
| ⑧ | T3' 遗留：主题感知评分色（黄铜直引 `ColorDark.tertiaryDark`）+ 音乐域评分显示（T3b） | LATER | 评分色=主题 token 打磨；T3b 挂 W5 音乐链 |
| ⑨ | T4 遗留：6 按钮批量条 360dp 竖屏挤压风险 + 新建/加入后 refresh 粒度 | 拆分：目检 **NOW P2**（W9-T6，K60 只读目检）；refresh 粒度重构 LATER | 目检若命中再立案 |
| ⑩ | T5 遗留：季页头部「季 · N」计数删除后当页不刷新 | **NOW P2** | W9-T6 |
| ⑪ | T3 备份分支 `backup/w8-t3-personal-rating` 个人评分写入启用裁定 | **DROP（不启用）** | 需求账本无个人评分条目（T3 转向已裁定正确需求=服务器评分展示，T3' 已交付）；且已探明写评分会顺带翻转服务端 Likes，语义有副作用。**分支保留不删备查**；未来用户明确要个人评分时再评估 rebase（届时需重验 Jellyfin 12 端点行为） |
| ⑫ | T6 观察：`adb input text` 空格需 `%s` 转义（环境级，非缺陷） | **DROP（转环境记忆）** | 补入 engramory 环境/取证条目，不占任务卡 |

合计：NOW 12（P0×3：F1b/F2/F7；P1×5：F4/②/⑤/⑥/⑦；P2×4：F5/③/④/⑩；⑨目检计入 T6 不单独计数）/ LATER 8（F6/L1/L2/L3/①/⑧/⑨refresh/——S1 为用户动作申请单列）/ DROP 3（O1/⑪/⑫）。

## 任务卡（T1–T9 骨架）

> 骨架口径：定目标/来源/文件边界/验收方向；设计口径、完整验收要点、写边界声明在各卡开工时由 Kimi 定稿补全。依赖：T1/T2/T3 互不依赖可并行；T4 依赖 W8-T1 写错误模型（已在）；T5 依赖 T2（同触音乐索引判定，错峰或同代理顺序做）；T8/T9 最后。

### T1 下载引擎稳定性：代次错位热循环 + PENDING 滞留并案（F7+F2，P0）

- **目标**：修复同进程「删除→再入队」代次错位导致的 claim 永久拒绝与 ~8.7 次/秒引擎空转热循环；修复删除链取消 worker 后 WM 指数退避致 PENDING 滞留。
- **修复方向（W7 已登记，开工复核）**：①`DownloadSourceControl` 增 `adoptGeneration`（enqueue 写行成功后内存 adopt 新代次，只增不减）+ 契约测试「同进程 delete→enqueue→runQueue 必须 claim 1」；②`runQueueInternal` 区分「可领取/待退避」，`ran==0 && claimed==0` 时有界退避（≥1s）并打 claim 拒绝原因日志；③补 `DownloaderImplRecoveryTest` 覆盖缺口（`:1292-1368` 现只断言插入从未跑引擎）。
- **文件边界**：`core/utils/DownloaderImpl.kt`、`core/utils/DownloadSourceControl.kt`（引擎域，属申报项，开工卡内申报）；不碰 UI、不动 Room schema。
- **验收方向**：同进程删除→再入队→下载实际执行且可终止；logcat 无高频 runQueue 自旋；K60 真机回归下载/暂停/删除/恢复全链路。
- 派 deepseek，1 卡；真机取证 Kimi（K60）。

### T2 F1b 音乐重下载删档绕过（P0）

- **目标**：曲目菜单按 `isDownloaded` 渲染「删除下载」；`toggleSongDownload` 拆 `downloadSong`/`deleteSong`，下载入口不再含 delete 分支（保底 = toggle 命中 COMPLETED 不删只提示）。
- **开工先办**：一条 logcat 定论 `isDownloaded` 误判三候选（legacy 记录 sidecar 缺 / 不可读 / identity 缺侧），指针 `MusicModeViewModel.kt:203-206`（判定）与 `:1911-1917`（toggle 删除点）。
- **文件边界**：`modes/music/.../MusicModeViewModel.kt` + 曲目菜单渲染处 + 规则纯函数单测。
- **验收方向**：已下载曲目点「下载·离线」无删除无二次入队；删除只能走显式「删除下载」；K60 真机双路径取证。
- 派 deepseek，1 卡（与 T1 文件域不重叠，可并行）。

### T3 全局搜索结果区不渲染（P1，W8 会话7 复现立项）

- **目标**：定位并修复「搜索 API 正常返回但结果区空白」（复现证据 `F:\Develop\code_data\qa\w8-t7\t7b.log`：`GET /Items?searchTerm=Tonari` 12:10:54 发出、结果区空白；代码指针 `SearchState.kt:38-52`）。
- **文件边界**：搜索状态/结果渲染链路（开工时由定位结论圈定）；不动搜索 API 层（已实证正常）。
- **验收方向**：Pad（测试服）搜索电影/单集/音乐关键词结果正常渲染；回归搜索筛选/分页不破坏。
- 派 deepseek，1 卡；真机取证 Kimi（Pad）。
- **✅ 已收口（2026-11-23 会话12，`2b82145e`）**：根因/修复/Pad 双路径实证见状态块。

### T4 写补偿统一（W8 ⑤+⑦，P1）

- **目标**：⑦SyncWorker 扫描范围从 movies/episodes 扩展到季/剧排队写（WRITE-08 缺口收口）；⑤音乐域收藏写失败接入 T1 写错误模型 `QueuedForSync` 排队补偿（与影视详情路径统一，WRITE-05 ⚠️+ 残留收口）。
- **文件边界**：`core` SyncWorker/调度（`SyncWorker.kt:77-89` 现仅两表）、`data` 音乐收藏写路径（`JellyfinRepositoryImpl` / `MusicRepository`）；复用 W8-T1 `UserDataWriteOutcome` 模型，不新建错误体系。
- **验收方向**：季/剧收藏排队行可被补传（代码+单测；末环真机受 Pad 断网约束，取证方案开工定稿——优先「关 Wi-Fi」温和断网，异常即停）；音乐收藏失败可见且排队。
- 派 deepseek，1 卡。

### T5 离线体验批（F4 + W8 ⑥，P1）

- **目标**：F4 音乐离线冷路径——离线媒体库索引含已下载音乐（修复冷启动 songs=0）+ 已完成音乐点击直开本地不再发 `Items` 请求（加载/导航层，T5b 已修 DataSource 暖路径）；⑥断网（飞行模式）下媒体库/首页降级 Room 缓存而非直出「加载数据错误」。
- **文件边界**：`data` 离线索引/仓储读路径、`modes/music` 点击路由、`app/phone` 首页/媒体库错误降级分支。
- **验收方向**：K60 断网（飞行模式）只读场景：媒体库/首页显示缓存内容、已下载音乐冷启动可见可播；Pad 不做飞行模式（设备纪律）。
- 派 deepseek，1 卡；与 T2 同触音乐判定逻辑，**T2 合并后开工**。
- **⚠️ 部分收口（2026-11-23 会话12）**：`ac91523f`+`5f1b8027`+`49e8f5fd`；⑥与 F4 冷启动可见 K60 断网实证绿；**F4 可播 → 残项 F4b**（队列快照恢复不重新解析，见裁定登记）。

### T6 W8 遗留小修批（③+④+⑩+⑨目检，P2）

- **目标**：③`ServerAddressesViewModel.deleteAddress` 被拒时给 snackbar 反馈（现仅 Timber）；④`primaryImageUrls`/`musicTrackCache` 60s TTL 缓存加 (serverId,userId) 身份键（`JellyfinRepositoryImpl.kt:206/208`，WRITE-11 轻缺口收口）；⑩季页头部「季 · N」计数删除后当页刷新；⑨6 按钮批量条 360dp 竖屏挤压 K60 只读目检（命中再立案，refresh 粒度重构 = LATER）。
- **验收方向**：③④⑩ 各配单测/契约 + 真机点验；⑨ dumpsys/目检即结。
- 派 deepseek，1 卡（小项可卡内分 commit）。

### T7 书籍通知接入（F5，P2）

- **目标**：BookDownloadManager 完成/失败通知接入 + 点击直达阅读器（复用 W7-T5 `DownloadNotificationDestination` 扩展模式），不可达回退下载页已完成分组。
- **验收方向**：通知出现+直达（无服务端书籍样本时用既有本地书重下/契约作证；S1 样本到位后 T8 补真机全链路）。
- 派 deepseek，1 卡。

### T8 收官前置：W4–W6 backlog 补汇总 + 账户设置域裁剪 + S1 补验（P1，文档+真机）

- **目标**：①汇总 W4/W5/W6 波次文档 backlog/观察项并补裁定 NOW/LATER/DROP（§15「T0 汇总 W4–W8」的剩余部分）；②账户设置域（W9 名义任务）现状盘点与 2.0 范围裁剪；③S1 样本窗口若已开放：补验 W7 ⚠️ 书籍侧 12/21–24/34/37/44/45 与 L3/F6 再评估。
- Kimi 主导，1 卡（文档为主；需用户在测试服建样本时另行申请）。

### T9 全域回归 + 发布（末卡）

- **目标**：全域回归矩阵（播放/下载/服务器写/搜索/离线核心链路，K60+Pad 分工按设备纪律）+ 全模块单测/ktfmt/Release 构建 + 版本号与发布材料（参照 1.4.1 流程）；push/tag/GitHub Release 需用户授权。
- Kimi 主导，1–2 卡；所有 NOW 卡收口后开工。

## 裁定登记（W9）

- **T5 残项 F4b（2026-11-23 会话12 登记，NOW P1，独立小卡）**：队列快照恢复路径不重新解析媒体源——断网冷启动点降级曲库已下载曲目仅空开播放器（tap 零 resolve 日志、无媒体会话），按播放键用 `MusicQueueStore` 持久化快照里的旧流 URL（jellyfins，非当前 jellyfin-test 会话）→ UnknownHost、BUFFERING 卡死。**修复方向**：恢复队列条目按当前解析链（含 `getDownloadedLocalSource` 本地短路）重解析 `mediaSourceUri`（或恢复时标记未解析惰性重解析）；`MusicQueueStore` 持久化口径一并审；配「快照 URI 过期→重解析命中本地文件」契约测试。证据 `F:\Develop\code_data\qa\w9-t5\`（`r4-tap-live.log`/`offline-play-fail.log`/r2–r4 dump 序列）。真机取证 Kimi（K60 断网）。
- **T5 观察项（会话12 登记）**：①降级曲库冷启动单曲不稳定（songs=1 但条目轮换，疑离线下载库查询缺稳定排序，随 F4b 同域再看）；②下载页 `getDownloads()` 不含 Audio 条目 → 下载页音乐单击直开本地（`5f1b8027`）真机不可验，仅 20 条单测作证；若未来下载页纳入 Audio 需补验；③`MovieScreen/EpisodeScreen` 严格快路径仍用旧 `completedLocalSource` 判据（与 F4 可播同族，LATER 候选）。

- **T1 复核发现（F2 表述修正，会话11）**：代码中**不存在**「删除链取消引擎 worker」调用点（`cancelUnique` 仅用于图片任务）；PENDING 滞留可由「claim 被拒 + 0 延迟重排 + APPEND_OR_REPLACE 链」完整解释，T1 修复已覆盖。若真机再复现 PENDING 滞留，需新一轮取证另立。
- **T2 遗留观察（会话11登记）**：①v5 之前旧最近播放行在再次播放前仍无 identity，菜单可能仍显「下载」，但点击走 sources 保底只提示不删（无害，T5 同域再评）；②拆分新增 `cancelSongDownload` 入口（原 toggle 取消能力的必要出口）；③长按 sheet 对本地媒体库曲目不再显示死入口「下载 · 离线」（去噪）；④取消下载后 sources 落 FAILED/CANCELLED（retry=0 不重试）+ `.download` 残片与 sidecar 记录暂留（既有语义，菜单正确回到「下载」、引擎静默）——残片清理策略维持现状，2.0 内不另立。
- **W9-T3 旁证（会话11）**：K60 音乐内搜索同症状复现——`GET /Items?searchTerm=Nirvana&includeItemTypes=Audio` 正常发出、服务端有数据、结果区空白（18:35 取证），与 T7 复现一致，T3 开工可直接用此路径。
- **W8-T5 回归收口（会话11）**：`adaptIsolatedFindroidMovie` 未随 FindroidMovie `canDelete`（27 参）同步致 2 条 core 测试红，`2ee5f71d` 已修，`:core:testDebugUnitTest` 481/481 绿。
- **设备事实修正（会话11）**：K60 的 App 当前实际连**测试服** admin 会话（logcat URL 实证，非"生产只读"）；zhangwenkang/w8limited 存储 token 已 401 失效（切账号被拒根因，UI 无反馈），admin token 有效。
- **主题体系核查（会话11，用户反馈「主题与设计不一样」）**：主题切换链路实证**无故障**（K60 切浅色整页即时生效，K1 定稿 token 与 `CinefinTokens` 逐值一致）；aurora/AMOLED/动态色三者深色差异天然细微是设计口径（§2 明暗与取色为正交维度），非 bug。两处真实落差登记：①五框架稿（Prism/Nocturne/Monolith）经 2026-11-18 K1 定稿判为 LATER、不做进 2.0.0，Lumen 退役遗产条目——属决策非遗忘；②K1 帧「首页 Hero 整体构图」布局（V2_DESIGN §4）原无立项——**已立项 V2-UX-17（2026-11-23 用户裁定，排 W10/2.x 不进 2.0.0 发布门）**；Prism/Nocturne/Monolith 框架稿**维持 LATER 裁定**（用户同口径确认，以后再说）。
