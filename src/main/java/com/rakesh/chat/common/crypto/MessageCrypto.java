package com.rakesh.chat.common.crypto;

import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.common.ProtocolException;

import java.security.GeneralSecurityException;

/**
 * Decides <b>which part</b> of a message gets encrypted, and does it.
 *
 * <p>{@link MessageCipher} knows how to scramble text and nothing else. This class knows the
 * protocol: only the <b>body</b> of the six verbs that carry something a human typed is
 * encrypted. The verb, the nickname, the target and the timestamp stay in plain text,
 * because the server has to read them to route the message. That is a real, permanent leak
 * and it is written down in PROTOCOL.md section 10 rather than hidden.
 *
 * <pre>
 *   PM      bob   {base64}          &lt;- "bob" readable, the text is not
 *   WHISPER alice 2026-..Z {base64}
 * </pre>
 *
 * <p>Use {@link #OFF} when no passphrase is configured. It passes every message straight
 * through, so the server and the client behave exactly as they did in Phase 7 and you can
 * still drive them by hand with telnet.
 */
public final class MessageCrypto {

    /** No passphrase configured: everything stays plain text. */
    public static final MessageCrypto OFF = new MessageCrypto(null);

    /** null means "off". */
    private final MessageCipher cipher;

    private MessageCrypto(MessageCipher cipher) {
        this.cipher = cipher;
    }

    /** @param passphrase null or blank gives you {@link #OFF} */
    public static MessageCrypto forPassphrase(String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            return OFF;
        }
        return new MessageCrypto(new MessageCipher(KeyUtils.fromPassphrase(passphrase)));
    }

    public boolean isOn() {
        return cipher != null;
    }

    /**
     * The six verbs whose body is something a person typed. Everything else — WELCOME,
     * ERROR, USERS, JOINED, LEFT, HELLO, LIST, QUIT — is the server talking about the
     * connection, not a private thought, and is left readable so that a plain telnet
     * session still tells you what is going on.
     */
    private static boolean hasSecretBody(MessageType type) {
        return switch (type) {
            case MSG, PM, REPLY, CHAT, WHISPER, SENT -> true;
            default -> false;
        };
    }

    /** Same message with the body scrambled, or the same object if there is nothing to do. */
    public Message encrypt(Message message) {
        if (cipher == null || !hasSecretBody(message.type())) {
            return message;
        }
        return withBody(message, cipher.encrypt(message.body()));
    }

    /**
     * Same message with the body readable again.
     *
     * @throws ProtocolException with {@link ErrorCode#BAD_PAYLOAD} if the body was tampered
     *         with, was sent by someone using a different passphrase, or was plain text sent
     *         to an encrypted server. The caller answers with an {@code ERROR} line and keeps
     *         the connection — a bad message kills the message, not the session.
     */
    public Message decrypt(Message message) throws ProtocolException {
        if (cipher == null || !hasSecretBody(message.type())) {
            return message;
        }
        try {
            return withBody(message, cipher.decrypt(message.body()));
        } catch (GeneralSecurityException e) {
            // Deliberately vague: telling a peer exactly why decryption failed is how
            // padding-oracle style attacks get started. "It did not decrypt" is enough.
            throw new ProtocolException(ErrorCode.BAD_PAYLOAD,
                    "could not decrypt the body of " + message.type()
                            + " (wrong passphrase, or the message was altered)");
        }
    }

    /** Records are immutable, so "changing" the body means building a new one. */
    private static Message withBody(Message m, String body) {
        return new Message(m.type(), m.sender(), m.target(), body, m.timestamp());
    }
}
