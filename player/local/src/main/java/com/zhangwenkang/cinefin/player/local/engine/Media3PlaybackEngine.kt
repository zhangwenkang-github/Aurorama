package com.zhangwenkang.cinefin.player.local.engine

import androidx.media3.common.Player
import com.zhangwenkang.cinefin.player.core.playback.PlaybackEngine

/**
 * 带 media3 接入点的引擎（W2-T4）。
 *
 * `player/core` 的 [PlaybackEngine] 刻意不依赖 media3，而 `PlayerHolder` / MediaSession / 既有代码仍要用底层实例， 所以
 * media3 那一半只在这一层出现：适配器实现本接口，`PlaybackEngine` 面保持纯净。
 */
internal interface Media3PlaybackEngine : PlaybackEngine {
    /** 底层 media3 实例；对调用方来说与委托目标同一个对象。 */
    val media3Player: Player
}
