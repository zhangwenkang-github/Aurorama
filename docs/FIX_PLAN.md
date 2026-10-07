# Cinefin · v1.1.0 维护 / 缺陷修复线（FIX_PLAN）

> 本轮 = v1.0.0 发布后的第一次更新，**以修复缺陷为主**（含少量小增强）。
> 需求来源：用户 2026-10-06 消息（18 条）+ 同日追问答复（2 张截图 / 4 条澄清）。
> 本文件 = 需求登记 + 波次任务卡 + 状态总表；每条修完后的细节（决策 / 取证 / 踩坑）按线回写
> `PLAYER_PLAN` / `UI_PLAN` / `MUSIC_PLAN` / `TEST_PLAN`，本文件只保留状态与指针。
> 版本：**1.1.0（versionCode 2）**（含新功能：详情评分 / 全页搜索入口；按 `RELEASE_PLAN` §6 规则 +MINOR）。
> 测试策略（用户 2026-10-06 决策）：**一个功能只在一台设备验证**——默认 Redmi K60 `8e875894`；Pad 5 仅在平板形态相关时使用。
> 服务器 `jellyfins.zhangwenkang.com` 只读：禁止任何写 / 删调用。

## 1. 本轮原则

1. 以修 bug 为主；**不做大规模重构**，只做被缺陷直接驱动的局部收敛（媒体库数据源 / 图片占位 / 字幕选择状态）。
2. 每条 = 目标 + 非目标（≥2）+ 可验收判据（形容词先转判据）+ 落点 + 门禁；条内不夹带新需求。
3. 波次内避开咽喉文件对写；播放器类改动（§2 的 1/7/8/9/14/15/18）全部串行。
4. 每条修复流程：取证（真机复现 + 日志）→ 根因 → 修 → 单测 + K60 真机 → 门禁（根 `assembleDebug` 含 TV + `ktfmtCheck` + 全量单测 `--rerun`）→ 合并。
5. 汇报分列「已完成真机测试」/「未覆盖项」；人工感知条目（吸附手感 / 触觉）转用户代测。

## 2. 需求登记（18 条 · 用户 2026-10-06）

状态：⬜ 未开始 / 🟡 进行中 / ✅ 完成 / ⏸ 待信息

| # | 用户描述（摘要） | 解读 / 验收判据 | 归属线 | 波次 | 状态 |
|---|------------------|------------------|--------|------|------|
| 1 | 进度条提示条不明显 + 要滑动吸附；追问：提示条发布前已修；**已播放刻度保持白色**；**感觉不到吸附** | ①已播章节刻度 = 白 85%（不再用媒体色 / 极光青；仅吸附中那条 3dp 高亮）；②有章节片源上拖动 / 点击能磁吸到章节起点（±阈值内），气泡显示「第 N 章 · 名称」+ 一次轻触觉，手感需真机确认「明显可感」；③无章节片源行为不变 | PLAYER_PLAN | W75 | ✅ 已完成（W75-S1，合并 `a874c04`）：①已播刻度改前极光青 (92,225,210) → **改后白 85% (220,242,243)**，吸附中那条 3dp 高亮保留；②根因 = 吸附窗口为时间域固定值，长片折算后触控区坍缩（1008px / 24min ≈ 1.45s/px）→ 改按片长 2%/3% 派生（下限 ±2s/±3s），K60 实测触控区 ≈3px → ≈40px（15×）+ 触觉 `TextHandleMove` → `ContextClick`；③无章节片源纯线性不回归；**吸附手感转用户代测**（步骤见 `w75-reports/W75-S1.md` §四） |
| 2 | 管理员登录不要打开抽屉后才显示控制台 / 资料管理 | 管理员登录后**第一次**打开侧栏 / 抽屉即含两行；冷启动 + 切换账号两种场景都成立 | UI_PLAN | W75-S2 | ✅ 已实现（合并 `999cab9`；根因 = `isAdministrator` 只等联网 + 跨账号残留；修 = 同账号缓存先填 + `refreshAccount()`；普通账号回归通过；**管理员场景待用户代测**，步骤见 `w75-reports/W75-S2.md`。`NavigationRoot.kt` +2 行已申报） |
| 3 | 媒体库不能用黑块 | 媒体库总览大卡无封面 / 加载中 / 失败时显示媒体域占位（图标 + 媒体色底），无纯黑块 | UI_PLAN | W74 | ✅ 已修复（S74-3，Pad 5：音乐卡松石图标 / Playlists 卡琥珀图标 + 域色底；加载中先露占位不再先画深色块） |
| 4 | 季里每集看完，季没有勾 | 整季全部集看完后，剧集详情的季卡 / 季行显示已看态；播放结束后刷新及时（返回一次内更新） | UI_PLAN | W73 | ✅ 已修复（K60 合并包：全看完季卡 ✓；标记 / 取消「已播放」往返各一次、返回一次内生效并已还原） |
| 5 | 进季退出回到界面最上面 | 剧集详情滚动到中段 → 进季 → 返回，位置保持（不置顶）；连续 3 次一致 | UI_PLAN | W75-S2 | ✅ 已完成（剧集详情原本就保持；同族**必现**缺陷「列表 / 网格页返回必回顶」一并修复：`NavScrollMemory` 按回退栈条目记忆偏移，落点 ShowScreen / SeasonScreen / 视频聚合网格 / 库内容页；Pad 5 三连测 + 0 FATAL·ANR；合并 `999cab9`） |
| 6 | 选具体库时直接显示库内容，而不是卡片 | 从媒体库 / 侧栏选择某个具体库 → 直接进该库内容列表；与 #16 同链修复 | UI_PLAN | W73 | ✅ 主体已修（侧栏 / 媒体页选库直达内容）；**补充按用户 2026-10-06 拍板 B（决策 D-F7）**：视频页顶栏「库选择」选中具体库 → 直达该库内容 → **S74-3 已完成**（Pad 5：选「电影」直达「电影库 · 共 17 个项目」内容网格；「全部库」保持卡片总览） |
| 7 | Exo 播放无法跳转 / 快进（退），回到视频开头 | Exo 内核下进度条拖动 / ±快进快退 / 手势 seek 均落目标位置（误差 ≤1s），不回 0；直放与转码（HLS）两种库都验 | PLAYER_PLAN | W73 | ✅ 已修复（K60 实测，见 S73-1 卡；分支 `fix/w73-player-seek`） |
| 8 | 播放中突然退出回剧集详情页（偶发） | 连续播放 ≥30 分钟（含后台 / 切集）无非用户操作的退出；若复现，日志定性根因并修复 | PLAYER_PLAN | W73 | ✅ 根因已定性 + 兜底（断网复现链修复，长稳观察见未覆盖项） |
| 9 | 切换视频无法保持倍速（同播放列表应继承） | 同一剧集队列切下一集后倍速保持（面板读数 + `dumpsys media_session` 一致）；新开一部片按既有语义回落 | PLAYER_PLAN | W74-S2 | ✅ 已修复（Pad 5：直放 / 3 Mbps 转码 / mpv 三档切集均保持 1.5×；新开会话回落 1×；合并 `010cc79`，合并态门禁 **861/0**；见 `PLAYER_PLAN` §35） |
| 10 | 一部分季没有图片（如 ISUCA 依丝卡）；追问：**未知季没有图且不能播放**，服务器上该季有图、有 1 个视频，其他播放器正常 | ①ISUCA「未知季」卡片显示服务器图片；②季详情列出该 1 集且可播放（与 Jellyfin 网页端一致）；③通用「季→剧集图」回落链修复 | PLAYER_PLAN / data | W73 | ✅ 已修复（K60 合并包：季卡 / 详情海报 = 剧集图；集列表列出 OAD；播放键可用，点播放进入 S0:E0 正常播放） |
| 11 | 管理员账号首页没有继续播放；追问：**Jellyfin 网页端此前没有，现已开启** | 管理员首页出现「继续播放」走廊且能继续播放；若服务端开启后已正常则记录关闭 | UI_PLAN | W73（验证） | ✅ 结论关闭（服务端已开启：admin Resume=18；App 无管理员分支；K60 实测「继续观看」走廊出现 + 点击进入；注：K60 账号为普通用户，管理员账号真机文本未复核） |
| 12 | 视频详情页没有评分 | 电影 / 剧集详情信息行显示「★ x.x」（无评分时不显示）；与单集页口径一致 | UI_PLAN | W75 | ✅ 已完成（W75-S3，合并 `a874c04`）：电影「乔西的虎与鱼」★ 8.3 / 剧集「超能力女儿」★ 8.7；无评分「被狙击的学园」不显示；新增纯函数 `CommunityRating` + 单测 3；Pad 5 真机、0 FATAL·ANR |
| 13 | 搜索只在首页；视频页 / 音乐 / 所有媒体页都要有 | 视频页 / 音乐 / 书架 / 库内容页顶栏有搜索入口且可用（复用现有搜索流程）；媒体总览既有搜索回归 | UI_PLAN | W75 | ✅ 已完成（W75-S3，合并 `a874c04`）：四处顶栏新增搜索入口，复用首页同一条「媒体页 + 搜索浮层」流程（搜索逻辑 / 范围零改动）；媒体库既有搜索不回归；`NavigationRoot.kt` +19 行已申报（未改路由定义 / `popUpTo` 语义）；Pad 5 真机（含手机形态 `wm 1080x2400` 一轮） |
| 14 | 字幕选择不准（选到繁体）；切码率要重选；切视频次字幕不继承 | ①简 / 繁同优先级下自动命中简中（按设置优先级）；②手动选定字幕在切码率后保持；③次字幕跨视频继承（用户级记忆） | PLAYER_PLAN | W74 | ✅ 已修复（W74-S1，K60：简繁线索不再被 `chi/zho` 抢占 → 自动命中 `zh-Hans`；切 3 Mbps 重建播放页后仍恢复手动选的 `index=5`（繁体）；次字幕 `ja` 跨到冰海战记 E1 自动选到日语轨。详见 `PLAYER_PLAN.md` §33） |
| 15 | 字幕不消失，到下一句才变 | 字幕在句末时间点消失，不拖到下一句；SRT 生成 ASS 与原生 ASS 两条渲染路径都验 | PLAYER_PLAN | W74 | ✅ 已修复（W74-S1，K60：根因 = `ass-kt` 该时刻无图元时返回 null，被上层当「本帧无结果」保留上一帧；修后两条路径句末 **+39 ms / +9 ms** 清屏，改前同位置仍显示上一句。详见 `PLAYER_PLAN.md` §33） |
| 16 | 侧栏媒体库显示全部；媒体页只显 2 个卡片；侧栏进入能显示全部但只能打开前两个；上方库选择只能选两个 | 三处（侧栏 / 媒体页库卡 / 库选择器）库集合一致（数量 + 可打开性）；每个库可打开且内容一致 | UI_PLAN | W73 | ✅ |
| 17 | 媒体库界面部分条目因无缩略图显示黑卡 | 无缩略图条目卡显示占位（图标 + 域色），无纯黑卡；与 #3 同波 | UI_PLAN | W74 | ✅ 已修复（S74-3，Pad 5：视频页聚合 / 临时库网格 + 通用库内容页四处调用点接域色占位；加载中 / 无图 / 失败三态实测无黑卡；注：服务器自身黑色缩略图不在 App 范围） |
| 18 | 后台播放默认是开着的，现在要改为关 | `pref_player_background_audio` 默认回归 `false`；全新安装（清数据）播放器设置显示为关、退后台默认不出声；**已显式设置过的安装保留用户值** | PLAYER_PLAN | W74-S2 | ✅ 已修复（D-F2；清数据新装 prefs 无该键 → 面板「关」、退后台无声；显式开启后 `install -r` 保留 `true`；红线 `AppPreferences.kt` 已申报；合并 `010cc79`；见 `PLAYER_PLAN` §35） |
| 19 | （用户 2026-10-06 追加）所有播放、阅读等相关设置，都要有切换、重启保持 | 全量盘点播放器 + 阅读器设置项，逐项验证：a) 切换（离开 / 返回页面、换片 / 换集、切内核）后保持当前值；b) 冷启动 / 进程重启后保持；产出「设置项 → 存储键 → 切换保持 → 重启保持」盘点表，不保持的逐项修复；「切换」语义按此解读，若不符请指出 | PLAYER_PLAN / READER_PLAN | W74（S74-4 + S74-7） | ✅ 完成（审计 64 条见 `w74-reports/W74-S4.md`；待修项已修：U1 倍速 = S74-2、U2-B 循环 / 随机 = D-F8 + S74-7、U5/U6/U7 = S74-7（K60 逐项真机通过，合并 `999cab9`）；U3/U4 由 S74-1 覆盖（mpv 侧遗留见其未覆盖项）；余 6 条设计性见 S74-4 §4） |
| 20 | （用户 2026-10-06 追加）**主字幕与次字幕相互叠加**，应显示为**上下两行** | 双语字幕渲染为「主上、次下」两行、互不重叠（默认口径；如需主下 / 次上请指出）；主 / 次各自样式与延迟参数不回归；单字幕布局不受影响 | PLAYER_PLAN | W74-S5 | ✅ 已修复（K60：主 libass 上移 / 次文本贴底两行分离；单字幕与 #15 句末清屏不回归；合并 `19a1850`，合并态门禁 **858/0**；见 `PLAYER_PLAN` §34） |
| 21 | （W74-S1 新发现）转码档（如 3 Mbps）下**服务端字幕交付内容为空**（HTTP 200 但 `cues=0, ass=false`），直连档同集正常 | 转码档字幕可用，或明确回落直连字幕交付；先只读取证服务端 `DeliveryUrl` / `SubtitleProfile` 行为 | PLAYER_PLAN / data | W74-S5 + S74-S7 | ✅ 已核验关闭（服务端：DeliveryUrl External + GET 200 + 合法 ASS；K60：3 Mbps 字幕正常 `cues=442`，**未复现空字幕** → 归因一次性下载失败；加固已落地：下载失败 500ms 重试一次 + 异常日志，飞行模式实测重试 → 优雅降级 → 恢复网络 `cues=383`；合并 `999cab9`） |

### 2.1 补充说明（用户原话要点）

- 2026-10-06 截图 1：ISUCA 剧集详情 —— 「未知季」卡为深灰空块 + 角标 1；「第 1 季」正常（角标 10）。
- 2026-10-06 截图 2：未知季详情 —— 无头图、无剧集列表、播放键置灰不可点；服务器侧该季有图且有 1 个视频，其他客户端可播。

## 3. 波次与任务卡

### W73 波 1（P0 · 可用性与数据正确性）

**S73-1 播放内核：seek 失效 + 偶发退出（#7 + #8）**

