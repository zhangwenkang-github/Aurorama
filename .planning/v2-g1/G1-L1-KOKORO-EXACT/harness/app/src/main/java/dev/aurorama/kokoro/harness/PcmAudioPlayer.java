package dev.aurorama.kokoro.harness;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

import java.util.Arrays;

final class PcmAudioPlayer {
    interface Listener {
        void onStarted();

        void onProgress(long positionMillis);

        void onCompleted();

        void onError(Throwable error);
    }

    private static final int CHUNK_FRAMES = 2048;

    private final Object lock = new Object();
    private Playback active;

    void play(
            float[] samples,
            int sampleRate,
            boolean speech,
            Listener listener) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("PCM samples cannot be empty");
        }
        synchronized (lock) {
            if (active != null) {
                active.stop();
                active = null;
            }
            Playback playback =
                    new Playback(
                            Arrays.copyOf(samples, samples.length),
                            sampleRate,
                            speech,
                            listener);
            active = playback;
            playback.start();
        }
    }

    void stop() {
        Playback playback;
        synchronized (lock) {
            playback = active;
            active = null;
        }
        if (playback != null) {
            playback.stop();
        }
    }

    boolean isPlaying() {
        synchronized (lock) {
            return active != null && active.isRunning();
        }
    }

    private final class Playback implements Runnable {
        private final float[] samples;
        private final int sampleRate;
        private final boolean speech;
        private final Listener listener;
        private final AudioTrack track;
        private final Thread thread;

        private volatile boolean running = true;
        private volatile boolean completed;

        Playback(
                float[] samples,
                int sampleRate,
                boolean speech,
                Listener listener) {
            this.samples = samples;
            this.sampleRate = sampleRate;
            this.speech = speech;
            this.listener = listener;
            int minimum =
                    AudioTrack.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                throw new IllegalStateException("AudioTrack minimum buffer is invalid: " + minimum);
            }
            AudioAttributes attributes =
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(
                                    speech
                                            ? AudioAttributes.CONTENT_TYPE_SPEECH
                                            : AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build();
            AudioFormat format =
                    new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build();
            track =
                    new AudioTrack.Builder()
                            .setAudioAttributes(attributes)
                            .setAudioFormat(format)
                            .setTransferMode(AudioTrack.MODE_STREAM)
                            .setBufferSizeInBytes(Math.max(minimum * 4, sampleRate / 2))
                            .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                throw new IllegalStateException("AudioTrack failed to initialize");
            }
            thread = new Thread(this, "kokoro-pcm-audio");
            thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        boolean isRunning() {
            return running;
        }

        void stop() {
            running = false;
            try {
                track.pause();
            } catch (IllegalStateException ignored) {
                // The track may already have completed or been released.
            }
            try {
                track.flush();
            } catch (IllegalStateException ignored) {
                // The track may already have completed or been released.
            }
        }

        @Override
        public void run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            Throwable failure = null;
            int sampleOffset = 0;
            try {
                track.play();
                listener.onStarted();
                short[] chunk = new short[CHUNK_FRAMES];
                while (running && sampleOffset < samples.length) {
                    int frames = Math.min(CHUNK_FRAMES, samples.length - sampleOffset);
                    for (int index = 0; index < frames; index++) {
                        chunk[index] = toPcm16(samples[sampleOffset + index]);
                    }
                    int written = track.write(chunk, 0, frames, AudioTrack.WRITE_BLOCKING);
                    if (written < 0) {
                        throw new IllegalStateException("AudioTrack write failed: " + written);
                    }
                    sampleOffset += written;
                    listener.onProgress(
                            Math.round(sampleOffset * 1000.0 / Math.max(1, sampleRate)));
                }
                completed = running && sampleOffset >= samples.length;
            } catch (Throwable error) {
                if (running) {
                    failure = error;
                }
            } finally {
                running = false;
                try {
                    track.stop();
                } catch (IllegalStateException ignored) {
                    // A failed or already-stopped AudioTrack is safe to release below.
                }
                track.release();
                synchronized (lock) {
                    if (active == this) {
                        active = null;
                    }
                }
            }
            if (failure != null) {
                listener.onError(failure);
            } else if (completed) {
                listener.onCompleted();
            }
        }
    }

    private static short toPcm16(float sample) {
        if (Float.isNaN(sample)) {
            return 0;
        }
        long scaled = Math.round(Math.max(-1.0f, Math.min(1.0f, sample)) * 32_768.0f);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
    }
}
