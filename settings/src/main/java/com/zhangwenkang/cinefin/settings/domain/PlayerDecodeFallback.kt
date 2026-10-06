package com.zhangwenkang.cinefin.settings.domain

/**
 * 解码回退链（W16 · 用户拍板的优先级）：**本地硬解 → 服务器解码/转码 → 本地软解**。
 *
 * 与 W12（服务器转码 → 本地硬解 → 软解）顺序相反：现在先在本地硬解上试，硬解报「解码能力不足」时 才让服务器转码重发一路客户端能解的流，最后一次才落到本地软解（最耗电）。
 *
 * 放在 `settings`（而不是 player 模块）是因为三个地方要读同一份判定： ① data 层构造 PlaybackInfo（档位 = 转码时强制禁用直连）；②
 * PlayerHolder 建实例（档位 = 本地软解时强制软解）； ③ 播放页面板 / ViewModel 推进档位。抽成纯函数（+ 单测）避免三处各写一套 if。
 */
object PlayerDecodeFallback {
    /** 内核取值，与 `AppPreferences.playerBackend` / `PlayerViewModel.PLAYER_BACKEND_*` 一致 */
    const val BACKEND_EXOPLAYER = "exoplayer"

    const val BACKEND_MPV = "mpv"

    /** 未降级：本地硬解 */
    const val STAGE_NONE = 0

    /** 已降到「服务器解码/转码」：请求服务器转码流（客户端只解 h264/aac） */
    const val STAGE_SERVER_TRANSCODE = 1

    /** 已降到「本地软解」：mpv hwdec=no / Exo 扩展渲染器优先 */
    const val STAGE_LOCAL_SOFTWARE = 2

    /** 未知值一律按未降级处理（不静默降级成软解，避免耗电） */
    fun normalize(stage: Int?): Int =
        when (stage) {
            STAGE_SERVER_TRANSCODE -> STAGE_SERVER_TRANSCODE
            STAGE_LOCAL_SOFTWARE -> STAGE_LOCAL_SOFTWARE
            else -> STAGE_NONE
        }

    /**
     * 硬解失败后是否该走「服务器解码/转码」这一档。
     *
     * 只有「还没降过级」+「码率档位是自动」才升到服务器转码：具体 Mbps 档本来就在服务器转码， 「原始画质」是用户明确要求只直连，两者都直接落到本地软解。
     */
    fun serverTranscodeNext(
        stage: Int?,
        bitratePreference: Long,
    ): Boolean = normalize(stage) == STAGE_NONE && bitratePreference == PlayerStreamingQuality.AUTO

    /** 硬解失败后的下一档；`STAGE_LOCAL_SOFTWARE` 是最后一档（再失败不再推进，避免死循环） */
    fun nextStage(
        stage: Int?,
        bitratePreference: Long,
    ): Int =
        when {
            serverTranscodeNext(stage, bitratePreference) -> STAGE_SERVER_TRANSCODE
            normalize(stage) == STAGE_LOCAL_SOFTWARE -> STAGE_LOCAL_SOFTWARE
            else -> STAGE_LOCAL_SOFTWARE
        }

    /** 档位是否要求「服务器转码」：data 层据此构造 PlaybackInfo（禁用直连 / 直传） */
    fun forcesServerTranscode(stage: Int?): Boolean = normalize(stage) == STAGE_SERVER_TRANSCODE

    /** 档位是否要求「本地软解」：PlayerHolder 据此忽略硬解偏好 */
    fun forcesLocalSoftware(stage: Int?): Boolean = normalize(stage) == STAGE_LOCAL_SOFTWARE

    /**
     * W18：**一次播放失败后的下一档**（null = 链路已用尽，交给错误卡片）。
     *
     * 关键约束（用户实测反馈）：**内核是「手动选」还是「自动走链路」不影响判定**—— 手动切到 ExoPlayer / mpv 之后，失败仍要从第 1 档按同一条链路下降；只有第 3
     * 档（本地软解）再失败才报错。
     *
     * | 当前档位  | 内核        | 错误类型            | 下一档                               |
     * |-------|-----------|-----------------|-----------------------------------|
     * | 服务器转码 | 任意        | 任意（转码流失败也要继续降）  | 本地软解                              |
     * | 本地硬解  | ExoPlayer | 解不了这个格式         | 自动档 → 服务器转码；原始画质 / 具体 Mbps → 本地软解 |
     * | 本地硬解  | ExoPlayer | 网络 / DRM 等      | null（换内核救不了，直接报错）                 |
     * | 本地硬解  | mpv       | 任意（mpv 上报不带错误码） | 同「解不了这个格式」                        |
     * | 本地软解  | 任意        | 任意              | null（全败）                          |
     *
     * @param backend 当前内核；null / 未知按 ExoPlayer 的保守规则处理
     */
    fun stageAfterFailure(
        stage: Int?,
        backend: String?,
        bitratePreference: Long,
        codecCapabilityError: Boolean,
        networkError: Boolean = false,
    ): Int? =
        when {
            // 第 3 档是最后一档：再失败就是「全部失败」，显示错误卡片
            normalize(stage) == STAGE_LOCAL_SOFTWARE -> null
            /*
             * W73（#8）：网络 / IO 类错误在任何档位都不换内核——换内核救不了网络，重启播放页反而会把
             * 「偶发网络抖动」放大成「播放中退回详情页」（重启时网络未恢复 → 初始化失败）。
             * 交给调用方原地重试 / 重开转码会话。
             */
            networkError -> null
            // 第 2 档（服务器转码流）失败：不挑错误码，一律继续降到本地软解
            normalize(stage) == STAGE_SERVER_TRANSCODE -> STAGE_LOCAL_SOFTWARE
            // 第 1 档：ExoPlayer 只接「解不了这个格式」类错误（网络 / DRM 换内核没用）
            backend != BACKEND_MPV && !codecCapabilityError -> null
            // 第 1 档可降级：自动档先请服务器转码，其余档位直接落本地软解
            else -> nextStage(STAGE_NONE, bitratePreference)
        }

