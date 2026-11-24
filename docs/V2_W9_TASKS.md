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
- **F4b 已收口（2026-11-23 会话13，`407e4a39`，deepseek，K60 断网实证绿）**：根因=`MusicQueueStore` 持久化的 `mediaSourceUri` 是上会话产物（旧主机 jellyfins/旧 UserId），恢复路径（`MusicQueuePersister.load`/`resumeRestored`/歌单快照复用）当已解析结果直接交播放器，从不重解析。修复=恢复快照非本地地址逐条走 `getDownloadedLocalSource` 本地短路（命中换本地路径、未命中保留、恢复阶段零网络）+ 起播前当前曲按 `MusicTrackResolver` 当前链重解析（失败沿用快照）+ 歌单快照复用只信任本地可播地址 + `restoredTrackSource` 按 `isLocalPlaybackUri` 判本地（修下载绝对路径误标 SERVER）。红→绿契约 6 条、`:modes:music` 425 测试全绿；K60 断网定式实证：快照当前曲带旧 jellyfins URL → 点播放无 ExoPlayer 错误、`media_session PLAYING` position 5.8s→17.8s 推进；未下载曲目仍按原行为跳过。**遗留观察**：①降级曲库 songs=1 真因=K60 `sources` 表仅 1 行、另 3 个文件是无下载行的孤儿文件（「缺稳定排序」推测不成立；孤儿对账/清理是否立卡待裁定）；②`resumeRestored` 异步化有极短窗口：解析中编辑恢复队列，本次起播用进入时快照。至此 **T5 全收口（⑥/F4可见/F4可播全绿）**。
- **T4 已收口（2026-11-23 会话13，`e74b4f9c`，deepseek，Pad 温和断网实证绿）**：⑦SyncWorker 扫描集扩为 movies/episodes/shows/seasons ∪ 该用户全部 toBeSynced 行（季/剧页面只建 userdata 行无表投影，旧 Worker 必漏；seasons 经所属剧 serverId 归属；推送/清标记抽成 `UserDataSyncQueue` 可测函数）；⑤音乐收藏写失败原不排队（纯 UPDATE 连本地 userdata 行都没有）——修=收藏落库前先 `getUserDataOrCreateNew` 建行 + `MusicRepository.setFavorite` 委托 `JellyfinRepository.markAsFavorite`（复用 W8-T1 `UserDataWriteOutcome` 统一写模型，实物=`data/.../repository/UserDataWriteOutcome.kt` + `IdentityWriteCoordinator` 无关、调度=`WorkManagerUserDataSyncScheduler`，零新错误体系）+ 无投影条目只推 `isFavorite`（避免整包覆盖清服务端 played/进度）+ 排队时经既有 snackbar 提示「已保存，联网后自动同步」（与影视同口径；角标刷新经统一写路径自带 `notifyFavoriteChanged` 无回归）。测试：core 7 + data 5 + music 8 新增全绿。Pad 断网实证：季（表 0 行）与音乐收藏各落 `toBeSynced=1`，恢复 Wi-Fi 10s 内清零，服务器回读两者 favorite=True 且音乐 played=True 未被覆盖（验毕已还原）。**遗留**：`JellyfinRepositoryOfflineImpl.markAsFavorite` 仍纯 UPDATE（离线模式无音乐入口暂不可达）；`:data:connectedDebugAndroidTest` 预存 AAPT 打包失败（`attr/colorControlNormal`，与本次无关）。
- **T6 已收口（2026-11-23 会话13，`5b1adfa3`/`bbde067d`/`fb3cdcf1`，deepseek，门禁全绿；③④⑩ 真机点验待 Kimi 抽查窗口）**：③删除当前地址被拒改为经 setup 模块既有 Channel→Flow 事件模式发 `CannotDeleteCurrentAddress` + `CinefinSnackbarHost` 可见反馈（setup 模块首次引入测试源集，2 条单测）；④`primaryImageUrls`/`musicTrackCache` 加 `RepositoryCacheIdentity(baseUrl,userId)` 身份键（封面键=(identity,itemId)、曲库改单槽 (identity,tracks)，TTL 语义不变，A/B 身份契约 2 条）；⑩季页头部「季 · N」取 `state.episodes.size`、删除后只靠 RESUMED 时序重取——修=`SeasonViewModel` init 订阅 `UserDataEvents.contentVersion.drop(1)` 广播当下 `loadSeason`（14→13 集即时刷新单测）。**⑨目检命中→立案 NOW P2（见裁定登记）**：K60 实测 411dp 下 6 按钮动作行外沿 291.8dp；360dp 等效内宽 328dp < 需求 345–353dp，超出 17–25dp，`CinefinBatchBar` Row+SpaceBetween 无 weight/横滚，末按钮有挤出风险——**设计裁定（Kimi）= 动作行横向滚动**（按钮保 44dp 触控面，大屏无变化）。单测：setup 2 / data 513 / modes:film 225 / app:phone 607 全绿。
- **T6b 已收口（2026-11-23 会话13，`6d40aba`，deepseek，K60 双宽度取证绿）**：根因=动作行需 6×44+5×4=284dp，360dp 竖屏扣「已选 N 项」后仅 ~267dp，固定 Row 只能挤压末按钮。修复=`CinefinMultiSelect` 动作行 `weight(1f, fill=false)` + `horizontalScroll`（放得下仍 SpaceBetween 右对齐，411dp 视觉零变化；放不下行内横滚，按钮保 44dp；44dp 魔法数提 `CinefinIconButtonSize` 常量）+ 布局预算契约单测（411.4dp 不滚 / 360dp 溢出 25.3dp 必滚 / 1–5 动作不滚）。K60 实证：411dp 6 按钮 bounds 与修复前逐像素一致；360dp 探针页实证容器 `scrollable=true`、视口 258.7dp < 内容 284dp、末按钮被裁未被压。**残项**：①~~横滚手势未录到~~ → ✅ 会话14 抽查窗口补验绿（触摸注入已恢复，swipe 后末按钮完整入视口）；②MIUI 拦 `connectedAndroidTest` 安装（`INSTALL_FAILED_USER_RESTRICTED`，K60/Pad 均复现，harness 已回滚零残留）。证据 `qa/w9-t6/w9-t6b-batch-bar-scroll-check.md`、`qa/w9-spotcheck/`。
- **T7 已收口（2026-11-23 会话13，`9e756ce9`，deepseek，Pad 真机取证绿）**：根因=书籍整本下载走 data 独立 `BookDownloadManager`，从不回调通知出口（data 不反向依赖 core，`DownloadNotifications`/`DownloadNotificationDestination` 在 core 且 internal），路由侧本来就好、缺的是书籍链发通知能力。修复=data 新增公开 `BookDownloadNotificationSink`（完成/失败两回调，只在真正终态触发：成功/transfer 异常/ProvenanceFailed；暂停/取消/恢复补记不发）+ core `BookDownloadNotifications` 把 `BookDownloadTask` 投影成 `DownloadTask(BOOK)` 直接复用既有通知通道与文案 + Hilt 绑定注入。测试 data 4 + core 4（完成回调一次/传输失败/取消不发/冷启动恢复不发 + BOOK 投影/缺名占位/失败映射/deep-link requestCode 分离）。Pad 实证：真实下载 CBZ 落盘后 `dumpsys notification` 见「下载完成 futuristic_tales」（channel=downloads）、PendingIntent requestCode 反算吻合、点击直达 `ReaderActivity`（title=futuristic_tales）、全零 itemId 回退下载页已完成分组。证据 `qa/w9-t7/`。**W9 至此 T1–T7 全部收口，NOW 卡仅剩 T8（Kimi）→ 孤儿对账 → T9**。
- **T8 ✅ 已收口（2026-11-24 会话14，Kimi 主导 + explore 证据核对）**：W4–W6 backlog 逐条裁定与账户设置域裁剪全部落档（见「裁定登记」顶部两条）；预汇编建议全采纳、两处调整（coroutines 条目措辞订正=Gradle catalog 收口；账户切换「401 静默」归因修正→真正静默是 stale/RECOVERING 两分支，立案 T8b）。S1 样本维持「依赖用户动作、不阻塞发布」。
- **T8b ✅ 已收口（会话14，`532e6f22`，deepseek，门禁全绿）**：切账号 `IdentityAttemptStaleException`→`INTERRUPTED`、`IdentityTransitionFailedException`(RECOVERING)→`IDENTITY_RECOVERING` 两静默分支接入既有 `activationFailure` 行内红字（同机制同位置，无 snackbar，不动身份机/TV）；中英繁文案三份；setup 单测 4/4。相邻 401 路径行内红字经抽查窗口真机实证（见下条）。
- **孤儿对账卡 ✅ 代码+dry-run 收口（会话14，`28c95fcc`，deepseek）**：core 新增 `DownloadIndexReconciler` 纯逻辑三分类（linked / orphanMedia=仅命中 `<itemId(UUID)>.<sourceId>[.gN]` 约定且无下载行的正式文件 / residual=残片·witness·sidecar·不识名，永不清理）+ `Downloader.reconcileDownloadIndex()`/`deleteOrphanDownloadIndexFiles()`（删除前重新对账+canonical 根内校验，行快照读取失败零动作）+ 下载页存储占用行右侧「下载索引对账」入口（Lumen 面板对话框 + 二次确认显式开关）。单测 15 条全绿。K60 dry-run 实证：orphan=3·43.5MB 与磁盘 ls、`sources` 表（count=1）逐字节吻合（linked=1·27.1MB、residual=0），零写入零日志错误。**清理裁定（Kimi）：K60 现有 3 孤儿保留作 T9 删除链路真机终验 fixture，T9 窗口验后即清**。证据 `F:\Develop\code_data\qa\w9-orphan\`。
- **K60 抽查窗口 ✅（会话14，deepseek，证据 `qa/w9-spotcheck/`）**：触摸注入已恢复存活（会话13 失效为暂时性）。③删当前地址 snackbar ✅（`该地址正在使用中…` dump 实证）；T8b 相邻 401 切号 ✅（行内红字 `保存的登录已失效…`、停留 admin 不跳转）；④身份键缓存行为点验 ✅（弱：浏览三页零图像/网络错误，精确行为以契约单测作证）；**T6b 360dp 横滚手势 ✅**（临时探针 `swipe 850→400` 后末按钮 A6 完整入视口 `[779,108][905,234]`、回滑逐像素复原，探针已还原、设备复装干净 HEAD）→ T6b 残项①销项。**⑩ 真机点验改判**：删本地下载不触发 `contentVersion` 广播（仅 `deleteItem`/元数据写入触发），原替代路径不能作证；真证=授权后删测试服服务器条目（破坏性，待用户窗口），`SeasonViewModelContentChangeTest`（14→13）已作证，不阻塞 T9。**新环境事实**：Gradle `installLibreDebug` 任务会安装到**所有已连设备**（本次误装 Pad 一次）——多设备在线时改 `assembleLibreDebug` + `adb -s <id> install -r` 定向安装。观察：探针滑到末端时计数标签 a11y bounds 缩窄（文本不变，疑 uiautomator 重叠裁剪伪影），视觉窗口顺手复核。**W9 NOW 卡全部清空，仅剩 T9（回归矩阵草案已入 T9 卡）**。
- **T9 回归 R1–R11 全绿（2026-11-24 会话15，Kimi 主导，deepseek 子代理取证；证据 `F:\Develop\code_data\qa\w9-t9\`）**：K60 在线域 R1 播放（起播/seek/字幕切换✅）/R3 下载引擎（删除→同进程再入队 gen 1→3 claim 成功、20s 自旋 0 行、CPU 7.6%✅）/R4 音乐三态菜单+删除三清（文件/Room/sidecar 三级证据✅）；K60 R5 孤儿清理终验（两轮清孤儿 3 个回收 43.54MiB、linked 27.1MB 与视频保留、sources 表零影响✅）+A3 根因真机钉死+③snackbar 复验✅；K60 断网域 R8（首页/媒体库离线 dump 与在线逐字节一致、零错误态✅）/R2（降级曲库 songs=3、三曲本地短路 fd 硬证据、media_session position 推进、断网定式验后恢复✅）；Pad R6 写补偿（三目标断网收藏 toBeSynced=1→恢复 4–9s 清零→服务端回读 favorite=True 且 played 未覆盖，已还原✅）；Pad R11 季页计数（用户授权删《齐木楠雄的灾难》S1E1 Played=true，计数 24→23 约 1.5s 自动刷新，服务端仅少该集✅）；Pad R7（全局搜 Tonari 25 条/音乐内搜 Nirvana 2 条/影视结果导航✅）；Pad R9（书籍下载完成通知 channel=downloads、requestCode=直达阅读器分支、落点 ReaderActivity 标题正确✅）。回归新发现全部裁定落档（见裁定登记顶部）：搜索音乐结果导航缺陷=2.0.1 P1 首修，余 LATER/观察。
- **T9 R12 发布 ✅（会话15，用户当场授权）**：版本 bump 2.0.0/code 7（`Versions.kt`）；签名发布构建绿（6m59s/410 tasks + mpv JNI 契约 PASS）；双 APK v2 验签指纹 `e449c4aa…e155ff` 一致；发布面冒烟绿（K60 arm64 / Pad universal 自 1.4.1(6) `install -r` 覆盖 Success，0 FATAL/ANR）；**2026-11-24 已发布**：分支推送 `312209a6` + tag `v2.0.0` + GitHub Release 双 APK（发布后下载资产 SHA-256 复核一致），详见 `docs/RELEASE_PLAN.md` §7。**W9 至此全部收口，2.0.0 发布完成；2.0.1 候选池见裁定登记（提示词用户届时自写）。**
- **设备与取证约束（新）**：Pad=43af8627 直连测试服 admin，**飞行模式测试禁用**（ZTE CPE 蜂窝脆弱，掉注册 47+ 分钟）——离线场景真机取证改用 K60=8e875894（生产服只读，浏览/离线播放等只读场景可用；一切写验证仍只用 Pad + 测试服）。写补偿末环（断网写→恢复补传）若需 Pad 断网，改用「关 Wi-Fi/拔 CPE 网线」等温和手段先试，触发异常即停止上报。**K60 断网定式（会话12 定型）**：`svc wifi disable` + `svc data disable`，`dumpsys connectivity` 确认 none 后取证，验毕 `enable` 恢复。
- **通用约束（每卡继承，不在卡内复述）**：构建 `./gradlew.bat :app:phone:assembleDebug --console=plain` + 根 `ktfmtCheck` + 相关模块单测绿；每任务一子代理一 commit；**会话13 起 adb 取证下放子代理（用户拍板，Kimi 额度有限；设备纪律不变：Pad 飞行模式禁用仅温和断网、K60 断网定式验后恢复、异常即停）**，真机证据由 Kimi 按质量门审查；红线见 `AGENTS.md`；证据清单 = 构建/测试输出关键行 + diff 摘要 + 真机文本取证。安装用 `:app:phone:installLibreDebug`（`installDebug` 有 flavor 歧义）。

## 裁定汇总

### W7 裁定登记来源（11 项，`V2_W7_TASKS.md`「裁定登记」）

| 条目 | 内容 | 裁定 | 去向/理由 |
|------|------|------|-----------|
| F1b | 音乐重下载删档绕过（「下载·离线」实为 toggle，误删已下载文件；根因已定位） | **NOW P0** | W9-T2；数据丢失风险，修复方向已登记 |
| F2 | 删除链取消 worker → WM 退避致 PENDING 滞留 | **NOW P0** | W9-T1，与 F7 同域并案 |
| F7 | 引擎空转热循环（同进程删除→再入队代次错位，claim 永久拒绝 + ~8.7 次/秒自旋 CPU 满载；根因强推断已定位） | **NOW P0** | W9-T1；资源耗尽 + 「App 内删除不能终止」 |
| F4 | 音乐离线冷路径断（冷启动索引 songs=0、已完成点击仍发网络请求） | **NOW P1** | W9-T5：冷启动可见/降级已修（`ac91523f`+`49e8f5fd`，K60 实证）；**可播 → 残项 F4b**（队列恢复重解析，P1，见裁定登记） |
| F5 | 书籍链无完成/失败通知 | **NOW P2** | **W9-T7 ✅ 已收口**（`9e756ce9`，会话13 Pad 实证直达阅读器） |
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
| ③ | `ServerAddressesViewModel` 删当前地址被拒无 UI 反馈（仅 Timber 日志） | **NOW P2** | **W9-T6 ✅ 已收口**（`5b1adfa3`，snackbar 反馈） |
| ④ | WRITE-11 轻缺口：`primaryImageUrls`/`musicTrackCache` 无身份 60s TTL 缓存（`JellyfinRepositoryImpl.kt:206/208`） | **NOW P2** | **W9-T6 ✅ 已收口**（`bbde067d`，身份键） |
| ⑤ | 音乐域收藏写失败不排队（与影视详情路径补偿策略不统一，WRITE-05 ⚠️+ 残留） | **NOW P1** | **W9-T4 ✅ 已收口**（`e74b4f9c`，会话13 Pad 断网实证） |
| ⑥ | 飞行模式下媒体库/首页直出「加载数据错误」不降级 Room 缓存 | **NOW P1** | **W9-T5 ✅ 已收口**（`ac91523f`：快照跨进程可读 + 浏览类 30 天离线窗口；会话12 K60 断网实证首页/媒体库缓存渲染） |
| ⑦ | T1r 遗留：SyncWorker 只扫 movies/episodes，季/剧排队写不补传（WRITE-08 ⚠️+ 残留） | **NOW P1** | **W9-T4 ✅ 已收口**（`e74b4f9c`） |
| ⑧ | T3' 遗留：主题感知评分色（黄铜直引 `ColorDark.tertiaryDark`）+ 音乐域评分显示（T3b） | LATER | 评分色=主题 token 打磨；T3b 挂 W5 音乐链 |
| ⑨ | T4 遗留：6 按钮批量条 360dp 竖屏挤压风险 + 新建/加入后 refresh 粒度 | 拆分：目检命中→**T6b ✅ 已收口**（`6d40aba`，动作行横滚；残项=360dp 手势复核）；refresh 粒度重构 LATER | 证据 `qa/w9-t6/` |
| ⑩ | T5 遗留：季页头部「季 · N」计数删除后当页不刷新 | **NOW P2** | **W9-T6 ✅ 已收口**（`fb3cdcf1`，订阅 contentVersion） |
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
- **✅ 已收口（2026-11-23 会话13，`e74b4f9c`，Pad 温和断网实证绿）**：根因/修复/测试/真机证据见状态块。

- **目标**：F4 音乐离线冷路径——离线媒体库索引含已下载音乐（修复冷启动 songs=0）+ 已完成音乐点击直开本地不再发 `Items` 请求（加载/导航层，T5b 已修 DataSource 暖路径）；⑥断网（飞行模式）下媒体库/首页降级 Room 缓存而非直出「加载数据错误」。
- **文件边界**：`data` 离线索引/仓储读路径、`modes/music` 点击路由、`app/phone` 首页/媒体库错误降级分支。
- **验收方向**：K60 断网（飞行模式）只读场景：媒体库/首页显示缓存内容、已下载音乐冷启动可见可播；Pad 不做飞行模式（设备纪律）。
- 派 deepseek，1 卡；与 T2 同触音乐判定逻辑，**T2 合并后开工**。
- **⚠️ 部分收口（2026-11-23 会话12）→ ✅ 全收口（会话13 F4b=`407e4a39`）**：`ac91523f`+`5f1b8027`+`49e8f5fd`；⑥与 F4 冷启动可见 K60 断网实证绿；F4 可播经残项 F4b（队列快照恢复重解析）收口，见状态块。

### T6 W8 遗留小修批（③+④+⑩+⑨目检，P2）

- **目标**：③`ServerAddressesViewModel.deleteAddress` 被拒时给 snackbar 反馈（现仅 Timber）；④`primaryImageUrls`/`musicTrackCache` 60s TTL 缓存加 (serverId,userId) 身份键（`JellyfinRepositoryImpl.kt:206/208`，WRITE-11 轻缺口收口）；⑩季页头部「季 · N」计数删除后当页刷新；⑨6 按钮批量条 360dp 竖屏挤压 K60 只读目检（命中再立案，refresh 粒度重构 = LATER）。
- **验收方向**：③④⑩ 各配单测/契约 + 真机点验；⑨ dumpsys/目检即结。
- 派 deepseek，1 卡（小项可卡内分 commit）。
- **✅ 已收口（2026-11-23 会话13，`5b1adfa3`/`bbde067d`/`fb3cdcf1`）**：③④⑩ 根因/修复/测试见状态块；⑨目检命中立案（设计裁定=动作行横向滚动，见裁定登记）。

- **目标**：BookDownloadManager 完成/失败通知接入 + 点击直达阅读器（复用 W7-T5 `DownloadNotificationDestination` 扩展模式），不可达回退下载页已完成分组。
- **验收方向**：通知出现+直达（无服务端书籍样本时用既有本地书重下/契约作证；S1 样本到位后 T8 补真机全链路）。
- 派 deepseek，1 卡。
- **✅ 已收口（2026-11-23 会话13，`9e756ce9`，Pad 真机取证：通知出现+直达阅读器+回退下载页全绿）**：根因/修复/证据见状态块。

### T8 ：W4–W6 backlog 补汇总 + 账户设置域裁剪 + S1 补验（P1，文档+真机）

- **目标**：①汇总 W4/W5/W6 波次文档 backlog/观察项并补裁定 NOW/LATER/DROP（§15「T0 汇总 W4–W8」的剩余部分）；②账户设置域（W9 名义任务）现状盘点与 2.0 范围裁剪；③S1 样本窗口若已开放：补验 W7 ⚠️ 书籍侧 12/21–24/34/37/44/45 与 L3/F6 再评估。
- Kimi 主导，1 卡（文档为主；需用户在测试服建样本时另行申请）。
- **✅ 裁定已落档（2026-11-23 会话14，Kimi + explore 证据核对）**：W4/W5/W6 逐条裁定与账户设置域裁剪见「裁定登记」顶部两条；预汇编建议全部采纳，两处调整（coroutines 条目措辞订正；账户切换缺陷归因修正→立案 T8b NOW P2）。S1 样本窗口维持「依赖用户动作、不阻塞发布」，书籍侧 ⚠️ 项与 L3/F6 复验随样本到位再评。

#### T8 前置准备（2026-11-23 会话13 Kimi 预汇编，执行时逐条裁定确认）

**W4 视频域遗留盘点**（源 `V2_W4_TASKS.md`）：
- L3 主题跟随 / L7 mpv content:// 字幕 / L8 媒体键绕组闸门 / coroutines catalog —— 四项 W5-T10（`dbd264f6`）已收，执行时核对关闭证据后判 ✅ 关闭。
- L5 自动回退链无坏流 fixture 真机复验 —— 建议 **LATER**（无坏流片源，代码路径已统一钉测试；2.0 内不阻塞）。
- mpv HDR 补偿参数（hdr-compute-peak/target-peak 观察观感）/ 设备档案无 HDR 位深门控 / 断网恢复对齐 ~24s —— 维持「观察不立项」→ 建议 **DROP（观察级）**。
- SyncPlay DEFERRED（组队列编辑/胶囊完整动效/深链/PiP 细分）—— 建议 **LATER**（需求 SHOULD，2.x 候选）。

**W5 音乐域遗留盘点**（源 `V2_W5_TASKS.md`）：
- MUSIC-18 渐进加载 / MUSIC-35 内嵌歌词·ReplayGain 入库 / MUSIC-49 本地缺失标记观感 —— 建议 **LATER**（W10/2.x；49 先列观察）。
- T12 观察×4（InstantMix 首曲种子/通知键不清刹车/续播无淡入/已下载不作种子）、歌词 `loadedLyricsItemId` 守卫、LyricsSheet 整行高亮色差 —— 建议 **DROP（已定稿口径/观察级）**。
- 挂 W5 链既有 LATER：L2 已下载音乐歌词离线核对、⑧评分色 token+T3b 音乐评分显示 —— 维持 **LATER** 不变。

**W6 阅读域遗留盘点**（源 `V2_W6_TASKS.md`）：
- 7 条 ⚠️ MUST backlog（08 EPUB 覆盖仿真 / 14·42 分页落盘缓存 / 16 内置字体 / 17 链接高亮单独色 / 43 远程 PDF 流式 / 44 动态字体缩放）—— 建议 **LATER**（全部已在 W6 收官判 backlog，2.0 内不排期）。
- 观察项（JS 通路简化 / renderer 单崩 / MIUI action0·action4 禁用 / 平板方向锁定 Android 16）—— 建议 **DROP（观察级/环境级）**；loadNavigation 大书首开卡顿主因已被 T13 覆盖，残余列观察。

**孤儿下载文件对账（F4b 衍生，Kimi 裁定 = NOW P2 小卡）**：K60 downloads 目录 3 个无下载行孤儿文件（既不进降级曲库也无法被本地短路命中）。方向=「下载索引对账」小卡：先 dry-run 报告模式（对账 sources 表 vs downloads 目录，输出孤儿清单+体积）再裁定清理动作（默认只报告不自动删，清理需显式开关）；排在 T8 后、T9 回归前，派 deepseek。

**账户设置域盘点要点（执行时展开）**：现状=服务器/地址管理（③已修反馈）、多账号管理与切换（zhangwenkang/w8limited token 401 切换静默被拒无反馈——与 ③ 同族，执行时裁定是否同口径修）、Jellyfin 12 鉴权矩阵已稳定；2.0 裁剪建议=「账户设置不做新功能，只收口反馈类缺陷」，S1 样本依赖用户动作不阻塞发布。

### T9 全域回归 + 发布（末卡）

- **目标**：全域回归矩阵（播放/下载/服务器写/搜索/离线核心链路，K60+Pad 分工按设备纪律）+ 全模块单测/ktfmt/Release 构建 + 版本号与发布材料（参照 1.4.1 流程）；push/tag/GitHub Release 需用户授权。
- Kimi 主导，1–2 卡；所有 NOW 卡收口后开工。

#### T9 回归矩阵定稿（2026-11-24 会话15 Kimi 定稿；执行纪律：多设备在线一律 assembleLibreDebug + adb -s 定向安装）

| # | 域 | 用例 | 设备 | 覆盖的 W9 修复 |
|---|----|------|------|----------------|
| R1 | 播放 | 在线电影起播/seek/字幕切换 | K60 | 基线 |
| R2 | 播放 | 断网冷起已下载音乐：降级曲库可见 + 点播放重解析真出声音 | K60（断网定式） | ⑥/F4/F4b |
| R3 | 下载 | 电影入队→完成→删除→同进程再入队，引擎无自旋 | K60 | T1（F2/F7） |
| R4 | 下载 | 音乐下载/取消/删除三态菜单 + 删除三清 | K60 | T2（F1b） |
| R5 | 下载 | **fixture 终验①：孤儿清理真机执行**（dry-run 已证 3 孤儿 43.5MB，本次显式开关清理，预期 70.6→27.1MB，验后即清） | K60 | 孤儿对账卡 |
| R6 | 服务器写 | 影视收藏 + 季/剧收藏 + 音乐收藏断网排队→恢复补传→服务器回读（写验证只用 Pad+测试服，温和断网） | Pad | T4（⑤⑦） |
| R7 | 搜索 | 全局搜索 + 音乐内搜索结果区渲染 | Pad / K60 | T3（②） |
| R8 | 离线 | 首页/媒体库断网缓存降级渲染 | K60（断网定式） | ⑥ |
| R9 | 书籍 | 整本下载完成通知 + 直达阅读器 | Pad | T7（F5） |
| R10 | 设置 | 删当前地址 snackbar；切账号失败行内反馈；批量条 360dp 横滚 | K60 | ③/T8b/T6b |
| R11 | 设置 | **fixture 终验②：⑩季页计数删集即时刷新**——需用户当场授权删测试服一条服务器条目（破坏性）；未授权则跳过，单测 `SeasonViewModelContentChangeTest` 已作证不阻塞发布 | Pad | ⑩ |
| R12 | 构建 | 全模块单测 + ktfmtCheck + assembleLibreRelease（签名 APK）+ 版本号 2.0.0/code 7 + 发布材料（参照 1.4.1） | — | 发布门 |

## 裁定登记（W9）

- **T9 回归发现裁定（2026-11-24 会话15，Kimi；证据 `F:\Develop\code_data\qa\w9-t9\`）**：
  - **A3 音乐下载 FILE_ERROR 静默 → 根因已真机钉死，主修复路径 LATER（2.0.1）**：11-02 遗留孤儿 `<itemId>.<sourceId>.g1` 占用目标名致收尾 rename 失败（R5 清理后重试 gen 1→2 换名 g2 一次通过）；真正解因是 T1 的 generation 递增机制，孤儿清理只回收空间。残留缺口=收尾 FILE_ERROR 不打日志 + 失败 `.download` 残片不自动清理 → LATER。
  - **A4 旧记录「已下载」徽标/菜单判定失效**（《一路生花》sources COMPLETED 且文件在盘仍显「下载」，疑 identity 命名空间不匹配；含会话15 新见的徽标缺口）→ LATER，与 T2 遗留观察①同族并案。
  - **A5 FAILED 态不同步 UI**（列表仍显「下载中/取消下载」）→ LATER（P2）。
  - **A6 下载页计数 0/2/4 跳变、0B 重复条目** → LATER（P2；会话15 R5 复验窗口未复现，留观察）。
  - **对账口径缺口（R5 衍生）**：FAILED 行（path 指向 `.download`）会把磁盘同名旧 `.g1` 判成 linked，孤儿被「认领」躲过清理；建议补「FAILED 行 + 同名 TARGET 文件」单测明确归属 → LATER（2.0.1，R5 已证两轮清理可达成孤儿=0）。
  - **「全部继续」对 FAILED 任务无效**（失败钻取页点按无反应，需行内「重试」）→ LATER（UX）。
  - **搜索导航缺陷（R7 复现，立案 NOW→2.0.x 首修候选）**：音乐搜索结果卡片点击不进详情、回音乐库根页且左侧主导航消失、返回又回搜索页（栈 search→music root→search）。T3 修复范围=渲染（已验证达标），此为独立链路缺陷，**登记为 2.0.1 P1 首修**。
  - **Pad 断网视频/动漫库页整页「加载数据错误」**（R6 窗口发现；K60 R8 首页/媒体库根页降级已验证绿）→ 库内容页离线降级缺口，LATER（2.0.1 候选）。
  - **A1 起播 ~62s（15GB 高码率 mpv 链路 buffering 抖动）/ A2 每秒 discontinuity 诊断噪声** → 观察级 DROP（不立项；A1 链路功能正常，A2 为诊断输出）。
- **W9-T8 · W4–W6 backlog 补裁定（2026-11-23 会话14 落档，Kimi；explore 只读核对证据）——§15「T0 汇总 W4–W8」至此补齐**：
  - **W4 来源**：L3 主题跟随 / L7 mpv content:// 字幕 / L8 媒体键绕组闸门 / coroutines catalog → **✅ 关闭**（`dbd264f6` 实现+单测俱在，`dbd264f6..HEAD` 对涉及 11 文件零回改；coroutines 条目措辞订正=Gradle 版本目录收口非协程盘点）。**设备证据缺口登记观察（不阻塞发布）**：L3 播放页 aurora↔amoled 切换真机取样未做（主题链路会话11 已实证，播放页低风险）、L8 实组耳机/通知栏媒体键（依赖 SyncPlay 组，随 SyncPlay 窗口补）、L7 SAF 本地视频端到端（无素材，维持 W5 已记边界）。L5 坏流自动回退真机复验 → **LATER**（无坏流片源，代码路径已钉测试）。mpv HDR 补偿观感 / 设备档案 HDR 位深门控 / 断网恢复对齐 ~24s → **DROP（观察级）**。SyncPlay 细分（组队列编辑/胶囊动效/深链/PiP）→ **LATER**（需求 SHOULD，2.x 候选）。
  - **W5 来源**：MUSIC-18 渐进加载 / MUSIC-35 内嵌歌词·ReplayGain 入库 / MUSIC-49 本地缺失标记观感 → **LATER**（W10/2.x，49 先列观察）；T12 观察×4（InstantMix 种子/通知键刹车/续播淡入/已下载不作种子）+ 歌词 `loadedLyricsItemId` 守卫 + LyricsSheet 整行高亮色差 → **DROP（已定稿口径/观察级）**；L2 歌词离线核对、⑧评分色 token+T3b 音乐评分显示 → 维持 **LATER**（挂 W5 链）。
  - **W6 来源**：7 条 ⚠️ MUST backlog（08 EPUB 覆盖仿真 / 14·42 分页落盘缓存 / 16 内置字体 / 17 链接高亮单独色 / 43 远程 PDF 流式 / 44 动态字体缩放）→ **LATER**（W6 收官已判 backlog，2.0 内不排期）；观察项（JS 通路简化 / renderer 单崩 / MIUI action0·action4 / 平板方向锁定）→ **DROP（观察级/环境级）**。
- **W9-T8 · 账户设置域 2.0 裁剪裁定（会话14）**：**不新增功能，只收口反馈类缺陷**。现状七类：服务器/地址管理（③已修）、多账号管理与切换、登录（密码/QuickConnect/TOFU）、离线模式进出、身份展示与权限门控、W1B 原子身份入口。**归因修正（explore 代码实证）**：会话11 登记「w8limited token 401 切换静默被拒无反馈」**有误**——401 经 `TOKEN_EXPIRED`→`activationFailure`→`UsersScreen` 行内红字（文案自 `50532952` 已在）；真正静默 = ①`IdentityAttemptStaleException` 被 `UsersViewModel.kt:79-81` 吞掉、②`IdentityTransitionFailedException`（RECOVERING/隔离）逃出 viewModelScope。→ 立案 **T8b（NOW P2）**：两静默分支接入既有 `activationFailure` 行内反馈（同位置同机制，不引入 snackbar）；TV 端切换零反馈登记 LATER（app/tv 冻结）。S1 样本窗口维持「依赖用户动作、不阻塞发布」。**T8b ✅ 已收口（会话14，`532e6f22`，deepseek）**：stale→`INTERRUPTED`、RECOVERING→`IDENTITY_RECOVERING`（其余 transition→UNKNOWN），中英繁文案三份，UsersScreen 仅补枚举→字符串映射两行；setup 单测 4/4、assembleDebug、ktfmtCheck 全绿；真机 401 行内红字路径列入 K60 抽查窗口点验（真实 RECOVERING 分支无法主动构造，单测作证）。
- **T6b 批量条小屏挤压修复（2026-11-23 会话13 立案，NOW P2，W9-T6 ⑨目检命中）→ ✅ 已收口（同会话，`6d40aba`，K60 双宽度取证绿）**：K60 实测（411.4dp）6 按钮动作行外沿 291.8dp，360dp 等效内宽 328dp < 需求 345–353dp，超 17–25dp。修复=动作行横向滚动（设计裁定见状态块 T6b 条）。**残项**：①~~360dp 横滚手势复核~~ → ✅ 会话14 抽查窗口补验绿（swipe 后末按钮完整入视口、回滑逐像素复原，`qa/w9-spotcheck/`）；②MIUI 拦 instrumented 测试 APK 安装（`INSTALL_FAILED_USER_RESTRICTED`）登记为环境事实。观察：探针滑到末端计数标签 a11y bounds 缩窄（疑 uiautomator 重叠裁剪伪影，视觉窗口顺手复核）。
- **T5 残项 F4b（2026-11-23 会话12 登记，NOW P1，独立小卡）→ ✅ 已收口（会话13，`407e4a39`，K60 断网实证绿）**：队列快照恢复路径不重新解析媒体源——断网冷启动点降级曲库已下载曲目仅空开播放器（tap 零 resolve 日志、无媒体会话），按播放键用 `MusicQueueStore` 持久化快照里的旧流 URL（jellyfins，非当前 jellyfin-test 会话）→ UnknownHost、BUFFERING 卡死。修复=恢复条目按当前解析链重解析（含 `getDownloadedLocalSource` 本地短路，恢复阶段零网络）+ `MusicQueueStore` 持久化口径明确为「地址提示，不可直接信任」。证据 `F:\Develop\code_data\qa\w9-t5\`（`r4-tap-live.log`/`offline-play-fail.log`/r2–r4 dump 序列）。**衍生候选（待裁定）**：下载索引对账/孤儿文件清理小卡（K60 downloads 目录 3 个无下载行孤儿文件，既进不了降级曲库也无法被本地短路命中）。
- **T5 观察项（会话12 登记）**：①降级曲库冷启动单曲不稳定——会话13 F4b 已定论：**不是排序问题**，是 K60 `sources` 表仅 1 行 + 3 个孤儿下载文件（无下载行），「轮换」的是恢复底栏/队列条目非曲库条目；孤儿对账是否立卡见上条衍生候选；②下载页 `getDownloads()` 不含 Audio 条目 → 下载页音乐单击直开本地（`5f1b8027`）真机不可验，仅 20 条单测作证；若未来下载页纳入 Audio 需补验；③`MovieScreen/EpisodeScreen` 严格快路径仍用旧 `completedLocalSource` 判据（与 F4 可播同族，LATER 候选）。

- **T1 复核发现（F2 表述修正，会话11）**：代码中**不存在**「删除链取消引擎 worker」调用点（`cancelUnique` 仅用于图片任务）；PENDING 滞留可由「claim 被拒 + 0 延迟重排 + APPEND_OR_REPLACE 链」完整解释，T1 修复已覆盖。若真机再复现 PENDING 滞留，需新一轮取证另立。
- **T2 遗留观察（会话11登记）**：①v5 之前旧最近播放行在再次播放前仍无 identity，菜单可能仍显「下载」，但点击走 sources 保底只提示不删（无害，T5 同域再评）；②拆分新增 `cancelSongDownload` 入口（原 toggle 取消能力的必要出口）；③长按 sheet 对本地媒体库曲目不再显示死入口「下载 · 离线」（去噪）；④取消下载后 sources 落 FAILED/CANCELLED（retry=0 不重试）+ `.download` 残片与 sidecar 记录暂留（既有语义，菜单正确回到「下载」、引擎静默）——残片清理策略维持现状，2.0 内不另立。
- **W9-T3 旁证（会话11）**：K60 音乐内搜索同症状复现——`GET /Items?searchTerm=Nirvana&includeItemTypes=Audio` 正常发出、服务端有数据、结果区空白（18:35 取证），与 T7 复现一致，T3 开工可直接用此路径。
- **W8-T5 回归收口（会话11）**：`adaptIsolatedFindroidMovie` 未随 FindroidMovie `canDelete`（27 参）同步致 2 条 core 测试红，`2ee5f71d` 已修，`:core:testDebugUnitTest` 481/481 绿。
- **设备事实修正（会话11）**：K60 的 App 当前实际连**测试服** admin 会话（logcat URL 实证，非"生产只读"）；zhangwenkang/w8limited 存储 token 已 401 失效（切账号被拒根因，UI 无反馈），admin token 有效。
- **主题体系核查（会话11，用户反馈「主题与设计不一样」）**：主题切换链路实证**无故障**（K60 切浅色整页即时生效，K1 定稿 token 与 `CinefinTokens` 逐值一致）；aurora/AMOLED/动态色三者深色差异天然细微是设计口径（§2 明暗与取色为正交维度），非 bug。两处真实落差登记：①五框架稿（Prism/Nocturne/Monolith）经 2026-11-18 K1 定稿判为 LATER、不做进 2.0.0，Lumen 退役遗产条目——属决策非遗忘；②K1 帧「首页 Hero 整体构图」布局（V2_DESIGN §4）原无立项——**已立项 V2-UX-17（2026-11-23 用户裁定，排 W10/2.x 不进 2.0.0 发布门）**；Prism/Nocturne/Monolith 框架稿**维持 LATER 裁定**（用户同口径确认，以后再说）。
