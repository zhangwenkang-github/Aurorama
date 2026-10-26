package com.zhangwenkang.cinefin.utils

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
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
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.database.DownloadedEpisodeHierarchy
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.DownloadDeleteBatchDto
import com.zhangwenkang.cinefin.models.DownloadDeleteReceiptDto
import com.zhangwenkang.cinefin.models.DownloadIntentDto
import com.zhangwenkang.cinefin.models.DownloadIntentStatus
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidEpisodeDto
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidSources
import com.zhangwenkang.cinefin.models.FindroidTrickplayInfo
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.models.toFindroidEpisodeDto
import com.zhangwenkang.cinefin.models.toFindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.toFindroidMovieDto
import com.zhangwenkang.cinefin.models.toFindroidSeasonDto
import com.zhangwenkang.cinefin.models.toFindroidSegmentsDto
import com.zhangwenkang.cinefin.models.toFindroidShowDto
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.models.toFindroidTrickplayInfo
import com.zhangwenkang.cinefin.models.toFindroidTrickplayInfoDto
import com.zhangwenkang.cinefin.models.toFindroidUserDataDto
import com.zhangwenkang.cinefin.repository.BookDownloadStatus
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.JellyfinOriginCredentials
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.OriginScopedJellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber

private data class FrozenDownloadOrigin(
    val serverId: String?,
    val userId: String?,
)

private data class PersistedDownloadIdentity(
    val kind: DownloadMediaKind,
    val origin: FrozenDownloadOrigin,
)

private sealed interface PersistedOriginResolution {
    data object MissingLegacy : PersistedOriginResolution

    data class UniqueComplete(val identity: PersistedDownloadIdentity) : PersistedOriginResolution

    data class Ambiguous(val matchingRecordCount: Int) : PersistedOriginResolution

    data class FailClosed(val detail: String) : PersistedOriginResolution
}

internal data class DownloadTransferRequest(
    val url: String,
    val token: String?,
    val baseUrl: String?,
    val target: File,
    val existingBytes: Long,
    val validator: String?,
    val expectedTotalBytes: Long,
    val speedLimitBytesPerSecond: Long,
    val onProgress:
        suspend (
            downloadedBytes: Long,
            totalBytes: Long,
            validator: String?,
            resumed: Boolean,
        ) -> Unit,
)

internal fun interface DownloadTransfer {
    suspend fun download(request: DownloadTransferRequest): DownloadAttemptResult
}

private class HttpDownloadTransfer(private val engine: DownloadHttpEngine) : DownloadTransfer {
    override suspend fun download(request: DownloadTransferRequest): DownloadAttemptResult =
        engine.download(
            url = request.url,
            token = request.token,
            baseUrl = request.baseUrl,
            target = request.target,
            existingBytes = request.existingBytes,
            validator = request.validator,
            expectedTotalBytes = request.expectedTotalBytes,
            speedLimitBytesPerSecond = request.speedLimitBytesPerSecond,
            onProgress = request.onProgress,
        )
}

internal interface DownloadWorkGateway {
    suspend fun uniqueWorkInfos(name: String): List<WorkInfo>

    fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    )

    fun cancelUnique(name: String)

    fun enqueue(request: OneTimeWorkRequest)
}

internal interface DownloadImageFinalizationScheduler {
    suspend fun schedule(item: FindroidItem, sourceId: String, generation: Long)

    suspend fun scheduleParent(
        item: FindroidItem,
        owner: ImageDownloadParentOwner,
    )

    suspend fun cancelParent(owner: ImageDownloadParentOwner)
}

