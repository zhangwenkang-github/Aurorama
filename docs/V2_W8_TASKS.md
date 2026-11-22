# 2.0 W8 · 服务器写（任务卡）

> 状态：`T1/T1r/T2/T3'/T4 已交付验收，待派 T5（阻塞：其他库无可删样本，待用户补料）/T6`（2026-11-22 会话4：T4 77143ae0；T1 ea8b9c10+T1r e98632b4、T2 a98b4566、T3' a56cb0ec；T3 个人评分写入经用户裁定转向，备份 backup/w8-t3-personal-rating 并 revert 6d09a52d；12 条判定最新见下方「T2/T3' 后判定变化」）
> 依据：`docs/V2_REQUIREMENTS.md` §6（V2-WRITE 全 12 条）与 §15 波次表（W8 ← W3，依赖已满足）；设计唯一权威 `docs/V2_DESIGN.md`。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W8 基线 = W7 收官 HEAD（`da4791d5`）；T1/T1r 后 HEAD 以 git log 为准。
- **T1/T1r 验收记录（2026-11-22，Pad=43af8627，App 登录 zhangwenkang，样本=电影《告白实行委员会：从很久以前就喜欢你了》`587e411f…`）**：
  - ✅ 在线收藏/取消收藏双向即时生效，服务端回读一致，仅影响登录账号（WRITE-07/12 实证）。
  - ✅ 飞行模式点收藏 → snackbar「已保存，联网后自动同步」+ 本地行 (fav=1,toBeSynced=1) 排队（WRITE-03 失败可见、WRITE-05 非乐观更新达标）。
  - ✅ T1r 修复①：QueuedForSync 入队带网络约束的 SyncWorker（WM 日志实证调度与约束门控）；②Confirmed 清旧 toBeSynced 残留（实证 (1,·,1)→(0,0,0)）。
  - ⏳ 补同步最后一环（网络恢复→worker 执行→服务端翻转）因 Pad 路由器 captive portal 掉线未收官；排队行 (1,0,1) 安全留存，网络恢复后 worker 应自动补传——**下会话开机先回读该行/服务端确认，并做最终还原（取消收藏）**。
  - **设备事实（重要）**：**K60=8e875894 的 App 连的是生产服**（servers 库只有 jellyfins URL）；**Pad=43af8627 的 App 连测试服**。W8 写验证一律用 Pad，K60 禁止写操作。WAL 陷阱：读设备 DB 必须连 `-wal` 一起拉，否则看到的是陈旧主文件。
  - T1r 遗留登记（非 T1r 引入）：①KEEP 竞态——排队时若同名家任务正运行且已越过该行，需下一次触发才补传（接受口径）；②**SyncWorker 只扫 movies/episodes 两表——季/剧的排队写不会被补传**（WRITE-08 既有缺口，T7 或新卡评估扩展）。
