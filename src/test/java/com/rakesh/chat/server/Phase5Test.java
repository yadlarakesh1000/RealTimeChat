package com.rakesh.chat.server;

import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.MessageType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.rakesh.chat.server.TestSupport.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The Phase 5 checkpoint, automated: handshake deadline, nickname policy, rate limiting,
 * and a connection log that records a complete session.
 *
 * <p>Each test starts its own server, because the interesting behaviours need different
 * configuration — a 10-second handshake deadline is correct in production and useless in
 * a test, so the deadline is a parameter and the test picks 300&nbsp;ms. That is the
 * entire justification for {@link ServerConfig} existing this early.
 */
@Timeout(60)
class Phase5Test {

    @TempDir
    Path tmp;

    private ChatServer server;
    private final List<RawClient> clients = new ArrayList<>();

    /** Starts a server on an ephemeral port with a per-test log file. */
    private void startServer(ServerConfig config) throws IOException {
        config.port = 0;                                            // any free port
        config.connectionLogPath = tmp.resolve("connections.log");  // don't touch logs/
        server = new ChatServer(config);
        Thread acceptor = new Thread(server::start, "test-acceptor");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    private void startServer() throws IOException {
        startServer(new ServerConfig());
    }

    private RawClient connect() throws IOException {
        return track(new RawClient(server.getPort()));
    }

    /** A config with only the rate-limit numbers changed. */
    private static ServerConfig rateLimit(int burst, int windowMillis, int violationsBeforeKick) {
        ServerConfig config = new ServerConfig();
        config.rateBurst = burst;
        config.rateWindowMillis = windowMillis;
        config.rateViolationsBeforeKick = violationsBeforeKick;
        return config;
    }

    private RawClient track(RawClient c) {
        clients.add(c);
        return c;
    }

    @AfterEach
    void stopServer() {
        clients.forEach(RawClient::close);
        clients.clear();
        if (server != null) {
            server.shutdown();
        }
    }

    // ------------------------------------------------------------ log helpers

    /** Log records split into trimmed fields, header excluded. Call after shutdown(). */
    private List<String[]> logRecords() throws IOException {
        Path path = tmp.resolve("connections.log");
        if (Files.notExists(path)) {
            return List.of();
        }
        return Files.readAllLines(path, StandardCharsets.UTF_8).stream()
                .filter(line -> !line.startsWith("#"))
                .map(line -> {
                    String[] fields = line.split("\\|");
                    for (int i = 0; i < fields.length; i++) {
                        fields[i] = fields[i].trim();
                    }
                    return fields;
                })
                .toList();
    }

    private List<String> allEvents() throws IOException {
        return logRecords().stream().map(f -> f[1]).toList();
    }

    private List<String> eventsFor(String nickname) throws IOException {
        return logRecords().stream()
                .filter(f -> f[2].equals(nickname))
                .map(f -> f[1])
                .toList();
    }

    private String render(List<String[]> records) {
        StringBuilder sb = new StringBuilder("\n");
        for (String[] r : records) {
            sb.append(String.join(" | ", r)).append('\n');
        }
        return sb.toString();
    }

    // =================================================================== deadline

    @Nested
    @DisplayName("handshake deadline")
    class HandshakeDeadline {

        @Test
        @DisplayName("silence past the deadline -> ERROR TIMEOUT, then close")
        void silentClientIsReaped() throws Exception {
            ServerConfig config = new ServerConfig();
            config.handshakeTimeoutMillis = 300;
            startServer(config);

            RawClient lurker = connect();
            // No HELLO. The connection costs a pooled thread and a file descriptor, and an
            // unauthenticated peer has not earned either indefinitely. Without this,
            // holding every thread in the pool costs an attacker one TCP connection each
            // and no bytes at all.
            Message err = lurker.expectError(ErrorCode.TIMEOUT);
            assertTrue(err.body().contains("HELLO"), err.body());

            lurker.expectClosed();
            await("the lurker to be released", () -> server.activeConnections() == 0);
        }

        @Test
        @DisplayName("dribbling bytes with no newline does not extend the deadline")
        void aPartialLineDoesNotResetTheDeadline() throws Exception {
            ServerConfig config = new ServerConfig();
            config.handshakeTimeoutMillis = 400;
            startServer(config);

            RawClient dripper = connect();
            for (int i = 0; i < 6; i++) {
                dripper.sendPartial("x");   // never a '\n', so readLine() never returns
                Thread.sleep(100);
            }

            // SO_TIMEOUT is an inactivity timer on read(), so a peer that sends one byte
            // every 100 ms keeps resetting it — which is exactly the slowloris shape. The
            // bound that actually holds here is the 4096-byte line cap: the connection
            // either completes a line, exceeds the cap, or stops sending and expires.
            dripper.readTimeout(3000);
            Message err = dripper.expectError(ErrorCode.TIMEOUT);
            assertTrue(err.body().contains("HELLO"), err.body());
            dripper.expectClosed();
        }

        @Test
        @DisplayName("a client that says HELLO in time is not reaped")
        void promptClientSurvivesTheDeadline() throws Exception {
            ServerConfig config = new ServerConfig();
            config.handshakeTimeoutMillis = 300;
            config.idleTimeoutMillis = 60_000;
            startServer(config);

            RawClient alice = connect().helloAs("alice");
            Thread.sleep(600); // well past the handshake deadline

            // Still usable: the deadline governed the handshake only, and was replaced by
            // the idle timeout the moment the handshake completed.
            alice.send("LIST");
            assertEquals(List.of("alice"), alice.expect(MessageType.USERS).userList());
        }

        @Test
        @DisplayName("an idle established connection is eventually reaped too")
        void idleTimeoutAppliesAfterTheHandshake() throws Exception {
            ServerConfig config = new ServerConfig();
            config.handshakeTimeoutMillis = 5000;
            config.idleTimeoutMillis = 300;
            startServer(config);

            RawClient alice = connect().helloAs("alice");

            Message err = alice.expectError(ErrorCode.TIMEOUT);
            assertTrue(err.body().contains("no traffic"), err.body());
            alice.expectClosed();
        }

        @Test
        @DisplayName("the deadline is audited as HANDSHAKE_FAIL, not DISCONNECT")
        void deadlineIsAudited() throws Exception {
            ServerConfig config = new ServerConfig();
            config.handshakeTimeoutMillis = 300;
            startServer(config);

            RawClient lurker = connect();
            lurker.expectError(ErrorCode.TIMEOUT);
            lurker.expectClosed();
            await("cleanup", () -> server.activeConnections() == 0);

            server.shutdown();

            assertEquals(List.of("CONNECT", "HANDSHAKE_FAIL"), allEvents(), render(logRecords()));
            assertTrue(logRecords().get(1)[5].contains("deadline"), render(logRecords()));
        }
    }

    // =============================================================== nickname policy

    @Nested
    @DisplayName("nickname policy")
    class Nicknames {

        @Test
        @DisplayName("too short -> ERROR MALFORMED, then close")
        void tooShortIsRejected() throws Exception {
            startServer();
            RawClient c = connect();
            c.send("HELLO 1 ab");

            Message err = c.expectError(ErrorCode.MALFORMED);
            assertTrue(err.body().contains("at least 3"), err.body());
            c.expectClosed();
        }

        @Test
        @DisplayName("illegal characters -> ERROR MALFORMED, then close")
        void illegalCharactersAreRejected() throws Exception {
            startServer();
            for (String bad : List.of("al-ice", "al.ice", "señor", "b@b")) {
                RawClient c = connect();
                c.send("HELLO 1 " + bad);
                Message err = c.expectError(ErrorCode.MALFORMED);
                assertTrue(err.body().contains("letters, digits and underscore"),
                        "for <" + bad + ">: " + err.body());
                c.expectClosed();
            }
        }

        @Test
        @DisplayName("reserved names cannot be claimed")
        void reservedNamesAreRejected() throws Exception {
            startServer();
            RawClient c = connect();
            c.send("HELLO 1 Server");

            assertTrue(c.expectError(ErrorCode.MALFORMED).body().contains("reserved"));
            c.expectClosed();
        }

        @Test
        @DisplayName("17 chars is refused by policy; 33 by the protocol — two different layers")
        void theTwoLengthLimitsAreBothEnforced() throws Exception {
            startServer();

            RawClient policyReject = connect();
            policyReject.send("HELLO 1 " + "a".repeat(17));
            assertTrue(policyReject.expectError(ErrorCode.MALFORMED).body().contains("at most 16"));

            RawClient syntaxReject = connect();
            syntaxReject.send("HELLO 1 " + "a".repeat(33));
            assertTrue(syntaxReject.expectError(ErrorCode.MALFORMED).body().contains("32"));
        }

        @Test
        @DisplayName("a rejected nickname is never reserved")
        void aRejectedNicknameLeavesNoTrace() throws Exception {
            startServer();

            RawClient c = connect();
            c.send("HELLO 1 ab");
            c.expectError(ErrorCode.MALFORMED);
            c.expectClosed();

            await("registry to settle", () -> server.getRegistry().size() == 0);

            connect().helloAs("abc");
            assertEquals(1, server.getRegistry().size());
        }

        /**
         * The Phase 5 checkpoint: <i>two clients try to take the same nickname
         * simultaneously — exactly one succeeds.</i>
         *
         * <p>Ten racers rather than two, released from a common latch: two threads rarely
         * interleave badly enough to expose a check-then-act bug. This is the end-to-end
         * counterpart of {@code ClientRegistryTest.testConcurrentRegistrationRace} — that
         * one races {@code register()} directly, this one races the whole handshake over
         * real sockets, which is where an ordering mistake between {@code putIfAbsent} and
         * the {@code nickname} assignment would surface.
         */
        @Test
        @DisplayName("CHECKPOINT: 10 clients race for one nickname, exactly one wins")
        void simultaneousNicknameClaimHasExactlyOneWinner() throws Exception {
            startServer();

            int racers = 10;
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            CountDownLatch ready = new CountDownLatch(racers);
            CountDownLatch go = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(racers);
            AtomicInteger welcomed = new AtomicInteger();
            AtomicInteger refused = new AtomicInteger();
            List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());

            // Held open until every racer has reported. Closing the winner's socket inside
            // the loop would unregister the nickname while other racers were still
            // handshaking, and a later racer would legitimately win it — the test would
            // then be measuring how fast sockets close, not whether registration is atomic.
            List<RawClient> racerSockets = Collections.synchronizedList(new ArrayList<>());

            for (int i = 0; i < racers; i++) {
                pool.execute(() -> {
                    try {
                        RawClient c = new RawClient(server.getPort());
                        racerSockets.add(c);
                        ready.countDown();
                        go.await();
                        c.send("HELLO 1 contested");
                        Message reply = c.read();
                        switch (reply.type()) {
                            case WELCOME -> welcomed.incrementAndGet();
                            case ERROR -> {
                                assertEquals(ErrorCode.NICK_TAKEN, reply.errorCode());
                                refused.incrementAndGet();
                            }
                            default -> fail("unexpected reply: " + reply);
                        }
                    } catch (Throwable t) {
                        failures.add(t);
                    } finally {
                        done.countDown();
                    }
                });
            }

            assertTrue(ready.await(15, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS));
            pool.shutdownNow();
            clients.addAll(racerSockets); // closed by the outer @AfterEach

            assertTrue(failures.isEmpty(), "racer failed: " + failures);
            assertEquals(1, welcomed.get(), "exactly one client must be welcomed");
            assertEquals(racers - 1, refused.get(), "every other client must get NICK_TAKEN");
            assertEquals(1, server.getRegistry().size());
        }
    }

