package com.zhangwenkang.cinefin.music.data

import java.io.ByteArrayInputStream
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExchangeAdapterTest {
    private val serverId = "server-a"
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000401")
    private val serverItemId = UUID.fromString("00000000-0000-0000-0000-000000000402")
    private val localItemId = UUID.fromString("00000000-0000-0000-0000-000000000403")
    private val unresolvedId = UUID.fromString("00000000-0000-0000-0000-000000000404")

    @Test
    fun jsonRoundTrip_preservesNameOrderIdentitiesSnapshotsAndPlaceholders() {
        val document =
            PlaylistExchangeDocument(
                name = "夜间, Drive",
                entries =
                    listOf(
                        PlaylistExchangeEntry(
                            track = serverTrack(serverItemId),
                            unavailable = true,
                        ),
                        PlaylistExchangeEntry(
                            track = localTrack(localItemId),
                            unavailable = false,
                        ),
                        PlaylistExchangeEntry(
                            track = unresolvedTrack(unresolvedId),
                            unavailable = true,
                        ),
                    ),
            )

        val decoded =
            PlaylistExchangeAdapter.decodeJson(PlaylistExchangeAdapter.encodeJson(document))

        assertEquals(document, decoded)
        assertEquals(
            listOf(TrackRef.Server::class, TrackRef.Local::class, TrackRef.Unresolved::class),
            decoded.entries.map { it.track.ref::class },
        )
    }

    @Test
    fun jsonRejectsUnknownVersionMissingFieldsAndTruncation() {
        val encoded = PlaylistExchangeAdapter.encodeJson(sampleDocument())

        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeJson(
                encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2")
            )
        }
        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeJson(encoded.replace("\"albumName\":\"Album\",", ""))
        }
        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeJson(encoded.dropLast(5))
        }
        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeJson("""{"format":"aurorama-playlist"}""")
        }
    }

    @Test
    fun jsonRejectsNonSequentialPositionsAndDuplicateExplicitIdentities() {
        val encoded = PlaylistExchangeAdapter.encodeJson(twoTrackDocument())
        val badOrder = encoded.replaceFirst("\"position\":0", "\"position\":1")

        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeJson(badOrder)
        }
        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeJson(
                PlaylistExchangeAdapter.encodeJson(
                    PlaylistExchangeDocument(
                        name = "Duplicate",
                        entries =
                            listOf(
                                PlaylistExchangeEntry(serverTrack(serverItemId), false),
                                PlaylistExchangeEntry(serverTrack(serverItemId), false),
                            ),
                    )
                )
            )
        }
    }

    @Test
    fun jsonRejectsAmbiguousIdentityFields() {
        val encoded = PlaylistExchangeAdapter.encodeJson(sampleDocument())

        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeJson(
                encoded.replaceFirst("\"localLibraryId\":null", "\"localLibraryId\":7")
            )
        }
    }

    @Test
    fun jsonRejectsEntryLimitAndOversizedInput() {
        val tooMany =
            PlaylistExchangeDocument(
                name = "Large",
                entries =
                    List(PlaylistExchangeAdapter.MAX_ENTRIES + 1) { index ->
                        PlaylistExchangeEntry(
                            track = localTrack(UUID.nameUUIDFromBytes("item-$index".toByteArray())),
                            unavailable = false,
                        )
                    },
            )

        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeJson(
                PlaylistExchangeAdapter.encodeJson(tooMany, enforceLimits = false)
            )
        }
        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.readBoundedUtf8(
                ByteArrayInputStream(ByteArray(PlaylistExchangeAdapter.MAX_FILE_BYTES + 1))
            )
        }
    }

    @Test
    fun m3u8RecoversOnlyExplicitAuroramaIdentitiesAndKeepsUnprovenEntriesAsPlaceholders() {
        val m3u8 =
            """
            #EXTM3U
            #EXT-X-AURORAMA-PLAYLIST:v1;name=Road%20Trip
            #EXTINF:90,First
            #EXT-X-AURORAMA-TRACK:v1;kind=SERVER;serverId=server-a;userId=$userId;itemId=$serverItemId;unavailable=1;ticks=900000000
            aurorama://server/track
            #EXTINF:120,UUID-like title $localItemId
            /music/$localItemId.mp3
            #EXTINF:30,Local
            #EXT-X-AURORAMA-TRACK:v1;kind=LOCAL;localLibraryId=7;itemId=$localItemId;unavailable=0;ticks=300000000
            content://local/track
            """
                .trimIndent()

        val decoded = PlaylistExchangeAdapter.decodeM3u8(m3u8)

        assertEquals("Road Trip", decoded.name)
        assertEquals(3, decoded.entries.size)
        assertEquals(TrackRef.Server(serverId, userId, serverItemId), decoded.entries[0].track.ref)
        assertTrue(decoded.entries[0].unavailable)
        assertTrue(decoded.entries[1].track.ref is TrackRef.Unresolved)
        assertTrue(decoded.entries[1].unavailable)
        assertEquals("UUID-like title $localItemId", decoded.entries[1].track.snapshot.displayName)
        assertEquals(TrackRef.Local(7, localItemId), decoded.entries[2].track.ref)
        assertEquals(900_000_000L, decoded.entries[0].track.snapshot.runtimeTicks)
        assertEquals(300_000_000L, decoded.entries[2].track.snapshot.runtimeTicks)
    }

    @Test
    fun m3u8ExportRoundTripPreservesOrderExplicitIdentityAndPlaceholders() {
        val document =
            PlaylistExchangeDocument(
                name = "交换",
                entries =
                    listOf(
                        PlaylistExchangeEntry(serverTrack(serverItemId), true),
                        PlaylistExchangeEntry(unresolvedTrack(unresolvedId), true),
                        PlaylistExchangeEntry(localTrack(localItemId), false),
                    ),
            )

        val decoded =
            PlaylistExchangeAdapter.decodeM3u8(PlaylistExchangeAdapter.encodeM3u8(document))

        assertEquals(document.name, decoded.name)
        assertEquals(document.entries.map { it.track.ref }, decoded.entries.map { it.track.ref })
        assertEquals(
            document.entries.map { it.unavailable },
            decoded.entries.map { it.unavailable },
        )
        assertEquals(
            document.entries.map { it.track.snapshot },
            decoded.entries.map { it.track.snapshot },
        )
    }

    @Test
    fun malformedM3u8AndDuplicateExplicitIdentityRejectWholeDocument() {
        val badHeader = "#EXTINF:10,Title\n/x.mp3"
        val missingExtinf =
            """
            #EXTM3U
            #EXT-X-AURORAMA-TRACK:v1;kind=SERVER;serverId=s;userId=$userId;itemId=$serverItemId
            aurorama://server/track
            """
                .trimIndent()
        val duplicate =
            """
            #EXTM3U
            #EXTINF:10,A
            #EXT-X-AURORAMA-TRACK:v1;kind=SERVER;serverId=s;userId=$userId;itemId=$serverItemId
            a
            #EXTINF:20,B
            #EXT-X-AURORAMA-TRACK:v1;kind=SERVER;serverId=s;userId=$userId;itemId=$serverItemId
            b
            """
                .trimIndent()
        val invalidTicks =
            """
            #EXTM3U
            #EXTINF:10,A
            #EXT-X-AURORAMA-TRACK:v1;kind=SERVER;serverId=s;userId=$userId;itemId=$serverItemId;ticks=not-a-number
            a
            """
                .trimIndent()

        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeM3u8(badHeader)
        }
        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeM3u8(missingExtinf)
        }
        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeM3u8(duplicate)
        }
        assertThrows(PlaylistExchangeException::class.java) {
            PlaylistExchangeAdapter.decodeM3u8(invalidTicks)
        }
    }

    @Test
    fun importCopyNameIsDeterministicAndAvoidsEveryExistingName() {
        assertEquals(
            "Drive",
            PlaylistExchangeAdapter.importCopyName("Drive", emptySet()),
        )
        assertEquals(
            "Drive (导入 2)",
            PlaylistExchangeAdapter.importCopyName("Drive", setOf("Drive")),
        )
        assertEquals(
            "Drive (导入 3)",
            PlaylistExchangeAdapter.importCopyName(
                "Drive",
                setOf("Drive", "Drive (导入 2)"),
            ),
        )
    }

    private fun sampleDocument() =
        PlaylistExchangeDocument(
            name = "Sample",
            entries = listOf(PlaylistExchangeEntry(serverTrack(serverItemId), false)),
        )

    private fun twoTrackDocument() =
        PlaylistExchangeDocument(
            name = "Two",
            entries =
                listOf(
                    PlaylistExchangeEntry(serverTrack(serverItemId), false),
                    PlaylistExchangeEntry(localTrack(localItemId), false),
                ),
        )

    private fun serverTrack(itemId: UUID) =
        LocalPlaylistTrack(
            ref = TrackRef.Server(serverId, userId, itemId),
            snapshot =
                PlaylistTrackSnapshot(
                    displayName = "Server title",
                    albumName = "Album",
                    artist = "Artist",
                    imageUri = "https://image.example/a.jpg",
                    mediaSourceUri = null,
                    runtimeTicks = 900_000_000L,
                ),
        )

    private fun localTrack(itemId: UUID) =
        LocalPlaylistTrack(
            ref = TrackRef.Local(libraryId = 7L, itemId = itemId),
            snapshot =
                PlaylistTrackSnapshot(
                    displayName = "Local title",
                    albumName = "Album",
                    artist = null,
                    imageUri = null,
                    mediaSourceUri = "content://library/track",
                    runtimeTicks = 300_000_000L,
                ),
        )

    private fun unresolvedTrack(placeholderKey: UUID) =
        LocalPlaylistTrack(
            ref = TrackRef.Unresolved(placeholderKey),
            snapshot =
                PlaylistTrackSnapshot(
                    displayName = "Unproven",
                    albumName = "",
                    artist = "Unknown",
                    imageUri = null,
                    mediaSourceUri = null,
                    runtimeTicks = 0L,
                ),
        )
}
