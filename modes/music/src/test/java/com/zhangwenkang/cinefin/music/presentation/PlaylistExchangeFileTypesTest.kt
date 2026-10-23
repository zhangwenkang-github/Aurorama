package com.zhangwenkang.cinefin.music.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExchangeFileTypesTest {
    @Test
    fun importPickerAdvertisesJsonAndM3u8MimeTypes() {
        val mimeTypes = PlaylistExchangeFileTypes.importMimeTypes

        assertTrue("application/json" in mimeTypes)
        assertTrue("audio/x-mpegurl" in mimeTypes)
        assertTrue("application/vnd.apple.mpegurl" in mimeTypes)
    }

    @Test
    fun formatDetectionUsesContentMarkerInsteadOfExtensionOrTitle() {
        assertEquals(
            PlaylistExchangeFormat.JSON,
            PlaylistExchangeFileTypes.detect(
                """
                {"format":"aurorama-playlist"}
                """
                    .trimIndent()
            ),
        )
        assertEquals(
            PlaylistExchangeFormat.M3U8,
            PlaylistExchangeFileTypes.detect("\uFEFF\n#EXTM3U\n#EXTINF:1,Title"),
        )
        assertNull(PlaylistExchangeFileTypes.detect("#EXTINF:1,UUID-like.mp3"))
    }

    @Test
    fun exportFileIdentityHasExpectedMimeAndSafeExtension() {
        assertEquals("application/json", PlaylistExchangeFormat.JSON.mimeType)
        assertEquals("json", PlaylistExchangeFormat.JSON.extension)
        assertEquals("audio/x-mpegurl", PlaylistExchangeFormat.M3U8.mimeType)
        assertEquals("m3u8", PlaylistExchangeFormat.M3U8.extension)
        assertEquals(
            "Night_Drive_.json",
            PlaylistExchangeFileTypes.suggestedFileName(
                "Night/Drive:*?",
                PlaylistExchangeFormat.JSON,
            ),
        )
    }
}
