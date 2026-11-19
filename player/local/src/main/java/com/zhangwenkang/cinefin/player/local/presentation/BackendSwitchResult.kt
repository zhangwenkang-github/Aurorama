package com.zhangwenkang.cinefin.player.local.presentation

/**
 * 原地切换内核的结果（W4-T3a-2）。
 *
 * 宿主（播放页）据此决定提示条文案与是否走重启兜底：成功 → 「已切换」；失败且旧内核已保住 → 「切换失败，已保持原内核」； 失败且旧实例没能保住 → 必须先重启播放页（旧 recreate
 * 路径）才能继续播放。
 */
sealed interface BackendSwitchResult {
    data class Success(val backend: String) : BackendSwitchResult

    /**
     * @param reason 短标识（日志 / 兜底决策用），不是给用户看的文案。
     * @param keptPreviousBackend 旧内核实例是否仍然可用（回滚成功）。
     */
    data class Failure(
        val reason: String,
        val keptPreviousBackend: Boolean,
    ) : BackendSwitchResult
}
