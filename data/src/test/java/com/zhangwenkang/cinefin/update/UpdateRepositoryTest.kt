package com.zhangwenkang.cinefin.update

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
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
