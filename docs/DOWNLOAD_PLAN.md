# Cinefin · 下载 / 离线任务线（DOWNLOAD_PLAN）

> **本文件是下载 / 离线线的唯一权威文档**：需求、决策、进度、验收、踩坑都写在这里，不新建零散 `.md`。
> 维护会话：W32-DOWNLOAD（分支 `feature/w32-download-manager`，基线 master `e465a83`）
> 最后更新：2026-10-02

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

## 3. 决策记录

| 编号 | 决策 | 理由 / 后果 |
|------|------|-------------|
| D1 | **保留系统 DownloadManager 作为唯一下载引擎**，不换自研 Range 下载器 | 进程被杀 / 重启 / 换网络时任务由系统服务托管，天然续传；本波无法真机验证，换引擎风险过高。外部流 / trickplay 下载链路保持原样 |
| D2 | **暂停 = remove 系统任务 + 保留残片**；**恢复 = 安全重试**（删残片后重新入队） | DownloadManager 没有公开 pause/resume API（已用 `javap android.jar` 核实）。真正续传场景发生在「进程被杀」路径（D1）；暂停后恢复的断点续传真机待验证，若系统不支持则按安全重试从 0 开始（如实展示） |
| D3 | **状态持久化到 `sources` 表**：`taskStatus` / `failureReason` / `updatedAt`，Room v8 → v9（AutoMigration） | 暂停状态必须跨进程保留；旧数据（三列 NULL）按路径推断：`.download` = 失败残片，否则 = 已完成 |
| D4 | **失败任务不再删除记录**：`DownloadReceiver` / `DownloaderImpl` 失败时写 FAILED + 原因 | 失败任务要可在 UI 重试；旧实现失败即 `deleteItem`，用户看不到失败项 |
| D5 | **网络恢复自动重试 = CONNECTED 约束的 WorkManager 唯一任务**；只重试网络 / 服务器类失败，每个任务每个进程一次 | 断网时不空转；进程重启后重新获得一次机会；避免页面轮询造成无限重试 |
| D6 | **状态判定 / 恢复策略抽 `DownloadTaskRules` 纯函数** | 队列状态机与续传判定可单测（无 Android 运行时依赖），符合本波门禁要求 |
| D7 | 已完成列表沿用 `getDownloads()`（movies + shows）；仅有进行中 / 失败 source 的电影不重复出现在「已完成」 | 剧集容器（show，无 LOCAL source）保留为入口；其删除仍走详情页 |

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

## 5. 进度

- [x] sources 状态列 + Room v9 AutoMigration + DAO
- [x] 任务模型 / 状态机 / 恢复策略纯函数 + 单测（7 项）
- [x] 对账（完成补重命名 / 失败归档 / 进度刷新）
- [x] 暂停 / 恢复 / 重试 / 删除（单个 + 批量）
- [x] 存储占用显示（下载目录占用 + 可用空间）
- [x] 网络恢复自动重试 worker（CONNECTED + 每进程一次）
- [x] 下载管理 UI（进行中 / 已完成 / 失败 + 多选）
- [x] 门禁：`assembleDebug` + `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`
- [ ] **真机验证（设备待负责人调度）**：见 §6

## 6. 验收清单（真机）

设备纪律：W32 登记为「待调度」（Pad 5 曾归 W29、K60 归 W30），**不自行占用设备**。窗口指派后按下表走查：

1. 详情页发起下载 → 下载页「进行中」出现任务，进度 / 大小刷新，可暂停；恢复后重新下载（安全重试）到完成。
2. 断网：任务进入「等待网络」（系统暂停）→ 网络恢复 → 自动继续，无需操作。
3. 网络类失败（注入不可达 / 服务器 5xx）→ 「失败」分组显示「网络错误 / 服务器错误」→ 手工重试可恢复；网络恢复后 worker 自动重试一次。
4. 空间不足场景（可用空间 < 文件）→ 显示「存储空间不足」，不自动重试。
5. 下载中 `adb shell am force-stop` / 杀进程 → 重开 App：任务仍在「进行中」且进度继续（系统托管）；完成后自动进入「已完成」。
6. 下载中重启（`adb shell reboot`）→ 同上。
7. 已完成：点击可进入详情 / 播放；删除后条目与文件消失；批量选择（暂停 / 恢复 / 重试 / 删除）逐项生效；剧集容器不可在下载页删除。
8. 存储占用行数值与实际 `downloads/` 目录大小一致；`adb logcat` 无 FATAL / ANR。

## 7. 踩坑与遗留

- **DownloadManager 没有公开 pause/resume**：官方 API 只有 enqueue / remove / query（`javap` 核实）；暂停必须 remove，恢复只能重新入队，「断点续传 or 安全重试」在暂停路径当前取安全重试。
- **恢复的续传能力待真机确认**：若服务器 / 系统在重新入队时支持复用残片，可在后续波次改为「保留残片 + 重新入队」；当前先保证成功。
- **失败任务的 DownloadManager 记录**：失败后系统记录仍在（可查询 reason）；删除任务时 `remove` 清掉，避免残留。
- **旧数据判定**：v9 迁移后旧 source 的 `taskStatus` 为 NULL，首次进入下载页按路径自动补写状态（`.download` → FAILED，其余 → COMPLETED）。
- **未做**：本地文件播放（后续波）；下载限速 / 并发队列调整；已完成列表的封面图（当前为文本行卡片）。

## 8. 日志

| 日期 | 会话 | 内容 |
|------|------|------|
| 2026-10-02 | W32-DOWNLOAD | 建线；实现管理 UI + 任务韧性 + 单测；门禁结果见交接报告；设备待调度，真机清单见 §6 |
