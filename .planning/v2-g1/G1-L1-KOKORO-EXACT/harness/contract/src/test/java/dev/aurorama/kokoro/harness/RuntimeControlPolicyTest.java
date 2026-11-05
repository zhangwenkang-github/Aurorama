package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;
import org.junit.Test;

public final class RuntimeControlPolicyTest {
    @Test
    public void compactNotificationPrioritizesStopAndExposesExactlyThreeActions() {
        List<RuntimeControlPolicy.NotificationAction> compact =
                RuntimeControlPolicy.compactNotificationActions();

        assertEquals(3, compact.size());
        assertEquals(RuntimeControlPolicy.NotificationAction.STOP, compact.get(0));
        assertEquals(RuntimeControlPolicy.NotificationAction.PLAY_PAUSE, compact.get(1));
        assertEquals(RuntimeControlPolicy.NotificationAction.NEXT, compact.get(2));
        assertEquals(compact.size(), new HashSet<>(compact).size());
    }

    @Test
    public void notificationRecordRetainsRealPreviousAndNextCommands() {
        List<RuntimeControlPolicy.NotificationAction> all =
                RuntimeControlPolicy.allNotificationActions();

        assertEquals(4, all.size());
        assertTrue(all.contains(RuntimeControlPolicy.NotificationAction.PREVIOUS));
        assertTrue(all.contains(RuntimeControlPolicy.NotificationAction.NEXT));
        assertTrue(all.contains(RuntimeControlPolicy.NotificationAction.PLAY_PAUSE));
        assertTrue(all.contains(RuntimeControlPolicy.NotificationAction.STOP));
    }

    @Test
    public void pausedPlaybackKeepsForegroundButTerminalStatesRemoveIt() {
        assertTrue(RuntimeControlPolicy.requiresForeground(PlaybackStatus.PREPARING));
        assertTrue(RuntimeControlPolicy.requiresForeground(PlaybackStatus.PLAYING));
        assertTrue(RuntimeControlPolicy.requiresForeground(PlaybackStatus.PAUSED));
        assertFalse(RuntimeControlPolicy.requiresForeground(PlaybackStatus.IDLE));
        assertFalse(RuntimeControlPolicy.requiresForeground(PlaybackStatus.STOPPED));
        assertFalse(RuntimeControlPolicy.requiresForeground(PlaybackStatus.COMPLETED));
        assertFalse(RuntimeControlPolicy.requiresForeground(PlaybackStatus.ERROR));
    }
}
