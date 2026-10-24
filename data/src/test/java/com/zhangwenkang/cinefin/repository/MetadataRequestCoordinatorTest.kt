package com.zhangwenkang.cinefin.repository

import java.util.concurrent.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRequestCoordinatorTest {

    @Test
    fun `同 namespace generation key 合并请求`() {
        val coordinator = MetadataRequestCoordinator()
        val fence = fence()

        val first = coordinator.join(fence, "show:1")
        val second = coordinator.join(fence, "show:1")

        assertTrue(first.second)
        assertFalse(second.second)
        assertSame(first.first, second.first)
        assertTrue(coordinator.size() == 1)
    }

    @Test
    fun `不同 server 不合并请求`() {
        val coordinator = MetadataRequestCoordinator()

        val first = coordinator.join(fence(serverId = "server-a"), "show:1")
        val second = coordinator.join(fence(serverId = "server-b"), "show:1")

        assertTrue(first.second)
        assertTrue(second.second)
        assertFalse(first.first === second.first)
        first.first.complete("old generation")
        assertFalse(second.first.isCompleted)
    }

    @Test
    fun `不同 user 不合并请求`() {
        val coordinator = MetadataRequestCoordinator()

        val first = coordinator.join(fence(userId = "user-a"), "show:1")
        val second = coordinator.join(fence(userId = "user-b"), "show:1")

        assertTrue(first.second)
        assertTrue(second.second)
        assertFalse(first.first === second.first)
    }

    @Test
    fun `不同 generation 不合并请求`() {
        val coordinator = MetadataRequestCoordinator()

        val first = coordinator.join(fence(generation = 1L), "show:1")
        val second = coordinator.join(fence(generation = 2L), "show:1")

        assertTrue(first.second)
        assertTrue(second.second)
        assertFalse(first.first === second.first)
    }

    @Test
    fun `旧代取消或失败不影响新代请求登记`() {
        val coordinator = MetadataRequestCoordinator()
        val oldFence = fence(generation = 1L)
        val newFence = fence(generation = 2L)
        val old = coordinator.join(oldFence, "show:1")
        val current = coordinator.join(newFence, "show:1")

        old.first.cancel(CancellationException("generation changed"))
        coordinator.finish(oldFence, "show:1", old.first)

        val joinedAfterCancel = coordinator.join(newFence, "show:1")
        assertFalse(joinedAfterCancel.second)
        assertSame(current.first, joinedAfterCancel.first)

        val failed = coordinator.join(fence(generation = 3L), "show:1")
        failed.first.completeExceptionally(IllegalStateException("old request failed"))
        coordinator.finish(fence(generation = 3L), "show:1", failed.first)

        val joinedCurrent = coordinator.join(newFence, "show:1")
        assertFalse(joinedCurrent.second)
        assertSame(current.first, joinedCurrent.first)
        assertFalse(current.first.isCompleted)
        assertTrue(coordinator.size() == 1)
    }

    private fun fence(
        serverId: String = "server-a",
        userId: String = "user-a",
        generation: Long = 1L,
    ): MetadataFence =
        MetadataFence(
            namespace = MetadataNamespace(serverId = serverId, userId = userId),
            contentGeneration = generation,
        )
}
