package com.zhangwenkang.cinefin.setup

import com.zhangwenkang.cinefin.identity.IdentityProjection
import com.zhangwenkang.cinefin.identity.IdentityReadPort
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentitySnapshot
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.identity.IdentityTransitionResult
import com.zhangwenkang.cinefin.identity.IdentityWritePort
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddresses
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersAction
import com.zhangwenkang.cinefin.setup.presentation.servers.ServersViewModel
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServersViewModelIdentityTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun serverClickCallsIdentityWriterOnceAndOnlyProjectsThroughCallback() =
        runTest(dispatcher) {
            val serverId = "server-a"
            val addressId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val server =
                ServerWithAddresses(
                    server =
                        Server(
                            id = serverId,
                            name = "Server A",
                            currentServerAddressId = addressId,
                            currentUserId = userId,
                        ),
                    addresses =
                        listOf(
                            ServerAddress(
                                id = addressId,
                                serverId = serverId,
                                address = "https://server-a.example",
                            )
                        ),
                    user =
                        User(
                            id = userId,
                            name = "User A",
                            serverId = serverId,
                            accessToken = "never-enter-identity",
                        ),
                )
            val repository = RecordingSetupRepository(server)
            val reader =
                FixedIdentityReadPort(
                    IdentitySnapshot(
                        selection = IdentitySelection(offlineMode = true),
                        identityGeneration = 4L,
                        status = IdentityStateStatus.READY,
                    )
                )
            val writer = RecordingIdentityWriter()
            val viewModel = ServersViewModel(repository.repository, reader, writer)

            viewModel.loadServers()
            advanceUntilIdle()
            viewModel.onAction(ServersAction.OnServerClick(serverId))
            advanceUntilIdle()

            assertEquals(1, writer.serverCalls)
            assertEquals(
                IdentitySelection(
                    serverId = serverId,
                    userId = userId.toString(),
                    addressId = addressId.toString(),
                    offlineMode = true,
                ),
                writer.selection,
            )
            assertEquals(1, repository.setCurrentServerCalls)
            assertEquals(serverId, repository.lastServerId)
            assertEquals(1, writer.projectionCalls)
            assertFalse(
                ServersViewModel::class
                    .java
                    .constructors
                    .single()
                    .parameterTypes
                    .contains(AppPreferences::class.java)
            )
        }
}

private class FixedIdentityReadPort(private val snapshot: IdentitySnapshot) : IdentityReadPort {
    override suspend fun hydrate(): IdentitySnapshot = snapshot

    override suspend fun current(): IdentitySnapshot = snapshot
}

private class RecordingIdentityWriter : IdentityWritePort {
    var serverCalls = 0
        private set

    var projectionCalls = 0
        private set

    var selection: IdentitySelection? = null
        private set

    override suspend fun selectServer(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult {
        serverCalls += 1
        this.selection = selection
        projectionCalls += 1
        projection.project(
            IdentitySnapshot(
                selection = selection,
                identityGeneration = 5L,
                status = IdentityStateStatus.READY,
            )
        )
        return IdentityTransitionResult.APPLIED
    }

    override suspend fun selectAddress(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult = IdentityTransitionResult.NO_OP

    override suspend fun selectSavedUser(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult = IdentityTransitionResult.NO_OP

    override suspend fun selectAuthenticatedUser(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult = IdentityTransitionResult.NO_OP

    override suspend fun selectOfflineMode(
        enabled: Boolean,
        projection: IdentityProjection,
    ): IdentityTransitionResult = IdentityTransitionResult.NO_OP

    override suspend fun deleteSelection(
        selection: IdentitySelection,
        projection: IdentityProjection,
    ): IdentityTransitionResult = IdentityTransitionResult.NO_OP
}

private class RecordingSetupRepository(server: ServerWithAddresses) {
    var setCurrentServerCalls = 0
        private set

    var lastServerId: String? = null
        private set

    val repository: SetupRepository =
        Proxy.newProxyInstance(
            SetupRepository::class.java.classLoader,
            arrayOf(SetupRepository::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getServers" -> {
                    @Suppress("UNCHECKED_CAST")
                    val continuation = args!!.last() as Continuation<List<ServerWithAddresses>>
                    continuation.resume(listOf(server))
                    COROUTINE_SUSPENDED
                }
                "setCurrentServer" -> {
                    setCurrentServerCalls += 1
                    lastServerId = args!!.first() as String
                    @Suppress("UNCHECKED_CAST") val continuation = args.last() as Continuation<Unit>
                    continuation.resume(Unit)
                    COROUTINE_SUSPENDED
                }
                "toString" -> "RecordingSetupRepository"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> throw AssertionError("Unexpected repository call: ${method.name}")
            }
        } as SetupRepository
}