    // ================================================================ rate limiting

    @Nested
    @DisplayName("rate limiting")
    class RateLimiting {

        /** Burst 5; a window long enough that no refill happens mid-test; kick at 50. */
        private ServerConfig limited() {
            return rateLimit(5, 60_000, 50);
        }

        @Test
        @DisplayName("the burst is honoured, then ERROR RATE_LIMITED")
        void burstThenRefusal() throws Exception {
            startServer(limited());

            RawClient alice = connect().helloAs("alice");   // charge 1 of 5
            RawClient bob = connect().helloAs("bob");
            alice.expect(MessageType.JOINED);

            for (int i = 0; i < 4; i++) {                   // charges 2..5
                alice.send("MSG line " + i);
                assertEquals("line " + i, bob.expect(MessageType.CHAT).body());
            }

            alice.send("MSG one too many");
            Message err = alice.expectError(ErrorCode.RATE_LIMITED);
            assertTrue(err.body().contains("5"), err.body());
        }

        @Test
        @DisplayName("a refused message is dropped, not delivered late")
        void refusedMessagesNeverArrive() throws Exception {
            startServer(limited());

            RawClient alice = connect().helloAs("alice");
            RawClient bob = connect().helloAs("bob");
            alice.expect(MessageType.JOINED);

            for (int i = 0; i < 4; i++) {
                alice.send("MSG allowed" + i);
            }
            alice.send("MSG SHOULD_NOT_ARRIVE");
            alice.expectError(ErrorCode.RATE_LIMITED);

            for (int i = 0; i < 4; i++) {
                assertEquals("allowed" + i, bob.expect(MessageType.CHAT).body());
            }

            // Prove nothing is queued behind those four without sleeping for it: bob asks
            // a question, and the answer must be the very next line he sees.
            bob.send("LIST");
            assertEquals(MessageType.USERS, bob.read().type(),
                    "a rate-limited message must be discarded, not buffered");
        }

