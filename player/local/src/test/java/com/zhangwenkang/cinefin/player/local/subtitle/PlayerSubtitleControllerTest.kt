package com.zhangwenkang.cinefin.player.local.subtitle

import android.content.SharedPreferences
import com.zhangwenkang.cinefin.language.LanguageMatcher
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleAppearance
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleLayout
import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.local.domain.PlayerExtraPreferences
import com.zhangwenkang.cinefin.player.local.presentation.SubtitleRouting
import com.zhangwenkang.cinefin.player.local.presentation.resolveSubtitleRouting
import com.zhangwenkang.cinefin.settings.domain.AppPreferences
import com.zhangwenkang.cinefin.settings.domain.Constants
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** controller 级换路语义：主→次 / 次→主清空原路，但不把「用户显式关闭」写脏。 */
class PlayerSubtitleControllerTest {

    private val sources =
        listOf(
            source(index = 3, title = "简体", language = "zh-Hans"),
            source(index = 4, title = "English", language = "en", codec = "subrip"),
        )

    private val sideloadSources =
        listOf(
            source(
                index = SideloadedSubtitleStore.INDEX_BASE,
                title = "English",
                language = "en",
                codec = "subrip",
            ),
            source(
                index = SideloadedSubtitleStore.INDEX_BASE + 1,
                title = "简体",
                language = "zh-Hans",
                codec = "subrip",
            ),
        )

    private fun source(
        index: Int,
        title: String,
        language: String,
        codec: String = "ass",
        uri: String = "file:///cinefin-test-$index.$codec",
    ) =
        PlayerSubtitleSource(
            index = index,
            title = title,
            language = language,
            uri = uri,
            codec = codec,
        )

    private fun scope(
        serverId: String? = "server-a",
        userId: String? = "user-a",
        itemId: String = "item-a",
    ): SubtitleSelectionScope =
        requireNotNull(
            SubtitleSelectionScope.create(
                serverId = serverId,
                userId = userId,
                itemId = itemId,
            )
        )

    private fun PlaybackFixture.reset(
        itemScope: SubtitleSelectionScope,
        autoSecondary: Boolean = false,
    ) {
        controller.reset(
            mediaId = itemScope.itemId,
            sources = sources,
            selectionScope = itemScope,
            autoSecondary = autoSecondary,
        )
    }

    private fun PlaybackFixture.resetSideload(
        itemScope: SubtitleSelectionScope,
        defaultPrimaryIndex: Int? = null,
    ) {
        controller.reset(
            mediaId = itemScope.itemId,
            sources = sideloadSources,
            selectionScope = itemScope,
            autoSecondary = false,
            defaultPrimaryIndex = defaultPrimaryIndex,
        )
    }

    private fun fixture(
        preferences: AppPreferences = AppPreferences(FakeSharedPreferences())
    ): PlaybackFixture {
        val scope = CoroutineScope(Job())
        return PlaybackFixture(scope, PlayerSubtitleController(scope, preferences), preferences)
    }

    private data class PlaybackFixture(
        val scope: CoroutineScope,
        val controller: PlayerSubtitleController,
        val preferences: AppPreferences,
    )

