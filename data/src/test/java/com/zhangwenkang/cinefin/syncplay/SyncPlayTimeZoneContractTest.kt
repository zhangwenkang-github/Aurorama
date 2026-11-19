package com.zhangwenkang.cinefin.syncplay

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.jellyfin.sdk.model.api.UtcTimeResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * W4-T8b 时区契约（T8a 遗留项：「时区换算 `toInstantInSystemZone()` 严格可逆」）。
 *
 * SDK 的 `DateTimeSerializer` 按 `ZoneId.systemDefault()` 把服务器时间解析成 [LocalDateTime]，序列化方向同用系统时区；
 * [toInstantInSystemZone] / [toServerLocalDateTime] 必须严格可逆，且换算结果只由「当时的系统时区」决定 —— 这里显式切到
 * +05:30（非整小时偏移，最容易暴露写死 UTC / 整小时假设的 bug），不依赖测试机默认时区。
 */
class SyncPlayTimeZoneContractTest {
    private val contractZone: ZoneId = ZoneId.of("Asia/Kolkata")
    private lateinit var originalDefault: TimeZone

    @Before
    fun useHalfHourZone() {
        originalDefault = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(contractZone))
    }

    @After
    fun restoreDefaultZone() {
        TimeZone.setDefault(originalDefault)
    }

    @Test
    fun `Instant 与服务器 LocalDateTime 往返严格可逆`() {
        // 前置：系统时区确实被切成了 +05:30（否则本测试机时区的巧合会掩盖实现错误）
        assertEquals(contractZone, ZoneId.systemDefault())

        val instants =
            listOf(
                Instant.parse("2026-11-19T12:00:00Z"),
                Instant.parse("2026-11-19T12:00:00.123Z"),
                Instant.parse("2026-11-19T23:59:59.999999999Z"),
                Instant.EPOCH,
                Instant.parse("1969-12-31T23:59:59.500Z"),
            )

        instants.forEach { instant ->
            val local = instant.toServerLocalDateTime()
            assertEquals(instant, local.toInstantInSystemZone())
            // 二次往返也不漂移（毫秒 / 半秒 / 跨年边界都覆盖）
            assertEquals(
                instant,
                local.toInstantInSystemZone().toServerLocalDateTime().toInstantInSystemZone(),
            )
        }
    }

    @Test
    fun `服务器墙钟时刻按 systemDefault 解释`() {
        // 12:00Z 在 +05:30 是 17:30
        assertEquals(
            LocalDateTime.of(2026, 11, 19, 17, 30),
            Instant.parse("2026-11-19T12:00:00Z").toServerLocalDateTime(),
        )
        assertEquals(
            Instant.parse("2026-11-19T12:00:00Z"),
            LocalDateTime.of(2026, 11, 19, 17, 30).toInstantInSystemZone(),
        )
        // 跨日：00:15（+05:30）落在前一天 18:45Z
        assertEquals(
            Instant.parse("2026-11-18T18:45:00Z"),
            LocalDateTime.of(2026, 11, 19, 0, 15).toInstantInSystemZone(),
        )
    }

    @Test
    fun `getUtcTime 映射在非整小时时区下不丢时刻`() {
        val reception = LocalDateTime.of(2026, 11, 19, 17, 30, 0, 123_000_000)
        val transmission = LocalDateTime.of(2026, 11, 19, 17, 30, 1, 456_000_000)

        val serverTime =
            UtcTimeResponse(
                    requestReceptionTime = reception,
                    responseTransmissionTime = transmission,
                )
                .toDomain()

        assertEquals(Instant.parse("2026-11-19T12:00:00.123Z"), serverTime.requestReceptionTime)
        assertEquals(
            Instant.parse("2026-11-19T12:00:01.456Z"),
            serverTime.responseTransmissionTime,
        )
    }
}
