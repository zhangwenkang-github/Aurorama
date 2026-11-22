package com.zhangwenkang.cinefin.repository

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.api.JellyfinApi
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.api.client.Response
import org.jellyfin.sdk.api.operations.UserApi
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** W8-T2：policy 缓存按 (serverId, userId) 键控——换服 / 换账号不得串号，未刷新前 fail-closed。 */
class UserPolicyStoreTest {
    private var currentUserId: UUID? = USER_A
    private var currentServer: String? = SERVER_A

    private val userApi = mockk<UserApi>()

    private val jellyfinApi: JellyfinApi =
        mockk<JellyfinApi>(relaxed = true).also { jellyfinApi ->
            every { jellyfinApi.userId } answers { currentUserId }
            every { jellyfinApi.userApi } returns userApi
        }

    private val preferences: SharedPreferences =
        mockk<SharedPreferences>(relaxed = true).also { preferences ->
            every { preferences.getString("pref_current_server", null) } answers { currentServer }
        }

    private val store = UserPolicyStore(jellyfinApi, AppPreferences(preferences))

    private val allowAllPolicy: UserPolicy = policy(isAdministrator = true)
    private val noPermissionPolicy: UserPolicy = policy()

    @Test
    fun `刷新后返回当前账号的权限`() = runBlocking {
        coEvery { userApi.getCurrentUser() } returns response(allowAllPolicy)

        val access = store.refresh()

        assertTrue(access.isLoaded)
        assertEquals(SERVER_A, access.serverId)
        assertEquals(USER_A.toString(), access.userId)
        assertTrue(access.canDeleteContent(LIBRARY_ID))
        assertTrue(access.canEditMetadata())
        assertTrue(store.current().isLoaded)
    }

    @Test
    fun `换账号后未刷新前不得沿用上一账号权限`() = runBlocking {
        coEvery { userApi.getCurrentUser() } returns response(allowAllPolicy)
        store.refresh()
        assertTrue(store.current().canDeleteContent(LIBRARY_ID))

        currentUserId = USER_B
        currentServer = SERVER_B

        val access = store.current()
        assertFalse(access.isLoaded)
        assertEquals(USER_B.toString(), access.userId)
        assertFalse(access.canDeleteContent(LIBRARY_ID))
        assertFalse(access.canEditMetadata())
    }

    @Test
    fun `换账号后刷新拿到的是新账号权限`() = runBlocking {
        coEvery { userApi.getCurrentUser() } returns response(allowAllPolicy)
        store.refresh()

        currentUserId = USER_B
        currentServer = SERVER_B
        coEvery { userApi.getCurrentUser() } returns response(noPermissionPolicy)

        val access = store.refresh()

        assertEquals(USER_B.toString(), access.userId)
        assertFalse(access.canDeleteContent(LIBRARY_ID))
        assertFalse(access.canEditMetadata())
    }

    @Test
    fun `刷新期间切换身份则丢弃旧身份结果`() = runBlocking {
        coEvery { userApi.getCurrentUser() } answers
            {
                currentUserId = USER_B
                response(allowAllPolicy)
            }

        val access = store.refresh()

        assertFalse(access.isLoaded)
        assertEquals(USER_B.toString(), access.userId)
        assertFalse(store.current().canDeleteContent(LIBRARY_ID))
    }

    @Test
    fun `身份未解析时不请求服务器且 fail-closed`() = runBlocking {
        currentUserId = null

        val access = store.refresh()

        assertFalse(access.isLoaded)
        coVerify(exactly = 0) { userApi.getCurrentUser() }
    }

    @Test
    fun `拉取失败 fail-closed`() = runBlocking {
        coEvery { userApi.getCurrentUser() } throws IOException("offline")

        val access = store.refresh()

        assertFalse(access.isLoaded)
        assertFalse(access.canDeleteContent(LIBRARY_ID))
        assertFalse(store.current().isLoaded)
    }

    @Test
    fun `invalidate 后立即 fail-closed`() = runBlocking {
        coEvery { userApi.getCurrentUser() } returns response(allowAllPolicy)
        store.refresh()

        store.invalidate()

        assertFalse(store.current().isLoaded)
    }

    @Test
    fun `ensureLoaded 命中缓存时不重复请求`() = runBlocking {
        coEvery { userApi.getCurrentUser() } returns response(noPermissionPolicy)

        store.ensureLoaded()
        val cached = store.ensureLoaded()

        assertTrue(cached.isLoaded)
        coVerify(exactly = 1) { userApi.getCurrentUser() }
    }

    private fun response(policy: UserPolicy): Response<UserDto> {
        val user =
            mockk<UserDto>(relaxed = true).also { user -> every { user.policy } returns policy }
        val response = mockk<Response<UserDto>>(relaxed = true)
        every { response.content } returns user
        return response
    }

    private fun policy(isAdministrator: Boolean = false, enableContentDeletion: Boolean = false) =
        mockk<UserPolicy>(relaxed = true).also { policy ->
            every { policy.isAdministrator } returns isAdministrator
            every { policy.enableContentDeletion } returns enableContentDeletion
            every { policy.enableContentDeletionFromFolders } returns null
        }

    private companion object {
        const val SERVER_A = "server-a"
        const val SERVER_B = "server-b"
        val USER_A: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val USER_B: UUID = UUID.fromString("22222222-2222-4222-8222-222222222222")
        val LIBRARY_ID: UUID = UUID.fromString("12c75549-db55-001f-7116-13d2fddbb9e2")
    }
}
