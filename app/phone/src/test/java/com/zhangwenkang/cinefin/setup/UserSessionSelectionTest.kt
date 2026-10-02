package com.zhangwenkang.cinefin.setup

import com.zhangwenkang.cinefin.models.User
import com.zhangwenkang.cinefin.setup.domain.nextCurrentUserIdAfterDelete
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserSessionSelectionTest {
    private val serverId = "server-1"
    private val alice = User(id = UUID.randomUUID(), name = "Alice", serverId = serverId)
    private val bob = User(id = UUID.randomUUID(), name = "Bob", serverId = serverId)

    @Test
    fun `删除非当前用户时当前用户保持不变`() {
        val users = listOf(alice, bob)

        assertEquals(
            bob.id,
            nextCurrentUserIdAfterDelete(users, deletedUserId = alice.id, currentUserId = bob.id),
        )
        assertNull(
            nextCurrentUserIdAfterDelete(users, deletedUserId = alice.id, currentUserId = null)
        )
    }

    @Test
    fun `删除当前用户时顺延到剩余用户`() {
        assertEquals(
            bob.id,
            nextCurrentUserIdAfterDelete(
                remainingUsers = listOf(alice, bob),
                deletedUserId = alice.id,
                currentUserId = alice.id,
            ),
        )
    }

    @Test
    fun `删除最后一个用户时清空当前用户`() {
        assertNull(
            nextCurrentUserIdAfterDelete(
                remainingUsers = listOf(alice),
                deletedUserId = alice.id,
                currentUserId = alice.id,
            )
        )
    }
}
