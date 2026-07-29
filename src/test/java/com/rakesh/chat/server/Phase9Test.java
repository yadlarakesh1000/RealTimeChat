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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.rakesh.chat.server.TestSupport.await;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 9: heartbeat, graceful shutdown, settings from a file, and the leak audit.
 *
 * <p>The one to read is {@link Resources#twoHundredConnectionsLeaveNothingBehind}. It is the
 * build guide's checkpoint written as an assertion: connect and disconnect 200 clients and
 * prove that the thread count and the connection count come back to where they started.
 * Everything else in this file is a feature test; that one is the phase.
 */
@Timeout(120)
class Phase9Test {

    @TempDir
    Path tmp;

    private ChatServer server;

    /** Starts a server on a free port with a per-test log file. */
    private int startServer(ServerConfig config) throws IOException {
        config.port = 0;
        config.connectionLogPath = tmp.resolve("connections.log");
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

    /**
     * A config whose heartbeat fires in milliseconds rather than half a minute.
     *
     * <p>The idle timeout is pushed far out of the way so these tests only ever exercise
     * the PING path — otherwise a slow machine could reach the idle deadline first and the
     * test would pass for the wrong reason.
     */
    private static ServerConfig fastHeartbeat(int pingMillis, int missedAllowed) {
        ServerConfig config = new ServerConfig();
        config.pingIntervalMillis = pingMillis;
        config.missedPongsBeforeKick = missedAllowed;
        config.idleTimeoutMillis = 60_000;
        return config;
    }

    // =================================================================== heartbeat

    @Nested
    @DisplayName("PING / PONG")
    class Heartbeat {

        @Test
        @DisplayName("a quiet client is asked whether it is still there")
        void aSilentClientGetsPinged() throws Exception {
            int port = startServer(fastHeartbeat(200, 2));

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                // We say nothing at all. Before Phase 9 that meant silence for 15 minutes
                // and then a disconnect; now the server checks in.
                assertEquals(MessageType.PING, alice.read().type());
            }
        }

        @Test
        @DisplayName("answering PONG keeps the connection alive indefinitely")
        void answeringKeepsYouConnected() throws Exception {
            int port = startServer(fastHeartbeat(150, 2));

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                // Four rounds is two more than missedPongsBeforeKick, so a client that
                // answers must outlive a client that does not.
                for (int i = 0; i < 4; i++) {
                    assertEquals(MessageType.PING, alice.read().type(), "ping " + i);
                    alice.send("PONG");
                }

                // Still a working connection, not just a surviving socket.
                alice.send("LIST");
                assertEquals(List.of("alice"), alice.expect(MessageType.USERS).userList());
            }
        }

        @Test
        @DisplayName("ignoring two PINGs ends the connection with ERROR TIMEOUT")
        void anUnansweredHeartbeatEndsTheConnection() throws Exception {
            int port = startServer(fastHeartbeat(150, 2));

            try (RawClient ghost = new RawClient(port).helloAs("ghost")) {
                // This is the unplugged-cable case: the socket is open, the peer is gone,
                // and TCP has no idea. Reading and never replying is exactly what a dead
                // machine's kernel does.
                assertEquals(MessageType.PING, ghost.read().type());
                assertEquals(MessageType.PING, ghost.read().type());

                Message err = ghost.expectError(ErrorCode.TIMEOUT);
                assertTrue(err.body().contains("PING"), err.body());
                ghost.expectClosed();

                await("the dead connection to be released",
                        () -> server.activeConnections() == 0);
            }
        }

        @Test
        @DisplayName("a client that chats never sees a PING at all")
        void realTrafficPostponesTheHeartbeat() throws Exception {
            int port = startServer(fastHeartbeat(400, 2));

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                // Six LISTs at 100 ms apart span 600 ms, well past the 400 ms tick. Any
                // PING in here would be a wasted round trip on a connection we can already
                // see is alive.
                for (int i = 0; i < 6; i++) {
                    alice.send("LIST");
                    assertEquals(MessageType.USERS, alice.read().type());
                    Thread.sleep(100);
                }
            }
        }

        @Test
        @DisplayName("a PONG is not conversation: it does not hold off the idle timeout")
        void pongDoesNotCountAsActivity() throws Exception {
            ServerConfig config = fastHeartbeat(100, 10);   // pings often, kicks late
            config.idleTimeoutMillis = 500;                 // ...so idle must win
            int port = startServer(config);

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                // A dutiful client that answers every heartbeat and never says anything
                // else. If PONG counted as activity this loop would run forever.
                for (int i = 0; i < 20; i++) {
                    Message m = alice.read();
                    if (m.type() == MessageType.ERROR) {
                        assertEquals(ErrorCode.TIMEOUT, m.errorCode());
                        assertTrue(m.body().contains("no traffic"), m.body());
                        return;
                    }
                    assertEquals(MessageType.PING, m.type());
                    alice.send("PONG");
                }
                fail("answering heartbeats kept the connection alive past the idle timeout");
            }
        }

        @Test
        @DisplayName("PONG before HELLO is refused, like every other verb")
        void pongIsNotAWayAroundTheHandshake() throws Exception {
            int port = startServer(new ServerConfig());

            try (RawClient stranger = new RawClient(port)) {
                stranger.send("PONG");
                stranger.expectError(ErrorCode.MALFORMED);
            }
        }

        @Test
        @DisplayName("a client may not send PING - it is a server verb")
        void clientsCannotPing() throws Exception {
            int port = startServer(new ServerConfig());

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                alice.send("PING");
                alice.expectError(ErrorCode.MALFORMED);
            }
        }

        @Test
        @DisplayName("a real ChatClient answers the heartbeat without the GUI seeing it")
        void theRealClientAnswersByItself() throws Exception {
            int port = startServer(fastHeartbeat(150, 2));

            com.rakesh.chat.client.ChatClient client = new com.rakesh.chat.client.ChatClient();
            java.util.concurrent.BlockingQueue<Message> seen =
                    new java.util.concurrent.LinkedBlockingQueue<>();
            client.setListener(seen::add);

            try {
                client.connect("localhost", port, "alice");

                // Long enough for three heartbeat rounds. missedPongsBeforeKick is 2, so an
                // unanswered heartbeat would have closed this connection by now.
                Thread.sleep(600);

                assertTrue(client.isConnected(), "the client failed to answer a heartbeat");
                assertTrue(seen.stream().noneMatch(m -> m.type() == MessageType.PING),
                        "PING reached the listener; the GUI would have printed it");
            } finally {
                client.disconnect();
            }
        }
    }

    // =================================================================== shutdown

    @Nested
    @DisplayName("graceful shutdown")
    class Shutdown {

        @Test
        @DisplayName("clients are told the server is going down before the socket closes")
        void everybodyGetsAGoodbye() throws Exception {
            int port = startServer(new ServerConfig());

            try (RawClient alice = new RawClient(port).helloAs("alice");
                 RawClient bob = new RawClient(port).helloAs("bob")) {

                alice.read(); // JOINED bob

                server.shutdown();

                // Without the goodbye this is just a closed socket, and a planned restart
                // is indistinguishable from a crash.
                assertEquals(ErrorCode.SERVER_SHUTDOWN,
                        alice.expectError(ErrorCode.SERVER_SHUTDOWN).errorCode());
                assertEquals(ErrorCode.SERVER_SHUTDOWN,
                        bob.expectError(ErrorCode.SERVER_SHUTDOWN).errorCode());

                alice.expectClosed();
                bob.expectClosed();
            }
        }

        @Test
        @DisplayName("a client that never said HELLO is closed too")
        void silentConnectionsAreNotForgotten() throws Exception {
            int port = startServer(new ServerConfig());

            try (RawClient lurker = new RawClient(port)) {
                await("the connection to register", () -> server.activeConnections() == 1);

                // The registry only knows about clients that finished the handshake, so
                // this one is only reachable through the server's own liveHandlers set.
                server.shutdown();

                lurker.readTimeout(3000);
                assertEquals(ErrorCode.SERVER_SHUTDOWN,
                        lurker.expectError(ErrorCode.SERVER_SHUTDOWN).errorCode());
                lurker.expectClosed();
            }
        }

        @Test
        @DisplayName("shutdown twice is not an error - the JVM hook and a test both call it")
        void shutdownIsIdempotent() throws Exception {
            int port = startServer(new ServerConfig());

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                server.shutdown();
                assertDoesNotThrow(() -> server.shutdown());
                assertEquals(0, server.activeConnections());
            }
        }

        @Test
        @DisplayName("the shutdown is written to the connection log as KICKED")
        void shutdownIsAudited() throws Exception {
            int port = startServer(new ServerConfig());

            try (RawClient alice = new RawClient(port).helloAs("alice")) {
                server.shutdown();
            }

            List<String> lines = Files.readAllLines(tmp.resolve("connections.log"),
                    StandardCharsets.UTF_8);
            assertTrue(lines.stream().anyMatch(l -> l.contains("KICKED")
                            && l.contains("server shutdown")),
                    "no KICKED / server shutdown record:\n" + String.join("\n", lines));
        }
    }

    // =================================================================== configuration

    @Nested
    @DisplayName("settings from a properties file")
    class Configuration {

        @Test
        void aFileOverridesTheDefaults() throws Exception {
            Path file = write("""
                    port=6001
                    maxClients=7
                    pingIntervalMillis=1234
                    serverName=college-chat
                    """);

            ServerConfig config = ServerConfig.load(file);

            assertEquals(6001, config.port);
            assertEquals(7, config.maxClients);
            assertEquals(1234, config.pingIntervalMillis);
            assertEquals("college-chat", config.serverName);
        }

        @Test
        @DisplayName("keys you leave out keep their defaults")
        void missingKeysAreNotZero() throws Exception {
            ServerConfig defaults = ServerConfig.defaults();
            ServerConfig config = ServerConfig.load(write("port=6002\n"));

            assertEquals(6002, config.port);
            assertEquals(defaults.maxClients, config.maxClients);
            assertEquals(defaults.idleTimeoutMillis, config.idleTimeoutMillis);
            assertEquals(defaults.serverName, config.serverName);
        }

        @Test
        @DisplayName("no file at all is fine - you get the defaults")
        void aMissingFileIsNotAnError() throws Exception {
            ServerConfig config = ServerConfig.load(tmp.resolve("not-here.properties"));
            assertEquals(ServerConfig.defaults().port, config.port);
        }

        @Test
        @DisplayName("a typo in a number stops the server instead of being ignored")
        void nonsenseValuesAreRejectedLoudly() throws Exception {
            Path file = write("maxClients=lots\n");

            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(file));
            assertTrue(e.getMessage().contains("maxClients"), e.getMessage());
        }

        @Test
        @DisplayName("a config file really does change how the server behaves")
        void theFileReachesTheRunningServer() throws Exception {
            ServerConfig config = ServerConfig.load(write("""
                    maxClients=1
                    handshakeTimeoutMillis=60000
                    """));
            int port = startServer(config);

            try (RawClient first = new RawClient(port).helloAs("alice");
                 RawClient second = new RawClient(port)) {

                // maxClients=1 came from the file, so the second connection is refused.
                second.readTimeout(3000);
                second.expectClosed();
            }
        }

        @Test
        @DisplayName("the example file that ships with the project is valid")
        void theExampleFileParses() throws Exception {
            Path example = Path.of("server.properties.example");
            assertTrue(Files.exists(example), "server.properties.example is missing");

            ServerConfig config = ServerConfig.load(example);
            assertEquals(5000, config.port);
            // The passphrase line is commented out on purpose: an example file must never
            // carry a real secret, and this assertion is what keeps it that way.
            assertNull(config.passphrase, "the example file must not contain a passphrase");
        }
    }

    // =================================================================== the leak audit

    @Nested
    @DisplayName("resource audit")
    class Resources {

        /**
         * The build guide's Phase 9 checkpoint.
         *
         * <p>Every connection costs two threads (a pooled reader and a writer), a socket,
         * and an entry in two collections. A leak here is the classic production server
         * bug: nothing looks wrong for the first hour, and then the process runs out of
         * file descriptors at three in the morning.
         *
         * <p>The pool's own 100 threads are created once and stay — that is what a fixed
         * pool is — so the count to watch is the <b>writer</b> threads, one per connection,
         * created and destroyed with it.
         */
        @Test
        @DisplayName("200 connect/disconnect cycles leave no threads and no handlers behind")
        void twoHundredConnectionsLeaveNothingBehind() throws Exception {
            int port = startServer(new ServerConfig());

            int writersAtStart = countWriterThreads();

            for (int i = 0; i < 200; i++) {
                try (RawClient client = new RawClient(port)) {
                    client.send("HELLO 1 user" + i);
                    assertEquals(MessageType.WELCOME, client.read().type(), "cycle " + i);
                    client.send("QUIT");
                }
                // Wait for the server to notice, rather than sleeping and hoping. Without
                // this the test would race ahead of the cleanups and measure a backlog
                // instead of a leak.
                int cycle = i;
                await("cleanup after cycle " + cycle, () -> server.activeConnections() == 0);
            }

            assertEquals(0, server.activeConnections());
            assertEquals(0, server.getRegistry().size(), "a nickname was left registered");

            // Threads die a moment after the code that owns them returns, so give them one.
            await("writer threads to exit", () -> countWriterThreads() <= writersAtStart);
            assertTrue(countWriterThreads() <= writersAtStart,
                    "writer threads leaked: started with " + writersAtStart
                            + ", ended with " + countWriterThreads());
        }

        @Test
        @DisplayName("a client that vanishes without QUIT is cleaned up just the same")
        void abruptDisconnectsAreCleanedUpToo() throws Exception {
            int port = startServer(new ServerConfig());

            for (int i = 0; i < 50; i++) {
                RawClient rude = new RawClient(port);
                rude.send("HELLO 1 rude" + i);
                assertEquals(MessageType.WELCOME, rude.read().type());
                rude.close();   // socket slammed shut, no QUIT, no goodbye
                await("cleanup after rude client " + i,
                        () -> server.activeConnections() == 0);
            }

            assertEquals(0, server.getRegistry().size());
        }

        @Test
        @DisplayName("after shutdown, no reader or writer thread of ours is still running")
        void shutdownLeavesNoThreadsRunning() throws Exception {
            int port = startServer(new ServerConfig());

            List<RawClient> clients = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                clients.add(new RawClient(port).helloAs("user" + i));
            }

            server.shutdown();
            clients.forEach(RawClient::close);

            await("all chat threads to exit", () -> chatThreadNames().isEmpty());
            assertTrue(chatThreadNames().isEmpty(),
                    "threads still running after shutdown: " + chatThreadNames());
        }

        private int countWriterThreads() {
            return (int) chatThreadNames().stream()
                    .filter(name -> name.startsWith("writer-"))
                    .count();
        }

        /**
         * Our own threads, by name. Naming threads in Phase 5 was mostly about readable log
         * lines; here it is what makes the leak measurable at all.
         */
        private Set<String> chatThreadNames() {
            return Thread.getAllStackTraces().keySet().stream()
                    .map(Thread::getName)
                    .filter(name -> name.startsWith("writer-") || name.startsWith("reader-"))
                    .collect(Collectors.toSet());
        }
    }

    // =================================================================== helper

    private Path write(String contents) throws IOException {
        Path file = tmp.resolve("server-" + System.nanoTime() + ".properties");
        Files.writeString(file, contents, StandardCharsets.UTF_8);
        return file;
    }
}
