package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NativeInferenceLifecycleTest {
    @Test
    public void releaseIsDeferredUntilTheInFlightInferenceFinishes() {
        NativeInferenceLifecycle lifecycle = new NativeInferenceLifecycle();

        lifecycle.beginInference();
        assertFalse(lifecycle.requestRelease());
        assertFalse(lifecycle.isReleased());

        assertTrue(lifecycle.finishInference());
        assertTrue(lifecycle.isReleased());
    }

    @Test
    public void aWorkerCannotStartUsingNativeObjectsAfterReleaseWasRequested() {
        NativeInferenceLifecycle lifecycle = new NativeInferenceLifecycle();

        assertTrue(lifecycle.requestRelease());
        assertTrue(lifecycle.isReleased());
        assertFalse(lifecycle.tryBeginInference());
    }

    @Test
    public void releaseIsImmediateWhenNoInferenceIsActiveAndCannotHappenTwice() {
        NativeInferenceLifecycle lifecycle = new NativeInferenceLifecycle();

        assertTrue(lifecycle.requestRelease());
        assertTrue(lifecycle.isReleased());
        assertFalse(lifecycle.requestRelease());
        assertFalse(lifecycle.finishInference());
    }
}