- **T2 交付（2026-11-22，`a98b4566`）**：`UserPolicyStore`（按 serverId+userId 键控、StateFlow、fail-closed、切号不串）+ 纯函数 `canDeleteContent`（admin∥全局许可∥库白名单）/`canEditMetadata`（**仅管理员**——T2 实测：Jellyfin 12 `ItemUpdateController` 整体 RequiresElevation，zhangwenkang 全库 403、admin 204；T6 据此门控）；启动 READY 时刷新一次，同进程切号由写入口 `ensureLoaded()` 兜底。实测：w8limited 仅见动漫+书籍 2 库（服务端强制，App 零代码）。Pad 冒烟：冷启动 `GET /Users/Me` ×2 实证。15 条新单测全绿。
- **T3 转向记录（2026-11-22 用户裁定）**：原「个人评分写入」系需求理解错误（原始需求文档无评分条目），正确需求 = 显示服务器评分元数据。已交付的 T3（`618c005a`，14 单测+测试服实测：7.5 精确回读、清除须走 `DELETE /UserItems/{id}/Rating`、写评分会顺带翻转服务端 Likes）已备份 `backup/w8-t3-personal-rating` 并主干 revert（`6d09a52d`），是否启用登记 W9。Pad 复验副产物：评分星条"off-by-one"实为 **uiautomator dump 与 input tap ~50px 坐标系偏差**（点击目标按 dump 坐标 +50 命中），对话框实现正确、无缺陷。新 T3' = 列表卡片评分徽标（定稿见任务卡）。
- **T2/T3' 后 12 条判定变化**：03（失败可见）❌→✅（T1 已修并真机验证）；04（权限门控）❌→⚠️（基础设施就绪，UI 门控随 T5/T6 落地）；09（403 区分）❌→✅（T1 错误模型）；05/08 ⚠️→⚠️+（T1/T1r 服务器先行+排队补同步已闭环，SyncWorker 季/剧缺口遗留）；10 ❌→⚠️（受限用户 w8limited 就绪，覆盖测试随 T5–T7）。
- **范围锚点**：V2-WRITE-01 = 写能力对齐官方客户端可比完备度；02–12 为横切纪律（端点依据官方 spec/源码 02；写失败用户可见且区分可重试 03；权限不足入口前置隐藏/禁用 04；禁乐观更新、以服务端确认为准 05/08；危险操作二次确认 06；用户级写=本人身份 07；403 不当网络错误重试 09；受限账号测试覆盖 10；多服务器隔离+环境可见 11；交付标准=成功/被拒/失败三态可见，不以「接口调通」为完成 12）。
- **测试服务器政策（2026-11-22 用户拍板）**：`jellyfin-test.zhangwenkang.com`（Jellyfin 12.4.0）为独立可写测试服——资源可删除、可改元数据；优先操作已播放完内容。凭据位置 `F:\Develop\codex_work\Documents\Codex\资源.txt`（值不得进仓库/日志/报告/记忆/截图）。生产 `jellyfins.zhangwenkang.com` 依旧永久只读。
- **测试服盘点（T0 实测，2026-11-22）**：
  - 账号：`admin`（管理员，删除权全开）；`zhangwenkang`（非管理员，6 库全开，删除权仅限「其他」库 `12c75549…`）。**受限用户 `w8limited` 误建在生产服**（2026-11-22 复核：测试服仍仅 2 账号，生产 `jellyfins` 出现 w8limited）——待用户在**测试服**网页端补建（非管理员、仅 1–2 库可见、无删除权），生产侧多余账号由用户自行处置；未就绪前 T2/T7 用 `zhangwenkang` 做非管理员侧验证。
  - 库：动漫(tvshows) / 音乐(music) / 其他(homevideos) / 合集(boxsets) / 书籍(books) / 电影(movies)。
  - 已播完可写样本充足：admin 已播 380 集+85 音频+2 电影；zhangwenkang 已播 17 集+158 音频+4 电影。服务端歌单各 1 个（歌单写测试可用，新建测试歌单用后删除）。
- **T0 判定表（§6 全 12 条，✅1/⚠️7/❌4）**：

