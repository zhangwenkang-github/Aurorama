package com.zhangwenkang.cinefin

import android.app.Application
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.crossfade
import coil3.svg.SvgDecoder
import com.google.android.material.color.DynamicColors
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.di.IdentityHydrationGate
import com.zhangwenkang.cinefin.identity.IdentityReadLeasePort
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.network.SharedPreferencesCertificateTrustStore
import com.zhangwenkang.cinefin.network.buildCertificateAwareOkHttpClient
import com.zhangwenkang.cinefin.presentation.update.launchAutomaticUpdateCheck
import com.zhangwenkang.cinefin.repository.UserPolicyStore
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.ImageCacheSize
import com.zhangwenkang.cinefin.update.UpdateWorkScheduler
import com.zhangwenkang.cinefin.utils.DownloadDeleteTraceInstaller
import com.zhangwenkang.cinefin.utils.DownloadDeleteTraceRuntime
import com.zhangwenkang.cinefin.utils.Downloader
import com.zhangwenkang.cinefin.work.MpvCleanupWorker
import com.zhangwenkang.cinefin.work.ReaderProgressSyncWorker
import com.zhangwenkang.cinefin.work.SyncWorker
import com.zhangwenkang.cinefin.work.WorkManagerUserDataSyncScheduler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import timber.log.Timber

@HiltAndroidApp
class BaseApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {
    @Inject lateinit var appPreferences: AppPreferences

    @Inject lateinit var identityStartupProjector: IdentityStartupProjector

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var downloader: Downloader

    @Inject
    lateinit var downloadDeleteTraceInstallers:
        Set<@JvmSuppressWildcards DownloadDeleteTraceInstaller>

    @Inject lateinit var updateWorkScheduler: UpdateWorkScheduler

    /** W8-T2：当前用户写权限缓存（启动会话恢复后刷新；T5/T6 写入口消费）。 */
    @Inject lateinit var userPolicyStore: UserPolicyStore

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
            downloadDeleteTraceInstallers.forEach(DownloadDeleteTraceRuntime::install)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            val mode =
                when (appPreferences.getValue(appPreferences.theme)) {
                    "light" -> AppCompatDelegate.MODE_NIGHT_NO
                    // 深色主题 + 存量 "dark"：固定深色外观
                    "aurora",
                    "amoled",
                    "lumen",
                    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
                    // "dynamic" / "system" / 未知：跟随系统明暗
                    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                }
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        if (appPreferences.getValue(appPreferences.dynamicColors)) {
            DynamicColors.applyToActivitiesIfAvailable(this)
        }

        val workManager = WorkManager.getInstance(applicationContext)

        CoroutineScope(Dispatchers.IO).launch {
            val snapshot = identityStartupProjector.hydrate()
            if (
                snapshot.status == IdentityStateStatus.READY &&
                    !snapshot.selection.serverId.isNullOrBlank()
            ) {
                scheduleUserDataSync(workManager)
                scheduleReaderProgressSync(workManager)
                // W8-T2：启动恢复会话后拉一次当前用户 UserPolicy（删除 / 元数据入口的前置门控）。
                if (!snapshot.selection.offlineMode) {
                    runCatching { userPolicyStore.refresh() }
                        .onFailure { Timber.w(it, "UserPolicy 拉取失败") }
                }
            }
        }
        launchAutomaticUpdateCheck(CoroutineScope(Dispatchers.IO), updateWorkScheduler).start()

