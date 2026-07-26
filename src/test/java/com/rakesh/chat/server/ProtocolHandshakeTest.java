package com.rakesh.chat.server;

import com.rakesh.chat.common.BoundedLineReader;
import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.common.ProtocolException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Step 4 checkpoint, automated.
 *
 * <p>The manual {@code nc} walkthrough is still worth doing once — it is how you find
 * out that your error text is unreadable. But every line of it is a regression waiting
 * to happen, so it lives here too. Real sockets, real threads, an ephemeral port.
 */
@Timeout(15)
class ProtocolHandshakeTest {

    private ChatServer server;
    private Thread acceptor;
    private final List<RawClient> clients = new ArrayList<>();

    /** A hand-driven client that speaks raw protocol, exactly as {@code nc} would. */
    private final class RawClient implements Closeable {
        private final Socket socket;
        private final PrintWriter out;
        private final BoundedLineReader in;

        RawClient() throws IOException {
            this.socket = new Socket();
            this.socket.connect(new InetSocketAddress("127.0.0.1", server.getPort()), 3000);
            this.socket.setSoTimeout(5000);
            this.out = new PrintWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8), true);
            this.in = new BoundedLineReader(socket.getInputStream(), 1 << 20);
        }

        /** Writes a raw line with a bare LF, as nc does. */
        RawClient send(String rawLine) {
            out.print(rawLine + "\n");
            out.flush();
            return this;
        }

        /** Writes a raw line with CRLF, as Windows telnet does. */
        RawClient sendCrLf(String rawLine) {
            out.print(rawLine + "\r\n");
            out.flush();
            return this;
        }

        String readRaw() throws IOException, ProtocolException {
            return in.readLine();
        }

        Message read() throws IOException, ProtocolException {
            String line = readRaw();
            assertNotNull(line, "expected a message, got end of stream");
            return Message.parse(line);
        }

        Message expect(MessageType type) throws IOException, ProtocolException {
            Message m = read();
            assertEquals(type, m.type(), "unexpected message: " + m);
            return m;
        }

        void expectClosed() throws IOException, ProtocolException {
            assertNull(readRaw(), "expected the server to close the connection");
        }

        RawClient helloAs(String nick) throws IOException, ProtocolException {
            send("HELLO 1 " + nick);
            assertEquals(MessageType.WELCOME, read().type());
            return this;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // test teardown
            }
        }
    }

    private RawClient connect() throws IOException {
        RawClient c = new RawClient();
        clients.add(c);
        return c;
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new ChatServer(0);
        acceptor = new Thread(server::start, "test-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @AfterEach
    void stopServer() {
        clients.forEach(RawClient::close);
        clients.clear();
        server.shutdown();
    }

    /** Waits for a condition instead of sleeping a magic number of milliseconds. */
    private static void await(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        fail("timed out waiting for: " + what);
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
    @DisplayName("PM is refused politely until Phase 6")
    void pmIsNotImplementedYet() throws Exception {
        RawClient alice = connect().helloAs("alice");
        alice.send("PM bob secret");

        Message err = alice.expect(MessageType.ERROR);
        assertEquals(ErrorCode.MALFORMED, err.errorCode());
        assertTrue(err.body().contains("Phase 6"), err.body());
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
