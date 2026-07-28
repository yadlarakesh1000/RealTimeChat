package com.rakesh.chat.server;

import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.common.crypto.MessageCipher;
import com.rakesh.chat.common.crypto.KeyUtils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 8 end to end, over real sockets.
 *
 * <p>These tests use {@link RawClient}, which writes bytes and reads bytes, so the
 * assertions are about <b>what is actually on the wire</b> rather than about what our own
 * classes think they sent. {@code theTextIsUnreadableOnTheWire} is the build guide's
 * Wireshark checkpoint written as an assertion: you should still take the screenshot,
 * but this test is what keeps the property true after you stop looking.
 */
@Timeout(60)
class Phase8Test {

    private static final String PASSPHRASE = "open sesame";

    @TempDir
    Path tmp;

    private ChatServer server;

    /** Our own copy of the cipher, standing in for a client that knows the passphrase. */
    private final MessageCipher cipher = new MessageCipher(KeyUtils.fromPassphrase(PASSPHRASE));

    /**
     * Started per test rather than in {@code @BeforeEach}, because half of these tests need
     * a server with a passphrase and half need one without.
     */
    private int startServer(String passphrase) throws Exception {
        ServerConfig config = new ServerConfig();
        config.port = 0;                                            // any free port
        config.passphrase = passphrase;
        config.connectionLogPath = tmp.resolve("connections.log");  // don't touch logs/

        server = new ChatServer(config);
        Thread acceptor = new Thread(server::start, "test-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
        return server.getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.shutdown();
        }
    }

    // ------------------------------------------------------------------ the demo

    @Nested
    @DisplayName("what an eavesdropper sees")
    class OnTheWire {

        @Test
        @DisplayName("the room message is unreadable, but the verb and nickname are not")
        void theTextIsUnreadableOnTheWire() throws Exception {
            int port = startServer(PASSPHRASE);

            try (RawClient alice = new RawClient(port).helloAs("alice");
                 RawClient bob = new RawClient(port).helloAs("bob")) {

                alice.read(); // JOINED bob
                alice.send("MSG " + cipher.encrypt("meet me at five"));

                String raw = bob.readRaw();

                // This is the whole point of the phase, in three assertions.
                assertTrue(raw.startsWith("CHAT alice "), "routing stays readable: " + raw);
                assertFalse(raw.contains("meet"), "the text leaked: " + raw);
                assertFalse(raw.contains("five"), "the text leaked: " + raw);

                // ...and bob, who has the passphrase, reads it perfectly well.
                Message chat = Message.parse(raw);
                assertEquals("meet me at five", cipher.decrypt(chat.body()));
            }
        }

        @Test
        void privateMessagesAreEncryptedInBothDirections() throws Exception {
            int port = startServer(PASSPHRASE);

            try (RawClient alice = new RawClient(port).helloAs("alice");
                 RawClient bob = new RawClient(port).helloAs("bob")) {

                alice.read(); // JOINED bob
                alice.send("PM bob " + cipher.encrypt("just between us"));

                String toBob = bob.readRaw();
                assertTrue(toBob.startsWith("WHISPER alice "), toBob);
                assertFalse(toBob.contains("between"), toBob);
                assertEquals("just between us", cipher.decrypt(Message.parse(toBob).body()));

                // The sender's own SENT confirmation is encrypted too - it is the same text.
                String confirmation = alice.readRaw();
                assertTrue(confirmation.startsWith("SENT bob "), confirmation);
                assertFalse(confirmation.contains("between"), confirmation);
                assertEquals("just between us", cipher.decrypt(Message.parse(confirmation).body()));
            }
        }

