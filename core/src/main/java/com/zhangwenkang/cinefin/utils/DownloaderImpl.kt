package com.zhangwenkang.cinefin.utils

import android.app.DownloadManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidSources
import com.zhangwenkang.cinefin.models.FindroidTrickplayInfo
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.models.toFindroidEpisode
import com.zhangwenkang.cinefin.models.toFindroidEpisodeDto
import com.zhangwenkang.cinefin.models.toFindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.toFindroidMovie
import com.zhangwenkang.cinefin.models.toFindroidMovieDto
import com.zhangwenkang.cinefin.models.toFindroidSeasonDto
import com.zhangwenkang.cinefin.models.toFindroidSegmentsDto
import com.zhangwenkang.cinefin.models.toFindroidShowDto
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.models.toFindroidTrickplayInfoDto
import com.zhangwenkang.cinefin.models.toFindroidUserDataDto
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.work.DownloadEngineWorker
import com.zhangwenkang.cinefin.work.ImagesDownloaderWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

/**
 * W50 自研下载引擎门面。
 *
 * 与旧 DownloadManager 实现的差异：
 * - 传输层：OkHttp + HTTP Range 真断点续传（暂停保留残片、恢复从残片继续、服务器忽略 Range 时安全重下）；
 * - 前台服务：由 [DownloadEngineWorker]（WorkManager 长时 worker）通过 `setForeground` 托管，规避 Android 14+
 *   后台启动前台服务限制，并复用 WorkManager 的重启 / 网络约束持久化；
 * - 队列：Room `sources` 为单一数据源，进程内并发数读偏好 `pref_download_concurrency`（1–8，默认 2）；
 * - 限速：每次任务启动时读取偏好 `pref_download_speed_limit_mbps`（0–100 MB/s，0 = 不限速），运行中的任务不打断；
 * - 失败：分类 + 指数退避自动重试（网络类无限、服务器类限次、空间 / 鉴权不自动）；
 * - 兼容：旧 DownloadManager 进行中任务（engineVersion = 0）不做无缝接管，首次启动标记「需重下」； 已完成的完整文件继续按路径识别。
 */
