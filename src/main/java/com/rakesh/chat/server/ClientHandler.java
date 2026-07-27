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
 * Handles one connected client. Each client gets:
 *
 * <ul>
 *   <li>a <b>reader thread</b> — this class's {@code run()}, reading lines from the socket;</li>
 *   <li>a <b>writer thread</b> — takes lines off the {@code outbox} queue and writes them;</li>
 *   <li>a {@link ConnectionState} saying where in the lifecycle this connection is.</li>
 * </ul>
 *
 * <p><b>Who touches what.</b> {@code in} and the rate limiter belong to the reader thread.
 * {@code out} belongs to the writer thread. Everything other threads can touch is either a
 * queue or marked {@code volatile}, and {@link #cleanup()} uses a compare-and-set so it
 * runs exactly once even if several threads reach it together.
 *
 * <p><b>Two ways a connection ends.</b>
 * <ul>
 *   <li>{@link #cleanup()} — the normal way. The reader falls out of its loop, and a
 *       "poison pill" is put on the <i>end</i> of the outbox, so anything already queued
 *       (like the ERROR line explaining the disconnect) still gets written first.</li>
 *   <li>{@link #kick(String)} — the forced way. Closes the socket immediately and throws
 *       the queued output away. Used when the client is clearly not reading, or when the
 *       server is shutting down. It never blocks the caller, because the caller is usually
 *       another client's thread in the middle of a broadcast.</li>
 * </ul>
 */
public class ClientHandler implements Runnable {

    /**
     * The "stop now" marker put on the outbox to tell the writer thread to finish.
     *
     * <p>{@code new String(...)} looks pointless but is not: it creates an object that is
     * only ever equal to itself by {@code ==}. If this were a plain literal, a client who
     * typed {@code __POISON__} could shut down their own writer thread.
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

    /** Reader thread only, so no synchronisation is needed. */
    private int rateViolations = 0;

    private final String remoteAddress;
    private final Instant connectedAt;
    private final long connectedAtNanos;

    private final AtomicBoolean cleanedUp = new AtomicBoolean(false);

    private volatile ConnectionState state = ConnectionState.CONNECTED;
    private volatile String nickname;

    /**
     * True once {@code JOINED} has been broadcast. Not the same as {@code state == ACTIVE},
     * which is already false by the time {@link #cleanup()} looks — cleanup needs to know
     * whether this client was ever announced, not what it is doing right now.
     */
    private volatile boolean announcedJoin = false;

    /**
     * Phase 6: who whispered to us most recently, so {@code REPLY} knows where to go.
     * Written by the <i>sender's</i> reader thread, read by ours, hence {@code volatile}.
     */
    private volatile String lastPmFrom;

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

        this.in = new BoundedLineReader(socket.getInputStream(), config.maxLineBytes);

        this.out = new PrintWriter(
                new BufferedWriter(
                        new OutputStreamWriter(
                                socket.getOutputStream(),
                                StandardCharsets.UTF_8)),
                true); // autoFlush on println()

        this.outbox = new LinkedBlockingQueue<>(config.outboxCapacity);
        this.writerThread = new Thread(this::writerLoop, "writer-pending");
        this.rateLimiter = new TokenBucket(config.rateBurst, config.rateWindowMillis);
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
            applyReadTimeout(config.handshakeTimeoutMillis);

            if (!handshake()) {
                return;
            }

            // Post-handshake the deadline becomes an idle cap, not a liveness probe: it
            // reaps half-open connections that TCP will never report (the unplugged-cable
            // case), but it cannot distinguish those from a user who is simply reading.
            // Hence 15 minutes rather than seconds. Phase 9's PING/PONG is what makes a
            // short idle timeout safe, because then silence is genuinely evidence.
            applyReadTimeout(config.idleTimeoutMillis);

            readLoop();

        } catch (SocketTimeoutException e) {
            // Only reachable post-handshake; the handshake catches its own.
            closeReason = "idle timeout";
            send(Message.error(ErrorCode.TIMEOUT,
                    "no traffic for " + config.idleTimeoutMillis + " ms"));
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
                    "no HELLO within " + config.handshakeTimeoutMillis + " ms",
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

        send(Message.welcome(requested, config.serverName));
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
     * What to do with the line just read. There are three answers, not two.
     *
     * <p>This used to be a {@code boolean} and it caused a real bug (LEARNING-LOG.md, B4):
     * {@code false} meant "disconnect", so the "drop this line" case had nowhere to go and
     * returned {@code true}, which the caller read as "go ahead and process it". Every
     * rate-limited message got an ERROR <i>and was broadcast anyway</i>.
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
                    "slow down: at most " + config.rateBurst
                            + " messages per " + config.rateWindowMillis + " ms"));
            ServerLog.warn("RATE_LIMITED", who(), null);
        }

        if (rateViolations >= config.rateViolationsBeforeKick) {
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

            // Phase 6. Note there is no call to registry.broadcast() anywhere below —
            // that is the privacy boundary, and it is a one-line thing to check.
            case PM -> sendPrivateMessage(m.target(), m.body());

            case REPLY -> {
                String target = lastPmFrom;
                if (target == null) {
                    send(Message.error(ErrorCode.NO_SUCH_USER,
                            "nobody has sent you a private message yet"));
                } else {
                    sendPrivateMessage(target, m.body());
                }
            }

            default -> throw new ProtocolException(ErrorCode.MALFORMED,
                    "unhandled verb: " + m.type());
        }
        return true;
    }

    // ------------------------------------------------------------------ private messages

    /**
     * Phase 6: deliver one private message from this client to {@code target}.
     *
     * <p>The receiver gets {@code WHISPER <fromNickname> <timestamp> <text>} and the sender
     * gets {@code SENT <targetNickname> <timestamp> <text>} as a confirmation. Nobody else
     * sees anything at all.
     *
     * <p><b>The race to think about.</b> Between {@code registry.find()} returning a handler
     * and us putting the message on that handler's outbox, the target can disconnect. This
     * is called a TOCTOU (time-of-check to time-of-use) window and you cannot close it — by
     * the time you have the answer, it may already be out of date. So instead of trying to
     * prevent it we make the failure honest: if the target is already closing, or its outbox
     * refuses the message, we tell the sender {@code NO_SUCH_USER}, which is exactly what the
     * target is about to become.
     *
     * <p><b>Messaging yourself works.</b> There is deliberately no special case for it: you
     * get the WHISPER and the SENT confirmation, which is precisely what the routing rule
     * says should happen. Handy for testing, and one less branch to get wrong.
     */
    private void sendPrivateMessage(String target, String text) {
        ClientHandler receiver = registry.find(target).orElse(null);

        if (receiver == null) {
            send(Message.error(ErrorCode.NO_SUCH_USER, "no such user: " + target));
            return;
        }

        // send() returns false when the receiver's outbox is full, i.e. they have stopped
        // reading. Either way the message is not going to arrive, so say so rather than
        // pretending it was delivered.
        if (receiver.state == ConnectionState.CLOSING
                || !receiver.send(Message.whisper(nickname, text))) {
            send(Message.error(ErrorCode.NO_SUCH_USER, "no such user: " + target));
            return;
        }

        // Remember us as the receiver's last whisperer, so their REPLY comes back here.
        receiver.lastPmFrom = nickname;

        // Confirm to the sender, using the receiver's real spelling of their nickname —
        // "PM ALICE hi" should confirm as "SENT alice ...".
        send(Message.sent(receiver.getNickname(), text));

        // Log that a whisper happened, but never the text. A server admin does not need to
        // read private messages to debug routing.
        ServerLog.event("WHISPER", nickname, "-> " + receiver.getNickname());
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
     * End this connection from another thread, right now, throwing away queued output.
     *
     * <p>Never blocks. Closing the socket wakes the reader thread, which then runs
     * {@link #cleanup()} itself. That matters because the caller is usually another
     * client's thread in the middle of a broadcast, and it must not be made to wait.
     */
    public void kick(String reason) {
        // Only claim the reason if nothing else has already set one, otherwise a shutdown
        // sweep arriving while a client is already leaving would relabel a normal
        // DISCONNECT as a KICKED — and "who ended it" is what the log exists to answer.
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

    /** How long this connection has been alive. Uses nanoTime for the same reason
     *  {@link TokenBucket} does: a duration from the wall clock can come out negative. */
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
            // setSoTimeout affects read() ONLY. connect() takes its own timeout argument,
            // and write() has no timeout at all in normal java.net — writing to a client
            // who has stopped reading blocks until their buffer drains or the connection
            // dies. That is exactly why output goes through the outbox queue.
            socket.setSoTimeout(millis);
        } catch (SocketException e) {
            // Not fatal: without the timeout the connection just behaves as it did before.
            ServerLog.warn("NO_SO_TIMEOUT", who(), e.getMessage());
        }
    }

    private String who() {
        String nick = nickname;
        return (nick != null) ? nick : remoteAddress;
    }
}
