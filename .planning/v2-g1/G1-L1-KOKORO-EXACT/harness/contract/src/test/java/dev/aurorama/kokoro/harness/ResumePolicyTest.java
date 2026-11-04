package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class ResumePolicyTest {
    @Test
    public void persistsAndRestoresOnlyLastStableSentenceAsManualPausedState() {
        String encoded =
                PersistentPlaybackState.encode(
                        PackWhitelist.KOKORO_PACK_ID,
                        "zm_010",
                        59,
                        PlaybackStatus.PLAYING,
                        3,
                        2,
                        12_345L,
                        Arrays.asList("第一句。", "第二句。", "第三句。", "第四句。"));

        ResumePolicy.ResumeResult result = ResumePolicy.restore(encoded);

        assertEquals(PlaybackStatus.PAUSED, result.getStateMachine().status());
        assertEquals(2, result.getStateMachine().currentIndex());
        assertEquals("第三句。", result.getStateMachine().currentSentence());
        assertFalse(result.isAutoPlay());
        assertEquals(AudioOwner.NONE, result.getActiveOwner());
        assertTrue(result.requiresManualPlay());
    }

    @Test
    public void toleratesSharedPreferencesTrailingIndentWithoutWeakeningValidation() {
        String encoded =
                PersistentPlaybackState.encode(
                                PackWhitelist.KOKORO_PACK_ID,
                                "zf_001",
                                3,
                                PlaybackStatus.PLAYING,
                                0,
                                0,
                                42L,
                                Arrays.asList("测试。"))
                        + "\n    ";

        ResumePolicy.ResumeResult result = ResumePolicy.restore(encoded);

        assertEquals(PlaybackStatus.PAUSED, result.getStateMachine().status());
        assertEquals("测试。", result.getStateMachine().currentSentence());
    }

    @Test
    public void rejectsPackDriftBeforeAnyPlaybackCanResume() {
        String encoded =
                PersistentPlaybackState.encode(
                        "unapproved-pack",
                        "zf_001",
                        3,
                        PlaybackStatus.PLAYING,
                        0,
                        0,
                        1L,
                        Arrays.asList("测试。"));

        assertThrows(IllegalArgumentException.class, () -> ResumePolicy.restore(encoded));
    }

    @Test
    public void rejectsSpeaker58AndOtherSpeakerDriftBeforeAnyPlaybackCanResume() {
        String rejected =
                PersistentPlaybackState.encode(
                        PackWhitelist.KOKORO_PACK_ID,
                        "zm_009",
                        58,
                        PlaybackStatus.PLAYING,
                        0,
                        0,
                        1L,
                        Arrays.asList("测试。"));
        String mismatchedSid =
                PersistentPlaybackState.encode(
                        PackWhitelist.KOKORO_PACK_ID,
                        "zm_010",
                        3,
                        PlaybackStatus.PLAYING,
                        0,
                        0,
                        1L,
                        Arrays.asList("测试。"));

        assertThrows(IllegalArgumentException.class, () -> ResumePolicy.restore(rejected));
        assertThrows(IllegalArgumentException.class, () -> ResumePolicy.restore(mismatchedSid));
    }

    @Test
    public void userStoppedOrCompletedStateDoesNotBecomeResumablePausedPlayback() {
        String stopped =
                PersistentPlaybackState.encode(
                        PackWhitelist.KOKORO_PACK_ID,
                        "zf_001",
                        3,
                        PlaybackStatus.STOPPED,
                        0,
                        0,
                        0L,
                        Arrays.asList("测试。"));

        assertThrows(IllegalStateException.class, () -> ResumePolicy.restore(stopped));
    }

    private static void assertThrows(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getName() + " but got " + error, error);
        }
        throw new AssertionError("Expected " + expected.getName());
    }
}
