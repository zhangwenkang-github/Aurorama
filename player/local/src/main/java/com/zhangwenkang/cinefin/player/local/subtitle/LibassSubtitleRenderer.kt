package com.zhangwenkang.cinefin.player.local.subtitle

import android.graphics.Bitmap
import io.github.peerless2012.ass.Ass
import io.github.peerless2012.ass.AssFrame
import io.github.peerless2012.ass.AssRender
import io.github.peerless2012.ass.AssTex
import io.github.peerless2012.ass.AssTexType
import io.github.peerless2012.ass.AssTrack
import timber.log.Timber

/** 一帧 libass 渲染结果（对外结构，屏蔽 ass-kt 类型，避免 app 模块直接依赖原生库） */
data class LibassFrame(
    /** 本帧要绘制的位图切片（坐标以渲染分辨率为基准） */
    val images: List<LibassImage>,
    /** 与上一帧相同 = false（调用方可直接复用上一帧） */
    val changed: Boolean,
)

/** libass 输出的一个位图切片：位置 + 位图 + 颜色（ALPHA 位图用颜色着色） */
data class LibassImage(
    val x: Int,
    val y: Int,
    val bitmap: Bitmap,
    val color: Int,
)

/**
 * Exo 内核的 libass 渲染器（W16 · §1.18）。
 *
 * 与 mpv 内核「原生 libass」对齐：ASS/SSA 的定位 / 字体 / 特效由 libass 还原，App 侧只负责 「选哪条字幕、延迟多少、字号倍率」这些管线语义。SRT 由
 * [AssSubtitleScript] 生成 ASS 脚本后同样走这里， 因此两个内核下文本字幕共用同一套还原逻辑。
 *
 * 依赖 `io.github.peerless2012:ass-kt`（MIT 包装 + libass ISC，自带 arm64/armv7/x86 原生库）。 选它而不是 media3 集成的
 * `ass-media`：① ass-kt 不依赖 Media3，不受 1.8.0 → 1.11.1 版本差影响； ② 自研字幕管线（延迟 / 双语 / 语言选择 / 外观）在 Media3
 * 轨道体系里做不了，必须由 App 自己喂脚本。
 *
 * 线程约定：所有方法可跨线程调用（内部由 libass 包装的 ReentrantLock 串行）， 但调用方应保证同一时刻只有一次 renderFrame 在飞（覆盖层是「渲染完成 →
 * 再渲染下一帧」）。 任何一步抛异常（含 `UnsatisfiedLinkError` 这类 Error）都只标记 [failed]，由上层回退既有文本渲染，绝不带崩播放页。
 */
class LibassSubtitleRenderer {

    private var ass: Ass? = null
    private var track: AssTrack? = null
    private var render: AssRender? = null

    /** 已经生效的载入参数；完全一致时 [load] 直接复用当前轨道 */
    private var loadedScript: String? = null
    private var fontScale = 1f
    private var frameWidth = 0
    private var frameHeight = 0
    private var storageWidth = 0
    private var storageHeight = 0

    /** 初始化 / 渲染失败（上层据此回退文本渲染，并打日志） */
    var failed: Boolean = false
        private set

    /** 失败原因（日志 / 状态展示用） */
    var failureReason: String? = null
        private set

    /** 当前是否有可渲染的 ASS 轨 */
    val isReady: Boolean
        get() = !failed && track != null && render != null