        @Test
        @DisplayName("exactly ONE error per episode — the limiter must not become the amplifier")
        void theLimiterDoesNotAmplify() throws Exception {
            startServer(limited());

            RawClient alice = connect().helloAs("alice");
            for (int i = 0; i < 4; i++) {
                alice.send("MSG fill" + i);          // spends the rest of the burst
            }

            // 30 refused lines. A naive limiter answers each one, turning ~12 bytes in into
            // ~70 bytes out, thirty times over — which is the amplification hole
            // PROTOCOL.md §3 deferred to this phase, reintroduced by its own fix.
            for (int i = 0; i < 30; i++) {
                alice.send("MSG refused" + i);
            }
            alice.expectError(ErrorCode.RATE_LIMITED);
            alice.send("LIST");                      // also refused, silently

            Thread.sleep(300);                       // give a chatty server time to be chatty
            alice.readTimeout(400);
            assertThrows(SocketTimeoutException.class, alice::readRaw,
                    "the server must answer an over-limit episode once, not once per line");
        }

        @Test
        @DisplayName("garbage costs the same as a valid message")
        void malformedLinesAreChargedToo() throws Exception {
            startServer(limited());

            RawClient alice = connect().helloAs("alice"); // 1 of 5
            for (int i = 0; i < 4; i++) {                 // 2..5, each answered with MALFORMED
                alice.send("GARBAGE" + i);
                alice.expectError(ErrorCode.MALFORMED);
            }

            // Sixth line: the bucket is empty, so this is refused BEFORE it is parsed.
            // If bad lines were free, an attacker would simply send only bad lines.
            alice.send("GARBAGE_AGAIN");
            alice.expectError(ErrorCode.RATE_LIMITED);
        }

