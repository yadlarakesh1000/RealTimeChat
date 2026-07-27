package com.rakesh.chat.server;

import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.rakesh.chat.server.TestSupport.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Step 4 checkpoint, automated.
 *
 * <p>The manual {@code nc} walkthrough is still worth doing once — it is how you find
 * out that your error text is unreadable. But every line of it is a regression waiting
 * to happen, so it lives here too. Real sockets, real threads, an ephemeral port.
 *
 * <p>Phase 5 moved the {@code RawClient} harness out to its own file so this suite and
 * {@link Phase5Test} share one hand-written protocol client rather than two that drift.
 */
@Timeout(20)
class ProtocolHandshakeTest {

    @TempDir
    Path tmp;

    private ChatServer server;
    private final List<RawClient> clients = new ArrayList<>();

    private RawClient connect() throws IOException {
        RawClient c = new RawClient(server.getPort());
        clients.add(c);
        return c;
    }

    @BeforeEach
    void startServer() throws IOException {
        // Default settings, but on a free port and with the log redirected so the suite
        // leaves nothing behind in logs/.
        ServerConfig config = new ServerConfig();
        config.port = 0;
        config.connectionLogPath = tmp.resolve("connections.log");
        server = new ChatServer(config);
        Thread acceptor = new Thread(server::start, "test-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(RawClient::close);
        clients.clear();
        server.shutdown();
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("HELLO 1 alice -> WELCOME alice rakesh-chat")
    void handshakeSucceeds() throws Exception {
        RawClient alice = connect();
        alice.send("HELLO 1 alice");

        Message welcome = alice.expect(MessageType.WELCOME);
        assertEquals("alice", welcome.sender());
        assertEquals("rakesh-chat", welcome.body());
    }

    @Test
    @DisplayName("B1/B2: a second 'alice' SEES the ERROR before the socket closes")
    void nickTakenReachesTheClient() throws Exception {
        connect().helloAs("alice");

        RawClient impostor = connect();
        impostor.send("HELLO 1 alice");

        // This is the bug. The error used to be queued onto a writer thread that had not
        // been started and never would be, so the client saw a silent close.
        Message err = impostor.expect(MessageType.ERROR);
        assertEquals(ErrorCode.NICK_TAKEN, err.errorCode());
        assertTrue(err.body().contains("alice"), err.body());

        impostor.expectClosed();
    }

    @Test
    @DisplayName("nickname collision is case-insensitive")
    void nickTakenIsCaseInsensitive() throws Exception {
        connect().helloAs("Alice");
        RawClient other = connect();
        other.send("HELLO 1 alice");
        assertEquals(ErrorCode.NICK_TAKEN, other.expect(MessageType.ERROR).errorCode());
    }

    @Test
    void badVersionIsReportedThenDisconnected() throws Exception {
        RawClient c = connect();
        c.send("HELLO 2 alice");

        Message err = c.expect(MessageType.ERROR);
        assertEquals(ErrorCode.BAD_VERSION, err.errorCode());
        c.expectClosed();
    }

    @Test
    @DisplayName("anything other than HELLO first is MALFORMED + disconnect")
    void nonHelloFirstMessageIsRejected() throws Exception {
        RawClient c = connect();
        c.send("MSG hello before saying hello");

        Message err = c.expect(MessageType.ERROR);
        assertEquals(ErrorCode.MALFORMED, err.errorCode());
        c.expectClosed();
    }

    @Test
    @DisplayName("alice sees JOINED bob")
    void joinIsAnnounced() throws Exception {
        RawClient alice = connect().helloAs("alice");
        connect().helloAs("bob");

        Message joined = alice.expect(MessageType.JOINED);
        assertEquals("bob", joined.sender());
    }

    @Test
    @DisplayName("MSG hello world how are you -> full body intact")
    void broadcastPreservesTheWholeBody() throws Exception {
        RawClient alice = connect().helloAs("alice");
        RawClient bob = connect().helloAs("bob");
        alice.expect(MessageType.JOINED);

        alice.send("MSG hello world how are you");

        Message chat = bob.expect(MessageType.CHAT);
        assertEquals("alice", chat.sender());
        assertEquals("hello world how are you", chat.body());
        assertNotNull(chat.timestamp(), "the server assigns the timestamp");
    }

    @Test
    void emojiSurviveTheWire() throws Exception {
        RawClient alice = connect().helloAs("alice");
        RawClient bob = connect().helloAs("bob");
        alice.expect(MessageType.JOINED);

        alice.send("MSG hello 👋🏽 wörld 🎉");
        assertEquals("hello 👋🏽 wörld 🎉", bob.expect(MessageType.CHAT).body());
    }

    @Test
    @DisplayName("LIST -> USERS alice,bob")
    void listReturnsEveryoneOnline() throws Exception {
        RawClient alice = connect().helloAs("alice");
        connect().helloAs("bob");
        alice.expect(MessageType.JOINED);

        alice.send("LIST");
        // Exact list, not containsAll: the order is part of the contract, so that USERS
        // does not shuffle between calls at the whim of ConcurrentHashMap's hash order.
        assertEquals(List.of("alice", "bob"), alice.expect(MessageType.USERS).userList());
    }

    @Test
    @DisplayName("GARBAGE -> ERROR MALFORMED, connection STAYS OPEN")
    void unknownVerbDoesNotDropTheConnection() throws Exception {
        RawClient alice = connect().helloAs("alice");

        alice.send("GARBAGE");
        assertEquals(ErrorCode.MALFORMED, alice.expect(MessageType.ERROR).errorCode());

        // Still usable: the connection survived.
        alice.send("LIST");
        assertEquals(List.of("alice"), alice.expect(MessageType.USERS).userList());
    }

    @Test
    @DisplayName("bare MSG and lowercase msg both error without dropping the connection")
    void malformedMessagesDoNotDropTheConnection() throws Exception {
        RawClient alice = connect().helloAs("alice");

        for (String bad : List.of("MSG", "MSG ", "msg hello", "PM bob", "  ", "WELCOME a b")) {
            alice.send(bad);
            Message err = alice.expect(MessageType.ERROR);
            assertEquals(ErrorCode.MALFORMED, err.errorCode(), "for input <" + bad + ">");
        }

        alice.send("LIST");
        assertEquals(MessageType.USERS, alice.read().type());
    }

    @Test
    @DisplayName("PM to somebody who is not online -> ERROR NO_SUCH_USER (Phase 6)")
    void pmToAnOfflineUserIsRefused() throws Exception {
        RawClient alice = connect().helloAs("alice");
        alice.send("PM bob secret");

        Message err = alice.expect(MessageType.ERROR);
        assertEquals(ErrorCode.NO_SUCH_USER, err.errorCode());
        assertTrue(err.body().contains("bob"), err.body());

        // The connection survives: a bad message kills the message, not the session.
        alice.send("LIST");
        assertEquals(MessageType.USERS, alice.read().type());
    }

    @Test
    @DisplayName("5000-byte line -> ERROR TOO_LONG, then the connection closes")
    void overLongLineIsTheOneThingThatDisconnects() throws Exception {
        RawClient alice = connect().helloAs("alice");

        alice.send("MSG " + "a".repeat(5000));

        Message err = alice.expect(MessageType.ERROR);
        assertEquals(ErrorCode.TOO_LONG, err.errorCode());
        alice.expectClosed();
    }

    @Test
    @DisplayName("a line of exactly 4096 bytes is delivered")
    void theCapBoundaryIsExact() throws Exception {
        RawClient alice = connect().helloAs("alice");
        RawClient bob = connect().helloAs("bob");
        alice.expect(MessageType.JOINED);

        String body = "a".repeat(4096 - "MSG ".length());
        alice.send("MSG " + body);

        assertEquals(body, bob.expect(MessageType.CHAT).body());
    }

    @Test
    @DisplayName("telnet's CRLF works: the reader strips the CR")
    void crLfLineEndingsAreAccepted() throws Exception {
        RawClient alice = connect();
        alice.sendCrLf("HELLO 1 alice");
        assertEquals("alice", alice.expect(MessageType.WELCOME).sender());

        RawClient bob = connect();
        bob.sendCrLf("HELLO 1 bob");
        bob.expect(MessageType.WELCOME);
        alice.expect(MessageType.JOINED);

        alice.sendCrLf("MSG typed in telnet");
        assertEquals("typed in telnet", bob.expect(MessageType.CHAT).body(),
                "a trailing CR must not survive into the body");
    }

    @Test
    @DisplayName("QUIT closes cleanly and the other client sees LEFT")
    void quitAnnouncesDeparture() throws Exception {
        RawClient alice = connect().helloAs("alice");
        RawClient bob = connect().helloAs("bob");
        alice.expect(MessageType.JOINED);

        bob.send("QUIT");
        bob.expectClosed();

        assertEquals("bob", alice.expect(MessageType.LEFT).sender());
        await("bob to be unregistered", () -> server.getRegistry().size() == 1);
    }

    @Test
    @DisplayName("B3: an abrupt disconnect also produces LEFT")
    void abruptDisconnectAnnouncesDeparture() throws Exception {
        RawClient alice = connect().helloAs("alice");
        RawClient bob = connect().helloAs("bob");
        alice.expect(MessageType.JOINED);

        // No QUIT — just yank the socket, which is what Ctrl-C does. The old code
        // broadcast LEFT after the read loop, so the IOException skipped it entirely.
        bob.close();

        Message left = alice.expect(MessageType.LEFT);
        assertEquals("bob", left.sender());
    }

    @Test
    @DisplayName("EOF without a HELLO leaves nothing behind")
    void connectAndCloseWithoutHandshakeIsClean() throws Exception {
        RawClient alice = connect().helloAs("alice");

        RawClient silent = connect();
        silent.close();

        // No LEFT, no registry entry: the client never joined.
        await("registry to settle", () -> server.getRegistry().size() == 1);
        alice.send("LIST");
        assertEquals(List.of("alice"), alice.expect(MessageType.USERS).userList());
    }

    @Test
    @DisplayName("a nickname freed by disconnect can be reclaimed")
    void nicknameIsReusableAfterDeparture() throws Exception {
        RawClient first = connect().helloAs("alice");
        first.send("QUIT");
        first.expectClosed();
        await("alice to be unregistered", () -> server.getRegistry().size() == 0);

        RawClient second = connect();
        second.send("HELLO 1 alice");
        assertEquals(MessageType.WELCOME, second.read().type());
    }

    @Test
    @DisplayName("a second HELLO is an error but does not drop the connection")
    void duplicateHelloIsRejectedWithoutDisconnecting() throws Exception {
        RawClient alice = connect().helloAs("alice");

        alice.send("HELLO 1 alice2");
        assertEquals(ErrorCode.MALFORMED, alice.expect(MessageType.ERROR).errorCode());

        alice.send("LIST");
        assertEquals(List.of("alice"), alice.expect(MessageType.USERS).userList());
    }

    @Test
    @DisplayName("the sender does not receive an echo of its own MSG")
    void senderDoesNotSeeItsOwnMessage() throws Exception {
        RawClient alice = connect().helloAs("alice");
        RawClient bob = connect().helloAs("bob");
        alice.expect(MessageType.JOINED);

        alice.send("MSG one");
        assertEquals("one", bob.expect(MessageType.CHAT).body());

        // If alice were echoed, this LIST reply would be preceded by her own CHAT.
        alice.send("LIST");
        assertEquals(MessageType.USERS, alice.read().type());
    }
}
