# 2.0 W7 · 下载域（任务卡）

> 状态：`IN_PROGRESS`（2026-11-22 **W7 会话2**：T1/T2 收口（代码 + K60 真机取证通过），下接 T3）
> 依据：`docs/V2_REQUIREMENTS.md` §8（V2-DOWNLOAD 全 55 条）与 §15 波次（W7 ← W3）；设计唯一权威 `docs/V2_DESIGN.md`；下载线历史 `docs/DOWNLOAD_PLAN.md`（1.4.x W32–W80 + 1.4.1 验收整改 R1，只读追溯）。

## 状态块（新会话先读这里）

- 分支：`codex/v2-integration`；W7 开工基线 = W6 收官 HEAD（`1562fb48`，以 git log 为准）。
- **会话2 续3（同日，Kimi 派单 + K60 取证）**：T5（`13692252`，通知直达/500ms/权限引导）+ **T5b（`5779ac29`，Kimi 红线直改）** 收口。T5 真机：音乐完成通知（id=7603「下载完成/夜曲」）点击 → MainActivity → 音乐起播（`PlaybackStart item=cc3b8c4b` 即夜曲）✓；下载页进度 8 连采持续递增（与 500ms 一致，采样间隔 1.7s 无法排他区分，以代码常量 `POLL_INTERVAL_MS=500` 为准）✓。**T5b（既有缺陷，K60 取证揪出）**：已下载音乐离线起播 = `MalformedURLException: no protocol`（sources 表 LOCAL 裸路径被 `ServerAuthDataSource` 当 HTTP URL）→ 起播失败按 T8 容错自动跳曲；W36 只验过视频/阅读、音乐离线播放从未被真实执行（T0-53 仅验导航）。修复 = `ServerAuthDataSource` 对非 http(s) 委托 `FileDataSource`（`player/local` 红线，Kimi 申报直改）；修复后夜曲离线起播 PLAYING 0:09/3:46 不跳曲 ✓。**T5 未验项转 T6**：视频/书籍完成通知点击落点（S1E1 完成通知在取证时未点验即清理）；权限引导条（禁改系统设置不可构造，4 条单测覆盖）；书籍完成通知不存在（书籍链走 BookDownloadManager 不经 DownloadNotifications，T5 卡外，T6 裁定是否立项）。**观察项②（PENDING 滞留=W76-B9 遗留④）当日第二次复现**（清理后补下一路生花再滞留，重启 recoverOnStartup 恢复并 Range 续传完成 16465673→28367204，顺带再证 W50 续传）——T6 建议立项修。
- **会话2 续2（同日，Kimi 派单 + K60 取证）**：T4（`e716aede`，13 文件 +1290 行）收口。K60 取证：排序入口（页签行）+ 四档菜单（默认/名称/下载时间/大小）+ 选择交互无回归 ✓；「已完成 0→1」初判回归实为 W63 两阶段加载时延（DB 1 行 = 页面 1 行，正确）。无样本三组记 ⚠️（单测覆盖判定逻辑，T6/样本窗口补真机）：SP「其他」组（生产只读造不出 SP 剧集）、无季专项文案（无无季 Show 样本）、书籍徽标/分区（无服务端书籍）。排序真实重排单一条目不可目测，纯函数 4 条排序单测覆盖。T4 偏差记录：排序菜单设第四档「默认」（回退分组排序）；书籍分区仅「书籍」筛选下渲染（ALL 混排不插分区头）；SP 判定只看季名别名表（不用 seasonIndex==0）。
- **会话2 续（同日，Kimi 派单）**：T1b（`c038b001`，媒体库多选接提示层，K60 快验过）+ T3（`081bd69a`，书籍链路状态机对齐）收口。T3：书籍 `PENDING/RUNNING/PAUSED/COMPLETED/FAILED` 五态 + 暂停保留残片/恢复 Range 续传/FAILED 手动重试/重启按 `isAutoResumeOnRestore` 恢复（PAUSED 保持用户意图），有声书 `BookAudioRules` 识别 + 时长投影（识别不了不造数据，章节数 backlog）；Room schema 未动（status String 列）；**真机验证受样本限制**（服务器 books 库为空，W80 记录延续）——暂停/续传/重试以 `LocalRangeServer` + 真实 `BookDownloadRangeTransfer` 契约级单测作证（断言 `Range: bytes=N-`/`If-Range`/字节拼接），真机四项推迟至 T6 或样本窗口。W80 语义（主动下载后台继续、按需落盘页面取消）不回归。
- **会话2（2026-11-22，Kimi 派单 + K60 取证）**：T1（`cc7b88a1`）+ T2（`d0b52cf5`）+ T1b（`c038b001`）收口，Kimi 文案修正 `c03e56cf`（提示层 Wi-Fi 场景主行动「下载」）。T1 真机：视频/音乐提示层文案逐项命中口径、提示先于网络（点击零 OkHttp）、整剧批量「共约 13.63 GB + 空间预检行」、入队→真实落盘→App 内删除→占用回基线全闭环。T2 真机：单曲×2 原文件落盘（ID3+fLaC 字节验证）、歌单批量「将下载 7 首→已加入 7 首」、下载页专辑分组；引擎无 URL 日志，`/Audio/{id}/stream` 端点以契约测试 8 条 + 代码链路 + 字节验证组合取证。**T1b（媒体库多选接提示层）**：三处入口（Favorites/Library/VideoScreen）统一 `GENERIC_BATCH` 面板「将下载 N 项」+ 空间截断前 N 项；K60 快验：多选 2 项 → 提示层 → 主行动「下载」（Wi-Fi 文案修正真机生效）→「已加入下载队列 · 2 项」→ 清理回基线。证据根 `F:\Develop\code_data\qa\w7-s2\`（EVIDENCE.md 汇总）。**观察项登记 T6**：①跨版本遗留 COMPLETED 记录批量未跳过 → 97% FILE_ERROR（rename 撞既有文件）；②批量删除后新入队 PENDING 滞留、重启恢复（顺带验证 W50 启动恢复）——**会话2 末已定性：即 W76-B9 已知遗留④「删除任务后再次入队偶发不立即启动」的再现**（`DownloadEngineScheduleRules` 头注释有案）；W76-Q7 加固只修了「运行中 worker 退出竞态」，本次实例 = 删除链取消 worker → WM 指数退避重排（ENQUEUED）→ 新入队 `ensureEngineRunning` 决策 WAIT/节流，等退避窗（分钟级）才起传；T6 裁定是否立项修；③书籍提示层无服务端样本（W80 记录延续）；⑤库网格条目不带 sources，多选批量大小恒「未知」（零网络口径的固有取舍）。空间不足/移动数据场景设备上不可构造，由 `DetailDownloadRulesTest` 单测覆盖判定逻辑。
- **会话1（2026-11-22，Kimi 亲自）**：T0 现状核对完成（对照 §8 全 55 条，逐条归证见下文「T0 现状核对」）；判定 **55 条 = ✅34 / ⚠️9 / ❌12**。缺口聚为五簇：①提示层（06/07/08/10/11/12/13/50/55，最大缺口，视频/音乐点击直接入队无前置检查）；②音乐端点与播放列表批量（03/33）；③书籍链路状态机（21–24 书籍侧无暂停/续传/重试）；④下载中心展示项（37/39/44/45/46/51）；⑤反馈细节（09/25/27）。任务卡 **T1–T6 定稿待派**（见文末「任务卡」）；1.4.1 两条口径类遗留（GB/GiB、ETA 公式）经核对**已收敛判 ✅**，无需修（归并结论见 T0 末节）。
- **现状基线（T0 要点）**：下载引擎 = 1.4.x W50 自研 OkHttp Range 引擎（`core/utils/DownloaderImpl.kt` 6280 行；WM 长时 worker `DownloadEngineWorker` 托管前台通知；Room `sources` 表 v12 六列）；任务状态机 6 态（`DownloadTaskRules`，PENDING/RUNNING/PAUSED/COMPLETED/FAILED/DELETING）；入口与反馈 = W51 粒度（整剧/全季/单集）+ W60b 徽标与 Snackbar 三态；下载页 = W59 钻取式 IA + W63 两阶段加载；删除链路 = 1.4.1 整改 R1 代次收敛（`DownloadDeleteBatch`/`DownloadControlPlane`）；音乐批量 = W59 专辑多选 + 专辑详情「下载专辑」；书籍 = W80 独立 `bookDownloadTasks` 链路（仅 RUNNING/FAILED/COMPLETED 三态）。**缺口集中在「前置条件检查提示层」与若干展示/端点细节，引擎主体不需重做。**
- **设计分权**：一切视觉/交互规格以 `V2_DESIGN.md` + 本文任务卡内的「设计口径（Kimi 定稿）」为准，deepseek 零设计权；组件一律复用既有 Lumen 面板语言（`LumenCardFrame` / `CinefinButton` / `CinefinSnackbarHost`，参照 `BatchDownloadDialog`）。
- **取证纪律**（沿用 W6）：① K60（`8e875894`）行为日志一律流式 `adb logcat --pid=<pid>` 落盘；② 装机前核对 APK mtime 晚于目标合并；③ 设备用后停播放 + force-stop 兜底；④ 测试下载任务验收后必须经 App 内删除流程清空（`files/downloads` 归零、占用回基线）；⑤ 生产 jellyfins 永久只读（App 下载链路自身请求除外）。
- **环境**：`export JAVA_HOME='F:\Develop\Android\Android Studio\jbr'; export GRADLE_USER_HOME='F:\Develop\gradle-home'`；构建 `./gradlew.bat :app:phone:assembleDebug --console=plain --max-workers=2`；CI 仅 master push/PR 触发。
- **本次质量门**：相关模块单测 + `ktfmtCheck` + `:app:phone:assembleDebug` 绿；核心链路（提示层→入队→续传→完成→离线打开）K60 真机取证；服务器只读。
- **W7 开工提示词存档**（会话1 原始指令，供追溯）：

```
你是主负责人/技术总监 Kimi，实现派 deepseek 子代理。
工作区：F:\Develop\code_data\work_tree\Aurorama\v2-merge-141（分支 codex/v2-integration）。
开工读序：AGENTS.md → docs/PROJECT_PLAN.md 焦点节 → docs/V2_REQUIREMENTS.md §8（V2-DOWNLOAD）
与 §15 波次表（W7 下载域 ← W3）→ docs/DOWNLOAD_PLAN.md 顶部状态块 → 记忆库 MEMORY.md
（读序外文件按需，不通读）。W6 参考口径（只读状态块）：docs/V2_W6_TASKS.md。
现状：W6 阅读域已收官（2026-11-22，HEAD 以 git log 为准），48 条 = ✅41/⚠️7/❌0，
7 条 ⚠️ 全部 backlog；移交 W7 的观察清单见 V2_W6_TASKS.md「T11 全量验收」末节
（含 loadNavigation 卡顿、TTS 停止按钮 backlog 等，均不阻塞 W7）。
本会话任务（沿用波次制，做不完的回写状态后交接）：
① W7-T0 现状核对：对照 §8 V2-DOWNLOAD 全条目逐条核对 data/core 下载链路与 UI 现状
   （✅/⚠️/❌+代码位置归证）；含 DOWNLOAD_PLAN.md 所列 1.4.1 口径类遗留的归并；