class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
    httpClient: OkHttpClient,
) : Downloader {
    private val httpEngine = DownloadHttpEngine(httpClient)
    private val notifications = DownloadNotifications(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)
    private val sidecar = DownloadMediaSidecar(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** W63：批量入队的节目 / 季快照缓存（同剧 N 集只解析一次，避免逐集两次网络往返）。 */
    private val showSnapshotCache = ConcurrentHashMap<UUID, CachedSnapshot<FindroidShow>>()
    private val seasonSnapshotCache = ConcurrentHashMap<UUID, CachedSnapshot<FindroidSeason>>()

    /** W63：队列活动集变化信号（入队 / 完成 / 失败 / 删除），侧栏角标即时刷新。 */
    private val _queueChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val queueChanges: SharedFlow<Unit> = _queueChanges.asSharedFlow()

    /** W63：队列活动集变化（尽力投递；无收集者时不阻塞）。 */
    private fun notifyQueueChanged() {
        _queueChanges.tryEmit(Unit)
    }

    /** sourceId → 运行中的下载协程。 */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /**
     * W76-Q7：入队方与运行中 worker 之间的「退出交接」状态（保护 [queueKickRequested] / [engineWorkerQuiesced]）。
     *
     * 见 [runQueue] 尾部与 [kickRunningEngineWorker]：两侧都在同一把锁内做决定，消除「新任务落库在 worker
     * 判定队列为空与退出之间」导致的静默漏调度。
     */
    private val queueKickLock = Any()

    /** 入队方请求运行中的 worker 退出前再确认一次队列。 */
    private var queueKickRequested = false

    /** 运行中的 worker 已判定队列为空、正准备退出（此刻起它不会再领取新任务）。 */
    private var engineWorkerQuiesced = false

    /** sourceId → 内存运行态（速度 / ETA / 最新字节数）。 */
    private val runtime = MutableStateFlow<Map<String, TaskRuntime>>(emptyMap())

    /** 同时下载数（引擎并发上限；读「设置 → 下载 → 同时下载数」偏好）。 */
    private val maxConcurrentTasks: Int
        get() =
            DownloadTaskRules.coerceConcurrency(
                appPreferences.getValue(appPreferences.downloadConcurrency)
            )

    @Volatile private var lastNotificationAt = 0L

    /**
     * 网络恢复回调（W50）：把「网络类失败 + 退避等待」的任务立即唤醒。
     *
     * 退避封顶 30 分钟，但网络恢复属于用户可感知的明确事件：进程存活时不等退避，直接清退避 + 强制调度； 进程已死时仍由 WorkManager 的 CONNECTED 延迟任务兜底。
     */
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { wakeNetworkBlockedTasks() }
            }
        }

    init {
        runCatching {
            context
                .getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(networkCallback)
        }
            .onFailure { Timber.w(it, "注册下载网络回调失败") }
    }

    // ---------------------------------------------------------------------------------------------
    // 入队 / 调度
    // ---------------------------------------------------------------------------------------------

    override suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
    ): Pair<Long, UiText?> = downloadItem(item, sourceId, storageIndex, null, null, 0)

    override suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
        albumName: String?,
        artist: String?,
        trackIndex: Int,
    ): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) {
            val handle = downloadTaskHandle(sourceId)
            val storageLocation = context.getExternalFilesDirs(null).getOrNull(storageIndex)
            if (
                storageLocation == null ||
                    Environment.getExternalStorageState(storageLocation) !=
                        Environment.MEDIA_MOUNTED
            ) {
                return@withContext Pair(
                    -1L,
                    UiText.StringResource(CoreR.string.storage_unavailable),
                )
            }

            val existing = database.getSource(sourceId)
            val existingStatus = existing?.taskStatus.toTaskStatus()
            if (
                existingStatus == DownloadTaskStatus.PENDING ||
                    existingStatus == DownloadTaskStatus.RUNNING ||
                    existingStatus == DownloadTaskStatus.PAUSED
            ) {
                // 幂等：同一来源已在队列 / 下载中 / 暂停，不重置进度。
                ensureEngineRunning(forceStart = true)
                return@withContext Pair(existing?.downloadId ?: handle, null)
            }

            val partialPath = File(storageLocation, "downloads/${item.id}.$sourceId.download").path
            val partial = File(partialPath)
            val existingBytes = if (partial.isFile) partial.length() else 0L

            // W51b 竞态修复：条目快照必须先于队列行落库。
            //
            // 批量入队（整剧 / 全季）时引擎往往已在运行：若先插入 PENDING 队列行，再写快照（剧集要拉
            // show / season，两次网络往返），引擎会在快照落库前抢到该行 → findItem 读不到条目 →
            // FILE_ERROR「任务对应的媒体条目缺失」。真机批量入队 12 集时 11 集命中该竞态。
            runCatching { persistItemSnapshot(item) }
                .onFailure { Timber.w(it, "写入下载条目快照失败 ${item.id}") }

            val sourceDto =
                FindroidSourceDto(
                    id = sourceId,
                    itemId = item.id,
                    name = item.name,
                    type = FindroidSourceType.LOCAL,
                    path = partialPath,
                    downloadId = handle,
                    taskStatus = DownloadTaskStatus.PENDING.name,
                    failureReason = null,
                    updatedAt = System.currentTimeMillis(),
                    downloadedBytes = existingBytes,
                    retryCount = 0,
                    nextRetryAt = 0L,
                    engineVersion = ENGINE_VERSION,
                    allowOffline = existing?.allowOffline ?: true,
                )
            database.insertSource(sourceDto)
            notifyQueueChanged()

            // W34：音乐曲目（专辑 / 艺人）写入侧车，供下载列表层级化与离线展示。
            if (albumName != null) {
                sidecar.put(
                    DownloadMediaRecord(
                        itemId = item.id.toString(),
                        kind = DownloadMediaKind.MUSIC,
                        albumName = albumName,
                        artist = artist,
                        trackIndex = trackIndex,
                    )
                )
            }

            ensureEngineRunning(forceStart = true)
            Pair(handle, null)
        }

    /**
     * W63：批量入队。同剧的节目 / 季快照在批内只解析一次（[persistItemSnapshot] 的缓存）， 逐条入队逻辑沿用 [downloadItem]（幂等命中活动任务 /
     * 已下载时不重复入队）。
     */
    override suspend fun enqueueItems(
        items: List<FindroidItem>,
        storageIndex: Int,
    ): DownloadBatchResult =
        withContext(Dispatchers.IO) {
            val added = mutableListOf<UUID>()
            var lastError: UiText? = null
            for (item in items) {
                coroutineContext.ensureActive()
                val sourceId = item.sources.firstOrNull()?.id ?: continue
                val result = runCatching {
                    downloadItem(item, sourceId, storageIndex)
                }
                    .onFailure { Timber.w(it, "批量入队失败 ${item.id}") }
                    .getOrNull()
                if (result == null || result.first == -1L) {
                    lastError = result?.second ?: lastError
                    continue
                }
                added += item.id
            }
            DownloadBatchResult(addedIds = added.distinct(), lastError = lastError)
        }

    override suspend fun runQueue(): DownloadQueueOutcome {
        // W76-Q7：新一轮 worker 开始 → 复位调度交接状态（上一轮遗留的 kick / 静默标记作废）。
        synchronized(queueKickLock) {
            engineWorkerQuiesced = false
            queueKickRequested = false
        }
        var ran = 0
        while (true) {
            while (true) {
                coroutineContext.ensureActive()
                val slots = maxConcurrentTasks - activeJobs.size
                val claimed = claimRunnableTasks(slots)
                if (claimed.isEmpty()) break
                supervisorScope {
                    claimed.map { source -> async { executeTask(source) } }.forEach { it.await() }
                }
                ran += claimed.size
            }
            val pendingSources =
                withContext(Dispatchers.IO) {
                    database.getPendingSources().filter { source ->
                        val status = source.taskStatus.toTaskStatus()
                        source.engineVersion >= ENGINE_VERSION &&
                            (status == DownloadTaskStatus.PENDING ||
                                (status == DownloadTaskStatus.RUNNING &&
                                    !activeJobs.containsKey(source.id)))
                    }
                }
            if (pendingSources.isNotEmpty()) {
                // 任务级指数退避：按最近一个 nextRetryAt 安排下一次唤醒（带 CONNECTED 约束）。
                val now = System.currentTimeMillis()
                val nextWakeAt = pendingSources.minOf { it.nextRetryAt }
                enqueueEngineWork(delayMs = (nextWakeAt - now).coerceAtLeast(0L))
                return DownloadQueueOutcome(
                    ranTasks = ran,
                    hasPendingTasks = true,
                    shouldRetry = true,
                )
            }
            /*
             * W76-Q7：退出前的原子交接。竞态——新任务落库 PENDING 恰好发生在「claim 空」与「worker 真正结束」
             * 之间时，入队方会观察到本 worker 仍 RUNNING → 走 REUSE_RUNNING 分支并置 queueKickRequested 而不另起
             * worker；本处在锁内消费该标记：有则再跑一轮（新任务由下一轮 claim 领取），无则声明「本轮静默退出」
             * （期间如有入队方观察到静默，它会自行补一个 worker）。由此窗口被两侧夹死。
             */
            val retry =
                synchronized(queueKickLock) {
                    if (queueKickRequested) {
                        queueKickRequested = false
                        true
                    } else {
                        engineWorkerQuiesced = true
                        false
                    }
                }
            if (!retry)
                return DownloadQueueOutcome(
                    ranTasks = ran,
                    hasPendingTasks = false,
                    shouldRetry = false,
                )
        }
    }

    /** 领取可立即运行的 PENDING 任务并标记 RUNNING（受并发上限约束）。 */
    private suspend fun claimRunnableTasks(limit: Int): List<FindroidSourceDto> {
        if (limit <= 0) return emptyList()
        val now = System.currentTimeMillis()
        return withContext(Dispatchers.IO) {
            database
                .getPendingSources()
                .filter { it.engineVersion >= ENGINE_VERSION }
                .filter { source ->
                    val status = source.taskStatus.toTaskStatus()
                    // 进程中断后残留的 RUNNING（没有活动协程）同样可领取，实现自愈恢复。
                    status == DownloadTaskStatus.PENDING ||
                        (status == DownloadTaskStatus.RUNNING && !activeJobs.containsKey(source.id))
                }
                .filter { it.nextRetryAt <= now }
                .filter { !activeJobs.containsKey(it.id) }
                .sortedBy { it.updatedAt }
                .take(limit)
                .onEach { source ->
                    database.setSourceTaskStatus(
                        source.id,
                        DownloadTaskStatus.RUNNING.name,
                        null,
                        now,
                    )
                }
        }
    }

    /**
     * 确保有一个下载引擎 worker 在运行。
     *
     * - 唯一工作链上已有 **运行中** 的 worker：直接复用（它会继续领取任务）；
     * - 只有「排队中」（等待退避 / 网络）的 worker：普通调用不打扰；用户动作（新下载 / 继续 / 重试）用 [forceStart] 取消延迟任务并立即唤醒；
     * - 链上已无未完成工作：入队即时 worker。
     */
    private suspend fun ensureEngineRunning(forceStart: Boolean = false) {
        val infos =
            withContext(Dispatchers.IO) {
                runCatching {
                    workManager.getWorkInfosForUniqueWorkFlow(DOWNLOAD_ENGINE_WORK_NAME).first()
                }
                    .getOrNull()
            }
        if (infos == null) {
            enqueueEngineWork(delayMs = 0L)
            return
        }
        val hasRunning = infos.any { it.state == WorkInfo.State.RUNNING }
        val hasUnfinished = infos.any { !it.state.isFinished }
        when (DownloadEngineScheduleRules.decide(hasRunning, hasUnfinished, forceStart)) {
            EngineScheduleDecision.REUSE_RUNNING ->
                // W76-Q7：运行中的 worker 会继续领取，但要置一次 kick——它在退出前会再确认一次队列，
                // 避免「新任务恰好落库在它判定空与退出之间」被漏掉。
                kickRunningEngineWorker()
            EngineScheduleDecision.ENQUEUE_NOW -> enqueueEngineWork(delayMs = 0L)
            EngineScheduleDecision.RESTART_NOW ->
                enqueueEngineWork(delayMs = 0L, policy = ExistingWorkPolicy.REPLACE)
            EngineScheduleDecision.WAIT -> Unit
        }
    }

    /**
     * W76-Q7：与运行中的引擎 worker 做一次「退出交接」。
     *
     * 若 worker 尚未决定退出 → 置 `queueKickRequested`，让它在本轮结束前再跑一轮； 若 worker 已声明静默退出（即将返回）→ 由本调用补一个即时
     * worker，接管这次入队。
     */
    private fun kickRunningEngineWorker() {
        val scheduleFresh =
            synchronized(queueKickLock) {
                if (engineWorkerQuiesced) {
                    engineWorkerQuiesced = false
                    true
                } else {
                    queueKickRequested = true
                    false
                }
            }
        if (scheduleFresh) enqueueEngineWork(delayMs = 0L)
    }

    private fun enqueueEngineWork(
        delayMs: Long,
        policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
    ) {
        val builder =
            OneTimeWorkRequestBuilder<DownloadEngineWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        if (delayMs > 0L) {
            builder.setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        }
        workManager.enqueueUniqueWork(
            DOWNLOAD_ENGINE_WORK_NAME,
            policy,
            builder.build(),
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 单任务执行
    // ---------------------------------------------------------------------------------------------

    private suspend fun executeTask(source: FindroidSourceDto) {
        val job = coroutineContext[Job]
        if (job != null) {
            val running = activeJobs.putIfAbsent(source.id, job)
            if (running != null && running !== job) return
        }
        val meter = DownloadSpeedMeter()
        var lastPersistAt = 0L
        try {
            val item =
                findItem(source.itemId)
                    ?: throw DownloadHttpException(
                        DownloadFailureReason.FILE_ERROR,
                        "任务对应的媒体条目缺失",
                    )
            val mediaSource = resolveRemoteSource(item, source.id)
            val target = File(source.path)
            val fileExists = target.isFile
            val offset =
                DownloadTaskRules.initialOffset(
                    downloadedBytes = source.downloadedBytes,
                    fileExists = fileExists,
                    fileLength = if (fileExists) target.length() else 0L,
                )
            if (!networkPolicyAllows()) {
                throw DownloadHttpException(
                    DownloadFailureReason.NETWORK_UNAVAILABLE,
                    "当前网络不允许下载（移动数据 / 漫游开关）",
                )
            }

            val totalBytes = mediaSource.size.takeIf { it > 0L } ?: source.totalBytes
            val requiredBytes = (totalBytes - offset).coerceAtLeast(0L)
            if (totalBytes > 0L && requiredBytes > 0L) {
                // W76-B9：首次下载（新装 / 清数据）时 downloads 目录尚不存在，
                // StatFs(不存在路径) 会抛 IllegalArgumentException；先建父目录，必要时回落 filesDir。
                val statDirectory = DownloadStorageRules.resolveStatPath(target, context.filesDir)
                val stats = StatFs(statDirectory.path)
                if (stats.availableBytes < requiredBytes) {
                    throw DownloadHttpException(
                        DownloadFailureReason.STORAGE_INSUFFICIENT,
                        "存储空间不足：需要 ${Formatter.formatFileSize(context, requiredBytes)}，可用 ${Formatter.formatFileSize(context, stats.availableBytes)}",
                    )
                }
            }

            runtime.update {
                it +
                    (source.id to
                        TaskRuntime(
                            name = itemName(item, source.name),
                            downloadedBytes = offset,
                            totalBytes = totalBytes,
                            speedBytesPerSecond = 0L,
                            etaSeconds = null,
                            validator = source.resumeValidator,
                        ))
            }

            // W57 限速：每次任务启动时读取偏好；运行中的任务不打断（新任务 / 重试立即生效）。
            val speedLimitBytesPerSecond =
                DownloadSpeedLimitRules.bytesPerSecond(
                    appPreferences.getValue(appPreferences.downloadSpeedLimitMbps)
                )
            val result =
                httpEngine.download(
                    url = mediaSource.path,
                    token = jellyfinRepository.getAccessToken(),
                    baseUrl = jellyfinRepository.getBaseUrl(),
                    target = target,
                    existingBytes = offset,
                    validator = source.resumeValidator,
                    expectedTotalBytes = totalBytes,
                    speedLimitBytesPerSecond = speedLimitBytesPerSecond,
                    onProgress = { downloaded, total, validator, _ ->
                        meter.onProgress(downloaded)
                        val eta = meter.etaSeconds(total, downloaded)
                        runtime.update {
                            it +
                                (source.id to
                                    TaskRuntime(
                                        name = itemName(item, source.name),
                                        downloadedBytes = downloaded,
                                        totalBytes = total,
                                        speedBytesPerSecond = meter.speedBytesPerSecond(),
                                        etaSeconds = eta,
                                        validator = validator,
                                    ))
                        }
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                            lastPersistAt = now
                            runCatching {
                                database.setSourceProgress(source.id, downloaded, total, now)
                            }
                                .onFailure { Timber.w(it, "持久化下载进度失败 ${source.id}") }
                            updateForegroundNotificationNow()
                        }
                    },
                )
            completeTask(source, item, mediaSource, target, result)
        } catch (cancellation: CancellationException) {
            // 取消（暂停 / 删除 / worker 停止）：清理写库需要 NonCancellable，否则会被立刻再次取消。
            withContext(NonCancellable) { handleTaskCancellation(source) }
        } catch (http: DownloadHttpException) {
            handleTaskFailure(source, http)
        } catch (error: Exception) {
            // W76-Q2：先按异常类型判定到具体原因；真未知才落 UNKNOWN。B9 留痕保留（release 无 Timber 时
            // 至少 logcat 可查 cause），但不再一律收成 UNKNOWN。
            val reason = DownloadFailureClassifier.classify(error)
            Timber.w(error, "下载任务异常（%s）：%s", reason, source.id)
            handleTaskFailure(
                source,
                DownloadHttpException(reason, error.message, error),
            )
        } finally {
            if (job != null) activeJobs.remove(source.id, job)
            runtime.update { it - source.id }
            lastNotificationAt = 0L
            updateForegroundNotificationNow()
        }
    }

    private suspend fun completeTask(
        source: FindroidSourceDto,
        item: FindroidItem,
        mediaSource: FindroidSource,
        partial: File,
        result: DownloadAttemptResult,
    ) {
        val finalFile =
            if (partial.path.endsWith(".download")) {
                File(partial.path.removeSuffix(".download"))
            } else {
                partial
            }
        if (finalFile.exists()) finalFile.delete()
        val renamed = partial.renameTo(finalFile)
        val now = System.currentTimeMillis()
        if (!renamed) {
            database.setSourceTaskStatus(
                source.id,
                DownloadTaskStatus.FAILED.name,
                DownloadFailureReason.FILE_ERROR.name,
                now,
            )
            notifications.notifyFailed(
                buildTask(
                    source,
                    status = DownloadTaskStatus.FAILED,
                    failureReason = DownloadFailureReason.FILE_ERROR,
                    downloadedBytes = result.downloadedBytes,
                    totalBytes = result.totalBytes,
                )
            )
            return
        }

        database.setSourcePath(source.id, finalFile.path)
        database.setSourceTaskStatus(source.id, DownloadTaskStatus.COMPLETED.name, null, now)
        database.setSourceProgress(source.id, result.downloadedBytes, result.totalBytes, now)
        database.setSourceResumeValidator(source.id, result.validator)
        database.setSourceRetry(source.id, 0, 0L, now)
        // W63：完成后活动集变化——侧栏角标即时减一，不等 2s 轮询。
        notifyQueueChanged()
        // 「下载完成通知」偏好（默认开）：关闭后只保留进行中的前台服务通知。
        if (appPreferences.getValue(appPreferences.downloadCompleteNotification)) {
            notifications.notifyCompleted(
                buildTask(
                    source.copy(path = finalFile.path),
                    status = DownloadTaskStatus.COMPLETED,
                    downloadedBytes = result.downloadedBytes,
                    totalBytes = result.totalBytes,
                )
            )
        }
        Timber.i("下载完成：%s（%d 字节）", source.name, result.downloadedBytes)

        runCatching { persistItemSnapshot(item) }
            .onFailure { Timber.w(it, "补写下载条目快照失败 ${item.id}") }
        runCatching { downloadExtras(item, mediaSource, source) }
            .onFailure { Timber.w(it, "下载附属内容失败 ${item.id}") }
    }

    private suspend fun handleTaskCancellation(source: FindroidSourceDto) {
        val now = System.currentTimeMillis()
        val persisted = database.getSource(source.id)
        val status = persisted?.taskStatus.toTaskStatus()
        val bytes =
            runtime.value[source.id]?.downloadedBytes
                ?: persisted?.downloadedBytes
                ?: partialFileSize(source.path)
        if (status == DownloadTaskStatus.PAUSED || status == DownloadTaskStatus.COMPLETED) {
            return
        }
        // 进程 / worker 中断：保留残片，回到等待调度，由下次 worker 续传。
        runCatching {
            database.setSourceProgress(source.id, bytes, persisted?.totalBytes ?: 0L, now)
            database.setSourceTaskStatus(source.id, DownloadTaskStatus.PENDING.name, null, now)
        }
    }

    private suspend fun handleTaskFailure(
        source: FindroidSourceDto,
        failure: DownloadHttpException,
    ) {
        val now = System.currentTimeMillis()
        val reason = failure.reason
        val retryCount = (database.getSource(source.id)?.retryCount ?: source.retryCount) + 1
        val retryable =
            DownloadTaskRules.isAutoRetryEligible(
                status = DownloadTaskStatus.FAILED,
                failureReason = reason,
                retryCount = retryCount,
            )
        if (reason == DownloadFailureReason.CANNOT_RESUME) {
            // 残片不可续：清掉残片，下一次从头安全重下。
            deletePartialArtifacts(source.path)
            runCatching { database.setSourceProgress(source.id, 0L, source.totalBytes, now) }
        }
        if (retryable) {
            /*
             * 断网时不用长退避：下一次 worker 直接带 CONNECTED 约束等待网络恢复，恢复即续传；
             * 仅在「网络可用但仍失败」（超时 / 服务端错误 / 计费策略拦截）时叠加指数退避。
             */
            val offline =
                reason == DownloadFailureReason.NETWORK_UNAVAILABLE && !hasValidatedInternet()
            val delayMs = if (offline) 0L else DownloadTaskRules.backoffDelayMs(retryCount)
            database.setSourceTaskStatus(
                source.id,
                DownloadTaskStatus.PENDING.name,
                reason.name,
                now,
            )
            database.setSourceRetry(source.id, retryCount, now + delayMs, now)
            Timber.i(
                failure,
                "下载失败将自动重试（%s，第 %d 次，%d ms 后）：%s",
                reason,
                retryCount,
                delayMs,
                source.name,
            )
        } else {
            database.setSourceTaskStatus(
                source.id,
                DownloadTaskStatus.FAILED.name,
                reason.name,
                now,
            )
            database.setSourceRetry(source.id, retryCount, 0L, now)
            // W63：进入终态失败——角标即时去掉该活动任务。
            notifyQueueChanged()
            Timber.w(failure, "下载失败（%s）：%s", reason, source.name)
            notifications.notifyFailed(
                buildTask(
                    source,
                    status = DownloadTaskStatus.FAILED,
                    failureReason = reason,
                    downloadedBytes =
                        runtime.value[source.id]?.downloadedBytes ?: source.downloadedBytes,
                    totalBytes = runtime.value[source.id]?.totalBytes ?: source.totalBytes,
                )
            )
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 暂停 / 恢复 / 重试 / 删除
    // ---------------------------------------------------------------------------------------------

    override suspend fun pauseTask(task: DownloadTask): Boolean = pauseTaskById(task.sourceId)

    override suspend fun pauseTaskById(sourceId: String): Boolean =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val source = database.getSource(sourceId) ?: return@withContext false
            val bytes =
                runtime.value[sourceId]?.downloadedBytes
                    ?: source.downloadedBytes.takeIf { it > 0L }
                    ?: partialFileSize(source.path)
            // 先写 PAUSED，再取消协程：取消处理据此保留状态与残片。
            database.setSourceTaskStatus(sourceId, DownloadTaskStatus.PAUSED.name, null, now)
            database.setSourceProgress(sourceId, bytes, source.totalBytes, now)
            val job = activeJobs[sourceId]
            job?.cancel(CancellationException("用户暂停下载"))
            job?.join()
            updateForegroundNotificationNow()
            true
        }

    override suspend fun resumeTask(task: DownloadTask): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) {
            val source = database.getSource(task.sourceId) ?: return@withContext Pair(-1L, null)
            if (source.taskStatus.toTaskStatus() != DownloadTaskStatus.PAUSED) {
                return@withContext Pair(source.downloadId ?: downloadTaskHandle(source.id), null)
            }
            database.setSourceTaskStatus(
                source.id,
                DownloadTaskStatus.PENDING.name,
                null,
                System.currentTimeMillis(),
            )
            database.setSourceRetry(source.id, source.retryCount, 0L, System.currentTimeMillis())
            ensureEngineRunning(forceStart = true)
            Pair(source.downloadId ?: downloadTaskHandle(source.id), null)
        }

    override suspend fun retryTask(task: DownloadTask): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) {
            val source = database.getSource(task.sourceId) ?: return@withContext Pair(-1L, null)
            val now = System.currentTimeMillis()
            database.setSourceTaskStatus(
                source.id,
                DownloadTaskStatus.PENDING.name,
                null,
                now,
            )
            database.setSourceRetry(source.id, 0, 0L, now)
            ensureEngineRunning(forceStart = true)
            Pair(source.downloadId ?: downloadTaskHandle(source.id), null)
        }

    override suspend fun deleteTask(task: DownloadTask): Boolean = deleteTaskById(task.sourceId)

    override suspend fun deleteTaskById(sourceId: String): Boolean =
        withContext(Dispatchers.IO) {
            val source = database.getSource(sourceId) ?: return@withContext false
            val job = activeJobs[sourceId]
            job?.cancel(CancellationException("用户取消下载"))
            job?.join()
            deletePartialArtifacts(source.path)
            sidecar.remove(source.itemId)
            val item = findItem(source.itemId)
            if (item != null) {
                deleteItem(item, source.toFindroidSource(database))
            } else {
                deletePartialArtifacts(source.path)
                database.deleteSource(sourceId)
            }
            runtime.update { it - sourceId }
            updateForegroundNotificationNow()
            notifyQueueChanged()
            true
        }

    override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) {
        val source = findSourceByHandle(downloadId) ?: return
        deleteTaskById(source.id)
    }

    override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) {
        sidecar.remove(item.id)
        when (item) {
            is FindroidMovie -> {
                database.deleteMovie(item.id)
            }
            is FindroidEpisode -> {
                database.deleteEpisode(item.id)
                val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                if (remainingEpisodes.isEmpty()) {
                    database.deleteSeason(item.seasonId)
                    database.deleteUserData(item.seasonId)
                    File(context.filesDir, "trickplay/${item.seasonId}").deleteRecursively()
                    File(context.filesDir, "images/${item.seasonId}").deleteRecursively()
                    val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                    if (remainingSeasons.isEmpty()) {
                        database.deleteShow(item.seriesId)
                        database.deleteUserData(item.seriesId)
                        File(context.filesDir, "trickplay/${item.seriesId}").deleteRecursively()
                        File(context.filesDir, "images/${item.seriesId}").deleteRecursively()
                    }
                }
            }
        }

        database.deleteSource(source.id)
        File(source.path).delete()
        deletePartialSidecar(source.path)

        val mediaStreams = database.getMediaStreamsBySourceId(source.id)
        for (mediaStream in mediaStreams) {
            File(mediaStream.path).delete()
        }
        database.deleteMediaStreamsBySourceId(source.id)

        database.deleteUserData(item.id)

        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
    }

    // ---------------------------------------------------------------------------------------------
    // 查询 / 对账
    // ---------------------------------------------------------------------------------------------

    override suspend fun getProgress(downloadId: Long?): Pair<Int, Int> {
        if (downloadId == null) return DownloadManager.STATUS_FAILED to -1
        val source = findSourceByHandle(downloadId) ?: return DownloadManager.STATUS_FAILED to -1
        val status = source.taskStatus.toTaskStatus() ?: return DownloadManager.STATUS_FAILED to -1
        val taskRuntime = runtime.value[source.id]
        val downloaded = taskRuntime?.downloadedBytes ?: source.downloadedBytes
        val total = taskRuntime?.totalBytes ?: source.totalBytes
        val progress =
            when {
                status == DownloadTaskStatus.COMPLETED -> 100
                total > 0L -> (downloaded * 100L / total).coerceIn(0L, 100L).toInt()
                else -> -1
            }
        return status.toDownloadManagerStatus() to progress
    }

    override suspend fun refreshDownloadTasks(): List<DownloadTask> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val mediaRecords = sidecar.records()
            val episodeHierarchy = runCatching {
                // W59：进行中 / 已完成的剧集都要带归属（Show → 季 → 剧集钻取分组）。
                database.getEpisodeHierarchyWithSources().associateBy { it.episodeId }
            }
                .getOrElse { emptyMap() }
            val tasks = mutableListOf<DownloadTask>()

            for (source in database.getPendingSources()) {
                // 有运行中的协程就是 RUNNING（持久化状态在进程中断时会被恢复逻辑降级为 PENDING）。
                val status =
                    if (activeJobs.containsKey(source.id)) {
                        DownloadTaskStatus.RUNNING
                    } else {
                        DownloadTaskRules.resolveStatus(
                            persistedStatus = source.taskStatus,
                            pathIsPartial = source.path.endsWith(".download"),
                        )
                    }
                val runtimeState = runtime.value[source.id]
                val downloaded =
                    runtimeState?.downloadedBytes
                        ?: source.downloadedBytes.takeIf { it > 0L }
                        ?: partialFileSize(source.path)
                val total = runtimeState?.totalBytes ?: source.totalBytes
                val record = mediaRecords[source.itemId.toString()]
                val hierarchy: DownloadedEpisodeHierarchy? = episodeHierarchy[source.itemId]
                tasks +=
                    DownloadTask(
                        itemId = source.itemId,
                        sourceId = source.id,
                        name = runtimeState?.name ?: itemNameFor(source.itemId, source.name),
                        path = source.path,
                        downloadId = source.downloadId ?: downloadTaskHandle(source.id),
                        status = status,
                        failureReason = source.failureReason?.toFailureReason(),
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        updatedAt = source.updatedAt.takeIf { it > 0L } ?: now,
                        mediaKind = record?.kind ?: DownloadMediaKind.VIDEO,
                        seriesId = hierarchy?.seriesId,
                        seasonId = hierarchy?.seasonId,
                        seriesName = hierarchy?.seriesName,
                        seasonName = hierarchy?.seasonName,
                        episodeIndex = hierarchy?.episodeIndex ?: 0,
                        seasonIndex = hierarchy?.seasonIndex ?: 0,
                        albumName = record?.albumName,
                        artist = record?.artist,
                        speedBytesPerSecond = runtimeState?.speedBytesPerSecond ?: 0L,
                        etaSeconds = runtimeState?.etaSeconds,
                        retryCount = source.retryCount,
                        nextRetryAt = source.nextRetryAt,
                    )
            }

            if (tasks.any { it.status == DownloadTaskStatus.PENDING }) {
                ensureEngineRunning()
            }
            tasks.sortedByDescending { it.updatedAt }
        }

    override suspend fun recoverOnStartup(): Int =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var recovered = 0
            for (source in database.getPendingSources()) {
                val status = source.taskStatus.toTaskStatus()
                val pathIsPartial = source.path.endsWith(".download")
                if (
                    DownloadTaskRules.requiresRedownloadAfterEngineUpgrade(
                        engineVersion = source.engineVersion,
                        downloadId = source.downloadId,
                        status = status,
                        pathIsPartial = pathIsPartial,
                    )
                ) {
                    // 旧 DownloadManager 进行中任务：不做无缝接管，提示需重下。
                    database.setSourceTaskStatus(
                        source.id,
                        DownloadTaskStatus.FAILED.name,
                        DownloadFailureReason.CANCELLED.name,
                        now,
                    )
                    database.setSourceRetry(source.id, 0, 0L, now)
                    recovered += 1
                    continue
                }
                if (
                    source.engineVersion >= ENGINE_VERSION &&
                        (status == DownloadTaskStatus.RUNNING ||
                            status == DownloadTaskStatus.PENDING) &&
                        status != DownloadTaskStatus.COMPLETED
                ) {
                    val bytes =
                        source.downloadedBytes.takeIf { it > 0L } ?: partialFileSize(source.path)
                    database.setSourceProgress(source.id, bytes, source.totalBytes, now)
                    database.setSourceTaskStatus(
                        source.id,
                        DownloadTaskStatus.PENDING.name,
                        null,
                        now,
                    )
                    // 启动恢复 = 新会话：清掉历史退避时间，立即尝试一次（网络未恢复时由 CONNECTED 约束等待）。
                    database.setSourceRetry(source.id, source.retryCount, 0L, now)
                    recovered += 1
                }
            }
            if (recovered > 0) ensureEngineRunning(forceStart = true)
            recovered
        }

    override suspend fun retryNetworkFailures(): Int =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            var retried = 0
            for (source in database.getPendingSources()) {
                if (source.taskStatus.toTaskStatus() != DownloadTaskStatus.FAILED) continue
                val reason = source.failureReason?.toFailureReason()
                if (
                    !DownloadTaskRules.isAutoRetryEligible(
                        DownloadTaskStatus.FAILED,
                        reason,
                        source.retryCount,
                    )
                ) {
                    continue
                }
                database.setSourceTaskStatus(
                    source.id,
                    DownloadTaskStatus.PENDING.name,
                    reason?.name,
                    now,
                )
                database.setSourceRetry(source.id, source.retryCount, 0L, now)
                retried += 1
            }
            if (retried > 0) ensureEngineRunning(forceStart = true)
            retried
        }

    override suspend fun getStorageUsage(): DownloadStorageUsage =
        withContext(Dispatchers.IO) {
            val locations = context.getExternalFilesDirs(null).filterNotNull()
            val usedBytes =
                locations
                    .map { File(it, "downloads") }
                    .distinct()
                    .sumOf { dir ->
                        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                    }
            val stats = StatFs(locations.firstOrNull()?.path ?: context.filesDir.path)
            DownloadStorageUsage(
                usedBytes = usedBytes,
                availableBytes = stats.availableBytes,
                totalBytes = stats.totalBytes,
            )
        }

    override fun mediaSidecar(): DownloadMediaSidecar = sidecar

    override suspend fun downloadedItemIds(): Set<UUID> =
        withContext(Dispatchers.IO) {
            runCatching { database.getDownloadedItemIds().toSet() }.getOrElse { emptySet() }
        }

    override suspend fun activeItemIds(): Set<UUID> =
        withContext(Dispatchers.IO) {
            runCatching {
                database
                    .getPendingSources()
                    .mapNotNull { source ->
                        val status =
                            if (activeJobs.containsKey(source.id)) {
                                DownloadTaskStatus.RUNNING
                            } else {
                                DownloadTaskRules.resolveStatus(
                                    persistedStatus = source.taskStatus,
                                    pathIsPartial = source.path.endsWith(".download"),
                                )
                            }
                        source.itemId.takeIf { DownloadTaskRules.isActiveQueueStatus(status) }
                    }
                    .toSet()
            }
                .getOrElse { emptySet() }
        }

    override suspend fun foregroundInfo(): ForegroundInfo {
        val tasks = snapshotActiveTasks()
        val notification =
            notifications.buildForeground(task = tasks.firstOrNull(), activeCount = tasks.size)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                DownloadNotifications.FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(DownloadNotifications.FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 附属内容（外部字幕流 / 分段 / Trickplay / 图片缓存）
    // ---------------------------------------------------------------------------------------------

    private suspend fun downloadExtras(
        item: FindroidItem,
        source: FindroidSource,
        sourceDto: FindroidSourceDto,
    ) {
        val storageIndex = storageIndexFor(sourceDto.path)
        downloadExternalMediaStreams(item, source, storageIndex)
        runCatching {
            val segments = jellyfinRepository.getSegments(item.id)
            segments.forEach { database.insertSegment(it.toFindroidSegmentsDto(item.id)) }
        }
            .onFailure { Timber.w(it, "下载分段信息失败 ${item.id}") }
        if (item is FindroidSources) {
            val trickplay = item.trickplayInfo?.get(source.id)
            if (trickplay != null) {
                runCatching { downloadTrickplayData(item.id, source.id, trickplay) }
                    .onFailure { Timber.w(it, "下载 Trickplay 失败 ${item.id}") }
            }
        }
        startImagesDownloader(item)
    }

    private suspend fun downloadExternalMediaStreams(
        item: FindroidItem,
        source: FindroidSource,
        storageIndex: Int,
    ) {
        val storageLocation = context.getExternalFilesDirs(null).getOrNull(storageIndex) ?: return
        for (mediaStream in source.mediaStreams.filter { it.isExternal && it.path != null }) {
            runCatching {
                val id = UUID.randomUUID()
                val target = File(storageLocation, "downloads/${item.id}.${source.id}.$id")
                val partial = File(target.path + ".download")
                database.insertMediaStream(
                    mediaStream.toFindroidMediaStreamDto(id, source.id, target.path)
                )
                httpEngine.download(
                    url = mediaStream.path!!,
                    token = jellyfinRepository.getAccessToken(),
                    baseUrl = jellyfinRepository.getBaseUrl(),
                    target = partial,
                    existingBytes = 0L,
                    validator = null,
                    expectedTotalBytes = 0L,
                    speedLimitBytesPerSecond =
                        DownloadSpeedLimitRules.bytesPerSecond(
                            appPreferences.getValue(appPreferences.downloadSpeedLimitMbps)
                        ),
                    onProgress = { _, _, _, _ -> },
                )
                if (target.exists()) target.delete()
                if (partial.renameTo(target)) {
                    database.setMediaStreamPath(id, target.path)
                } else {
                    database.deleteMediaStream(id)
                }
            }
                .onFailure { Timber.w(it, "下载外部媒体流失败 ${item.id}（${mediaStream.title}）") }
        }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
    ) {
        val maxIndex =
            ceil(
                    trickplayInfo.thumbnailCount
                        .toDouble()
                        .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                )
                .toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toFindroidTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            File(context.filesDir, "$basePath/$i").writeBytes(byteArray)
        }
    }

    private fun startImagesDownloader(item: FindroidItem) {
        val request =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setInputData(workDataOf(ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString()))
                .build()
        workManager.enqueue(request)
    }

    // ---------------------------------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------------------------------

    private suspend fun persistItemSnapshot(item: FindroidItem) {
        val serverId = appPreferences.getValue(appPreferences.currentServer)
        when (item) {
            is FindroidMovie -> database.insertMovie(item.toFindroidMovieDto(serverId))
            is FindroidEpisode -> {
                // W63：整剧 / 全季批量入队时，同剧的节目 / 季快照只解析一次（批内缓存 + 首次并发拉取）。
                val cachedShow = showSnapshotCache[item.seriesId]?.takeIf { it.isFresh() }?.value
                val cachedSeason =
                    seasonSnapshotCache[item.seasonId]?.takeIf { it.isFresh() }?.value
                val resolved =
                    if (cachedShow != null && cachedSeason != null) {
                        cachedShow to cachedSeason
                    } else {
                        coroutineScope {
                            val showDeferred =
                                if (cachedShow != null) null
                                else async { jellyfinRepository.getShow(item.seriesId) }
                            val seasonDeferred =
                                if (cachedSeason != null) null
                                else async { jellyfinRepository.getSeason(item.seasonId) }
                            (cachedShow ?: showDeferred!!.await()) to
                                (cachedSeason ?: seasonDeferred!!.await())
                        }
                    }
                val show = resolved.first
                val season = resolved.second
                val now = System.currentTimeMillis()
                if (cachedShow == null) {
                    showSnapshotCache[item.seriesId] = CachedSnapshot(show, now)
                }
                if (cachedSeason == null) {
                    seasonSnapshotCache[item.seasonId] = CachedSnapshot(season, now)
                }
                database.insertShow(show.toFindroidShowDto(serverId))
                database.insertSeason(season.toFindroidSeasonDto())
                database.insertEpisode(item.toFindroidEpisodeDto(serverId))
                startImagesDownloader(show)
                startImagesDownloader(season)
            }
        }
        // W57：所有条目入队即落盘自身封面——下载中 / 等待网络 / 离线时下载页也能出图。
        startImagesDownloader(item)
        runCatching {
            database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))
        }
            .onFailure { Timber.w(it, "写入下载条目用户数据失败 ${item.id}") }
    }

    private suspend fun resolveRemoteSource(
        item: FindroidItem,
        sourceId: String,
    ): FindroidSource {
        val sources = runCatching {
            jellyfinRepository.getMediaSources(item.id, true)
        }
            .getOrElse { error ->
                // W76-Q2：先按异常类型判定（网络类 → NETWORK_UNAVAILABLE 等）；非传输层异常仍归服务器错误
                // （保持「取媒体来源失败 = 服务器交互失败」的既有口径）。
                val classified = DownloadFailureClassifier.classify(error)
                val reason =
                    if (classified == DownloadFailureReason.UNKNOWN) {
                        DownloadFailureReason.SERVER_ERROR
                    } else {
                        classified
                    }
                throw DownloadHttpException(reason, error.message, error)
            }
        return sources.firstOrNull { it.id == sourceId }
            ?: throw DownloadHttpException(
                DownloadFailureReason.SERVER_ERROR,
                "服务器未返回该媒体来源（sourceId=$sourceId）",
            )
    }

    private suspend fun findItem(itemId: UUID): FindroidItem? {
        val movie = runCatching { database.getMovie(itemId) }.getOrNull()
        if (movie != null) {
            return movie.toFindroidMovie(database, jellyfinRepository.getUserId())
        }
        val episode = runCatching { database.getEpisode(itemId) }.getOrNull()
        if (episode != null) {
            return episode.toFindroidEpisode(database, jellyfinRepository.getUserId())
        }
        return null
    }

    private suspend fun itemNameFor(itemId: UUID, fallback: String): String {
        runCatching { database.getMovie(itemId).name }
            .getOrNull()
            ?.let { if (it.isNotBlank()) return it }
        runCatching { database.getEpisode(itemId).name }
            .getOrNull()
            ?.let { if (it.isNotBlank()) return it }
        return fallback
    }

    private fun itemName(item: FindroidItem, fallback: String): String =
        item.name.takeIf { it.isNotBlank() } ?: fallback

    private fun storageIndexFor(path: String): Int {
        val index =
            context.getExternalFilesDirs(null).filterNotNull().indexOfFirst {
                path.startsWith(it.path)
            }
        return if (index >= 0) index else 0
    }

    private suspend fun findSourceByHandle(handle: Long): FindroidSourceDto? =
        withContext(Dispatchers.IO) {
            database.getAllSources().firstOrNull { downloadTaskHandle(it.id) == handle }
        }

    private fun buildTask(
        source: FindroidSourceDto,
        status: DownloadTaskStatus,
        failureReason: DownloadFailureReason? = null,
        downloadedBytes: Long = source.downloadedBytes,
        totalBytes: Long = source.totalBytes,
    ): DownloadTask {
        val runtimeState = runtime.value[source.id]
        return DownloadTask(
            itemId = source.itemId,
            sourceId = source.id,
            name = runtimeState?.name ?: source.name,
            path = source.path,
            downloadId = source.downloadId ?: downloadTaskHandle(source.id),
            status = status,
            failureReason = failureReason ?: source.failureReason?.toFailureReason(),
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            updatedAt = System.currentTimeMillis(),
            speedBytesPerSecond = runtimeState?.speedBytesPerSecond ?: 0L,
            etaSeconds = runtimeState?.etaSeconds,
            retryCount = source.retryCount,
            nextRetryAt = source.nextRetryAt,
        )
    }

    private suspend fun snapshotActiveTasks(): List<DownloadTask> =
        withContext(Dispatchers.IO) {
            activeJobs.keys
                .mapNotNull { database.getSource(it) }
                .map { buildTask(it, DownloadTaskStatus.RUNNING) }
        }

    private fun updateForegroundNotificationNow() {
        val entries = runtime.value.entries.toList()
        val active = entries.size
        if (active == 0) {
            lastNotificationAt = 0L
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) return
        lastNotificationAt = now
        val (sourceId, state) = entries.first()
        val first =
            DownloadTask(
                itemId = UUID(0L, 0L),
                sourceId = sourceId,
                name = state.name,
                path = "",
                downloadId = null,
                status = DownloadTaskStatus.RUNNING,
                failureReason = null,
                downloadedBytes = state.downloadedBytes,
                totalBytes = state.totalBytes,
                updatedAt = 0L,
                speedBytesPerSecond = state.speedBytesPerSecond,
                etaSeconds = state.etaSeconds,
            )
        notificationManager?.notify(
            DownloadNotifications.FOREGROUND_NOTIFICATION_ID,
            notifications.buildForeground(task = first, activeCount = active),
        )
    }

    private fun networkPolicyAllows(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = runCatching {
            manager.activeNetwork?.let { network -> manager.getNetworkCapabilities(network) }
        }
            .getOrNull()
        if (capabilities == null) {
            // 无法判定（网络切换瞬间 / 系统未返回能力）：放行，交给传输层失败分类处理，避免误拦。
            Timber.d("下载网络策略：无法读取活动网络能力，默认允许")
            return true
        }
        // W57：「仅 Wi-Fi 下载」不再看系统计费标记——Wi-Fi / 以太网直接允许（修复家庭 Wi-Fi 被判计费时永远等待）。
        val wifiOrEthernet =
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        // 漫游只对蜂窝网络有意义（Wi-Fi 不因“无 NOT_ROAMING 能力”被误拦）。
        val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val roaming =
            cellular && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
        return when (
            DownloadNetworkRules.decide(
                isWifiOrEthernet = wifiOrEthernet,
                isCellular = cellular,
                isRoaming = roaming,
                allowMobileData = appPreferences.getValue(appPreferences.downloadOverMobileData),
                allowRoaming = appPreferences.getValue(appPreferences.downloadWhenRoaming),
            )
        ) {
            DownloadNetworkDecision.ALLOW -> true
            DownloadNetworkDecision.WAIT_FOR_MOBILE_DATA -> {
                Timber.i("下载网络策略：非 Wi-Fi / 以太网且未允许移动数据，等待")
                false
            }
            DownloadNetworkDecision.WAIT_FOR_ROAMING -> {
                Timber.i("下载网络策略：蜂窝漫游且未允许，等待")
                false
            }
        }
    }

    /** 当前是否有「已验证可用」的网络（断网退避判定用）。 */
    private fun hasValidatedInternet(): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities =
            runCatching {
                manager.activeNetwork?.let { network -> manager.getNetworkCapabilities(network) }
            }
                .getOrNull() ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** 网络恢复：清掉网络类任务的退避时间并立即唤醒队列。 */
    private suspend fun wakeNetworkBlockedTasks() {
        val now = System.currentTimeMillis()
        val blocked =
            withContext(Dispatchers.IO) {
                database.getPendingSources().filter { source ->
                    source.taskStatus.toTaskStatus() == DownloadTaskStatus.PENDING &&
                        source.failureReason.toFailureReason() ==
                            DownloadFailureReason.NETWORK_UNAVAILABLE
                }
            }
        if (blocked.isEmpty()) return
        for (source in blocked) {
            database.setSourceRetry(source.id, source.retryCount, 0L, now)
        }
        ensureEngineRunning(forceStart = true)
        Timber.i("网络恢复：唤醒 %d 个等待网络的下载任务", blocked.size)
    }

    private data class TaskRuntime(
        val name: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val speedBytesPerSecond: Long,
        val etaSeconds: Long?,
        val validator: String?,
    )

    /** W63：节目 / 季快照缓存条目（TTL 内同剧批量入队只网络拉取一次）。 */
    private data class CachedSnapshot<T>(val value: T, val savedAt: Long) {
        fun isFresh(): Boolean = System.currentTimeMillis() - savedAt <= SNAPSHOT_TTL_MS
    }

    private companion object {
        /** 自研引擎写入 sources 行的版本标记（旧 DownloadManager 行为 0）。 */
        const val ENGINE_VERSION = 1

        /** 进度落库 / 通知刷新间隔。 */
        const val PERSIST_INTERVAL_MS = 1_000L
        const val NOTIFICATION_INTERVAL_MS = 1_000L

        /** W63：节目 / 季快照缓存有效期。 */
        const val SNAPSHOT_TTL_MS = 10 * 60 * 1_000L
    }
}

/** W50 唯一下载引擎任务名。 */
internal const val DOWNLOAD_ENGINE_WORK_NAME = "downloadEngine"

/** sourceId → 稳定的 UI 句柄（替代旧 DownloadManager id 语义，不持久化数值）。 */
internal fun downloadTaskHandle(sourceId: String): Long =
    sourceId.hashCode().toLong() and 0xFFFFFFFFL

/** 自研引擎状态 → DownloadManager 常量（旧 UI 轮询兼容；仅常量映射，不调用系统下载器）。 */
internal fun DownloadTaskStatus.toDownloadManagerStatus(): Int =
    when (this) {
        DownloadTaskStatus.PENDING -> DownloadManager.STATUS_PENDING
        DownloadTaskStatus.RUNNING -> DownloadManager.STATUS_RUNNING
        DownloadTaskStatus.PAUSED -> DownloadManager.STATUS_PAUSED
        DownloadTaskStatus.COMPLETED -> DownloadManager.STATUS_SUCCESSFUL
        DownloadTaskStatus.FAILED -> DownloadManager.STATUS_FAILED
    }
