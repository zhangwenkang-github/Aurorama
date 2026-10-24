package com.zhangwenkang.cinefin.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun downloadUsesOnlyPersistedStateForTheRequestedUpdateId() = runBlocking {
        val now = 1_000L
        val store = UpdateStateStore(temporaryFolder.newFolder(), clock = { now })
        val release = releaseIdentity()
        store.saveDiscoveredRelease(release, now)
        store.beginDownload(release, now)
        val transfer = (store.loadTransfer() as UpdateStateReadResult.Present).value
        val part = store.partFile(transfer)
        part.writeBytes(byteArrayOf(1, 2, 3))
        var downloadedRelease: UpdateReleaseIdentity? = null
        var observedPartLength = -1L
        val executor = UpdateDownloadExecutor { requested, _ ->
            downloadedRelease = requested
            observedPartLength = store.partFile(transfer).length()
            UpdateDownloadResult.ReadyForVerification(
                updateId = transfer.updateId,
                completeFile = File("unused.apk"),
                bytesWritten = requested.assetSize,
            )
        }
        val repository = repository(store, executor)

        val result = repository.download(transfer.updateId)

        assertEquals(release, downloadedRelease)
        assertEquals(3L, observedPartLength)
        assertEquals(
            transfer.updateId,
            (result as UpdateDownloadResult.ReadyForVerification).updateId,
        )
    }

    @Test
    fun unknownUpdateIdFailsWithoutStartingTransport() = runBlocking {
        val store = UpdateStateStore(temporaryFolder.newFolder(), clock = { 1_000L })
        var downloadCalls = 0
        val repository =
            repository(
                store,
                UpdateDownloadExecutor { _, _ ->
                    downloadCalls++
                    UpdateDownloadResult.Failed(UpdateFailureCode.NETWORK_ERROR, "unexpected")
                },
            )

        val result = repository.download("0".repeat(64))

        assertEquals(
            UpdateFailureCode.STATE_ERROR,
            (result as UpdateDownloadResult.Failed).failureCode,
        )
        assertEquals(0, downloadCalls)
    }

    @Test
    fun cancellationFromDownloaderIsNotConvertedToDomainFailure() = runBlocking {
        val store = UpdateStateStore(temporaryFolder.newFolder(), clock = { 1_000L })
        val release = releaseIdentity()
        store.saveDiscoveredRelease(release, 1_000L)
        store.beginDownload(release, 1_000L)
        val transfer = (store.loadTransfer() as UpdateStateReadResult.Present).value
        val cancellation = CancellationException("cancelled")
        val repository =
            repository(
                store,
                UpdateDownloadExecutor { _, _ -> throw cancellation },
            )

        try {
            repository.download(transfer.updateId)
            throw AssertionError("expected cancellation")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun explicitCancelPersistsCancelledForMatchingActiveTransferAndKeepsPart() = runBlocking {
        val store = UpdateStateStore(temporaryFolder.newFolder(), clock = { 2_000L })
        val release = releaseIdentity()
        store.saveDiscoveredRelease(release, 1_000L)
        store.beginDownload(release, 1_000L)
        val transfer = (store.loadTransfer() as UpdateStateReadResult.Present).value
        val part = store.partFile(transfer)
        part.writeBytes(byteArrayOf(1, 2, 3))
        val repository = repository(store, unexpectedDownloader())

        assertEquals(UpdateCancelResult.Success, repository.cancel(transfer.updateId))

        val cancelled = (store.loadTransfer() as UpdateStateReadResult.Present).value
        assertEquals(UpdateTransferPhase.CANCELLED, cancelled.phase)
        assertEquals(null, cancelled.failureCode)
        assertTrue(part.isFile)
        assertEquals(listOf<Byte>(1, 2, 3), part.readBytes().toList())
        assertTrue(
            store.markFailed(transfer, UpdateFailureCode.NETWORK_ERROR)
                is UpdateStateWriteResult.Failure
        )
        assertEquals(
            UpdateTransferPhase.CANCELLED,
            (store.loadTransfer() as UpdateStateReadResult.Present).value.phase,
        )
    }

    @Test
    fun cancelFailsClosedWhenTransferIsAbsentWrongOrNoLongerDownloading() = runBlocking {
        val absentStore = UpdateStateStore(temporaryFolder.newFolder(), clock = { 1_000L })
        val absentRepository = repository(absentStore, unexpectedDownloader())
        assertTrue(absentRepository.cancel("0".repeat(64)) is UpdateCancelResult.Failure)

        val wrongStore = UpdateStateStore(temporaryFolder.newFolder(), clock = { 1_000L })
        val release = releaseIdentity()
        wrongStore.saveDiscoveredRelease(release, 1_000L)
        wrongStore.beginDownload(release, 1_000L)
        val wrongRepository = repository(wrongStore, unexpectedDownloader())
        assertTrue(wrongRepository.cancel("0".repeat(64)) is UpdateCancelResult.Failure)
        assertEquals(
            UpdateTransferPhase.DOWNLOADING,
            (wrongStore.loadTransfer() as UpdateStateReadResult.Present).value.phase,
        )

        val failedStore = UpdateStateStore(temporaryFolder.newFolder(), clock = { 1_000L })
        failedStore.saveDiscoveredRelease(release, 1_000L)
        failedStore.beginDownload(release, 1_000L)
        val failedTransfer = (failedStore.loadTransfer() as UpdateStateReadResult.Present).value
        failedStore.markFailed(failedTransfer, UpdateFailureCode.NETWORK_ERROR)
        val failedRepository = repository(failedStore, unexpectedDownloader())
        assertTrue(failedRepository.cancel(failedTransfer.updateId) is UpdateCancelResult.Failure)
        assertEquals(
            UpdateTransferPhase.FAILED,
            (failedStore.loadTransfer() as UpdateStateReadResult.Present).value.phase,
        )
    }

    @Test
    fun retryAfterCancelReturnsToDownloadingWithTheSamePart() = runBlocking {
        val store = UpdateStateStore(temporaryFolder.newFolder(), clock = { 2_000L })
        val release = releaseIdentity()
        store.saveDiscoveredRelease(release, 1_000L)
        store.beginDownload(release, 1_000L)
        val transfer = (store.loadTransfer() as UpdateStateReadResult.Present).value
        store.partFile(transfer).writeBytes(byteArrayOf(7, 8, 9))
        val repository = repository(store, unexpectedDownloader())
        assertEquals(UpdateCancelResult.Success, repository.cancel(transfer.updateId))

        var observedPhase: UpdateTransferPhase? = null
        var observedPart: List<Byte>? = null
        val retryRepository =
            repository(
                store,
                UpdateDownloadExecutor { requested, _ ->
                    assertEquals(
                        UpdateStateWriteResult.Success,
                        store.beginDownload(requested, 2_000L),
                    )
                    val resumed = (store.loadTransfer() as UpdateStateReadResult.Present).value
                    observedPhase = resumed.phase
                    observedPart = store.partFile(resumed).readBytes().toList()
                    UpdateDownloadResult.ReadyForVerification(
                        updateId = resumed.updateId,
                        completeFile = File("unused.apk"),
                        bytesWritten = resumed.assetSize,
                    )
                },
            )

        assertTrue(
            retryRepository.download(transfer.updateId) is UpdateDownloadResult.ReadyForVerification
        )
        assertEquals(UpdateTransferPhase.DOWNLOADING, observedPhase)
        assertEquals(listOf<Byte>(7, 8, 9), observedPart)
    }

    private fun repository(
        store: UpdateStateStore,
        executor: UpdateDownloadExecutor,
    ): UpdateRepository =
        UpdateRepositoryImpl(
            coordinator =
                UpdateCheckCoordinator(
                    source =
                        UpdateReleaseSource {
                            ReleaseFetchResult.Failure(ReleaseFetchFailure.TRANSPORT, "not used")
                        },
                    store = store,
                    gate = NoOpGate,
                    platform = NoOpPlatform,
                    clock = { 1_000L },
                ),
            store = store,
            downloader = executor,
        )

    private fun releaseIdentity() =
        UpdateReleaseIdentity(
            releaseTag = "v1.4.0",
            version = "1.4.0",
            assetName = "Aurorama-1.4.0-arm64-v8a.apk",
            assetSize = 8L,
            assetDigest = "sha256:0123456789abcdef",
            downloadUrl =
                "https://github.com/zhangwenkang-github/Aurorama/releases/download/" +
                    "v1.4.0/Aurorama-1.4.0-arm64-v8a.apk",
        )

    private fun unexpectedDownloader() = UpdateDownloadExecutor { _, _ ->
        throw AssertionError("downloader must not run")
    }

    private object NoOpGate : UpdateCheckGateStore {
        override fun lastAutomaticAttemptAtEpochMs(): Long = 0L

        override fun lastSuccessfulCheckAtEpochMs(): Long = 0L

        override fun recordAutomaticAttempt(epochMs: Long): Boolean = true

        override fun recordSuccessfulCheck(epochMs: Long): Boolean = true
    }

    private object NoOpPlatform : UpdatePlatformInfo {
        override fun installedVersionName(): String = "1.3.0"

        override fun supportedAbis(): List<String> = listOf("arm64-v8a")
    }
}
