package dev.aurorama.kokoro.harness;

public final class GenerationParameters {
    private final float speed;
    private final float lengthScale;
    private final float silenceScale;
    private final int numThreads;
    private final int maxNumSentences;

    public GenerationParameters(
            float speed,
            float lengthScale,
            float silenceScale,
            int numThreads,
            int maxNumSentences) {
        this.speed = speed;
        this.lengthScale = lengthScale;
        this.silenceScale = silenceScale;
        this.numThreads = numThreads;
        this.maxNumSentences = maxNumSentences;
    }

    public float getSpeed() {
        return speed;
    }

    public float getLengthScale() {
        return lengthScale;
    }

    public float getSilenceScale() {
        return silenceScale;
    }

    public int getNumThreads() {
        return numThreads;
    }

    public int getMaxNumSentences() {
        return maxNumSentences;
    }

    public String signature() {
        return "speed="
                + speed
                + ";lengthScale="
                + lengthScale
                + ";silenceScale="
                + silenceScale
                + ";numThreads="
                + numThreads
                + ";maxNumSentences="
                + maxNumSentences;
    }
}
