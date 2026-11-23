package com.zhangwenkang.cinefin.setup.presentation.users

import com.zhangwenkang.cinefin.identity.IdentityTransitionResult
import com.zhangwenkang.cinefin.setup.domain.IdentityAttemptStaleException
import com.zhangwenkang.cinefin.setup.domain.IdentityTransitionFailedException
import com.zhangwenkang.cinefin.setup.domain.SavedUserActivationFailure
import com.zhangwenkang.cinefin.setup.domain.SetupRepository
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

/** W9-T8b：切换用户的两个静默失败分支必须落到行内 activationFailure 反馈。 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsersViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `切换被并发操作打断时写入行内反馈并清空激活态`() = runBlocking {
        val userId = UUID.randomUUID()
        val repository = mockk<SetupRepository>()
        coEvery { repository.activateSavedUser(userId) } throws IdentityAttemptStaleException()
        val viewModel = UsersViewModel(repository)

        viewModel.onAction(UsersAction.OnUserClick(userId))

        val state = withTimeout(5_000) { viewModel.state.first { it.activationFailure != null } }
        assertEquals(SavedUserActivationFailure.INTERRUPTED, state.activationFailure)
        assertNull(state.activatingUserId)
        coVerify(exactly = 1) { repository.activateSavedUser(userId) }
        assertNull(withTimeoutOrNull(200) { viewModel.events.first() })
    }

    @Test
    fun `身份恢复中时异常被接住并写入行内反馈`() = runBlocking {
        val userId = UUID.randomUUID()
        val repository = mockk<SetupRepository>()
        coEvery { repository.activateSavedUser(userId) } throws
            IdentityTransitionFailedException(IdentityTransitionResult.RECOVERING)
        val viewModel = UsersViewModel(repository)

        viewModel.onAction(UsersAction.OnUserClick(userId))

        val state = withTimeout(5_000) { viewModel.state.first { it.activationFailure != null } }
        assertEquals(SavedUserActivationFailure.IDENTITY_RECOVERING, state.activationFailure)
        assertNull(state.activatingUserId)
        coVerify(exactly = 1) { repository.activateSavedUser(userId) }
        assertNull(withTimeoutOrNull(200) { viewModel.events.first() })
    }
}
