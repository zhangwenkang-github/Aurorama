package com.zhangwenkang.cinefin.network

import android.content.Context

/**
 * 基于应用私有 SharedPreferences 的信任仓库。
 *
 * 刻意不放进 Room：证书信任属于「网络身份」而不是服务器媒体数据，清数据/迁移时也应独立于媒体缓存； 同时 SharedPreferences 读写是同步且线程安全的，适合在 TLS
 * 握手线程里直接查询。
 */
class SharedPreferencesCertificateTrustStore(context: Context) : CertificateTrustStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun trustedFingerprint(trustKey: String): String? =
        preferences.getString(trustKey, null)?.let(::normalizeFingerprint)

    override fun trustedCertificates(): List<TrustedCertificate> =
        preferences.all.entries
            .mapNotNull { (key, value) ->
                (value as? String)?.let(::normalizeFingerprint)?.let { TrustedCertificate(key, it) }
            }
            .sortedBy { it.trustKey }

    override fun trust(trustKey: String, fingerprint: String) {
        val normalized = normalizeFingerprint(fingerprint) ?: return
        preferences.edit().putString(trustKey, normalized).apply()
    }

    override fun clear(trustKey: String) {
        preferences.edit().remove(trustKey).apply()
    }

    companion object {
        const val PREFERENCES_NAME = "cinefin_trusted_certificates"
    }
}
