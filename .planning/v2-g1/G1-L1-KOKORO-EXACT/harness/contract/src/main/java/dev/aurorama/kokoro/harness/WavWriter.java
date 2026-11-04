package dev.aurorama.kokoro.harness;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.UUID;

public final class WavWriter {
    public static final int SAMPLE_RATE = 24_000;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int CHANNELS = 1;
    private static final int HEADER_BYTES = 44;
    private static final int BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8;

    private WavWriter() {}

    public static void writeMono16(Path output, float[] samples) throws IOException {
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(samples, "samples");
        if (samples.length == 0) {
            throw new IllegalArgumentException("Refusing to write an empty WAV");
        }
        if (Files.exists(output)) {
            throw new IllegalStateException("Refusing to overwrite WAV: " + output);
        }

        int dataBytes = Math.multiplyExact(samples.length, BYTES_PER_SAMPLE);
        if (dataBytes > Integer.MAX_VALUE - HEADER_BYTES) {
            throw new IllegalArgumentException("WAV exceeds RIFF 32-bit size limit");
        }

        Path parent = output.toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalArgumentException("WAV output has no parent directory: " + output);
        }
        Files.createDirectories(parent);
        Path temporary = parent.resolve("." + output.getFileName() + "." + UUID.randomUUID() + ".tmp");

        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE)) {
                ByteBuffer header =
                        ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN);
                putFourCc(header, "RIFF");
                header.putInt(HEADER_BYTES - 8 + dataBytes);
                putFourCc(header, "WAVE");
                putFourCc(header, "fmt ");
                header.putInt(16);
                header.putShort((short) 1);
                header.putShort((short) CHANNELS);
                header.putInt(SAMPLE_RATE);
                header.putInt(SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE);
                header.putShort((short) (CHANNELS * BYTES_PER_SAMPLE));
                header.putShort((short) BITS_PER_SAMPLE);
                putFourCc(header, "data");
                header.putInt(dataBytes);
                header.flip();
                writeFully(channel, header);

                ByteBuffer pcm = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN);
                for (float sample : samples) {
                    pcm.putShort(toPcm16(sample));
                }
                pcm.flip();
                writeFully(channel, pcm);
                channel.force(true);
            }

            try {
                Files.move(
                        temporary,
                        output,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static short toPcm16(float sample) {
        if (Float.isNaN(sample)) {
            return 0;
        }
        long scaled = Math.round(Math.max(-1.0f, Math.min(1.0f, sample)) * 32_768.0f);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
    }

    private static void putFourCc(ByteBuffer buffer, String value) {
        for (int index = 0; index < 4; index++) {
            buffer.put((byte) value.charAt(index));
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }
}
