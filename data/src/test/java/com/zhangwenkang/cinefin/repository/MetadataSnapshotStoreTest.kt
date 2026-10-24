package com.zhangwenkang.cinefin.repository

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createTempDirectory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.jellyfin.sdk.model.api.BaseItemDto
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataSnapshotStoreTest {

    @Test
    fun `content generation mismatch is a miss and removes stale generation file`() {
        val directory = Files.createTempDirectory("metadata-generation").toFile()
        try {
            val store = MetadataSnapshotStore(directory)
            val namespace = MetadataNamespace("server-a", "user-a")
            val generationZero = MetadataFence(namespace, contentGeneration = 0L)
            val generationOne = MetadataFence(namespace, contentGeneration = 1L)

            assertTrue(
                store.write(generationZero, "views", "old-generation")
                    is MetadataSnapshotWriteResult.Success
            )

            assertTrue(
                store.read(generationOne, "views", Long.MAX_VALUE)
                    is MetadataSnapshotReadResult.Miss
            )
            assertTrue(
                store.read(generationOne, "views", Long.MAX_VALUE)
                    is MetadataSnapshotReadResult.Miss
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `namespace invalidation removes only its own files`() {
        val directory = Files.createTempDirectory("metadata-namespace-clear").toFile()
        try {
            val store = MetadataSnapshotStore(directory)
            val namespaceA = MetadataNamespace("server-a", "user-a")
            val namespaceB = MetadataNamespace("server-b", "user-b")
            val fenceA = MetadataFence(namespaceA, 0L)
            val fenceB = MetadataFence(namespaceB, 0L)
            assertTrue(store.write(fenceA, "views", "a") is MetadataSnapshotWriteResult.Success)
            assertTrue(store.write(fenceB, "views", "b") is MetadataSnapshotWriteResult.Success)

            store.invalidateNamespace(namespaceA)

            assertTrue(
                store.read(fenceA, "views", Long.MAX_VALUE) is MetadataSnapshotReadResult.Miss
            )
            assertEquals(
                "b",
                (store.read(fenceB, "views", Long.MAX_VALUE) as MetadataSnapshotReadResult.Hit)
                    .value,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private val tempDirectory = createTempDirectory("metadata-snapshot-test").toFile()
    private val namespace = MetadataNamespace(serverId = "server-a", userId = "user-a")
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun tearDown() {
        tempDirectory.deleteRecursively()
    }

    @Test
    fun `SDK DTO round trip retains only display metadata`() {
        val store = MetadataSnapshotStore(tempDirectory)
        val item = item(name = "Movie", overview = "A plain overview")

        assertTrue(store.write(namespace, "item:1", item) is MetadataSnapshotWriteResult.Success)

        val hit =
            store.read(namespace, "item:1", maxAgeMs = 10_000) as MetadataSnapshotReadResult.Hit
        val decoded = hit.value as BaseItemDto
        assertEquals("Movie", decoded.name)
        assertEquals("A plain overview", decoded.overview)
        assertEquals(
            "poster-tag",
            decoded.imageTags?.get(org.jellyfin.sdk.model.api.ImageType.PRIMARY),
        )
    }

    @Test
    fun `SDK DTO list and controlled primitives round trip`() {
        val store = MetadataSnapshotStore(tempDirectory)
        val items =
            listOf(
                item(name = "First"),
                item(name = "Second", id = "22222222-2222-2222-2222-222222222222"),
            )

        assertTrue(
            store.write(namespace, "search:all:test", items) is MetadataSnapshotWriteResult.Success
        )
        assertTrue(store.write(namespace, "views", "text") is MetadataSnapshotWriteResult.Success)
        assertTrue(store.write(namespace, "nextup", true) is MetadataSnapshotWriteResult.Success)
        assertTrue(store.write(namespace, "count", 42) is MetadataSnapshotWriteResult.Success)

        val decodedItems =
            (store.read(namespace, "search:all:test", 10_000) as MetadataSnapshotReadResult.Hit)
                .value as List<*>
        assertEquals(listOf("First", "Second"), decodedItems.map { (it as BaseItemDto).name })
        assertEquals(
            "text",
            (store.read(namespace, "views", 10_000) as MetadataSnapshotReadResult.Hit).value,
        )
        assertEquals(
            true,
            (store.read(namespace, "nextup", 10_000) as MetadataSnapshotReadResult.Hit).value,
        )
        assertEquals(
            42,
            (store.read(namespace, "count", 10_000) as MetadataSnapshotReadResult.Hit).value,
        )
    }

    @Test
    fun `sensitive DTO fields never reach disk while ordinary URL text survives`() {
        val store = MetadataSnapshotStore(tempDirectory)
        val item =
            item(
                name = "Secret-bearing item",
                overview = "Documentation lives at https://docs.example.test/item",
            )

        assertTrue(
            store.write(namespace, "item:secret", item) is MetadataSnapshotWriteResult.Success
        )

        val file = requireNotNull(store.fileFor(namespace, "item:secret"))
        val persisted = file.readText()
        assertFalse(persisted.contains("MediaSources"))
        assertFalse(persisted.contains("DirectStreamUrl"))
        assertFalse(persisted.contains("TranscodingUrl"))
        assertFalse(persisted.contains("UserData"))
        assertFalse(persisted.contains("AccessToken"))
        assertFalse(persisted.contains("Authorization"))
        assertFalse(persisted.contains("api_key"))
        assertFalse(persisted.contains("/private/media/movie.mkv"))
        assertFalse(persisted.contains("/private/media/chapter.jpg"))
        assertFalse(persisted.contains("ImagePath"))
        assertFalse(persisted.contains("https://stream.example.test/movie"))
        assertFalse(persisted.contains("token-value"))
        assertTrue(persisted.contains("https://docs.example.test/item"))

        val decoded =
            (store.read(namespace, "item:secret", 10_000) as MetadataSnapshotReadResult.Hit).value
                as BaseItemDto
        assertNull(decoded.path)
        assertNull(decoded.mediaSources)
        assertNull(decoded.userData)
        assertEquals(
            "Documentation lives at https://docs.example.test/item",
            decoded.overview,
        )
    }

    @Test
    fun `truncated snapshot is a miss and is cleaned`() {
        val store = MetadataSnapshotStore(tempDirectory)
        writeString(store, "truncated", "payload")
        val file = requireNotNull(store.fileFor(namespace, "truncated"))
        file.writeText(file.readText().dropLast(8))

        val result = store.read(namespace, "truncated", 10_000)

        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.MALFORMED),
            result,
        )
        assertFalse(file.exists())
    }

    @Test
    fun `old schema is a miss and is cleaned`() {
        val store = MetadataSnapshotStore(tempDirectory)
        writeString(store, "old-schema", "payload")
        val file = requireNotNull(store.fileFor(namespace, "old-schema"))
        rewriteObject(file) {
            buildJsonObject {
                it.forEach { (key, value) -> put(key, value) }
                put("schemaVersion", 0)
            }
        }

        val result = store.read(namespace, "old-schema", 10_000)

        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.SCHEMA_MISMATCH),
            result,
        )
        assertFalse(file.exists())
    }

    @Test
    fun `checksum mismatch is a miss and is cleaned`() {
        val store = MetadataSnapshotStore(tempDirectory)
        writeString(store, "checksum", "original")
        val file = requireNotNull(store.fileFor(namespace, "checksum"))
        rewriteObject(file) {
            buildJsonObject {
                it.forEach { (key, value) -> put(key, value) }
                put("payload", JsonPrimitive("tampered"))
            }
        }

        val result = store.read(namespace, "checksum", 10_000)

        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.CHECKSUM_MISMATCH),
            result,
        )
        assertFalse(file.exists())
    }

    @Test
    fun `oversized on-disk snapshot is a miss and is cleaned before parsing`() {
        val store = MetadataSnapshotStore(tempDirectory, maxBytes = 128L)
        val file = requireNotNull(store.fileFor(namespace, "oversized"))
        Files.createDirectories(requireNotNull(file.parentFile).toPath())
        file.writeText("x".repeat(256))

        val result = store.read(namespace, "oversized", 10_000)

        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.UNREADABLE),
            result,
        )
        assertFalse(file.exists())
    }

    @Test
    fun `namespace and key mismatch fail closed independently`() {
        val store = MetadataSnapshotStore(tempDirectory)
        writeString(store, "source-a", "payload")
        val sourceFile = requireNotNull(store.fileFor(namespace, "source-a"))

        val otherKeyFile = requireNotNull(store.fileFor(namespace, "source-b"))
        Files.createDirectories(requireNotNull(otherKeyFile.parentFile).toPath())
        Files.copy(sourceFile.toPath(), otherKeyFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.KEY_MISMATCH),
            store.read(namespace, "source-b", 10_000),
        )
        assertFalse(otherKeyFile.exists())

        val otherNamespace = MetadataNamespace(serverId = "server-a", userId = "user-b")
        val otherNamespaceFile = requireNotNull(store.fileFor(otherNamespace, "source-a"))
        Files.createDirectories(requireNotNull(otherNamespaceFile.parentFile).toPath())
        Files.copy(
            sourceFile.toPath(),
            otherNamespaceFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.NAMESPACE_MISMATCH),
            store.read(otherNamespace, "source-a", 10_000),
        )
        assertFalse(otherNamespaceFile.exists())
    }

    @Test
    fun `TTL boundary is exclusive and expired file is cleaned`() {
        var now = 1_000L
        val store = MetadataSnapshotStore(tempDirectory, clock = { now })
        assertTrue(
            store.write(namespace, "ttl", "payload", savedAtEpochMs = 1_000L)
                is MetadataSnapshotWriteResult.Success
        )
        val file = requireNotNull(store.fileFor(namespace, "ttl"))

        now = 1_499L
        assertTrue(store.read(namespace, "ttl", maxAgeMs = 500L) is MetadataSnapshotReadResult.Hit)

        now = 1_500L
        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.EXPIRED),
            store.read(namespace, "ttl", maxAgeMs = 500L),
        )
        assertFalse(file.exists())
    }

    @Test
    fun `oldest savedAt is evicted when file count exceeds limit`() {
        val store =
            MetadataSnapshotStore(
                tempDirectory,
                clock = { 100L },
                maxFiles = 2,
                maxBytes = 1_000_000,
            )
        assertTrue(
            store.write(namespace, "oldest", "one", 1L) is MetadataSnapshotWriteResult.Success
        )
        assertTrue(
            store.write(namespace, "middle", "two", 2L) is MetadataSnapshotWriteResult.Success
        )
        assertTrue(
            store.write(namespace, "newest", "three", 3L) is MetadataSnapshotWriteResult.Success
        )

        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.NOT_FOUND),
            store.read(namespace, "oldest", 10_000),
        )
        assertTrue(store.read(namespace, "middle", 10_000) is MetadataSnapshotReadResult.Hit)
        assertTrue(store.read(namespace, "newest", 10_000) is MetadataSnapshotReadResult.Hit)
        assertEquals(2, snapshotFiles().size)
    }

    @Test
    fun `oldest savedAt is evicted when byte budget is exceeded`() {
        val store =
            MetadataSnapshotStore(
                tempDirectory,
                clock = { 100L },
                maxFiles = 10,
                maxBytes = 1_200L,
            )
        assertTrue(
            store.write(namespace, "oldest", "a".repeat(500), 1L)
                is MetadataSnapshotWriteResult.Success
        )
        assertTrue(
            store.write(namespace, "newest", "b".repeat(500), 2L)
                is MetadataSnapshotWriteResult.Success
        )

        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.NOT_FOUND),
            store.read(namespace, "oldest", 10_000),
        )
        assertTrue(store.read(namespace, "newest", 10_000) is MetadataSnapshotReadResult.Hit)
        assertTrue(snapshotFiles().sumOf { it.length() } <= 1_200L)
    }

    @Test
    fun `atomic write failure preserves previous snapshot and cleans part file`() {
        var fail = false
        val writer =
            object : MetadataSnapshotAtomicWriter {
                override fun writeAndReplace(partFile: File, targetFile: File, bytes: ByteArray) {
                    if (fail) {
                        Files.createDirectories(requireNotNull(partFile.parentFile).toPath())
                        partFile.writeBytes(bytes)
                        throw IOException("simulated rename failure")
                    }
                    AtomicSnapshotFileWriter.writeAndReplace(partFile, targetFile, bytes)
                }
            }
        val store = MetadataSnapshotStore(tempDirectory, atomicWriter = writer)
        assertTrue(
            store.write(namespace, "atomic", "previous") is MetadataSnapshotWriteResult.Success
        )
        fail = true

        val result = store.write(namespace, "atomic", "replacement")

        assertTrue(result is MetadataSnapshotWriteResult.Degraded)
        assertFalse((result as MetadataSnapshotWriteResult.Degraded).snapshotPersisted)
        assertEquals(
            "previous",
            (store.read(namespace, "atomic", 10_000) as MetadataSnapshotReadResult.Hit).value,
        )
        assertTrue(tempDirectory.walkTopDown().none { it.isFile && it.extension == "part" })
    }

    @Test
    fun `oversized snapshot degrades without creating a file`() {
        val store = MetadataSnapshotStore(tempDirectory, maxBytes = 128L)

        val result = store.write(namespace, "too-large", "x".repeat(256))

        assertEquals(
            MetadataSnapshotWriteResult.Degraded(
                reason = MetadataSnapshotWriteFailure.TOO_LARGE,
                snapshotPersisted = false,
            ),
            result,
        )
        assertFalse(requireNotNull(store.fileFor(namespace, "too-large")).exists())
    }

    @Test
    fun `writes for one namespace are serialized`() {
        val writer =
            object : MetadataSnapshotAtomicWriter {
                private val active = AtomicInteger()
                val maximumActive = AtomicInteger()

                override fun writeAndReplace(partFile: File, targetFile: File, bytes: ByteArray) {
                    val current = active.incrementAndGet()
                    maximumActive.updateAndGet { maxOf(it, current) }
                    try {
                        Thread.sleep(50)
                        AtomicSnapshotFileWriter.writeAndReplace(partFile, targetFile, bytes)
                    } finally {
                        active.decrementAndGet()
                    }
                }
            }
        val store = MetadataSnapshotStore(tempDirectory, atomicWriter = writer)
        val start = CountDownLatch(1)
        val threads =
            listOf("one", "two").map { key ->
                Thread {
                    start.await()
                    store.write(namespace, key, key)
                }
            }

        threads.forEach(Thread::start)
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, writer.maximumActive.get())
    }

    @Test
    fun `traversal-like namespace and key remain hashed inside store root`() {
        val store = MetadataSnapshotStore(tempDirectory)
        val hostileNamespace = MetadataNamespace(serverId = "..", userId = "..\\outside")
        val hostileKey = "../../outside/secret"
        val escapedFile = File(tempDirectory.parentFile, "outside/secret")

        assertTrue(
            store.write(hostileNamespace, hostileKey, "safe") is MetadataSnapshotWriteResult.Success
        )
        val file = requireNotNull(store.fileFor(hostileNamespace, hostileKey))

        assertFalse(escapedFile.exists())
        assertTrue(file.canonicalPath.startsWith(tempDirectory.canonicalPath + File.separator))
        assertEquals(64, requireNotNull(file.parentFile).name.length)
        assertEquals(64, file.nameWithoutExtension.length)
        assertEquals(
            "safe",
            (store.read(hostileNamespace, hostileKey, 10_000) as MetadataSnapshotReadResult.Hit)
                .value,
        )
    }

    @Test
    fun `blank identity or key fails closed without writing`() {
        val store = MetadataSnapshotStore(tempDirectory)
        val blankNamespace = MetadataNamespace(serverId = "", userId = "user-a")

        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.INVALID_IDENTITY),
            store.read(blankNamespace, "key", 10_000),
        )
        assertTrue(
            store.write(blankNamespace, "key", "value") is MetadataSnapshotWriteResult.Degraded
        )
        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.INVALID_KEY),
            store.read(namespace, "", 10_000),
        )
        assertNull(store.fileFor(blankNamespace, "key"))
        assertNull(store.fileFor(namespace, ""))
        assertFalse(File(tempDirectory, "metadata-snapshots").exists())
    }

    @Test
    fun `different namespaces and keys use different files`() {
        val store = MetadataSnapshotStore(tempDirectory)
        val otherNamespace = MetadataNamespace(serverId = "server-a", userId = "user-b")

        assertTrue(store.write(namespace, "same", "one") is MetadataSnapshotWriteResult.Success)
        assertTrue(
            store.write(otherNamespace, "same", "two") is MetadataSnapshotWriteResult.Success
        )
        assertNotEquals(
            store.fileFor(namespace, "same"),
            store.fileFor(otherNamespace, "same"),
        )
        assertEquals(
            "one",
            (store.read(namespace, "same", 10_000) as MetadataSnapshotReadResult.Hit).value,
        )
        assertEquals(
            "two",
            (store.read(otherNamespace, "same", 10_000) as MetadataSnapshotReadResult.Hit).value,
        )
    }

    @Test
    fun `single key delete removes only that namespace and key`() {
        val store = MetadataSnapshotStore(tempDirectory)
        val otherNamespace = MetadataNamespace(serverId = "server-a", userId = "user-b")
        assertTrue(
            store.write(namespace, "playlists", "old") is MetadataSnapshotWriteResult.Success
        )
        assertTrue(store.write(namespace, "views", "keep") is MetadataSnapshotWriteResult.Success)
        assertTrue(
            store.write(otherNamespace, "playlists", "other-user")
                is MetadataSnapshotWriteResult.Success
        )

        assertTrue(store.delete(namespace, "playlists"))
        assertFalse(store.delete(namespace, "playlists"))

        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.NOT_FOUND),
            store.read(namespace, "playlists", Long.MAX_VALUE),
        )
        assertEquals(
            "keep",
            (store.read(namespace, "views", Long.MAX_VALUE) as MetadataSnapshotReadResult.Hit)
                .value,
        )
        assertEquals(
            "other-user",
            (store.read(otherNamespace, "playlists", Long.MAX_VALUE)
                    as MetadataSnapshotReadResult.Hit)
                .value,
        )
    }

    @Test
    fun `single key delete keeps hostile keys inside the store root`() {
        val store = MetadataSnapshotStore(tempDirectory)
        val hostileNamespace = MetadataNamespace(serverId = "..", userId = "..\\outside")
        val hostileKey = "../../outside/secret"
        val escapedFile = File(tempDirectory.parentFile, "outside/secret")
        assertTrue(
            store.write(hostileNamespace, hostileKey, "safe") is MetadataSnapshotWriteResult.Success
        )

        assertTrue(store.delete(hostileNamespace, hostileKey))

        assertFalse(escapedFile.exists())
        assertEquals(
            MetadataSnapshotReadResult.Miss(MetadataSnapshotMissReason.NOT_FOUND),
            store.read(hostileNamespace, hostileKey, Long.MAX_VALUE),
        )
    }

    private fun writeString(store: MetadataSnapshotStore, key: String, value: String) {
        assertTrue(store.write(namespace, key, value) is MetadataSnapshotWriteResult.Success)
    }

    private fun rewriteObject(file: File, transform: (JsonObject) -> JsonObject) {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        file.writeText(transform(root).toString())
    }

    private fun snapshotFiles(): List<File> =
        tempDirectory.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()

    private fun item(
        name: String,
        id: String = "11111111-1111-1111-1111-111111111111",
        overview: String = "Overview",
    ): BaseItemDto {
        val source =
            """
            {
              "Name": "$name",
              "OriginalTitle": "$name original",
              "Id": "$id",
              "Type": "Movie",
              "Overview": "$overview",
              "ImageTags": { "Primary": "poster-tag" },
              "Chapters": [
                {
                  "StartPositionTicks": 0,
                  "Name": "Intro",
                  "ImageDateModified": "2026-10-24T00:00:00",
                  "ImagePath": "/private/media/chapter.jpg",
                  "ImageTag": "chapter-tag"
                }
              ],
              "Path": "/private/media/movie.mkv",
              "MediaSources": [
                {
                  "Protocol": "File",
                  "Path": "/private/media/movie.mkv",
                  "Type": "Default",
                  "IsRemote": false,
                  "ReadAtNativeFramerate": false,
                  "IgnoreDts": false,
                  "IgnoreIndex": false,
                  "GenPtsInput": false,
                  "SupportsTranscoding": true,
                  "SupportsDirectStream": true,
                  "SupportsDirectPlay": true,
                  "IsInfiniteStream": false,
                  "RequiresOpening": false,
                  "RequiresClosing": false,
                  "RequiresLooping": false,
                  "SupportsProbing": true,
                  "TranscodingSubProtocol": "hls",
                  "HasSegments": false,
                  "DirectStreamUrl": "https://stream.example.test/movie",
                  "TranscodingUrl": "https://stream.example.test/movie/transcode"
                }
              ],
              "UserData": {
                "PlaybackPositionTicks": 123456789,
                "PlayCount": 2,
                "IsFavorite": true,
                "Played": false,
                "Key": "user-data-key",
                "ItemId": "$id"
              },
              "AccessToken": "token-value",
              "Authorization": "Bearer token-value",
              "api_key": "token-value"
            }
            """
                .trimIndent()
        return json.decodeFromJsonElement(BaseItemDto.serializer(), Json.parseToJsonElement(source))
    }
}
