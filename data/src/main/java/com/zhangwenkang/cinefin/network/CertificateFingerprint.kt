package com.zhangwenkang.cinefin.network

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale

private const val HEX_CHARS = "0123456789abcdef"
private const val SHA256_HEX_LENGTH = 64

/** 证书 DER 编码的 SHA-256 指纹：64 位小写十六进制、无分隔符（存储与比较统一用这种形式）。 */
fun sha256Fingerprint(encodedCertificate: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(encodedCertificate)
    val output = CharArray(digest.size * 2)
    digest.forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        output[index * 2] = HEX_CHARS[value ushr 4]
        output[index * 2 + 1] = HEX_CHARS[value and 0x0F]
    }
    return String(output)
}

/** 某张证书的 SHA-256 指纹。 */
fun fingerprintOf(certificate: X509Certificate): String = sha256Fingerprint(certificate.encoded)

/** 归一化用户输入 / 历史存储中的指纹：去掉冒号、空格与连字符并转小写； 不是 64 位十六进制时返回 null（宁可不匹配，也不做模糊放行）。 */
fun normalizeFingerprint(raw: String): String? {
    val compact =
        raw.filterNot { it == ':' || it == '-' || it.isWhitespace() }.lowercase(Locale.ROOT)
    if (compact.length != SHA256_HEX_LENGTH) return null
    return compact.takeIf { fingerprint -> fingerprint.all { it in '0'..'9' || it in 'a'..'f' } }
}

/** 展示用格式：`AA:BB:…`（大写、冒号分隔），便于与系统证书页逐段核对。 */
fun formatFingerprint(raw: String): String {
    val normalized = normalizeFingerprint(raw) ?: return raw
    return normalized.chunked(2).joinToString(":") { it.uppercase(Locale.ROOT) }
}

/** 两个指纹是否指向同一张证书（忽略分隔符与大小写）。 */
fun fingerprintsMatch(expected: String, presented: String): Boolean {
    val expectedNormalized = normalizeFingerprint(expected) ?: return false
    val presentedNormalized = normalizeFingerprint(presented) ?: return false
    return expectedNormalized == presentedNormalized
}
