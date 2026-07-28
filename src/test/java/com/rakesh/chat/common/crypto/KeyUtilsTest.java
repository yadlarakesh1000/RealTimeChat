package com.rakesh.chat.common.crypto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** Where keys come from. */
class KeyUtilsTest {

    private static final byte[] SALT = "some-salt".getBytes(StandardCharsets.UTF_8);

    @Test
    void aGeneratedKeyIs256BitsOfAes() {
        SecretKey key = KeyUtils.generateAesKey(256);

        assertEquals("AES", key.getAlgorithm());
        assertEquals(32, key.getEncoded().length); // 256 bits / 8
    }

    @Test
    void a128BitKeyIsAlsoAllowed() {
        assertEquals(16, KeyUtils.generateAesKey(128).getEncoded().length);
    }

    @Test
    void twoGeneratedKeysAreNeverTheSame() {
        assertFalse(Arrays.equals(
                KeyUtils.generateAesKey(256).getEncoded(),
                KeyUtils.generateAesKey(256).getEncoded()));
    }

    @Test
    void aSillyKeySizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> KeyUtils.generateAesKey(100));
    }

    @Test
    @DisplayName("the same passphrase and salt always give the same key")
    void derivationIsRepeatable() {
        // This is the property the whole design rests on: two machines that never talk to
        // each other still end up holding identical bytes.
        byte[] first = KeyUtils.deriveFromPassphrase("open sesame".toCharArray(), SALT, 1000)
                               .getEncoded();
        byte[] second = KeyUtils.deriveFromPassphrase("open sesame".toCharArray(), SALT, 1000)
                                .getEncoded();

        assertArrayEquals(first, second);
    }

    @Test
    void aDifferentPassphraseGivesADifferentKey() {
        assertFalse(Arrays.equals(
                KeyUtils.deriveFromPassphrase("open sesame".toCharArray(), SALT, 1000).getEncoded(),
                KeyUtils.deriveFromPassphrase("open sesamf".toCharArray(), SALT, 1000).getEncoded()));
    }

    @Test
    void aDifferentSaltGivesADifferentKey() {
        byte[] otherSalt = "other-salt".getBytes(StandardCharsets.UTF_8);

        assertFalse(Arrays.equals(
                KeyUtils.deriveFromPassphrase("open sesame".toCharArray(), SALT, 1000).getEncoded(),
                KeyUtils.deriveFromPassphrase("open sesame".toCharArray(), otherSalt, 1000)
                        .getEncoded()));
    }

    @Test
    void aDifferentIterationCountGivesADifferentKey() {
        assertFalse(Arrays.equals(
                KeyUtils.deriveFromPassphrase("open sesame".toCharArray(), SALT, 1000).getEncoded(),
                KeyUtils.deriveFromPassphrase("open sesame".toCharArray(), SALT, 2000).getEncoded()));
    }

    @Test
    @DisplayName("a short passphrase still produces a full-size key")
    void shortPassphrasesAreStretched() {
        // "hi" is 2 bytes. The key is 32. That stretching is what PBKDF2 is for - though it
        // does not make "hi" a good passphrase, it only makes each guess expensive.
        assertEquals(32, KeyUtils.fromPassphrase("hi").getEncoded().length);
    }

    @Test
    void anEmptyPassphraseIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> KeyUtils.deriveFromPassphrase(new char[0], SALT, 1000));
    }
}