| 条目 | 判定 | 归证（代码位置） |
|------|------|------------------|
| 01 写能力完备度 | ⚠️ 部分 | 已有：收藏（`JellyfinRepositoryImpl.kt:2062/2077`）、已看/未看（`:2091/2103`）、播放进度上报（`:1983/2024/2044`）、书籍进度回写（`ReaderRepositoryImpl.kt:345`、`SyncWorker.kt:77`）、歌单移除条目（`MusicRepository.kt:319`）。缺口：评分、歌单创建/加入、条目删除、元数据编辑（全仓零命中） |
| 02 端点依据官方 | ⚠️ 部分 | 既有全部走官方 SDK 1.8.12（OpenAPI 生成），依据记录于 `ARCHITECTURE.md` §9/`PLAYER_PLAN.md`；新增端点需逐条核实记录（T0 已本地 jar 实证能力面，见对照表） |
| 03 失败可见+可重试区分 | ❌ | 收藏/已看写失败 `catch (_: Exception)` 吞掉仅标记 `toBeSynced`（`:2069/2083/2097/2109`），用户无任何可见反馈，无可重试区分 |
| 04 权限不足入口前置隐藏 | ❌ | 全仓无 `UserPolicy` 门控；仅 setup 查 `isAdministrator`（`:2156`），写入口不做权限判断 |
| 05 禁乐观更新/服务端确认为准 | ⚠️ | 非乐观更新，但「本地先落库→服务器失败静默」（`:2066-2071`），UI 以本地为准，服务端结果不参与 UI 状态 |
| 06 危险操作二次确认 | ⚠️ | 批量动作存在（`MediaBatchViewModel`/`MusicBatchRules`）但删除仅限本地下载（`Downloader.deleteItem`）；服务器侧危险操作尚不存在，确认机制随 T5 建立 |
| 07 用户本人身份写 | ✅ | 全部写操作走用户 access token + `jellyfinApi.userId`（`MUSIC_PLAN.md` D10 实证服务端只认用户令牌） |
| 08 静默不一致补偿 | ⚠️ | 有 `toBeSynced` + `SyncWorker` 后台补同步机制，但补偿过程用户不可见、无回读确认闭环 |
| 09 403 不重试 | ❌ | 用户数据写路径 catch-all，无状态码区分（`SyncPlayMapping.kt:216`/`PlaybackSourceResolution.kt:635` 有 `InvalidStatusException` 映射，但不在用户数据写路径） |
| 10 受限账号测试覆盖 | ❌ | 测试服无受限用户（见盘点），无任何受限侧测试 |
| 11 多服务器隔离 | ⚠️ | 多服务器管理存在（`setup/servers`）；但 `UserDataEvents` 为全局单例 object（`UserDataEvents.kt:17`），写路径/缓存跨服隔离未验证 |
| 12 交付三态标准 | ⚠️ | 过程标准，本波次 T7 验收执行；现状 03/04 不达标 |

- **WRITE-01 完备度对照表（官方 jellyfin-android/web + Findroid 上游 vs Cinefin；SDK 1.8.12 本地 jar 实证操作组全在）**：

| 写操作 | Jellyfin API（SDK 操作组） | 官方客户端 | Findroid 上游 | Cinefin 现状 | W8 处置 |
|--------|---------------------------|-----------|---------------|--------------|---------|
| 收藏 toggle | `userLibraryApi.markFavoriteItem/unmark` | ✅ | ✅ | ✅ | 保持，接 T1 错误模型 |
| 已看/未看 | `playStateApi.markPlayedItem/markUnplayedItem` | ✅ | ✅ | ✅ | 保持，接 T1 错误模型 |
| 播放进度上报 | `playStateApi.reportPlayback*` | ✅ | ✅ | ✅ | 保持 |
| 阅读进度回写 | `itemsApi.updateItemUserData` | ✅ | — | ✅ | 保持 |
| 评分元数据显示（CommunityRating/OfficialRating） | 读字段（`ItemFields.COMMUNITY_RATING` 等） | ✅ | ✅ | ⚠️ 仅详情页（W75 #12） | **T3'**（2026-11-22 用户裁定：评分=服务器元数据展示；个人评分写入备份 `backup/w8-t3-personal-rating`、登记 W9） |
| 喜欢（Likes） | 同上(likes) | web 无 | ❌ | ❌ | 不纳入（L535 未列，裁定 W9 候选登记） |
| 歌单创建 | `playlistsApi.createPlaylist` | ✅ | ❌ | ✅ **T4** | 保持（移除条目随既有实现） |
| 歌单加入 | `playlistsApi.addToPlaylist`（SDK 方法名 `addItemToPlaylist`） | ✅ | ❌ | ✅ **T4** | 保持 |
| 歌单移除条目 | `playlistsApi.removeItemFromPlaylist` | ✅ | ❌ | ✅ | 保持，接 T1 错误模型 |
| 歌单删除 | `itemsApi.deleteItem` | ✅ | ❌ | ❌ | 随 **T5** |
| 条目删除 | `itemsApi.deleteItem`（需 EnableContentDeletion） | ✅ | ❌ | ❌ | **T5** |
| 元数据编辑 | `itemUpdateApi.updateItem` | ✅（web 编辑器，权限模型 T6 实测） | ❌ | ❌ | **T6** |
| 合集创建/加入 | `collectionApi.create/addToCollection` | ✅ | ❌ | ❌ | 裁定 LATER → W9 backlog 登记 |
| 刷新元数据 | `itemRefreshApi.refreshItem` | ✅ | ❌ | ❌ | 裁定 LATER → W9 backlog 登记 |
| 图片上传/删除 | `imageApi` | ✅ | ❌ | ❌ | 裁定 LATER → W9 backlog 登记 |