        // W50 下载引擎启动恢复：旧引擎进行中任务标记「需重下」；自研引擎中断任务从残片继续。
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { downloader.recoverOnStartup() }.onFailure { Timber.w(it, "下载引擎启动恢复失败") }
        }

        if (!appPreferences.getValue(appPreferences.mpvMigrated)) {
            scheduleMpvCleanup(workManager)
        }
    }

    @OptIn(ExperimentalCoilApi::class, ExperimentalTime::class)
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        // 自签证书支持：图片请求与 API 请求共用「默认校验 + 用户确认过的指纹」同一套策略。
        val imageHttpClient =
            buildCertificateAwareOkHttpClient(
                OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build(),
                SharedPreferencesCertificateTrustStore(context),
            )
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageHttpClient },
                        cacheStrategy = { CacheControlCacheStrategy() },
                    )
                )
                add(SvgDecoder.Factory())
            }
            .diskCachePolicy(
                if (appPreferences.getValue(appPreferences.imageCache)) CachePolicy.ENABLED
                else CachePolicy.DISABLED
            )
            .diskCache {
                val builder =
                    DiskCache.Builder()
                        .directory(context.cacheDir.resolve("image_cache").toOkioPath())
                val maxSizeBytes =
                    ImageCacheSize.maxDiskCacheBytes(
                        appPreferences.getValue(appPreferences.imageCacheSize)
                    )
                if (maxSizeBytes != null) {
                    // 显式上限：Coil 走 maxSizeBytes 分支，不受 maximumMaxSizeBytes 钳制。
                    builder.maxSizeBytes(maxSizeBytes)
                } else {
                    // 0（默认）= 无固定上限：保留 Coil「可用空间 × 2%（10MB 保底）」，解除 250MB 默认硬顶。
                    builder.maximumMaxSizeBytes(Long.MAX_VALUE)
                }
                builder.build()
            }
            .crossfade(true)
            .build()
    }

    private fun scheduleUserDataSync(workManager: WorkManager) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        val syncWorkRequest =
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = WorkManagerUserDataSyncScheduler.SYNC_USER_DATA_WORK_NAME,
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = syncWorkRequest,
        )
    }

    private fun scheduleMpvCleanup(workManager: WorkManager) {
        val cleanupRequest = OneTimeWorkRequestBuilder<MpvCleanupWorker>().build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "mpv_cleanup",
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = cleanupRequest,
        )
    }

    /**
     * 阅读进度离线队列回传（EB-9）：断网期间的进度先落本地，联网后由 WorkManager 回传。
     *
     * 两条路径：① 启动即尝试一次（进程被杀 / 离线写入后，重新打开应用就能补传）； ② 15 分钟周期兜底（应用不重启也不会漏）。无待同步记录时只读一次本地 JSON，不产生网络请求。
     */
    private fun scheduleReaderProgressSync(workManager: WorkManager) {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        workManager.enqueueUniqueWork(
            uniqueWorkName = "readerProgressSyncNow",
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request =
                OneTimeWorkRequestBuilder<ReaderProgressSyncWorker>()
                    .setConstraints(constraints)
                    .build(),
        )

        val periodicRequest =
            PeriodicWorkRequestBuilder<ReaderProgressSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName = "readerProgressSync",
            existingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP,
            request = periodicRequest,
        )
    }
}

fun interface StartupIdentityPublisher {
    fun publish(baseUrl: String?, accessToken: String?, userId: UUID?)
}

