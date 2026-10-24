package com.zhangwenkang.cinefin.update

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateCheckCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val dayMs = 24L * 60L * 60L * 1_000L
    private val currentRelease =
        GitHubReleaseDto(
            tagName = "v1.3.0",
            draft = false,
            prerelease = false,
            assets = emptyList(),
        )
    private val release =
        GitHubReleaseDto(
            tagName = "v1.4.0",
            draft = false,
            prerelease = false,
            assets =
                listOf(
                    GitHubReleaseAssetDto(
                        name = "Aurorama-1.4.0-arm64-v8a.apk",
                        size = 8L,
                        browserDownloadUrl =
                            "https://github.com/zhangwenkang-github/Aurorama/releases/download/" +
                                "v1.4.0/Aurorama-1.4.0-arm64-v8a.apk",
                        digest = "sha256:0123456789abcdef",
                    )
                ),
        )

    @Test
    fun automaticCheckRunsOnlyAtTwentyFourHourBoundary() = runBlocking {
        var now = 10L * dayMs
        val gate = FakeCheckGate(automaticAttemptAtEpochMs = now - dayMs + 1L)
        var networkCalls = 0
        val coordinator =
            coordinator(
                source = {
                    networkCalls++
                    ReleaseFetchResult.Success(currentRelease)
                },
                gate = gate,
                clock = { now },
            )

        assertEquals(UpdateCheckResult.Gated, coordinator.check(UpdateCheckMode.AUTO))
        assertEquals(0, networkCalls)

        gate.automaticAttemptAtEpochMs = now - dayMs
        assertEquals(UpdateCheckResult.UpToDate(now), coordinator.check(UpdateCheckMode.AUTO))
        assertEquals(1, networkCalls)
        assertEquals(now, gate.automaticAttemptAtEpochMs)
    }

    @Test
    fun networkFailurePersistsAttemptAndBlocksFollowingAutomaticCheck() = runBlocking {
        var now = 20L * dayMs
        val gate = FakeCheckGate()
        var networkCalls = 0
        val coordinator =
            coordinator(
                source = {
                    networkCalls++
                    ReleaseFetchResult.Failure(ReleaseFetchFailure.TRANSPORT, "offline")
                },
                gate = gate,
                clock = { now },
            )

        val first = coordinator.check(UpdateCheckMode.AUTO)
        assertEquals(
            UpdateCheckResult.Failed(UpdateCheckFailure.NETWORK_ERROR),
            first,
        )
        assertEquals(now, gate.automaticAttemptAtEpochMs)

        now += 1L
        assertEquals(UpdateCheckResult.Gated, coordinator.check(UpdateCheckMode.AUTO))
        assertEquals(1, networkCalls)
    }

    @Test
    fun deterministicReleaseFailureAlsoPersistsAttempt() = runBlocking {
        val now = 21L * dayMs
        val gate = FakeCheckGate()
        val coordinator =
            coordinator(
                source = {
                    ReleaseFetchResult.Failure(
                        ReleaseFetchFailure.INVALID_RESPONSE,
                        "invalid release json",
                    )
                },
                gate = gate,
                clock = { now },
            )

        assertEquals(
            UpdateCheckResult.Failed(UpdateCheckFailure.INVALID_RELEASE),
            coordinator.check(UpdateCheckMode.AUTO),
        )
        assertEquals(now, gate.automaticAttemptAtEpochMs)
    }

    @Test
    fun manualCheckBypassesAutomaticGateWithoutRewritingAutomaticAttempt() = runBlocking {
        val now = 22L * dayMs
        val previousAttempt = now - 1_000L
        val gate = FakeCheckGate(automaticAttemptAtEpochMs = previousAttempt)
        var networkCalls = 0
        val coordinator =
            coordinator(
                source = {
                    networkCalls++
                    ReleaseFetchResult.Success(currentRelease)
                },
                gate = gate,
                clock = { now },
            )

        assertEquals(UpdateCheckResult.UpToDate(now), coordinator.check(UpdateCheckMode.MANUAL))
        assertEquals(1, networkCalls)
        assertEquals(previousAttempt, gate.automaticAttemptAtEpochMs)
        assertEquals(now, gate.lastSuccessfulCheckAtEpochMs)
    }

    @Test
    fun automaticAndManualChecksShareOneProcessSingleFlight() = runBlocking {
        val now = 23L * dayMs
        val entered = CountDownLatch(1)
        val releaseNetwork = CountDownLatch(1)
        val gate = FakeCheckGate()
        var networkCalls = 0
        val coordinator =
            coordinator(
                source = {
                    networkCalls++
                    entered.countDown()
                    check(releaseNetwork.await(5, TimeUnit.SECONDS))
                    ReleaseFetchResult.Success(currentRelease)
                },
                gate = gate,
                clock = { now },
            )

        val automatic = async(Dispatchers.Default) { coordinator.check(UpdateCheckMode.AUTO) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        assertEquals(
            UpdateCheckResult.AlreadyRunning,
            coordinator.check(UpdateCheckMode.MANUAL),
        )
        assertEquals(1, networkCalls)

        releaseNetwork.countDown()
        assertEquals(UpdateCheckResult.UpToDate(now), automatic.await())
    }

    @Test
    fun availableReleasePersistsDiscoveryAndReturnsStableUpdateId() = runBlocking {
        val now = 24L * dayMs
        val gate = FakeCheckGate()
        val store = UpdateStateStore(temporaryFolder.newFolder(), clock = { now })
        val coordinator =
            coordinator(
                source = { ReleaseFetchResult.Success(release) },
                gate = gate,
                clock = { now },
                store = store,
            )

        val result = coordinator.check(UpdateCheckMode.MANUAL) as UpdateCheckResult.Available
        val stored = store.loadDiscoveredRelease() as UpdateStateReadResult.Present

        assertEquals("1.4.0", result.version)
        assertEquals(stableUpdateId(stored.value.release), result.updateId)
        assertEquals(now, gate.lastSuccessfulCheckAtEpochMs)
    }

    @Test
    fun failedAttemptPersistencePreventsNetworkCall() = runBlocking {
        val now = 25L * dayMs
        val gate = FakeCheckGate(failAttemptWrites = true)
        var networkCalls = 0
        val coordinator =
            coordinator(
                source = {
                    networkCalls++
                    ReleaseFetchResult.Success(release)
                },
                gate = gate,
                clock = { now },
            )

        assertEquals(
            UpdateCheckResult.Failed(UpdateCheckFailure.STORAGE_ERROR),
            coordinator.check(UpdateCheckMode.AUTO),
        )
        assertEquals(0, networkCalls)
    }

    private fun coordinator(
        source: () -> ReleaseFetchResult,
        gate: FakeCheckGate,
        clock: () -> Long,
        store: UpdateStateStore =
            UpdateStateStore(Files.createTempDirectory("update-check-test").toFile(), clock),
    ): UpdateCheckCoordinator =
        UpdateCheckCoordinator(
            source = UpdateReleaseSource(source),
            store = store,
            gate = gate,
            platform = FakePlatformInfo("1.3.0", listOf("arm64-v8a")),
            clock = clock,
        )
}

private class FakeCheckGate(
    var automaticAttemptAtEpochMs: Long = 0L,
    var lastSuccessfulCheckAtEpochMs: Long = 0L,
    private val failAttemptWrites: Boolean = false,
    private val failSuccessWrites: Boolean = false,
) : UpdateCheckGateStore {
    override fun lastAutomaticAttemptAtEpochMs(): Long = automaticAttemptAtEpochMs

    override fun lastSuccessfulCheckAtEpochMs(): Long = lastSuccessfulCheckAtEpochMs

    override fun recordAutomaticAttempt(epochMs: Long): Boolean {
        if (failAttemptWrites) return false
        automaticAttemptAtEpochMs = epochMs
        return true
    }

    override fun recordSuccessfulCheck(epochMs: Long): Boolean {
        if (failSuccessWrites) return false
        lastSuccessfulCheckAtEpochMs = epochMs
        return true
    }
}

private class FakePlatformInfo(
    private val versionName: String,
    private val supportedAbis: List<String>,
) : UpdatePlatformInfo {
    override fun installedVersionName(): String = versionName

    override fun supportedAbis(): List<String> = supportedAbis
}
