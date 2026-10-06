package com.zhangwenkang.cinefin.player.local.subtitle

/**
 * 覆盖层的 libass 帧状态机（W74 #15，纯函数便于单测）。
 *
 * libass 每一帧只会给出三种结果：
 * 1. 有图元的新帧（[LibassFrame.changed] == true）→ 直接替换；
 * 2. 与上一帧完全相同（[LibassFrame.changed] == false，图元在原生层被省略）→ 复用上一帧；
 * 3. 该时刻没有任何图元（渲染器返回空帧，见 [LibassSubtitleRenderer.renderFrame]）→ 必须清屏。
 *
 * [rendered] 为 null = 渲染器不可用（失败 / 已释放），保持现状，由上层决定回退文本渲染。
 */
fun nextLibassFrame(previous: LibassFrame?, rendered: LibassFrame?): LibassFrame? {
    if (rendered == null) return previous
    return if (rendered.changed || previous == null) rendered else previous
}
