package com.zhangwenkang.cinefin.utils

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.zhangwenkang.cinefin.core.R as CoreR
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSources
import com.zhangwenkang.cinefin.models.FindroidTrickplayInfo
import com.zhangwenkang.cinefin.models.UiText
import com.zhangwenkang.cinefin.models.toFindroidEpisode
import com.zhangwenkang.cinefin.models.toFindroidEpisodeDto
import com.zhangwenkang.cinefin.models.toFindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.toFindroidMovie
import com.zhangwenkang.cinefin.models.toFindroidMovieDto
import com.zhangwenkang.cinefin.models.toFindroidSeasonDto
import com.zhangwenkang.cinefin.models.toFindroidSegmentsDto
import com.zhangwenkang.cinefin.models.toFindroidShowDto
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.models.toFindroidSourceDto
import com.zhangwenkang.cinefin.models.toFindroidTrickplayInfoDto
import com.zhangwenkang.cinefin.models.toFindroidUserDataDto
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.work.DownloadRetryWorker
import com.zhangwenkang.cinefin.work.ImagesDownloaderWorker
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.Exception
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import timber.log.Timber

class DownloaderImpl(
    private val context: Context,
    private val database: ServerDatabaseDao,
    private val jellyfinRepository: JellyfinRepository,
    private val appPreferences: AppPreferences,
    private val workManager: WorkManager,
) : Downloader {
    private val downloadManager = context.getSystemService(DownloadManager::class.java)

    /**
     * W32：本进程内已经自动重试过的任务。
     *
     * 网络恢复 worker 可能被多次调度，用集合保证「每个任务每个进程只自动重试一次」，进程重启后重新获得一次机会。
     */
    private val autoRetriedSourceIds = ConcurrentHashMap.newKeySet<String>()

    // TODO: We should probably move most (if not all) code to a worker.
    //  At this moment it is possible that some things are not downloaded due to the user leaving
    //  the current screen
    override suspend fun downloadItem(
        item: FindroidItem,
        sourceId: String,
        storageIndex: Int,
    ): Pair<Long, UiText?> = coroutineScope {
        try {
            val source =
                jellyfinRepository.getMediaSources(item.id, true).first { it.id == sourceId }
            val segments = jellyfinRepository.getSegments(item.id)
            val trickplayInfo =
                if (item is FindroidSources) {
                    item.trickplayInfo?.get(sourceId)
                } else {
                    null
                }
            val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
            if (
                storageLocation == null ||
                    Environment.getExternalStorageState(storageLocation) !=
                        Environment.MEDIA_MOUNTED
            ) {
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(CoreR.string.storage_unavailable),
                )
            }
            val path =
                Uri.fromFile(File(storageLocation, "downloads/${item.id}.${source.id}.download"))
            val stats = StatFs(storageLocation.path)
            if (stats.availableBytes < source.size) {
                return@coroutineScope Pair(
                    -1,
                    UiText.StringResource(
                        CoreR.string.not_enough_storage,
                        Formatter.formatFileSize(context, source.size),
                        Formatter.formatFileSize(context, stats.availableBytes),
                    ),
                )
            }
            val request =
                DownloadManager.Request(source.path.toUri())
                    .setTitle(item.name)
                    .setAllowedOverMetered(
                        appPreferences.getValue(appPreferences.downloadOverMobileData)
                    )
                    .setAllowedOverRoaming(
                        appPreferences.getValue(appPreferences.downloadWhenRoaming)
                    )
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationUri(path)
            val downloadId = downloadManager.enqueue(request)

            when (item) {
                is FindroidMovie -> {
                    database.insertMovie(
                        item.toFindroidMovieDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )
                }
                is FindroidEpisode -> {
                    val show = jellyfinRepository.getShow(item.seriesId)
                    database.insertShow(
                        show.toFindroidShowDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )
                    val season = jellyfinRepository.getSeason(item.seasonId)
                    database.insertSeason(season.toFindroidSeasonDto())
                    database.insertEpisode(
                        item.toFindroidEpisodeDto(
                            appPreferences.getValue(appPreferences.currentServer)
                        )
                    )

                    startImagesDownloader(show)
                    startImagesDownloader(season)
                }
            }

            val sourceDto = source.toFindroidSourceDto(item.id, path.path.orEmpty())

            database.insertSource(
                sourceDto.copy(
                    downloadId = downloadId,
                    taskStatus = DownloadTaskStatus.PENDING.name,
                    failureReason = null,
                    updatedAt = System.currentTimeMillis(),
                )
            )
            database.insertUserData(item.toFindroidUserDataDto(jellyfinRepository.getUserId()))

            downloadExternalMediaStreams(item, source, storageIndex)

            segments.forEach { database.insertSegment(it.toFindroidSegmentsDto(item.id)) }

            if (trickplayInfo != null) {
                downloadTrickplayData(item.id, sourceId, trickplayInfo)
            }

            startImagesDownloader(item)
            return@coroutineScope Pair(downloadId, null)
        } catch (e: Exception) {
            // W32：失败不再删除整条下载记录——保留任务并把可读原因写回，供下载管理页展示 / 重试。
            archiveFailedSource(item.id, sourceId, classifyDownloadException(e))
            Timber.e(e)
            return@coroutineScope Pair(
                -1,
                if (e.message != null) UiText.DynamicString(e.message!!)
                else UiText.StringResource(CoreR.string.unknown_error),
            )
        }
    }

    override suspend fun cancelDownload(item: FindroidItem, downloadId: Long) {
        val source =
            database.getSourceByDownloadId(downloadId)?.toFindroidSource(database) ?: return
        if (source.downloadId != null) {
            downloadManager.remove(source.downloadId!!)
        }
        deleteItem(item, source)
    }

    override suspend fun deleteItem(item: FindroidItem, source: FindroidSource) {
        when (item) {
            is FindroidMovie -> {
                database.deleteMovie(item.id)
            }
            is FindroidEpisode -> {
                database.deleteEpisode(item.id)
                val remainingEpisodes = database.getEpisodesBySeasonId(item.seasonId)
                if (remainingEpisodes.isEmpty()) {
                    database.deleteSeason(item.seasonId)
                    database.deleteUserData(item.seasonId)
                    File(context.filesDir, "trickplay/${item.seasonId}").deleteRecursively()
                    File(context.filesDir, "images/${item.seasonId}").deleteRecursively()
                    val remainingSeasons = database.getSeasonsByShowId(item.seriesId)
                    if (remainingSeasons.isEmpty()) {
                        database.deleteShow(item.seriesId)
                        database.deleteUserData(item.seriesId)
                        File(context.filesDir, "trickplay/${item.seriesId}").deleteRecursively()
                        File(context.filesDir, "images/${item.seriesId}").deleteRecursively()
                    }
                }
            }
        }

        database.deleteSource(source.id)
        File(source.path).delete()
        deletePartialSidecar(source.path)

        val mediaStreams = database.getMediaStreamsBySourceId(source.id)
        for (mediaStream in mediaStreams) {
            File(mediaStream.path).delete()
        }
        database.deleteMediaStreamsBySourceId(source.id)

        database.deleteUserData(item.id)

        File(context.filesDir, "trickplay/${item.id}").deleteRecursively()
        File(context.filesDir, "images/${item.id}").deleteRecursively()
    }

    override suspend fun getProgress(downloadId: Long?): Pair<Int, Int> {
        var downloadStatus = -1
        var progress = -1
        if (downloadId == null) {
            return Pair(downloadStatus, progress)
        }
        val query = DownloadManager.Query().setFilterById(downloadId)
        downloadManager.query(query).use { cursor ->
            if (cursor.moveToFirst()) {
                downloadStatus =
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                when (downloadStatus) {
                    DownloadManager.STATUS_RUNNING -> {
                        val totalBytes =
                            cursor.getLong(
                                cursor.getColumnIndexOrThrow(
                                    DownloadManager.COLUMN_TOTAL_SIZE_BYTES
                                )
                            )
                        if (totalBytes > 0) {
                            val downloadedBytes =
                                cursor.getLong(
                                    cursor.getColumnIndexOrThrow(
                                        DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR
                                    )
                                )
                            progress = downloadedBytes.times(100).div(totalBytes).toInt()
                        }
                    }

                    DownloadManager.STATUS_SUCCESSFUL -> {
                        progress = 100
                    }
                }
            } else {
                downloadStatus = DownloadManager.STATUS_FAILED
            }
        }
        return Pair(downloadStatus, progress)
    }

    override suspend fun refreshDownloadTasks(): List<DownloadTask> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val tasks = mutableListOf<DownloadTask>()

            for (source in database.getPendingSources()) {
                val snapshot = downloadManager.querySnapshot(source.downloadId)
                val status =
                    DownloadTaskRules.resolveStatus(
                        persistedStatus = source.taskStatus,
                        managerStatus = snapshot?.status,
                        pathIsPartial = source.path.endsWith(".download"),
                    )
                val failureReason =
                    DownloadTaskRules.resolveFailureReason(
                        persistedReason = source.failureReason,
                        managerStatus = snapshot?.status,
                        managerReason = snapshot?.reason,
                    )

                if (status == DownloadTaskStatus.COMPLETED) {
                    // 补偿 DownloadReceiver 漏掉的完成回调（进程被杀 / 广播丢失）：补重命名 + 落状态。
                    finishDownloadedSource(database, source)
                    continue
                }

                if (
                    source.taskStatus != status.name || source.failureReason != failureReason?.name
                ) {
                    database.setSourceTaskStatus(
                        source.id,
                        status.name,
                        failureReason?.name,
                        now,
                    )
                }

                tasks +=
                    DownloadTask(
                        itemId = source.itemId,
                        sourceId = source.id,
                        name = itemNameFor(source.itemId, source.name),
                        path = source.path,
                        downloadId = source.downloadId,
                        status = status,
                        failureReason = failureReason,
                        downloadedBytes =
                            (snapshot?.downloadedBytes ?: 0L).takeIf { it > 0L }
                                ?: partialFileSize(source.path),
                        totalBytes = snapshot?.totalBytes ?: 0L,
                        updatedAt = source.updatedAt.takeIf { it > 0L } ?: now,
                    )
            }

            // 有网络类失败任务且本进程还没自动重试过：入队带 CONNECTED 约束的唯一 worker（网络恢复后执行）。
            if (
                tasks.any {
                    DownloadTaskRules.isAutoRetryEligible(it.status, it.failureReason) &&
                        it.sourceId !in autoRetriedSourceIds
                }
            ) {
                enqueueRetryWorker()
            }

            tasks.sortedByDescending { it.updatedAt }
        }

    override suspend fun pauseTask(task: DownloadTask): Boolean =
        withContext(Dispatchers.IO) {
            // 系统任务取消，但保留已写入的残片（进度大小供 UI 展示；恢复走安全重试）。
            task.downloadId?.let { runCatching { downloadManager.remove(it) } }
            // remove 会删除目标残片，但 sidecar（.download.js）可能残留：主动清干净。
            deletePartialArtifacts(task.path)
            database.setSourceTaskStatus(
                task.sourceId,
                DownloadTaskStatus.PAUSED.name,
                null,
                System.currentTimeMillis(),
            )
            true
        }

    override suspend fun resumeTask(task: DownloadTask): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) {
            val waitingForNetwork =
                task.status == DownloadTaskStatus.PAUSED &&
                    task.failureReason == DownloadFailureReason.NETWORK_UNAVAILABLE &&
                    downloadManager.querySnapshot(task.downloadId) != null
            if (waitingForNetwork) {
                // 系统级暂停（等待网络 / 等待重试）：网络恢复后 DownloadManager 自行续传，无需重新入队。
                return@withContext task.downloadId?.let { it to null } ?: (-1L to null)
            }
            restartTask(task)
        }

    override suspend fun retryTask(task: DownloadTask): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) { restartTask(task) }

    override suspend fun deleteTask(task: DownloadTask): Boolean =
        withContext(Dispatchers.IO) {
            task.downloadId?.let { runCatching { downloadManager.remove(it) } }
            deletePartialArtifacts(task.path)
            val source = database.getSources(task.itemId).firstOrNull { it.id == task.sourceId }
            val item = findItem(task.itemId)
            if (source != null && item != null) {
                deleteItem(item, source.toFindroidSource(database))
            } else {
                deletePartialArtifacts(task.path)
                database.deleteSource(task.sourceId)
            }
            true
        }

    override suspend fun retryNetworkFailures(): Int =
        withContext(Dispatchers.IO) {
            var retried = 0
            for (task in refreshDownloadTasks()) {
                if (!DownloadTaskRules.isAutoRetryEligible(task.status, task.failureReason))
                    continue
                if (!autoRetriedSourceIds.add(task.sourceId)) continue
                val (downloadId, _) = restartTask(task)
                if (downloadId != -1L) {
                    retried += 1
                    Timber.i("网络恢复自动重试下载：${task.name}（${task.failureReason}）")
                }
            }
            retried
        }

    override suspend fun getStorageUsage(): DownloadStorageUsage =
        withContext(Dispatchers.IO) {
            val locations = context.getExternalFilesDirs(null).filterNotNull()
            val usedBytes =
                locations
                    .map { File(it, "downloads") }
                    .distinct()
                    .sumOf { dir ->
                        dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
                    }
            val stats = StatFs(locations.firstOrNull()?.path ?: context.filesDir.path)
            DownloadStorageUsage(
                usedBytes = usedBytes,
                availableBytes = stats.availableBytes,
                totalBytes = stats.totalBytes,
            )
        }

    /** 安全重试：清理残片后重新入队（进程被杀 / 暂停期间由 DownloadManager 自行延续的任务不经过这里）。 */
    private suspend fun restartTask(task: DownloadTask): Pair<Long, UiText?> =
        withContext(Dispatchers.IO) {
            val item =
                findItem(task.itemId)
                    ?: return@withContext Pair(
                        -1L,
                        UiText.StringResource(CoreR.string.download_task_item_missing),
                    )
            task.downloadId?.let { runCatching { downloadManager.remove(it) } }
            deletePartialFile(task.path)
            database.setSourceDownloadId(task.sourceId, null)
            database.setSourceTaskStatus(
                task.sourceId,
                DownloadTaskStatus.PENDING.name,
                null,
                System.currentTimeMillis(),
            )
            downloadItem(item, task.sourceId, storageIndexFor(task.path))
        }

    private suspend fun archiveFailedSource(
        itemId: UUID,
        sourceId: String,
        reason: DownloadFailureReason,
    ) {
        val source =
            runCatching { database.getSources(itemId).firstOrNull { it.id == sourceId } }
                .getOrNull() ?: return
        database.setSourceTaskStatus(
            source.id,
            DownloadTaskStatus.FAILED.name,
            reason.name,
            System.currentTimeMillis(),
        )
    }

    private suspend fun findItem(itemId: UUID): FindroidItem? {
        val movie = runCatching { database.getMovie(itemId) }.getOrNull()
        if (movie != null) {
            return movie.toFindroidMovie(database, jellyfinRepository.getUserId())
        }
        val episode = runCatching { database.getEpisode(itemId) }.getOrNull()
        if (episode != null) {
            return episode.toFindroidEpisode(database, jellyfinRepository.getUserId())
        }
        return null
    }

    private suspend fun itemNameFor(itemId: UUID, fallback: String): String {
        runCatching { database.getMovie(itemId).name }
            .getOrNull()
            ?.let { if (it.isNotBlank()) return it }
        runCatching { database.getEpisode(itemId).name }
            .getOrNull()
            ?.let { if (it.isNotBlank()) return it }
        return fallback
    }

    private fun storageIndexFor(path: String): Int {
        val index =
            context.getExternalFilesDirs(null).filterNotNull().indexOfFirst {
                path.startsWith(it.path)
            }
        return if (index >= 0) index else 0
    }

    private fun enqueueRetryWorker() {
        val constraints =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            OneTimeWorkRequestBuilder<DownloadRetryWorker>().setConstraints(constraints).build()
        workManager.enqueueUniqueWork(
            uniqueWorkName = DOWNLOAD_RETRY_WORK_NAME,
            existingWorkPolicy = ExistingWorkPolicy.KEEP,
            request = request,
        )
    }

    private suspend fun downloadExternalMediaStreams(
        item: FindroidItem,
        source: FindroidSource,
        storageIndex: Int = 0,
    ) {
        val storageLocation = context.getExternalFilesDirs(null)[storageIndex]
        for (mediaStream in source.mediaStreams.filter { it.isExternal }) {
            val id = UUID.randomUUID()
            val streamPath =
                Uri.fromFile(
                    File(storageLocation, "downloads/${item.id}.${source.id}.$id.download")
                )
            database.insertMediaStream(
                mediaStream.toFindroidMediaStreamDto(id, source.id, streamPath.path.orEmpty())
            )
            val request =
                DownloadManager.Request(mediaStream.path!!.toUri())
                    .setTitle(mediaStream.title)
                    .setAllowedOverMetered(
                        appPreferences.getValue(appPreferences.downloadOverMobileData)
                    )
                    .setAllowedOverRoaming(
                        appPreferences.getValue(appPreferences.downloadWhenRoaming)
                    )
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN)
                    .setDestinationUri(streamPath)
            val downloadId = downloadManager.enqueue(request)
            database.setMediaStreamDownloadId(id, downloadId)
        }
    }

    private suspend fun downloadTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
    ) {
        val maxIndex =
            ceil(
                    trickplayInfo.thumbnailCount
                        .toDouble()
                        .div(trickplayInfo.tileWidth * trickplayInfo.tileHeight)
                )
                .toInt()
        val byteArrays = mutableListOf<ByteArray>()
        for (i in 0..maxIndex) {
            jellyfinRepository.getTrickplayData(itemId, trickplayInfo.width, i)?.let { byteArray ->
                byteArrays.add(byteArray)
            }
        }
        saveTrickplayData(itemId, sourceId, trickplayInfo, byteArrays)
    }

    private suspend fun saveTrickplayData(
        itemId: UUID,
        sourceId: String,
        trickplayInfo: FindroidTrickplayInfo,
        byteArrays: List<ByteArray>,
    ) {
        val basePath = "trickplay/$itemId/$sourceId"
        database.insertTrickplayInfo(trickplayInfo.toFindroidTrickplayInfoDto(sourceId))
        File(context.filesDir, basePath).mkdirs()
        for ((i, byteArray) in byteArrays.withIndex()) {
            val file = File(context.filesDir, "$basePath/$i")
            file.writeBytes(byteArray)
        }
    }

    private fun startImagesDownloader(item: FindroidItem) {
        val downloadImagesRequest =
            OneTimeWorkRequestBuilder<ImagesDownloaderWorker>()
                .setInputData(workDataOf(ImagesDownloaderWorker.KEY_ITEM_ID to item.id.toString()))
                .build()

        workManager.enqueue(downloadImagesRequest)
    }
}

/** W32 唯一的网络恢复重试任务名（KEEP 策略保证同一时间只有一份）。 */
internal const val DOWNLOAD_RETRY_WORK_NAME = "downloadNetworkRetry"
