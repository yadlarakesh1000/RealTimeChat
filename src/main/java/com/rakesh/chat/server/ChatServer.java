package com.rakesh.chat.server;

import com.rakesh.chat.common.ErrorCode;
import com.rakesh.chat.common.Message;
import com.rakesh.chat.common.crypto.MessageCrypto;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The acceptor: binds a port, hands each accepted socket to a pooled handler, and owns
 * the two things whose lifetime is the server's rather than a connection's — the
 * {@link ClientRegistry} and the {@link ConnectionLog}.
 */
public class ChatServer {

    private final ServerConfig config;
    private final ServerSocket serverSocket;
    private final ExecutorService pool;
    private final ClientRegistry registry;
    private final ConnectionLog connectionLog;

    /**
     * Phase 8. Built once here, not once per connection: turning the passphrase into a key
     * runs PBKDF2 100,000 times and takes about a tenth of a second, which is exactly what
     * you want when someone is guessing passwords and exactly what you do not want on the
     * accept path. Every handler shares this one object.
     */
    private final MessageCrypto crypto;

    /**
     * Every live connection, including ones that have not said HELLO yet.
     *
     * <p>The registry only knows about clients that finished the handshake, so shutdown
     * could not close a client that connected and then stayed silent — its thread just sat
     * in {@code read()} forever. Interrupting a thread blocked on socket I/O does nothing;
     * closing its socket is the only thing that works, and for that you need a reference to
     * it. Hence this set. (LEARNING-LOG.md, B5.)
     */
    private final Set<ClientHandler> liveHandlers = ConcurrentHashMap.newKeySet();

    private volatile boolean running = false;
    private final AtomicBoolean shutDown = new AtomicBoolean(false);

    public ChatServer() throws IOException {
        this(ServerConfig.defaults());
    }

    /** Convenience for tests: default settings, but on the given port (0 = any free port). */
    public ChatServer(int port) throws IOException {
        this(portOnly(port));
    }

    private static ServerConfig portOnly(int port) {
        ServerConfig config = new ServerConfig();
        config.port = port;
        return config;
    }

    public ChatServer(ServerConfig config) throws IOException {
        this.config = config;

        // setReuseAddress before bind: after a restart the previous socket may still be in
        // TIME_WAIT, and SO_REUSEADDR is what lets the new process bind anyway.
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(config.port));