        @Test
        @DisplayName("the allowance comes back on its own")
        void theBucketRefills() throws Exception {
            // 3 per 2 s — a wide window so the three sends below cannot be spaced far
            // enough apart by a slow machine to earn a refill mid-test.
            startServer(rateLimit(3, 2000, 50));

            RawClient alice = connect().helloAs("alice");   // 1 of 3
            RawClient bob = connect().helloAs("bob");
            alice.expect(MessageType.JOINED);

            alice.send("MSG a");
            assertEquals("a", bob.expect(MessageType.CHAT).body());
            alice.send("MSG b");
            assertEquals("b", bob.expect(MessageType.CHAT).body());
            alice.send("MSG c");
            alice.expectError(ErrorCode.RATE_LIMITED);

            Thread.sleep(2200); // one full window

            alice.send("MSG after refill");
            assertEquals("after refill", bob.expect(MessageType.CHAT).body(),
                    "the client must recover on its own, without reconnecting");
        }

        @Test
        @DisplayName("persistent abuse is kicked and audited as KICKED, with a reason")
        void persistentAbuseIsDisconnected() throws Exception {
            startServer(rateLimit(3, 60_000, 5));

            RawClient flooder = connect().helloAs("flooder");

            // Phase 10 fix. This used to fire all 40 lines and only then read, and it failed
            // roughly one run in three on Windows with "connection aborted".
            //
            // The reason is worth knowing. Closing a socket that still has UNREAD data
            // sitting in its receive buffer makes TCP send RST rather than FIN — and an RST
            // also throws away whatever that socket had already queued in the other
            // direction. So the server would kick the flooder after ~8 lines, close on the
            // remaining ~32 unread ones, and the ERROR it had politely sent first died with
            // them. The test was racing the server's own teardown.
            //
            // So: provoke the refusal, READ it, and only then keep flooding.
            for (int i = 0; i < 5; i++) {
                flooder.send("MSG flood " + i);
            }
            flooder.expectError(ErrorCode.RATE_LIMITED);

            for (int i = 0; i < 40; i++) {
                flooder.send("MSG more " + i);
            }
            flooder.expectClosed();

            await("cleanup", () -> server.getRegistry().size() == 0);
            server.shutdown();

            assertEquals(List.of("HANDSHAKE_OK", "KICKED"), eventsFor("flooder"),
                    render(logRecords()));
            assertTrue(logRecords().stream()
                            .anyMatch(f -> f[1].equals("KICKED") && f[5].contains("rate limit")),
                    "the log must say why: " + render(logRecords()));
        }

