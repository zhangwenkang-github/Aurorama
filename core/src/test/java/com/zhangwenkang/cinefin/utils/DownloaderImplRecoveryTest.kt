package com.zhangwenkang.cinefin.utils

import android.app.Notification
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMediaStream
import com.zhangwenkang.cinefin.models.FindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidSegmentType
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidTrickplayInfoDto
import com.zhangwenkang.cinefin.models.FindroidUserDataDto
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import timber.log.Timber

class DownloaderImplRecoveryTest {

    @Test
    fun `在线入口按代次提交并真实执行六项副作用`() = runBlocking {
        val fixture = Fixture()
        try {
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = fixture.movie(),
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )

            assertTrue(error == null)
            assertTrue(handle > 0L)
            val queued =
                DownloadCompletionCommitRules.downloadTargetFiles(
                    directory = fixture.downloads,
                    itemId = ITEM_ID.toString(),
                    sourceId = SOURCE_ID,
                    generation = 1L,
                )
            assertEquals(queued.first.path, fixture.dao.source?.path)

            fixture.downloader.runQueue()

            assertEquals(
                "source=${fixture.dao.source}, logs=${fixture.logs}",
                "COMPLETED",
                fixture.dao.source?.taskStatus,
            )
            fixture.assertSixEffects()
            assertEquals(2, fixture.imageScheduler.requests.size)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `恢复前置失败后的手动 retry 保持 witness 代次并完成 finalizer`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.prepareInterruptedCompletion()
            fixture.dao.itemAvailable = false

            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()

            assertEquals(DownloadTaskStatus.FAILED.name, fixture.dao.source?.taskStatus)
            assertEquals(GENERATION, fixture.dao.source?.taskGeneration)
            assertTrue(fixture.witness().isFile)

            fixture.dao.itemAvailable = true
            val (handle, error) =
                fixture.downloader.retryTask(
                    DownloadTask(
                        itemId = ITEM_ID,
                        sourceId = SOURCE_ID,
                        name = ITEM_NAME,
                        path = fixture.dao.source!!.path,
                        downloadId = downloadTaskHandle(SOURCE_ID),
                        status = DownloadTaskStatus.FAILED,
                        failureReason = DownloadFailureReason.FILE_ERROR,
                        downloadedBytes = MEDIA_BYTES.size.toLong(),
                        totalBytes = MEDIA_BYTES.size.toLong(),
                        updatedAt = 0L,
                        mediaKind = DownloadMediaKind.VIDEO,
                        taskGeneration = GENERATION,
                    )
                )

            assertTrue(error == null)
            assertTrue(handle > 0L)
            assertEquals(
                "可恢复 witness 的手动 retry 不得推进完成代次",
                GENERATION,
                fixture.dao.source?.taskGeneration,
            )
            assertTrue(fixture.witness().isFile)

            fixture.downloader.runQueue()

            assertEquals("COMPLETED", fixture.dao.source?.taskStatus)
            assertFalse(fixture.witness().exists())
            fixture.assertSixEffects()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `旧 final 不阻塞旧路径上的新代次残片提交`() = runBlocking {
        val fixture = Fixture()
        try {
            val oldEvidence = fixture.prepareLegacyTargetConflict()
            fixture.dao.source =
                FindroidSourceDto(
                    id = SOURCE_ID,
                    itemId = ITEM_ID,
                    name = ITEM_NAME,
                    type = FindroidSourceType.LOCAL,
                    path =
                        File(
                                fixture.downloads,
                                "${ITEM_ID}.${SOURCE_ID}.download",
                            )
                            .path,
                    taskStatus = DownloadTaskStatus.PENDING.name,
                    updatedAt = 0L,
                    downloadedBytes = MEDIA_BYTES.size.toLong(),
                    totalBytes = MEDIA_BYTES.size.toLong(),
                    engineVersion = DownloaderImpl.ENGINE_VERSION,
                    taskGeneration = GENERATION,
                )

            fixture.downloader.runQueue()

            val current =
                DownloadCompletionCommitRules.downloadTargetFiles(
                    directory = fixture.downloads,
                    itemId = ITEM_ID.toString(),
                    sourceId = SOURCE_ID,
                    generation = GENERATION,
                )
            assertEquals(
                "source=${fixture.dao.source}, legacy=${oldEvidence.legacyFinal}, logs=${fixture.logs}",
                "COMPLETED",
                fixture.dao.source?.taskStatus,
            )
            assertTrue(current.second.isFile)
            assertFalse(oldEvidence.legacyFinal.canonicalFile == current.second.canonicalFile)
            assertTrue(oldEvidence.legacyFinal.isFile)
            assertEquals("old payload", oldEvidence.legacyFinal.readText())
            assertTrue(oldEvidence.legacyWitness.isFile)
            fixture.assertSixEffects()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `重复恢复复用外置流且失败状态先落库`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.prepareInterruptedCompletion()
            val staleExternal = fixture.seedStaleExternal()
            fixture.repository.segmentFailuresRemaining = 1

            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()

            assertEquals(
                "source=${fixture.dao.source}, external=${fixture.transfer.externalDownloads}, logs=${fixture.logs}",
                "PENDING",
                fixture.dao.source?.taskStatus,
            )
            assertEquals(DownloadFailureReason.SERVER_ERROR.name, fixture.dao.source?.failureReason)
            assertTrue(fixture.dao.source!!.retryCount > 0)
            assertTrue(fixture.witness().isFile)
            assertFalse("新代次成功落盘后应安全清理旧外置流", staleExternal.isFile)
            assertEquals(1, fixture.transfer.externalDownloads)
            val externalPath = fixture.dao.externalStream()!!.path
            assertTrue(File(externalPath).isFile)
            val expectedExternalId =
                DownloadPostCommitRules.externalMediaStreamId(
                    sourceId = SOURCE_ID,
                    generation = GENERATION,
                    streamIndex = 1,
                    ordinal = 0,
                )
            assertTrue(
                "外置流目标必须按当前代次隔离：$externalPath",
                externalPath.endsWith(expectedExternalId.toString()),
            )

            fixture.dao.source =
                fixture.dao.source!!.copy(
                    nextRetryAt = 0L,
                    updatedAt = System.currentTimeMillis(),
                )
            fixture.downloader.runQueue()

            assertEquals("COMPLETED", fixture.dao.source?.taskStatus)
            assertFalse(fixture.witness().exists())
            assertEquals("重复恢复不得重新下载同一代外置流", 1, fixture.transfer.externalDownloads)
            assertEquals(externalPath, fixture.dao.externalStream()?.path)
            fixture.assertSixEffects()
        } finally {
            fixture.close()
        }
    }

    private fun Fixture.assertSixEffects() {
        assertTrue("snapshot", dao.snapshotWrites > 0)
        assertTrue("media streams", dao.mediaStreamWrites >= 2)
        assertTrue("external streams", transfer.externalDownloads > 0)
        assertTrue("segments", dao.segmentWrites > 0)
        assertTrue("trickplay", dao.trickplayWrites > 0)
        assertTrue("images", imageScheduler.requests.isNotEmpty())
    }

    private class Fixture {
        private val root = Files.createTempDirectory("downloader-recovery").toFile()
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        private val tree =
            object : Timber.Tree() {
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    logs += "$message ${t?.javaClass?.simpleName}:${t?.message}"
                }
            }
        val downloads = File(root, "downloads").apply { mkdirs() }
        val dao = RecordingDao()
        val repository = RecordingRepository()
        val transfer = RecordingTransfer()
        val workGateway = RecordingWorkGateway()
        val imageScheduler = RecordingImageScheduler()
        val downloader =
            DownloaderImpl.createForTest(
                context = TestContext(root),
                database = dao.proxy,
                jellyfinRepository = repository.proxy,
                readerRepository = unusedProxy(ReaderRepository::class.java),
                appPreferences = AppPreferences(FakePreferences()),
                imageCacheStore = allocate(),
                workManager =
                    allocateClass(Class.forName("androidx.work.impl.WorkManagerImpl"))
                        as WorkManager,
                httpClient = allocate(),
                notifications = FakeNotificationGateway(),
                registerNetworkCallback = {},
                externalStorageState = { "mounted" },
                availableBytes = { Long.MAX_VALUE },
                itemResolverOverride = { if (dao.itemAvailable) movie() else null },
                workGateway = workGateway,
                downloadTransfer = transfer,
                imageFinalizationScheduler = imageScheduler,
            )

        init {
            Timber.plant(tree)
        }

        fun movie(): FindroidMovie {
            val source =
                FindroidSource(
                    id = SOURCE_ID,
                    name = SOURCE_NAME,
                    type = FindroidSourceType.REMOTE,
                    path = MEDIA_URL,
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = repository.mediaStreams,
                    container = "mp4",
                )
            return FindroidMovie(
                id = ITEM_ID,
                name = ITEM_NAME,
                originalTitle = null,
                overview = "",
                sources = listOf(source),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = true,
                runtimeTicks = 0L,
                playbackPositionTicks = 0L,
                premiereDate = null,
                people = emptyList(),
                genres = emptyList(),
                communityRating = null,
                officialRating = null,
                status = "Released",
                productionYear = null,
                endDate = null,
                trailer = null,
                images = FindroidImages(),
                chapters = emptyList(),
                trickplayInfo =
                    mapOf(
                        SOURCE_ID to
                            com.zhangwenkang.cinefin.models.FindroidTrickplayInfo(
                                width = 80,
                                height = 45,
                                tileWidth = 1,
                                tileHeight = 1,
                                thumbnailCount = 1,
                                interval = 1_000,
                                bandwidth = 1,
                            )
                    ),
                kind = "MOVIE",
            )
        }

        suspend fun prepareInterruptedCompletion() {
            val target =
                DownloadCompletionCommitRules.downloadTargetFiles(
                    directory = downloads,
                    itemId = ITEM_ID.toString(),
                    sourceId = SOURCE_ID,
                    generation = GENERATION,
                )
            target.first.writeBytes(MEDIA_BYTES)
            runCatching {
                DownloadCompletionCommitRules.commit(
                    partial = target.first,
                    finalFile = target.second,
                    canStart = { true },
                    commitState = { error("模拟 rename 后进程中断") },
                    sourceId = SOURCE_ID,
                    generation = GENERATION,
                )
            }
            dao.source =
                FindroidSourceDto(
                    id = SOURCE_ID,
                    itemId = ITEM_ID,
                    name = ITEM_NAME,
                    type = FindroidSourceType.LOCAL,
                    path = target.second.path,
                    taskStatus = DownloadTaskStatus.COMPLETED.name,
                    updatedAt = 0L,
                    downloadedBytes = MEDIA_BYTES.size.toLong(),
                    totalBytes = MEDIA_BYTES.size.toLong(),
                    engineVersion = DownloaderImpl.ENGINE_VERSION,
                    taskGeneration = GENERATION,
                )
        }

        fun prepareLegacyTargetConflict(): LegacyTargetEvidence {
            val legacyFinal =
                File(downloads, "${ITEM_ID}.${SOURCE_ID}").apply { writeText("old payload") }
            val legacyPartial =
                File(downloads, "${ITEM_ID}.${SOURCE_ID}.download").apply {
                    writeBytes(MEDIA_BYTES)
                }
            val legacyWitness =
                DownloadCompletionCommitRules.witnessFile(legacyPartial, legacyFinal).apply {
                    parentFile?.mkdirs()
                    writeText(
                        listOf(
                                SOURCE_ID,
                                (GENERATION - 1).toString(),
                                legacyPartial.canonicalPath,
                                legacyFinal.canonicalPath,
                            )
                            .joinToString("\n")
                    )
                }
            return LegacyTargetEvidence(legacyFinal, legacyWitness)
        }

        fun seedStaleExternal(): File {
            val staleId =
                DownloadPostCommitRules.externalMediaStreamId(
                    sourceId = SOURCE_ID,
                    generation = GENERATION - 1,
                    streamIndex = 1,
                    ordinal = 0,
                )
            val stale =
                File(downloads, "${ITEM_ID}.${SOURCE_ID}.$staleId").apply {
                    writeText("stale subtitle")
                }
            dao.putMediaStream(
                FindroidMediaStreamDto(
                    id = staleId,
                    sourceId = SOURCE_ID,
                    title = "English",
                    displayTitle = null,
                    language = "eng",
                    type = MediaStreamType.SUBTITLE,
                    codec = "srt",
                    isExternal = true,
                    path = stale.path,
                    channelLayout = null,
                    videoRangeType = null,
                    height = null,
                    width = null,
                    videoDoViTitle = null,
                )
            )
            return stale
        }

        fun witness(): File =
            DownloadCompletionCommitRules.witnessFile(
                partial =
                    DownloadCompletionCommitRules.downloadTargetFiles(
                            directory = downloads,
                            itemId = ITEM_ID.toString(),
                            sourceId = SOURCE_ID,
                            generation = GENERATION,
                        )
                        .first,
                finalFile = File(checkNotNull(dao.source).path),
            )

        fun close() {
            Timber.uproot(tree)
            root.deleteRecursively()
        }
    }

