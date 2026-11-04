package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AudioOwnerArbiterTest {
    @Test
    public void ttsStartPausesMusicButRetainsItsQueue() {
        AudioOwnerArbiter arbiter = new AudioOwnerArbiter(3);
        arbiter.beginMusic();
        arbiter.nextMusicTrack();

        AudioOwnerArbiter.ArbitrationDecision decision = arbiter.beginTts();

        assertTrue(decision.mustPauseMusic());
        assertEquals(AudioOwner.TTS, arbiter.activeOwner());
        assertEquals(AudioOwnerArbiter.MusicStatus.PAUSED, arbiter.musicStatus());
        assertEquals(1, arbiter.musicIndex());
    }

    @Test
    public void stoppingTtsLeavesMusicPausedInsteadOfAutoResumingIt() {
        AudioOwnerArbiter arbiter = new AudioOwnerArbiter(2);
        arbiter.beginMusic();
        arbiter.beginTts();

        arbiter.stopTts();

        assertEquals(AudioOwner.NONE, arbiter.activeOwner());
        assertEquals(AudioOwnerArbiter.MusicStatus.PAUSED, arbiter.musicStatus());
    }

    @Test
    public void musicStartTerminatesTtsFirstAndNeverCreatesTwoOwners() {
        AudioOwnerArbiter arbiter = new AudioOwnerArbiter(2);
        arbiter.beginTts();

        AudioOwnerArbiter.ArbitrationDecision decision = arbiter.beginMusic();

        assertTrue(decision.mustTerminateTts());
        assertEquals(AudioOwner.MUSIC, arbiter.activeOwner());
        assertEquals(AudioOwnerArbiter.MusicStatus.PLAYING, arbiter.musicStatus());
        assertFalse(arbiter.hasConflictingOwners());
    }

    @Test
    public void fullSequenceKeepsExactlyOneOwnerAtEveryStep() {
        AudioOwnerArbiter arbiter = new AudioOwnerArbiter(4);
        assertSingle(arbiter);
        arbiter.beginMusic();
        assertSingle(arbiter);
        arbiter.beginTts();
        assertSingle(arbiter);
        arbiter.stopTts();
        assertSingle(arbiter);
        arbiter.beginMusic();
        assertSingle(arbiter);
        arbiter.pauseMusic();
        assertSingle(arbiter);
    }

    private static void assertSingle(AudioOwnerArbiter arbiter) {
        assertFalse(arbiter.hasConflictingOwners());
        assertTrue(
                arbiter.activeOwner() == AudioOwner.NONE
                        || arbiter.activeOwner() == AudioOwner.TTS
                        || arbiter.activeOwner() == AudioOwner.MUSIC);
    }
}
