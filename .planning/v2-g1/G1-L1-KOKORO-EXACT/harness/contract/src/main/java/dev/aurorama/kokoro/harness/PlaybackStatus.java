package dev.aurorama.kokoro.harness;

public enum PlaybackStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    STOPPED,
    COMPLETED,
    ERROR;

    public boolean isInterrupted() {
        return this == PREPARING || this == PLAYING || this == PAUSED;
    }
}
