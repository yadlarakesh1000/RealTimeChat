package com.rakesh.chat.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
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
     * <b>Every</b> live connection, including those that have not completed a handshake.
     *
     * <p>Phase 4 tracked only a count, and shutdown iterated the <i>registry</i> — which
     * by construction contains only clients that already said {@code HELLO}. A peer that
     * connected and stayed silent was therefore invisible to shutdown: its reader thread
     * stayed blocked in {@code read()}, {@code awaitTermination} timed out, and
     * {@code shutdownNow()} could not help either, because interrupting a thread parked in
     * blocking socket I/O does nothing. Closing its socket is the only thing that does,
     * and you cannot close a socket you are not holding a reference to.
     *
     * <p>{@code newKeySet()} is a {@code ConcurrentHashMap} in disguise: add and remove are
     * called from many threads and the only read is the shutdown sweep.
     */
    private final Set<ClientHandler> liveHandlers = ConcurrentHashMap.newKeySet();

    private volatile boolean running = false;
    private final AtomicBoolean shutDown = new AtomicBoolean(false);

    public ChatServer() throws IOException {
        this(ServerConfig.defaults());
    }

    /** Convenience for tests: default configuration on {@code port} (0 = ephemeral). */
    public ChatServer(int port) throws IOException {
        this(ServerConfig.defaults().withPort(port));
    }

    public ChatServer(ServerConfig config) throws IOException {
        this.config = config;

        // setReuseAddress before bind: after a restart the previous socket may still be in
        // TIME_WAIT, and SO_REUSEADDR is what lets the new process bind anyway.
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(config.port()));

        // Fixed, not cached: a cached pool creates one thread per connection with no
        // ceiling, so a connection flood becomes an OutOfMemoryError instead of a refusal.
        // A fixed pool degrades by queueing, and the explicit capacity check below turns
        // that queueing into an honest rejection.
        this.pool = Executors.newFixedThreadPool(config.maxClients());
        this.registry = new ClientRegistry();
        this.connectionLog = new ConnectionLog(config.connectionLogPath());
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
        // getPort(), not config.port(): they differ whenever port 0 was requested.
        System.out.println("Server started on port " + getPort());
        System.out.println("Connection log: " + config.connectionLogPath().toAbsolutePath());
        System.out.println("Waiting for clients...");
        System.out.println("====================================");

        while (running) {
            try {
                Socket socket = serverSocket.accept();

                // Only the acceptor thread runs this, so size-then-add is not a race.
                if (liveHandlers.size() >= config.maxClients()) {
                    ServerLog.warn("REJECTED", String.valueOf(socket.getRemoteSocketAddress()),
                            "server full (" + config.maxClients() + ")");
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
     * Stop accepting, end every live connection, then wind down the pool.
     *
     * <p>The order matters and each step exists for a different reason:
     * <ol>
     *   <li>{@code running = false} then close the {@code ServerSocket} — the flag makes
     *       the resulting {@code SocketException} recognisable as intentional.</li>
     *   <li>{@code kick()} every live handler. This is what actually ends the connections;
     *       the pool cannot, because its threads are parked in blocking reads.</li>
     *   <li>{@code shutdown()} refuses new tasks and lets running ones finish;
     *       {@code awaitTermination} gives them a bounded window; {@code shutdownNow()}
     *       interrupts whatever is left. All three, because each handles a different
     *       failure: a task that has not started, one that is finishing, one that is stuck.</li>
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

    private static void closeQuietly(AutoCloseable c) {
        try {
            c.close();
        } catch (Exception ignored) {
            // shutdown path: nothing useful left to do about it
        }
    }

    public static void main(String[] args) throws Exception {
        ChatServer server = new ChatServer();
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown-hook"));
        server.start();
    }
}
