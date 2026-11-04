package dev.aurorama.kokoro.harness;

import java.util.Objects;

public final class PlaybackStateMachine {
    private SentenceQueue queue;
    private PlaybackStatus status = PlaybackStatus.IDLE;
    private int stableIndex = -1;

    public void loadQueue(SentenceQueue value) {
        queue = Objects.requireNonNull(value, "queue");
        queue.moveTo(0);
        stableIndex = 0;
        status = PlaybackStatus.IDLE;
    }

    public void restorePaused(SentenceQueue value, int stableSentenceIndex) {
        queue = Objects.requireNonNull(value, "queue");
        queue.moveTo(stableSentenceIndex);
        stableIndex = stableSentenceIndex;
        status = PlaybackStatus.PAUSED;
    }

    public void requestPlay() {
        requireQueue();
        if (status == PlaybackStatus.PREPARING) {
            return;
        }
        status = PlaybackStatus.PREPARING;
    }

    public void markPlaying() {
        requireQueue();
        if (status != PlaybackStatus.PREPARING) {
            throw new IllegalStateException("Can only enter PLAYING from PREPARING");
        }
        status = PlaybackStatus.PLAYING;
    }

    public void pause() {
        if (status == PlaybackStatus.PREPARING || status == PlaybackStatus.PLAYING) {
            stableIndex = queue.currentIndex();
            status = PlaybackStatus.PAUSED;
        }
    }

    public void focusLost() {
        pause();
    }

    public void onSentenceStarted(int index) {
        requireQueue();
        queue.moveTo(index);
        stableIndex = index;
    }

    public void selectNextSentence() {
        requireQueue();
        if (queue.hasNext()) {
            queue.moveNext();
            stableIndex = queue.currentIndex();
        }
    }

    public void selectPreviousSentence() {
        requireQueue();
        if (queue.hasPrevious()) {
            queue.movePrevious();
            stableIndex = queue.currentIndex();
        }
    }

    public void stop() {
        requireQueue();
        stableIndex = queue.currentIndex();
        status = PlaybackStatus.STOPPED;
    }

    public void cancel() {
        stop();
    }

    public void complete() {
        requireQueue();
        stableIndex = queue.currentIndex();
        status = PlaybackStatus.COMPLETED;
    }

    public void fail() {
        status = PlaybackStatus.ERROR;
    }

    public PlaybackStatus status() {
        return status;
    }

    public SentenceQueue queue() {
        requireQueue();
        return queue;
    }

    public int currentIndex() {
        return queue().currentIndex();
    }

    public int stableIndex() {
        return stableIndex;
    }

    public String currentSentence() {
        return queue().currentSentence();
    }

    public String stableSentence() {
        requireQueue();
        if (stableIndex < 0 || stableIndex >= queue.size()) {
            return "";
        }
        int current = queue.currentIndex();
        queue.moveTo(stableIndex);
        String sentence = queue.currentSentence();
        queue.moveTo(current);
        return sentence;
    }

    private void requireQueue() {
        if (queue == null) {
            throw new IllegalStateException("Playback queue has not been loaded");
        }
    }
}