- 目标：Exo 内核 seek 失效（回片头）根因修复；播放中偶发退回详情页定位并修复 / 兜底。
- 非目标：①不改 mpv 内核行为（除必要对等保护）；②不做 seek 手感 / 吸附增强（#1 属 W75）；③不重构播放页 UI。
- 落点：`data`（PlaybackInfo `startTimeTicks` 透传）、`player/local`（PlayerViewModel / PlaylistManager / PlayerHolder）、`app/phone` PlayerActivity / PlayerControlOverlay（seek 入口）。
- 验收：#7 / #8 判据 + 退出前日志证据；K60 单机。
- **取证结论（2026-10-06，K60，debug `1.0.0` / master `205db28`）**：
  - 直连（8-bit）片源：进度条拖动 / ±快进快退 / 手势 seek 全部正常（误差 <2s）。
  - **转码（Hi10P 10-bit H.264）片源 = 复现用户场景**：直连报 `NO_EXCEEDS_CAPABILITIES` → 回退服务器转码（`h264-profile=high10`）。在转码流上 seek → **BUFFERING 停滞 15–30 s**（实测 848 s 目标点卡 20 s+），位置最终落点正确但体验=「无法跳转 / 回到开头」；期间每个队列条目各自新建转码会话（`Stream url` 逐条新增）。
  - **根因（代码级）**：`JellyfinRepositoryImpl.getMediaSources` 构造 `PlaybackInfoDto` **未传 `startTimeTicks`** → 服务器转码会话恒从 0 开始；ExoPlayer 只能在已生成的 HLS 分片窗口内 seek，窗口外的 seek 会被钳/等待 → 表现为回片头或长卡缓冲。队列条目转换（`PlaylistManager`）同样每项新建转码会话。
  - 次级发现：回退窗口内发出的 seek 会丢（进度条 drag/tap 未入队，W67c 只覆盖手势）——切集后立刻 seek，回退完成后播放从 0 开始（实测）。
  - #8 待验假设：`PlayerActivity.restartPlaybackFromPosition` 走 `viewModelStore.clear()+recreate()`；回退 / 切内核触发 recreate 时若重建失败（条目 extras 丢失 / 初始化异常），用户即回落到详情页——需 logcat 复现定性。
  - 修复方向（待实现）：① PlaybackInfo 透传 `startTimeTicks`（续播与 seek-after-transcode 都用目标位置请求）；② 转码流 seek 超出缓冲窗口 → 携带目标位置重新拉流转码（原位替换媒体项，不重启 Activity）；③ 进度条 seek 在未就绪窗口排队（复用 W67c）；④ 回退重启用「当前位置」而非 0；⑤ 直连失败 → 转码的回退耗时（实测 ERROR ~15–21 s）一并压缩。

- **实施与验收（2026-10-06，分支 `fix/w73-player-seek`；详见 `PLAYER_PLAN.md` §32）**：
  - ① 已实现：`getMediaSources(startPositionTicks)` → `PlaybackInfoDto.startTimeTicks`（默认 0 兼容全部调用点）；起播 / 队列构建按续播位置传 ticks。
  - ② 已实现：转码流超窗 → 150 ms 尾部去抖 + 原位 `replaceMediaItem` 重开会话（`rebuildPlayerItemForPosition`，30 s 内最多 6 次防循环；保持播放 / 暂停意图），不重启 Activity。
  - ③ 已实现：进度条 drag/tap 纳入统一 `requestSeek`（未就绪时按比例 / 绝对目标 / 相对增量排队，就绪后补投）。
  - ④ 未走 Activity 重建路径：重开会话用目标位置（`startTimeTicks` 透传实测 `11471720000`）；回退重启仍保留原机制。
  - ⑤ 部分压缩：直连失败 → 转码链路未单独计时；本轮聚焦 seek 与 #8。
  - **#8 根因已定性**：网络瞬断（`SocketException` / mpv `ERROR_CODE_UNSPECIFIED`）在转码档被判定为链路失败 → 切 mpv + `viewModelStore.clear()+recreate()`；重建时 DNS 未恢复 → `initializePlayer` 失败（可演化为回详情页）。修复：网络类错误任何档位都不换内核 / 不重建页面，原地重试 ≤2 次（3 s 去抖），转码流第二次带位置重开会话；恢复位置优先最近进度快照。
  - 真机（K60）：转码流进度条 / 手势 / 向后拖 seek 落点 0.9–1.5 s（重开会话 1 次），±键窗口内 <1 s，直放 ±键立即；断网 55 s ×2 播放页始终未退出、无 mpv 重启日志、恢复后自动续播；0 FATAL / 0 ANR。
  - 未覆盖：重开会话到 READY 7.6–11.3 s（服务端转码启动，未达 ≤2 s「可播」口径）；30 分钟长稳观察未做满；断网恢复位置差未量化；直放进度条拖动未单独取证。

**S73-2 媒体库入口一致性 + 直达内容（#16 + #6）**

- 目标：侧栏 / 媒体页 / 库选择器三处一致；每个库可打开；选择具体库直达内容。
- 非目标：①不改库内条目布局样式（#3 / #17 属 W74）；②不新增设置项；③不动音乐 / 书籍域的库选择逻辑（除非同源缺陷）。
- 落点：`MediaScreen` / `MediaViewModel` / `NavigationRoot` / `VideoViewModel`（库过滤器）。
- 验收：#16 / #6 判据（三入口逐一取证）；K60 单机。

**S73-3 观看状态与未知季（#4 + #10 + #11 验证）**

- 目标：整季看完有勾；ISUCA 未知季有图且可播；管理员继续播放通道验证。
- 非目标：①不做通用图片占位样式（#3 / #17 属 W74）；②不改季卡布局；③服务端只读。
- 落点：`data`（季 / 集查询与映射）、ShowScreen / SeasonScreen、HomeViewModel。
- 验收：#4 / #10 判据 + #11 只读核对；K60 单机。
- **取证与实现（2026-10-06，可见会话 W73-S3；分支 `fix/w73-watch-state`，提交 `f4359c0`（前任 WIP 复用）+ `49e5ced`）**：
  - 服务器只读探针（明细见 `.planning/cinefin-expansion/w73-evidence/w73_watch_state/api-probe.md`）：
    ISUCA「未知季」= `LocationType=Virtual`、`IndexNumber=null`、**自身无图**（`/Items/{seasonId}/Images/Primary` → 404、`/Images` → `[]`）
    但有 `SeriesPrimaryImageTag`（剧集海报可回落）；唯一一集 OAD **`SeasonId=null`**（`/Shows/{id}/Episodes?seasonId=` 能取到，集自身有截图）。
    全看完季 = `Played=true / UnplayedItemCount=0`（只有神知道的世界「四人与偶像」实测）；admin 的 Resume=**18 条**、测试号 484 条。
  - 根因：#10① 季卡 / 详情海报 / 头图只取季自身图（无 → 深灰块），未回落剧集图；#10② `seasonId!!` 把无季号的集整条丢掉 → 集列表空、播放键置灰；
    #4 容器徽标只在 `unplayed>0` 画数字、0（全看完）什么都不画（W56 口径）；返回不刷新 = 播放器是独立 Activity，`LaunchedEffect(Unit)` 不会重跑。
  - 修：新增纯函数 `seasonPosterImage`（季海报 → 剧集海报）/ `seasonBackdropImage`（季 backdrop → 剧集 backdrop → 海报链），
    接线 `ItemPoster(VERTICAL)` / `DetailPoster` / `ItemHeader`×2 / `HeroBackdropLayer`×2；`resolveEpisodeSeasonId` 按季取集回落；
    容器徽标改「`unplayed>0` → 数字；否则 `played` 或 `unplayed==0` → 打勾」；`ShowScreen` / `SeasonScreen` 改 `repeatOnLifecycle(RESUMED)` 重取
    （配合 `postPlaybackStop` 内的 `invalidateMetadataCache()`，返回一次内刷新）。
  - 门禁：`ktfmtCheck` + `:app:phone:assembleDebug` 全绿；8 任务 `--rerun` = **819 项 / 0 失败 0 错误**（基线 813 + WIP 3 + 本波 3）。
  - 真机（K60）：前任 WIP 包 12:45–12:56 已验 #4 全看完季卡打勾、#10① 未知季卡显示剧集海报；**本波最终包待窗口**重验
    #10②（详情海报 / 集列表 / 播放）+ #4（播放结束返回一次内刷新）+ #11（首页「继续观看」文本）。
  - #11 结论（服务端维度）：服务端功能此前未开启（用户已开启，admin Resume=18 条）；App 无管理员维度分支，
    `home_continue_watching` 默认开、任意账号按当前登录用户请求——待真机文本复核后关闭。

### W74 波 2（P1 · 播放体验与展示）

**S74-1 字幕链路（#15 + #14）**：字幕句末消失 + 简繁选择 / 码率保持 / 次字幕继承；落点 `PlayerSubtitleController` / `PlayerSubtitleOverlay` / `TrackSelectionEngine` / `LanguageMatcher`；非目标：不动图形字幕（PGS）原生路径、不新增字幕设置项。

**S74-2 播放继承与后台（#9 + #18）**：倍速跨集继承 + 后台播放默认关；落点 `PlayerHolder` / `PlayerViewModel` / `AppPreferences`（红线，改动申报）；非目标：不改音乐后台语义、不改锁屏 WakeLock 行为。

**S74-3 黑块清零（#3 + #17）+ 库选择直达内容（#6 补充 / D-F7）**：媒体库大卡 + 库内容条目卡占位统一（图标 + 域色）＋视频页顶栏「库选择」选中具体库直达内容（落点 `VideoScreen` 选择回调）；落点 `LibraryEntryCard` / 条目卡组件 / 相关占位纯函数；非目标：不改真实封面加载链路、不改布局、不动媒体页 / 侧栏既有直达链路。

**S74-4 设置持久化盘点（#19，用户 2026-10-06 追加）**

- 目标：播放 / 阅读全部设置项（倍速、字幕模式 / 延迟 / 样式 / 次字幕、音轨延迟、内核 / 解码 / 回退档、码率、后台播放、章节刻度、时钟制式、跳段、循环、画面比例 / 缩放 / 裁剪 / 镜像、长按倍速档、阅读器模式 / 字号 / 行距 / 主题 / 护眼 / RTL / 双栏等）都要「切换（离开返回、换片换集、切内核）与重启（冷启动）后保持」。
- 方法：读 `AppPreferences`（SharedPreferences）逐项核对存储键 + 落盘时机；真机行为验证两种场景（K60 或 Pad 5 单机）；产出盘点表；不保持的修复并补回归单测。
- 非目标：①不做设置页 UI 重排；②不改任何默认值（后台播放默认关 = D-F2 已在 #18 处理）；③不引入新的配置存储框架。

### W74 尾波（第五任负责人 · #20/#21 + S74-2/S74-7）

**W74-S5 双语字幕叠行（#20）**：主上、次下两行不重叠；单字幕不回归；#15 句末清屏不回归；落点 `PlayerSubtitleOverlay`；K60 单机；分支 `fix/w74-subtitle-two-lines`——✅ 已合并 `19a1850`（合并态门禁 858/0，CI 已推送）。

**W74-S2 播放继承与后台（#9 + #18）**：倍速会话内跨集继承 + `pref_player_background_audio` 默认 true→false（红线申报）；落点 `PlayerHolder` / `PlayerViewModel` / `AppPreferences.kt`；Pad 5 单机；分支 `fix/w74-playback-inherit`——✅ 已合并 `010cc79`（合并态门禁 861/0，CI 已推送）；派发时误用 v4-pro 已叫停并改由 flash 接手（详见 §5 日志）。

**W74-S7 设置即时生效与加固（U2-B + U5 + U6 + U7 + #21 加固）**：①U2 = 方案 B：新增 `pref_player_repeat_mode` / `pref_player_shuffle`，进播放页应用（切内核 / 重开 / 冷启动保持）；②U5 长按倍速档改「用时现读」；③U6 seek 步进 ±按钮 / 内核命令改现读偏好；④U7 设置页「首选语言」下拉值改由 plural 优先列表首项派生；⑤#21 加固：字幕下载失败一次重试 + 失败日志带异常（落点 `PlayerSubtitleController`）。落点 `PlayerControlOverlay` / `PlayerHolder` / `PlayerViewModel` / `PlayerGestureHelper` / 设置页 / `PlayerSubtitleController`；**须在 S74-2 合并后再开**（PlayerHolder / PlayerViewModel 咽喉文件串行）；K60 或 Pad 5 单机。

**W74-S6 转码档字幕（#21）**：先取证定性（服务端已证交付正常：DeliveryUrl External + GET 200 + 合法 ASS；待 K60 侧失败异常 / HTTP 码），再定修法（倾向 App 侧下载超时 / 无重试）；落点 `PlayerSubtitleController`；证据齐后开。

### W75 波 3（P1/P2 · 交互与增强）

**S75-1 进度条（#1）**：已播刻度回白 + 吸附手感增强（阈值 / 触觉 / 气泡联动），必要时手势 seek 吸附下沉；落点 `PlayerControlOverlay` / `PlayerChapterSnap`；非目标：不做无章节吸附网格、不改刻度尺寸规格（W67b 已定稿）。

**S75-2 导航与入口（#5 + #2）**：返回保持滚动位置 + 管理员入口首开即显；落点 ShowScreen / SeasonScreen / DrawerViewModel / NavigationRoot。

**S75-3 搜索与评分（#13 + #12）**：视频页 / 音乐 / 书架 / 库内容页搜索入口 + 详情页评分；落点各页顶栏 + 详情页信息行。

### W76 收口（第七任负责人 + 回归 · 2026-10-06）

- **W76-F1 日志脱敏（B8）**：`PlayerViewModel.toMediaItem()` 的 `Timber.d("Stream url: $streamUrl")` 把转码 `api_key=…` 明文打进 logcat（debug）。修 = 脱敏纯函数（保留 host / path / 非敏感 query，`api_key` / `X-Emby-Token` 值 → `***`）+ 单测；K60 logcat 取证（改后无明文 key 且播放正常）；分支 `fix/w76-log-redact`。非目标：不改 release 日志树 / 不引入日志框架 / 不扩大改动面。
- **版本与构建（负责人）**：`buildSrc/Versions.kt` → **1.1.0 / code 2** → 合并态门禁（8 任务 `--rerun-tasks`）→ `assembleLibreRelease -Paurorama.universalApk=true` → `apksigner verify`（指纹 `e449c4aa…e155ff`）→ `RELEASE_PLAN` §7 记体积 / 哈希。
- **全量回归（对象 = 1.1.0 release 签名包）**：
  - **R1（K60，B5 发布面补验）**：①release 真实下载任务（含 1.0.0 → 1.1.0 覆盖安装升级路径 / 用户数据保留）；②SAF 本地库授权重建（删库 → 重选目录 → 扫描 → 打开 / 播放 → 还原）；③mpv 内核兜底触发（回退链第 3 档真机端到端）；④Quick Connect 登录路径。
  - **R2（Pad 5，核心回归）**：按 `TEST_PLAN` §7.6 框架轻量复跑——全链路冒烟（首页 / 视频 / 音乐 / 书架 / 媒体库 / 下载 / 搜索 / 收藏 / 设置）+ 播放（直连 + HLS 转码 + 字幕）+ 阅读（EPUB / PDF / CBZ）+ 音乐 + 离线 + 下载 + 性能（冷启动 ×3 / PSS / 滚动 jank）+ 稳定性（0 FATAL·ANR）。
