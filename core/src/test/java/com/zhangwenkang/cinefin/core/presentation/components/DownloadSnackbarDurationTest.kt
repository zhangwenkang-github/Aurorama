package com.zhangwenkang.cinefin.core.presentation.components

import androidx.compose.material3.SnackbarDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** W63 P1 缺陷锁定：带 `actionLabel` 的下载反馈若省略 `duration`，Material3 默认 `Indefinite`（常驻不消失）。 */
class DownloadSnackbarDurationTest {

    @Test
    fun `download feedback duration is explicitly long`() {
        assertEquals(SnackbarDuration.Long, DownloadSnackbarDuration)
        assertNotEquals(SnackbarDuration.Indefinite, DownloadSnackbarDuration)
    }
}