private class WorkManagerDownloadWorkGateway(private val workManager: WorkManager) :
    DownloadWorkGateway {
    override suspend fun uniqueWorkInfos(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWorkFlow(name).first()

    override fun enqueueUnique(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest,
    ) {
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override fun cancelUnique(name: String) {
        workManager.cancelUniqueWork(name)
    }

    override fun enqueue(request: OneTimeWorkRequest) {
        workManager.enqueue(request)
    }
}

internal class WorkManagerImageFinalizationScheduler(private val workGateway: DownloadWorkGateway) :
    DownloadImageFinalizationScheduler {
    private val scheduled = ConcurrentHashMap.newKeySet<String>()

    override suspend fun schedule(item: FindroidItem, sourceId: String, generation: Long) {
        val finalizationKey = "$sourceId|$generation"
        if (!scheduled.add(finalizationKey)) return
        val workName = ImageDownloadWorkRules.uniqueWorkName(sourceId)
        val alreadyScheduled =
            withContext(Dispatchers.IO) {
                runCatching { workGateway.uniqueWorkInfos(workName) }
                    .getOrNull()
                    ?.any { finalizationKey in it.tags }
            }
        if (alreadyScheduled == true) return
        val request =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .addTag(finalizationKey)
                .setInputData(
                    workDataOf(
                        ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString(),
                        ImagesDownloaderWorker.KEY_SOURCE_ID to sourceId,
                        ImagesDownloaderWorker.KEY_GENERATION to generation,
                    )
                )
                .build()
        try {
            workGateway.enqueueUnique(
                workName,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        } catch (error: Exception) {
            scheduled.remove(finalizationKey)
            throw error
        }
    }

    override suspend fun scheduleParent(
        item: FindroidItem,
        owner: ImageDownloadParentOwner,
    ) {
        val request =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .addTag(ImageDownloadWorkRules.parentTag(owner))
                .setInputData(
                    workDataOf(
                        ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString(),
                        ImagesDownloaderWorker.KEY_PARENT_KIND to owner.kind.name,
                        ImagesDownloaderWorker.KEY_SERVER_ID to owner.serverId,
                        ImagesDownloaderWorker.KEY_USER_ID to owner.userId,
                    )
                )
                .build()
        workGateway.enqueueUnique(
            ImageDownloadWorkRules.parentWorkName(owner),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    override suspend fun cancelParent(owner: ImageDownloadParentOwner) {
        workGateway.cancelUnique(ImageDownloadWorkRules.parentWorkName(owner))
    }
}

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
class DownloaderImpl
private constructor(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val readerRepository: ReaderRepository,
    private val appPreferences: AppPreferences,
    private val imageCacheStore: ImageCacheStore,
    private val workManager: WorkManager,
    httpClient: OkHttpClient,
    private val notifications: DownloadNotificationGateway,
    private val registerNetworkCallback: (ConnectivityManager.NetworkCallback) -> Unit,
    private val externalStorageState: (File) -> String,
    private val availableBytes: (File) -> Long,
    workGatewayOverride: DownloadWorkGateway?,
    downloadTransferOverride: DownloadTransfer?,
    imageFinalizationSchedulerOverride: DownloadImageFinalizationScheduler?,
) : Downloader {
    constructor(
        context: Context,
        database: ServerDatabaseDao,
        jellyfinRepository: JellyfinRepository,
        readerRepository: ReaderRepository,
        appPreferences: AppPreferences,
        imageCacheStore: ImageCacheStore,
        workManager: WorkManager,
        httpClient: OkHttpClient,
    ) : this(
        context = context,
        database = database,
        jellyfinRepository = jellyfinRepository,
        readerRepository = readerRepository,
        appPreferences = appPreferences,
        imageCacheStore = imageCacheStore,
        workManager = workManager,
        httpClient = httpClient,
        notifications = DownloadNotifications(context),
        registerNetworkCallback = { callback ->
            context
                .getSystemService(ConnectivityManager::class.java)
                ?.registerDefaultNetworkCallback(callback)
        },
        externalStorageState = { file -> Environment.getExternalStorageState(file) },
        availableBytes = { directory -> StatFs(directory.path).availableBytes },
        workGatewayOverride = null,
        downloadTransferOverride = null,
        imageFinalizationSchedulerOverride = null,
    )

    private val workGateway =
        workGatewayOverride ?: WorkManagerDownloadWorkGateway(workManager = workManager)
    private val downloadTransfer =
        downloadTransferOverride ?: HttpDownloadTransfer(DownloadHttpEngine(httpClient))
    private val imageFinalizationScheduler =
        imageFinalizationSchedulerOverride ?: WorkManagerImageFinalizationScheduler(workGateway)
    private val sidecar = DownloadMediaSidecar(context)
    private val intentMutations =
        DownloadIntentMutations(
            beginCancellation = database::beginDownloadIntentCancellation,
            beginDeletion = database::beginDownloadIntentDeletion,
        )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** W63：批量入队的节目 / 季快照缓存（同剧 N 集只解析一次，避免逐集两次网络往返）。 */
    private val showSnapshotCache =
        ConcurrentHashMap<SnapshotCacheKey, CachedSnapshot<FindroidShow>>()
    private val seasonSnapshotCache =
        ConcurrentHashMap<SnapshotCacheKey, CachedSnapshot<FindroidSeason>>()

    /** W84：音乐接收意图批量门面；完整来源以 sidecar 为真源，Room 仅作 legacy 兼容行。 */
    private val batchInbox =
        DownloadBatchInbox(
            store =
                object : DownloadIntentStore {
                    override suspend fun existingCanonicalKeys(
                        requests: List<DownloadMusicIntentRequest>
                    ): Set<String> =
                        withContext(Dispatchers.IO) {
                            val provenIdentities =
                                sidecar.allRecords().requireComplete().mapTo(mutableSetOf()) {
                                    it.canonicalIdentity()
                                }
                            requests
                                .filter { request ->
                                    val identity = request.canonicalIdentity
                                    identity.hasCompleteNamespace &&
                                        provenIdentities.any { it.matchesProvenance(identity) }
                                }
                                .mapTo(mutableSetOf()) { it.acceptanceKey }
                        }

                    override suspend fun saveAll(requests: List<DownloadMusicIntentRequest>) =
                        withContext(Dispatchers.IO) {
                            val now = System.currentTimeMillis()
                            val placeholders = requests.map { request ->
                                DownloadMediaRecord(
                                    itemId = request.itemId.toString(),
                                    kind = DownloadMediaKind.MUSIC,
                                    albumName = request.albumName,
                                    artist = request.artist,
                                    trackIndex = request.trackIndex,
                                    serverId = request.serverId,
                                    userId = request.userId,
                                    sourceId = request.sourceId,
                                    displayName = request.name,
                                )
                            }
                            check(sidecar.putAll(placeholders)) { "下载意图 sidecar 持久化失败" }
                            database.saveDownloadIntents(
                                requests.map { request ->
                                    DownloadIntentDto(
                                        itemId = request.itemId,
                                        name = request.name,
                                        albumName = request.albumName,
                                        artist = request.artist,
                                        trackIndex = request.trackIndex,
                                        status = DownloadIntentStatus.RECEIVED.name,
                                        createdAt = now,
                                        updatedAt = now,
                                        taskGeneration = 0L,
                                    )
                                }
                            )
                        }
                }
        )
    private val receivedIntentLock = Mutex()

    /** W63：队列活动集变化信号（入队 / 完成 / 失败 / 删除），侧栏角标即时刷新。 */
    private val _queueChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val queueChanges: SharedFlow<Unit> = _queueChanges.asSharedFlow()

    /** W63：队列活动集变化（尽力投递；无收集者时不阻塞）。 */
    private fun notifyQueueChanged() {
        _queueChanges.tryEmit(Unit)
    }

    /** sourceId → 运行中的下载协程。 */
    private val activeJobs = ConcurrentHashMap<String, Job>()

    /** 活跃 worker 内的单 claim pump；每个空槽变化都重新领取，而不是等待整批任务结束。 */
    private val queuePump = DownloadQueuePump()

    /** 设置真源变化会唤醒 app-scope pump；降低上限时由 pump 自动暂停最新启动的任务。 */
    private val downloadConcurrencyListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == appPreferences.downloadConcurrency.backendName) {
                queuePump.kick()
                scope.launch { ensureEngineRunning(forceStart = true) }
            }
        }

    /** 同 source 生命周期命令串行，并以代次 claim/fence 封锁迟到副作用。 */
    private val lifecycleControl = DownloadSourceControl()

    /** 同 item 的 source 插入、删除和共享快照清理串行，避免迟到清理破坏新 source。 */
    private val itemMutationLocks = Array(64) { Mutex() }

    /** 父级快照变更与父图任务调度共享 app-scope 串行边界，消除最后子项删除竞态。 */
    private val parentImageMutationLock = Mutex()

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
            appPreferences.sharedPreferences.registerOnSharedPreferenceChangeListener(
                downloadConcurrencyListener
            )
        }
            .onFailure { Timber.w(it, "注册下载并发设置监听失败") }
        runCatching { registerNetworkCallback(networkCallback) }
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
        enqueueItem(
            item = item,
            sourceId = sourceId,
            storageIndex = storageIndex,
            albumName = albumName,
            artist = artist,
            trackIndex = trackIndex,
            origin = freezeDownloadOrigin(),
        )

    /** 在任何 suspend / IO 调度前冻结来源，避免入队途中切号污染下载 provenance。 */
    private fun freezeDownloadOrigin() =
        FrozenDownloadOrigin(
            serverId = appPreferences.getValue(appPreferences.currentServer),
            userId = runCatching { jellyfinRepository.getUserId().toString() }.getOrNull(),
        )

    private fun FrozenDownloadOrigin.toJellyfinOriginOrNull(): JellyfinOrigin? {
        val server = serverId?.takeIf(String::isNotBlank) ?: return null
        val user = userId?.toUuidOrNull() ?: return null
        return JellyfinOrigin(serverId = server, userId = user)
    }

    private fun originScopedRepository(): OriginScopedJellyfinRepository =
        jellyfinRepository as? OriginScopedJellyfinRepository
            ?: error("JellyfinRepository 未提供 origin-scoped 下载恢复能力")

    private fun downloadOriginForSource(
        item: FindroidItem,
        sourceId: String,
    ): FrozenDownloadOrigin? {
        val expectedKind =
            if ((item as? FindroidMovie)?.kind.equals("AUDIO", ignoreCase = true)) {
                DownloadMediaKind.MUSIC
            } else {
                DownloadMediaKind.VIDEO
            }
        return when (val resolution = persistedOriginForSource(item.id, sourceId)) {
            PersistedOriginResolution.MissingLegacy -> null
            is PersistedOriginResolution.UniqueComplete ->
                resolution.identity.origin.takeIf { resolution.identity.kind == expectedKind }
                    ?: error("下载 sidecar 媒体类型不匹配：${item.id}/$sourceId")
            is PersistedOriginResolution.Ambiguous ->
                error("下载 sidecar 来源身份歧义：${item.id}/$sourceId/" + resolution.matchingRecordCount)
            is PersistedOriginResolution.FailClosed ->
                throw DownloadHttpException(
                    DownloadFailureReason.SERVER_ERROR,
                    "下载 sidecar 不可证明来源身份：${resolution.detail}",
                )
        }
    }

    private fun frozenOriginForSource(
        item: FindroidItem,
        persistedOrigin: PersistedOriginResolution,
    ): FrozenDownloadOrigin? {
        val expectedKind =
            if ((item as? FindroidMovie)?.kind.equals("AUDIO", ignoreCase = true)) {
                DownloadMediaKind.MUSIC
            } else {
                DownloadMediaKind.VIDEO
            }
        return when (persistedOrigin) {
            PersistedOriginResolution.MissingLegacy -> null
            is PersistedOriginResolution.UniqueComplete ->
                persistedOrigin.identity.origin.takeIf {
                    persistedOrigin.identity.kind == expectedKind
                } ?: error("下载 sidecar 媒体类型不匹配：${item.id}")
            is PersistedOriginResolution.Ambiguous ->
                error("下载 sidecar 来源身份歧义：${item.id}/" + persistedOrigin.matchingRecordCount)
            is PersistedOriginResolution.FailClosed ->
                error("下载 sidecar 不可证明来源身份：${persistedOrigin.detail}")
        }
    }

    private suspend fun enqueueItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
        albumName: String?,
        artist: String?,
        trackIndex: Int,
        origin: FrozenDownloadOrigin,
    ): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) {
            val handle = downloadTaskHandle(sourceId)
            val storageLocation = context.getExternalFilesDirs(null).getOrNull(storageIndex)
            if (
                storageLocation == null ||
                    externalStorageState(storageLocation) != Environment.MEDIA_MOUNTED
            ) {
                return@withContext Pair(
                    -1L,
                    UiText.StringResource(CoreR.string.storage_unavailable),
                )
            }

            lifecycleControl.withSourceOperation(sourceId) {
                val existing = database.getSource(sourceId)
                val requestedOrigin = origin.toJellyfinOriginOrNull()
                if (existing != null && requestedOrigin != null) {
                    val existingOrigin =
                        downloadOriginForSource(item, sourceId)?.toJellyfinOriginOrNull()
                    check(existingOrigin == requestedOrigin) {
                        "sourceId 已被其他 namespace 占用：$sourceId"
                    }
                }
                val existingStatus = existing?.taskStatus.toTaskStatus()
                if (
                    existingStatus == DownloadTaskStatus.PENDING ||
                        existingStatus == DownloadTaskStatus.RUNNING ||
                        existingStatus == DownloadTaskStatus.PAUSED
                ) {
                    // 幂等：同一来源已在队列 / 下载中 / 暂停，不重置进度。
                    notifyQueueChanged()
                    ensureEngineRunning(forceStart = true)
                    return@withSourceOperation Pair(existing?.downloadId ?: handle, null)
                }

                val taskGeneration =
                    lifecycleControl.nextEnqueueGeneration(
                        sourceId,
                        existing?.taskGeneration ?: 0L,
                    )
                val partialPath =
                    DownloadCompletionCommitRules.downloadTargetFiles(
                            directory = File(storageLocation, "downloads"),
                            itemId = item.id.toString(),
                            sourceId = sourceId,
                            generation = taskGeneration,
                        )
                        .first
                        .path
                val partial = File(partialPath)
                val existingBytes = if (partial.isFile) partial.length() else 0L
                val sourceMetadata =
                    (item as? FindroidSources)?.sources?.firstOrNull { source ->
                        source.id == sourceId
                    }

                itemMutationLock(item.id).withLock {
                    // W51b 竞态修复：条目快照必须先于队列行落库。
                    //
                    // 批量入队（整剧 / 全季）时引擎往往已在运行：若先插入 PENDING 队列行，再写快照（剧集要拉
                    // show / season，两次网络往返），引擎会在快照落库前抢到该行 → findItem 读不到条目 →
                    // FILE_ERROR「任务对应的媒体条目缺失」。真机批量入队 12 集时 11 集命中该竞态。
                    runCatching {
                        persistItemSnapshot(
                            item = item,
                            origin = origin,
                            completionOrigin = null,
                        )
                    }
                        .onFailure { Timber.w(it, "写入下载条目快照失败 ${item.id}") }

                    val mediaKind =
                        if ((item as? FindroidMovie)?.kind.equals("AUDIO", ignoreCase = true)) {
                            DownloadMediaKind.MUSIC
                        } else {
                            DownloadMediaKind.VIDEO
                        }
                    val sidecarRecord =
                        DownloadMediaRecord(
                            itemId = item.id.toString(),
                            kind = mediaKind,
                            albumName = albumName,
                            artist = artist,
                            trackIndex = trackIndex,
                            serverId = origin.serverId,
                            userId = origin.userId,
                            sourceId = sourceId,
                            displayName = item.name,
                        )
                    check(sidecar.put(sidecarRecord)) { "下载 sidecar 持久化失败：${item.id}/$sourceId" }
                    check(
                        sidecar.allRecords().requireComplete().any {
                            it.itemId == item.id.toString() &&
                                it.sourceId == sourceId &&
                                it.serverId == origin.serverId &&
                                it.userId == origin.userId
                        }
                    ) {
                        "下载 sidecar 写入后不可验证：${item.id}/$sourceId"
                    }

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
                            bitrate = sourceMetadata?.bitrate,
                            container = sourceMetadata?.container,
                            taskGeneration = taskGeneration,
                        )
                    try {
                        database.insertSource(sourceDto)
                    } catch (failure: Exception) {
                        if (
                            !sidecar.replaceResolvedWithPending(
                                pending = sidecarRecord.copy(sourceId = null),
                                failedSourceId = sourceId,
                            )
                        ) {
                            failure.addSuppressed(
                                IllegalStateException(
                                    "Room insertSource 失败后无法补偿下载 pending：" + "${item.id}/$sourceId"
                                )
                            )
                        }
                        throw failure
                    }
                    startImagesDownloader(item, sourceId, sourceDto.taskGeneration)
                }
                notifyQueueChanged()
                ensureEngineRunning(forceStart = true)
                Pair(handle, null)
            }
        }

    /**
     * W63：批量入队。同剧的节目 / 季快照在批内只解析一次（[persistItemSnapshot] 的缓存）， 逐条入队逻辑沿用 [downloadItem]（幂等命中活动任务 /
     * 已下载时不重复入队）。
     */
    override suspend fun enqueueItems(
        items: List<FindroidItem>,
        storageIndex: Int,
    ): DownloadBatchResult {
        val origin = freezeDownloadOrigin()
        return withContext(Dispatchers.IO) {
            val added = mutableListOf<UUID>()
            var lastError: UiText? = null
            for (item in items) {
                coroutineContext.ensureActive()
                val sourceId = item.sources.firstOrNull()?.id ?: continue
                val result = runCatching {
                    enqueueItem(
                        item = item,
                        sourceId = sourceId,
                        storageIndex = storageIndex,
                        albumName = null,
                        artist = null,
                        trackIndex = 0,
                        origin = origin,
                    )
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
    }

    override suspend fun receiveMusicBatch(
        requests: List<DownloadMusicIntentRequest>
    ): DownloadBatchReceipt {
        return withContext(Dispatchers.IO) {
            val startedAt = System.nanoTime()
            val scopedRequests = requests.map { request ->
                val serverId = request.serverId?.takeIf(String::isNotBlank)
                val userId = request.userId?.takeIf(String::isNotBlank)
                if (serverId != null && userId != null) {
                    request
                } else if (serverId == null && userId == null) {
                    request.copy(sourceId = null)
                } else {
                    // 只有一段不能证明 namespace；不从 current origin 补齐另一半。
                    request.copy(serverId = null, userId = null, sourceId = null)
                }
            }
            val receipt = batchInbox.accept(scopedRequests)
            Timber.i(
                "下载批次已受理：requested=%d accepted=%d duplicate=%d durationMs=%d",
                scopedRequests.size,
                receipt.acceptedRequests.size,
                receipt.alreadyAcceptedCount,
                (System.nanoTime() - startedAt) / 1_000_000L,
            )
            val sidecarNeedsProcessing =
                when (val snapshot = sidecar.allRecords()) {
                    DownloadMediaReadResult.Missing -> false
                    is DownloadMediaReadResult.Complete ->
                        snapshot.records.any {
                            it.kind == DownloadMediaKind.MUSIC && it.sourceId.isNullOrBlank()
                        }
                    is DownloadMediaReadResult.Unreadable -> true
                    is DownloadMediaReadResult.Corrupt -> true
                }
            if (receipt.acceptedRequests.isNotEmpty() || sidecarNeedsProcessing) {
                notifyQueueChanged()
                launchReceivedIntentProcessor()
            }
            receipt
        }
    }

    /**
     * 后台解析已受理意图。
     *
     * 页面协程退出、切后台或进程重启都不影响已落库的意图；成功写入 sources 后才删除意图行， 因此中途失败 / 被杀时可从持久化快照继续。
     */
    private fun launchReceivedIntentProcessor() {
        scope.launch {
            runCatching { processReceivedIntents() }.onFailure { Timber.w(it, "处理已受理下载意图失败") }
        }
    }

    private suspend fun processReceivedIntents() = receivedIntentLock.withLock {
        val snapshot = withContext(Dispatchers.IO) { sidecar.allRecords() }
        val records =
            when (snapshot) {
                DownloadMediaReadResult.Missing -> emptyList()
                is DownloadMediaReadResult.Complete -> snapshot.records
                is DownloadMediaReadResult.Unreadable -> {
                    Timber.w(snapshot.error, "下载 sidecar 不可读，保留 pending 等待重试")
                    return@withLock
                }
                is DownloadMediaReadResult.Corrupt -> {
                    Timber.w(
                        "下载 sidecar 损坏，保留 pending 等待修复：%s",
                        snapshot.invalidLineNumbers.joinToString(),
                    )
                    return@withLock
                }
            }
        val pendingRecords = records.filter {
            it.kind == DownloadMediaKind.MUSIC &&
                it.sourceId.isNullOrBlank() &&
                it.itemId.toUuidOrNull() != null
        }
        val pendingItemIds = pendingRecords.mapTo(mutableSetOf()) { it.itemId }
        pendingRecords.chunked(INTENT_RESOLVE_CONCURRENCY).forEach { chunk ->
            coroutineScope {
                chunk
                    .map { record -> async { resolveSidecarIntent(record) } }
                    .forEach { it.await() }
            }
        }
        val legacyPending =
            withContext(Dispatchers.IO) {
                database.getReceivedDownloadIntents().filterNot {
                    it.itemId.toString() in pendingItemIds
                }
            }
        legacyPending.chunked(INTENT_RESOLVE_CONCURRENCY).forEach { chunk ->
            coroutineScope {
                chunk
                    .map { intent -> async { resolveLegacyReceivedIntent(intent) } }
                    .forEach { it.await() }
            }
        }
        val remainingPendingItemIds =
            withContext(Dispatchers.IO) {
                val remaining =
                    when (val remainingSnapshot = sidecar.allRecords()) {
                        DownloadMediaReadResult.Missing -> emptyList()
                        is DownloadMediaReadResult.Complete -> remainingSnapshot.records
                        is DownloadMediaReadResult.Unreadable -> {
                            Timber.w(
                                remainingSnapshot.error,
                                "下载 sidecar 二次读取失败，保留 intent 等待重试",
                            )
                            return@withContext emptySet()
                        }
                        is DownloadMediaReadResult.Corrupt -> {
                            Timber.w(
                                "下载 sidecar 二次读取损坏，保留 intent 等待修复：%s",
                                remainingSnapshot.invalidLineNumbers.joinToString(),
                            )
                            return@withContext emptySet()
                        }
                    }
                remaining
                    .filter {
                        it.kind == DownloadMediaKind.MUSIC &&
                            it.sourceId.isNullOrBlank() &&
                            it.itemId.toUuidOrNull() != null
                    }
                    .mapTo(mutableSetOf()) { it.itemId }
            }
        val latestSnapshot = sidecar.allRecords()
        if (
            latestSnapshot is DownloadMediaReadResult.Unreadable ||
                latestSnapshot is DownloadMediaReadResult.Corrupt ||
                (latestSnapshot == DownloadMediaReadResult.Missing &&
                    snapshot != DownloadMediaReadResult.Missing)
        ) {
            Timber.w("下载 sidecar 在 intent 清理前失效，保留 pending 等待重试")
            return@withLock
        }
        withContext(Dispatchers.IO) {
            database.getDownloadIntents().forEach { intent ->
                if (intent.itemId.toString() !in remainingPendingItemIds) {
                    database.deleteDownloadIntentIfCurrent(
                        intent.itemId,
                        intent.taskGeneration,
                    )
                }
            }
        }
        val processed = pendingRecords.size + legacyPending.size
        if (processed > 0) {
            notifyQueueChanged()
            Timber.i("已受理下载意图后台解析完成：%d 项", processed)
        }
    }

    private suspend fun resolveSidecarIntent(record: DownloadMediaRecord) {
        val itemId = record.itemId.toUuidOrNull() ?: return
        val origin =
            FrozenDownloadOrigin(record.serverId, record.userId).toJellyfinOriginOrNull()
                ?: run {
                    Timber.w("sidecar 缺少完整 namespace，保持可重试未知状态：%s", record)
                    return
                }
        runCatching {
            if (!sidecarContains(record)) return
            val originRepository = originScopedRepository()
            val item =
                originRepository.getItemForOrigin(itemId, origin)
                    ?: error("冻结来源中下载条目不存在：${origin.serverId}/${origin.userId}")
            val mediaSources = originRepository.getMediaSourcesForOrigin(itemId, origin)
            val frozenSource =
                when {
                    record.sourceId != null ->
                        mediaSources.singleOrNull { it.id == record.sourceId }
                    else -> mediaSources.singleOrNull()
                }
                    ?: error(
                        "冻结来源媒体源缺失或歧义：${origin.serverId}/${origin.userId}/" +
                            "${record.sourceId ?: "implicit"}"
                    )
            if (!sidecarContains(record)) return
            val (handle, queueError) =
                enqueueItem(
                    item = item,
                    sourceId = frozenSource.id,
                    storageIndex = 0,
                    albumName = record.albumName,
                    artist = record.artist,
                    trackIndex = record.trackIndex,
                    origin = FrozenDownloadOrigin(origin.serverId, origin.userId.toString()),
                )
            if (handle == -1L) {
                error("加入下载队列失败：${queueError ?: "未知错误"}")
            }
        }
            .onFailure { failure -> Timber.w(failure, "解析 sidecar 下载意图失败：%s", itemId) }
    }

    private fun sidecarContains(record: DownloadMediaRecord): Boolean =
        when (val snapshot = sidecar.allRecords()) {
            DownloadMediaReadResult.Missing -> false
            is DownloadMediaReadResult.Complete -> record in snapshot.records
            is DownloadMediaReadResult.Unreadable ->
                throw IllegalStateException(
                    "下载 sidecar 不可读，拒绝继续解析",
                    snapshot.error,
                )
            is DownloadMediaReadResult.Corrupt ->
                error("下载 sidecar 内容损坏，拒绝继续解析：行 " + snapshot.invalidLineNumbers.joinToString())
        }

    private suspend fun resolveLegacyReceivedIntent(intent: DownloadIntentDto) {
        runCatching {
            if (!isReceivedIntentCurrent(intent)) return
            val item = jellyfinRepository.getItem(intent.itemId) ?: error("下载条目不存在")
            val sourceId =
                jellyfinRepository
                    .getMediaSources(intent.itemId, includePath = true)
                    .firstOrNull()
                    ?.id ?: error("下载媒体源不存在")
            if (!isReceivedIntentCurrent(intent)) return
            val (handle, queueError) =
                enqueueItem(
                    item = item,
                    sourceId = sourceId,
                    storageIndex = 0,
                    albumName = intent.albumName,
                    artist = intent.artist,
                    trackIndex = intent.trackIndex,
                    // legacy 缺少持久 provenance：显式降级 unknown，不用当前会话猜回。
                    origin = FrozenDownloadOrigin(serverId = null, userId = null),
                )
            if (handle == -1L) {
                error("加入下载队列失败：${queueError ?: "未知错误"}")
            }
            if (!isReceivedIntentCurrent(intent)) {
                cancelTaskById(sourceId)
            }
        }
            .onSuccess {
                withContext(Dispatchers.IO) {
                    database.deleteDownloadIntentIfCurrent(
                        intent.itemId,
                        intent.taskGeneration,
                    )
                }
            }
            .onFailure { failure ->
                withContext(Dispatchers.IO) {
                    database.setDownloadIntentStatusIfCurrent(
                        itemId = intent.itemId,
                        taskGeneration = intent.taskGeneration,
                        status = DownloadIntentStatus.FAILED.name,
                        failureReason = DownloadFailureReason.SERVER_ERROR.name,
                        failureDetail = failure.message?.take(500),
                        updatedAt = System.currentTimeMillis(),
                    )
                }
                Timber.w(failure, "解析 legacy 下载意图失败：%s", intent.itemId)
            }
    }

    private suspend fun isReceivedIntentCurrent(intent: DownloadIntentDto): Boolean {
        val current = database.getDownloadIntent(intent.itemId) ?: return false
        return current.status == DownloadIntentStatus.RECEIVED.name &&
            current.taskGeneration == intent.taskGeneration
    }

    override suspend fun runQueue(): DownloadQueueOutcome {
        // W76-Q7：新一轮 worker 开始 → 复位调度交接状态（上一轮遗留的 kick / 静默标记作废）。
        synchronized(queueKickLock) {
            engineWorkerQuiesced = false
            queueKickRequested = false
        }
        var ran = 0
        while (true) {
            ran +=
                queuePump.run(
                    maxConcurrentTasks = { maxConcurrentTasks },
                    claim = ::claimRunnableTasks,
                    key = FindroidSourceDto::id,
                    execute = ::executeTask,
                    pause = ::pauseTaskById,
                )
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
            val candidates =
                database
                    .getPendingSources()
                    .filter { it.engineVersion >= ENGINE_VERSION }
                    .filter { source ->
                        val status = source.taskStatus.toTaskStatus()
                        // 进程中断后残留的 RUNNING（没有活动协程）同样可领取，实现自愈恢复。
                        status == DownloadTaskStatus.PENDING ||
                            (status == DownloadTaskStatus.RUNNING &&
                                !activeJobs.containsKey(source.id))
                    }
                    .filter { it.nextRetryAt <= now }
                    .filter { !activeJobs.containsKey(it.id) }
                    .sortedBy { it.updatedAt }
            val claimed = mutableListOf<FindroidSourceDto>()
            for (source in candidates) {
                var claimedSource = source
                // 选择与更新之间可能被删除抢占；只有真正条件更新成功的行才能执行。
                if (
                    lifecycleControl.claim(source.id, source.taskGeneration) {
                        val claimed =
                            database.claimSourceRunning(source.id, source.taskGeneration, now) > 0
                        if (claimed) {
                            claimedSource =
                                runCatching { normalizeLegacyTarget(source) }
                                    .onFailure { Timber.w(it, "迁移旧代次下载目标失败：${source.id}") }
                                    .getOrDefault(source)
                        }
                        claimed
                    }
                ) {
                    claimed += claimedSource
                }
                if (claimed.size >= limit) break
            }
            claimed
        }
    }

    private suspend fun normalizeLegacyTarget(source: FindroidSourceDto): FindroidSourceDto {
        val persisted = File(source.path)
        val finalFile = DownloadCompletionCommitRules.finalFile(persisted)
        val partial =
            if (persisted.path.endsWith(".download")) persisted
            else File(finalFile.path + ".download")
        if (partial.path == persisted.path && !finalFile.exists()) return source
        val parent = persisted.parentFile ?: return source
        val expected =
            DownloadCompletionCommitRules.downloadTargetFiles(
                directory = parent,
                itemId = source.itemId.toString(),
                sourceId = source.id,
                generation = source.taskGeneration,
            )
        if (
            persisted.canonicalPath == expected.first.canonicalPath ||
                persisted.canonicalPath == expected.second.canonicalPath
        ) {
            return source
        }
        if (
            DownloadCompletionCommitRules.hasInterruptedFinalization(
                partial = partial,
                finalFile = finalFile,
                sourceId = source.id,
                generation = source.taskGeneration,
            )
        ) {
            return source
        }
        val migrated =
            when {
                expected.first.isFile -> expected.first
                !partial.isFile -> expected.first
                partial.renameTo(expected.first) -> expected.first
                else -> return source
            }
        database.setSourcePath(source.id, migrated.path)
        return source.copy(path = migrated.path)
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
                runCatching { workGateway.uniqueWorkInfos(DOWNLOAD_ENGINE_WORK_NAME) }.getOrNull()
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
        queuePump.kick()
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
        workGateway.enqueueUnique(
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
            if (!workerAccepts(source, DownloadLifecycleTransition.PROGRESS)) return
            val persistedOrigin = persistedOriginForSource(source.itemId, source.id)
            val persistedTarget = File(source.path)
            val finalFile = DownloadCompletionCommitRules.finalFile(persistedTarget)
            val target =
                if (persistedTarget.path.endsWith(".download")) persistedTarget
                else File(finalFile.path + ".download")
            if (
                DownloadCompletionCommitRules.hasInterruptedFinalization(
                    partial = target,
                    finalFile = finalFile,
                    sourceId = source.id,
                    generation = source.taskGeneration,
                )
            ) {
                val item =
                    findItem(source.itemId, source.id, persistedOrigin)
                        ?: throw DownloadHttpException(
                            DownloadFailureReason.FILE_ERROR,
                            "恢复完成副作用时媒体条目缺失",
                        )
                val mediaSource = resolveRemoteSource(item, source.id, persistedOrigin)
                recoverInterruptedCompletion(
                    source,
                    target,
                    finalFile,
                    item,
                    mediaSource,
                    persistedOrigin,
                )
                return
            }
            val item =
                findItem(source.itemId, source.id, persistedOrigin)
                    ?: throw DownloadHttpException(
                        DownloadFailureReason.FILE_ERROR,
                        "任务对应的媒体条目缺失",
                    )
            val mediaSource = resolveRemoteSource(item, source.id, persistedOrigin)
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
                val available = availableBytes(statDirectory)
                if (available < requiredBytes) {
                    throw DownloadHttpException(
                        DownloadFailureReason.STORAGE_INSUFFICIENT,
                        "存储空间不足：需要 ${Formatter.formatFileSize(context, requiredBytes)}，可用 ${Formatter.formatFileSize(context, available)}",
                    )
                }
            }

            val activated =
                lifecycleControl.withCurrentClaim(source.id, source.taskGeneration) {
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
                }
            if (activated == null) {
                throw CancellationException("下载 claim 已被新代次取代")
            }

            // W57 限速：每次任务启动时读取偏好；运行中的任务不打断（新任务 / 重试立即生效）。
            val speedLimitBytesPerSecond =
                DownloadSpeedLimitRules.bytesPerSecond(
                    appPreferences.getValue(appPreferences.downloadSpeedLimitMbps)
                )
            val revalidatedOrigin = persistedOriginForSource(source.itemId, source.id)
            if (revalidatedOrigin != persistedOrigin) {
                throw DownloadHttpException(
                    DownloadFailureReason.SERVER_ERROR,
                    "下载 sidecar 在解析与 transfer 之间发生变化：${source.id}",
                )
            }
            val transferCredentials =
                credentialsForSource(source.itemId, source.id, persistedOrigin)
            val result =
                downloadTransfer.download(
                    DownloadTransferRequest(
                        url = mediaSource.path,
                        token = transferCredentials.accessToken,
                        baseUrl = transferCredentials.baseUrl,
                        target = target,
                        existingBytes = offset,
                        validator = source.resumeValidator,
                        expectedTotalBytes = totalBytes,
                        speedLimitBytesPerSecond = speedLimitBytesPerSecond,
                        onProgress = { downloaded, total, validator, _ ->
                            val accepted =
                                lifecycleControl.withCurrentClaim(
                                    source.id,
                                    source.taskGeneration,
                                ) {
                                    meter.onProgress(downloaded)
                                    val eta = meter.etaSeconds(total, downloaded)
                                    runtime.update {
                                        it +
                                            (source.id to
                                                TaskRuntime(
                                                    name = itemName(item, source.name),
                                                    downloadedBytes = downloaded,
                                                    totalBytes = total,
                                                    speedBytesPerSecond =
                                                        meter.speedBytesPerSecond(),
                                                    etaSeconds = eta,
                                                    validator = validator,
                                                ))
                                    }
                                    val now = System.currentTimeMillis()
                                    if (now - lastPersistAt >= PERSIST_INTERVAL_MS) {
                                        lastPersistAt = now
                                        runCatching {
                                            database.setSourceProgressIfCurrent(
                                                source.id,
                                                source.taskGeneration,
                                                downloaded,
                                                total,
                                                now,
                                            )
                                        }
                                            .onFailure { Timber.w(it, "持久化下载进度失败 ${source.id}") }
                                        updateForegroundNotificationNow()
                                    }
                                }
                            if (accepted == null) {
                                throw CancellationException("下载 claim 已被取消、删除或恢复")
                            }
                        },
                    )
                )
            completeTask(source, item, mediaSource, target, result, persistedOrigin)
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
            try {
                withContext(NonCancellable) {
                    lifecycleControl.withCurrentClaim(source.id, source.taskGeneration) {
                        runtime.update { it - source.id }
                        lastNotificationAt = 0L
                        updateForegroundNotificationNow()
                    }
                }
            } finally {
                lifecycleControl.releaseClaim(source.id, source.taskGeneration)
            }
        }
    }

    private suspend fun workerAccepts(
        source: FindroidSourceDto,
        transition: DownloadLifecycleTransition,
    ): Boolean {
        val persisted = database.getSource(source.id) ?: return false
        val status = persisted.taskStatus.toTaskStatus() ?: return false
        return DownloadLifecycleRules.accepts(
            claim =
                DownloadLifecycleRules.claim(
                    sourceId = source.id,
                    runningGeneration = source.taskGeneration,
                ),
            current =
                DownloadLifecycleState(
                    status = status,
                    generation = persisted.taskGeneration,
                ),
            transition = transition,
        )
    }

    private fun hasInterruptedFinalization(source: FindroidSourceDto): Boolean {
        val persistedTarget = File(source.path)
        val finalFile = DownloadCompletionCommitRules.finalFile(persistedTarget)
        val partial =
            if (persistedTarget.path.endsWith(".download")) persistedTarget
            else File(finalFile.path + ".download")
        return DownloadCompletionCommitRules.hasInterruptedFinalization(
            partial = partial,
            finalFile = finalFile,
            sourceId = source.id,
            generation = source.taskGeneration,
        )
    }

    private suspend fun recoverInterruptedCompletion(
        source: FindroidSourceDto,
        partial: File,
        finalFile: File,
        item: FindroidItem,
        mediaSource: FindroidSource,
        persistedOrigin: PersistedOriginResolution,
    ) {
        val now = System.currentTimeMillis()
        val downloadedBytes = finalFile.length()
        val totalBytes = source.totalBytes.takeIf { it > 0L } ?: downloadedBytes
        lifecycleControl.withCurrentClaim(source.id, source.taskGeneration) {
            val commit =
                DownloadCompletionCommitRules.commit(
                    partial = partial,
                    finalFile = finalFile,
                    canStart = { workerAccepts(source, DownloadLifecycleTransition.COMPLETION) },
                    commitState = {
                        database.completeSourceIfCurrent(
                            id = source.id,
                            generation = source.taskGeneration,
                            path = finalFile.path,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            validator = source.resumeValidator,
                            updatedAt = now,
                        )
                    },
                    onCommitted = {
                        completeCommittedEffects(
                            source = source,
                            item = item,
                            mediaSource = mediaSource,
                            finalFile = finalFile,
                            downloadedBytes = downloadedBytes,
                            totalBytes = totalBytes,
                            persistedOrigin = persistedOrigin,
                        )
                    },
                    sourceId = source.id,
                    generation = source.taskGeneration,
                )
            if (commit == DownloadCompletionCommit.RENAME_FAILED) {
                database.setSourceTaskStatusIfCurrent(
                    source.id,
                    source.taskGeneration,
                    DownloadTaskStatus.FAILED.name,
                    DownloadFailureReason.FILE_ERROR.name,
                    now,
                )
            }
        }
    }

    /**
     * 在线完成与 witness 恢复共用的提交后集合。
     *
     * 每项都按同一 source/generation 执行；外置流使用确定性 id、元数据写入可覆盖、图片工作按代次去重。全部成功才允许删除 witness 并发布完成通知。
     */
    private suspend fun completeCommittedEffects(
        source: FindroidSourceDto,
        item: FindroidItem,
        mediaSource: FindroidSource,
        finalFile: File,
        downloadedBytes: Long,
        totalBytes: Long,
        persistedOrigin: PersistedOriginResolution,
    ) {
        val frozenOrigin = frozenOriginForSource(item, persistedOrigin)
        val completionOrigin =
            frozenOrigin?.toJellyfinOriginOrNull()
                ?: if (frozenOrigin == null) {
                    null
                } else {
                    throw DownloadHttpException(
                        DownloadFailureReason.SERVER_ERROR,
                        "下载 sidecar 来源身份不完整：${item.id}/${source.id}",
                    )
                }
        val finalization =
            withContext(NonCancellable) {
                DownloadPostCommitRules.finalize(
                    listOf(
                        DownloadPostCommitTask(DownloadPostCommitEffect.SNAPSHOT) {
                            persistItemSnapshot(
                                item,
                                frozenOrigin,
                                completionOrigin,
                            )
                        },
                        DownloadPostCommitTask(DownloadPostCommitEffect.MEDIA_STREAMS) {
                            persistDownloadedMediaStreams(mediaSource, finalFile.path) { mediaStream
                                ->
                                database.insertMediaStream(mediaStream)
                            }
                        },
                        DownloadPostCommitTask(DownloadPostCommitEffect.EXTERNAL_STREAMS) {
                            downloadExternalMediaStreams(
                                item = item,
                                source = mediaSource,
                                storageIndex = storageIndexFor(source.path),
                                generation = source.taskGeneration,
                                persistedOrigin = persistedOrigin,
                            )
                        },
                        DownloadPostCommitTask(DownloadPostCommitEffect.SEGMENTS) {
                            segmentsForCompletion(item.id, completionOrigin).forEach {
                                database.insertSegment(it.toFindroidSegmentsDto(item.id))
                            }
                        },
                        DownloadPostCommitTask(DownloadPostCommitEffect.TRICKPLAY) {
                            if (item is FindroidSources) {
                                item.trickplayInfo?.get(mediaSource.id)?.let { trickplay ->
                                    downloadTrickplayData(
                                        item.id,
                                        mediaSource.id,
                                        trickplay,
                                        completionOrigin,
                                    )
                                }
                            }
                        },
                        DownloadPostCommitTask(DownloadPostCommitEffect.IMAGES) {
                            startImagesDownloader(item, source.id, source.taskGeneration)
                        },
                    )
                )
            }
        if (!finalization.isComplete) {
            deferPostCommitFinalization(source, finalization)
            throw DownloadPostCommitIncompleteException(finalization)
        }

        notifyQueueChanged()
        if (appPreferences.getValue(appPreferences.downloadCompleteNotification)) {
            notifications.notifyCompleted(
                buildTask(
                    source.copy(path = finalFile.path),
                    status = DownloadTaskStatus.COMPLETED,
                    downloadedBytes = downloadedBytes,
                    totalBytes = totalBytes,
                )
            )
        }
        Timber.i("下载完成：%s（%d 字节）", source.name, downloadedBytes)
    }

    private suspend fun deferPostCommitFinalization(
        source: FindroidSourceDto,
        result: DownloadPostCommitResult,
    ) {
        val failure = result.failures.first()
        val now = System.currentTimeMillis()
        val reason =
            DownloadFailureClassifier.classify(failure.error).let {
                if (it == DownloadFailureReason.NETWORK_UNAVAILABLE) it
                else DownloadFailureReason.SERVER_ERROR
            }
        val retryCount = (database.getSource(source.id)?.retryCount ?: source.retryCount) + 1
        val delayMs = DownloadTaskRules.backoffDelayMs(retryCount)
        val updated =
            database.setSourceTaskStatusIfCurrent(
                source.id,
                source.taskGeneration,
                DownloadTaskStatus.PENDING.name,
                reason.name,
                now,
            )
        if (updated <= 0) return
        database.setSourceRetryIfCurrent(
            source.id,
            source.taskGeneration,
            retryCount,
            now + delayMs,
            now,
        )
        notifyQueueChanged()
        Timber.w(
            failure.error,
            "下载提交后副作用未完成（%s，第 %d 项，第 %d 次，%d ms 后重试）：%s",
            failure.effect,
            result.failures.size,
            retryCount,
            delayMs,
            source.name,
        )
    }

    private suspend fun writeFailureIfCurrent(
        sourceId: String,
        generation: Long,
        status: String,
        reason: String,
        now: Long,
    ): Int =
        database.setSourceTaskStatusIfCurrent(
            id = sourceId,
            generation = generation,
            status = status,
            failureReason = reason,
            updatedAt = now,
        )

    private suspend fun completeTask(
        source: FindroidSourceDto,
        item: FindroidItem,
        mediaSource: FindroidSource,
        partial: File,
        result: DownloadAttemptResult,
        persistedOrigin: PersistedOriginResolution,
    ) {
        val finalFile = DownloadCompletionCommitRules.finalFile(partial)
        val now = System.currentTimeMillis()
        lifecycleControl.withCurrentClaim(source.id, source.taskGeneration) {
            if (!workerAccepts(source, DownloadLifecycleTransition.COMPLETION)) {
                return@withCurrentClaim
            }
            val commit =
                DownloadCompletionCommitRules.commit(
                    partial = partial,
                    finalFile = finalFile,
                    canStart = { true },
                    commitState = {
                        database.completeSourceIfCurrent(
                            id = source.id,
                            generation = source.taskGeneration,
                            path = finalFile.path,
                            downloadedBytes = result.downloadedBytes,
                            totalBytes = result.totalBytes,
                            validator = result.validator,
                            updatedAt = now,
                        )
                    },
                    sourceId = source.id,
                    generation = source.taskGeneration,
                    onCommitted = {
                        completeCommittedEffects(
                            source = source,
                            item = item,
                            mediaSource = mediaSource,
                            finalFile = finalFile,
                            downloadedBytes = result.downloadedBytes,
                            totalBytes = result.totalBytes,
                            persistedOrigin = persistedOrigin,
                        )
                    },
                )
            if (commit != DownloadCompletionCommit.RENAME_FAILED) return@withCurrentClaim
            val updated =
                database.setSourceTaskStatusIfCurrent(
                    source.id,
                    source.taskGeneration,
                    DownloadTaskStatus.FAILED.name,
                    DownloadFailureReason.FILE_ERROR.name,
                    now,
                )
            if (updated > 0) {
                notifications.notifyFailed(
                    buildTask(
                        source,
                        status = DownloadTaskStatus.FAILED,
                        failureReason = DownloadFailureReason.FILE_ERROR,
                        downloadedBytes = result.downloadedBytes,
                        totalBytes = result.totalBytes,
                    )
                )
            }
        }
    }

    private suspend fun handleTaskCancellation(source: FindroidSourceDto) {
        lifecycleControl.withCurrentClaim(source.id, source.taskGeneration) {
            handleCurrentTaskCancellation(source)
        }
    }

    private suspend fun handleCurrentTaskCancellation(source: FindroidSourceDto) {
        val now = System.currentTimeMillis()
        val persisted = database.getSource(source.id)
        val status = persisted?.taskStatus.toTaskStatus()
        val bytes =
            runtime.value[source.id]?.downloadedBytes
                ?: persisted?.downloadedBytes
                ?: partialFileSize(source.path)
        if (
            status == DownloadTaskStatus.PAUSED ||
                status == DownloadTaskStatus.FAILED ||
                status == DownloadTaskStatus.COMPLETED ||
                status == DownloadTaskStatus.DELETING
        ) {
            return
        }
        // 进程 / worker 中断：保留残片，回到等待调度，由下次 worker 续传。
        runCatching {
            database.setSourceProgressIfCurrent(
                source.id,
                source.taskGeneration,
                bytes,
                persisted?.totalBytes ?: 0L,
                now,
            )
            database.setSourceTaskStatusIfCurrent(
                source.id,
                source.taskGeneration,
                DownloadTaskStatus.PENDING.name,
                null,
                now,
            )
        }
    }

    private suspend fun handleTaskFailure(
        source: FindroidSourceDto,
        failure: DownloadHttpException,
    ) {
        lifecycleControl.withCurrentClaim(source.id, source.taskGeneration) {
            if (workerAccepts(source, DownloadLifecycleTransition.FAILURE)) {
                handleCurrentTaskFailure(source, failure)
            }
        }
    }

    private suspend fun handleCurrentTaskFailure(
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
            runCatching {
                database.setSourceProgressIfCurrent(
                    source.id,
                    source.taskGeneration,
                    0L,
                    source.totalBytes,
                    now,
                )
            }
        }
        if (retryable) {
            /*
             * 断网时不用长退避：下一次 worker 直接带 CONNECTED 约束等待网络恢复，恢复即续传；
             * 仅在「网络可用但仍失败」（超时 / 服务端错误 / 计费策略拦截）时叠加指数退避。
             */
            val offline =
                reason == DownloadFailureReason.NETWORK_UNAVAILABLE && !hasValidatedInternet()
            val delayMs = if (offline) 0L else DownloadTaskRules.backoffDelayMs(retryCount)
            if (
                writeFailureIfCurrent(
                    source.id,
                    source.taskGeneration,
                    DownloadTaskStatus.PENDING.name,
                    reason.name,
                    now,
                ) <= 0
            ) {
                return
            }
            database.setSourceRetryIfCurrent(
                source.id,
                source.taskGeneration,
                retryCount,
                now + delayMs,
                now,
            )
            Timber.i(
                failure,
                "下载失败将自动重试（%s，第 %d 次，%d ms 后）：%s",
                reason,
                retryCount,
                delayMs,
                source.name,
            )
        } else {
            val updated =
                writeFailureIfCurrent(
                    source.id,
                    source.taskGeneration,
                    DownloadTaskStatus.FAILED.name,
                    reason.name,
                    now,
                )
            if (updated <= 0) return
            database.setSourceRetryIfCurrent(
                source.id,
                source.taskGeneration,
                retryCount,
                0L,
                now,
            )
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

    override suspend fun pauseTask(task: DownloadTask): Boolean =
        pauseTaskAtGeneration(task.sourceId, task.taskGeneration)

    override suspend fun pauseTaskById(sourceId: String): Boolean =
        withContext(Dispatchers.IO) {
            val source = database.getSource(sourceId) ?: return@withContext false
            pauseTaskAtGeneration(sourceId, source.taskGeneration)
        }

    private suspend fun pauseTaskAtGeneration(
        sourceId: String,
        expectedGeneration: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val paused =
                lifecycleControl.withCommand(sourceId, expectedGeneration) {
                    val row =
                        database.beginSourcePause(
                            sourceId,
                            expectedGeneration,
                            System.currentTimeMillis(),
                        )
                    DownloadSourceCommandResult(row, row?.taskGeneration)
                } ?: return@withContext false

            val bytes =
                runtime.value[sourceId]?.downloadedBytes
                    ?: paused.downloadedBytes.takeIf { it > 0L }
                    ?: partialFileSize(paused.path)
            val job = activeJobs[sourceId]
            job?.cancel(CancellationException("用户暂停下载"))
            job?.join()
            lifecycleControl.withCurrentGeneration(sourceId, paused.taskGeneration) {
                database.setSourceProgressIfCurrent(
                    sourceId,
                    paused.taskGeneration,
                    bytes,
                    paused.totalBytes,
                    System.currentTimeMillis(),
                )
                runtime.update { it - sourceId }
                updateForegroundNotificationNow()
                notifyQueueChanged()
            }
            true
        }

    override suspend fun resumeTask(task: DownloadTask): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) {
            val source = database.getSource(task.sourceId) ?: return@withContext Pair(-1L, null)
            when (
                DownloadLifecycleRules.resumeDecision(
                    source.taskStatus.toTaskStatus() ?: return@withContext Pair(-1L, null)
                )
            ) {
                DownloadResumeDecision.REUSE -> {
                    val sameOrImmediate =
                        source.taskGeneration == task.taskGeneration ||
                            source.taskGeneration ==
                                DownloadLifecycleRules.nextGeneration(task.taskGeneration)
                    return@withContext if (sameOrImmediate) {
                        Pair(source.downloadId ?: downloadTaskHandle(source.id), null)
                    } else {
                        Pair(-1L, null)
                    }
                }
                DownloadResumeDecision.REJECT -> return@withContext Pair(-1L, null)
                DownloadResumeDecision.RESUME -> Unit
            }
            val resumed =
                lifecycleControl.withCommand(task.sourceId, task.taskGeneration) {
                    val row =
                        database.beginSourceResume(
                            task.sourceId,
                            task.taskGeneration,
                            System.currentTimeMillis(),
                        )
                    DownloadSourceCommandResult(row, row?.taskGeneration)
                } ?: return@withContext Pair(-1L, null)
            lifecycleControl.withCurrentGeneration(task.sourceId, resumed.taskGeneration) {
                queuePump.kick()
                ensureEngineRunning(forceStart = true)
                notifyQueueChanged()
            }
            Pair(resumed.downloadId ?: downloadTaskHandle(resumed.id), null)
        }

    override suspend fun retryTask(task: DownloadTask): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) {
            if (task.sourceId.startsWith(INTENT_SOURCE_PREFIX)) {
                val now = System.currentTimeMillis()
                return@withContext if (
                    database.retryDownloadIntent(
                        task.itemId,
                        task.taskGeneration,
                        now,
                    ) > 0
                ) {
                    launchReceivedIntentProcessor()
                    notifyQueueChanged()
                    Pair(downloadTaskHandle(task.sourceId), null)
                } else {
                    Pair(-1L, null)
                }
            }
            val current = database.getSource(task.sourceId) ?: return@withContext Pair(-1L, null)
            val status = current.taskStatus.toTaskStatus()
            if (status == DownloadTaskStatus.PENDING || status == DownloadTaskStatus.RUNNING) {
                val sameOrImmediate =
                    current.taskGeneration == task.taskGeneration ||
                        current.taskGeneration ==
                            DownloadLifecycleRules.nextGeneration(task.taskGeneration)
                return@withContext if (sameOrImmediate) {
                    currentRetryHandle(task.sourceId)
                } else {
                    Pair(-1L, null)
                }
            }
            if (status != DownloadTaskStatus.FAILED) {
                return@withContext Pair(-1L, null)
            }
            val retried =
                lifecycleControl.withCommand(task.sourceId, task.taskGeneration) {
                    val now = System.currentTimeMillis()
                    val latest = database.getSource(task.sourceId)
                    if (latest != null && hasInterruptedFinalization(latest)) {
                        val updated =
                            database.setSourceTaskStatusIfCurrent(
                                task.sourceId,
                                task.taskGeneration,
                                DownloadTaskStatus.PENDING.name,
                                null,
                                now,
                            )
                        if (updated > 0) {
                            database.setSourceRetryIfCurrent(
                                task.sourceId,
                                task.taskGeneration,
                                0,
                                0L,
                                now,
                            )
                        }
                        DownloadSourceCommandResult(
                            latest
                                .copy(
                                    taskStatus = DownloadTaskStatus.PENDING.name,
                                    failureReason = null,
                                    retryCount = 0,
                                    nextRetryAt = 0L,
                                    updatedAt = now,
                                )
                                .takeIf { updated > 0 },
                            latest.taskGeneration.takeIf { updated > 0 },
                        )
                    } else {
                        val row =
                            database.beginSourceRetry(
                                task.sourceId,
                                task.taskGeneration,
                                now,
                            )
                        DownloadSourceCommandResult(row, row?.taskGeneration)
                    }
                } ?: return@withContext Pair(-1L, null)
            lifecycleControl.withCurrentGeneration(task.sourceId, retried.taskGeneration) {
                ensureEngineRunning(forceStart = true)
                notifyQueueChanged()
            }
            Pair(retried.downloadId ?: downloadTaskHandle(retried.id), null)
        }

    /** stale UI 显示 FAILED、但实际 worker 已 PENDING/RUNNING 时，重试是幂等复用而不是打断 worker。 */
    private suspend fun currentRetryHandle(sourceId: String): Pair<Long, UiText?> {
        val current = database.getSource(sourceId) ?: return Pair(-1L, null)
        val status = current.taskStatus.toTaskStatus()
        return if (status == DownloadTaskStatus.PENDING || status == DownloadTaskStatus.RUNNING) {
            ensureEngineRunning(forceStart = true)
            Pair(current.downloadId ?: downloadTaskHandle(current.id), null)
        } else {
            Pair(-1L, null)
        }
    }

    override suspend fun deleteTask(task: DownloadTask): Boolean =
        if (task.sourceId.startsWith(INTENT_SOURCE_PREFIX)) {
            deleteIntentAtGeneration(task.itemId, task.taskGeneration)
        } else {
            deleteTaskAtGeneration(task.sourceId, task.taskGeneration)
        }

    override suspend fun cancelTask(task: DownloadTask): Boolean =
        if (task.sourceId.startsWith(INTENT_SOURCE_PREFIX)) {
            cancelIntentAtGeneration(task.itemId, task.taskGeneration)
        } else {
            cancelTaskAtGeneration(task.sourceId, task.taskGeneration)
        }

    override suspend fun cancelTaskById(sourceId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (sourceId.startsWith(INTENT_SOURCE_PREFIX)) {
                val itemId = sourceId.removePrefix(INTENT_SOURCE_PREFIX).let(UUID::fromString)
                val current = database.getDownloadIntent(itemId) ?: return@withContext true
                return@withContext cancelIntentAtGeneration(itemId, current.taskGeneration)
            }
            val source = database.getSource(sourceId) ?: return@withContext false
            cancelTaskAtGeneration(sourceId, source.taskGeneration)
        }

    private suspend fun cancelIntentAtGeneration(
        itemId: UUID,
        expectedGeneration: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val cancelled =
                intentMutations.cancel(
                    itemId,
                    expectedGeneration,
                    System.currentTimeMillis(),
                ) ?: return@withContext false
            notifyQueueChanged()
            // 解析协程可能已越过首个代次检查；对它已落的 source 再执行同一取消语义。
            receivedIntentLock.withLock {
                database.getSources(itemId).forEach { source -> cancelTaskById(source.id) }
            }
            cancelled.status == "FAILED"
        }

    private suspend fun cancelTaskAtGeneration(
        sourceId: String,
        expectedGeneration: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val cancelled =
                lifecycleControl.withCommand(sourceId, expectedGeneration) {
                    val row =
                        database.beginSourceCancellation(
                            sourceId,
                            expectedGeneration,
                            System.currentTimeMillis(),
                        )
                    DownloadSourceCommandResult(row, row?.taskGeneration)
                } ?: return@withContext false

            val job = activeJobs[sourceId]
            job?.cancel(CancellationException("用户取消下载"))
            job?.join()
            lifecycleControl.withCurrentGeneration(sourceId, cancelled.taskGeneration) {
                runtime.update { it - sourceId }
                updateForegroundNotificationNow()
                notifyQueueChanged()
            }
            cancelled.taskStatus.toTaskStatus() == DownloadTaskStatus.FAILED
        }

    override suspend fun deleteTaskById(sourceId: String): Boolean =
        withContext(Dispatchers.IO) {
            if (sourceId.startsWith(INTENT_SOURCE_PREFIX)) {
                val itemId = sourceId.removePrefix(INTENT_SOURCE_PREFIX).let(UUID::fromString)
                val current = database.getDownloadIntent(itemId) ?: return@withContext true
                return@withContext deleteIntentAtGeneration(itemId, current.taskGeneration)
            }
            lifecycleControl.withSourceOperation(sourceId) {
                val existing = database.getSource(sourceId) ?: return@withSourceOperation true
                deleteTaskAtGenerationSerialized(sourceId, existing.taskGeneration)
            }
        }

    private suspend fun deleteIntentAtGeneration(
        itemId: UUID,
        expectedGeneration: Long,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val cancelled =
                intentMutations.delete(
                    itemId,
                    expectedGeneration,
                    System.currentTimeMillis(),
                ) ?: return@withContext false
            try {
                receivedIntentLock.withLock {
                    val sourceIds = database.getSources(itemId).map { it.id }
                    val result =
                        DownloadIntentDeletionRules.deleteAllOrReport(
                            sourceIds = sourceIds,
                            deleteSource = { childSourceId -> deleteTaskById(childSourceId) },
                            deleteIntent = {
                                database.deleteDownloadIntentIfCurrent(
                                    itemId = itemId,
                                    taskGeneration = cancelled.taskGeneration,
                                ) > 0 || database.getDownloadIntent(itemId) == null
                            },
                        )
                    if (!result.confirmed) {
                        throw result.failure ?: IllegalStateException("下载意图删除未确认")
                    }
                    sidecar
                        .allRecords()
                        .requireComplete()
                        .filter {
                            it.kind == DownloadMediaKind.MUSIC &&
                                it.itemId == itemId.toString() &&
                                it.sourceId.isNullOrBlank()
                        }
                        .forEach { sidecar.remove(it.canonicalIdentity()) }
                }
            } finally {
                notifyQueueChanged()
            }
            true
        }

    private suspend fun deleteTaskAtGeneration(
        sourceId: String,
        expectedGeneration: Long,
    ): Boolean =
        lifecycleControl.withSourceOperation(sourceId) {
            deleteTaskAtGenerationSerialized(sourceId, expectedGeneration)
        }

    private suspend fun deleteTaskAtGenerationSerialized(
        sourceId: String,
        expectedGeneration: Long,
        knownItem: FindroidItem? = null,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val current = database.getSource(sourceId) ?: return@withContext true
            if (current.taskGeneration != expectedGeneration) {
                return@withContext false
            }
            val source =
                if (current.taskStatus.toTaskStatus() == DownloadTaskStatus.DELETING) {
                    lifecycleControl.withPersistedGeneration(sourceId, expectedGeneration) {
                        current
                    }
                } else {
                    lifecycleControl.withCommand(sourceId, expectedGeneration) {
                        val row =
                            database.beginSourceDeletion(
                                sourceId,
                                expectedGeneration,
                                System.currentTimeMillis(),
                            )
                        DownloadSourceCommandResult(row, row?.taskGeneration)
                    }
                } ?: return@withContext false

            val job = activeJobs[sourceId]
            job?.cancel(CancellationException("用户取消下载"))
            job?.join()
            val cleanupSucceeded =
                lifecycleControl.withCurrentGeneration(sourceId, source.taskGeneration) {
                    if (database.getSource(sourceId)?.taskGeneration != source.taskGeneration) {
                        return@withCurrentGeneration false
                    }
                    workGateway.cancelUnique(ImageDownloadWorkRules.uniqueWorkName(sourceId))
                    deletePartialArtifacts(source.path)
                    val item = knownItem ?: findItem(source.itemId, source.id)
                    if (item != null) {
                        deleteItem(
                            item,
                            source.toFindroidSource(database),
                            source.taskGeneration,
                        )
                    } else {
                        if (
                            !deleteSourceCompletionArtifacts(source) ||
                                !deletePartialSidecar(source.path) ||
                                database.getSource(sourceId)?.taskGeneration !=
                                    source.taskGeneration
                        ) {
                            return@withCurrentGeneration false
                        }
                        database.deleteMediaStreamsBySourceId(sourceId)
                        database.deleteSource(sourceId)
                        removeSidecarForSource(
                            itemId = source.itemId,
                            sourceId = source.id,
                        )
                    }
                    if (database.getSource(sourceId) != null) {
                        return@withCurrentGeneration false
                    }
                    runtime.update { it - sourceId }
                    updateForegroundNotificationNow()
                    notifyQueueChanged()
                    true
                } ?: false
            if (cleanupSucceeded) {
                lifecycleControl.forget(sourceId, source.taskGeneration)
            }
            cleanupSucceeded
        }

    override suspend fun deleteDownloads(
        requests: List<DownloadDeleteRequest>
    ): DownloadDeleteBatchResult =
        withContext(Dispatchers.IO) {
            val prepared = requests.map { request ->
                if (
                    request.mediaKind == DownloadMediaKind.BOOK ||
                        request.expectedGeneration != null
                ) {
                    request
                } else {
                    request.copy(
                        expectedGeneration =
                            request.sourceId?.takeIf(String::isNotBlank)?.let { sourceId ->
                                if (sourceId.startsWith(INTENT_SOURCE_PREFIX)) {
                                    val itemId =
                                        sourceId
                                            .removePrefix(INTENT_SOURCE_PREFIX)
                                            .let(UUID::fromString)
                                    database.getDownloadIntent(itemId)?.taskGeneration
                                } else {
                                    database.getSource(sourceId)?.taskGeneration
                                }
                            }
                    )
                }
            }
            deleteBatchProcessor.deleteAll(prepared)
        }

    override suspend fun resumePendingDeleteBatches(): Int =
        withContext(Dispatchers.IO) {
            deleteBatchProcessor.resumeIncomplete().sumOf { result ->
                result.receipts.count { it.outcome == DownloadDeleteOutcome.SUCCEEDED }
            }
        }

    private val deleteBatchProcessor: DownloadDeleteBatchProcessor by lazy {
        val store =
            object : DownloadDeleteBatchStore {
                override suspend fun begin(
                    batchId: String,
                    requests: List<DownloadDeleteRequest>,
                    createdAtMs: Long,
                ) {
                    database.beginDownloadDeleteBatch(
                        batch =
                            DownloadDeleteBatchDto(
                                id = batchId,
                                requestedCount = requests.size,
                                createdAt = createdAtMs,
                            ),
                        pendingReceipts =
                            requests.mapIndexed { ordinal, request ->
                                DownloadDeleteReceiptDto(
                                    batchId = batchId,
                                    canonicalKey = DownloadDeletionIdentity.canonicalKey(request),
                                    itemId = request.itemId,
                                    sourceId = request.sourceId,
                                    serverId = request.serverId,
                                    userId = request.userId,
                                    ordinal = ordinal,
                                    mediaKind = request.mediaKind.name,
                                    outcome = DownloadDeleteOutcome.PENDING.name,
                                    detail = null,
                                    updatedAt = createdAtMs,
                                )
                            },
                    )
                }

                override suspend fun record(receipt: DownloadDeleteReceipt) {
                    database.insertDownloadDeleteReceipt(
                        DownloadDeleteReceiptDto(
                            batchId = receipt.batchId,
                            canonicalKey = receipt.canonicalKey,
                            itemId = receipt.itemId,
                            sourceId = receipt.sourceId,
                            serverId = receipt.serverId,
                            userId = receipt.userId,
                            ordinal = receipt.ordinal,
                            mediaKind = receipt.mediaKind.name,
                            outcome = receipt.outcome.name,
                            detail = receipt.detail,
                            updatedAt = receipt.updatedAtMs,
                        )
                    )
                }

                override suspend fun finish(batchId: String, completedAtMs: Long) {
                    database.finishDownloadDeleteBatch(batchId, completedAtMs)
                }

                override suspend fun incomplete(): List<DownloadDeleteBatchSnapshot> =
                    database.getIncompleteDownloadDeleteBatches().map { batch ->
                        DownloadDeleteBatchSnapshot(
                            batchId = batch.id,
                            receipts =
                                database.getDownloadDeleteReceipts(batch.id).map { receipt ->
                                    DownloadDeleteReceipt(
                                        batchId = receipt.batchId,
                                        canonicalKey = receipt.canonicalKey,
                                        itemId = receipt.itemId,
                                        sourceId = receipt.sourceId,
                                        mediaKind =
                                            DownloadMediaKind.entries.first {
                                                it.name == receipt.mediaKind
                                            },
                                        serverId = receipt.serverId,
                                        userId = receipt.userId,
                                        ordinal = receipt.ordinal,
                                        outcome =
                                            DownloadDeleteOutcome.entries.first {
                                                it.name == receipt.outcome
                                            },
                                        detail = receipt.detail,
                                        updatedAtMs = receipt.updatedAt,
                                        expectedGeneration = null,
                                    )
                                },
                        )
                    }
            }
        DownloadDeleteBatchProcessor(store = store, delete = ::deleteDownloadRequest)
    }

    private suspend fun deleteDownloadRequest(
        request: DownloadDeleteRequest,
        resumed: Boolean,
    ): DownloadDeleteAttempt =
        when (request.mediaKind) {
            DownloadMediaKind.BOOK -> {
                readerRepository.removeBookDownload(request.itemId)
                readerRepository.deleteLocalFile(request.itemId)
                DownloadDeleteAttempt(outcome = DownloadDeleteOutcome.SUCCEEDED)
            }
            DownloadMediaKind.MUSIC,
            DownloadMediaKind.VIDEO -> {
                val sourceId = request.sourceId?.takeIf(String::isNotBlank) ?: error("下载来源缺失")
                if (sourceId.startsWith(INTENT_SOURCE_PREFIX)) {
                    deleteIntentForReceipt(sourceId, request, resumed)
                } else {
                    deleteSourceForReceipt(sourceId, request, resumed)
                }
            }
        }

    private suspend fun deleteIntentForReceipt(
        sourceId: String,
        request: DownloadDeleteRequest,
        resumed: Boolean,
    ): DownloadDeleteAttempt {
        val itemId = sourceId.removePrefix(INTENT_SOURCE_PREFIX).let(UUID::fromString)
        val current =
            database.getDownloadIntent(itemId)
                ?: return DownloadDeleteAttempt(outcome = DownloadDeleteOutcome.SUCCEEDED)
        if (current.itemId != request.itemId) {
            return DownloadDeleteAttempt(
                outcome = DownloadDeleteOutcome.SUPERSEDED,
                detail = "下载意图身份已被替换",
                expectedGeneration = request.expectedGeneration,
            )
        }
        val expectedGeneration =
            request.expectedGeneration
                ?: if (resumed) {
                    return DownloadDeleteAttempt(
                        outcome = DownloadDeleteOutcome.SUPERSEDED,
                        detail = "下载意图缺少可验证的删除代次",
                    )
                } else {
                    current.taskGeneration
                }
        if (current.taskGeneration != expectedGeneration) {
            return DownloadDeleteAttempt(
                outcome = DownloadDeleteOutcome.SUPERSEDED,
                detail = "下载意图删除代次已被替换",
                expectedGeneration = expectedGeneration,
            )
        }
        return try {
            if (deleteIntentAtGeneration(itemId, expectedGeneration)) {
                DownloadDeleteAttempt(
                    outcome = DownloadDeleteOutcome.SUCCEEDED,
                    expectedGeneration = expectedGeneration,
                )
            } else {
                DownloadDeleteAttempt(
                    outcome = DownloadDeleteOutcome.FAILED,
                    detail = "下载意图删除未确认",
                    expectedGeneration =
                        database.getDownloadIntent(itemId)?.taskGeneration ?: expectedGeneration,
                )
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            DownloadDeleteAttempt(
                outcome = DownloadDeleteOutcome.FAILED,
                detail = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName,
                expectedGeneration =
                    database.getDownloadIntent(itemId)?.taskGeneration ?: expectedGeneration,
            )
        }
    }

    private suspend fun deleteSourceForReceipt(
        sourceId: String,
        request: DownloadDeleteRequest,
        resumed: Boolean,
    ): DownloadDeleteAttempt =
        lifecycleControl.withSourceOperation(sourceId) {
            val current =
                database.getSource(sourceId)
                    ?: return@withSourceOperation DownloadDeleteAttempt(
                        outcome = DownloadDeleteOutcome.SUCCEEDED
                    )
            if (current.itemId != request.itemId) {
                return@withSourceOperation DownloadDeleteAttempt(
                    outcome = DownloadDeleteOutcome.SUPERSEDED,
                    detail = "下载来源身份已被替换",
                    expectedGeneration = request.expectedGeneration,
                )
            }
            val requestedGeneration = request.expectedGeneration
            val currentStatus = current.taskStatus.toTaskStatus()
            val deletionGeneration =
                if (resumed) {
                    requestedGeneration
                        ?: if (currentStatus == DownloadTaskStatus.DELETING) {
                            return@withSourceOperation DownloadDeleteAttempt(
                                outcome = DownloadDeleteOutcome.FAILED,
                                detail = "缺少可验证的删除代次，等待启动扫描",
                            )
                        } else {
                            return@withSourceOperation DownloadDeleteAttempt(
                                outcome = DownloadDeleteOutcome.SUPERSEDED,
                                detail = "当前 source 已不是待删除代次",
                            )
                        }
                } else {
                    if (
                        requestedGeneration != null && current.taskGeneration != requestedGeneration
                    ) {
                        return@withSourceOperation DownloadDeleteAttempt(
                            outcome = DownloadDeleteOutcome.SUPERSEDED,
                            detail = "删除代次已被新入队替换",
                            expectedGeneration = requestedGeneration,
                        )
                    }
                    current.taskGeneration
                }
            if (
                resumed &&
                    (current.taskGeneration != deletionGeneration ||
                        currentStatus != DownloadTaskStatus.DELETING)
            ) {
                return@withSourceOperation DownloadDeleteAttempt(
                    outcome = DownloadDeleteOutcome.SUPERSEDED,
                    detail = "删除代次已完成或被新入队替换",
                    expectedGeneration = deletionGeneration,
                )
            }
            try {
                if (deleteTaskAtGenerationSerialized(sourceId, deletionGeneration)) {
                    DownloadDeleteAttempt(
                        outcome = DownloadDeleteOutcome.SUCCEEDED,
                        expectedGeneration = deletionGeneration,
                    )
                } else {
                    val persisted = database.getSource(sourceId)
                    if (persisted == null) {
                        DownloadDeleteAttempt(
                            outcome = DownloadDeleteOutcome.SUCCEEDED,
                            expectedGeneration = deletionGeneration,
                        )
                    } else if (persisted.taskGeneration != deletionGeneration) {
                        DownloadDeleteAttempt(
                            outcome = DownloadDeleteOutcome.SUPERSEDED,
                            detail = "删除代次已被新入队替换",
                            expectedGeneration = deletionGeneration,
                        )
                    } else {
                        DownloadDeleteAttempt(
                            outcome = DownloadDeleteOutcome.FAILED,
                            detail = "删除未确认",
                            expectedGeneration = deletionGeneration,
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                DownloadDeleteAttempt(
                    outcome = DownloadDeleteOutcome.FAILED,
                    detail = error.message?.takeIf(String::isNotBlank) ?: error::class.simpleName,
                    expectedGeneration =
                        database.getSource(sourceId)?.taskGeneration ?: deletionGeneration,
                )
            }
        }

    override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) {
        val source = findSourceByHandle(downloadId) ?: return
        cancelTaskById(source.id)
    }

    override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) {
        withContext(Dispatchers.IO) {
            lifecycleControl.withSourceOperation(source.id) {
                val current = database.getSource(source.id) ?: error("下载来源不存在或已清理：${source.id}")
                val persisted =
                    when (val resolution = persistedOriginForSource(item.id, source.id)) {
                        PersistedOriginResolution.MissingLegacy ->
                            error("下载来源缺少完整持久身份：${source.id}")
                        is PersistedOriginResolution.UniqueComplete -> resolution.identity
                        is PersistedOriginResolution.Ambiguous ->
                            error("下载来源持久身份歧义：${source.id}/" + resolution.matchingRecordCount)
                        is PersistedOriginResolution.FailClosed ->
                            error("下载来源持久身份不可证明：${source.id}/${resolution.detail}")
                    }
                val expectedKind =
                    if ((item as? FindroidMovie)?.kind.equals("AUDIO", ignoreCase = true)) {
                        DownloadMediaKind.MUSIC
                    } else {
                        DownloadMediaKind.VIDEO
                    }
                if (
                    current.itemId != item.id ||
                        current.type != FindroidSourceType.LOCAL ||
                        source.type != FindroidSourceType.LOCAL ||
                        current.path != source.path ||
                        current.taskGeneration <= 0L ||
                        persisted.kind != expectedKind
                ) {
                    error("下载来源身份与当前代次不匹配：${source.id}")
                }
                if (
                    !deleteTaskAtGenerationSerialized(
                        source.id,
                        current.taskGeneration,
                        knownItem = item,
                    )
                ) {
                    error("下载来源删除未确认：${source.id}")
                }
            }
        }
    }

    private suspend fun deleteItem(
        item: FindroidItem,
        source: FindroidSource,
        expectedGeneration: Long,
    ): Boolean =
        itemMutationLock(item.id).withLock {
            if (database.getSource(source.id)?.taskGeneration != expectedGeneration) {
                return@withLock false
            }
            deleteItemUnderItemLock(item, source)
            true
        }

    private suspend fun deleteItemUnderItemLock(
        item: FindroidItem,
        source: FindroidSource,
    ) {
        val parentOrigin = downloadOriginForSource(item, source.id)
        val mediaStreams = database.getMediaStreamsBySourceId(source.id)
        val files = buildList {
            File(source.path).parentFile?.let { directory ->
                addAll(
                    DownloadCompletionCommitRules.sourceArtifacts(
                        directory = directory,
                        itemId = item.id.toString(),
                        sourceId = source.id,
                    )
                )
            }
            mediaStreams.mapTo(this) { stream -> File(stream.path) }
        }
        for (file in files) {
            if (file.exists() && !file.delete()) {
                error("无法删除下载文件：${file.path}")
            }
        }
        if (!deletePartialSidecar(source.path)) {
            error("无法删除下载断点信息：${source.path}")
        }

        database.deleteMediaStreamsBySourceId(source.id)
        database.deleteSource(source.id)
        removeSidecarForSource(itemId = item.id, sourceId = source.id)
        if (database.getSources(item.id).isNotEmpty()) return

        when (item) {
            is FindroidMovie -> database.deleteMovie(item.id)
            is FindroidEpisode -> {
                parentImageMutationLock.withLock {
                    database.deleteEpisode(item.id)
                    val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                    if (remainingEpisodes.isEmpty()) {
                        database.deleteSeason(item.seasonId)
                        cancelParentImagesDownloader(
                            kind = ImageDownloadParentKind.SEASON,
                            itemId = item.seasonId,
                            origin = parentOrigin,
                        )
                        database.deleteUserData(item.seasonId)
                        File(context.filesDir, "trickplay/${item.seasonId}").deleteRecursively()
                        File(context.filesDir, "images/${item.seasonId}").deleteRecursively()
                        val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                        if (remainingSeasons.isEmpty()) {
                            database.deleteShow(item.seriesId)
                            cancelParentImagesDownloader(
                                kind = ImageDownloadParentKind.SHOW,
                                itemId = item.seriesId,
                                origin = parentOrigin,
                            )
                            database.deleteUserData(item.seriesId)
                            File(context.filesDir, "trickplay/${item.seriesId}").deleteRecursively()
                            File(context.filesDir, "images/${item.seriesId}").deleteRecursively()
                        }
                    }
                }
            }
        }

        database.deleteUserData(item.id)
        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
        imageCacheStore.delete(item.id)
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
            val completeSidecarRecords =
                when (val snapshot = sidecar.allRecords()) {
                    DownloadMediaReadResult.Missing -> emptyList()
                    is DownloadMediaReadResult.Complete -> snapshot.records
                    is DownloadMediaReadResult.Unreadable -> {
                        Timber.w(snapshot.error, "刷新下载任务时 sidecar 不可读，暂不折叠媒体记录")
                        emptyList()
                    }
                    is DownloadMediaReadResult.Corrupt -> {
                        Timber.w(
                            "刷新下载任务时 sidecar 损坏，暂不折叠媒体记录：行 %s",
                            snapshot.invalidLineNumbers.joinToString(),
                        )
                        emptyList()
                    }
                }
            val mediaRecords = completeSidecarRecords.associateBy {
                it.canonicalIdentity().canonicalKey
            }
            val pendingIntentRecordsByItemId =
                completeSidecarRecords
                    .filter { it.kind == DownloadMediaKind.MUSIC && it.sourceId.isNullOrBlank() }
                    .groupBy(DownloadMediaRecord::itemId)
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
                val record =
                    mediaRecords.values.firstOrNull {
                        it.itemId == source.itemId.toString() && it.sourceId == source.id
                    }
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
                        taskGeneration = source.taskGeneration,
                        serverId = record?.serverId,
                        userId = record?.userId,
                    )
            }

            val sourceItemIds = tasks.mapTo(mutableSetOf()) { it.itemId }
            tasks +=
                database
                    .getDownloadIntents()
                    .asSequence()
                    .filterNot { it.itemId in sourceItemIds }
                    .map { intent ->
                        val pendingRecords = pendingIntentRecordsByItemId[intent.itemId.toString()]
                        val pendingRecord =
                            pendingRecords
                                ?.distinctBy { it.canonicalIdentity().canonicalKey }
                                ?.singleOrNull()
                        DownloadTask(
                            itemId = intent.itemId,
                            sourceId = intentSourceId(intent.itemId),
                            name = intent.name,
                            path = "",
                            downloadId = null,
                            status =
                                if (intent.status == DownloadIntentStatus.FAILED.name) {
                                    DownloadTaskStatus.FAILED
                                } else {
                                    DownloadTaskStatus.PENDING
                                },
                            failureReason = intent.failureReason.toFailureReason(),
                            downloadedBytes = 0L,
                            totalBytes = 0L,
                            updatedAt = intent.updatedAt,
                            mediaKind = DownloadMediaKind.MUSIC,
                            albumName = intent.albumName,
                            artist = intent.artist,
                            retryCount = 0,
                            nextRetryAt = 0L,
                            taskGeneration = intent.taskGeneration,
                            serverId = pendingRecord?.serverId,
                            userId = pendingRecord?.userId,
                        )
                    }
                    .toList()

            val downloadedItemIds = database.getDownloadedItemIds().toSet()
            tasks.removeAll { task ->
                task.status != DownloadTaskStatus.FAILED && task.itemId in downloadedItemIds
            }

            if (tasks.any { it.status == DownloadTaskStatus.PENDING }) {
                ensureEngineRunning()
            }
            tasks.sortedByDescending { it.updatedAt }
        }

    override suspend fun recoverOnStartup(): Int =
        withContext(Dispatchers.IO) {
            try {
                resumePendingDeleteBatches()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Timber.w(error, "恢复未完成删除批次失败")
            }
            val now = System.currentTimeMillis()
            var recovered = 0
            for (source in database.getAllSources()) {
                val status = source.taskStatus.toTaskStatus()
                if (status != DownloadTaskStatus.COMPLETED && status != DownloadTaskStatus.FAILED) {
                    continue
                }
                if (!hasInterruptedFinalization(source)) continue
                if (
                    database.setSourceTaskStatusIfCurrent(
                        source.id,
                        source.taskGeneration,
                        DownloadTaskStatus.PENDING.name,
                        null,
                        now,
                    ) > 0
                ) {
                    database.setSourceRetryIfCurrent(
                        source.id,
                        source.taskGeneration,
                        source.retryCount,
                        0L,
                        now,
                    )
                    recovered += 1
                }
            }
            for (source in database.getPendingSources()) {
                val status = source.taskStatus.toTaskStatus()
                val pathIsPartial = source.path.endsWith(".download")
                if (status == DownloadTaskStatus.DELETING) {
                    if (deleteTaskAtGeneration(source.id, source.taskGeneration)) {
                        recovered += 1
                    }
                    continue
                }
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
                    if (
                        database.setSourceProgressIfCurrent(
                            source.id,
                            source.taskGeneration,
                            bytes,
                            source.totalBytes,
                            now,
                        ) <= 0 ||
                            database.setSourceTaskStatusIfCurrent(
                                source.id,
                                source.taskGeneration,
                                DownloadTaskStatus.PENDING.name,
                                null,
                                now,
                            ) <= 0
                    ) {
                        continue
                    }
                    // 启动恢复 = 新会话：清掉历史退避时间，立即尝试一次（网络未恢复时由 CONNECTED 约束等待）。
                    database.setSourceRetryIfCurrent(
                        source.id,
                        source.taskGeneration,
                        source.retryCount,
                        0L,
                        now,
                    )
                    recovered += 1
                }
            }
            if (recovered > 0) {
                try {
                    resumePendingDeleteBatches()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (error: Exception) {
                    Timber.w(error, "对账已删除来源的 receipt 失败")
                }
                ensureEngineRunning(forceStart = true)
            }
            val hasLegacyReceivedIntent = database.getReceivedDownloadIntents().isNotEmpty()
            val hasSidecarIntent =
                when (val snapshot = sidecar.allRecords()) {
                    DownloadMediaReadResult.Missing -> false
                    is DownloadMediaReadResult.Complete ->
                        snapshot.records.any {
                            it.kind == DownloadMediaKind.MUSIC &&
                                it.sourceId.isNullOrBlank() &&
                                it.itemId.toUuidOrNull() != null
                        }
                    is DownloadMediaReadResult.Unreadable -> true
                    is DownloadMediaReadResult.Corrupt -> true
                }
            if (hasLegacyReceivedIntent || hasSidecarIntent) {
                launchReceivedIntentProcessor()
            }
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
                val transitioned =
                    lifecycleControl.withCommand(source.id, source.taskGeneration) {
                        val current =
                            database.beginSourceRetry(
                                source.id,
                                source.taskGeneration,
                                now,
                            )
                        DownloadSourceCommandResult(current != null, current?.taskGeneration)
                    } ?: false
                if (transitioned) retried += 1
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

    override suspend fun downloadedMediaRecords(): List<DownloadMediaRecord> =
        withContext(Dispatchers.IO) {
            val completedLocalSources =
                database
                    .getAllSources()
                    .asSequence()
                    .filter { source ->
                        source.type == FindroidSourceType.LOCAL &&
                            DownloadTaskRules.resolveStatus(
                                persistedStatus = source.taskStatus,
                                pathIsPartial = source.path.endsWith(".download"),
                            ) == DownloadTaskStatus.COMPLETED
                    }
                    .map { source -> DownloadedMediaSource(source.itemId, source.id) }
                    .toSet()
            DownloadedMediaRecordRules.completedRecords(
                    records =
                        when (val snapshot = sidecar.allRecords()) {
                            DownloadMediaReadResult.Missing -> emptyList()
                            is DownloadMediaReadResult.Complete -> snapshot.records
                            is DownloadMediaReadResult.Unreadable -> {
                                Timber.w(
                                    snapshot.error,
                                    "读取已完成媒体记录时 sidecar 不可读，保留来源未知",
                                )
                                emptyList()
                            }
                            is DownloadMediaReadResult.Corrupt -> {
                                Timber.w(
                                    "读取已完成媒体记录时 sidecar 损坏，保留来源未知：行 %s",
                                    snapshot.invalidLineNumbers.joinToString(),
                                )
                                emptyList()
                            }
                        },
                    completedLocalSources = completedLocalSources,
                    bookFileSize = { itemId ->
                        File(context.filesDir, "books/$itemId.book").takeIf { it.isFile }?.length()
                            ?: 0L
                    },
                )
                .map { record ->
                    if (!record.displayName.isNullOrBlank()) {
                        record
                    } else {
                        val itemId = record.itemId.toUuidOrNull() ?: return@map record
                        record.copy(displayName = downloadDisplayName(itemId))
                    }
                }
        }

    private suspend fun downloadDisplayName(itemId: UUID): String? =
        runCatching { database.getMovie(itemId)?.name }.getOrNull()
            ?: runCatching { database.getEpisode(itemId)?.name }.getOrNull()
            ?: runCatching { database.getShow(itemId)?.name }.getOrNull()

    override suspend fun activeItemIds(): Set<UUID> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sourceIds =
                    database.getPendingSources().mapNotNull { source ->
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
                val receivedIntentIds = database.getReceivedDownloadIntents().map { it.itemId }
                val runningBookIds =
                    readerRepository.bookDownloads.value
                        .filter { it.status == BookDownloadStatus.RUNNING }
                        .map { it.itemId }
                val downloadedItemIds = database.getDownloadedItemIds().toSet()
                (sourceIds + receivedIntentIds + runningBookIds).filterNotTo(mutableSetOf()) {
                    it in downloadedItemIds
                }
            }
                .getOrElse { emptySet() }
        }

    override suspend fun foregroundInfo(): ForegroundInfo {
        val tasks = snapshotActiveTasks()
        val activeCount = activeItemIds().size
        val notification =
            notifications.buildForeground(task = tasks.firstOrNull(), activeCount = activeCount)
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

    private suspend fun downloadExternalMediaStreams(
        item: FindroidItem,
        source: FindroidSource,
        storageIndex: Int,
        generation: Long,
        persistedOrigin: PersistedOriginResolution,
    ) {
        val storageLocation = context.getExternalFilesDirs(null).getOrNull(storageIndex) ?: return
        val transferCredentials = credentialsForSource(item.id, source.id, persistedOrigin)
        val existing = database.getMediaStreamsBySourceId(source.id).associateBy { it.id }
        val failures = mutableListOf<Exception>()
        for ((ordinal, mediaStream) in
            source.mediaStreams.filter { it.isExternal && it.path != null }.withIndex()) {
            val id =
                DownloadPostCommitRules.externalMediaStreamId(
                    sourceId = source.id,
                    generation = generation,
                    streamIndex = mediaStream.index,
                    ordinal = ordinal,
                )
            val target = File(storageLocation, "downloads/${item.id}.${source.id}.$id")
            if (existing[id]?.path == target.path && target.isFile) continue
            try {
                val partial = File(target.path + ".download")
                database.insertMediaStream(
                    mediaStream.toFindroidMediaStreamDto(id, source.id, target.path)
                )
                downloadTransfer.download(
                    DownloadTransferRequest(
                        url = mediaStream.path!!,
                        token = transferCredentials.accessToken,
                        baseUrl = transferCredentials.baseUrl,
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
                )
                if (target.exists()) target.delete()
                if (partial.renameTo(target)) {
                    database.setMediaStreamPath(id, target.path)
                } else {
                    database.deleteMediaStream(id)
                }
            } catch (error: Exception) {
                failures += error
                Timber.w(error, "下载外部媒体流失败 ${item.id}（${mediaStream.title}）")
            }
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
        val currentIds =
            source.mediaStreams
                .filter { it.isExternal && it.path != null }
                .withIndex()
                .mapTo(mutableSetOf()) { (ordinal, mediaStream) ->
                    DownloadPostCommitRules.externalMediaStreamId(
                        sourceId = source.id,
                        generation = generation,
                        streamIndex = mediaStream.index,
                        ordinal = ordinal,
                    )
                }
        database
            .getMediaStreamsBySourceId(source.id)
            .filter { it.isExternal && it.id !in currentIds }
            .forEach { stale ->
                runCatching {
                    val staleFile = File(stale.path)
                    if (!staleFile.exists() || staleFile.delete()) {
                        database.deleteMediaStream(stale.id)
                    }
                }
                    .onFailure { Timber.w(it, "清理旧代外置媒体流失败：${stale.id}") }
            }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
        origin: JellyfinOrigin?,
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
            val byteArray =
                if (origin == null) {
                    jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)
                } else {
                    originScopedRepository()
                        .getTrickplayDataForOrigin(itemId, trickplayInfo.width, i, origin)
                        ?: throw DownloadHttpException(
                            DownloadFailureReason.SERVER_ERROR,
                            "冻结来源缺少 Trickplay 帧：${origin.serverId}/$itemId/$i",
                        )
                }
            if (byteArray != null) {
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

    private suspend fun startImagesDownloader(
        item: FindroidItem,
        sourceId: String,
        generation: Long,
    ) {
        imageFinalizationScheduler.schedule(item, sourceId, generation)
    }

    private suspend fun startParentImagesDownloader(
        item: FindroidItem,
        origin: FrozenDownloadOrigin,
    ) {
        val kind =
            when (item) {
                is FindroidShow -> ImageDownloadParentKind.SHOW
                is FindroidSeason -> ImageDownloadParentKind.SEASON
                else -> {
                    Timber.w("父级图片跳过：仅节目 / 季可建立父图 owner（%s）", item.id)
                    return
                }
            }
        val serverId = origin.serverId?.takeIf(String::isNotBlank)
        val userId = origin.userId?.takeIf(String::isNotBlank)
        if (serverId == null || userId == null) {
            Timber.w("父级图片跳过：缺少真实 server/user namespace（%s）", item.id)
            return
        }
        imageFinalizationScheduler.scheduleParent(
            item,
            ImageDownloadParentOwner(
                kind = kind,
                itemId = item.id,
                serverId = serverId,
                userId = userId,
            ),
        )
    }

    private suspend fun cancelParentImagesDownloader(
        kind: ImageDownloadParentKind,
        itemId: UUID,
        origin: FrozenDownloadOrigin?,
    ) {
        if (origin == null) {
            Timber.w("父级图片取消跳过：缺少可证明的完整来源（%s）", itemId)
            return
        }
        val serverId = origin.serverId?.takeIf(String::isNotBlank)
        val userId = origin.userId?.takeIf(String::isNotBlank)
        if (serverId == null || userId == null) {
            // key 无法重建时不做猜测；持久父快照已在调用前删除，worker 的存在性 fence 仍会拒绝写入。
            Timber.w("父级图片取消跳过：缺少真实 server/user namespace（%s）", itemId)
            return
        }
        imageFinalizationScheduler.cancelParent(
            ImageDownloadParentOwner(
                kind = kind,
                itemId = itemId,
                serverId = serverId,
                userId = userId,
            )
        )
    }

    // ---------------------------------------------------------------------------------------------
    // 辅助
    // ---------------------------------------------------------------------------------------------

    private suspend fun persistItemSnapshot(
        item: FindroidItem,
        origin: FrozenDownloadOrigin?,
        completionOrigin: JellyfinOrigin?,
    ) {
        val serverId = origin?.serverId?.takeIf(String::isNotBlank)
        val userId =
            origin?.userId?.takeIf(String::isNotBlank)?.let { raw ->
                runCatching { UUID.fromString(raw) }.getOrNull()
            }
        if (serverId == null || userId == null) {
            Timber.w("跳过下载快照：缺少可证明的完整持久身份（%s）", item.id)
            return
        }
        when (item) {
            is FindroidMovie -> database.insertMovie(item.toFindroidMovieDto(serverId))
            is FindroidEpisode -> {
                // W63：整剧 / 全季批量入队时，同剧的节目 / 季快照只解析一次（批内缓存 + 首次并发拉取）。
                val showCacheKey =
                    SnapshotCacheKey(
                        serverId = completionOrigin?.serverId,
                        userId = completionOrigin?.userId?.toString(),
                        itemId = item.seriesId,
                    )
                val seasonCacheKey =
                    SnapshotCacheKey(
                        serverId = completionOrigin?.serverId,
                        userId = completionOrigin?.userId?.toString(),
                        itemId = item.seasonId,
                    )
                val cachedShow = showSnapshotCache[showCacheKey]?.takeIf { it.isFresh() }?.value
                val cachedSeason =
                    seasonSnapshotCache[seasonCacheKey]?.takeIf { it.isFresh() }?.value
                val resolved =
                    if (cachedShow != null && cachedSeason != null) {
                        cachedShow to cachedSeason
                    } else {
                        coroutineScope {
                            val showDeferred =
                                if (cachedShow != null) null
                                else async { showForCompletion(item.seriesId, completionOrigin) }
                            val seasonDeferred =
                                if (cachedSeason != null) null
                                else async { seasonForCompletion(item.seasonId, completionOrigin) }
                            (cachedShow ?: showDeferred!!.await()) to
                                (cachedSeason ?: seasonDeferred!!.await())
                        }
                    }
                val show = resolved.first
                val season = resolved.second
                val now = System.currentTimeMillis()
                if (cachedShow == null) {
                    showSnapshotCache[showCacheKey] = CachedSnapshot(show, now)
                }
                if (cachedSeason == null) {
                    seasonSnapshotCache[seasonCacheKey] = CachedSnapshot(season, now)
                }
                parentImageMutationLock.withLock {
                    database.insertShow(show.toFindroidShowDto(serverId))
                    database.insertSeason(season.toFindroidSeasonDto())
                    database.insertEpisode(item.toFindroidEpisodeDto(serverId))
                    startParentImagesDownloader(show, origin)
                    startParentImagesDownloader(season, origin)
                }
            }
        }
        database.insertUserData(item.toFindroidUserDataDto(userId))
    }

    private suspend fun resolveRemoteSource(
        item: FindroidItem,
        sourceId: String,
        persistedOrigin: PersistedOriginResolution = persistedOriginForSource(item.id, sourceId),
    ): FindroidSource {
        val origin =
            when (val resolution = persistedOrigin) {
                PersistedOriginResolution.MissingLegacy -> null
                is PersistedOriginResolution.UniqueComplete ->
                    resolution.identity.origin.toJellyfinOriginOrNull()
                        ?: throw DownloadHttpException(
                            DownloadFailureReason.SERVER_ERROR,
                            "下载 sidecar 来源身份不完整：${item.id}/$sourceId",
                        )
                is PersistedOriginResolution.Ambiguous ->
                    throw DownloadHttpException(
                        DownloadFailureReason.SERVER_ERROR,
                        "下载 sidecar 来源身份歧义：${item.id}/$sourceId/" + resolution.matchingRecordCount,
                    )
                is PersistedOriginResolution.FailClosed ->
                    throw DownloadHttpException(
                        DownloadFailureReason.SERVER_ERROR,
                        "下载 sidecar 不可证明来源身份：${resolution.detail}",
                    )
            }
        val sources = runCatching {
            if (origin != null) {
                originScopedRepository().getMediaSourcesForOrigin(item.id, origin)
            } else {
                // 无完整 sidecar 的旧记录是显式 legacy 回退，只有这条路径允许 current session。
                jellyfinRepository.getMediaSources(item.id, true)
            }
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
        return sources.singleOrNull { it.id == sourceId }
            ?: throw DownloadHttpException(
                DownloadFailureReason.SERVER_ERROR,
                "服务器未返回该媒体来源（sourceId=$sourceId）",
            )
    }

    private suspend fun segmentsForCompletion(
        itemId: UUID,
        origin: JellyfinOrigin?,
    ): List<FindroidSegment> =
        if (origin == null) {
            jellyfinRepository.getSegments(itemId)
        } else {
            originScopedRepository().getSegmentsForOrigin(itemId, origin)
        }

    private suspend fun showForCompletion(
        itemId: UUID,
        origin: JellyfinOrigin?,
    ): FindroidShow =
        if (origin == null) {
            jellyfinRepository.getShow(itemId)
        } else {
            originScopedRepository().getShowForOrigin(itemId, origin)
        }

    private suspend fun seasonForCompletion(
        itemId: UUID,
        origin: JellyfinOrigin?,
    ): FindroidSeason =
        if (origin == null) {
            jellyfinRepository.getSeason(itemId)
        } else {
            originScopedRepository().getSeasonForOrigin(itemId, origin)
        }

    private suspend fun credentialsForSource(
        itemId: UUID,
        sourceId: String,
        persistedOrigin: PersistedOriginResolution = persistedOriginForSource(itemId, sourceId),
    ): JellyfinOriginCredentials {
        return when (val resolution = persistedOrigin) {
            PersistedOriginResolution.MissingLegacy ->
                JellyfinOriginCredentials(
                    baseUrl = jellyfinRepository.getBaseUrl(),
                    accessToken = jellyfinRepository.getAccessToken(),
                )
            is PersistedOriginResolution.UniqueComplete -> {
                val origin =
                    resolution.identity.origin.toJellyfinOriginOrNull()
                        ?: throw DownloadHttpException(
                            DownloadFailureReason.SERVER_ERROR,
                            "下载 sidecar 来源身份不完整：$itemId/$sourceId",
                        )
                originScopedRepository().credentialsForOrigin(origin)
            }
            is PersistedOriginResolution.Ambiguous ->
                throw DownloadHttpException(
                    DownloadFailureReason.SERVER_ERROR,
                    "下载 sidecar 来源身份歧义：$itemId/$sourceId/" + resolution.matchingRecordCount,
                )
            is PersistedOriginResolution.FailClosed ->
                throw DownloadHttpException(
                    DownloadFailureReason.SERVER_ERROR,
                    "下载 sidecar 不可证明来源身份：${resolution.detail}",
                )
        }
    }

    private fun persistedOriginForSource(
        itemId: UUID,
        sourceId: String,
    ): PersistedOriginResolution {
        val records =
            when (val snapshot = sidecar.allRecords()) {
                DownloadMediaReadResult.Missing -> emptyList()
                is DownloadMediaReadResult.Complete -> snapshot.records
                is DownloadMediaReadResult.Unreadable ->
                    return PersistedOriginResolution.FailClosed(
                        "sidecar 不可读：${snapshot.error.message.orEmpty()}"
                    )
                is DownloadMediaReadResult.Corrupt ->
                    return PersistedOriginResolution.FailClosed(
                        "sidecar 损坏行：${snapshot.invalidLineNumbers.joinToString()}"
                    )
            }
        val matchingRecords =
            records
                .asSequence()
                .filter { record ->
                    record.itemId == itemId.toString() && record.sourceId == sourceId
                }
                .toList()
        if (matchingRecords.isEmpty()) return PersistedOriginResolution.MissingLegacy
        val complete =
            matchingRecords
                .mapNotNull { record ->
                    record
                        .provenance()
                        ?.takeIf { provenance ->
                            provenance.itemId == itemId && provenance.mediaSourceId == sourceId
                        }
                        ?.let { provenance ->
                            PersistedDownloadIdentity(
                                kind = record.kind,
                                origin =
                                    FrozenDownloadOrigin(
                                        serverId = provenance.serverId,
                                        userId = provenance.userId.toString(),
                                    ),
                            )
                        }
                }
                .distinct()
        return complete.singleOrNull()?.let(PersistedOriginResolution::UniqueComplete)
            ?: PersistedOriginResolution.Ambiguous(matchingRecords.size)
    }

    private suspend fun findItem(
        itemId: UUID,
        sourceId: String,
        persistedOrigin: PersistedOriginResolution = persistedOriginForSource(itemId, sourceId),
    ): FindroidItem? {
        val persisted =
            when (val resolution = persistedOrigin) {
                PersistedOriginResolution.MissingLegacy -> return null
                is PersistedOriginResolution.UniqueComplete -> resolution.identity
                is PersistedOriginResolution.Ambiguous ->
                    throw DownloadHttpException(
                        DownloadFailureReason.SERVER_ERROR,
                        "下载 sidecar 来源身份歧义：$itemId/$sourceId/" + resolution.matchingRecordCount,
                    )
                is PersistedOriginResolution.FailClosed ->
                    throw DownloadHttpException(
                        DownloadFailureReason.SERVER_ERROR,
                        "下载 sidecar 不可证明来源身份：${resolution.detail}",
                    )
            }
        val userId = UUID.fromString(checkNotNull(persisted.origin.userId))
        val movie = runCatching { database.getMovie(itemId) }.getOrNull()
        if (movie != null) {
            return hydrateDownloadMovie(movie, persisted.kind, userId)
        }
        val episode = runCatching { database.getEpisode(itemId) }.getOrNull()
        if (episode != null) {
            return hydrateDownloadEpisode(episode, userId)
        }
        return null
    }

    private suspend fun hydrateDownloadMovie(
        movie: FindroidMovieDto,
        kind: DownloadMediaKind,
        userId: UUID,
    ): FindroidMovie {
        val userData = database.getUserDataOrCreateNew(movie.id, userId)
        val sources =
            database.getSources(movie.id).map { source -> source.toFindroidSource(database) }
        val trickplayInfo =
            sources
                .mapNotNull { source ->
                    database.getTrickplayInfo(source.id)?.toFindroidTrickplayInfo()?.let { info ->
                        source.id to info
                    }
                }
                .toMap()
        return FindroidMovie(
            id = movie.id,
            name = movie.name,
            originalTitle = movie.originalTitle,
            overview = movie.overview,
            sources = sources,
            played = userData.played,
            favorite = userData.favorite,
            canPlay = true,
            canDownload = false,
            runtimeTicks = movie.runtimeTicks,
            playbackPositionTicks = userData.playbackPositionTicks,
            premiereDate = movie.premiereDate,
            people = emptyList(),
            genres = emptyList(),
            communityRating = movie.communityRating,
            officialRating = movie.officialRating,
            status = movie.status,
            productionYear = movie.productionYear,
            endDate = movie.endDate,
            trailer = null,
            images = FindroidImages(),
            chapters = movie.chapters.orEmpty(),
            trickplayInfo = trickplayInfo,
            kind = if (kind == DownloadMediaKind.MUSIC) "AUDIO" else null,
        )
    }

    private suspend fun hydrateDownloadEpisode(
        episode: FindroidEpisodeDto,
        userId: UUID,
    ): FindroidEpisode {
        val userData = database.getUserDataOrCreateNew(episode.id, userId)
        val sources =
            database.getSources(episode.id).map { source -> source.toFindroidSource(database) }
        val trickplayInfo =
            sources
                .mapNotNull { source ->
                    database.getTrickplayInfo(source.id)?.toFindroidTrickplayInfo()?.let { info ->
                        source.id to info
                    }
                }
                .toMap()
        return FindroidEpisode(
            id = episode.id,
            name = episode.name,
            originalTitle = null,
            overview = episode.overview,
            indexNumber = episode.indexNumber,
            indexNumberEnd = episode.indexNumberEnd,
            parentIndexNumber = episode.parentIndexNumber,
            sources = sources,
            played = userData.played,
            favorite = userData.favorite,
            canPlay = true,
            canDownload = false,
            runtimeTicks = episode.runtimeTicks,
            playbackPositionTicks = userData.playbackPositionTicks,
            premiereDate = episode.premiereDate,
            seriesId = episode.seriesId,
            seriesName = episode.seriesName,
            seasonId = episode.seasonId,
            seasonName = null,
            communityRating = episode.communityRating,
            people = emptyList(),
            images = FindroidImages(),
            chapters = episode.chapters.orEmpty(),
            trickplayInfo = trickplayInfo,
        )
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

    private fun itemMutationLock(itemId: UUID): Mutex =
        itemMutationLocks[(itemId.hashCode() and Int.MAX_VALUE) % itemMutationLocks.size]

    private fun removeSidecarForSource(itemId: UUID, sourceId: String) {
        when (val snapshot = sidecar.allRecords()) {
            DownloadMediaReadResult.Missing -> Unit
            is DownloadMediaReadResult.Complete ->
                snapshot.records
                    .filter { it.itemId == itemId.toString() && it.sourceId == sourceId }
                    .forEach { sidecar.remove(it.canonicalIdentity()) }
            is DownloadMediaReadResult.Unreadable ->
                Timber.w(snapshot.error, "删除下载来源时 sidecar 不可读，拒绝推断缺失记录")
            is DownloadMediaReadResult.Corrupt ->
                Timber.w(
                    "删除下载来源时 sidecar 损坏，拒绝推断缺失记录：行 %s",
                    snapshot.invalidLineNumbers.joinToString(),
                )
        }
    }

    private fun deleteSourceCompletionArtifacts(source: FindroidSourceDto): Boolean {
        val parent = File(source.path).parentFile ?: return deleteCompletionArtifacts(source.path)
        return DownloadCompletionCommitRules.sourceArtifacts(
                directory = parent,
                itemId = source.itemId.toString(),
                sourceId = source.id,
            )
            .all { artifact -> !artifact.exists() || artifact.delete() }
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
            taskGeneration = source.taskGeneration,
        )
    }

    private suspend fun snapshotActiveTasks(): List<DownloadTask> =
        withContext(Dispatchers.IO) {
            activeJobs.keys
                .mapNotNull { database.getSource(it) }
                .map { buildTask(it, DownloadTaskStatus.RUNNING) }
        }

    private fun updateForegroundNotificationNow() {
        val now = System.currentTimeMillis()
        if (now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) return
        lastNotificationAt = now
        scope.launch {
            val active = activeItemIds().size
            if (active == 0) {
                lastNotificationAt = 0L
                return@launch
            }
            val first = snapshotActiveTasks().firstOrNull()
            notifications.notifyForeground(task = first, activeCount = active)
        }
    }

    private fun networkPolicyAllows(): Boolean {
        val manager =
            runCatching { context.getSystemService(ConnectivityManager::class.java) }.getOrNull()
                ?: return true
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
            database.setSourceRetryIfCurrent(
                source.id,
                source.taskGeneration,
                source.retryCount,
                0L,
                now,
            )
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

    private data class SnapshotCacheKey(
        val serverId: String?,
        val userId: String?,
        val itemId: UUID,
    )

    /** W63：节目 / 季快照缓存条目（TTL 内同剧批量入队只网络拉取一次）。 */
    private data class CachedSnapshot<T>(val value: T, val savedAt: Long) {
        fun isFresh(): Boolean = System.currentTimeMillis() - savedAt <= SNAPSHOT_TTL_MS
    }

    internal companion object {
        fun createForTest(
            context: Context,
            database: ServerDatabaseDao,
            jellyfinRepository: JellyfinRepository,
            readerRepository: ReaderRepository,
            appPreferences: AppPreferences,
            imageCacheStore: ImageCacheStore,
            workManager: WorkManager,
            httpClient: OkHttpClient,
            notifications: DownloadNotificationGateway,
            registerNetworkCallback: (ConnectivityManager.NetworkCallback) -> Unit,
            externalStorageState: (File) -> String = { Environment.getExternalStorageState(it) },
            availableBytes: (File) -> Long = { directory -> StatFs(directory.path).availableBytes },
            workGateway: DownloadWorkGateway? = null,
            downloadTransfer: DownloadTransfer? = null,
            imageFinalizationScheduler: DownloadImageFinalizationScheduler? = null,
        ): DownloaderImpl =
            DownloaderImpl(
                context = context,
                database = database,
                jellyfinRepository = jellyfinRepository,
                readerRepository = readerRepository,
                appPreferences = appPreferences,
                imageCacheStore = imageCacheStore,
                workManager = workManager,
                httpClient = httpClient,
                notifications = notifications,
                registerNetworkCallback = registerNetworkCallback,
                externalStorageState = externalStorageState,
                availableBytes = availableBytes,
                workGatewayOverride = workGateway,
                downloadTransferOverride = downloadTransfer,
                imageFinalizationSchedulerOverride = imageFinalizationScheduler,
            )

        /** 自研引擎写入 sources 行的版本标记（旧 DownloadManager 行为 0）。 */
        const val ENGINE_VERSION = 1

        /** 进度落库 / 通知刷新间隔。 */
        const val PERSIST_INTERVAL_MS = 1_000L
        const val NOTIFICATION_INTERVAL_MS = 1_000L

        /** W63：节目 / 季快照缓存有效期。 */
        const val SNAPSHOT_TTL_MS = 10 * 60 * 1_000L

        /** W84：持久化接收意图的后台解析并发上限。分片执行但不会截断批次。 */
        const val INTENT_RESOLVE_CONCURRENCY = 6
    }
}

/** W50 唯一下载引擎任务名。 */
internal const val DOWNLOAD_ENGINE_WORK_NAME = "downloadEngine"

/** sourceId → 稳定的 UI 句柄（替代旧 DownloadManager id 语义，不持久化数值）。 */
internal fun downloadTaskHandle(sourceId: String): Long =
    sourceId.hashCode().toLong() and 0xFFFFFFFFL

internal const val INTENT_SOURCE_PREFIX = "intent:"

internal fun intentSourceId(itemId: UUID): String = "$INTENT_SOURCE_PREFIX$itemId"

internal class DownloadIntentMutations(
    private val beginCancellation: suspend (UUID, Long, Long) -> DownloadIntentDto?,
    private val beginDeletion: suspend (UUID, Long, Long) -> DownloadIntentDto?,
) {
    suspend fun cancel(
        itemId: UUID,
        expectedGeneration: Long,
        updatedAt: Long,
    ): DownloadIntentDto? = beginCancellation(itemId, expectedGeneration, updatedAt)

    suspend fun delete(
        itemId: UUID,
        expectedGeneration: Long,
        updatedAt: Long,
    ): DownloadIntentDto? = beginDeletion(itemId, expectedGeneration, updatedAt)
}

/** 自研引擎状态 → DownloadManager 常量（旧 UI 轮询兼容；仅常量映射，不调用系统下载器）。 */
internal fun DownloadTaskStatus.toDownloadManagerStatus(): Int =
    when (this) {
        DownloadTaskStatus.PENDING -> DownloadManager.STATUS_PENDING
        DownloadTaskStatus.RUNNING -> DownloadManager.STATUS_RUNNING
        DownloadTaskStatus.PAUSED -> DownloadManager.STATUS_PAUSED
        DownloadTaskStatus.COMPLETED -> DownloadManager.STATUS_SUCCESSFUL
        DownloadTaskStatus.FAILED -> DownloadManager.STATUS_FAILED
        DownloadTaskStatus.DELETING -> DownloadManager.STATUS_FAILED
    }
