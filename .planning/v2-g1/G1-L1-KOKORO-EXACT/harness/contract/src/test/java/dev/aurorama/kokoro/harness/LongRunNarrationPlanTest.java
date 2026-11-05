package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.Test;

public final class LongRunNarrationPlanTest {
    @Test
    public void pinnedPlanCoversAtLeastThirtyOneMinutesAndStoresTextOnly() {
        LongRunNarrationPlan plan = LongRunNarrationPlan.pinned();

        assertEquals(31 * 60 * 1000L, plan.getTargetDurationMillis());
        assertTrue(plan.getPlannedMinimumDurationMillis() >= plan.getTargetDurationMillis());
        assertTrue(plan.getCycleCount() >= 39);
        assertEquals(
                plan.getCycleCount() * plan.getSourceSentenceCount(),
                plan.getSentences().size());
        assertEquals(
                SentenceQueue.fromText(NarrationContract.TEXT).size(),
                plan.getSourceSentenceCount());

        for (Field field : LongRunNarrationPlan.class.getDeclaredFields()) {
            assertFalse(
                    "Long-run plan must not materialize PCM arrays: " + field,
                    field.getType().isArray());
        }
    }

    @Test
    public void stableTextAdvancesAcrossRepeatedCyclesWithoutWrapping() {
        LongRunNarrationPlan plan = LongRunNarrationPlan.pinned();
        SentenceQueue source = SentenceQueue.fromText(NarrationContract.TEXT);
        SentenceQueue queue = new SentenceQueue(plan.getSentences());

        assertEquals(source.currentSentence(), queue.currentSentence());
        for (int index = 1; index < plan.getSourceSentenceCount(); index++) {
            queue.moveNext();
        }
        assertEquals(source.sentences().get(source.size() - 1), queue.currentSentence());
        queue.moveNext();
        assertEquals(source.currentSentence(), queue.currentSentence());
        assertEquals(plan.getSourceSentenceCount(), queue.currentIndex());
    }

    @Test
    public void navigationAndCompletionStopAtAbsoluteQueueBoundaries() {
        LongRunNarrationPlan plan = LongRunNarrationPlan.pinned();
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.loadQueue(new SentenceQueue(plan.getSentences()));

        machine.selectPreviousSentence();
        assertEquals(0, machine.currentIndex());

        machine.queue().moveTo(machine.queue().size() - 1);
        machine.selectNextSentence();
        assertEquals(machine.queue().size() - 1, machine.currentIndex());
        machine.requestPlay();
        machine.markPlaying();
        machine.complete();
        machine.selectNextSentence();
        assertEquals(PlaybackStatus.COMPLETED, machine.status());
        assertEquals(machine.queue().size() - 1, machine.currentIndex());
    }

    @Test
    public void stopCancelAndCompletionNeverRestoreOrAutoPlay() {
        LongRunNarrationPlan plan = LongRunNarrationPlan.pinned();

        PlaybackStateMachine stopped = activeAt(plan, 120);
        stopped.stop();
        assertTerminalWithoutAutoplay(stopped, PlaybackStatus.STOPPED);

        PlaybackStateMachine cancelled = activeAt(plan, 121);
        cancelled.cancel();
        assertTerminalWithoutAutoplay(cancelled, PlaybackStatus.STOPPED);

        PlaybackStateMachine completed = activeAt(plan, plan.getSentences().size() - 1);
        completed.complete();
        assertTerminalWithoutAutoplay(completed, PlaybackStatus.COMPLETED);
    }

    @Test
    public void persistedLongRunCheckpointRestoresManualPausedAtStableSentence() {
        LongRunNarrationPlan plan = LongRunNarrationPlan.pinned();
        int current = 233;
        int stable = 231;
        String encoded =
                PersistentPlaybackState.encode(
                        PackWhitelist.KOKORO_PACK_ID,
                        "zf_001",
                        3,
                        PlaybackStatus.PLAYING,
                        current,
                        stable,
                        3_250L,
                        plan.getSentences());

        ResumePolicy.ResumeResult result = ResumePolicy.restore(encoded);

        assertEquals(PlaybackStatus.PAUSED, result.getStateMachine().status());
        assertEquals(stable, result.getStateMachine().currentIndex());
        assertEquals(plan.getSentences().get(stable), result.getStateMachine().stableSentence());
        assertEquals(AudioOwner.NONE, result.getActiveOwner());
        assertFalse(result.isAutoPlay());
        assertTrue(result.requiresManualPlay());
    }

    private static PlaybackStateMachine activeAt(LongRunNarrationPlan plan, int index) {
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.loadQueue(new SentenceQueue(plan.getSentences()));
        machine.queue().moveTo(index);
        machine.requestPlay();
        machine.markPlaying();
        machine.onSentenceStarted(index);
        return machine;
    }

    private static void assertTerminalWithoutAutoplay(
            PlaybackStateMachine machine, PlaybackStatus expectedStatus) {
        assertEquals(expectedStatus, machine.status());
        assertEquals(AudioOwner.NONE, new AudioOwnerArbiter(3).activeOwner());
        String encoded =
                PersistentPlaybackState.encode(
                        PackWhitelist.KOKORO_PACK_ID,
                        "zf_001",
                        3,
                        machine.status(),
                        machine.currentIndex(),
                        machine.stableIndex(),
                        0L,
                        machine.queue().sentences());
        boolean restoreRejected = false;
        try {
            ResumePolicy.restore(encoded);
        } catch (IllegalStateException expected) {
            restoreRejected = true;
        }
        assertTrue("Terminal state must not be restorable", restoreRejected);
    }
}
