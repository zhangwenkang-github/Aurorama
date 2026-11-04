package dev.aurorama.kokoro.harness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class SpeakerWhitelist {
    public static final int ZF_001_SID = 3;
    public static final int REJECTED_SPEAKER_58_SID = 58;
    public static final int ZM_010_SID = 59;

    private static final VoiceProfile ZF_001 = new VoiceProfile("zf_001", ZF_001_SID);
    private static final VoiceProfile ZM_010 = new VoiceProfile("zm_010", ZM_010_SID);

    private SpeakerWhitelist() {}

    public static List<Integer> allowedSpeakerIds() {
        return Collections.unmodifiableList(Arrays.asList(ZF_001_SID, ZM_010_SID));
    }

    public static VoiceProfile requireAllowed(String name) {
        rejectForbiddenSpeaker(name, -1);
        if ("zf_001".equals(name)) {
            return ZF_001;
        }
        if ("zm_010".equals(name)) {
            return ZM_010;
        }
        throw new IllegalArgumentException("Speaker is not in the exact-task whitelist: " + name);
    }

    public static VoiceProfile requireAllowed(String name, int sid) {
        rejectForbiddenSpeaker(name, sid);
        VoiceProfile profile = requireAllowed(name);
        if (profile.getSid() != sid) {
            throw new IllegalArgumentException(
                    "Speaker/sid mismatch: " + name + " must use sid " + profile.getSid());
        }
        return profile;
    }

    private static void rejectForbiddenSpeaker(String name, int sid) {
        if (sid == REJECTED_SPEAKER_58_SID || "zm_009".equals(name)) {
            throw new IllegalArgumentException(
                    "speaker 58 / zm_009 is permanently rejected by the exact-task whitelist");
        }
    }
}
