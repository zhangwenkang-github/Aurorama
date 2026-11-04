package dev.aurorama.kokoro.harness;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class PersistentPlaybackState {
    private static final String MAGIC = "aurorama-playback-v1";

    private final String packId;
    private final String speakerName;
    private final int sid;
    private final PlaybackStatus status;
    private final int currentIndex;
    private final int stableIndex;
    private final long positionMillis;
    private final List<String> sentences;

    private PersistentPlaybackState(
            String packId,
            String speakerName,
            int sid,
            PlaybackStatus status,
            int currentIndex,
            int stableIndex,
            long positionMillis,
            List<String> sentences) {
        this.packId = packId;
        this.speakerName = speakerName;
        this.sid = sid;
        this.status = status;
        this.currentIndex = currentIndex;
        this.stableIndex = stableIndex;
        this.positionMillis = positionMillis;
        this.sentences = Collections.unmodifiableList(new ArrayList<>(sentences));
    }

    public static String encode(
            String packId,
            String speakerName,
            int sid,
            PlaybackStatus status,
            int currentIndex,
            int stableIndex,
            long positionMillis,
            List<String> sentences) {
        PersistentPlaybackState state =
                new PersistentPlaybackState(
                        Objects.requireNonNull(packId, "packId"),
                        Objects.requireNonNull(speakerName, "speakerName"),
                        sid,
                        Objects.requireNonNull(status, "status"),
                        currentIndex,
                        stableIndex,
                        Math.max(0L, positionMillis),
                        validateSentences(sentences));
        state.validateIndexes();
        StringBuilder output = new StringBuilder();
        output.append(MAGIC).append('\n');
        append(output, "pack", state.packId);
        append(output, "speaker", state.speakerName);
        output.append("sid=").append(state.sid).append('\n');
        output.append("status=").append(state.status.name()).append('\n');
        output.append("current=").append(state.currentIndex).append('\n');
        output.append("stable=").append(state.stableIndex).append('\n');
        output.append("position=").append(state.positionMillis).append('\n');
        output.append("count=").append(state.sentences.size()).append('\n');
        for (String sentence : state.sentences) {
            append(output, "sentence", sentence);
        }
        return output.toString();
    }

    public static PersistentPlaybackState decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Persistent playback state is empty");
        }
        String[] lines = encoded.split("\\n", -1);
        if (lines.length < 8 || !MAGIC.equals(lines[0])) {
            throw new IllegalArgumentException("Unsupported persistent playback state");
        }
        String packId = null;
        String speakerName = null;
        int sid = Integer.MIN_VALUE;
        PlaybackStatus status = null;
        int current = Integer.MIN_VALUE;
        int stable = Integer.MIN_VALUE;
        long position = -1L;
        int count = -1;
        List<String> sentences = new ArrayList<>();
        for (int index = 1; index < lines.length; index++) {
            String line = lines[index];
            if (line.trim().isEmpty()) {
                continue;
            }
            int separator = line.indexOf('=');
            if (separator <= 0) {
                throw new IllegalArgumentException("Malformed playback state line");
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 1);
            try {
                switch (key) {
                    case "pack":
                        packId = decodeValue(value);
                        break;
                    case "speaker":
                        speakerName = decodeValue(value);
                        break;
                    case "sid":
                        sid = Integer.parseInt(value);
                        break;
                    case "status":
                        status = PlaybackStatus.valueOf(value);
                        break;
                    case "current":
                        current = Integer.parseInt(value);
                        break;
                    case "stable":
                        stable = Integer.parseInt(value);
                        break;
                    case "position":
                        position = Long.parseLong(value);
                        break;
                    case "count":
                        count = Integer.parseInt(value);
                        break;
                    case "sentence":
                        sentences.add(decodeValue(value));
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown playback state key: " + key);
                }
            } catch (RuntimeException error) {
                if (error instanceof IllegalArgumentException
                        && error.getMessage() != null
                        && error.getMessage().startsWith("Unknown playback state key")) {
                    throw error;
                }
                throw new IllegalArgumentException("Invalid playback state value for " + key, error);
            }
        }
        if (count < 1 || count != sentences.size()) {
            throw new IllegalArgumentException("Playback sentence count mismatch");
        }
        PersistentPlaybackState state =
                new PersistentPlaybackState(
                        requireValue(packId, "pack"),
                        requireValue(speakerName, "speaker"),
                        sid,
                        Objects.requireNonNull(status, "status"),
                        current,
                        stable,
                        position,
                        sentences);
        state.validateIndexes();
        return state;
    }

    private static List<String> validateSentences(List<String> source) {
        Objects.requireNonNull(source, "sentences");
        List<String> result = new ArrayList<>();
        for (String sentence : source) {
            String value = Objects.requireNonNull(sentence, "sentence").trim();
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Persistent sentence cannot be empty");
            }
            result.add(value);
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("Persistent sentence queue cannot be empty");
        }
        return result;
    }

    private void validateIndexes() {
        if (sentences.isEmpty()) {
            throw new IllegalArgumentException("Persistent sentence queue cannot be empty");
        }
        if (currentIndex < 0 || currentIndex >= sentences.size()) {
            throw new IllegalArgumentException("Current sentence index is outside the queue");
        }
        if (stableIndex < 0 || stableIndex >= sentences.size()) {
            throw new IllegalArgumentException("Stable sentence index is outside the queue");
        }
        if (positionMillis < 0L) {
            throw new IllegalArgumentException("Playback position cannot be negative");
        }
    }

    private static String requireValue(String value, String field) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Missing persistent playback field: " + field);
        }
        return value;
    }

    private static void append(StringBuilder output, String key, String value) {
        output.append(key)
                .append('=')
                .append(Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)))
                .append('\n');
    }

    private static String decodeValue(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    public String getPackId() {
        return packId;
    }

    public String getSpeakerName() {
        return speakerName;
    }

    public int getSid() {
        return sid;
    }

    public PlaybackStatus getStatus() {
        return status;
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public int getStableIndex() {
        return stableIndex;
    }

    public long getPositionMillis() {
        return positionMillis;
    }

    public List<String> getSentences() {
        return sentences;
    }
}
