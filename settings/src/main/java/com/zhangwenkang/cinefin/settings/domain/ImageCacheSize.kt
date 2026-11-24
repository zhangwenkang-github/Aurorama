package com.zhangwenkang.cinefin.settings.domain

/**
 * 图片磁盘缓存容量的换算规则。
 *
 * 偏好 [AppPreferences.imageCacheSize] 的单位是 MB：0（或负值）= 不设上限，由 Coil 按可用空间自行管理； 正数 = 该 MB 数。
 */
object ImageCacheSize {
    /** 全新安装的默认值：不设上限（0）。 */
    const val DEFAULT_MB = 0

    private const val MB_IN_BYTES = 1024L * 1024

    /**
     * 偏好值换算成 Coil `DiskCache.Builder.maxSizeBytes` 的参数。
     *
     * 返回 null = 不设上限（调用方不调用 maxSizeBytes）；Coil 侧 maxSizeBytes 要求 > 0， 所以 0 / 负值必须在这里就被拦下，不能直接传 0。
     */
    fun maxDiskCacheBytes(mb: Int): Long? = if (mb > 0) mb * MB_IN_BYTES else null
}
