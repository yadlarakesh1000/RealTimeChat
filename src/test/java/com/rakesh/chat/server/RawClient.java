package com.rakesh.chat.server;

import com.rakesh.chat.common.BoundedLineReader;
import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.common.ProtocolException;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A hand-driven client that speaks raw protocol, exactly as {@code nc} would.
 *
 * <p>Deliberately <b>not</b> built on any production client class. The whole value of this
 * harness is that it makes no assumption the server also makes — it writes bytes and reads
 * bytes. A test that drives the server through the same encoder the server decodes with
 * can only ever prove the two agree with each other, not that either matches PROTOCOL.md.
 *
 * <p>Its own reader is a {@link BoundedLineReader} with a 1&nbsp;MB cap rather than the
 * protocol's 4096, so that a server bug which emits an over-long line shows up as a failed
 * assertion about content rather than as a {@code TOO_LONG} from the test's own reader.
 */
final class RawClient implements Closeable {

    private final Socket socket;
    private final PrintWriter out;
    private final BoundedLineReader in;

    RawClient(int port) throws IOException {
        this(port, 0);
    }

    /**
     * @param receiveBufferBytes when positive, sets {@code SO_RCVBUF} <b>before</b> connect
     *                           — the only point at which it can influence the negotiated
     *                           TCP window. A tiny receive buffer is how a test makes a
     *                           client credibly "stalled" without suspending a process.
     */
    RawClient(int port, int receiveBufferBytes) throws IOException {
        this.socket = new Socket();
        if (receiveBufferBytes > 0) {
            this.socket.setReceiveBufferSize(receiveBufferBytes);
        }
        this.socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
        // Every read in a test must fail rather than hang: a hung test tells you nothing
        // and a failed one names the line it was waiting on.
        this.socket.setSoTimeout(5000);
        this.out = new PrintWriter(new OutputStreamWriter(
                socket.getOutputStream(), StandardCharsets.UTF_8), true);
        this.in = new BoundedLineReader(socket.getInputStream(), 1 << 20);
    }

    /** Overrides the default 5 s read timeout, for tests that expect a deliberate wait. */
    RawClient readTimeout(int millis) throws IOException {
        socket.setSoTimeout(millis);
        return this;
    }

    /** Writes a raw line with a bare LF, as nc does. */
    RawClient send(String rawLine) {
        out.print(rawLine + "\n");
        out.flush();
        return this;
    }

    /**
     * Writes bytes with <b>no</b> terminator, so the server's {@code readLine()} cannot
     * complete. Used to prove that dribbling data is not a way to hold a connection open
     * past a deadline.
     */
    RawClient sendPartial(String fragment) {
        out.print(fragment);
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

    Message expectError(ErrorCode code) throws IOException, ProtocolException {
        Message m = expect(MessageType.ERROR);
        assertEquals(code, m.errorCode(), "unexpected error: " + m.body());
        return m;
    }

    void expectClosed() throws IOException, ProtocolException {
        try {
            assertNull(readRaw(), "expected the server to close the connection");
        } catch (SocketException e) {
            // A reset is also the connection ending, and from here it is indistinguishable
            // from a polite close. The server gets a reset instead of a clean FIN whenever
            // it closes a socket that still has unread bytes in its receive buffer — which
            // is exactly the situation in every "the server gave up on this client" test,
            // because those clients are mid-flood when they get cut off.
            //
            // Windows words it "An established connection was aborted by the software in
            // your host machine"; Linux says "Connection reset by peer".
        }
    }

    /**
     * Waits {@code millis} and fails if anything arrives. This is how Phase 6 proves the
     * privacy boundary: we check the third client's <b>raw stream</b>, not a UI.
     */
    void expectNothing(int millis) throws IOException {
        int previousTimeout = socket.getSoTimeout();
        socket.setSoTimeout(millis);
        try {
            String line = in.readLine();
            fail("expected nothing to arrive, but got: " + line);
        } catch (java.net.SocketTimeoutException expected) {
            // Good — silence is what we wanted.
        } catch (ProtocolException e) {
            fail("expected nothing to arrive, but got an unparseable line: " + e.getMessage());
        } finally {
            socket.setSoTimeout(previousTimeout);
        }
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