        @Test
        @DisplayName("one client's flood does not limit anyone else")
        void limitsArePerConnection() throws Exception {
            startServer(rateLimit(3, 60_000, 50));

            RawClient flooder = connect().helloAs("flooder");
            RawClient bob = connect().helloAs("bob");
            RawClient carol = connect().helloAs("carol");
            flooder.expect(MessageType.JOINED); // bob
            flooder.expect(MessageType.JOINED); // carol
            bob.expect(MessageType.JOINED);     // carol

            for (int i = 0; i < 20; i++) {
                flooder.send("MSG flood " + i);
            }
            flooder.expectError(ErrorCode.RATE_LIMITED);

            // The flooder's HELLO spent one token, so exactly two of its floods got through.
            for (int i = 0; i < 2; i++) {
                assertEquals("flood " + i, bob.expect(MessageType.CHAT).body());
                assertEquals("flood " + i, carol.expect(MessageType.CHAT).body());
            }

            // bob still has his own bucket, untouched by his neighbour's behaviour.
            bob.send("MSG unaffected");
            assertEquals("unaffected", carol.expect(MessageType.CHAT).body());
        }
    }

    // ================================================================ connection log

    @Nested
    @DisplayName("connection log")
    class Log {

        /**
         * The other half of the Phase 5 checkpoint: <i>the connection log shows a
         * complete, correct record of a session from connect to disconnect including
         * duration.</i>
         */
        @Test
        @DisplayName("CHECKPOINT: a full session is CONNECT -> HANDSHAKE_OK -> DISCONNECT")
        void aCompleteSessionIsRecorded() throws Exception {
            startServer();

            RawClient alice = connect().helloAs("alice");
            Thread.sleep(250); // so the duration is measurably non-zero
            alice.send("QUIT");
            alice.expectClosed();

            await("cleanup", () -> server.getRegistry().size() == 0);
            server.shutdown(); // drains and closes the log

            List<String[]> records = logRecords();
            assertEquals(3, records.size(), () -> "expected 3 records: " + render(records));

            String[] connect = records.get(0);
            String[] handshake = records.get(1);
            String[] disconnect = records.get(2);

            assertEquals("CONNECT", connect[1]);
            assertEquals("-", connect[2], "no nickname is known yet at connect time");
            assertEquals("0.000", connect[4]);

            assertEquals("HANDSHAKE_OK", handshake[1]);
            assertEquals("alice", handshake[2]);

            assertEquals("DISCONNECT", disconnect[1]);
            assertEquals("alice", disconnect[2]);
            assertEquals("QUIT", disconnect[5], "the log records why, not merely that");

            // All three lines agree on the peer, which is what makes them one session.
            assertTrue(connect[3].startsWith("/127.0.0.1:"), connect[3]);
            assertEquals(connect[3], handshake[3]);
            assertEquals(connect[3], disconnect[3]);

            double duration = Double.parseDouble(disconnect[4]);
            assertTrue(duration >= 0.25 && duration < 30.0,
                    "duration should reflect the real session length, was " + duration);
        }