- **Release notes**：新建 `docs/RELEASE_NOTES_v1.1.0.md`（参照 v1.0.0 模板：亮点 / APK 选择建议 / 签名指纹 / 已知限制）。
- **用户全检 → 发布**：1.1.0 release 装机交用户全检（含转用户代测项 #1 吸附手感 / #2 管理员入口）；通过后按 `RELEASE_PLAN` §5 打 tag `v1.1.0` + GitHub Release + 上传 APK。**全检通过前不打 tag / 不发 Release**。
- 文档收口：各线文档回写 + `PROJECT_PLAN` §3 状态 + 记忆 `cinefin-v11-update`。

### W76-B9/B10/B11 修复波（方案B · 2026-10-07 用户拍板）

**W76-B9 首次下载必失败（P1 · 阻断项）**：根因 = `core` `DownloaderImpl.kt:425` 的 `StatFs(target.parentFile?.path ?: context.filesDir.path)` 排在 `DownloadHttpEngine.kt:92` 的 `target.parentFile?.mkdirs()` 之前 → `…/files/downloads` 不存在时抛 `IllegalArgumentException` → 被通用 catch 收成 `DownloadFailureReason.UNKNOWN`（UI「失败 / 0 B」）。修 = 存储预检查前先建父目录 + `StatFs` 失败回落 `filesDir` + 「首次下载·目标目录不存在」单测；失败原因不吞成 UNKNOWN 仅做最小留痕（超 20 行不做）。非目标：下载 UI / DB 结构 / 限速逻辑。落点 `core`；分支 `fix/w76-b9-download`，worktree `w76b`；设备 K60 `8e875894`（debug 清数据首下验证；release 口径交发布包复验波）。验收 = 单测 + debug 真机「清数据后首次下载」有真实进度 + 0 FATAL。

**W76-B10 音乐会话粘住 → 视频换内核 / 回退失效（P2）**：根因 = `PlaybackCoordinatorImpl.onVideoStartRequested()` 全仓库无调用点；`PlayerHolder.kt:93`（player getter 粘住态恒回 audioSession）与 `:187`（release 早退）锁死 → R1c 用户可见面 = 横屏「播放失败」卡片 +「改用 mpv 内核」被吞。修 = 视频实际起播链路接线上 `onVideoStartRequested()`（或等价最小去粘住）；**W68 语义不回归**（音乐后台播放时视频页退出不得释放音乐实例）。非目标：不重构协调器 / 不动上报协议 / 不改媒体会话结构。落点 `player/local`（PlayerHolder / PlaybackCoordinatorImpl / PlayerViewModel —— 本波独占咽喉文件）；分支 `fix/w76-b10-music-video`，worktree `w76c`；设备 Pad 5 `43af8627`。验收 = 同进程「音乐→视频→切 mpv」立即生效（面板 / logcat / media_session 一致）+ W68 不回归 + 单测 + 0 FATAL·ANR。

**W76-B11 系列详情页「播放」空载（P3）**：根因 = `ShowScreen.kt:182` 传 series id → `PlaylistManager.getInitialItem` SERIES 分支（NextUp / Seasons / loadSeriesEpisodes 任一取空即）`return null` **静默**；真机 = `00:00/00:00` + 队列为空 + 无提示，季 / 集级正常。修 = 定位取空真因并修到「系列级播放能起播（NextUp 或回退第一集）」+ 解析失败改用户可见提示（禁止静默兜底）。非目标：本波不修 `EpisodeAction.NavigateToSeason` 无发射点（记入 §6.1 Q1）。落点 `player/local` PlaylistManager（+ `app/phone` ShowScreen 如需）；分支 `fix/w76-b11-series-play`，worktree `w76d`；设备暂不分配（K60 空闲时可用）。验收 = 单测 + 真机系列级播放出画或明确错误提示 + 季 / 集级不回归。

**W76-B11 执行修正（2026-10-07）**：真机实证主因 = 系列级入口在起播前**同步串行枚举整剧**（`getNextUp` → 逐季 `getEpisodes`，5 次串行 HTTP，实测 7.8 / 18.5 / 31.7 s）造成「空载」空白窗口（UI 逐字 = `00:00/00:00` + 队列为空 + 无提示）；静默兜底为同类隐患但三次运行均未命中。已交付 `b8935f3`：三处静默兜底取消（降级链 + 中文 `PlaybackStartException` Toast），系列级语义不变；**空白窗口消除 + 退出时 `JobCancellationException` Toast → W76-B11b**（落点 `PlayerViewModel`，须在 B10 合并后串行开）。**在 B11b 完成前，不对外声明「用户可见空载已消失」。**

**W76-B11b 已完成并合并 master（2026-10-07，`080fd38` rebase 后 `3b85d95`）**：系列级起播改「**先播后补队列**」（快路径 = NextUp + 起播集所在季；整剧枚举移至 `Playing MediaItem` 之后）+ 取消语义修复（`initializePlayer` 先放行 `CancellationException`；`runCatching` 不再连取消一起吞）；K60 真机首帧 **31.7 s → 2.0 / 5.0 s**，加载中退出不再误报；**终合并态门禁 920/0/0**（见 §5；`player:local` 169/0/0，含本波 +7 单测）。**「用户可见空载已消失」的表述以发布包复验实测为准**（复验清单见下）。

### W76-Q1–Q7 修复波（全并入 1.1.0 · 2026-10-07 用户拍板）

> 4 个任务包（子代理一律 `deepseek-flash`；**设备独占**——同一设备同一时刻只跑一个会话，防 W76-B9/B11 覆盖事故重演）。批次：① Q1（Pad 5）与 ② Q2+Q7（K60）并行 → ③ Q5（Pad 5）→ ④ Q6（K60，待 Q5 合并后；同咽喉文件串行）。全部合并后跑终门禁 → 构建候选 release（自动签名）→ V1/V2 回归；**回归通过后**才做正式重建签名 + 哈希落档。

**Q1 剧集页「前往所属季」入口**：`EpisodeAction.NavigateToSeason` 自 W66 `b5446e6`（有意删除顶栏名称入口）后无发射点，handler 仍在 `EpisodeScreen.kt:155`。修 = 剧集详情页补轻量入口（建议信息行「Sx · Ey」可点或动作区「所属季」；不动顶栏）。落点 `app/phone` EpisodeScreen（+ `modes/film` 如需）。验收 = 真机「从剧集页点新入口 → 到达所属季页」且季 / 集 / 系列级各入口不回归 + 0 FATAL。非目标：不改系列 / 季页布局；不恢复 W66 顶栏入口样式。

**Q2 下载失败原因可诊断性**：DB/UI 基建已存在（`sources.failureReason` 落库 + `DownloadRows.failureLabel` 分原因文案）；把 `executeTask` / 下载引擎通用 catch 中可判定异常映射到具体 `DownloadFailureReason`（NETWORK_UNAVAILABLE / STORAGE_INSUFFICIENT / SERVER_ERROR / FILE_ERROR / AUTHENTICATION…），真未知才落 `UNKNOWN`。落点 `core`。验收 = 映射单测 + K60 真机（如断网）看到对应原因文案 + 0 FATAL。非目标：不改下载 UI 结构 / 重试策略 / API。

**Q7 删除-重入队偶发不启动（加固）**：未复现（W76-B9 遗留④，疑似 WorkManager）→ 排查入队路径（唯一工作名 / REPLACE 策略 / 陈旧 worker 状态）做防御性加固 + 单测。验收 = 单测 + V1 期间 5 次「删任务 → 重入队」全部立即启动；零复现则按「加固 + 留观」如实收口。与 Q2 同区串行（同会话）。非目标：不重构调度框架 / 不升 WorkManager 版本。

**Q5 视频页退出误发音乐 `Stopped`**：`PlayerViewModel.releasePlayer()` 在「视频 attach 音乐会话后退出」用现有条目补发 `Sessions/Playing/Stopped`（W68 D59 未覆盖；B10 遗留③）。修 = 仅「本页自己启动的会话」才补发，音乐条目归音乐侧管理。落点 `player/local`（咽喉文件）。验收 = 单测 + Pad 5 真机 W68 隔离不回归（音乐后台仍 `PLAYING(3)`）+ 0 FATAL·ANR。非目标：不改协调器结构 / 不动上报协议 / 不碰 B10 判定。

**W76-Q5 已完成（2026-10-07，分支 `fix/w76-q5-stopped-report` @ `08c0814`，未 push）**：新增纯判定 `shouldReportStopOnPlayerExit(currentItemIsMusic) = !currentItemIsMusic`（口径同 `musicSessionActionOnVideoStart`：认 `MUSIC_MEDIA_EXTRA` 标记，不信任 `musicSessionActive` 标志），`releasePlayer()` 的 Stopped 上报加该 gate；其余上报点与协调器 / 上报协议 / B10 链路零改动。门禁 `:player:local:ktfmtCheck` ✅ / `:player:local:testDebugUnitTest` **171/0/0**（基线 169，+2）/ `:app:phone:assembleDebug` ✅。Pad 5 真机双向取证（07:09–07:13）：①attach 音乐会话后退出（两轮）→ 只有 `Clearing Player ViewModel`、**无** `Sending playback stop`，音乐仍 `PLAYING(3)` 且服务在（W68 隔离不回归）；②对照本页自启视频（冰海战记 S1·E17，HEVC）退出 → `PlayerViewModel$releasePlayer: Sending playback stop` 照常命中 + `视频起播：已收掉音乐会话（STOP_MUSIC_AND_CLEAR_FLAG）` 不回归；③0 FATAL / 0 ANR。**申报**：gate 落地为「当前媒体项不是音乐条目」——视频页 attach 后台**视频**会话退出仍按既有行为上报（未改）；「本页起播视频 → 音乐抢占实例 → 退出」同因同治不再误报。**还原**：force-stop、临时文件删除、播放回退档位偏好 hand-edit 还原 + 冷启复核、release / K60 零接触；副作用 = 冰海战记 S1·E17 进度改写（不可回滚）。报告 `w76-reports/W76-Q5.md`。

**Q6 后台补队列请求瘦身**：现状 = 起播后 `fillQueueInBackground()` 对整剧每集请求 `PlaybackInfo`（含转码会话）。保底 = 请求去重 / 缓存；再评估「懒解析（条目临近播放才解析）+ 连播窗口预取」能否在不回归「队列正确性 / 连播顺序 / 可播性」下成立——成立实装，不成立留档 1.2。落点 `player/local`（Q5 合并后串行）。验收 = 单测 + K60 真机：系列级首帧不回归（2–5 s 量级）、队列完整、NEXT 顺序正确、请求数下降（logcat 计数对比）+ 0 FATAL。非目标：不改队列 UI / 转码策略 / B11b 快路径语义。

**W76-Q2 已完成（2026-10-07，分支 `fix/w76-q2-q7-download` @ `8fd8a5f`，未 push）**：新增纯函数 `DownloadFailureClassifier`（网络族 → `NETWORK_UNAVAILABLE`、磁盘满 `ENOSPC` → `STORAGE_INSUFFICIENT`、`FileNotFoundException` / `SecurityException` / 裸 `IOException` → `FILE_ERROR`、取消 → `CANCELLED`，真未知才 `UNKNOWN`），接入 `executeTask` 通用 catch、`resolveRemoteSource`、`DownloadHttpEngine` 的 `IOException` catch（传输层非文件类 IO 仍保持网络口径）。门禁 `:core:ktfmtCheck` ✅ / `:core:testDebugUnitTest` **104/0/0**（基线 93，+11）/ `:app:phone:assembleDebug` ✅；K60 真机：目标父目录不可写 → **DB `FILE_ERROR` + 下载页「文件写入失败」**（修复前该链路被 IO 兜底误判 `NETWORK_UNAVAILABLE` → 「网络错误」）。**申报**：原因→策略对照表零改动、`resumeStrategy` 生产零调用点，但文件 / 空间类不再无限自动重试（原误判为网络），属预期。**负向发现**：飞行模式中途断网由 WorkManager 停 worker（CONNECTED 约束）→ 引擎按「worker 中断」处理（保留残片 / 回 PENDING / 不落原因）→ 行显示「正在等待…」，恢复网络自动续传（21.1→34.7 MB）；即纯网络中断不产生失败原因文案（既有行为）。报告 `w76-reports/W76-Q2-Q7.md`。

**W76-Q7 未复现 → 加固 + 留观（2026-10-07，同支 `744a631`，未 push）**：假设 = 新任务落库恰在「worker 判定队列空 → 退出」窗口、入队方读到唯一工作链仍 `RUNNING` 而跳过唤醒 → 该 `PENDING` 无人调度（与 B9 遗留④「停在队列、无残片无日志」吻合）。加固 = 入队方 ↔ worker **锁内原子退出交接**（worker 未退出则置 kick 让它再跑一轮；已声明退出则由入队方补一个即时 worker）+ 调度决策抽为纯函数 `DownloadEngineScheduleRules`（既有 `ExistingWorkPolicy` / `forceStart=REPLACE` 语义逐字保留）；`:core` 单测 +3。K60 真机 10 轮「删任务 → 重入队」**0 次复现**（每轮行状态均「正在下载…」；12 次 worker 启动 = 1 次/入队）；**正式 5 次验收排在 V1 发布包复验波**。仍存疑：竞态未实证、`forceStart=REPLACE` 取消旧链节点会在 logcat 留 `Work … was cancelled`（既有路径）。

