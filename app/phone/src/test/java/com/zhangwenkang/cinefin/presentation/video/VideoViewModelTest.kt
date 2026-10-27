package com.zhangwenkang.cinefin.presentation.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoViewModelTest {

    @Test
    fun videoCacheMustReloadAfterOnlineToOfflineSourceSwitch() {
        assertFalse(
            shouldReuseVideoCache(
                cachedSource = VideoLibrarySource.Online,
                requestedSource = VideoLibrarySource.Offline,
                loaded = true,
                sameTemporaryLibrary = true,
                force = false,
                loadedAtMs = 9_999L,
                nowMs = 10_000L,
            )
        )
    }

    @Test
    fun freshCacheStillReusesOnlyWithinTheSameSource() {
        assertTrue(
            shouldReuseVideoCache(
                cachedSource = VideoLibrarySource.Online,
                requestedSource = VideoLibrarySource.Online,
                loaded = true,
                sameTemporaryLibrary = true,
                force = false,
                loadedAtMs = 9_999L,
                nowMs = 10_000L,
            )
        )
    }

    @Test
    fun videoCacheMustReloadAfterOfflineToOnlineSourceSwitch() {
        assertFalse(
            shouldReuseVideoCache(
                cachedSource = VideoLibrarySource.Offline,
                requestedSource = VideoLibrarySource.Online,
                loaded = true,
                sameTemporaryLibrary = true,
                force = false,
                loadedAtMs = 9_999L,
                nowMs = 10_000L,
            )
        )
    }

    @Test
    fun staleOnlineLoadResultCannotOverwriteOfflineSource() {
        val onlineRequest = VideoLoadRequest(serial = 1L, source = VideoLibrarySource.Online)
        val offlineRequest = VideoLoadRequest(serial = 2L, source = VideoLibrarySource.Offline)

        assertFalse(
            isCurrentVideoLoad(
                request = onlineRequest,
                currentSerial = 2L,
                currentSource = VideoLibrarySource.Offline,
            )
        )
        assertTrue(
            isCurrentVideoLoad(
                request = offlineRequest,
                currentSerial = 2L,
                currentSource = VideoLibrarySource.Offline,
            )
        )
    }
}
