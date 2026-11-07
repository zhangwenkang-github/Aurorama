package com.zhangwenkang.cinefin.setup

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.identity.IdentitySelection
import com.zhangwenkang.cinefin.identity.IdentityStateStatus
import com.zhangwenkang.cinefin.identity.IdentityStateStore
import com.zhangwenkang.cinefin.identity.IdentityTransitionResult
import com.zhangwenkang.cinefin.identity.IdentityWriteCoordinator
import com.zhangwenkang.cinefin.models.Server
import com.zhangwenkang.cinefin.models.ServerAddress
import com.zhangwenkang.cinefin.models.ServerWithAddressAndUser
import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.setup.data.SetupRepositoryImpl
import io.mockk.coEvery
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class SetupRepositoryImplIdentityProjectionTest {
    @Test
    fun missingDatabaseServerThrowsBeforeTouchingGlobalApi() {
        val fixture = ProjectionFixture()
        coEvery { fixture.database.getServerWithAddressAndUser(SERVER_ID) } returns null

        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fixture.repository.setCurrentServer(SERVER_ID) }
            }

        assertTrue(error.message.orEmpty().contains("server", ignoreCase = true))
        verify(exactly = 0) { fixture.apiClient.update(any(), any(), any(), any()) }
    }

    @Test
    fun missingCurrentAddressThrowsBeforeTouchingGlobalApi() {
        val fixture = ProjectionFixture()
        coEvery { fixture.database.getServerWithAddressAndUser(SERVER_ID) } returns
            relation(address = null)

        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fixture.repository.setCurrentServer(SERVER_ID) }
            }

        assertTrue(error.message.orEmpty().contains("address", ignoreCase = true))
        verify(exactly = 0) { fixture.apiClient.update(any(), any(), any(), any()) }
    }

    @Test
    fun mismatchedBaseUrlReadbackFailsAndCoordinatorStaysRecovering() {
        val fixture = ProjectionFixture()
        coEvery { fixture.database.getServerWithAddressAndUser(SERVER_ID) } returns relation()
        val coordinator =
            IdentityWriteCoordinator(IdentityStateStore(AppPreferences(fixture.preferences)))
        fixture.baseUrlReadback = "https://wrong.example"

        val projectionError =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fixture.repository.setCurrentServer(SERVER_ID) }
            }
        assertTrue(projectionError.message.orEmpty().contains("baseUrl", ignoreCase = true))
        assertEquals(1, fixture.updateCalls)

        fixture.updateCalls = 0
        val (result, current) =
            runBlocking {
                coordinator.selectServer(selection()) { _, permit ->
                    assertTrue(permit.commit { fixture.repository.setCurrentServer(SERVER_ID) })
                } to coordinator.current()
            }
        assertEquals(IdentityTransitionResult.RECOVERING, result)
        assertEquals(IdentityStateStatus.RECOVERING, current.status)
        assertEquals(selection(), current.selection)
    }

    @Test
    fun mismatchedAccessTokenReadbackFailsAfterMutation() {
        val fixture = ProjectionFixture()
        coEvery { fixture.database.getServerWithAddressAndUser(SERVER_ID) } returns relation()
        fixture.accessTokenReadback = "wrong-token"

        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fixture.repository.setCurrentServer(SERVER_ID) }
            }

        assertTrue(error.message.orEmpty().contains("accessToken", ignoreCase = true))
        assertEquals(1, fixture.updateCalls)
    }

    @Test
    fun mismatchedUserIdReadbackFailsAfterMutation() {
        val fixture = ProjectionFixture()
        coEvery { fixture.database.getServerWithAddressAndUser(SERVER_ID) } returns relation()
        fixture.userIdReadback = UUID.randomUUID()

        val error =
            assertThrows(IllegalStateException::class.java) {
                runBlocking { fixture.repository.setCurrentServer(SERVER_ID) }
            }

        assertTrue(error.message.orEmpty().contains("userId", ignoreCase = true))
        assertEquals(1, fixture.updateCalls)
    }

    @Test
    fun cancellationFromDatabaseIsNotWrapped() {
        val fixture = ProjectionFixture()
        coEvery { fixture.database.getServerWithAddressAndUser(SERVER_ID) } throws
            CancellationException("cancelled")

        val error =
            assertThrows(CancellationException::class.java) {
                runBlocking { fixture.repository.setCurrentServer(SERVER_ID) }
            }

        assertEquals("cancelled", error.message)
        verify(exactly = 0) { fixture.apiClient.update(any(), any(), any(), any()) }
    }

    @Test
    fun missingUserRemainsValidServerOnlySelection() {
        val fixture = ProjectionFixture()
        coEvery { fixture.database.getServerWithAddressAndUser(SERVER_ID) } returns
            relation(user = null)
        fixture.accessTokenReadback = null
        fixture.userIdReadback = null

        runBlocking { fixture.repository.setCurrentServer(SERVER_ID) }

        assertEquals(1, fixture.updateCalls)
        assertEquals(ADDRESS, fixture.baseUrlReadback)
        assertEquals(null, fixture.accessTokenReadback)
        assertEquals(null, fixture.userIdReadback)
    }

    private fun relation(
        address: ServerAddress? =
            ServerAddress(
                id = ADDRESS_ID,
                serverId = SERVER_ID,
                address = ADDRESS,
            ),
        user: User? =
            User(
                id = USER_ID,
                name = "User A",
                serverId = SERVER_ID,
                accessToken = ACCESS_TOKEN,
            ),
    ): ServerWithAddressAndUser =
        ServerWithAddressAndUser(
            server =
                Server(
                    id = SERVER_ID,
                    name = "Server A",
                    currentServerAddressId = address?.id,
                    currentUserId = user?.id,
                ),
            address = address,
            user = user,
        )

    private fun selection(): IdentitySelection =
        IdentitySelection(
            serverId = SERVER_ID,
            userId = USER_ID.toString(),
            addressId = ADDRESS_ID.toString(),
        )

    private class ProjectionFixture {
        val apiClient = mockk<ApiClient>(relaxed = true)
        val jellyfinApi = mockk<JellyfinApi>(relaxed = true)
        val database = mockk<ServerDatabaseDao>(relaxed = true)
        val preferences = ProjectionSharedPreferences()
        val repository = SetupRepositoryImpl(jellyfinApi, database, AppPreferences(preferences))

        var updateCalls = 0
        var baseUrlReadback: String? = ADDRESS
        var accessTokenReadback: String? = ACCESS_TOKEN
        var userIdReadback: UUID? = USER_ID

        init {
            every { jellyfinApi.api } returns apiClient
            every { apiClient.baseUrl } answers { baseUrlReadback }
            every { apiClient.accessToken } answers { accessTokenReadback }
            every { jellyfinApi.userId } answers { userIdReadback }
            justRun {
                apiClient.update(any(), any(), any(), any())
                updateCalls += 1
            }
        }
    }

    companion object {
        @BeforeClass
        @JvmStatic
        fun disableAndroidSlf4jProviderForMockK() {
            System.setProperty(
                "slf4j.provider",
                "org.slf4j.helpers.NOP_FallbackServiceProvider",
            )
        }

        private const val SERVER_ID = "server-a"
        private const val ADDRESS = "https://server-a.example"
        private const val ACCESS_TOKEN = "token-a"
        private val ADDRESS_ID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        private val USER_ID = UUID.fromString("22222222-2222-4222-8222-222222222222")
    }
}

private class ProjectionSharedPreferences : SharedPreferences {
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