    @Test
    fun `主轨移到次路再移回主路都不写用户关闭态`() {
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        try {
            controller.reset("media", sources, autoSecondary = false)
            val primary = controller.overlayState.value.primaryIndex
            assertNotNull(primary)

            controller.assignSecondary(primary!!)
            val secondaryOnly = controller.overlayState.value
            assertNull(secondaryOnly.primaryIndex)
            assertEquals(primary, secondaryOnly.secondaryIndex)
            assertFalse(secondaryOnly.disabledByUser)

            controller.assignPrimary(primary)
            val primaryOnly = controller.overlayState.value
            assertEquals(primary, primaryOnly.primaryIndex)
            assertNull(primaryOnly.secondaryIndex)
            assertFalse(primaryOnly.disabledByUser)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `仅图形字幕也会自动成为主轨`() {
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        val graphic =
            PlayerSubtitleSource(
                index = 9,
                title = "PGS 中文",
                language = "zh-Hans",
                uri = "https://server/Subtitles/9/Stream.sup",
                codec = "pgs",
                isGraphic = true,
            )
        try {
            controller.reset("media-graphic", listOf(graphic), autoSecondary = false)

            assertEquals(9, controller.overlayState.value.primaryIndex)
            assertFalse(controller.overlayState.value.primaryManaged)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `失效图形次轨恢复为显式关闭而不是伪手动空态`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope()
        val graphic =
            PlayerSubtitleSource(
                index = 9,
                title = "PGS 中文",
                language = "zh-Hans",
                uri = "https://server/Subtitles/9/Stream.sup",
                codec = "pgs",
                isGraphic = true,
            )
        preferences.setValue(
            PlayerExtraPreferences.subtitleManualSelectionsV2,
            ScopedSubtitleSelection(
                    scope = itemScope,
                    primaryIndex = ScopedSubtitleSelection.NO_INDEX,
                    secondaryIndex = 9,
                )
                .encode(),
        )
        val fixture = fixture(preferences)
        try {
            fixture.controller.reset(
                mediaId = itemScope.itemId,
                sources = listOf(graphic),
                selectionScope = itemScope,
                autoSecondary = false,
            )

            val state = fixture.controller.overlayState.value
            assertNull(state.primaryIndex)
            assertNull(state.secondaryIndex)
            assertTrue(state.disabledByUser)
            assertEquals(
                SubtitleSecondaryUnavailableReason.GraphicOnly,
                state.secondaryUnavailableReason,
            )
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun `缺少 DeliveryUrl 的次字幕请求保持关闭并给出原因`() {
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        val nativeOnly =
            source(
                index = 7,
                title = "仅原生文本",
                language = "en",
                codec = "subrip",
                uri = "",
            )
        try {
            controller.reset(
                "media-native-secondary",
                sources + nativeOnly,
                autoSecondary = false,
            )
            controller.assignSecondary(nativeOnly.index)

            val state = controller.overlayState.value
            assertNull(state.secondaryIndex)
            assertEquals(
                SubtitleSecondaryUnavailableReason.DeliveryUrlMissing,
                state.secondaryUnavailableReason,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `次字幕下载失败公开原因且不再按 ready 接管内核`() = runBlocking {
        val missingFile =
            File.createTempFile("cinefin-subtitle-missing-", ".srt").apply { delete() }
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        val failed =
            source(
                index = 7,
                title = "下载失败",
                language = "en",
                codec = "subrip",
                uri = missingFile.toURI().toString(),
            )
        try {
            controller.reset(
                "media-secondary-download-failed",
                listOf(failed),
                autoSecondary = false,
            )
            controller.assignSecondary(failed.index)

            val state =
                withTimeout(5_000) {
                    controller.overlayState.first { it.secondaryUnavailableReason != null }
                }

            assertEquals("DownloadFailed", state.secondaryUnavailableReason?.name)
            assertEquals(failed.index, state.secondaryIndex)
            assertTrue(state.secondaryCues.isEmpty())
            assertFalse(state.secondaryManaged)
            assertFalse(state.loading)
            assertEquals(SubtitleRouting.Off, resolveSubtitleRouting(state))
        } finally {
            scope.cancel()
            missingFile.delete()
        }
    }

    @Test
    fun `次字幕解析为空公开原因而不是静默空画面`() = runBlocking {
        val emptySubtitle =
            File.createTempFile("cinefin-subtitle-empty-", ".srt").apply {
                writeText("not a subtitle", Charsets.UTF_8)
            }
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        val failed =
            source(
                index = 8,
                title = "空内容",
                language = "zh-Hans",
                codec = "subrip",
                uri = emptySubtitle.toURI().toString(),
            )
        try {
            controller.reset("media-secondary-empty", listOf(failed), autoSecondary = false)
            controller.assignSecondary(failed.index)

            val state =
                withTimeout(5_000) {
                    controller.overlayState.first { it.secondaryUnavailableReason != null }
                }

            assertEquals("InvalidContent", state.secondaryUnavailableReason?.name)
            assertEquals(failed.index, state.secondaryIndex)
            assertTrue(state.secondaryCues.isEmpty())
            assertFalse(state.secondaryManaged)
            assertFalse(state.loading)
            assertEquals(SubtitleRouting.Off, resolveSubtitleRouting(state))
        } finally {
            scope.cancel()
            emptySubtitle.delete()
        }
    }

    @Test
    fun `仅切换文本主字幕保留失败次字幕原因，次轨重分配后才清除`() = runBlocking {
        val validSubtitle =
            """
            1
            00:00:01,000 --> 00:00:03,000
            重分配成功
            """
                .trimIndent()
        val preferences = AppPreferences(FakeSharedPreferences())
        preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
        val failed = source(index = 7, title = "无效内容", language = "en", codec = "subrip")
        val reassignedSecondary =
            source(index = 5, title = "可重分配", language = "ja", codec = "subrip")
        val scope = CoroutineScope(Job())
        val controller =
            PlayerSubtitleController(scope, preferences) { selected ->
                if (selected.index == failed.index) "" else validSubtitle
            }
        try {
            controller.reset(
                "media-secondary-invalid-primary-change",
                sources + failed + reassignedSecondary,
                autoSecondary = false,
            )
            controller.assignSecondary(failed.index)
            withTimeout(5_000) {
                controller.overlayState.first {
                    it.secondaryUnavailableReason ==
                        SubtitleSecondaryUnavailableReason.InvalidContent
                }
            }

            controller.assignPrimary(4)
            val primaryChanged = controller.overlayState.value
            assertEquals(4, primaryChanged.primaryIndex)
            assertEquals(failed.index, primaryChanged.secondaryIndex)
            assertEquals(
                SubtitleSecondaryUnavailableReason.InvalidContent,
                primaryChanged.secondaryUnavailableReason,
            )
            assertTrue(primaryChanged.secondaryCues.isEmpty())
            assertFalse(primaryChanged.secondaryManaged)

            controller.assignSecondary(reassignedSecondary.index)
            val reassigned =
                withTimeout(5_000) {
                    controller.overlayState.first {
                        it.secondaryIndex == reassignedSecondary.index && it.secondaryManaged
                    }
                }
            assertNull(reassigned.secondaryUnavailableReason)
            assertEquals("重分配成功", reassigned.secondaryCues.single().text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `仅切换图形主字幕保留失败次字幕原因，关闭次轨后才清除`() = runBlocking {
        val validSubtitle =
            """
            1
            00:00:01,000 --> 00:00:03,000
            主字幕
            """
                .trimIndent()
        val preferences = AppPreferences(FakeSharedPreferences())
        preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
        val failed = source(index = 7, title = "下载失败", language = "en", codec = "subrip")
        val graphic =
            PlayerSubtitleSource(
                index = 9,
                title = "PGS 中文",
                language = "zh-Hans",
                uri = "https://server/Subtitles/9/Stream.sup",
                codec = "pgs",
                isGraphic = true,
            )
        val scope = CoroutineScope(Job())
        val controller =
            PlayerSubtitleController(scope, preferences) { selected ->
                if (selected.index == failed.index) {
                    error("download failed")
                }
                validSubtitle
            }
        try {
            controller.reset(
                "media-secondary-download-primary-change",
                sources + failed + graphic,
                autoSecondary = false,
            )
            controller.assignSecondary(failed.index)
            withTimeout(5_000) {
                controller.overlayState.first {
                    it.secondaryUnavailableReason ==
                        SubtitleSecondaryUnavailableReason.DownloadFailed
                }
            }

            controller.selectPrimary(graphic.index)
            val primaryChanged = controller.overlayState.value
            assertEquals(graphic.index, primaryChanged.primaryIndex)
            assertEquals(failed.index, primaryChanged.secondaryIndex)
            assertEquals(
                SubtitleSecondaryUnavailableReason.DownloadFailed,
                primaryChanged.secondaryUnavailableReason,
            )
            assertTrue(primaryChanged.secondaryCues.isEmpty())
            assertFalse(primaryChanged.secondaryManaged)

            controller.selectSecondary(null)
            val secondaryClosed = controller.overlayState.value
            assertNull(secondaryClosed.secondaryIndex)
            assertNull(secondaryClosed.secondaryUnavailableReason)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `失败次字幕可直接重试并在成功后清除原因`() = runBlocking {
        var attempts = 0
        val preferences = AppPreferences(FakeSharedPreferences())
        preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
        val source = source(index = 9, title = "可重试", language = "en", codec = "subrip")
        val scope = CoroutineScope(Job())
        val controller =
            PlayerSubtitleController(scope, preferences) {
                attempts++
                if (attempts == 1) {
                    error("first attempt failed")
                }
                """
                1
                00:00:01,000 --> 00:00:03,000
                重试成功
                """
                    .trimIndent()
            }
        try {
            controller.reset("media-secondary-retry", listOf(source), autoSecondary = false)
            controller.assignSecondary(source.index)
            withTimeout(5_000) {
                controller.overlayState.first {
                    it.secondaryUnavailableReason ==
                        SubtitleSecondaryUnavailableReason.DownloadFailed
                }
            }

            controller.assignSecondary(source.index)
            val recovered =
                withTimeout(5_000) {
                    controller.overlayState.first {
                        it.secondaryIndex == source.index && it.secondaryManaged
                    }
                }

            assertEquals(2, attempts)
            assertNull(recovered.secondaryUnavailableReason)
            assertEquals("重试成功", recovered.secondaryCues.single().text)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `过期次字幕加载失败不得覆盖新选择`() = runBlocking {
        val staleStarted = CompletableDeferred<Unit>()
        val releaseStale = CompletableDeferred<Unit>()
        val current = source(index = 8, title = "新选择", language = "zh-Hans", codec = "subrip")
        val stale = source(index = 9, title = "旧选择", language = "en", codec = "subrip")
        val scope = CoroutineScope(Job())
        val controller =
            PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences())) { selected ->
                when (selected.index) {
                    stale.index -> {
                        staleStarted.complete(Unit)
                        releaseStale.await()
                        error("stale load failed")
                    }
                    current.index ->
                        """
                        1
                        00:00:01,000 --> 00:00:03,000
                        当前字幕
                        """
                            .trimIndent()
                    else -> error("unexpected source")
                }
            }
        try {
            controller.reset(
                "media-secondary-stale",
                listOf(current, stale),
                autoSecondary = false,
            )
            controller.assignSecondary(stale.index)
            withTimeout(5_000) { staleStarted.await() }
            controller.assignSecondary(current.index)
            val currentState =
                withTimeout(5_000) {
                    controller.overlayState.first {
                        it.secondaryIndex == current.index && it.secondaryManaged
                    }
                }

            releaseStale.complete(Unit)
            Thread.sleep(50)
            val finalState = controller.overlayState.value
            assertEquals(current.index, finalState.secondaryIndex)
            assertTrue(finalState.secondaryManaged)
            assertNull(finalState.secondaryUnavailableReason)
            assertEquals(currentState.secondaryCues, finalState.secondaryCues)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `持久次轨不存在时保留有效主轨并给出过期原因`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope()
        preferences.setValue(
            PlayerExtraPreferences.subtitleManualSelectionsV2,
            ScopedSubtitleSelection(
                    scope = itemScope,
                    primaryIndex = 3,
                    secondaryIndex = 99,
                )
                .encode(),
        )
        val fixture = fixture(preferences)
        try {
            fixture.controller.reset(
                mediaId = itemScope.itemId,
                sources = sources,
                selectionScope = itemScope,
                autoSecondary = false,
            )

            val state = fixture.controller.overlayState.value
            assertEquals(3, state.primaryIndex)
            assertNull(state.secondaryIndex)
            assertEquals(
                SubtitleSecondaryUnavailableReason.StaleTrack,
                state.secondaryUnavailableReason,
            )
            assertFalse(state.disabledByUser)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun `自动次字幕按语言记忆选择且不抢主轨`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        preferences.setValue(preferences.secondarySubtitleLanguages, "en")
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, preferences)
        try {
            controller.reset("media-auto-dual", sources, autoSecondary = true)

            val state = controller.overlayState.value
            assertEquals(3, state.primaryIndex)
            assertEquals(4, state.secondaryIndex)
            assertTrue(state.primaryIndex != state.secondaryIndex)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `字幕延迟与外观修改即时发布且重建后持久读取`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, preferences)
        val appearance =
            SubtitleAppearance(
                primary = SubtitleStyle(sizePercent = 125),
                secondary = SubtitleStyle(sizePercent = 85),
                layout = SubtitleLayout(gapDp = 6, bottomPercent = 12),
            )
        try {
            controller.reset("media-settings", sources, autoSecondary = false)
            controller.setDelay(300)
            controller.updateAppearance(appearance)

            val live = controller.overlayState.value
            assertEquals(300L, live.delayMs)
            assertEquals(appearance.primary, live.primaryStyle)
            assertEquals(appearance.secondary, live.secondaryStyle)
            assertEquals(appearance.layout, live.layout)
        } finally {
            scope.cancel()
        }

        val readerScope = CoroutineScope(Job())
        val reader = PlayerSubtitleController(readerScope, preferences)
        try {
            reader.reset("media-settings", sources, autoSecondary = false)
            val restored = reader.overlayState.value
            assertEquals(300L, restored.delayMs)
            assertEquals(appearance.primary, restored.primaryStyle)
            assertEquals(appearance.secondary, restored.secondaryStyle)
            assertEquals(appearance.layout, restored.layout)
        } finally {
            readerScope.cancel()
        }
    }

    @Test
    fun `直放与转码使用相同字幕序号时产出相同 cue 和 managed 状态`() = runBlocking {
        val repeatedCue = "同一不变量"
        val files =
            List(2) { index ->
                File.createTempFile("cinefin-subtitle-route-$index-", ".srt").apply {
                    writeText(
                        """
                        1
                        00:00:01,000 --> 00:00:03,000
                        $repeatedCue
                        """
                            .trimIndent(),
                        Charsets.UTF_8,
                    )
                }
            }
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        try {
            val direct =
                source(
                        index = 4,
                        title = "简体",
                        language = "zh-Hans",
                        codec = "subrip",
                        uri = files[0].toURI().toString(),
                    )
                    .copy(isExternal = false)
            controller.reset("media-direct", listOf(direct), autoSecondary = false)
            val directState =
                withTimeout(5_000) { controller.overlayState.first { it.primaryCues.isNotEmpty() } }

            val transcode =
                direct.copy(
                    uri = files[1].toURI().toString(),
                    isExternal = true,
                )
            controller.reset("media-transcode", listOf(transcode), autoSecondary = false)
            val transcodeState =
                withTimeout(5_000) { controller.overlayState.first { it.primaryCues.isNotEmpty() } }

            assertEquals(direct.index, transcodeState.primaryIndex)
            assertEquals(directState.primaryCues, transcodeState.primaryCues)
            assertEquals(directState.primaryManaged, transcodeState.primaryManaged)
            assertEquals(repeatedCue, transcodeState.primaryCues.single().text)
        } finally {
            scope.cancel()
            files.forEach(File::delete)
        }
    }

    @Test
    fun `选中本地字幕后公开 overlay 产出真实 cue 与 managed`() = runBlocking {
        val subtitleFile =
            File.createTempFile("cinefin-subtitle-", ".srt").apply {
                writeText(
                    """
                    1
                    00:00:01,000 --> 00:00:03,000
                    真正上屏
                    """
                        .trimIndent(),
                    Charsets.UTF_8,
                )
            }
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        try {
            val source =
                source(
                    index = 4,
                    title = "简体",
                    language = "zh-Hans",
                    codec = "subrip",
                    uri = subtitleFile.toURI().toString(),
                )
            controller.reset("media-real-cue", listOf(source), autoSecondary = false)

            val state =
                withTimeout(5_000) { controller.overlayState.first { it.primaryCues.isNotEmpty() } }
            assertEquals(4, state.primaryIndex)
            assertTrue(state.primaryManaged)
            assertEquals("真正上屏", state.primaryCues.single().text)
        } finally {
            scope.cancel()
            subtitleFile.delete()
        }
    }

    @Test
    fun `显式关闭主字幕仍写用户关闭态`() {
        val scope = CoroutineScope(Job())
        val controller = PlayerSubtitleController(scope, AppPreferences(FakeSharedPreferences()))
        try {
            controller.reset("media", sources, autoSecondary = false)
            controller.selectPrimary(null)

            assertTrue(controller.overlayState.value.disabledByUser)
            assertNull(controller.overlayState.value.primaryIndex)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `手动 mpv 主字幕会写入主语言优先级`() {
        val scope = CoroutineScope(Job())
        val preferences = AppPreferences(FakeSharedPreferences())
        val controller = PlayerSubtitleController(scope, preferences)
        try {
            controller.reset("media", sources, autoSecondary = false)
            controller.rememberPrimaryLanguage(4)

            val priority =
                LanguageMatcher.parsePriority(
                    preferences.getValue(preferences.preferredSubtitleLanguages),
                    emptyList(),
                )
            assertEquals("en", priority.first())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `不同影片的单片主次选择互不覆盖`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val first = fixture(preferences)
        val second = fixture(preferences)
        try {
            first.reset(scope(itemId = "item-a"))
            first.controller.assignPrimary(3)
            first.controller.assignSecondary(4)

            second.reset(scope(itemId = "item-b"))
            second.controller.assignPrimary(4)
            second.controller.assignSecondary(3)

            val firstAgain = fixture(preferences)
            val secondAgain = fixture(preferences)
            try {
                firstAgain.reset(scope(itemId = "item-a"))
                secondAgain.reset(scope(itemId = "item-b"))

                assertEquals(3, firstAgain.controller.overlayState.value.primaryIndex)
                assertEquals(4, firstAgain.controller.overlayState.value.secondaryIndex)
                assertEquals(4, secondAgain.controller.overlayState.value.primaryIndex)
                assertEquals(3, secondAgain.controller.overlayState.value.secondaryIndex)
            } finally {
                firstAgain.scope.cancel()
                secondAgain.scope.cancel()
            }
        } finally {
            first.scope.cancel()
            second.scope.cancel()
        }
    }

    @Test
    fun `服务器或用户不同时不恢复另一命名空间的手动选择`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
        val writer = fixture(preferences)
        try {
            writer.reset(scope(serverId = "server-a", userId = "user-a", itemId = "item-a"))
            writer.controller.assignPrimary(4)
        } finally {
            writer.scope.cancel()
        }

        val same = fixture(preferences)
        val otherUser = fixture(preferences)
        val otherServer = fixture(preferences)
        try {
            same.reset(scope(serverId = "server-a", userId = "user-a", itemId = "item-a"))
            otherUser.reset(scope(serverId = "server-a", userId = "user-b", itemId = "item-a"))
            otherServer.reset(scope(serverId = "server-b", userId = "user-a", itemId = "item-a"))

            assertEquals(4, same.controller.overlayState.value.primaryIndex)
            assertNull(otherUser.controller.overlayState.value.primaryIndex)
            assertNull(otherServer.controller.overlayState.value.primaryIndex)
        } finally {
            same.scope.cancel()
            otherUser.scope.cancel()
            otherServer.scope.cancel()
        }
    }

    @Test
    fun `主次选择与显式关闭在重建后分别恢复`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope()
        val writer = fixture(preferences)
        try {
            writer.reset(itemScope)
            writer.controller.assignPrimary(3)
            writer.controller.assignSecondary(4)
            writer.controller.selectPrimary(null)
        } finally {
            writer.scope.cancel()
        }

        val reader = fixture(preferences)
        try {
            reader.reset(itemScope)
            assertNull(reader.controller.overlayState.value.primaryIndex)
            assertNull(reader.controller.overlayState.value.secondaryIndex)
            assertTrue(reader.controller.overlayState.value.disabledByUser)
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `首条侧载默认项不得覆盖已恢复的单片主次选择`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope(itemId = "item-b")
        val writer = fixture(preferences)
        try {
            writer.resetSideload(itemScope)
            writer.controller.assignPrimary(SideloadedSubtitleStore.INDEX_BASE + 1)
            writer.controller.assignSecondary(SideloadedSubtitleStore.INDEX_BASE)
        } finally {
            writer.scope.cancel()
        }

        val reader = fixture(preferences)
        try {
            reader.resetSideload(
                itemScope,
                defaultPrimaryIndex = SideloadedSubtitleStore.INDEX_BASE,
            )

            assertEquals(
                SideloadedSubtitleStore.INDEX_BASE + 1,
                reader.controller.overlayState.value.primaryIndex,
            )
            assertEquals(
                SideloadedSubtitleStore.INDEX_BASE,
                reader.controller.overlayState.value.secondaryIndex,
            )
            assertEquals(
                ScopedSubtitleSelection(
                    scope = itemScope,
                    primaryIndex = SideloadedSubtitleStore.INDEX_BASE + 1,
                    secondaryIndex = SideloadedSubtitleStore.INDEX_BASE,
                ),
                ScopedSubtitleSelection.decodeAll(
                        reader.preferences.getValue(
                            PlayerExtraPreferences.subtitleManualSelectionsV2
                        )
                    )
                    .single(),
            )
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `首条侧载默认项不得复活已恢复的显式关闭`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope(itemId = "item-b")
        val writer = fixture(preferences)
        try {
            writer.resetSideload(itemScope)
            writer.controller.selectPrimary(null)
        } finally {
            writer.scope.cancel()
        }

        val reader = fixture(preferences)
        try {
            reader.resetSideload(
                itemScope,
                defaultPrimaryIndex = SideloadedSubtitleStore.INDEX_BASE,
            )

            assertNull(reader.controller.overlayState.value.primaryIndex)
            assertNull(reader.controller.overlayState.value.secondaryIndex)
            assertTrue(reader.controller.overlayState.value.disabledByUser)
            assertEquals(
                ScopedSubtitleSelection(
                    scope = itemScope,
                    primaryIndex = ScopedSubtitleSelection.NO_INDEX,
                    secondaryIndex = ScopedSubtitleSelection.NO_INDEX,
                ),
                ScopedSubtitleSelection.decodeAll(
                        reader.preferences.getValue(
                            PlayerExtraPreferences.subtitleManualSelectionsV2
                        )
                    )
                    .single(),
            )
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `无手动选择时首条侧载仍作为默认主字幕`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope(itemId = "item-b")
        val reader = fixture(preferences)
        try {
            reader.resetSideload(
                itemScope,
                defaultPrimaryIndex = SideloadedSubtitleStore.INDEX_BASE,
            )

            assertEquals(
                SideloadedSubtitleStore.INDEX_BASE,
                reader.controller.overlayState.value.primaryIndex,
            )
            assertFalse(reader.controller.hasManualSelectionForCurrentItem())
            assertTrue(
                reader.preferences
                    .getValue(PlayerExtraPreferences.subtitleManualSelectionsV2)
                    .isEmpty()
            )
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `自动清除后用首条侧载重建仍不恢复手动覆盖`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemA = scope(itemId = "item-a")
        val itemB = scope(itemId = "item-b")
        val writerA = fixture(preferences)
        val writerB = fixture(preferences)
        try {
            writerA.resetSideload(itemA)
            writerA.controller.assignPrimary(SideloadedSubtitleStore.INDEX_BASE + 1)
            writerA.controller.assignSecondary(SideloadedSubtitleStore.INDEX_BASE)
            writerB.resetSideload(itemB)
            writerB.controller.assignPrimary(SideloadedSubtitleStore.INDEX_BASE)
            writerB.controller.assignSecondary(SideloadedSubtitleStore.INDEX_BASE + 1)
        } finally {
            writerA.scope.cancel()
            writerB.scope.cancel()
        }

        val auto = fixture(preferences)
        try {
            auto.resetSideload(itemA)
            auto.controller.autoSelectAndForgetManual()
        } finally {
            auto.scope.cancel()
        }
        val onlyB =
            ScopedSubtitleSelection(
                scope = itemB,
                primaryIndex = SideloadedSubtitleStore.INDEX_BASE,
                secondaryIndex = SideloadedSubtitleStore.INDEX_BASE + 1,
            )
        assertEquals(
            listOf(onlyB),
            ScopedSubtitleSelection.decodeAll(
                preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2)
            ),
        )

        val reopened = fixture(preferences)
        try {
            reopened.resetSideload(
                itemA,
                defaultPrimaryIndex = SideloadedSubtitleStore.INDEX_BASE,
            )

            assertEquals(
                SideloadedSubtitleStore.INDEX_BASE,
                reopened.controller.overlayState.value.primaryIndex,
            )
            assertFalse(reopened.controller.hasManualSelectionForCurrentItem())
            assertEquals(
                listOf(onlyB),
                ScopedSubtitleSelection.decodeAll(
                    preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2)
                ),
            )
        } finally {
            reopened.scope.cancel()
        }
    }

    @Test
    fun `坏记录逐条忽略且仍能恢复同集合中的好记录`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope()
        val good =
            ScopedSubtitleSelection(
                itemScope,
                primaryIndex = 4,
                secondaryIndex = 3,
            )
        preferences.setValue(
            PlayerExtraPreferences.subtitleManualSelectionsV2,
            "bad\nserver-a|user-a|item-x|oops|-1\n${good.encode()}\n",
        )
        val reader = fixture(preferences)
        try {
            reader.reset(itemScope)

            assertEquals(4, reader.controller.overlayState.value.primaryIndex)
            assertEquals(3, reader.controller.overlayState.value.secondaryIndex)
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `旧单槽仅在当前 item 匹配时迁移并清除`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope(itemId = "item-a")
        preferences.setValue(
            PlayerExtraPreferences.subtitleManualSelection,
            SubtitleManualSelection("item-a", primaryIndex = 4, secondaryIndex = 3).encode(),
        )
        val reader = fixture(preferences)
        try {
            reader.reset(itemScope)

            assertEquals(4, reader.controller.overlayState.value.primaryIndex)
            assertEquals(3, reader.controller.overlayState.value.secondaryIndex)
            assertEquals(
                "",
                reader.preferences.getValue(PlayerExtraPreferences.subtitleManualSelection),
            )
            val migrated =
                ScopedSubtitleSelection.decodeAll(
                    reader.preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2)
                )
            assertEquals(listOf(ScopedSubtitleSelection(itemScope, 4, 3)), migrated)
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `旧单槽 item 不匹配时不恢复也不冒充当前片`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
        preferences.setValue(
            PlayerExtraPreferences.subtitleManualSelection,
            SubtitleManualSelection("item-b", primaryIndex = 4, secondaryIndex = 3).encode(),
        )
        val reader = fixture(preferences)
        try {
            reader.reset(scope(itemId = "item-a"))

            assertNull(reader.controller.overlayState.value.primaryIndex)
            assertEquals(
                "",
                reader.preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2),
            )
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `全局模式语言变化不改写已有单片记录且单片优先于全局关`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope()
        val writer = fixture(preferences)
        try {
            writer.reset(itemScope)
            writer.controller.assignPrimary(4)
            writer.controller.assignSecondary(3)
        } finally {
            writer.scope.cancel()
        }
        val savedRaw = preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2)

        preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
        preferences.setValue(preferences.preferredSubtitleLanguages, "fr,en")
        preferences.setValue(preferences.secondarySubtitleLanguages, "ja")
        val reader = fixture(preferences)
        try {
            reader.reset(itemScope)

            assertEquals(4, reader.controller.overlayState.value.primaryIndex)
            assertEquals(3, reader.controller.overlayState.value.secondaryIndex)
            assertEquals(
                savedRaw,
                preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2),
            )
            assertEquals(Constants.SubtitleMode.OFF, preferences.getValue(preferences.subtitleMode))
            assertEquals("fr,en", preferences.getValue(preferences.preferredSubtitleLanguages))
            assertEquals("ja", preferences.getValue(preferences.secondarySubtitleLanguages))
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `全局关闭不覆盖已有单片主次且保留持久化行`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemA = scope(itemId = "item-a")
        val itemB = scope(itemId = "item-b")
        val writerA = fixture(preferences)
        val writerB = fixture(preferences)
        try {
            writerA.reset(itemA)
            writerA.controller.assignPrimary(4)
            writerA.controller.assignSecondary(3)
            writerB.reset(itemB)
            writerB.controller.assignPrimary(3)
            writerB.controller.assignSecondary(4)
        } finally {
            writerB.scope.cancel()
        }
        val savedRaw = preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2)

        preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
        try {
            assertFalse(writerA.controller.applyGlobalSelection(autoSecondary = true))
            val state = writerA.controller.overlayState.value
            assertEquals(4, state.primaryIndex)
            assertEquals(3, state.secondaryIndex)
            assertFalse(state.disabledByUser)
            assertEquals(
                savedRaw,
                preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2),
            )
        } finally {
            writerA.scope.cancel()
        }
    }

    @Test
    fun `无手动覆盖时全局关闭只清有效状态且不伪造持久化行`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope()
        val controller = fixture(preferences)
        try {
            controller.reset(itemScope)
            assertTrue(
                preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2).isEmpty()
            )

            preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
            assertTrue(controller.controller.applyGlobalSelection(autoSecondary = true))

            val state = controller.controller.overlayState.value
            assertNull(state.primaryIndex)
            assertNull(state.secondaryIndex)
            assertTrue(state.disabledByUser)
            assertTrue(
                preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2).isEmpty()
            )
        } finally {
            controller.scope.cancel()
        }
    }

    @Test
    fun `全局自动与语言变化不覆盖已有单片主次`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemScope = scope()
        val writer = fixture(preferences)
        try {
            writer.reset(itemScope)
            writer.controller.assignPrimary(4)
            writer.controller.assignSecondary(3)
        } finally {
            writer.scope.cancel()
        }
        val savedRaw = preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2)

        val reader = fixture(preferences)
        try {
            reader.reset(itemScope)
            preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.AUTO)
            preferences.setValue(preferences.preferredSubtitleLanguages, "fr,en")
            preferences.setValue(preferences.secondarySubtitleLanguages, "ja")

            assertFalse(reader.controller.applyGlobalSelection(autoSecondary = true))
            val state = reader.controller.overlayState.value
            assertEquals(4, state.primaryIndex)
            assertEquals(3, state.secondaryIndex)
            assertEquals(
                savedRaw,
                preferences.getValue(PlayerExtraPreferences.subtitleManualSelectionsV2),
            )
        } finally {
            reader.scope.cancel()
        }
    }

    @Test
    fun `自动选择只清除当前片并保留其他片覆盖`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        val itemA = scope(itemId = "item-a")
        val itemB = scope(itemId = "item-b")
        val writerA = fixture(preferences)
        val writerB = fixture(preferences)
        try {
            writerA.reset(itemA)
            writerA.controller.assignPrimary(3)
            writerA.controller.assignSecondary(4)
            writerB.reset(itemB)
            writerB.controller.assignPrimary(4)
            writerB.controller.assignSecondary(3)
        } finally {
            writerA.scope.cancel()
            writerB.scope.cancel()
        }

        preferences.setValue(preferences.subtitleMode, Constants.SubtitleMode.OFF)
        val auto = fixture(preferences)
        try {
            auto.reset(itemA)
            auto.controller.autoSelectAndForgetManual(autoSecondary = true)
        } finally {
            auto.scope.cancel()
        }

        val readerA = fixture(preferences)
        val readerB = fixture(preferences)
        try {
            readerA.reset(itemA)
            readerB.reset(itemB)

            assertNull(readerA.controller.overlayState.value.primaryIndex)
            assertNull(readerA.controller.overlayState.value.secondaryIndex)
            assertEquals(4, readerB.controller.overlayState.value.primaryIndex)
            assertEquals(3, readerB.controller.overlayState.value.secondaryIndex)
        } finally {
            readerA.scope.cancel()
            readerB.scope.cancel()
        }
    }

    @Test
    fun `字幕选择迁移不影响全局外观与延迟`() {
        val preferences = AppPreferences(FakeSharedPreferences())
        preferences.setValue(preferences.playerSubtitleAppearanceMigratedV2, true)
        preferences.setValue(preferences.playerSubtitleDelayMs, 250L)
        preferences.setValue(preferences.playerSubtitlePrimarySizePercent, 117)
        val item = fixture(preferences)
        try {
            item.reset(scope())
            item.controller.assignPrimary(4)
            item.controller.autoSelectAndForgetManual()

            assertEquals(250L, preferences.getValue(preferences.playerSubtitleDelayMs))
            assertEquals(117, preferences.getValue(preferences.playerSubtitlePrimarySizePercent))
        } finally {
            item.scope.cancel()
        }
    }
}

/** JUnit-only 测试用的内存 SharedPreferences；只实现 AppPreferences 实际读取 / 写入的入口。 */
private class FakeSharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    override fun getStringSet(
        key: String?,
        defValues: MutableSet<String>?,
    ): MutableSet<String> =
        (values[key] as? Set<*>)?.filterIsInstance<String>()?.toMutableSet()
            ?: defValues
            ?: mutableSetOf()

    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = FakeEditor(values)

    override fun registerOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener?
    ) = Unit
}

private class FakeEditor(private val values: MutableMap<String, Any?>) : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private val removed = mutableSetOf<String>()

    override fun putString(key: String?, value: String?): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun putStringSet(
        key: String?,
        values: MutableSet<String>?,
    ): SharedPreferences.Editor = also { pending[key.orEmpty()] = values?.toSet() }

    override fun putInt(key: String?, value: Int): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun putLong(key: String?, value: Long): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = also {
        pending[key.orEmpty()] = value
    }

    override fun remove(key: String?): SharedPreferences.Editor = also { removed += key.orEmpty() }

    override fun clear(): SharedPreferences.Editor = also {
        pending.clear()
        removed.addAll(values.keys)
    }

    override fun commit(): Boolean {
        removed.forEach(values::remove)
        values.putAll(pending)
        pending.clear()
        removed.clear()
        return true
    }

    override fun apply() {
        commit()
    }
}