        // Fixed, not cached: a cached pool creates one thread per connection with no
        // ceiling, so a connection flood becomes an OutOfMemoryError instead of a refusal.
        // A fixed pool degrades by queueing, and the explicit capacity check below turns
        // that queueing into an honest rejection.
        this.pool = Executors.newFixedThreadPool(config.maxClients);
        this.registry = new ClientRegistry();
        this.connectionLog = new ConnectionLog(config.connectionLogPath);
        this.crypto = MessageCrypto.forPassphrase(config.passphrase);
    }

    /** Phase 8. Shared by every {@link ClientHandler}; {@code OFF} when no passphrase is set. */
    public MessageCrypto getCrypto() {
        return crypto;
    }

    public ClientRegistry getRegistry() {
        return registry;
    }

    public ServerConfig getConfig() {
        return config;
    }

    public ConnectionLog getConnectionLog() {
        return connectionLog;
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    /** Live connections, handshaken or not. */
    public int activeConnections() {
        return liveHandlers.size();
    }

    /** Called from {@link ClientHandler#cleanup()} — the single exit path. */
    void handlerClosed(ClientHandler handler) {
        liveHandlers.remove(handler);
    }

    public void start() {
        running = true;

        System.out.println("====================================");
        // getPort(), not config.port: they differ whenever port 0 was requested.
        System.out.println("Server started on port " + getPort());
        System.out.println("Connection log: " + config.connectionLogPath.toAbsolutePath());
        System.out.println(crypto.isOn()
                ? "Encryption:     ON (AES-GCM on message bodies)"
                : "Encryption:     OFF - start with -Dchat.passphrase=... to turn it on");
        System.out.println("Heartbeat:      PING every " + config.pingIntervalMillis
                + " ms, drop after " + config.missedPongsBeforeKick + " unanswered");
        System.out.println("Max clients:    " + config.maxClients);
        System.out.println("Waiting for clients...");
        System.out.println("====================================");

        while (running) {
            try {
                Socket socket = serverSocket.accept();

                // Only the acceptor thread runs this, so size-then-add is not a race.
                if (liveHandlers.size() >= config.maxClients) {
                    ServerLog.warn("REJECTED", String.valueOf(socket.getRemoteSocketAddress()),
                            "server full (" + config.maxClients + ")");
                    closeQuietly(socket);
                    continue;
                }

                ClientHandler handler;
                try {
                    handler = new ClientHandler(socket, this);
                } catch (IOException e) {
                    ServerLog.error("SETUP_FAILED",
                            String.valueOf(socket.getRemoteSocketAddress()), e.getMessage());
                    closeQuietly(socket);
                    continue;
                }

                // Registered before submission: if execute() throws, the handler is
                // already tracked and cleanup() will untrack it. The reverse order leaves
                // a handler that ran but was never tracked.
                liveHandlers.add(handler);
                try {
                    // execute(), not submit(): submit() wraps any throwable in a Future
                    // nobody reads, so a bug in run() would vanish silently.
                    pool.execute(handler);
                } catch (RejectedExecutionException e) {
                    ServerLog.error("REJECTED", handler.getRemoteAddress(), e.getMessage());
                    handler.cleanup(); // untracks, closes the socket, releases the slot
                }

            } catch (SocketException e) {
                // shutdown() closes the ServerSocket, which is the documented way to
                // interrupt a thread blocked in accept() — Thread.interrupt() will not do
                // it. So this exception is expected during shutdown and only alarming
                // otherwise.
                if (running) {
                    ServerLog.error("ACCEPT_FAILED", null, e.getMessage());
                }
            } catch (IOException e) {
                ServerLog.error("ACCEPT_ERROR", null, e.getMessage());
            }
        }

        System.out.println("Acceptor thread stopped.");
    }

    /**
     * Stop accepting, end every live connection, then shut the thread pool down.
     *
     * <p>The order matters:
     * <ol>
     *   <li>{@code running = false}, then close the ServerSocket. The flag is what tells us
     *       the {@code SocketException} that follows was on purpose.</li>
     *   <li>Phase 9: <b>tell everybody first.</b> One {@code ERROR SERVER_SHUTDOWN} line
     *       each, then a short pause so the writer threads can get it out. Without the
     *       pause the sockets close underneath the queued line and a planned restart looks
     *       exactly like a crash.</li>
     *   <li>{@code kick()} every live handler — this is what actually ends the connections,
     *       because the pool's threads are all sitting inside a blocking read.</li>
     *   <li>{@code shutdown()}, {@code awaitTermination()}, {@code shutdownNow()}. All three,
     *       because they handle three different cases: a task that has not started, one that
     *       is finishing, and one that is stuck.</li>
     * </ol>
     */
    public void shutdown() {
        // Idempotent: a JVM shutdown hook and an explicit call in a test will both fire,
        // and double-closing the pool or the log must not throw out of the hook thread.
        if (!shutDown.compareAndSet(false, true)) {
            return;
        }
        running = false;

        System.out.println("\nShutting down server...");

        closeQuietly(serverSocket);

        // Phase 9. Say goodbye before pulling the plug. This is queued, not written here:
        // send() only puts the line on each client's outbox and returns immediately, so one
        // unresponsive client cannot hold up the shutdown of all the others.
        for (ClientHandler handler : liveHandlers) {
            handler.send(Message.error(ErrorCode.SERVER_SHUTDOWN, "server is shutting down"));
        }

        // One shared pause for all of them to be written, rather than waiting on each in
        // turn. If a client is too slow to receive it in this window it loses the notice
        // and sees a plain disconnect — the old behaviour, which is an acceptable worst case.
        sleepQuietly(config.shutdownGraceMillis);

        // kick(), not cleanup(): cleanup() joins each writer for up to a second, so
        // closing 100 clients from this one thread would take 100 seconds. kick() returns
        // immediately and every handler tears itself down on its own thread, in parallel.
        for (ClientHandler handler : liveHandlers) {
            handler.kick("server shutdown");
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Last, so that every DISCONNECT/KICKED record above is still accepted.
        closeQuietly(connectionLog);

        System.out.println("Server stopped.");
    }

    /** Phase 9. A pause that cannot throw out of the shutdown path. */
    private static void sleepQuietly(int millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // Someone wants us to hurry up. Skip the grace period, but do not swallow the
            // interrupt — the code after us may want to know.
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // shutdown path: nothing useful left to do about it
        }
    }

    public static void main(String[] args) throws Exception {
        // Phase 9. Settings come from a file now, so changing the port or the client limit
        // no longer means editing Java and recompiling. A missing file is fine: you get the
        // defaults, exactly as in Phases 1-8.
        Path configFile = Path.of(
                System.getProperty("chat.config", ServerConfig.DEFAULT_FILE.toString()));
        ServerConfig config = ServerConfig.load(configFile);
        System.out.println(Files.exists(configFile)
                ? "Settings:       " + configFile.toAbsolutePath()
                : "Settings:       defaults (no " + configFile + ")");

        // Phase 8. Two ways in, because a system property is easy from Maven
        // (-Dchat.passphrase=...) and an environment variable is what you would really use,
        // since anyone running `ps` can read a command line but not another user's env.
        //
        // Phase 9: both still beat the file. A secret typed on the command line lives for
        // one run; a secret in a file lives until someone commits it by accident.
        String override = System.getProperty("chat.passphrase", System.getenv("CHAT_PASSPHRASE"));
        if (override != null && !override.isBlank()) {
            config.passphrase = override;
        }

        ChatServer server = new ChatServer(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown-hook"));
        server.start();
    }
}
