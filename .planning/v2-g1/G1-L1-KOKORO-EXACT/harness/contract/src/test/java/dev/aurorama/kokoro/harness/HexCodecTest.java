package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;

public final class HexCodecTest {
    @Test
    public void encodesUnsignedBytesAsLowercaseHex() {
        byte[] bytes = {0, 1, 15, 16, 127, (byte) 0x80, (byte) 0xff};

        assertEquals("00010f107f80ff", HexCodec.encode(bytes));
        assertEquals("", HexCodec.encode(new byte[0]));
        assertThrows(NullPointerException.class, () -> HexCodec.encode(null));
    }

    @Test
    public void preservesSha256ContractWithoutHexFormat() throws Exception {
        byte[] digest =
                MessageDigest.getInstance("SHA-256")
                        .digest("abc".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HexCodec.encode(digest));
    }
}