    /**
     * 载入脚本与渲染参数；参数完全一致时复用当前轨道（不重建）。
     *
     * **为什么参数变化必须整体重建**：libass 对「同一时间戳」的渲染结果有帧缓存，只调 `ass_set_font_scale` / `ass_set_frame_size`
     * 不会让旧帧失效——暂停画面下改字号 / 转屏后， 会一直画旧尺寸的字幕（真机实测复现）。重建轨道 + 渲染器是唯一稳的失效手段， 代价只是一次脚本重解析（几十 KB，毫秒级）。
     *
     * @param script 完整 ASS 脚本（ASS/SSA 原文或 SRT 生成的脚本），UTF-8 文本
     * @param fontScale 字号倍率（面板「大小」档位；ASS 的定位 / 颜色仍由脚本决定）
     * @param storageWidth/storageHeight 视频像素尺寸（libass storage size）
     * @param frameWidth/frameHeight 渲染分辨率（画面显示区像素尺寸，[renderFrame] 坐标基准）
     */
    fun load(
        script: String,
        fontScale: Float,
        storageWidth: Int,
        storageHeight: Int,
        frameWidth: Int,
        frameHeight: Int,
    ): Boolean {
        if (failed) return false
        if (
            script == loadedScript &&
                fontScale == this.fontScale &&
                storageWidth == this.storageWidth &&
                storageHeight == this.storageHeight &&
                frameWidth == this.frameWidth &&
                frameHeight == this.frameHeight
        ) {
            return isReady
        }
        this.fontScale = fontScale
        this.storageWidth = storageWidth
        this.storageHeight = storageHeight
        this.frameWidth = frameWidth
        this.frameHeight = frameHeight
        return guarded("load") {
            val assInstance = ass ?: Ass().also { ass = it }
            render?.release()
            render = null
            track?.release()
            track = null
            val newTrack = assInstance.createTrack()
            track = newTrack
            val newRender = assInstance.createRender()
            render = newRender
            newRender.setCacheLimit(GLYPH_CACHE_MAX, BITMAP_CACHE_LIMIT_MB)
            newRender.setStorageSize(storageWidth.coerceAtLeast(1), storageHeight.coerceAtLeast(1))
            newRender.setFrameSize(frameWidth.coerceAtLeast(1), frameHeight.coerceAtLeast(1))
            newRender.setFontScale(fontScale)
            newTrack.readBuffer(script.toByteArray(Charsets.UTF_8))
            newRender.setTrack(newTrack)
            loadedScript = script
        } != null && isReady
    }

    /**
     * 渲染某一时刻的 ASS 帧（毫秒；调用方已扣掉字幕延迟）。
     *
     * 返回 null = 没到时间 / 失败；[AssFrame.changed] == 0 = 与上一帧相同（可直接复用上一帧）。
     */
    fun renderFrame(positionMs: Long): LibassFrame? {
        if (failed || !isReady) return null
        val frame =
            guarded("renderFrame") { render?.renderFrame(positionMs, AssTexType.BITMAP_ALPHA) }
                ?: return null
        val images =
            frame.images.orEmpty().mapNotNull { tex ->
                val bitmap = bitmapOf(tex) ?: return@mapNotNull null
                LibassImage(x = tex.x, y = tex.y, bitmap = bitmap, color = paintColorOf(tex))
            }
        return LibassFrame(images = images, changed = frame.changed != 0)
    }

    fun release() {
        guarded("release") {
            render?.release()
            track?.release()
            ass?.release()
        }
        render = null
        track = null
        ass = null
        loadedScript = null
        fontScale = 1f
        frameWidth = 0
        frameHeight = 0
        storageWidth = 0
        storageHeight = 0
    }

    private inline fun <T> guarded(what: String, block: () -> T): T? =
        try {
            block()
        } catch (throwable: Throwable) {
            failed = true
            failureReason =
                "$what: ${throwable.javaClass.simpleName} ${throwable.message.orEmpty()}"
            // 不用 Timber.e(throwable)：libass 失败只需要一条可检索的标记 + 原因
            Timber.w("libass 渲染不可用（%s），回退既有文本渲染", failureReason)
            null
        }

    companion object {
        /** 与 ass-media 默认配置一致：字形缓存条目上限 */
        private const val GLYPH_CACHE_MAX = 10_000

        /** 与 ass-media 默认配置一致：位图缓存上限（MB） */
        private const val BITMAP_CACHE_LIMIT_MB = 128

        /**
         * [AssTex.color] → Android Paint 颜色。
         *
         * 原生侧打包是 `R<<24 | G<<16 | B<<8 | (255 - A)`（与 ass-media 的 Canvas 覆盖层同一套换算），
         * 这里保持完全一致，避免两个渲染器颜色不同。
         */
        private fun paintColorOf(tex: AssTex): Int {
            val r = tex.color shr 24 and 0xFF
            val g = tex.color shr 16 and 0xFF
            val b = tex.color shr 8 and 0xFF
            val a = 0xFF - tex.color and 0xFF
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        /** 位图有效性（libass 空帧会给出 0×0 的占位） */
        private fun bitmapOf(tex: AssTex): Bitmap? =
            tex.bitmap?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
    }
}
