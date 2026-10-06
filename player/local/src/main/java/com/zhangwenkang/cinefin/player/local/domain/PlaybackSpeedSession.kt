package com.zhangwenkang.cinefin.player.local.domain

/**
 * W74-S2（#9）：播放倍速是「播放会话内状态」，不落盘、不新增偏好键。
 *
 * 规则：
 * - 同一播放会话（队列切下一集 / 自动下一集 / 回退重启复用同一 session id）→ 继承会话倍速；
 * - 新播放会话（新开一部片 / 新开播放页）→ 回落 1×。
 *
 * 抽成纯函数便于单测回归：写反会直接导致「跨集丢倍速」或「换片仍带上次的倍速」。
 */
const val DEFAULT_PLAYBACK_SPEED: Float = 1f

/**
 * 算出这次播放请求该用的倍速。
 *
 * @param sessionSpeed 当前会话已经选定的倍速。
 * @param isNewSession 这次请求是否开启了新的播放会话。
 */
internal fun speedForPlaybackSession(sessionSpeed: Float, isNewSession: Boolean): Float =
    if (isNewSession) DEFAULT_PLAYBACK_SPEED else sessionSpeed