**W76-Q1 已完成并合并 master（2026-10-07，`f6e9ee0` rebase 后 `8f40a90`）**：`DetailHero` 加**可选** `onEyebrowClick`（默认 null → 电影 / 节目 / 季页零变化；眉标抽 `HeroEyebrow`，可点时追加 `›` 提示可深入）；`EpisodeScreen` 眉标接 `onAction(EpisodeAction.NavigateToSeason(episode.seasonId))`（`navigateToSeason` handler 一字未动、**顶栏未动**）；`app:phone` 单测 **273/0/0**（+1）。Pad 5 真机：竖屏 compact + 横屏 expanded **两条头图渲染路径**均「眉标 → 所属季页」；系列 → 季 → 集 → 剧集 + BACK 不回归；0 FATAL·ANR；**副作用无**（未点播放 / 下载，服务器只读）。**待全检反馈**：入口形态暂为「眉标可点 + `›`」（零新增文案，不落 35 locale）；如需显式「所属季」动作键，改动集中 `HeroEyebrow` 一处。报告 `w76-reports/W76-Q1.md`。

**W76-Q6 已完成并合并 master（2026-10-07，`6a438a2` 保底 + `61d13a8` 加固）**：新增 `PlaybackRequestCache`（按 `(itemId, mediaSourceIndex, startTimeTicks)`「一键一锁」合并同键请求 + 复用已解析结果；转码 seek 重建 `forceRefresh` 不复用、解析失败不落缓存），`PlaylistManager.toPlayerItem` 接入缓存层 + `PlaybackInfo 统计` 日志；**顺带修掉**重复起播 / 重复入队场景「补片取消把剩余条目判失败」导致的队列截断（`runCatching` → `runCatchingCancellable`）。`:player:local` **178/0/0**（基线 171，+7）。K60 6 轮（灼眼的夏娜 73 集）：系列级首帧 **3.40 / 4.65 / 7.15 s**（改前同口径 4.59 / 4.67 / 15.01 s）、队列 **73 项完整**、NEXT 顺序正确、0 FATAL·ANR；**请求数对比** = 单次起播 73→73（每集本就需要一次，正常路径无可去重）、**重复起播同参请求 2→1**（且改前该场景队列被截断为 2 条、改后 73 条完整）。**懒解析 / 连播窗口预取 = 不实装（留档 1.2）**：证据 = 队列面板逐条读 `getMediaItemAt`、点选 = `seekTo(index, 0L)`、mpv 插入即 `loadfile` → 时间线每条都需真实地址，降总数须改队列数据源 + 点选按需解析（结构性改动、触非目标）；故**普通路径总请求数仍 O(剧集数)**——已知边界，报告 `w76-reports/W76-Q6.md` §五留 1.2 最小路径。

### W76 修复后复验（回归已放行 · 排在 Q 波之后 · 2026-10-07）

> **2026-10-07 更新（D-F10 + Q 波完成）**：对象 = Q 波候选 release 包（master `61d13a8`，副本 `w76-evidence/candidate/`；universal `ef55c0af…b35fe26a` / arm64-v8a `c18815c2…6518f876`）。**复验已完成：V1 ✅（K60，含首帧 / Q5 口径裁减）与 V2 ✅（Pad 5，阅读组闭环）**——逐项结论见 §5 最后两行，新观察项见 §6.1（Q8–Q10）；正式重建签名 + 哈希落档随后执行（报告 `W76-V1.md` / `W76-V2.md`）。

**W76-V1 发布包复验（K60 `8e875894`）**：

1. 手续：`adb install -r` 修复重建包覆盖现 1.1.0（保留登录态）；装前记录 `dumpsys package` 与设置 / 下载计数，装后核对 `1.1.0 (2)`、免重登、设置保留；release 严格口径需要时先 `pm clear`（登录态用 Quick Connect 重建，见 R1 ⑤）。
2. **B9 严格首下（本波主验收）**：清数据 / 首次安装后 → 冷启登录 → 视频 / 音乐 / 书籍各入队一条下载 → 预期真实进度与残片增长、**无「失败 / 0 B」**（修复前必失败）；暂停-恢复-删除清理，测后清空任务。
3. **B11/B11b 系列级起播**：任一剧系列详情页「播放」→ 预期 **2–5 s 出画**（对照修复前 31.7 s 空白 + `00:00/00:00` + `队列为空`）；加载中 BACK 退出无错误 Toast；季 / 集级不回归；**增补**：直连 / 转码 + 连播 NEXT 切集轻量复跑（覆盖 Q5/Q6 影响面）。
4. **B10 内核切换（抽验）**：先播音乐 → 同进程进视频（10-bit 片源）→ 解码面板切 mpv 立即生效（修复前选择被吞 /「播放失败」卡片）；退出后音乐隔离不回归。
5. 稳定性 0 FATAL·ANR；还原：`force-stop`、删测试残片 / 通知、内核 / 码率偏好还原、进度副作用如实记录；device-lock 登记 / 释放。
6. **Q 波增补**：Q7——5 次「删任务 → 重入队」全部立即启动（正式验收）；Q1——剧集页眉标「所属季」入口 → 到达所属季页（抽验）。

**W76-V2 阅读组补测（Pad 5 `43af8627`）**（R2 未覆盖组；书籍库已由用户建立 = Q3）：

1. 在线书架：入口显示书籍；EPUB / PDF / CBZ 各开一本 → 出页正常、进度保存与返回恢复。
2. 阅读设置（字体 / 亮度等）切换即时生效 + 重启保持（#19 阅读侧）。
3. 离线：下载 1 本（顺带覆盖 B9 修复后的书籍下载）→ 飞行模式打开离线书 / 离线书架；下载页「已完成」逐条核对（R2 当时 0 条）。
4. 0 FATAL·ANR；还原与副作用记录；device-lock 登记 / 释放。非目标：不重跑 R2 已通过的 8 组。

## 4. 决策记录

| 编号 | 决策 | 来源 / 理由 |
|------|------|-------------|
| D-F1 | 本轮版本 = **1.1.0（code 2）** | 用户 2026-10-06「按推荐」；含新功能（评分 / 搜索）按 MINOR |
| D-F2 | 后台播放默认 **开 → 关**（推翻 W68 D57 的默认值） | 用户 2026-10-06 明确要求；已持久化过用户值不强制改写 |
| D-F3 | 已播章节刻度**改回白色**（吸附条仍 3dp 高亮） | 用户 2026-10-06 建议；原 D54 / D62 的「已播用媒体色」推翻 |
| D-F4 | 测试策略：**单设备（K60）验证即可** | 用户 2026-10-06「提高效率」；Pad 5 仅形态相关时用 |
| D-F5 | 文档结构：本文件为唯一入口，`PROJECT_PLAN` §3 挂线，细节回写各线文档 | 延续「一条线一个文档」，不新增零散文档 |
| D-F6 | **不做大规模重构**；仅局部收敛（数据源 / 占位 / 字幕状态） | 刚发布 + 18 条覆盖咽喉文件，大重构放大回归面 |
| D-F7 | 视频页顶栏「库选择」选中具体库 → **直达该库内容**（覆盖 D57 的「只过滤库卡」语义；选「全部库」时仍显示卡片总览） | 用户 2026-10-06 拍板「使用 B 方案」；**S74-3 已实现**：复用侧栏 / 媒体页同一条 `libraryEntryRoute` 直达链路（`VideoScreen.onOpenLibrary` → `navigateToItem`），库卡模式恒为「全部库」总览（历史偏好不再过滤库卡） |
| D-F8 | 视频「循环·随机」**持久化**（方案 B）：新增 `pref_player_repeat_mode` / `pref_player_shuffle`，进播放页时应用（切内核 / 重开播放页 / 冷启动保持） | 用户 2026-10-06 拍板「按推荐的方案 B」；对齐 #19「切换 + 重启保持」与音乐侧队列持久化 |
| D-F9 | **方案B**：B9 + B10 + B11 三项全部修入 1.1.0（B9 阻断项 + B10/B11 一并修，不留给 1.1.1） | 用户 2026-10-07 拍板「使用方案B」；修复改变 release 哈希 → `RELEASE_PLAN` §7/§8 / `RELEASE_NOTES_v1.1.0` / 全检对象同步更新为修复后重建包 |
| D-F10 | **Q1–Q7 全部并入 1.1.0**：Q3 书籍库永久保留；Q4 按建议（B11 主因定级 P2 + 对外文案简化）；回归暂停令解除——执行序 = Q 波修复 → 终门禁 → 候选 release（自动签名）→ V1/V2 回归（+ 播放轻量复跑）→ **回归通过后**正式重建签名 + 哈希落档 → 用户全检 → tag | 用户 2026-10-07 拍板「Q1到Q7全部修进1.1.0，随后进行回归，不再阻塞」「书籍库永远保留」「Q4按建议的来」「从建签名等回归没问题后再进行」 |
| D-F11 | **阅读流式 / 按需加载 = 下一波头号项（方案 A）**：1.1.0 照原计划发布；本项先 spike（已派发 `w77a`）→ EPUB 流式 → CBZ 远端 zip → PDF 预览 → 专项回归 | 用户 2026-10-07 拍板「就按A方案实施」；诊断与分格式方案见 `READER_PLAN` §9「2026-10-07」条 / `FIX_PLAN` Q11 |
| D-F12 | **PDF 快速首开 = 换 PDF 引擎路线**（流式改造已证不可行）：**以后实施、当前先调研**；与 W77 波解耦——W77 内 PDF 过渡态 = 「整本下载 + 打开显示进度」 | 用户 2026-10-07 拍板「换PDF引擎。以后再做，现在先调研」；调研范围见 `READER_PLAN` §9「2026-10-07 · PDF 引擎替换（调研）」条 |
| D-F13 | **PDF 引擎替换 6 决策点拍板（2026-10-07）**：①采用 PDFium 路线 ✅；②**首开口径修正 = 不做后台整本下载**，只按需 Range 预取「当前页 + 后面几页」（用户原话「后台不能整本下载，只预加载当前和后面几页，不要一次性全部加载，这样会浪费流量」）；③Phase 1 保留 PdfBox、Phase 2 迁 PDFium 文本层 ✅；④MuPDF 仅记录在案、不投入 ✅；⑤双栏版式扫描改用 PDFium 批量页尺寸 ✅；⑥接受新增 native 依赖 ✅ —— 待排 PDF 实施波 | 用户 2026-10-07「第2条…其他按推荐」；详情见 `READER_PLAN` §9「PDF 引擎替换」小节 |
| D-F14 | **三格式统一「不做自动整本下载」（2026-10-07）**：EPUB / CBZ / PDF 远端打开一律**只按需预取「当前 + 后面几页」**，不自动整本下载；手动「下载整本」按钮语义保留（用户主动点才下载；完成后可热切换）；失败 / 离线回退整本下载的兜底保留。影响：W77-1（EPUB）的「打开即自动后台整本下载」作废、移除并入 W77-3；W77-2（CBZ）按新口径施工；PDFium 实施波按 D-F13 第 2 条 | 用户 2026-10-07 回复「统一」（承接 D-F13 第 2 条修正） |

## 5. 进度日志

