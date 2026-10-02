package com.zhangwenkang.cinefin.network

/** 已信任的自签证书：信任键（host:port）+ 归一化后的 SHA-256 指纹。 */
data class TrustedCertificate(val trustKey: String, val fingerprint: String)

/**
 * 自签证书信任仓库（TOFU，Trust On First Use）。
 *
 * 只保存用户明确确认过的「地址 → 证书 SHA-256 指纹」；从不对系统 CA 之外、用户没确认过的证书做任何隐式放行。 清除记录后，对应地址立即回到默认校验（证书不受系统信任时连接被拒绝）。
 */
interface CertificateTrustStore {
    /** 该地址上被信任的指纹；没有记录时返回 null。 */
    fun trustedFingerprint(trustKey: String): String?

    /** 全部已信任记录，按信任键排序，供管理界面展示。 */
    fun trustedCertificates(): List<TrustedCertificate>

    /** 用户确认信任：记录该地址的指纹（覆盖旧值）。 */
    fun trust(trustKey: String, fingerprint: String)

    /** 清除该地址的信任记录。 */
    fun clear(trustKey: String)
}
