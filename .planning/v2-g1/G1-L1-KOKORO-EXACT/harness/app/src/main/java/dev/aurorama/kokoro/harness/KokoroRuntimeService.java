package dev.aurorama.kokoro.harness;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import kotlin.jvm.functions.Function1;

public final class KokoroRuntimeService extends Service
        implements AudioManager.OnAudioFocusChangeListener {
    public static final String ACTION_COMMAND =
            "dev.aurorama.kokoro.harness.action.RUNTIME_COMMAND";
    public static final String EXTRA_COMMAND = "command";

    public static final String COMMAND_RESTORE = "restore";
    public static final String COMMAND_PLAY_SAMPLE = "play_sample";
    public static final String COMMAND_PLAY_FULL = "play_full";
    public static final String COMMAND_RESUME = "resume";
    public static final String COMMAND_PAUSE = "pause";
    public static final String COMMAND_PREVIOUS = "previous";
    public static final String COMMAND_NEXT = "next";
    public static final String COMMAND_STOP = "stop";
    public static final String COMMAND_CANCEL = "cancel";
    public static final String COMMAND_TOGGLE_VOICE = "toggle_voice";
    public static final String COMMAND_MUSIC_PLAY = "music_play";
    public static final String COMMAND_MUSIC_PAUSE = "music_pause";
    public static final String COMMAND_SIMULATE_FOCUS_LOSS = "simulate_focus_loss";
    public static final String COMMAND_DUMP_STATE = "dump_state";

    private static final String TAG = "KOKORO_RUNTIME";
    private static final String PREFS_NAME = "kokoro_runtime_state";
    private static final String PREF_CHECKPOINT = "checkpoint_v1";
    private static final String CHANNEL_ID = "kokoro_runtime_media";
    private static final int NOTIFICATION_ID = 0x4b4f4b4f;
    private static final int MUSIC_SAMPLE_RATE = 24_000;
    private static final int MUSIC_TRACK_SECONDS = 12;
    private static final long CANCEL_PROCESS_EXIT_DELAY_MILLIS = 1_000L;
    private static final String SAMPLE_TEXT =
            "雨停以后，旧车站的钟慢了两分钟。林舟把湿伞靠在长椅边，听见铜铃碰了三次。";

    public interface RuntimeListener {
        void onRuntimeStateChanged(String stateJson);
    }

    public final class LocalBinder extends Binder {
        public String getStateJson() {
            return latestStateJson;
        }

        public void sendCommand(String command) {
            mainHandler.post(() -> processCommand(command));
        }

        public void addListener(RuntimeListener listener) {
            listeners.addIfAbsent(listener);
            mainHandler.post(
                    () -> {
                        String snapshot = latestStateJson;
                        if (snapshot != null && !snapshot.isEmpty()) {
                            listener.onRuntimeStateChanged(snapshot);
                        }
                    });
        }

        public void removeListener(RuntimeListener listener) {
            listeners.remove(listener);
        }
    }

    private final LocalBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<RuntimeListener> listeners =
            new CopyOnWriteArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private final AudioOwnerArbiter arbiter = new AudioOwnerArbiter(3);
    private final PlaybackStateMachine playback = new PlaybackStateMachine();

    private ExecutorService ttsExecutor;
    private OfflineTts tts;
    private SharedPreferences preferences;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private PcmAudioPlayer audioPlayer;
    private MediaSession mediaSession;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;

    private VoiceProfile voice = SpeakerWhitelist.requireAllowed("zf_001");
    private String packId = PackWhitelist.KOKORO_PACK_ID;
    private boolean queueLoaded;
    private boolean focusHeld;
    private boolean foregroundStarted;
    private boolean shuttingDown;
    private long positionMillis;
    private String lastError = "";
    private String lastCommand = "";
    private volatile String latestStateJson = "{}";

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        notificationManager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        audioPlayer = new PcmAudioPlayer();
        ttsExecutor =
                Executors.newSingleThreadExecutor(
                        new ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable runnable) {
                                Thread thread = new Thread(runnable, "kokoro-tts-runtime");
                                thread.setDaemon(true);
                                return thread;
                            }
                        });
        createNotificationChannel();
        createMediaSession();
        AudioAttributes attributes =
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();
        focusRequest =
                new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(attributes)
                        .setWillPauseWhenDucked(true)
                        .setOnAudioFocusChangeListener(this, mainHandler)
                        .build();
        PowerManager powerManager =
                (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock =
                powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Aurorama:KokoroRuntimePlayback");
        wakeLock.setReferenceCounted(false);
        Log.i(TAG, "SERVICE_CREATED pid=" + android.os.Process.myPid());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String command = commandFromIntent(intent);
        if (command.isEmpty()) {
            command = COMMAND_RESTORE;
        }
        if ((command.equals(COMMAND_RESTORE)
                        || command.equals(COMMAND_DUMP_STATE)
                        || command.equals(COMMAND_MUSIC_PLAY)
                        || command.equals(COMMAND_PAUSE)
                        || command.equals(COMMAND_RESUME))
                && !queueLoaded) {
            restoreCheckpoint();
        }
        ensureForeground();
        final String selectedCommand = command;
        mainHandler.post(() -> processCommand(selectedCommand));
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (!shuttingDown) {
            cancelPlaybackToken();
            if (queueLoaded) {
                PlaybackStatus status = playback.status();
                if (status == PlaybackStatus.PLAYING || status == PlaybackStatus.PREPARING) {
                    playback.pause();
                }
                persistCheckpoint();
            }
        }
        releasePlaybackResources();
        releaseSessionAndForeground();
        if (ttsExecutor != null) {
            ttsExecutor.shutdownNow();
        }
        if (tts != null) {
            try {
                tts.release();
            } catch (Throwable error) {
                Log.w(TAG, "TTS release failed", error);
            }
            tts = null;
        }
        Log.i(TAG, "SERVICE_DESTROYED pid=" + android.os.Process.myPid());
        super.onDestroy();
    }

    private String commandFromIntent(Intent intent) {
        if (intent == null) {
            return "";
        }
        String command = intent.getStringExtra(EXTRA_COMMAND);
        if (command == null) {
            command = intent.getAction();
        }
        return command == null ? "" : command.trim();
    }

    private void processCommand(String command) {
        if (command == null || command.isEmpty()) {
            command = COMMAND_RESTORE;
        }
        if (shuttingDown && !command.equals(COMMAND_CANCEL)) {
            return;
        }
        lastCommand = command;
        lastError = "";
        Log.i(TAG, "COMMAND " + command);
        try {
            switch (command) {
                case COMMAND_RESTORE:
                    if (!queueLoaded) {
                        restoreCheckpoint();
                    }
                    publishState();
                    break;
                case COMMAND_PLAY_SAMPLE:
                    startTtsText(SAMPLE_TEXT);
                    break;
                case COMMAND_PLAY_FULL:
                    startTtsText(NarrationContract.TEXT);
                    break;
                case COMMAND_RESUME:
                    resumeTts();
                    break;
                case COMMAND_PAUSE:
                    if (arbiter.activeOwner() == AudioOwner.MUSIC) {
                        pauseMusic();
                    } else {
                        pauseTts();
                    }
                    break;
                case COMMAND_PREVIOUS:
                    navigate(-1);
                    break;
                case COMMAND_NEXT:
                    navigate(1);
                    break;
                case COMMAND_STOP:
                    stopTts();
                    break;
                case COMMAND_CANCEL:
                    cancelRuntime();
                    break;
                case COMMAND_TOGGLE_VOICE:
                    toggleVoice();
                    break;
                case COMMAND_MUSIC_PLAY:
                    startMusic();
                    break;
                case COMMAND_MUSIC_PAUSE:
                    pauseMusic();
                    break;
                case COMMAND_SIMULATE_FOCUS_LOSS:
                    handleFocusLoss("simulated_focus_loss");
                    break;
                case COMMAND_DUMP_STATE:
                    dumpState();
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported runtime command: " + command);
            }
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void startTtsText(String text) throws Exception {
        cancelPlaybackToken();
        audioPlayer.stop();
        releaseWakeLock();
        playback.loadQueue(SentenceQueue.fromText(text));
        queueLoaded = true;
        positionMillis = 0L;
        voice = SpeakerWhitelist.requireAllowed(voice.getName(), voice.getSid());
        packId = PackWhitelist.requireAllowed(packId);
        startCurrentTtsSentence();
    }

    private void startCurrentTtsSentence() {
        if (!queueLoaded) {
            throw new IllegalStateException("No TTS sentence queue is loaded");
        }
        audioPlayer.stop();
        AudioOwnerArbiter.ArbitrationDecision decision = arbiter.beginTts();
        if (decision.mustPauseMusic()) {
            Log.i(TAG, "MUSIC_PAUSED_FOR_TTS index=" + arbiter.musicIndex());
        }
        acquireAudioFocusOrFail();
        playback.requestPlay();
        playback.onSentenceStarted(playback.queue().currentIndex());
        positionMillis = 0L;
        final long token = generation.incrementAndGet();
        persistCheckpoint();
        publishState();
        final int sentenceIndex = playback.currentIndex();
        final String sentence = playback.currentSentence();
        final VoiceProfile selectedVoice = voice;
        Log.i(
                TAG,
                "TTS_SENTENCE_REQUEST index="
                        + sentenceIndex
                        + " speaker="
                        + selectedVoice.getName()
                        + " sid="
                        + selectedVoice.getSid()
                        + " generation="
                        + token);
        ttsExecutor.execute(
                () -> {
                    try {
                        OfflineTts engine = getOrCreateTts();
                        if (!isCurrentGeneration(token)) {
                            return;
                        }
                        GeneratedAudio audio =
                                engine.generateWithCallback(
                                        sentence,
                                        selectedVoice.getSid(),
                                        NarrationContract.PARAMETERS.getSpeed(),
                                        new CancellableCallback(token));
                        if (audio == null
                                || audio.getSamples() == null
                                || audio.getSamples().length == 0) {
                            throw new IllegalStateException(
                                    "TTS returned empty PCM for sentence " + sentenceIndex);
                        }
                        if (audio.getSampleRate() != MUSIC_SAMPLE_RATE) {
                            throw new IllegalStateException(
                                    "Unexpected TTS sample rate " + audio.getSampleRate());
                        }
                        final float[] samples = audio.getSamples();
                        mainHandler.post(
                                () -> playGeneratedTtsSentence(token, samples));
                    } catch (Throwable error) {
                        mainHandler.post(
                                () -> {
                                    if (isCurrentGeneration(token)) {
                                        fail(error);
                                    }
                                });
                    }
                });
    }

    private void playGeneratedTtsSentence(long token, float[] samples) {
        if (!isCurrentGeneration(token)
                || arbiter.activeOwner() != AudioOwner.TTS
                || playback.status() != PlaybackStatus.PREPARING) {
            Log.i(TAG, "TTS_STALE_RESULT_DISCARDED generation=" + token);
            return;
        }
        try {
            playback.markPlaying();
            acquireWakeLock();
            publishState();
            audioPlayer.play(
                    samples,
                    MUSIC_SAMPLE_RATE,
                    true,
                    new PcmAudioPlayer.Listener() {
                        @Override
                        public void onStarted() {
                            Log.i(
                                    TAG,
                                    "AUDIO_TRACK_STARTED owner=TTS generation="
                                            + token
                                            + " samples="
                                            + samples.length
                                            + " durationMillis="
                                            + Math.round(samples.length * 1000.0 / MUSIC_SAMPLE_RATE));
                            mainHandler.post(() -> publishState());
                        }

                        @Override
                        public void onProgress(long value) {
                            positionMillis = value;
                        }

                        @Override
                        public void onCompleted() {
                            Log.i(
                                    TAG,
                                    "AUDIO_TRACK_COMPLETED owner=TTS generation=" + token);
                            mainHandler.post(
                                    () -> {
                                        if (isCurrentGeneration(token)) {
                                            onTtsSentenceCompleted();
                                        }
                                    });
                        }

                        @Override
                        public void onError(Throwable error) {
                            mainHandler.post(
                                    () -> {
                                        if (isCurrentGeneration(token)) {
                                            fail(error);
                                        }
                                    });
                        }
                    });
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void onTtsSentenceCompleted() {
        if (arbiter.activeOwner() != AudioOwner.TTS) {
            return;
        }
        if (playback.queue().hasNext()) {
            playback.selectNextSentence();
            startCurrentTtsSentence();
            return;
        }
        playback.complete();
        arbiter.stopTts();
        releasePlaybackResources();
        persistCheckpoint();
        publishState();
        Log.i(TAG, "TTS_COMPLETED");
    }

    private void resumeTts() {
        if (!queueLoaded) {
            throw new IllegalStateException("No persisted TTS queue is available");
        }
        if (playback.status() != PlaybackStatus.PAUSED) {
            Log.i(TAG, "TTS_RESUME_IGNORED status=" + playback.status());
            publishState();
            return;
        }
        startCurrentTtsSentence();
    }

    private void pauseTts() {
        if (arbiter.activeOwner() == AudioOwner.MUSIC) {
            pauseMusic();
            return;
        }
        boolean active =
                playback.status() == PlaybackStatus.PREPARING
                        || playback.status() == PlaybackStatus.PLAYING;
        cancelPlaybackToken();
        audioPlayer.stop();
        if (active) {
            playback.pause();
        }
        arbiter.pauseTts();
        releasePlaybackResources();
        persistCheckpoint();
        publishState();
        Log.i(TAG, "TTS_PAUSED index=" + playback.currentIndex());
    }

    private void stopTts() {
        cancelPlaybackToken();
        audioPlayer.stop();
        if (queueLoaded) {
            playback.stop();
            persistCheckpoint();
        }
        arbiter.stopTts();
        releasePlaybackResources();
        publishState();
        Log.i(
                TAG,
                "TTS_STOPPED music="
                        + arbiter.musicStatus()
                        + " musicIndex="
                        + arbiter.musicIndex());
    }

    private void navigate(int direction) {
        if (arbiter.activeOwner() == AudioOwner.MUSIC) {
            if (direction < 0) {
                arbiter.previousMusicTrack();
            } else {
                arbiter.nextMusicTrack();
            }
            if (arbiter.musicStatus() == AudioOwnerArbiter.MusicStatus.PLAYING) {
                startMusic();
            } else {
                publishState();
            }
            return;
        }
        if (!queueLoaded) {
            return;
        }
        boolean wasActive =
                playback.status() == PlaybackStatus.PREPARING
                        || playback.status() == PlaybackStatus.PLAYING;
        cancelPlaybackToken();
        audioPlayer.stop();
        int before = playback.currentIndex();
        if (direction < 0) {
            playback.selectPreviousSentence();
        } else {
            playback.selectNextSentence();
        }
        playback.onSentenceStarted(playback.currentIndex());
        positionMillis = 0L;
        Log.i(
                TAG,
                "TTS_NAVIGATE from="
                        + before
                        + " to="
                        + playback.currentIndex()
                        + " wasActive="
                        + wasActive);
        if (wasActive) {
            startCurrentTtsSentence();
        } else {
            persistCheckpoint();
            publishState();
        }
    }

    private void toggleVoice() {
        if (arbiter.activeOwner() != AudioOwner.NONE
                || playback.status() == PlaybackStatus.PLAYING
                || playback.status() == PlaybackStatus.PREPARING) {
            throw new IllegalStateException("Pause or stop playback before changing voice");
        }
        if ("zf_001".equals(voice.getName())) {
            voice = SpeakerWhitelist.requireAllowed("zm_010", 59);
        } else {
            voice = SpeakerWhitelist.requireAllowed("zf_001", 3);
        }
        persistCheckpoint();
        publishState();
        Log.i(TAG, "VOICE_SELECTED " + voice.getName() + " sid=" + voice.getSid());
    }

    private void startMusic() {
        boolean terminatingTts = arbiter.activeOwner() == AudioOwner.TTS;
        if (terminatingTts) {
            cancelPlaybackToken();
            audioPlayer.stop();
            if (queueLoaded) {
                playback.pause();
                persistCheckpoint();
            }
            arbiter.pauseTts();
            Log.i(TAG, "TTS_TERMINATED_BEFORE_MUSIC");
        }
        audioPlayer.stop();
        AudioOwnerArbiter.ArbitrationDecision decision = arbiter.beginMusic();
        if (decision.mustTerminateTts() && !terminatingTts) {
            cancelPlaybackToken();
            audioPlayer.stop();
        }
        acquireAudioFocusOrFail();
        positionMillis = 0L;
        final long token = generation.incrementAndGet();
        acquireWakeLock();
        publishState();
        final int trackIndex = arbiter.musicIndex();
        float[] tone = createMusicTone(trackIndex);
        Log.i(TAG, "MUSIC_TRACK_REQUEST index=" + trackIndex + " generation=" + token);
        audioPlayer.play(
                tone,
                MUSIC_SAMPLE_RATE,
                false,
                new PcmAudioPlayer.Listener() {
                    @Override
                    public void onStarted() {
                        Log.i(
                                TAG,
                                "AUDIO_TRACK_STARTED owner=MUSIC generation="
                                        + token
                                        + " samples="
                                        + tone.length
                                        + " durationMillis="
                                        + Math.round(tone.length * 1000.0 / MUSIC_SAMPLE_RATE));
                        mainHandler.post(() -> publishState());
                    }

                    @Override
                    public void onProgress(long value) {
                        positionMillis = value;
                    }

                    @Override
                    public void onCompleted() {
                        Log.i(
                                TAG,
                                "AUDIO_TRACK_COMPLETED owner=MUSIC generation=" + token);
                        mainHandler.post(
                                () -> {
                                    if (isCurrentGeneration(token)) {
                                        onMusicTrackCompleted();
                                    }
                                });
                    }

                    @Override
                    public void onError(Throwable error) {
                        mainHandler.post(
                                () -> {
                                    if (isCurrentGeneration(token)) {
                                        fail(error);
                                    }
                                });
                    }
                });
    }

    private void onMusicTrackCompleted() {
        if (arbiter.activeOwner() != AudioOwner.MUSIC) {
            return;
        }
        if (arbiter.musicIndex() + 1 < 3) {
            arbiter.nextMusicTrack();
            startMusic();
            return;
        }
        arbiter.pauseMusic();
        releasePlaybackResources();
        publishState();
        Log.i(TAG, "MUSIC_QUEUE_COMPLETED index=" + arbiter.musicIndex());
    }

    private void pauseMusic() {
        cancelPlaybackToken();
        audioPlayer.stop();
        arbiter.pauseMusic();
        releasePlaybackResources();
        publishState();
        Log.i(
                TAG,
                "MUSIC_PAUSED index="
                        + arbiter.musicIndex()
                        + " tts="
                        + playback.status());
    }

    private void handleFocusLoss(String source) {
        Log.i(TAG, "AUDIO_FOCUS_LOSS source=" + source);
        AudioOwner previousOwner = arbiter.activeOwner();
        cancelPlaybackToken();
        audioPlayer.stop();
        if (previousOwner == AudioOwner.TTS) {
            playback.focusLost();
            arbiter.pauseTts();
        } else if (previousOwner == AudioOwner.MUSIC) {
            arbiter.pauseMusic();
        }
        releasePlaybackResources();
        persistCheckpoint();
        publishState();
    }

    private void cancelRuntime() {
        lastCommand = COMMAND_CANCEL;
        shuttingDown = true;
        cancelPlaybackToken();
        audioPlayer.stop();
        if (queueLoaded) {
            playback.cancel();
            persistCheckpoint();
        }
        arbiter.stopTts();
        arbiter.stopMusic();
        releasePlaybackResources();
        publishState();
        Log.i(TAG, "CANCEL_CONVERGED pid=" + android.os.Process.myPid());
        releaseSessionAndForeground();
        if (ttsExecutor != null) {
            ttsExecutor.shutdownNow();
        }
        stopSelf();
        mainHandler.postDelayed(
                () -> {
                    Log.i(TAG, "CANCEL_PROCESS_EXIT pid=" + android.os.Process.myPid());
                    android.os.Process.killProcess(android.os.Process.myPid());
                },
                CANCEL_PROCESS_EXIT_DELAY_MILLIS);
    }

    private void fail(Throwable error) {
        lastError = error.getClass().getName() + ": " + String.valueOf(error.getMessage());
        cancelPlaybackToken();
        audioPlayer.stop();
        if (queueLoaded) {
            playback.fail();
            persistCheckpoint();
        }
        arbiter.stopTts();
        releasePlaybackResources();
        publishState();
        Log.e(TAG, "RUNTIME_FAILED", error);
    }

    private void restoreCheckpoint() {
        String encoded = preferences.getString(PREF_CHECKPOINT, null);
        if (encoded == null || encoded.isEmpty()) {
            publishState();
            return;
        }
        try {
            ResumePolicy.ResumeResult result = ResumePolicy.restore(encoded);
            playStateCopy(result.getStateMachine());
            queueLoaded = true;
            voice =
                    SpeakerWhitelist.requireAllowed(
                            result.getState().getSpeakerName(), result.getState().getSid());
            packId = PackWhitelist.requireAllowed(result.getState().getPackId());
            positionMillis = 0L;
            lastError = "";
            persistCheckpoint();
            Log.i(
                    TAG,
                    "RESUME_RESTORED_PAUSED index="
                            + playback.currentIndex()
                            + " speaker="
                            + voice.getName()
                            + " autoPlay=false");
        } catch (Throwable error) {
            queueLoaded = false;
            lastError = "Resume rejected fail-closed: " + error.getMessage();
            Log.e(TAG, "RESUME_REJECTED", error);
        }
        publishState();
    }

    private void playStateCopy(PlaybackStateMachine source) {
        playback.loadQueue(source.queue());
        playback.restorePaused(source.queue(), source.stableIndex());
    }

    private OfflineTts getOrCreateTts() {
        if (tts != null) {
            return tts;
        }
        GenerationParameters parameters = NarrationContract.PARAMETERS;
        OfflineTtsKokoroModelConfig kokoro = new OfflineTtsKokoroModelConfig();
        kokoro.setModel("model.onnx");
        kokoro.setVoices("voices.bin");
        kokoro.setTokens("tokens.txt");
        kokoro.setDataDir("espeak-ng-data");
        kokoro.setLexicon("lexicon-zh.txt");
        kokoro.setLang("zh");
        kokoro.setDictDir("dict");
        kokoro.setLengthScale(parameters.getLengthScale());

        OfflineTtsModelConfig model = new OfflineTtsModelConfig();
        model.setKokoro(kokoro);
        model.setNumThreads(parameters.getNumThreads());
        model.setDebug(false);
        model.setProvider("cpu");

        OfflineTtsConfig config = new OfflineTtsConfig();
        config.setModel(model);
        config.setRuleFsts("date-zh.fst,number-zh.fst,phone-zh.fst");
        config.setRuleFars("");
        config.setMaxNumSentences(parameters.getMaxNumSentences());
        config.setSilenceScale(parameters.getSilenceScale());
        tts = new OfflineTts(getAssets(), config);
        Log.i(
                TAG,
                "TTS_LOADED sampleRate="
                        + tts.sampleRate()
                        + " speakers="
                        + tts.numSpeakers()
                        + " lang="
                        + tts.lang());
        return tts;
    }

    private final class CancellableCallback implements Function1<float[], Integer> {
        private final long token;

        CancellableCallback(long token) {
            this.token = token;
        }

        @Override
        public Integer invoke(float[] samples) {
            return isCurrentGeneration(token) ? 1 : 0;
        }
    }

    private boolean isCurrentGeneration(long token) {
        return token == generation.get() && !shuttingDown;
    }

    private void cancelPlaybackToken() {
        generation.incrementAndGet();
    }

    private float[] createMusicTone(int trackIndex) {
        int sampleCount = MUSIC_SAMPLE_RATE * MUSIC_TRACK_SECONDS;
        float[] samples = new float[sampleCount];
        double baseFrequency = 220.0 + trackIndex * 34.0;
        for (int index = 0; index < samples.length; index++) {
            double seconds = index / (double) MUSIC_SAMPLE_RATE;
            double envelope = Math.min(1.0, seconds * 4.0);
            envelope = Math.min(envelope, (MUSIC_TRACK_SECONDS - seconds) * 4.0);
            double value =
                    Math.sin(2.0 * Math.PI * baseFrequency * seconds) * 0.10
                            + Math.sin(2.0 * Math.PI * (baseFrequency * 1.5) * seconds)
                                    * 0.035;
            samples[index] = (float) (value * Math.max(0.0, envelope));
        }
        return samples;
    }

    private void acquireAudioFocusOrFail() {
        if (focusHeld) {
            return;
        }
        int result = audioManager.requestAudioFocus(focusRequest);
        focusHeld = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        Log.i(TAG, "AUDIO_FOCUS_REQUEST result=" + result);
        if (!focusHeld) {
            throw new IllegalStateException("Audio focus was not granted: " + result);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        FocusPolicy.FocusChange mapped;
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                mapped = FocusPolicy.FocusChange.GAIN;
                break;
            case AudioManager.AUDIOFOCUS_LOSS:
                mapped = FocusPolicy.FocusChange.LOSS;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                mapped = FocusPolicy.FocusChange.LOSS_TRANSIENT;
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                mapped = FocusPolicy.FocusChange.LOSS_TRANSIENT_CAN_DUCK;
                break;
            default:
                Log.w(TAG, "AUDIO_FOCUS_UNKNOWN change=" + focusChange);
                return;
        }
        Log.i(TAG, "AUDIO_FOCUS_CHANGE change=" + focusChange + " policy=" + mapped);
        if (FocusPolicy.onFocusChange(mapped) == FocusPolicy.FocusAction.PAUSE) {
            mainHandler.post(() -> handleFocusLoss("system_change_" + focusChange));
        }
    }

    private void abandonAudioFocus() {
        if (!focusHeld) {
            return;
        }
        audioManager.abandonAudioFocusRequest(focusRequest);
        focusHeld = false;
        Log.i(TAG, "AUDIO_FOCUS_ABANDONED");
    }

    private void acquireWakeLock() {
        if (!wakeLock.isHeld()) {
            wakeLock.acquire(60 * 60 * 1000L);
            Log.i(TAG, "WAKE_LOCK_ACQUIRED");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.i(TAG, "WAKE_LOCK_RELEASED");
        }
    }

    private void releasePlaybackResources() {
        releaseWakeLock();
        abandonAudioFocus();
    }

    private void createNotificationChannel() {
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "Kokoro runtime playback",
                        NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Isolated TTS and local music owner controls");
        channel.setShowBadge(false);
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
    }

    private void createMediaSession() {
        mediaSession = new MediaSession(this, "Kokoro isolated runtime");
        mediaSession.setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS
                        | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(
                new MediaSession.Callback() {
                    @Override
                    public void onPlay() {
                        mainHandler.post(() -> processCommand(COMMAND_RESUME));
                    }

                    @Override
                    public void onPause() {
                        mainHandler.post(() -> processCommand(COMMAND_PAUSE));
                    }

                    @Override
                    public void onSkipToPrevious() {
                        mainHandler.post(() -> processCommand(COMMAND_PREVIOUS));
                    }

                    @Override
                    public void onSkipToNext() {
                        mainHandler.post(() -> processCommand(COMMAND_NEXT));
                    }

                    @Override
                    public void onStop() {
                        mainHandler.post(() -> processCommand(COMMAND_STOP));
                    }

                    @Override
                    public void onSeekTo(long pos) {
                        Log.i(TAG, "MEDIA_SESSION_SEEK_REJECTED position=" + pos);
                    }
                });
        mediaSession.setActive(true);
    }

    private void ensureForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private Notification buildNotification() {
        RuntimePresentation presentation = buildPresentation();
        Notification.MediaStyle mediaStyle =
                new Notification.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2);
        Notification.Builder builder =
                new Notification.Builder(this, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_media_play)
                        .setColor(0xff9f2e22)
                        .setContentTitle(presentation.getNotificationTitle())
                        .setContentText(presentation.getNotificationText())
                        .setContentIntent(openActivityPendingIntent())
                        .setCategory(Notification.CATEGORY_TRANSPORT)
                        .setVisibility(Notification.VISIBILITY_PUBLIC)
                        .setOnlyAlertOnce(true)
                        .setShowWhen(false)
                        .setOngoing(playbackOngoing())
                        .setStyle(mediaStyle);

        builder.addAction(
                new Notification.Action.Builder(
                                android.R.drawable.ic_media_previous,
                                "上一句",
                                commandPendingIntent(COMMAND_PREVIOUS, 11))
                        .build());
        RuntimePresentation.PlayPauseAction playPause = presentation.getPlayPauseAction();
        String playPauseLabel;
        int playPauseIcon;
        String playPauseCommand;
        switch (playPause) {
            case PAUSE_TTS:
            case PAUSE_MUSIC:
                playPauseLabel = "暂停";
                playPauseIcon = android.R.drawable.ic_media_pause;
                playPauseCommand = COMMAND_PAUSE;
                break;
            case PLAY_MUSIC:
                playPauseLabel = "播放音乐";
                playPauseIcon = android.R.drawable.ic_media_play;
                playPauseCommand = COMMAND_MUSIC_PLAY;
                break;
            case PLAY_TTS:
                playPauseLabel = "继续";
                playPauseIcon = android.R.drawable.ic_media_play;
                playPauseCommand = COMMAND_RESUME;
                break;
            case NONE:
            default:
                playPauseLabel = "开始";
                playPauseIcon = android.R.drawable.ic_media_play;
                playPauseCommand = COMMAND_PLAY_SAMPLE;
                break;
        }
        builder.addAction(
                new Notification.Action.Builder(
                                playPauseIcon,
                                playPauseLabel,
                                commandPendingIntent(playPauseCommand, 12))
                        .build());
        builder.addAction(
                new Notification.Action.Builder(
                                android.R.drawable.ic_media_next,
                                "下一句",
                                commandPendingIntent(COMMAND_NEXT, 13))
                        .build());
        builder.addAction(
                new Notification.Action.Builder(
                                android.R.drawable.ic_menu_close_clear_cancel,
                                "停止",
                                commandPendingIntent(COMMAND_STOP, 14))
                        .build());
        return builder.build();
    }

    private boolean playbackOngoing() {
        PlaybackStatus status = displayStatus();
        return status == PlaybackStatus.PREPARING
                || status == PlaybackStatus.PLAYING
                || status == PlaybackStatus.PAUSED;
    }

    private PendingIntent openActivityPendingIntent() {
        Intent intent = new Intent(this, HarnessActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return PendingIntent.getActivity(
                this,
                10,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private PendingIntent commandPendingIntent(String command, int requestCode) {
        Intent intent =
                new Intent(this, KokoroRuntimeService.class)
                        .setAction(ACTION_COMMAND)
                        .putExtra(EXTRA_COMMAND, command);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void updateNotification() {
        if (!foregroundStarted || shuttingDown) {
            return;
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification());
    }

    private void updateMediaSession() {
        RuntimePresentation presentation = buildPresentation();
        PlaybackStatus status = displayStatus();
        int platformState;
        float speed;
        switch (status) {
            case PREPARING:
                platformState = PlaybackState.STATE_BUFFERING;
                speed = 0.0f;
                break;
            case PLAYING:
                platformState = PlaybackState.STATE_PLAYING;
                speed = 1.0f;
                break;
            case PAUSED:
                platformState = PlaybackState.STATE_PAUSED;
                speed = 0.0f;
                break;
            case STOPPED:
            case COMPLETED:
                platformState = PlaybackState.STATE_STOPPED;
                speed = 0.0f;
                break;
            case ERROR:
                platformState = PlaybackState.STATE_ERROR;
                speed = 0.0f;
                break;
            case IDLE:
            default:
                platformState = PlaybackState.STATE_NONE;
                speed = 0.0f;
                break;
        }
        long actions =
                PlaybackState.ACTION_PLAY
                        | PlaybackState.ACTION_PAUSE
                        | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                        | PlaybackState.ACTION_SKIP_TO_NEXT
                        | PlaybackState.ACTION_STOP;
        PlaybackState.Builder playbackState =
                new PlaybackState.Builder()
                        .setActions(actions)
                        .setState(
                                platformState,
                                positionMillis,
                                speed,
                                SystemClock.elapsedRealtime());
        if (lastError != null && !lastError.isEmpty()) {
            playbackState.setErrorMessage(lastError);
        }
        mediaSession.setPlaybackState(playbackState.build());
        MediaMetadata.Builder metadata =
                new MediaMetadata.Builder()
                        .putString(MediaMetadata.METADATA_KEY_TITLE, presentation.getNotificationTitle())
                        .putString(
                                MediaMetadata.METADATA_KEY_ARTIST,
                                "Kokoro isolated runtime · " + voice.getName())
                        .putString(MediaMetadata.METADATA_KEY_ALBUM, packId);
        mediaSession.setMetadata(metadata.build());
        mediaSession.setActive(true);
    }

    private void publishState() {
        if (shuttingDown && !lastCommand.equals(COMMAND_CANCEL)) {
            return;
        }
        JSONObject snapshot = buildSnapshot();
        latestStateJson = snapshot.toString();
        updateNotification();
        updateMediaSession();
        for (RuntimeListener listener : listeners) {
            try {
                listener.onRuntimeStateChanged(latestStateJson);
            } catch (Throwable error) {
                Log.w(TAG, "Runtime listener failed", error);
            }
        }
        Log.i(TAG, "STATE " + latestStateJson);
    }

    private JSONObject buildSnapshot() {
        RuntimePresentation presentation = buildPresentation();
        JSONObject snapshot = new JSONObject();
        try {
            snapshot.put("schema", "aurorama.kokoro-runtime.state.v1");
            snapshot.put("pid", android.os.Process.myPid());
            snapshot.put("status", displayStatus().name());
            snapshot.put("activeAudioOwner", arbiter.activeOwner().name());
            snapshot.put("musicStatus", arbiter.musicStatus().name());
            snapshot.put("musicIndex", arbiter.musicIndex());
            snapshot.put("queueLoaded", queueLoaded);
            snapshot.put("queueIndex", queueLoaded ? playback.currentIndex() : -1);
            snapshot.put("queueSize", queueLoaded ? playback.queue().size() : 0);
            snapshot.put("stableIndex", queueLoaded ? playback.stableIndex() : -1);
            snapshot.put(
                    "currentSentence", queueLoaded ? playback.currentSentence() : "");
            snapshot.put(
                    "stableSentence", queueLoaded ? playback.stableSentence() : "");
            snapshot.put("packId", packId);
            snapshot.put("speaker", voice.getName());
            snapshot.put("sid", voice.getSid());
            snapshot.put("focusHeld", focusHeld);
            snapshot.put("audioTrackPlaying", audioPlayer != null && audioPlayer.isPlaying());
            snapshot.put("positionMillis", positionMillis);
            snapshot.put("generation", generation.get());
            snapshot.put("autoPlay", false);
            snapshot.put("notificationTitle", presentation.getNotificationTitle());
            snapshot.put("notificationText", presentation.getNotificationText());
            snapshot.put("playPauseAction", presentation.getPlayPauseAction().name());
            snapshot.put("mediaSessionState", presentation.getMediaStateLabel());
            snapshot.put("lastCommand", lastCommand);
            snapshot.put("lastError", lastError);
            snapshot.put(
                    "updatedAtEpochMillis", System.currentTimeMillis());
        } catch (Exception error) {
            Log.e(TAG, "Failed to build runtime snapshot", error);
        }
        return snapshot;
    }

    private RuntimePresentation buildPresentation() {
        PlaybackStatus status = displayStatus();
        AudioOwner owner = arbiter.activeOwner();
        if (owner == AudioOwner.MUSIC) {
            String musicSentence = "本地音乐轨道 " + (arbiter.musicIndex() + 1) + "/3";
            return RuntimePresentation.from(
                    status,
                    AudioOwner.MUSIC,
                    musicSentence,
                    arbiter.musicIndex(),
                    3);
        }
        if (queueLoaded) {
            return RuntimePresentation.from(
                    status,
                    owner,
                    playback.currentSentence(),
                    playback.currentIndex(),
                    playback.queue().size());
        }
        return RuntimePresentation.from(status, owner, "", 0, 0);
    }

    private PlaybackStatus displayStatus() {
        if (arbiter.activeOwner() == AudioOwner.MUSIC) {
            return PlaybackStatus.PLAYING;
        }
        if (queueLoaded) {
            return playback.status();
        }
        return PlaybackStatus.IDLE;
    }

    private void persistCheckpoint() {
        if (!queueLoaded || preferences == null) {
            return;
        }
        String encoded =
                PersistentPlaybackState.encode(
                        packId,
                        voice.getName(),
                        voice.getSid(),
                        playback.status(),
                        playback.currentIndex(),
                        playback.stableIndex(),
                        positionMillis,
                        playback.queue().sentences());
        preferences.edit().putString(PREF_CHECKPOINT, encoded).commit();
    }

    private void dumpState() {
        try {
            File root = getExternalFilesDir("runtime");
            if (root == null) {
                throw new IllegalStateException("External runtime directory is unavailable");
            }
            if (!root.isDirectory() && !root.mkdirs()) {
                throw new IllegalStateException("Cannot create runtime evidence directory");
            }
            File output = new File(root, "runtime_state.json");
            JSONObject wrapped = new JSONObject(latestStateJson);
            wrapped.put("evidenceDumpAtEpochMillis", System.currentTimeMillis());
            Files.write(
                    output.toPath(),
                    (wrapped.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            Log.i(TAG, "STATE_DUMP " + output.getAbsolutePath());
        } catch (Throwable error) {
            fail(error);
        }
    }

    private void releaseSessionAndForeground() {
        if (mediaSession != null) {
            mediaSession.setActive(false);
            mediaSession.release();
            mediaSession = null;
        }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            notificationManager.cancel(NOTIFICATION_ID);
            foregroundStarted = false;
        }
    }
}