        @Test
        @DisplayName("an abrupt disconnect is still DISCONNECT")
        void abruptDisconnectIsRecorded() throws Exception {
            startServer();

            RawClient alice = connect().helloAs("alice");
            alice.close(); // no QUIT — what Ctrl-C does

            await("cleanup", () -> server.getRegistry().size() == 0);
            server.shutdown();

            assertEquals(List.of("CONNECT", "HANDSHAKE_OK", "DISCONNECT"), allEvents(),
                    render(logRecords()));
        }

        @Test
        @DisplayName("a taken nickname is HANDSHAKE_FAIL, and the attempt is recorded")
        void rejectedHandshakeIsRecordedWithTheAttemptedName() throws Exception {
            startServer();

            connect().helloAs("alice");
            RawClient impostor = connect();
            impostor.send("HELLO 1 alice");
            impostor.expectError(ErrorCode.NICK_TAKEN);
            impostor.expectClosed();

            await("cleanup", () -> server.activeConnections() == 1);
            server.shutdown();

            List<String[]> fails = logRecords().stream()
                    .filter(f -> f[1].equals("HANDSHAKE_FAIL")).toList();
            assertEquals(1, fails.size(), render(logRecords()));
            assertEquals("alice", fails.get(0)[2],
                    "recording the attempted nickname is what makes the log useful for "
                            + "spotting a name being hunted");
            assertTrue(fails.get(0)[5].contains("taken"), fails.get(0)[5]);
        }

        @Test
        @DisplayName("connect and hang up without a HELLO still leaves a trace")
        void silentConnectIsStillAudited() throws Exception {
            startServer();

            RawClient ghost = connect();
            ghost.close();

            await("cleanup", () -> server.activeConnections() == 0);
            server.shutdown();

            List<String[]> records = logRecords();
            assertEquals(List.of("CONNECT", "HANDSHAKE_FAIL"), allEvents(), render(records));
            assertEquals("-", records.get(1)[2]);
        }
    }

    // ================================================================ state machine

    @Nested
    @DisplayName("state machine")
    class States {

        @Test
        @DisplayName("every command is refused before HELLO, and the connection closes")
        void commandsBeforeHelloAreRefused() throws Exception {
            startServer();
            for (String premature : List.of("LIST", "MSG hi", "QUIT", "PM bob hi")) {
                RawClient c = connect();
                c.send(premature);
                c.expectError(ErrorCode.MALFORMED);
                c.expectClosed();
            }
        }

        @Test
        @DisplayName("a second HELLO names the state that refused it")
        void duplicateHelloReportsTheState() throws Exception {
            startServer();
            RawClient alice = connect().helloAs("alice");

            alice.send("HELLO 1 alice2");
            Message err = alice.expectError(ErrorCode.MALFORMED);
            assertTrue(err.body().contains("ACTIVE"),
                    "the diagnostic should say what refused it: " + err.body());

            alice.send("LIST"); // and the connection survives
            assertEquals(List.of("alice"), alice.expect(MessageType.USERS).userList());
        }

