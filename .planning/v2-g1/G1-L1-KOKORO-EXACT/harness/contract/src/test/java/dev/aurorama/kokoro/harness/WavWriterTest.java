package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class WavWriterTest {
    @Test
    public void writesCanonical24kMono16BitPcmWave() throws Exception {
        Path directory = Files.createTempDirectory("kokoro-contract-");
        Path output = directory.resolve("sample.wav");
        try {
            float[] samples = {-1.5f, -1.0f, 0.0f, 0.5f, 1.0f, 1.5f};
            WavWriter.writeMono16(output, samples);

            byte[] bytes = Files.readAllBytes(output);
            assertEquals(44 + samples.length * 2, bytes.length);
            assertEquals("RIFF", ascii(bytes, 0, 4));
            assertEquals("WAVE", ascii(bytes, 8, 4));
            assertEquals("fmt ", ascii(bytes, 12, 4));
            assertEquals("data", ascii(bytes, 36, 4));

            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(bytes.length - 8, buffer.getInt(4));
            assertEquals(1, buffer.getShort(20));
            assertEquals(1, buffer.getShort(22));
            assertEquals(24_000, buffer.getInt(24));
            assertEquals(48_000, buffer.getInt(28));
            assertEquals(2, buffer.getShort(32));
            assertEquals(16, buffer.getShort(34));
            assertEquals(samples.length * 2, buffer.getInt(40));

            short[] expected = {-32768, -32768, 0, 16384, 32767, 32767};
            short[] actual = new short[samples.length];
            for (int index = 0; index < samples.length; index++) {
                actual[index] = buffer.getShort(44 + index * 2);
            }
            assertArrayEquals(expected, actual);
        } finally {
            Files.deleteIfExists(output);
            Files.deleteIfExists(directory);
        }
    }

    @Test
    public void rejectsEmptySamplesAndOverwrite() throws Exception {
        Path output = Files.createTempFile("kokoro-contract-", ".wav");
        try {
            assertThrows(IllegalArgumentException.class, () -> WavWriter.writeMono16(output, new float[0]));
            Files.write(output, new byte[] {1});
            assertThrows(
                    IllegalStateException.class,
                    () -> WavWriter.writeMono16(output, new float[] {0.0f}));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, java.nio.charset.StandardCharsets.US_ASCII);
    }
}
