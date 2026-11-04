package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class SentenceQueueTest {
    @Test
    public void splitsChineseProseIntoStableSentenceItems() {
        SentenceQueue queue =
                SentenceQueue.fromText("第一句。第二句！第三句？\n第四句，仍在同一句。");

        assertEquals(4, queue.size());
        assertEquals("第一句。", queue.currentSentence());
        queue.moveNext();
        assertEquals("第二句！", queue.currentSentence());
        assertTrue(queue.hasPrevious());
    }

    @Test
    public void rejectsEmptyQueuesAndOutOfRangeIndexes() {
        assertThrows(IllegalArgumentException.class, () -> new SentenceQueue(Arrays.asList(" ", "")));

        SentenceQueue queue = new SentenceQueue(Arrays.asList("only"));
        assertFalse(queue.hasNext());
        assertThrows(IllegalStateException.class, queue::moveNext);
        assertThrows(IllegalArgumentException.class, () -> queue.moveTo(2));
    }

    private static void assertThrows(Class<? extends Throwable> expected, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            if (expected.isInstance(error)) {
                return;
            }
            throw new AssertionError("Expected " + expected.getName() + " but got " + error, error);
        }
        throw new AssertionError("Expected " + expected.getName());
    }
}
