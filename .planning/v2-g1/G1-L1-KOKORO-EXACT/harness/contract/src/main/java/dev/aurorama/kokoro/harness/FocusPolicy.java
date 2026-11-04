package dev.aurorama.kokoro.harness;

public final class FocusPolicy {
    public enum FocusChange {
        GAIN,
        LOSS,
        LOSS_TRANSIENT,
        LOSS_TRANSIENT_CAN_DUCK
    }

    public enum FocusAction {
        KEEP_PLAYING,
        PAUSE
    }

    private FocusPolicy() {}

    public static FocusAction onFocusChange(FocusChange change) {
        switch (change) {
            case GAIN:
                return FocusAction.KEEP_PLAYING;
            case LOSS:
            case LOSS_TRANSIENT:
            case LOSS_TRANSIENT_CAN_DUCK:
                return FocusAction.PAUSE;
            default:
                throw new IllegalArgumentException("Unknown focus change: " + change);
        }
    }
}
