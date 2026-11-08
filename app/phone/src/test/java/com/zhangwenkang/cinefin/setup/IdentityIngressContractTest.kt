package com.zhangwenkang.cinefin.setup

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.api.JellyfinApiFactory
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.identity.IdentityReadLease
import com.zhangwenkang.cinefin.identity.IdentityReadLeasePort
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.identity.IdentityStateStore
import com.zhangwenkang.cinefin.identity.IdentityTransitionResult
import com.zhangwenkang.cinefin.identity.IdentityWriteCoordinator
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddressAndUser
import com.zhangwenkang.cinefin.models.ServerWithAddresses
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.network.TrustedCertificate
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.setup.data.SetupRepositoryImpl
import com.zhangwenkang.cinefin.setup.domain.IdentityAttemptStaleException
import com.zhangwenkang.cinefin.setup.domain.IdentityTransitionFailedException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.BrandingApi
import org.jellyfin.sdk.api.operations.QuickConnectApi
import org.jellyfin.sdk.api.operations.SystemApi
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.discovery.RecommendedServerInfo
import org.jellyfin.sdk.discovery.RecommendedServerInfoScore
import org.jellyfin.sdk.model.api.AuthenticateUserByName
import org.jellyfin.sdk.model.api.AuthenticationResult
import org.jellyfin.sdk.model.api.PublicSystemInfo
import org.jellyfin.sdk.model.api.QuickConnectDto
import org.jellyfin.sdk.model.api.UserDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class IdentityIngressContractTest {
    @Test
    fun `permit for selection A cannot project selection B`() = runBlocking {
        val fixture = IngressFixture()
        val selectionA =
            IdentitySelection(
                serverId = SERVER_A,
                userId = null,
                addressId = ADDRESS_A.toString(),
            )

        val result =
            fixture.coordinator.selectServer(selectionA) { _, permit ->
                permit.commit { fixture.repository.setCurrentServer(SERVER_B) }
            }

        assertEquals(IdentityTransitionResult.RECOVERING, result)
        assertEquals(GLOBAL_BASE_URL, fixture.baseUrl)
        assertEquals(GLOBAL_USER_ID, fixture.userId)
    }

    @Test
    fun `direct server projection outside permit fails without side effects`() = runBlocking {
        val fixture = IngressFixture()

        val failure = runCatching {
            fixture.repository.setCurrentServer(SERVER_B)
        }
            .exceptionOrNull()

        assertEquals(IllegalStateException::class.java, failure?.javaClass)
        assertEquals(GLOBAL_BASE_URL, fixture.baseUrl)
        assertEquals(GLOBAL_USER_ID, fixture.userId)
    }

    @Test
    fun `external generation during current user lookup leaves add server without side effects`() =
        runBlocking {
            val fixture = IngressFixture()
            boundedStage("initialize") { fixture.initializeIdentity() }
            val discovery = fixture.addCandidate()
            val enteredUserLookup = CompletableDeferred<Unit>()
            val releaseUserLookup = CompletableDeferred<Unit>()
            var insertedServers = 0
            var insertedAddresses = 0
            val serverInfo =
                PublicSystemInfo(
                    serverName = "New Server",
                    version = "10.11.8",
                    productName = "Jellyfin Server",
                    id = NEW_SERVER,
                )
            val recommended =
                mockk<RecommendedServerInfo>().also {
                    every { it.score } returns RecommendedServerInfoScore.GREAT
                    every { it.address } returns ADDRESS
                    every { it.systemInfo } returns Result.success(serverInfo)
                }
            coEvery {
                discovery.jellyfinApi.jellyfin.discovery.getAddressCandidates(ADDRESS)
            } returns emptyList()
            coEvery {
                discovery.jellyfinApi.jellyfin.discovery.getRecommendedServers(
                    any<Collection<String>>(),
                    RecommendedServerInfoScore.OK,
                )
            } returns listOf(recommended)
            coEvery { fixture.database.getServer(NEW_SERVER) } returns null
            coEvery { fixture.database.getServerCurrentUser(NEW_SERVER) } coAnswers
                {
                    enteredUserLookup.complete(Unit)
                    releaseUserLookup.await()
                    null
                }
            coEvery { fixture.database.insertServer(any()) } coAnswers { insertedServers += 1 }
            coEvery { fixture.database.insertServerAddress(any()) } coAnswers
                {
                    insertedAddresses += 1
                }

            val addAttempt = async { runCatching { fixture.repository.addServer(ADDRESS) } }
            boundedStage("user-lookup-entered") {
                try {
                    enteredUserLookup.await()
                } catch (timeout: TimeoutCancellationException) {
                    val earlyFailure =
                        if (addAttempt.isCompleted) addAttempt.await().exceptionOrNull() else null
                    throw AssertionError("ADD_NOT_AT_USER_LOOKUP failure=$earlyFailure", timeout)
                }
            }
            val externalSelection =
                IdentitySelection(
                    serverId = SERVER_A,
                    userId = USER_A.toString(),
                    addressId = ADDRESS_B.toString(),
                )
            boundedStage("external-transition") {
                assertEquals(
                    IdentityTransitionResult.APPLIED,
                    fixture.coordinator.selectAddress(externalSelection),
                )
            }
            releaseUserLookup.complete(Unit)

            boundedStage("add-await") {
                assertTrue(addAttempt.await().exceptionOrNull() is IdentityAttemptStaleException)
            }
            assertEquals(0, insertedServers)
            assertEquals(0, insertedAddresses)
            assertEquals(externalSelection, fixture.coordinator.current().selection)
            assertEquals(GLOBAL_BASE_URL, fixture.baseUrl)
            assertEquals(GLOBAL_ACCESS_TOKEN, fixture.accessToken)
            assertEquals(GLOBAL_USER_ID, fixture.userId)
        }

    @Test
    fun `add server persists and publishes one generation through permit`() = runBlocking {
        val fixture = IngressFixture()
        val discovery = fixture.addCandidate()
        var insertedServer: Server? = null
        var insertedAddress: ServerAddress? = null
        val serverInfo =
            PublicSystemInfo(
                serverName = "New Server",
                version = "10.11.8",
                productName = "Jellyfin Server",
                id = NEW_SERVER,
            )
        val recommended =
            mockk<RecommendedServerInfo>().also {
                every { it.score } returns RecommendedServerInfoScore.GREAT
                every { it.address } returns ADDRESS
                every { it.systemInfo } returns Result.success(serverInfo)
            }
        coEvery { discovery.jellyfinApi.jellyfin.discovery.getAddressCandidates(ADDRESS) } returns
            emptyList()
        coEvery {
            discovery.jellyfinApi.jellyfin.discovery.getRecommendedServers(
                any<Collection<String>>(),
                RecommendedServerInfoScore.OK,
            )
        } returns listOf(recommended)
        coEvery { fixture.database.getServer(NEW_SERVER) } returns null
        coEvery { fixture.database.getServerCurrentUser(NEW_SERVER) } returns null
        coEvery { fixture.database.insertServer(any()) } coAnswers { insertedServer = firstArg() }
        coEvery { fixture.database.insertServerAddress(any()) } coAnswers
            {
                insertedAddress = firstArg()
            }

        val added = fixture.repository.addServer(ADDRESS)

        assertEquals(NEW_SERVER, added.id)
        assertEquals(NEW_SERVER, insertedServer?.id)
        assertEquals(NEW_SERVER, insertedAddress?.serverId)
        assertEquals(1L, fixture.coordinator.current().identityGeneration)
        assertEquals(NEW_SERVER, fixture.coordinator.current().selection.serverId)
        assertEquals(ADDRESS, fixture.baseUrl)
        assertNull(fixture.accessToken)
        assertNull(fixture.userId)
        coVerify(exactly = 1) { fixture.database.insertServer(any()) }
        coVerify(exactly = 1) { fixture.database.insertServerAddress(any()) }
    }

    @Test
    fun `external generation during delete user lookup leaves stale effects untouched`() =
        runBlocking {
            val fixture = IngressFixture()
            fixture.initializeIdentity()
            val serverLookupEntered = CompletableDeferred<Unit>()
            val releaseServerLookup = CompletableDeferred<Unit>()
            coEvery { fixture.database.getServer(SERVER_A) } coAnswers
                {
                    serverLookupEntered.complete(Unit)
                    releaseServerLookup.await()
                    fixture.serverA
                }

            val deleteAttempt = async { runCatching { fixture.repository.deleteUser(USER_A) } }
            try {
                boundedStage("delete-server-lookup-entered") { serverLookupEntered.await() }
                val externalSelection =
                    IdentitySelection(
                        serverId = SERVER_B,
                        addressId = ADDRESS_B.toString(),
                    )
                assertEquals(
                    IdentityTransitionResult.APPLIED,
                    fixture.coordinator.selectAddress(externalSelection),
                )
            } finally {
                releaseServerLookup.complete(Unit)
            }

            val deleteFailure =
                boundedStage("delete-await") { deleteAttempt.await().exceptionOrNull() }
            assertEquals(SERVER_B, fixture.coordinator.current().selection.serverId)
            coVerify(exactly = 0) { fixture.database.updateServer(any()) }
            coVerify(exactly = 0) { fixture.database.deleteUser(any()) }
            assertTrue(deleteFailure is IdentityAttemptStaleException)
        }

    @Test
    fun `late quick connect result is stale and cannot overwrite newer login`() = runBlocking {
        val fixture = IngressFixture()
        fixture.initializeIdentity()
        val oldCandidate = fixture.addCandidate()
        val newCandidate = fixture.addCandidate()
        val enteredOldAttempt = CompletableDeferred<Unit>()
        val releaseOldAttempt = CompletableDeferred<Unit>()
        coEvery {
            oldCandidate.userApi.authenticateWithQuickConnect(any<QuickConnectDto>())
        } coAnswers
            {
                enteredOldAttempt.complete(Unit)
                releaseOldAttempt.await()
                response(fixture.authenticationResult(fixture.userA))
            }
        coEvery {
            newCandidate.userApi.authenticateUserByName(any<AuthenticateUserByName>())
        } returns response(fixture.authenticationResult(fixture.newUser))

        val oldAttempt = async { runCatching { fixture.repository.loginWithSecret("old-secret") } }
        enteredOldAttempt.await()
        fixture.repository.login("new-user", "new-password")
        releaseOldAttempt.complete(Unit)

        assertTrue(oldAttempt.await().exceptionOrNull() is IdentityAttemptStaleException)
        assertEquals(2L, fixture.coordinator.current().identityGeneration)
        assertEquals(fixture.newUser.id, fixture.userId)
        assertEquals(NEW_USER_TOKEN, fixture.accessToken)
        coVerify(exactly = 1) {
            fixture.database.updateServerCurrentUser(SERVER_A, fixture.newUser.id)
        }
        coVerify(exactly = 0) {
            fixture.database.updateServerCurrentUser(SERVER_A, fixture.userA.id)
        }
    }

    @Test
    fun `late quick connect read is discarded after external generation`() = runBlocking {
        val fixture = IngressFixture()
        fixture.initializeIdentity()
        val candidate = fixture.addCandidate()
        val readStarted = CompletableDeferred<Unit>()
        val releaseRead = CompletableDeferred<Unit>()
        coEvery { candidate.quickConnectApi.getQuickConnectEnabled() } coAnswers
            {
                readStarted.complete(Unit)
                releaseRead.await()
                response(true)
            }

        val readAttempt = async { fixture.repository.getIsQuickConnectEnabled() }
        boundedStage("quick-connect-read-started") { readStarted.await() }
        val externalSelection =
            IdentitySelection(
                serverId = SERVER_A,
                userId = USER_A.toString(),
                addressId = ADDRESS_B.toString(),
            )
        assertEquals(
            IdentityTransitionResult.APPLIED,
            fixture.coordinator.selectAddress(externalSelection),
        )
        releaseRead.complete(Unit)

        assertEquals(
            false,
            boundedStage("stale-quick-connect-read") { readAttempt.await() },
        )
    }

    @Test
    fun `stale quick connect state network error does not escape`() = runBlocking {
        val fixture = IngressFixture()
        fixture.initializeIdentity()
        val candidate = fixture.addCandidate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldFailure = OldNetworkFailure("quick-connect-state")
        coEvery { candidate.quickConnectApi.getQuickConnectState("secret") } coAnswers
            {
                entered.complete(Unit)
                release.await()
                throw oldFailure
            }

        val call = async { runCatching { fixture.repository.getQuickConnectState("secret") } }
        boundedStage("quick-connect-state-entered") { entered.await() }
        try {
            assertEquals(
                IdentityTransitionResult.APPLIED,
                fixture.advanceIdentityGeneration(),
            )
        } finally {
            release.complete(Unit)
        }

        val failure = boundedStage("quick-connect-state-result") { call.await().exceptionOrNull() }
        assertTrue(
            "stale quick-connect-state error escaped: $failure",
            failure is IdentityAttemptStaleException,
        )
        assertTrue(failure !== oldFailure)
    }

    @Test
    fun `stale disclaimer network error does not escape`() = runBlocking {
        val fixture = IngressFixture()
        fixture.initializeIdentity()
        val candidate = fixture.addCandidate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldFailure = OldNetworkFailure("disclaimer")
        coEvery { candidate.brandingApi.getBrandingOptions() } coAnswers
            {
                entered.complete(Unit)
                release.await()
                throw oldFailure
            }

        val call = async { runCatching { fixture.repository.loadDisclaimer() } }
        boundedStage("disclaimer-entered") { entered.await() }
        try {
            assertEquals(
                IdentityTransitionResult.APPLIED,
                fixture.advanceIdentityGeneration(),
            )
        } finally {
            release.complete(Unit)
        }

        val failure = boundedStage("disclaimer-result") { call.await().exceptionOrNull() }
        assertTrue(
            "stale disclaimer error escaped: $failure",
            failure is IdentityAttemptStaleException,
        )
        assertTrue(failure !== oldFailure)
    }

    @Test
    fun `stale public users network error does not escape`() = runBlocking {
        val fixture = IngressFixture()
        fixture.initializeIdentity()
        val candidate = fixture.addCandidate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldFailure = OldNetworkFailure("public-users")
        coEvery { candidate.userApi.getPublicUsers() } coAnswers
            {
                entered.complete(Unit)
                release.await()
                throw oldFailure
            }

        val call = async { runCatching { fixture.repository.getPublicUsers(SERVER_A) } }
        boundedStage("public-users-entered") { entered.await() }
        try {
            assertEquals(
                IdentityTransitionResult.APPLIED,
                fixture.advanceIdentityGeneration(),
            )
        } finally {
            release.complete(Unit)
        }

        val failure = boundedStage("public-users-result") { call.await().exceptionOrNull() }
        assertTrue(
            "stale public-users error escaped: $failure",
            failure is IdentityAttemptStaleException,
        )
        assertTrue(failure !== oldFailure)
    }

    @Test
    fun `stale add server address candidates error does not escape`() = runBlocking {
        val fixture = IngressFixture()
        fixture.initializeIdentity()
        val candidate = fixture.addCandidate()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldFailure = OldNetworkFailure("address-candidates")
        coEvery { candidate.jellyfinApi.jellyfin.discovery.getAddressCandidates(ADDRESS) } coAnswers
            {
                entered.complete(Unit)
                release.await()
                throw oldFailure
            }

        val call = async { runCatching { fixture.repository.addServer(ADDRESS) } }
        boundedStage("address-candidates-entered") { entered.await() }
        try {
            assertEquals(
                IdentityTransitionResult.APPLIED,
                fixture.advanceIdentityGeneration(),
            )
        } finally {
            release.complete(Unit)
        }

        val failure = boundedStage("address-candidates-result") { call.await().exceptionOrNull() }
        assertTrue(
            "stale address-candidates error escaped: $failure",
            failure is IdentityAttemptStaleException,
        )
        assertTrue(failure !== oldFailure)
    }

    @Test
    fun `ready network failures preserve original errors`() = runBlocking {
        val quickConnectFixture = IngressFixture()
        quickConnectFixture.initializeIdentity()
        val quickConnectCandidate = quickConnectFixture.addCandidate()
        val quickConnectFailure = OldNetworkFailure("ready-quick-connect-state")
        coEvery { quickConnectCandidate.quickConnectApi.getQuickConnectState("secret") } throws
            quickConnectFailure

        assertSame(
            quickConnectFailure,
            runCatching { quickConnectFixture.repository.getQuickConnectState("secret") }
                .exceptionOrNull(),
        )

        val disclaimerFixture = IngressFixture()
        disclaimerFixture.initializeIdentity()
        val disclaimerCandidate = disclaimerFixture.addCandidate()
        val disclaimerFailure = OldNetworkFailure("ready-disclaimer")
        coEvery { disclaimerCandidate.brandingApi.getBrandingOptions() } throws disclaimerFailure

        assertSame(
            disclaimerFailure,
            runCatching { disclaimerFixture.repository.loadDisclaimer() }.exceptionOrNull(),
        )

        val publicUsersFixture = IngressFixture()
        publicUsersFixture.initializeIdentity()
        val publicUsersCandidate = publicUsersFixture.addCandidate()
        val publicUsersFailure = OldNetworkFailure("ready-public-users")
        coEvery { publicUsersCandidate.userApi.getPublicUsers() } throws publicUsersFailure

        assertSame(
            publicUsersFailure,
            runCatching { publicUsersFixture.repository.getPublicUsers(SERVER_A) }
                .exceptionOrNull(),
        )

        val addServerFixture = IngressFixture()
        addServerFixture.initializeIdentity()
        val addServerCandidate = addServerFixture.addCandidate()
        val addServerFailure = OldNetworkFailure("ready-address-candidates")
        coEvery {
            addServerCandidate.jellyfinApi.jellyfin.discovery.getAddressCandidates(ADDRESS)
        } throws addServerFailure

        assertSame(
            addServerFailure,
            runCatching { addServerFixture.repository.addServer(ADDRESS) }.exceptionOrNull(),
        )
    }

    @Test
    fun `long-call cancellation propagates unchanged after generation advance`() = runBlocking {
        val readFixture = IngressFixture()
        readFixture.initializeIdentity()
        val readCandidate = readFixture.addCandidate()
        val readEntered = CompletableDeferred<Unit>()
        val readRelease = CompletableDeferred<Unit>()
        val readCancellation = CancellationException("read-cancellation")
        coEvery { readCandidate.quickConnectApi.getQuickConnectState("secret") } coAnswers
            {
                readEntered.complete(Unit)
                readRelease.await()
                throw readCancellation
            }

        val readCall = async {
            runCatching { readFixture.repository.getQuickConnectState("secret") }
        }
        boundedStage("cancellation-read-entered") { readEntered.await() }
        try {
            assertEquals(
                IdentityTransitionResult.APPLIED,
                readFixture.advanceIdentityGeneration(),
            )
        } finally {
            readRelease.complete(Unit)
        }
        val readFailure =
            boundedStage("cancellation-read-result") { readCall.await().exceptionOrNull() }
        assertTrue(readFailure is CancellationException)
        assertEquals(readCancellation.message, readFailure?.message)

        val addFixture = IngressFixture()
        addFixture.initializeIdentity()
        val addCandidate = addFixture.addCandidate()
        val addEntered = CompletableDeferred<Unit>()
        val addRelease = CompletableDeferred<Unit>()
        val addCancellation = CancellationException("add-cancellation")
        coEvery {
            addCandidate.jellyfinApi.jellyfin.discovery.getAddressCandidates(ADDRESS)
        } coAnswers
            {
                addEntered.complete(Unit)
                addRelease.await()
                throw addCancellation
            }

        val addCall = async { runCatching { addFixture.repository.addServer(ADDRESS) } }
        boundedStage("cancellation-add-entered") { addEntered.await() }
        try {
            assertEquals(
                IdentityTransitionResult.APPLIED,
                addFixture.advanceIdentityGeneration(),
            )
        } finally {
            addRelease.complete(Unit)
        }
        val addFailure =
            boundedStage("cancellation-add-result") { addCall.await().exceptionOrNull() }
        assertTrue(addFailure is CancellationException)
        assertEquals(addCancellation.message, addFailure?.message)
    }

    @Test
    fun `add server projection failure stays recovering and setup retry fails closed`() =
        runBlocking {
            val fixture = IngressFixture()
            var serverInDatabase: Server? = null
            var addressInDatabase: ServerAddress? = null
            val serverInfo =
                PublicSystemInfo(
                    serverName = "New Server",
                    version = "10.11.8",
                    productName = "Jellyfin Server",
                    id = NEW_SERVER,
                )
            coEvery { fixture.database.getServer(NEW_SERVER) } answers { serverInDatabase }
            coEvery { fixture.database.getServerCurrentUser(NEW_SERVER) } returns null
            coEvery { fixture.database.getServerWithAddresses(NEW_SERVER) } answers
                {
                    ServerWithAddresses(
                        server = requireNotNull(serverInDatabase),
                        addresses = listOf(requireNotNull(addressInDatabase)),
                        user = null,
                    )
                }
            coEvery { fixture.database.insertServer(any()) } coAnswers
                {
                    serverInDatabase = firstArg()
                }
            coEvery { fixture.database.insertServerAddress(any()) } coAnswers
                {
                    addressInDatabase = firstArg()
                }

            fixture.stubRecommendedServer(fixture.addCandidate(), serverInfo)
            fixture.baseUrlReadbackOverride = "https://wrong-readback.example"
            val firstFailure = runCatching {
                fixture.repository.addServer(ADDRESS)
            }
                .exceptionOrNull()
            assertTrue(firstFailure?.message.orEmpty().contains("baseUrl"))
            assertEquals(1L, fixture.coordinator.current().identityGeneration)
            assertEquals(IdentityStateStatus.RECOVERING, fixture.coordinator.current().status)

            assertRecoveringFailure(
                "retry addServer",
                runCatching { fixture.repository.addServer(ADDRESS) }.exceptionOrNull(),
            )
            assertEquals(1L, fixture.coordinator.current().identityGeneration)
            assertEquals(IdentityStateStatus.RECOVERING, fixture.coordinator.current().status)
            assertEquals(1, fixture.candidateCreations)
            coVerify(exactly = 1) { fixture.database.insertServer(any()) }
            coVerify(exactly = 1) { fixture.database.insertServerAddress(any()) }
            assertNull(fixture.accessToken)
            assertNull(fixture.userId)
        }

    @Test
    fun `recovering identity fails closed across setup identity consumers`() = runBlocking {
        val fixture = IngressFixture()
        val recoveringSelection =
            IdentitySelection(
                serverId = SERVER_A,
                userId = USER_A.toString(),
                addressId = ADDRESS_A.toString(),
            )
        assertEquals(
            IdentityTransitionResult.RECOVERING,
            fixture.coordinator.selectAddress(recoveringSelection) { _, _ ->
                error("deliberate projection failure")
            },
        )
        val recoveringSnapshot = fixture.coordinator.current()
        assertEquals(IdentityStateStatus.RECOVERING, recoveringSnapshot.status)
        assertEquals(recoveringSelection, recoveringSnapshot.selection)

        assertNull(fixture.repository.getCurrentServer())
        assertNull(fixture.repository.getCurrentUser())
        assertNull(fixture.repository.loadDisclaimer())
        assertTrue(fixture.repository.getPublicUsers(SERVER_A).isEmpty())
        assertEquals(false, fixture.repository.getIsQuickConnectEnabled())
        fixture.repository.deleteUser(USER_A)
        fixture.repository.setCurrentAddress(ADDRESS_A)

        assertRecoveringFailure(
            "login",
            runCatching { fixture.repository.login("user", "password") }.exceptionOrNull(),
        )
        assertRecoveringFailure(
            "loginWithSecret",
            runCatching { fixture.repository.loginWithSecret("secret") }.exceptionOrNull(),
        )
        assertRecoveringFailure(
            "initiateQuickConnect",
            runCatching { fixture.repository.initiateQuickConnect() }.exceptionOrNull(),
        )
        assertRecoveringFailure(
            "getQuickConnectState",
            runCatching { fixture.repository.getQuickConnectState("secret") }.exceptionOrNull(),
        )
        assertRecoveringFailure(
            "setCurrentServer",
            runCatching { fixture.repository.setCurrentServer(SERVER_B) }.exceptionOrNull(),
        )
        assertRecoveringFailure(
            "addServer",
            runCatching { fixture.repository.addServer(ADDRESS) }.exceptionOrNull(),
        )
        assertRecoveringFailure(
            "authenticate",
            runCatching { fixture.repository.authenticate(USER_A) }.exceptionOrNull(),
        )
        assertRecoveringFailure(
            "activateSavedUser",
            runCatching { fixture.repository.activateSavedUser(USER_A) }.exceptionOrNull(),
        )
        assertRecoveringFailure(
            "setCurrentUser",
            runCatching { fixture.repository.setCurrentUser(USER_A) }.exceptionOrNull(),
        )

        coVerify(exactly = 0) {
            fixture.database.getServer(any())
            fixture.database.getServerCurrentUser(any())
            fixture.database.getUser(any())
            fixture.database.getAddress(any())
            fixture.database.getServerWithAddressAndUser(any())
            fixture.database.getServerWithAddresses(any())
            fixture.database.getUsers(any())
            fixture.database.insertServer(any())
            fixture.database.insertServerAddress(any())
            fixture.database.insertUser(any())
            fixture.database.updateServer(any())
            fixture.database.updateServerCurrentUser(any(), any())
            fixture.database.deleteUser(any())
        }
        assertEquals(0, fixture.candidateCreations)
    }

    @Test
    fun `ready snapshot delayed across recovering transition cannot expose old server`() =
        runBlocking {
            val fixture = IngressFixture()
            fixture.initializeIdentity()
            val snapshotCaptured = CompletableDeferred<Unit>()
            val releaseSnapshot = CompletableDeferred<Unit>()
            val delayedReadPort =
                object : IdentityReadPort {
                    override suspend fun hydrate(): IdentitySnapshot = fixture.coordinator.hydrate()

                    override suspend fun current(): IdentitySnapshot {
                        val snapshot = fixture.coordinator.current()
                        snapshotCaptured.complete(Unit)
                        releaseSnapshot.await()
                        return snapshot
                    }
                }
            val repository = fixture.repositoryWith(identityReadPort = delayedReadPort)

            val readAttempt = async { repository.getCurrentServer() }
            val captured = withTimeoutOrNull(2_000L) { snapshotCaptured.await() }
            if (captured == null) {
                assertNull(readAttempt.await())
                return@runBlocking
            }

            val recoveringSelection =
                IdentitySelection(
                    serverId = SERVER_A,
                    userId = USER_A.toString(),
                    addressId = ADDRESS_B.toString(),
                )
            try {
                assertEquals(
                    IdentityTransitionResult.RECOVERING,
                    fixture.coordinator.selectAddress(recoveringSelection) { _, _ ->
                        error("deliberate transition failure")
                    },
                )
            } finally {
                releaseSnapshot.complete(Unit)
            }

            assertNull(boundedStage("delayed-ready-read") { readAttempt.await() })
        }

    @Test
    fun `certificate read fails closed while identity is recovering`() = runBlocking {
        val fixture = IngressFixture()
        every { fixture.globalJellyfinApi.certificateTrustStore.trustedCertificates() } returns
            listOf(
                TrustedCertificate(
                    trustKey = "recovering.example:443",
                    fingerprint = "AA:BB",
                )
            )
        assertEquals(
            IdentityTransitionResult.RECOVERING,
            fixture.coordinator.selectAddress(
                IdentitySelection(
                    serverId = SERVER_A,
                    userId = USER_A.toString(),
                    addressId = ADDRESS_A.toString(),
                )
            ) { _, _ ->
                error("deliberate projection failure")
            },
        )

        assertTrue(fixture.repository.getTrustedCertificates().isEmpty())
    }

    @Test
    fun `identity read port without lease cannot expose ready snapshot`() = runBlocking {
        val fixture = IngressFixture()
        val fakeReadPort =
            object : IdentityReadPort {
                override suspend fun hydrate(): IdentitySnapshot = readySnapshot()

                override suspend fun current(): IdentitySnapshot = readySnapshot()

                private fun readySnapshot(): IdentitySnapshot =
                    IdentitySnapshot(
                        selection =
                            IdentitySelection(
                                serverId = SERVER_A,
                                userId = USER_A.toString(),
                                addressId = ADDRESS_A.toString(),
                            ),
                        identityGeneration = 1L,
                        status = IdentityStateStatus.READY,
                    )
            }
        val repository = fixture.repositoryWith(identityReadPort = fakeReadPort)

        assertNull(repository.getCurrentServer())
        coVerify(exactly = 0) { fixture.database.getServer(any()) }
    }

    @Test
    fun `stale ready lease cannot expose identity dependent dao result`() = runBlocking {
        val fixture = IngressFixture()
        fixture.initializeIdentity()
        val leaseCaptured = CompletableDeferred<Unit>()
        val releaseLease = CompletableDeferred<Unit>()
        val delayedLeasePort =
            object : IdentityReadLeasePort {
                override suspend fun acquireReadyLease(): IdentityReadLease? {
                    val lease = fixture.coordinator.acquireReadyLease() ?: return null
                    leaseCaptured.complete(Unit)
                    releaseLease.await()
                    return lease
                }

                override suspend fun <T> withReadyLease(
                    lease: IdentityReadLease,
                    effect: suspend (IdentitySelection) -> T,
                ): T? = fixture.coordinator.withReadyLease(lease, effect)

                override suspend fun isReadyLeaseCurrent(lease: IdentityReadLease): Boolean =
                    fixture.coordinator.isReadyLeaseCurrent(lease)

                override suspend fun beginIngressForLease(lease: IdentityReadLease) =
                    fixture.coordinator.beginIngressForLease(lease)
            }
        val repository = fixture.repositoryWith(identityReadLeasePort = delayedLeasePort)

        val readAttempt = async { repository.getCurrentServer() }
        boundedStage("lease-captured") { leaseCaptured.await() }
        val recoveringSelection =
            IdentitySelection(
                serverId = SERVER_A,
                userId = USER_A.toString(),
                addressId = ADDRESS_B.toString(),
            )
        try {
            assertEquals(
                IdentityTransitionResult.RECOVERING,
                fixture.coordinator.selectAddress(recoveringSelection) { _, _ ->
                    error("deliberate transition failure")
                },
            )
        } finally {
            releaseLease.complete(Unit)
        }

        assertNull(boundedStage("stale-lease-read") { readAttempt.await() })
        coVerify(exactly = 0) { fixture.database.getServer(any()) }
    }

    @Test
    fun `first server set current succeeds from ready empty selection`() = runBlocking {
        val fixture = IngressFixture()
        val selection =
            IdentitySelection(
                serverId = SERVER_B,
                addressId = ADDRESS_B.toString(),
            )

        assertEquals(
            IdentityTransitionResult.APPLIED,
            fixture.coordinator.selectServer(selection) { _, permit ->
                assertTrue(permit.commit { fixture.repository.setCurrentServer(SERVER_B) })
            },
        )
        assertEquals(1L, fixture.coordinator.current().identityGeneration)
        assertEquals(SERVER_B, fixture.coordinator.current().selection.serverId)
        assertEquals("https://server-b.example", fixture.baseUrl)
    }

    @Test
    fun `setup ingress has no direct global api or optional permit path`() {
        val repositorySource =
            findSource(
                    "setup/src/main/java/com/zhangwenkang/cinefin/setup/data/SetupRepositoryImpl.kt"
                )
                .readText(Charsets.UTF_8)
        val addServerSource =
            findSource(
                    "setup/src/main/java/com/zhangwenkang/cinefin/setup/presentation/addserver/AddServerViewModel.kt"
                )
                .readText(Charsets.UTF_8)
        val usersSource =
            findSource(
                    "setup/src/main/java/com/zhangwenkang/cinefin/setup/presentation/users/UsersViewModel.kt"
                )
                .readText(Charsets.UTF_8)

        assertTrue(repositorySource.contains("apiFactory.publish"))
        assertTrue(!repositorySource.contains("jellyfinApi.api.update"))
        assertTrue(!repositorySource.contains("jellyfinApi.userId ="))
        assertTrue(!repositorySource.contains("permit: IdentityProjectionPermit?"))
        assertTrue(repositorySource.contains("IdentityReadLeasePort"))
        assertTrue(repositorySource.contains("acquireReadyLease()"))
        assertTrue(repositorySource.contains("withReadyLease"))
        assertTrue(repositorySource.contains("beginIngressForLease"))
        assertEquals(
            0,
            Regex("""\b(?:current|hydrate)\(\)""").findAll(repositorySource).count(),
        )
        assertTrue(!addServerSource.contains("appPreferences"))
        assertTrue(!usersSource.contains("appPreferences"))
    }

    private fun assertRecoveringFailure(name: String, failure: Throwable?) {
        assertTrue(
            "$name must fail closed with IdentityTransitionFailedException, actual=$failure",
            failure is IdentityTransitionFailedException,
        )
    }

    private fun findSource(relativePath: String): File {
        var directory: File? = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = directory?.resolve(relativePath)
            if (candidate?.isFile == true) return candidate
            directory = directory?.parentFile
        }
        error("Source not found from ${System.getProperty("user.dir")}: $relativePath")
    }

    private suspend fun <T> boundedStage(name: String, block: suspend () -> T): T =
        try {
            withTimeout(10_000L) { block() }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("TIMEOUT_STAGE=$name", timeout)
        }

    private class IngressFixture {
        val preferences = IngressSharedPreferences()
        val appPreferences = AppPreferences(preferences)
        val coordinator = IdentityWriteCoordinator(IdentityStateStore(appPreferences))
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        val globalApiClient = mockk<ApiClient>(relaxed = true)
        val globalJellyfinApi = mockk<JellyfinApi>(relaxed = true)
        val userA =
            User(
                id = USER_A,
                name = "User A",
                serverId = SERVER_A,
                accessToken = USER_A_TOKEN,
            )
        val addressA =
            ServerAddress(
                id = ADDRESS_A,
                serverId = SERVER_A,
                address = "https://server-a.example",
            )
        val serverA =
            Server(
                id = SERVER_A,
                name = "Server A",
                currentServerAddressId = ADDRESS_A,
                currentUserId = USER_A,
            )
        val newUser =
            User(
                id = NEW_USER,
                name = "New User",
                serverId = SERVER_A,
                accessToken = NEW_USER_TOKEN,
            )
        val candidates = ArrayDeque<CandidateApi>()
        var candidateCreations = 0
        val apiFactory =
            JellyfinApiFactory(globalJellyfinApi) {
                candidateCreations += 1
                check(candidates.isNotEmpty()) { "No candidate API queued" }
                candidates.removeFirst().jellyfinApi
            }
        var baseUrl: String? = GLOBAL_BASE_URL
        var baseUrlReadbackOverride: String? = null
        var accessToken: String? = GLOBAL_ACCESS_TOKEN
        var userId: UUID? = GLOBAL_USER_ID
        val repository = repositoryWith(identityConditionalWritePort = coordinator)

        init {
            every { globalJellyfinApi.api } returns globalApiClient
            every { globalApiClient.baseUrl } answers { baseUrlReadbackOverride ?: baseUrl }
            every { globalApiClient.accessToken } answers { accessToken }
            every { globalJellyfinApi.userId } answers { userId }
            every { globalJellyfinApi.userId = any() } answers { userId = firstArg() }
            every { globalJellyfinApi.userId = null } answers { userId = null }
            every { globalApiClient.update(any(), any(), any(), any()) } answers
                {
                    baseUrl = invocation.args[0] as String?
                    accessToken = invocation.args[1] as String?
                }
            coEvery { database.getServer(SERVER_A) } returns serverA
            coEvery { database.getAddress(ADDRESS_A) } returns addressA
            coEvery { database.getUser(USER_A) } returns userA
            coEvery { database.getServerCurrentUser(SERVER_A) } returns userA
            coEvery { database.getServerWithAddressAndUser(SERVER_B) } returns
                ServerWithAddressAndUser(
                    server =
                        Server(
                            id = SERVER_B,
                            name = "Server B",
                            currentServerAddressId = ADDRESS_B,
                            currentUserId = null,
                        ),
                    address =
                        ServerAddress(
                            id = ADDRESS_B,
                            serverId = SERVER_B,
                            address = "https://server-b.example",
                        ),
                    user = null,
                )
        }

        fun addCandidate(): CandidateApi = CandidateApi().also { candidates.addLast(it) }

        fun repositoryWith(
            identityReadPort: IdentityReadPort? = null,
            identityReadLeasePort: IdentityReadLeasePort? = null,
            identityConditionalWritePort:
                com.zhangwenkang.cinefin.identity.IdentityConditionalWritePort? =
                coordinator,
        ): SetupRepositoryImpl =
            SetupRepositoryImpl(
                jellyfinApi = globalJellyfinApi,
                database = database,
                appPreferences = appPreferences,
                apiFactory = apiFactory,
                identityReadPort = identityReadPort,
                identityReadLeasePort = identityReadLeasePort,
                identityConditionalWritePort = identityConditionalWritePort,
            )

        fun stubRecommendedServer(
            candidate: CandidateApi,
            serverInfo: PublicSystemInfo,
        ): RecommendedServerInfo {
            val recommended =
                mockk<RecommendedServerInfo>().also {
                    every { it.score } returns RecommendedServerInfoScore.GREAT
                    every { it.address } returns ADDRESS
                    every { it.systemInfo } returns Result.success(serverInfo)
                }
            coEvery {
                candidate.jellyfinApi.jellyfin.discovery.getAddressCandidates(ADDRESS)
            } returns emptyList()
            coEvery {
                candidate.jellyfinApi.jellyfin.discovery.getRecommendedServers(
                    any<Collection<String>>(),
                    RecommendedServerInfoScore.OK,
                )
            } returns listOf(recommended)
            return recommended
        }

        suspend fun initializeIdentity() {
            assertEquals(
                IdentityTransitionResult.APPLIED,
                coordinator.selectServer(
                    IdentitySelection(
                        serverId = SERVER_A,
                        userId = USER_A.toString(),
                        addressId = ADDRESS_A.toString(),
                    )
                ),
            )
        }

        suspend fun advanceIdentityGeneration(): IdentityTransitionResult =
            coordinator.selectAddress(
                IdentitySelection(
                    serverId = SERVER_A,
                    userId = USER_A.toString(),
                    addressId = ADDRESS_B.toString(),
                )
            )

        fun authenticationResult(user: User): AuthenticationResult =
            AuthenticationResult(
                user = userDto(user),
                accessToken = user.accessToken,
                serverId = user.serverId,
            )
    }

    private class CandidateApi {
        val apiClient = mockk<ApiClient>(relaxed = true)
        val brandingApi = mockk<BrandingApi>(relaxed = true)
        val userApi = mockk<UserApi>(relaxed = true)
        val quickConnectApi = mockk<QuickConnectApi>(relaxed = true)
        val systemApi = mockk<SystemApi>(relaxed = true)
        val jellyfinApi = mockk<JellyfinApi>(relaxed = true)
        var baseUrl: String? = null
        var accessToken: String? = null
        var userId: UUID? = null

        init {
            every { jellyfinApi.api } returns apiClient
            every { jellyfinApi.brandingApi } returns brandingApi
            every { jellyfinApi.userApi } returns userApi
            every { jellyfinApi.quickConnectApi } returns quickConnectApi
            every { jellyfinApi.systemApi } returns systemApi
            every { apiClient.baseUrl } answers { baseUrl }
            every { apiClient.accessToken } answers { accessToken }
            every { jellyfinApi.userId } answers { userId }
            every { jellyfinApi.userId = any() } answers { userId = firstArg() }
            every { jellyfinApi.userId = null } answers { userId = null }
            every { apiClient.update(any(), any(), any(), any()) } answers
                {
                    val requestedBaseUrl = invocation.args[0] as String?
                    if (requestedBaseUrl != null) baseUrl = requestedBaseUrl
                    accessToken = invocation.args[1] as String?
                }
        }
    }

    private class OldNetworkFailure(message: String) : RuntimeException(message)

    companion object {
        private const val SERVER_A = "server-a"
        private const val SERVER_B = "server-b"
        private const val NEW_SERVER = "server-new"
        private const val ADDRESS = "https://server-a.example/"
        private const val GLOBAL_BASE_URL = "https://global.example"
        private const val USER_A_TOKEN = "user-a-token"
        private const val GLOBAL_ACCESS_TOKEN = "global-token"
        private const val NEW_USER_TOKEN = "new-user-token"
        private val ADDRESS_A = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private val ADDRESS_B = UUID.fromString("22222222-2222-4222-8222-222222222222")
        private val USER_A = UUID.fromString("33333333-3333-4333-8333-333333333333")
        private val NEW_USER = UUID.fromString("44444444-4444-4444-8444-444444444444")
        private val GLOBAL_USER_ID = UUID.fromString("55555555-5555-4555-8555-555555555555")

        private fun userDto(user: User): UserDto =
            UserDto(
                name = user.name,
                serverId = user.serverId,
                id = user.id,
                hasPassword = false,
                hasConfiguredPassword = false,
                hasConfiguredEasyPassword = false,
            )

        private fun <T> response(content: T): Response<T> =
            mockk<Response<T>>().also { every { it.content } returns content }

        @BeforeClass
        @JvmStatic
        fun disableAndroidSlf4jProviderForMockK() {
            System.setProperty(
                "slf4j.provider",
                "org.slf4j.helpers.NOP_FallbackServiceProvider",
            )
        }
    }
}

private class IngressSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = Collections.unmodifiableMap(values.toMap())

    override fun getString(key: String, defValue: String?): String? =
        values[key] as String? ?: defValue

    override fun getStringSet(
        key: String,
        defValues: MutableSet<String>?,
    ): MutableSet<String>? = defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()

        override fun putString(key: String, value: String?): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putStringSet(
            key: String,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = apply { updates[key] = values?.toSet() }

        override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply {
            updates[key] = value
        }

        override fun remove(key: String): SharedPreferences.Editor = apply { updates[key] = null }

        override fun clear(): SharedPreferences.Editor = apply { updates.clear() }

        override fun commit(): Boolean {
            updates.forEach { (key, value) ->
                if (value == null) values.remove(key) else values[key] = value
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
