package com.zhangwenkang.cinefin.utils

import java.io.File

/** 下载根目录中的一个文件：相对路径（相对下载根目录，`/` 分隔）+ 字节数。 */
data class DownloadIndexFileEntry(val relativePath: String, val sizeBytes: Long)

/**
 * 下载行快照：只取对账需要的字段。
 *
 * [relativePath] 是 `sources.path` 相对某个下载根目录的相对路径；不属于该根目录的行由调用方先过滤掉（见
 * [DownloadIndexReconciler.relativize]）。
 */
data class DownloadIndexRowEntry(val relativePath: String)

/** 无任何下载行关联的媒体文件（对账报告里唯一允许清理的分类）。 */
data class DownloadIndexOrphanMedia(val relativePath: String, val sizeBytes: Long)

/**
 * W9：下载索引对账报告。
 *
 * 分类口径：
 * - [linked]：能关联到下载行（行 path 同名，或去掉派生后缀后同名）的文件，含残片 / witness；
 * - [orphanMedia]：命名符合下载落盘约定（`<itemId(UUID)>.<sourceId>…`）但没有任何下载行关联的媒体文件；
 * - [residual]：`.download` 残片 / `.finalizing` witness / 旧 DownloadManager `.js` sidecar 等派生文件，
 *   以及不认识的文件名——只要没有对应下载行就单列计数，**默认永不清理**（W9-T2 裁定维持现状）。
 */
data class DownloadIndexReconciliationReport(
    val linkedCount: Int = 0,
    val linkedBytes: Long = 0L,
    val orphanMedia: List<DownloadIndexOrphanMedia> = emptyList(),
    val residualCount: Int = 0,
    val residualBytes: Long = 0L,
) {
    val orphanMediaCount: Int
        get() = orphanMedia.size

    val orphanMediaBytes: Long
        get() = orphanMedia.sumOf { it.sizeBytes }

    val scannedCount: Int
        get() = linkedCount + orphanMediaCount + residualCount
}

/** W9：只统计 orphanMedia 的清理结果；residual 不会进入删除计划。 */
data class DownloadIndexDeletionOutcome(
    val deletedCount: Int = 0,
    val deletedBytes: Long = 0L,
    val failedCount: Int = 0,
)

/**
 * W9 下载索引对账规则（纯逻辑，单测覆盖；不碰 Room）。
 *
 * 背景：下载目录可能出现「磁盘上有媒体文件、`sources` 表没有对应行」的孤儿文件——既进不了离线降级曲库，也无法被本地短路命中， 只占空间。对账先出 dry-run
 * 报告，清理必须显式触发，且只删除 orphanMedia。
 *
 * 落盘命名（两代引擎一致，见 `DownloadCompletionCommitRules` / 旧 DownloadManager 实现）：
 * - 正式文件：`<itemId>.<sourceId>.g<generation>`（旧引擎 `<itemId>.<sourceId>[.<id>]`）
 * - 进行中残片：正式文件名 + `.download`
 * - finalize witness：`.` + 正式文件名 + `.finalizing`
 * - 旧 DownloadManager 断点 sidecar：`.{正式文件名}.js`（也兼容 `{正式文件名}.js`）
 */
object DownloadIndexReconciler {

    private const val PARTIAL_SUFFIX = ".download"
    private const val WITNESS_SUFFIX = ".finalizing"
    private const val LEGACY_SIDECAR_SUFFIX = ".js"

    /**
     * 媒体落盘名约定：`<UUID>.<任意 sourceId 片段>[.g<generation>]`。
     *
     * 只有命中这条约定的「正式文件」才可能进入 orphanMedia；不认识的文件名一律 residual，避免清理动作误伤。
     */
    private val MEDIA_TARGET_PATTERN =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\..+")

    private enum class ArtifactKind {
        TARGET,
        PARTIAL,
        WITNESS,
        LEGACY_SIDECAR,
    }

    /**
     * 对账一组（同一根目录的）文件与下载行快照。
     *
     * 关联判定用「目标名」：去掉 `.download` / `.finalizing` / `.js` 后的文件名。完成行的 `path` 是正式文件、 进行中行的 `path`
     * 是残片，都会归一到同一个目标名，因此残片与 witness 在行存在时归入 linked 而不是 residual。
     */
    fun reconcile(
        files: List<DownloadIndexFileEntry>,
        rows: List<DownloadIndexRowEntry>,
    ): DownloadIndexReconciliationReport {
        val rowPaths = rows.mapNotNull { normalizeRelativePath(it.relativePath) }.toSet()
        val rowTargets = rowPaths.mapNotNull(::targetPathOf).toSet()
        var linkedCount = 0
        var linkedBytes = 0L
        var residualCount = 0
        var residualBytes = 0L
        val orphans = mutableListOf<DownloadIndexOrphanMedia>()

        files.forEach { file ->
            val relativePath = normalizeRelativePath(file.relativePath) ?: return@forEach
            val size = file.sizeBytes.coerceAtLeast(0L)
            val target = targetPathOf(relativePath)
            val linked = relativePath in rowPaths || (target != null && target in rowTargets)
            when {
                linked -> {
                    linkedCount += 1
                    linkedBytes += size
                }
                artifactKindOf(fileName(relativePath)) == ArtifactKind.TARGET &&
                    isMediaTargetName(target) -> {
                    orphans += DownloadIndexOrphanMedia(relativePath, size)
                }
                else -> {
                    residualCount += 1
                    residualBytes += size
                }
            }
        }
        return DownloadIndexReconciliationReport(
            linkedCount = linkedCount,
            linkedBytes = linkedBytes,
            orphanMedia = orphans.sortedBy { it.relativePath },
            residualCount = residualCount,
            residualBytes = residualBytes,
        )
    }

