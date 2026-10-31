package com.zhangwenkang.cinefin.utils

import android.app.Notification
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.zhangwenkang.cinefin.database.ServerDatabaseDao
import com.zhangwenkang.cinefin.models.DownloadDeleteBatchDto
import com.zhangwenkang.cinefin.models.DownloadDeleteReceiptDto
import com.zhangwenkang.cinefin.models.DownloadIntentDto
import com.zhangwenkang.cinefin.models.DownloadIntentStatus
import com.zhangwenkang.cinefin.models.FindroidEpisode
import com.zhangwenkang.cinefin.models.FindroidEpisodeDto
import com.zhangwenkang.cinefin.models.FindroidImages
import com.zhangwenkang.cinefin.models.FindroidItem
import com.zhangwenkang.cinefin.models.FindroidMediaStream
import com.zhangwenkang.cinefin.models.FindroidMediaStreamDto
import com.zhangwenkang.cinefin.models.FindroidMovie
import com.zhangwenkang.cinefin.models.FindroidMovieDto
import com.zhangwenkang.cinefin.models.FindroidSeason
import com.zhangwenkang.cinefin.models.FindroidSeasonDto
import com.zhangwenkang.cinefin.models.FindroidSegment
import com.zhangwenkang.cinefin.models.FindroidSegmentDto
import com.zhangwenkang.cinefin.models.FindroidSegmentType
import com.zhangwenkang.cinefin.models.FindroidShow
import com.zhangwenkang.cinefin.models.FindroidShowDto
import com.zhangwenkang.cinefin.models.FindroidSource
import com.zhangwenkang.cinefin.models.FindroidSourceDto
import com.zhangwenkang.cinefin.models.FindroidSourceType
import com.zhangwenkang.cinefin.models.FindroidTrickplayInfoDto
import com.zhangwenkang.cinefin.models.FindroidUserDataDto
import com.zhangwenkang.cinefin.models.toFindroidMovie
import com.zhangwenkang.cinefin.models.toFindroidSource
import com.zhangwenkang.cinefin.repository.JellyfinOrigin
import com.zhangwenkang.cinefin.repository.JellyfinOriginCredentials
import com.zhangwenkang.cinefin.repository.JellyfinRepository
import com.zhangwenkang.cinefin.repository.OriginScopedJellyfinRepository
import com.zhangwenkang.cinefin.repository.ReaderRepository
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.work.ImagesDownloaderWorker
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import javax.tools.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jellyfin.sdk.model.api.MediaStreamType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import timber.log.Timber

class DownloaderImplRecoveryTest {

    private object RealDtoMapperBridge {
        fun map(
            dto: FindroidMovieDto,
            database: ServerDatabaseDao,
            userId: UUID,
        ): FindroidMovie = runBlocking { dto.toFindroidMovie(database, userId) }
    }

    private val realDtoMapperLoader: ClassLoader by lazy {
        val root = Files.createTempDirectory("real-dto-uri").toFile()
        val source =
            File(root, "android/net/Uri.java").apply {
                parentFile.mkdirs()
                writeText(
                    """
                    package android.net;

                    public final class Uri {
                        private final String value;

                        private Uri(String value) {
                            this.value = value;
                        }

                        public static final class Builder {
                            private final StringBuilder value = new StringBuilder();

                            public Builder appendEncodedPath(String path) {
                                value.append(path);
                                return this;
                            }

                            public Uri build() {
                                return new Uri(value.toString());
                            }
                        }
                    }
                    """
                        .trimIndent()
                )
            }
        val classes = File(root, "classes").apply { mkdirs() }
        val diagnostics = ByteArrayOutputStream()
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val result =
            compiler.run(
                null,
                null,
                diagnostics,
                "-d",
                classes.path,
                source.path,
            )
        check(result == 0) { "真实 DTO mapper 的 Uri 测试替身编译失败：${diagnostics.toString("UTF-8")}" }
        object :
            URLClassLoader(
                arrayOf(classes.toURI().toURL()),
                DownloaderImplRecoveryTest::class.java.classLoader,
            ) {
            private val childFirstExact =
                setOf(
                    "android.net.Uri",
                    "android.net.Uri\$Builder",
                    "com.zhangwenkang.cinefin.models.FindroidImages",
                    "com.zhangwenkang.cinefin.models.FindroidImagesKt",
                    "com.zhangwenkang.cinefin.models.FindroidItem",
                    "com.zhangwenkang.cinefin.models.FindroidMovie",
                    "com.zhangwenkang.cinefin.models.FindroidMovieKt",
                    "com.zhangwenkang.cinefin.models.FindroidSources",
                )
            private val childFirstPrefix =
                listOf(
                    "com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest\$RealDtoMapperBridge",
                    "com.zhangwenkang.cinefin.models.FindroidImages\$",
                    "com.zhangwenkang.cinefin.models.FindroidMovie\$",
                    "com.zhangwenkang.cinefin.models.FindroidMovieKt\$toFindroidMovie",
                )

            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name !in childFirstExact && childFirstPrefix.none(name::startsWith)) {
                    return super.loadClass(name, resolve)
                }
                return synchronized(this) {
                    val loaded = findLoadedClass(name) ?: findClass(name)
                    if (resolve) resolveClass(loaded)
                    loaded
                }
            }