| 日期 | 事件 |
|------|------|
| 2026-10-06 | 建线：18 条登记 + 波次划分 + 决策 D-F1–D-F6；W73 开工（负责人线程取证）。 |
| 2026-10-06 | W73-S1 取证完成：K60 装机（debug 1.0.0 / master `205db28`）+ 登录；直连 seek 正常、**转码流 seek 复现长卡/回片头**；根因 = PlaybackInfo 未传 `startTimeTicks`（转码恒从 0 开始）+ 进度条 seek 未排队 + 回退 recreate 路径待查（#8）。详见 S73-1 卡内取证结论。 |
| 2026-10-06 | **会话模式调整**：子代理通道载荷投递异常（3 个中 2 个收不到任务、1 个收到并产出 WIP）→ 改用 **3 条可见会话线程**（W73-S1/S2/S3，各带 app 托管 worktree）继续 W73 波 1；前任子代理在 S73-3 留下的半成品保存在分支 `fix/w73-watch-state`（`f4359c0`：未知季图/取集回落 + 季看完打勾；未跑门禁与真机，由 W73-S3 会话评估复用或重做）。 |
| 2026-10-06 | W73-S2 完成（#16 + #6，分支 `fix/w73-library-entry`，Pad 5 `43af8627` 单机）：复现「视频页只显 2 张库卡 / 库选择只 2 项 / 点『其他』回落卡层」→ 根因 = `pickVideoLibraries` 只放行 movies+tvshows（与 `temporaryLibraryKindOf` 不一致）+ 聚合类型缺 `Video` + 侧栏·媒体页「只加载一次」竞态；修后三入口集合一致、每个库直达内容。门禁 `assembleDebug` + `ktfmtCheck` 全绿、单测 **814 / 0**（基线 813 + 1）。详见 `UI_PLAN` §4/§5 W73-S2。 |
| 2026-10-06 | W73-S1 修复与真机验收完成（分支 `fix/w73-player-seek`）：`startTimeTicks` 透传 + 转码超窗重开会话 + 统一 seek 排队 + #8 网络抖动兜底；门禁 823 项全绿；K60 实测落点 0.9–1.5 s、断网不退出。详见 `PLAYER_PLAN.md` §32。 |
| 2026-10-06 | **W73 波 1 合并验收完成**：S1/S2/S3 三支 rebase 后合并 master（`a94ef2a`）；合并态门禁 = 根 `assembleDebug` + `ktfmtCheck` 全绿、8 任务 `--rerun` **830 项 / 0 失败 0 错误**（813 基线 + S1 10 + S2 1 + S3 6）；推送触发 CI。负责人 K60 补验合并包（15:05–15:20）：#10② 未知季详情（海报 + OAD 集列表 + 播放键可用 + 点播放进入 S0:E0）、#4 返回一次内刷新（标记 / 取消「已播放」往返各一次生效并已还原）、#11 首页「继续观看」走廊（出现 + 点击进入 ISUCA S1E1）；设备已还原（App force-stop / 临时文件删除 / 观看状态复原）。新增需求 #19（播放 / 阅读设置切换 + 重启保持，S74-4）。 |
| 2026-10-06 | 基建：**子代理载荷投递修复（方案一）**——`config.toml` 增 `features.multi_agent_v2 = false`；`models.json` 两条 deepseek 条目 `multi_agent_version` v2→v1、`supports_search_tool` → false；备份 `.codex\backup-multiagent-fix-2026-10-06\`；需重启 Codex 生效（重启后 spawn 探针复验）。另：#6 依用户 B 方案拍板（D-F7，并入 S74-3）。 |
| 2026-10-06 | 基建：**方案一重启后复验失败**（进程 15:37 重启、探针仍收不到载荷）→ **实施方案二（自建最小本地代理）**：`C:\Users\zhangwenkang\.codex\deepseek-subagent-proxy\`（`proxy.mjs` 只做 `agent_message`/`encrypted_content` → 普通 user 消息改写 + 逐字节透传；`watchdog.ps1` 看护 + Startup vbs 登录自启）；`config.toml` `base_url` → `http://127.0.0.1:8787/`；自测 `REWRITE_OK` + 真实 API 烟测 HTTP 200；备份 `config.toml.before-proxy.bak`；**待再次重启 Codex 后复验 spawn 探针**。 |
| 2026-10-06 | 基建：**方案二首次上线失败复盘**——重启后应用「无法联网」，用户已将 `base_url` 临时改回 `https://api.deepseek.com`。排查结论：**代理/看护进程已死**（8787 无监听、无 node/watchdog 进程、日志停在启动行）＝后台进程被 agent exec 会话回收带走、且计划任务注册被系统拒绝访问（`Register-ScheduledTask` 拒绝访问）→ 连接被拒并非改写逻辑问题。**修复**：①改用 **WMI 派生**（`Win32_Process.Create`，脱离会话树）启动看护，看护拉起 node；②登录自启由 Startup `codex-deepseek-proxy.vbs` 承担；③代理新增访问日志（`logs/proxy.log` 记录 `METHOD path -> status`，无正文）；④新增 `rollback.ps1` 一键回滚。**复测**：watchdog+node 跨命令会话存活、`healthz` OK、真实 API **SSE 流式**请求（含 `agent_message` 载荷）HTTP 200（13722 B / `text/event-stream`）。待用户确认后切回代理并再次重启复验探针。 |
| 2026-10-06 | **子代理通道复验通过（方案二生效）**：重启后代理访问日志见应用流量 `POST /responses -> 200`；spawn 探针返回 **`PAYLOAD_OK`**。W74 波 2 派发：**S74-1 字幕链路（#15+#14，K60）**、**S74-3 黑块清零+#6 库选择直达（D-F7，#3/#17，Pad 5）**、**S74-4 设置持久化盘点（#19，无设备先只读审计）**；S74-2（#9+#18）待 S74-1 合并后开（PlayerViewModel 咽喉文件串行）。 |
| 2026-10-06 | **W74-S3 完成（#3 + #17 + D-F7，分支 `fix/w74-blackcards`）**：媒体库总览大卡 + 库内容条目卡统一「域色底 + 类型图标」占位（沿用 D81 口径），无封面 / 加载中 / 失败三态都不再出黑块；视频页「库选择」选中具体库改为**直达该库内容**（复用侧栏 / 媒体页同一条 `libraryEntryRoute` 链路，库卡模式恒为「全部库」总览）。门禁 835 项全绿（基线 830 + 5）；Pad 5 真机实测（含断网失败态、聚合显示方式回归、0 FATAL / ANR）。详见 `UI_PLAN` §4/§5 W74-S3 与 `.planning/cinefin-expansion/w74-reports/W74-S3.md`。 |
| 2026-10-06 | W73-S3 只读取证（ISUCA 未知季结构 / 全看完季 userData / admin 与测试号 Resume）+ 复用前任 WIP `f4359c0` 并补全：季取图回落链（季卡 / 详情海报 / 两版 ItemHeader / 两版 HeroBackdropLayer）、未知季取集回落（`SeasonId=null`）、季「全看完打勾」、`ShowScreen`/`SeasonScreen` 返回 RESUMED 即刷新；门禁全绿（**819 项 / 0**）；K60 真机待设备窗口（#10② / #11 / 返回刷新）。证据：`w73-evidence/w73_watch_state/api-probe.md`。 |
| 2026-10-06 | **W74-S1 完成（#15 + #14，分支 `fix/w74-subtitles`，K60 `8e875894` 单机）**：①#15 根因 = `ass-kt` 的 JNI 在 libass 该时刻无图元时返回 `null`（不是空帧），上层当「本帧无结果」保留上一帧 → 句末字幕挂到下一句；修 = 显式空帧清屏 + 覆盖层三态状态机（`LibassFramePolicy`）。②#14① 根因 = `LanguageMatcher.detect` 取「第一个能识别的线索」，Jellyfin 的泛化语言码 `chi`/`zho` 抢占了标题里的简繁线索（`简体中文` / `Chinese(Traditional)` / `简日双语` / `chs-01`）+ 单字「简 / 繁」关键字缺失；修后默认优先级自动命中 `zh-Hans`。③#14② 手动选择落新键 `pref_player_subtitle_manual_selection`，`reset()` 按媒体恢复（切 3 Mbps 重建播放页后仍保持）。④#14③ 次字幕语言记忆（`pref_secondary_subtitle_languages`）+ 检测修复跨视频继承（夏日幽灵 手动次字幕=日语 → 冰海战记 E1 自动选日语轨）。门禁见 `PLAYER_PLAN.md` §33.3；真机证据 `w74-evidence/w74_s1/`；**新发现**：转码档（3 Mbps）服务端字幕交付内容为空（`cues=0`）建议单开一条。 |