    /** 多存储根目录（同一设备的主存储 + 外置存储）报告的合并。 */
    fun merge(reports: List<DownloadIndexReconciliationReport>): DownloadIndexReconciliationReport {
        if (reports.isEmpty()) return DownloadIndexReconciliationReport()
        return DownloadIndexReconciliationReport(
            linkedCount = reports.sumOf { it.linkedCount },
            linkedBytes = reports.sumOf { it.linkedBytes },
            orphanMedia = reports.flatMap { it.orphanMedia }.sortedBy { it.relativePath },
            residualCount = reports.sumOf { it.residualCount },
            residualBytes = reports.sumOf { it.residualBytes },
        )
    }

    /**
     * 把 `sources.path` 归一成相对 [rootPath] 的相对路径；不在该根目录下（含前缀相同但不同目录）返回 null。
     *
     * 纯字符串运算：下载根目录可能在主存储或外置存储，只有路径前缀精确命中 `<root>/` 才算属于该根。
     */
    fun relativize(rootPath: String, path: String): String? {
        val root = rootPath.trim().replace('\\', '/').trimEnd('/')
        val candidate = path.trim().replace('\\', '/')
        if (root.isEmpty()) return null
        if (!candidate.startsWith("$root/")) return null
        return normalizeRelativePath(candidate.removePrefix("$root/"))
    }

    /**
     * 在根目录内解析孤儿文件路径；越界（`..` / 绝对路径 / 符号链接逃逸）或不是媒体正式文件命名时返回 null。
     *
     * 让删除执行器与单测共用同一条边界检查。
     */
    fun resolveInsideRoot(root: File, relativePath: String): File? {
        val relative = normalizeRelativePath(relativePath) ?: return null
        if (!isMediaTargetName(targetPathOf(relative))) return null
        val rootDir = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val candidate =
            runCatching { File(rootDir, relative).canonicalFile }.getOrNull() ?: return null
        if (candidate == rootDir) return null
        if (!candidate.path.startsWith(rootDir.path + File.separator)) return null
        if (!candidate.isFile) return null
        if (artifactKindOf(candidate.name) != ArtifactKind.TARGET) return null
        return candidate
    }

    /**
     * 执行清理：只删除 [requested] 里、且 [report] 仍判定为 orphanMedia 的文件。
     *
     * 残片 / witness / sidecar 永远不在 [DownloadIndexReconciliationReport.orphanMedia] 里，因此不会被触碰。
     */
    fun deleteOrphans(
        root: File,
        report: DownloadIndexReconciliationReport,
        requested: Set<String>,
    ): DownloadIndexDeletionOutcome {
        if (requested.isEmpty()) return DownloadIndexDeletionOutcome()
        var deletedCount = 0
        var deletedBytes = 0L
        var failedCount = 0
        report.orphanMedia.forEach { orphan ->
            if (orphan.relativePath !in requested) return@forEach
            // 已消失（视为已达成目标）或路径不安全（越界 / 非媒体命名）：不删、不计失败。
            val file = resolveInsideRoot(root, orphan.relativePath) ?: return@forEach
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                deletedCount += 1
                deletedBytes += size
            } else {
                failedCount += 1
            }
        }
        return DownloadIndexDeletionOutcome(
            deletedCount = deletedCount,
            deletedBytes = deletedBytes,
            failedCount = failedCount,
        )
    }

    /** 归一一个相对路径；空段 / `.` / `..` / 绝对路径一律拒绝（返回 null）。 */
    fun normalizeRelativePath(path: String): String? {
        val normalized = path.trim().replace('\\', '/').removePrefix("./")
        if (normalized.isEmpty()) return null
        if (normalized.startsWith("/")) return null
        val segments = normalized.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        return segments.joinToString("/")
    }

    private fun fileName(relativePath: String): String = relativePath.substringAfterLast('/')

    private fun artifactKindOf(name: String): ArtifactKind =
        when {
            name.endsWith(PARTIAL_SUFFIX) -> ArtifactKind.PARTIAL
            name.startsWith(".") && name.endsWith(WITNESS_SUFFIX) -> ArtifactKind.WITNESS
            name.endsWith(LEGACY_SIDECAR_SUFFIX) -> ArtifactKind.LEGACY_SIDECAR
            else -> ArtifactKind.TARGET
        }

    /** 去掉派生后缀后的目标相对路径（保留目录前缀）；文件名退化成空时返回 null。 */
    private fun targetPathOf(relativePath: String): String? {
        val name = fileName(relativePath)
        val target =
            when (artifactKindOf(name)) {
                ArtifactKind.PARTIAL -> name.removeSuffix(PARTIAL_SUFFIX)
                ArtifactKind.WITNESS -> name.removePrefix(".").removeSuffix(WITNESS_SUFFIX)
                ArtifactKind.LEGACY_SIDECAR ->
                    name.removeSuffix(LEGACY_SIDECAR_SUFFIX).removePrefix(".")
                ArtifactKind.TARGET -> name
            }
        if (target.isEmpty() || target == ".") return null
        val directory = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        return if (directory.isEmpty()) target else "$directory/$target"
    }

    private fun isMediaTargetName(target: String?): Boolean =
        target != null && MEDIA_TARGET_PATTERN.matches(fileName(target))
}