- **取证纪律**：写操作必须真机对测试服验证真实结果（写后回读或服务端确认 05/08）；每卡声明写边界与还原方式；K60=`8e875894`，真机取证 Kimi 执行（子代理 adb 已解禁，可辅助）。
- **门禁**：相关模块单测 + `ktfmtCheck` + `:app:phone:assembleDebug` 绿；新增端点至少一条契约测试或可复现 fixture。
- **依赖关系**：T1 → T3/T4/T5/T6（错误模型底座）；T2 → T5/T6（权限门控）；T5 → T4 的歌单删除部分；T7 最后。T3 ∥ T4 可并行（不同模块）。
- **W8 会话1 开工提示词存档**（已执行完毕，自 `PROJECT_PLAN.md` 焦点节移入）：

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_W8_TASKS.md 状态块
→ 记忆库 MEMORY.md（读序外文件按需，不通读）。
现状：W7 下载域已收官（55 条 = ✅51/⚠️4/❌0，F1b/F7 修复卡根因已定位，登记
V2_W7_TASKS.md 裁定登记）；W8 服务器写未开工，依赖 W3 已满足；测试服
jellyfin-test.zhangwenkang.com 已获用户可写授权（可删除/改元数据，优先操作
已播完内容；凭据在资源.txt，值不入库）；W9-T0 聚合顺延至 W8 后。
本会话任务（1 卡，Kimi 亲自）：W8-T0 现状核对 + 任务卡定稿——对照 §6 V2-WRITE
全 12 条逐条核对现有写能力（收藏/已看/进度上报/评分/服务端播放列表/条目
元数据编辑/删除，✅/⚠️/❌+归证）；以官方 jellyfin-android / Findroid 上游
写操作清单做 WRITE-01 完备度对照；盘点测试服账号（受限用户有无，无则申请）
与已播完可写样本；任务卡 T1+ 定稿写入 V2_W8_TASKS.md（每卡含写边界与还原
声明），本提示词移入其状态块存档。
设计分权：设计以 docs/V2_DESIGN.md 为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线；本卡为核对/文档工作，不动代码、无需
真机（子代理 adb 已解禁，本卡用不到）。
交接：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## 任务卡

### T0 现状核对 + 任务卡定稿 ✅（2026-11-22，Kimi 亲自）

结果已入上方状态块：12 条判定表 + 完备度对照表 + 测试服盘点；T1–T7 定稿如下。遗留申请：请用户在测试服网页端创建受限用户（建议 `w8limited`，非管理员、仅 1–2 库可见、无删除权），供 T2/T7 受限侧验证。

### T1 写基础设施：统一写错误模型 + 失败可见 ✅（2026-11-22，`ea8b9c10` + T1r `e98632b4`，Pad 真机验证通过）

- **交付**：`UserDataWriteOutcome`（Confirmed/QueuedForSync/Failed）+ `UserDataWriteFailure`（PermissionDenied/Network/ServerError/Unknown），沿 cause 链解析 InvalidStatusException；收藏/已看四方法 + 音乐歌单移除全部接入，消除 catch-all 静默；详情页与批量操作失败走既有 snackbar/弹窗通道。T1r：QueuedForSync 触发带网络约束的 SyncWorker 调度（`UserDataSyncScheduler` 抽象在 data、WorkManager 实现在 core）；Confirmed 清旧 toBeSynced。
- **验收记录**：见状态块「T1/T1r 验收记录」。补同步末环（worker 执行→服务端翻转）待 Pad 网络恢复后确认。
- **写边界与还原声明（执行记录）**：样本电影 `587e411f…`（已播完库内），收藏 toggle 验证；还原 = 取消收藏回 False（末环确认后执行）。

### T2 权限门控：UserPolicy 前置隐藏/禁用（派 deepseek，1 卡）

