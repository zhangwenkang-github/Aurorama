package dev.aurorama.kokoro.harness;

/**
 * Serializes native inference ownership against teardown.
 *
 * <p>Once release is requested, no new inference may begin. If inference is already active,
 * release is deferred until the last active worker explicitly finishes.
 */
public final class NativeInferenceLifecycle {
    private int inFlightCount;
    private boolean acceptingInference = true;
    private boolean releaseRequested;
    private boolean released;

    public synchronized void beginInference() {
        if (!tryBeginInference()) {
            throw new IllegalStateException("Native inference has already been shut down");
        }
    }

    public synchronized boolean tryBeginInference() {
        if (!acceptingInference) {
            return false;
        }
        inFlightCount++;
        return true;
    }

    public synchronized boolean finishInference() {
        if (inFlightCount == 0) {
            return false;
        }
        inFlightCount--;
        if (inFlightCount == 0 && releaseRequested && !released) {
            released = true;
            return true;
        }
        return false;
    }

    /**
     * Requests teardown and returns true when the caller must release native objects immediately.
     * A false result with release requested means the finishing worker owns the deferred release.
     */
    public synchronized boolean requestRelease() {
        acceptingInference = false;
        releaseRequested = true;
        if (released || inFlightCount > 0) {
            return false;
        }
        released = true;
        return true;
    }

    public synchronized boolean isReleased() {
        return released;
    }

    public synchronized int inFlightCount() {
        return inFlightCount;
    }
}
