package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RuntimePresentationTest {
    @Test
    public void playingPresentationUsesSameStateForUiNotificationAndMediaSession() {
        RuntimePresentation presentation =
                RuntimePresentation.from(
                        PlaybackStatus.PLAYING, AudioOwner.TTS, "第二句。", 1, 4);

        assertEquals("正在朗读", presentation.getNotificationTitle());
        assertEquals("第二句。 （2/4）", presentation.getNotificationText());
        assertEquals(RuntimePresentation.PlayPauseAction.PAUSE_TTS, presentation.getPlayPauseAction());
        assertEquals("PLAYING", presentation.getMediaStateLabel());
    }

    @Test
    public void pausedPresentationOffersManualPlayAndNeverAutoResumes() {
        RuntimePresentation presentation =
                RuntimePresentation.from(
                        PlaybackStatus.PAUSED, AudioOwner.NONE, "第二句。", 1, 4);

        assertEquals("朗读已暂停", presentation.getNotificationTitle());
        assertEquals(RuntimePresentation.PlayPauseAction.PLAY_TTS, presentation.getPlayPauseAction());
        assertEquals("PAUSED", presentation.getMediaStateLabel());
    }

    @Test
    public void focusLossPolicyPausesEveryLossVariant() {
        assertEquals(
                FocusPolicy.FocusAction.PAUSE,
                FocusPolicy.onFocusChange(FocusPolicy.FocusChange.LOSS));
        assertEquals(
                FocusPolicy.FocusAction.PAUSE,
                FocusPolicy.onFocusChange(FocusPolicy.FocusChange.LOSS_TRANSIENT));
        assertEquals(
                FocusPolicy.FocusAction.PAUSE,
                FocusPolicy.onFocusChange(
                        FocusPolicy.FocusChange.LOSS_TRANSIENT_CAN_DUCK));
        assertEquals(
                FocusPolicy.FocusAction.KEEP_PLAYING,
                FocusPolicy.onFocusChange(FocusPolicy.FocusChange.GAIN));
    }
}