| 2026-10-06 | **W74 波 2 合并验收**：S74-1 `e636531`（#15 句末清屏 + #14 简繁 / 码率保持 / 次字幕继承；+15 单测）+ S74-3 `2b87729`（#3/#17 占位不黑 + D-F7 库选择直达；+5 单测）合并 master `2f0055d`；合并态门禁 = `assembleDebug` + `ktfmtCheck` + 8 任务 `--rerun` **850 / 0 失败 0 错误**；已推送。S74-4 只读审计完成（64 条 / 13 项不保持，报告 `w74-reports/W74-S4.md`）。**用户新反馈 #20（字幕↔次字幕叠行 → 上下两行）与 #21（转码档字幕为空）已登记，移交新负责人会话**（2026-10-06 按用户指示开新会话交接）。 |
| 2026-10-06 | **W74 尾波派发（第五任负责人）**：①**W74-S5**（#20 主/次字幕上下两行）K60 `8e875894`，分支 `fix/w74-subtitle-two-lines`，worktree `w74d`；②**W74-S2**（#9 倍速跨集继承 + #18 后台播放默认关）Pad 5 `43af8627`，分支 `fix/w74-playback-inherit`，worktree `w74e`；③负责人线：**#21 服务端只读取证**——用与 App 相同的 PlaybackInfo 请求（3 Mbps + EnableTranscoding + HLS/ts + EnableSubtitlesInManifest + SubtitleProfiles srt/ass External）复现：转码源字幕 `DeliveryUrl` 存在（Method=External）且直接 GET 返回 **HTTP 200 + 合法 ASS**（冰海战记 S2E1 idx5/26/11/25 均 <2s，12.8–17.8 KB）→ **服务端交付正常**，`cues=0` 需 App 侧证据（已交由 S74-5 在 K60 补只读日志取证）。设备与 worktree 已登记 device-lock。 |
| 2026-10-06 | 用户拍板 **D-F8（U2 = 方案 B：视频循环 / 随机持久化）**；登记 W74-S7 任务卡（U2-B + U5/U6/U7，排期在 S74-2 合并后，咽喉文件串行）。负责人线保持「最小必要」：只做派发 / 收口核对 / 合并态门禁复跑 / 推送 CI / 文档回写，其余交子代理，避免重复劳动。 |
| 2026-10-06 | **W74-S5 合并验收（#20）**：分支 `fix/w74-subtitle-two-lines` rebase 后合并 master `19a1850`；合并态门禁 = `assembleDebug` + `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **858 项 / 0 失败 0 错误**（app 245 / core 89 / data 68 / player:core 12 / player:local 138 / film 53 / book 113 / music 140）；已推送 CI。K60 真机：主 libass 上移 + 次文本贴底两行分离（改前叠行）、单字幕与 #15 句末清屏不回归、样式 / 延迟不回归；#21 同窗口取证 3 Mbps 字幕正常 `cues=442`（未复现空字幕）。证据落 `.planning/cinefin-expansion/w74-evidence/w74_s5/`；详见 `PLAYER_PLAN` §34 与 `w74-reports/W74-S5.md`。 |
| 2026-10-06 | **子代理模型修正**：W74-S2 派发误用 `deepseek-v4-pro`（用户纠正应为 `deepseek-flash`）→ 已叫停该会话，未提交改动（MPVPlayer / PlayerViewModel / AppPreferences 三处）保留在 worktree，改由 flash 接手会话 `w74_playback_inherit2` 复核补全；**此后子代理一律 `deepseek-flash`**。另：本地 DeepSeek 代理加装 usage 只读日志（`logs/usage.log`：model/in/out/cached/reqBytes），实测子代理单请求 ~21–30 万 tokens、缓存命中率 ~99% → 成本主因 = 大上下文 × 高频往返。 |
| 2026-10-06 | **W74-S2 合并验收（#9 + #18）**：flash 接手会话复核后补全（`onNewIntent` 新会话回落 1× + mpv 侧按 `mpvSpeed` 真实值判定 + 纯函数 `PlaybackSpeedSession` +3 单测）；rebase 解决 `PLAYER_PLAN` 章节冲突（S74-2 小节改为 §35）后合并 master `010cc79`；合并态门禁 = `assembleDebug` + `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **861 项 / 0 失败 0 错误**（858 + 3）；已推送 CI。Pad 5 真机 9 组全过（直放 / 3 Mbps 转码 / mpv 切集保持 1.5×、新开会话回落 1×、#18 显式开 / 关 / 清数据新装 / 保留用户值），0 FATAL·ANR。未覆盖：mpv `dumpsys media_session` 读数既有问题（B7）、内核 / 码率切换回落 1×（按任务卡语义）、K60/TV 未走查。 |
| 2026-10-06 | **下一波派发（均 `deepseek-flash`）**：**W74-S7**（U2-B 循环 / 随机持久化 + U5 长按倍速档即时生效 + U6 seek 步进即时生效 + U7 首选语言下拉读数 + #21 字幕下载重试加固）K60 `8e875894`，分支 `fix/w74-prefs-live`，worktree `w74f`；**W75-S2**（#5 进季返回保持滚动位置 + #2 管理员入口首开即显）Pad 5 `43af8627`，分支 `fix/w75-nav-scroll`，worktree `w75a`。W75-S1（#1）因与 W74-S7 共用 `PlayerControlOverlay` 咽喉文件、W75-S3（#13/#12）因与 W75-S2 可能共用剧集详情页，均排下一轮。设备与 worktree 已登记 device-lock。 |
| 2026-10-06 | **W74-S7 + W75-S2 合并验收**：`e2769db`（U2-B / U5 / U6 / U7 + #21 字幕下载重试；K60 逐项真机过、0 FATAL·ANR）+ `f472e38`/`d0a50ca`（#5 `NavScrollMemory` 滚动位置记忆 + #2 管理员入口首开即显；`NavigationRoot.kt` +2 行已申报；管理员场景转用户代测）rebase 后合并 master `999cab9`；合并态门禁 = `assembleDebug` + `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **884 项 / 0 失败 0 错误**（861 + 12 + 11）；已推送 CI。副作用：随机跳集把「冰海战记」E16 等与「灼眼的夏娜」E4 标记为已观看（不可回滚，需用户知晓）。**下一轮**：W75-S1（#1 刻度白 + 吸附手感）、W75-S3（#13 搜索入口 + #12 评分）→ W76 收口（1.1.0 构建 + release + 发布）。 |
| 2026-10-06 | **负责人交接阈值 + 观测升级（用户拍板）**：①负责人会话「上下文接近 **35 万 token** 主动交接」，交接文档（本进度日志 + `cinefin-v11-update` 记忆）**全程实时更新**，负责人只做少量任务（1–2 波）；②本地 DeepSeek 代理 usage 观测升级——压缩请求标 `kind=compact`（识别输入末条 user 文本的 `CONTEXT CHECKPOINT COMPACTION`，历史引用不误报）、新增 `hit=%`（cached/in）并在 API 缺失时派生 `missed=in−cached`；离线自测 `USAGE_TEST_OK`，线上已热重启（`healthz` 0.8s 恢复，实况行 `kind=normal hit=99.8% missed=220`）。回滚备份 `proxy.mjs.bak-compactobs-2026-10-06`。 |
| 2026-10-06 | **压缩阈值回退（用户拍板）**：`models.json` 两模型 `auto_compact_token_limit` 250000 → `null`（恢复原值；当前文件与备份 `models.json.bak-compact-2026-10-06` 逐行一致，JSON 校验通过）。**需完全重启 Codex 生效**——20:45:31 启动的进程加载的仍是 250k 版。注意：`null` = 出厂默认（接近窗口时可能仍有内置兜底压缩），只是去掉了提前触发点；会话续命以「接近 35 万主动交接」为准。 |
| 2026-10-06 | **W75-S1 + W75-S3 合并验收**：`ce82473`（#1 已播刻度白 85% + 吸附窗口按片长 2%/3% 派生（下限 ±2s/±3s，K60 触控区 ≈3px → ≈40px）+ 触觉升级；K60 真机、0 FATAL·ANR）+ `a874c04`（#13 全页搜索入口 + #12 详情评分；Pad 5 真机、0 FATAL·ANR；`NavigationRoot` +19 行已申报）rebase 后合并 master `a874c04`；合并态门禁 = `assembleDebug` + `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **896 项 / 0 失败 0 错误**（884 + 9 + 3）；已推送 CI。测试副作用：冰海战记 E16–E18 观看进度被改写（不可回滚，需用户知晓）。**下一步**：W76 收口（全量回归 + 发布面补验 + 1.1.0 构建签名 + Release notes → 用户全检后发布）。**转用户代测**：① #2 管理员入口首开即显（步骤见 `w75-reports/W75-S2.md`）；② W75-S1 吸附手感（步骤见 `w75-reports/W75-S1.md` §四）。 |
| 2026-10-06 | **W76 收口开工（第七任负责人）**：接手态核对（master `7fb2472`、W75 全部合并、896/0；FIX_PLAN 全文 + `RELEASE_PLAN` + `TEST_PLAN` §7.6 + 记忆已读）。**W76 任务卡落档（§3）**：F1 日志脱敏（B8，K60）/ 版本 1.1.0+code2 / release 构建签名 / R1（K60 发布面补验 B5）/ R2（Pad 5 核心回归）/ Release notes / 用户全检后发布。已派发 **W76-F1**（分支 `fix/w76-log-redact`）；负责人线并行推进版本号与 Release notes 草稿。设备：F1 用 K60 `8e875894`，R2 预留 Pad 5 `43af8627`。 |
| 2026-10-06 | **W76-F1 完成（B8 日志脱敏，分支 `fix/w76-log-redact`，worktree `w76a`，代码提交 `0266353`）**：①新增纯函数 `redactUrlSecrets(url)`（`PlayerMediaInfoFormat.kt`）——保留 scheme/host/path 与非敏感 query，`api_key` / `ApiKey` / `API-KEY` / `X-Emby-Token` 等鉴权参数值 → `***`，大小写与 `-`/`_` 混写都命中，无 key / 空串 / 非 URL 原样返回；`PlayerViewModel` L730 `Timber.d("Stream url: …")` 改用脱敏结果。②**顺手修同线的第二处同类泄露**：`PlayerActivity` L455 `Timber.d("$uiState")` 把 `mediaInfo.path`（同一条转码 `master.m3u8?...&ApiKey=<token>`）全量入库 → 改为 `redactUrlSecrets(uiState.toString())`（首轮真机取证实测命中，1 行改动，非红线文件）。③门禁 `assembleDebug + ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **899 项 / 0 失败 0 错误**（896 + 3 个新测试方法）。④K60 真机：灼眼的夏娜 S01E03（10-bit Hi10P → 服务器转码）logcat `Stream url` 行 `ApiKey=***`、全量扫描无明文凭据、`state=PLAYING(3)` 起播正常、crash 缓冲 0 FATAL。⑤其它 URL 日志点扫描仅报告：字幕控制器 / 通知封面已脱敏，`X-Emby-Token` 只作请求头，SAF `content://` 无凭据。⑥口径修正：任务卡的 `logcat -s Timber` 取不到行（DebugTree 用类名作 tag，如 `PlayerViewModel`），改用全量 logcat 过滤。报告 `.planning/cinefin-expansion/w76-reports/W76-F1.md`。测试副作用：灼眼的夏娜 S01E03 观看进度被改写（不可回滚）。 |
| 2026-10-06 | **W76 合并态门禁 + release 构建签名（负责人）**：F1 分支 ff 合并 master（代码 `0266353` + 文档 `458eb9a`，基线 `1b533fb` = 版本号 `1.1.0 (2)`）。合并态门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **899 项 / 0 失败 0 错误**（app 272 / core 89 / data 68 / player:core 12 / player:local 152 / film 53 / book 113 / music 140）。`assembleLibreRelease "-Paurorama.universalApk=true"` 成功：universal 171,125,240 B（163.2 MiB）`9c20b06c…c58570`、arm64-v8a 77,543,262 B（74.0 MiB）`d24df47d…5666b0e`；两份 `apksigner verify` v2 通过、证书 SHA-256 `e449c4aa…e155ff` 与 `RELEASE_PLAN` §2 一致（§7 / §8 已回写）。踩坑：PowerShell 下 `-Paurorama.universalApk=true` **必须加引号**，否则 Gradle 把 `.universalApk=true` 当任务名（首次 34s 失败，已修正）。发布资产副本（含 1.0.0 对照包）在 `.planning/cinefin-expansion/w76-evidence/`。**R1（K60 发布面补验 B5）/ R2（Pad 5 核心回归）已派发**；Release notes 草稿落 `docs/RELEASE_NOTES_v1.1.0.md`。 |
| 2026-10-07 | **W76-R1 发布面补验完成（K60 `8e875894` 独占，22:22–00:10，0 FATAL / 0 ANR）**：①1.0.0→1.1.0 覆盖升级 **✅**（免重登 / 设置下载保留 / 关于页 `1.1.0 (2)`）②release 真实下载 **❌**（三类样本全 `失败 / 0 B / 0%`、无真实媒体 HTTP 仅 `PlaybackInfo`；**1.0.0 同样失败 → 非 1.1.0 引入**，根因未定位，建议单独立项）③SAF 本地库重建 **✅**（坑：`adb push` 中文名截断 → 改英文名；已删库删文件）④**mpv 兜底 ✅ 决定性复验**——干净进程 + 码率「原始画质」，解码面板切 ExoPlayer 后 Hi10P 报 `10bit Luma/Chroma NO_EXCEEDS_CAPABILITIES` + `c2.qti.avc.decoder Decoder failed`，App **无需干预自动回退 mpv 软解**并续播（`media_session` 由 `NONE(0)/position=0/item=0` → `BUFFERING(6)/pos=99000` → 释放 → 稳定 `PLAYING(3), position=-1, speed=0.0`；logcat `[vd:v] Decoder format: 1920x1080 yuv420p10`）⑤Quick Connect **✅**（`pm clear` 冷装 → 服务器 → 6 位码 `296876` → 服务端 `POST /Users/AuthenticateByName` + `POST /QuickConnect/Authorize` = `true/200` → App 轮询自动登录 `zhangwenkang` / `普通用户`）。**新缺陷 D1（P2 疑似）**：`PlayerHolder.musicSessionActive` 粘住会让同进程内「换内核 / 回退链」全部失效（`PlaybackCoordinatorImpl.onVideoStartRequested()` 全仓库**无调用点**，`PlayerHolder.kt:93/186` 两处锁死；本轮未做「先音乐后视频」对照复现 → 待定级）；**D2（观察 P3）**系列详情页「播放」进入空载播放页（00:00/00:00、队列为空）。副作用：灼眼 S1E1/S1E5、冰海战记 S1E1 观看进度被改写（不可回滚）+ `pm clear` 清空 App 本地数据。报告 `.planning/cinefin-expansion/w76-reports/W76-R1.md`。 |
| 2026-10-07 | **W76-R1b 定级取证完成（K60 `8e875894` 独占，00:15–00:43 约 28 分钟，只诊断不修，0 FATAL / 0 ANR）**：①**D1 = 缺陷确认 · P2**（**非 1.1.0/W76 引入**，tag v1.0.0 同代码）——静态根因：`PlaybackCoordinatorImpl.onVideoStartRequested()` 全仓库无调用点（`git log -S` 仅命中 W1 R2 `21d4051` / W0 `24aa02d`），`PlayerHolder.kt:93`（`if (musicSessionActive) return audioSession()`）与 `:186`（`release()` 早退）两处锁死；真机复现：同进程先播音乐 → 进视频（灼眼 S1E5 续播）→ 解码面板点 mpv，播放页重载但内核仍为 ExoPlayer（原始 Hi10P 报 `format_supported=NO_EXCEEDS_CAPABILITIES` + `c2.qti.avc.decoder Decoder failed` → 自动回落服务器转码继续播），重开面板仍显示 ExoPlayer ✓（选择被吞）；**对照**：`am force-stop` 冷启（未播音乐）→ 同偏好直接生效 **libmpv**（`V mpv: [cplayer:v]` + `first video frame after restart shown`、`media_session position=-1`）→ 决定性命中「音乐会话粘住」。②**下载失败 = App 本地层（写盘前/网络前）**、**非 1.1.0 引入**（W50 `8f07ee8`/2026-10-03 起，1.0.0 同结构）——服务器只读对照全正常（`GET /Items/{id}/Download` = **206 + 1024B 无 302**；`PlaybackInfo` 的 `MediaSource.Id` 稳定、`Size=1017085577`、`SupportsDirectPlay=true`，`enableDirectPlay=false` 下 Id 不变）；App 侧任务行 `0 B`、无总大小、`…/files/` 下无 `downloads/` 子目录与残片，`DownloadEngineWorker → SUCCESS`；**决定性 A/B**：仅 `mkdir -p …/files/downloads` → 点「重试」→ 立刻真实下载（通知 `4.03 MB / 1.02 GB · 0%`、残片 `…download` = 4,034,322 B；暂停时 7.19 MB / 970 MB）。最强假设：`DownloaderImpl.kt:425 StatFs(target.parentFile?.path ?: context.filesDir.path)` 在 `…/files/downloads` 不存在时抛 `IllegalArgumentException("Invalid path")` → 被 `executeTask` 通用 catch 收成 `DownloadFailureReason.UNKNOWN`，而 `mkdirs()` 排在它之后（`DownloadHttpEngine.kt:92`）；**为何 W76-R1 才暴露** = W76-R1 的 `pm clear` 抹掉了外部 `downloads/` 目录。③**D2 = 缺陷（P3），不是设计回落**（1.0.0 同代码，`git show v1.0.0` 两文件逐字相同）——`ShowScreen.kt:182 ShowAction.Play` 直接把 series id 传给 `PlayerActivity`（itemKind=SERIES），`PlaylistManager.kt:54` SERIES 分支 = 续播（getNextUp）→ 否则第一季第一集，`seasons.isEmpty()` / `episodes.isEmpty()` 时 **`return null`（静默无提示）** → 播放页空载；服务器该剧 3 季齐全、NextUp = 第 6 集 → 空载来自解析异常被吞，非「设计上无内容」。顺带：`EpisodeAction.NavigateToSeason` 自 W66 起无发射点（剧集页无法回季/剧集页）。报告 `.planning/cinefin-expansion/w76-reports/W76-R1b.md`（`W76-R1.md` 顶部已加指针，二选一）。**还原**：`user_rotation=1` / `accelerometer_rotation=0`（accel 曾漂移为 1，已复位）、App `force-stop`、临时 `…/files/downloads/` 已 rmdir、下载任务与残片已清、失败通知已划、`/sdcard/r1b_*` 与 `%TEMP%\r1b_*` 残留 0、Pad 5 零接触。**副作用（不可回滚）**：播放内核偏好已变为 **mpv**（D1 对照所致，要回默认需手动切回）；灼眼 S1E5 等多条观看进度被改写。 |
| 2026-10-07 | **W76-R1c 补充取证完成（K60 `8e875894` 独占，00:50–01:47 约 57 分钟，超 45 分钟预算 12 分钟，只诊断/只还原，0 FATAL / 0 ANR）**：①**D1 最终定级 = P2**——粘住态（先播音乐 → 同进程再进视频）+ 码率「原始画质」+ 10-bit Hi10P：竖屏播放页全黑 `00:00 / 24:43` 无提示；横屏全屏弹**「播放失败」**卡片（`MediaCodecVideoRenderer error … video/avc avc1.6E0032 … 10bit Luma/Chroma … format_supported=NO_EXCEEDS_CAPABILITIES` + `exoplayer · ERROR_CODE_DECODING_FAILED`）+ 按钮 **「重试」/「改用 mpv 内核」**；`media_session` = `ERROR(7)`。点「改用 mpv 内核」→ 卡片消失但**卡 `00:00 / 00:00` 无限缓冲**（`BUFFERING(6), buffered=0`，**无 mpv 日志**）= 选择被吞。对照 A：粘住态下改「自动」重进仍拿原盘 Hi10P 直连 → 同样 `ERROR_CODE_DECODING_FAILED`；对照 B：非粘住态 + ExoPlayer + 自动档（季页选集，同片源 Hi10P）**正常出画**。定 P2 依据 = 有明确错误卡片/提示 + 有可行绕过（冷启动 App）+ 触发面窄（先音乐→同进程再进视频 + 非默认原画质 + 10-bit 片源）；**注**：若「必须重启才能看某类片源」不可接受可上调 P1。②**D2 真机复验 = 已复现（维持 P3）**——中文搜索入口无法 adb 驱动（`input text` 抛 NPE、SEARCH intent 不可解析）→ 走 **媒体库 → 动漫库（94 项）→「灼眼的夏娜」卡片** 达**系列详情页** → 点系列级「播放」→ `uiautomator` 得 `00:00 / 00:00` + **`队列为空`** + 无提示 + 全黑，`media_session = NONE(0)/metadata=null`；对照：季「灼眼的夏娜」→「第 1 集」→ 播放 → **正常出画**（`06:18 / 24:43`、`PLAYING(3)`）→ 仅系列级播放坏。③**内核偏好还原 ✅**——冷启动 → 冰海战记 E1 → 全屏 → 解码面板选 **ExoPlayer** → 进度保留（`17:57`）+ `position≥0`（ExoPlayer 宿主）+ 正常出画 → R1b 的 mpv 副作用已还原为默认；**冷进程换内核正常**，反证 D1 只在粘住态。还原：`user_rotation=1` / `accelerometer_rotation=0`（全屏取证期间临时切过已复位）、App `force-stop`、`/sdcard/r1c_*` 与 `%TEMP%\r1c*` 残留 0、Pad 5 `43af8627` 零接触。副作用（不可回滚）：内核偏好 mpv→ExoPlayer（= 默认）、码率偏好 原始画质→自动（= 默认）、灼眼 S1E1/S1E6 观看进度被改写。报告 `.planning/cinefin-expansion/w76-reports/W76-R1b.md` §七（R1c 补充）。 |
| 2026-10-07 | **用户拍板方案B（D-F9）**：B9 + B10 + B11 三项全部修入 1.1.0（不留给 1.1.1）；书籍媒体库已由用户建立 → R2 阅读组解封，补测并入本波。派发 **W76-B9**（`fix/w76-b9-download`，`w76b`，K60 `8e875894`）/ **W76-B10**（`fix/w76-b10-music-video`，`w76c`，Pad 5 `43af8627`）/ **W76-B11**（`fix/w76-b11-series-play`，`w76d`，设备暂缓）；均 `deepseek-flash`。后续 = 三支合并态门禁 → release 重建签名（哈希变化 → `RELEASE_PLAN` §7/§8 + Release notes 同步）→ 发布包复验（K60）+ 阅读组补测（Pad 5）→ 交用户全检。 |
| 2026-10-07 | 环境收口：`AGENTS.md` 构建命令 F 盘化（`F:\Develop\Android\Android Studio\jbr`）；graphify 忽略项落库（`.gitignore` 加 `graphify-out/`、新增 `.graphifyignore`）——与 f25f1d4 同批补提交。 |
| 2026-10-07 | **W76-B9 完成（分支 `fix/w76-b9-download`，commit `16f54a4`，未 push）**：新增纯函数 `DownloadStorageRules.resolveStatPath`（预检查前先建父目录 + 失败回落 `filesDir`）+ 通用 catch 前 `Timber.w` 留痕；`core` 93/0/0（+4 单测）；K60 debug 真机 A/B——目录缺失 → 点下载自动建目录 + 真实残片增长 5.06→14.63 MB（中途被无修复包反向对照复现「失败 · 0 B」旧症状）；release 严格口径（`pm clear` + 重登）并入发布包复验波；「失败原因落 DB/UI」仍为 §6.1 Q2。 |
| 2026-10-07 | **W76-B11 完成（分支 `fix/w76-b11-series-play`，commit `b8935f3`，未 push）**：SERIES / SEASON / 未知类型三处静默兜底取消（能降级则降级：NextUp 失败→第一季、归属季失败→季号最小季；无内容→中文 `PlaybackStartException` Toast）；`player:local` 159/0/0（+7 单测）；K60 真机：系列级 31.7 s 后出画（`PLAYING(3)`）、季 4.6 s / 集 9.0 s 不回归。**主因修正** = 起播前同步串行枚举整剧的 8–32 s 空白窗口（见 §3 执行修正）→ 追加 **W76-B11b**（`PlayerViewModel`，B10 合并后串行；含 `JobCancellationException` 不再被当失败 Toast）。 |
| 2026-10-07 | **W76-B10 完成（分支 `fix/w76-b10-music-video`，commit `fb3707e`，未 push）**：`initializePlayer()` 第一句接上协调器 + 新纯判定 `MusicSessionVideoStartPolicy`（音乐真在播才停播并补发 Stopped；标志粘住只清标志、不误停视频）——修掉旧守卫 `!isPlayingMusicItem() → return` 导致标志**永久**粘住；`player:local` 155/0/0（+3 单测）+ `:app:phone:assembleDebug` 绿；Pad 5 真机：修复前 release A 侧选择被吞（无 mpv 日志）→ 修复后 mpv 出画 + 回退链落 mpv + W68 隔离（attach 退出后音乐仍 `PLAYING(3)`）+ 0 FATAL/ANR。遗留：错误卡片按钮未单独点按（同入口已证）；`releasePlayer()` 对音乐条目补发 Stopped 的观察 → §6.1 Q5。 |
| 2026-10-07 | **W76-B9/B10/B11 合并态门禁（负责人）**：三支 rebase 后 ff 合并 master（`600be7a` / `d0942dc` / `1eb093f`）；合并态门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **913 项 / 0 失败 0 错误**（app 272 / core 93 / data 68 / player:core 12 / player:local 162 / film 53 / book 113 / music 140；基线 899 + B9 4 + B10 3 + B11 7；日志 `w76-evidence/gate_merged_2.log`）。**W76-B11b 已派发**（起播不等整剧枚举 + `CancellationException` 重抛；`w76e` / `fix/w76-b11b-series-start`，复用 W76-B11 会话）；B11b 合并后跑终门禁 → **release 重建签名（哈希变化）** → 发布包复验（K60）+ 阅读组补测（Pad 5）。 |
| 2026-10-07 | **W76-B11b 完成（分支 `fix/w76-b11b-series-start`，commit `080fd38`，待合并）**：系列级起播「先播后补队列」（快路径 = NextUp + 起播集所在季；整剧枚举落 `Playing MediaItem` 之后）+ 取消语义修复（`initializePlayer` 先放行 `CancellationException`；`runCatching` 不再吞取消）；门禁 `:player:local` **169/0/0**（27 suites）+ ktfmt + `:app:phone:assembleDebug` 绿；K60 真机首帧 **31.7 s → 2.0 / 5.0 s**、加载中退出不再误报；报告 `W76-B11b.md`。**负责人第七任按 35 万阈值交接（auto-compact 已触发）**：B11b 未合并；**回归暂停令**——用户「回归前先停一下」，发布包复验（K60）/ 阅读组补测（Pad 5）等用户放行后启动；移交摘要见记忆 `cinefin-v11-update` 与下表最新行。 |
| 2026-10-07 | **W76-B11b 合并验收（第八任负责人）**：分支由 `080fd38` rebase 至 **`3b85d95`**（基座 `dec7d04`，内容与原件逐字节一致）→ ff 合并 master（`dec7d04` → `3b85d95`）；终合并态门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **920 项 / 0 失败 0 错误**（app 272 / core 93 / data 68 / player:core 12 / player:local 169 / film 53 / book 113 / music 140；基线 913 + B11b 7；日志 `w76-evidence/gate_merged_3.log`）；已推送 CI（`dec7d04..3b85d95`）。 | 
| 2026-10-07 | **1.1.0 修复后 release 重建签名（负责人）**：`assembleLibreRelease "-Paurorama.universalApk=true"` 成功（master `3b85d95`，日志 `w76-evidence/release_build_2.log`）——universal **171,141,648 B（163.2 MiB）`c00c3311…31ef2f46`** / arm64-v8a **77,559,670 B（74.0 MiB）`be25b209…efef3ec9`**；`apksigner verify` 两份均 **v2 = true**、证书 SHA-256 `e449c4aa…e155ff` 与 §2 一致；`RELEASE_PLAN` §7/§8 + `RELEASE_NOTES_v1.1.0.md`（B9/B10/B11b 已修口径）回写并推送；旧构建副本移入 `w76-evidence/superseded/`。**回归暂停令持续**：V1 发布包复验（K60）+ V2 阅读组补测（Pad 5）清单已备（§3），等用户放行。 | 
| 2026-10-07 | **用户拍板 Q1–Q7 全部并入 1.1.0（D-F10）+ 回归放行**：①Q3 书籍库**永久保留**；②Q4 按建议（B11 主因合并定级 **P2**；对外文案简化为「空白等待 8–32 s → 2–5 s」）；③Q1/Q2/Q5/Q6/Q7 全部排入本波修复；④执行序 = **Q 波修复（4 任务包，§3）→ 终门禁 → 候选 release（自动签名）→ V1/V2 回归（+ 播放组轻量复跑）→ 回归通过后正式重建签名 + 哈希落档 → 用户全检 → tag**。`3b85d95` 候选包将被 Q 波后重建取代（§7② 标注待重建）；回归暂停令解除。 | 
| 2026-10-07 | **W76-Q 波派发（第八任负责人）**：① **Q1** → worktree `w76f` / 分支 `fix/w76-q1-season-entry` / Pad 5 `43af8627`；② **Q2+Q7** → `w76g` / `fix/w76-q2-q7-download` / K60 `8e875894`（均 `deepseek-flash`、device-lock 已预登记、未 push）。后续批次：**Q5**（Pad 5，player/local 咽喉）→ **Q6**（K60，待 Q5 合并后串行）。全部合并后终门禁 → 候选 release → V1/V2 回归。 | 
| 2026-10-07 | **W76-Q2+Q7 完成（分支 `fix/w76-q2-q7-download`，`8fd8a5f` Q2 + `744a631` Q7，未 push；worktree `w76g`；K60 `8e875894` 独占；04:10–04:27）**：**Q2 可诊断性** —— 新增纯函数 `DownloadFailureClassifier`（网络族 / 磁盘满 / 本地文件 / 取消 → 具体原因，真未知才 UNKNOWN），接入 `executeTask` 通用 catch + `resolveRemoteSource` + `DownloadHttpEngine` IOException catch；K60 真机「目标父目录不可写」→ DB `failureReason=FILE_ERROR` + 下载页 **「文件写入失败」**（修复前该链路被 IO 兜底误判 `NETWORK_UNAVAILABLE` → 「网络错误」）；**申报**原因→策略表零改动、`resumeStrategy` 生产零调用点，但文件 / 空间类不再无限自动重试（原误判为网络）。**Q2 负向发现**：飞行模式**中途**断网 → WorkManager 依 CONNECTED 约束停 worker（`StopWorkRunnable`）→ 引擎按 worker 中断处理（保留残片 / 回 PENDING / **不落失败原因**）→ 行「正在等待…」；恢复网络自动续传 21.1→34.7 MB（纯网络中断无失败原因文案 = 既有行为，Q2 未改）。**Q7 未复现 → 加固 + 留观** —— 假设 = 新任务落库恰在 worker「判定队列空 → 退出」窗口、入队方读到 `RUNNING` 跳过唤醒 → PENDING 无调度；加固 = 入队方 ↔ worker 锁内原子退出交接（kick / 补 worker）+ 调度决策纯函数 `DownloadEngineScheduleRules`（既有 `ExistingWorkPolicy` / `REPLACE` 语义逐字保留）；K60 10 轮「删 → 重入队」**0 复现**（每轮「正在下载…」、12 次 worker 启动 = 1 次/入队），正式 5 次验收排在 V1 发布包复验波。**门禁**（`w76-evidence/gate_w76_q2q7.log`）= `:core:ktfmtCheck` ✅ + `:core:testDebugUnitTest` **104/0/0**（基线 93，+11）+ `:app:phone:assembleDebug` ✅；真机 **0 FATAL / 0 ANR**（dropbox 无新增）。设备已还原（下载任务 0 条 / `downloads` 目录删除 / force-stop / 飞行模式关 / Wi-Fi 开 / 旋转未改）；release 包零接触。报告 `w76-reports/W76-Q2-Q7.md`。 | 
| 2026-10-07 | **W76-Q1 + Q2+Q7 合并验收（第八任负责人）**：两卡 rebase 后 ff 合并 master（Q1 `8f40a90`；Q2 `5610811` + Q7 `b149157` + docs `7a999e1`；Q2+Q7 rebase 时 §5 表格行冲突已解决——保留派发行与完成行双行）；合并态门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **932 项 / 0 失败 0 错误**（app 273 / core 104 / data 68 / player:core 12 / player:local 169 / film 53 / book 113 / music 140；基线 920 + Q1 1 + Q2/Q7 11；日志 `w76-evidence/gate_merged_4.log`）；**负责人经办**：①接受 Q2「文件 / 空间类失败不再无限自动重试」（准确原因 → 准确处置，符合 Q2 目的）；②Q1 入口形态按任务卡首选（眉标可点 + `›`），显式动作键留全检反馈定。已推送 CI；**下一批：Q5（Pad 5）→ Q6（K60）**。 | 
| 2026-10-07 | **W76-Q5 完成（分支 `fix/w76-q5-stopped-report`，commit `08c0814`，未 push；worktree `w76h`；Pad 5 `43af8627` 独占；07:04–07:14）**：新增纯判定 `shouldReportStopOnPlayerExit(currentItemIsMusic) = !currentItemIsMusic`（口径同 `musicSessionActionOnVideoStart`，认 `MUSIC_MEDIA_EXTRA` 标志而非 `musicSessionActive`），`PlayerViewModel.releasePlayer()` 的 Stopped 上报加 gate → 只对「本页自己启动 / 播放的视频会话」补发；其余上报点、协调器结构、上报协议、B10 判定链路、下载域**零改动**（3 文件 +60/−1）。门禁 = `:player:local:ktfmtCheck` ✅ / `:player:local:testDebugUnitTest` **171 / 0 / 0**（基线 169，+2）/ `:app:phone:assembleDebug` ✅。Pad 5 双向取证：①音乐后台 `PLAYING(3)` → `am start …/PlayerActivity`（无 itemId = attach 路径，两轮）→ BACK 退出：logcat 仅 `Clearing Player ViewModel`、**无** `PlayerViewModel$releasePlayer: Sending playback stop`；音乐 `PLAYING(3)`（position 继续递增）+ `CinefinPlaybackService` 仍在（W68 隔离不回归）——attach 后播放页 uiState `currentItemTitle=我曾爱过一个人 (笛子版) / currentItemId=1b3500b3-…` 即退出瞬间实例上确是音乐条目；②对照本页自启视频（首页继续观看 → 冰海战记 S1·E17 HEVC）→ `initializePlayer: itemId=cd9457e9-…` + `视频起播：已收掉音乐会话（STOP_MUSIC_AND_CLEAR_FLAG）` → BACK 退出 **`Sending playback stop` 照常命中**（正确上报未误删）；③crash buffer 0 行、无 FATAL / ANR。**申报**：gate 落地为「当前媒体项不是音乐条目」——视频页 attach 后台**视频**会话退出仍按既有行为上报（未改）；「本页起播视频 → 音乐抢占实例 → 退出」同因同治不再误报。**还原**：`am force-stop`、`/sdcard/q5_ui.xml` 与 `%TEMP%\q5_ui.xml` 已删（`/sdcard/q5.xml` = 2026-09-30 旧包名遗留，非本会话产物，未删）、播放回退档位偏好（stage/media_id/guard/session）hand-edit 还原为测前值 + 冷启复核、旋转与其它偏好未改、release 包与 K60 零接触。**副作用（不可回滚）**：冰海战记 S1·E17「辅佐之人」观看进度被改写（退出时补发 Stopped 落库）；debug 包升级为本分支形态。报告 `w76-reports/W76-Q5.md`。**下一步：Q6（K60，待 Q5 合并后串行）→ 终门禁 → 候选 release → V1/V2 回归**。 |
| 2026-10-07 | **W76-Q6 合并 + 终门禁（负责人）**：Q6 ff 合并 master（`4f0f820` → `61d13a8`）；**终合并态门禁** = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **941 项 / 0 失败 0 错误**（app 273 / core 104 / data 68 / player:core 12 / player:local 178 / film 53 / book 113 / music 140；基线 920 + Q1 1 + Q2/Q7 11 + Q5 2 + Q6 7；日志 `w76-evidence/gate_merged_final.log`）。 | 
| 2026-10-07 | **Q 波候选 release 构建 + V1/V2 派发（负责人）**：`assembleLibreRelease "-Paurorama.universalApk=true"` 成功（master `61d13a8`，日志 `w76-evidence/release_build_candidate.log`）——候选 universal **171,141,668 B（163.2 MiB）`ef55c0af…b35fe26a`** / arm64-v8a **77,559,690 B（74.0 MiB）`c18815c2…6518f876`**；`apksigner verify` v2 通过、证书 `e449c4aa…e155ff` 一致；副本 `w76-evidence/candidate/Aurorama-1.1.0-candidate-*.apk`。**V1（K60）/ V2（Pad 5）已派发**（回归对象 = 本候选包）；正式重建签名 + 哈希落档待回归通过后执行。 | 
| 2026-10-07 | **W76-V1 发布包复验完成（K60，10:48–11:47，候选包 `ef55c0af…`，0 FATAL·ANR）**：①覆盖安装 ✅（免重登、设置保留）②**B9 严格首下 ✅**（`pm clear` + Quick Connect 重建 → 视频 / 音乐 / 书籍真实进度、无「失败 / 0 B」）③B11b ✅（不再空载、直接出画；加载中 BACK ×2 无 Toast；季 / 集不回归；**首帧口径裁减**：release 无 Timber，屏幕采样实测 6.0–6.6 s @ 当日网络 0.5–1.2 MB/s，未复现 2–5 s，判为环境带宽成本）④B10 切内核 ✅（1.7 s 生效、无错误卡片、退出音乐隔离）⑤Q7 ✅（6 次删-重入队全部立即启动；2 次误触「暂停」已排除）⑥播放复跑 ✅（直连 + 转码、队列 E01–E12 完整、NEXT 顺序）⑦Q1 ✅（眉标 → 季页）⑧稳定性 ✅。**观察项 = Q8/Q9（§6.1）**；副作用：`pm clear` 清数据（Quick Connect 已重建）/ 通知权限复位「拒绝」/ 进度写入。报告 `W76-V1.md`。 | 
| 2026-10-07 | **W76-V2 阅读组补测完成（Pad 5，候选包，0 FATAL·ANR）**：在线书架 ✅（8 本）/ EPUB·PDF·CBZ ✅ / 进度保存恢复 ✅（服务端已回传）/ 阅读设置保持 ✅（冷启仍保持）/ 离线下载 + 飞行模式 ✅（已下载 3 本）/「已完成」列表 ✅（3 条，R2 时为 0）/ 稳定性 ✅——**R2 阅读组缺口闭环**。**关键前置**：books 库只对 `zhangwenkang` 账号可见（`admin` 无）→ 全检看书架需先切账号。**观察项 = Q10（§6.1）+ 报告 O1/O5**；副作用：`zhangwenkang` 3 本书进度改写（不可回滚）；测试下载 875 MB 已删。报告 `W76-V2.md`。 | 
| 2026-10-07 | **v1.1.0 已发布（负责人）**：用户全检通过（2026-10-07）→ 按 `RELEASE_PLAN` §5 打 tag **`v1.1.0`** + GitHub Release「极光幕 / Aurorama v1.1.0」+ 上传双 APK（universal `38b5992f…436c3f29` / arm64-v8a `310ca8fd…68d0012c`，与 §7③ 一致）；发布后自检 ✅。**W76 收口闭环**；下一波 = **W77 阅读流式**（D-F11；spike 已完成——EPUB ✅ / CBZ ✅（难度下调）/ PDF ❌ 不可行（PdfBox parse 全量物化），任务拆分见 `w77-reports/W77-R1-spike.md`）。 | 
| 2026-10-07 | **W77-1 EPUB 远程流式首开完成（未合并）**：分支 `feature/w77-epub-stream`（worktree `w77b`）commit `c8e8ce3`（基座 `cfb220f`，10 文件 +1270/−57，**未 push**）；报告 `w77-reports/W77-1.md`；`READER_PLAN` §9 + 变更日志已随分支回写。门禁 = `:modes:book:ktfmtCheck` / 根 `ktfmtCheck` / `assembleDebug` 全绿、`:modes:book:testDebugUnitTest` **113 → 124**（+11）。K60 真机（229 MB EPUB）：首开远端就绪 **29.9–46.3 s / 3–5 次 Range / ≈2.7–4.7 MB**（同书整本下载 ≈108 s）；热切换 1262 ms 同页同位置；返回即中止在途 + `.part` 4 s 清理；失败 / 离线回退整本下载；0 FATAL·ANR。关键实现：自建 OkHttp 版 Readium `HttpClient`（TOFU + `X-Emby-Token`）；**Jellyfin Download 端点 HEAD = 405** → `GET Range: bytes=0-0` + `Content-Range` 合成长度；自建 `PreciseHttpResource`（有界 Range + 读完即关 + 1 MB×12 块 LRU）；`InFlightRequestRegistry` 取消兜底。**待第九任合并**（rebase 预期 `READER_PLAN` 冲突）。 | 
| 2026-10-07 | **W77-PDF-R PDF 引擎调研完成（D-F12）**：报告 `w77-reports/W77-PDF-engine-research.md`（桌面研究 + 产物级 API 验证；未占真机、未改生产）。**推荐 PDFium**——`android.graphics.pdf.PdfRenderer` 本身即 native pdfium，「换引擎」实为换字节供给（fd → HTTP Range）→ 渲染保真风险低、无需自写 JNI；`io.legere:pdfiumandroid:2.0.3` 暴露 `PdfiumSource`（即 `FPDF_LoadCustomDocument`），可直接接 `HttpByteSource`。许可：PDFium = **BSD-3-Clause**（修正此前「Apache-2.0」写法）与 GPL-3.0 干净兼容；MuPDF = AGPL（唯一法务决策点）。成本：Phase 1 ≈ +5 MB/ABI；Phase 2 迁文本层并去 PdfBox 可净 −8 MB；真机 spike 9 项（≈3 人日，建议 x86_64 AVD）。**6 项决策点待用户拍板**（报告「决策点」节）。 | 
| 2026-10-07 | **W77-1 合并验收（第九任负责人）**：分支 rebase（`c8e8ce3` → `0683e86`，基座 master `11c5f96`；`READER_PLAN` §9 冲突解决 = 保两侧（W77-1 小节 + PDF 调研条目），W77-1 在前）→ ff 合并 master（`11c5f96` → `0683e86`）；合并态门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **952 项 / 0 失败 0 错误**（app 273 / core 104 / data 68 / player:core 12 / player:local 178 / film 53 / book 124 / music 140；基线 941 + W77-1 11；日志 `w77-evidence/gate_merged_w77_1_build.log` / `gate_merged_w77_1_tests.log`）；已推送 CI（`11c5f96..0683e86`）。 |
| 2026-10-07 | **PDF 6 决策点用户拍板（D-F13）+ Q8/Q9/Q10 处置（第九任负责人）**：①PDFium 路线全收（第 1/3/4/5/6 条按推荐）；②**第 2 条修正**——PDF 首开「先出第 1 页」保留，但**不做后台整本下载**，只按需 Range 预取「当前页 + 后面几页」（用户：浪费流量）；③Q9 关闭（竖屏省略「码率 / 解码」键 = 预期设计，避免画面拥挤）；④Q8 按推荐单开复核（debug / release 对照 attach 入口）、Q10 留 1.2 评估。→ 已同步提示 W77-2 会话「后台整本下载 / 热切换部分暂缓深入」；**EPUB/CBZ 是否统一为同一新口径（不自动整本下载）待用户确认**。 |
| 2026-10-07 | **口径统一拍板（D-F14）+ W77-2 中途口径修正**：用户回「统一」——EPUB / CBZ / PDF 均**不做自动整本下载**，远端只按需预取「当前 + 后几页」；手动「下载整本」语义保留、失败回退保留。已下达 W77-2（真机验收前先改掉 CBZ 自动下载触发、加「静默期无整本流量」证据、重跑门禁）；W77-1（EPUB）的自动下载移除并入 **W77-3** 范围；PDF 按 D-F13。 |
| 2026-10-07 | **W77-2 合并验收（第九任负责人）**：分支 rebase（`2575b97` → `dd12bfc`，基座 `56e5571`；`READER_PLAN` §9 冲突解决 = 保两侧（W77-2 小节在前、口径统一段在后））→ ff 合并 master（`56e5571` → `dd12bfc`）；合并态门禁 = 根 `assembleDebug`（含 TV）+ `ktfmtCheck` 全绿、8 任务 `--rerun-tasks` **965 项 / 0 失败 0 错误**（app 273 / core 104 / data 68 / player:core 12 / player:local 178 / film 53 / book 137 / music 140；基线 952 + W77-2 13；日志 `w77-evidence/gate_merged_w77_2_*.log`）；已推送 CI（`56e5571..dd12bfc`）。 |

