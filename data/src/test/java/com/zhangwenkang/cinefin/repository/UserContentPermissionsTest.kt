package com.zhangwenkang.cinefin.repository

import io.mockk.every
import io.mockk.mockk
import java.util.UUID
import org.jellyfin.sdk.model.api.UserPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * W8-T2：写权限门控纯函数矩阵（fail-closed）。
 *
 * 语义依据见 [canDeleteContent] / [canEditMetadata] 的 KDoc；测试服实测：非管理员 `updateItem` 一律 403（含获删权库）， 管理员
 * 204；删除权限字段为 `EnableContentDeletion` / `EnableContentDeletionFromFolders`。
 */
class UserContentPermissionsTest {
    @Test
    fun `管理员可删除任意库且可编辑元数据`() {
        val policy = policy(isAdministrator = true)

        assertTrue(canDeleteContent(policy, OTHER_LIBRARY_ID))
        assertTrue(canDeleteContent(policy, MOVIE_LIBRARY_ID))
        assertTrue(canDeleteContent(policy, libraryId = null))
        assertTrue(canEditMetadata(policy))
    }

    @Test
    fun `全局删除许可对任意库生效但不给元数据编辑`() {
        val policy = policy(enableContentDeletion = true)

        assertTrue(canDeleteContent(policy, MOVIE_LIBRARY_ID))
        assertTrue(canDeleteContent(policy, libraryId = null))
        assertFalse(canEditMetadata(policy))
    }

    @Test
    fun `仅指定库许可命中该库才可删除`() {
        val policy = policy(deleteFolders = listOf(OTHER_LIBRARY_ID.toString()))

        assertTrue(canDeleteContent(policy, OTHER_LIBRARY_ID))
        assertFalse(canDeleteContent(policy, MOVIE_LIBRARY_ID))
        assertFalse(canDeleteContent(policy, libraryId = null))
        assertFalse(canEditMetadata(policy))
    }

    @Test
    fun `库 id 匹配不区分大小写`() {
        val policy = policy(deleteFolders = listOf(OTHER_LIBRARY_ID.toString().uppercase()))

        assertTrue(canDeleteContent(policy, OTHER_LIBRARY_ID))
    }

    @Test
    fun `无任何删除许可时全部不可写`() {
        val policy = policy()

        assertFalse(canDeleteContent(policy, OTHER_LIBRARY_ID))
        assertFalse(canDeleteContent(policy, null))
        assertFalse(canEditMetadata(policy))
    }

    @Test
    fun `policy 为 null 时全部不可写`() {
        assertFalse(canDeleteContent(null, OTHER_LIBRARY_ID))
        assertFalse(canDeleteContent(null, null))
        assertFalse(canEditMetadata(null))
    }

    @Test
    fun `权限快照委托同一判定且未加载时 fail-closed`() {
        val loaded =
            UserPolicyAccess(
                serverId = SERVER_ID,
                userId = USER_ID,
                policy = policy(deleteFolders = listOf(OTHER_LIBRARY_ID.toString())),
            )
        val unknown = UserPolicyAccess.unknown(SERVER_ID, USER_ID)

        assertTrue(loaded.isLoaded)
        assertTrue(loaded.canDeleteContent(OTHER_LIBRARY_ID))
        assertFalse(loaded.canDeleteContent(MOVIE_LIBRARY_ID))
        assertFalse(loaded.canEditMetadata())

        assertFalse(unknown.isLoaded)
        assertFalse(unknown.canDeleteContent(OTHER_LIBRARY_ID))
        assertFalse(unknown.canEditMetadata())
    }

    private fun policy(
        isAdministrator: Boolean = false,
        enableContentDeletion: Boolean = false,
        deleteFolders: List<String>? = null,
    ): UserPolicy =
        mockk<UserPolicy>(relaxed = true).also { policy ->
            every { policy.isAdministrator } returns isAdministrator
            every { policy.enableContentDeletion } returns enableContentDeletion
            every { policy.enableContentDeletionFromFolders } returns deleteFolders
        }

    private companion object {
        const val SERVER_ID = "server-a"
        const val USER_ID = "11111111-1111-4111-8111-111111111111"
        val OTHER_LIBRARY_ID: UUID = UUID.fromString("12c75549-db55-001f-7116-13d2fddbb9e2")
        val MOVIE_LIBRARY_ID: UUID = UUID.fromString("3227ce1e-0697-54c5-94af-25ea66d69fc7")
    }
}
