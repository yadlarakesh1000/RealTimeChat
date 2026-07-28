package com.rakesh.chat.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts and decrypts one piece of text with AES-GCM.
 *
 * <p><b>What comes out.</b> {@link #encrypt} returns Base64 text laid out like this:
 *
 * <pre>
 *   base64( 12-byte IV | ciphertext | 16-byte tag )
 *           \_______/   \________________________/
 *            in clear    what Cipher.doFinal gave us
 * </pre>
 *
 * <p>The IV travels in the clear, and that is fine — an IV is not a secret, it just has to
 * be <b>different every time</b>. Reusing one IV with one key in GCM is the classic
 * catastrophic mistake: it leaks the relationship between the two messages and lets an
 * attacker forge new ones. So we generate 12 fresh random bytes per message and glue them
 * on the front, because the other side cannot decrypt without them.
 *
 * <p>The 16-byte <b>tag</b> at the end is what makes GCM <i>authenticated</i> encryption.
 * Java's {@code Cipher} appends it to the ciphertext for you. On decryption it is checked
 * before you get a single byte back, so a tampered message throws
 * {@link javax.crypto.AEADBadTagException} instead of quietly returning garbage.
 */
public class MessageCipher {

    /** GCM's recommended IV size. 12 bytes is what everyone uses; other sizes are slower. */
    private static final int IV_BYTES = 12;

    /** Tag length in <b>bits</b>, which is what GCMParameterSpec wants. 128 bits = 16 bytes. */
    private static final int TAG_BITS = 128;

    /** The same number in bytes, for the length check in {@link #decrypt}. */
    private static final int TAG_BYTES = TAG_BITS / 8;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public MessageCipher(SecretKey key) {
        if (key == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        this.key = key;
    }

    /** @return base64(iv + ciphertext + tag) — safe to put in a line of our text protocol */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv); // a new one for every single message

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] payload = new byte[iv.length + sealed.length];
            System.arraycopy(iv, 0, payload, 0, iv.length);
            System.arraycopy(sealed, 0, payload, iv.length, sealed.length);

            // Base64 is not encryption. It is here only because our protocol is
            // newline-delimited text and ciphertext is arbitrary bytes, some of which
            // would be a newline or a control character and would break the framing.
            return Base64.getEncoder().encodeToString(payload);

        } catch (GeneralSecurityException e) {
            // Everything used here is built into Java, so a failure means a broken JVM or a
            // key of the wrong type - our bug, not the peer's.
            throw new IllegalStateException("encryption failed", e);
        }
    }

    /**
     * Reverses {@link #encrypt}.
     *
     * @throws javax.crypto.AEADBadTagException if the payload was altered, or was encrypted
     *         with a different key — GCM cannot tell those two apart, and does not need to
     * @throws GeneralSecurityException if the text is not Base64, or is too short to hold
     *         an IV and a tag
     */
    public String decrypt(String payload) throws GeneralSecurityException {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(payload);
        } catch (IllegalArgumentException e) {
            // Turn "that is not base64" into the same kind of failure as "that does not
            // decrypt", so callers have one thing to catch.
            throw new GeneralSecurityException("payload is not valid base64");
        }

        // Every real payload is at least an IV plus a tag, even when the text was empty.
        // Checking that here is not fussiness: hand a shorter byte array to the JCE and it
        // throws a ProviderException, which is a RuntimeException and would escape every
        // `catch (GeneralSecurityException)` in the project. A peer controls these bytes,
        // so refusing them here is what keeps a bad payload a protocol error rather than a
        // crash. (LEARNING-LOG.md, B8.)
        if (bytes.length < IV_BYTES + TAG_BYTES) {
            throw new GeneralSecurityException("payload is too short to be encrypted");
        }

        // Split the front off: the first 12 bytes are the IV, the rest is for the cipher.
        byte[] iv = new byte[IV_BYTES];
        System.arraycopy(bytes, 0, iv, 0, IV_BYTES);
        byte[] sealed = new byte[bytes.length - IV_BYTES];
        System.arraycopy(bytes, IV_BYTES, sealed, 0, sealed.length);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        // doFinal checks the tag first. If it does not match, nothing is returned at all.
        return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    }
}
