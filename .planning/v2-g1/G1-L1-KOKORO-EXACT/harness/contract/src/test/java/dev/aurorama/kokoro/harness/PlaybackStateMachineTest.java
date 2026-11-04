package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class PlaybackStateMachineTest {
    @Test
    public void tracksStableSentenceAcrossPreparationPlaybackAndNavigation() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.loadQueue(new SentenceQueue(Arrays.asList("一。", "二。", "三。")));

        assertEquals(PlaybackStatus.IDLE, machine.status());
        machine.requestPlay();
        assertEquals(PlaybackStatus.PREPARING, machine.status());
        machine.markPlaying();
        assertEquals(PlaybackStatus.PLAYING, machine.status());

        machine.onSentenceStarted(1);
        assertEquals(1, machine.currentIndex());
        assertEquals(1, machine.stableIndex());
        assertEquals("二。", machine.stableSentence());

        machine.selectNextSentence();
        assertEquals(2, machine.currentIndex());
        assertEquals("三。", machine.currentSentence());
        machine.selectPreviousSentence();
        assertEquals("二。", machine.currentSentence());
    }

    @Test
    public void focusLossAndPauseConvergeToPausedAtCurrentStableSentence() {
        PlaybackStateMachine machine = restoredAtSecondSentence();
        machine.requestPlay();
        machine.markPlaying();
        machine.onSentenceStarted(1);

        machine.focusLost();

        assertEquals(PlaybackStatus.PAUSED, machine.status());
        assertEquals(1, machine.stableIndex());
        assertEquals("二。", machine.stableSentence());
    }

    @Test
    public void stopAndCompletionNeverReturnToAutomaticPlayback() {
        PlaybackStateMachine stopped = restoredAtSecondSentence();
        stopped.requestPlay();
        stopped.markPlaying();
        stopped.stop();
        assertEquals(PlaybackStatus.STOPPED, stopped.status());

        PlaybackStateMachine completed = restoredAtSecondSentence();
        completed.requestPlay();
        completed.markPlaying();
        completed.complete();
        assertEquals(PlaybackStatus.COMPLETED, completed.status());
    }

    @Test
    public void requestingTheNextSentenceReturnsToPreparingBeforeNewAudio() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.loadQueue(new SentenceQueue(Arrays.asList("一。", "二。")));
        machine.requestPlay();
        machine.markPlaying();
        machine.onSentenceStarted(1);

        machine.requestPlay();

        assertEquals(PlaybackStatus.PREPARING, machine.status());
    }

    private static PlaybackStateMachine restoredAtSecondSentence() {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.restorePaused(
                new SentenceQueue(Arrays.asList("一。", "二。", "三。")), 1);
        return machine;
    }
}
