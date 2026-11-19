package com.zhangwenkang.cinefin.player.local.engine

import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle
import com.zhangwenkang.cinefin.player.core.playback.EngineCapabilities
import com.zhangwenkang.cinefin.player.core.playback.EngineTrack
import com.zhangwenkang.cinefin.player.core.playback.EngineTrackType
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackEngine] 的接口面契约（W2-T4；W4-T3a-1 扩到 15 个动词）。
 *
 * 两类证据：编译期（下面这个 fake 必须能完整实现接口）与反射期（方法名 / 参数形态一旦漂移就红）。
 */
class PlaybackEngineContractTest {

    /** 编译期契约：接口只有这些成员，实现方不需要额外的抽象方法。 */
    private class FakeEngine(
        override val capabilities: EngineCapabilities =
            EngineCapabilities(
                canPlayHls = true,
                supportsSoftwareDecode = true,
                supportsSubtitleStyling = true,
                supportsAudioDelay = true,
                supportsSideloadedSubtitles = true,
                supportsShuffleMode = true,
                supportsTrackEnumeration = true,
                supportsSubtitleDelay = true,
            ),
        override val backendName: String = "fake",
    ) : PlaybackEngine {
        val calls = mutableListOf<String>()
        val tracks = mutableListOf<EngineTrack>()
        var selected: String? = null
        var subtitleDelayMs: Long = 0L
        var audioDelayMs: Long = 0L

        override fun prepare() {
            calls += "prepare"
        }

        override fun play() {
            calls += "play"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seekTo($positionMs)"
        }

        override fun seekTo(itemIndex: Int, positionMs: Long) {
            calls += "seekTo($itemIndex,$positionMs)"
        }

        override fun setRate(rate: Float) {
            calls += "setRate($rate)"
        }

        override fun release() {
            calls += "release"
        }

        override fun availableTracks(type: EngineTrackType): List<EngineTrack> = tracks.filter {
            it.type == type
        }

        override fun selectedTrack(type: EngineTrackType): String? = selected

        override fun selectTrack(type: EngineTrackType, trackId: String?) {
            calls += "selectTrack($type,$trackId)"
        }

        override fun setSubtitleDelay(delayMs: Long) {
            subtitleDelayMs = delayMs
        }

        override fun getSubtitleDelay(): Long = subtitleDelayMs

        override fun setAudioDelay(delayMs: Long) {
            audioDelayMs = delayMs
        }

        override fun getAudioDelay(): Long = audioDelayMs

        override fun setSubtitleStyle(style: SubtitleStyle) {
            calls += "setSubtitleStyle(${style.sizePercent})"
        }
    }

    @Test
    fun `接口成员就是契约表里的那些`() {
        val names = PlaybackEngine::class.java.declaredMethods.map { it.name }.sorted().distinct()

        assertEquals(
            listOf(
                "availableTracks",
                "getAudioDelay",
                "getBackendName",
                "getCapabilities",
                "getSubtitleDelay",
                "pause",
                "play",
                "prepare",
                "release",
                "seekTo",
                "selectTrack",
                "selectedTrack",
                "setAudioDelay",
                "setRate",
                "setSubtitleDelay",
                "setSubtitleStyle",
            ),
            names,
        )
    }

    @Test
    fun `seekTo 有两个重载且条目序号重载带 index 与位置`() {
        val overloads =
            PlaybackEngine::class
                .java
                .declaredMethods
                .filter { it.name == "seekTo" }
                .map { method -> method.parameterTypes.toList() }

        assertEquals(2, overloads.size)
        assertTrue(overloads.contains(listOf(Long::class.javaPrimitiveType)))
        assertTrue(
            overloads.contains(listOf(Int::class.javaPrimitiveType, Long::class.javaPrimitiveType))
        )
    }

    @Test
    fun `能力位就是契约表的八个字段`() {
        val fields = EngineCapabilities::class.java.declaredFields.map { it.name }.sorted()

        assertEquals(
            listOf(
                "canPlayHls",
                "supportsAudioDelay",
                "supportsShuffleMode",
                "supportsSideloadedSubtitles",
                "supportsSoftwareDecode",
                "supportsSubtitleDelay",
                "supportsSubtitleStyling",
                "supportsTrackEnumeration",
            ),
            fields,
        )
    }

    @Test
    fun `fake 实现能按契约记录调用`() {
        val engine = FakeEngine()

        engine.prepare()
        engine.play()
        engine.pause()
        engine.seekTo(1_000L)
        engine.seekTo(3, 2_000L)
        engine.setRate(1.5f)
        engine.selectTrack(EngineTrackType.SUBTITLE, "0:1")
        engine.selectTrack(EngineTrackType.SUBTITLE, null)
        engine.setSubtitleStyle(SubtitleStyle(sizePercent = 125))
        engine.release()

        assertEquals(
            listOf(
                "prepare",
                "play",
                "pause",
                "seekTo(1000)",
                "seekTo(3,2000)",
                "setRate(1.5)",
                "selectTrack(SUBTITLE,0:1)",
                "selectTrack(SUBTITLE,null)",
                "setSubtitleStyle(125)",
                "release",
            ),
            engine.calls,
        )
    }

    @Test
    fun `延迟动词按原值往返`() {
        val engine = FakeEngine()

        engine.setSubtitleDelay(-350L)
        engine.setAudioDelay(1_250L)

        assertEquals(-350L, engine.getSubtitleDelay())
        assertEquals(1_250L, engine.getAudioDelay())
    }

    @Test
    fun `轨道枚举按类型过滤且选中态可读`() {
        val engine = FakeEngine()
        engine.tracks +=
            listOf(
                EngineTrack(
                    id = "0:0",
                    type = EngineTrackType.VIDEO,
                    language = null,
                    title = "视频",
                    codec = "h264",
                    isExternal = false,
                    isDefault = true,
                    isForced = false,
                ),
                EngineTrack(
                    id = "1:0",
                    type = EngineTrackType.SUBTITLE,
                    language = "zh-Hans",
                    title = "简体中文",
                    codec = "ass",
                    isExternal = true,
                    isDefault = false,
                    isForced = false,
                ),
            )

        assertEquals(listOf("1:0"), engine.availableTracks(EngineTrackType.SUBTITLE).map { it.id })
        assertEquals(emptyList<EngineTrack>(), engine.availableTracks(EngineTrackType.AUDIO))

        engine.selected = "1:0"
        assertEquals("1:0", engine.selectedTrack(EngineTrackType.SUBTITLE))
    }

    @Test
    fun `内核标识与播放器偏好的取值一致`() {
        assertEquals(PlayerHolder.BACKEND_EXOPLAYER, PlaybackEngine.BACKEND_EXOPLAYER)
        assertEquals(PlayerHolder.BACKEND_MPV, PlaybackEngine.BACKEND_MPV)
        assertEquals("exoplayer", PlaybackEngine.BACKEND_EXOPLAYER)
        assertEquals("mpv", PlaybackEngine.BACKEND_MPV)
    }
}
