package com.zhangwenkang.cinefin.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

enum class UpdateCheckMode {
    AUTO,
    MANUAL,
}

enum class UpdateCheckFailure {
    NETWORK_ERROR,
    INVALID_RELEASE,
    NO_COMPATIBLE_ASSET,
    STORAGE_ERROR,
    STATE_ERROR,
}

sealed interface UpdateCheckResult {
    data class UpToDate(val checkedAtEpochMs: Long) : UpdateCheckResult

    data class Available(
        val updateId: String,
        val version: String,
    ) : UpdateCheckResult

    data object Gated : UpdateCheckResult

    data object AlreadyRunning : UpdateCheckResult

    data class Failed(val failure: UpdateCheckFailure) : UpdateCheckResult
}

enum class UpdateTerminalResult {
    UP_TO_DATE,
    AVAILABLE,
    READY_FOR_VERIFICATION,
    NETWORK_ERROR,
    STORAGE_ERROR,
    INVALID_RELEASE,
    NO_COMPATIBLE_ASSET,
    STATE_ERROR,
    CHECK_GATED,
    CHECK_IN_PROGRESS,
    CANCELED,
}

fun interface UpdateReleaseSource {
    fun fetchLatestRelease(): ReleaseFetchResult
}

interface UpdateCheckGateStore {
    fun lastAutomaticAttemptAtEpochMs(): Long

    fun lastSuccessfulCheckAtEpochMs(): Long

    fun recordAutomaticAttempt(epochMs: Long): Boolean

    fun recordSuccessfulCheck(epochMs: Long): Boolean
}

interface UpdatePlatformInfo {
    fun installedVersionName(): String

    fun supportedAbis(): List<String>
}

/**
 * 自动 / 手动更新检查的唯一进程级入口。
 *
 * 自动检查在发网前同步持久化 attempt；此后无论网络、HTTP 或确定性解析失败，都依靠该 attempt 占满 24h 门控。手动检查绕过门控，但和自动检查共享同一把
 * single-flight 锁，因此不会叠加第二次网络发现。
 */
class UpdateCheckCoordinator(
    private val source: UpdateReleaseSource,
    private val store: UpdateStateStore,
    private val gate: UpdateCheckGateStore,
    private val platform: UpdatePlatformInfo,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val singleFlight = Mutex()

    suspend fun check(mode: UpdateCheckMode): UpdateCheckResult {
        if (!singleFlight.tryLock()) {
            return UpdateCheckResult.AlreadyRunning
        }
        return try {
            checkLocked(mode)
        } finally {
            singleFlight.unlock()
        }
    }

    fun automaticCheckDelayMillis(nowEpochMs: Long = clock()): Long {
        val attempt = gate.lastAutomaticAttemptAtEpochMs()
        if (attempt <= 0L) return 0L
        val nextAllowed =
            if (attempt > Long.MAX_VALUE - AUTOMATIC_CHECK_INTERVAL_MS) {
                Long.MAX_VALUE
            } else {
                attempt + AUTOMATIC_CHECK_INTERVAL_MS
            }
        return if (nowEpochMs >= nextAllowed) 0L else nextAllowed - nowEpochMs
    }

    private suspend fun checkLocked(mode: UpdateCheckMode): UpdateCheckResult {
        val now = clock()
        if (mode == UpdateCheckMode.AUTO) {
            if (gate.lastAutomaticAttemptAtEpochMs() > 0L && !isAutomaticCheckDue(now)) {
                return UpdateCheckResult.Gated
            }
            if (!gate.recordAutomaticAttempt(now)) {
                return UpdateCheckResult.Failed(UpdateCheckFailure.STORAGE_ERROR)
            }
        }

        val fetched =
            try {
                withContext(Dispatchers.IO) { source.fetchLatestRelease() }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                return UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK_ERROR)
            }

        return when (fetched) {
            is ReleaseFetchResult.Failure -> mappedReleaseFailure(fetched)
            is ReleaseFetchResult.Success -> handleRelease(fetched.release, now)
        }
    }

    private fun handleRelease(
        release: GitHubReleaseDto,
        nowEpochMs: Long,
    ): UpdateCheckResult {
        val decision =
            StableReleasePolicy.evaluate(
                release = release,
                currentVersion = platform.installedVersionName(),
                supportedAbis = platform.supportedAbis(),
            )
        return when (decision) {
            UpdateDecision.UpToDate -> recordCheckSuccess(nowEpochMs)
            is UpdateDecision.UpdateAvailable -> {
                val version = decision.version.versionName
                val identity =
                    UpdateReleaseIdentity(
                        releaseTag = release.tagName,
                        version = version,
                        assetName = decision.asset.name,
                        assetSize = decision.asset.size,
                        assetDigest = decision.asset.digest,
                        downloadUrl = decision.asset.browserDownloadUrl,
                    )
                when (val saved = store.saveDiscoveredRelease(identity, nowEpochMs)) {
                    is UpdateStateWriteResult.Failure ->
                        UpdateCheckResult.Failed(UpdateCheckFailure.STORAGE_ERROR)
                    UpdateStateWriteResult.Success -> {
                        when (val success = recordCheckSuccess(nowEpochMs)) {
                            is UpdateCheckResult.Failed -> success
                            else ->
                                UpdateCheckResult.Available(
                                    updateId = stableUpdateId(identity),
                                    version = version,
                                )
                        }
                    }
                }
            }
            UpdateDecision.InvalidInstalledVersion,
            UpdateDecision.UnsupportedRelease ->
                UpdateCheckResult.Failed(UpdateCheckFailure.INVALID_RELEASE)
            UpdateDecision.NoCompatibleAsset ->
                UpdateCheckResult.Failed(UpdateCheckFailure.NO_COMPATIBLE_ASSET)
        }
    }

    private fun recordCheckSuccess(nowEpochMs: Long): UpdateCheckResult =
        if (gate.recordSuccessfulCheck(nowEpochMs)) {
            UpdateCheckResult.UpToDate(nowEpochMs)
        } else {
            UpdateCheckResult.Failed(UpdateCheckFailure.STORAGE_ERROR)
        }

    private fun mappedReleaseFailure(failure: ReleaseFetchResult.Failure): UpdateCheckResult =
        when (failure.reason) {
            ReleaseFetchFailure.TRANSPORT,
            ReleaseFetchFailure.HTTP_STATUS ->
                UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK_ERROR)
            ReleaseFetchFailure.INVALID_RESPONSE ->
                UpdateCheckResult.Failed(UpdateCheckFailure.INVALID_RELEASE)
        }

    private fun isAutomaticCheckDue(nowEpochMs: Long): Boolean =
        automaticCheckDelayMillis(nowEpochMs) == 0L

    companion object {
        const val AUTOMATIC_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1_000L
    }
}
