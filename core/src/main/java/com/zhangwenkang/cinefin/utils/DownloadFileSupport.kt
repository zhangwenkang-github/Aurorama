package com.zhangwenkang.cinefin.utils

import java.io.File

/**
 * W76-B9：下载前存储预检查的路径解析（纯函数，单测覆盖）。
 *
 * 缺陷：`DownloaderImpl.executeTask` 曾在 `DownloadHttpEngine` 建目录**之前**执行
 * `StatFs(target.parentFile)`；首次下载（新装 / 清数据）时 `…/files/downloads` 尚不存在 → Android `StatFs(不存在路径)` 抛
 * `IllegalArgumentException` → 被通用 catch 收成 `DownloadFailureReason.UNKNOWN`（UI「失败 / 0 B」）。
 *
 * 修复：取目标父目录，必要时 `mkdirs()`；仍不是可用目录（父目录缺失 / 创建失败）则回落到 必然存在的
 * [fallbackDirectory]（`context.filesDir`），保证返回路径可安全交给 `StatFs`。
 */
internal object DownloadStorageRules {
    fun resolveStatPath(target: File, fallbackDirectory: File): File {
        val parent = target.parentFile
        if (parent != null && !parent.isDirectory) parent.mkdirs()
        return parent?.takeIf { it.isDirectory } ?: fallbackDirectory
    }
}

/**
 * W50：下载文件辅助（原 DownloadManagerSupport 的通用部分，随引擎替换迁移）。
 *
 * 保留对旧 `.js` 断点 sidecar 的清理：W32 时代 DownloadManager 的隐藏残渣可能仍留在设备上，删除任务时一并清掉。
 */
internal fun partialFileSize(path: String): Long = runCatching {
    File(path).length()
}
    .getOrDefault(0L)

internal fun deletePartialFile(path: String): Boolean = runCatching {
    !File(path).exists() || File(path).delete()
}
    .getOrDefault(false)

/**
 * DownloadManager 断点续传 sidecar（`<目标文件>.js`）。
 *
 * 真机（K60 / Android 15）实测：暂停（remove）会删除目标残片，但 sidecar 可能残留，导致下载页存储占用清不干净。 统一在暂停 / 重试 / 删除时主动清理。
 */
internal fun deletePartialSidecar(path: String): Boolean = runCatching {
    val target = File(path)
    val candidates = buildList {
        target.parentFile?.let { add(File(it, ".${target.name}.js")) }
        add(File("$path.js"))
    }
    candidates.all { !it.exists() || it.delete() }
}
    .getOrDefault(false)

internal fun deletePartialArtifacts(path: String) {
    deletePartialFile(path)
    deletePartialSidecar(path)
}
