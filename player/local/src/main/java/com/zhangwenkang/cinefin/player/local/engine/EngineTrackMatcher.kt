package com.zhangwenkang.cinefin.player.local.engine

import com.zhangwenkang.cinefin.player.core.playback.EngineTrack

/**
 * 跨内核轨道映射表（W4-T3a-2，纯 Kotlin）。
 *
 * 原地切换内核时，旧内核的轨道 id 在新内核里没有意义（Exo 的 `group:track`、mpv 的 sid 都会随实例重建漂移）， 只能用**字段**重新认轨。命中顺序写死在
 * [match]，调用方不得自行加档：
 *
 * 1. 精确：language + codec + isExternal + title 全等；
 * 2. 宽松：language + isExternal（类型已由候选集限定）；
 * 3. 兜底：同类型的 `isDefault` 轨。
 *
 * 快照轨道为 null（原类型未启用，例如字幕关闭）时**不映射**，由调用方保持关闭——本对象只回答「这条轨在新内核里是哪一条」。
 */
internal object EngineTrackMatcher {

    /**
     * 在 [candidates]（必须是新内核同一类型的轨道）里找回 [snapshot] 对应的轨道。
     *
     * 返回 null = 三级都未命中，调用方保持新内核自己的选择，不写参数。
     */
    fun match(snapshot: EngineTrack, candidates: List<EngineTrack>): EngineTrack? {
        val sameType = candidates.filter { it.type == snapshot.type }
        if (sameType.isEmpty()) return null

        sameType
            .firstOrNull { candidate ->
                candidate.language.matches(snapshot.language) &&
                    candidate.codec.matches(snapshot.codec) &&
                    candidate.isExternal == snapshot.isExternal &&
                    candidate.title.matches(snapshot.title)
            }
            ?.let {
                return it
            }

        sameType
            .firstOrNull { candidate ->
                candidate.language.matches(snapshot.language) &&
                    candidate.isExternal == snapshot.isExternal
            }
            ?.let {
                return it
            }

        return sameType.firstOrNull { it.isDefault }
    }

    /** 字段比较：忽略大小写与首尾空白，两边都为 null / 空串视为相等。 */
    private fun String?.matches(other: String?): Boolean = normalize(this) == normalize(other)

    private fun normalize(value: String?): String = value?.trim()?.lowercase().orEmpty()
}