    private data class LegacyTargetEvidence(val legacyFinal: File, val legacyWitness: File)

    private class RecordingDao {
        var source: FindroidSourceDto? = null
        var itemAvailable = true
        var snapshotWrites = 0
        var mediaStreamWrites = 0
        var segmentWrites = 0
        var trickplayWrites = 0
        private val mediaStreams = linkedMapOf<UUID, FindroidMediaStreamDto>()
        private val movie =
            FindroidMovieDto(
                id = ITEM_ID,
                serverId = SERVER_ID,
                name = ITEM_NAME,
                originalTitle = null,
                overview = "",
                runtimeTicks = 0L,
                premiereDate = null,
                communityRating = null,
                officialRating = null,
                status = "Released",
                productionYear = null,
                endDate = null,
                chapters = emptyList(),
            )
        private val trickplay =
            FindroidTrickplayInfoDto(
                sourceId = SOURCE_ID,
                width = 80,
                height = 45,
                tileWidth = 1,
                tileHeight = 1,
                thumbnailCount = 1,
                interval = 1_000,
                bandwidth = 1,
            )
        private val userData =
            FindroidUserDataDto(
                userId = USER_ID,
                itemId = ITEM_ID,
                played = false,
                favorite = false,
                playbackPositionTicks = 0L,
            )

