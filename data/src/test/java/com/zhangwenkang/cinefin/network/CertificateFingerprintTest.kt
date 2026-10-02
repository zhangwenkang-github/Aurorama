package com.zhangwenkang.cinefin.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateFingerprintTest {
    private val fingerprint = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun `sha256 指纹是 64 位小写十六进制`() {
        val digest = sha256Fingerprint("cinefin".toByteArray())

        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `归一化指纹忽略大小写与分隔符`() {
        assertEquals(fingerprint, normalizeFingerprint(fingerprint.uppercase()))
        assertEquals(fingerprint, normalizeFingerprint(formatFingerprint(fingerprint)))
        assertEquals(fingerprint, normalizeFingerprint(fingerprint.chunked(8).joinToString(" ")))

        assertNull(normalizeFingerprint("abc"))
        assertNull(normalizeFingerprint("z".repeat(64)))
        assertNull(normalizeFingerprint(fingerprint.dropLast(1)))
    }

    @Test
    fun `展示格式为大写冒号分隔`() {
        val formatted = formatFingerprint(fingerprint)

        assertTrue(formatted.startsWith("01:23:45:67:89:AB:CD:EF"))
        assertEquals(64 + 31, formatted.length)
        assertEquals(fingerprint, normalizeFingerprint(formatted))
    }

    @Test
    fun `指纹匹配要求完全一致`() {
        assertTrue(fingerprintsMatch(fingerprint, formatFingerprint(fingerprint)))
        assertFalse(fingerprintsMatch(fingerprint, fingerprint.dropLast(1) + "0"))
        assertFalse(fingerprintsMatch("not-a-fingerprint", fingerprint))
    }

    @Test
    fun `信任键按主机名与端口归一化`() {
        assertEquals("example.com:443", trustKeyOf("Example.COM", 443))
        assertEquals("192.168.1.10:8920", trustKeyOf(" 192.168.1.10 ", 8920))
        assertEquals("::1:8920", trustKeyOf("[::1]", 8920))

        assertEquals("example.com", hostOfTrustKey("example.com:443"))
        assertEquals("::1", hostOfTrustKey("::1:8920"))
        assertEquals(443, portOfTrustKey("example.com:443"))
        assertEquals(-1, portOfTrustKey("example.com"))
    }
}
