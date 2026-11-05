package dev.aurorama.kokoro.harness;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class RuntimeControlPolicy {
    public enum NotificationAction {
        STOP,
        PLAY_PAUSE,
        NEXT,
        PREVIOUS
    }

    private static final List<NotificationAction> ALL_NOTIFICATION_ACTIONS =
            Collections.unmodifiableList(
                    Arrays.asList(
                            NotificationAction.STOP,
                            NotificationAction.PLAY_PAUSE,
                            NotificationAction.NEXT,
                            NotificationAction.PREVIOUS));

    private RuntimeControlPolicy() {}

    public static List<NotificationAction> allNotificationActions() {
        return ALL_NOTIFICATION_ACTIONS;
    }

    public static List<NotificationAction> compactNotificationActions() {
        return ALL_NOTIFICATION_ACTIONS.subList(0, 3);
    }

    public static boolean requiresForeground(PlaybackStatus status) {
        return status == PlaybackStatus.PREPARING
                || status == PlaybackStatus.PLAYING
                || status == PlaybackStatus.PAUSED;
    }
}