        val proxy: ServerDatabaseDao =
            Proxy.newProxyInstance(
                ServerDatabaseDao::class.java.classLoader,
                arrayOf(ServerDatabaseDao::class.java),
                InvocationHandler { _, method, rawArgs ->
                    val args = rawArgs ?: emptyArray()
                    when (method.name) {
                        "getPendingSources" ->
                            listOfNotNull(source).filter {
                                it.taskStatus != "COMPLETED" || it.path.endsWith(".download")
                            }
                        "getAllSources" -> listOfNotNull(source)
                        "getSource" -> source?.takeIf { it.id == args[0] }
                        "insertSource" -> {
                            source = args[0] as FindroidSourceDto
                            Unit
                        }
                        "setSourcePath" -> {
                            source =
                                source?.takeIf { it.id == args[0] }?.copy(path = args[1] as String)
                            Unit
                        }
                        "claimSourceRunning" ->
                            updateSource(args) {
                                it.copy(taskStatus = "RUNNING", failureReason = null)
                            }
                        "beginSourceRetry" -> retrySource(args)
                        "setSourceProgressIfCurrent" ->
                            updateSource(args) {
                                it.copy(
                                    downloadedBytes = args[2] as Long,
                                    totalBytes = args[3] as Long,
                                )
                            }
                        "setSourceTaskStatusIfCurrent" ->
                            updateSource(args) {
                                it.copy(
                                    taskStatus = args[2] as String?,
                                    failureReason = args[3] as String?,
                                )
                            }
                        "setSourceRetryIfCurrent" ->
                            updateSource(args) {
                                it.copy(
                                    retryCount = args[2] as Int,
                                    nextRetryAt = args[3] as Long,
                                )
                            }
                        "completeSourceIfCurrent" ->
                            updateSource(args, requireRunning = true) {
                                it.copy(
                                    path = args[2] as String,
                                    taskStatus = "COMPLETED",
                                    failureReason = null,
                                    downloadedBytes = args[3] as Long,
                                    totalBytes = args[4] as Long,
                                    resumeValidator = args[5] as String?,
                                    retryCount = 0,
                                    nextRetryAt = 0L,
                                )
                            }
                        "getMovie" -> movie.takeIf { itemAvailable && args[0] == ITEM_ID }
                        "getEpisode" -> null
                        "getUserDataOrCreateNew" -> userData
                        "getSources" ->
                            listOfNotNull(source).filter { it.itemId == args[0] as UUID }
                        "getTrickplayInfo" -> trickplay.takeIf { args[0] == SOURCE_ID }
                        "insertMovie" -> {
                            snapshotWrites += 1
                            Unit
                        }
                        "insertUserData" -> Unit
                        "insertMediaStream" -> {
                            val stream = args[0] as FindroidMediaStreamDto
                            mediaStreams[stream.id] = stream
                            mediaStreamWrites += 1
                            Unit
                        }
                        "getMediaStreamsBySourceId" ->
                            mediaStreams.values.filter { it.sourceId == args[0] }
                        "setMediaStreamPath" -> {
                            val id = args[0] as UUID
                            mediaStreams[id]?.let {
                                mediaStreams[id] = it.copy(path = args[1] as String)
                            }
                            Unit
                        }
                        "deleteMediaStream" -> {
                            mediaStreams.remove(args[0] as UUID)
                            Unit
                        }
                        "insertSegment" -> {
                            segmentWrites += 1
                            Unit
                        }
                        "insertTrickplayInfo" -> {
                            trickplayWrites += 1
                            Unit
                        }
                        "getIncompleteDownloadDeleteBatches" -> emptyList<Any>()
                        "getReceivedDownloadIntents" -> emptyList<Any>()
                        else -> error("unexpected DAO call: ${method.name}")
                    }
                },
            ) as ServerDatabaseDao

