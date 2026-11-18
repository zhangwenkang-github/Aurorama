package com.zhangwenkang.cinefin.player.local.mpv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvPrepareCommandsTest {
    @Test
    fun `fresh session at index zero keeps legacy loadfile replace then appends`() {
        val uris = listOf("u0", "u1", "u2", "u3")

        val commands = MpvPrepareCommands.freshSessionCommands(uris, targetIndex = 0)

        assertEquals(uris.size, commands.size)
        assertArrayEquals(arrayOf("loadfile", "u0", "replace"), commands[0])
        assertArrayEquals(arrayOf("loadfile", "u1", "append"), commands[1])
        assertArrayEquals(arrayOf("loadfile", "u2", "append"), commands[2])
        assertArrayEquals(arrayOf("loadfile", "u3", "append"), commands[3])
    }

    @Test
    fun `fresh session targets requested item with its first loadfile`() {
        val uris = listOf("u0", "u1", "u2", "u3", "u4")

        val commands = MpvPrepareCommands.freshSessionCommands(uris, targetIndex = 3)

        assertArrayEquals(arrayOf("loadfile", "u3", "replace"), commands[0])
        assertTrue(commands.none { it[1] == "u0" && it[2] == "replace" })
    }

    @Test
    fun `fresh session keeps mpv playlist order matching sources for every target`() {
        val uris = listOf("u0", "u1", "u2", "u3", "u4")

        for (targetIndex in uris.indices) {
            val commands = MpvPrepareCommands.freshSessionCommands(uris, targetIndex)
            val playlist = replay(commands)

            assertEquals("target=$targetIndex playlist", uris, playlist.order)
            assertEquals("target=$targetIndex current", targetIndex, playlist.currentIndex)
        }
    }

    @Test
    fun `fresh session never selects an entry after the fact`() {
        val uris = listOf("u0", "u1", "u2", "u3")

        val commands = MpvPrepareCommands.freshSessionCommands(uris, targetIndex = 2)

        assertTrue(commands.none { it[0] == MpvPrepareCommands.PLAYLIST_PLAY_INDEX })
        assertEquals(1, commands.count { it[2] == MpvPrepareCommands.REPLACE })
        assertEquals(
            uris.size - 1,
            commands.count {
                it[2] == MpvPrepareCommands.INSERT_AT || it[2] == MpvPrepareCommands.APPEND
            },
        )
    }

    @Test
    fun `fresh session is empty without sources or out of range target`() {
        assertTrue(MpvPrepareCommands.freshSessionCommands(emptyList(), targetIndex = 0).isEmpty())
        assertTrue(MpvPrepareCommands.freshSessionCommands(listOf("u0"), targetIndex = 1).isEmpty())
        assertTrue(
            MpvPrepareCommands.freshSessionCommands(listOf("u0"), targetIndex = -1).isEmpty()
        )
    }

    @Test
    fun `preserve source command never loads a file`() {
        val command = MpvPrepareCommands.preserveSourceCommand(currentIndex = 2)

        assertArrayEquals(arrayOf("playlist-play-index", "2"), command)
        assertFalse(command[0] == MpvPrepareCommands.LOADFILE)
    }

    @Test
    fun `retry prepare targets the errored item instead of the session start`() {
        // 会话起点是 0（比如从第 1 集开播），已跨到第 7 集后重试：目标必须是 7，不是 0。
        val target =
            MpvPrepareCommands.targetIndex(itemCount = 10, currentIndex = 7, initialIndex = 0)

        assertEquals(7, target)
        assertArrayEquals(
            arrayOf("loadfile", "u7", "replace"),
            MpvPrepareCommands.freshSessionCommands(uris(10), target).first(),
        )
    }

    @Test
    fun `new session prefers the session start index before any current item exists`() {
        assertEquals(
            3,
            MpvPrepareCommands.targetIndex(itemCount = 10, currentIndex = -1, initialIndex = 3),
        )
        assertEquals(
            3,
            MpvPrepareCommands.targetIndex(itemCount = 10, currentIndex = 3, initialIndex = 3),
        )
    }

    @Test
    fun `target index is minus one without items and clamped otherwise`() {
        assertEquals(
            -1,
            MpvPrepareCommands.targetIndex(itemCount = 0, currentIndex = 0, initialIndex = 0),
        )
        assertEquals(
            2,
            MpvPrepareCommands.targetIndex(itemCount = 3, currentIndex = 99, initialIndex = 99),
        )
    }

    private fun uris(count: Int): List<String> = (0 until count).map { "u$it" }

    /**
     * 按 mpv 的语义重放命令，只模拟与顺序映射相关的一条规则：
     *
     * `loadfile ... insert-at <i>` 插到 i 号位；插入位置在当前条目之前时，当前条目右移一格（mpv 保持同一条目为当前）。
     */
    private fun replay(commands: List<Array<String>>): Replay {
        val order = mutableListOf<String>()
        var current = -1
        for (command in commands) {
            assertEquals(MpvPrepareCommands.LOADFILE, command[0])
            when (command[2]) {
                MpvPrepareCommands.REPLACE -> {
                    order.clear()
                    order += command[1]
                    current = 0
                }
                MpvPrepareCommands.APPEND -> order += command[1]
                MpvPrepareCommands.INSERT_AT -> {
                    val index = command[3].toInt()
                    order.add(index, command[1])
                    if (index <= current) current += 1
                }
                else -> throw AssertionError("unknown loadfile flag: ${command[2]}")
            }
        }
        return Replay(order, current)
    }

    private data class Replay(
        val order: List<String>,
        val currentIndex: Int,
    )
}
