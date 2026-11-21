package com.zhangwenkang.cinefin.repository

import com.zhangwenkang.cinefin.local.localItemIdFor
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** W6-T13：本地书打开跳过远程进度拉取；服务器书路径回归原语义（无网不查、2s 超时、失败回退）。 */
class ReaderProgressRemoteGateTest {
    private val localUri =
        "content://com.android.externalstorage.documents/tree/primary%3ABooks/document/w6-test-utf8.txt"
    private val localItemId = localItemIdFor(localUri)
    private val serverItemId = UUID.fromString("8bb46d1a-2e5f-4d5f-9d19-8e4a2f4f0e1a")

    private val localProgress =
        ReadingProgress(
            itemId = localItemId,
            locatorJson = """{"href":"OEBPS/chapter-4.xhtml","locations":{"progression":0.42}}""",
            progression = 0.42,
            positionTicks = 4_200,
            updatedAt = Instant.ofEpochMilli(1_000),
            runtimeTicks = 10_000,
        )
    private val remoteProgress =
        ReadingProgress(
            itemId = serverItemId,
            progression = 0.75,
            positionTicks = 7_500,
            updatedAt = Instant.ofEpochMilli(2_000),
        )

    @Test
    fun `本地书跳过远程查询_零调用且返回本地进度`() = runBlocking {
        var remoteCalls = 0
        val merged =
            resolveReadingProgressWithRemote(
                itemId = localItemId,
                local = localProgress,
                isLocalItem = true,
                hasActiveNetwork = true,
                fetchRemote = {
                    remoteCalls += 1
                    remoteProgress.copy(itemId = localItemId)
                },
            )

        // 本地 UUID 服务器不认识：不发请求，因此也不会产生 2s 超时等待。
        assertEquals("本地书不得触碰远程进度接口", 0, remoteCalls)
        assertNotNull(merged)
        assertEquals(0.42, merged!!.progression, 0.0001)
        // 本地 locator 原样保留，打开位置精确。
        assertEquals(localProgress.locatorJson, merged.locatorJson)
        assertEquals(10_000, merged.runtimeTicks)
    }

    @Test
    fun `服务器书仍查询远程进度并按时间戳合并`() = runBlocking {
        var remoteCalls = 0
        val merged =
            resolveReadingProgressWithRemote(
                itemId = serverItemId,
                local = localProgress.copy(itemId = serverItemId),
                isLocalItem = false,
                hasActiveNetwork = true,
                fetchRemote = {
                    remoteCalls += 1
                    remoteProgress
                },
            )

        assertEquals(1, remoteCalls)
        assertNotNull(merged)
        // 远端进度更新 → 胜出，且不套用本机旧 locator。
        assertEquals(0.75, merged!!.progression, 0.0001)
        assertEquals("", merged.locatorJson)
    }

    @Test
    fun `无网络时不查询远程进度`() = runBlocking {
        var remoteCalls = 0
        val merged =
            resolveReadingProgressWithRemote(
                itemId = serverItemId,
                local = localProgress.copy(itemId = serverItemId),
                isLocalItem = false,
                hasActiveNetwork = false,
                fetchRemote = {
                    remoteCalls += 1
                    remoteProgress
                },
            )

        assertEquals(0, remoteCalls)
        assertEquals(0.42, merged!!.progression, 0.0001)
        assertEquals(localProgress.locatorJson, merged.locatorJson)
    }

    @Test
    fun `远程超时回退本地进度`() = runBlocking {
        var remoteCalls = 0
        val merged =
            resolveReadingProgressWithRemote(
                itemId = serverItemId,
                local = localProgress.copy(itemId = serverItemId),
                isLocalItem = false,
                hasActiveNetwork = true,
                fetchRemote = {
                    remoteCalls += 1
                    delay(5_000)
                    remoteProgress
                },
                timeoutMillis = 50,
            )

        assertEquals(1, remoteCalls)
        assertEquals(0.42, merged!!.progression, 0.0001)
        assertEquals(localProgress.locatorJson, merged.locatorJson)
    }

    @Test
    fun `远程读取失败回退本地进度`() = runBlocking {
        val merged =
            resolveReadingProgressWithRemote(
                itemId = serverItemId,
                local = localProgress.copy(itemId = serverItemId),
                isLocalItem = false,
                hasActiveNetwork = true,
                fetchRemote = { throw IOException("offline") },
            )

        assertEquals(0.42, merged!!.progression, 0.0001)
        assertEquals(localProgress.locatorJson, merged.locatorJson)
    }
}
