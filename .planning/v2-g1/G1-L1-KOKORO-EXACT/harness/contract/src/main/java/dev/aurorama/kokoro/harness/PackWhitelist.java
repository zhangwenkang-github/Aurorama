package dev.aurorama.kokoro.harness;

public final class PackWhitelist {
    public static final String KOKORO_PACK_ID = "kokoro-multi-lang-v1_1";

    private PackWhitelist() {}

    public static String requireAllowed(String packId) {
        if (!KOKORO_PACK_ID.equals(packId)) {
            throw new IllegalArgumentException("Voice pack is not approved for exact task: " + packId);
        }
        return packId;
    }
}
