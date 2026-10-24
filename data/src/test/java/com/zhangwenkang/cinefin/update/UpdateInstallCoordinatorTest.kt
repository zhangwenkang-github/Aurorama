package com.zhangwenkang.cinefin.update

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateInstallCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun verificationFailureDeletesCandidateMarksFailedAndNeverCallsInstaller() = runBlocking {
        val store = readyStore(fileContents = "abd")
        val transfer = (store.loadTransfer() as UpdateStateReadResult.Present).value
        val complete = store.completeFile(transfer)
        var gatewayCalls = 0
        val coordinator =
            coordinator(
                store = store,
                gateway =
                    UpdateInstallerGateway {
                        gatewayCalls++
                        UpdateInstallerLaunchResult.Launched(command = complete)
                    },
            )

        val result = coordinator.verifyAndLaunch(transfer.updateId)

        assertEquals(
            UpdateInstallAttemptResult.VerificationFailed(
                failure = UpdateVerificationFailure.DIGEST_MISMATCH,
                cleanupSucceeded = true,
            ),
            result,
        )
        assertEquals(0, gatewayCalls)
        assertFalse(complete.exists())
        val failed = (store.loadTransfer() as UpdateStateReadResult.Present).value
        assertEquals(UpdateTransferPhase.FAILED, failed.phase)
        assertEquals(UpdateFailureCode.INVALID_RESPONSE, failed.failureCode)
    }

    @Test
    fun verificationSuccessCallsInstallerOnlyAfterReturningVerifiedCandidate() = runBlocking {
        val store = readyStore(fileContents = "abc")
        val transfer = (store.loadTransfer() as UpdateStateReadResult.Present).value
        var launchedFile: File? = null
        val coordinator =
            coordinator(
                store = store,
                gateway =
                    UpdateInstallerGateway { file ->
                        launchedFile = file
                        UpdateInstallerLaunchResult.Launched(command = file)
                    },
            )

        val prepared = coordinator.prepare(transfer.updateId)
        assertEquals(
            UpdateInstallPreparationResult.Prepared(
                updateId = transfer.updateId,
                apkFile = store.completeFile(transfer),
                candidateVersionCode = 5L,
                installedVersionCode = 4L,
            ),
            prepared,
        )
        assertEquals(null, launchedFile)

        val attempted = coordinator.verifyAndLaunch(transfer.updateId)

        assertEquals(
            UpdateInstallAttemptResult.Launched(
                command = store.completeFile(transfer),
                baselineVersionCode = 4L,
            ),
            attempted,
        )
        assertEquals(store.completeFile(transfer), launchedFile)
        assertTrue(launchedFile!!.isFile)
    }

    @Test
    fun absentOrMismatchedTransferFailsWithoutCallingInstaller() = runBlocking {
        val store = readyStore(fileContents = "abc")
        var gatewayCalls = 0
        val coordinator =
            coordinator(
                store = store,
                gateway =
                    UpdateInstallerGateway {
                        gatewayCalls++
                        UpdateInstallerLaunchResult.Launched(command = File("unused"))
                    },
            )

        assertTrue(
            coordinator.verifyAndLaunch("0".repeat(64)) is UpdateInstallAttemptResult.StateFailure
        )
        assertEquals(0, gatewayCalls)
    }

    @Test
    fun installedVersionReadClassifiesSuccessOnlyWhenVersionIncreases() {
        assertEquals(
            UpdateInstallReturnResult.Installed,
            classifyInstallReturn(baselineVersionCode = 4L) { 5L },
        )
        assertEquals(
            UpdateInstallReturnResult.CancelledOrUnchanged,
            classifyInstallReturn(baselineVersionCode = 4L) { 4L },
        )
        assertEquals(
            UpdateInstallReturnResult.Failed,
            classifyInstallReturn(baselineVersionCode = 4L) { 3L },
        )
    }

    @Test
    fun installedVersionReadFailureIsNeverReportedAsInstalled() {
        val result =
            classifyInstallReturn(baselineVersionCode = 4L) {
                throw IllegalStateException("PackageManager unavailable")
            }

        assertEquals(UpdateInstallReturnResult.Failed, result)
    }

    private fun readyStore(fileContents: String): UpdateStateStore {
        val release = releaseIdentity()
        val store = UpdateStateStore(temporaryFolder.newFolder(), clock = { 1_000L })
        store.saveDiscoveredRelease(release, 1_000L)
        store.beginDownload(release, 1_000L)
        val transfer = (store.loadTransfer() as UpdateStateReadResult.Present).value
        store.partFile(transfer).writeText(fileContents)
        assertEquals(
            UpdateStateWriteResult.Success,
            store.finalizePart(transfer, nowEpochMs = 1_500L),
        )
        return store
    }

    private fun coordinator(
        store: UpdateStateStore,
        gateway: UpdateInstallerGateway<File>,
    ): UpdateInstallCoordinator<File> =
        UpdateInstallCoordinator(
            store = store,
            installedReader =
                object : InstalledApplicationReader {
                    override fun readInstalledApplication() = installedPackage()

                    override fun readInstalledVersionCode(): Long = 4L
                },
            metadataReader =
                UpdateApkMetadataReader {
                    CandidateApkMetadata(
                        packageName = "io.github.zhangwenkang.aurorama.debug",
                        versionCode = 5L,
                        versionName = "1.4.0",
                        minSdk = 28,
                        signerCertificateDigests = setOf("installed-signer"),
                    )
                },
            gateway = gateway,
            deviceSdk = { 36 },
        )

    private fun installedPackage() =
        InstalledAppMetadata(
            packageName = "io.github.zhangwenkang.aurorama.debug",
            versionCode = 4L,
            versionName = "1.3.0",
            signerCertificateDigests = setOf("installed-signer"),
        )

    private fun releaseIdentity(): UpdateReleaseIdentity {
        val version = "1.4.0"
        val assetName = "Aurorama-$version-arm64-v8a.apk"
        return UpdateReleaseIdentity(
            releaseTag = "v$version",
            version = version,
            assetName = assetName,
            assetSize = 3L,
            assetDigest = "sha256:ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            downloadUrl = releaseAssetUrl(version, assetName),
        )
    }
}
