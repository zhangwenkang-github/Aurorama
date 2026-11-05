package dev.aurorama.kokoro.focuspeer;

import android.app.Activity;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

public final class FocusPeerActivity extends Activity {
    private static final String TAG = "KOKORO_FOCUS_PEER";
    private static final int SAMPLE_RATE = 24_000;
    private static final int HOLD_SECONDS = 10;

    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private AudioTrack track;
    private Thread audioThread;
    private volatile boolean focusHeld;
    private volatile boolean running;
    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        statusView = new TextView(this);
        statusView.setGravity(Gravity.CENTER);
        statusView.setTextSize(20);
        statusView.setText("Kokoro independent focus peer");
        setContentView(statusView);

        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        AudioAttributes attributes =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build();
        focusRequest =
                new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(attributes)
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(
                                change -> Log.i(TAG, "PEER_FOCUS_CHANGE change=" + change))
                        .build();
        int result = audioManager.requestAudioFocus(focusRequest);
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Log.i(
                TAG,
                "PEER_FOCUS_REQUEST result="
                        + result
                        + " focus=GAIN_TRANSIENT"
                        + " uid="
                        + Process.myUid());
        if (!focusHeld) {
            statusView.setText("Focus request rejected: " + result);
            return;
        }
        statusView.setText("Holding transient focus for " + HOLD_SECONDS + " seconds");
        startPeerAudio();
    }

    private void startPeerAudio() {
        AudioFormat format =
                new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build();
        int minimum =
                AudioTrack.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
        if (minimum <= 0) {
            finishWithError("Invalid AudioTrack minimum buffer: " + minimum);
            return;
        }
        track =
                new AudioTrack.Builder()
                        .setAudioAttributes(
                                new AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .build())
                        .setAudioFormat(format)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes(Math.max(minimum * 4, SAMPLE_RATE / 2))
                        .build();
        if (track.getState() != AudioTrack.STATE_INITIALIZED) {
            finishWithError("AudioTrack failed to initialize");
            return;
        }
        running = true;
        audioThread =
                new Thread(
                        () -> {
                            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
                            int totalFrames = SAMPLE_RATE * HOLD_SECONDS;
                            int writtenFrames = 0;
                            short[] chunk = new short[2048];
                            try {
                                track.play();
                                Log.i(
                                        TAG,
                                        "PEER_AUDIO_TRACK_STARTED uid="
                                                + Process.myUid()
                                                + " durationMillis="
                                                + HOLD_SECONDS * 1000L);
                                runOnUiThread(
                                        () -> statusView.setText("Transient focus + real AudioTrack active"));
                                while (running && writtenFrames < totalFrames) {
                                    int frames =
                                            Math.min(
                                                    chunk.length,
                                                    totalFrames - writtenFrames);
                                    for (int index = 0; index < frames; index++) {
                                        double seconds =
                                                (writtenFrames + index) / (double) SAMPLE_RATE;
                                        chunk[index] =
                                                (short)
                                                        (Math.sin(
                                                                        2.0
                                                                                * Math.PI
                                                                                * 330.0
                                                                                * seconds)
                                                                * 1400);
                                    }
                                    int written =
                                            track.write(
                                                    chunk,
                                                    0,
                                                    frames,
                                                    AudioTrack.WRITE_BLOCKING);
                                    if (written < 0) {
                                        throw new IllegalStateException(
                                                "AudioTrack write failed: " + written);
                                    }
                                    writtenFrames += written;
                                }
                                Log.i(
                                        TAG,
                                        "PEER_AUDIO_TRACK_COMPLETED uid="
                                                + Process.myUid()
                                                + " frames="
                                                + writtenFrames);
                            } catch (Throwable error) {
                                Log.e(TAG, "PEER_AUDIO_FAILED", error);
                            } finally {
                                running = false;
                                releaseTrack();
                                abandonFocus();
                                runOnUiThread(
                                        () ->
                                                statusView.setText(
                                                        "Transient focus released; main TTS must remain paused"));
                            }
                        },
                        "kokoro-focus-peer-audio");
        audioThread.start();
    }

    private void finishWithError(String message) {
        Log.e(TAG, "PEER_FAILED " + message);
        statusView.setText(message);
        abandonFocus();
        releaseTrack();
    }

    private void releaseTrack() {
        AudioTrack current = track;
        track = null;
        if (current == null) {
            return;
        }
        try {
            current.stop();
        } catch (IllegalStateException ignored) {
            // The peer track may already be stopped or uninitialized.
        }
        try {
            current.release();
        } catch (IllegalStateException ignored) {
            // Releasing a failed track must still leave the peer cleanup path confluent.
        }
    }

    private void abandonFocus() {
        if (!focusHeld) {
            return;
        }
        audioManager.abandonAudioFocusRequest(focusRequest);
        focusHeld = false;
        Log.i(TAG, "PEER_FOCUS_ABANDONED uid=" + Process.myUid());
    }

    @Override
    protected void onDestroy() {
        running = false;
        releaseTrack();
        abandonFocus();
        Log.i(TAG, "PEER_ACTIVITY_DESTROYED uid=" + Process.myUid());
        super.onDestroy();
    }
}
