package dev.aurorama.kokoro.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class SentenceQueue {
    private final List<String> sentences;
    private int currentIndex;

    public SentenceQueue(List<String> source) {
        Objects.requireNonNull(source, "source");
        List<String> normalized = new ArrayList<>();
        for (String sentence : source) {
            if (sentence == null) {
                continue;
            }
            String trimmed = sentence.trim();
            if (!trimmed.isEmpty()) {
                normalized.add(trimmed);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Sentence queue cannot be empty");
        }
        this.sentences = Collections.unmodifiableList(normalized);
    }

    public static SentenceQueue fromText(String text) {
        Objects.requireNonNull(text, "text");
        List<String> parsed = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value == '\n' || value == '\r') {
                emit(parsed, current);
                continue;
            }
            current.append(value);
            if (isSentenceTerminator(value)) {
                emit(parsed, current);
            }
        }
        emit(parsed, current);
        return new SentenceQueue(parsed);
    }

    private static void emit(List<String> parsed, StringBuilder current) {
        String value = current.toString().trim();
        if (!value.isEmpty()) {
            parsed.add(value);
        }
        current.setLength(0);
    }

    private static boolean isSentenceTerminator(char value) {
        return value == '。'
                || value == '！'
                || value == '？'
                || value == '!'
                || value == '?'
                || value == '；'
                || value == ';';
    }

    public List<String> sentences() {
        return sentences;
    }

    public int size() {
        return sentences.size();
    }

    public int currentIndex() {
        return currentIndex;
    }

    public String currentSentence() {
        return sentences.get(currentIndex);
    }

    public boolean hasNext() {
        return currentIndex + 1 < sentences.size();
    }

    public boolean hasPrevious() {
        return currentIndex > 0;
    }

    public void moveTo(int index) {
        if (index < 0 || index >= sentences.size()) {
            throw new IllegalArgumentException(
                    "Sentence index " + index + " outside 0.." + (sentences.size() - 1));
        }
        currentIndex = index;
    }

    public void moveNext() {
        if (!hasNext()) {
            throw new IllegalStateException("Sentence queue is already at its last item");
        }
        currentIndex++;
    }

    public void movePrevious() {
        if (!hasPrevious()) {
            throw new IllegalStateException("Sentence queue is already at its first item");
        }
        currentIndex--;
    }
}
