package com.rakesh.chat.common.crypto;

import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.common.ProtocolException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Which part of a message is secret, and which part is not.
 *
 * <p>The "not" half is the interesting one: {@code nicknamesStayReadable} is the test that
 * documents the metadata leak. An eavesdropper still learns who is talking to whom and when
 * — they just cannot read what was said.
 */
class MessageCryptoTest {

    private final MessageCrypto crypto = MessageCrypto.forPassphrase("open sesame");

    @Test
    void aBlankPassphraseMeansNoEncryption() {
        assertFalse(MessageCrypto.forPassphrase(null).isOn());
        assertFalse(MessageCrypto.forPassphrase("").isOn());
        assertFalse(MessageCrypto.forPassphrase("   ").isOn());
        assertTrue(MessageCrypto.forPassphrase("x").isOn());
    }

    @Test
    void withEncryptionOffTheMessageIsHandedBackUntouched() throws Exception {
        Message m = Message.msg("hello room");

        assertSame(m, MessageCrypto.OFF.encrypt(m));
        assertSame(m, MessageCrypto.OFF.decrypt(m));
    }

    @Test
    void theBodyIsScrambledAndEverythingElseIsNot() {
        Message chat = Message.chat("alice", "meet me at five", Instant.parse("2026-07-28T10:00:00Z"));

        Message sealed = crypto.encrypt(chat);

        assertEquals(MessageType.CHAT, sealed.type());
        assertEquals("alice", sealed.sender());
        assertEquals(chat.timestamp(), sealed.timestamp());
        assertNotEquals("meet me at five", sealed.body());
    }

    @Test
    @DisplayName("the whole wire line still shows the verb, the nickname and the time")
    void nicknamesStayReadable() {
        String line = crypto.encrypt(
                Message.chat("alice", "meet me at five", Instant.parse("2026-07-28T10:00:00Z")))
                .serialize();

        assertTrue(line.startsWith("CHAT alice 2026-07-28T10:00:00Z "), line);
        assertFalse(line.contains("meet"));
    }

    @Test
    void privateMessagesKeepTheirTargetReadableSoTheServerCanRoute() {
        String line = crypto.encrypt(Message.pm("bob", "just between us")).serialize();

        assertTrue(line.startsWith("PM bob "), line);
        assertFalse(line.contains("between"));
    }

    @Test
    void allSixHumanTypedVerbsAreEncrypted() throws Exception {
        assertBodyIsHidden(Message.msg("text"));
        assertBodyIsHidden(Message.pm("bob", "text"));
        assertBodyIsHidden(Message.reply("text"));
        assertBodyIsHidden(Message.chat("alice", "text"));
        assertBodyIsHidden(Message.whisper("alice", "text"));
        assertBodyIsHidden(Message.sent("bob", "text"));
    }

    @Test
    @DisplayName("server chatter stays in plain text on purpose")
    void housekeepingVerbsAreLeftAlone() {
        Message hello = Message.hello("alice");
        assertSame(hello, crypto.encrypt(hello), "HELLO carries no body at all");

        assertEquals("WELCOME alice rakesh-chat",
                crypto.encrypt(Message.welcome("alice", "rakesh-chat")).serialize());
        assertEquals("USERS alice,bob",
                crypto.encrypt(Message.users(java.util.List.of("alice", "bob"))).serialize());
        assertEquals("JOINED bob", crypto.encrypt(Message.joined("bob")).serialize());
        assertEquals("LEFT bob", crypto.encrypt(Message.left("bob")).serialize());
        assertEquals("ERROR NO_SUCH_USER no such user: carol",
                crypto.encrypt(Message.error(ErrorCode.NO_SUCH_USER, "no such user: carol"))
                      .serialize());
    }

    @Test
    @DisplayName("encrypt, serialize, parse, decrypt gets the original back")
    void theFullWireRoundTrip() throws Exception {
        Message original = Message.pm("bob", "hello  there, with  spaces");

        String line = crypto.encrypt(original).serialize();
        Message received = crypto.decrypt(Message.parse(line));

        assertEquals(original, received);
    }

    @Test
    void aTamperedBodyBecomesAProtocolError() {
        Message sealed = crypto.encrypt(Message.msg("transfer 100"));
        Message tampered = new Message(MessageType.MSG, null, null,
                flipOneCharacter(sealed.body()), null);

        ProtocolException e = assertThrows(ProtocolException.class, () -> crypto.decrypt(tampered));
        assertEquals(ErrorCode.BAD_PAYLOAD, e.code());
    }

    @Test
    void aDifferentPassphraseCannotRead() {
        Message sealed = crypto.encrypt(Message.msg("hello"));
        MessageCrypto stranger = MessageCrypto.forPassphrase("wrong one");

        ProtocolException e = assertThrows(ProtocolException.class, () -> stranger.decrypt(sealed));
        assertEquals(ErrorCode.BAD_PAYLOAD, e.code());
    }

    @Test
    @DisplayName("plain text sent to an encrypted server is refused, not passed on")
    void plainTextIsNotSilentlyAccepted() {
        ProtocolException e = assertThrows(ProtocolException.class,
                () -> crypto.decrypt(Message.msg("hello in the clear")));
        assertEquals(ErrorCode.BAD_PAYLOAD, e.code());
    }

    @Test
    void theErrorNeverLeaksTheTextItFailedOn() {
        Message sealed = crypto.encrypt(Message.msg("my bank pin is 1234"));
        MessageCrypto stranger = MessageCrypto.forPassphrase("wrong one");

        ProtocolException e = assertThrows(ProtocolException.class, () -> stranger.decrypt(sealed));
        assertFalse(e.getMessage().contains(sealed.body()));
    }

    private void assertBodyIsHidden(Message m) throws Exception {
        Message sealed = crypto.encrypt(m);
        assertNotEquals(m.body(), sealed.body(), m.type() + " should have been encrypted");
        assertEquals(m, crypto.decrypt(sealed));
    }

    private static String flipOneCharacter(String payload) {
        int index = payload.length() / 2;
        char replacement = (payload.charAt(index) == 'A') ? 'B' : 'A';
        return payload.substring(0, index) + replacement + payload.substring(index + 1);
    }
}
