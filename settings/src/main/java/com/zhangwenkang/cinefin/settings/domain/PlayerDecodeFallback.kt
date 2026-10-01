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

    /** 优先级文案用的顺序表（面板与文档同一份来源） */
    val PRIORITY: List<String> = listOf("本地硬解", "服务器解码/转码", "本地软解")
}