        @Test
        @DisplayName("the same sentence sent twice does not look the same twice")
        void repeatedTextGetsAFreshIvEachTime() throws Exception {
            int port = startServer(PASSPHRASE);

            try (RawClient alice = new RawClient(port).helloAs("alice");
                 RawClient bob = new RawClient(port).helloAs("bob")) {

                alice.read(); // JOINED bob
                alice.send("MSG " + cipher.encrypt("yes"));
                alice.send("MSG " + cipher.encrypt("yes"));

                String first = bob.read().body();
                String second = bob.read().body();

                // If these matched, an eavesdropper could count how often you say "yes"
                // without ever decrypting anything. That is what breaks ECB mode.
                assertNotEquals(first, second);
                assertEquals("yes", cipher.decrypt(first));
                assertEquals("yes", cipher.decrypt(second));
            }
        }

        @Test
        @DisplayName("who is online is still visible - the honest limitation")
        void metadataIsNotProtected() throws Exception {
            int port = startServer(PASSPHRASE);

            try (RawClient alice = new RawClient(port).helloAs("alice");
                 RawClient bob = new RawClient(port).helloAs("bob")) {

                assertEquals("JOINED bob", alice.readRaw());

                alice.send("LIST");
                assertEquals("USERS alice,bob", alice.readRaw());
            }
        }

