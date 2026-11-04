package dev.aurorama.kokoro.harness;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.TextView;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import kotlin.jvm.functions.Function1;
import org.json.JSONArray;
import org.json.JSONObject;

public final class HarnessActivity extends Activity {
    private static final String TAG = "KOKORO_HARNESS";
    private static final String EXPECTED_TEXT_SHA256 =
            "3908bcd1fa6aa9e57ab544636ed975c93f881c7d599ceb045fd57b953c05bfcb";

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setText("Kokoro isolated harness: preparing");
        setContentView(statusView);

        String mode = getIntent().getStringExtra("mode");
        if (mode == null || mode.trim().isEmpty()) {
            mode = "both";
        }
        String selectedMode = mode;
        new Thread(() -> execute(selectedMode), "kokoro-harness").start();
    }

    private void execute(String mode) {
        File externalRoot = getExternalFilesDir(null);
        JSONObject report = new JSONObject();
        try {
            if (externalRoot == null) {
                throw new IllegalStateException("External files directory is unavailable");
            }
            if (!externalRoot.isDirectory() && !externalRoot.mkdirs()) {
                throw new IllegalStateException("Cannot create external files directory");
            }

            String text = loadText(externalRoot);
            String textSha256 = sha256(text.getBytes(StandardCharsets.UTF_8));
            boolean probeOnly = mode.equals("probe-zf");
            if (!probeOnly && !EXPECTED_TEXT_SHA256.equals(textSha256)) {
                throw new IllegalStateException(
                        "Narration text hash mismatch: expected "
                                + EXPECTED_TEXT_SHA256
                                + " but got "
                                + textSha256);
            }

            GenerationParameters parameters = NarrationContract.PARAMETERS;
            report.put("mode", mode);
            report.put("textSha256", textSha256);
            report.put("textCharacters", text.length());
            report.put("parameters", parameters.signature());
            report.put("deviceApi", Build.VERSION.SDK_INT);
            report.put("supportedAbis", new JSONArray(Build.SUPPORTED_ABIS));

            long loadStart = SystemClock.elapsedRealtimeNanos();
            OfflineTts tts = createTts(parameters);
            long loadMillis = elapsedMillis(loadStart);
            report.put("modelLoadMillis", loadMillis);
            report.put("ttsSampleRate", tts.sampleRate());
            report.put("ttsSpeakerCount", tts.numSpeakers());
            report.put("ttsLang", tts.lang());
            if (tts.sampleRate() != WavWriter.SAMPLE_RATE) {
                throw new IllegalStateException(
                        "Unexpected TTS sample rate " + tts.sampleRate());
            }

            List<RunSpec> runs = selectRuns(mode, externalRoot);
            JSONArray results = new JSONArray();
            for (RunSpec run : runs) {
                JSONObject result = generateOne(tts, run, text, parameters);
                results.put(result);
                Log.i(TAG, "SAMPLE_OK " + result);
            }
            report.put("results", results);
            report.put("status", "PASS");

            File metricsFile = new File(externalRoot, "metrics_" + sanitize(mode) + ".json");
            writeUtf8(metricsFile, report.toString(2) + "\n");
            updateStatus("PASS: " + metricsFile.getAbsolutePath());
            Log.i(TAG, "RESULT_OK " + metricsFile.getAbsolutePath());
        } catch (Throwable error) {
            Log.e(TAG, "RESULT_FAILED", error);
            try {
                report.put("status", "FAIL");
                report.put("errorType", error.getClass().getName());
                report.put("error", String.valueOf(error.getMessage()));
                if (externalRoot != null) {
                    File failureFile = new File(externalRoot, "metrics_failure.json");
                    writeUtf8(failureFile, report.toString(2) + "\n");
                    Log.e(TAG, "RESULT_FAILED_FILE " + failureFile.getAbsolutePath());
                }
                updateStatus("FAILED: " + error);
            } catch (Throwable ignored) {
                Log.e(TAG, "RESULT_FAILED_REPORT_WRITE", ignored);
            }
        }
    }

    private OfflineTts createTts(GenerationParameters parameters) {
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
        return new OfflineTts(getAssets(), config);
    }

    private JSONObject generateOne(
            OfflineTts tts, RunSpec run, String text, GenerationParameters parameters)
            throws Exception {
        long generationStart = SystemClock.elapsedRealtimeNanos();
        TimingCallback callback = new TimingCallback(generationStart);

        GeneratedAudio audio =
                tts.generateWithCallback(
                        text, run.voice.getSid(), parameters.getSpeed(), callback);
        long inferenceMillis = elapsedMillis(generationStart);
        if (audio == null || audio.getSamples() == null || audio.getSamples().length == 0) {
            throw new IllegalStateException("TTS returned empty audio for " + run.voice.getName());
        }
        if (audio.getSampleRate() != WavWriter.SAMPLE_RATE) {
            throw new IllegalStateException(
                    "Unexpected generated audio sample rate " + audio.getSampleRate());
        }

        File output = new File(run.outputDirectory, run.fileName);
        WavWriter.writeMono16(output.toPath(), audio.getSamples());
        long durationMillis =
                Math.round(audio.getSamples().length * 1_000.0 / WavWriter.SAMPLE_RATE);
        long firstAudioMillis = callback.firstAudioMillis();

        JSONObject result = new JSONObject();
        result.put("label", run.label);
        result.put("speaker", run.voice.getName());
        result.put("sid", run.voice.getSid());
        result.put("outputPath", output.getAbsolutePath());
        result.put("outputBytes", output.length());
        result.put("outputSha256", sha256(Files.readAllBytes(output.toPath())));
        result.put("firstAudioMillis", firstAudioMillis);
        result.put("inferenceMillis", inferenceMillis);
        result.put("audioDurationMillis", durationMillis);
        result.put(
                "rtf",
                inferenceMillis == 0
                        ? 0.0
                        : round6(inferenceMillis / (double) durationMillis));
        result.put("sampleCount", audio.getSamples().length);
        result.put("callbackCount", callback.getInvocationCount());
        return result;
    }

    private static List<RunSpec> selectRuns(String mode, File externalRoot)
            throws Exception {
        List<RunSpec> runs = new ArrayList<>();
        VoiceProfile female = SpeakerWhitelist.requireAllowed("zf_001");
        VoiceProfile male = SpeakerWhitelist.requireAllowed("zm_010");
        switch (mode) {
            case "zf":
                runs.add(new RunSpec("zf-cold", female, externalRoot, "zf_001_sid3.wav"));
                break;
            case "warm-zf":
                runs.add(new RunSpec("zf-warm", female, externalRoot, "warmup_zf_001_sid3.wav"));
                break;
            case "zm":
                runs.add(new RunSpec("zm", male, externalRoot, "zm_010_sid59.wav"));
                break;
            case "probe-zf":
                runs.add(new RunSpec("zf-probe", female, externalRoot, "zf_001_sid3_probe.wav"));
                break;
            case "both":
                runs.add(new RunSpec("zf", female, externalRoot, "zf_001_sid3.wav"));
                runs.add(new RunSpec("zm", male, externalRoot, "zm_010_sid59.wav"));
                break;
            default:
                throw new IllegalArgumentException("Unsupported mode: " + mode);
        }
        return runs;
    }

    private static String loadText(File externalRoot) throws Exception {
        File override = new File(externalRoot, "narration_override.txt");
        if (override.isFile()) {
            return new String(Files.readAllBytes(override.toPath()), StandardCharsets.UTF_8).trim();
        }
        return NarrationContract.TEXT;
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexCodec.encode(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static long elapsedMillis(long startNanos) {
        return (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L;
    }

    private static double round6(double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static void writeUtf8(File output, String content) throws Exception {
        Files.write(output.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    private void updateStatus(String value) {
        runOnUiThread(
                () -> {
                    statusView.setText(value);
                    Log.i(TAG, "STATUS " + value);
                });
    }

    private static final class RunSpec {
        final String label;
        final VoiceProfile voice;
        final File outputDirectory;
        final String fileName;

        RunSpec(String label, VoiceProfile voice, File outputDirectory, String fileName) {
            this.label = label;
            this.voice = voice;
            this.outputDirectory = outputDirectory;
            this.fileName = fileName;
        }
    }

    private static final class TimingCallback implements Function1<float[], Integer> {
        private final long generationStartNanos;
        private final AtomicLong firstAudioNanos = new AtomicLong(-1);
        private final AtomicLong invocationCount = new AtomicLong();

        TimingCallback(long generationStartNanos) {
            this.generationStartNanos = generationStartNanos;
        }

        @Override
        public Integer invoke(float[] samples) {
            if (samples != null && samples.length > 0) {
                firstAudioNanos.compareAndSet(-1, SystemClock.elapsedRealtimeNanos());
            }
            invocationCount.incrementAndGet();
            return 1;
        }

        long firstAudioMillis() {
            long firstNanos = firstAudioNanos.get();
            return firstNanos < 0 ? -1 : (firstNanos - generationStartNanos) / 1_000_000L;
        }

        long getInvocationCount() {
            return invocationCount.get();
        }
    }
}
