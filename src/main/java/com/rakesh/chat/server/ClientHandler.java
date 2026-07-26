package com.rakesh.chat.server;

import com.rakesh.chat.common.BoundedLineReader;
import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;
import com.rakesh.chat.common.ProtocolException;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connected client: a reader thread (this {@code run()}), a writer thread draining a
 * bounded outbox, and one {@link ConnectionState}.
 *
 * <h2>Thread ownership — the map of who may touch what</h2>
 *
 * <table border="1">
 *   <caption>Fields by owning thread</caption>
 *   <tr><th>Owner</th><th>State</th></tr>
 *   <tr><td>reader thread only</td>
 *       <td>{@code in}, {@code rateLimiter}, {@code rateViolations}</td></tr>
 *   <tr><td>writer thread only</td><td>{@code out}</td></tr>
 *   <tr><td>any thread</td>
 *       <td>{@code outbox} (a {@code BlockingQueue}), {@code state}, {@code nickname},
 *           {@code closeReason} (all {@code volatile}), {@code cleanedUp} (a CAS)</td></tr>
 * </table>
 *
 * <p>The invariant that makes this safe is that no field is written by two threads without
 * either a queue, a {@code volatile}, or a compare-and-set standing between them, and that
 * {@link #cleanup()} — the one method every exit path funnels through — is guarded by a
 * single CAS so it runs exactly once no matter how many threads race into it.
 *
 * <h2>Two ways a connection ends, and why they are not the same method</h2>
 *
 * <ul>
 *   <li><b>Self-initiated</b> ({@code QUIT}, EOF, {@code TOO_LONG}, a failed handshake,
 *       rate abuse): the reader falls out of its loop and {@code finally} runs
 *       {@link #cleanup()}, which queues a poison pill <i>behind</i> whatever is already
 *       in the outbox. The writer therefore delivers the diagnostic {@code ERROR} line
 *       before the socket closes. This is the Phase 4 B1/B2 lesson, preserved.</li>
 *   <li><b>Externally forced</b> ({@link #kick}): another thread closes the socket
 *       immediately and discards the outbox. Used when the client is provably not reading
 *       (outbox overflow) or when the server is going down. It must not block the caller,
 *       because the caller is usually a <i>different</i> client's reader thread mid-broadcast.</li>
 * </ul>
 *
 * <p>Collapsing these into one method is the tempting simplification and it is wrong in
 * both directions: a graceful close called from a broadcaster stalls the broadcast for up
 * to a second per slow client, and a hard close on the {@code NICK_TAKEN} path throws away
 * the very message that explains the disconnect.
 */
public class ClientHandler implements Runnable {

    /**
     * Sentinel compared by <b>identity</b>, so a client who literally types
     * {@code __POISON__} cannot shut down their own writer. {@code new String(...)} is
     * deliberate and must not be "simplified" to a literal — literals are interned, and
     * an interned sentinel is forgeable.
     */
    private static final String POISON = new String("__POISON__");

    private final Socket socket;
    private final ChatServer server;
    private final ClientRegistry registry;
    private final ServerConfig config;
    private final ConnectionLog connectionLog;

    private final BoundedLineReader in;
    private final PrintWriter out;

    private final BlockingQueue<String> outbox;
    private final Thread writerThread;

    /** Charged for every line read, valid or not — see {@link #readLoop()}. */
    private final TokenBucket rateLimiter;

    /** Reader thread only; no synchronisation needed or wanted. */
    private int rateViolations = 0;

    private final String remoteAddress;
    private final Instant connectedAt;
    private final long connectedAtNanos;

    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    private volatile ConnectionState state = ConnectionState.CONNECTED;
    private volatile String nickname;

    /**
     * True once {@code JOINED} has been broadcast. Distinct from {@code state == ACTIVE},
     * which is false again by the time {@link #cleanup()} inspects it — cleanup needs to
     * know whether this client was ever <i>announced</i>, not what it is doing now.
     */
    private volatile boolean announcedJoin = false;

    /** Why the connection ended, for the connection log. First writer wins. */
    private volatile String closeReason;

    /** True when the server ended the connection rather than the client. */
    private volatile boolean kicked = false;

    public ClientHandler(Socket socket, ChatServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.registry = server.getRegistry();
        this.config = server.getConfig();
        this.connectionLog = server.getConnectionLog();

        this.remoteAddress = (socket.getRemoteSocketAddress() == null)
                ? "-" : socket.getRemoteSocketAddress().toString();
        // Captured on the acceptor thread, at accept time — not when run() finally gets a
        // pool slot. Under load those differ, and the log should record when the client
        // arrived, not when we got round to it.
        this.connectedAt = Instant.now();
        this.connectedAtNanos = System.nanoTime();

        this.in = new BoundedLineReader(socket.getInputStream(), config.maxLineBytes());

        this.out = new PrintWriter(
                new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8)),
                true); // autoFlush on println()

        this.outbox = new LinkedBlockingQueue<>(config.outboxCapacity());
        this.writerThread = new Thread(this::writerLoop, "writer-pending");
        this.rateLimiter = new TokenBucket(config.rateBurst(), config.rateWindowMillis());
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void run() {
        Thread.currentThread().setName("reader-" + remoteAddress);
        connectionLog.record(ConnectionLog.Event.CONNECT, null, remoteAddress, 0, null);
        ServerLog.event("CONNECT", remoteAddress, null);

        try {
            // Started before the handshake so that a rejection's ERROR line has a thread
            // to carry it. Phase 4 bug B1.
            writerThread.start();

            // The handshake deadline. An unauthenticated peer holds a pooled thread and a
            // file descriptor; ten seconds is generous for a machine and eternal for an
            // attacker opening connections in a loop.
            applyReadTimeout(config.handshakeTimeoutMillis());

            if (!handshake()) {
                return;
            }

            // Post-handshake the deadline becomes an idle cap, not a liveness probe: it
            // reaps half-open connections that TCP will never report (the unplugged-cable
            // case), but it cannot distinguish those from a user who is simply reading.
            // Hence 15 minutes rather than seconds. Phase 9's PING/PONG is what makes a
            // short idle timeout safe, because then silence is genuinely evidence.
            applyReadTimeout(config.idleTimeoutMillis());

            readLoop();

        } catch (SocketTimeoutException e) {
            // Only reachable post-handshake; the handshake catches its own.
            closeReason = "idle timeout";
            send(Message.error(ErrorCode.TIMEOUT,
                    "no traffic for " + config.idleTimeoutMillis() + " ms"));
            ServerLog.warn("IDLE_TIMEOUT", who(), null);

        } catch (SocketException e) {
            // Reset, or our own kick() closing the socket underneath the read.
            if (closeReason == null) {
                closeReason = "connection reset";
            }
            ServerLog.event("RESET", who(), e.getMessage());

        } catch (IOException e) {
            if (closeReason == null) {
                closeReason = "io error: " + e.getMessage();
            }
            ServerLog.event("IO_ERROR", who(), e.getMessage());

        } catch (Throwable t) {
            // A bug in our code, not in the peer's input. Loud, and contained: one
            // handler dying must never reach the accept loop.
            closeReason = "internal error";
            ServerLog.error("BUG", who(), String.valueOf(t));
            t.printStackTrace();

        } finally {
            cleanup();
        }
    }

    /**
     * Reads and validates the first line.
     *
     * @return {@code true} if the client is registered and announced; {@code false} if the
     *         connection should end (the explanatory {@code ERROR} is already queued)
     */
    private boolean handshake() throws IOException {
        String line;
        try {
            line = in.readLine();
        } catch (SocketTimeoutException e) {
            // The deadline. Note the partial line already buffered is discarded — we are
            // disconnecting, so there is nothing to resynchronise with.
            return handshakeFailed(ErrorCode.TIMEOUT,
                    "no HELLO within " + config.handshakeTimeoutMillis() + " ms",
                    "handshake deadline");
        } catch (ProtocolException e) {
            return handshakeFailed(e.code(), e.getMessage(), "over-length handshake");
        }

        if (line == null) {
            // Connected and hung up without a word. Nothing to report to nobody.
            closeReason = "closed before handshake";
            return false;
        }

        // Charged like any other line, so the handshake cannot be used as a free
        // reconnect-and-spam channel.
        rateLimiter.tryConsume();

        Message hello;
        try {
            hello = Message.parse(line);
        } catch (ProtocolException e) {
            return handshakeFailed(e.code(), e.getMessage(), "unparseable handshake");
        }

        if (!state.permits(hello.type())) {
            return handshakeFailed(ErrorCode.MALFORMED,
                    "expected HELLO as the first message, got " + hello.type(),
                    "wrong first verb: " + hello.type());
        }

        String requested = hello.sender();

        // Server policy, layered on top of the wire syntax Message.parse already enforced.
        String problem = NicknamePolicy.problem(requested);
        if (problem != null) {
            return handshakeFailed(ErrorCode.MALFORMED, "nickname " + problem,
                    "rejected nickname: " + requested);
        }

        // Set before register() so there is no window in which this handler is reachable
        // from the registry while still reporting a null nickname. Unregistration is
        // conditional (remove(key, this)), so a failed registration below cannot evict
        // whoever legitimately holds the name.
        this.nickname = requested;

        if (!registry.register(requested, this)) {
            return handshakeFailed(ErrorCode.NICK_TAKEN,
                    "nickname already in use: " + requested,
                    "nickname taken: " + requested);
        }

        transitionTo(ConnectionState.NAMED);

        Thread.currentThread().setName("reader-" + requested);
        writerThread.setName("writer-" + requested);

        send(Message.welcome(requested, config.serverName()));
        registry.broadcast(Message.joined(requested), this);
        announcedJoin = true;

        transitionTo(ConnectionState.ACTIVE);

        connectionLog.record(ConnectionLog.Event.HANDSHAKE_OK, requested, remoteAddress,
                elapsedMillis(), null);
        ServerLog.event("JOINED", requested, registry.size() + " online");
        return true;
    }

    /** Queues the diagnostic, records why, and tells {@link #handshake()} to give up. */
    private boolean handshakeFailed(ErrorCode code, String text, String reason) {
        closeReason = reason;
        send(Message.error(code, text));
        ServerLog.warn("HANDSHAKE_FAIL", who(), code + ": " + text);
        return false;
    }

    private void readLoop() throws IOException {
        while (true) {
            String line;
            try {
                line = in.readLine();
            } catch (ProtocolException e) {
                // Only TOO_LONG can reach here. Framing sync is gone, so there is nothing
                // safe to resume from: report and disconnect.
                closeReason = "line too long";
                send(Message.error(e.code(), e.getMessage()));
                ServerLog.warn("TOO_LONG", who(), "disconnecting");
                return;
            }

            if (line == null) {
                closeReason = "peer closed";
                return; // clean EOF
            }

            // Charged BEFORE parsing, so a garbage line costs the same as a valid one.
            // This is what closes the amplification hole PROTOCOL.md §3 deferred to this
            // phase: previously "X\n" (2 bytes in) bought ~40 bytes of ERROR out, for free
            // and without limit.
            RateDecision decision = rateCheck();
            if (decision == RateDecision.DISCONNECT) {
                return;
            }
            if (decision == RateDecision.DROP) {
                continue; // the line is discarded unparsed
            }

            try {
                if (!dispatch(Message.parse(line))) {
                    return; // QUIT
                }
            } catch (ProtocolException e) {
                // A bad message kills the message, not the connection.
                send(Message.error(e.code(), e.getMessage()));
            }
        }
    }

    /**
     * What to do with the line just read.
     *
     * <p>Three outcomes, not two. An earlier version of this returned a {@code boolean}
     * and it was wrong in a way that a passing test still missed: {@code false} meant
     * "disconnect", so the drop case had to return {@code true} — which the caller read as
     * "process this line". Every rate-limited message was answered with
     * {@code ERROR RATE_LIMITED} <i>and then broadcast anyway</i>. See LEARNING-LOG.md, B4.
     */
    private enum RateDecision {
        /** Within the limit: parse and dispatch. */
        ALLOW,
        /** Over the limit: discard the line, keep the connection. */
        DROP,
        /** Persistently over the limit: end the connection. */
        DISCONNECT
    }

    private RateDecision rateCheck() {
        if (rateLimiter.tryConsume()) {
            rateViolations = 0;
            return RateDecision.ALLOW;
        }

        rateViolations++;

        // Exactly one ERROR per over-limit episode. Replying to every refused line would
        // make the rate limiter itself the amplifier it was added to prevent — the peer
        // sends 12 bytes, we send 70, and the limit has changed nothing about our egress.
        if (rateViolations == 1) {
            send(Message.error(ErrorCode.RATE_LIMITED,
                    "slow down: at most " + config.rateBurst()
                            + " messages per " + config.rateWindowMillis() + " ms"));
            ServerLog.warn("RATE_LIMITED", who(), null);
        }

        if (rateViolations >= config.rateViolationsBeforeKick()) {
            // Ignoring the limit long enough is itself the signal: a chat client backs off,
            // and something that does not is not a chat client.
            kicked = true;
            closeReason = "rate limit abuse (" + rateViolations + " violations)";
            ServerLog.warn("KICKED", who(), closeReason);
            return RateDecision.DISCONNECT;
        }

        return RateDecision.DROP;
    }

    private boolean dispatch(Message m) throws ProtocolException {
        // parse() is direction-agnostic on purpose; state.permits() is where both
        // direction and lifecycle legality are decided, in one place.
        if (!state.permits(m.type())) {
            throw new ProtocolException(ErrorCode.MALFORMED,
                    m.type() + " is not allowed in state " + state);
        }

        switch (m.type()) {
            case MSG -> registry.broadcast(Message.chat(nickname, m.body()), this);

            case LIST -> send(Message.users(registry.onlineNicknames()));

            case QUIT -> {
                closeReason = "QUIT";
                ServerLog.event("QUIT", nickname, null);
                return false;
            }

            case PM -> throw new ProtocolException(ErrorCode.MALFORMED,
                    "PM is not implemented yet (Phase 6)");

            default -> throw new ProtocolException(ErrorCode.MALFORMED,
                    "unhandled verb: " + m.type());
        }
        return true;
    }

    // ------------------------------------------------------------------ output

    public boolean send(Message message) {
        return sendSerialized(message.serialize());
    }

    /** Non-blocking. {@code false} means the outbox is full — the caller decides what that means. */
    boolean sendSerialized(String wireLine) {
        return outbox.offer(wireLine);
    }

    private void writerLoop() {
        try {
            while (true) {
                String line = outbox.take();
                if (line == POISON) { // identity, not equality
                    break;
                }
                out.println(line);
                if (out.checkError()) {
                    // PrintWriter swallows IOException; checkError is the only way to
                    // learn the socket died under us.
                    if (closeReason == null) {
                        closeReason = "write failed";
                    }
                    ServerLog.event("WRITE_FAIL", who(), null);
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // If the reader is still blocked in read(), this is the thread that ends the
            // connection. cleanup() is idempotent, so both racing here is fine.
            cleanup();
        }
    }

    // ------------------------------------------------------------------ teardown

    /**
     * End this connection from another thread, immediately, discarding queued output.
     *
     * <p>Never blocks: closing the socket unblocks the reader, which then runs
     * {@link #cleanup()} on its own thread. That matters because the usual caller is a
     * <i>different</i> client's reader thread, part-way through a broadcast — the Phase 3
     * rule that a broadcaster never waits on a consumer applies to disconnecting one just
     * as much as to writing to one.
     */
    public void kick(String reason) {
        // Claim responsibility only if nothing else has already decided why this
        // connection is ending. Without this guard, a shutdown sweep that arrives while a
        // client is already tearing itself down would relabel an ordinary DISCONNECT as a
        // KICKED — and "who ended it" is the one question the connection log exists to
        // answer, so a log that guesses wrong under load is worse than no log.
        if (closeReason == null) {
            closeReason = reason;
            kicked = true;
        }
        try {
            socket.close(); // unblocks a reader parked in read()
        } catch (IOException ignored) {
            // already closed
        }
        writerThread.interrupt(); // unblocks a writer parked in take()
    }

    /**
     * The single exit path. Idempotent via CAS, and reachable from the reader thread, the
     * writer thread, and {@link ChatServer#shutdown()}.
     */
    public void cleanup() {
        if (!cleanedUp.compareAndSet(false, true)) {
            return;
        }
        state = ConnectionState.CLOSING;

        String nick = nickname;
        long durationMillis = elapsedMillis();

        // 1. Stop being reachable, before announcing departure — otherwise the LEFT
        //    broadcast would include this client in its own iteration.
        if (nick != null) {
            registry.unregister(nick, this);
        }

        // 2. Announce, but only if there was ever a join to undo. PROTOCOL.md §4.3: LEFT
        //    is emitted here and nowhere else, so QUIT, EOF, reset, Ctrl-C, outbox
        //    overflow and shutdown all produce it. Phase 4 bug B3.
        if (announcedJoin && nick != null) {
            registry.broadcast(Message.left(nick), this);
            ServerLog.event("LEFT", nick, registry.size() + " online");
        }

        // 3. Audit. KICKED beats DISCONNECT because "who ended it" is the question the log
        //    exists to answer; HANDSHAKE_FAIL covers everything that died before joining.
        ConnectionLog.Event event = kicked ? ConnectionLog.Event.KICKED
                : announcedJoin ? ConnectionLog.Event.DISCONNECT
                : ConnectionLog.Event.HANDSHAKE_FAIL;
        connectionLog.record(event, nick, remoteAddress, durationMillis, closeReason);

        // 4. Poison pill rather than interrupt: it sits BEHIND everything already queued,
        //    so a diagnostic ERROR queued microseconds ago still reaches the wire. An
        //    interrupt would discard it — that was Phase 4 bug B2.
        if (!outbox.offer(POISON)) {
            // Full outbox is precisely the case where the backlog is what we have decided
            // to throw away, so here the interrupt is correct.
            writerThread.interrupt();
        }

        // 5. Give the writer a bounded moment to drain. Never join from the writer itself.
        if (Thread.currentThread() != writerThread) {
            try {
                writerThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            writerThread.interrupt(); // no-op if it already exited
        }

        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed, or never opened
        }

        // 6. Release the connection slot.
        server.handlerClosed(this);
    }

    // ------------------------------------------------------------------ accessors

    public String getNickname() {
        return nickname;
    }

    public ConnectionState getState() {
        return state;
    }

    public String getRemoteAddress() {
        return remoteAddress;
    }

    public Instant getConnectedAt() {
        return connectedAt;
    }

    /**
     * Elapsed since accept, from the monotonic clock — the same reasoning as
     * {@link TokenBucket}: a duration computed from wall time can come out negative.
     */
    private long elapsedMillis() {
        return (System.nanoTime() - connectedAtNanos) / 1_000_000L;
    }

    private void transitionTo(ConnectionState next) {
        ConnectionState current = state;
        if (!current.canTransitionTo(next)) {
            // Our own bug, never the peer's: fail loudly.
            throw new IllegalStateException("illegal transition " + current + " -> " + next);
        }
        state = next;
    }

    private void applyReadTimeout(int millis) {
        try {
            // SO_TIMEOUT governs read() ONLY. connect() has its own timeout argument, and
            // write() has none at all in blocking java.net — a write to a peer that has
            // stopped reading blocks until the TCP send buffer drains or the connection
            // dies. That asymmetry is exactly why output goes through an outbox instead of
            // being written on the broadcaster's thread.
            socket.setSoTimeout(millis);
        } catch (SocketException e) {
            // Non-fatal: without a timeout the connection simply behaves as it did in
            // Phase 4. Worth a line, not worth dropping a working client.
            ServerLog.warn("NO_SO_TIMEOUT", who(), e.getMessage());
        }
    }

    private String who() {
        String nick = nickname;
        return (nick != null) ? nick : remoteAddress;
    }
}
