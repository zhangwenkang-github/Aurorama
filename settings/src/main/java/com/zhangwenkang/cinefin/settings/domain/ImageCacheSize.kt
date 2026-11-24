package com.zhangwenkang.cinefin.settings.domain

/**
 * 图片磁盘缓存容量的换算规则。
 *
 * 偏好 [AppPreferences.imageCacheSize] 的单位是 MB：0（或负值）= 不设固定上限，缓存按可用空间动态管理； 正数 = 该 MB 数的显式上限。
 */
object ImageCacheSize {
    /**
     * 全新安装的默认值：不设固定上限（0）。
     *
     * 0 分支不由本类换算字节数：调用方保留 Coil 的默认策略（可用空间 × 2%，10MB 保底）， 并把 `DiskCache.Builder.maximumMaxSizeBytes`
     * 提到 `Long.MAX_VALUE`，解除 Coil 250MB 的默认硬顶。
     */
    const val DEFAULT_MB = 0

    private const val MB_IN_BYTES = 1024L * 1024

    /**
     * 偏好值换算成 Coil `DiskCache.Builder.maxSizeBytes` 的参数。
     *
     * 返回 null = 不设固定上限（调用方不写死 maxSizeBytes，改走 Coil 的可用空间百分比策略）； 正数 = 显式上限字节数（Coil 的 maxSizeBytes
     * 分支不会再被 maximumMaxSizeBytes 钳制）。 Coil 侧 maxSizeBytes 要求 > 0，所以 0 / 负值必须在这里就被拦下，不能直接传 0。
     */
    fun maxDiskCacheBytes(mb: Int): Long? = if (mb > 0) mb * MB_IN_BYTES else null
}
