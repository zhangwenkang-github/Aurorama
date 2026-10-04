package com.zhangwenkang.cinefin.utils

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test

/** W62：下载页 / 离线书架共用的书籍显示名口径单测。 */
class OfflineBookNamingTest {

    private val itemId = UUID.fromString("e2d0c13d-fc10-5255-b4c4-c84b67a53f01")

    @Test
    fun `侧车书名优先于服务器元数据`() {
        assertEquals(
            "虚构推理 (2026)",
            offlineBookDisplayName(
                serverName = "服务器上的新名字",
                sidecarTitle = "虚构推理 (2026)",
                itemId = itemId,
            ),
        )
    }

    @Test
    fun `侧车缺失时用服务器元数据兜底（在线）`() {
        assertEquals(
            "futuristic_tales",
            offlineBookDisplayName(
                serverName = "futuristic_tales",
                sidecarTitle = null,
                itemId = itemId,
            ),
        )
    }

    @Test
    fun `侧车与服务器名都是空白时给占位名`() {
        assertEquals(
            "离线书籍 e2d0c13d",
            offlineBookDisplayName(serverName = "  ", sidecarTitle = "", itemId = itemId),
        )
        assertEquals(
            "离线书籍 e2d0c13d",
            offlineBookDisplayName(serverName = null, sidecarTitle = null, itemId = itemId),
        )
    }
}
