package dev.aurorama.kokoro.harness;

import java.util.Objects;

public final class VoiceProfile {
    private final String name;
    private final int sid;

    VoiceProfile(String name, int sid) {
        this.name = Objects.requireNonNull(name, "name");
        this.sid = sid;
    }

    public String getName() {
        return name;
    }

    public int getSid() {
        return sid;
    }
}