② 任务卡定稿：每卡自包含（目标/文件边界/验收要点/门禁/红线），新建 docs/V2_W7_TASKS.md
   （状态块在顶部），本提示词移入其状态块存档。
设计分权：设计以 docs/V2_DESIGN.md 为准，deepseek 零设计权。
门禁与纪律：见 AGENTS.md 质量门/红线；子代理禁 adb；K60 真机取证 Kimi 做。
交接：收口按 AGENTS.md 五件事，提示词按统一标准填槽更新。
```

## T0 现状核对（2026-11-22，Kimi 亲自，对照 §8 全 55 条）

图例：✅ 已具备 / ⚠️ 部分具备（差距见注）/ ❌ 缺失。归证路径相对仓库根。

### §8.1 能力边界与提示层（01–13）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 01 | ✅ | 三类媒体均有下载入口且点击有明确反馈：视频详情动作排三态（`app/phone/.../film/components/ItemButtonsBar.kt:128-170`）；音乐曲目菜单（`modes/music/.../MusicModeViewModel.kt:1810`）；书籍书架批量与阅读页（W80）。点击反馈 = W51/W60b Snackbar 三态「已加入下载队列 / 已在队列 / 已下载」。 |
| 02 | ✅ | 全部下载统一走官方原始文件端点：`libraryApi.getDownloadUrl(itemId)` → `GET /Items/{itemId}/Download`（`data/.../repository/JellyfinRepositoryImpl.kt:305,1776` → `models/FindroidSource.kt:143` `toOfficialDownloadSource`）；无任何转码参数（与裁决 3「下载转码 = OUT」一致）。 |
| 03 | ❌ | 音乐下载实际复用视频同款 `/Items/{id}/Download`（`MusicModeViewModel.kt:1836` → `downloader.downloadItem(...)`），未按需求使用 `/Audio/{itemId}/stream.{container}`。功能可用（原始文件可取），但端点与需求口径不符。 |
| 04 | ✅ | 书籍下载 = 原始文件直取（`data/.../repository/ReaderRepositoryImpl.kt:156` `RemoteBookSource(/Items/{id}/Download)` + `BookDownloadRangeTransfer.kt:217`）；格式白名单在阅读/导入侧（W6 T7：EPUB/TXT/PDF/CBZ 等），下载层格式无关。 |
| 05 | ✅ | 进度 = 客户端按 `Content-Length` / `Content-Range` 总字节与已收字节自算：`core/utils/DownloadHttpEngine.kt:201`（`body.contentLength()`）/`:168,212`（`parseContentRangeTotal`）；无服务端进度流依赖。 |
| 06 | ❌ | 无「前置条件检查 + 媒体类型确认」提示层：单集/电影点击直接入队（`ItemButtonsBar.kt:114-126` `requestDownload()` → `onDownloadClick`，多存储时仅弹存储位置选择）；音乐点击直接入队（`MusicModeViewModel.kt:1836`）；整剧/全季仅弹数量确认框（`BatchDownloadDialog.kt:63`，含「仅补齐缺失集 + 100 集上限」，无前置条件检查）。 |
| 07 | ⚠️ | 空间检查存在但在**引擎传输时**才拦截：`DownloaderImpl.kt:2307-2317`（`available < required` → `STORAGE_INSUFFICIENT`，消息含「需要 X，可用 Y」）。缺：点击时前置阻止、UI 清理空间入口（当前表现为下载失败后的错误态）。 |
| 08 | ❌ | 无点击时「移动数据将消耗流量」提示与「仅 Wi-Fi / 继续下载」双选项；现状 = 设置项「仅 Wi-Fi 下载」（`pref_downloads_mobile_data`，W57 `DownloadNetworkRules`）+ 引擎在不允许时置「等待网络」（`DownloaderImpl.kt:2298-2303`）。 |
| 09 | ⚠️ | `POST_NOTIFICATIONS` 在 `MainActivity.kt:177-183` 首启申请（Android 13+），被拒后**无**下载时引导跳转系统设置；存储走 app 私有目录无需运行时权限（该半条天然满足）。 |
| 10 | ❌ | 视频点击下载无提示层，「原始文件下载 / 文件大小 / 容器格式」无从展示（见 06）。 |
| 11 | ❌ | 音乐单曲点击直接入队，无「曲名 / 艺术家 / 格式 / 大小」确认；整剧/全季确认框只有「将加入 N 集 / 跳过计数 / 上限」，无「共约 X MB」（`BatchDownloadDialog.kt:134-190`）。 |
| 12 | ❌ | 书籍下载无提示（格式 / 离线打开方式）；有声书无时长提示；sidecar 说明缺失（附属文件实际有：外部字幕流/图片/trickplay，见 19）。 |
| 13 | ❌ | 提示层整体缺失（06–12 同源），「权限与网络类提示先于网络请求」自然不满足。 |

### §8.2 流程、状态机与反馈（14–31）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 14 | ✅ | `DownloadTask`（`core/utils/DownloadTask.kt:19-78`）含 itemId/mediaKind/serverId/userId/path/totalBytes/status 等，持久化 Room `sources` 表（W50 v12 六列）+ 下载意图 sidecar（`DownloadMediaSidecar`）。 |
| 15 | ⚠️ | 入队前解析条目元数据并写快照（`DownloaderImpl` `persistItemSnapshot`，W51b 竞态已修「快照先于队列行」）；**下载权限校验弱化**：W51b 起过滤门槛 = `sources.isNotEmpty()`（不再信 `canDownload` 按需字段），鉴权失败交引擎 401/403 → `AUTHENTICATION` 分类（`DownloadFailureClassifier`）。裁定：可接受（服务端仍为最终裁决），记 ✅-裁定，不修。 |
| 16 | ✅ | 幂等去重：同 itemId 命中 PENDING/RUNNING/PAUSED 直接返回不重复入队（`DownloaderImpl`，W51b「幂等命中仍直接返回」）；详情页三态「已在队列」拦截在前（`DetailDownloadRules.stateOf`）。 |
| 17 | ✅ | WM 长时 worker `DownloadEngineWorker`（`core/work/`）唯一工作链 + `setForeground`；并发上限读偏好 `pref_download_concurrency`（1–8 默认 2，`DownloadTaskRules.coerceConcurrency`）；请求带会话令牌（`credentialsForSource`，同主机注头、跨主机重定向剥离）。 |
| 18 | ✅ | 流式落盘：`DownloadHttpEngine` 64KB 缓冲写 `.download` 临时文件 + 4s fsync，不整文件入内存。 |
| 19 | ✅ | 完成后附属阶段：外部字幕流逐个下载落盘（`DownloaderImpl.kt:5126` `downloadExternalMediaStreams`）、trickplay（`:2602`）、图片缓存（`ImagesDownloaderWorker`）。注：附属阶段失败不影响主文件 COMPLETE（与 W50 口径一致）。 |
| 20 | ✅ | 全部落盘后 `.download` rename 最终文件置 COMPLETED；I/O / 网络异常分类置 FAILED + 原因（`DownloadHttpEngine` + `DownloadFailureClassifier`）。 |
| 21 | ✅ | 暂停 = 取消协程 + 保留残片 + PAUSED（W50 D38，Pad 5 真机实测残片保留续传）；队列其余任务继续。 |
| 22 | ✅ | 恢复 = `Range: bytes=N-` + `If-Range`（ETag/Last-Modified）；206 校验 `Content-Range` 起点；200 → 截断安全重下；416 → 完成判定或 CANNOT_RESUME（`DownloadHttpEngine.kt:91-212`）。 |
| 23 | ✅ | FAILED 可手动重试：`DownloadTaskRules.canRetry(FAILED)`；`resumeStrategy` 按失败原因分流 RESUME/RESTART/BLOCKED（`DownloadTask.kt:341-356`）。 |
| 24 | ✅ | 状态机 6 态（超需求 5 态）：PENDING/RUNNING/PAUSED/COMPLETED/FAILED/DELETING（`DownloadTaskStatus`）；UI 表达 = 下载页状态徽标 + 库网格徽标（进度环/双竖线/红叹号/完成角标，W60b `DownloadStatusBadge`）。 |
| 25 | ⚠️ | 条目级按钮三态 + 进度环已有（W51 `ItemButtonsBar` + W60b 角标）；**刷新节奏不达标**：下载页与徽标监控均为 1500ms 轮询（`DownloadsViewModel.kt:2320` / `DownloadStatusMonitor.kt:127`），需求要求 ≥500ms。 |
| 26 | ✅ | 下载中心聚合：顶层卡聚合 x/y、已用/总大小、速度（子任务之和）、剩余时间（`DownloadAggregateRules` + W59 钻取头部「全部暂停/继续/删除」）。 |
| 27 | ⚠️ | 进度与完成通知存在（前台单条 + 完成/失败单任务通知，`DownloadNotifications.kt`）；**完成通知点击 = 打开 App 首页**（`openAppIntent(HOME)`，`:299`），非「直达本地文件或播放/阅读界面」（失败通知 → 下载页失败分组 `:318`）。 |
| 28 | ✅ | COMPLETE 后离线可用：W36 离线媒体库闭环（Pad 5 实测断网播放/阅读，fd 指向本地文件）；下载页/库网格「已下载」徽标。 |
| 29 | ✅ | 状态即时持久化 + 启动恢复：进程死亡后 RUNNING/PENDING（engineVersion=1）回 PENDING 从残片续传（W50 18.4；`DownloaderImpl` `recoverOnStartup`），不置 ERROR。 |
| 30 | ✅ | 进度以已写盘字节为准：引擎按写块推进 `downloadedBytes` 并落 Room（4s fsync 只影响掉电安全，不影响计数口径）。 |
| 31 | ✅ | 任意时刻可回答状态：Room 单一数据源 + 下载页/徽标/通知三路投影（W63 两阶段加载保证首屏 2.9s 内出本地任务）。 |

### §8.3 媒体类型适配（32–35）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 32 | ✅ | 视频 Movie/Episode 分支：原始文件 + 暂停续传可靠（W50 真机矩阵）+ 完成保存 sidecar（19）。 |
| 33 | ⚠️ | 专辑批量入队已有（W59：专辑列表长按多选「下载整张」+ 专辑详情「下载专辑（N 首）」，`MusicAlbumDownloadRules`）；**播放列表批量入队未见实现**（`MusicModeViewModel` 播放列表分支无下载动作）。完成后保存封面（`files/images`）与歌词链路未单独核对——歌词按音乐域 W5 既有链路，下载侧未做专项。 |
| 34 | ⚠️ | 电子书直接下载交本地阅读器 ✅（W80）；**有声书无任何专门处理**：不按「音频处理 + 状态展示时长与章节数」，m4b 等若入库只按普通条目对待（全仓 grep `audiobook|m4b` 零命中）。 |
| 35 | ✅ | 显式 `DownloadMediaKind` 分支（VIDEO/MUSIC/BOOK，`DownloadTask.kt:35`；`DownloadedMediaRecordRules` 按 kind 分流完成判定）。 |

### §8.4 层级结构（36–45）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 36 | ✅ | 视频层级按季动态渲染：W34 起「节目 → 季 → 剧集」，无季直接平铺（`DownloadHierarchy.kt`，W59 钻取详情内季卡展开）。 |
| 37 | ⚠️ | 特别篇 / SP 无「其他」折叠组：季归属缺失的剧集回落为「未知季」分桶（`DownloadHierarchy.kt:395` `seasonName ?: "未知季"`），未按需求归入显式「其他」组。 |
| 38 | ✅ | 三层批量入口：Series（整剧，按季拆批对外聚合）/ Season（全季）/ Episode（集上下文）（W51 `ShowViewModel.loadDownloadTargets` / Season / Episode + `DetailDownloadRules.selectBatch`）。 |
| 39 | ⚠️ | 无季节目整剧下载能力在（等同全部集），但确认框**无**「此节目无季分组，将下载全部 N 集」专项说明（`BatchDownloadDialog` 文案与有季同款）。 |
| 40 | ✅ | 专辑容器组头聚合「已完成 x/y 首 · 体积」（W59 顶层专辑卡 + 钻取头部；Pad 5 实测「已完成 · 1/1 首 + 体积」）。 |
| 41 | ✅ | 曲目列表平铺 Track 行：专辑钻取详情 = 曲目平铺（W59②「专辑详情 = 曲目平铺」），每行自身状态徽标。 |
| 42 | ✅ | 歌曲栏点击单曲只入队该 Track（`MusicModeViewModel.kt:1810-1846` 单曲 `downloadItem`；W34 起即单曲粒度）。 |
| 43 | ✅ | 层级 = 导航上下文属性：同一 Track 在专辑页为组内条目、在歌曲栏/搜索结果为独立平铺条目（W59 数据模型不变、视图层分流）。 |
| 44 | ⚠️ | 书籍在下载页为平铺条目（W59 顶层书籍行），**无「电子书 / 有声书 / 漫画」类型分区、无按作者折叠**。书架侧（W6 阅读域）有自己的组织，下载中心未做。 |
| 45 | ⚠️ | 书籍条目未确认有格式徽标（EPUB/PDF/M4B 等文字徽标）；下载页书籍行显示书名 + 体积（W34 真机记录），`DownloadRows.kt` 书籍分支未见格式徽标字段。 |

### §8.5 下载中心与边界（46–55）

| ID | 判定 | 归证与差距 |
|---|---|---|
| 46 | ⚠️ | 分区实现形态 = **类型筛选 chips**（全部/视频/音乐/书籍，`DownloadsScreen.kt:310-330` 区域）+ 状态三页签（进行中/失败/已完成），非并排分区。筛选切换后折叠/选择状态作用域需裁定（现状：多选按当前页签 + 筛选可见集，`DownloadSelectionRules.keysFor`）。设计口径候选见 T4。 |
| 47 | ✅ | 队列视图（下载页：进行中/等待/出错 + 已完成）与离线库视图（离线媒体库，W36）分离；离线库保留层级导航。 |
| 48 | ✅ | 媒体类型徽标常驻：行内图标 + 状态徽标（W52/W59/W60b），紧凑模式未省略。 |
| 49 | ✅ | 批量聚合进度（容器 x/y + 字节聚合 + 速度之和 + 剩余）+ 每个叶子可单独暂停/重试/删除（钻取详情季卡内剧集行操作键）。 |
| 50 | ❌ | 批量入队前**无**批次总大小预估与空间预检；`BatchDownloadDialog` 只有数量与 100 集上限；空间不足由各任务在引擎传输时逐个失败（见 07），无「将下载前 N 集（约 X MB），剩余 M 集空间不足」确认。 |
| 51 | ❌ | 无用户可选排序（名称/下载时间/大小）：顶层固定「进行中 → 失败 → 已完成」分组排序（`DownloadDrilldownRules.sortForDisplay`），季内按集号（`DownloadHierarchy.kt:378`）。 |
| 52 | ✅ | 队列只存叶子任务 + 展示分组字段（`seriesId/seasonId/albumName` on leaf `DownloadTask`；父子层级纯视图层 `DownloadHierarchy`）。 |
| 53 | ✅ | 下载中心点已下载音乐 → `onPlayMusicItemIds` → `MusicItemRoute` → `MusicModeScreen(initialSongId, initialQueueItemIds)` 全屏音乐播放（`NavigationRoot.kt:1398-1407`），不进视频播放界面。 |
| 54 | ✅ | 删除不阻塞主线程：1.4.1 整改 R1 代次删除管线（`DownloadDeleteBatch` / `DownloadControlPlane` / `DownloadIntentDeletionRules`），确认后首帧进 DELETING、失败叶可重试（`DOWNLOAD_PLAN.md` §31.3 契约 + 实现候选已 GREEN）。 |
| 55 | ❌ | 视频下载入口点击直接入队（`ItemButtonsBar.kt:114-126`），无提示层（与 06 同源；1.4.1 旧 bug ① 在 2.0 需求中重申，当前实现不满足）。 |

### 1.4.1 口径类遗留归并（`V2_REQUIREMENTS.md` §13 两条 DEFERRED→W7）

| 遗留 | 核对结论 | 归证 |
|---|---|---|
| 下载页 / 通知 GB 与 GiB 口径不一致 | ✅ **已收敛，无需修**：下载页统一 1024 进制（`DownloadFormatRules.formatBytes`，`modes/film/.../downloads/DownloadFormatting.kt:20-38`）；通知统一 `Formatter.formatFileSize`（同为 1024 进制，`DownloadNotifications.kt:324,327`）；存储占用同款（`DownloaderImpl.kt:2315`、`DownloadsScreen.kt:838`）。两处进制一致、单位标签一致（均标 GB）。残余观察：标签严格语义为 GiB（1024 进制标 GB），全域统一故不构成不一致。 | 代码核对 |
| 下载页 / 通知 ETA 公式不一致 | ✅ **已收敛，无需修**：两侧同为「剩余字节 ÷ 速度，向上取整」——下载页 `DownloadSpeedRules.etaSeconds`（`core/utils/DownloadSpeedEstimator.kt:48-53`）与通知 `DownloadNotifications.kt:75-83` 公式逐项相同；显示格式同为 `h:mm:ss`/`m:ss`（`DownloadFormatting.kt:45` vs `DownloadNotifications.kt:335`）。 | 代码核对 |

**T0 汇总：55 条 = ✅34 / ⚠️9 / ❌12**——✅ 34 条（01/02/04/05/14/15/16/17/18/19/20/21/22/23/24/26/28/29/30/31/32/35/36/38/40/41/42/43/47/48/49/52/53/54，其中 15 为「引擎分类鉴权」裁定），⚠️ 9 条（07/09/25/27/33/34/37/39/44），❌ 12 条（03/06/08/10/11/12/13/45/46/50/51/55）。

## 任务卡（T1–T6，定稿待派）

通用约束（每卡继承，不在卡内复述）：构建 `./gradlew.bat :app:phone:assembleDebug --console=plain --max-workers=2` + 根 `ktfmtCheck` + 相关模块单测绿；每任务一子代理一 commit；子代理禁 adb；红线见 `AGENTS.md`（`settings.gradle.kts` / `libs.versions.toml` / `NavigationRoot.kt` / `AppPreferences.kt` / `AndroidManifest.xml` / `player:*` 动前申报；生产服务器只读）；设计以 `V2_DESIGN.md` + 卡内「设计口径」为准，子代理不得自由发挥样式；证据清单 = 构建/测试输出关键行 + diff 摘要，核心链路另需 K60 真机文本取证（Kimi 执行）。

### T1 下载前置条件检查提示层（最大缺口，需求 06/07/08/10/11/12/13/50/55）

- **目标**：所有下载入口（视频单集/电影、整剧/全季批量、音乐单曲/专辑批量、书籍）点击后先进入「前置条件检查 + 媒体类型确认」提示层，确认后才入队；任何权限/网络类提示先于网络请求。
- **设计口径（Kimi 定稿）**：
  - 组件 = 复用 `BatchDownloadDialog` 的 Lumen 面板语言（`LumenCardFrame` + `CinefinButton`，宽 ≤480dp），新组件 `DownloadPrecheckDialog`（`app/phone/.../film/components/`）；不引入新配色/图标。
  - 面板内容按媒体类型分支：①视频（单集/电影）=「原始文件下载」标注 + 文件大小 + 容器格式；②整剧/全季 = 并入现有 `BatchDownloadDialog`：数量行下增「共约 X MB」（有 size 的集求和，未知不计并注明）+ 空间预检结果；③音乐单曲 = 曲名/艺术家/格式/大小；专辑批量 =「将下载 N 首、共约 X MB」；④书籍 = 格式 +「下载后可在书架离线阅读」；有声书（能识别时）= 时长；存在 sidecar（外部字幕/封面）时一行说明「将同时保存附属文件」。
  - 前置检查（面板内即时给出，不阻塞展示）：存储空间（需要 vs 可用；不足 → 确认键禁用 + 「去清理」跳系统存储设置）+ 网络（当前为移动数据且「仅 Wi-Fi」开 → 行内提示「将消耗流量」，主行动变「继续下载」、副行动「仅 Wi-Fi 下载」（入队后等待网络））。
  - 批量空间预检（50）：批次总大小 > 可用空间时，面板给出「将下载前 N 集（约 X MB），剩余 M 集空间不足」，确认 = 只入队前 N 集，不静默部分执行。
- **文件边界**：新建 `app/phone/.../film/components/DownloadPrecheckDialog.kt`；改 `ItemButtonsBar.kt`（单集/电影接提示层）、`BatchDownloadDialog.kt`（批量聚合大小 + 空间预检）、`modes/film/.../detail/DetailDownloadRules.kt`（新增纯函数：批次大小聚合/空间截断选择/网络提示判定，单测）、`modes/film/.../detail/DetailDownloadViewModel.kt`（接线）；音乐侧 `modes/music/.../MusicModeViewModel.kt` + 音乐确认面板（可复用同组件或 `MusicAlbumDownloadRules` 扩大小聚合）；书籍侧书架批量/阅读页下载入口接线。
- **验收要点**：①三类媒体各入口点击 → 提示层出现且**未发任何下载网络请求**（logcat OkHttp 零命中）→ 确认后才入队；②空间不足场景（可用 < 需要）确认键禁用并显示需要/可用；③移动数据 + 仅 Wi-Fi 开 → 双选项出现；④批量超限空间 → 部分入队文案与数量正确；⑤既有 Snackbar 三态与幂等不回归。
- **门禁**：assembleDebug + ktfmtCheck + film/music/app 相关单测绿（新增 `DetailDownloadRules` 扩项纯函数单测 ≥4）。
- **红线**：不动引擎（`DownloaderImpl` 传输逻辑）；不动 `NavigationRoot.kt`/`AppPreferences.kt`；字符串走 core res 三语言。
- **真机取证（Kimi，K60）**：视频单集/整剧、音乐单曲/专辑、书籍各一遍「点击 → 提示层 → 确认 → 入队」；空间不足与移动数据两场景 UI 树取证。

### T2 音乐下载端点对齐 + 播放列表批量入队（需求 03、33 缺口）

- **目标**：音乐单曲/批量下载改用 `/Audio/{itemId}/stream.{container}` 可寻址端点（container 取源文件容器）；播放列表支持批量入队（与专辑同口径：仅补齐缺失 + 100 首上限）。
- **设计口径（Kimi 定稿）**：端点拼装集中为纯函数（参照 `data/api/MusicStreamUrl.kt` 的契约模式，新增 `MusicDownloadUrl`），鉴权沿用下载引擎既有注头机制（不进 URL）；container 从媒体源元数据取，缺失时回退 `/Items/{id}/Download` 并打日志（不静默失败）。
- **文件边界**：`data/.../api/`（新纯函数 + 契约测试）、`data/.../repository/JellyfinRepositoryImpl.kt`（音乐下载源解析分支）、`core/utils/DownloaderImpl.kt`（音乐 transfer URL 来源，最小改动）、`modes/music/.../MusicModeViewModel.kt` + `MusicAlbumDownloadRules.kt`（播放列表批量规则复用/泛化 + 单测）。
- **验收要点**：①音乐下载请求 URL 命中 `/Audio/{id}/stream.<container>`（logcat 取证）；②下载完成文件可播（音乐播放器起播）；③播放列表批量入队 Snackbar 与下载页专辑/列表分组正确；④既有专辑批量不回归。
- **门禁**：assembleDebug + ktfmtCheck + data/core/music 单测绿（`MusicDownloadUrl` 契约测试 ≥3）。
- **红线**：不改播放链路（`MusicStreamUrl` 不动）；401/403 仍归 AUTHENTICATION 分类。
- **真机取证（Kimi，K60）**：单曲 + 播放列表批量各一条，URL 与落盘文件双取证。

### T3 书籍下载链路对齐统一状态机（需求 21/22/23/24 书籍侧、34 有声书口径）

- **目标**：书籍下载（`bookDownloadTasks`）补齐 PAUSED + Range 续传 + 手动重试，与视频/音乐同状态机语义；有声书按音频类处理并在状态上展示时长（章节数可裁定 backlog）。
- **设计口径（Kimi 定稿）**：书籍链路保持在 `data` 层独立表（不并入视频/音乐引擎，避免大改）；暂停 = 取消在途 call + 保留 `.part` 残片与字节偏移；恢复 = Range 续传（`BookDownloadRangeTransfer` 已具备 Range 能力，扩展写偏移）；重试 = FAILED → 重新入队续传；下载页书籍行复用既有状态徽标组件。**有声书识别口径**：`mediaKind` 能判定为有声书（服务器类型/容器 m4b 等）时按音乐类展示时长，识别不了不造数据（缺样本时记 ⚠️ backlog）。
- **文件边界**：`data/.../repository/BookDownloadTask.kt`（+PAUSED 等）、`ReaderRepository(Impl)`（暂停/恢复/重试 API + Range 续传）、`BookDownloadRangeTransfer.kt`（偏移续写）、`modes/film/.../downloads/DownloadsViewModel.kt`（状态映射含 PAUSED/重试）、`app/phone/.../downloads/DownloadRows.kt`（书籍行操作键）。
- **验收要点**：①书籍下载中暂停 → 残片保留 → 恢复从偏移续传（DB/文件双取证）；②FAILED 可重试；③进程 force-stop 重开后任务可续（表已持久化，验证恢复投影）；④W80 既有语义（主动下载后台继续、按需落盘页面取消）不回归。
- **门禁**：assembleDebug + ktfmtCheck + data/film 单测绿（`BookDownloadTaskTest` 扩 + 续传偏移纯函数 ≥3）。
- **红线**：不动视频/音乐引擎；不改 Room schema 主表（书籍表自增列可申报）。
- **真机取证（Kimi，K60）**：暂停/续传/重试/force-stop 恢复四项（需书籍样本；若服务器 books 库仍为空（W80 记录），用既有本地书重下或申请样本窗口）。

### T4 下载中心展示补齐（需求 37/39/44/45/46/51）

- **目标**：①特别篇/SP 归入显式「其他」折叠组（替换「未知季」回落）；②无季节目整剧确认框加专项说明「此节目无季分组，将下载全部 N 集」；③下载中心书籍区按类型分区（电子书/有声书/漫画）+ 条目格式徽标；④排序（名称/下载时间/大小）作用于同级节点；⑤46 分区口径落地。
- **设计口径（Kimi 定稿）**：46 裁定 = **类型筛选 chips 即分区载体**（不并排渲染三个分区），但须满足「不共享折叠状态、不混排排序、不共用全选」：切换筛选时折叠/选择状态按媒体类型独立记忆（内存态即可，不持久化）；排序入口放页签行右侧溢出菜单（复用 `CinefinOverflowMenu` 若存在，否则文字菜单），三档「名称/下载时间/大小」，作用范围为当前页签 + 当前筛选的同级节点，默认 = 现有分组排序；「其他」组视觉与季卡同语言，组名文案「其他」；格式徽标 = 书籍行标题旁小文字徽标（EPUB/PDF/M4B…，取文件扩展名，未知不显示）。
- **文件边界**：`modes/film/.../downloads/DownloadHierarchy.kt`（其他组分桶 + 排序规则纯函数）、`DownloadDrilldownRules.kt`（排序/筛选状态隔离）、`DownloadSelectionRules.kt`（全选作用域）、`DownloadsViewModel.kt`（状态记忆 + 排序状态）、`app/phone/.../downloads/DownloadRows.kt`（格式徽标 + 排序入口 UI）、`BatchDownloadDialog.kt`（无季专项文案）。
- **验收要点**：①SP 剧集入「其他」组（构造 fixture：无 seasonId 剧集）；②无季 Show 确认框专项文案；③书籍行显示格式徽标、类型分区可折叠且折叠状态不跨类型串；④三档排序对同级生效且不跨层重排；⑤切换筛选后多选/折叠不串（UI 树取证）。
- **门禁**：assembleDebug + ktfmtCheck + film/app 单测绿（层级/排序/选择纯函数单测 ≥6）。
- **红线**：不动 Room schema；不动引擎；排序状态不落盘（免迁移）。
- **真机取证（Kimi，K60 + Pad 5 抽验）**：SP/无季/书籍徽标/排序四组 UI 树取证。

### T5 反馈与通知收口（需求 09/25/27）

- **目标**：①完成通知点击直达对应播放/阅读界面（音乐 → 音乐全屏播放；视频 → 本地播放；书籍 → 阅读器），不可达时回退下载页已完成分组；②下载中进度刷新 ≥500ms；③`POST_NOTIFICATIONS` 被拒时，下载相关界面给「去系统设置开启通知」引导（非阻断）。
- **设计口径（Kimi 定稿）**：通知直达复用 `MusicItemRoute` / `PlayerActivity` / 阅读器既有深链形态，`DownloadNotificationDestination` 扩 `COMPLETED(itemId, mediaKind)`；500ms 口径 = 下载页可见期间轮询间隔 1500→500ms（`DownloadsViewModel.POLL_INTERVAL_MS`），徽标监控 `DownloadStatusMonitor` 保持 1500ms 并加注释说明（角标非进度主体，裁定豁免，记录）；通知权限引导 = 下载页顶部长条提示（有进行中任务且无通知权限时显示一次/会话，带「去开启」跳 `ACTION_APP_NOTIFICATION_SETTINGS`）。
- **文件边界**：`core/utils/DownloadNotifications.kt`（目的地扩展 + 直达 intent）、`modes/film/.../downloads/DownloadsViewModel.kt`（轮询间隔 + 权限提示状态）、`app/phone/.../film/DownloadsScreen.kt`（提示条 UI）、`MainActivity.kt`（如需暴露权限状态查询，最小改动）。
- **验收要点**：①三类媒体完成通知点击分别落到音乐全屏/本地播放/阅读器（logcat + 栈取证）；②下载中下载页进度刷新间隔实测 ≤500ms；③通知权限关闭时有引导条且可跳转。
- **门禁**：assembleDebug + ktfmtCheck + core/film/app 单测绿。
- **红线**：`AndroidManifest.xml` 原则上不动（深链若需新 intent-filter 先申报）；通知动作行为不回归。
- **真机取证（Kimi，K60）**：完成通知三类点击 + 刷新节奏 + 权限引导三组。

### T6 W7 全量验收 + 收官（末卡）

- **目标**：对照 §8 全 55 条逐条终判（✅/⚠️/❌ + 证据），⚠️/❌ 裁定去向（修复卡/backlog/OUT），K60 全链路真机矩阵（提示层 → 入队 → 暂停/续传 → 完成 → 通知直达 → 离线打开），backlog/观察项按 W9 聚合机制登记。
- **验收要点**：逐条判定表 + 真机证据落盘（证据根 `F:\Develop\code_data\qa\w7-s<n>\`）；测试下载全部清空、设备还原。
- **门禁**：全仓单测 + ktfmtCheck + assembleDebug 绿。
- **前置**：T1–T5 全部收口。
