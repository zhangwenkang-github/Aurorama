package dev.aurorama.kokoro.harness;

import java.util.Objects;

public final class RuntimePresentation {
    public enum PlayPauseAction {
        PLAY_TTS,
        PAUSE_TTS,
        PLAY_MUSIC,
        PAUSE_MUSIC,
        NONE
    }

    private final String notificationTitle;
    private final String notificationText;
    private final PlayPauseAction playPauseAction;
    private final String mediaStateLabel;

    private RuntimePresentation(
            String notificationTitle,
            String notificationText,
            PlayPauseAction playPauseAction,
            String mediaStateLabel) {
        this.notificationTitle = notificationTitle;
        this.notificationText = notificationText;
        this.playPauseAction = playPauseAction;
        this.mediaStateLabel = mediaStateLabel;
    }

    public static RuntimePresentation from(
            PlaybackStatus status,
            AudioOwner owner,
            String sentence,
            int index,
            int size) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(owner, "owner");
        String safeSentence = sentence == null ? "" : sentence.trim();
        String text =
                safeSentence.isEmpty()
                        ? ""
                        : safeSentence + " （" + (index + 1) + "/" + Math.max(1, size) + "）";
        switch (status) {
            case PREPARING:
                return new RuntimePresentation(
                        "正在准备朗读",
                        text,
                        PlayPauseAction.PAUSE_TTS,
                        "BUFFERING");
            case PLAYING:
                if (owner == AudioOwner.MUSIC) {
                    return new RuntimePresentation(
                            "本地音乐模拟播放中",
                            "音乐队列 · 与朗读共用单一音频 owner",
                            PlayPauseAction.PAUSE_MUSIC,
                            "PLAYING");
                }
                return new RuntimePresentation(
                        "正在朗读", text, PlayPauseAction.PAUSE_TTS, "PLAYING");
            case PAUSED:
                return new RuntimePresentation(
                        "朗读已暂停", text, PlayPauseAction.PLAY_TTS, "PAUSED");
            case STOPPED:
                return new RuntimePresentation(
                        "朗读已停止", text, PlayPauseAction.NONE, "STOPPED");
            case COMPLETED:
                return new RuntimePresentation(
                        "朗读已完成", text, PlayPauseAction.NONE, "STOPPED");
            case ERROR:
                return new RuntimePresentation(
                        "朗读错误", text, PlayPauseAction.NONE, "ERROR");
            case IDLE:
            default:
                return new RuntimePresentation(
                        "Kokoro runtime 待机", text, PlayPauseAction.PLAY_TTS, "NONE");
        }
    }

    public String getNotificationTitle() {
        return notificationTitle;
    }

    public String getNotificationText() {
        return notificationText;
    }

    public PlayPauseAction getPlayPauseAction() {
        return playPauseAction;
    }

    public String getMediaStateLabel() {
        return mediaStateLabel;
    }
}
