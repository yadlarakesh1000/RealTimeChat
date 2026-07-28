package com.rakesh.chat.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.crypto.AEADBadTagException;
import javax.crypto.SecretKey;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The AES-GCM layer on its own — no sockets, no protocol.
 *
 * <p>The two tests that matter most are {@code sameTextTwiceLooksDifferent} (proves the IV
 * is fresh every time, which is the difference between GCM and the broken ECB mode) and the
 * whole {@link Tampering} group (proves GCM is <i>authenticated</i> encryption: altered text
 * is refused, not silently returned as garbage).
 */
class MessageCipherTest {

    private final SecretKey key = KeyUtils.generateAesKey(256);
    private final MessageCipher cipher = new MessageCipher(key);

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void whatGoesInComesOut() throws Exception {
            String plain = "meet me at five";
            assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)));
        }

        @Test
        void spacesPunctuationAndEmojiSurvive() throws Exception {
            String plain = "hi, bob! 50% off :) éè 😀 tab\there";
            assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)));
        }

        @Test
        void anEmptyStringIsStillEncryptable() throws Exception {
            // The protocol never sends an empty body, but the cipher should not care.
            assertEquals("", cipher.decrypt(cipher.encrypt("")));
        }

        @Test
        void aLongMessageSurvives() throws Exception {
            String plain = "x".repeat(2000);
            assertEquals(plain, cipher.decrypt(cipher.encrypt(plain)));
        }
    }

    @Nested
    @DisplayName("what the ciphertext looks like")
    class Shape {

        @Test
        @DisplayName("the same text encrypted twice gives two different payloads")
        void sameTextTwiceLooksDifferent() {
            String first = cipher.encrypt("hello");
            String second = cipher.encrypt("hello");

            // This is the fresh-IV guarantee. With ECB, or with a fixed IV, these two would
            // be identical and an eavesdropper could tell you sent the same thing twice.
            assertNotEquals(first, second);
        }

        @Test
        void theOriginalTextIsNotVisibleInThePayload() {
            assertFalse(cipher.encrypt("meet me at five").contains("meet"));
        }

        @Test
        @DisplayName("payload = 12-byte IV + ciphertext + 16-byte tag")
        void theLayoutIsIvPlusCiphertextPlusTag() {
            byte[] raw = Base64.getDecoder().decode(cipher.encrypt("abcde"));
            // 12 IV + 5 bytes of ciphertext (GCM is a stream mode, so no padding) + 16 tag.
            assertEquals(12 + 5 + 16, raw.length);
        }

        @Test
        void thePayloadIsSafeForOurLineBasedProtocol() {
            String payload = cipher.encrypt("line one");
            assertFalse(payload.contains("\n"), "a newline would break framing");
            assertFalse(payload.contains("\r"));
            assertFalse(payload.contains(" "), "a space would break field splitting");
        }
    }

    @Nested
    @DisplayName("tampering and wrong keys")
    class Tampering {

        @Test
        @DisplayName("flipping one character is detected")
        void oneAlteredCharacterIsRejected() {
            String payload = cipher.encrypt("transfer 100 rupees");
            String tampered = flipOneCharacter(payload);

            assertThrows(AEADBadTagException.class, () -> cipher.decrypt(tampered));
        }

        @Test
        void aDifferentKeyIsRejected() {
            String payload = cipher.encrypt("secret");
            MessageCipher somebodyElse = new MessageCipher(KeyUtils.generateAesKey(256));

            // GCM cannot tell "altered" from "wrong key" and does not need to: both mean
            // "this did not come from someone holding my key".
            assertThrows(AEADBadTagException.class, () -> somebodyElse.decrypt(payload));
        }

        @Test
        void plainTextIsRejected() {
            assertThrows(GeneralSecurityException.class, () -> cipher.decrypt("hello there"));
        }

        @Test
        @DisplayName("half a payload is rejected cleanly, not with a RuntimeException")
        void aTruncatedPayloadIsRejected() {
            String payload = cipher.encrypt("hello");
            String halved = payload.substring(0, payload.length() / 2);

            // This test found a real bug: without the length check in decrypt(), the JCE
            // threw a ProviderException, which is a RuntimeException and would have sailed
            // past every catch block on the server. See LEARNING-LOG.md, B8.
            assertThrows(GeneralSecurityException.class, () -> cipher.decrypt(halved));
        }

        @Test
        void aPayloadWithAnIvButNoTagIsRejected() {
            // 12 bytes of IV and nothing else - the shape a naive slicing bug produces.
            String ivOnly = Base64.getEncoder().encodeToString(new byte[12]);

            assertThrows(GeneralSecurityException.class, () -> cipher.decrypt(ivOnly));
        }

        @Test
        void somethingShorterThanTheIvIsRejected() {
            String tiny = Base64.getEncoder().encodeToString(new byte[]{1, 2, 3});
            GeneralSecurityException e =
                    assertThrows(GeneralSecurityException.class, () -> cipher.decrypt(tiny));
            assertTrue(e.getMessage().contains("too short"));
        }

        @Test
        @DisplayName("the failure message never repeats the payload back")
        void failuresDoNotEchoThePayload() {
            String payload = cipher.encrypt("secret");
            GeneralSecurityException e =
                    assertThrows(GeneralSecurityException.class, () -> cipher.decrypt("not base64!!"));
            assertFalse(String.valueOf(e.getMessage()).contains(payload));
        }
    }

    @Test
    void aNullKeyIsRefusedImmediately() {
        assertThrows(IllegalArgumentException.class, () -> new MessageCipher(null));
    }

    /** Changes one base64 character to a different one, keeping the string valid base64. */
    private static String flipOneCharacter(String payload) {
        int index = payload.length() / 2;
        char original = payload.charAt(index);
        char replacement = (original == 'A') ? 'B' : 'A';
        return payload.substring(0, index) + replacement + payload.substring(index + 1);
    }
}