- **目标**：登录/切服时拉取并缓存当前用户 `UserPolicy`；写入口按权限前置处理——删除/元数据编辑按 `EnableContentDeletion(FromFolders)` 门控（无权限隐藏入口），收藏/已看/评分全员可用不门控；受限用户看不到越权入口（WRITE-04/10 前置）。
- **文件边界**：`data`（policy 拉取/缓存）、`app/phone`+`modes/*` 写入口渲染处；禁动 setup 登录流程本体。
- **验收要点**：①`zhangwenkang`（非管理员）登录：删除/元数据入口隐藏，收藏/已看/评分可用；②admin：全入口可见；③受限用户 `w8limited`（待用户创建）库外条目不可见（继承库权限，无需额外代码则记录实证）。
- **写边界与还原声明**：本卡不写服务器数据（仅读 policy）；验证登录态切换后无残留。
- **真机取证点**：两账号分别登录，`uiautomator dump` 取证入口有无。
- **前置依赖**：受限用户创建为完整矩阵前置；未就绪可先交 ①② 两轮。

### T3' 评分元数据显示 ✅（2026-11-22，`a56cb0ec`，Pad 真机验证通过）

- **交付**：`CommunityRatingBadge`（`BaseBadge` + `communityRatingText()` 唯一口径 + 黄铜 `ColorDark.tertiaryDark` token，仅非 null 渲染）挂入 `PosterItemCard`/`LandscapeItemCard`/`ItemCard`/`EpisodeCard` 海报左下角；`FindroidSeason` 补 `communityRating` 字段与映射；覆盖走廊/海报行/媒体库网格/收藏页/搜索/季页单集列表（复用关系实测清单在交付报告）；数据链路实测确认列表查询默认返回 CommunityRating（SDK ItemFields 无该枚举、不受门控），零额外请求、零服务器写。新增单测 5 条，三模块单测全绿。
- **验收记录（Pad）**：首页走廊四卡 ★7.8/7.0/7.6/8.7（告白实行委员会等，左下角）；电影库网格乔西的虎与鱼 ★8.3（8.266→8.3 一位小数正确）、告白 ★7.8；无评分条目（测试服 AURA/被狙击的学园，API 实证 CommunityRating=None）**正确不显示**——K60 早前所见 AURA ★6.4 系生产服元数据，非缺陷。收藏页为空态（还原后无收藏），与网格同为 ItemCard 覆盖。
- **遗留**：①横版卡 48dp 抬升按标题块估算，真机目检通过（走廊证据在案）；②黄铜直接引用 TV 遗留 `ColorDark.tertiaryDark`（phone `CinefinColors` 未暴露评分槽位）——主题感知评分色登记 W9；③音乐域评分显示（T3b）登记 W9 backlog。

### T4 服务端播放列表：创建 + 加入 ✅（2026-11-22，`77143ae0`，Pad 真机验证通过）

- **交付**：`createServerPlaylist`（`playlistsApi.createPlaylist`，CreatePlaylistDto name/ids/userId/`MediaType.AUDIO`/isPublic=false）+ `addToPlaylist`（SDK 实际方法名 `addItemToPlaylist`），统一走 T1 写边界 `musicServerWrite`（泛型化带回值），成功才失效歌单卡片缓存；`ServerPlaylistCreateOutcome`（Created(id)/Failed）新模型。UI（全复用既有组件零新样式）：歌单页「新建服务器歌单」按钮 + PlaylistNameDialog；`AddToPlaylistDialog` 同列本地/服务器歌单（服务器行「 · 服务器」后缀、含本地曲目时禁用）；批量条新增 `ADD_TO_PLAYLIST`。14 条新单测（9 契约 + 6 规则），模块 390 测试全绿。
- **真机验收记录（Pad=43af8627，zhangwenkang 测试服）**：①新建 `W8-TEST-PAD1` → forceRefresh 服务端回读出列表（WRITE-05 非乐观）；②单曲加入 → 服务端回读含目标曲；③批量加入 2 首 → 回读共 3 首；④歌单详情「共 3 首曲目」与服务端一致；⑤详情内 6 按钮批量条渲染无重叠、删除按规则禁用。**验收抓出真缺陷并修复**：服务端 `PlaylistCreationResult.id` 为 32 位无连字符十六进制（SDK 该字段是裸 String，未走 UUID 序列化器），初版直接 `UUID.fromString` 必抛 → 兜底「无法写入服务器歌单」；修复为宽容解析（契约测试成功用例改真实线格式 id）。
- **写边界与还原声明（执行记录）**：测试歌单统一 `W8-TEST-*`；验证后全部删除（`DELETE /Items/{id}` ×4 全 204、回读仅剩既有「测试播放列表」），该删除同时作 T5 deleteItem 端点契约探针（zhangwenkang 对自有歌单 204 实证）。测试曲目均为已播完音频，非破坏操作。
- **遗留**：①6 按钮批量条仅 Pad 宽屏目检，360dp 竖屏挤压风险登记（W9 或 K60 只读目检）；②本地曲目禁用态无本地库可验，以 `ServerPlaylistRulesTest` 单测口径为准；③新建/加入后 `refresh` 连曲库重拉，粒度可后拆。