@Singleton
class JellyfinStartupIdentityPublisher @Inject constructor(private val jellyfinApi: JellyfinApi) :
    StartupIdentityPublisher {
    override fun publish(baseUrl: String?, accessToken: String?, userId: UUID?) {
        jellyfinApi.api.update(baseUrl = baseUrl.orEmpty(), accessToken = accessToken)
        jellyfinApi.userId = userId
        check(jellyfinApi.userId == userId) { "Published identity userId mismatch" }
        if (baseUrl != null) {
            check(jellyfinApi.api.baseUrl == baseUrl) { "Published identity baseUrl mismatch" }
            check(jellyfinApi.api.accessToken == accessToken) {
                "Published identity accessToken mismatch"
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
object StartupIdentityModule {
    @Singleton
    @Provides
    fun provideStartupIdentityPublisher(
        publisher: JellyfinStartupIdentityPublisher
    ): StartupIdentityPublisher = publisher
}

/**
 * Validates the canonical READY snapshot against Room before publishing the global API identity.
 */
@Singleton
class IdentityStartupProjector
@Inject
constructor(
    private val gate: IdentityHydrationGate,
    private val identityReadPort: IdentityReadPort,
    private val database: ServerDatabaseDao,
    private val apiPublisher: StartupIdentityPublisher,
) {
    private val mutex = Mutex()

    suspend fun hydrate(): IdentitySnapshot = mutex.withLock { evaluate() }

    private suspend fun evaluate(): IdentitySnapshot {
        var snapshot =
            try {
                gate.hydrate()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                clearPublishedIdentity()
                return unavailableSnapshot(0L)
            }

        repeat(2) {
            when (val resolution = resolve(snapshot)) {
                Resolution.Valid -> return snapshot
                is Resolution.Unavailable -> {
                    clearPublishedIdentity()
                    return unavailableSnapshot(resolution.generation)
                }
                Resolution.Stale -> {
                    snapshot =
                        try {
                            gate.current()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            clearPublishedIdentity()
                            return unavailableSnapshot(snapshot.identityGeneration)
                        }
                }
            }
        }

        clearPublishedIdentity()
        return unavailableSnapshot(snapshot.identityGeneration)
    }

    private suspend fun resolve(snapshot: IdentitySnapshot): Resolution {
        if (snapshot.status != IdentityStateStatus.READY) {
            return Resolution.Unavailable(snapshot.identityGeneration)
        }
        val selection = snapshot.selection
        if (selection.serverId == null) {
            clearPublishedIdentity()
            return Resolution.Valid
        }
        parseUserId(selection) ?: return Resolution.Unavailable(snapshot.identityGeneration)
        parseAddressId(selection) ?: return Resolution.Unavailable(snapshot.identityGeneration)

        val leasePort = identityReadPort as? IdentityReadLeasePort
        if (leasePort != null) {
            val lease =
                try {
                    leasePort.acquireReadyLease()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                } ?: return Resolution.Stale
            return leasePort.withReadyLease(lease) { liveSelection ->
                if (liveSelection != selection) Resolution.Stale else validateAndPublish(snapshot)
            } ?: Resolution.Stale
        }

        val resolution = validateAndPublish(snapshot)
        if (resolution != Resolution.Valid) return resolution
        val current =
            try {
                identityReadPort.current()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                return Resolution.Unavailable(snapshot.identityGeneration)
            }
        return if (current == snapshot) Resolution.Valid else Resolution.Stale
    }

    private suspend fun validateAndPublish(snapshot: IdentitySnapshot): Resolution {
        val selection = snapshot.selection
        val serverId =
            selection.serverId ?: return Resolution.Unavailable(snapshot.identityGeneration)
        val userId =
            parseUserId(selection) ?: return Resolution.Unavailable(snapshot.identityGeneration)
        val addressId =
            parseAddressId(selection) ?: return Resolution.Unavailable(snapshot.identityGeneration)
        val relation =
            try {
                database.getServerWithAddressAndUser(serverId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: return Resolution.Unavailable(snapshot.identityGeneration)
        val address = relation.address ?: return Resolution.Unavailable(snapshot.identityGeneration)
        val user = relation.user ?: return Resolution.Unavailable(snapshot.identityGeneration)
        val valid =
            relation.server.id == serverId &&
                relation.server.currentServerAddressId == addressId &&
                relation.server.currentUserId == userId &&
                address.id == addressId &&
                address.serverId == serverId &&
                address.address.isNotBlank() &&
                user.id == userId &&
                user.serverId == serverId &&
                !user.accessToken.isNullOrBlank()
        if (!valid) return Resolution.Unavailable(snapshot.identityGeneration)

        if (selection.offlineMode) {
            clearPublishedIdentity()
        } else {
            apiPublisher.publish(address.address, user.accessToken, user.id)
        }
        return Resolution.Valid
    }

    private fun parseUserId(selection: IdentitySelection): UUID? =
        selection.userId?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }

    private fun parseAddressId(selection: IdentitySelection): UUID? =
        selection.addressId?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }

    private fun clearPublishedIdentity() {
        apiPublisher.publish(null, null, null)
    }

    private fun unavailableSnapshot(generation: Long): IdentitySnapshot =
        IdentitySnapshot(
            selection = IdentitySelection(),
            identityGeneration = generation,
            status = IdentityStateStatus.RECOVERING,
        )

    private sealed interface Resolution {
        data object Valid : Resolution

        data object Stale : Resolution

        data class Unavailable(val generation: Long) : Resolution
    }
}