## 6. 候选缺陷 backlog（负责人审视 · 待用户决定是否纳入）

### 6.1 待用户决定（2026-10-07 记录 · 不阻塞）

> **决议（2026-10-07 用户拍板，D-F10）——Q1–Q7 已全部闭环**：Q1–Q7 **全部修进 1.1.0**（不再延后）；**Q3 = 书籍库永久保留**（V2 补测 / 全检照常安排）；**Q4 按建议**（B11 主因合并定级 P2；对外文案简化）；**回归暂停令解除**——执行序 = Q 波修复（§3）→ 终门禁 → 候选 release → V1/V2 回归（+ 播放轻量复跑）→ **回归通过后**正式重建签名 / 哈希落档 → 用户全检 → tag `v1.1.0`。下表「建议」列保留为决策前记录；**Q8–Q10 = V1/V2 回归新观察项（2026-10-07 记录，不阻塞发布，待用户决定）**。

| # | 事项 | 背景 | 建议 |
|---|---|---|---|
| Q1 | `EpisodeAction.NavigateToSeason` 自 W66 起无发射点（剧集页无法回季 / 剧集页）——是否单开需求 | W76-R1b §7.2 顺带发现 | 单开 1.2 波需求；本波不动 |
| Q2 | 下载失败原因落 DB / UI（release 无 Timber 时用户只见「失败 / 0 B」） | W76-R1b §2 建议 | 随 B9 修复做最简留痕；超范围则 1.2 波评估 |
| Q3 | 测试服务器书籍库（`/medi/书籍`）是否长期保留 | 2026-10-07 用户已建立，本波阅读组补测依赖 | 若为临时库，需在回收前完成补测并同步 R2 报告 |
| Q4 | B11 主因（系列级起播等待整剧枚举 8–32 s）的定级与对外表述 | W76-B11.md；与 R1c「必须重启才能看」是两件事 | 本波按缺陷修（W76-B11b，负责人执行方案B）；Release notes 按修复后实测口径写 |
| Q5 | B10 顺带观察（既有、非本波引入）：视频页 attach 到音乐会话后退出，`releasePlayer()` 对音乐条目补发一次 `Sessions/Playing/Stopped` | W76-B10.md 遗留③ | 建议单开 P3 / 并入 1.2 波；本波不动 |
| Q6 | 后台补队列为整剧每集请求 `PlaybackInfo`（含转码会话）——补队列既有代价（与非本波无关） | W76-B11b.md；B11b 为保语义未改 | 1.2 波评估懒请求 / 去重；本波不动 |
| Q7 | 下载任务删除后再次入队偶发不立即启动（疑似 WorkManager）——未复现、未定性 | W76-B9.md 遗留 3 | 留观；复现再立项 |
| Q8 | release 上 attach 播放页入口（`OPEN_MUSIC_NOW_PLAYING` / mini-player 展开）未打开播放页（V1 疑似观察项；debug 侧未经该入口） | W76-V1 §十一；与 Q5 同域 | 单开复核：debug / release 对照同一入口，定性后决定是否立项；本波不动（**2026-10-07 用户按推荐放行：单开复核，排后续**） |
| Q9 | 手机竖屏窄窗（<600dp）按 `playerBottomKeysForWidth` 省略「码率 / 解码」键——竖屏无法进解码面板（需横屏 / 宽窗） | W76-V1 §四取证注 | **2026-10-07 用户拍板：预期设计（避免画面拥挤）→ 关闭** |
| Q10 | 阅读组观察三则：①设置面板无「亮度」项（#19 阅读侧口径待确认）②阅读器无流式预览（126 MB PDF 首开 6–8 min）③阅读设置存**全包全局** SharedPreferences（非按用户） | W76-V2 §二 O2/O3/O4 | 均不阻塞 1.1.0；1.2 波评估（②如改需流式加载，单独立项）——**2026-10-07 用户按推荐：1.2 评估；②方向已在 W77 波推进** |
| Q11 | **阅读流式 / 按需加载**（大书首开慢：整本下载完才出页；用户 2026-10-07 提出——「先加载显示需要的页，然后再慢慢根据阅读进度加载」） | 诊断与分格式方案见 `READER_PLAN` §9「2026-10-07」条；瓶颈 = 整本下载等待（非解析 / 渲染） | **已拍板 A（D-F11）**：1.1.0 已发布（2026-10-07）；spike **已交付**（EPUB ✅ / CBZ ✅ / PDF ❌）；W77-1 **已合并（`0683e86`）**、W77-2 施工中；**PDF 快速首开 → 换引擎路线（D-F12 / D-F13 已拍板，待排实施波）** |

