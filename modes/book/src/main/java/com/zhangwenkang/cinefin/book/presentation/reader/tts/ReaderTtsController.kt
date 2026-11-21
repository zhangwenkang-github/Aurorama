package com.zhangwenkang.cinefin.book.presentation.reader.tts

/**
 * 听书状态机（W6 T9 / READER-32–36，定稿六）。
 *
 * 纯逻辑：不碰 Android 组件，引擎与时钟由构造参数注入，章节文本由调用方按需喂入 （[addChapter] / [onRequestChapter]），因此「上下句边界 / 跳章 /
 * 定时各档 / 降级路径」都能在 JVM 单测里跑。真正的 `TextToSpeech` 绑定在 `AndroidTtsEngine`，本类只收它的三个回调。
 */
internal class ReaderTtsController(
    private val engine: TtsEngine,
    private val clock: () -> Long,
    private val onStateChanged: (ReaderTtsUiState) -> Unit = {},
    /** 需要下一章文本（懒加载）：调用方解析后回喂 [addChapter]；解析失败回 [onChapterUnavailable]。 */
    private val onRequestChapter: (Int) -> Unit = {},
) {
    private val chapters = mutableListOf<TtsChapter>()
    private var chapterIndex = -1
    private var sentenceIndex = -1
    private var playback = TtsPlayback.Idle
    private var speaking = false
    private var rate = ReaderTtsDefaultRate
    private var timer = TtsTimerOption.Off
    private var deadlineMs: Long? = null
    private var timerRemainingMs: Long? = null
    private var stopAtChapterEnd = false
    private var waitingChapter: Int? = null
    private var pendingStart: Pair<Int, Int>? = null
    private var consecutiveErrors = 0
    private var degradedSentences = 0
    private var engineError: String? = null
    private var message: String? = null

    /** 已加载章节（按序号升序）。 */
    val loadedChapters: List<TtsChapter>
        get() = chapters.toList()

    val state: ReaderTtsUiState
        get() = snapshot()

    // ---- 章节喂入 ----

    /** 追加 / 替换一章；若正等这一章（跨章续播 / 指定章首启动）则立刻续上。 */
    fun addChapter(chapter: TtsChapter) {
        val existing = chapters.indexOfFirst { it.index == chapter.index }
        if (existing >= 0) chapters[existing] = chapter else chapters.add(chapter)
        chapters.sortBy { it.index }
        degradedSentences = chapters.sumOf { current ->
            current.sentences.count { !it.highlightable }
        }

        pendingStart?.let { (targetChapter, targetSentence) ->
            if (targetChapter == chapter.index) {
                pendingStart = null
                waitingChapter = null
                if (chapter.sentences.isEmpty()) {
                    moveToNextChapter(chapter.index + 1)
                } else {
                    chapterIndex = chapter.index
                    sentenceIndex = targetSentence.coerceIn(0, chapter.sentences.lastIndex)
                    playback = TtsPlayback.Playing
                    speakCurrent()
                }
                return
            }
        }
        if (waitingChapter == chapter.index) {
            waitingChapter = null
            if (chapter.sentences.isEmpty()) {
                moveToNextChapter(chapter.index + 1)
            } else {
                chapterIndex = chapter.index
                sentenceIndex = 0
                playback = TtsPlayback.Playing
                speakCurrent()
            }
            return
        }
        emit()
    }

    /** 指定章节无法提供文本（越界 / 全图资源 / 解析失败）。 */
    fun onChapterUnavailable(index: Int) {
        if (pendingStart?.first == index) {
            pendingStart = null
            waitingChapter = null
            stopInternal("这本书没有可朗读的后续章节")
            return
        }
        if (waitingChapter != index) return
        waitingChapter = null
        stopInternal("已读到书末")
    }

    // ---- 播放控制 ----

    /** 从指定章 / 句开始朗读（章未加载时先准备）。 */
    fun startAt(chapterIndex: Int, sentenceIndex: Int = 0) {
        engineError = null
        val chapter = chapterAt(chapterIndex)
        if (chapter == null || chapter.sentences.isEmpty()) {
            pendingStart = chapterIndex to sentenceIndex
            waitingChapter = chapterIndex
            playback = TtsPlayback.Preparing
            message = null
            emit()
            onRequestChapter(chapterIndex)
            return
        }
        this.chapterIndex = chapter.index
        this.sentenceIndex = sentenceIndex.coerceIn(0, chapter.sentences.lastIndex)
        pendingStart = null
        waitingChapter = null
        playback = TtsPlayback.Playing
        message = null
        speakCurrent()
    }

    fun play() {
        if (playback == TtsPlayback.Paused) resume()
    }

    fun pause() {
        if (playback != TtsPlayback.Playing) return
        engine.stop()
        speaking = false
        playback = TtsPlayback.Paused
        emit()
    }

    fun resume() {
        if (playback != TtsPlayback.Paused) return
        if (activeSentence() == null) return
        playback = TtsPlayback.Playing
        speakCurrent()
    }

    fun toggle() {
        when (playback) {
            TtsPlayback.Playing -> pause()
            TtsPlayback.Paused -> resume()
            TtsPlayback.Idle,
            TtsPlayback.Preparing -> Unit
        }
    }

    fun next() {
        val chapter = chapterAt(chapterIndex)
        if (chapter != null && sentenceIndex + 1 < chapter.sentences.size) {
            sentenceIndex++
            playback = TtsPlayback.Playing
            speakCurrent()
            return
        }
        if (chapter == null) return
        playback = TtsPlayback.Playing
        advancePastChapter()
    }

    fun previous() {
        if (sentenceIndex > 0) {
            sentenceIndex--
            playback = TtsPlayback.Playing
            speakCurrent()
            return
        }
        val previousChapter = chapterAt(chapterIndex - 1) ?: return
        if (previousChapter.sentences.isEmpty()) return
        chapterIndex = previousChapter.index
        sentenceIndex = previousChapter.sentences.lastIndex
        playback = TtsPlayback.Playing
        speakCurrent()
    }

    /** 停止并清空位置（关闭听书 / 离开阅读页 / 定时到点）。 */
    fun stop() = stopInternal(null)

    /**
     * 引擎不可用（设备没有任何可绑定的 TTS 引擎，W6 T9r）。
     *
     * 停止朗读并留下一条用户可读的 [engineError]，控制条 / 设置弹层据此给出明确提示。**不自动重试**： 只有用户再次主动点「听书」才会重新走引擎绑定（避免失败轮询）。
     */
    fun reportEngineUnavailable(message: String = ReaderTtsNoEngineMessage) {
        engine.stop()
        speaking = false
        playback = TtsPlayback.Idle
        chapterIndex = -1
        sentenceIndex = -1
        deadlineMs = null
        timerRemainingMs = null
        timer = TtsTimerOption.Off
        stopAtChapterEnd = false
        waitingChapter = null
        pendingStart = null
        consecutiveErrors = 0
        this.message = null
        engineError = message
        emit()
    }

    /** 引擎已就绪（用户重试成功）：清掉不可用提示。 */
    fun clearEngineUnavailable() {
        if (engineError == null) return
        engineError = null
        emit()
    }

    fun setRate(next: Float) {
        val value = if (next.isFinite()) next.coerceIn(ReaderTtsRateRange) else rate
        if (value == rate) return
        rate = value
        engine.setRate(rate)
        if (playback == TtsPlayback.Playing) speakCurrent() else emit()
    }

    /** 定时关闭（定稿六 §4）：15 / 30 / 60 分钟，或「本章结束后停止」。 */
    fun setTimer(option: TtsTimerOption) {
        timer = option
        stopAtChapterEnd = option.isEndOfChapter
        deadlineMs = option.minutes?.let { clock() + it * 60_000L }
        timerRemainingMs = deadlineMs?.let { it - clock() }
        emit()
    }

    /** 定时心跳：调用方每秒喂一次；到点直接 stop（系统 TTS 无音量包络，记观察项）。 */
    fun onTick(now: Long = clock()) {
        val deadline = deadlineMs ?: return
        val remaining = deadline - now
        if (remaining <= 0) {
            stopInternal("定时结束，已停止朗读")
        } else if (remaining != timerRemainingMs) {
            timerRemainingMs = remaining
            emit()
        }
    }

    /**
     * 把朗读位置挪到可见处（定稿六 §3「点按正文跳转朗读位置」）：按 href 找章，再取 progression 处或之后的第一句。[pause] =
     * 挪位后暂停（设置「点按正文时暂停」，默认保持播放）。
     */
    fun relocate(href: String, progression: Double?, pause: Boolean = false): Boolean {
        val target = locateReadingPoint(chapters, href, progression) ?: return false
        chapterIndex = target.first
        sentenceIndex = target.second
        waitingChapter = null
        pendingStart = null
        if (pause) {
            engine.stop()
            speaking = false
            playback = TtsPlayback.Paused
            emit()
        } else {
            playback = TtsPlayback.Playing
            speakCurrent()
        }
        return true
    }

    // ---- 引擎回调 ----

    /** `UtteranceProgressListener.onStart` / `onRangeStart`：真正开口后才显示句高亮。 */
    fun onUtteranceStarted(utteranceId: String) {
        val sentence = activeSentence() ?: return
        if (utteranceId != utteranceId(sentence)) return
        consecutiveErrors = 0
        if (speaking) return
        speaking = true
        emit()
    }

    /** `onRangeStart(utteranceId, start, end, frame)`：一句一个 utterance，句内区间不细分。 */
    fun onRangeStart(utteranceId: String) = onUtteranceStarted(utteranceId)

    fun onUtteranceFinished(utteranceId: String) {
        val sentence = activeSentence() ?: return
        if (utteranceId != utteranceId(sentence)) return
        if (playback != TtsPlayback.Playing) return
        speaking = false
        advanceAuto()
    }

    fun onUtteranceFailed(utteranceId: String) {
        val sentence = activeSentence() ?: return
        if (utteranceId != utteranceId(sentence)) return
        consecutiveErrors++
        if (consecutiveErrors >= ReaderTtsMaxConsecutiveErrors) {
            stopInternal("朗读引擎出错，已停止")
            return
        }
        if (playback == TtsPlayback.Playing) {
            advanceAuto()
        } else {
            speaking = false
            emit()
        }
    }

    // ---- 内部 ----

    private fun speakCurrent() {
        val sentence = activeSentence()
        if (sentence == null) {
            stopInternal("已读到书末")
            return
        }
        speaking = false
        engine.setRate(rate)
        val started = engine.speak(sentence.text, utteranceId(sentence))
        if (!started) {
            playback = TtsPlayback.Paused
            message = "朗读引擎不可用"
            emit()
            return
        }
        message = null
        emit()
    }

    /** 自动推进（句末回调）：章末按定时档决定停或续。 */
    private fun advanceAuto() {
        val chapter = chapterAt(chapterIndex) ?: return
        if (sentenceIndex + 1 < chapter.sentences.size) {
            sentenceIndex++
            speakCurrent()
            return
        }
        if (stopAtChapterEnd) {
            stopInternal("本章结束，已停止朗读")
            return
        }
        moveToNextChapter(chapterIndex + 1)
    }

    private fun advancePastChapter() {
        if (stopAtChapterEnd) {
            stopInternal("本章结束，已停止朗读")
            return
        }
        moveToNextChapter(chapterIndex + 1)
    }

    /** 续接下一章：已加载就播，未加载就请求（空章循环跳过）。 */
    private fun moveToNextChapter(from: Int) {
        var next = from
        while (true) {
            val chapter = chapterAt(next)
            if (chapter == null) {
                waitingChapter = next
                pendingStart = null
                playback = TtsPlayback.Preparing
                message = null
                emit()
                onRequestChapter(next)
                return
            }
            if (chapter.sentences.isEmpty()) {
                next++
                continue
            }
            chapterIndex = chapter.index
            sentenceIndex = 0
            playback = TtsPlayback.Playing
            speakCurrent()
            return
        }
    }

    private fun stopInternal(reason: String?) {
        engine.stop()
        speaking = false
        playback = TtsPlayback.Idle
        chapterIndex = -1
        sentenceIndex = -1
        deadlineMs = null
        timerRemainingMs = null
        timer = TtsTimerOption.Off
        stopAtChapterEnd = false
        waitingChapter = null
        pendingStart = null
        consecutiveErrors = 0
        message = reason
        emit()
    }

    private fun chapterAt(index: Int): TtsChapter? = chapters.firstOrNull { it.index == index }

    private fun activeSentence(): TtsSentence? {
        val chapter = chapterAt(chapterIndex) ?: return null
        return chapter.sentences.getOrNull(sentenceIndex)
    }

    private fun utteranceId(sentence: TtsSentence): String =
        "tts-${sentence.chapterIndex}-${sentence.index}"

    private fun snapshot(): ReaderTtsUiState =
        ReaderTtsUiState(
            playback = playback,
            active = activeSentence(),
            speaking = speaking,
            rate = rate,
            timer = timer,
            timerRemainingMs = timerRemainingMs,
            sentenceCount = chapters.sumOf { it.sentences.size },
            loadingChapter = waitingChapter ?: pendingStart?.first,
            degradedSentences = degradedSentences,
            engineError = engineError,
            message = message,
        )

    private fun emit() {
        onStateChanged(snapshot())
    }
}

/**
 * 阅读位置 → 朗读位置（纯函数）：同 href 章内取 progression 处或之后的第一句；找不到章返回 null。
 *
 * 「从当前位置朗读」与「点按正文跳转朗读位置」共用；progression 为 null 时从章首开始。
 */
internal fun locateReadingPoint(
    chapters: List<TtsChapter>,
    href: String,
    progression: Double?,
): Pair<Int, Int>? {
    val target = href.substringBefore('#')
    var fallback: Pair<Int, Int>? = null
    chapters
        .sortedBy { it.index }
        .forEach { chapter ->
            if (chapter.href.substringBefore('#') != target) return@forEach
            if (chapter.sentences.isEmpty()) return@forEach
            if (progression == null) return chapter.index to 0
            val first = chapter.sentences.indexOfFirst { (it.progression ?: 0.0) >= progression }
            if (first >= 0) return chapter.index to first
            // 进度落在本章末尾之外：记住本章最后一句，若书里还有更靠后的章再往后找。
            if (fallback == null) fallback = chapter.index to chapter.sentences.lastIndex
        }
    return fallback
}
