package dev.aurorama.kokoro.harness;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class LongRunNarrationPlan {
    public static final long TARGET_DURATION_MILLIS = 31L * 60L * 1000L;
    public static final long CONSERVATIVE_CYCLE_MILLIS = 48_000L;

    private final List<String> sentences;
    private final int sourceSentenceCount;
    private final int cycleCount;
    private final long plannedMinimumDurationMillis;

    private LongRunNarrationPlan(
            List<String> sourceSentences,
            int cycleCount,
            long plannedMinimumDurationMillis) {
        this.sourceSentenceCount = sourceSentences.size();
        this.cycleCount = cycleCount;
        this.plannedMinimumDurationMillis = plannedMinimumDurationMillis;
        List<String> repeated = new ArrayList<>(sourceSentenceCount * cycleCount);
        for (int cycle = 0; cycle < cycleCount; cycle++) {
            repeated.addAll(sourceSentences);
        }
        this.sentences = Collections.unmodifiableList(repeated);
    }

    public static LongRunNarrationPlan pinned() {
        SentenceQueue source = SentenceQueue.fromText(NarrationContract.TEXT);
        int cycles =
                Math.toIntExact(
                        (TARGET_DURATION_MILLIS + CONSERVATIVE_CYCLE_MILLIS - 1L)
                                / CONSERVATIVE_CYCLE_MILLIS);
        return new LongRunNarrationPlan(
                source.sentences(),
                cycles,
                cycles * CONSERVATIVE_CYCLE_MILLIS);
    }

    public List<String> getSentences() {
        return sentences;
    }

    public int getSourceSentenceCount() {
        return sourceSentenceCount;
    }

    public int getCycleCount() {
        return cycleCount;
    }

    public long getTargetDurationMillis() {
        return TARGET_DURATION_MILLIS;
    }

    public long getPlannedMinimumDurationMillis() {
        return plannedMinimumDurationMillis;
    }
}