        fun externalStream(): FindroidMediaStreamDto? =
            mediaStreams.values.firstOrNull { it.isExternal }

        fun putMediaStream(stream: FindroidMediaStreamDto) {
            mediaStreams[stream.id] = stream
        }

        private fun retrySource(args: Array<Any?>): FindroidSourceDto? {
            val current = source ?: return null
            if (
                current.id != args[0] ||
                    current.taskGeneration != args[1] ||
                    current.taskStatus != "FAILED"
            ) {
                return null
            }
            return current
                .copy(
                    taskStatus = "PENDING",
                    failureReason = null,
                    retryCount = 0,
                    nextRetryAt = 0L,
                    taskGeneration = current.taskGeneration + 1,
                )
                .also { source = it }
        }

        private fun updateSource(
            args: Array<Any?>,
            requireRunning: Boolean = false,
            transform: (FindroidSourceDto) -> FindroidSourceDto,
        ): Int {
            val current = source ?: return 0
            if (
                current.id != args[0] ||
                    current.taskGeneration != args[1] ||
                    (requireRunning && current.taskStatus != "RUNNING")
            ) {
                return 0
            }
            source = transform(current)
            return 1
        }
    }

    private class RecordingRepository {
        val mediaStreams =
            listOf(
                FindroidMediaStream(
                    title = "Main",
                    displayTitle = null,
                    language = "und",
                    type = MediaStreamType.VIDEO,
                    codec = "h264",
                    isExternal = false,
                    path = null,
                    channelLayout = null,
                    videoRangeType = null,
                    height = 720,
                    width = 1_280,
                    videoDoViTitle = null,
                    index = 0,
                ),
                FindroidMediaStream(
                    title = "English",
                    displayTitle = null,
                    language = "eng",
                    type = MediaStreamType.SUBTITLE,
                    codec = "srt",
                    isExternal = true,
                    path = SUBTITLE_URL,
                    channelLayout = null,
                    videoRangeType = null,
                    height = null,
                    width = null,
                    videoDoViTitle = null,
                    index = 1,
                ),
            )
        var segmentFailuresRemaining = 0

