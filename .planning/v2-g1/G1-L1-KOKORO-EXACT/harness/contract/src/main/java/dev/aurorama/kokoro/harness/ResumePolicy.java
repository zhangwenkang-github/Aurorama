package dev.aurorama.kokoro.harness;

public final class ResumePolicy {
    private ResumePolicy() {}

    public static ResumeResult restore(String encoded) {
        PersistentPlaybackState state = PersistentPlaybackState.decode(encoded);
        PackWhitelist.requireAllowed(state.getPackId());
        SpeakerWhitelist.requireAllowed(state.getSpeakerName(), state.getSid());
        if (!state.getStatus().isInterrupted()) {
            throw new IllegalStateException(
                    "State is not resumable: " + state.getStatus());
        }
        PlaybackStateMachine machine = new PlaybackStateMachine();
        machine.restorePaused(
                new SentenceQueue(state.getSentences()), state.getStableIndex());
        return new ResumeResult(state, machine);
    }

    public static final class ResumeResult {
        private final PersistentPlaybackState state;
        private final PlaybackStateMachine stateMachine;

        private ResumeResult(
                PersistentPlaybackState state, PlaybackStateMachine stateMachine) {
            this.state = state;
            this.stateMachine = stateMachine;
        }

        public PersistentPlaybackState getState() {
            return state;
        }

        public PlaybackStateMachine getStateMachine() {
            return stateMachine;
        }

        public boolean isAutoPlay() {
            return false;
        }

        public AudioOwner getActiveOwner() {
            return AudioOwner.NONE;
        }

        public boolean requiresManualPlay() {
            return true;
        }
    }
}
