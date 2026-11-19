package com.zhangwenkang.cinefin.player.local.engine.exo

import com.zhangwenkang.cinefin.player.core.domain.models.SubtitleStyle

/**
 * Exo 内核的字幕延迟 / 样式出口（W4-T3a-1）。
 *
 * ExoPlayer 既没有字幕时间偏移 API，也不渲染字幕外观：延迟由自研字幕覆盖层（`PlayerSubtitleController`）兑现， 样式经
 * `PlayerEvents.SubtitleStyleChanged` 下发给自绘层。引擎动词因此不另存一份状态，而是通过本接口读写 既有那份状态——装配层在 `PlayerViewModel`
 * 里注入，避免出现平行第二套。
 *
 * 全部方法主线程调用。
 */
internal interface ExoSubtitleHooks {
    /** 当前生效的字幕时间偏移（毫秒）。 */
    fun subtitleDelayMs(): Long

    /** 写入字幕时间偏移（毫秒）；执行方负责落偏好并刷新覆盖层，语义与既有面板入口一致。 */
    fun setSubtitleDelayMs(delayMs: Long)

    /** 应用字幕外观（主字幕样式）。 */
    fun applySubtitleStyle(style: SubtitleStyle)
}