            override fun findClass(name: String): Class<*> {
                if (name == "android.net.Uri" || name == "android.net.Uri\$Builder") {
                    return super.findClass(name)
                }
                val resource = name.replace('.', '/') + ".class"
                val bytes =
                    checkNotNull(parent.getResourceAsStream(resource)) { "测试类不在父 classpath：$name" }
                        .use { it.readBytes() }
                return defineClass(name, bytes, 0, bytes.size)
            }
        }
    }

    private fun mapWithRealDtoProduction(
        dto: FindroidMovieDto,
        database: ServerDatabaseDao,
        userId: UUID,
    ): FindroidMovie {
        val bridge =
            realDtoMapperLoader.loadClass(
                "com.zhangwenkang.cinefin.utils.DownloaderImplRecoveryTest\$RealDtoMapperBridge"
            )
        val instanceField = bridge.getField("INSTANCE").apply { isAccessible = true }
        val instance = instanceField.get(null)
        val method =
            bridge.methods.single {
                it.name == "map" &&
                    it.parameterTypes.contentEquals(
                        arrayOf(
                            FindroidMovieDto::class.java,
                            ServerDatabaseDao::class.java,
                            UUID::class.java,
                        )
                    )
            }
        method.isAccessible = true
        val mapped = method.invoke(instance, dto, database, userId)
        return mapped as? FindroidMovie ?: adaptIsolatedFindroidMovie(mapped)
    }

    private fun adaptIsolatedFindroidMovie(value: Any): FindroidMovie {
        fun property(name: String): Any? = value.javaClass.getMethod(name).invoke(value)

        val constructor =
            FindroidMovie::class.java.declaredConstructors.single { it.parameterCount == 26 }
        return constructor.newInstance(
            property("getId"),
            property("getName"),
            property("getOriginalTitle"),
            property("getOverview"),
            property("getSources"),
            property("getPlayed"),
            property("getFavorite"),
            property("getCanPlay"),
            property("getCanDownload"),
            property("getRuntimeTicks"),
            property("getPlaybackPositionTicks"),
            property("getPlayedPercentage"),
            property("getPremiereDate"),
            property("getPeople"),
            property("getGenres"),
            property("getCommunityRating"),
            property("getOfficialRating"),
            property("getStatus"),
            property("getProductionYear"),
            property("getEndDate"),
            property("getTrailer"),
            property("getUnplayedItemCount"),
            FindroidImages(),
            property("getChapters"),
            property("getTrickplayInfo"),
            property("getKind"),
        ) as FindroidMovie
    }

    private data class CompletedMusicDeleteSeed(
        val item: FindroidMovie,
        val source: FindroidSource,
        val finalFile: File,
        val partialFile: File,
        val witnessFile: File,
        val mediaStreamFile: File,
    )

    private data class DownloadArtifactSeed(
        val finalFile: File,
        val partialFile: File,
        val witnessFile: File,
    )

    private fun writeDownloadArtifacts(
        fixture: Fixture,
        itemId: UUID = ITEM_ID,
        sourceId: String = SOURCE_ID,
        generation: Long = 1L,
        witnessGeneration: Long = generation,
        partialOnly: Boolean = false,
    ): DownloadArtifactSeed {
        val (partialFile, finalFile) =
            DownloadCompletionCommitRules.downloadTargetFiles(
                directory = fixture.downloads,
                itemId = itemId.toString(),
                sourceId = sourceId,
                generation = generation,
            )
        if (partialOnly) {
            partialFile.writeText("partial")
        } else {
            finalFile.writeText("completed")
        }
        val witnessFile =
            DownloadCompletionCommitRules.witnessFile(partialFile, finalFile).apply {
                writeText(
                    listOf(
                            sourceId,
                            witnessGeneration.toString(),
                            partialFile.canonicalPath,
                            finalFile.canonicalPath,
                        )
                        .joinToString("\n")
                )
            }
        return DownloadArtifactSeed(
            finalFile = finalFile,
            partialFile = partialFile,
            witnessFile = witnessFile,
        )
    }

    private fun seedDownloadArtifactSource(
        fixture: Fixture,
        itemId: UUID = ITEM_ID,
        sourceId: String = SOURCE_ID,
        generation: Long = 1L,
        partialOnly: Boolean = false,
    ): DownloadArtifactSeed {
        val artifacts =
            writeDownloadArtifacts(
                fixture = fixture,
                itemId = itemId,
                sourceId = sourceId,
                generation = generation,
                partialOnly = partialOnly,
            )
        val localSource =
            fixture
                .movie()
                .sources
                .single()
                .copy(
                    id = sourceId,
                    type = FindroidSourceType.LOCAL,
                    path =
                        if (partialOnly) artifacts.partialFile.path else artifacts.finalFile.path,
                )
        val item = fixture.movie().copy(id = itemId, sources = listOf(localSource))
        fixture.dao.seedMovie(itemId)
        fixture.seedCompletedSource(
            item = item,
            source = localSource,
            generation = generation,
            taskStatus =
                if (partialOnly) DownloadTaskStatus.PENDING else DownloadTaskStatus.COMPLETED,
        )
        return artifacts
    }

    private suspend fun seedCompletedMusicDelete(fixture: Fixture): CompletedMusicDeleteSeed {
        val itemId = UUID.randomUUID()
        val sourceId = "completed-music-source"
        val finalFile = File(fixture.downloads, "$itemId.$sourceId.g1").apply { writeText("music") }
        val partialFile = File(finalFile.path + ".download").apply { writeText("partial") }
        val witnessFile =
            DownloadCompletionCommitRules.witnessFile(partialFile, finalFile).apply {
                writeText(
                    listOf(
                            sourceId,
                            "1",
                            partialFile.canonicalPath,
                            finalFile.canonicalPath,
                        )
                        .joinToString("\n")
                )
            }
        val mediaStreamFile =
            File(fixture.downloads, "$itemId.$sourceId.external.srt").apply {
                writeText("subtitle")
            }
        fixture.dao.putMediaStream(
            FindroidMediaStreamDto(
                id = UUID.randomUUID(),
                sourceId = sourceId,
                title = "External",
                displayTitle = null,
                language = "und",
                type = MediaStreamType.SUBTITLE,
                codec = "srt",
                isExternal = true,
                path = mediaStreamFile.path,
                channelLayout = null,
                videoRangeType = null,
                height = null,
                width = null,
                videoDoViTitle = null,
            )
        )
        val source =
            FindroidSource(
                id = sourceId,
                name = "Completed music",
                type = FindroidSourceType.LOCAL,
                path = finalFile.path,
                size = finalFile.length(),
                mediaStreams = emptyList(),
                container = "m4a",
            )
        fixture.dao.seedMovie(itemId)
        fixture.seedCompletedSource(
            item = fixture.movie().copy(id = itemId, sources = listOf(source), kind = "AUDIO"),
            source = source,
            persistOrigin = false,
        )
        assertTrue(
            fixture.putMusicSidecar(
                itemId = itemId,
                sourceId = sourceId,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
                displayName = "Completed music",
                albumName = "Album",
            )
        )
        val rebuilt =
            mapWithRealDtoProduction(
                dto = requireNotNull(fixture.dao.proxy.getMovie(itemId)),
                database = fixture.dao.proxy,
                userId = USER_ID,
            )
        return CompletedMusicDeleteSeed(
            item = rebuilt,
            source =
                requireNotNull(fixture.dao.sourceById(sourceId))
                    .toFindroidSource(fixture.dao.proxy),
            finalFile = finalFile,
            partialFile = partialFile,
            witnessFile = witnessFile,
            mediaStreamFile = mediaStreamFile,
        )
    }

    @Test
    fun `设置并发从二增到四会经公开偏好写入立即补槽`() = runBlocking {
        val fixture = Fixture()
        val sources = fixture.seedConcurrentMovies(4)
        fixture.appPreferences.setValue(fixture.appPreferences.downloadConcurrency, 2)
        fixture.transfer.holdDownloads()
        val run = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
        try {
            assertTrue(
                "初始并发 2 未启动两个下载：${fixture.transfer.startedPaths()}",
                fixture.transfer.awaitStarted(2),
            )

            fixture.appPreferences.setValue(fixture.appPreferences.downloadConcurrency, 4)

            assertTrue(
                "公开设置写入 2→4 后未立即补齐到四个运行任务：${fixture.transfer.startedPaths()}",
                fixture.transfer.awaitStarted(4),
            )
            assertEquals(
                sources.take(4).map { it.source.id }.toSet(),
                fixture.transfer.startedSourceIds().toSet(),
            )
            assertTrue("提高并发不应自动暂停任务", fixture.dao.pausedSourceIds().isEmpty())
        } finally {
            fixture.transfer.release()
            withTimeoutOrNull(5_000L) { run.join() }
            run.cancel()
            fixture.close()
        }
    }

    @Test
    fun `设置并发从八降到二会经公开偏好写入暂停最新六个并保留残片`() = runBlocking {
        val fixture = Fixture()
        val sources = fixture.seedConcurrentMovies(8)
        fixture.appPreferences.setValue(fixture.appPreferences.downloadConcurrency, 8)
        fixture.transfer.holdDownloads()
        val run = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
        try {
            assertTrue(
                "初始并发 8 未启动八个下载：${fixture.transfer.startedPaths()}",
                fixture.transfer.awaitStarted(8),
            )

            fixture.appPreferences.setValue(fixture.appPreferences.downloadConcurrency, 2)
            val paused =
                withTimeoutOrNull(2_000L) {
                    while (fixture.dao.pausedSourceIds().size < 6) delay(10L)
                    fixture.dao.pausedSourceIds()
                }
            assertNotNull("公开设置写入 8→2 后两秒内未暂停六个多余任务", paused)

            val retained = sources.take(2).map { it.source.id }.toSet()
            val expectedPaused = sources.drop(2).map { it.source.id }.toSet()
            assertEquals(expectedPaused, paused!!.toSet())
            assertEquals(retained, fixture.dao.runningSourceIds().toSet())
            expectedPaused.forEach { sourceId ->
                val source = fixture.dao.sourceById(sourceId)!!
                val partial = File(source.path)
                assertTrue("暂停任务残片丢失：$sourceId", partial.isFile)
                assertEquals("partial", partial.readText())
            }
        } finally {
            fixture.transfer.release()
            withTimeoutOrNull(5_000L) { run.join() }
            run.cancel()
            fixture.close()
        }
    }

    @Test
    fun `删除同条目单个来源经生产入口保留另一来源共享恢复数据`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val second = fixture.seedAdditionalSource(index = 1)
            fixture.persistVideoOrigin(second.source.id)
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertEquals(listOf(second.source.id), fixture.dao.sourceIds())
            assertTrue(
                fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == second.source.id
                }
            )
            assertFalse(
                fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                }
            )
            assertEquals(
                "删除一个 source 不得清理共享 movie 快照",
                listOf(ITEM_ID),
                fixture.dao.snapshotIds(),
            )
            assertFalse(
                "删除一个 source 不得清理共享 userData",
                ITEM_ID in fixture.dao.deletedUserDataIds(),
            )
            assertTrue("删除一个 source 不得清理共享图片", imageMarker.isFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除同条目最后一个来源经生产入口清理共享数据`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val second = fixture.seedAdditionalSource(index = 1)
            fixture.persistVideoOrigin(second.source.id)
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))
            assertTrue(fixture.downloader.deleteTaskById(second.source.id))

            assertTrue(fixture.dao.sourceIds().isEmpty())
            assertTrue(fixture.dao.snapshotIds().isEmpty())
            assertTrue(ITEM_ID in fixture.dao.deletedUserDataIds())
            assertFalse(imageMarker.exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除中并发同来源入队不会让旧清理删除新代`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            fixture.dao.armSourceDeletionGate()
            val insertCountBefore = fixture.dao.sourceInsertCount(SOURCE_ID)

            val deletion = async(Dispatchers.IO) { fixture.downloader.deleteTaskById(SOURCE_ID) }
            withTimeout(5_000) { fixture.dao.sourceDeleteStarted.await() }
            val deletingGeneration = fixture.dao.sourceDeletionGeneration

            val concurrentEnqueue =
                async(Dispatchers.IO) { fixture.downloader.downloadItem(movie, SOURCE_ID, 0) }
            val insertedWhileDeleting =
                withTimeoutOrNull(500) {
                    fixture.dao.concurrentSourceInserted.await()
                    true
                }
            assertFalse("新 source 不应在删除操作锁释放前插入", insertedWhileDeleting == true)
            assertEquals(
                "删除持锁期间 insert 调用次数必须保持为零",
                insertCountBefore,
                fixture.dao.sourceInsertCount(SOURCE_ID),
            )
            fixture.dao.releaseSourceDelete.complete(Unit)

            assertTrue(deletion.await())
            val (firstHandle, firstError) = concurrentEnqueue.await()
            if (firstHandle >= 0L) {
                assertTrue("等待删除后入队应成功且无错误", firstError == null)
                val inserted = requireNotNull(fixture.dao.lastInsertedSource(SOURCE_ID))
                assertEquals(DownloadTaskStatus.PENDING.name, inserted.taskStatus)
                assertTrue(
                    "新 source 代次必须晚于删除代次：inserted=${inserted.taskGeneration} deleting=$deletingGeneration",
                    inserted.taskGeneration > deletingGeneration,
                )
                assertNotNull("新 generation source 被旧清理删除", fixture.dao.sourceById(SOURCE_ID))
                assertTrue(
                    "新 generation sidecar 被旧清理删除",
                    fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                        it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                    },
                )
                val insertedAt =
                    fixture.dao.sourceEvents.indexOf("INSERT:$SOURCE_ID:${inserted.taskGeneration}")
                val lastMediaDeleteAt =
                    fixture.dao.sourceEvents.indexOfLast { it == "DELETE_MEDIA:$SOURCE_ID" }
                assertTrue(
                    "旧 generation 的 media 清理不得发生在新 source 插入之后",
                    insertedAt > lastMediaDeleteAt,
                )
            } else {
                assertTrue("删除中拒绝入队必须向生产 UI 返回可重试错误", firstError != null)
                assertTrue(fixture.dao.sourceById(SOURCE_ID) == null)

                val (retryHandle, retryError) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
                assertTrue("删除完成后的重试应成功", retryHandle >= 0L && retryError == null)
                val retried = requireNotNull(fixture.dao.lastInsertedSource(SOURCE_ID))
                assertEquals(DownloadTaskStatus.PENDING.name, retried.taskStatus)
                assertTrue(retried.taskGeneration > deletingGeneration)
                assertNotNull(fixture.dao.sourceById(SOURCE_ID))
                assertTrue(
                    fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                        it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                    }
                )
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `幂等入队读取与成功返回不得被删除提前穿插`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            fixture.dao.armSourceReadGate()
            val completionOrder = CopyOnWriteArrayList<String>()

            val enqueue =
                async(Dispatchers.IO) {
                    fixture.downloader.downloadItem(movie, SOURCE_ID, 0).also {
                        completionOrder += "enqueue"
                    }
                }
            withTimeout(5_000) { fixture.dao.sourceReadStarted.await() }
            val deletion =
                async(Dispatchers.IO) {
                    fixture.downloader.deleteTaskById(SOURCE_ID).also {
                        completionOrder += "delete"
                    }
                }
            try {
                val deletedBeforeEnqueueReturned =
                    withTimeoutOrNull(500) {
                        deletion.join()
                        true
                    }
                assertFalse(
                    "删除不得在幂等入队读取 / 成功返回前完成：$completionOrder",
                    deletedBeforeEnqueueReturned == true,
                )
            } finally {
                fixture.dao.releaseSourceRead.complete(Unit)
            }

            val (handle, error) = enqueue.await()
            assertTrue("幂等入队应返回存活任务", handle >= 0L)
            assertTrue("幂等入队不应报错", error == null)
            assertTrue(deletion.await())
            assertEquals(
                "删除必须在 enqueue 完成线性化之后才返回",
                listOf("enqueue", "delete"),
                completionOrder,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy公开删除旧source身份不得删除同名新代`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val first = requireNotNull(fixture.dao.source)
            val staleSource = first.toFindroidSource(emptyList())
            fixture.dao.putSource(
                first.copy(
                    taskStatus = DownloadTaskStatus.FAILED.name,
                    taskGeneration = first.taskGeneration + 1,
                )
            )

            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val current = requireNotNull(fixture.dao.source)
            assertTrue(current.taskGeneration > first.taskGeneration)
            assertTrue(current.path != staleSource.path)

            val failure = runCatching {
                fixture.downloader.deleteItem(
                    item = movie,
                    source = staleSource,
                )
            }
                .exceptionOrNull()

            assertNotNull("旧 source 身份必须 fail-closed", failure)
            assertEquals(
                "旧 UI 调用不得删除同名新 generation",
                current,
                fixture.dao.source,
            )
            assertTrue(
                "新 generation sidecar 不得被旧 UI 调用删除",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy公开删除blank sidecar必须失败关闭`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val source = requireNotNull(fixture.dao.source).toFindroidSource(emptyList())
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.allRecords().requireComplete().forEach {
                sidecar.remove(it.canonicalIdentity())
            }
            sidecar.put(
                DownloadMediaRecord(
                    itemId = ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    sourceId = null,
                    displayName = ITEM_NAME,
                )
            )

            val failure = runCatching {
                fixture.downloader.deleteItem(movie, source)
            }
                .exceptionOrNull()

            assertNotNull("blank sidecar 无法证明来源归属时必须失败关闭", failure)
            assertNotNull("失败关闭不得删除 source", fixture.dao.source)
            assertEquals(
                "失败关闭不得删除 blank sidecar",
                1,
                sidecar.allRecords().requireComplete().count {
                    it.itemId == ITEM_ID.toString() && it.sourceId.isNullOrBlank()
                },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy公开删除no generation必须失败关闭`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val current = requireNotNull(fixture.dao.source)
            val source = current.toFindroidSource(emptyList())
            fixture.dao.putSource(current.copy(taskGeneration = 0L))

            val failure = runCatching {
                fixture.downloader.deleteItem(movie, source)
            }
                .exceptionOrNull()

            assertNotNull("缺 generation 的身份必须失败关闭", failure)
            assertEquals(
                "缺 generation 不得删除 source",
                0L,
                requireNotNull(fixture.dao.source).taskGeneration,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `DELETING清理抛错后新实例启动扫描可幂等续删`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")

            val cleanupFailure = runCatching {
                fixture.downloader.deleteTaskById(SOURCE_ID)
            }
                .exceptionOrNull()
            assertNotNull("清理阻塞必须向调用方暴露失败", cleanupFailure)
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)

            assertTrue(File(deleting.path, "blocked").delete())
            assertTrue(File(deleting.path).delete())
            val restarted = fixture.newDownloader()

            assertTrue("重启后应续完当前 DELETING 代次", restarted.recoverOnStartup() > 0)
            assertTrue("续删成功后 source 行必须移除", fixture.dao.source == null)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `DELETING旧receipt只由startup扫描续删并成功`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")
            assertNotNull(
                runCatching { fixture.downloader.deleteTaskById(SOURCE_ID) }.exceptionOrNull()
            )
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)
            fixture.dao.seedPendingDeleteBatch(
                batchId = "delete-batch-$SOURCE_ID",
                itemId = ITEM_ID,
                sourceId = SOURCE_ID,
                expectedGeneration = deleting.taskGeneration,
            )

            assertTrue(File(deleting.path, "blocked").delete())
            assertTrue(File(deleting.path).delete())
            val restarted = fixture.newDownloader()

            assertEquals(1, restarted.recoverOnStartup())
            assertTrue(fixture.dao.source == null)
            assertEquals(
                DownloadDeleteOutcome.SUCCEEDED.name,
                fixture.dao.deleteReceipts("delete-batch-$SOURCE_ID").single().outcome,
            )
            assertNotNull(fixture.dao.deleteBatch("delete-batch-$SOURCE_ID")?.completedAt)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `FAILED receipt不自动恢复且显式删除可复用强类型generation重试`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.downloader.downloadItem(fixture.movie(), SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")

            val request =
                DownloadDeleteRequest(
                    itemId = ITEM_ID,
                    sourceId = SOURCE_ID,
                    mediaKind = DownloadMediaKind.VIDEO,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                )
            val first = fixture.downloader.deleteDownloads(listOf(request))
            val failed = first.receipts.single()
            assertEquals(DownloadDeleteOutcome.FAILED, failed.outcome)
            assertEquals(DownloadTaskStatus.DELETING.name, fixture.dao.source?.taskStatus)
            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())

            assertEquals(0, fixture.downloader.resumePendingDeleteBatches())
            assertEquals(
                DownloadDeleteOutcome.FAILED.name,
                fixture.dao.deleteReceipt(first.batchId, failed.canonicalKey)?.outcome,
            )

            val retried = fixture.downloader.deleteDownloads(listOf(request))

            assertEquals(null, fixture.dao.source)
            assertEquals(
                DownloadDeleteOutcome.SUCCEEDED.name,
                retried.receipts.single().outcome.name,
            )
            assertEquals(
                DownloadDeleteOutcome.FAILED.name,
                fixture.dao.deleteReceipt(first.batchId, failed.canonicalKey)?.outcome,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除未确认后保留sidecar与DELETING且同一实例可再次删除`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.downloader.downloadItem(fixture.movie(), SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")

            val request =
                DownloadDeleteRequest(
                    itemId = ITEM_ID,
                    sourceId = SOURCE_ID,
                    mediaKind = DownloadMediaKind.VIDEO,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                )
            val first = fixture.downloader.deleteDownloads(listOf(request))
            val failed = first.receipts.single()

            assertEquals(DownloadDeleteOutcome.FAILED, failed.outcome)
            assertEquals(
                DownloadTaskStatus.DELETING.name,
                requireNotNull(fixture.dao.source).taskStatus,
            )
            assertTrue(
                "未确认清理不得移除 source sidecar",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )

            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())
            assertEquals(0, fixture.downloader.resumePendingDeleteBatches())
            assertTrue(
                "FAILED receipt 不得被启动恢复自动重跑",
                fixture.dao.deleteReceipt(first.batchId, failed.canonicalKey)?.outcome ==
                    DownloadDeleteOutcome.FAILED.name,
            )

            val retried = fixture.downloader.deleteDownloads(listOf(request))

            assertEquals("DELETING key 必须可再次删除", null, fixture.dao.source)
            assertEquals(
                DownloadDeleteOutcome.SUCCEEDED.name,
                retried.receipts.single().outcome.name,
            )
            assertTrue(
                "成功确认后才移除 sidecar",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )
            assertEquals(
                DownloadDeleteOutcome.FAILED.name,
                fixture.dao.deleteReceipt(first.batchId, failed.canonicalKey)?.outcome,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `DELETING receipt失败后startup扫描删行仍不自动重跑FAILED`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")

            val first =
                fixture.downloader.deleteDownloads(
                    listOf(
                        DownloadDeleteRequest(
                            itemId = ITEM_ID,
                            sourceId = SOURCE_ID,
                            mediaKind = DownloadMediaKind.VIDEO,
                            serverId = SERVER_ID,
                            userId = USER_ID.toString(),
                        )
                    )
                )
            val failed = first.receipts.single()
            assertEquals(DownloadDeleteOutcome.FAILED, failed.outcome)
            assertEquals(
                DownloadTaskStatus.DELETING.name,
                requireNotNull(fixture.dao.source).taskStatus,
            )

            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())
            fixture.newDownloader().recoverOnStartup()

            assertEquals("续删成功后 source 行必须移除", null, fixture.dao.source)
            assertEquals(
                "FAILED receipt 必须等待显式重试，不得被启动扫描改写",
                DownloadDeleteOutcome.FAILED.name,
                requireNotNull(fixture.dao.deleteReceipt(first.batchId, failed.canonicalKey))
                    .outcome,
            )
            assertEquals(null, fixture.dao.deleteBatch(first.batchId)?.completedAt)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `startup旧DELETING快照不得删除锁内已建立的新代`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")
            assertNotNull(
                runCatching { fixture.downloader.deleteTaskById(SOURCE_ID) }.exceptionOrNull()
            )
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)
            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())

            fixture.dao.armPendingSourcesSnapshotGate()
            val startup = async(Dispatchers.Default) { fixture.newDownloader().recoverOnStartup() }
            assertTrue(
                "startup 未取得旧 DELETING 快照",
                withTimeoutOrNull(5_000L) {
                    fixture.dao.pendingSourcesSnapshotStarted.await()
                    true
                } == true,
            )

            val (_, failure) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            assertEquals(null, failure)
            val pending = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.PENDING.name, pending.taskStatus)
            assertTrue(pending.taskGeneration > deleting.taskGeneration)

            fixture.dao.releasePendingSourcesSnapshot.complete(Unit)
            withTimeout(5_000L) { startup.await() }

            val retained = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.PENDING.name, retained.taskStatus)
            assertEquals(pending.taskGeneration, retained.taskGeneration)
        } finally {
            if (!fixture.dao.releasePendingSourcesSnapshot.isCompleted) {
                fixture.dao.releasePendingSourcesSnapshot.complete(Unit)
            }
            fixture.close()
        }
    }

    @Test
    fun `startup旧FAILED receipt不自动重跑且不得删除已REPLACE的新代`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")

            val first =
                fixture.downloader.deleteDownloads(
                    listOf(
                        DownloadDeleteRequest(
                            itemId = ITEM_ID,
                            sourceId = SOURCE_ID,
                            mediaKind = DownloadMediaKind.VIDEO,
                            serverId = SERVER_ID,
                            userId = USER_ID.toString(),
                        )
                    )
                )
            val failed = first.receipts.single()
            assertEquals(DownloadDeleteOutcome.FAILED, failed.outcome)
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)

            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())
            val (_, enqueueFailure) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            assertEquals(null, enqueueFailure)
            val pending = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.PENDING.name, pending.taskStatus)
            assertTrue(pending.taskGeneration > deleting.taskGeneration)

            fixture.persistVideoOrigin(SOURCE_ID)
            val external = fixture.seedStaleExternal()
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")
            fixture.dao.sourceEvents.clear()

            fixture.newDownloader().recoverOnStartup()

            val retainedRow = fixture.dao.sourceById(SOURCE_ID)
            assertNotNull("旧 receipt 不得删除已 REPLACE 的新 generation source", retainedRow)
            val retained = requireNotNull(retainedRow)
            assertEquals(pending.taskGeneration, retained.taskGeneration)
            assertTrue(
                "旧 receipt 不得把新代推进到删除：${retained.taskStatus}",
                retained.taskStatus != DownloadTaskStatus.DELETING.name,
            )
            assertTrue(
                "旧 receipt 不得清理新 source",
                fixture.dao.sourceEvents.none { it == "DELETE_SOURCE:$SOURCE_ID" },
            )
            assertTrue(
                "旧 receipt 不得清理新 source 的 media",
                fixture.dao.sourceEvents.none { it == "DELETE_MEDIA:$SOURCE_ID" },
            )
            assertTrue(
                "旧 receipt 不得清理新 generation 的 sidecar",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )
            assertTrue("旧 receipt 不得清理新 generation 的外部 media", external.isFile)
            assertTrue(
                "旧 receipt 不得清理新 generation 的 media row",
                fixture.dao.externalMediaStreams().any { it.path == external.path },
            )
            assertTrue("旧 receipt 不得清理共享 movie 快照", ITEM_ID in fixture.dao.snapshotIds())
            assertFalse("旧 receipt 不得清理共享 userData", ITEM_ID in fixture.dao.deletedUserDataIds())
            assertTrue("旧 receipt 不得清理共享图片", imageMarker.isFile)
            assertEquals(
                "FAILED receipt 必须保留为显式重试输入",
                "FAILED",
                requireNotNull(fixture.dao.deleteReceipt(first.batchId, failed.canonicalKey))
                    .outcome,
            )
            assertEquals(
                "FAILED receipt 批次不得被启动恢复自动完成",
                null,
                fixture.dao.deleteBatch(first.batchId)?.completedAt,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `无generation旧receipt保持FAILED并等待显式重试`() = runBlocking {
        val fixture = Fixture()
        try {
            val movie = fixture.movie()
            fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(fixture.dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")
            assertNotNull(
                runCatching { fixture.downloader.deleteTaskById(SOURCE_ID) }.exceptionOrNull()
            )
            val deleting = requireNotNull(fixture.dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)
            fixture.dao.seedPendingDeleteBatch(
                batchId = "legacy-receipt-$SOURCE_ID",
                itemId = ITEM_ID,
                sourceId = SOURCE_ID,
                expectedGeneration = null,
            )

            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())
            fixture.dao.sourceEvents.clear()

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())
            val retained = requireNotNull(fixture.dao.sourceById(SOURCE_ID))
            assertEquals(deleting.taskGeneration, retained.taskGeneration)
            assertEquals(DownloadTaskStatus.DELETING.name, retained.taskStatus)
            assertTrue(
                "无 generation 的旧 receipt 不得清理当前代",
                fixture.dao.sourceEvents.none {
                    it == "DELETE_SOURCE:$SOURCE_ID" || it == "DELETE_MEDIA:$SOURCE_ID"
                },
            )
            assertEquals(
                DownloadDeleteOutcome.FAILED.name,
                requireNotNull(
                        fixture.dao.deleteReceipt(
                            "legacy-receipt-$SOURCE_ID",
                            DownloadDeletionIdentity.canonicalKey(
                                DownloadDeleteRequest(
                                    itemId = ITEM_ID,
                                    sourceId = SOURCE_ID,
                                    mediaKind = DownloadMediaKind.VIDEO,
                                    serverId = SERVER_ID,
                                    userId = USER_ID.toString(),
                                )
                            ),
                        )
                    )
                    .outcome,
            )

            fixture.newDownloader().recoverOnStartup()
            assertTrue("startup scan 应完成已验证代次的删除", fixture.dao.sourceById(SOURCE_ID) == null)
            assertEquals(
                "无 generation 的 FAILED receipt 不得被启动恢复自动重跑",
                DownloadDeleteOutcome.FAILED.name,
                fixture.dao.deleteReceipts("legacy-receipt-$SOURCE_ID").single().outcome,
            )
            assertEquals(
                null,
                fixture.dao.deleteBatch("legacy-receipt-$SOURCE_ID")?.completedAt,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy自由文本generation前缀不得触发receipt删除`() = runBlocking {
        val fixture = Fixture()
        try {
            val deleting = fixture.seedDeletingSource()
            fixture.persistVideoOrigin(SOURCE_ID)
            val external = fixture.seedStaleExternal()
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")
            fixture.dao.seedPendingDeleteBatch(
                batchId = "legacy-generation-prefix-$SOURCE_ID",
                itemId = ITEM_ID,
                sourceId = SOURCE_ID,
                expectedGeneration = null,
                detail = "generation=${deleting.taskGeneration}",
            )
            fixture.dao.sourceEvents.clear()

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

            val retained = requireNotNull(fixture.dao.sourceById(SOURCE_ID))
            assertEquals(deleting.taskGeneration, retained.taskGeneration)
            assertEquals(DownloadTaskStatus.DELETING.name, retained.taskStatus)
            assertTrue("自由文本 generation 不得触发任何 source 副作用", fixture.dao.sourceEvents.isEmpty())
            assertTrue(
                "自由文本 generation 不得清理 sidecar",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )
            assertTrue("自由文本 generation 不得清理 external media", external.isFile)
            assertTrue(
                "自由文本 generation 不得清理 media row",
                fixture.dao.externalMediaStreams().any { it.path == external.path },
            )
            assertTrue("自由文本 generation 不得清理共享图片", imageMarker.isFile)
            assertEquals(
                DownloadDeleteOutcome.FAILED.name,
                fixture.dao.deleteReceipts("legacy-generation-prefix-$SOURCE_ID").single().outcome,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy异常文本包含完整当前generation不得触发receipt删除`() = runBlocking {
        val fixture = Fixture()
        try {
            val deleting = fixture.seedDeletingSource()
            fixture.persistVideoOrigin(SOURCE_ID)
            val external = fixture.seedStaleExternal()
            fixture.dao.seedPendingDeleteBatch(
                batchId = "legacy-generation-message-$SOURCE_ID",
                itemId = ITEM_ID,
                sourceId = SOURCE_ID,
                expectedGeneration = null,
                detail =
                    "generation=${deleting.taskGeneration}\n" +
                        "java.lang.IllegalStateException: cleanup failed at " +
                        "generation=${deleting.taskGeneration}",
            )
            fixture.dao.sourceEvents.clear()

            assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

            val retained = requireNotNull(fixture.dao.sourceById(SOURCE_ID))
            assertEquals(deleting.taskGeneration, retained.taskGeneration)
            assertEquals(DownloadTaskStatus.DELETING.name, retained.taskStatus)
            assertTrue("异常文本不得触发任何 source 副作用", fixture.dao.sourceEvents.isEmpty())
            assertTrue("异常文本不得清理 external media", external.isFile)
            assertTrue(
                "异常文本不得清理 media row",
                fixture.dao.externalMediaStreams().any { it.path == external.path },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `旧receipt遇RUNNING或COMPLETED新代均SUPERSEDED且零副作用`() = runBlocking {
        for (newStatus in listOf(DownloadTaskStatus.RUNNING, DownloadTaskStatus.COMPLETED)) {
            val fixture = Fixture()
            try {
                val deleting = fixture.seedDeletingSource()
                val batchId = "legacy-${newStatus.name.lowercase()}-$SOURCE_ID"
                fixture.dao.seedPendingDeleteBatch(
                    batchId = batchId,
                    itemId = ITEM_ID,
                    sourceId = SOURCE_ID,
                    expectedGeneration = deleting.taskGeneration,
                )
                fixture.dao.putSource(
                    deleting.copy(
                        taskStatus = newStatus.name,
                        taskGeneration = deleting.taskGeneration + 1L,
                    )
                )
                fixture.dao.sourceEvents.clear()

                assertEquals(0, fixture.newDownloader().resumePendingDeleteBatches())

                val retained = requireNotNull(fixture.dao.sourceById(SOURCE_ID))
                assertEquals(newStatus.name, retained.taskStatus)
                assertEquals(deleting.taskGeneration + 1L, retained.taskGeneration)
                assertTrue(
                    "${newStatus.name} 新代不得被旧 receipt 删除",
                    fixture.dao.sourceEvents.isEmpty(),
                )
                assertEquals(
                    DownloadDeleteOutcome.SUPERSEDED.name,
                    fixture.dao.deleteReceipts(batchId).single().outcome,
                )
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `删除剧集单个来源保留另一来源快照和父级层级`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.dao.trackParentRows = true
            val episode = fixture.episode()
            fixture.downloader.downloadItem(episode, SOURCE_ID, 0)
            val second =
                fixture.seedAdditionalSource(
                    index = 1,
                    sourceId = "episode-second-source",
                )
            fixture.persistEpisodeOrigin(second.source.id)
            val imageMarker = fixture.markerFile("images/$ITEM_ID/cover")

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertEquals(listOf(second.source.id), fixture.dao.sourceIds())
            assertNotNull("删除一个 source 不得清理共享 episode 快照", fixture.dao.episodeSnapshot)
            assertTrue(fixture.dao.hasEpisode(SEASON_ID, ITEM_ID))
            assertTrue(fixture.dao.hasSeason(SHOW_ID, SEASON_ID))
            assertFalse(ITEM_ID in fixture.dao.deletedUserDataIds())
            assertTrue(imageMarker.isFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `剧集入队为节目和季调度父级图片`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()

            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = episode,
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )

            assertTrue(error == null)
            assertTrue(handle > 0L)
            assertEquals(
                setOf(SHOW_ID, SEASON_ID),
                fixture.imageScheduler.parentItemIds.toSet(),
            )
            assertEquals(
                setOf(
                    ImageDownloadParentOwner(
                        kind = ImageDownloadParentKind.SHOW,
                        itemId = SHOW_ID,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    ),
                    ImageDownloadParentOwner(
                        kind = ImageDownloadParentKind.SEASON,
                        itemId = SEASON_ID,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    ),
                ),
                fixture.imageScheduler.parentOwners.toSet(),
            )
            assertEquals(SERVER_ID, fixture.dao.showSnapshot?.serverId)
            assertEquals(SHOW_ID, fixture.dao.seasonSnapshot?.seriesId)
            assertEquals(SEASON_ID, fixture.dao.seasonSnapshot?.id)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `父图调度器写入完整WorkManager输入`() = runBlocking {
        val fixture = Fixture()
        try {
            val gateway = RecordingWorkGateway()
            val scheduler = WorkManagerImageFinalizationScheduler(gateway)
            val owner =
                ImageDownloadParentOwner(
                    kind = ImageDownloadParentKind.SEASON,
                    itemId = SEASON_ID,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                )

            scheduler.scheduleParent(fixture.season(), owner)

            assertEquals(
                listOf(ImageDownloadWorkRules.parentWorkName(owner)),
                gateway.uniqueRequests.map { it.first },
            )
            val data = gateway.uniqueRequests.single().third.workSpec.input
            assertEquals(SEASON_ID.toString(), data.getString(ImagesDownloaderWorker.KEY_ITEM_ID))
            assertEquals(
                ImageDownloadParentKind.SEASON.name,
                data.getString(ImagesDownloaderWorker.KEY_PARENT_KIND),
            )
            assertEquals(SERVER_ID, data.getString(ImagesDownloaderWorker.KEY_SERVER_ID))
            assertEquals(
                USER_ID.toString(),
                data.getString(ImagesDownloaderWorker.KEY_USER_ID),
            )

            scheduler.cancelParent(owner)
            assertEquals(
                listOf(ImageDownloadWorkRules.parentWorkName(owner)),
                gateway.cancelledNames,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除最后一个剧集取消节目和季父级图片`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val source = fixture.episodeSource()
            fixture.seedCompletedSource(episode, source)
            fixture.dao.remainingEpisodes = emptyList()
            fixture.dao.remainingSeasons = emptyList()

            fixture.downloader.deleteItem(
                item = episode,
                source = source,
            )

            assertEquals(
                setOf(SEASON_ID, SHOW_ID),
                fixture.imageScheduler.cancelledParentItemIds.toSet(),
            )
            assertEquals(
                setOf(ImageDownloadParentKind.SEASON, ImageDownloadParentKind.SHOW),
                fixture.imageScheduler.cancelledParentOwners.map { it.kind }.toSet(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除仍有兄弟剧集时不取消同级父图`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val source = fixture.episodeSource()
            fixture.seedCompletedSource(episode, source)
            fixture.dao.remainingEpisodes = listOf(Any())
            fixture.dao.remainingSeasons = listOf(Any())

            fixture.downloader.deleteItem(
                item = episode,
                source = source,
            )

            assertTrue(fixture.imageScheduler.cancelledParentItemIds.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `完成恢复缺少sidecar时不写当前namespace快照或父图`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = episode,
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )
            assertTrue(error == null)
            assertTrue(handle > 0L)
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.allRecords().requireComplete().forEach {
                sidecar.remove(it.canonicalIdentity())
            }
            fixture.imageScheduler.parentOwners.clear()
            fixture.imageScheduler.parentItemIds.clear()
            fixture.resetEffectCalls()
            fixture.preferences.currentServer = "server-b"
            fixture.preferences.currentServerReads = 0
            fixture.repository.userId = OTHER_USER_ID
            fixture.repository.userIdReads = 0
            fixture.repository.showReads = 0
            fixture.repository.seasonReads = 0

            fixture.downloader.runQueue()

            assertEquals(0, fixture.dao.showSnapshotWrites)
            assertEquals(0, fixture.dao.seasonSnapshotWrites)
            assertEquals(0, fixture.dao.episodeSnapshotWrites)
            assertEquals(0, fixture.dao.userDataWrites)
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)
            assertEquals(0, fixture.repository.showReads)
            assertEquals(0, fixture.repository.seasonReads)
            assertTrue(
                "sidecar 缺失不得借用当前 server/user：${fixture.imageScheduler.parentOwners}",
                fixture.imageScheduler.parentOwners.isEmpty(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `同 UUID 不同 namespace 经生产接收后按各自冻结来源逐条恢复`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("22222222-2222-4222-8222-222222222222")
            val originA =
                JellyfinOrigin("server-a", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            val originB =
                JellyfinOrigin("server-b", UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
            fixture.repository.putItem(
                fixture
                    .movie()
                    .copy(
                        id = itemId,
                        name = "同 UUID 音乐",
                        kind = "AUDIO",
                    )
            )
            fixture.repository.putOriginItem(
                itemId,
                originA,
                fixture.movie().copy(id = itemId, name = "server-a song", kind = "AUDIO"),
            )
            fixture.repository.putOriginItem(
                itemId,
                originB,
                fixture.movie().copy(id = itemId, name = "server-b song", kind = "AUDIO"),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                originA,
                FindroidSource(
                    id = "source-server-a",
                    name = "A",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-a.test/song",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                originB,
                FindroidSource(
                    id = "source-server-b",
                    name = "B",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-b.test/song",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.repository.blockNextItemLookup()

            val receipt =
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "同 UUID 音乐",
                            albumName = "专辑",
                            artist = "歌手",
                            trackIndex = 1,
                            serverId = originA.serverId,
                            userId = originA.userId.toString(),
                        ),
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "同 UUID 音乐",
                            albumName = "专辑",
                            artist = "歌手",
                            trackIndex = 1,
                            serverId = originB.serverId,
                            userId = originB.userId.toString(),
                        ),
                    )
                )

            assertEquals(2, receipt.acceptedIds.size)
            assertEquals(1, fixture.dao.intentCount())
            assertEquals(
                setOf(
                    originA.serverId to originA.userId.toString(),
                    originB.serverId to originB.userId.toString(),
                ),
                fixture.downloader
                    .mediaSidecar()
                    .allRecords()
                    .requireComplete()
                    .filter { it.sourceId.isNullOrBlank() }
                    .filter { it.itemId == itemId.toString() }
                    .mapTo(mutableSetOf()) { it.serverId to it.userId },
            )

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.repository.awaitItemLookup())
            fixture.preferences.currentServer = "server-c"
            fixture.repository.userId = UUID.fromString("33333333-3333-4333-8333-333333333333")
            fixture.preferences.currentServerReads = 0
            fixture.repository.userIdReads = 0
            fixture.repository.baseUrlReads = 0
            fixture.repository.accessTokenReads = 0
            fixture.repository.releaseItemLookup()

            assertTrue(
                "同一 item 的旧 namespace 应被新 intent supersede；sources=${fixture.dao.sourceIds()} " +
                    "sidecar=${fixture.downloader.mediaSidecar().allRecords()} " +
                    "logs=${fixture.logs}",
                fixture.dao.awaitInsertedSourceCount(1),
            )
            assertEquals(
                mapOf("source-server-b" to (originB.serverId to originB.userId.toString())),
                fixture.downloader
                    .mediaSidecar()
                    .allRecords()
                    .requireComplete()
                    .filter { !it.sourceId.isNullOrBlank() }
                    .filter { it.itemId == itemId.toString() }
                    .associate { record ->
                        checkNotNull(record.sourceId) to (record.serverId to record.userId)
                    },
            )
            fixture.downloader.runQueue()
            assertEquals(
                mapOf(
                    "https://server-b.test/song" to ("https://server-b.test" to "token-server-b")
                ),
                fixture.transfer
                    .requestSnapshot()
                    .filter { it.url.startsWith("https://server-") }
                    .associate { request -> request.url to (request.baseUrl to request.token) },
            )
            assertEquals(
                "sidecar 恢复不得读取 current server",
                0,
                fixture.preferences.currentServerReads,
            )
            assertEquals(
                "sidecar 恢复不得读取 current user",
                0,
                fixture.repository.userIdReads,
            )
            assertEquals(0, fixture.repository.baseUrlReads)
            assertEquals(0, fixture.repository.accessTokenReads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `单个 intent resolver 挂起时同批其它项仍推进到 source created`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemIds =
                listOf(
                    UUID.fromString("71000000-0000-4000-8000-000000000001"),
                    UUID.fromString("71000000-0000-4000-8000-000000000002"),
                    UUID.fromString("71000000-0000-4000-8000-000000000003"),
                )
            itemIds.forEachIndexed { index, itemId ->
                fixture.prepareMusicOrigin(
                    itemId = itemId,
                    name = "intent-$index",
                    sourceId = "intent-source-$index",
                )
            }
            fixture.repository.blockItemLookup(itemIds[1])
            fixture.downloader.receiveMusicBatch(
                itemIds.mapIndexed { index, itemId ->
                    fixture.completeMusicRequest(itemId, "intent-$index")
                }
            )
            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.repository.awaitItemLookup(itemIds[1]))

            assertTrue(
                "第 1 / 3 项必须越过挂起的第 2 项完成：sources=${fixture.dao.sourceIds()} " +
                    "events=${itemIds.map(fixture.dao::downloadIntentStatusEvents)} logs=${fixture.logs}",
                fixture.dao.awaitInsertedSourceCount(2),
            )
            assertEquals(
                listOf("RECEIVED", "RESOLVING", "SOURCE_CREATED"),
                fixture.dao.downloadIntentStatusEvents(itemIds[0]),
            )
            assertEquals(
                listOf("RECEIVED", "RESOLVING", "SOURCE_CREATED"),
                fixture.dao.downloadIntentStatusEvents(itemIds[2]),
            )
            assertEquals(
                listOf("RECEIVED", "RESOLVING"),
                fixture.dao.downloadIntentStatusEvents(itemIds[1]),
            )
        } finally {
            fixture.repository.releaseAllItemLookups()
            fixture.close()
        }
    }

    @Test
    fun `单项 intent 超过 deadline 只失败本项且不生成 source`() = runBlocking {
        val scheduler = ManualDownloadIntentDeadlineScheduler()
        val fixture = Fixture(resolutionDeadlineScheduler = scheduler)
        val slowItemId = UUID.fromString("72000000-0000-4000-8000-000000000001")
        val fastItemId = UUID.fromString("72000000-0000-4000-8000-000000000002")
        try {
            fixture.prepareMusicOrigin(slowItemId, "slow", "timeout-source-slow")
            fixture.prepareMusicOrigin(fastItemId, "fast", "timeout-source-fast")
            fixture.repository.blockItemLookup(slowItemId)
            fixture.downloader.receiveMusicBatch(
                listOf(
                    fixture.completeMusicRequest(slowItemId, "slow"),
                    fixture.completeMusicRequest(fastItemId, "fast"),
                )
            )
            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.repository.awaitItemLookup(slowItemId))
            assertTrue(
                "未被阻塞项必须先完成",
                fixture.dao.awaitInsertedSourceCount(1),
            )

            scheduler.advanceTimeBy(15_000L)
            fixture.repository.releaseAllItemLookups()

            assertTrue(
                "超时项必须落 FAILED",
                fixture.awaitIntentStatus(slowItemId, DownloadIntentStatus.FAILED.name),
            )
            assertEquals(
                listOf("RECEIVED", "RESOLVING", "FAILED"),
                fixture.dao.downloadIntentStatusEvents(slowItemId),
            )
            assertEquals(
                DownloadFailureReason.NETWORK_UNAVAILABLE.name,
                fixture.dao.downloadIntent(slowItemId)?.failureReason,
            )
            assertFalse(fixture.dao.sourceIds().contains("timeout-source-slow"))
            assertEquals(
                listOf("RECEIVED", "RESOLVING", "SOURCE_CREATED"),
                fixture.dao.downloadIntentStatusEvents(fastItemId, generation = 0L),
            )
        } finally {
            fixture.repository.releaseAllItemLookups()
            fixture.close()
        }
    }

    @Test
    fun `旧 generation 迟到成功不得覆盖重试代或重复创建 source`() = runBlocking {
        val fixture = Fixture()
        val itemId = UUID.fromString("73000000-0000-4000-8000-000000000001")
        try {
            fixture.prepareMusicOrigin(itemId, "stale-success", "stale-success-source")
            fixture.repository.blockItemLookup(itemId)
            fixture.downloader.receiveMusicBatch(
                listOf(fixture.completeMusicRequest(itemId, "stale-success"))
            )
            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.repository.awaitItemLookup(itemId))
            assertEquals(0L, fixture.dao.downloadIntent(itemId)?.taskGeneration)

            fixture.cancelIntent(itemId)
            fixture.retryIntent(itemId)
            assertEquals(2L, fixture.dao.downloadIntent(itemId)?.taskGeneration)
            fixture.repository.releaseAllItemLookups()

            assertTrue(
                "重试代必须创建唯一 source",
                fixture.dao.awaitInsertedSourceCount(1),
            )
            assertEquals(listOf("stale-success-source"), fixture.dao.sourceIds())
            assertEquals(
                listOf("RECEIVED", "RESOLVING"),
                fixture.dao.downloadIntentStatusEvents(itemId, generation = 0L),
            )
            assertEquals(
                listOf("RECEIVED", "RESOLVING", "SOURCE_CREATED"),
                fixture.dao.downloadIntentStatusEvents(itemId, generation = 2L),
            )
        } finally {
            fixture.repository.releaseAllItemLookups()
            fixture.close()
        }
    }

    @Test
    fun `旧 generation 迟到失败不得覆盖重试代`() = runBlocking {
        val scheduler = ManualDownloadIntentDeadlineScheduler()
        val fixture = Fixture(resolutionDeadlineScheduler = scheduler)
        val itemId = UUID.fromString("74000000-0000-4000-8000-000000000001")
        try {
            fixture.prepareMusicOrigin(itemId, "stale-failure", "stale-failure-source")
            fixture.repository.blockItemLookup(itemId)
            fixture.downloader.receiveMusicBatch(
                listOf(fixture.completeMusicRequest(itemId, "stale-failure"))
            )
            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.repository.awaitItemLookup(itemId))
            val oldKey =
                DownloadIntentResolutionKey(
                    logicalKey =
                        DownloadCanonicalIdentity.of(
                                mediaKind = DownloadMediaKind.MUSIC,
                                itemId = itemId.toString(),
                                serverId = SERVER_ID,
                                userId = USER_ID.toString(),
                                sourceId = null,
                            )
                            .canonicalKey,
                    generation = 0L,
                )
            scheduler.awaitArmed(oldKey)

            fixture.cancelIntent(itemId)
            fixture.retryIntent(itemId)
            scheduler.timeOut(oldKey)
            fixture.repository.releaseAllItemLookups()

            assertTrue("旧代超时后新代仍须完成", fixture.dao.awaitInsertedSourceCount(1))
            assertEquals(listOf("stale-failure-source"), fixture.dao.sourceIds())
            assertEquals(
                listOf("RECEIVED", "RESOLVING"),
                fixture.dao.downloadIntentStatusEvents(itemId, generation = 0L),
            )
            assertEquals(
                listOf("RECEIVED", "RESOLVING", "SOURCE_CREATED"),
                fixture.dao.downloadIntentStatusEvents(itemId, generation = 2L),
            )
        } finally {
            fixture.repository.releaseAllItemLookups()
            fixture.close()
        }
    }

    @Test
    fun `同 item source 双完整 origin 歧义时不得启动 transfer`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("14141414-1414-4414-8414-141414141414")
            val sourceId = "source-ambiguous"
            val originA =
                JellyfinOrigin("server-a", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            val originB =
                JellyfinOrigin("server-b", UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
            fixture.repository.putOriginItem(
                itemId,
                originA,
                fixture.movie().copy(id = itemId, name = "A", kind = "AUDIO"),
            )
            fixture.repository.putOriginItem(
                itemId,
                originB,
                fixture.movie().copy(id = itemId, name = "B", kind = "AUDIO"),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                originA,
                FindroidSource(
                    id = sourceId,
                    name = "A",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-a.test/ambiguous",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                originB,
                FindroidSource(
                    id = sourceId,
                    name = "B",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-b.test/ambiguous",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )

            assertTrue(
                fixture.putMusicSidecar(
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = originA.serverId,
                    userId = originA.userId.toString(),
                    displayName = "A",
                )
            )
            assertTrue(
                fixture.putMusicSidecar(
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = originB.serverId,
                    userId = originB.userId.toString(),
                    displayName = "B",
                )
            )
            fixture.dao.seedDownloadIntent(itemId)
            fixture.dao.putSource(
                FindroidSourceDto(
                    id = sourceId,
                    itemId = itemId,
                    name = "ambiguous",
                    type = FindroidSourceType.LOCAL,
                    path = File(fixture.downloads, "$itemId.$sourceId.1.download").path,
                    taskStatus = DownloadTaskStatus.PENDING.name,
                    updatedAt = 1L,
                    downloadedBytes = 0L,
                    totalBytes = MEDIA_BYTES.size.toLong(),
                    engineVersion = DownloaderImpl.ENGINE_VERSION,
                    container = "mp3",
                    taskGeneration = 1L,
                )
            )
            fixture.preferences.currentServer = "server-c"
            fixture.repository.userId = UUID.fromString("33333333-3333-4333-8333-333333333333")
            fixture.preferences.currentServerReads = 0
            fixture.repository.userIdReads = 0
            fixture.repository.baseUrlReads = 0
            fixture.repository.accessTokenReads = 0

            val processed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.downloader.queueChanges.first()
                }
            fixture.downloader.recoverOnStartup()
            withTimeout(5_000L) { processed.await() }
            fixture.downloader.runQueue()

            assertTrue(
                "身份歧义不得启动任何 transfer：${fixture.transfer.requestSnapshot()}",
                fixture.transfer.requestSnapshot().isEmpty(),
            )
            assertEquals(
                "身份歧义必须可归因为服务器侧失败",
                DownloadFailureReason.SERVER_ERROR.name,
                fixture.dao.sourceById(sourceId)?.failureReason,
            )
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)
            assertEquals(0, fixture.repository.baseUrlReads)
            assertEquals(0, fixture.repository.accessTokenReads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `跨批次 unknown 与 complete 不得通过 Room 裸 itemId 互认`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("66666666-6666-4666-8666-666666666666")
            val origin =
                JellyfinOrigin("server-a", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            fixture.repository.putOriginItem(
                itemId,
                origin,
                fixture.movie().copy(id = itemId, name = "cross-batch song", kind = "AUDIO"),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                origin,
                FindroidSource(
                    id = "source-cross-batch",
                    name = "cross-batch",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-a.test/cross-batch",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.repository.blockNextItemLookup()

            val complete =
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "complete",
                            albumName = null,
                            artist = null,
                            trackIndex = 1,
                            serverId = origin.serverId,
                            userId = origin.userId.toString(),
                        )
                    )
                )
            val unknown = runCatching {
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "unknown",
                            albumName = null,
                            artist = null,
                            trackIndex = 2,
                        )
                    )
                )
            }
                .getOrElse { failure ->
                    throw IllegalStateException(
                        "unknown 批受理失败：intent=${fixture.dao.downloadIntent(itemId)} " +
                            "sidecar=${fixture.downloader.mediaSidecar().allRecords()}",
                        failure,
                    )
                }
            val anotherUnknown =
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "another unknown",
                            albumName = null,
                            artist = null,
                            trackIndex = 3,
                        )
                    )
                )

            assertEquals(1, complete.acceptedRequests.size)
            assertEquals(
                "unknown 不得被同 UUID 的 Room / complete 行吞掉：$unknown",
                1,
                unknown.acceptedRequests.size,
            )
            assertEquals(0, anotherUnknown.acceptedRequests.size)
            assertEquals(1, anotherUnknown.alreadyAcceptedCount)
            assertEquals(
                2,
                fixture.downloader.mediaSidecar().allRecords().requireComplete().count {
                    it.itemId == itemId.toString()
                },
            )
            fixture.repository.releaseItemLookup()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `不完整 origin 不得分别借用 current server 和 user`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("77777777-7777-4777-8777-777777777777")
            fixture.preferences.currentServer = "server-current"
            fixture.repository.userId = UUID.fromString("88888888-8888-4888-8888-888888888888")

            val receipt =
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "server-only",
                            albumName = null,
                            artist = null,
                            trackIndex = 1,
                            serverId = "server-a",
                        )
                    )
                )

            assertEquals(1, receipt.acceptedRequests.size)
            val pending =
                fixture.downloader.mediaSidecar().allRecords().requireComplete().single {
                    it.itemId == itemId.toString()
                }
            assertEquals("不完整 origin 不得混拼 current user", null, pending.serverId)
            assertEquals("不完整 origin 不得混拼 current user", null, pending.userId)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `同 UUID 的 sidecar key 隔离且只采用 Room anchor namespace`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("99999999-9999-4999-8999-999999999999")
            val originA =
                JellyfinOrigin("server-a", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            val originB =
                JellyfinOrigin("server-b", UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
            fixture.repository.putOriginItem(
                itemId,
                originA,
                fixture.movie().copy(id = itemId, name = "A", kind = "AUDIO"),
            )
            fixture.repository.putOriginItem(
                itemId,
                originB,
                fixture.movie().copy(id = itemId, name = "B", kind = "AUDIO"),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                originA,
                FindroidSource(
                    id = "source-key-a",
                    name = "A",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-a.test/key",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                originB,
                FindroidSource(
                    id = "source-key-b",
                    name = "B",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-b.test/key",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.seedAnchoredIntent(
                itemId = itemId,
                name = "B",
                serverId = originB.serverId,
                userId = originB.userId,
            )
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.put(
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    serverId = originA.serverId,
                    userId = originA.userId.toString(),
                    displayName = "A",
                )
            )
            sidecar.put(
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    serverId = originB.serverId,
                    userId = originB.userId.toString(),
                    displayName = "B",
                )
            )

            fixture.downloader.recoverOnStartup()

            assertTrue(
                "Room anchor 指定的 B 必须入队：sources=${fixture.dao.sourceIds()} logs=${fixture.logs}",
                fixture.dao.awaitInsertedSourceCount(1),
            )
            assertEquals(listOf("source-key-b"), fixture.dao.sourceIds())
            assertTrue(
                "wrong namespace 的 A sidecar 不得被采用或删除",
                sidecar.allRecords().requireComplete().any {
                    it.serverId == originA.serverId && it.sourceId.isNullOrBlank()
                },
            )

            fixture.downloader.recoverOnStartup()

            assertEquals(setOf("source-key-b"), fixture.dao.sourceIds().toSet())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `跨 namespace 复用同一 sourceId 的生产 receipt 保留先到项且另一意图可重试`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("13131313-1313-4313-8313-131313131313")
            val originA =
                JellyfinOrigin("server-a", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            val originB =
                JellyfinOrigin("server-b", UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"))
            val sharedSource =
                FindroidSource(
                    id = "shared-source-id",
                    name = "shared-source-id",
                    type = FindroidSourceType.REMOTE,
                    path = "https://shared.test/song",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                )
            fixture.repository.putOriginItem(
                itemId,
                originA,
                fixture.movie().copy(id = itemId, name = "A", kind = "AUDIO"),
            )
            fixture.repository.putOriginItem(
                itemId,
                originB,
                fixture.movie().copy(id = itemId, name = "B", kind = "AUDIO"),
            )
            fixture.repository.putOriginMediaSource(itemId, originA, sharedSource)
            fixture.repository.putOriginMediaSource(itemId, originB, sharedSource)
            fixture.repository.blockNextItemLookup()
            val receipt =
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "A",
                            albumName = null,
                            artist = null,
                            trackIndex = 1,
                            serverId = originA.serverId,
                            userId = originA.userId.toString(),
                        ),
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "B",
                            albumName = null,
                            artist = null,
                            trackIndex = 1,
                            serverId = originB.serverId,
                            userId = originB.userId.toString(),
                        ),
                    )
                )
            assertEquals(
                "acceptedRequests 表示两条持久意图已受理，不代表两条 source 都已可见",
                2,
                receipt.acceptedRequests.size,
            )
            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.repository.awaitItemLookup())
            fixture.repository.releaseItemLookup()
            assertTrue(fixture.dao.awaitInsertedSourceCount(1))
            delay(100)

            assertEquals(listOf("shared-source-id"), fixture.dao.sourceIds())
            val sidecar = fixture.downloader.mediaSidecar()
            assertEquals(
                "同一 sourceId 只能属于一个 namespace，另一 key 必须保持可重试",
                1,
                sidecar.allRecords().requireComplete().count {
                    it.itemId == itemId.toString() && it.sourceId.isNullOrBlank()
                },
            )
            val retained =
                sidecar.allRecords().requireComplete().single {
                    it.itemId == itemId.toString() && !it.sourceId.isNullOrBlank()
                }
            val retainedServer = checkNotNull(retained.serverId)
            fixture.preferences.currentServer = "server-c"
            fixture.repository.userId = UUID.fromString("33333333-3333-4333-8333-333333333333")
            fixture.preferences.currentServerReads = 0
            fixture.repository.userIdReads = 0
            fixture.repository.baseUrlReads = 0
            fixture.repository.accessTokenReads = 0

            fixture.downloader.runQueue()

            assertEquals(
                listOf(
                    "https://shared.test/song" to
                        ("https://$retainedServer.test" to "token-$retainedServer")
                ),
                fixture.transfer
                    .requestSnapshot()
                    .filter { it.url == "https://shared.test/song" }
                    .map { request -> request.url to (request.baseUrl to request.token) },
            )
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)
            assertEquals(0, fixture.repository.baseUrlReads)
            assertEquals(0, fixture.repository.accessTokenReads)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `sidecar 替换后 Room 插入失败必须补偿 pending 且重启可完成`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("15151515-1515-4515-8515-151515151515")
            val origin =
                JellyfinOrigin("server-a", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            fixture.repository.putOriginItem(
                itemId,
                origin,
                fixture.movie().copy(id = itemId, name = "insert-fail", kind = "AUDIO"),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                origin,
                FindroidSource(
                    id = "source-insert-fail",
                    name = "insert-fail",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-a.test/insert-fail",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.repository.blockNextItemLookup()
            fixture.dao.failSourceInsertions(1)
            val receipt =
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "insert-fail",
                            albumName = null,
                            artist = null,
                            trackIndex = 1,
                            serverId = origin.serverId,
                            userId = origin.userId.toString(),
                        )
                    )
                )
            assertEquals(1, receipt.acceptedRequests.size)

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.repository.awaitItemLookup())
            fixture.repository.releaseItemLookup()
            assertTrue(fixture.dao.awaitSourceInsertAttempts(1))
            assertTrue(
                "Room 插入失败后不得出现可见 source：${fixture.dao.sourceIds()}",
                fixture.dao.sourceIds().isEmpty(),
            )
            val sidecar = fixture.downloader.mediaSidecar()
            assertTrue(
                "Room 插入失败后必须补偿为可重试 pending",
                withTimeoutOrNull(2_000L) {
                    while (
                        sidecar.allRecords().requireComplete().none {
                            it.itemId == itemId.toString() &&
                                it.serverId == origin.serverId &&
                                it.userId == origin.userId.toString() &&
                                it.sourceId.isNullOrBlank()
                        }
                    ) {
                        delay(10L)
                    }
                    true
                } ?: false,
            )
            assertEquals(
                "失败状态不得同时保留伪完整 sourceId",
                0,
                sidecar.allRecords().requireComplete().count {
                    it.itemId == itemId.toString() && !it.sourceId.isNullOrBlank()
                },
            )

            fixture.retryIntent(itemId)
            fixture.newDownloader().recoverOnStartup()

            assertTrue(
                "解除 Room 失败后重启恢复必须再次完成：${fixture.dao.sourceIds()}",
                fixture.dao.awaitInsertedSourceCount(1),
            )
            assertEquals(listOf("source-insert-fail"), fixture.dao.sourceIds())
            assertTrue(
                "成功插入 source 后 pending 必须被替换",
                sidecar.allRecords().requireComplete().none {
                    it.itemId == itemId.toString() && it.sourceId.isNullOrBlank()
                },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `sidecar 替换失败时不得插入 source 且失败后可重试`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("12121212-1212-4212-8212-121212121212")
            val origin =
                JellyfinOrigin("server-a", UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"))
            fixture.repository.putOriginItem(
                itemId,
                origin,
                fixture.movie().copy(id = itemId, name = "sidecar-fail", kind = "AUDIO"),
            )
            fixture.repository.putOriginMediaSource(
                itemId,
                origin,
                FindroidSource(
                    id = "source-sidecar-fail",
                    name = "sidecar-fail",
                    type = FindroidSourceType.REMOTE,
                    path = "https://server-a.test/sidecar-fail",
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.seedAnchoredIntent(itemId, "sidecar-fail", userId = origin.userId)
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.put(
                DownloadMediaRecord(
                    itemId = itemId.toString(),
                    kind = DownloadMediaKind.MUSIC,
                    serverId = origin.serverId,
                    userId = origin.userId.toString(),
                    sourceId = null,
                    displayName = "sidecar-fail",
                )
            )
            val blockedTemp = fixture.blockSidecarReplace()

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.repository.awaitOriginItemLookup(1))
            delay(100)

            assertTrue(
                "sidecar 写入失败不得让 source 可见：${fixture.dao.sourceIds()}",
                fixture.dao.sourceIds().isEmpty(),
            )
            assertTrue(
                "sidecar 写入失败后必须保留可恢复的 pending 记录",
                sidecar.allRecords().requireComplete().any {
                    it.itemId == itemId.toString() && it.sourceId.isNullOrBlank()
                },
            )

            File(blockedTemp, "holder").delete()
            assertTrue(blockedTemp.delete())
            fixture.retryIntent(itemId)
            fixture.downloader.recoverOnStartup()

            assertTrue(
                "sidecar 恢复后重试必须能完成 source 插入",
                fixture.dao.awaitInsertedSourceCount(1),
            )
            assertEquals(listOf("source-sidecar-fail"), fixture.dao.sourceIds())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy intent 缺 sidecar 经恢复与队列后可见需重下且不插入源`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("44444444-4444-4444-8444-444444444444")
            fixture.repository.putItem(
                fixture.movie().copy(id = itemId, name = "legacy 音乐", kind = "AUDIO")
            )
            fixture.dao.seedDownloadIntent(itemId)
            fixture.preferences.currentServer = "server-current"
            fixture.preferences.currentServerReads = 0
            fixture.repository.userId = UUID.fromString("55555555-5555-4555-8555-555555555555")
            fixture.repository.userIdReads = 0

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            fixture.downloader.runQueue()

            assertTrue(
                "旧 intent 不得生成不可执行 source：${fixture.dao.sourceIds()}",
                fixture.dao.sourceIds().isEmpty(),
            )
            assertTrue(
                "旧 intent 不得启动 transfer：${fixture.transfer.requestSnapshot()}",
                fixture.transfer.requestSnapshot().isEmpty(),
            )
            val task = fixture.downloader.refreshDownloadTasks().single { it.itemId == itemId }
            assertEquals(DownloadTaskStatus.FAILED, task.status)
            assertEquals(DownloadFailureReason.CANCELLED, task.failureReason)
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                task.failureDetail,
            )
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            fixture.downloader.runQueue()
            assertTrue(fixture.dao.sourceIds().isEmpty())
            assertEquals(
                DownloadFailureReason.CANCELLED,
                fixture.dao.downloadIntent(itemId)?.failureReason.toFailureReason(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy intent 只清理精确未知 pending source 且重复恢复幂等`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("46464646-4646-4646-8646-464646464646")
            fixture.repository.putItem(
                fixture.movie().copy(id = itemId, name = "legacy cleanup", kind = "AUDIO")
            )
            fixture.dao.seedDownloadIntent(itemId)
            fixture.dao.putSource(
                fixture
                    .seedPendingSource(itemId, "legacy-unknown")
                    .copy(
                        taskStatus = "PENDING",
                        originServerId = null,
                        originUserId = null,
                    )
            )
            fixture.dao.putSource(
                fixture
                    .seedPendingSource(itemId, "other-unproven")
                    .copy(
                        taskStatus = "FAILED",
                        originServerId = null,
                        originUserId = null,
                    )
            )
            fixture.downloader
                .mediaSidecar()
                .put(
                    DownloadMediaRecord(
                        itemId = itemId.toString(),
                        kind = DownloadMediaKind.MUSIC,
                        serverId = null,
                        userId = null,
                        sourceId = "legacy-unknown",
                        displayName = "legacy cleanup",
                    )
                )
            fixture.resetCurrentOriginReads()

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))

            assertEquals(setOf("other-unproven"), fixture.dao.sourceIds().toSet())
            assertTrue(
                "精确未知 sidecar 应随不可执行 source 清理",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.itemId == itemId.toString()
                },
            )
            assertEquals(
                DownloadFailureReason.CANCELLED,
                fixture.dao.downloadIntent(itemId)?.failureReason.toFailureReason(),
            )
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                fixture.dao.downloadIntent(itemId)?.failureDetail,
            )
            fixture.assertCurrentOriginReadsZero()

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            fixture.downloader.runQueue()

            assertEquals(setOf("other-unproven"), fixture.dao.sourceIds().toSet())
            assertEquals(
                DownloadFailureReason.CANCELLED,
                fixture.dao.downloadIntent(itemId)?.failureReason.toFailureReason(),
            )
            fixture.assertCurrentOriginReadsZero()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy intent 遇损坏 sidecar 仍可见失败且不插入源`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("47474747-4747-4747-8747-474747474747")
            fixture.repository.putItem(
                fixture.movie().copy(id = itemId, name = "legacy corrupt", kind = "AUDIO")
            )
            fixture.dao.seedDownloadIntent(itemId)
            val sidecar = fixture.sidecarFile()
            val corrupt = "v2,broken"
            sidecar.writeText(corrupt)
            fixture.resetCurrentOriginReads()

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            fixture.downloader.runQueue()

            assertTrue(fixture.dao.sourceIds().isEmpty())
            assertTrue(fixture.transfer.requestSnapshot().isEmpty())
            assertEquals(corrupt, sidecar.readText())
            val task = fixture.downloader.refreshDownloadTasks().single { it.itemId == itemId }
            assertEquals(DownloadTaskStatus.FAILED, task.status)
            assertEquals(DownloadFailureReason.CANCELLED, task.failureReason)
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                fixture.dao.downloadIntent(itemId)?.failureDetail,
            )
            fixture.assertCurrentOriginReadsZero()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy intent 遇不可读 sidecar 仍可见失败且不插入源`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("49494949-4949-4949-8949-494949494949")
            fixture.repository.putItem(
                fixture.movie().copy(id = itemId, name = "legacy unreadable", kind = "AUDIO")
            )
            fixture.dao.seedDownloadIntent(itemId)
            val sidecar = fixture.sidecarFile()
            assertTrue(sidecar.mkdirs())
            fixture.resetCurrentOriginReads()

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            fixture.downloader.runQueue()

            assertTrue(fixture.dao.sourceIds().isEmpty())
            assertTrue(fixture.transfer.requestSnapshot().isEmpty())
            assertTrue(sidecar.isDirectory)
            val task = fixture.downloader.refreshDownloadTasks().single { it.itemId == itemId }
            assertEquals(DownloadTaskStatus.FAILED, task.status)
            assertEquals(DownloadFailureReason.CANCELLED, task.failureReason)
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                fixture.dao.downloadIntent(itemId)?.failureDetail,
            )
            fixture.assertCurrentOriginReadsZero()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy intent 歧义未知 source 保留但失败关闭且不入队`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("48484848-4848-4848-8848-484848484848")
            fixture.repository.putItem(
                fixture.movie().copy(id = itemId, name = "legacy ambiguous", kind = "AUDIO")
            )
            fixture.dao.seedDownloadIntent(itemId)
            fixture.dao.putSource(fixture.seedPendingSource(itemId, "ambiguous-unknown"))
            fixture.downloader
                .mediaSidecar()
                .putAll(
                    listOf(
                        DownloadMediaRecord(
                            itemId = itemId.toString(),
                            kind = DownloadMediaKind.MUSIC,
                            albumName = "A",
                            userId = null,
                            sourceId = "ambiguous-unknown",
                            displayName = "legacy ambiguous",
                        ),
                        DownloadMediaRecord(
                            itemId = itemId.toString(),
                            kind = DownloadMediaKind.MUSIC,
                            albumName = "B",
                            userId = null,
                            sourceId = "ambiguous-unknown",
                            displayName = "legacy ambiguous",
                        ),
                    )
                )
            fixture.resetCurrentOriginReads()

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            withTimeout(5_000L) {
                while (
                    fixture.dao.sourceById("ambiguous-unknown")?.taskStatus !=
                        DownloadTaskStatus.FAILED.name
                ) {
                    delay(10L)
                }
            }
            fixture.downloader.runQueue()

            assertEquals(setOf("ambiguous-unknown"), fixture.dao.sourceIds().toSet())
            assertEquals("FAILED", fixture.dao.sourceById("ambiguous-unknown")?.taskStatus)
            assertEquals(
                DownloadFailureReason.CANCELLED.name,
                fixture.dao.sourceById("ambiguous-unknown")?.failureReason,
            )
            assertTrue(fixture.transfer.requestSnapshot().isEmpty())
            assertEquals(
                DownloadFailureReason.CANCELLED,
                fixture.dao.downloadIntent(itemId)?.failureReason.toFailureReason(),
            )
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                fixture.dao.downloadIntent(itemId)?.failureDetail,
            )
            fixture.assertCurrentOriginReadsZero()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `sidecar namespace 不完整时旧 intent 收口失败且不进入队列`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("50505050-5050-4050-8050-505050505050")
            fixture.dao.seedDownloadIntent(itemId)
            assertTrue(
                fixture.putMusicSidecar(
                    itemId = itemId,
                    sourceId = null,
                    serverId = SERVER_ID,
                    displayName = "partial namespace",
                )
            )
            fixture.resetCurrentOriginReads()

            val processed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.downloader.queueChanges.first()
                }
            fixture.downloader.recoverOnStartup()
            withTimeout(5_000L) { processed.await() }
            fixture.downloader.runQueue()

            assertTrue(fixture.dao.sourceIds().isEmpty())
            assertTrue(fixture.transfer.requestSnapshot().isEmpty())
            assertEquals(
                DownloadIntentStatus.FAILED.name,
                fixture.dao.downloadIntent(itemId)?.status,
            )
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                fixture.dao.downloadIntent(itemId)?.failureDetail,
            )
            val task = fixture.downloader.refreshDownloadTasks().single { it.itemId == itemId }
            assertEquals(DownloadTaskStatus.FAILED, task.status)
            assertEquals(DownloadFailureReason.CANCELLED, task.failureReason)
            fixture.assertCurrentOriginReadsZero()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `partial namespace 首次失败后完整重下可替换旧占位且删除新源不复活失败`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("53535353-5353-4353-8353-535353535353")
            fixture.dao.seedDownloadIntent(itemId)
            assertTrue(
                fixture.putMusicSidecar(
                    itemId = itemId,
                    sourceId = null,
                    serverId = SERVER_ID,
                    displayName = "partial retry",
                )
            )

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                fixture.dao.downloadIntent(itemId)?.failureDetail,
            )

            fixture.prepareMusicOrigin(itemId, "partial retry")
            val receipt =
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "partial retry",
                            albumName = "Retry",
                            artist = "Provenance",
                            trackIndex = 1,
                            serverId = SERVER_ID,
                            userId = USER_ID.toString(),
                        )
                    )
                )
            assertEquals(1, receipt.acceptedRequests.size)

            assertTrue(
                "完整重下必须替换 partial 占位并解析新 source：" +
                    "intent=${fixture.dao.downloadIntent(itemId)} " +
                    "sidecar=${fixture.downloader.mediaSidecar().allRecords()}",
                fixture.dao.awaitInsertedSourceCount(1),
            )
            fixture.downloader.runQueue()
            assertEquals(listOf(SOURCE_ID), fixture.dao.sourceIds())
            assertTrue(fixture.transfer.requestSnapshot().isNotEmpty())
            assertTrue(
                withTimeoutOrNull(5_000L) {
                    while (fixture.dao.downloadIntent(itemId) != null) delay(10L)
                    true
                } == true
            )

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))
            assertTrue(fixture.dao.sourceById(SOURCE_ID) == null)
            assertEquals(null, fixture.dao.downloadIntent(itemId))
            assertTrue(
                "删除新 source 后旧 partial 不得残留：" + fixture.downloader.mediaSidecar().allRecords(),
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.itemId == itemId.toString()
                },
            )
            assertTrue(fixture.downloader.refreshDownloadTasks().none { it.itemId == itemId })

            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()
            assertEquals(null, fixture.dao.downloadIntent(itemId))
            assertTrue(fixture.downloader.refreshDownloadTasks().none { it.itemId == itemId })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `missing sidecar 完整重下显式接管旧失败且删除新源不复活 ghost`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("54545454-5454-4454-8454-545454545454")
            fixture.dao.seedDownloadIntent(itemId)

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                fixture.dao.downloadIntent(itemId)?.failureDetail,
            )

            fixture.prepareMusicOrigin(itemId, "missing retry")
            val receipt =
                fixture.downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "missing retry",
                            albumName = "Retry",
                            artist = "Provenance",
                            trackIndex = 2,
                            serverId = SERVER_ID,
                            userId = USER_ID.toString(),
                        )
                    )
                )
            assertEquals(1, receipt.acceptedRequests.size)
            assertTrue(fixture.dao.awaitInsertedSourceCount(1))
            fixture.downloader.runQueue()
            assertEquals(listOf(SOURCE_ID), fixture.dao.sourceIds())

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))
            assertTrue(fixture.dao.sourceById(SOURCE_ID) == null)
            assertTrue(
                "完整重下必须显式 supersede 旧失败：" +
                    "intent=${fixture.dao.downloadIntent(itemId)} " +
                    "sidecar=${fixture.downloader.mediaSidecar().allRecords()}",
                withTimeoutOrNull(5_000L) {
                    while (fixture.dao.downloadIntent(itemId) != null) delay(10L)
                    true
                } == true,
            )
            assertTrue(
                "删除新 source 后旧失败不得复活：" + fixture.downloader.refreshDownloadTasks(),
                fixture.downloader.refreshDownloadTasks().none { it.itemId == itemId },
            )

            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()
            assertEquals(null, fixture.dao.downloadIntent(itemId))
            assertTrue(fixture.downloader.refreshDownloadTasks().none { it.itemId == itemId })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `Room 保存失败后同一完整 batch 重试先接管旧代且删除新源不复活`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("56565656-5656-4656-8656-565656565656")
            fixture.dao.seedDownloadIntent(itemId)
            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            val legacyIntent = requireNotNull(fixture.dao.downloadIntent(itemId))
            assertEquals(
                DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                legacyIntent.failureDetail,
            )

            fixture.prepareMusicOrigin(itemId, "room failure retry")
            val request = fixture.completeMusicRequest(itemId, "room failure retry")
            fixture.dao.failDownloadIntentSaves(1)

            val firstFailure = runCatching {
                fixture.downloader.receiveMusicBatch(listOf(request))
            }
                .exceptionOrNull()
            assertNotNull("Room 保存失败不得被吞成已受理", firstFailure)
            assertEquals(1, fixture.dao.downloadIntentSaveAttempts)
            assertEquals(
                "普通 Room 事务失败必须精确清理本次 placeholder",
                0,
                fixture.downloader.mediaSidecar().allRecords().requireComplete().count {
                    it.itemId == itemId.toString() &&
                        it.serverId == SERVER_ID &&
                        it.userId == USER_ID.toString() &&
                        it.sourceId == null
                },
            )
            assertEquals(legacyIntent, fixture.dao.downloadIntent(itemId))

            val retryReceipt = fixture.downloader.receiveMusicBatch(listOf(request))
            assertEquals(
                "完整 pending sidecar 的 Room 失败不能被后续同 batch 永久误判为已受理",
                1,
                retryReceipt.acceptedRequests.size,
            )
            assertTrue(fixture.dao.awaitInsertedSourceCount(1))
            assertEquals(2, fixture.dao.downloadIntentSaveAttempts)

            fixture.downloader.runQueue()
            assertTrue(fixture.transfer.requestSnapshot().isNotEmpty())
            assertTrue(
                withTimeoutOrNull(5_000L) {
                    while (fixture.dao.downloadIntent(itemId) != null) delay(10L)
                    true
                } == true
            )

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))
            assertTrue(fixture.dao.sourceById(SOURCE_ID) == null)
            assertEquals(null, fixture.dao.downloadIntent(itemId))
            assertTrue(
                "删除新 source 后旧 legacy failure 不得复活：" +
                    fixture.downloader.mediaSidecar().allRecords(),
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.itemId == itemId.toString()
                },
            )

            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()
            assertTrue(
                "删除新 source 后 refresh / cold recovery 不得出现 ghost：" +
                    fixture.downloader.refreshDownloadTasks(),
                fixture.downloader.refreshDownloadTasks().none { it.itemId == itemId },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `Room 持续失败时冷启动不得绕过接管生成 source 且修复后可恢复`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("57575757-5757-4757-8757-575757575757")
            fixture.dao.seedDownloadIntent(itemId)
            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
            val legacyIntent = requireNotNull(fixture.dao.downloadIntent(itemId))

            fixture.prepareMusicOrigin(itemId, "sustained room failure")
            val request = fixture.completeMusicRequest(itemId, "sustained room failure")
            fixture.dao.failDownloadIntentSaves(20)
            val firstFailure = runCatching {
                fixture.downloader.receiveMusicBatch(listOf(request))
            }
                .exceptionOrNull()
            assertNotNull("首次 Room 保存失败必须显式返回", firstFailure)
            assertTrue(
                "普通 Room 保存失败不得留下无 Room 权威的 pending sidecar",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.itemId == itemId.toString() && it.sourceId == null
                },
            )

            fixture.newDownloader().recoverOnStartup()
            assertTrue(fixture.dao.sourceIds().isEmpty())
            assertTrue(fixture.transfer.requestSnapshot().isEmpty())
            assertEquals(legacyIntent, fixture.dao.downloadIntent(itemId))
            assertEquals(1, fixture.dao.downloadIntentSaveAttempts)

            val secondFailure = runCatching {
                fixture.downloader.receiveMusicBatch(listOf(request))
            }
                .exceptionOrNull()
            assertNotNull("持续 Room 保存失败必须持续显式返回", secondFailure)
            assertEquals(2, fixture.dao.downloadIntentSaveAttempts)
            assertTrue(fixture.dao.sourceIds().isEmpty())
            assertTrue(fixture.transfer.requestSnapshot().isEmpty())
            assertEquals(legacyIntent, fixture.dao.downloadIntent(itemId))

            fixture.dao.clearDownloadIntentSaveFailures()
            val retryReceipt = fixture.downloader.receiveMusicBatch(listOf(request))
            assertEquals(1, retryReceipt.acceptedRequests.size)
            assertEquals(3, fixture.dao.downloadIntentSaveAttempts)
            val recovered = fixture.newDownloader()
            recovered.recoverOnStartup()
            assertTrue(
                "Room 恢复后 cold recovery 必须完成接管与 source 解析",
                fixture.dao.awaitInsertedSourceCount(1),
            )
            recovered.runQueue()
            assertTrue(fixture.transfer.requestSnapshot().isNotEmpty())
            assertTrue(
                withTimeoutOrNull(5_000L) {
                    while (fixture.dao.downloadIntent(itemId) != null) delay(10L)
                    true
                } == true
            )

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))
            assertTrue(fixture.dao.sourceById(SOURCE_ID) == null)
            assertEquals(null, fixture.dao.downloadIntent(itemId))
            assertTrue(
                "接管并删除新 source 后旧失败不得复活：" + fixture.downloader.mediaSidecar().allRecords(),
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.itemId == itemId.toString()
                },
            )

            fixture.newDownloader().recoverOnStartup()
            fixture.downloader.runQueue()
            assertTrue(
                "接管后的 refresh / 再次 cold recovery 不得出现 ghost：" +
                    fixture.downloader.refreshDownloadTasks(),
                fixture.downloader.refreshDownloadTasks().none { it.itemId == itemId },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `失败 legacy intent 不得因同 item 的无关完整 source 静默消失`() = runBlocking {
        val fixture = Fixture()
        try {
            val failedItemId = UUID.fromString("51515151-5151-4151-8151-515151515151")
            val failedIntent =
                fixture.dao
                    .seedDownloadIntent(failedItemId, status = DownloadIntentStatus.FAILED.name)
                    .copy(
                        failureReason = DownloadFailureReason.CANCELLED.name,
                        failureDetail = DownloaderImpl.LEGACY_PROVENANCE_UNAVAILABLE,
                    )
            fixture.dao.putDownloadIntent(failedIntent)
            val unrelatedSourceId = "unrelated-complete"
            fixture.dao.putSource(fixture.seedPendingSource(failedItemId, unrelatedSourceId))
            assertTrue(
                fixture.putMusicSidecar(
                    itemId = failedItemId,
                    sourceId = unrelatedSourceId,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    displayName = "unrelated complete",
                )
            )

            val triggerItemId = UUID.fromString("52525252-5252-4252-8252-525252525252")
            val origin = JellyfinOrigin(SERVER_ID, USER_ID)
            fixture.repository.putOriginItem(
                triggerItemId,
                origin,
                fixture.movie().copy(id = triggerItemId, name = "trigger", kind = "AUDIO"),
            )
            fixture.repository.putOriginMediaSource(
                triggerItemId,
                origin,
                FindroidSource(
                    id = SOURCE_ID,
                    name = "trigger",
                    type = FindroidSourceType.REMOTE,
                    path = MEDIA_URL,
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = fixture.repository.mediaStreams,
                    container = "mp3",
                ),
            )
            fixture.seedAnchoredIntent(triggerItemId, "trigger")
            assertTrue(
                fixture.putMusicSidecar(
                    itemId = triggerItemId,
                    sourceId = null,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    displayName = "trigger",
                )
            )

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitInsertedSourceCount(1))
            withTimeoutOrNull(1_000L) {
                while (fixture.dao.downloadIntent(failedItemId) != null) delay(10L)
            }

            assertEquals(
                "无法证明新 source 与失败 intent 同代，失败状态必须保留",
                failedIntent,
                fixture.dao.downloadIntent(failedItemId),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy cleanup 与并发入队由同一 source operation 串行且不删新代`() = runBlocking {
        val fixture = Fixture()
        var gateReleased = false
        try {
            val movie = fixture.movie().copy(kind = "AUDIO")
            fixture.repository.putItem(movie)
            fixture.seedLegacyUnknownSource(ITEM_ID, SOURCE_ID, ITEM_NAME)
            fixture.dao.armSourceDeletionGate()

            fixture.downloader.recoverOnStartup()
            assertTrue(
                "legacy cleanup 必须进入正常删除的 generation 栅栏",
                withTimeoutOrNull(5_000L) {
                    fixture.dao.sourceDeleteStarted.await()
                    true
                } == true,
            )
            val deletionGeneration = fixture.dao.sourceDeletionGeneration
            val concurrentEnqueue =
                async(Dispatchers.IO) { fixture.downloader.downloadItem(movie, SOURCE_ID, 0) }
            assertEquals(
                "source operation 持锁期间不得插入新代",
                null,
                withTimeoutOrNull(300L) {
                    fixture.dao.concurrentSourceInserted.await()
                    true
                },
            )

            fixture.dao.releaseSourceDelete.complete(Unit)
            gateReleased = true
            val (handle, error) = withTimeout(5_000L) { concurrentEnqueue.await() }
            assertTrue(handle >= 0L)
            assertEquals(null, error)
            val inserted = requireNotNull(fixture.dao.lastInsertedSource(SOURCE_ID))
            assertTrue(inserted.taskGeneration > deletionGeneration)
            assertNotNull("旧 cleanup 删除了等待后的新代", fixture.dao.sourceById(SOURCE_ID))
            assertTrue(
                fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                    it.itemId == ITEM_ID.toString() &&
                        it.sourceId == SOURCE_ID &&
                        it.provenance() != null
                }
            )
        } finally {
            if (!gateReleased) fixture.dao.releaseSourceDelete.complete(Unit)
            fixture.transfer.release()
            fixture.close()
        }
    }

    @Test
    fun `legacy cleanup 取消并等待同 source 活动任务`() = runBlocking {
        val fixture = Fixture()
        var gateReleased = false
        try {
            val movie = fixture.movie().copy(kind = "AUDIO")
            fixture.repository.putItem(movie)
            val (handle, error) = fixture.downloader.downloadItem(movie, SOURCE_ID, 0)
            assertTrue(handle >= 0L)
            assertEquals(null, error)
            fixture.transfer.holdDownloads()
            val queueRun = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
            assertTrue(
                "source 必须已经启动后才能模拟 legacy cleanup：" +
                    "source=${fixture.dao.source} records=${fixture.downloader.mediaSidecar().allRecords()}",
                fixture.transfer.awaitStarted(1),
            )
            fixture.dao.source =
                requireNotNull(fixture.dao.source).copy(originServerId = null, originUserId = null)

            fixture
                .sidecarFile()
                .writeText(
                    DownloadMediaCodec.encodeLine(
                        DownloadMediaRecord(
                            itemId = ITEM_ID.toString(),
                            kind = DownloadMediaKind.MUSIC,
                            serverId = null,
                            userId = null,
                            sourceId = SOURCE_ID,
                            displayName = ITEM_NAME,
                        )
                    )
                )
            fixture.dao.seedDownloadIntent(ITEM_ID)
            fixture.dao.armSourceDeletionGate()
            fixture.downloader.recoverOnStartup()
            assertTrue(
                "活动 job 清理必须先进入删除栅栏",
                withTimeoutOrNull(5_000L) {
                    fixture.dao.sourceDeleteStarted.await()
                    true
                } == true,
            )

            fixture.dao.releaseSourceDelete.complete(Unit)
            gateReleased = true
            withTimeout(5_000L) { queueRun.join() }
            assertTrue("cleanup 必须取消并等待活动 job", fixture.awaitSourceRemoved(SOURCE_ID))
        } finally {
            if (!gateReleased) fixture.dao.releaseSourceDelete.complete(Unit)
            fixture.transfer.release()
            fixture.close()
        }
    }

    @Test
    fun `legacy cleanup sidecar 删除失败保留 DELETING 并可重启重试`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("53535353-5353-4353-8353-535353535353")
            fixture.seedLegacyUnknownSource(itemId, "retry-sidecar", "retry sidecar")
            val blockedReplace = fixture.blockSidecarReplace()

            val firstProcessing =
                async(start = CoroutineStart.UNDISPATCHED) {
                    fixture.downloader.queueChanges.first()
                }
            fixture.downloader.recoverOnStartup()
            withTimeout(5_000L) { firstProcessing.await() }
            assertTrue(
                "sidecar 未删除前必须保留可重试 tombstone",
                fixture.awaitSourceStatus("retry-sidecar", DownloadTaskStatus.DELETING),
            )
            assertEquals(
                DownloadTaskStatus.DELETING.name,
                fixture.dao.sourceById("retry-sidecar")?.taskStatus,
            )

            assertTrue(blockedReplace.deleteRecursively())
            fixture.newDownloader().recoverOnStartup()
            assertTrue(
                "sidecar 可写后必须续删 source",
                fixture.awaitSourceRemoved("retry-sidecar"),
            )
            assertTrue(
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.itemId == itemId.toString() && it.sourceId == "retry-sidecar"
                }
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `pending intent sidecar 删除失败保留 FAILED tombstone 且冷启动不复活`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = UUID.fromString("56565656-5656-4656-8656-565656565656")
            fixture.dao.seedDownloadIntent(itemId)
            assertTrue(
                fixture.putMusicSidecar(
                    itemId = itemId,
                    sourceId = null,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                )
            )
            val identity =
                fixture.downloader
                    .mediaSidecar()
                    .allRecords()
                    .requireComplete()
                    .single { it.itemId == itemId.toString() && it.sourceId == null }
                    .canonicalIdentity()
            val blockedReplace = fixture.blockSidecarReplace()

            assertFalse(
                "sidecar 删除未确认时不得报告 intent 删除成功",
                fixture.downloader.deleteTaskById(intentSourceId(itemId)),
            )
            val failed = requireNotNull(fixture.dao.downloadIntent(itemId))
            assertEquals(DownloadIntentStatus.FAILED.name, failed.status)
            assertEquals(DownloadFailureReason.CANCELLED.name, failed.failureReason)
            assertTrue(
                "sidecar 删除失败时必须保留目标 identity 供重试",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().any {
                    it.canonicalIdentity() == identity
                },
            )

            val restarted = fixture.newDownloader()
            restarted.recoverOnStartup()
            withTimeoutOrNull(500L) { while (fixture.dao.sourceIds().isEmpty()) delay(10L) }
            restarted.runQueue()
            assertTrue(
                "FAILED tombstone 存在时冷启动不得用 pending sidecar 重建 source",
                fixture.dao.sourceIds().isEmpty(),
            )
            assertTrue(
                "FAILED tombstone 存在时冷启动不得启动 transfer",
                fixture.transfer.requestSnapshot().isEmpty(),
            )
            assertNotNull(
                "sidecar 写失败后的删除 intent 必须保留到重试",
                fixture.dao.downloadIntent(itemId),
            )

            assertTrue(blockedReplace.deleteRecursively())
            assertTrue(
                "sidecar 可写后新 lifecycle 必须能完成删除",
                fixture.newDownloader().deleteTaskById(intentSourceId(itemId)),
            )
            assertEquals(null, fixture.dao.downloadIntent(itemId))
            assertTrue(
                "成功删除 intent 后目标 sidecar identity 必须不存在",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.canonicalIdentity() == identity
                },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy cleanup partial 文件删除失败保留 DELETING 并可冷启动重试`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seedLegacyUnknownSource(
                itemId = UUID.fromString("57575757-5757-4757-8757-575757575757"),
                sourceId = "retry-partial",
                displayName = "retry partial",
            )
            val source = requireNotNull(fixture.dao.sourceById("retry-partial"))
            val blockedPartial =
                File(fixture.downloads, "legacy-partial.orphan.download").apply {
                    assertTrue(mkdirs())
                    File(this, "holder").writeText("hold")
                }
            fixture.dao.putSource(source.copy(path = blockedPartial.path))

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.awaitIntentProcessingFailure())
            assertEquals(
                "partial 文件删除失败时必须保留 DELETING tombstone",
                DownloadTaskStatus.DELETING.name,
                fixture.dao.sourceById("retry-partial")?.taskStatus,
            )

            assertTrue(blockedPartial.deleteRecursively())
            fixture.newDownloader().recoverOnStartup()
            assertTrue(
                "partial 文件可删后冷启动必须续删 source",
                fixture.awaitSourceRemoved("retry-partial"),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `legacy cleanup js sidecar 删除失败保留 DELETING 并可冷启动重试`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.seedLegacyUnknownSource(
                itemId = UUID.fromString("58585858-5858-4858-8858-585858585858"),
                sourceId = "retry-js",
                displayName = "retry js",
            )
            val source = requireNotNull(fixture.dao.sourceById("retry-js"))
            val blockedSidecar =
                File("${source.path}.js").apply {
                    assertTrue(mkdirs())
                    File(this, "holder").writeText("hold")
                }

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.awaitIntentProcessingFailure())
            assertTrue(
                "旧 .js sidecar 删除失败时必须保留 DELETING tombstone",
                fixture.awaitSourceStatus("retry-js", DownloadTaskStatus.DELETING),
            )

            assertTrue(blockedSidecar.deleteRecursively())
            fixture.newDownloader().recoverOnStartup()
            assertTrue(
                "旧 .js sidecar 可删后冷启动必须续删 source",
                fixture.awaitSourceRemoved("retry-js"),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `不可读或损坏 sidecar 收口失败 intent 时恰好一次队列刷新`() = runBlocking {
        val itemIds =
            listOf(
                UUID.fromString("54545454-5454-4454-8454-545454545454"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
            )
        itemIds.forEachIndexed { index, itemId ->
            val fixture = Fixture()
            try {
                fixture.dao.seedDownloadIntent(itemId)
                if (index == 0) {
                    assertTrue(fixture.sidecarFile().mkdirs())
                } else {
                    fixture.sidecarFile().writeText("v2,broken")
                }

                val observed = mutableListOf<Unit>()
                val queueChanges =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        fixture.downloader.queueChanges.collect { observed += Unit }
                    }
                try {
                    fixture.downloader.recoverOnStartup()
                    assertTrue(fixture.dao.awaitIntentNotReceived(itemId))
                    withTimeout(5_000L) { while (observed.isEmpty()) delay(10L) }
                    withTimeoutOrNull(500L) { while (observed.size < 2) delay(10L) }
                } finally {
                    queueChanges.cancelAndJoin()
                }
                assertEquals(
                    "整个收口流程结束后 queue change 必须恰好一次：${observed.size}",
                    1,
                    observed.size,
                )
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `完整 provenance 的接收意图仍正常入队执行`() = runBlocking {
        val fixture = Fixture()
        try {
            val itemId = fixture.preparePendingMusicOrigin()
            fixture.resetCurrentOriginReads()

            fixture.downloader.recoverOnStartup()
            assertTrue(fixture.dao.awaitInsertedSourceCount(1))
            fixture.downloader.runQueue()

            assertEquals(listOf(SOURCE_ID), fixture.dao.sourceIds())
            assertTrue(fixture.transfer.requestSnapshot().isNotEmpty())
            fixture.assertCurrentOriginReadsZero()
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `完成恢复sidecar身份歧义时失败关闭`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = episode,
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )
            assertTrue(error == null)
            assertTrue(handle > 0L)
            fixture.persistEpisodeOrigin(
                sourceId = SOURCE_ID,
                serverId = "server-b",
                userId = OTHER_USER_ID,
            )
            fixture.imageScheduler.resetParentObservations()
            fixture.resetEffectCalls()
            fixture.preferences.currentServer = "server-c"
            fixture.preferences.currentServerReads = 0
            fixture.repository.userId = OTHER_USER_ID
            fixture.repository.userIdReads = 0

            fixture.downloader.runQueue()

            assertEquals("PENDING", fixture.dao.source?.taskStatus)
            assertEquals(DownloadFailureReason.SERVER_ERROR.name, fixture.dao.source?.failureReason)
            assertEquals(0, fixture.dao.showSnapshotWrites)
            assertEquals(0, fixture.dao.seasonSnapshotWrites)
            assertEquals(0, fixture.dao.episodeSnapshotWrites)
            assertEquals(0, fixture.dao.userDataWrites)
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)
            assertEquals(0, fixture.repository.segmentReads)
            assertEquals(0, fixture.repository.trickplayReads)
            assertEquals(0, fixture.repository.showReads)
            assertEquals(0, fixture.repository.seasonReads)
            assertTrue(fixture.imageScheduler.parentOwners.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `完成恢复完整sidecar使用持久namespace且仍调度父图`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val origin = JellyfinOrigin(SERVER_ID, USER_ID)
            fixture.repository.putOriginShow(SHOW_ID, origin, fixture.show())
            fixture.repository.putOriginSeason(SEASON_ID, origin, fixture.season())
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = episode,
                    sourceId = SOURCE_ID,
                    storageIndex = 0,
                )
            assertTrue(error == null)
            assertTrue(handle > 0L)
            fixture.imageScheduler.parentOwners.clear()
            fixture.imageScheduler.parentItemIds.clear()
            fixture.resetEffectCalls()
            fixture.preferences.currentServer = "server-b"
            fixture.preferences.currentServerReads = 0
            fixture.repository.userId = OTHER_USER_ID
            fixture.repository.userIdReads = 0

            fixture.downloader.runQueue()

            assertEquals(
                "source=${fixture.dao.source}, sidecar=${fixture.downloader.mediaSidecar().allRecords()}, logs=${fixture.logs}",
                setOf(
                    ImageDownloadParentOwner(
                        kind = ImageDownloadParentKind.SHOW,
                        itemId = SHOW_ID,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    ),
                    ImageDownloadParentOwner(
                        kind = ImageDownloadParentKind.SEASON,
                        itemId = SEASON_ID,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    ),
                ),
                fixture.imageScheduler.parentOwners.toSet(),
            )
            assertEquals(USER_ID, fixture.dao.userDataSnapshot?.userId)
            assertEquals(0, fixture.preferences.currentServerReads)
            assertEquals(0, fixture.repository.userIdReads)
            assertEquals(0, fixture.repository.segmentReads)
            assertEquals(0, fixture.repository.trickplayReads)
            assertEquals(0, fixture.repository.showReads)
            assertEquals(0, fixture.repository.seasonReads)
            assertEquals(
                listOf(SHOW_ID to JellyfinOrigin(SERVER_ID, USER_ID)),
                fixture.repository.originShowLookups,
            )
            assertEquals(
                listOf(SEASON_ID to JellyfinOrigin(SERVER_ID, USER_ID)),
                fixture.repository.originSeasonLookups,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `UniqueComplete 的 segments 与 Trickplay 只从冻结 origin 读取`() = runBlocking {
        val historicalOrigins =
            listOf(
                "server-a" to UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "server-b" to UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            )
        for ((serverId, userId) in historicalOrigins) {
            val fixture = Fixture()
            try {
                val origin = JellyfinOrigin(serverId, userId)
                val movie = fixture.movie()
                val segment =
                    FindroidSegment(
                        FindroidSegmentType.INTRO,
                        startTicks = if (serverId == "server-a") 101L else 201L,
                        endTicks = if (serverId == "server-a") 102L else 202L,
                    )
                val trickplayByte = if (serverId == "server-a") 11.toByte() else 22.toByte()
                fixture.repository.putOriginItem(movie.id, origin, movie)
                fixture.repository.putOriginMediaSource(
                    movie.id,
                    origin,
                    movie.sources.single(),
                )
                fixture.repository.putOriginSegments(movie.id, origin, listOf(segment))
                fixture.repository.putOriginTrickplay(movie.id, origin, byteArrayOf(trickplayByte))
                fixture.preferences.currentServer = serverId
                fixture.repository.userId = userId

                val (handle, error) =
                    fixture.downloader.downloadItem(
                        item = movie,
                        sourceId = SOURCE_ID,
                        storageIndex = 0,
                    )
                assertTrue(error == null)
                assertTrue(handle > 0L)
                fixture.resetEffectCalls()
                fixture.resetCurrentOriginReads()

                fixture.downloader.runQueue()

                assertEquals(
                    "source=${fixture.dao.source}, logs=${fixture.logs}",
                    "COMPLETED",
                    fixture.dao.source?.taskStatus,
                )
                assertEquals(
                    "origin=$origin, lookups=${fixture.repository.originSegmentLookups}",
                    listOf(segment),
                    fixture.dao.segments().map {
                        FindroidSegment(it.type, it.startTicks, it.endTicks)
                    },
                )
                assertEquals(
                    listOf(listOf(trickplayByte), listOf(trickplayByte)),
                    fixture.trickplayFiles().map { file -> file.readBytes().toList() },
                )
                assertEquals(0, fixture.repository.segmentReads)
                assertEquals(0, fixture.repository.trickplayReads)
                assertEquals(0, fixture.repository.showReads)
                assertEquals(0, fixture.repository.seasonReads)
                assertEquals(1, fixture.repository.originSegmentReads)
                assertEquals(2, fixture.repository.originTrickplayReads)
                assertEquals(listOf(movie.id to origin), fixture.repository.originSegmentLookups)
                assertEquals(
                    listOf(movie.id to origin, movie.id to origin),
                    fixture.repository.originTrickplayLookups,
                )
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `UniqueComplete 的剧集父快照只从冻结 origin 读取`() = runBlocking {
        val historicalOrigins =
            listOf(
                "server-a" to UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "server-b" to UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            )
        for ((serverId, userId) in historicalOrigins) {
            val fixture = Fixture()
            try {
                val origin = JellyfinOrigin(serverId, userId)
                val episode = fixture.episode()
                val show = fixture.show().copy(name = "Show $serverId")
                val season =
                    fixture
                        .season()
                        .copy(
                            name = "Season $serverId",
                            seriesName = "Show $serverId",
                        )
                fixture.repository.putOriginItem(episode.id, origin, episode)
                fixture.repository.putOriginMediaSource(
                    episode.id,
                    origin,
                    episode.sources.single(),
                )
                fixture.repository.putOriginShow(SHOW_ID, origin, show)
                fixture.repository.putOriginSeason(SEASON_ID, origin, season)
                fixture.preferences.currentServer = serverId
                fixture.repository.userId = userId

                val (handle, error) =
                    fixture.downloader.downloadItem(
                        item = episode,
                        sourceId = SOURCE_ID,
                        storageIndex = 0,
                    )
                assertTrue(error == null)
                assertTrue(handle > 0L)
                fixture.imageScheduler.parentOwners.clear()
                fixture.imageScheduler.parentItemIds.clear()
                fixture.resetEffectCalls()
                fixture.resetCurrentOriginReads()
                fixture.dao.showSnapshot = null
                fixture.dao.seasonSnapshot = null

                fixture.downloader.runQueue()

                assertEquals(
                    "source=${fixture.dao.source}, logs=${fixture.logs}",
                    "COMPLETED",
                    fixture.dao.source?.taskStatus,
                )
                assertEquals(show.name, fixture.dao.showSnapshot?.name)
                assertEquals(season.name, fixture.dao.seasonSnapshot?.name)
                assertEquals(
                    setOf(
                        ImageDownloadParentOwner(
                            kind = ImageDownloadParentKind.SHOW,
                            itemId = SHOW_ID,
                            serverId = serverId,
                            userId = userId.toString(),
                        ),
                        ImageDownloadParentOwner(
                            kind = ImageDownloadParentKind.SEASON,
                            itemId = SEASON_ID,
                            serverId = serverId,
                            userId = userId.toString(),
                        ),
                    ),
                    fixture.imageScheduler.parentOwners.toSet(),
                )
                assertEquals(0, fixture.repository.segmentReads)
                assertEquals(0, fixture.repository.trickplayReads)
                assertEquals(0, fixture.repository.showReads)
                assertEquals(0, fixture.repository.seasonReads)
                assertEquals(listOf(SHOW_ID to origin), fixture.repository.originShowLookups)
                assertEquals(listOf(SEASON_ID to origin), fixture.repository.originSeasonLookups)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun `UniqueComplete 的 origin 元数据缺失或读取失败时保持 pending`() = runBlocking {
        val fixture = Fixture()
        try {
            val origin = JellyfinOrigin(SERVER_ID, USER_ID)
            val movie = fixture.movie()
            fixture.repository.putOriginItem(movie.id, origin, movie)
            fixture.repository.failNextOriginSegments()

            fixture.downloader.downloadItem(
                item = movie,
                sourceId = SOURCE_ID,
                storageIndex = 0,
            )
            fixture.resetEffectCalls()
            fixture.resetCurrentOriginReads()

            fixture.downloader.runQueue()

            assertEquals("PENDING", fixture.dao.source?.taskStatus)
            assertEquals(DownloadFailureReason.SERVER_ERROR.name, fixture.dao.source?.failureReason)
            assertTrue(fixture.witness().isFile)
            assertTrue(fixture.dao.segments().isEmpty())
            assertEquals(0, fixture.dao.segmentWrites)
            assertEquals(0, fixture.repository.segmentReads)
            assertEquals(1, fixture.repository.originSegmentReads)
            assertEquals(0, fixture.repository.trickplayReads)
            assertEquals(0, fixture.repository.showReads)
            assertEquals(0, fixture.repository.seasonReads)
        } finally {
            fixture.close()
        }

        val missingTrickplayFixture = Fixture()
        try {
            val origin = JellyfinOrigin(SERVER_ID, USER_ID)
            val movie = missingTrickplayFixture.movie()
            missingTrickplayFixture.repository.putOriginItem(movie.id, origin, movie)
            missingTrickplayFixture.repository.removeOriginTrickplay(movie.id, origin)

            missingTrickplayFixture.downloader.downloadItem(
                item = movie,
                sourceId = SOURCE_ID,
                storageIndex = 0,
            )
            missingTrickplayFixture.resetEffectCalls()
            missingTrickplayFixture.resetCurrentOriginReads()

            missingTrickplayFixture.downloader.runQueue()

            assertEquals("PENDING", missingTrickplayFixture.dao.source?.taskStatus)
            assertEquals(
                DownloadFailureReason.SERVER_ERROR.name,
                missingTrickplayFixture.dao.source?.failureReason,
            )
            assertTrue(missingTrickplayFixture.witness().isFile)
            assertEquals(0, missingTrickplayFixture.dao.trickplayWrites)
            assertTrue(missingTrickplayFixture.dao.trickplayInfos().isEmpty())
            assertTrue(missingTrickplayFixture.trickplayFiles().isEmpty())
            assertEquals(0, missingTrickplayFixture.repository.trickplayReads)
            assertEquals(1, missingTrickplayFixture.repository.originTrickplayReads)
            assertEquals(0, missingTrickplayFixture.repository.showReads)
            assertEquals(0, missingTrickplayFixture.repository.seasonReads)
        } finally {
            missingTrickplayFixture.close()
        }

        val missingParentFixture = Fixture()
        try {
            val origin = JellyfinOrigin(SERVER_ID, USER_ID)
            val episode = missingParentFixture.episode()
            missingParentFixture.repository.putOriginItem(episode.id, origin, episode)
            missingParentFixture.repository.putOriginMediaSource(
                episode.id,
                origin,
                episode.sources.single(),
            )

            missingParentFixture.downloader.downloadItem(
                item = episode,
                sourceId = SOURCE_ID,
                storageIndex = 0,
            )
            missingParentFixture.resetEffectCalls()
            missingParentFixture.resetCurrentOriginReads()

            missingParentFixture.downloader.runQueue()

            assertEquals("PENDING", missingParentFixture.dao.source?.taskStatus)
            assertEquals(
                DownloadFailureReason.SERVER_ERROR.name,
                missingParentFixture.dao.source?.failureReason,
            )
            assertTrue(missingParentFixture.witness().isFile)
            assertEquals(0, missingParentFixture.dao.showSnapshotWrites)
            assertEquals(0, missingParentFixture.dao.seasonSnapshotWrites)
            assertEquals(0, missingParentFixture.repository.showReads)
            assertEquals(0, missingParentFixture.repository.seasonReads)
            assertEquals(1, missingParentFixture.repository.originShowReads)
        } finally {
            missingParentFixture.close()
        }
    }

    @Test
    fun `最后剧集删除与新兄弟入队具备父级串行边界`() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.dao.trackParentRows = true
            val deletingEpisode = fixture.episode()
            val deletingQueueSource = fixture.episodeSource()
            val (handle, error) =
                fixture.downloader.downloadItem(
                    item = deletingEpisode,
                    sourceId = deletingQueueSource.id,
                    storageIndex = 0,
                )
            assertTrue(error == null)
            assertTrue(handle > 0L)
            val deletingSource =
                requireNotNull(fixture.dao.sourceById(deletingQueueSource.id))
                    .toFindroidSource(emptyList())
            fixture.imageScheduler.resetParentObservations()
            fixture.dao.armSeasonDeleteGate()

            val deletion =
                async(Dispatchers.IO) {
                    fixture.downloader.deleteItem(deletingEpisode, deletingSource)
                }
            withTimeout(5_000) { fixture.dao.seasonDeleteStarted.await() }

            val siblingEpisode = fixture.episode(itemId = SIBLING_ITEM_ID)
            val siblingSource = fixture.episodeSource(id = SIBLING_SOURCE_ID)
            val siblingEnqueue =
                async(Dispatchers.IO) {
                    fixture.downloader.downloadItem(
                        item = siblingEpisode,
                        sourceId = siblingSource.id,
                        storageIndex = 0,
                    )
                }
            // 旧实现会在删除门内完成 sibling 调度；新串行实现则让 sibling 等待，
            // 因此这里的超时只负责在新实现上释放仍由删除持有的父级锁。
            withTimeoutOrNull(2_000) { fixture.imageScheduler.parentScheduled.await() }
            fixture.dao.releaseSeasonDelete.complete(Unit)
            deletion.await()
            val (siblingHandle, siblingError) = siblingEnqueue.await()

            assertTrue(siblingError == null)
            assertTrue(siblingHandle > 0L)
            assertTrue("新兄弟 episode 被删除", fixture.dao.hasEpisode(SEASON_ID, SIBLING_ITEM_ID))
            assertTrue("新兄弟所属 season 被删除", fixture.dao.hasSeason(SHOW_ID, SEASON_ID))
            assertEquals(
                "新兄弟入队后的父图任务必须保留",
                setOf(SHOW_ID, SEASON_ID),
                fixture.imageScheduler.activeParentItemIds.toSet(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除父图按item和source拒绝同source的错误身份`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val source = fixture.episodeSource()
            val sidecar = fixture.downloader.mediaSidecar()
            sidecar.put(
                DownloadMediaRecord(
                    itemId = ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    sourceId = source.id,
                )
            )
            sidecar.put(
                DownloadMediaRecord(
                    itemId = OTHER_ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    serverId = "server-b",
                    userId = OTHER_USER_ID.toString(),
                    sourceId = source.id,
                )
            )
            fixture.dao.remainingEpisodes = emptyList()
            fixture.dao.remainingSeasons = emptyList()
            fixture.seedCompletedSource(episode, source, persistOrigin = false)

            fixture.downloader.deleteItem(item = episode, source = source)

            assertEquals(
                setOf(SERVER_ID),
                fixture.imageScheduler.cancelledParentOwners.map { it.serverId }.toSet(),
            )
            assertEquals(
                setOf(USER_ID.toString()),
                fixture.imageScheduler.cancelledParentOwners.map { it.userId }.toSet(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `删除父图在完整身份歧义时失败关闭`() = runBlocking {
        val fixture = Fixture()
        try {
            val episode = fixture.episode()
            val source = fixture.episodeSource()
            val first =
                DownloadMediaRecord(
                    itemId = ITEM_ID.toString(),
                    kind = DownloadMediaKind.VIDEO,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    sourceId = source.id,
                    albumName = "first",
                )
            val second = first.copy(albumName = "second")
            fixture
                .sidecarFile()
                .writeText(
                    listOf(first, second)
                        .joinToString("\n", transform = DownloadMediaCodec::encodeLine)
                )
            fixture.dao.remainingEpisodes = emptyList()
            fixture.dao.remainingSeasons = emptyList()
            fixture.seedCompletedSource(episode, source, persistOrigin = false)

            val failure = runCatching {
                fixture.downloader.deleteItem(item = episode, source = source)
            }
                .exceptionOrNull()

            assertTrue(
                "歧义身份必须以删除失败暴露：${fixture.imageScheduler.cancelledParentOwners}",
                failure != null,
            )
            assertTrue(
                "歧义身份不得猜来源或清理父图",
                fixture.imageScheduler.cancelledParentOwners.isEmpty(),
            )
            assertNotNull(
                "歧义身份不得删除同名 source",
                fixture.dao.sourceById(source.id),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `生产删除完成来源清理仅witness锚定的finalizing且冷启不复活`() = runBlocking {
        val fixture = Fixture()
        try {
            val artifacts = seedDownloadArtifactSource(fixture)

            assertTrue(artifacts.finalFile.isFile)
            assertFalse("复现前提不得存在第二锚点 .download", artifacts.partialFile.exists())
            assertTrue(artifacts.witnessFile.isFile)
            assertNotNull(fixture.dao.sourceById(SOURCE_ID))
            assertTrue(
                fixture.downloader.downloadedMediaRecords().any {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                }
            )

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertFalse("最终媒体文件未清理", artifacts.finalFile.exists())
            assertFalse("orphan witness 未清理", artifacts.witnessFile.exists())
            assertFalse("断点文件未清理", artifacts.partialFile.exists())
            assertNull("source / task id 未清理", fixture.dao.sourceById(SOURCE_ID))
            assertTrue(
                "sidecar 未清理",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )
            assertTrue(
                "进行中 UI 快照未归零",
                fixture.downloader.activeItemIds().isEmpty(),
            )
            assertTrue(
                "已完成 UI 快照未归零：${fixture.downloader.downloadedMediaRecords()}",
                fixture.downloader.downloadedMediaRecords().none {
                    it.itemId == ITEM_ID.toString() && it.sourceId == SOURCE_ID
                },
            )
            assertTrue(
                "失败 / 任务 UI 快照未归零：${fixture.downloader.refreshDownloadTasks()}",
                fixture.downloader.refreshDownloadTasks().isEmpty(),
            )

            assertTrue("重复删除必须幂等", fixture.downloader.deleteTaskById(SOURCE_ID))
            val restarted = fixture.newDownloader()
            restarted.recoverOnStartup()
            restarted.runQueue()
            assertTrue(
                "冷启恢复复活了已删除任务：${restarted.refreshDownloadTasks()}",
                restarted.refreshDownloadTasks().isEmpty(),
            )
            assertFalse("冷启恢复重建了 orphan witness", artifacts.witnessFile.exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `生产删除真实artifact一与claim三代次分叉时清理精确witness`() = runBlocking {
        val fixture = Fixture()
        try {
            val artifacts =
                writeDownloadArtifacts(
                    fixture = fixture,
                    generation = 1L,
                    witnessGeneration = 3L,
                )
            val localSource =
                fixture
                    .movie()
                    .sources
                    .single()
                    .copy(
                        id = SOURCE_ID,
                        type = FindroidSourceType.LOCAL,
                        path = artifacts.finalFile.path,
                    )
            val item = fixture.movie().copy(id = ITEM_ID, sources = listOf(localSource))
            fixture.dao.seedMovie(ITEM_ID)
            fixture.seedCompletedSource(
                item = item,
                source = localSource,
                generation = 3L,
                taskStatus = DownloadTaskStatus.COMPLETED,
            )

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertFalse(
                "artifact generation 不得截断合法 witness claim：${artifacts.witnessFile}",
                artifacts.witnessFile.exists(),
            )
            assertFalse(artifacts.finalFile.exists())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `生产删除DELETING栅栏四重试时按前代claim三清理且保留隔离项`() = runBlocking {
        val fixture = Fixture()
        try {
            val current =
                writeDownloadArtifacts(
                    fixture = fixture,
                    generation = 1L,
                    witnessGeneration = 3L,
                )
            val otherSource =
                writeDownloadArtifacts(
                    fixture = fixture,
                    sourceId = "other-source",
                    generation = 1L,
                    witnessGeneration = 3L,
                )
            val otherItem =
                writeDownloadArtifacts(
                    fixture = fixture,
                    itemId = OTHER_ITEM_ID,
                    generation = 1L,
                    witnessGeneration = 3L,
                )
            val otherArtifact =
                writeDownloadArtifacts(
                    fixture = fixture,
                    generation = 2L,
                    witnessGeneration = 3L,
                )
            val localSource =
                fixture
                    .movie()
                    .sources
                    .single()
                    .copy(
                        id = SOURCE_ID,
                        type = FindroidSourceType.LOCAL,
                        path = current.finalFile.path,
                    )
            val item = fixture.movie().copy(id = ITEM_ID, sources = listOf(localSource))
            fixture.dao.seedMovie(ITEM_ID)
            fixture.seedCompletedSource(
                item = item,
                source = localSource,
                generation = 4L,
                taskStatus = DownloadTaskStatus.DELETING,
            )

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertFalse(current.finalFile.exists())
            assertFalse(current.witnessFile.exists())
            assertNull(fixture.dao.sourceById(SOURCE_ID))
            assertTrue(otherSource.finalFile.isFile)
            assertTrue(otherSource.witnessFile.isFile)
            assertTrue(otherItem.finalFile.isFile)
            assertTrue(otherItem.witnessFile.isFile)
            assertTrue(otherArtifact.finalFile.isFile)
            assertTrue(otherArtifact.witnessFile.isFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `生产删除精确文件名但witness claim不匹配时保留tombstone并报失败`() = runBlocking {
        val fixture = Fixture()
        try {
            val current =
                writeDownloadArtifacts(
                    fixture = fixture,
                    generation = 1L,
                    witnessGeneration = 2L,
                )
            val localSource =
                fixture
                    .movie()
                    .sources
                    .single()
                    .copy(
                        id = SOURCE_ID,
                        type = FindroidSourceType.LOCAL,
                        path = current.finalFile.path,
                    )
            val item = fixture.movie().copy(id = ITEM_ID, sources = listOf(localSource))
            fixture.dao.seedMovie(ITEM_ID)
            fixture.seedCompletedSource(
                item = item,
                source = localSource,
                generation = 4L,
                taskStatus = DownloadTaskStatus.DELETING,
            )

            assertFalse(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertEquals(
                DownloadTaskStatus.DELETING.name,
                fixture.dao.sourceById(SOURCE_ID)?.taskStatus,
            )
            assertTrue(current.finalFile.isFile)
            assertTrue(current.witnessFile.isFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `生产删除只清理当前item source generation的下载证据`() = runBlocking {
        val fixture = Fixture()
        try {
            val current = seedDownloadArtifactSource(fixture)
            val otherSource =
                writeDownloadArtifacts(
                    fixture = fixture,
                    sourceId = "other-source",
                    partialOnly = true,
                )
            val otherItem =
                writeDownloadArtifacts(
                    fixture = fixture,
                    itemId = OTHER_ITEM_ID,
                    partialOnly = true,
                )
            val otherGeneration =
                writeDownloadArtifacts(
                    fixture = fixture,
                    generation = 2L,
                    partialOnly = true,
                )

            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertFalse("当前 witness 未清理", current.witnessFile.exists())
            assertTrue("其他 source 的 .download 被误删", otherSource.partialFile.isFile)
            assertTrue("其他 source 的 witness 被误删", otherSource.witnessFile.isFile)
            assertTrue("其他 item 的 .download 被误删", otherItem.partialFile.isFile)
            assertTrue("其他 item 的 witness 被误删", otherItem.witnessFile.isFile)
            assertTrue("其他 generation 的 .download 被误删", otherGeneration.partialFile.isFile)
            assertTrue("其他 generation 的 witness 被误删", otherGeneration.witnessFile.isFile)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `生产删除活动download与witness后重复调用保持幂等`() = runBlocking {
        val fixture = Fixture()
        try {
            val artifacts = seedDownloadArtifactSource(fixture, partialOnly = true)

            assertTrue(artifacts.partialFile.isFile)
            assertTrue(artifacts.witnessFile.isFile)
            assertTrue(fixture.downloader.deleteTaskById(SOURCE_ID))

            assertFalse("活动 .download 未清理", artifacts.partialFile.exists())
            assertFalse("活动 witness 未清理", artifacts.witnessFile.exists())
            assertNull(fixture.dao.sourceById(SOURCE_ID))
            assertTrue("第二次删除必须幂等成功", fixture.downloader.deleteTaskById(SOURCE_ID))
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `完成态音乐经DTO丢失kind后公开删除成功清理全部副作用`() = runBlocking {
        val fixture = Fixture()
        try {
            val seed = seedCompletedMusicDelete(fixture)

            assertNull("Room DTO 重建的完成态音乐不应伪造 kind", seed.item.kind)

            fixture.downloader.deleteItem(item = seed.item, source = seed.source)

            assertFalse("完成文件未清理", seed.finalFile.exists())
            assertFalse("断点文件未清理", seed.partialFile.exists())
            assertFalse("完成 witness 未清理", seed.witnessFile.exists())
            assertFalse("媒体流文件未清理", seed.mediaStreamFile.exists())
            assertNull("source 未清理", fixture.dao.sourceById(seed.source.id))
            assertNull("完成媒体快照未清理", fixture.dao.proxy.getMovie(seed.item.id))
            assertTrue(
                "sidecar 未清理",
                fixture.downloader.mediaSidecar().allRecords().requireComplete().none { record ->
                    record.itemId == seed.item.id.toString() && record.sourceId == seed.source.id
                },
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `显式MOVIE或EPISODE与MUSIC sidecar冲突时拒绝且零副作用`() = runBlocking {
        val fixture = Fixture()
        try {
            val seed = seedCompletedMusicDelete(fixture)

            for (kind in listOf("MOVIE", "EPISODE")) {
                val conflictItem = seed.item.copy(kind = kind)
                val failure = runCatching {
                    fixture.downloader.deleteItem(item = conflictItem, source = seed.source)
                }
                    .exceptionOrNull()

                assertTrue("$kind kind 冲突必须 fail-closed", failure is IllegalStateException)
                assertTrue("$kind 冲突删除了完成文件", seed.finalFile.isFile)
                assertTrue("$kind 冲突删除了断点文件", seed.partialFile.isFile)
                assertTrue("$kind 冲突删除了 witness", seed.witnessFile.isFile)
                assertTrue("$kind 冲突删除了媒体流文件", seed.mediaStreamFile.isFile)
                assertNotNull("$kind 冲突删除了 source", fixture.dao.sourceById(seed.source.id))
                assertNotNull("$kind 冲突删除了完成快照", fixture.dao.proxy.getMovie(seed.item.id))
                assertTrue(
                    "$kind 冲突删除了 MUSIC sidecar",
                    fixture.downloader.mediaSidecar().allRecords().requireComplete().any { record ->
                        record.itemId == seed.item.id.toString() &&
                            record.sourceId == seed.source.id &&
                            record.kind == DownloadMediaKind.MUSIC
                    },
                )
            }
        } finally {
            fixture.close()
        }
    }

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

            fixture.resetEffectCalls()
            fixture.downloader.runQueue()

            assertEquals(
                "source=${fixture.dao.source}, logs=${fixture.logs}",
                "COMPLETED",
                fixture.dao.source?.taskStatus,
            )
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 1,
                    internalMediaStreamWrites = 1,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 1,
                    segmentWrites = 1,
                    trickplayReads = 2,
                    trickplayWrites = 1,
                    imageAttempts = 1,
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ordinaryRenameFailurePublishesOneQueueChangeAfterCasSuccess() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.downloader.downloadItem(
                item = fixture.movie(),
                sourceId = SOURCE_ID,
                storageIndex = 0,
            )
            val source = requireNotNull(fixture.dao.source)
            val finalFile = DownloadCompletionCommitRules.finalFile(File(source.path))
            finalFile.writeText("occupied final")

            val changes = CopyOnWriteArrayList<Unit>()
            val changesJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.downloader.queueChanges.collect { changes += Unit }
                }
            fixture.transfer.holdDownloads()
            val run = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
            try {
                assertTrue(fixture.transfer.awaitStarted(1))
                changes.clear()
                fixture.transfer.release()
                withTimeout(5_000L) { run.join() }

                assertEquals(
                    "rename 失败必须由普通 completeTask 落 FAILED/FILE_ERROR：${fixture.logs}",
                    DownloadTaskStatus.FAILED.name,
                    fixture.dao.sourceById(SOURCE_ID)?.taskStatus,
                )
                assertEquals(
                    DownloadFailureReason.FILE_ERROR.name,
                    fixture.dao.sourceById(SOURCE_ID)?.failureReason,
                )
                assertTrue(
                    "普通 completeTask CAS 成功后必须发一次 queue event",
                    withTimeoutOrNull(1_000L) {
                        while (changes.isEmpty()) delay(10L)
                        true
                    } == true,
                )
                assertEquals("queue event 必须恰好一次", 1, changes.size)
                assertEquals("occupied final", finalFile.readText())
            } finally {
                fixture.transfer.release()
                run.cancelAndJoin()
                changesJob.cancelAndJoin()
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ordinaryRenameFailureCasMissPublishesNoQueueChange() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.downloader.downloadItem(
                item = fixture.movie(),
                sourceId = SOURCE_ID,
                storageIndex = 0,
            )
            val source = requireNotNull(fixture.dao.source)
            val finalFile = DownloadCompletionCommitRules.finalFile(File(source.path))
            finalFile.writeText("occupied final")
            fixture.dao.sourceTaskStatusUpdateResult = 0

            val changes = CopyOnWriteArrayList<Unit>()
            val changesJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.downloader.queueChanges.collect { changes += Unit }
                }
            fixture.transfer.holdDownloads()
            val run = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
            try {
                assertTrue(fixture.transfer.awaitStarted(1))
                changes.clear()
                fixture.transfer.release()
                delay(300L)

                assertEquals(
                    DownloadTaskStatus.RUNNING.name,
                    fixture.dao.sourceById(SOURCE_ID)?.taskStatus,
                )
                assertEquals("CAS updated<=0 不得发 queue event", 0, changes.size)
            } finally {
                fixture.transfer.release()
                run.cancelAndJoin()
                changesJob.cancelAndJoin()
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun ordinaryCompletionSuccessPublishesNoRenameQueueChange() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.downloader.downloadItem(
                item = fixture.movie(),
                sourceId = SOURCE_ID,
                storageIndex = 0,
            )

            val changes = CopyOnWriteArrayList<Unit>()
            val changesJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.downloader.queueChanges.collect { changes += Unit }
                }
            fixture.transfer.holdDownloads()
            val run = launch(Dispatchers.Default) { fixture.downloader.runQueue() }
            try {
                assertTrue(fixture.transfer.awaitStarted(1))
                changes.clear()
                fixture.transfer.release()
                withTimeout(5_000L) { run.join() }

                assertEquals(
                    DownloadTaskStatus.COMPLETED.name,
                    fixture.dao.sourceById(SOURCE_ID)?.taskStatus,
                )
                delay(200L)
                assertEquals(
                    "非 rename 分支只保留既有完成事件，不得追加 rename 事件",
                    1,
                    changes.size,
                )
            } finally {
                fixture.transfer.release()
                run.cancelAndJoin()
                changesJob.cancelAndJoin()
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun interruptedCompletionRecoveryPublishesSingleQueueChange() = runBlocking {
        val fixture = Fixture()
        try {
            fixture.prepareInterruptedCompletion()
            fixture.downloader.recoverOnStartup()

            val changes = CopyOnWriteArrayList<Unit>()
            val changesJob =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    fixture.downloader.queueChanges.collect { changes += Unit }
                }
            try {
                fixture.downloader.runQueue()

                assertEquals(
                    DownloadTaskStatus.COMPLETED.name,
                    fixture.dao.sourceById(SOURCE_ID)?.taskStatus,
                )
                assertTrue(
                    "恢复完成必须保留既有 queue event",
                    withTimeoutOrNull(1_000L) {
                        while (changes.isEmpty()) delay(10L)
                        true
                    } == true,
                )
                delay(200L)
                assertEquals("恢复完成 queue event 不得重复", 1, changes.size)
            } finally {
                changesJob.cancelAndJoin()
            }
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
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 1,
                    internalMediaStreamWrites = 1,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 1,
                    segmentWrites = 1,
                    trickplayReads = 2,
                    trickplayWrites = 1,
                    imageAttempts = 1,
                )
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `旧 final 不阻塞旧路径上的新代次残片提交`() = runBlocking {
        val fixture = Fixture()
        try {
            val oldEvidence = fixture.prepareLegacyTargetConflict()
            fixture.persistVideoOrigin(SOURCE_ID)
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
                    originServerId = SERVER_ID,
                    originUserId = USER_ID.toString(),
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
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 1,
                    internalMediaStreamWrites = 1,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 1,
                    segmentWrites = 1,
                    trickplayReads = 2,
                    trickplayWrites = 1,
                    imageAttempts = 1,
                )
            )
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
            fixture.repository.failNextOriginSegments()

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
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 1,
                    internalMediaStreamWrites = 1,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 1,
                    segmentWrites = 0,
                    trickplayReads = 2,
                    trickplayWrites = 1,
                    imageAttempts = 1,
                ),
                segmentsPersisted = false,
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
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 2,
                    internalMediaStreamWrites = 2,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 2,
                    segmentWrites = 1,
                    trickplayReads = 4,
                    trickplayWrites = 2,
                    imageAttempts = 2,
                )
            )
            val completedArtifacts = fixture.artifacts()

            fixture.leaveCompletedWitness()
            fixture.downloader.recoverOnStartup()
            fixture.downloader.runQueue()

            assertEquals("COMPLETED", fixture.dao.source?.taskStatus)
            assertFalse(fixture.witness().exists())
            fixture.assertSixEffects(
                EffectCalls(
                    snapshotWrites = 3,
                    internalMediaStreamWrites = 3,
                    externalMediaStreamWrites = 1,
                    externalDownloads = 1,
                    segmentReads = 3,
                    segmentWrites = 2,
                    trickplayReads = 6,
                    trickplayWrites = 3,
                    imageAttempts = 3,
                )
            )
            assertEquals(
                "重复恢复不得增加快照 / 流 / 分段 / Trickplay / 图片 / 外置文件产物",
                completedArtifacts,
                fixture.artifacts(),
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `sidecar 坏行恢复必须保留 pending 且不启动 current fallback transfer`() = runBlocking {
        assertCorruptPendingSidecarFailsClosed { original -> "$original\nbroken-row" }
    }

    @Test
    fun `sidecar 坏 base64 恢复必须保留 pending 且不启动 current fallback transfer`() = runBlocking {
        assertCorruptPendingSidecarFailsClosed { original ->
            "$original\nv2\tMUSIC\t%%%\t\t\t1\t\t\t\t"
        }
    }

    @Test
    fun `sidecar torn 行恢复必须保留 pending 且不启动 current fallback transfer`() = runBlocking {
        assertCorruptPendingSidecarFailsClosed { original -> "$original\nv2\tMUSIC\t" }
    }

    @Test
    fun `sidecar 存储不可读恢复必须保留 pending 且不启动 current fallback transfer`() = runBlocking {
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
            val sidecar = fixture.sidecarFile()
            val backup = File(sidecar.parentFile, "${sidecar.name}.retained")
            Files.move(sidecar.toPath(), backup.toPath())
            assertTrue(sidecar.mkdirs())
            fixture.resetCurrentOriginReads()

            fixture.downloader.runQueue()

            assertTrue(
                "不可读 sidecar 不得启动 transfer：${fixture.transfer.requestSnapshot()}",
                fixture.transfer.requestSnapshot().isEmpty(),
            )
            fixture.assertCurrentOriginReadsZero()
            assertTrue("pending 原始文件必须保留供修复后重试", backup.isFile)

            assertTrue(sidecar.delete())
            Files.move(backup.toPath(), sidecar.toPath())
            fixture.dao.source = fixture.dao.source?.copy(taskStatus = "PENDING", nextRetryAt = 0L)
            fixture.downloader.runQueue()

            assertTrue("sidecar 修复后必须可重试", fixture.transfer.requestSnapshot().isNotEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `首次唯一完整 origin 后 sidecar 二次读取失败不得回退 current 且不得启动 transfer`() = runBlocking {
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
            val sidecar = fixture.sidecarFile()
            val backup = File(sidecar.parentFile, "${sidecar.name}.retained")
            fixture.dao.onGetMovie = {
                fixture.dao.onGetMovie = null
                Files.move(sidecar.toPath(), backup.toPath())
                sidecar.mkdirs()
            }
            fixture.resetCurrentOriginReads()

            fixture.downloader.runQueue()

            assertTrue(
                "二次读取失败不得启动 transfer：${fixture.transfer.requestSnapshot()}",
                fixture.transfer.requestSnapshot().isEmpty(),
            )
            fixture.assertCurrentOriginReadsZero()

            assertTrue(sidecar.delete())
            Files.move(backup.toPath(), sidecar.toPath())
            fixture.dao.source = fixture.dao.source?.copy(taskStatus = "PENDING", nextRetryAt = 0L)
            fixture.downloader.runQueue()

            assertTrue("sidecar 修复后必须可重试", fixture.transfer.requestSnapshot().isNotEmpty())
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertCorruptPendingSidecarFailsClosed(corrupt: (String) -> String) {
        val fixture = Fixture()
        try {
            val itemId = fixture.preparePendingMusicOrigin()
            val sidecar = fixture.sidecarFile()
            val original = sidecar.readText()
            val corrupted = corrupt(original)
            sidecar.writeText(corrupted)
            fixture.resetCurrentOriginReads()

            fixture.downloader.recoverOnStartup()
            delay(200)
            fixture.downloader.runQueue()

            assertTrue(
                "损坏 sidecar 不得插入 source：${fixture.dao.sourceIds()}",
                fixture.dao.sourceIds().isEmpty(),
            )
            assertTrue(
                "损坏 sidecar 不得启动 transfer：${fixture.transfer.requestSnapshot()}",
                fixture.transfer.requestSnapshot().isEmpty(),
            )
            fixture.assertCurrentOriginReadsZero()
            assertEquals("损坏 sidecar 不得被静默重写", corrupted, sidecar.readText())

            sidecar.writeText(original)
            fixture.retryIntent(itemId)
            fixture.downloader.recoverOnStartup()

            assertTrue("sidecar 修复后必须重试并插入 source", fixture.dao.awaitInsertedSourceCount(1))
            assertEquals(listOf(SOURCE_ID), fixture.dao.sourceIds())
        } finally {
            fixture.close()
        }
    }

    private data class EffectCalls(
        val snapshotWrites: Int,
        val internalMediaStreamWrites: Int,
        val externalMediaStreamWrites: Int,
        val externalDownloads: Int,
        val segmentReads: Int,
        val segmentWrites: Int,
        val trickplayReads: Int,
        val trickplayWrites: Int,
        val imageAttempts: Int,
    )

    internal data class EffectArtifacts(
        val movieIds: List<UUID>,
        val internalMediaStreams: List<Pair<UUID, String>>,
        val externalMediaStreams: List<Pair<UUID, String>>,
        val segments: List<FindroidSegmentDto>,
        val trickplay: List<FindroidTrickplayInfoDto>,
        val files: List<Triple<String, Int, String>>,
        val imageRequests: List<Pair<String, Long>>,
    )

    private fun Fixture.assertSixEffects(
        expected: EffectCalls,
        segmentsPersisted: Boolean = true,
    ) {
        val calls =
            EffectCalls(
                snapshotWrites = dao.snapshotWrites,
                internalMediaStreamWrites = dao.internalMediaStreamWrites,
                externalMediaStreamWrites = dao.externalMediaStreamWrites,
                externalDownloads = transfer.externalDownloads,
                segmentReads = repository.segmentReads + repository.originSegmentReads,
                segmentWrites = dao.segmentWrites,
                trickplayReads = repository.trickplayReads + repository.originTrickplayReads,
                trickplayWrites = dao.trickplayWrites,
                imageAttempts = imageScheduler.attempts,
            )
        assertEquals("六项副作用调用次数", expected, calls)

        val artifacts = artifacts()
        assertEquals("SNAPSHOT 产物", listOf(ITEM_ID), artifacts.movieIds)
        assertEquals(
            "MEDIA_STREAMS 产物",
            listOf(
                UUID.nameUUIDFromBytes("$SOURCE_ID|VIDEO|index=0".toByteArray()) to
                    checkNotNull(dao.source).path
            ),
            artifacts.internalMediaStreams,
        )
        assertEquals(
            "主媒体终稿内容",
            MEDIA_BYTES.toList(),
            File(checkNotNull(dao.source).path).readBytes().toList(),
        )
        val generation = checkNotNull(dao.source).taskGeneration
        val expectedExternalId =
            DownloadPostCommitRules.externalMediaStreamId(
                sourceId = SOURCE_ID,
                generation = generation,
                streamIndex = 1,
                ordinal = 0,
            )
        assertEquals(
            "EXTERNAL_STREAMS 产物",
            listOf(expectedExternalId to dao.externalStream()!!.path),
            artifacts.externalMediaStreams,
        )
        assertEquals(
            "外置流文件内容",
            SUBTITLE_BYTES.toList(),
            File(dao.externalStream()!!.path).readBytes().toList(),
        )
        assertEquals(
            "SEGMENTS 产物",
            if (segmentsPersisted) {
                listOf(
                    FindroidSegmentDto(
                        itemId = ITEM_ID,
                        type = FindroidSegmentType.INTRO,
                        startTicks = 0L,
                        endTicks = 10L,
                    )
                )
            } else {
                emptyList()
            },
            artifacts.segments,
        )
        assertEquals(
            "TRICKPLAY 产物",
            listOf(
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
            ),
            artifacts.trickplay,
        )
        val trickplayFiles = trickplayFiles()
        assertEquals("Trickplay 文件数", 2, trickplayFiles.size)
        assertEquals(listOf("0", "1"), trickplayFiles.map { it.name })
        trickplayFiles.forEach { file ->
            assertEquals(
                "Trickplay 文件内容：${file.name}",
                listOf(1.toByte()),
                file.readBytes().toList(),
            )
        }
        assertEquals(
            "IMAGES 产物",
            listOf(SOURCE_ID to generation),
            artifacts.imageRequests,
        )
    }

    @Test
    fun `SOURCE_CREATED with visible source projects source pending on the source task`() =
        runBlocking {
            val fixture = Fixture()
            val itemId = UUID.fromString("82828282-8282-4282-8282-828282828282")
            val sourceId = "visible-source-pending"
            try {
                fixture.prepareMusicOrigin(itemId, "Visible source pending", sourceId)
                fixture.dao.seedMovie(itemId)
                fixture.dao.putSource(
                    fixture
                        .seedPendingSource(itemId, sourceId)
                        .copy(taskStatus = DownloadTaskStatus.PENDING.name)
                )
                fixture.dao.putDownloadIntent(
                    DownloadIntentDto(
                        itemId = itemId,
                        name = "Visible source pending",
                        albumName = "Projection",
                        artist = "Intent",
                        trackIndex = 0,
                        status = DownloadIntentStatus.SOURCE_CREATED.name,
                        createdAt = 1L,
                        updatedAt = 2L,
                        taskGeneration = 7L,
                    )
                )
                assertTrue(
                    fixture.putMusicSidecar(
                        itemId = itemId,
                        sourceId = sourceId,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                        displayName = "Visible source pending",
                    )
                )

                val task = fixture.downloader.refreshDownloadTasks().single { it.itemId == itemId }

                assertEquals(sourceId, task.sourceId)
                assertEquals(DownloadIntentPhase.SOURCE_PENDING, task.intentPhase)
                assertEquals(
                    DownloadTaskPhase.SOURCE_PENDING,
                    DownloadControlPlaneRules.phase(
                        status = task.status,
                        downloadedBytes = task.downloadedBytes,
                        totalBytes = task.totalBytes,
                        failureReason = task.failureReason,
                        nextRetryAt = task.nextRetryAt,
                        nowMs = 0L,
                        intentPhase = task.intentPhase,
                    ),
                )
            } finally {
                fixture.close()
            }
        }

    internal class Fixture(
        private val resolutionDispatcher: CoroutineDispatcher = Dispatchers.IO,
        private val resolutionDeadlineScheduler: DownloadIntentDeadlineScheduler =
            CoroutineDownloadIntentDeadlineScheduler,
        private val resolutionTimeoutMs: Long = 15_000L,
    ) {
        private val root = Files.createTempDirectory("downloader-recovery").toFile()
        private val context = TestContext(root)
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
        val preferences = FakePreferences()
        val appPreferences = AppPreferences(preferences)
        val downloader = newDownloader()

        init {
            Timber.plant(tree)
            transfer.sourceIdForPath = dao::sourceIdByPath
        }

        fun newDownloader(): DownloaderImpl =
            DownloaderImpl.createForTest(
                context = context,
                database = dao.proxy,
                jellyfinRepository = repository.proxy,
                readerRepository = unusedProxy(ReaderRepository::class.java),
                appPreferences = appPreferences,
                imageCacheStore = testImageCacheStore(context),
                workManager =
                    allocateClass(Class.forName("androidx.work.impl.WorkManagerImpl"))
                        as WorkManager,
                httpClient = allocate(),
                notifications = FakeNotificationGateway(),
                registerNetworkCallback = {},
                externalStorageState = { "mounted" },
                availableBytes = { Long.MAX_VALUE },
                workGateway = workGateway,
                downloadTransfer = transfer,
                imageFinalizationScheduler = imageScheduler,
                intentResolutionDispatcher = resolutionDispatcher,
                intentResolutionDeadlineScheduler = resolutionDeadlineScheduler,
                intentResolutionTimeoutMs = resolutionTimeoutMs,
            )

        suspend fun seedConcurrentMovies(count: Int): List<SeededSource> =
            (0 until count).map { index ->
                val itemId = UUID(1_000L, index.toLong() + 1L)
                val sourceId = "concurrency-$index"
                val source = sourceDto(itemId = itemId, sourceId = sourceId, index = index)
                val repositorySource =
                    FindroidSource(
                        id = sourceId,
                        name = "Concurrent $index",
                        type = FindroidSourceType.REMOTE,
                        path = "https://media.test/$sourceId",
                        size = MEDIA_BYTES.size.toLong(),
                        mediaStreams = repository.mediaStreams,
                        container = "mp4",
                    )
                dao.putSource(source)
                dao.seedMovie(itemId)
                repository.putMediaSource(itemId, repositorySource)
                persistVideoOrigin(
                    sourceId = sourceId,
                    itemId = itemId,
                )
                SeededSource(itemId = itemId, source = source)
            }

        fun seedAdditionalSource(
            index: Int,
            itemId: UUID = ITEM_ID,
            sourceId: String = "source-second",
        ): SeededSource {
            val source = sourceDto(itemId = itemId, sourceId = sourceId, index = index)
            dao.putSource(source)
            return SeededSource(itemId = itemId, source = source)
        }

        fun markerFile(relativePath: String): File =
            File(root, relativePath).apply {
                parentFile?.mkdirs()
                writeText("shared")
            }

        fun blockSidecarReplace(): File =
            File(root, "download_media.tsv.tmp").apply {
                assertTrue(mkdirs())
                File(this, "holder").writeText("block replace")
            }

        fun sidecarFile(): File = File(root, "download_media.tsv")

        fun putMusicSidecar(
            itemId: UUID,
            sourceId: String?,
            serverId: String? = null,
            userId: String? = null,
            displayName: String? = null,
            albumName: String? = null,
            artist: String? = null,
            placement: DownloadPlacement = DownloadPlacement.LEGACY,
            kind: DownloadMediaKind = DownloadMediaKind.MUSIC,
        ): Boolean =
            downloader
                .mediaSidecar()
                .put(
                    DownloadMediaRecord(
                        itemId = itemId.toString(),
                        kind = kind,
                        albumName = albumName,
                        artist = artist,
                        serverId = serverId,
                        userId = userId,
                        sourceId = sourceId,
                        displayName = displayName,
                        placement = placement,
                    )
                )

        fun putMusicRecord(record: DownloadMediaRecord): Boolean =
            downloader.mediaSidecar().put(record)

        fun seedLegacyUnknownSource(
            itemId: UUID,
            sourceId: String,
            displayName: String,
        ): FindroidSourceDto {
            dao.seedDownloadIntent(itemId)
            return seedPendingSource(itemId, sourceId)
                .also { source ->
                    assertTrue(putMusicSidecar(itemId, sourceId, displayName = displayName))
                    assertTrue(source.taskGeneration > 0L)
                }
                .copy(originServerId = null, originUserId = null)
                .also(dao::putSource)
        }

        suspend fun awaitSourceStatus(sourceId: String, status: DownloadTaskStatus): Boolean =
            withTimeoutOrNull(5_000L) {
                while (dao.sourceById(sourceId)?.taskStatus != status.name) delay(10L)
                true
            } ?: false

        suspend fun awaitSourceRemoved(sourceId: String): Boolean =
            withTimeoutOrNull(5_000L) {
                while (dao.sourceById(sourceId) != null) delay(10L)
                true
            } ?: false

        suspend fun awaitIntentProcessingFailure(): Boolean =
            withTimeoutOrNull(5_000L) {
                while (logs.none { it.startsWith("处理已受理下载意图失败") }) delay(10L)
                true
            } ?: false

        suspend fun awaitIntentStatus(itemId: UUID, status: String): Boolean =
            withTimeoutOrNull(5_000L) {
                while (dao.downloadIntent(itemId)?.status != status) delay(10L)
                true
            } ?: false

        suspend fun retryIntent(itemId: UUID) {
            val intent = checkNotNull(dao.downloadIntent(itemId))
            val (handle, error) =
                downloader.retryTask(
                    DownloadTask(
                        itemId = itemId,
                        sourceId = intentSourceId(itemId),
                        name = intent.name,
                        path = "",
                        downloadId = null,
                        status = DownloadTaskStatus.PENDING,
                        failureReason = null,
                        downloadedBytes = 0L,
                        totalBytes = 0L,
                        updatedAt = intent.updatedAt,
                        mediaKind = DownloadMediaKind.MUSIC,
                        taskGeneration = intent.taskGeneration,
                    )
                )
            assertTrue("intent retry 应返回稳定句柄", handle >= 0L)
            assertEquals(null, error)
        }

        suspend fun cancelIntent(itemId: UUID) {
            assertTrue(downloader.cancelTaskById(intentSourceId(itemId)))
        }

        fun seedPendingSource(itemId: UUID, sourceId: String): FindroidSourceDto =
            sourceDto(itemId = itemId, sourceId = sourceId, index = 0).also(dao::putSource)

        fun seedAnchoredSource(
            itemId: UUID,
            sourceId: String,
            serverId: String = SERVER_ID,
            userId: UUID = USER_ID,
            generation: Long = 1L,
            status: DownloadTaskStatus = DownloadTaskStatus.PENDING,
        ): FindroidSourceDto =
            seedPendingSource(itemId, sourceId)
                .copy(
                    originServerId = serverId,
                    originUserId = userId.toString(),
                    taskGeneration = generation,
                    taskStatus = status.name,
                )
                .also(dao::putSource)

        fun seedAnchoredIntent(
            itemId: UUID,
            name: String,
            serverId: String = SERVER_ID,
            userId: UUID = USER_ID,
            generation: Long = 4L,
            status: DownloadIntentStatus = DownloadIntentStatus.RECEIVED,
            albumName: String? = "Anchor Album",
            artist: String? = "Anchor Artist",
        ): DownloadIntentDto =
            DownloadIntentDto(
                    itemId = itemId,
                    name = name,
                    albumName = albumName,
                    artist = artist,
                    trackIndex = 0,
                    status = status.name,
                    createdAt = 1L,
                    updatedAt = 1L,
                    taskGeneration = generation,
                    originServerId = serverId,
                    originUserId = userId.toString(),
                )
                .also(dao::putDownloadIntent)

        fun seedReceivedIntentWithPendingSidecar(
            itemId: UUID,
            sourceId: String,
            name: String,
        ): DownloadIntentDto {
            val intent =
                DownloadIntentDto(
                    itemId = itemId,
                    name = name,
                    albumName = "Recovery",
                    artist = "Locks",
                    trackIndex = 0,
                    status = DownloadIntentStatus.RECEIVED.name,
                    createdAt = 1L,
                    updatedAt = 1L,
                    taskGeneration = 4L,
                    originServerId = SERVER_ID,
                    originUserId = USER_ID.toString(),
                )
            dao.putDownloadIntent(intent)
            assertTrue(
                putMusicSidecar(
                    itemId = itemId,
                    sourceId = null,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    displayName = name,
                )
            )
            return intent
        }

        fun seedResolvingIntentWithResolvedSidecar(
            itemId: UUID,
            sourceId: String,
            name: String,
            sourceExists: Boolean,
        ): DownloadIntentDto {
            val intent =
                DownloadIntentDto(
                    itemId = itemId,
                    name = name,
                    albumName = "Recovery",
                    artist = "Crash",
                    trackIndex = 0,
                    status = DownloadIntentStatus.RESOLVING.name,
                    createdAt = 1L,
                    updatedAt = 1L,
                    taskGeneration = 4L,
                    originServerId = SERVER_ID,
                    originUserId = USER_ID.toString(),
                )
            dao.putDownloadIntent(intent)
            assertTrue(
                putMusicSidecar(
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    displayName = name,
                )
            )
            if (sourceExists) {
                seedPendingSource(itemId, sourceId)
            }
            return intent
        }

        fun seedSourceCreatedIntentWithSource(
            itemId: UUID,
            sourceId: String,
            name: String,
        ): DownloadIntentDto {
            dao.putSource(seedPendingSource(itemId, sourceId))
            assertTrue(
                putMusicSidecar(
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    displayName = name,
                )
            )
            return DownloadIntentDto(
                    itemId = itemId,
                    name = name,
                    albumName = "Snapshot",
                    artist = "Intent",
                    trackIndex = 0,
                    status = DownloadIntentStatus.SOURCE_CREATED.name,
                    createdAt = 1L,
                    updatedAt = 1L,
                    taskGeneration = 4L,
                    originServerId = SERVER_ID,
                    originUserId = USER_ID.toString(),
                )
                .also(dao::putDownloadIntent)
        }

        fun armIntentSourceReadGate(sourceId: String) {
            dao.armIntentSourceReadGate(sourceId)
        }

        fun observeIntentSourceRead(sourceId: String) {
            dao.observeIntentSourceRead(sourceId)
        }

        suspend fun awaitIntentSourceRead() {
            withTimeout(5_000L) { dao.awaitIntentSourceRead() }
        }

        fun releaseIntentSourceRead() {
            dao.releaseIntentSourceRead()
        }

        fun armIntentDeletionGate() {
            dao.armIntentDeletionGate()
        }

        suspend fun awaitIntentDeletion() {
            withTimeout(5_000L) { dao.awaitIntentDeletion() }
        }

        fun releaseIntentDeletion() {
            dao.releaseIntentDeletion()
        }

        fun armIntentSourceCreatedCasGate() {
            dao.armIntentSourceCreatedCasGate()
        }

        suspend fun awaitIntentSourceCreatedCas() {
            withTimeout(5_000L) { dao.awaitIntentSourceCreatedCas() }
        }

        fun releaseIntentSourceCreatedCas() {
            dao.releaseIntentSourceCreatedCas()
        }

        fun armIntentSourceSnapshotGate(
            itemId: UUID,
            replacementSourceId: String,
        ) {
            dao.armIntentSourceSnapshotGate(itemId, replacementSourceId)
        }

        suspend fun awaitIntentSourceSnapshot() {
            withTimeout(5_000L) { dao.awaitIntentSourceSnapshot() }
        }

        fun releaseIntentSourceSnapshot() {
            dao.releaseIntentSourceSnapshot()
        }

        fun intent(itemId: UUID): DownloadIntentDto? = dao.downloadIntent(itemId)

        fun sourceInsertCount(sourceId: String): Int = dao.sourceInsertCount(sourceId)

        fun sourceIdsSnapshot(): List<String> = dao.sourceIds()

        fun lastIntentDeletionGeneration(): Long? = dao.lastIntentDeletionGeneration

        fun prepareMusicOrigin(
            itemId: UUID,
            name: String,
            sourceId: String = SOURCE_ID,
        ) {
            val origin = JellyfinOrigin(SERVER_ID, USER_ID)
            repository.putOriginItem(
                itemId,
                origin,
                movie().copy(id = itemId, name = name, kind = "AUDIO"),
            )
            repository.putOriginMediaSource(
                itemId,
                origin,
                FindroidSource(
                    id = sourceId,
                    name = name,
                    type = FindroidSourceType.REMOTE,
                    path = MEDIA_URL,
                    size = MEDIA_BYTES.size.toLong(),
                    mediaStreams = repository.mediaStreams,
                    container = "mp3",
                ),
            )
        }

        fun completeMusicRequest(
            itemId: UUID,
            name: String,
        ): DownloadMusicIntentRequest =
            DownloadMusicIntentRequest(
                itemId = itemId,
                name = name,
                albumName = "Retry",
                artist = "Provenance",
                trackIndex = 1,
                serverId = SERVER_ID,
                userId = USER_ID.toString(),
            )

        suspend fun preparePendingMusicOrigin(): UUID {
            val itemId = UUID.fromString("abababab-abab-4bab-8bab-abababababab")
            prepareMusicOrigin(itemId, "round-four pending")
            val receipt =
                downloader.receiveMusicBatch(
                    listOf(
                        DownloadMusicIntentRequest(
                            itemId = itemId,
                            name = "round-four pending",
                            albumName = "Round 4",
                            artist = "Fail closed",
                            trackIndex = 4,
                            serverId = SERVER_ID,
                            userId = USER_ID.toString(),
                        )
                    )
                )
            assertEquals(1, receipt.acceptedRequests.size)
            return itemId
        }

        fun resetCurrentOriginReads() {
            preferences.currentServer = "server-c"
            preferences.currentServerReads = 0
            repository.userId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
            repository.userIdReads = 0
            repository.baseUrlReads = 0
            repository.accessTokenReads = 0
        }

        fun assertCurrentOriginReadsZero() {
            assertEquals("不得读取 current server", 0, preferences.currentServerReads)
            assertEquals("不得读取 current user", 0, repository.userIdReads)
            assertEquals("不得读取 current baseUrl", 0, repository.baseUrlReads)
            assertEquals("不得读取 current token", 0, repository.accessTokenReads)
        }

        private fun sourceDto(
            itemId: UUID,
            sourceId: String,
            index: Int,
        ): FindroidSourceDto {
            val generation = 1L
            val partial =
                DownloadCompletionCommitRules.downloadTargetFiles(
                        directory = downloads,
                        itemId = itemId.toString(),
                        sourceId = sourceId,
                        generation = generation,
                    )
                    .first
            return FindroidSourceDto(
                id = sourceId,
                itemId = itemId,
                name = "Concurrent $index",
                type = FindroidSourceType.LOCAL,
                path = partial.path,
                taskStatus = DownloadTaskStatus.PENDING.name,
                updatedAt = index.toLong() + 1L,
                downloadedBytes = 0L,
                totalBytes = MEDIA_BYTES.size.toLong(),
                engineVersion = DownloaderImpl.ENGINE_VERSION,
                container = "mp4",
                taskGeneration = generation,
                originServerId = SERVER_ID,
                originUserId = USER_ID.toString(),
            )
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

        suspend fun seedDeletingSource(): FindroidSourceDto {
            downloader.downloadItem(movie(), SOURCE_ID, 0)
            val blockedPath = File(requireNotNull(dao.source).path)
            blockedPath.delete()
            assertTrue(blockedPath.mkdirs())
            File(blockedPath, "blocked").writeText("hold")
            assertNotNull(runCatching { downloader.deleteTaskById(SOURCE_ID) }.exceptionOrNull())
            val deleting = requireNotNull(dao.source)
            assertEquals(DownloadTaskStatus.DELETING.name, deleting.taskStatus)
            assertTrue(File(blockedPath, "blocked").delete())
            assertTrue(blockedPath.delete())
            return deleting
        }

        fun episode(
            itemId: UUID = ITEM_ID,
            sourceId: String = SOURCE_ID,
        ): FindroidEpisode =
            FindroidEpisode(
                id = itemId,
                name = ITEM_NAME,
                originalTitle = null,
                overview = "",
                indexNumber = 1,
                indexNumberEnd = null,
                parentIndexNumber = 1,
                sources =
                    listOf(
                        FindroidSource(
                            id = sourceId,
                            name = SOURCE_NAME,
                            type = FindroidSourceType.REMOTE,
                            path = MEDIA_URL,
                            size = MEDIA_BYTES.size.toLong(),
                            mediaStreams = repository.mediaStreams,
                            container = "mp4",
                        )
                    ),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = true,
                runtimeTicks = 0L,
                playbackPositionTicks = 0L,
                premiereDate = null,
                seriesId = SHOW_ID,
                seriesName = SHOW_NAME,
                seasonId = SEASON_ID,
                seasonName = SEASON_NAME,
                communityRating = null,
                people = emptyList(),
                images = FindroidImages(),
                chapters = emptyList(),
                trickplayInfo = emptyMap(),
            )

        fun episodeSource(id: String = "episode-local-source"): FindroidSource =
            FindroidSource(
                id = id,
                name = "Episode",
                type = FindroidSourceType.LOCAL,
                path = File(root, id).path,
                size = 1L,
                mediaStreams = emptyList(),
                container = "mp4",
            )

        fun persistEpisodeOrigin(
            sourceId: String,
            itemId: UUID = ITEM_ID,
            serverId: String = SERVER_ID,
            userId: UUID = USER_ID,
        ) {
            persistVideoOrigin(sourceId, itemId, serverId, userId)
        }

        fun seedCompletedSource(
            item: FindroidItem,
            source: FindroidSource,
            persistOrigin: Boolean = true,
            generation: Long = 1L,
            taskStatus: DownloadTaskStatus = DownloadTaskStatus.COMPLETED,
        ) {
            dao.putSource(
                FindroidSourceDto(
                    id = source.id,
                    itemId = item.id,
                    name = source.name,
                    type = FindroidSourceType.LOCAL,
                    path = source.path,
                    taskStatus = taskStatus.name,
                    updatedAt = 1L,
                    engineVersion = DownloaderImpl.ENGINE_VERSION,
                    container = source.container,
                    taskGeneration = generation,
                    originServerId = SERVER_ID,
                    originUserId = USER_ID.toString(),
                )
            )
            if (persistOrigin) {
                persistEpisodeOrigin(sourceId = source.id, itemId = item.id)
            }
        }

        fun persistVideoOrigin(
            sourceId: String,
            itemId: UUID = ITEM_ID,
            serverId: String = SERVER_ID,
            userId: UUID = USER_ID,
        ) {
            val origin = JellyfinOrigin(serverId, userId)
            repository.putOriginMediaSource(
                itemId,
                origin,
                repository.mediaSourcesByItemId[itemId]?.takeIf { it.id == sourceId }
                    ?: FindroidSource(
                        id = sourceId,
                        name = sourceId,
                        type = FindroidSourceType.REMOTE,
                        path = "https://$serverId.test/$sourceId",
                        size = MEDIA_BYTES.size.toLong(),
                        mediaStreams = repository.mediaStreams,
                        container = "mp4",
                    ),
            )
            downloader
                .mediaSidecar()
                .put(
                    DownloadMediaRecord(
                        itemId = itemId.toString(),
                        kind = DownloadMediaKind.VIDEO,
                        serverId = serverId,
                        userId = userId.toString(),
                        sourceId = sourceId,
                    )
                )
        }

        fun show(): FindroidShow =
            FindroidShow(
                id = SHOW_ID,
                name = SHOW_NAME,
                originalTitle = null,
                overview = "",
                sources = emptyList(),
                seasons = emptyList(),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = false,
                unplayedItemCount = null,
                genres = emptyList(),
                people = emptyList(),
                runtimeTicks = 0L,
                communityRating = null,
                officialRating = null,
                status = "Continuing",
                productionYear = null,
                endDate = null,
                trailer = null,
                images = FindroidImages(),
            )

        fun season(): FindroidSeason =
            FindroidSeason(
                id = SEASON_ID,
                name = SEASON_NAME,
                seriesId = SHOW_ID,
                seriesName = SHOW_NAME,
                originalTitle = null,
                overview = "",
                sources = emptyList(),
                indexNumber = 1,
                episodes = emptyList(),
                played = false,
                favorite = false,
                canPlay = true,
                canDownload = false,
                unplayedItemCount = null,
                images = FindroidImages(),
            )

        suspend fun prepareInterruptedCompletion() {
            persistVideoOrigin(SOURCE_ID)
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
                    originServerId = SERVER_ID,
                    originUserId = USER_ID.toString(),
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

        fun downloadTarget(): Pair<File, File> =
            DownloadCompletionCommitRules.downloadTargetFiles(
                directory = downloads,
                itemId = ITEM_ID.toString(),
                sourceId = SOURCE_ID,
                generation = GENERATION,
            )

        fun leaveCompletedWitness() {
            val target = downloadTarget()
            dao.source =
                checkNotNull(dao.source)
                    .copy(
                        path = target.second.path,
                        taskStatus = DownloadTaskStatus.COMPLETED.name,
                        failureReason = null,
                        nextRetryAt = 0L,
                    )
            witness()
                .writeText(
                    listOf(
                            SOURCE_ID,
                            GENERATION.toString(),
                            target.first.canonicalPath,
                            target.second.canonicalPath,
                        )
                        .joinToString("\n")
                )
        }

        fun resetEffectCalls() {
            dao.resetEffectCalls()
            repository.resetEffectCalls()
            transfer.externalDownloads = 0
            imageScheduler.attempts = 0
        }

        fun trickplayFiles(): List<File> =
            File(root, "trickplay/$ITEM_ID/$SOURCE_ID").listFiles().orEmpty().sortedBy { it.name }

        fun artifacts(): EffectArtifacts =
            EffectArtifacts(
                movieIds = dao.snapshotIds().sortedBy(UUID::toString),
                internalMediaStreams =
                    dao.internalMediaStreams()
                        .map { stream -> stream.id to stream.path }
                        .sortedBy { it.first.toString() },
                externalMediaStreams =
                    dao.externalMediaStreams()
                        .map { stream -> stream.id to stream.path }
                        .sortedBy { it.first.toString() },
                segments = dao.segments().sortedWith(compareBy({ it.itemId }, { it.type })),
                trickplay = dao.trickplayInfos().sortedBy { it.sourceId },
                files = buildList {
                        dao.externalMediaStreams()
                            .map { File(it.path) }
                            .plus(File(root, "trickplay/$ITEM_ID/$SOURCE_ID").listFiles().orEmpty())
                            .filter { it.isFile }
                            .sortedBy { it.path }
                            .forEach { file ->
                                add(
                                    Triple(
                                        file.relativeTo(root).invariantSeparatorsPath,
                                        file.length().toInt(),
                                        file.readBytes().joinToString("") { byte ->
                                            "%02x".format(byte)
                                        },
                                    )
                                )
                            }
                    }
                        .sortedBy { it.first },
                imageRequests = imageScheduler.requests.toList(),
            )

        fun close() {
            Timber.uproot(tree)
            root.deleteRecursively()
        }
    }

    internal data class SeededSource(
        val itemId: UUID,
        val source: FindroidSourceDto,
    )

    internal data class LegacyTargetEvidence(val legacyFinal: File, val legacyWitness: File)

    internal class RecordingDao {
        private val sources = linkedMapOf<String, FindroidSourceDto>()
        private val deleteBatches = linkedMapOf<String, DownloadDeleteBatchDto>()
        private val deleteReceipts = linkedMapOf<Pair<String, String>, DownloadDeleteReceiptDto>()
        private val downloadIntents = linkedMapOf<UUID, DownloadIntentDto>()
        private val intentStatusEvents = CopyOnWriteArrayList<String>()
        var source: FindroidSourceDto?
            get() = sources[SOURCE_ID]
            set(value) {
                if (value == null) sources.remove(SOURCE_ID) else sources[value.id] = value
            }

        var itemAvailable = true
        var sourceTaskStatusUpdateResult: Int? = null
        var onGetMovie: (() -> Unit)? = null
        var trackParentRows = false
        var remainingEpisodes: List<Any> = emptyList()
        var remainingSeasons: List<Any> = emptyList()
        var seasonDeleteStarted = CompletableDeferred<Unit>()
        var releaseSeasonDelete = CompletableDeferred<Unit>()
        private var blockSeasonDelete = false
        var sourceDeleteStarted = CompletableDeferred<Unit>()
        var releaseSourceDelete = CompletableDeferred<Unit>()
        var concurrentSourceInserted = CompletableDeferred<Unit>()
        var sourceDeletionGeneration = 0L
        private var blockSourceDelete = false
        private var interruptSourceDeleteAfterCommit = false
        var sourceReadStarted = CompletableDeferred<Unit>()
        var releaseSourceRead = CompletableDeferred<Unit>()
        private var blockSourceRead = false
        private var observedIntentSourceReadId: String? = null
        private var intentSourceReadStarted = CompletableDeferred<Unit>()
        private var releaseIntentSourceRead = CompletableDeferred<Unit>()
        private var blockIntentSourceRead = false
        var intentDeletionStarted = CompletableDeferred<Unit>()
        var releaseIntentDeletion = CompletableDeferred<Unit>()
        private var blockIntentDeletion = false
        var intentDeletionCommitted = CompletableDeferred<Unit>()
        private var interruptIntentDeletionAfterCommit = false
        private var interruptIntentDeletionBeforeCommit = false
        var intentSourceCreatedCasStarted = CompletableDeferred<Unit>()
        var releaseIntentSourceCreatedCas = CompletableDeferred<Unit>()
        private var blockIntentSourceCreatedCas = false
        var intentSourceSnapshotStarted = CompletableDeferred<Unit>()
        var releaseIntentSourceSnapshot = CompletableDeferred<Unit>()
        private var blockIntentSourceSnapshot = false
        private var intentSourceSnapshotItemId: UUID? = null
        private var intentSourceSnapshotReplacementId: String? = null
        var lastIntentDeletionGeneration: Long? = null
        var pendingSourcesSnapshotStarted = CompletableDeferred<Unit>()
        var releasePendingSourcesSnapshot = CompletableDeferred<Unit>()
        private var blockPendingSourcesSnapshot = false
        private val insertedSources = mutableListOf<FindroidSourceDto>()
        val sourceEvents = mutableListOf<String>()
        var sourceInsertAttempts = 0
        private var sourceInsertFailuresRemaining = 0
        var downloadIntentSaveAttempts = 0
        private var downloadIntentSaveFailuresRemaining = 0
        private var downloadIntentSaveInterruptionsRemaining = 0
        private val episodeIdsBySeason = linkedMapOf<UUID, MutableSet<UUID>>()
        private val seasonIdsByShow = linkedMapOf<UUID, MutableSet<UUID>>()
        var snapshotWrites = 0
        var showSnapshotWrites = 0
        var seasonSnapshotWrites = 0
        var episodeSnapshotWrites = 0
        var userDataWrites = 0
        var showSnapshot: FindroidShowDto? = null
        var seasonSnapshot: FindroidSeasonDto? = null
        var episodeSnapshot: FindroidEpisodeDto? = null
        var userDataSnapshot: FindroidUserDataDto? = null
        var internalMediaStreamWrites = 0
        var externalMediaStreamWrites = 0
        var segmentWrites = 0
        var trickplayWrites = 0
        private val snapshots = linkedMapOf<UUID, FindroidMovieDto>()
        private val removedUserDataIds = linkedSetOf<UUID>()
        private val mediaStreams = linkedMapOf<UUID, FindroidMediaStreamDto>()
        private val segments = linkedMapOf<Pair<UUID, FindroidSegmentType>, FindroidSegmentDto>()
        private val trickplayInfos = linkedMapOf<String, FindroidTrickplayInfoDto>()
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
                        "getPendingSources" -> {
                            val snapshot =
                                sources.values.filter {
                                    it.taskStatus != "COMPLETED" || it.path.endsWith(".download")
                                }
                            if (blockPendingSourcesSnapshot) {
                                blockPendingSourcesSnapshot = false
                                pendingSourcesSnapshotStarted.complete(Unit)
                                runBlocking { releasePendingSourcesSnapshot.await() }
                            }
                            snapshot
                        }
                        "getAllSources" -> sources.values.toList()
                        "getSource" -> {
                            val sourceId = args[0] as String
                            if (sourceId == observedIntentSourceReadId) {
                                if (!intentSourceReadStarted.isCompleted) {
                                    intentSourceReadStarted.complete(Unit)
                                }
                                if (blockIntentSourceRead) {
                                    blockIntentSourceRead = false
                                    runBlocking { releaseIntentSourceRead.await() }
                                }
                            }
                            if (blockSourceRead) {
                                blockSourceRead = false
                                sourceReadStarted.complete(Unit)
                                runBlocking { releaseSourceRead.await() }
                            }
                            sources[sourceId]
                        }
                        "insertSource" -> {
                            sourceInsertAttempts += 1
                            if (sourceInsertFailuresRemaining > 0) {
                                sourceInsertFailuresRemaining -= 1
                                error("模拟 Room insertSource 失败")
                            }
                            val inserted = args[0] as FindroidSourceDto
                            insertedSources += inserted
                            sources[inserted.id] = inserted
                            sourceEvents += "INSERT:${inserted.id}:${inserted.taskGeneration}"
                            concurrentSourceInserted.complete(Unit)
                            Unit
                        }
                        "setSourcePath" -> {
                            val id = args[0] as String
                            sources[id]?.let { sources[id] = it.copy(path = args[1] as String) }
                            Unit
                        }
                        "claimSourceRunning" ->
                            updateSource(args) {
                                it.copy(taskStatus = "RUNNING", failureReason = null)
                            }
                        "beginSourcePause" ->
                            updateSourceRow(args) {
                                it.copy(
                                    taskStatus = "PAUSED",
                                    failureReason = null,
                                    taskGeneration = it.taskGeneration + 1,
                                )
                            }
                        "beginSourceCancellation" ->
                            updateSourceRow(args) {
                                it.copy(
                                    taskStatus = DownloadTaskStatus.FAILED.name,
                                    failureReason = DownloadFailureReason.CANCELLED.name,
                                    nextRetryAt = 0L,
                                    taskGeneration = it.taskGeneration + 1,
                                )
                            }
                        "beginSourceDeletion" -> beginSourceDeletion(args)
                        "beginSourceRetry" -> retrySource(args)
                        "setSourceProgressIfCurrent" ->
                            updateSource(args) {
                                it.copy(
                                    downloadedBytes = args[2] as Long,
                                    totalBytes = args[3] as Long,
                                )
                            }
                        "setSourceTaskStatusIfCurrent" -> {
                            val override = sourceTaskStatusUpdateResult
                            if (override != null) {
                                override
                            } else {
                                updateSource(args) {
                                    it.copy(
                                        taskStatus = args[2] as String?,
                                        failureReason = args[3] as String?,
                                    )
                                }
                            }
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
                        "getMovie" -> {
                            onGetMovie?.invoke()
                            (snapshots[args[0] as UUID] ?: movie.takeIf { args[0] == ITEM_ID })
                                ?.takeIf { itemAvailable && episodeSnapshot == null }
                        }
                        "getEpisode" ->
                            episodeSnapshot?.takeIf { itemAvailable && it.id == args[0] as UUID }
                        "insertShow" -> {
                            val show = args[0] as FindroidShowDto
                            showSnapshotWrites += 1
                            if (showSnapshot == null) showSnapshot = show
                            Unit
                        }
                        "insertSeason" -> {
                            val season = args[0] as FindroidSeasonDto
                            seasonSnapshotWrites += 1
                            if (seasonSnapshot == null) seasonSnapshot = season
                            if (trackParentRows) {
                                seasonIdsByShow
                                    .getOrPut(season.seriesId, ::linkedSetOf)
                                    .add(season.id)
                            }
                            Unit
                        }
                        "insertEpisode" -> {
                            val episode = args[0] as FindroidEpisodeDto
                            episodeSnapshotWrites += 1
                            episodeSnapshot = episode
                            if (trackParentRows) {
                                episodeIdsBySeason
                                    .getOrPut(episode.seasonId, ::linkedSetOf)
                                    .add(episode.id)
                            }
                            Unit
                        }
                        "getEpisodesBySeasonId" ->
                            if (trackParentRows) {
                                episodeIdsBySeason[args[0] as UUID].orEmpty().map { Any() }
                            } else {
                                remainingEpisodes
                            }
                        "getSeasonsByShowId" ->
                            if (trackParentRows) {
                                seasonIdsByShow[args[0] as UUID].orEmpty().map { Any() }
                            } else {
                                remainingSeasons
                            }
                        "deleteEpisode" -> {
                            if (episodeSnapshot?.id == args[0] as UUID) episodeSnapshot = null
                            if (trackParentRows) {
                                val episodeId = args[0] as UUID
                                episodeIdsBySeason.values.forEach { it.remove(episodeId) }
                            }
                            Unit
                        }
                        "deleteSeason" -> {
                            if (blockSeasonDelete) {
                                blockSeasonDelete = false
                                seasonDeleteStarted.complete(Unit)
                                runBlocking { releaseSeasonDelete.await() }
                            }
                            if (trackParentRows) {
                                val seasonId = args[0] as UUID
                                seasonIdsByShow.values.forEach { it.remove(seasonId) }
                                episodeIdsBySeason.remove(seasonId)
                            }
                            Unit
                        }
                        "deleteShow" -> {
                            if (trackParentRows) {
                                seasonIdsByShow.remove(args[0] as UUID)
                            }
                            Unit
                        }
                        "deleteMovie" -> {
                            snapshots.remove(args[0] as UUID)
                            Unit
                        }
                        "deleteMediaStreamsBySourceId" -> {
                            val sourceId = args[0] as String
                            mediaStreams.entries.removeIf { (_, stream) ->
                                stream.sourceId == sourceId
                            }
                            sourceEvents += "DELETE_MEDIA:$sourceId"
                            Unit
                        }
                        "deleteSource" -> {
                            val sourceId = args[0] as String
                            sources.remove(sourceId)
                            sourceEvents += "DELETE_SOURCE:$sourceId"
                            Unit
                        }
                        "deleteUserData" -> {
                            removedUserDataIds += args[0] as UUID
                            Unit
                        }
                        "getUserDataOrCreateNew" -> userData
                        "getSources" -> {
                            val snapshot = sources.values.filter { it.itemId == args[0] as UUID }
                            if (blockIntentSourceSnapshot) {
                                blockIntentSourceSnapshot = false
                                intentSourceSnapshotStarted.complete(Unit)
                                val itemId = checkNotNull(intentSourceSnapshotItemId)
                                val retryAdvanced = runBlocking {
                                    while (
                                        downloadIntents[itemId]?.status !=
                                            DownloadIntentStatus.RECEIVED.name &&
                                            !releaseIntentSourceSnapshot.isCompleted
                                    ) {
                                        delay(5L)
                                    }
                                    downloadIntents[itemId]?.status ==
                                        DownloadIntentStatus.RECEIVED.name
                                }
                                runBlocking { releaseIntentSourceSnapshot.await() }
                                snapshot.firstOrNull()?.let { old ->
                                    val replacementId =
                                        checkNotNull(intentSourceSnapshotReplacementId)
                                    sources[replacementId] =
                                        old.copy(
                                            id = replacementId,
                                            taskStatus = DownloadTaskStatus.PENDING.name,
                                            failureReason = null,
                                            taskGeneration = old.taskGeneration + 1L,
                                        )
                                }
                                if (retryAdvanced) {
                                    sources.values.filter { it.itemId == itemId }
                                } else {
                                    snapshot
                                }
                            } else {
                                snapshot
                            }
                        }
                        "getTrickplayInfo" -> trickplay.takeIf { args[0] == SOURCE_ID }
                        "insertMovie" -> {
                            snapshotWrites += 1
                            val snapshot = args[0] as FindroidMovieDto
                            snapshots.putIfAbsent(snapshot.id, snapshot)
                            Unit
                        }
                        "insertUserData" -> {
                            userDataWrites += 1
                            userDataSnapshot = args[0] as FindroidUserDataDto
                            Unit
                        }
                        "insertMediaStream" -> {
                            val stream = args[0] as FindroidMediaStreamDto
                            if (stream.isExternal) {
                                externalMediaStreamWrites += 1
                            } else {
                                internalMediaStreamWrites += 1
                            }
                            mediaStreams[stream.id] = stream
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
                            val segment = args[0] as FindroidSegmentDto
                            segmentWrites += 1
                            segments[segment.itemId to segment.type] = segment
                            Unit
                        }
                        "insertTrickplayInfo" -> {
                            val info = args[0] as FindroidTrickplayInfoDto
                            trickplayWrites += 1
                            trickplayInfos[info.sourceId] = info
                            Unit
                        }
                        "beginDownloadDeleteBatch" -> {
                            val batch = args[0] as DownloadDeleteBatchDto
                            deleteBatches[batch.id] = batch
                            (args[1] as List<*>)
                                .map { it as DownloadDeleteReceiptDto }
                                .forEach { receipt ->
                                    deleteReceipts[receipt.batchId to receipt.canonicalKey] =
                                        receipt
                                }
                            Unit
                        }
                        "insertDownloadDeleteReceipt" -> {
                            val receipt = args[0] as DownloadDeleteReceiptDto
                            deleteReceipts[receipt.batchId to receipt.canonicalKey] = receipt
                            Unit
                        }
                        "finishDownloadDeleteBatch" -> {
                            val batchId = args[0] as String
                            deleteBatches[batchId]?.let {
                                deleteBatches[batchId] = it.copy(completedAt = args[1] as Long)
                            }
                            Unit
                        }
                        "getIncompleteDownloadDeleteBatches" ->
                            deleteBatches.values.filter { it.completedAt == null }
                        "getDownloadDeleteReceipts" ->
                            deleteReceipts.values
                                .filter { it.batchId == args[0] as String }
                                .sortedBy { it.ordinal }
                        "getDownloadIntents" -> downloadIntents.values.toList()
                        "getDownloadIntent" -> downloadIntents[args[0] as UUID]
                        "beginDownloadIntentDeletion" -> {
                            if (interruptIntentDeletionBeforeCommit) {
                                interruptIntentDeletionBeforeCommit = false
                                intentDeletionStarted.complete(Unit)
                                throw CancellationException("模拟 intent CAS 前进程中断")
                            }
                            if (blockIntentDeletion) {
                                blockIntentDeletion = false
                                intentDeletionStarted.complete(Unit)
                                runBlocking { releaseIntentDeletion.await() }
                            }
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (
                                current?.taskGeneration == generation &&
                                    current.status in
                                        setOf(
                                            DownloadIntentStatus.RECEIVED.name,
                                            DownloadIntentStatus.RESOLVING.name,
                                            DownloadIntentStatus.SOURCE_CREATED.name,
                                            DownloadIntentStatus.FAILED.name,
                                        )
                            ) {
                                val deleted =
                                    current.copy(
                                        status = DownloadIntentStatus.FAILED.name,
                                        failureReason = DownloadFailureReason.CANCELLED.name,
                                        failureDetail = args[3] as String?,
                                        taskGeneration = generation + 1,
                                        updatedAt = args[2] as Long,
                                    )
                                downloadIntents[itemId] = deleted
                                lastIntentDeletionGeneration = deleted.taskGeneration
                                if (interruptIntentDeletionAfterCommit) {
                                    interruptIntentDeletionAfterCommit = false
                                    intentDeletionCommitted.complete(Unit)
                                    throw CancellationException("模拟 intent CAS 后进程中断")
                                }
                                deleted
                            } else {
                                null
                            }
                        }
                        "beginDownloadIntentCancellation" -> {
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (
                                current?.taskGeneration == generation &&
                                    current.status in
                                        setOf(
                                            DownloadIntentStatus.RECEIVED.name,
                                            DownloadIntentStatus.RESOLVING.name,
                                            DownloadIntentStatus.SOURCE_CREATED.name,
                                        )
                            ) {
                                val cancelled =
                                    current.copy(
                                        status = DownloadIntentStatus.FAILED.name,
                                        failureReason = DownloadFailureReason.CANCELLED.name,
                                        failureDetail = null,
                                        taskGeneration = generation + 1L,
                                        updatedAt = args[2] as Long,
                                    )
                                downloadIntents[itemId] = cancelled
                                intentStatusEvents +=
                                    "${cancelled.status}:$itemId:${cancelled.taskGeneration}"
                                cancelled
                            } else {
                                null
                            }
                        }
                        "getReceivedDownloadIntents" ->
                            downloadIntents.values
                                .filter {
                                    it.status == DownloadIntentStatus.RECEIVED.name ||
                                        it.status == DownloadIntentStatus.RESOLVING.name
                                }
                                .sortedBy { it.createdAt }
                        "saveDownloadIntents" -> {
                            downloadIntentSaveAttempts += 1
                            if (downloadIntentSaveInterruptionsRemaining > 0) {
                                downloadIntentSaveInterruptionsRemaining -= 1
                                throw CancellationException("模拟 Room anchor 前进程中断")
                            }
                            if (downloadIntentSaveFailuresRemaining > 0) {
                                downloadIntentSaveFailuresRemaining -= 1
                                error("模拟 Room saveDownloadIntents 事务失败")
                            }
                            @Suppress("UNCHECKED_CAST")
                            val intents = args[0] as List<DownloadIntentDto>
                            val supersedableItemIds =
                                (args[1] as Set<*>).mapNotNullTo(mutableSetOf()) { it as? UUID }
                            val legacyFailureDetail = args[2] as String
                            intents.forEach { intent ->
                                val current = downloadIntents[intent.itemId]
                                if (
                                    intent.itemId in supersedableItemIds &&
                                        (current?.status == DownloadIntentStatus.RECEIVED.name ||
                                            current?.status ==
                                                DownloadIntentStatus.RESOLVING.name ||
                                            (current?.status == DownloadIntentStatus.FAILED.name &&
                                                current.failureDetail == legacyFailureDetail))
                                ) {
                                    val saved =
                                        intent.copy(
                                            createdAt = current.createdAt,
                                            taskGeneration = current.taskGeneration + 1L,
                                        )
                                    downloadIntents[intent.itemId] = saved
                                    intentStatusEvents +=
                                        "${saved.status}:${saved.itemId}:${saved.taskGeneration}"
                                } else {
                                    downloadIntents.putIfAbsent(intent.itemId, intent)
                                    downloadIntents[intent.itemId]?.let { saved ->
                                        intentStatusEvents +=
                                            "${saved.status}:${saved.itemId}:${saved.taskGeneration}"
                                    }
                                }
                            }
                            Unit
                        }
                        "deleteDownloadIntentIfCurrent" -> {
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            if (downloadIntents[itemId]?.taskGeneration == generation) {
                                downloadIntents.remove(itemId)
                                1
                            } else {
                                0
                            }
                        }
                        "retryDownloadIntent" -> {
                            val itemId = args[0] as UUID
                            val expectedGeneration = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (
                                current?.taskGeneration == expectedGeneration &&
                                    current.status == DownloadIntentStatus.FAILED.name
                            ) {
                                val retried =
                                    current.copy(
                                        status = DownloadIntentStatus.RECEIVED.name,
                                        failureReason = null,
                                        failureDetail = null,
                                        taskGeneration = expectedGeneration + 1L,
                                        updatedAt = args[2] as Long,
                                    )
                                downloadIntents[itemId] = retried
                                intentStatusEvents +=
                                    "${retried.status}:$itemId:${retried.taskGeneration}"
                                1
                            } else {
                                0
                            }
                        }
                        "setDownloadIntentStatusIfCurrent" -> {
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (current?.taskGeneration == generation) {
                                downloadIntents[itemId] =
                                    current.copy(
                                        status = args[2] as String,
                                        failureReason = args[3] as String?,
                                        failureDetail = args[4] as String?,
                                        updatedAt = args[5] as Long,
                                    )
                                1
                            } else {
                                0
                            }
                        }
                        "beginDownloadIntentResolutionIfCurrent" -> {
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (
                                current?.taskGeneration == generation &&
                                    current.status in
                                        setOf(
                                            DownloadIntentStatus.RECEIVED.name,
                                            DownloadIntentStatus.RESOLVING.name,
                                        )
                            ) {
                                val resolving =
                                    current.copy(
                                        status = DownloadIntentStatus.RESOLVING.name,
                                        failureReason = null,
                                        failureDetail = null,
                                        updatedAt = args[2] as Long,
                                    )
                                downloadIntents[itemId] = resolving
                                intentStatusEvents +=
                                    "${resolving.status}:$itemId:${resolving.taskGeneration}"
                                1
                            } else {
                                0
                            }
                        }
                        "confirmDownloadIntentResolutionIfCurrent" -> {
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (
                                current?.taskGeneration == generation &&
                                    current.status == DownloadIntentStatus.RESOLVING.name
                            ) {
                                downloadIntents[itemId] = current.copy(updatedAt = args[2] as Long)
                                1
                            } else {
                                0
                            }
                        }
                        "markDownloadIntentSourceCreatedIfCurrent" -> {
                            if (blockIntentSourceCreatedCas) {
                                blockIntentSourceCreatedCas = false
                                intentSourceCreatedCasStarted.complete(Unit)
                                runBlocking { releaseIntentSourceCreatedCas.await() }
                            }
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (
                                current?.taskGeneration == generation &&
                                    current.status == DownloadIntentStatus.RESOLVING.name
                            ) {
                                val created =
                                    current.copy(
                                        status = DownloadIntentStatus.SOURCE_CREATED.name,
                                        updatedAt = args[2] as Long,
                                    )
                                downloadIntents[itemId] = created
                                intentStatusEvents +=
                                    "${created.status}:$itemId:${created.taskGeneration}"
                                1
                            } else {
                                0
                            }
                        }
                        "failDownloadIntentResolutionIfCurrent" -> {
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (
                                current?.taskGeneration == generation &&
                                    current.status in
                                        setOf(
                                            DownloadIntentStatus.RECEIVED.name,
                                            DownloadIntentStatus.RESOLVING.name,
                                        )
                            ) {
                                val failed =
                                    current.copy(
                                        status = DownloadIntentStatus.FAILED.name,
                                        failureReason = args[2] as String,
                                        failureDetail = args[3] as String,
                                        updatedAt = args[4] as Long,
                                    )
                                downloadIntents[itemId] = failed
                                intentStatusEvents +=
                                    "${failed.status}:$itemId:${failed.taskGeneration}"
                                1
                            } else {
                                0
                            }
                        }
                        "requeueDownloadIntentResolutionIfCurrent" -> {
                            val itemId = args[0] as UUID
                            val generation = args[1] as Long
                            val current = downloadIntents[itemId]
                            if (
                                current?.taskGeneration == generation &&
                                    current.status == DownloadIntentStatus.RESOLVING.name
                            ) {
                                val requeued =
                                    current.copy(
                                        status = DownloadIntentStatus.RECEIVED.name,
                                        failureReason = null,
                                        failureDetail = null,
                                        updatedAt = args[2] as Long,
                                    )
                                downloadIntents[itemId] = requeued
                                intentStatusEvents +=
                                    "${requeued.status}:$itemId:${requeued.taskGeneration}"
                                1
                            } else {
                                0
                            }
                        }
                        "getSourceItemIds" -> {
                            @Suppress("UNCHECKED_CAST") val itemIds = args[0] as Set<UUID>
                            sources.values.mapNotNull { it.itemId.takeIf(itemIds::contains) }
                        }
                        "getDownloadIntentItemIds" -> {
                            @Suppress("UNCHECKED_CAST") val itemIds = args[0] as Set<UUID>
                            downloadIntents.keys.filter(itemIds::contains)
                        }
                        "getDownloadedItemIds" ->
                            sources.values
                                .filter { it.taskStatus == DownloadTaskStatus.COMPLETED.name }
                                .mapTo(mutableSetOf()) { it.itemId }
                        else -> error("unexpected DAO call: ${method.name}")
                    }
                },
            ) as ServerDatabaseDao

        fun externalStream(): FindroidMediaStreamDto? =
            mediaStreams.values.firstOrNull { it.isExternal }

        fun armSeasonDeleteGate() {
            blockSeasonDelete = true
            seasonDeleteStarted = CompletableDeferred()
            releaseSeasonDelete = CompletableDeferred()
        }

        fun armSourceDeletionGate() {
            blockSourceDelete = true
            sourceDeleteStarted = CompletableDeferred()
            releaseSourceDelete = CompletableDeferred()
            concurrentSourceInserted = CompletableDeferred()
            sourceDeletionGeneration = 0L
        }

        fun armSourceDeletionInterruption() {
            armSourceDeletionGate()
            interruptSourceDeleteAfterCommit = true
        }

        fun armSourceReadGate() {
            blockSourceRead = true
            sourceReadStarted = CompletableDeferred()
            releaseSourceRead = CompletableDeferred()
        }

        fun armPendingSourcesSnapshotGate() {
            blockPendingSourcesSnapshot = true
            pendingSourcesSnapshotStarted = CompletableDeferred()
            releasePendingSourcesSnapshot = CompletableDeferred()
        }

        fun lastInsertedSource(sourceId: String): FindroidSourceDto? = insertedSources.lastOrNull {
            it.id == sourceId
        }

        fun sourceInsertCount(sourceId: String): Int = insertedSources.count { it.id == sourceId }

        fun failSourceInsertions(count: Int) {
            sourceInsertFailuresRemaining = count
        }

        fun failDownloadIntentSaves(count: Int) {
            downloadIntentSaveFailuresRemaining = count
        }

        fun interruptDownloadIntentSaves(count: Int) {
            downloadIntentSaveInterruptionsRemaining = count
        }

        fun clearDownloadIntentSaveFailures() {
            downloadIntentSaveFailuresRemaining = 0
        }

        suspend fun awaitSourceInsertAttempts(count: Int): Boolean =
            withTimeoutOrNull(5_000L) {
                while (sourceInsertAttempts < count) delay(10L)
                true
            } ?: false

        suspend fun awaitInsertedSourceCount(count: Int): Boolean =
            withTimeoutOrNull(5_000L) {
                while (insertedSources.size < count) delay(10L)
                true
            } ?: false

        suspend fun awaitIntentNotReceived(itemId: UUID): Boolean =
            withTimeoutOrNull(5_000L) {
                while (
                    downloadIntents[itemId]?.status !in
                        setOf(
                            DownloadIntentStatus.FAILED.name,
                            DownloadIntentStatus.SOURCE_CREATED.name,
                        )
                ) {
                    delay(10L)
                }
                true
            } ?: false

        fun intentCount(): Int = downloadIntents.size

        fun downloadIntentStatusEvents(itemId: UUID): List<String> =
            downloadIntentStatusEvents(
                itemId,
                checkNotNull(downloadIntents[itemId]).taskGeneration,
            )

        fun downloadIntentStatusEvents(itemId: UUID, generation: Long): List<String> =
            intentStatusEvents
                .filter { it.endsWith(":$itemId:$generation") }
                .map { it.substringBefore(':') }

        fun downloadIntent(itemId: UUID): DownloadIntentDto? = downloadIntents[itemId]

        fun putDownloadIntent(intent: DownloadIntentDto) {
            downloadIntents[intent.itemId] = intent
        }

        fun observeIntentSourceRead(sourceId: String) {
            observedIntentSourceReadId = sourceId
            intentSourceReadStarted = CompletableDeferred()
            releaseIntentSourceRead = CompletableDeferred()
            blockIntentSourceRead = false
        }

        fun armIntentSourceReadGate(sourceId: String) {
            observeIntentSourceRead(sourceId)
            blockIntentSourceRead = true
        }

        suspend fun awaitIntentSourceRead() {
            intentSourceReadStarted.await()
        }

        fun releaseIntentSourceRead() {
            if (!releaseIntentSourceRead.isCompleted) {
                releaseIntentSourceRead.complete(Unit)
            }
        }

        fun armIntentDeletionGate() {
            intentDeletionStarted = CompletableDeferred()
            releaseIntentDeletion = CompletableDeferred()
            blockIntentDeletion = true
        }

        suspend fun awaitIntentDeletion() {
            intentDeletionStarted.await()
        }

        fun releaseIntentDeletion() {
            if (!releaseIntentDeletion.isCompleted) {
                releaseIntentDeletion.complete(Unit)
            }
        }

        fun armIntentDeletionInterruptionAfterCommit() {
            intentDeletionCommitted = CompletableDeferred()
            interruptIntentDeletionAfterCommit = true
        }

        fun armIntentDeletionInterruptionBeforeCommit() {
            intentDeletionStarted = CompletableDeferred()
            interruptIntentDeletionBeforeCommit = true
        }

        suspend fun awaitIntentDeletionCommitted() {
            intentDeletionCommitted.await()
        }

        fun armIntentSourceCreatedCasGate() {
            intentSourceCreatedCasStarted = CompletableDeferred()
            releaseIntentSourceCreatedCas = CompletableDeferred()
            blockIntentSourceCreatedCas = true
        }

        suspend fun awaitIntentSourceCreatedCas() {
            intentSourceCreatedCasStarted.await()
        }

        fun releaseIntentSourceCreatedCas() {
            if (!releaseIntentSourceCreatedCas.isCompleted) {
                releaseIntentSourceCreatedCas.complete(Unit)
            }
        }

        fun armIntentSourceSnapshotGate(
            itemId: UUID,
            replacementSourceId: String,
        ) {
            intentSourceSnapshotStarted = CompletableDeferred()
            releaseIntentSourceSnapshot = CompletableDeferred()
            blockIntentSourceSnapshot = true
            intentSourceSnapshotItemId = itemId
            intentSourceSnapshotReplacementId = replacementSourceId
        }

        suspend fun awaitIntentSourceSnapshot() {
            intentSourceSnapshotStarted.await()
        }

        fun releaseIntentSourceSnapshot() {
            if (!releaseIntentSourceSnapshot.isCompleted) {
                releaseIntentSourceSnapshot.complete(Unit)
            }
        }

        fun seedDownloadIntent(itemId: UUID, status: String = "RECEIVED"): DownloadIntentDto {
            val intent =
                DownloadIntentDto(
                    itemId = itemId,
                    name = "legacy 音乐",
                    albumName = null,
                    artist = null,
                    trackIndex = 0,
                    status = status,
                    failureReason = null,
                    failureDetail = null,
                    createdAt = 1L,
                    updatedAt = 1L,
                    taskGeneration = 0L,
                )
            downloadIntents[itemId] = intent
            return intent
        }

        fun hasEpisode(
            seasonId: UUID,
            episodeId: UUID,
        ): Boolean = episodeIdsBySeason[seasonId].orEmpty().contains(episodeId)

        fun hasSeason(
            showId: UUID,
            seasonId: UUID,
        ): Boolean = seasonIdsByShow[showId].orEmpty().contains(seasonId)

        fun snapshotIds(): List<UUID> = snapshots.keys.toList()

        fun sourceIds(): List<String> = sources.keys.toList()

        fun sourceById(id: String): FindroidSourceDto? = sources[id]

        fun sourceIdByPath(path: String): String? =
            sources.values.firstOrNull { it.path == path }?.id

        fun pausedSourceIds(): List<String> =
            sources.values.filter { it.taskStatus == "PAUSED" }.map { it.id }

        fun runningSourceIds(): List<String> =
            sources.values.filter { it.taskStatus == "RUNNING" }.map { it.id }

        fun deletedUserDataIds(): Set<UUID> = removedUserDataIds

        fun putSource(source: FindroidSourceDto) {
            sources[source.id] = source
        }

        fun seedPendingDeleteBatch(
            batchId: String,
            itemId: UUID,
            sourceId: String,
            expectedGeneration: Long?,
            mediaKind: DownloadMediaKind = DownloadMediaKind.VIDEO,
            detail: String? = expectedGeneration?.let { "generation=$it" },
        ) {
            deleteBatches[batchId] =
                DownloadDeleteBatchDto(
                    id = batchId,
                    requestedCount = 1,
                    createdAt = 1L,
                )
            val key =
                DownloadDeletionIdentity.canonicalKey(
                    DownloadDeleteRequest(
                        itemId = itemId,
                        sourceId = sourceId,
                        mediaKind = mediaKind,
                        serverId = SERVER_ID,
                        userId = USER_ID.toString(),
                    )
                )
            deleteReceipts[batchId to key] =
                DownloadDeleteReceiptDto(
                    batchId = batchId,
                    canonicalKey = key,
                    itemId = itemId,
                    sourceId = sourceId,
                    serverId = SERVER_ID,
                    userId = USER_ID.toString(),
                    ordinal = 0,
                    mediaKind = mediaKind.name,
                    outcome = DownloadDeleteOutcome.PENDING.name,
                    detail = detail,
                    updatedAt = 1L,
                    verifiedGeneration = expectedGeneration,
                )
        }

        fun deleteBatch(batchId: String): DownloadDeleteBatchDto? = deleteBatches[batchId]

        fun deleteReceipt(
            batchId: String,
            canonicalKey: String,
        ): DownloadDeleteReceiptDto? = deleteReceipts[batchId to canonicalKey]

        fun deleteReceipts(batchId: String): List<DownloadDeleteReceiptDto> =
            deleteReceipts.values.filter { it.batchId == batchId }

        fun pendingDeleteReceipts(): List<DownloadDeleteReceiptDto> =
            deleteReceipts.values.filter { it.outcome == DownloadDeleteOutcome.PENDING.name }

        fun seedMovie(itemId: UUID) {
            snapshots[itemId] = movie.copy(id = itemId, name = "Concurrent $itemId")
        }

        fun internalMediaStreams(): List<FindroidMediaStreamDto> =
            mediaStreams.values.filterNot { it.isExternal }

        fun externalMediaStreams(): List<FindroidMediaStreamDto> =
            mediaStreams.values.filter { it.isExternal }

        fun segments(): List<FindroidSegmentDto> = segments.values.toList()

        fun trickplayInfos(): List<FindroidTrickplayInfoDto> = trickplayInfos.values.toList()

        fun resetEffectCalls() {
            snapshotWrites = 0
            showSnapshotWrites = 0
            seasonSnapshotWrites = 0
            episodeSnapshotWrites = 0
            userDataWrites = 0
            internalMediaStreamWrites = 0
            externalMediaStreamWrites = 0
            segmentWrites = 0
            trickplayWrites = 0
        }

        fun putMediaStream(stream: FindroidMediaStreamDto) {
            mediaStreams[stream.id] = stream
        }

        private fun beginSourceDeletion(args: Array<Any?>): FindroidSourceDto? {
            val sourceId = args[0] as String
            if (sources[sourceId]?.taskStatus == DownloadTaskStatus.DELETING.name) return null
            val deleting =
                updateSourceRow(args) {
                    it.copy(
                        taskStatus = "DELETING",
                        failureReason = null,
                        taskGeneration = it.taskGeneration + 1,
                    )
                } ?: return null
            if (blockSourceDelete) {
                blockSourceDelete = false
                sourceDeletionGeneration = deleting.taskGeneration
                sourceDeleteStarted.complete(Unit)
                runBlocking { releaseSourceDelete.await() }
            }
            if (interruptSourceDeleteAfterCommit) {
                interruptSourceDeleteAfterCommit = false
                throw CancellationException("模拟 source 删除栅栏提交后进程中断")
            }
            return deleting
        }

        private fun retrySource(args: Array<Any?>): FindroidSourceDto? {
            val current = sources[args[0] as String] ?: return null
            if (current.taskGeneration != args[1] || current.taskStatus != "FAILED") {
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
                .also { sources[it.id] = it }
        }

        private fun updateSource(
            args: Array<Any?>,
            requireRunning: Boolean = false,
            transform: (FindroidSourceDto) -> FindroidSourceDto,
        ): Int {
            val current = sources[args[0] as String] ?: return 0
            if (
                current.taskGeneration != args[1] ||
                    (requireRunning && current.taskStatus != "RUNNING")
            ) {
                return 0
            }
            sources[current.id] = transform(current)
            return 1
        }

        private fun updateSourceRow(
            args: Array<Any?>,
            transform: (FindroidSourceDto) -> FindroidSourceDto,
        ): FindroidSourceDto? {
            val current = sources[args[0] as String] ?: return null
            if (current.taskGeneration != args[1]) return null
            return transform(current).also { sources[it.id] = it }
        }
    }

    internal class RecordingRepository {
        private data class ItemLookupGate(
            val started: CompletableDeferred<Unit>,
            val release: CompletableDeferred<Unit>,
        )

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
        var segmentReads = 0
        var trickplayReads = 0
        var userId = USER_ID
        var userIdReads = 0
        var baseUrlReads = 0
        var accessTokenReads = 0
        var showReads = 0
        var seasonReads = 0
        var originSegmentReads = 0
        var originTrickplayReads = 0
        var originShowReads = 0
        var originSeasonReads = 0
        private var originSegmentFailuresRemaining = 0
        val mediaSourcesByItemId = linkedMapOf<UUID, FindroidSource>()
        private val queuedMediaSourcesByItemId = linkedMapOf<UUID, ArrayDeque<FindroidSource>>()
        private val itemsById = linkedMapOf<UUID, FindroidItem>()
        private val originItems = linkedMapOf<Pair<UUID, JellyfinOrigin>, FindroidItem>()
        private val originMediaSources = linkedMapOf<Pair<UUID, JellyfinOrigin>, FindroidSource>()
        private val originSegments =
            linkedMapOf<Pair<UUID, JellyfinOrigin>, List<FindroidSegment>>()
        private val originTrickplay = linkedMapOf<Pair<UUID, JellyfinOrigin>, ByteArray>()
        private val missingOriginTrickplay = mutableSetOf<Pair<UUID, JellyfinOrigin>>()
        private val originShows = linkedMapOf<Pair<UUID, JellyfinOrigin>, FindroidShow>()
        private val originSeasons = linkedMapOf<Pair<UUID, JellyfinOrigin>, FindroidSeason>()
        private val originItemLookups = CopyOnWriteArrayList<Pair<UUID, JellyfinOrigin>>()
        val originSegmentLookups = CopyOnWriteArrayList<Pair<UUID, JellyfinOrigin>>()
        val originTrickplayLookups = CopyOnWriteArrayList<Pair<UUID, JellyfinOrigin>>()
        val originShowLookups = CopyOnWriteArrayList<Pair<UUID, JellyfinOrigin>>()
        val originSeasonLookups = CopyOnWriteArrayList<Pair<UUID, JellyfinOrigin>>()
        private val originFailures = mutableMapOf<JellyfinOrigin, Int>()
        private var itemLookupGate: CompletableDeferred<Unit>? = null
        private var itemLookupStarted = CompletableDeferred<Unit>()
        private val itemLookupGates = java.util.concurrent.ConcurrentHashMap<UUID, ItemLookupGate>()

        fun resetEffectCalls() {
            segmentReads = 0
            trickplayReads = 0
            showReads = 0
            seasonReads = 0
            originSegmentReads = 0
            originTrickplayReads = 0
            originShowReads = 0
            originSeasonReads = 0
            originSegmentLookups.clear()
            originTrickplayLookups.clear()
            originShowLookups.clear()
            originSeasonLookups.clear()
        }

        fun putMediaSource(itemId: UUID, source: FindroidSource) {
            mediaSourcesByItemId[itemId] = source
        }

        fun enqueueMediaSource(itemId: UUID, source: FindroidSource) {
            synchronized(queuedMediaSourcesByItemId) {
                queuedMediaSourcesByItemId.getOrPut(itemId, ::ArrayDeque).addLast(source)
            }
        }

        fun putItem(item: FindroidItem) {
            itemsById[item.id] = item
        }

        fun putOriginItem(
            itemId: UUID,
            origin: JellyfinOrigin,
            item: FindroidItem,
        ) {
            originItems[itemId to origin] = item
        }

        fun putOriginMediaSource(
            itemId: UUID,
            origin: JellyfinOrigin,
            source: FindroidSource,
        ) {
            originMediaSources[itemId to origin] = source
        }

        fun putOriginSegments(
            itemId: UUID,
            origin: JellyfinOrigin,
            segments: List<FindroidSegment>,
        ) {
            originSegments[itemId to origin] = segments
        }

        fun putOriginTrickplay(
            itemId: UUID,
            origin: JellyfinOrigin,
            bytes: ByteArray,
        ) {
            originTrickplay[itemId to origin] = bytes
            missingOriginTrickplay.remove(itemId to origin)
        }

        fun removeOriginTrickplay(itemId: UUID, origin: JellyfinOrigin) {
            originTrickplay.remove(itemId to origin)
            missingOriginTrickplay += itemId to origin
        }

        fun putOriginShow(
            itemId: UUID,
            origin: JellyfinOrigin,
            show: FindroidShow,
        ) {
            originShows[itemId to origin] = show
        }

        fun putOriginSeason(
            itemId: UUID,
            origin: JellyfinOrigin,
            season: FindroidSeason,
        ) {
            originSeasons[itemId to origin] = season
        }

        fun failNextOriginSegments() {
            originSegmentFailuresRemaining += 1
        }

        fun failNextOriginItem(origin: JellyfinOrigin) {
            originFailures[origin] = (originFailures[origin] ?: 0) + 1
        }

        suspend fun awaitOriginItemLookup(count: Int): Boolean =
            withTimeoutOrNull(5_000L) {
                while (originItemLookups.size < count) delay(10L)
                true
            } ?: false

        fun blockNextItemLookup() {
            itemLookupGate = CompletableDeferred()
            itemLookupStarted = CompletableDeferred()
        }

        fun blockItemLookup(itemId: UUID) {
            itemLookupGates[itemId] =
                ItemLookupGate(
                    started = CompletableDeferred(),
                    release = CompletableDeferred(),
                )
        }

        suspend fun awaitItemLookup(itemId: UUID): Boolean =
            withTimeoutOrNull(2_000L) {
                while (itemLookupGates[itemId]?.started?.isCompleted != true) {
                    delay(10L)
                }
                true
            } ?: false

        fun releaseAllItemLookups() {
            itemLookupGates.values.forEach { it.release.complete(Unit) }
            itemLookupGates.clear()
        }

        suspend fun awaitItemLookup(): Boolean =
            withTimeoutOrNull(2_000L) {
                itemLookupStarted.await()
                true
            } ?: false

        fun releaseItemLookup() {
            itemLookupGate?.complete(Unit)
            itemLookupGate = null
        }

        val proxy: JellyfinRepository =
            Proxy.newProxyInstance(
                JellyfinRepository::class.java.classLoader,
                arrayOf(
                    JellyfinRepository::class.java,
                    OriginScopedJellyfinRepository::class.java,
                ),
                InvocationHandler { _, method, args ->
                    when (method.name) {
                        "getItem" -> {
                            val itemId = args[0] as UUID
                            itemLookupGate?.let { gate ->
                                itemLookupStarted.complete(Unit)
                                runBlocking { gate.await() }
                            }
                            itemsById[itemId]
                        }
                        "getItemForOrigin" -> {
                            val itemId = args[0] as UUID
                            val origin = args[1] as JellyfinOrigin
                            itemLookupGates[itemId]?.let { gate ->
                                gate.started.complete(Unit)
                                runBlocking { gate.release.await() }
                            }
                            itemLookupGate?.let { gate ->
                                itemLookupStarted.complete(Unit)
                                runBlocking { gate.await() }
                            }
                            originItemLookups += itemId to origin
                            val failures = originFailures[origin] ?: 0
                            if (failures > 0) {
                                originFailures[origin] = failures - 1
                                error("origin item unavailable: ${origin.serverId}")
                            }
                            originItems[itemId to origin]
                        }
                        "getMediaSources" -> {
                            val itemId = args[0] as UUID
                            listOf(
                                synchronized(queuedMediaSourcesByItemId) {
                                    queuedMediaSourcesByItemId[itemId]?.removeFirstOrNull()
                                }
                                    ?: mediaSourcesByItemId[itemId]
                                    ?: FindroidSource(
                                        id = SOURCE_ID,
                                        name = SOURCE_NAME,
                                        type = FindroidSourceType.REMOTE,
                                        path = MEDIA_URL,
                                        size = MEDIA_BYTES.size.toLong(),
                                        mediaStreams = mediaStreams,
                                        container = "mp4",
                                    )
                            )
                        }
                        "getMediaSourcesForOrigin" -> {
                            val itemId = args[0] as UUID
                            val origin = args[1] as JellyfinOrigin
                            listOfNotNull(
                                originMediaSources[itemId to origin]
                                    ?: mediaSourcesByItemId[itemId]?.takeIf {
                                        origin == JellyfinOrigin(SERVER_ID, USER_ID)
                                    }
                                    ?: FindroidSource(
                                            id = SOURCE_ID,
                                            name = SOURCE_NAME,
                                            type = FindroidSourceType.REMOTE,
                                            path = MEDIA_URL,
                                            size = MEDIA_BYTES.size.toLong(),
                                            mediaStreams = mediaStreams,
                                            container = "mp4",
                                        )
                                        .takeIf { origin == JellyfinOrigin(SERVER_ID, USER_ID) }
                            )
                        }
                        "credentialsForOrigin" -> {
                            val origin = args[0] as JellyfinOrigin
                            JellyfinOriginCredentials(
                                baseUrl = "https://${origin.serverId}.test",
                                accessToken = "token-${origin.serverId}",
                            )
                        }
                        "getSegments" -> {
                            segmentReads += 1
                            if (segmentFailuresRemaining > 0) {
                                segmentFailuresRemaining -= 1
                                error("segments unavailable")
                            }
                            listOf(FindroidSegment(FindroidSegmentType.INTRO, 0L, 10L))
                        }
                        "getSegmentsForOrigin" -> {
                            val itemId = args[0] as UUID
                            val origin = args[1] as JellyfinOrigin
                            originSegmentReads += 1
                            originSegmentLookups += itemId to origin
                            if (originSegmentFailuresRemaining > 0) {
                                originSegmentFailuresRemaining -= 1
                                error("origin segments unavailable: ${origin.serverId}")
                            }
                            originSegments[itemId to origin]
                                ?: listOf(FindroidSegment(FindroidSegmentType.INTRO, 0L, 10L))
                        }
                        "getTrickplayData" -> {
                            trickplayReads += 1
                            byteArrayOf(1)
                        }
                        "getTrickplayDataForOrigin" -> {
                            val itemId = args[0] as UUID
                            val origin = args[3] as JellyfinOrigin
                            originTrickplayReads += 1
                            originTrickplayLookups += itemId to origin
                            if (itemId to origin in missingOriginTrickplay) {
                                null
                            } else {
                                originTrickplay[itemId to origin] ?: byteArrayOf(1)
                            }
                        }
                        "getBaseUrl" -> {
                            baseUrlReads += 1
                            "https://example.test"
                        }
                        "getAccessToken" -> {
                            accessTokenReads += 1
                            "token"
                        }
                        "getUserId" -> {
                            userIdReads += 1
                            userId
                        }
                        "getShow" -> {
                            showReads += 1
                            FindroidShow(
                                id = SHOW_ID,
                                name = SHOW_NAME,
                                originalTitle = null,
                                overview = "",
                                sources = emptyList(),
                                seasons = emptyList(),
                                played = false,
                                favorite = false,
                                canPlay = true,
                                canDownload = false,
                                unplayedItemCount = null,
                                genres = emptyList(),
                                people = emptyList(),
                                runtimeTicks = 0L,
                                communityRating = null,
                                officialRating = null,
                                status = "Continuing",
                                productionYear = null,
                                endDate = null,
                                trailer = null,
                                images = FindroidImages(),
                            )
                        }
                        "getShowForOrigin" -> {
                            val itemId = args[0] as UUID
                            val origin = args[1] as JellyfinOrigin
                            originShowReads += 1
                            originShowLookups += itemId to origin
                            originShows[itemId to origin]
                                ?: error("origin show unavailable: ${origin.serverId}")
                        }
                        "getSeason" -> {
                            seasonReads += 1
                            FindroidSeason(
                                id = SEASON_ID,
                                name = SEASON_NAME,
                                seriesId = SHOW_ID,
                                seriesName = SHOW_NAME,
                                originalTitle = null,
                                overview = "",
                                sources = emptyList(),
                                indexNumber = 1,
                                episodes = emptyList(),
                                played = false,
                                favorite = false,
                                canPlay = true,
                                canDownload = false,
                                unplayedItemCount = null,
                                images = FindroidImages(),
                            )
                        }
                        "getSeasonForOrigin" -> {
                            val itemId = args[0] as UUID
                            val origin = args[1] as JellyfinOrigin
                            originSeasonReads += 1
                            originSeasonLookups += itemId to origin
                            originSeasons[itemId to origin]
                                ?: error("origin season unavailable: ${origin.serverId}")
                        }
                        else -> error("unexpected repository call: ${method.name}")
                    }
                },
            ) as JellyfinRepository
    }

    internal class RecordingTransfer : DownloadTransfer {
        var externalDownloads = 0
        var sourceIdForPath: (String) -> String? = { null }
        private val started = CopyOnWriteArrayList<String>()
        private val requests = CopyOnWriteArrayList<DownloadTransferRequest>()
        private var held = false
        private var releaseGate = CompletableDeferred<Unit>()

        fun holdDownloads() {
            held = true
            releaseGate = CompletableDeferred()
        }

        fun release() {
            held = false
            releaseGate.complete(Unit)
        }

        fun startedPaths(): List<String> = started.toList()

        fun startedSourceIds(): List<String> = started.mapNotNull { path -> sourceIdForPath(path) }

        fun requestSnapshot(): List<DownloadTransferRequest> = requests.toList()

        suspend fun awaitStarted(count: Int): Boolean =
            withTimeoutOrNull(2_000L) {
                while (started.size < count) delay(10L)
                true
            } ?: false

        override suspend fun download(request: DownloadTransferRequest): DownloadAttemptResult {
            requests += request
            val bytes =
                if (request.url == SUBTITLE_URL) {
                    externalDownloads += 1
                    SUBTITLE_BYTES
                } else {
                    started += request.target.path
                    request.target.parentFile?.mkdirs()
                    request.target.writeText("partial")
                    MEDIA_BYTES
                }
            if (held) releaseGate.await()
            request.target.parentFile?.mkdirs()
            request.target.writeBytes(bytes)
            return DownloadAttemptResult(
                downloadedBytes = bytes.size.toLong(),
                totalBytes = bytes.size.toLong(),
                validator = "etag",
            )
        }
    }

    internal class RecordingWorkGateway : DownloadWorkGateway {
        val enqueuedNames = mutableListOf<String>()
        val cancelledNames = mutableListOf<String>()
        val uniqueRequests = mutableListOf<Triple<String, ExistingWorkPolicy, OneTimeWorkRequest>>()

        override suspend fun uniqueWorkInfos(name: String): List<WorkInfo> = emptyList()

        override fun enqueueUnique(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest,
        ) {
            enqueuedNames += name
            uniqueRequests += Triple(name, policy, request)
        }

        override fun cancelUnique(name: String) {
            cancelledNames += name
        }

        override fun enqueue(request: OneTimeWorkRequest) {
            enqueuedNames += request.id.toString()
        }
    }

    internal class RecordingImageScheduler : DownloadImageFinalizationScheduler {
        var attempts = 0
        val requests = mutableListOf<Pair<String, Long>>()
        val parentItemIds = mutableListOf<UUID>()
        val parentOwners = mutableListOf<ImageDownloadParentOwner>()
        val cancelledParentItemIds = mutableListOf<UUID>()
        val cancelledParentOwners = mutableListOf<ImageDownloadParentOwner>()
        val activeParentOwners = CopyOnWriteArraySet<ImageDownloadParentOwner>()
        val activeParentItemIds: Set<UUID>
            get() = activeParentOwners.mapTo(mutableSetOf()) { it.itemId }

        var parentScheduled = CompletableDeferred<Unit>()
        private val scheduled = mutableSetOf<String>()

        override suspend fun schedule(item: FindroidItem, sourceId: String, generation: Long) {
            attempts += 1
            if (!scheduled.add("$sourceId|$generation")) return
            requests += sourceId to generation
        }

        override suspend fun scheduleParent(
            item: FindroidItem,
            owner: ImageDownloadParentOwner,
        ) {
            parentItemIds += item.id
            parentOwners += owner
            activeParentOwners += owner
            parentScheduled.complete(Unit)
        }

        override suspend fun cancelParent(owner: ImageDownloadParentOwner) {
            cancelledParentItemIds += owner.itemId
            cancelledParentOwners += owner
            activeParentOwners -= owner
        }

        fun resetParentObservations() {
            parentItemIds.clear()
            parentOwners.clear()
            cancelledParentItemIds.clear()
            cancelledParentOwners.clear()
            activeParentOwners.clear()
            parentScheduled = CompletableDeferred()
        }
    }

    private class FakeNotificationGateway : DownloadNotificationGateway {
        override fun buildForeground(task: DownloadTask?, activeCount: Int): Notification =
            allocate()

        override fun notifyForeground(task: DownloadTask?, activeCount: Int) = Unit

        override fun notifyCompleted(task: DownloadTask) = Unit

        override fun notifyFailed(task: DownloadTask) = Unit
    }

    internal class FakePreferences : SharedPreferences {
        var currentServer = SERVER_ID
        var currentServerReads = 0
        private val values = linkedMapOf<String, Any?>()
        private val listeners =
            CopyOnWriteArraySet<SharedPreferences.OnSharedPreferenceChangeListener>()

        override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

        override fun getString(key: String?, defValue: String?): String? =
            if (key == "pref_current_server") {
                currentServerReads += 1
                currentServer
            } else {
                defValue
            }

        override fun getStringSet(
            key: String?,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (values[key] as? MutableSet<String>) ?: defValues

        override fun getInt(key: String?, defValue: Int): Int = (values[key] as? Int) ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (values[key] as? Long) ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (values[key] as? Float) ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            (values[key] as? Boolean) ?: defValue

        override fun contains(key: String?): Boolean = values.containsKey(key)

        override fun edit(): SharedPreferences.Editor = FakeEditor(this)

        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners += listener
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?
        ) {
            if (listener != null) listeners -= listener
        }

        fun applyEdits(
            updated: Map<String, Any?>,
            removed: Set<String>,
            clear: Boolean,
        ) {
            val changed = linkedSetOf<String>()
            if (clear) {
                changed += values.keys
                values.clear()
            }
            removed.forEach { key -> if (values.remove(key) != null) changed += key }
            updated.forEach { (key, value) ->
                values[key] = value
                changed += key
            }
            changed.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this, key) } }
        }
    }

    private class FakeEditor(private val preferences: FakePreferences) : SharedPreferences.Editor {
        private val updated = linkedMapOf<String, Any?>()
        private val removed = linkedSetOf<String>()
        private var clear = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String>?,
        ): SharedPreferences.Editor {
            key?.let { updated[it] = values }
            return this
        }

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
            key?.let { updated[it] = value }
            return this
        }

        override fun remove(key: String?): SharedPreferences.Editor {
            key?.let { removed += it }
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        override fun commit(): Boolean {
            preferences.applyEdits(updated, removed, clear)
            updated.clear()
            removed.clear()
            clear = false
            return true
        }

        override fun apply() {
            commit()
        }
    }

    private class TestContext(private val root: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = root

        override fun getExternalFilesDirs(type: String?): Array<File> = arrayOf(root)
    }

    private companion object {
        val ITEM_ID: UUID = UUID(0L, 11L)
        val OTHER_ITEM_ID: UUID = UUID(0L, 15L)
        val SIBLING_ITEM_ID: UUID = UUID(0L, 17L)
        val USER_ID: UUID = UUID(0L, 12L)
        val OTHER_USER_ID: UUID = UUID(0L, 16L)
        val SHOW_ID: UUID = UUID(0L, 13L)
        val SEASON_ID: UUID = UUID(0L, 14L)
        const val SOURCE_ID = "source-recovery"
        const val SIBLING_SOURCE_ID = "source-sibling"
        const val SOURCE_NAME = "Recovery Source"
        const val ITEM_NAME = "Recovery Movie"
        const val SHOW_NAME = "Recovery Show"
        const val SEASON_NAME = "Recovery Season"
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

        fun testImageCacheStore(context: ContextWrapper): ImageCacheStore {
            val constructor =
                ImageCacheStore::class.java.declaredConstructors.first { it.parameterCount == 2 }
            constructor.isAccessible = true
            return constructor.newInstance(context, { null }) as ImageCacheStore
        }
    }
}
