package com.zhangwenkang.cinefin.player.local.presentation

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerItem
import com.zhangwenkang.cinefin.player.core.domain.models.PlayerSubtitleSource
import com.zhangwenkang.cinefin.player.local.subtitle.SideloadedSubtitleStore
import com.zhangwenkang.cinefin.player.local.subtitle.SubtitleOverlayState
import com.zhangwenkang.cinefin.player.local.subtitle.shouldSuppressSecondaryForMpvPrimarySelection
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSubtitleRoutingTest {

    private val item =
        PlayerItem(
            name = "测试片",
            itemId = UUID.fromString("0b834979-9e2e-8c13-9014-783cfd83e7ed"),
            mediaSourceId = "source",
            playbackPosition = 0L,
            subtitleSources =
                listOf(
                    PlayerSubtitleSource(
                        index = 4,
                        title = "简日双语",
                        language = "zh-Hans",
                        uri = "https://server/Subtitles/4/Stream.ass",
                        codec = "ass",
                    )
                ),
        )

    @Test
    fun `mpv 内核让位给 libass，不启用自研覆盖层`() {
        val sources = playerSubtitleSourcesForBackend(PlayerViewModel.PLAYER_BACKEND_MPV, item)

        assertTrue(sources.isEmpty())
    }

    @Test
    fun `ExoPlayer 内核仍走自研字幕清单`() {
        val sources =
            playerSubtitleSourcesForBackend(PlayerViewModel.PLAYER_BACKEND_EXOPLAYER, item)

        assertEquals(listOf(4), sources.map { it.index })
    }

    @Test
    fun `mpv 主字幕映射不到源时立即抑制次字幕`() {
        assertTrue(
            shouldSuppressSecondaryForMpvPrimarySelection(
                selectedTrackId = 7,
                mappedSourceIndex = null,
            )
        )
        assertFalse(
            shouldSuppressSecondaryForMpvPrimarySelection(
                selectedTrackId = 7,
                mappedSourceIndex = 4,
            )
        )
        assertFalse(
            shouldSuppressSecondaryForMpvPrimarySelection(
                selectedTrackId = null,
                mappedSourceIndex = null,
            )
        )
    }

    @Test
    fun `缺少 DeliveryUrl 的已选文本轨走 Native 而不是 Auto`() {
        val source =
            PlayerSubtitleSource(
                index = 7,
                title = "原生文本",
                language = "zh-Hans",
                uri = "",
                codec = "subrip",
            )

        val routing =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(source),
                    primaryIndex = source.index,
                )
            )

        assertTrue(routing is SubtitleRouting.Native)
        assertEquals(source.index, (routing as SubtitleRouting.Native).source.index)
    }

    @Test
    fun `已选文本轨加载失败时确定性回退 Native`() {
        val source =
            PlayerSubtitleSource(
                index = 8,
                title = "失败文本",
                language = "en",
                uri = "https://server/Subtitles/8/Stream.srt",
                codec = "subrip",
            )

        val routing =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(source),
                    primaryIndex = source.index,
                    primaryRenderFailed = true,
                )
            )

        assertTrue(routing is SubtitleRouting.Native)
        assertEquals(source.index, (routing as SubtitleRouting.Native).source.index)
    }

    @Test
    fun `加载失败文本只选择同一源 id 的原生轨`() {
        val source =
            PlayerSubtitleSource(
                index = 8,
                title = "失败文本",
                language = "en",
                uri = "https://server/Subtitles/8/Stream.srt",
                codec = "subrip",
            )
        val sameLanguageOtherSource = nativeTextGroup("another-source", "en")
        val exactSource = nativeTextGroup(nativeSubtitleSourceTrackId(source.index), "en")
        val routing =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(source),
                    primaryIndex = source.index,
                    primaryRenderFailed = true,
                )
            )

        val update =
            applySubtitleRoutingParameters(
                parameters = TrackSelectionParameters.DEFAULT,
                routing = routing,
                tracks = Tracks(listOf(sameLanguageOtherSource, exactSource)),
            )

        assertTrue(update.parameters.overrides.containsKey(exactSource.mediaTrackGroup))
        assertFalse(
            update.parameters.overrides.containsKey(sameLanguageOtherSource.mediaTrackGroup)
        )
        assertFalse(C.TRACK_TYPE_TEXT in update.parameters.disabledTrackTypes)
        assertNull(update.nativeUnavailableReason)
    }

    @Test
    fun `同语言但缺少稳定源 id 时 Native 失败关闭`() {
        val source =
            PlayerSubtitleSource(
                index = 7,
                title = "原生文本",
                language = "zh-Hans",
                uri = "",
                codec = "subrip",
            )
        val routing =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(source),
                    primaryIndex = source.index,
                )
            )

        val update =
            applySubtitleRoutingParameters(
                parameters = TrackSelectionParameters.DEFAULT,
                routing = routing,
                tracks = Tracks(listOf(nativeTextGroup("unknown", "zh-Hans"))),
            )

        assertTrue(update.parameters.overrides.isEmpty())
        assertTrue(C.TRACK_TYPE_TEXT in update.parameters.disabledTrackTypes)
        assertEquals(
            PlayerViewModel.SubtitlePrimaryUnavailableReason.NativeSourceIdentityUnavailable,
            update.nativeUnavailableReason,
        )
    }

    @Test
    fun `图形源不得借用同语言原生文本轨`() {
        val source =
            PlayerSubtitleSource(
                index = 9,
                title = "PGS 中文",
                language = "zh-Hans",
                uri = "",
                codec = "pgs",
                isGraphic = true,
            )
        val routing =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(source),
                    primaryIndex = source.index,
                )
            )

        val update =
            applySubtitleRoutingParameters(
                parameters = TrackSelectionParameters.DEFAULT,
                routing = routing,
                tracks = Tracks(listOf(nativeTextGroup("text", "zh-Hans"))),
            )

        assertTrue(update.parameters.overrides.isEmpty())
        assertTrue(C.TRACK_TYPE_TEXT in update.parameters.disabledTrackTypes)
        assertEquals(
            PlayerViewModel.SubtitlePrimaryUnavailableReason.NativeSourceIdentityUnavailable,
            update.nativeUnavailableReason,
        )
    }

    @Test
    fun `同一源 id 出现多条原生轨时失败关闭`() {
        val source =
            PlayerSubtitleSource(
                index = 6,
                title = "重复身份",
                language = "en",
                uri = "https://server/Subtitles/6/Stream.srt",
                codec = "subrip",
            )
        val routing =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(source),
                    primaryIndex = source.index,
                    primaryRenderFailed = true,
                )
            )
        val duplicateId = nativeSubtitleSourceTrackId(source.index)

        val update =
            applySubtitleRoutingParameters(
                parameters = TrackSelectionParameters.DEFAULT,
                routing = routing,
                tracks =
                    Tracks(
                        listOf(
                            nativeTextGroup(duplicateId, "en"),
                            nativeTextGroup(duplicateId, "en"),
                        )
                    ),
            )

        assertTrue(update.parameters.overrides.isEmpty())
        assertTrue(C.TRACK_TYPE_TEXT in update.parameters.disabledTrackTypes)
        assertEquals(
            PlayerViewModel.SubtitlePrimaryUnavailableReason.NativeSourceIdentityUnavailable,
            update.nativeUnavailableReason,
        )
    }

    @Test
    fun `原生字幕配置为服务器与侧载来源绑定稳定 id`() {
        val server =
            PlayerSubtitleSource(
                index = 4,
                title = "服务器字幕",
                language = "zh-Hans",
                uri = "https://server/Subtitles/4/Stream.srt",
                codec = "subrip",
                isExternal = true,
            )
        val sid =
            PlayerSubtitleSource(
                index = SideloadedSubtitleStore.INDEX_BASE,
                title = "侧载字幕",
                language = "en",
                uri = "file:///subtitle.srt",
                codec = "subrip",
                isExternal = true,
            )
        val item =
            PlayerItem(
                name = "测试片",
                itemId = UUID.fromString("0b834979-9e2e-8c13-9014-783cfd83e7ed"),
                mediaSourceId = "source",
                playbackPosition = 0L,
                subtitleSources = listOf(server, sid),
            )

        val configurations = subtitleConfigurationsForNativeFallback(item, "External")

        assertEquals(
            listOf(
                nativeSubtitleSourceTrackId(server.index),
                nativeSubtitleSourceTrackId(sid.index),
            ),
            configurations.map { it.id },
        )
    }

    @Test
    fun `直放与转码的可渲染文本轨共享 Managed 不变量`() {
        val direct =
            PlayerSubtitleSource(
                index = 4,
                title = "简体",
                language = "zh-Hans",
                uri = "https://server/Videos/item/Subtitles/4/Stream.srt",
                codec = "subrip",
            )
        val transcode =
            direct.copy(uri = "https://server/Videos/item/Subtitles/4/Stream.srt?transcode=true")

        val directRouting =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(direct),
                    primaryIndex = direct.index,
                    primaryManaged = true,
                )
            )
        val transcodeRouting =
            resolveSubtitleRouting(
                SubtitleOverlayState(
                    sources = listOf(transcode),
                    primaryIndex = transcode.index,
                    primaryManaged = true,
                )
            )

        assertEquals(SubtitleRouting.Managed, directRouting)
        assertEquals(directRouting, transcodeRouting)
    }

    private fun nativeTextGroup(id: String, language: String): Tracks.Group =
        Tracks.Group(
            TrackGroup(
                Format.Builder()
                    .setId(id)
                    .setLabel("subtitle")
                    .setLanguage(language)
                    .setSampleMimeType("application/x-subrip")
                    .build()
            ),
            true,
            intArrayOf(C.FORMAT_HANDLED),
            booleanArrayOf(false),
        )
}
