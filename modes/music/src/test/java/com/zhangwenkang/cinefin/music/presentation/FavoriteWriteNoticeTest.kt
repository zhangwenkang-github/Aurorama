package com.zhangwenkang.cinefin.music.presentation

import com.zhangwenkang.cinefin.repository.UserDataWriteFailure
import com.zhangwenkang.cinefin.repository.UserDataWriteOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** W9-T4：收藏写「已排队」提示的纯规则（与影视详情页同一口径，不另造 UI）。 */
class FavoriteWriteNoticeTest {

    @Test
    fun `排队成功提示联网后自动同步`() {
        assertEquals(
            FAVORITE_QUEUED_NOTICE,
            favoriteWriteNotice(UserDataWriteOutcome.QueuedForSync),
        )
    }

    @Test
    fun `服务器确认静默成功`() {
        assertNull(favoriteWriteNotice(UserDataWriteOutcome.Confirmed))
    }

    @Test
    fun `不可重试失败不出排队提示`() {
        assertNull(
            favoriteWriteNotice(
                UserDataWriteOutcome.Failed(UserDataWriteFailure.PermissionDenied(403, "forbidden"))
            )
        )
    }

    @Test
    fun `写根本没完成按失败处理不提示排队`() {
        assertNull(favoriteWriteNotice(null))
    }
}
