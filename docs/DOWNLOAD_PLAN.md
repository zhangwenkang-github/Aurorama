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
| D2 | **暂停 = remove 系统任务**（进度不保留）；**恢复 = 安全重试**（重新入队，从 0 开始） | DownloadManager 没有公开 pause/resume API（已用 `javap android.jar` 核实）。K60 真机实测：`remove` 会删除 `.download` 残片，暂停后无法续传；真正的断点续传场景是「进程被杀 / 断网等待网络」由系统 Resume（D1）。原「保留残片」描述已按实测修正 |
| D3 | **状态持久化到 `sources` 表**：`taskStatus` / `failureReason` / `updatedAt`，Room v8 → v9（AutoMigration） | 暂停状态必须跨进程保留；旧数据（三列 NULL）按路径推断：`.download` = 失败残片，否则 = 已完成 |
| D4 | **失败任务不再删除记录**：`DownloadReceiver` / `DownloaderImpl` 失败时写 FAILED + 原因 | 失败任务要可在 UI 重试；旧实现失败即 `deleteItem`，用户看不到失败项 |
| D5 | **网络恢复自动重试 = CONNECTED 约束的 WorkManager 唯一任务**；只重试网络 / 服务器类失败，每个任务每个进程一次 | 断网时不空转；进程重启后重新获得一次机会；避免页面轮询造成无限重试 |
| D6 | **状态判定 / 恢复策略抽 `DownloadTaskRules` 纯函数** | 队列状态机与续传判定可单测（无 Android 运行时依赖），符合本波门禁要求 |
| D7 | 已完成列表沿用 `getDownloads()`（movies + shows）；仅有进行中 / 失败 source 的电影不重复出现在「已完成」 | 剧集容器（show，无 LOCAL source）保留为入口；其删除仍走详情页 |
| D8 | 暂停 / 重试 / 删除统一清理 DownloadManager sidecar（`.<目标名>.js`） | K60 实测 `remove` 后隐藏 sidecar 残留（约 50 kB/任务），存储占用清不干净；修复 `5341027`，真机复验删除后目录归零 |

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
- [x] 门禁：`assembleDebug`（含 TV）+ `ktfmtCheck` + `:app:phone:testLibreDebugUnitTest`（58 项全绿；新增 `DownloadTaskRulesTest` 7 项）
- [x] **K60 真机验收（2026-10-02，`8e875894`）**：断网续传 / 进程被杀续传 / 批量暂停删除 / 完成与删除 / 存储占用全部通过，并修复 sidecar 残留（`5341027`）；逐条结论与未触发项见 §6 / §7

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

## 8. 日志

| 日期 | 会话 | 内容 |
|------|------|------|
| 2026-10-02 | W32-DOWNLOAD | 建线；实现管理 UI + 任务韧性 + 单测；门禁 `assembleDebug`（含 TV）/ `ktfmtCheck` / app 单测 58 项全绿；提交 `138b189`(feat) + `4053cb5`(docs) 已推送未合并；设备待调度，真机清单见 §6 |
| 2026-10-02 | W32-DOWNLOAD | **K60 真机验收（8e875894）**：断网续传 / force-stop 续传 / 批量暂停删除 / 完成与删除 / 存储占用 / 0 FATAL-ANR 通过；暂停路径实测不续传（D2 修正）、修复 sidecar 残留 `5341027`（复验通过）；未触发：FAILED 自动重试、空间不足、reboot；设备已还原；修复提交已推送未合并 master |