        @Test
        void errorsStayReadableSoTheUserCanBeToldWhatWentWrong() throws Exception {
            int port = startServer(PASSPHRASE);

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                alice.send("PM nobody " + cipher.encrypt("hi"));

                assertEquals("ERROR NO_SUCH_USER no such user: nobody", alice.readRaw());
            }
        }
    }

    // ------------------------------------------------------------------ bad input

    @Nested
    @DisplayName("payloads that will not decrypt")
    class BadPayloads {

        @Test
        @DisplayName("a tampered message is refused and the connection survives")
        void tamperingIsRejectedWithoutDroppingTheClient() throws Exception {
            int port = startServer(PASSPHRASE);

            try (RawClient alice = new RawClient(port).helloAs("alice");
                 RawClient bob = new RawClient(port).helloAs("bob")) {

                alice.read(); // JOINED bob

                String payload = cipher.encrypt("transfer 100 rupees");
                alice.send("MSG " + flipOneCharacter(payload));

                alice.expectError(ErrorCode.BAD_PAYLOAD);

                // Nobody else saw the broken message at all.
                bob.expectNothing(300);

                // And alice is still connected, so a good message goes straight through.
                alice.send("MSG " + cipher.encrypt("sorry, again"));
                assertEquals("sorry, again", cipher.decrypt(bob.read().body()));
            }
        }

        @Test
        void plainTextOnAnEncryptedServerIsRefused() throws Exception {
            int port = startServer(PASSPHRASE);

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                alice.send("MSG hello in the clear");

                alice.expectError(ErrorCode.BAD_PAYLOAD);
            }
        }

        @Test
        void theWrongPassphraseIsRefused() throws Exception {
            int port = startServer(PASSPHRASE);
            MessageCipher stranger = new MessageCipher(KeyUtils.fromPassphrase("wrong one"));

            try (RawClient carol = new RawClient(port).helloAs("carol")) {
                carol.send("MSG " + stranger.encrypt("let me in"));

                carol.expectError(ErrorCode.BAD_PAYLOAD);
            }
        }

        @Test
        @DisplayName("a bad payload does not stop the handshake or the housekeeping verbs")
        void everythingElseKeepsWorkingAfterABadPayload() throws Exception {
            int port = startServer(PASSPHRASE);

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                alice.send("MSG not-encrypted");
                alice.expectError(ErrorCode.BAD_PAYLOAD);

                alice.send("LIST");
                assertEquals(MessageType.USERS, alice.read().type());
            }
        }
    }

    // ------------------------------------------------------------------ no regression

    @Nested
    @DisplayName("with no passphrase, Phase 7 behaviour is unchanged")
    class EncryptionOff {

        @Test
        void plainTextStillWorks() throws Exception {
            int port = startServer(null);

            try (RawClient alice = new RawClient(port).helloAs("alice");
                 RawClient bob = new RawClient(port).helloAs("bob")) {

                alice.read(); // JOINED bob
                alice.send("MSG hello room");

                String raw = bob.readRaw();
                assertTrue(raw.startsWith("CHAT alice "), raw);
                assertTrue(raw.endsWith(" hello room"), raw);
            }
        }

        @Test
        void anEncryptedClientTalkingToAPlainServerJustLooksLikeNoise() throws Exception {
            int port = startServer(null);

            try (RawClient alice = new RawClient(port).helloAs("alice");
                 RawClient bob = new RawClient(port).helloAs("bob")) {

                alice.read(); // JOINED bob
                alice.send("MSG " + cipher.encrypt("hello"));

                // The server has no key, so it forwards the base64 untouched. Bob, who does
                // have the key, can still read it - but the server can no longer tell a
                // valid message from nonsense, which is why the mismatch is worth catching
                // rather than tolerating. Mixing modes is a misconfiguration, not a feature.
                assertEquals("hello", cipher.decrypt(bob.read().body()));
            }
        }
    }

    // ------------------------------------------------------------------ the real client

    @Nested
    @DisplayName("two real ChatClients, the way the GUI uses them")
    class RealClients {

        @Test
        void twoClientsWithTheSamePassphraseChatNormally() throws Exception {
            int port = startServer(PASSPHRASE);

            Inbox alice = connect(port, "alice", PASSPHRASE);
            Inbox bob = connect(port, "bob", PASSPHRASE);
            try {
                assertTrue(alice.client.isEncrypted());

                alice.client.send(Message.msg("hello room"));

                // The client decrypts on the way in, so the listener - and therefore the
                // window - never sees a byte of base64.
                Message chat = bob.await(MessageType.CHAT);
                assertEquals("alice", chat.sender());
                assertEquals("hello room", chat.body());
            } finally {
                alice.client.disconnect();
                bob.client.disconnect();
            }
        }

        @Test
        @DisplayName("the wrong passphrase gets ERROR BAD_PAYLOAD, not a crash")
        void aClientWithTheWrongPassphraseIsToldWhy() throws Exception {
            int port = startServer(PASSPHRASE);

            Inbox carol = connect(port, "carol", "wrong one");
            try {
                carol.client.send(Message.msg("let me in"));

                Message error = carol.await(MessageType.ERROR);
                assertEquals(ErrorCode.BAD_PAYLOAD, error.errorCode());
                assertTrue(carol.client.isConnected(), "one bad message must not kill the session");
            } finally {
                carol.client.disconnect();
            }
        }

        private Inbox connect(int port, String nickname, String passphrase) throws Exception {
            com.rakesh.chat.client.ChatClient client = new com.rakesh.chat.client.ChatClient();
            Inbox inbox = new Inbox(client);
            client.setListener(inbox);
            client.connect("localhost", port, nickname, passphrase);
            inbox.await(MessageType.WELCOME);
            return inbox;
        }
    }

    /** Collects what a {@code ChatClient} hands to its listener, on the reader thread. */
    private static final class Inbox implements java.util.function.Consumer<Message> {

        final com.rakesh.chat.client.ChatClient client;
        final java.util.concurrent.BlockingQueue<Message> received =
                new java.util.concurrent.LinkedBlockingQueue<>();

        Inbox(com.rakesh.chat.client.ChatClient client) {
            this.client = client;
        }

        @Override
        public void accept(Message message) {
            received.add(message);
        }

        Message await(MessageType type) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                Message m = received.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (m != null && m.type() == type) {
                    return m;
                }
            }
            throw new AssertionError("timed out waiting for a " + type + " message");
        }
    }

    private static String flipOneCharacter(String payload) {
        int index = payload.length() / 2;
        char replacement = (payload.charAt(index) == 'A') ? 'B' : 'A';
        return payload.substring(0, index) + replacement + payload.substring(index + 1);
    }
}