        val proxy: JellyfinRepository =
            Proxy.newProxyInstance(
                JellyfinRepository::class.java.classLoader,
                arrayOf(JellyfinRepository::class.java),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "getMediaSources" ->
                            listOf(
                                FindroidSource(
                                    id = SOURCE_ID,
                                    name = SOURCE_NAME,
                                    type = FindroidSourceType.REMOTE,
                                    path = MEDIA_URL,
                                    size = MEDIA_BYTES.size.toLong(),
                                    mediaStreams = mediaStreams,
                                    container = "mp4",
                                )
                            )
                        "getSegments" -> {
                            if (segmentFailuresRemaining > 0) {
                                segmentFailuresRemaining -= 1
                                error("segments unavailable")
                            }
                            listOf(FindroidSegment(FindroidSegmentType.INTRO, 0L, 10L))
                        }
                        "getTrickplayData" -> byteArrayOf(1)
                        "getBaseUrl" -> "https://example.test"
                        "getAccessToken" -> "token"
                        "getUserId" -> USER_ID
                        else -> error("unexpected repository call: ${method.name}")
                    }
                },
            ) as JellyfinRepository
    }

    private class RecordingTransfer : DownloadTransfer {
        var externalDownloads = 0

        override suspend fun download(request: DownloadTransferRequest): DownloadAttemptResult {
            val bytes =
                if (request.url == SUBTITLE_URL) {
                    externalDownloads += 1
                    SUBTITLE_BYTES
                } else {
                    MEDIA_BYTES
                }
            request.target.parentFile?.mkdirs()
            request.target.writeBytes(bytes)
            return DownloadAttemptResult(
                downloadedBytes = bytes.size.toLong(),
                totalBytes = bytes.size.toLong(),
                validator = "etag",
            )
        }
    }

    private class RecordingWorkGateway : DownloadWorkGateway {
        val enqueuedNames = mutableListOf<String>()

        override suspend fun uniqueWorkInfos(name: String): List<WorkInfo> = emptyList()

        override fun enqueueUnique(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            enqueuedNames += name
        }

        override fun cancelUnique(name: String) = Unit

        override fun enqueue(request: OneTimeWorkRequest) {
            enqueuedNames += request.id.toString()
        }
    }

    private class RecordingImageScheduler : DownloadImageFinalizationScheduler {
        val requests = mutableListOf<Pair<String, Long>>()

        override suspend fun schedule(item: FindroidItem, sourceId: String, generation: Long) {
            requests += sourceId to generation
        }
    }

    private class FakeNotificationGateway : DownloadNotificationGateway {
        override fun buildForeground(task: DownloadTask?, activeCount: Int): Notification =
            allocate()

        override fun notifyForeground(task: DownloadTask?, activeCount: Int) = Unit

        override fun notifyCompleted(task: DownloadTask) = Unit

        override fun notifyFailed(task: DownloadTask) = Unit
    }

    private class FakePreferences : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any?>()

        override fun getString(key: String?, defValue: String?): String? = defValue

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(key: String?, defValue: Int): Int = defValue

        override fun getLong(key: String?, defValue: Long): Long = defValue

        override fun getFloat(key: String?, defValue: Float): Float = defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue

        override fun contains(key: String?): Boolean = false

        override fun edit(): SharedPreferences.Editor = FakeEditor()

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) = Unit
    }

    private class FakeEditor : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?): SharedPreferences.Editor = this

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor = this

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this

        override fun remove(key: String?): SharedPreferences.Editor = this

        override fun clear(): SharedPreferences.Editor = this

        override fun commit(): Boolean = true

        override fun apply() = Unit
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(root)
    }

    private companion object {
        val ITEM_ID: UUID = UUID(0L, 11L)
        val USER_ID: UUID = UUID(0L, 12L)
        const val SOURCE_ID = "source-recovery"
        const val SOURCE_NAME = "Recovery Source"
        const val ITEM_NAME = "Recovery Movie"
        const val SERVER_ID = "server-a"
        const val GENERATION = 5L
        const val MEDIA_URL = "https://media.test/movie"
        const val SUBTITLE_URL = "https://media.test/subtitle"
        val MEDIA_BYTES = "media-payload".toByteArray()
        val SUBTITLE_BYTES = "subtitle-payload".toByteArray()

        fun <T : Any> unusedProxy(type: Class<T>): T =
            type.cast(
                Proxy.newProxyInstance(
                    type.classLoader,
                    arrayOf(type),
                    InvocationHandler { _, method, _ ->
                        error("unexpected ${type.simpleName}.${method.name}")
                    },
                )
            )!!

        val unsafe: Unsafe =
            Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
                as Unsafe

        inline fun <reified T : Any> allocate(): T = unsafe.allocateInstance(T::class.java) as T

        fun allocateClass(type: Class<*>): Any = unsafe.allocateInstance(type)
    }
}
