package com.zhangwenkang.cinefin.update

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface UpdateApkMetadataReader {
    fun readCandidateMetadata(apkFile: File): CandidateApkMetadata
}

interface InstalledApplicationReader {
    fun readInstalledApplication(): InstalledAppMetadata

    fun readInstalledVersionCode(): Long
}

fun interface UpdateInstallerGateway<T> {
    fun launch(apkFile: File): UpdateInstallerLaunchResult<T>
}

sealed interface UpdateInstallerLaunchResult<out T> {
    data class Launched<T>(val command: T) : UpdateInstallerLaunchResult<T>

    data class Failure(val reason: String) : UpdateInstallerLaunchResult<Nothing>
}

sealed interface UpdateInstallPreparationResult {
    data class Prepared(
        val updateId: String,
        val apkFile: File,
        val candidateVersionCode: Long,
        val installedVersionCode: Long,
    ) : UpdateInstallPreparationResult

    data class VerificationFailed(
        val failure: UpdateVerificationFailure,
        val cleanupSucceeded: Boolean,
    ) : UpdateInstallPreparationResult

    data class StateFailure(val reason: String) : UpdateInstallPreparationResult
}

sealed interface UpdateInstallAttemptResult<out T> {
    data class Launched<T>(
        val command: T,
        val baselineVersionCode: Long,
    ) : UpdateInstallAttemptResult<T>

    data class VerificationFailed(
        val failure: UpdateVerificationFailure,
        val cleanupSucceeded: Boolean,
    ) : UpdateInstallAttemptResult<Nothing>

    data class StateFailure(val reason: String) : UpdateInstallAttemptResult<Nothing>
}

sealed interface UpdateInstallReturnResult {
    data object Installed : UpdateInstallReturnResult

    data object CancelledOrUnchanged : UpdateInstallReturnResult

    data object Failed : UpdateInstallReturnResult
}

class UpdateInstallCoordinator<T>(
    private val store: UpdateStateStore,
    private val installedReader: InstalledApplicationReader,
    private val metadataReader: UpdateApkMetadataReader,
    private val gateway: UpdateInstallerGateway<T>,
    private val deviceSdk: () -> Int,
) {
    suspend fun prepare(updateId: String): UpdateInstallPreparationResult =
        withContext(Dispatchers.IO) {
            val transfer =
                when (val loaded = store.loadTransfer()) {
                    is UpdateStateReadResult.Present -> loaded.value
                    is UpdateStateReadResult.Invalid ->
                        return@withContext UpdateInstallPreparationResult.StateFailure(
                            loaded.reason
                        )
                    UpdateStateReadResult.Absent ->
                        return@withContext UpdateInstallPreparationResult.StateFailure(
                            "download transfer is absent"
                        )
                }
            if (transfer.updateId != updateId) {
                return@withContext UpdateInstallPreparationResult.StateFailure(
                    "download identity mismatch"
                )
            }
            if (transfer.phase != UpdateTransferPhase.READY_FOR_VERIFICATION) {
                return@withContext UpdateInstallPreparationResult.StateFailure(
                    "download is not ready for verification"
                )
            }
            val installed =
                try {
                    installedReader.readInstalledApplication()
                } catch (exception: Exception) {
                    return@withContext UpdateInstallPreparationResult.StateFailure(
                        "cannot read installed application: ${exception.message}"
                    )
                }
            val completeFile = store.completeFile(transfer)
            val verification =
                verifyUpdatePackageFile(
                    file = completeFile,
                    expectedSize = transfer.assetSize,
                    expectedDigest = transfer.assetDigest,
                    readCandidateMetadata = metadataReader::readCandidateMetadata,
                    installed = installed,
                    deviceSdk = deviceSdk(),
                )
            when (verification) {
                is UpdatePackageVerificationResult.Failure -> {
                    val cleanupSucceeded =
                        runCatching { completeFile.delete() }.getOrDefault(false) ||
                            !completeFile.exists()
                    store.markVerificationFailed(transfer)
                    UpdateInstallPreparationResult.VerificationFailed(
                        failure = verification.failure,
                        cleanupSucceeded = cleanupSucceeded,
                    )
                }
                is UpdatePackageVerificationResult.Success ->
                    UpdateInstallPreparationResult.Prepared(
                        updateId = transfer.updateId,
                        apkFile = completeFile,
                        candidateVersionCode = verification.packageMetadata.versionCode,
                        installedVersionCode = installed.versionCode,
                    )
            }
        }

    suspend fun verifyAndLaunch(updateId: String): UpdateInstallAttemptResult<T> =
        when (val prepared = prepare(updateId)) {
            is UpdateInstallPreparationResult.Prepared ->
                when (val launched = gateway.launch(prepared.apkFile)) {
                    is UpdateInstallerLaunchResult.Launched ->
                        UpdateInstallAttemptResult.Launched(
                            command = launched.command,
                            baselineVersionCode = prepared.installedVersionCode,
                        )
                    is UpdateInstallerLaunchResult.Failure ->
                        UpdateInstallAttemptResult.StateFailure(launched.reason)
                }
            is UpdateInstallPreparationResult.VerificationFailed ->
                UpdateInstallAttemptResult.VerificationFailed(
                    failure = prepared.failure,
                    cleanupSucceeded = prepared.cleanupSucceeded,
                )
            is UpdateInstallPreparationResult.StateFailure ->
                UpdateInstallAttemptResult.StateFailure(prepared.reason)
        }

    fun readInstalledVersionCode(): Long = installedReader.readInstalledVersionCode()
}

fun classifyInstallReturn(
    baselineVersionCode: Long,
    readInstalledVersionCode: () -> Long,
): UpdateInstallReturnResult {
    val current =
        try {
            readInstalledVersionCode()
        } catch (exception: Exception) {
            return UpdateInstallReturnResult.Failed
        }
    return when {
        current > baselineVersionCode -> UpdateInstallReturnResult.Installed
        current == baselineVersionCode -> UpdateInstallReturnResult.CancelledOrUnchanged
        else -> UpdateInstallReturnResult.Failed
    }
}

fun validateUpdateInstallUri(
    applicationId: String,
    apkFile: File,
    updatesDirectory: File,
    uriAuthority: String?,
    uriPathSegments: List<String>,
): Boolean {
    val expectedAuthority = "$applicationId.updateinstall"
    val canonicalFile = runCatching { apkFile.canonicalFile }.getOrNull() ?: return false
    val canonicalUpdates =
        runCatching { updatesDirectory.canonicalFile }.getOrNull() ?: return false
    return uriAuthority == expectedAuthority &&
        uriPathSegments == listOf("validated_updates", canonicalFile.name) &&
        canonicalFile.parentFile == canonicalUpdates &&
        canonicalFile.isFile &&
        canonicalFile.name.matches(Regex("^[0-9a-f]{64}\\.apk$"))
}