    /**
     * W19：一次失败后的回退决策（把「开关 / 链路档位 / 循环保护」收在一个纯函数里，便于单测）。
     *
     * @param autoFallbackEnabled 解码面板的「失败自动回退」开关：关 = 强制所选内核，失败只提示错误
     * @param candidateStage [stageAfterFailure] 的链路判定结果（null = 链路已用尽）
     * @param restartGuardExceeded 同一媒体 + 同一目标档位的重启次数是否已超上限（循环保护）
     * @return 下一档；null = **不接管**（显示错误卡片：开关关闭 / 链路用尽 / 重启次数超限）
     */
    fun fallbackDecision(
        autoFallbackEnabled: Boolean,
        candidateStage: Int?,
        restartGuardExceeded: Boolean,
    ): Int? =
        when {
            !autoFallbackEnabled -> null
            restartGuardExceeded -> null
            else -> candidateStage
        }

    /**
     * W19：回退重启守卫（循环保护），按「同一媒体 + 同一目标档位」计数。
     *
     * 正常链路每个目标档位最多重启一次（服务器转码 / 本地软解）；同一个目标被反复重启 （档位没能生效、状态被意外清零等循环）超过
     * [MAX_FALLBACK_RESTARTS_PER_STAGE] 次就判定为循环， 直接交给错误卡片，不再无限重启。落盘格式
     * `mediaId|targetStage|attempts`（[formatGuard] / [parseGuard]）。
     */
    data class RestartGuard(
        val mediaId: String,
        val targetStage: Int,
        val attempts: Int,
    ) {
        fun exceeded(limit: Int = MAX_FALLBACK_RESTARTS_PER_STAGE): Boolean = attempts > limit
    }

    /** 同一媒体 + 同一目标档位的回退重启上限（超过即判定循环，交错误卡片） */
    const val MAX_FALLBACK_RESTARTS_PER_STAGE = 2

    /** 记录一次即将发起的重启：目标一致则计数 +1，换媒体 / 换目标档位则从 1 重新计数 */
    fun recordRestart(
        previous: RestartGuard?,
        mediaId: String,
        targetStage: Int,
    ): RestartGuard {
        val normalized = normalize(targetStage)
        return if (
            previous != null && previous.mediaId == mediaId && previous.targetStage == normalized
        ) {
            previous.copy(attempts = previous.attempts + 1)
        } else {
            RestartGuard(mediaId, normalized, 1)
        }
    }

    /** 守卫序列化（SharedPreferences 单键存储）：`mediaId|targetStage|attempts` */
    fun formatGuard(guard: RestartGuard): String =
        "${guard.mediaId}|${guard.targetStage}|${guard.attempts}"

    /** 守卫反序列化；空 / 损坏数据一律返回 null（按「没有历史」处理） */
    fun parseGuard(raw: String?): RestartGuard? {
        val parts = raw?.split('|') ?: return null
        if (parts.size != 3) return null
        val mediaId = parts[0].takeIf { it.isNotBlank() } ?: return null
        val stage = parts[1].toIntOrNull() ?: return null
        val attempts = parts[2].toIntOrNull() ?: return null
        if (attempts <= 0) return null
        return RestartGuard(mediaId, normalize(stage), attempts)
    }

    /** 优先级文案用的顺序表（面板与文档同一份来源） */
    val PRIORITY: List<String> = listOf("本地硬解", "服务器解码/转码", "本地软解")

    /** 失败上报去重用的键：同一次失败 = 同一内核 + 同一档位 */
    fun failureKey(
        backend: String?,
        stage: Int?,
    ): String = "$backend:${normalize(stage)}"

    /**
     * W18：是否是「同一次失败的重复上报」。
     *
     * 内核（尤其 mpv）一次播放失败可能连发多条错误；若不去重，第 1 档的失败会被处理两次，档位直接从 0 跳到 2、 跳过服务器转码这一档。判据 = 键相同 + 在窗口内；换了内核 /
     * 档位或过了窗口就是新的失败。
     */
    fun isDuplicateFailure(
        lastKey: String?,
        lastHandledAtMs: Long,
        backend: String?,
        stage: Int?,
        nowMs: Long,
        windowMs: Long = DUPLICATE_FAILURE_WINDOW_MS,
    ): Boolean =
        lastKey != null &&
            lastKey == failureKey(backend, stage) &&
            nowMs - lastHandledAtMs < windowMs

    /** 重复失败上报的去重窗口（3 s：足够覆盖一次失败的多条回调，又不影响链路下一次失败） */
    const val DUPLICATE_FAILURE_WINDOW_MS = 3_000L
}
