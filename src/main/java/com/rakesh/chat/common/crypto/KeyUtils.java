package com.rakesh.chat.common.crypto;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

/**
 * Two ways to get an AES key: make a random one, or turn a passphrase into one.
 *
 * <p><b>Why a passphrase is not a key.</b> An AES-256 key is 32 completely random bytes.
 * "hunter2" is 7 bytes and not random at all. Feeding it straight to AES would give an
 * attacker a key they can guess by trying dictionary words. PBKDF2 fixes both problems: it
 * stretches whatever you type into exactly 32 bytes, and it makes each guess expensive by
 * hashing 100,000 times, so a dictionary attack that would take a second takes days.
 */
public final class KeyUtils {

    /** How many PBKDF2 rounds. More = slower to derive, slower to attack. */
    public static final int DEFAULT_ITERATIONS = 100_000;

    /**
     * The salt every demo run uses.
     *
     * <p>A salt is public, random data mixed into the derivation so that two people with the
     * same passphrase do not end up with the same key, and so that an attacker cannot use a
     * pre-computed table. <b>Hard-coding it is a real weakness</b> and it is here on purpose:
     * the server and every client must derive the <i>same</i> key from the same passphrase,
     * and we have no key-exchange step to send a random salt through. A real system either
     * exchanges the salt during the handshake or does not use passphrases at all. See the
     * "Known limitations" section of README.md.
     */
    public static final byte[] DEMO_SALT = "rakesh-chat-demo-salt".getBytes(StandardCharsets.UTF_8);

    private KeyUtils() {
        // utility class, never instantiated
    }

    /**
     * A fresh random AES key.
     *
     * @param bits 128 or 256. 256 is the default everywhere else in this project; 128 is
     *             here so the tests can prove the class is not hard-wired to one size.
     */
    public static SecretKey generateAesKey(int bits) {
        if (bits != 128 && bits != 192 && bits != 256) {
            throw new IllegalArgumentException("AES keys are 128, 192 or 256 bits, not " + bits);
        }
        try {
            KeyGenerator generator = KeyGenerator.getInstance("AES");
            // SecureRandom, not Random: Random is predictable from a few outputs.
            generator.init(bits, new SecureRandom());
            return generator.generateKey();
        } catch (GeneralSecurityException e) {
            // "AES" is required to exist in every Java installation, so this cannot happen.
            throw new IllegalStateException("this JVM has no AES", e);
        }
    }

    /**
     * Turns a passphrase into a 256-bit AES key with PBKDF2.
     *
     * <p>The same passphrase, salt and iteration count always give the same key — that is
     * the whole point, it is how two machines end up with the same key without ever sending
     * it to each other. Change any one of the three and you get a different key.
     *
     * @param passphrase a char[] rather than a String, which is the usual convention for
     *                   secrets: a String would sit in the string pool until the garbage
     *                   collector felt like clearing it, and you cannot blank it yourself
     */
    public static SecretKey deriveFromPassphrase(char[] passphrase, byte[] salt, int iterations) {
        if (passphrase == null || passphrase.length == 0) {
            throw new IllegalArgumentException("passphrase must not be empty");
        }
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            PBEKeySpec spec = new PBEKeySpec(passphrase, salt, iterations, 256);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            // PBKDF2 gives us generic key material; this line labels those bytes "an AES key".
            return new SecretKeySpec(keyBytes, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("could not derive a key from the passphrase", e);
        }
    }

    /** The everyday version: demo salt, 100,000 rounds, 256-bit key. */
    public static SecretKey fromPassphrase(String passphrase) {
        return deriveFromPassphrase(passphrase.toCharArray(), DEMO_SALT, DEFAULT_ITERATIONS);
    }
}
