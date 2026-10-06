package com.zhangwenkang.cinefin.player.local.subtitle

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** 覆盖层 libass 帧状态机（W74 #15）： 句末的空帧必须真的清屏，不能像「本帧无结果」那样保留上一句。 */
class LibassFramePolicyTest {

    private fun changedFrame() = LibassFrame(images = emptyList(), changed = true)

    private val unchangedFrame = LibassFrame(images = emptyList(), changed = false)

    @Test
    fun `changed 的帧替换上一帧（句末空帧即走这条）`() {
        val previous = LibassFrame(images = emptyList(), changed = false)
        val clear = changedFrame()

        val next = nextLibassFrame(previous, clear)

        assertSame(clear, next)
    }

    @Test
    fun `changed 为 false 时复用上一帧`() {
        val previous = changedFrame()

        val next = nextLibassFrame(previous, unchangedFrame)

        assertSame(previous, next)
    }

    @Test
    fun `渲染器不可用时保持现状`() {
        val previous = changedFrame()

        assertSame(previous, nextLibassFrame(previous, null))
        assertNull(nextLibassFrame(null, null))
    }

    @Test
    fun `没有上一帧时任何结果都直接采用`() {
        val clear = changedFrame()

        assertSame(clear, nextLibassFrame(null, clear))
        assertSame(unchangedFrame, nextLibassFrame(null, unchangedFrame))
    }
}
