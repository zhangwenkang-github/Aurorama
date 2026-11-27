package com.zhangwenkang.cinefin.update

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UpdateStateStoreTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("update-state-test").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun atomicallyReplacesDiscoveryAndReadsItFromNewInstance() {
        val first = UpdateStateStore(directory)
        val release = release()

        assertEquals(
            UpdateStateWriteResult.Success,
            first.saveDiscoveredRelease(release, nowEpochMs = 100),
        )

        val loaded =
            (UpdateStateStore(directory).loadDiscoveredRelease() as UpdateStateReadResult.Present)
                .value
        assertEquals(release, loaded.release)
        assertEquals(1, loaded.schemaVersion)
        assertEquals(100, loaded.updatedAtEpochMs)
        assertTrue(directory.resolve("release.json").isFile)
        assertTrue(!directory.resolve("release.json.tmp").exists())
    }

    @Test
    fun discoveringNewReleaseDoesNotOverwriteActiveTransferIdentity() {
        val store = UpdateStateStore(directory)
        val active = release()
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(active, nowEpochMs = 200))

        val newer =
            release(
                version = "1.4.1",
                assetSize = 10,
            )
        assertEquals(
            UpdateStateWriteResult.Success,
            store.saveDiscoveredRelease(newer, nowEpochMs = 300),
        )

        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        assertEquals(active, transfer.release)
        assertEquals(stableUpdateId(active), transfer.updateId)
        assertEquals(UpdateTransferPhase.DOWNLOADING, transfer.phase)
    }

    @Test
    fun discoveringNewReleaseStartsANewDiscoveryTimestamp() {
        val store = UpdateStateStore(directory)
        assertEquals(
            UpdateStateWriteResult.Success,
            store.saveDiscoveredRelease(release(), nowEpochMs = 100),
        )

        val newer = release(version = "1.4.1")
        assertEquals(
            UpdateStateWriteResult.Success,
            store.saveDiscoveredRelease(newer, nowEpochMs = 300),
        )

        val loaded =
            (store.loadDiscoveredRelease() as UpdateStateReadResult.Present<StoredReleaseState>)
                .value
        assertEquals(300, loaded.discoveredAtEpochMs)
        assertEquals(300, loaded.updatedAtEpochMs)
    }

    @Test
    fun updateIdIsStableForIdentityAndDistinctForDifferentAssets() {
        val arm64 = release()
        val universal =
            release(
                assetName = "Aurorama-1.4.0-universal.apk",
                assetSize = 20,
            )

        assertEquals(stableUpdateId(arm64), stableUpdateId(arm64.copy()))
        assertNotEquals(stableUpdateId(arm64), stableUpdateId(universal))
        assertNotEquals(
            stableUpdateId(arm64),
            stableUpdateId(arm64.copy(assetDigest = "sha256:changed")),
        )
    }

    @Test
    fun offsetAlwaysComesFromActualPartLength() {
        val store = UpdateStateStore(directory)
        val release = release(assetSize = 20)
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 200))
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value

        assertEquals(0L, store.inspectTransferFiles(transfer).offsetOrNull())
        Files.write(
            store.partFile(transfer).toPath(),
            byteArrayOf(1, 2, 3, 4, 5),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )

        assertEquals(5L, store.inspectTransferFiles(transfer).offsetOrNull())
    }

    @Test
    fun oversizedPartRequiresResetInsteadOfTrustingMetadata() {
        val store = UpdateStateStore(directory)
        val release = release(assetSize = 3)
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 200))
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        Files.write(store.partFile(transfer).toPath(), byteArrayOf(1, 2, 3, 4))

        assertTrue(store.inspectTransferFiles(transfer) is TransferFileInspection.ResetRequired)
    }

    @Test
    fun missingTransferIsDistinctFromCorruptTransfer() {
        val store = UpdateStateStore(directory)

        assertEquals(UpdateStateReadResult.Absent, store.loadTransfer())
        directory.resolve("transfer.json").writeText("""{"schemaVersion":}""")

        assertTrue(store.loadTransfer() is UpdateStateReadResult.Invalid)
    }

    @Test
    fun corruptSchemaFailsClosed() {
        val store = UpdateStateStore(directory)
        assertEquals(
            UpdateStateWriteResult.Success,
            store.saveDiscoveredRelease(release(), nowEpochMs = 100),
        )
        val file = directory.resolve("release.json")
        file.writeText(file.readText().replace("\"schemaVersion\":1", "\"schemaVersion\":2"))

        assertTrue(store.loadDiscoveredRelease() is UpdateStateReadResult.Invalid)
    }

    @Test
    fun tamperedTransferIdentityFailsClosed() {
        val store = UpdateStateStore(directory)
        assertEquals(
            UpdateStateWriteResult.Success,
            store.beginDownload(release(), nowEpochMs = 200),
        )
        val file = directory.resolve("transfer.json")
        file.writeText(
            file
                .readText()
                .replace(
                    stableUpdateId(release()),
                    "0000000000000000000000000000000000000000000000000000000000000000",
                )
        )

        assertTrue(store.loadTransfer() is UpdateStateReadResult.Invalid)
    }

    @Test
    fun missingTransferMetadataRecreatesStateAndStartsPartFromZero() {
        val store = UpdateStateStore(directory)
        val first = release(assetSize = 20)
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(first, nowEpochMs = 200))
        val firstTransfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        Files.write(store.partFile(firstTransfer).toPath(), byteArrayOf(1, 2, 3))
        directory.resolve("transfer.json").delete()

        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(first, nowEpochMs = 300))
        val recreated =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value

        assertEquals(0L, store.inspectTransferFiles(recreated).offsetOrNull())
        assertTrue(!store.partFile(recreated).exists() || store.partFile(recreated).length() == 0L)
    }

    @Test
    fun exactCompleteFileRecoversReadyForVerificationFromUnfinishedPhase() {
        val store = UpdateStateStore(directory)
        val release = release(assetSize = 6)
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 200))
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        Files.write(store.completeFile(transfer).toPath(), byteArrayOf(1, 2, 3, 4, 5, 6))

        val recovered =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value

        assertEquals(UpdateTransferPhase.READY_FOR_VERIFICATION, recovered.phase)
        assertEquals(
            UpdateTransferPhase.READY_FOR_VERIFICATION,
            store.loadTransfer().let {
                (it as UpdateStateReadResult.Present<UpdateTransferState>).value.phase
            },
        )
        assertTrue(!store.partFile(recovered).exists())
    }

    @Test
    fun wrongSizedCompleteFileFailsClosed() {
        val store = UpdateStateStore(directory)
        val release = release(assetSize = 6)
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 200))
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        Files.write(store.completeFile(transfer).toPath(), byteArrayOf(1, 2))

        assertTrue(store.loadTransfer() is UpdateStateReadResult.Invalid)
    }

    @Test
    fun beginDownloadDiscardsCorruptTransferRecordInsteadOfFailingForever() {
        val store = UpdateStateStore(directory)
        val release = release(assetSize = 6)
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 200))
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        Files.write(store.completeFile(transfer).toPath(), byteArrayOf(1, 2, 3, 4, 5, 6))
        assertEquals(
            UpdateTransferPhase.READY_FOR_VERIFICATION,
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>)
                .value
                .phase,
        )
        // 完整包被系统清理后，只剩 READY 记录 → loadTransfer() 判为 Invalid。
        store.completeFile(transfer).delete()
        assertTrue(store.loadTransfer() is UpdateStateReadResult.Invalid)

        // 再点「下载更新」必须能重建，而不是永久 STATE_ERROR。
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 300))
        val rebuilt =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value

        assertEquals(stableUpdateId(release), rebuilt.updateId)
        assertEquals(UpdateTransferPhase.DOWNLOADING, rebuilt.phase)
        assertEquals(0L, store.inspectTransferFiles(rebuilt).offsetOrNull())
    }

    @Test
    fun beginDownloadDropsWrongSizedCompleteFileWhenRecovering() {
        val store = UpdateStateStore(directory)
        val release = release(assetSize = 6)
        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 200))
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        Files.write(store.completeFile(transfer).toPath(), byteArrayOf(1, 2))
        assertTrue(store.loadTransfer() is UpdateStateReadResult.Invalid)

        assertEquals(UpdateStateWriteResult.Success, store.beginDownload(release, nowEpochMs = 300))
        val rebuilt =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value

        assertTrue("尺寸不符的旧完整包必须被清掉", !store.completeFile(rebuilt).exists())
        assertEquals(0L, store.inspectTransferFiles(rebuilt).offsetOrNull())
    }

    @Test
    fun failurePhasePersistsAnExplicitCode() {
        val store = UpdateStateStore(directory)
        assertEquals(
            UpdateStateWriteResult.Success,
            store.beginDownload(release(), nowEpochMs = 200),
        )
        val transfer =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value

        assertEquals(
            UpdateStateWriteResult.Success,
            store.markFailed(transfer, UpdateFailureCode.NETWORK_ERROR, nowEpochMs = 250),
        )
        val failed =
            (store.loadTransfer() as UpdateStateReadResult.Present<UpdateTransferState>).value
        assertEquals(UpdateTransferPhase.FAILED, failed.phase)
        assertEquals(UpdateFailureCode.NETWORK_ERROR, failed.failureCode)
        assertEquals(250, failed.updatedAtEpochMs)
    }

    @Test
    fun releaseNotesRoundTripAndSurviveRediscoveryOfSameRelease() {
        val store = UpdateStateStore(directory)
        val notes = "## 2.0.0\n\n- 修复应用内更新链路"

        assertEquals(
            UpdateStateWriteResult.Success,
            store.saveDiscoveredRelease(release(), nowEpochMs = 100, releaseNotes = notes),
        )
        assertEquals(
            notes,
            (store.loadDiscoveredRelease() as UpdateStateReadResult.Present).value.releaseNotes,
        )

        assertEquals(
            UpdateStateWriteResult.Success,
            store.saveDiscoveredRelease(release(), nowEpochMs = 200),
        )
        assertEquals(
            notes,
            (UpdateStateStore(directory).loadDiscoveredRelease() as UpdateStateReadResult.Present)
                .value
                .releaseNotes,
        )
    }

    @Test
    fun legacyReleaseStateWithoutReleaseNotesLoadsWithNullNotes() {
        // 2.0.0 写出的 release.json 没有 releaseNotes 键；2.0.1 读取时按缺省值兜底
        val identity = release()
        Files.write(
            directory.resolve("release.json").toPath(),
            releaseJson(identity).toByteArray(Charsets.UTF_8),
        )

        val loaded = UpdateStateStore(directory).loadDiscoveredRelease()

        assertTrue(loaded is UpdateStateReadResult.Present)
        assertEquals(identity, (loaded as UpdateStateReadResult.Present).value.release)
        assertEquals(null, loaded.value.releaseNotes)
    }

    @Test
    fun unknownReleaseStateFieldIsReportedInvalidAndNeverThrows() {
        // 反过来：旧版本 App（DTO 无 releaseNotes、ignoreUnknownKeys=false）读新版 release.json
        // 会走同一条 readState 的 try/catch → Invalid，调用方按「无更新」处理，不会崩溃。
        val identity = release()
        val futureJson = releaseJson(identity, extraFields = ",\n  \"futureField\": true")
        Files.write(
            directory.resolve("release.json").toPath(),
            futureJson.toByteArray(Charsets.UTF_8),
        )

        val loaded = UpdateStateStore(directory).loadDiscoveredRelease()

        assertTrue("未知字段必须降级为 Invalid 而不是抛异常", loaded is UpdateStateReadResult.Invalid)
    }

    private fun releaseJson(
        identity: UpdateReleaseIdentity,
        extraFields: String = "",
    ): String =
        """
        {
          "schemaVersion": 1,
          "releaseTag": "${identity.releaseTag}",
          "version": "${identity.version}",
          "assetName": "${identity.assetName}",
          "assetSize": ${identity.assetSize},
          "assetDigest": "${identity.assetDigest}",
          "downloadUrl": "${identity.downloadUrl}",
          "discoveredAtEpochMs": 100,
          "updatedAtEpochMs": 100$extraFields
        }
        """
            .trimIndent()

    private fun release(
        version: String = "1.4.0",
        assetName: String = "Aurorama-$version-arm64-v8a.apk",
        assetSize: Long = 8,
        assetDigest: String? =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    ): UpdateReleaseIdentity =
        UpdateReleaseIdentity(
            releaseTag = "v$version",
            version = version,
            assetName = assetName,
            assetSize = assetSize,
            assetDigest = assetDigest,
            downloadUrl = releaseAssetUrl(version, assetName),
        )

    private fun TransferFileInspection.offsetOrNull(): Long? =
        when (this) {
            is TransferFileInspection.ReadyToResume -> offset
            is TransferFileInspection.ResetRequired -> null
            is TransferFileInspection.Invalid -> null
            TransferFileInspection.AlreadyComplete -> null
        }
}
