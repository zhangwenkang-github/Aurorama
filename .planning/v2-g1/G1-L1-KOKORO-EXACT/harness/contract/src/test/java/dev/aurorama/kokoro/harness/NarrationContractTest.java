package dev.aurorama.kokoro.harness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.junit.Test;

public final class NarrationContractTest {
    @Test
    public void fixedNarrationMeetsOriginalNovelLengthAndPunctuationContract() {
        String text = NarrationContract.TEXT;
        long hanCount =
                text.codePoints()
                        .filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN)
                        .count();

        assertTrue("Han character count=" + hanCount, hanCount >= 175 && hanCount <= 300);
        assertTrue(text.contains("，"));
        assertTrue(text.contains("。"));
        assertTrue(text.contains("："));
        assertEquals(
                "3908bcd1fa6aa9e57ab544636ed975c93f881c7d599ceb045fd57b953c05bfcb",
                sha256(text));
    }

    @Test
    public void bothVoicesUseTheSameTextAndParameters() {
        NarrationRequest female =
                NarrationContract.requestFor(SpeakerWhitelist.requireAllowed("zf_001"));
        NarrationRequest male =
                NarrationContract.requestFor(SpeakerWhitelist.requireAllowed("zm_010"));

        assertEquals(female.getText(), male.getText());
        assertSame(female.getParameters(), male.getParameters());
        assertEquals(3, female.getVoice().getSid());
        assertEquals(59, male.getVoice().getSid());
    }

    @Test
    public void generationParametersArePinned() {
        GenerationParameters parameters = NarrationContract.PARAMETERS;

        assertEquals(1.0f, parameters.getSpeed(), 0.0f);
        assertEquals(1.0f, parameters.getLengthScale(), 0.0f);
        assertEquals(1.0f, parameters.getSilenceScale(), 0.0f);
        assertEquals(2, parameters.getNumThreads());
        assertEquals(1, parameters.getMaxNumSentences());
        assertEquals(24_000, WavWriter.SAMPLE_RATE);
        assertEquals(16, WavWriter.BITS_PER_SAMPLE);
        assertEquals(1, WavWriter.CHANNELS);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexCodec.encode(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
