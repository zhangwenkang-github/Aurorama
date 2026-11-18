package com.zhangwenkang.cinefin.player.local.engine

import com.zhangwenkang.cinefin.player.core.playback.EngineCapabilities
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine
import com.zhangwenkang.cinefin.player.local.presentation.PlayerHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PlaybackEngine] 的接口面契约（W2-T4）。
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
            ),
        override val backendName: String = "fake",
    ) : PlaybackEngine {
        val calls = mutableListOf<String>()

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
    }

    @Test
    fun `接口成员就是契约表里的那些`() {
        val names = PlaybackEngine::class.java.declaredMethods.map { it.name }.sorted().distinct()

        assertEquals(
            listOf(
                "getBackendName",
                "getCapabilities",
                "pause",
                "play",
                "prepare",
                "release",
                "seekTo",
                "setRate",
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
    fun `能力位就是契约表的六个字段`() {
        val fields = EngineCapabilities::class.java.declaredFields.map { it.name }.sorted()

        assertEquals(
            listOf(
                "canPlayHls",
                "supportsAudioDelay",
                "supportsShuffleMode",
                "supportsSideloadedSubtitles",
                "supportsSoftwareDecode",
                "supportsSubtitleStyling",
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
        engine.release()

        assertEquals(
            listOf(
                "prepare",
                "play",
                "pause",
                "seekTo(1000)",
                "seekTo(3,2000)",
                "setRate(1.5)",
                "release",
            ),
            engine.calls,
        )
    }

    @Test
    fun `内核标识与播放器偏好的取值一致`() {
        assertEquals(PlayerHolder.BACKEND_EXOPLAYER, PlaybackEngine.BACKEND_EXOPLAYER)
        assertEquals(PlayerHolder.BACKEND_MPV, PlaybackEngine.BACKEND_MPV)
        assertEquals("exoplayer", PlaybackEngine.BACKEND_EXOPLAYER)
        assertEquals("mpv", PlaybackEngine.BACKEND_MPV)
    }
}