- **B1**（**已并入 W73-S2，命中**）视频库过滤器只认 `Movies` / `TvShows`（`pickVideoLibraries`）——混合内容 / 家庭视频库不进视频页「库选择」与聚合。
- **B2**（**已随 W73-S2 修复**）媒体页库列表仅在首进加载一次（无 TTL / 无刷新）——改为每次 RESUMED 重取（仓库元数据缓存 TTL 10 分钟去重），侧栏同步在导航变化时只读刷新。
- **B3** 元数据缓存 30s 冷却 + TTL 可能让「已看 / 进度」短时滞后——与 #4 验证时一并观察。
- **B4** `PlayerControlOverlay.kt`（约 2.4k 行）/ `NavigationRoot.kt`（约 2.2k 行）维护性差——建议 1.2.x 单独开重构波，本轮不做。
- **B5**（**已随 W76 收口执行**：W76-R1 K60 / W76-R2 Pad 5 覆盖，见报告 `w76-reports/`）W71 / W72 发布面未覆盖回归（release 真实下载任务 / SAF 重建 / mpv 兜底 / Quick Connect）。
- **B6** 冷启动中位 ~1394 ms（W61 记录 `D-W47-2`，未拍板）——待定。
- **B7** mpv 内核下 `dumpsys media_session` 的 `speed` 恒 `0.0`、`position` / `active item id` 恒 `-1`（App 面板读数正常）——既有问题（S74-2 用未改动 master 同机对照复现，非本轮引入），建议单开一条。
- **B8** debug 日志把转码 `Stream url` 连同 `ApiKey` 一起打进 logcat（S74-2 顺带发现，非本轮引入）——**已随 W76-F1 修复**（`redactUrlSecrets`，`0266353`）。
- **B9**（**W76-R1b 定位，建议列为 1.1.0 阻断项 —— 待用户拍板**）下载任务首次必失败——`DownloaderImpl.kt:425` 的存储预检查 `StatFs(target.parentFile?.path ?: filesDir.path)` 排在 `DownloadHttpEngine.kt:92` 的 `mkdirs()` **之前**；`…/files/downloads` 不存在时抛 `IllegalArgumentException("Invalid path")` → 被 `executeTask` 通用 catch 收成 `DownloadFailureReason.UNKNOWN`（UI 只显「失败 / 0 B / 无总大小」）。既有缺陷：W50 `8f07ee8`（2026-10-03）起，1.0.0 同结构；**非 1.1.0 引入**。修复 = StatFs 前先 `target.parentFile?.mkdirs()`（或路径不存在时回落 `filesDir`）+ 补「首次下载·目标目录不存在」单测；建议 release 下把失败原因落 DB / UI（无 Timber）。
- **B10**（= **D1，P2 已确认**，非本轮引入）`PlayerHolder.musicSessionActive` 粘住 → 同进程内「换内核 / 回退链」全失效（`PlaybackCoordinatorImpl.onVideoStartRequested()` 全仓库无调用点；`PlayerHolder.kt:93/186`）。决定性命中见 W76-R1b §1.2 / §7.1；**R1c** 拿到明确用户可见面（横屏「播放失败」卡片 + 「改用 mpv 内核」按钮在粘住态失效 → 卡 `00:00 / 00:00`），建议单独立项。
- **B11**（= **D2，P3 已归类**，非本轮引入）系列详情页「播放」进空载播放页——`ShowScreen` 传 series id、`PlaylistManager` SERIES 分支 `return null` **静默兜底无提示**；顺带 `EpisodeAction.NavigateToSeason` 自 W66 起无发射点（剧集页无法回季/剧集页）。建议单独立项（**R1c 已真机复现**，见 W76-R1b §7.2：系列级「播放」空载、季/集级正常出画）。
