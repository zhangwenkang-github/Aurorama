package com.zhangwenkang.cinefin.player.local.mpv

/**
 * `MPVPlayer.prepare()` 的命令计划（W2-T5-β · F2）。
 *
 * 本对象是纯 Kotlin：命令拼接不依赖 mpvLib 与 media3，因此可以被 JVM 单测直接断言。
 *
 * `MPVPlayer.prepare()` 只负责执行这里给出的计划，不再自行拼接命令。
 */
internal object MpvPrepareCommands {
    const val LOADFILE = "loadfile"

    const val REPLACE = "replace"

    const val APPEND = "append"

    const val INSERT_AT = "insert-at"

    const val PLAYLIST_PLAY_INDEX = "playlist-play-index"

    /**
     * 新会话 prepare 的有序命令序列；空列表表示无命令可发（条目为空或 [targetIndex] 越界）。
     *
     * 第一条是 `loadfile <目标条目> replace`：mpv 的第一条 loadfile 直接指向目标条目，0 号条不再被加载或缓冲。
     *
     * 目标之前的条目按序号升序 `loadfile <条目> insert-at <序号>`：每次插入把先前的目标条目右移一格，最终落在 [targetIndex]。
     *
     * 目标之后的条目按序号升序 `append`。
     *
     * 序列执行完后 mpv playlist 与 [sourceUris] 一一对应，当前条目正是 [targetIndex]。
     *
     * 之所以不用 `playlist-start` 方案：该选项只在整份播放列表经 `loadlist` 载入时决定起播项。
     *
     * 本场景的条目是逐条独立的媒体地址，`loadfile ... replace` 永远落在 0 号位，无法预置起播序号。
     */
    fun freshSessionCommands(sourceUris: List<String>, targetIndex: Int): List<Array<String>> {
        if (targetIndex !in sourceUris.indices) return emptyList()
        val commands = ArrayList<Array<String>>(sourceUris.size)
        commands += loadFile(sourceUris[targetIndex], REPLACE)
        for (index in 0 until targetIndex) {
            commands += loadFile(sourceUris[index], INSERT_AT, index.toString())
        }
        for (index in targetIndex + 1 until sourceUris.size) {
            commands += loadFile(sourceUris[index], APPEND)
        }
        return commands
    }

    /**
     * 保源路径 prepare 的唯一命令：把 mpv 的当前条目重新指向 [currentIndex]，不触碰 loadfile。
     *
     * 该路径的内核源已经在播放，重发 loadfile 会把同一条目真实重载一遍。
     */
    fun preserveSourceCommand(currentIndex: Int): Array<String> =
        arrayOf(PLAYLIST_PLAY_INDEX, currentIndex.toString())

    /**
     * prepare 的目标条目序号；无条目时返回 -1。
     *
     * 会话刚由 `setMediaItems` 建立时 [currentIndex] 与 [initialIndex] 相等，两种口径给出同一个目标。
     *
     * 会话内跨集之后 [initialIndex] 仍是会话起点、已经过期：重试 / 引擎重建要的是出错的那一集，因此 [currentIndex] 优先。
     *
     * [currentIndex] 越界（尚无当前条目）时退回 [initialIndex] 并夹到有效范围。
     */
    fun targetIndex(itemCount: Int, currentIndex: Int, initialIndex: Int): Int {
        if (itemCount <= 0) return -1
        if (currentIndex in 0 until itemCount) return currentIndex
        return initialIndex.coerceIn(0, itemCount - 1)
    }

    private fun loadFile(uri: String, flag: String): Array<String> = arrayOf(LOADFILE, uri, flag)

    private fun loadFile(uri: String, flag: String, argument: String): Array<String> =
        arrayOf(LOADFILE, uri, flag, argument)
}
