package dev.aurorama.kokoro.harness;

public final class AudioOwnerArbiter {
    public enum MusicStatus {
        IDLE,
        PLAYING,
        PAUSED,
        STOPPED
    }

    private final int musicTrackCount;
    private AudioOwner activeOwner = AudioOwner.NONE;
    private MusicStatus musicStatus = MusicStatus.IDLE;
    private int musicIndex;

    public AudioOwnerArbiter(int musicTrackCount) {
        if (musicTrackCount < 1) {
            throw new IllegalArgumentException("Music track count must be positive");
        }
        this.musicTrackCount = musicTrackCount;
    }

    public ArbitrationDecision beginTts() {
        boolean pauseMusic = activeOwner == AudioOwner.MUSIC;
        if (pauseMusic) {
            musicStatus = MusicStatus.PAUSED;
        }
        activeOwner = AudioOwner.TTS;
        return new ArbitrationDecision(pauseMusic, false);
    }

    public ArbitrationDecision beginMusic() {
        boolean terminateTts = activeOwner == AudioOwner.TTS;
        if (terminateTts) {
            activeOwner = AudioOwner.NONE;
        }
        musicStatus = MusicStatus.PLAYING;
        activeOwner = AudioOwner.MUSIC;
        return new ArbitrationDecision(false, terminateTts);
    }

    public void pauseTts() {
        if (activeOwner == AudioOwner.TTS) {
            activeOwner = AudioOwner.NONE;
        }
    }

    public void stopTts() {
        if (activeOwner == AudioOwner.TTS) {
            activeOwner = AudioOwner.NONE;
        }
    }

    public void pauseMusic() {
        if (activeOwner == AudioOwner.MUSIC) {
            activeOwner = AudioOwner.NONE;
        }
        if (musicStatus == MusicStatus.PLAYING) {
            musicStatus = MusicStatus.PAUSED;
        }
    }

    public void stopMusic() {
        if (activeOwner == AudioOwner.MUSIC) {
            activeOwner = AudioOwner.NONE;
        }
        musicStatus = MusicStatus.STOPPED;
    }

    public void nextMusicTrack() {
        if (musicIndex + 1 < musicTrackCount) {
            musicIndex++;
        }
    }

    public void previousMusicTrack() {
        if (musicIndex > 0) {
            musicIndex--;
        }
    }

    public AudioOwner activeOwner() {
        return activeOwner;
    }

    public MusicStatus musicStatus() {
        return musicStatus;
    }

    public int musicIndex() {
        return musicIndex;
    }

    public boolean hasConflictingOwners() {
        return activeOwner == null;
    }

    public static final class ArbitrationDecision {
        private final boolean pauseMusic;
        private final boolean terminateTts;

        private ArbitrationDecision(boolean pauseMusic, boolean terminateTts) {
            this.pauseMusic = pauseMusic;
            this.terminateTts = terminateTts;
        }

        public boolean mustPauseMusic() {
            return pauseMusic;
        }

        public boolean mustTerminateTts() {
            return terminateTts;
        }
    }
}
