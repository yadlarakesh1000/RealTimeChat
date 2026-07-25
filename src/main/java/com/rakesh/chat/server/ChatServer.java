package com.rakesh.chat.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatServer {

    private static final int PORT = 5000;
    private static final int MAX_CLIENT_THREADS = 100;

    private final ServerSocket serverSocket;
    private final ExecutorService pool;
    private final ClientRegistry registry;
    private final AtomicInteger activeConnections = new AtomicInteger(0);

    private volatile boolean running = false;

    public ChatServer() throws IOException {
        this(PORT);
    }

    public ChatServer(int port) throws IOException {
        // Set reuseAddress before binding to prevent bind errors during fast restarts
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new java.net.InetSocketAddress(port));

        // Fixed-size worker pool
        this.pool = Executors.newFixedThreadPool(MAX_CLIENT_THREADS);
        this.registry = new ClientRegistry();
    }

    public ClientRegistry getRegistry() {
        return registry;
    }

    public void decrementActiveConnections() {
        activeConnections.decrementAndGet();
    }

    public void start() {

        running = true;

        System.out.println("====================================");
        System.out.println("Server started on port " + PORT);
        System.out.println("Waiting for clients...");
        System.out.println("====================================");

        while (running) {

            try {

                Socket socket = serverSocket.accept();

                System.out.println(
                        "[CONNECTED] "
                                + socket.getRemoteSocketAddress());

                // Capacity rejection check before submitting to thread pool
                if (activeConnections.get() >= MAX_CLIENT_THREADS) {
                    System.err.println("Server full. Rejecting: " + socket.getRemoteSocketAddress());
                    try {
                        socket.close();
                    } catch (IOException ignored) {}
                    continue;
                }

                try {
                    activeConnections.incrementAndGet();
                    // Switch to execute() to prevent silent execution failures
                    pool.execute(new ClientHandler(socket, this));
                } catch (IOException | RejectedExecutionException e) {
                    activeConnections.decrementAndGet();
                    System.err.println(
                            "Client setup failed: " + e.getMessage());
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }

            } catch (SocketException e) {

                // Happens when shutdown() closes the ServerSocket.

                if (running) {
                    System.err.println("Accept failed: " + e.getMessage());
                }

            } catch (IOException e) {

                System.err.println("Accept error: " + e.getMessage());

            }

        }

        System.out.println("Acceptor thread stopped.");

    }

    public void shutdown() {

        running = false;

        System.out.println("\nShutting down server...");

        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }

        // Close all client sockets (this unblocks client reader threads)
        for (ClientHandler handler : registry.all()) {
            handler.cleanup();
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

        System.out.println("Server stopped.");

    }

    public static void main(String[] args) throws Exception {

        ChatServer server = new ChatServer();

        Runtime.getRuntime().addShutdownHook(
                new Thread(server::shutdown));

        server.start();

    }

}