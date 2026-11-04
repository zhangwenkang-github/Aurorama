package dev.aurorama.kokoro.harness;

import java.util.Objects;

public final class NarrationRequest {
    private final String text;
    private final VoiceProfile voice;
    private final GenerationParameters parameters;

    NarrationRequest(String text, VoiceProfile voice, GenerationParameters parameters) {
        this.text = Objects.requireNonNull(text, "text");
        this.voice = Objects.requireNonNull(voice, "voice");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
    }

    public String getText() {
        return text;
    }

    public VoiceProfile getVoice() {
        return voice;
    }

    public GenerationParameters getParameters() {
        return parameters;
    }
}
