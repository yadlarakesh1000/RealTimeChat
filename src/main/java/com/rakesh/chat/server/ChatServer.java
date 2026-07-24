package com.rakesh.chat.server;


import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ChatServer {

    private static final int PORT = 5000;
    private static final int MAX_CLIENT_THREADS = 100;

    private final ServerSocket serverSocket;
    private final ExecutorService pool;

    private volatile boolean running = false;

    public ChatServer() throws IOException {
        this.serverSocket = new ServerSocket(PORT);

        // Fixed-size worker pool
        this.pool = Executors.newFixedThreadPool(MAX_CLIENT_THREADS);
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

                pool.submit(new ClientHandler(socket, this));

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