### T5 条目删除（含歌单删除）（派 deepseek，1 卡）

- **目标**：`itemsApi.deleteItem` 服务器条目删除——危险操作二次确认+影响范围说明（WRITE-06）；入口按 T2 权限门控；删除后本地缓存/索引同步清理。
- **文件边界**：`data/repository`、`app/phone`+`modes/film|music` 删除入口与确认弹窗（样式按 `V2_DESIGN.md`）。
- **验收要点**：①`zhangwenkang` 仅「其他」库可见删除入口（权限实证）；②二次确认文案含影响范围（服务器文件删除、不可还原）；③删除后回读 404/列表消失；④403 按 T1 模型呈权限失败不重试。
- **写边界与还原声明**：**删除不可还原**——仅删测试服「其他」库已播完样本，总量 ≤5 条；每条删除前登记快照（名称/ID/路径）入任务证据，清单经用户确认后执行。歌单删除 = 删 `W8-TEST-*` 测试歌单。
- **真机取证点**：删除 → 回读消失 + 网页端确认。依赖 T1+T2。
- **阻塞与前置证据（2026-11-22 会话4 盘点）**：①「其他」库对唯一有删除权的 zhangwenkang **仅显示空文件夹「隐藏影库」，0 个可见可删条目**（admin 反被服务端拒访该库）——**待用户往 `/medi/下载完成/隐藏影库` 补可弃样本并扫描后方可执行**；②端点契约探针已留存：zhangwenkang 对自有 W8-TEST-* 歌单 `DELETE /Items/{id}` ×4 全 204、回读消失（T4 清理记录）。

### T6 条目元数据编辑（派 deepseek，1 卡）

- **目标**：`itemUpdateApi.updateItem` 元数据编辑——字段裁剪：名称/简介/SortName；**权限模型已实证（T2，2026-11-22）**：Jellyfin 12 `ItemUpdateController` 整体 RequiresElevation=**仅管理员可编辑**（zhangwenkang 全库 403、admin 204），非管理员入口一律隐藏（走 T2 `canEditMetadata()`）；快照→写→回读→还原闭环。
- **文件边界**：`data/repository`、`app/phone`/`modes/film` 详情页编辑入口。
- **验收要点**：①编辑→回读新值；②还原→回读原值；③无权限账号入口隐藏（T2）；④契约测试覆盖 updateItem 字段映射。
- **写边界与还原声明**：仅改测试服已播完电影/单集的名称/简介/SortName；每次编辑前快照原值入任务证据，验证后写回原值并回读确认，不留改动。
- **真机取证点**：编辑前后回读对照。依赖 T1+T2。

### T7 全量验收矩阵 + 多服务器隔离核查（Kimi 主导，1 卡）

- **目标**：WRITE-12 三态矩阵验收（admin / zhangwenkang / w8limited × 全部写操作：权限正确成功、不足被拒且 UI 合理、失败可见）；WRITE-11 核查（`UserDataEvents` 全局单例作用域、切服后缓存/事件串扰）；收官报告 + W9 backlog 移交（含 T0 裁定 LATER 项：Likes/合集/刷新元数据/图片）。
- **验收**：矩阵全绿 + 隔离核查结论入状态块；W8 状态置收官。
- **写边界与还原声明**：矩阵复用 T1–T6 边界；终态核对测试服无 W8 残留（测试歌单已删、评分已清、元数据已还原）。
