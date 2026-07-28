package com.rakesh.chat.client;

import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.server.ChatServer;
import com.rakesh.chat.server.ServerConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 tests.
 *
 * <p>Every one of these drives a real {@link ChatClient} against a real {@link ChatServer}
 * over a real socket, and <b>not one of them starts JavaFX</b>. That is the payoff for
 * keeping the networking class free of UI imports: if {@code ChatClient} imported a
 * {@code TextArea}, this whole file would need a headless display to run.
 *
 * <p>The GUI itself is not tested here. Testing JavaFX needs an extra library (TestFX) and
 * a virtual display, which is more machinery than a college project needs — the window is
 * checked by hand against the checkpoint in PHASE-7-REPORT.md.
 */
class ChatClientTest {

    private ChatServer server;
    private Thread serverThread;
    private final List<ChatClient> started = new ArrayList<>();

    @BeforeEach
    void startServer(@TempDir Path tempDir) throws IOException, InterruptedException {
        ServerConfig config = ServerConfig.defaults();
        config.port = 0; // let the OS pick a free port
        config.connectionLogPath = tempDir.resolve("connections.log");

        server = new ChatServer(config);
        serverThread = new Thread(server::start, "test-server");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    @AfterEach
    void stopServer() {
        for (ChatClient client : started) {
            client.disconnect();
        }
        started.clear();
        server.shutdown();
    }

    // ------------------------------------------------------------------ the tests

    @Test
    @DisplayName("connecting sends HELLO and the server answers WELCOME")
    void connectingGetsAWelcome() throws Exception {
        Inbox alice = connect("alice");

        Message welcome = alice.await(MessageType.WELCOME);
        assertEquals("alice", welcome.sender());
        assertEquals("rakesh-chat", welcome.body());
    }

    @Test
    @DisplayName("a room message reaches the other client as CHAT")
    void roomMessagesReachEverybodyElse() throws Exception {
        Inbox alice = connect("alice");
        alice.await(MessageType.WELCOME);

        Inbox bob = connect("bob");
        bob.await(MessageType.WELCOME);

        alice.client.send(Message.msg("hello room"));

        Message chat = bob.await(MessageType.CHAT);
        assertEquals("alice", chat.sender());
        assertEquals("hello room", chat.body());
        assertNotNull(chat.timestamp());
    }

    @Test
    @DisplayName("your own room message never comes back to you")
    void theServerDoesNotEchoYourOwnRoomMessage() throws Exception {
        Inbox alice = connect("alice");
        alice.await(MessageType.WELCOME);

        Inbox bob = connect("bob");
        bob.await(MessageType.WELCOME);
        alice.await(MessageType.JOINED);

        alice.client.send(Message.msg("hello room"));
        bob.await(MessageType.CHAT); // bob has it, so the server has finished the broadcast

        assertNull(alice.poll(300),
                "the server does not echo MSG, which is why the window adds it locally");
    }

    @Test
    @DisplayName("a private message gives the target WHISPER and the sender SENT")
    void privateMessagesGoBothWays() throws Exception {
        Inbox alice = connect("alice");
        alice.await(MessageType.WELCOME);

        Inbox bob = connect("bob");
        bob.await(MessageType.WELCOME);

        alice.client.send(Message.pm("bob", "meet me at 5"));

        Message whisper = bob.await(MessageType.WHISPER);
        assertEquals("alice", whisper.sender());
        assertEquals("meet me at 5", whisper.body());

        Message sent = alice.await(MessageType.SENT);
        assertEquals("bob", sent.target());
        assertEquals("meet me at 5", sent.body());
    }

    @Test
    @DisplayName("LIST comes back as USERS and userList() splits it")
    void listGivesTheOnlineUsers() throws Exception {
        Inbox alice = connect("alice");
        alice.await(MessageType.WELCOME);

        Inbox bob = connect("bob");
        bob.await(MessageType.WELCOME);

        bob.client.send(Message.list());

        Message users = bob.await(MessageType.USERS);
        assertEquals(List.of("alice", "bob"), users.userList());
    }

    @Test
    @DisplayName("an error from the server arrives as a normal message, not an exception")
    void serverErrorsArriveAsMessages() throws Exception {
        Inbox alice = connect("alice");
        alice.await(MessageType.WELCOME);

        alice.client.send(Message.pm("nobody", "hello?"));

        Message error = alice.await(MessageType.ERROR);
        assertEquals(ErrorCode.NO_SUCH_USER, error.errorCode());
        assertTrue(alice.client.isConnected(), "one bad message must not kill the session");
    }

    @Test
    @DisplayName("disconnect() closes the client and the room is told")
    void disconnectEndsTheSession() throws Exception {
        Inbox alice = connect("alice");
        alice.await(MessageType.WELCOME);

        Inbox bob = connect("bob");
        bob.await(MessageType.WELCOME);

        bob.client.disconnect();
        assertFalse(bob.client.isConnected());

        Message left = alice.await(MessageType.LEFT);
        assertEquals("bob", left.sender());
    }

    @Test
    @DisplayName("the listener is told when the server goes away")
    void theListenerHearsAboutAnUnexpectedClose() throws Exception {
        Inbox alice = connect("alice");
        alice.await(MessageType.WELCOME);

        server.shutdown(); // pulls the socket out from under the client

        assertNotNull(alice.closeReason.poll(3, TimeUnit.SECONDS),
                "onClosed must fire when the connection drops by itself");
        assertFalse(alice.client.isConnected());
    }

    // ------------------------------------------------------------------ helpers

    private Inbox connect(String nickname) throws IOException {
        ChatClient client = new ChatClient();
        Inbox inbox = new Inbox(client);
        client.setListener(inbox);
        client.setOnClosed(reason -> inbox.closeReason.add(reason));
        client.connect("localhost", server.getPort(), nickname);
        started.add(client);
        return inbox;
    }

    /**
     * Collects everything the client hands to its listener.
     *
     * <p>A queue rather than a list, because the listener runs on the reader thread and the
     * test runs on the JUnit thread — {@code poll(timeout)} is how the test waits for the
     * network without a single {@code Thread.sleep}.
     */
    private static final class Inbox implements Consumer<Message> {

        final ChatClient client;
        final BlockingQueue<Message> received = new LinkedBlockingQueue<>();
        final BlockingQueue<String> closeReason = new LinkedBlockingQueue<>();

        Inbox(ChatClient client) {
            this.client = client;
        }

        @Override
        public void accept(Message message) {
            received.add(message);
        }

        /** Waits for the next message of this type, skipping anything else on the way. */
        Message await(MessageType type) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (System.nanoTime() < deadline) {
                Message m = received.poll(200, TimeUnit.MILLISECONDS);
                if (m != null && m.type() == type) {
                    return m;
                }
            }
            throw new AssertionError("timed out waiting for a " + type + " message");
        }

        /** Whatever turns up in the next {@code millis}, or null if nothing does. */
        Message poll(int millis) throws InterruptedException {
            return received.poll(millis, TimeUnit.MILLISECONDS);
        }
    }
}