        @Test
        @DisplayName("a joined handler is observably ACTIVE")
        void statesAreObservable() throws Exception {
            startServer();
            connect().helloAs("alice");

            await("alice to be ACTIVE", () -> server.getRegistry()
                    .find("alice")
                    .map(h -> h.getState() == ConnectionState.ACTIVE)
                    .orElse(false));
        }

        @Test
        @DisplayName("CLOSING is terminal and no state may be skipped")
        void transitionTableIsClosed() {
            assertTrue(ConnectionState.CONNECTED.canTransitionTo(ConnectionState.NAMED));
            assertTrue(ConnectionState.NAMED.canTransitionTo(ConnectionState.ACTIVE));
            assertTrue(ConnectionState.ACTIVE.canTransitionTo(ConnectionState.CLOSING));

            assertFalse(ConnectionState.CONNECTED.canTransitionTo(ConnectionState.ACTIVE),
                    "skipping NAMED would mean a client is active without a registry entry");
            assertFalse(ConnectionState.ACTIVE.canTransitionTo(ConnectionState.NAMED));

            for (ConnectionState s : ConnectionState.values()) {
                assertFalse(ConnectionState.CLOSING.canTransitionTo(s),
                        "a closing connection must never come back");
                assertFalse(s.canTransitionTo(s), "a self-transition is not a transition");
                assertFalse(s.canTransitionTo(null));
            }
        }

        @Test
        @DisplayName("permits() enforces direction as well as lifecycle")
        void permitsEnforcesDirectionAndLifecycle() {
            assertTrue(ConnectionState.CONNECTED.permits(MessageType.HELLO));
            assertFalse(ConnectionState.CONNECTED.permits(MessageType.MSG));

            assertTrue(ConnectionState.ACTIVE.permits(MessageType.MSG));
            assertTrue(ConnectionState.ACTIVE.permits(MessageType.QUIT));
            assertFalse(ConnectionState.ACTIVE.permits(MessageType.HELLO));

            for (ConnectionState s : ConnectionState.values()) {
                for (MessageType t : MessageType.values()) {
                    if (t.fromServer()) {
                        assertFalse(s.permits(t),
                                t + " is server-to-client and must never be accepted in " + s);
                    }
                }
                assertFalse(s.permits(null), "a null verb is never permitted");
                assertFalse(ConnectionState.CLOSING.permits(MessageType.MSG));
            }
        }
    }

    // ================================================================ capacity

    @Nested
    @DisplayName("capacity and shutdown")
    class Capacity {

        @Test
        @DisplayName("past maxClients, a new connection is closed immediately")
        void serverFullRejects() throws Exception {
            ServerConfig config = new ServerConfig();
            config.maxClients = 1;
            startServer(config);

            connect().helloAs("alice");
            await("alice to occupy the slot", () -> server.activeConnections() == 1);

            RawClient overflow = connect();
            overflow.expectClosed();
        }

        /**
         * The bug this exists for: Phase 4's shutdown iterated the <i>registry</i>, so a
         * connection that never completed a handshake was invisible to it. Its reader
         * thread stayed blocked in {@code read()} — and interrupting a thread parked in
         * blocking socket I/O does nothing, so {@code shutdownNow()} could not help either.
         * Closing the socket is the only mechanism that works, and that requires holding a
         * reference to every live handler, handshaken or not.
         */
        @Test
        @DisplayName("shutdown also closes connections that never said HELLO")
        void shutdownReachesUnhandshakenConnections() throws Exception {
            ServerConfig config = new ServerConfig();
            config.handshakeTimeoutMillis = 60_000;
            startServer(config);

            connect().helloAs("alice");
            RawClient lurker = connect();
            await("both connections to be tracked", () -> server.activeConnections() == 2);

            server.shutdown();

            // Phase 9 added a goodbye line before the close, and it goes to connections
            // that never handshook as well — they are somebody's client sitting at a
            // connect screen, and they deserve to be told too.
            lurker.expectError(ErrorCode.SERVER_SHUTDOWN);
            lurker.expectClosed();
            assertEquals(0, server.activeConnections(),
                    "shutdown must leave no live handler behind");
        }

