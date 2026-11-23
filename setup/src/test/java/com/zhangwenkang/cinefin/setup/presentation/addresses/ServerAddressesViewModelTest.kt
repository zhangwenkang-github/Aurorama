package com.zhangwenkang.cinefin.setup.presentation.addresses

import android.app.Application
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddresses
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** W9-T6 ③：删除当前地址被拒时必须给用户可见反馈，且不落库。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerAddressesViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `删除当前地址被拒时发出可见事件且不落库`() = runBlocking {
        val current = address("https://current.example")
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        coEvery { database.getServerCurrentAddress(SERVER_ID) } returns current
        val viewModel = ServerAddressesViewModel(mockk<Application>(relaxed = true), database)
        viewModel.loadAddresses(SERVER_ID)

        viewModel.deleteAddress(current.id)

        val event = withTimeout(5_000) { viewModel.events.first() }
        assertEquals(ServerAddressesEvent.CannotDeleteCurrentAddress, event)
        coVerify(exactly = 0) { database.deleteServerAddress(any()) }
    }

    @Test
    fun `删除非当前地址时正常落库且不发被拒事件`() = runBlocking {
        val current = address("https://current.example")
        val other = address("https://other.example")
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        coEvery { database.getServerCurrentAddress(SERVER_ID) } returns current
        coEvery { database.getServerWithAddresses(SERVER_ID) } returns
            ServerWithAddresses(
                server =
                    Server(
                        id = SERVER_ID,
                        name = "test",
                        currentServerAddressId = current.id,
                        currentUserId = null,
                    ),
                addresses = listOf(other, current),
                user = null,
            )
        val viewModel = ServerAddressesViewModel(mockk<Application>(relaxed = true), database)
        viewModel.loadAddresses(SERVER_ID)

        viewModel.deleteAddress(other.id)

        coVerify(exactly = 1) { database.deleteServerAddress(other.id) }
        assertNull(withTimeoutOrNull(200) { viewModel.events.first() })
    }

    private fun address(value: String) = ServerAddress(UUID.randomUUID(), SERVER_ID, value)

    private companion object {
        const val SERVER_ID = "server-addresses-test"
    }
}
