package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public final class SpeakerWhitelistTest {
    @Test
    public void allowsExactlyTheTwoApprovedVoices() {
        assertEquals(List.of(3, 59), SpeakerWhitelist.allowedSpeakerIds());

        VoiceProfile female = SpeakerWhitelist.requireAllowed("zf_001");
        VoiceProfile male = SpeakerWhitelist.requireAllowed("zm_010");

        assertEquals("zf_001", female.getName());
        assertEquals(3, female.getSid());
        assertEquals("zm_010", male.getName());
        assertEquals(59, male.getSid());
    }

    @Test
    public void explicitlyRejectsSpeaker58AndZm009() {
        IllegalArgumentException sidError =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> SpeakerWhitelist.requireAllowed("zm_009"));
        assertTrue(sidError.getMessage().contains("speaker 58"));

        IllegalArgumentException pairError =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> SpeakerWhitelist.requireAllowed("zm_009", 58));
        assertTrue(pairError.getMessage().contains("speaker 58"));
    }

    @Test
    public void rejectsMismatchedNamesUnknownIdsAndNulls() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeakerWhitelist.requireAllowed("zf_001", 59));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeakerWhitelist.requireAllowed("zf_002"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SpeakerWhitelist.requireAllowed((String) null));
    }
}