        @Test
        @DisplayName("shutdown twice is harmless")
        void shutdownIsIdempotent() throws Exception {
            startServer();
            connect().helloAs("alice");
            server.shutdown();
            assertDoesNotThrow(() -> server.shutdown());
        }
    }

    // ============================================================ slow consumer

    @Nested
    @DisplayName("slow consumer")
    class SlowConsumer {

        /**
         * Phase 3 solved the slow-consumer problem for <i>writes</i>; Phase 5 finishes the
         * job for <i>disconnects</i>. The old overflow path called {@code cleanup()} on the
         * broadcaster's thread — which joins the doomed client's writer for up to a second
         * and then broadcasts its {@code LEFT} — so the act of removing a slow consumer
         * stalled exactly the broadcast the outbox exists to keep flowing.
         *
         * <p>The stalled client is made credible with a 512-byte {@code SO_RCVBUF} rather
         * than by suspending a process: the server's send buffer fills within a few
         * kilobytes, the writer thread blocks, the outbox fills, and overflow is reached
         * deterministically instead of after however much data the OS felt like buffering.
         */
        @Test
        @DisplayName("dropping a stalled client does not stall the sender")
        void overflowDisconnectDoesNotBlockTheBroadcaster() throws Exception {
            ServerConfig config = rateLimit(100_000, 1000, 1_000_000);
            config.outboxCapacity = 4;
            startServer(config);

            RawClient sender = connect().helloAs("sender");
            RawClient stalled = track(new RawClient(server.getPort(), 512)).helloAs("stalled");
            sender.expect(MessageType.JOINED);

            String body = "x".repeat(2000);
            long startNanos = System.nanoTime();
            for (int i = 0; i < 200; i++) {
                sender.send("MSG " + i + " " + body);
            }
            long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

            assertTrue(elapsedMillis < 5000,
                    "the broadcaster was stalled for " + elapsedMillis
                            + " ms — a slow consumer must never become everyone's problem");

            await("the stalled client to be dropped",
                    () -> server.getRegistry().find("stalled").isEmpty());

            // And the sender is unharmed: LEFT stalled, then an answer to its own question.
            sender.send("LIST");
            Message m = sender.read();
            if (m.type() == MessageType.LEFT) {
                assertEquals("stalled", m.sender());
                m = sender.read();
            }
            assertEquals(MessageType.USERS, m.type(), "unexpected: " + m);
            assertEquals(List.of("sender"), m.userList());

            stalled.close();
        }
    }

    // ================================================================ regression

    @Nested
    @DisplayName("Phase 4 behaviour is unchanged")
    class NoRegression {

        @Test
        @DisplayName("join, broadcast, list, quit still work end to end")
        void theHappyPathStillWorks() throws Exception {
            startServer();

            RawClient alice = connect().helloAs("alice");
            RawClient bob = connect().helloAs("bob");
            assertEquals("bob", alice.expect(MessageType.JOINED).sender());

            alice.send("MSG hello world how are you");
            Message chat = bob.expect(MessageType.CHAT);
            assertEquals("alice", chat.sender());
            assertEquals("hello world how are you", chat.body());
            assertNotNull(chat.timestamp());

            bob.send("LIST");
            assertEquals(List.of("alice", "bob"), bob.expect(MessageType.USERS).userList());

            bob.send("QUIT");
            bob.expectClosed();
            assertEquals("bob", alice.expect(MessageType.LEFT).sender());
        }

        @Test
        @DisplayName("TOO_LONG is still the only post-handshake condition that disconnects")
        void overLongLineStillDisconnects() throws Exception {
            startServer();
            RawClient alice = connect().helloAs("alice");

            alice.send("MSG " + "a".repeat(5000));
            alice.expectError(ErrorCode.TOO_LONG);
            alice.expectClosed();
        }
    }
}
